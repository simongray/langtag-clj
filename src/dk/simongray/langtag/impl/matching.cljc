(ns ^:no-doc dk.simongray.langtag.impl.matching
  "Language priority lists, from Accept-Language by RFC 9110 or from a
  coll of ranges, and the matching schemes of RFC 4647.

  A priority list is a vector of maps of :range and :weight, in order of
  weight. The functions that match take ranges and tags in lower case,
  which the callers make once rather than for each comparison.

  TODO: these, which need the registry:

  - equivalent tags, e.g. he for the deprecated iw
  - Suppress-Script, e.g. en for en-Latn
  - macrolanguages, e.g. zh for cmn"
  (:require [clojure.string :as str]
            [dk.simongray.langtag.impl.syntax :as syntax]))

;; RFC 4647, section 2.2, whose extended ranges include the basic ranges
;; of section 2.1
(def range-pattern
  "A basic or extended language range: subtags of one to eight letters and
  digits, or *, with only letters in the first."
  #"^(?:[A-Za-z]{1,8}|\*)(?:-(?:[A-Za-z0-9]{1,8}|\*))*$")

;; RFC 9110, section 12.5.4, with the weight of section 12.4.2 and the OWS
;; of section 5.6.3
(def element-pattern
  "An element of Accept-Language, with its range and qvalue as groups."
  #"^[ \t]*([^ \t;]+)(?:[ \t]*;[ \t]*[qQ]=(0(?:\.\d{0,3})?|1(?:\.0{0,3})?))?[ \t]*$")

(defn element
  "The range and weight of the element `s` of an Accept-Language value, or
  nil when it doesn't follow the grammar, e.g. when it's empty."
  [s]
  (when-let [[_ range qvalue] (re-matches element-pattern s)]
    (when (re-matches range-pattern range)
      {:range range :weight (if qvalue (parse-double qvalue) 1.0)})))

(defn entry
  "The range and weight of `x` (a range, or a map of :range and :weight),
  or nil when the range isn't well formed."
  [x]
  (let [{:keys [range weight] :or {weight 1.0}} (if (map? x) x {:range x})]
    (when (and (string? range) (re-matches range-pattern range))
      {:range range :weight weight})))

(defn distinct-ranges
  "The `entries` without those whose range repeats an earlier one."
  [entries]
  (first (reduce (fn [[kept seen] {:keys [range] :as entry}]
                   (let [k (syntax/lower range)]
                     (if (seen k)
                       [kept seen]
                       [(conj kept entry) (conj seen k)])))
                 [[] #{}]
                 entries)))

(defn priority-list
  "The language priority list of `x` (an Accept-Language value or a coll of
  ranges), without the ranges that aren't well formed or that repeat an
  earlier one."
  [x]
  (->> (if (string? x)
         (keep element (str/split x #","))
         (keep entry x))
       distinct-ranges
       (sort-by :weight >)
       vec))

(defn lower-text
  "The `x` in lower case when it's text, or else nil."
  [x]
  (when (string? x)
    (syntax/lower x)))

(defn lower-priority-list
  "The priority list of `x` with each range in lower case."
  [x]
  (mapv #(update % :range syntax/lower) (priority-list x)))

;; RFC 4647, section 3.2; a range without * is returned as it is, since
;; splitting and joining it made lookup a quarter slower
(defn basic-range
  "The basic range of the `range`: * when it starts with *, or else the
  range without its wildcards."
  [range]
  (cond
    (str/starts-with? range "*") "*"
    (str/includes? range "*")    (str/join "-" (remove #{"*"} (str/split range #"-")))
    :else                        range))

(defn basic-priority-list
  "The priority list of `x` with each range in lower case and mapped to a
  basic range."
  [x]
  (mapv #(update % :range basic-range) (lower-priority-list x)))

;; a split on a string rather than a regex, which the JDK does without
;; regex machinery, as that was most of the time of extended filtering
(defn subtags
  "The subtags of the range or tag `s`, as an array."
  [s]
  #?(:clj  (.split ^String s "-" -1)
     :cljs (.split s "-")))

;; RFC 4647, section 3.3.1
(defn basic-match?
  "Whether the basic `range` matches the `tag`, both in lower case: when
  it's *, the tag itself, or the tag up to a hyphen."
  [range tag]
  (or (= "*" range)
      (= range tag)
      (and (str/starts-with? tag range)
           (= \- (nth tag (count range))))))

;; RFC 4647, section 3.3.2, steps 3 and 4, by index rather than by seq,
;; which made extended filtering three times slower
(defn subtags-match?
  "Whether the array of subtags `rs` of an extended range matches the
  array of subtags `ts` of a tag, from the second subtag of each."
  [^objects rs ^objects ts]
  (loop [i 1
         j 1]
    (cond
      (== i (alength rs))         true
      (= "*" (aget rs i))         (recur (inc i) j)
      (== j (alength ts))         false
      (= (aget rs i) (aget ts j)) (recur (inc i) (inc j))
      (== 1 (count (aget ts j)))  false
      :else                       (recur i (inc j)))))

;; RFC 4647, section 3.3.2
(defn extended-match?
  "Whether an extended range matches a tag, by their arrays of lower-case
  subtags `rs` and `ts`: when their first subtags match and the tag has
  each later subtag of the range in order, without skipping a singleton."
  [^objects rs ^objects ts]
  (let [r (aget rs 0)]
    (and (or (= "*" r) (= r (aget ts 0)))
         (subtags-match? rs ts))))

;; the tags of a filter split once rather than for each range, which made
;; extended filtering twice as slow in Node
(def basic
  "How basic filtering matches: a basic range with a tag, both in lower
  case."
  {:ranges  basic-priority-list
   :prepare identity
   :match?  basic-match?})

(def extended
  "How extended filtering matches: the subtags of a range with those of a
  tag, both in lower case."
  {:ranges  lower-priority-list
   :prepare subtags
   :match?  extended-match?})

(defn text-match?
  "Whether the `range` matches the `tag` by the `scheme`, ignoring case.
  Nothing matches a range or tag that isn't text, e.g. nil."
  [{:keys [prepare match?] :as scheme} range tag]
  (let [range (lower-text range)
        tag   (lower-text tag)]
    (boolean (and range tag (match? (prepare range) (prepare tag))))))

(defn specificity
  "The number of subtags of the `range` that aren't *."
  [range]
  (count (remove #{"*"} (subtags range))))

(defn prepare-ranges
  "The lower-case `priority-list` with each range also as `prepare` returns
  it, under :prepared."
  [prepare priority-list]
  (mapv #(assoc % :prepared (prepare (:range %))) priority-list))

;; RFC 2616, section 14.4, the longest range that matches
(defn acceptable?
  "Whether the `tag` has a weight above 0 in the prepared `priority-list`,
  or none. Its weight is that of the most specific range that `match?`
  says matches it, or the lowest weight when several tie."
  [match? priority-list tag]
  ;; only a range of weight 0 can rule a tag out
  (or (not-any? (comp zero? :weight) priority-list)
      (let [best (->> priority-list
                      (filter #(match? (:prepared %) tag))
                      (sort-by (juxt #(- (specificity (:range %))) :weight))
                      first)]
        (or (nil? best) (pos? (:weight best))))))

(defn priority
  "The index of the first range of weight above 0 in the prepared
  `priority-list` that `match?` says matches the `tag`, or nil."
  [match? priority-list tag]
  (reduce-kv (fn [_ i {:keys [prepared weight]}]
               (when (and (pos? weight) (match? prepared tag))
                 (reduced i)))
             nil
             priority-list))

(defn ranked
  "The item `x` with the priority of its tag, which `f` returns, or nil
  when no range of the prepared `priority-list` matches the tag by the
  `scheme` or the tag isn't acceptable."
  [{:keys [prepare match?] :as scheme} priority-list f x]
  (let [tag (some-> (lower-text (f x)) prepare)
        i   (some->> tag (priority match? priority-list))]
    (when (and i (acceptable? match? priority-list tag))
      [i x])))

;; RFC 4647, section 3.3
(defn filter-tags
  "The items of `coll` whose tag a range of the priority list of `x` matches
  by the `scheme`, in order of priority. The `f` returns the tag of an
  item."
  [{:keys [ranges prepare] :as scheme} x f coll]
  (let [priority-list (prepare-ranges prepare (ranges x))]
    (->> coll
         (keep #(ranked scheme priority-list f %))
         (sort-by first)
         (mapv second))))

;; RFC 4647, section 3.4
(defn truncate
  "The `subtags` of a range without the last one, and without a single
  letter or digit before it, which goes with it."
  [subtags]
  (let [subtags (pop subtags)]
    (cond-> subtags
      (= 1 (count (peek subtags))) pop)))

;; RFC 4647, section 3.4
(defn fallbacks
  "The `range` followed by each range that lookup falls back to, e.g. zh-Hant
  and zh after zh-Hant-CN."
  [range]
  (->> (str/split range #"-")
       (iterate truncate)
       (take-while seq)
       (map #(str/join "-" %))))

(defn first-by-tag
  "A map of the lower-case tag that `f` returns for each item of `coll` to
  the first item with it."
  [f coll]
  (reduce (fn [m x]
            (let [tag (lower-text (f x))]
              (cond-> m
                (and tag (not (contains? m tag))) (assoc tag x))))
          {}
          coll))

;; RFC 4647, section 3.4
(defn lookup
  "The first item of `coll` whose tag equals a basic range of the priority
  list of `x` or one of its fallbacks and is acceptable, or nil. The `f`
  returns the tag of an item. The range * is skipped."
  [x f coll]
  (let [priority-list (prepare-ranges identity (basic-priority-list x))
        items         (first-by-tag f coll)]
    (first (for [{:keys [range weight]} priority-list
                 :when (and (pos? weight) (not= "*" range))
                 fallback (fallbacks range)
                 :when (and (contains? items fallback)
                            (acceptable? basic-match? priority-list fallback))]
             (items fallback)))))

(comment
  (priority-list "da, en-gb;q=0.8, en;q=0.7")
  (fallbacks "zh-Hant-CN-x-private1-private2")
  (lookup "da-DK, en;q=0.8" identity ["da" "en"])
  (filter-tags extended "de-*-DE" :lang [{:lang "de-Latn-DE"} {:lang "de"} {}])
  #_.)
