;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.html-mode.refs
  "Derived refs over the HTML Mode state subtrees (`:html-mode`,
   `:html-mode-local`). Defined inside the feature — rather than in
   `app.main.refs` — so the mode remains a fully removable surface
   (precedent: `fullscreen-ref` in the viewer header)."
  (:require
   [app.main.store :as st]
   [okulary.core :as l]))

(def html-mode-data
  "The fetched bundle: {:file :pages :project :permissions :libraries :users}."
  (l/derived :html-mode st/state))

(def html-mode-local
  "Mode-local UI state: {:zoom :share-id}."
  (l/derived :html-mode-local st/state))

(def zoom
  (l/derived (comp :zoom :html-mode-local) st/state))

(def busy?
  "True while the section is fetching / converting the preview. Drives
   the header's Refresh button spinner + disabled state (the button is
   rendered in the header, outside the section that owns the status)."
  (l/derived (comp boolean :busy? :html-mode-local) st/state))

(def workspace-bg
  "Preview background override for the Workspace tab (nil = the page's
   own background). The picker lives in the header, next to Share."
  (l/derived (comp :workspace-bg :html-mode-local) st/state))

(def components-bg
  "Preview background for the Components tab. The picker lives in the
   header, next to Share."
  (l/derived (comp :components-bg :html-mode-local) st/state))

(def device-view
  "Device-view settings for the Prototype tab. The controls live in the
   header (next to Zoom); the section reads this for board sizing, touch
   mode and the stage background."
  (l/derived (comp :device-view :html-mode-local) st/state))
