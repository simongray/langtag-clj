The design of langtag-clj
=========================

This records the design of version 0.1.0, of the registry namespace after
it, what isn't done yet and who would use it.

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

The registry
------------

The `registry` namespace adds validity, the canonical form and the extlang
form by the
[IANA Language Subtag Registry](https://www.iana.org/assignments/language-subtag-registry/language-subtag-registry).
[registry-2026.md](registry-2026.md) works out what it needs from the
registry and how small that can be.

- **A namespace of its own.** The root namespace doesn't require it, so a
  ClojureScript build that doesn't either carries none of the table. Its
  defs call no function when they load, as Closure keeps such a def and
  whatever it refers to.
- **A copy of the registry in the repository.** IANA keeps no older copies,
  so the table can only be made again from one that's committed. The CI
  makes it again and fails on a diff.
- **No registry of your own, for now.** A release after each update of the
  registry is enough until someone needs newer subtags sooner.
- **Valid means registered subtags and one extlang at most.** Section 2.2.2
  reserves the places of a second and third extlang. A Prefix is only a
  SHOULD (section 3.1.8), so validity ignores it, as section 2.2.9 does.
- **The canonical form as a fixed point.** It replaces a whole tag again
  after its subtags, since sgn-DD becomes the redundant tag sgn-DE, whose
  Preferred-Value is gsg. And it replaces a language after its extlang,
  since the deprecated extlang ajp is its own Preferred-Value, while the
  language ajp has apc.
- **Matching in extlang form.** RFC 4647, section 3.2, has a matcher that
  canonicalizes ranges canonicalize the tags too. The extlang form does
  that and also keeps the macrolanguage before a language that has one as
  an extlang, so zh matches yue-HK and yue-HK falls back to zh. Section 4.5
  of RFC 5646 suggests it for matching.

Not yet
-------

- Suppress-Script, e.g. en for en-Latn
- macrolanguages beyond the extlang form, e.g. that nb is a kind of no
- the Prefix of a variant, e.g. sl for rozaj, which section 4.1 recommends
  checking

The details of the -u- (RFC 6067) and -t- (RFC 6497) extensions need
CLDR's data instead, so they may come later.

The code marks each place where an algorithm stops short of these with a
TODO.

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

**podcast-clj.** Its validator parses a language with `langtag/parse` and
gives the reason from `langtag/error` (`language-problem` in
src/dk/simongray/podcast/feed/validate.cljc). With `registry/error`
instead, it would also refuse en-UK, a common mistake for en-GB, and
languages that don't exist.
