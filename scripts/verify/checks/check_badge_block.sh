# shellcheck shell=bash
#
# check_badge_block — the README badge block is exactly the agreed literal.
# Task 15.2; Requirement 16.x (badge block), registry artefact "README.md badge block".
#
# The badge block is the first thing a reader sees, and it is the one place where a
# copy-paste edit silently changes what the repository claims about itself. So it is
# compared as a literal rather than pattern-matched: the nine lines below are embedded
# here, and README.md lines 3 to 11 must equal them character for character.
#
# Three assertions:
#
#   1. Literal equality of README.md lines 3–11 with EXPECTED_BLOCK below. A mismatch
#      names the README line number and the first differing character position, with both
#      versions of the line printed, so the fix does not need a second tool.
#   2. Exactly 8 badges in the block — the count is what a dropped or duplicated badge
#      line changes first, and stating it separately makes that failure self-explaining.
#   3. The block sits directly under the `# ` title: line 1 is the title, line 2 is the
#      single blank separator, line 3 is the first badge. Nothing else in between.
#
# Plus: four retired badge sources must not appear anywhere in README.md — not rendered
# and not commented out. A commented-out badge is a promise to bring it back, and these
# four were dropped deliberately; leaving the markup behind invites someone to re-enable a
# signal this repository does not maintain.
#
# Nothing is read from the design document at runtime. That is the point: if the agreed
# block ever changes, this file changes with it in the same commit, under review.

check_badge_block() {
    local readme="README.md"
    local block_start=3

    # Retired badge sources: dropped on purpose, must not reappear in any form.
    local -a forbidden=(
        "securityscorecards"
        "bestpractices.dev"
        "swagger/valid"
        "codacy"
    )

    if [[ ! -f "$readme" ]]; then
        report_fail "$readme" "file not found; the badge block cannot be compared against the expected literal"
        return 0
    fi

    # --- The expected block, embedded verbatim ------------------------------------------
    # Quoted heredoc: no expansion, so what is written here is what is compared.
    local -a expected=()
    local line
    while IFS= read -r line; do
        expected+=("$line")
    done <<'EXPECTED_BLOCK'
[![GitHub release](https://img.shields.io/github/v/release/elenavanengelenmaslova/aws-lambda-streaming-jvm-runtime)](https://github.com/elenavanengelenmaslova/aws-lambda-streaming-jvm-runtime/releases/latest)
[![Maven Central](https://img.shields.io/maven-central/v/nl.vintik/aws-lambda-streaming-core)](https://central.sonatype.com/artifact/nl.vintik/aws-lambda-streaming-core)
[![Build Status](https://github.com/elenavanengelenmaslova/aws-lambda-streaming-jvm-runtime/actions/workflows/ci-main-build.yml/badge.svg?branch=main)](https://github.com/elenavanengelenmaslova/aws-lambda-streaming-jvm-runtime/actions/workflows/ci-main-build.yml)
[![codecov](https://codecov.io/gh/elenavanengelenmaslova/aws-lambda-streaming-jvm-runtime/graph/badge.svg)](https://codecov.io/gh/elenavanengelenmaslova/aws-lambda-streaming-jvm-runtime)
[![CodeQL](https://github.com/elenavanengelenmaslova/aws-lambda-streaming-jvm-runtime/actions/workflows/codeql.yml/badge.svg?branch=main&event=push)](https://github.com/elenavanengelenmaslova/aws-lambda-streaming-jvm-runtime/security/code-scanning)

[![Kotlin](https://img.shields.io/badge/kotlin-2.3.0-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![JVM](https://img.shields.io/badge/JVM-21-orange.svg)](https://openjdk.org)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
EXPECTED_BLOCK

    local -a actual=()
    while IFS= read -r line; do
        actual+=("$line")
    done <"$readme"

    local expected_count=${#expected[@]}
    local block_end=$(( block_start + expected_count - 1 ))

    if (( ${#actual[@]} < block_end )); then
        report_fail "$readme" "file has ${#actual[@]} line(s); the badge block needs lines ${block_start}–${block_end} (${expected_count} lines)"
        return 0
    fi

    # --- 1. Literal equality, line by line ----------------------------------------------
    local i idx lineno mismatches=0 pos len_expected len_actual shorter
    for (( i = 0; i < expected_count; i++ )); do
        idx=$(( block_start - 1 + i ))
        lineno=$(( block_start + i ))
        [[ "${actual[idx]}" == "${expected[i]}" ]] && continue

        mismatches=$(( mismatches + 1 ))

        # First differing character position, 1-based, for a pinpointed message.
        len_expected=${#expected[i]}
        len_actual=${#actual[idx]}
        shorter=$len_expected
        (( len_actual < shorter )) && shorter=$len_actual
        pos=0
        while (( pos < shorter )) && [[ "${expected[i]:pos:1}" == "${actual[idx]:pos:1}" ]]; do
            pos=$(( pos + 1 ))
        done

        report_fail "$readme line ${lineno}" \
            "differs from the expected badge block at character $(( pos + 1 )) (expected ${len_expected} chars, found ${len_actual}); expected: '${expected[i]}'; found: '${actual[idx]}'"
    done

    if (( mismatches == 0 )); then
        report_pass "$readme badge block" "lines ${block_start}–${block_end} match the expected literal character for character"
    fi

    # --- 2. Exactly 8 badges ------------------------------------------------------------
    local badge_count=0
    for (( i = 0; i < expected_count; i++ )); do
        idx=$(( block_start - 1 + i ))
        [[ "${actual[idx]}" =~ ^\[!\[[^]]+\]\(.+\)\]\(.+\)$ ]] && badge_count=$(( badge_count + 1 ))
    done
    if (( badge_count == 8 )); then
        report_pass "$readme badge block" "exactly 8 badges on lines ${block_start}–${block_end}"
    else
        report_fail "$readme badge block" "expected exactly 8 badges on lines ${block_start}–${block_end}, found ${badge_count}"
    fi

    # --- 3. The block sits directly under the `# ` title --------------------------------
    local title=${actual[0]} separator=${actual[1]} first_badge=${actual[block_start - 1]}
    if [[ ! "$title" =~ ^\#[[:space:]] ]]; then
        report_fail "$readme line 1" "expected the '# ' title, found: '${title}'"
    elif [[ -n "${separator//[[:space:]]/}" ]]; then
        report_fail "$readme line 2" "expected a single blank line between the title and the badge block, found: '${separator}'"
    elif [[ ! "$first_badge" =~ ^\[!\[ ]]; then
        report_fail "$readme line ${block_start}" "expected the first badge directly under the title, found: '${first_badge}'"
    else
        report_pass "$readme" "badge block starts on line ${block_start}, directly under the '${title}' title with only the blank separator between"
    fi

    # --- Retired badge sources, rendered or commented out -------------------------------
    local token hits present=0
    for token in "${forbidden[@]}"; do
        hits="$(grep -n -i -F -- "$token" "$readme" || true)"
        [[ -z "$hits" ]] && continue
        present=1
        report_fail "$readme" "retired badge source '${token}' still present (rendered or commented out) on line(s): $(printf '%s\n' "$hits" | cut -d: -f1 | tr '\n' ' ' | sed -E 's/[[:space:]]+$//')"
    done
    if (( present == 0 )); then
        report_pass "$readme" "none of the retired badge sources present, rendered or commented out (${forbidden[*]})"
    fi
}
