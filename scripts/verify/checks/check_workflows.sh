# shellcheck shell=bash
#
# check_workflows — the workflow files under .github/workflows are structurally sound.
# Task 15.8.
#
# This check is about workflow *structure*, not policy. Four offline assertions plus one
# online sub-check:
#
#   1. Every file under .github/workflows/ parses as YAML. A workflow that does not parse
#      never runs, and GitHub reports it far away from the commit that broke it.
#   2. Every local `uses: ./.github/workflows/<file>` names a file that exists. A dangling
#      local call is only discovered when the caller is triggered, which for
#      cd-deploy-on-demand.yml can be weeks later.
#   3. Every workflow declares a trigger and at least one job — the two things without
#      which the file is inert.
#   4. Every `timeout-minutes:` present is a positive integer. Jobs *without* one are
#      reported as a NOTE, never a failure: the key is unsupported on a job that calls a
#      reusable workflow (`uses:` at job level), so the callers in ci-*/cd-* physically
#      cannot carry one, and a plain job without one falls back to the 6-hour default.
#   5. With the network available, every external `uses:` ref (actions/checkout@v4,
#      gradle/actions/setup-gradle@v4, codecov/codecov-action@v5,
#      github/codeql-action/init@v3, …) is resolved against the GitHub API, so a renamed
#      action or a major tag that was never published is caught here rather than in a CI
#      run. Gated on `network_enabled`, so --offline skips it; a missing or unauthenticated
#      `gh` is a SKIP with the reason stated, not a failure, because third-party
#      availability must not decide whether this script passes (Requirement 5.5). Every
#      network call is bounded by a timeout so the check cannot hang.
#
# Deliberately NOT re-asserted here, to keep one owner per fact:
#   * the `permissions:` maps — check_permissions, task 15.7
#   * the Dependabot credential isolation — check_dependabot_isolation, task 15.9
#
# YAML is read through the shared Psych helpers. Psych follows YAML 1.1, so a workflow's
# `on:` key loads as the boolean true rather than the string "on" — hence `doc[true]`.

check_workflows() {
    # Local helpers live inside the function and are unset at the end: every check file is
    # sourced into one shell, so a file-scope helper would leak into the other checks.

    # _wf_squash <text…> — collapse whitespace runs so a parser error or a YAML-wrapped
    # value fits on one result line.
    _wf_squash() {
        printf '%s' "$*" | tr -s '[:space:]' ' ' | sed -E 's/^ //; s/ $//'
    }

    # _wf_have_timeout — is there any way to bound a network call? macOS ships no
    # coreutils `timeout`, so perl's alarm is the fallback.
    _wf_have_timeout() {
        have_tool timeout || have_tool gtimeout || have_tool perl
    }

    # _wf_with_timeout <seconds> <command…> — run the command, killing it after <seconds>.
    _wf_with_timeout() {
        local secs=$1
        shift
        if have_tool timeout; then
            timeout "$secs" "$@"
        elif have_tool gtimeout; then
            gtimeout "$secs" "$@"
        else
            perl -e 'alarm shift; exec @ARGV' "$secs" "$@"
        fi
    }

    _wf_body() {
        local wf_dir=".github/workflows"

        if [[ ! -d "$wf_dir" ]]; then
            report_fail "$wf_dir" "directory not found; no workflow can be checked"
            return 0
        fi

        local -a files=()
        local file
        while IFS= read -r file; do
            [[ -n "$file" ]] && files+=("$file")
        done < <(find "$wf_dir" -maxdepth 1 -type f | sort)

        if (( ${#files[@]} == 0 )); then
            report_fail "$wf_dir" "contains no files; the repository's workflow set cannot be empty"
            return 0
        fi

        # --- 1. every file parses as YAML -------------------------------------------------
        local -a parsed=()
        local err
        for file in "${files[@]}"; do
            if err="$(yaml_parses "$file" 2>&1)"; then
                parsed+=("$file")
            else
                report_fail "$file" "does not parse as YAML: $(_wf_squash "$err")"
            fi
        done

        if (( ${#parsed[@]} == ${#files[@]} )); then
            report_pass "$wf_dir" "all ${#files[@]} workflow file(s) parse as YAML"
        fi
        if (( ${#parsed[@]} == 0 )); then
            report_fail "$wf_dir" "no workflow file parses, so nothing further can be checked"
            return 0
        fi

        # --- collect every uses:, job-level and step-level, with its location -------------
        # One tab-separated line per reference: <file>\t<location>\t<uses value>.
        local uses_tsv
        uses_tsv="$(mktemp -t verify-check-workflows.XXXXXX)"

        local expr_uses='doc["jobs"].to_h.flat_map { |n, j| h = (j.is_a?(Hash) ? j : {}); (h["uses"] ? ["job \"" + n.to_s + "\"\t" + h["uses"].to_s] : []) + (h["steps"].is_a?(Array) ? h["steps"] : []).each_with_index.map { |s, i| (s.is_a?(Hash) && s["uses"]) ? "job \"" + n.to_s + "\" step " + (i + 1).to_s + "\t" + s["uses"].to_s : nil }.compact }'

        local line
        for file in "${parsed[@]}"; do
            while IFS= read -r line; do
                [[ -n "$line" ]] && printf '%s\t%s\n' "$file" "$line" >>"$uses_tsv"
            done < <(yaml_eval "$file" "$expr_uses")
        done

        # --- 2. every local uses: resolves to a file that exists --------------------------
        local local_total=0 local_missing=0 src location ref target
        while IFS=$'\t' read -r src location ref; do
            [[ "$ref" == ./* ]] || continue
            (( local_total++ )) || true
            target="${ref%%@*}"
            [[ -f "$target" ]] && continue
            report_fail "$src" "${location} has 'uses: ${ref}', which points at ${target} — no such file in the repository"
            (( local_missing++ )) || true
        done <"$uses_tsv"

        if (( local_total == 0 )); then
            report_note "no local 'uses: ./…' reference found; nothing to resolve for assertion 2"
        elif (( local_missing == 0 )); then
            report_pass "$wf_dir" "all ${local_total} local 'uses: ./…' reference(s) resolve to an existing workflow file"
        fi

        # --- 3. a trigger and at least one job, in every workflow -------------------------
        local expr_on='on = doc[true] || doc["on"]; on.nil? ? "" : (on.is_a?(Hash) ? on.keys.join(",") : (on.is_a?(Array) ? on.join(",") : on.to_s))'
        local expr_jobs='doc["jobs"].to_h.keys.length'

        local triggers job_count structural_bad=0 job_total=0
        for file in "${parsed[@]}"; do
            triggers="$(yaml_eval "$file" "$expr_on")"
            job_count="$(yaml_eval "$file" "$expr_jobs")"
            job_count="${job_count:-0}"

            if [[ -z "$triggers" ]]; then
                report_fail "$file" "declares no 'on:' trigger, so the workflow can never be triggered"
                (( structural_bad++ )) || true
            fi
            if (( job_count == 0 )); then
                report_fail "$file" "declares no jobs under 'jobs:', so the workflow does nothing when triggered"
                (( structural_bad++ )) || true
            fi
            (( job_total += job_count )) || true
        done

        if (( structural_bad == 0 )); then
            report_pass "$wf_dir" "all ${#parsed[@]} workflow(s) declare a trigger and at least one job (${job_total} jobs in total)"
        fi

        # --- 4. every timeout-minutes present is a positive integer -----------------------
        # <location>US<value or empty>US<job|caller-job|step>, where US is \x1F. A tab will
        # not do here: tab is IFS whitespace, so bash collapses the run of two delimiters a
        # missing value produces and the fields shift left.
        local expr_timeouts='doc["jobs"].to_h.flat_map { |n, j| h = (j.is_a?(Hash) ? j : {}); [["job \"" + n.to_s + "\"", h["timeout-minutes"], (h["uses"] ? "caller-job" : "job")]] + (h["steps"].is_a?(Array) ? h["steps"] : []).each_with_index.map { |s, i| ["job \"" + n.to_s + "\" step " + (i + 1).to_s, (s.is_a?(Hash) ? s["timeout-minutes"] : nil), "step"] } }.map { |c, v, k| [c, (v.nil? ? "" : v.to_s), k].join("\x1F") }'

        local timeout_present=0 timeout_invalid=0 value kind
        local -a jobs_without_timeout=()
        for file in "${parsed[@]}"; do
            while IFS=$'\x1f' read -r location value kind; do
                [[ -n "$location" ]] || continue
                if [[ -z "$value" ]]; then
                    # Steps inherit the job timeout, so only a job without one is worth noting.
                    [[ "$kind" == "step" ]] || jobs_without_timeout+=("${file##*/} ${location} [${kind}]")
                    continue
                fi
                if [[ "$value" =~ ^[1-9][0-9]*$ ]]; then
                    (( timeout_present++ )) || true
                else
                    report_fail "$file" "${location} has 'timeout-minutes: ${value}', which is not a positive integer"
                    (( timeout_invalid++ )) || true
                fi
            done < <(yaml_eval "$file" "$expr_timeouts")
        done

        if (( timeout_invalid == 0 )); then
            report_pass "$wf_dir" "all ${timeout_present} 'timeout-minutes:' value(s) are positive integers"
        fi

        if (( ${#jobs_without_timeout[@]} > 0 )); then
            local without_list=""
            local entry
            for entry in "${jobs_without_timeout[@]:-}"; do
                [[ -n "$entry" ]] || continue
                without_list+="${without_list:+; }${entry}"
            done
            report_note "no 'timeout-minutes:' on ${#jobs_without_timeout[@]} job(s): ${without_list}. Not a failure — the key is unsupported on a caller-job (one with a job-level 'uses:'), and a plain job without one falls back to the 6-hour default."
        fi

        # --- 5. external uses: refs resolve (network sub-check) ---------------------------
        # A ref is owner/repo[/sub/path]@rev. The rev belongs to owner/repo, so the sub-path
        # is dropped before asking the API — github/codeql-action/init@v3 is resolved as the
        # v3 rev of github/codeql-action.
        local artefact="external 'uses:' refs"
        if ! network_enabled; then
            report_skip "$artefact" "--offline mode: action refs are not resolved against the GitHub API"
        elif ! have_tool gh; then
            report_skip "$artefact" "'gh' not found on PATH, so no action ref can be resolved — install the GitHub CLI, or run with --offline to skip this sub-check deliberately"
        elif ! _wf_have_timeout; then
            report_skip "$artefact" "none of 'timeout', 'gtimeout' or 'perl' is available to bound a network call, and an unbounded call could hang this check"
        elif ! _wf_with_timeout 20 gh auth status >/dev/null 2>&1; then
            report_skip "$artefact" "'gh auth status' failed: the GitHub CLI is unauthenticated or the API is unreachable — run 'gh auth login', or use --offline"
        else
            local -a refs=()
            while IFS= read -r ref; do
                [[ -n "$ref" ]] && refs+=("$ref")
            done < <(awk -F'\t' '$3 !~ /^\.\// { print $3 }' "$uses_tsv" | sort -u)

            local resolved=0 unresolved=0 resolved_list="" action rev owner repo users
            for ref in "${refs[@]:-}"; do
                [[ -n "$ref" ]] || continue
                users="$(awk -F'\t' -v r="$ref" '$3 == r { print $1 }' "$uses_tsv" | sed 's#.*/##' | sort -u | tr '\n' ' ' | sed -E 's/ $//')"

                if [[ "$ref" == docker://* ]]; then
                    report_note "'uses: ${ref}' (${users}) is a container action, not a GitHub ref; not resolved"
                    continue
                fi
                if [[ "$ref" != *@* ]]; then
                    report_fail "$wf_dir" "'uses: ${ref}' (in ${users}) names no version after '@', so the action reference is unpinned and cannot be resolved"
                    (( unresolved++ )) || true
                    continue
                fi

                action="${ref%@*}"
                rev="${ref##*@}"
                owner="${action%%/*}"
                repo="${action#*/}"
                repo="${repo%%/*}"

                if _wf_with_timeout 20 gh api "repos/${owner}/${repo}/commits/${rev}" --jq .sha >/dev/null 2>&1; then
                    (( resolved++ )) || true
                    resolved_list+="${resolved_list:+, }${ref}"
                else
                    report_fail "$wf_dir" "'uses: ${ref}' (in ${users}) does not resolve: rev '${rev}' was not found in ${owner}/${repo} via the GitHub API"
                    (( unresolved++ )) || true
                fi
            done

            if (( resolved == 0 && unresolved == 0 )); then
                report_note "no external 'uses:' reference found; nothing to resolve"
            elif (( unresolved == 0 )); then
                report_pass "$artefact" "all ${resolved} distinct external ref(s) resolve on the GitHub API: ${resolved_list}"
            fi
        fi

        rm -f "$uses_tsv"
    }

    _wf_body
    unset -f _wf_body _wf_squash _wf_have_timeout _wf_with_timeout
    return 0
}
