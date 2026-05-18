;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.html-mode.cache
  "LRU cache for HTML Mode converted output.

   Conversion is fast (~50–150ms for a typical page) but not free; the
   cache makes re-mounts and back-and-forth between tabs effectively
   instant. The standard key (`key-for`) includes the file's `revn` so
   the cache is naturally invalidated when the workspace persists
   edits.

   The cache is a module-level `js/Map`; it survives shadow-cljs
   hot-reloads but is dropped on a full page reload.")

(def ^:private default-capacity 4)

(defonce ^:private state
  ;; `:capacity` is mutable to let callers (or tests) reconfigure the
  ;; ceiling without losing existing entries. `:map` is the storage.
  (atom {:capacity default-capacity
         :map      (js/Map.)}))

(defn key-for
  "Build the canonical cache key from the file and page maps. The
   string form is stable as long as `file-id`, `page-id` and the
   file's `revn` are stable."
  [file page]
  (str (:id file) "::" (:id page) "::" (:revn file)))

(defn get!
  "Return the cached value for `k`, refreshing its LRU position.
   Returns nil on miss."
  [k]
  (let [m (:map @state)]
    (when (.has m k)
      (let [v (.get m k)]
        (.delete m k)
        (.set m k v)
        v))))

(defn put!
  "Insert `[k v]` into the cache, evicting the oldest entry if the
   cache is at capacity. Returns `v`."
  [k v]
  (let [{:keys [capacity ^js map]} @state]
    (when (>= (.-size map) capacity)
      (let [iter   (.keys map)
            oldest (.-value (.next iter))]
        (when (some? oldest) (.delete map oldest))))
    (.set map k v))
  v)

(defn clear!
  "Drop every entry. Intended for tests and `dv/finalize` paths."
  []
  (.clear (:map @state)))

(defn set-capacity!
  "Override the eviction ceiling. Tests use it to exercise eviction
   without inserting many entries."
  [n]
  (swap! state assoc :capacity n))

(defn size
  "Current entry count. Test-only helper."
  []
  (.-size (:map @state)))
