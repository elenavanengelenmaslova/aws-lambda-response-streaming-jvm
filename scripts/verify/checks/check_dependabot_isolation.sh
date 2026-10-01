# shellcheck shell=bash
#
# check_dependabot_isolation — the Dependabot validation workflow stays credential-isolated.
# Task 15.9.
#
# A workflow run triggered by Dependabot gets a read-only GITHUB_TOKEN and reads from a
# separate credential store, so an Actions secret passed into such a run resolves to the
# empty string. The isolation therefore has to hold structurally, in the two files below,
# rather than at runtime:
#
#   .github/workflows/ci-dependabot-validation.yml   the Dependabot entry point
#   .github/workflows/workflow-build.yml             the reusable build it calls
#
# Six assertions:
#
#   1. ci-dependabot-validation.yml never says "secrets" — not as a key, not in a comment.
#      The file was written to avoid the word entirely, so any occurrence is a regression
#      towards handing the run a credential it cannot read.
#   2. It references no deploy or streaming-test workflow (workflow-deploy-aws,
#      workflow-streaming-test): those are the credential-carrying workflows.
#   3. It has exactly one job-level `uses:`, and it names ./.github/workflows/workflow-build.yml.
#   4. That job carries the head-branch guard. `pull_request` has no head-branch filter, so
#      the `if:` is the substitute: on a human pull request the job must be skipped, not run.
#   5. workflow-build.yml declares CODECOV_TOKEN with `required: false`. A required secret
#      would fail every Dependabot run at workflow resolution, before a single step ran.
#   6. workflow-build.yml's coverage-upload eligibility is gated on
#      `github.event_name == 'push' && github.ref == 'refs/heads/main'`, so a Dependabot run
#      never reaches the upload at all.
#
# YAML is read through the shared Psych helpers. Psych follows YAML 1.1, so a workflow's
# `on:` key loads as the boolean true — hence `doc[true]` below.

check_dependabot_isolation() {
    # Local helpers, defined here (not at file scope) so they can be unset again at the end
    # without breaking a second invocation: every check file is sourced into one shell.

    # _dbi_squash <text…> — collapse whitespace runs to single spaces and trim, so a guard
    # expression compares equal regardless of how the YAML wrapped it.
    _dbi_squash() {
        printf '%s' "$*" | tr -s '[:space:]' ' ' | sed -E 's/^ //; s/ $//'
    }

    _dbi_body() {
        local dbi_file=".github/workflows/ci-dependabot-validation.yml"
        local build_file=".github/workflows/workflow-build.yml"
        local reusable="./.github/workflows/workflow-build.yml"
        local token="CODECOV_TOKEN"

        local missing=0 file
        for file in "$dbi_file" "$build_file"; do
            if [[ ! -f "$file" ]]; then
                report_fail "$file" "file not found; the Dependabot credential isolation cannot be checked"
                missing=1
            fi
        done
        (( missing == 0 )) || return 0

        for file in "$dbi_file" "$build_file"; do
            if ! yaml_parses "$file"; then
                report_fail "$file" "does not parse as YAML, so the Dependabot credential isolation cannot be checked"
                return 0
            fi
        done

        # Shared sub-expressions. Ruby uses double quotes throughout so the whole expression
        # can sit inside single shell quotes.
        local on_expr='(doc[true] || doc["on"] || {})'
        local steps_expr='doc["jobs"].to_h.values.flat_map { |j| j.is_a?(Hash) ? (j["steps"] || []) : [] }'

        # --- 1. the word "secrets" never appears in the Dependabot workflow ----------------
        local secret_hits
        secret_hits="$(grep -n -i -- 'secrets' "$dbi_file" || true)"
        if [[ -z "$secret_hits" ]]; then
            report_pass "$dbi_file" "contains no occurrence of 'secrets' — the run is handed no Actions secret, which is the only correct shape when the token is read-only and the credential store is separate"
        else
            report_fail "$dbi_file" "mentions 'secrets' on $(printf '%s\n' "$secret_hits" | wc -l | tr -d ' ') line(s): $(printf '%s\n' "$secret_hits" | sed -E 's/^([0-9]+):[[:space:]]*/line \1: /' | tr '\n' '|' | sed -E 's/\|$//'). A Dependabot run resolves any Actions secret to the empty string, so this file names none — remove the occurrence, comments included"
        fi

        # --- 2. no reference to a credential-carrying workflow -----------------------------
        local privileged_hits
        privileged_hits="$(grep -n -E 'workflow-deploy-aws|workflow-streaming-test' "$dbi_file" || true)"
        if [[ -z "$privileged_hits" ]]; then
            report_pass "$dbi_file" "references neither workflow-deploy-aws nor workflow-streaming-test, so no credential-carrying workflow is reachable from a Dependabot run"
        else
            report_fail "$dbi_file" "references a credential-carrying workflow: $(printf '%s\n' "$privileged_hits" | sed -E 's/^([0-9]+):[[:space:]]*/line \1: /' | tr '\n' '|' | sed -E 's/\|$//'). Neither workflow-deploy-aws nor workflow-streaming-test may be called from the Dependabot entry point"
        fi

        # --- 3. exactly one job-level uses:, naming the reusable build ---------------------
        # One tab-separated line per job: name, job-level uses (blank when absent), if.
        local job_lines
        job_lines="$(yaml_eval "$dbi_file" \
            'doc["jobs"].to_h.map { |n, b| h = (b.is_a?(Hash) ? b : {}); [n.to_s, h["uses"].to_s, h["if"].to_s].join("\t") }')"

        local calling_jobs=() name uses guard
        while IFS=$'\t' read -r name uses guard; do
            [[ -n "$name" ]] || continue
            [[ -n "$uses" ]] || continue
            calling_jobs+=("${name}"$'\t'"${uses}"$'\t'"${guard}")
        done <<< "$job_lines"

        local call_count=${#calling_jobs[@]}
        if (( call_count != 1 )); then
            local listed=""
            local entry
            for entry in "${calling_jobs[@]}"; do
                listed+="${listed:+, }$(printf '%s' "$entry" | cut -f1) uses '$(printf '%s' "$entry" | cut -f2)'"
            done
            report_fail "$dbi_file" "expected exactly one job-level 'uses:' naming ${reusable}, found ${call_count}${listed:+ (${listed})}. The Dependabot entry point calls the reusable build and nothing else"
            report_fail "$dbi_file" "the head-branch guard cannot be checked: no single calling job to read 'if:' from"
            return 0
        fi

        name="$(printf '%s' "${calling_jobs[0]}" | cut -f1)"
        uses="$(printf '%s' "${calling_jobs[0]}" | cut -f2)"
        guard="$(printf '%s' "${calling_jobs[0]}" | cut -f3)"

        if [[ "$uses" == "$reusable" ]]; then
            report_pass "$dbi_file" "exactly one job-level 'uses:' (job '${name}'), naming ${reusable}"
        else
            report_fail "$dbi_file" "job '${name}' has 'uses: ${uses}'; the only permitted target is ${reusable}"
        fi

        # --- 4. the head-branch guard on that job -----------------------------------------
        local guard_expected="\${{ github.event_name == 'push' || startsWith(github.head_ref, 'dependabot/') }}"
        if [[ "$(_dbi_squash "$guard")" == "$(_dbi_squash "$guard_expected")" ]]; then
            report_pass "$dbi_file" "job '${name}' carries the head-branch guard 'if: ${guard_expected}' — pull_request has no head-branch filter, so this is what skips the job on a human pull request"
        elif [[ -z "$guard" ]]; then
            report_fail "$dbi_file" "job '${name}' has no 'if:'; pull_request offers no head-branch filter, so the guard 'if: ${guard_expected}' is what keeps a human pull request from running this workflow"
        else
            report_fail "$dbi_file" "job '${name}' has 'if: ${guard}', expected 'if: ${guard_expected}'. The guard is the only head-branch filter available on pull_request, and on a human pull request the job must be skipped rather than run"
        fi

        # --- 5. workflow-build.yml declares CODECOV_TOKEN as required: false --------------
        local secret_names required_raw required
        secret_names="$(yaml_eval "$build_file" "${on_expr}"'["workflow_call"].to_h["secrets"].to_h.keys')"
        if ! printf '%s\n' "$secret_names" | grep -qxF -- "$token"; then
            report_fail "$build_file" "on.workflow_call.secrets declares no ${token} (found: $(printf '%s\n' "$secret_names" | tr '\n' ' ' | sed -E 's/[[:space:]]+$//; s/^$/none/')); ${dbi_file} calls this workflow without passing any secret, which only resolves if ${token} is a declared, optional input"
        else
            required_raw="$(yaml_eval "$build_file" "${on_expr}"'["workflow_call"].to_h["secrets"].to_h["'"$token"'"].to_h["required"].inspect')"
            required="${required_raw//\"/}"
            if [[ "$required" == "false" ]]; then
                report_pass "$build_file" "on.workflow_call.secrets.${token} declares 'required: false', so a caller that passes no secret still resolves — a required secret would fail every Dependabot run at workflow resolution, before any step ran"
            else
                report_fail "$build_file" "on.workflow_call.secrets.${token} has 'required: ${required}', expected 'required: false'. A required secret makes every Dependabot run fail at workflow resolution (${dbi_file} passes none), not at the upload step"
            fi
        fi

        # --- 6. coverage upload is unreachable from a Dependabot run -----------------------
        local push_cond="github.event_name == 'push'"
        local main_cond="github.ref == 'refs/heads/main'"

        local upload_ifs
        upload_ifs="$(yaml_eval "$build_file" \
            "${steps_expr}"'.select { |s| s.is_a?(Hash) && s["uses"].to_s.include?("codecov/codecov-action") }.map { |s| s["if"].to_s }')"

        local upload_count
        upload_count="$(printf '%s' "$upload_ifs" | grep -c '' || true)"
        if [[ -z "$upload_ifs" ]] || (( upload_count != 1 )); then
            report_fail "$build_file" "expected exactly one codecov/codecov-action step to inspect, found ${upload_count}; the coverage-upload gate that keeps a Dependabot run away from ${token} cannot be located"
            return 0
        fi

        local upload_if gate_text gate_source gate_id gate_if
        upload_if="$(_dbi_squash "$upload_ifs")"
        gate_text="$upload_if"
        gate_source="the codecov/codecov-action step's own 'if:'"

        if [[ "$gate_text" != *"$push_cond"* || "$gate_text" != *"$main_cond"* ]]; then
            # The upload step delegates the decision to an earlier step's output; follow it.
            gate_id="$(printf '%s' "$upload_if" | sed -nE 's/.*steps\.([A-Za-z0-9_-]+)\.outputs\.[A-Za-z0-9_-]+.*/\1/p')"
            if [[ -z "$gate_id" ]]; then
                report_fail "$build_file" "the codecov/codecov-action step has 'if: ${upload_if}', which gates on neither \"${push_cond} && ${main_cond}\" nor a preceding step's output. Nothing then stops a Dependabot run from reaching the upload"
                return 0
            fi
            gate_if="$(yaml_eval "$build_file" \
                "${steps_expr}"'.select { |s| s.is_a?(Hash) && s["id"] }.map { |s| s["id"].to_s + "\t" + s["if"].to_s }' \
                | awk -F'\t' -v id="$gate_id" '$1 == id { print $2 }')"
            if [[ -z "$gate_if" ]]; then
                report_fail "$build_file" "the codecov/codecov-action step gates on 'steps.${gate_id}.outputs.…', but no step with id '${gate_id}' carries an 'if:' restricting the upload to pushes of main"
                return 0
            fi
            gate_text="$(_dbi_squash "$gate_if")"
            gate_source="step '${gate_id}', which the codecov/codecov-action step gates on"
        fi

        if [[ "$gate_text" != *"$push_cond"* || "$gate_text" != *"$main_cond"* ]]; then
            report_fail "$build_file" "the coverage-upload gate (${gate_source}) is 'if: ${gate_text}', which does not require both \"${push_cond}\" and \"${main_cond}\". A Dependabot run is a push to a dependabot/** branch or a pull_request, so both conditions are what make the upload unreachable for it"
        elif [[ "$gate_text" == *"||"* ]]; then
            report_fail "$build_file" "the coverage-upload gate (${gate_source}) is 'if: ${gate_text}': it contains '||', so \"${push_cond}\" and \"${main_cond}\" are not necessarily both required and a Dependabot run could satisfy the gate through the other branch. Join the conditions with '&&'"
        else
            report_pass "$build_file" "the coverage-upload gate (${gate_source}) requires \"${push_cond} && ${main_cond}\", so a Dependabot run — a push to dependabot/** or a pull_request — never reaches the upload"
        fi

        report_note "isolation rests on three independent facts: no secret is passed in (${dbi_file}), ${token} is optional so resolution succeeds without one, and the upload is gated to pushes of main so it is never attempted."
    }

    _dbi_body
    unset -f _dbi_body _dbi_squash
    return 0
}
