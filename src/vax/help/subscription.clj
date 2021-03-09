(ns vax.help.subscription
  (:require [com.brunobonacci.mulog :as μ]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(defn get-for-providers
  "Get all subscriptions that are subscribed to the specified providers, including, for each
   subscription, an array of all the providers to which that subscription is... subscribed."
  [provider-ids dbconn]
  (μ/log ::retrieving-subscription :provider-ids provider-ids)
  (jdbc/execute!
    dbconn
    ["select s.id, s.email, s.language,
        array(select p.id
              from provider.providers p
                join subscription.subscriptions_providers sp on p.id = sp.provider_id
                   and sp.subscription_id = s.id) as provider_ids
      from subscription.subscriptions s
      where s.id in (select subscription_id
                     from subscription.subscriptions_providers
                     where provider_id = any (?))"
     (into-array String provider-ids)]
    {:builder-fn rs/as-unqualified-lower-maps}))
