(ns vax.help.background
  (:require [com.brunobonacci.mulog :as μ]
            [vax.help.background.feed.updates :as feed-updates]
            [vax.help.background.email.verifications :as verifications]))

(def jobs
  {:feed-updates/start  feed-updates/start
   :verifications/start verifications/start})

(defn start
  "WARNING if an error occurs this will kill the VM!"
  [_args]
  ;; Using println because I want to print something as soon as the function starts, just as a sign
  ;; that something is happening, and it takes some time for the μ publisher to start up, or something.
  (println "Starting background job runner.")

  (μ/start-publisher! {:type :console-json})
  
  (μ/log ::starting :jobs (keys jobs))
  
  ;; TODO: this is simplistic! If a thread fails, we don’t restart it, and we don’t retry, and we
  ;; don’t do anything smart! We should use something smarter, at a minimum Java 5’s ExecutorService
  (try 
    (let [threads (map #(Thread. %) (vals jobs))]
      (run! #(.start %) threads)
      
      (while true
        (when-let [thread (some #(not (.isAlive %)) threads)]
          (throw (ex-info "A background thread seems to have exited. That’s not supposed to happen!" {:dead-thread thread})))
        (Thread/sleep 1000)))    
    (catch Exception e
      ;; If an exception bubbled up to this level, then one of our threads has died. We don’t
      ;; currently have the sophistication to do anything smarter than log the error and kill the
      ;; whole process. Ah well — fail fast and loud right?!
      (μ/log ::error :ex-class (.getSimpleName (class e)), :ex-msg (.getMessage e))
      (Thread/sleep 1000)  ; give the μ publisher time to print stuff out (not SO fast, I guess)
      (System/exit 1))))
