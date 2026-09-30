#!/usr/bin/env bash
#
# dependency-baseline.sh — Capture a dependency-resolution baseline for all modules.
#
# Runs the `resolvedCoordinates` task (registered in the root build.gradle.kts) and collects the
# per-module reports under build/dependency-baseline/<label>/. Two labelled captures can then be
# compared to prove a build-file change altered declaration form only:
#
#   ./scripts/dependency-baseline.sh before      # pre-migration tree
#   ...change dependency declarations...
#   ./scripts/dependency-baseline.sh after       # post-migration tree
#   diff -ru build/dependency-baseline/before build/dependency-baseline/after   # must be empty
#
# Usage: ./scripts/dependency-baseline.sh <label>

set -euo pipefail

if [ "$#" -ne 1 ] || [ -z "${1// }" ]; then
    echo "Usage: $0 <label>   (e.g. $0 before)" >&2
    exit 1
fi

label="$1"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

target="build/dependency-baseline/${label}"

echo "Resolving coordinates for all modules..."
./gradlew --quiet resolvedCoordinates -PexcludeTags=integration

rm -rf "$target"
mkdir -p "$target"

shopt -s nullglob
reports=(*/build/reports/resolved-coordinates/*.txt)
shopt -u nullglob

if [ "${#reports[@]}" -eq 0 ]; then
    echo "ERROR: no resolved-coordinates reports found under */build/reports/resolved-coordinates/" >&2
    exit 1
fi

cp "${reports[@]}" "$target/"

echo "Captured baseline '${label}' (${#reports[@]} module(s)) in ${target}/:"
for report in "$target"/*.txt; do
    echo "  $(basename "$report")  $(wc -l <"$report" | tr -d ' ') lines"
done
