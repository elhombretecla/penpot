;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.data.html-mode-style-parse-test
  (:require
   [app.main.data.html-mode.style-parse :as sp]
   [cljs.test :as t]))

;; ---------------------------------------------------------------------------
;; parse-declarations

(t/deftest parses-simple-declarations
  (t/is (= [["color" "red"]
            ["width" "100px"]]
           (sp/parse-declarations "color: red; width: 100px;"))))

(t/deftest accepts-trailing-or-missing-semicolons
  (t/is (= [["color" "red"]]
           (sp/parse-declarations "color: red")))
  (t/is (= [["color" "red"]]
           (sp/parse-declarations "color: red;")))
  (t/is (= [["color" "red"]
            ["width" "1px"]]
           (sp/parse-declarations "color: red ; width: 1px;"))))

(t/deftest preserves-colons-inside-values
  (t/is (= [["background-image"
             "url(http://example.com/foo.png)"]]
           (sp/parse-declarations
            "background-image: url(http://example.com/foo.png);"))))

(t/deftest handles-multi-segment-values
  (t/is (= [["box-shadow" "0 1px 2px rgba(0,0,0,0.2), 0 2px 4px rgba(0,0,0,0.1)"]]
           (sp/parse-declarations
            "box-shadow: 0 1px 2px rgba(0,0,0,0.2), 0 2px 4px rgba(0,0,0,0.1);"))))

(t/deftest empty-and-nil-input
  (t/is (= [] (sp/parse-declarations nil)))
  (t/is (= [] (sp/parse-declarations "")))
  (t/is (= [] (sp/parse-declarations "   ")))
  (t/is (= [] (sp/parse-declarations ";;;"))))

(t/deftest drops-malformed-entries-gracefully
  ;; A declaration with no colon is kept as [decl ""] — the sidebar shows
  ;; it under :other with an empty value. The point is to not blow up.
  (let [out (sp/parse-declarations "color: red; not-a-decl; width: 1px;")]
    (t/is (= 3 (count out)))
    (t/is (= ["not-a-decl" ""] (second out)))))

;; ---------------------------------------------------------------------------
;; group-by-section

(t/deftest groups-by-known-sections
  (let [decls   (sp/parse-declarations
                 "position: absolute; width: 100px; display: flex; color: red; font-size: 14px;")
        groups  (sp/group-by-section decls)
        sections (mapv first groups)]
    (t/is (= [:position :size :layout :visual :typography] sections))))

(t/deftest unknown-props-fall-into-other
  (let [groups (sp/group-by-section [["my-custom-prop" "value"]])]
    (t/is (= [[:other [["my-custom-prop" "value"]]]] groups))))

(t/deftest empty-sections-are-omitted
  (let [groups (sp/group-by-section [["color" "red"]])]
    (t/is (= [[:visual [["color" "red"]]]] groups))))

(t/deftest preserves-declaration-order-within-a-section
  (let [groups (sp/group-by-section
                [["width" "100px"]
                 ["height" "50px"]
                 ["min-width" "10px"]])]
    (t/is (= [[:size [["width" "100px"]
                      ["height" "50px"]
                      ["min-width" "10px"]]]]
             groups))))

;; ---------------------------------------------------------------------------
;; declarations->css

(t/deftest renders-one-line-per-declaration
  (t/is (= "color: red;\nwidth: 100px;"
           (sp/declarations->css [["color" "red"] ["width" "100px"]]))))

(t/deftest empty-input-renders-empty-string
  (t/is (= "" (sp/declarations->css []))))

(t/deftest round-trip-parse-then-render
  (let [original "color: red; width: 100px;"
        parsed   (sp/parse-declarations original)
        rendered (sp/declarations->css parsed)
        reparsed (sp/parse-declarations rendered)]
    (t/is (= parsed reparsed))))
