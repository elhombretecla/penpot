;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.html-mode.converter-ctx
  "Shared builders for the JS context that
   `@penpot/html-converter` and its `shape-code` companion consume.

   Both the live HTML Mode renderer (`app.main.ui.html-mode`)
   and the Export modal (`app.main.ui.html-mode.export-modal`)
   need the same ctx — file typographies, applied tokens, and image-
   url resolution. Keeping it here avoids a circular require between
   the renderer module and the modal."
  (:require
   ["@penpot/html-converter" :as cv]
   [app.config :as cf]
   [app.main.data.html-mode.adapter :as adapter]))

(defn resolve-image-url
  "Penpot image fills are uploaded as file media and served at
   `/assets/by-file-media-id/<id>`. The converter callback only carries
   the bare id, so we reconstruct the wrapper map that
   `cf/resolve-file-media` expects."
  [id]
  (cf/resolve-file-media {:id id}))

(defn file-typographies
  "Extract the file's typography library as a plain JS object keyed by
   typography id. The converter looks leaves up by their
   `typographyRefId` and inherits the typography's font properties when
   the leaf itself doesn't override them."
  [file]
  (let [typos (or (get-in file [:data :typographies])
                  (get file :typographies))]
    (when (seq typos)
      (let [obj #js {}]
        (doseq [[id typo] typos]
          (unchecked-set obj (str id) (adapter/->js typo)))
        obj))))

(defn converter-context
  "Build the JS context the converter consumes. The base fields cover
   image-asset resolution and disable the (no-op) HTML pretty-printer.

   On top of that we thread:
   - `typographies` — the file's typography library, so leaves with a
     `:typography-ref-id` inherit their font properties.
   - `tokens` — a `Map<token-name, resolved-css-value>` built from the
     page's `appliedTokens`. The converter calls `tokenToCssVar(name,
     tokens)` which produces `var(--name, <fallback>)`; without this
     map the fallback is omitted and the browser silently resolves the
     reference to `inherit`."
  [file js-page]
  (let [base #js {:resolveImageUrl resolve-image-url
                  :format          false}]
    (when-let [typos (file-typographies file)]
      (unchecked-set base "typographies" typos))
    (when-let [tokens (some-> ^js js-page .-objects cv/extractTokens)]
      (when (pos? (.-size ^js tokens))
        (unchecked-set base "tokens" tokens)))
    base))
