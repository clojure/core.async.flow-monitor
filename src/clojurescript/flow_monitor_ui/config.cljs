(ns clojurescript.flow-monitor-ui.config)

;; DEBUG is special. It is always defined but we override it
(def debug? ^boolean goog.DEBUG)

;; All other `goog-define` use the following syntax:
(goog-define alert-timeout-ms 3000)

;; ====
(defonce websocket-uri-base "localhost:")
(defonce websocket-uri-route "/flow-socket")
