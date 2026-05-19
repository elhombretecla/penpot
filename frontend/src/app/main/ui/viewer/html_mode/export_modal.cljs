;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.html-mode.export-modal
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
   [app.main.data.html-mode.adapter :as adapter]
   [app.main.data.html-mode.converter-ctx :as cctx]
   [app.main.data.modal :as modal]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.checkbox :refer [checkbox*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.util.clipboard :as clipboard]
   [app.util.code-beautify :as beautify]
   [app.util.i18n :refer [tr]]
   [app.util.timers :as tm]
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
  [file page shape format styling include-data-attrs?]
  (let [js-page    (adapter/->js-page page)
        js-objects (.-objects js-page)
        js-shape   (unchecked-get js-objects (str (:id shape)))
        ctx        (cctx/converter-context file js-page)
        options    #js {:format            (name format)
                        :styling           (name styling)
                        :includeDataAttrs  include-data-attrs?}]
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
;; Modal

(mf/defc export-shape-dialog
  {::mf/register modal/components
   ::mf/register-as :html-mode-export-shape
   ::mf/wrap-props false}
  [{:keys [file page shape]}]
  (let [format*       (mf/use-state :html)
        format        (deref format*)
        styling*      (mf/use-state :css)
        styling       (deref styling*)
        include-data* (mf/use-state false)
        include-data? (deref include-data*)

        result*       (mf/use-state {:code "" :css "" :loading? true})
        result        (deref result*)

        on-close
        (mf/use-fn
         (fn [_] (st/emit! (modal/hide))))

        on-format-change   (mf/use-fn (fn [v] (reset! format* v)))
        on-styling-change  (mf/use-fn (fn [v] (reset! styling* v)))
        on-include-toggle  (mf/use-fn (fn [_]
                                        (swap! include-data* not)))]

    (mf/with-effect [file page shape format styling include-data?]
      (let [cancelled? (volatile! false)]
        (reset! result* {:code "" :css "" :loading? true})
        (-> (generate-code file page shape format styling include-data?)
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
                                        (tr "viewer.html-mode.export.no-styles"))}])]]]))
