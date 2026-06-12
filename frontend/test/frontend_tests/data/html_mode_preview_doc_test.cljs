;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.data.html-mode-preview-doc-test
  "Tests for the HTML-document builders behind HTML Mode's iframes —
   including the interpolation-safety guarantees: the wrapper document
   interpolates page-level data (name, background, interactions JSON)
   into markup rendered inside an `allow-same-origin` iframe, so every
   escape here is load-bearing."
  (:require
   [app.common.uuid :as uuid]
   [app.main.ui.viewer.html-mode.preview-doc :as pdoc]
   [cljs.test :as t]
   [cuerdas.core :as str]))

;; ---------------------------------------------------------------------------
;; Fixtures

(def root-id (uuid/custom 7 1))
(def rect-id (uuid/custom 7 2))
(def frame-id (uuid/custom 7 3))

(def sample-page
  {:id      (uuid/custom 7 0)
   :name    "Page 1"
   :options {:background "#fabada"}
   :objects {root-id {:id root-id :parent-id root-id :shapes [rect-id]}
             rect-id {:id rect-id :parent-id root-id
                      :selrect {:x 10 :y 20 :x2 110 :y2 220}}}})

(def empty-page
  {:id (uuid/custom 7 9) :name "Empty" :objects {}})

(def parts
  {:html       "<div data-id=\"x\">converted</div>"
   :fonts-css  "@font-face { font-family: test; }"
   :tokens-css ":root { --color-brand: #112233; }"})

;; ---------------------------------------------------------------------------
;; escape-html

(t/deftest escape-html-basics
  (t/is (= "a&amp;b" (pdoc/escape-html "a&b")))
  (t/is (= "&lt;script&gt;" (pdoc/escape-html "<script>")))
  (t/is (= "&quot;q&quot;" (pdoc/escape-html "\"q\"")))
  (t/is (= "" (pdoc/escape-html nil)))
  (t/is (= "plain" (pdoc/escape-html "plain"))))

;; ---------------------------------------------------------------------------
;; page-background sanitization

(t/deftest page-background-accepts-color-values
  (t/is (= "#fabada" (pdoc/page-background sample-page)))
  (t/is (= "rgba(0, 0, 0, 0.5)"
           (pdoc/page-background {:options {:background "rgba(0, 0, 0, 0.5)"}})))
  (t/is (= "#ffffff" (pdoc/page-background {}))))

(t/deftest page-background-rejects-style-breakouts
  ;; A value able to close the declaration or the <style> block must
  ;; fall back to the default instead of being interpolated.
  (t/is (= "#ffffff"
           (pdoc/page-background
            {:options {:background "red;}</style><script>alert(1)</script>"}})))
  (t/is (= "#ffffff"
           (pdoc/page-background {:options {:background "red} body{display:none"}}))))

;; ---------------------------------------------------------------------------
;; page-bounds

(t/deftest page-bounds-from-selrects
  (t/is (= {:min-x 10 :min-y 20 :width 100 :height 200}
           (pdoc/page-bounds sample-page)))
  (t/is (nil? (pdoc/page-bounds empty-page))))

;; ---------------------------------------------------------------------------
;; build-document (Workspace tab)

(t/deftest build-document-structure
  (let [doc (pdoc/build-document parts sample-page)]
    (t/is (str/includes? doc "<title>Page 1</title>"))
    (t/is (str/includes? doc (:html parts)))
    (t/is (str/includes? doc (:fonts-css parts)))
    (t/is (str/includes? doc (:tokens-css parts)))
    (t/is (str/includes? doc "background: #fabada"))
    ;; Bounds wrapper translates the canvas back to the origin.
    (t/is (str/includes? doc "penpot-hm-canvas"))
    (t/is (str/includes? doc "width:100px;height:200px"))
    (t/is (str/includes? doc "translate(-10px,-20px)"))
    ;; The workspace doc embeds the inspector bridge script.
    (t/is (str/includes? doc "penpot:html-mode:select"))))

(t/deftest build-document-escapes-page-name
  (let [page (assoc sample-page :name "</title><script>alert(1)</script>")
        doc  (pdoc/build-document parts page)]
    (t/is (not (str/includes? doc "<script>alert(1)</script>")))
    (t/is (str/includes? doc "&lt;/title&gt;"))))

;; ---------------------------------------------------------------------------
;; build-prototype-document (Prototype tab)

(def proto-frame
  {:id frame-id :selrect {:x 0 :y 0 :width 320 :height 480}})

(t/deftest build-prototype-document-structure
  (let [doc (pdoc/build-prototype-document
             (assoc parts :interactions {(str rect-id)
                                         [{:eventType "click"
                                           :actionType "navigate"
                                           :destination (str frame-id)}]})
             sample-page proto-frame)]
    (t/is (str/includes? doc "window.__PENPOT_INTERACTIONS__="))
    (t/is (str/includes? doc (str "window.__PENPOT_ROOT_ID__=\"" frame-id "\"")))
    ;; The board root is forced to fill the iframe body so device-view
    ;; resizing reflows without rebuilding the doc.
    (t/is (str/includes? doc (str "[data-id=\"" frame-id "\"]")))
    (t/is (str/includes? doc "background: #fabada"))
    ;; The prototype runtime script ships in the doc.
    (t/is (str/includes? doc "penpot:prototype:trigger"))))

(t/deftest build-prototype-document-transparent-bg
  (let [doc (pdoc/build-prototype-document
             parts sample-page proto-frame {:transparent-bg? true})]
    (t/is (str/includes? doc "background: transparent"))))

(t/deftest build-prototype-document-neutralizes-script-breakout
  ;; A `</script>` inside any interaction string value (the open-url
  ;; URL is user-authored) would terminate the inline <script> block —
  ;; the HTML parser knows nothing about JS string context. The builder
  ;; must escape `<` so the payload stays inert.
  (let [doc (pdoc/build-prototype-document
             (assoc parts :interactions {(str rect-id)
                                         [{:eventType "click"
                                           :actionType "open-url"
                                           :url "</script><script>alert(1)</script>"}]})
             sample-page proto-frame)]
    (t/is (not (str/includes? doc "</script><script>alert(1)")))
    (t/is (str/includes? doc "\\u003c/script>"))))

(t/deftest build-prototype-document-escapes-page-name
  (let [page (assoc sample-page :name "</title><img src=x onerror=alert(1)>")
        doc  (pdoc/build-prototype-document parts page proto-frame)]
    (t/is (not (str/includes? doc "<img src=x")))
    (t/is (str/includes? doc "&lt;/title&gt;"))))

;; ---------------------------------------------------------------------------
;; build-static-doc (Design Tokens / Components previews)

(t/deftest build-static-doc-sanitizes-background
  (let [page (assoc-in sample-page [:options :background]
                       "red;}</style><script>alert(1)</script>")
        doc  (pdoc/build-static-doc "<div>body</div>" "" "" page)]
    (t/is (not (str/includes? doc "<script>alert(1)</script>")))
    (t/is (str/includes? doc "background: #ffffff"))))
