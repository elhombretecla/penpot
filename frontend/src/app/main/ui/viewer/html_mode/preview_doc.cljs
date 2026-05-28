;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.html-mode.preview-doc
  "Shared builders for the HTML document mounted into a non-interactive
   preview iframe (used by Design Tokens usage view and the Components
   tab). Kept separate from the views that consume it so we don't pull
   either of them into the other's require graph."
  (:require
   [app.main.fonts :as fonts]
   [beicon.v2.core :as rx]))

(defn page-background
  "Page canvas background colour."
  [page]
  (or (get-in page [:options :background]) "#ffffff"))

(defn page-bounds
  "Bounding rect of every top-level shape on `page`, in canvas coords.
   Returns `nil` when the page is empty. The preview iframe uses this
   to translate the converter output back to the origin so it can be
   centred inside the viewport."
  [page]
  (let [objects (:objects page)
        root    (some (fn [shape]
                        (when (and (some? shape)
                                   (= (:id shape) (:parent-id shape)))
                          shape))
                      (vals objects))
        rects   (when root
                  (->> (:shapes root)
                       (keep #(get objects %))
                       (keep :selrect)))]
    (when (seq rects)
      (let [xs  (map :x rects)
            ys  (map :y rects)
            x2s (map :x2 rects)
            y2s (map :y2 rects)
            min-x (apply min xs)
            min-y (apply min ys)]
        {:min-x  min-x
         :min-y  min-y
         :width  (- (apply max x2s) min-x)
         :height (- (apply max y2s) min-y)}))))

(defn- typography->font-ref
  [typo]
  (select-keys typo [:font-id :font-variant-id :font-weight :font-style]))

(defn- collect-font-refs
  "Union of font refs needed by the page: every text shape's runtime
   font refs PLUS every typography defined in the file library (text
   leaves that only carry a `:typography-ref-id` aren't visible to the
   per-shape walk, so we inline the library wholesale)."
  [file page]
  (let [page-refs (->> (vals (:objects page))
                       (filter #(= (:type %) :text))
                       (mapcat (comp fonts/get-content-fonts :content))
                       (into #{}))
        typos     (or (get-in file [:data :typographies])
                      (get file :typographies))
        file-refs (into #{} (keep (comp typography->font-ref second)) typos)]
    (into page-refs file-refs)))

(defn render-fonts-css-async
  "Resolve a Promise of the concatenated `@font-face` CSS the page
   needs. Routes through `render-font-styles-cached` so reopening the
   preview (or returning to it after BACK) hits an in-memory cache
   and doesn't re-fetch the font files. On error / empty refs the
   Promise resolves to an empty string — fonts are best-effort and
   must never block the preview from rendering."
  [file page]
  (let [refs (collect-font-refs file page)]
    (if (empty? refs)
      (js/Promise.resolve "")
      (js/Promise.
       (fn [resolve _]
         (->> (fonts/render-font-styles-cached refs)
              (rx/subs! resolve
                        (fn [^js err]
                          (js/console.warn "Preview font CSS failed:" err)
                          (resolve "")))))))))

(defn build-static-doc
  "Assemble the HTML document for a non-interactive preview iframe.
   Includes `@font-face` rules so text shapes render with their real
   typographies, and the `:root { --token: value }` block so the
   converter's `var(--name, fallback)` references resolve.

   Highlight + scrolling are NOT baked into the doc — the parent owns
   them via DOM mutation through the iframe ref so flipping selection
   doesn't reload the iframe (and re-fetch fonts / images)."
  [body fonts-css tokens-css page]
  (let [bg     (page-background page)
        bounds (page-bounds page)
        body   (if bounds
                 (str "<div class=\"penpot-hm-canvas\" data-bounds-x=\""
                      (:min-x bounds) "\" data-bounds-y=\"" (:min-y bounds)
                      "\" style=\"position:relative;"
                      "width:" (:width bounds) "px;height:" (:height bounds) "px;\">"
                      "<div style=\"position:absolute;inset:0;"
                      "transform:translate(" (- (:min-x bounds)) "px," (- (:min-y bounds)) "px);"
                      "transform-origin:0 0;\">"
                      body
                      "</div></div>")
                 body)]
    (str
     "<!DOCTYPE html>\n"
     "<html lang=\"en\">\n"
     "<head>\n"
     "<meta charset=\"utf-8\">\n"
     "<title>Penpot preview</title>\n"
     "<style>\n"
     (when (seq fonts-css) (str fonts-css "\n"))
     (when (seq tokens-css) (str tokens-css "\n"))
     "  *, *::before, *::after { box-sizing: border-box; }\n"
     "  html, body { margin: 0; padding: 0; }\n"
     "  p, h1, h2, h3, h4, h5, h6, ul, ol, dl, li, dd, blockquote, figure, pre { margin: 0; padding: 0; }\n"
     ;; Grid with `place-content: safe center` centres the canvas in
     ;; the viewport when it fits AND keeps the overflow reachable
     ;; when it doesn't — unlike `display:flex; justify-content:
     ;; center; align-items: center;` which makes overflowing items
     ;; unscrollable.
     "  body { min-block-size: 100vh; min-inline-size: 100vw; background: " bg "; "
     "display: grid; place-content: safe center; padding: 24px; "
     "overflow: auto; user-select: none; pointer-events: none; }\n"
     "  @keyframes penpot-token-pulse {\n"
     "    0%   { box-shadow: 0 0 0 0    rgba(140, 51, 235, 0.85); }\n"
     "    70%  { box-shadow: 0 0 0 22px rgba(140, 51, 235, 0); }\n"
     "    100% { box-shadow: 0 0 0 0    rgba(140, 51, 235, 0); }\n"
     "  }\n"
     "  /* Highlight rule is mutated from the parent via DOM access. */\n"
     "</style>\n"
     "<style id=\"penpot-static-highlight\"></style>\n"
     "</head>\n"
     "<body>\n"
     body "\n"
     "</body>\n"
     "</html>")))
