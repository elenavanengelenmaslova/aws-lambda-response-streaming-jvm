# shellcheck shell=bash
#
# check_badge_urls — every badge in the README badge block resolves: both its image URL
# and the page it clicks through to. Task 15.12, registered as a `network` check.
#
# Two URLs per badge, eight badges, so sixteen requests. The image URL proves the badge
# renders at all; the target URL proves the click-through is not a dead end. A badge that
# renders but links nowhere is still a broken signal, which is why both are requested.
#
# Pending badges (Requirement 16.8)
# ---------------------------------
# Four badges point at signals that do not exist yet — `GitHub release`, `Maven Central`,
# `codecov`, `CodeQL`. Their image URLs legitimately render "no release" / "unknown", and
# their targets legitimately 404: there is no release to redirect to, the artifact is not
# indexed, no Codecov project exists, and code-scanning has no results. For those four an
# unreachable URL is PENDING, not FAIL. `report_pending` looks the blocking item up in
# PENDING_BADGES itself and fails if the label is unknown, so the label is passed exactly
# as it appears in the README. The other four (`Build Status`, `Kotlin`, `JVM`,
# `License: MIT`) point at signals that exist today, so unreachable means broken.
#
# Flaky third parties
# -------------------
# shields.io throttles and occasionally stalls. A request that dies on a timeout, or comes
# back 429, is reported SKIP rather than FAIL — the same reasoning as Requirement 5.5,
# which keeps third-party availability out of the merge gate, applied inside the script:
# a slow CDN must not be able to block a merge. Every request is bounded
# (--connect-timeout 5, --max-time 10) so the check cannot hang; worst case is sixteen
# ten-second waits.

check_badge_urls() {
    # Local helpers live inside the function and are unset at the end: every check file is
    # sourced into one shell, so file-scope helpers would leak across checks.

    # _urls_probe <url> — print "<http status>\t<curl exit code>". Never fails the shell:
    # a curl error is returned as data so the caller can classify it.
    _urls_probe() {
        local url=$1 code="" rc=0
        code="$(curl --silent --show-error --location \
            --connect-timeout 5 --max-time 10 \
            --output /dev/null --write-out '%{http_code}' \
            "$url" 2>/dev/null)" || rc=$?
        printf '%s\t%s\n' "${code:-000}" "$rc"
    }

    # _urls_report <badge label> <what> <url> — one result line for one URL.
    _urls_report() {
        local badge=$1 what=$2 url=$3
        local probe code rc artefact
        probe="$(_urls_probe "$url")"
        code="${probe%%$'\t'*}"
        rc="${probe##*$'\t'}"
        artefact="${badge} ${what}"

        # Timeout (curl 28) or throttling (429): third-party availability, not a defect.
        if (( rc == 28 )); then
            report_skip "$artefact" "request to ${url} timed out (curl exit 28, --max-time 10); shields.io and friends stall under load and must not block a merge"
            return 0
        fi
        if [[ "$code" == "429" ]]; then
            report_skip "$artefact" "${url} returned HTTP 429 (rate limited); retry later rather than treating throttling as a broken badge"
            return 0
        fi

        # 2xx and 3xx both count as reachable: --location follows redirects, so a 3xx here
        # is one curl chose not to follow (a redirect loop or cross-protocol hop), which
        # still proves the endpoint exists.
        if [[ "$code" =~ ^[23][0-9][0-9]$ ]]; then
            report_pass "$artefact" "${url} → HTTP ${code}"
            return 0
        fi

        local detail="${url} → HTTP ${code}"
        (( rc == 0 )) || detail+=" (curl exit ${rc})"

        if is_pending_badge "$badge"; then
            report_pending "$badge" "${what} not reachable yet: ${detail}"
            return 0
        fi

        # A GitHub Actions badge.svg 404s when the workflow has no run on the branch the
        # badge names — which is the state on any branch where the workflow file has not
        # reached main yet. Distinguish that from a wrong URL by whether the workflow file
        # exists in this checkout: present means "no run on that branch yet" (SKIP, it
        # resolves on merge), absent means the badge names a workflow that does not exist
        # (FAIL, and no merge fixes that).
        local workflow
        workflow="$(_urls_actions_workflow "$url")"
        if [[ "$code" == "404" && -n "$workflow" ]]; then
            if [[ -f ".github/workflows/${workflow}" ]]; then
                report_skip "$artefact" "${detail}: .github/workflows/${workflow} exists in this checkout but has no run on the branch the badge names, so GitHub has no badge to render yet; this resolves once the workflow runs on main"
            else
                report_fail "$artefact" "not reachable: ${detail}, and .github/workflows/${workflow} does not exist — the badge names a workflow this repository does not have"
            fi
            return 0
        fi

        report_fail "$artefact" "not reachable: ${detail}. Expected a 2xx or 3xx status"
    }

    # _urls_actions_workflow <url> — the workflow file name from a GitHub Actions badge
    # URL (…/actions/workflows/<file>/badge.svg…), or nothing for any other URL.
    _urls_actions_workflow() {
        printf '%s' "$1" | sed -nE 's#^https://github\.com/[^/]+/[^/]+/actions/workflows/([^/]+)/badge\.svg.*$#\1#p'
    }

    _urls_body() {
        local readme="README.md"

        if [[ ! -f "$readme" ]]; then
            report_fail "$readme" "file not found; the badge URLs cannot be checked"
            return 0
        fi
        if ! have_tool curl; then
            report_skip "$readme" "'curl' not found on PATH, so badge URL reachability is unproven in this run"
            return 0
        fi
        # A named network check bypasses the scaffold's auto-skip, so the mode is honoured
        # here too: --offline must never reach the network.
        if ! network_enabled; then
            report_skip "the badge image and target URLs" "network check, skipped in --offline mode"
            return 0
        fi

        # Badge lines are `[![<label>](<image url>)](<target url>)`. No URL in the block
        # contains a parenthesis, so the bracket-balanced character classes are enough.
        local badge_lines
        badge_lines="$(grep -E '^\[!\[[^]]+\]\([^)]+\)\]\([^)]+\)$' "$readme" \
            | sed -E 's/^\[!\[([^]]+)\]\(([^)]+)\)\]\(([^)]+)\)$/\1\t\2\t\3/' || true)"

        if [[ -z "$badge_lines" ]]; then
            report_fail "$readme" "no badge lines of the form '[![label](image)](target)' found; the badge block cannot be probed"
            return 0
        fi

        local count
        count="$(printf '%s\n' "$badge_lines" | grep -c '' || true)"
        if (( count != 8 )); then
            report_fail "$readme" "found ${count} badge line(s), expected 8; check_badge_block owns the literal, but an unexpected count means some badge went unprobed here"
        fi

        local label image target
        while IFS=$'\t' read -r label image target; do
            [[ -n "$label" ]] || continue
            _urls_report "$label" "image" "$image"
            _urls_report "$label" "target" "$target"
        done <<<"$badge_lines"

        report_note "each request is bounded by --connect-timeout 5 --max-time 10, and a timeout or HTTP 429 is a SKIP: a stalling shields.io must not block a merge (Requirement 5.5's reasoning)."
    }

    _urls_body
    unset -f _urls_body _urls_report _urls_probe _urls_actions_workflow
    return 0
}
