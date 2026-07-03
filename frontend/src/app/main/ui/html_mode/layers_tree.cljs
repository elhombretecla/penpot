;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.html-mode.layers-tree
  "Left-side layers tree for HTML Mode.

   Mirrors the visual conventions of the viewer's Inspect left sidebar
   (`app.main.ui.inspect.left-sidebar`): one panel-background `<aside>`,
   rows with a shape icon and label, expand/collapse chevron for parents,
   indentation by `--layer-indentation-size`, selected row uses
   `--color-background-quaternary`.

   We don't reuse `layer-item-inner*` from the workspace because it pulls
   in workspace state (`refs/workspace-data`, drag-and-drop, contextual
   menus). HTML Mode's tree is read-only and operates on the page's
   `:objects` map directly, so a smaller component is clearer."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.util.i18n :refer [tr]]
   [app.util.shape-icon :as usi]
   [rumext.v2 :as mf]))

(defn- find-root-frame
  "Locate the page's root frame — the synthetic frame whose `:parent-id`
   equals its own `:id`. Same approach `html_mode.cljs` uses to centre
   the preview."
  [objects]
  (some (fn [shape]
          (when (and (some? shape)
                     (= (:id shape) (:parent-id shape)))
            shape))
        (vals objects)))

;; ---------------------------------------------------------------------------
;; Single row

(mf/defc layer-row*
  [{:keys [shape depth selected? expanded? has-children?
           inside-component? on-select on-toggle]}]
  (let [id        (:id shape)
        sname     (:name shape)
        icon-id   (usi/get-shape-icon shape)
        ;; Mirrors the workspace `.element-name.type-comp` styling:
        ;; any shape that's the head of a component instance — or a
        ;; descendant of one — gets the component-foreground colour
        ;; for both its label and its icon. The recursion that builds
        ;; the tree below passes `inside-component?` down so the
        ;; whole subtree picks up the same accent.
        component? (or inside-component? (some? (:component-id shape)))

        handle-toggle
        (mf/use-fn
         (mf/deps id on-toggle)
         (fn [^js e]
           (.stopPropagation e)
           (when on-toggle (on-toggle id))))

        handle-select
        (mf/use-fn
         (mf/deps id on-select)
         (fn [^js e]
           ;; Ctrl/Cmd-click on a layer row → zoom-to-fit in the
           ;; canvas; plain click → just centre. The handler in the
           ;; parent reads `:fit` from `opts` and forwards it to the
           ;; iframe as `d.fit`.
           (when on-select
             (on-select id {:fit (or (.-ctrlKey e) (.-metaKey e))}))))

        ;; Keyboard operation for the treeitem row: Enter/Space selects,
        ;; ArrowRight expands a collapsed parent, ArrowLeft collapses an
        ;; expanded one — so the tree is usable without a pointer.
        handle-key
        (mf/use-fn
         (mf/deps id on-select on-toggle has-children? expanded?)
         (fn [^js e]
           (case (.-key e)
             ("Enter" " ")
             (do (.preventDefault e)
                 (when on-select
                   (on-select id {:fit (or (.-ctrlKey e) (.-metaKey e))})))
             "ArrowRight"
             (when (and has-children? (not expanded?) on-toggle)
               (.preventDefault e)
               (on-toggle id))
             "ArrowLeft"
             (when (and has-children? expanded? on-toggle)
               (.preventDefault e)
               (on-toggle id))
             nil)))]

    [:div {:class (stl/css-case
                   :layer-row true
                   :layer-row-component component?
                   :selected selected?)
           :style {"--depth" depth}
           :role "treeitem"
           :tab-index 0
           :aria-selected selected?
           :aria-expanded (when has-children? expanded?)
           :on-click handle-select
           :on-key-down handle-key
           :data-testid (dm/str "html-mode-layer-" id)}
     [:span {:class (stl/css :tab-indentation)
             :style {"--depth" depth}}]
     [:div {:class (stl/css :layer-row-inner)}
      [:button {:class (stl/css-case
                        :toggle-button true
                        :inverse expanded?
                        :empty (not has-children?))
                :type "button"
                :tab-index (if has-children? 0 -1)
                :aria-label (tr "viewer.html-mode.tree.toggle")
                :on-click handle-toggle}
       (when has-children?
         [:> icon* {:icon-id (if expanded? i/arrow-down i/arrow)
                    :size "s"}])]
      [:div {:class (stl/css :layer-icon)}
       [:> icon* {:icon-id icon-id :size "s"}]]
      [:span {:class (stl/css :layer-name)} (or sname "—")]]]))

;; ---------------------------------------------------------------------------
;; Recursive subtree

(declare layer-subtree*)

(mf/defc layer-subtree*
  [{:keys [shape objects depth selected-id collapsed-set inside-component?
           on-select on-toggle]}]
  (let [id         (:id shape)
        expanded?  (not (contains? collapsed-set id))
        child-ids  (:shapes shape)
        children   (when (and expanded? (seq child-ids))
                     (->> child-ids
                          (reverse)
                          (keep #(get objects %))))
        ;; Sticky `inside-component?` flag: once true, all descendants
        ;; inherit it. A nested component head still resolves to true
        ;; from its own `:component-id`, so the recursion is a no-op
        ;; in that case.
        child-inside? (or inside-component? (some? (:component-id shape)))]
    [:*
     [:> layer-row*
      {:shape shape
       :depth depth
       :selected? (= (str id) selected-id)
       :expanded? expanded?
       :has-children? (boolean (seq child-ids))
       :inside-component? inside-component?
       :on-select on-select
       :on-toggle on-toggle}]
     (when (seq children)
       (for [child children]
         [:> layer-subtree*
          {:key (str (:id child))
           :shape child
           :objects objects
           :depth (inc depth)
           :selected-id selected-id
           :collapsed-set collapsed-set
           :inside-component? child-inside?
           :on-select on-select
           :on-toggle on-toggle}]))]))

;; ---------------------------------------------------------------------------
;; Root component

(mf/defc layers-tree*
  [{:keys [page selected on-select]}]
  (let [objects      (:objects page)
        root         (find-root-frame objects)
        selected-id  (:id selected)
        scroll-ref   (mf/use-ref nil)
        collapsed*   (mf/use-state #{})
        collapsed    (deref collapsed*)

        handle-toggle
        (mf/use-fn
         (fn [id]
           (swap! collapsed*
                  (fn [s] (if (contains? s id) (disj s id) (conj s id))))))]

    ;; When the canvas selects a shape, scroll the matching tree row
    ;; into the visible part of the panel. `scrollIntoView` with
    ;; `block: 'center'` re-centres the row in the scroll viewport so
    ;; the user can find their selection immediately.
    (mf/with-effect [selected-id]
      (when-let [^js scroll (mf/ref-val scroll-ref)]
        (when selected-id
          (when-let [^js row (.querySelector scroll
                                             (str "[data-testid=\"html-mode-layer-"
                                                  selected-id "\"]"))]
            (.scrollIntoView row #js {:block "center" :behavior "smooth"})))))

    [:aside {:class (stl/css :tree)
             :aria-label (tr "viewer.html-mode.tree.aria")}
     [:div {:class (stl/css :tree-scroll)
            :ref scroll-ref
            :role "tree"}
      (when root
        (for [child-id (reverse (:shapes root))]
          (when-let [child (get objects child-id)]
            [:> layer-subtree*
             {:key (str child-id)
              :shape child
              :objects objects
              :depth 0
              :selected-id selected-id
              :collapsed-set collapsed
              :inside-component? false
              :on-select on-select
              :on-toggle handle-toggle}])))]]))
