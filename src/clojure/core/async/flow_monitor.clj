(ns clojure.core.async.flow-monitor
  (:require
    [clojure.core.async :as async]
    [clojure.core.async.flow :as flow]
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

(def default-write-handler (transit/write-handler "Default" (fn [o] (str o))))

(defn clj->transit-str [state arg]
  (let [out (ByteArrayOutputStream. 4096)
        writer (transit/writer out :json {:default-handler default-write-handler})]
    (transit/write writer arg)
    (.toString out)))

(defn transit-str->clj [arg]
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

(defn send-message [state message]
  (doall (for [channel (:channels @state)]
           (httpkit/send! channel (clj->transit-str message)))))

(defn loop-ping [state]
  (async/thread
    (loop [s state]
      (if (:loop-ping? @s)
        (do (send-message state {:action :ping :data (reduce-kv
                                                       (fn [res k v]
                                                         (assoc res k (mainline-chan-meta v)))
                                                       {}
                                                       (flow/ping (:flow @state)))})
            (Thread/sleep 1000)
            (recur s))
        (println "Ping loop stopped")))))

(defn ws-response [state request]
  (httpkit/as-channel
    request
    {:on-open (fn [ch]
                (swap! state update-in [:channels] conj ch)
                (send-message state {:action :datafy
                                     :data (d/datafy (:flow @state))})
                (swap! state assoc-in [:loop-ping?] true)
                (loop-ping state))
     :on-receive (fn [ch data]
                   (let [clj-data (transit-str->clj data)
                         action (:action clj-data)]
                     (case action
                       :inject (flow/inject (:flow @state) (:target clj-data) (edn/read-string (:data clj-data)))
                       :resume-proc (flow/resume-proc (:flow @state) (:pid clj-data))
                       :pause-proc (flow/pause-proc (:flow @state) (:pid clj-data)))))
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
              (send-message state {:action :message :data (with-out-str (clojure.pprint/pprint val))}))
            (recur))))))
  nil)

(defn start-server
  "Starts a web server for monitoring and interacting with a core.async.flow

  Parameters:
  - opts: A map with the following keys:
    - :flow (required) - The return value from clojure.core.async.flow/create-flow
    - :port (optional) - The port to run the server on (default: 9998)

  Returns:
  An atom containing the server's state, and prints a local url where the frontend can be reached"
  [{:keys [flow port] :or {port 9998}}]
  (let [state (atom default-state)
        error-chan (:clojure.datafy/obj (meta (:error (:chans (d/datafy flow)))))
        report-chan (:clojure.datafy/obj (meta (:report (:chans (d/datafy flow)))))]
    (swap! state assoc :flow flow)
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
