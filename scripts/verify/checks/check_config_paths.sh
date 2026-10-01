# shellcheck shell=bash
#
# check_config_paths — every path pattern in the third-party tool configs matches something.
# Task 15.10.
#
# A path pattern that matches nothing does not fail: the tool reading it simply scopes
# itself to no files and reports a clean run. That is the failure mode this check exists
# for — a module rename or a moved template leaves the pattern syntactically fine and
# semantically empty, and nothing else in the repository notices.
#
# Five configs, each read from the file rather than assumed:
#
#   .snyk                  iac.include (three templates, must exist as files) and
#                          exclude.global patterns
#   .coderabbit.yaml       reviews.path_filters — ONE ordered list where a bare pattern
#                          includes and a `!`-prefixed pattern excludes; the `!` is
#                          stripped before globbing, since both forms name paths the same
#                          way. Plus reviews.path_instructions paths, one of which is a
#                          brace pattern.
#   trufflehog-config.yml  exclude_paths
#   codecov.yml            ignore
#   .github/dependabot.yml every `directories` entry plus the github-actions `directory`,
#                          each of which must be a real directory holding the build file
#                          Dependabot expects to find there.
#
# Two details worth stating:
#
#   * `**/bin/**` and `**/build/**` match generated, gitignored trees (Eclipse/JDT writes
#     `bin/main` and `bin/test` under all three modules). Those matches are the point of
#     the patterns, so a gitignored match counts as a match here — the check globs the
#     working tree, not the git index.
#   * Brace expansion is done by hand. Bash brace-expands literal text only, never the
#     result of a variable expansion, so `{a,b}/**` read from YAML would otherwise glob
#     as a literal and match nothing.

check_config_paths() {
    # Local helpers, defined inside the function and unset at the end: every check file is
    # sourced into one shell, so a file-scope helper would leak into sibling checks.

    # _cfg_brace_expand <pattern> — one line per alternative. Recurses so nested or
    # repeated groups expand too. A pattern without braces prints unchanged.
    _cfg_brace_expand() {
        local pattern=$1
        if [[ "$pattern" =~ ^([^{}]*)\{([^{}]*)\}(.*)$ ]]; then
            local prefix="${BASH_REMATCH[1]}" body="${BASH_REMATCH[2]}" suffix="${BASH_REMATCH[3]}"
            local alternative
            local IFS=,
            for alternative in $body; do
                _cfg_brace_expand "${prefix}${alternative}${suffix}"
            done
        else
            printf '%s\n' "$pattern"
        fi
    }

    # _cfg_unsafe <pattern> — true when the pattern holds a character that would mean
    # something other than "path" during an unquoted expansion. Configs are trusted, but
    # a pattern that cannot be globbed safely is reported rather than run.
    _cfg_unsafe() {
        case $1 in
            *[[:space:]]*|*'$'*|*'`'*|*';'*|*'&'*|*'|'*|*'<'*|*'>'*|*'('*|*')'*|*"'"*|*'"'*|*\\*) return 0 ;;
            *) return 1 ;;
        esac
    }

    # _cfg_match_info <pattern> — "<match count>\t<first match>". Needs globstar (for `**`)
    # and nullglob (so a pattern that matches nothing yields no words instead of itself);
    # _cfg_body sets and restores both.
    _cfg_match_info() {
        local pattern=$1 expanded first="" total=0
        local -a hits
        while IFS= read -r expanded; do
            [[ -n "$expanded" ]] || continue
            # Unquoted on purpose: this is the pathname expansion being tested.
            # shellcheck disable=SC2206
            hits=( $expanded )
            total=$(( total + ${#hits[@]} ))
            if [[ -z "$first" ]] && (( ${#hits[@]} > 0 )); then
                first="${hits[0]}"
            fi
        done < <(_cfg_brace_expand "$pattern")
        printf '%s\t%s\n' "$total" "$first"
    }

    # _cfg_assert_globs <file> <label> <pattern…> — every pattern must match ≥ 1 path.
    _cfg_assert_globs() {
        local file=$1 label=$2
        shift 2
        local total=$# raw pattern info count first misses=0 sample=""
        for raw in "$@"; do
            pattern="${raw#!}"   # a `!`-prefixed exclusion names paths the same way
            if _cfg_unsafe "$pattern"; then
                report_fail "$file" "${label} pattern '${raw}' contains a character that cannot be expanded as a path pattern, so it was not checked"
                misses=$(( misses + 1 ))
                continue
            fi
            info="$(_cfg_match_info "$pattern")"
            count="${info%%$'\t'*}"
            first="${info#*$'\t'}"
            if (( count == 0 )); then
                report_fail "$file" "${label} pattern '${raw}' matched nothing in the working tree — the pattern is stale, and a pattern that matches nothing scopes the tool to no files instead of failing"
                misses=$(( misses + 1 ))
            else
                sample+="${sample:+, }${raw}→${count}"
            fi
        done
        if (( misses == 0 )); then
            report_pass "$file" "${label}: all ${total} pattern(s) match at least one working-tree path (${sample})"
        fi
        return 0
    }

    # _cfg_assert_listed <file> <label> <configured…> -- <expected…> — every expected entry
    # is still present in the configured list, so a removal is caught as well as a typo.
    _cfg_assert_listed() {
        local file=$1 label=$2
        shift 2
        local -a configured=() expected=()
        local item seen_separator=false
        for item in "$@"; do
            if [[ "$item" == "--" ]]; then
                seen_separator=true
                continue
            fi
            if [[ "$seen_separator" == true ]]; then expected+=("$item"); else configured+=("$item"); fi
        done

        local missing=0
        for item in "${expected[@]}"; do
            if ! printf '%s\n' "${configured[@]}" | grep -qxF -- "$item"; then
                report_fail "$file" "${label} no longer lists '${item}'; the tool is then silently not applied to it"
                missing=$(( missing + 1 ))
            fi
        done
        if (( missing == 0 )); then
            report_pass "$file" "${label} lists all ${#expected[@]} expected entr(y/ies)"
        fi
        return 0
    }

    _cfg_body() {
        local snyk=".snyk"
        local coderabbit=".coderabbit.yaml"
        local trufflehog="trufflehog-config.yml"
        local codecov="codecov.yml"
        local dependabot=".github/dependabot.yml"

        local -a configs=("$snyk" "$coderabbit" "$trufflehog" "$codecov" "$dependabot")

        # --- 6. every config file is present and parses as YAML -------------------------
        local file
        local -A parsed=()
        for file in "${configs[@]}"; do
            if [[ ! -f "$file" ]]; then
                report_fail "$file" "file not found; its path patterns cannot be checked"
                parsed["$file"]=false
                continue
            fi
            if yaml_parses "$file"; then
                report_pass "$file" "parses as YAML"
                parsed["$file"]=true
            else
                report_fail "$file" "does not parse as YAML, so its path patterns cannot be read"
                parsed["$file"]=false
            fi
        done

        # Glob options, saved and restored so nothing leaks into a sibling check.
        local globstar_was=off nullglob_was=off
        shopt -q globstar && globstar_was=on
        shopt -q nullglob && nullglob_was=on
        shopt -s globstar nullglob

        local -a values=()

        # --- 1. .snyk -------------------------------------------------------------------
        if [[ "${parsed[$snyk]}" == true ]]; then
            mapfile -t values < <(yaml_eval "$snyk" 'doc["iac"].to_h["include"].to_a.map(&:to_s)')
            if (( ${#values[@]} == 0 )); then
                report_fail "$snyk" "iac.include is empty or absent; Snyk IaC then scans no template at all"
            else
                local template present=0 absent=0
                for template in "${values[@]}"; do
                    if [[ -f "$template" ]]; then
                        present=$(( present + 1 ))
                    else
                        report_fail "$snyk" "iac.include names '${template}', which is not an existing file — Snyk IaC skips a template it cannot find rather than failing"
                        absent=$(( absent + 1 ))
                    fi
                done
                (( absent > 0 )) || report_pass "$snyk" "iac.include: all ${present} template(s) exist ($(printf '%s ' "${values[@]}" | sed -E 's/ $//'))"
                _cfg_assert_listed "$snyk" "iac.include" "${values[@]}" -- \
                    "deployment/aws/sam/template.yaml" \
                    "deployment/aws/sam-java/template.yaml" \
                    "deployment/aws/oidc/github-oidc-role.yaml"
            fi

            mapfile -t values < <(yaml_eval "$snyk" 'doc["exclude"].to_h["global"].to_a.map(&:to_s)')
            if (( ${#values[@]} == 0 )); then
                report_fail "$snyk" "exclude.global is empty or absent; the generated trees are then scanned"
            else
                _cfg_assert_globs "$snyk" "exclude.global" "${values[@]}"
            fi
        fi

        # --- 2. .coderabbit.yaml --------------------------------------------------------
        if [[ "${parsed[$coderabbit]}" == true ]]; then
            mapfile -t values < <(yaml_eval "$coderabbit" 'doc["reviews"].to_h["path_filters"].to_a.map(&:to_s)')
            if (( ${#values[@]} == 0 )); then
                report_fail "$coderabbit" "reviews.path_filters is empty or absent; the review scope is then undefined"
            else
                _cfg_assert_globs "$coderabbit" "reviews.path_filters (leading '!' stripped)" "${values[@]}"
            fi

            mapfile -t values < <(yaml_eval "$coderabbit" \
                'doc["reviews"].to_h["path_instructions"].to_a.map { |e| e.is_a?(Hash) ? e["path"].to_s : e.to_s }')
            if (( ${#values[@]} == 0 )); then
                report_fail "$coderabbit" "reviews.path_instructions is empty or absent; the per-area review instructions are gone"
            else
                if (( ${#values[@]} == 3 )); then
                    report_pass "$coderabbit" "reviews.path_instructions has the expected 3 entries"
                else
                    report_fail "$coderabbit" "reviews.path_instructions has ${#values[@]} entr(y/ies), expected 3 (streaming-core, both example modules, src/test)"
                fi
                _cfg_assert_globs "$coderabbit" "reviews.path_instructions paths" "${values[@]}"
            fi
        fi

        # --- 3. trufflehog-config.yml ---------------------------------------------------
        if [[ "${parsed[$trufflehog]}" == true ]]; then
            mapfile -t values < <(yaml_eval "$trufflehog" 'doc["exclude_paths"].to_a.map(&:to_s)')
            if (( ${#values[@]} == 0 )); then
                report_fail "$trufflehog" "exclude_paths is empty or absent; a working-tree scan then reports every generated copy of a file twice"
            else
                _cfg_assert_globs "$trufflehog" "exclude_paths" "${values[@]}"
            fi
        fi

        # --- 4. codecov.yml -------------------------------------------------------------
        if [[ "${parsed[$codecov]}" == true ]]; then
            mapfile -t values < <(yaml_eval "$codecov" 'doc["ignore"].to_a.map(&:to_s)')
            if (( ${#values[@]} == 0 )); then
                report_fail "$codecov" "ignore is empty or absent; test and generated sources then count towards coverage"
            else
                _cfg_assert_globs "$codecov" "ignore" "${values[@]}"
            fi
        fi

        # --- 5. .github/dependabot.yml --------------------------------------------------
        # Dependabot's Gradle parser does not recurse into subprojects, so a directory that
        # does not exist (or holds no build file) means that module is simply never updated.
        if [[ "${parsed[$dependabot]}" == true ]]; then
            local ecosystem expr entry path missing
            for ecosystem in gradle github-actions; do
                expr='doc["updates"].to_a.select { |u| u.is_a?(Hash) && u["package-ecosystem"].to_s == "'"$ecosystem"'" }'
                expr+='.flat_map { |u| (u["directories"] || [u["directory"]]).to_a.compact.map(&:to_s) }'
                mapfile -t values < <(yaml_eval "$dependabot" "$expr")
                if (( ${#values[@]} == 0 )); then
                    report_fail "$dependabot" "the ${ecosystem} update declares no directory/directories entry, so nothing is scanned for that ecosystem"
                    continue
                fi

                missing=0
                for entry in "${values[@]}"; do
                    path="${entry#/}"
                    path="${path:-.}"
                    if [[ ! -d "$path" ]]; then
                        report_fail "$dependabot" "${ecosystem} directory '${entry}' is not an existing directory — Dependabot finds no manifest there and updates nothing, silently"
                        missing=$(( missing + 1 ))
                    fi
                done
                (( missing == 0 )) || continue

                if [[ "$ecosystem" == gradle ]]; then
                    for entry in "${values[@]}"; do
                        path="${entry#/}"
                        path="${path:-.}"
                        if [[ ! -f "${path}/build.gradle.kts" ]]; then
                            report_fail "$dependabot" "gradle directory '${entry}' holds no build.gradle.kts, so Dependabot's Gradle parser finds no manifest to update there"
                            missing=$(( missing + 1 ))
                        fi
                    done
                    if [[ ! -f "gradle/libs.versions.toml" ]]; then
                        report_fail "$dependabot" "gradle directory '/' is meant to cover gradle/libs.versions.toml as well, but that file does not exist"
                        missing=$(( missing + 1 ))
                    fi
                    (( missing > 0 )) || report_pass "$dependabot" "gradle directories: all ${#values[@]} resolve to a directory with a build.gradle.kts, and '/' also covers gradle/libs.versions.toml ($(printf '%s ' "${values[@]}" | sed -E 's/ $//'))"
                    _cfg_assert_listed "$dependabot" "gradle directories" "${values[@]}" -- \
                        "/" "/streaming-core" "/streaming-s3-example" "/streaming-s3-example-java"
                else
                    report_pass "$dependabot" "github-actions directory: all ${#values[@]} resolve to an existing directory ($(printf '%s ' "${values[@]}" | sed -E 's/ $//'))"
                    _cfg_assert_listed "$dependabot" "github-actions directory" "${values[@]}" -- "/"
                fi
            done
        fi

        [[ "$globstar_was" == on ]] || shopt -u globstar
        [[ "$nullglob_was" == on ]] || shopt -u nullglob

        report_note "the '**/bin/**' and '**/build/**' patterns match generated, gitignored trees; that is what they are for, so a gitignored match counts here — this check globs the working tree, not the git index."
        return 0
    }

    _cfg_body
    unset -f _cfg_body _cfg_assert_listed _cfg_assert_globs _cfg_match_info _cfg_unsafe _cfg_brace_expand
    return 0
}
