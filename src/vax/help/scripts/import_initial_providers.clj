(ns vax.help.scripts.import-initial-providers
  (:require [next.jdbc :as jdbc]
            [cheshire.core :as json]
            [clojure.string :as str]))

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

(def data-url "https://am-i-eligible.covid19vaccine.health.ny.gov/api/list-providers")

(defn run
  [_args]
  (let [data   (slurp data-url)
        parsed (json/parse-string data true)]
    (jdbc/with-transaction [tx (db)]
      (let [{feed-id :feeds/id}
            (jdbc/execute-one! tx ["insert into feed.feeds (state_or_territory, name, data_url)
                                    values (cast(? as feed.state_or_territory), ?, ?)"
                                   "NY" "am-i-eligible.covid19vaccine.health.ny.gov" data-url]
                                  {:return-keys true})]
        (doseq [{:keys [providerId providerName address]} (:providerList parsed)]
          (let [{provider-id :providers/id}
                (jdbc/execute-one! tx ["insert into provider.providers (feed_id, id_from_feed, initial_name, initial_address)
                                        values (?, ?, ?, ?)"
                                       feed-id providerId providerName address]
                                  {:return-keys true})]
            (jdbc/execute-one! tx ["insert into provider.names (provider_id, name) values (?, ?)"
                                   provider-id providerName])))))))
