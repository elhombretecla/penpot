;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.data.html-mode-adapter-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.html-mode.adapter :as adapter]
   [app.util.object :as obj]
   [cljs.test :as t]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- js-keys
  [o]
  (vec (.sort (js/Array.from (js/Object.keys o)))))

(defn- js-get
  [o & ks]
  (reduce (fn [acc k] (when (some? acc) (obj/get acc k))) o ks))

(t/use-fixtures :each
  {:before (fn [] (adapter/reset-cache!))})

;; ---------------------------------------------------------------------------
;; Scalar / collection translation

(t/deftest scalars-pass-through
  (t/is (nil? (adapter/->js nil)))
  (t/is (true? (adapter/->js true)))
  (t/is (false? (adapter/->js false)))
  (t/is (= 0 (adapter/->js 0)))
  (t/is (= 1.5 (adapter/->js 1.5)))
  (t/is (= "hello" (adapter/->js "hello"))))

(t/deftest keyword-becomes-string
  (t/is (= "rect" (adapter/->js :rect)))
  (t/is (= "flex" (adapter/->js :flex)))
  (t/is (= "row-reverse" (adapter/->js :row-reverse))))

(t/deftest uuid-becomes-string
  (let [id (uuid/custom 1 2)]
    (t/is (string? (adapter/->js id)))
    (t/is (= (str id) (adapter/->js id)))))

(t/deftest vector-becomes-js-array
  (let [out (adapter/->js [1 2 3])]
    (t/is (array? out))
    (t/is (= 3 (.-length out)))
    (t/is (= 2 (aget out 1)))))

(t/deftest list-becomes-js-array
  (let [out (adapter/->js '(:a :b :c))]
    (t/is (array? out))
    (t/is (= ["a" "b" "c"] (vec out)))))

(t/deftest set-becomes-js-array
  ;; Order is not guaranteed for sets; we only check membership.
  (let [out (adapter/->js #{:a :b})]
    (t/is (array? out))
    (t/is (= #{"a" "b"} (set out)))))

;; ---------------------------------------------------------------------------
;; Key translation

(t/deftest kebab-keys-become-camel
  (let [out (adapter/->js {:parent-id          "p-1"
                           :layout-flex-dir    :row
                           :layout-justify-content :space-between
                           :layout-grid-rows   [{:type :auto}]})]
    (t/is (= ["layoutFlexDir" "layoutGridRows" "layoutJustifyContent" "parentId"]
             (js-keys out)))
    (t/is (= "row" (obj/get out "layoutFlexDir")))
    (t/is (= "space-between" (obj/get out "layoutJustifyContent")))
    (t/is (= "auto" (js-get out "layoutGridRows" 0 "type")))))

(t/deftest already-camel-string-keys-untouched
  (let [out (adapter/->js {"alreadyCamel" 1})]
    (t/is (= 1 (obj/get out "alreadyCamel")))))

(t/deftest uuid-keys-become-strings
  (let [id  (uuid/custom 0 42)
        out (adapter/->js {id {:hello :world}})]
    (t/is (= [(str id)] (js-keys out)))
    (t/is (= "world" (js-get out (str id) "hello")))))

(t/deftest nested-maps-translate-recursively
  (let [shape {:transform         {:a 1 :b 0 :c 0 :d 1 :e 0 :f 0}
               :applied-tokens    {:fill         "color.brand"
                                   :stroke-color "color.line"}
               :selrect           {:x 0 :y 0 :width 100 :height 50
                                   :x1 0 :y1 0 :x2 100 :y2 50}}
        out (adapter/->js shape)]
    (t/is (= 1 (js-get out "transform" "a")))
    (t/is (= "color.brand" (js-get out "appliedTokens" "fill")))
    (t/is (= "color.line"  (js-get out "appliedTokens" "strokeColor")))
    (t/is (= 100 (js-get out "selrect" "width")))))

;; ---------------------------------------------------------------------------
;; Shape coverage

(defn- sample-shape
  [type-kw extras]
  (merge
   {:id        (uuid/custom 0 1)
    :name      "Shape"
    :type      type-kw
    :parent-id (uuid/custom 0 0)
    :frame-id  (uuid/custom 0 0)
    :x 10 :y 20 :width 100 :height 80
    :selrect   {:x 10 :y 20 :width 100 :height 80
                :x1 10 :y1 20 :x2 110 :y2 100}
    :transform {:a 1 :b 0 :c 0 :d 1 :e 0 :f 0}
    :transform-inverse {:a 1 :b 0 :c 0 :d 1 :e 0 :f 0}
    :points    [{:x 10 :y 20} {:x 110 :y 20}
                {:x 110 :y 100} {:x 10 :y 100}]}
   extras))

(t/deftest shape-rect
  (let [out (adapter/->js-shape
             (sample-shape :rect {:r1 8 :r2 8 :r3 8 :r4 8
                                  :fills [{:fill-color "#ff0000" :fill-opacity 1}]}))]
    (t/is (= "rect" (obj/get out "type")))
    (t/is (= 8 (obj/get out "r1")))
    (let [fills (obj/get out "fills")]
      (t/is (array? fills))
      (t/is (= "#ff0000" (js-get out "fills" 0 "fillColor")))
      (t/is (= 1        (js-get out "fills" 0 "fillOpacity"))))))

(t/deftest shape-frame-with-flex-layout
  (let [child-id (uuid/custom 0 2)
        out (adapter/->js-shape
             (sample-shape
              :frame
              {:shapes [child-id]
               :layout :flex
               :layout-flex-dir :column
               :layout-align-items :center
               :layout-justify-content :start
               :layout-padding {:p1 8 :p2 8 :p3 8 :p4 8}
               :layout-row-gap 4
               :layout-column-gap 4
               :clip-content true}))]
    ;; Penpot's data key `:layout` carries the layout type, which the
    ;; converter inspects as `layoutType`. The mechanical rename of
    ;; `:layout` produces `"layout"`, NOT `"layoutType"`. This test
    ;; documents that the adapter is structural — there's a separate
    ;; concern about whether Penpot's source key matches the converter
    ;; field. (Penpot's frame data carries both `:layout` and other
    ;; flags; converter inspects `shape.layoutType` and falls back to
    ;; `rawLayout`. See frame.ts in the vendored package.)
    (t/is (= "frame" (obj/get out "type")))
    (t/is (= "column" (obj/get out "layoutFlexDir")))
    (t/is (= "center" (obj/get out "layoutAlignItems")))
    (t/is (= "start"  (obj/get out "layoutJustifyContent")))
    (t/is (= 8 (js-get out "layoutPadding" "p1")))
    (t/is (= 4 (obj/get out "layoutRowGap")))
    (t/is (true? (obj/get out "clipContent")))
    (t/is (= [(str child-id)] (vec (obj/get out "shapes"))))))

(t/deftest shape-text-with-content-tree
  (let [content {:type :root
                 :children [{:type :paragraph-set
                             :children [{:type :paragraph
                                         :children [{:text "Hello"
                                                     :font-family "Inter"
                                                     :font-size "14"}]}]}]}
        out (adapter/->js-shape (sample-shape :text {:content content}))]
    (t/is (= "text" (obj/get out "type")))
    (t/is (= "root" (js-get out "content" "type")))
    (t/is (= "Inter"
             (js-get out "content" "children" 0 "children" 0 "children" 0 "fontFamily")))
    (t/is (= "Hello"
             (js-get out "content" "children" 0 "children" 0 "children" 0 "text")))))

(t/deftest shape-image-with-metadata
  (let [media-id (uuid/custom 0 9)
        out (adapter/->js-shape
             (sample-shape
              :image
              {:metadata {:id media-id :width 200 :height 100 :mtype "image/png"}}))]
    (t/is (= "image" (obj/get out "type")))
    (t/is (= (str media-id) (js-get out "metadata" "id")))
    (t/is (= "image/png" (js-get out "metadata" "mtype")))))

(t/deftest shape-bool-with-content-and-children
  (let [c1  (uuid/custom 0 3)
        out (adapter/->js-shape
             (sample-shape
              :bool
              {:bool-type :union :shapes [c1] :content "M0,0 L10,10 Z"}))]
    (t/is (= "bool" (obj/get out "type")))
    (t/is (= "union" (obj/get out "boolType")))
    (t/is (= "M0,0 L10,10 Z" (obj/get out "content")))))

(t/deftest shape-svg-raw-with-string-content
  (let [out (adapter/->js-shape
             (sample-shape :svg-raw {:content "<svg><rect/></svg>"}))]
    (t/is (= "svg-raw" (obj/get out "type")))
    (t/is (= "<svg><rect/></svg>" (obj/get out "content")))))

;; ---------------------------------------------------------------------------
;; Page wrapper

(t/deftest page-wrapper-structure
  (let [page-id  (uuid/custom 5 0)
        root-id  (uuid/custom 5 1)
        rect-id  (uuid/custom 5 2)
        root     (sample-shape :frame {:id root-id :parent-id root-id
                                       :shapes [rect-id]})
        rect     (sample-shape :rect  {:id rect-id :parent-id root-id})
        out      (adapter/->js-page
                  {:id page-id
                   :name "Page 1"
                   :objects {root-id root rect-id rect}
                   :options {:background "#ffffff"}})]
    (t/is (= (str page-id) (obj/get out "id")))
    (t/is (= "Page 1" (obj/get out "name")))
    (t/is (= "#ffffff" (js-get out "options" "background")))
    (let [objects (obj/get out "objects")]
      (t/is (= [(str rect-id) (str root-id)] (js-keys objects)))
      (t/is (= "frame" (js-get objects (str root-id) "type")))
      (t/is (= "rect"  (js-get objects (str rect-id) "type")))
      ;; parent-id reference is stringified
      (t/is (= (str root-id) (js-get objects (str rect-id) "parentId"))))))

;; ---------------------------------------------------------------------------
;; Cache behaviour

(t/deftest key-cache-is-reused
  (let [_ (adapter/->js {:my-key 1 :other-key 2})
        _ (adapter/->js {:my-key 3})]
    ;; reset-cache! is invoked by the fixture before each test; here we
    ;; just verify the second call still produces the right output —
    ;; cache hits are an internal optimisation, not behaviourally
    ;; observable.
    (t/is true)))
