(ns vax.help.background.feed.updates
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as μ]
            [next.jdbc :as jdbc]
            [vax.help.config :as c :refer [env env! ss->ms]]
            [vax.help.db]
            [vax.help.feed :as feed]
            [vax.help.feed.ny :as ny]
            [vax.help.provider :as provider]
            [vax.help.subscription :as subscription]
            [vax.help.i8n :as i8n])
  (:import [com.wildbit.java.postmark Postmark]
           [com.wildbit.java.postmark.client.data.model.message Message]))

(defn build-config!
  "Throws if a required environment variable is missing or blank."
  []
  {:baseurl       (c/ensure-trailing-slash (env! "EXTERNAL_BASE_URL"))   ; for unsubscribing
   :db            {:dbtype       "postgresql"
                   :host         (env! "DB_HOST")
                   :port         (env! "DB_PORT")
                   :dbname       (env! "DB_NAME")
                   :user         (env! "DB_USERNAME")
                   :password     (env! "DB_PASSWORD")
                   ::comment     "This is (and must be) a valid next.jdbc dbspec."}
   :postmark      {:server-token (env! "POSTMARK_SERVER_TOKEN")}
   :sleep-ms      (ss->ms (env! "FEED_UPDATE_FREQUENCY_SECS"))  ; how many seconds between updates
   :err-sleep-ms  (ss->ms (env "FEED_UPDATE_ERR_SLEEP_SECS" "1"))})  ; how long to pause after an error

(defn body
  [t
   {sub-provider-ids :provider_ids, :as sub}
   providers-by-availability]
  (μ/log ::building-email-body :sub sub, :providers-by-availability providers-by-availability)
  (let [subscribed-feed-provider-ids (set sub-provider-ids)

        ff    (fn [provider] (contains? subscribed-feed-provider-ids (:db-id provider)))
        has   (filter ff (get providers-by-availability true))
        nope  (filter ff (get providers-by-availability false))]
    (when (or (seq has) (seq nope))
      (str
       (when (seq has)
         (str (t "These providers now DO have appointments available:")
              "\n\n"
              (str/join "\n" (sort (map :providerName has)))
              "\n\n"
              "** "
              (t "Indicates providers for which eligibility is restricted by residency")
              "\n\n"))
       (when (seq nope)
         (str (t "These providers now do NOT have appointments available:")
              "\n\n"
              (str/join "\n" (sort (map :providerName nope)))
              "\n\n"
              "** "
              (t "Indicates providers for which eligibility is restricted by residency")))))))

;; TODO: customize the subject according to whether the updates are good news, bad news, or mixed
(defn sub->email
  [{:keys [email lang] :as sub} providers-by-availability]
  (let [t           (i8n/translator lang)
        sender      "updates@vax.help"
        recipient   email
        subject     (t "COVID-19 vaccination appointment updates")
        html-body   nil
        text-body   (body t sub providers-by-availability)]
    (Message. sender recipient subject html-body text-body)))

(defn send-emails
  [providers-from-update dbconn pm-client]
  (let [feed-provider-ids          (set (map :db-id providers-from-update))
        relevant-subscriptions     (subscription/get-for-providers feed-provider-ids dbconn)
        providers-by-availability  (group-by ny/appointments-available? providers-from-update)]
    (doseq [sub relevant-subscriptions
            :let [msg  (sub->email sub providers-by-availability)]]
      (μ/log ::sending-email, :subscription/id (:id sub), :email/from (.getFrom msg), :email/to (.getTo msg), :email/subject (.getSubject msg))
      (.deliverMessage pm-client msg))))

;; TODO: check for provider name changes and add the new names to the DB
(defn check-for-updates
  [cv]
  (let [{feed-id :id, data-url :data-url, :as _feed}  ny/feed
        pider             (partial provider/id feed-id)
        dbconn            (jdbc/get-connection (cv :db))
        _                 (μ/log ::db-conn :connection :successful)
        feed-json-str     (slurp data-url)
        new-feed-state    (json/parse-string feed-json-str true)
        prior-feed-state  (or (feed/get-latest-update feed-id dbconn) {})]
   (jdbc/with-transaction [tx dbconn]
    (if (feed/feeds=? new-feed-state prior-feed-state)
      (μ/log ::no-changes :new-last-updated (:lastUpdated new-feed-state), :prior-last-updated (:lastUpdated prior-feed-state))
      (let [update-id              (feed/store-update feed-id feed-json-str tx)
            update-provider-states (->> (:providerList new-feed-state)
                                        (map #(assoc % :db-id (pider (:providerId %)))))
            db-provider-states     (provider/get-all-for-feed feed-id tx)
            availability-changed?  (atom false)]  ; it’s possible the feeds are different because a new provider has been added. In this case we want to add the provider to the DB, so that people can start subscribing to it. But there won’t be any existing subscriptions for the provider, of course.
        (doseq [{pid :db-id :as new-provider-state} update-provider-states]
          (μ/log ::handling-provider :new-provider-state new-provider-state)

          (if-let [db-provider-state (get db-provider-states pid)]
            (do
              (μ/log ::provider-already-in-db :db-provider-state db-provider-state)
              (when (not= (ny/appointments-available? new-provider-state)
                          (ny/appointments-available? db-provider-state))
                (reset! availability-changed? true)  ; for later
                (μ/log ::provider-state-change :provider-id pid, :feed-update-id update-id, :appointments-available? (ny/appointments-available? new-provider-state))
                (provider/store-state-change new-provider-state feed-id update-id tx)))
            (do (μ/log ::new-provider :feed-id feed-id, :provider new-provider-state)
                (provider/store-new feed-id new-provider-state tx))))

        ;; Within the doseq above we might have toggled the value of availability-changed? to true.
        ;; If so, that means at least one provider wasn’t new, but rather was already known and its
        ;; availability changed. This right here is the whole point of this project, so now this is
        ;; our change to do something about this! Namely, to enqueue email updates notifying all
        ;; those who subscribed to this particular provider that there’s been a change to its
        ;; availability. However! Some subscribers may have subscribed to two providers, or many,
        ;; and it’s possible that more than one of a subscriber’s selected providers have changes
        ;; from this single feed update. We therefore need to “batch” the changes by feed update,
        ;; and for each subscriber, send them a single update listing the changes to the specific
        ;; providers to which they subscribed that were changed *in this update*.
        (if @availability-changed?
          (do
            (μ/log ::sending-emails)
            (send-emails update-provider-states tx (Postmark/getApiClient (cv :postmark :server-token))))
          (μ/log ::not-sending-emails)))))))

(defn start
  [& _args]
  (let [config     (build-config!)
        cv         (c/get-getter config)
        sleep-ms   (cv :sleep-ms)]
    (while true
      (check-for-updates cv)
      (Thread/sleep sleep-ms))))

(comment
  (μ/start-publisher! {:type :console})

  (with-redefs [ny/feed {:id                 "F-NYS-01"
                         :state-or-territory "NY"
                         :name               "am-i-eligible.covid19vaccine.health.ny.gov"
                         :data-url           "test/resources/feed-updates/ny/ny-feed-02.json"}]
    (let [config     (build-config!)
          cv         (c/get-getter config)
          sleep-ms   (cv :sleep-ms)]
      ;; (while true (slurp "/tmp/bar")
        (check-for-updates cv)))
  ;; (Thread/sleep sleep-ms))))
  )
