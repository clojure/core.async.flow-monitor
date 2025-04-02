(ns clojurescript.flow-monitor-ui.global
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    [cognitect.transit :as t]
    [clojurescript.flow-monitor-ui.events :as events]
    [clojurescript.flow-monitor-ui.utils.helpers :refer [>dis <sub]]
    [clojurescript.flow-monitor-ui.config :refer [websocket-uri-base websocket-uri-route]]))

(defonce global-state (r/atom {:ws-chan nil
                               :ws-connected false
                               :data nil
                               :flow-ping {}
                               :errors {}
                               :messages {}
                               :active-tab :messages
                               :active-proc-pid :nil}))

(def lines (r/atom {}))

(defonce global-pings (r/atom {:flow-ping {}}))

;; Are the cards expanded {:proc-io true}
(def proc-card-state (r/atom {}))
(def chan-representation (r/atom :meter)) ; :meter or :line

(def writer (t/writer :json))
(def reader (t/reader :json))

; region == svg line related functions used by both the view and nav

(defn set-id-on-last-leader-line!
  [id]
  (let [body (.-body js/document)
        children (.-children body)
        child-count (.-length children)
        last-leader-line
        (loop [i (dec child-count)]
          (when (>= i 0)
            (let [child (aget children i)]
              (if (.. child -classList (contains "leader-line"))
                child
                (recur (dec i))))))]

    (when last-leader-line
      (set! (.-id last-leader-line) id)
      last-leader-line)))


(defn draw []
  (doall (for [proc (-> @global-state :data :conns)]
           (do
             (let [chan-type @chan-representation
                   in-expanded? (get @proc-card-state (-> proc second first))
                   out-expanded? (get @proc-card-state (ffirst proc))
                   in-socket (str (-> proc second first) "-" (-> proc second second))
                   in-chan (str in-socket "-in-chan")
                   in-chan-el (when (= :meter chan-type) (.getElementById js/document in-chan))
                   in-socket-target (if in-expanded?
                                      in-socket
                                      (str in-socket "-collapsed"))
                   out-socket (str (-> proc first first) "-" (-> proc first second))
                   out-chan (str out-socket "-out-chan")
                   out-chan-el (when (= :meter chan-type) (.getElementById js/document out-chan))
                   out-socket-target (if out-expanded?
                                       out-socket
                                       (str out-socket "-collapsed"))
                   out-socket-el (.getElementById js/document out-socket-target)
                   in-socket-el (.getElementById js/document in-socket-target)]
               (if (= :meter chan-type)
                 (let [l1 (js/LeaderLine. out-socket-el out-chan-el (clj->js {:color "#52606D"
                                                                              :size 3
                                                                              :startSocket "bottom"
                                                                              :hide true
                                                                              :endSocket "top"
                                                                              :path "straight" #_ "grid" #_ "straight"}))
                       l2 (js/LeaderLine. out-chan-el in-socket-el (clj->js {:color "#52606D"
                                                                             :size 3
                                                                             :startSocket "bottom"
                                                                             :hide true
                                                                             :endSocket "top"
                                                                             :path "grid" #_ "grid" #_ "straight"}))]
                   (.show l1 "draw")
                   (swap! lines assoc (random-uuid) l1)
                   (.show l2 "draw")
                   (swap! lines assoc (random-uuid) l2))
                 (let [line (js/LeaderLine. out-socket-el in-socket-el (clj->js {:color "#52606D"
                                                                                 :size 3
                                                                                 :startSocket "bottom"
                                                                                 :endSocket "top"
                                                                                 :path "grid" #_"grid" #_"straight"
                                                                                 :hide true
                                                                                 :animOptions (clj->js {:duration 1000 :timing "ease"})
                                                                                 :startLabel (js/LeaderLine.captionLabel "0/10" (clj->js {:color "#52606D" :outlineColor "#CBD2D9"}))
                                                                                 ; TODO Conditional upon unique chan id
                                                                                 #_ #_ :endLabel (js/LeaderLine.captionLabel "0/10"
                                                                                                                             {:color "#52606D"
                                                                                                                              :outlineColor "#CBD2D9"
                                                                                                                              #_#_:offset [-20 0]})}))]
                   (.show line "draw")
                   ;(set! (.-id line) (str in-socket "-" out-socket))
                   (set-id-on-last-leader-line! (str out-socket "-svg"))
                   (swap! lines assoc in-socket line)
                   (swap! lines assoc out-socket line))))))))

(defn remove-arrows []
  (let [arrows @lines
        arrow-set (reduce (fn [res [_ l]] (conj res l)) #{} arrows)]
    (doseq [l arrow-set]
      (.remove l))
    (reset! lines {})))
; endregion

; region == WebSocket
(defn send-socket-data [data]
  (.send (:ws-chan @global-state) (.stringify js/JSON (t/write writer data))))

(defn process-ws-message [msg]
  (let [data (->> msg .-data (t/read reader))
        action (:action data)]
    (case action
      :datafy (do
                (swap! global-state assoc :data (:data data))
                (let [procs (reduce (fn [res [[from _] [to _]]]
                                      (conj res from to))
                                    #{}
                                    (-> data :data :conns))
                      ; set the initial state of the box expansion
                      proc-state (reduce (fn [res proc]
                                           (assoc res proc true)) {} procs)]
                  (reset! proc-card-state proc-state)))
      :ping (let [current-time (js/Date.)]
              (swap! global-pings update-in [:flow-ping]
                     (fn [ping-map]
                       (merge-with #(assoc (merge %1 %2) :last-updated current-time)
                         ping-map
                         (:data data)))))
      :error (do
               (let [pid (keyword (second (re-find #":pid :(.*)," (:data data))))]
                 (swap! global-state update-in [:errors pid]
                        (fnil (comp #(take-last 100 %) conj) []) (:data data))))
      "Default")))

(rf/reg-sub
  ::get-params
  (fn [db _]
    (get-in db [:query-params])))

(defn make-websocket! []
  (let [params (<sub [::get-params])]
    (if (not (:ws-connected @global-state))
     (if-let [chan (js/WebSocket. (str websocket-uri-base (or (:port params) 9998) websocket-uri-route))]
       (do
         (swap! global-state assoc :ws-connected true)
         (set! (.-onmessage chan) (fn [msg]
                                    (process-ws-message msg)))
         (set! (.-onclose chan) (fn [msg]
                                  (.log js/console "Websocket was closed. Connection should be reestablished.")
                                  (swap! global-state assoc :ws-connected false)
                                  (>dis [::events/websocket-server-closed-alert "Websocket server was closed!"])
                                  (remove-arrows)
                                  #_(make-websocket!)))
         (swap! global-state assoc :ws-chan chan)
         (.log js/console (str "Websocket connection established with: " (str websocket-uri-base (or (:port params) 9998) websocket-uri-route))))
       (throw (js/Error. "Websocket connection failed!"))))))
; endregion

