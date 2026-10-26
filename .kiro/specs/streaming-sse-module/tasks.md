# Implementation Plan: streaming-sse module

## Overview

Promote the proven SSE padding/dribble workaround out of `streaming-bedrock-example` into a new, reusable `streaming-sse` Gradle module (package `nl.vintik.lambda.streaming.sse`), mirroring `streaming-core`'s build, publishing, coverage, and binary-compatibility conventions. The implementation proceeds test-driven: scaffold the module, promote `PaddedSseWriter` byte-for-byte (adding named default constants, a no-arg constructor, and `hasPadding()`), add the new `PacedSseEmitter` with an injected sleeper, pin the behaviour with unit and property-based tests, lock the public API and coverage gates, then refactor the Bedrock example to consume the module and complete the documentation. Deployed-endpoint proof (Requirement 5.6/5.7) can only run against real AWS and is captured as an optional, non-coding task.

## Tasks

- [x] 1. Scaffold the `streaming-sse` Gradle module
  - [x] 1.1 Create `streaming-sse/build.gradle.kts` mirroring `streaming-core`
    - Copy the plugin block (versionless `kotlin("jvm")`, `kotlin("plugin.serialization")`, `id("org.jetbrains.kotlinx.kover")`; module-local `alias(libs.plugins.maven.publish)`, `alias(libs.plugins.bcv)`)
    - Set `group = "nl.vintik"` and `version = providers.gradleProperty("releaseVersion").getOrElse("2.0.0-SNAPSHOT")` (same git-tag property as core)
    - Declare `api(project(":streaming-core"))` as the only non-test project dependency; add test deps `libs.junit.jupiter`, `libs.mockk`, `testRuntimeOnly(libs.junit.platform.launcher)`
    - Configure Java toolchain 21 + `JvmTarget.JVM_21`, `kotlin { explicitApi() }`, the `tasks.test { useJUnitPlatform { excludeTags(...) }; systemProperty("net.bytebuddy.experimental", "true") }` block, and `kover { reports { verify { rule { bound { minValue = 90 } } } } }`
    - Configure `mavenPublishing { publishToMavenCentral(); signAllPublications(); coordinates("nl.vintik", "aws-lambda-streaming-sse", version.toString()); pom { ... } }` with the distinct artifact id
    - _Requirements: 1.1, 1.2, 1.5, 1.6, 1.7, 1.12, 1.13_

  - [x] 1.2 Register the module and wire root aggregation
    - Add `include("streaming-sse")` to `settings.gradle.kts`
    - Add `kover(project(":streaming-sse"))` to the root `build.gradle.kts` aggregation and add `:streaming-sse` to `validationReports` / the `koverXmlReport` dependsOn list
    - Create the `streaming-sse/src/main/kotlin/nl/vintik/lambda/streaming/sse/` and `streaming-sse/src/test/kotlin/nl/vintik/lambda/streaming/sse/` package directories
    - _Requirements: 1.11_

- [x] 2. Promote `PaddedSseWriter` into the new module
  - [x] 2.1 Create `PaddedSseWriter` in `nl.vintik.lambda.streaming.sse`
    - Promote the writer byte-for-byte from `nl.vintik.lambda.bedrock.PaddedSseWriter` (only the package changes); keep `writeEvent`, `writeEnd`, `eventsWritten`, tiered-pad logic, per-event flush, and the exact terminal bytes `event: end\ndata: [DONE]\n\n`
    - Add the companion constants `DEFAULT_HEAD_PAD_BYTES = 102_400`, `DEFAULT_TAIL_PAD_BYTES = 10_240`, `DEFAULT_HEAD_PAD_EVENTS = 3`
    - Add the no-arg convenience constructor applying those three defaults
    - Add `hasPadding(): Boolean` (true when `headPadBytes > 0 || tailPadBytes > 0`), keeping tier fields `private`
    - Keep construction rejecting any negative tier value via `require` → `IllegalArgumentException`
    - All public declarations under `explicitApi()` (explicit visibility + return type)
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9, 2.10, 2.11, 2.12_

  - [x] 2.2 Move and extend `PaddedSseWriterTest` into the module
    - Move `PaddedSseWriterTest` to `streaming-sse/src/test/kotlin/nl/vintik/lambda/streaming/sse/`, bringing the `CountingFlushStream` helper; update the package/import
    - Add example/edge-case unit tests: one `data:` line emitted and flushed exactly once; head tier produces larger events than tail tier; zero padding omits the comment line; `writeEnd` emits the exact terminal bytes; negative construction params rejected; no-arg constructor applies the three default constants; `hasPadding()` true/false cases
    - _Requirements: 2.2, 2.5, 2.6, 2.7, 2.8, 2.10, 2.11, 2.12_

  - [x] 2.3 Write property test — payload round-trips through the padded stream (excluding terminal)
    - Implement with `@ParameterizedTest` + `@MethodSource`; corpus of 10–20 diverse cases (1–20 events, payloads 0 bytes up to a few KB) including a leading `:`, literal `data:`, embedded newline, multi-byte UTF-8, empty-payload, and empty-sequence cases; assert `sed -n 's/^data: //p'` of output excluding the terminal equals the concatenation of original texts byte-identically
    - **Property 1: Payload round-trips through the padded stream (excluding terminal)**
    - **Validates: Requirements 4.1, 4.4, 4.5, 4.6**

  - [x] 2.4 Write property test — full-stream round-trip adds exactly the `[DONE]` sentinel
    - Same corpus; assert the filter over the full stream (including terminal) yields the concatenation followed by exactly one trailing `[DONE]` line and nothing else
    - **Property 2: Full-stream round-trip adds exactly the `[DONE]` sentinel**
    - **Validates: Requirements 4.2, 4.7**

  - [x] 2.5 Write property test — padding lives only in comment lines
    - For any event and any tier, assert every padding byte appears only within a `: ` comment line and no `data:`/filter-visible line is affected by padding
    - **Property 3: Padding lives only in comment lines**
    - **Validates: Requirements 4.3, 4.7**

  - [x] 2.6 Write property test — tiered padding crosses the buffer threshold
    - For `headPadBytes >= 100000`, assert each of the first `headPadEvents` events exceeds ~100 KB total; for `tailPadBytes > 0`, assert each subsequent event carries at least `tailPadBytes` padding
    - **Property 4: Tiered padding crosses the buffer threshold**
    - **Validates: Requirements 2.3, 2.4, 2.6**

  - [x] 2.7 Write property test — returned byte count equals bytes written
    - For any text and tier, assert `writeEvent`'s return equals the exact UTF-8 byte count written for that event (data line + optional padding comment)
    - **Property 5: Returned byte count equals bytes written**
    - **Validates: Requirements 2.9**

- [x] 3. Checkpoint - Ensure all tests pass
  - Ensure all `streaming-sse` writer tests pass, ask the user if questions arise.

- [x] 4. Implement `PacedSseEmitter`
  - [x] 4.1 Create `PacedSseEmitter` in `nl.vintik.lambda.streaming.sse`
    - Constructor `(writer: PaddedSseWriter, delayMillis: Int = NO_DELAY, sleep: (Long) -> Unit = Thread::sleep)`; companion constants `NO_DELAY = 0`, `MIN_DELAY_MILLIS = 1`, `MAX_DELAY_MILLIS = 5000`
    - Construction-time `require` validation: reject delay > 5000; reject delay < 1 that is not `NO_DELAY`; reject a non-`NO_DELAY` delay when `!writer.hasPadding()` with a message explaining delay alone cannot cross the runtime buffer — all via `IllegalArgumentException`, nothing retained on failure
    - `emit(output, text): Int` — "sleep before every event except the first" boundary semantics (sleep only when a delay is configured and index > 0), write+flush via the writer before any pause, return the writer's byte count; never sleep before the first event
    - `end(output)` — delegate to `writer.writeEnd`, never apply a trailing delay; `eventsWritten(): Int` delegating to the writer
    - All public declarations under `explicitApi()`
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6_

  - [x] 4.2 Write unit tests for `PacedSseEmitter` validation and boundaries
    - Assert `assertThrows(IllegalArgumentException::class.java)` for delay > 5000, delay < 1 (non-sentinel), and delay-with-zero-padding; assert `end` never sleeps and `NO_DELAY` never sleeps, using a recording `(Long) -> Unit` sleeper
    - _Requirements: 3.2, 3.3, 3.4, 3.6_

  - [x] 4.3 Write property test — pacing sleeps once per inter-event gap, never at boundaries
    - With an injected recording sleeper (zero wall-clock delay), for any delay in `[1, 5000]` and any event count n, assert emitting n events then `end` invokes the sleeper exactly n−1 times, each with the configured delay, none before the first event and none on `end`
    - **Property 6: Pacing sleeps once per inter-event gap and never at the boundaries**
    - **Validates: Requirements 3.1, 3.5**

  - [x] 4.4 Write property test — no pacing means no added pause
    - For any event sequence emitted with `delayMillis == NO_DELAY`, assert the injected sleeper is never invoked
    - **Property 7: No pacing means no added pause**
    - **Validates: Requirements 3.2**

- [x] 5. Checkpoint - Ensure all tests pass
  - Ensure all `streaming-sse` writer and emitter tests pass, ask the user if questions arise.

- [x] 6. Lock the public API and coverage gates
  - [x] 6.1 Generate and commit the binary-compatibility API dump
    - Run `apiDump` to produce `streaming-sse/api/streaming-sse.api`; commit it so `apiCheck` passes against the public surface in Components and Interfaces
    - Confirm `apiCheck` reports no difference and `koverVerify` passes the 90% line-coverage bound for the module
    - _Requirements: 1.8, 1.9, 1.10_

  - [x] 6.2 Assert no AWS coordinates on the module classpath
    - Using the root `resolvedCoordinates` report for `:streaming-sse`, assert the compile/runtime classpaths contain no coordinate whose group begins with `aws`, `software.amazon`, or `com.amazonaws`
    - _Requirements: 1.4_

  - [x] 6.3 Assert `streaming-core` resolves transitively
    - Assert the resolved `:streaming-sse` classpath includes `streaming-core` (and its transitive `kotlinx-serialization-json`) via the api-scoped dependency without an explicit declaration
    - _Requirements: 1.1, 1.3_

- [x] 7. Refactor `streaming-bedrock-example` to consume the module
  - [x] 7.1 Add the dependency and switch imports
    - Add `implementation(project(":streaming-sse"))` to `streaming-bedrock-example/build.gradle.kts`
    - Change `BedrockStreamHandler` and `Priming` to import `nl.vintik.lambda.streaming.sse.PaddedSseWriter` instead of `nl.vintik.lambda.bedrock.PaddedSseWriter`, with no behaviour change
    - _Requirements: 5.1, 5.2_

  - [x] 7.2 Delete the local writer and its test
    - Delete `streaming-bedrock-example/src/main/kotlin/nl/vintik/lambda/bedrock/PaddedSseWriter.kt` and `streaming-bedrock-example/src/test/kotlin/nl/vintik/lambda/bedrock/PaddedSseWriterTest.kt`
    - Verify no source references to `nl.vintik.lambda.bedrock.PaddedSseWriter` remain (grep) and `./gradlew :streaming-bedrock-example:build` compiles and passes its tests
    - _Requirements: 5.3, 5.4, 5.5_

- [x] 8. Documentation and knowledge capture
  - [x] 8.1 Write the module README
    - Create `streaming-sse/README.md` covering: the runtime internal-buffer cause (6.1); the tiered-padding + optional dribble workaround (6.2); the verifiable claim that delay alone does not cross the buffer and works only with padding (6.3); the ~100×–1000× bandwidth trade-off (6.4); a resolvable link to the AWS Builder Center article (6.5)
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_

  - [x] 8.2 Append the build-log entries
    - Append to `docs/log.md`: the runtime-buffer finding (small writes held below the language layer, released only on fill/end) (7.1); the tiered-padding workaround making delivery progressive (7.2); that delay-without-padding yields a terminal burst (7.3); and the padding bandwidth trade-off (7.4)
    - _Requirements: 7.1, 7.2, 7.3, 7.4_

- [ ] 9. Final checkpoint - Ensure all tests pass
  - Ensure the full `./gradlew build` (all modules), `apiCheck`, and `koverVerify` pass, ask the user if questions arise.

- [ ] 10. Deployed-endpoint streaming proof (manual, real AWS only)
  - Run the existing `Post_Deploy_Test` against the deployed Bedrock endpoint to confirm the first response chunk arrives before the full response completes (5.6) and the content is byte-identical to the pre-refactor output (5.7). No local emulator implements the streaming runtime's buffering, so this can only be proven against real AWS and is not a coding task — run it manually after deploy.
  - _Requirements: 5.6, 5.7_

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP (tests, classpath assertions, and the manual deployed-endpoint proof).
- Each task references specific requirement sub-clauses for traceability.
- Property tests use `@ParameterizedTest` + `@MethodSource`/`@ValueSource` (the repo's PBT style), with a recording sleeper for pacing properties so the suite has zero wall-clock delay.
- The byte-identical guarantee (Requirement 5.7) holds by construction because `PaddedSseWriter` is promoted unchanged; the deployed proof in task 10 confirms it end to end.
- Task 10 is intentionally non-coding and optional: it is the only way to prove Requirement 5.6/5.7 and requires a real AWS deploy.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2"] },
    { "id": 2, "tasks": ["2.1"] },
    { "id": 3, "tasks": ["2.2", "2.3", "2.4", "2.5", "2.6", "2.7"] },
    { "id": 4, "tasks": ["4.1"] },
    { "id": 5, "tasks": ["4.2", "4.3", "4.4"] },
    { "id": 6, "tasks": ["6.1", "6.2", "6.3"] },
    { "id": 7, "tasks": ["7.1"] },
    { "id": 8, "tasks": ["7.2"] },
    { "id": 9, "tasks": ["8.1", "8.2", "10"] }
  ]
}
```
