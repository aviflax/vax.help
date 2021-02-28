#!/usr/bin/env bb

(ns script
  (:require [org.httpkit.server :as srv]))

(defn app [req]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    "hello HTTP!"})

(def port 8080)

(println "Starting HTTP server listening on port" port)
(srv/run-server app {:port port})

;; Prevent Babashka from exiting
@(promise)
