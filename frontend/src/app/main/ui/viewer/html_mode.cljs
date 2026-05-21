;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.html-mode
  "HTML Mode section of the viewer.

   The component renders the current Penpot page as HTML (via
   `@penpot/html-converter`, fed by `app.main.data.html-mode.adapter`)
   inside a sandboxed iframe, alongside a CSS inspector sidebar. The
   converter output is cached by (file-id, page-id, file-revn) in
   `app.main.data.html-mode.cache`; the cache is invalidated naturally
   when the workspace persists edits (revn advances).

   ## iframe threat model

   The iframe is rendered with `sandbox=\"allow-scripts allow-same-origin\"`.
   Same-origin is needed so the iframe can fetch fonts and image
   assets from Penpot's own URLs with the user's session credentials
   — without it the iframe has an opaque origin and cross-origin
   requests for fonts fail, causing text shapes to render with system
   fallback fonts and overflow their measured bounds.

   Note that per the HTML spec, `allow-scripts allow-same-origin` is
   effectively *no sandbox* — scripts inside the iframe can call
   `parent.document.querySelector('iframe').removeAttribute('sandbox')`
   and escape entirely. We accept that trade-off because:

   - **The only script in the iframe is ours.** `select-bridge-script`
     below is the entire JS payload; the converter output is style-only
     and never emits `<script>` tags. If a future converter version
     started emitting third-party scripts, this calculus must be
     revisited.

   - **The converter is vendored and audited.** It is in-tree at
     `frontend/vendor/penpot-html-converter`, snapshotted from a
     pinned commit, and refreshed only via `scripts/sync-html-converter.sh`.

   - **postMessage validation.** The parent listens for
     `penpot:html-mode:select` / `:deselect` messages but validates
     (a) the payload is a JS object and (b) the `type` field matches
     one of our prefixes. Any other message is ignored. If destructive
     actions ever appear in the sidebar (e.g. \"delete shape\"),
     origin validation becomes mandatory.

   Image fills are routed through `cf/resolve-file-media`, hitting
   `/assets/by-file-media-id/<id>` with the user's session cookie.

   ## Phase history

   Phase 1 added the toolbar button and an empty section; Phase 2
   vendored the converter and adapter; Phase 3 wired the iframe;
   Phase 4 added the inspector sidebar; Phase 5 inlined `@font-face`
   rules from Penpot's font system; Phase 6 added the cache and
   live refresh; Phase 7 hardened tests and a11y."
  (:require-macros [app.main.style :as stl])
  (:require
   ["@penpot/html-converter" :as cv]
   [app.main.data.html-mode :as dhtml]
   [app.main.data.html-mode.adapter :as adapter]
   [app.main.data.html-mode.cache :as cache]
   [app.main.data.html-mode.converter-ctx :as cctx]
   [app.main.fonts :as fonts]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.layout.tab-switcher :refer [tab-switcher*]]
   [app.main.ui.viewer.html-mode.design-tokens :refer [design-tokens-view*]]
   [app.main.ui.viewer.html-mode.export-modal]
   [app.main.ui.viewer.html-mode.layers-tree :refer [layers-tree*]]
   [app.main.ui.viewer.html-mode.sidebar :refer [html-mode-sidebar*]]
   [app.util.i18n :refer [tr]]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

;; ---------------------------------------------------------------------------
;; Converter wiring
;;
;; The JS context builder lives in `app.main.data.html-mode.converter-ctx`
;; so it can be shared with the Export modal without dragging the renderer
;; into the modal's require graph.

;; ---------------------------------------------------------------------------
;; Fonts
;;
;; The iframe is rendered with an opaque origin (sandbox without
;; allow-same-origin), so its CSS must inline every `@font-face` rule
;; for the text shapes on the page. We reuse Penpot's own font system
;; (`app.main.fonts`) instead of the converter's `buildPenpotFontsCss`
;; because that upstream helper assumes a `/internal/gfonts/...` proxy
;; that Penpot's backend does not expose.

(defn- extract-page-fonts
  "Walk every text shape on the page and collect the unique set of font
   refs used by their content. Returns a set of
   `{:font-id :font-variant-id :font-weight :font-style}` maps."
  [page]
  (->> (vals (:objects page))
       (filter #(= (:type %) :text))
       (mapcat (comp fonts/get-content-fonts :content))
       (into #{})))

(defn- typography->font-ref
  "Project a library typography map onto the same `{:font-id
   :font-variant-id :font-weight :font-style}` shape that the page
   font extractor produces. Used so leaves referring to a typography
   still pull the right `@font-face` rule into the iframe."
  [typo]
  (select-keys typo [:font-id :font-variant-id :font-weight :font-style]))

(defn- extract-file-fonts
  "Collect font refs from every typography defined on the file. Text
   leaves that only carry a `:typography-ref-id` would otherwise leave
   the page extractor blind to the actual font, so we inline every
   library typography font preemptively."
  [file]
  (let [typos (or (get-in file [:data :typographies])
                  (get file :typographies))]
    (into #{} (keep (comp typography->font-ref second)) typos)))

(defn- render-fonts-css-async
  "Resolve a Promise of the concatenated `@font-face` CSS for the page's
   fonts. On error or with no fonts the Promise resolves to an empty
   string — fonts are best-effort and must never break the preview."
  [file page]
  (let [refs (into (extract-page-fonts page) (extract-file-fonts file))]
    (if (empty? refs)
      (js/Promise.resolve "")
      (js/Promise.
       (fn [resolve _]
         ;; `rx/subs!` is `subscribe` with inverted arguments — the
         ;; observable goes LAST so it composes with `->>`.
         (->> (fonts/render-font-styles refs)
              (rx/subs! resolve
                        (fn [^js err]
                          (js/console.warn "HTML Mode font CSS failed:" err)
                          (resolve "")))))))))

(defn- render-page-html
  "Resolve a Promise of `{:html, :fonts-css, :tokens-css}` for the given
   page. The converter call and the font CSS build run in parallel
   because each is independent.

   `:tokens-css` is the `:root { --name: value; … }` block that defines
   every design-token CSS custom property the page references. It must
   be inlined into the iframe's `<style>` block — otherwise the
   `var(--token, fallback)` references the converter emits resolve to
   the fallback (or to `inherit` when no fallback is present), which is
   why typography-token-driven text used to render invisible."
  [file page]
  (let [js-page (adapter/->js-page page)
        ctx    (cctx/converter-context file js-page)
        tokens (.-tokens ctx)
        tokens-css (when (and tokens (pos? (.-size tokens))) (cv/tokensToCss tokens))]
    (-> (js/Promise.all
         #js [(-> (js/Promise.resolve)
                  (.then (fn [] (cv/convertPage js-page ctx)))
                  (.then (fn [^js result] (.-html result))))
              (render-fonts-css-async file page)])
        (.then (fn [^js parts]
                 {:html       (aget parts 0)
                  :fonts-css  (aget parts 1)
                  :tokens-css (or tokens-css "")})))))

(defn- render-page-html-cached
  [file page]
  (let [k (cache/key-for file page)]
    (if-let [hit (cache/get! k)]
      (js/Promise.resolve hit)
      (-> (render-page-html file page)
          (.then (fn [result]
                   (cache/put! k result)
                   result))))))

;; Forward declarations for helpers used by the prototype renderer
;; that live in the next section (the HTML document wrapper).
(declare page-background)

;; ---------------------------------------------------------------------------
;; Prototype mode rendering
;;
;; Prototype mode shows ONE board at a time, picked by the URL
;; `?index=` query param (driven by the existing viewer header
;; thumbnails / pagination). The board renders via `convertShape` —
;; just static HTML; clicks do nothing (no interactions wired here
;; yet). When `index` changes, the iframe rebuilds with the new
;; board.

(defn- render-board-html
  "Resolve a Promise of `{:html :fonts-css :tokens-css}` for the
   currently-selected board. Mirrors `render-page-html` but calls
   `cv/convertShape` so only the requested frame and its descendants
   are converted."
  [file page frame]
  (let [js-page    (adapter/->js-page page)
        ctx        (cctx/converter-context file js-page)
        js-objects (.-objects js-page)
        js-shape   (unchecked-get js-objects (str (:id frame)))
        tokens     (.-tokens ctx)
        tokens-css (when (and tokens (pos? (.-size tokens))) (cv/tokensToCss tokens))]
    (-> (js/Promise.all
         #js [(-> (js/Promise.resolve)
                  (.then (fn [] (cv/convertShape js-shape js-objects ctx)))
                  (.then (fn [^js result] (.-html result))))
              (render-fonts-css-async file page)])
        (.then (fn [^js parts]
                 {:html       (aget parts 0)
                  :fonts-css  (aget parts 1)
                  :tokens-css (or tokens-css "")})))))

;; ---------------------------------------------------------------------------
;; HTML document wrapper

(defn- page-background
  [page]
  (or (get-in page [:options :background]) "#ffffff"))

(defn- find-root-frame
  "Locate a page's root frame — the synthetic frame whose `:parent-id`
   equals its own `:id`. Every Penpot page has exactly one."
  [objects]
  (some (fn [shape]
          (when (and (some? shape)
                     (= (:id shape) (:parent-id shape)))
            shape))
        (vals objects)))

(defn- page-bounds
  "Bounding rect (in canvas coords) that contains every top-level shape
   on the page. Returns `{:min-x :min-y :width :height}`, or `nil` when
   the page has no rendered children. The iframe builder uses this to
   translate the converter output to the origin and centre it inside
   the preview viewport."
  [page]
  (let [objects (get page :objects)
        root    (find-root-frame objects)
        rects   (when root
                  (->> (:shapes root)
                       (keep #(get objects %))
                       (keep :selrect)))]
    (when (seq rects)
      (let [xs  (map :x rects)
            ys  (map :y rects)
            x2s (map :x2 rects)
            y2s (map :y2 rects)]
        {:min-x  (apply min xs)
         :min-y  (apply min ys)
         :width  (- (apply max x2s) (apply min xs))
         :height (- (apply max y2s) (apply min ys))}))))

(def ^:private select-bridge-script
  ;; Inline JS executed inside the iframe. Mirrors the Inspect Mode of
  ;; the viewer: pink hover outline + purple selected outline + white
  ;; W×H label below each, plus pink distance pills (top/right/bottom/
  ;; left) between the hovered element and the selected parent — the
  ;; same colours and conventions as the SVG-based Inspect renderer in
  ;; `app.main.ui.inspect.selection-feedback` / `app.main.ui.measurements`.
  ;;
  ;; Runs in the iframe's opaque-origin sandbox; CSS variables from the
  ;; parent don't reach here so the Penpot DS accent values are hardcoded
  ;; (--color-accent-tertiary, --color-accent-quaternary).
  (str
   "(function(){"
   ;; ---- colours / sizes ----
   "var SEL='#8c33eb';"     ;; --color-accent-tertiary  (selected outline)
   "var HOV='#ff6fe0';"     ;; --color-accent-quaternary (hover outline + distance pills)
   "var BAR=16;"            ;; pill / label height in px
   "var FONT='11px/16px \"Helvetica Neue\",Arial,sans-serif';"

   ;; ---- styles ----
   "var s=document.createElement('style');"
   "s.textContent='"
   ".penpot-hm-overlay{position:absolute;pointer-events:none;box-sizing:border-box;z-index:2147483647;display:none}"
   ".penpot-hm-hover{outline:1px solid '+HOV+';outline-offset:-1px}"
   ".penpot-hm-selected{outline:1px solid '+SEL+';outline-offset:-1px}"
   ".penpot-hm-dim{position:absolute;left:50%;transform:translateX(-50%);top:100%;margin-top:4px;background:#fff;color:#000;font:'+FONT+';padding:0 6px;height:'+BAR+'px;border-radius:2px;white-space:nowrap;pointer-events:none;box-shadow:0 1px 2px rgba(0,0,0,0.15)}"
   ".penpot-hm-pill{position:absolute;background:'+HOV+';color:#fff;font:'+FONT+';padding:0 6px;height:'+BAR+'px;line-height:'+BAR+'px;border-radius:'+(BAR/2)+'px;white-space:nowrap;pointer-events:none;z-index:2147483647;display:none;transform:translate(-50%,-50%)}"
   "';"
   "document.head.appendChild(s);"

   ;; ---- overlay + label DOM ----
   "function mk(cls){var d=document.createElement('div');d.className='penpot-hm-overlay '+cls;document.body.appendChild(d);return d;}"
   "var hoverEl=mk('penpot-hm-hover');"
   "var hoverLabel=document.createElement('div');hoverLabel.className='penpot-hm-dim';hoverEl.appendChild(hoverLabel);"
   "var selEl=mk('penpot-hm-selected');"
   "var selLabel=document.createElement('div');selLabel.className='penpot-hm-dim';selEl.appendChild(selLabel);"

   ;; ---- distance pills (top / right / bottom / left) ----
   "function pill(){var p=document.createElement('div');p.className='penpot-hm-pill';document.body.appendChild(p);return p;}"
   "var pTop=pill(),pRight=pill(),pBottom=pill(),pLeft=pill();"

   "var currentSel=null;"
   ;; The shape the user has drilled INTO via double-click. Subsequent
   ;; single clicks pick its direct child on the cursor's ancestor
   ;; chain — same semantics as Figma's "Enter to go down a level".
   ;; `null` means we're at the root (top-level shapes).
   "var drillParent=null;"
   ;; Tracks whether Control/Cmd is held; Ctrl+hover previews the
   ;; deepest shape (Figma-style "select inside") instead of the
   ;; outer-most one. Updated by global key listeners further down.
   "var ctrlHeld=false;"

   ;; ---- helpers ----
   "function send(p){try{parent.postMessage(p,'*');}catch(e){}}"

   ;; Walk up from a DOM target collecting every ancestor element that
   ;; carries a `data-id` — i.e. every Penpot shape in the hit-test
   ;; path. The returned array is ordered SHALLOWEST → DEEPEST so the
   ;; selection helpers below can index into it intuitively
   ;; (index 0 = top-level, last index = leaf the cursor is actually
   ;; over).
   "function chainAt(t){"
   "  var chain=[], el=t;"
   "  while(el && el!==document.body){"
   "    if(el.hasAttribute && el.hasAttribute('data-id')){chain.unshift(el);}"
   "    el=el.parentElement;"
   "  }"
   "  return chain;"
   "}"

   ;; Top-of-tree shape under the cursor (used by plain hover / single
   ;; click). Returns null when the cursor isn't over any shape.
   "function topShapeAt(t){var c=chainAt(t);return c[0]||null;}"

   ;; Deepest shape under the cursor (used by Ctrl-hover / Ctrl-click).
   "function deepShapeAt(t){var c=chainAt(t);return c[c.length-1]||null;}"

   ;; Resolve the shape a plain click should select given the current
   ;; drill context:
   ;;   • drillParent is null → top of the cursor's chain (root level).
   ;;   • drillParent is on the cursor's chain → the direct child of
   ;;     drillParent on that chain (one level deeper than drillParent).
   ;;   • drillParent is NOT on the cursor's chain → the user clicked
   ;;     outside the drilled subtree. Pop back to the root and select
   ;;     the new top-level. Figma does the same: clicking elsewhere
   ;;     breaks out of the previous frame.
   ;;
   ;; The chain index is read by reference equality on the element,
   ;; not on the data-id, so re-rendered iframes don't accidentally
   ;; preserve a stale drill context.
   "function selectAtDrill(target){"
   "  var c=chainAt(target);"
   "  if(!c.length) return {shape:null,reset:true};"
   "  if(!drillParent) return {shape:c[0],reset:false};"
   "  var idx=c.indexOf(drillParent);"
   "  if(idx===-1) return {shape:c[0],reset:true};"
   "  return {shape:c[idx+1]||c[idx],reset:false};"
   "}"

   "function fmt(n){var r=Math.round(n*100)/100;return (Math.round(r)===r)?String(Math.round(r)):r.toFixed(2);}"
   "function place(overlay,label,el){"
   "  var r=el.getBoundingClientRect();"
   "  overlay.style.left=(r.left+window.scrollX)+'px';"
   "  overlay.style.top=(r.top+window.scrollY)+'px';"
   "  overlay.style.width=r.width+'px';"
   "  overlay.style.height=r.height+'px';"
   "  overlay.style.display='block';"
   "  if(label){label.textContent=fmt(r.width)+' x '+fmt(r.height);}"
   "}"
   "function hide(o){o.style.display='none';}"
   "function showPill(p,cx,cy,text){"
   "  p.textContent=text;"
   "  p.style.left=(cx+window.scrollX)+'px';"
   "  p.style.top=(cy+window.scrollY)+'px';"
   "  p.style.display='block';"
   "}"
   "function hidePills(){hide(pTop);hide(pRight);hide(pBottom);hide(pLeft);}"

   ;; ---- distance pills between hover and selected ----
   "function placeDistances(hoverElNode){"
   "  if(!currentSel || !hoverElNode || hoverElNode===currentSel){hidePills();return;}"
   "  var H=hoverElNode.getBoundingClientRect();"
   "  var S=currentSel.getBoundingClientRect();"
   "  var top=H.top-S.top, right=S.right-H.right, bottom=S.bottom-H.bottom, left=H.left-S.left;"
   "  var hcX=H.left+H.width/2, hcY=H.top+H.height/2;"
   "  if(top>0.5){showPill(pTop,hcX,(H.top+S.top)/2,fmt(top)+'px');}else{hide(pTop);}"
   "  if(bottom>0.5){showPill(pBottom,hcX,(H.bottom+S.bottom)/2,fmt(bottom)+'px');}else{hide(pBottom);}"
   "  if(left>0.5){showPill(pLeft,(H.left+S.left)/2,hcY,fmt(left)+'px');}else{hide(pLeft);}"
   "  if(right>0.5){showPill(pRight,(H.right+S.right)/2,hcY,fmt(right)+'px');}else{hide(pRight);}"
   "}"

   ;; ---- hover ----
   ;; Hover preview mirrors what the next click would select:
   ;;   • Ctrl-hover         → deepest shape (matches Ctrl+click).
   ;;   • Plain hover w/ drill → direct child of drillParent at cursor.
   ;;   • Plain hover, no drill → top-of-tree shape (matches single click).
   ;; A subsequent double-click drills one level further from there.
   ;;
   ;; `lastMoveTarget` is remembered so we can refresh the highlight
   ;; instantly when Ctrl is pressed/released or when the drill
   ;; context changes — without waiting for the user to nudge the
   ;; cursor.
   "var currentHover=null;"
   "var lastMoveTarget=null;"
   "function hoverAt(target){"
   "  if(ctrlHeld) return deepShapeAt(target);"
   "  return selectAtDrill(target).shape;"
   "}"
   "function refreshHover(target){"
   "  lastMoveTarget=target;"
   "  var el=target?hoverAt(target):null;"
   "  currentHover=el;"
   "  if(!el || el===currentSel){hide(hoverEl);hidePills();return;}"
   "  place(hoverEl,hoverLabel,el);"
   "  placeDistances(el);"
   "}"
   "document.addEventListener('mousemove',function(e){refreshHover(e.target);},{capture:true});"
   "document.addEventListener('mouseleave',function(){hide(hoverEl);hidePills();currentHover=null;lastMoveTarget=null;});"

   ;; ---- click ----
   ;; Selection rules (Figma-style):
   ;;   • Plain single click → child of `drillParent` at cursor (or
   ;;     top-of-tree when no drill context).
   ;;   • Double click       → drill INTO whatever the first click of
   ;;     the dbl-click just selected, then re-resolve the click — so
   ;;     the next-deeper shape ends up selected and subsequent SINGLE
   ;;     clicks operate at that new level.
   ;;   • Triple/Nth click   → keep drilling, one level per click.
   ;;   • Ctrl / Cmd + click → deepest shape, drill context cleared.
   ;; `MouseEvent.detail` carries the click count (1, 2, 3, …) so we
   ;; don't need a separate dblclick listener.
   "document.addEventListener('click',function(e){"
   "  var chain=chainAt(e.target);"
   "  var el;"
   "  if(e.ctrlKey || e.metaKey){"
   "    drillParent=null;"
   "    el=chain[chain.length-1]||null;"
   "  } else if(e.detail>=2){"
   ;; The detail=1 click ran just before us and set currentSel based
   ;; on the OLD drillParent. Promote currentSel to be the new
   ;; drillParent (drilling INTO it), then re-resolve.
   "    if(currentSel) drillParent=currentSel;"
   "    var pick=selectAtDrill(e.target);"
   "    if(pick.reset) drillParent=null;"
   "    el=pick.shape;"
   "  } else {"
   "    var pick1=selectAtDrill(e.target);"
   "    if(pick1.reset) drillParent=null;"
   "    el=pick1.shape;"
   "  }"
   "  if(!el){currentSel=null;drillParent=null;hide(selEl);hide(hoverEl);hidePills();send({type:'penpot:html-mode:deselect'});return;}"
   "  e.preventDefault();e.stopPropagation();"
   "  currentSel=el;"
   "  hide(hoverEl);"
   "  hidePills();"
   "  place(selEl,selLabel,el);"
   "  send({"
   "    type:'penpot:html-mode:select',"
   "    id:el.getAttribute('data-id'),"
   "    shapeType:el.getAttribute('data-type'),"
   "    shapeName:el.getAttribute('data-name'),"
   "    style:el.getAttribute('style')||'',"
   "    tag:el.tagName.toLowerCase()"
   "  });"
   "},{capture:true});"

   ;; ---- keep overlays aligned on scroll/resize ----
   "function reposition(){"
   "  if(currentSel){place(selEl,selLabel,currentSel);}"
   "  if(currentHover && currentHover!==currentSel){place(hoverEl,hoverLabel,currentHover);placeDistances(currentHover);}"
   "}"
   "window.addEventListener('scroll',reposition,true);"
   "window.addEventListener('resize',reposition);"
   ;; ---- parent -> iframe: select-by-id ----
   ;; Triggered by the layers tree clicks. Looks up the element with
   ;; the matching `data-id` and runs the same selection flow as a
   ;; native iframe click.
   "window.addEventListener('message',function(e){"
   "  var d=e.data;"
   "  if(!d || typeof d!=='object'){return;}"
   "  if(d.type==='penpot:html-mode:select-by-id'){"
   ;; Layers-tree → iframe selection. `d.ids` may be either a single
   ;; id (legacy / single click) carried on `d.id`, or an array of
   ;; ids (multi-selection). `d.fit`: when truthy the viewport zooms
   ;; so the framed shape(s) fill the visible area with breathing
   ;; room — driven by Ctrl-click on a tree row.
   "    var ids=d.ids;"
   "    if(!ids){ ids = d.id ? [d.id] : []; }"
   "    var els=ids.map(function(id){return document.querySelector('[data-id=\"'+id+'\"]');})"
   "      .filter(function(el){return !!el;});"
   "    if(!els.length){return;}"
   ;; Track the FIRST element as the primary selection so the
   ;; overlay & sidebar reflect a single shape. (Multi-selection
   ;; overlays aren't part of HTML Mode yet — we centre on the
   ;; union but still emit one selection message.)
   "    var el=els[0];"
   "    currentSel=el;"
   ;; Selecting via the layers tree bypasses the iframe's drill
   ;; context — the tree can jump to any depth, so we reset the drill
   ;; parent so subsequent in-iframe single clicks behave predictably
   ;; (start from the root again).
   "    drillParent=null;"
   "    hide(hoverEl);"
   "    hidePills();"
   "    place(selEl,selLabel,el);"
   "    centerOnElements(els, !!d.fit, d.padding);"
   ;; Re-place after the transform changed so the outline lands
   ;; on the now-shifted element.
   "    place(selEl,selLabel,el);"
   "    send({"
   "      type:'penpot:html-mode:select',"
   "      id:el.getAttribute('data-id'),"
   "      shapeType:el.getAttribute('data-type'),"
   "      shapeName:el.getAttribute('data-name'),"
   "      style:el.getAttribute('style')||'',"
   "      tag:el.tagName.toLowerCase()"
   "    });"
   "    return;"
   "  }"
   ;; Zoom commands forwarded by the parent's keydown handler. Done at
   ;; this layer (rather than relying on `Ctrl + +/-` inside the iframe)
   ;; so the host browser never sees the shortcut and never fires its
   ;; native page-zoom — preventDefault inside the iframe is unreliable
   ;; for those keys when focus is not inside the sandboxed document.
   "  if(d.type==='penpot:html-mode:zoom'){"
   "    if(d.action==='in'){zoomBy(1.1);}"
   "    else if(d.action==='out'){zoomBy(1/1.1);}"
   "    else if(d.action==='reset'){resetView();}"
   "    return;"
   "  }"
   "});"

   ;; ---- pan & zoom -------------------------------------------------
   ;; Mirrors Penpot's workspace canvas controls:
   ;;   • middle-mouse drag          → pan
   ;;   • space + left-mouse drag    → pan (Figma/Penpot convention)
   ;;   • ctrl/cmd + '+' or '='      → zoom in
   ;;   • ctrl/cmd + '-'             → zoom out
   ;;   • ctrl/cmd + '0'             → reset (1× zoom, centred)
   ;; The transform is applied to `.penpot-hm-canvas`; overlays use
   ;; `getBoundingClientRect()` which already reflects the post-transform
   ;; layout, so they stay anchored automatically.
   "var hmCanvas=null;"
   "var panX=0,panY=0,zoom=1;"
   "var spaceDown=false,dragging=false;"
   "var dStartX=0,dStartY=0,pStartX=0,pStartY=0;"
   "function ensureCanvas(){if(!hmCanvas){hmCanvas=document.querySelector('.penpot-hm-canvas');}return hmCanvas;}"
   "function applyT(){var c=ensureCanvas();if(c){c.style.transform='translate('+panX+'px,'+panY+'px) scale('+zoom+')';}}"
   "function zoomBy(f){zoom=Math.max(0.1,Math.min(8,zoom*f));applyT();reposition();}"
   "function resetView(){panX=0;panY=0;zoom=1;applyT();reposition();}"

   ;; Pan so that the union bounding rect of `rects` lands at the
   ;; centre of the viewport, without changing the zoom level. `rects`
   ;; are viewport-space rects (i.e. what `getBoundingClientRect()`
   ;; returns AFTER the current transform). One shape → single rect;
   ;; multi-selection → caller passes the union.
   "function panToCenter(unionRect){"
   "  if(!unionRect) return;"
   "  var cx=unionRect.left+unionRect.width/2;"
   "  var cy=unionRect.top+unionRect.height/2;"
   "  panX+=window.innerWidth/2-cx;"
   "  panY+=window.innerHeight/2-cy;"
   "  applyT();reposition();"
   "}"

   ;; Compute the union of N viewport-space rects. Returns null when
   ;; the input is empty so callers can no-op cleanly.
   "function unionRects(rects){"
   "  if(!rects || !rects.length) return null;"
   "  var r0=rects[0];"
   "  var l=r0.left,t=r0.top,ri=r0.right,b=r0.bottom;"
   "  for(var i=1;i<rects.length;i++){"
   "    var r=rects[i];"
   "    if(r.left<l) l=r.left;"
   "    if(r.top<t) t=r.top;"
   "    if(r.right>ri) ri=r.right;"
   "    if(r.bottom>b) b=r.bottom;"
   "  }"
   "  return {left:l,top:t,right:ri,bottom:b,width:ri-l,height:b-t};"
   "}"

   ;; Centre the iframe viewport on a set of elements, optionally
   ;; zooming so the union of their rects (plus a padding margin)
   ;; fits inside the visible area. `padding` is given in viewport
   ;; pixels — 48 leaves comfortable breathing room around the
   ;; framed shapes. The zoom is clamped to the same 0.1–8 range
   ;; as the manual controls.
   "function centerOnElements(els,fit,padding){"
   "  if(!els || !els.length) return;"
   "  padding=(padding==null?48:padding);"
   "  var rects=els.map(function(el){return el.getBoundingClientRect();});"
   "  var u=unionRects(rects);"
   "  if(!u) return;"
   "  if(fit && u.width>0 && u.height>0){"
   "    var fitW=(window.innerWidth-padding*2)/u.width;"
   "    var fitH=(window.innerHeight-padding*2)/u.height;"
   "    var f=Math.min(fitW,fitH);"
   "    zoom=Math.max(0.1,Math.min(8,zoom*f));"
   "    applyT();"
   ;; Rects above were taken at the previous zoom; re-read so the
   ;; pan-to-centre step works against the new geometry.
   "    rects=els.map(function(el){return el.getBoundingClientRect();});"
   "    u=unionRects(rects);"
   "  }"
   "  panToCenter(u);"
   "}"

   ;; Toggle helper used by the keydown/keyup handlers — pressing or
   ;; releasing Ctrl/Cmd refreshes the hover preview immediately so the
   ;; user can see the new highlight (deepest vs top-of-tree) without
   ;; having to move the mouse first.
   "function setCtrlHeld(v){"
   "  if(ctrlHeld===v) return;"
   "  ctrlHeld=v;"
   "  if(lastMoveTarget) refreshHover(lastMoveTarget);"
   "}"

   "document.addEventListener('keydown',function(e){"
   "  if(e.code==='Space'){"
   ;; Always preventDefault so autorepeat doesn't scroll the page;
   ;; only flip state on the leading keydown.
   "    e.preventDefault();"
   "    if(!spaceDown){spaceDown=true;document.body.style.cursor='grab';}"
   "    return;"
   "  }"
   "  if(e.ctrlKey || e.metaKey){"
   "    setCtrlHeld(true);"
   "    if(e.key==='+' || e.key==='='){e.preventDefault();zoomBy(1.1);}"
   "    else if(e.key==='-'){e.preventDefault();zoomBy(1/1.1);}"
   "    else if(e.key==='0'){e.preventDefault();resetView();}"
   "  }"
   "},{capture:true});"
   "document.addEventListener('keyup',function(e){"
   "  if(e.code==='Space'){spaceDown=false;if(!dragging){document.body.style.cursor='';}}"
   "  if(e.key==='Control' || e.key==='Meta' || (!e.ctrlKey && !e.metaKey)){setCtrlHeld(false);}"
   "},{capture:true});"
   ;; If the iframe loses focus the keyup may never reach us — drop the
   ;; held flag so the next hover/click doesn't behave as if Ctrl were
   ;; still pressed.
   "window.addEventListener('blur',function(){setCtrlHeld(false);});"

   ;; Suppress browser middle-click autoscroll inside the preview.
   "document.addEventListener('auxclick',function(e){if(e.button===1){e.preventDefault();}},{capture:true});"

   ;; Ctrl/Cmd + wheel → zoom. `passive:false` is mandatory: chromium
   ;; treats wheel listeners as passive by default and `preventDefault`
   ;; is a no-op there, which would let the browser run its native
   ;; pinch-zoom on the iframe document.
   "document.addEventListener('wheel',function(e){"
   "  if(!(e.ctrlKey || e.metaKey)){return;}"
   "  e.preventDefault();"
   ;; Normalise the delta to a step-per-notch factor. Trackpad pinch
   ;; events arrive in small fractional deltas; clamp the per-event
   ;; factor so a hard scroll doesn't jump several zoom levels.
   "  var step=Math.min(0.25,Math.max(-0.25,-e.deltaY/200));"
   "  zoomBy(1+step);"
   "},{passive:false,capture:true});"

   "document.addEventListener('mousedown',function(e){"
   "  var middleBtn=e.button===1;"
   "  var spacePan=e.button===0 && spaceDown;"
   "  if(!middleBtn && !spacePan){return;}"
   "  e.preventDefault();e.stopPropagation();"
   "  dragging=true;"
   "  dStartX=e.clientX;dStartY=e.clientY;"
   "  pStartX=panX;pStartY=panY;"
   "  document.body.style.cursor='grabbing';"
   "},{capture:true});"

   "document.addEventListener('mousemove',function(e){"
   "  if(!dragging){return;}"
   "  panX=pStartX+(e.clientX-dStartX);"
   "  panY=pStartY+(e.clientY-dStartY);"
   "  applyT();reposition();"
   "},{capture:true});"

   "document.addEventListener('mouseup',function(e){"
   "  if(!dragging){return;}"
   "  dragging=false;"
   "  document.body.style.cursor=spaceDown?'grab':'';"
   ;; Always swallow the trailing click for a pan gesture (middle-click
   ;; or space+left-click), even when the user didn't actually drag —
   ;; matches Penpot's workspace behaviour, where pan mode never doubles
   ;; as a shape selection.
   "  var killer=function(ev){ev.preventDefault();ev.stopPropagation();document.removeEventListener('click',killer,true);};"
   "  document.addEventListener('click',killer,true);"
   "},{capture:true});"

   ;; ---- initial transform on next frame --------------------------
   ;; The canvas is added to the DOM before this script runs, but
   ;; querying it inside ensureCanvas() is lazy. Trigger one apply so
   ;; the transform-origin attribute is already in effect on first
   ;; zoom keystroke.
   "requestAnimationFrame(applyT);"

   "})();"))

(defn- build-document
  "Wrap the converter body output in a minimal HTML document with reset
   CSS, the page background applied, the inline `@font-face` block, and
   the selection bridge script.

   The converter emits shapes positioned at their canvas coords, which
   typically live somewhere off-origin. We compute the page's content
   bounding box, then wrap the output in a fixed-size flex item whose
   inner contents are translated by `-(minX, minY)`. The body uses
   flexbox centering so the resulting block sits in the middle of the
   preview viewport (horizontally always; vertically when it fits)."
  [{:keys [html fonts-css tokens-css]} page]
  (let [bg     (page-background page)
        name   (or (:name page) "Penpot HTML preview")
        bounds (page-bounds page)
        body   (if bounds
                 (str
                  "<div class=\"penpot-hm-canvas\" style=\"position:relative;flex:none;"
                  ;; Transform origin is the canvas's own centre so the
                  ;; pan/zoom controls (see select-bridge-script) scale
                  ;; around the visible content rather than its top-left
                  ;; corner.
                  "transform-origin:50% 50%;"
                  "width:" (:width bounds) "px;height:" (:height bounds) "px;\">"
                  "<div style=\"position:absolute;inset:0;"
                  "transform:translate(" (- (:min-x bounds)) "px," (- (:min-y bounds)) "px);"
                  "transform-origin:0 0;\">"
                  html
                  "</div></div>")
                 html)]
    (str
     "<!DOCTYPE html>\n"
     "<html lang=\"en\">\n"
     "<head>\n"
     "<meta charset=\"utf-8\">\n"
     "<title>" name "</title>\n"
     "<style>\n"
     (when (seq fonts-css) (str fonts-css "\n"))
     ;; Design-token CSS custom properties: every `var(--name, fallback)`
     ;; reference the converter emits resolves against this block. Without
     ;; it, the fallback is the only colour the browser ever sees — and if
     ;; the converter wasn't given a tokens map, even the fallback is
     ;; missing and the rule degrades to `inherit`.
     (when (seq tokens-css) (str tokens-css "\n"))
     "  *, *::before, *::after { box-sizing: border-box; }\n"
     "  html, body { margin: 0; padding: 0; }\n"
     ;; Browser user-agent stylesheets give `<p>` (and the related
     ;; block elements the converter emits for text) ~1em of top and
     ;; bottom margin. The converter sets `top` and `height` on the
     ;; outer text shape based on Penpot's measured glyph rect, so
     ;; that extra margin pushes the actual glyphs below the shape
     ;; bounds — the text renders outside its bounding box. Resetting
     ;; the margins on every block element brings the line-box back
     ;; into the shape. `line-height: normal` would also work but we
     ;; keep the shape's computed line-height in place; instead we
     ;; just collapse the default block margins.
     "  p, h1, h2, h3, h4, h5, h6, ul, ol, dl, li, dd, blockquote, figure, pre { margin: 0; padding: 0; }\n"
     ;; `overflow: hidden` keeps the panned/zoomed canvas from spawning
     ;; native scrollbars; navigation is fully driven by the pan/zoom
     ;; bridge handlers below. `user-select: none` avoids text drags
     ;; while space-panning.
     "  body { min-block-size: 100vh; background: " bg "; display: flex; justify-content: center; align-items: center; padding: 24px; overflow: hidden; user-select: none; }\n"
     "</style>\n"
     "</head>\n"
     "<body>\n"
     body "\n"
     "<script>" select-bridge-script "</script>\n"
     "</body>\n"
     "</html>")))

;; ---------------------------------------------------------------------------
;; Prototype mode iframe bridge
;;
;; The prototype bridge is intentionally much smaller than the
;; workspace bridge: no hover overlays, no selection drill, no pan, no
;; zoom, no keyboard shortcuts. The only behaviour is "click on an
;; interactive shape → tell the parent which interactions fired", plus
;; a one-pass DOM annotation that flags interactive shapes so CSS can
;; give them a pointer cursor.

(defn- build-prototype-document
  "Wrap a single board's HTML in a minimal document with reset CSS,
   the page background, and the page-scoped fonts + tokens. Prototype
   mode is static (no click handlers, no scripts) — it's just the
   converted board rendered in isolation."
  [{:keys [html fonts-css tokens-css]} page]
  (let [bg   (page-background page)
        name (or (:name page) "Penpot HTML preview")]
    (str
     "<!DOCTYPE html>\n"
     "<html lang=\"en\">\n"
     "<head>\n"
     "<meta charset=\"utf-8\">\n"
     "<title>" name "</title>\n"
     "<style>\n"
     (when (seq fonts-css) (str fonts-css "\n"))
     (when (seq tokens-css) (str tokens-css "\n"))
     "  *, *::before, *::after { box-sizing: border-box; }\n"
     "  html, body { margin: 0; padding: 0; }\n"
     "  p, h1, h2, h3, h4, h5, h6, ul, ol, dl, li, dd, blockquote, figure, pre { margin: 0; padding: 0; }\n"
     "  body { min-block-size: 100vh; background: " bg "; display: flex; justify-content: center; align-items: flex-start; padding: 24px; user-select: none; }\n"
     "</style>\n"
     "</head>\n"
     "<body>\n"
     html "\n"
     "</body>\n"
     "</html>")))

;; ---------------------------------------------------------------------------
;; postMessage bridge

(defn- read-selected
  "Build the CLJS selected map from an event.data JS object. Returns nil
   if the payload doesn't look like one of ours."
  [^js data]
  (when (and (some? data) (object? data))
    (let [t (obj/get data "type")]
      (case t
        "penpot:html-mode:select"
        {:id         (obj/get data "id")
         :shape-type (obj/get data "shapeType")
         :shape-name (obj/get data "shapeName")
         :style      (obj/get data "style")
         :tag        (obj/get data "tag")}

        "penpot:html-mode:deselect"
        ::deselect

        nil))))

;; ---------------------------------------------------------------------------
;; Auto-refresh throttling
;;
;; A `js/Date.now ()` timestamp of the last refresh request lives in a
;; component ref. We throttle visibility-driven refreshes so quick tab
;; toggles don't hammer the backend.

(def ^:private auto-refresh-throttle-ms 5000)

;; ---------------------------------------------------------------------------
;; Component

(mf/defc html-mode-section*
  [{:keys [page file frame html-mode]}]
  (let [state*    (mf/use-state {:status :loading :html nil :error nil :updated-at nil})
        selected* (mf/use-state nil)
        ;; `html-mode` is `:workspace` (default), `:prototype`, or
        ;; `:design-tokens`. It comes from the URL `?mode=` query param
        ;; so the selection is shareable. The Workspace/Prototype/
        ;; Design Tokens tab switcher emits a route nav that flips it.
        mode      (or html-mode :workspace)
        mode-str  (name mode)
        on-mode-change
        (mf/use-fn
         (fn [tab]
           (let [params (rt/get-params @st/state)]
             (st/emit! (rt/nav :viewer (assoc params :mode tab))))))
        last-refresh* (mf/use-ref (js/Date.now))
        iframe-ref    (mf/use-ref nil)
        {:keys [status html error updated-at]} (deref state*)
        selected (deref selected*)

        request-refresh
        (mf/use-fn
         (fn []
           (mf/set-ref-val! last-refresh* (js/Date.now))
           (st/emit! (dhtml/refresh-viewer-bundle))))

        handle-tree-select
        (mf/use-fn
         (mf/deps page)
         (fn [shape-id & [opts]]
           ;; `opts` is an optional map carrying `:fit` — set by
           ;; Ctrl/Cmd-click on a layer to request zoom-to-fit.
           (let [shape  (get-in page [:objects shape-id])
                 id-str (str shape-id)
                 fit?   (boolean (:fit opts))
                 prev   (deref selected*)]
             ;; Optimistic update so the right sidebar reflects the new
             ;; selection immediately; the iframe will follow up with a
             ;; richer payload (style + tag) once it positions the
             ;; selected overlay.
             (when shape
               (reset! selected*
                       {:id         id-str
                        :shape-type (some-> (:type shape) name)
                        :shape-name (:name shape)
                        :style      (if (= id-str (:id prev)) (:style prev) "")
                        :tag        (or (:tag prev) "div")}))
             ;; Ask the iframe to draw the overlay AND centre on the
             ;; element (or zoom-to-fit when `fit?` is true).
             (when-let [iframe (mf/ref-val iframe-ref)]
               (when-let [win (.-contentWindow iframe)]
                 (.postMessage win
                               #js {:type "penpot:html-mode:select-by-id"
                                    :id id-str
                                    :fit fit?}
                               "*"))))))]

    ;; Re-render whenever the file, page, mode, or (for prototype
    ;; Re-render whenever the file, page, mode, or (for prototype
    ;; mode) the selected frame changes. Workspace caches whole-page
    ;; renders; prototype renders the currently-picked board via
    ;; `render-board-html` and wraps it in a static iframe document
    ;; (no scripts, no click handlers).
    (mf/with-effect [file page mode frame]
      (let [cancelled? (volatile! false)
            on-error
            (fn [^js err]
              (when-not @cancelled?
                (js/console.error "HTML Mode conversion failed:" err)
                (reset! state*
                        {:status     :error
                         :html       nil
                         :error      (.-message err)
                         :updated-at nil})))]
        (reset! selected* nil)
        (cond
          (nil? page)
          (reset! state* {:status :empty :html nil :error nil :updated-at nil})

          ;; Design Tokens mode bypasses the iframe entirely — the
          ;; design-tokens-view* operates on `(:objects page)` directly
          ;; and renders its own layout. Mark the state so the toolbar
          ;; can disable the refresh button.
          (= mode :design-tokens)
          (reset! state* {:status :design-tokens :html nil :error nil :updated-at nil})

          (= mode :prototype)
          (if (nil? frame)
            (reset! state* {:status :empty :html nil :error nil :updated-at nil})
            (do
              (reset! state* {:status :loading :html nil :error nil :updated-at nil})
              (-> (render-board-html file page frame)
                  (.then (fn [parts]
                           (when-not @cancelled?
                             (reset! state*
                                     {:status     :ready
                                      :html       (build-prototype-document parts page)
                                      :error      nil
                                      :updated-at (js/Date.now)}))))
                  (.catch on-error))))

          :else
          (do
            (reset! state* {:status :loading :html nil :error nil :updated-at nil})
            (-> (render-page-html-cached file page)
                (.then (fn [parts]
                         (when-not @cancelled?
                           (reset! state*
                                   {:status     :ready
                                    :html       (build-document parts page)
                                    :error      nil
                                    :updated-at (js/Date.now)}))))
                (.catch on-error))))
        (fn [] (vreset! cancelled? true))))

    ;; Listen for shape-selection messages from the workspace-mode
    ;; iframe. Prototype mode posts nothing — the iframe is static.
    (mf/with-effect []
      (let [handler (fn [^js e]
                      (let [result (read-selected (.-data e))]
                        (cond
                          (= result ::deselect) (reset! selected* nil)
                          (some? result)        (reset! selected* result))))]
        (.addEventListener js/window "message" handler)
        (fn [] (.removeEventListener js/window "message" handler))))

    ;; Auto-refresh when the HTML Mode window regains visibility, but
    ;; only if it has been more than `auto-refresh-throttle-ms` since the
    ;; last refresh — quick alt-tab cycles should not hammer the server.
    (mf/with-effect []
      (let [handler (fn []
                      (when (= "visible" (.-visibilityState js/document))
                        (let [now      (js/Date.now)
                              last-ts  (mf/ref-val last-refresh*)]
                          (when (or (nil? last-ts)
                                    (> (- now last-ts) auto-refresh-throttle-ms))
                            (mf/set-ref-val! last-refresh* now)
                            (st/emit! (dhtml/refresh-viewer-bundle))))))]
        (.addEventListener js/document "visibilitychange" handler)
        (fn [] (.removeEventListener js/document "visibilitychange" handler))))

    ;; Forward Ctrl/Cmd + `+`/`=`/`-`/`0` to the iframe and swallow the
    ;; browser default. Without this, the host browser interprets the
    ;; combo as page zoom and the iframe never gets a chance to act on
    ;; it — `preventDefault` inside the sandboxed document only works
    ;; when keyboard focus lives there, and most of the time focus is
    ;; on the parent app (sidebar, layers tree, etc).
    ;;
    ;; The handler is a no-op if the user is typing in an input /
    ;; textarea / contenteditable, so it doesn't break form inputs.
    (mf/with-effect []
      (let [editable? (fn [^js el]
                        (when el
                          (let [tag (some-> (.-tagName el) (.toLowerCase))]
                            (or (= tag "input")
                                (= tag "textarea")
                                (= tag "select")
                                (.-isContentEditable el)))))
            handler (fn [^js e]
                      (when (and (or (.-ctrlKey e) (.-metaKey e))
                                 (not (editable? (.-target e))))
                        (let [key (.-key e)
                              action (case key
                                       ("+" "=") "in"
                                       ("-" "_") "out"
                                       "0"       "reset"
                                       nil)]
                          (when action
                            (.preventDefault e)
                            (when-let [iframe (mf/ref-val iframe-ref)]
                              (when-let [win (.-contentWindow iframe)]
                                (.postMessage win
                                              #js {:type "penpot:html-mode:zoom"
                                                   :action action}
                                              "*")))))))]
        (.addEventListener js/window "keydown" handler true)
        (fn [] (.removeEventListener js/window "keydown" handler true))))

    [:section {:class (stl/css :html-mode-section)
               :data-viewer-section true
               :data-mode mode-str
               :data-page-id (some-> page :id str)}
     ;; Sidebars are workspace-only: prototype mode mirrors the regular
     ;; viewer interactions view, which has no layer tree or property
     ;; inspector. Skip the components entirely so they don't run their
     ;; own renders / effects while hidden.
     (when (= mode :workspace)
       [:> layers-tree* {:page page
                         :selected selected
                         :on-select handle-tree-select}])
     [:div {:class (stl/css :preview-pane)}
      [:div {:class (stl/css :preview-toolbar)}
       ;; Refresh — re-uses Penpot's ghost icon-button so the look
       ;; matches every other toolbar action in the app. The reload
       ;; glyph spins while a render is in flight (status `:loading`)
       ;; so users get feedback that the refresh is actually working.
       [:> icon-button* {:variant "ghost"
                         :icon i/reload
                         :class (stl/css :refresh-btn)
                         :icon-class (stl/css-case
                                      :refresh-icon-spinning (= status :loading))
                         :on-click request-refresh
                         :disabled (= status :loading)
                         :aria-label (tr "viewer.html-mode.toolbar.refresh")}]
       ;; Centred segmented control (DS tab-switcher). The selected
       ;; tab mirrors the URL `?mode=` query param so toggling here
       ;; navigates the route; that in turn re-renders this component
       ;; with the new `html-mode` prop and the render effect picks
       ;; the right pipeline.
       [:> tab-switcher* {:class (stl/css :toolbar-tabs)
                          :tabs [{:id "prototype"
                                  :label (tr "viewer.html-mode.toolbar.prototype")}
                                 {:id "workspace"
                                  :label (tr "viewer.html-mode.toolbar.workspace")}
                                 {:id "design-tokens"
                                  :label (tr "viewer.html-mode.toolbar.design-tokens")}]
                          :selected mode-str
                          :on-change on-mode-change}]]

      (if (= mode :design-tokens)
        ;; Design Tokens mode renders its own panel layout (left sub-
        ;; sidebar with sub-tabs + main content area), so it bypasses
        ;; the iframe-driven `:empty/:loading/:error/:ready` state
        ;; machine entirely. `file` carries the `:tokens-lib` the
        ;; DTCG JSON exporter consumes.
        [:> design-tokens-view* {:page page :file file}]

        (case status
          :empty
          [:div {:class (stl/css :state)}
           [:p {:class (stl/css :description)}
            (tr "viewer.empty-state")]]

          :loading
          [:div {:class (stl/css :state)}
           [:p {:class (stl/css :description)}
            (tr "viewer.html-mode.loading")]]

          :error
          [:div {:class (stl/css :state)}
           [:h1 {:class (stl/css :title)}
            (tr "viewer.html-mode.error")]
           [:p {:class (stl/css :description)}
            (or error (tr "errors.generic"))]]

          :ready
          [:iframe {:class           (stl/css :preview-iframe)
                    :ref             iframe-ref
                    :title           (tr "viewer.html-mode.iframe-title")
                    :src-doc         html
                    ;; `allow-same-origin` is required so the iframe can load
                    ;; fonts and image assets from Penpot's own URLs with the
                    ;; user's session credentials — without it the iframe has
                    ;; an opaque origin and cross-origin requests for fonts
                    ;; fail, causing text shapes to render with system
                    ;; fallback fonts and overflow their measured bounds.
                    ;; The injected script is one we control, so granting
                    ;; same-origin is acceptable. See the namespace docstring
                    ;; for the full threat model.
                    :sandbox         "allow-scripts allow-same-origin"
                    :referrer-policy "no-referrer"}]

          ;; Default branch — exercised on the first render after the
          ;; user navigates AWAY from `:design-tokens`. React renders
          ;; once with the stale `:design-tokens` status before the
          ;; render effect re-runs and resets it to `:loading`; without
          ;; a default the `case` would throw "No matching clause".
          ;; Showing the loading placeholder is fine — the effect
          ;; immediately kicks in and the real content arrives next
          ;; tick.
          [:div {:class (stl/css :state)}
           [:p {:class (stl/css :description)}
            (tr "viewer.html-mode.loading")]]))]

     (when (= mode :workspace)
       [:> html-mode-sidebar* {:selected selected :page page :file file}])]))
