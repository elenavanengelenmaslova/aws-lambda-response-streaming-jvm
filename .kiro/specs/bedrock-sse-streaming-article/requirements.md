# Requirements Document

## Introduction

This feature is a standalone narrative article, `docs/bedrock-sse-streaming.md`, that teaches a reader to build and verify progressive Server-Sent Events (SSE) streaming of Amazon Bedrock Nova Lite tokens from a Kotlin/JVM AWS Lambda behind an API Gateway REST endpoint. The article uses this repository's `streaming-core` and `streaming-sse` libraries together with the `streaming-bedrock-example` module as its running example.

The deliverable is a documentation artifact only. This scope covers authoring and verifying the article file; it does not cover changing any Kotlin source, SAM templates, or scripts. The article is framed as a follow-on piece in a streaming series: it refers readers to the existing S3-streaming example for shared cold-start and deployment setup (SnapStart/CRaC priming, SAM `RESPONSE_STREAM`, arm64, tiered compilation) and focuses its own content on the Bedrock/SSE-specific mechanics.

The article's voice matches the author's two existing Medium articles: practical, first-person, conversational, concrete, developer-to-developer, and honest about mistakes and trade-offs.

## Glossary

- **Article**: The Markdown document authored at `docs/bedrock-sse-streaming.md`.
- **Author**: The person who writes and finalizes the Article (the actor these requirements serve).
- **Reader**: The developer who follows the Article to build and verify Bedrock SSE streaming.
- **S3_Example**: The existing S3-streaming example in `streaming-s3-example/`, referenced for shared setup.
- **Bedrock_Example**: The `streaming-bedrock-example` module that the Article uses as its running example.
- **Streaming_Core**: The `streaming-core` library providing the API Gateway streaming wire protocol via `ResponseWriter`.
- **Streaming_SSE**: The `streaming-sse` library providing `PaddedSseWriter` and `PacedSseEmitter`.
- **Pinned_Repo_Base**: The source URL base pinned to the `main` branch, `https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/`.
- **Measured_Results_Table**: The time-to-first-byte (TTFB) and arrival table reproduced verbatim from `streaming-bedrock-example/README.md`.
- **Architecture_Diagram**: The Bedrock-specific architecture diagram at `docs/SolutionDesign-Bedrock.png`, which shares the same layout as `docs/SolutionDesign.png` with Amazon Bedrock replacing the generic data source. The Author adds this diagram file later, so it may not exist at authoring time.
- **Builder_Center_Reference**: The AWS Builder Center article "The Curious Case of SSE Streaming Agent Responses on AWS Lambda" at `https://builder.aws.com/content/39iqpehwjnn2TuNmGnHAnsMcHmP/the-curious-case-of-sse-streaming-agent-responses-on-aws-lambda`.
- **TTFB**: Time to first byte, the elapsed time until the client receives the first response byte.

## Requirements

### Requirement 1: Standalone article file

**User Story:** As the Author, I want the Article written to a new standalone file, so that the existing `docs/article.md` guide is left untouched.

#### Acceptance Criteria

1. THE Article SHALL be created at the path `docs/bedrock-sse-streaming.md`.
2. THE Article SHALL be authored as a new file that does not modify the contents of `docs/article.md`.
3. THE Article SHALL be written in Markdown.

### Requirement 2: Series framing and shared-setup reference

**User Story:** As a Reader, I want the article framed as a follow-on to the S3-streaming example, so that I can reuse the shared setup instead of repeating it.

#### Acceptance Criteria

1. THE Article SHALL state that the Article is part of a streaming series and is a follow-on to the S3-streaming example.
2. THE Article SHALL reference the S3_Example for the shared setup covering SnapStart with CRaC priming, SAM `RESPONSE_STREAM`, arm64, and tiered compilation.
3. THE Article SHALL focus its own detailed content on the Bedrock-specific and SSE-specific parts rather than re-explaining the shared setup.

### Requirement 3: Demo and architecture visuals

**User Story:** As a Reader, I want to see the demo and architecture visuals, so that I understand the behavior and structure before reading the details.

#### Acceptance Criteria

1. THE Article SHALL embed the demo GIF located at `docs/bedrock-streaming-demo.gif`.
2. THE Article SHALL embed the Architecture_Diagram located at `docs/SolutionDesign-Bedrock.png`.
3. WHERE the Architecture_Diagram file does not yet exist at authoring time, THE Article SHALL embed the Architecture_Diagram via a Markdown image reference to `docs/SolutionDesign-Bedrock.png` that renders as a valid Markdown image with descriptive alt text.

### Requirement 4: Architecture and named components

**User Story:** As a Reader, I want the end-to-end architecture explained with its components named, so that I can map the request flow to the code.

#### Acceptance Criteria

1. THE Article SHALL describe the request flow as API Gateway REST with `RESPONSE_STREAM` to a Lambda `RequestStreamHandler` to Bedrock `InvokeModelWithResponseStream` to a padded SSE relay to the client.
2. THE Article SHALL name the component `PromptResolver` and describe its role in the flow.
3. THE Article SHALL name the component `BedrockSource` and describe its role in the flow.
4. THE Article SHALL name the components `PaddedSseWriter` and `PacedSseEmitter` and describe their roles in the flow.
5. THE Article SHALL name the component `BedrockStreamHandler` and describe its role in the flow.
6. THE Article SHALL name the component `ResponseWriter` and describe its role in the flow.
7. THE Article SHALL name the component `Priming` and describe its role in the flow.

### Requirement 5: Consuming the repo libraries and example module

**User Story:** As a Reader, I want to know how to consume the repo's own libraries and example module, so that I can build the same thing in my project.

#### Acceptance Criteria

1. THE Article SHALL explain how to consume Streaming_Core's `ResponseWriter`.
2. THE Article SHALL explain how to consume Streaming_SSE's `PaddedSseWriter` and `PacedSseEmitter`.
3. THE Article SHALL explain how the Bedrock_Example module uses Streaming_Core and Streaming_SSE together.

### Requirement 6: Followable build, test, deploy, and try-it steps

**User Story:** As a Reader, I want followable build, test, validate, deploy, and try-it steps, so that I can reproduce the example end to end.

#### Acceptance Criteria

1. THE Article SHALL provide the command to build the Bedrock_Example.
2. THE Article SHALL provide the command to run the Bedrock_Example tests.
3. THE Article SHALL provide the command to validate the SAM template.
4. THE Article SHALL provide the steps to deploy the Bedrock_Example with SAM.
5. THE Article SHALL provide a `curl` try-it command that drops the SSE padding client-side using a `sed`/`awk` pipeline.

### Requirement 7: Progressive-arrival verification and measured results

**User Story:** As a Reader, I want a way to confirm progressive streaming actually worked, so that I can trust the result rather than assume it.

#### Acceptance Criteria

1. THE Article SHALL reference `scripts/bedrock-streaming-test.sh` as the reproducible progressive-arrival and TTFB verification check.
2. THE Article SHALL state that the verification script is opt-in and guarded by the environment variable `BEDROCK_TEST_ENABLED=true` because it invokes paid Bedrock.
3. THE Article SHALL reproduce the Measured_Results_Table verbatim from `streaming-bedrock-example/README.md`.
4. THE Article SHALL state the measured outcomes for the eu-west-1 region: the no-padding configuration delivers a single terminal burst, and the tiered 100 KB times 3 plus 10 KB configuration yields a TTFB near 0 ms across 9 distinct 500 ms buckets over approximately 4.4 seconds.

### Requirement 8: Server-side buffering mistakes

**User Story:** As a Reader, I want the server-side buffering mistakes documented, so that I avoid the traps that produce a single terminal burst.

#### Acceptance Criteria

1. THE Article SHALL explain that a naive `flush()` does not override the Lambda response-streaming runtime buffer because the buffer lives below the language layer.
2. THE Article SHALL explain that an inter-chunk delay alone does not force chunks past the buffer, referencing the earlier MockNest "dribble" attempt.
3. THE Article SHALL explain that `PacedSseEmitter` enforces the presence of padding before allowing a configured delay.
4. THE Article SHALL explain that the `OutputStream` must be closed with an explicit flush before close to avoid a partial body on warm invocations.
5. THE Article SHALL explain that the response metadata headers must be modeled as `Map<String, String>` rather than `Map<String, List<String>>`, because the latter produces a 502 error.
6. THE Article SHALL explain the SAM and integration naming trap between `STREAM` and `RESPONSE_STREAM`.

### Requirement 9: Client-side buffering mistakes

**User Story:** As a Reader, I want the client-side buffering mistakes documented, so that my client observes progressive delivery.

#### Acceptance Criteria

1. THE Article SHALL explain that `curl` over HTTP/2 can fail with error 92 and that forcing `--http1.1` resolves it.
2. THE Article SHALL explain that the client must drop the SSE padding using a `sed`/`awk` pipeline.
3. THE Article SHALL explain that `curl -N` (`--no-buffer`) is required to avoid client-side buffering.

### Requirement 10: Padding gotcha, trade-off, and attribution

**User Story:** As a Reader, I want the padding gotcha and its trade-off explained with proper credit, so that I understand the cost and the discovery history.

#### Acceptance Criteria

1. THE Article SHALL explain the tiered padding scheme as approximately 100 KB of head padding on the first 3 events and approximately 10 KB of tail padding on each event afterwards.
2. THE Article SHALL explain the bandwidth-for-responsiveness trade-off, including the approximately 100 times to 1000 times response-size inflation.
3. THE Article SHALL credit the Builder_Center_Reference as having discovered the buffering behavior first, including a link to that reference.
4. THE Article SHALL reproduce no more than 30 consecutive words from the Builder_Center_Reference.

### Requirement 11: No layers, no custom runtime

**User Story:** As a Reader, I want to know this approach needs no special runtime machinery, so that I understand its deployment simplicity.

#### Acceptance Criteria

1. THE Article SHALL state explicitly that this approach uses no Lambda layers.
2. THE Article SHALL state explicitly that this approach uses no custom runtime.

### Requirement 12: Pinned GitHub source links

**User Story:** As a Reader, I want GitHub source links pinned to a stable branch, so that the links keep pointing at the code the Article describes.

#### Acceptance Criteria

1. THE Article SHALL link to the Bedrock_Example module source files using URLs built on the Pinned_Repo_Base.
2. THE Article SHALL pin every repository source link to the `main` branch.

### Requirement 13: Medium-style voice and honesty

**User Story:** As a Reader, I want the article to read like the author's Medium pieces, so that it is engaging and honest rather than dry reference material.

#### Acceptance Criteria

1. THE Article SHALL be written in a practical, first-person, conversational, developer-to-developer voice consistent with the author's two referenced Medium articles.
2. THE Article SHALL be honest about the mistakes and trade-offs encountered, rather than presenting only the final working solution.
