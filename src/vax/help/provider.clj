(ns vax.help.provider
  (:require ;; [clojure.string :as str]
            ;; [com.brunobonacci.mulog :as μ]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [next.jdbc.result-set :as rs]
            [vax.help.feed.ny :as ny]))

(defn get-all-for-feed
  "Retrieves all providers for the feed from the DB, and returns them as a map of id to provider."
  [feed-id dbconn]
  (let [providers (sql/query dbconn
                             ["select id, feed_id, appointments_available
                               from provider.with_current_state
                               where feed_id = ?"
                              feed-id]
                             {:builder-fn rs/as-unqualified-lower-maps})]
    (zipmap (map :id providers) providers)))

(defn id
  "Given e.g. F-NYS-01 and 1234 will return P-1234-F-NYS-01"
  [feed-id id-from-feed]
  (format "P-%s-%s" id-from-feed feed-id))

(defn store-new
  [feed-id
   {initial-name    :providerName
    initial-address :address       :as new-provider}
   db]
  (jdbc/with-transaction [tx db]
    (let [pid (id feed-id (:providerId new-provider))]
      (sql/query tx ["insert into provider.providers (id, feed_id, initial_name, initial_address)
                      values (?, ?, ?, ?)"
                     pid feed-id initial-name initial-address])
      (sql/query tx ["insert into provider.names (provider_id, name) values (?, ?)"
                     pid initial-name])
      pid)))

(defn store-state-change
  [{provider-id :providerId, :as new-provider-state} feed-id update-id dbconn]
  (sql/insert! dbconn
               :provider.state_changes
               {:provider_id            (id feed-id provider-id)
                :feed_update_id         update-id
                :appointments_available (ny/appointments-available? new-provider-state)})
  nil)
