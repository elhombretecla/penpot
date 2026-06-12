;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.html-mode.sidebar
  "Dev sidebar for HTML Mode.

   Renders the CSS declarations, applied tokens, and asset references
   of the shape the user clicked on in the preview iframe. The visual
   shell mirrors the viewer's Inspect sidebar
   (`app.main.ui.inspect.right-sidebar`): same shape-info header, same
   disclosure pattern for collapsible panels, and the same
   `properties-row*` component for key/value pairs — so HTML Mode and
   Inspect feel like sibling surfaces.

   Pure CSS-string parsing lives in
   `app.main.data.html-mode.style-parse` so it stays unit-testable
   without React dependencies."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.variant :as cfv]
   [app.common.types.component :as ctc]
   [app.common.types.components-list :as ctcl]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.exports.assets :as de]
   [app.main.data.html-mode.style-parse :as sp]
   [app.main.data.modal :as modal]
   [app.main.render :as render]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.html-mode.refs :as hrefs]
   [app.main.ui.inspect.attributes.common :as cmm]
   [app.util.clipboard :as clipboard]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.shape-icon :as usi]
   [app.util.timers :as tm]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; ---------------------------------------------------------------------------
;; Section labels

(def ^:private section->i18n-key
  {:position   "viewer.html-mode.sidebar.section.position"
   :size       "viewer.html-mode.sidebar.section.size"
   :layout     "viewer.html-mode.sidebar.section.layout"
   :visual     "viewer.html-mode.sidebar.section.visual"
   :typography "viewer.html-mode.sidebar.section.typography"
   :other      "viewer.html-mode.sidebar.section.other"})

;; ---------------------------------------------------------------------------
;; Format picker
;;
;; Backs the small "HEX · PX" trigger in the Styles header. The popover
;; lets the user swap between color profiles (hex/rgb/hsl/oklch) and
;; length units (px/rem/em); the choice flows down into every CSS value
;; the sidebar renders.

(def ^:private color-formats [:hex :rgb :hsl :oklch])
(def ^:private unit-formats  [:px :rem :em])

(mf/defc segmented-row*
  "Tiny segmented control used inside the format popover."
  [{:keys [label options selected on-change]}]
  [:div {:class (stl/css :segmented-row)}
   [:span {:class (stl/css :segmented-label)} label]
   [:div {:role "radiogroup"
          :class (stl/css :segmented-options)}
    (for [opt options]
      (let [opt-name (name opt)
            active? (= opt selected)]
        [:button
         {:key opt-name
          :type "button"
          :role "radio"
          :aria-checked active?
          :data-no-close "true"
          :data-value opt-name
          :class (stl/css-case
                  :segmented-option true
                  :segmented-option-active active?)
          :on-click #(on-change opt)}
         opt-name]))]])

(mf/defc format-picker*
  [{:keys [color unit on-change]}]
  (let [open* (mf/use-state false)
        open (deref open*)
        trigger-ref (mf/use-ref nil)
        toggle (mf/use-fn (fn [] (swap! open* not)))
        close (mf/use-fn (fn [] (reset! open* false)))
        change-color
        (mf/use-fn
         (mf/deps unit on-change)
         (fn [v] (on-change {:color v :unit unit})))
        change-unit
        (mf/use-fn
         (mf/deps color on-change)
         (fn [v] (on-change {:color color :unit v})))]
    [:div {:class (stl/css :format-picker-wrapper) :ref trigger-ref}
     [:button {:type "button"
               :class (stl/css :format-picker-trigger)
               :aria-haspopup "dialog"
               :aria-expanded open
               :aria-label (tr "viewer.html-mode.sidebar.format.aria")
               :on-click toggle}
      [:> icon* {:icon-id i/filter :size "s"
                 :class (stl/css :format-picker-icon)}]
      [:span {:class (stl/css :format-picker-tag)}
       (str/upper (name color))]
      [:span {:class (stl/css :format-picker-sep)} "·"]
      [:span {:class (stl/css :format-picker-tag)}
       (str/upper (name unit))]]
     [:& dropdown {:show open :on-close close :container trigger-ref}
      [:div {:class (stl/css :format-popover)
             :data-no-close "true"
             :role "dialog"}
       [:> segmented-row*
        {:label (tr "viewer.html-mode.sidebar.format.color")
         :options color-formats
         :selected color
         :on-change change-color}]
       [:> segmented-row*
        {:label (tr "viewer.html-mode.sidebar.format.unit")
         :options unit-formats
         :selected unit
         :on-change change-unit}]]]]))

;; ---------------------------------------------------------------------------
;; Shape lookup

(defn- safe-parse-uuid
  [s]
  (try (uuid/parse s) (catch :default _ nil)))

(defn- lookup-shape
  [page id-str]
  (when (and page id-str)
    (when-let [id (safe-parse-uuid id-str)]
      (get-in page [:objects id]))))

(defn- shape-icon-id
  "Pick the icon for the shape header. Prefers the resolved CLJS shape
   (so we get accurate variants like flex-horizontal / flex-grid for
   frames), falling back to a coarse mapping on the raw shape-type
   string posted from the iframe."
  [shape shape-type-str]
  (or (when shape (usi/get-shape-icon shape))
      (case shape-type-str
        "frame"   "board"
        "image"   "img"
        "text"    "text"
        "group"   "group"
        "rect"    "rectangle"
        "circle"  "elipse"
        "path"    "path"
        "bool"    "boolean-union"
        "svg-raw" "img"
        "board")))

;; ---------------------------------------------------------------------------
;; Disclosure (collapsible section) — visually identical to
;; `app.main.ui.inspect.styles.style-box` but accepts a free-form
;; title rather than the inspect panel keyword.

(mf/defc section-disclosure*
  [{:keys [title testid action children]}]
  (let [expanded* (mf/use-state true)
        expanded (deref expanded*)
        toggle
        (mf/use-fn
         (mf/deps expanded)
         (fn [] (reset! expanded* (not expanded))))]
    [:article {:class (stl/css :section)
               :data-testid testid}
     [:header {:class (stl/css :section-header)}
      [:button {:type "button"
                :class (stl/css :disclosure-button)
                :aria-expanded expanded
                :on-click toggle}
       [:> icon* {:icon-id (if expanded i/arrow-down i/arrow)
                  :class (stl/css :disclosure-icon)
                  :size "s"}]]
      [:span {:class (stl/css :section-title)} title]
      (when action
        [:div {:class (stl/css :section-action)} action])]
     (when expanded
       [:div {:class (stl/css :section-content)} children])]))

;; ---------------------------------------------------------------------------
;; Box model
;;
;; Mirrors the browser-devtools box-model diagram: nested boxes (margin
;; → border → padding → content) with the numeric value of each side
;; in its outer cell. Numbers come from `sp/extract-box-model`, which
;; reads the inline style for margin/border/padding shorthands and
;; per-side declarations, and falls back to the shape's intrinsic
;; width/height for the content cell.

(mf/defc box-cell*
  [{:keys [class value]}]
  [:div {:class [(stl/css :box-cell) class]} (sp/fmt-length value)])

(mf/defc box-model*
  [{:keys [box]}]
  (let [{:keys [margin border padding content]} box
        {mt :top mr :right mb :bottom ml :left} margin
        {bt :top br :right bb :bottom bl :left} border
        {pt :top pr :right pb :bottom pl :left} padding
        {cw :width ch :height} content]
    [:section {:class (stl/css :box-model)
               :data-testid "html-mode-box-model"
               :aria-label (tr "viewer.html-mode.sidebar.box-model.aria")}
     [:div {:class (stl/css :box-margin)}
      [:span {:class (stl/css :box-tag)} (tr "viewer.html-mode.sidebar.box-model.margin")]
      [:> box-cell* {:class (stl/css :box-cell-top) :value mt}]
      [:> box-cell* {:class (stl/css :box-cell-right) :value mr}]
      [:> box-cell* {:class (stl/css :box-cell-bottom) :value mb}]
      [:> box-cell* {:class (stl/css :box-cell-left) :value ml}]
      [:div {:class (stl/css :box-border)}
       [:span {:class (stl/css :box-tag)} (tr "viewer.html-mode.sidebar.box-model.border")]
       [:> box-cell* {:class (stl/css :box-cell-top) :value bt}]
       [:> box-cell* {:class (stl/css :box-cell-right) :value br}]
       [:> box-cell* {:class (stl/css :box-cell-bottom) :value bb}]
       [:> box-cell* {:class (stl/css :box-cell-left) :value bl}]
       [:div {:class (stl/css :box-padding)}
        [:span {:class (stl/css :box-tag)} (tr "viewer.html-mode.sidebar.box-model.padding")]
        [:> box-cell* {:class (stl/css :box-cell-top) :value pt}]
        [:> box-cell* {:class (stl/css :box-cell-right) :value pr}]
        [:> box-cell* {:class (stl/css :box-cell-bottom) :value pb}]
        [:> box-cell* {:class (stl/css :box-cell-left) :value pl}]
        [:div {:class (stl/css :box-content)}
         (sp/fmt-length cw) " × " (sp/fmt-length ch)]]]]]))

;; ---------------------------------------------------------------------------
;; Style row
;;
;; Renders a single CSS declaration the way browser devtools does:
;; `<property>: <value>;` styled as code, with the bullet swatch for
;; hex colours embedded inline so the value stays scannable.

(def ^:private color-prop?
  ;; Properties whose values typically contain a hex color we want to
  ;; render with a swatch beside the value. Anything else falls through
  ;; to the plain text rendering.
  #{"color" "background" "background-color" "fill" "stroke"
    "border" "border-top" "border-right" "border-bottom" "border-left"
    "border-color" "outline" "outline-color"
    "box-shadow" "text-decoration-color" "caret-color"
    "column-rule" "column-rule-color"})

(defn- extract-hex
  "Return the first hex color literal inside a CSS value, or nil."
  [value]
  (when value
    (when-let [m (re-find #"#[0-9a-fA-F]{8}|#[0-9a-fA-F]{6}|#[0-9a-fA-F]{4}|#[0-9a-fA-F]{3}" value)]
      m)))

(mf/defc style-row*
  [{:keys [prop value format]}]
  (let [rendered-value (sp/rewrite-value value format)
        swatch-hex (when (color-prop? prop) (extract-hex value))]
    [:div {:class (stl/css :style-row)}
     [:span {:class (stl/css :style-prop)} prop]
     [:span {:class (stl/css :style-colon)} ": "]
     (when swatch-hex
       [:span {:class (stl/css :style-swatch)
               :style {:background-color swatch-hex}}])
     [:span {:class (stl/css :style-value)} rendered-value]
     [:span {:class (stl/css :style-semicolon)} ";"]]))

;; ---------------------------------------------------------------------------
;; Style panel
;;
;; Groups declarations by section (POSITION / LAYOUT / VISUAL /
;; TYPOGRAPHY / OTHER) and renders each group as a code-styled list
;; of `prop: value;` rows. Values flow through the active color/unit
;; format.

(mf/defc style-panel*
  [{:keys [decls groups format]}]
  (when (seq decls)
    [:div {:class (stl/css :style-groups)}
     (for [[section pairs] groups]
       [:div {:key (name section)
              :class (stl/css :style-group)
              :data-testid (dm/str "html-mode-section-" (name section))}
        [:h4 {:class (stl/css :style-group-title)}
         (tr (get section->i18n-key section
                  "viewer.html-mode.sidebar.section.other"))]
        [:pre {:class (stl/css :style-group-pre)}
         (for [[i [prop value]] (d/enumerate pairs)]
           [:> style-row* {:key (str i "-" prop)
                           :prop prop
                           :value value
                           :format format}])]])]))

;; ---------------------------------------------------------------------------
;; Component panel
;;
;; Mirrors the workspace inspect panel for component instances. If the
;; selected shape is the head of a component instance — or the variant
;; container itself — we show:
;;   • the component's icon + name as a small header
;;   • each variant property (`type`, `state`, `show`, …) on its own
;;     row, taken from `:variant-properties` for regular instances
;;     and from `cfv/extract-properties-values` for variant containers
;;     (which expose every combination of values, joined by commas).
;;
;; The component lookup walks `libraries` keyed by file id; the local
;; file is folded into the same map by `lookup-libraries` below.

(defn- libraries-from-viewer-data
  "Build the `libraries` map the workspace inspect's `right-sidebar`
   uses for the viewer flow: take the viewer's library list and add
   the local file under its own id so a single `(get libraries file-id)`
   lookup works for components defined in either the current file or
   any of its libraries."
  [vdata]
  (let [local     (get-in vdata [:file :data])
        local-id  (get local :id)
        libraries (:libraries vdata)]
    (cond-> libraries
      (some? local-id) (assoc local-id {:id local-id :data local}))))

(defn- component-info
  "Resolve the component, its name, and the variant-property rows for
   the selected shape. Returns `nil` when the shape isn't a component
   head or a variant container.

   • Instance heads: look the component up in `libraries` keyed by
     `:component-file`, and list its `:variant-properties` (replacing
     blank values with `--`).
   • Variant containers: pull every variant's values from the local
     file's data via `cfv/extract-properties-values`, joined by commas
     so the sidebar previews the full dimension.

   This function is pure — it never touches React refs. The caller
   passes pre-resolved `libraries` and `local-data` so the hook
   dereferences stay at the top of the component."
  [shape libraries page local-data]
  (let [variant-container? (ctc/is-variant-container? shape)
        instance-head?     (ctc/instance-head? shape)]
    (when (or variant-container? instance-head?)
      (let [data (cond
                   variant-container? local-data
                   instance-head?     (dm/get-in libraries
                                                 [(:component-file shape) :data]))
            component (when (and data instance-head? (:component-id shape))
                        (ctcl/get-component data (:component-id shape) true))
            properties (cond
                         variant-container?
                         (when data
                           (->> (cfv/extract-properties-values data
                                                               (:objects page)
                                                               (:id shape))
                                (mapv #(update % :value (partial str/join ", ")))))
                         (some? component)
                         (->> (:variant-properties component)
                              (mapv #(update % :value (fn [v] (if (str/blank? v) "--" v))))))
            cname (or (:name component)
                      (:name shape))]
        (when cname
          {:name cname
           :variant? (or variant-container? (ctc/is-variant? component))
           :main?    (and instance-head? (ctc/main-instance? shape))
           :copy?    (and instance-head? (not (ctc/main-instance? shape)))
           :properties properties})))))

(mf/defc component-panel*
  [{:keys [shape page]}]
  ;; Dereference React refs unconditionally up top — if any of these
  ;; happened inside a `when` / `cond` branch React would render a
  ;; different number of hooks on toggling the branch and throw
  ;; "Rendered fewer hooks than expected".
  (let [vdata      (mf/deref hrefs/html-mode-data)
        libraries  (libraries-from-viewer-data vdata)
        local-data (get-in vdata [:file :data])
        info       (component-info shape libraries page local-data)]
    (when info
      [:section {:class (stl/css :section :section-component)
                 :data-testid "html-mode-section-component"}
       [:header {:class (stl/css :section-header :component-header)}
        [:> icon* {:icon-id (if (:variant? info) "variant" "component")
                   :class (stl/css :component-icon)
                   :size "s"}]
        [:span {:class (stl/css :section-title)}
         (tr "viewer.html-mode.sidebar.component.title")]]
       [:div {:class (stl/css :section-content :component-content)}
        [:h3 {:class (stl/css :component-name)} (:name info)]
        (when (seq (:properties info))
          [:dl {:class (stl/css :component-properties)}
           (for [property (:properties info)]
             [:div {:key (str "prop-" (:name property))
                    :class (stl/css :component-property-row)}
              [:dt {:class (stl/css :component-property-name)}
               (:name property)]
              [:dd {:class (stl/css :component-property-value)}
               (:value property)]])])]])))

;; ---------------------------------------------------------------------------
;; Tokens panel

(mf/defc tokens-panel*
  [{:keys [shape]}]
  (let [tokens (get shape :applied-tokens)]
    (when (seq tokens)
      [:> section-disclosure*
       {:testid "html-mode-section-tokens"
        :title (tr "viewer.html-mode.sidebar.tokens.title")}
       [:dl {:class (stl/css :token-list)}
        (for [[k v] (sort-by (comp name first) tokens)]
          [:div {:key (name k) :class (stl/css :token-row)}
           [:dt {:class (stl/css :token-term)}
            (cmm/get-css-rule-humanized k)]
           [:dd {:class (stl/css :token-detail)} (str v)]])]])))

;; ---------------------------------------------------------------------------
;; Assets panel
;;
;; Walks the selected shape's subtree collecting two kinds of asset:
;;
;;   • Image fills — static media on Penpot's CDN; downloaded straight
;;     from `cf/resolve-file-media`, no export pipeline needed.
;;
;;   • Vector icons — path/bool/svg-raw art, downloaded as SVG through
;;     the regular export pipeline (`de/request-export`), the same route
;;     the Inspect panel uses, so we get a server-rendered SVG identical
;;     to a normal export.
;;
;; Each is rendered with a preview thumbnail plus a download button.

(defn- collect-image-fills
  "Walk a shape and its descendants, returning a vector of image fill maps.
   Each entry carries the shape id and shape name so the sidebar can
   label it sensibly."
  [shape page]
  (let [objects (get page :objects)]
    (loop [stack [shape]
           found []]
      (if (empty? stack)
        found
        (let [s         (peek stack)
              rest-st   (pop stack)
              fills     (get s :fills)
              imgs      (->> fills
                             (keep :fill-image)
                             (mapv #(assoc %
                                           :shape-id (:id s)
                                           :shape-name (:name s))))
              child-ids (get s :shapes)
              children  (when (seq child-ids)
                          (keep #(get objects %) child-ids))]
          (recur (into rest-st children)
                 (into found imgs)))))))

(mf/defc asset-row*
  [{:keys [img]}]
  (let [uri (cf/resolve-file-media img)
        name (or (:shape-name img) (:name img) (str (:id img)))
        on-download
        (mf/use-fn
         (mf/deps uri name)
         (fn [_]
           ;; Image fills are static URLs on Penpot's CDN; a regular
           ;; download anchor is enough.
           (dom/trigger-download-uri name (:mtype img "image/png") uri)))]
    [:div {:class (stl/css :asset-row)}
     [:div {:class (stl/css :asset-thumb)
            :style {:background-image (dm/str "url(\"" uri "\")")}}]
     [:div {:class (stl/css :asset-meta)}
      [:span {:class (stl/css :asset-name)} name]
      [:span {:class (stl/css :asset-type)}
       (-> (:mtype img "image")
           (str/split "/")
           first
           str/upper)]]
     [:> icon-button* {:variant "ghost"
                       :icon i/download
                       :aria-label (tr "viewer.html-mode.sidebar.assets.download")
                       :on-click on-download}]]))

;; --- Icon detection -------------------------------------------------------
;;
;; An icon is vector art: a single path is the simplest case, but icons
;; are commonly built from several paths sitting inside a group or board.
;; In that case the useful asset is the SVG of the *whole set*, not each
;; path on its own — so a group whose paths are its direct content reads
;; as one icon. But we always prefer the *innermost* such grouping: when
;; a vector group merely wraps a deeper icon group, we drill down to the
;; inner one instead of offering the outer hierarchy, and a container that
;; bundles several icon groups yields one entry per icon.

(def ^:private vector-leaf-types
  "Shape types that count as vector content an icon can be made of."
  #{:path :bool :svg-raw :rect :circle})

(def ^:private path-like-types
  "The strong \"this is an icon\" signal: a subtree must contain at least
   one of these to be offered as an icon (a bare rect/circle alone is
   just a box, not an icon)."
  #{:path :bool :svg-raw})

(defn- raster-fill?
  "True when the shape paints itself with an image fill — that makes it a
   raster asset (surfaced by the image list), not vector icon art."
  [shape]
  (boolean (some :fill-image (get shape :fills))))

(defn- analyze-vector
  "Classify a shape's subtree for icon detection, returning
   `{:vector? bool :path? bool}`:

   • `:vector?` — the entire subtree is pure vector art (no text, image
     or raster-filled shape anywhere in it).
   • `:path?` — the subtree holds at least one path/bool/svg-raw."
  [shape objects]
  (let [type      (get shape :type)
        child-ids (get shape :shapes)]
    (if (and (contains? #{:group :frame} type) (seq child-ids))
      (let [results (->> child-ids
                         (keep #(get objects %))
                         (mapv #(analyze-vector % objects)))]
        {:vector? (and (not (raster-fill? shape))
                       (seq results)
                       (every? :vector? results))
         :path?   (boolean (some :path? results))})
      (let [vector? (and (contains? vector-leaf-types type)
                         (not (raster-fill? shape)))]
        {:vector? vector?
         :path?   (and vector? (contains? path-like-types type))}))))

(defn- icon-container?
  "True when `shape` is a group/board whose whole subtree is vector art
   with at least one path — i.e. it reads as one self-contained icon."
  [shape objects]
  (and (contains? #{:group :frame} (get shape :type))
       (let [{:keys [vector? path?]} (analyze-vector shape objects)]
         (and vector? path?))))

(defn- collect-icon-shapes
  "Walk the selected shape's subtree top-down, returning the shapes that
   represent downloadable SVG icons.

   An icon is the *innermost* group/board (or lone path) that holds
   paths: a group whose direct children are paths collapses into a single
   icon (one SVG of the whole set) rather than one download per path —
   the common \"icon made of several paths\" case. But when a vector
   group merely *wraps* one or more deeper icon groups, we skip the
   wrapper and keep descending, so we return the tightest grouping of
   paths instead of an outer hierarchy (and a board bundling several icon
   groups yields one entry per icon). Stray paths sitting beside an inner
   icon group — or among non-vector siblings — are offered on their own,
   and containers with mixed content are descended into so nested icons
   are still found."
  [shape objects]
  (let [child-shapes (keep #(get objects %) (get shape :shapes))
        {:keys [vector? path?]} (analyze-vector shape objects)]
    (if (and vector? path?
             (not-any? #(icon-container? % objects) child-shapes))
      ;; Innermost vector+path grouping (or a lone path): this is the icon.
      [shape]
      ;; Either not vector art here, or it just wraps deeper icon groups —
      ;; descend to find the tightest path groupings.
      (into [] (mapcat #(collect-icon-shapes % objects)) child-shapes))))

(mf/defc icon-asset-row*
  [{:keys [shape page file]}]
  (let [objects (get page :objects)
        name    (or (:name shape) (str (:id shape)))
        on-download
        (mf/use-fn
         (mf/deps shape page file)
         (fn [_]
           ;; Vector icons have no standalone media id, so we render them
           ;; to SVG through the regular export pipeline — the same path
           ;; the Inspect panel uses — which also handles the download.
           (st/emit!
            (de/request-export
             {:exports [{:type :svg
                         :suffix ""
                         :scale 1
                         :page-id (:id page)
                         :file-id (:id file)
                         :name name
                         :object-id (:id shape)}]})
            (de/export-shapes-event [{:type :svg}] "html-mode"))))]
    [:div {:class (stl/css :asset-row)}
     [:div {:class (stl/css :asset-thumb :asset-thumb-icon)}
      [:& render/frame-svg {:frame shape
                            :objects objects
                            :use-thumbnails false
                            :background-color "transparent"}]]
     [:div {:class (stl/css :asset-meta)}
      [:span {:class (stl/css :asset-name)} name]
      [:span {:class (stl/css :asset-type)} "SVG"]]
     [:> icon-button* {:variant "ghost"
                       :icon i/download
                       :aria-label (tr "viewer.html-mode.sidebar.assets.download")
                       :on-click on-download}]]))

(mf/defc assets-panel*
  [{:keys [shape page file]}]
  (let [images (mf/with-memo [shape page]
                 (when shape (collect-image-fills shape page)))
        icons  (mf/with-memo [shape page]
                 (when shape (collect-icon-shapes shape (get page :objects))))
        total  (+ (count images) (count icons))]
    (when (pos? total)
      [:> section-disclosure*
       {:testid "html-mode-section-assets"
        :title (tr "viewer.html-mode.sidebar.assets.title")
        :action (str total)}
       [:div {:class (stl/css :asset-list)}
        (for [[idx img] (d/enumerate images)]
          [:> asset-row* {:key (str "img-" (:id img) "-" idx) :img img}])
        (for [[idx icon-shape] (d/enumerate icons)]
          [:> icon-asset-row* {:key (str "icon-" (:id icon-shape) "-" idx)
                               :shape icon-shape
                               :page page
                               :file file}])]])))

;; ---------------------------------------------------------------------------
;; Export action
;;
;; The small "</> Export" button opens the Export Shape modal — same
;; surface the design mock describes: format toggle (HTML/JSX), styling
;; toggle (CSS/Tailwind), include-data-attrs checkbox, and live code
;; blocks the user can copy to the clipboard. Live code generation
;; lives in `app.main.ui.html-mode.export-modal`.

(mf/defc export-button*
  [{:keys [shape page file]}]
  (let [on-click
        (mf/use-fn
         (mf/deps shape page file)
         (fn [_]
           (when (and shape page file)
             (st/emit! (modal/show :html-mode-export-shape
                                   {:file file :page page :shape shape})))))]
    [:button {:type "button"
              :class (stl/css :export-button)
              :on-click on-click
              :title (tr "viewer.html-mode.sidebar.export.title")
              :aria-label (tr "viewer.html-mode.sidebar.export.title")}
     [:> icon* {:icon-id i/code :size "s"}]
     [:span (tr "viewer.html-mode.sidebar.export.label")]]))

;; ---------------------------------------------------------------------------
;; Sidebar shell

(defn- shape-tag
  "Short label rendered above the layer name (e.g. `# frame`,
   `# button`). Falls back to the raw shape-type string posted from the
   iframe when the CLJS shape isn't resolved yet."
  [shape shape-type-str]
  (or (some-> (:type shape) name)
      shape-type-str
      "—"))

(mf/defc html-mode-sidebar*
  [{:keys [selected page file]}]
  (let [shape (mf/with-memo [page selected]
                (lookup-shape page (:id selected)))
        format* (mf/use-state {:color :hex :unit :px})
        format (deref format*)

        decls (mf/with-memo [selected]
                (sp/parse-declarations (:style selected)))
        groups (mf/with-memo [decls]
                 (sp/group-by-section decls))
        box (mf/with-memo [decls shape]
              (sp/extract-box-model decls shape))

        copied* (mf/use-state false)
        copied (deref copied*)
        on-copy
        (mf/use-fn
         (mf/deps decls format)
         (fn [_]
           (let [rewritten (mapv (fn [[p v]]
                                   [p (sp/rewrite-value v format)])
                                 decls)
                 text (sp/declarations->css rewritten)]
             (clipboard/to-clipboard text)
             (reset! copied* true)
             (tm/schedule 1000 #(reset! copied* false)))))

        on-format-change
        (mf/use-fn
         (fn [next] (reset! format* next)))]
    [:aside {:class (stl/css :sidebar)
             :aria-label (tr "viewer.html-mode.sidebar.aria")}
     (if (nil? selected)
       [:div {:class (stl/css :empty)}
        [:> icon* {:icon-id i/code :size "s"}]
        [:p {:class (stl/css :empty-description)}
         (tr "viewer.html-mode.sidebar.empty.description")]]

       [:div {:class (stl/css :tool-windows)}
        ;; Shape info header (tag + name + Export button)
        [:div {:class (stl/css :shape-info)}
         [:div {:class (stl/css :shape-info-meta)}
          [:div {:class (stl/css :shape-tag)}
           [:> icon* {:icon-id (shape-icon-id shape (:shape-type selected))
                      :size "s"
                      :class (stl/css :shape-tag-icon)}]
           [:span (shape-tag shape (:shape-type selected))]]
          [:div {:class (stl/css :layer-title)}
           (or (:shape-name selected)
               (:name shape)
               (:shape-type selected)
               "—")]]
         (when (and shape page file)
           [:> export-button* {:shape shape :page page :file file}])]

        ;; Scrollable content area
        [:div {:class (stl/css :inspect-content)}

         ;; BOX MODEL
         [:section {:class (stl/css :section :section-box-model)}
          [:header {:class (stl/css :section-header)}
           [:span {:class (stl/css :section-title)}
            (tr "viewer.html-mode.sidebar.box-model.title")]]
          [:div {:class (stl/css :section-content)}
           [:> box-model* {:box box}]]]

         ;; COMPONENT (only renders when shape is a component instance or variant container)
         [:> component-panel* {:shape shape :page page}]

         ;; STYLES
         [:section {:class (stl/css :section :section-styles)}
          [:header {:class (stl/css :section-header :styles-header)}
           [:span {:class (stl/css :section-title)}
            (tr "viewer.html-mode.sidebar.style.title")]
           [:div {:class (stl/css :styles-header-actions)}
            [:> format-picker* {:color (:color format)
                                :unit (:unit format)
                                :on-change on-format-change}]
            [:button {:type "button"
                      :class (stl/css-case :copy-button true
                                           :copy-button-active copied)
                      :on-click on-copy
                      :aria-label (tr "viewer.html-mode.sidebar.copy")
                      :data-active (str copied)}
             [:> icon* {:icon-id (if copied i/tick i/clipboard)
                        :size "s"}]
             [:span (if copied
                      (tr "viewer.html-mode.sidebar.copied")
                      (tr "viewer.html-mode.sidebar.copy"))]]]]
          [:div {:class (stl/css :section-content)}
           (if (seq decls)
             [:> style-panel* {:decls decls :groups groups :format format}]
             [:p {:class (stl/css :empty-description-inline)}
              (tr "viewer.html-mode.sidebar.style.empty")])]]

         ;; TOKENS + ASSETS
         [:> tokens-panel* {:shape shape}]
         [:> assets-panel* {:shape shape :page page :file file}]]])]))
