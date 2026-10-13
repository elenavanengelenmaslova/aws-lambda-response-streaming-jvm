# Design Document

## Overview

The Release_Pipeline is new GitHub Actions automation that sits **upstream** of the existing `workflow-publish.yml`. It never publishes. Its job is to turn a merge into `main` that touches the published library into: a pushed `vX.Y.Z` tag, a GitHub Release with generated notes, and a reviewable README-update PR.

The handoff to the existing world is a single event: **pushing a `vX.Y.Z` tag**. That tag push is what fires `workflow-publish.yml` (unchanged). The Release_Pipeline's responsibility ends at producing the tag, the Release, and the PR.

Grounding facts verified in this repo:

- `workflow-publish.yml` triggers on `push` of tags `v*`, validates strict `^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$`, derives `RELEASE_VERSION` from the tag, publishes `streaming-core`. **Do not modify.**
- `ci-main-build.yml` runs on push to `main` (with `paths-ignore: ['**.md','docs/**','.kiro/**']`) and on `pull_request` to `main` (no paths-ignore, so every PR reports the required check).
- `workflow-build.yml` (reusable) is `permissions: contents: read`, checkout `fetch-depth: 0`.
- `README.md` carries the single hardcoded version under `## Dependency`: `implementation("nl.vintik:aws-lambda-streaming-core:2.1.0")`.
- Version is **not** in `gradle.properties` — the tag is the single source of truth.
- `dependabot.yml` groups all updates with commit-message `prefix: chore` + `include: scope`, so Dependabot commits read `chore(deps): ...` / `chore(deps-dev): ...`.

## Topology Decision: Single Release Workflow

**Decision: one workflow, `release-tag.yml`.** A two-workflow layout (Workflow A for the tag plus a tag-triggered Workflow B, `release-publish-extras.yml`, for the Release and README PR) was considered and **rejected**. Implementers use the single `release-tag.yml` workflow and **must NOT** add a tag-triggered extras workflow. The rejected layout is described below for the record.

### Rejected Workflow A — `release-tag.yml` (push to `main`, path-filtered)

```yaml
on:
  push:
    branches: [main]
    paths:
      - 'streaming-core/src/main/**'
      - 'streaming-core/build.gradle.kts'
      - 'gradle/libs.versions.toml'
```

Responsibilities: compute the next version, guard against an existing tag, create and push the tag. **This is the only workflow that reacts to library changes** (Req 1, 2, 3).

### Rejected Workflow B — `release-publish-extras.yml` (push tags `v*`) — do not add

```yaml
on:
  push:
    tags: ['v*']
```

In the rejected layout, Workflow B would create the GitHub Release with generated notes (Req 4) and open the README PR on `release/vX.Y.Z` (Req 5), firing on the same tag push that Workflow A creates.

### The parallelism call-out (important)

The existing `workflow-publish.yml` triggers on `push` tags `v*`. In the chosen design it is the **only** tag-triggered consumer, and it runs in parallel with the remaining jobs of `release-tag.yml`:

```
release-tag.yml: compute → tag (push vX.Y.Z) ─┬─▶ Release + readme-pr jobs (same run)
                                               └─▶ workflow-publish.yml (tag push; existing, untouched)
```

Publishing to Maven Central and creating a GitHub Release / README PR have no ordering dependency on each other; both only depend on the tag existing. Keeping the extras inside `release-tag.yml` keeps the publish workflow single-purpose and avoids coupling the new logic into a file we were told not to touch.

### Alternatives weighed

**Alternative 1 (chosen) — single workflow does everything after computing the tag.** One `release-tag.yml` on push-to-main computes the version, pushes the tag, then (same run) creates the Release and opens the README PR; `workflow-publish.yml` still fires in parallel off the tag push.

- Pro: no reliance on a second tag-triggered run; the Release + PR are guaranteed to run in the same job that already proved the tag was created, so there is no "tag pushed but extras workflow didn't start" gap.
- Pro: simpler mental model and one place to read logs.
- Con: the Release/PR steps don't fire for tags pushed by a human by hand (only for pipeline-computed tags). For this repo that is acceptable — the pipeline is meant to be the only tagging path.

**Alternative 2 (rejected) — two workflows (A: tag, B: tag-triggered extras).**

- Pro: Release/PR are produced for **any** `vX.Y.Z` tag however it was created, matching how `workflow-publish.yml` already behaves (symmetry).
- Con: relies on a second tag-triggered run actually starting; and a `GITHUB_TOKEN`-pushed tag does **not** trigger `on: push` tag workflows (see note below), which would break Workflow B entirely unless the tag is pushed with a Release_PAT.

**Chosen design: single workflow for the tag + extras (Alternative 1), with the publish workflow left as the only separate tag-triggered consumer. Do not add a tag-triggered extras workflow.** Concretely:

- `release-tag.yml` (push to main, path-filtered): compute → guard → push tag → create Release → open README PR, all in one run.
- `workflow-publish.yml` (unchanged): fires off the pushed tag, publishes.

This avoids the fragile "second workflow must re-trigger off the tag" dependency and the `GITHUB_TOKEN`-tag-does-not-trigger trap, while still letting publish run in parallel off the tag. The rest of this document describes `release-tag.yml` as a single multi-job workflow with explicit `needs:` ordering (Req 8).

> Rationale tie-break: the steering/requirements emphasise idempotency and not leaving half-done releases. A single run that owns "tag → Release → PR" with `needs:` ordering is easier to reason about for "did everything happen?" than two runs connected only by a tag event.

## Architecture

`release-tag.yml` is three jobs, strictly ordered with `needs:`:

```
job: compute      (permissions: contents: read)
    └─ outputs: version=vX.Y.Z, bump=major|minor|patch|initial|none, exists= (placeholder)
job: tag          (needs: compute, if bump!='none'; permissions: contents: write)
    └─ preflight RELEASE_PAT, guard, push vX.Y.Z if missing, create/reconcile GitHub Release
job: readme-pr    (needs: [compute, tag], if bump!='none'; permissions: contents: write, pull-requests: write)
    └─ reconcile or create branch release/vX.Y.Z, edit README, commit, open PR
```

Per-job least-privilege permissions (Req 7): `compute` is read-only; only `tag` and `readme-pr` escalate, and only to what they need.

The version computation is a small **bash script** checked into the repo (`scripts/release/compute-version.sh`), invoked by the `compute` job. See "Shell vs Gradle" below.

## Components and Interfaces

| Component | File | Responsibility | Requirements |
|---|---|---|---|
| Trigger / path filter | `release-tag.yml` `on.push.paths` | React only to the three `Library_Source_Paths` | 1, 6 |
| Version computer | `scripts/release/compute-version.sh` | Latest tag → aggregate commits → bump → next `vX.Y.Z` | 2 |
| Tag guard | `tag` job step | Detect an existing tag; skip only the push and reconcile missing Release / PR | 3.4 |
| Tag creator | `tag` job | Create + push `vX.Y.Z` | 3.1, 3.2 |
| Release creator | `tag` job | `gh release create --generate-notes` + non-empty verify | 4, 8.1, 8.4 |
| README editor | `scripts/release/update-readme-version.sh` | Replace trailing version on the coordinate line | 5.2, 8.3 |
| PR opener | `readme-pr` job | Branch + commit + `gh pr create` | 5.1, 5.3, 5.4, 8.2 |

## Version Computation

### Shell vs Gradle

Steering explicitly allows a shell script for CI version computation. The computation is pure git/text work — list tags, enumerate commit subjects, classify prefixes, pick the max bump. A Gradle task would require booting the JVM/Gradle daemon on every push just to read `git log`, which is heavier and slower for no benefit. **Shell (POSIX/bash) is the right tool here.** It stays independently unit-testable (see Testing) and keeps the `compute` job free of JDK/Gradle setup.

### Algorithm (`compute-version.sh`)

Inputs: full history + tags (`fetch-depth: 0`). Output (to `$GITHUB_OUTPUT`): `version`, `bump`, `exists`.

```bash
#!/usr/bin/env bash
set -euo pipefail

# 1. Latest tag. Strict vMAJOR.MINOR.PATCH only, newest by version order.
latest="$(git tag --list 'v*' --sort=-v:refname \
  | grep -E '^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$' \
  | head -n1 || true)"

# Key=value lines go to the GitHub-provided $GITHUB_OUTPUT so the tag job receives them.
# Fall back to stdout when unset (bare manual run); tests point it at a temp file.
GITHUB_OUTPUT="${GITHUB_OUTPUT:-/dev/stdout}"

# 2. No prior tag -> Initial_Version (Req 2.5). Emit all three keys before exiting.
if [ -z "$latest" ]; then
  {
    echo "version=v1.0.0"
    echo "bump=initial"
    echo "exists="
  } >> "$GITHUB_OUTPUT"
  exit 0
fi

# 3. Classify every non-merge commit in latest..HEAD, one commit at a time.
#    The SUBJECT alone decides the type and the `!` marker; the BODY is scanned
#    only for a `BREAKING CHANGE:` footer.
range="${latest}..HEAD"
major=0; minor=0; patch=0

while IFS= read -r sha; do
  subject="$(git log -1 --format='%s' "$sha")"
  body="$(git log -1 --format='%b' "$sha")"

  # `!` immediately before the first `:` of the subject -> breaking (feat!:, fix(core)!: …).
  case "$subject" in
    *":"*)
      type_field="${subject%%:*}"
      case "$type_field" in
        *"!") major=1 ;;
      esac
      ;;
  esac
  case "$subject" in
    feat\(*\):*|feat:*|feat\(*\)!:*|feat!:*)  minor=1 ;;
    fix\(*\):*|fix:*|fix\(*\)!:*|fix!:*)      patch=1 ;;
    # Dependabot scopes -> patch (see "Dependabot bump rule" below)
    chore\(deps\):*|chore\(deps-dev\):*|build\(deps\):*|build\(deps-dev\):*) patch=1 ;;
  esac

  # Footer scan: a body line starting with `BREAKING CHANGE:` -> major.
  if printf '%s\n' "$body" | grep -q '^BREAKING CHANGE:'; then
    major=1
  fi
done < <(git log --no-merges --format='%H' "$range")

# 4. Max-severity precedence: major > minor > patch (Req 2.7).
IFS='.' read -r cur_major cur_minor cur_patch <<< "${latest#v}"
if   [ "$major" = 1 ]; then next="v$((cur_major + 1)).0.0";                        bump="major"
elif [ "$minor" = 1 ]; then next="v${cur_major}.$((cur_minor + 1)).0";             bump="minor"
elif [ "$patch" = 1 ]; then next="v${cur_major}.${cur_minor}.$((cur_patch + 1))";  bump="patch"
else                        next="";                                               bump="none"
fi

# 5. Emit the result to $GITHUB_OUTPUT. `bump=none` carries an empty version by contract;
#    `exists=` is a placeholder — the tag job computes the real existence guard.
{
  echo "version=${next}"
  echo "bump=${bump}"
  echo "exists="
} >> "$GITHUB_OUTPUT"
```

**Why per-commit, subject-only classification:** an earlier draft fed `git log --format='%s%n%b'` through a single line-by-line loop, so every body line was classified as if it were a subject. A squash-merge body listing `feat: …` sub-commits, or prose such as `fix: typo` in a body, inflated the bump. Reading subject and body separately per SHA confines type/`!` detection to the subject and limits the body to the `BREAKING CHANGE:` footer check (Req 2.9).

Parsing rules, aligned to requirements:

- **Major** (Req 2.2, 2.9): any commit whose **subject** type carries `!` before the colon (`feat!:`, `fix!:`, `fix(core)!:`, `refactor!:` …) **or** whose body has a line starting `BREAKING CHANGE:`.
- **Subject vs body** (Req 2.9): type and `!` come from the subject alone; body lines are never classified as subjects.
- **Minor** (Req 2.3): any `feat:` / `feat(scope):` and no major present.
- **Patch** (Req 2.4): any `fix:` / `fix(scope):` and no minor/major present.
- **Aggregate set** (Req 2.6, 2.7): every non-merge commit in `latest..HEAD` is classified, including Dependabot commits, then the **highest** bump wins (`major > minor > patch`).
- **Initial** (Req 2.5): no prior strict tag → `v1.0.0`.
- **Output form** (Req 2.8): always `vMAJOR.MINOR.PATCH`.

### Dependabot bump rule (resolving the Req 2 contradiction)

**The contradiction:** `dependabot.yml` uses `commit-message.prefix: chore` + `include: scope`, so dependency upgrades arrive as `chore(deps): ...` / `chore(deps-dev): ...` (and the development variant). Under strict Conventional Commits, `chore` maps to **no bump** — but Req 2 wants dependency upgrades to be able to cut a release, and Req 2.6/2.7 require Dependabot commits to be treated identically to all others under the standard rules.

**Two options:**

1. **Treat `chore(deps)` / `chore(deps-dev)` / `build(deps)` scopes as a patch bump** in `compute-version.sh`. No change to the working Dependabot config.
2. **Change Dependabot's `commit-message.prefix`** to `fix` (or `build`) so upgrades map to a bump under vanilla rules.

**Recommendation: Option 1 (lower risk).** Deriving a patch bump from the `deps`/`deps-dev` scope keeps the working, GitHub-validated `dependabot.yml` untouched (recall its header comments document a prior invalid-config rejection — minimising edits there is deliberately safe). The rule lives in one place (the version script) and is unit-testable.

**Tradeoff / cost of Option 1:** the release pipeline now encodes a repo-specific convention (`chore(deps)` → patch) that is **not** standard Conventional Commits, so a reader of the script must know it. It is documented inline in the script and in `docs/log.md`. A hand-written `chore:` with no `deps`/`deps-dev` scope still correctly maps to no bump, so only dependency-scoped chores are promoted — matching Req 2's intent that dependency upgrades cut a (patch) release. This gotcha (chore→no-bump vs the deps-scope promotion) is recorded in `docs/log.md`.

## Data Models

The pipeline carries a small set of typed values between jobs rather than persistent data structures. These are the models that flow through `release-tag.yml`:

| Model | Shape | Produced by | Consumed by |
|---|---|---|---|
| `version` | string, strict `vMAJOR.MINOR.PATCH` (e.g. `v2.2.0`) | `compute` job (`compute-version.sh`) | `tag`, `readme-pr` jobs |
| `bump` | enum: `major` \| `minor` \| `patch` \| `initial` \| `none` | `compute` job | `tag` job (gate: skip when `none`) |
| `exists` | boolean (`compute` emits an empty placeholder; the real value comes from the guard step) | `tag` job guard (`git rev-parse`) | `tag` job: when true, skip only the tag create/push and reuse the existing tag to reconcile any missing Release / README PR — not a skip-everything signal |
| `README_Artifact_Version` | the trailing `X.Y.Z` of the coordinate `nl.vintik:aws-lambda-streaming-core:X.Y.Z` in `README.md` | `update-readme-version.sh` | the README PR |
| `Release body` | GitHub-generated notes string, must be non-empty | `gh release create --generate-notes` | published Release |

All inter-job values are passed via `$GITHUB_OUTPUT`; none are persisted beyond the workflow run except the tag, the Release, and the PR branch.

## Idempotency / Guard

- The `compute` job and `tag` job run with `fetch-depth: 0` so **all tags and full history** are present (without it, `git tag --list` and `latest..HEAD` are unreliable).
- `bump == none`: the `tag` and `readme-pr` jobs are skipped via `if: needs.compute.outputs.bump != 'none'` — nothing to release.
- Guard (Req 3.4): the `tag` job checks `git rev-parse -q --verify "refs/tags/$version"`. An existing tag is **not** treated as a completed release; it means an earlier run got at least as far as the tag push. The run reuses the tag and reconciles only what is missing:
  - **Tag:** the create-and-push step runs only when `exists == false`; an existing tag is never re-created or overwritten.
  - **Release:** if a published Release exists, leave it alone; if a leftover draft exists, verify its body and publish it; otherwise create it with `gh release create --verify-tag --generate-notes --draft` and then verify + publish.
  - **README PR (`readme-pr` job):** if `gh pr list --head release/vX.Y.Z --state all` finds a PR (open, closed or merged), do nothing; if the `release/vX.Y.Z` branch is already pushed but has no PR, reuse it and only open the PR; otherwise create the branch, edit, commit, push, and open the PR.
  - When the tag, the published Release and the PR all exist, the run makes no changes. Re-runs are safe and never duplicate a tag, Release or PR.

## Release Notes Failure Handling (Req 4.4)

Create the Release **as a draft first**, verify the body is non-empty, then publish:

```bash
# 1. Reconcile: published Release -> leave alone; leftover draft -> verify it; else create the draft.
if is_draft="$(gh release view "$version" --json isDraft --jq '.isDraft' 2>/dev/null)"; then
  [ "$is_draft" = "false" ] && exit 0
else
  gh release create "$version" --verify-tag --generate-notes --draft --title "$version"
fi

# 2. Fetch the generated body and verify it is non-empty.
body="$(gh release view "$version" --json body --jq '.body')"
if [ -z "${body//[[:space:]]/}" ]; then
  echo "::error::generated release notes are empty for $version"
  gh release delete "$version" --yes --cleanup-tag=false   # keep the tag; publish ran off it
  exit 1
fi

# 3. Publish only after the body is confirmed non-empty.
gh release edit "$version" --draft=false
```

This guarantees we never leave a **published** Release with a missing/empty body (Req 4.4). The draft approach is preferred over "create then inspect a live release" because a draft is not visible to consumers while being validated. We delete only the draft Release on failure — the tag stays (publish already consumed it), and the maintainer can add notes manually. No `CHANGELOG.md` is created or committed (Req 4.3).

## README PR Mechanics

### The edit (`update-readme-version.sh`)

Anchor on the coordinate, replace only the trailing version — never a blind line-number edit:

```bash
version_no_v="${version#v}"
# Match the coordinate, capture everything up to and including the final ':', swap the version.
sed -E -i.bak \
  's#(nl\.vintik:aws-lambda-streaming-core:)[0-9]+\.[0-9]+\.[0-9]+#\1'"${version_no_v}"'#' \
  README.md
rm -f README.md.bak
# Fail loudly if nothing changed (coordinate moved/renamed).
git diff --quiet -- README.md && { echo "::error::README version anchor not found"; exit 1; }
```

Anchoring on `nl.vintik:aws-lambda-streaming-core:` is resilient to the line moving; the trailing `X.Y.Z` is the only thing replaced (Req 5.2, 8.3).

### Branch + PR

```bash
branch="release/${version}"
git switch -c "$branch"
git commit -am "chore: update README dependency version to ${version_no_v}"
git push origin "$branch"
gh pr create --base main --head "$branch" \
  --title "docs: README version ${version}" \
  --body "Automated README dependency-version bump for ${version}. Docs-only; does not re-trigger a release."
```

(Req 5.1, 5.3, 5.4.) Note the commit message is `chore:` with **no** `deps` scope and is a **README-only** change, so even on merge it carries no bump and — more importantly — doesn't touch a trigger path.

### Loop guard (Req 6)

The README PR edits only `README.md`. The `release-tag.yml` trigger is `paths: [streaming-core/src/main/**, streaming-core/build.gradle.kts, gradle/libs.versions.toml]`. A README-only merge touches **none** of the three paths, so the path filter excludes it and no release run starts (Req 6.1, 6.2). This is a structural guarantee from the trigger, not a runtime check — the strongest kind.

**Why the version catalog is a trigger path.** `gradle/libs.versions.toml` is included so a Dependabot dependency bump — which edits only the shared catalog and no `.kt` file — still cuts a release. This matches the repo's intent that dependency upgrades produce releases (and lines up with the `chore(deps)` → patch rule above). Accepted tradeoff: because the catalog is shared across all three modules, a bump that affects only the example/test/tooling modules (not the published `streaming-core`) will also trigger a release. The loop guard is unaffected — the README-only PR edits only `README.md`, which is still none of the three trigger paths, so the structural guarantee holds.

### Branch protection + Release_PAT (Req 7.3)

**PR behaviour with `GITHUB_TOKEN`:** per current GitHub docs, a PR created by a workflow using the default `GITHUB_TOKEN` produces `pull_request` runs in an **approval-required** state. A user with write access can start them via **"Approve workflows to run"** on the PR. So if branch protection on `main` requires `ci-main-build.yml`'s PR check, a `GITHUB_TOKEN`-opened README PR waits for that manual approval before the check runs. Using a PAT avoids the approval step, but it is not the only way to start the checks.

**Tag behaviour with `GITHUB_TOKEN`:** a tag pushed with `GITHUB_TOKEN` does **not** trigger `on: push` tag workflows. In the chosen single-workflow design this does not affect the Release (created in-run), **but** `workflow-publish.yml` would never fire, leaving a tag and Release with no published artifact. Therefore **the tag push must use the Release_PAT**.

**Design — RELEASE_PAT is REQUIRED (no `GITHUB_TOKEN` fallback anywhere):**

- A preflight step is the first step of the `tag` job, before checkout and tagging. `secrets` is not usable in `if:`, so the step maps the secret into `env` and tests emptiness; if empty it fails with `::error::` and nothing is written. The value is never echoed:
  ```yaml
  - name: Preflight — RELEASE_PAT configured?
    env:
      RELEASE_PAT: ${{ secrets.RELEASE_PAT }}
    run: |
      if [ -z "${RELEASE_PAT:-}" ]; then
        echo "::error::RELEASE_PAT is not configured. ..."
        exit 1
      fi
  ```
- Checkout in both `tag` and `readme-pr` uses the PAT, so the tag push and branch push are authenticated with it:
  ```yaml
  - uses: actions/checkout@v4
    with:
      fetch-depth: 0
      token: ${{ secrets.RELEASE_PAT }}
  ```
  and `GH_TOKEN: ${{ secrets.RELEASE_PAT }}` for `gh` (Release creation, PR creation) (Req 3.5, 3.6, 7.3).
- `contents: write` (Req 7.1) for tags/branches/Release; `pull-requests: write` (Req 7.2) for the PR.

> Document in `docs/log.md`: "GITHUB_TOKEN-pushed tags do not trigger tag workflows, and GITHUB_TOKEN-opened PRs get approval-required pull_request runs — RELEASE_PAT is required so publish fires and the README PR's checks start without approval."

## Error Handling

- `set -euo pipefail` in every script step; fail fast on git/`gh` errors.
- Never echo `RELEASE_PAT` / `GITHUB_TOKEN` / Maven/GPG secrets. Tokens flow only through `env`/`with.token`; the version script handles no secrets.
- Missing `RELEASE_PAT` fails the `tag` job's preflight with `::error::` before checkout, tagging, or Release creation (Req 3.6). There is no `GITHUB_TOKEN` fallback, so a tag is never pushed that would fail to trigger `workflow-publish.yml`.
- Least-privilege per-job permissions (Req 7): `compute` read-only; `tag`/`readme-pr` escalate narrowly.
- The tag guard prevents duplicate/overwritten tags; the draft-then-verify prevents empty published Releases.
- `--no-merges` in `git log` avoids double-counting merge commits.

## Correctness Properties

These universal properties hold for any input and are what the test layers assert.

### Property 1: Max-severity bump
For any aggregate commit set since the latest tag, the computed `bump` equals the highest-precedence classification present (`major > minor > patch`), independent of commit order or count.

**Validates: Requirements 2.6, 2.7**

### Property 2: Strict output form
The computed `version` always matches `^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$`.

**Validates: Requirements 2.8**

### Property 3: Monotonic increase
The computed `version` is strictly greater (SemVer order) than the latest existing tag, or is the `Initial_Version` when none exists.

**Validates: Requirements 2.1, 2.5**

### Property 4: README round-trip
After `update-readme-version.sh` runs for `vX.Y.Z`, the coordinate line parses back to exactly `nl.vintik:aws-lambda-streaming-core:X.Y.Z`, and exactly one line changes.

**Validates: Requirements 5.2, 8.3**

### Property 5: Idempotency
For any replay of the same push, the computed version is the same; if that tag already exists the pipeline never re-creates or pushes it, creates only the missing GitHub Release and/or README PR (never duplicates), and makes no changes when both already exist.

**Validates: Requirements 3.4**

### Property 6: Loop exclusion
A change set touching only `README.md` matches none of the three trigger paths, so it can never start a release run.

**Validates: Requirements 6.1, 6.2**

### Property 7: No empty Release
A published GitHub Release always has a non-empty body; an empty generated body fails the step with no published Release left behind.

**Validates: Requirements 4.4**

## Testing Strategy

Mirroring steering's testing norms (layered; cheap unit tests over live runs; property-style parameterized cases where a universal rule exists):

### 1. Version computation — unit-test the shell script

`compute-version.sh` is pure given a tag + a commit list, so test it directly (e.g. `bats`, or a plain bash harness invoked from a Gradle `exec`/CI step) over curated commit sets:

- `feat:` only → minor; `fix:` only → patch; `feat!:` → major; `BREAKING CHANGE:` footer → major.
- Mixed set `{fix:, feat:, chore(deps):}` → **minor** (max precedence).
- Mixed set `{fix:, feat!:}` → **major**.
- `chore(deps):` only → **patch** (Dependabot rule); plain `chore:` only → **none**.
- No prior tag → `v1.0.0`.
- Existing tag `v2.1.0` + `fix:` → `v2.1.1`; + `feat:` → `v2.2.0`; + `feat!:` → `v3.0.0`.
- Scoped breaking subject `fix(scope)!:` → **major**.
- Subject-only classification (Req 2.9): a `fix:` commit whose body contains `feat: …` or `x!: …` lines (e.g. a squash-merge listing) → stays **patch**; a plain `chore:` commit whose body contains `fix: …` prose → stays **none**.
- This is a natural property set: *for any* aggregate commit set, output bump = max precedence of per-commit classifications. Use 10–20 diverse fixtures.

### 2. README edit — unit-test `update-readme-version.sh`

- *For any* version `vX.Y.Z`, running the script leaves exactly one changed line and the coordinate line equals `nl.vintik:aws-lambda-streaming-core:X.Y.Z` (round-trip: parse the version back out of the edited line).
- Anchor-missing fixture (renamed coordinate) → script exits non-zero.

### 3. Path filter + loop guard — static assertion

- Assert `release-tag.yml`'s `on.push.paths` is exactly the three Library_Source_Paths (a `yq`/grep check in CI), proving docs/example/tooling pushes don't trigger (Req 1) and a README-only merge can't (Req 6).

### 4. Release-notes guard — logic test

- Simulate empty generated notes (mock `gh release view` body = whitespace) → the step exits non-zero and the draft is deleted; non-empty body → publishes. Validated without a live release by factoring the verify logic into a small testable function.

Full end-to-end (real tag → real publish ∥ real Release + PR) is proven once against the live repo, mirroring how streaming is only fully proven post-deploy. The unit layers above cover everything that varies with input without a live release.

## End-to-End Flow

```mermaid
sequenceDiagram
    participant Dev as Maintainer
    participant Main as main branch
    participant RT as release-tag.yml
    participant Script as compute-version.sh
    participant Tag as vX.Y.Z tag
    participant Pub as workflow-publish.yml
    participant GH as GitHub Release
    participant PR as README PR (release/vX.Y.Z)

    Dev->>Main: merge change to streaming-core/src/main/** or build.gradle.kts
    Main->>RT: push event (path filter matches)
    RT->>Script: compute (fetch-depth 0: tags + history)
    Script-->>RT: version=vX.Y.Z, bump
    RT->>RT: bump==none? preflight RELEASE_PAT, guard — tag exists?
    alt bump == none
        RT-->>Main: skip tag + readme-pr jobs (nothing to release)
    else tag already exists (reconcile)
        RT->>RT: skip tag push, reuse existing vX.Y.Z
        RT->>GH: published → leave alone; draft → verify + publish; missing → create --verify-tag, verify, publish
        RT->>PR: PR exists (state all) → nothing; branch pushed w/o PR → open PR; else branch + commit + push + PR
        Note over RT: all present → no changes
    else new version
        RT->>Tag: create + push vX.Y.Z (RELEASE_PAT so publish fires)
        par publish and extras run in parallel off the tag
            Tag->>Pub: tag push triggers publish
            Pub->>Pub: validate SemVer, publish streaming-core to Maven Central
        and
            RT->>GH: gh release create --verify-tag --generate-notes --draft
            GH-->>RT: body
            RT->>RT: verify body non-empty, else delete draft + fail
            RT->>GH: publish Release
            RT->>PR: branch release/vX.Y.Z, edit README, commit, open PR
        end
        Dev->>PR: review
        PR->>Main: merge README-only change
        Main-->>RT: NOT re-triggered (README path excluded by filter)
    end
```

## Requirements Traceability

| Design section | Requirements |
|---|---|
| Topology Decision (two consumers of the tag; single release workflow) | 1, 3, 4, 5, 8 |
| Trigger / path filter | 1.1, 1.2, 1.3 |
| Version Computation (algorithm) | 2.1, 2.2, 2.3, 2.4, 2.5, 2.8 |
| Dependabot bump rule | 2.6, 2.7 |
| Idempotency / Guard | 3.4 |
| Tag creator (`tag` job) | 3.1, 3.2; 3.3 (never publishes) |
| Release Notes Failure Handling | 4.1, 4.2, 4.3, 4.4, 8.1, 8.4 |
| README PR Mechanics (edit) | 5.2, 8.3 |
| README PR Mechanics (branch + PR) | 5.1, 5.3, 5.4, 8.2 |
| Loop guard | 6.1, 6.2 |
| Branch protection + Release_PAT; per-job permissions | 7.1, 7.2, 7.3 |
| Architecture (job ordering via `needs:`) | 8.1, 8.2 |
| Error Handling & Security | 3.3, 4.4, 7 |
| Testing | 1, 2, 4, 6 (validation of) |
