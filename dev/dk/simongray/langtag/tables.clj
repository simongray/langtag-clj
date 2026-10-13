(ns dk.simongray.langtag.tables
  "Write the table in resources from the IANA Language Subtag Registry that
  dev/fetch.sh downloads.

      clojure -X:tables

  The table has a line for each part of the registry that the library
  needs: a name, then its entries, separated by spaces and in lower case.

  - file-date: the File-Date of the registry
  - languages: a bit for each combination of two letters, then of three
    letters, in hexadecimal, set for each language subtag
  - extlangs: each extlang with its Prefix, e.g. yue=zh
  - scripts, regions and variants: their subtags
  - preferred-languages, preferred-scripts, preferred-regions and
    preferred-variants: each subtag with its Preferred-Value, e.g. iw=he
  - preferred-tags: each grandfathered or redundant tag with its
    Preferred-Value, e.g. i-klingon=tlh

  It throws when the registry breaks a rule that the library relies on,
  e.g. that a Preferred-Value of a subtag is one subtag."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dk.simongray.langtag.impl.registry :as registry]
            [dk.simongray.langtag.impl.syntax :as syntax]))

(def source
  "Where dev/fetch.sh puts the registry."
  "dev-resources/language-subtag-registry")

(def target
  "Where the table goes."
  "resources/dk/simongray/langtag/registry.txt")

;; RFC 5646, section 3.1.1, whose record-jar format folds a long field onto
;; lines that start with whitespace
(defn record
  "The fields of the `text` of a record, as a map of each name to its
  values."
  [text]
  (->> (str/split-lines (str/replace text #"\r?\n[ \t]+" " "))
       (remove str/blank?)
       (map #(str/split % #":\s*" 2))
       (reduce (fn [m [k v]] (update m k (fnil conj []) v)) {})))

(defn records
  "The records of the registry `text`, the first of which holds the
  File-Date."
  [text]
  (map record (str/split text #"(?m)^%%\r?$")))

(defn field
  "The one value of the field `k` of the record `r`, or nil."
  [r k]
  (let [[v & more] (get r k)]
    (when more
      (throw (ex-info (str "More than one " k) r)))
    v))

(defn subtag
  "The subtag or tag of the record `r`, in lower case."
  [r]
  (syntax/lower (or (field r "Subtag") (field r "Tag"))))

(defn of-type
  "The records of the type `t`."
  [records t]
  (filter #(= t (field % "Type")) records))

(defn letters
  "The `n` lower-case letters of the number `x` in base 26."
  [x n]
  (->> (iterate #(quot % 26) x)
       (take n)
       (map #(char (+ 97 (rem % 26))))
       reverse
       (apply str)))

;; RFC 5646, section 3.1.4: only the subtags for private use are ranges,
;; e.g. qaa..qtz, and both ends of each are letters
(defn expand
  "The subtags of the `subtag`, which is a range such as qaa..qtz or one
  subtag."
  [subtag]
  (if-let [[_ a b] (re-matches #"([a-z]+)\.\.([a-z]+)" subtag)]
    (map #(letters % (count a))
         (range (registry/base-26 a) (inc (registry/base-26 b))))
    [subtag]))

(defn bits
  "The `xs` of a set of numbers below `n` as bits in hexadecimal: each
  digit holds four numbers, with the first in its lowest bit."
  [xs n]
  (let [xs (set xs)]
    (->> (range 0 n 4)
         (map (fn [i]
                (Integer/toHexString
                 (reduce + (for [j (range 4) :when (xs (+ i j))]
                             (bit-shift-left 1 j))))))
         (apply str))))

(defn languages
  "The language subtags of the `records` as bits, which the library tests
  with registry/language?."
  [records]
  (let [subtags (mapcat (comp expand subtag) (of-type records "language"))]
    (doseq [s subtags
            :when (not (re-matches #"[a-z]{2,3}" s))]
      (throw (ex-info "A language subtag that isn't two or three letters"
                      {:subtag s})))
    (bits (map registry/language-bit subtags) registry/language-bits)))

(defn prefixes
  "Each extlang of the `records` with its Prefix, e.g. yue=zh."
  [records]
  (for [r (of-type records "extlang")
        :let [prefix (syntax/lower (field r "Prefix"))]]
    (if (and (= (subtag r) (syntax/lower (field r "Preferred-Value")))
             (re-matches #"[a-z]{2,3}" prefix))
      (str (subtag r) "=" prefix)
      (throw (ex-info (str "An extlang whose Preferred-Value isn't itself, "
                           "or whose Prefix isn't a language")
                      r)))))

;; RFC 5646, section 4.5, which replaces each subtag once
(defn preferred
  "Each record of the `records` that has a Preferred-Value, with it, e.g.
  iw=he. The value of a subtag must be one subtag without a value of its
  own."
  [records]
  (let [values (into {} (for [r records
                              :let [v (field r "Preferred-Value")]
                              :when v]
                          [(subtag r) (syntax/lower v)]))]
    (doseq [[k v] values
            :when (or (contains? values v)
                      (and (not (str/includes? k "-")) (str/includes? v "-")))]
      (throw (ex-info "A Preferred-Value that can't replace its subtag once"
                      {:subtag k :preferred-value v})))
    (for [[k v] values]
      (str k "=" v))))

(defn table
  "The text of the table of the registry `text`."
  [text]
  (let [[head & records] (records text)
        typed            #(of-type records %)
        subtags          #(map subtag (typed %))
        line             #(str/join " " (cons %1 (sort %2)))]
    (str/join "\n"
              [(line "file-date" [(field head "File-Date")])
               (line "languages" [(languages records)])
               (line "extlangs" (prefixes records))
               (line "scripts" (mapcat expand (subtags "script")))
               (line "regions" (mapcat expand (subtags "region")))
               (line "variants" (subtags "variant"))
               (line "preferred-languages" (preferred (typed "language")))
               (line "preferred-scripts" (preferred (typed "script")))
               (line "preferred-regions" (preferred (typed "region")))
               (line "preferred-variants" (preferred (typed "variant")))
               (line "preferred-tags" (preferred (concat (typed "grandfathered")
                                                         (typed "redundant"))))])))

(defn write!
  "Write the table to resources."
  [_]
  (let [text (table (slurp source))]
    (io/make-parents target)
    (spit target (str text "\n"))
    (println target (count text) "characters")))

(comment
  (take 3 (records (slurp source)))
  (write! nil)
  #_.)
