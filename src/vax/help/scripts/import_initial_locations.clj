(ns vax.help.scripts.import-initial-locations
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

(defn run
  [_args]
  (let [data   (slurp "https://am-i-eligible.covid19vaccine.health.ny.gov/api/list-providers")
        parsed (json/parse-string data true)]
    (jdbc/with-transaction [tx (db)]
      (doseq [{:keys [providerName address]} (:providerList parsed)]
        (let [{id :locations/id}
              (jdbc/execute-one! tx ["insert into location.locations (us_state, initial_name, address)
                                      values (cast(? as location.us_state), ?, ?)"
                                     "NY" providerName address]
                                 {:return-keys true})]
          (jdbc/execute-one! tx ["insert into location.names (location_id, name) values (?, ?)"
                             id providerName]))))))
