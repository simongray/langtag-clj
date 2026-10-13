(ns ^:no-doc dk.simongray.langtag.impl.matching
  "Language priority lists, from Accept-Language by RFC 9110 or from a
  coll of ranges, and the matching schemes of RFC 4647.

  A priority list is a vector of maps of :range and :weight, in order of
  weight. Every function here compares subtags ignoring case.

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
  "The range and weight of the `element` of an Accept-Language value, or
  nil when it doesn't follow the grammar, e.g. when it's empty."
  [element]
  (when-let [[_ range qvalue] (re-matches element-pattern element)]
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

(defn subtags
  "The subtags of the range or tag `s` in lower case."
  [s]
  (str/split (syntax/lower s) #"-" -1))

;; RFC 4647, section 3.2
(defn basic-range
  "The basic range of the `range`: * when it starts with *, or else the
  range without its wildcards."
  [range]
  (if (str/starts-with? range "*")
    "*"
    (str/join "-" (remove #{"*"} (str/split range #"-")))))

(defn basic-priority-list
  "The priority list of `x` with each range mapped to a basic range."
  [x]
  (mapv #(update % :range basic-range) (priority-list x)))

;; RFC 4647, section 3.3.1
(defn basic-match?
  "Whether the basic `range` matches the `tag`: when it's *, the tag
  itself, or the tag up to a hyphen. Nothing matches a tag that isn't
  text, e.g. nil."
  [range tag]
  (and (string? range)
       (string? tag)
       (let [range (syntax/lower range)
             tag   (syntax/lower tag)]
         (or (= "*" range)
             (= range tag)
             (str/starts-with? tag (str range "-"))))))

;; RFC 4647, section 3.3.2, steps 3 and 4
(defn subtags-match?
  "Whether the subtags `rs` of an extended range match the subtags `ts` of
  a tag, both without their first subtag."
  [rs ts]
  (loop [[r & more-rs :as rs] rs
         [t & more-ts :as ts] ts]
    (cond
      (empty? rs)     true
      (= "*" r)       (recur more-rs ts)
      (empty? ts)     false
      (= r t)         (recur more-rs more-ts)
      (= 1 (count t)) false
      :else           (recur rs more-ts))))

;; RFC 4647, section 3.3.2
(defn extended-match?
  "Whether the extended `range` matches the `tag`: when their first
  subtags match and the tag has each later subtag of the range in order,
  without skipping a singleton. Nothing matches a tag that isn't text."
  [range tag]
  (and (string? range)
       (string? tag)
       (let [[r & rs] (subtags range)
             [t & ts] (subtags tag)]
         (and (or (= "*" r) (= r t))
              (subtags-match? rs ts)))))

(defn specificity
  "The number of subtags of the `range` that aren't *."
  [range]
  (count (remove #{"*"} (subtags range))))

;; RFC 2616, section 14.4, the longest range that matches
(defn acceptable?
  "Whether the `tag` has a weight above 0 in the `priority-list`, or none.
  Its weight is that of the most specific range that `match?` says matches
  it, or the lowest weight when several tie."
  [match? priority-list tag]
  (let [best (->> priority-list
                  (filter #(match? (:range %) tag))
                  (sort-by (juxt #(- (specificity (:range %))) :weight))
                  first)]
    (or (nil? best) (pos? (:weight best)))))

;; RFC 4647, section 3.3
(defn filter-tags
  "The items of `coll` whose tag a range of the `priority-list` matches,
  in order of priority. The `f` returns the tag of an item, and `match?`
  matches it with a range. Items of tags that aren't acceptable are left
  out."
  [match? priority-list f coll]
  (->> (for [{:keys [range weight]} priority-list
             :when (pos? weight)
             x     coll
             :let  [tag (f x)]
             :when (and (match? range tag)
                        (acceptable? match? priority-list tag))]
         x)
       distinct
       vec))

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

;; RFC 4647, section 3.4
(defn lookup
  "The first item of `coll` whose tag equals a range of the `priority-list`
  or one of its fallbacks and is acceptable, or nil. The `f` returns the
  tag of an item. The range * is skipped."
  [priority-list f coll]
  (first (for [{:keys [range weight]} priority-list
               :when (and (pos? weight) (not= "*" range))
               fallback (fallbacks range)
               x        coll
               :let     [tag (f x)]
               :when    (and (string? tag)
                             (= (syntax/lower fallback) (syntax/lower tag))
                             (acceptable? basic-match? priority-list tag))]
           x)))

(comment
  (priority-list "da, en-gb;q=0.8, en;q=0.7")
  (fallbacks "zh-Hant-CN-x-private1-private2")
  (lookup (priority-list "da-DK, en;q=0.8") identity ["da" "en"])
  (filter-tags extended-match? (priority-list "de-*-DE") :lang
               [{:lang "de-Latn-DE"} {:lang "de"} {}])
  #_.)
