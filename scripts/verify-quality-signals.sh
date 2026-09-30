#!/usr/bin/env bash
#
# verify-quality-signals.sh — Verify the repository's quality signals before merge.
#
# This is a LOCAL PRE-MERGE TOOL, not a CI job, and deliberately so:
#
#   * Requirement 3.3 forbids `ci-main-build.yml` from declaring a second job, so there is
#     no place in the merge gate for these checks to live.
#   * Requirement 5.5 keeps third-party availability out of the merge gate. Making every
#     pull request depend on shields.io, codecov.io and the GitHub API being reachable
#     would contradict that decision.
#
# `.github/pull_request_template.md` asks for this script's output instead, which is how
# Requirement 16.6 is met. Moving the offline subset into CI later (actionlint plus the
# cross-file checks) is a reasonable follow-up and is noted in CONTRIBUTING.md.
#
# Modes
# -----
#   --offline   YAML parsing, badge-block literal comparison, cross-file consistency,
#               permission tables, catalog invariants, config path-pattern existence,
#               documentation coverage, `sam validate`, the Gradle build and report checks.
#   (default)   The offline subset plus the network checks: badge URL reachability and
#               rendering, and `uses:` ref resolution.
#
# Pending badges (Requirement 16.8)
# ---------------------------------
# Four badges point at signals that do not exist until a maintainer acts or a first
# release happens. They are declared in PENDING_BADGES below, reported as PENDING with
# the blocking item named, and excluded from the failure count. The script asserts that
# list agrees with the `docs/log.md` pending entry, so the two cannot drift.
#
# Requirements covered by this file itself: 16.4 (both SAM templates validate), 16.5
# (Gradle build plus the three coverage reports), 16.7 (every failure names the check and
# the artefact; non-zero exit on any failure), 16.8 (pending, not failed).
#
# Adding a check — tasks 15.2 through 15.12
# -----------------------------------------
#   1. Create `scripts/verify/checks/<check_name>.sh` defining one shell function whose
#      name is exactly the registry name, e.g.
#
#          # shellcheck shell=bash
#          check_badge_block() {
#              report_pass "README.md badge block" "8 lines match the expected literal"
#          }
#
#      Every file matching `scripts/verify/checks/*.sh` is sourced before dispatch, so no
#      edit to this file is needed. Registry entries whose function is not defined report
#      SKIP, which is why the script runs end to end at every point in the task sequence.
#   2. Report only through the helpers: report_pass / report_fail / report_pending /
#      report_skip / report_note. Never call `exit` from a check and never print a bare
#      line — the helpers are what name the check alongside the artefact and keep the
#      counters honest. Return 0; the counters decide the exit code.
#   3. A check that reports nothing is treated as a failure, because a check that silently
#      passes on everything is indistinguishable from one that works (task 15.13).
#   4. Parse YAML with the `yaml_*` helpers below (Ruby's Psych). PyYAML is not assumed to
#      be present; a missing parser is a hard, named error, never a silent skip.
#
# Usage
# -----
#   ./scripts/verify-quality-signals.sh                  # full run: offline + network
#   ./scripts/verify-quality-signals.sh --offline        # skip the network checks
#   ./scripts/verify-quality-signals.sh --no-gradle      # skip the Gradle build check
#   ./scripts/verify-quality-signals.sh check_badge_block check_licence
#   ./scripts/verify-quality-signals.sh --list
#   ./scripts/verify-quality-signals.sh --help
#
# Exit codes
#   0  every check that ran passed (PENDING and SKIP are not failures)
#   1  at least one check failed
#   2  usage error, or a prerequisite the checks cannot work without is missing
#
set -euo pipefail

# ---------------------------------------------------------------------------
# Pending badges (Requirement 16.8)
#
#   <badge label, exactly as it appears in the README>|<blocking item>|<docs/log.md marker>
#
# The label is the README badge label so a pending badge can be matched back to the badge
# block. The blocking item is what a PENDING line names. The marker is a fixed string that
# must appear in the `docs/log.md` pending entry for that badge, which is how the script
# proves its own list agrees with the log (task 14.2).
# ---------------------------------------------------------------------------
PENDING_BADGES=(
    "GitHub release|the first v* tag and its GitHub release (no checklist item covers it)|vMAJOR.MINOR.PATCH"
    "Maven Central|the first v* tag and the resulting publish being indexed on Maven Central|indexed on Maven Central"
    "codecov|maintainer setup checklist items 1 and 2 (create the Codecov project, then store CODECOV_TOKEN)|items 1 and 2"
    "CodeQL|maintainer setup checklist item 7 (enable code scanning via the advanced workflow)|item 7"
)

# The docs/log.md entry the list above must agree with (task 14.2).
LOG_FILE="docs/log.md"
LOG_PENDING_HEADING="^## Badges that render unresolved on day one"

# ---------------------------------------------------------------------------
# Check registry
#
#   <function name>|<offline|network>|<owning task>|<artefact the check reports against>
#
# Order is the order of execution: the cheap literal and cross-file checks first, the
# Gradle build last of the offline subset, the network checks after that.
#
#   offline  runs in both modes.
#   network  runs in full mode only; SKIPped under --offline.
#
# A check that does more when the network is available calls `network_enabled` rather than
# taking a second registry entry (check_workflows resolves `uses:` refs that way).
# ---------------------------------------------------------------------------
CHECK_REGISTRY=(
    "check_pending_badges|offline|15.1|the pending-badge list vs docs/log.md"
    "check_badge_block|offline|15.2|README.md badge block"
    "check_badge_sources|offline|15.3|README.md badges vs their sources of truth"
    "check_licence|offline|15.4|LICENSE, the streaming-core POM and the badge label"
    "check_catalog|offline|15.5|gradle/libs.versions.toml and the four build files"
    "check_coverage_paths|offline|15.6|workflow-build.yml files: vs coverage-report-paths.txt"
    "check_permissions|offline|15.7|the permissions maps under .github/workflows"
    "check_workflows|offline|15.8|.github/workflows YAML and uses: refs"
    "check_dependabot_isolation|offline|15.9|ci-dependabot-validation.yml and workflow-build.yml"
    "check_config_paths|offline|15.10|.snyk, .coderabbit.yaml, trufflehog-config.yml, codecov.yml, dependabot.yml"
    "check_docs|offline|15.11|README.md, SECURITY.md and CONTRIBUTING.md"
    "check_sam_templates|offline|15.1|deployment/aws/sam/template.yaml and deployment/aws/sam-java/template.yaml"
    "check_gradle_build|offline|15.1|./gradlew build -PexcludeTags=integration and the three coverage reports"
    "check_badge_urls|network|15.12|the badge image and target URLs"
)

# Where per-check implementations live. One file per check, sourced before dispatch.
CHECKS_DIR="scripts/verify/checks"

# The three coverage reports Requirement 16.5 needs on disk. The authoritative list is
# written by the root `verifyCoverageReports` task; these are the fallback when it has not
# run yet, and check_coverage_paths (task 15.6) is what proves the two agree.
COVERAGE_PATHS_FILE="build/reports/coverage-report-paths.txt"
COVERAGE_REPORTS_FALLBACK=(
    "streaming-core/build/reports/kover/report.xml"
    "streaming-s3-example/build/reports/kover/report.xml"
    "streaming-s3-example-java/build/reports/jacoco/test/jacocoTestReport.xml"
)

SAM_TEMPLATES=(
    "deployment/aws/sam/template.yaml"
    "deployment/aws/sam-java/template.yaml"
)

# ---------------------------------------------------------------------------
# Runtime state
# ---------------------------------------------------------------------------
MODE="full"           # full | offline
RUN_GRADLE="${VERIFY_SKIP_GRADLE:+false}"
RUN_GRADLE="${RUN_GRADLE:-true}"
SELECTED_CHECKS=()

PASS_COUNT=0
FAIL_COUNT=0
PENDING_COUNT=0
SKIP_COUNT=0
RESULTS_IN_CHECK=0
CURRENT_CHECK="-"
FAILURES=()
PENDINGS=()
SKIPS=()

# ---------------------------------------------------------------------------
# Output helpers
#
# Every result line names the check and the artefact, in that order, so a failure is
# actionable without reading the script (Requirement 16.7).
# ---------------------------------------------------------------------------
info() { printf '[verify] %s\n' "$*"; }

heading() {
    printf '\n[verify] === %s\n' "$*"
}

# usage_error <message> — a CLI mistake. Exit 2, never a check failure.
usage_error() {
    printf '[verify] ERROR: %s\n' "$*" >&2
    printf "[verify] Run '%s --help' for usage.\n" "${0##*/}" >&2
    exit 2
}

# prerequisite_error <message> — something the checks cannot work without is missing.
# Exit 2 with the reason named, rather than skipping a check and reporting a clean run.
prerequisite_error() {
    printf '[verify] ERROR: %s\n' "$*" >&2
    exit 2
}

result_line() {
    local status=$1 artefact=$2 detail=${3:-}
    if [[ -n "$detail" ]]; then
        printf '  %-7s %-26s %s — %s\n' "$status" "$CURRENT_CHECK" "$artefact" "$detail"
    else
        printf '  %-7s %-26s %s\n' "$status" "$CURRENT_CHECK" "$artefact"
    fi
}

# report_pass <artefact> [detail]
report_pass() {
    (( PASS_COUNT++, RESULTS_IN_CHECK++ )) || true
    result_line "PASS" "$@"
}

# report_fail <artefact> <message> — counted, and repeated in the closing summary.
report_fail() {
    (( FAIL_COUNT++, RESULTS_IN_CHECK++ )) || true
    result_line "FAIL" "$@" >&2
    FAILURES+=("${CURRENT_CHECK}: ${1} — ${2:-failed}")
}

# report_pending <badge label> [detail] — Requirement 16.8. Excluded from the failure
# count, with the blocking item taken from PENDING_BADGES so a PENDING line always says
# what unblocks it. A badge that is not on the list cannot be reported pending; that is a
# failure, otherwise any check could silence itself.
report_pending() {
    local badge=$1 detail=${2:-} blocker
    if ! blocker="$(pending_blocker "$badge")"; then
        report_fail "$badge" "reported PENDING but is not on the script's pending-badge list"
        return 0
    fi
    (( PENDING_COUNT++, RESULTS_IN_CHECK++ )) || true
    result_line "PENDING" "$badge" "${detail:+$detail; }blocked by ${blocker}"
    PENDINGS+=("${CURRENT_CHECK}: ${badge} — blocked by ${blocker}")
}

# report_skip <artefact> <reason> — not run, and the reason is always stated.
report_skip() {
    (( SKIP_COUNT++, RESULTS_IN_CHECK++ )) || true
    result_line "SKIP" "$@"
    SKIPS+=("${CURRENT_CHECK}: ${1} — ${2:-skipped}")
}

# report_note <message> — context, never a result.
report_note() {
    printf '  %-7s %-26s %s\n' "NOTE" "$CURRENT_CHECK" "$*"
}

# ---------------------------------------------------------------------------
# Pending-badge helpers (available to every check)
# ---------------------------------------------------------------------------

# pending_badge_names — one label per line, in declaration order.
pending_badge_names() {
    local entry
    for entry in "${PENDING_BADGES[@]}"; do
        printf '%s\n' "${entry%%|*}"
    done
}

# is_pending_badge <label> — true when the label is on the pending list. This is what
# check_badge_urls (task 15.12) uses to decide PENDING instead of FAIL.
is_pending_badge() {
    pending_badge_names | grep -qxF -- "$1"
}

# pending_blocker <label> — the blocking item text; non-zero when the label is not listed.
pending_blocker() {
    local entry name rest
    for entry in "${PENDING_BADGES[@]}"; do
        name="${entry%%|*}"
        if [[ "$name" == "$1" ]]; then
            rest="${entry#*|}"
            printf '%s\n' "${rest%%|*}"
            return 0
        fi
    done
    return 1
}

# pending_log_marker <label> — the fixed string that must appear in the docs/log.md entry.
pending_log_marker() {
    local entry
    for entry in "${PENDING_BADGES[@]}"; do
        if [[ "${entry%%|*}" == "$1" ]]; then
            printf '%s\n' "${entry##*|}"
            return 0
        fi
    done
    return 1
}

# ---------------------------------------------------------------------------
# Tooling and parsing helpers (available to every check)
# ---------------------------------------------------------------------------

# network_enabled — true in full mode. A check with extra online work asks this.
network_enabled() {
    [[ "$MODE" == "full" ]]
}

# have_tool <name>
have_tool() {
    command -v "$1" >/dev/null 2>&1
}

# require_tool <name> <purpose> — hard failure when a tool the checks depend on is absent.
require_tool() {
    have_tool "$1" || prerequisite_error "required tool '$1' not found on PATH — needed for $2"
}

# require_yaml_parser — YAML parsing goes through Ruby's Psych. PyYAML is not assumed to be
# installed anywhere this runs, so there is no Python fallback; a missing parser is a named
# error rather than a check that quietly does nothing.
require_yaml_parser() {
    have_tool ruby || prerequisite_error \
        "no YAML parser available: 'ruby' not found on PATH. The checks parse YAML with 'ruby -ryaml' (PyYAML is not required, and is not present on every machine this runs on). Install Ruby, or run a subset that needs no YAML parsing."
}

# yaml_parses <file> — exit 0 when the file loads as YAML; prints the parser error to
# stderr otherwise. Psych follows YAML 1.1 on booleans, so a workflow's `on:` key loads as
# the boolean true rather than the string "on" — index it as `doc[true]`.
yaml_parses() {
    require_yaml_parser
    ruby -ryaml -rdate -e '
        begin
            YAML.safe_load(File.read(ARGV[0]), aliases: true, permitted_classes: [Date, Time])
        rescue => e
            warn e.message
            exit 1
        end
    ' "$1"
}

# yaml_eval <file> <ruby expression> — load <file> into `doc` and print the expression.
# An Array prints one element per line, nil prints nothing, anything else prints to_s.
# Expressions come from this repository's own check files, never from user input.
yaml_eval() {
    require_yaml_parser
    ruby -ryaml -rdate -e '
        doc = YAML.safe_load(File.read(ARGV[0]), aliases: true, permitted_classes: [Date, Time])
        result = eval(ARGV[1])
        case result
        when nil   then nil
        when Array then result.each { |item| puts item }
        when Hash  then result.each { |k, v| puts "#{k}=#{v}" }
        else puts result
        end
    ' "$1" "$2"
}

# log_section <heading regex> — print the docs/log.md section under a heading, stopping at
# the next `## ` heading or horizontal rule.
log_section() {
    awk -v pattern="$1" '
        $0 ~ pattern { inside = 1; next }
        inside && (/^## / || /^---[[:space:]]*$/) { exit }
        inside { print }
    ' "$LOG_FILE"
}

# ---------------------------------------------------------------------------
# Checks owned by this task (15.1)
# ---------------------------------------------------------------------------

# check_pending_badges — the pending-badge list is only trustworthy if it agrees with the
# log entry a reader is pointed at (Requirement 16.8, task 14.2). Three assertions:
# set equality with the log's numbered badges, the blocking item present in the log text,
# and each label actually used in the README badge block.
check_pending_badges() {
    if [[ ! -f "$LOG_FILE" ]]; then
        report_fail "$LOG_FILE" "file not found; the pending-badge entry cannot be verified"
        return 0
    fi

    local section
    section="$(log_section "$LOG_PENDING_HEADING")"
    if [[ -z "$section" ]]; then
        report_fail "$LOG_FILE" "no section matching '${LOG_PENDING_HEADING}' — the pending-badge entry from task 14.2 is missing"
        return 0
    fi

    # Numbered, back-quoted badge names inside the entry: `  1. **`codecov`** — ...`
    local logged
    logged="$(printf '%s\n' "$section" \
        | grep -Eo '^[[:space:]]*[0-9]+\. \*\*`[^`]+`\*\*' \
        | sed -E 's/.*`([^`]+)`.*/\1/' || true)"

    local flat
    flat="$(printf '%s\n' "$section" | tr '\n' ' ' | tr -s ' ')"

    local badge missing_in_log=() marker
    while IFS= read -r badge; do
        if ! printf '%s\n' "$logged" | grep -qxF -- "$badge"; then
            missing_in_log+=("$badge")
            continue
        fi
        marker="$(pending_log_marker "$badge")"
        if ! printf '%s\n' "$flat" | grep -qF -- "$marker"; then
            report_fail "$LOG_FILE" "pending badge '${badge}' is listed but its blocking item is not stated there (expected the text '${marker}')"
        fi
    done < <(pending_badge_names)

    local extra_in_log=()
    while IFS= read -r badge; do
        [[ -n "$badge" ]] || continue
        is_pending_badge "$badge" || extra_in_log+=("$badge")
    done <<<"$logged"

    for badge in "${missing_in_log[@]:-}"; do
        [[ -n "$badge" ]] || continue
        report_fail "$LOG_FILE" "badge '${badge}' is on the script's pending list but not in the log entry"
    done
    for badge in "${extra_in_log[@]:-}"; do
        [[ -n "$badge" ]] || continue
        report_fail "$LOG_FILE" "badge '${badge}' is pending in the log entry but not on the script's pending list"
    done

    if (( ${#missing_in_log[@]} == 0 )) && (( ${#extra_in_log[@]} == 0 )); then
        local joined=""
        while IFS= read -r badge; do
            joined+="${joined:+, }${badge}"
        done < <(pending_badge_names)
        report_pass "$LOG_FILE" "pending list agrees with the log entry (${joined})"
    fi

    # The labels must be the README's labels, or a PENDING line names a badge that is not
    # in the badge block.
    if [[ -f README.md ]]; then
        while IFS= read -r badge; do
            if grep -qF -- "[![${badge}]" README.md; then
                report_pass "README.md" "badge label '${badge}' present in the badge block"
            else
                report_fail "README.md" "pending badge '${badge}' is not a badge label in the README"
            fi
        done < <(pending_badge_names)
    else
        report_fail "README.md" "file not found; pending badge labels cannot be matched to the badge block"
    fi
}

# check_sam_templates — Requirement 16.4: both templates must validate, exit code 0 each.
check_sam_templates() {
    if ! have_tool sam; then
        report_fail "AWS SAM CLI" "'sam' not found on PATH, so Requirement 16.4 cannot be checked — install the AWS SAM CLI"
        return 0
    fi

    local template output rc
    for template in "${SAM_TEMPLATES[@]}"; do
        if [[ ! -f "$template" ]]; then
            report_fail "$template" "template not found"
            continue
        fi
        rc=0
        output="$(sam validate --template-file "$template" 2>&1)" || rc=$?
        if (( rc == 0 )); then
            report_pass "$template" "sam validate exited 0"
        else
            report_fail "$template" "sam validate exited ${rc}: $(printf '%s' "$output" | tail -n 3 | tr '\n' ' ')"
        fi
    done
}

# check_gradle_build — Requirement 16.5: the build exits 0 and all three coverage reports
# exist, are non-empty, and sit at the paths the Codecov upload names. The paths come from
# `build/reports/coverage-report-paths.txt` (written by the root `verifyCoverageReports`
# task); comparing that file against the workflow's `files:` input is task 15.6.
#
# Slowest check in the script by a wide margin, hence --no-gradle / VERIFY_SKIP_GRADLE=1
# for a quick re-run of the literal and cross-file checks.
check_gradle_build() {
    if [[ "$RUN_GRADLE" != "true" ]]; then
        report_skip "./gradlew build -PexcludeTags=integration" "disabled by --no-gradle / VERIFY_SKIP_GRADLE; Requirement 16.5 is unproven in this run"
        return 0
    fi
    if [[ ! -x ./gradlew ]]; then
        report_fail "./gradlew" "Gradle wrapper not found or not executable"
        return 0
    fi

    local log rc=0
    log="$(mktemp -t verify-quality-signals-gradle.XXXXXX)"
    if ./gradlew build -PexcludeTags=integration >"$log" 2>&1; then
        report_pass "./gradlew build -PexcludeTags=integration" "exited 0"
    else
        rc=$?
        report_fail "./gradlew build -PexcludeTags=integration" "exited ${rc}: $(grep -E '^(FAILURE|\* What went wrong:|> )' "$log" | head -n 3 | tr '\n' ' ')"
        report_note "full Gradle output: $log"
        return 0
    fi

    # Produce (and self-check) the three reports plus the authoritative path list.
    # --continue keeps the reports on disk when a coverage gate fails.
    if ./gradlew verifyCoverageReports -PexcludeTags=integration --continue >>"$log" 2>&1; then
        report_pass "verifyCoverageReports" "exited 0"
    else
        rc=$?
        report_fail "verifyCoverageReports" "exited ${rc}: $(grep -E '^(FAILURE|\* What went wrong:|> )' "$log" | head -n 3 | tr '\n' ' ')"
        report_note "full Gradle output: $log"
    fi
    rm -f "$log"

    local -a reports=()
    if [[ -s "$COVERAGE_PATHS_FILE" ]]; then
        while IFS= read -r line; do
            [[ -n "$line" ]] && reports+=("$line")
        done <"$COVERAGE_PATHS_FILE"
        report_note "coverage paths read from $COVERAGE_PATHS_FILE"
    else
        reports=("${COVERAGE_REPORTS_FALLBACK[@]}")
        report_fail "$COVERAGE_PATHS_FILE" "not written by verifyCoverageReports; falling back to the three known report paths"
    fi

    local report
    for report in "${reports[@]}"; do
        if [[ -s "$report" ]]; then
            report_pass "$report" "coverage report present, $(wc -c <"$report" | tr -d ' ') bytes"
        else
            report_fail "$report" "coverage report missing or empty"
        fi
    done
}

# ---------------------------------------------------------------------------
# Dispatch
# ---------------------------------------------------------------------------

registry_field() {
    local entry=$1 index=$2
    printf '%s\n' "$entry" | cut -d'|' -f"$index"
}

registry_entry_for() {
    local name=$1 entry
    for entry in "${CHECK_REGISTRY[@]}"; do
        if [[ "${entry%%|*}" == "$name" ]]; then
            printf '%s\n' "$entry"
            return 0
        fi
    done
    return 1
}

# source_check_implementations — one file per check under scripts/verify/checks/. A
# registry entry with no matching function reports SKIP, so the script is runnable while
# tasks 15.2 through 15.12 land one file at a time.
source_check_implementations() {
    [[ -d "$CHECKS_DIR" ]] || return 0
    shopt -s nullglob
    local file
    for file in "$CHECKS_DIR"/*.sh; do
        # shellcheck source=/dev/null
        source "$file"
    done
    shopt -u nullglob
}

# run_check <registry entry> [explicit]
run_check() {
    local entry=$1 explicit=${2:-false}
    local name mode owner artefact
    name="$(registry_field "$entry" 1)"
    mode="$(registry_field "$entry" 2)"
    owner="$(registry_field "$entry" 3)"
    artefact="$(registry_field "$entry" 4)"

    CURRENT_CHECK="$name"
    RESULTS_IN_CHECK=0

    if [[ "$mode" == "network" ]] && ! network_enabled && [[ "$explicit" != "true" ]]; then
        report_skip "$artefact" "network check, skipped in --offline mode"
        return 0
    fi

    if ! declare -F "$name" >/dev/null; then
        report_skip "$artefact" "not implemented yet — task ${owner} adds ${CHECKS_DIR}/${name}.sh"
        return 0
    fi

    local before_failures=$FAIL_COUNT rc=0
    if "$name"; then
        rc=0
    else
        rc=$?
    fi

    if (( rc != 0 )) && (( FAIL_COUNT == before_failures )); then
        report_fail "$artefact" "check exited with status ${rc} without reporting a failure"
    fi
    if (( RESULTS_IN_CHECK == 0 )); then
        report_fail "$artefact" "check reported no result; a check that asserts nothing cannot pass"
    fi
}

print_summary() {
    heading "Summary (${MODE} mode)"
    printf '  %d passed, %d failed, %d pending, %d skipped\n' \
        "$PASS_COUNT" "$FAIL_COUNT" "$PENDING_COUNT" "$SKIP_COUNT"

    local item
    if (( ${#PENDINGS[@]} > 0 )); then
        printf '\n  Pending (excluded from the failure count, Requirement 16.8):\n'
        for item in "${PENDINGS[@]}"; do
            printf '    - %s\n' "$item"
        done
    fi
    if (( ${#SKIPS[@]} > 0 )); then
        printf '\n  Skipped:\n'
        for item in "${SKIPS[@]}"; do
            printf '    - %s\n' "$item"
        done
    fi
    if (( ${#FAILURES[@]} > 0 )); then
        printf '\n  Failures:\n' >&2
        for item in "${FAILURES[@]}"; do
            printf '    - %s\n' "$item" >&2
        done
        printf '\n[verify] FAILED: %d check result(s) need fixing before merge.\n' "$FAIL_COUNT" >&2
    else
        printf '\n[verify] OK: no failures.\n'
    fi
}

print_usage() {
    cat <<'USAGE'
verify-quality-signals.sh — local pre-merge verification of the repository's quality
signals: badges, workflow permissions, the version catalog, coverage reports, the
third-party tool configs, and the documentation that enumerates them.

Not a CI job by design: Requirement 3.3 forbids a second job in ci-main-build.yml, and
Requirement 5.5 keeps third-party availability (shields.io, codecov.io, the GitHub API)
out of the merge gate. The pull-request template asks for this script's output instead.

Usage:
  verify-quality-signals.sh [options] [check_name ...]

Options:
  --offline        Run the offline subset only (no badge URL or uses:-ref requests).
  --no-gradle      Skip check_gradle_build (the slow one). Requirement 16.5 is then
                   reported as unproven for that run. Same as VERIFY_SKIP_GRADLE=1.
  --list           List the registered checks with their mode and owning task.
  -h, --help       Show this help.

Arguments:
  check_name ...   Run only the named checks, in the order given. A named network check
                   runs even under --offline, because naming it is an explicit request.

Exit codes:
  0  every check that ran passed (PENDING and SKIP are not failures)
  1  at least one check failed
  2  usage error, or a missing prerequisite (for example no YAML parser)
USAGE
}

print_list() {
    printf '%-28s %-8s %-6s %s\n' "CHECK" "MODE" "TASK" "ARTEFACT"
    local entry name mode owner artefact state
    for entry in "${CHECK_REGISTRY[@]}"; do
        name="$(registry_field "$entry" 1)"
        mode="$(registry_field "$entry" 2)"
        owner="$(registry_field "$entry" 3)"
        artefact="$(registry_field "$entry" 4)"
        if declare -F "$name" >/dev/null; then state=""; else state=" [stub]"; fi
        printf '%-28s %-8s %-6s %s%s\n' "$name" "$mode" "$owner" "$artefact" "$state"
    done
}

parse_args() {
    while (( $# > 0 )); do
        case "$1" in
            --offline) MODE="offline" ;;
            --full) MODE="full" ;;
            --no-gradle) RUN_GRADLE="false" ;;
            --list) LIST_ONLY="true" ;;
            -h|--help) print_usage; exit 0 ;;
            -*) usage_error "unknown option: $1" ;;
            *)
                registry_entry_for "$1" >/dev/null \
                    || usage_error "unknown check: $1 (see --list)"
                SELECTED_CHECKS+=("$1")
                ;;
        esac
        shift
    done
}

main() {
    if (( BASH_VERSINFO[0] < 4 )); then
        prerequisite_error "bash 4 or newer is required (globstar, associative arrays); this is bash ${BASH_VERSION}"
    fi

    local repo_root
    repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
    cd "$repo_root"

    LIST_ONLY="false"
    parse_args "$@"

    source_check_implementations

    if [[ "$LIST_ONLY" == "true" ]]; then
        print_list
        exit 0
    fi

    require_tool git "the repository state checks"

    info "repository: $repo_root"
    info "mode: ${MODE} ($([[ "$MODE" == full ]] && echo 'offline subset + network checks' || echo 'offline subset only'))"
    [[ "$RUN_GRADLE" == "true" ]] || info "Gradle build check disabled (--no-gradle / VERIFY_SKIP_GRADLE)"

    local entry name
    if (( ${#SELECTED_CHECKS[@]} > 0 )); then
        heading "Selected checks"
        for name in "${SELECTED_CHECKS[@]}"; do
            entry="$(registry_entry_for "$name")"
            run_check "$entry" "true"
        done
    else
        heading "Checks"
        for entry in "${CHECK_REGISTRY[@]}"; do
            run_check "$entry"
        done
    fi

    CURRENT_CHECK="-"
    print_summary
    (( FAIL_COUNT == 0 ))
}

main "$@"
