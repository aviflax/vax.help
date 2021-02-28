#!/usr/bin/env bb

(ns script
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [hiccup.core :as hiccup]
            [org.httpkit.server :as srv]))

(def locations
  (json/parse-string (slurp "locations.json") true))

(def page-title "New York State COVID-19 Vaccine Availability Notifications")

(def homepage
  (hiccup/html
   "<!DOCTYPE html>\n"
   [:html
    [:head
      [:meta {:charset "UTF-8"}]
      [:title page-title]]
    [:body
      [:h1 page-title]
     
      [:form {:method :POST, :action "TBD"}
       [:h3 "Check the locations about which you’d like to be notified"]
       [:p "** Indicates locations for which eligibility is restricted by residency"]
       
       (for [{:keys [providerName address]} (:providerList locations)]
         [:div
          [:label
           [:input {:type :checkbox, :name :locations, :value providerName}]
           " "
           providerName " (" address ")"]])
       
       [:label
        [:h3 "Enter the email address at which you’d like to be notified when the checked locations have new availability"]
        [:input {:type :email, :name :email}]]
       
       [:div
        [:input {:type :submit}]]]]]))

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
