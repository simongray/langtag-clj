(ns dk.simongray.langtag.bench
  "Benchmarks of parsing tags and matching them, on the JVM by criterium
  and in Node by the clock, and flame graphs of the JVM by
  clj-async-profiler. On the JVM, the cases that start with jdk- time the
  JDK doing the same, for comparison.

      clojure -M:bench -m dk.simongray.langtag.bench
      clojure -M:cljs release bench && node target/bench.js

  In a REPL with the :bench alias, (run) gives the mean of each case in
  microseconds, and (profile :basic-filter 10) the file of a flame graph."
  (:require #?@(:clj [[clj-async-profiler.core :as prof]
                      [criterium.core :as criterium]])
            [dk.simongray.langtag :as langtag]
            [dk.simongray.langtag.registry :as registry])
  #?(:clj (:import [java.util Locale Locale$LanguageRange])))

(def header
  "The Accept-Language of a browser set to Danish, then English."
  "da-DK,da;q=0.9,en-US;q=0.8,en;q=0.7")

(def documents
  "The tags of a thousand documents in a few dozen languages."
  (vec (take 1000 (cycle (for [language ["en" "de" "fr" "sv" "nb" "zh" "es" "pt" "ar" "da"]
                               region   [nil "US" "GB" "DE" "CN" "BR" "DK"]]
                           (str language (when region (str "-" region))))))))

(def cases
  "Each case by name, as a function to time."
  (merge
   {:parse           #(langtag/parse "zh-yue-Hant-HK-u-co-pinyin-x-private")
    :parse-short     #(langtag/parse "en-GB")
    :error           #(langtag/error "de-419-DE")
    :tag             #(langtag/tag "EN-gb")
    :tag-map         #(langtag/tag {:language "sr" :script "Latn" :region "RS"})
    :priority-list   #(langtag/priority-list header)
    :lookup          #(langtag/lookup header ["da" "en"])
    :lookup-1000     #(langtag/lookup "sv-FI, nb;q=0.5" documents)
    :basic-filter    #(langtag/basic-filter header documents)
    :extended-filter #(langtag/extended-filter header documents)
    :valid           #(registry/valid? "zh-yue-Hant-HK-u-co-pinyin-x-private")
    :canonical-form  #(registry/canonical-form "zh-yue-Hant-HK-u-co-pinyin-x-private")
    :registry-lookup #(registry/lookup header ["da" "en"])
    :registry-filter #(registry/basic-filter header documents)}
   #?(:clj
      {:jdk-parse         #(Locale/forLanguageTag "zh-yue-Hant-HK-u-co-pinyin-x-private")
       :jdk-priority-list #(Locale$LanguageRange/parse header)
       :jdk-lookup        #(Locale/lookupTag (Locale$LanguageRange/parse header) ["da" "en"])
       :jdk-lookup-1000   #(Locale/lookupTag (Locale$LanguageRange/parse "sv-FI, nb;q=0.5")
                                             documents)
       :jdk-basic-filter  #(Locale/filterTags (Locale$LanguageRange/parse header) documents)})))

#?(:cljs
   (defn- mean
     "The mean time of a call of `f` in seconds, over a second of calls
     after a second of warming up."
     [f]
     (let [now   #(js/performance.now)
           calls (fn [ms]
                   (let [end (+ (now) ms)]
                     (loop [n 0]
                       (if (< (now) end)
                         (do (dotimes [_ 100] (f)) (recur (+ n 100)))
                         n))))]
       (calls 1000)
       (let [start (now)
             n     (calls 1000)]
         (/ (- (now) start) n 1000)))))

(defn run
  "The mean time of each case, or of those named `ks`, in microseconds."
  ([]
   (run (keys cases)))
  ([ks]
   (into (sorted-map)
         (for [k    ks
               :let [mean #?(:clj  (first (:mean (criterium/quick-benchmark* (cases k) {})))
                             :cljs (mean (cases k)))]]
           [k (/ (Math/round (* mean 1e9)) 1000.0)]))))

#?(:clj
   (defn profile
     "The file of a flame graph of the case `k`, run for `seconds` by
     clj-async-profiler."
     [k seconds]
     (let [f   (cases k)
           end (+ (System/currentTimeMillis) (* 1000 seconds))]
       (prof/profile (while (< (System/currentTimeMillis) end) (f))))))

(defn -main
  "Print the mean time of each case, or of those named in `args`."
  [& args]
  (doseq [[k us] (if (seq args) (run (map keyword args)) (run))]
    (println (str (name k) (apply str (repeat (- 18 (count (name k))) " ")) us " µs")))
  #?(:clj (shutdown-agents)))

(comment
  (run)
  (run [:lookup :basic-filter])
  (profile :basic-filter 10)
  #_.)
