(ns ^:no-doc dk.simongray.langtag.impl.registry
  "Validity, the canonical form and the extlang form of language tags by
  the IANA Language Subtag Registry, whose table is read on first use.

  The table in resources is made by dev/dk/simongray/langtag/tables.clj,
  whose docstring describes it.

  TODO: the fields that the library doesn't use yet:

  - Suppress-Script, e.g. Latn for en
  - Macrolanguage, e.g. no for nb
  - the Prefix of a variant, e.g. sl for rozaj"
  #?(:cljs (:require-macros [dk.simongray.langtag.impl.registry :refer [table-text file-date]]))
  (:require [clojure.string :as str]
            #?(:clj [clojure.java.io :as io])
            [dk.simongray.langtag.impl.syntax :as syntax]))

#?(:clj
   (def table-path
     "The table on the classpath."
     "dk/simongray/langtag/registry.txt"))

#?(:clj
   ;; ClojureScript carries the table as a string literal, which the JVM
   ;; can't, as a constant is at most 64 KB there
   (defmacro table-text
     "The text of the table, as a string literal in ClojureScript, and read
     when it's evaluated on the JVM."
     []
     (if (:ns &env)
       (slurp (io/resource table-path))
       `(slurp (io/resource ~table-path)))))

#?(:clj
   (defmacro file-date
     "The File-Date of the registry that the table is made from, as a string
     literal, e.g. \"2026-09-17\"."
     []
     (with-open [r (io/reader (io/resource table-path))]
       (subs (first (line-seq r)) (count "file-date ")))))

;; a split on a string rather than with clojure.string/split, whose two
;; branches Closure gave a copy each of the table's literal
(defn- split
  "The parts of `s` between each `separator`, as an array."
  [s separator]
  #?(:clj  (.split ^String s ^String separator)
     :cljs (.split s separator)))

(defn- entries
  "The entries of each line of the table `text`, by the line's name as a
  keyword."
  [text]
  (into {}
        (for [line (split text "\n")
              :let [[k & vs] (split line " ")]]
          [(keyword k) vs])))

(defn- pairs
  "The map of the entries `vs` such as iw=he."
  [vs]
  (into {} (map #(vec (split % "="))) vs))

(def table
  "The parts of the registry, read on first use."
  (delay
    (let [m    (entries (table-text))
          tags (pairs (:preferred-tags m))]
      {:languages (first (:languages m))
       :extlangs  (pairs (:extlangs m))
       :scripts   (set (:scripts m))
       :regions   (set (:regions m))
       :variants  (set (:variants m))
       :preferred {:language (pairs (:preferred-languages m))
                   :script   (pairs (:preferred-scripts m))
                   :region   (pairs (:preferred-regions m))
                   :variant  (pairs (:preferred-variants m))
                   :tag      tags}
       ;; the languages of the redundant tags among them, e.g. sgn for
       ;; sgn-BR, without the grandfathered tags, which parse whole
       :redundant (->> (keys tags)
                       (remove syntax/grandfathered)
                       (map #(first (split % "-")))
                       set)})))

(defn base-26
  "The number of the lower-case letters `s`, read in base 26."
  [s]
  (reduce (fn [n i]
            (let [c #?(:clj  (int (.charAt ^String s i))
                       :cljs (.charCodeAt s i))]
              (+ (* 26 n) (- c 97))))
          0
          (range (count s))))

(def language-bits
  "The number of bits for languages: one for each combination of two
  letters, then of three."
  (+ (* 26 26) (* 26 26 26)))

(defn language-bit
  "The bit of the language subtag `s` of two or three lower-case letters."
  [s]
  (cond-> (base-26 s)
    (= 3 (count s)) (+ (* 26 26))))

(defn- hex-digit
  "The number of the hexadecimal digit at index `i` of `s`."
  [s i]
  #?(:clj  (Character/digit (.charAt ^String s (int i)) 16)
     :cljs (js/parseInt (.charAt s i) 16)))

(defn language?
  "Whether the lower-case `s` is a language subtag of the registry."
  [s]
  (boolean
   (when (re-matches #"^[a-z]{2,3}$" s)
     (let [i (language-bit s)]
       (bit-test (hex-digit (:languages @table) (quot i 4)) (rem i 4))))))

(defn registered?
  "Whether the lower-case `subtag` of kind `k` (:language, :extlang,
  :script, :region or :variant) is in the registry."
  [k subtag]
  (case k
    :language (language? subtag)
    :extlang  (contains? (:extlangs @table) subtag)
    :script   (contains? (:scripts @table) subtag)
    :region   (contains? (:regions @table) subtag)
    :variant  (contains? (:variants @table) subtag)))

(defn preferred
  "The Preferred-Value of the lower-case `x` of kind `k` (:language,
  :script, :region, :variant or :tag, a grandfathered or redundant tag), or
  nil."
  [k x]
  (get-in @table [:preferred k x]))

(defn prefix
  "The Prefix of the lower-case extlang `subtag`, e.g. zh for yue, or nil
  when it isn't an extlang."
  [subtag]
  (get (:extlangs @table) subtag))

;; RFC 5646, section 2.2.9
(defn subtags
  "The language, extlangs, script, region and variants of the map `m` of a
  tag, in its order, each as a pair of its kind and its lower-case subtag."
  [{:keys [language extlangs script region variants]}]
  (for [[k s] (concat [[:language language]]
                      (map #(vector :extlang %) extlangs)
                      [[:script script] [:region region]]
                      (map #(vector :variant %) variants))
        :when s]
    [k (syntax/lower s)]))

;; RFC 5646, section 2.2.2, rule 4, for a second or third extlang
(defn invalid
  "The reason that a subtag of the map `m` of a well-formed tag isn't
  valid, with its index, or nil."
  [m]
  (first (keep-indexed (fn [i [k s]]
                         (cond
                           (and (= :extlang k) (< 1 i))
                           {:reason :unexpected-subtag :index i}

                           (not (registered? k s))
                           {:reason :unregistered-subtag :index i}))
                       (subtags m))))

(defn error
  "The reason that the tag `s` isn't well formed or valid, or nil. The
  error names the subtag as written in `s`."
  [s]
  (let [{:keys [tag error]} (syntax/read-tag s)]
    (or error
        (when-let [e (invalid tag)]
          (assoc e :subtag (nth (str/split (str s) #"-") (:index e)))))))

;; RFC 5646, section 4.5, step 3
(defn replace-extlang
  "The map `m` of a tag with its first extlang as the language instead,
  when the extlang is registered, e.g. yue for zh-yue."
  [{[extlang & more] :extlangs :as m}]
  (if (prefix extlang)
    (assoc m :language extlang :extlangs (vec more))
    m))

(defn preferred-subtag
  "The Preferred-Value of the `subtag` of kind `k`, or the subtag."
  [k subtag]
  (or (preferred k (syntax/lower subtag)) subtag))

;; RFC 5646, section 4.5, step 3, with the case of section 2.1.1
(defn replace-subtags
  "The map `m` of a tag with each subtag replaced by its Preferred-Value,
  in canonical case."
  [{:keys [language script region variants] :as m}]
  (cond-> m
    language (assoc :language (preferred-subtag :language language))
    script   (assoc :script (syntax/title (preferred-subtag :script script)))
    region   (assoc :region (syntax/upper (preferred-subtag :region region)))
    variants (assoc :variants (->> variants
                                   (map #(preferred-subtag :variant %))
                                   distinct
                                   vec))))

;; RFC 5646, section 4.5, step 2; only a grandfathered tag or one with the
;; language of a redundant tag is written as text to look it up, since
;; writing every tag took most of the time of the canonical form
(defn replace-tag
  "The map `m` of a tag, or the map of its Preferred-Value when it's a
  grandfathered or redundant tag that has one, e.g. tlh for i-klingon."
  [{:keys [grandfathered language] :as m}]
  (if-let [v (and (or grandfathered (contains? (:redundant @table) language))
                  (preferred :tag (syntax/lower (syntax/join m))))]
    (:tag (syntax/read-tag v))
    m))

;; RFC 5646, section 4.5, whose step 1 is join's; step 2 comes again after
;; step 3 for a tag that it makes redundant, e.g. sgn-DD as sgn-DE
(defn canonical
  "The map `m` of a well-formed tag in canonical form."
  [m]
  ;; the extlang before the language, since a deprecated extlang such as
  ;; the ajp of ar-ajp is its own Preferred-Value, while the language ajp
  ;; has apc
  (-> m replace-tag replace-extlang replace-subtags replace-tag))

;; RFC 5646, section 4.5
(defn extlang
  "The map `m` of a tag in canonical form, in extlang form instead: with the
  Prefix of its language before it when the language is also an extlang,
  e.g. zh-hak-CN for hak-CN."
  [{:keys [language] :as m}]
  (if-let [p (some-> language prefix)]
    (assoc m :language p :extlangs (into [language] (:extlangs m)))
    m))

;; RFC 4647, section 3.2, which has a matcher that canonicalizes the ranges
;; canonicalize the tags too
(defn tag-form
  "The tag `x` in extlang form, or `x` itself when it isn't a well-formed
  tag, e.g. nil."
  [x]
  (or (when (string? x)
        (some-> (:tag (syntax/read-tag x)) canonical extlang syntax/join))
      x))

;; memoized, since items often share a tag, which made basic filtering of
;; a thousand documents five times faster
(defn in-tag-form
  "The function `f` of an item to its tag, with the tag in extlang form."
  [f]
  (comp (memoize tag-form) f))

;; RFC 4647, section 3.3.2, where a * after the first subtag matches what
;; it would without; und stands in for a * at the start, so that the rest
;; can be read as a tag
(defn range-form
  "The `range` in extlang form and lower case, without the wildcards after
  its first subtag, or `range` itself when the rest isn't a well-formed
  tag."
  [range]
  (if-not (string? range)
    range
    (let [[s & more] (str/split range #"-")
          wildcard?  (= "*" s)
          subtags    (cons (if wildcard? "und" s) (remove #{"*"} more))
          m          (some-> (:tag (syntax/read-tag (str/join "-" subtags)))
                             canonical
                             extlang)]
      (cond
        (nil? m)                range
        (not wildcard?)         (syntax/lower (syntax/join m))
        (= "und" (:language m)) (syntax/lower (syntax/join (assoc m :language "*")))
        :else                   range))))

(comment
  (file-date)
  (language? "da")
  (registered? :region "uk")
  (preferred :language "iw")
  (prefix "yue")
  (invalid (:tag (syntax/read-tag "en-UK")))
  (syntax/join (canonical (:tag (syntax/read-tag "zh-yue-BU-heploc"))))
  (syntax/join (extlang (canonical (:tag (syntax/read-tag "hak-CN")))))
  #_.)
