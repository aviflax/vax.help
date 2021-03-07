(ns vax.help.config
  (:require [clojure.string :as str]))

(defn env!
  "Throws if the environment variable is missing or blank."
  [vn]
  (let [vv (System/getenv vn)]
    (if (or (not vv)
            (str/blank? vv))
      (throw (RuntimeException. (format "Required environment variable %s not found." vn)))
      vv)))

(defn env
  [vn default]
  (or (System/getenv vn) default))

(defn ss->ms
  "Convert a string containing a number of seconds to an integer equivalent in milliseconds"
  [v]
  (-> v
      (Integer/parseInt)
      (* 1000)))

(defn ensure-trailing-slash
  [v]
  (if-not (str/ends-with? v "/")  ; I normally prefer `if` over `if-not` but it’s just so much easier to append a char than remove one.
    (str v "/")
    v))

(defn get-getter
  [config]
  (fn [first-key & more-keys]
    (get-in config (cons first-key more-keys))))
