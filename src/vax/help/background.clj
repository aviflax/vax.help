(ns vax.help.background
  (:require [com.brunobonacci.mulog :as μ]
            [vax.help.background.email.verifications :as verifications]))

(defn start
  [_args]
  (μ/start-publisher! {:type :console})
  
  (μ/log ::start :description "Starting background thread for sending email notifications for new subscriptions...")
  (.join (Thread. (verifications/start))))
