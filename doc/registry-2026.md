The language registry, October 2026
====================================

The full version of langtag-clj needs the
[IANA Language Subtag Registry](https://www.iana.org/assignments/language-subtag-registry/language-subtag-registry).
This page says what it needs from it, how small that can be and how a
ClojureScript build can leave it out when nothing uses it. The registry is
that of 17 September 2026, and the builds are releases of shadow-cljs 3.5.3
with advanced compilation.

What the features need
----------------------

| Feature | Field of the registry | Records |
|---|---|---|
| Validity (RFC 5646, section 2.2.9) | the subtags of each type | 9,203 |
| The canonical form (section 4.5) | Preferred-Value (section 3.1.7) | 423 |
| The extlang form (section 4.5) | the Prefix of an extlang (section 3.1.8) | 258 |
| Scripts that go without saying | Suppress-Script (section 3.1.9) | 134 |
| Macrolanguages | Macrolanguage (section 3.1.10) | 545 |
| The prefixes of variants, which section 4.1 recommends | the Prefix of a variant | 132 |

None of them read the descriptions, comments or dates, which are more than
half of the registry's 731 KB. The -u- and -t- extensions take their keys
from CLDR's BCP 47 data rather than from this registry, so they're a
question of their own.

Size
----

Most of the data is the 8,085 language subtags of three letters. They fill
half of the 17,576 combinations of three letters, so a bit for each
combination (2.9 KB in base64) is much smaller than a list of them. Go's
x/text/language packs its tables in the same spirit, as sorted strings of
fixed width that it searches by halves
([gen.go](https://go.googlesource.com/text/+/v0.2.0/language/gen.go)).

| Data | As lists | With the languages as bits |
|---|---|---|
| Validity | 37 KB, 21 KB gzipped | 6 KB, 4 KB gzipped |
| The mappings of the canonical form, the extlang form, Suppress-Script and macrolanguages | 11 KB, 4 KB gzipped | the same |
| Both | 48 KB, 25 KB gzipped | 17 KB, 8 KB gzipped |

For comparison, the registry is 731 KB (99 KB gzipped), and npm's
language-tags, which ships it as JSON, is listed at 1,049 KB minified and
156 KB gzipped ([DepScope](https://mcp.depscope.dev/pkg/npm/language-tags)).

Leaving it out of a build
-------------------------

Advanced compilation drops a table that nothing asks for, as long as
nothing reads it when its namespace loads. These are builds with a test
table of 60 KB:

| The table's namespace | Build | Gzipped |
|---|---|---|
| not required | 90 KB | 19 KB |
| required, with the table parsed in a delay that nothing derefs | 91 KB | 19 KB |
| required, with the table parsed in a function that nothing calls | 91 KB | 19 KB |
| required, with the table parsed when the namespace loads | 224 KB | 93 KB |
| required and used, with the table parsed by clojure.string/split | 224 KB | 94 KB |
| required and used, with the table parsed by one call of .split | 155 KB | 57 KB |

Two rules follow:

- **Parse a table in a delay or a function,** never in a def that runs when
  the namespace loads. Then a build that never asks for the table doesn't
  carry it.
- **Read the literal of a table in one call.** Closure inlined
  clojure.string/split, and each of its two branches got a copy of the
  string.

A table for each feature, each in a delay of its own, would let a project
that only checks validity leave out the mappings too. That follows from the
same rule, but I didn't test it.

How it would ship
-----------------

As whatwg-url ships its Unicode tables:

- dev/fetch.sh downloads the registry at a pinned File-Date.
- A dev script writes the tables to resources/, and the CI writes them
  again and fails on a diff.
- A macro like whatwg-url's table-text inlines a table as a string literal
  in ClojureScript and reads it as a resource on the JVM.
- The functions that need the registry go in a namespace of their own, e.g.
  dk.simongray.langtag.registry. The root namespace then doesn't depend on
  the tables, and the size of a build doesn't rest on the rules above
  alone.

The registry gets new subtags four or five times a year: its records carry
18 dates of addition since 2023. Each update means a new File-Date and a
release. A function that reads the registry file itself would let a
project use a newer registry before then, as whatwg-url does with the
Public Suffix List.

What it would take
------------------

- the fetch script, the generator and the tables
- the registry namespace, with validity, the canonical form and the extlang
  form
- lookup and filtering of canonical tags, since RFC 4647, section 3.2, asks
  a matcher that canonicalizes ranges to canonicalize the tags too, or to
  match the original ranges as well
- tests by the examples of section 4.5 (e.g. en-BU becomes en-MM, zh-hakka
  becomes hak, and the extlang form of hak-CN is zh-hak-CN), by the
  registry's own records, and by the JDK, whose LanguageRange/parse adds
  the equivalents of a range

That's about as much work again as the minimal version, most of it in the
generator and the tests.

Questions for Simon
-------------------

1. Should the registry functions get a namespace of their own, or go in
   the root namespace and rely on dead-code elimination?
2. Should a project be able to read its own copy of the registry, for
   subtags newer than the release?
3. The -u- and -t- extensions need CLDR's data instead. Leave them for
   later?
