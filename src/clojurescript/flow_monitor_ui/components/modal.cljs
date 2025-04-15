(ns clojurescript.flow-monitor-ui.components.modal
  (:require
    [clojurescript.flow-monitor-ui.utils.helpers :refer [>dis <sub]]
    [clojurescript.flow-monitor-ui.events :as events]
    [reagent.core :as r]
    [re-frame.core :as rf]
    [clojurescript.flow-monitor-ui.global :refer [global-state send-socket-data]]
    [clojure.string :as str]))

(rf/reg-sub
  ::modal-visible?
  (fn [db _]
    (-> db :routes :components :modal-visible?)))

(rf/reg-event-fx
  ::set-modal-visibility
  (fn [{:keys [db]} [_ state]]
    {:db (assoc-in db [:routes :components :modal-visible?] state)}))

(rf/reg-event-fx
  ::set-code-mirror
  (fn [{:keys [db]} [_ code-mirror]]
    {:db (assoc-in db [:routes :components :code-mirror] code-mirror)}))

(rf/reg-event-fx
  ::save-editor-content
  (fn [{:keys [db]} [_ content]]
    {:db (assoc-in db [:editor-contents :inject] content)}))

(rf/reg-sub ::get-editor-content
  (fn [db _]
    (get-in db [:editor-contents :inject] "[]")))  ;; default to empty string

(defn codemirror []
  (let [node-ref (atom nil)
        cm-ref (atom nil)
        saved-content (<sub [::get-editor-content])]
    (r/create-class
      {:reagent-render (fn []
                         [:div#codemirror-wrapper
                          {:ref #(reset! node-ref %)
                           :style {:height "100%" :width "100%"}}])
       :component-did-mount (fn [_]
                              (when @node-ref
                                (let [opts (clj->js {:value (or saved-content "[]")
                                                     :mode "text/x-clojure"
                                                     :lineNumbers false
                                                     :autoCloseBrackets true
                                                     :matchBrackets true})
                                      code-mirror (js/CodeMirror. @node-ref opts)]
                                  (reset! cm-ref code-mirror)
                                  (.focus code-mirror)
                                  (.setCursor code-mirror 0 1)
                                  (.on code-mirror "change"
                                       #(rf/dispatch [::save-editor-content (.getValue %1)]))
                                  (>dis [::set-code-mirror code-mirror]))))})))

(defn find-targets [connections proc]
  (reduce (fn [res [from to]]
            (cond
              (= proc (first from)) (conj res from)
              (= proc (first to)) (conj res to)
              :else res))
          #{}
          connections))

(defn inject []
  (let [pid (:active-proc-pid @global-state)
        conns (-> @global-state :data :conns)
        targets (find-targets conns pid)
        content (<sub [::get-editor-content])]
    [:div#input-body-wrapper
     [codemirror]
     [:div.inject-btn-container
      [:div.inject-buttons
       (for [t targets]
         ^{:key t} [:div.button
                    {:on-click (fn [e]
                                 (send-socket-data {:action :inject :target t :data content})
                                 (>dis [::events/inject-complete-alert (str "Data successfully injected to: " t)]))}
                    (str (second t))])]]]))

(defn error-display []
  (let [pid (:active-proc-pid @global-state)]
    [:div#error-display
     (for [[idx error] (map-indexed vector (-> @global-state :errors pid))]
       ^{:key idx}
       [:pre error])]))

(defn titleize-keyword [kw]
  (when kw (-> (name kw)
               (clojure.string/replace #"-" " ")
               (clojure.string/split #"\s+")
               (->> (map clojure.string/capitalize)
                    (clojure.string/join " ")))))

(defn handle-tab-click [tab-name]
  (when (= tab-name :inject)
    (when-let [cm (.querySelector js/document ".CodeMirror")]
      (-> cm .-CodeMirror .focus))))

(defn report-modal []
  (let [modal-visible? (<sub [::modal-visible?])]
    [:div.modal-overlay {:class (when modal-visible? "is-visible")}
     [:div.modal
      [:div.modal-header
       [:div#modal-title (titleize-keyword (:active-proc-pid @global-state))]
       [:div#close-button {:on-click (fn [evt]
                                       (.remove (.-classList (.-body js/document)) "modal-open")
                                       (>dis [::set-modal-visibility false]))} "X"]]
      [:div.modal-body
       [:div.tabs
        [:div.tab {:class (when (= (:active-tab @global-state) :inject) "active")
                   :on-click (fn [e]
                               (swap! global-state assoc :active-tab :inject)
                               (handle-tab-click :inject))}
         "Inject"]
        [:div.tab {:class (when (= (:active-tab @global-state) :errors) "active")
                   :on-click (fn [e]
                               (swap! global-state assoc :active-tab :errors)
                               (handle-tab-click :errors))}
         (let [pid (:active-proc-pid @global-state)]
           (str "Errors (" (-> @global-state :errors pid count) ")"))]]
       [:div.panel-contents
        (case (:active-tab @global-state)
          :inject [inject]
          :errors [error-display])]]]]))

