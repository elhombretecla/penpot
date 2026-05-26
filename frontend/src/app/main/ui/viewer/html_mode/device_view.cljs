;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.html-mode.device-view
  "Popover that lets the user preview the prototype board at a different
   size (device presets / custom dimensions), simulate touch input, draw a
   decorative device frame and recolor the preview stage. All of this is
   pure visualization — it never touches the design, only the size of the
   `.board-stack` and a couple of presentation flags. The parent
   (`html-mode-section*`) owns the `device-view*` state; this component is
   controlled through `:settings` + `:on-change`.

   The size-preset dropdown + orientation toggle reuse the exact same
   pattern as the workspace frame options (`drawing/frame.cljs`)."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.math :as mth]
   [app.main.constants :refer [size-presets]]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.components.search-bar :refer [search-bar*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.numeric-input :refer [numeric-input*]]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.ds.controls.switch :refer [switch*]]
   [app.main.ui.ds.foundations.assets.icon :as i :refer [icon*]]
   [app.main.ui.workspace.sidebar.options.menus.measures :as measures]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def ^:private default-bg
  "Hex mirror of `--color-background-secondary` (the stage's default grey),
   used as the current value until the user picks an explicit background."
  "#e8e9ea")

(def ^:private bg-presets
  "Quick background swatches. Lowercase so they compare equal to the values
   returned by the native color input."
  ["#000000" "#454545" "#e8e9ea" "#ffffff"])

(defn- orient
  "Lay out `width`/`height` so the longest side follows `orientation`
   (`:horizontal` => landscape, anything else => portrait). Penpot presets
   are authored portrait, so this also rotates a preset when the user has
   landscape selected."
  [{:keys [width height]} orientation]
  (let [lo (min width height)
        hi (max width height)]
    (if (= orientation :horizontal)
      {:width hi :height lo}
      {:width lo :height hi})))

(mf/defc device-view-controls*
  [{:keys [settings default-dims on-change]}]
  (let [{:keys [size-override preset-name interaction mockup? bg-color]} settings

        ;; Effective dimensions feeding the H/W inputs and preset-match
        ;; highlight: the override when present, otherwise the board's
        ;; design size.
        eff           (or size-override default-dims)
        eff-w         (mth/round (:width eff 0))
        eff-h         (mth/round (:height eff 0))
        cur-orient    (if (> eff-w eff-h) :horizontal :vertical)

        label
        (cond
          (= preset-name :custom)  (tr "viewer.html-mode.device-view.custom-size")
          (string? preset-name)    preset-name
          (some? size-override)    (tr "viewer.html-mode.device-view.custom-size")
          :else                    (tr "viewer.html-mode.device-view.default-size"))

        ;; Outer popover (opened from the toolbar button).
        open*         (mf/use-state false)
        open?         (deref open*)
        root-ref      (mf/use-ref nil)

        ;; Inner preset dropdown (opened from the select inside the popover).
        preset-open*  (mf/use-state false)
        preset-open?  (deref preset-open*)
        preset-ref    (mf/use-ref nil)
        search-term*  (mf/use-state "")
        search-term   (deref search-term*)

        on-toggle
        (mf/use-fn (fn [] (swap! open* not)))

        on-close
        (mf/use-fn (fn [] (reset! open* false)))

        on-preset-toggle
        (mf/use-fn
         (fn []
           (swap! preset-open* not)
           (reset! search-term* "")))

        on-preset-close
        (mf/use-fn
         (fn []
           (reset! preset-open* false)
           (reset! search-term* "")))

        on-search-change
        (mf/use-fn
         (fn [value _event]
           (reset! search-term* value)))

        filtered-presets
        (mf/with-memo [search-term]
          (measures/filter-size-presets search-term size-presets))

        on-preset-selected
        (mf/use-fn
         (mf/deps cur-orient on-change)
         (fn [event]
           (let [target (dom/get-current-target event)
                 name   (dom/get-data target "name")
                 width  (d/read-string (dom/get-data target "width"))
                 height (d/read-string (dom/get-data target "height"))]
             (on-change {:preset-name name
                         :size-override (orient {:width width :height height} cur-orient)})
             (reset! preset-open* false)
             (reset! search-term* ""))))

        ;; "Default size" resets the override so the board falls back to its
        ;; design dimensions (see the effective-dims `(or override default)`).
        on-default-selected
        (mf/use-fn
         (mf/deps on-change)
         (fn [_event]
           (on-change {:preset-name nil :size-override nil})
           (reset! preset-open* false)
           (reset! search-term* "")))

        on-orientation-change
        (mf/use-fn
         (mf/deps eff on-change)
         (fn [value]
           (on-change {:size-override (orient eff (keyword value))})))

        on-width-change
        (mf/use-fn
         (mf/deps eff on-change)
         (fn [value]
           (on-change {:preset-name :custom
                       :size-override (assoc eff :width value)})))

        on-height-change
        (mf/use-fn
         (mf/deps eff on-change)
         (fn [value]
           (on-change {:preset-name :custom
                       :size-override (assoc eff :height value)})))

        on-interaction-change
        (mf/use-fn
         (mf/deps on-change)
         (fn [value]
           (on-change {:interaction (keyword value)})))

        on-mockup-change
        (mf/use-fn
         (mf/deps on-change)
         (fn [value]
           (on-change {:mockup? value})))

        ;; Background color: quick preset swatches + an eyedropper that opens
        ;; the native OS color picker for custom colors. The workspace
        ;; colorpicker modal can't be reused here — it positions itself
        ;; against the workspace viewport (`refs/workspace-local`), nil in the
        ;; viewer — so we keep it simple and robust.
        current-bg (.toLowerCase (or bg-color default-bg))

        on-bg-change
        (mf/use-fn
         (mf/deps on-change)
         (fn [event]
           (on-change {:bg-color (dom/get-target-val event)})))

        on-swatch-click
        (mf/use-fn
         (mf/deps on-change)
         (fn [event]
           (on-change {:bg-color (dom/get-data (dom/get-current-target event) "color")})))]

    [:div {:class (stl/css :device-view) :ref root-ref}
     [:> icon-button* {:variant "ghost"
                       :icon i/monitor-smartphone
                       :class (stl/css :device-view-btn)
                       :on-click on-toggle
                       :aria-label (tr "viewer.html-mode.device-view.toggle")}]

     [:& dropdown {:show open?
                   :on-close on-close
                   :container root-ref}
      ;; No `stop-propagation` here on purpose: letting clicks reach the
      ;; document lets the inner preset dropdown's outside-click detection
      ;; close it when the user clicks elsewhere in the popover. The outer
      ;; dropdown stays open because its `:container` (root-ref) wraps both
      ;; the button and this popover.
      [:div {:class (stl/css :device-view-popover)}

       ;; --- Row 1: preset dropdown + orientation toggle ----------------
       [:div {:class (stl/css :row :preset-row)}
        [:div {:class (stl/css-case :presets-wrapper true :opened preset-open?)
               :ref preset-ref
               :on-click on-preset-toggle}
         [:span {:class (stl/css :select-name)} label]
         [:span {:class (stl/css :collapsed-icon)}
          [:> icon* {:icon-id i/arrow-down :size "s"}]]
         [:& dropdown {:show preset-open?
                       :on-close on-preset-close
                       :container preset-ref}
          [:div {:class (stl/css :custom-select-dropdown)
                 :on-click dom/stop-propagation}
           [:div {:class (stl/css :preset-search)}
            [:> search-bar* {:on-change on-search-change
                             :value search-term
                             :auto-focus true
                             :placeholder (tr "workspace.options.search-size-preset")}]]
           [:ul {:class (stl/css :preset-list)}
            ;; First entry: reset to the board's design size. Only shown when
            ;; not actively searching (it isn't a searchable device preset).
            (when (empty? search-term)
              (let [default? (nil? size-override)]
                [:li {:key "default-size"
                      :class (stl/css-case :dropdown-element true :match default?)
                      :on-click on-default-selected}
                 [:div {:class (stl/css :name-wrapper)}
                  [:span {:class (stl/css :preset-name)}
                   (tr "viewer.html-mode.device-view.default-size")]
                  [:span {:class (stl/css :preset-size)}
                   (mth/round (:width default-dims 0)) " x " (mth/round (:height default-dims 0))]]
                 (when default?
                   [:span {:class (stl/css :check-icon)}
                    [:> icon* {:icon-id i/tick :size "s"}]])]))
            (if (empty? filtered-presets)
              [:li {:class (stl/css-case :dropdown-element true :disabled true)}
               [:span {:class (stl/css :preset-name)}
                (tr "workspace.options.no-size-preset-results")]]
              (for [preset filtered-presets]
                (if-not (:width preset)
                  [:li {:key (:name preset)
                        :class (stl/css-case :dropdown-element true :disabled true)}
                   [:span {:class (stl/css :preset-name)} (:name preset)]]
                  (let [match? (and (= (:width preset) eff-w)
                                    (= (:height preset) eff-h))]
                    [:li {:key (:name preset)
                          :class (stl/css-case :dropdown-element true :match match?)
                          :data-width (str (:width preset))
                          :data-height (str (:height preset))
                          :data-name (:name preset)
                          :on-click on-preset-selected}
                     [:div {:class (stl/css :name-wrapper)}
                      [:span {:class (stl/css :preset-name)} (:name preset)]
                      [:span {:class (stl/css :preset-size)}
                       (:width preset) " x " (:height preset)]]
                     (when match?
                       [:span {:class (stl/css :check-icon)}
                        [:> icon* {:icon-id i/tick :size "s"}]])]))))]]]]

        [:& radio-buttons {:selected (d/name cur-orient)
                           :on-change on-orientation-change
                           :name "device-view-orientation"
                           :class (stl/css :orientation)}
         [:& radio-button {:icon i/size-vertical
                           :value "vertical"
                           :id "device-size-vertical"}]
         [:& radio-button {:icon i/size-horizontal
                           :value "horizontal"
                           :id "device-size-horizontal"}]]]

       ;; --- Row 2: height / width inputs -------------------------------
       [:div {:class (stl/css :row :size-row)}
        [:div {:class (stl/css :size-field)}
         [:span {:class (stl/css :field-label)} "H"]
         [:> numeric-input* {:value eff-h
                             :min 1
                             :class (stl/css :size-input)
                             :aria-label (tr "viewer.html-mode.device-view.height")
                             :on-change on-height-change}]]
        [:div {:class (stl/css :size-field)}
         [:span {:class (stl/css :field-label)} "W"]
         [:> numeric-input* {:value eff-w
                             :min 1
                             :class (stl/css :size-input)
                             :aria-label (tr "viewer.html-mode.device-view.width")
                             :on-change on-width-change}]]]

       ;; --- Row 3: interaction type ------------------------------------
       [:div {:class (stl/css :row :labeled-row)}
        [:span {:class (stl/css :row-label)}
         (tr "viewer.html-mode.device-view.interaction")]
        [:> select* {:default-selected (d/name (or interaction :mouse))
                     :class (stl/css :interaction-select)
                     :options [{:id "mouse"
                                :label (tr "viewer.html-mode.device-view.interaction.mouse")}
                               {:id "touch"
                                :label (tr "viewer.html-mode.device-view.interaction.touch")}]
                     :on-change on-interaction-change}]]

       ;; --- Row 4: device mockup ---------------------------------------
       [:div {:class (stl/css :row :labeled-row)}
        [:span {:class (stl/css :row-label)}
         (tr "viewer.html-mode.device-view.mockup")]
        [:> switch* {:default-checked (boolean mockup?)
                     :aria-label (tr "viewer.html-mode.device-view.mockup")
                     :on-change on-mockup-change}]]

       ;; --- Row 5: background color ------------------------------------
       [:div {:class (stl/css :row :labeled-row)}
        [:span {:class (stl/css :row-label)}
         (tr "viewer.html-mode.device-view.background")]
        [:div {:class (stl/css :bg-color)}
         (for [hex bg-presets]
           [:button {:key hex
                     :type "button"
                     :class (stl/css-case :bg-swatch true
                                          :selected (= current-bg hex))
                     :data-color hex
                     :style {:background-color hex}
                     :aria-label hex
                     :on-click on-swatch-click}])
         ;; Eyedropper: a label wrapping a visually-hidden native color
         ;; input, so clicking it opens the OS picker for a custom colour.
         [:label {:class (stl/css :eyedropper)
                  :title (tr "viewer.html-mode.device-view.background")}
          [:> icon* {:icon-id i/picker :size "m"}]
          [:input {:type "color"
                   :class (stl/css :bg-native-input)
                   :value current-bg
                   :aria-label (tr "viewer.html-mode.device-view.background")
                   :on-change on-bg-change}]]]]]]]))
