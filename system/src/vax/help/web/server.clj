(ns vax.help.web.server
  (:require [clojure.string :as str]
            [com.brunobonacci.mulog :as μ]
            [hiccup.core :as hiccup]
            [next.jdbc :as jdbc]
            [org.httpkit.server :as srv]
            [vax.help.config :refer [env!]]
            [vax.help.i8n :as i8n]
            [vax.help.subscription.nonce :as nonce]
            [vax.help.subscription :as subscription])
  (:import [java.net URLDecoder]))

(defn logger
  [level]
  (fn [& args] (apply println (cons (str level ":") args))))

(def debug (logger "DEBUG"))
(def info  (logger "INFO"))
(def warn  (logger "WARN"))
(def error (logger "ERROR"))

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
    (catch Exception _e
      (swap! dbconn (fn [cur-conn]
                      (try
                        (jdbc/execute! cur-conn ["select version()"])
                        cur-conn
                        (catch Exception _e
                          (jdbc/get-connection {:dbtype   "postgresql"
                                                :host     (cv :db :host)
                                                :dbname   (cv :db :name)
                                                :user     (cv :db :username)
                                                :password (cv :db :password)
                                                :port     (cv :db :port)}))))))))

;; TODO: change this to a proper cache that will invalidate and refresh after e.g. an hour
(def providers (atom nil))

(def supported-languages
  {:en "English"
   :es "Español"})

(defn home-page
  [lang & _etc]
  ;; TODO: add client-side form validation, once we’ve tested server-side validation
  (let [t     (i8n/translator lang)
        title (t "New York State COVID-19 Vaccine Appointment Availability Notifications")]
    (hiccup/html
    "<!DOCTYPE html>\n"
    [:html
      [:head
        [:meta {:charset "UTF-8"}]
        [:meta {:name "viewport", :content "width=device-width, initial-scale=1"}]
        [:title title]
        [:style
         "html, body, select, option, input { font-family: Charter, Palatino; font-size: large; }
          
          #providers {
            display: grid;
            grid-template-columns: 1.25em 1fr;
            gap: 1em 0;
          }
                    
          .address { display: block; font-style: italic; }
          
          footer {
            margin-top: 10em;
            font-size: smaller;
          }"]]
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
        [:h3 (t "Check the providers about which you’d like to be notified")]
        [:p "** " (t "Indicates providers for which eligibility is restricted by residency")]
        
        [:div#providers
         (mapcat identity
          (for [{:keys [:with_current_name/id :with_current_name/name :with_current_name/initial_address]} @providers
                :let [elem-id (str "provider-" id)]]
            [[:input {:type :checkbox, :name :providers, :value id, :id elem-id}]
              [:label {:for elem-id}
              [:span.providerName name]
              [:span.address initial_address]]]))]
         
        [:label
          [:h3 (t "Enter the email address at which you’d like to be notified when the checked providers have new availability")]
          [:input {:type :email, :name :email, :required :required, :title "email address (required)"}]]
        
        [:h3 (t "Click/tap the button below to create your subscription")]
         
        [:input {:type :submit, :value (t "Receive Notifications via Email")}]
         
        [:footer
         [:p
          (t "This service is operated by")
          " "
          [:a {:href "mailto:team@vax.help"} "Team vax.help"]
          " "
          (t "with data from")
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
  [{providers "providers", email "email" :as _posted-form}]
  (when (or (not (seq providers))
            (str/blank? email)) ; TODO: make this more robust. Maybe with a good old regex!
    {:status  400
     :headers {"Content-Type" "text/plain"}
     :body    (str "400 Bad Request"
                   "\n\nDid you check at least one provider?"
                   "\n\nPlease go back and try again.")}))

(defn save-subscription
  [{email "email", providers "providers" :as _posted-form} lang]
  (jdbc/with-transaction [tx @dbconn]
    (let [{id :subscriptions/id}
          (jdbc/execute-one! tx ["insert into subscription.subscriptions (email, language, nonce)
                                  values (?, ?, ?)"
                                 email (name lang) (nonce/random-str 16)]
                       {:return-keys true})]
      (jdbc/execute! tx ["insert into subscription.state_changes (subscription_id, state)
                          values (?, cast(? as subscription.state))"
                         id "new"])
      (doseq [provider-id (if (coll? providers) ; if only one box is checked the value will be a scalar
                            providers
                            [providers])]
        (jdbc/execute! tx ["insert into subscription.subscriptions_providers (subscription_id, provider_id)
                            values (?, ?)"
                           id provider-id]))))
  {:status  303
   :headers {"Location" (str "/received" (when (not= lang :en)
                                           (str "?lang=" (name lang))))}})

(defn subscribe
  [req lang]
  (try
    (let [posted-form (form-decode (slurp (:body req)))]
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
  [lang & _etc]
  (let [t     (i8n/translator lang)
        title (t "New York State COVID-19 Vaccine Appointment Availability Notifications")]
    (hiccup/html
    "<!DOCTYPE html>\n"
    [:html
      [:head
        [:meta {:charset "UTF-8"}]
        [:meta {:name "viewport", :content "width=device-width, initial-scale=1"}]
        [:title title]]
      [:body
        [:header
          [:h1 title]]
        [:h2 (t "Subscription Request Received")]
        [:p (t "We have received your request to subscribe to appointment availability changes for the selected providers.")]
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

;; TODO: needs way more error handling. e.g. what if nonce is not provided in query string
(defn subscription-verification
  [req]
  (try
    (let [t      (i8n/translator (which-lang req))
          nonce  (->> (get req :query-string "")
                      (re-find #"nonce=([a-zA-Z0-9-]+)")
                      (second))

          {:keys [id state]
           :as sub}          (if nonce
                               (subscription/get-by-nonce nonce @dbconn)
                               {})]
      (μ/log ::sub-verification-data-retrieved :nonce nonce, :sub sub)
      (cond
        (not nonce)                         {:status 400, :body "Bad request"}
        (not sub)                           {:status 404, :body "Not found"}
        (= state "active")                  {:status 200, :body (t "Subscription verified")}
        (= state "canceled")                {:status 400, :body "Bad request: subscription was already canceled"}
        (not= state "pending-verification") {:status 400, :body "Bad request: subscription not pending verification"}

        :else
        (do (subscription/store-stage-change id "active" @dbconn)
            {:status 200, :body (t "Subscription verified")})))
  (catch Exception e
    (μ/log ::sub-verification-req-err :exception e)
    {:status 500, :body "Internal server error"})))

(defn handle-get
  [req page-fn]
  (if (not= (:request-method req) :get)
    {:status 405, :body "Method not allowed"}
    (let [lang (which-lang req)]
      {:status  200
       :headers {"Content-Type" "text/html; charset=UTF-8", "Content-Language" lang}
       :body    (page-fn lang req)})))

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
  {"/"                          (fn [req] (handle-get req (memoize home-page)))
   "/healthz"                   (constantly {:status 200
                                             :body   "OK"})
   "/subscribe"                 (fn [req] (handle-post req subscribe))
   "/received"                  (fn [req] (handle-get req (memoize received-page)))
   "/subscription/verification" subscription-verification
   "/favicon.ico"               (fn [req] (handle-not-found req false))})

(def do-not-log
  #{"/healthz" "/favicon.ico"})

(defn app [req]
  (let [uri  (:uri req)
        res  (if-let [handler (get routes uri)]
               (handler req)
               (handle-not-found req true))]
    ;; TODO: this is SUPER primitive!
    (when-not (do-not-log uri)
      (μ/log ::request :request (dissoc req :body), :response (dissoc res :body)))
    res))

(def port 8080)

(defn start
  [& _args]
  (μ/start-publisher! {:type :console-json})

  (info "Connecting to the database...")
  (ensure-dbconn)

  ;; Loading the copy here/now to trigger it being loaded from disk and read in case there’s a
  ;; syntax error in the EDN in which case we’ll get a CompilerException. This makes *some* sense, I think 😬.
  (μ/log ::loaded-copy :count (count @i8n/copy))

  ;; TODO: change this to a proper cache that will invalidate and refresh after e.g. an hour
  (reset! providers (jdbc/execute! @dbconn ["select id, name, initial_address from provider.with_current_name order by name"]))

  (debug "providers:" @providers)

  (info "Starting HTTP server listening on port" port)
  (srv/run-server app {:port port}))
