;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.html-mode.semantics
  "Per-file store for HTML Mode's semantic-rule overrides.

   A rule teaches the code-export pipeline that a specific shape (or
   every shape with a given name) should be wrapped in something other
   than the default `<div>` — `<button>`, `<a>`, `<section>`,
   `<h1>`-`<h6>`, `<ul>`/`<li>`, etc.

   Shape:
     {:id      <unique-id>      ;; local id, used by the UI for keying
      :type    :shape-id | :name-equals | :name-contains
      :value   <string>         ;; the UUID or the layer-name fragment
      :tag     <semantic-tag>   ;; one of SEMANTIC_TAGS
      :enabled <bool>}

   Persistence is local to the browser — rules live in Penpot's
   `storage` atom (backed by `localStorage`) under
   `[:html-mode-semantics <file-id-str>]`. Rules are inert state: they
   only feed the export modal's code-generation pipeline. Changing them
   does not modify the file on the server, which is exactly how the
   upstream penpot-tools viewer behaves (it stores rules under
   `~/.config/penpot-tools/semantics/<file-id>.json`)."
  (:require
   [app.util.storage :as storage]
   [cuerdas.core :as str]))

;; ---------------------------------------------------------------------------
;; Schema

(def semantic-tags
  "Whitelist of HTML tags an override can produce. Mirrors
   `SEMANTIC_TAGS` in `@penpot/html-converter/shape-code` — keep in
   sync with the JS module when adding tags."
  ["div" "button" "a"
   "nav" "header" "footer" "main" "section" "article" "aside"
   "ul" "ol" "li"
   "h1" "h2" "h3" "h4" "h5" "h6"
   "p" "label" "span"])

(def rule-types
  "Matcher kinds with their human labels. Order matters — the UI shows
   them in this order, and `shape-id` is hidden from the generic add
   form (only created via the quick-tag action on the selected shape)."
  [{:value :shape-id      :label "this shape"}
   {:value :name-equals   :label "name equals"}
   {:value :name-contains :label "name contains"}])

(def ^:private semantic-tag-set (set semantic-tags))
(def ^:private rule-type-set    (into #{} (map :value) rule-types))

(defn- new-id []
  (-> (js/Math.random) (.toString 36) (.slice 2 11)))

(defn- valid-rule?
  [r]
  (and (map? r)
       (string? (:id r))
       (seq (:id r))
       (rule-type-set (:type r))
       (string? (:value r))
       (seq (:value r))
       (semantic-tag-set (:tag r))
       (boolean? (:enabled r))))

(defn normalize-rules
  "Drop entries that don't match the rule schema. Used both on read
   (defensive against stale localStorage payloads) and on write
   (the UI never produces invalid rules, but better safe than sorry)."
  [rules]
  (into [] (filter valid-rule?) (or rules [])))

;; ---------------------------------------------------------------------------
;; Storage I/O

(defn- file-key
  [file-id]
  (when file-id (str file-id)))

(defn read-rules
  "Return the persisted rules for `file-id` as a vector. Empty when
   nothing has been stored yet."
  [file-id]
  (-> @storage/storage
      (get-in [:html-mode-semantics (file-key file-id)])
      normalize-rules))

(defn write-rules!
  "Replace the rule list for `file-id`. Storing an empty vector clears
   the entry so we don't leak unused per-file maps."
  [file-id rules]
  (let [k         (file-key file-id)
        sanitized (normalize-rules rules)]
    (when k
      (swap! storage/storage
             (fn [state]
               (let [next (if (seq sanitized)
                            (assoc-in state [:html-mode-semantics k] sanitized)
                            (update state :html-mode-semantics dissoc k))]
                 (cond-> next
                   (empty? (:html-mode-semantics next))
                   (dissoc :html-mode-semantics))))))
    sanitized))

;; ---------------------------------------------------------------------------
;; Rule helpers (pure)

(defn- normalize-value
  ;; `_type` is accepted for call-site symmetry with the typed rule fields;
  ;; value normalization is currently type-independent.
  [_type value]
  (-> value (or "") str str/trim))

(defn make-rule
  "Construct a fresh rule with a generated `:id` and `:enabled true`."
  [{:keys [type value tag]}]
  {:id      (new-id)
   :type    type
   :value   (normalize-value type value)
   :tag     tag
   :enabled true})

(defn upsert-shape-rule
  "Replace any existing `:shape-id` rule targeting `shape-id` with a new
   one tagging it as `tag`; prepend if there is none. Mirrors the
   upstream Quick-Tag behaviour."
  [rules shape-id tag]
  (let [shape-key (str shape-id)
        filtered  (filterv (fn [r]
                             (not (and (= :shape-id (:type r))
                                       (= shape-key (:value r)))))
                           rules)
        rule      (make-rule {:type :shape-id :value shape-key :tag tag})]
    (into [rule] filtered)))

(defn add-rule
  "Append a new rule, refusing empty values and unknown tags."
  [rules {:keys [type value tag]}]
  (let [v (normalize-value type value)]
    (if (or (str/empty? v) (not (semantic-tag-set tag)))
      rules
      (conj (vec rules) (make-rule {:type type :value v :tag tag})))))

(defn toggle-rule
  [rules rule-id enabled?]
  (mapv (fn [r]
          (cond-> r
            (= rule-id (:id r)) (assoc :enabled (boolean enabled?))))
        rules))

(defn delete-rule
  [rules rule-id]
  (filterv #(not= rule-id (:id %)) rules))

;; ---------------------------------------------------------------------------
;; Interop

(defn ->js
  "Convert a CLJS rules vector into the plain-object array
   `@penpot/html-converter/shape-code` expects (`resolveTagOverrides`
   reads `id`, `type`, `value`, `tag`, `enabled` literally)."
  [rules]
  (let [out (array)]
    (doseq [r (normalize-rules rules)]
      (.push out #js {:id      (:id r)
                      :type    (name (:type r))
                      :value   (:value r)
                      :tag     (:tag r)
                      :enabled (:enabled r)}))
    out))
