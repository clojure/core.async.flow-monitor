# Flow Monitor

A real-time monitoring and interaction tool for [clojure.core.async.flow](https://clojure.github.io/core.async/flow.html)

<img width="2189" alt="flow-example-gif" src="https://github.com/user-attachments/assets/ddef50e1-ccb0-43cb-8dd2-f52b9e69bb55">

## Overview

Flow Monitor provides a web-based interface for visualizing, monitoring, and interacting with clojure.core.async.flow

With Flow Monitor, you can observe the structure of your flow, track the state of processes and the async channels between them, control process execution by pausing and resuming them, inject data into channels, and view any errors or messages that emerge from the flow.


## Installation

Add the following dependency to your project:

```clojure
;; deps.edn
{:deps {org.clojure/core.async.flow-monitor {:git/url "https://github.com/clojure/core.async.flow-monitor" 
                                             :sha "..."}}}
```

## Usage

### Starting a Monitor Server

```clojure
(:require 
  [clojure.core.async.flow-monitor :as monitor]
  [clojure.core.async.flow :as flow])

;; Create a flow
(def my-flow (flow/create-flow ...))

;; Start the monitoring server
(def server-state (monitor/start-server {:flow my-flow :port 9876}))

;; The web interface will be available at:
;; http://localhost:9876/index.html#/?port=9876
```

## Custom Transit Handlers

You can provide custom Transit write handlers to properly serialize types that aren't natively supported by Transit (used for visualizing state). These handlers should follow the format expected by cognitect.transit/writer :handlers. If not provided, the default handler will be used, which converts objects to strings.

```clojure
(:require
  [clojure.core.async.flow-monitor :as monitor]
  [clojure.core.async.flow :as flow]
  [cognitect.transit :as transit]
  [java.time :as time])
(:import
  [java.time Instant ZoneId]
  [java.time.format DateTimeFormatter])

(def my-flow (flow/create-flow ...))

(defn format-instant [instant]
  (.format
    (.withZone
      (DateTimeFormatter/ofPattern "MMMM d, yyyy")
      (ZoneId/systemDefault))
    instant))

(def instant-write-handler
  (transit/write-handler "instant-long"
                         (fn [instant] (format-instant instant))))

(def server-state (monitor/start-server {:flow my-flow
                                         :port 9876
                                         :handlers {Instant instant-write-handler}}))
```

### Stopping the Server

```clojure
(monitor/stop-server server-state)
```

### Multiple Monitors

You can run multiple monitoring servers simultaneously to monitor different flows:

```clojure
(def server1 (monitor/start-server {:flow flow1 :port 9876}))
(def server2 (monitor/start-server {:flow flow2 :port 9877})) ; unique unused port

;; Stop them independently
(monitor/stop-server server1)
(monitor/stop-server server2)
```

## License

Copyright © 2025

Distributed under the Eclipse Public License v 1.0