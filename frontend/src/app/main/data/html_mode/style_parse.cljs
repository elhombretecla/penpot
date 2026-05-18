;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.html-mode.style-parse
  "Pure helpers for parsing the inline `style=\"…\"` attribute the
   converter writes on every shape element, and for grouping the
   resulting CSS declarations into the sections the sidebar renders.

   No UI dependencies — testable from cljs.test without bringing in
   React or the clipboard helper."
  (:require
   [app.common.data :as d]
   [app.common.math :as mth]
   [app.common.types.color :as cc]
   [cuerdas.core :as str]))

;; ---------------------------------------------------------------------------
;; Declaration parsing

(defn parse-declarations
  "Split an inline `style=\"…\"` value into ordered [prop value] pairs.
   Trims whitespace and silently drops malformed entries. Only the
   first `:` in a declaration is treated as the prop/value separator
   so values containing `:` (like `url(http://…)`) survive intact."
  [style-str]
  (if (or (nil? style-str) (str/blank? style-str))
    []
    (->> (str/split style-str ";")
         (map str/trim)
         (remove str/empty?)
         (mapv (fn [decl]
                 (let [colon (str/index-of decl ":")]
                   (if (some? colon)
                     [(str/trim (subs decl 0 colon))
                      (str/trim (subs decl (inc colon)))]
                     [decl ""])))))))

;; ---------------------------------------------------------------------------
;; Section grouping

(def prop->section
  ;; Map of CSS property → declaration section. Properties that don't
  ;; match fall into :other.
  {"position"        :position
   "top"             :position
   "right"           :position
   "bottom"          :position
   "left"            :position
   "z-index"         :position
   "transform"       :position

   "width"           :size
   "height"          :size
   "min-width"       :size
   "min-height"      :size
   "max-width"       :size
   "max-height"      :size

   "display"               :layout
   "overflow"              :layout
   "flex-direction"        :layout
   "flex-wrap"             :layout
   "justify-content"       :layout
   "align-items"           :layout
   "align-content"         :layout
   "gap"                   :layout
   "row-gap"               :layout
   "column-gap"            :layout
   "flex"                  :layout
   "flex-shrink"           :layout
   "align-self"            :layout
   "justify-self"          :layout
   "grid-template-columns" :layout
   "grid-template-rows"    :layout
   "grid-row-start"        :layout
   "grid-row-end"          :layout
   "grid-column-start"     :layout
   "grid-column-end"       :layout
   "margin"                :layout
   "padding"               :layout

   "background"          :visual
   "background-color"    :visual
   "background-image"    :visual
   "background-size"     :visual
   "background-position" :visual
   "background-repeat"   :visual
   "color"               :visual
   "border"              :visual
   "border-radius"       :visual
   "box-shadow"          :visual
   "opacity"             :visual
   "filter"              :visual
   "backdrop-filter"     :visual
   "mix-blend-mode"      :visual

   "font-family"     :typography
   "font-size"       :typography
   "font-weight"     :typography
   "font-style"      :typography
   "line-height"     :typography
   "letter-spacing"  :typography
   "text-align"      :typography
   "text-transform"  :typography
   "text-decoration" :typography
   "white-space"     :typography})

(def section-order
  [:position :size :layout :visual :typography :other])

(defn section-of
  [prop]
  (get prop->section prop :other))

(defn group-by-section
  "Group a sequence of [prop value] pairs into an ordered seq of
   `[section pairs]` entries. The section order is fixed by
   `section-order`; empty sections are omitted."
  [decls]
  (let [grouped (reduce
                 (fn [acc [prop _ :as pair]]
                   (update acc (section-of prop) (fnil conj []) pair))
                 {}
                 decls)]
    (->> section-order
         (keep (fn [section]
                 (when-let [pairs (get grouped section)]
                   [section pairs]))))))

;; ---------------------------------------------------------------------------
;; Rendering back to CSS

(defn declarations->css
  "Render a `[[prop value] …]` vector back into a multi-line CSS
   string (one declaration per line, terminated with a semicolon)."
  [pairs]
  (->> pairs
       (map (fn [[p v]] (str p ": " v ";")))
       (str/join "\n")))

;; ---------------------------------------------------------------------------
;; Color format conversion
;;
;; The converter emits all colors as `#rrggbb` (or `#rrggbbaa`) hex
;; strings. The sidebar lets the user pick between `hex`, `rgb`, `hsl`
;; and `oklch`; we rewrite each hex occurrence inside a declaration
;; value to the chosen format, leaving the rest of the value untouched
;; (gradients, fallbacks inside `var(...)`, multiple stops, etc).

(def ^:private hex-pattern
  ;; Matches #rgb / #rrggbb / #rrggbbaa (and the 4-char #rgba shorthand).
  ;; Word-boundary guard via lookbehind/lookahead would be ideal but JS
  ;; regex doesn't support lookbehind in older targets, so we anchor on
  ;; the explicit `#` and rely on the trailing character class to stop.
  ;;
  ;; NOTE: the alternation is wrapped in a NON-capturing group. Cuerdas
  ;; (the `str/replace` we use below) detects capture groups and calls
  ;; the replacement fn with a `[full-match capture-1 …]` vector rather
  ;; than the bare match string — and our callback expects a string.
  #"#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{4}|[0-9a-fA-F]{3})\b")

(defn- expand-short-hex
  "Expand `#rgb` / `#rgba` shorthand to the equivalent 6/8-char form."
  [hex]
  (case (count hex)
    4 (str "#" (subs hex 1 2) (subs hex 1 2)
              (subs hex 2 3) (subs hex 2 3)
              (subs hex 3 4) (subs hex 3 4))
    5 (str "#" (subs hex 1 2) (subs hex 1 2)
              (subs hex 2 3) (subs hex 2 3)
              (subs hex 3 4) (subs hex 3 4)
              (subs hex 4 5) (subs hex 4 5))
    hex))

(defn- hex->parts
  "Split a normalized `#rrggbb`/`#rrggbbaa` string into `[hex-rgb alpha]`
   where `hex-rgb` is the leading 6-char hex and `alpha` is 0..1."
  [hex]
  (let [hex (expand-short-hex hex)]
    (if (= (count hex) 9)
      (let [base  (subs hex 0 7)
            ahex  (subs hex 7 9)
            alpha (/ (js/parseInt ahex 16) 255)]
        [base alpha])
      [hex 1])))

(defn- fmt-num
  "Trim trailing zeros from a fixed-precision float string. Used so we
   render `1` instead of `1.00` while keeping enough precision for
   meaningful fractional values."
  [n precision]
  (let [s (.toFixed (js/Number. n) precision)]
    (if (str/includes? s ".")
      (-> s
          (str/rtrim "0")
          (str/rtrim "."))
      s)))

;; sRGB -> OKLCh, after Björn Ottosson's reference implementation
;; (https://bottosson.github.io/posts/oklab/). We compute in floating
;; point and round the result for display.

(defn- srgb->linear
  [c]
  (let [c (/ c 255.0)]
    (if (<= c 0.04045)
      (/ c 12.92)
      (mth/pow (/ (+ c 0.055) 1.055) 2.4))))

(defn- linear-rgb->oklab
  [[r g b]]
  (let [l (+ (* 0.4122214708 r) (* 0.5363325363 g) (* 0.0514459929 b))
        m (+ (* 0.2119034982 r) (* 0.6806995451 g) (* 0.1073969566 b))
        s (+ (* 0.0883024619 r) (* 0.2817188376 g) (* 0.6299787005 b))
        l_ (mth/pow l (/ 1 3))
        m_ (mth/pow m (/ 1 3))
        s_ (mth/pow s (/ 1 3))]
    [(+ (* 0.2104542553 l_) (* 0.7936177850 m_) (* -0.0040720468 s_))
     (+ (* 1.9779984951 l_) (* -2.4285922050 m_) (* 0.4505937099 s_))
     (+ (* 0.0259040371 l_) (* 0.7827717662 m_) (* -0.8086757660 s_))]))

(defn- hex->oklch
  "Convert a 6-char `#rrggbb` hex string to an OKLCh triple
   `[lightness chroma hue-deg]` with display-friendly precision."
  [hex]
  (let [[r g b] (cc/hex->rgb hex)
        rl (srgb->linear r)
        gl (srgb->linear g)
        bl (srgb->linear b)
        [L a b'] (linear-rgb->oklab [rl gl bl])
        chroma (mth/sqrt (+ (* a a) (* b' b')))
        h-rad (if (and (zero? a) (zero? b')) 0 (.atan2 js/Math b' a))
        h-deg (mod (* h-rad (/ 180 js/Math.PI)) 360)]
    [L chroma h-deg]))

(defn format-color
  "Render a hex color in the requested format. `format` is one of
   `:hex`, `:rgb`, `:hsl`, `:oklch`. `hex` may carry an alpha suffix
   (`#rrggbbaa`); when present, the output uses the `a`-suffixed form
   for rgb/hsl, or `oklch(... / alpha)` for oklch."
  [hex format]
  (let [[base alpha] (hex->parts hex)]
    (case format
      :hex
      (if (< alpha 1)
        (str base (-> (js/Math.round (* alpha 255))
                      (.toString 16)
                      (str/pad {:length 2 :char "0" :type :left})))
        base)

      :rgb
      (let [[r g b] (cc/hex->rgb base)]
        (if (< alpha 1)
          (str "rgba(" r ", " g ", " b ", " (fmt-num alpha 2) ")")
          (str "rgb(" r ", " g ", " b ")")))

      :hsl
      (let [[h s l] (cc/hex->hsl base)
            h (int (mth/round h))
            s (fmt-num (* s 100) 1)
            l (fmt-num (* l 100) 1)]
        (if (< alpha 1)
          (str "hsla(" h ", " s "%, " l "%, " (fmt-num alpha 2) ")")
          (str "hsl(" h ", " s "%, " l "%)")))

      :oklch
      (let [[L c h] (hex->oklch base)
            L (fmt-num (* L 100) 2)
            c (fmt-num c 3)
            h (fmt-num h 2)]
        (if (< alpha 1)
          (str "oklch(" L "% " c " " h " / " (fmt-num alpha 2) ")")
          (str "oklch(" L "% " c " " h ")")))

      ;; Fallback: leave as-is.
      hex)))

(defn rewrite-colors
  "Rewrite every hex color literal in a CSS value string to the requested
   format. Non-hex content is preserved verbatim."
  [value format]
  (if (or (str/blank? value) (= format :hex))
    value
    (str/replace value hex-pattern
                 (fn [match]
                   (format-color match format)))))

;; ---------------------------------------------------------------------------
;; Length unit conversion

(def ^:private px-pattern
  ;; Plain (non-capturing) match. With a capture group, cuerdas would
  ;; hand the replacement fn a `[full-match capture]` vector, breaking
  ;; the `js/parseFloat` parse below.
  #"-?\d*\.?\d+px\b")

(defn convert-px
  "Convert a numeric `n` in pixels to the target unit string. `base` is
   the assumed root font-size for rem/em conversion (defaults to 16)."
  [n unit base]
  (let [base (or base 16)]
    (case unit
      :px  (str (fmt-num n 4) "px")
      :rem (str (fmt-num (/ n base) 4) "rem")
      :em  (str (fmt-num (/ n base) 4) "em")
      (str n "px"))))

(defn rewrite-units
  "Rewrite every `<n>px` length in a CSS value string to the requested
   unit. Non-length content is preserved verbatim. Properties that
   would be nonsensical in non-px units (e.g. zero values, border
   widths smaller than 1px when in rem) are left unchanged for the
   simplest, least-surprising output: 0 is unitless, anything else is
   converted by the matching `px-pattern`."
  [value unit & {:keys [base]}]
  (cond
    (str/blank? value) value
    (= unit :px) value
    :else
    (str/replace value px-pattern
                 (fn [match]
                   (let [n (js/parseFloat match)]
                     (if (zero? n)
                       "0"
                       (convert-px n unit base)))))))

;; ---------------------------------------------------------------------------
;; Combined per-declaration rewrite

(defn rewrite-value
  "Rewrite both colors and px lengths in a value according to the given
   `format` options `{:color :hex|:rgb|:hsl|:oklch, :unit :px|:rem|:em,
   :base <number>}`."
  [value {:keys [color unit base]}]
  (-> value
      (rewrite-colors (or color :hex))
      (rewrite-units  (or unit :px) :base base)))

;; ---------------------------------------------------------------------------
;; Box model extraction
;;
;; The CSS the converter emits encodes layout through inline
;; declarations. To render the browser-devtools-style box-model
;; diagram in the sidebar we collapse the inline declarations into
;; four 4-tuples (top/right/bottom/left) for margin, border and
;; padding, plus the content `width × height` from the shape itself.

(defn- to-num
  "Parse a CSS length to its numeric pixel value, dropping the unit.
   Returns nil if the value is not a plain length we can render in a
   compact cell (`auto`, `var(...)`, percentages, etc)."
  [v]
  (when (string? v)
    (let [trimmed (str/trim v)]
      (when (re-matches #"-?\d*\.?\d+(?:px)?" trimmed)
        (let [n (js/parseFloat trimmed)]
          (when-not (js/isNaN n) n))))))

(defn- parse-trbl
  "Parse a CSS shorthand value (`10px`, `10px 20px`, `10px 20px 30px`,
   `10px 20px 30px 40px`) into a `[top right bottom left]` vector of
   numbers (or `nil` when a side has no numeric value). Each value is
   passed through `to-num`, so non-numeric tokens collapse to `nil`."
  [value]
  (when (string? value)
    (let [parts (->> (str/split value #"\s+") (remove str/blank?))
          parts (mapv to-num parts)]
      (case (count parts)
        1 [(parts 0) (parts 0) (parts 0) (parts 0)]
        2 [(parts 0) (parts 1) (parts 0) (parts 1)]
        3 [(parts 0) (parts 1) (parts 2) (parts 1)]
        4 parts
        nil))))

(defn- side-value
  "Resolve the value of one side of a `box`-style property given a
   declaration map. Looks up the explicit `<box>-<side>` value first
   (e.g. `padding-top`), falling back to the shorthand `<box>`
   parsed by `parse-trbl`."
  [decls box side]
  (let [explicit (get decls (str box "-" (name side)))
        shorthand (get decls box)
        idx (case side :top 0 :right 1 :bottom 2 :left 3)]
    (or (to-num explicit)
        (some-> shorthand parse-trbl (nth idx)))))

(defn- border-width-of
  "Extract a numeric border width for a side from `border` declarations.
   Looks at `border-<side>-width`, `border-<side>` (e.g. `2px solid
   #000`), and finally the `border` shorthand. `nil` if none parses
   cleanly."
  [decls side]
  (let [side-name (name side)
        per-side-width (get decls (str "border-" side-name "-width"))
        per-side       (get decls (str "border-" side-name))
        shorthand      (get decls "border")
        width-tok      (fn [s]
                         (when (string? s)
                           (let [tokens (str/split (str/trim s) #"\s+")]
                             (some to-num tokens))))]
    (or (to-num per-side-width)
        (width-tok per-side)
        (width-tok shorthand))))

(defn extract-box-model
  "Build the box-model record from parsed declarations plus a fallback
   shape map (used to supply width/height when the inline style lacks
   them). Returns `{:margin :border :padding :content}`, each a map
   of `:top :right :bottom :left` (margin/border/padding) or
   `:width :height` (content). Numeric values may be `nil` to signal
   that the cell should render the `-` placeholder."
  [decls shape]
  (let [decls (into {} decls)
        sides [:top :right :bottom :left]
        for-each (fn [f]
                   (zipmap sides (map f sides)))
        content-w (or (to-num (get decls "width"))
                      (some-> shape :width))
        content-h (or (to-num (get decls "height"))
                      (some-> shape :height))]
    {:margin  (for-each #(side-value decls "margin" %))
     :border  (for-each #(border-width-of decls %))
     :padding (for-each #(side-value decls "padding" %))
     :content {:width content-w :height content-h}}))

;; ---------------------------------------------------------------------------
;; Display helpers

(defn fmt-length
  "Format a number for a box-model cell. Drops the `.0` suffix on whole
   numbers and renders fractional values with two decimals. Returns
   the `-` placeholder when the input is `nil`."
  [n]
  (cond
    (nil? n) "-"
    (zero? n) "0"
    (== (mth/round n) n) (str (int n))
    :else (str (d/format-number n 2))))
