# shellcheck shell=bash
#
# path-filter_test.sh — static assertion over .github/workflows/release-tag.yml's
# `on.push.paths` filter.
#
# This is the loop-guard proof (Task 7.1). The release workflow must trigger ONLY on
# changes to the published library's production sources or build file. If the path set
# ever drifts (e.g. someone adds `**` or a docs path), docs/example/tooling pushes could
# start a release (violating Req 1) and — critically — a README-only merge from the
# release PR could re-trigger a run (violating Req 6, the loop guard). Pinning the exact
# set of two paths here makes that drift a test failure.
#
# Expected `on.push.paths`, EXACTLY these two entries and no others:
#   - streaming-core/src/main/**
#   - streaming-core/build.gradle.kts
#
# Harness contract: this file is sourced by run-tests.sh, which has already sourced
# assert.sh and zeroed ASSERT_FAILURES. It sources nothing itself and only calls the
# assert_* helpers. It must NOT `exit` (that would end the file mid-run); it lets each
# assertion accumulate into ASSERT_FAILURES.
#
# Properties validated:
#   Property 6 (loop exclusion): Validates Requirements 6.1, 6.2
#   Also covers Requirement 1.3 (path filters restrict the trigger to Library_Source_Paths).

# Resolve the workflow file relative to this test's directory so the suite is
# location-independent: scripts/release/test/ -> ../../../.github/workflows/release-tag.yml
_PF_HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKFLOW_FILE="$(cd "$_PF_HERE/../../.." && pwd)/.github/workflows/release-tag.yml"

# The exact path set the trigger must carry — order-independent, but no more and no less.
_PF_EXPECTED_1='streaming-core/src/main/**'
_PF_EXPECTED_2='streaming-core/build.gradle.kts'

# Sanity: the workflow file must exist before we can assert anything about it.
if [[ -f "$WORKFLOW_FILE" ]]; then
    assert_eq "exists" "exists" "release-tag.yml workflow file is present"
else
    assert_eq "exists" "missing" "release-tag.yml workflow file is present"
fi

# --- extract on.push.paths entries ------------------------------------------------------
#
# Two strategies. Prefer yq (a real YAML parse) when it is on PATH; otherwise fall back to
# a focused line scan that reads the `paths:` list under `on.push`. Both populate the
# newline-separated string _PF_PATHS so the assertions below are identical either way.

_PF_PATHS=""

if command -v yq >/dev/null 2>&1 && [[ -f "$WORKFLOW_FILE" ]]; then
    # `on` is a reserved-ish key in YAML (parses to boolean true in some tools); address it
    # by string key via [] to be safe across yq builds. Emit one path per line.
    _PF_PATHS="$(yq -r '.on.push.paths[]' "$WORKFLOW_FILE" 2>/dev/null)"
    if [[ -z "$_PF_PATHS" ]]; then
        # Some yq versions fold the `on:` key to `true:`; retry via that spelling.
        _PF_PATHS="$(yq -r '.["on"].push.paths[]' "$WORKFLOW_FILE" 2>/dev/null)"
    fi
elif [[ -f "$WORKFLOW_FILE" ]]; then
    # Fallback line scan: walk into on: -> push: -> paths:, then collect the `- 'value'`
    # list items until the indentation steps back out of the paths list. We strip the
    # leading `- ` and any surrounding single/double quotes, yielding bare path strings.
    _PF_PATHS="$(awk '
        # Track whether we are inside on: and push: and paths:.
        /^on:[[:space:]]*$/            { in_on = 1; next }
        in_on && /^[^[:space:]]/       { in_on = 0; in_push = 0; in_paths = 0 }  # dedent out of on:
        in_on && /^[[:space:]]+push:[[:space:]]*$/  { in_push = 1; in_paths = 0; next }
        in_push && /^[[:space:]]+paths:[[:space:]]*$/ { in_paths = 1; next }
        # Once inside paths:, a sibling key (same/less indent, "key:") ends the list.
        in_paths && /^[[:space:]]+[A-Za-z_-]+:[[:space:]]*/ { in_paths = 0 }
        in_paths && /^[[:space:]]+-[[:space:]]*/ {
            line = $0
            sub(/^[[:space:]]+-[[:space:]]*/, "", line)   # strip "  - "
            gsub(/^["\x27]|["\x27][[:space:]]*$/, "", line) # strip surrounding quotes
            sub(/[[:space:]]+$/, "", line)                 # trim trailing space
            print line
        }
    ' "$WORKFLOW_FILE")"
fi

# --- assertions -------------------------------------------------------------------------

# Count the entries. Exactly two — no accidental extra paths (which could widen the trigger
# and reopen the loop) and not fewer (which could drop a real trigger path).
if [[ -z "$_PF_PATHS" ]]; then
    _PF_COUNT=0
else
    _PF_COUNT="$(printf '%s\n' "$_PF_PATHS" | grep -c .)"
fi
assert_eq "2" "$_PF_COUNT" "on.push.paths contains exactly two entries"

# Both expected entries must be present (order-independent containment).
assert_contains "$_PF_PATHS" "$_PF_EXPECTED_1" \
    "on.push.paths includes $_PF_EXPECTED_1 (library production sources)"
assert_contains "$_PF_PATHS" "$_PF_EXPECTED_2" \
    "on.push.paths includes $_PF_EXPECTED_2 (library build file)"

# No unexpected entry: every extracted line must be one of the two expected paths. This is
# the real loop-guard check — a stray path like `**` or `README.md` would fail here.
_PF_UNEXPECTED=""
while IFS= read -r _pf_line; do
    [[ -z "$_pf_line" ]] && continue
    if [[ "$_pf_line" != "$_PF_EXPECTED_1" && "$_pf_line" != "$_PF_EXPECTED_2" ]]; then
        _PF_UNEXPECTED+="$_pf_line "
    fi
done <<< "$_PF_PATHS"
assert_eq "" "${_PF_UNEXPECTED% }" \
    "on.push.paths has no entries beyond the two Library_Source_Paths"
