#!/usr/bin/env bb

(ns script
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hiccup.core :as hiccup]
            [org.httpkit.server :as srv]))

(def locations
  (json/parse-string (slurp "locations.json") true))

(def copy
  {"New York State COVID-19 Vaccine Availability Notifications"
   {:es "Notificaciones de disponibilidad de la vacuna COVID-19 del estado de Nueva York"}
 
   "Check the locations about which you’d like to be notified"
   {:es "Comprueba las ubicaciones sobre las que te gustaría recibir notificaciones."}

   "Indicates locations for which eligibility is restricted by residency"
   {:es "Indica lugares para los que la elegibilidad está restringida por residencia"}

   "Enter the email address at which you’d like to be notified when the checked locations have new availability"
   {:es "Ingrese la dirección de correo electrónico en la que desea recibir una notificación cuando las ubicaciones marcadas tengan nueva disponibilidad"}
   
   "Site created by"
   {:es "Sitio creado por"}

   "with data from"
   {:es "con datos de"}})

(defn translate
  [phrase lang]
  (get-in copy [phrase lang] phrase))

(defn homepage
  [lang]
  (let [title (translate "New York State COVID-19 Vaccine Availability Notifications" lang)]
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
        [:h3 (translate "Check the locations about which you’d like to be notified" lang)]
        [:p "** " (translate "Indicates locations for which eligibility is restricted by residency" lang)]
        
        (for [{:keys [providerName address]} (:providerList locations)]
          [:div
            [:label
            [:input {:type :checkbox, :name :locations, :value providerName}]
            " "
            providerName " (" address ")"]])
        
        [:label
          [:h3 (translate "Enter the email address at which you’d like to be notified when the checked locations have new availability" lang)]
          [:input {:type :email, :name :email}]]
        
        [:div
          [:input {:type :submit}]]
         
        [:footer
         [:p
          (translate "Site created by" lang)
          " "
          [:a {:href "mailto:avi@aviflax.com"} "Avi Flax"]
          " "
          (translate "with data from" lang)
          " "
          [:a {:href "https://am-i-eligible.covid19vaccine.health.ny.gov"}
           "https://am-i-eligible.covid19vaccine.health.ny.gov"]]]]]])))

(defn which-lang
  [req]
  (let [lang-header (get-in req [:headers "accept-lang"] "")
        first-lang (first (str/split lang-header #",|-"))]
    (if (= first-lang "es")
      :es
      :en)))

(defn app [req]
  (let [lang (which-lang req)]
    {:status  200
    :headers {"Content-Type" "text/html", "Content-Language" lang}
    :body    (homepage lang)}))

(def port 8080)

(println "Starting HTTP server listening on port" port)
(srv/run-server app {:port port})

;; Prevent Babashka from exiting
@(promise)
