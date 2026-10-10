# Implementation Plan: Automated Release Pipeline

## Overview

Build the release-automation pipeline as CI/CD glue only — no application/Kotlin code. The deliverables are two bash scripts under `scripts/release/` and a single GitHub Actions workflow (`.github/workflows/release-tag.yml`) with three `needs:`-ordered jobs (`compute` → `tag` → `readme-pr`), plus a bash test harness for the scripts and a static assertion over the workflow's path filter.

The plan is test-driven: each script's tests are written just before (or alongside) the script, so the behavior is pinned by fixtures before the implementation lands. The workflow is wired only after both scripts pass their unit tests, then a static path-filter assertion proves the loop guard. Finally the known gotchas are appended to `docs/log.md`, and a maintainer-only checklist covers the one-time live validation and the `RELEASE_PAT` secret setup.

Grounding facts (verified against the repo):
- README coordinate line to edit: `implementation("nl.vintik:aws-lambda-streaming-core:2.1.0")` (README.md line 33).
- `workflow-publish.yml` already fires on `push` tags `v*` — must not be modified.
- `docs/log.md` already exists and uses a Title / Symptom / Resolution entry form.

## Tasks

- [x] 1. Set up the release script directory and test harness
  - Create `scripts/release/` and a `scripts/release/test/` directory for fixtures/tests.
  - Add a minimal plain-bash test harness (`scripts/release/test/run-tests.sh`) that discovers and runs `*_test.sh` files, counts pass/fail, and exits non-zero on any failure. Use `bats` only if already available; otherwise the plain harness keeps the suite dependency-free.
  - Add a tiny assertion helper (`assert_eq`, `assert_contains`, `assert_exit_code`) sourced by the test files.
  - _Requirements: 2, 5_

- [x] 2. Version computation — tests first, then the script
  - [x] 2.1 Write unit tests for `compute-version.sh`
    - Create `scripts/release/test/compute-version_test.sh`. Each case builds a throwaway git repo in a temp dir (`git init`, make commits with the fixture subjects/bodies, create the baseline tag), runs `compute-version.sh` with a redirected `GITHUB_OUTPUT`, then asserts the emitted `version`/`bump`/`exists`.
    - Diverse fixtures (10–20 cases) covering the design's Testing Strategy:
      - `feat:` only → `bump=minor`; `fix:` only → `bump=patch`; `feat!:` → `bump=major`; `BREAKING CHANGE:` footer in body → `bump=major`.
      - Mixed `{fix:, feat:, chore(deps):}` → `minor`; mixed `{fix:, feat!:}` → `major` (max-severity).
      - `chore(deps):` only → `patch`; `chore(deps-dev):` / `build(deps):` → `patch`; plain `chore:` only → `bump=none`.
      - No prior tag → `version=v1.0.0`.
      - Increment cases from baseline `v2.1.0`: `+fix:` → `v2.1.1`; `+feat:` → `v2.2.0`; `+feat!:` → `v3.0.0`.
    - **Property 1 (max-severity bump): Validates Requirements 2.6, 2.7**
    - **Property 2 (strict output form `^v\d+\.\d+\.\d+$`): Validates Requirements 2.8**
    - **Property 3 (monotonic increase / Initial_Version): Validates Requirements 2.1, 2.5**
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8_

  - [x] 2.2 Implement `scripts/release/compute-version.sh`
    - `set -euo pipefail`. Select Latest_Tag via `git tag --list 'v*' --sort=-v:refname` filtered by strict `^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$`, newest first.
    - No prior tag → emit `version=v1.0.0`, `bump=initial`.
    - Enumerate `latest..HEAD` with `git log --no-merges --format='%s%n%b'`; classify each commit: `!` before `:` or `BREAKING CHANGE:` footer → major; `feat`/`feat(scope)` → minor; `fix`/`fix(scope)` → patch; `chore(deps)` / `chore(deps-dev)` / `build(deps)` / `build(deps-dev)` scopes → patch (repo-specific Dependabot promotion, documented inline).
    - Apply max-severity precedence major > minor > patch; compute next `vX.Y.Z`; `bump=none` when nothing bumpable.
    - Emit `version`, `bump` (and `exists` placeholder) to `$GITHUB_OUTPUT`. Add an inline comment flagging the `chore(deps)`→patch convention.
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8_

  - [x] 2.3 Make the compute-version tests pass
    - Run `scripts/release/test/run-tests.sh` and resolve any failures until all compute-version fixtures pass.
    - _Requirements: 2.6, 2.7, 2.8_

- [x] 3. Checkpoint - version computation
  - Ensure all compute-version tests pass, ask the user if questions arise.

- [x] 4. README version edit — tests first, then the script
  - [x] 4.1 Write unit tests for `update-readme-version.sh`
    - Create `scripts/release/test/update-readme-version_test.sh`. Each case copies a README fixture into a temp dir (inside a temp git repo so the `git diff --quiet` guard works), runs the script with a `vX.Y.Z` arg, then asserts.
    - Round-trip: for several versions, after running, the coordinate line equals exactly `nl.vintik:aws-lambda-streaming-core:X.Y.Z` and **exactly one** line changed.
    - Anchor-missing fixture (coordinate renamed/removed) → script exits non-zero and leaves README unchanged.
    - **Property 4 (README round-trip, exactly one changed line): Validates Requirements 5.2, 8.3**
    - _Requirements: 5.2, 8.3_

  - [x] 4.2 Implement `scripts/release/update-readme-version.sh`
    - `set -euo pipefail`. Strip leading `v`; `sed -E -i.bak 's#(nl\.vintik:aws-lambda-streaming-core:)[0-9]+\.[0-9]+\.[0-9]+#\1<version>#' README.md`; remove the `.bak`.
    - Fail loudly (`exit 1` + `::error::`) when `git diff --quiet -- README.md` reports no change (anchor not found / nothing changed).
    - _Requirements: 5.2, 8.3_

  - [x] 4.3 Make the update-readme tests pass
    - Run the harness and resolve failures until round-trip and anchor-missing cases pass.
    - _Requirements: 5.2, 8.3_

- [x] 5. Checkpoint - README edit
  - Ensure all script tests pass, ask the user if questions arise.

- [x] 6. Wire the release workflow
  - [x] 6.1 Create `.github/workflows/release-tag.yml` trigger and `compute` job
    - `on: push: branches: [main], paths: ['streaming-core/src/main/**', 'streaming-core/build.gradle.kts']`.
    - `compute` job: `permissions: contents: read`; `actions/checkout@v4` with `fetch-depth: 0`; run `scripts/release/compute-version.sh`; expose `version`, `bump`, `exists` as job outputs.
    - _Requirements: 1.1, 1.2, 1.3, 2.1, 7.1_

  - [x] 6.2 Add the `tag` job (guard, create+push tag, GitHub Release)
    - `needs: compute`; condition `if` to skip when `bump == none`; `permissions: contents: write`; checkout `fetch-depth: 0` with `token: ${{ secrets.RELEASE_PAT || secrets.GITHUB_TOKEN }}`.
    - Guard: `git rev-parse -q --verify "refs/tags/$version"` → set `exists`; stop before creating/pushing when the tag already exists.
    - Create + push the tag. Never publish to Maven Central.
    - Create the GitHub Release draft-first: `gh release create "$version" --generate-notes --draft`; read body via `gh release view --json body --jq .body`; if empty/whitespace, `gh release delete --cleanup-tag=false` + `exit 1`; otherwise `gh release edit "$version" --draft=false`. Set `GH_TOKEN` from `RELEASE_PAT || GITHUB_TOKEN`.
    - Do **not** create or commit a `CHANGELOG.md`.
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 4.1, 4.2, 4.3, 4.4, 7.1, 7.3, 8.1, 8.4_

  - [x] 6.3 Add the `readme-pr` job (branch, edit, commit, PR)
    - `needs: tag`; `permissions: contents: write, pull-requests: write`; checkout `fetch-depth: 0` with `token: ${{ secrets.RELEASE_PAT || secrets.GITHUB_TOKEN }}`.
    - `git switch -c "release/$version"`; run `scripts/release/update-readme-version.sh "$version"`; commit `chore: update README dependency version to X.Y.Z`; push the branch; `gh pr create --base main --head "release/$version"` with title/body noting it is docs-only. Set `GH_TOKEN` from `RELEASE_PAT || GITHUB_TOKEN`.
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 7.1, 7.2, 7.3, 8.2, 8.3_

- [x] 7. Static path-filter / loop-guard assertion
  - [x] 7.1 Add a static assertion over `release-tag.yml` `on.push.paths`
    - Create `scripts/release/test/path-filter_test.sh` asserting `on.push.paths` is **exactly** `streaming-core/src/main/**` and `streaming-core/build.gradle.kts` (use `yq` if present, else a grep/line check), proving docs/example/tooling pushes don't trigger (Req 1) and a README-only merge cannot start a run (Req 6).
    - **Property 6 (loop exclusion): Validates Requirements 6.1, 6.2**
    - _Requirements: 1.3, 6.1, 6.2_

- [x] 8. Document known gotchas in `docs/log.md`
  - Append entries (Title / Symptom / Resolution form) for the release-pipeline gotchas:
    - `GITHUB_TOKEN`-pushed tags do **not** trigger `on: push` tag workflows → `workflow-publish.yml` won't auto-fire; use `RELEASE_PAT` for the tag push.
    - `GITHUB_TOKEN`-opened PRs do **not** trigger `on: pull_request` workflows → required checks on the README PR won't auto-run under branch protection; use `RELEASE_PAT`.
    - `chore:` → no bump, but `chore(deps)` / `chore(deps-dev)` / `build(deps)` scopes are promoted to **patch** (repo-specific convention, not vanilla Conventional Commits).
    - No runtime secrets recorded (per steering).
    - _Requirements: 2.7, 7.3_

- [x] 9. Final checkpoint - maintainer manual validation (documented, not code)
  - This task documents one-time maintainer actions; it does not execute a real release from the pipeline. All sub-steps are **repo-admin actions**, not coding-agent tasks. Record the detailed checklist below in `docs/log.md` (or the implementation PR description) so it is captured in the repo.

  - [x] 9.1 Create the `RELEASE_PAT` repository secret (repo admin)
    - **Why:** GitHub suppresses workflow re-triggering when the actor is the built-in `GITHUB_TOKEN`. A tag pushed with `GITHUB_TOKEN` will not fire `workflow-publish.yml`, and a PR opened with it will not fire the `on: pull_request` checks in `ci-main-build.yml`. A PAT is a distinct actor, so both downstream workflows run.
    - **Token type:** prefer a **fine-grained PAT** scoped to only `aws-lambda-streaming-jvm-runtime`, with repository permissions **Contents: Read and write** (push tags, create the `release/vX.Y.Z` branch, create the Release) and **Pull requests: Read and write** (open the README PR). Set an expiry and a rotation reminder. A classic PAT with the `repo` scope also works but is broader.
    - **Create:** GitHub → Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token; resource owner = the repo owner; Repository access = Only select repositories → this repo; set the two permissions; generate and copy the value (shown once).
    - **Store as a repo secret** named exactly `RELEASE_PAT` (the workflow reads `secrets.RELEASE_PAT`, falling back to `GITHUB_TOKEN`): Repo → Settings → Secrets and variables → Actions → New repository secret. CLI equivalent:
      ```bash
      gh secret set RELEASE_PAT --repo elenavanengelenmaslova/aws-lambda-streaming-jvm-runtime
      # paste the token when prompted (not echoed)
      ```
    - **Branch protection caveat:** if `main` is protected or rulesets restrict who may create tags, add the PAT's identity to the allow/bypass list so it can push the release branch and tag.
    - _Requirements: 7.1, 7.2, 7.3_

  - [x] 9.2 Trigger a real release (one-time live validation)
    - On a branch, make a trivial but real change under `streaming-core/src/main/**` (the reliable way to exercise the trigger path), commit with a Conventional Commit message that forces a known bump (e.g. `fix: trigger first automated release` → patch, or `feat: ...` → minor), open a PR into `main`, let `ci-main-build.yml` pass, and merge.
    - _Requirements: 1.1, 2.1_

  - [x] 9.3 Confirm the computed tag
    - In the Actions tab, confirm `release-tag.yml` started and the `compute` job output matches expectation (latest tag + chosen bump), and the `tag` job pushed exactly that `vX.Y.Z`:
      ```bash
      git fetch --tags
      git tag --list 'v*' --sort=-v:refname | head
      ```
    - _Requirements: 2.1, 3.1, 3.2, 3.4_

  - [x] 9.4 Confirm the two parallel tag consumers
    - `workflow-publish.yml` run is green and the version appears on Maven Central (this is the real proof `RELEASE_PAT` is wired — it silently no-ops if the tag was pushed by `GITHUB_TOKEN`).
    - The Release step produced a **published** GitHub Release on the tag with **non-empty** auto-generated notes (not left as a draft, which would mean the empty-notes guard tripped):
      ```bash
      gh release view v<X.Y.Z> --repo elenavanengelenmaslova/aws-lambda-streaming-jvm-runtime
      ```
    - _Requirements: 3.3, 4.1, 4.2, 4.4, 8.1, 8.4_

  - [x] 9.5 Confirm the README PR
    - The `readme-pr` job created branch `release/vX.Y.Z` and opened a PR into `main`; the diff changes only the one README line to `nl.vintik:aws-lambda-streaming-core:X.Y.Z`; required checks are **reporting** on the PR (proves the PAT re-triggered them):
      ```bash
      gh pr list --repo elenavanengelenmaslova/aws-lambda-streaming-jvm-runtime --head release/v<X.Y.Z>
      ```
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 8.2, 8.3_

  - [x] 9.6 Confirm the loop guard (critical negative test)
    - Merge the README PR into `main` and confirm **no new `release-tag.yml` run starts** (the PR touched only `README.md`, matching neither trigger path). If a run does start, the path filter is wrong and must be fixed before relying on the automation.
    - _Requirements: 6.1, 6.2_

  - [x] 9.7 Record the outcome
    - Append to `docs/log.md`: the first version produced, confirmation of the parallel publish + non-empty Release, and that the loop guard held.
    - Ensure all script and static tests pass before handing off; ask the user if questions arise.
    - _Requirements: 3.3, 4.4, 6.1, 7.3, 8.1, 8.2_

## Notes

- Tasks marked with `*` are optional — here the test tasks are first-class (TDD), so none are optional; the script tests gate the implementation.
- Each task references specific requirement sub-clauses for traceability.
- No Kotlin/application code is produced; all deliverables are bash + GitHub Actions YAML.
- `workflow-publish.yml` is never modified — the pipeline hands off via the pushed tag.
- Property numbers map to the design's Correctness Properties section.
- Task 9's live validation and `RELEASE_PAT` setup are maintainer actions (repo admin), not coding-agent tasks.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1"] },
    { "id": 1, "tasks": ["2.1", "4.1"] },
    { "id": 2, "tasks": ["2.2", "4.2"] },
    { "id": 3, "tasks": ["2.3", "4.3"] },
    { "id": 4, "tasks": ["6.1"] },
    { "id": 5, "tasks": ["6.2"] },
    { "id": 6, "tasks": ["6.3", "7.1"] },
    { "id": 7, "tasks": ["8"] }
  ]
}
```
