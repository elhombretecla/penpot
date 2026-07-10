;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns binfile-bench
  "Dev-only helpers for measuring binfile-v3 (.penpot) size composition
  and import/export timing. The census only depends on java.util.zip,
  so it can run standalone (no running system needed):

    cd backend
    clojure -M:dev -e \"(require 'binfile-bench)(binfile-bench/print-census \\\"path/to/file.penpot\\\")\"

  The bench-* helpers resolve app namespaces lazily and require a
  running system (use them from the devenv REPL)."
  (:require
   [clojure.pprint :as pp])
  (:import
   java.io.File
   java.util.zip.ZipEntry
   java.util.zip.ZipFile))

(def ^:private uuid-re
  "[0-9a-fA-F-]{36}")

(def ^:private bucket-patterns
  "Ordered [bucket regex] pairs; first match wins."
  [[:manifest       #"^manifest\.json$"]
   [:shape          (re-pattern (str "^files/" uuid-re "/pages/" uuid-re "/" uuid-re "\\.json$"))]
   [:page-meta      (re-pattern (str "^files/" uuid-re "/pages/" uuid-re "\\.json$"))]
   [:component      (re-pattern (str "^files/" uuid-re "/components/.+\\.json$"))]
   [:color          (re-pattern (str "^files/" uuid-re "/colors/.+\\.json$"))]
   [:typography     (re-pattern (str "^files/" uuid-re "/typographies/.+\\.json$"))]
   [:media-meta     (re-pattern (str "^files/" uuid-re "/media/.+\\.json$"))]
   [:tokens         (re-pattern (str "^files/" uuid-re "/tokens\\.json$"))]
   [:thumbnail      (re-pattern (str "^files/" uuid-re "/thumbnails/.+$"))]
   [:file-meta      (re-pattern (str "^files/" uuid-re "\\.json$"))]
   [:storage-meta   #"^objects/[^/]+\.json$"]
   [:storage-binary #"^objects/[^/]+$"]])

(defn- bucket-of
  [^String path]
  (or (some (fn [[bucket pattern]]
              (when (re-matches pattern path) bucket))
            bucket-patterns)
      :other))

(defn census
  "Scan the central directory of a .penpot (binfile-v3) file and return
  per-bucket entry counts and compressed/uncompressed byte totals, plus
  the structural overhead (file size minus the sum of compressed entry
  payloads: local headers, central directory, entry names, etc.)."
  [path]
  (let [file (File. ^String path)]
    (with-open [zfile (ZipFile. file)]
      (let [entries (enumeration-seq (.entries zfile))
            stats   (reduce (fn [acc ^ZipEntry entry]
                              (let [bucket (bucket-of (.getName entry))]
                                (-> acc
                                    (update-in [bucket :entries] (fnil inc 0))
                                    (update-in [bucket :compressed] (fnil + 0) (max 0 (.getCompressedSize entry)))
                                    (update-in [bucket :uncompressed] (fnil + 0) (max 0 (.getSize entry))))))
                            {}
                            entries)
            totals  (reduce (fn [acc [_ {:keys [entries compressed uncompressed]}]]
                              (-> acc
                                  (update :entries + entries)
                                  (update :compressed + compressed)
                                  (update :uncompressed + uncompressed)))
                            {:entries 0 :compressed 0 :uncompressed 0}
                            stats)
            fsize   (.length file)]
        {:path path
         :file-size fsize
         :buckets stats
         :totals totals
         :zip-overhead (- fsize (:compressed totals))}))))

(defn- fmt-mb
  [n]
  (format "%.1f" (/ (double n) 1048576.0)))

(defn print-census
  "Print a census as an aligned table (sizes in MiB)."
  [path]
  (let [{:keys [file-size buckets totals zip-overhead]} (census path)
        rows (->> buckets
                  (map (fn [[bucket {:keys [entries compressed uncompressed]}]]
                         {:bucket bucket
                          :entries entries
                          :compressed-mb (fmt-mb compressed)
                          :uncompressed-mb (fmt-mb uncompressed)}))
                  (sort-by :entries >))]
    (println "File:" path)
    (println "Size:" (fmt-mb file-size) "MiB")
    (pp/print-table [:bucket :entries :compressed-mb :uncompressed-mb] rows)
    (println "Totals:" (:entries totals) "entries,"
             (fmt-mb (:compressed totals)) "MiB compressed,"
             (fmt-mb (:uncompressed totals)) "MiB uncompressed")
    (println "ZIP structural overhead:" (fmt-mb zip-overhead) "MiB"
             (format "(%.1f%%)" (* 100.0 (/ (double zip-overhead) (double file-size)))))))

;; --- Timing helpers (need a running system; used from the devenv REPL)

(defn bench-import!
  "Import a .penpot file measuring wall time. Returns {:elapsed-ms ... :result ...}.
  Requires a running system (see backend/scripts/repl); pass the profile-id
  and project-id of the destination."
  [path {:keys [profile-id project-id]}]
  (let [import-files! (requiring-resolve 'app.binfile.v3/import-files!)
        main-system   @(requiring-resolve 'app.main/system)
        start         (System/nanoTime)
        result        (import-files!
                       (assoc main-system
                              :app.binfile.common/input (File. ^String path)
                              :app.binfile.common/profile-id profile-id
                              :app.binfile.common/project-id project-id)
                       nil)]
    {:elapsed-ms (/ (- (System/nanoTime) start) 1e6)
     :result result}))

(defn bench-export!
  "Export file-ids to a temp .penpot measuring wall time.
  Returns {:elapsed-ms ... :path ...}."
  [file-ids]
  (let [export-files! (requiring-resolve 'app.binfile.v3/export-files!)
        main-system   @(requiring-resolve 'app.main/system)
        target        (File/createTempFile "binfile-bench" ".penpot")
        start         (System/nanoTime)]
    (with-open [output (java.io.FileOutputStream. target)]
      (export-files!
       (assoc main-system
              :app.binfile.common/ids (set file-ids)
              :app.binfile.common/embed-assets false
              :app.binfile.common/include-libraries false)
       output))
    {:elapsed-ms (/ (- (System/nanoTime) start) 1e6)
     :path (.getAbsolutePath target)}))
