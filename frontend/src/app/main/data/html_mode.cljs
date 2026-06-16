;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.html-mode
  "State lifecycle and events for the HTML Mode page.

   HTML Mode is a standalone top-level mode (route `:html-mode`): it
   owns its state subtree (`:html-mode` for the fetched bundle,
   `:html-mode-local` for UI state like zoom) and fetches the same
   read-only bundle the viewer uses through the shared
   `app.main.data.view-bundle` helper — it does NOT depend on
   `app.main.data.viewer`."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]
   [app.main.data.event :as ev]
   [app.main.data.fonts :as df]
   [app.main.data.persistence :as-alias dps]
   [app.main.data.view-bundle :as dvb]
   [app.main.features :as features]
   [app.main.router :as rt]
   [app.util.globals :as ug]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; ---------------------------------------------------------------------------
;; Local state

(def ^:private default-local-state
  {:zoom 1})

;; ---------------------------------------------------------------------------
;; Bundle lifecycle

(defn bundle-fetched
  "Store a resolved view-only bundle under the `:html-mode` subtree.

   The top-level `:share-links`, `:current-team-id`, `:teams` and
   `:files` assocs mirror the viewer's `bundle-fetched` so app-wide
   helpers (dashboard navigation, the share-links dialog, library
   lookups) work identically from this mode."
  [{:keys [project file team share-links libraries users permissions] :as _bundle}]
  (ptk/reify ::bundle-fetched
    ptk/UpdateEvent
    (update [_ state]
      (let [team-id (:id team)
            team    (assoc team :members users)
            pages   (dvb/decorate-pages file)]
        (-> state
            (assoc :share-links share-links)
            (assoc :current-team-id team-id)
            (assoc :teams {team-id team})
            (assoc :files (-> (d/index-by :id libraries)
                              (assoc (:id file) file)))
            (assoc :html-mode {:libraries (d/index-by :id libraries)
                               :users (d/index-by :id users)
                               :permissions permissions
                               :project project
                               :pages pages
                               :file file}))))))

(defn- fetch-bundle
  "Fetch + resolve the bundle and emit the standard follow-up events:
   fonts registration (preview docs inline `@font-face` from the fonts
   registry), feature initialization, and `bundle-fetched`."
  [{:keys [file-id share-id]}]
  (->> (dvb/fetch {:file-id file-id :share-id share-id})
       (rx/mapcat
        (fn [{:keys [fonts team] :as bundle}]
          (rx/of (df/fonts-fetched fonts)
                 (features/initialize (:features team))
                 (bundle-fetched bundle))))))

(def ^:private schema:initialize
  [:map {:title "initialize"}
   [:file-id ::sm/uuid]
   [:share-id {:optional true} [:maybe ::sm/uuid]]
   [:page-id {:optional true} ::sm/uuid]])

(defn initialize
  "Initialize the HTML Mode page state and fetch the file bundle."
  [{:keys [file-id share-id] :as params}]
  (dm/assert!
   "expected valid params"
   (sm/check schema:initialize params))

  (ptk/reify ::initialize
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc :current-file-id file-id)
          (assoc :current-share-id share-id)
          (update :html-mode-local #(or % default-local-state))
          (assoc-in [:html-mode-local :share-id] share-id)))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/merge
       (fetch-bundle params)
       (when (some? share-id)
         (rx/of (ev/event {::ev/name "shared-html-mode-visited"})))))

    ptk/EffectEvent
    (effect [_ _ _]
      ;; Named window: opening the same file's HTML Mode twice focuses
      ;; the already-open tab instead of spawning a new one.
      (unchecked-set ug/global "name" (dm/str "html-mode-" file-id)))))

(defn finalize
  []
  (ptk/reify ::finalize
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (dissoc :html-mode)
          (dissoc :html-mode-local)))))

(defn refresh-bundle
  "Re-fetch the file bundle for the current HTML Mode session so the
   preview reflects any workspace edits that landed since the window
   was opened. Reads the active file-id / share-id from state so
   callers don't have to thread them through."
  []
  (ptk/reify ::refresh-bundle
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id  (:current-file-id state)
            share-id (dm/get-in state [:html-mode-local :share-id])]
        (when (uuid? file-id)
          (fetch-bundle (cond-> {:file-id file-id}
                          (uuid? share-id) (assoc :share-id share-id))))))))

(defn set-busy
  "Mirror the section's conversion/loading status into mode-local state.
   The Refresh button now lives in the page header (next to the file
   breadcrumb), outside the section that owns the render status — this
   flag lets the header drive the button's in-flight spinner + disabled
   state without lifting the whole section state machine."
  [busy?]
  (ptk/reify ::set-busy
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:html-mode-local :busy?] (boolean busy?)))))

;; ---------------------------------------------------------------------------
;; Zoom
;;
;; Same clamps as the viewer's zoom events, but over the mode's own
;; local state — HTML Mode zoom must never leak into (or read from)
;; the SVG viewer.

(def increase-zoom
  (ptk/reify ::increase-zoom
    ptk/UpdateEvent
    (update [_ state]
      (let [increase #(min (* % 1.3) 200)]
        (update-in state [:html-mode-local :zoom] (fnil increase 1))))))

(def decrease-zoom
  (ptk/reify ::decrease-zoom
    ptk/UpdateEvent
    (update [_ state]
      (let [decrease #(max (/ % 1.3) 0.01)]
        (update-in state [:html-mode-local :zoom] (fnil decrease 1))))))

(def reset-zoom
  (ptk/reify ::reset-zoom
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:html-mode-local :zoom] 1))))

;; ---------------------------------------------------------------------------
;; In-mode navigation (all against the `:html-mode` route)

(defn go-to-page
  "Switch the active page. Drops the frame selection — frames belong
   to a page."
  [page-id]
  (ptk/reify ::go-to-page
    ptk/WatchEvent
    (watch [_ state _]
      (let [params (-> (rt/get-params state)
                       (assoc :page-id page-id)
                       (dissoc :frame-id :index))]
        (rx/of (rt/nav :html-mode params))))))

(defn go-to-frame
  "Select a prototype board by id (replaces the viewer's `?index=`
   pagination)."
  [frame-id]
  (ptk/reify ::go-to-frame
    ptk/WatchEvent
    (watch [_ state _]
      (let [params (-> (rt/get-params state)
                       (assoc :frame-id (dm/str frame-id))
                       (dissoc :index))]
        (rx/of (rt/nav :html-mode params))))))

(defn go-to-mode
  "Switch the active section (workspace | prototype | design-tokens |
   components)."
  [mode]
  (ptk/reify ::go-to-mode
    ptk/WatchEvent
    (watch [_ state _]
      (let [params (-> (rt/get-params state)
                       (assoc :mode (name mode)))]
        (rx/of (rt/nav :html-mode params))))))

;; ---------------------------------------------------------------------------
;; Entry point (from the workspace toolbar)

(defn go-to-html-mode
  "Persist the current file and open HTML Mode in a new window."
  [& {:keys [file-id page-id frame-id] :as options}]
  (ptk/reify ::go-to-html-mode
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id (or page-id (:current-page-id state))
            file-id (or file-id (:current-file-id state))
            params  (-> {:file-id file-id
                         :page-id page-id
                         :frame-id frame-id}
                        (d/without-nils))
            window  (dm/str "html-mode-" file-id)
            options (merge {::rt/new-window true
                            ::rt/window-name window}
                           options)]
        (rx/of ::dps/force-persist
               (rt/nav :html-mode params options))))))

;; ---------------------------------------------------------------------------
;; Legacy (removed in the standalone flip)
;;
;; `refresh-viewer-bundle` kept the viewer-embedded HTML Mode in sync
;; by re-running `dv/initialize`. The standalone page uses
;; `refresh-bundle` above; the viewer-embedded section is rewired in
;; the same change-set, so no compatibility shim is needed here.

(defn parse-mode
  "Parse the `?mode=` query param into the section keyword. Defaults
   to `:workspace`."
  [s]
  (case s
    "prototype"     :prototype
    "design-tokens" :design-tokens
    "components"    :components
    :workspace))

(defn parse-uuid-param
  "Parse an optional uuid-typed query param. Returns nil on missing or
   malformed input."
  [v]
  (some-> v uuid/parse*))
