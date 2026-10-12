# Requirements Document

## Introduction

This feature adds a GitHub Actions release-automation pipeline that sits **upstream** of the existing publish workflow (`.github/workflows/workflow-publish.yml`). The existing publish workflow already triggers on push of a `vX.Y.Z` tag, validates strict SemVer, derives the release version from the tag, and publishes `streaming-core` to Maven Central. It must not be rewritten.

The new pipeline watches `main` for changes to the published library's production sources or build file, computes the next SemVer version from Conventional Commits since the latest tag, creates and pushes the matching `vX.Y.Z` tag (which fires the existing publish workflow), creates a GitHub Release with auto-generated notes, and opens a README-update pull request on a `release/vX.Y.Z` branch for the maintainer to review and merge. The pipeline is responsible only for producing the tag, the release notes, and the README PR — never for publishing. A built-in path guard guarantees that merging the README PR cannot re-trigger a release.

## Glossary

- **Release_Pipeline**: The new GitHub Actions automation defined by this feature. It computes the version, creates and pushes the tag, creates the GitHub Release, and opens the README-update PR.
- **Publish_Workflow**: The existing `.github/workflows/workflow-publish.yml`, which triggers on `vX.Y.Z` tag push and publishes `streaming-core` to Maven Central. Out of scope for modification.
- **Release_Tag**: A Git tag of the exact form `vMAJOR.MINOR.PATCH` (strict SemVer), e.g. `v2.1.0`. The single source of truth for the published version.
- **Latest_Tag**: The most recent existing `Release_Tag` reachable in repository history, used as the baseline for computing the next version.
- **Conventional_Commits**: The commit-message convention where a `fix:` prefix denotes a patch, `feat:` denotes a minor, and `feat!:` or a `BREAKING CHANGE` footer denotes a major change.
- **Library_Source_Paths**: The path set `streaming-core/src/main/**` and `streaming-core/build.gradle.kts`. Changes to these paths are the only trigger for a release.
- **Release_Branch**: A branch named `release/vMAJOR.MINOR.PATCH` created to carry the README version update.
- **README_Artifact_Version**: The hardcoded dependency coordinate in `README.md` (~line 33): `implementation("nl.vintik:aws-lambda-streaming-core:X.Y.Z")`.
- **Generated_Release_Notes**: GitHub's auto-generated release notes derived from merged pull requests and commits since the previous `Release_Tag` (e.g. via `gh release create vX.Y.Z --generate-notes`).
- **Initial_Version**: The `Release_Tag` used when no prior `Release_Tag` exists in the repository.
- **GITHUB_TOKEN**: The default token GitHub Actions provides to a workflow run.
- **Release_PAT**: A personal access token (or equivalent credential), stored as the repository secret `RELEASE_PAT`, that the Release_Pipeline is required to use for pushing the Release_Tag, creating the GitHub Release, and opening the README-update pull request. It is required because a tag pushed with GITHUB_TOKEN does not trigger `on: push` tag workflows, so the Publish_Workflow would not run; and a pull request opened with GITHUB_TOKEN gets `pull_request` runs in an approval-required state that a user with write access must approve, which the Release_PAT avoids.
- **Dependency_Scope_Commit**: A Dependabot commit whose subject uses a dependency scope: `chore(deps)`, `chore(deps-dev)`, `build(deps)`, or `build(deps-dev)`.

## Requirements

### Requirement 1: Trigger only on published-library changes

**User Story:** As a library maintainer, I want releases to be cut only when the published library's production code or build file changes, so that documentation, example-module, tooling, and configuration changes never produce a release.

#### Acceptance Criteria

1. WHEN a push to the `main` branch includes a change to a path within `streaming-core/src/main/**` or to `streaming-core/build.gradle.kts`, THE Release_Pipeline SHALL start a release run.
2. IF a push to the `main` branch touches no path within `Library_Source_Paths`, THEN THE Release_Pipeline SHALL NOT start a release run.
3. THE Release_Pipeline SHALL use GitHub Actions path filters to restrict the trigger to `Library_Source_Paths`.

### Requirement 2: Compute the next version from Conventional Commits

**User Story:** As a library maintainer, I want the next version computed automatically from Conventional Commits since the last tag, so that version bumps are consistent and require no manual decision.

#### Acceptance Criteria

1. WHEN a release run starts, THE Release_Pipeline SHALL determine the Latest_Tag by selecting the most recent existing Release_Tag of the form `vMAJOR.MINOR.PATCH`.
2. WHEN at least one commit since the Latest_Tag contains a `feat!:` prefix or a `BREAKING CHANGE` footer, THE Release_Pipeline SHALL increment the major version and reset the minor and patch versions to zero.
3. WHEN the commits since the Latest_Tag contain at least one `feat:` prefix and no major-level change, THE Release_Pipeline SHALL increment the minor version and reset the patch version to zero.
4. WHEN the commits since the Latest_Tag contain at least one `fix:` prefix and no minor-level or major-level change, THE Release_Pipeline SHALL increment the patch version.
5. IF no Release_Tag exists in the repository when a release run starts, THEN THE Release_Pipeline SHALL use the Initial_Version `v1.0.0` as the computed version.
6. WHEN the commits since the Latest_Tag contain a Dependency_Scope_Commit, THE Release_Pipeline SHALL treat that Dependency_Scope_Commit as a patch-level change (a repository-specific convention that extends vanilla Conventional_Commits), and SHALL apply the standard bump rules to all other Dependabot commits.
7. WHEN the aggregate commit set since the Latest_Tag contains commits of differing bump levels, THE Release_Pipeline SHALL select the highest bump level present using the precedence major > minor > patch, including Dependabot commits and Dependency_Scope_Commits in the same aggregate commit set as all other commits.
8. THE Release_Pipeline SHALL express the computed version as a Release_Tag of the exact form `vMAJOR.MINOR.PATCH`.
9. WHEN the Release_Pipeline classifies a commit, THE Release_Pipeline SHALL determine the commit type and the `!` breaking marker from the commit subject alone, and SHALL examine the commit body only for a `BREAKING CHANGE:` footer.

### Requirement 3: Create and push the release tag

**User Story:** As a library maintainer, I want the computed tag created and pushed automatically, so that the existing publish workflow fires without manual tagging.

#### Acceptance Criteria

1. WHEN the next version has been computed, THE Release_Pipeline SHALL create a Git tag equal to the computed Release_Tag.
2. WHEN the Release_Tag has been created, THE Release_Pipeline SHALL push the Release_Tag to the repository.
3. THE Release_Pipeline SHALL NOT publish any artifact to Maven Central.
4. IF the computed Release_Tag already exists in the repository, THEN THE Release_Pipeline SHALL NOT create or push a tag, SHALL reuse the existing Release_Tag to create any missing GitHub Release or README-update pull request without creating duplicates, and SHALL make no changes when both the GitHub Release and the README-update pull request already exist.
5. THE Release_Pipeline SHALL push the Release_Tag using the Release_PAT so that the push triggers the Publish_Workflow.
6. IF the Release_PAT is not configured, THEN THE Release_Pipeline SHALL fail the release run before creating or pushing a tag, so that a Release_Tag and GitHub Release are never created without triggering artifact publication.

### Requirement 4: Create the GitHub Release with generated notes

**User Story:** As a library maintainer, I want a GitHub Release with auto-generated notes attached to each release tag, so that consumers see what changed without a committed changelog file.

#### Acceptance Criteria

1. WHEN the Release_Tag has been pushed, THE Release_Pipeline SHALL create a GitHub Release attached to the Release_Tag.
2. WHEN the Release_Pipeline creates the GitHub Release, THE Release_Pipeline SHALL populate the release body with Generated_Release_Notes derived from merged pull requests and commits since the previous Release_Tag.
3. THE Release_Pipeline SHALL NOT create or commit a `CHANGELOG.md` file.
4. IF generation of the Generated_Release_Notes fails or produces empty content, THEN THE Release_Pipeline SHALL fail the release step and SHALL NOT leave a published GitHub Release with a missing or empty body.

### Requirement 5: Open the README-update pull request on a release branch

**User Story:** As a library maintainer, I want the README artifact version updated via a reviewable pull request after the tag exists, so that the published documentation matches the released version under my review.

#### Acceptance Criteria

1. WHEN the Release_Tag has been created, THE Release_Pipeline SHALL create a Release_Branch named `release/` followed by the Release_Tag.
2. WHEN the Release_Branch has been created, THE Release_Pipeline SHALL update the README_Artifact_Version in `README.md` to `nl.vintik:aws-lambda-streaming-core:MAJOR.MINOR.PATCH` matching the Release_Tag.
3. WHEN the README_Artifact_Version has been updated, THE Release_Pipeline SHALL commit the change to the Release_Branch.
4. WHEN the README change has been committed to the Release_Branch, THE Release_Pipeline SHALL open a pull request from the Release_Branch targeting the `main` branch.

### Requirement 6: Prevent a release re-trigger loop

**User Story:** As a library maintainer, I want merging the README pull request to never start another release, so that the pipeline cannot loop.

#### Acceptance Criteria

1. WHEN the README-update pull request is merged into `main`, THE Release_Pipeline SHALL NOT start a release run.
2. THE Release_Pipeline SHALL restrict the README-update pull request to changes outside `streaming-core/src/main/**` and `streaming-core/build.gradle.kts` so that the path filter in Requirement 1 excludes the merge.

### Requirement 7: Required workflow permissions and branch-protection handling

**User Story:** As a repository administrator, I want the pipeline to hold the permissions it needs and account for branch protection, so that tagging, releasing, and PR creation succeed and the README PR can satisfy required checks.

#### Acceptance Criteria

1. WHERE the Release_Pipeline is granted `contents: write` permission, THE Release_Pipeline SHALL use it to create tags, create branches, and create GitHub Releases, and THE Release_Pipeline SHALL be configured with `contents: write` so these capabilities are available.
2. THE Release_Pipeline SHALL run with `pull-requests: write` permission to open the README-update pull request.
3. THE Release_Pipeline SHALL use the Release_PAT to push the Release_Tag, create the GitHub Release, and open the README-update pull request, so that the Publish_Workflow is triggered and the pull request's required status checks run without manual approval.

### Requirement 8: Ordering of post-tag steps

**User Story:** As a library maintainer, I want the GitHub Release and the README PR produced only after the tag exists, so that both reflect the released version.

#### Acceptance Criteria

1. THE Release_Pipeline SHALL create the GitHub Release only after the Release_Tag has been pushed.
2. THE Release_Pipeline SHALL create the Release_Branch and open the README-update pull request only after the Release_Tag has been created.
3. WHEN the README-update pull request is opened, THE Release_Pipeline SHALL set the README_Artifact_Version to the version carried by the Release_Tag.
4. WHEN the GitHub Release is created, THE Release_Pipeline SHALL attach the Generated_Release_Notes to the Release_Tag.
