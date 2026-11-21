# Implementation Plan: Java Bedrock SSE Streaming Endpoint

## Overview

This plan adds `streaming-bedrock-example-java`, a Java Lambda that relays Amazon Bedrock Nova Lite tokens as Server-Sent Events through API Gateway, token by token, and adds two Java-friendly entry points to the published libraries. It proves that `aws-lambda-streaming-sse` works from plain Java on a deployed endpoint.

Work proceeds bottom-up and library-first: the two additive library entry points (the only change that triggers a release), then module scaffolding, the pure components (parser, Nova payload, deadline, delta bridge), the SDK glue, priming and the handler, then packaging, coverage, the SAM stack and CI wiring. Deploy-and-measure tasks come last because they need the pipeline and a paid model call; they are marked **user-run**. Every coding task ends at a `./gradlew` checkpoint and every SAM change ends with `sam validate`.

Language: **Java 25** (toolchain) for the new module, consuming the Java-21-compiled libraries. Package root: `nl.vintik.streaming.bedrock`; test packages mirror `main/java`. Each of the seven Correctness Properties becomes one `@ParameterizedTest` tagged `Feature: java-bedrock-sse-streaming-endpoint, Property N: …`.

Constraints that apply throughout:
- The Kotlin examples and the existing stacks must keep building and deploying. Task 13.4 is the only task allowed to change Kotlin example behaviour, and only by the rule fixed in the design.
- Library changes are additive. Commit them as `feat:`, never `feat!:`, so the release is MINOR (Req 14.4).
- Example-module paths stay out of the release trigger filter (Req 13.9).
- Never log prompts, model ids, keys or tokens; the Dev_Log records secrets by descriptive name only (Req 15.6).

## Tasks

- [x] 1. Add the Java-friendly library entry points
  - [x] 1.1 Add `@JvmOverloads` to `ResponseMetadata`'s primary constructor in `streaming-core`
    - Change only the constructor modifier; do not touch the properties, `@Serializable` or `@EncodeDefault`.
    - If the serialization plugin rejects it, fall back to an explicit secondary `constructor(statusCode, headers) : this(statusCode, headers, null)` and record the reason in `docs/log.md`.
    - _Requirements: 2.2, 2.3_
  - [x] 1.2 Write a Java test for the `ResponseMetadata` convenience constructor
    - Create `streaming-core/src/test/java/...`: construct `new ResponseMetadata(200, headers)` from Java, write it through `new ResponseWriter()`, split on the 8-null-byte delimiter, decode the JSON and assert status and headers round-trip and that no `cookies` member is emitted.
    - Confirm the module compiles Java test sources; if the Kotlin plugin setup does not, add the minimal configuration.
    - Run the existing `streaming-core` Kotlin tests unchanged to prove source compatibility.
    - _Requirements: 2.2, 2.3, 2.6_
  - [x] 1.3 Add `ResponseBody` and `StreamingResponse.stream` to `streaming-sse`
    - `public fun interface ResponseBody { @Throws(IOException::class) public fun write(output: OutputStream) }`.
    - `@Throws(IOException::class) public fun stream(requestId: String, metadata: ResponseMetadata, body: ResponseBody)` delegating to `send` so every guarantee of `send` applies unchanged.
    - Do not add an overload named `send`.
    - _Requirements: 2.1, 2.3_
  - [x] 1.4 Write a Java test for `stream`
    - Create `streaming-sse/src/test/java/...`: use a Java lambda `ResponseChannelFactory`, `requireAgent=false`, and a Java lambda body that throws `IOException`.
    - Assert: success writes metadata then body and marks the invocation answered; a throwing body completes the channel, does not mark answered, and propagates the same `IOException`; the existing Kotlin `StreamingResponse` tests still pass.
    - _Requirements: 2.1, 2.3, 2.6_
  - [x] 1.5 Regenerate and check the public API dumps
    - Run `./gradlew :streaming-core:apiDump :streaming-sse:apiDump`, then `apiCheck`.
    - Confirm the diff contains additions only (the two-argument constructor, `ResponseBody`, `stream`) and no removed lines. Commit the dumps in the same change.
    - _Requirements: 2.4, 2.5, 14.4_
  - [x] 1.6 Checkpoint - run `./gradlew :streaming-core:build :streaming-sse:build apiCheck -PexcludeTags=integration`. Ensure all tests pass and `apiCheck` is green. Ask the user if questions arise.

- [x] 2. Scaffold the Java module
  - [x] 2.1 Register the module and its dependencies
    - Add `include("streaming-bedrock-example-java")` to `settings.gradle.kts`.
    - Add versionless catalog entries `aws-sdk-java-bedrockruntime` (`software.amazon.awssdk:bedrockruntime`) and `aws-sdk-java-netty-nio` (`software.amazon.awssdk:netty-nio-client`), versions from the existing `awsSdkJava` BOM.
    - _Requirements: 1.1, 4.7, 4.8_
  - [x] 2.2 Create `streaming-bedrock-example-java/build.gradle.kts`
    - Plugins: `java`, `com.gradleup.shadow`, `jacoco`. Java 25 toolchain.
    - Dependencies: `project(":streaming-core")`, `project(":streaming-sse")`, `libs.aws.lambda.core`, `platform(libs.aws.sdk.java.bom)`, the two new entries, `libs.jackson.databind`, `libs.slf4j.simple`, `libs.crac`. Do not declare `kotlin-stdlib` (Req 1.6); if compilation proves it necessary, stop and record why, because that is evidence for Req 2.
    - Test: JUnit Jupiter, Mockito, `junit-platform-launcher`; `useJUnitPlatform` honouring `-PexcludeTags`.
    - Create source roots `src/main/java/nl/vintik/streaming/bedrock` and `src/test/java/nl/vintik/streaming/bedrock`.
    - _Requirements: 1.1, 1.6, 4.7, 4.8, 12.4_
  - [x] 2.3 Add the new build file to the catalog invariant check
    - Add `streaming-bedrock-example-java/build.gradle.kts` to `catalog_build_files` in `scripts/verify/checks/check_catalog.sh`. Read the check first; if it enumerates versionless entries or the Requirement 9.8 allow-list, update both it and the matching `docs/log.md` section so the two cannot drift.
    - _Requirements: 4.7_
  - [x] 2.4 Checkpoint - run `./gradlew :streaming-bedrock-example-java:build -PexcludeTags=integration` and confirm the module configures and compiles, and that `streaming-core`, `streaming-sse` and all examples still build. Ask the user if questions arise.

- [x] 3. Implement the prompt model and parser
  - [x] 3.1 Create the data models
    - `PromptRequest(String prompt, int maxTokens)` record; `sealed interface PromptResult` with `Resolved(PromptRequest)` and `Rejected(int status, String message)`.
    - Constants: `DEFAULT_PROMPT`, `DEFAULT_MAX_TOKENS = 1000`, `MAX_PROMPT_LEN = 8000`, `MAX_TOKENS_CEILING = 5000`.
    - _Requirements: 3.1, 3.2, 3.4_
  - [x] 3.2 Implement `PromptParser`
    - Follow the design's parser table exactly: proxy event with `body` versus bare payload; absent, empty and `null` body use defaults; non-JSON body, non-string prompt, over-long prompt and any member other than `prompt` / `maxTokens` are `Rejected(400, …)`; the rejected member's name is truncated to 64 characters; non-numeric `maxTokens` uses the default then clamps.
    - Log only exception types, never the payload.
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6_
  - [x] 3.3 Write unit tests for every row of the parser table
    - Include `body: null`, a hostile parameter name containing quotes and newlines (assert the serialised error stays valid JSON through `ResponseWriter.writeError`), and a 10,000-character parameter name (assert truncation).
    - _Requirements: 3.1, 3.2, 3.3, 3.5, 3.6_
  - [x] 3.4 Write the clamping property test
    - **Property 4: The token count never exceeds the ceiling** — `@ParameterizedTest` over 10–20 values of `maxTokens` (negative, zero, 1, ceiling, ceiling + 1, `Integer.MAX_VALUE`, a long beyond int range, fractional, string, null, absent), asserting the resolved value lies in `[1, MAX_TOKENS_CEILING]`.
    - Tag: `Feature: java-bedrock-sse-streaming-endpoint, Property 4: the token count never exceeds the ceiling`.
    - **Validates: Requirements 3.4, 10.3**
  - [x] 3.5 Write the unsupported-parameter property test at parser level
    - **Property 6: Unsupported parameters never reach the model** — over 10–20 extra member names (including the removed padding knobs `headPadBytes`, `tailPadBytes`, `headPadEvents`), assert `Rejected(400, …)` naming the member. The handler-level zero-model-interaction half is asserted in task 8.3.
    - **Validates: Requirements 3.6**
  - [x] 3.6 Checkpoint - run `./gradlew :streaming-bedrock-example-java:test -PexcludeTags=integration`. Ensure all tests pass. Ask the user if questions arise.

- [x] 4. Implement the Nova payload helpers
  - [x] 4.1 Implement `NovaPayload`
    - `requestBody(prompt, maxTokens)` builds the `messages-v1` body with Jackson's `ObjectNode`, never string concatenation.
    - `textDelta(json)` returns `contentBlockDelta.delta.text` or `null` for events without text, and never throws.
    - _Requirements: 4.1_
  - [x] 4.2 Write the request-body property test
    - **Property 3: The Nova request body preserves the prompt exactly** — `@ParameterizedTest` over 10–20 prompts (quotes, backslashes, newlines, tabs, emoji, right-to-left text, U+0000 escapes, 8,000 characters), asserting the body parses as JSON and carries the identical prompt and token count.
    - Tag: `Feature: java-bedrock-sse-streaming-endpoint, Property 3: the Nova request body preserves the prompt exactly`.
    - **Validates: Requirements 4.1**
  - [x] 4.3 Write unit tests for `textDelta`
    - Cover `contentBlockDelta` with text, `messageStart`, `contentBlockStop`, `metadata`, a delta without `text`, empty text, malformed JSON, and a non-object root. Assert none throw.
    - _Requirements: 4.1, 4.3_
  - [x] 4.4 Checkpoint - run `./gradlew :streaming-bedrock-example-java:test -PexcludeTags=integration`. Ensure all tests pass. Ask the user if questions arise.

- [x] 5. Implement the deadline and the delta bridge
  - [x] 5.1 Implement `Deadline`
    - `fromContext(Context)` is `now + getRemainingTimeInMillis() − 3 s`; also `after(Duration)` for tests, `remainingNanos()` and `isExpired()`.
    - _Requirements: 4.6, 4.10_
  - [x] 5.2 Implement `DeltaBridge` and the exceptions
    - A `LinkedBlockingQueue<Signal>` with `Delta`, `Done` and `Failed`; `offerDelta`, `offerDone` and `offerFailure` never block and never throw.
    - `drain(deadline, sink, cancelAction)` polls with the remaining time: delta goes to the sink on the calling thread; `Done` returns; `Failed` throws `ModelStreamException`; a poll timeout runs `cancelAction` and throws `ModelStreamTimeoutException`; an interrupt restores the flag, cancels and throws. The first terminal signal wins.
    - _Requirements: 4.2, 4.3, 4.5, 4.6, 4.10_
  - [x] 5.3 Write the bridge property test
    - **Property 5: The bridge preserves order and terminal semantics** — `@ParameterizedTest` over 10–20 scenarios with real producer threads and random inter-delta delays: complete sequences, failure after k deltas (consumer sees the k-prefix then the exception), no terminal (timeout no later than the deadline plus a stated tolerance, and the cancel action ran exactly once), `Done` then `Failed` (first wins), and an interrupted consumer.
    - Also assert that `offerDelta`, `offerDone` and `offerFailure` return immediately when the queue is large and never throw.
    - Tag: `Feature: java-bedrock-sse-streaming-endpoint, Property 5: the bridge preserves order and terminal semantics`.
    - **Validates: Requirements 4.2, 4.3, 4.5, 4.6, 4.10**
  - [x] 5.4 Checkpoint - run `./gradlew :streaming-bedrock-example-java:test -PexcludeTags=integration`. Ensure all tests pass and no test is timing-flaky (run the bridge tests 20 times). Ask the user if questions arise.

- [x] 6. Implement the Bedrock async source
  - [x] 6.1 Implement `TokenStream`, `DeltaSink` and `BedrockAsyncSource`
    - Build `BedrockRuntimeAsyncClient` lazily on first use, after restore, with an explicitly configured `NettyNioAsyncHttpClient.builder().readTimeout(...)` set through `httpClientBuilder`.
    - Model id from the `MODEL_ID` environment variable, defaulting to `eu.amazon.nova-lite-v1:0`; never logged.
    - Wire `Visitor.builder().onChunk(...)`, `.subscriber(visitor)`, `.onComplete(bridge::offerDone)` and `.onError(bridge::offerFailure)` per the design, plus `future.whenComplete` for failure only. The visitor must catch everything and never write to the live stream.
    - If `onChunk` is not the visitor's callback name, the compiler will say so; correct it and note it in the Dev_Log.
    - _Requirements: 4.1, 4.2, 4.5, 4.8_
  - [x] 6.2 Test the SDK glue
    - Capture the handler passed to a Mockito mock of `BedrockRuntimeAsyncClient` and drive it with a minimal `Publisher` test double emitting chunk events, then completion; repeat for an error before any chunk and an error after some.
    - If this proves disproportionately fiddly, keep the glue thin, record the trade-off in the Dev_Log, and rely on `DeltaBridge` and `NovaPayload` tests plus the deployed test.
    - _Requirements: 4.2, 4.5, 12.1_
  - [x] 6.3 Measure the async client's footprint
    - Build the fat jar and record its size. Try excluding `software.amazon.awssdk:apache-client` (this module builds no synchronous client) and record the new size. Keep the exclusion only if every test still passes; the deployed endpoint is verified in task 13.1.
    - Record the Netty cost in the Dev_Log; the cold-start half is measured in task 13.2.
    - _Requirements: 4.8, 4.9_
  - [x] 6.4 Checkpoint - run `./gradlew :streaming-bedrock-example-java:build -PexcludeTags=integration`. Ensure all tests pass. Ask the user if questions arise.

- [x] 7. Implement the CRaC priming hook
  - [x] 7.1 Implement `Priming`
    - Registers with `Core.getGlobalContext()` in its public constructor. A package-private constructor takes `Supplier`s for the client factory, parser, SSE encoder and metadata writer.
    - `beforeCheckpoint` exercises, once each and without catching: construct and close a `BedrockRuntimeAsyncClient`; parse a primed event; encode one SSE event; serialise response metadata. It must not call the model and must not attempt the Runtime API transport.
    - _Requirements: 7.2, 7.3, 7.4, 7.5_
  - [x] 7.2 Write the priming tests
    - **Property 7: Priming exercises every path exactly once** — use counting suppliers; assert each path is invoked exactly once per `beforeCheckpoint`, that a throwing path propagates, and that no model call is made.
    - Tag: `Feature: java-bedrock-sse-streaming-endpoint, Property 7: priming exercises every path exactly once`.
    - **Validates: Requirements 7.3, 7.4, 7.8**
  - [x] 7.3 Checkpoint - run `./gradlew :streaming-bedrock-example-java:test -PexcludeTags=integration`. Ensure all tests pass. Ask the user if questions arise.

- [x] 8. Implement the handler
  - [x] 8.1 Implement `StreamTimings`
    - INFO lines `timing requestId=… stage=… tMs=…` for `start`, `firstDelta`, `streamEnd` and `closed`. No prompt, no model id.
    - _Requirements: 8.5_
  - [x] 8.2 Implement `BedrockStreamHandler`
    - Public no-arg constructor wires production collaborators; a package-private constructor injects `PromptParser`, `TokenStream`, `ResponseWriter` and `StreamingResponse`. Declare `private static final Priming PRIMING = new Priming();`.
    - A `Rejected` result is answered on the runtime-provided `OutputStream` via `ResponseWriter.writeError`, and the streaming path is never opened. A `Resolved` result calls `streaming.stream(requestId, SSE_METADATA, live -> relay(...))` using the new `ResponseBody` entry point and the two-argument `ResponseMetadata` constructor.
    - `relay` writes each delta with `SseWriter` and the terminal event on success. On any failure it logs at WARN or above, makes a best-effort write of a named SSE `error` event carrying no model or prompt detail, then rethrows. There must be no branch that writes a different status after the stream opens.
    - Record any Java-from-Kotlin friction encountered in the Dev_Log with the call site (Req 1.5).
    - _Requirements: 1.2, 1.3, 1.4, 1.5, 3.3, 3.5, 4.4, 4.5, 4.10, 4.11, 5.1, 5.2, 5.3, 5.4_
  - [x] 8.3 Write unit tests for the handler
    - Rejected requests: 400 on the buffered stream, the channel factory is never asked to open, the model source has zero interactions (the handler-level half of Property 6).
    - Failure paths with a stub `TokenStream`: fails before the first delta, fails mid-stream, times out, writes throw. In each, assert the exception propagates, the live stream carries exactly one metadata block and no second status, and the best-effort `error` event is written where the stream is still writable.
    - A missing agent (`requireAgent=true`, `isPatched()` false) fails with the documented message before anything is written.
    - _Requirements: 3.3, 3.5, 3.6, 4.5, 4.6, 4.10, 4.11, 5.1, 5.2, 5.3, 5.4, 12.1_
  - [x] 8.4 Write the relay property tests
    - **Property 1: SSE relay round-trips the model's text** — `@ParameterizedTest` over 10–20 delta sequences (empty deltas, multi-line, non-ASCII, a single huge delta, many tiny ones), reassembling with a conforming SSE parser and asserting the concatenation and the terminal event.
    - **Property 2: Bytes on the wire stay proportional to the answer** — over the same sequences, assert bytes written never exceed answer size plus a fixed per-event overhead plus the terminal event.
    - Tags: `Feature: java-bedrock-sse-streaming-endpoint, Property 1: …` and `Property 2: …`.
    - **Validates: Requirements 4.2, 4.4, 11.4, 12.2**
  - [x] 8.5 Write the priming-registration test
    - Reflectively assert `BedrockStreamHandler` has a static, non-null `PRIMING` of type `Priming`, so a defined-but-never-constructed hook cannot recur.
    - _Requirements: 7.2, 12.3_
  - [x] 8.6 Write the in-process streaming integration test
    - Real handler, real `StreamingResponse(requireAgent=false)`, real `RuntimeApiChannelFactory` pointed at a `com.sun.net.httpserver` stand-in for the Runtime API, and a stub `TokenStream` that sleeps between deltas. Record the arrival time of each chunk at the server and assert the first chunk arrives while the stub is still producing, and that the request carries the streaming headers. No container is required, so do not tag it `integration`.
    - _Requirements: 4.2, 4.3, 12.4_
  - [x] 8.7 Checkpoint - run `./gradlew :streaming-bedrock-example-java:test -PexcludeTags=integration`. Ensure all tests pass. Ask the user if questions arise.

- [x] 9. Package the function so the agent loads
  - [x] 9.1 Configure the fat jar and the zip
    - `shadowJar`: `archiveFileName = "bedrock-streaming-endpoint-java.jar"`, destination `build/dist`, `mergeServiceFiles()`, manifest `Premain-Class: nl.vintik.lambda.streaming.sse.StreamingAgent` and `Can-Retransform-Classes: true`.
    - `lambdaZip`: `archiveFileName = "bedrock-streaming-endpoint-java.zip"`, `destinationDirectory` set explicitly to `build/dist`, `from(shadowJar) { into("lib") }`.
    - Both names are distinct from every other module's artifacts.
    - _Requirements: 6.1, 6.2, 6.3_
  - [x] 9.2 Add a build-time check that the zip exists and contains the jar
    - A `verifyLambdaZip` task that fails when the zip is absent or has no `lib/bedrock-streaming-endpoint-java.jar` entry, wired into `build`.
    - Verify by running the build, then a negative run (temporarily point the zip entry elsewhere) and confirm the build fails; restore it.
    - _Requirements: 6.2, 6.7_
  - [x] 9.3 Checkpoint - run `./gradlew :streaming-bedrock-example-java:build -PexcludeTags=integration`, then `unzip -l build/dist/bedrock-streaming-endpoint-java.zip` and confirm the layout. Confirm the other examples' artifacts are untouched. Ask the user if questions arise.

- [x] 10. Coverage gate and registration
  - [x] 10.1 Configure JaCoCo with the 80% gate and the cache opt-out
    - Mirror `streaming-s3-example-java`: `jacoco` toolVersion from the catalog, `jacocoTestReport` and `jacocoTestCoverageVerification` depending on `test`, 0.80 line minimum, and `koverVerify` / `koverHtmlReport` alias tasks.
    - On `tasks.test` set `outputs.upToDateWhen { false }` and `outputs.cacheIf { false }`, with a comment explaining the Codecov 12-hour age rejection. The same opt-out is currently an uncommitted change in `streaming-s3-example-java/build.gradle.kts`; commit it together with this work.
    - _Requirements: 12.5, 12.7, 12.8_
  - [x] 10.2 Register the report in the root build and the upload workflow
    - Add `streaming-bedrock-example-java/build/reports/jacoco/test/jacocoTestReport.xml` to `validationReports` and `uploadReports` in the root `build.gradle.kts`, to the eligibility loop in `workflow-build.yml`, and to the single-line, un-spaced `files:` input there.
    - _Requirements: 12.6, 12.9_
  - [x] 10.3 Make the coverage-path check able to verify the new entry
    - `scripts/verify/checks/check_coverage_paths.sh` currently looks for `codecov/codecov-action@v5` while the workflow uses `@v7`, so it reports failure regardless. Fix the match, then prove the check works by temporarily removing the new report from `files:` and confirming the check fails; restore it.
    - _Requirements: 12.9_
  - [x] 10.4 Checkpoint - run `./gradlew verifyCoverageReports koverVerify koverHtmlReport apiCheck -PexcludeTags=integration --continue`. Ensure all six-plus-one reports validate and the coverage gate passes. Run the Java module's tests twice and confirm `test` executes both times (never `FROM-CACHE`). Ask the user if questions arise.

- [x] 11. Create the SAM stack
  - [x] 11.1 Write `deployment/aws/sam-bedrock-java/template.yaml`
    - Start from `sam-bedrock/template.yaml` and change only what the design's deployment table lists: handler, `CodeUri` (zip), `JAVA_TOOL_OPTIONS` agent path matching the jar name exactly, `MODEL_ID` from `NovaLiteProfileId`, `LogRetentionDays` Min 14 / Max 30 / Default 14, function name, usage plan name.
    - Keep `ResponseTransferMode: RESPONSE_STREAM` exactly, REGIONAL endpoint, API-key auth with a per-API usage plan, `bedrock:InvokeModelWithResponseStream` only on the inference profile and its regional foundation-model copies, memory 128–1024, timeout 1–300, no provisioned concurrency, and the access-log format with the three timing fields and `integration.requestId`. Do not point `CodeUri` at a bare jar.
    - _Requirements: 6.4, 6.6, 7.1, 7.6, 8.1, 8.2, 8.5, 9.1, 9.2, 9.3, 9.6, 10.1, 10.2_
  - [x] 11.2 Add `samconfig.toml`, `build.sh` and `deploy.sh`
    - Mirror `sam-bedrock/`: stack name `bedrock-streaming-endpoint-java`, `build.sh` runs `:streaming-bedrock-example-java:build` and lists the zip and jar, `deploy.sh` forwards extra arguments to `sam deploy` and carries the API Gateway account-level CloudWatch role prerequisite comment.
    - _Requirements: 8.6, 10.6_
  - [x] 11.3 Validate the template
    - Run `sam validate --template-file deployment/aws/sam-bedrock-java/template.yaml` and require exit code 0 before committing.
    - _Requirements: 8.4_
  - [x] 11.4 Register the template in the quality tooling
    - Add it to `SAM_TEMPLATES` and the `check_sam_templates` description in `scripts/verify-quality-signals.sh`, to the Snyk list in `scripts/verify/checks/check_config_paths.sh`, and to `iac.include` in `.snyk`.
    - _Requirements: 13.2_
  - [x] 11.5 Checkpoint - run `./scripts/verify-quality-signals.sh --offline` and compare with the baseline recorded in the PR #37 description. Confirm no failure was introduced; the seven pre-existing ones may remain unless fixed by 10.3. Ask the user if questions arise.

- [x] 12. Wire the module into CI
  - [x] 12.1 Make log retention a deploy-workflow input
    - In `workflow-deploy-aws.yml` add a `log-retention-days` input defaulting to `7` and replace the hardcoded `LogRetentionDays=7` with the input, using the file's existing `${INPUT:-default}` fallback so an empty matrix value still deploys.
    - This is a prerequisite: the new template's parameter has `MinValue: 14`, so the current hardcoded `7` would fail the deploy.
    - _Requirements: 9.6, 13.5_
  - [x] 12.2 Add the deploy and test matrix entries
    - In `cd-deploy-on-demand.yml` and `ci-feature-build.yml` add a deploy entry (`sam-dir: deployment/aws/sam-bedrock-java`, `stack-name: bedrock-streaming-endpoint-java`, `build-task: ':streaming-bedrock-example-java:build'`, `jar-name: bedrock-streaming-endpoint-java.zip`, `log-retention-days: '14'`) and a test entry using `scripts/bedrock-streaming-test.sh`.
    - Generalise the opt-in expression to `startsWith(matrix.stack-name, 'bedrock-streaming-endpoint')` so the Java leg honours `run-bedrock-test`. Confirm `ci-feature-build.yml` still passes `bedrock-test-enabled: 'false'`, so a push never invokes the paid model.
    - _Requirements: 10.4, 10.5, 13.3, 13.5, 13.7, 13.8_
  - [x] 12.3 Validate every SAM template in the build workflow
    - Add `sam validate` steps for `sam-bedrock` and `sam-bedrock-java` to `validate-sam` in `workflow-build.yml`, closing the gap where only two of the four templates were validated.
    - _Requirements: 13.2_
  - [x] 12.4 Verify the OIDC roles need no change
    - The CloudFormation execution role is already wildcard-scoped and the Kotlin Bedrock stack deploys under it. Confirm by deploying (task 13.1). Extend `github-oidc-role.yaml` only if a real permission gap appears, and then record it and soften Requirement 13.4's wording to match.
    - _Requirements: 13.3, 13.4_
  - [x] 12.5 Register the module in repository configuration
    - Add `/streaming-bedrock-example-java` to the Dependabot Gradle directories and to the expected list in `check_config_paths.sh`; while there, also add the two directories that are missing today, `/streaming-sse` and `/streaming-bedrock-example`.
    - Add the module to `.coderabbit.yaml`, the PR and issue templates (including the missing `streaming-sse`), `CONTRIBUTING.md` module and coverage tables, and `SECURITY.md`. Do not touch `release-tag.yml` path filters.
    - _Requirements: 13.9, 14.3, 15.1_
  - [x] 12.6 Checkpoint - run `actionlint` on the changed workflows if installed, `./scripts/verify-quality-signals.sh --offline`, and `./gradlew build apiCheck -PexcludeTags=integration`. Ask the user if questions arise.

- [x] 13. Deploy, measure and settle the failure path (**user-run**; needs the pipeline and a paid model call)
  - [x] 13.1 Deploy and run the post-deploy test
    - Dispatch **CD - Deploy On Demand** on the branch with `run-bedrock-test = true`. Confirm all legs deploy, the Java alias reports `-javaagent` in `JAVA_TOOL_OPTIONS` (the script prints it), and the Java leg passes the four assertions. Record client TTFB, ratio, buckets, bytes and inflation.
    - This also settles task 12.4 and task 6.3's exclusion on the real runtime.
    - _Requirements: 6.4, 7.1, 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7, 11.8, 13.3, 13.6_
  - [x] 13.2 Measure the cold-start cost of the async client
    - From CloudWatch `REPORT` lines compare init and restore durations and package size for the Java and Kotlin Bedrock functions, on cold starts. Record Netty's cost in the Dev_Log. State plainly that the two functions differ in more than the HTTP client, so the numbers describe the examples, not Netty alone.
    - _Requirements: 4.8, 4.9_
  - [x] 13.3 Measure the failure path on the existing Kotlin stack
    - Make the model call fail before the first delta (for example point `NovaLiteProfileId` at a nonexistent profile). Per Requirement 7.7, confirm the `live` alias version actually carries the change, because a config-only deploy does not publish a new version.
    - Invoke it and record: what the client receives, whether the invocation reports an error, whether `Runtime.ExitError` appears, and whether the next invocation reuses the environment. Restore the parameter afterwards and re-verify the alias.
    - Write the result to `docs/log.md` under the open entry on the unreachable 502 path, as a measurement and not an inference.
    - _Requirements: 5.4, 5.6_
  - [x] 13.4 Apply the decision rule to the Kotlin handler
    - If the failure is harmless or log-only: remove the unreachable pre-first-delta 502 branch from `BedrockStreamHandler`, update its KDoc and tests, and redeploy to confirm the Kotlin test still passes.
    - If it kills the execution environment: keep the branch, mark it unreachable with a comment citing the log entry, and record a follow-up to make the agent guard `reportInvocationError`. Do not widen this spec to patch a second runtime method.
    - Either way, the invariant of Requirement 5.6 holds for both modules afterwards.
    - _Requirements: 5.5, 5.6, 5.7_
  - [x] 13.5 Checkpoint - confirm the CD run is green for all three streaming tests, the Java and Kotlin results are recorded, and no stack from the failure experiment was left in a failing configuration. Ask the user if questions arise.

- [x] 14. Documentation and demo assets
  - [x] 14.1 Write `streaming-bedrock-example-java/README.md`
    - Cover what it does, why it needs `streaming-sse`, the packaging and `-javaagent` steps (and that a wrong path aborts JVM startup rather than degrading), a quick start that builds, deploys and resolves the URL and API key, the CloudWatch role prerequisite, the measured result from task 13.1, a teardown step, and the differences from the Kotlin port: the asynchronous client, the delta bridge, explicit `null`-free metadata construction, and the stricter parameter handling. Link the Dev_Log entry on the unreachable path.
    - _Requirements: 3.7, 5.7, 10.6, 15.2, 15.3_
  - [x] 14.2 Update the repository and library READMEs
    - Add a row to the example-modules table in the root `README.md` stating that it proves the SSE library is consumable from plain Java.
    - In `streaming-sse/README.md` present Java and Kotlin usage side by side, including `stream` and the two-argument `ResponseMetadata` constructor, and name the release that introduced them. State that packaging and the `-javaagent` flag are identical for both languages.
    - _Requirements: 2.7, 15.1, 15.4_
  - [x] 14.3 Gate video rendering on a verified recording
    - In `scripts/record-streaming-demo.sh` stamp a `streaming_verified` marker into the cast header at record time, after the alias check and the secret scan have passed. Make a `CAST_IN` render that produces an MP4 refuse a cast without the marker.
    - Test: re-render the existing committed cast (record a fresh one first if it predates the marker) and confirm an unmarked cast is refused.
    - _Requirements: 16.2, 16.3, 16.4, 16.6, 16.7_
  - [x] 14.4 Append `docs/log.md` entries
    - One entry per gotcha, bug or fix hit during this work, in the existing title / symptom / resolution form, with no secrets: the `LogRetentionDays` hardcode, any `@JvmOverloads` or SDK-callback surprises, the measured Netty cost, and the failure-path measurement.
    - _Requirements: 1.5, 4.5, 15.5, 15.6_
  - [x] 14.5 Optionally add a short paragraph to the published article
    - Mention the Java example as evidence that the mechanism is not Kotlin-specific, only with claims verifiable from the deployed Java endpoint.
    - _Requirements: 15.7_

- [x] 15. Final verification and release hygiene
  - [ ] 15.1 Run the complete gate
    - `./gradlew clean build apiCheck -PexcludeTags=integration`, `./gradlew verifyCoverageReports koverVerify koverHtmlReport -PexcludeTags=integration --continue`, `sam validate` on all four templates, and `./scripts/verify-quality-signals.sh`. Paste the quality-signals output into the PR description as the template asks.
    - _Requirements: 12.5, 12.6, 13.1, 13.2_
  - [x] 15.2 Confirm the release outcome before merging
    - Run `scripts/release/compute-version.sh` on the branch's merge result and confirm `bump=minor`. The library commits must be `feat:` and no commit may carry `!` or `BREAKING CHANGE:`. If the PR is squash-merged, the PR title must start with `feat:`, because only the title is classified.
    - Confirm the release trigger filter is unchanged and that both published modules still share one version.
    - _Requirements: 2.5, 13.9, 14.1, 14.2, 14.3, 14.4, 14.5_
  - [ ] 15.3 Checkpoint - ensure every Correctness Property test passes, the demo and README references are current, and the PR description reports what was and was not verified, including that task 13 results come from a deployed run. Ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional. Only 14.5 is; every test task here is required, because the seven Correctness Properties and the registration test are what stop the defects this work already hit once (a never-instantiated priming hook, a restored coverage report, an unreachable error path) from returning.
- Task 1 is the only task that can trigger a release. It changes published library sources, so merging it cuts a MINOR version. Everything under `streaming-bedrock-example-java/`, `deployment/` and the workflows is outside the release path filter.
- Tasks 12 and 13 touch shared infrastructure and a real AWS account. 12.1 changes a reusable workflow that every stack uses, so its default must stay `7` and the existing legs must deploy exactly as before.
- Task 13 is **user-run**: it needs the pipeline, a paid Bedrock call, and, for 13.3, a temporary misconfiguration of the existing Kotlin stack. Run 13.2 before 13.3, because 13.3 deliberately breaks the Kotlin function until restored. Deploys share one concurrency group, so runs queue rather than race.
- 13.3 is a measurement, not a fix. The decision rule for 13.4 is fixed in the design so the outcome is applied, not negotiated. Do not widen the work to patch `reportInvocationError` in the agent; if the measurement calls for it, open a follow-up spec.
- Requirement 13.4 asks for the OIDC roles to be "extended". The design expects no change to be needed. 12.4 verifies this against a real deploy and, if no gap appears, the requirement's wording should be softened to match.
- Requirement 3.6 makes this module's parameter handling stricter than the Kotlin example's. That is deliberate and documented (3.7); do not "fix" the Kotlin example to match unless asked.
- Never log prompts, model ids, API keys or tokens. In `docs/log.md`, record secrets by descriptive name.
- Tests that need no container are not tagged `integration`, so they run in the normal CI gate. Bedrock cannot be emulated, so this module has no Floci suite.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.3", "2.1", "12.1", "14.3"] },
    { "id": 1, "tasks": ["1.2", "1.4", "2.2"] },
    { "id": 2, "tasks": ["1.5", "2.3", "3.1", "4.1", "5.1", "10.1"] },
    { "id": 3, "tasks": ["3.2", "4.2", "4.3", "5.2", "10.2"] },
    { "id": 4, "tasks": ["3.3", "3.4", "3.5", "5.3", "6.1", "10.3"] },
    { "id": 5, "tasks": ["6.2", "6.3", "7.1", "8.1"] },
    { "id": 6, "tasks": ["7.2", "8.2"] },
    { "id": 7, "tasks": ["8.3", "8.4", "8.5", "8.6", "9.1"] },
    { "id": 8, "tasks": ["9.2", "11.1"] },
    { "id": 9, "tasks": ["11.2", "11.3", "11.4", "12.3"] },
    { "id": 10, "tasks": ["12.2", "12.5", "14.2"] },
    { "id": 11, "tasks": ["13.1"] },
    { "id": 12, "tasks": ["12.4", "13.2", "14.1", "14.5"] },
    { "id": 13, "tasks": ["13.3"] },
    { "id": 14, "tasks": ["13.4"] },
    { "id": 15, "tasks": ["14.4"] },
    { "id": 16, "tasks": ["15.1"] },
    { "id": 17, "tasks": ["15.2"] }
  ]
}
```
