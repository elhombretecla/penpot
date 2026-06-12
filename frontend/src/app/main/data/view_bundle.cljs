;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.view-bundle
  "Shared fetching + decoration logic for the read-only file bundle
   (`:get-view-only-bundle`). Extracted from `app.main.data.viewer` so
   read-only modes that are NOT the SVG viewer (e.g. HTML Mode) can
   load the same bundle without depending on the viewer's state
   lifecycle.

   The two non-obvious responsibilities that MUST stay shared (forking
   them caused subtle drift in the past):

   - **Transit pointer resolution.** Large files arrive with
     `:pages-index` entries (and other `:data` values) replaced by
     transit pointers; each must be resolved with a follow-up
     `:get-file-fragment` call before the bundle is usable.

   - **Page decoration.** Consumers index boards through `:frames` /
     `:all-frames` on each page, computed via
     `ctt/get-viewer-frames`."
  (:require
   [app.common.data.macros :as dm]
   [app.common.features :as cfeat]
   [app.common.transit :as t]
   [app.common.types.shape-tree :as ctt]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]))

(defn fetch
  "Return an observable that emits ONE fully-resolved view-only bundle
   for `file-id` (optionally scoped by `share-id`) and completes. All
   transit pointers inside `[:file :data]` (the `:pages-index` first,
   then any other pointer-valued entry) are resolved via
   `:get-file-fragment` before emission.

   NOTE: the viewer (and any non-logged-in consumer) cannot know the
   team's enabled features before fetching, so the request reports the
   whole supported set; the caller is expected to re-initialize
   features from the returned team."
  [{:keys [file-id share-id]}]
  (let [features cfeat/supported-features
        params   (cond-> {:file-id file-id :features features}
                   (uuid? share-id)
                   (assoc :share-id share-id))

        resolve  (fn [[key pointer]]
                   (let [params {:file-id file-id :fragment-id @pointer}
                         params (cond-> params
                                  (uuid? share-id)
                                  (assoc :share-id share-id))]
                     (->> (rp/cmd! :get-file-fragment params)
                          (rx/map :data)
                          (rx/map #(vector key %)))))]

    (->> (rp/cmd! :get-view-only-bundle params)
         (rx/mapcat
          (fn [bundle]
            (->> (rx/from (-> bundle :file :data :pages-index seq))
                 (rx/merge-map
                  (fn [[_ page :as kp]]
                    (if (t/pointer? page)
                      (resolve kp)
                      (rx/of kp))))
                 (rx/reduce conj {})
                 (rx/map (fn [pages-index]
                           (update-in bundle [:file :data] assoc :pages-index pages-index))))))
         (rx/mapcat
          (fn [bundle]
            (->> (rx/from (-> bundle :file :data seq))
                 (rx/merge-map
                  (fn [[_ object :as kp]]
                    (if (t/pointer? object)
                      (resolve kp)
                      (rx/of kp))))
                 (rx/reduce conj {})
                 (rx/map (fn [data]
                           (update bundle :file assoc :data data)))))))))

(defn decorate-pages
  "Build the `{page-id page-data}` map consumers store in state, where
   each page is decorated with the `:frames` / `:all-frames` vectors
   (`ctt/get-viewer-frames`) used for board indexing and pagination."
  [file]
  (->> (dm/get-in file [:data :pages])
       (map (fn [page-id]
              (let [data (get-in file [:data :pages-index page-id])]
                [page-id (assoc data
                                :frames (ctt/get-viewer-frames (:objects data))
                                :all-frames (ctt/get-viewer-frames (:objects data) {:all-frames? true}))])))
       (into {})))
