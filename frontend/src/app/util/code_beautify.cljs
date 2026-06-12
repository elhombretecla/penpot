;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.util.code-beautify
  (:require
   ["highlight.js/lib/core$default" :as hljs]
   ["highlight.js/lib/languages/css$default" :as hljs-css]
   ["highlight.js/lib/languages/javascript$default" :as hljs-js]
   ["highlight.js/lib/languages/json$default" :as hljs-json]
   ["highlight.js/lib/languages/xml$default" :as hljs-xml]
   ["js-beautify" :as beautify]
   [cuerdas.core :as str]))

;; ---------------------------------------------------------------------------
;; Syntax highlighting
;;
;; `highlight.js` ships ~200 language grammars; we register only the three
;; we need (HTML — covered by the `xml` grammar — plus CSS and JavaScript,
;; with JSX falling back to the JS grammar). Each module's `module.exports`
;; is the bare factory / instance, so the `$default` import suffix tells
;; shadow-cljs to dereference the synthetic ES-default wrapper directly.
;; Registration is idempotent — re-evaluating this namespace at REPL time
;; is safe.

(defonce ^:private _hljs-init
  (do (.registerLanguage ^js hljs "xml"        hljs-xml)
      (.registerLanguage ^js hljs "css"        hljs-css)
      (.registerLanguage ^js hljs "javascript" hljs-js)
      (.registerLanguage ^js hljs "json"       hljs-json)
      true))

(def ^:private hljs-aliases
  {"html" "xml"
   "jsx"  "javascript"
   "js"   "javascript"
   "css"  "css"
   "json" "json"})

(defn highlight
  "Return an HTML string with `.hljs-…` token spans for `code` rendered in
   the requested language. Returns `nil` when the language isn't
   recognised so the caller can fall back to plain text."
  [code lang]
  (let [lang (cond-> lang (keyword? lang) name)
        lang (get hljs-aliases lang lang)
        opts #js {:language lang :ignoreIllegals true}]
    (try
      (.-value ^js (.highlight ^js hljs code opts))
      (catch :default _e nil))))

(defn format-html
  [data]
  (beautify/html data #js {:indent_size 2}))

(defn format-css
  [data]
  (beautify/css data #js {:indent_size 2}))

(defn format-js
  "Format a JavaScript / JSX snippet. Same indentation as the HTML
   formatter; `e4x: true` keeps JSX trees intact instead of crashing on
   `<` tokens."
  [data]
  (beautify/js data #js {:indent_size 2 :e4x true}))

(defn format-code
  [code type]
  (let [type (if (keyword? type) (name type) type)]
    (cond-> code
      (= type "svg")
      (-> (str/replace "<defs></defs>" "")
          (str/replace "><" ">\n<"))

      (or (= type "svg") (= type "html"))
      (format-html)

      (= type "css")
      (format-css)

      (or (= type "js") (= type "jsx"))
      (format-js))))

