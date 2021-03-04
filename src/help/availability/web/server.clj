(ns help.availability.web.server
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hiccup.core :as hiccup]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [org.httpkit.server :as srv])
  (:import [java.net URLEncoder URLDecoder]))

(defn logger
  [level]
  (fn [& args] (apply println (cons (str level ":") args))))

(def debug (logger "DEBUG"))
(def info  (logger "INFO"))
(def warn  (logger "WARN"))
(def error (logger "ERROR"))

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
(defn ensure-dbconn
  "Ensures we have a working and active DB connection. We probably only want to invoke this if/when
   we get a PSQLException when trying to do something meaningful. Meaning we especially don’t want
   to invoke this for every page load."
  []  
  (try
    (jdbc/execute! @dbconn "select version()")
    (catch Exception e
      (swap! dbconn (fn [cur-conn]
                      (try
                        (jdbc/execute! cur-conn ["select version()"])
                        cur-conn
                        (catch Exception e
                          (jdbc/get-connection {:dbtype   "postgresql"
                                                :host     (cv :db :host)
                                                :dbname   (cv :db :name)
                                                :user     (cv :db :username)
                                                :password (cv :db :password)
                                                :port     (cv :db :port)}))))))))

;; TODO: change this to a proper cache that will invalidate and refresh after e.g. an hour
(def locations (atom nil))

(def supported-languages
  {:en "English"
   :es "Español"})

(def copy
  {"New York State COVID-19 Vaccine Appointment Availability Notifications"
   {:es "Notificaciones de disponibilidad de citas de vacunas COVID-19 del estado de Nueva York"}
 
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
        (warn "phrase not found in copy map:" phrase))
      phrase)
    (if-let [trans (get-in copy [phrase lang])]
      trans
      (do
        (warn "missing translation" lang "for" phrase)
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
        title (t "New York State COVID-19 Vaccine Appointment Availability Notifications")]
    (hiccup/html
    "<!DOCTYPE html>\n"
    [:html
      [:head
        [:meta {:charset "UTF-8"}]
        [:title title]
        [:style
         "html, body, select, option, input { font-family: Charter, Palatino; font-size: large; }
          
          #locations {
            display: grid;
            grid-template-columns: 1.25em 1fr;
            gap: 1em 0;
          }
                    
          .address { display: block; font-style: italic; }
          
          footer { margin-top: 10em; }"]]
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
          (for [{:keys [:with_current_name/id :with_current_name/name :with_current_name/address]} @locations
                :let [elem-id (str "location-" id)]]
            [[:input {:type :checkbox, :name :locations, :value id, :id elem-id}]
              [:label {:for elem-id}
              [:span.locationName name]
              [:span.address address]]]))]
         
        [:label
          [:h3 (translate "Enter the email address at which you’d like to be notified when the checked locations have new availability" lang)]
          [:input {:type :email, :name :email, :required :required, :title "email address (required)"}]]
        
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

;; copied from https://github.com/ring-clojure/ring-codec/blob/master/src/ring/util/codec.clj
(defn assoc-conj
  "Associate a key with a value in a map. If the key already exists in the map,
  a vector of values is associated with the key."
  [map key val]
  (assoc map key
         (if-let [cur (get map key)]
           (if (vector? cur)
             (conj cur val)
             [cur val])
           val)))

;; copied from https://github.com/ring-clojure/ring-codec/blob/master/src/ring/util/codec.clj
(defn- form-decode-str
  "Decode the supplied www-form-urlencoded string using the specified encoding,
  or UTF-8 by default."
  ([encoded]
   (form-decode-str encoded "UTF-8"))
  ([^String encoded ^String encoding]
   (try
     (URLDecoder/decode encoded encoding)
     (catch Exception _ nil))))

;; copied from https://github.com/ring-clojure/ring-codec/blob/master/src/ring/util/codec.clj
(defn- form-decode
  "Decode the supplied www-form-urlencoded string using the specified encoding,
  or UTF-8 by default. If the encoded value is a string, a string is returned.
  If the encoded value is a map of parameters, a map is returned."
  ([encoded]
   (form-decode encoded "UTF-8"))
  ([^String encoded encoding]
   (if-not (.contains encoded "=")
     (form-decode-str encoded encoding)
     (reduce
      (fn [m param]
        (let [[k v] (str/split param #"=" 2)
              k     (form-decode-str k encoding)
              v     (form-decode-str (or v "") encoding)]
          (if (and k v)
            (assoc-conj m k v)
            m)))
      {}
      (str/split encoded #"&")))))

(defn validate-subscribe-request
  "Returns an error response as a Ring response map, or nil."
  [{locations "locations", email "email" :as posted-form}]
  (when (or (not (seq locations))
            (str/blank? email)) ; TODO: make this more robust. Maybe with a good old regex!
    {:status  400
     :headers {"Content-Type" "text/plain"}
     :body    (str "400 Bad Request"
                   "\n\nDid you check at least one location?"
                   "\n\nPlease go back and try again.")}))

(defn save-subscription
  [{email "email", locations "locations" :as _posted-form} lang]
  (jdbc/with-transaction [tx @dbconn]
    (let [[{id :subscriptions/id}]
          (jdbc/execute! tx ["insert into subscription.subscriptions (email, language, nonce)
                            values (?, ?, ?)"
                           email (name lang) "TODO: THIS IS NOT ACTUALLY A NONCE"]
                       {:return-keys true})]
      (jdbc/execute! tx ["insert into subscription.state_changes (subscription_id, state)
                        values (?, cast(? as subscription.state))"
                       id "new"])
      (doseq [loc-id (if (coll? locations) ; if only one box is checked the value will be a scalar
                         locations
                         [locations])]
        (jdbc/execute! tx ["insert into subscription.locations (subscription_id, location_id)
                          values (?, ?)"
                         id
                         (Integer/parseInt loc-id)]))))
  {:status  303
   :headers {"Location" (str "/received" (when (not= lang :en)
                                           (str "?lang=" (name lang))))}})

(defn subscribe
  [req lang]
  (try
    (let [{locations "locations", email "email" :as posted-form} (form-decode (slurp (:body req)))]
      (debug "decoded form:" posted-form)
      (or (validate-subscribe-request posted-form)
          (save-subscription posted-form lang)))
    (catch Exception e
      (error e)
      {:status  500
       :headers {"Content-Type" "text/plain"}
       :body    (str "500 Internal server error"
                     "\n\nSorry, something went wrong. Please try again later."
                     "\n\nIf you continue to see this message, please email me: avi@aviflax.com.")})))

(defn received-page
  [lang]
  (let [t     (translator lang)
        title (t "New York State COVID-19 Vaccine Appointment Availability Notifications")]
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
    (warn "no handler found for path" (:uri req)))
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

(defn start
  [_args]
  (info "Connecting to the database...")
  (ensure-dbconn)

  ;; TODO: change this to a proper cache that will invalidate and refresh after e.g. an hour
  (reset! locations (jdbc/execute! @dbconn ["select id, name, address from location.with_current_name order by name"]))

  (debug "locations:" @locations)

  (info "Starting HTTP server listening on port" port)
  (srv/run-server app {:port port}))
