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
