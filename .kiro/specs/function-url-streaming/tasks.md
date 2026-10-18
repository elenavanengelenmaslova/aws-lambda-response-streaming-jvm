# Implementation Plan: Function URL Streaming

## Overview

This plan adds a Lambda Function URL streaming path alongside the existing API Gateway endpoint, in both example modules, and proves it end to end in the pipeline. Work proceeds: Kotlin resolver + handler (cheap, the generic `StreamHandler<R>` already exists) → Java resolver abstraction + refactor + handler (the larger lift) → SAM `FunctionUrlConfig` with `AuthType: AWS_IAM` and `InvokeMode: RESPONSE_STREAM` for both templates → OIDC role `lambda:InvokeFunctionUrl` grant → post-deploy script SigV4 signing + pipeline wiring → README + `docs/log.md`. Each code task is immediately followed by its unit/property tests; integration tests follow their handler; every top-level task ends with a `./gradlew clean test` checkpoint; SAM tasks end with a `sam validate` gate.

Languages: **Kotlin 2.3.x / Java 25** (per the design and `tech.md`). Kotlin package `nl.vintik.lambda.streaming`; Java package `nl.vintik.streaming.java`. Test packages mirror main.

The three Correctness Properties from the design each become a single `@ParameterizedTest` property-based test, tagged with the exact tag string from the design's Testing Strategy.

## Tasks

- [x] 1. Kotlin example: Function URL resolver and handler
  - [x] 1.1 Implement `FunctionUrlResolver`
    - Add `FunctionUrlResolver : RequestResolver<FileRequest>` in `streaming-s3-example/src/main/kotlin/nl/vintik/lambda/streaming/`.
    - Decode a lenient payload-2.0 projection (`rawPath`, `requestContext.http.path`) with `LenientJson`; derive the file name as `path.trimStart('/').substringAfterLast('/')` (empty when absent).
    - Validate with the shared `FileNameValidator`; map decode failure → `RequestResult.Error(400, "The request could not be parsed.")`, validation rejection → `RequestResult.Error(400, <reason message>)` (no S3 call), success → `RequestResult.Resolved(FileRequest(name))`. Reuse the exact rejection-message wording from `FileKeyResolver`.
    - _Requirements: 2.1, 2.2, 2.3, 2.4_
  - [x] 1.2 Implement `S3FunctionUrlHandler` entry point
    - Add `S3FunctionUrlHandler : RequestStreamHandler` wiring `StreamHandler(requestResolver = ::FunctionUrlResolver, source = ::S3Source)`. No logic of its own. Do not modify `StreamHandler`, `S3Source`, `FileNameValidator`, `ResponseWriter`, or `FileRequest`.
    - _Requirements: 1.1, 1.2, 1.3, 2.5_
  - [x] 1.3 Write unit tests for `FunctionUrlResolver`
    - Cover: empty input → 400; malformed JSON → 400; well-formed payload-2.0 event with a rejected name (`..`, `/`, illegal char, over-length) → 400; well-formed event with an accepted name → `Resolved(FileRequest(name))`. Use a representative payload-2.0 event including `rawPath` and `requestContext.http`. Assert on typed results, not whole-response string equality.
    - _Requirements: 4.1, 4.2, 4.3_
  - [x] 1.4 Write property test — Function URL path extraction parity
    - **Property 1: Function URL path extraction yields the same file name as the proxy path** — `@ParameterizedTest` over 10–20 valid names; assert `FunctionUrlResolver` on `rawPath="/"+n` resolves to the same `FileRequest(n)` as `FileKeyResolver` on `pathParameters.proxy=n`.
    - Tag: `Feature: function-url-streaming, Property 1: Function URL path extraction matches proxy path resolution`.
    - **Validates: Requirements 2.1, 2.3, 4.1**
  - [x] 1.5 Write property test — Function URL resolver rejection and no-S3 guarantee
    - **Property 2: Function URL resolver rejects exactly the unsafe set and issues no S3 request** — `@ParameterizedTest` over 10–20 mixed valid/adversarial/malformed inputs; assert the 400-iff-rejected biconditional against the shared validator and verify a relaxed `S3Source` mock receives zero interactions on every rejection.
    - Tag: `Feature: function-url-streaming, Property 2: Function URL resolver rejects unsafe names and issues no S3 request`.
    - **Validates: Requirements 2.2, 2.4, 4.1, 4.3**
  - [x] 1.6 Checkpoint - run `./gradlew :streaming-s3-example:clean :streaming-s3-example:test`. Ensure all tests pass, ask the user if questions arise.

- [x] 2. Kotlin example: Function URL handler integration tests (Floci)
  - [x] 2.1 Add sub-6 MB Function URL integration test
    - New Floci test extending `FlociS3IntegrationTestBase`; invoke `S3FunctionUrlHandler` with an `AWS_IAM`-shaped payload-2.0 event (include `requestContext.authorizer.iam`) for a sub-6 MB object; assert the received body is byte-identical to the stored object. One container per class; clean only data between tests.
    - _Requirements: 5.1, 5.3, 5.4_
  - [x] 2.2 Add ≥15 MB Function URL integration test
    - Same base/pattern for an at-least-15 MB object; assert full byte-identical delivery.
    - **Property 3: Streaming output over the Function URL path is byte-identical to the source** — cases across sub-6 MB and ≥15 MB.
    - Tag: `Feature: function-url-streaming, Property 3: Function URL streamed body is byte-identical to the source`.
    - **Validates: Requirements 5.1, 5.2, 5.3**
  - [x] 2.3 Checkpoint - run `./gradlew :streaming-s3-example:clean :streaming-s3-example:test`. Ensure all tests pass, ask the user if questions arise.

- [x] 3. Java example: introduce the request-resolution abstraction
  - [x] 3.1 Implement `RequestResolver` and `RequestResult`
    - Add `@FunctionalInterface RequestResolver<R>` and sealed `RequestResult<R>` (records `Resolved<R>(R request)` and `Error<R>(int statusCode, String message)`) in `streaming-s3-example-java/src/main/java/nl/vintik/streaming/java/`.
    - _Requirements: 3.1_
  - [x] 3.2 Implement `FileKeyResolver` (API Gateway proxy)
    - Wrap the existing `RequestParser` + `FileNameValidator`; produce `FileRequest`; map parse error → `Error(400, …)`, validation rejection → `Error(400, messageFor(reason))`, success → `Resolved(FileRequest(name))`, preserving the existing handler's exact messages.
    - _Requirements: 3.1, 3.2_
  - [x] 3.3 Refactor `StreamHandler.java` onto `FileKeyResolver`
    - Replace the inline `parser.parse` + `validator.validate` steps with a `RequestResolver<FileRequest>` (default `FileKeyResolver`), keeping existing externally observable behavior. Preserve the status-committed-early ordering and all current messages/log lines.
    - _Requirements: 3.2, 3.3_
  - [x] 3.4 Write unit tests for `RequestResolver` abstraction and `FileKeyResolver`
    - Cover parse failure → 400, validation rejection → 400 (no S3 call), valid name → `Resolved`.
    - _Requirements: 3.4_
  - [x] 3.5 Verify existing Java handler behavior is unchanged
    - Run the existing `StreamHandlerTest` and the Java example suite; confirm all pass unchanged after the refactor.
    - _Requirements: 3.3, 4.4_
  - [x] 3.6 Checkpoint - run `./gradlew :streaming-s3-example-java:clean :streaming-s3-example-java:test`. Ensure all tests pass, ask the user if questions arise.

- [x] 4. Java example: Function URL resolver and handler
  - [x] 4.1 Implement Java `FunctionUrlResolver`
    - Parse the payload-2.0 `rawPath` (fallback `requestContext.http.path`) with the lenient Jackson `ObjectMapper` used by `RequestParser`; derive the file name (strip leading `/`, last segment); validate with the shared `FileNameValidator`; map decode/validation outcomes to `RequestResult` exactly as the Kotlin resolver does.
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 3.2_
  - [x] 4.2 Implement Java `S3FunctionUrlHandler` entry point
    - `RequestStreamHandler` wired with `FunctionUrlResolver`, driving the existing `S3Source` + library `ResponseWriter`; register `Priming`.
    - _Requirements: 1.1, 1.2, 1.3, 2.5, 8.2_
  - [x] 4.3 Write unit tests for Java `FunctionUrlResolver`
    - Same four cases as Kotlin 1.3 (empty → 400, malformed → 400, rejected name → 400, accepted → Resolved), payload-2.0 event shape, typed-result assertions.
    - _Requirements: 4.1, 4.2, 4.3_
  - [x] 4.4 Add Java Function URL integration tests (Floci)
    - Sub-6 MB and ≥15 MB tests extending the Java `FlociS3IntegrationTestBase`, invoking `S3FunctionUrlHandler` with an `AWS_IAM`-shaped payload-2.0 event; assert byte-identical delivery. One container per class.
    - _Requirements: 5.1, 5.2, 5.3, 5.4_
  - [x] 4.5 Checkpoint - run `./gradlew :streaming-s3-example-java:clean :streaming-s3-example-java:test`. Ensure all tests pass, ask the user if questions arise.

- [x] 5. SAM templates: Function URL Lambda for both examples
  - [x] 5.1 Add `FunctionUrlFunction` to the Kotlin template
    - In `deployment/aws/sam/template.yaml` add a second `AWS::Serverless::Function` (`Handler: nl.vintik.lambda.streaming.S3FunctionUrlHandler`, same `CodeUri`, `MemorySize`/`Timeout` refs, `AutoPublishAlias: live`, SnapStart `PublishedVersions`, `JAVA_TOOL_OPTIONS` L1, `BUCKET_NAME`, scoped `s3:GetObject` policy, its own `LogGroup` at the same retention). No API `Events`. Add `FunctionUrlConfig: { AuthType: AWS_IAM, InvokeMode: RESPONSE_STREAM }`. Add `FunctionUrl` and `FunctionUrlFunctionName` outputs.
    - _Requirements: 1.1, 1.2, 6.1, 6.2, 6.4, 7.1, 8.1, 8.2, 8.3, 8.4, 10.1, 10.3, 10.4, 10.5, 10.6_
  - [x] 5.2 Add `FunctionUrlFunction` to the Java template
    - Same additions in `deployment/aws/sam-java/template.yaml` with `Handler: nl.vintik.streaming.java.S3FunctionUrlHandler` and the Java `CodeUri`/function-name prefix.
    - _Requirements: 1.1, 1.2, 6.1, 6.2, 6.4, 7.1, 8.1, 8.2, 8.3, 8.4, 10.1, 10.3, 10.4, 10.5, 10.6_
  - [x] 5.3 Grant `lambda:InvokeFunctionUrl` to the OIDC deploy role
    - In `deployment/aws/oidc/github-oidc-role.yaml` add an `Allow lambda:InvokeFunctionUrl` statement scoped to the specific Function URL function ARNs (Kotlin and Java), with the `lambda:FunctionUrlAuthType = AWS_IAM` condition; no wildcard resource.
    - _Requirements: 7.3_
  - [x] 5.4 Validate both templates
    - Run `sam validate --template-file deployment/aws/sam/template.yaml` and `sam validate --template-file deployment/aws/sam-java/template.yaml`; require exit code 0 for each before committing. Confirm `AuthType` is exactly `AWS_IAM` and `InvokeMode` exactly `RESPONSE_STREAM` in both.
    - _Requirements: 6.3, 6.5, 6.6, 7.2_

- [x] 6. Post-deploy script and pipeline: signed Function URL verification
  - [x] 6.1 Add SigV4-signed Function URL checks to `post-deploy-test.sh`
    - Resolve the `FunctionUrl` stack output. Add a verification that issues SigV4-signed GETs (`curl --aws-sigv4 "aws:amz:$AWS_REGION:lambda" --user "$AWS_ACCESS_KEY_ID:$AWS_SECRET_ACCESS_KEY" -H "x-amz-security-token: $AWS_SESSION_TOKEN"`) to the Function URL. Always sign; never fall back to unsigned. Reuse the full-and-byte-identical check and a single post-warmup first-byte-within-50% measurement. Treat 403 / unreachable / non-2xx as a hard non-zero exit; size/content mismatch likewise. Keep the existing API Gateway checks intact and run the Function URL checks in addition.
    - _Requirements: 7.4, 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7_
  - [x] 6.2 Wire the Function URL output through the workflows
    - In `workflow-deploy-aws.yml` `Get deployment outputs`, additionally read `FunctionUrl` and expose it as a job output. In `workflow-streaming-test.yml` / the pipeline script, pass the Function URL through for both the Kotlin and Java stacks so the signed checks run on every deploy. The job already assumes the OIDC role (SigV4 creds available).
    - _Requirements: 9.1, 9.7_
  - [x] 6.3 Checkpoint - run the script locally against a deployed stack if credentials are available, else confirm shell syntax (`bash -n scripts/post-deploy-test.sh`). Ask the user before any real invocation.

- [x] 7. Documentation and knowledge capture
  - [x] 7.1 Update the README
    - State that both API Gateway and Lambda Function URLs are supported and that the Function URL path is proven end to end by the pipeline for both the Kotlin and Java examples. Add a signed-`curl`/`awscurl` example and note `AuthType: AWS_IAM`.
    - _Requirements: 11.1, 7.6_
  - [x] 7.2 Record gotchas in `docs/log.md`
    - Add a named entry for the `InvokeMode: RESPONSE_STREAM` (Function URL) vs `ResponseTransferMode: RESPONSE_STREAM` (API Gateway SAM) distinction, with symptom and resolution. Append any further hiccup encountered during this feature. No runtime secrets; identifiers by placeholder.
    - _Requirements: 11.2, 11.3, 11.4_

- [x] 8. Final verification
  - [x] 8.1 Full build and coverage gate
    - Run `./gradlew clean build` then `./gradlew koverVerify` (≥ 90%). Fix any gaps in the new resolvers / Java resolver layer. Confirm both example suites and all property tests pass.
    - _Requirements: 3.4, 4.4, 5.1, 5.2, 5.3_

## Task Dependency Graph
```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "3.1"] },
    { "id": 1, "tasks": ["1.2", "3.2"] },
    { "id": 2, "tasks": ["1.3", "1.4", "1.5", "3.3"] },
    { "id": 3, "tasks": ["1.6", "3.4"] },
    { "id": 4, "tasks": ["2.1", "2.2", "3.5"] },
    { "id": 5, "tasks": ["2.3", "3.6"] },
    { "id": 6, "tasks": ["4.1"] },
    { "id": 7, "tasks": ["4.2"] },
    { "id": 8, "tasks": ["4.3", "4.4"] },
    { "id": 9, "tasks": ["4.5"] },
    { "id": 10, "tasks": ["5.1", "5.2"] },
    { "id": 11, "tasks": ["5.3"] },
    { "id": 12, "tasks": ["5.4"] },
    { "id": 13, "tasks": ["6.1"] },
    { "id": 14, "tasks": ["6.2"] },
    { "id": 15, "tasks": ["6.3"] },
    { "id": 16, "tasks": ["7.1", "7.2"] },
    { "id": 17, "tasks": ["8.1"] }
  ]
}
```

The Kotlin track (1,2) and Java track (3,4) are independent and run in parallel through waves 0-9; task 3 precedes task 4. Task 5 (SAM) depends on both handler class names existing; task 6 depends on a deployable Function URL; task 8 is the terminal gate.

## Notes

- The Kotlin track is intentionally small: `StreamHandler<R>` is already generic over the request type, so only a new resolver and a thin entry point are added — nothing in `StreamHandler`, `S3Source`, `FileNameValidator`, `ResponseWriter`, or `FileRequest` changes.
- The Java track is the larger lift: it introduces a resolver abstraction the module lacks today and refactors the existing handler onto it. Task 3.5 explicitly guards against behavior drift by re-running the existing suite.
- IAM authentication (403 on unsigned requests) is a Function URL platform behavior, not handler code; it is proven only by the signed post-deploy script in the pipeline (task 6), never in unit/integration tests. The Floci integration tests feed the handler an `AWS_IAM`-shaped payload-2.0 event but cannot exercise real signing.
- Keep `build.gradle.kts` as the source of truth for versions; do not introduce new libraries for SigV4 — `curl --aws-sigv4` and the AWS CLI already present in the pipeline cover signing.
- Run `sam validate` (exit 0) after every template change before committing (task 5.4).
