(ns vax.help.feed.ny
  (:require [clojure.spec.alpha :as s]))

(s/def ::providerId pos-int?)
(s/def ::providerName string?)  ;; TODO: make this more robust (e.g. require to be non-blank)
(s/def ::address string?)       ;; TODO: make this more robust (e.g. require to be non-blank)
(s/def ::availableAppointments #{"AA" "NAC"})

(s/def ::provider (s/keys :req-un [::providerId ::providerName ::address ::availableAppointments]))

(s/def ::providerList (s/coll-of ::provider))

(s/def ::lastUpdated string?)  ;; TODO: make this more robust

(s/def ::feed (s/keys :req-un [::providerList ::lastUpdated]))

(defn appointments-available?
  "Accepts a map that plausibly represents a provider"
  [provider]
  ;; Yes, this will throw if the input is not what we’d expect. Yes, this is draconian. I’m not a
  ;; fan of using exceptions for this sort of thing either. This is (hopefully) just temporary,
  ;; until I have a chance to implement proper validation of the feed data via spec.
  (if (contains? provider :appointments_available)
      (:appointments_available provider)
      (case (:availableAppointments provider)
        ;; newer values that the NYS feed started using today, 2021-03-17
        "Y"   true
        "N"   false

        ;; older values; keeping them around just for a bit just in case.
        "AA"  true
        "NAC" false)))

(def feed
  {:id                 "F-NYS-01"
   :state-or-territory "NY"
   :name               "am-i-eligible.covid19vaccine.health.ny.gov"
   :data-url           "https://am-i-eligible.covid19vaccine.health.ny.gov/api/list-providers"
   :book-url           "https://am-i-eligible.covid19vaccine.health.ny.gov/"})
