;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.html-mode.design-tokens
  "Design Tokens inventory for HTML Mode.

   Lists every design token applied on the page (via `appliedTokens` on
   any shape) grouped by category. Two sub-views:

   - **Preview** — cards grid with per-category visual previews, a
     buscador, the HEX·PX format picker, and a `X / Y tokens` counter.
   - **CSS**     — a `:root { … }` block with the same tokens, grouped
     and commented by category, plus a Copy all button.

   The token extraction is delegated to `@penpot/html-converter`'s
   `extractAllTokens` (vendored under `frontend/vendor/penpot-html-converter`).
   That function already emits one entry per `(attribute, token)` with
   the resolved CSS value and the usage count; we then aggregate by
   token NAME so a token applied as both `p2` and `p4` shows up as a
   single card with both attribute chips.

   Each card also exposes a kebab (three-dots) menu with three
   actions — copy the token name, copy the CSS custom-property
   declaration, and open a per-token *usage* sub-view that lists every
   shape applying the token next to a non-interactive HTML preview of
   the page with the selected shape outlined."
  (:require-macros [app.main.style :as stl])
  (:require
   ["@penpot/html-converter" :as cv]
   [app.common.types.token :as cto]
   [app.main.data.html-mode.adapter :as adapter]
   [app.main.data.html-mode.converter-ctx :as cctx]
   [app.main.data.html-mode.style-parse :as sp]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.store :as st]
   [app.main.ui.components.context-menu-a11y :refer [context-menu*]]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.components.search-bar :refer [search-bar*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.layout.tab-switcher :refer [tab-switcher*]]
   [app.main.ui.html-mode.preview-doc :as pdoc]
   [app.main.ui.html-mode.sidebar :refer [format-picker*]]
   ;; Side-effecting require: the `:tokens/export` modal registers
   ;; itself when this namespace is loaded. Pulled in here so the
   ;; viewer bundle picks it up — without this the `modal/show!`
   ;; below would fail with "no such modal" because the workspace
   ;; entry point that normally registers it isn't loaded in viewer.
   [app.main.ui.workspace.tokens.export]
   [app.util.clipboard :as clipboard]
   [app.util.code-beautify :as beautify]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.timers :as tm]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; ---------------------------------------------------------------------------
;; Categories

(def ^:private category-order
  [:color :typography :spacing :radius :dimension :stroke :rotation :opacity :shadow])

(def ^:private category-index
  (zipmap category-order (range)))

(def ^:private category->i18n
  {:color      "viewer.html-mode.design-tokens.category.color"
   :typography "viewer.html-mode.design-tokens.category.typography"
   :spacing    "viewer.html-mode.design-tokens.category.spacing"
   :radius     "viewer.html-mode.design-tokens.category.radius"
   :dimension  "viewer.html-mode.design-tokens.category.dimension"
   :stroke     "viewer.html-mode.design-tokens.category.stroke"
   :rotation   "viewer.html-mode.design-tokens.category.rotation"
   :opacity    "viewer.html-mode.design-tokens.category.opacity"
   :shadow     "viewer.html-mode.design-tokens.category.shadow"})

;; ---------------------------------------------------------------------------
;; Extraction & aggregation

(defn- aggregate
  "Collapse the flat `(attribute, token)` rows from `cv/extractAllTokens`
   into one entry per token NAME. Attributes are accumulated into a
   single vector and usage counts are summed."
  [items]
  (->> items
       (reduce
        (fn [acc {:keys [name category attribute value numericValue usageCount]}]
          (if (contains? acc name)
            (-> acc
                (update-in [name :attributes] conj attribute)
                (update-in [name :usage] + usageCount))
            (assoc acc name
                   {:name name
                    :category (keyword category)
                    :attributes [attribute]
                    :value value
                    :numeric-value numericValue
                    :usage usageCount})))
        {})
       vals
       (sort-by (juxt #(get category-index (:category %) 999)
                      #(or (:numeric-value %) 0)
                      :name))
       vec))

(defn extract-tokens
  "Pull every applied token off the page and aggregate by name. Returns
   `[]` when the page has none — callers use that as the empty-state
   trigger."
  [page]
  (if (nil? page)
    []
    (let [js-page (adapter/->js-page page)
          js-list (cv/extractAllTokens (.-objects js-page))]
      (aggregate (js->clj js-list :keywordize-keys true)))))

;; ---------------------------------------------------------------------------
;; Filtering & rendering helpers

(defn- matches?
  "Case-insensitive substring match against the token's name, value, or
   any of its attribute chips."
  [token query]
  (or (str/blank? query)
      (let [q (str/lower query)]
        (or (str/includes? (str/lower (str (:name token))) q)
            (str/includes? (str/lower (str (:value token))) q)
            (some #(str/includes? (str/lower (str %)) q)
                  (:attributes token))))))

(defn- display-value
  "Apply the active HEX·PX preferences to a token's stored value. Values
   that don't contain a hex literal or px length pass through unchanged."
  [token format]
  (sp/rewrite-value (:value token) format))

(defn- build-css-string
  "Render the visible tokens as a `:root { … }` block grouped per
   category, with a `/* Category */` comment heading each section. The
   active format is applied to every value via `sp/rewrite-value`."
  [tokens format]
  (let [grouped (group-by :category tokens)
        sections (for [cat category-order
                       :let [items (get grouped cat)]
                       :when (seq items)]
                   (str "  /* " (tr (category->i18n cat)) " */\n"
                        (->> items
                             (map (fn [{:keys [name] :as t}]
                                    (str "  --" (cv/tokenToCssVarName name)
                                         ": " (display-value t format) ";")))
                             (str/join "\n"))))]
    (if (seq sections)
      (str ":root {\n" (str/join "\n\n" sections) "\n}")
      ":root {}")))


;; ---------------------------------------------------------------------------
;; Token preview (left side of each card)

(defn- safe-numeric
  "Clamp a token's numeric value into `[lo hi]`. Returns `default` when
   the value is missing or non-numeric (which happens for typography
   sub-attributes like fontFamily/textCase)."
  [n lo hi default]
  (cond
    (nil? n)    default
    (number? n) (max lo (min hi n))
    :else       default))

(mf/defc token-preview*
  {::mf/private true}
  [{:keys [token]}]
  (let [{:keys [category value numeric-value attributes]} token]
    (case category
      :color
      [:div {:class (stl/css :token-preview :token-preview-color)
             :style {:background-color value}
             :aria-label (str "Color swatch " value)}]

      :radius
      (let [r (safe-numeric numeric-value 0 22 0)]
        [:div {:class (stl/css :token-preview)}
         [:div {:class (stl/css :token-preview-radius)
                :style {:border-radius (str r "px")}}]])

      (:spacing :dimension)
      (let [w (safe-numeric numeric-value 4 56 12)
            h (min 8 w)]
        [:div {:class (stl/css :token-preview)}
         [:div {:class (stl/css :token-preview-bar)
                :style {:width (str w "px")
                        :height (str (max 3 h) "px")}}]])

      :stroke
      (let [h (safe-numeric numeric-value 1 12 2)]
        [:div {:class (stl/css :token-preview)}
         [:div {:class (stl/css :token-preview-stroke)
                :style {:height (str h "px")}}]])

      :rotation
      (let [deg (safe-numeric numeric-value -360 360 0)]
        [:div {:class (stl/css :token-preview)}
         [:div {:class (stl/css :token-preview-rotation)
                :style {:transform (str "rotate(" deg "deg)")}}]])

      :opacity
      (let [o (safe-numeric numeric-value 0 1 1)]
        [:div {:class (stl/css :token-preview)}
         [:div {:class (stl/css :token-preview-opacity)
                :style {:opacity o}}]])

      :shadow
      [:div {:class (stl/css :token-preview)}
       [:div {:class (stl/css :token-preview-shadow)
              :style {:box-shadow value}}]]

      :typography
      (let [attr  (first attributes)
            style (case attr
                    "fontSize"       {:font-size value}
                    "fontFamily"     {:font-family value}
                    "fontWeight"     {:font-weight value}
                    "lineHeight"     {:line-height value}
                    "letterSpacing"  {:letter-spacing value}
                    "textCase"       {:text-transform value}
                    "textDecoration" {:text-decoration value}
                    {})]
        [:div {:class (stl/css :token-preview)}
         [:span {:class (stl/css :token-preview-typo)
                 :style style} "Aa"]])

      ;; Fallback for any unknown category.
      [:div {:class (stl/css :token-preview)}])))

;; ---------------------------------------------------------------------------
;; Token card

(mf/defc token-card*
  {::mf/private true}
  [{:keys [token format static? on-show-usage view]}]
  (let [{:keys [name attributes usage]} token
        ;; "rows" lays each token out as a full-width row (the default
        ;; Design Tokens view); "cards" keeps the original grid card.
        ;; The DOM is identical for both — only the class differs and
        ;; the layout is driven entirely from CSS (see `.token-card-row`).
        rows? (= view "rows")
        rendered (display-value token format)

        ;; Static cards are the read-only variant used in the "Used
        ;; by" sub-view — they have no kebab menu and no hover state.
        static? (boolean static?)

        ;; `menu-pos` doubles as the open flag: `nil` means closed,
        ;; a `{:x :y}` map means open and positioned at those viewport
        ;; coords. The kebab button writes its bounding rect's bottom-
        ;; right corner here so the menu lines up under the icon.
        menu-pos* (mf/use-state nil)
        menu-pos  (deref menu-pos*)
        menu-id   (mf/use-id)

        open-menu
        (mf/use-fn
         (fn [^js e]
           (dom/stop-propagation e)
           (let [^js target (dom/get-current-target e)
                 rect       (dom/get-bounding-rect target)]
             ;; Anchor under the kebab. `context-menu*`'s built-in
             ;; `check-menu-offscreen` flips the menu left automatically
             ;; when the right edge would clip the viewport — so for
             ;; cards near the right edge the menu still stays on-screen.
             (reset! menu-pos* {:x (:left rect)
                                :y (:bottom rect)}))))

        close-menu
        (mf/use-fn (fn [] (reset! menu-pos* nil)))

        on-copy-token
        (mf/use-fn
         (mf/deps name)
         (fn [_]
           (clipboard/to-clipboard name)
           (st/emit! (ntf/success
                      (tr "viewer.html-mode.design-tokens.copied")))))

        on-copy-css
        (mf/use-fn
         (mf/deps name rendered)
         (fn [_]
           (clipboard/to-clipboard
            (str "--" (cv/tokenToCssVarName name) ": " rendered ";"))
           (st/emit! (ntf/success
                      (tr "viewer.html-mode.design-tokens.copied-css")))))

        on-usage-click
        (mf/use-fn
         (mf/deps token on-show-usage)
         (fn [_]
           (when (fn? on-show-usage)
             (on-show-usage token))))

        options
        (mf/with-memo [name usage on-copy-token on-copy-css on-usage-click]
          [{:name    (tr "viewer.html-mode.design-tokens.card.copy-token")
            :id      "copy-token"
            :handler on-copy-token}
           {:name    (tr "viewer.html-mode.design-tokens.card.copy-css")
            :id      "copy-css"
            :handler on-copy-css}
           {:name    (tr "viewer.html-mode.design-tokens.card.used-times"
                         (str usage))
            :id      "used-times"
            :handler on-usage-click}])]
    [:div {:class (stl/css-case
                   :token-card true
                   :token-card-row rows?
                   :token-card-static static?
                   :token-card-menu-open (some? menu-pos))
           :data-category (clojure.core/name (:category token))
           :title name}
     [:> token-preview* {:token token}]
     [:div {:class (stl/css :token-body)}
      [:p {:class (stl/css :token-name)} name]
      [:p {:class (stl/css :token-value)} rendered]]
     [:div {:class (stl/css :token-meta)}
      (for [a attributes]
        [:span {:key a :class (stl/css :token-attr-tag)} a])
      [:span {:class (stl/css :token-usage)}
       (tr "viewer.html-mode.design-tokens.used-times" (str usage))]]
     (when-not static?
       [:div {:class (stl/css :token-card-actions)}
        [:> icon-button* {:variant "ghost"
                          :icon i/menu
                          :icon-size "s"
                          :class (stl/css :token-card-kebab)
                          :aria-label (tr "viewer.html-mode.design-tokens.card.more-options")
                          :on-click open-menu}]
        [:> context-menu* {:show (some? menu-pos)
                           :fixed true
                           :on-close close-menu
                           :min-width true
                           :top (or (:y menu-pos) 0)
                           :left (or (:x menu-pos) 0)
                           :options options
                           :origin menu-id}]])]))

;; ---------------------------------------------------------------------------
;; "Used by" sub-view — finds shapes applying a token + renders a
;; non-interactive HTML preview of the page with the selected shape
;; outlined.

(defn- shapes-using-token
  "Walk every object on the page and return entries for shapes whose
   `:applied-tokens` map references `token-name` in any attribute.
   The returned vector is stable (sorted by shape name) so the list
   doesn't reshuffle when the page re-renders. We probe the canonical
   `cto/all-keys` set from `app.common.types.token` so the lookup
   automatically picks up any new attribute the schema gains."
  [page token-name]
  (when (and page token-name)
    (->> (vals (:objects page))
         (keep (fn [shape]
                 (when-let [applied (:applied-tokens shape)]
                   (when-let [attr (some (fn [k]
                                           (when (= token-name (get applied k))
                                             k))
                                         cto/all-keys)]
                     {:id (str (:id shape))
                      :name (or (:name shape) "Untitled")
                      :type (some-> (:type shape) clojure.core/name)
                      :attribute (clojure.core/name attr)}))))
         (sort-by (juxt :name :id))
         vec)))

(defn- shape-type->icon
  "Map a Penpot shape type onto a DS icon-id for the elements list.
   Falls back to a generic `board` glyph for unknown types."
  [shape-type]
  (case shape-type
    "frame"   i/board
    "rect"    i/rectangle
    "circle"  i/ellipse
    "path"    i/path
    "text"    i/text
    "image"   i/img
    "group"   i/group
    "bool"    i/boolean-union
    "svg-raw" i/img
    i/board))

(defn- apply-highlight!
  "Mutate the iframe's loaded document to outline + centre the shape
   matching `shape-id`. Doing this from the parent avoids the srcDoc
   reload (and the flash + font re-fetch) we'd get from rebuilding
   the document string on every selection change."
  [^js iframe shape-id]
  (when-let [^js doc (some-> iframe .-contentDocument)]
    (let [^js style (.querySelector doc "#penpot-static-highlight")
          ^js body  (.-body doc)
          css       (when (and shape-id (not (str/blank? shape-id)))
                      (str "[data-id=\"" shape-id "\"]{"
                           "outline:2px solid var(--color-accent-tertiary,#8c33eb)!important;"
                           "outline-offset:-1px;"
                           ;; Subtle pulsing ring on top of the static
                           ;; outline. The keyframes are defined in
                           ;; `build-static-doc`'s base block so they
                           ;; stay available across selection changes.
                           "animation:penpot-token-pulse 1.4s ease-out infinite;}"))]
      (when style
        (set! (.-textContent style) (or css "")))
      (when body
        (if (and shape-id (not (str/blank? shape-id)))
          (.setAttribute body "data-highlight-id" shape-id)
          (.removeAttribute body "data-highlight-id")))
      (when (and shape-id (not (str/blank? shape-id)))
        (when-let [^js el (.querySelector doc (str "[data-id=\"" shape-id "\"]"))]
          (.scrollIntoView el #js {:block "center" :inline "center"}))))))

(mf/defc usage-canvas*
  "Non-interactive iframe that renders the page once (with the right
   fonts and design-token custom properties) and lets the parent
   highlight + centre on any shape via direct DOM mutation. The
   iframe never reloads on selection change — only when the page or
   file itself changes."
  {::mf/private true}
  [{:keys [page file selected-shape-id]}]
  (let [doc*       (mf/use-state nil)
        loaded?*   (mf/use-state false)
        iframe-ref (mf/use-ref nil)
        cancel*    (mf/use-ref false)
        on-load
        (mf/use-fn (fn [_] (reset! loaded?* true)))]

    ;; Build the static doc once per (page, file). Runs the converter
    ;; and the font CSS fetch in parallel — the doc isn't useful
    ;; without either, so blocking until both resolve avoids a flash
    ;; of un-styled / wrong-font content.
    (mf/with-effect [page file]
      (mf/set-ref-val! cancel* false)
      (reset! loaded?* false)
      (reset! doc* nil)
      (let [js-page    (adapter/->js-page page)
            ctx        (cctx/converter-context file js-page)
            tokens     (.-tokens ctx)
            tokens-css (when (and tokens (pos? (.-size ^js tokens)))
                         (cv/tokensToCss tokens))]
        (-> (js/Promise.all
             #js [(.then (cv/convertPage js-page ctx) (fn [^js r] (.-html r)))
                  (pdoc/render-fonts-css-async file page)])
            (.then (fn [^js parts]
                     (when-not (mf/ref-val cancel*)
                       (reset! doc* (pdoc/build-static-doc
                                     (aget parts 0)
                                     (aget parts 1)
                                     (or tokens-css "")
                                     page)))))))
      (fn [] (mf/set-ref-val! cancel* true)))

    ;; Apply selection highlight by mutating the loaded iframe's DOM.
    ;; Guarded on `loaded?*` so the mutation only runs once the
    ;; iframe has actually parsed our srcDoc — otherwise
    ;; `contentDocument` is the previous (or empty) document and the
    ;; querySelector finds nothing.
    ;;
    ;; We re-apply once after `document.fonts.ready` resolves because
    ;; text-shape bounding boxes only stabilise after web fonts have
    ;; finished loading; centring before that point puts the wrong
    ;; rectangle in view.
    (mf/with-effect [(deref loaded?*) selected-shape-id]
      (when (deref loaded?*)
        (when-let [iframe (mf/ref-val iframe-ref)]
          (apply-highlight! iframe selected-shape-id)
          (when-let [^js inner-doc (.-contentDocument iframe)]
            (when-let [^js inner-fonts (.-fonts inner-doc)]
              (.then (.-ready inner-fonts)
                     (fn [_] (apply-highlight! iframe selected-shape-id))))))))

    (let [doc (deref doc*)]
      (if (some? doc)
        [:iframe {:class (stl/css :usage-iframe)
                  :ref iframe-ref
                  :title (tr "viewer.html-mode.design-tokens.usage.preview-title")
                  :src-doc doc
                  :on-load on-load
                  ;; No scripts inside the iframe (the converter is
                  ;; style-only and we no longer inject a centering
                  ;; helper). `allow-same-origin` is the minimum we
                  ;; need so the parent can read `contentDocument`
                  ;; for DOM-mutation-based highlight updates and so
                  ;; the iframe can load fonts/images with the user's
                  ;; session cookies.
                  :sandbox "allow-same-origin"
                  :referrer-policy "no-referrer"}]
        [:div {:class (stl/css :usage-iframe-loading)}
         (tr "viewer.html-mode.design-tokens.usage.preview-loading")]))))

(mf/defc token-usage-view*
  "Three-column layout shown when the user picks 'Used X times' on a
   token card: the selected token + BACK on the left, the list of
   shapes applying the token in the middle, and a centred HTML
   preview on the right with the chosen shape outlined."
  {::mf/private true}
  [{:keys [page file token format on-back]}]
  (let [usages           (mf/with-memo [page token]
                           (shapes-using-token page (:name token)))
        ;; Default to the first usage so the preview never opens blank
        ;; — if the user switches token, the parent resets this to nil
        ;; and the effect below picks the new list's first item.
        selected-id*     (mf/use-state nil)
        selected-id      (deref selected-id*)
        first-usage-id   (some-> (first usages) :id)]

    (mf/with-effect [first-usage-id]
      (reset! selected-id* first-usage-id))

    [:div {:class (stl/css :usage-root)}
     ;; COLUMN 1 — back button + selected token card
     [:aside {:class (stl/css :usage-left)}
      [:button {:type "button"
                :class (stl/css :usage-back)
                :on-click on-back
                :aria-label (tr "viewer.html-mode.design-tokens.usage.back")}
       [:> icon* {:icon-id i/arrow :size "s"}]
       [:span (tr "viewer.html-mode.design-tokens.usage.back")]]
      [:> token-card* {:token token
                       :format format
                       :static? true}]]

     ;; COLUMN 2 — list of shapes referencing the token
     [:aside {:class (stl/css :usage-elements)}
      [:h4 {:class (stl/css :usage-elements-title)}
       (tr "viewer.html-mode.design-tokens.usage.elements-title"
           (str (count usages)))]
      (if (seq usages)
        [:ul {:class (stl/css :usage-elements-list)}
         (for [u usages
               :let [active? (= (:id u) selected-id)]]
           [:li {:key (:id u)}
            [:button {:type "button"
                      :class (stl/css-case
                              :usage-element true
                              :usage-element-active active?)
                      :on-click #(reset! selected-id* (:id u))}
             [:> icon* {:icon-id (shape-type->icon (:type u)) :size "s"}]
             [:span {:class (stl/css :usage-element-name)} (:name u)]
             [:span {:class (stl/css :usage-element-attr)} (:attribute u)]]])]
        [:p {:class (stl/css :usage-elements-empty)}
         (tr "viewer.html-mode.design-tokens.usage.no-elements")])]

     ;; COLUMN 3 — non-interactive HTML preview
     [:main {:class (stl/css :usage-canvas)}
      [:> usage-canvas* {:page page :file file
                         :selected-shape-id selected-id}]]]))

;; ---------------------------------------------------------------------------
;; Main component

(mf/defc design-tokens-view*
  [{:keys [page file]}]
  (let [tokens     (mf/with-memo [page] (extract-tokens page))
        ;; Pulled raw from the file so the "Export JSON" button can
        ;; decide whether to enable itself without pulling in the
        ;; whole DTCG serialiser at render time.
        tokens-lib (some-> file :data :tokens-lib)
        sub-tab*   (mf/use-state "preview")
        sub-tab    (deref sub-tab*)
        search*    (mf/use-state "")
        search     (deref search*)
        format*    (mf/use-state {:color :hex :unit :px})
        format     (deref format*)
        ;; Preview layout: "rows" (default) renders each token as a
        ;; full-width row; "cards" is the original grid of cards. Toggled
        ;; via the list/grid switch in the Preview header, reusing the
        ;; same radio-buttons control as the workspace assets panel.
        view*      (mf/use-state "rows")
        view       (deref view*)
        copied*    (mf/use-state false)
        copied     (deref copied*)
        ;; When non-nil, the whole view switches to the "Used by"
        ;; sub-layout (3 columns) showing the picked token alongside
        ;; the shapes that apply it. Going BACK simply resets this to
        ;; nil; the rest of the filter / sub-tab state survives so the
        ;; grid lands exactly where the user left it.
        selected-token* (mf/use-state nil)
        selected-token  (deref selected-token*)
        ;; Set of categories the user has toggled ON in the left
        ;; sidebar. Empty set means "show all" — clicking a category
        ;; row adds/removes it from this set; whichever categories
        ;; survive the active filter are the ones rendered in the
        ;; main pane AND emitted into the CSS block.
        active*    (mf/use-state #{})
        active     (deref active*)

        ;; Search filter is applied first so the sidebar counts can
        ;; reflect "how many tokens of each category match the
        ;; current search" — independent of which categories are
        ;; currently toggled on.
        searched   (mf/with-memo [tokens search]
                     (filterv #(matches? % search) tokens))
        ;; Category filter on top of the search filter. Empty set
        ;; disables the filter so every category is included.
        filtered   (mf/with-memo [searched active]
                     (if (empty? active)
                       searched
                       (filterv #(contains? active (:category %)) searched)))
        grouped    (mf/with-memo [filtered]
                     (group-by :category filtered))
        ;; Counts shown in the sidebar reflect the search-filtered
        ;; set (NOT the category filter), so toggling a category
        ;; off doesn't make its count vanish from the sidebar.
        sidebar-grouped (mf/with-memo [searched]
                          (group-by :category searched))
        css-text   (mf/with-memo [filtered format]
                     (build-css-string filtered format))
        ;; Run the same `highlight.js` pipeline used by the Export
        ;; modal so the Design Tokens Code view picks up the exact
        ;; same theme-aware palette (see `_syntax_highlighting` rules
        ;; in design_tokens.scss + theme variables in export_modal.scss).
        ;; Returns `nil` if hljs can't parse the snippet — we then
        ;; fall back to the plain string.
        css-html   (mf/with-memo [css-text]
                     (beautify/highlight css-text "css"))

        on-search-change
        (mf/use-fn (fn [v _] (reset! search* v)))

        on-search-clear
        (mf/use-fn (fn [_] (reset! search* "")))

        on-format-change
        (mf/use-fn (fn [next] (reset! format* next)))

        on-view-change
        (mf/use-fn (fn [v _] (reset! view* v)))

        on-sub-tab-change
        (mf/use-fn (fn [t] (reset! sub-tab* t)))

        toggle-category
        (mf/use-fn
         (fn [cat]
           (swap! active*
                  (fn [s] (if (contains? s cat) (disj s cat) (conj s cat))))))

        on-copy-all
        (mf/use-fn
         (mf/deps css-text)
         (fn [_]
           (clipboard/to-clipboard css-text)
           (reset! copied* true)
           (tm/schedule 1500 #(reset! copied* false))))

        ;; Reuse the workspace's `:tokens/export` modal so the viewer
        ;; user gets the exact same SINGLE FILE / MULTIPLE FILES
        ;; preview + download flow as the editor. The modal accepts
        ;; an optional `:tokens-lib` prop because the viewer state
        ;; doesn't expose `refs/tokens-lib`.
        on-export-json
        (mf/use-fn
         (mf/deps tokens-lib)
         (fn [_]
           (when tokens-lib
             (modal/show! :tokens/export {:tokens-lib tokens-lib}))))

        on-show-usage
        (mf/use-fn
         (fn [token]
           (reset! selected-token* token)))

        on-back-from-usage
        (mf/use-fn
         (fn [_]
           (reset! selected-token* nil)))]

    (cond
      (empty? tokens)
      [:div {:class (stl/css :empty)
             :data-testid "html-mode-design-tokens-empty"}
       [:> icon* {:icon-id i/search :size "s"}]
       [:p {:class (stl/css :empty-description)}
        (tr "viewer.html-mode.design-tokens.empty")]]

      ;; "Used by" sub-view takes over the whole pane when the user
      ;; picked a token. The HTML Mode toolbar (file tabs above us in
      ;; `html-mode.cljs`) stays intact; the BACK button below returns
      ;; to the grid with sub-tab / search / category state preserved.
      (some? selected-token)
      [:> token-usage-view* {:page page
                             :file file
                             :token selected-token
                             :format format
                             :on-back on-back-from-usage}]

      :else
      [:div {:class (stl/css :design-tokens-root)}
       ;; LEFT PANEL: sub-tabs + categories
       [:aside {:class (stl/css :left-panel)
                :aria-label (tr "viewer.html-mode.design-tokens.aria")}
        [:> tab-switcher* {:class (stl/css :left-panel-tabs)
                           :tabs [{:id "preview"
                                   :label (tr "viewer.html-mode.design-tokens.subtab.preview")}
                                  {:id "code"
                                   :label (tr "viewer.html-mode.design-tokens.subtab.code")}]
                           :selected sub-tab
                           :on-change on-sub-tab-change}]
        ;; Search lives in the sidebar so it stays visible across both
        ;; sub-tabs and acts as the single filter input for the whole
        ;; Design Tokens view. The category toggles below the search
        ;; refine that further (or independently if the search is empty).
        [:div {:class (stl/css :left-search)}
         [:> search-bar* {:value search
                          :placeholder (tr "viewer.html-mode.design-tokens.search-placeholder")
                          :icon-id i/search
                          :on-change on-search-change
                          :on-clear on-search-clear}]]
        [:section {:class (stl/css :left-categories)}
         [:h4 {:class (stl/css :left-categories-title)}
          (tr "viewer.html-mode.design-tokens.categories")]
         [:ul {:class (stl/css :left-categories-list)}
          (for [cat category-order
                :let [items (get sidebar-grouped cat)]
                :when (seq items)
                :let [active? (contains? active cat)]]
            [:li {:key (clojure.core/name cat)}
             [:button {:type "button"
                       :class (stl/css-case
                               :left-category-link true
                               :left-category-link-active active?)
                       :aria-pressed active?
                       :on-click #(toggle-category cat)}
              [:span (tr (category->i18n cat))]
              [:span {:class (stl/css :left-category-count)}
               (count items)]]])]]]

       ;; MAIN PANE
       [:main {:class (stl/css :main-pane)}
        (case sub-tab
          "preview"
          [:*
           [:header {:class (stl/css :main-header)}
            [:> format-picker* {:color (:color format)
                                :unit (:unit format)
                                :on-change on-format-change}]
            ;; List/grid view switch — same radio-buttons control and
            ;; icons used by the workspace assets panel.
            [:& radio-buttons {:selected view
                               :on-change on-view-change
                               :name "design-tokens-view"}
             [:& radio-button {:icon i/view-as-list
                               :value "rows"
                               :title (tr "viewer.html-mode.design-tokens.view.rows")
                               :id "design-tokens-view-rows"}]
             [:& radio-button {:icon i/flex-grid
                               :value "cards"
                               :title (tr "viewer.html-mode.design-tokens.view.cards")
                               :id "design-tokens-view-cards"}]]
            [:span {:class (stl/css :counter)}
             (str (count filtered) " / " (count tokens) " "
                  (tr "viewer.html-mode.design-tokens.tokens"))]]

           ;; `.grid-scroll` is the full-width scroll container so the
           ;; scrollbar sits flush against the viewport's right edge;
           ;; `.grid-inner` carries the centred 1000px max-width layout.
           [:div {:class (stl/css :grid-scroll)}
            [:div {:class (stl/css :grid-inner)}
             (if (zero? (count filtered))
               [:p {:class (stl/css :no-results)}
                (tr "viewer.html-mode.design-tokens.no-results")]
               (for [cat category-order
                     :let [items (get grouped cat)]
                     :when (seq items)]
                 [:section {:key (clojure.core/name cat)
                            :id (str "cat-" (clojure.core/name cat))
                            :class (stl/css :category-section)}
                  [:h3 {:class (stl/css :category-title)}
                   [:span (tr (category->i18n cat))]
                   [:span {:class (stl/css :category-count)}
                    (count items)]]
                  [:div {:class (stl/css-case
                                 :token-grid true
                                 :token-grid-colors (= cat :color)
                                 :token-grid-rows (= view "rows"))}
                   (for [t items]
                     [:> token-card* {:key (:name t)
                                      :token t
                                      :format format
                                      :view view
                                      :on-show-usage on-show-usage}])]]))]]]

          "code"
          [:*
           [:header {:class (stl/css :main-header)}
            [:h2 {:class (stl/css :css-title)}
             (tr "viewer.html-mode.design-tokens.css.title")]
            [:div {:class (stl/css :css-actions)}
             [:> format-picker* {:color (:color format)
                                 :unit (:unit format)
                                 :on-change on-format-change}]
             ;; Hands off to the workspace's `:tokens/export` modal —
             ;; same SINGLE FILE / MULTIPLE FILES preview + download
             ;; flow the editor exposes from its tokens sidebar.
             ;; Disabled when the file has no tokens library to export.
             [:button {:type "button"
                       :class (stl/css :export-json-btn)
                       :on-click on-export-json
                       :disabled (nil? tokens-lib)
                       :aria-label (tr "viewer.html-mode.design-tokens.export-json")}
              [:> icon* {:icon-id i/download :size "s"}]
              [:span (tr "viewer.html-mode.design-tokens.export-json")]]
             [:button {:type "button"
                       :class (stl/css-case
                               :copy-all-btn true
                               :copy-all-btn-active copied)
                       :on-click on-copy-all
                       :aria-label (tr "viewer.html-mode.design-tokens.copy-all")}
              [:> icon* {:icon-id (if copied i/tick i/clipboard)
                         :size "s"}]
              [:span (if copied
                       (tr "viewer.html-mode.sidebar.copied")
                       (tr "viewer.html-mode.design-tokens.copy-all"))]]]]
           [:pre {:class (stl/css-case :css-block true :hljs true)}
            (if (some? css-html)
              [:code {:dangerouslySetInnerHTML #js {:__html css-html}}]
              [:code css-text])]])]])))
