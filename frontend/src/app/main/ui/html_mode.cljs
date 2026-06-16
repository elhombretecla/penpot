;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.html-mode
  "HTML Mode — a standalone top-level mode (route `:html-mode`) that
   renders Penpot pages as real HTML.

   This namespace is the entry of the `:main-html-mode` JS module: it
   hosts `html-mode-page*` (state lifecycle + permission gate + header)
   and the section component below it.

   The section renders the current Penpot page as HTML (via
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
     (in `app.main.ui.html-mode.bridge-scripts`) is the entire
     JS payload; the converter output is style-only and never emits
     `<script>` tags. If a future converter version started emitting
     third-party scripts, this calculus must be revisited.

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
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.html-mode :as dhtml]
   [app.main.data.html-mode.adapter :as adapter]
   [app.main.data.html-mode.cache :as cache]
   [app.main.data.html-mode.converter-ctx :as cctx]
   [app.main.data.html-mode.prototype :as proto]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.ds.product.loader :refer [loader*]]
   [app.main.ui.html-mode.components :refer [components-view*]]
   [app.main.ui.html-mode.design-tokens :refer [design-tokens-view*]]
   [app.main.ui.html-mode.export-modal]
   [app.main.ui.html-mode.header :refer [header*]]
   [app.main.ui.html-mode.layers-tree :refer [layers-tree*]]
   [app.main.ui.html-mode.preview-doc :as pdoc]
   [app.main.ui.html-mode.refs :as hrefs]
   [app.main.ui.html-mode.sidebar :refer [html-mode-sidebar*]]
   [app.main.ui.modal :refer [modal-container*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

;; ---------------------------------------------------------------------------
;; Converter wiring
;;
;; The JS context builder lives in `app.main.data.html-mode.converter-ctx`
;; so it can be shared with the Export modal without dragging the renderer
;; into the modal's require graph.

;; ---------------------------------------------------------------------------
;; Fonts + document assembly
;;
;; `@font-face` collection and the HTML document builders (workspace,
;; prototype, and static preview docs) live in
;; `app.main.ui.html-mode.preview-doc` (aliased `pdoc`), shared
;; with the Design Tokens and Components views. The inline bridge
;; scripts injected into those documents live in
;; `app.main.ui.html-mode.bridge-scripts`.

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
              (pdoc/render-fonts-css-async file page)])
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

;; NOTE: the pure projection / parsing helpers behind the prototype
;; controller (interaction harvesting, the postMessage protocol, overlay
;; geometry, WAAPI keyframe vocabulary) live in
;; `app.main.data.html-mode.prototype` (aliased `proto`) so they can be
;; unit-tested without React.

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
            ix         (proto/harvest-interactions page)]
        (-> (js/Promise.all
             #js [(-> (js/Promise.resolve)
                      (.then (fn [] (cv/convertShape js-shape js-objects ctx)))
                      (.then (fn [^js result] (.-html result))))
                  (pdoc/render-fonts-css-async file page)])
            (.then (fn [^js parts]
                     (let [result {:html         (aget parts 0)
                                   :fonts-css    (aget parts 1)
                                   :tokens-css   (or tokens-css "")
                                   :interactions ix}]
                       (prototype-cache-put! k result)
                       result))))))))

;; ---------------------------------------------------------------------------
;; Prototype parent-side controller
;;
;; The iframe runtime emits `penpot:prototype:trigger` messages. The
;; parent decides what each action does: navigate swaps the rendered
;; board (with optional WAAPI animation across two stacked iframes);
;; open/toggle/close-overlay maintain a list of mounted overlay
;; iframes positioned via `ctsi/calc-overlay-position` (the same math
;; the SVG viewer uses); prev-screen pops a nav stack.

(defn- nav-to-frame!
  "Sync the URL `?frame-id=` to the currently displayed board. Must be
   called on every controller-driven prototype navigation (animated,
   instant, or prev-screen) so external consumers — the page header's
   board picker, link-sharing, reloads — see the current board. No-op
   when the id isn't a top-level frame, which keeps overlay actions
   (whose destination may live deeper in `:objects`) from corrupting
   the query string."
  [page frame-id-str]
  (when (and frame-id-str
             (some (fn [f] (= (str (:id f)) frame-id-str)) (:frames page)))
    (st/emit! (dhtml/go-to-frame frame-id-str))))

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
          easing (proto/easing->css (:easing animation))
          opts   #js {:duration dur :easing easing :fill "both"}
          frames (case kind
                   :dissolve (if exit? [{:opacity 1} {:opacity 0}] [{:opacity 0} {:opacity 1}])
                   :slide    (let [dir (if exit?
                                         (:direction (ctsi/invert-direction animation))
                                         (:direction animation))
                                   off (proto/slide-axis-percent dir)]
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
        ;; `html-mode` is `:workspace` (default), `:prototype`,
        ;; `:design-tokens` or `:components`. It comes from the URL
        ;; `?mode=` query param so the selection is shareable; the
        ;; header's mode-zone emits `dhtml/go-to-mode` to flip it.
        mode      (or html-mode :workspace)
        mode-str  (name mode)
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
        ;; Device-view settings (Prototype tab). The controls live in the
        ;; header now (next to Zoom); the value is held in mode-local
        ;; state and read here for board sizing, touch mode and the stage
        ;; background.
        device-view  (mf/deref hrefs/device-view)
        ;; Workspace preview background override (nil = page's own bg).
        ;; The picker lives in the header now (next to Share); the value
        ;; is held in mode-local state and read here for the render.
        workspace-bg (mf/deref hrefs/workspace-bg)
        _ (mf/set-ref-val! proto-live* (deref proto-state*))
        _ (mf/set-ref-val! state-html* html)
        selected (deref selected*)
        ;; Subscribe to the mode-local zoom. The page header's zoom
        ;; widget dispatches `dhtml/increase-zoom` / `decrease-zoom` /
        ;; `reset-zoom`, all of which mutate `[:html-mode-local :zoom]`
        ;; — fully independent from the SVG viewer's zoom. We project
        ;; it onto the rendered content via a CSS `zoom` property on a
        ;; wrapper div — that scales layout AND visual (unlike
        ;; `transform: scale` which only scales visual), so the
        ;; surrounding `.preview-stage`'s `overflow: auto` correctly
        ;; engages when zoomed past 100%.
        zoom         (or (mf/deref hrefs/zoom) 1)

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
           (when-let [{:keys [source-id interaction]} (proto/read-prototype-trigger msg)]
             (let [action       (:action-type interaction)
                   ps           (mf/ref-val proto-live*)
                   base-id      (:current-frame-id ps)
                   base-frame   (when base-id (proto/find-frame-by-id-str page base-id))
                   source-uuid  (uuid/parse* source-id)
                   source-shape (when source-uuid (get (:objects page) source-uuid))]
               (case action
                 :navigate
                 (when-let [dest-id (some-> (:destination interaction) str)]
                   (when-let [dest-frame (proto/find-frame-by-id-str page dest-id)]
                     (-> (render-board-html file page dest-frame)
                         (.then (fn [parts]
                                  (let [doc (pdoc/build-prototype-document parts page dest-frame {:transparent-bg? true})
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
                                        (nav-to-frame! page dest-id))))))
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
                       (let [dest-frame (or (proto/find-frame-by-id-str page dest-id)
                                            (get-in page [:objects (uuid/parse* dest-id)]))]
                         (when (and dest-frame base-frame source-shape)
                           (-> (render-board-html file page dest-frame)
                               (.then (fn [parts]
                                        (let [doc (pdoc/build-prototype-document parts page dest-frame {:transparent-bg? true})
                                              rect (proto/compute-overlay-rect page interaction
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
                     (when-let [prev-frame (proto/find-frame-by-id-str page prev)]
                       (-> (render-board-html file page prev-frame)
                           (.then (fn [parts]
                                    (let [doc (pdoc/build-prototype-document parts page prev-frame {:transparent-bg? true})]
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
                                      (nav-to-frame! page prev))))
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
    (mf/with-effect [file page mode current-frame-id workspace-bg]
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
          (let [fr    (or (proto/find-frame-by-id-str page current-frame-id) frame)
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
                                        :html       (pdoc/build-prototype-document parts page fr {:transparent-bg? true})
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
                           ;; Apply the Workspace background override (if
                           ;; any) at document-assembly time. The cached
                           ;; converter output is background-independent —
                           ;; `pdoc/build-document` reads the page's
                           ;; `[:options :background]` — so swapping colors
                           ;; never thrashes `render-page-html-cached`.
                           (let [doc-page (cond-> page
                                            (some? workspace-bg)
                                            (assoc-in [:options :background] workspace-bg))]
                             (reset! state*
                                     {:status     :ready
                                      :html       (pdoc/build-document parts doc-page)
                                      :error      nil
                                      :updated-at (js/Date.now)})))))
                (.catch on-error))))
        (fn [] (vreset! cancelled? true))))

    ;; Listen for shape-selection messages from the workspace-mode
    ;; iframe AND for `:prototype:trigger` messages from the prototype
    ;; iframe. A single window-level listener handles both so we don't
    ;; pay for two registrations.
    (mf/with-effect []
      (let [handler (fn [^js e]
                      (let [data (.-data e)
                            result (proto/read-selected data)]
                        (cond
                          (= result ::proto/deselect) (reset! selected* nil)
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
              easing  (proto/easing->css (:easing anim))
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
                  (nav-to-frame! page dest-id)))]
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
                                                             {:transform (proto/slide-axis-percent (:direction anim))}]))
                              :push     (let [[a b] (proto/push-from-keyframes (:direction anim))]
                                          (animate! from-el [{:transform a} {:transform b}]))
                              nil)
                            to-anim
                            (case kind
                              :dissolve (animate! to-el [{:opacity 0} {:opacity 1}])
                              :slide    (when (not= :out (:way anim))
                                          (let [[a b] (proto/slide-keyframes (:direction anim))]
                                            (animate! to-el [{:transform a} {:transform b}])))
                              :push     (let [[a b] (proto/slide-keyframes (:direction anim))]
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

    ;; Mirror the conversion/loading status into mode-local state so the
    ;; header's Refresh button — rendered next to the file breadcrumb,
    ;; outside this section — can show its in-flight spinner + disabled
    ;; state. Cleared on unmount so a stale spinner never carries over.
    (mf/with-effect [status]
      (st/emit! (dhtml/set-busy (= status :loading)))
      (fn [] (st/emit! (dhtml/set-busy false))))

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
                            (st/emit! (dhtml/refresh-bundle))))))]
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
        (st/emit! dhtml/reset-zoom)))

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
                                       ("+" "=") dhtml/increase-zoom
                                       ("-" "_") dhtml/decrease-zoom
                                       "0"       dhtml/reset-zoom
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
      ;; The section toolbar is gone: Refresh + section switcher + the
      ;; background picker + the Prototype device-view controls all live
      ;; in the page header now, so the preview pane goes straight to the
      ;; content.
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
              (let [proto-frame    (or (proto/find-frame-by-id-str page current-frame-id) frame)
                    from-frame     (when transition (proto/find-frame-by-id-str page (:from-id transition)))
                    to-frame       (when transition (proto/find-frame-by-id-str page (:to-id transition)))
                    ;; Device-view size override: when the user picks a preset
                    ;; or types a custom size, the board-stack uses those dims
                    ;; instead of the board's design size. The iframe content
                    ;; reflows because the board root is forced to fill the
                    ;; body (see `build-prototype-document`). During a navigate
                    ;; transition we keep the natural board dims so neither the
                    ;; from- nor the to-board gets clipped mid-slide; the stack
                    ;; snaps back to the override once the transition commits.
                    override       (:size-override device-view)
                    {pw :width ph :height} (or override (proto/board-dims proto-frame))
                    {fw :width fh :height} (proto/board-dims (or from-frame proto-frame))
                    {tw :width th :height} (proto/board-dims (or to-frame proto-frame))
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

;; ---------------------------------------------------------------------------
;; Standalone page
;;
;; `html-mode-page*` owns the state lifecycle (initialize/finalize on
;; mount/unmount), the permission gate, and the page chrome (header +
;; modal container). The section component above stays chrome-agnostic
;; so it keeps working should it ever be embedded elsewhere.

(mf/defc html-mode-view*
  {::mf/private true}
  [{:keys [data page-id frame-id index mode]}]
  (let [{:keys [file project pages permissions]} data
        page-id (or page-id (first (get-in file [:data :pages])))
        page    (get pages page-id)
        frames  (:frames page)
        ;; Board resolution: explicit `?frame-id=`, then the legacy
        ;; `?index=` (kept so pre-standalone URLs survive the redirect
        ;; shim), then the first board.
        frame   (or (when frame-id (d/seek #(= (:id %) frame-id) frames))
                    (when (and index (< -1 index (count frames))) (get frames index))
                    (first frames))
        ;; Mirror of the access predicate the embedded section used:
        ;; editors always pass; share-link visitors (logged or
        ;; anonymous) need `who-inspect = "all"` on their link.
        allowed (and (contains? cf/flags :html-mode)
                     (or (:can-edit permissions)
                         (= "all" (:who-inspect permissions))))]

    (mf/with-effect [allowed]
      (when-not allowed
        (st/emit! (rt/nav :auth-login))))

    (mf/with-effect [(:name file)]
      (when-let [name (:name file)]
        (dom/set-html-title (dm/str "</> " name))))

    (when allowed
      [:div {:class (stl/css :html-mode-page)}
       [:> header* {:project project
                    :file file
                    :page page
                    :frames frames
                    :frame frame
                    :mode mode
                    :permissions permissions}]
       [:> html-mode-section* {:page page
                               :file file
                               :frame frame
                               :html-mode mode}]])))

(mf/defc html-mode-page*
  {::mf/lazy-load true}
  [{:keys [file-id share-id] :as props}]
  (let [data (mf/deref hrefs/html-mode-data)]

    (mf/with-effect [file-id share-id]
      (st/emit! (dhtml/initialize {:file-id file-id :share-id share-id}))
      (fn []
        (st/emit! (dhtml/finalize))))

    (if (and (some? data)
             (= file-id (dm/get-in data [:file :id])))
      [:*
       [:> modal-container*]
       [:> html-mode-view* (mf/spread-props props {:data data})]]
      [:> loader* {:title (tr "labels.loading")
                   :overlay true}])))
