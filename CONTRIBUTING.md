# Contributing

Thanks for taking the time to work on this repository. It holds one published library and two runnable examples:

| Module | Language | Toolchain | Role |
|---|---|---|---|
| `:streaming-core` | Kotlin | Java 21 | The published library (`nl.vintik:aws-lambda-streaming-core`). Public API is explicit and checked. |
| `:streaming-s3-example` | Kotlin | Java 25 | Kotlin example Lambda streaming an S3 object. |
| `:streaming-s3-example-java` | Java | Java 25 | Pure-Java example Lambda, proving the library is usable without Kotlin. |

## Prerequisites

- A JDK on `PATH`; the build provisions the Java 21 and Java 25 toolchains through the foojay resolver.
- The Gradle wrapper (`./gradlew`, Gradle 9.0.0). Do not install Gradle separately and do not edit `gradle/wrapper/` — a workflow validates the wrapper JAR checksum on every push and pull request.
- **Colima** for the integration tests. This project does not use Docker Desktop.
- The AWS SAM CLI if you touch anything under `deployment/aws/`.

## Build commands

```bash
# Everything except the container-backed tests (what CI runs)
./gradlew build -PexcludeTags=integration

# One module
./gradlew :streaming-core:build -PexcludeTags=integration

# Coverage: the single invocation CI uses. --continue keeps the XML reports on
# disk when a gate fails, so you can open the report and see what is uncovered.
./gradlew verifyCoverageReports koverVerify koverHtmlReport -PexcludeTags=integration --continue

# SAM templates, after any change under deployment/aws/
sam validate --template-file deployment/aws/sam/template.yaml
sam validate --template-file deployment/aws/sam-java/template.yaml
```

Both `sam validate` invocations must exit 0 before you commit a template change.

## Test tags

Tests split into two sets by JUnit tag:

- **Untagged unit and property tests.** They need nothing beyond the JDK and run everywhere.
- **Tests tagged `integration`.** TestContainers + Floci start a real emulated S3, Lambda and API Gateway. They extend each module's `FlociS3IntegrationTestBase`.

`-PexcludeTags=integration` is honoured by every module's `test` task and is what CI passes, so no container is ever started in CI. Use it locally whenever you do not want to wait for containers:

```bash
./gradlew test -PexcludeTags=integration
```

To run the integration tests, **start Colima first** — TestContainers fails fast if the Docker daemon is down — and point TestContainers at Colima's socket:

```bash
colima start
export DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="/var/run/docker.sock"

./gradlew test        # no excludeTags: the tagged tests run too
```

`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` is required so the TestContainers reaper mounts the right socket path inside the VM. Never invoke the `docker` CLI from build or test code; let TestContainers manage containers.

## Coverage gates

| Module | Tool | Line gate | Local command |
|---|---|---|---|
| `:streaming-core` | Kover | **90%** | `./gradlew :streaming-core:koverVerify` |
| `:streaming-s3-example` | Kover | **80%** | `./gradlew :streaming-s3-example:koverVerify` |
| `:streaming-s3-example-java` | JaCoCo (behind `koverVerify`/`koverHtmlReport` aliases) | **80%** | `./gradlew :streaming-s3-example-java:koverVerify` |

Repo-wide, `./gradlew build -PexcludeTags=integration` runs all three gates. `./gradlew koverHtmlReport` per module writes the browsable HTML report. A gate failure names the module and its measured percentage.

## Public API changes (`:streaming-core` only)

`:streaming-core` runs with `explicitApi()` and the binary-compatibility validator. The public API is checked into `streaming-core/api/streaming-core.api` and `apiCheck` runs as part of `build`.

If you change anything public — a signature, a new public declaration, a visibility widening — regenerate the dump and commit it in the same change:

```bash
./gradlew :streaming-core:apiDump
./gradlew :streaming-core:apiCheck
```

A change that alters the public API without a matching `apiDump` fails the build. A refactor that is meant to be internal and still moves the dump is a signal to reconsider the change, not to accept the new dump.

## Dependencies: the version catalog rule

Every external dependency and plugin version lives in **`gradle/libs.versions.toml`** and is referenced from build files through a generated accessor:

```kotlin
// yes
implementation(libs.kotlinx.serialization.json)
testImplementation(libs.bundles.integration.testing)

// no
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
implementation("com.example:thing:${rootProject.extra["thingVersion"]}")
```

Adding a dependency means: add the version under `[versions]`, add the coordinate under `[libraries]` (or `[plugins]`), then reference the accessor. **Never an inline coordinate literal and never a version read from `extra[…]`.** Dependabot's Gradle parser reads literal coordinates, `gradle.properties` and version catalogs; it cannot evaluate Kotlin-DSL `extra` lookups, so a version hidden there is a version that never gets an update pull request.

The few deliberate exceptions — the foojay resolver literal in `settings.gradle.kts`, the composed Floci image tag, `:streaming-core`'s own `version`, and the two versionless entries resolved from a BOM or a parent artifact — are documented in [`docs/log.md`](docs/log.md), and the verification script reads that list as its allow-list. An undocumented literal version fails the check.

## Test naming

Tests use Given-When-Then, stating the input, the action, and the observable outcome. Kotlin uses backtick function names; Java uses `@DisplayName` with the same sentence:

```kotlin
@Test
fun `Given a 15MB S3 object When streamed Then body is byte-identical and memory stays bounded`() { … }
```

```java
@Test
@DisplayName("Given metadata built from Java When written by the library and decoded Then status and every header round-trip")
void metadataRoundTrips() { … }
```

Other test conventions: MockK mocks declared `mockk(relaxed = true)` at property level and reset with `clearMocks(...)`; `assertNotNull` before asserting on a nullable; `assertEquals` rather than `assertTrue(a == b)`; property-based tests as `@ParameterizedTest` with 10–20 diverse cases asserting a universal property; larger fixtures under `src/test/resources/test-data/`.

## Commit messages

[Conventional Commits](https://www.conventionalcommits.org/) with a **required scope**:

```
<type>(<scope>): <summary>
```

Accepted types: `feat`, `fix`, `chore`, `docs`, `test`, `refactor`, `ci`. Scope is the module or area — `streaming-core`, `streaming-s3-example`, `ci`, `deps`, `docs`.

Dependabot's `chore(deps): …` messages are an instance of this convention, not an exception to it: `.github/dependabot.yml` sets the `chore` prefix with scope included for both production and development dependency updates.

## Workflow map

Every file under `.github/workflows`, what triggers it, the Gradle command it runs, and whether its result blocks a merge into `main`.

| Workflow | Triggers | Gradle command | Blocks a merge into `main` |
|---|---|---|---|
| `ci-main-build.yml` | `push` to `main` (ignoring `**.md`, `docs/**`, `.kiro/**`), `pull_request` → `main` (`opened`, `reopened`, `synchronize`) | none directly — delegates to `workflow-build.yml` | **Yes** — the build badge's check, and the required status check |
| `ci-dependabot-validation.yml` | `push` to `dependabot/**`, `pull_request` → `main` (job runs only when the head branch is `dependabot/**`) | none directly — delegates to `workflow-build.yml` | Yes, on Dependabot pull requests only; skipped on human ones |
| `codeql.yml` | `push` to `main`, `pull_request` → `main`, weekly cron (Mondays 04:17 UTC) | `./gradlew assemble --stacktrace` (the `java-kotlin` matrix entry only; the `actions` entry runs no build) | Yes |
| `gradle-wrapper-validation.yml` | `push` to `main`, `pull_request` (any base branch, no path filters) | none — deliberately runs no Gradle task | Yes |
| `dependency-submission.yml` | `push` to `main`, `workflow_dispatch` | none directly — `gradle/actions/dependency-submission` resolves the graph | No — runs after the merge |
| `workflow-build.yml` | `workflow_call` (reusable: unit tests, coverage gates, Codecov upload, SAM validation) | `./gradlew verifyCoverageReports koverVerify koverHtmlReport -PexcludeTags=integration --continue`, plus both `sam validate` invocations | Not directly — it gates through its callers |
| `ci-feature-build.yml` | `push` to `feature/**` | none directly — delegates to `workflow-build.yml`, then deploy and streaming test | No |
| `cd-deploy-on-demand.yml` | `workflow_dispatch` | none directly — delegates to `workflow-build.yml`, then deploy and streaming test | No |
| `workflow-deploy-aws.yml` | `workflow_call` | `./gradlew <build-task> --no-build-cache` (the caller's `build-task` input) | No |
| `workflow-streaming-test.yml` | `workflow_call` | none — runs `scripts/pipeline-streaming-test.sh` against the deployed stack | No |
| `workflow-publish.yml` | `push` of a `v*` tag | `./gradlew :streaming-core:publishAndReleaseToMavenCentral` | No |

Deploys stay manual (`cd-deploy-on-demand.yml`) and releases stay manual (a `vMAJOR.MINOR.PATCH` tag). Neither happens on a merge to `main`.

## Before you open a pull request

1. `./gradlew build -PexcludeTags=integration` exits 0.
2. Both `sam validate` invocations exit 0, if you touched a template.
3. `./gradlew :streaming-core:apiDump` committed, if you changed the public API.
4. `./scripts/verify-quality-signals.sh` (or `--offline` to skip the network checks) reports no failures. It checks the badge block against its sources of truth, the workflow permission tables, the catalog invariants, and that this document's workflow map matches the files on disk. It is a local pre-merge tool rather than a CI job, so that a merge never depends on shields.io or codecov.io being reachable; moving its offline subset into CI later is a reasonable follow-up.

The pull-request template asks for the output of steps 1 and 4.

## Maintainer setup checklist

These thirteen actions cannot be performed by a contributor, an agent, or a workflow. Each needs the repository owner's settings access or a third-party account.

**Items 1–10 gate the badges and the automation they belong to** — until an item is done, its badge renders unresolved or its tool produces nothing, and no code change can substitute for it. **Items 11–12 gate the required-check behaviour** — without them the checks still run and still report, but nothing stops a red pull request from being merged. **Item 13 gates the security reporting route** — without it the route `SECURITY.md` documents does not exist, so a vulnerability has no private channel to arrive through.

Status markers: `[ ] open` means not yet done, `[x] done` means confirmed.

### 1. Create a Codecov account covering `elenavanengelenmaslova/aws-lambda-streaming-jvm-runtime`  `[ ] open`
- **Owner:** repository owner, as the Codecov account holder — sign in to codecov.io with GitHub and add the repository.
- **Unblocks:** the Codecov badge, and the upload target for the coverage step in `workflow-build.yml`.
- **If skipped:** the badge renders "unknown" and the upload step has nothing to upload to.
- **Confirm by:** `https://codecov.io/gh/elenavanengelenmaslova/aws-lambda-streaming-jvm-runtime` loading as a project page rather than a 404.

### 2. Store the Codecov upload token as the repository secret `CODECOV_TOKEN`  `[ ] open`
- **Owner:** repository owner — copy the upload token from the Codecov project settings into Settings → Secrets and variables → Actions. Requires item 1.
- **Unblocks:** the coverage upload step in `workflow-build.yml`, which `ci-main-build.yml` passes the secret to.
- **If skipped:** the upload is skipped with a `::notice::` naming this item, the run still passes, and the badge stays "unknown".
- **Confirm by:** a green upload step on the next push to `main`, and a percentage replacing "unknown" on the badge.

### 3. Enable the dependency graph  `[ ] open`
- **Owner:** repository owner — Settings → Code security, turn on the dependency graph.
- **Unblocks:** `dependency-submission.yml`'s submission target, and everything downstream of it (items 4 and 5).
- **If skipped:** the workflow's submission is rejected and the graph stays empty, so no transitive dependency is ever alerted on.
- **Confirm by:** Insights → Dependency graph listing entries for all three modules and naming the commit SHA of the last `main` push.

### 4. Enable Dependabot alerts  `[ ] open`
- **Owner:** repository owner — Settings → Code security, turn on Dependabot alerts. Requires item 3.
- **Unblocks:** vulnerability alerts on the submitted dependency graph.
- **If skipped:** known CVEs in direct or transitive dependencies are never reported for this repository.
- **Confirm by:** the Security → Dependabot alerts page being enabled rather than offering a "enable" prompt.

### 5. Enable Dependabot security updates  `[ ] open`
- **Owner:** repository owner — Settings → Code security, turn on Dependabot security updates. Requires item 4.
- **Unblocks:** automatic pull requests that fix an alert, in addition to the scheduled version bumps `.github/dependabot.yml` already configures.
- **If skipped:** alerts are reported but nothing opens a pull request to fix them; only the weekly scheduled updates arrive.
- **Confirm by:** the setting showing as enabled, and a security-update pull request appearing for the next alert raised.

### 6. Create the labels `dependencies`, `gradle`, and `github-actions`  `[ ] open`
- **Owner:** repository owner — Issues → Labels, create all three with exactly these names, as `.github/dependabot.yml` references them.
- **Unblocks:** label application on every Dependabot pull request, and any filtering or automation keyed on those labels.
- **If skipped:** Dependabot opens the pull requests but cannot apply a label it does not find, so they arrive unlabelled.
- **Confirm by:** the first Dependabot pull request carrying `dependencies` plus its ecosystem label.

### 7. Enable code scanning using the advanced workflow, with GitHub's default CodeQL setup left off  `[ ] open`
- **Owner:** repository owner — Settings → Code security → Code scanning: keep default setup **disabled** and let `.github/workflows/codeql.yml` be the analysis source.
- **Unblocks:** the CodeQL badge and the code-scanning results page.
- **If skipped, or if default setup is enabled instead:** with code scanning off, no results are accepted and the badge stays unresolved; with default setup on, it competes with `codeql.yml` and the advanced run's results are rejected as a duplicate configuration.
- **Confirm by:** Security → Code scanning showing results attributed to the `codeql.yml` workflow for both the `java-kotlin` and `actions` analyses.

### 8. Install the Snyk GitHub app on the repository  `[ ] open`
- **Owner:** repository owner, as the Snyk account holder — import the repository from the Snyk dashboard.
- **Unblocks:** Snyk's dependency, code, and IaC scanning against the committed `.snyk` configuration.
- **If skipped:** `.snyk` has no effect at all — nothing reads it, and no Snyk output appears on pull requests. No badge reports this either way.
- **Confirm by:** the repository appearing as a Snyk project, and Snyk commenting on or checking the next pull request.

### 9. Install the CodeRabbit GitHub app on the repository  `[ ] open`
- **Owner:** repository owner, as the CodeRabbit account holder — install the app and grant it this repository.
- **Unblocks:** AI review comments driven by the committed `.coderabbit.yaml` path instructions.
- **If skipped:** `.coderabbit.yaml` has no effect at all and no review comments are produced. No badge reports this either way.
- **Confirm by:** a CodeRabbit review appearing on the next pull request, respecting the include/exclude lists in the config.

### 10. Enable a secret-scanning run that consumes `trufflehog-config.yml`  `[ ] open`
- **Owner:** repository owner — add the TruffleHog run (a workflow or the equivalent app) that reads the committed config, and enable GitHub secret scanning alongside it.
- **Unblocks:** secret detection using the repository's own detector and exclusion lists.
- **If skipped:** `trufflehog-config.yml` is inert — it is committed but nothing in the repository reads it, so no scan happens and no result is reported anywhere.
- **Confirm by:** a completed scanning run whose log shows the config's detectors and excluded paths in effect.

### 11. Protect branch `main`  `[ ] open`
- **Owner:** repository owner — Settings → Rules/Branches, add a rule for `main` that requires a pull request before merging and requires status checks to pass.
- **Unblocks:** the merge gate itself, and item 12, which needs a rule to attach the required checks to.
- **If skipped:** every check still runs and still reports, but a red pull request — or a direct push to `main` — can land unblocked.
- **Confirm by:** a direct push to `main` being rejected, and an open pull request showing the "required" section in its merge box.

### 12. Mark `ci-main-build.yml` as a required status check on `main`  `[ ] open`
- **Owner:** repository owner — in the rule from item 11, add `ci-main-build.yml`'s check as required; `codeql.yml` and `gradle-wrapper-validation.yml` are worth adding alongside it. Requires item 11.
- **Unblocks:** merge blocking on a failing build, which is what makes the build badge's status consequential rather than informational.
- **If skipped:** the checks report pass or fail but the merge button stays green either way.
- **Confirm by:** a pull request with a deliberately failing build showing a blocked merge button naming the missing check.

### 13. Enable GitHub private vulnerability reporting  `[ ] open`
- **Owner:** repository owner — Settings → Code security, turn on private vulnerability reporting.
- **Unblocks:** the Security tab → Advisories → **Report a vulnerability** form, which is the only reporting route [`SECURITY.md`](SECURITY.md) documents.
- **If skipped:** the Security tab shows no **Report a vulnerability** button, so there is no private channel at all, and a reporter's only options are to wait or to disclose the problem publicly.
- **Confirm by:** the **Report a vulnerability** button appearing on the repository's Security → Advisories page.

### Prerequisites of a future change, not open items of this one

These two are needed only if the Dependabot validation pipeline is ever extended to deploy to AWS, which it deliberately does not do today. Nothing in the current setup is blocked by them:

- Adding `AWS_ACCOUNT_ID` to the **Dependabot** secrets store — a Dependabot run gets a read-only token and a separate secrets store, so the repository Actions secret is not visible to it.
- Adding `refs/heads/dependabot/*` to the OIDC trust policy in `deployment/aws/oidc/github-oidc-role.yaml`, so a Dependabot branch could assume the deploy role.
