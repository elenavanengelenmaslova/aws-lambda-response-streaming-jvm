# shellcheck shell=bash
#
# check_coverage_paths — the three coverage-report path lists agree, in order.
# Task 15.6; Requirement 16.5.
#
# The same three XML report paths are written down in three independent places. Nothing in
# the build fails when they drift apart — the Codecov upload would quietly send fewer
# reports, and the report-existence guard in CI would quietly check the wrong files — so
# the agreement is asserted here instead:
#
#   1. build/reports/coverage-report-paths.txt   authoritative; written by the root
#                                                `verifyCoverageReports` Gradle task.
#   2. .github/workflows/workflow-build.yml      the `files:` input of the
#                                                codecov/codecov-action@v5 step.
#   3. .github/workflows/workflow-build.yml      the `for f in …` loop of the
#                                                `Determine coverage-upload eligibility`
#                                                step (`id: cov`).
#
# The scaffold's COVERAGE_REPORTS_FALLBACK list in verify-quality-signals.sh is compared
# too, since check_gradle_build falls back to it when the Gradle task has not run.
#
# Parsing notes
# -------------
# The workflow's `files:` value is a YAML folded scalar (`>-`), so it loads as one line of
# comma-separated paths *with spaces after the commas*: "a.xml, b.xml, c.xml". Splitting on
# the comma alone leaves a leading space on entries 2 and 3, which is why every entry is
# trimmed before comparison. YAML goes through the scaffold's `yaml_eval` (Ruby/Psych).
#
# The path list file is read, never regenerated: producing it is check_gradle_build's job.
# When it is absent this check FAILS naming it, rather than passing on two sources it can
# still read — a check that passes because its input is missing proves nothing.

# _coverage_paths_trim <string> — strip leading and trailing whitespace.
_coverage_paths_trim() {
    local value=$1
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    printf '%s' "$value"
}

# _coverage_paths_split <text> — one trimmed, non-empty path per line, splitting the input
# on both commas and newlines so a folded scalar and a plain list parse the same way.
_coverage_paths_split() {
    local line trimmed out=""
    while IFS= read -r line; do
        trimmed="$(_coverage_paths_trim "$line")"
        [[ -n "$trimmed" && "$trimmed" != \#* ]] || continue
        out+="${trimmed}"$'\n'
    done < <(printf '%s\n' "$1" | tr ',' '\n')
    printf '%s' "$out"
}

# _coverage_paths_compare <label a> <list a> <label b> <list b> — element-wise equality of
# two newline-separated lists. On disagreement the failure names both sources and the
# differing entry; returns non-zero so the caller can keep counting.
_coverage_paths_compare() {
    local label_a=$1 list_a=$2 label_b=$3 list_b=$4
    local -a a=() b=()
    local line

    while IFS= read -r line; do
        [[ -n "$line" ]] && a+=("$line")
    done <<<"$list_a"
    while IFS= read -r line; do
        [[ -n "$line" ]] && b+=("$line")
    done <<<"$list_b"

    local max=${#a[@]}
    if (( ${#b[@]} > max )); then
        max=${#b[@]}
    fi

    local i va vb
    for (( i = 0; i < max; i++ )); do
        va="${a[i]:-<absent>}"
        vb="${b[i]:-<absent>}"
        if [[ "$va" != "$vb" ]]; then
            report_fail "${label_a} vs ${label_b}" \
                "entry $((i + 1)) differs: ${label_a} has '${va}', ${label_b} has '${vb}' (${#a[@]} path(s) vs ${#b[@]}). The two must list the same reports in the same order"
            return 1
        fi
    done

    report_pass "${label_a} vs ${label_b}" "${#a[@]} coverage report path(s) agree, in order"
    return 0
}

check_coverage_paths() {
    local workflow=".github/workflows/workflow-build.yml"

    if [[ ! -f "$workflow" ]]; then
        report_fail "$workflow" "workflow not found; the Codecov files: input and the eligibility loop cannot be compared against ${COVERAGE_PATHS_FILE}"
        return 0
    fi

    # --- 1. The authoritative list ---------------------------------------------------------
    if [[ ! -f "$COVERAGE_PATHS_FILE" ]]; then
        report_fail "$COVERAGE_PATHS_FILE" "authoritative coverage-path list not present — run './gradlew verifyCoverageReports -PexcludeTags=integration' first. This check will not pass while its input is missing"
        return 0
    fi

    local authoritative
    authoritative="$(_coverage_paths_split "$(cat "$COVERAGE_PATHS_FILE")")"
    if [[ -z "$authoritative" ]]; then
        report_fail "$COVERAGE_PATHS_FILE" "file is empty or holds no paths; verifyCoverageReports writes one report path per line"
        return 0
    fi
    report_note "$(printf '%s' "$authoritative" | grep -c . | tr -d ' ') path(s) read from ${COVERAGE_PATHS_FILE} (authoritative)"

    # --- 2. The Codecov action's files: input ----------------------------------------------
    local files_raw rc=0
    files_raw="$(yaml_eval "$workflow" '
        doc["jobs"].to_h.values
            .flat_map { |job| (job["steps"] || []) }
            .select { |step| step["uses"].to_s.start_with?("codecov/codecov-action@v5") }
            .map { |step| (step["with"] || {})["files"].to_s }
    ')" || rc=$?

    if (( rc != 0 )); then
        report_fail "${workflow} codecov/codecov-action@v5 files:" "could not parse the workflow as YAML"
    elif [[ -z "$(_coverage_paths_trim "$files_raw")" ]]; then
        report_fail "${workflow} codecov/codecov-action@v5 files:" "no codecov/codecov-action@v5 step with a files: input found; the upload would default to auto-discovery instead of the three reports in ${COVERAGE_PATHS_FILE}"
    elif (( $(printf '%s\n' "$files_raw" | grep -c . | tr -d ' ') > 1 )); then
        report_fail "${workflow} codecov/codecov-action@v5 files:" "more than one codecov/codecov-action@v5 step declares files:; exactly one upload step is expected so the comparison against ${COVERAGE_PATHS_FILE} is unambiguous"
    else
        _coverage_paths_compare \
            "$COVERAGE_PATHS_FILE" "$authoritative" \
            "${workflow} codecov files:" "$(_coverage_paths_split "$files_raw")" || true
    fi

    # --- 3. The eligibility step's report-existence loop -----------------------------------
    local cov_run
    rc=0
    cov_run="$(yaml_eval "$workflow" '
        doc["jobs"].to_h.values
            .flat_map { |job| (job["steps"] || []) }
            .select { |step| step["id"].to_s == "cov" }
            .map { |step| step["run"].to_s }
    ')" || rc=$?

    if (( rc != 0 )); then
        report_fail "${workflow} step id: cov" "could not parse the workflow as YAML"
        return 0
    fi
    if [[ -z "$(_coverage_paths_trim "$cov_run")" ]]; then
        report_fail "${workflow} step id: cov" "no 'Determine coverage-upload eligibility' step (id: cov) with a run: script found; the report-existence guard cannot be checked"
        return 0
    fi

    # The `for f in <path> \ <path> \ <path>; do` header, continuation lines included.
    local loop_paths
    loop_paths="$(printf '%s\n' "$cov_run" | awk '
        /for[[:space:]]+[A-Za-z_][A-Za-z0-9_]*[[:space:]]+in[[:space:]]/ { inside = 1 }
        inside {
            print
            if ($0 ~ /;[[:space:]]*do([[:space:]]|$)/ || $0 ~ /(^|[[:space:]])do[[:space:]]*$/) { exit }
        }
    ' | grep -oE '[A-Za-z0-9_./@-]+\.xml' || true)"

    if [[ -z "$loop_paths" ]]; then
        report_fail "${workflow} step id: cov report-existence loop" "no coverage report paths found in the 'for f in …' loop; the guard would check nothing while still reporting reports-missing=0"
        return 0
    fi

    _coverage_paths_compare \
        "$COVERAGE_PATHS_FILE" "$authoritative" \
        "${workflow} id: cov loop" "$(_coverage_paths_split "$loop_paths")" || true

    # --- 4. The scaffold's fallback list ---------------------------------------------------
    # check_gradle_build uses COVERAGE_REPORTS_FALLBACK when the Gradle task has not run, so
    # a stale fallback would silently check the wrong files in exactly that situation.
    local fallback="" path
    for path in "${COVERAGE_REPORTS_FALLBACK[@]:-}"; do
        [[ -n "$path" ]] && fallback+="${path}"$'\n'
    done
    if [[ -z "$fallback" ]]; then
        report_fail "verify-quality-signals.sh COVERAGE_REPORTS_FALLBACK" "the fallback report list is empty or unset"
    else
        _coverage_paths_compare \
            "$COVERAGE_PATHS_FILE" "$authoritative" \
            "verify-quality-signals.sh COVERAGE_REPORTS_FALLBACK" "$fallback" || true
    fi
}
