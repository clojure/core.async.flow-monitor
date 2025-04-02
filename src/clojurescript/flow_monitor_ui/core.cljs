(ns clojurescript.flow-monitor-ui.core
  (:import goog.History)
  (:require
    [clojure.string :as string]
    [day8.re-frame.http-fx]
    [goog.events :as gevents]
    [goog.history.EventType :as EventType]
    [re-frame.core :as rf]
    [reagent.dom :as reagent]
    [reitit.frontend :as rfront]
    [lambdaisland.glogi :as log]
    [lambdaisland.glogi.console :as glogi-console]
    [clojurescript.flow-monitor-ui.components.alerts :refer [alert-display]]
    [clojurescript.flow-monitor-ui.components.nav :refer [settings-bar]]
    [clojurescript.flow-monitor-ui.config :as config]
    [clojurescript.flow-monitor-ui.events :as shared-events]
    [clojurescript.flow-monitor-ui.router :as router]
    [clojurescript.flow-monitor-ui.subs :as shared-subs]
    [clojurescript.flow-monitor-ui.utils.helpers :refer [<sub >dis]]))

(glogi-console/install!)

(log/set-levels
  {:glogi/root   :info    ;; Set a root logger level, this will be inherited by all loggers
   'my.app.thing :trace   ;; Some namespaces you might want detailed logging
   'action-tests.routes.index.events :error   ;; or for others you only want to see errors.
   })

(defn dev-setup []
  (when config/debug?
    (enable-console-print!)
    (log/info :message "Dev mode")))

(rf/reg-fx
  ::hook-browser-navigation
  (fn []
    (doto (History.)
      (gevents/listen
        EventType/NAVIGATE
        (fn [^js event]
          (let [uri (or (not-empty (string/replace (.-token event) #"^.*#" "")) "/")]
            (>dis [::shared-events/set-active-route (rfront/match-by-path router/routes uri)]))))
      (.setEnabled true))))

(rf/reg-event-fx
  ::initialize-browser-navigation
  (fn [_ _]
    {::hook-browser-navigation nil}))

(defn active-route []
  (let [active-route (<sub [::shared-subs/active-route])]
    (>dis [::shared-events/dispatch-route-events active-route])
    (-> active-route :data :view)))

(defn app []
  (let [ready? (<sub [::shared-subs/initialized?])]
    (if-not ready?
      [:div "Initialising ..."]
      [:div#container
       [alert-display]
       [active-route]])))

(defn mount-app []
  (rf/clear-subscription-cache!)
  (reagent/render [app] (.getElementById js/document "app")))

(defn ^:export main []
  (dev-setup)
  (rf/dispatch-sync [::shared-events/initialize-db])
  (rf/dispatch-sync [::initialize-browser-navigation])
  (mount-app))

(defn ^:after-load on-reload
  "Reload hook to run after project recompiles
   *NOTE* This has effect of hitting `:dispatch-on-entry` events twice.
   Only a dev time situation so can safely be ignored"
  []
  (let [uri (subs (.-hash js/window.location) 1)]
    (>dis [::shared-events/set-active-route (rfront/match-by-path router/routes uri)])
    (mount-app)))
