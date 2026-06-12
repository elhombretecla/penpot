;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.html-mode.preview-doc
  "All HTML-document assembly for HTML Mode's iframes:

   - `build-static-doc`      — non-interactive preview (Design Tokens
                               usage view, Components tab).
   - `build-document`        — Workspace tab (inspector bridge inside).
   - `build-prototype-document` — Prototype tab (interactions runtime
                               inside).

   Kept separate from the views that consume it so we don't pull any
   of them into the others' require graph. The inline JS injected into
   the interactive documents lives in
   `app.main.ui.viewer.html-mode.bridge-scripts`."
  (:require
   [app.main.fonts :as fonts]
   [app.main.ui.viewer.html-mode.bridge-scripts :as scripts]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]))

;; ---------------------------------------------------------------------------
;; Interpolation safety
;;
;; Everything interpolated into the document string that is not
;; converter output must be escaped here. The converter escapes its own
;; markup (`escapeHtml` in the vendored package), but the wrapper
;; document interpolates page-level data — the page NAME into <title>,
;; the page BACKGROUND into an inline <style> block, and the harvested
;; interactions JSON into a <script> block. Each is attacker-influenced
;; for anyone viewing a shared file, and the iframes run with
;; `allow-same-origin`, so a breakout is session XSS.

(defn escape-html
  "Minimal HTML escaping for text interpolated into the wrapper
   document's markup (e.g. the <title>)."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(def ^:private css-color-re
  ;; Hex colors, rgb()/rgba()/hsl()/oklch() functions, gradients and
  ;; plain keywords — wide enough for anything the color picker can
  ;; persist, while rejecting `;`, `{`, `}` and `<` so the value cannot
  ;; escape its declaration or the inline <style> block.
  #"(?i)^[#a-z0-9(),.%\s/-]+$")

(defn- safe-css-color
  "Return `value` when it looks like a plain CSS color value; otherwise
   `fallback`."
  [value fallback]
  (let [v (str/trim (str value))]
    (if (and (seq v) (re-matches css-color-re v)) v fallback)))

(defn page-background
  "Page canvas background colour, sanitized for interpolation into an
   inline <style> block."
  [page]
  (safe-css-color (get-in page [:options :background]) "#ffffff"))

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

(defn build-document
  "Wrap the converter body output in a minimal HTML document with reset
   CSS, the page background applied, the inline `@font-face` block, and
   the selection bridge script.

   The converter emits shapes positioned at their canvas coords, which
   typically live somewhere off-origin. We compute the page's content
   bounding box, then wrap the output in a fixed-size flex item whose
   inner contents are translated by `-(minX, minY)`. The body uses
   flexbox centering so the resulting block sits in the middle of the
   preview viewport (horizontally always; vertically when it fits)."
  [{:keys [html fonts-css tokens-css]} page]
  (let [bg     (page-background page)
        name   (escape-html (or (:name page) "Penpot HTML preview"))
        bounds (page-bounds page)
        body   (if bounds
                 (str
                  "<div class=\"penpot-hm-canvas\" style=\"position:relative;flex:none;"
                  ;; Transform origin is the canvas's own centre so the
                  ;; pan/zoom controls (see select-bridge-script) scale
                  ;; around the visible content rather than its top-left
                  ;; corner.
                  "transform-origin:50% 50%;"
                  "width:" (:width bounds) "px;height:" (:height bounds) "px;\">"
                  "<div style=\"position:absolute;inset:0;"
                  "transform:translate(" (- (:min-x bounds)) "px," (- (:min-y bounds)) "px);"
                  "transform-origin:0 0;\">"
                  html
                  "</div></div>")
                 html)]
    (str
     "<!DOCTYPE html>\n"
     "<html lang=\"en\">\n"
     "<head>\n"
     "<meta charset=\"utf-8\">\n"
     "<title>" name "</title>\n"
     "<style>\n"
     (when (seq fonts-css) (str fonts-css "\n"))
     ;; Design-token CSS custom properties: every `var(--name, fallback)`
     ;; reference the converter emits resolves against this block. Without
     ;; it, the fallback is the only colour the browser ever sees — and if
     ;; the converter wasn't given a tokens map, even the fallback is
     ;; missing and the rule degrades to `inherit`.
     (when (seq tokens-css) (str tokens-css "\n"))
     "  *, *::before, *::after { box-sizing: border-box; }\n"
     "  html, body { margin: 0; padding: 0; }\n"
     ;; Browser user-agent stylesheets give `<p>` (and the related
     ;; block elements the converter emits for text) ~1em of top and
     ;; bottom margin. The converter sets `top` and `height` on the
     ;; outer text shape based on Penpot's measured glyph rect, so
     ;; that extra margin pushes the actual glyphs below the shape
     ;; bounds — the text renders outside its bounding box. Resetting
     ;; the margins on every block element brings the line-box back
     ;; into the shape. `line-height: normal` would also work but we
     ;; keep the shape's computed line-height in place; instead we
     ;; just collapse the default block margins.
     "  p, h1, h2, h3, h4, h5, h6, ul, ol, dl, li, dd, blockquote, figure, pre { margin: 0; padding: 0; }\n"
     ;; `overflow: hidden` keeps the panned/zoomed canvas from spawning
     ;; native scrollbars; navigation is fully driven by the pan/zoom
     ;; bridge handlers below. `user-select: none` avoids text drags
     ;; while space-panning.
     "  body { min-block-size: 100vh; background: " bg "; display: flex; justify-content: center; align-items: center; padding: 24px; overflow: hidden; user-select: none; }\n"
     "</style>\n"
     "</head>\n"
     "<body>\n"
     body "\n"
     "<script>" scripts/select-bridge-script "</script>\n"
     "</body>\n"
     "</html>")))

(defn build-prototype-document
  "Wrap a single board's HTML in a minimal document with reset CSS,
   the page background, the page-scoped fonts + tokens, and the
   prototype runtime.

   The iframe IS the board: the body fills 100% × 100% of the iframe
   (which the parent sizes to the board's dimensions) and the
   converter's output is rendered directly inside the body. The
   converter renders the root shape with `position: relative` and
   `width / height` taken from the shape itself (see
   `convertShape` in
   `frontend/vendor/penpot-html-converter/src/converter/index.ts`,
   which passes `_forceRelative: true`), so the board lands at the
   body's origin naturally — no translate trick is needed (the
   workspace mode's `(-minX, -minY)` translate is for full-page
   renders that include shapes at arbitrary canvas coordinates).

   The page background paints only inside this board-sized iframe —
   the surrounding pane background is the parent's `.preview-stage`,
   which doesn't animate. This is what eliminates the background
   flicker that earlier pane-sized versions had.

   The runtime reads the harvested interactions map (injected as
   `window.__PENPOT_INTERACTIONS__`) and dispatches click / hover /
   after-delay events. Navigate / overlay actions bubble up to the
   parent via `postMessage`."
  [{:keys [html fonts-css tokens-css interactions]} page frame & [{:keys [transparent-bg?]}]]
  ;; Both boards and overlays render with a transparent body so the frame's
  ;; own (possibly rounded / partially transparent) background is the only
  ;; thing painted — otherwise the page background fills the square iframe
  ;; and leaks past the frame's border-radius at the corners (showing white).
  ;; With a transparent body the rounded corners reveal the parent
  ;; `.preview-stage` (also seen through the transparent `.board-stack`).
  (let [bg       (if transparent-bg? "transparent" (page-background page))
        title    (escape-html (or (:name page) "Penpot HTML preview"))
        ;; The JSON is inlined into a <script> block. The HTML parser
        ;; terminates that block at the FIRST `</script>` it sees — it
        ;; knows nothing about JS string context — so a `</script>` in
        ;; any string value (e.g. an open-url URL) would break out of
        ;; the block and inject markup. Escaping every `<` as `\\u003c`
        ;; keeps the JS literal semantically identical and inert.
        ix-json  (-> (.stringify js/JSON (clj->js (or interactions {})))
                     (str/replace "<" "\\u003c"))
        root-id  (str (:id frame))]
    (str
     "<!DOCTYPE html>\n"
     "<html lang=\"en\">\n"
     "<head>\n"
     "<meta charset=\"utf-8\">\n"
     "<title>" title "</title>\n"
     "<style>\n"
     (when (seq fonts-css) (str fonts-css "\n"))
     (when (seq tokens-css) (str tokens-css "\n"))
     "  *, *::before, *::after { box-sizing: border-box; }\n"
     "  html, body { margin: 0; padding: 0; inline-size: 100%; block-size: 100%; }\n"
     "  p, h1, h2, h3, h4, h5, h6, ul, ol, dl, li, dd, blockquote, figure, pre { margin: 0; padding: 0; }\n"
     "  body { background: " bg "; overflow: hidden; user-select: none; position: relative; }\n"
     ;; Device-view sizing: the converter renders the board root with its
     ;; FIXED design width/height. Forcing it to fill the body lets the
     ;; parent resize the board purely by resizing the `.board-stack`
     ;; (and thus this iframe) — responsive content reflows, no rebuild /
     ;; reload of the srcDoc needed. At the board's design size this is a
     ;; no-op (100% == the natural size).
     "  [data-id=\"" root-id "\"] { inline-size: 100% !important; block-size: 100% !important; }\n"
     ;; Touch-input simulation. The parent toggles `body.penpot-touch-mode`
     ;; (mirroring the `penpot-show-interactions` mechanism) when the user
     ;; picks the Touch interaction type. We swap the cursor for a finger-
     ;; sized translucent ring and the bridge script spawns a tap ripple on
     ;; pointerdown. `*` + `!important` so links / buttons don't restore the
     ;; pointer cursor.
     "  body.penpot-touch-mode, body.penpot-touch-mode * {\n"
     "    cursor: url(\"data:image/svg+xml,%3Csvg%20xmlns='http://www.w3.org/2000/svg'%20width='32'%20height='32'%3E%3Ccircle%20cx='16'%20cy='16'%20r='13'%20fill='white'%20fill-opacity='0.35'%20stroke='white'%20stroke-opacity='0.95'%20stroke-width='3'/%3E%3Ccircle%20cx='16'%20cy='16'%20r='13'%20fill='none'%20stroke='black'%20stroke-opacity='0.6'%20stroke-width='1.5'/%3E%3C/svg%3E\") 16 16, auto !important;\n"
     "  }\n"
     "  @keyframes penpot-tap-ripple {\n"
     "    0%   { transform: translate(-50%, -50%) scale(0.4); opacity: 0.5; }\n"
     "    100% { transform: translate(-50%, -50%) scale(1.8); opacity: 0; }\n"
     "  }\n"
     "  .penpot-tap-ripple {\n"
     "    position: fixed; inline-size: 44px; block-size: 44px; margin: 0;\n"
     "    border-radius: 50%; background: rgb(0 0 0 / 0.28); pointer-events: none;\n"
     "    z-index: 2147483647; animation: penpot-tap-ripple 0.45s ease-out forwards;\n"
     "  }\n"
     ;; On-demand highlight of every shape that carries a prototype
     ;; interaction. The parent toggles `body.penpot-show-interactions`
     ;; (for 2s) when the user clicks the pane background, and also sets
     ;; `--penpot-highlight-color` on the iframe body to the app's
     ;; current `--color-accent-primary` (purple in light theme, green
     ;; in dark theme) — the iframe is its own document so it can't
     ;; inherit the app's CSS variables, hence the explicit injection.
     ;; The fallback (#6911d4, the light-theme purple) covers the brief
     ;; window before the variable is set. The pulse + outline mirror
     ;; the design-token "used by" highlight so both read as one effect.
     ;; `[data-prototype-interactive]` is set by the bridge script's
     ;; `annotate()` on every shape with a user-triggered interaction.
     "  @keyframes penpot-prototype-pulse {\n"
     "    0%   { box-shadow: 0 0 0 0    color-mix(in srgb, var(--penpot-highlight-color, #6911d4) 85%, transparent); }\n"
     "    70%  { box-shadow: 0 0 0 22px transparent; }\n"
     "    100% { box-shadow: 0 0 0 0    transparent; }\n"
     "  }\n"
     "  body.penpot-show-interactions [data-prototype-interactive] {\n"
     "    outline: 2px solid var(--penpot-highlight-color, #6911d4) !important;\n"
     "    outline-offset: -1px;\n"
     "    animation: penpot-prototype-pulse 1.4s ease-out infinite;\n"
     "  }\n"
     "</style>\n"
     "</head>\n"
     "<body>\n"
     html "\n"
     "<script>window.__PENPOT_INTERACTIONS__=" ix-json ";"
     "window.__PENPOT_ROOT_ID__=\"" root-id "\";</script>\n"
     "<script>" scripts/prototype-bridge-script "</script>\n"
     "</body>\n"
     "</html>")))
