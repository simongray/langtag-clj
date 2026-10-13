#!/bin/sh
# Download the newest IANA Language Subtag Registry into dev-resources/.
# IANA keeps no older copies, so the repository keeps the one that the
# table in resources is made from: commit it along with the new table.
set -eu

cd "$(dirname "$0")/.."
mkdir -p dev-resources
curl -sSfL -o dev-resources/language-subtag-registry \
  https://www.iana.org/assignments/language-subtag-registry/language-subtag-registry
head -1 dev-resources/language-subtag-registry
