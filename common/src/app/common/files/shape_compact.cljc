;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.files.shape-compact
  "Helpers for the binfile-v3 compact format: prune derivable and
  redundant shape attributes before JSON encoding (`compact-shape`) and
  restore them after decoding (`expand-shape`).

  The pruning rules are intentionally conservative: selrect and points
  are only omitted when they are exactly derivable from the plain
  geometry attributes (identity transform and no rotation); otherwise
  the shape geometry is left untouched."
  (:require
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.types.shape :as cts]
   [clojure.walk :as walk]))

(defn- pathish?
  [type]
  (or (= type :path)
      (= type :bool)))

(defn- identity-transform?
  "A nil transform is equivalent to the unit matrix."
  [transform]
  (or (nil? transform)
      (gmt/unit? transform)))

(defn- derivable-geometry?
  "Check if selrect/points can be exactly derived from the plain
  geometry attributes."
  [{:keys [rotation transform]}]
  (and (or (nil? rotation) (zero? rotation))
       (identity-transform? transform)))

(defn compact-shape
  "Remove derivable and redundant attributes from a shape before JSON
  encoding. The inverse operation is `expand-shape`."
  [{:keys [type] :as shape}]
  (let [derivable? (derivable-geometry? shape)]
    (-> (cond->> shape (record? shape) (into {}))
        (d/without-nils)
        (cond-> (identity-transform? (:transform shape))
          (dissoc :transform :transform-inverse))
        (cond-> (and derivable? (not (pathish? type)))
          (dissoc :selrect :points))
        (cond-> (and derivable? (pathish? type))
          (dissoc :points :x :y :width :height)))))

(defn expand-shape
  "Restore the attributes omitted by `compact-shape`. Expected to run
  after schema decoding and before validation. It is a no-op for
  complete (non compacted) shapes."
  [{:keys [type] :as shape}]
  (let [transform (d/nilv (:transform shape) (gmt/matrix))
        selrect   (:selrect shape)
        shape     (-> shape
                      (assoc :transform transform)
                      (update :transform-inverse #(or % (gmt/inverse transform))))
        shape     (cond-> shape
                    (some? selrect)
                    (-> (update :x d/nilv (:x selrect))
                        (update :y d/nilv (:y selrect))
                        (update :width d/nilv (:width selrect))
                        (update :height d/nilv (:height selrect))))]
    (if (and (some? (:selrect shape))
             (some? (:points shape)))
      shape
      (if (pathish? type)
        (cts/setup-path shape)
        (cts/setup-rect shape)))))

(def ^:private rounding-factor
  "10^decimals; NOTE: we intentionally don't use `mth/precision` here
  because on the JVM `mth/round` casts to float and loses precision on
  values over ~16.7M (2^24)."
  1e4)

(defn- finite?*
  [v]
  #?(:clj  (Double/isFinite (double v))
     :cljs (js/isFinite v)))

(defn- round-value
  [v]
  (if (and (number? v)
           (not (integer? v))
           (finite?* v))
    (/ (double #?(:clj  (Math/round (* (double v) rounding-factor))
                  :cljs (js/Math.round (* v rounding-factor))))
       rounding-factor)
    v))

(defn round-values
  "Round all non-integer numeric leaf values of a JSON-ready data
  structure to a fixed number of decimals; removes float32 conversion
  artifacts (like 0.6000000238418579) from the serialized output.
  Expected to be applied after JSON encoding, where all values are
  plain data."
  [data]
  (walk/postwalk round-value data))
