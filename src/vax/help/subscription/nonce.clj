(ns vax.help.subscription.nonce
  "Copied verbatim from https://github.com/funcool/buddy-core/blob/bd31ff9b802e689b046afae0f8c29ed70bb15ab5/src/buddy/core/nonce.clj"
  (:require [clojure.string :as str])
  (:import java.security.SecureRandom))

(defn- random-bytes
  "Generate a byte array of specified length with random
  bytes taken from secure random number generator.
  This method should be used to generate a random
  iv/salt or arbitrary length."
  ([^long numbytes]
   (random-bytes numbytes (SecureRandom.)))
  ([^long numbytes ^SecureRandom sr]
   (let [buffer (byte-array numbytes)]
     (.nextBytes sr buffer)
     buffer)))

(defn random
  "Generate a secure nonce based on current time
  and additional random data obtained from secure random
  generator. The minimum value is 8 bytes, and recommended
  minimum value is 32."
  ([^long numbytes]
   (random numbytes (SecureRandom.)))
  ([^long numbytes ^SecureRandom sr]
   (let [buffer (java.nio.ByteBuffer/allocate numbytes)]
     (.putLong buffer (System/currentTimeMillis))
     (.put buffer ^bytes (random-bytes (.remaining buffer) sr))
     (.array buffer))))

(defn random-str
  [^long numbytes]
  (str/join (vec (random numbytes))))
