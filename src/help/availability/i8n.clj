(ns help.availability.i8n
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [com.brunobonacci.mulog :as μ]))

(def copy-file-path "copy.edn")
(def copy (edn/read-string (slurp (io/resource copy-file-path))))

(defn translate
  [phrase language]
  (let [lang (keyword language)]
    (if (= lang :en)
      (do
        (when-not (contains? copy phrase)
          (μ/log ::missing-phrase, :phrase phrase, :copy-file copy-file-path))
        phrase)
      (if-let [trans (get-in copy [phrase lang])]
        trans
        (do
          (μ/log ::missing-translation, :lang lang, :phrase phrase, :copy-file copy-file-path)
          phrase)))))

(defn translator
  "Returns a function that will accept a phrase and return its translation in the given lang, if
   any."
  [lang]
  (fn [phrase] (translate phrase lang)))
