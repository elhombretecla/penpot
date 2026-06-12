;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.html-mode.export-modal
  "Export Shape modal for HTML Mode.

   Reuses `@penpot/html-converter`'s `convertShape` to produce the raw
   HTML for the selected shape, then runs it through the vendored
   `shape-code` pipeline (port of penpot-tools') to produce
   framework-ready output:

     - FORMAT  → html | jsx       (swaps `class` ↔ `className`)
     - STYLING → css  | tailwind  (extracts named classes or emits
                                   Tailwind v4 utilities inline)
     - INCLUDE DATA-* ATTRS       (keeps the `data-id`/`data-type`/
                                   `data-name` attrs the converter
                                   emits to drive the inspector)

   The converter context is built from
   `app.main.data.html-mode.converter-ctx`, so resolved tokens,
   typographies and image URLs are picked up exactly like the live
   HTML Mode iframe."
  (:require-macros [app.main.style :as stl])
  (:require
   ["@penpot/html-converter/shape-code" :as sc]
   [app.common.data.macros :as dm]
   [app.main.data.html-mode.adapter :as adapter]
   [app.main.data.html-mode.converter-ctx :as cctx]
   [app.main.data.html-mode.semantics :as sem]
   [app.main.data.modal :as modal]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.checkbox :refer [checkbox*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.util.clipboard :as clipboard]
   [app.util.code-beautify :as beautify]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.timers :as tm]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; ---------------------------------------------------------------------------
;; Segmented toggle (HTML/JSX, CSS/Tailwind)

(mf/defc segmented*
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
;; Code block (header + copy button + scrollable <pre>)

(mf/defc code-block*
  [{:keys [title code lang empty-hint]}]
  (let [copied* (mf/use-state false)
        copied  (deref copied*)
        on-copy
        (mf/use-fn
         (mf/deps code)
         (fn [_]
           (when (seq code)
             (clipboard/to-clipboard code)
             (reset! copied* true)
             (tm/schedule 1500 #(reset! copied* false)))))
        empty?      (not (seq code))
        highlighted (when-not empty?
                      (beautify/highlight code lang))]
    [:div {:class (stl/css :code-block)}
     [:div {:class (stl/css :code-block-header)}
      [:span {:class (stl/css :code-block-title)} title]
      [:button {:type "button"
                :class (stl/css :copy-button)
                :on-click on-copy
                :disabled empty?
                :aria-label (tr "viewer.html-mode.export.copy")}
       [:> icon* {:icon-id (if copied i/tick i/clipboard) :size "s"}]
       [:span (tr (if copied
                    "viewer.html-mode.export.copied"
                    "viewer.html-mode.export.copy"))]]]
     [:pre {:class (stl/css-case
                    :code-block-content true
                    :hljs true
                    :code-block-empty empty?)
            :data-lang lang}
      (cond
        empty?
        [:span {:class (stl/css :code-block-empty-hint)}
         (or empty-hint (tr "viewer.html-mode.export.empty"))]

        (some? highlighted)
        [:code {:dangerouslySetInnerHTML #js {:__html highlighted}}]

        :else
        [:code code])]]))

;; ---------------------------------------------------------------------------
;; Code generation
;;
;; Build a {:code :css} pair from the selected shape using shape-code.
;; The format/styling/includeDataAttrs trio comes from the dialog's
;; local state; everything else (typographies, tokens, image url
;; resolution) is reused from the shared `converter-ctx` helper so the
;; exported snippet renders identically to the live preview.

(defn- generate-code
  [file page shape format styling include-data-attrs? rules]
  (let [js-page    (adapter/->js-page page)
        js-objects (.-objects js-page)
        js-shape   (unchecked-get js-objects (str (:id shape)))
        ctx        (cctx/converter-context file js-page)
        options    #js {:format            (name format)
                        :styling           (name styling)
                        :includeDataAttrs  include-data-attrs?
                        :rules             (sem/->js rules)}]
    (-> (sc/shapeToCode js-shape js-objects ctx options)
        (.then (fn [^js result]
                 (let [code (or (.-code result) "")
                       css  (or (.-css result) "")
                       code-lang (if (= format :jsx) "jsx" "html")]
                   {:code (if (seq code)
                            (beautify/format-code code code-lang)
                            "")
                    :css  (if (seq css)
                            (beautify/format-code css "css")
                            "")}))))))

;; ---------------------------------------------------------------------------
;; Semantic rules
;;
;; Port of penpot-tools' SemanticRules panel. Mirrors the upstream UX:
;;
;;   - "Tag <selected-layer> as <select>" + Apply → creates / replaces a
;;     `:shape-id` rule targeting the current selection.
;;   - List of existing rules, each row with: enable/disable checkbox,
;;     matcher kind, value, → <tag>, delete button.
;;   - Add-rule form: matcher select (name-contains / name-equals), value
;;     input, tag select, plus icon-button to commit.
;;
;; Rules live in the per-file localStorage map managed by
;; `app.main.data.html-mode.semantics`. Changes write through immediately
;; and bubble back up via the parent `mf/with-effect`, which retriggers
;; code generation so the preview stays in sync.

(def ^:private tag-options
  "Pre-baked option vector for the DS `select*` (`{:id :label}` shape).
   Built once at namespace load."
  (mapv (fn [t] {:id t :label (str "<" t ">")}) sem/semantic-tags))

(def ^:private form-rule-type-options
  "Matcher kinds shown in the add-rule form. `shape-id` is excluded
   because the only sensible way to create one is the quick-tag action
   on the selected shape — the form has no UUID input."
  (->> sem/rule-types
       (remove #(= :shape-id (:value %)))
       (mapv (fn [{:keys [value label]}]
               {:id (name value) :label label}))))

(defn- rule-type-label
  [type]
  (->> sem/rule-types
       (some (fn [{:keys [value label]}]
               (when (= type value) label)))))

(defn- rule-display-value
  "Render-friendly representation of a rule's `:value`. Shape-id rules
   surface the current selection as `(this shape)` and otherwise show a
   short UUID prefix; name rules are wrapped in quotes for readability."
  [{:keys [type value]} selected-shape-id]
  (case type
    :shape-id (if (= value (str selected-shape-id))
                "(this shape)"
                (str (subs value 0 (min (count value) 8)) "…"))
    (str "\"" value "\"")))

(mf/defc semantic-rules-quick-tag*
  [{:keys [selected-name on-apply disabled]}]
  (let [tag*       (mf/use-state "button")
        tag        (deref tag*)
        on-change  (mf/use-fn (fn [v] (reset! tag* v)))
        on-click   (mf/use-fn
                    (mf/deps tag on-apply)
                    (fn [_] (when (fn? on-apply) (on-apply tag))))]
    [:div {:class (stl/css :sem-quick-tag)}
     [:span {:class (stl/css :sem-quick-tag-prompt)}
      (tr "viewer.html-mode.export.semantics.tag-prefix") " "
      [:span {:class (stl/css :sem-quick-tag-name)}
       (or selected-name (tr "viewer.html-mode.export.semantics.this-shape"))]
      " " (tr "viewer.html-mode.export.semantics.tag-suffix")]
     [:div {:class (stl/css :sem-select)}
      [:> select* {:options tag-options
                   :default-selected tag
                   :on-change on-change}]]
     [:button {:type "button"
               :class (stl/css :sem-apply-button)
               :disabled disabled
               :on-click on-click}
      (tr "viewer.html-mode.export.semantics.apply")]]))

(mf/defc semantic-rule-row*
  [{:keys [rule selected-shape-id on-toggle on-delete]}]
  (let [{:keys [id type value tag enabled]} rule
        on-checkbox (mf/use-fn
                     (mf/deps id on-toggle)
                     (fn [event]
                       (let [checked (-> event dom/get-target dom/checked?)]
                         (when (fn? on-toggle) (on-toggle id checked)))))
        on-trash    (mf/use-fn
                     (mf/deps id on-delete)
                     (fn [_] (when (fn? on-delete) (on-delete id))))]
    [:li {:class (stl/css-case
                  :sem-rule-row true
                  :sem-rule-row-disabled (not enabled))}
     [:> checkbox* {:id (str "sem-rule-" id)
                    :checked (boolean enabled)
                    :on-change on-checkbox}]
     [:span {:class (stl/css :sem-rule-type)} (rule-type-label type)]
     [:span {:class (stl/css :sem-rule-value)
             :title value}
      (rule-display-value rule selected-shape-id)]
     [:span {:class (stl/css :sem-rule-arrow)} "→"]
     [:span {:class (stl/css :sem-rule-tag)}
      (str "<" tag ">")]
     [:> icon-button* {:class (stl/css :sem-rule-delete)
                       :variant "ghost"
                       :icon i/delete
                       :on-click on-trash
                       :aria-label (tr "viewer.html-mode.export.semantics.delete")}]]))

(mf/defc semantic-rules-add-form*
  [{:keys [on-add disabled]}]
  (let [type*  (mf/use-state :name-contains)
        type   (deref type*)
        value* (mf/use-state "")
        value  (deref value*)
        tag*   (mf/use-state "button")
        tag    (deref tag*)

        trimmed (str/trim value)
        can-add (and (seq trimmed) (not disabled))

        on-type-change (mf/use-fn (fn [v] (reset! type* (keyword v))))
        on-tag-change  (mf/use-fn (fn [v] (reset! tag* v)))
        on-value-input (mf/use-fn
                        (fn [event]
                          (reset! value* (dom/get-target-val event))))
        on-add-click
        (mf/use-fn
         (mf/deps type trimmed tag can-add on-add)
         (fn [_]
           (when can-add
             (when (fn? on-add)
               (on-add {:type type :value trimmed :tag tag}))
             (reset! value* ""))))

        on-key-down
        (mf/use-fn
         (mf/deps can-add on-add-click)
         (fn [event]
           (when (= "Enter" (.-key event))
             (.preventDefault event)
             (on-add-click event))))]

    [:div {:class (stl/css :sem-add-form)}
     [:div {:class (stl/css :sem-select :sem-select-type)}
      [:> select* {:options form-rule-type-options
                   :default-selected (name type)
                   :on-change on-type-change}]]
     [:div {:class (stl/css :sem-add-value)}
      [:> input* {:type "text"
                  :variant "dense"
                  :placeholder (tr "viewer.html-mode.export.semantics.value-placeholder")
                  :value value
                  :on-change on-value-input
                  :on-key-down on-key-down}]]
     [:span {:class (stl/css :sem-rule-arrow)} "→"]
     [:div {:class (stl/css :sem-select)}
      [:> select* {:options tag-options
                   :default-selected tag
                   :on-change on-tag-change}]]
     [:> icon-button* {:class (stl/css :sem-add-button)
                       :variant "ghost"
                       :icon i/add
                       :on-click on-add-click
                       :disabled (not can-add)
                       :aria-label (tr "viewer.html-mode.export.semantics.add")}]]))

(mf/defc semantic-rules-section*
  [{:keys [file-id shape rules on-change]}]
  (let [open*      (mf/use-state false)
        open?      (deref open*)
        toggle     (mf/use-fn (fn [_] (swap! open* not)))

        rule-count (count rules)
        shape-id   (some-> shape :id str)
        shape-name (or (:name shape)
                       (tr "viewer.html-mode.export.semantics.this-shape"))

        persist
        (mf/use-fn
         (mf/deps file-id on-change)
         (fn [next-rules]
           (let [saved (sem/write-rules! file-id next-rules)]
             (when (fn? on-change) (on-change saved)))))

        on-quick-tag
        (mf/use-fn
         (mf/deps rules shape-id persist)
         (fn [tag]
           (when (and shape-id tag)
             (persist (sem/upsert-shape-rule rules shape-id tag)))))

        on-toggle
        (mf/use-fn
         (mf/deps rules persist)
         (fn [rule-id enabled?]
           (persist (sem/toggle-rule rules rule-id enabled?))))

        on-delete
        (mf/use-fn
         (mf/deps rules persist)
         (fn [rule-id]
           (persist (sem/delete-rule rules rule-id))))

        on-add
        (mf/use-fn
         (mf/deps rules persist)
         (fn [partial]
           (persist (sem/add-rule rules partial))))]

    [:section {:class (stl/css :sem-section)}
     [:button {:type "button"
               :class (stl/css :sem-toggle)
               :on-click toggle
               :aria-expanded open?}
      [:span {:class (stl/css-case
                      :sem-toggle-chevron true
                      :sem-toggle-chevron-open open?)}
       [:> icon* {:icon-id i/arrow :size "s"}]]
      [:span {:class (stl/css :sem-toggle-label)}
       (tr "viewer.html-mode.export.semantics.title")]
      [:span {:class (stl/css :sem-toggle-count)}
       (dm/str "(" rule-count ")")]]

     (when open?
       [:div {:class (stl/css :sem-panel)}
        [:> semantic-rules-quick-tag*
         {:selected-name shape-name
          :on-apply on-quick-tag
          :disabled (nil? shape-id)}]

        (when (seq rules)
          [:ul {:class (stl/css :sem-rule-list)}
           (for [rule rules]
             [:> semantic-rule-row*
              {:key (:id rule)
               :rule rule
               :selected-shape-id shape-id
               :on-toggle on-toggle
               :on-delete on-delete}])])

        [:> semantic-rules-add-form*
         {:on-add on-add
          :disabled false}]])]))

;; ---------------------------------------------------------------------------
;; Modal

(mf/defc export-shape-dialog
  {::mf/register modal/components
   ::mf/register-as :html-mode-export-shape
   ::mf/wrap-props false}
  [{:keys [file page shape]}]
  (let [file-id       (:id file)
        format*       (mf/use-state :html)
        format        (deref format*)
        styling*      (mf/use-state :css)
        styling       (deref styling*)
        include-data* (mf/use-state false)
        include-data? (deref include-data*)

        rules*        (mf/use-state #(sem/read-rules file-id))
        rules         (deref rules*)

        result*       (mf/use-state {:code "" :css "" :loading? true})
        result        (deref result*)

        on-close
        (mf/use-fn
         (fn [_] (st/emit! (modal/hide))))

        on-format-change   (mf/use-fn (fn [v] (reset! format* v)))
        on-styling-change  (mf/use-fn (fn [v] (reset! styling* v)))
        on-include-toggle  (mf/use-fn (fn [_]
                                        (swap! include-data* not)))
        on-rules-change    (mf/use-fn (fn [next] (reset! rules* next)))]

    (mf/with-effect [file page shape format styling include-data? rules]
      (let [cancelled? (volatile! false)]
        (reset! result* {:code "" :css "" :loading? true})
        (-> (generate-code file page shape format styling include-data? rules)
            (.then (fn [parts]
                     (when-not @cancelled?
                       (reset! result* (assoc parts :loading? false)))))
            (.catch (fn [err]
                      (when-not @cancelled?
                        (js/console.error "Export shape failed:" err)
                        (reset! result* {:code "" :css ""
                                         :loading? false
                                         :error true})))))
        (fn [] (vreset! cancelled? true))))

    [:div {:class (stl/css :modal-overlay)
           :on-click (fn [event]
                       (when (= (.-target event) (.-currentTarget event))
                         (st/emit! (modal/hide))))
           :data-testid "html-mode-export-modal"}
     [:div {:class (stl/css :modal-dialog)
            :role "dialog"
            :aria-modal "true"
            :aria-labelledby "html-mode-export-title"}

      [:> icon-button* {:class (stl/css :close-btn)
                        :on-click on-close
                        :aria-label (tr "labels.close")
                        :variant "ghost"
                        :icon i/close}]

      [:header {:class (stl/css :modal-header)}
       [:h2 {:class (stl/css :modal-title)
             :id "html-mode-export-title"}
        (tr "viewer.html-mode.export.title")]
       [:p {:class (stl/css :modal-subtitle)}
        (tr "viewer.html-mode.export.subtitle")]]

      [:div {:class (stl/css :modal-controls)}
       [:> segmented* {:label (tr "viewer.html-mode.export.format")
                       :value format
                       :options [:html :jsx]
                       :on-change on-format-change}]
       [:> segmented* {:label (tr "viewer.html-mode.export.styling")
                       :value styling
                       :options [:css :tailwind]
                       :on-change on-styling-change}]
       [:> checkbox* {:id "html-mode-export-include-data-attrs"
                      :class (stl/css :data-attrs-toggle)
                      :label (tr "viewer.html-mode.export.include-data-attrs")
                      :checked include-data?
                      :on-change on-include-toggle}]]

      [:div {:class (stl/css-case
                     :modal-body true
                     :modal-body-two-columns (= styling :css))}
       [:> code-block* {:title (case format
                                 :jsx "JSX"
                                 "HTML")
                        :code (if (:loading? result) "" (:code result))
                        :lang (name format)
                        :empty-hint (when (:loading? result)
                                      (tr "viewer.html-mode.export.generating"))}]
       (when (= styling :css)
         [:> code-block* {:title "CSS"
                          :code (if (:loading? result) "" (:css result))
                          :lang "css"
                          :empty-hint (cond
                                        (:loading? result)
                                        (tr "viewer.html-mode.export.generating")
                                        :else
                                        (tr "viewer.html-mode.export.no-styles"))}])]

      [:> semantic-rules-section*
       {:file-id file-id
        :shape shape
        :rules rules
        :on-change on-rules-change}]]]))
