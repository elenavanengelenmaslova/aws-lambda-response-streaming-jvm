# Implementation Plan: quality-badges-and-dependabot

## Overview

The work lands in the order the design's five blocks demand: **signal before badge, resolvability before automation, artefacts before the documentation that enumerates them, everything before the verification script that checks it.**

Two ordering constraints override convenience:

1. **The version catalog migration is bracketed by a proof.** Requirement 9.3 demands resolved coordinates be provably unchanged, so the baseline tooling lands first (task 1), the baseline is captured from the still-unmigrated tree, the migration happens (task 2), and the diff runs after (task 3). The design's `git stash` procedure is replaced by this ordering: because `resolvedCoordinates` lands in its own task before any declaration changes, the tree at task 1.2 **is** the pre-migration tree and no stash is needed.
2. **`docs/log.md` precedes the verification script.** Property 4 reads its exception allow-list from `docs/log.md`, and the script's pending-badge list must agree with the log's pending entries. So knowledge capture (task 14) comes before the script (task 15), inverting the usual "docs last" instinct.

The repo is never left broken between tasks. Inside task 2 that means modules move to catalog accessors **while the root still holds `extra[…]`** (the entries simply go unread), and the root's `extra` block is deleted last. Every task that touches Gradle ends with `./gradlew build -PexcludeTags=integration` exiting 0, with the gates unchanged at 90% for `:streaming-core` and 80% for each example module.

**Human-only actions are not coding tasks.** Creating the Codecov account, storing `CODECOV_TOKEN`, creating the three repository labels, enabling the dependency graph / Dependabot alerts / security updates / code scanning, and installing the Snyk and CodeRabbit apps cannot be done by an agent. Task 12.1 has the agent **write** that checklist into `CONTRIBUTING.md`; the maintainer **executes** it afterwards. No task attempts the action itself.

**Post-merge-only verification.** Badge rendering, the first Codecov upload, CodeQL results appearing in the Security tab, the dependency-graph submission, Dependabot accepting its config, and the first Dependabot pull request cannot be proven before merge. Each is called out on the task that produces it and collected in task 16.

## Tasks

- [x] 1. Dependency-resolution baseline tooling and capture

  - [x] 1.1 Add the `resolvedCoordinates` task and the baseline capture script
    - In the root `build.gradle.kts`, register `resolvedCoordinates` per subproject exactly as design section 2 ("Capturing the Version_Catalog_Baseline") specifies: inside `subprojects { plugins.withId("java") { … } }` — not a bare `subprojects {}` body, because the root script is evaluated before the subprojects and `configurations` is empty at that point.
    - Walk `configurations.named(name).flatMap { it.incoming.resolutionResult.rootComponent }` for `compileClasspath`, `runtimeClasspath`, `testCompileClasspath`, `testRuntimeClasspath`; collect every `ModuleComponentIdentifier`; sort; write `"<config>  <group>:<name>:<version>"` lines to `build/reports/resolved-coordinates/<project.name>.txt`. Capture the providers outside `doLast` so the task stays configuration-cache safe.
    - Create `scripts/dependency-baseline.sh <label>`: runs `./gradlew --quiet resolvedCoordinates -PexcludeTags=integration`, then copies `*/build/reports/resolved-coordinates/*.txt` into `build/dependency-baseline/<label>/`. Make it executable.
    - Do not touch any dependency declaration in this task — registering a task must not change resolution.
    - _Design: section 2 "Capturing the Version_Catalog_Baseline"; Testing Strategy Layer 1_
    - _Requirements: 9.3, 9.10_
    - **Verify:** `./gradlew resolvedCoordinates -PexcludeTags=integration` exits 0 and produces three non-empty files; `./gradlew build -PexcludeTags=integration` exits 0.

  - [x] 1.2 Capture the pre-migration Version_Catalog_Baseline
    - Run `./scripts/dependency-baseline.sh before` against the current, unmigrated tree.
    - Confirm `build/dependency-baseline/before/` holds one file per module (`streaming-core`, `streaming-s3-example`, `streaming-s3-example-java`), each listing all four configurations.
    - Keep the directory out of git (it is under `build/`); the artefact is consumed by task 3.1 in the same working tree.
    - _Design: section 2 "Capturing the Version_Catalog_Baseline"_
    - _Requirements: 9.3, 9.10_
    - **Verify:** three files present and non-empty; spot-check that `aws.sdk.kotlin:s3:1.6.59` and `org.junit.jupiter:junit-jupiter:6.0.0` appear.

- [x] 2. Version catalog and build-file migration

  - [x] 2.1 Create `gradle/libs.versions.toml`
    - Write the catalog exactly as design section 2 "Target `gradle/libs.versions.toml`" specifies: `[versions]` (22 entries incl. the six literals promoted from build files — `kotlin`, `shadow`, `kover`, `mavenPublish`, `bcv`, `jacoco`, `slf4j`), `[libraries]`, the single `integration-testing` `[bundles]` entry, and `[plugins]`.
    - Carry `awsLambdaEvents` even though no module uses it (Requirement 9.1 names all 15 entries; removing it is a separate follow-up).
    - Keep the design's comments verbatim in intent: the header stating why the catalog exists rather than `extra[…]`, the `flociImage` note that it is a Docker tag Dependabot cannot bump, the `jacoco-agent` note that the library entry exists only so Dependabot sees the coordinate, and the two versionless entries (`aws-sdk-java-s3` from the BOM, `junit-platform-launcher` from `junit-jupiter`).
    - Version values must be byte-identical to today's `extra[…]` values and build-file literals.
    - _Design: section 2 "The 15 `extra` entries being migrated", "Target `gradle/libs.versions.toml`"_
    - _Requirements: 9.1, 9.5, 9.6_
    - **Verify:** `./gradlew help` exits 0 (catalog parses, aliases generate); `./gradlew build -PexcludeTags=integration` exits 0 — nothing consumes the catalog yet, so the build must be untouched.

  - [x] 2.2 Migrate `streaming-core/build.gradle.kts` to catalog accessors
    - Replace the three `rootProject.extra["…"]` interpolations with `libs.kotlinx.serialization.json`, `libs.junit.jupiter`, `libs.mockk`; keep `testRuntimeOnly(libs.junit.platform.launcher)` versionless.
    - Switch only the two module-local plugins to aliases: `alias(libs.plugins.maven.publish)` and `alias(libs.plugins.bcv)`. Leave `kotlin("jvm")`, `kotlin("plugin.serialization")` and `id("org.jetbrains.kotlinx.kover")` versionless — they are on the inherited script classpath and re-adding a version fails with "Plugin request for plugin already on the classpath must not include a version".
    - Leave `version = providers.gradleProperty("releaseVersion")…`, the Java 21 toolchain, `explicitApi()`, and the 90% Kover bound untouched.
    - _Design: section 2 "How plugin versions move"_
    - _Requirements: 9.2, 9.4, 9.6_
    - **Verify:** `./gradlew :streaming-core:build -PexcludeTags=integration` exits 0 with the 90% gate satisfied; `./gradlew :streaming-core:apiCheck` exits 0.

  - [x] 2.3 Migrate `streaming-s3-example/build.gradle.kts` to catalog accessors
    - Replace every `rootProject.extra["…"]` interpolation with the matching accessor, using `libs.bundles.integration.testing` for the three Testcontainers/Floci test dependencies and `libs.aws.sdk.kotlin.s3` / `.lambda` / `.apigateway` for the three Kotlin-SDK coordinates.
    - Promote the `org.slf4j:slf4j-simple:2.0.16` literal to `libs.slf4j.simple`.
    - Compose the emulator image as `systemProperty("floci.image", "floci/floci:${libs.versions.flociImage.get()}")` — the image name stays in the build file, the tag comes from the catalog.
    - Leave the 80% Kover bound and the Java 25 toolchain untouched.
    - _Design: section 2 "The 15 `extra` entries being migrated", "Non-Maven and versionless exceptions"_
    - _Requirements: 9.2, 9.4, 9.5, 9.8_
    - **Verify:** `./gradlew :streaming-s3-example:build -PexcludeTags=integration` exits 0 with the 80% gate satisfied.

  - [x] 2.4 Migrate `streaming-s3-example-java/build.gradle.kts` to catalog accessors
    - Replace every `rootProject.extra["…"]` interpolation with accessors, including `implementation(platform(libs.aws.sdk.java.bom))` followed by the versionless `libs.aws.sdk.java.s3`, both Mockito entries, and `libs.bundles.integration.testing`.
    - Promote `org.slf4j:slf4j-simple:2.0.16` to `libs.slf4j.simple` and `jacoco.toolVersion = "0.8.14"` to `libs.versions.jacoco.get()`.
    - Compose `floci.image` from `libs.versions.flociImage.get()` as in 2.3.
    - Leave the JaCoCo 80% rule, the `xml.required.set(true)` report config (Requirement 4.2 needs no change here), and the `koverVerify`/`koverHtmlReport` aliases exactly as they are.
    - _Design: section 2 "The 15 `extra` entries being migrated"_
    - _Requirements: 9.2, 9.4, 9.5, 4.2, 4.5_
    - **Verify:** `./gradlew :streaming-s3-example-java:build -PexcludeTags=integration` exits 0 with the 80% JaCoCo gate satisfied.

  - [x] 2.5 Strip the root `build.gradle.kts` of versions and switch its plugins to aliases
    - Replace the four literal plugin versions with `alias(libs.plugins.kotlin.jvm) apply false`, `alias(libs.plugins.kotlin.serialization) apply false`, `alias(libs.plugins.shadow) apply false`, `alias(libs.plugins.kover) apply false`.
    - Delete all 15 `extra["…"]` version entries. No `extra["` or `rootProject.extra[` occurrence may remain in the root or the three module build files.
    - Replace the stale header comment ("Versions are the single source of truth; bump here and subprojects pick them up via rootProject.extra") with a comment stating that versions are declared in `gradle/libs.versions.toml`.
    - Preserve the explanatory prose currently attached to the Floci and Java-example entries by moving it into the catalog's comments (done in 2.1) rather than dropping it.
    - Leave `settings.gradle.kts` alone — the foojay-resolver `version "0.9.0"` literal stays, because settings scripts have no catalog accessors and Dependabot reads that literal fine.
    - Keep the `resolvedCoordinates` registration from task 1.1 intact.
    - _Design: section 2 "How plugin versions move", "Non-Maven and versionless exceptions"_
    - _Requirements: 9.1, 9.2, 9.5, 9.6, 9.8_
    - **Verify:** `grep -rn 'extra\["' build.gradle.kts streaming-*/build.gradle.kts` returns nothing; `./gradlew build -PexcludeTags=integration` exits 0.

- [x] 3. Prove the migration changed declaration form only

  - [x] 3.1 Capture the post-migration baseline and diff it against `before`
    - **Property 5: Migrating declaration form is the identity on resolution**
    - Run `./scripts/dependency-baseline.sh after`, then `diff -ru build/dependency-baseline/before build/dependency-baseline/after`. The diff must be empty — zero additions, removals or version changes across all three modules and all four configurations.
    - Run `./gradlew :streaming-core:apiCheck` to confirm the checked-in public API dump is unchanged.
    - If the diff is non-empty, correct the catalog (do not accept the drift) and record the difference and the correction in `docs/log.md` as part of task 14.1.
    - **Validates: Requirements 9.3, 9.10**
    - **Verify:** empty `diff -ru` output, `apiCheck` exits 0, `./gradlew build -PexcludeTags=integration` exits 0.

  - [x] 3.2 Confirm the Java module's Kover-named aliases still resolve
    - Run `./gradlew :streaming-s3-example-java:koverVerify :streaming-s3-example-java:koverHtmlReport --dry-run` and confirm the task graph resolves to `jacocoTestCoverageVerification` and `jacocoTestReport`.
    - _Design: Testing Strategy Layer 1 "Task aliases intact"_
    - _Requirements: 4.5_

- [x] 4. Checkpoint - migration verified
  - Ensure all tests pass, ask the user if questions arise.
  - The baseline diff must be empty and `./gradlew build -PexcludeTags=integration` must exit 0 before any workflow or badge work begins. Everything downstream assumes a resolvable, Dependabot-readable build.

- [x] 5. Coverage aggregation

  - [x] 5.1 Add the root `verifyCoverageReports` task
    - Register `verifyCoverageReports` in the root `build.gradle.kts`, `dependsOn` `:streaming-core:koverXmlReport`, `:streaming-s3-example:koverXmlReport`, `:streaming-s3-example-java:jacocoTestReport`.
    - Assert, for each of the three verified paths — `streaming-core/build/reports/kover/report.xml`, `streaming-s3-example/build/reports/kover/report.xml`, `streaming-s3-example-java/build/reports/jacoco/test/jacocoTestReport.xml` — that the file exists, parses, contains at least one `<class>` element, and reports a non-zero LINE `covered` count. Fail the build naming the offending module.
    - **Disable external DTD loading in the XML reader.** The JaCoCo report carries `<!DOCTYPE report PUBLIC … "report.dtd">` and `report.dtd` is not next to the XML, so a default `DocumentBuilder` either fails or reaches out to the network.
    - Write the three resolved paths to `build/reports/coverage-report-paths.txt` so the cross-file path check in task 15.6 has something to compare against.
    - Do not alter any coverage gate: 90% `:streaming-core`, 80% `:streaming-s3-example`, 80% `:streaming-s3-example-java` via its existing alias.
    - _Design: section 3 "Coverage aggregation"; Data Models "Coverage report contract"_
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.7_
    - **Verify:** `./gradlew verifyCoverageReports koverVerify koverHtmlReport -PexcludeTags=integration --continue` exits 0, all three XML reports exist at the paths above, and `build/reports/coverage-report-paths.txt` lists exactly those three.

  - [x] 5.2 Prove `verifyCoverageReports` fails on a missing or empty report
    - Delete one XML report after generation, re-run the task alone, and confirm a non-zero exit whose message names the offending module. Restore the report afterwards.
    - Repeat with a truncated/zero-coverage report to exercise the "no class entries / zero covered lines" branch.
    - _Design: Testing Strategy Layer 2 "proven once against a deliberate failure"_
    - _Requirements: 4.7_

- [x] 6. Licence alignment

  - [x] 6.1 Declare MIT in the `streaming-core` publication POM
    - In `streaming-core/build.gradle.kts`, replace the POM `licenses { license { name = "Apache-2.0"; url = "https://www.apache.org/licenses/LICENSE-2.0" } }` block with `name = "MIT"` and `url = "https://opensource.org/licenses/MIT"`.
    - Leave `LICENSE` untouched — it is the authoritative source (Requirement 12.4).
    - The log entry covering the mismatch, the identifier kept, the files changed, and the fact that already-published versions keep the Apache-2.0 POM immutably on Maven Central is written in task 14.1.
    - _Design: section 12 "Licence resolution"_
    - _Requirements: 12.3, 12.4, 12.8_
    - **Verify:** `./gradlew :streaming-core:generatePomFileForMavenPublication` exits 0 and the generated POM contains `<name>MIT</name>`; `git diff --exit-code LICENSE` exits 0; `./gradlew build -PexcludeTags=integration` exits 0.

- [x] 7. Codecov configuration and upload wiring

  - [x] 7.1 Create `codecov.yml`
    - Write the repository-root file exactly as design section 4 specifies: `codecov.require_ci_to_pass: true`; `coverage.precision: 2`; `round: down`; project status target 80% with a 1% threshold; patch target 80%; `ignore` listing `**/test/**`, `**/build/**`, `**/bin/**`, `deployment/**`.
    - Keep the comment stating that 80% matches the **lowest** per-module gate and that `:streaming-core`'s stricter 90% stays the build's responsibility, not Codecov's (Requirement 5.7).
    - _Design: section 4 "Codecov wiring"_
    - _Requirements: 5.6, 5.7_
    - **Verify:** the file parses as YAML; `**/bin/**` is present (the gitignored Eclipse trees the design calls out).

  - [x] 7.2 Wire coverage and Codecov into `workflow-build.yml`
    - Add an optional secret to the `workflow_call` declaration: `CODECOV_TOKEN` with `required: false` and the description from the design. `required: false` is what lets `ci-feature-build.yml` and `cd-deploy-on-demand.yml` stay untouched. Do not use `secrets: inherit`.
    - Add `timeout-minutes: 30` to both the `test` and `validate-sam` jobs. This is where Requirements 3.10 and 11.7 land — `timeout-minutes` is unsupported on a job that calls a reusable workflow, so the thin callers cannot carry it.
    - Replace the current per-module coverage command with the single invocation `./gradlew verifyCoverageReports koverVerify koverHtmlReport -PexcludeTags=integration --continue`. `--continue` is what keeps reports on disk when a gate fails, which Requirements 4.8 and 5.1 need together.
    - Add the `Determine coverage-upload eligibility` step (`id: cov`) exactly as designed: gated on `!cancelled() && github.event_name == 'push' && github.ref == 'refs/heads/main'`, mapping the token into `env` (the `secrets` context is unavailable in `if:`), emitting `::error::` per missing/empty report, emitting a `::notice::` naming the checklist item when the token is absent, and setting `eligible` / `reports-missing` outputs. Never echo the token value.
    - Add the `codecov/codecov-action@v5` step: `if: ${{ !cancelled() && steps.cov.outputs.eligible == 'true' }}`, `timeout-minutes: 10`, `continue-on-error: true`, `fail_ci_if_error: true`, and the three-file `files:` list matching `build/reports/coverage-report-paths.txt` byte for byte.
    - Keep the existing HTML artefact upload and both `sam validate` steps.
    - _Design: section 4 "Codecov wiring in `workflow-build.yml`"; Data Models "Timeout table"_
    - _Requirements: 4.6, 5.1, 5.2, 5.3, 5.4, 5.5, 5.9, 5.10, 3.10, 11.4, 11.5, 11.7_
    - **Verify:** the file parses as YAML; the `files:` list matches the three paths from task 5.1; `./gradlew verifyCoverageReports koverVerify koverHtmlReport -PexcludeTags=integration --continue` exits 0 locally. **Post-merge only:** the first successful upload and the badge turning from "unknown" to a percentage — it needs `CODECOV_TOKEN` in the repository secrets store (checklist item 2) and a push to `main`.

- [x] 8. New CI workflows

  - [x] 8.1 Create `.github/workflows/ci-main-build.yml`
    - Write it exactly as design section 5 specifies: `push` to `main` with `paths-ignore: ['**.md', 'docs/**', '.kiro/**']`, `pull_request` to `main` with types `opened`/`reopened`/`synchronize`, `permissions: contents: read`, a concurrency group keyed on `github.ref` with `cancel-in-progress: true`, and one job whose only content is `uses: ./.github/workflows/workflow-build.yml` with `aws-region: 'eu-west-1'` and `secrets: CODECOV_TOKEN: ${{ secrets.CODECOV_TOKEN }}`.
    - `paths-ignore` on the `push` trigger **only**. On `pull_request` it would make a docs-only PR never report the required check, which blocks the merge forever.
    - Declare no second job and reference no deploy or streaming-test workflow.
    - _Design: section 5; Data Models "Workflow permission table"_
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.7, 3.8, 3.9, 3.10_
    - **Verify:** parses as YAML; `uses:` resolves to a path inside this repository; exactly one job, no `permissions` beyond `contents: read`. **Post-merge only:** the run appearing green on `main` and the required-check blocking behaviour.

  - [x] 8.2 Create `.github/workflows/ci-dependabot-validation.yml`
    - Write it exactly as design section 6 specifies: `push` to `dependabot/**`, `pull_request` to `main`, `permissions: contents: read`, concurrency keyed on `${{ github.workflow }}-${{ github.head_ref || github.ref_name }}` with `cancel-in-progress: true`, and one job carrying `if: ${{ github.event_name == 'push' || startsWith(github.head_ref, 'dependabot/') }}` and a single `uses: ./.github/workflows/workflow-build.yml`.
    - The job-level `if:` is the substitute for the head-branch filter `pull_request` does not offer; on human PRs the job is skipped, not run.
    - **No `secrets:` block and no `secrets` context reference anywhere in the file** — a Dependabot run gets a read-only token and a separate secrets store. Name no deploy or streaming-test workflow.
    - _Design: section 6_
    - _Requirements: 11.1, 11.2, 11.3, 11.6, 11.8, 11.9_
    - **Verify:** parses as YAML; `grep -c 'secrets' .github/workflows/ci-dependabot-validation.yml` returns 0; `grep -E 'workflow-deploy-aws|workflow-streaming-test'` returns nothing. **Post-merge only:** the first Dependabot PR turning the check green with the Codecov step skipped.

  - [x] 8.3 Create `.github/workflows/codeql.yml`
    - Write it exactly as design section 7 specifies: `push` to `main`, `pull_request` to `main`, `schedule: cron '17 4 * * 1'`, with **no path filters on any trigger**; workflow-level `permissions: contents: read`; one `analyze` job with `timeout-minutes: 60`, job permissions exactly `security-events: write`, `contents: read`, `actions: read`, `packages: read` (no `id-token: write`), and `strategy.fail-fast: false` over the two matrix entries `java-kotlin`/`manual` and `actions`/`none`.
    - Steps: `actions/checkout@v4`, `actions/setup-java@v4` with Temurin 25, `gradle/actions/setup-gradle@v4`, `github/codeql-action/init@v3`, a `./gradlew assemble --stacktrace` step guarded by `if: matrix.build-mode == 'manual'`, then `github/codeql-action/analyze@v3` with `category: "/language:${{ matrix.language }}"`.
    - `assemble` runs no test task; the foojay resolver provisions Java 21 for `:streaming-core` under the JDK 25 setup.
    - _Design: section 7; Error Handling "CodeQL's Kotlin extractor rejects the language level"_
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.8, 6.9, 6.10_
    - **Verify:** parses as YAML; the job permission map equals the design's table exactly; `./gradlew assemble` exits 0 locally. **Post-merge only:** whether the Kotlin 2.3.0 extractor is supported by the shipped CodeQL bundle — if it fails, apply the design's escalation ladder (pin `tools:`, else reduce the matrix to `actions` only) and log the exact Kotlin/JDK/bundle combination per Requirement 6.7.

  - [x] 8.4 Create `.github/workflows/gradle-wrapper-validation.yml`
    - Write it exactly as design section 8 specifies: `push` to `main` and unfiltered `pull_request`, no `paths`/`paths-ignore`, workflow-level `permissions: contents: read`, **no job-level permissions block**, one job with `timeout-minutes: 10`, steps `actions/checkout@v4` then `gradle/actions/wrapper-validation@v4` with `min-wrapper-count: 1`.
    - Invoke no Gradle task and add no `setup-gradle` step — Requirement 7.7 forbids running Gradle here, and `setup-gradle` would duplicate the validation internally.
    - _Design: section 8_
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7_
    - **Verify:** parses as YAML; no `gradlew` string in the file; no job-level `permissions:` key. **Post-merge only:** the action's checksum match against published Gradle releases.

  - [x] 8.5 Create `.github/workflows/dependency-submission.yml`
    - Write it exactly as design section 9 specifies: `push` to `main` plus `workflow_dispatch`, `permissions: contents: write` as the only write scope, one job with `timeout-minutes: 15`, steps `actions/checkout@v4` with `fetch-depth: 0`, `actions/setup-java@v4` (Temurin 25), then `gradle/actions/dependency-submission@v4`.
    - Gate no step on the event name, so a `workflow_dispatch` run behaves identically to a `main` push.
    - _Design: section 9_
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7_
    - **Verify:** parses as YAML; `contents: write` is the only write scope. **Post-merge only:** the dependency graph naming the commit SHA and carrying entries for all three modules.

- [x] 9. Checkpoint - build green, workflows parse
  - Ensure all tests pass, ask the user if questions arise.
  - `./gradlew build -PexcludeTags=integration` exits 0, every file under `.github/workflows` parses as YAML, and both `sam validate` invocations exit 0. The documentation task that follows enumerates these workflows, so the set must be final here.

- [x] 10. Dependabot configuration

  - [x] 10.1 Create `.github/dependabot.yml`
    - Write it exactly as design section 10 specifies: `version: 2`; one `multi-ecosystem-groups` entry named `all-dependencies` scheduled weekly Monday 06:00 `Etc/UTC` with label `dependencies` and commit-message prefix `chore` (both production and development) including scope.
    - A `gradle` entry with `directories: ["/", "/streaming-core", "/streaming-s3-example", "/streaming-s3-example-java"]` — Dependabot's Gradle parser does not recurse into subprojects, and `/` covers both the root build file and `gradle/libs.versions.toml`. Labels `dependencies`, `gradle`. `open-pull-requests-limit: 5`. `patterns: ["*"]`.
    - A `github-actions` entry at `/`, labels `dependencies`, `github-actions`, same schedule, same limit.
    - **Declare no `ignore` block** — an absent `ignore` is what permits `major`, `minor` and `patch`.
    - _Design: section 10; Error Handling "Dependabot rejects the multi-ecosystem group"_
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.9_
    - **Verify:** parses as YAML; every `directories` entry matches an existing path. **Post-merge only:** whether Dependabot accepts `multi-ecosystem-groups`. If rejected, apply the design's fallback (one group per ecosystem, same schedule/labels/prefix/limit) and log it per Requirement 10.10. Creating the three labels is a maintainer action (checklist item 6), not a coding task.

- [x] 11. Third-party review and scanning configuration

  - [x] 11.1 Create `.snyk`
    - Write it exactly as design section 11 specifies: `gradle.allProjects: true`, `code.enabled: true`, `iac.enabled: true` with the three templates (`deployment/aws/sam/template.yaml`, `deployment/aws/sam-java/template.yaml`, `deployment/aws/oidc/github-oidc-role.yaml`), and `exclude.global` listing `**/build/**`, `**/bin/**`, `.gradle/**`.
    - `**/bin/**` matters here specifically: Eclipse/JDT writes `bin/main` and `bin/test` trees under all three modules; they are gitignored, so a git-tree scan misses them but a local `snyk test` would report every finding twice.
    - _Design: section 11 "`.snyk`"_
    - _Requirements: 13.1_
    - **Verify:** parses as YAML; all three IaC paths exist on disk.

  - [x] 11.2 Create `.coderabbit.yaml`
    - Include list exactly: `streaming-core/**`, `streaming-s3-example/**`, `streaming-s3-example-java/**`, `deployment/**`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `**/*.md`. Exclude list exactly: `**/build/**`, `**/bin/**`, `.gradle/**`, `**/*.jar`, `**/*.class`, with exclude winning on overlap.
    - Exactly three `path_instructions` entries per the design's table: `streaming-core/**` on public-API stability and `explicitApi()` compliance (an API change requires a matching `apiDump`); the two example modules on AWS integration glue and streaming-protocol correctness (validate before committing the status, metadata → 8-byte delimiter → body, bounded buffer, flush per chunk); `**/src/test/**` on Given-When-Then backtick naming and the gates 90% `streaming-core` / 80% per example module.
    - _Design: section 11 "`.coderabbit.yaml`"_
    - _Requirements: 13.2, 13.3_
    - **Verify:** parses as YAML; every include and exclude pattern matches at least one existing path (`gradle/libs.versions.toml` exists as of task 2.1).

  - [x] 11.3 Create `trufflehog-config.yml`
    - Detectors exactly `AWS`, `GitHub`, `Generic` — narrower than the reference repository's six, deliberately, since there is no GitLab or Slack integration here to produce true positives. `exclude_paths` exactly `**/build/**`, `**/bin/**`, `.gradle/**`, `**/src/test/resources/**`.
    - _Design: section 11 "`trufflehog-config.yml`"_
    - _Requirements: 13.4_
    - **Verify:** parses as YAML; every excluded path pattern matches something real. Note that nothing in the repository consumes this file yet — enabling that run is checklist item 10, and `SECURITY.md` (task 12.2) must say the file is inert until then.

- [x] 12. Documentation, community templates, and the maintainer setup checklist

  - [x] 12.1 Write `CONTRIBUTING.md` including the maintainer setup checklist
    - Workflow map with one row per file under `.github/workflows` — all eleven after task 8 — each naming triggers, the Gradle command it runs (or "none"), and whether it blocks a merge into `main`. Use the design's "Complete workflow inventory" table as the source.
    - Coverage gates 90% `:streaming-core` / 80% per example module, with the local commands `./gradlew koverVerify` per module and `./gradlew build -PexcludeTags=integration` repo-wide.
    - Conventional Commits types `feat`, `fix`, `chore`, `docs`, `test`, `refactor`, `ci`, scope required, noting the Dependabot `chore` prefix as an instance.
    - The **`## Maintainer setup checklist`** section — the single home for it; `SECURITY.md` and `README.md` link to this anchor. Eleven numbered items in the design's dependency-respecting order (Codecov account → `CODECOV_TOKEN` secret → dependency graph → Dependabot alerts → security updates → labels → code scanning → Snyk app → CodeRabbit app → secret-scanning run → required status check), each in the fixed four-line shape: heading with a `[ ] open` / `[x] done` status marker, then **Unblocks:**, **If skipped:**, **Confirm by:**.
    - Append, marked as prerequisites of a *future* change rather than open items of this one: adding `AWS_ACCOUNT_ID` to the Dependabot secrets store and adding `refs/heads/dependabot/*` to the OIDC trust policy in `deployment/aws/oidc/github-oidc-role.yaml`.
    - Note that the offline subset of the verification script is a reasonable follow-up to move into CI.
    - **This task writes the checklist only.** Every item on it is an action only the repository owner or a third-party account holder can perform; the agent must not attempt any of them, and no item may describe something the build or a workflow already does automatically.
    - _Design: section 13 "Documentation and the setup checklist"; section 14_
    - _Requirements: 14.3, 14.4, 14.5, 14.9, 15.1, 15.2, 15.3, 15.4, 15.5, 15.6, 15.7, 15.8, 15.9, 15.10, 10.8, 13.5, 11.11_
    - **Verify:** every workflow filename under `.github/workflows` appears in the map and every filename the map mentions exists; item numbering is gap-free; each item has all three labelled sub-fields plus a status marker; the stated gates match the module build files.

  - [x] 12.2 Write `SECURITY.md`
    - Private vulnerability-reporting route, an explicit statement that vulnerabilities must not be reported via public issues or PRs, acknowledgement within 5 business days, status updates at least every 10 business days until closure.
    - One subsection per tool — Codecov, CodeQL, Gradle wrapper validation, dependency graph submission, Dependabot, Snyk, CodeRabbit, secret scanning — each stating purpose, whether a Badge_Block badge represents it, config file path, cadence, and the maintainer action needed to keep it active. For Snyk, CodeRabbit and secret scanning, state that no badge reports them and their results are visible only in the app's own PR output, and that `trufflehog-config.yml` is inert until a consuming run exists.
    - A short "not used, and why" list for the excluded tooling: both OpenSSF badges, the OpenAPI/Swagger badge, semantic-release, deploy-on-`main`, Dependabot auto-merge. This is the only place besides the design where the exclusions are recorded.
    - Link to the `## Maintainer setup checklist` anchor in `CONTRIBUTING.md`.
    - _Design: section 13_
    - _Requirements: 14.1, 14.2, 13.6, 13.9, 15.1_
    - **Verify:** all eight tool subsections present; the relative link to `CONTRIBUTING.md` resolves; every config path named exists.

  - [x] 12.3 Write `CODE_OF_CONDUCT.md`
    - Expected behaviours, unacceptable behaviours, the scope in which it applies, exactly one enforcement contact address, and the same 5-business-day acknowledgement window as `SECURITY.md`.
    - _Design: section 13_
    - _Requirements: 14.6_
    - **Verify:** the acknowledgement window string matches `SECURITY.md`.

  - [x] 12.4 Add the community templates
    - `.github/ISSUE_TEMPLATE/bug_report.md` asking for reproduction steps, expected result, actual result, affected module, and the Java and Gradle versions used.
    - `.github/pull_request_template.md` asking for a change summary, related issue references, confirmation that `./gradlew build -PexcludeTags=integration` passed locally, and the output of `scripts/verify-quality-signals.sh` — the PR template is how Requirement 16.6 is met, since the script is a local pre-merge tool rather than a CI job.
    - _Design: section 13; section 14 "Verification script"_
    - _Requirements: 14.7, 16.6_
    - **Verify:** both files exist at those exact paths; the PR template names both the Gradle command and the script.

  - [x] 12.5 Add `.github/FUNDING.yml`
    - Sponsor links. **OPTIONAL** — the design lists it among the community templates, but no acceptance criterion requires it (Requirement 14.7 names only the bug-report and pull-request templates). Skip freely.
    - _Design: section 13_

  - [x] 12.6 Link `SECURITY.md` and `CONTRIBUTING.md` from the README
    - Add a "Contributing and security" section immediately before `## License`, with repository-relative links to `SECURITY.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, and the `## Maintainer setup checklist` anchor.
    - This must **not** sit near the top: Requirement 1.1 forbids any content between the title and the Badge_Block. It is a separate edit from task 13.1 in the same pull request.
    - Change no existing README section or prose.
    - _Design: section 1 "Badge block", final paragraph_
    - _Requirements: 14.8, 14.10, 15.1_
    - **Verify:** every relative link resolves to a file present in the same commit; `git diff` shows only the added section.

- [x] 13. README badge block

  - [x] 13.1 Insert the eight-badge block under the README title
    - Insert the design's literal eight lines verbatim from design section 1, directly beneath `# aws-lambda-streaming-core`, one blank line either side, nothing else between the title and the block: five status badges (Release, Maven Central, Build, codecov, CodeQL) on five consecutive lines, one blank line, then three language badges (Kotlin 2.3.0, JVM 21, License: MIT).
    - **JVM says 21, not 25** — Requirement 12.2 ties it to the published `streaming-core` module (`JavaLanguageVersion.of(21)`, `JvmTarget.JVM_21`). The example modules' Java 25 is not a consumer-facing floor and must not be advertised.
    - Every badge is an image inside a link; no bare images, no inline HTML; every label 1–40 characters. The Repo_Slug `elenavanengelenmaslova/aws-lambda-streaming-jvm-runtime` is spelled exactly in every GitHub/shields URL.
    - No excluded badge may appear, rendered or commented out: no `securityscorecards`, no `bestpractices.dev`, no `swagger/valid`.
    - Leave the existing title text and every existing section and paragraph unchanged.
    - Placed this late deliberately: the Build and CodeQL badges name `ci-main-build.yml` and `codeql.yml`, which exist on the branch from task 8.
    - _Design: section 1 "Badge block"; Data Models "Badge-to-source-of-truth map"_
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 2.1, 2.2, 2.3, 2.4, 3.5, 3.6, 5.8, 6.6, 12.1, 12.2, 12.3_
    - **Verify:** the two workflow filenames in the Build and CodeQL URLs match files under `.github/workflows`; the Maven coordinates match `mavenPublishing.coordinates(...)`; the Kotlin version matches `[versions] kotlin`; the JVM major matches `streaming-core`'s toolchain and `jvmTarget`; the licence label matches `LICENSE`. **Post-merge only:** actual badge rendering — Release, Maven Central, Codecov and CodeQL can all render unresolved on day one and are handled as PENDING by task 15.12.

- [x] 14. Knowledge capture in `docs/log.md`

  - [x] 14.1 Append the Gradle, coverage and licence entries
    - Follow the file's existing three-part form: `##` title, a **Symptom / trigger** bullet, a **Resolution / status** bullet.
    - Dependabot cannot read Kotlin-DSL `extra[…]` versions — why the catalog was introduced (Req 9.7) — **plus the exception allow-list** (Req 9.8): the foojay literal in `settings.gradle.kts`, the composed Floci image name, `streaming-core`'s own `version` from `releaseVersion`, versionless `junit-platform-launcher`, versionless `aws-sdk-java-s3` from the BOM. This list is the allow-list Property 4 reads, so it must be machine-greppable.
    - Plugin aliases and the inherited script classpath — why the root uses `alias(...) apply false` while subprojects stay versionless, and why `settings.gradle.kts` keeps a literal.
    - Kover on a pure-Java module — extend the existing entry with the JaCoCo XML path differing from the Kover modules (Req 4.9).
    - Licence mismatch `LICENSE` MIT vs POM Apache-2.0 — identifier kept, every file changed, and that already-published versions keep the Apache-2.0 POM immutably (Req 12.5).
    - Gitignored Eclipse `bin/` trees double local scan results — why `**/bin/**` is excluded from Snyk, CodeRabbit, TruffleHog and Codecov here but not in MockNest.
    - If task 3.1's baseline diff was non-empty at any point, record the difference and the correction (Req 9.10).
    - If the `streaming-core` toolchain and `jvmTarget` ever diverge, record the divergence and the version displayed (Req 12.9).
    - _Design: "Knowledge Capture — `docs/log.md` deliverables"_
    - _Requirements: 9.7, 9.8, 9.10, 4.9, 12.5, 12.9_
    - **Verify:** each entry has all three parts; the exception list names every file/value in the design's exceptions table.

  - [x] 14.2 Append the workflow, CI and badge entries
    - `timeout-minutes` is unsupported on reusable-workflow calls — why the 30-minute bound sits on `workflow-build.yml`'s jobs.
    - The `secrets` context is unavailable in `if:` — why the Codecov upload needs a preceding eligibility step.
    - `pull_request` has no head-branch filter — why the Dependabot workflow uses a job-level `if:` on `github.head_ref` and normalises `head_ref`/`ref_name` in its concurrency key.
    - `paths-ignore` on a pull-request trigger blocks merges forever — why the exclusions are on `push` only (Req 3.7).
    - MockNest's SAR badge has no counterpart; replaced by the Maven Central badge (Req 2.6).
    - Badges that render unresolved on day one — Release (no tag), Maven Central (no publish), Codecov (no upload), CodeQL (workflow not yet on `main`): how each renders, which checklist item unblocks it, and that badges are never removed to make a check pass (Req 2.5, 2.7, 5.11, 16.8). This list must agree with the script's pending list in task 15.1.
    - Placeholders to fill **only if the failure occurs**: the CodeQL Kotlin extractor combination and the rung applied (Req 6.7); the rejected Dependabot multi-ecosystem group setting and the per-ecosystem fallback (Req 10.10).
    - _Design: "Knowledge Capture — `docs/log.md` deliverables"; Error Handling_
    - _Requirements: 2.5, 2.6, 2.7, 3.7, 5.11, 6.7, 10.10, 11.10, 16.8, 16.9_
    - **Verify:** each entry has all three parts; the pending-badge entry names four badges and their unblocking checklist items.

- [x] 15. Verification script

  - [x] 15.1 Build the `verify-quality-signals.sh` scaffold
    - Create `scripts/verify-quality-signals.sh` with two modes: `--offline` (YAML parsing, literal comparisons, cross-file consistency, catalog invariants, config path patterns, documentation coverage, `sam validate`, the Gradle build and report checks) and the default full mode (offline plus network checks).
    - Dispatch named checks (`verify-quality-signals.sh check_badge_block`, etc.) to one implementation file per check under `scripts/verify/checks/`, sourced by the main script. The CLI stays exactly as the design names it; splitting the implementations keeps each property independently written and independently provable.
    - Run `sam validate --template-file deployment/aws/sam/template.yaml` and `sam validate --template-file deployment/aws/sam-java/template.yaml`, requiring exit code 0 from each (Req 16.4), and `./gradlew build -PexcludeTags=integration` plus the three-report existence check (Req 16.5).
    - Declare the **pending-badge list** at the top: Release, Maven Central, Codecov, CodeQL, each with its blocking checklist item. Pending badges report `PENDING`, are excluded from the failure count, and the script asserts the list agrees with the `docs/log.md` pending entries from task 14.2.
    - Aggregate results so every failure names both the check and the artefact, and exit non-zero if any non-pending check fails (Req 16.7).
    - Make it executable. This is a local pre-merge tool, not a CI job — Requirement 3.3 forbids a second job in `ci-main-build.yml`, and making every PR depend on shields.io/codecov.io availability would contradict the decision to keep third-party availability out of the merge gate.
    - _Design: section 14 "Verification script"; Testing Strategy Layer 2_
    - _Requirements: 16.4, 16.5, 16.7, 16.8_
    - **Verify:** `./scripts/verify-quality-signals.sh --offline` runs end to end and reports per-check status; `sam validate` exits 0 for both templates.

  - [x] 15.2 Implement `check_badge_block`
    - **Property 1: The badge block is exactly the expected literal**
    - Extract the block from `README.md` and diff it against the expected eight-line, two-group literal — same count, order, image and target URLs, `[![label](image)](target)` form with a 1–40 character label, one blank line between groups. Then assert no substring of the README matches `securityscorecards`, `bestpractices.dev` or `swagger/valid`, rendered or commented.
    - **Validates: Requirements 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 2.1, 2.2, 2.4, 3.5, 5.8, 6.6**

  - [x] 15.3 Implement `check_badge_sources`
    - **Property 2: Every badge displays what its source of truth declares**
    - One assertion per row of the badge-to-source map: Maven coordinates from `mavenPublishing.coordinates(...)`, Kotlin version from `[versions] kotlin`, Java major from `streaming-core`'s toolchain **and** `jvmTarget` (which must agree with each other), licence identifier from `LICENSE`'s first line; and for both workflow-status badges, the filename embedded in the image and target URLs names a file that exists under `.github/workflows`.
    - **Validates: Requirements 2.3, 2.8, 3.6, 12.1, 12.2, 12.3, 12.6, 12.7, 12.8, 12.9**

  - [x] 15.4 Implement `check_licence`
    - **Property 3: The licence identifier is one string in three places**
    - Assert three-way equality between `LICENSE`, the `streaming-core` publication POM licence name, and the badge label; and that `git diff --exit-code LICENSE` is clean.
    - **Validates: Requirements 12.3, 12.4, 12.8**

  - [x] 15.5 Implement `check_catalog`
    - **Property 4: No build file hides a version from Dependabot**
    - Scan the root and three module build files for quoted version-like literals and for `extra[` / `rootProject.extra[`; cross-reference `[versions]`, `[libraries]` and `[plugins]`; read the documented exception allow-list from `docs/log.md` (task 14.1). An undocumented literal version fails the check. Assert a `[versions]` entry exists for each of the 15 migrated `extra` keys and a `[plugins]` entry for every plugin that previously carried a literal version.
    - **Validates: Requirements 9.1, 9.2, 9.5, 9.6, 9.8**

  - [x] 15.6 Implement `check_coverage_paths`
    - **Property 6 (script half): Coverage reports sit where Codecov looks**
    - Parse the `files:` input out of `workflow-build.yml` and compare it to `build/reports/coverage-report-paths.txt` written by `verifyCoverageReports`. The Gradle half of this property — existence, parseability, at least one `<class>`, non-zero LINE `covered` — is task 5.1.
    - **Validates: Requirements 4.1, 4.2, 4.3, 4.7, 5.2, 16.5**

  - [x] 15.7 Implement `check_permissions`
    - **Property 7: Every workflow requests exactly the permissions the requirements name**
    - YAML-parse each workflow file and compare both the workflow-level and job-level `permissions` maps by **set equality** against the design's permission table — no extra scope, no widened scope, and specifically no `id-token: write` on `codeql.yml`.
    - **Validates: Requirements 3.4, 6.5, 7.3, 8.3, 11.9**

  - [x] 15.8 Implement `check_workflows`
    - **Property 8: Every workflow parses, and every `uses:` reference is pinned and resolvable**
    - `yaml.safe_load` every file under `.github/workflows` with zero errors; assert every `uses:` carries an explicit ref (never a bare `@main`); in full mode resolve each ref via `gh api` or confirm it is a path inside this repository. Report failures as `file:line`.
    - **Validates: Requirements 7.2, 8.2, 16.3**

  - [x] 15.9 Implement `check_dependabot_isolation`
    - **Property 9: No Dependabot path reaches AWS or a secret**
    - Negative grep over `ci-dependabot-validation.yml` for any `secrets` context reference, any `secrets:` input block, and any reference to `workflow-deploy-aws.yml` or `workflow-streaming-test.yml`; plus a positive structural assertion that the only secret-dependent step in `workflow-build.yml` is the Codecov upload, that it is guarded by the `main`-push condition, and that it carries `continue-on-error: true`.
    - **Validates: Requirements 11.2, 11.3, 11.5, 5.1, 5.4**

  - [x] 15.10 Implement `check_config_paths`
    - **Property 10: Every declared path pattern matches something real**
    - Glob every repository-path pattern declared in `.snyk`, `.coderabbit.yaml`, `trufflehog-config.yml` and `codecov.yml`, plus every Dependabot `directories` entry, against the working tree; assert each file parses as YAML. A pattern matching nothing fails with the file and pattern named.
    - **Validates: Requirements 10.6, 13.1, 13.2, 13.4, 13.7, 13.8**

  - [x] 15.11 Implement `check_docs`
    - **Property 11: The documentation enumerates exactly what the repository contains**
    - Bidirectional workflow-map check against `.github/workflows` (both an added and a deleted workflow must fail it); a `SECURITY.md` subsection per tool for all eight; the three coverage-gate values in `CONTRIBUTING.md` equal to the module build files; every relative markdown link in `README.md`, `SECURITY.md` and `CONTRIBUTING.md` resolving to an existing path; and for every numbered checklist item, the three labelled sub-fields plus a status marker, gap-free numbering, and every dependent item ordered after its dependency.
    - **Validates: Requirements 13.6, 14.1, 14.3, 14.4, 14.8, 14.9, 14.10, 15.1, 15.2, 15.3, 15.4, 15.5, 15.6, 15.7, 15.8, 15.10, 16.9**

  - [x] 15.12 Implement `check_badge_urls` (network mode)
    - Request every badge image URL and every badge target URL with `curl --max-time 10 --retry 2 -L`, requiring a 2xx response, and assert the returned SVG is a rendered badge rather than a placeholder reading `invalid`, `not found` or `inaccessible`.
    - Badges on the pending list report `PENDING` with the blocking checklist item named, rather than failing.
    - **Validates: Requirements 16.1, 16.2, 16.8**

  - [x] 15.13 Prove each check fails on a deliberate failure
    - Per the design's Testing Strategy Layer 2: introduce one deliberate defect at a time — a wrong badge URL, a missing coverage report, a bogus `.coderabbit.yaml` pattern, a broken README link, a mismatched Kotlin version, an undocumented literal version, a widened workflow permission, a `secrets` reference in the Dependabot workflow — and confirm the exit code is non-zero and the message names both the check and the artefact. Revert each defect afterwards.
    - A check that silently passes on everything is indistinguishable from one that works, which is why this is here.
    - _Requirements: 16.7_

- [x] 16. Final checkpoint - ensure everything passes and record what remains post-merge
  - Ensure all tests pass, ask the user if questions arise.
  - Pre-merge gate: `./gradlew build -PexcludeTags=integration` exits 0 with gates at 90%/80%/80%; the baseline diff from task 3.1 is empty; `./gradlew :streaming-core:apiCheck` exits 0; both `sam validate` invocations exit 0; `./scripts/verify-quality-signals.sh` reports no failures (pending badges excluded).
  - Record on the pull request the observations that **cannot** be made before merge, from the design's Testing Strategy Layer 3: the Build badge turning green on `main`; the first Codecov upload and the badge showing a percentage; CodeQL results appearing under Security → Code scanning for both matrix entries; the dependency graph naming the commit SHA with all three modules; Dependabot accepting the config (apply the per-ecosystem fallback and log it if the multi-ecosystem group is rejected); the first Dependabot pull request carrying three labels, a `chore(...)` commit, and a green `ci-dependabot-validation.yml` with the Codecov step skipped; wrapper validation green on the first PR; and the Release and Maven Central badges resolving only after the first `vX.Y.Z` tag and publish.

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster path. Note that the starred checks in task 15 are how Requirement 16 is satisfied — skipping them leaves the badge, catalog, permission and documentation invariants unenforced, so treat them as deferred rather than unnecessary. Task 12.5 (`FUNDING.yml`) is the only genuinely nice-to-have item.
- No property-based testing library is introduced. This feature has no function with a generatable input domain; the "for all" domains are fixed file sets (eight badge lines, eleven workflow files, the resolved coordinates of three modules) and every check traverses them exhaustively.
- Human-only actions appear **only** inside the checklist written by task 12.1. The agent writes the checklist; the maintainer executes it. No task creates a Codecov account, stores a secret, creates a label, changes a repository security setting, or installs a GitHub app.
- Property 5 is verified by task 3.1 rather than the script, because the baseline it compares against exists only in the migration working tree.
- Every task that touches Gradle ends with `./gradlew build -PexcludeTags=integration` exiting 0. The gates stay at 90% for `:streaming-core` and 80% for each example module throughout; no task may lower one to make a step pass.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0,  "tasks": ["1.1"] },
    { "id": 1,  "tasks": ["1.2"] },
    { "id": 2,  "tasks": ["2.1"] },
    { "id": 3,  "tasks": ["2.2", "2.3", "2.4"] },
    { "id": 4,  "tasks": ["2.5"] },
    { "id": 5,  "tasks": ["3.1", "3.2"] },
    { "id": 6,  "tasks": ["5.1"] },
    { "id": 7,  "tasks": ["5.2", "6.1", "7.1", "7.2", "8.3", "8.4", "8.5", "10.1", "11.1", "11.2", "11.3"] },
    { "id": 8,  "tasks": ["8.1", "8.2"] },
    { "id": 9,  "tasks": ["12.1", "12.3", "12.4", "12.5"] },
    { "id": 10, "tasks": ["12.2"] },
    { "id": 11, "tasks": ["12.6"] },
    { "id": 12, "tasks": ["13.1"] },
    { "id": 13, "tasks": ["14.1"] },
    { "id": 14, "tasks": ["14.2"] },
    { "id": 15, "tasks": ["15.1"] },
    { "id": 16, "tasks": ["15.2", "15.3", "15.4", "15.5", "15.6", "15.7", "15.8", "15.9", "15.10", "15.11", "15.12"] },
    { "id": 17, "tasks": ["15.13"] }
  ]
}
```
