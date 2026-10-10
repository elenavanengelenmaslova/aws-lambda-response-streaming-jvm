#!/usr/bin/env bash
#
# compute-version.sh — compute the next SemVer Release_Tag from Conventional Commits.
#
# Part of the automated-release-pipeline (invoked by the `compute` job of
# release-tag.yml). Pure git/text work: read the latest strict `vX.Y.Z` tag, classify
# every non-merge commit since that tag, pick the highest bump, and emit the next version.
#
# Output contract — three keys appended to the file named by $GITHUB_OUTPUT:
#     version=vX.Y.Z   (empty string when bump=none)
#     bump=major|minor|patch|initial|none
#     exists=           (placeholder; the `tag` job computes the real existence guard)
#
# Classification of each non-merge commit in `latest..HEAD`:
#     any type with `!` before the `:` (feat!:, fix!:, …) or a `BREAKING CHANGE:` footer -> major
#     feat: / feat(scope):                                                               -> minor
#     fix:  / fix(scope):                                                                -> patch
#     chore(deps): / chore(deps-dev): / build(deps): / build(deps-dev):                  -> patch
#         ^-- REPO-SPECIFIC CONVENTION (not vanilla Conventional Commits): Dependabot in
#             this repo commits with `commit-message.prefix: chore` + scope, so dependency
#             upgrades arrive as `chore(deps)` / `chore(deps-dev)`. Under strict CC `chore`
#             is no-bump; here the deps/deps-dev (and build) scopes are promoted to a PATCH
#             bump so a dependency upgrade can still cut a release. A plain `chore:` with no
#             deps scope stays no-bump. This gotcha is also recorded in docs/log.md.
#
# Max-severity precedence: major > minor > patch. No prior tag -> Initial_Version v1.0.0.

set -euo pipefail

# Where key=value lines are emitted. In CI this is set by GitHub Actions; the tests
# redirect it to a temp file. Fall back to stdout if unset so a bare manual run is visible.
GITHUB_OUTPUT="${GITHUB_OUTPUT:-/dev/stdout}"

# 1. Latest tag: strict vMAJOR.MINOR.PATCH only, newest by version order.
latest="$(git tag --list 'v*' --sort=-v:refname \
  | grep -E '^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$' \
  | head -n1 || true)"

# 2. No prior tag -> Initial_Version (Req 2.5).
if [ -z "$latest" ]; then
  {
    echo "version=v1.0.0"
    echo "bump=initial"
    echo "exists="
  } >> "$GITHUB_OUTPUT"
  exit 0
fi

# 3. Classify every non-merge commit in latest..HEAD.
#    `%s%n%b` prints the subject then the full body, so a `BREAKING CHANGE:` footer
#    lands on its own line and is matched directly.
range="${latest}..HEAD"
major=0; minor=0; patch=0

while IFS= read -r line; do
  # `!` before the first `:` -> breaking (feat!:, fix!:, refactor!: …).
  case "$line" in
    *":"*)
      type_field="${line%%:*}"
      case "$type_field" in
        *"!") major=1 ;;
      esac
      ;;
  esac
  case "$line" in
    "BREAKING CHANGE:"*)              major=1 ;;
    feat\(*\):*|feat:*)               minor=1 ;;
    fix\(*\):*|fix:*)                 patch=1 ;;
    # Dependabot / build dependency scopes -> patch (repo convention; see header).
    chore\(deps\):*|chore\(deps-dev\):*|build\(deps\):*|build\(deps-dev\):*) patch=1 ;;
  esac
done < <(git log --no-merges --format='%s%n%b' "$range")

# 4. Max-severity precedence: major > minor > patch (Req 2.7). Compute next vX.Y.Z.
IFS='.' read -r cur_major cur_minor cur_patch <<< "${latest#v}"
if   [ "$major" = 1 ]; then next="v$((cur_major + 1)).0.0";                        bump=major
elif [ "$minor" = 1 ]; then next="v${cur_major}.$((cur_minor + 1)).0";             bump=minor
elif [ "$patch" = 1 ]; then next="v${cur_major}.${cur_minor}.$((cur_patch + 1))";  bump=patch
else                        next="";                                               bump=none
fi

# 5. Emit the result. `bump=none` carries an empty version by contract.
{
  echo "version=${next}"
  echo "bump=${bump}"
  echo "exists="
} >> "$GITHUB_OUTPUT"
