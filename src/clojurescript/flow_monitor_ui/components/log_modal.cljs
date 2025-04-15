(ns clojurescript.flow-monitor-ui.components.log-modal
  (:require
    [clojurescript.flow-monitor-ui.utils.helpers :refer [>dis <sub]]
    [cljs.reader :as reader]
    [clojurescript.flow-monitor-ui.events :as events]
    [reagent.core :as r]
    [re-frame.core :as rf]
    [clojurescript.flow-monitor-ui.global :refer [global-state send-socket-data]]
    [clojure.string :as str]))

(def modal-state (r/atom {:display-type :data
                          :filter-text ""}))

(rf/reg-sub
  ::log-modal-visible?
  (fn [db _]
    (-> db :routes :components :log-modal-visible?)))

(rf/reg-event-fx
  ::set-log-modal-visibility
  (fn [{:keys [db]} [_ state]]
    {:db (assoc-in db [:routes :components :log-modal-visible?] state)}))

(defn message-table [filtered-messages]
  (let [messages (if (string? (first filtered-messages))
                   (map reader/read-string filtered-messages)
                   filtered-messages)
        columns (keys (first messages))]
    [:div.table-container
     [:table.data-table
      [:thead
       [:tr
        (for [column columns]
          ^{:key (name column)}
          [:th (name column)])]]
      [:tbody
       (for [[idx row] (map-indexed vector messages)]
         ^{:key idx}
         [:tr
          (for [column columns]
            ^{:key (str idx "-" (name column))}
            [:td (get row column)])])]]]))

(defn message-data [messages]
  [:div#log-display
   (for [[idx log] (map-indexed vector messages)]
     ^{:key idx}
     [:pre (str log)])])

(defn display-select
  [{:keys [options id class-name]}]
  [:select.dropdown-select
   {:id id
    :class class-name
    :value (:display-type @modal-state)
    :on-change (fn [evt] (let [new-val (keyword (.. evt -target -value))]
                           (swap! modal-state assoc-in [:display-type] new-val)))}
   (for [{:keys [value label]} options]
     ^{:key value}
     [:option {:value value} label])])

(defn filter-input [full?]
  [:div.filter-container {:class (when full? "filter-container-full")}
   [:input {:type "text"
            :placeholder "Regex Filter..."
            :value (:filter-text @modal-state)
            :on-change (fn [e]
                         (let [filter-str (.. e -target -value)]
                           (swap! modal-state assoc-in [:filter-text] filter-str)))}]])

(defn log-modal []
  (let [modal-visible? (<sub [::log-modal-visible?])
        messages (:messages @global-state)
        homogeneous? (= 1 (count (set (map keys messages))))
        filter-text (:filter-text @modal-state)
        filtered-messages (if (str/blank? filter-text) [] (filter #(re-find (re-pattern filter-text) %) (map str messages)))
        display-messages (if (not-empty filtered-messages) filtered-messages messages)]
    [:div.modal-overlay {:class (when modal-visible? "is-visible")}
     [:div.modal
      [:div.modal-header
       [:div#modal-title "Logs"]
       [:div#close-button {:on-click (fn [evt]
                                       (.remove (.-classList (.-body js/document)) "modal-open")
                                       (>dis [::set-log-modal-visibility false]))} "X"]]
      [:div.modal-body.modal-body-pad
       [:div.controls-row
        [filter-input (not homogeneous?)]
        (when homogeneous? [display-select {:options [{:value "data" :label "data"} {:value "table" :label "table"}]}])]
       [:div.panel-contents
        (if homogeneous?
          (case (:display-type @modal-state)
            :data [message-data display-messages]
            :table [message-table display-messages])
          [message-data display-messages])]]]]))

