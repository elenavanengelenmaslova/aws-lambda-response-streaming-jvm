# Requirements Document

## Introduction

This feature promotes the SSE padding/dribble capability out of `streaming-bedrock-example` into a new, reusable Gradle module named `streaming-sse`, published and versioned like `streaming-core`. The capability exists to defeat a property of the AWS Lambda response-streaming runtime: small writes (a few bytes per LLM token or SSE event) are held in an internal buffer (~100 KB observed) and only forwarded to the client when that buffer fills or the invocation ends. There is no `flush()` that overrides this behaviour, so a naive token relay arrives as a single terminal burst rather than progressively.

The proven workaround is **tiered SSE-comment padding**: inflate the first few events with large padding (~100 KB) to break the initial buffer, then apply smaller padding (~10 KB) to every subsequent event to keep the stream flowing. A prior "dribble" attempt (an inter-chunk delay, in MockNest) did not work on its own because without padding each small write never crossed the buffer; the delayed chunks still coalesced and burst at the end. The padding is the mechanism that makes progressive delivery possible; the delay only paces delivery once padding is present.

The new module exists to let the repository's own examples and the external MockNest project reuse this mechanism — `PaddedSseWriter` (promoted from the Bedrock example) plus a new, optional inter-chunk delay (dribble/pacing) capability that the local writer does not yet have. `streaming-sse` depends only on `streaming-core` and mirrors `streaming-core`'s conventions. Changes to `streaming-core`'s public API are out of scope, as is Function URL support.

## Glossary

- **Streaming_SSE_Module**: The new Gradle module `streaming-sse` that provides the SSE padding and dribble capability as a reusable library extension.
- **Streaming_Core_Module**: The existing `streaming-core` module that implements the API Gateway streaming wire protocol (`ResponseWriter` + bounded-buffer `copy()`), depends only on `kotlinx-serialization-json`, and exposes a committed Maven Central API under `explicitApi()`.
- **Padded_SSE_Writer**: The `PaddedSseWriter` class, promoted from `streaming-bedrock-example`, that writes SSE `data:` events followed by tiered SSE-comment padding and flushes per event.
- **SSE**: Server-Sent Events, a text event stream format where each event is a line prefixed `data: ` and comment lines are prefixed `: `.
- **SSE_Comment_Line**: A line beginning with `: ` that compliant SSE clients ignore and that a `sed -n 's/^data: //p'` filter discards; used to carry padding bytes without corrupting payload.
- **Lambda_Streaming_Runtime**: The AWS Lambda response-streaming runtime that buffers small writes (~100 KB observed) before forwarding them to the client.
- **Tiered_Padding**: A padding scheme applying a larger head padding (`headPadBytes`) to the first `headPadEvents` events and a smaller tail padding (`tailPadBytes`) to every event afterwards.
- **Head_Padding**: The padding byte count (`headPadBytes`) applied to the first `headPadEvents` events to break the initial runtime buffer.
- **Tail_Padding**: The padding byte count (`tailPadBytes`) applied to every event after the first `headPadEvents` events to keep the stream flowing.
- **Head_Event_Count**: The number of initial events (`headPadEvents`) that receive Head_Padding.
- **Inter_Chunk_Delay**: An optional, bounded pause applied between consecutive events to deliberately pace (dribble) delivery.
- **Recoverable_Payload**: The original event text that must be extractable from the padded SSE stream using a `sed -n 's/^data: //p'` filter. Applying the filter to the event stream excluding the Terminal_Event yields exactly the concatenation of the original event texts; applying it to the full stream (including the Terminal_Event) yields that same concatenation followed by a trailing `[DONE]` line contributed by the Terminal_Event.
- **Terminal_Event**: The end-of-stream event emitted when the stream is ended, written as the exact byte sequence `event: end\ndata: [DONE]\n\n`. Its `data: [DONE]` line is captured by a `sed -n 's/^data: //p'` filter as a trailing `[DONE]` line — a single intentional, known sentinel addition, not payload corruption.
- **Done_Sentinel**: The trailing `[DONE]` line that a `sed -n 's/^data: //p'` filter extracts from the Terminal_Event's `data: [DONE]` line.
- **Bedrock_Example**: The `streaming-bedrock-example` module, which currently contains the local `PaddedSseWriter` and will be refactored to consume Streaming_SSE_Module.
- **Post_Deploy_Test**: The deployed-endpoint verification that measures progressive arrival (time-to-first-byte and token distribution across time buckets).
- **Build_Log**: The `docs/log.md` file, the running record of gotchas and fixes that feeds the article.
- **Api_Scoped_Dependency**: A Gradle `api`-configuration dependency that is exposed on the compile and runtime classpath of downstream consumers, so a consumer of Streaming_SSE_Module receives Streaming_Core_Module (and its own transitive dependencies) without declaring it.

## Requirements

### Requirement 1: New reusable `streaming-sse` module

**User Story:** As a developer maintaining this multi-module project, I want a new `streaming-sse` Gradle module that depends only on `streaming-core`, so that the SSE streaming workaround is a reusable, versioned library extension rather than example-local code.

#### Acceptance Criteria

1. THE Streaming_SSE_Module SHALL declare Streaming_Core_Module as an api-scoped project dependency so that Streaming_Core_Module and its transitive dependency on `kotlinx-serialization-json` are exposed on the compile and runtime classpath of any consumer that depends on Streaming_SSE_Module.
2. THE Streaming_SSE_Module SHALL declare Streaming_Core_Module as its only non-test project dependency.
3. WHEN a consumer declares a dependency on Streaming_SSE_Module, THE resolved consumer classpath SHALL include Streaming_Core_Module transitively without the consumer declaring Streaming_Core_Module explicitly.
4. THE Streaming_SSE_Module SHALL resolve a compile and runtime classpath whose coordinates contain no module whose group identifier begins with `aws`, `software.amazon`, or `com.amazonaws`.
5. THE Streaming_SSE_Module SHALL configure a Java language toolchain of version 21 and a JVM bytecode target of version 21, matching Streaming_Core_Module.
6. THE Streaming_SSE_Module SHALL enable `explicitApi()` so that every public declaration has an explicitly stated visibility and return type.
7. THE Streaming_SSE_Module SHALL configure the Kover verification rule with a minimum line-coverage bound of 90 percent.
8. IF the module's measured line coverage is below 90 percent, THEN THE Streaming_SSE_Module SHALL fail the Kover verification task and report the measured coverage against the 90 percent bound.
9. THE Streaming_SSE_Module SHALL maintain a committed binary-compatibility API dump produced and validated by the same binary-compatibility-validator convention (`apiDump` to generate, `apiCheck` to verify) used by Streaming_Core_Module.
10. IF the module's current public API differs from its committed API dump, THEN THE Streaming_SSE_Module SHALL fail the API-check verification task and report the differing declarations.
11. THE Streaming_SSE_Module SHALL be registered via an `include` entry in `settings.gradle.kts`.
12. THE Streaming_SSE_Module SHALL derive its published version from the same `releaseVersion` git-tag-driven Gradle property as Streaming_Core_Module, so that Streaming_SSE_Module and Streaming_Core_Module are released at the same version from the same tag.
13. WHERE the module is configured for release, THE Streaming_SSE_Module SHALL publish to Maven Central under group `nl.vintik` with a distinct artifact identifier and with all publications signed, matching the publishing configuration of Streaming_Core_Module.

### Requirement 2: Padded SSE Writer with tiered padding

**User Story:** As a developer streaming small incremental chunks from a JVM Lambda, I want a Padded_SSE_Writer with configurable tiered padding, so that each chunk crosses the Lambda_Streaming_Runtime internal buffer and reaches the client progressively.

#### Acceptance Criteria

1. THE Padded_SSE_Writer SHALL accept a configurable Head_Padding byte count, Tail_Padding byte count, and Head_Event_Count.
2. WHEN the writer is constructed without explicit padding values, THE Padded_SSE_Writer SHALL default Head_Padding to 102400 bytes, Tail_Padding to 10240 bytes, and Head_Event_Count to 3.
3. WHEN an event is written and the zero-based event index is less than the Head_Event_Count, THE Padded_SSE_Writer SHALL apply Head_Padding to that event.
4. WHEN an event is written and the zero-based event index is greater than or equal to the Head_Event_Count, THE Padded_SSE_Writer SHALL apply Tail_Padding to that event.
5. WHEN an event is written, THE Padded_SSE_Writer SHALL emit a `data: <text>` line terminated by a blank line.
6. WHEN an event is written and the padding byte count for that event is greater than zero, THE Padded_SSE_Writer SHALL emit, after the data line, an SSE_Comment_Line of the form `: <spaces>` carrying the padding bytes and terminated by a blank line.
7. WHEN an event is written and the padding byte count for that event is zero, THE Padded_SSE_Writer SHALL omit the SSE_Comment_Line for that event.
8. WHEN an event is written, THE Padded_SSE_Writer SHALL flush the output stream before returning.
9. WHEN an event is written, THE Padded_SSE_Writer SHALL return the number of bytes written including padding.
10. WHEN the stream is ended, THE Padded_SSE_Writer SHALL emit the terminal end-of-stream event as the exact byte sequence `event: end\ndata: [DONE]\n\n` and SHALL flush the output stream before returning.
11. IF any of Head_Padding, Tail_Padding, or Head_Event_Count is negative, THEN THE Padded_SSE_Writer SHALL reject construction with an observable error and complete construction with no partially-constructed object retained.
12. THE Padded_SSE_Writer SHALL expose the count of events written so far through an `eventsWritten()` accessor.

### Requirement 3: Optional inter-chunk dribble delay

**User Story:** As a developer who wants deliberately paced delivery, I want an optional inter-chunk delay, so that events arrive spread over time rather than as fast as the model produces them.

#### Acceptance Criteria

1. WHERE an Inter_Chunk_Delay in the inclusive range 1 to 5000 milliseconds is configured, THE Streaming_SSE_Module SHALL pause for the configured duration in the gap between completing one event write and starting the next event write.
2. WHEN no Inter_Chunk_Delay is configured, THE Streaming_SSE_Module SHALL write events with zero added pause between them.
3. IF a configured Inter_Chunk_Delay exceeds the maximum of 5000 milliseconds, THEN THE Streaming_SSE_Module SHALL reject the configuration with an observable error and retain the prior valid configuration.
4. IF a configured Inter_Chunk_Delay is below the minimum of 1 millisecond and is not the unset/no-delay value, THEN THE Streaming_SSE_Module SHALL reject the configuration with an observable error and retain the prior valid configuration.
5. WHERE an Inter_Chunk_Delay is configured together with padding, THE Streaming_SSE_Module SHALL write and flush each padded event (each exceeding the Lambda_Streaming_Runtime buffer threshold) before applying the configured delay.
6. IF an Inter_Chunk_Delay is configured without any padding, THEN THE Streaming_SSE_Module SHALL reject the configuration with an observable error, because delay alone does not force chunks past the Lambda_Streaming_Runtime buffer.

### Requirement 4: Padding must preserve recoverable payload

**User Story:** As a client consuming the padded SSE stream, I want the padding to never corrupt the payload, so that filtering the stream yields the exact original event text.

#### Acceptance Criteria

1. WHEN a `sed -n 's/^data: //p'` filter is applied to the event stream excluding the Terminal_Event for a sequence of events, THE Streaming_SSE_Module SHALL yield the concatenation of the original event texts byte-identically.
2. WHEN a `sed -n 's/^data: //p'` filter is applied to the full stream including the Terminal_Event for a sequence of events, THE Streaming_SSE_Module SHALL yield the concatenation of the original event texts byte-identically followed by the Done_Sentinel as the single trailing line.
3. THE Streaming_SSE_Module SHALL write all padding bytes only within SSE_Comment_Line entries.
4. FOR ALL event sequences in a round-trip corpus of 10 to 20 diverse cases, where each case has between 1 and 20 events and each payload has a byte length spanning 0 bytes up to a few kilobytes, applying the `sed -n 's/^data: //p'` filter to the written output excluding the Terminal_Event SHALL reproduce the original Recoverable_Payload byte-identically (round-trip property).
5. THE round-trip corpus SHALL include payloads containing a leading `:` character, payloads containing the literal text `data:`, payloads containing embedded newline characters, and payloads containing multi-byte UTF-8 characters.
6. THE round-trip corpus SHALL include an empty-payload boundary case and an empty-sequence boundary case.
7. FOR ALL cases in the round-trip corpus, THE Streaming_SSE_Module SHALL write no padding event and no non-terminal data event whose bytes would cause the `sed -n 's/^data: //p'` filter output to diverge from the concatenation of the original event texts, and the only filter-visible line the Terminal_Event contributes SHALL be the Done_Sentinel.

### Requirement 5: Bedrock example refactored to consume the module

**User Story:** As a maintainer of the Bedrock example, I want it to consume `streaming-sse` instead of its local `PaddedSseWriter`, so that there is a single source of the streaming workaround with no behaviour change.

#### Acceptance Criteria

1. THE Bedrock_Example SHALL declare a build dependency on Streaming_SSE_Module.
2. THE Bedrock_Example SHALL use the Padded_SSE_Writer provided by Streaming_SSE_Module for all SSE output.
3. THE Bedrock_Example source tree SHALL NOT contain a local `PaddedSseWriter` class.
4. THE Bedrock_Example source tree SHALL NOT contain a local test for the removed `PaddedSseWriter` class.
5. WHEN the project is built, THE Bedrock_Example SHALL compile and pass all of its tests with zero references to a local `PaddedSseWriter` class.
6. WHEN the Post_Deploy_Test is run against the deployed Bedrock endpoint, THE Bedrock_Example SHALL deliver the first response chunk to the client before the full response completes.
7. WHEN the Post_Deploy_Test is run against the deployed Bedrock endpoint, THE Bedrock_Example SHALL return response content byte-identical to the content produced before the refactor.

### Requirement 6: Documentation of cause, workaround, and trade-off

**User Story:** As a library user, I want the module documentation to explain why the workaround is needed and what it costs, so that I can decide whether to use it and configure it correctly.

#### Acceptance Criteria

1. THE Streaming_SSE_Module documentation SHALL contain explanatory content describing the Lambda_Streaming_Runtime internal-buffer behaviour as the cause of non-progressive delivery for small chunks.
2. THE Streaming_SSE_Module documentation SHALL contain explanatory content describing the tiered padding and optional dribble workaround.
3. THE Streaming_SSE_Module documentation SHALL contain the verifiable claim that an Inter_Chunk_Delay alone does not force chunks past the Lambda_Streaming_Runtime buffer and is effective only when combined with padding.
4. THE Streaming_SSE_Module documentation SHALL state the bandwidth trade-off that padding inflates the on-the-wire response size by roughly 100 to 1000 times relative to the original payload.
5. THE Streaming_SSE_Module documentation SHALL cite the AWS Builder Center article describing the runtime-buffer behaviour as a resolvable link.

### Requirement 7: Knowledge capture in the build log

**User Story:** As the author of the accompanying article, I want the build log to reference the buffer and padding findings, so that the knowledge feeds the published guide.

#### Acceptance Criteria

1. THE Build_Log SHALL record the Lambda_Streaming_Runtime buffer finding, including both that small writes are held in a runtime-level buffer below the language layer and that buffered bytes are released only when the buffer fills or the stream ends.
2. THE Build_Log SHALL record the tiered-padding workaround, including that appending tiered padding to each event large enough to cross the runtime buffer causes the client to observe progressive delivery.
3. THE Build_Log SHALL record the finding that an Inter_Chunk_Delay applied without padding does not cross the runtime buffer, so the client observes a single terminal burst rather than progressive delivery.
4. THE Build_Log SHALL record, for the tiered-padding workaround, the bandwidth trade-off that the padding inflates the on-the-wire response size relative to the original payload.

## Open Questions

1. **Consumer set and publishing scope.** The module is intended for reuse in `streaming-bedrock-example` now and later in `../mocknest-serverless-recovered` (to replace its non-functional, zero-value dribble delay). Should `streaming-sse` be published to Maven Central from the start (like `streaming-core`), or stay internal until MockNest actually consumes it? This affects whether Requirement 1.13 (publishing) is in the first increment. A BOM (bill-of-materials) was considered for aligning consumer-side versions and has been **deferred for now**: the decision is to rely on the api-scoped transitivity of Streaming_Core_Module (Requirement 1.1–1.3) plus lockstep git-tag-driven versioning (Requirement 1.12), and to add a BOM later only if the module set grows. A BOM's main remaining value is for consumers that import `streaming-core` and `streaming-sse` (or future modules) independently and want their versions aligned; with api-scoped transitivity a consumer of `streaming-sse` already gets a matching `streaming-core` without declaring it, so a BOM adds little at the current two-module scale.
2. **Content-agnostic primitive split.** Should a content-agnostic "minimum-chunk padding" primitive be extracted separately from the SSE-specific `PaddedSseWriter` (so non-SSE consumers can force the buffer break without SSE framing), or is a single SSE-specific writer sufficient for now?
3. **Java target discrepancy.** Repo-level steering specifies a Java 25 target, but `streaming-core` actually targets Java 21 and the issue specifies Java 21 for this module (a library bytecode target is a floor for all consumers, and Java 21 is a supported Lambda runtime). Requirement 1.3 follows `streaming-core`'s actual Java 21 target — confirm this is correct and that the steering/library discrepancy is acceptable.
4. **Default padding exact values.** Requirement 2.2 states "approximately 100 KB / 10 KB." Should the defaults be exact byte constants (e.g. 102400 / 10240), and should they be exposed as named public constants in the module API?
