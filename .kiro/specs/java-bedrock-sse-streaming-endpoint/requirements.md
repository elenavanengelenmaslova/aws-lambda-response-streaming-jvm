# Requirements Document

## Introduction

This feature adds a **Java** counterpart to the Kotlin `streaming-bedrock-example`, and makes the
`aws-lambda-streaming-sse` library demonstrably comfortable to consume from plain Java. It is the
same move that `streaming-s3-example-java` made for `streaming-core`: one new Gradle module whose
production sources are Java only, driving the published library from Java source, proving
cross-language interoperability on a real deployed endpoint rather than in a unit test.

The Java example relays Amazon Bedrock Nova Lite token deltas to the client as Server-Sent Events,
arriving token by token, from a Lambda on the managed `java25` runtime behind an API Gateway REST
proxy integration — no Lambda layer, no custom runtime, no wrapper script. Progressive delivery comes
from `streaming-sse`: the handler posts its invocation response to the Lambda Runtime API itself and
a Java agent stops AWS's invocation loop from posting a second one.

Two findings already establish the shape of this work, both measured rather than assumed:

- **`streaming-sse` 3.0.0 is already consumable from plain Java.** A scratch Java probe compiled and
  ran against the published module: `SseWriter`, `ResponseChannelFactory` (a `fun interface`, so a
  Java lambda satisfies it), an anonymous `ResponseChannel`, the `StreamingResponse` constructor,
  `StreamingAgent.isPatched()`, and `send(...)` called with a Java lambda. `kotlin-stdlib` reaches a
  Java consumer's compile classpath transitively through `streaming-core`, so
  `kotlin.jvm.functions.Function1` is nameable from Java. No library change is required for Java to
  work at all.
- **Two ergonomic frictions are real.** `send`'s body lambda has to `return null;` to stand in for
  Kotlin's `Unit`, and its generic return type makes the call site yield `Object` unless explicitly
  typed. Separately, `ResponseMetadata`'s third constructor parameter (`cookies`) carries a Kotlin
  default argument, which is invisible to Java, so a Java caller must pass it explicitly — the
  existing `streaming-s3-example-java` already passes `null` for it.

A third finding drives the example's design and distinguishes it from the Kotlin one: in the **AWS
SDK for Java v2, response streaming for Bedrock exists only on the asynchronous client.** The
Javadoc method list for `BedrockRuntimeClient` is `applyGuardrail, converse, countTokens,
getAsyncInvoke, invokeGuardrailChecks, invokeModel, listAsyncInvokes, listAsyncInvokesPaginator,
startAsyncInvoke` — neither `invokeModelWithResponseStream` nor `converseStream` is present, while
`BedrockRuntimeAsyncClient` has both. The Kotlin example consumes a coroutine `Flow`; the Java
example must bridge an asynchronous response handler onto the invocation thread, which is the
interesting part of the port and the part the article will describe.

Scope is deliberately parallel to the Kotlin example: one Lambda, one prompt endpoint, one new
Gradle module, one new SAM stack, no business logic beyond demonstrating progressive SSE delivery.
This document handles NO PII. It does invoke a paid foundation model, so every automated path that
calls Bedrock stays opt-in.

## Glossary

- **Streaming_Sse_Library**: The published JVM library `nl.vintik:aws-lambda-streaming-sse` (module
  `:streaming-sse`, released at 3.0.0) providing `StreamingResponse`, `SseWriter`, `StreamingState`,
  `StreamingAgent`, `ResponseChannel`, `ResponseChannelFactory`, `RuntimeApiChannelFactory` and
  `RUNTIME_API_ACCEPTED`.
- **Streaming_Core_Library**: The published JVM library `nl.vintik:aws-lambda-streaming-core` (module
  `:streaming-core`) providing `ResponseWriter`, `ResponseMetadata` (+ `fromMultiValue`), `copy(...)`,
  `METADATA_PRELUDE_CONTENT_TYPE` and `OBSERVED_MAX_PRELUDE_LEN`. `:streaming-sse` exposes it as an
  `api` dependency, so one coordinate brings in both.
- **Java_Bedrock_Module**: The new single Gradle module `streaming-bedrock-example-java`, containing
  production sources in `src/main/java` only and tests in `src/test/java`.
- **Kotlin_Bedrock_Example**: The existing module `streaming-bedrock-example`, whose behaviour the
  Java_Bedrock_Module mirrors (`PromptResolver`, `BedrockSource`, `BedrockStreamHandler`,
  `BedrockStreamingHandler`, `Priming`, `StreamTimings`, `RequestResolver`, `TokenStreamSource`).
- **Sse_Endpoint**: The HTTP `POST /stream` endpoint exposed through API Gateway that returns a
  `text/event-stream` response produced by the Java Lambda.
- **Java_Stream_Handler**: The Java component implementing
  `com.amazonaws.services.lambda.runtime.RequestStreamHandler` that reads the API Gateway proxy event
  from an `InputStream`, obtains a live stream from the Streaming_Sse_Library, and relays model
  deltas as SSE events.
- **Prompt_Parser**: The Java component that parses the API Gateway proxy event into a domain request
  object carrying the prompt text and a maximum token count, and clamps both to safe bounds.
- **Bedrock_Async_Source**: The Java component that calls
  `BedrockRuntimeAsyncClient.invokeModelWithResponseStream` and surfaces each text delta to the
  Java_Stream_Handler.
- **Delta_Bridge**: The mechanism by which Bedrock_Async_Source delivers deltas produced on an SDK
  callback thread to the invocation thread that owns the live response stream.
- **Streaming_Agent**: The Streaming_Sse_Library's Java agent (`StreamingAgent`, declared through
  `Premain-Class` on the function's own fat jar) that suppresses the runtime's duplicate response
  post. Reported by `StreamingAgent.isPatched()`.
- **Priming_Hook**: A CRaC `org.crac.Resource` written in Java whose `beforeCheckpoint` exercises the
  local critical paths so the SnapStart snapshot is warm.
- **Java_Sam_Template**: The AWS SAM `template.yaml` for this example, under
  `deployment/aws/sam-bedrock-java/`.
- **Bedrock_Post_Deploy_Test**: The opt-in post-deploy check that proves progressive token delivery
  against a deployed endpoint — today `scripts/bedrock-streaming-test.sh`, gated on
  `BEDROCK_TEST_ENABLED=true`.
- **CI_Pipeline**: The GitHub Actions workflows that build, cover, validate, deploy and test the
  examples (`workflow-build.yml`, `workflow-deploy-aws.yml`, `workflow-streaming-test.yml`,
  `ci-feature-build.yml`, `cd-deploy-on-demand.yml`).
- **OIDC_Role**: The GitHub Actions deployment role and paired CloudFormation execution role in
  `deployment/aws/oidc/github-oidc-role.yaml`.
- **Dev_Log**: The running log of hiccups, bugs, gotchas and fixes in `docs/log.md`.
- **Public_Api_Dump**: The binary-compatibility-validator output committed as
  `streaming-sse/api/streaming-sse.api` and `streaming-core/api/streaming-core.api`.
- **Release_Version**: The single SemVer version both published library modules share through the
  `releaseVersion` Gradle property, cut from a `vX.Y.Z` tag by `release-tag.yml`.

## Requirements

### Requirement 1: Prove the SSE library is consumable from plain Java

**User Story:** As a maintainer of the library, I want a Java Lambda that drives the SSE library's
public API from Java source, so that Java consumption is proven by a deployed example rather than
claimed in a README.

#### Acceptance Criteria

1. THE Java_Bedrock_Module SHALL depend on the Streaming_Sse_Library via
   `implementation(project(":streaming-sse"))` and SHALL contain production sources written in Java
   under `src/main/java` only, with no Kotlin production sources.
2. WHEN the Java_Stream_Handler opens its streamed response, THE Java_Stream_Handler SHALL obtain the
   live stream by invoking the Streaming_Sse_Library's `StreamingResponse.send` from Java, and SHALL
   NOT reimplement the Runtime API post, the chunked transfer setup or the response-mode headers in
   the Java_Bedrock_Module.
3. WHEN the Java_Stream_Handler writes an SSE event, THE Java_Stream_Handler SHALL use the
   Streaming_Sse_Library's `SseWriter` from Java, and SHALL NOT reimplement SSE framing.
4. WHEN the Java_Stream_Handler builds response metadata, THE Java_Stream_Handler SHALL use the
   Streaming_Core_Library's `ResponseMetadata` type from Java, passing the `cookies` parameter
   explicitly because Kotlin default arguments are not visible to Java.
5. WHERE the Streaming_Sse_Library or Streaming_Core_Library exposes a construct that is awkward to
   call from Java, THE Java_Bedrock_Module SHALL call it through a Java-compatible entry point and
   THE Dev_Log SHALL record the friction encountered, with the Java call site that demonstrates it.
6. THE Java_Bedrock_Module SHALL NOT depend on `kotlin-stdlib` explicitly for the purpose of naming
   `kotlin.jvm.functions.Function1`; if a direct dependency proves necessary to compile, that
   necessity SHALL be recorded in the Dev_Log and SHALL be treated as evidence for Requirement 2.

### Requirement 2: Offer Java-friendly entry points without breaking the Kotlin API

**User Story:** As a Java developer adopting the library, I want to call it without writing Kotlin
idioms by hand, so that the library feels native in a Java codebase.

#### Acceptance Criteria

1. THE Streaming_Sse_Library SHALL provide a Java-callable way to supply the response body that does
   not require the caller to name a Kotlin function type and does not require returning a value to
   stand in for `Unit`.
2. THE Streaming_Core_Library SHALL provide a Java-callable way to construct `ResponseMetadata` with
   only a status code and headers, without the caller passing a value for the `cookies` parameter.
3. WHEN a Java-friendly entry point is added, THE existing Kotlin call signatures SHALL remain source
   compatible and binary compatible, such that no existing Kotlin or Java consumer has to change.
4. WHEN a Java-friendly entry point is added, THE Public_Api_Dump SHALL be regenerated and committed
   in the same change, and `apiCheck` SHALL pass.
5. IF the added entry points are purely additive, THEN THE change SHALL be released as a MINOR
   Release_Version increment, and THE commit SHALL NOT be marked as a breaking change.
6. THE Java-friendly entry points SHALL be exercised by at least one test written in Java, so a
   future refactor that breaks Java callers fails the build rather than the next Java consumer.
7. THE `streaming-sse` README SHALL show a Java usage example alongside the Kotlin one, including the
   packaging and `-javaagent` steps, which are identical for both languages.
8. IF a Java-friendly entry point cannot be added without a source-incompatible change to an existing
   Kotlin call signature, THEN THE entry point SHALL be redesigned to preserve Kotlin source
   compatibility, and the Java API addition SHALL be deferred until such a design exists, rather than
   being released as a breaking change.

### Requirement 3: Accept and validate the prompt request

**User Story:** As a client, I want to post a prompt and receive streamed tokens, so that I can
render an answer as it is produced.

#### Acceptance Criteria

1. WHEN a POST request arrives at the Sse_Endpoint, THE Prompt_Parser SHALL parse the API Gateway
   proxy event from the input stream into a domain request object containing the prompt text and a
   maximum token count.
2. IF the request body is absent, empty, or contains no prompt, THEN THE Prompt_Parser SHALL
   substitute a default prompt so the endpoint remains demonstrable with an empty body.
3. IF the prompt exceeds the configured maximum prompt length, THEN THE Java_Stream_Handler SHALL
   answer with HTTP status code 400 and an error message indicating the prompt is too long, and SHALL
   NOT invoke the model.
4. IF the requested maximum token count is absent, non-numeric, below 1, or above the configured
   ceiling, THEN THE Prompt_Parser SHALL clamp it to the configured bounds rather than rejecting the
   request.
5. IF the API Gateway proxy event cannot be parsed, THEN THE Java_Stream_Handler SHALL answer with
   HTTP status code 400 and an error message indicating the request could not be parsed, and SHALL
   NOT invoke the model.
6. IF the request body carries a parameter other than the supported `prompt` and `maxTokens`, THEN
   THE Java_Stream_Handler SHALL reject the entire request with HTTP status code 400 and an error
   message naming the unsupported parameter, SHALL NOT invoke the model, and SHALL NOT process the
   remaining parameters.
7. THE Java example's README SHALL state that its parameter handling is stricter than the
   Kotlin_Bedrock_Example's, which ignores unsupported parameters.

### Requirement 4: Stream model deltas from the asynchronous Java SDK

**User Story:** As a Java developer, I want the example to use the SDK path that actually supports
response streaming, so that it is a correct demonstration rather than a Kotlin translation.

#### Acceptance Criteria

1. THE Bedrock_Async_Source SHALL invoke the model through
   `BedrockRuntimeAsyncClient.invokeModelWithResponseStream`, because the synchronous
   `BedrockRuntimeClient` exposes no response-streaming operation.
2. WHEN a text delta arrives from the model, THE Delta_Bridge SHALL deliver it to the thread that
   owns the live response stream, and THE Java_Stream_Handler SHALL write and flush it as one SSE
   event before the invocation returns.
3. THE Delta_Bridge SHALL NOT accumulate the complete answer before the first event is written, such
   that the bytes written for a delta leave the execution environment while the model is still
   generating.
4. WHEN the model stream completes, THE Java_Stream_Handler SHALL write the terminal SSE event and
   complete the streamed response, and THE invocation SHALL NOT return before the response is
   complete.
5. IF the model stream fails, THEN THE failure SHALL be surfaced on the invocation thread rather than
   swallowed on an SDK callback thread, and THE Dev_Log SHALL record how the asynchronous failure is
   propagated.
6. IF the model stream neither completes nor fails within the Lambda timeout, THEN THE invocation
   SHALL fail rather than return a response that the client would see as silently truncated.
7. THE Java_Bedrock_Module SHALL declare the Bedrock Runtime dependency through the AWS SDK for Java
   BOM already in `gradle/libs.versions.toml`, adding a versionless catalog entry for
   `software.amazon.awssdk:bedrockruntime` alongside the existing `aws-sdk-java-s3` entry.
8. THE Bedrock_Async_Source SHALL use the Netty NIO async HTTP client
   (`software.amazon.awssdk:netty-nio-client`), declared as an explicit dependency through a
   versionless entry managed by the AWS SDK for Java BOM and set explicitly on the client builder,
   rather than inheriting whichever implementation happens to be on the classpath.
9. THE Dev_Log SHALL record the cold-start and package-size cost of the Netty NIO client, measured
   under SnapStart.
10. THE Java_Stream_Handler SHALL bound its wait for the model stream by a deadline derived from the
    invocation's remaining time less a safety margin, and WHEN that deadline is reached before the
    stream completes or fails, THE Java_Stream_Handler SHALL raise an explicit timeout error on the
    invocation thread before the Lambda runtime terminates the function.
11. WHEN the deadline in criterion 10 is reached, THE Java_Stream_Handler SHALL, on a best-effort
    basis, write a named SSE `error` event to the live stream before completing it, so a client can
    tell a timed-out stream from one that ended with the terminal `[DONE]` event.

### Requirement 5: Make the status-ordering contract explicit and correct

**User Story:** As a developer reading the example, I want to know exactly when the HTTP status is
committed, so that I do not write error handling that can never run.

#### Acceptance Criteria

1. WHEN a request fails validation before the streamed response is opened, THE Java_Stream_Handler
   SHALL answer on the `OutputStream` the runtime provided, with no body bytes written to a live
   stream.
2. THE Java_Stream_Handler SHALL treat the HTTP status as committed from the moment
   `StreamingResponse.send` writes the metadata and the 8-byte delimiter, which happens **before**
   the body callback runs and therefore before the model is invoked.
3. THE Java_Bedrock_Module SHALL NOT contain an error path that attempts to return a different HTTP
   status to the client after the streamed response has been opened, because such a path cannot
   reach the client.
4. IF the model fails at any point after the streamed response has been opened, THEN THE client SHALL
   receive the already-committed status with a truncated body, and THE invocation SHALL be reported as
   failed.
5. THE documented ordering guarantees in the Java_Bedrock_Module's sources and README SHALL match the
   behaviour of the code, and SHALL NOT repeat the claim that the status is committed when the first
   delta arrives.
6. THE Java_Bedrock_Module and the Kotlin_Bedrock_Example SHALL each contain no error path that
   attempts to change the HTTP status after the streamed response has been opened, and this SHALL
   hold regardless of whether such a path currently exists; any such path found in either module
   SHALL be removed, or marked unreachable with a comment that cites its Dev_Log entry.
7. THE Java example's README SHALL reference the Dev_Log entry that records the unreachable-path
   finding, so a reader porting the Kotlin handler does not reproduce it.

### Requirement 6: Package the function so the agent loads

**User Story:** As an operator deploying the Java example, I want the packaging to be the documented
one, so that the agent loads and the endpoint actually streams.

#### Acceptance Criteria

1. THE Java_Bedrock_Module's build SHALL produce a fat jar whose manifest declares
   `Premain-Class: nl.vintik.lambda.streaming.sse.StreamingAgent`.
2. THE Java_Bedrock_Module's build SHALL produce a **zip** deployment package containing that fat jar
   under `lib/`, and SHALL set the zip task's destination directory explicitly so the archive is
   written to the path the Java_Sam_Template's `CodeUri` names.
3. THE fat jar and zip SHALL be named distinctly from every other module's artifacts, so all examples
   can be built in one repository without overwriting each other.
4. THE Java_Sam_Template SHALL set `JAVA_TOOL_OPTIONS` to include `-javaagent:` pointing at the jar's
   path inside `/var/task/lib/`, matching the archive's jar name exactly.
5. IF the `-javaagent` path does not resolve to a jar carrying a `Premain-Class`, THEN the JVM aborts
   startup and every invocation fails to initialise; THE Java example's README SHALL state this
   failure mode rather than describing a graceful fallback.
6. THE Java_Sam_Template SHALL NOT point `CodeUri` at a bare jar, because Lambda extracts a bare-jar
   package loose into `/var/task` and leaves no jar file for `-javaagent` to name.
7. IF the build does not produce the zip deployment package, or the produced zip does not contain the
   fat jar under `lib/`, THEN THE build SHALL fail.

### Requirement 7: Apply the project's cold-start optimizations

**User Story:** As an operator, I want SnapStart, priming and tiered compilation configured, so the
Java example matches the baseline the other examples set.

#### Acceptance Criteria

1. THE Java_Sam_Template SHALL configure an auto-published alias named `live` and SnapStart with
   apply-on set to published versions.
2. THE Priming_Hook SHALL be **instantiated** by the Lambda entry-point class's static
   initialization, so that it registers itself with the global CRaC context and `beforeCheckpoint`
   actually runs; a Priming_Hook that is defined but never constructed SHALL be treated as a defect.
3. WHILE the SnapStart snapshot is being created, THE Priming_Hook SHALL exercise, in one pass, the
   Bedrock client construction, the request-parsing path, the SSE event encoding and the response
   metadata serialization, WITHOUT invoking the model and therefore without incurring model cost.
4. IF any critical path exercised by the Priming_Hook raises an error during the before-checkpoint
   phase, THEN THE error SHALL propagate so that snapshot creation fails and no version is published.
5. THE Priming_Hook SHALL NOT attempt to prime the streamed Runtime API transport, because opening a
   response requires a real invocation id, which does not exist during the snapshot phase.
6. THE Java_Sam_Template SHALL inherit the `arm64` architecture and the `java25` runtime from the
   template's global configuration without per-function overrides, and SHALL include
   `-XX:+TieredCompilation -XX:TieredStopAtLevel=1` in `JAVA_TOOL_OPTIONS`.
7. WHEN a deploy changes only configuration and not code, THE operator SHALL confirm which version
   the `live` alias points at, because an auto-published alias advances only on a code change.
8. IF the Priming_Hook exercises any critical path in more than one pass during a single
   `beforeCheckpoint`, THEN THE build SHALL fail, enforced by a test that counts how many times each
   path is exercised, rather than being accepted because every path was eventually covered.

### Requirement 8: Configure API Gateway for response streaming

**User Story:** As an operator, I want the gateway configured so nothing buffers the response.

#### Acceptance Criteria

1. THE Java_Sam_Template SHALL define a REST API with a proxy integration routing `POST /stream` to
   the Java Lambda, with a REGIONAL endpoint configuration.
2. THE Java_Sam_Template SHALL set the `ResponseTransferMode` property on the API event to the exact
   value `RESPONSE_STREAM`.
3. IF `ResponseTransferMode` is set to any other value, including the lower-level integration value
   `STREAM`, THEN THE template SHALL be treated as invalid for response streaming and SHALL NOT be
   committed, because any other value deploys a buffered integration without raising an error.
4. WHEN the Java_Sam_Template changes, THE developer SHALL run `sam validate` against it and confirm
   exit code 0 before committing.
5. THE Java_Sam_Template SHALL carry an access log whose format includes the response-streaming
   timing fields (`$context.integration.timeToAllHeaders`,
   `$context.integration.timeToFirstContent`, `$context.integration.latency`) and
   `$context.integration.requestId`, so server-side timings can be joined to the handler's own
   timing lines.
6. THE Java example's deployment instructions SHALL state the account-level prerequisite for that
   access log: the API Gateway CloudWatch role must be configured once per account and Region.

### Requirement 9: Secure the endpoint and scope its permissions

**User Story:** As a security reviewer, I want the paid model endpoint protected and its permissions
minimal.

#### Acceptance Criteria

1. THE Java_Sam_Template SHALL require an API key on the Sse_Endpoint and SHALL create a usage plan
   scoped to this API.
2. THE Java_Sam_Template SHALL grant the Lambda execution role the
   `bedrock:InvokeModelWithResponseStream` action only, scoped to the specific inference profile and
   the regional foundation-model copies it routes to, and SHALL NOT use a wildcard resource.
3. THE Java_Sam_Template SHALL NOT grant any other Bedrock action, and SHALL NOT grant
   `bedrock:InvokeModel`.
4. WHEN a client requests the Sse_Endpoint over HTTPS, THE Sse_Endpoint SHALL serve the response over
   that connection; IF a client requests it over plain HTTP, THEN the request SHALL be rejected
   without invoking the model.
5. THE resolved API key SHALL be masked in CI logs and SHALL NOT be printed by any script.
6. THE Java_Sam_Template SHALL configure the Lambda and access log groups with a finite retention
   period between 14 and 30 days inclusive, which are the CloudWatch Logs retention values of 14 and
   30 days.

### Requirement 10: Control the cost of a paid-model example

**User Story:** As an operator, I want a model-invoking example that cannot run up a bill.

#### Acceptance Criteria

1. THE Java_Sam_Template SHALL set the Lambda memory size to a bounded value between 128 MB and
   1024 MB inclusive, and the timeout to between 1 and 300 seconds inclusive.
2. THE Java_Sam_Template SHALL NOT configure provisioned concurrency or any other always-on capacity.
3. THE maximum token count that the Prompt_Parser passes to the model SHALL NOT exceed the
   configured token ceiling, so a single request cannot ask for an unbounded generation.
4. EVERY automated path that invokes the model SHALL be opt-in behind an explicit flag, and SHALL
   exit successfully without invoking the model when that flag is absent.
5. THE CI_Pipeline SHALL NOT invoke the model on a routine build or on a push-triggered deploy;
   invoking it SHALL require an explicit request on the triggering workflow.
6. THE Java example's documentation SHALL provide a teardown step that removes the deployed stack.

### Requirement 11: Prove progressive delivery against the deployed Java endpoint

**User Story:** As an author of the guide, I want the Java endpoint measured the same way as the
Kotlin one, so the two are directly comparable and neither can regress silently.

#### Acceptance Criteria

1. THE Bedrock_Post_Deploy_Test SHALL be reusable against the Java stack without duplicating the
   script, by resolving the stack name, endpoint URL and API key from configuration rather than
   hard-coding one stack.
2. WHEN run against the deployed Java endpoint, THE Bedrock_Post_Deploy_Test SHALL assert that the
   client-observed time to first byte, measured as curl's `%{time_starttransfer}`, is below a
   configured fraction of `%{time_total}`.
3. WHEN run against the deployed Java endpoint, THE Bedrock_Post_Deploy_Test SHALL assert that token
   arrivals fall into at least a configured minimum number of distinct time windows, so a fast
   download cannot be mistaken for progressive production.
4. WHEN run against the deployed Java endpoint, THE Bedrock_Post_Deploy_Test SHALL assert that the
   bytes received stay within a configured multiple of the answer's own size, so no padding-style
   inflation can be reintroduced.
5. WHEN run against the deployed Java endpoint, THE Bedrock_Post_Deploy_Test SHALL assert that the
   terminal SSE event is received, so a truncated stream is not reported as success.
6. THE Bedrock_Post_Deploy_Test SHALL report whether the deployed alias carries `-javaagent`, so a
   buffered result is diagnosed rather than merely failed.
7. THE Bedrock_Post_Deploy_Test SHALL force HTTP/1.1 and disable client-side buffering, so neither
   HTTP/2 stream termination nor curl's own buffering produces a misleading result.
8. IF any assertion fails or the endpoint is unreachable, THEN THE Bedrock_Post_Deploy_Test SHALL exit
   non-zero and SHALL NOT report success.

### Requirement 12: Test the Java example in the project's layers

**User Story:** As a maintainer, I want the Java example tested like the others, so a regression fails
the build rather than the deploy.

#### Acceptance Criteria

1. THE Java_Bedrock_Module SHALL provide unit tests using JUnit Jupiter covering prompt parsing and
   clamping, SSE relay of a stubbed delta sequence, the pre-commit validation error path, and the
   registration of the Priming_Hook.
2. THE Java_Bedrock_Module SHALL provide property-based tests via `@ParameterizedTest` over a diverse
   set of inputs, asserting universal properties: that a conforming SSE client reassembles exactly the
   text the model produced, and that the bytes written stay proportional to the answer's size.
3. THE Java_Bedrock_Module SHALL assert that the Priming_Hook is actually registered by the
   entry-point class's initialization, so the defect of a never-instantiated hook cannot recur.
4. THE Java_Bedrock_Module SHALL NOT require a running container runtime for its unit and property
   tests, and any test that does SHALL be tagged so it can be excluded.
5. THE Java_Bedrock_Module SHALL enforce a coverage gate consistent with the other example modules
   and SHALL fail the build when the threshold is not met.
6. THE Java_Bedrock_Module's coverage report SHALL be registered in the repository's coverage report
   validation and in the Codecov upload list, both of which are driven from the same declaration in
   the root build.
7. WHERE a coverage report is produced by a task whose output can be restored from the Gradle build
   cache, THE task SHALL be configured so the report is produced in the run that uploads it, because
   a restored report carries stale session timestamps and is rejected by Codecov.
8. WHEN a coverage report cannot be restored from the Gradle build cache, THE build SHALL regenerate
   the report and complete successfully, and SHALL NOT treat the absence of a cache hit as a
   failure.
9. IF the Java_Bedrock_Module's coverage report is not registered in the repository's coverage report
   validation and in the Codecov upload list, THEN THE build SHALL fail or flag the coverage as
   incomplete, regardless of whether the report was freshly produced or restored from the build
   cache.

### Requirement 13: Build, validate and deploy the Java example in CI

**User Story:** As a maintainer, I want the Java Bedrock example wired into the pipeline on the same
terms as the other examples, so nothing about it is special-cased or silently skipped.

#### Acceptance Criteria

1. THE CI_Pipeline build stage SHALL compile the Java_Bedrock_Module, run its tests with
   container-tagged tests excluded, and run its coverage verification.
2. THE CI_Pipeline build stage SHALL validate the Java_Sam_Template with `sam validate` and SHALL
   fail on a non-zero exit code; THE existing gap whereby only some templates are validated SHALL be
   closed so every template under `deployment/aws/` is validated.
3. THE CI_Pipeline deploy stage SHALL deploy the Java stack using short-lived credentials obtained by
   assuming the OIDC_Role, and SHALL NOT use long-lived access keys.
4. THE OIDC_Role and its paired CloudFormation execution role SHALL be extended to authorize the Java
   stack's resources, scoped to its stack name, its function's log group and its Bedrock inference
   profile, without granting broader access than the existing pattern.
5. THE Java stack's deploy SHALL be expressed as another entry in the existing deploy matrix rather
   than as a separate bespoke job.
6. WHEN the Java stack deploy completes, THE deploy stage SHALL expose the endpoint URL, function name
   and API key id as stack outputs so the test stage can resolve them.
7. THE Java example's post-deploy test SHALL run as its own matrix entry, judged on its own result, so
   an unrelated stack's failure neither skips it nor fails it.
8. WHERE concurrent runs could update the same shared stacks, THE deploy workflows SHALL serialise
   through the existing concurrency group.
9. THE Java_Bedrock_Module's source paths SHALL NOT be added to the release trigger's path filter,
   because example modules are not published and a release must not be cut for an example-only
   change.

### Requirement 14: Keep the published-library release model intact

**User Story:** As a maintainer, I want the library change that comes with this work released
correctly, so consumers get a coherent version.

#### Acceptance Criteria

1. WHERE this work changes `:streaming-sse` or `:streaming-core` production sources, THOSE paths are
   already in the release trigger's filter and the merge SHALL cut a Release_Version.
2. BOTH published modules SHALL continue to share one Release_Version; this work SHALL NOT introduce
   per-module versioning.
3. WHEN a new published module is ever added, IT SHALL be added to both the release trigger's path
   filter and the publish workflow's explicit task list; this work adds no published module, so
   neither list changes.
4. IF the library changes in this work are additive only, THEN THE Release_Version increment SHALL be
   MINOR, and the merge SHALL NOT be marked breaking.
5. THE README dependency coordinate SHALL continue to be updated by the automated version-bump pull
   request rather than by hand.

### Requirement 15: Document the Java example and the port

**User Story:** As a reader, I want the Java example discoverable and its differences from the Kotlin
one explained, so I can follow it in my own codebase.

#### Acceptance Criteria

1. THE repository README's example-modules table SHALL gain a row for the Java Bedrock example,
   stating that it proves the SSE library is consumable from plain Java.
2. THE Java_Bedrock_Module SHALL carry a README covering what it does, why it needs the SSE library,
   the packaging and agent steps, a quick start that builds, deploys and resolves the endpoint URL
   and API key, and its measured result.
3. THE Java example's README SHALL state where the Java port differs from the Kotlin one and why:
   the asynchronous Bedrock client, the Delta_Bridge, and the Java-side handling of Kotlin default
   arguments.
4. THE `streaming-sse` README SHALL present Java and Kotlin usage side by side rather than describing
   Java support in prose only.
5. WHEN a hiccup, bug, gotcha, fix or interop friction is encountered during this work, THE developer
   SHALL append an entry to the Dev_Log in the existing title / symptom / resolution form, within the
   same session.
6. THE Dev_Log SHALL NOT contain credentials, tokens, resource identifiers used as secrets, or
   authorization header values; such a detail SHALL be recorded by descriptive name instead.
7. WHERE the published article describes the Kotlin example, IT MAY reference the Java example as
   evidence that the mechanism is not Kotlin-specific; any such claim SHALL be verifiable from the
   deployed Java endpoint rather than asserted.

### Requirement 16: Keep the demo assets accurate

**User Story:** As a reader, I want the demo in the READMEs to show the current implementation.

#### Acceptance Criteria

1. THE demo asset referenced by the repository README, the Java and Kotlin example READMEs and the
   article SHALL be produced from a recording of a currently deployed endpoint.
2. WHERE a recording is kept as an asciicast alongside the rendered asset, A re-render SHALL be
   possible from that recording without invoking the paid model again.
3. THE recording script SHALL verify, before recording, that the deployed alias carries the streaming
   agent, so a buffered endpoint is never recorded as if it streamed.
4. THE recording script SHALL scan the recording for the API key and the endpoint host before
   rendering, and SHALL abort rather than render if either appears.
5. WHERE a published venue cannot embed an animated image, A video rendering of the same recording
   SHALL be available.
6. THE video rendering SHALL be produced from the same recording that passed the agent verification
   and the secret scan, and SHALL NOT require a separate verification or scan of its own.
7. IF the verification that the deployed alias carries the streaming agent has not passed, THEN THE
   recording script SHALL NOT produce a video rendering, and publication of a video SHALL wait until
   that verification has passed on a re-run.

## Open questions

These need a decision before or during design, and are deliberately not answered here:

1. **The shape of the Java-friendly body parameter** (Requirement 2.1): an overload taking
   `java.util.function.Consumer<OutputStream>`, a dedicated functional interface in the module, or a
   `void`-returning `sendTo` variant. Each differs in how it reads from Kotlin and in what it adds to
   the `.api` dump.
2. **Where the `ResponseMetadata` Java convenience lives** (Requirement 2.2): `@JvmOverloads` on the
   existing Kotlin constructor, a static factory, or leaving it and documenting the explicit `null`.
   The first changes `streaming-core`'s public API surface.
3. **The Delta_Bridge mechanism** (Requirement 4.2): a blocking queue drained on the invocation
   thread, a `CompletableFuture` with a subscriber that writes directly, or the SDK's visitor with
   the invocation thread awaiting completion. This is the main design decision of the port.
4. **Whether the Kotlin example's unreachable 502 path is removed or only marked** (Requirement
   5.6). The requirement is now an invariant that holds either way; the choice between deleting the
   path and marking it is a design decision, because removing it changes when the prelude is written
   for both examples.
5. **Whether a shared `sam-bedrock` template parameterised by handler can serve both examples**, or
   whether a separate `sam-bedrock-java` stack is clearer. The S3 examples use separate stacks.
