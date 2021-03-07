(ns vax.help.background.email.verifications
  (:require [clojure.string :as str]
            [com.brunobonacci.mulog :as μ]
            [vax.help.i8n :as i8n]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql :as sql])
  (:import [com.wildbit.java.postmark Postmark]
           [com.wildbit.java.postmark.client.data.model.message Message]))

(defn env!
  "Throws if the environment variable is missing or blank."
  [vn]
  (let [vv (System/getenv vn)]
    (if (or (not vv)
            (str/blank? vv))
      (throw (RuntimeException. (format "Required environment variable %s not found." vn)))
      vv)))

(defn env
  [vn default]
  (or (System/getenv vn) default))

(defn- ss->ms
  "Convert a string containing a number of seconds to an integer equivalent in milliseconds"
  [v]
  (-> v
      (Integer/parseInt)
      (* 1000)))

(defn build-config!
  "Throws if a required environment variable is missing or blank."
  []
  {:baseurl     (let [v (env! "EXTERNAL_BASE_URL")]
                  (if (str/ends-with? v "/")
                    (subs v 0 (dec (count v)))
                    v))
   :db          {:dbtype       "postgresql"
                 :host         (env! "DB_HOST")
                 :port         (env! "DB_PORT")
                 :dbname       (env! "DB_NAME")
                 :user         (env! "DB_USERNAME")
                 :password     (env! "DB_PASSWORD")
                 ::comment     "This is (and must be) a valid next.jdbc dbspec."}
   :fetch-limit (Integer/parseInt (env! "FETCH_LIMIT"))
   :postmark    {:server-token (env! "POSTMARK_SERVER_TOKEN")}
   :sleep-ms      (ss->ms (env! "SLEEP_SECS"))  ; how long to pause between each "batch" i.e. query
   :err-sleep-ms  (ss->ms (env "ERR_SLEEP_SECS" "1"))})  ; how long to pause after an error

(defn config-val-getter
  [config]
  (fn [first-key & more-keys]
    (get-in config (cons first-key more-keys))))

(defn- new-subs
  "Get subscriptions for which we have not yet sent verification emails."
  [dbconn cv]
  (sql/query dbconn
             ["SELECT id, email, language, nonce, state, state_change_ts
               FROM subscription.with_current_state
               WHERE state='new'
               ORDER BY state_change_ts ASC
               LIMIT ?"
              (cv :fetch-limit)]
             {:builder-fn rs/as-unqualified-lower-maps}))

(defn verification-url
  [lang nonce base-url]
  (str base-url "/subscription-verification/" nonce (when (not= lang "en")
                                                      (str "?lang=" (name lang)))))

(defn- body
  [t lang nonce base-url]
  (format
   (str (t "To verify your subscription to COVID-19 vaccine appointment availability notifications, please open this link:")
        "\n\n%s\n\n"
        (t "If you did not request such notifications, you may ignore this email."))
   (verification-url lang nonce base-url)))

(defn sub->email
  [{:keys [id email language nonce] :as _sub}]
  (let [t           (i8n/translator language)
        sender      "updates@vax.help"
        recipient   email
        subject     (t "Confirm your subscription to COVID-19 vaccine appointment availability notifications")
        html-body   nil
        text-body   (body t language nonce (cv :baseurl))]
    (Message. sender recipient subject html-body text-body)))

(defn send-verification-email
  "Returns nil upon success, otherwise a map representing an anomaly.
   TODO: return a proper ::anom/anomaly
   
   Might throw if e.g. something goes really bad. (I’m not entirely sure; I’d need to read more of
   the source of the Postmark Java client lib (which is at https://github.com/wildbit/postmark-java
   ). This is one of the problems with using a wrapper.)"
  [{:keys [id email language nonce] :as sub} pm-client dbconn cv]
  (try
    (let [msg         (sub->email sub)

          _           (μ/log ::sending-email, :sub-id id, :sender sender, :recipient recipient, :subject subject, :nonce nonce, :body text-body)

          result      (.deliverMessage pm-client msg)  ; returns an instance of MessageResponse

          error-code  (.getErrorCode result)
          success?    (zero? error-code)
          result-msg  (.getMessage result)
          msg-id      (.getMessageId result)]
      (μ/log ::postmark-response, :sub-id id, :error-code error-code, :result-message result-msg, :message-id msg-id)
      (when-not success?
        {:error/category       :fault
         :error/code           error-code
         :error/message        result-msg
         :postmark/message-id  msg-id}))
    (catch Exception e
      (μ/log ::postmark-error, :sub-id id, :exception-class (.getSimpleName e) :exception-message (.getMessage e))
      {:error/message   (.getMessage e)
       :error/category  :fault
       :exception       e})))

(defn transition-sub-state
  [{id :id, :as _sub} dbconn]
  (let [new-state "pending-verification"]
    (μ/log ::transitioning-sub-state, :sub-id id, :new-state new-state)
    (jdbc/execute! dbconn
                   ["insert into subscription.state_changes (subscription_id, state)
                     values (?, cast(? as subscription.state))"
                    id
                    new-state])))

(defn start
  [& _args]
  (let [config     (build-config!)
        cv         (config-val-getter config)
        pm-client  (Postmark/getApiClient (cv :postmark :server-token))
        dbconn     (jdbc/get-connection (cv :db))]
    (μ/log ::db-conn :connection :successful)
    (while true
      (when-let [new-subs (seq (new-subs dbconn cv))]
        (μ/log ::new-subs-found :subs new-subs)
        (doseq [sub new-subs]
          (if-let [send-error (send-verification-email sub pm-client dbconn cv)]
            (Thread/sleep (cv :err-sleep-ms))
            (transition-sub-state sub dbconn)))))
      (Thread/sleep (cv :sleep-ms))))
