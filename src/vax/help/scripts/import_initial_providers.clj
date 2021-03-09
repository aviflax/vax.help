(ns vax.help.scripts.import-initial-providers
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [vax.help.feed :as feed]
            [vax.help.feed.ny :as ny]
            [vax.help.provider :as provider]))

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

(defn db
  []
  {:dbtype   "postgresql"
   :host     (cv :db :host)
   :dbname   (cv :db :name)
   :user     (cv :db :username)
   :password (cv :db :password)
   :port     (cv :db :port)})

(defn run
  [_args]
  (let [{:keys [state-or-territory data-url] feed-id :id, feed-name :name, :as _feed} ny/feed
        json-str   (slurp "db/initial-providers.json")
        parsed     (json/parse-string json-str true)]
    (jdbc/with-transaction [tx (db)]
      (feed/store-new feed-id state-or-territory feed-name data-url tx)
      (let [feed-update-id (feed/store-update feed-id json-str tx)]
        (doseq [provider (:providerList parsed)]
          (provider/store-new feed-id provider tx)
          (provider/store-state-change provider feed-id feed-update-id tx))))))

(comment 
  (slurp "providers.json") 
)
