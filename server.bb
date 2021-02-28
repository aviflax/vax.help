#!/usr/bin/env bb

(ns script
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [hiccup.core :as hiccup]
            [org.httpkit.server :as srv]))

(def locations
  (json/parse-string (slurp "locations.json") true))

(def homepage
  (hiccup/html
   [:html
    [:head
        [:title "vaxavailability.help"]]
    [:body
        [:h1 "vaxavailability.help"]
        (for [{:keys [providerName address]} (:providerList locations)]
          [:div [:label providerName " (" address ")"]])]]))

(defn app [req]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    homepage})

(println locations)

(def port 8080)

(println "Starting HTTP server listening on port" port)
(srv/run-server app {:port port})

;; Prevent Babashka from exiting
@(promise)
