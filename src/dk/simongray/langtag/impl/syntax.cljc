(ns ^:no-doc dk.simongray.langtag.impl.syntax
  "Parse and write language tags by RFC 5646, section 2.1.

  TODO: the subtags of the -u- extension (RFC 6067) and the -t- extension
  (RFC 6497), which the parser keeps as plain subtags."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]))

;; RFC 5646, section 2.1.1: the root locale, so that a Turkish locale can't
;; turn i into İ
(defn lower
  "The text `s` in lower case."
  [s]
  #?(:clj  (.toLowerCase ^String s java.util.Locale/ROOT)
     :cljs (.toLowerCase s)))

(defn upper
  "The text `s` in upper case."
  [s]
  #?(:clj  (.toUpperCase ^String s java.util.Locale/ROOT)
     :cljs (.toUpperCase s)))

(defn title
  "The text `s` with its first letter in upper case and the rest in lower
  case."
  [s]
  (str (upper (subs s 0 1)) (lower (subs s 1))))

;; RFC 5646, section 2.2.8, the irregular and regular productions of the
;; grammar in section 2.1
(def grandfathered
  "Each grandfathered tag in lower case, to the tag as the registry writes
  it.

  TODO: their Preferred-Value in the registry, e.g. tlh for i-klingon."
  (into {}
        (map (juxt lower identity))
        ["en-GB-oed" "i-ami" "i-bnn" "i-default" "i-enochian" "i-hak"
         "i-klingon" "i-lux" "i-mingo" "i-navajo" "i-pwn" "i-tao" "i-tay"
         "i-tsu" "sgn-BE-FR" "sgn-BE-NL" "sgn-CH-DE"
         "art-lojban" "cel-gaulish" "no-bok" "no-nyn" "zh-guoyu" "zh-hakka"
         "zh-min" "zh-min-nan" "zh-xiang"]))

;; RFC 5646, section 2.1, the order of the langtag production
(def rank
  "Each kind of subtag to its place in a tag."
  {:language 0 :extlang 1 :script 2 :region 3 :variant 4 :extension 5
   :private-use 6})

;; RFC 5646, section 2.1
(defn kind
  "The kind of the lower-case `subtag` after the primary language, by its
  length and content, or nil.

  A subtag after a singleton belongs to its extension or private use
  sequence instead (see in-sequence?)."
  [subtag]
  (condp re-matches subtag
    #"^[a-z]{3}$"                           :extlang
    #"^[a-z]{4}$"                           :script
    #"^(?:[a-z]{2}|[0-9]{3})$"              :region
    #"^(?:[a-z0-9]{5,8}|[0-9][a-z0-9]{3})$" :variant
    #"^[0-9a-wyz]$"                         :extension
    #"^x$"                                  :private-use
    nil))

(defn failure
  "The parse that failed for the `reason` at the subtag at `index`."
  [reason index]
  {:error {:reason reason :index index}})

(defn malformed
  "The failure of the `subtag` at `index` when it isn't one to eight ASCII
  letters and digits, or nil."
  [subtag index]
  (cond
    (= "" subtag)
    (failure :empty-subtag index)

    (not (re-matches #"^[A-Za-z0-9]{1,8}$" subtag))
    (failure :invalid-subtag index)))

;; RFC 5646, section 2.1, the primary language subtag or the x of a
;; privateuse tag
(defn start
  "The parse of a tag that starts with the lower-case `subtag`, or nil
  when no tag can."
  [subtag]
  (cond
    (re-matches #"^[a-z]{2,8}$" subtag) {:tag {:language subtag}
                                         :at  :language}
    (= "x" subtag)                      {:tag {:private-use []}
                                         :at  :private-use}))

;; RFC 5646, sections 2.2.6 and 2.2.7
(defn in-sequence?
  "Whether the lower-case `subtag` belongs to the extension or private use
  sequence that the `parse` is in."
  [{:keys [at]} subtag]
  (or (= :private-use at)
      (and (= :extension at) (re-matches #"^[a-z0-9]{2,8}$" subtag))))

(defn add-to-sequence
  "The `parse` with the `subtag` added to its extension or private use
  sequence."
  [{:keys [at singleton] :as parse} subtag]
  (if (= :private-use at)
    (update-in parse [:tag :private-use] conj subtag)
    (update-in parse [:tag :extensions singleton] conj subtag)))

(defn empty-extension?
  "Whether the `parse` ends with a singleton that has no subtags yet."
  [{:keys [at singleton tag]}]
  (and (= :extension at) (empty? (get-in tag [:extensions singleton]))))

(defn empty-private-use?
  "Whether the `parse` ends with an x that has no subtags after it."
  [{:keys [at tag]}]
  (and (= :private-use at) (empty? (:private-use tag))))

;; RFC 5646, section 2.1, and section 2.2.2 for the extended languages
(defn follows?
  "Whether a subtag of kind `k` can come next after the `parse`:

  - later in the grammar's order
  - as one more variant or extension
  - as one of up to three extlangs after a language of two or three
    letters"
  [{:keys [tag at]} k]
  (case k
    :extlang              (and (#{:language :extlang} at)
                               (<= (count (:language tag)) 3)
                               (< (count (:extlangs tag)) 3))
    (:variant :extension) (<= (rank at) (rank k))
    (< (rank at) (rank k))))

;; RFC 5646, section 2.2.5, rule 5, and section 2.2.6, rule 3
(defn duplicate
  "The reason that a `subtag` of kind `k` can't follow the `parse` because
  it repeats a variant or singleton, or nil.

  TODO: the other rules of validity (section 2.2.9), which need the
  registry."
  [{:keys [tag]} k subtag]
  (case k
    :variant   (when (some #{subtag} (:variants tag)) :duplicate-variant)
    :extension (when (contains? (:extensions tag) subtag) :duplicate-singleton)
    nil))

;; RFC 5646, section 2.1.1, the case of the registry
(defn add
  "The `parse` with the `subtag` of kind `k` added in canonical case."
  [parse k subtag]
  (-> (case k
        :extlang     (update-in parse [:tag :extlangs] (fnil conj []) subtag)
        :script      (assoc-in parse [:tag :script] (title subtag))
        :region      (assoc-in parse [:tag :region] (upper subtag))
        :variant     (update-in parse [:tag :variants] (fnil conj []) subtag)
        :extension   (-> parse
                         (assoc-in [:tag :extensions subtag] [])
                         (assoc :singleton subtag))
        :private-use (assoc-in parse [:tag :private-use] []))
      (assoc :at k)))

(defn next-parse
  "The `parse` after the lower-case `subtag` at `index`, or the failure
  when the subtag can't go there."
  [parse index subtag]
  (let [k (kind subtag)]
    (cond
      (nil? parse)                     (or (start subtag)
                                           (failure :unexpected-subtag index))
      (in-sequence? parse subtag)      (add-to-sequence parse subtag)
      (empty-extension? parse)         (failure :empty-extension (dec index))
      (not (and k (follows? parse k))) (failure :unexpected-subtag index)
      (duplicate parse k subtag)       (failure (duplicate parse k subtag) index)
      :else                            (add parse k subtag))))

(defn step
  "The `parse` after the `subtag` at `index`, or the failure, reduced, when
  the subtag is malformed or can't go there."
  [parse index subtag]
  (let [parse (or (malformed subtag index)
                  (next-parse parse index (lower subtag)))]
    (cond-> parse
      (:error parse) reduced)))

(defn finish
  "The `parse` of a whole tag whose last subtag is at `index`, or the
  failure when it ends with a singleton or an x."
  [parse index]
  (cond
    (:error parse)             parse
    (empty-extension? parse)   (failure :empty-extension index)
    (empty-private-use? parse) (failure :empty-private-use index)
    :else                      {:tag (:tag parse)}))

(defn read-tag
  "The tag `s` as {:tag m} with the map of its subtags, or as {:error e}
  with the reason it isn't well formed. The error names the subtag as
  written in `s`."
  [s]
  (let [s (str s)]
    (if-let [tag (grandfathered (lower s))]
      {:tag {:grandfathered tag}}
      (let [subtags (str/split s #"-" -1)
            parse   (-> (reduce-kv step nil subtags)
                        (finish (dec (count subtags))))]
        (if-let [index (get-in parse [:error :index])]
          (assoc-in parse [:error :subtag] (subtags index))
          parse)))))

(defn- extension-subtags
  "The singleton and subtags of each extension in the map `extensions`, in
  the order of the singletons (RFC 5646, section 4.5, step 1)."
  [extensions]
  (mapcat (fn [[singleton subtags]] (cons singleton subtags))
          (sort-by key extensions)))

(defn join
  "The text of the tag in the map `m`, which need not be well formed."
  [{:keys [grandfathered language extlangs script region variants extensions
           private-use]}]
  (if grandfathered
    grandfathered
    (->> (concat [language] extlangs [script region] variants
                 (extension-subtags extensions)
                 (when private-use (cons "x" private-use)))
         (remove nil?)
         (str/join "-"))))

(defn lowered
  "The map `m` of a tag without its empty parts and with its subtags in
  lower case, to compare it with another."
  [m]
  (->> (remove (comp #(and (seqable? %) (empty? %)) val) m)
       (into {})
       (walk/postwalk #(if (string? %) (lower %) %))))

(defn canonical
  "The map `m` of a tag with its subtags in canonical case, or nil when it
  isn't the map of a well-formed tag, e.g. a region without a language."
  [m]
  (let [parsed (:tag (read-tag (join m)))]
    (when (= (lowered m) (lowered parsed))
      parsed)))

(comment
  (read-tag "zh-yue-Hant-HK-u-co-pinyin-x-private")
  (read-tag "de-419-DE")
  (read-tag "ar-a-aaa-b-bbb-a-ccc")
  (join (:tag (read-tag "EN-b-xx-a-yy")))
  #_.)
