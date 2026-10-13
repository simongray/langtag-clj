(ns dk.simongray.langtag
  "Parse, write and match language tags by BCP 47: the tags of RFC 5646
  and the matching of RFC 4647.

  A tag is a plain map of the subtags it has, in canonical case:

  - :language, e.g. \"zh\"
  - :extlangs, the extended languages, e.g. [\"yue\"]
  - :script, e.g. \"Hant\"
  - :region, e.g. \"HK\" or \"419\"
  - :variants, e.g. [\"1901\"]
  - :extensions, a map of each singleton to its subtags, e.g.
    {\"u\" [\"co\" \"pinyin\"]}
  - :private-use, the subtags after x, e.g. [\"private\"]

  A grandfathered tag such as i-klingon is a map of :grandfathered alone.

  The functions that match tags take a language priority list: an
  Accept-Language value such as \"da, en;q=0.8\", or a coll of language
  ranges in order of priority such as [\"da\" \"en\"]. They return the tags
  as given. With a function that returns the tag of an item, they match
  items instead, e.g. labels by their language. A nil tag matches
  nothing."
  (:require [dk.simongray.langtag.impl.matching :as matching]
            [dk.simongray.langtag.impl.syntax :as syntax]))

;; RFC 5646, section 2.1
(defn parse
  "The map of the tag `s`, or nil when it isn't well formed.

      (parse \"sr-latn-rs\")
      ;; => {:language \"sr\" :script \"Latn\" :region \"RS\"}

  A well-formed tag follows the grammar and has no variant or singleton
  twice (sections 2.2.5 and 2.2.6)."
  [s]
  (:tag (syntax/read-tag s)))

(defn error
  "The reason that the tag `s` isn't well formed, or nil when it is.

      (error \"de-419-DE\")
      ;; => {:reason :unexpected-subtag :subtag \"DE\" :index 2}

  The :index counts subtags from 0. The reasons are:

  - :empty-subtag, e.g. in en--US
  - :invalid-subtag, for a subtag that isn't one to eight ASCII letters
    and digits, e.g. en_US
  - :unexpected-subtag, for a subtag that the grammar doesn't allow where
    it is, e.g. the second region of de-419-DE
  - :empty-extension, for a singleton with no subtags after it, e.g. the
    a of en-a
  - :empty-private-use, for an x with no subtags after it
  - :duplicate-variant, e.g. the second 1901 of de-1901-1901
  - :duplicate-singleton, e.g. the second a of en-a-bbb-a-ccc"
  [s]
  (:error (syntax/read-tag s)))

(defn well-formed?
  "Whether parse accepts the tag `s`.

  TODO: valid tags (RFC 5646, section 2.2.9), whose subtags must be in the
  registry."
  [s]
  (some? (parse s)))

;; RFC 5646, section 2.1.1
(defn tag
  "The tag `x` (a map or text) as text in canonical case, or nil when it
  isn't well formed.

      (tag \"EN-gb\")
      ;; => \"en-GB\"

      (tag {:language \"sr\" :script \"latn\" :region \"rs\"})
      ;; => \"sr-Latn-RS\"

  Extensions come in the order of their singletons, as in the canonical
  form of section 4.5.

  TODO: the rest of the canonical form, e.g. he for iw, and the extlang
  form, e.g. zh-hak-CN for hak-CN, which need the registry."
  [x]
  (some-> (if (map? x) (syntax/canonical x) (parse x))
          syntax/join))

(defn priority-list
  "The language priority list of `x` (RFC 4647, section 2.3): a vector of
  maps of :range and :weight, in order of weight.

  The `x` is an Accept-Language value (RFC 9110, section 12.5.4) or a coll
  of ranges. A range in a coll has weight 1 unless it's a map with a
  :weight. Ranges that aren't well formed or that repeat an earlier one
  are left out.

      (priority-list \"da, en-gb;q=0.8, en;q=0.7\")
      ;; => [{:range \"da\" :weight 1.0}
      ;;     {:range \"en-gb\" :weight 0.8}
      ;;     {:range \"en\" :weight 0.7}]"
  [x]
  (matching/priority-list x))

(defn basic-match?
  "Whether the basic language `range` matches the `tag` (RFC 4647, section
  3.3.1): when it's *, the tag itself, or the start of the tag up to a
  hyphen."
  [range tag]
  (matching/basic-match? range tag))

(defn extended-match?
  "Whether the extended language `range` matches the `tag` (RFC 4647,
  section 3.3.2), e.g. de-*-DE or de-DE matches de-Latn-DE."
  [range tag]
  (matching/extended-match? range tag))

(defn basic-filter
  "The `tags` that a range of the `priority-list` matches by basic
  filtering (RFC 4647, section 3.3.1), in order of priority. With `f`, it
  filters the items of `coll` by the tag that `f` returns for each.

      (basic-filter \"de-de\" [\"de-DE-1996\" \"de-Deva\" \"de-Latn-DE\"])
      ;; => [\"de-DE-1996\"]

      (basic-filter \"en\" :lang [{:text \"hund\" :lang \"da\"}
                                {:text \"dog\" :lang \"en-GB\"}])
      ;; => [{:text \"dog\" :lang \"en-GB\"}]

  A range with * in it becomes a basic range first (section 3.2). Each tag
  has the weight of the most specific range that matches it (as in RFC
  2616) and is left out when that weight is 0. So for \"en, en-GB;q=0\" it
  returns every English tag but en-GB."
  ([priority-list tags]
   (basic-filter priority-list identity tags))
  ([priority-list f coll]
   (matching/filter-tags matching/basic-match?
                         (matching/basic-priority-list priority-list)
                         f
                         coll)))

(defn extended-filter
  "The `tags` that a range of the `priority-list` matches by extended
  filtering (RFC 4647, section 3.3.2), in order of priority. With `f`, it
  filters the items of `coll` by the tag that `f` returns for each.

      (extended-filter \"de-*-DE\" [\"de-DE\" \"de-Latn-DE\" \"de-x-DE\"])
      ;; => [\"de-DE\" \"de-Latn-DE\"]

  As in basic-filter, a tag of weight 0 is left out."
  ([priority-list tags]
   (extended-filter priority-list identity tags))
  ([priority-list f coll]
   (matching/filter-tags matching/extended-match?
                         (matching/priority-list priority-list)
                         f
                         coll)))

(defn lookup
  "The tag in `tags` that best matches the `priority-list` by lookup (RFC
  4647, section 3.4), or nil. With `f`, it looks up an item of `coll` by
  the tag that `f` returns for each.

      (lookup \"da-DK, en;q=0.8\" [\"da\" \"en\"])
      ;; => \"da\"

  It tries each range in turn and falls back to shorter ranges before the
  next one, e.g. from zh-Hant-CN to zh-Hant and then zh. It skips the
  range * and maps a range with * in it to a basic range (section 3.2).
  As in basic-filter, a tag of weight 0 is left out. When no tag matches,
  the default is up to you (section 3.4.1), e.g. (or (lookup ...) \"en\").

  TODO: equivalent tags, e.g. he for the deprecated iw, which need the
  registry."
  ([priority-list tags]
   (lookup priority-list identity tags))
  ([priority-list f coll]
   (matching/lookup (matching/basic-priority-list priority-list) f coll)))

(comment
  (parse "zh-yue-Hant-HK-u-co-pinyin-x-private")
  (error "ar-a-aaa-b-bbb-a-ccc")
  (tag "MN-cYRL-mn")
  (priority-list "da, en-gb;q=0.8, en;q=0.7")
  (lookup "zh-Hant-CN-x-private1-private2" ["zh-Hant" "zh"])
  (extended-filter "*-CH" ["de-CH" "fr-CH" "it-IT"])
  #_.)
