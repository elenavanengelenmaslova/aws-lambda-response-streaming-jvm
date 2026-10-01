# shellcheck shell=bash
#
# check_permissions — Property 5: every workflow's declared permissions match the design's
# permission table exactly. Task 15.7.
#
# The assertion is SET EQUALITY, not "no more than". A subset test would pass a workflow
# that quietly gained `id-token: write`, which is the exact widening this check exists to
# catch. So for every level that can carry a `permissions:` map — the workflow level and
# every job — the full scope set is compared against one table row, and both directions of
# the comparison are checked:
#
#   * a level on disk with no table row          → FAIL (an unlisted workflow or job)
#   * a table row with no matching level on disk → FAIL (a removed job, or a renamed one)
#   * a level whose scope set differs            → FAIL, naming expected vs actual
#
# The table below is the design's "Workflow permission table" for the six workflows this
# spec owns, plus the five that pre-date it recorded as they actually are. The pre-existing
# rows are not a judgement about whether those permissions are right; they are a baseline,
# so that changing them becomes a deliberate edit to this file rather than a silent drift.
# Write scopes are additionally listed as a NOTE per workflow, so a reviewer reading the
# output sees every elevated token without cross-referencing the table.
#
# Three facts the table cannot express on its own are asserted separately:
#
#   1. `ci-main-build.yml` declares exactly one job (Requirement 3.3 — the merge gate has
#      no room for a second job, so a second one is a spec violation, not just a new row).
#   2. `codeql.yml`'s `analyze` job has no `id-token: write`. Implied by set equality, but
#      called out because OIDC in a code-scanning job is the specific mistake worth naming.
#   3. `dependency-submission.yml` has `contents: write` as its ONLY write scope anywhere
#      in the file — the one workflow in the repository that needs a write token at all.
#
# Scope-set format, used for both sides of every comparison so the equality is textual and
# auditable: `scope=value` pairs, sorted, comma-joined ("actions=read,contents=read"). Three
# sentinels stand in for the non-map cases: "none" (no `permissions:` key), "empty" (`{}`,
# i.e. all scopes dropped) and "scalar:<value>" (`read-all` / `write-all`).
#
# Everything here is function-local — no helper functions are defined, because all check
# files are sourced into one shell and a file-scope `unset -f` would run before dispatch.
#
# YAML is read through `yaml_eval` (Ruby/Psych). Psych is YAML 1.1, so a workflow's `on:`
# key loads as `doc[true]`; this check never touches `on:`, but the same parser quirk is why
# `permissions:` is read via the helper rather than grepped out of the text.

check_permissions() {
    local workflows_dir=".github/workflows"

    # <workflow file>|<workflow|job:NAME>|<sorted scope set, or none/empty/scalar:…>
    local -a expected_rows=(
        # --- the design's permission table (this spec) ---
        "ci-main-build.yml|workflow|contents=read"
        "ci-main-build.yml|job:build|none"
        "ci-dependabot-validation.yml|workflow|contents=read"
        "ci-dependabot-validation.yml|job:build|none"
        "codeql.yml|workflow|contents=read"
        "codeql.yml|job:analyze|actions=read,contents=read,packages=read,security-events=write"
        "gradle-wrapper-validation.yml|workflow|contents=read"
        "gradle-wrapper-validation.yml|job:validation|none"
        "dependency-submission.yml|workflow|contents=write"
        "dependency-submission.yml|job:dependency-submission|none"
        "workflow-build.yml|workflow|contents=read"
        "workflow-build.yml|job:test|contents=read"
        "workflow-build.yml|job:validate-sam|contents=read"
        # --- pre-dating this spec: recorded as-is, as a drift baseline ---
        "ci-feature-build.yml|workflow|contents=read,id-token=write"
        "ci-feature-build.yml|job:test|none"
        "ci-feature-build.yml|job:deploy|none"
        "ci-feature-build.yml|job:streaming-tests|none"
        "cd-deploy-on-demand.yml|workflow|contents=read,id-token=write"
        "cd-deploy-on-demand.yml|job:test|none"
        "cd-deploy-on-demand.yml|job:deploy|none"
        "cd-deploy-on-demand.yml|job:streaming-tests|none"
        "workflow-deploy-aws.yml|workflow|contents=read,id-token=write"
        "workflow-deploy-aws.yml|job:build-and-deploy|none"
        "workflow-streaming-test.yml|workflow|contents=read,id-token=write"
        "workflow-streaming-test.yml|job:streaming-test|none"
        "workflow-publish.yml|workflow|contents=read"
        "workflow-publish.yml|job:publish|none"
    )

    if [[ ! -d "$workflows_dir" ]]; then
        report_fail "$workflows_dir" "directory not found; no workflow permissions can be checked"
        return 0
    fi

    # One line per level: "<workflow|job:NAME>\t<scope set>", workflow level first.
    local levels_expr='([["workflow", doc["permissions"]]] + (doc["jobs"] || {}).map { |n, j| ["job:#{n}", j.is_a?(Hash) ? j["permissions"] : nil] }).map { |lvl, p| lvl + "\t" + (p.nil? ? "none" : (p.is_a?(Hash) ? (p.empty? ? "empty" : p.map { |k, v| "#{k}=#{v}" }.sort.join(",")) : "scalar:#{p}")) }'

    local -A expected_map=()
    local -A seen_levels=()
    local row key
    for row in "${expected_rows[@]}"; do
        key="${row%|*}"
        expected_map["$key"]="${row##*|}"
    done

    local -a files=()
    local file
    while IFS= read -r file; do
        [[ -n "$file" ]] && files+=("$file")
    done < <(find "$workflows_dir" -maxdepth 1 -type f \( -name '*.yml' -o -name '*.yaml' \) 2>/dev/null | sort)

    if (( ${#files[@]} == 0 )); then
        report_fail "$workflows_dir" "no workflow files found; the permission table describes ${#expected_rows[@]} levels that cannot exist"
        return 0
    fi

    local base lines rc line level actual expected
    local -a write_scopes=() file_failures=()
    local job_count matched

    for file in "${files[@]}"; do
        base="${file##*/}"

        rc=0
        lines="$(yaml_eval "$file" "$levels_expr" 2>&1)" || rc=$?
        if (( rc != 0 )); then
            report_fail "$base" "could not read its permissions map: $(printf '%s' "$lines" | head -n 2 | tr '\n' ' ')"
            continue
        fi

        write_scopes=()
        file_failures=()
        job_count=0
        matched=0

        while IFS= read -r line; do
            [[ -n "$line" ]] || continue
            level="${line%%$'\t'*}"
            actual="${line#*$'\t'}"
            [[ "$level" == job:* ]] && (( job_count++ ))
            seen_levels["${base}|${level}"]=1

            # Any write scope at this level, for the NOTE below.
            local pair
            for pair in ${actual//,/ }; do
                [[ "$pair" == *=write ]] && write_scopes+=("${level}:${pair}")
            done

            if [[ -z "${expected_map[${base}|${level}]+set}" ]]; then
                file_failures+=("$(printf '%s has no row in the permission table (actual %s) — add it to check_permissions.sh, or drop the permissions it declares' \
                    "$([[ "$level" == workflow ]] && echo "the workflow level" || echo "job '${level#job:}'")" "$actual")")
                continue
            fi
            expected="${expected_map[${base}|${level}]}"
            if [[ "$actual" == "$expected" ]]; then
                (( matched++ ))
            else
                file_failures+=("$(printf "%s declares %s but the table expects %s" \
                    "$([[ "$level" == workflow ]] && echo "the workflow level" || echo "job '${level#job:}'")" \
                    "$actual" "$expected")")
            fi
        done <<<"$lines"

        if (( ${#write_scopes[@]} > 0 )); then
            report_note "${base}: write scope(s) declared — ${write_scopes[*]} (expected by the table; anything added here widens the job token)"
        fi

        if (( ${#file_failures[@]} == 0 )); then
            report_pass "$base" "all $((matched)) declared level(s) match the permission table exactly"
        else
            local failure
            for failure in "${file_failures[@]}"; do
                report_fail "$base" "$failure"
            done
        fi

        # --- Requirement 3.3: the merge gate is one job ---------------------------------
        if [[ "$base" == "ci-main-build.yml" ]]; then
            if (( job_count == 1 )); then
                report_pass "$base" "declares exactly one job, so no second job can widen the merge gate's permissions (Requirement 3.3)"
            else
                report_fail "$base" "declares ${job_count} jobs; Requirement 3.3 allows exactly one, and each extra job is an unlisted permissions surface"
            fi
        fi

        # --- codeql.yml: no OIDC in the analysis job ------------------------------------
        if [[ "$base" == "codeql.yml" ]]; then
            if [[ " ${write_scopes[*]-} " == *"id-token=write"* ]]; then
                report_fail "$base" "declares id-token: write (${write_scopes[*]}); code scanning needs no OIDC token, so this is a widening to remove"
            else
                report_pass "$base" "declares no id-token: write anywhere, so the analysis job cannot assume an AWS role"
            fi
        fi

        # --- dependency-submission.yml: contents: write is the only write scope ---------
        if [[ "$base" == "dependency-submission.yml" ]]; then
            if [[ "${write_scopes[*]-}" == "workflow:contents=write" ]]; then
                report_pass "$base" "contents: write at the workflow level is its only write scope, as the dependency graph submission requires"
            else
                report_fail "$base" "expected exactly one write scope (workflow-level contents: write) but found: ${write_scopes[*]:-none}"
            fi
        fi
    done

    # --- Table rows with no matching level on disk --------------------------------------
    local missing=0
    for row in "${expected_rows[@]}"; do
        key="${row%|*}"
        if [[ -z "${seen_levels[$key]+set}" ]]; then
            report_fail "${key%%|*}" "$([[ "${key#*|}" == workflow ]] && echo "the workflow level" || echo "job '${key#*|job:}'") is in the permission table with '${row##*|}' but does not exist in the file; remove the row or restore the job"
            missing=1
        fi
    done
    (( missing == 0 )) && report_pass "the permission table" "every one of its ${#expected_rows[@]} rows matches a level that exists under ${workflows_dir}"

    return 0
}
