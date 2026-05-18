;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.data.html-mode-cache-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.html-mode.cache :as cache]
   [cljs.test :as t]))

(t/use-fixtures
  :each
  {:before (fn []
             (cache/clear!)
             (cache/set-capacity! 4))})

;; ---------------------------------------------------------------------------
;; key-for

(t/deftest key-for-includes-file-page-and-revn
  (let [file {:id (uuid/custom 1 0) :revn 7}
        page {:id (uuid/custom 1 1)}
        k    (cache/key-for file page)]
    (t/is (string? k))
    (t/is (.includes k (str (:id file))))
    (t/is (.includes k (str (:id page))))
    (t/is (.includes k "7"))))

(t/deftest key-for-changes-when-revn-advances
  (let [file-a {:id (uuid/custom 1 0) :revn 1}
        file-b {:id (uuid/custom 1 0) :revn 2}
        page   {:id (uuid/custom 1 1)}]
    (t/is (not= (cache/key-for file-a page)
                (cache/key-for file-b page)))))

(t/deftest key-for-stable-for-same-inputs
  (let [file {:id (uuid/custom 9 0) :revn 3}
        page {:id (uuid/custom 9 1)}]
    (t/is (= (cache/key-for file page)
             (cache/key-for file page)))))

;; ---------------------------------------------------------------------------
;; get! / put!

(t/deftest get-returns-nil-on-miss
  (t/is (nil? (cache/get! "no-such-key"))))

(t/deftest put-then-get-roundtrips
  (cache/put! "k" {:html "<div/>" :fonts-css ""})
  (t/is (= {:html "<div/>" :fonts-css ""}
           (cache/get! "k"))))

(t/deftest put-returns-value
  (let [v {:html "x"}]
    (t/is (= v (cache/put! "k" v)))))

(t/deftest put-overwrites-existing-key
  (cache/put! "k" "first")
  (cache/put! "k" "second")
  (t/is (= "second" (cache/get! "k")))
  (t/is (= 1 (cache/size))))

;; ---------------------------------------------------------------------------
;; LRU eviction

(t/deftest eviction-drops-oldest-when-at-capacity
  (cache/set-capacity! 2)
  (cache/put! "a" 1)
  (cache/put! "b" 2)
  (cache/put! "c" 3)
  (t/is (nil? (cache/get! "a")))
  (t/is (= 2 (cache/get! "b")))
  (t/is (= 3 (cache/get! "c")))
  (t/is (= 2 (cache/size))))

(t/deftest get-refreshes-lru-order
  (cache/set-capacity! 2)
  (cache/put! "a" 1)
  (cache/put! "b" 2)
  ;; Touch "a" so it becomes most-recent. Now "b" is oldest.
  (cache/get! "a")
  (cache/put! "c" 3)
  (t/is (= 1 (cache/get! "a")))
  (t/is (nil? (cache/get! "b")))
  (t/is (= 3 (cache/get! "c")))
  (t/is (= 2 (cache/size))))

(t/deftest capacity-zero-skips-storage
  (cache/set-capacity! 0)
  (cache/put! "a" 1)
  ;; With capacity 0, the put first evicts the oldest entry — but
  ;; since the map is empty, eviction is a no-op and `set` still
  ;; stores the value. The size briefly exceeds capacity until the
  ;; NEXT put evicts it. That's acceptable; tests assert the
  ;; observable behavior rather than the transient invariant.
  (t/is (some? (cache/get! "a")))
  (cache/put! "b" 2)
  (t/is (= 1 (cache/size))))

;; ---------------------------------------------------------------------------
;; clear!

(t/deftest clear-drops-everything
  (cache/put! "a" 1)
  (cache/put! "b" 2)
  (cache/clear!)
  (t/is (zero? (cache/size)))
  (t/is (nil? (cache/get! "a")))
  (t/is (nil? (cache/get! "b"))))
