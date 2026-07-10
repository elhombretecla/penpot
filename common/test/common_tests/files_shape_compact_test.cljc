;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.files-shape-compact-test
  (:require
   [app.common.data :as d]
   [app.common.files.shape-compact :as fsc]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.schema.generators :as sg]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(defn- make-rect-shape
  [attrs]
  (cts/setup-shape
   (merge {:type :rect
           :id (uuid/uuid "2532eab0-9375-8000-8002-000000000001")
           :name "Rect"
           :frame-id uuid/zero
           :parent-id uuid/zero
           :x 10 :y 20 :width 30 :height 40}
          attrs)))

(defn- make-path-shape
  [attrs]
  (cts/setup-shape
   (merge {:type :path
           :id (uuid/uuid "2532eab0-9375-8000-8002-000000000002")
           :name "Path"
           :frame-id uuid/zero
           :parent-id uuid/zero
           :content [{:command :move-to :params {:x 0 :y 0}}
                     {:command :line-to :params {:x 10 :y 0}}
                     {:command :line-to :params {:x 10 :y 10}}
                     {:command :close-path}]}
          attrs)))

(t/deftest compact-rect-prunes-derivable-geometry
  (let [shape   (make-rect-shape {})
        compact (fsc/compact-shape shape)]
    (t/is (not (contains? compact :selrect)))
    (t/is (not (contains? compact :points)))
    (t/is (not (contains? compact :transform)))
    (t/is (not (contains? compact :transform-inverse)))
    (t/is (= (:x compact) (:x shape)))
    (t/is (= (:width compact) (:width shape)))))

(t/deftest compact-expand-rect-roundtrip
  (let [shape    (make-rect-shape {})
        expanded (fsc/expand-shape (fsc/compact-shape shape))]
    (t/is (= (d/without-nils (into {} shape))
             (d/without-nils (into {} expanded))))
    ;; compact-shape returns a plain map (export side); on import the
    ;; JSON decoder rebuilds the Shape record before expand-shape runs,
    ;; so validity is checked over the record.
    (t/is (cts/valid-shape? (cts/create-shape expanded)))))

(t/deftest compact-keeps-geometry-on-rotated-shapes
  (let [transform (gmt/rotate-matrix 45 (gpt/point 25 40))
        shape     (-> (make-rect-shape {})
                      (assoc :rotation 45)
                      (assoc :transform transform)
                      (assoc :transform-inverse (gmt/inverse transform)))
        compact   (fsc/compact-shape shape)]
    (t/is (contains? compact :selrect))
    (t/is (contains? compact :points))
    (t/is (contains? compact :transform))
    (t/is (contains? compact :transform-inverse))
    (t/is (= (d/without-nils (into {} shape))
             (d/without-nils (into {} (fsc/expand-shape compact)))))))

(t/deftest compact-expand-path-roundtrip
  (let [shape   (make-path-shape {})
        compact (fsc/compact-shape shape)]
    ;; selrect is preserved for paths (recomputing it from content is
    ;; expensive and float-sensitive); points and the geom attrs that
    ;; duplicate selrect are pruned.
    (t/is (contains? compact :selrect))
    (t/is (not (contains? compact :points)))
    (t/is (not (contains? compact :x)))
    (t/is (not (contains? compact :width)))
    (let [expanded (fsc/expand-shape compact)]
      (t/is (= (:selrect shape) (:selrect expanded)))
      (t/is (= (:points shape) (:points expanded)))
      (t/is (= (:content shape) (:content expanded)))
      (t/is (cts/valid-shape? (cts/create-shape expanded))))))

(t/deftest expand-is-noop-for-complete-shapes
  (let [shape (make-rect-shape {})]
    (t/is (= (into {} shape)
             (into {} (fsc/expand-shape shape))))))

(t/deftest compact-removes-nils
  (let [shape   (-> (make-rect-shape {})
                    (assoc :flip-x nil)
                    (assoc :flip-y nil))
        compact (fsc/compact-shape shape)]
    (t/is (not (contains? compact :flip-x)))
    (t/is (not (contains? compact :flip-y)))))

(t/deftest generated-shapes-survive-compact-expand
  ;; Generative check over the real shape schema: expanding a compacted
  ;; shape must always yield a valid shape. Note that generated shapes
  ;; do not satisfy the cross-attr geometry invariants (selrect/points
  ;; are random, not derived), so only schema validity and the plain
  ;; geometry of non-path shapes can be asserted here; exact roundtrip
  ;; equality is covered by the directed tests above.
  (doseq [shape (sg/sample cts/schema:shape {:size 30})]
    (let [expanded (fsc/expand-shape (fsc/compact-shape shape))]
      (t/is (cts/valid-shape? (cts/create-shape expanded)))
      (when-not (contains? #{:path :bool} (:type shape))
        (t/is (= (:x shape) (:x expanded)))
        (t/is (= (:y shape) (:y expanded)))
        (t/is (= (:width shape) (:width expanded)))
        (t/is (= (:height shape) (:height expanded)))))))

(t/deftest round-values-removes-float32-artifacts
  (let [data {:opacity 0.6000000238418579
              :x 4213.60009765625
              :count 5
              :name "shape"
              :nested [{:y 0.30000001192092896}]}]
    (t/is (= {:opacity 0.6
              :x 4213.6001
              :count 5
              :name "shape"
              :nested [{:y 0.3}]}
             (fsc/round-values data)))))

(t/deftest round-values-preserves-integers-and-strings
  (let [data {:a 42 :b "0.6000000238418579" :c [1 2 3]}]
    (t/is (= data (fsc/round-values data)))))
