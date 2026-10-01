## Summary

What this change does, and why. One paragraph is usually enough.

## Related issues

Closes #
<!-- Add "Refs #" lines for issues this touches but does not close. -->

## Modules touched

- [ ] `streaming-core`
- [ ] `streaming-s3-example`
- [ ] `streaming-s3-example-java`
- [ ] Build, deployment or scripts (not a module)

## Checks run locally

All four are required. Tick a box only after the command has actually run on this branch.

- [ ] **Build** — `./gradlew build -PexcludeTags=integration` exited 0.
- [ ] **Coverage gates** — `./gradlew verifyCoverageReports koverVerify koverHtmlReport -PexcludeTags=integration --continue` exited 0, meeting 90% on `:streaming-core` and 80% on each example module.
- [ ] **Public API dump** — `./gradlew :streaming-core:apiDump` run, and `streaming-core/api/streaming-core.api` is either unchanged or its diff is committed and explained below. Any change here is a public-API change.
- [ ] **Quality signals** — `scripts/verify-quality-signals.sh` run and its output pasted below. This script is a local pre-merge tool, not a CI job, so this paste is the only record of it.

<details>
<summary><code>scripts/verify-quality-signals.sh</code> output</summary>

```text

```

</details>

### Public API diff

Not applicable, or: what changed in `streaming-core/api/streaming-core.api` and why it is safe for consumers.

### Integration tests

Integration tests are excluded by `-PexcludeTags=integration`. If you ran them, confirm Colima was running (`colima start`) and say which suites passed. If you did not run them, say so.

## Documentation

- [ ] `docs/log.md` has an entry for every gotcha, bug and fix hit while making this change, in the existing title / symptom / resolution form.
- [ ] Any tool, workflow or coverage gate this change adds, renames or removes is updated in `SECURITY.md` and `CONTRIBUTING.md`.
- [ ] Not applicable — no gotchas, and no tool, workflow or gate changed.

## Notes for the reviewer

Anything that needs a human eye: deliberate trade-offs, follow-ups left out of scope, parts that could not be verified locally.
