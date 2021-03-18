(ns vax.help.subscription
  (:require [com.brunobonacci.mulog :as μ]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql :as sql]))

(defn store-stage-change
  [id new-state db]
  (sql/query db ["insert into subscription.state_changes (subscription_id, state)
                   values (?, ?::subscription.state)"
                 id new-state])
  nil)

(defn get-by-nonce
  [nonce db]
  (jdbc/execute-one! db ["select id, email, language, nonce, state::varchar,
                                 state_change_ts, state_change_note
                          from subscription.with_current_state
                          where nonce = ?"
                         nonce]
                     {:builder-fn rs/as-unqualified-lower-maps}))

(defn get-active-for-providers
  "Get all subscriptions that are subscribed to the specified providers, including, for each
   subscription, an array of all the providers to which that subscription is... subscribed."
  [provider-ids dbconn]
  (μ/log ::retrieving-active-subscriptions :provider-ids provider-ids)
  (jdbc/execute!
    dbconn
    ["select s.id, s.email, s.language as lang, s.nonce,
        array(select p.id
              from provider.providers p
                join subscription.subscriptions_providers sp on p.id = sp.provider_id
                   and sp.subscription_id = s.id) as provider_ids
      from subscription.with_current_state s
      where s.state = 'active'::subscription.state
        and s.id in (select subscription_id
                     from subscription.subscriptions_providers
                     where provider_id = any (?))"
     (into-array String provider-ids)]
    {:builder-fn rs/as-unqualified-lower-maps}))
