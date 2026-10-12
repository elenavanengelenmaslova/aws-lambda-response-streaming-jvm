# shellcheck shell=bash
#
# harness_selftest_test.sh — proves the test harness and assertion helpers work.
#
# This is a self-test for Task 1's deliverables (assert.sh + run-tests.sh). It exercises
# the happy path of each assertion so a green run confirms the harness itself is sound
# before the real release-script tests (compute-version, update-readme) are added in later
# tasks. It asserts only passing cases here; failure-path behaviour of the asserts is
# covered implicitly by those later suites. Sourced by run-tests.sh, which has already
# sourced assert.sh and zeroed ASSERT_FAILURES for this file.

# assert_eq: exact string equality.
assert_eq "v1.2.3" "v1.2.3" "assert_eq matches identical strings"

# assert_contains: substring containment.
assert_contains "nl.vintik:aws-lambda-streaming-core:2.1.0" "aws-lambda-streaming-core" \
    "assert_contains finds the coordinate anchor"

# assert_exit_code: numeric exit-code equality, including a real command's status.
assert_exit_code 0 0 "assert_exit_code matches zero"
true;  assert_exit_code 0 "$?" "assert_exit_code reads a successful command"
false; assert_exit_code 1 "$?" "assert_exit_code reads a failing command"
