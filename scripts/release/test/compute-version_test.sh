# shellcheck shell=bash
#
# compute-version_test.sh — unit tests for scripts/release/compute-version.sh (TDD).
#
# These tests are written BEFORE the implementation (Task 2.2). Until compute-version.sh
# exists they are EXPECTED to fail — that is the point of this TDD step.
#
# Harness contract (see run-tests.sh / assert.sh):
#   - This file is SOURCED by the runner inside a subshell that has already sourced
#     assert.sh and zeroed ASSERT_FAILURES. It must NOT source anything itself and must
#     NOT call `exit` (that would end the subshell early and skip later cases). It only
#     calls the assert_* helpers; the per-file counter decides the verdict.
#
# What is under test (per design.md "Version Computation" / "Testing Strategy"):
#   compute-version.sh reads the repo's git history + tags and writes three keys to the
#   file named by $GITHUB_OUTPUT:
#       version=vX.Y.Z   (empty string when bump=none)
#       bump=major|minor|patch|initial|none
#       exists=...       (placeholder emitted by the script)
#   Classification of each non-merge commit in latest..HEAD:
#       feat!: / any type with `!` before `:` / BREAKING CHANGE: footer  -> major
#       feat: / feat(scope):                                             -> minor
#       fix:  / fix(scope):                                              -> patch
#       chore(deps): chore(deps-dev): build(deps): build(deps-dev):      -> patch (repo rule)
#       plain chore: (no deps scope)                                     -> none
#   Max-severity precedence major > minor > patch; no prior tag -> v1.0.0 (initial).

# Resolve the script under test by path (relative to this test file's directory).
_CV_TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPUTE_VERSION_SCRIPT="$(cd "$_CV_TEST_DIR/../.." && pwd)/release/compute-version.sh"

# --- git fixture helpers --------------------------------------------------------------
#
# Each case builds a disposable git repo in a fresh temp dir so cases never interfere.
# Commits are empty (`--allow-empty`) — only the message matters to the classifier. The
# author/committer identity is forced locally so the suite doesn't depend on global git
# config, and the default branch is pinned to `main` for determinism.

# _cv_new_repo — create and `cd` into a fresh temp git repo. Echoes the repo dir.
_cv_new_repo() {
    local dir
    dir="$(mktemp -d "${TMPDIR:-/tmp}/cv-test.XXXXXX")"
    git -C "$dir" init -q -b main
    git -C "$dir" config user.email "test@example.com"
    git -C "$dir" config user.name "Release Test"
    git -C "$dir" config commit.gpgsign false
    printf '%s\n' "$dir"
}

# _cv_commit <repo> <subject> [body] — add one empty commit with the given message.
_cv_commit() {
    local repo=$1 subject=$2 body=${3:-}
    if [[ -n "$body" ]]; then
        git -C "$repo" commit -q --allow-empty -m "$subject" -m "$body"
    else
        git -C "$repo" commit -q --allow-empty -m "$subject"
    fi
}

# _cv_tag <repo> <tag> — create a lightweight tag at HEAD.
_cv_tag() {
    git -C "$1" tag "$2"
}

# _cv_run <repo> — run compute-version.sh inside <repo> with a redirected GITHUB_OUTPUT.
# Echoes the captured GITHUB_OUTPUT contents; sets the global _CV_RC to the exit code.
_cv_run() {
    local repo=$1 out rc
    out="$(mktemp "${TMPDIR:-/tmp}/cv-out.XXXXXX")"
    (
        cd "$repo" || exit 97
        GITHUB_OUTPUT="$out" bash "$COMPUTE_VERSION_SCRIPT"
    )
    rc=$?
    _CV_RC=$rc
    cat "$out"
    rm -f "$out"
}

# _cv_field <github_output_text> <key> — extract the value of key=value (last wins).
_cv_field() {
    local text=$1 key=$2
    printf '%s\n' "$text" | grep -E "^${key}=" | tail -n1 | cut -d= -f2-
}

# _cv_cleanup <repo> — remove a fixture repo.
_cv_cleanup() {
    [[ -n "${1:-}" && -d "$1" ]] && rm -rf "$1"
}

# _cv_version <repo> — convenience: run and echo just the `version` field.
_cv_version() {
    local out; out="$(_cv_run "$1")"
    _cv_field "$out" version
}
# _cv_bump <repo> — convenience: run and echo just the `bump` field.
_cv_bump() {
    local out; out="$(_cv_run "$1")"
    _cv_field "$out" bump
}

# =====================================================================================
# Example-based fixtures (design "Testing Strategy" §1)
# =====================================================================================

# --- No prior tag -> Initial_Version v1.0.0 (Req 2.5, Property 3) ---------------------
{
    repo="$(_cv_new_repo)"
    _cv_commit "$repo" "feat: first feature"
    out="$(_cv_run "$repo")"
    assert_eq "v1.0.0" "$(_cv_field "$out" version)" "no prior tag -> version=v1.0.0"
    assert_eq "initial" "$(_cv_field "$out" bump)"    "no prior tag -> bump=initial"
    _cv_cleanup "$repo"
}

# --- feat: only -> minor (Req 2.3) ----------------------------------------------------
{
    repo="$(_cv_new_repo)"
    _cv_commit "$repo" "chore: seed"; _cv_tag "$repo" "v2.1.0"
    _cv_commit "$repo" "feat: add streaming option"
    out="$(_cv_run "$repo")"
    assert_eq "minor"  "$(_cv_field "$out" bump)"    "feat: only -> bump=minor"
    assert_eq "v2.2.0" "$(_cv_field "$out" version)" "feat: from v2.1.0 -> v2.2.0"
    _cv_cleanup "$repo"
}

# --- fix: only -> patch (Req 2.4) -----------------------------------------------------
{
    repo="$(_cv_new_repo)"
    _cv_commit "$repo" "chore: seed"; _cv_tag "$repo" "v2.1.0"
    _cv_commit "$repo" "fix: correct off-by-one in buffer"
    out="$(_cv_run "$repo")"
    assert_eq "patch"  "$(_cv_field "$out" bump)"    "fix: only -> bump=patch"
    assert_eq "v2.1.1" "$(_cv_field "$out" version)" "fix: from v2.1.0 -> v2.1.1"
    _cv_cleanup "$repo"
}

# --- feat!: -> major (Req 2.2) --------------------------------------------------------
{
    repo="$(_cv_new_repo)"
    _cv_commit "$repo" "chore: seed"; _cv_tag "$repo" "v2.1.0"
    _cv_commit "$repo" "feat!: drop support for buffered responses"
    out="$(_cv_run "$repo")"
    assert_eq "major"  "$(_cv_field "$out" bump)"    "feat!: -> bump=major"
    assert_eq "v3.0.0" "$(_cv_field "$out" version)" "feat!: from v2.1.0 -> v3.0.0"
    _cv_cleanup "$repo"
}

# --- BREAKING CHANGE: footer in body -> major (Req 2.2) -------------------------------
{
    repo="$(_cv_new_repo)"
    _cv_commit "$repo" "chore: seed"; _cv_tag "$repo" "v2.1.0"
    _cv_commit "$repo" "feat: rework response writer" "BREAKING CHANGE: metadata format changed"
    out="$(_cv_run "$repo")"
    assert_eq "major"  "$(_cv_field "$out" bump)"    "BREAKING CHANGE footer -> bump=major"
    assert_eq "v3.0.0" "$(_cv_field "$out" version)" "BREAKING CHANGE footer -> v3.0.0"
    _cv_cleanup "$repo"
}

# --- Mixed {fix:, feat:, chore(deps):} -> minor (max-severity, Req 2.6/2.7, Property 1)
{
    repo="$(_cv_new_repo)"
    _cv_commit "$repo" "chore: seed"; _cv_tag "$repo" "v2.1.0"
    _cv_commit "$repo" "fix: tighten validation"
    _cv_commit "$repo" "feat: add key resolver"
    _cv_commit "$repo" "chore(deps): bump aws sdk"
    out="$(_cv_run "$repo")"
    assert_eq "minor"  "$(_cv_field "$out" bump)"    "mixed {fix,feat,chore(deps)} -> bump=minor"
    assert_eq "v2.2.0" "$(_cv_field "$out" version)" "mixed minor set from v2.1.0 -> v2.2.0"
    _cv_cleanup "$repo"
}

# --- Mixed {fix:, feat!:} -> major (max-severity, Req 2.6/2.7, Property 1) -------------
{
    repo="$(_cv_new_repo)"
    _cv_commit "$repo" "chore: seed"; _cv_tag "$repo" "v2.1.0"
    _cv_commit "$repo" "fix: patch a leak"
    _cv_commit "$repo" "feat!: remove deprecated handler"
    out="$(_cv_run "$repo")"
    assert_eq "major"  "$(_cv_field "$out" bump)"    "mixed {fix,feat!} -> bump=major"
    assert_eq "v3.0.0" "$(_cv_field "$out" version)" "mixed major set from v2.1.0 -> v3.0.0"
    _cv_cleanup "$repo"
}

# --- chore(deps): only -> patch (Dependabot rule, Req 2.6) ----------------------------
{
    repo="$(_cv_new_repo)"
    _cv_commit "$repo" "chore: seed"; _cv_tag "$repo" "v2.1.0"
    _cv_commit "$repo" "chore(deps): bump kotlinx-serialization"
    out="$(_cv_run "$repo")"
    assert_eq "patch"  "$(_cv_field "$out" bump)"    "chore(deps): only -> bump=patch"
    assert_eq "v2.1.1" "$(_cv_field "$out" version)" "chore(deps): from v2.1.0 -> v2.1.1"
    _cv_cleanup "$repo"
}

# --- chore(deps-dev): only -> patch (Dependabot rule, Req 2.6) ------------------------
{
    repo="$(_cv_new_repo)"
    _cv_commit "$repo" "chore: seed"; _cv_tag "$repo" "v2.1.0"
    _cv_commit "$repo" "chore(deps-dev): bump mockk"
    out="$(_cv_run "$repo")"
    assert_eq "patch"  "$(_cv_field "$out" bump)"    "chore(deps-dev): only -> bump=patch"
    assert_eq "v2.1.1" "$(_cv_field "$out" version)" "chore(deps-dev): from v2.1.0 -> v2.1.1"
    _cv_cleanup "$repo"
}

# --- build(deps): only -> patch (Dependabot rule, Req 2.6) ----------------------------
{
    repo="$(_cv_new_repo)"
    _cv_commit "$repo" "chore: seed"; _cv_tag "$repo" "v2.1.0"
    _cv_commit "$repo" "build(deps): bump gradle plugin"
    out="$(_cv_run "$repo")"
    assert_eq "patch"  "$(_cv_field "$out" bump)"    "build(deps): only -> bump=patch"
    assert_eq "v2.1.1" "$(_cv_field "$out" version)" "build(deps): from v2.1.0 -> v2.1.1"
    _cv_cleanup "$repo"
}

# --- plain chore: only -> none (no bump) (Req 2) --------------------------------------
{
    repo="$(_cv_new_repo)"
    _cv_commit "$repo" "chore: seed"; _cv_tag "$repo" "v2.1.0"
    _cv_commit "$repo" "chore: reformat comments"
    out="$(_cv_run "$repo")"
    assert_eq "none" "$(_cv_field "$out" bump)"    "plain chore: only -> bump=none"
    assert_eq ""     "$(_cv_field "$out" version)" "plain chore: only -> empty version"
    _cv_cleanup "$repo"
}

# --- Increment: v2.1.0 + fix: -> v2.1.1 (Property 3 monotonic) -------------------------
{
    repo="$(_cv_new_repo)"
    _cv_commit "$repo" "chore: seed"; _cv_tag "$repo" "v2.1.0"
    _cv_commit "$repo" "fix: handle empty object"
    assert_eq "v2.1.1" "$(_cv_version "$repo")" "increment v2.1.0 + fix: -> v2.1.1"
    _cv_cleanup "$repo"
}

# --- Increment: v2.1.0 + feat: -> v2.2.0 (Property 3 monotonic) ------------------------
{
    repo="$(_cv_new_repo)"
    _cv_commit "$repo" "chore: seed"; _cv_tag "$repo" "v2.1.0"
    _cv_commit "$repo" "feat: new endpoint"
    assert_eq "v2.2.0" "$(_cv_version "$repo")" "increment v2.1.0 + feat: -> v2.2.0"
    _cv_cleanup "$repo"
}

# --- Increment: v2.1.0 + feat!: -> v3.0.0 (Property 3 monotonic) -----------------------
{
    repo="$(_cv_new_repo)"
    _cv_commit "$repo" "chore: seed"; _cv_tag "$repo" "v2.1.0"
    _cv_commit "$repo" "feat!: incompatible protocol change"
    assert_eq "v3.0.0" "$(_cv_version "$repo")" "increment v2.1.0 + feat!: -> v3.0.0"
    _cv_cleanup "$repo"
}

# --- feat(scope): classified as minor (Req 2.3 — scoped form) -------------------------
{
    repo="$(_cv_new_repo)"
    _cv_commit "$repo" "chore: seed"; _cv_tag "$repo" "v2.1.0"
    _cv_commit "$repo" "feat(core): scoped feature"
    assert_eq "minor" "$(_cv_bump "$repo")" "feat(scope): -> bump=minor"
    _cv_cleanup "$repo"
}

# --- fix(scope): classified as patch (Req 2.4 — scoped form) --------------------------
{
    repo="$(_cv_new_repo)"
    _cv_commit "$repo" "chore: seed"; _cv_tag "$repo" "v2.1.0"
    _cv_commit "$repo" "fix(core): scoped fix"
    assert_eq "patch" "$(_cv_bump "$repo")" "fix(scope): -> bump=patch"
    _cv_cleanup "$repo"
}

# =====================================================================================
# Property-style assertions over the fixtures above (design "Correctness Properties")
# =====================================================================================

# --- Property 1 (max-severity bump): Validates Requirements 2.6, 2.7 ------------------
# Order-independence: the same aggregate set yields the same max-severity bump regardless
# of the order the commits land in. Permute {feat!:, fix:, chore(deps):} and expect major.
{
    repo="$(_cv_new_repo)"
    _cv_commit "$repo" "chore: seed"; _cv_tag "$repo" "v2.1.0"
    _cv_commit "$repo" "chore(deps): bump a"
    _cv_commit "$repo" "fix: b"
    _cv_commit "$repo" "feat!: c"
    assert_eq "major" "$(_cv_bump "$repo")" "Property 1: max-severity is order-independent (-> major)"
    _cv_cleanup "$repo"

    repo="$(_cv_new_repo)"
    _cv_commit "$repo" "chore: seed"; _cv_tag "$repo" "v2.1.0"
    _cv_commit "$repo" "feat!: c"
    _cv_commit "$repo" "chore(deps): bump a"
    _cv_commit "$repo" "fix: b"
    assert_eq "major" "$(_cv_bump "$repo")" "Property 1: reordered aggregate still -> major"
    _cv_cleanup "$repo"
}

# --- Property 2 (strict output form ^v\d+\.\d+\.\d+$): Validates Requirements 2.8 ------
# Every non-none run emits a version matching strict vMAJOR.MINOR.PATCH. Checked across a
# diverse set of baselines + bumps.
{
    _cv_strict_re='^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'

    # No prior tag -> v1.0.0.
    repo="$(_cv_new_repo)"
    _cv_commit "$repo" "feat: x"
    v="$(_cv_version "$repo")"
    if [[ "$v" =~ $_cv_strict_re ]]; then
        assert_eq "v1.0.0" "$v" "Property 2: initial version is strict vX.Y.Z"
    else
        assert_eq "<strict vX.Y.Z>" "$v" "Property 2: initial version is strict vX.Y.Z"
    fi
    _cv_cleanup "$repo"

    # Each of patch/minor/major from a baseline emits a strict form.
    for spec in "fix: p|v2.1.1" "feat: m|v2.2.0" "feat!: M|v3.0.0"; do
        msg="${spec%%|*}"; want="${spec##*|}"
        repo="$(_cv_new_repo)"
        _cv_commit "$repo" "chore: seed"; _cv_tag "$repo" "v2.1.0"
        _cv_commit "$repo" "$msg"
        v="$(_cv_version "$repo")"
        if [[ "$v" =~ $_cv_strict_re ]]; then
            assert_eq "$want" "$v" "Property 2: '$msg' -> strict $want"
        else
            assert_eq "<strict vX.Y.Z>" "$v" "Property 2: '$msg' -> strict vX.Y.Z"
        fi
        _cv_cleanup "$repo"
    done
}

# --- Property 3 (monotonic increase / Initial_Version): Validates Requirements 2.1, 2.5
# The computed version is strictly greater (SemVer order) than the latest tag, or equals
# the Initial_Version when none exists. `sort -V` orders SemVer; the computed version must
# sort strictly after the baseline.
{
    # Initial_Version branch.
    repo="$(_cv_new_repo)"
    _cv_commit "$repo" "fix: y"
    assert_eq "v1.0.0" "$(_cv_version "$repo")" "Property 3: no tag -> Initial_Version v1.0.0"
    _cv_cleanup "$repo"

    # Strictly-greater branch for each bump kind from v2.1.0.
    for spec in "fix: p|v2.1.1" "feat: m|v2.2.0" "feat!: M|v3.0.0"; do
        msg="${spec%%|*}"
        baseline="v2.1.0"
        repo="$(_cv_new_repo)"
        _cv_commit "$repo" "chore: seed"; _cv_tag "$repo" "$baseline"
        _cv_commit "$repo" "$msg"
        v="$(_cv_version "$repo")"
        # Highest of {baseline, v} under version sort must be v, and v != baseline.
        highest="$(printf '%s\n%s\n' "$baseline" "$v" | sort -V | tail -n1)"
        if [[ "$v" != "$baseline" && "$highest" == "$v" ]]; then
            _assert_pass "Property 3: '$msg' -> $v strictly greater than $baseline"
        else
            _assert_fail "Property 3: '$msg' expected > $baseline but got [$v]"
        fi
        _cv_cleanup "$repo"
    done
}
