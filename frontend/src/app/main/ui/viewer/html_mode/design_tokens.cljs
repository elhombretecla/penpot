;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.html-mode.design-tokens
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
   single card with both attribute chips."
  (:require-macros [app.main.style :as stl])
  (:require
   ["@penpot/html-converter" :as cv]
   [app.main.data.html-mode.adapter :as adapter]
   [app.main.data.html-mode.style-parse :as sp]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.store :as st]
   [app.main.ui.components.search-bar :refer [search-bar*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.layout.tab-switcher :refer [tab-switcher*]]
   [app.main.ui.viewer.html-mode.sidebar :refer [format-picker*]]
   ;; Side-effecting require: the `:tokens/export` modal registers
   ;; itself when this namespace is loaded. Pulled in here so the
   ;; viewer bundle picks it up — without this the `modal/show!`
   ;; below would fail with "no such modal" because the workspace
   ;; entry point that normally registers it isn't loaded in viewer.
   [app.main.ui.workspace.tokens.export]
   [app.util.clipboard :as clipboard]
   [app.util.code-beautify :as beautify]
   [app.util.i18n :refer [tr]]
   [app.util.timers :as tm]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; ---------------------------------------------------------------------------
;; Categories

(def ^:private category-order
  [:color :typography :spacing :radius :dimension :stroke :rotation])

(def ^:private category-index
  (zipmap category-order (range)))

(def ^:private category->i18n
  {:color      "viewer.html-mode.design-tokens.category.color"
   :typography "viewer.html-mode.design-tokens.category.typography"
   :spacing    "viewer.html-mode.design-tokens.category.spacing"
   :radius     "viewer.html-mode.design-tokens.category.radius"
   :dimension  "viewer.html-mode.design-tokens.category.dimension"
   :stroke     "viewer.html-mode.design-tokens.category.stroke"
   :rotation   "viewer.html-mode.design-tokens.category.rotation"})

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

(defn- extract-tokens
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

      :typography
      (let [attr  (first attributes)
            style (case attr
                    "fontSize"       {:font-size value}
                    "fontFamily"     {:font-family value}
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
  [{:keys [token format]}]
  (let [{:keys [name attributes usage]} token
        rendered (display-value token format)

        on-click
        (mf/use-fn
         (mf/deps name)
         (fn [_]
           (clipboard/to-clipboard name)
           (st/emit! (ntf/success
                      (tr "viewer.html-mode.design-tokens.copied")))))

        ;; Keyboard parity with the click — Enter/Space activate the
        ;; copy too. Plays nice with screen readers because the card
        ;; is a `<button>`.
        on-key-down
        (mf/use-fn
         (mf/deps name)
         (fn [^js e]
           (let [key (.-key e)]
             (when (or (= key "Enter") (= key " "))
               (.preventDefault e)
               (clipboard/to-clipboard name)
               (st/emit! (ntf/success
                          (tr "viewer.html-mode.design-tokens.copied")))))))]
    [:button {:type "button"
              :class (stl/css :token-card)
              :data-category (clojure.core/name (:category token))
              :on-click on-click
              :on-key-down on-key-down
              :aria-label (tr "viewer.html-mode.design-tokens.copy-token" name)
              :title name}
     [:> token-preview* {:token token}]
     [:div {:class (stl/css :token-body)}
      [:p {:class (stl/css :token-name)} name]
      [:p {:class (stl/css :token-value)} rendered]
      [:div {:class (stl/css :token-meta)}
       (for [a attributes]
         [:span {:key a :class (stl/css :token-attr-tag)} a])
       [:span {:class (stl/css :token-usage)}
        (tr "viewer.html-mode.design-tokens.used-times" (str usage))]]]]))

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
        copied*    (mf/use-state false)
        copied     (deref copied*)
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
             (modal/show! :tokens/export {:tokens-lib tokens-lib}))))]

    (cond
      (empty? tokens)
      [:div {:class (stl/css :empty)
             :data-testid "html-mode-design-tokens-empty"}
       [:> icon* {:icon-id i/search :size "s"}]
       [:p {:class (stl/css :empty-description)}
        (tr "viewer.html-mode.design-tokens.empty")]]

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
            [:span {:class (stl/css :counter)}
             (str (count filtered) " / " (count tokens) " "
                  (tr "viewer.html-mode.design-tokens.tokens"))]]

           [:div {:class (stl/css :grid-scroll)}
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
                                :token-grid-colors (= cat :color))}
                  (for [t items]
                    [:> token-card* {:key (:name t)
                                     :token t
                                     :format format}])]]))]]

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
