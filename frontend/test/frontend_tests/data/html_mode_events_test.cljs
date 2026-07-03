;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.data.html-mode-events-test
  "Tests for the standalone HTML Mode state lifecycle: initialize /
   finalize / bundle-fetched state transitions, the mode-local zoom
   events, and the query-param parsing helpers."
  (:require
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.data.html-mode :as dhtml]
   [cljs.test :as t]
   [potok.v2.core :as ptk]))

(def file-id  (uuid/custom 9 1))
(def share-id (uuid/custom 9 2))
(def page-id  (uuid/custom 9 3))
;; Real pages root their shape tree at uuid/zero — `decorate-pages`
;; (via `ctt/get-viewer-frames`) relies on it to exclude the root.
(def root-id  uuid/zero)
(def frame-id (uuid/custom 9 5))
(def team-id  (uuid/custom 9 6))
(def lib-id   (uuid/custom 9 7))

;; ---------------------------------------------------------------------------
;; initialize / finalize

(t/deftest initialize-seeds-local-state
  (let [evt    (dhtml/initialize {:file-id file-id :share-id share-id})
        result (ptk/update evt {})]
    (t/is (= file-id (:current-file-id result)))
    (t/is (= share-id (:current-share-id result)))
    (t/is (= 1 (get-in result [:html-mode-local :zoom])))
    (t/is (= share-id (get-in result [:html-mode-local :share-id])))))

(t/deftest initialize-preserves-existing-local-state
  ;; Re-initializing (e.g. a bundle refresh path re-entering) must not
  ;; reset the user's zoom.
  (let [evt    (dhtml/initialize {:file-id file-id})
        result (ptk/update evt {:html-mode-local {:zoom 2.5}})]
    (t/is (= 2.5 (get-in result [:html-mode-local :zoom])))))

(t/deftest finalize-drops-mode-state
  (let [state  {:html-mode {:file {:id file-id}}
                :html-mode-local {:zoom 2}
                :other "untouched"}
        result (ptk/update (dhtml/finalize) state)]
    (t/is (not (contains? result :html-mode)))
    (t/is (not (contains? result :html-mode-local)))
    (t/is (= "untouched" (:other result)))))

;; ---------------------------------------------------------------------------
;; zoom

(t/deftest zoom-events-are-mode-local
  ;; The HTML Mode zoom must never read from or write to the viewer's
  ;; `:viewer-local` state.
  (let [state  {:viewer-local {:zoom 4}}
        result (ptk/update dhtml/increase-zoom state)]
    (t/is (= 1.3 (get-in result [:html-mode-local :zoom])))
    (t/is (= 4 (get-in result [:viewer-local :zoom])))))

(t/deftest zoom-clamps
  (let [grown (ptk/update dhtml/increase-zoom {:html-mode-local {:zoom 199}})
        shrunk (ptk/update dhtml/decrease-zoom {:html-mode-local {:zoom 0.011}})]
    (t/is (= 200 (get-in grown [:html-mode-local :zoom])))
    (t/is (= 0.01 (get-in shrunk [:html-mode-local :zoom])))))

(t/deftest zoom-reset
  (let [result (ptk/update dhtml/reset-zoom {:html-mode-local {:zoom 3.7}})]
    (t/is (= 1 (get-in result [:html-mode-local :zoom])))))

;; ---------------------------------------------------------------------------
;; bundle-fetched

(defn- sample-bundle
  []
  (let [root  (-> (cts/setup-shape {:type :frame :id root-id
                                    :x 0 :y 0 :width 100 :height 100})
                  (assoc :parent-id root-id :frame-id root-id
                         :shapes [frame-id]))
        board (-> (cts/setup-shape {:type :frame :id frame-id :name "Board 1"
                                    :x 0 :y 0 :width 320 :height 480})
                  (assoc :parent-id root-id :frame-id root-id))
        file  {:id file-id
               :data {:pages [page-id]
                      :pages-index {page-id {:id page-id
                                             :name "Page 1"
                                             :objects {root-id root
                                                       frame-id board}}}}}]
    {:project     {:id (uuid/custom 9 8) :team-id team-id}
     :file        file
     :team        {:id team-id :name "Team"}
     :share-links [{:id share-id}]
     :libraries   [{:id lib-id}]
     :users       [{:id (uuid/custom 9 9) :name "User"}]
     :permissions {:can-edit true}}))

(t/deftest bundle-fetched-stores-mode-subtree
  (let [result (ptk/update (dhtml/bundle-fetched (sample-bundle)) {})
        mode   (:html-mode result)]
    (t/is (some? mode))
    (t/is (= file-id (get-in mode [:file :id])))
    (t/is (= team-id (get-in mode [:project :team-id])))
    (t/is (= {:can-edit true} (:permissions mode)))
    ;; Pages are decorated with the :frames vector used for board
    ;; indexing (prototype pagination / board picker).
    (let [page (get-in mode [:pages page-id])]
      (t/is (some? page))
      (t/is (vector? (:frames page)))
      (t/is (= [frame-id] (mapv :id (:frames page)))))
    ;; Libraries / users are indexed by id.
    (t/is (contains? (:libraries mode) lib-id))))

(t/deftest bundle-fetched-mirrors-top-level-keys
  ;; App-wide helpers (dashboard nav, share dialog, library lookups)
  ;; read these top-level keys; HTML Mode must populate them like the
  ;; viewer does.
  (let [result (ptk/update (dhtml/bundle-fetched (sample-bundle)) {})]
    (t/is (= team-id (:current-team-id result)))
    (t/is (= [{:id share-id}] (:share-links result)))
    (t/is (contains? (:files result) file-id))
    (t/is (contains? (:files result) lib-id))
    (t/is (contains? (:teams result) team-id))))

(t/deftest bundle-fetched-ignores-stale-revn
  ;; The Refresh button and the visibilitychange auto-refresh can both be
  ;; in flight; a slower/older response must not clobber a newer bundle
  ;; that already landed.
  (let [newer (assoc-in (sample-bundle) [:file :revn] 5)
        older (assoc-in (sample-bundle) [:file :revn] 3)
        s1    (ptk/update (dhtml/bundle-fetched newer) {})
        s2    (ptk/update (dhtml/bundle-fetched older) s1)]
    (t/is (= 5 (get-in s2 [:html-mode :file :revn])))))

(t/deftest bundle-fetched-applies-newer-revn
  (let [older (assoc-in (sample-bundle) [:file :revn] 3)
        newer (assoc-in (sample-bundle) [:file :revn] 7)
        s1    (ptk/update (dhtml/bundle-fetched older) {})
        s2    (ptk/update (dhtml/bundle-fetched newer) s1)]
    (t/is (= 7 (get-in s2 [:html-mode :file :revn])))))

(t/deftest bundle-fetched-applies-when-no-current-revn
  ;; First fetch (no revn in state yet) always applies, even revn 0.
  (let [result (ptk/update (dhtml/bundle-fetched
                            (assoc-in (sample-bundle) [:file :revn] 0)) {})]
    (t/is (some? (:html-mode result)))
    (t/is (= 0 (get-in result [:html-mode :file :revn])))))

;; ---------------------------------------------------------------------------
;; param parsing

(t/deftest parse-mode-defaults-and-values
  (t/is (= :prototype (dhtml/parse-mode "prototype")))
  (t/is (= :design-tokens (dhtml/parse-mode "design-tokens")))
  (t/is (= :components (dhtml/parse-mode "components")))
  (t/is (= :workspace (dhtml/parse-mode "workspace")))
  (t/is (= :workspace (dhtml/parse-mode nil)))
  (t/is (= :workspace (dhtml/parse-mode "garbage"))))
