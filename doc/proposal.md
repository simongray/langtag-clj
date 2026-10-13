The minimal version of langtag-clj
==================================

This records the design of version 0.1.0, what the full version would add
and who would use it.

Decisions
---------

- **One cljc codebase, with the JDK as a test oracle.** JavaScript has no
  matching, and its `Intl.Locale` canonicalizes by CLDR rather than by
  RFC 5646. On the JVM, wrapping the JDK would bring in its copy of the
  registry, e.g. he in a priority list with iw. So the library has one
  implementation, which the JVM tests compare with the JDK. The docstring
  of test/dk/simongray/langtag/jdk_test.clj lists where the JDK differs.
- **Well formed means the grammar, with no variant or extension twice.**
  Sections 2.2.5 and 2.2.6 forbid both with a MUST that needs no
  registry, and Appendix A calls ar-a-aaa-b-bbb-a-ccc invalid. The rule
  also lets the map key extensions by their singleton. Section 2.2.9 and
  the JDK accept both, though.
- **Canonical case on parsing.** Case carries no meaning, so the map holds
  each subtag in the case of section 2.1.1. Writing a tag also sorts its
  extensions by singleton, as step 1 of section 4.5 does without the
  registry.
- **A grandfathered tag stays whole,** as `{:grandfathered "zh-min-nan"}`,
  since its subtags don't mean what their shape says (section 2.2.8).
- **Singletons as strings,** e.g. `{"u" ["co" "phonebk"]}`, since a digit
  singleton would make the awkward keyword :1.
- **One error per tag:** the first that the parser finds, with the subtag
  as written and its index. Later errors in a broken tag are mostly
  echoes of the first.
- **Accept-Language by RFC 9110's grammar, leniently.** The library leaves
  out an element that breaks the grammar (e.g. en_US or q=0.1234) rather
  than refuse the whole header, much as section 5.6.1.2 has a recipient
  ignore empty elements.
- **A weight of 0 by RFC 2616.** A tag has the weight of the most specific
  range that matches it (section 14.4), so filtering by "en-GB, en;q=0"
  still returns en-GB. The JDK's filtering rules out every tag that a
  range of weight 0 matches, so it returns nothing here.
- **Extended ranges become basic ranges** in basic filtering and lookup
  (option a of RFC 4647, section 3.2), as in the JDK's
  MAP_EXTENDED_RANGES mode. So en-*-US looks up en-US.
- **Lookup returns nil when nothing matches.** Section 3.4.1 leaves the
  default to the protocol, and `(or (lookup ...) "en")` is short enough.
- **Filtering returns every matching item once,** at the first range that
  matches it, as `clojure.core/filter` would. The JDK returns each tag
  once instead, which would merge documents that share a tag.
- **Matching takes an optional key function,** as `sort-by` does, so that
  DanNet can pick a label by its language and get the label back. An item
  whose tag is nil, e.g. a label without a language, matches nothing.

Not yet: the full version
-------------------------

These need the
[IANA Language Subtag Registry](https://www.iana.org/assignments/language-subtag-registry/language-subtag-registry):

- validity (RFC 5646, section 2.2.9)
- the canonical form by Preferred-Value (section 4.5), e.g. he for iw
  and tlh for i-klingon
- the extlang form, e.g. zh-hak-CN for hak-CN
- Suppress-Script, e.g. en for en-Latn
- macrolanguages, e.g. that cmn is a kind of zh
- the details of the -u- (RFC 6067) and -t- (RFC 6497) extensions

The code marks each place where an algorithm stops short of these with a
TODO, e.g. lookup, which doesn't treat iw and he as the same language.
Matching with canonical ranges has a rule of its own: RFC 4647, section
3.2, says to match both the canonical and the original range, or to
canonicalize the tags too.

**How the full version would ship the registry.** As whatwg-url does with
its Unicode tables:

- dev/fetch.sh downloads the registry at a pinned File-Date into
  dev-resources/, which Git ignores.
- A dev script (`clojure -X:registry`) writes
  resources/dk/simongray/langtag/registry.txt, with a line per record and
  only the fields that the algorithms use: type, subtag, Preferred-Value,
  Suppress-Script, Macrolanguage and Prefix. That comes to about a tenth
  of the registry's 732 KB, close to the size of whatwg-url's idna.txt.
- A `table-text` macro like whatwg-url's reads the table as a resource on
  the JVM and inlines it as a string literal in ClojureScript. A delay
  parses it on first use, so the functions that don't need it never load
  it.
- The CI writes the table again and fails on a diff. Updating the
  registry means a new File-Date in fetch.sh and a run of the script.

Who would use it
----------------

**DanNet.** Its `->language-negotiation-ic` in
src/main/dk/cst/dannet/web/resources.clj uses Pedestal's media-type
negotiation, which compares whole tags. So an Accept-Language of only
da-DK, only en-GB or DA matches neither da nor en. Lookup finds da, en
and da. The negotiation branch would become:

```clojure
(if-let [language (some-> (get-in ctx [:request :headers "accept-language"])
                          (langtag/lookup supported-languages))]
  (assoc-in ctx [:request :accept-language] {:field language :type language})
  ctx)
```

This keeps the map that the cookie branch sets, so `request->languages`
in response.clj reads :type as before. The TODO on `select-label` in
web/i18n.cljc (to compare with BCP 47) could become
`(first (langtag/basic-filter languages lang labels))`, which also accepts
a label in en-GB for en. A nil in its languages stands for a label without
one, which no range matches, so `select-label` would keep its own fallback
for those.

**podcast-clj.** Its validator checks a language by its shape
(`language-tag?` in src/dk/simongray/podcast/feed/validate.cljc), so it
accepts en-US-US and en-a. With `langtag/well-formed?` it would refuse
both, and `langtag/error` could give the reason in the diagnostic. Apple's
two or three letters would stay a check of their own, on the :language of
the parsed tag. The entry "Language, email and media-type values aren't
validated" in doc/needs-2026.md looks out of date, since validate.cljc
now checks all three.
