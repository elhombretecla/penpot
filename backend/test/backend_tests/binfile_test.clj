;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.binfile-test
  "Internal binfile test, no RPC involved"
  (:require
   [app.binfile.common :as bfc]
   [app.binfile.v3 :as v3]
   [app.common.features :as cfeat]
   [app.common.pprint :as pp]
   [app.common.thumbnails :as thc]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.http :as http]
   [app.rpc :as-alias rpc]
   [app.storage :as sto]
   [app.storage.tmp :as tmp]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [datoteka.io :as io]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- update-file!
  [& {:keys [profile-id file-id changes revn] :or {revn 0}}]
  (let [params {::th/type :update-file
                ::rpc/profile-id profile-id
                :id file-id
                :session-id (uuid/random)
                :revn revn
                :vern 0
                :features cfeat/supported-features
                :changes changes}
        out    (th/command! params)]
    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (:result out)))

(defn- prepare-simple-file
  [profile]
  (let [page-id-1 (uuid/custom 1 1)
        page-id-2 (uuid/custom 1 2)
        shape-id  (uuid/custom 2 1)
        file      (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id (:default-project-id profile)
                                      :is-shared false})]
    (update-file!
     :file-id (:id file)
     :profile-id (:id profile)
     :revn 0
     :vern 0
     :changes
     [{:type :add-page
       :name "test 1"
       :id page-id-1}
      {:type :add-page
       :name "test 2"
       :id page-id-2}])

    (update-file!
     :file-id (:id file)
     :profile-id (:id profile)
     :revn 0
     :vern 0
     :changes
     [{:type :add-obj
       :page-id page-id-1
       :id shape-id
       :parent-id uuid/zero
       :frame-id uuid/zero
       :components-v2 true
       :obj (cts/setup-shape
             {:id shape-id
              :name "image"
              :frame-id uuid/zero
              :parent-id uuid/zero
              :type :rect})}])

    (dissoc file :data)))

(t/deftest export-binfile-v3
  (let [profile (th/create-profile* 1)
        file    (prepare-simple-file profile)
        output  (tmp/tempfile :suffix ".zip")]

    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/ids #{(:id file)})
         (assoc ::bfc/embed-assets false)
         (assoc ::bfc/include-libraries false))
     (io/output-stream output))

    (let [result (-> th/*system*
                     (assoc ::bfc/project-id (:default-project-id profile))
                     (assoc ::bfc/profile-id (:id profile))
                     (assoc ::bfc/input output)
                     (v3/import-files!))]
      (t/is (= (count result) 1))
      (t/is (every? uuid? result)))))

(defn- zip-entry-names
  [path]
  (with-open [zfile (java.util.zip.ZipFile. (fs/file path))]
    (mapv (fn [^java.util.zip.ZipEntry entry] (.getName entry))
          (iterator-seq (.entries zfile)))))

(t/deftest export-binfile-v3-compact
  (let [profile (th/create-profile* 1)
        file    (prepare-simple-file profile)
        output  (tmp/tempfile :suffix ".zip")]

    (binding [cf/flags (conj cf/flags
                             :binfile-v3-compact-export
                             :binfile-v3-round-floats)]
      (v3/export-files!
       (-> th/*system*
           (assoc ::bfc/ids #{(:id file)})
           (assoc ::bfc/embed-assets false)
           (assoc ::bfc/include-libraries false))
       (io/output-stream output)))

    ;; The compact layout writes one entry per page (objects embedded,
    ;; so no per-shape entries) and version 2 on the manifest
    (t/is (= 2 (:version (v3/get-manifest output))))

    (let [entries (zip-entry-names output)]
      (t/is (empty? (filterv #(re-matches #"files/[^/]+/pages/[^/]+/[^/]+\.json" %) entries)))
      ;; the initial page created by create-file* plus the two pages
      ;; added by prepare-simple-file
      (t/is (= 3 (count (filterv #(re-matches #"files/[^/]+/pages/[^/]+\.json" %) entries)))))

    ;; Importing a compact binfile needs no flag: the format is
    ;; detected structurally from the page entries
    (let [result (-> th/*system*
                     (assoc ::bfc/project-id (:default-project-id profile))
                     (assoc ::bfc/profile-id (:id profile))
                     (assoc ::bfc/input output)
                     (v3/import-files!))]
      (t/is (= (count result) 1))
      (t/is (every? uuid? result))

      ;; The pruned attributes (selrect, points, transform,
      ;; transform-inverse) must be restored on import: the imported
      ;; shape has to be identical to the original one
      (let [page-id  (uuid/custom 1 1)
            shape-id (uuid/custom 2 1)
            file1    (bfc/get-file th/*system* (:id file) :realize? true)
            file2    (bfc/get-file th/*system* (first result) :realize? true)
            shape1   (get-in file1 [:data :pages-index page-id :objects shape-id])
            shape2   (get-in file2 [:data :pages-index page-id :objects shape-id])]
        (t/is (some? shape1))
        (t/is (some? shape2))
        (t/is (= (into {} shape1) (into {} shape2)))))))
