# Requirements Document

## Introduction

This spec covers an investigation-first body of work on the `aws-lambda-streaming-jvm-runtime` repository (AWS account `021259937026`, region `eu-west-1`). The driving open question is: **does Amazon Bedrock (Nova Lite) token streaming arrive progressively at an HTTP client when relayed through an API Gateway REST endpoint fronting a Kotlin/JVM `RequestStreamHandler` Lambda, or does it arrive as a single terminal burst?**

The established runtime fact for this repo is that the managed Java Lambda streaming runtime behind API Gateway REST forwards flushed bytes based on **write volume + invocation completion**, not on `flush()` while the handler is idle. Continuous/large writes stream smoothly; small writes with idle gaps get buffered and burst near the end. Bedrock's token cadence is bursty-but-continuous, so the outcome is genuinely unknown and must be **measured**, not assumed.

The work proceeds in strict order so cost and risk stay bounded:

1. A **throwaway** Bedrock POC (minimal standalone handler + a copied SAM stack) measures the arrival cadence, then is **torn down completely**.
2. A **~40-second streaming run** proves that a streaming response is not capped by the API Gateway integration timeout.
3. **Only if** the POC shows progressive arrival and the user greenlights it, a production-grade `streaming-bedrock-example` module is added, mirroring `streaming-s3-example`.
4. **Last**, all documentation is produced: an S3 streaming demo GIF, a root-README restructure (hero GIF + green-checkmark problems-solved checklist + solution-design image), and a Bedrock GIF if the module was built.

No production resource is modified. The deployed `s3-file-streaming-endpoint` stack and its handler are never changed. Every throwaway artifact is removed. Bedrock invocations cost money and are gated behind explicit user confirmation. No secrets (API key values, execute-api host) are ever written to recordings, logs, or the repo.

## Glossary

- **Bedrock_POC**: The throwaway, clearly-labeled experiment (handler + SAM stack) used solely to measure Bedrock streaming cadence; deleted after measurement.
- **Nova_Lite**: Amazon Bedrock's low-cost model used for the experiment, addressed via its eu-west-1 inference-profile id.
- **Exp_Handler**: The minimal standalone Kotlin `RequestStreamHandler` that relays Bedrock `InvokeModelWithResponseStream` text deltas to the Lambda `OutputStream`, writing the streaming protocol via the `streaming-core` `ResponseWriter`.
- **Exp_Stack**: The CloudFormation/SAM stack `bedrock-stream-exp` deploying the Exp_Handler on route `/exp/bedrock-stream`, copied from the S3 example template.
- **Response_Writer**: The existing `streaming-core` component writing metadata JSON + the 8 null-byte delimiter before body bytes.
- **Cadence_Measurement**: The per-chunk elapsed-ms recording that classifies arrival as progressive (multiple waves) or terminal burst.
- **Timeout_Proof**: The ~40-second streaming run demonstrating delivery past the API Gateway REST integration timeout.
- **Integration_Timeout**: The API Gateway REST integration timeout that caps buffered (non-streaming) responses.
- **Idle_Timeout**: The API Gateway window permitted between streamed chunks before the connection is closed.
- **Bedrock_Module**: The conditional production-grade `streaming-bedrock-example` Gradle module mirroring `streaming-s3-example`.
- **S3_Demo_GIF**: The dark-themed recording of the deployed S3 endpoint streaming `streaming-test-12mb.bin` progressively.
- **Bedrock_Demo_GIF**: The dark-themed recording of the Bedrock token stream (only if the module is built).
- **Dev_Log**: The running log at `docs/log.md` (Title / Symptom / Resolution format, no secrets).
- **Review_Gate**: A mandatory pause for explicit user approval before proceeding.

## Requirements

### Requirement 1: Author the spec documents first

**User Story:** As a maintainer, I want requirements, design, and tasks captured before implementation, so that the investigation is traceable and reviewable.

#### Acceptance Criteria

1. WHEN work begins, THE system SHALL create `.kiro/specs/bedrock-streaming-poc/requirements.md`, `design.md`, and `tasks.md` before any code or infrastructure change.
2. THE spec documents SHALL match the format and conventions of the existing specs under `.kiro/specs/`.
3. THE `tasks.md` SHALL enumerate the ordered task breakdown including the cost gate, teardown steps, and conditional tasks.

### Requirement 2: Confirm Nova Lite availability and scope the IAM grant without invoking the model

**User Story:** As an operator, I want model availability and least-privilege permissions confirmed before any paid call, so that the experiment is correct and cost-safe.

#### Acceptance Criteria

1. WHEN preparing the experiment, THE system SHALL resolve the exact Nova_Lite inference-profile id and confirm its availability in eu-west-1.
2. THE system SHALL draft a `bedrock:InvokeModelWithResponseStream` IAM statement scoped only to the Nova_Lite resource, destined for the Exp_Handler execution role.
3. THE system SHALL NOT invoke any Bedrock model while confirming availability or scoping IAM.

### Requirement 3: Build the throwaway Bedrock streaming handler

**User Story:** As a developer, I want a minimal handler that relays Bedrock deltas with per-token flush, so that the measurement reflects the simplest possible streaming relay.

#### Acceptance Criteria

1. THE Exp_Handler SHALL be a standalone `RequestStreamHandler` that parses a prompt from the request body, calls `InvokeModelWithResponseStream` on Nova_Lite via the Kotlin AWS SDK (`aws.sdk.kotlin:bedrockruntime`), and relays text deltas.
2. THE Exp_Handler SHALL call `ResponseWriter.writeMetadata` exactly once before writing any body byte.
3. FOR each received text delta, THE Exp_Handler SHALL write the delta bytes to the `OutputStream` and flush immediately.
4. THE Exp_Handler SHALL emit light per-token logging (index and elapsed ms) and SHALL NOT log model content, prompts, the API key, or the execute-api host.
5. THE Exp_Handler SHALL be clearly labeled as throwaway and SHALL NOT wire through the `RequestResolver`/`StreamSource` abstraction.
6. THE Exp_Handler SHALL register a CRaC priming hook, and the build SHALL run without relying on the Gradle cache.

### Requirement 4: Deploy the throwaway experiment as an isolated stack

**User Story:** As an operator, I want the experiment deployed as its own stack mirroring the project's Lambda standards, so that the real example is never touched.

#### Acceptance Criteria

1. THE Exp_Stack SHALL be defined by a copy of the S3 example SAM template (`deployment/aws/sam/template-bedrock-exp.yaml`) and SHALL NOT modify the real template or function in place.
2. THE Exp_Stack SHALL expose the Exp_Handler on route `/exp/bedrock-stream` with API-key auth, `ResponseTransferMode: RESPONSE_STREAM`, SnapStart `ApplyOn: PublishedVersions` + `AutoPublishAlias: live`, arm64, java25, and tiered-compilation `JAVA_TOOL_OPTIONS`.
3. WHEN the template changes, THE system SHALL run `sam validate` and require exit code 0 before deploying.
4. WHEN deploying, THE system SHALL deploy in the foreground, poll `describe-stacks` StackStatus for a `*_COMPLETE` state, and confirm the deployed Lambda `CodeSha256` matches the local jar before trusting results.

### Requirement 5: Measure Bedrock streaming cadence only after explicit cost confirmation

**User Story:** As a cost owner, I want to approve the first paid Bedrock call, so that spend is controlled.

#### Acceptance Criteria

1. THE system SHALL obtain explicit user confirmation before the FIRST Bedrock model invocation.
2. AFTER confirmation, THE system SHALL issue one discarded warmup invocation, then 2 to 3 measured runs only.
3. FOR each measured run, THE Cadence_Measurement SHALL record first-byte ms, last-byte ms, first-to-last spread, the count of distinct 100 ms arrival buckets, and whether more than one arrival wave occurred versus a single terminal burst.
4. IF any run is recorded as a terminal session, THEN the API key value and execute-api host SHALL NOT appear on screen.

### Requirement 6: Report and log the verdict

**User Story:** As a maintainer, I want a clear verdict, so that the module decision is grounded in evidence.

#### Acceptance Criteria

1. THE system SHALL report a verdict of progressive (smooth or bursty) versus terminal burst, accompanied by the timing table.
2. THE system SHALL append a Dev_Log entry recording the verdict and method, containing no secrets.

### Requirement 7: Mandatory teardown of the Bedrock experiment

**User Story:** As an operator, I want every throwaway artifact removed regardless of outcome, so that the account and repo return to their prior state.

#### Acceptance Criteria

1. THE system SHALL delete the Exp_Stack (including IAM additions) and confirm it reaches `DELETE_COMPLETE`.
2. THE system SHALL delete the copied template, the throwaway handler source, and any experiment-only jars, and revert any edits to real files.
3. THE system SHALL remove local scratch files and report `git status`.
4. THE system SHALL confirm the real `s3-file-streaming-endpoint` stack is unchanged.

### Requirement 8: Resolve the streaming/integration timeout figures from official AWS docs

**User Story:** As a technical writer, I want the timeout numbers sourced from AWS, so that the README checklist is accurate rather than assumed.

#### Acceptance Criteria

1. THE system SHALL obtain the API Gateway REST integration-timeout figure, the Lambda response-streaming duration/idle figures, and the Lambda maximum timeout from official AWS documentation.
2. THE system SHALL record the exact figures together with their source URLs.
3. IF a figure contradicts the repository's existing README "Limitations" wording, THEN the system SHALL note the discrepancy for reconciliation.

### Requirement 9: Prove streaming survives past the API Gateway integration timeout

**User Story:** As a developer, I want evidence a long streaming response is not cut at the integration timeout, so that the streaming claim is empirically backed.

#### Acceptance Criteria

1. THE Timeout_Proof SHALL produce a streaming response whose total transfer time is at least 40 seconds using a throwaway resource, and SHALL NOT modify the deployed `s3-file-streaming-endpoint` stack or handler.
2. THE Timeout_Proof run SHALL return HTTP 200 with the complete payload and a time-to-first-byte well below the total time.
3. THE system SHALL record per-chunk elapsed-ms showing bytes arriving both before and after the integration-timeout threshold.
4. AFTER measurement, THE system SHALL delete any throwaway resource created for the proof, append a Dev_Log entry with the measured duration and mechanism, report `git status`, and confirm the real stack is unchanged.

### Requirement 10: Conditionally build the production-grade Bedrock module

**User Story:** As a maintainer, I want a proper example module only when streaming is proven progressive, so that the repo ships only validated functionality.

#### Acceptance Criteria

1. THE Bedrock_Module tasks SHALL run ONLY IF the Requirement 6 verdict is progressive arrival AND the user greenlights the follow-up.
2. WHEN built, THE Bedrock_Module SHALL be a new Gradle module `streaming-bedrock-example` mirroring `streaming-s3-example`, implementing a `BedrockSource : StreamSource<R>` and a `PromptResolver : RequestResolver<R>` wired through the shared `StreamHandler`, with a CRaC priming hook.
3. WHEN built, THE Bedrock_Module SHALL include unit and property tests using MockK with at least 80% Kover coverage, a production-grade SAM template mirroring the S3 example (streaming, SnapStart, priming, arm64, java25, tiered-compilation, scoped Bedrock IAM, API-key auth) validating with `sam validate` exit 0, and a cost-guarded opt-in post-deploy progressive-arrival test mirroring `scripts/pipeline-streaming-test.sh`.

### Requirement 11: Produce documentation last, behind review gates

**User Story:** As a maintainer, I want the GIFs reviewed before they land in any README, so that only approved media is published.

#### Acceptance Criteria

1. THE system SHALL record the S3_Demo_GIF showing a real `curl` command (placeholders only on screen) and curl's native progress meter filling to the full object size, and SHALL confirm zero occurrences of the real API key value and execute-api host in the recording before rendering.
2. THE system SHALL pause for explicit user approval of the S3_Demo_GIF before making any README change.
3. AFTER approval, THE system SHALL restructure the root README so the S3_Demo_GIF leads, followed by a green-checkmark checklist (6 MB to up to 200 MB payload; API Gateway integration timeout does not cap streaming, bounded by the idle-timeout window and the Lambda maximum timeout, using the Requirement 8 figures refined by the Requirement 9 result; flat memory via a bounded buffer), followed by the existing library intro, followed by the `docs/SolutionDesign.png` image, with no existing content dropped.
4. IF the Bedrock_Module was built, THEN the system SHALL record the Bedrock_Demo_GIF, pause for explicit user approval, and only after approval wire it into the module README and the root README.
5. THE system SHALL append any remaining Dev_Log gotchas and report a final `git status` and verdict summary.
