# shellcheck shell=bash
#
# update-readme-version_test.sh — unit tests for scripts/release/update-readme-version.sh.
#
# TDD: these tests are written BEFORE the script exists (Task 4.1); the implementation
# lands in Task 4.2. Until then every case that invokes the script is EXPECTED to fail —
# that is the point of writing the tests first.
#
# What the script under test does (see design.md "README PR Mechanics"):
#   - takes one arg `vX.Y.Z`, strips the leading `v`
#   - rewrites the single README coordinate line
#       implementation("nl.vintik:aws-lambda-streaming-core:<old>")
#     to carry the new X.Y.Z, anchoring on the coordinate (never a blind line number):
#       sed -E -i.bak 's#(nl\.vintik:aws-lambda-streaming-core:)[0-9]+\.[0-9]+\.[0-9]+#\1<ver>#' README.md
#   - removes the .bak, and fails loudly (exit non-zero) when `git diff --quiet -- README.md`
#     reports no change (anchor not found / nothing to do), leaving README unchanged.
#
# Harness contract: this file is sourced by run-tests.sh, which has already sourced
# assert.sh and zeroed ASSERT_FAILURES. It sources nothing itself and only calls the
# assert_* helpers. It must NOT `exit` (that would end the file mid-run); instead it
# lets each assertion accumulate into ASSERT_FAILURES.
#
# Properties validated:
#   Property 4 (README round-trip, exactly one changed line): Validates Requirements 5.2, 8.3

# Path to the script under test, resolved relative to this test file's directory so the
# suite is location-independent. The script does not exist yet (TDD) — that is expected.
_UR_HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UPDATE_README_SCRIPT="$(cd "$_UR_HERE/.." && pwd)/update-readme-version.sh"

# --- fixtures ---------------------------------------------------------------------------

# A realistic README fixture whose coordinate line mirrors the real README.md (line ~33),
# surrounded by enough neighbouring lines to prove only ONE line changes. The starting
# version is 2.1.0, matching the repo's current README.
_write_readme_fixture() {
    # $1 = destination file path
    cat > "$1" <<'EOF'
# aws-lambda-streaming-core

A JVM library that implements the AWS Lambda / API Gateway HTTP response streaming protocol.

## Dependency

```kotlin
implementation("nl.vintik:aws-lambda-streaming-core:2.1.0")
```

**One dependency:** `kotlinx-serialization-json`, for metadata encoding.

Compiled for **Java 21**.
EOF
}

# A broken fixture where the coordinate anchor has been renamed/removed, so the script's
# sed matches nothing and the git-diff guard must fire (exit non-zero, README untouched).
_write_anchor_missing_fixture() {
    # $1 = destination file path
    cat > "$1" <<'EOF'
# aws-lambda-streaming-core

## Dependency

```kotlin
implementation("com.example:some-other-artifact:2.1.0")
```

Compiled for **Java 21**.
EOF
}

# Create a throwaway git repo in a fresh temp dir, drop README.md in via the given writer,
# and commit it so `git diff --quiet -- README.md` has a clean baseline to compare against.
# Echoes the repo dir on stdout. Caller is responsible for cleanup.
_make_repo() {
    # $1 = fixture-writer function name
    local writer=$1
    local dir
    dir="$(mktemp -d "${TMPDIR:-/tmp}/update-readme-test.XXXXXX")"
    (
        cd "$dir" || exit 1
        git init -q
        git config user.email "test@example.com"
        git config user.name "Release Test"
        "$writer" "$dir/README.md"
        git add README.md
        git commit -q -m "baseline README"
    )
    printf '%s\n' "$dir"
}

# Extract the single coordinate line's trailing version (X.Y.Z) from a README, or empty.
_coordinate_version() {
    # $1 = README path
    sed -nE 's#.*nl\.vintik:aws-lambda-streaming-core:([0-9]+\.[0-9]+\.[0-9]+).*#\1#p' "$1" \
        | head -n1
}

# Count how many lines differ between the committed README and the working copy. Uses the
# same git baseline the script's guard relies on. Echoes an integer — 0 when nothing
# changed (git prints no numstat row, so the END block supplies the zero).
_changed_line_count() {
    # $1 = repo dir
    local dir=$1
    ( cd "$dir" && git diff --numstat -- README.md \
        | awk '{ sum += $1 + $2 } END { print sum + 0 }' )
}

# --- round-trip cases (Property 4) ------------------------------------------------------
#
# For several versions, after running the script the coordinate line must equal exactly
# `nl.vintik:aws-lambda-streaming-core:X.Y.Z` AND exactly one line may have changed.
# A changed single line shows as 1 addition + 1 deletion in `git diff --numstat`, i.e. 2.

for version in v2.2.0 v3.0.0 v2.1.1 v10.20.30 v0.0.1 v1.0.0; do
    ver_no_v="${version#v}"
    repo="$(_make_repo _write_readme_fixture)"

    ( cd "$repo" && "$UPDATE_README_SCRIPT" "$version" ) >/dev/null 2>&1
    rc=$?

    assert_exit_code 0 "$rc" "round-trip $version: script exits zero"

    actual_coord="$(_coordinate_version "$repo/README.md")"
    assert_eq "$ver_no_v" "$actual_coord" \
        "round-trip $version: coordinate line is nl.vintik:aws-lambda-streaming-core:$ver_no_v"

    # Exactly one line changed → one removed + one added → numstat sum of 2.
    changed="$(_changed_line_count "$repo")"
    assert_eq "2" "$changed" \
        "round-trip $version: exactly one line changed (1 add + 1 del)"

    # The .bak sed leaves behind must be cleaned up by the script.
    if [[ -e "$repo/README.md.bak" ]]; then
        assert_eq "no README.md.bak" "README.md.bak present" \
            "round-trip $version: script removes the sed .bak file"
    else
        assert_eq "no README.md.bak" "no README.md.bak" \
            "round-trip $version: script removes the sed .bak file"
    fi

    rm -rf "$repo"
done

# --- anchor-missing case ----------------------------------------------------------------
#
# When the coordinate anchor is gone, the script must exit non-zero and leave README
# byte-for-byte unchanged (the git-diff guard fires before any damage is kept).

repo="$(_make_repo _write_anchor_missing_fixture)"
before="$(cat "$repo/README.md")"

( cd "$repo" && "$UPDATE_README_SCRIPT" v2.2.0 ) >/dev/null 2>&1
rc=$?

# Non-zero exit (anything but 0). assert_exit_code checks equality, so assert it is 1 —
# the design specifies `exit 1`.
assert_exit_code 1 "$rc" "anchor-missing: script exits non-zero"

after="$(cat "$repo/README.md")"
assert_eq "$before" "$after" "anchor-missing: README is left unchanged"

# And git agrees nothing changed (guard baseline is clean).
changed="$(_changed_line_count "$repo")"
assert_eq "0" "$changed" "anchor-missing: no lines changed per git"

rm -rf "$repo"
