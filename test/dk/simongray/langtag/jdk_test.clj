(ns dk.simongray.langtag.jdk-test
  "The library compared with the JDK's BCP 47 support on random input.

  The tests keep away from the places where the JDK differs:

  - Locale$Builder replaces grandfathered and deprecated tags (e.g. tlh
    for i-klingon) and the extlang form (yue for zh-yue).
  - It drops a repeated extension, keeps the case of variants and sorts
    the keywords of -u-.
  - It refuses a digit as a singleton (en-1-co), which RFC 5646 allows,
    and allows an extlang after a language of four or more letters
    (latn-yue), which RFC 5646 doesn't.
  - Locale$LanguageRange/parse adds equivalents from its copy of the
    registry, e.g. he after iw.
  - A range of weight 0 rules out every tag it matches, even by lookup's
    fallback, and *;q=0 rules out every tag.
  - In extended filtering de-* doesn't match de, and lookup reads * in a
    range as any subtag.
  - Before Java 18, filtering with the range * returns every tag in the
    order given.
  - Filtering returns a tag once, even when the tags have it twice."
  (:require [clojure.string :as str]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [dk.simongray.langtag :as langtag]
            [dk.simongray.langtag.generators :as g])
  (:import [java.util IllformedLocaleException Locale$Builder Locale$FilteringMode
            Locale$LanguageRange Locale]))

(defn jdk-tag
  "The tag `s` as the JDK writes it, or nil when it's ill formed."
  [s]
  (try
    (.toLanguageTag (.build (.setLanguageTag (Locale$Builder.) s)))
    (catch IllformedLocaleException _ nil)))

(defn jdk-difference?
  "Whether the JDK reads the text `s` differently, since it has one of
  these:

  - a repeated variant or singleton
  - a subtag of one digit, e.g. a singleton
  - three letters after a first subtag of four or more"
  [s]
  (or (#{:duplicate-variant :duplicate-singleton} (:reason (langtag/error s)))
      (some #(re-matches #"[0-9]" %) (str/split s #"-"))
      (re-find #"^[A-Za-z]{4,8}-[A-Za-z]{3}(?:-|$)" s)))

(defspec well-formed-as-the-jdk-reads-it 3000
  (prop/for-all [s g/text]
    (or (jdk-difference? s)
        (= (langtag/well-formed? s) (some? (jdk-tag s))))))

(defspec canonical-case-as-the-jdk-writes-it 2000
  (prop/for-all [s g/tag]
    (let [ours   (langtag/tag s)
          theirs (jdk-tag s)
          {:keys [grandfathered extlangs variants extensions]} (langtag/parse s)]
      (cond
        (or (jdk-difference? s)
            grandfathered
            extlangs
            (contains? extensions "u")) true
        variants                        (= (str/lower-case ours) (str/lower-case theirs))
        :else                           (= ours theirs)))))

(defn jdk-ranges
  "The ranges and weights of the JDK's priority list of `s`."
  [s]
  (map (fn [^Locale$LanguageRange r] [(.getRange r) (.getWeight r)])
       (Locale$LanguageRange/parse s)))

(defn ranges
  "The ranges, in lower case, and weights of the priority list of `s`."
  [s]
  (map (juxt (comp str/lower-case :range) :weight) (langtag/priority-list s)))

(defspec priority-lists-as-the-jdk-parses-them 2000
  (prop/for-all [s (g/accept-language g/extended-range)]
    (let [theirs (jdk-ranges s)]
      (or (not= (count theirs) (count (ranges s)))  ; the JDK added equivalents
          (= (ranges s) theirs)))))

(defn ->jdk
  "The priority list of `s` as the JDK's ranges, without equivalents."
  [s]
  (map (fn [{:keys [range weight]}] (Locale$LanguageRange. range weight))
       (langtag/priority-list s)))

(defn star-before-java-18?
  "Whether a range of the Accept-Language value `s` starts with * and the
  JDK is older than Java 18."
  [s]
  (and (< (.feature (Runtime/version)) 18)
       (some #(str/starts-with? (:range %) "*") (langtag/priority-list s))))

(defspec basic-filtering-as-the-jdk-does-it 3000
  (prop/for-all [s  (g/accept-language g/extended-range)
                 ts g/tags]
    (or (star-before-java-18? s)
        (= (langtag/basic-filter s ts)
           (Locale/filterTags (->jdk s) ts Locale$FilteringMode/MAP_EXTENDED_RANGES)))))

(defspec extended-filtering-as-the-jdk-does-it 3000
  (prop/for-all [s  (g/accept-language (gen/fmap #(str/replace % #"(-\*)+$" "")
                                                 g/extended-range))
                 ts g/tags]
    (or (star-before-java-18? s)
        (= (langtag/extended-filter s ts)
           (Locale/filterTags (->jdk s) ts Locale$FilteringMode/EXTENDED_FILTERING)))))

(defspec lookup-as-the-jdk-does-it 3000
  (prop/for-all [s  (g/accept-language g/basic-range)
                 ts g/tags]
    (= (langtag/lookup s ts)
       (Locale/lookupTag (->jdk s) ts))))
