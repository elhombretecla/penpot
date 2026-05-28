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
   [app.common.geom.shapes.bounds :as gsb]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.uuid :as uuid]
   [app.main.data.html-mode :as dhtml]
   [app.main.data.html-mode.adapter :as adapter]
   [app.main.data.html-mode.cache :as cache]
   [app.main.data.html-mode.converter-ctx :as cctx]
   [app.main.data.viewer :as dv]
   [app.main.fonts :as fonts]
   [app.main.refs :as refs]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.layout.tab-switcher :refer [tab-switcher*]]
   [app.main.ui.viewer.html-mode.components :refer [components-view*]]
   [app.main.ui.viewer.html-mode.design-tokens :refer [design-tokens-view*]]
   [app.main.ui.viewer.html-mode.device-view :refer [device-view-controls*]]
   [app.main.ui.viewer.html-mode.export-modal]
   [app.main.ui.viewer.html-mode.layers-tree :refer [layers-tree*]]
   [app.main.ui.viewer.html-mode.sidebar :refer [html-mode-sidebar*]]
   [app.util.dom :as dom]
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
   ;; The palette mirrors the inspector's box-model + the app accent
   ;; tokens so both speak the same language: selection = primary,
   ;; hover = quaternary, padding = success (green), spacing/distance =
   ;; warning (orange). Selection reads the live `--color-accent-primary`
   ;; from the parent (theme-aware via `allow-same-origin`), the rest are
   ;; the theme-stable token hexes.
   "var SEL=(function(){try{var v=(getComputedStyle(parent.document.body).getPropertyValue('--color-accent-primary')||'').trim();return v||'#6911d4';}catch(e){return '#6911d4';}})();"
   ;; Selection text colour: black on a light accent (e.g. dark-mode mint),
   ;; white on a dark one (e.g. light-mode purple) — so the name pill /
   ;; dimension badge stay legible whichever theme the primary comes from.
   "var SELFG=(function(c){c=(c||'').trim();if(c.charAt(0)!=='#')return '#fff';var h=c.slice(1);if(h.length===3){h=h.charAt(0)+h.charAt(0)+h.charAt(1)+h.charAt(1)+h.charAt(2)+h.charAt(2);}var r=parseInt(h.slice(0,2),16),g=parseInt(h.slice(2,4),16),b=parseInt(h.slice(4,6),16);var lum=(0.299*r+0.587*g+0.114*b)/255;return lum>0.6?'#000':'#fff';})(SEL);"
   "var HOV='#ff6fe0';"     ;; --color-accent-quaternary (hover)
   "var PAD='#2d9f8f';"     ;; --color-accent-success   (padding bands)
   "var DIST='#fe9c07';"    ;; --color-accent-warning   (spacing / distance bands)
   "var BAR=18;"            ;; name / dimension tag height in px
   ;; Penpot's UI font. The iframe is its own document so it doesn't get
   ;; the app's `@font-face`; we inline one (same-origin, served at
   ;; `/fonts`) so every overlay label renders in Work Sans.
   "var FONT='11px/1 worksans,\"Helvetica Neue\",Arial,sans-serif';"

   ;; ---- styles ----
   "var s=document.createElement('style');"
   "s.textContent='"
   "@font-face{font-family:worksans;src:url(/fonts/WorkSans-VariableFont.ttf);font-weight:100 900}"
   ".penpot-hm-overlay{position:absolute;pointer-events:none;box-sizing:border-box;z-index:2147483646;display:none}"
   ".penpot-hm-hover{outline:1.5px solid '+HOV+';outline-offset:-1px}"
   ".penpot-hm-selected{outline:1.5px solid '+SEL+';outline-offset:-1px}"
   ;; name pill — a tag at the top-left of the box.
   ".penpot-hm-name{position:absolute;left:-1.5px;top:0;transform:translateY(-100%);display:none;align-items:center;background:'+SEL+';color:'+SELFG+';font:'+FONT+';font-weight:600;height:'+BAR+'px;padding:0 6px;border-radius:3px 3px 3px 0;white-space:nowrap;max-inline-size:90vw;overflow:hidden;text-overflow:ellipsis;pointer-events:none}"
   ".penpot-hm-name.hov{background:'+HOV+';color:#fff}"
   ;; dimension badge — W / H below the box.
   ".penpot-hm-dim{position:absolute;left:50%;bottom:-5px;transform:translate(-50%,100%);display:none;align-items:center;gap:8px;background:'+SEL+';color:'+SELFG+';font:'+FONT+';height:'+BAR+'px;padding:0 7px;border-radius:3px;white-space:nowrap;pointer-events:none;box-shadow:0 1px 3px rgba(0,0,0,0.25)}"
   ".penpot-hm-dim.hov{background:'+HOV+';color:#fff}"
   ;; hatched spacing band + numeric badge (padding = green, distance = orange).
   ".penpot-hm-band{position:absolute;pointer-events:none;z-index:2147483645;display:none}"
   ".penpot-hm-pad{background-image:repeating-linear-gradient(-45deg,rgba(45,159,143,0.32) 0 5px,transparent 5px 10px);box-shadow:inset 0 0 0 1px rgba(45,159,143,0.5)}"
   ".penpot-hm-dist{background-image:repeating-linear-gradient(-45deg,rgba(254,156,7,0.34) 0 5px,transparent 5px 10px);box-shadow:inset 0 0 0 1px rgba(254,156,7,0.6)}"
   ".penpot-hm-badge{position:absolute;transform:translate(-50%,-50%);display:none;background:'+PAD+';color:#fff;font:'+FONT+';height:16px;line-height:16px;padding:0 5px;border-radius:3px;white-space:nowrap;pointer-events:none;z-index:2147483647;box-shadow:0 1px 2px rgba(0,0,0,0.28)}"
   ".penpot-hm-badge.dist{background:'+DIST+'}"
   "';"
   "document.head.appendChild(s);"

   ;; ---- outline overlays, each with a name pill + dimension badge ----
   "function mk(cls){var d=document.createElement('div');d.className='penpot-hm-overlay '+cls;document.body.appendChild(d);return d;}"
   "function child(parent,cls){var d=document.createElement('div');d.className=cls;parent.appendChild(d);return d;}"
   "var hoverEl=mk('penpot-hm-hover');"
   "var hoverName=child(hoverEl,'penpot-hm-name hov');"
   "var hoverDim=child(hoverEl,'penpot-hm-dim hov');"
   "var selEl=mk('penpot-hm-selected');"
   "var selName=child(selEl,'penpot-hm-name');"
   "var selDim=child(selEl,'penpot-hm-dim');"

   ;; ---- hatched spacing bands + numeric badges ----
   ;; Index order [top,right,bottom,left]. `pad*` show the selected
   ;; element's own padding (blue); `dist*` show the gap / insets between
   ;; the selected element and the hovered one (pink).
   "function band(cls){var d=document.createElement('div');d.className='penpot-hm-band '+cls;document.body.appendChild(d);return d;}"
   "function badge(cls){var d=document.createElement('div');d.className='penpot-hm-badge '+cls;document.body.appendChild(d);return d;}"
   "var padBands=[band('penpot-hm-pad'),band('penpot-hm-pad'),band('penpot-hm-pad'),band('penpot-hm-pad')];"
   "var padBadges=[badge(''),badge(''),badge(''),badge('')];"
   "var distBands=[band('penpot-hm-dist'),band('penpot-hm-dist'),band('penpot-hm-dist'),band('penpot-hm-dist')];"
   "var distBadges=[badge('dist'),badge('dist'),badge('dist'),badge('dist')];"

   "var currentSel=null;"
   ;; The shape the user has drilled INTO via double-click. Subsequent
   ;; single clicks pick its direct child on the cursor's ancestor
   ;; chain — the same "go down a level" semantics as the workspace canvas.
   ;; `null` means we're at the root (top-level shapes).
   "var drillParent=null;"
   ;; Tracks whether Control/Cmd is held; Ctrl+hover previews the
   ;; deepest shape ("select inside") instead of the
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
   ;;     the new top-level — clicking elsewhere breaks out of the
   ;;     previous frame.
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
   "function place(overlay,el){"
   "  var r=el.getBoundingClientRect();"
   "  overlay.style.left=(r.left+window.scrollX)+'px';"
   "  overlay.style.top=(r.top+window.scrollY)+'px';"
   "  overlay.style.width=r.width+'px';"
   "  overlay.style.height=r.height+'px';"
   "  overlay.style.display='block';"
   "}"
   "function hide(o){o.style.display='none';}"
   ;; Name pill: the shape's name (falls back to its tag).
   "function placeName(nameEl,el){"
   "  nameEl.textContent=el.getAttribute('data-name')||el.tagName.toLowerCase();"
   "  nameEl.style.display='inline-flex';"
   "}"
   ;; Dimension badge: `<width> x <height>` (rendered px).
   "function placeDim(dimEl,el){"
   "  var r=el.getBoundingClientRect();"
   "  dimEl.textContent=fmt(r.width)+' × '+fmt(r.height);"
   "  dimEl.style.display='inline-flex';"
   "}"
   ;; Position a hatched band + its centred numeric badge (page coords).
   ;; Hidden when the measured value rounds to ~0.
   "function setBand(bnd,bdg,x,y,w,h,val){"
   "  if(val<=0.5){hide(bnd);hide(bdg);return;}"
   "  bnd.style.left=(x+window.scrollX)+'px';bnd.style.top=(y+window.scrollY)+'px';"
   "  bnd.style.width=Math.max(0,w)+'px';bnd.style.height=Math.max(0,h)+'px';bnd.style.display='block';"
   "  bdg.textContent=fmt(val);"
   "  bdg.style.left=(x+w/2+window.scrollX)+'px';bdg.style.top=(y+h/2+window.scrollY)+'px';bdg.style.display='block';"
   "}"
   "function hidePadding(){for(var i=0;i<4;i++){hide(padBands[i]);hide(padBadges[i]);}}"
   "function hideDist(){for(var i=0;i<4;i++){hide(distBands[i]);hide(distBadges[i]);}}"

   ;; ---- the selected element's own padding (blue hatched bands) ----
   "function placePadding(){"
   "  hidePadding();"
   "  if(!currentSel)return;"
   "  var cs=getComputedStyle(currentSel);"
   "  var pt=parseFloat(cs.paddingTop)||0,pr=parseFloat(cs.paddingRight)||0,pb=parseFloat(cs.paddingBottom)||0,pl=parseFloat(cs.paddingLeft)||0;"
   "  var r=currentSel.getBoundingClientRect();"
   "  setBand(padBands[0],padBadges[0],r.left,r.top,r.width,pt,pt);"
   "  setBand(padBands[2],padBadges[2],r.left,r.bottom-pb,r.width,pb,pb);"
   "  setBand(padBands[3],padBadges[3],r.left,r.top+pt,pl,r.height-pt-pb,pl);"
   "  setBand(padBands[1],padBadges[1],r.right-pr,r.top+pt,pr,r.height-pt-pb,pr);"
   "}"

   ;; ---- distance between the selected element and the hovered one ----
   ;; Disjoint boxes → the gap on the separating axis (sibling spacing).
   ;; Hovered inside selected → all four insets (frame-padding redlines).
   "function placeDistances(){"
   "  hideDist();"
   "  if(!currentSel||!currentHover||currentHover===currentSel)return;"
   "  var H=currentHover.getBoundingClientRect(),S=currentSel.getBoundingClientRect();"
   "  if(H.top>=S.bottom-0.5){var x=Math.max(H.left,S.left),w=Math.min(H.right,S.right)-x;setBand(distBands[2],distBadges[2],x,S.bottom,(w>1?w:H.width),H.top-S.bottom,H.top-S.bottom);return;}"
   "  if(S.top>=H.bottom-0.5){var x2=Math.max(H.left,S.left),w2=Math.min(H.right,S.right)-x2;setBand(distBands[0],distBadges[0],x2,H.bottom,(w2>1?w2:H.width),S.top-H.bottom,S.top-H.bottom);return;}"
   "  if(H.left>=S.right-0.5){var y=Math.max(H.top,S.top),h=Math.min(H.bottom,S.bottom)-y;setBand(distBands[1],distBadges[1],S.right,y,H.left-S.right,(h>1?h:H.height),H.left-S.right);return;}"
   "  if(S.left>=H.right-0.5){var y2=Math.max(H.top,S.top),h2=Math.min(H.bottom,S.bottom)-y2;setBand(distBands[3],distBadges[3],H.right,y2,S.left-H.right,(h2>1?h2:H.height),S.left-H.right);return;}"
   "  setBand(distBands[0],distBadges[0],H.left,S.top,H.width,H.top-S.top,H.top-S.top);"
   "  setBand(distBands[2],distBadges[2],H.left,H.bottom,H.width,S.bottom-H.bottom,S.bottom-H.bottom);"
   "  setBand(distBands[3],distBadges[3],S.left,H.top,H.left-S.left,H.height,H.left-S.left);"
   "  setBand(distBands[1],distBadges[1],H.right,H.top,S.right-H.right,H.height,S.right-H.right);"
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
   "  if(!el || el===currentSel){hide(hoverEl);hide(hoverName);hide(hoverDim);hideDist();placePadding();return;}"
   "  place(hoverEl,el);"
   "  placeName(hoverName,el);"
   ;; With a selection active, hovering another element shows the spacing
   ;; between the two (and hides the selected element's padding to keep
   ;; the redlines readable). Without a selection, just show the size.
   "  if(currentSel){hide(hoverDim);hidePadding();placeDistances();}"
   "  else{hideDist();placeDim(hoverDim,el);}"
   "}"
   "document.addEventListener('mousemove',function(e){refreshHover(e.target);},{capture:true});"
   "document.addEventListener('mouseleave',function(){hide(hoverEl);hide(hoverName);hide(hoverDim);hideDist();placePadding();currentHover=null;lastMoveTarget=null;});"

   ;; ---- click ----
   ;; Selection rules:
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
   "  if(!el){currentSel=null;drillParent=null;hide(selEl);hide(selName);hide(selDim);hidePadding();hide(hoverEl);hide(hoverName);hide(hoverDim);hideDist();send({type:'penpot:html-mode:deselect'});return;}"
   "  e.preventDefault();e.stopPropagation();"
   "  currentSel=el;"
   "  hide(hoverEl);hide(hoverName);hide(hoverDim);hideDist();"
   "  place(selEl,el);placeName(selName,el);placeDim(selDim,el);placePadding();"
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
   "  if(currentSel){place(selEl,currentSel);placeName(selName,currentSel);placeDim(selDim,currentSel);}"
   "  if(currentHover && currentHover!==currentSel){place(hoverEl,currentHover);placeName(hoverName,currentHover);}"
   "  if(currentHover && currentHover!==currentSel && currentSel){placeDistances();}else{placePadding();}"
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
   "    hide(hoverEl);hide(hoverName);hide(hoverDim);hideDist();"
   "    place(selEl,el);placeName(selName,el);placeDim(selDim,el);placePadding();"
   "    centerOnElements(els, !!d.fit, d.padding);"
   ;; Re-place after the transform changed so the outline + spacing land
   ;; on the now-shifted element.
   "    place(selEl,el);placeName(selName,el);placeDim(selDim,el);placePadding();"
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
   ;;   • space + left-mouse drag    → pan (Penpot convention)
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

   ;; ---- touch tap ripple ----
   ;; Always registered; only paints when the parent has flagged this
   ;; document with `body.penpot-touch-mode` (Touch interaction type).
   ;; The ripple lives in the iframe where the pointer events are, so no
   ;; cross-frame wiring is needed — the parent just toggles the class.
   "document.addEventListener('pointerdown',function(e){"
   "  if(!document.body.classList.contains('penpot-touch-mode')) return;"
   "  var r=document.createElement('div');"
   "  r.className='penpot-tap-ripple';"
   "  r.style.left=e.clientX+'px';"
   "  r.style.top=e.clientY+'px';"
   "  document.body.appendChild(r);"
   "  r.addEventListener('animationend',function(){ if(r.parentNode) r.parentNode.removeChild(r); });"
   "},{capture:true, passive:true});"

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
  [{:keys [html fonts-css tokens-css interactions]} page frame & [{:keys [transparent-bg?]}]]
  ;; Both boards and overlays render with a transparent body so the frame's
  ;; own (possibly rounded / partially transparent) background is the only
  ;; thing painted — otherwise the page background fills the square iframe
  ;; and leaks past the frame's border-radius at the corners (showing white).
  ;; With a transparent body the rounded corners reveal the parent
  ;; `.preview-stage` (also seen through the transparent `.board-stack`).
  (let [bg       (if transparent-bg? "transparent" (page-background page))
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
     ;; Device-view sizing: the converter renders the board root with its
     ;; FIXED design width/height. Forcing it to fill the body lets the
     ;; parent resize the board purely by resizing the `.board-stack`
     ;; (and thus this iframe) — responsive content reflows, no rebuild /
     ;; reload of the srcDoc needed. At the board's design size this is a
     ;; no-op (100% == the natural size).
     "  [data-id=\"" root-id "\"] { inline-size: 100% !important; block-size: 100% !important; }\n"
     ;; Touch-input simulation. The parent toggles `body.penpot-touch-mode`
     ;; (mirroring the `penpot-show-interactions` mechanism) when the user
     ;; picks the Touch interaction type. We swap the cursor for a finger-
     ;; sized translucent ring and the bridge script spawns a tap ripple on
     ;; pointerdown. `*` + `!important` so links / buttons don't restore the
     ;; pointer cursor.
     "  body.penpot-touch-mode, body.penpot-touch-mode * {\n"
     "    cursor: url(\"data:image/svg+xml,%3Csvg%20xmlns='http://www.w3.org/2000/svg'%20width='32'%20height='32'%3E%3Ccircle%20cx='16'%20cy='16'%20r='13'%20fill='white'%20fill-opacity='0.35'%20stroke='white'%20stroke-opacity='0.95'%20stroke-width='3'/%3E%3Ccircle%20cx='16'%20cy='16'%20r='13'%20fill='none'%20stroke='black'%20stroke-opacity='0.6'%20stroke-width='1.5'/%3E%3C/svg%3E\") 16 16, auto !important;\n"
     "  }\n"
     "  @keyframes penpot-tap-ripple {\n"
     "    0%   { transform: translate(-50%, -50%) scale(0.4); opacity: 0.5; }\n"
     "    100% { transform: translate(-50%, -50%) scale(1.8); opacity: 0; }\n"
     "  }\n"
     "  .penpot-tap-ripple {\n"
     "    position: fixed; inline-size: 44px; block-size: 44px; margin: 0;\n"
     "    border-radius: 50%; background: rgb(0 0 0 / 0.28); pointer-events: none;\n"
     "    z-index: 2147483647; animation: penpot-tap-ripple 0.45s ease-out forwards;\n"
     "  }\n"
     ;; On-demand highlight of every shape that carries a prototype
     ;; interaction. The parent toggles `body.penpot-show-interactions`
     ;; (for 2s) when the user clicks the pane background, and also sets
     ;; `--penpot-highlight-color` on the iframe body to the app's
     ;; current `--color-accent-primary` (purple in light theme, green
     ;; in dark theme) — the iframe is its own document so it can't
     ;; inherit the app's CSS variables, hence the explicit injection.
     ;; The fallback (#6911d4, the light-theme purple) covers the brief
     ;; window before the variable is set. The pulse + outline mirror
     ;; the design-token "used by" highlight so both read as one effect.
     ;; `[data-prototype-interactive]` is set by the bridge script's
     ;; `annotate()` on every shape with a user-triggered interaction.
     "  @keyframes penpot-prototype-pulse {\n"
     "    0%   { box-shadow: 0 0 0 0    color-mix(in srgb, var(--penpot-highlight-color, #6911d4) 85%, transparent); }\n"
     "    70%  { box-shadow: 0 0 0 22px transparent; }\n"
     "    100% { box-shadow: 0 0 0 0    transparent; }\n"
     "  }\n"
     "  body.penpot-show-interactions [data-prototype-interactive] {\n"
     "    outline: 2px solid var(--penpot-highlight-color, #6911d4) !important;\n"
     "    outline-offset: -1px;\n"
     "    animation: penpot-prototype-pulse 1.4s ease-out infinite;\n"
     "  }\n"
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
   `ctsi/calc-overlay-position`.

   `calc-overlay-position` positions the overlay's *bounds box* — which
   includes the padding added by shadows / blur / overflowing children
   (`gsb/get-object-bounds`), NOT the frame's `selrect`. Since we render
   the iframe at the frame's `selrect` size, we shift the result by the
   selrect's offset within the bounds box so the visible frame lands
   exactly where the SVG viewer puts it (the viewer achieves the same
   alignment via its `calculate-delta`). Without this, an overlay with a
   shadow is mis-centred by half the shadow padding."
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
        srect            (:selrect dest-frame)
        bounds           (gsb/get-object-bounds objects dest-frame)
        off-x            (- (:x srect) (:x bounds))
        off-y            (- (:y srect) (:y bounds))]
    {:x      (+ (:x pos) off-x)
     :y      (+ (:y pos) off-y)
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

(defn- run-overlay-anim!
  "Play an overlay enter / exit animation on `el` with the Web Animations
   API and return the `Animation` (so callers can await `.finished`), or
   nil when there's nothing to animate. Penpot allows only `:dissolve`
   and `:slide` for overlays (`:push` is navigate-only). `exit?` reverses
   it: dissolve fades out; slide leaves toward the inverted direction —
   matching the SVG viewer's `invert-direction` on close."
  [^js el animation exit?]
  (when (and el animation)
    (let [kind   (:animation-type animation)
          dur    (max 1 (or (:duration animation) 300))
          easing (easing->css (:easing animation))
          opts   #js {:duration dur :easing easing :fill "both"}
          frames (case kind
                   :dissolve (if exit? [{:opacity 1} {:opacity 0}] [{:opacity 0} {:opacity 1}])
                   :slide    (let [dir (if exit?
                                         (:direction (ctsi/invert-direction animation))
                                         (:direction animation))
                                   off (slide-axis-percent dir)]
                               (if exit?
                                 [{:transform "translate(0,0)"} {:transform off}]
                                 [{:transform off} {:transform "translate(0,0)"}]))
                   nil)]
      (when frames
        (.animate el (clj->js frames) opts)))))

(mf/defc overlay-frame*
  "One mounted prototype overlay: a positioned iframe rendering the
   overlay board. Plays the entrance animation once on mount, and when
   the parent flags `:closing?` plays the exit animation and only then
   calls `on-closed` — deferring the actual removal from state so the
   iframe doesn't vanish mid-transition. Overlays without an animation
   appear / disappear instantly."
  {::mf/private true}
  [{:keys [overlay on-closed]}]
  (let [{:keys [id rect doc animation closing?]} overlay
        el-ref (mf/use-ref nil)]
    ;; Entrance, once on mount. The trailing `nil` matters: an effect
    ;; body must return a cleanup fn or nothing — `run-overlay-anim!`
    ;; returns an `Animation`, which React would otherwise reject.
    (mf/with-effect []
      (run-overlay-anim! (mf/ref-val el-ref) animation false)
      nil)
    ;; Exit when the parent flags `:closing?`, then remove from state.
    (mf/with-effect [closing?]
      (when closing?
        (if-let [^js a (run-overlay-anim! (mf/ref-val el-ref) animation true)]
          (-> (.-finished a)
              (.then (fn [_] (on-closed id)))
              (.catch (fn [_] (on-closed id))))
          (on-closed id)))
      nil)
    [:div {:class (stl/css :overlay-frame)
           :ref el-ref
           :style {:left   (str (:x rect) "px")
                   :top    (str (:y rect) "px")
                   :width  (str (:width rect) "px")
                   :height (str (:height rect) "px")}}
     [:iframe {:class           (stl/css :preview-iframe)
               :title           (tr "viewer.html-mode.iframe-title")
               :src-doc         doc
               :sandbox         "allow-scripts allow-same-origin"
               :referrer-policy "no-referrer"}]]))

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
        ;; Device-view settings (PROTOTYPING tab only). Pure visualization
        ;; layer: overrides the board-stack size, toggles a touch cursor /
        ;; device mockup, and recolors the preview stage. Lives here (not in
        ;; `state*`) so the chosen size survives board navigations; session
        ;; only — it resets on reload. `:size-override` nil means "use the
        ;; board's design size".
        device-view* (mf/use-state {:size-override nil
                                    :preset-name   nil
                                    :interaction   :mouse
                                    :mockup?       false
                                    :bg-color      nil})
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
        ;; The `.preview-stage` DOM node. The device-view touch-mode effect
        ;; queries every iframe under it (prototype boards + overlays) to
        ;; toggle the touch cursor class — same approach as
        ;; `highlight-interactions!`, but driven by an effect rather than a
        ;; click, so it needs an explicit ref.
        stage-ref     (mf/use-ref nil)
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
        ;; Live mirrors of the controller state. The window `message`
        ;; listener is registered once (`[]` deps) and the dispatch it
        ;; calls is memoised by `[file page]`, so its closure reads
        ;; `state*` / `proto-state*` as the per-render SNAPSHOT captured
        ;; when it was created (rumext `use-state` derefs are
        ;; render-time values, not a live cell) — i.e. the initial
        ;; `nil` board id / html, even after navigations update them.
        ;; That's why animated navigate fell back to instant (stale
        ;; `from-doc` nil) and overlays never opened (stale `base-frame`
        ;; nil). Mirroring the current values into refs — which ARE
        ;; stable live cells — lets the dispatch read them correctly at
        ;; trigger time.
        proto-live*   (mf/use-ref nil)
        state-html*   (mf/use-ref nil)
        {:keys [status html error updated-at]} (deref state*)
        {:keys [current-frame-id overlays transition]} (deref proto-state*)
        device-view  (deref device-view*)
        on-device-view-change
        (mf/use-fn (fn [m] (swap! device-view* merge m)))
        _ (mf/set-ref-val! proto-live* (deref proto-state*))
        _ (mf/set-ref-val! state-html* html)
        selected (deref selected*)
        ;; Subscribe to the shared viewer zoom (the same value the
        ;; SVG viewer renders at). The header's zoom widget dispatches
        ;; `dv/increase-zoom` / `dv/decrease-zoom` / `dv/reset-zoom` /
        ;; `dv/zoom-to-fit` / `dv/zoom-to-fill`, all of which mutate
        ;; this. We project it onto the rendered content via a CSS
        ;; `zoom` property on a wrapper div — that scales layout AND
        ;; visual (unlike `transform: scale` which only scales
        ;; visual), so the surrounding `.preview-stage`'s
        ;; `overflow: auto` correctly engages when zoomed past 100%.
        viewer-local (mf/deref refs/viewer-local)
        zoom         (or (:zoom viewer-local) 1)

        request-refresh
        (mf/use-fn
         (fn []
           (mf/set-ref-val! last-refresh* (js/Date.now))
           (st/emit! (dhtml/refresh-viewer-bundle))))

        ;; Click on the pane background (outside the board) while in
        ;; prototype mode: flash the pulse highlight on every shape that
        ;; carries an interaction, for 2s, so the user can tell at a
        ;; glance which elements respond to input. A click that lands on
        ;; the board never reaches this handler — the iframe swallows
        ;; its own clicks — so any click we see here is on the
        ;; background and we don't need to test the event target.
        ;;
        ;; The highlight is driven by toggling `penpot-show-interactions`
        ;; on the board iframe's `<body>` (the iframe is same-origin via
        ;; `allow-same-origin`, so the parent can reach its
        ;; `contentDocument` directly — the same DOM-mutation pattern
        ;; `design_tokens.cljs` uses). We toggle every iframe under the
        ;; stage rather than looking one up by id, so it works during a
        ;; navigate transition (two boards) and is robust to the
        ;; current-frame bookkeeping. In non-prototype modes the iframe
        ;; lacks the rule + annotations, so the class is a no-op there.
        highlight-interactions!
        (mf/use-fn
         (fn [^js e]
           (let [^js stage   (.-currentTarget e)
                 ^js iframes (.querySelectorAll stage "iframe")
                 n           (.-length iframes)
                 ;; Resolve the app's current primary accent (purple in
                 ;; light theme, green in dark). The theme class lives on
                 ;; `<body>`, so read the computed value from there — the
                 ;; variable isn't set on `<html>`. We inject it into each
                 ;; iframe because the iframe can't inherit the app's CSS
                 ;; variables across the document boundary.
                 color       (.trim (dom/get-css-variable "--color-accent-primary" (.-body js/document)))]
             (dotimes [i n]
               (let [^js iframe (aget iframes i)]
                 (when-let [^js body (some-> iframe (.-contentDocument) (.-body))]
                   (when (seq color)
                     (.setProperty (.-style body) "--penpot-highlight-color" color))
                   (.add (.-classList body) "penpot-show-interactions")
                   (js/setTimeout
                    (fn []
                      (when-let [^js b (some-> iframe (.-contentDocument) (.-body))]
                        (.remove (.-classList b) "penpot-show-interactions")))
                    2000)))))))

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
                   ps           (mf/ref-val proto-live*)
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
                                  (let [doc (build-prototype-document parts page dest-frame {:transparent-bg? true})
                                        anim (:animation interaction)
                                        ps' (mf/ref-val proto-live*)
                                        from-id (:current-frame-id ps')
                                        from-doc (mf/ref-val state-html*)]
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
                       ;; Toggle off: flag it `:closing?` so `overlay-frame*`
                       ;; plays the exit animation before it's removed.
                       (swap! proto-state* update :overlays
                              (fn [xs] (mapv (fn [o] (if (= (:id o) dest-id) (assoc o :closing? true) o)) xs)))
                       (let [dest-frame (or (find-frame-by-id-str page dest-id)
                                            (get-in page [:objects (parse-uuid-safe dest-id)]))]
                         (when (and dest-frame base-frame source-shape)
                           (-> (render-board-html file page dest-frame)
                               (.then (fn [parts]
                                        (let [doc (build-prototype-document parts page dest-frame {:transparent-bg? true})
                                              rect (compute-overlay-rect page interaction
                                                                         source-shape base-frame
                                                                         dest-frame)]
                                          (swap! proto-state* update :overlays conj
                                                 {:id        dest-id
                                                  :source-id source-id
                                                  :rect      rect
                                                  :animation (:animation interaction)
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
                     ;; Flag `:closing?` so `overlay-frame*` plays the exit
                     ;; animation; it removes itself from state on finish.
                     (swap! proto-state* update :overlays
                            (fn [xs] (mapv (fn [o] (if (= (:id o) target-id) (assoc o :closing? true) o)) xs)))))

                 :prev-screen
                 (let [stack (:nav-stack ps)
                       prev  (peek stack)]
                   (when prev
                     (when-let [prev-frame (find-frame-by-id-str page prev)]
                       (-> (render-board-html file page prev-frame)
                           (.then (fn [parts]
                                    (let [doc (build-prototype-document parts page prev-frame {:transparent-bg? true})]
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
        ;; Forget the prototype skip-guard whenever we're NOT in
        ;; prototype mode. The guard (`rendered-frame-id*`, checked
        ;; below) lets a controller-driven navigate/transition keep its
        ;; freshly-committed board doc instead of being clobbered by a
        ;; `:status :loading` reset — but the ref outlives a mode
        ;; switch while the doc it vouches for does NOT: Workspace and
        ;; Design Tokens overwrite `state*` with a non-board document.
        ;; If we kept the stamp, returning to Prototype on the SAME
        ;; frame would make the guard wrongly skip, leaving the
        ;; full-page Workspace doc rendered inside the board-sized
        ;; `.board-stack` — the board appears mispositioned until a
        ;; reload clears the ref. Clearing it here forces a fresh board
        ;; render on re-entry, matching the reload behaviour.
        (when (not= mode :prototype)
          (mf/set-ref-val! rendered-frame-id* nil))
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
                                        :html       (build-prototype-document parts page fr {:transparent-bg? true})
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

    ;; Reset zoom to 100% whenever the user enters HTML Mode (mount)
    ;; OR flips between the Workspace and Prototype tabs. The viewer's
    ;; `:viewer-local :zoom` is shared with the SVG viewer sections, so
    ;; without this a zoom level set in (say) Interactions mode would
    ;; carry over into HTML Mode and surprise the user. Design Tokens
    ;; mode renders its own layout (no iframe, no zoom), so we skip it.
    (mf/with-effect [mode]
      (when (or (= mode :prototype) (= mode :workspace))
        (st/emit! dv/reset-zoom)))

    ;; Catch Ctrl/Cmd + `+`/`=`/`-`/`0` keyboard shortcuts and dispatch
    ;; the same `dv/*` zoom actions the header widget uses, so the
    ;; CSS-`zoom` wrapper around the rendered content updates in sync
    ;; (the widget's percent label stays accurate, and Fit/Fill via
    ;; the widget still compose correctly). preventDefault swallows
    ;; the browser's native page-zoom — otherwise the host page zooms
    ;; instead.
    ;;
    ;; No-op when focus is inside an input / textarea / contenteditable
    ;; so form fields keep working.
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
                                       ("+" "=") dv/increase-zoom
                                       ("-" "_") dv/decrease-zoom
                                       "0"       dv/reset-zoom
                                       nil)]
                          (when action
                            (.preventDefault e)
                            (st/emit! action)))))]
        (.addEventListener js/window "keydown" handler true)
        (fn [] (.removeEventListener js/window "keydown" handler true))))

    ;; Device-view touch cursor. Toggle `body.penpot-touch-mode` on every
    ;; iframe under the stage so the CSS cursor + tap ripple (both baked
    ;; into the prototype document) engage. Re-runs when the interaction
    ;; type flips or when a new board / overlay doc is rendered; the per-
    ;; iframe `load` listener re-applies the class once an iframe finishes
    ;; loading its (async) srcDoc.
    (mf/with-effect [(:interaction device-view) html overlays mode]
      (when (= mode :prototype)
        (let [touch?   (= (:interaction device-view) :touch)
              stage    (mf/ref-val stage-ref)
              iframes  (some-> ^js stage (.querySelectorAll "iframe"))
              n        (if iframes (.-length iframes) 0)
              apply!   (fn [^js iframe]
                         (when-let [^js body (some-> iframe (.-contentDocument) (.-body))]
                           (if touch?
                             (.add (.-classList body) "penpot-touch-mode")
                             (.remove (.-classList body) "penpot-touch-mode"))))
              attached (volatile! [])]
          (dotimes [i n]
            (let [^js iframe (aget iframes i)
                  on-load    (fn [] (apply! iframe))]
              (apply! iframe)
              (.addEventListener iframe "load" on-load)
              (vswap! attached conj [iframe on-load])))
          (fn []
            (doseq [[^js iframe on-load] @attached]
              (.removeEventListener iframe "load" on-load))))))

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
                                  :label (tr "viewer.html-mode.toolbar.design-tokens")}
                                 {:id "components"
                                  :label (tr "viewer.html-mode.toolbar.components")}]
                          :selected mode-str
                          :on-change on-mode-change}]
       ;; Device-view controls live in the toolbar's right zone
       ;; (`justify-self: end`). Prototype tab only — the workspace /
       ;; design-token views have no resizable board.
       (when (= mode :prototype)
         [:> device-view-controls*
          {:settings device-view
           :default-dims (board-dims (or (find-frame-by-id-str page current-frame-id) frame))
           :on-change on-device-view-change}])]

      (cond
        ;; Design Tokens mode renders its own panel layout (left sub-
        ;; sidebar with sub-tabs + main content area), so it bypasses
        ;; the iframe-driven `:empty/:loading/:error/:ready` state
        ;; machine entirely. `file` carries the `:tokens-lib` the
        ;; DTCG JSON exporter consumes.
        (= mode :design-tokens)
        [:> design-tokens-view* {:page page :file file}]

        ;; Components mode (Storybook-like browser) also bypasses the
        ;; iframe state machine — it manages its own per-component
        ;; preview iframes via the html-converter.
        (= mode :components)
        [:> components-view* {:page page :file file}]

        :else
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
          [:div {:class (stl/css :preview-stage)
                 :ref stage-ref
                 ;; Device-view background override (prototype tab only):
                 ;; recolors the stage that surrounds the board, NOT the
                 ;; board itself. Kept as a literal style map (with a
                 ;; conditional value) so rumext camelCases the key at
                 ;; compile time; a dynamic map expression would leave the
                 ;; kebab key untouched and React would ignore it. nil value
                 ;; → no inline override, so the scss default grey shows.
                 :style {:background-color (when (= mode :prototype)
                                             (:bg-color device-view))}
                 :on-click highlight-interactions!}
           [:div {:class (stl/css :zoom-container)
                  :style {:zoom zoom}}
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
                   ;; Device-view size override: when the user picks a preset
                   ;; or types a custom size, the board-stack uses those dims
                   ;; instead of the board's design size. The iframe content
                   ;; reflows because the board root is forced to fill the
                   ;; body (see `build-prototype-document`). During a navigate
                   ;; transition we keep the natural board dims so neither the
                   ;; from- nor the to-board gets clipped mid-slide; the stack
                   ;; snaps back to the override once the transition commits.
                   override       (:size-override device-view)
                   {pw :width ph :height} (or override (board-dims proto-frame))
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
                     ;; Click on backdrop: flag every close-click-outside
                     ;; overlay as `:closing?` so it plays its exit
                     ;; animation; `overlay-frame*` removes each from state
                     ;; when the animation settles. Mirrors the viewer's
                     ;; `on-click` handler in `viewer.cljs:157-164`.
                     (swap! proto-state* update :overlays
                            (fn [xs]
                              (mapv (fn [o]
                                      (if (get-in o [:options :close-click-outside])
                                        (assoc o :closing? true)
                                        o))
                                    xs))))
                   on-overlay-closed
                   (fn [id]
                     (swap! proto-state* update :overlays
                            (fn [xs] (into [] (remove (fn [o] (= (:id o) id))) xs))))]
               (when (and (pos? stack-w) (pos? stack-h))
                 (let [board-stack
                       ;; `mf/html` compiles this hiccup to a React element
                       ;; up front. Without it, binding the vector in a let
                       ;; and returning it as a dynamic child leaves it an
                       ;; uncompiled CLJS vector, which React iterates as a
                       ;; collection — rendering `:div` as a child and
                       ;; throwing "objects are not valid as a React child".
                       (mf/html
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
                     (for [o overlays]
                       [:> overlay-frame* {:key       (:id o)
                                           :overlay   o
                                           :on-closed on-overlay-closed}])])])]
                   ;; Optional decorative device frame (bezel) around the
                   ;; board. Rendered as a wrapper so the previewed board
                   ;; keeps its EXACT pixel size (a border on `.board-stack`
                   ;; would shrink the content under border-box).
                   (if (:mockup? device-view)
                     [:div {:class (stl/css :device-frame)} board-stack]
                     board-stack))))

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
                       :referrer-policy "no-referrer"}])]]

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
