langtag-clj
===========

[![Clojars Project](https://img.shields.io/clojars/v/dk.simongray/langtag-clj.svg)](https://clojars.org/dk.simongray/langtag-clj)

This library implements [BCP 47](https://www.rfc-editor.org/info/bcp47) in
Clojure and ClojureScript: the language tags of
[RFC 5646](https://www.rfc-editor.org/rfc/rfc5646) and their matching by
[RFC 4647](https://www.rfc-editor.org/rfc/rfc4647). Use it to pick the
language of a response from an Accept-Language header, or to check the
language of a document.

- **Matching that falls back.** A browser that asks for da-DK gets your
  Danish page, though you only have da. Comparing whole tags misses that.
- **Tags as maps.** A tag parses into a map of its language, script,
  region and other subtags. The map writes back as a tag in canonical
  case.
- **Errors as data.** You can ask why a tag isn't well formed, or why it
  isn't valid, e.g. that en-UK should be en-GB.
- **The registry when you need it.** Validity and the canonical form come
  from the IANA registry, in a namespace of its own. A ClojureScript build
  only carries the registry when it uses that namespace.
- **The same results everywhere.** One codebase runs on the JVM, in Node
  and in browsers, since JavaScript has no matching of its own. On the
  JVM, the tests compare it with the JDK's.

> This library was developed with assistance from frontier LLMs.

Getting started
---------------

The library needs Clojure 1.11+ and Java 11+. Add the latest release from
[Clojars](https://clojars.org/dk.simongray/langtag-clj) to the `:deps` of
your `deps.edn`:

```clojure
dk.simongray/langtag-clj {:mvn/version "0.2.0"}
```

For changes that aren't released yet, use the SHA of the latest commit on
`master` instead. In shadow-cljs a Git dependency only works through
`deps.edn`, so set `:deps true` in your `shadow-cljs.edn` too.

Language tags
-------------

Parse a tag with `langtag/parse`:

```clojure
(require '[dk.simongray.langtag :as langtag])

(langtag/parse "zh-yue-Hant-HK-u-co-pinyin-x-private")
;; => {:language "zh" :extlangs ["yue"] :script "Hant" :region "HK"
;;     :extensions {"u" ["co" "pinyin"]} :private-use ["private"]}
```

The map only has the subtags that the tag has, in canonical case. A
grandfathered tag such as i-klingon parses as
`{:grandfathered "i-klingon"}`. To write a map back as text, or to put a
tag in canonical case, use `langtag/tag`:

```clojure
(langtag/tag "EN-gb")
;; => "en-GB"

(langtag/tag {:language "sr" :script "latn" :region "rs"})
;; => "sr-Latn-RS"
```

For a tag that isn't well formed, both functions return nil. To find out
why, use `langtag/error`:

```clojure
(langtag/error "de-419-DE")
;; => {:reason :unexpected-subtag :subtag "DE" :index 2}
```

To check a tag, use `langtag/well-formed?`. Besides the grammar, it checks
the two rules of RFC 5646 that need no registry: no variant or extension
may appear twice. To check its subtags as well, see
[the registry](#the-registry).

Matching
--------

[Lookup](https://www.rfc-editor.org/rfc/rfc4647#section-3.4) picks the one
tag of yours that best matches the user's preferences, e.g. for the
language of a page:

```clojure
(langtag/lookup "da-DK, en;q=0.8" ["da" "en"])
;; => "da"

(langtag/lookup "fr-CA" ["da" "en"])
;; => nil
```

The first argument is a language priority list: an Accept-Language value,
or a coll of ranges in order of priority such as `["da" "en"]`. Lookup
falls back from da-DK to da before it tries the next range. When nothing
matches, the default is up to you, e.g. `(or (langtag/lookup ...) "en")`.

Filtering returns every tag that matches, in order of priority.
[Basic filtering](https://www.rfc-editor.org/rfc/rfc4647#section-3.3.1)
matches the start of a tag, while
[extended filtering](https://www.rfc-editor.org/rfc/rfc4647#section-3.3.2)
also skips subtags in between, such as a script. Its ranges can start
with `*` for any language, e.g. `*-CH`.

```clojure
(langtag/basic-filter "de-DE" ["de-DE-1996" "de-Deva" "de-Latn-DE"])
;; => ["de-DE-1996"]

(langtag/extended-filter "de-DE" ["de-DE-1996" "de-Deva" "de-Latn-DE"])
;; => ["de-DE-1996" "de-Latn-DE"]
```

The matching functions return the tags as you gave them. To match other
things than tags, e.g. the labels of a word, pass a function that returns
the tag of each (or nil when it has none):

```clojure
(langtag/lookup "da-DK, en;q=0.8" :lang [{:text "dog" :lang "en"}
                                         {:text "hund" :lang "da"}])
;; => {:text "hund" :lang "da"}
```

To see the priority list itself, use `langtag/priority-list`:

```clojure
(langtag/priority-list "da, en-gb;q=0.8, en;q=0.7")
;; => [{:range "da" :weight 1.0} {:range "en-gb" :weight 0.8}
;;     {:range "en" :weight 0.7}]
```

A weight of 0 rules a language out. A tag has the weight of the most
specific range that matches it, as in HTTP/1.1
([RFC 2616](https://www.rfc-editor.org/rfc/rfc2616#section-14.4)), so
`"en, en-GB;q=0"` accepts all English but British English.

The registry
------------

The root namespace works from the syntax of a tag alone. The `registry`
namespace adds what needs the
[IANA Language Subtag Registry](https://www.iana.org/assignments/language-subtag-registry/language-subtag-registry):
whether a tag is valid, i.e. whether the registry has each of its
subtags, and the canonical form of a tag.

```clojure
(require '[dk.simongray.langtag.registry :as registry])

(registry/error "en-UK")
;; => {:reason :unregistered-subtag :subtag "UK" :index 1}

(registry/canonical-form "iw-IL")
;; => "he-IL"

(registry/extlang-form "yue-HK")
;; => "zh-yue-HK"
```

The canonical form replaces deprecated tags and subtags with the ones that
the registry prefers. The extlang form also puts a language such as
Cantonese (yue) after its macrolanguage (zh), as the registry allows for a
few of them.

The namespace has its own `lookup`, `basic-filter` and `extended-filter`,
which compare ranges and tags in extlang form. So a browser that asks for
iw gets your he page, and one that asks for yue-HK falls back to zh:

```clojure
(registry/lookup "yue-HK, en;q=0.8" ["en" "zh"])
;; => "zh"
```

The library carries the registry of `registry/file-date`, and each update
of the registry means a new release.

Not yet
-------

The registry has more than the library uses yet:

- scripts that go without saying (Suppress-Script), e.g. en rather than
  en-Latn
- macrolanguages beyond the extlang form, e.g. that nb is a kind of no
- the tag that a variant should follow (its Prefix), e.g. sl for rozaj

The parser keeps the subtags of the -u- (RFC 6067) and -t- (RFC 6497)
extensions as plain subtags. Their keys come from CLDR rather than the
registry, so reading them may come later.

Development
-----------

```bash
clojure -X:test                                       # the tests on the JVM
npm install                                           # once, for the Node tests
clojure -M:cljs compile test                          # the tests in Node
clojure -M:bench -m dk.simongray.langtag.bench        # benchmarks on the JVM
clojure -M:cljs release bench && node target/bench.js # benchmarks in Node
dev/fetch.sh && clojure -X:tables                     # a newer registry
```

IANA keeps no older copies of the registry, so the repository keeps the one
in dev-resources that the table in resources is made from. After an
update, commit both.

Besides the examples of RFC 5646 and RFC 4647, the tests compare random
tags and priority lists with the JDK, and the registry namespace with each
record of the registry. The docstring of
[jdk_test.clj](test/dk/simongray/langtag/jdk_test.clj) lists where the JDK
differs.

The public namespaces are the root and `registry`. The internal ones are
under `impl`:

- `impl.syntax`, which parses and writes tags
- `impl.matching`, which reads priority lists and matches tags
- `impl.registry`, which reads the registry's table and puts tags in
  canonical form

License
-------

The langtag-clj project is licensed under the [MIT licence](LICENSE).
