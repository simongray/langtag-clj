(ns dk.simongray.langtag.generative-test
  "Properties that hold for any tag, range or Accept-Language value, tried
  on random ones in both Clojure and ClojureScript."
  (:require [clojure.string :as str]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [dk.simongray.langtag :as langtag]
            [dk.simongray.langtag.generators :as g]))

(defn- subtag-counts
  "How often each subtag of the tag `s` occurs in it, ignoring case."
  [s]
  (frequencies (str/split (str/lower-case s) #"-")))

(defspec well-formed-tags-parse 2000
  (prop/for-all [s (gen/one-of [g/tag g/grandfathered])]
    (let [m (langtag/parse s)
          t (langtag/tag s)]
      (and (some? m)
           (nil? (langtag/error s))
           (= t (langtag/tag m) (langtag/tag t))
           (= m (langtag/parse t))
           (= (subtag-counts s) (subtag-counts t))))))

(defspec errors-explain-each-failed-parse 3000
  (prop/for-all [s g/text]
    (let [{:keys [subtag index] :as error} (langtag/error s)]
      (if (langtag/parse s)
        (nil? error)
        (= subtag (get (str/split s #"-" -1) index))))))

(defspec priority-lists-are-in-order-of-weight 1000
  (prop/for-all [s (g/accept-language g/extended-range)]
    (let [ranges (langtag/priority-list s)]
      (and (apply >= (map :weight (cons {:weight 1.0} ranges)))
           (= ranges (langtag/priority-list ranges))))))

(defspec basic-filtering-is-within-extended-filtering 2000
  (prop/for-all [s  (g/accept-language g/basic-range)
                 ts g/tags]
    (let [basic (langtag/basic-filter s ts)]
      (and (every? (set (langtag/extended-filter s ts)) basic)
           (every? (fn [t] (some #(langtag/basic-match? (:range %) t)
                                 (langtag/priority-list s)))
                   basic)))))

(defspec lookup-finds-a-tag-that-basic-filtering-finds-or-a-fallback 2000
  (prop/for-all [s  (g/accept-language g/basic-range)
                 ts g/tags]
    (let [found (langtag/lookup s ts)]
      (or (nil? found)
          (and (some #{found} ts)
               (some (fn [{:keys [range]}]
                       (langtag/basic-match? found range))
                     (langtag/priority-list s)))))))
