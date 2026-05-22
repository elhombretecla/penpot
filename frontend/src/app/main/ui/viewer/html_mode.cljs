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

   ## Prototype mode

   The Prototype tab is an interactive prototype runner: shape
   `:interactions` authored in edit mode are translated to HTML/CSS/JS
   at preview time. `harvest-interactions` projects the Penpot schema
   into a JSON map injected as `window.__PENPOT_INTERACTIONS__`; the
   inline `prototype-bridge-script` reads it and emits `postMessage`
   events on click/hover/after-delay. The parent CLJS owns navigation
   stack, open overlays, and animation orchestration — overlays are
   mounted as their own iframes positioned via the same math the SVG
   viewer uses (`calc-overlay-position` in
   `app.common.types.shape.interactions`). Frame transitions briefly
   mount two iframes side-by-side and animate them via the Web
   Animations API on the parent wrappers (the iframe contents are
   opaque to the animation).

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
   [app.common.geom.point :as gpt]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.uuid :as uuid]
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
;; thumbnails / pagination). The board renders via `convertShape`.
;; Unlike Workspace mode, the iframe runs a tiny JS runtime
;; (`prototype-bridge-script`) that wires the shape `:interactions`
;; data to real DOM behavior — click / hover / after-delay / open-url
;; — and posts back navigate / overlay events to the parent for the
;; bits that need parent-level orchestration (board swap, overlay
;; mounting, animations).

(defn- ->js-animation
  "Project a Penpot animation map onto a JSON-serialisable shape the
   iframe runtime understands. Keys are camelCased and keywords are
   stringified, matching the convention used by `adapter/->js-page`."
  [animation]
  (when animation
    (let [t (some-> (:animation-type animation) name)]
      (cond-> {:type t
               :duration (:duration animation)
               :easing (some-> (:easing animation) name)}
        (= t "slide") (assoc :way (some-> (:way animation) name)
                             :direction (some-> (:direction animation) name)
                             :offsetEffect (boolean (:offset-effect animation)))
        (= t "push")  (assoc :direction (some-> (:direction animation) name))))))

(defn- ->js-interaction
  "Project a Penpot interaction map onto a JSON-serialisable shape
   the iframe runtime understands. Only the fields the runtime needs
   are emitted — unused options stay in the source map."
  [interaction]
  (let [pos (:overlay-position interaction)]
    (cond-> {:eventType  (some-> (:event-type interaction) name)
             :actionType (some-> (:action-type interaction) name)}
      (:destination interaction)        (assoc :destination (str (:destination interaction)))
      (:delay interaction)              (assoc :delay (:delay interaction))
      (:preserve-scroll interaction)    (assoc :preserveScroll (boolean (:preserve-scroll interaction)))
      (:url interaction)                (assoc :url (:url interaction))
      pos                               (assoc :overlayPosition {:x (:x pos) :y (:y pos)})
      (:overlay-pos-type interaction)   (assoc :overlayPosType (name (:overlay-pos-type interaction)))
      (some? (:close-click-outside interaction)) (assoc :closeClickOutside (boolean (:close-click-outside interaction)))
      (some? (:background-overlay interaction))  (assoc :backgroundOverlay (boolean (:background-overlay interaction)))
      (:position-relative-to interaction) (assoc :positionRelativeTo (str (:position-relative-to interaction)))
      (:animation interaction)          (assoc :animation (->js-animation (:animation interaction))))))

(defn- harvest-interactions
  "Walk every shape on the page and return a map
   `{shape-id-str → [interaction-payload …]}` for use by the iframe
   runtime. Shapes without interactions are omitted to keep the map
   small (the runtime checks for membership before reading)."
  [page]
  (->> (vals (:objects page))
       (keep (fn [shape]
               (let [xs (:interactions shape)]
                 (when (seq xs)
                   [(str (:id shape)) (mapv ->js-interaction xs)]))))
       (into {})))

;; ---------------------------------------------------------------------------
;; Prototype board doc cache
;;
;; LRU cache for `build-prototype-document` output, keyed by
;; (file-id, page-id, file-revn, frame-id). Eliminates the converter
;; round-trip on revisits — most relevant for hover-driven
;; toggle-overlay (rapid open/close cycles) and prev-screen rewinds.
;; Separate from `app.main.data.html-mode.cache` because that cache
;; sizes its capacity around large full-page workspace renders;
;; prototype docs are smaller and a board-heavy file can have many.
;; The cache is module-level and drops on a full page reload.

(def ^:private prototype-cache-capacity 32)

(defonce ^:private prototype-doc-cache (js/Map.))

(defn- prototype-cache-key
  [file page frame]
  (str (:id file) "::" (:id page) "::" (:revn file) "::" (str (:id frame))))

(defn- prototype-cache-get!
  "Look up a cached doc and refresh its LRU position. Returns nil on
   miss."
  [k]
  (when (.has prototype-doc-cache k)
    (let [v (.get prototype-doc-cache k)]
      (.delete prototype-doc-cache k)
      (.set prototype-doc-cache k v)
      v)))

(defn- prototype-cache-put!
  "Insert `[k v]`, evicting the oldest entry if at capacity."
  [k v]
  (when (>= (.-size prototype-doc-cache) prototype-cache-capacity)
    (let [iter   (.keys prototype-doc-cache)
          oldest (.-value (.next iter))]
      (when (some? oldest) (.delete prototype-doc-cache oldest))))
  (.set prototype-doc-cache k v)
  v)

(defn- render-board-html
  "Resolve a Promise of `{:html :fonts-css :tokens-css :interactions}`
   for the currently-selected board. Mirrors `render-page-html` but
   calls `cv/convertShape` so only the requested frame and its
   descendants are converted. The `:interactions` map is the harvested
   subset of shape interactions that the converter's output covers,
   keyed by shape-id string.

   The result is memoised in `prototype-doc-cache` so repeat renders
   of the same board (hover-toggle ping-ponging an overlay, prev-screen
   rewinding through history, navigate visiting an already-seen
   destination) resolve synchronously without re-invoking the
   converter. Cache invalidates naturally on `revn` advance."
  [file page frame]
  (let [k (prototype-cache-key file page frame)]
    (if-let [hit (prototype-cache-get! k)]
      (js/Promise.resolve hit)
      (let [js-page    (adapter/->js-page page)
            ctx        (cctx/converter-context file js-page)
            js-objects (.-objects js-page)
            js-shape   (unchecked-get js-objects (str (:id frame)))
            tokens     (.-tokens ctx)
            tokens-css (when (and tokens (pos? (.-size tokens))) (cv/tokensToCss tokens))
            ix         (harvest-interactions page)]
        (-> (js/Promise.all
             #js [(-> (js/Promise.resolve)
                      (.then (fn [] (cv/convertShape js-shape js-objects ctx)))
                      (.then (fn [^js result] (.-html result))))
                  (render-fonts-css-async file page)])
            (.then (fn [^js parts]
                     (let [result {:html         (aget parts 0)
                                   :fonts-css    (aget parts 1)
                                   :tokens-css   (or tokens-css "")
                                   :interactions ix}]
                       (prototype-cache-put! k result)
                       result))))))))

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
;; The prototype bridge wires Penpot's `:interactions` data (harvested
;; in CLJS and injected as `window.__PENPOT_INTERACTIONS__`) into real
;; DOM behaviour. A single delegated listener handles click /
;; mouseover / mouseout; `:after-delay` fires from a `setTimeout` set
;; on load; `:open-url` runs locally with `window.open`. Every other
;; action (`:navigate`, `:open-overlay`, `:toggle-overlay`,
;; `:close-overlay`, `:prev-screen`) posts back to the parent which
;; owns the navigation stack and animation orchestration.

(def ^:private prototype-bridge-script
  ;; Inline JS executed inside the prototype iframe. Reads the
  ;; interactions map injected as `window.__PENPOT_INTERACTIONS__`
  ;; (a `{shape-id → [interaction …]}` map) and dispatches per the
  ;; event-type of each interaction. The script intentionally has no
  ;; dependency on the parent's runtime — even if the parent never
  ;; replies, click hotspots still feel responsive (pointer cursor,
  ;; default-prevented).
  (str
   "(function(){"
   "var IX = (window.__PENPOT_INTERACTIONS__) || {};"
   "var ROOT_ID = window.__PENPOT_ROOT_ID__ || null;"

   ;; ---- styles: pointer cursor on interactive shapes ----
   "var s=document.createElement('style');"
   "s.textContent='[data-prototype-interactive]{cursor:pointer}"
   "[data-prototype-interactive] *{cursor:pointer}';"
   "document.head.appendChild(s);"

   ;; ---- helpers ----
   "function send(p){try{parent.postMessage(p,'*');}catch(e){}}"

   ;; Walk from event.target up collecting [data-id] elements,
   ;; SHALLOWEST → DEEPEST. Used to find which shapes are under the
   ;; pointer (innermost wins when multiple have interactions).
   "function chainAt(t){"
   "  var chain=[], el=t;"
   "  while(el && el!==document.body){"
   "    if(el.nodeType===1 && el.hasAttribute && el.hasAttribute('data-id')){chain.unshift(el);}"
   "    el=el.parentElement;"
   "  }"
   "  return chain;"
   "}"

   ;; Returns the innermost [data-id] element in the chain that has at
   ;; least one interaction matching one of the given event types.
   ;; Returns `{el, interactions}` or null.
   "function findInteractive(target, eventTypes){"
   "  var c=chainAt(target);"
   "  for(var i=c.length-1;i>=0;i--){"
   "    var id=c[i].getAttribute('data-id');"
   "    var xs=IX[id];"
   "    if(!xs) continue;"
   "    var matched=[];"
   "    for(var j=0;j<xs.length;j++){"
   "      if(eventTypes.indexOf(xs[j].eventType)>=0) matched.push(xs[j]);"
   "    }"
   "    if(matched.length) return {el:c[i], id:id, interactions:matched};"
   "  }"
   "  return null;"
   "}"

   ;; ---- dispatch one interaction ----
   ;; Some actions (open-url) run locally; everything else is
   ;; forwarded to the parent which owns navigation + overlay state.
   "function dispatch(interaction, sourceId){"
   "  var a=interaction.actionType;"
   "  if(a==='open-url' && interaction.url){"
   "    try{window.open(interaction.url,'_blank','noopener,noreferrer');}catch(e){}"
   "    return;"
   "  }"
   "  send({type:'penpot:prototype:trigger', sourceId:sourceId, interaction:interaction});"
   "}"

   ;; ---- one-pass DOM annotation: mark interactive shapes ----
   ;; Done once at load so CSS can apply pointer cursor without
   ;; per-event work. Only marks shapes with at least one trigger
   ;; that the runtime supports (click / mouse-* / after-delay is
   ;; not user-triggered so it doesn't count for cursor purposes).
   "function annotate(){"
   "  var ids=Object.keys(IX);"
   "  for(var i=0;i<ids.length;i++){"
   "    var xs=IX[ids[i]];"
   "    var userTriggered=false;"
   "    for(var j=0;j<xs.length;j++){"
   "      var et=xs[j].eventType;"
   "      if(et==='click' || et==='mouse-press' || et==='mouse-over' || et==='mouse-enter' || et==='mouse-leave'){"
   "        userTriggered=true; break;"
   "      }"
   "    }"
   "    if(!userTriggered) continue;"
   "    var el=document.querySelector('[data-id=\"'+ids[i]+'\"]');"
   "    if(el) el.setAttribute('data-prototype-interactive','');"
   "  }"
   "}"

   ;; ---- click ----
   ;; Both :click and :mouse-press fire on a primary click. We treat
   ;; them as synonyms here — Penpot's data model preserves the
   ;; distinction so editors can author either, but at runtime in the
   ;; iframe there's no separable mousedown-vs-click semantic worth
   ;; differentiating.
   "document.addEventListener('click',function(e){"
   "  var hit=findInteractive(e.target, ['click','mouse-press']);"
   "  if(!hit) return;"
   "  e.preventDefault(); e.stopPropagation();"
   "  for(var i=0;i<hit.interactions.length;i++) dispatch(hit.interactions[i], hit.id);"
   "},{capture:true});"

   ;; ---- hover (mouseover / mouseout) ----
   ;; We use bubbling mouseover/mouseout instead of mouseenter/leave
   ;; so a single document-level listener captures everything. The
   ;; `relatedTarget` check ensures we only fire enter/leave when the
   ;; pointer actually crosses the interactive boundary.
   ;;
   ;; mouse-over fires once on enter (same as mouse-enter for these
   ;; purposes — Penpot's data model lists it separately but the
   ;; viewer treats them together). mouse-leave is its inverse and
   ;; fires on the way out.
   "function containsRelated(el, related){"
   "  if(!related) return false;"
   "  return el.contains(related);"
   "}"
   "document.addEventListener('mouseover',function(e){"
   "  var hit=findInteractive(e.target, ['mouse-enter','mouse-over']);"
   "  if(!hit) return;"
   "  if(containsRelated(hit.el, e.relatedTarget)) return;"
   "  for(var i=0;i<hit.interactions.length;i++) dispatch(hit.interactions[i], hit.id);"
   "},{capture:true});"
   "document.addEventListener('mouseout',function(e){"
   "  var hit=findInteractive(e.target, ['mouse-leave']);"
   "  if(!hit) return;"
   "  if(containsRelated(hit.el, e.relatedTarget)) return;"
   "  for(var i=0;i<hit.interactions.length;i++) dispatch(hit.interactions[i], hit.id);"
   "},{capture:true});"

   ;; ---- after-delay ----
   ;; Per Penpot's data model `:after-delay` is only meaningful on
   ;; frame shapes, and at runtime only on the CURRENTLY DISPLAYED
   ;; board. We schedule one timer per matching interaction on the
   ;; root board id; the parent unmounts the iframe (clearing the
   ;; timer naturally) whenever the board changes, so stale delays
   ;; never fire against the wrong board.
   "function scheduleDelays(){"
   "  if(!ROOT_ID) return;"
   "  var xs=IX[ROOT_ID];"
   "  if(!xs) return;"
   "  for(var i=0;i<xs.length;i++){"
   "    var ix=xs[i];"
   "    if(ix.eventType!=='after-delay') continue;"
   "    var d=Math.max(0, +ix.delay || 0);"
   "    setTimeout((function(payload){return function(){dispatch(payload, ROOT_ID);};})(ix), d);"
   "  }"
   "}"

   "if(document.readyState==='loading'){"
   "  document.addEventListener('DOMContentLoaded',function(){annotate();scheduleDelays();});"
   "} else {"
   "  annotate(); scheduleDelays();"
   "}"

   "})();"))

(defn- build-prototype-document
  "Wrap a single board's HTML in a minimal document with reset CSS,
   the page background, the page-scoped fonts + tokens, and the
   prototype runtime.

   The iframe IS the board: the body fills 100% × 100% of the iframe
   (which the parent sizes to the board's dimensions) and the
   converter's output is rendered directly inside the body. The
   converter renders the root shape with `position: relative` and
   `width / height` taken from the shape itself (see
   `convertShape` in
   `frontend/vendor/penpot-html-converter/src/converter/index.ts`,
   which passes `_forceRelative: true`), so the board lands at the
   body's origin naturally — no translate trick is needed (the
   workspace mode's `(-minX, -minY)` translate is for full-page
   renders that include shapes at arbitrary canvas coordinates).

   The page background paints only inside this board-sized iframe —
   the surrounding pane background is the parent's `.preview-stage`,
   which doesn't animate. This is what eliminates the background
   flicker that earlier pane-sized versions had.

   The runtime reads the harvested interactions map (injected as
   `window.__PENPOT_INTERACTIONS__`) and dispatches click / hover /
   after-delay events. Navigate / overlay actions bubble up to the
   parent via `postMessage`."
  [{:keys [html fonts-css tokens-css interactions]} page frame]
  (let [bg       (page-background page)
        title    (or (:name page) "Penpot HTML preview")
        ix-json  (.stringify js/JSON (clj->js (or interactions {})))
        root-id  (str (:id frame))]
    (str
     "<!DOCTYPE html>\n"
     "<html lang=\"en\">\n"
     "<head>\n"
     "<meta charset=\"utf-8\">\n"
     "<title>" title "</title>\n"
     "<style>\n"
     (when (seq fonts-css) (str fonts-css "\n"))
     (when (seq tokens-css) (str tokens-css "\n"))
     "  *, *::before, *::after { box-sizing: border-box; }\n"
     "  html, body { margin: 0; padding: 0; inline-size: 100%; block-size: 100%; }\n"
     "  p, h1, h2, h3, h4, h5, h6, ul, ol, dl, li, dd, blockquote, figure, pre { margin: 0; padding: 0; }\n"
     "  body { background: " bg "; overflow: hidden; user-select: none; position: relative; }\n"
     "</style>\n"
     "</head>\n"
     "<body>\n"
     html "\n"
     "<script>window.__PENPOT_INTERACTIONS__=" ix-json ";"
     "window.__PENPOT_ROOT_ID__=\"" root-id "\";</script>\n"
     "<script>" prototype-bridge-script "</script>\n"
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
;; Prototype parent-side controller
;;
;; The iframe runtime emits `penpot:prototype:trigger` messages. The
;; parent decides what each action does: navigate swaps the rendered
;; board (with optional WAAPI animation across two stacked iframes);
;; open/toggle/close-overlay maintain a list of mounted overlay
;; iframes positioned via `ctsi/calc-overlay-position` (the same math
;; the SVG viewer uses); prev-screen pops a nav stack.

(defn- ^:private parse-uuid-safe
  "Parse a UUID-string or return nil on bad input. Used because the
   iframe payload only carries strings — Penpot's CLJS API expects
   real UUIDs for ids."
  [s]
  (when (and (string? s) (seq s))
    (try (uuid/parse s) (catch :default _ nil))))

(defn- js-animation->cljs
  "Re-hydrate the JS animation payload the runtime returns into the
   keyword-flavoured CLJS shape the rest of the code expects (matches
   `app.common.types.shape.interactions/animation-types`)."
  [^js a]
  (when a
    (let [t (obj/get a "type")]
      (cond-> {:animation-type (keyword t)
               :duration       (obj/get a "duration")
               :easing         (some-> (obj/get a "easing") keyword)}
        (= t "slide")
        (assoc :way (some-> (obj/get a "way") keyword)
               :direction (some-> (obj/get a "direction") keyword)
               :offset-effect (boolean (obj/get a "offsetEffect")))
        (= t "push")
        (assoc :direction (some-> (obj/get a "direction") keyword))))))

(defn- js-interaction->cljs
  "Rebuild a CLJS interaction map matching the Penpot schema from the
   JS payload the iframe forwarded. UUID-strings are parsed back into
   uuids and gpt/point is rebuilt so downstream helpers (notably
   `ctsi/calc-overlay-position`) work without surprise."
  [^js ix]
  (let [pos (obj/get ix "overlayPosition")]
    (cond-> {:event-type  (some-> (obj/get ix "eventType") keyword)
             :action-type (some-> (obj/get ix "actionType") keyword)}
      (obj/get ix "destination")
      (assoc :destination (parse-uuid-safe (obj/get ix "destination")))

      (obj/get ix "delay")
      (assoc :delay (obj/get ix "delay"))

      (obj/get ix "preserveScroll")
      (assoc :preserve-scroll (boolean (obj/get ix "preserveScroll")))

      (obj/get ix "url")
      (assoc :url (obj/get ix "url"))

      pos
      (assoc :overlay-position (gpt/point (obj/get pos "x") (obj/get pos "y")))

      (obj/get ix "overlayPosType")
      (assoc :overlay-pos-type (keyword (obj/get ix "overlayPosType")))

      (some? (obj/get ix "closeClickOutside"))
      (assoc :close-click-outside (boolean (obj/get ix "closeClickOutside")))

      (some? (obj/get ix "backgroundOverlay"))
      (assoc :background-overlay (boolean (obj/get ix "backgroundOverlay")))

      (obj/get ix "positionRelativeTo")
      (assoc :position-relative-to (parse-uuid-safe (obj/get ix "positionRelativeTo")))

      (obj/get ix "animation")
      (assoc :animation (js-animation->cljs (obj/get ix "animation"))))))

(defn- read-prototype-trigger
  "Recognise a `penpot:prototype:trigger` postMessage payload from the
   prototype bridge script and return `{:source-id :interaction}` (with
   `:interaction` rebuilt as a CLJS interaction map), or `nil` if the
   message isn't one of ours."
  [^js data]
  (when (and (some? data) (object? data))
    (when (= (obj/get data "type") "penpot:prototype:trigger")
      {:source-id   (obj/get data "sourceId")
       :interaction (js-interaction->cljs (obj/get data "interaction"))})))

(defn- find-frame-by-id-str
  "Resolve a frame UUID-string to its shape map by walking the page's
   `:frames` (the top-level frame index used by viewer pagination)."
  [page id-str]
  (some (fn [f] (when (= (str (:id f)) id-str) f)) (:frames page)))

(defn- board-dims
  "Return `{:width :height}` for a frame in canvas units. Used by the
   parent JSX to size the `.board-stack` wrapper that isolates the
   board from the preview pane's surrounding stage background. Falls
   back to zeros for nil so the JSX can still emit a valid style map
   (which then renders as nothing — the JSX guards on the wrapping
   `(when frame ...)` higher up)."
  [frame]
  (let [s (:selrect frame)]
    {:width  (or (:width s) (:width frame) 0)
     :height (or (:height s) (:height frame) 0)}))

(defn- nav-to-frame-index!
  "Sync the URL `?index=` to the position of `frame-id-str` in the
   page's top-level frames index. Must be called on every controller-
   driven prototype navigation (animated, instant, or prev-screen) so
   external consumers — the viewer header breadcrumb, the thumbnail
   pagination, link-sharing — see the current board. No-op when the
   id isn't a top-level frame, which keeps overlay actions (whose
   destination may live deeper in `:objects`) from corrupting the
   query string."
  [page frame-id-str]
  (when frame-id-str
    (when-let [idx (some (fn [[i f]] (when (= (str (:id f)) frame-id-str) i))
                         (map-indexed vector (:frames page)))]
      (let [params (rt/get-params @st/state)]
        (st/emit! (rt/nav :viewer (assoc params :index idx)))))))

(defn- compute-overlay-rect
  "Compute the overlay's position and size relative to the base frame
   (the currently displayed board). Returns `{:x :y :width :height}`
   in canvas units. Mirrors the viewer's overlay positioning math at
   `frontend/src/app/main/ui/viewer.cljs:141-212` by delegating to
   `ctsi/calc-overlay-position`."
  [page interaction source-shape base-frame dest-frame]
  (let [objects          (:objects page)
        relative-to-id   (:position-relative-to interaction)
        relative-shape   (cond
                           (some? relative-to-id) (get objects relative-to-id)
                           (= :manual (:overlay-pos-type interaction)) base-frame
                           :else source-shape)
        [pos _snap]      (ctsi/calc-overlay-position
                          interaction
                          source-shape
                          objects
                          (or relative-shape base-frame)
                          base-frame
                          dest-frame
                          (gpt/point 0 0))
        srect            (:selrect dest-frame)]
    {:x      (:x pos)
     :y      (:y pos)
     :width  (:width srect)
     :height (:height srect)}))

(defn- easing->css
  "Project a Penpot easing keyword to its CSS timing-function string."
  [easing]
  (case easing
    :linear      "linear"
    :ease        "ease"
    :ease-in     "ease-in"
    :ease-out    "ease-out"
    :ease-in-out "ease-in-out"
    "ease"))

(defn- slide-axis-percent
  "Translate a Penpot direction keyword into a `transform` for a 100%
   offset in that direction. `:right` means the destination starts off-
   screen to the right and slides in to the left; `:left` mirrored;
   etc. Used by both slide and push animations."
  [direction]
  (case direction
    :right "translateX(100%)"
    :left  "translateX(-100%)"
    :up    "translateY(-100%)"
    :down  "translateY(100%)"
    "translateX(100%)"))

(defn- slide-keyframes
  "Build the keyframe pair for the destination iframe in a `:slide` or
   `:push` animation. Returns `[from-transform, to-transform]`."
  [direction]
  [(slide-axis-percent direction) "translate(0,0)"])

(defn- push-from-keyframes
  "Keyframes for the origin iframe in a `:push` animation: slides out
   in the OPPOSITE direction of the destination's entry."
  [direction]
  ["translate(0,0)"
   (slide-axis-percent (case direction
                         :right :left
                         :left  :right
                         :up    :down
                         :down  :up))])

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
        ;; Prototype-mode controller state. Lives alongside `state*`
        ;; because it spans navigations: even though `state*` resets
        ;; on every board change, the nav-stack / overlays / in-flight
        ;; transition must persist.
        ;;
        ;; `:current-frame-id` mirrors the URL `?index=` on entry but
        ;; the controller TAKES OWNERSHIP of it while transitions are
        ;; in flight — that lets the dual-iframe animation outlive a
        ;; would-be URL-driven re-render. URL sync happens once the
        ;; animation finishes (so refresh / link-sharing still work).
        proto-state* (mf/use-state {:current-frame-id nil
                                    :nav-stack        []
                                    :overlays         []
                                    :transition       nil})
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
        ;; Layer DOM nodes keyed by frame-id. The prototype JSX
        ;; renders one `.board-layer` div per visible layer (one in
        ;; steady state, two during a navigate transition) and uses
        ;; a ref callback that stores the div element in this map.
        ;; The WAAPI animation effect looks up `from` / `to` by id.
        ;;
        ;; React keys on the layer divs match the `frame-id`, so the
        ;; destination iframe survives the transition→commit
        ;; reconciliation without unmounting — that is what makes
        ;; the post-animation hand-off glitch-free (the iframe's
        ;; srcDoc never changes, the browser never reloads it).
        layer-refs*   (mf/use-ref #js {})
        set-layer-ref!
        (mf/use-fn
         (fn [id el]
           (let [m (mf/ref-val layer-refs*)]
             (if el
               (unchecked-set m id el)
               (js-delete m id)))))
        ;; Tracks which frame's doc `state*` currently holds. The
        ;; render effect uses this to SKIP its work when the
        ;; controller has already produced the right doc (e.g. after
        ;; a navigate / prev-screen / transition commit) — without
        ;; this guard the effect would reset `:status :loading` and
        ;; momentarily unmount the iframe right after the controller
        ;; just mounted it, causing a visible re-mount flicker.
        rendered-frame-id* (mf/use-ref nil)
        {:keys [status html error updated-at]} (deref state*)
        {:keys [current-frame-id overlays transition]} (deref proto-state*)
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
                               "*"))))))

        ;; -------------------------------------------------------------
        ;; Prototype controller — dispatches one interaction trigger.
        ;; Routed to from the `penpot:prototype:trigger` postMessage
        ;; listener further down. The iframe runtime handles
        ;; `:open-url` locally, so this branch never sees it; everything
        ;; else maps to a piece of `proto-state*`:
        ;;   :navigate         → render dest doc, start transition
        ;;   :open-overlay     → render dest doc, append to overlays
        ;;   :toggle-overlay   → open if absent, close if present
        ;;   :close-overlay    → drop from overlays (defaults to source)
        ;;   :prev-screen      → pop nav-stack and rewind
        ;;
        ;; Each branch needs the destination frame as a CLJS shape; we
        ;; resolve it via `find-frame-by-id-str` (root frames) for
        ;; navigate, and via the full `:objects` map for overlays
        ;; (overlay destinations are board-typed shapes but not
        ;; necessarily in :frames).
        dispatch-prototype-trigger
        (mf/use-fn
         (mf/deps file page)
         (fn [^js msg]
           (when-let [{:keys [source-id interaction]} (read-prototype-trigger msg)]
             (let [action       (:action-type interaction)
                   ps           (deref proto-state*)
                   base-id      (:current-frame-id ps)
                   base-frame   (when base-id (find-frame-by-id-str page base-id))
                   source-uuid  (parse-uuid-safe source-id)
                   source-shape (when source-uuid (get (:objects page) source-uuid))]
               (case action
                 :navigate
                 (when-let [dest-id (some-> (:destination interaction) str)]
                   (when-let [dest-frame (find-frame-by-id-str page dest-id)]
                     (-> (render-board-html file page dest-frame)
                         (.then (fn [parts]
                                  (let [doc (build-prototype-document parts page dest-frame)
                                        anim (:animation interaction)
                                        ps' (deref proto-state*)
                                        from-id (:current-frame-id ps')
                                        from-doc (:html (deref state*))]
                                    (if (and anim from-doc)
                                      (swap! proto-state* assoc
                                             :transition {:from-id from-id
                                                          :from-doc from-doc
                                                          :to-id dest-id
                                                          :to-doc doc
                                                          :animation anim})
                                      ;; No animation (or no prior doc to animate from):
                                      ;; instant swap. Push the previous board onto the nav
                                      ;; stack so :prev-screen can rewind to it later, and
                                      ;; sync the URL `?index=` so the viewer header
                                      ;; breadcrumb / thumbnails reflect the new board.
                                      ;; Mark `rendered-frame-id*` so the render effect
                                      ;; (which will re-fire when current-frame-id changes)
                                      ;; skips its work and doesn't unmount the iframe.
                                      (do
                                        (swap! proto-state*
                                               (fn [s]
                                                 (-> s
                                                     (update :nav-stack conj from-id)
                                                     (assoc :current-frame-id dest-id)
                                                     (assoc :overlays []))))
                                        (reset! state* {:status     :ready
                                                        :html       doc
                                                        :error      nil
                                                        :updated-at (js/Date.now)})
                                        (mf/set-ref-val! rendered-frame-id* dest-id)
                                        (nav-to-frame-index! page dest-id))))))
                         (.catch (fn [^js err]
                                   (js/console.warn "Prototype navigate failed:" err))))))

                 (:open-overlay :toggle-overlay)
                 (when-let [dest-id (some-> (:destination interaction) str)]
                   (let [already (some (fn [o] (when (= (:id o) dest-id) o)) (:overlays ps))]
                     (if (and already (= action :toggle-overlay))
                       (swap! proto-state* update :overlays
                              (fn [xs] (into [] (remove (fn [o] (= (:id o) dest-id))) xs)))
                       (let [dest-frame (or (find-frame-by-id-str page dest-id)
                                            (get-in page [:objects (parse-uuid-safe dest-id)]))]
                         (when (and dest-frame base-frame source-shape)
                           (-> (render-board-html file page dest-frame)
                               (.then (fn [parts]
                                        (let [doc (build-prototype-document parts page dest-frame)
                                              rect (compute-overlay-rect page interaction
                                                                         source-shape base-frame
                                                                         dest-frame)]
                                          (swap! proto-state* update :overlays conj
                                                 {:id        dest-id
                                                  :source-id source-id
                                                  :rect      rect
                                                  :options   {:close-click-outside (boolean (:close-click-outside interaction))
                                                              :background-overlay  (boolean (:background-overlay interaction))}
                                                  :doc       doc}))))
                               (.catch (fn [^js err]
                                         (js/console.warn "Prototype open-overlay failed:" err)))))))))

                 :close-overlay
                 (let [target-id (or (some-> (:destination interaction) str)
                                     ;; The Penpot UI exposes "Close self" by leaving
                                     ;; destination empty — the source frame IS the
                                     ;; overlay being closed, so we identify it by
                                     ;; matching the source shape's frame id.
                                     (when source-shape
                                       (str (or (:frame-id source-shape) (:id source-shape)))))]
                   (when target-id
                     (swap! proto-state* update :overlays
                            (fn [xs] (into [] (remove (fn [o] (= (:id o) target-id))) xs)))))

                 :prev-screen
                 (let [stack (:nav-stack ps)
                       prev  (peek stack)]
                   (when prev
                     (when-let [prev-frame (find-frame-by-id-str page prev)]
                       (-> (render-board-html file page prev-frame)
                           (.then (fn [parts]
                                    (let [doc (build-prototype-document parts page prev-frame)]
                                      (swap! proto-state*
                                             (fn [s]
                                               (-> s
                                                   (update :nav-stack pop)
                                                   (assoc :current-frame-id prev)
                                                   (assoc :overlays []))))
                                      (reset! state* {:status     :ready
                                                      :html       doc
                                                      :error      nil
                                                      :updated-at (js/Date.now)})
                                      (mf/set-ref-val! rendered-frame-id* prev)
                                      (nav-to-frame-index! page prev))))
                           (.catch (fn [^js err]
                                     (js/console.warn "Prototype prev-screen failed:" err)))))))

                 ;; :open-url is dispatched locally inside the iframe
                 ;; and never round-trips to the parent — keeping the
                 ;; user-gesture context intact for pop-up blockers.
                 nil)))))]

    ;; Sync the prototype controller's `:current-frame-id` to the
    ;; `frame` prop whenever the prop changes from outside (e.g. the
    ;; viewer header pagination, or first mount). When the controller
    ;; itself drove the change — via :navigate / :prev-screen — the
    ;; URL update arrives one tick later and `:current-frame-id`
    ;; already matches, so this no-ops. While a transition is in
    ;; flight we leave state alone; the transition's `finished`
    ;; handler commits the new id and then the URL.
    (mf/with-effect [frame mode]
      (when (and (= mode :prototype) (some? frame))
        (let [id (str (:id frame))
              ps (deref proto-state*)]
          (when (and (nil? (:transition ps))
                     (not= (:current-frame-id ps) id))
            (reset! proto-state* {:current-frame-id id
                                  :nav-stack        []
                                  :overlays         []
                                  :transition       nil})))))

    ;; Re-render whenever the file, page, mode, or (for prototype
    ;; mode) the controller's `:current-frame-id` changes. Workspace
    ;; caches whole-page renders; prototype renders the currently-
    ;; picked board via `render-board-html` and wraps it in a runtime-
    ;; equipped iframe document so click/hover/after-delay interactions
    ;; fire.
    (mf/with-effect [file page mode current-frame-id]
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
          (let [fr    (or (find-frame-by-id-str page current-frame-id) frame)
                fr-id (some-> fr :id str)]
            (cond
              (nil? fr)
              (do (mf/set-ref-val! rendered-frame-id* nil)
                  (reset! state* {:status :empty :html nil :error nil :updated-at nil}))

              ;; The controller (navigate / prev-screen / transition
              ;; finish!) commits the destination doc to `state*` and
              ;; stamps `rendered-frame-id*` with the new id. When the
              ;; effect later re-fires for the same id, skip the work
              ;; — otherwise we'd reset `:status :loading` and unmount
              ;; the iframe that the controller (and React's keyed
              ;; layer reconciliation) just preserved.
              (and fr-id (= fr-id (mf/ref-val rendered-frame-id*)))
              nil

              :else
              (do
                (reset! state* {:status :loading :html nil :error nil :updated-at nil})
                (-> (render-board-html file page fr)
                    (.then (fn [parts]
                             (when-not @cancelled?
                               (reset! state*
                                       {:status     :ready
                                        :html       (build-prototype-document parts page fr)
                                        :error      nil
                                        :updated-at (js/Date.now)})
                               (mf/set-ref-val! rendered-frame-id* fr-id))))
                    (.catch on-error)))))

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
    ;; iframe AND for `:prototype:trigger` messages from the prototype
    ;; iframe. A single window-level listener handles both so we don't
    ;; pay for two registrations.
    (mf/with-effect []
      (let [handler (fn [^js e]
                      (let [data (.-data e)
                            result (read-selected data)]
                        (cond
                          (= result ::deselect) (reset! selected* nil)
                          (some? result)        (reset! selected* result)
                          :else                 (dispatch-prototype-trigger data))))]
        (.addEventListener js/window "message" handler)
        (fn [] (.removeEventListener js/window "message" handler))))

    ;; Animation orchestration. When `:transition` lands in proto-state*,
    ;; React renders BOTH the from- and to-iframes stacked in a
    ;; transition stage (see the JSX further down). This effect runs
    ;; the WAAPI animation against the wrapper divs, waits for it to
    ;; finish, then commits the new board into `:current-frame-id`
    ;; (which in turn drives the regular render effect to produce the
    ;; final single-iframe state) and syncs the URL `?index=` so a
    ;; refresh lands on the same board.
    (mf/with-effect [transition]
      (when transition
        (let [refs    (mf/ref-val layer-refs*)
              from-el (unchecked-get refs (:from-id transition))
              to-el   (unchecked-get refs (:to-id transition))
              anim    (:animation transition)
              kind    (:animation-type anim)
              dur     (max 1 (or (:duration anim) 300))
              easing  (easing->css (:easing anim))
              opts    #js {:duration dur :easing easing :fill "both"}
              raf     (volatile! nil)
              promises (volatile! [])
              animate!
              (fn [^js el keyframes]
                (when el (.animate el (clj->js keyframes) opts)))
              finish!
              (fn []
                (let [dest-id  (:to-id transition)
                      from-id  (:from-id transition)
                      dest-doc (:to-doc transition)]
                  ;; Commit destination first, then sync URL. The
                  ;; render effect WILL re-trigger when URL changes
                  ;; `frame` prop, but `rendered-frame-id*` below
                  ;; tells it the destination is already on screen
                  ;; so it skips its work — that keeps the
                  ;; destination iframe (which React preserved via
                  ;; the layer key) from being unmounted by a stale
                  ;; `:status :loading` reset.
                  (swap! proto-state*
                         (fn [s]
                           (-> s
                               (update :nav-stack conj from-id)
                               (assoc :current-frame-id dest-id)
                               (assoc :overlays [])
                               (assoc :transition nil))))
                  (reset! state* {:status     :ready
                                  :html       dest-doc
                                  :error      nil
                                  :updated-at (js/Date.now)})
                  (mf/set-ref-val! rendered-frame-id* dest-id)
                  (nav-to-frame-index! page dest-id)))]
          ;; Wait one paint for both iframes to be in the DOM before
          ;; animating — without this, getBoundingClientRect inside
          ;; the iframe runtime can race with the transform.
          (vreset! raf
                   (js/requestAnimationFrame
                    (fn []
                      ;; Slide direction conventions mirror the SVG viewer
                      ;; (interactions.cljs lines 319-618):
                      ;;   :way :in  → destination slides IN from off-screen,
                      ;;               source stays put
                      ;;   :way :out → source slides OUT exposing destination
                      ;;               (destination is already in place, no
                      ;;               animation on it)
                      ;; Push always moves both frames in lockstep.
                      ;;
                      ;; For `:way :out` we need the source to be ON TOP of the
                      ;; destination so the user sees it slide off — flip the
                      ;; stacking order via inline style. (The default CSS gives
                      ;; `.transition-to` z-index:2, which is correct for
                      ;; everything except `:slide :out`.)
                      (when (and (= kind :slide) (= :out (:way anim)))
                        (when from-el (set! (.. from-el -style -zIndex) "3"))
                        (when to-el   (set! (.. to-el -style -zIndex) "1")))
                      (let [from-anim
                            (case kind
                              :dissolve (animate! from-el [{:opacity 1} {:opacity 0}])
                              :slide    (when (= :out (:way anim))
                                          (animate! from-el [{:transform "translate(0,0)"}
                                                             {:transform (slide-axis-percent (:direction anim))}]))
                              :push     (let [[a b] (push-from-keyframes (:direction anim))]
                                          (animate! from-el [{:transform a} {:transform b}]))
                              nil)
                            to-anim
                            (case kind
                              :dissolve (animate! to-el [{:opacity 0} {:opacity 1}])
                              :slide    (when (not= :out (:way anim))
                                          (let [[a b] (slide-keyframes (:direction anim))]
                                            (animate! to-el [{:transform a} {:transform b}])))
                              :push     (let [[a b] (slide-keyframes (:direction anim))]
                                          (animate! to-el [{:transform a} {:transform b}]))
                              nil)
                            ps (cond-> []
                                 from-anim (conj (.-finished from-anim))
                                 to-anim   (conj (.-finished to-anim)))]
                        (vreset! promises ps)
                        (if (seq ps)
                          (-> (js/Promise.all (clj->js ps))
                              (.then finish!)
                              (.catch (fn [_] (finish!))))
                          (finish!))))))
          ;; Cleanup: cancel a queued RAF if the transition state is
          ;; replaced before it fires. In-flight WAAPI animations
          ;; complete on their own; their `finished` Promise resolution
          ;; is harmless to a stale `finish!` because `swap!` is
          ;; idempotent on the current state.
          (fn []
            (when-let [r @raf]
              (js/cancelAnimationFrame r))))))

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
          [:div {:class (stl/css :preview-stage)}
           (if (= mode :prototype)
             ;; Prototype mode: isolate the board in its own sized
             ;; stack so interactions and animations affect ONLY the
             ;; board, not the surrounding pane background. The stack
             ;; is sized to the current board's dimensions; during a
             ;; transition it expands to fit both from / to boards
             ;; (using max width / height) so neither gets clipped
             ;; while sliding. `.board-clip` wraps the animated
             ;; iframes with `overflow:hidden` so slide / push
             ;; animations can't visually escape the board area.
             ;; Overlays sit OUTSIDE the clip so they can extend past
             ;; the board edge (matching the SVG viewer's behaviour).
             ;;
             ;; The board iframes are rendered as a list of "layers"
             ;; keyed by `frame-id`. In steady state there's one
             ;; layer (the current board); during a navigate
             ;; transition there are two (from + to). When the
             ;; transition commits, the layers list shrinks back to
             ;; one — and because React reconciles by key, the
             ;; surviving layer's DOM element and iframe persist
             ;; without remounting. The browser never reloads the
             ;; iframe's srcDoc, eliminating the post-animation
             ;; flicker that earlier versions had.
             (let [proto-frame    (or (find-frame-by-id-str page current-frame-id) frame)
                   from-frame     (when transition (find-frame-by-id-str page (:from-id transition)))
                   to-frame       (when transition (find-frame-by-id-str page (:to-id transition)))
                   {pw :width ph :height} (board-dims proto-frame)
                   {fw :width fh :height} (board-dims (or from-frame proto-frame))
                   {tw :width th :height} (board-dims (or to-frame proto-frame))
                   stack-w        (if transition (max fw tw) pw)
                   stack-h        (if transition (max fh th) ph)
                   layers         (if transition
                                    [{:id (:from-id transition)
                                      :doc (:from-doc transition)
                                      :role "from"}
                                     {:id (:to-id transition)
                                      :doc (:to-doc transition)
                                      :role "to"}]
                                    (when (and current-frame-id html)
                                      [{:id current-frame-id
                                        :doc html
                                        :role "base"}]))
                   needs-backdrop? (some (fn [o]
                                           (let [opts (:options o)]
                                             (or (:background-overlay opts)
                                                 (:close-click-outside opts))))
                                         overlays)
                   close-all-bg-or-click
                   (fn []
                     ;; Click on backdrop: close every overlay that
                     ;; opted into close-click-outside. Mirrors the
                     ;; viewer's `on-click` handler in
                     ;; `viewer.cljs:157-164`.
                     (swap! proto-state* update :overlays
                            (fn [xs]
                              (into [] (remove (fn [o]
                                                 (get-in o [:options :close-click-outside])))
                                    xs))))]
               (when (and (pos? stack-w) (pos? stack-h))
                 [:div {:class (stl/css :board-stack)
                        :style {:width  (str stack-w "px")
                                :height (str stack-h "px")}}
                  [:div {:class (stl/css :board-clip)}
                   (for [{:keys [id doc role]} layers]
                     [:div {:key id
                            :class (stl/css :board-layer)
                            :data-role role
                            :ref #(set-layer-ref! id %)}
                      [:iframe {:class           (stl/css :preview-iframe)
                                :title           (tr "viewer.html-mode.iframe-title")
                                :src-doc         doc
                                ;; `allow-same-origin` is required so the iframe can
                                ;; load fonts and images with the user's session
                                ;; credentials. The only script inside is ours
                                ;; (`prototype-bridge-script`). See namespace
                                ;; docstring for the full threat model.
                                :sandbox         "allow-scripts allow-same-origin"
                                :referrer-policy "no-referrer"}]])]
                  (when (seq overlays)
                    [:*
                     (when needs-backdrop?
                       [:div {:class (stl/css :overlay-backdrop)
                              :on-click close-all-bg-or-click}])
                     (for [{:keys [id rect doc]} overlays]
                       [:div {:key id
                              :class (stl/css :overlay-frame)
                              :style {:left   (str (:x rect) "px")
                                      :top    (str (:y rect) "px")
                                      :width  (str (:width rect) "px")
                                      :height (str (:height rect) "px")}}
                        [:iframe {:class           (stl/css :preview-iframe)
                                  :title           (tr "viewer.html-mode.iframe-title")
                                  :src-doc         doc
                                  :sandbox         "allow-scripts allow-same-origin"
                                  :referrer-policy "no-referrer"}]])])]))

             ;; Non-prototype modes (workspace): iframe fills the
             ;; whole preview-stage as before. Pan/zoom + inspector
             ;; selection bridge live inside the iframe.
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
                       :referrer-policy "no-referrer"}])]

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
