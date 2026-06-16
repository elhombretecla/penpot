;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.html-mode.header
  "Top bar of the standalone HTML Mode page, mirroring the viewer
   header's left / center / right structure:

   - LEFT: Penpot logo (→ dashboard), `file / page` breadcrumb with a
     page dropdown, and — in the Prototype section — a board picker
     (replaces the viewer's thumbnails panel + `?index=` pagination).
   - CENTER: the section mode-zone — Prototype / Workspace / Design
     Tokens / Components as icon buttons (the same pattern as the
     viewer's interactions/comments/inspect buttons). These used to be
     a tab-switcher inside the section toolbar.
   - RIGHT: zoom widget (Workspace + Prototype sections only — the
     other sections don't render through the CSS `zoom` container),
     Share (share-links dialog) and Open-in-workspace."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.common :as dcm]
   [app.main.data.html-mode :as dhtml]
   [app.main.data.html-mode.prototype :as proto]
   [app.main.data.modal :as modal]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.html-mode.components :refer [bg-swatches*]]
   [app.main.ui.html-mode.device-view :refer [device-view-controls*]]
   [app.main.ui.html-mode.refs :as hrefs]
   [app.main.ui.icons :as deprecated-icon]
   [app.main.ui.share-link]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def ^:private penpot-logo-icon
  (deprecated-icon/icon-xref :penpot-logo-icon (stl/css :logo-icon)))

;; ---------------------------------------------------------------------------
;; Left zone: breadcrumb + page dropdown + board picker

(mf/defc page-dropdown*
  {::mf/private true}
  [{:keys [file page]}]
  (let [show*    (mf/use-state false)
        show?    (deref show*)
        open!    (mf/use-fn #(reset! show* true))
        close!   (mf/use-fn #(reset! show* false))
        page-id  (:id page)
        ;; Only the pages present in the (possibly share-link-filtered)
        ;; bundle are listed.
        page-ids (get-in file [:data :pages])
        on-select
        (mf/use-fn
         (fn [event]
           (let [id (-> (dom/get-current-target event)
                        (dom/get-data "id")
                        (uuid))]
             (st/emit! (dhtml/go-to-page id)))))]
    [:div {:class (stl/css :breadcrumb)
           :on-click open!}
     [:span {:class (stl/css :breadcrumb-text)}
      (dm/str (:name file) " / " (:name page))]
     [:span {:class (stl/css :icon)} deprecated-icon/arrow]
     [:& dropdown {:show show? :on-close close!}
      [:ul {:class (stl/css :dropdown)}
       (for [id page-ids]
         [:li {:key (dm/str id)
               :class (stl/css-case :dropdown-item true
                                    :selected (= id page-id))
               :data-id (dm/str id)
               :on-click on-select}
          [:span {:class (stl/css :dropdown-item-name)}
           (get-in file [:data :pages-index id :name])]
          (when (= id page-id)
            [:span {:class (stl/css :icon-check)} deprecated-icon/tick])])]]]))

(mf/defc board-picker*
  {::mf/private true}
  [{:keys [frames frame]}]
  (let [show*  (mf/use-state false)
        show?  (deref show*)
        open!  (mf/use-fn #(reset! show* true))
        close! (mf/use-fn #(reset! show* false))
        on-select
        (mf/use-fn
         (fn [event]
           (let [id (-> (dom/get-current-target event)
                        (dom/get-data "id")
                        (uuid))]
             (st/emit! (dhtml/go-to-frame id)))))]
    (when (seq frames)
      [:div {:class (stl/css :board-picker)
             :on-click open!}
       [:span {:class (stl/css :board-picker-name)}
        (or (:name frame) "—")]
       [:span {:class (stl/css :icon)} deprecated-icon/arrow]
       [:& dropdown {:show show? :on-close close!}
        [:ul {:class (stl/css :dropdown)}
         (for [f frames]
           [:li {:key (dm/str (:id f))
                 :class (stl/css-case :dropdown-item true
                                      :selected (= (:id f) (:id frame)))
                 :data-id (dm/str (:id f))
                 :on-click on-select}
            [:span {:class (stl/css :dropdown-item-name)} (:name f)]
            (when (= (:id f) (:id frame))
              [:span {:class (stl/css :icon-check)} deprecated-icon/tick])])]]])))

;; ---------------------------------------------------------------------------
;; Center zone: section mode buttons

(def ^:private sections
  [{:id "prototype"     :icon i/play      :label-key "viewer.html-mode.toolbar.prototype"}
   {:id "workspace"     :icon i/code      :label-key "viewer.html-mode.toolbar.workspace"}
   {:id "design-tokens" :icon i/tokens    :label-key "viewer.html-mode.toolbar.design-tokens"}
   {:id "components"    :icon i/component :label-key "viewer.html-mode.toolbar.components"}])

(mf/defc mode-zone*
  {::mf/private true}
  [{:keys [mode]}]
  (let [mode-str (name mode)
        on-click
        (mf/use-fn
         (fn [event]
           (let [value (-> (dom/get-current-target event)
                           (dom/get-data "value"))]
             (st/emit! (dhtml/go-to-mode value)))))]
    [:div {:class (stl/css :mode-zone)}
     (for [{:keys [id icon label-key]} sections]
       (let [selected? (= id mode-str)]
         [:> icon-button* {:key id
                           :variant "ghost"
                           :icon icon
                           :class (stl/css-case :mode-zone-btn true
                                                :selected selected?)
                           :data-value id
                           :aria-pressed selected?
                           :aria-label (tr label-key)
                           :title (tr label-key)
                           :on-click on-click}]))]))

;; ---------------------------------------------------------------------------
;; Right zone: zoom + share + open-in-workspace

(mf/defc zoom-widget*
  {::mf/private true}
  [{:keys [zoom]}]
  (let [on-increase (mf/use-fn #(st/emit! dhtml/increase-zoom))
        on-decrease (mf/use-fn #(st/emit! dhtml/decrease-zoom))
        on-reset    (mf/use-fn #(st/emit! dhtml/reset-zoom))]
    [:div {:class (stl/css :zoom-widget)
           :title (tr "workspace.header.zoom")}
     [:button {:class (stl/css :zoom-btn)
               :aria-label (tr "workspace.header.zoom")
               :on-click on-decrease}
      deprecated-icon/remove-icon]
     [:button {:class (stl/css :zoom-reset)
               :title (tr "workspace.header.reset-zoom")
               :on-click on-reset}
      (dm/str (js/Math.round (* 100 (or zoom 1))) "%")]
     [:button {:class (stl/css :zoom-btn)
               :aria-label (tr "workspace.header.zoom")
               :on-click on-increase}
      deprecated-icon/add]]))

(mf/defc header*
  [{:keys [project file page frames frame mode permissions]}]
  (let [zoom    (mf/deref hrefs/zoom)
        busy?   (mf/deref hrefs/busy?)
        team-id (:team-id project)
        ;; The zoom container only wraps the iframe-driven sections.
        zoomable? (or (= mode :workspace) (= mode :prototype))

        ;; Preview-background picker (Workspace + Components tabs). The
        ;; selection lives in mode-local state so it can be driven from
        ;; the header while the actual preview is rendered elsewhere.
        bg-tab?    (or (= mode :workspace) (= mode :components))
        preview-bg (mf/deref (if (= mode :components)
                               hrefs/components-bg
                               hrefs/workspace-bg))

        on-refresh
        (mf/use-fn #(st/emit! (dhtml/refresh-bundle)))

        on-bg-change
        (mf/use-fn
         (mf/deps mode)
         (fn [hex]
           (st/emit! (dhtml/set-preview-bg
                      (if (= mode :components) :components :workspace)
                      hex))))

        ;; Device-view controls (Prototype tab) sit next to the Zoom
        ;; widget. Settings are held in mode-local state and consumed by
        ;; the section for board sizing / touch mode / stage background.
        device-view (mf/deref hrefs/device-view)

        on-device-view-change
        (mf/use-fn (fn [m] (st/emit! (dhtml/update-device-view m))))

        go-dashboard
        (mf/use-fn
         (mf/deps team-id)
         (fn []
           (when team-id
             (st/emit! (dcm/go-to-dashboard-recent :team-id team-id)))))

        open-share-dialog
        (mf/use-fn
         (mf/deps page file)
         (fn []
           (modal/show! :share-link {:page page :file file})
           (modal/disallow-click-outside!)))

        go-workspace
        (mf/use-fn
         (mf/deps team-id file page)
         (fn []
           (st/emit! (dcm/go-to-workspace
                      :team-id team-id
                      :file-id (:id file)
                      :page-id (:id page)
                      ::rt/new-window true
                      ::rt/window-name (dm/str "workspace-" (:id file))))))]

    [:header {:class (stl/css :header)}
     [:div {:class (stl/css :left-zone)}
      [:button {:class (stl/css :logo-btn)
                :on-click go-dashboard
                :aria-label (tr "labels.dashboard")}
       penpot-logo-icon]
      [:> page-dropdown* {:file file :page page}]
      ;; Prototype's board picker sits between the breadcrumb and Refresh.
      (when (= mode :prototype)
        [:> board-picker* {:frames frames :frame frame}])
      ;; Refresh is the rightmost item of the left cluster in every tab
      ;; (after the board picker in Prototype). It re-fetches the bundle;
      ;; the glyph spins + the button disables while the section is
      ;; fetching / converting (mirrored via `hrefs/busy?`).
      [:> icon-button* {:variant "ghost"
                        :icon i/reload
                        :class (stl/css :refresh-btn)
                        :icon-class (stl/css-case :refresh-icon-spinning busy?)
                        :on-click on-refresh
                        :disabled busy?
                        :aria-label (tr "viewer.html-mode.toolbar.refresh")
                        :title (tr "viewer.html-mode.toolbar.refresh")}]]

     [:> mode-zone* {:mode mode}]

     [:div {:class (stl/css :right-zone)}
      (when zoomable?
        [:> zoom-widget* {:zoom zoom}])
      ;; Device-view controls live right next to Zoom, on the Prototype
      ;; tab (the only one with a resizable board).
      (when (= mode :prototype)
        [:> device-view-controls* {:settings device-view
                                   :default-dims (proto/board-dims frame)
                                   :on-change on-device-view-change}])
      ;; Background picker sits just left of Share, on the tabs whose
      ;; preview has a recolorable backdrop (Workspace + Components).
      (when bg-tab?
        [:> bg-swatches* {:selected preview-bg
                          :on-change on-bg-change}])
      (when (:in-team permissions)
        [:button {:class (stl/css :share-btn)
                  :on-click open-share-dialog}
         (tr "labels.share")])
      (when (:can-edit permissions)
        [:> icon-button* {:variant "ghost"
                          :icon i/curve
                          :class (stl/css :workspace-btn)
                          :aria-label (tr "viewer.html-mode.header.open-workspace")
                          :title (tr "viewer.html-mode.header.open-workspace")
                          :on-click go-workspace}])]]))
