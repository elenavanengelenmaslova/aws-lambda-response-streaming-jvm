# shellcheck shell=bash
#
# assert.sh — a tiny assertion helper for the release-script test suite.
#
# Sourced by every `*_test.sh` file. Keeps the suite dependency-free: no bats, no
# external assertion library, just three functions and a shared counter.
#
# Contract with the test files and the runner (run-tests.sh):
#   - Each assertion prints a one-line PASS/FAIL diagnostic and, on failure, bumps the
#     per-file failure counter `ASSERT_FAILURES` (exported so a sourced test file and
#     the harness share it).
#   - Assertions do NOT exit the process; a test file keeps running so every failing
#     assertion in it is reported in one pass. The runner inspects the counter to decide
#     the file's verdict, so an assertion failure never aborts the whole suite.
#   - A short, optional message is appended to the diagnostic to name the case.
#
# The three assertions cover what the version/README scripts need:
#   assert_eq        <expected> <actual> [message]      — exact string equality
#   assert_contains  <haystack> <needle> [message]      — substring containment
#   assert_exit_code <expected> <actual> [message]      — numeric exit-code equality
#
# `ASSERT_FAILURES` starts at zero when this file is first sourced; the runner resets it
# per test file (see run-tests.sh) so each file gets an independent tally.

: "${ASSERT_FAILURES:=0}"

# _assert_pass <description> — record and report a passing assertion.
_assert_pass() {
    printf '    ok   - %s\n' "$1"
}

# _assert_fail <description> — record and report a failing assertion.
_assert_fail() {
    ASSERT_FAILURES=$(( ASSERT_FAILURES + 1 ))
    printf '    FAIL - %s\n' "$1" >&2
}

# assert_eq <expected> <actual> [message]
# Passes when the two strings are byte-for-byte equal.
assert_eq() {
    local expected=$1 actual=$2 message=${3:-}
    local label="assert_eq"
    [[ -n "$message" ]] && label="$message"
    if [[ "$expected" == "$actual" ]]; then
        _assert_pass "$label"
    else
        _assert_fail "$label: expected [$expected] but got [$actual]"
    fi
}

# assert_contains <haystack> <needle> [message]
# Passes when <needle> is a substring of <haystack>.
assert_contains() {
    local haystack=$1 needle=$2 message=${3:-}
    local label="assert_contains"
    [[ -n "$message" ]] && label="$message"
    if [[ "$haystack" == *"$needle"* ]]; then
        _assert_pass "$label"
    else
        _assert_fail "$label: expected to find [$needle] in [$haystack]"
    fi
}

# assert_exit_code <expected> <actual> [message]
# Passes when the two exit codes are numerically equal.
assert_exit_code() {
    local expected=$1 actual=$2 message=${3:-}
    local label="assert_exit_code"
    [[ -n "$message" ]] && label="$message"
    if [[ "$expected" -eq "$actual" ]]; then
        _assert_pass "$label"
    else
        _assert_fail "$label: expected exit code $expected but got $actual"
    fi
}
