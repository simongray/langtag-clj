(ns dk.simongray.langtag.registry
  "Check, canonicalize and match language tags by the IANA Language Subtag
  Registry of file-date.

  The functions take tags as text, as the root namespace's do, and those
  that write a tag also take its map. Validity depends on the registry, so
  a tag with a subtag newer than file-date isn't valid here until a later
  release of the library.

  The matching functions work as the root namespace's, but compare ranges
  and tags in extlang form, so that e.g. iw matches he and zh matches yue.
  They return the tags as given."
  (:require [dk.simongray.langtag.impl.matching :as matching]
            [dk.simongray.langtag.impl.registry :as registry]
            [dk.simongray.langtag.impl.syntax :as syntax]))

(def file-date
  "The File-Date of the registry that the library carries, e.g.
  \"2026-09-17\"."
  (registry/file-date))

;; RFC 5646, section 2.2.9
(defn error
  "The reason that the tag `s` isn't valid, or nil when it is.

      (error \"en-UK\")
      ;; => {:reason :unregistered-subtag :subtag \"UK\" :index 1}

  The reasons are those of langtag/error, and:

  - :unregistered-subtag, for a language, extlang, script, region or
    variant that the registry doesn't have, e.g. the UK of en-UK (it's GB)
  - :unexpected-subtag, also for a second or third extlang, e.g. the gan of
    zh-yue-gan (section 2.2.2)"
  [s]
  (registry/error s))

(defn valid?
  "Whether the tag `s` is well formed and the registry has each of its
  subtags.

  TODO: whether the subtags of the -u- (RFC 6067) and -t- (RFC 6497)
  extensions are valid, which CLDR's data says."
  [s]
  (nil? (error s)))

;; RFC 5646, section 4.5
(defn canonical-form
  "The tag `x` (a map or text) in canonical form, or nil when it isn't well
  formed.

      (canonical-form \"iw-IL\")
      ;; => \"he-IL\"

  It replaces a deprecated tag or subtag with the one that the registry
  prefers, if any, and an extlang with the language it stands for, e.g.
  yue-HK for zh-yue-HK. Then it writes the tag as langtag/tag does."
  [x]
  (some-> (syntax/tag-map x) registry/canonical syntax/join))

;; RFC 5646, section 4.5
(defn extlang-form
  "The tag `x` (a map or text) in extlang form, or nil when it isn't well
  formed: the canonical form, with the Prefix of the language before it
  when the language is also an extlang.

      (extlang-form \"hak-CN\")
      ;; => \"zh-hak-CN\""
  [x]
  (some-> (syntax/tag-map x) registry/canonical registry/extlang syntax/join))

(defn basic-match?
  "Whether the basic language `range` matches the `tag`, as
  langtag/basic-match? has it, in extlang form."
  [range tag]
  (matching/text-match? matching/basic
                        (registry/range-form range)
                        (registry/tag-form tag)))

(defn extended-match?
  "Whether the extended language `range` matches the `tag`, as
  langtag/extended-match? has it, in extlang form."
  [range tag]
  (matching/text-match? matching/extended
                        (registry/range-form range)
                        (registry/tag-form tag)))

(defn basic-filter
  "The `tags` that a range of the `priority-list` matches by basic
  filtering, as langtag/basic-filter has it, in extlang form. With `f`, it
  filters the items of `coll` by the tag that `f` returns for each.

      (basic-filter \"zh, he\" [\"yue-HK\" \"iw\" \"en\"])
      ;; => [\"yue-HK\" \"iw\"]"
  ([priority-list tags]
   (basic-filter priority-list identity tags))
  ([priority-list f coll]
   (matching/filter-tags (matching/in-form matching/basic registry/range-form)
                         priority-list
                         (registry/in-tag-form f)
                         coll)))

(defn extended-filter
  "The `tags` that a range of the `priority-list` matches by extended
  filtering, as langtag/extended-filter has it, in extlang form. With `f`,
  it filters the items of `coll` by the tag that `f` returns for each."
  ([priority-list tags]
   (extended-filter priority-list identity tags))
  ([priority-list f coll]
   (matching/filter-tags (matching/in-form matching/extended registry/range-form)
                         priority-list
                         (registry/in-tag-form f)
                         coll)))

(defn lookup
  "The tag in `tags` that best matches the `priority-list` by lookup, as
  langtag/lookup has it, in extlang form, or nil. With `f`, it looks up an
  item of `coll` by the tag that `f` returns for each.

      (lookup \"iw, en;q=0.8\" [\"en\" \"he\"])
      ;; => \"he\"

  A range falls back to its macrolanguage, too, when it's one of the
  languages that the registry also has as an extlang, e.g. from yue-HK to
  zh-yue and then zh."
  ([priority-list tags]
   (lookup priority-list identity tags))
  ([priority-list f coll]
   (matching/lookup (matching/in-form matching/basic registry/range-form)
                    priority-list
                    (registry/in-tag-form f)
                    coll)))

(comment
  (error "en-UK")
  (valid? "zh-yue-gan")
  (canonical-form "zh-yue-BU-heploc")
  (extlang-form "hak-CN")
  (lookup "yue-HK" ["zh" "en"])
  (extended-filter "*-BU" ["en-MM" "my"])
  #_.)
