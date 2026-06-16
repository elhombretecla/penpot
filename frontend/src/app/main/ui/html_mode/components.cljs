;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.html-mode.components
  "Storybook-like browser for the components used on the current page.

   Sidebar groups components by their assets path; the detail pane
   renders an HTML preview, the generated HTML / CSS, the design
   tokens referenced, and (for variants) the property pairs that
   describe the chosen variant. Variants of the same component are
   collapsed into one sidebar entry with a chip row in the detail."
  (:require-macros [app.main.style :as stl])
  (:require
   ["@penpot/html-converter" :as cv]
   ["@penpot/html-converter/shape-code" :as sc]
   [app.common.files.helpers :as cfh]
   [app.common.files.variant :as cfv]
   [app.common.path-names :as cpn]
   [app.common.types.components-list :as ctkl]
   [app.common.types.file :as ctf]
   [app.common.types.variant :as ctv]
   [app.main.data.html-mode.adapter :as adapter]
   [app.main.data.html-mode.converter-ctx :as cctx]
   [app.main.data.html-mode.style-parse :as sp]
   [app.main.ui.components.code-block :refer [code-block*]]
   [app.main.ui.components.copy-button :refer [copy-button*]]
   [app.main.ui.components.search-bar :refer [search-bar*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.html-mode.design-tokens :as dt]
   [app.main.ui.html-mode.preview-doc :as pdoc]
   [app.main.ui.html-mode.refs :as hrefs]
   [app.main.ui.html-mode.sidebar :refer [format-picker* section-disclosure*]]
   [app.util.clipboard :as clipboard]
   [app.util.code-beautify :as cb]
   [app.util.i18n :refer [tr]]
   [app.util.timers :as tm]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; ---------------------------------------------------------------------------
;; Discovery: components used on the current page

(defn- used-component-ids
  "Walk `(:objects page)` and return the set of component ids that are
   instantiated on this page AND defined in the current file (matched
   by `:component-file`). External-library components are out of scope
   in v1."
  [page file-id]
  (->> (vals (:objects page))
       (filter (fn [shape]
                 (and (:component-id shape)
                      (= file-id (:component-file shape)))))
       (map :component-id)
       (into #{})))

(defn- variant-set-id
  "Stable id for the sidebar entry: variants of the same set collapse
   onto a single key."
  [component]
  (or (:variant-id component) (:id component)))

(defn- siblings-for
  "Ordered vector of siblings for `component`'s variant set. For a
   non-variant component, returns `[component]`."
  [file-data component]
  (if-let [vid (:variant-id component)]
    (let [found   (cfv/find-variant-components file-data vid)
          ordered (when (seq found) (vec found))]
      (or ordered [component]))
    [component]))

(defn- build-sets
  "Returns a vector of variant-set descriptors used on the page:

   `{:set-id <uuid> :representative <component> :variants <[component …]>
     :path <string> :name <string>}`

   Sets are deduped by `:variant-id` (or `:id` for non-variant
   components). The representative is the first sibling — used for
   the sidebar label and the path grouping."
  [page file]
  (let [file-data (:data file)
        comp-ids  (used-component-ids page (:id file))
        comps     (->> comp-ids
                       (keep #(ctkl/get-component file-data %))
                       vec)]
    (->> comps
         (group-by variant-set-id)
         (map (fn [[set-id [first-comp]]]
                (let [variants    (siblings-for file-data first-comp)
                      representative (first variants)]
                  {:set-id set-id
                   :representative representative
                   :variants variants
                   :path (or (:path representative) "")
                   :name (:name representative)})))
         (sort-by (fn [s] (str (:path s) "/" (:name s))))
         vec)))

(defn- matches-search?
  [set query]
  (or (str/blank? query)
      (let [q (str/lower query)]
        (or (str/includes? (str/lower (str (:name set))) q)
            (str/includes? (str/lower (str (:path set))) q)))))

(defn- build-tree
  "Convert a flat list of variant-set descriptors into the nested
   `{segment {... \"\" [items]}}` shape that the recursive renderer
   consumes. Mirrors `assets/groups/group-assets` but inlined so we
   don't pull the workspace assets module into the viewer bundle."
  [sets]
  (reduce
   (fn [acc s]
     (let [segments (cpn/split-path (or (:path s) ""))
           keypath  (conj (mapv str segments) "")]
       (update-in acc keypath (fnil conj []) s)))
   {}
   sets))

;; ---------------------------------------------------------------------------
;; Synthetic page builder for the preview iframe / code generation
;;
;; We render and code-gen each component in isolation. The converter
;; consumes a `page` whose `:objects` map describes a single shape
;; tree with the component's root as the page root (`:parent-id` =
;; `:id`). All non-relevant siblings on the original main-instance
;; page are filtered out so the iframe shows just this component and
;; the CSS / HTML are scoped to it.

(defn- subtree-objects
  "Build the `:objects` map for the synthetic page: the original page
   root (with its `:shapes` narrowed to point at the component only),
   the component's root shape (re-parented to the page root), and
   every descendant in that subtree.

   Keeping the original page root matters because the html-converter's
   `renderPage` *skips* whatever shape it considers the page root —
   rendering only its children. If we rewired the component frame to
   be its own parent, the component itself would never render, only
   its children (losing the frame's background, layout, inherited
   typography…). With the real page root kept, the converter finds
   that root, then descends into the component frame and renders it
   normally.

   Reparenting the component to the page root also handles the variant
   case: a variant component lives inside the variant container frame
   (the shape whose id equals the component's `:variant-id`), so its
   original `:parent-id` would point at a shape that's *not* in our
   subset. Code-gen and layout calculations look the parent up by id
   to read its `:selrect`; if missing, they fail with
   `(rect? rect)` assertions. Pointing the component at the page root
   guarantees a valid parent regardless of original nesting depth."
  [objects root]
  (let [page-root (some (fn [s]
                          (when (and (some? s) (= (:id s) (:parent-id s)))
                            s))
                        (vals objects))
        child-ids (cfh/get-children-ids objects (:id root))
        ids       (cond-> #{(:id root)}
                    (some? page-root) (conj (:id page-root))
                    :always           (into child-ids))
        subset    (select-keys objects ids)]
    (if page-root
      (-> subset
          (assoc (:id page-root)
                 (assoc page-root :shapes [(:id root)]))
          (update (:id root)
                  (fn [s]
                    (assoc s
                           :parent-id (:id page-root)
                           :frame-id  (:id page-root)))))
      ;; Defensive fallback for malformed pages with no self-parented
      ;; root: rewire the component root to be a page root so the
      ;; converter can still find one.
      (assoc subset (:id root)
             (-> (get subset (:id root))
                 (assoc :parent-id (:id root)
                        :frame-id  (:id root)))))))

(defn- synthetic-page
  "Build a single-component `page` that the converter and code-gen can
   consume. Returns nil when the component's root or container page
   can't be resolved so the caller can short-circuit."
  [cpage root]
  (when (and cpage root)
    (assoc cpage :objects (subtree-objects (:objects cpage) root))))

;; ---------------------------------------------------------------------------
;; Code generation
;;
;; We hand off to `@penpot/html-converter/shape-code` — the same
;; pipeline the Export Shape modal in the workspace uses. It produces
;; clean, framework-ready output: deduped class names, sensible names
;; (`utilities-preferences-tab` instead of `shape-<uuid>`), separated
;; HTML / CSS, optional Tailwind v4 inline utilities, optional JSX
;; `className` swap. The HEX·PX format picker is applied on top of the
;; resulting CSS string via `sp/rewrite-value`.

(defn- generate-code-async
  "Returns a Promise of `#js {:code <html|jsx>, :css <css>}` for the
   given component root. Both are post-beautified through the shared
   `code-beautify` formatter so they line-break and indent like the
   Export modal output."
  [file page root code-format code-styling]
  (let [js-page    (adapter/->js-page page)
        js-objects (.-objects js-page)
        js-shape   (unchecked-get js-objects (str (:id root)))
        ctx        (cctx/converter-context file js-page)
        options    #js {:format           (name code-format)
                        :styling          (name code-styling)
                        :includeDataAttrs false
                        :rules            #js []}]
    (-> (sc/shapeToCode js-shape js-objects ctx options)
        (.then (fn [^js result]
                 (let [code (or (.-code result) "")
                       css  (or (.-css result) "")
                       lang (if (= code-format :jsx) "jsx" "html")]
                   #js {:code (if (seq code) (cb/format-code code lang) "")
                        :css  (if (seq css)  (cb/format-code css "css") "")}))))))

(defn- format-token-value
  [value format]
  (sp/rewrite-value (or value "") format))

;; ---------------------------------------------------------------------------
;; Preview iframe (one component at a time)

(mf/defc preview-frame*
  {::mf/private true}
  [{:keys [file page]}]
  (let [doc*    (mf/use-state nil)
        cancel* (mf/use-ref false)]

    (mf/with-effect [file page]
      (mf/set-ref-val! cancel* false)
      (reset! doc* nil)
      (when page
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
                                       page))))))))
      (fn [] (mf/set-ref-val! cancel* true)))

    (let [doc (deref doc*)]
      (if (some? doc)
        [:iframe {:class (stl/css :preview-iframe)
                  :title (tr "viewer.html-mode.components.preview-title")
                  :src-doc doc
                  :sandbox "allow-same-origin"
                  :referrer-policy "no-referrer"}]
        [:div {:class (stl/css :preview-loading)}
         (tr "viewer.html-mode.components.preview-loading")]))))

;; ---------------------------------------------------------------------------
;; Sidebar tree

(mf/defc tree-item*
  {::mf/private true}
  [{:keys [set selected? on-select]}]
  (let [{:keys [set-id name variants]} set
        variant-count (count variants)]
    [:button {:type "button"
              :class (stl/css-case
                      :tree-item true
                      :tree-item-active selected?)
              :on-click #(on-select set-id)
              :title name}
     [:> icon* {:icon-id i/component :size "s"
                :class (stl/css :tree-item-icon)}]
     [:span {:class (stl/css :tree-item-name)} name]
     (when (> variant-count 1)
       [:span {:class (stl/css :tree-item-badge)} variant-count])]))

(mf/defc tree-group*
  {::mf/private true}
  [{:keys [label children]}]
  (let [open* (mf/use-state true)
        open  (deref open*)]
    [:div {:class (stl/css :tree-group)}
     [:button {:type "button"
               :class (stl/css :tree-group-header)
               :aria-expanded open
               :on-click #(swap! open* not)}
      [:> icon* {:icon-id (if open i/arrow-down i/arrow) :size "s"
                 :class (stl/css :tree-group-icon)}]
      [:span {:class (stl/css :tree-group-label)} label]]
     (when open
       [:div {:class (stl/css :tree-group-body)} children])]))

(defn- render-tree-nodes
  "Returns compiled hiccup for one level of the tree. Recurses into
   sub-folders by direct function calls; the result is wrapped in
   `mf/html` so rumext compiles it eagerly (otherwise React would
   iterate the raw vector as children and throw)."
  [node selected-id on-select]
  (mf/html
   [:*
    (for [set (get node "")]
      [:> tree-item* {:key (str (:set-id set))
                      :set set
                      :selected? (= (:set-id set) selected-id)
                      :on-select on-select}])
    (for [[k child] (sort-by first (dissoc node ""))]
      ^{:key k}
      [:> tree-group* {:label k
                       :children (render-tree-nodes child selected-id on-select)}])]))

;; ---------------------------------------------------------------------------
;; Detail pane sub-components

(mf/defc variant-chip-row*
  {::mf/private true}
  [{:keys [variants selected-id on-select]}]
  [:div {:class (stl/css :variant-chip-row)
         :role "tablist"
         :aria-label (tr "viewer.html-mode.components.variants")}
   (for [v variants
         :let [label (or (some-> (:variant-properties v) ctv/properties-to-name not-empty)
                         (:name v))
               active? (= (:id v) selected-id)]]
     [:button {:key (str (:id v))
               :type "button"
               :role "tab"
               :aria-selected active?
               :class (stl/css-case
                       :variant-chip true
                       :variant-chip-active active?)
               :on-click #(on-select (:id v))}
      label])])

(mf/defc variant-properties-block*
  {::mf/private true}
  [{:keys [properties]}]
  [:dl {:class (stl/css :variant-props)}
   (for [{:keys [name value]} properties
         :when (not (str/blank? value))]
     ^{:key name}
     [:div {:class (stl/css :variant-props-row)}
      [:dt {:class (stl/css :variant-props-name)} name]
      [:dd {:class (stl/css :variant-props-value)} value]])])

(mf/defc token-row*
  {::mf/private true}
  [{:keys [token format]}]
  (let [name  (:name token)
        value (format-token-value (:value token) format)
        decl  (str "--" (cv/tokenToCssVarName name) ": " value ";")]
    [:li {:class (stl/css :token-row)}
     [:code {:class (stl/css :token-row-name)} (str "--" name)]
     [:code {:class (stl/css :token-row-value)} value]
     [:> copy-button* {:data decl
                       :class (stl/css :token-row-copy)
                       :aria-label (tr "viewer.html-mode.components.copy-token")}]]))

;; ---------------------------------------------------------------------------
;; Compact copy button for the section-disclosure `:action` slot. The
;; shared `copy-button*` from `app.main.ui.components.copy-button` sets
;; `width: 100%` which would stretch the button across its own row in
;; the section header — flipping copy state with a 32px box keeps the
;; header tidy and matches the size of the segmented + format-picker
;; siblings sitting next to it.

(mf/defc section-copy*
  {::mf/private true}
  [{:keys [data aria-label]}]
  (let [copied* (mf/use-state false)
        copied  (deref copied*)
        on-click
        (mf/use-fn
         (mf/deps data)
         (fn [_]
           (when (seq data)
             (clipboard/to-clipboard data)
             (reset! copied* true)
             (tm/schedule 1000 #(reset! copied* false)))))]
    [:button {:type "button"
              :class (stl/css :section-copy)
              :on-click on-click
              :disabled (str/blank? data)
              :aria-label aria-label}
     [:> icon* {:icon-id (if copied i/tick i/clipboard) :size "s"}]]))

;; ---------------------------------------------------------------------------
;; Segmented control (FORMAT html/jsx, STYLING css/tailwind). Same UX
;; as the Export Shape modal — kept private to this namespace because
;; the modal's copy lives in a separate require graph.

(mf/defc segmented*
  {::mf/private true}
  [{:keys [label value options on-change]}]
  [:div {:class (stl/css :segmented)}
   [:span {:class (stl/css :segmented-label)} label]
   [:div {:role "radiogroup"
          :class (stl/css :segmented-options)}
    (for [opt options]
      (let [opt-label (name opt)
            active?   (= opt value)]
        [:button {:key opt-label
                  :type "button"
                  :role "radio"
                  :aria-checked active?
                  :class (stl/css-case
                          :segmented-option true
                          :segmented-option-active active?)
                  :on-click #(on-change opt)}
         opt-label]))]])

;; ---------------------------------------------------------------------------
;; Preview background swatches
;;
;; Four predefined background colors for the preview iframe so users
;; can sanity-check the component against light and dark backdrops.
;; Order matches the visual order in the UI (dark → light).

;; Same preset colours used by the device-view background picker
;; (`html_mode/device_view.cljs:bg-presets`) so the two surfaces feel
;; like the same control.
(def ^:private bg-swatches
  ["#000000" "#454545" "#e8e9ea" "#ffffff"])

(def ^:private default-bg "#e8e9ea")

;; Public so HTML Mode's Workspace toolbar can reuse the exact same
;; control (see `app.main.ui.html-mode` → `:workspace` background picker).
(mf/defc bg-swatches*
  [{:keys [selected on-change]}]
  [:div {:class (stl/css :bg-color)
         :role "radiogroup"
         :aria-label (tr "viewer.html-mode.components.preview-bg")}
   (for [hex bg-swatches
         :let [active? (= hex selected)]]
     [:button {:key hex
               :type "button"
               :role "radio"
               :aria-checked active?
               :data-color hex
               :class (stl/css-case
                       :bg-swatch true
                       :selected active?)
               :style {:background-color hex}
               :aria-label hex
               :on-click #(on-change hex)}])])

;; ---------------------------------------------------------------------------
;; Main view

(mf/defc components-view*
  [{:keys [page file]}]
  (let [sets      (mf/with-memo [page file] (build-sets page file))
        search*   (mf/use-state "")
        search    (deref search*)
        format*   (mf/use-state {:color :hex :unit :px})
        format    (deref format*)
        ;; Preview backdrop color. The picker moved to the page header
        ;; (next to Share); the value is held in mode-local state. Fall
        ;; back to `default-bg` until the store value is initialized.
        bg-color  (or (mf/deref hrefs/components-bg) default-bg)
        layout-mode* (mf/use-state :rows)
        layout-mode  (deref layout-mode*)

        ;; Per-section code generation toggles — `code-format` swaps
        ;; HTML / JSX in the HTML section; `code-styling` swaps the
        ;; CSS / Tailwind output in the CSS section (and affects the
        ;; classes the HTML side emits).
        code-format*  (mf/use-state :html)
        code-format   (deref code-format*)
        code-styling* (mf/use-state :css)
        code-styling  (deref code-styling*)

        filtered  (mf/with-memo [sets search]
                    (filterv #(matches-search? % search) sets))

        tree      (mf/with-memo [filtered]
                    (when (seq filtered) (build-tree filtered)))

        selected-set-id*  (mf/use-state nil)
        selected-set-id   (deref selected-set-id*)

        ;; Default selection: first set in the filtered list. Resets
        ;; whenever the filter would hide the current selection.
        _ (mf/with-effect [filtered]
            (let [first-id (some-> (first filtered) :set-id)
                  current  (deref selected-set-id*)]
              (when (or (nil? current)
                        (not (some #(= (:set-id %) current) filtered)))
                (reset! selected-set-id* first-id))))

        selected-set (some #(when (= (:set-id %) selected-set-id) %) filtered)
        variants     (:variants selected-set)
        single-variant? (<= (count variants) 1)

        selected-variant-id*  (mf/use-state nil)
        selected-variant-id   (deref selected-variant-id*)

        _ (mf/with-effect [selected-set-id]
            (when selected-set
              (reset! selected-variant-id* (-> selected-set :variants first :id))))

        active-variant (or (some #(when (= (:id %) selected-variant-id) %) variants)
                           (first variants))

        cpage     (when active-variant (ctf/get-component-page (:data file) active-variant))
        root      (when active-variant (ctf/get-component-root (:data file) active-variant))
        syn-page  (mf/with-memo [cpage root]
                    (synthetic-page cpage root))

        ;; Separate page for the iframe preview only — overrides the
        ;; background so the user-picked swatch flows into the iframe's
        ;; `<body>` via `pdoc/page-background`. Code-gen and token
        ;; extraction keep using the unmodified `syn-page`.
        syn-page-preview (mf/with-memo [syn-page bg-color]
                           (when syn-page
                             (assoc-in syn-page [:options :background] bg-color)))

        ;; Async result of the shape-code pipeline. `:loading?` true on
        ;; the first run for each (file, page, root, code-format,
        ;; code-styling) tuple — the section bodies render an empty
        ;; placeholder until the promise resolves so we don't flash the
        ;; previous component's code while generating the new one.
        code-result* (mf/use-state {:code "" :css "" :loading? false})
        code-result  (deref code-result*)

        _ (mf/with-effect [file syn-page root code-format code-styling]
            (when (and syn-page root)
              (let [cancelled? (volatile! false)]
                (reset! code-result* {:code "" :css "" :loading? true})
                (-> (generate-code-async file syn-page root code-format code-styling)
                    (.then (fn [^js parts]
                             (when-not @cancelled?
                               (reset! code-result* {:code (.-code parts)
                                                     :css  (.-css parts)
                                                     :loading? false}))))
                    (.catch (fn [err]
                              (when-not @cancelled?
                                (js/console.error "Components code-gen failed:" err)
                                (reset! code-result* {:code "" :css ""
                                                      :loading? false})))))
                (fn [] (vreset! cancelled? true)))))

        ;; HEX·PX rewrite is only meaningful for CSS values; HTML/JSX
        ;; output is mostly class names + structure and applying the
        ;; pattern there could mangle Tailwind arbitrary values like
        ;; `bg-[#ff0000]` or `w-[16px]`.
        html-code (:code code-result)
        css-code  (mf/with-memo [code-result format]
                    (let [raw (:css code-result)]
                      (if (str/blank? raw) raw (sp/rewrite-value raw format))))

        tokens    (mf/with-memo [syn-page]
                    (when syn-page (dt/extract-tokens syn-page)))

        on-search-change    (mf/use-fn (fn [v _] (reset! search* v)))
        on-search-clear     (mf/use-fn (fn [_] (reset! search* "")))
        on-format-change    (mf/use-fn (fn [next] (reset! format* next)))
        on-code-format      (mf/use-fn (fn [v] (reset! code-format* v)))
        on-code-styling     (mf/use-fn (fn [v] (reset! code-styling* v)))
        on-toggle-layout    (mf/use-fn
                             (fn [_]
                               (swap! layout-mode* #(if (= % :rows) :columns :rows))))
        on-select-set       (mf/use-fn (fn [id] (reset! selected-set-id* id)))
        on-select-variant   (mf/use-fn (fn [id] (reset! selected-variant-id* id)))]

    (if (empty? sets)
      [:div {:class (stl/css :empty)
             :data-testid "html-mode-components-empty"}
       [:> icon* {:icon-id i/component :size "s"}]
       [:p {:class (stl/css :empty-description)}
        (tr "viewer.html-mode.components.empty")]]

      [:div {:class (stl/css :components-root)}
       ;; -------- LEFT SIDEBAR --------
       [:aside {:class (stl/css :sidebar)
                :aria-label (tr "viewer.html-mode.components.aria")}
        [:div {:class (stl/css :sidebar-search)}
         [:> search-bar* {:value search
                          :placeholder (tr "viewer.html-mode.components.search-placeholder")
                          :icon-id i/search
                          :on-change on-search-change
                          :on-clear on-search-clear}]]
        [:nav {:class (stl/css :sidebar-tree)}
         (if (or (nil? tree) (empty? tree))
           [:p {:class (stl/css :sidebar-no-results)}
            (tr "viewer.html-mode.components.no-results")]
           (render-tree-nodes tree selected-set-id on-select-set))]]

       ;; -------- DETAIL PANE --------
       [:main {:class (stl/css :detail)}
        (if-not active-variant
          [:div {:class (stl/css :detail-empty)}
           [:p (tr "viewer.html-mode.components.empty")]]
          (let [comp-name (:name selected-set)
                props     (:variant-properties active-variant)]
            (mf/html
             [:*
              [:header {:class (stl/css :detail-header)}
               [:div {:class (stl/css :detail-title-block)}
                [:h2 {:class (stl/css :detail-title)} comp-name]
                (when (not (str/blank? (:path selected-set)))
                  [:span {:class (stl/css :detail-path)} (:path selected-set)])]
               ;; The background picker moved to the page header (next to
               ;; Share); only the rows/columns layout toggle remains here.
               [:div {:class (stl/css :detail-actions)}
                [:> icon-button* {:variant "secondary"
                                  :icon i/layout-panel-top
                                  :icon-size "s"
                                  :class (stl/css-case
                                          :layout-toggle true
                                          :layout-toggle-active (= layout-mode :columns))
                                  :on-click on-toggle-layout
                                  :aria-label (tr "viewer.html-mode.components.toggle-layout")}]]]

              (when-not single-variant?
                [:> variant-chip-row* {:variants variants
                                       :selected-id (:id active-variant)
                                       :on-select on-select-variant}])

              [:div {:class (stl/css-case
                             :detail-body true
                             :detail-body-cols (= layout-mode :columns))}
               [:div {:class (stl/css :detail-preview)}
                [:div {:class (stl/css :preview-wrapper)}
                 [:> preview-frame* {:key (str (:id active-variant))
                                     :file file
                                     :page syn-page-preview}]]]

               [:div {:class (stl/css :detail-content)}
                (when (seq props)
                  [:> section-disclosure*
                   {:title (tr "viewer.html-mode.components.variant-properties")
                    :testid "html-mode-components-variant-props"}
                   [:> variant-properties-block* {:properties props}]])

                ;; HTML / CSS / Tokens sit in a responsive auto-fit
                ;; grid so the three code panels can be scanned at a
                ;; glance in row mode. The grid collapses to a single
                ;; column when (a) the user toggles columns mode (the
                ;; preview already takes half the detail pane) or (b)
                ;; the available width is too narrow for the minimum
                ;; per-column legibility — see `.sections-grid` in the
                ;; SCSS.
                [:div {:class (stl/css :sections-grid)}
                 [:> section-disclosure*
                  {:title (case code-format :jsx "JSX" "HTML")
                   :testid "html-mode-components-html"
                   :action (mf/html
                            [:div {:class (stl/css :section-action-group)}
                             [:> segmented* {:label (tr "viewer.html-mode.components.format")
                                             :value code-format
                                             :options [:html :jsx]
                                             :on-change on-code-format}]
                             [:> format-picker* {:color (:color format)
                                                 :unit (:unit format)
                                                 :on-change on-format-change}]
                             [:> section-copy* {:data (or html-code "")
                                                :aria-label (tr "viewer.html-mode.components.copy-html")}]])}
                  [:div {:class (stl/css :code-wrapper)}
                   [:> code-block* {:type (case code-format :jsx "jsx" "html")
                                    :code (or html-code "")}]]]

                 [:> section-disclosure*
                  {:title (tr "viewer.html-mode.components.section.css")
                   :testid "html-mode-components-css"
                   :action (mf/html
                            [:div {:class (stl/css :section-action-group)}
                             [:> segmented* {:label (tr "viewer.html-mode.components.styling")
                                             :value code-styling
                                             :options [:css :tailwind]
                                             :on-change on-code-styling}]
                             [:> format-picker* {:color (:color format)
                                                 :unit (:unit format)
                                                 :on-change on-format-change}]
                             [:> section-copy* {:data (or css-code "")
                                                :aria-label (tr "viewer.html-mode.components.copy-css")}]])}
                  (if (and (= code-styling :tailwind) (str/blank? css-code))
                    [:p {:class (stl/css :code-empty-hint)}
                     (tr "viewer.html-mode.components.css.tailwind-empty")]
                    [:div {:class (stl/css :code-wrapper)}
                     [:> code-block* {:type "css"
                                      :code (or css-code "")}]])]

                 [:> section-disclosure*
                  {:title (tr "viewer.html-mode.components.section.tokens")
                   :testid "html-mode-components-tokens"
                   :action (mf/html
                            [:div {:class (stl/css :section-action-group)}
                             [:> format-picker* {:color (:color format)
                                                 :unit (:unit format)
                                                 :on-change on-format-change}]])}
                  (if (seq tokens)
                    [:ul {:class (stl/css :tokens-list)}
                     (for [t tokens]
                       [:> token-row* {:key (:name t)
                                       :token t
                                       :format format}])]
                    [:p {:class (stl/css :tokens-empty)}
                     (tr "viewer.html-mode.components.tokens-empty")])]]]]])))]])))
