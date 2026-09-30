# shellcheck shell=bash
#
# check_licence — Property 3: the licence identifier is one string in three places.
# Task 15.4; Requirements 12.3, 12.4, 12.8.
#
# Three declarations of the licence have to agree:
#
#   1. LICENSE                          first non-blank line                  e.g. "MIT License"
#   2. streaming-core/build.gradle.kts  POM licenses { license { name = … } }  e.g. "MIT"
#   3. README.md                        licence badge label                   e.g. "License: MIT"
#
# All three values are read at runtime; nothing here hardcodes "MIT". A future licence
# change that updates only one of the three therefore fails this check instead of slipping
# through it.
#
# Normalisation, applied identically to all three so the equality is auditable rather than
# magic:
#
#   a. trim, then collapse runs of whitespace to a single space
#   b. drop a leading "The "                        "The MIT License"   → "MIT License"
#   c. drop a leading "License:" / "Licence:"       "License: MIT"      → "MIT"
#   d. drop a trailing parenthetical                "MIT License (MIT)" → "MIT License"
#   e. drop a trailing "License" / "Licence" word   "MIT License"       → "MIT"
#
# What survives is the bare identifier, compared case-sensitively: Requirement 12.8 asks
# for identical strings, so "mit" against "MIT" is a disagreement, not a match.
#
# The normalisation covers the "<identifier> License" family. A licence whose file header
# spreads the identifier over several lines — Apache-2.0 writes "Apache License" with
# "Version 2.0" further down — normalises to "Apache" and will not match a POM name of
# "Apache-2.0". That is deliberate: changing the licence is precisely the moment a human
# should re-read this comment, not the moment to trust a silent pass.
#
# Also asserted here, per task 15.4: LICENSE is unmodified. Requirement 12.4 makes it the
# authoritative source, so the POM and the badge move to match it, never the other way.

check_licence() {
    local licence_file="LICENSE"
    local pom_file="streaming-core/build.gradle.kts"
    local readme_file="README.md"

    # The normalisation above, as one sed program shared by all three values.
    local normalise='
        s/^[[:space:]]+//
        s/[[:space:]]+$//
        s/[[:space:]]+/ /g
        s/^[Tt]he //
        s/^[Ll]icen[cs]e[[:space:]]*:[[:space:]]*//
        s/[[:space:]]*\([^)]*\)$//
        s/[[:space:]]+[Ll]icen[cs]e$//
        s/[[:space:]]+$//
    '

    local missing=0 file
    for file in "$licence_file" "$pom_file" "$readme_file"; do
        if [[ ! -f "$file" ]]; then
            report_fail "$file" "file not found; the three-way licence equality cannot be checked"
            missing=1
        fi
    done
    (( missing == 0 )) || return 0

    # --- 1. LICENSE: first non-blank line -------------------------------------------------
    local licence_raw licence_id
    licence_raw="$(grep -m1 -E '[^[:space:]]' "$licence_file" || true)"
    if [[ -z "$licence_raw" ]]; then
        report_fail "$licence_file" "no non-blank line found; the authoritative licence identifier is unreadable"
        return 0
    fi
    licence_id="$(printf '%s\n' "$licence_raw" | sed -E "$normalise")"

    # --- 2. streaming-core POM: licenses { license { name = "…" } } -----------------------
    # Scoped to the licences block by brace depth, because the POM also declares a top-level
    # `name` (the artifact name) and a developer `name`. Whole-line comments are ignored.
    local pom_names pom_raw pom_id
    pom_names="$(awk '
        /^[[:space:]]*\/\// { next }
        !inside {
            if ($0 ~ /licenses[[:space:]]*\{/) { inside = 1; depth = 1 }
            next
        }
        {
            if ($0 ~ /name[[:space:]]*=/ && match($0, /"[^"]*"/)) {
                print substr($0, RSTART + 1, RLENGTH - 2)
                next
            }
            depth += gsub(/\{/, "{") - gsub(/\}/, "}")
            if (depth <= 0) { exit }
        }
    ' "$pom_file" || true)"

    if [[ -z "$pom_names" ]]; then
        report_fail "$pom_file" "no licences { licence { name = \"…\" } } declaration found in the publication POM (Requirement 12.4 requires one naming the ${licence_file} licence '${licence_id}')"
        return 0
    fi
    if (( $(printf '%s\n' "$pom_names" | wc -l | tr -d ' ') > 1 )); then
        report_fail "$pom_file" "the POM licences block declares more than one licence name ($(printf '%s\n' "$pom_names" | tr '\n' ' ')); Requirement 12.8 expects a single identifier"
        return 0
    fi
    pom_raw="$pom_names"
    pom_id="$(printf '%s\n' "$pom_raw" | sed -E "$normalise")"

    # --- 3. README badge label ------------------------------------------------------------
    # The badge block writes one badge per line as [![label](image)](target); the licence
    # badge is the one whose label starts with "Licen[cs]e".
    local badge_lines badge_raw badge_id
    badge_lines="$(grep -E '^[[:space:]]*\[!\[[[:space:]]*[Ll]icen[cs]e[^]]*\]\(' "$readme_file" || true)"
    if [[ -z "$badge_lines" ]]; then
        report_fail "$readme_file" "no licence badge line found (expected [![Licen[cs]e…](image)](target) naming '${licence_id}')"
        return 0
    fi
    if (( $(printf '%s\n' "$badge_lines" | wc -l | tr -d ' ') > 1 )); then
        report_fail "$readme_file" "more than one licence badge line found; the badge block must declare exactly one so its label is unambiguous"
        return 0
    fi
    badge_raw="$(printf '%s\n' "$badge_lines" | sed -E 's/^[[:space:]]*\[!\[([^]]*)\].*/\1/')"
    badge_id="$(printf '%s\n' "$badge_raw" | sed -E "$normalise")"

    # --- The three-way equality -----------------------------------------------------------
    local trio="${licence_file}='${licence_raw}' → '${licence_id}', ${pom_file} POM='${pom_raw}' → '${pom_id}', ${readme_file} badge='${badge_raw}' → '${badge_id}'"

    if [[ "$licence_id" == "$pom_id" && "$pom_id" == "$badge_id" ]]; then
        report_pass "the three licence declarations" "all three normalise to '${licence_id}' (${trio})"
    elif [[ "$licence_id" == "$pom_id" ]]; then
        report_fail "$readme_file" "the badge label disagrees: it says '${badge_id}' while ${licence_file} and the ${pom_file} POM both say '${licence_id}'. Requirement 12.8 wants one identifier — ${trio}"
    elif [[ "$licence_id" == "$badge_id" ]]; then
        report_fail "$pom_file" "the publication POM licence name disagrees: it says '${pom_id}' while ${licence_file} and the ${readme_file} badge both say '${licence_id}'. Requirement 12.4 makes ${licence_file} authoritative, so change the POM — ${trio}"
    elif [[ "$pom_id" == "$badge_id" ]]; then
        report_fail "$licence_file" "${licence_file} disagrees: it says '${licence_id}' while the POM and the badge both say '${pom_id}'. ${licence_file} is the authoritative source (Requirement 12.4), so the POM and the badge are the two to correct — ${trio}"
    else
        report_fail "the three licence declarations" "all three disagree — ${trio}. Requirement 12.4 makes ${licence_file} authoritative: align the POM and the badge to '${licence_id}'"
    fi

    # --- LICENSE itself is unchanged (Requirement 12.4) -----------------------------------
    if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
        report_skip "$licence_file" "not inside a git work tree, so 'git diff --exit-code ${licence_file}' cannot run"
    elif ! git rev-parse --verify --quiet HEAD >/dev/null 2>&1; then
        report_skip "$licence_file" "no commits yet, so there is no HEAD to diff ${licence_file} against"
    elif git diff --exit-code HEAD -- "$licence_file" >/dev/null 2>&1; then
        report_pass "$licence_file" "unmodified relative to HEAD (staged and unstaged), so it stays the authoritative licence source"
    else
        report_fail "$licence_file" "modified relative to HEAD ($(git diff --shortstat HEAD -- "$licence_file" | sed -E 's/^[[:space:]]+//')); Requirement 12.4 leaves ${licence_file} unchanged and moves the POM and the badge instead"
    fi
}
