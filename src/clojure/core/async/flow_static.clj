(ns clojure.core.async.flow-static
  (:require
    [clojure.java.io :as io]
    [hiccup2.core :as h]
    [clojure.data.json :as json]))

(defn titleize-keyword [kw]
  (as-> (name kw) $
        (clojure.string/replace $ #"-" " ")
        (clojure.string/split $ #"\s+")
        (map clojure.string/capitalize $)
        (clojure.string/join " " $)))

(defn flow-relationships [data]
  (reduce (fn [res [[from-proc out-port] [to-proc in-port]]]
            (-> res
                (update-in [from-proc :from] (fnil conj []))
                (update-in [to-proc :to] (fnil conj []))
                (update-in [from-proc :ins] (fnil conj []))
                (update-in [to-proc :outs] (fnil conj []))
                (update-in [to-proc :from] (fnil conj []) from-proc)
                (update-in [from-proc :to] (fnil conj []) to-proc)
                (update-in [to-proc :ins] (fnil conj []) in-port)
                (update-in [from-proc :outs] (fnil conj []) out-port)))
          {} data))

(defn flow-levels [relationships]
  (loop [result []
         current-level (filter (fn [[_ v]] (empty? (:from v))) relationships)
         remaining (apply dissoc relationships (map first current-level))]
    (if (empty? current-level)
      result
      (let [next-level (select-keys remaining (mapcat (fn [[_ v]] (:to v)) current-level))]
        (recur (conj result (map (fn [[k v]] {k v}) current-level))
               next-level
               (apply dissoc remaining (keys next-level)))))))

(defn proc-card [proc]
  [:div.middle-section-one-container
   [:div.title-container [:h2.title (titleize-keyword proc)]]])

(defn proc-el [proc-map]
  (let [proc (-> proc-map keys first)
        ins (-> proc-map vals first :ins)
        outs (-> proc-map vals first :outs)]
    [:div.card-container {:id (name proc)}
     [:div.proc-card {:class "expanded"}
      [:div.expanded-view
       [:div.header-labels
        (for [io-id ins]
          [:div.header-label {:id (str proc "-" io-id)} io-id])]
       (proc-card proc)
       [:div.output-section
        [:div.output-container
         (for [io-id outs]
           [:div.output {:id (str proc "-" io-id)} io-id])]]]]]))

(defn proc-row [idx row]
  [:div.row-3
   (for [proc row]
     (proc-el proc))])

(defn chart [conns]
  (let [relationships (flow-relationships conns)]
    [:div#chart
     (for [[idx row] (map-indexed vector (flow-levels relationships))]
       (proc-row idx row))]))

(defn json-friendly [conn]
  (let [from-data (first conn)
        to-data (second conn)
        from-proc (name (first from-data))
        from-port (name (second from-data))
        to-proc (name (first to-data))
        to-port (name (second to-data))]
    {"from" {"proc" from-proc "port" from-port}
     "to" {"proc" to-proc "port" to-port}}))

(defn connections-to-json [connections]
  (json/write-str (mapv json-friendly connections)))

(defn template [{:keys [conns]}]
  (str
    (h/html
      [:html
       [:head
        [:title "Flow Chart"]
        [:script (h/raw (slurp (io/resource "public/assets/js/vendor/leader-line.min.js")))]
        [:div#flow-data {:style "display: none;" :data-connections (h/raw (connections-to-json conns))}]
        [:script (h/raw "
window.addEventListener('load', function() {
  const dataEl = document.getElementById('flow-data');
  const connectionsJSON = dataEl.getAttribute('data-connections');
  const connections = JSON.parse(connectionsJSON);
  function drawConnections() {
    setTimeout(() => {
      connections.forEach(conn => {
        const outSocketId = `:${conn.from.proc}-:${conn.from.port}`;
        const inSocketId = `:${conn.to.proc}-:${conn.to.port}`;
        const outSocketEl = document.getElementById(outSocketId);
        const inSocketEl = document.getElementById(inSocketId);
        const line = new LeaderLine(
          outSocketEl,
          inSocketEl,
          {color: '#52606D',
           size: 3,
           startSocket: 'bottom',
           endSocket: 'top',
           path: 'grid',
           // hide: true,
           animOptions: {duration: 1000, timing: 'ease'}});
        // line.show('draw');
      });
    }, 500);
  }
  drawConnections();});")]
        [:style "html,\nbody {\n  margin: 0;\n  padding: 0;\n}\n\nbody {\n  height: 100vh;\n  width: 100vw;\n  margin-top: 50px;\n  background-color: #CBD2D9;\n}\n\n.row-3 {\n  display: flex;\n  flex-direction: row;\n  justify-content: center;\n  gap: 10px;\n  align-items: center;\n}\n\n.card-container {\n  display: flex;\n  flex-direction: column;\n  align-items: center;\n  margin-bottom: 40px;\n  min-width: 220px;\n}\n.card-container .proc-card {\n  background: #F5F7FA;\n  border-radius: 3px;\n  width: 100%;\n  display: inline-block;\n  position: relative;\n  transition: all 0.4s ease-in-out;\n  will-change: height;\n  margin-bottom: 55px;\n}\n.card-container .proc-card.expanded .expanded-view {\n  max-height: 500px;\n  opacity: 1;\n  visibility: visible;\n}\n.card-container .proc-card .expanded-view {\n  transition: all 0.4s ease-in-out;\n  max-height: 0;\n  opacity: 0;\n  overflow: hidden;\n  visibility: hidden;\n}\n.card-container .proc-card .expanded-view .header-labels {\n  display: flex;\n  justify-content: center;\n  padding: 0 15px;\n}\n.card-container .proc-card .expanded-view .header-labels .header-label {\n  font-size: 1.75em;\n  font-weight: 500;\n  color: #4a4a4a;\n  text-align: center;\n  width: 150px;\n}\n.card-container .proc-card .expanded-view .middle-section-one-container {\n  box-sizing: border-box;\n  background: #52606D;\n  color: #E4E7EB;\n  border-radius: 2px;\n  position: relative;\n  padding: 10px 0;\n  width: calc(100% - 20px);\n  margin: auto;\n}\n.card-container .proc-card .expanded-view .title-container {\n  text-align: center;\n}\n.card-container .proc-card .expanded-view .title-container .title {\n  font-size: 2.3em;\n  font-weight: 600;\n  margin: 0;\n  color: white;\n}\n.card-container .proc-card .output-section {\n  width: 100%;\n  display: flex;\n  flex-direction: column;\n  align-items: center;\n  box-sizing: border-box;\n}\n.card-container .proc-card .output-section .output-container {\n  display: flex;\n  flex-direction: row;\n  gap: 15px;\n  justify-content: center;\n  align-items: center;\n  width: calc(100% - 14px);\n  margin: 0 7px;\n  padding: 0 0;\n  box-sizing: border-box;\n}\n.card-container .proc-card .output-section .output-container .output {\n  flex: 1;\n  min-width: 110px;\n  padding: 0 8px;\n  font-size: 1.75em;\n  color: #4a4a4a;\n  text-align: center;\n  height: 40px;\n  display: flex;\n  justify-content: center;\n  align-items: center;\n  white-space: nowrap;\n}\n"]]
       [:body
        (chart conns)]])))

(defn graph [flow-config]
  (tagged-literal 'flare/html {:html (template flow-config)
                               :title "Flow Chart"}))