;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.data.html-mode-style-parse-test
  (:require
   [app.main.data.html-mode.style-parse :as sp]
   [cljs.test :as t]
   [cuerdas.core :as str]))

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

(t/deftest preserves-semicolons-inside-parentheses
  ;; Data URIs carry `;` inside `url(...)` — the splitter must not cut
  ;; the value there.
  (t/is (= [["background-image" "url(data:image/svg+xml;base64,PHN2Zz4=)"]
            ["color" "#ffffff"]]
           (sp/parse-declarations
            "background-image: url(data:image/svg+xml;base64,PHN2Zz4=); color: #ffffff;"))))

(t/deftest preserves-semicolons-inside-quoted-strings
  (t/is (= [["content" "\"a;b\""]
            ["color" "red"]]
           (sp/parse-declarations "content: \"a;b\"; color: red;")))
  (t/is (= [["content" "'x;y'"]
            ["width" "1px"]]
           (sp/parse-declarations "content: 'x;y'; width: 1px;"))))

(t/deftest quoted-string-escapes-do-not-terminate-the-string
  ;; A backslash-escaped quote inside the string must not end it — the
  ;; following `;` is still part of the value.
  (t/is (= [["content" "\"a\\\";b\""]
            ["color" "red"]]
           (sp/parse-declarations "content: \"a\\\";b\"; color: red;"))))

(t/deftest unbalanced-parens-do-not-swallow-the-rest
  ;; A stray `)` must not push depth negative and break later splits.
  (t/is (= [["x" "a)b"]
            ["color" "red"]]
           (sp/parse-declarations "x: a)b; color: red;"))))

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

;; ---------------------------------------------------------------------------
;; format-color

(t/deftest format-color-hex-low-alpha-pads-with-zero
  ;; alpha byte < 0x10 must render as a two-digit hex (`0a`), not `#000000 a`
  ;; — a `str/pad` mis-keyed with `:char` inserted a space instead of `0`.
  (t/is (= "#0000000a" (sp/format-color "#0000000a" :hex)))
  (t/is (= "#ffffff05" (sp/format-color "#ffffff05" :hex))))

;; ---------------------------------------------------------------------------
;; rewrite-colors / rewrite-units — url(...) contexts must be preserved

(t/deftest rewrite-colors-skips-url-fragment-refs
  ;; `url(#a1b2c3)` is an SVG fragment reference, not a hex colour — it
  ;; must survive a colour-format switch untouched.
  (t/is (= "fill: url(#a1b2c3)"
           (sp/rewrite-colors "fill: url(#a1b2c3)" :rgb)))
  ;; A real hex outside the url() is still converted.
  (t/is (= "color: rgb(255, 255, 255); fill: url(#a1b2c3)"
           (sp/rewrite-colors "color: #ffffff; fill: url(#a1b2c3)" :rgb))))

(t/deftest rewrite-units-skips-url-filenames
  ;; `16px` inside a filename / data URI must not be rewritten to rem.
  (t/is (= "background: url(\"icon-16px.svg\")"
           (sp/rewrite-units "background: url(\"icon-16px.svg\")" :rem)))
  ;; A real length outside the url() is still converted.
  (t/is (= "width: 1rem; background: url(\"icon-16px.svg\")"
           (sp/rewrite-units "width: 16px; background: url(\"icon-16px.svg\")" :rem))))

(t/deftest format-color-hsl-white-is-not-nan
  ;; Achromatic colors divide by zero for saturation in the HSL conversion;
  ;; the result must be `0%`, never a literal `NaN%`.
  (let [white (sp/format-color "#ffffff" :hsl)
        black (sp/format-color "#000000" :hsl)]
    (t/is (not (str/includes? white "NaN")) white)
    (t/is (not (str/includes? black "NaN")) black)
    (t/is (= "hsl(0, 0%, 100%)" white))
    (t/is (= "hsl(0, 0%, 0%)" black))))
