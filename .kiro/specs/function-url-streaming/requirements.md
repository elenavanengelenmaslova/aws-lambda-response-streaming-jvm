# Requirements Document

## Introduction

This feature adds a second streaming invocation path — an **AWS Lambda Function URL** — alongside the existing API Gateway REST endpoint, and proves it end to end against a deployed stack. Today both example modules (Kotlin `streaming-s3-example`, Java `streaming-s3-example-java`) expose a single Lambda behind API Gateway, configured with `ResponseTransferMode: RESPONSE_STREAM`. A Lambda Function URL streams through the same wire protocol but uses a different invocation layer (`AWS::Lambda::Url` with `InvokeMode: RESPONSE_STREAM`) and delivers a different event shape to the handler.

The goal is to demonstrate — not merely assert — that the shared `aws-lambda-streaming-core` library works unchanged behind a Function URL, for both Kotlin and Java consumers. Each example gets a dedicated Function URL Lambda (separate from its API Gateway Lambda), so the two invocation paths stay independently configured, independently testable, and clearly contrasted for the guide. The Function URL Lambda reuses the same `streaming-core`, `S3Source`, bounded-buffer copy, SnapStart + CRaC priming, and cold-start settings as the API Gateway Lambda; only the event parsing and the deployment resource differ.

Success is proven the same way as the API Gateway path: unit tests on the new request resolution, Floci integration tests on the handler, and a post-deploy script run in the CI/CD pipeline that confirms a payload larger than the 6 MB buffered limit is delivered in full and that the first byte arrives early (progressive delivery). The Java example currently has no request-resolution abstraction and must gain one to parse the Function URL event. Every gotcha (notably the `ResponseTransferMode: RESPONSE_STREAM` vs. `InvokeMode: RESPONSE_STREAM` distinction) is recorded in `docs/log.md`. This document handles NO PII and uses NO AI/Bedrock.

## Open Decisions (defaults proposed, pending confirmation)

- **D1 — Deployment topology.** Default: add the Function URL Lambda and its `AWS::Lambda::Url` resource to the **existing** SAM template per example (one stack, two Lambdas, two endpoints). Rationale: one `sam deploy`, shared OIDC role and bucket, minimal pipeline change.
- **D2 — Function URL auth type.** **Decided: `AuthType: AWS_IAM`.** The Function URL is not publicly invocable; callers must sign requests with SigV4. The Post_Deploy_Script therefore signs its requests using the pipeline's deploy credentials, and the pipeline deploy role must be granted `lambda:InvokeFunctionUrl` on the Function URL. Captured in Requirement 7 and Requirement 9.

## Glossary

- **Function_URL**: A dedicated HTTPS endpoint for a Lambda, created by an `AWS::Lambda::Url` resource, that invokes the function directly without API Gateway.
- **Function_URL_Lambda**: The example Lambda dedicated to the Function URL invocation path, separate from the existing API Gateway Lambda, sharing the same `streaming-core`, S3 source, bounded-buffer copy, and cold-start configuration.
- **API_Gateway_Lambda**: The existing example Lambda invoked through the API Gateway REST proxy integration.
- **Invoke_Mode**: The `InvokeMode` property on the `AWS::Lambda::Url` resource; must be `RESPONSE_STREAM` for the Function URL to stream.
- **Function_URL_Event**: The JSON event delivered to a Function URL Lambda (Lambda payload format 2.0: `version`, `rawPath`, `requestContext.http`, etc.), distinct from the API Gateway proxy event.
- **Function_URL_Resolver**: The component that parses the Function_URL_Event input stream and resolves the requested file name into a validated domain request, reusing the existing file-name validation rules.
- **Request_Resolver**: The resolution abstraction (`RequestResolver` + `RequestResult` in Kotlin) that maps an incoming event input stream to either a resolved request or an error. The Java example must gain an equivalent.
- **Stream_Handler**: The component implementing `RequestStreamHandler` that reads the event from an `InputStream` and writes the streaming response to an `OutputStream`.
- **Response_Writer**: The `streaming-core` component that writes the metadata JSON, the 8 null-byte delimiter, then the body bytes.
- **S3_Source**: The component that confirms object existence and size, then opens the S3 object as an `InputStream`.
- **Bounded_Buffer**: The fixed ~1 MB transfer buffer used to copy the S3 body to the output stream without materializing the whole object.
- **Priming_Hook**: The CRaC `org.crac.Resource` whose `beforeCheckpoint` warms the critical path for SnapStart.
- **SAM_Template**: The AWS SAM `template.yaml` for an example (`deployment/aws/sam/template.yaml` for Kotlin, `deployment/aws/sam-java/template.yaml` for Java).
- **Pipeline**: The GitHub Actions deploy + streaming-test workflows (`workflow-deploy-aws.yml`, `workflow-streaming-test.yml`) and their invoked scripts.
- **Post_Deploy_Script**: The verification script(s) under `scripts/` that exercise a deployed endpoint and measure streaming behavior.
- **Dev_Log**: The running log of gotchas and fixes in `docs/log.md`.
- **Test_Object**: The ≈15 MB (and ≥6 MB) S3 object used to prove delivery beyond the buffered limit.
- **6MB_Limit**: The legacy 6 MB buffered Lambda response payload limit that response streaming bypasses.
- **Both_Examples**: The Kotlin `streaming-s3-example` module and the Java `streaming-s3-example-java` module.

## Requirements

### Requirement 1: Dedicate a separate Lambda to the Function URL path

**User Story:** As an author of the guide, I want the Function URL served by its own Lambda rather than shared with the API Gateway Lambda, so that each invocation path is configured and tested independently and the contrast is clear.

#### Acceptance Criteria

1. FOR each of Both_Examples, THE SAM_Template SHALL define a Function_URL_Lambda as a resource distinct from the API_Gateway_Lambda, such that the two functions have separate logical resource identifiers.
2. THE Function_URL_Lambda SHALL reuse the same `aws-lambda-streaming-core` Response_Writer, S3_Source, Bounded_Buffer copy, and file-name validation rules as the API_Gateway_Lambda, without duplicating the streaming wire-protocol logic.
3. THE Function_URL_Lambda SHALL differ from the API_Gateway_Lambda only in event parsing (Function_URL_Event vs. API Gateway proxy event) and in its deployment resource (Function_URL vs. API Gateway integration).
4. THE API_Gateway_Lambda and its existing endpoint behavior SHALL remain unchanged by this feature.

### Requirement 2: Parse and resolve the Function URL event

**User Story:** As a client, I want to request a file through the Function URL, so that I receive that file's contents from S3 streamed back.

#### Acceptance Criteria

1. WHEN a request arrives at the Function_URL, THE Function_URL_Resolver SHALL parse the Function_URL_Event from the input stream and extract the requested file name from the request path.
2. IF the Function_URL_Event cannot be parsed from the input stream, THEN THE Stream_Handler SHALL write a response with HTTP status code 400 and an error message indicating that the request could not be parsed, and SHALL NOT write any body bytes.
3. WHEN a file name is extracted from the Function_URL_Event, THE Function_URL_Resolver SHALL validate it using the same rules the API_Gateway_Lambda applies (character allow-list, path-separator rejection, parent-directory rejection, absolute-path rejection, missing-name rejection, and the 1024-character maximum).
4. IF the extracted file name is rejected by validation, THEN THE Stream_Handler SHALL write a response with HTTP status code 400 that indicates the file name was rejected, and SHALL NOT issue any S3 request for the rejected file name.
5. WHEN the file name is valid, THE Stream_Handler SHALL confirm S3 object existence and size before committing any status, and then stream the object body using the same existence-before-commit and bounded-memory behavior as the API_Gateway_Lambda: 404 when the object is absent, 200 with declared content length when present, and 502 only for a retrieval failure detected BEFORE commit (during the existence/size check).
6. IF an S3 read or output-write failure occurs mid-stream AFTER the 200 status and content length have been committed, THEN THE Stream_Handler SHALL abort the response with a truncated body (fewer bytes than the declared content length) and SHALL NOT emit a retroactive 502, since the HTTP status cannot change after commit.

### Requirement 3: Provide a request-resolution abstraction in the Java example

**User Story:** As a maintainer, I want the Java example to resolve requests through the same kind of abstraction the Kotlin example uses, so that both examples stay structurally consistent and the Function URL path is cleanly separated from the API Gateway path.

#### Acceptance Criteria

1. THE Java example SHALL provide a Request_Resolver abstraction equivalent to the Kotlin `RequestResolver` / `RequestResult`, mapping an event input stream to either a resolved request or an error carrying an HTTP status code and message.
2. THE Java example SHALL provide a path-based resolver for the API Gateway proxy event and a Function_URL_Resolver for the Function_URL_Event, both producing the existing validated file request type.
3. WHEN the Java API_Gateway_Lambda is refactored to use the new Request_Resolver abstraction, THE refactoring SHALL preserve its existing externally observable behavior for every existing test case.
4. THE Java Request_Resolver abstraction and both resolvers SHALL have unit tests covering: parse failure → 400, validation rejection → 400, and a valid name → resolved request.

### Requirement 4: Unit-test the Function URL request resolution in both examples

**User Story:** As a maintainer, I want the Function URL resolution covered by unit tests in both languages, so that event parsing and validation are proven without a deployment.

#### Acceptance Criteria

1. FOR each of Both_Examples, THE Function_URL_Resolver SHALL have unit tests that cover an empty input stream → 400, a malformed JSON event → 400, a well-formed Function_URL_Event with a rejected file name → 400, and a well-formed Function_URL_Event with an accepted file name → a resolved request carrying that file name.
2. THE Function_URL_Resolver unit tests SHALL use a representative Lambda payload format 2.0 event shape (including `rawPath` and `requestContext.http`) as the parsed input.
3. THE Function_URL_Resolver unit tests SHALL assert on typed results (resolved vs. error with status code) rather than on string equality of the whole response.
4. THE existing unit and property test suites for Both_Examples SHALL continue to pass unchanged.

### Requirement 5: Integration-test the Function URL handler with Floci in both examples

**User Story:** As a maintainer, I want the Function URL handler exercised against an emulated AWS environment, so that handler-to-S3 wiring is proven before deployment.

#### Acceptance Criteria

1. FOR each of Both_Examples, THE Function_URL_Lambda handler SHALL have a Floci-based integration test that invokes the handler with a Function_URL_Event for a sub-6 MB object and asserts the received body is byte-identical to the stored S3 object.
2. FOR each of Both_Examples, THE Function_URL_Lambda handler SHALL have a Floci-based integration test that invokes the handler with a Function_URL_Event for an at-least-15 MB object and asserts the full body is delivered byte-identically.
3. THE Function URL integration tests SHALL use a Function_URL_Event representative of an `AWS_IAM`-authorized invocation (Lambda payload format 2.0 including a `requestContext.authorizer.iam` section), so the handler is exercised against the same event shape the IAM-authenticated Function URL produces.
4. THE Function URL integration tests SHALL extend the existing shared Floci test base per module and share one container across the test class, cleaning only data between tests, consistent with the existing integration tests.
5. WHERE no local emulator implements the Function URL streaming invocation path or its IAM request signing, THE integration tests MAY invoke the handler directly with a Function_URL_Event rather than through an emulated, signed Function URL; proof of IAM authentication and progressive delivery over the wire SHALL be deferred to the signed Post_Deploy_Script against real AWS (Requirement 9).

### Requirement 6: Configure the Function URL for response streaming

**User Story:** As an operator, I want the Function URL configured for streaming invocation, so that the response is delivered progressively and not buffered.

#### Acceptance Criteria

1. FOR each of Both_Examples, THE SAM_Template SHALL define an `AWS::Lambda::Url` resource targeting the Function_URL_Lambda (or the SAM `FunctionUrlConfig` equivalent on that function).
2. THE Function_URL resource SHALL set `InvokeMode` to the exact value `RESPONSE_STREAM`.
3. IF `InvokeMode` is set to any value other than `RESPONSE_STREAM` (including `BUFFERED`), THEN THE SAM_Template SHALL be treated as invalid for response streaming and SHALL NOT be committed.
4. THE SAM_Template SHALL expose the resolved Function URL as a stack output so the Pipeline can discover it without hard-coding.
5. WHEN the SAM_Template is changed, THE developer SHALL run SAM template validation for the changed template and confirm it returns exit code 0 before committing; WHERE the template is not changed, no validation run is required and absence of one SHALL NOT be treated as a violation.
6. IF SAM template validation returns any exit code other than 0, THEN THE developer SHALL NOT commit the change and SHALL resolve the reported validation error first.

### Requirement 7: Authenticate the Function URL with AWS IAM

**User Story:** As a security reviewer, I want the Function URL to require IAM authentication, so that it is not publicly invocable and every caller is an authenticated, authorized principal.

#### Acceptance Criteria

1. THE Function_URL resource SHALL set `AuthType` to the exact value `AWS_IAM`.
2. IF `AuthType` is set to any value other than `AWS_IAM` (including `NONE`), THEN THE SAM_Template SHALL be treated as invalid for this feature and SHALL NOT be committed.
3. THE pipeline deploy IAM role SHALL be granted the `lambda:InvokeFunctionUrl` action scoped to the specific Function_URL_Lambda ARN (with the function-url auth-type condition), and SHALL NOT be granted a wildcard-resource invoke permission.
4. WHEN the Post_Deploy_Script invokes the Function_URL, THE Post_Deploy_Script SHALL sign every request with AWS Signature Version 4 for the `lambda` service in the deploy region, using the pipeline's deploy credentials, and SHALL NOT send any unsigned request.
5. IF an unsigned or improperly signed request is sent to the Function_URL, THEN THE Function_URL SHALL reject it with an authentication/authorization error (HTTP 403) and SHALL NOT serve object content.
6. WHERE a developer needs to invoke the Function_URL manually, THE README or Dev_Log SHALL document how to sign the request (for example `curl --aws-sigv4 "aws:amz:<region>:lambda"` with credentials, or `awscurl`), so the IAM requirement is reproducible outside the pipeline.

### Requirement 8: Apply the same cold-start optimizations to the Function URL Lambda

**User Story:** As an operator, I want the Function URL Lambda to carry the same cold-start configuration as the API Gateway Lambda, so that the two paths are compared on equal footing.

#### Acceptance Criteria

1. THE Function_URL_Lambda SHALL be configured with an auto-published alias and SnapStart apply-on-published-versions, consistent with the API_Gateway_Lambda.
2. THE Function_URL_Lambda SHALL register a Priming_Hook whose before-checkpoint phase exercises S3 client initialization, one handler invocation against a primed Function_URL_Event, and response metadata serialization.
3. THE Function_URL_Lambda SHALL inherit arm64 architecture and the Java 25 runtime from the global configuration without per-function override, and SHALL set `JAVA_TOOL_OPTIONS` to enable tiered compilation stopping at level 1.
4. IF the Function_URL points at an alias or published version, THEN the configuration SHALL resolve to the SnapStart-enabled published version rather than `$LATEST`.

### Requirement 9: Prove Function URL streaming end to end in the pipeline

**User Story:** As an author of the guide, I want the pipeline to prove the Function URL streams a large payload progressively, so that "Lambda Function URL supported" is demonstrated against a real deployment for both languages.

#### Acceptance Criteria

1. WHEN the Pipeline deploys an example stack, THE Pipeline SHALL discover the Function URL from the stack output and run the Post_Deploy_Script against it, for each of Both_Examples, such that invoking the Function_URL is a mandatory verification step on every deploy and is never skipped.
2. THE Post_Deploy_Script SHALL always invoke the Function_URL as part of deployment verification, and SHALL sign every such request with AWS Signature Version 4 (`lambda` service, deploy region) using the pipeline's deploy credentials, since the Function_URL uses `AuthType: AWS_IAM` (Requirement 7).
3. WHEN the Post_Deploy_Script runs a signed request against the deployed Function_URL, THE Post_Deploy_Script SHALL confirm that a payload larger than the 6MB_Limit (an object of at least 15 MB) is delivered in full and is byte-identical to the Test_Object.
4. WHEN the Post_Deploy_Script measures first-byte timing against the Function_URL with a signed request, after a single discarded warmup request, THE Post_Deploy_Script SHALL take one post-warmup measurement and confirm the first byte arrives no later than 50% of that measurement's total response completion time, proving progressive delivery.
5. IF the Function_URL is unreachable, returns an authentication/authorization failure, or returns any other non-success response, THEN THE Post_Deploy_Script SHALL terminate with a non-zero exit code and an error message, without reporting success.
6. IF the delivered payload size or content does not match the Test_Object, THEN THE Post_Deploy_Script SHALL terminate with a non-zero exit code and an error message indicating the mismatch.
7. THE Pipeline SHALL run the Function URL verification in addition to, not in place of, the existing API Gateway verification, so that both paths are proven on every deploy.

### Requirement 10: Reuse secure and cost-bounded defaults for the Function URL Lambda

**User Story:** As a security reviewer, I want the Function URL Lambda to follow the same least-privilege and cost-bounded defaults as the existing Lambda, so that the new resource does not weaken the example.

#### Acceptance Criteria

1. THE Function_URL_Lambda execution role SHALL grant `s3:GetObject` only, scoped to the specific source bucket ARN and its objects, with no other S3 action and no wildcard resource.
2. THE Function_URL SHALL serve responses only over HTTPS (TLS); the Function URL endpoint is HTTPS-only by platform default and the template SHALL NOT weaken this.
3. THE Function_URL_Lambda SHALL have a CloudWatch log group with a finite retention period between 1 and 30 days inclusive.
4. THE Function_URL_Lambda memory SHALL be a bounded value between 128 MB and 1024 MB inclusive, and its timeout a bounded value between 1 and 300 seconds inclusive.
5. THE SAM_Template SHALL NOT configure provisioned concurrency or other always-on capacity for the Function_URL_Lambda.
6. THE Function_URL_Lambda SHALL reuse the existing source S3 bucket and its encryption, block-public-access, and lifecycle configuration without creating an unmanaged second bucket.

### Requirement 11: Document the Function URL path and record gotchas

**User Story:** As an author of the guide, I want the Function URL path documented and its gotchas captured, so that the README is accurate and the running log can feed the article.

#### Acceptance Criteria

1. THE README SHALL describe that both API Gateway and Lambda Function URLs are supported and SHALL note that the Function URL path is proven end to end by the pipeline for both the Kotlin and Java examples.
2. WHEN the `InvokeMode: RESPONSE_STREAM` (Function URL) versus `ResponseTransferMode: RESPONSE_STREAM` (API Gateway SAM) distinction is encountered, THE Dev_Log SHALL record it as a named gotcha with symptom and resolution.
3. WHEN any hiccup, bug, or fix is encountered during this feature, THE developer SHALL append an entry to the Dev_Log with a title, a symptom or trigger, and the resolution or current status, within the same development session; the obligation to log is independent of session timing, so IF the entry is not logged within that session THEN the requirement SHALL be treated as violated and the log entry SHALL remain owed.
4. THE Dev_Log SHALL NOT contain runtime secrets; any such detail SHALL be recorded by descriptive name or placeholder rather than literal value.
