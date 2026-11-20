# Design Document

## Overview

This design specifies the structure and content plan for a single documentation
artifact: the article `docs/bedrock-sse-streaming.md`. It is **not** a code
design — no Kotlin source, SAM template, or script changes. The article teaches a
reader to build and verify progressive Server-Sent Events (SSE) streaming of
Amazon Bedrock Nova Lite tokens from a Kotlin/JVM AWS Lambda behind an API Gateway
REST endpoint, using this repository's `streaming-core` and `streaming-sse`
libraries with the `streaming-bedrock-example` module as the running example.

The article is a follow-on to the existing S3-streaming example. It delegates the
shared cold-start and deployment setup (SnapStart + CRaC priming, SAM
`RESPONSE_STREAM`, arm64, tiered compilation) to the S3 example and concentrates
its own detail on the Bedrock- and SSE-specific mechanics: the Lambda
response-streaming buffer, the tiered SSE-comment padding workaround, the paced
emitter, and the server- and client-side traps that otherwise produce a single
terminal burst.

Because the deliverable is prose, this design defines the **section outline**,
the **content each section must carry**, the **assets and links** it references,
and a **Requirements Traceability** mapping so every acceptance criterion lands in
a named section. The "Correctness Properties" section below is reframed, as this
is a documentation artifact, into a verification checklist of inspectable
conditions on the finished article.

### Content-sourcing note (no invented numbers)

Every measured number, gotcha, and command in the article is lifted from existing
repository sources — nothing is invented:

- Measured TTFB/arrival table and eu-west-1 outcomes: `streaming-bedrock-example/README.md`.
- Padding tiers, constants, the delay-alone-does-nothing claim, the
  bandwidth-for-responsiveness trade-off, and the public API shapes:
  `streaming-sse/README.md`.
- Server/client gotchas (`STREAM` vs `RESPONSE_STREAM`, headers `Map<String,String>`
  vs `Map<String,List<String>>` → 502, `OutputStream` flush-before-close, curl
  HTTP/2 error 92 → `--http1.1`): `docs/log.md`.
- Build/test/validate/deploy/try-it commands: `streaming-bedrock-example/README.md`.

If a number is not present in one of those sources, the article must not state it.

## Architecture

### Document location and constraints

- Target file: `docs/bedrock-sse-streaming.md` (new file).
- `docs/article.md` is **not** touched (it is the separate S3 guide).
- Format: GitHub-flavored Markdown.
- Voice: first-person, conversational, developer-to-developer, honest about
  mistakes and trade-offs — matching the author's two Medium articles. At least
  one direct second-person ("you") address.

### Request flow the article explains

The article's architecture walkthrough follows this chain, naming each component
at its stage:

```
Client (curl -N)
   │  POST /stream  {"prompt": "...", "maxTokens": N}
   ▼
API Gateway REST  (ResponseTransferMode: RESPONSE_STREAM)
   ▼
Lambda  RequestStreamHandler  = BedrockStreamingHandler (no-arg entrypoint)
   ▼
BedrockStreamHandler  (orchestration)
   ├─ PromptResolver        parse + validate + clamp the request
   ├─ ResponseWriter        write streaming metadata prelude (status + headers)
   ├─ BedrockSource         InvokeModelWithResponseStream (Kotlin AWS SDK) → token deltas
   ├─ PaddedSseWriter       frame each delta as a padded SSE event (buffer workaround)
   └─ PacedSseEmitter       optional inter-chunk dribble on top of the writer
   ▼
padded SSE bytes → API Gateway → Client (sed|awk drops the padding)

Priming (CRaC beforeCheckpoint) warms this path during the SnapStart snapshot.
```

### Section outline (with requirement mapping)

The article is organized into the following sections. Each maps to the
requirement(s) it satisfies.

1. **Title + series-framing intro** — frames the piece as a follow-on in the
   streaming series; points the reader at the S3 example for shared setup.
   *(Req 2.1, 2.2, 2.3)*
2. **See it first: demo + architecture** — embeds `docs/bedrock-streaming-demo.gif`
   and `docs/SolutionDesign-Bedrock.png` *before* the detailed sections. The
   architecture diagram is a **deferred/placeholder asset the Author adds later**
   (same layout as `docs/SolutionDesign.png` but with Amazon Bedrock in place of
   the generic data source); it does not exist at authoring time, so the article
   still embeds a valid Markdown image reference with descriptive alt text even
   though the file may not yet exist. *(Req 3.1, 3.2)*
3. **The problem** — the Lambda response-streaming runtime buffers small writes
   (~100 KB) below the language layer; no application `flush()` overrides it; and
   this needs **no Lambda layers and no custom runtime** (managed `java25`).
   *(Req 8.1, 11.1, 11.2)*
4. **Architecture walkthrough** — the request flow above, naming every component
   and its role. *(Req 4.1–4.7)*
5. **The padding workaround + attribution** — tiered SSE-comment padding
   (~100 KB × 3 head, ~10 KB tail), why `:` comment lines are ignored by clients,
   the bandwidth-for-responsiveness trade-off (~100×–1000×), and credit to the
   Builder Center reference as the first discovery (with link, ≤ 30 consecutive
   words quoted). *(Req 10.1, 10.2, 10.3, 10.4)*
6. **Consuming the libraries** — depend on/import `streaming-core`'s
   `ResponseWriter`; use `streaming-sse`'s `PaddedSseWriter` / `PacedSseEmitter`
   from package `nl.vintik.lambda.streaming.sse`; how `Bedrock_Example` wires them
   together. *(Req 5.1, 5.2, 5.3)*
7. **Build, test, validate, deploy, try it** — exact commands and directories,
   plus the `curl | sed | awk` try-it that drops the padding client-side.
   *(Req 6.1–6.5)*
8. **Did it actually stream? Verification + measured results** — references
   `scripts/bedrock-streaming-test.sh`, the opt-in `BEDROCK_TEST_ENABLED=true`
   guard, the verbatim measured-results table, and the eu-west-1 outcomes.
   *(Req 7.1–7.4)*
9. **Lessons learned — server side** — naive `flush()` doesn't override the
   runtime buffer; delay-alone-does-nothing (MockNest dribble) and
   `PacedSseEmitter` enforcing padding-before-delay; `OutputStream` flush-before-
   close; headers `Map<String,String>` not `Map<String,List<String>>` → 502;
   `STREAM` vs `RESPONSE_STREAM` trap. Each with the failing symptom and the fix.
   *(Req 8.2, 8.3, 8.4, 8.5, 8.6, 13.2)*
10. **Lessons learned — client side** — curl HTTP/2 error 92 → `--http1.1`;
    dropping padding via `sed`/`awk`; `curl -N` / `--no-buffer`. Each with the
    failing and corrected invocation where applicable. *(Req 9.1, 9.2, 9.3, 13.2)*
11. **Closing** — recap and pointer back to the series. *(Req 13.1, 13.2)*

Pinned source links (Req 12.1, 12.2) and first-person honest voice (Req 13.1,
13.2) run throughout rather than living in one section.

## Components and Interfaces

"Components" here are the article's content units. Each unit lists what it must
contain and the source it draws from.

### Assets (embedded before detail — Section 2)

- `docs/bedrock-streaming-demo.gif` — embedded with descriptive alt text
  (e.g. "Bedrock Nova Lite tokens arriving progressively via curl").
- `docs/SolutionDesign-Bedrock.png` — embedded with descriptive, Bedrock-specific
  alt text (e.g. "Request flow: client → API Gateway REST (RESPONSE_STREAM) →
  Lambda RequestStreamHandler → Amazon Bedrock InvokeModelWithResponseStream →
  padded SSE relay"). **Note:** this file is a placeholder the Author adds later
  (same layout as `docs/SolutionDesign.png` but with Amazon Bedrock in place of the
  generic data source), so the embed may initially resolve to a missing image until
  the file is added.
- Embeds use Markdown image syntax with relative paths (the article lives in
  `docs/`, so the GIF/PNG are same-directory references).

### Named components to describe (Section 4)

Each component is named and given a one-to-two-sentence role, sourced from
`streaming-bedrock-example/README.md` and `streaming-sse/README.md`:

| Component | Role in the flow | Source file (pinned link) |
|---|---|---|
| `BedrockStreamingHandler` | No-arg Lambda entrypoint (`RequestStreamHandler`). | `streaming-bedrock-example/.../BedrockStreamingHandler.kt` |
| `BedrockStreamHandler` | Orchestration: resolve → write metadata → relay padded SSE. | `streaming-bedrock-example/.../BedrockStreamHandler.kt` |
| `PromptResolver` | Parses, validates, and clamps the prompt + cost/size knobs. | `streaming-bedrock-example/.../PromptResolver.kt` |
| `BedrockSource` | `TokenStreamSource`: `InvokeModelWithResponseStream` via Kotlin AWS SDK → token deltas. | `streaming-bedrock-example/.../BedrockSource.kt` |
| `PaddedSseWriter` | Tiered SSE-comment padding — the buffer workaround (lives in `streaming-sse`). | `streaming-sse/.../PaddedSseWriter.kt` |
| `PacedSseEmitter` | Optional inter-chunk dribble on top of the writer; enforces padding-before-delay. | `streaming-sse/.../PacedSseEmitter.kt` |
| `ResponseWriter` | `streaming-core`'s API Gateway streaming wire protocol (metadata prelude). | `streaming-core/.../ResponseWriter.kt` |
| `Priming` | CRaC `beforeCheckpoint` warm-up for SnapStart. | `streaming-bedrock-example/.../Priming.kt` |

`TokenStreamSource.kt` is also referenced as the streaming-source contract that
`BedrockSource` implements.

### Pinned source-link strategy (Req 12)

- **Pinned_Repo_Base:** `https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/`
- Every repository source link is built on this base and therefore pinned to the
  `main` branch (contains `/blob/main/`).
- Bedrock example files resolve under
  `streaming-bedrock-example/src/main/kotlin/nl/vintik/lambda/bedrock/`:
  `BedrockSource.kt`, `PromptResolver.kt`, `TokenStreamSource.kt`,
  `BedrockStreamHandler.kt`, `BedrockStreamingHandler.kt`, `Priming.kt`,
  `PromptRequest.kt`.
- `PaddedSseWriter.kt` and `PacedSseEmitter.kt` link into the **`streaming-sse`**
  module (package `nl.vintik.lambda.streaming.sse`), not the bedrock example.
- `ResponseWriter.kt` links into the **`streaming-core`** module.
- Script/README links (`scripts/bedrock-streaming-test.sh`, module READMEs) also
  use the pinned base.

### Consuming-the-libraries content (Section 6)

- **`streaming-core` / `ResponseWriter`** — show the Gradle dependency coordinate
  and import; `ResponseWriter` writes the metadata prelude (status + headers) before
  the first SSE event. Note headers must be `Map<String, String>` (see lessons).
- **`streaming-sse` / `PaddedSseWriter` + `PacedSseEmitter`** — package
  `nl.vintik.lambda.streaming.sse`; because `streaming-sse` declares `streaming-core`
  as `api`, depending on `streaming-sse` pulls `streaming-core` transitively. Show
  `PaddedSseWriter()` (default tiers) / explicit tiers, `writeEvent`/`writeEnd`, and
  `PacedSseEmitter(writer, delayMillis)`.
- **How `Bedrock_Example` wires them** — `BedrockStreamHandler` resolves the prompt
  with `PromptResolver`, writes the prelude with `ResponseWriter`, pulls token deltas
  from `BedrockSource`, and relays each through `PaddedSseWriter` (optionally paced by
  `PacedSseEmitter`).

### Build / test / validate / deploy / try-it commands (Section 7)

Reproduced from `streaming-bedrock-example/README.md`, exact commands and dirs:

- Build (fat jar): `cd deployment/aws/sam-bedrock && ./build.sh`
  (`:streaming-bedrock-example:shadowJar`).
- Tests: `./gradlew :streaming-bedrock-example:test`.
- Coverage (optional mention): `./gradlew :streaming-bedrock-example:koverHtmlReport :streaming-bedrock-example:koverVerify`.
- Validate SAM: `sam validate --template-file deployment/aws/sam-bedrock/template.yaml`.
- Deploy: `cd deployment/aws/sam-bedrock && ./build.sh && ./deploy.sh`.
- Try it (drops padding client-side):
  ```bash
  curl -N -H "x-api-key: $API_KEY" -H "Content-Type: application/json" \
    "${ENDPOINT_URL}stream" -d '{"prompt":"...","maxTokens":1000}' \
    | sed -u -n 's/^data: //p' | awk '{printf "%s",$0; fflush()}'
  ```

### Verification + measured results (Section 8)

- Reference `scripts/bedrock-streaming-test.sh` as the reproducible post-deploy
  progressive-arrival / TTFB check.
- State it is opt-in and guarded by `BEDROCK_TEST_ENABLED=true` because it invokes
  **paid** Bedrock; command:
  `BEDROCK_TEST_ENABLED=true AWS_REGION=eu-west-1 ./scripts/bedrock-streaming-test.sh`.
- **Measured_Results_Table — reproduced verbatim** from
  `streaming-bedrock-example/README.md`:

  | Configuration | TTFB | Arrival | Verdict |
  |---|---|---|---|
  | No padding | ≈ total | 1 time bucket | single terminal burst |
  | Tiered 100 KB × 3 + 10 KB | ~0 ms | 9 distinct 500 ms buckets over ~4.4 s | **progressive** |

- State the eu-west-1 outcomes in prose: no-padding → single terminal burst;
  tiered 100 KB × 3 + 10 KB → TTFB near 0 ms across 9 distinct 500 ms buckets over
  ~4.4 s.

### Lessons-learned content (Sections 9 and 10)

Server-side (Section 9), each with failing symptom + fix, sourced from `docs/log.md`
and the module READMEs:

1. Naive `flush()` does not override the runtime buffer — the buffer lives below
   the language layer (Req 8.1; also framed in Section 3's problem statement).
2. Delay alone does nothing — the earlier MockNest "dribble" attempt; delayed small
   writes still coalesce and burst (Req 8.2).
3. `PacedSseEmitter` enforces padding-before-delay — constructing a non-`NO_DELAY`
   delay against a writer with no padding throws `IllegalArgumentException` (Req 8.3).
4. `OutputStream` must flush-before-close — otherwise warm invocations deliver a
   partial body (`curl: (18) transfer closed ...`); fix is `output.use { }` + explicit
   `flush()` before close (Req 8.4).
5. Metadata headers must be `Map<String, String>`, not `Map<String, List<String>>`
   — the array form is valid JSON but makes API Gateway return 502 (Req 8.5).
6. `STREAM` vs `RESPONSE_STREAM` — SAM `Api` uses `RESPONSE_STREAM`; the lower-level
   integration value is `STREAM` with a `/response-streaming-invocations` URI suffix;
   not interchangeable (Req 8.6).

Client-side (Section 10):

1. curl HTTP/2 error 92 (`curl: (92) HTTP/2 stream ... INTERNAL_ERROR`) → force
   `--http1.1` (Req 9.1) — show failing and corrected invocation.
2. Drop the SSE padding with `sed -u -n 's/^data: //p' | awk '{printf "%s",$0; fflush()}'`
   (Req 9.2).
3. `curl -N` / `--no-buffer` is required so curl itself doesn't buffer (Req 9.3).

## Data Models

Not applicable — the deliverable is a single Markdown document. The only
structured data the article reproduces is the Measured_Results_Table (above),
which must match its source byte-for-byte (modulo the relative-link path to the
script).

## Error Handling

Authoring-time failure modes and how the design avoids them:

- **Inventing numbers** → every number is cross-checked against the cited source
  file; if absent there, it is not stated.
- **Over-quoting the Builder Center reference** → paraphrase; keep any quoted span
  under 30 consecutive words; attribute with the exact URL.
- **Unpinned links** → all repository links are built from Pinned_Repo_Base and
  verified to contain `/blob/main/`.
- **Modifying the wrong file** → only `docs/bedrock-sse-streaming.md` is created;
  `docs/article.md` is left unchanged.
- **Re-documenting shared setup** → shared cold-start/deploy items are referenced
  to the S3 example rather than re-explained.
- **Broken asset embeds** → GIF/PNG use correct same-directory relative paths and
  include alt text. The architecture diagram `docs/SolutionDesign-Bedrock.png` is an
  **intentionally deferred placeholder** the Author adds later — a missing-image
  render for *that* file is expected until it is added and must not be treated as a
  defect.

## Testing Strategy

This is a documentation artifact, so "testing" is content verification by
inspection rather than executable unit or property tests. There is no code under
test, so property-based testing does not apply (per the prework, no acceptance
criterion describes input-varying code behavior).

- **Structural / SMOKE checks** — file exists at `docs/bedrock-sse-streaming.md`;
  `docs/article.md` unchanged (git diff); valid Markdown. Covers Req 1.
- **Presence checks** — grep/inspection that each required content unit, command,
  component name, statement, and asset embed is present. Covers Req 2–6, 8–11, 13.
- **Exact-match checks** — the Measured_Results_Table and eu-west-1 numbers match
  `streaming-bedrock-example/README.md` byte-for-byte (Property 3). Covers Req 7.
- **Invariant checks (grep/inspection over the whole document)** — every repo link
  is pinned to `/blob/main/` (Property 1); no Builder Center quote exceeds 30
  consecutive words (Property 2). Covers Req 10.4, 12.
- **Source cross-check** — no number appears that is absent from a cited source
  file (`streaming-bedrock-example/README.md`, `streaming-sse/README.md`, `docs/log.md`).

The Verification Checklist at the end of this document is the operational form of
this strategy.

## Correctness Properties

*A property is a characteristic that should hold across all valid instances of an
artifact. This deliverable is a documentation artifact, so its "properties" are
inspectable invariants and presence/exact-match conditions on the finished article
rather than executable property-based tests. Per the prework, no acceptance
criterion describes input-varying code behavior; the two criteria with a
universal-quantification shape (10.4 and 12.2) are recorded here as invariants
verified by inspection/grep over the whole document.*

### Property 1: Every repository source link is pinned to `main`

*For all* repository source links in the article, the URL is built on
Pinned_Repo_Base and contains `/blob/main/`.

**Validates: Requirements 12.1, 12.2**

### Property 2: No quoted span from the Builder Center reference exceeds 30 words

*For any* run of consecutive words in the article that is attributable to the
Builder Center reference, the run contains no more than 30 consecutive words.

**Validates: Requirements 10.4**

### Property 3: The measured-results table matches its source

*For all* rows of the Measured_Results_Table in the article, the row is identical
to the corresponding row in `streaming-bedrock-example/README.md` (configuration,
TTFB, arrival, verdict).

**Validates: Requirements 7.3, 7.4**

### Property 4: Every named component appears with a role description

*For all* components in {`PromptResolver`, `BedrockSource`, `PaddedSseWriter`,
`PacedSseEmitter`, `BedrockStreamHandler`, `ResponseWriter`, `Priming`}, the
article names the component and describes its role in the request flow.

**Validates: Requirements 4.2, 4.3, 4.4, 4.5, 4.6, 4.7**

## Requirements Traceability

| Requirement | Acceptance criteria | Section / content unit | Verification |
|---|---|---|---|
| 1 Standalone file | 1.1, 1.2, 1.3 | File `docs/bedrock-sse-streaming.md` | File exists at path; `docs/article.md` unchanged (git diff); valid Markdown |
| 2 Series framing | 2.1, 2.2, 2.3 | §1 Intro | Series/follow-on language; references S3 example for SnapStart+CRaC, RESPONSE_STREAM, arm64, tiered compilation; detail stays on Bedrock/SSE |
| 3 Visuals | 3.1, 3.2 | §2 Demo + architecture | GIF `docs/bedrock-streaming-demo.gif` and PNG `docs/SolutionDesign-Bedrock.png` embedded before detail, with alt text; the diagram is a deferred-placeholder asset the Author adds later (embed + alt text present even if the file is not yet there) |
| 4 Architecture + components | 4.1–4.7 | §4 Walkthrough | Ordered flow present; Property 4 (all 7 components named + role) |
| 5 Consuming libraries | 5.1, 5.2, 5.3 | §6 Consuming the libraries | `ResponseWriter` consumption; `PaddedSseWriter`/`PacedSseEmitter` (pkg `nl.vintik.lambda.streaming.sse`); Bedrock example wiring |
| 6 Build/test/deploy/try-it | 6.1–6.5 | §7 Commands | Build, test, `sam validate`, deploy steps verbatim; `curl\|sed\|awk` try-it present |
| 7 Verification + results | 7.1–7.4 | §8 Verification | Script referenced; `BEDROCK_TEST_ENABLED=true` guard + paid rationale; Property 3 (verbatim table); eu-west-1 outcomes |
| 8 Server-side mistakes | 8.1–8.6 | §3 + §9 | flush-doesn't-override; delay-alone/MockNest; emitter enforces padding; flush-before-close; headers `Map<String,String>`→502; `STREAM` vs `RESPONSE_STREAM` |
| 9 Client-side mistakes | 9.1, 9.2, 9.3 | §10 | HTTP/2 error 92 → `--http1.1`; `sed`/`awk` padding drop; `curl -N`/`--no-buffer` |
| 10 Padding + attribution | 10.1, 10.2, 10.3, 10.4 | §5 | Tiers 100 KB×3 + 10 KB; ~100×–1000× trade-off; Builder Center credit + link; Property 2 (≤30-word quote) |
| 11 No layers/runtime | 11.1, 11.2 | §3 Problem | Explicit "no Lambda layers"; explicit "no custom runtime" (managed `java25`) |
| 12 Pinned links | 12.1, 12.2 | Throughout | Property 1 (all repo links on Pinned_Repo_Base, `/blob/main/`) |
| 13 Voice + honesty | 13.1, 13.2 | Throughout (§1, §9, §10, §11) | First-person, ≥1 second-person address, conversational; mistakes/trade-offs shown, not only the final solution |

## Verification Checklist (for the finished article)

- [ ] File created at `docs/bedrock-sse-streaming.md`; `docs/article.md` untouched (Req 1).
- [ ] Series framing + S3-example delegation for the four shared-setup items (Req 2).
- [ ] GIF and PNG (`docs/SolutionDesign-Bedrock.png`) embedded before the detailed sections, each with alt text; the diagram placeholder is acceptable — embed + alt text present even though the file is added later (Req 3).
- [ ] Request-flow chain described in order; all 7 components named with roles — Property 4 (Req 4).
- [ ] `ResponseWriter`, `PaddedSseWriter`/`PacedSseEmitter` (with package), and Bedrock wiring explained (Req 5).
- [ ] Build, test, `sam validate`, deploy, and `curl|sed|awk` commands present and correct (Req 6).
- [ ] Verification script referenced; `BEDROCK_TEST_ENABLED=true` guard stated; table verbatim — Property 3; eu-west-1 outcomes stated (Req 7).
- [ ] All six server-side mistakes explained with symptom + fix (Req 8).
- [ ] All three client-side mistakes explained with failing/corrected invocation (Req 9).
- [ ] Padding tiers + trade-off + Builder Center credit/link; ≤30-word quote — Property 2 (Req 10).
- [ ] Explicit "no Lambda layers" and "no custom runtime" statements (Req 11).
- [ ] Every repo source link on Pinned_Repo_Base and pinned to `main` — Property 1 (Req 12).
- [ ] First-person + ≥1 second-person voice; honest about mistakes/trade-offs (Req 13).
- [ ] No number appears that is not present in a cited source file.
