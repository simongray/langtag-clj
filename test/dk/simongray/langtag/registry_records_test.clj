(ns dk.simongray.langtag.registry-records-test
  "Validity and the canonical form checked against each record of the
  registry in dev-resources, which the table in resources is made from."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dk.simongray.langtag.registry :as registry]))

(def records
  "The records of the registry, each a map of a field's name to its first
  value, in lower case. A field that runs over several lines keeps only
  its first."
  (delay
    (for [text (rest (str/split (slurp "dev-resources/language-subtag-registry")
                                #"(?m)^%%$"))]
      (->> (str/split-lines text)
           (keep #(re-matches #"([\w-]+): (.*)" %))
           (reverse)
           (into {} (map (fn [[_ k v]] [k (str/lower-case v)])))))))

(defn in-registry
  "A predicate of whether a text is a subtag of the type `t` in the
  registry, by itself or in a range such as qaa..qtz."
  [t]
  (let [subtags (for [r @records :when (= t (r "Type"))]
                  (str/split (r "Subtag") #"\.\."))
        singles (set (map first (remove second subtags)))
        ranges  (filter second subtags)]
    (fn [s]
      (boolean (or (contains? singles s)
                   (some (fn [[a b]]
                           (and (= (count a) (count s))
                                (not (neg? (compare s a)))
                                (not (pos? (compare s b)))))
                         ranges))))))

(defn combinations
  "Each text of `n` characters from `cs`."
  [cs n]
  (if (zero? n)
    [""]
    (for [c cs, s (combinations cs (dec n))] (str c s))))

(def letters "abcdefghijklmnopqrstuvwxyz")

(deftest languages-and-regions-are-valid-when-registered
  (let [language? (in-registry "language")
        region?   (in-registry "region")]
    (is (empty? (for [s     (concat (combinations letters 2) (combinations letters 3))
                      :when (not= (language? s) (registry/valid? s))]
                  s)))
    (is (empty? (for [s     (concat (combinations letters 2) (combinations "0123456789" 3))
                      :when (not= (region? s) (registry/valid? (str "und-" s)))]
                  s)))))

(def preferred-languages
  "Each language of the registry with a Preferred-Value, to the value."
  (delay
    (into {} (for [r @records
                   :when (and (= "language" (r "Type")) (r "Preferred-Value"))]
               [(r "Subtag") (r "Preferred-Value")]))))

;; a deprecated extlang, e.g. ajp, is its own Preferred-Value, while the
;; language ajp has apc
(defn example
  "A tag with the subtag or tag of the record `r`, and the tag in canonical
  form by the record."
  [r]
  (let [s (or (r "Subtag") (r "Tag"))
        v (r "Preferred-Value")]
    (case (r "Type")
      "language"                    [s (or v s)]
      "extlang"                     [(str (r "Prefix") "-" s) (@preferred-languages v v)]
      ("script" "region" "variant") [(str "und-" s) (str "und-" (or v s))]
      ("grandfathered" "redundant") [s (or v s)])))

(deftest each-record-is-valid-and-in-canonical-form-by-its-preferred-value
  (let [examples (->> @records
                      (remove #(str/includes? (or (% "Subtag") "") ".."))
                      (map example))]
    (is (empty? (remove (comp registry/valid? first) examples)))
    (is (empty? (remove (fn [[s form]]
                          (= form (some-> (registry/canonical-form s) str/lower-case)))
                        examples)))))
