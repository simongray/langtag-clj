(ns dk.simongray.langtag.registry-test
  "Validity, the canonical and extlang forms by the examples of RFC 5646,
  and matching in extlang form."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.simongray.langtag.registry :as registry]))

;; RFC 5646, section 2.2.9
(deftest validity
  (doseq [s ["en-GB" "de-CH-1901" "zh-yue-HK" "sl-IT-nedis" "en-US-u-islamcal"
             "i-default" "x-whatever" "qaa-Qaaa-QM" "en-BU" "iw"]]
    (is (registry/valid? s) s))
  (is (= {:reason :unregistered-subtag :index 1 :subtag "UK"} (registry/error "en-UK")))
  (is (= {:reason :unregistered-subtag :index 0 :subtag "xx"} (registry/error "xx-GB")))
  (is (= {:reason :unregistered-subtag :index 1 :subtag "Abcd"} (registry/error "en-Abcd")))
  (is (= {:reason :unregistered-subtag :index 2 :subtag "1902"} (registry/error "de-CH-1902")))
  (testing "a second extlang (section 2.2.2)"
    (is (= {:reason :unexpected-subtag :index 2 :subtag "gan"} (registry/error "zh-yue-gan"))))
  (testing "the errors of a tag that isn't well formed"
    (is (= {:reason :empty-subtag :index 1 :subtag ""} (registry/error "en--US")))
    (is (not (registry/valid? nil)))))

;; RFC 5646, section 4.5
(deftest canonical-form
  (doseq [[s form] {"en-BU"                    "en-MM"
                    "zh-hakka"                 "hak"
                    "zh-hak-CN"                "hak-CN"
                    "ar-ajp"                   "apc"
                    "en-b-ccc-bbb-a-aaa-X-xyz" "en-a-aaa-b-ccc-bbb-x-xyz"
                    "iw-IL"                    "he-IL"
                    "no-nyn"                   "nn"
                    "i-klingon"                "tlh"
                    "art-lojban"               "jbo"
                    "en-GB-oed"                "en-GB-oxendict"
                    "sgn-BR"                   "bzs"
                    "sgn-DD"                   "gsg"
                    "zh-cmn-Hans"              "cmn-Hans"
                    "ja-Latn-hepburn-heploc"   "ja-Latn-hepburn-alalc97"
                    "ja-Latn-alalc97-heploc"   "ja-Latn-alalc97"
                    "i-default"                "i-default"
                    "x-whatever"               "x-whatever"
                    "EN-uk"                    "en-UK"
                    "en_US"                    nil}]
    (is (= form (registry/canonical-form s)) s))
  (is (= "he-IL" (registry/canonical-form {:language "iw" :region "il"}))))

;; RFC 5646, section 4.5
(deftest extlang-form
  (doseq [[s form] {"hak-CN"    "zh-hak-CN"
                    "zh-hak-CN" "zh-hak-CN"
                    "zh-hakka"  "zh-hak"
                    "sgn-BR"    "sgn-bzs"
                    "yue-HK"    "zh-yue-HK"
                    "en"        "en"
                    "i-default" "i-default"}]
    (is (= form (registry/extlang-form s)) s)))

(deftest matching-in-extlang-form
  (testing "a deprecated subtag matches the one that replaced it, either way"
    (is (= "he" (registry/lookup "iw, en;q=0.8" ["en" "he"])))
    (is (= "iw" (registry/lookup "he, en;q=0.8" ["en" "iw"])))
    (is (= ["en-BU" "en-MM"] (registry/basic-filter "en-MM" ["en-BU" "en-MM" "en"])))
    (is (= ["en-MM" "my-BU"] (registry/extended-filter "*-BU" ["en-MM" "my-BU" "my"]))))
  (testing "a macrolanguage matches the languages that are also its extlangs"
    (is (= ["yue-HK" "zh-TW"] (registry/basic-filter "zh" ["yue-HK" "zh-TW" "en"])))
    (is (= "zh" (registry/lookup "yue-HK" ["en" "zh"])))
    (is (registry/extended-match? "zh-*-HK" "yue-Hant-HK")))
  (testing "a range that repeats an earlier one in extlang form"
    (is (= ["he" "iw"] (registry/basic-filter "he, iw;q=0" ["he" "iw"]))))
  (testing "a tag or range that isn't well formed is compared as it is"
    (is (= ["en-a"] (registry/basic-filter "en" ["en-a" nil "de"])))
    (is (registry/basic-match? "*" "x-whatever"))
    (is (not (registry/basic-match? nil "he")))))
