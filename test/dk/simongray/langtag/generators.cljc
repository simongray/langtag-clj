(ns dk.simongray.langtag.generators
  "Random tags, ranges and Accept-Language values for the generative tests.

  Most subtags come from small sets, so that ranges and tags often match.
  Their case is random, since case carries no meaning."
  (:require [clojure.string :as str]
            [clojure.test.check.generators :as gen]))

(def letters
  "The ASCII letters."
  "abcdefghijklmnopqrstuvwxyz")

(def alphanums
  "The ASCII letters and digits."
  "abcdefghijklmnopqrstuvwxyz0123456789")

(defn chars-of
  "Text of `min` to `max` characters of the text `cs`."
  [cs min max]
  (gen/fmap str/join (gen/vector (gen/elements cs) min max)))

(defn some-of
  "One of the `common` subtags most often, or else one from the generator
  `random`."
  [common random]
  (gen/frequency [[4 (gen/elements common)] [1 random]]))

(def random-case
  "A function that changes the case of each letter of a text at random."
  (gen/fmap (fn [ups]
              (fn [s]
                (str/join (map #(if %2 (str/upper-case %1) (str/lower-case %1))
                               s (cycle ups)))))
            (gen/vector gen/boolean 1 5)))

(def language
  "A primary language subtag, which the JDK doesn't replace."
  (gen/such-that #(not (#{"iw" "in" "ji"} %))
                 (some-of ["en" "de" "da" "zh" "sr" "sl" "es" "cmn" "qaa"]
                          (gen/one-of [(chars-of letters 2 3)
                                       (chars-of letters 4 4)
                                       (chars-of letters 5 8)]))))

(def extlang
  "An extended language subtag."
  (some-of ["yue" "cmn" "min"] (chars-of letters 3 3)))

(def script
  "A script subtag."
  (some-of ["Latn" "Hant" "Cyrl"] (chars-of letters 4 4)))

(def region
  "A region subtag."
  (some-of ["US" "GB" "DE" "CH" "419"]
           (gen/one-of [(chars-of letters 2 2) (chars-of "0123456789" 3 3)])))

(def variant
  "A variant subtag."
  (some-of ["1901" "rozaj" "biske" "1abc"]
           (gen/one-of [(chars-of alphanums 5 8)
                        (gen/fmap str/join (gen/tuple (gen/elements "0123456789")
                                                      (chars-of alphanums 3 3)))])))

(def singleton
  "The singleton of an extension."
  (some-of ["a" "b" "u" "1"] (gen/elements "0123456789abcdefghijklmnopqrstuvwyz")))

(def extension-subtag
  "A subtag of an extension."
  (some-of ["co" "phonebk" "bbb"] (chars-of alphanums 2 8)))

(def private-use-subtag
  "A subtag of a private use sequence."
  (some-of ["a" "private" "1"] (chars-of alphanums 1 8)))

(defn distinct-by-lower
  "Vectors of `min` to `max` values of `g`, no two alike but for case."
  [g min max]
  (gen/fmap #(vec (vals (into {} (map (juxt str/lower-case identity)) %)))
            (gen/vector g min max)))

(def private-use
  "The subtags of a private use sequence."
  (gen/vector private-use-subtag 1 3))

(def extensions
  "The subtags of extensions with distinct singletons."
  (gen/fmap #(mapcat (fn [[s subtags]] (cons s subtags)) %)
            (gen/bind (distinct-by-lower singleton 0 3)
                      (fn [singletons]
                        (gen/fmap #(map vector singletons %)
                                  (gen/vector (gen/vector extension-subtag 1 3)
                                              (count singletons)))))))

(def langtag
  "The subtags of a tag of the langtag production, with no repeated
  variant or singleton."
  (gen/let [language   language
            extlangs   (gen/vector extlang 0 3)
            script     (gen/one-of [(gen/return nil) script])
            region     (gen/one-of [(gen/return nil) region])
            variants   (distinct-by-lower variant 0 2)
            extensions extensions
            private    (gen/one-of [(gen/return nil) private-use])]
    (concat [language]
            (when (<= (count language) 3) extlangs)
            [script region]
            variants
            extensions
            (when private (cons "x" private)))))

(def tag
  "A well-formed tag in random case, mostly of the langtag production."
  (gen/let [subtags (gen/frequency [[9 langtag]
                                    [1 (gen/fmap #(cons "x" %) private-use)]])
            recase  random-case]
    (recase (str/join "-" (remove nil? subtags)))))

(def grandfathered
  "A grandfathered tag."
  (gen/elements ["en-GB-oed" "i-klingon" "sgn-BE-FR" "zh-min-nan" "art-lojban"]))

(def subtag
  "A subtag of any kind, or a broken one."
  (gen/frequency [[3 language] [1 extlang] [1 script] [1 region] [2 variant]
                  [2 singleton] [1 (gen/return "x")] [2 extension-subtag]
                  [1 (gen/elements ["" "abcdefghi" "a_b" "é" " "])]]))

(def text
  "Text that's a tag or near one: subtags of random kinds in random order,
  or a well-formed tag with a subtag changed."
  (gen/one-of [(gen/fmap #(str/join "-" %) (gen/vector subtag 1 6))
               (gen/let [s   tag
                         t   subtag
                         i   gen/nat]
                 (let [subtags (str/split s #"-")]
                   (str/join "-" (assoc subtags (mod i (count subtags)) t))))]))

(def basic-range
  "A basic range: the start of a tag, or *."
  (gen/frequency [[9 (gen/let [s tag
                               n (gen/choose 1 4)]
                       (->> (str/split s #"-")
                            (take n)
                            (str/join "-")))]
                  [1 (gen/return "*")]]))

(def extended-range
  "An extended range: a basic range with * for some subtags."
  (gen/let [range basic-range
            stars (gen/vector gen/boolean 1 4)]
    (->> (map #(if %2 "*" %1) (str/split range #"-") (cycle stars))
         (str/join "-"))))

(def qvalue
  "A qvalue above 0, as RFC 9110 writes them."
  (gen/elements ["1" "1.0" "0.9" "0.85" "0.5" "0.123" "0.001"]))

(defn accept-language
  "An Accept-Language value of ranges from the generator `range`, each with
  or without a qvalue."
  [range]
  (gen/fmap #(str/join ", " %)
            (gen/vector (gen/let [r range
                                  q (gen/one-of [(gen/return nil) qvalue])]
                          (str r (when q (str ";q=" q))))
                        1 4)))

(def short-tag
  "A tag of a language and maybe a script or a region, which lookup finds
  more often than a long one."
  (gen/let [language language
            more     (gen/vector (gen/one-of [script region]) 0 1)
            recase   random-case]
    (recase (str/join "-" (cons language more)))))

(def tags
  "A few well-formed tags, distinct but for case."
  (distinct-by-lower (gen/one-of [tag short-tag]) 0 8))
