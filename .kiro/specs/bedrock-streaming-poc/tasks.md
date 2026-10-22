# Implementation Plan: Bedrock Streaming POC, 40s Timeout Proof, Conditional Module, Documentation

## Overview

Investigation-first plan on `aws-lambda-streaming-jvm-runtime` (account `021259937026`, eu-west-1). Resolve the Bedrock streaming question before anything else, prove the 40s integration-timeout claim, then conditionally build a production module, then do all documentation last. The deployed `s3-file-streaming-endpoint` stack and handler are never modified. Bedrock invocations are gated behind explicit user confirmation. No secrets reach recordings, logs, or the repo.

Standards: Kotlin 2.3.x / Java 25, Kotlin AWS SDK (not Java SDK), `--no-build-cache`, kotlin-logging structured lambdas, kotlinx-serialization, MockK + JUnit 6, given-when-then backtick test names. New Lambdas mirror `streaming-s3-example` (SnapStart `ApplyOn: PublishedVersions` + `AutoPublishAlias: live`, CRaC priming, arm64, java25, tiered-compilation `JAVA_TOOL_OPTIONS`).

## Tasks

- [x] 0. Author the spec documents under `.kiro/specs/bedrock-streaming-poc/` (`requirements.md`, `design.md`, `tasks.md`), matching existing spec conventions.
  - _Requirements: 1.1, 1.2, 1.3_

- [ ] 1. Verify Nova Lite availability and draft the scoped Bedrock IAM grant (NO invocation)
  - Resolve the Nova Lite inference-profile id and confirm eu-west-1 availability (`aws bedrock list-inference-profiles` / `list-foundation-models --region eu-west-1`).
  - Draft a `bedrock:InvokeModelWithResponseStream` IAM statement scoped only to the Nova resource for the Exp_Handler role.
  - Do not invoke any model.
  - _Requirements: 2.1, 2.2, 2.3_

- [ ] 2. Build the throwaway Bedrock streaming Lambda (minimal standalone handler)
  - Standalone Kotlin `RequestStreamHandler`: parse prompt from the body, `InvokeModelWithResponseStream` on Nova Lite via `aws.sdk.kotlin:bedrockruntime`, `ResponseWriter().writeMetadata` once, then per text delta `output.write(bytes); output.flush()`. Light per-token logging (index + elapsed ms, no content/secrets). CRaC priming hook. No `RequestResolver`/`StreamSource` wiring. Build `--no-build-cache`.
  - Test: MockK-mocked Bedrock streaming unit test — metadata once before any body byte, ordered delta write+flush, clean close.
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6_

- [ ] 3. Copy the SAM template for a throwaway Bedrock stack and deploy it
  - Copy `template.yaml` -> `template-bedrock-exp.yaml`: new function name/handler, route `/exp/bedrock-stream`, drop S3 bucket, add Task 1 Bedrock IAM; keep RESPONSE_STREAM, SnapStart, arm64, java25, tiered-compilation, API-key auth.
  - `sam validate` (exit 0). Build throwaway jar (`--no-build-cache`), deploy foreground as `bedrock-stream-exp`, poll `describe-stacks` to `*_COMPLETE`, confirm `CodeSha256` matches.
  - _Requirements: 4.1, 4.2, 4.3, 4.4_

- [ ] 4. Measure Bedrock streaming cadence (PAID — STOP for explicit user confirmation first)
  - Obtain explicit confirmation before the first invocation. One warmup (discarded) + 2–3 measured runs: real `curl --http1.1 -N -H "x-api-key: $KEY" "$EXP_URL/exp/bedrock-stream" -d '<prompt>'` piped to a Python per-chunk ms reader. Capture first-byte, last-byte, first-to-last spread, distinct 100ms buckets, >1 wave vs terminal burst. Key/host off-screen if recorded.
  - _Requirements: 5.1, 5.2, 5.3, 5.4_

- [ ] 5. Report the Bedrock verdict and log it
  - Report progressive (smooth/bursty) vs terminal burst with the timing table. Append a `docs/log.md` entry (no secrets). This verdict gates Tasks 10–13.
  - _Requirements: 6.1, 6.2_

- [ ] 6. Mandatory teardown of the Bedrock throwaway experiment
  - Delete `bedrock-stream-exp` (+ IAM) to `DELETE_COMPLETE`. Delete the copied template, throwaway handler source, experiment-only jars; revert real-file edits; clear `/tmp`. Report `git status`; confirm the real stack untouched.
  - _Requirements: 7.1, 7.2, 7.3, 7.4_

- [ ] 7. Resolve the AWS streaming/integration timeout figures from official docs
  - Obtain the API Gateway REST integration-timeout, Lambda response-streaming duration/idle figures, and the Lambda max timeout from official AWS docs; record exact numbers + source URLs. Note any discrepancy with the repo README "Limitations".
  - _Requirements: 8.1, 8.2, 8.3_

- [ ] 8. Prove streaming survives past the API Gateway integration timeout (~40s run)
  - Choose mechanism (a) natural 2 MB/s post-6MB throttle on a throwaway S3-stack copy, or (b) throwaway slow-drip handler; prefer (a) if one object cleanly exceeds 40s. Never modify the deployed stack/handler. Warm once; real `curl --http1.1 -N -w '%{time_starttransfer} %{time_total} %{http_code} %{size_download}'`; assert total >= 40s, HTTP 200, full byte count, TTFB << total. Record per-chunk ms across the timeout mark.
  - _Requirements: 9.1, 9.2, 9.3_

- [ ] 9. Teardown of the 40s-test throwaway resources + log the finding
  - Delete any Task 8 throwaway resource to `DELETE_COMPLETE`; remove scratch; revert real-file edits. Append a `docs/log.md` entry (duration, mechanism, cleared integration timeout). Report `git status`; confirm the real stack untouched.
  - _Requirements: 9.4_

### Conditional — Tasks 10–13 run ONLY IF Task 5's verdict is progressive arrival AND the user greenlights

- [ ] 10. Scaffold the `streaming-bedrock-example` module
  - `include(...)` in `settings.gradle.kts`; `build.gradle.kts` mirroring the S3 example (shadow, kover 80%, Java 25); add `aws-sdk-kotlin-bedrockruntime` to the catalog. Implement `BedrockSource : StreamSource<R>` (head -> unknown/chunked size), `PromptResolver : RequestResolver<R>`, wired through the shared `StreamHandler`, + `Priming`.
  - Test: unit + property tests (MockK), >=80% Kover, given-when-then names.
  - _Requirements: 10.1, 10.2, 10.3_

- [ ] 11. SAM template + deploy scripts for the Bedrock example (production-grade)
  - Template under `deployment/aws/sam-bedrock/` mirroring the S3 template (RESPONSE_STREAM, SnapStart, priming, arm64, java25, tiered-compilation, scoped Bedrock IAM, API-key auth) + build/deploy scripts. `sam validate` exit 0; deploy to a dedicated stack to `*_COMPLETE`; `CodeSha256` matches.
  - _Requirements: 10.3_

- [ ] 12. Post-deploy progressive-arrival integration test (opt-in, cost-guarded)
  - `scripts/` test mirroring `pipeline-streaming-test.sh`: first-to-last spread >= ~50% of generation time, >1 arrival bucket, terminal sentinel received. API_KEY mode; opt-in `*_ENABLED` flag; key masked in CI.
  - _Requirements: 10.3_

- [ ] 13. `streaming-bedrock-example/README.md` + module wiring (content only; GIF later)
  - Add the module README; reference it from the root README "Example modules" table.
  - _Requirements: 10.2_

### Documentation — last

- [ ] 14. Record the S3 streaming demo GIF
  - Resolve endpoint + key off-camera; export `URL`/`KEY`. Warm once. Record (~`--rows 5 --cols 90 --overwrite`) a `printf` placeholder line then the real bare `curl -N ... -o /dev/null` so the meter animates 0->12.0M. Grep `.cast` for zero real-key/real-host (abort render otherwise). Render `agg <cast> docs/s3-streaming-demo.gif --theme monokai --font-size 17`; ffmpeg mid-frame confirms command + intermediate %.
  - _Requirements: 11.1_

- [ ] 15. Pause for the user's S3 GIF review (GATE)
  - Report GIF path, exact rec/agg commands, leak-grep confirmation. Do not change any README until approved; change request loops back to Task 14.
  - _Requirements: 11.2_

- [ ] 16. Restructure the root README with the S3 GIF hero + green-checkmark checklist
  - Title + badges unchanged -> hero GIF -> green-check checklist (6 MB->200 MB; integration timeout does not cap streaming, bounded by idle-timeout window + Lambda max timeout, using Task 7 figures refined by Task 8; flat memory via bounded buffer) -> existing library intro preserved -> `docs/SolutionDesign.png` in a "How it works" spot. No existing content dropped.
  - _Requirements: 11.3_

### Documentation — conditional (ONLY IF Task 10 built the module)

- [ ] 17. Record the Bedrock demo GIF, then pause for the user's manual review (GATE)
  - Task-14-style GIF of the Bedrock token stream; off-camera `KEY`/`EXP_URL`, warm first, `.cast` leak-grep = 0, agg monokai font 17, ffmpeg mid-frame. Save `docs/bedrock-streaming-demo.gif`. STOP and report; do not wire into any README until approved.
  - _Requirements: 11.4_

- [ ] 18. Wire the approved Bedrock GIF into the READMEs + final docs pass
  - After sign-off: add the GIF to `streaming-bedrock-example/README.md` and the root README. Append remaining `docs/log.md` gotchas. Report final `git status`; confirm live stacks at expected state (real S3 intact; throwaway gone); summarize the verdict + what was built.
  - _Requirements: 11.4, 11.5_
