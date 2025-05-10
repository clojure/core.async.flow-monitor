(ns clojure.core.async.flow-monitor
  (:require
    [clojure.core.async :as async]
    [clojure.core.async.flow :as async-flow]
    [ring.util.response :as response]
    [ring.middleware.content-type :refer [wrap-content-type]]
    [ring.middleware.not-modified :refer [wrap-not-modified]]
    [clojure.edn :as edn]
    [muuntaja.core :as m]
    [reitit.ring :as ring]
    [cognitect.transit :as transit]
    [clojure.data.json :as json]
    [org.httpkit.server :as httpkit]
    [clojure.datafy :as d])
  (:import
    [java.io ByteArrayInputStream ByteArrayOutputStream]))

(def default-state {:server nil
                    :channels #{}
                    :loop-ping? false
                    :flow nil})

(def default-write-handler (transit/write-handler "default" (fn [obj] (str obj))))

(defn transit-str-writer [data user-handlers]
  (let [out (ByteArrayOutputStream. 4096)
        writer (transit/writer out :json {:handlers user-handlers
                                          :default-handler default-write-handler})]
    (transit/write writer data)
    (.toString out)))

(defn transit-str-reader [arg]
  (let [arg-json (json/read-str arg)
        arg-bytes (.getBytes arg-json "UTF-8")
        in (ByteArrayInputStream. arg-bytes)
        reader (transit/reader in :json)]
    (transit/read reader)))

(defn mainline-chan-meta [proc]
  (let [reducer-fn (fn [res k v]
                     (assoc res k (assoc v :chan-obj (re-find #"\@[a-zA-Z0-9]+" (str (:clojure.datafy/obj (meta v)))))))]
    (assoc proc
      :clojure.core.async.flow/ins (reduce-kv reducer-fn {} (:clojure.core.async.flow/ins proc))
      :clojure.core.async.flow/outs (reduce-kv reducer-fn {} (:clojure.core.async.flow/outs proc)))))

(defn default-flow-state-filter [proc-state]
  (into {} (filter (fn [[k _]] (not= "clojure.core.async.flow" (namespace k))) proc-state)))

(defn filter-state [proc filters]
  (let [pid (:clojure.core.async.flow/pid proc)
        state (:clojure.core.async.flow/state proc)
        user-filter (cond
                      (contains? filters pid) (pid filters)
                      (contains? filters :default) (:default filters)
                      :else identity)
        filtered-state (cond
                         (map? state) (->> state
                                           (default-flow-state-filter)
                                           (filter user-filter)
                                           (into {}))
                         (string? state) state
                         :else (filter user-filter state))]
    (assoc proc :clojure.core.async.flow/state filtered-state)))

(defn send-message [state message]
  (doall (for [channel (:channels @state)]
           (httpkit/send! channel (transit-str-writer message (:handlers @state))))))

(defn loop-ping [state]
  (async/thread
    (loop [s state]
      (if (:loop-ping? @s)
        (do (send-message state {:action :ping :data (d/datafy (reduce-kv
                                                                 (fn [res k v]
                                                                   (assoc res k (filter-state (mainline-chan-meta v) (:filters @state))))
                                                                 {}
                                                                 (async-flow/ping (:flow @state))))})
            (Thread/sleep 1000)
            (recur s))
        (println "Ping loop stopped")))))

(defn ws-response [state request]
  (httpkit/as-channel
    request
    {:on-open (fn [ch]
                (swap! state update-in [:channels] conj ch)
                (send-message state {:action :datafy
                                     :data (assoc (d/datafy (:flow @state)) :root (:root @state))})
                (swap! state assoc-in [:loop-ping?] true)
                (loop-ping state))
     :on-receive (fn [ch data]
                   (let [clj-data (transit-str-reader data)
                         action (:action clj-data)]
                     (case action
                       :inject (async-flow/inject (:flow @state) (:target clj-data) (edn/read-string (:data clj-data)))
                       :resume-proc (async-flow/resume-proc (:flow @state) (:pid clj-data))
                       :pause-proc (async-flow/pause-proc (:flow @state) (:pid clj-data)))))
     :on-close (fn [ch status]
                 (swap! state assoc-in [:loop-ping?] false)
                 (swap! state update-in [:channels] disj ch))
     :on-ping nil}))

(defn frontend-handler [_]
  (-> (response/resource-response "index.html" {:root "public"})
      (response/content-type "text/html")))

(defn app [state]
  (->
    (ring/ring-handler
      (ring/router
        [["/flow-socket" {:get (fn [req] (ws-response state req))}]
         ["/app/*" {:get frontend-handler}]]
        {:data {:muuntaja m/instance}})
      (ring/routes
        (ring/create-resource-handler {:path "/" :root "public"})
        (ring/create-default-handler)))
    (wrap-content-type)
    (wrap-not-modified)))

(defn report-monitoring [state report-chan error-chan]
  (async/thread
    (loop []
      (let [[val port] (async/alts!! [report-chan error-chan])]
        (if (nil? val)
          (prn "========= monitoring shutdown")
          (do
            (if (= port error-chan)
              (send-message state {:action :error :data (with-out-str (clojure.pprint/pprint val))})
              (send-message state {:action :message :data val}))
            (recur))))))
  nil)

(defn start-server
  "Starts a web server for monitoring and interacting with a core.async.flow

  Parameters:
  - opts: A map with the following keys:
    - :flow (required) - The return value from clojure.core.async.flow/create-flow
    - :port (optional) - The port to run the server on (default: 9998)
    - :handlers (optional) - A map of custom Transit write handlers to use when serializing state
                             data to send to the frontend. These handlers should follow the format
                             expected by cognitect.transit/writer :handlers
    - :filters (optional) - A map of {:pid state-filter-pred} which is applied to the state
                            for the associated pid. :default is also accepted and will be applied
                            to any proc that doesn't have a filter specified for the pid.
    - :root (optional) - A vector of :pid keywords to designate the root procs in the event of
                         circular flows.

  Returns:
  An atom containing the server's state, and prints a local url where the frontend can be reached"
  [{:keys [flow port handlers state-filters root] :or {port 9998}}]
  (let [state (atom default-state)
        error-chan (:clojure.datafy/obj (meta (:error (:chans (d/datafy flow)))))
        report-chan (:clojure.datafy/obj (meta (:report (:chans (d/datafy flow)))))]
    (async-flow/ping flow)
    (swap! state assoc :flow flow :handlers handlers :filters state-filters :root root)
    (report-monitoring state report-chan error-chan)
    (let [server (httpkit/run-server (app state) {:port port
                                                  :max-body 100000000
                                                  :legacy-return-value? false})]
      (swap! state assoc :server server)
      (println "\n--------------------------------")
      (println (str "- Visit: http://localhost:" port "/index.html#/?port=" port " -"))
      (println "--------------------------------\n")
      state)))

(defn stop-server
  "Stops a running flow monitoring server and cleans up its resources.

  Parameters:
    - server-atom (required) - The value returned from start-server"
  [server-state]
  (httpkit/server-stop! (:server @server-state))
  (reset! server-state default-state))
