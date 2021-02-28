#!/usr/bin/env bb

(ns script
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [hiccup.core :as hiccup]
            [org.httpkit.server :as srv]))

(def locations
  (json/parse-string (slurp "locations.json") true))

(def copy
  {:en {:title "New York State COVID-19 Vaccine Availability Notifications"
        :locations-header "Check the locations about which you’d like to be notified"
        :asterisk-note "Indicates locations for which eligibility is restricted by residency"
        :enter-email "Enter the email address at which you’d like to be notified when the checked locations have new availability"}
   :es {:title "Notificaciones de disponibilidad de la vacuna COVID-19 del estado de Nueva York"
        :locations-header "Comprueba las ubicaciones sobre las que te gustaría recibir notificaciones."
        :asterisk-note "Indica lugares para los que la elegibilidad está restringida por residencia"
        :enter-email "Ingrese la dirección de correo electrónico en la que desea recibir una notificación cuando las ubicaciones marcadas tengan nueva disponibilidad"}})

(defn homepage
  [language]
  (let [{:keys [title locations-header asterisk-note enter-email]} (get copy language)]
    (hiccup/html
    "<!DOCTYPE html>\n"
    [:html
      [:head
        [:meta {:charset "UTF-8"}]
        [:title title]]
      [:body
        [:header
          [:h1 title]]
      
        [:form {:method :POST, :action "TBD"}
        [:h3 locations-header]
        [:p "** " asterisk-note]
        
        (for [{:keys [providerName address]} (:providerList locations)]
          [:div
            [:label
            [:input {:type :checkbox, :name :locations, :value providerName}]
            " "
            providerName " (" address ")"]])
        
        [:label
          [:h3 enter-email]
          [:input {:type :email, :name :email}]]
        
        [:div
          [:input {:type :submit}]]
         
        [:footer
         [:p
          "Site created by "
          [:a {:href "mailto:avi@aviflax.com"} "Avi Flax"]
          " with data from "
          [:a {:href "https://am-i-eligible.covid19vaccine.health.ny.gov"}
           "https://am-i-eligible.covid19vaccine.health.ny.gov"]]]]]])))

(defn app [req]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    (homepage :en)})

(println locations)

(def port 8080)

(println "Starting HTTP server listening on port" port)
(srv/run-server app {:port port})

;; Prevent Babashka from exiting
@(promise)
