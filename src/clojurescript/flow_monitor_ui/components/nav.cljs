(ns clojurescript.flow-monitor-ui.components.nav
  (:require
    [clojurescript.flow-monitor-ui.global :refer [proc-card-state remove-arrows draw chan-representation]]
    [clojurescript.flow-monitor-ui.components.log-modal :as log-modal]
    [clojurescript.flow-monitor-ui.utils.helpers :refer [>dis <sub]]
    [reagent.core :as r]))


(defn set-proc-card-expansion-state [expanded?]
  (let [collapsed-state (reduce-kv (fn [res k _]
                                     (assoc res k expanded?))
                                   {} @proc-card-state)]
    (reset! proc-card-state collapsed-state)
    (remove-arrows)
    (js/setTimeout (fn []
                     (draw)) 600)))

(defn collapse-elements-with-delay [delay]
  (let [elements (array-seq (.getElementsByClassName js/document "collapsible-meter"))]
    (doseq [element elements]
      (let [height (.-scrollHeight element)]
        (set! (.. element -style -height) (str height "px"))
        (.. element -offsetHeight)
        (.add (.-classList element) "collapsing")
        (js/setTimeout
          (fn []
            (set! (.. element -style -height) "0px"))
          delay)))))

(defn expand-all-meters []
  (let [elements (array-seq (.getElementsByClassName js/document "collapsible-meter"))]
    (doseq [element elements]
      (set! (.. element -style -height) "0px")
      (.. element -offsetHeight)
      (.add (.-classList element) "collapsing")
      (set! (.. element -style -height) (str (.-scrollHeight element) "px"))
      (js/setTimeout
        (fn []
          (.remove (.-classList element) "collapsing")
          (set! (.. element -style -height) "auto"))
        300))))

(defn set-chan-representation
  "Valid options :meter :line"
  [display-type]
  (remove-arrows)
  (reset! chan-representation display-type)
  (if (= :line display-type)
    (collapse-elements-with-delay 500)
    (expand-all-meters))
  (js/setTimeout (fn []
                     (draw)) 600))

(defn settings-bar []
  (let [menu-open? (r/atom false)]
    (fn []
      (let [all-expanded? (reduce-kv (fn [res _ v]
                                       (cond
                                         (false? res) false
                                         (false? v) false
                                         :else true))
                                     true @proc-card-state)]
        [:div.settings-icons-container
         [:div.log-icon-wrapper
          [:button.log-icon
           {:on-click #(>dis [::log-modal/set-log-modal-visibility true])}
           [:img {:src "assets/img/message_icon.svg"}]]]
         [:div.settings-container
          [:div.settings-icon-wrapper
           [:button.settings-icon {:class (when @menu-open? "opened")
                                   :on-click #(swap! menu-open? not)}
            [:img {:src "assets/img/settings.svg"}]]]]
         [:div.settings-dropdown {:class (when @menu-open? "active")}
          [:div.settings-header
           [:h3 "Settings"]
           [:button.close-settings {:on-click #(reset! menu-open? false)} ""]]
          [:div.settings-content
           [:h4 "Display"]
           [:div.setting-option
            [:label "Proc Cards"]
            [:div.pill-toggle
             [:button.pill-btn.pill-left
              {:class (when-not all-expanded? "active")
               :on-click (fn [e] (set-proc-card-expansion-state false))}
              "Collapse All"]
             [:button.pill-btn.pill-right
              {:class (when all-expanded? "active")
               :on-click (fn [e] (set-proc-card-expansion-state true))}
              "Expand All"]]]
           [:div.setting-option
            [:label "Chan Representation"]
            [:div.pill-toggle
             [:button.pill-btn.pill-left
              {:class (when (= :line @chan-representation) "active")
               :on-click (fn [e] (set-chan-representation :line))}
              "Line"]
             [:button.pill-btn.pill-right
              {:class (when (= :meter @chan-representation) "active")
               :on-click (fn [e] (set-chan-representation :meter))}
              "Meter"]]]]]]))))
