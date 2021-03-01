#!/usr/bin/env bb

(ns script
  (:require [babashka.pods :as pods]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hiccup.core :as hiccup]
            [org.httpkit.server :as srv]))

(pods/load-pod 'org.babashka/postgresql "0.0.1")

(require '[pod.babashka.postgresql :as pg])

(defn env!
  "Throws if a the environment variable is missing or blank."
  [vn]
  (let [vv (System/getenv vn)]
    (if (or (not vv)
            (str/blank? vv))
      (throw (RuntimeException. (format "Required environment variable %s not found." vn)))
      vv)))

(defn build-config!
  "Throws if a required environment variable is missing or blank."
  []
  {:db {:name     (env! "DB_NAME")
        :host     (env! "DB_HOST")
        :port     (env! "DB_PORT")
        :username (env! "DB_USERNAME")
        :password (env! "DB_PASSWORD")}})

(def config (build-config!))

(defn cv
  [first-key & more-keys]
  (get-in config (cons first-key more-keys)))

(def dbconn (atom nil))

;; TODO: this is basically a hacky, crappy DB connection pool. So let’s use a real pooling library
;; that’ll handle auto-reconnects and other aspects of connection management for us, properly.
(defn ensure-dbconn!
  "Ensures we have a working and active DB connection. We probably only want to invoke this if/when
   we get a PSQLException when trying to do something meaningful. Meaning we especially don’t want
   to invoke this for every page load."
  []  
  (try
    (pg/execute! @dbconn ["select version()"])
    (catch Exception e
      (swap! dbconn (fn [cur-conn]
                      (try
                        (pg/execute! cur-conn ["select version()"])
                        cur-conn
                        (catch Exception e
                          (pg/get-connection {:dbtype   "postgresql"
                                              :host     (cv :db :host)
                                              :dbname   (cv :db :name)
                                              :user     (cv :db :username)
                                              :password (cv :db :password)
                                              :port     (cv :db :port)}))))))))

(def locations
  (json/parse-string (slurp "locations.json") true))

(def supported-languages
  {:en "English"
   :es "Español"})

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
   {:es "con datos de"}
   
   "Receive Notifications via Email"
   {:es "Recibir notificaciones por correo electrónico"}
   
   "Subscription Request Received"
   {:es "Solicitud de suscripción recibida"}

   "We have received your request to subscribe to appointment availability changes for the selected locations."
   {:es "Hemos recibido su solicitud para suscribirse a los cambios de disponibilidad de citas para las ubicaciones seleccionadas."}

   "If all goes well, you will receive a confirmation via email shortly."
   {:es "Si todo va bien, recibirá una confirmación por correo electrónico en breve."}

   "Once you click the link in that email, you’ll be subscribed."
   {:es "Una vez que haga clic en el enlace de ese correo electrónico, estará suscrito."}

   "Good luck!"
   {:es "¡Buena suerte!"}})

(defn translate
  [phrase lang]
  (if (= lang :en)
    (do
      (when-not (contains? copy phrase)
        (println "WARNING: phrase not found in copy map:" phrase))
      phrase)
    (if-let [trans (get-in copy [phrase lang])]
      trans
      (do
        (println "WARNING: missing translation" lang "for" phrase)
        phrase))))

(defn translator
  "Returns a function that will accept a phrase and return its translation in the given lang, if
   any."
  [lang]
  (fn [phrase] (translate phrase lang)))

(defn home-page
  [lang]
  ;; TODO: add client-side form validation, once we’ve tested server-side validation
  (let [t     (translator lang)
        title (t "New York State COVID-19 Vaccine Availability Notifications")]
    (hiccup/html
    "<!DOCTYPE html>\n"
    [:html
      [:head
        [:meta {:charset "UTF-8"}]
        [:title title]
        [:style
         "html, body, select, option { font-size: large }
          
          #locations {
            display: grid;
            grid-template-columns: 1.25em 1fr;
            gap: 1em 0;
          }
                    
          .address { display: block; font-style: italic; }"]]
      [:body
        [:header
          [:h1 title]]
       
        [:form {:method :GET, :action "/"}
         [:select
          {:name :lang, :onchange "this.form.submit()"}
          (for [[lang-code lang-name] supported-languages]
            [:option (merge {:value lang-code}
                            (when (= lang-code lang) {:selected true}))
             lang-name])]]
      
        [:form {:method :POST
                :action (str "/subscribe" (when (not= lang :en)
                                            (str "?lang=" (name lang))))}
        [:h3 (t "Check the locations about which you’d like to be notified")]
        [:p "** " (t "Indicates locations for which eligibility is restricted by residency")]
        
        [:div#locations
         (mapcat identity
          (for [{:keys [providerName address]} (:providerList locations)
                :let [id (str "location-" providerName)]]
            [[:input {:type :checkbox, :name :locations, :value providerName, :id id}]
              [:label {:for id}
              [:span.providerName providerName]
              [:span.address address]]]))]
         
        [:label
          [:h3 (translate "Enter the email address at which you’d like to be notified when the checked locations have new availability" lang)]
          [:input {:type :email, :name :email}]]
        
        [:div
          [:input {:type :submit
                   :value (translate "Receive Notifications via Email" lang)}]]
         
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

(defn subscribe
  [req lang]
  ;; TODO: enqueue a subscription request in the DB (or elsewhere)
  {:status 303
   :headers {"Location" (str "/received" (when (not= lang :en)
                                           (str "?lang=" (name lang))))}})

(defn received-page
  [lang]
  (let [t     (translator lang)
        title (t "New York State COVID-19 Vaccine Availability Notifications")]
    (hiccup/html
    "<!DOCTYPE html>\n"
    [:html
      [:head
        [:meta {:charset "UTF-8"}]
        [:title title]]
      [:body
        [:header
          [:h1 title]]
        [:h2 (t "Subscription Request Received")]
        [:p (t "We have received your request to subscribe to appointment availability changes for the selected locations.")]
        [:p (t "If all goes well, you will receive a confirmation via email shortly.")]
        [:p (t "Once you click the link in that email, you’ll be subscribed.")]
        [:p [:b (t "Good luck!")]]]])))

(defn which-lang
  [req]
  (let [lang-header (some-> (get-in req [:headers "accept-lang"])
                            (str/split #",|-")
                            (first))
        qs          (get req :query-string "")]
    (if (or (= lang-header "es")
            (str/includes? (or qs "") "lang=es"))
      :es
      :en)))

(defn handle-get
  [req page-fn]
  (if (not= (:request-method req) :get)
    {:status 405, :body "Method not allowed"}
    (let [lang (which-lang req)]
      {:status  200
       :headers {"Content-Type" "text/html; charset=UTF-8", "Content-Language" lang}
       :body    (page-fn lang)})))

(defn handle-post
  [req handler-fn]
  (if (not= (:request-method req) :post)
    {:status 405, :body "Method not allowed"}
    (let [lang (which-lang req)
          response (handler-fn req lang)]
      (assoc-in response "Content-Language" lang))))

(defn handle-not-found
  [req log?]
  (when log?
    (println "WARNING: no handler found for path" (:uri req)))
  {:status 404, :body "Not Found"})

(def routes
  {"/"            (fn [req] (handle-get req (memoize home-page)))
   "/healthz"     (constantly {:status 200, :body "OK"})
   "/subscribe"   (fn [req] (handle-post req subscribe))
   "/received"    (fn [req] (handle-get req (memoize received-page)))
   "/favicon.ico" (fn [req] (handle-not-found req false))})

(defn app [req]
  (if-let [handler (get routes (:uri req))]
    (handler req)
    (handle-not-found req true)))

(def port 8080)

(println "Starting HTTP server listening on port" port)
(srv/run-server app {:port port})

;; Prevent Babashka from exiting
@(promise)
