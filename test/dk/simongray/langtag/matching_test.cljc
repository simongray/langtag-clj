(ns dk.simongray.langtag.matching-test
  "Filtering and lookup by the examples of RFC 4647, and priority lists
  from Accept-Language by RFC 9110."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.simongray.langtag :as langtag]
            [dk.simongray.langtag.impl.matching :as matching]))

;; RFC 4647, section 3.3.1
(deftest basic-filtering
  (is (langtag/basic-match? "de-de" "de-DE-1996"))
  (is (not (langtag/basic-match? "de-de" "de-Deva")))
  (is (not (langtag/basic-match? "de-de" "de-Latn-DE")))
  (is (= ["de-DE-1996"] (langtag/basic-filter "de-de" ["de-DE-1996" "de-Deva" "de-Latn-DE"])))
  (testing "the range * matches any tag"
    (is (= ["de" "x-foo" "i-klingon"] (langtag/basic-filter "*" ["de" "x-foo" "i-klingon"]))))
  (testing "a range matches whole subtags"
    (is (not (langtag/basic-match? "de" "del")))
    (is (not (langtag/basic-match? "de-CH" "de"))))
  (testing "in order of priority, each item once"
    (is (= ["fr-CA" "en-US" "en" "en-US"]
           (langtag/basic-filter "fr, en" ["en-US" "fr-CA" "en" "en-US"])))
    (is (= ["en-US" "en"] (langtag/basic-filter "en-US, en" ["en" "en-US"])))))

(def extended-matches
  "The tags that de-*-DE matches in RFC 4647, section 3.3.2."
  ["de-DE" "de-de" "de-Latn-DE" "de-Latf-DE" "de-DE-x-goethe" "de-Latn-DE-1996"
   "de-Deva-DE"])

(def extended-mismatches
  "The tags that de-*-DE doesn't match in RFC 4647, section 3.3.2."
  ["de" "de-x-DE" "de-Deva"])

;; RFC 4647, section 3.3.2
(deftest extended-filtering
  (doseq [range ["de-*-DE" "de-DE"]]
    (testing range
      (doseq [tag extended-matches]
        (is (langtag/extended-match? range tag) tag))
      (doseq [tag extended-mismatches]
        (is (not (langtag/extended-match? range tag)) tag))
      (is (= extended-matches
             (langtag/extended-filter range (concat extended-matches extended-mismatches))))))
  (testing "a wildcard as the first subtag"
    (is (= ["de-CH" "fr-CH"] (langtag/extended-filter "*-CH" ["de-CH" "fr-CH" "it-IT"]))))
  (testing "a wildcard at the end can match nothing"
    (is (langtag/extended-match? "de-*" "de"))
    (is (langtag/extended-match? "*" "x-foo"))))

;; RFC 4647, section 3.2
(deftest extended-ranges-as-basic-ranges
  (is (= "en-US" (matching/basic-range "en-*-US")))
  (is (= "*" (matching/basic-range "*-CH")))
  (is (= ["en-US"] (langtag/basic-filter "en-*-US" ["en-US" "en-Latn-US"])))
  (is (= ["de-CH" "fr"] (langtag/basic-filter "*-CH" ["de-CH" "fr"])))
  (is (= "en-US" (langtag/lookup "en-*-US" ["en" "en-US"]))))

;; RFC 4647, section 3.4
(deftest lookup
  (testing "Example of a Lookup Fallback Pattern"
    (is (= ["zh-Hant-CN-x-private1-private2" "zh-Hant-CN-x-private1" "zh-Hant-CN"
            "zh-Hant" "zh"]
           (matching/fallbacks "zh-Hant-CN-x-private1-private2")))
    (is (= "zh-Hant" (langtag/lookup "zh-Hant-CN-x-private1-private2" ["zh" "zh-Hant"]))))
  (testing "de-ch finds de or de-CH but never de-CH-1996"
    (is (= "de-CH" (langtag/lookup "de-ch" ["de-CH-1996" "de" "de-CH"])))
    (is (= "de" (langtag/lookup "de-ch" ["de-CH-1996" "de"])))
    (is (nil? (langtag/lookup "de-ch" ["de-CH-1996"]))))
  (testing "a single letter goes with the subtag after it"
    (is (= ["en-a-bbb-x-aaa-ccc" "en-a-bbb-x-aaa" "en-a-bbb" "en"]
           (matching/fallbacks "en-a-bbb-x-aaa-ccc")))
    (is (= ["en-x-a-b-c" "en-x-a" "en"]
           (matching/fallbacks "en-x-a-b-c")))
    (is (= "en-x-a" (langtag/lookup "en-x-a-b-c" ["en" "en-x-a"]))))
  (testing "the range * is skipped"
    (is (= "fr" (langtag/lookup "*, fr" ["de" "fr"])))
    (is (nil? (langtag/lookup "*" ["de" "fr"]))))
  ;; RFC 4647, section 3.4.1, with ja-JP as the default range
  (testing "each range before the default"
    (is (= "zh" (langtag/lookup ["fr-FR" "zh-Hant" "ja-JP"] ["ja" "zh"])))
    (is (= "ja" (langtag/lookup ["fr-FR" "zh-Hant" "ja-JP"] ["ja" "zh-CN"])))
    (is (= "en" (or (langtag/lookup ["fr-FR" "zh-Hant" "ja-JP"] ["en"]) "en"))))
  (testing "the tag as it's given"
    (is (= "DA" (langtag/lookup "da-DK" ["en" "DA"])))))

;; RFC 9110, section 12.5.4
(deftest accept-language
  (is (= [{:range "da" :weight 1.0} {:range "en-gb" :weight 0.8} {:range "en" :weight 0.7}]
         (langtag/priority-list "da, en-gb;q=0.8, en;q=0.7")))
  (is (= "da" (langtag/lookup "da-DK, en;q=0.8" ["da" "en"])))
  (testing "weight before order"
    (is (= ["de" "fr" "en"] (map :range (langtag/priority-list "fr;q=0.5, de, en;q=0.5")))))
  (testing "the grammar"
    (is (= [{:range "en-GB" :weight 0.5}] (langtag/priority-list " en-GB\t; Q=0.5 ")))
    (is (= [{:range "da" :weight 1.0} {:range "en" :weight 1.0}]
           (langtag/priority-list "da,, ,en,")))
    (is (= [{:range "*" :weight 0.001} {:range "sv" :weight 0.0}]
           (langtag/priority-list "*;q=0.001, sv;q=0., da;q=0.1234, de;q=1.5, en_US, fr;q=.5, nl;level=1")))
    (is (= [] (langtag/priority-list ""))))
  (testing "a range that repeats an earlier one"
    (is (= [{:range "en" :weight 0.5}] (langtag/priority-list "en;q=0.5, EN"))))
  (testing "a coll of ranges"
    (is (= [{:range "da" :weight 1.0} {:range "en" :weight 1.0}] (langtag/priority-list ["da" "en" "da-"])))
    (is (= [{:range "en" :weight 1.0} {:range "da" :weight 0.5}]
           (langtag/priority-list [{:range "da" :weight 0.5} {:range "en"}])))))

;; DanNet's languages, which an exact comparison of whole tags misses
(deftest dannet
  (doseq [[accept-language language] {"da-DK"                  "da"
                                      "en-GB"                  "en"
                                      "DA"                     "da"
                                      "en-US,en;q=0.9,da;q=0.8" "en"
                                      "de-DE, da;q=0.5"         "da"
                                      "fr"                     nil}]
    (is (= language (langtag/lookup accept-language ["da" "en"])) accept-language)))

(def labels
  "The labels of a synset as DanNet's select-label sees them: in Danish, in
  British English and as a plain string without a language."
  [{:text "hund" :lang "da"} {:text "dog" :lang "en-GB"} "dog"])

(deftest key-function
  (let [[hund dog] labels]
    (is (= [dog hund] (langtag/basic-filter ["en" "da"] :lang labels)))
    (is (= [dog] (langtag/extended-filter "*-GB" :lang labels)))
    (is (= hund (langtag/lookup "da-DK, en;q=0.8" :lang labels)))
    (is (= dog (langtag/lookup "en-GB-oxendict" :lang labels)))
    (testing "lookup finds no tag more specific than the range"
      (is (nil? (langtag/lookup "en" :lang labels))))
    (testing "the plain string matches no range, not even *"
      (is (= [hund dog] (langtag/basic-filter "*" :lang labels))))))

(deftest nil-tags
  (is (not (langtag/basic-match? "*" nil)))
  (is (not (langtag/extended-match? "*" nil)))
  (is (= ["da"] (langtag/basic-filter "*" [nil "da"])))
  (is (= ["da"] (langtag/extended-filter "da" [nil "da"])))
  (is (= "da" (langtag/lookup "da" [nil "da"]))))

;; RFC 9110, section 12.4.2: "a value of 0 means 'not acceptable'"
(deftest weight-zero
  (testing "the most specific range decides, as in RFC 2616, section 14.4"
    (is (= ["en" "en-US"] (langtag/basic-filter "en, en-GB;q=0" ["en" "en-GB" "en-US"])))
    (is (= ["en-GB"] (langtag/basic-filter "en-GB, en;q=0" ["en-GB" "en-US"])))
    (is (= ["en-US"] (langtag/extended-filter "en, *-GB;q=0" ["en-GB" "en-US"]))))
  (testing "* only stands for the tags that no other range matches"
    (is (= ["da"] (langtag/basic-filter "da, *;q=0" ["da" "en"])))
    (is (= "da" (langtag/lookup "da, *;q=0" ["da" "en"]))))
  (testing "in lookup"
    (is (= "en" (langtag/lookup "en-GB;q=0, en;q=0.5" ["en-GB" "en"])))
    (is (nil? (langtag/lookup "en-GB, en;q=0" ["en"])))
    (is (nil? (langtag/lookup "en-GB-oed, en-GB;q=0" ["en-GB"])))))
