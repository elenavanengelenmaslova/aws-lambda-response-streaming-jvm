# Implementation Plan: Bedrock SSE Streaming Article

## Overview

The deliverable is a single prose artifact: `docs/bedrock-sse-streaming.md`. These
tasks author and verify that Markdown document section by section, following the
design's 11-section outline and its Verification Checklist. There is no Kotlin,
SAM, or script code to write — every task writes, edits, or inspects the article.

Content source-of-truth (no invented numbers): `streaming-bedrock-example/README.md`,
`streaming-sse/README.md`, and `docs/log.md`. `docs/article.md` must never be
modified. All repository source links are built on Pinned_Repo_Base
`https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/`.

## Tasks

- [x] 1. Create the article skeleton and series-framing intro
  - Create the new file `docs/bedrock-sse-streaming.md` (do NOT touch `docs/article.md`)
  - Lay out the full 11-section heading structure from the design outline (intro, demo+architecture, problem, architecture walkthrough, padding workaround, consuming the libraries, build/test/deploy/try-it, verification+results, server-side lessons, client-side lessons, closing)
  - Write the title and series-framing intro: part of a streaming series, follow-on to the S3-streaming example, and refer the reader to S3_Example for the shared setup (SnapStart + CRaC priming, SAM `RESPONSE_STREAM`, arm64, tiered compilation); keep this article's detail on the Bedrock/SSE parts
  - Use first-person, conversational, developer-to-developer voice with at least one second-person ("you") address
  - _Requirements: 1.1, 1.2, 1.3, 2.1, 2.2, 2.3_

- [x] 2. Embed the demo and architecture visuals
  - Embed the demo GIF `docs/bedrock-streaming-demo.gif` with descriptive alt text, placed before the detailed sections
  - Embed the architecture diagram `docs/SolutionDesign-Bedrock.png` (the Bedrock-specific diagram: same layout as `docs/SolutionDesign.png` but with Amazon Bedrock in place of the generic data source) with descriptive, Bedrock-specific alt text, placed before the detailed sections
  - This architecture diagram is a deferred placeholder that the Author adds later; it does not exist at authoring time. Still include a valid Markdown image embed with alt text now, so a missing-image render for `docs/SolutionDesign-Bedrock.png` is expected and acceptable
  - Use Markdown image syntax with same-directory relative paths (the article lives in `docs/`)
  - _Requirements: 3.1, 3.2, 3.3_

- [x] 3. Write the problem-statement section
  - Explain that the Lambda response-streaming runtime buffers small writes (~100 KB) below the language layer, and no application-level `flush()` overrides it
  - State explicitly that this approach uses no Lambda layers
  - State explicitly that this approach uses no custom runtime (managed `java25` runtime)
  - _Requirements: 8.1, 11.1, 11.2_

- [x] 4. Write the architecture walkthrough with named components
  - Describe the request flow in order: client → API Gateway REST (`RESPONSE_STREAM`) → Lambda `RequestStreamHandler` → Bedrock `InvokeModelWithResponseStream` → padded SSE relay → client
  - Name and describe the role of each component: `BedrockStreamingHandler`, `BedrockStreamHandler`, `PromptResolver`, `BedrockSource`, `PaddedSseWriter`, `PacedSseEmitter`, `ResponseWriter`, `Priming`
  - Source roles from `streaming-bedrock-example/README.md` and `streaming-sse/README.md`
  - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7_

- [x] 5. Write the padding-workaround section with attribution
  - Explain the tiered padding scheme: ~100 KB head padding on the first 3 events, ~10 KB tail padding on each event afterwards
  - Explain why SSE `:` comment lines are ignored by clients so the padding is invisible to the consumer
  - Explain the bandwidth-for-responsiveness trade-off, including the ~100×–1000× response-size inflation
  - Credit Builder_Center_Reference as having discovered the buffering behavior first, with its link, quoting no more than 30 consecutive words from it (paraphrase otherwise)
  - _Requirements: 10.1, 10.2, 10.3, 10.4_

- [x] 6. Write the consuming-the-libraries section
  - Explain consuming Streaming_Core's `ResponseWriter` (Gradle coordinate + import; writes the metadata prelude of status + headers before the first event; headers are `Map<String, String>`)
  - Explain consuming Streaming_SSE's `PaddedSseWriter` and `PacedSseEmitter` from package `nl.vintik.lambda.streaming.sse`, noting `streaming-sse` pulls `streaming-core` transitively via `api`; show `PaddedSseWriter()` default/explicit tiers, `writeEvent`/`writeEnd`, and `PacedSseEmitter(writer, delayMillis)`
  - Explain how Bedrock_Example wires them: `BedrockStreamHandler` resolves the prompt with `PromptResolver`, writes the prelude with `ResponseWriter`, pulls deltas from `BedrockSource`, and relays each through `PaddedSseWriter` (optionally paced by `PacedSseEmitter`)
  - _Requirements: 5.1, 5.2, 5.3_

- [x] 7. Write the build/test/validate/deploy/try-it section
  - Provide the exact build command and directory (`cd deployment/aws/sam-bedrock && ./build.sh`, `:streaming-bedrock-example:shadowJar`)
  - Provide the test command (`./gradlew :streaming-bedrock-example:test`) and optionally the coverage commands
  - Provide the SAM validate command (`sam validate --template-file deployment/aws/sam-bedrock/template.yaml`)
  - Provide the deploy steps (`cd deployment/aws/sam-bedrock && ./build.sh && ./deploy.sh`)
  - Provide the `curl -N` try-it that drops the padding client-side with the `sed`/`awk` pipeline
  - Reproduce all commands and directories exactly from `streaming-bedrock-example/README.md`
  - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_

- [x] 8. Write the verification + measured-results section
  - Reference `scripts/bedrock-streaming-test.sh` as the reproducible progressive-arrival / TTFB check, with its invocation
  - State that it is opt-in and guarded by `BEDROCK_TEST_ENABLED=true` because it invokes paid Bedrock
  - Reproduce the Measured_Results_Table verbatim from `streaming-bedrock-example/README.md`
  - State the eu-west-1 outcomes in prose: no-padding → single terminal burst; tiered 100 KB × 3 + 10 KB → TTFB near 0 ms across 9 distinct 500 ms buckets over ~4.4 s
  - _Requirements: 7.1, 7.2, 7.3, 7.4_

- [x] 9. Checkpoint - review content so far
  - Re-read sections 1–8 against the requirements they cite; confirm no number appears that is absent from a cited source file. Ask the user if questions arise.

- [x] 10. Write the server-side lessons section
  - Explain each trap with its failing symptom and the fix: naive `flush()` does not override the runtime buffer (buffer below the language layer); delay-alone-does-nothing (the earlier MockNest "dribble" attempt); `PacedSseEmitter` enforces padding-before-delay (non-`NO_DELAY` against an unpadded writer throws `IllegalArgumentException`); `OutputStream` flush-before-close to avoid a partial body on warm invocations; metadata headers must be `Map<String, String>` not `Map<String, List<String>>` (array form → 502); `STREAM` vs `RESPONSE_STREAM` naming trap
  - Keep the honest, mistakes-and-trade-offs voice; source from `docs/log.md` and the module READMEs
  - _Requirements: 8.2, 8.3, 8.4, 8.5, 8.6, 13.2_

- [x] 11. Write the client-side lessons section
  - Explain each with failing + corrected invocation where applicable: curl HTTP/2 error 92 → force `--http1.1`; dropping SSE padding via `sed -u -n 's/^data: //p' | awk '{printf "%s",$0; fflush()}'`; `curl -N` / `--no-buffer` required so curl itself doesn't buffer
  - Keep the honest, mistakes-and-trade-offs voice
  - _Requirements: 9.1, 9.2, 9.3, 13.2_

- [x] 12. Add pinned source links and write the closing
  - Add every repository source link built on Pinned_Repo_Base `.../blob/main/` for the named components, scripts, and module READMEs referenced throughout (Bedrock example files under `streaming-bedrock-example/src/main/kotlin/nl/vintik/lambda/bedrock/`; `PaddedSseWriter.kt`/`PacedSseEmitter.kt` under `streaming-sse`; `ResponseWriter.kt` under `streaming-core`)
  - Write the closing: recap and a pointer back to the series
  - _Requirements: 12.1, 12.2, 13.1, 13.2_

- [x] 13. Final voice/style and honesty pass
  - Read the whole article top to bottom; ensure first-person, conversational, developer-to-developer voice with at least one second-person address, matching the author's Medium articles
  - Ensure the piece is honest about mistakes and trade-offs rather than presenting only the final working solution
  - Tighten prose and transitions between sections
  - _Requirements: 13.1, 13.2_

- [x] 14. Verification pass against the design's Verification Checklist
  - Confirm the file exists at `docs/bedrock-sse-streaming.md` and `docs/article.md` is unchanged (git diff)
  - Confirm series framing + S3-example delegation for the four shared-setup items
  - Confirm both images are embedded before the detailed sections, each with alt text; the architecture diagram references `docs/SolutionDesign-Bedrock.png`, and the placeholder is acceptable (embed + alt text present even though the file is added later by the Author, so a missing-image render for that file is expected)
  - Confirm the request-flow chain is described in order and all named components appear with roles (Property 4)
  - Confirm `ResponseWriter`, `PaddedSseWriter`/`PacedSseEmitter` (with package), and the Bedrock wiring are explained
  - Confirm the build, test, `sam validate`, deploy, and `curl|sed|awk` commands are present and correct
  - Confirm the verification script is referenced, the `BEDROCK_TEST_ENABLED=true` guard is stated, the Measured_Results_Table is reproduced verbatim (Property 3), and the eu-west-1 outcomes are stated
  - Confirm all six server-side and all three client-side mistakes are explained with symptom/fix
  - Confirm padding tiers + trade-off + Builder Center credit/link, and that no quoted span exceeds 30 consecutive words (Property 2)
  - Confirm explicit "no Lambda layers" and "no custom runtime" statements
  - Confirm every repository source link is built on Pinned_Repo_Base and contains `/blob/main/` (Property 1)
  - Confirm no number appears that is not present in a cited source file, and that the Markdown renders cleanly
  - _Requirements: 1.1, 1.2, 3.1, 3.2, 4.1, 7.3, 7.4, 10.4, 12.1, 12.2_

## Notes

- This is a documentation artifact; "tasks" author and inspect prose, not code.
- The design has a Correctness Properties section, but its properties are
  inspection invariants on a prose document, not executable property-based tests,
  so they are verified in the final verification pass (task 14) rather than as
  separate test sub-tasks.
- Each task references the requirement clauses it satisfies for traceability.
- Content source-of-truth is `streaming-bedrock-example/README.md`,
  `streaming-sse/README.md`, and `docs/log.md` — no invented numbers.
- `docs/article.md` must remain untouched throughout.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1"] },
    { "id": 1, "tasks": ["2"] },
    { "id": 2, "tasks": ["3"] },
    { "id": 3, "tasks": ["4"] },
    { "id": 4, "tasks": ["5"] },
    { "id": 5, "tasks": ["6"] },
    { "id": 6, "tasks": ["7"] },
    { "id": 7, "tasks": ["8"] },
    { "id": 8, "tasks": ["10"] },
    { "id": 9, "tasks": ["11"] },
    { "id": 10, "tasks": ["12"] },
    { "id": 11, "tasks": ["13"] },
    { "id": 12, "tasks": ["14"] }
  ]
}
```
