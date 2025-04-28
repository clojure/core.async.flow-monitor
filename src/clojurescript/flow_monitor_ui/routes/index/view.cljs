(ns clojurescript.flow-monitor-ui.routes.index.view
  (:require [goog.string :as gstring]
            [cljs.pprint :as pprint]
            [clojure.string :as str]
            [reagent.core :as r]
            [clojurescript.flow-monitor-ui.components.modal :as component]
            [clojurescript.flow-monitor-ui.components.log-modal :refer [log-modal]]
            [clojurescript.flow-monitor-ui.global :refer [lines remove-arrows
                                                          draw make-websocket!
                                                          global-state global-pings
                                                          send-socket-data proc-card-state
                                                          chan-representation]]
            [clojurescript.flow-monitor-ui.components.nav :refer [settings-bar collapse-elements-with-delay]]
            [clojurescript.flow-monitor-ui.utils.helpers :refer [<sub >dis]]))


; = Data Shaping Functions =====================================================
(defn titleize-keyword [kw]
  (as-> (name kw) $
        (clojure.string/replace $ #"-" " ")
        (clojure.string/split $ #"\s+")
        (map clojure.string/capitalize $)
        (clojure.string/join " " $)))

(defn format-number [n]
  (if n
    (.format (js/Intl.NumberFormat. "en-US") n)
    "--"))

(defn flow-relationships [data]
  (reduce (fn [res [[from-proc _] [to-proc _]]]
            (-> res
                (update-in [from-proc :from] (fnil conj #{}))
                (update-in [from-proc :to] (fnil conj #{}) to-proc)
                (update-in [to-proc :from] (fnil conj #{}) from-proc)
                (update-in [to-proc :to] (fnil conj #{}))))
          {} data))

(defn assoc-ping-data [relationships ping]
  (reduce-kv (fn [res proc data]
               (-> res
                   (assoc-in [proc :status] (:clojure.core.async.flow/status data))
                   (assoc-in [proc :count] (:clojure.core.async.flow/count data))
                   (assoc-in [proc :ins] (:clojure.core.async.flow/ins data))
                   (assoc-in [proc :outs] (:clojure.core.async.flow/outs data))
                   (assoc-in [proc :state] (:clojure.core.async.flow/state data))
                   (assoc-in [proc :ins :in :put-count] (-> data :clojure.core.async.flow/ins :in :put-count))
                   (assoc-in [proc :ins :in :take-count] (-> data :clojure.core.async.flow/ins :in :take-count))
                   (assoc-in [proc :ins :in :buffer :type] (-> data :clojure.core.async.flow/ins :in :buffer :type))
                   (assoc-in [proc :ins :in :buffer :count] (-> data :clojure.core.async.flow/ins :in :buffer :count))
                   (assoc-in [proc :ins :in :buffer :capacity] (-> data :clojure.core.async.flow/ins :in :buffer :capacity))
                   (assoc-in [proc :outs :out :put-count] (-> data :clojure.core.async.flow/outs :in :put-count))
                   (assoc-in [proc :outs :out :take-count] (-> data :clojure.core.async.flow/outs :in :take-count))
                   (assoc-in [proc :outs :out :buffer :type] (-> data :clojure.core.async.flow/outs :out :buffer :type))
                   (assoc-in [proc :outs :out :buffer :count] (-> data :clojure.core.async.flow/outs :out :buffer :count))
                   (assoc-in [proc :outs :out :buffer :capacity] (-> data :clojure.core.async.flow/outs :out :buffer :capacity))
                   ))
             relationships ping))

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

; = Components =================================================================

(defn animate-leader-line
  [id {:keys [target-color target-width duration easing]
       :or {duration 300
            easing "ease-in-out"}}]
  (let [svg-el (js/document.getElementById id)
        line-shape (when svg-el
                     (.querySelector svg-el "use[id$='-line-shape']"))
        line-face (when svg-el
                    (.querySelector svg-el ".leader-line-plugs-face"))
        stroke-el (when svg-el
                    (.querySelector svg-el "g > use[style*='stroke:']"))]

    (when (and svg-el line-shape)
      (when target-width
        (set! (.. line-shape -style -transition)
              (str "stroke-width " duration "ms " easing))
        (set! (.. line-shape -style -strokeWidth) (str target-width "px"))
        (when line-face
          (set! (.. line-face -style -transition)
                (str "stroke-width " duration "ms " easing))
          (set! (.. line-face -style -strokeWidth) (str target-width "px"))))
      (when (and target-color stroke-el)
        (set! (.. stroke-el -style -transition)
              (str "stroke " duration "ms " easing))
        (set! (.. stroke-el -style -stroke) target-color)
        (let [markers (.querySelectorAll svg-el "marker g use[style*='fill:']")]
          (doseq [marker (array-seq markers)]
            (set! (.. marker -style -transition)
                  (str "fill " duration "ms " easing))
            (set! (.. marker -style -fill) target-color)))))))

(defn ws-connect-btn []
  [:div.centered-button-container
   [:button.button
    {:on-click (fn [e]
                 (make-websocket!)
                 (js/setTimeout (fn []
                                  (draw)) 1800))}
    "Flow Connect"]])

(defn log-scale [x]
  (if (zero? x)
    0
    (/ (js/Math.log2 (inc x)) 10)))

(defn channel-meter-value [put-count take-count]
  (cond
    (pos? take-count) (- (log-scale take-count))
    (pos? put-count)  (log-scale put-count)
    :else 0))

(defn set-meter-value [id value]
  (let [left-cover (.getElementById js/document (str "left-cover-" id))
        right-cover (.getElementById js/document (str "right-cover-" id))]
    (when left-cover
      (if (neg? value)
        (do
          (set! (.. left-cover -style -width) (str (+ 50 (* 50 value)) "%"))
          (set! (.. right-cover -style -width) "50%"))
        (do
          (set! (.. right-cover -style -width) (str (- 50 (* 50 value)) "%"))
          (set! (.. left-cover -style -width) "50%"))))))


(defn diverging-meter [id put-count take-count]
  (let [meter-value (channel-meter-value put-count take-count)]
    (r/create-class
      {:component-did-mount (fn [_] (set-meter-value id meter-value))
       :component-did-update (fn [this old-argv]
                               (let [[_ _ old-put old-take] old-argv
                                     [_ _ new-put new-take] (r/argv this)]
                                 (when (or (not= new-put old-put) (not= new-take old-take))
                                   (set-meter-value id (channel-meter-value new-put new-take)))))
       :reagent-render (fn [id put-count take-count]
                         [:div.meter-wrapper
                          [:div.label-container
                           [:div "Take: " take-count]
                           [:div "Put: " put-count]]
                          [:div.diverging-meter-container
                           [:div.meter-left]
                           [:div.meter-right]
                           [:div.meter-cover-left {:id (str "left-cover-" id)}]
                           [:div.meter-cover-right {:id (str "right-cover-" id)}]
                           [:div.meter-center]]])})))

(defn meter-card [proc in]
  (let [in-name (first in)
        in-stats (second in)
        in-put-count (-> in-stats :put-count)
        in-take-count (-> in-stats :take-count)
        in-buffer-count (format-number (-> in-stats :buffer :count))
        in-buffer-cap (format-number (-> in-stats :buffer :capacity))
        uid (str proc "-" in-name)]
    [:div.meter-card {:id (str proc "-" in-name "-in-chan")}
     [:div.buffer-info (or (-> in-stats :buffer :type) "Connecting") (str ": " in-buffer-count " / " in-buffer-cap)]
     [:div.meter-container
      [:div.meter {:style {:width (str (- 100 (* 100 (/ (js/parseInt in-buffer-count) (js/parseInt in-buffer-cap)))) "%")}}]]
     #_ [diverging-meter uid (or in-put-count 0) (or in-take-count 0)]]))

(defn escape-html [text]
  (-> text
      (str/replace #"&" "&amp;")
      (str/replace #"<" "&lt;")
      (str/replace #">" "&gt;")))

(defn fmt-state
  [data]
  (letfn [(format-map [m indent-level]
            (if (empty? m)
              "{}"
              (let [indent (str/join (repeat indent-level "  "))
                    inner-indent (str indent "  ")
                    pairs (for [[k v] m]
                            (str inner-indent (pr-str k) " "
                                 (if (map? v)
                                   (format-map v (+ indent-level 1))
                                   (pr-str v))))
                    formatted (str "{\n" (str/join ",\n" pairs) "\n" indent "}")]
                formatted)))]
    (if (map? data)
      (format-map data 0)
      (pr-str data))))

(defn seconds-since [^js/Date t]
  (let [now (js/Date.)
        diff (- (.getTime now) (.getTime t))]
    (Math/floor (/ diff 1000))))

(defn proc-card [proc proc-stats]
  (let [errors (-> @global-state :errors proc)
        last-updated (:last-updated (proc (:flow-ping @global-pings)))
        since-last-updated (if last-updated (seconds-since last-updated) 0)
        paused? (= :paused (:status proc-stats))]
    [:div.middle-section-one-container
     [:div.title-container [:h2.title (titleize-keyword proc)]]
     (when (:state proc-stats)
       [:div.state [:pre.code-block [:code (fmt-state (:state proc-stats))]]])
     [:div.call-count (format-number (:count proc-stats))]
     [:div.action-buttons
      [:div.action-button
       {:on-click (fn [evt]
                    (swap! global-state assoc :active-tab :inject)
                    (swap! global-state assoc :active-proc-pid proc)
                    (.add (.-classList (.-body js/document)) "modal-open")
                    (>dis [::component/set-modal-visibility true]))}
       [:img {:src "assets/img/inject_icon.svg"}]]
      [:div.action-button
       {:class (when (-> errors count zero? not) "error")
        :on-click (fn [evt]
                    (swap! global-state assoc :active-tab :errors)
                    (swap! global-state assoc :active-proc-pid proc)
                    (.add (.-classList (.-body js/document)) "modal-open")
                    (>dis [::component/set-modal-visibility true]))}
       [:img {:src (if (-> errors count zero? not) "assets/img/error_icon_red.svg" "assets/img/error_icon.svg")}]
       (when (-> errors count zero? not) [:div {:style {:margin-left "5px" :color "#E12D39" :padding-right "5px"}} (count errors)])]
      [:div.action-button
       {:on-click (fn [e]
                    (if (= :running (:status proc-stats))
                      (send-socket-data {:action :pause-proc :pid proc})
                      (send-socket-data {:action :resume-proc :pid proc})))}
       [:img {:src (if paused? "assets/img/pause_icon_orange.svg" "assets/img/pause_icon_white.svg")}]]]
     (when (> since-last-updated 0) [:div.stale "Last Updated: " since-last-updated " seconds ago."])]))

(defn buffer-usage-percentage [buffer-count buffer-capacity]
  (* 100 (/ (js/parseInt buffer-count) (js/parseInt buffer-capacity))))

(defn out-chan-card [proc io-id buffer-stats]
  (let [out-put-count (:put-count buffer-stats)
        out-take-count (:take-count buffer-stats)
        buffer-type (-> buffer-stats :buffer :type)
        buffer-count (-> buffer-stats :buffer :count)
        buffer-capacity (-> buffer-stats :buffer :capacity)
        uid (str proc "-" io-id)]
    [:div.output-card {:id (str proc "-" io-id "-out-chan")}
     [:div.buffer-info (or buffer-type "Connecting") (str ": " buffer-count " / " buffer-capacity)]
     [:div.meter-container
      [:div.meter {:style {:width (str (- 100 (buffer-usage-percentage buffer-count buffer-capacity)) "%")}}]]
     #_[diverging-meter uid (or out-put-count 0) (or out-take-count 0)]]))

(defn update-arrows [proc ins outs]
  (doall (for [^js/LeaderLine line @lines]
           (.position (second line))))
  (doall (for [in ins]
           (let [in-name (first in)
                 in-stats (second in)
                 in-buffer-count (format-number (-> in-stats :buffer :count))
                 in-buffer-cap (format-number (-> in-stats :buffer :capacity))
                 ^js/LeaderLine line (get @lines (str proc "-" in-name))]
             ;(when line
             ;  (set! (.-endLabel line)
             ;        (js/LeaderLine.captionLabel
             ;          (str in-buffer-count "/" in-buffer-cap)
             ;          (clj->js {:color "#52606D"
             ;                    :outlineColor "#CBD2D9"}))))
             )))
  (doall (for [[io-id buffer-stats] outs]
           (let [out-buffer-count (format-number (-> buffer-stats :buffer :count))
                 out-buffer-cap (format-number (-> buffer-stats :buffer :capacity))
                 percentage-used (buffer-usage-percentage out-buffer-count out-buffer-cap)
                 ^js/LeaderLine line (get @lines (str proc "-" io-id))]
             (when line
               (set! (.-startLabel line)
                     (js/LeaderLine.captionLabel
                       (str out-buffer-count "/" out-buffer-cap)
                       (clj->js {:color "#52606D"
                                 :outlineColor "#CBD2D9"})))
               (let [[size color] [(max 3 (/ percentage-used 10))
                                   (case percentage-used
                                     0 "#014D40"
                                     10 "#014D40"
                                     20 "#014D40"
                                     30 "#014D40"
                                     40 "#F0B429"
                                     50 "#F0B429"
                                     60 "#F0B429"
                                     70 "#F0B429"
                                     80 "#E12D39"
                                     90 "#E12D39"
                                     100 "#E12D39")]]
                 (animate-leader-line (str proc "-" io-id "-svg") {:target-color color
                                                                   :target-width size
                                                                   :duration 500})))))))


(defn proc-el [proc-map]
  (fn [proc-map]
    (let [proc (-> proc-map keys first)
          expanded? (get @proc-card-state proc)
          proc-stats (-> proc-map vals first)
          ins (:ins proc-stats)
          outs (:outs proc-stats)
          paused? (= :paused (:status proc-stats))]
      (update-arrows proc ins outs)
      [:div.card-container {:id (name proc)
                            :class (when (= :line @chan-representation) "line-chan-style")}
       ; TODO Conditional upon having a distinct channel id
       #_ [:div.meter-cards
           (doall (for [in ins
                        :when (-> in second :buffer :type)]
                    (let [in-name (first in)]
                      ^{:key in-name} [meter-card proc in])))]
       [:div.proc-card {:class (str (if expanded? "expanded" "collapsed"))}
        (if paused?
          [:div.dot-pattern.animate__animated.animate__fadeIn]
          [:div.dot-pattern.animate__animated.animate__fadeOut])
        [:div.chevron-icon
         {:on-click (fn [e]
                      (swap! proc-card-state update proc not)
                      (remove-arrows)
                      (js/setTimeout (fn []
                                       (draw)) 400))}
         (if expanded?
           [:img {:src "assets/img/chevron_down.svg"}]
           [:img.up {:src "assets/img/chevron_up.svg"}])]

        [:div.expanded-view
         [:div.header-labels
          (doall (for [[io-id buffer-stats] ins
                       :when (:put-count buffer-stats)]
                   ^{:key io-id} [:div.header-label {:id (str proc "-" io-id)} io-id]))]
         [proc-card proc proc-stats]
         [:div.output-section
          [:div.output-container
           (doall (for [[io-id buffer-stats] outs
                        :when (-> buffer-stats :buffer :type)]
                    ^{:key (str proc "-" io-id)} [:div.output {:id (str proc "-" io-id)} io-id]))]]]
        [:div.collapsed-view-container
         [:div.header-els
          (doall (for [[io-id buffer-stats] ins
                       :when (:put-count buffer-stats)]
                   ^{:key io-id} [:div.header-label {:id (str proc "-" io-id "-collapsed")}]))]
         [:div.collapsed-view {:id (str proc "-collapsed")}
          [:div.title-container [:h2.title proc]]
          #_ [:div.action-buttons
              [:div.action-button [:img {:src "assets/img/inject_icon.svg"}]]
              [:div.action-button [:img {:src "assets/img/message_icon.svg"}]]
              [:div.action-button [:img {:src "assets/img/error_icon.svg"}]]]]
         [:div.output-section-collapsed
          [:div.output-container
           (doall (for [[io-id buffer-stats] outs
                        :when (-> buffer-stats :buffer :type)]
                    ^{:key (str proc "-" io-id)} [:div.output {:id (str proc "-" io-id "-collapsed")}]))]]]]
       [:div.output-cards.animate__animated.collapsible-meter
        {:class (if (= :meter @chan-representation)
                  "animate__fadeInDown"
                  "animate__fadeOutUp")}
        (doall (for [[io-id buffer-stats] outs
                     :when (-> buffer-stats :buffer :type)]
                 ^{:key (str proc "-" io-id)} [out-chan-card proc io-id buffer-stats]))]])))

(defn proc-row [idx row]
  [:div.row-3
   (for [proc row]
     ^{:key (-> proc keys first)} [proc-el proc])])

(defn chart []
  (let [data (:data @global-state)
        relationships (flow-relationships (:conns data))
        relationships* (assoc-ping-data relationships (-> @global-pings :flow-ping))]
    [:div#chart
     (when data (for [[idx row] (map-indexed vector (flow-levels relationships*))]
                  ^{:key (str "chart-" idx)} [proc-row idx row]))]))

; = Template ===================================================================
(defn template []
  [:<>
   (when (:ws-connected @global-state)
     [settings-bar])
   [:div
    [component/report-modal]
    [log-modal]
    (if (:ws-connected @global-state)
      [chart]
      [ws-connect-btn])]])
