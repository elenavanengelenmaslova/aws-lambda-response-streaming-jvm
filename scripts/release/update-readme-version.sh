#!/usr/bin/env bash
#
# update-readme-version.sh — rewrite the single README dependency-coordinate version.
#
# Takes one argument `vX.Y.Z`, strips the leading `v`, and rewrites the trailing version
# on the README coordinate line
#
#     implementation("nl.vintik:aws-lambda-streaming-core:<old>")
#
# to carry the new X.Y.Z. The edit anchors on the coordinate prefix
# (`nl.vintik:aws-lambda-streaming-core:`) rather than a blind line number, so it is
# resilient to the line moving. Only the trailing version is replaced (Req 5.2, 8.3).
#
# Operates on README.md in the current working directory. On success it exits 0 after
# removing the sed `.bak`. If the coordinate anchor cannot be found (nothing changed),
# it fails loudly: emits a `::error::` annotation and exits 1, leaving README unchanged.
#
# Requirements: 5.2, 8.3

set -euo pipefail

version="${1:?usage: update-readme-version.sh vX.Y.Z}"

# Strip the leading `v` to get the bare X.Y.Z coordinate version.
version_no_v="${version#v}"

# Anchor on the coordinate, capturing everything up to and including the final ':',
# then swap only the trailing version. The `.bak` is a BSD/GNU-portable in-place marker.
sed -E -i.bak \
    's#(nl\.vintik:aws-lambda-streaming-core:)[0-9]+\.[0-9]+\.[0-9]+#\1'"${version_no_v}"'#' \
    README.md
rm -f README.md.bak

# Fail loudly when nothing changed — the coordinate anchor was renamed/removed, so there
# is nothing to update. The git-diff guard also keeps a no-op from masquerading as success.
if git diff --quiet -- README.md; then
    echo "::error::README version anchor not found (nothing changed in README.md)"
    exit 1
fi
