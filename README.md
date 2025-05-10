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
{:deps {io.github.clojure/core.async.flow-monitor {:git/tag "v0.1.0" :git/sha "9d02164"}}}
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

### Custom Transit Handlers

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

### Filtering Proc State

It is common for the state to contain credentials and other sensitive information not intended to be displayed in the monitor. Filter predicates can optionally be provided when starting the monitor to filter the state data of flow procs.

``` clojure 
;; Create a flow
(def my-flow (flow/create-flow ...))

;; Start the monitoring server
;;; In this example
;;; The :categorize proc will have the :salary value removed
;;; and :db-pass will be removed from all procs other than :categorize
(def server-state (monitor/start-server {:flow my-flow 
                                         :state-filters {:categorize (fn [[k v]] (not= :salary k))
                                                         :default (fn [[k v]] (not= :db-pass k))}}))
```


### Circular Flows

It is valid to create a cyclical flow. The monitor displays procs without a `[to-pid inid]` at the top as roots. A circular flow will need to specify the intended root explicitly. A vector of :pid keywords can optionally be provided when starting the monitor to designate the root procs.

``` clojure 

(def flow-config {:procs ...
                  :conns [[[:a :out] [:b :in]]
                          [[:b :out] [:a :in]]]})
                          
;; Create a flow
(def my-flow (flow/create-flow flow-config))

;; Start the monitoring server specifying the root proc(s)
(def server-state (monitor/start-server {:flow my-flow
                                         ;; Multiple roots can be provided if desired
                                         :root [:a]})) 
                                         
;; Inline static flow graphs also require the root to be specified for circular flows
(static/graph flow-config [:a])                                     
```

## Static Flow Graph

Both [Cursive](https://cursive-ide.com/blog/cursive-2025.1.html) and [Calva](https://calva.io/flares/) support displaying HTML in the editor. A static graph can be generated from your flow-config and displayed in either of those editor environments with the following:

``` clojure 
(:require 
  [clojure.core.async.flow-static :refer [graph]]
  [clojure.core.async.flow :as flow])

;; Create a flow
(def flow-config {:procs ... 
                  :conns ...})
                  
(graph flow-config) ; Takes a config not what is returned from create-flow

(def my-flow (flow/create-flow flow-config))
```

## Contributing

[Contributing to Clojure projects](https://clojure.org/community/contributing) requires a signed Contributor Agreement. Pull requests and GitHub issues are not accepted; please use the [core.async.flow-monitor JIRA project](https://clojure.atlassian.net/browse/AFMON) to report problems or enhancements.

## License

Copyright © 2025

Distributed under the Eclipse Public License v 1.0
