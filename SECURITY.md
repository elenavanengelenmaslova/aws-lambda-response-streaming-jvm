# Security Policy

This repository publishes one library (`nl.vintik:aws-lambda-streaming-core`) and two runnable example Lambdas. This document covers how to report a vulnerability, and what security and quality tooling is wired into the repository.

## Reporting a vulnerability

Report privately, through GitHub, on this repository:

**Security tab → Advisories → Report a vulnerability.**

That opens a private security advisory draft visible only to you and the maintainers. It is the only reporting route for this repository — there is no security mailing address.

**Do not report a vulnerability through a public issue, a pull request, a discussion, or a commit message.** A public report discloses the problem to everyone, including anyone consuming the published artifact, before a fix exists. If you have already opened something public, say so in the private report so the disclosure timeline can account for it.

This route depends on GitHub's private vulnerability reporting being enabled in the repository settings (Settings → Code security). If the Security tab shows no **Report a vulnerability** button, the setting is not yet on: ask the repository owner to enable it via their GitHub profile contact, **without including any detail of the vulnerability in that request**, and wait for the private channel rather than filing a public issue.

### What to include

- The affected module (`streaming-core`, `streaming-s3-example`, `streaming-s3-example-java`) and the version or commit SHA.
- What an attacker can do, and what access they need to do it.
- Reproduction steps, ideally a minimal test or request sequence.
- Any known workaround.

### What to expect

- **Acknowledgement within 5 business days** of the report.
- **A status update at least every 10 business days** until the report is closed, whether or not there is progress to show.
- Credit in the advisory when a fix ships, unless you ask otherwise.

### Supported versions

Fixes land on `main` and are published as a new release. There are no maintained release branches, so the latest published version of `nl.vintik:aws-lambda-streaming-core` is the only version that receives security fixes. Already-published versions on Maven Central are immutable and are never re-cut.

## Security and quality tooling

Eight tools guard this repository. Each entry below states what the tool is for, whether a README badge reports it, where its configuration lives, how often it runs, and the maintainer action needed to keep it active. Every maintainer action is a numbered item on the [maintainer setup checklist](CONTRIBUTING.md#maintainer-setup-checklist) in `CONTRIBUTING.md`.

### Codecov

- **Purpose:** collects the three modules' coverage reports and reports coverage on pull requests, so a change that drops coverage is visible before merge. The per-module gates themselves stay in the build, not in Codecov.
- **Badge:** yes — the `codecov` badge in the README badge block.
- **Config:** `codecov.yml` (repository root).
- **Cadence:** the coverage reports are produced on every build; the upload runs only on a push to `main`. Pull-request and Dependabot runs generate reports but do not upload, because they cannot read the upload token.
- **Maintainer action:** checklist items **1** (create the Codecov account covering the repository) and **2** (store the upload token as the `CODECOV_TOKEN` repository secret). Until item 2 is done the upload step logs a skip notice, the run still passes, and the badge renders "unknown".

### CodeQL

- **Purpose:** static analysis of the `java-kotlin` and `actions` languages, with results collected on the repository's code-scanning page.
- **Badge:** yes — the `CodeQL` badge in the README badge block, linking to the code-scanning results.
- **Config:** `.github/workflows/codeql.yml`.
- **Cadence:** on every push to `main`, on every pull request targeting `main`, and on a weekly schedule (Mondays, 04:17 UTC).
- **Maintainer action:** checklist item **7** — enable code scanning using the advanced workflow and leave GitHub's default CodeQL setup **off**, so `codeql.yml` is the single analysis source. With code scanning off, no results are accepted and the badge stays unresolved.

### Gradle wrapper validation

- **Purpose:** verifies the checked-in `gradle/wrapper/gradle-wrapper.jar` against the checksums of published Gradle releases, so a tampered wrapper JAR cannot execute arbitrary code in a build.
- **Badge:** no. Its result is visible as a check on the pull request.
- **Config:** `.github/workflows/gradle-wrapper-validation.yml`.
- **Cadence:** on every push to `main` and on every pull request, with no path filters — a wrapper change must be validated even when the change runs no Gradle task. The workflow deliberately invokes no Gradle itself.
- **Maintainer action:** none to make it run. Checklist item **12** optionally adds it alongside `ci-main-build.yml` as a required status check, which is what makes a failure block a merge rather than merely report.

### Dependency graph submission

- **Purpose:** submits the fully resolved Gradle dependency graph — including transitive dependencies, which a manifest scan misses — to GitHub's dependency graph. This is the data Dependabot alerts are raised against.
- **Badge:** no. Its result is visible under Insights → Dependency graph.
- **Config:** `.github/workflows/dependency-submission.yml`.
- **Cadence:** on every push to `main`, plus manual `workflow_dispatch`. It runs after a merge, so it never gates one.
- **Maintainer action:** checklist item **3** — enable the dependency graph. Until then the submission is rejected and the graph stays empty, so no transitive dependency is ever alerted on.

### Dependabot

- **Purpose:** opens pull requests for outdated Gradle dependencies and GitHub Actions versions, consolidated into one weekly multi-ecosystem group. Major, minor and patch updates are all in scope. Every Dependabot branch and pull request is validated by `ci-dependabot-validation.yml` before a human merges it; nothing auto-merges.
- **Badge:** no. Its output is the pull requests themselves.
- **Config:** `.github/dependabot.yml`. Versions it reads live in `gradle/libs.versions.toml` — a version hidden in a Kotlin-DSL `extra[…]` lookup is invisible to Dependabot's Gradle parser, which is why the version catalog exists.
- **Cadence:** weekly, Mondays at 06:00 `Etc/UTC`, at most five open pull requests per ecosystem.
- **Maintainer action:** checklist items **4** (enable Dependabot alerts, requires item 3), **5** (enable Dependabot security updates, requires item 4), and **6** (create the labels `dependencies`, `gradle`, `github-actions`). Without item 5, alerts are reported but nothing opens a fix; without item 6, the pull requests arrive unlabelled, because Dependabot silently drops labels it cannot find.

### Snyk

- **Purpose:** dependency, code (SAST) and IaC scanning, with the three SAM/OIDC templates under `deployment/aws/` in the IaC scan scope.
- **Badge:** **no badge reports Snyk.** Its findings are observable only in Snyk's own output on a pull request and in the Snyk dashboard.
- **Config:** `.snyk` (repository root).
- **Cadence:** per pull request, once the app is installed. The committed configuration has no effect before that — nothing in this repository reads `.snyk` on its own.
- **Maintainer action:** checklist item **8** — install the Snyk GitHub app on this repository. Confirmed by Snyk reporting on a pull request.

### CodeRabbit

- **Purpose:** AI pull-request review, scoped by path instructions to the things worth flagging here: `streaming-core` public-API stability and `explicitApi()` compliance, the example modules' AWS glue and streaming-protocol correctness, and test naming plus the coverage gates.
- **Badge:** **no badge reports CodeRabbit.** Its output is observable only as its own review comments on a pull request.
- **Config:** `.coderabbit.yaml` (repository root).
- **Cadence:** per pull request, once the app is installed. The committed configuration has no effect before that.
- **Maintainer action:** checklist item **9** — install the CodeRabbit GitHub app on this repository. Confirmed by a review appearing on the next pull request.

### Secret scanning

- **Purpose:** detects committed credentials, narrowed to the detector sets that can produce a true positive here — AWS, GitHub, and generic — with generated output and test resources excluded so they are not reported as source.
- **Badge:** **no badge reports secret scanning.** Results appear only in the scanning run's own output, and in GitHub's secret-scanning alerts once that is enabled.
- **Config:** `trufflehog-config.yml` (repository root).
- **Cadence:** none today. **`trufflehog-config.yml` is currently inert** — it is committed, but no workflow and no installed app in this repository consumes it, so no scan runs and no result is reported anywhere.
- **Maintainer action:** checklist item **10** — enable a secret-scanning run that consumes `trufflehog-config.yml` (a workflow or the equivalent app) and enable GitHub secret scanning alongside it. Confirmed by a completed run whose log shows the config's detectors and excluded paths in effect. Until item 10 is done, treat this repository as having no secret scanning.

## Not used, and why

Deliberate omissions, recorded so they are not mistaken for oversights. None of these has a badge, rendered or commented out.

| Not used | Why |
|---|---|
| OpenSSF Scorecard badge (and its workflow) | Excluded by the requester for this repository. |
| OpenSSF Best Practices badge | Excluded by the requester for this repository. |
| OpenAPI / Swagger validity badge | This repository publishes no OpenAPI document, so the badge would have nothing to validate. |
| Codacy | The ground it would cover is already covered: CodeQL for static analysis, Codecov for coverage, CodeRabbit for review. A fourth overlapping dashboard adds noise, not signal. |
| semantic-release | `workflow-publish.yml` triggers on `v*` tags and rejects anything that is not `vMAJOR.MINOR.PATCH`. The reference configuration's bare-semver tag format would cut tags that never trigger a publish. Releases stay manual. |
| Deploying on a push to `main` | Deploys stay on `cd-deploy-on-demand.yml`, keeping AWS cost and blast radius under manual control. |
| Dependabot auto-merge | CI validates every Dependabot pull request; a human merges it. |

## Maintainer setup

Every action above that only the repository owner or a third-party account holder can perform is listed once, in dependency order, under **[Maintainer setup checklist](CONTRIBUTING.md#maintainer-setup-checklist)** in `CONTRIBUTING.md`. Each item there states what it unblocks, the symptom if it is skipped, and how to confirm it is done.
