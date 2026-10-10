#!/usr/bin/env bash
#
# run-tests.sh — the release-script test runner.
#
# Discovers every `*_test.sh` beside it, runs each in its own subshell, counts how many
# files pass vs. fail, and exits non-zero if any file failed. This is the one entry point
# CI calls to gate the release scripts.
#
# Why a plain-bash harness instead of bats: the suite must stay dependency-free so it runs
# anywhere the scripts do (CI, Colima dev box, a fresh checkout) without an install step.
# bats is used ONLY if it already happens to be on PATH — then we hand the same test files
# to it; otherwise the built-in runner below executes them directly. Either way the test
# files look identical (they source assert.sh and call the assert_* helpers), so the choice
# of runner is invisible to the tests.
#
# Test-file contract:
#   - A test file is any `*_test.sh` in this directory.
#   - It sources `assert.sh` (and, when it exercises a release script, invokes the script
#     under test by path). Each assert_* call reports a line and, on failure, bumps the
#     per-file counter `ASSERT_FAILURES`.
#   - The runner resets `ASSERT_FAILURES` to 0 before each file and reads it afterwards:
#     a file "fails" if it leaves ASSERT_FAILURES > 0 OR exits non-zero. This lets a test
#     file both use the soft assert_* helpers and still hard-fail with `exit 1` if it wants.
#
# Exit code: 0 when every discovered test file passed; 1 if any failed or if no test files
# were found (an empty suite is treated as a failure so a mis-wired path is noticed).

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Discover test files deterministically (sorted) so output order is stable.
shopt -s nullglob
mapfile -t TEST_FILES < <(printf '%s\n' "$HERE"/*_test.sh | sort)
shopt -u nullglob

if (( ${#TEST_FILES[@]} == 0 )); then
    printf 'No *_test.sh files found in %s\n' "$HERE" >&2
    exit 1
fi

# Prefer bats only when it is already installed; keep the suite runnable without it.
if command -v bats >/dev/null 2>&1; then
    printf 'Running %d test file(s) with bats...\n' "${#TEST_FILES[@]}"
    bats "${TEST_FILES[@]}"
    exit $?
fi

printf 'Running %d test file(s) with the plain-bash harness...\n\n' "${#TEST_FILES[@]}"

passed=0
failed=0
failed_files=()

for test_file in "${TEST_FILES[@]}"; do
    name="$(basename "$test_file")"
    printf '==> %s\n' "$name"

    # Each file runs in its own subshell so a `set -e`, an `exit`, or a stray variable in
    # one file cannot leak into the next. ASSERT_FAILURES is exported in and read back out
    # via the subshell's exit status: the test file ends with the counter check below.
    (
        set +e
        export ASSERT_FAILURES=0
        # shellcheck source=/dev/null
        source "$HERE/assert.sh"
        # shellcheck source=/dev/null
        source "$test_file"
        # The subshell's exit status encodes the file verdict: non-zero when any assertion
        # failed. A test file that calls `exit N` itself overrides this and is honoured.
        exit "$(( ASSERT_FAILURES > 0 ? 1 : 0 ))"
    )
    status=$?

    if (( status == 0 )); then
        printf '    => PASS\n\n'
        passed=$(( passed + 1 ))
    else
        printf '    => FAIL (exit %d)\n\n' "$status"
        failed=$(( failed + 1 ))
        failed_files+=("$name")
    fi
done

printf '%s\n' "----------------------------------------"
printf 'Test files: %d passed, %d failed, %d total\n' "$passed" "$failed" "${#TEST_FILES[@]}"

if (( failed > 0 )); then
    printf 'Failed: %s\n' "${failed_files[*]}" >&2
    exit 1
fi

exit 0
