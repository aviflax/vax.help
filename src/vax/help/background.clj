(ns vax.help.background
  (:require [com.brunobonacci.mulog :as μ]
            [vax.help.background.feed.updates :as feed-updates]
            [vax.help.background.email.verifications :as verifications]))

(def jobs
  [feed-updates/start
   verifications/start])

(defn start
  "WARNING if an error occurs this will kill the VM!"
  [_args]
  (μ/start-publisher! {:type :console-json})
  
  (μ/log ::starting :jobs jobs)
  
  ;; TODO: this is simplistic! If a thread fails, we don’t restart it, and we don’t retry, and we
  ;; don’t do anything smart! We should use something smarter, at a minimum Java 5’s ExecutorService
  (try 
    (let [threads (map #(Thread. %))]
      (run! #(.start %) threads)
      (run! #(.join %) threads))
    (throw (RuntimeException. "All threads seem to have exited. That’s not supposed to happen!"))
    
    (catch Exception e
      ;; If an exception bubbled up to this level, then one of our threads has died. We don’t
      ;; currently have the sophistication to do anything smarter than log the error and kill the
      ;; whole process. Ah well — fail fast and loud right?!
      (μ/log ::error :ex-class (.getSimpleName (class e)), :ex-msg (.getMessage e))
      (System/exit 1))))
