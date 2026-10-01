---
name: Bug report
about: Report something that does not behave as documented
title: ''
labels: bug
assignees: ''
---

## Summary

One or two sentences describing what is broken.

## Affected module

Tick every module involved. If you are not sure, tick the one whose code appears in the stack trace.

- [ ] `streaming-core`
- [ ] `streaming-s3-example`
- [ ] `streaming-s3-example-java`
- [ ] Build, deployment or scripts (not a module)

## Reproduction steps

Numbered, from a clean checkout. Include the exact Gradle command or the request you sent.

1.
2.
3.

## Expected result

What you expected to happen.

## Actual result

What actually happened. Paste the relevant log lines or stack trace in a fenced block, with secrets, bucket names and tokens removed.

```text

```

## Where it fails

- [ ] Locally
- [ ] In CI (link the failing run: )
- [ ] Both

## Versions

| Item | Value |
|---|---|
| Kotlin version | |
| Java version (`java -version`) | |
| Gradle version (`./gradlew --version`) | |
| Operating system | |
| Container runtime, if integration tests are involved (this project uses Colima, not Docker Desktop) | |

## Additional context

Anything else that narrows it down — whether it reproduces against real AWS, whether `-PexcludeTags=integration` changes the outcome, recent dependency bumps.
