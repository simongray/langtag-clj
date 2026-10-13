(ns dk.simongray.langtag-test
  "Tags as maps, their canonical case and the reasons a tag isn't well
  formed, by the examples of RFC 5646."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.langtag :as langtag]))

;; RFC 5646, Appendix A
(def examples
  "Each example tag of Appendix A to its map."
  {;; Simple language subtag
   "de"                     {:language "de"}
   "fr"                     {:language "fr"}
   "ja"                     {:language "ja"}
   "i-enochian"             {:grandfathered "i-enochian"}
   ;; Language subtag plus Script subtag
   "zh-Hant"                {:language "zh" :script "Hant"}
   "zh-Hans"                {:language "zh" :script "Hans"}
   "sr-Cyrl"                {:language "sr" :script "Cyrl"}
   "sr-Latn"                {:language "sr" :script "Latn"}
   ;; Extended language subtags and their primary language subtag
   ;; counterparts
   "zh-cmn-Hans-CN"         {:language "zh" :extlangs ["cmn"] :script "Hans" :region "CN"}
   "cmn-Hans-CN"            {:language "cmn" :script "Hans" :region "CN"}
   "zh-yue-HK"              {:language "zh" :extlangs ["yue"] :region "HK"}
   "yue-HK"                 {:language "yue" :region "HK"}
   ;; Language-Script-Region
   "zh-Hans-CN"             {:language "zh" :script "Hans" :region "CN"}
   "sr-Latn-RS"             {:language "sr" :script "Latn" :region "RS"}
   ;; Language-Variant
   "sl-rozaj"               {:language "sl" :variants ["rozaj"]}
   "sl-rozaj-biske"         {:language "sl" :variants ["rozaj" "biske"]}
   "sl-nedis"               {:language "sl" :variants ["nedis"]}
   ;; Language-Region-Variant
   "de-CH-1901"             {:language "de" :region "CH" :variants ["1901"]}
   "sl-IT-nedis"            {:language "sl" :region "IT" :variants ["nedis"]}
   ;; Language-Script-Region-Variant
   "hy-Latn-IT-arevela"     {:language "hy" :script "Latn" :region "IT" :variants ["arevela"]}
   ;; Language-Region
   "de-DE"                  {:language "de" :region "DE"}
   "en-US"                  {:language "en" :region "US"}
   "es-419"                 {:language "es" :region "419"}
   ;; Private use subtags
   "de-CH-x-phonebk"        {:language "de" :region "CH" :private-use ["phonebk"]}
   "az-Arab-x-AZE-derbend"  {:language "az" :script "Arab" :private-use ["aze" "derbend"]}
   ;; Private use registry values
   "x-whatever"             {:private-use ["whatever"]}
   "qaa-Qaaa-QM-x-southern" {:language "qaa" :script "Qaaa" :region "QM" :private-use ["southern"]}
   "de-Qaaa"                {:language "de" :script "Qaaa"}
   "sr-Latn-QM"             {:language "sr" :script "Latn" :region "QM"}
   "sr-Qaaa-RS"             {:language "sr" :script "Qaaa" :region "RS"}
   ;; Tags that use extensions
   "en-US-u-islamcal"       {:language "en" :region "US" :extensions {"u" ["islamcal"]}}
   "zh-CN-a-myext-x-private" {:language "zh" :region "CN" :extensions {"a" ["myext"]}
                              :private-use ["private"]}
   "en-a-myext-b-another"   {:language "en" :extensions {"a" ["myext"] "b" ["another"]}}})

(deftest appendix-a
  (doseq [[s m] examples]
    (testing s
      (is (langtag/well-formed? s))
      (is (nil? (langtag/error s)))
      (is (= m (langtag/parse s)))
      (is (= (langtag/tag s) (langtag/tag m))))))

;; RFC 5646, Appendix A, "Some Invalid Tags"
(deftest appendix-a-invalid
  (is (= {:reason :unexpected-subtag :subtag "DE" :index 2}
         (langtag/error "de-419-DE")))
  (is (= {:reason :unexpected-subtag :subtag "a" :index 0}
         (langtag/error "a-DE")))
  (is (= {:reason :duplicate-singleton :subtag "a" :index 5}
         (langtag/error "ar-a-aaa-b-bbb-a-ccc")))
  (doseq [s ["de-419-DE" "a-DE" "ar-a-aaa-b-bbb-a-ccc"]]
    (is (not (langtag/well-formed? s)))
    (is (nil? (langtag/parse s)))
    (is (nil? (langtag/tag s)))))

;; RFC 5646, section 2.1, the irregular and regular productions
(deftest grandfathered
  (doseq [s ["en-GB-oed" "i-ami" "i-bnn" "i-default" "i-enochian" "i-hak"
             "i-klingon" "i-lux" "i-mingo" "i-navajo" "i-pwn" "i-tao" "i-tay"
             "i-tsu" "sgn-BE-FR" "sgn-BE-NL" "sgn-CH-DE" "art-lojban"
             "cel-gaulish" "no-bok" "no-nyn" "zh-guoyu" "zh-hakka" "zh-min"
             "zh-min-nan" "zh-xiang"]]
    (testing s
      (is (= {:grandfathered s} (langtag/parse s)))
      (is (= s (langtag/tag (str/upper-case s))))
      (is (= s (langtag/tag {:grandfathered s})))))
  (testing "a tag that only starts like a grandfathered one"
    (is (= {:language "zh" :extlangs ["min" "nan"] :script "Hant"}
           (langtag/parse "zh-min-nan-Hant")))
    (is (= :unexpected-subtag (:reason (langtag/error "i-klingon-x"))))
    (is (nil? (langtag/tag {:grandfathered "i-foo"})))))

;; RFC 5646, section 2.1.1
(deftest canonical-case
  (doseq [s ["MN-cYRL-mn" "mN-cYrL-Mn" "mn-cyrl-mn"]]
    (is (= "mn-Cyrl-MN" (langtag/tag s))))
  (is (= "i-ami" (langtag/tag "I-AMI")))
  (is (= "en-CA-x-ca" (langtag/tag "EN-ca-X-CA")))
  (is (= "sgn-BE-FR" (langtag/tag "SGN-be-fr")))
  (is (= "az-Latn-x-latn" (langtag/tag "AZ-latn-x-LATN")))
  (is (= "en-GB" (langtag/tag "EN-gb")))
  (testing "extensions in the order of their singletons (section 4.5)"
    (is (= "en-a-bbb-u-co-phonebk" (langtag/tag "en-U-CO-phonebk-A-bbb")))))

(deftest maps
  (is (= "sr-Latn-RS" (langtag/tag {:language "sr" :script "latn" :region "rs"})))
  (is (= "en-GB" (langtag/tag {:language "EN" :region "gb" :variants []})))
  (is (= "en-a-bb-u-co-phonebk"
         (langtag/tag {:language "en" :extensions {"U" ["CO" "phonebk"] "a" ["bb"]}})))
  (is (= "x-foo" (langtag/tag {:private-use ["Foo"]})))
  (testing "maps of tags that aren't well formed"
    (doseq [m [{}
               {:region "GB"}
               {:language "en" :script "US"}
               {:language "en" :extensions {"x" ["aa"]}}
               {:language "en" :extensions {"a" ["b"]}}
               {:language "english" :extlangs ["yue"]}
               {:language "en" :foo 1}]]
      (is (nil? (langtag/tag m)) (pr-str m)))))

(deftest errors
  (doseq [[s error] {""                {:reason :empty-subtag :subtag "" :index 0}
                     "en--US"          {:reason :empty-subtag :subtag "" :index 1}
                     "en-"             {:reason :empty-subtag :subtag "" :index 1}
                     "en_US"           {:reason :invalid-subtag :subtag "en_US" :index 0}
                     "en-ÜS"           {:reason :invalid-subtag :subtag "ÜS" :index 1}
                     "abcdefghi"       {:reason :invalid-subtag :subtag "abcdefghi" :index 0}
                     "1901"            {:reason :unexpected-subtag :subtag "1901" :index 0}
                     "en-US-Latn"      {:reason :unexpected-subtag :subtag "Latn" :index 2}
                     "abcd-yue"        {:reason :unexpected-subtag :subtag "yue" :index 1}
                     "zh-a-b-c-d"      {:reason :empty-extension :subtag "a" :index 1}
                     "en-abc-abd-abe-abf" {:reason :unexpected-subtag :subtag "abf" :index 4}
                     "en-A"            {:reason :empty-extension :subtag "A" :index 1}
                     "en-a-x-foo"      {:reason :empty-extension :subtag "a" :index 1}
                     "x"               {:reason :empty-private-use :subtag "x" :index 0}
                     "en-X"            {:reason :empty-private-use :subtag "X" :index 1}
                     "de-1901-1901"    {:reason :duplicate-variant :subtag "1901" :index 2}
                     "de-1901-ROZAJ-rozaj" {:reason :duplicate-variant :subtag "rozaj" :index 3}}]
    (testing (pr-str s)
      (is (= error (langtag/error s)))
      (is (nil? (langtag/parse s)))))
  (is (nil? (langtag/parse nil)))
  (testing "the subtags after a singleton or an x"
    (is (= {:language "en" :private-use ["a" "bb" "x"]} (langtag/parse "en-x-a-bb-x")))
    (is (= {:language "en" :extensions {"a" ["bb" "abcd" "us"]}}
           (langtag/parse "en-a-bb-abcd-us")))))

;; RFC 5646, section 2.1.1: a Turkish locale mustn't turn an i into İ
#?(:clj
   (deftest turkish-locale
     (let [default (java.util.Locale/getDefault)]
       (try
         (java.util.Locale/setDefault (java.util.Locale/forLanguageTag "tr-TR"))
         (is (= "en-IN" (langtag/tag "EN-in")))
         (is (= "i-ami" (langtag/tag "I-AMI")))
         (is (= "sa-Limb" (langtag/tag "SA-LIMB")))
         (finally
           (java.util.Locale/setDefault default))))))
