;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.data.html-mode-prototype-test
  "Tests for the pure helpers behind HTML Mode's prototype controller:
   the iframe postMessage protocol (projection + re-hydration), board
   lookup, overlay geometry, and the WAAPI keyframe vocabulary."
  (:require
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.data.html-mode.prototype :as proto]
   [cljs.test :as t]))

;; ---------------------------------------------------------------------------
;; Fixtures

(def shape-id  (uuid/custom 1 1))
(def dest-id   (uuid/custom 2 2))
(def other-id  (uuid/custom 3 3))

(def slide-animation
  {:animation-type :slide
   :duration       300
   :easing         :ease-out
   :way            :in
   :direction      :right
   :offset-effect  false})

(def navigate-interaction
  {:event-type      :click
   :action-type     :navigate
   :destination     dest-id
   :preserve-scroll true
   :animation       slide-animation})

(def overlay-interaction
  {:event-type           :click
   :action-type          :open-overlay
   :destination          dest-id
   :overlay-pos-type     :manual
   :overlay-position     {:x 12 :y 34}
   :close-click-outside  true
   :background-overlay   false
   :position-relative-to other-id
   :animation            {:animation-type :dissolve
                          :duration       200
                          :easing         :linear}})

;; ---------------------------------------------------------------------------
;; CLJS → iframe JSON projection

(t/deftest project-animation-slide
  (let [out (proto/->js-animation slide-animation)]
    (t/is (= "slide" (:type out)))
    (t/is (= 300 (:duration out)))
    (t/is (= "ease-out" (:easing out)))
    (t/is (= "in" (:way out)))
    (t/is (= "right" (:direction out)))
    (t/is (false? (:offsetEffect out)))))

(t/deftest project-animation-push-carries-direction-only
  (let [out (proto/->js-animation {:animation-type :push
                                   :duration 150
                                   :easing :ease
                                   :direction :up})]
    (t/is (= "push" (:type out)))
    (t/is (= "up" (:direction out)))
    (t/is (not (contains? out :way)))
    (t/is (not (contains? out :offsetEffect)))))

(t/deftest project-animation-nil
  (t/is (nil? (proto/->js-animation nil))))

(t/deftest project-interaction-navigate
  (let [out (proto/->js-interaction navigate-interaction)]
    (t/is (= "click" (:eventType out)))
    (t/is (= "navigate" (:actionType out)))
    (t/is (= (str dest-id) (:destination out)))
    (t/is (true? (:preserveScroll out)))
    (t/is (= "slide" (get-in out [:animation :type])))))

(t/deftest project-interaction-overlay-options
  (let [out (proto/->js-interaction overlay-interaction)]
    (t/is (= "open-overlay" (:actionType out)))
    (t/is (= {:x 12 :y 34} (:overlayPosition out)))
    (t/is (= "manual" (:overlayPosType out)))
    ;; Explicit booleans must survive even when false — the runtime
    ;; distinguishes "absent" from "explicitly off".
    (t/is (true? (:closeClickOutside out)))
    (t/is (false? (:backgroundOverlay out)))
    (t/is (= (str other-id) (:positionRelativeTo out)))))

(t/deftest harvest-only-shapes-with-interactions
  (let [page {:objects {shape-id {:id shape-id
                                  :interactions [navigate-interaction]}
                        other-id {:id other-id}}}
        out  (proto/harvest-interactions page)]
    (t/is (= #{(str shape-id)} (set (keys out))))
    (t/is (vector? (get out (str shape-id))))
    (t/is (= "navigate" (-> out (get (str shape-id)) first :actionType)))))

(t/deftest harvest-empty-page
  (t/is (= {} (proto/harvest-interactions {:objects {}})))
  (t/is (= {} (proto/harvest-interactions {}))))

;; ---------------------------------------------------------------------------
;; iframe JSON → CLJS round-trip
;;
;; The bridge script forwards the injected payload verbatim, so the
;; exact contract is: `(-> ix ->js-interaction clj->js js-interaction->cljs)`
;; must rebuild the CLJS map the controller branches on.

(t/deftest roundtrip-navigate-interaction
  (let [out (-> navigate-interaction
                (proto/->js-interaction)
                (clj->js)
                (proto/js-interaction->cljs))]
    (t/is (= :click (:event-type out)))
    (t/is (= :navigate (:action-type out)))
    (t/is (= dest-id (:destination out)))
    (t/is (uuid? (:destination out)))
    (t/is (true? (:preserve-scroll out)))
    (t/is (= slide-animation (:animation out)))))

(t/deftest roundtrip-overlay-interaction
  (let [out (-> overlay-interaction
                (proto/->js-interaction)
                (clj->js)
                (proto/js-interaction->cljs))]
    (t/is (= :open-overlay (:action-type out)))
    (t/is (= :manual (:overlay-pos-type out)))
    (t/is (= 12 (get-in out [:overlay-position :x])))
    (t/is (= 34 (get-in out [:overlay-position :y])))
    (t/is (true? (:close-click-outside out)))
    (t/is (false? (:background-overlay out)))
    (t/is (= other-id (:position-relative-to out)))
    (t/is (= {:animation-type :dissolve :duration 200 :easing :linear}
             (:animation out)))))

(t/deftest roundtrip-open-url
  (let [out (-> {:event-type :click
                 :action-type :open-url
                 :url "https://example.com"}
                (proto/->js-interaction)
                (clj->js)
                (proto/js-interaction->cljs))]
    (t/is (= :open-url (:action-type out)))
    (t/is (= "https://example.com" (:url out)))))

;; ---------------------------------------------------------------------------
;; postMessage payload recognition

(t/deftest read-prototype-trigger-valid
  (let [payload #js {:type "penpot:prototype:trigger"
                     :sourceId (str shape-id)
                     :interaction (clj->js (proto/->js-interaction navigate-interaction))}
        out     (proto/read-prototype-trigger payload)]
    (t/is (some? out))
    (t/is (= (str shape-id) (:source-id out)))
    (t/is (= :navigate (get-in out [:interaction :action-type])))
    (t/is (= dest-id (get-in out [:interaction :destination])))))

(t/deftest read-prototype-trigger-rejects-foreign-payloads
  (t/is (nil? (proto/read-prototype-trigger nil)))
  (t/is (nil? (proto/read-prototype-trigger "a string")))
  (t/is (nil? (proto/read-prototype-trigger 42)))
  (t/is (nil? (proto/read-prototype-trigger #js {})))
  (t/is (nil? (proto/read-prototype-trigger #js {:type "some:other:message"}))))

(t/deftest read-selected-select
  (let [out (proto/read-selected #js {:type "penpot:html-mode:select"
                                      :id (str shape-id)
                                      :shapeType "rect"
                                      :shapeName "Button"
                                      :style "width: 10px;"
                                      :tag "div"})]
    (t/is (= {:id (str shape-id)
              :shape-type "rect"
              :shape-name "Button"
              :style "width: 10px;"
              :tag "div"}
             out))))

(t/deftest read-selected-deselect-sentinel
  (t/is (= ::proto/deselect
           (proto/read-selected #js {:type "penpot:html-mode:deselect"}))))

(t/deftest read-selected-rejects-foreign-payloads
  (t/is (nil? (proto/read-selected nil)))
  (t/is (nil? (proto/read-selected "nope")))
  (t/is (nil? (proto/read-selected #js {:type "penpot:prototype:trigger"}))))

;; ---------------------------------------------------------------------------
;; Board lookup / geometry

(t/deftest find-frame-by-id-str-test
  (let [f1   {:id shape-id :name "Board 1"}
        f2   {:id dest-id :name "Board 2"}
        page {:frames [f1 f2]}]
    (t/is (= f1 (proto/find-frame-by-id-str page (str shape-id))))
    (t/is (= f2 (proto/find-frame-by-id-str page (str dest-id))))
    (t/is (nil? (proto/find-frame-by-id-str page (str other-id))))
    (t/is (nil? (proto/find-frame-by-id-str {:frames []} (str shape-id))))))

(t/deftest board-dims-prefers-selrect
  (t/is (= {:width 320 :height 480}
           (proto/board-dims {:selrect {:width 320 :height 480}
                              :width 1 :height 1})))
  (t/is (= {:width 100 :height 200}
           (proto/board-dims {:width 100 :height 200})))
  (t/is (= {:width 0 :height 0} (proto/board-dims nil))))

(t/deftest compute-overlay-rect-manual-position
  ;; A 100×100 destination board opened manually at (40, 60) relative
  ;; to a 400×400 base board. With no shadows/blur the bounds box equals
  ;; the selrect, so the selrect offset is zero and the rect lands at
  ;; the requested manual position.
  (let [base  (cts/setup-shape {:type :frame :id shape-id
                                :x 0 :y 0 :width 400 :height 400})
        dest  (cts/setup-shape {:type :frame :id dest-id
                                :x 1000 :y 1000 :width 100 :height 100})
        src   (cts/setup-shape {:type :rect :id other-id
                                :x 10 :y 10 :width 20 :height 20})
        page  {:objects {shape-id base dest-id dest other-id src}}
        ;; Round-trip through the bridge protocol so the interaction
        ;; arrives exactly as the controller receives it (uuid
        ;; destination, gpt/point overlay-position) — `calc-overlay-
        ;; position` schema-checks both.
        ix    (-> {:event-type :click
                   :action-type :open-overlay
                   :destination dest-id
                   :overlay-pos-type :manual
                   :overlay-position {:x 40 :y 60}}
                  (proto/->js-interaction)
                  (clj->js)
                  (proto/js-interaction->cljs))
        rect  (proto/compute-overlay-rect page ix src base dest)]
    (t/is (= 100 (:width rect)))
    (t/is (= 100 (:height rect)))
    (t/is (number? (:x rect)))
    (t/is (number? (:y rect)))
    (t/is (= 40 (:x rect)))
    (t/is (= 60 (:y rect)))))

;; ---------------------------------------------------------------------------
;; WAAPI keyframe vocabulary

(t/deftest easing-projection
  (t/is (= "linear" (proto/easing->css :linear)))
  (t/is (= "ease" (proto/easing->css :ease)))
  (t/is (= "ease-in" (proto/easing->css :ease-in)))
  (t/is (= "ease-out" (proto/easing->css :ease-out)))
  (t/is (= "ease-in-out" (proto/easing->css :ease-in-out)))
  ;; Unknown / nil easings fall back to "ease".
  (t/is (= "ease" (proto/easing->css nil)))
  (t/is (= "ease" (proto/easing->css :bouncy))))

(t/deftest slide-axis-projection
  (t/is (= "translateX(100%)" (proto/slide-axis-percent :right)))
  (t/is (= "translateX(-100%)" (proto/slide-axis-percent :left)))
  (t/is (= "translateY(-100%)" (proto/slide-axis-percent :up)))
  (t/is (= "translateY(100%)" (proto/slide-axis-percent :down)))
  (t/is (= "translateX(100%)" (proto/slide-axis-percent nil))))

(t/deftest slide-keyframes-enter-from-offset
  (t/is (= ["translateX(100%)" "translate(0,0)"]
           (proto/slide-keyframes :right)))
  (t/is (= ["translateY(100%)" "translate(0,0)"]
           (proto/slide-keyframes :down))))

(t/deftest push-from-keyframes-inverts-direction
  ;; The origin board of a :push slides OUT opposite to the entry.
  (t/is (= ["translate(0,0)" "translateX(-100%)"]
           (proto/push-from-keyframes :right)))
  (t/is (= ["translate(0,0)" "translateX(100%)"]
           (proto/push-from-keyframes :left)))
  (t/is (= ["translate(0,0)" "translateY(100%)"]
           (proto/push-from-keyframes :up)))
  (t/is (= ["translate(0,0)" "translateY(-100%)"]
           (proto/push-from-keyframes :down))))
