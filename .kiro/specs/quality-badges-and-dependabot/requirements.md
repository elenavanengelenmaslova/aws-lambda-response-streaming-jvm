# Requirements Document

## Introduction

This feature brings the **quality-signal and dependency-automation setup** of the sibling MockNest Serverless repository into `aws-lambda-streaming-jvm-runtime`: a README badge block that publicly reports release, distribution, build, coverage and code-scanning health, the CI workflows and config files those badges depend on, and Dependabot (configuration + the pipeline that validates its pull requests).

Today this repository has **no badges, no Dependabot configuration, no code scanning, no coverage publishing, and no workflow that runs on `main`**. Coverage is produced only as a local HTML report behind per-module `koverVerify`/JaCoCo gates, and every existing workflow triggers on feature-branch pushes, tags, or manual dispatch. Consequently several badges cannot simply be copied from MockNest — the signal they point at does not exist yet, and this document treats creating that signal as part of the work.

The target repository differs from MockNest in three ways that shape the requirements:

1. **Multi-module, mixed-language.** `:streaming-core` (Kotlin library, Java 21, Kover, 90% gate, published to Maven Central), `:streaming-s3-example` (Kotlin, Java 25, Kover, 80% gate), `:streaming-s3-example-java` (pure Java, Java 25, **JaCoCo**, 80% gate, with `koverVerify`/`koverHtmlReport` aliases). A single coverage badge has to represent all three.
2. **No SAR, no OpenAPI document.** MockNest's AWS SAR badge has no counterpart; the distribution channel here is **Maven Central**. The OpenAPI badge is excluded by the requester.
3. **Dependency versions live in `extra["…"]` entries in the root `build.gradle.kts`** and are read via `rootProject.extra["…"]` string templates. Dependabot's Gradle parser reads literal coordinates, `gradle.properties` values and version catalogs — not Kotlin-DSL `extra` map lookups — so Dependabot would open PRs for almost nothing until the declarations move to a format it understands.

**Explicitly excluded by the requester:** both OpenSSF badges — the OpenSSF Scorecard badge and the OpenSSF Best Practices badge — together with the workflows those badges would require, and the OpenAPI/Swagger validity badge, which is excluded because this repository publishes no OpenAPI document.

**Out of scope (deliberate deviations from MockNest, recorded here so they are not silently dropped):**

- **semantic-release** (`.releaserc.json`). MockNest auto-cuts bare-semver tags on merge to `main`; this repository's `workflow-publish.yml` triggers on `v*` tags and rejects anything that is not `vMAJOR.MINOR.PATCH`. Adopting MockNest's `tagFormat: "${version}"` would produce tags that never trigger a publish. Releases stay manual.
- **Deploying on every `main` push.** MockNest deploys to staging from `main`; here deploys stay on `cd-deploy-on-demand.yml` to keep AWS cost and blast radius under manual control.
- **Auto-merging Dependabot PRs.** The reference repository has no auto-merge workflow; its Dependabot PRs are validated by CI and merged by a human. This feature mirrors that.

This document handles no PII and introduces no AI/Bedrock usage.

## Glossary

- **Repo_Slug**: The GitHub path of this repository: `elenavanengelenmaslova/aws-lambda-streaming-jvm-runtime`.
- **README**: The repository root `README.md`.
- **Badge_Block**: The contiguous group of Markdown badge links placed immediately below the `README` title, before the first prose paragraph.
- **Release_Badge**: A shields.io `github/v/release` badge for the Repo_Slug, linking to the latest GitHub release.
- **Maven_Central_Badge**: A shields.io `maven-central/v` badge for the published artifact `nl.vintik:aws-lambda-streaming-core`, linking to that artifact's Maven Central page.
- **Build_Badge**: A GitHub Actions workflow status badge for the Main_CI_Workflow on branch `main`.
- **Codecov_Badge**: The Codecov graph badge for the Repo_Slug, linking to the Codecov project dashboard.
- **CodeQL_Badge**: A GitHub Actions workflow status badge for the CodeQL_Workflow, linking to the repository's code-scanning results page.
- **Language_Badges**: The static shields.io badges reporting the Kotlin version, the JVM target, and the repository licence.
- **Main_CI_Workflow**: A new workflow at `.github/workflows/ci-main-build.yml` that runs on pushes to `main` and on pull requests targeting `main`, and delegates to the Reusable_Build_Workflow.
- **Reusable_Build_Workflow**: The existing reusable workflow `.github/workflows/workflow-build.yml` (unit tests, coverage gates, SAM template validation).
- **Dependabot_Validation_Workflow**: A new workflow at `.github/workflows/ci-dependabot-validation.yml` that validates Dependabot branches and pull requests by delegating to the Reusable_Build_Workflow only.
- **CodeQL_Workflow**: A new workflow at `.github/workflows/codeql.yml` performing CodeQL analysis of the `java-kotlin` and `actions` languages.
- **Wrapper_Validation_Workflow**: A new workflow at `.github/workflows/gradle-wrapper-validation.yml` that verifies the checked-in Gradle wrapper JAR checksum.
- **Dependency_Submission_Workflow**: A new workflow at `.github/workflows/dependency-submission.yml` that submits the resolved Gradle dependency graph to GitHub's dependency graph.
- **Coverage_Reports**: The machine-readable XML coverage reports produced by the build: Kover XML for the Kotlin modules and JaCoCo XML for `:streaming-s3-example-java`.
- **Codecov_Upload**: The CI step that sends the Coverage_Reports to Codecov.
- **Codecov_Config**: The repository-root `codecov.yml` defining coverage targets, precision, and ignored paths.
- **Codecov_Token**: The repository secret `CODECOV_TOKEN` holding the Codecov upload token.
- **Dependabot_Config**: The file `.github/dependabot.yml`.
- **Dependabot_Branch**: A branch created by Dependabot, matching `dependabot/**`.
- **Dependabot_Run**: A GitHub Actions workflow run whose triggering actor is `dependabot[bot]`, which GitHub grants a read-only token and access only to the Dependabot secrets store.
- **Verification**: The set of pre-merge checks defined in Requirement 16, run locally or in CI against the changed artefacts.
- **Version_Catalog**: A Gradle version catalog at `gradle/libs.versions.toml` declaring the versions and coordinates of every external dependency and plugin used by the build.
- **Version_Catalog_Baseline**: The recorded pre-migration output of Gradle dependency resolution for all three modules, used to prove the Version_Catalog migration changed declaration form only.
- **Snyk_Config**: The repository-root `.snyk` configuration for Snyk dependency, code, and IaC scanning.
- **CodeRabbit_Config**: The repository-root `.coderabbit.yaml` configuration for CodeRabbit AI pull-request review.
- **TruffleHog_Config**: The repository-root `trufflehog-config.yml` declaring secret-scanning detectors and excluded paths.
- **Security_Doc**: A new `SECURITY.md` at the repository root.
- **Contributing_Doc**: A new `CONTRIBUTING.md` at the repository root.
- **Conduct_Doc**: A new `CODE_OF_CONDUCT.md` at the repository root.
- **Community_Templates**: The GitHub community-health templates under `.github/`: `ISSUE_TEMPLATE/bug_report.md`, `pull_request_template.md`, and `FUNDING.yml`.
- **Setup_Checklist**: A documented, ordered list of one-time actions that only a repository owner or a third-party-service account holder can perform.
- **Maintainer**: The human repository owner who performs the Setup_Checklist actions.
- **Dev_Log**: The running gotcha/fix log at `docs/log.md`.
- **Excluded_Badges**: The OpenSSF Scorecard badge, the OpenSSF Best Practices badge, and the OpenAPI/Swagger validity badge, all excluded from this feature by the requester.

## Requirements

### Requirement 1: README badge block

**User Story:** As a visitor evaluating this library, I want the repository health signals grouped at the top of the README, so that release, distribution, build, coverage, and scanning status are visible without reading the CI configuration.

#### Acceptance Criteria

1. THE Badge_Block SHALL appear exactly once in the README, beginning on the first non-blank line after the single level-1 title line, separated from that title by exactly one blank line and from the first prose paragraph by exactly one blank line, with no other content between the title and the Badge_Block.
2. THE Badge_Block SHALL contain exactly eight badges, each appearing exactly once and in this order: Release_Badge, Maven_Central_Badge, Build_Badge, Codecov_Badge, CodeQL_Badge, then the three Language_Badges in the order Kotlin version, JVM target, licence, and SHALL contain no badge other than these eight.
3. THE Badge_Block SHALL place the five status badges (Release_Badge, Maven_Central_Badge, Build_Badge, Codecov_Badge, CodeQL_Badge) one per line on five consecutive lines with no blank line between them, then exactly one blank line, then the three Language_Badges one per line on three consecutive lines with no blank line between them.
4. THE Badge_Block SHALL render every badge on a single line matching the form `[![<label>](<image-url>)](<target-url>)`, where `<label>` is non-empty, is at most 40 characters, and names the signal that badge reports, and SHALL render no badge as a bare image without a surrounding link or as inline HTML.
5. THE Badge_Block SHALL contain none of the Excluded_Badges, in rendered or commented-out form.
6. THE Badge_Block SHALL spell the Repo_Slug exactly, and name no other owner or repository path, in every badge image URL and every badge target URL that addresses github.com or a shields.io GitHub endpoint.
7. WHEN the Badge_Block is inserted into the README, THE README SHALL retain its existing level-1 title text and every existing section and prose paragraph unchanged, with the Badge_Block lines and the single blank line following them as the only additions.

### Requirement 2: Release and distribution badges

**User Story:** As a developer deciding whether to depend on this library, I want the latest release and the published Maven Central version on screen, so that I can confirm the artifact exists and which version is current.

#### Acceptance Criteria

1. THE Release_Badge SHALL use the image URL `https://img.shields.io/github/v/release/elenavanengelenmaslova/aws-lambda-streaming-jvm-runtime` exactly as written, with no additional query parameters or style modifiers, and SHALL link to `https://github.com/elenavanengelenmaslova/aws-lambda-streaming-jvm-runtime/releases/latest`.
2. THE Maven_Central_Badge SHALL use the image URL `https://img.shields.io/maven-central/v/nl.vintik/aws-lambda-streaming-core` exactly as written, with no additional query parameters or style modifiers, and SHALL link to `https://central.sonatype.com/artifact/nl.vintik/aws-lambda-streaming-core`.
3. THE Maven_Central_Badge image URL and target URL SHALL both name the same `groupId` (`nl.vintik`) and `artifactId` (`aws-lambda-streaming-core`) that `streaming-core/build.gradle.kts` passes to `mavenPublishing.coordinates(...)`.
4. THE Badge_Block SHALL place the Release_Badge first and the Maven_Central_Badge second within the status-badge group, and SHALL include both badges regardless of whether a GitHub release or a published Maven Central version exists for the Repo_Slug at the time the Badge_Block is added.
5. IF no GitHub release exists for the Repo_Slug when the Badge_Block is added, THEN THE Dev_Log SHALL record, as one entry, that the Release_Badge renders as "no release" until the first `vMAJOR.MINOR.PATCH` tag is pushed, and that this rendering is expected rather than a broken badge.
6. THE Dev_Log SHALL record that MockNest's AWS SAR badge has no counterpart in this repository and is replaced by the Maven_Central_Badge.
7. IF no version of the artifact named in the Maven_Central_Badge is indexed on Maven Central when the Badge_Block is added, THEN THE Dev_Log SHALL record, as one entry, that the Maven_Central_Badge renders as unresolved until the first publish completes, and that the badge is retained rather than removed.
8. WHEN the coordinates passed to `mavenPublishing.coordinates(...)` in `streaming-core/build.gradle.kts` change, THE Maven_Central_Badge image URL and target URL SHALL be updated in the same change, so that no change leaves the badge pointing at coordinates the build no longer publishes.

### Requirement 3: Main-branch CI workflow and build badge

**User Story:** As a maintainer, I want a workflow that runs on `main` and on pull requests into `main`, so that the build badge reports a real status and merges are gated by tests.

#### Acceptance Criteria

1. THE Main_CI_Workflow SHALL trigger on `push` events to branch `main` and on `pull_request` events targeting branch `main` with activity types `opened`, `reopened`, and `synchronize`, so that every commit added to an open pull request is re-validated.
2. THE Main_CI_Workflow SHALL delegate its build work to the Reusable_Build_Workflow through a single `uses:` reference, passing only the inputs and secrets that the Reusable_Build_Workflow declares, rather than duplicating build steps.
3. THE Main_CI_Workflow SHALL declare no job other than the job that calls the Reusable_Build_Workflow, and SHALL reference no deployment or streaming-test workflow, so that its scope stays limited to build, test, coverage, and SAM-template validation and AWS deployment stays with `cd-deploy-on-demand.yml`.
4. THE Main_CI_Workflow SHALL declare `permissions: contents: read` at workflow level, and SHALL declare no job-level permission wider than `contents: read` unless a job needs a wider scope, in which case the wider scope SHALL be declared on that job only.
5. THE Build_Badge SHALL use the image URL `https://github.com/elenavanengelenmaslova/aws-lambda-streaming-jvm-runtime/actions/workflows/ci-main-build.yml/badge.svg?branch=main` and SHALL link to `https://github.com/elenavanengelenmaslova/aws-lambda-streaming-jvm-runtime/actions/workflows/ci-main-build.yml`.
6. WHEN the Main_CI_Workflow file name changes, THE Build_Badge image URL and link URL SHALL both be updated in the same change so that both paths match the new workflow file name.
7. THE Main_CI_Workflow SHALL apply the path exclusions `**.md`, `docs/**`, and `.kiro/**` to its `push` trigger only, and SHALL apply no path exclusion to its `pull_request` trigger, so that documentation-only commits on `main` consume no CI minutes while every pull request still reports the merge-gating check.
8. IF any job of the delegated Reusable_Build_Workflow run fails, THEN THE Main_CI_Workflow SHALL conclude the run with a failed status, so that the Build_Badge reports a failing state and the pull-request check blocks the merge.
9. WHEN a new commit is pushed to a ref that already has a Main_CI_Workflow run in progress, THE Main_CI_Workflow SHALL cancel the in-progress run for that ref and SHALL let the newest run determine the reported status.
10. IF the delegated build has not completed within 30 minutes of the run starting, THEN THE Main_CI_Workflow SHALL cancel the run and SHALL conclude it with a failed status, so that the Build_Badge never remains in an in-progress state indefinitely.

### Requirement 4: Machine-readable coverage across all three modules

**User Story:** As a maintainer, I want XML coverage reports for every module, so that a single external coverage service can report the whole repository.

#### Acceptance Criteria

1. WHEN the single coverage invocation described in criterion 3 runs, THE build SHALL produce a Kover XML coverage report for `:streaming-core` and a Kover XML coverage report for `:streaming-s3-example`, each containing per-class line counters for that module's main sources.
2. WHEN the single coverage invocation described in criterion 3 runs, THE build SHALL produce a JaCoCo XML coverage report for `:streaming-s3-example-java` by enabling XML output on the JaCoCo report task, containing per-class line counters for that module's main sources.
3. THE Coverage_Reports SHALL be produced by exactly one Gradle invocation that runs the unit tests of all three modules and writes all three XML reports, requiring no second Gradle invocation, and SHALL write each report to a location that is identical on every run so that the Codecov_Upload can name the three files as a fixed list.
4. THE build SHALL keep the existing per-module coverage gates unchanged at 90% line coverage for `:streaming-core`, 80% line coverage for `:streaming-s3-example`, and 80% line coverage for `:streaming-s3-example-java`.
5. THE build SHALL keep the `koverVerify` and `koverHtmlReport` task names invocable on `:streaming-s3-example-java` under exactly those names, with `koverVerify` enforcing the module's 80% line gate and `koverHtmlReport` producing its HTML coverage report, as they do before this change.
6. WHERE a Gradle task is added or renamed to produce the Coverage_Reports, THE Reusable_Build_Workflow SHALL invoke that task with `-PexcludeTags=integration`, so that no test tagged `integration` executes and no container-backed test is started, matching the current unit-test-only CI behaviour.
7. IF any of the three XML reports is absent, contains no class entries, or reports zero covered lines after the coverage invocation completes, THEN THE build SHALL fail and SHALL indicate which module's report is missing or empty.
8. IF a module's measured line coverage is below its gate in criterion 4, THEN THE build SHALL fail the invocation, SHALL indicate which module fell below its gate together with its measured line-coverage percentage, and SHALL retain the Coverage_Reports already written so the shortfall can be inspected from the report.
9. THE Dev_Log SHALL record that `:streaming-s3-example-java` reports coverage through JaCoCo rather than Kover, that Kover on a pure-Java module produces a vacuously passing empty report, and that the JaCoCo XML report location therefore differs from the Kover modules.

### Requirement 5: Coverage publishing and Codecov badge

**User Story:** As a visitor, I want a coverage percentage badge backed by a public dashboard, so that I can judge test depth without cloning the repository.

#### Acceptance Criteria

1. WHEN the Gradle invocation that produces the Coverage_Reports finishes in the test job of the Reusable_Build_Workflow, THE Codecov_Upload SHALL run as a later step of that same test job, independently of whether the coverage gates passed, and SHALL NOT run in any other job.
2. THE Codecov_Upload SHALL pass exactly three coverage report files to the Codecov action — the Kover XML report of `:streaming-core`, the Kover XML report of `:streaming-s3-example`, and the JaCoCo XML report of `:streaming-s3-example-java` — so that the reported percentage covers all three modules.
3. THE Reusable_Build_Workflow SHALL declare the Codecov_Token as an optional secret input, and THE Codecov_Upload SHALL authenticate with the value that the calling workflow supplies from the repository secrets store.
4. THE Codecov_Upload SHALL execute only when the triggering event is a push whose ref is branch `main`, and SHALL be skipped for every other ref and event — including pull-request runs, Dependabot_Run runs, Dependabot_Branch pushes, and feature-branch pushes — none of which can read the Codecov_Token.
5. IF the Codecov_Upload fails or has not completed within 10 minutes, THEN THE Reusable_Build_Workflow SHALL record the failure in the run log, SHALL leave the failed step without changing the job or workflow conclusion, and SHALL run the remaining jobs to their own conclusions, so that the unit tests and coverage gates remain the only blocking checks.
6. THE Codecov_Config SHALL set `codecov.require_ci_to_pass: true`, a project coverage target of 80% with a 1% threshold, a patch target of 80%, a reported precision of 2 decimal places, and SHALL exclude `**/test/**`, `**/build/**`, `**/bin/**`, and `deployment/**` from coverage display.
7. THE Codecov_Config SHALL state in a comment that the 80% project target matches the lowest per-module gate in the repository, rather than the 90% gate of `:streaming-core`.
8. THE Codecov_Badge SHALL use the image URL `https://codecov.io/gh/elenavanengelenmaslova/aws-lambda-streaming-jvm-runtime/graph/badge.svg` and link to `https://codecov.io/gh/elenavanengelenmaslova/aws-lambda-streaming-jvm-runtime`.
9. IF the Codecov_Token is absent or empty on a run that otherwise satisfies the branch condition of criterion 4, THEN THE Reusable_Build_Workflow SHALL skip the Codecov_Upload, SHALL record in the run log that coverage publishing was skipped because no token was available, and SHALL complete the run with a conclusion determined by the unit tests, the coverage gates, and the SAM template validation.
10. IF any of the three coverage report files named in criterion 2 is absent when the Codecov_Upload runs, THEN THE Codecov_Upload SHALL record an error in the run log naming each missing report, SHALL NOT report the upload as successful, and SHALL leave the job conclusion determined by the unit tests and the coverage gates.
11. IF no Codecov_Upload has yet succeeded for the Repo_Slug when the Codecov_Badge is added to the README, THEN THE Dev_Log SHALL record that the badge renders as "unknown" until the first upload from branch `main` succeeds, and SHALL name the Setup_Checklist item covering the Codecov_Token as the unblocking action.

### Requirement 6: CodeQL code scanning and badge

**User Story:** As a maintainer, I want automated static security analysis of the JVM sources and the workflow definitions, so that vulnerabilities and unsafe Actions usage surface in the GitHub Security tab.

#### Acceptance Criteria

1. THE CodeQL_Workflow SHALL run exactly two parallel matrix entries — `java-kotlin` with build mode `manual` and `actions` with build mode `none` — with `fail-fast: false`, so that a failure in one entry does not cancel the other.
2. WHILE analysing the `java-kotlin` language, THE CodeQL_Workflow SHALL run a Gradle `assemble` invocation that covers all three modules (`:streaming-core`, `:streaming-s3-example`, `:streaming-s3-example-java`) after CodeQL initialisation and before analysis, and SHALL run no test tasks.
3. THE CodeQL_Workflow SHALL set up a Temurin JDK 25 toolchain, the highest toolchain in the build, and SHALL let the build resolve the Java 21 toolchain that `:streaming-core` declares, so that the `assemble` invocation compiles all three modules without a toolchain-resolution failure.
4. THE CodeQL_Workflow SHALL trigger on `push` events to branch `main`, on `pull_request` events targeting branch `main`, and on a `schedule` that fires once per week on a fixed day and time expressed in UTC, and SHALL apply no path filters to any of those three triggers, so that documentation-only changes and workflow-only changes are both analysed.
5. THE CodeQL_Workflow SHALL declare `permissions: contents: read` at workflow level and SHALL grant its analysis job exactly `security-events: write`, `contents: read`, `actions: read`, and `packages: read`, and no further permission.
6. THE CodeQL_Badge SHALL use the image URL `https://github.com/elenavanengelenmaslova/aws-lambda-streaming-jvm-runtime/actions/workflows/codeql.yml/badge.svg?branch=main&event=push` and link to `https://github.com/elenavanengelenmaslova/aws-lambda-streaming-jvm-runtime/security/code-scanning`, so that the badge reports the most recent push-to-`main` analysis rather than pull-request or scheduled runs.
7. IF the `java-kotlin` extractor fails with an error indicating an unsupported Kotlin or Java language level, THEN THE Dev_Log SHALL record the failing combination (Kotlin version, JDK version, CodeQL bundle version) and the workaround applied.
8. WHEN analysis of a matrix entry completes, THE CodeQL_Workflow SHALL upload that entry's results to the repository code-scanning results and SHALL report the entry as successful regardless of how many alerts the analysis reports, leaving alert triage to the repository's code-scanning results page.
9. IF the Gradle `assemble` invocation fails while analysing the `java-kotlin` language, THEN THE CodeQL_Workflow SHALL fail that matrix entry with an error identifying the failing Gradle task, SHALL upload no `java-kotlin` results for that run, and SHALL leave the previously recorded `java-kotlin` code-scanning alerts unchanged.
10. IF a matrix entry exceeds 60 minutes of run time, THEN THE CodeQL_Workflow SHALL terminate that entry, report it as failed with a timeout indication, and SHALL let the other matrix entry run to completion.

### Requirement 7: Gradle wrapper validation

**User Story:** As a maintainer, I want the checked-in Gradle wrapper JAR verified on every change, so that a tampered wrapper cannot execute arbitrary code in CI.

#### Acceptance Criteria

1. THE Wrapper_Validation_Workflow SHALL trigger on `push` events to branch `main` and on `pull_request` events targeting any branch, and SHALL declare no `paths` or `paths-ignore` filter, so that no change can bypass wrapper validation.
2. THE Wrapper_Validation_Workflow SHALL validate every checked-in Gradle wrapper JAR under `gradle/wrapper/` using the `gradle/actions/wrapper-validation` action, referenced at a pinned released version rather than a floating branch reference.
3. THE Wrapper_Validation_Workflow SHALL declare `permissions: contents: read` at workflow level, SHALL declare no job-level permissions block, and SHALL request no other permission scope.
4. IF a checked-in wrapper JAR checksum does not match any checksum published for a Gradle release, THEN THE Wrapper_Validation_Workflow SHALL conclude the run with a failure status and SHALL report in the run log the path of each wrapper JAR whose checksum was not matched.
5. IF no wrapper JAR is found under `gradle/wrapper/` in the checked-out tree, THEN THE Wrapper_Validation_Workflow SHALL conclude the run with a failure status and SHALL report an error indicating that no wrapper JAR was found, so that removing the wrapper cannot produce a passing run.
6. WHEN every checked-in wrapper JAR checksum matches a published Gradle release checksum, THE Wrapper_Validation_Workflow SHALL conclude the run as successful within 10 minutes of run start.
7. WHEN the Wrapper_Validation_Workflow runs, THE Wrapper_Validation_Workflow SHALL validate the wrapper JAR from the tree checked out at the commit under test without invoking the Gradle wrapper or any Gradle task in that run.

### Requirement 8: Dependency graph submission

**User Story:** As a maintainer, I want the resolved Gradle dependency graph submitted to GitHub, so that Dependabot alerts cover transitive dependencies that no build file names directly.

#### Acceptance Criteria

1. WHEN a commit is pushed to the `main` branch, THE Dependency_Submission_Workflow SHALL start automatically without manual action and reach a terminal conclusion (success or failure) within 15 minutes.
2. WHEN the Dependency_Submission_Workflow runs, THE Dependency_Submission_Workflow SHALL submit the resolved dependency graph for the checked-out commit SHA using the `gradle/actions/dependency-submission` action, such that after the run the repository dependency graph reports that commit SHA as its source.
3. THE Dependency_Submission_Workflow SHALL declare `contents: write` as its only write permission, with every other permission set to read or none.
4. THE Dependency_Submission_Workflow SHALL check out the repository with complete commit history and tags (not a shallow clone), so that the project version resolved during the run is identical to the version the release build resolves for the same commit.
5. WHEN the Dependency_Submission_Workflow runs, THE Dependency_Submission_Workflow SHALL resolve and submit the direct and transitive dependencies of all three modules `:streaming-core`, `:streaming-s3-example`, and `:streaming-s3-example-java`, so that the submitted graph contains at least one entry attributed to each of the three modules.
6. WHEN a maintainer triggers the Dependency_Submission_Workflow manually via `workflow_dispatch`, THE Dependency_Submission_Workflow SHALL perform the same checkout, resolution, and submission steps as a `main` push run, against the ref selected for the manual run.
7. IF dependency resolution fails for any of the three modules, or the graph submission is rejected, THEN THE Dependency_Submission_Workflow SHALL end with a failed conclusion, report an error identifying the failing module or the submission step, and leave the previously submitted dependency graph unchanged.

### Requirement 9: Dependabot-resolvable dependency declarations

**User Story:** As a maintainer, I want dependency versions declared where Dependabot can read them, so that enabling Dependabot produces real update pull requests instead of silence.

#### Acceptance Criteria

1. THE Version_Catalog SHALL declare a version entry for every external library version currently held in a root `build.gradle.kts` `extra["…"]` entry, covering all 15 such entries, so that no `extra["…"]` version entry remains in the root `build.gradle.kts` after the migration.
2. THE build files of `:streaming-core`, `:streaming-s3-example`, and `:streaming-s3-example-java` SHALL resolve every external dependency coordinate through Version_Catalog accessors, leaving zero `rootProject.extra["…"]` version interpolations in those three files.
3. THE build SHALL resolve, for each of the three modules, the identical set of external dependency coordinates (group, name, and version) recorded in the Version_Catalog_Baseline, with zero differences, and SHALL leave the checked-in `:streaming-core` public API dump unchanged, so that the change alters declaration form only.
4. WHEN the migration is complete, THE build SHALL exit with code 0 for `./gradlew build -PexcludeTags=integration`, with the coverage gates satisfied at 90% for `:streaming-core` and 80% for each example module.
5. THE Version_Catalog SHALL hold each external dependency and plugin version string exactly once, no such version string SHALL appear in the root `build.gradle.kts` or a module build file other than the literal exceptions permitted below, and the root `build.gradle.kts` SHALL state in a comment that versions are declared in `gradle/libs.versions.toml`.
6. THE Version_Catalog SHALL declare the version of every Gradle plugin that the root `build.gradle.kts` or a module build file applies with a literal version string, so that plugin updates are proposed from the Version_Catalog.
7. THE Dev_Log SHALL record, as one entry in the existing title / symptom / resolution form, that Dependabot's Gradle parser does not resolve Kotlin-DSL `extra["…"]` version lookups, and that this is why the Version_Catalog was introduced.
8. WHERE a version cannot be expressed through Version_Catalog accessors because the declaring file has no access to those accessors or because the value is not a Maven dependency version, THE build file SHALL declare that version literally AND THE Dev_Log SHALL record the file, the dependency, and the reason as an exception entry.
9. IF a build file references a Version_Catalog alias that the Version_Catalog does not declare, THEN THE build SHALL fail at configuration time with an error identifying the unresolved alias, and SHALL NOT fall back to a default or previously used version.
10. IF the post-migration resolved version of any external dependency differs from the Version_Catalog_Baseline, THEN THE Verification SHALL fail with an indication of which dependency differs, and THE Dev_Log SHALL record the difference and the correction applied before the change is merged.

### Requirement 10: Dependabot configuration

**User Story:** As a maintainer, I want weekly, consolidated dependency update pull requests for both Gradle and GitHub Actions, so that updates arrive predictably with minimal pull-request noise.

#### Acceptance Criteria

1. THE Dependabot_Config SHALL declare `version: 2`.
2. THE Dependabot_Config SHALL define exactly one multi-ecosystem group that consolidates the `gradle` and `github-actions` ecosystems, so that each scheduled run opens at most one pull request carrying all pending updates from both ecosystems.
3. THE Dependabot_Config SHALL schedule the group with a weekly interval on Monday at 06:00 UTC, stating the day, the time, and the timezone explicitly rather than relying on defaults.
4. THE Dependabot_Config SHALL apply the label `dependencies` to every generated pull request, the label `gradle` to every generated pull request containing at least one Gradle dependency update, and the label `github-actions` to every generated pull request containing at least one workflow action update, so that a consolidated pull request spanning both ecosystems carries all three labels.
5. THE Dependabot_Config SHALL set the commit-message prefix to `chore` and include the scope for both production and development dependency updates, so that generated commits satisfy the repository's Conventional Commits convention.
6. THE Dependabot_Config SHALL monitor the `gradle` ecosystem at directories that cover the root build file, the Version_Catalog, and the build files of `streaming-core`, `streaming-s3-example`, and `streaming-s3-example-java`, so that no module's declared dependency is left unmonitored.
7. THE Dependabot_Config SHALL monitor the `github-actions` ecosystem at directory `/`, covering every workflow file under `.github/workflows`, including the reusable workflows that other workflows reference through `uses:`.
8. IF one or more of the labels `dependencies`, `gradle`, or `github-actions` does not exist in the repository, THEN THE Setup_Checklist SHALL list creating each missing label as a required Maintainer action and SHALL state the consequence that Dependabot drops labels it cannot find, leaving generated pull requests unlabelled.
9. THE Dependabot_Config SHALL allow `major`, `minor`, and `patch` version updates for both ecosystems and SHALL limit concurrently open Dependabot pull requests to a maximum of 5.
10. IF Dependabot rejects the multi-ecosystem group definition, THEN THE Dependabot_Config SHALL instead define one group per ecosystem, each keeping the weekly Monday 06:00 UTC schedule, the same labels, the `chore` commit-message prefix with scope, and the maximum of 5 open pull requests, and THE Dev_Log SHALL record the rejected setting and the fallback applied.

### Requirement 11: Dependabot pull-request validation pipeline

**User Story:** As a maintainer, I want every Dependabot update built and tested automatically without touching AWS, so that I can merge updates on evidence and without cloud cost or credential exposure.

#### Acceptance Criteria

1. THE Dependabot_Validation_Workflow SHALL trigger on `push` events to branches matching `dependabot/**` and on `pull_request` events targeting `main` whose head branch matches `dependabot/**`.
2. THE Dependabot_Validation_Workflow SHALL contain exactly one job-level `uses:` reference, naming the Reusable_Build_Workflow, and SHALL name no deploy workflow and no streaming-test workflow anywhere in the file, so that the AWS deploy and streaming-test workflows stay out of the Dependabot path.
3. THE Dependabot_Validation_Workflow SHALL pass no `secrets:` input to the Reusable_Build_Workflow and SHALL contain no `secrets` context reference in any job, step, or input, so that a Dependabot_Run — which receives a read-only token and cannot read the standard secrets store — reaches a successful conclusion whenever the delegated build passes.
4. WHEN a Dependabot_Run executes the Reusable_Build_Workflow, THE Reusable_Build_Workflow SHALL run the unit tests and the per-module coverage gates with `-PexcludeTags=integration` and SHALL validate both the `sam` and the `sam-java` templates.
5. WHEN a Dependabot_Run executes the Reusable_Build_Workflow, THE Reusable_Build_Workflow SHALL skip the Codecov_Upload, SHALL record the skip in the run log, and SHALL conclude as successful when every non-skipped step passes.
6. IF any job delegated to the Reusable_Build_Workflow fails during a Dependabot_Run, THEN THE Dependabot_Validation_Workflow SHALL conclude as failed, SHALL surface that failure as a failing check on the Dependabot pull request, and SHALL leave the pull request unmerged, so that no Dependabot update merges without a passing build.
7. IF a run of the Dependabot_Validation_Workflow has not reached a conclusion within 30 minutes of its triggering event, THEN THE Dependabot_Validation_Workflow SHALL cancel the run and conclude as failed with a timeout indication in the run log.
8. THE Dependabot_Validation_Workflow SHALL declare a concurrency group keyed on the triggering ref that cancels the in-progress run for that ref, so that at most one run of this workflow is in progress per Dependabot_Branch when `push` and `pull_request` events arrive for the same branch.
9. THE Dependabot_Validation_Workflow SHALL declare `permissions: contents: read` at workflow level and SHALL request no further permission at workflow or job level.
10. THE Dev_Log SHALL record, as one entry in the existing title / symptom / resolution form, that Dependabot-triggered runs get a read-only token and a separate secrets store, and that this is why the Dependabot pipeline is build-only while `ci-feature-build.yml` keeps its deploy and streaming-test stages.
11. WHERE a future change requires a Dependabot_Run to reach AWS, THE Setup_Checklist SHALL list, as prerequisites of that change, adding `AWS_ACCOUNT_ID` to the Dependabot secrets store and adding `refs/heads/dependabot/*` to the OIDC trust policy in `deployment/aws/oidc/github-oidc-role.yaml`, each marked with the automation that depends on it.

### Requirement 12: Language, runtime, and licence badges

**User Story:** As a developer, I want the Kotlin version, JVM target, and licence stated as badges, so that I can check compatibility and licensing at a glance.

#### Acceptance Criteria

1. THE Kotlin Language_Badge SHALL be rendered as a Markdown image wrapped in a link and SHALL display the label `Kotlin` together with the exact version string declared for the `kotlin("jvm")` plugin in the root `build.gradle.kts` plugins block (currently `2.3.0`), including its patch component, with no rounding, truncation, or range notation.
2. THE JVM Language_Badge SHALL be rendered as a Markdown image wrapped in a link and SHALL display exactly one Java major version: the version named by both the toolchain `languageVersion` and the `jvmTarget` of the published `streaming-core` module (currently Java 21), and SHALL NOT display the Java 25 toolchain version used by the example modules.
3. THE licence Language_Badge SHALL display the licence identifier named in the repository `LICENSE` file (currently MIT) and SHALL link to the canonical published text of that licence.
4. THE `streaming-core` publication POM SHALL declare the MIT licence named in the repository `LICENSE` file, replacing the Apache-2.0 licence currently declared in `streaming-core/build.gradle.kts`, and SHALL leave the `LICENSE` file itself unchanged as the authoritative source of the licence value.
5. THE Dev_Log SHALL record, as one entry in the log's existing title / symptom / resolution form, the licence mismatch between the repository `LICENSE` file and `streaming-core/build.gradle.kts`, the licence identifier kept, and every file changed to align the declarations.
6. WHEN the Kotlin version declared for the `kotlin("jvm")` plugin in the root `build.gradle.kts` changes, THE Kotlin Language_Badge SHALL be updated to that new version within the same pull request.
7. WHEN the `streaming-core` toolchain `languageVersion` or `jvmTarget` changes, THE JVM Language_Badge SHALL be updated to the new Java major version within the same pull request.
8. THE licence identifier displayed by the licence Language_Badge, the licence identifier declared in the `streaming-core` publication POM, and the licence identifier named in the repository `LICENSE` file SHALL be identical strings.
9. IF the `streaming-core` toolchain `languageVersion` and its `jvmTarget` name different Java versions, THEN THE JVM Language_Badge SHALL display the `jvmTarget` version and THE Dev_Log SHALL record the divergence and the version displayed.

### Requirement 13: Third-party review and scanning configuration

**User Story:** As a maintainer, I want the Snyk, CodeRabbit, and secret-scanning configurations present and scoped to this repository's layout, so that the third-party apps behave correctly the moment they are installed.

#### Acceptance Criteria

1. THE Snyk_Config SHALL enable Gradle dependency scanning across all three sub-projects, code (SAST) scanning, and IaC scanning; SHALL bring the IaC templates `deployment/aws/sam/template.yaml`, `deployment/aws/sam-java/template.yaml`, and `deployment/aws/oidc/github-oidc-role.yaml` into the IaC scan scope; and SHALL exclude `**/build/**`, `**/bin/**`, and `.gradle/**` from every scan type so that generated output is not reported as source.
2. THE CodeRabbit_Config SHALL declare an include list of exactly these path patterns — `streaming-core/**`, `streaming-s3-example/**`, `streaming-s3-example-java/**`, `deployment/**`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `**/*.md` — and an exclude list of exactly these path patterns — `**/build/**`, `**/bin/**`, `.gradle/**`, `**/*.jar`, `**/*.class` — with the exclude list taking precedence for any path matched by both lists.
3. THE CodeRabbit_Config SHALL declare exactly one path-specific instruction entry for each of these three path groups: `streaming-core/**`, naming public-API stability and explicit-API compliance; the example modules `streaming-s3-example/**` and `streaming-s3-example-java/**`, naming AWS integration glue and streaming-response-protocol correctness; and `**/src/test/**`, naming the repository's test-naming convention and the per-module coverage gates of 90% for `streaming-core` and 80% for each example module.
4. THE TruffleHog_Config SHALL declare exactly the AWS, GitHub, and generic detector sets, and SHALL exclude exactly these paths from scanning: `**/build/**`, `**/bin/**`, `.gradle/**`, and `**/src/test/resources/**`.
5. THE Setup_Checklist SHALL list installing or authorising the Snyk GitHub app and the CodeRabbit GitHub app on this repository as two separate required Maintainer actions, SHALL state for each that the committed configuration has no effect until the app is installed, and SHALL name the observable confirmation of installation as the app reporting on a pull request.
6. THE Security_Doc SHALL name Snyk, CodeRabbit, and secret scanning, SHALL state each tool's configuration file path, and SHALL state that no README badge reports their status and that their results are observable only in the third-party app's own output on a pull request.
7. WHEN the Snyk_Config, the CodeRabbit_Config, or the TruffleHog_Config is added or changed, THE Verification SHALL confirm that the changed file parses as valid YAML and that every repository path pattern it declares matches at least one existing path in this repository.
8. IF a path pattern declared in the Snyk_Config, the CodeRabbit_Config, or the TruffleHog_Config matches no existing repository path, THEN THE Verification SHALL fail with an indication identifying the offending file and pattern, and the configuration change SHALL remain unmerged until the pattern is corrected.
9. IF no workflow or installed app in this repository consumes the TruffleHog_Config, THEN THE Setup_Checklist SHALL list enabling the secret-scanning run that consumes it as a required Maintainer action, and THE Security_Doc SHALL state that the configuration has no effect until that run exists.

### Requirement 14: Quality and security documentation

**User Story:** As a contributor, I want the quality gates, security tooling, and contribution rules written down, so that I can meet them before opening a pull request.

#### Acceptance Criteria

1. THE Security_Doc SHALL contain one entry per tool introduced by this feature — Codecov, CodeQL, Gradle wrapper validation, dependency graph submission, Dependabot, Snyk, CodeRabbit, and secret scanning — and each entry SHALL state the tool's purpose, whether a badge in the Badge_Block represents it, its configuration file path, and its update cadence together with the Maintainer action required to keep it active.
2. THE Security_Doc SHALL state the private vulnerability-reporting route for this repository, SHALL state that vulnerabilities must not be reported through public issues or pull requests, and SHALL commit to an acknowledgement within 5 business days of a report and a status update at least every 10 business days until the report is closed.
3. THE Contributing_Doc SHALL contain one workflow-map entry for every workflow file under `.github/workflows`, and each entry SHALL name the workflow's triggers, the Gradle command it runs (or state that it runs none), and whether its result blocks a merge into `main`.
4. THE Contributing_Doc SHALL state the coverage gates of 90% for `:streaming-core` and 80% for each example module, and SHALL give the local commands that verify them: `./gradlew koverVerify` per module and `./gradlew build -PexcludeTags=integration` for the whole repository.
5. THE Contributing_Doc SHALL list the accepted Conventional Commits types — at minimum `feat`, `fix`, `chore`, `docs`, `test`, `refactor`, and `ci` — SHALL state that a scope is required, and SHALL state that the Dependabot_Config `chore` prefix with scope is an instance of that convention.
6. THE Conduct_Doc SHALL list the behaviours expected of participants and the behaviours that are unacceptable, SHALL state the scope in which the document applies, SHALL name one enforcement contact address, and SHALL state the same 5-business-day acknowledgement window as the Security_Doc.
7. WHERE the Community_Templates are adopted, THE repository SHALL provide a bug-report issue template that asks for reproduction steps, expected result, actual result, affected module, and the Java and Gradle versions used, and a pull-request template that asks for a change summary, related issue references, and confirmation that `./gradlew build -PexcludeTags=integration` passed locally.
8. THE README SHALL link to the Security_Doc and the Contributing_Doc using repository-relative links that resolve to files present in the same commit.
9. WHEN a tool, workflow, or coverage gate named in the Security_Doc or the Contributing_Doc is added, renamed, or removed, THE same change SHALL update the affected entry in that document, so that no entry names a tool, workflow, or gate the repository no longer contains.
10. IF a README link to the Security_Doc or the Contributing_Doc does not resolve to an existing file, THEN THE Verification SHALL fail the change and SHALL report which link is unresolved.

### Requirement 15: Maintainer setup checklist

**User Story:** As the repository owner, I want every action only I can perform listed in one place, so that no badge silently renders "unknown" because of a missing account, secret, or repository setting.

#### Acceptance Criteria

1. THE Setup_Checklist SHALL be recorded in exactly one of the Contributing_Doc or the Security_Doc, under a single heading named "Maintainer setup checklist", and the other of those two documents and the README SHALL each link to that heading.
2. THE Setup_Checklist SHALL list creating a Codecov account covering the Repo_Slug and storing the Codecov_Token as a repository secret as two separate items, with the account item ordered before the secret item.
3. THE Setup_Checklist SHALL list enabling Dependabot alerts, enabling Dependabot security updates, and enabling the dependency graph in the repository security settings as three separate items.
4. THE Setup_Checklist SHALL list enabling GitHub code scanning for the Repo_Slug, and SHALL state that this item is complete when the repository code-scanning results page displays results produced by the CodeQL_Workflow.
5. THE Setup_Checklist SHALL list creating the repository labels `dependencies`, `gradle`, and `github-actions`, naming all three exactly as the Dependabot_Config references them.
6. THE Setup_Checklist SHALL list installing the Snyk GitHub app and installing the CodeRabbit GitHub app as two separate items, and SHALL state for each that its committed configuration file has no effect until the app is installed.
7. THE Setup_Checklist SHALL state, for every item, the badge or automation that depends on the item, the observable symptom if the item is skipped, and the check the Maintainer performs to confirm the item is complete.
8. THE Setup_Checklist SHALL number its items sequentially and SHALL place any item that depends on another item's completion after the item it depends on.
9. THE Setup_Checklist SHALL contain every action in this specification that only the Maintainer or a third-party-service account holder can perform, and SHALL contain no item that the build or a workflow performs automatically.
10. IF a Setup_Checklist item is not yet complete, THEN THE Setup_Checklist SHALL carry a status marker distinguishing that item as open rather than completed, and SHALL state which badge or automation remains unavailable while it stays open.

### Requirement 16: Verification of badges and workflows

**User Story:** As a maintainer, I want the new configuration verified before merge, so that the README does not ship broken images and the new workflows are not first run in anger.

#### Acceptance Criteria

1. WHEN the Badge_Block is added or changed, THE Verification SHALL request every badge image URL and every badge target URL present in the Badge_Block, following redirects, and SHALL confirm that each request returns a successful HTTP response within 10 seconds, retrying a failed request at most twice before treating it as failed.
2. WHEN the Badge_Block is added or changed, THE Verification SHALL confirm that each badge image response is a rendered badge rather than an error placeholder such as "invalid" or "not found".
3. WHEN a workflow file is added or changed, THE Verification SHALL confirm the file parses as valid YAML with zero parse errors, and that every `uses:` reference in the file resolves to a published action at the exact ref named, listing each unresolved reference by file and line.
4. WHEN a SAM template is added or changed, THE Verification SHALL confirm that `sam validate --template-file deployment/aws/sam/template.yaml` and `sam validate --template-file deployment/aws/sam-java/template.yaml` each exit with code 0.
5. WHEN a build file, a Version_Catalog entry, or a coverage configuration is added or changed, THE Verification SHALL confirm that `./gradlew build -PexcludeTags=integration` exits with code 0 and that all three Coverage_Reports exist, are non-empty, and are located at exactly the paths the Codecov_Upload passes to Codecov.
6. WHEN a pull request containing Badge_Block, workflow, Dependabot_Config, Codecov_Config, or build-file changes is opened, THE Verification SHALL run every check in criteria 1 through 5 that applies to the changed files and SHALL complete before that pull request is merged.
7. IF any Verification check fails, THEN THE Verification SHALL report the failing check and the artefact that caused it, and THE change SHALL remain unmerged until the artefact is corrected and every applicable check passes on a re-run.
8. IF a badge cannot be verified before merge because it depends on an incomplete Setup_Checklist item or on a workflow file that is not yet present on branch `main`, THEN THE Verification SHALL treat that badge as pending rather than failed, and THE Dev_Log SHALL record, in one entry, which badge is pending, which Setup_Checklist item or workflow unblocks it, and how the badge renders until then.
9. WHEN a gotcha is encountered while wiring the badges, workflows, or Dependabot, THE Dev_Log SHALL record it as one entry per gotcha, in the existing title / symptom / resolution form, before the change is merged.
