(ns vax.help.feed
  (:require [cheshire.core :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql :as sql]))

(defn get-latest-update
  [id dbconn]
  (some-> (jdbc/execute-one! dbconn ["select data::text
                                      from feed.updates
                                      where feed_id = ?
                                      order by ts DESC
                                      limit 1"
                                     id]
                             {:builder-fn rs/as-unqualified-lower-maps})
          (:data)
          (json/parse-string :true)))

(defn store-new
  [id state-or-territory name data-url db]
  (sql/query db ["insert into feed.feeds (id, state_or_territory, name, data_url)
                  values (?, ?::feed.state_or_territory, ?, ?)"
                 id state-or-territory name data-url])
  nil)

(defn store-update
  "Returns the ID of the new update record."
  [id json-str dbconn]
  (some-> (jdbc/execute-one! dbconn ["insert into feed.updates (feed_id, data)
                                      values (?, ?::jsonb)"
                                     id json-str]
                             {:return-keys true})
          (:updates/id)))

(defn feeds=?
  "Are the given feeds equal, apart from their lastUpdated timestamp?"
  [a b]
  (= (dissoc a :lastUpdated) (dissoc b :lastUpdated)))
