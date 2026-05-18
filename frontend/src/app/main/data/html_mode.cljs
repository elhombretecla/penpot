;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.html-mode
  "Events for the HTML Mode feature.

   HTML Mode reuses the viewer route with `:section :html`. Navigation mirrors
   `app.main.data.common/go-to-viewer`: the file is persisted before navigating
   and the new mode opens in its own window so the workspace tab is preserved."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.uuid :as uuid]
   [app.main.data.persistence :as-alias dps]
   [app.main.data.viewer :as dv]
   [app.main.router :as rt]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn go-to-html-mode
  "Persist the current file and open the viewer in a new window with the
   HTML Mode section active."
  [& {:keys [file-id page-id frame-id] :as options}]
  (ptk/reify ::go-to-html-mode
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id (or page-id (:current-page-id state))
            file-id (or file-id (:current-file-id state))
            params  (-> {:file-id file-id
                         :page-id page-id
                         :section :html
                         :frame-id frame-id}
                        (d/without-nils))
            window  (dm/str "html-mode-" file-id)
            options (merge {::rt/new-window true
                            ::rt/window-name window}
                           options)]
        (rx/of ::dps/force-persist
               (rt/nav :viewer params options))))))

(defn refresh-viewer-bundle
  "Re-fetch the file/page bundle for the current HTML Mode session so the
   preview reflects any workspace edits that landed since the window was
   opened. Reads the active file-id / page-id / share-id from the route
   so callers don't have to thread them through."
  []
  (ptk/reify ::refresh-viewer-bundle
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id     (:current-file-id state)
            share-id    (get-in state [:viewer-local :share-id])
            page-id-str (some-> state :route :query-params :page-id)
            page-id     (some-> page-id-str uuid/parse)]
        (when (and (uuid? file-id) (uuid? page-id))
          (rx/of (dv/initialize
                  (cond-> {:file-id file-id :page-id page-id}
                    (uuid? share-id) (assoc :share-id share-id)))))))))
