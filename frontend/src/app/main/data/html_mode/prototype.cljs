;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.html-mode.prototype
  "Pure helpers behind HTML Mode's prototype controller.

   Two concerns live here, both free of React / DOM / store
   dependencies so they are unit-testable from cljs.test:

   - **The postMessage bridge protocol.** `->js-interaction` /
     `->js-animation` project Penpot `:interactions` data onto the
     JSON shape injected into the prototype iframe
     (`window.__PENPOT_INTERACTIONS__`); `read-prototype-trigger` /
     `js-interaction->cljs` / `js-animation->cljs` re-hydrate the
     payload the iframe runtime posts back. `read-selected` decodes
     the workspace-mode selection messages.

   - **Controller geometry / projection helpers.** Overlay
     positioning (`compute-overlay-rect`), board lookup
     (`find-frame-by-id-str`, `board-dims`), and the WAAPI keyframe
     vocabulary (`easing->css`, `slide-axis-percent`,
     `slide-keyframes`, `push-from-keyframes`).

   The stateful orchestration (React state, WAAPI invocation, URL
   sync) stays in `app.main.ui.html-mode`."
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.bounds :as gsb]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.uuid :as uuid]
   [app.util.object :as obj]))

;; ---------------------------------------------------------------------------
;; CLJS interactions → iframe JSON payload

(defn ->js-animation
  "Project a Penpot animation map onto a JSON-serialisable shape the
   iframe runtime understands. Keys are camelCased and keywords are
   stringified, matching the convention used by `adapter/->js-page`."
  [animation]
  (when animation
    (let [t (some-> (:animation-type animation) name)]
      (cond-> {:type t
               :duration (:duration animation)
               :easing (some-> (:easing animation) name)}
        (= t "slide") (assoc :way (some-> (:way animation) name)
                             :direction (some-> (:direction animation) name)
                             :offsetEffect (boolean (:offset-effect animation)))
        (= t "push")  (assoc :direction (some-> (:direction animation) name))))))

(defn ->js-interaction
  "Project a Penpot interaction map onto a JSON-serialisable shape
   the iframe runtime understands. Only the fields the runtime needs
   are emitted — unused options stay in the source map."
  [interaction]
  (let [pos (:overlay-position interaction)]
    (cond-> {:eventType  (some-> (:event-type interaction) name)
             :actionType (some-> (:action-type interaction) name)}
      (:destination interaction)        (assoc :destination (str (:destination interaction)))
      (:delay interaction)              (assoc :delay (:delay interaction))
      (:preserve-scroll interaction)    (assoc :preserveScroll (boolean (:preserve-scroll interaction)))
      (:url interaction)                (assoc :url (:url interaction))
      pos                               (assoc :overlayPosition {:x (:x pos) :y (:y pos)})
      (:overlay-pos-type interaction)   (assoc :overlayPosType (name (:overlay-pos-type interaction)))
      (some? (:close-click-outside interaction)) (assoc :closeClickOutside (boolean (:close-click-outside interaction)))
      (some? (:background-overlay interaction))  (assoc :backgroundOverlay (boolean (:background-overlay interaction)))
      (:position-relative-to interaction) (assoc :positionRelativeTo (str (:position-relative-to interaction)))
      (:animation interaction)          (assoc :animation (->js-animation (:animation interaction))))))

(defn harvest-interactions
  "Walk every shape on the page and return a map
   `{shape-id-str → [interaction-payload …]}` for use by the iframe
   runtime. Shapes without interactions are omitted to keep the map
   small (the runtime checks for membership before reading)."
  [page]
  (->> (vals (:objects page))
       (keep (fn [shape]
               (let [xs (:interactions shape)]
                 (when (seq xs)
                   [(str (:id shape)) (mapv ->js-interaction xs)]))))
       (into {})))

;; ---------------------------------------------------------------------------
;; iframe JSON payload → CLJS

(defn read-selected
  "Build the CLJS selected map from an event.data JS object. Returns nil
   if the payload doesn't look like one of ours; returns the
   `::deselect` sentinel for an explicit deselection."
  [^js data]
  (when (and (some? data) (object? data))
    (let [t (obj/get data "type")]
      (case t
        "penpot:html-mode:select"
        {:id         (obj/get data "id")
         :shape-type (obj/get data "shapeType")
         :shape-name (obj/get data "shapeName")
         :style      (obj/get data "style")
         :tag        (obj/get data "tag")}

        "penpot:html-mode:deselect"
        ::deselect

        nil))))

(defn js-animation->cljs
  "Re-hydrate the JS animation payload the runtime returns into the
   keyword-flavoured CLJS shape the rest of the code expects (matches
   `app.common.types.shape.interactions/animation-types`)."
  [^js a]
  (when a
    (let [t (obj/get a "type")]
      (cond-> {:animation-type (keyword t)
               :duration       (obj/get a "duration")
               :easing         (some-> (obj/get a "easing") keyword)}
        (= t "slide")
        (assoc :way (some-> (obj/get a "way") keyword)
               :direction (some-> (obj/get a "direction") keyword)
               :offset-effect (boolean (obj/get a "offsetEffect")))
        (= t "push")
        (assoc :direction (some-> (obj/get a "direction") keyword))))))

(defn js-interaction->cljs
  "Rebuild a CLJS interaction map matching the Penpot schema from the
   JS payload the iframe forwarded. UUID-strings are parsed back into
   uuids and gpt/point is rebuilt so downstream helpers (notably
   `ctsi/calc-overlay-position`) work without surprise."
  [^js ix]
  (let [pos (obj/get ix "overlayPosition")]
    (cond-> {:event-type  (some-> (obj/get ix "eventType") keyword)
             :action-type (some-> (obj/get ix "actionType") keyword)}
      (obj/get ix "destination")
      (assoc :destination (uuid/parse* (obj/get ix "destination")))

      (obj/get ix "delay")
      (assoc :delay (obj/get ix "delay"))

      (obj/get ix "preserveScroll")
      (assoc :preserve-scroll (boolean (obj/get ix "preserveScroll")))

      (obj/get ix "url")
      (assoc :url (obj/get ix "url"))

      pos
      (assoc :overlay-position (gpt/point (obj/get pos "x") (obj/get pos "y")))

      (obj/get ix "overlayPosType")
      (assoc :overlay-pos-type (keyword (obj/get ix "overlayPosType")))

      (some? (obj/get ix "closeClickOutside"))
      (assoc :close-click-outside (boolean (obj/get ix "closeClickOutside")))

      (some? (obj/get ix "backgroundOverlay"))
      (assoc :background-overlay (boolean (obj/get ix "backgroundOverlay")))

      (obj/get ix "positionRelativeTo")
      (assoc :position-relative-to (uuid/parse* (obj/get ix "positionRelativeTo")))

      (obj/get ix "animation")
      (assoc :animation (js-animation->cljs (obj/get ix "animation"))))))

(defn read-prototype-trigger
  "Recognise a `penpot:prototype:trigger` postMessage payload from the
   prototype bridge script and return `{:source-id :interaction}` (with
   `:interaction` rebuilt as a CLJS interaction map), or `nil` if the
   message isn't one of ours."
  [^js data]
  (when (and (some? data) (object? data))
    (when (= (obj/get data "type") "penpot:prototype:trigger")
      {:source-id   (obj/get data "sourceId")
       :interaction (js-interaction->cljs (obj/get data "interaction"))})))

;; ---------------------------------------------------------------------------
;; Board lookup / geometry

(defn find-frame-by-id-str
  "Resolve a frame UUID-string to its shape map by walking the page's
   `:frames` (the top-level frame index used by viewer pagination)."
  [page id-str]
  (some (fn [f] (when (= (str (:id f)) id-str) f)) (:frames page)))

(defn board-dims
  "Return `{:width :height}` for a frame in canvas units. Used by the
   parent JSX to size the `.board-stack` wrapper that isolates the
   board from the preview pane's surrounding stage background. Falls
   back to zeros for nil so the JSX can still emit a valid style map
   (which then renders as nothing — the JSX guards on the wrapping
   `(when frame ...)` higher up)."
  [frame]
  (let [s (:selrect frame)]
    {:width  (or (:width s) (:width frame) 0)
     :height (or (:height s) (:height frame) 0)}))

(defn compute-overlay-rect
  "Compute the overlay's position and size relative to the base frame
   (the currently displayed board). Returns `{:x :y :width :height}`
   in canvas units. Mirrors the viewer's overlay positioning math at
   `frontend/src/app/main/ui/viewer.cljs:141-212` by delegating to
   `ctsi/calc-overlay-position`.

   `calc-overlay-position` positions the overlay's *bounds box* — which
   includes the padding added by shadows / blur / overflowing children
   (`gsb/get-object-bounds`), NOT the frame's `selrect`. Since we render
   the iframe at the frame's `selrect` size, we shift the result by the
   selrect's offset within the bounds box so the visible frame lands
   exactly where the SVG viewer puts it (the viewer achieves the same
   alignment via its `calculate-delta`). Without this, an overlay with a
   shadow is mis-centred by half the shadow padding."
  [page interaction source-shape base-frame dest-frame]
  (let [objects          (:objects page)
        relative-to-id   (:position-relative-to interaction)
        relative-shape   (cond
                           (some? relative-to-id) (get objects relative-to-id)
                           (= :manual (:overlay-pos-type interaction)) base-frame
                           :else source-shape)
        [pos _snap]      (ctsi/calc-overlay-position
                          interaction
                          source-shape
                          objects
                          (or relative-shape base-frame)
                          base-frame
                          dest-frame
                          (gpt/point 0 0))
        srect            (:selrect dest-frame)
        bounds           (gsb/get-object-bounds objects dest-frame)
        off-x            (- (:x srect) (:x bounds))
        off-y            (- (:y srect) (:y bounds))]
    {:x      (+ (:x pos) off-x)
     :y      (+ (:y pos) off-y)
     :width  (:width srect)
     :height (:height srect)}))

;; ---------------------------------------------------------------------------
;; WAAPI keyframe vocabulary

(defn easing->css
  "Project a Penpot easing keyword to its CSS timing-function string."
  [easing]
  (case easing
    :linear      "linear"
    :ease        "ease"
    :ease-in     "ease-in"
    :ease-out    "ease-out"
    :ease-in-out "ease-in-out"
    "ease"))

(defn slide-axis-percent
  "Translate a Penpot direction keyword into a `transform` for a 100%
   offset in that direction. `:right` means the destination starts off-
   screen to the right and slides in to the left; `:left` mirrored;
   etc. Used by both slide and push animations."
  [direction]
  (case direction
    :right "translateX(100%)"
    :left  "translateX(-100%)"
    :up    "translateY(-100%)"
    :down  "translateY(100%)"
    "translateX(100%)"))

(defn slide-keyframes
  "Build the keyframe pair for the destination iframe in a `:slide` or
   `:push` animation. Returns `[from-transform, to-transform]`."
  [direction]
  [(slide-axis-percent direction) "translate(0,0)"])

(defn push-from-keyframes
  "Keyframes for the origin iframe in a `:push` animation: slides out
   in the OPPOSITE direction of the destination's entry."
  [direction]
  ["translate(0,0)"
   (slide-axis-percent (case direction
                         :right :left
                         :left  :right
                         :up    :down
                         :down  :up))])
