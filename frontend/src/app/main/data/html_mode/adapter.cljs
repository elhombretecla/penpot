;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.html-mode.adapter
  "Translates Penpot's in-memory page / shape data into the plain JavaScript
   shape that `@penpot/html-converter` expects.

   The converter expects camelCase object keys, string values for enum-like
   keywords (e.g. `\"rect\"` instead of `:rect`), and UUID strings instead of
   `cljs.core/UUID` instances. Penpot's CLJS data uses kebab-case keyword
   keys, keyword enum values, and `UUID` objects, so this namespace performs
   that translation recursively.

   The translation is deliberately *structural*: every map key is renamed
   kebab → camel, every keyword value becomes a string, every UUID is
   stringified. Fields the converter does not understand are simply ignored
   downstream, so there is no whitelist to keep in sync with the upstream
   shape definitions."
  (:require
   [app.common.types.path :as path]
   [cuerdas.core :as str]))

;; ---------------------------------------------------------------------------
;; Key translation
;;
;; A page may carry thousands of shapes, all of which use the same handful of
;; keyword keys. We memoise the kebab->camel conversion to avoid recomputing
;; it for every shape.

(def ^:private kebab->camel-cache
  (atom {}))

(defn- camelize-kw
  [k]
  (if-let [cached (get @kebab->camel-cache k)]
    cached
    (let [out (str/camel (name k))]
      (swap! kebab->camel-cache assoc k out)
      out)))

(defn- ->js-key
  [k]
  (cond
    (keyword? k) (camelize-kw k)
    (string? k)  k
    (uuid? k)    (str k)
    :else        (str k)))

;; ---------------------------------------------------------------------------
;; Value translation

(declare ->js)

(defn- ->js-object
  [m]
  (let [obj #js {}]
    (reduce-kv
     (fn [_ k v]
       (unchecked-set obj (->js-key k) (->js v))
       nil)
     nil
     m)
    obj))

(defn- ->js-array
  [coll]
  (let [arr #js []]
    (reduce (fn [_ v] (.push arr (->js v)) nil) nil coll)
    arr))

(defn- path-content?
  "True if `v` looks like a Penpot path-shape `:content` payload: either a
   `PathData` instance or a sequential of segment maps (each carrying
   a `:command` key). The legacy in-memory vector form still appears for
   plugin-created paths and for shapes that haven't been migrated yet."
  [v]
  (or (path/content? v)
      (and (sequential? v)
           (map? (first v))
           (contains? (first v) :command))))

;; ---------------------------------------------------------------------------
;; SVG content
;;
;; SVG raw shapes carry a nested `{:tag :attrs :content}` node tree. The
;; `:attrs` map must preserve its original kebab-case keys (`stroke-width`,
;; `stroke-linecap`, `fill-rule`, …) because SVG XML attribute names are
;; case-sensitive — `strokeWidth="2"` is silently ignored by the SVG
;; parser. The generic `->js-key` camelization breaks every SVG icon on
;; the page, so we walk svg-raw content with a key-preserving variant.

(declare ->js-svg-node)

(defn- ->js-svg-attrs
  "Convert an SVG attributes map to a JS object, preserving the original
   keyword name (kebab-case or single-word) verbatim. Values pass through
   the generic `->js` so e.g. transform matrices and numeric strings are
   stringified as before."
  [m]
  (let [obj #js {}]
    (reduce-kv
     (fn [_ k v]
       (let [k-str (cond
                     (keyword? k) (name k)
                     (string? k)  k
                     :else        (str k))]
         (unchecked-set obj k-str (->js v)))
       nil)
     nil
     m)
    obj))

(defn- ->js-svg-node
  "Recursively convert an SVG node tree. Maps with a `:tag` key are
   treated as SVG nodes: their `:attrs` map is serialised with raw
   kebab-case keys; `:content` is a sequence of child nodes which may
   itself be a string (text content) or another node. Anything that
   doesn't look like an SVG node falls back to the generic `->js`."
  [node]
  (cond
    (nil? node)        nil
    (string? node)     node
    (number? node)     node
    (boolean? node)    node
    (keyword? node)    (name node)
    (sequential? node) (let [arr #js []]
                         (doseq [n node]
                           (.push arr (->js-svg-node n)))
                         arr)
    (and (map? node) (contains? node :tag))
    (let [obj #js {}]
      (doseq [[k v] node]
        (unchecked-set obj (->js-key k)
                       (cond
                         (= k :attrs)   (->js-svg-attrs v)
                         (= k :content) (->js-svg-node v)
                         :else          (->js v))))
      obj)
    :else (->js node)))

(defn ->js
  "Recursively convert a Penpot value to a JavaScript value that the
   converter understands. Maps become plain JS objects with camelCase
   keys, sequences become JS arrays, keywords become strings, UUIDs
   become strings, and scalars are passed through unchanged.

   Path content (a `PathData` instance or a vector of segment maps) is
   flattened to its SVG `d` string representation *before* the generic
   sequential branch runs — otherwise it would serialise as a JS array of
   segment objects and the converter's path renderer would emit
   `d=\"[object Object],...\"`."
  [v]
  (cond
    (nil? v)         nil
    (boolean? v)     v
    (number? v)      v
    (string? v)      v
    (keyword? v)     (name v)
    (uuid? v)        (str v)
    (path-content? v) (.toString (path/content v))
    (map? v)         (->js-object v)
    (set? v)         (->js-array v)
    (sequential? v)  (->js-array v)
    :else            v))

;; ---------------------------------------------------------------------------
;; Page / shape entry points

(defn ->js-shape
  "Convert a Penpot shape map to the JS object expected by the converter.
   The shape is opaque to this function — every key is recursively
   translated. Returns a fresh JS object.

   svg-raw shapes are special-cased so their `:content` SVG tree keeps
   the original kebab-case attribute keys (see `->js-svg-node`)."
  [shape]
  (if (= :svg-raw (get shape :type))
    (let [obj #js {}]
      (reduce-kv
       (fn [_ k v]
         (unchecked-set obj (->js-key k)
                        (if (= k :content)
                          (->js-svg-node v)
                          (->js v)))
         nil)
       nil
       shape)
      obj)
    (->js-object shape)))

(defn ->js-page
  "Build the JS `Page` object expected by `convertPage` / `convertPageShapes`.

   Takes a Penpot page map (`{:id :name :objects :options}`) and returns
   `{id, name, objects, options}` where `objects` is a JS object keyed by
   shape UUID string, and each value is a converter-shaped JS shape."
  [page]
  (let [objects (get page :objects)
        js-objects #js {}]
    (reduce-kv
     (fn [_ id shape]
       (unchecked-set js-objects (->js-key id) (->js-shape shape))
       nil)
     nil
     objects)
    #js {:id      (->js-key (get page :id))
         :name    (get page :name)
         :objects js-objects
         :options (->js (get page :options))}))

(defn reset-cache!
  "Drop the memoised key cache. Test-only entry point."
  []
  (reset! kebab->camel-cache {}))
