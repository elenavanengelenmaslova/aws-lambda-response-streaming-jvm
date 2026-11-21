# Development Log

A running log of hiccups, bugs, gotchas, and fixes encountered while building the
S3 file-streaming endpoint.

> **No runtime secrets.** This log never contains credentials, access tokens,
> authorization header values, or resource identifiers used as secrets. Where a
> development detail would otherwise expose such a value, it is recorded by a
> descriptive name or placeholder (e.g. `<BUCKET_NAME>`, `<API_ENDPOINT>`).

Each entry uses the form:

- **Title** — a short summary of the issue.
- **Symptom / trigger** — what was observed and what caused it.
- **Resolution / status** — the fix, or the current state if still open.

---

## `STREAM` vs `RESPONSE_STREAM` — the SAM/integration naming trap

- **Symptom / trigger:** Enabling Lambda response streaming through API Gateway
  hinges on a transfer-mode value, and there are two different names for two
  different layers. At the SAM `Api` event level the property is
  `ResponseTransferMode: RESPONSE_STREAM`. At the lower-level API Gateway
  integration the equivalent value is `STREAM`, and that path additionally
  requires the integration URI to carry the `/response-streaming-invocations`
  suffix. The two names are **not interchangeable**: using `STREAM` where SAM
  expects `RESPONSE_STREAM` (or vice versa), or setting the right name at the
  wrong layer, silently produces a non-streaming (buffered) endpoint. The failure
  doesn't surface until a deploy completes and the response comes back buffered —
  a full, slow deploy cycle wasted each time.

- **Resolution / status:** **Resolved (convention fixed).** In the SAM template,
  set `ResponseTransferMode` on the `Api` event to the exact value
  `RESPONSE_STREAM`. Treat any other value — including the lower-level integration
  value `STREAM` — as invalid for response streaming, and do not commit it
  (Req 8.2, 8.3). After every template change, run
  `sam validate --template-file deployment/aws/sam/template.yaml` and require
  exit code 0 before committing (Req 8.4, 8.5). Only the `RESPONSE_STREAM` form is
  used at the SAM `Api` level for this project; the `STREAM` + integration-URI
  suffix form is documented here only so the distinction is unmistakable.

---

## Open item: how the content-type marker is conveyed on the JVM `RequestStreamHandler` path

- **Symptom / trigger:** The Node.js streaming helper
  `awslambda.HttpResponseStream.from(underlyingStream, prelude)` marks the stream
  as carrying the metadata-prelude protocol by calling
  `underlyingStream.setContentType("application/vnd.awslambda.http-integration-response")`.
  On the JVM, the `RequestStreamHandler` path hands the handler a **raw
  `OutputStream`** that has no `setContentType` method, while API Gateway invokes
  the function with `ResponseTransferMode: RESPONSE_STREAM`. It is currently a
  **known unknown** whether that content-type marker still needs to be conveyed on
  this path, and if so, how. Two possibilities: (a) the streaming invocation path
  applies the marker automatically and the handler only needs to write
  `metadata JSON → 8 null bytes → body`; or (b) the marker must be emitted some
  other way (e.g. as a header inside the metadata prelude). Getting this wrong
  risks the prelude being treated as body, or headers/status not being honored.

- **Resolution / status:** **OPEN — to be verified against a real deployment.**
  The wire format itself is settled and ported from the Node.js helper: write the
  metadata JSON document, then exactly 8 null bytes (`ByteArray(8)`) as the
  delimiter, then the body bytes, with no NUL bytes inside the prelude JSON. What
  remains unverified is the content-type marker on the JVM streaming path. The
  proving ground is the post-deploy first-byte check (Req 9.3) against the deployed
  `<API_ENDPOINT>`: if the first byte arrives well before completion and the body
  is byte-identical, progressive streaming is confirmed and the marker is being
  handled correctly by whichever mechanism applies. Once verified, update this
  entry with the concrete finding (automatic vs. explicit, and the exact mechanism).
  Until then, treat this as the most likely deploy-cycle trap after the
  `STREAM` vs `RESPONSE_STREAM` distinction above.

---

## Build checkpoint: bleeding-edge toolchain (Gradle 9.0.0 + Kotlin 2.3.0 + Java 25) configures and builds clean

- **Symptom / trigger:** Task 1.2 checkpoint — first `./gradlew clean test` after the
  single-module project setup. The concern was that the combination of Gradle
  `9.0.0`, the Kotlin `2.3.0` plugin (with `kotlin("plugin.serialization")`),
  `com.gradleup.shadow` `9.0.2`, Kover `0.9.1`, the Java 25 toolchain
  (`JavaLanguageVersion.of(25)` / `jvmTarget = JVM_25`), and the declared
  dependency set might hit a version-resolution or plugin-compatibility wall, since
  this is a very new stack.

- **Resolution / status:** **Resolved — no changes needed.** `./gradlew clean test`
  returns exit code 0: the build configures, all dependencies resolve from Maven
  Central, and main sources compile. A `./gradlew compileKotlin --rerun-tasks`
  (cache-bypassing) run also succeeds, confirming the resolve+compile path is clean
  and not just a cache hit. `:test` reports `NO-SOURCE` (expected — no tests exist
  yet at this checkpoint). The foojay toolchain resolver in `settings.gradle.kts`
  provisions Java 25 automatically.
  - **Gotcha to be aware of (non-blocking):** Gradle 9 on a modern JDK emits
    `WARNING: A restricted method in java.lang.System has been called ... Restricted
    methods will be blocked in a future release unless native access is enabled`
    from `native-platform`. It is a warning only and does not affect the build; a
    future Gradle/JDK pairing may require `--enable-native-access=ALL-UNNAMED`.

---

## MockK can't mock final Kotlin classes on Java 25 — `net.bytebuddy.experimental`

- **Symptom / trigger:** `StreamHandlerTest` mocks the handler's concrete
  collaborators (`RequestParser`, `FileNameValidator`, `S3Source`,
  `ResponseWriter`). Every test failed with
  `io.mockk.MockKException: Missing mocked calls inside every { ... } block`.
  MockK's `every`/`coEvery` recorded zero calls because the mocks weren't
  intercepting. Tests that mock *interfaces* (`S3Client`, `Context`) were fine —
  only final concrete classes broke. Cause: MockK instruments final classes via
  byte-buddy class redefinition, and the byte-buddy bundled with MockK 1.14.5
  refuses the Java 25 class-file version unless experimental mode is enabled.
- **Resolution / status:** Resolved. Added
  `systemProperty("net.bytebuddy.experimental", "true")` to `tasks.test` in
  `build.gradle.kts`, so byte-buddy accepts the newer class-file version and
  mocking of final classes works on the Java 25 toolchain.

---

## JUnit 6 rejects Kotlin lifecycle methods that return a value (expression bodies)

- **Symptom / trigger:** During the final `./gradlew clean test` checkpoint, test
  discovery failed with `DiscoveryIssueException` — two **critical** issues against
  `S3StreamingSub6MbIntegrationTest`:
  - `@BeforeAll method 'startContainer()' must not return a value`
  - `@AfterEach method 'cleanObjects()' must not return a value`
  JUnit Jupiter 6 enforces that lifecycle methods (`@BeforeAll`/`@AfterAll`/
  `@BeforeEach`/`@AfterEach`) and `@Test` methods return `void`/`Unit`. The two
  methods used Kotlin **expression bodies** (`fun startContainer() = runBlocking { ... }`).
  The last expression of each block was non-`Unit`: `startContainer` ended with
  `s3.createBucket { ... }` (returns `CreateBucketResponse`), and `cleanObjects`
  ended with `listed.contents?.forEach { ... }` (returns `Unit?` because of the safe
  call). Kotlin compiled those into JVM methods returning `CreateBucketResponse` /
  `kotlin.Unit`, which JUnit 6 rejects. (Sibling tests that used **block bodies**
  — `fun startContainer() { ... }` — were fine, which is why only one file failed.)

- **Resolution / status:** **Resolved.** Convert lifecycle methods to **block bodies**
  so the JVM return type is `void`: wrap the `runBlocking { ... }` in `{ }` rather than
  using `= runBlocking { ... }`. Rule of thumb for this stack: never use an
  expression body for a `@BeforeAll/@AfterAll/@BeforeEach/@AfterEach/@Test` method —
  a trailing builder/`forEach`/safe-call silently makes the method return a value.

- **Non-blocking note (same run):** JUnit also logged two **non-critical** warnings
  that the `@Tag("Feature: ... , Property N: ...")` strings have *invalid tag syntax*
  (commas/colons are reserved), so those tags are **ignored** for tag-based filtering.
  This does not fail the build; the property tests still run. If tag-based selection
  is ever needed, switch to a syntactically valid tag and keep the descriptive
  property string in the test name / a comment.

---

## Coverage gate: covering the write-failure propagation branch in `ResponseWriter`

- **Symptom / trigger:** `./gradlew koverVerify` failed at the final gate —
  `lines covered percentage is 89.673900, but expected minimum is 90` (165/184 lines).
  The Kover XML report showed the only easily-closable production gap was
  `ResponseWriter` at 88.89%: the two `.onFailure { logger.error(...) }.getOrThrow()`
  branches in `writeMetadata`/`writeError` (the write-failure propagation paths,
  Req 4.6) were never exercised. The larger uncovered blocks (`PrimingContext` /
  `PrimingLogger`, 13 lines) are `private object` no-op stubs unreachable from tests
  without changing production visibility, and the handler never reads `Context`, so
  they were left as-is.

- **Resolution / status:** **Resolved without changing production behavior.** Added
  two unit tests to `ResponseWriterTest` using a `FailingOutputStream` (throws
  `IOException` on every `write`) to assert both `writeMetadata` and `writeError`
  propagate the failure rather than swallowing it. Line coverage rose to
  **91.30% (168/184)** and `koverVerify` passes. The 90% threshold was **not**
  weakened.

---

## Streaming protocol metadata: headers must be `Map<String, String>`, not `Map<String, List<String>>`

- **Symptom / trigger:** Lambda completes successfully (12s, 209 MB, no errors in
  CloudWatch) but API Gateway returns HTTP 502 `{"message": "Internal server error"}`.
  The streaming integration is correctly configured (`responseTransferMode: STREAM`,
  URI suffix `/response-streaming-invocations`), and the Lambda writes the metadata
  JSON + 8 null-byte delimiter + body to the `OutputStream`. The issue only surfaces
  against a deployed API Gateway; the LocalStack integration tests pass because
  LocalStack doesn't enforce the metadata prelude format.

- **Root cause:** The metadata JSON prelude's `headers` field was serialized as
  `Map<String, List<String>>` (JSON arrays for header values):
  ```json
  {"statusCode":200,"headers":{"Content-Type":["application/octet-stream"],"Content-Length":["12582912"]}}
  ```
  API Gateway's streaming protocol parser expects `Map<String, String>` (plain string
  values, not arrays):
  ```json
  {"statusCode":200,"headers":{"Content-Type":"application/octet-stream","Content-Length":"12582912"}}
  ```
  The array format is syntactically valid JSON but not recognized as a valid streaming
  metadata prelude by API Gateway, which then returns 502 because it cannot extract the
  HTTP status code and headers from the Lambda output.

- **Resolution / status:** **Resolved.** Changed `ResponseMetadata.headers` from
  `Map<String, List<String>>` to `Map<String, String>`. For repeatable headers (e.g.
  `Set-Cookie`), the API Gateway streaming format provides a separate `cookies` array
  field — it does not use JSON arrays inside the `headers` map. The proven working format
  (matching the MockNest implementation) is:
  ```kotlin
  @Serializable
  data class ResponseMetadata(
      val statusCode: Int,
      val headers: Map<String, String>,
  )
  ```
  This is the single most consequential gotcha for porting the Node.js streaming helper
  to the JVM: the metadata prelude format is implicitly documented through the Node.js
  helper's behavior but never spelled out for other runtimes.

---

## End-to-end streaming confirmed: 21.7 MB NASA GeoTIFF delivered byte-identical

- **Symptom / trigger:** Final proof that the deployed endpoint streams a real-world
  large file (well beyond the legacy 6 MB buffered limit) to a client with no
  corruption. The test used a ~21 MB NASA Black Marble GeoTIFF
  (`BlackMarble_2016_1200m_africa_s.tif`) — a publicly available Earth-observation
  image that exercises the endpoint with a realistic, non-synthetic payload.

- **Verification steps:**
  1. Uploaded the 21,702,219-byte GeoTIFF to the source bucket via `aws s3 cp`.
  2. Curled the endpoint with the API key header:
     ```
     HTTP 200 | First byte: 6.97s | Total: 12.70s | Size: 21702219 bytes
     ```
  3. Downloaded the source object directly from S3 and ran `cmp -s` against the
     streamed body — **byte-identical**.

- **Observations:**
  - First byte at ~7s vs total ~12.7s (55% mark). Slightly above the 50% target on
    the first invocation — attributable to SnapStart restore latency on a cold alias
    version. Subsequent requests (post-warmup) showed TTFB well within the 50%
    threshold.
  - The 1 MB bounded buffer held: Lambda max memory did not spike with the 21 MB body.
  - Content-Type detection is not performed; the object streams as
    `application/octet-stream` regardless of the original S3 content type. This is
    fine for the example scope (the goal is to prove streaming, not serve a CDN).

- **Resolution / status:** **Resolved — success criteria met.** A payload far exceeding
  6 MB is delivered progressively and byte-identically. The content-type marker open
  item (above) is also implicitly closed: API Gateway correctly interprets the metadata
  prelude on the JVM `RequestStreamHandler` path without any explicit
  `setContentType("application/vnd.awslambda.http-integration-response")` call — the
  streaming invocation path handles it automatically when `ResponseTransferMode` is set.

---

## curl HTTP/2 error 92 with API Gateway response streaming — force `--http1.1`

- **Symptom / trigger:** In CI (GitHub Actions) and on local machines with newer curl
  versions that default to HTTP/2, streaming requests to the API Gateway endpoint fail
  with:
  ```
  curl: (92) HTTP/2 stream 1 was not closed cleanly: INTERNAL_ERROR (err 2)
  ```
  The body may have been fully transferred, but curl treats the unclean stream close
  as a fatal error (non-zero exit code), causing the pipeline test to report "endpoint
  unreachable". The same request succeeds immediately when forced to HTTP/1.1.

- **Root cause:** API Gateway's streaming response uses chunked transfer encoding.
  When curl negotiates HTTP/2, the streamed response body transfers correctly, but the
  HTTP/2 stream termination (RST_STREAM or GOAWAY) is not sent cleanly by the API
  Gateway frontend after the Lambda finishes writing. Curl interprets this as an
  INTERNAL_ERROR on the HTTP/2 stream. HTTP/1.1 chunked transfer encoding terminates
  cleanly with a zero-length chunk and works without issue.

- **Resolution / status:** **Resolved.** Added `--http1.1` to all curl calls in both
  `scripts/pipeline-streaming-test.sh` and `scripts/post-deploy-test.sh`. This forces
  HTTP/1.1 regardless of the curl version's default protocol negotiation.

- **AWS docs reference:** The [API Gateway response streaming troubleshooting page](https://docs.aws.amazon.com/apigateway/latest/developerguide/response-streaming-troubleshoot.html)
  recommends using `--no-buffer` and `-i` for testing streaming but does not
  explicitly document the HTTP/2 stream-close incompatibility. The troubleshooting
  page only mentions `curl: (18) transfer closed with outstanding read data remaining`
  (a timeout issue). The HTTP/2 error 92 is not covered as of June 2026 — this may be
  a documentation gap or an issue specific to the REST API streaming integration path.
  **TODO:** verify against official AWS docs/blogs whether this is a known limitation
  or a transient platform bug.

---

## Lambda streaming: `OutputStream` must be closed explicitly — partial body on warm invocations

- **Symptom / trigger:** The pipeline TTFB test (`scripts/pipeline-streaming-test.sh`)
  consistently failed with:
  ```
  curl: (18) transfer closed with 4112195 bytes remaining to read
  ```
  The first invocation after SnapStart restore delivered all 12 MB correctly.
  Every subsequent warm invocation delivered exactly 8 470 717 bytes (~8.4 MB)
  and then closed the connection, leaving 4 112 195 bytes undelivered — despite
  no errors anywhere in the Lambda CloudWatch logs and a normal `REPORT` line
  (Duration ~1 000 ms, no timeout).

- **Root cause:** `StreamHandler.handleRequest()` wrote to the `OutputStream` but
  never called `close()` on it. AWS Lambda streaming requires the handler to close
  the output stream to signal to the runtime that the response is complete. Without
  an explicit `close()`, the runtime flushes what it can before tearing down the
  execution environment — on a slow first invocation (6 390 ms with SnapStart restore)
  there happened to be enough time for the runtime's own cleanup to flush everything;
  on fast warm invocations (~1 000 ms) the Lambda exited before the runtime finished
  delivering the remaining ~3.6 MB to API Gateway.

  The AWS documentation states: *"You should close the output stream at the end of
  your handler function."* This requirement is easy to miss because omitting `close()`
  does not produce any error — the handler exits normally, the CloudWatch logs are
  clean, and the bug only appears under timing pressure (fast/warm invocations), not
  on the slower cold-start path.

- **Resolution / status:** **Resolved.** Two changes were required:

  1. **`output.use { }`** — wraps the handler body so `close()` is called on every
     exit path (normal return, error response, and uncaught exception).
  2. **Explicit `output.flush()` before close** — added at the end of the `output.use { }`
     block so the Lambda streaming runtime drains its buffer completely before receiving
     the `close()` signal. Without this, a race condition in the runtime's streaming
     channel occasionally left the last ~225 KB undelivered on fast (~1 000 ms) warm
     invocations even after `close()` was called — an intermittent `curl: (18)` failure
     that disappeared on retry.

  Both fixes are in `StreamHandler.handleRequest()` in `streaming-s3-example`.

---

## Consuming `aws-lambda-streaming-core` from Java — Kotlin-idiom interop friction

- **Symptom / trigger:** The `streaming-s3-example-java` module drives the same
  library the Kotlin example uses (`ResponseWriter`, `ResponseMetadata` +
  `fromMultiValue`, and the top-level `copy(...)`), but from **plain Java**. The
  library is Kotlin compiled for the JVM, and several Kotlin idioms that are
  invisible from Kotlin surface as awkward call sites from Java. None are blockers,
  but each needs a Java-compatible entry point (Req 1.5, 14):
  - **Default constructor arguments don't exist for Java callers.** `ResponseWriter`
    has a secondary constructor `ResponseWriter(json: Json = …, maxPreludeLen: Int? = …)`.
    Java can't omit the defaults, so it must pass them explicitly:
    `new ResponseWriter(Json.Default, ResponseWriterKt.OBSERVED_MAX_PRELUDE_LEN)`.
    `Json.Default` is the companion singleton on `kotlinx.serialization.json.Json`.
  - **Top-level `const`/`val` members live on a synthetic `*Kt` facade class.**
    `OBSERVED_MAX_PRELUDE_LEN` and `DELIMITER_LEN` are file-level constants, so from
    Java they are reached as `ResponseWriterKt.OBSERVED_MAX_PRELUDE_LEN` /
    `ResponseWriterKt.DELIMITER_LEN`, not as members of `ResponseWriter`.
  - **Companion functions are reached via `.Companion`.**
    `ResponseMetadata.fromMultiValue(...)` is a companion-object function, so the
    Java call site is `ResponseMetadata.Companion.fromMultiValue(status, headers)`
    (there is no `@JvmStatic` on it).
  - **A nullable-with-default parameter still has to be passed from Java.**
    `ResponseMetadata(statusCode, headers, cookies = null)` — the `cookies` default
    is unavailable to Java, so the direct constructor call must pass `null`
    explicitly: `new ResponseMetadata(200, headers, null)`.
  - **Function-typed parameters would force constructing a Kotlin `Function0`.**
    The top-level `copy(source, sink, flush = { sink.flush() })` has a
    function-typed third parameter. Thanks to `@JvmOverloads`, a 2-arg overload
    `copy(InputStream, OutputStream)` is generated that defaults `flush` to flushing
    the sink — exactly the desired behavior — so Java calls
    `BoundedBufferKt.copy(in, out)` and avoids building a `kotlin.jvm.functions.Function0`.

- **Resolution / status:** **Resolved by call-site convention (no library change
  required to ship).** The Java handler consumes the library through these
  Java-friendly entry points: `new ResponseWriter(Json.Default, ResponseWriterKt.OBSERVED_MAX_PRELUDE_LEN)`,
  `new ResponseMetadata(int, Map, null)`, `ResponseMetadata.Companion.fromMultiValue(...)`,
  and the 2-arg `BoundedBufferKt.copy(source, sink)`. These are verified against the
  library's `.api` dump (`streaming-core/api/streaming-core.api`) and exercised by the
  Property 1 metadata round-trip test; confirm the exact call sites compile and pass
  at the task 3.2 checkpoint.
  - **Candidate library ergonomics improvement (for the article / a future library
    release):** add Java-friendly overloads so the `*Kt`/`Companion`/`Json.Default`
    detours aren't needed — e.g. a `ResponseWriter(int maxPreludeLen)` overload
    (and/or a no-`Json` overload defaulting to `Json.Default`), and optionally
    `@JvmStatic` on `fromMultiValue`. These would make the library callable from Java
    without any knowledge of Kotlin's compilation model. Recorded as a **candidate**,
    not yet actioned — the current example deliberately calls the library as-published
    to document the real Java consumption experience.

---

## Mockito on Java 25 — Byte Buddy class-file version (confirmed: flag NOT required)

- **Symptom / trigger:** The Java example mocks its concrete collaborators
  (`RequestParser`, `FileNameValidator`, `S3Source`, `ResponseWriter`, and the AWS SDK
  for Java v2 `S3Client`) with **Mockito** instead of MockK. Mockito instruments
  classes through **Byte Buddy**, the same engine that forced
  `net.bytebuddy.experimental=true` for MockK on the Java 25 toolchain (see the MockK
  entry above): older Byte Buddy builds refuse the Java 25 class-file version unless
  experimental mode is enabled, which shows up as instrumentation/mock-creation
  failures rather than a clear "unsupported class file" message.

- **Resolution / status:** **Confirmed at the task-14.1 checkpoint — the experimental
  flag is NOT required for the Java module.** With **Mockito 5.23.0** (`mockito-core` +
  `mockito-junit-jupiter`), all 167 unit + property tests — including the ones that mock
  concrete Java-25-compiled collaborators (`RequestParser`, `FileNameValidator`,
  `S3Source`, `StreamHandler`, `ResponseWriter`) and the AWS SDK v2 `S3Client` interface —
  pass on the Java 25 toolchain **without** `net.bytebuddy.experimental=true`. The Byte
  Buddy bundled with Mockito 5.23.0 already recognises the JDK 25 class-file version, so
  the pre-emptive `systemProperty("net.bytebuddy.experimental", "true")` was **dropped**
  from the Java module's `tasks.test` (verified: the suite is green with the flag absent).
  - **Contrast with the Kotlin/MockK module:** the Kotlin `streaming-core` module still
    needs the flag (see the MockK entry above), because MockK 1.14.5 bundles an older Byte
    Buddy that rejects the Java 25 class-file version. So the flag is a MockK-version
    problem, not a JDK-25 problem per se — a newer Byte Buddy (as shipped in Mockito
    5.23.0) removes the need for it entirely.

---

## Kover on a pure-Java module (confirmed: does NOT work — switched to JaCoCo)

- **Symptom / trigger:** The repo standardizes on **Kover** (`koverVerify`) for the
  coverage gate across modules. Kover instruments compiled JVM **bytecode**, so in
  principle it should cover a Java-only module (`src/main/java`, no Kotlin production
  sources) just as well as a Kotlin one. In practice it does **not**: with only the
  `java` plugin applied (no `kotlin` plugin), running
  `./gradlew :streaming-s3-example-java:koverHtmlReport :streaming-s3-example-java:koverVerify`
  produced an HTML report reading **"No coverage information was found"** and a
  `koverVerify` that **passed vacuously** — i.e. the 80% gate was green while measuring
  *zero* code. A `--dry-run` of the report task told the whole story: for the Kotlin
  module (`streaming-core`) Kover's graph includes `koverFindJar`, `test`, and
  `koverGenerateArtifactJvm`; for the pure-Java module the graph is only
  `koverGenerateArtifact` + `koverHtmlReport` — **no `test` task and no `…Jvm`
  variant**. Kover creates its JVM coverage variant (the thing that instruments the
  `test` task) only for a *Kotlin JVM module* — one where a Kotlin plugin is applied —
  as the [Kover docs](https://kotlin.github.io/kotlinx-kover/gradle-plugin/) spell out
  ("for Kotlin JVM module Kover creates special report variant with name jvm").
  Switching the engine via `kover { useJacoco() }` did not help — the report then
  rendered in JaCoCo's HTML style but still said **"No class files specified"**, because
  the missing piece is the *variant/`test`-task wiring*, not the coverage engine.
  (Content was rephrased for compliance with licensing restrictions.)

- **Resolution / status:** **Confirmed at the task-14.1 checkpoint — Kover cannot
  instrument this pure-Java module, so it was switched to JaCoCo (Req 14, per the
  design's fallback note).** The module's `build.gradle.kts` now applies the core
  `jacoco` plugin instead of `org.jetbrains.kotlinx.kover`:
  - `jacoco { toolVersion = "0.8.14" }` — **JaCoCo 0.8.14 is the first release that
    officially supports Java 25 class files** (0.8.13 was experimental), so the Java 25
    toolchain's bytecode instruments cleanly. (Ref: JaCoCo change history, 0.8.14 —
    "JaCoCo now officially supports Java 25".)
  - `jacocoTestCoverageVerification` enforces the **same 80% gate** on the LINE counter
    (`COVEREDRATIO`, `minimum = 0.80`) — the counter Kover's default `minValue` bound
    used, so the gate is equivalent, not weakened.
  - `jacocoTestReport` generates the HTML (and XML) report.
  - The repo-wide command surface is preserved: thin **`koverHtmlReport` / `koverVerify`
    alias tasks** delegate to the JaCoCo tasks, so the task-14.1 command and CI
    (`workflow-build.yml` runs `:streaming-s3-example-java:koverVerify`) keep working
    unchanged — no edits to the pipeline were needed.
  - **Proof the gate is real (not vacuous like Kover's):** measured **line coverage
    83.53% (142/170)** — report renders full per-class data — so `koverVerify` passes;
    temporarily raising `minimum` to `0.95` made it **fail** with
    `lines covered ratio is 0.83, but expected minimum is 0.95`, then reverted to `0.80`.
  - **Takeaway for the article:** Kover's "it's just bytecode, so it covers Java too"
    promise holds only when a Kotlin plugin is present to register the JVM variant. For a
    genuinely Java-only Gradle module, reach for JaCoCo directly (and pin ≥ 0.8.14 on
    Java 25). If a uniform `koverVerify` command surface matters across a mixed repo,
    alias it to the JaCoCo tasks as done here.

---

## `sam build` fails on a prebuilt `java25` fat jar — deploy the CodeUri jar directly

- **Symptom / trigger:** Running the local `deployment/aws/sam-java/deploy.sh` (which
  did `build.sh` → `sam build` → `sam deploy`) failed at the `sam build` step against
  the deployed SAM CLI (1.160.x):
  ```
  Build Failed
  Error: Unable to find a supported build workflow for runtime 'java25'.
  Reason: None of the supported manifests '['build.gradle', 'build.gradle.kts', 'pom.xml']'
  were found in the following paths '[.../build/dist/streaming-endpoint-java.jar', '.../deployment/aws/sam-java']'
  ```
  The template's `CodeUri` already points at a **prebuilt fat jar**
  (`build/dist/streaming-endpoint-java.jar`) produced by Gradle/Shadow. Recent SAM CLI
  versions register a build workflow for the `java25` runtime that expects a
  Gradle/Maven **manifest** in the `CodeUri` path; pointed at a bare `.jar` (which has
  no manifest) that workflow errors out instead of treating the jar as already built.
  The Kotlin `deployment/aws/sam/deploy.sh` carries the same `sam build` step and hits
  the same trap on this SAM version.

- **Root cause:** `sam build` is the wrong tool for an already-assembled fat jar. The
  proven-working path — the CI pipeline `workflow-deploy-aws.yml` — never runs
  `sam build`: it builds the jar with Gradle, then runs `sam deploy` directly, which
  performs an implicit `sam package` (uploads the `CodeUri` jar to the artifacts bucket
  and rewrites the template). `sam build` only makes sense when SAM itself compiles the
  source, which is not how this repo produces the Lambda artifact.

- **Resolution / status:** **Resolved.** Dropped the `sam build` step from
  `deployment/aws/sam-java/deploy.sh`; it now runs `build.sh` (Gradle fat jar) then
  `sam deploy --config-file samconfig.toml --template-file template.yaml`, packaging the
  prebuilt jar directly — exactly what the pipeline does. With this, the Java stack
  deployed cleanly to `<AWS_REGION>` (SnapStart `live` alias published) and the pipeline
  streaming test passed against the live endpoint: a 12 MB (> 6 MB) payload delivered in
  full and byte-identical, TTFB ≈ 46% of total over HTTP/1.1 (progressive delivery
  confirmed). If a stale `.aws-sam/` build dir exists from a failed `sam build`, remove it
  so `sam deploy` uses the source `template.yaml` rather than a broken build artifact.
  - **Note:** the Kotlin `deployment/aws/sam/deploy.sh` still has the old `sam build`
    step and would hit the same error on this SAM version; apply the same fix there when
    it is next deployed locally (the CI pipeline is unaffected since it deploys directly).

---

## `post-deploy-test.sh` didn't send the API key — 403 at the warmup against an ApiKeyRequired endpoint

- **Symptom / trigger:** Running `STACK_NAME=java-s3-file-streaming-endpoint scripts/post-deploy-test.sh`
  against the deployed Java stack failed immediately: the endpoint enforces
  `Auth: ApiKeyRequired: true` (both the Kotlin and Java SAM templates do), so an
  unauthenticated GET returns **HTTP 403**. `post-deploy-test.sh` — unlike
  `pipeline-streaming-test.sh` — never resolved or sent an `x-api-key` header, so
  its very first request (the warmup) tripped the script's own non-success guard
  (`non-success HTTP status 403 for warmup`) before any timing/memory check ran.
  A bare `curl` to the endpoint confirms it: `no-key http_code=403`, whereas the
  same request with a valid `x-api-key` header is accepted.

- **Resolution / status:** **Resolved.** Brought `post-deploy-test.sh` in line with
  `pipeline-streaming-test.sh`: `resolve_config` now resolves the API key from the
  stack's `ApiKeyId` output via `aws apigateway get-api-key --include-value` (unless
  `API_KEY` is already set), builds an `auth_header_args=(--header "x-api-key: …")`
  array, and every `curl` in `http_get_timed` sends it. The key is treated as a
  **runtime secret**: it is never echoed, and it is masked in GitHub Actions logs
  with `::add-mask::`. When no `ApiKeyId` output exists the script falls back to
  unauthenticated requests, so it still works against a keyless endpoint.
  - **Takeaway for the article:** the two verification scripts are near-twins, but
    only the pipeline one had learned the API-key lesson. When a template turns on
    `ApiKeyRequired`, every client path — including the local post-deploy prover —
    has to send `x-api-key`, or it fails at 403 long before it can measure anything.

---

## Missing S3 object returns **403**, not `NoSuchKey` — `s3:GetObject`-only IAM makes the not-found path respond 502

- **Symptom / trigger:** With a valid API key, requesting a **non-existent** object
  from the deployed Java endpoint consistently returned **HTTP 502** (fast, ~0.13 s —
  not a cold start), where Req 4.2 expects **404**. CloudWatch shows the handler took
  the `Failure` branch, not `NotFound`:
  ```
  WARN nl.vintik.streaming.java.StreamHandler - Object head failed or timed out; responding 502
  software.amazon.awssdk.services.s3.model.S3Exception: Forbidden (Service: S3, Status Code: 403 ...)
      at nl.vintik.streaming.java.S3Source.head(S3Source.java:76)
  ```

- **Root cause:** The Lambda's execution role grants **`s3:GetObject` only**, scoped
  to `<BUCKET_ARN>/*`, with **no `s3:ListBucket`**. S3 returns `NoSuchKey` (404) for a
  missing key only when the caller has `s3:ListBucket` on the bucket; **without**
  `ListBucket`, S3 deliberately returns **`403 Forbidden`** for a missing key (so it
  can't be used to probe object existence). `HeadObject` therefore surfaces a generic
  `S3Exception` (status 403), not `NoSuchKeyException`. `S3Source.head` catches only
  `NoSuchKeyException` → `NotFound`; every other exception → `Failure`, which the
  handler maps to **502**. So a genuinely missing object is reported as an upstream
  failure (502) instead of not-found (404).

- **Resolution / status:** **RESOLVED.** Fixed in `S3Source.head()` by adding a catch
  block for `S3Exception` (between the existing `NoSuchKeyException` and
  `ApiCallTimeoutException` catches) that maps HTTP status codes 403 and 404 to
  `HeadResult.NotFound`. This keeps the IAM policy `s3:GetObject`-only (no
  `s3:ListBucket` needed) and makes the handler correctly return **404** for a missing
  object regardless of whether S3 responds with 403 (no `ListBucket`) or 404 (has
  `ListBucket`). A corresponding unit test (`headForbiddenOnMissingObjectMapsToNotFound`)
  confirms the mapping. The Kotlin SDK handles this internally (maps both to `NotFound`),
  so only the Java module required this fix.
  - **Takeaway for the article:** "object not found ⇒ 404" is an IAM-shaped assumption.
    On an S3 `GetObject`-only role, *missing* and *forbidden* are indistinguishable —
    S3 returns 403 for both — so a not-found handler that keys off `NoSuchKey` alone
    will mis-report missing objects as 5xx.

## Negative-testing `verifyCoverageReports` — `dependsOn` silently repairs the sabotage

- **Context:** `verifyCoverageReports` (root `build.gradle.kts`) asserts all three XML
  coverage reports exist, parse, hold `<class>` entries, and report non-zero covered
  lines. Proving it actually fails means breaking a report on purpose.
- **Gotcha:** the task `dependsOn` `:streaming-core:koverXmlReport`,
  `:streaming-s3-example:koverXmlReport` and `:streaming-s3-example-java:jacocoTestReport`.
  Delete a report and re-run, and Gradle notices the missing output, re-runs the report
  task, and regenerates the file *before* the assertion executes — the build goes green
  and the negative test proves nothing. `outputs.upToDateWhen { false }` does not help:
  it only stops the verify task itself from being skipped.
- **Fix:** exclude the three producer tasks so the broken file survives into the
  assertion:
  ```bash
  ./gradlew verifyCoverageReports -PexcludeTags=integration \
    -x :streaming-core:koverXmlReport \
    -x :streaming-s3-example:koverXmlReport \
    -x :streaming-s3-example-java:jacocoTestReport
  ```
- **Proven, each exit code 1, each naming the offending module:**
  - missing file — `:streaming-core: no coverage report at …/streaming-core/build/reports/kover/report.xml`
  - valid XML, no classes — `:streaming-core: coverage report at … contains no <class> entries`
  - classes present, nothing covered — `:streaming-core: coverage report at … reports zero covered lines across 2 class entries`
  - truncated file — `:streaming-s3-example-java: coverage report at … does not parse as XML (XML document structures must start and end within the same entity.)`
- **Second gotcha, when restoring:** copy the backups back byte-identically. The report
  tasks stay `UP-TO-DATE` on a byte-identical output, so the next full run reuses the
  restored files rather than regenerating them — a "close enough" restore would linger.
  Verify with `shasum -a 256` against the backups, then confirm
  `./gradlew verifyCoverageReports koverVerify koverHtmlReport -PexcludeTags=integration --continue`
  exits 0 again.

---

## Dependabot cannot read Kotlin-DSL `extra[…]` versions — the version-catalog migration

- **Symptom / trigger:** Every external version lived in the root `build.gradle.kts` as
  `extra["awsSdkKotlinVersion"] = "1.6.59"` and was consumed from the modules as
  `implementation("aws.sdk.kotlin:s3:${rootProject.extra["awsSdkKotlinVersion"]}")`. No
  Dependabot pull request ever arrived for any of them. **Cause:** Dependabot's Gradle
  parser is a *static* reader — it recognises literal coordinates in a build file,
  `gradle.properties` values, and version catalogs, but it does not evaluate Kotlin DSL.
  A map lookup like `rootProject.extra["…"]` is code, so the coordinate it produces is
  invisible and the version is silently never updated. The whole dependency-automation
  story fails quietly: the config looks correct, the alerts stay empty.
- **Resolution / status:** **RESOLVED.** All versions moved into
  `gradle/libs.versions.toml` (`[versions]`, `[libraries]`, one `[bundles]` entry, and
  `[plugins]`), consumed through generated type-safe accessors (`libs.aws.sdk.kotlin.s3`,
  `libs.bundles.integration.testing`, `alias(libs.plugins.bcv)`), and every
  `extra["…"]` / `rootProject.extra[…]` entry deleted from the root and all three module
  build files. Because the catalog is a first-class Dependabot input, each version now
  has exactly one bumpable declaration site. The migration was proven
  **resolution-neutral, not merely assumed to be:** `scripts/dependency-baseline.sh before`
  was captured from the unmigrated tree, `… after` from the migrated one, and
  `diff -ru build/dependency-baseline/before build/dependency-baseline/after` came back
  **empty** — zero additions, removals or version changes across all three modules
  (`streaming-core`, `streaming-s3-example`, `streaming-s3-example-java`) and all four
  configurations (`compileClasspath`, `runtimeClasspath`, `testCompileClasspath`,
  `testRuntimeClasspath`), with identical per-module MD5s. `:streaming-core:apiCheck`
  was unchanged, confirming the public API dump was untouched. Only the *form* of the
  declarations changed.

---

## The versionless-plugin trap — `alias(...)` re-introduces a version Gradle rejects

- **Symptom / trigger:** Converting the module `plugins { }` blocks to catalog aliases
  wholesale looks like the obvious completion of the migration, and it breaks the build
  immediately: `alias(libs.plugins.kotlin.jvm)` in `streaming-core/build.gradle.kts`
  fails with *"Plugin request for plugin already on the classpath must not include a
  version"*. **Cause:** the root project applies `kotlin("jvm")`,
  `kotlin("plugin.serialization")` and the Kover plugin with `apply false`, which puts
  them on the **inherited script classpath**; the subprojects then apply them *without* a
  version on purpose. A catalog alias always carries the version from `[plugins]`, so
  switching to `alias(...)` silently re-adds the thing the versionless form exists to
  omit. The error message names the plugin but not the mechanism, so it reads like a
  catalog problem rather than a classpath one.
- **Resolution / status:** **RESOLVED (convention fixed).** `alias(libs.plugins.…)` is
  used **only** where the plugin is resolved by that build file itself: the root
  (`kotlin.jvm`, `kotlin.serialization`, `shadow`, `kover`, all `apply false`) and the
  two genuinely module-local plugins in `streaming-core`,
  `alias(libs.plugins.maven.publish)` and `alias(libs.plugins.bcv)`. The three inherited
  plugins stay versionless in every module — `kotlin("jvm")`,
  `kotlin("plugin.serialization")`, `id("org.jetbrains.kotlinx.kover")`. Their versions
  are still catalog-managed and Dependabot-visible; they are just declared once, in the
  root, where the classpath is actually formed.

---

## `resolvedCoordinates` needs `outputs.upToDateWhen { false }` — and `plugins.withId("java")`

- **Symptom / trigger:** Two ways the dependency baseline can pass while proving nothing.
  **(1) Up-to-date skip.** The task declares an output file (
  `build/reports/resolved-coordinates/<module>.txt`) but has **no file inputs** — its real
  input is the resolution result, which Gradle does not model as a file. Gradle therefore
  considers the task up to date on the second run and skips it, so an `after` capture
  copies the *`before`* content forward and `diff -ru` compares a file against itself. The
  baseline reports "no change" on a tree whose resolution genuinely changed — the exact
  failure the check exists to catch, inverted into a false pass.
  **(2) Empty `configurations`.** Registering the task in a bare `subprojects { }` body
  produces zero coordinates. **Cause:** the root build script is evaluated *before* the
  subprojects, so at that moment no subproject has applied the Java plugin and
  `configurations` is empty; `configurations.named("compileClasspath")` either fails or
  resolves nothing.
- **Resolution / status:** **RESOLVED.** The task carries
  `outputs.upToDateWhen { false }` so it re-resolves on every invocation, with a comment
  on site explaining that the alternative is a baseline diff that passes on a changed
  tree. It is registered inside `subprojects { plugins.withId("java") { … } }`, which
  defers registration until the Java plugin is applied and the configurations exist. The
  four `rootComponent` providers are captured outside `doLast` so the task stays
  configuration-cache safe. `outputs.upToDateWhen { false }` on a verification task that
  reads generated state is now the house rule here — the root's
  `verifyCoverageReports` carries it for the same reason.

---

## Version literals that deliberately stay — the Requirement 9.8 allow-list

- **Symptom / trigger:** "No version literals in build files" is the wrong rule: five
  values cannot or must not be expressed as a catalog accessor, and a blanket grep for
  version strings flags all five as violations. This matters beyond tidiness — the
  verification script reads **this list** as its allow-list, so a literal that is *not*
  recorded here fails the check. An incomplete list produces false failures; a vague one
  lets a genuinely unmanaged version through.
- **Resolution / status:** **RESOLVED — the allow-list is exactly these six, and is
  complete:**
  1. **`settings.gradle.kts`** — `id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"`.
     Settings scripts are evaluated before the catalog exists and have no generated
     accessors. Acceptable rather than merely unavoidable: Dependabot reads a literal
     `version "…"` declaration fine, so the value stays automated.
  2. **Both example modules** — `systemProperty("floci.image", "floci/floci:${libs.versions.flociImage.get()}")`.
     The *image name* is not a Maven coordinate. The tag comes from the catalog
     (`[versions] flociImage`), but no library entry references it, so Dependabot cannot
     bump a **Docker** tag held there — this one is a **manual** bump, by design, and is
     pinned rather than left tracking `floci/floci:latest`.
  3. **`streaming-core`** — `version = providers.gradleProperty("releaseVersion").getOrElse("2.0.0-SNAPSHOT")`.
     The project's *own* published version, derived from the git tag by the publish
     workflow. Out of scope for the catalog; "migrating" it would break the rule that the
     tag is the single source of truth.
  4. **Every module with tests** — `junit-platform-launcher`, a **versionless** catalog entry.
     Its version is constrained by `junit-jupiter`; pinning it separately invites a split.
  5. **The Java S3 example** — `aws-sdk-java-s3`, also **versionless**. The version comes from
     `platform(libs.aws.sdk.java.bom)`.
  6. **Root `build.gradle.kts`** — `classpath("org.freemarker:freemarker:2.3.35")` in the
     `buildscript { constraints }` block. It lifts the FreeMarker that Kover pulls onto the build
     classpath to the patched release (Dependabot alert, path traversal). A `buildscript` block is
     evaluated before the catalog accessors exist, so it cannot use `libs.`. Build tooling only;
     nothing reaches the Lambda zip. Remove it once Kover ships a fixed FreeMarker.
  Both versionless entries carry an inline comment in `gradle/libs.versions.toml` naming
  where the version comes from, so the omission reads as intentional.

---

## The JaCoCo DTD gotcha — `report.dtd` is never written next to the XML

- **Symptom / trigger:** `verifyCoverageReports` parses all three coverage XML reports,
  and the JaCoCo one behaves differently from the two Kover ones. The JaCoCo report opens
  with `<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">`, but
  **`report.dtd` is never written next to the XML**. A default `DocumentBuilder` honours
  that declaration: it either fails outright on the unresolvable relative system ID, or —
  worse — resolves the public ID **over the network**, turning a coverage assertion into
  a task that needs internet access and can hang or fail in CI for reasons unrelated to
  coverage. Kover's XML has **no DOCTYPE at all**, so the Kotlin modules parse cleanly and
  the problem looks module-specific rather than parser-specific.
- **Resolution / status:** **RESOLVED.** The `DocumentBuilderFactory` in
  `verifyCoverageReports` disables external DTD and entity loading —
  `FEATURE_SECURE_PROCESSING` on, `load-external-dtd` off,
  `external-general-entities` / `external-parameter-entities` off, `isValidating = false` —
  with the feature calls guarded so an unsupported feature on a different parser cannot
  break the build. Nothing is validated against the DTD; the task only needs the document
  tree. (Separate gotcha on the same task, already logged above: *"Negative-testing
  `verifyCoverageReports` — `dependsOn` silently repairs the sabotage"*.)

---

## Licence mismatch: `LICENSE` said MIT, the `streaming-core` POM said Apache-2.0

- **Symptom / trigger:** The repository `LICENSE` file is **MIT**, while the
  `streaming-core` Maven publication POM declared
  `licenses { license { name = "Apache-2.0"; url = "https://www.apache.org/licenses/LICENSE-2.0" } }`.
  **Cause:** the publishing block was lifted from a template and the POM licence was never
  reconciled with the file that actually governs the code. Nothing fails a build over it —
  it only surfaces to consumers, who read the POM, not the repository, and to any
  automated licence audit that compares the two.
- **Resolution / status:** **RESOLVED — MIT kept.** `LICENSE` is treated as the
  authoritative source and was **deliberately left untouched**; the POM was corrected to
  match it. Files changed: `streaming-core/build.gradle.kts` only — the POM `licenses`
  block now reads `name = "MIT"` / `url = "https://opensource.org/licenses/MIT"`, with a
  comment stating that this identifier, `LICENSE`'s first line and the README licence badge
  are deliberately the same string. The README licence badge already read MIT, so no badge
  change was needed. **Not retroactively fixable:** versions already published carry the
  Apache-2.0 POM, and Maven Central artefacts are immutable — those POMs cannot be
  corrected. The fix applies from the next published version onward, and
  `./gradlew :streaming-core:generatePomFileForMavenPublication` was run to confirm the
  generated POM contains `<name>MIT</name>`.

---

## `timeout-minutes` is not supported on a job that calls a reusable workflow

- **Symptom / trigger:** The two thin callers — `ci-main-build.yml` and
  `ci-dependabot-validation.yml` — each consist of a single job whose entire body is a
  `uses:` pointing at `workflow-build.yml`. Adding the required 30-minute bound to that
  caller job is rejected by the workflow parser. **Cause:** a job that delegates to a
  reusable workflow accepts only a small key set (`uses`, `with`, `secrets`, `needs`,
  `if`, `permissions`, `strategy`, `concurrency`); `timeout-minutes` is not among them,
  because the caller does not own the runner — the called workflow's jobs do. A run bound
  declared where no runner exists has nothing to time out.
- **Resolution / status:** **RESOLVED — the bound moved down one level.** Both jobs
  *inside* `workflow-build.yml` (`test` and `validate-sam`) carry
  `timeout-minutes: 30`, which is where the runners actually are, so the bound covers
  every caller rather than being repeated per caller. The Codecov upload step carries its
  own tighter `timeout-minutes: 10`. `ci-main-build.yml` keeps an inline comment stating
  why the bound is not on the caller, so the apparent omission does not read as an
  oversight. This is how Requirements 3.10 and 11.7 are satisfied — not in the workflows
  those requirements name, but in the one they both call.

---

## The `secrets` context is unavailable in an `if:` expression

- **Symptom / trigger:** The coverage upload must be skipped, with a log line, when no
  Codecov token is available — the obvious spelling being
  `if: secrets.CODECOV_TOKEN != ''` on the upload step. GitHub does not expose `secrets`
  to `if:`. **Cause:** `if:` expressions are evaluated during job/step scheduling, before
  the secrets context is made available to a step's environment; the allowed contexts in
  a step `if:` do not include `secrets`. Writing it anyway yields an expression that
  never evaluates the way it reads.
- **Resolution / status:** **RESOLVED via a preceding eligibility step.**
  `workflow-build.yml`'s `test` job has a `Determine coverage-upload eligibility` step
  (`id: cov`) that maps the secret into `env:` — the one place it *is* available — tests
  it with `[ -z "${CODECOV_TOKEN:-}" ]`, and writes `eligible=true|false` to
  `$GITHUB_OUTPUT`. The upload step then gates on
  `steps.cov.outputs.eligible == 'true'`, which is an ordinary step-output expression.
  The token value is **never echoed** — only the emptiness test result leaves the step —
  and the skip path emits a `::notice::` naming maintainer setup checklist item 2, so a
  skipped upload is visible in the run log rather than silently absent. The same step
  also asserts all three coverage XML reports exist and are non-empty.

---

## `paths-ignore` on a `pull_request` trigger blocks merges forever

- **Symptom / trigger:** `ci-main-build.yml` carries
  `paths-ignore: ['**.md', 'docs/**', '.kiro/**']`, and the tempting move is to put it on
  both triggers so docs-only changes never burn a build. Doing that on `pull_request`
  deadlocks the repository. **Cause:** when a path filter excludes a pull request, the
  workflow is not skipped-with-success — it **never reports a check at all**. Combined
  with maintainer setup checklist item 12, which makes this workflow a *required* status
  check on `main`, a docs-only pull request would sit forever waiting for a check that
  will never arrive. There is no timeout and no override short of editing the branch
  rule.
- **Resolution / status:** **RESOLVED — the exclusions are on `push` only.** The
  `pull_request` trigger has no `paths-ignore`, so every pull request into `main` reports
  the required check, including docs-only ones (they simply run a fast, fully cached
  build). The `push` trigger keeps the exclusions, which is where they pay off: a
  docs-only commit landing on `main` runs no build. The workflow carries the reason as an
  inline comment directly above the trigger block, so nobody "tidies up" the asymmetry.

---

## `pull_request` offers no head-branch filter — and a Dependabot run cannot read secrets

- **Symptom / trigger:** `ci-dependabot-validation.yml` must run only for branches
  Dependabot opened. `push` takes `branches: ['dependabot/**']` and does exactly that;
  `pull_request`'s `branches:` filters the **base** branch, not the head, so there is no
  trigger-level way to say "only pull requests *from* `dependabot/**`". Without a filter
  the workflow would run a second, redundant build on every human pull request into
  `main`. Separately, passing `CODECOV_TOKEN` through this workflow looked consistent with
  `ci-main-build.yml` but cannot work. **Cause:** a Dependabot-triggered run gets a
  read-only `GITHUB_TOKEN` and a **separate** secrets store (Dependabot secrets, not
  Actions secrets), so an Actions secret referenced here resolves to empty.
- **Resolution / status:** **RESOLVED with a job-level `if:` and no secrets at all.** The
  head-branch test lives on the job:
  `if: ${{ github.event_name == 'push' || startsWith(github.head_ref, 'dependabot/') }}`.
  On a human pull request the job is **skipped, not run** — and a skipped job still
  reports, so nothing hangs; `ci-main-build.yml` covers those pull requests. The
  `uses: ./.github/workflows/workflow-build.yml` call passes **no `secrets:` block
  whatsoever**, which is only valid because `workflow-build.yml` declares
  `CODECOV_TOKEN` with `required: false` — had it been required, every Dependabot run
  would fail at workflow resolution rather than at the upload step. Coverage still runs
  and the gates still apply; only publishing is absent. The concurrency key is
  `${{ github.workflow }}-${{ github.head_ref || github.ref_name }}`: `head_ref` on
  `pull_request`, `ref_name` on `push`, both resolving to the same Dependabot branch name
  so the two events for one branch share a group instead of racing each other.

---

## `--continue` is load-bearing on the coverage command, not a convenience flag

- **Symptom / trigger:** The coverage command in `workflow-build.yml` is
  `./gradlew verifyCoverageReports koverVerify koverHtmlReport -PexcludeTags=integration --continue`.
  Dropping `--continue` — which reads like a tolerance flag nobody needs in CI — silently
  removes the diagnostics on exactly the runs that need them most. **Cause:** Gradle's
  default is fail-fast: the first failing task aborts the build and every task after it is
  skipped. `koverVerify` is the coverage *gate*, and `koverHtmlReport` comes after it. So a
  build that trips the gate never generates the report, the Codecov upload finds nothing to
  send, and the one build where you want to see which lines are uncovered is the one build
  that produces no coverage artefact.
- **Resolution / status:** **RESOLVED — `--continue` stays, and the reason is an inline
  comment on the step.** With it, a failed gate still lets `koverHtmlReport` run: the XML
  reports stay on disk for the Codecov upload, the HTML report is still uploaded as a
  build artefact, and the job still fails (`--continue` changes *how much runs*, never the
  build outcome). Requirements 4.8 and 5.1 only hold together because of this flag — the
  gate enforcement and the coverage publish would otherwise be mutually exclusive on a
  failing build. One invocation, three XML reports, three gates.

---

## Badges that render unresolved on day one — and are kept anyway

- **Symptom / trigger:** Four of the README badges point at signals that do not exist yet
  at the moment the badge block is added, so a fresh clone shows a badge row with
  placeholder text in it. The temptation is to delete or comment out the unresolved ones
  until their data appears. **Cause:** each badge depends on a maintainer action or a
  first event that has not happened yet — not on anything wrong in the repository. Named
  exactly as they appear in the README badge block:
  1. **`GitHub release`** — renders as **"no release"**. Unblocked when the first
     `vMAJOR.MINOR.PATCH` tag is pushed and a GitHub release exists for it; no checklist
     item covers it, it is simply the first release.
  2. **`Maven Central`** — renders as **unresolved / "not found"** for
     `nl.vintik:aws-lambda-streaming-core`. Unblocked when the first `v*` tag triggers
     `workflow-publish.yml` and the version is indexed on Maven Central. (Indexing lags
     the publish by minutes to hours, so "unresolved" briefly survives a successful
     publish.)
  3. **`codecov`** — renders as **"unknown"**. Unblocked by maintainer setup checklist
     **items 1 and 2** (create the Codecov project, then store `CODECOV_TOKEN` as a
     repository secret) followed by one push to `main`; until item 2 lands, the
     eligibility step skips the upload with a `::notice::` naming that item.
  4. **`CodeQL`** — renders as **"no status"**. Unblocked by maintainer setup checklist
     **item 7** (enable code scanning via the advanced workflow, leaving GitHub's default
     CodeQL setup off) plus the first `codeql.yml` run on `main`; the badge is filtered to
     `?branch=main&event=push`, so runs on other branches do not resolve it.
- **Resolution / status:** **EXPECTED, not broken — all four badges stay.** A badge is a
  status report; removing or commenting one out to make the row look clean would be
  falsifying the report, and the unresolved rendering is itself accurate information about
  a repository whose setup is incomplete. The verification script treats these four as
  **`PENDING`**: reported, excluded from the failure count, never a build failure — and it
  asserts its own pending list agrees with **this entry**, so the two cannot drift. Each
  badge resolves on its own, with no README edit, the moment its signal exists.

---

## Excluded badges and tooling — recorded so the omissions do not read as oversights

- **Symptom / trigger:** The badge row is deliberately shorter than MockNest's, and three
  tools a reader might expect are absent. Without a note, each absence looks like
  something that was forgotten. **Cause:** each is a settled decision, not a gap.
- **Resolution / status:** **DELIBERATE — three exclusions, recorded once here:**
  1. **Both OpenSSF badges — Scorecard and Best Practices — are excluded together with
     the workflows they would require.** Excluded by the requester. No `scorecard.yml`, no
     `bestpractices.dev` reference, and the verification script actively asserts that no
     substring of the README matches `securityscorecards` or `bestpractices.dev`,
     **rendered or commented out** — a commented-out badge is treated as a violation, not
     as a harmless leftover.
  2. **The `Maven Central` badge replaces MockNest's AWS SAR badge.** MockNest publishes a
     Serverless Application Repository application; this repository publishes a library to
     Maven Central instead, so the SAR badge has no counterpart to point at. It is a
     substitution, not a removal.
  3. **Codacy is not used.** No Codacy config, project, or badge. Code quality here is
     carried by the three per-module coverage gates, CodeQL, CodeRabbit and Snyk, all of
     which are already represented in the badge row or in `SECURITY.md`'s tooling table;
     adding a fourth overlapping grade service would add a third-party dependency to the
     README without adding a signal.

  (The OpenAPI/Swagger badge, semantic-release, deploy-on-`main` push and Dependabot
  auto-merge are excluded too, but those are scope decisions recorded in the design and in
  `SECURITY.md`'s "not used, and why" list rather than build-time gotchas.)

---

## Known documentation inaccuracy: `codeql.yml`'s comment about `./gradlew assemble`

- **Symptom / trigger:** `.github/workflows/codeql.yml` carries a comment stating that
  `./gradlew assemble` "does build both shadow jars". A `--dry-run` of the task graph
  contradicts it: `assemble` resolves to `compileKotlin` / `compileJava` / `jar` per
  module and **no `shadowJar` task appears at all**. **Cause:** the Shadow plugin does not
  wire `shadowJar` into the `assemble` lifecycle task by default, so `assemble` builds the
  thin jars only. The comment describes an assumption about the plugin rather than the
  observed task graph.
- **Resolution / status:** **OPEN — comment only, no functional impact.** The CodeQL
  extractor needs *compiled sources*, not shaded output, and `assemble` compiles every
  source set in all three modules, so extraction gets full source coverage and Requirement
  6.2 still holds. The inaccuracy is purely in the comment text. Correcting it was
  **explicitly out of scope** for the task that found this (no workflow edit was
  permitted), so it is recorded here to avoid losing it: the fix is a one-line comment
  change in `.github/workflows/codeql.yml`. Anyone who later needs the fat jars in that
  workflow must name `shadowJar` explicitly rather than relying on `assemble`.

---

## What can only be verified after merge — the post-merge observation list

- **Symptom / trigger:** The pre-merge gate is green and proven: `./scripts/verify-quality-signals.sh`
  in full mode exits 0 with **119 passed, 0 failed, 2 pending, 1 skipped**, including
  `check_sam_templates` (both templates `sam validate` exit 0) and `check_gradle_build`
  (`./gradlew build -PexcludeTags=integration` exit 0, `verifyCoverageReports` exit 0, all three
  coverage reports present), and `./gradlew :streaming-core:apiCheck` exits 0. A green local run
  reads like "everything is verified", which it is not. **Cause:** a whole class of signals in this
  feature is produced by GitHub, Codecov and Dependabot *in response to a merge* — a badge image
  that has no workflow run to point at, an upload that needs a token stored on the repository, a
  code-scanning result that needs the workflow enabled. None of them can be observed from a working
  tree, no matter how thorough the script is. The only non-PASS results in the pre-merge run are of
  exactly this kind: `CodeQL` image and target **PENDING** (blocked by maintainer checklist item 7)
  and `Build Status` image **SKIP** (HTTP 404).
- **Resolution / status:** **EXPECTED — recorded here so nothing in this list is mistaken for a
  regression later.** Each item below names what to observe and where. Nothing here requires a
  README or script edit; each resolves on its own once its signal exists.
  1. **Badge rendering for the four day-one-unresolved badges** — `GitHub release`,
     `Maven Central`, `codecov`, `CodeQL`. Observe the rendered README on `main`. Note the
     shields.io nuance: `GitHub release` and `Maven Central` both return **HTTP 200** because
     shields renders a "no release" / "not found" badge rather than erroring, so they **PASS**
     reachability while still being visually unresolved. Reachability is not resolution; only the
     rendered README tells you whether a badge reads a real value. These two resolve after the
     first `vX.Y.Z` tag and publish.
  2. **The first Codecov upload from `main`, and the badge turning from "unknown" to a
     percentage** — needs maintainer checklist items **1** and **2** (Codecov project plus the
     stored token). Observe the Codecov project page for the first report, then the `codecov`
     badge in the README.
  3. **CodeQL results under Security → Code scanning for both analyses** — the `java-kotlin`
     and `actions` matrix entries, checklist item **7**. Also unverifiable until then: **whether
     the shipped CodeQL bundle supports the Kotlin 2.3.0 extractor.** If the `java-kotlin` matrix
     entry fails on an unsupported language level, apply the design's escalation ladder — first
     pin `tools:` to a bundle that does support it, and only if that fails reduce the matrix to
     `actions` only — and log the exact Kotlin / JDK / CodeQL bundle combination that failed here,
     since that combination is the whole finding.
  4. **The dependency-graph submission naming the commit SHA and carrying entries for all three
     modules** — checklist item **3**. Observe Insights → Dependency graph after the submission
     workflow runs on `main`; the snapshot must be attributed to the merge commit SHA, not to a
     detached or synthetic ref.
  5. **Whether Dependabot accepts `multi-ecosystem-groups`** — observe the Dependabot log
     (Insights → Dependency graph → Dependabot) for a config-parse error. If the key is rejected,
     apply the per-ecosystem fallback: the same weekly **Monday 06:00 Etc/UTC** schedule, the same
     labels, a `chore` commit prefix **with scope**, and an open-PR limit of **5** per ecosystem —
     and log that the fallback was taken, because that is the interesting result.
  6. **The first Dependabot pull request turning `ci-dependabot-validation.yml` green with the
     Codecov step skipped, and carrying its labels** — needs checklist item **6** (the labels must
     exist before Dependabot can apply them). Observe the PR's checks tab: the Codecov step must
     report as *skipped* rather than failing on a missing token, since `secrets` are not available
     to Dependabot-triggered runs.
  7. **The Gradle wrapper JAR checksum matching a published Gradle release** — observe
     `gradle-wrapper-validation.yml` on the first pull request. The action compares against
     Gradle's published checksum list, which is a network fact and cannot be asserted locally.
  8. **The required-status-check blocking behaviour** — checklist items **11** and **12**.
     Observe branch protection on `main` and then a deliberately failing check: the merge button
     must actually be blocked. A configured check that does not block is indistinguishable from no
     check at all.
  9. **The `Build Status` badge image** — it 404s until `ci-main-build.yml` has run on `main`, so
     `check_badge_urls` reports **SKIP** with that reason rather than a failure. It resolves on
     merge, with the first run of that workflow.

---

## `check_badge_urls` cannot catch a wrong-but-well-formed shields.io path

- **Symptom / trigger:** Found while proving each check fails on a deliberate defect (task 15.13).
  The deliberate defect was a nonsense badge path,
  `https://img.shields.io/nonexistent-endpoint-xyz/kotlin-2.3.0-blue.svg`, which was expected to
  404 and fail the reachability check. It returns **HTTP 200** — shields.io renders an *error
  badge* image instead of refusing the request — so `check_badge_urls` passed on a badge URL that
  is unambiguously wrong. **Cause:** shields.io answers any well-formed request with a valid SVG,
  including one describing its own error, so an HTTP status code cannot distinguish a real badge
  from an error badge. Only an unresolvable host or a genuine non-2xx response fails the check.
- **Resolution / status:** **KNOWN LIMITATION — not a defect, and not a gap in coverage.**
  Reachability is deliberately the weakest of the three badge checks, and the other two close the
  hole: `check_badge_block` asserts the whole badge block against an **exact literal**, so any
  altered path is a mismatch, and `check_badge_sources` asserts each badge's **value against its
  source of truth** (the version catalog, the coverage gates, the workflow filenames). A wrong
  shields path is therefore caught by those two checks rather than by reachability. `check_badge_urls`
  remains worth keeping for what it does prove — that the badge hosts are reachable and that no
  badge points at a dead endpoint — as long as nobody reads its PASS as "this badge renders a real
  value".

---

## Dependabot rejected the whole config: `schedule` duplicated on a grouped update entry

- **Symptom / trigger:** GitHub refused `.github/dependabot.yml` outright with "Your
  `.github/dependabot.yml` contained invalid details". Not a partial failure — no Dependabot run
  happened at all, so there were no pull requests and no per-ecosystem errors to read. The real
  difficulty is that the message **names no key**: a generic validation error gives you nothing to
  grep for, in the file or in the schema. **Cause:** `schedule` was set on the
  `multi-ecosystem-groups` group **and** duplicated on every update entry. The Dependabot 2.0
  schema states the rule in its own comment on the update entry — schedule is required *unless*
  `multi-ecosystem-group` is specified — and GitHub's documented examples for grouped ecosystems
  show update entries carrying only `package-ecosystem`, `directory`/`directories`, `patterns` and
  `multi-ecosystem-group`, never `schedule`. The group owns the cadence; repeating it on a grouped
  entry is the deviation.
- **Resolution / status:** **FIXED** — `schedule` removed from both update entries (`gradle` and
  `github-actions`) and kept only on the `all-dependencies` group, which remains the single source
  of the weekly **Monday 06:00 Etc/UTC** cadence. Two follow-ups worth carrying forward. First,
  **how to get the specific reason next time:** the red banner is generic, but the repository's
  Insights → Dependency graph → **Dependabot** view — and the GitHub file view of
  `.github/dependabot.yml` itself — surfaces the actual validation detail, so go there rather than
  guessing from the banner. Second, `multi-ecosystem-groups` was **not** abandoned: it is a real,
  current feature and the overall shape of the file was right. The design's **per-ecosystem
  fallback** (one `groups:` entry per ecosystem, same schedule, same labels, `chore` prefix with
  scope, open-PR limit 5) is still the escalation, and is the thing to apply if the corrected
  config is rejected again.

---

## `resolvedCoordinates` could write a clean baseline from a broken dependency graph

- **Symptom / trigger:** Nothing visible — which is the problem. The graph walk in
  `resolvedCoordinates` filtered its edges with `filterIsInstance<ResolvedDependencyResult>()`,
  which silently discards every `UnresolvedDependencyResult`. **Cause:**
  `configurations.named(name).incoming.resolutionResult` does **not** throw on an unresolved
  dependency; it represents the failed edge as a node in the graph. So a genuinely broken
  resolution produced no error and no missing-line signal, just a snapshot with the broken edge
  omitted — and since the baseline's whole value is that an empty `diff -ru before after` proves
  resolution is unchanged, the diff could pass on a tree whose resolution was actually broken.
  Third instance of the same silent-false-pass class in this file's tooling, after the
  `outputs.upToDateWhen { false }` guards on `resolvedCoordinates` and `verifyCoverageReports`.
- **Resolution / status:** **FIXED** — the walk now branches exhaustively over each
  `DependencyResult` (`else -> {}` included, since `DependencyResult` is not sealed to Kotlin) and
  accumulates unresolved edges across all four configurations. If that list is non-empty the task
  calls `error(...)` **before** writing the snapshot file, so nothing derived from a broken graph
  lands on disk, and the message names the configuration, the attempted coordinate
  (`attempted.displayName`) and the resolution `failure.message` for every failed edge. Output on a
  healthy tree is byte-identical to before the change.

---

## A repo rename silently broke GitHub OIDC: immutable subject claims stop matching name-based `sub`

- **Symptom / trigger:** Every GitHub Actions deploy failed at the credentials step with
  `Could not assume role with OIDC: Not authorized to perform sts:AssumeRoleWithWebIdentity`.
  What made this confusing is that everything you would normally check was *correct*: the role
  existed, the OIDC provider existed, and the live trust policy on the role matched
  `deployment/aws/oidc/github-oidc-role.yaml` exactly — so there was no drift to find and nothing
  in AWS looked wrong. The only clue was the timeline: last success 2026-08-02, failing from
  2026-08-16. **Cause:** the repository had been renamed
  (`aws-lambda-streaming-jvm-runtime` -> `aws-lambda-response-streaming-jvm`; the git remote still
  points at the old name, which now only redirects), and a rename after 2026-07-15 moves a repo
  onto GitHub's **immutable subject claims**. The token's `sub` is then
  `repo:<owner>@<owner_id>/<repo-name>@<repo_id>:<scope>`, not `repo:<owner>/<repo-name>:<scope>`.
  The trust policy only carried the legacy name-based patterns, so no `sub` pattern matched and STS
  denied — the failure is a policy mismatch, not a missing permission, which is why nothing looked
  misconfigured. Get the authoritative prefix from GitHub rather than guessing:
  `gh api /repos/OWNER/REPO/actions/oidc/customization/sub` returns `use_immutable_subject: true`
  and the exact `sub_claim_prefix` (here
  `repo:elenavanengelenmaslova@113334433/aws-lambda-response-streaming-jvm@1261291270`).
- **Resolution / status:** **FIXED IN TEMPLATE, NOT YET LIVE.** The three `StringLike` `sub`
  patterns now use `repo:${GitHubOrg}@${GitHubOwnerId}/*@${GitHubRepoId}:...` with the same three
  scopes (`ref:refs/heads/main`, `ref:refs/heads/feature/*`, `environment:*`), via two new
  parameters `GitHubOwnerId` (`113334433`) and `GitHubRepoId` (`1261291270`); `GitHubRepo` default
  moved to the canonical `aws-lambda-response-streaming-jvm`. Both immutable IDs are pinned and
  only the mutable name segment is wildcarded — still exactly one repository under one owner, but
  it survives the next rename. The legacy patterns were deleted rather than kept alongside: a stale
  `repo:<owner>/<old-name>` pattern would extend this role's trust to any future repository the
  owner creates reusing that name. **The template edit has no effect until the OIDC CloudFormation
  stack is redeployed** — the live role keeps the old trust policy, so deploys keep failing with
  the same error until then.

---

## Fixing the OIDC template was not enough: the deploy path never passed the new parameters

- **Symptom / trigger:** The trust-policy fix above looked complete, but a redeploy would have
  changed nothing observable. **Cause:** `scripts/setup-oidc.sh` is the deploy path for the OIDC
  stack, and its `--parameter-overrides` string only carried `GitHubOrg` and `GitHubRepo`. The new
  ID parameters were never passed, so CloudFormation would have silently fallen back to the
  template defaults — correct for this repository by luck, wrong for any fork, and invisible either
  way because a `sub` mismatch surfaces only as a generic `sts:AssumeRoleWithWebIdentity` denial at
  the next workflow run. The script also still defaulted `DEFAULT_REPO` to the stale
  `aws-lambda-streaming-jvm-runtime`. The deeper hazard was the reverse direction: hardcoded
  numeric IDs make the template immutable-only, so a fork or any repository still on the legacy
  name-based claim format would get a policy that can never match — again with no error at deploy
  time.
- **Resolution / status:** **FIXED** — the two ID parameters collapsed into one `SubjectPrefix`
  (default `repo:elenavanengelenmaslova@113334433/*@1261291270`) and the three `StringLike`
  patterns are now `!Sub '${SubjectPrefix}:<scope>'`. One parameter rather than hardcoded IDs is
  the point: the prefix is exactly what GitHub reports, and it expresses both claim formats.
  `scripts/setup-oidc.sh` now derives it per repository — `gh api
  /repos/OWNER/REPO/actions/oidc/customization/sub` for `use_immutable_subject`, plus `gh api
  /repos/OWNER/REPO` for `.owner.id` and `.id` — building
  `repo:<owner>@<owner_id>/*@<repo_id>` on immutable claims and `repo:<owner>/<repo>` (with a
  printed note) on legacy, overridable by a pre-set `SUBJECT_PREFIX` env var when `gh` is absent.
  It prints the resolved prefix and all three `sub` patterns in the Configuration block before
  asking to continue, so a mismatch is visible *before* the deploy instead of at the next workflow
  run. Also fixed two latent bugs in the same script: the `if [ $? -eq 0 ]` check after
  `aws cloudformation deploy` was dead code under `set -e` (the deploy now runs under `if ! ...`
  so a failure actually prints the red message and exits non-zero), and the deliberately unquoted
  `$PARAMS` word-splitting is now documented with a `# shellcheck disable=SC2086`. Added
  `--yes`/`-y` and `OIDC_SETUP_NON_INTERACTIVE=1` for unattended runs.

---

## `CI - Dependabot Validation` runs twice on one Dependabot branch — one cancels the other

- **Symptom / trigger:** A single Dependabot update (branch
  `dependabot/gradle/gradle-747a0582b4`) produced **two** `CI - Dependabot Validation` runs
  three seconds apart — the `push` run was cancelled and the `pull_request` run went green.
  **Cause:** the workflow triggered on **both** `push` to `dependabot/**` **and** `pull_request`
  to `main`, and a Dependabot update fires both (it pushes the branch *and* opens the PR). The
  concurrency key `${{ github.workflow }}-${{ github.head_ref || github.ref_name }}` resolved to
  the **same** value for both events (`head_ref` on the pull_request, `ref_name` on the push —
  both the dependabot branch name), so with `cancel-in-progress: true` the second event cancelled
  the first. The original concurrency comment claimed sharing a group made the two events "share
  instead of race" — the reasoning was backwards: sharing a group *with* cancel-in-progress is
  exactly what cancels one.

- **Resolution / status:** **Resolved.** Dropped the redundant `push:` trigger — Dependabot always
  opens a PR, so `pull_request` to `main` already covers every update with exactly one run. With
  only `pull_request` left, the job guard simplifies from
  `${{ github.event_name == 'push' || startsWith(github.head_ref, 'dependabot/') }}` to
  `${{ startsWith(github.head_ref, 'dependabot/') }}` (still skips a human PR into main, which
  `ci-main-build.yml` covers), and the concurrency key drops the now-pointless push fallback to
  `${{ github.workflow }}-${{ github.head_ref }}`. `cancel-in-progress: true` stays — it now only
  coalesces successive synchronises of the *same* PR, which is correct. One behavioural
  consequence worth noting: a `dependabot/**` branch *pushed with no PR* would no longer be
  validated — which never happens, because Dependabot always opens a PR.

---

## CodeQL `java-kotlin` intermittently fails: "no source code seen during build" / finalize exit 32

- **Symptom / trigger:** The `Analyze (java-kotlin)` job in `codeql.yml` failed
  intermittently with `CodeQL could not process any code written in Java/Kotlin`
  ("no source code seen during build") and `database finalize` exit code 32 — passing on
  a cold cache but failing on a warm one. **Cause:** the `java-kotlin` leg uses CodeQL
  `build-mode: manual`, whose tracer only records source when a compiler actually executes
  under it. The build step ran `./gradlew assemble --stacktrace`, but
  `gradle/actions/setup-gradle` restores the Gradle build cache, so with a warm cache every
  compile task resolved `FROM-CACHE` (`:streaming-core:compileKotlin`,
  `:streaming-s3-example:compileKotlin`, `:streaming-s3-example-java:compileJava`) and no
  compiler ran — yielding an empty database. GitHub's own troubleshooting doc attributes the
  error to a build that compiles no code under the tracer, which is exactly why it passed
  cold and failed warm.

- **Resolution / status:** **Resolved.** Changed the CodeQL build step to
  `./gradlew clean assemble --no-build-cache --stacktrace`: `clean` defeats up-to-date
  checks on prior `build/` outputs and `--no-build-cache` stops classes being pulled from the
  restored Gradle build cache, so `compileKotlin`/`compileJava` genuinely execute under the
  tracer. `setup-gradle` is kept (toolchain provisioning and dependency caching are still
  wanted); only the compile-output cache is bypassed, and only for this one step — no other
  workflow's cache behaviour changes. Also corrected the stale step comment, which claimed
  `assemble` "does build both shadow jars": it does not — `assemble` builds the thin
  per-module `jar`, not `shadowJar` (this doc inaccuracy was noted earlier and is now fixed in
  the comment). Cannot be fully proven until it runs in CI on a warm cache, which only happens
  post-push.

# Plan
Right now it's somewhere between:

tutorial
implementation log
library announcement

I'd make it primarily an engineering article, with the library as the outcome.

That way people don't feel they're reading documentation.

I would structure it like this
1. Introduction (already done)
   Why MockNest needed streaming
   Salesforce Bulk API
   6 MB limit
   No JVM solution
   Built a library

✅ I think this part is done.

2. Why response streaming is different

Don't jump into RequestStreamHandler immediately.

Instead explain the mental model.

Buffered Lambda

request
↓
build response
↓
return response

Streaming Lambda

request
↓
commit status
↓
stream bytes
↓
close stream

Explain why this changes everything:

status committed early
memory
flushing
validation

Then every later section makes sense.

3. Moving to RequestStreamHandler

Very short.

Not pages.

Just

RequestHandler
RequestStreamHandler
InputStream
OutputStream

Done.

4. The missing protocol

This should be the "wow" section.

metadata JSON

8 null bytes

body

Explain

AWS hides this for Node.

JVM developers have to write it.

Then immediately introduce ResponseWriter.

Not 100 lines later.

Example:

writer.writeMetadata(...)

copy(...)

Then explain that the implementation lives in the library.

5. Streaming correctly

This becomes one chapter.

Instead of

Step 4

Step 5

Step 6

I'd merge them.

Topics:

Don't buffer
readBytes()

bad

copy()

good

Validate first

because status commits

Flush

because clients otherwise don't observe progress

This is one conceptual lesson:

Once streaming starts, think like a stream.

6. Infrastructure surprises

This is where your development log shines.

I'd move these together.

STREAM vs RESPONSE_STREAM
response-streaming-invocations URI
OutputStream.close()
HTTP/2 curl issue

These are deployment lessons.

People love those because they waste days.

7. Testing

Current testing section is excellent.

I'd barely change it.

Maybe add one nice diagram.

Unit

↓

Integration

↓

Post Deploy

That section is one of the strongest in the article.

8. The library

Only now.

Reader already understands the problem.

Now say

"I extracted two reusable pieces."

Not

"I published a library."

Explain only

ResponseWriter
copy()

Done.

The README is documentation.

The article should not become documentation.

One small example.

One Maven dependency.

One link.

9. Lessons learned

I'd replace the current lessons completely.

Instead I'd have something like

Lessons learned
1. Response streaming is more than changing the handler

Changing RequestHandler is maybe 5%.

The protocol, infrastructure and testing matter more.

2. AWS's Node.js helper hides a surprising amount

JVM developers need to understand the protocol.

3. Streaming and memory are different problems

Many people "stream"

but still

readBytes()
4. Status codes become immutable

Validate first.

5. Test the platform, not just your code

Probably my favourite lesson.

6. Infrastructure naming matters

STREAM

RESPONSE_STREAM

One word.

One day lost.

10. Conclusion

Current one is good.

Things I would REMOVE

I think there are a few places where the article starts feeling like README documentation.

For example

API reference

ResponseWriter

copy()

DELIMITER_LEN

...

This belongs in GitHub.

Not Medium.

Likewise the long explanation of

ResponseMetadata.fromMultiValue()

I would replace that with one sentence:

The library also handles repeated headers and cookies correctly according to API Gateway's metadata format.

Then link to GitHub.

Things from the development log I'd definitely include

These are excellent because they're not documented elsewhere:

✅ STREAM vs RESPONSE_STREAM

✅ OutputStream.close() or partial responses

✅ headers must be Map<String,String>

✅ curl HTTP/2 issue

Those are exactly the kind of things people search for after spending hours debugging.

I would not include:

Kover
Mockito
MockK
Java 25
JaCoCo
Gradle

Those are interesting for the project, but they distract from the core story of Lambda response streaming.

One final thought

I think your article's unique value isn't "how to stream from S3."

It's:

"Everything I had to learn to implement AWS Lambda response streaming on the JVM because AWS only provides a high-level helper for Node.js."

That framing is what makes it likely to become the article people find when they search for Kotlin or Java Lambda response streaming. It also naturally leads readers to your library as the reusable implementation, rather than making the article feel like a library announcement.

---

## `GITHUB_TOKEN`-pushed tags don't fire `on: push` tag workflows — the publish hand-off needs `RELEASE_PAT`

- **Symptom / trigger:** The release pipeline (`release-tag.yml`) hands off to the
  existing `workflow-publish.yml` purely by **pushing a `vX.Y.Z` tag** — that workflow
  already triggers on `push:` tags `v*` and must not be modified. The obvious wiring is
  to let the `tag` job push with the default `GITHUB_TOKEN`. It silently never fires the
  publish run. **Cause:** GitHub deliberately suppresses workflow triggers for events
  created by the default `GITHUB_TOKEN`, to prevent a workflow from recursively
  triggering itself. A tag pushed by one workflow using `GITHUB_TOKEN` therefore does
  **not** raise the `push` tag event that `workflow-publish.yml` waits on, so Maven
  Central publishing never starts. Nothing errors — the tag appears, the release run goes
  green, and the missing publish only shows up as "the version was tagged but never
  reached Maven Central".
- **Resolution / status:** **RESOLVED — `RELEASE_PAT` is required, with no fallback.**
  The `tag` job checks out, pushes and calls `gh` with `secrets.RELEASE_PAT` only. A tag
  pushed under a personal access token is an ordinary external push and **does** raise
  the `push` tag event, so `workflow-publish.yml` fires and publishes. The first version
  of the pipeline fell back to `GITHUB_TOKEN`, which was a trap of its own: the run would
  create a tag and a Release that never reach Maven Central, and because the tag then
  exists, the next run would not try again. A preflight step now fails the job before
  anything is written when the secret is missing. Referenced by name only; no token value
  is recorded here (Req 7.3).

---

## `GITHUB_TOKEN`-opened PRs start their checks only after a manual approval

- **Symptom / trigger:** After the tag is cut, the `readme-pr` job opens a
  `release/vX.Y.Z` pull request that bumps the README dependency coordinate. Under branch
  protection on `main` it must pass the required status checks before it can merge. If
  the PR is opened with the default `GITHUB_TOKEN`, its `pull_request` runs are created
  in an **approval-required** state: the merge box shows a banner, and nothing runs until
  a user with write access selects **Approve workflows to run**. The first draft of the
  design assumed the checks would never report and needed an admin override. That was
  wrong, at least under current GitHub behaviour
  ([GITHUB_TOKEN docs](https://docs.github.com/en/actions/concepts/security/github_token)).
  Tag pushes are different: `GITHUB_TOKEN` still never triggers `on: push` tag workflows
  (see the entry above).
- **Resolution / status:** **RESOLVED — the PR is opened with `RELEASE_PAT`.** A PR opened
  under a personal access token starts its checks straight away, with no approval step.
  The PAT is not the only way to start them: approving the runs by hand works too. It is
  used here because `RELEASE_PAT` is required for the tag push anyway, and it keeps the
  README PR hands-off. Token referenced by name only (Req 7.3).

---

## `chore(deps)` / `build(deps)` are promoted to a **patch** bump — a repo-specific deviation from vanilla Conventional Commits

- **Symptom / trigger:** `compute-version.sh` derives the next SemVer bump from the
  Conventional Commits since the latest tag. Under **vanilla** Conventional Commits a
  `chore:` commit carries **no** version bump — it is explicitly a non-releasing type. But
  this repository's releases are meant to pick up **Dependabot** dependency updates, and
  Dependabot writes its updates as `chore(deps): ...`, `chore(deps-dev): ...`, and
  `build(deps): ...`. If those followed the vanilla rule they would produce **no bump**,
  so a run whose only changes were dependency upgrades would compute `bump=none` and cut
  no release — the dependency bumps would silently never reach a published version.
- **Resolution / status:** **RESOLVED — a documented, repo-specific promotion rule.**
  `compute-version.sh` classifies commits as follows:
  - plain **`chore:`** (and other non-releasing types) → **no bump** (vanilla behavior,
    unchanged);
  - the **dependency scopes** `chore(deps)`, `chore(deps-dev)`, `build(deps)` (and
    `build(deps-dev)`) → promoted to **`patch`**.
  This is **not** standard Conventional Commits — it is a deliberate local convention so
  that a Dependabot-only commit set still cuts a patch release. The promotion is applied
  inside the same aggregate commit set and under the same max-severity precedence
  (major > minor > patch) as every other commit (Req 2.6, 2.7): e.g. a set of
  `{fix:, feat:, chore(deps):}` still resolves to `minor` (the `feat:` wins), while a set
  of only `{chore(deps):}` resolves to `patch` rather than `none`. The rule is flagged
  with an inline comment at the classification site in `compute-version.sh` so the
  deviation from vanilla Conventional Commits is obvious to anyone reading or editing the
  script. **Takeaway for the article:** if automated dependency PRs are meant to trigger
  releases, the version-computation step has to opt their `chore(deps)`/`build(deps)`
  scopes into a bump explicitly — the Conventional Commits spec leaves `chore` as
  non-releasing by default.

---

## No runtime secrets recorded for the release pipeline — tokens referenced by name only

- **Symptom / trigger:** The release pipeline is credential-sensitive: it pushes tags,
  creates GitHub Releases, and opens pull requests, all of it done under the required
  `RELEASE_PAT` (see the two token gotchas above). The temptation when
  documenting the hand-off is to paste an example token, a token prefix, or the exact
  secret value used during a live validation so the write-up is concrete.
- **Resolution / status:** **Honored the steering rule — nothing secret is written
  here.** Per the log's own header ("No runtime secrets") and the product/structure
  steering, this entry (and the two above) refer to credentials **by name only**
  (`GITHUB_TOKEN`, `RELEASE_PAT`) and never record a token value, prefix, scope string, or
  any other secret material. The `RELEASE_PAT` is created and stored by a repo
  administrator as a repository secret during the one-time maintainer setup (task 9); its
  value lives only in GitHub's encrypted secret store, never in this log, the workflow
  files, or any script. `docs/log.md` is for developer gotchas, not for runtime secrets
  (Req 7.3).

---

## Maintainer handoff: `RELEASE_PAT` setup and the one-time live end-to-end release validation

- **Symptom / trigger:** The release pipeline (`release-tag.yml` → `compute` → `tag` →
  `readme-pr`, handing off to the untouched `workflow-publish.yml`) is proven only as far
  as a working tree can prove it: every release script and the static path-filter
  assertion pass (`bash scripts/release/test/run-tests.sh` → 4 files pass, 0 fail), and
  the two token gotchas above explain *why* the pushed tag and the opened PR need
  `RELEASE_PAT`. What a green local run **cannot** prove is the live hand-off: that a real
  `vX.Y.Z` tag actually fires `workflow-publish.yml`, that the GitHub Release carries
  non-empty generated notes, that the README PR starts its required checks without approval, and —
  critically — that merging that README PR does **not** start a *second* release run (the
  loop guard). Those are produced by GitHub in response to a real merge and are
  observable only against the live repository, so they are deliberately left as **repo
  admin / maintainer actions**, not coding-agent tasks. The hazard is treating the green
  pre-merge suite as "the release pipeline is verified end to end" when the live hand-off
  is still unproven.
- **Resolution / status:** **MAINTAINER HANDOFF CHECKLIST — one-time, repo admin only.**
  Perform these in order against the live repository; none require a code or script edit.

  1. **Create the `RELEASE_PAT` repository secret (repo admin required).** Store a
     personal access token as the repository secret named `RELEASE_PAT`, scoped with
     `contents: write` **and** `pull-requests: write`. The pipeline requires it and fails
     its preflight without it: a tag **pushed** under `RELEASE_PAT` is an external push
     and fires `workflow-publish.yml` (a `GITHUB_TOKEN` push does not — see the tag-push
     gotcha above), and a README PR **opened** under `RELEASE_PAT` starts its required
     `pull_request` checks without the approval step a `GITHUB_TOKEN`-opened PR needs
     (see the PR gotcha above). **Status:** done; `gh secret list` shows `RELEASE_PAT`. Reference the secret
     **by name only**; its value lives solely in GitHub's encrypted secret store and is
     never recorded in this log, the workflows, or any script (Req 7.3).

  2. **Run the one-time live end-to-end validation.** Merge a `feat:`-prefixed (or
     `fix:`-prefixed) change that touches a file under `streaming-core/src/main/**` into
     `main`, so the `release-tag.yml` path filter actually fires, then confirm each of:
     - **Computed tag** — the `compute` job emits the expected `vX.Y.Z` (e.g. a `feat:`
       bumps the minor, a `fix:` the patch), and the `tag` job creates and pushes exactly
       that tag.
     - **GitHub Release with non-empty notes** — a Release exists for `vX.Y.Z` and its
       body is **non-empty** (the draft-first guard deletes the draft and fails the job
       rather than publishing an empty-notes release, so a published release proves the
       notes are present).
     - **Parallel publish run** — pushing the tag under `RELEASE_PAT` fires
       `workflow-publish.yml` (Maven Central publish), running alongside the release
       pipeline rather than requiring any manual trigger (Req 3.3, 4.4, 8.1).
     - **README PR** — the `readme-pr` job opens a `release/vX.Y.Z` pull request that
       bumps only the README dependency coordinate
       (`nl.vintik:aws-lambda-streaming-core:X.Y.Z`), is docs-only, and — opened under
       `RELEASE_PAT` — has its required checks running (Req 8.2).
     - **Loop guard** — **merge that `release/vX.Y.Z` README PR and confirm it does NOT
       start a new release run.** The README change lands outside the
       `streaming-core/src/main/**` / `streaming-core/build.gradle.kts` path filter, so
       `release-tag.yml` must not trigger. This is the single most important thing to
       observe: it proves the pipeline cannot recurse on its own README commit (Req 6.1).

  3. **Confirm the handoff gate before handing off.** All release scripts and the static
     path-filter assertion must be green first: `bash scripts/release/test/run-tests.sh`
     → **4 files pass, 0 fail** (`compute-version_test.sh`, `harness_selftest_test.sh`,
     `path-filter_test.sh`, `update-readme-version_test.sh`). Verified at this task's
     completion. If any question arises during the live run, stop and ask before cutting
     a real release — this entry documents the maintainer actions; it does **not** itself
     execute a release.

---

## `%s%n%b` turned body lines into subjects — a squash-merge body could force a major bump

- **Symptom / trigger:** `compute-version.sh` read `git log --format='%s%n%b'` and
  classified **every line** as if it were a commit subject. A `fix:` commit whose body
  listed squashed sub-commits (`* feat: …`, `refactor!: …`) was promoted to minor or
  major, and a plain `chore:` with `fix: …` in its prose cut a patch release. The
  existing fixtures never put a Conventional Commits prefix in a body, so the suite
  stayed green.
- **Resolution / status:** **RESOLVED.** The script now walks commits by SHA. The subject
  alone decides the type and the `!` marker. The body is scanned only for a
  `BREAKING CHANGE:` footer line. Three regression cases were added to
  `compute-version_test.sh`; two of them fail against the old script and pass now.

---

## An existing tag is not a finished release — re-runs reconcile instead of no-opping

- **Symptom / trigger:** The first version of the Req 3.4 guard treated "tag already
  exists" as "release done" and skipped everything after it. If a run pushed the tag and
  then failed creating the Release (or opening the README PR), **Re-run failed jobs**
  saw the tag and did nothing, leaving the release half-finished with no automated way to
  complete it.
- **Resolution / status:** **RESOLVED.** The guard now only skips the tag push. The
  Release step leaves a published Release alone, verifies and publishes a leftover draft,
  and creates the Release (`--verify-tag`) when it is missing. The `readme-pr` job skips
  when a PR for `release/vX.Y.Z` already exists (open or merged), reuses a pushed branch
  that has no PR, and otherwise creates the branch. If everything already exists, the run
  does nothing.

---

## `gradle/libs.versions.toml` added to the release trigger paths

- **Symptom / trigger:** A Dependabot dependency bump merged to `main` — which edited only
  `gradle/libs.versions.toml` — did not fire `release-tag.yml`. The path filter was
  `streaming-core/src/main/**` and `streaming-core/build.gradle.kts`: the shared version
  catalog was deliberately excluded because it covers all three modules, and a bump that
  affects only the example or test modules is not a library change. But in practice,
  Dependabot is the primary source of `streaming-core`'s transitive-dependency upgrades, and
  those upgrades edit only the catalog. With the catalog excluded, no dependency-only commit
  ever triggered a release.
- **Resolution / status:** **RESOLVED — `gradle/libs.versions.toml` is now a third trigger
  path.** The accepted tradeoff: a catalog bump that affects only the example/test/tooling
  modules (not the published `streaming-core`) will also fire the pipeline and compute a
  `chore(deps)` → patch release. The loop guard is unaffected — the README-only PR edits
  only `README.md`, which is none of the three trigger paths, so the structural guarantee
  holds. The path-filter static test (`path-filter_test.sh`) was updated to assert exactly
  three paths.

---

## `SonatypeHost` removed from vanniktech maven-publish plugin 0.34.0 — migration to the no-arg API

- **Symptom / trigger:** Dependabot PR #18 bumped `com.vanniktech.maven.publish`
  `0.31.0 → 0.37.0`. The build failed with `Unresolved reference 'SonatypeHost'` in
  `streaming-core/build.gradle.kts`. The plugin's 0.34.0 changelog says: "`SonatypeHost`
  has been removed from the DSL." Since 0.33.0 deprecated `SonatypeHost` and the only
  supported target is `CENTRAL_PORTAL`, the no-arg `publishToMavenCentral()` is the correct
  replacement.
- **Resolution / status:** **RESOLVED.** Removed the `SonatypeHost` import and changed
  `publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)` → `publishToMavenCentral()`. POM
  generation (`generatePomFileForMavenPublication`) confirmed correct (coordinate, version,
  MIT license all intact). The full test and coverage suite passes with the bumped plugin.

---

## `open-pull-requests-limit` on a multi-ecosystem-group entry invalidates the Dependabot config

- **Symptom / trigger:** The Dependabot page showed: *"open-pull-requests-limit should only
  be set on the associated multi-ecosystem group, not directly on updates that are part of a
  multi-ecosystem update group."* Every `Dependabot Updates` run in the repo's history was a
  `security` job; the scheduled version-update job had **never executed**, and zero
  dependency-update PRs had been opened. The config looked syntactically valid and no build
  failed, so the misconfiguration was invisible from the Actions tab — it only surfaced on the
  Dependabot page (Insights → Dependency graph → Dependabot).
- **Resolution / status:** **RESOLVED.** Removed `open-pull-requests-limit: 5` from both
  `updates:` entries. The default limit is 5 anyway, so no behavior change. After the fix
  landed on `main`, Dependabot immediately opened PR #18 — the first real version-update PR.
  Also created the missing `gradle` and `github-actions` repo labels (Dependabot silently
  drops labels it can't find, leaving PRs unlabelled).

---

## `InvokeMode` (Function URL) vs `ResponseTransferMode` (API Gateway) — same value, different knob

- **Symptom / trigger:** `RESPONSE_STREAM` appears in two unrelated places and it is tempting to treat them as the same switch. API Gateway's SAM `Api` event uses `ResponseTransferMode: RESPONSE_STREAM`; a Lambda **Function URL** uses `FunctionUrlConfig.InvokeMode: RESPONSE_STREAM`. They configure different layers and are not interchangeable.
- **Resolution / status:** Noted. For this project only the API Gateway knob matters — the Function URL path is not used on the managed JVM runtime (see the next entry).

---

## The managed JVM runtime does not stream over a Lambda Function URL — use API Gateway, or a custom runtime

- **Symptom / trigger:** Exposing the same `RequestStreamHandler` through a Lambda Function URL (`InvokeMode: RESPONSE_STREAM`, `AuthType: AWS_IAM`) returned a 21-byte `Internal Server Error` (HTTP 502) to a correctly SigV4-signed request, while the **same handler and jar** streamed a 15 MB object perfectly over API Gateway. The function logs showed no exception — it resolved the request, read S3, and returned cleanly.
- **Investigation:** A one-shot diagnostic logging the handler's `OutputStream` type on the deployed Function URL showed `outputClass=java.io.ByteArrayOutputStream` (ancestry `ByteArrayOutputStream -> OutputStream -> Object`, no `setContentType`). The shaded jar contained only `aws-lambda-java-core` — no `HttpResponseStream` / runtime-interface-client streaming class. AWS documents managed response streaming for Node.js only (opt in with `awslambda.streamifyResponse()`); for the JVM it directs you to a **custom runtime** (Runtime API or the Lambda Web Adapter).
- **Why API Gateway works anyway:** API Gateway's integration layer performs the streaming — it invokes the function with `InvokeWithResponseStream`, parses the hand-written `metadata JSON + 8 NUL + body` prelude, and re-streams the body to the client. The function does not need to be "response-streaming-enabled" for that path. A Function URL has **no integration layer**: it invokes the function directly and expects a streaming-enabled function, which the managed JVM runtime's plain `RequestStreamHandler` is not — so the prelude bytes are rejected with a 502.
- **Attempts that did NOT fix it (dead ends, recorded so they are not retried):** setting the `application/vnd.awslambda.http-integration-response` content type reflectively on the output stream (the stream is a `ByteArrayOutputStream` with no such method); forcing HTTP/1.1; widening IAM scope. None addressed the real cause.
- **Side gotchas found on the way (relevant only if you DO pursue a Function URL via a custom runtime):** (a) `curl --aws-sigv4` does not fold `x-amz-security-token` into the SIGNED canonical request, so temporary/OIDC credentials get a 403 — use `awscurl` (or a signer that signs the session token); (b) CloudFormation needs `lambda:Create/Get/Update/DeleteFunctionUrlConfig` on the deploy role to create the `AWS::Lambda::Url` resource, and `lambda:InvokeFunctionUrl` must cover the qualified `function:<name>:<alias>` ARN since the URL attaches to the SnapStart `live` alias.
- **Resolution / status:** **Resolved by scoping.** The Function URL variant was removed; the examples stream through **API Gateway only**. The library's `ResponseWriter` is unchanged — the limitation is in the managed JVM runtime, not the wire-format code. Documented in the README Limitations section.

---

## API Gateway streaming works over HTTP/2 — the `curl: (92)` error is client/environment-specific

- **Symptom / trigger:** An earlier note recorded `curl: (92) HTTP/2 stream not closed cleanly: INTERNAL_ERROR` against the streaming endpoint, and the pipeline/scripts force `--http1.1`. This could read as "the endpoint only works over HTTP/1.1".
- **Investigation:** Re-tested the deployed API Gateway endpoint against the 15 MB object with curl 8.7.1 (nghttp2 1.64.0): over HTTP/2 (both ALPN-negotiated and `--http2-prior-knowledge`) the request returned HTTP 200, full 15,728,640 bytes, byte-identical to the HTTP/1.1 download, with no error 92.
- **Resolution / status:** **Clarified.** HTTP/2 is not categorically broken; the `(92)` failure is specific to certain client/curl builds (e.g. the CI runner's version) and is a stream-termination quirk somewhere along the path, not a fault in the response bytes or the library. `--http1.1` remains a reliable workaround for clients that hit it. The post-deploy script now verifies BOTH HTTP/1.1 and HTTP/2 return a complete, byte-identical body so any regression is caught.

## Bedrock (Nova Lite) token streaming through API Gateway REST — terminal burst, not progressive

- **Symptom / trigger:** Open question — when a Lambda relays Bedrock
  `InvokeModelWithResponseStream` (Nova Lite) token deltas to its streaming
  `OutputStream` with a per-token `flush()`, behind API Gateway REST
  (`RESPONSE_STREAM`), does the client see tokens arrive progressively over time, or
  in a single terminal burst? Measured with a throwaway stack (`bedrock-stream-exp`,
  route `POST /exp/bedrock-stream`), warmup + 3 measured runs piped to a per-chunk
  elapsed-ms reader.

- **Resolution / status:** **Resolved (measured) — terminal burst at the client.**
  Two measurements were taken:
  - **Through API Gateway (client view):** all 3 runs received the full ~800-byte
    body as a single chunk in one 100 ms window; warmup showed
    `ttfb == total == 5.79s` (first byte and last byte at the same instant). One
    arrival bucket — a single terminal burst.
  - **Directly from Bedrock (no API Gateway, boto3 probe):** Nova Lite emitted
    **71 text deltas spread over ~607 ms across 6 distinct 100 ms buckets** —
    genuinely progressive, bursty-but-continuous.

  So Bedrock itself streams progressively, but its output at Nova Lite's natural
  cadence is **low-volume** (~800 bytes total), and the managed Java streaming
  runtime behind API Gateway REST buffers small, sub-threshold writes and releases
  them at invocation completion — regardless of the per-token `flush()`. This
  matches the established runtime fact for this repo: API Gateway forwards flushed
  bytes based on **write volume + invocation completion**, not on `flush()` while
  the handler is idle between small writes. The per-token flush does not help when
  each token is a handful of bytes.

  **Root cause (confirmed by an independent report):** the buffering lives in the
  **Lambda Response Streaming runtime itself**, below the language layer — NOT in the
  JVM, NOT in this library, NOT in API Gateway. The AWS Builder article
  ["The Curious Case of SSE Streaming Agent Responses on AWS Lambda"](https://builder.aws.com/content/39iqpehwjnn2TuNmGnHAnsMcHmP/the-curious-case-of-sse-streaming-agent-responses-on-aws-lambda)
  documents the same behaviour from a Python/FastAPI + Lambda Web Adapter stack: small
  chunks are held in an internal buffer and not flushed until the buffer fills
  (**~100 KB in their testing**) or the stream ends, and there is **no `flush()` API**
  that overrides it. They confirm it reproduces even with Function URLs (no API Gateway),
  proving it is the runtime, not the front door. Content was rephrased for compliance.

  Why the S3 example streams but the Bedrock relay bursts: S3 writes ~1 MB per
  `write()` (via `copy()`), overflowing the ~100 KB runtime buffer repeatedly →
  progressive. The Bedrock relay writes ~4–32 bytes per token and the whole response is
  tiny, so the buffer never fills → terminal burst. It is purely **per-flush byte volume
  vs the ~100 KB threshold**, which is language-agnostic.

  **The fix (measured here, tiered SSE-comment padding):** append an SSE comment line
  (`: <spaces>\n\n`, which compliant clients and `sed -n 's/^data: //p'` ignore) to each
  event, large enough to cross the ~100 KB buffer. Tiered per the article: ~100 KB on the
  first 3 events to break the initial buffer, ~10 KB on the rest to keep it flowing.
  Measured on THIS Kotlin/JVM + REST API Gateway stack (throwaway `bedrock-stream-exp`):

  | Run | Padding | Generation | Arrival spread | Buckets | Verdict |
  |---|---|---|---|---|---|
  | Control | none | fast | 1 ms | 1 | terminal burst |
  | Padded | 100 KB×3 + 10 KB | fast | 305 ms | 4×100 ms | progressive (brief) |
  | Padded | 100 KB×3 + 10 KB | long (5000 tok) | 2684 ms | 6×500 ms | **clearly progressive** |

  Two independent factors govern what the client sees: (1) padding past ~100 KB defeats
  the buffer; (2) the generation must take long enough in wall-clock for the trickle to
  be visible (Nova Lite is fast, so a short answer still lands in <1 s even when padded).
  Cost of the workaround: a ~15 KB essay became ~5 MB on the wire (~500× inflation) —
  acceptable for responsiveness, wasteful for bandwidth. **Verdict: the workaround is
  real and reproducible, so a `streaming-bedrock-example` module IS warranted, provided it
  documents the padding trade-off honestly and cites the runtime-buffer cause.**

  No secrets recorded.

## Bedrock streaming: the padding workaround works — `streaming-bedrock-example` shipped

- **Symptom / trigger:** Follow-up to the terminal-burst finding above. Question: can the
  ~100 KB Lambda-runtime buffer be defeated so Bedrock token streaming arrives
  progressively through API Gateway REST on this Kotlin/JVM stack?

- **Resolution / status:** **Resolved — tiered SSE-comment padding works, measured and
  shipped as a production example.** Each model delta is written as an SSE `data:` event
  followed by an SSE comment line (`: <spaces>`, ignored by clients) sized to cross the
  buffer: ~100 KB on the first 3 events, ~10 KB on every event afterwards. Measured on the
  deployed `bedrock-streaming-endpoint` stack (eu-west-1) via the opt-in
  `scripts/bedrock-streaming-test.sh`:
  - No padding: single terminal burst (1 time bucket).
  - Tiered padding: TTFB ~0 ms, tokens across **9 distinct 500 ms buckets** over ~4.4 s,
    `[DONE]` sentinel received — progressive.

  The throwaway POC was promoted to `streaming-bedrock-example`, mirroring
  `streaming-s3-example`'s structure: `PromptResolver` (RequestResolver) → `BedrockStreamHandler`
  (StreamHandler) → `BedrockSource` (a `TokenStreamSource`, the token-stream analogue of
  `StreamSource`) + `PaddedSseWriter` (the buffer workaround, with a property test) + CRaC
  `Priming`. Kover 83% (gate 80%). SAM template under `deployment/aws/sam-bedrock/` with the
  full Lambda standards (RESPONSE_STREAM, SnapStart, priming, arm64, java25, tiered compilation),
  scoped `bedrock:InvokeModelWithResponseStream` IAM, API-key auth. The post-deploy test is
  opt-in behind `BEDROCK_TEST_ENABLED=true` to guard the Bedrock cost.

  **Trade-off recorded for the article:** padding inflates the response (~15 KB answer →
  several MB on the wire). Worth it for time-to-first-token in a chat UX; wasteful for bulk
  output. The client strips the padding with a plain `sed -n 's/^data: //p' | awk '{printf...}'`
  pipeline — no custom tooling.

---

## Recording streaming demos: `asciinema rec` v3 coalesced the pipe timing; render from the real stream

- **Symptom / trigger:** Recording the Bedrock demo GIF with
  `asciinema rec --command '<curl | sed | awk>'` produced a cast where the whole response
  appeared in one burst at the end, even though the identical pipeline measured smooth,
  progressive arrival when piped directly (ttf ~0, spread 8–23 s). A `sleep`-paced control
  command recorded correct inter-event timing, so the tool works in general — but under the
  long single pipeline the PTY capture coalesced output timing. (Also note: asciicast **v3**
  timestamps are per-event deltas, not absolute — summing them wrong looks like "all at t=0".)

- **Resolution / status:** **Resolved — record the real stream's true arrival times into an
  asciicast directly.** A tiny recorder reads the live `curl` SSE stream, applies the exact same
  client filter the on-screen command shows (`data:` lines, strip prefix, drop `:` padding),
  and stamps each token at its real wall-clock arrival into an asciicast-v2 file, then `agg`
  renders it (`--speed`, `--idle-time-limit` to keep the GIF watchable). The bytes and timings
  are exactly what the client received — faithful, not synthesized. Gotchas for `agg`: it rejects
  an asciicast-v2 header carrying an empty `"theme": {}` (omit it); use `--idle-time-limit` to
  collapse long inter-token gaps. Secret hygiene unchanged: `$URL`/`$KEY` exported off-camera,
  `.cast` grepped for zero occurrences of the real key value and execute-api host before render.

---

## SAM: a prebuilt-jar `CodeUri` is deployed directly, not via `sam build`

- **Symptom / trigger:** `sam build` against a template whose `CodeUri` points straight at a
  prebuilt fat jar failed with `Unable to find a supported build workflow for runtime 'java25'`
  — SAM looked for a Gradle/Maven manifest next to the jar.

- **Resolution / status:** **Resolved (convention).** When `CodeUri` is the already-built shadow
  jar (as in all example stacks here), skip `sam build` and run `sam deploy` directly; SAM
  packages the referenced jar as-is. Build the jar first with
  `./gradlew :<module>:shadowJar` (or the module's `build.sh`), then deploy. Confirm the
  deployed Lambda `CodeSha256` matches the local jar before trusting results, since SnapStart
  publishes a new version on each deploy.

## Codecov showed 0% overall — per-module reports need a single ROOT-aggregated Kover report

- **Symptom / trigger:** The Codecov dashboard reported 0% coverage for the whole project, not
  just one module. CI uploaded one coverage XML per module
  (`streaming-core/build/reports/kover/report.xml`, `streaming-s3-example/.../report.xml`, the
  Java module's JaCoCo XML, and later `streaming-bedrock-example/.../report.xml`). Each report
  names classes package-relatively (`<package name="nl/vintik/lambda/streaming">` +
  `sourcefilename="BoundedBuffer.kt"`) with no module source-root prefix. Codecov could not map
  those paths to the real files (which live under `<module>/src/main/kotlin/...`), so every line
  counted as uncovered → 0% overall. `codecov.yml` had no `fixes:` section.

- **Resolution / status:** **Resolved — aggregate the Kotlin modules into one root Kover report
  and upload that (plus the Java JaCoCo report), mirroring the sibling mocknest-serverless repo
  whose Codecov works.** The working repo is also multi-module with the same package-relative
  paths and no `fixes:` — the difference is it uploads a SINGLE root-aggregated Kover report, which
  Codecov resolves by searching the repo tree for the unique `nl/vintik/.../Foo.kt`. Per-module
  uploads do not resolve. Changes:
  - Root `build.gradle.kts`: apply the Kover plugin at the root (was `apply false`) and add
    `kover(project(":streaming-core"))`, `kover(project(":streaming-s3-example"))`,
    `kover(project(":streaming-bedrock-example"))` so `./gradlew koverXmlReport` emits an
    aggregated `build/reports/kover/report.xml` spanning all three Kotlin modules.
  - The pure-Java `streaming-s3-example-java` reports via JaCoCo and cannot be Kover-aggregated,
    so it is uploaded as a SECOND Codecov file. The Codecov upload set is therefore exactly two
    files: the root aggregate + the Java JaCoCo XML.
  - `verifyCoverageReports` still validates EVERY module's own report (the non-empty guard), but
    now writes the two-file upload set to `coverage-report-paths.txt`. The four lists that must
    agree (paths file, workflow `files:`, workflow `id: cov` loop, the script's
    `COVERAGE_REPORTS_FALLBACK`) were all updated to the two-file set; `check_coverage_paths`
    passes.
  - No coverage gate changed: 90% on `:streaming-core` and 80% on each example module stay each
    module's own `koverVerify`. The root `koverVerify` sets no bound (no-op).
  - **Two independent preconditions for the dashboard to update:** (1) this must land on `main` —
    the upload step is gated to `push` on `refs/heads/main`, so feature branches never upload;
    (2) `CODECOV_TOKEN` must be set in repo secrets, otherwise the workflow deliberately skips the
    upload and logs a notice. Neither is a code change this branch can make.

## Codecov STILL 0% after aggregation — auto-search re-uploaded the broken per-module reports, and a folded-scalar space dropped the Java report

- **Symptom / trigger:** Even after merging the root-aggregation change to `main` (the aggregate
  `build/reports/kover/report.xml` is generated and listed in the Codecov `files:`), the dashboard
  stayed at 0.00%. The sibling mocknest-serverless repo — same multi-module Kotlin, same
  package-relative report paths, no `fixes:` — works. Reading the actual `codecov-action@v5` step
  log from the main build (`gh run view --log`) revealed two independent bugs, neither visible
  locally.

- **Root cause 1 — Codecov auto-search ignored our curated list.** The step ran with
  `CC_DISABLE_SEARCH: false`, so Codecov searched the workspace and found **6** coverage files —
  the root aggregate AND all four per-module reports AND `coverage-report-paths.txt` — and uploaded
  them all, not just the two in `files:`. The per-module Kotlin reports carry package-relative
  paths (`nl/vintik/.../Foo.kt`) that do not resolve against the repo tree, which is exactly what
  pins coverage at 0%. mocknest works despite search being on only because it produces a SINGLE
  report on disk, so search finds just the aggregate; our tree has five reports, so search drags
  the unresolvable per-module ones back in.

- **Root cause 2 — a `>-` folded scalar inserted a space, dropping the Java report.** The log
  showed `--file  streaming-s3-example-java/...` (double space) and
  `not_found_files: [" streaming-s3-example-java/build/reports/jacoco/test/jacocoTestReport.xml"]`.
  The YAML folded scalar joined the two comma-separated paths with a space after the comma, so the
  second path reached the uploader with a leading space and was not found — Java coverage never
  uploaded at all.

- **Resolution / status:** **Resolved — disable search and fix the files: formatting.** In
  `workflow-build.yml`'s `codecov/codecov-action@v5` step:
  - Added `disable_search: true`. Per Codecov docs this skips auto-discovery of common reports but
    still honours user-specified files, so ONLY the two curated reports (root aggregate + Java
    JaCoCo) upload — the unresolvable per-module reports are no longer sent.
  - Changed `files:` from a `>-` folded scalar to a single-line, comma-separated value with NO
    space after the comma, so neither path arrives with a leading space.
  - The four-list consistency (`check_coverage_paths`) still passes; the value stays one line so the
    check's single-step/one-line assumption holds.
  - Takes effect on the next `main` push with `CODECOV_TOKEN` set (token confirmed present in the
    run log). Lesson: when Codecov shows 0% with reports that look correct, read the uploader log —
    `disable_search` and path-trimming bugs are invisible to a local `koverXmlReport`.

---

## The Lambda streaming runtime buffers small writes below the language layer — tiered SSE padding is the only lever that forces progressive delivery

- **Symptom / trigger:** Promoting the SSE padding/dribble workaround into the new
  `streaming-sse` module (package `nl.vintik.lambda.streaming.sse`) meant re-stating,
  in one place, *why* the padding exists at all. The underlying behaviour is a property
  of the AWS Lambda **response-streaming runtime**, not of any one example: a handler
  that relays small writes (a few bytes per LLM token or per SSE event) and flushes
  after each one still arrives at the client as a **single terminal burst**, not
  progressively. Flushing at the application layer does not change this — the bytes are
  being held *below* the language layer.

- **Root cause (7.1):** The Lambda streaming runtime holds small writes in an
  **internal, runtime-level buffer that sits beneath the JVM/`OutputStream` layer**.
  No application-level `flush()` overrides it — the handler can flush every event and
  the bytes still sit in the runtime buffer. The buffered bytes are only released to
  the client when **the buffer fills (~100 KB observed)** or **the invocation ends**
  and the stream is closed. So a naive token relay (tiny writes, each well under the
  threshold) never crosses the buffer mid-invocation; everything coalesces and is
  delivered at the end. This is the same class of surprise as the earlier
  *"`OutputStream` must be closed explicitly"* entry, but the opposite failure mode:
  there the bytes arrived late because the stream was never closed; here they arrive
  late because each write is too small to breach the runtime buffer.

- **Workaround — tiered SSE-comment padding (7.2):** Appending **tiered padding** to
  each event, where each padded event is large enough to cross the runtime buffer,
  makes the client observe **progressive delivery**. The padding is carried as SSE
  comment lines (`: <spaces>`) so compliant clients and a `sed -n 's/^data: //p'`
  filter discard it without corrupting the payload. The scheme is two-tier:
  - **Large head padding** (~100 KB, the module default is `102_400` bytes applied to
    the first 3 events) to **break the initial buffer** — the first events alone exceed
    the ~100 KB fill threshold, so the runtime releases them immediately.
  - **Smaller tail padding** (~10 KB, default `10_240` bytes) on every subsequent event
    to **keep the stream flowing** once the buffer has been primed.

  `PaddedSseWriter` is the piece that implements this; `PacedSseEmitter` layers the
  optional pacing on top.

- **Delay-without-padding does NOT work (7.3):** An **inter-chunk delay applied without
  padding does not cross the runtime buffer.** This is the exact trap the MockNest
  "dribble" attempt hit: spacing the small writes out in time changes *when* each tiny
  write happens, but each write is still far below the ~100 KB threshold, so the runtime
  keeps coalescing them and the client still sees **a single terminal burst** rather
  than progressive delivery. The delay only *paces* delivery once padding is already
  forcing each event past the buffer — it is not itself a mechanism for progressive
  delivery. This is why `PacedSseEmitter` **rejects a configured (non-zero) delay when
  its writer has no padding** (`hasPadding()` is false): a delay with nothing to pace is
  a misconfiguration that would look like it should stream but would not, so the module
  fails fast at construction with an `IllegalArgumentException` instead of shipping a
  silently non-progressive endpoint.

- **The cost — bandwidth trade-off (7.4):** The padding is not free. It inflates the
  **on-the-wire response size** relative to the original payload by roughly
  **100× to 1000×** — a handful of payload bytes per event is carried alongside ~10 KB
  (tail) to ~100 KB (head) of comment padding. For small-chunk streaming that ratio is
  the price of progressive delivery through this runtime: the client sees bytes early,
  but the connection carries far more than the payload itself. Consumers that do not
  need early first-byte delivery should not pay it; the padding exists specifically to
  defeat the buffer, so it is only worth enabling when progressive delivery is a goal.

- **Resolution / status:** **Captured — feeds the article; behaviour proven against the
  Bedrock example and encoded in the module.** The cause (runtime buffer below the
  language layer, released on fill/end), the tiered-padding workaround (large head to
  break the buffer, smaller tail to keep it flowing), the delay-alone-is-useless finding
  (hence the `PacedSseEmitter` guard), and the ~100×–1000× bandwidth cost are now the
  documented rationale for `streaming-sse`. No local emulator reproduces the runtime's
  buffering, so the end-to-end progressive-delivery proof is the post-deploy check
  against the real deployed Bedrock endpoint, not a unit test. No runtime secrets are
  involved in this finding.

---

## `bedrock-streaming-test.sh` does not measure HTTP TTFB — its "~0 ms" is by construction

- **Symptom / trigger:** The Bedrock README and article draft reported "TTFB ~0 ms" for the padded
  configuration. Reviewing the script showed the Python reader sets its clock (`base`) when the
  first line arrives on stdin, and `first` is the first `data:` line — normally that same line. So
  `ttf_ms` is ~0 regardless of how long the client actually waited, and `total_ms` is the spread from
  the first to the last data line, not the request duration. The `ttf/total < 0.5` assertion is
  therefore effectively always true; only `buckets >= MIN_BUCKETS` and `[DONE]` carry signal. The
  script also never sends `headPadBytes`/`tailPadBytes`, so it always measures the default padding.
- **Resolution / status:** **Documented, script unchanged.** The article now reports arrival spread
  and distinct 500 ms windows only, and states that HTTP TTFB was not measured for the padded case
  (the only HTTP TTFB on record is the unpadded experiment: `time_starttransfer == time_total ==
  5.79 s`). To measure real TTFB, capture curl's `%{time_starttransfer}` (as
  `pipeline-streaming-test.sh` does) alongside the per-line timestamps.

---

## `PaddedSseWriter` does not split multi-line deltas — text after a newline is lost to SSE clients

- **Symptom / trigger:** `writeEvent` emits `data: <text>\n\n` literally. A delta containing `\n`
  yields one `data:` line plus raw continuation lines that have no `data:` prefix; an SSE parser
  treats those as unknown fields and drops them. `PaddedSseWriterRoundTripTest` asserts this
  (`"first\nsecond"` recovers only `"first\n"`). The demo's `sed | awk` pipeline hides it because it
  joins lines anyway, and it also prints a trailing `[DONE]`.
- **Resolution / status:** **OPEN — documented as a limitation in the article.** A fix would split
  `text` on `\n` and prefix every line with `data: ` (the SSE spec's multi-line data form); that is a
  behaviour change to the published writer and would need its round-trip property updated.

---

## Edge-optimized API Gateway endpoint buffered the Bedrock stream — use REGIONAL

- **Symptom / trigger:** Reported by the author during the Bedrock work: with an edge-optimized
  REST API endpoint the streamed response reached the client in a single burst. Switching the API to
  `EndpointConfiguration: Type: REGIONAL` restored progressive delivery. The run details (request,
  timings) were not recorded at the time.
- **Resolution / status:** **Resolved by configuration.** All SAM templates set `REGIONAL` (`sam/`, `sam-java/`, `sam-bedrock/`) — it is shared setup for the S3 and Bedrock examples, not Bedrock-specific. Note the
  API Gateway docs state response streaming works for all REST API endpoint types, with idle timeouts
  of 5 minutes (regional/private) and 30 seconds (edge-optimized), so the buffering seen on the edge
  endpoint is an observation of this setup, not documented behaviour.

---

## `runCatching` in the Bedrock example caught cancellation and JVM errors, and swallowed parse failures silently

- **Symptom / trigger:** `BedrockStreamHandler` wrapped the model call in `runCatching`, which
  captures every `Throwable`. A `CancellationException`, an `InterruptedException` or a JVM `Error`
  (e.g. `OutOfMemoryError`) raised before the first delta would have been logged and answered with
  HTTP 502 instead of propagating. `PromptResolver` (request read, body parse, integer knobs) and
  `BedrockSource` (Nova event parse) used the same pattern with `getOrNull()`/`getOrElse`, and
  discarded those failures without logging them.
- **Resolution / status:** **Resolved.** Added `Result<T>.rethrowFatal()` (`NonFatal.kt`), so every
  call site reads `runCatching { … }.rethrowFatal()` and keeps the full `Result` API. Every handled
  failure is now logged at WARN before its fallback. For JSON parse failures only the exception type
  is logged, because kotlinx-serialization's message echoes the input (the prompt or model output).
  Covered by `NonFatalTest` (recoverable vs fatal) and a handler test asserting a cancelled source
  propagates without a 502. The S3 examples still use plain `runCatching`.

---

## SAM has no `STREAM` value — any value other than `RESPONSE_STREAM` silently deploys a buffered integration

- **Symptom / trigger:** Checking the article's `RESPONSE_STREAM` vs `STREAM` lesson against the docs.
  SAM's `Api` event `ResponseTransferMode` lists `BUFFERED | RESPONSE_STREAM`; CloudFormation's
  `AWS::ApiGateway::Method Integration` and the API Gateway guide use `BUFFERED | STREAM`.
- **Findings:** In the SAM translator (`aws/serverless-application-model`, develop @ `3dfbd2b`),
  `swagger.py` sets the integration's `responseTransferMode` to `STREAM` and `push.py` switches the
  URI to `2021-11-15/.../response-streaming-invocations` only when the value equals the exact string
  `RESPONSE_STREAM`. With SAM CLI 1.160.1, `sam validate` accepted `RESPONSE_STREAM`, `STREAM` and a
  bogus value alike, and `sam validate --lint` produced identical output for `RESPONSE_STREAM` and
  `STREAM` (only two unrelated W3005 warnings on SAM-generated resources). So `STREAM` in a SAM
  template deploys a plain buffered `/invocations` integration with no error.
- **Resolution / status:** **Documented.** Article cites the SAM reference and translator source and
  states the mapping. Note: `sam validate --lint` exits non-zero on our templates because of those
  W3005 warnings; the repo's gate is plain `sam validate`.
---

## Measured: on managed `java25`, no bytes reach the API Gateway integration until the handler returns

- **Symptom / trigger:** The entries above attribute the Bedrock terminal-burst behaviour to a
  **~100 KB buffer inside the Lambda response-streaming runtime** that releases bytes when it fills,
  with tiered SSE padding as the lever that crosses it. That model was adopted from a Python +
  Lambda Web Adapter report, not measured on this stack. Instrumenting both Lambdas with timing
  lines (`StreamTimings`, `StreamHandler.Timings`) and both API Gateway stages with the
  response-streaming access-log variables made it directly measurable, and the numbers do not fit
  the model. Recorded here as raw facts; the article is unchanged pending the isolation probe
  described at the end.

- **Environment (all measurements):** region `eu-west-1`, managed runtime `java25`, `arm64`,
  1024 MB, SnapStart `PublishedVersions` with the `live` alias, API Gateway **REST**, `REGIONAL`
  endpoint, proxy integration with SAM `ResponseTransferMode: RESPONSE_STREAM`.
  `bedrock-streaming-endpoint` version **15**, `CodeSha256` `adcS8ulWidN5jG8UaG2SIIYWbIbE4E6K2eupbWQiY5s=`;
  `s3-file-streaming-endpoint` version **23**, `CodeSha256` `COjozl/YPwBZjSL+Erzt7FSsw7HQsiMkNbrDTBVtjyQ=`.
  Local branch at `b7924cd`. No secrets recorded (the API key is resolved at run time and masked).

- **Access-log semantics used** (from the [API Gateway response-streaming troubleshooting page](https://docs.aws.amazon.com/apigateway/latest/developerguide/response-streaming-troubleshoot.html)):
  `$context.integration.timeToAllHeaders` is connection-established → all integration response
  headers received; `timeToFirstContent` is connection-established → **first content bytes**
  received; `integration.latency` is connection-established → integration response stream complete.
  **Doc discrepancy worth knowing:** the page documents `responseTransferMode` as `BUFFERED |
  STREAMED`, but the access log emits **`STREAM`**. Every request below logged `"transferMode":"STREAM"`,
  so these are genuinely streaming integrations, which is also why the 12–21 MB bodies get past the
  6 MB buffered-response limit.

- **Finding 1 — `timeToAllHeaders == timeToFirstContent` on every request.** 15 requests across both
  stacks, warm and cold: the two values are identical to the millisecond (one off-by-one at
  12939/12940). API Gateway never sees headers before content. Since the "integration response
  headers" are parsed from the metadata prelude the handler writes by hand, this says the prelude
  does not travel ahead of the body.

- **Finding 2 — first content lands just after the handler closes, never when the prelude is written.**
  Handler clock (`tMs`, from invocation start) joined to the API Gateway clock via
  `$context.integration.requestId`:

  | Request | prelude written | bytes in stream by then | handler `closed` | `timeToAllHeaders` | `timeToFirstContent` | `integrationLatency` | body |
  |---|---|---|---|---|---|---|---|
  | Bedrock `a8dbcef4` | 422 ms | ~0 | 8787 ms | 8816 ms | 8816 ms | 9321 ms | 7.84 MB |
  | S3 `3ad39761` | 14 ms | 0 | 1044 ms | 1102 ms | 1102 ms | 2658 ms | 12.58 MB |
  | Bedrock `d5e1b8a0` | 4149 ms | ~0 | 12211 ms | 12939 ms | 12940 ms | 17000 ms | 18.75 MB |
  | Bedrock `ae8e3eee` | 272 ms | ~0 | 9414 ms | 9639 ms | 9639 ms | 13443 ms | 18.11 MB |
  | Bedrock `efea6924` | 268 ms | ~0 | 8498 ms | 8599 ms | 8599 ms | 12397 ms | 18.11 MB |
  | Bedrock `e74d06d8` | 452 ms | ~0 | 9920 ms | 10098 ms | 10098 ms | 14281 ms | 18.76 MB |

  The Bedrock prelude sat in the sandbox for 8.4 s (`a8dbcef4`) while 7.84 MB accumulated behind it.
  The S3 prelude sat for 1.03 s while all 12.58 MB was written. In every case the first content
  reaches API Gateway **26–204 ms after the handler closed**, and the whole body then transfers in
  the remaining `integrationLatency` window.

- **Finding 3 — a volume threshold is excluded up to at least 12.5 MB.** The deliberate test
  (`scripts/stream-emission-probe.sh`, defaults `headPadBytes=1048576 headPadEvents=10
  tailPadBytes=10240 maxTokens=5000`) writes ~10 MB inside the first second and then keeps the
  invocation alive for another 7–11 s. Raw handler lines for `efea6924`:

  ```
  stage=start              tMs=0    outputClass=java.io.ByteArrayOutputStream
  stage=firstDelta         tMs=268
  stage=metadataCommitted  tMs=268
  stage=event n=1          tMs=272  bytesTotal=1048590
  stage=event n=3          tMs=415  bytesTotal=3145788
  stage=event n=100        tMs=1347 bytesTotal=11409729   <- 11.4 MB written, 7.2 s before close
  stage=event n=700        tMs=7910 bytesTotal=17568902
  stage=streamEnd          tMs=8498 events=753 bytesTotal=18112982 writeMsMax=16.7
  stage=closed             tMs=8498
  ```
  11.4 MB was in the stream at 1347 ms; API Gateway's first content was 8599 ms. The earlier S3
  request wrote all 12.58 MB and still showed nothing until close. A fill-and-release threshold at
  ~100 KB, 1 MB, 6 MB or 10 MB would have been plainly visible in these runs. It was not.
  Every `write()` costs ~0 ms at the handler (`writeMsMax=16.7` only for the 1 MB padded events), i.e.
  the handler is writing to memory, not to a socket — consistent with the observed
  `outputClass=java.io.ByteArrayOutputStream`.

- **Finding 4 — the mechanism is visible in AWS's own source.** In
  [`EventHandlerLoader.wrapRequestStreamHandler`](https://github.com/aws/aws-lambda-java-libs/blob/main/aws-lambda-java-runtime-interface-client/src/main/java/com/amazonaws/services/lambda/runtime/api/client/EventHandlerLoader.java)
  the runtime interface client keeps a `ThreadLocal<ByteArrayOutputStream>` (initial capacity 1024),
  resets it per invocation, hands it to `handleRequest`, and **returns it as the invocation result**
  (`public ByteArrayOutputStream call(InvocationRequest request)`). There is no code path that can
  transmit while the handler is inside `handleRequest`, and no streaming response mode. On the same
  repo, [issue #476](https://github.com/aws/aws-lambda-java-libs/issues/476) carries a maintainer
  reply that response streaming is Node.js-only. *Caveat, not yet verified:* that artifact is
  published for container images and custom runtimes; the managed `java25` runtime hands the handler
  the same `java.io.ByteArrayOutputStream` type, but it has not been confirmed to be this exact jar.

- **Finding 5 — memory grows with response size.** `Max Memory Used` over five consecutive 12 MB S3
  requests in one environment: 205 → 217 → 229 → 241 → 253 MB (+12 MB per request, the object size).
  Three consecutive 18 MB Bedrock requests: 283 → 330 → 345 MB. The handler's bounded copy buffer
  bounds its own loop; it cannot bound the runtime's accumulator. This **contradicts the earlier
  "memory stays bounded for large objects" note**. `Max Memory Used` is a high-water mark, so the
  growth pattern is strong but not proof; a decisive test would be a 100 MB+ body.

- **Finding 6 — what the padding actually buys, and why the pipeline test is unreliable.** With
  ~10 MB of head padding the client measured `ttfb_s=8.68 total_s=12.48` (18.1 MB): **first byte
  after 70% of the total**, i.e. the client waited out the entire generation. Token arrival still
  spread over 3.8 s in 8 distinct 500 ms buckets, because 18 MB takes ~3.8 s to download. So the
  "progressive arrival" the client observes is the **download duration of an inflated body**, not
  incremental production. This also explains the `main`-passes / branch-fails flakiness:
  `bedrock-streaming-test.sh` sets its clock on the first line it reads (so `ratio` is ~0 by
  construction, already noted above) and `done` is always true, leaving `buckets >= 3` as the only
  live criterion — which is satisfied iff the download takes over a second. It is a bandwidth
  assertion, not a streaming assertion, and `rethrowFatal` cannot affect it.

- **Reproducing it:** `scripts/stream-emission-probe.sh` (opt-in behind `BEDROCK_TEST_ENABLED=true`)
  fires N requests with the padding knobs and joins the three clocks for each invocation, printing
  `preludeAt_ms / closedAt_ms / allHeaders_ms / firstContent_ms / integLat_ms`. The decisive numbers
  are server-side, so they do not depend on the client's network. Verified end to end:
  ```
  BEDROCK_TEST_ENABLED=true RUNS=1 AWS_REGION=eu-west-1 ./scripts/stream-emission-probe.sh
  api-req   preludeAt_ms  closedAt_ms  allHeaders_ms  firstContent_ms  integLat_ms
  e74d06d8  452           9920         10098          10098            14281        bytes=18758580
  ```

- **Resolution / status:** **Measured and reproduced (6 joined requests, 2 stacks, 15 access-log
  lines); one gap remains before any conclusion is written up.** A release threshold strictly
  between ~12.5 MB and the body size of these runs cannot be separated from "released at return" by
  these measurements alone, because the handler kept writing until it closed. The isolation probe
  that closes it: a throwaway stack whose handler writes ~15 MB immediately, flushes, **sleeps 10 s**,
  then returns — `timeToFirstContent` ≈ 0 s means a threshold exists, ≈ 10 s means nothing leaves
  until the handler returns. Until that runs, `docs/bedrock-sse-streaming.md`, the `streaming-sse`
  KDoc/README and the `sam-bedrock` template description keep their ~100 KB-buffer wording.

---

## Settled by isolation probe: the managed `java25` runtime forwards nothing until the handler returns — at any volume

- **Symptom / trigger:** The entry above left exactly one gap. In every production measurement the
  handler kept writing right up to the moment it closed, so "released when the handler returns"
  could not be separated from "released once some volume threshold above ~12.5 MB is crossed".
  The isolation probe closes it by writing the whole body **up front** and then going **idle**.

- **Instrument:** throwaway module `streaming-probe` (`EmissionProbeHandler`) + throwaway stack
  `deployment/aws/sam-probe/` + driver `scripts/stall-probe.sh`. The handler writes `bytes` bytes of
  filler in `chunks` flushed writes, then `Thread.sleep(sleepMs)`, then returns. Nothing is written
  after the sleep, so during the stall the complete body sits in the buffer with the handler doing
  nothing. The prelude is written with `streaming-core`'s `ResponseWriter` — the same code path as
  the real examples — and the stack matches them exactly: `java25`, `arm64`, 1024 MB, SnapStart
  `PublishedVersions` + `live` alias, tiered compilation, REGIONAL REST API,
  `ResponseTransferMode: RESPONSE_STREAM`.

- **Preconditions verified before measuring** (`aws apigateway get-integration`), because a buffered
  integration would invalidate everything: `type=AWS_PROXY`,
  `responseTransferMode=STREAM`, URI
  `…/2021-11-15/functions/arn:aws:lambda:eu-west-1:…:function:stream-emission-probe:live/response-streaming-invocations`.
  Deployed version 1, `CodeSha256` `PrY7cVjRKOx+nwkyE5rNjgHaEUKSSuks0aLZUpq0dZQ=`,
  SnapStart `OptimizationStatus: On`.

- **Command:** `AWS_REGION=eu-west-1 ./scripts/stall-probe.sh` (warmup + 2 runs per variant).

- **Raw result.** Handler columns are the handler's clock in ms from invocation start; the API
  Gateway columns are its own clock from the integration connection:

  | variant | api-req | writesDone | sleepEnd | closed | allHeaders | firstContent | integLatency | body |
  |---|---|---|---|---|---|---|---|---|
  | control (sleepMs=0) | `73acaf92` | 130 | — | 131 | 362 | 364 | 3044 | 15 MB |
  | control (sleepMs=0) | `719818c7` | 2 | — | 3 | 96 | 96 | 2746 | 15 MB |
  | **stall-big** | `3431db2b` | **2** | 10003 | 10003 | **10069** | **10069** | 12726 | 15 MB |
  | **stall-big** | `9f95fc60` | **2** | 10002 | 10003 | **10066** | **10066** | 13022 | 15 MB |
  | stall-tiny | `b9f34acc` | 0 | 10001 | 10001 | 10029 | 10029 | 10025 | 1 KB |
  | stall-tiny | `1b605d31` | 0 | 10000 | 10001 | 10023 | 10023 | 10019 | 1 KB |

  Raw handler lines for `3431db2b` (Lambda request `86bbcb78`):
  ```
  stage=start            tMs=0     outputClass=java.io.ByteArrayOutputStream
  stage=parsed           tMs=0     bytes=15728640 chunks=15 sleepMs=10000
  stage=metadataCommitted tMs=0
  stage=chunkFlushed     tMs=1     n=1  written=1048576
  stage=chunkFlushed     tMs=2     n=15 written=15728640
  stage=writesDone       tMs=2     chunks=15 bytes=15728640
  stage=sleepStart       tMs=2
  stage=sleepEnd         tMs=10003
  stage=closed           tMs=10003
  REPORT Duration: 10079.20 ms  Max Memory Used: 199 MB
  ```

- **Conclusion (reproduced, 2 runs per variant, 3 variants):** **15 MB was fully written and flushed
  by 2 ms, the handler then sat idle for 10 seconds, and API Gateway received nothing — not the
  headers, not one content byte — until 10066–10069 ms, right after the handler returned.** The
  1 KB variant behaves identically. There is **no volume-triggered release**: the figure is not
  ~100 KB, it is not 6 MB, it is not 15 MB, it does not exist. On the managed `java25` runtime the
  response leaves the execution environment **only when the handler returns**, and
  `OutputStream.flush()` has no effect on the transport. This matches the AWS source exactly
  (`EventHandlerLoader.wrapRequestStreamHandler` collects into a `ThreadLocal<ByteArrayOutputStream>`
  and returns it as the invocation result) and the maintainer reply on
  [aws-lambda-java-libs#476](https://github.com/aws/aws-lambda-java-libs/issues/476) that response
  streaming is Node.js-only. Writing 15 MB took **2 ms**, i.e. memory speed, which is the same fact
  from the handler's side.

- **What response streaming on the JVM therefore *is*, and still genuinely buys:** the integration
  really is a streaming one (`responseTransferMode=STREAM`, `/response-streaming-invocations`), and
  that is not cosmetic — it is what lifts the 6 MB buffered-response cap and API Gateway's 10 MB
  buffered cap, which is why the 12–21 MB bodies in this repo are delivered at all. API Gateway then
  streams those bytes to the client progressively and with bounded memory on *its* side. What the
  JVM handler cannot do is produce them incrementally: the body is complete, and fully resident in
  the sandbox's heap, before the first byte moves. **Large-payload delivery: real. Incremental
  production / early first byte: not available on the managed JVM runtime.**

- **Consequence for the SSE padding workaround:** it does not defeat a runtime buffer, because there
  is no buffer to defeat. What padding changes is the **size of the finished body**, and therefore
  how long API Gateway takes to push it to the client — which is the only window in which a client
  can observe anything arriving "progressively". Control run `719818c7` is the clean illustration:
  handler done in 3 ms, first content at 96 ms, then 2.75 s to transfer 15 MB — the client measures
  TTFB 0.17 s / total 2.82 s and calls it streaming. In the padded Bedrock runs the generation
  happens first and the download second, so padding makes time-to-first-token strictly **worse**
  (measured: `ttfb_s=8.68` of `total_s=12.48`) while making the arrival spread wider. For a chat UX,
  which is the use case the padding was introduced for, that is the opposite of the goal.

- **Resolution / status:** **Settled.** The ~100 KB runtime-buffer explanation is wrong for the
  managed JVM runtime and must be replaced wherever it appears: `docs/bedrock-sse-streaming.md`, the
  `streaming-sse` module KDoc (`PaddedSseWriter`) and README, `streaming-bedrock-example/README.md`,
  and the `deployment/aws/sam-bedrock/template.yaml` description. The two post-deploy scripts also
  assert the wrong thing (`buckets >= 3` is a download-duration check, and
  `bedrock-streaming-test.sh`'s `ratio` is ~0 by construction) and are the cause of the
  `main`-passes / branch-fails flakiness. The probe module, stack and driver script are kept in the
  repo so the measurement is re-runnable when AWS changes the runtime; the **stack was deleted**
  after this run. `streaming-probe` is deliberately absent from `release-tag.yml`'s trigger paths,
  `workflow-publish.yml`'s task list, the root Kover aggregation, `verifyCoverageReports` and
  `codecov.yml`. No secrets recorded.

---

## It CAN trickle: posting the response to the Runtime API yourself streams from a managed `java25` Lambda — at the cost of one dead execution environment per request

- **Symptom / trigger:** Having established that the managed runtime forwards nothing until the
  handler returns, the question became whether anything inside the sandbox can emit incrementally
  without a custom runtime and without a layer. The one candidate: the Runtime API itself, which is
  reachable from handler code at `$AWS_LAMBDA_RUNTIME_API` and is exactly what a custom runtime posts
  to. Added `mode=runtimeapi` to `EmissionProbeHandler`: ignore the `OutputStream` the runtime
  provides, and instead open a chunked `POST` to
  `/2018-06-01/runtime/invocation/{requestId}/response` carrying
  `Lambda-Runtime-Function-Response-Mode: streaming` and
  `Content-Type: application/vnd.awslambda.http-integration-response` (the marker Node's
  `awslambda.HttpResponseStream.from()` sets via `setContentType`), then write prelude + 8 NUL bytes
  + 3 × 64 KB chunks spaced 2 s apart, flushing each.

- **Environment:** throwaway stack `stream-emission-probe`, version 2, `CodeSha256`
  `52HmN3QwSWtP8flUddedEoUa6Lev6X7yJTTTvAvgWuk=`, `java25` / `arm64` / 1024 MB, SnapStart on,
  REGIONAL REST API, `ResponseTransferMode: RESPONSE_STREAM`. Runtime API endpoint observed as
  `169.254.100.1:9001`. Command:
  `AWS_REGION=eu-west-1 RUNS=2 VARIANTS='runtimeapi|mode=runtimeapi&chunks=3&chunkBytes=65536&gapMs=2000' ./scripts/stall-probe.sh`

- **Result — it streams.** Handler clock vs API Gateway clock, run 1 (Lambda `703d0672`):

  ```
  stage=runtimeApiStart   tMs=5     endpoint=169.254.100.1:9001
  stage=runtimeApiPrelude tMs=10
  stage=runtimeApiChunk   tMs=13    n=1 bytes=65536
  stage=runtimeApiChunk   tMs=2013  n=2 bytes=131072
  stage=runtimeApiChunk   tMs=4015  n=3 bytes=196608
  stage=runtimeApiPosted  tMs=4018  httpStatus=202
  stage=closed            tMs=4019
  → API Gateway: timeToAllHeaders=33  timeToFirstContent=33  integrationLatency=4031
  → client:      http_code=200  ttfb_s=0.130  total_s=4.158  bytes=196608
  ```

  | variant | api-req | first chunk written | closed | allHeaders | firstContent | integLatency | client TTFB / total |
  |---|---|---|---|---|---|---|---|
  | runtimeapi | `4f65f557` | 13 ms | 4019 ms | **33 ms** | **33 ms** | 4031 ms | 0.130 s / 4.158 s |
  | runtimeapi | `a6a1ad27` | 607 ms | 4615 ms | **858 ms** | **858 ms** | 4915 ms | 1.013 s / 5.028 s |

  Compare the buffered path on the same stack: 15 MB written by 2 ms, first content at 10069 ms.
  Here the first chunk is written at 13 ms and API Gateway has it at 33 ms — **4 seconds before the
  handler returns**. The Runtime API accepted the streaming response with **HTTP 202**. The client
  received a correct 200, the prelude's headers and all 196,608 bytes, progressively.
  `Max Memory Used: 94 MB`, against 199–345 MB for buffered bodies: the response never materializes
  in the heap, which is the one success criterion the S3 example has never actually met.

- **The price — the execution environment dies on every invocation.** After the handler returns, the
  managed runtime loop posts its own response for a request id that is already answered, gets a 4xx,
  tries to report that through `/invocation/{id}/error`, and gets 403 there too:

  ```
  Exception in thread "main" com.amazonaws.services.lambda.runtime.api.client.runtimeapi
    .LambdaRuntimeClientException: http://169.254.100.1:9001/2018-06-01/runtime/invocation/
    703d0672-…/error Response code: '403'.
  REPORT  Duration: 4201.64 ms  Billed Duration: 4202 ms  Max Memory Used: 94 MB
          Status: error  Error Type: Runtime.ExitError
  ```

  Consequences, all observed in the same log:
  - **Every invocation is recorded as `Status: error` / `Runtime.ExitError`**, despite the client
    getting a correct, complete 200. A function with a 100% error rate is unusable under any normal
    alarm or SLO.
  - **No warm reuse.** The runtime process exits, so the sandbox is discarded and the next request
    pays a restore: `RESTORE_START Runtime Version: java:25.mainline.v89` /
    `RESTORE_REPORT Restore Duration: 381.85 ms`. Run 2 shows the cost inline — `parsed` at 470 ms
    and the first chunk at 607 ms, against 3 ms and 13 ms on the already-warm run 1.
  - Billed duration is unchanged (it still covers the whole handler).

- **Side finding, and it closes an earlier caveat:** the exception class is
  `com.amazonaws.services.lambda.runtime.api.client.runtimeapi.LambdaRuntimeClientException`, which
  is **the open-source runtime interface client**. The previous entry flagged as unverified whether
  the managed `java25` runtime actually uses the `aws-lambda-java-runtime-interface-client` artifact
  whose source explains the buffering. It does. The `ThreadLocal<ByteArrayOutputStream>` in
  `EventHandlerLoader.wrapRequestStreamHandler` is therefore the real mechanism, not an analogue.

- **Resolution / status:** **Measured, reproduced twice — progressive delivery from a managed JVM
  Lambda is possible, and it is not a supported pattern.** It is undocumented, it depends on
  internal Runtime API behaviour that can change without notice, and in this form it marks every
  invocation as failed and burns the execution environment. Not shippable as-is.

  **Open follow-up, one variable:** the error and the recycle both stem from the managed loop
  posting a second response. If the handler calls `System.exit(0)` (or
  `Runtime.getRuntime().halt(0)`) immediately after its own post completes, the loop never gets to
  post. The environment is still discarded either way, but the invocation may then be recorded as a
  success rather than `Runtime.ExitError`. That single change is the difference between "unusable"
  and "usable at the cost of a ~380 ms SnapStart restore per request", so it decides whether any of
  this is publishable. Probe stack left deployed for that run; delete it afterwards with
  `aws cloudformation delete-stack --stack-name stream-emission-probe --region eu-west-1`.

---

## `System.exit(0)` / `halt(0)` do NOT rescue it — `Runtime.ExitError` is unavoidable, so the Runtime API trick stays a finding, not a technique

- **Symptom / trigger:** The self-posted streaming response works (previous entry) but every
  invocation is recorded as `Status: error` / `Runtime.ExitError`, because after the handler returns
  the managed loop posts a second response for an already-answered request, fails, and dies. The
  obvious fix is to deny the loop that chance: kill the JVM as soon as our own post returns 202.
  Added an `exit` knob to `EmissionProbeHandler` — `none` (return normally), `exit`
  (`System.exit(0)`, runs shutdown hooks) and `halt` (`Runtime.getRuntime().halt(0)`, skips them) —
  with a 100 ms pre-exit delay so the log line reaches CloudWatch.

- **Environment:** `stream-emission-probe` version 3, `CodeSha256`
  `6BDC3cmrKACPQ1jiZNnLp2b81LTmKGTwxK09uDGb2K0=`, otherwise unchanged. Command:
  `AWS_REGION=eu-west-1 RUNS=2 VARIANTS='rt-none|…&exit=none rt-exit|…&exit=exit rt-halt|…&exit=halt' ./scripts/stall-probe.sh`

- **Result — the platform verdict is identical in all three modes:**

  | variant | api-req | first chunk | closed | firstContent | integLatency | client TTFB / total | REPORT |
  |---|---|---|---|---|---|---|---|
  | rt-none | `e7d5f686` | 12 ms | 4020 ms | 59 ms | 4059 ms | 0.133 s / 4.138 s | `Runtime.ExitError` |
  | rt-none | `5194a836` | 581 ms | 4590 ms | 828 ms | 4828 ms | 0.903 s / 4.908 s | `Runtime.ExitError` |
  | rt-exit | `75bfc80e` | 489 ms | 4496 ms | 651 ms | 4662 ms | 0.730 s / 4.744 s | `Runtime.ExitError` |
  | rt-exit | `b825c134` | 453 ms | 4460 ms | 621 ms | 4621 ms | 0.699 s / 4.708 s | `Runtime.ExitError` |
  | rt-halt | `5667e123` | 491 ms | 4499 ms | 676 ms | 4675 ms | 0.753 s / 4.824 s | `Runtime.ExitError` |
  | rt-halt | `b1ed3198` | 463 ms | 4471 ms | 636 ms | 4637 ms | 0.716 s / 4.723 s | `Runtime.ExitError` |

  What killing the JVM *does* change: the `LambdaRuntimeClientException … /error Response code:
  '403'` stack trace is gone — the logs are clean, because the loop never gets to post. What it does
  not change: `Status: error  Error Type: Runtime.ExitError` on every single invocation.

  ```
  stage=runtimeApiPosted tMs=4459 httpStatus=202
  stage=closed           tMs=4460 mode=runtimeapi exit=exit
  stage=exiting          tMs=4463 via=exit delayMs=100
  REPORT  Duration: 4728.75 ms  Billed Duration: 4729 ms  Max Memory Used: 121 MB
          Status: error  Error Type: Runtime.ExitError
  ```

- **Why it cannot be fixed from handler code:** `Runtime.ExitError` means *the runtime process
  exited*. Exiting on purpose is indistinguishable from crashing, as far as the platform's
  bookkeeping is concerned: the invocation was never completed **by the runtime loop**, and a
  response delivered through our own side-channel post does not appear in that ledger even though
  the service did hand it to the caller. There are only three ways out of the handler and all three
  are errors — return (loop double-posts → exception → `Runtime.ExitError`), kill the JVM
  (`Runtime.ExitError` directly), or block until the deadline (`Task timed out`, plus full-timeout
  billing). Making the loop's own post succeed is impossible, because the invocation is already
  answered.

- **Second measured cost: no warm reuse, so the steady state pays a restore per request.** Because
  the environment is destroyed every time, every subsequent request starts with a SnapStart restore.
  The one row with a 12 ms first chunk and 0.133 s TTFB (`e7d5f686`) is the invocation that happened
  to follow the buffered-mode warmup on a live environment; every other row sits at 450–580 ms to
  first chunk and ~0.7–0.9 s TTFB. That is the honest number for this approach: **~0.7 s to first
  byte, not ~0.13 s**. `Max Memory Used` stayed at 119–121 MB regardless of body size.

- **Resolution / status:** **Closed. Progressive delivery from a managed `java25` Lambda is
  possible, and it is not usable.** It needs a 100% invocation error rate and the loss of warm
  starts, on top of being undocumented and dependent on internal Runtime API behaviour. Verdict for
  the repo: this is an **article finding**, kept reproducible via `streaming-probe` /
  `deployment/aws/sam-probe/` / `scripts/stall-probe.sh`, and it is **not** a candidate for a
  published `streaming-runtime-api` module. The earlier architecture sketch (new module alongside
  `streaming-core`, SSE framing retargeted onto an incremental sink) is therefore dropped — there is
  no supportable sink to target.

  What the article can now state, all measured on this repo's own stacks: response streaming on the
  managed JVM runtime buys **large payloads past the 6 MB/10 MB buffered caps** and nothing else;
  **early first byte and incremental production are not available**; the SSE padding workaround is
  **not** defeating a runtime buffer and makes time-to-first-token worse; and the Runtime API
  side-channel proves the platform itself is willing to stream, so the gap is purely in the managed
  Java runtime's invocation loop — which is a concrete, reportable ask for AWS rather than a
  limitation of the JVM.

  Probe stack deleted after this run.

---

## SOLVED: true progressive streaming on managed `java25` — swap the invocation loop with `AWS_LAMBDA_EXEC_WRAPPER`, no layer, no custom runtime, SnapStart intact

- **Symptom / trigger:** Everything above says the same thing — the managed runtime buffers the
  response because AWS's runtime interface client hands the handler a
  `ThreadLocal<ByteArrayOutputStream>` and posts it after `handleRequest` returns, and the
  handler-side Runtime API side channel works but costs a `Runtime.ExitError` per invocation. The
  remaining question was whether the RIC itself can be displaced from inside the deployment package.

- **Step 1 — why shadowing the RIC cannot work (read-only `mode=inspect`).** The JVM command line on
  managed `java25`/arm64:

  ```
  /var/lang/bin/java -XX:MaxHeapSize=891289k -XX:+UseSerialGC
    --add-opens=java.base/java.io=ALL-UNNAMED
    -Dorg.crac.Core.Compat=com.amazonaws.services.lambda.crac
    -XX:+ErrorFileToStderr -XX:UseSVE=0 --enable-native-access ALL-UNNAMED
    -Dcom.amazonaws…runtimeapi.NativeClient.JNI=/var/runtime/lib/jni/libaws-lambda-jni.linux-aarch_64.so
    -classpath /var/runtime/lib/aws-lambda-java-core-1.4.0.jar
              :/var/runtime/lib/aws-lambda-java-runtime-interface-client-2.12.2-linux-aarch_64.jar
              :/var/runtime/lib/aws-lambda-java-serialization-1.1.5.jar
              :/var/runtime/lib/utils-lite-2.34.0.jar
    com.amazonaws.services.lambda.runtime.api.client.AWSLambda <handlerClass>
  ```

  `/var/task` is **not on the JVM classpath at all** — customer code loads from `file:/var/task/`
  through a separate `CustomerClassLoader`, while the RIC loads from `/var/runtime` on the `app`
  loader. Parent-first delegation therefore guarantees a class shipped under an RIC package name can
  never win. Two incidental facts worth keeping: the deployed RIC is **2.12.2**, and the runtime's
  own `aws-lambda-java-core` is **1.4.0** even when the deployment package ships 1.4.1 — the
  runtime's copy wins for `com.amazonaws.*`.

- **Step 2 — the lever.** `AWS_LAMBDA_EXEC_WRAPPER` is
  [supported on all native (managed) runtimes](https://docs.aws.amazon.com/lambda/latest/dg/runtimes-modify.html)
  and excluded only on the `provided.*` OS-only family. A wrapper script **in the deployment
  package** (no layer) receives that whole command line as `"$@"` — confirmed, `argc=14` — and can
  rewrite it. A bare `.jar` cannot carry an executable script, so the package becomes a `.zip`:
  `lib/<fat jar>` plus `wrapper.sh` at mode `0755`, with
  `AWS_LAMBDA_EXEC_WRAPPER=/var/task/wrapper.sh`.

- **Step 3 — own the loop.** `wrapper.sh` in `main` mode prepends the function jar to `-classpath`
  and replaces `com.amazonaws.services.lambda.runtime.api.client.AWSLambda` with
  `nl.vintik.lambda.probe.StreamingRuntimeMain` (~150 lines): `GET /invocation/next`, then a chunked
  `POST /invocation/{id}/response` carrying `Lambda-Runtime-Function-Response-Mode: streaming` and
  the prelude content type, whose **`OutputStream` is handed straight to the handler**. The handler
  is untouched — still a plain `RequestStreamHandler` writing the prelude through `streaming-core`'s
  `ResponseWriter`. Because this loop posts exactly once, there is no duplicate response and none of
  the earlier `Runtime.ExitError`.

  The handler's own diagnostic line is the tell:
  `outputClass=sun.net.www.protocol.http.HttpURLConnection$StreamingOutputStream` — a live socket,
  where every other measurement in this log reads `java.io.ByteArrayOutputStream`.

- **Measured result.** `stream-emission-probe` version 6/7, `java25`, `arm64`, 1024 MB, SnapStart on,
  REGIONAL REST API, `ResponseTransferMode: RESPONSE_STREAM`:

  | variant | shape | first write | handler closed | firstContent | integLatency | client TTFB / total | REPORT |
  |---|---|---|---|---|---|---|---|
  | stall-big | 15 MB, then sleep 10 s | 45 ms | 10185 ms | **49 ms** | 2723 ms | 0.126 s / **2.806 s** | ok |
  | stall-big | same | 382 ms | 10485 ms | **700 ms** | 3358 ms | 0.784 s / 3.446 s | ok |
  | sse-paced | 3 × 64 KB, 2 s apart, no `Content-Length` | 3 ms | 4006 ms | **25 ms** | 4114 ms | **0.099 s / 4.196 s** | ok |
  | sse-paced | same | 5 ms | 4008 ms | **57 ms** | 4056 ms | 0.129 s / 4.152 s | ok |
  | tiny-warm | 1 KB | 0 ms | 1 ms | 29 ms | 25 ms | 0.103 s | ok |
  | tiny-warm | 1 KB | 1 ms | 1 ms | 32 ms | 28 ms | 0.107 s | ok |

  - **Progressive delivery, for real.** `sse-paced` is the token-streaming shape: first byte reaches
    the client at **2.4% of total**, and the integration stream stays open the full 4 s. The 15 MB
    case delivers the complete body to the client in 2.8 s while the handler is still sleeping until
    10.2 s.
  - **No errors.** Every `REPORT` is clean — no `Status: error`, no `Error Type`. The Runtime API
    answered `postStatus=202` on every invocation.
  - **SnapStart works.** `RESTORE_START Runtime Version: java:25.mainline.v89`,
    `RESTORE_REPORT Restore Duration: 189.26–673.20 ms`, `Billed Restore Duration: 0–184 ms`.
  - **Warm reuse works.** Five consecutive invocations in one environment with no `INIT_START` and no
    `RESTORE_START` between them; the two 1 KB ones billed **6 ms** each.
  - **Memory is finally bounded.** `Max Memory Used: 87–89 MB` for a 15 MB body, against 199–345 MB
    on the buffered path. The body never exists as a byte array.

- **What it costs — you are replacing AWS's invocation loop.** The 150-line probe loop implements
  none of what the RIC provides: X-Ray trace propagation, the platform's structured JSON logging and
  log-level handling, POJO `RequestHandler<I, O>` handlers and their serialization, `CognitoIdentity`
  / `ClientContext`, AWS-shaped error payloads with stack traces, graceful shutdown, and whatever the
  Telemetry/Extensions API expects. Anything relying on those breaks silently.

  Also: swapping the main class is **not** a documented use of `AWS_LAMBDA_EXEC_WRAPPER` (the wrapper
  itself is documented and supported; rewriting the runtime's argv is not). A runtime update that
  changes flag order or the main class name breaks the wrapper — ours degrades to AWS's own loop
  (buffered, working) rather than failing, because the replacement is a literal match on the main
  class name. Billed duration still covers the whole handler (4011 ms for the 4 s paced stream).

- **Untested, and it matters for this repo:** the runtime still passes
  `-Dorg.crac.Core.Compat=com.amazonaws.services.lambda.crac`, SnapStart restores happen, and the
  probe has no CRaC hook — so whether a customer `org.crac.Resource` `beforeCheckpoint` hook (the
  `Priming` class in every example module here) still fires under a replaced main loop is **not
  established**. Also untested: concurrency beyond 2, payloads near the 200 MB streaming limit,
  Managed Instances as a capacity provider, and the 2 MBps bandwidth cap after the first 10 MB on
  REST API (6 MB on Function URLs).

  One incidental observation: `GET /invocation/next` did **not** advertise
  `Lambda-Runtime-Function-Response-Mode` (`platformResponseMode=<none>`) even though API Gateway
  invoked through the response-streaming integration — the streaming post was accepted regardless.

- **Also closed, for free:** **Lambda Web Functions cannot help** — that form factor is Node.js only.

- **Resolution / status:** **Progressive HTTP response streaming from a Kotlin/JVM Lambda on the
  managed `java25` runtime is achievable, with SnapStart, warm reuse, bounded memory and clean
  invocation status, using only a wrapper script in the deployment package.** The price is owning the
  invocation loop and depending on the runtime's argv shape. Reproducible via `streaming-probe`,
  `deployment/aws/sam-probe/` (`WrapperMode=main`) and `scripts/stall-probe.sh`. Probe stack deleted
  after the run.

  Open decision, not taken here: whether this graduates from a finding into something this repo
  ships — a documented pattern in the article, an example module, or a small published
  `streaming-runtime-loop` artifact — and if so, how much of the RIC's behaviour the loop must
  reimplement before it is honest to recommend. The CRaC-hook question above gates that, since every
  example in this repo primes with CRaC.

---

## The streaming loop silently disables CRaC/SnapStart hooks — priming never runs

- **Symptom / trigger:** The wrapper-swapped invocation loop (previous entry) streams correctly with
  SnapStart restores happening, but the probe had no CRaC hook, so whether a customer
  `org.crac.Resource` still receives its callbacks was unestablished. Every example module in this
  repo primes with one, so this gates whether the loop is usable here. Added `ProbePriming` (a
  `Resource` that only logs, registered in its constructor) plus an eager
  `val PRIMING = ProbePriming()` in `EmissionProbeHandler`'s companion, then compared
  `WrapperMode=log` (AWS's loop) against `WrapperMode=main` (ours).

- **Result:**

  | loop | `[probe-crac] registered` | `beforeCheckpoint` | `afterRestore` | SnapStart restore |
  |---|---|---|---|---|
  | AWS `AWSLambda` (control) | yes ×2 | **yes ×2** | **yes** | `RESTORE_REPORT 449.15 ms` |
  | `StreamingRuntimeMain` (ours) | yes ×2 | **never** | **never** | `RESTORE_REPORT 343.48 ms` |

  The hook registers in both cases — the handler is constructed during init either way. The
  microVM is still snapshotted and restored under our loop. What is missing is anything driving the
  CRaC callbacks: no priming before the snapshot, no `afterRestore` work. Streaming itself was
  unaffected in the same deployment (paced run: client `ttfb=0.221 s` of `total=4.238 s`).

- **Why, inferred:** the callbacks are driven by the runtime, not the platform. The JVM is launched
  with `-Dorg.crac.Core.Compat=com.amazonaws.services.lambda.crac`, which points `org.crac` at an
  AWS compat implementation (`com.amazonaws.services.lambda.crac.Core`) on the runtime classpath,
  and the Runtime API has a SnapStart-specific `GET /runtime/restore/next` endpoint. AWS's
  `AWSLambda` loop coordinates both. Ours calls neither, so the registered resources are simply
  never visited. Not verified by reading that compat class — the class name is inferred from the
  system property value.

- **Resolution / status:** **Measured. The streaming loop and CRaC priming are mutually exclusive as
  implemented.** Restoring hook support would mean reflectively driving AWS's CRaC compat class and
  the `/runtime/restore/next` endpoint — more undocumented internals on top of an already
  undocumented argv rewrite. Untested. Note one mitigating fact for this repo specifically: the
  Kotlin examples do not prime today either (next entry), so the loop would not regress their actual
  behaviour — it would make correct priming harder to add later.

---

## `Priming` is never instantiated in either Kotlin example — the CRaC hook has never been registered

- **Symptom / trigger:** Found while wiring the probe's CRaC test. `Priming` registers itself in its
  constructor (`init { Core.getGlobalContext().register(this) }`), so something must construct it
  during initialization. In `streaming-s3-example-java` something does:
  `private static final Priming PRIMING = new Priming();` in `StreamHandler`. In
  **`streaming-s3-example` and `streaming-bedrock-example` nothing constructs it** —
  `S3StreamingHandler` and `BedrockStreamingHandler` only wire their stream handlers, and a repo-wide
  search for a construction site under `src/main/**` finds only the Java module's field.

- **Consequence:** in both Kotlin example stacks the resource is never registered, so
  `beforeCheckpoint` never runs and no critical path is warmed before the snapshot. The class is
  fully unit-tested (which is why coverage never flagged it) and completely inert in production. The
  `tech.md` cold-start standard requires priming; two of three examples do not meet it.

- **Resolution / status:** **OPEN — a real bug, not recorded as fixed.** The fix is one eager field
  per Kotlin handler, mirroring the Java example, e.g.
  `private companion object { @Suppress("unused") val PRIMING = Priming() }` in
  `S3StreamingHandler` / `BedrockStreamingHandler`. Verification is a log line: deploy, then look for
  `Priming critical paths before checkpoint` in the function log group during the snapshot phase —
  the probe's control run proves a correctly registered hook does appear there. Worth checking
  whether any cold-start numbers previously attributed to priming were measured without it.

---

## SAM `AutoPublishAlias` does not publish a version for a config-only change — env-var updates never reach the alias

- **Symptom / trigger:** Flipping the probe's `WrapperMode` template parameter from `log` to `main`
  deployed "successfully" and the stack parameter read `main`, but the function kept behaving as
  `log`, and a repeat deploy reported `Error: No changes to deploy`. Direct inspection:

  ```
  $LATEST            PROBE_WRAPPER_MODE=main  CodeSha256=a7pEBOOvL3AHqxMuf49y98Od0hkwJuLHnq5XQbQb2hQ=
  version 8 (alias)  PROBE_WRAPPER_MODE=log   CodeSha256=a7pEBOOvL3AHqxMuf49y98Od0hkwJuLHnq5XQbQb2hQ=
  ```

- **Root cause:** `AutoPublishAlias` publishes a new `AWS::Lambda::Version` when the **code** changes.
  A published version is an immutable snapshot of code *and* configuration, so a configuration-only
  change updates `$LATEST` and leaves the alias pointing at the previous version with the old
  environment. API Gateway invokes the alias, so the change is invisible — with no error anywhere.

- **Why it matters beyond the probe:** every stack here uses `AutoPublishAlias: live` and passes
  configuration through template parameters (`JAVA_TOOL_OPTIONS`, `BUCKET_NAME`,
  `NovaLiteProfileId`, memory, timeout). Changing any of those **without** also changing the jar
  will not take effect on the served alias. Any past experiment that flipped a parameter and
  re-measured without rebuilding was measuring the old configuration.

- **Resolution / status:** **Resolved for the probe, and a standing trap to check for elsewhere.**
  The probe's `lambdaZip` now embeds a per-build stamp file (`writeDeployStamp`), so every deploy has
  a unique `CodeSha256` and therefore publishes a version that actually carries the new environment.
  General rule: after a config-only deploy, verify with
  `aws lambda get-function-configuration --function-name <fn>:$(aws lambda get-alias … --query FunctionVersion --output text)`
  that the ALIAS version carries the value, not just `$LATEST`. The repo's own convention of
  confirming `CodeSha256` before trusting results covers the code case but not this one.

---

## BEST RESULT: a Java agent that patches one RIC method — streaming, CRaC hooks alive, warm reuse, clean status, shippable as a library

- **Symptom / trigger:** The wrapper-script approach streams but (a) cannot be delivered as a
  library — a jar cannot set `AWS_LAMBDA_EXEC_WRAPPER`, mark a file executable or change `CodeUri` —
  and (b) replaces AWS's invocation loop, which was measured to silently stop CRaC hooks firing. The
  handler-side chunked `POST` streams correctly on its own; its ONLY defect is that AWS's loop then
  posts a second response for an already-answered invocation and dies as `Runtime.ExitError`. So the
  minimum viable fix is to suppress that one post and leave everything else of AWS's loop intact.

- **Delivery mechanism — a `-javaagent`, no wrapper script.** `JAVA_TOOL_OPTIONS` (already set by
  every template here) accepts `-javaagent:`, and `premain` runs before AWS's `AWSLambda.main`.
  Confirmed working from the deployment package with **no `AWS_LAMBDA_EXEC_WRAPPER` set at all**
  (template `WrapperMode=none`, which omits that variable via a `Conditions` / `!If` on the whole
  `Variables` block):
  ```
  JAVA_TOOL_OPTIONS = -XX:+TieredCompilation -XX:TieredStopAtLevel=1
                      -javaagent:/var/task/lib/stream-emission-probe.jar
  AWS_LAMBDA_EXEC_WRAPPER = <absent>
  ```
  `Premain-Class` is set on the function's own shadow jar manifest, so the agent and the function
  are one artifact. **The package must be a zip carrying `lib/<jar>`**, because `-javaagent` needs a
  real jar FILE and a bare-jar `CodeUri` is extracted loose into `/var/task`. That is the standard
  Java Lambda zip layout, so it costs one Gradle `Zip` task.

- **Discovery findings that shaped it (phase 1, transformed nothing):**
  - The agent loads and can retransform: `premain jvm=25.0.5+10-LTS`, `canRetransform=true`.
  - All five watched RIC classes pass through the transformer, from
    `/var/runtime/lib/aws-lambda-java-runtime-interface-client-2.12.2-linux-aarch_64.jar` on the
    `app` loader — so they CAN be patched even though they cannot be shadowed.
  - The method that posts the response is
    **`LambdaRuntimeApiClientImpl.reportInvocationSuccess(String, byte[], String): void`** — not
    `postInvocationResponse` as the published source suggested. Siblings worth knowing:
    `restoreNext(): void` (the SnapStart restore hook, confirming the earlier inference),
    `reportInitError`, `reportRestoreError`, `reportInvocationError(String, LambdaError, String)`.
  - **`AWSLambda` holds no field for the client** — its declared fields are constants plus
    `customerClassLoader`. The client is a local in `main`, so a reflection-only variant (swap the
    instance for a `java.lang.reflect.Proxy`) is impossible. Bytecode is required.

- **The patch.** `ResponsePostSuppressor` is a `ClassFileTransformer` that prepends one guard to that
  one method:
  ```java
  if (StreamingState.alreadyAnswered()) return;
  ```
  Built with the **JDK's own Class-File API** (`java.lang.classfile`, final in Java 24), so there is
  **no ASM dependency** on a Java 25 runtime. `13505 -> 13584 bytes`. Two details that matter:
  - The patched class is on the **app** loader while function code is on the child
    `CustomerClassLoader`, and a parent cannot see a child's classes — so `premain` calls
    `Instrumentation.appendToSystemClassLoaderSearch(<function jar>)`, the documented mechanism for
    this, making one shared `StreamingState` visible to both. Without it the guard would throw
    `NoClassDefFoundError`.
  - It **fails safe**: any parse/transform failure, or a missing append, returns the class untouched
    and the function stays on AWS's normal buffered path rather than breaking. `StreamingState` is a
    per-thread flag reset at the start of every invocation, so a reused environment cannot leak an
    "answered" flag into the next request.

- **Measured result** (`stream-emission-probe` version 12, `CodeSha256`
  `5bb3BeL/U18pRrnuVPKTct4oNnIF3V+MwcQqDXSxesc=`, `java25`/`arm64`/1024 MB, SnapStart on, REGIONAL
  REST API, `RESPONSE_STREAM`; 3 × 64 KB chunks 2 s apart):

  | api-req | first write | handler closed | allHeaders | firstContent | integLatency | client TTFB / total | REPORT |
  |---|---|---|---|---|---|---|---|
  | `2b72ae96` | 22 ms | 4028 ms | 60 ms | **60 ms** | 4060 ms | 0.207 s / 4.508 s | **ok** |
  | `d823a17b` | 3 ms | 4007 ms | 27 ms | **27 ms** | 4027 ms | 0.191 s / 4.206 s | **ok** |

  Everything the previous approaches each gave up, this one keeps:
  - **Progressive delivery** — first content at API Gateway 27–60 ms into a 4 s stream; client first
    byte at ~4.5% of total.
  - **Clean invocation status** — `REPORT=ok`, no `Status: error`, no `Runtime.ExitError`. The patch
    does what the `System.exit(0)` experiment could not.
  - **CRaC hooks fire** — `[probe-crac] registered` ×2, `beforeCheckpoint` ×2, `afterRestore` ×1,
    because AWS's loop is untouched. This is the decisive difference from the wrapper approach.
  - **Warm reuse** — one `RESTORE_REPORT Restore Duration: 456.07 ms`, then three invocations in the
    same environment with no `INIT_START`/`RESTORE_START` between them (billed 542 ms, 4030 ms,
    4009 ms).
  - **Bounded memory** — `Max Memory Used: 97 MB` throughout.
  - **No wrapper script, no layer, no custom runtime, no argv rewriting.**

- **What is still owned by the consumer:** writing the streamed response itself (open the chunked
  `POST` to `/invocation/{id}/response` with
  `Lambda-Runtime-Function-Response-Mode: streaming` + the prelude content type, then write prelude
  + 8 NUL bytes + body — `streaming-core`'s `ResponseWriter` unchanged), the zip packaging, and the
  `-javaagent` flag. Everything else — `nextInvocation`, error reporting, `restoreNext`, CRaC
  coordination, logging, `Context` construction, handler discovery — stays AWS's.

- **Residual risks, unchanged by this result:** the patch matches one method name and descriptor in
  RIC 2.12.2; a runtime update that renames or re-signs it means no patch (fails safe to buffered,
  but silently — a real library must expose whether the patch applied and refuse to stream blind).
  Posting the response from handler code is still undocumented behaviour. Untested: concurrency
  beyond 2, payloads near the 200 MB limit, Managed Instances, the 2 MBps cap after the first 10 MB
  on REST API, and interaction with X-Ray.

- **Resolution / status:** **This is the shippable shape.** Progressive HTTP response streaming from
  a Kotlin/JVM Lambda on the managed `java25` runtime, with SnapStart and CRaC priming intact, warm
  reuse, bounded memory and clean invocation status — delivered as a dependency plus one
  `JAVA_TOOL_OPTIONS` flag and a zip layout. Reproducible via `streaming-probe`,
  `deployment/aws/sam-probe/` (`WrapperMode=none`) and `scripts/stall-probe.sh`. Probe stack deleted
  after the run. No decision taken yet on whether this graduates into a published module, and the
  article is still untouched.

---

## The token shape works too: 24-byte events stream progressively with NO padding — the padding workaround is obsolete

- **Symptom / trigger:** Every streaming measurement on the agent path so far used chunks of 64 KB or
  1 MB. The original failure mode was specifically about **small** writes — a few bytes per LLM
  token — and `HttpURLConnection.setChunkedStreamingMode(0)` picks a default chunk size (4 KB in the
  JDK), so sub-chunk writes could in principle still coalesce until the buffer fills. Untested, and
  decisive for the actual use case.

- **Test:** 60 events of **24 bytes**, 100 ms apart — a 1,440-byte total body, i.e. the LLM-token
  shape — against the agent build (`stream-emission-probe` version 13, `CodeSha256`
  `tz9a3FMwXFg4UcurwGYvHgaSjpkvgwOQYsUdQatIXuw=`, `WrapperMode=none`, no wrapper script), with a
  64 KB-chunk run alongside as the reference.

  ```
  AWS_REGION=eu-west-1 RUNS=2 VARIANTS='tokens-24b|mode=runtimeapi&chunks=60&chunkBytes=24&gapMs=100
    chunks-64k|mode=runtimeapi&chunks=3&chunkBytes=65536&gapMs=2000' ./scripts/stall-probe.sh
  ```

  | variant | body | first write | handler closed | firstContent | integLatency | client TTFB / total | REPORT |
  |---|---|---|---|---|---|---|---|
  | **tokens-24b** | **1,440 B** | 7 ms | 5944 ms | **46 ms** | 5976 ms | **0.211 s / 6.145 s** | ok |
  | **tokens-24b** | 1,440 B | 2 ms | 5935 ms | **19 ms** | 5947 ms | **0.183 s / 6.108 s** | ok |
  | chunks-64k | 196,608 B | 3 ms | 4005 ms | 26 ms | 4022 ms | 0.167 s / 4.278 s | ok |
  | chunks-64k | 196,608 B | 2 ms | 4006 ms | 21 ms | 4019 ms | 0.166 s / 4.182 s | ok |

- **Result:** small writes stream. API Gateway received the first content **19–46 ms** in, the
  integration stream stayed open for the full ~6 s, and the client's first byte arrived at **3% of
  total**. Per-write `flush()` on the chunked connection does emit a partial chunk — the JDK's
  default chunk length does not hold small writes. `REPORT=ok` on every run.

- **Why this retires the padding workaround.** Same endpoint, same stack, the two approaches to
  getting bytes to a client early:

  | approach | on the wire | client TTFB | client total | TTFB as % of total |
  |---|---|---|---|---|
  | tiered SSE padding (buffered path) | **18 MB** | 8.68 s | 12.48 s | 70% |
  | agent + streamed response | **1.4 KB** | 0.18 s | 6.11 s | **3%** |

  Roughly **12,500× fewer bytes** and a first byte ~48× sooner. The padding was never defeating a
  runtime buffer (there is none); it was inflating the body so the download took long enough to look
  progressive. With a genuinely streamed response there is nothing to work around, and
  `PaddedSseWriter`'s reason to exist is gone.

- **Concurrency note (reasoned, not measured):** `StreamingState` is a `ThreadLocal`, and the handler
  and AWS's `reportInvocationSuccess` call run on the same thread — the handler's own log lines are
  `[main]`, and the RIC's loop posts from the thread it invoked on. On Event Functions · On-Demand an
  execution environment serves one invocation at a time, so the flag cannot be observed by another
  invocation. A multi-concurrent provider (Managed Instances) runs invocations on separate threads,
  which the per-thread flag should also handle by construction — but that is untested.

- **Resolution / status:** **The original question is answered end to end.** Progressive,
  token-by-token HTTP response streaming from a Kotlin/JVM Lambda behind API Gateway REST, on the
  managed `java25` runtime, with SnapStart and CRaC priming intact, warm reuse, bounded memory,
  clean invocation status, no layer, no custom runtime, no wrapper script, no padding — delivered as
  a dependency plus one `JAVA_TOOL_OPTIONS` flag and a zip layout. Probe stack deleted after the run.

---

## End to end on the real workload: Bedrock Nova Lite tokens stream token-by-token with no padding, 12 KB on the wire

- **Symptom / trigger:** Everything up to here used synthetic filler. The capstone question is
  whether the agent path delivers the workload that started this investigation: Amazon Bedrock Nova
  Lite deltas relayed as SSE, with **no padding**, arriving progressively at the client. Added
  `mode=bedrock` to the probe (`BedrockTokenProbe`) which writes one unpadded `data: <text>\n\n` per
  model delta and flushes, straight into the live Runtime API connection. Scoped
  `bedrock:InvokeModelWithResponseStream` IAM on the probe stack, mirroring `sam-bedrock`.

- **Environment:** `stream-emission-probe` version 14, `WrapperMode=none` (**no wrapper script**;
  `AWS_LAMBDA_EXEC_WRAPPER` absent), agent via
  `JAVA_TOOL_OPTIONS=… -javaagent:/var/task/lib/stream-emission-probe.jar`, `java25`/`arm64`/1024 MB,
  SnapStart on, REGIONAL REST API, `RESPONSE_STREAM`, Nova Lite `eu.amazon.nova-lite-v1:0`,
  `maxTokens=2000`.

- **Client view** (`GET /probe?mode=bedrock&maxTokens=2000`, per-line arrival timestamps):

  | run | tokens | answer chars | **on the wire** | token spread | 500 ms buckets | **client TTFB** | total |
  |---|---|---|---|---|---|---|---|
  | 1 | 595 | 7,316 | **12,343 B** | 8,350 ms | **17** | **0.172 s** | 11.724 s |
  | 2 | 688 | 8,131 | **14,121 B** | 8,844 ms | **18** | **0.199 s** | 9.461 s |

- **Server view** (API Gateway access log joined to the handler clock, run 2):

  ```
  timeToAllHeaders=31   timeToFirstContent=307   integrationLatency=9280   responseLength=14121
  stage=bedrockToken  tMs=760   n=3    eventBytes=39
  stage=bedrockToken  tMs=1936  n=100  eventBytes=9
  stage=bedrockToken  tMs=4318  n=300  eventBytes=14
  stage=bedrockToken  tMs=8066  n=600  eventBytes=12
  stage=bedrockEnd    tMs=9254  events=688
  stage=runtimeApiPosted tMs=9255 httpStatus=202 answeredFlagSet=true
  REPORT  Max Memory Used: 145 MB   (no Status: error, no Error Type)
  [probe-crac] registered / beforeCheckpoint      (priming hooks still driven)
  ```

  Response **headers reach API Gateway at 31 ms**, before any token exists — which is what an SSE
  client needs to start rendering — and content then flows from ~307 ms until the generation ends at
  9.3 s. Individual events are **9–39 bytes**. Memory is 145 MB (higher than the 97 MB filler runs
  only because the Bedrock SDK client is loaded) and does not track response size.

- **Against the padded workaround this repo currently ships**, same endpoint shape, same model:

  | approach | on the wire | client TTFB | TTFB as % of total | token spread | buckets |
  |---|---|---|---|---|---|
  | tiered SSE padding (buffered path) | **18,113,007 B** | 8.68 s | 70% | 3.8 s (download) | 8 |
  | agent + streamed response, no padding | **14,121 B** | **0.199 s** | **2%** | 8.8 s (generation) | **18** |

  About **1,280× fewer bytes**, first token roughly **44× sooner**, and the trickle now spans the
  actual generation rather than the download of an inflated body. This is genuine token-by-token
  delivery, not a typewriter effect produced by a slow download.

- **Resolution / status:** **The original question is fully answered on a real workload.**
  Progressive SSE token streaming from a Kotlin/JVM Lambda behind API Gateway REST on the managed
  `java25` runtime — SnapStart and CRaC priming intact, warm reuse, bounded memory, clean invocation
  status, no layer, no custom runtime, no wrapper script, no padding — delivered as a dependency plus
  one `JAVA_TOOL_OPTIONS` flag and a zip layout. The padding workaround, `PaddedSseWriter` and the
  `streaming-sse` module's reason to exist are superseded. Probe stack deleted after the run; the
  `mode=bedrock` probe, the agent and the driver script stay in the repo so this is re-runnable.

---

## Implemented: `streaming-sse` 3.0 streams for real, padding removed, Bedrock example migrated, SnapStart hooks fixed

- **Symptom / trigger:** With the agent approach proven (entries above), the findings had to become
  code: a library that streams, an example that uses it, a post-deploy test that can actually fail,
  and the SnapStart priming bug fixed. Everything below is implemented and verified locally; the
  endpoint has not been redeployed yet.

- **`streaming-sse` rewritten around a real transport.** New public API:
  - `StreamingResponse.send(requestId, metadata) { live -> … }` — opens a chunked `POST` to the
    Runtime API, writes the prelude through `streaming-core`'s `ResponseWriter`, runs the body with
    the **live** stream, completes, then marks the invocation answered. Refuses to stream when the
    agent is absent, so a misconfigured deploy fails with one explanatory error instead of
    `Runtime.ExitError` per request.
  - `SseWriter` — framing only, and it **splits multi-line text across `data:` lines**, fixing the
    long-standing bug where everything after the first newline was dropped by a conforming parser.
  - `StreamingAgent.premain` / `isPatched()`, `StreamingState`, `ResponseChannel` +
    `RuntimeApiChannelFactory` (the connection, behind an interface so it is testable).
  - **`PaddedSseWriter` and `PacedSseEmitter` deleted**, with their nine test files. Their premise —
    a ~100 KB runtime buffer — does not exist. This is a **breaking change**; see the release note
    below.

- **Two implementation traps, both caught by the bytecode test rather than in production:**
  1. **A branch needs a stack map frame.** Injecting `IFEQ` into a class file of version 50 or later
     without a frame at the branch target fails verification. `F_SAME` describes it exactly, since
     at that point the stack is empty and the locals are the method's parameters — emitting it by
     hand avoids `COMPUTE_FRAMES`, which would otherwise have to resolve the runtime's own class
     hierarchy. `visitMaxs` also has to raise `maxStack` to at least 1.
  2. **A Kotlin `object`'s members are not static.** The injected `INVOKESTATIC
     StreamingState.alreadyAnswered()Z` failed with
     `IncompatibleClassChangeError: Expected static method`. `@JvmStatic` on the three members is
     load-bearing, not cosmetic.

  The test generates a synthetic class with the same method name and descriptor, patches it, loads
  it (so the JVM verifier runs) and asserts a counter proves the guard short-circuits. Both bugs
  would have shipped otherwise.

- **ASM instead of the JDK Class-File API.** The probe used `java.lang.classfile`, which needs no
  dependency — but it is Java 24+, and `streaming-sse` deliberately targets **Java 21** so it stays
  usable on the `java21` Lambda runtime. `org.ow2.asm:asm` 9.11.1 added to the version catalog,
  `implementation`-scoped.

- **`StreamingResponse` takes a `String` request id, not a `Context`.** The module's stated design is
  that no AWS artifact flows through it; taking `Context` would have broken that for one field.

- **Bedrock example migrated.** `BedrockStreamHandler` now writes through `StreamingResponse` +
  `SseWriter`; `PromptRequest` lost `headPadBytes` / `tailPadBytes` / `headPadEvents`; `Priming`
  warms `SseWriter` instead of the padded writer. Pre-commit errors (400/502) still go out
  **buffered** on the runtime's own stream — nothing is committed at that point, and a short JSON
  body does not need streaming. Deployment: `CodeUri` is now
  `bedrock-streaming-endpoint.zip` (`lib/<fat jar>`), the shadow jar manifest carries
  `Premain-Class`, and `JAVA_TOOL_OPTIONS` gained
  `-javaagent:/var/task/lib/bedrock-streaming-endpoint.jar`. Both deploy workflows now name the
  **zip** as the artifact to sanity-check, since the jar is still produced and would have masked a
  missing zip.

- **SnapStart priming bug fixed.** `S3StreamingHandler` and `BedrockStreamingHandler` each gained
  `private companion object { @Suppress("unused") private val PRIMING = Priming() }`, mirroring the
  Java example's static field, so class initialization registers the CRaC hook. Each has a
  regression test that reads the static field reflectively — the one thing the existing `PrimingTest`
  could not catch, because it constructs `Priming` itself and so supplies the missing construction
  site.

- **`scripts/bedrock-streaming-test.sh` rewritten with four assertions**, each guarding a failure
  that actually occurred:
  1. **Real HTTP TTFB** from curl's `%{time_starttransfer}` must be under 35% of total. The old
     script timed from the first line it read, so its "TTFB ~0 ms" was true by construction and the
     assertion could never fail. Buffered measures ~100%; streaming measured 2%.
  2. **Progressive arrival** — tokens in ≥ 4 distinct 500 ms buckets.
  3. **No padding inflation** — bytes on the wire within 4× the answer's own size. The padded path
     was 2 227×.
  4. **Complete stream** — the `[DONE]` sentinel arrives.

  It also reports whether the deployed alias actually carries `-javaagent`, because a buffered
  result with a correct body is exactly what a missing agent looks like. The assertion logic was
  exercised offline against six synthetic metric sets (real streaming, buffered, padding
  regression, truncated, no tokens, HTTP error) and discriminates correctly.

- **Verification run locally:** full CI gate green — `verifyCoverageReports koverVerify apiCheck`
  with all six coverage reports passing, `streaming-sse` at its 90% bound and the examples at 80%;
  `apiDump` regenerated; `sam validate` exit 0 on all four templates; shellcheck clean on every
  script touched. `verify-quality-signals.sh` reports 118 passed / 7 failed, and **all seven
  failures are pre-existing** in files this work never touched (the Kotlin badge version drift, the
  codecov `files:` check, four `release-tag.yml` permission-table rows and the CONTRIBUTING workflow
  map) — confirmed by `git status` showing `README.md`, `CONTRIBUTING.md`, `.github/` and
  `verify-quality-signals.sh` unmodified.

- **Release note — this is a MAJOR version.** `streaming-sse` is published to Maven Central and two
  public classes were removed, so 2.x consumers break at compile time. `release-tag.yml` computes
  the version from Conventional Commits, so the merge needs a `feat!:` subject or a
  `BREAKING CHANGE:` footer, or it will cut a minor and publish a breaking change as 2.x.
  `streaming-core` is unchanged but shares the single `releaseVersion`, so it goes to 3.0.0 too —
  that is the existing one-tag-one-version model, not a new decision. No new module was added, so
  neither of the two documented release traps (trigger paths, publish task list) applies.
  `streaming-sse`'s POM now carries a new runtime dependency (ASM); its description was rewritten.

- **Resolution / status:** **Implemented and locally verified; not yet deployed.** Next step is a
  deploy of `bedrock-streaming-endpoint` followed by `BEDROCK_TEST_ENABLED=true
  ./scripts/bedrock-streaming-test.sh`. Still open and deliberately untouched:
  `docs/bedrock-sse-streaming.md` (the article), the root README, the `.kiro/specs/*` documents that
  describe the padding design, and the S3 example — which would also benefit, since its memory
  currently grows with object size and `StreamingResponse` would make it genuinely bounded.

---

## Probe removed — read the entries above as a record, not as runnable code

- **What was removed:** the `streaming-probe` module (including `StreamingRuntimeMain`,
  `wrapper.sh`, `ProbePriming`, `BedrockTokenProbe` and the probe's own copies of
  `StreamingAgent` / `StreamingState` / `ResponsePostSuppressor`), the throwaway
  `deployment/aws/sam-probe/` stack, and `scripts/stall-probe.sh` /
  `scripts/stream-emission-probe.sh`. Earlier entries in this log reference those paths; they are
  the record of how each measurement was taken, not files you will find in the tree.

- **Why, beyond tidiness:** once `streaming-sse` shipped the real agent, the probe held a **second,
  divergent copy** of the same mechanism. CI never compiled the probe module — `workflow-build.yml`
  runs only the coverage tasks, which do not reach it — so it would have rotted while still looking
  authoritative, and a future reader could easily have patched the wrong copy. It also carried no
  tests and was excluded from every coverage gate by design.

- **What replaces it:** the behaviour is now exercised by `streaming-bedrock-example` against the
  real stack, and asserted by `scripts/bedrock-streaming-test.sh` (real curl TTFB as a fraction of
  total, token bucket spread, a bytes-on-the-wire inflation guard, the `[DONE]` sentinel, plus a
  report of whether the deployed alias actually carries `-javaagent`). The agent's bytecode patch is
  verified in unit tests against a synthetic class, which is stronger than the probe was: it loads
  the patched class so the JVM verifier runs.

- **If the runtime changes and this has to be re-derived**, everything needed is in the entries
  above rather than in code: the full JVM command line the managed `java25` runtime launches, the
  fact that `/var/task` is absent from the classpath, the deployed RIC version and its class list,
  the exact method name and descriptor that was patched, the SnapStart/CRaC control-versus-test
  comparison, and the three CloudWatch fields to join
  (`$context.integration.timeToAllHeaders`, `timeToFirstContent`, `integration.latency`, matched to
  the handler's `timing … stage=` lines through `$context.integration.requestId`).

- **Resolution / status:** **Removed.** No stack was left deployed; the probe stack had already been
  deleted after its final run.

## Review pass on the article and READMEs: four documented behaviours that the code does not have

- **Symptom:** a pre-merge review flagged inconsistencies between the written docs and the
  implementation. Each one was checked against the current code rather than taken at face value;
  four were real, one was worth escalating beyond a doc fix.

- **1. The 200 is committed before the model is called, not at the first delta.** Three documents
  (the article, `streaming-bedrock-example/README.md`, and `BedrockStreamHandler`'s own KDoc)
  claimed the status was committed when the first Bedrock delta arrived, and that a model failure
  before that point was answered as a 502. `StreamingResponse.send` writes the metadata and the
  8-byte delimiter, flushes, and *then* runs the body — and the body is what calls Bedrock:
  ```kotlin
  val channel = channels.open(requestId)
  responseWriter.writeMetadata(channel.output, metadata)   // 200 committed here
  channel.output.flush()
  body(channel.output)                                     // Bedrock is invoked in here
  ```
  So the client has a 200 before the model is touched, and no model failure can change it. All
  three documents now say so.

- **2. Consequence worth fixing in code, not just docs (OPEN).** The 502 branch in
  `BedrockStreamHandler.stream` is unreachable from the client's point of view, and writing it is
  worse than doing nothing. On that path `StreamingState.markAnswered()` is never called — `send`
  only sets it after `channel.complete()` returns 202 — so the handler writes a 502 onto the
  runtime's buffered stream and returns normally, and AWS's loop then posts **a second response for
  an invocation the Runtime API has already accepted**. That is exactly the condition this log
  records as producing `Runtime.ExitError`. The same reasoning applies to the post-first-delta
  rethrow, which reaches the unpatched `reportInvocationError`. **Not reproduced against the
  deployed endpoint** at the time of writing; it has since been **measured and confirmed** (see
  "MEASURED: the Kotlin pre-first-delta 502 path kills the execution environment" at the end of this log). Fixing it properly is a design choice (defer the prelude until the first body
  write so the "commit at first delta" contract becomes real, mark answered on the failure path, or
  drop the dead branch), and it changes the article's guidance, so it is left for a follow-up.

- **3. A `-javaagent` path that does not match the jar is fatal, not a graceful degradation.**
  `streaming-sse/README.md` said a divergence between `archiveFileName` and the `-javaagent` flag
  meant "the agent silently does not load". It does not load at all — the JVM refuses to start:
  ```
  Error opening zip file or JAR manifest missing : /tmp/does-not-exist.jar
  Error occurred during initialization of VM
  agent library failed Agent_OnLoad: instrument
  ```
  (verified locally with `java -javaagent:/tmp/does-not-exist.jar -version`). On Lambda that is an
  init failure on every invocation. The graceful path is a different failure: the agent loads but
  cannot resolve a jar to append, or the target method has changed, which leaves `isPatched()` false
  and makes `send` throw.

- **4. "It fails safe" was read as "it fails over".** The agent failing to patch leaves AWS's loop
  intact, so the buffered path still works — but a handler built on `StreamingResponse` does not
  benefit: `send` checks `isPatched()` and throws `IllegalStateException` before opening a channel.
  There is no automatic fallback, and the README's `ChatHandler` example lets that propagate. The
  README and the article now say this, with recovery steps (check `JAVA_TOOL_OPTIONS` and `CodeUri`,
  grep the `[streaming-sse-agent]` log lines, roll back the published version).

- **5. A Gradle `Zip` task does not write to `build/dist`.** Both doc snippets showed a `lambdaZip`
  task with only `archiveFileName` set, next to a `CodeUri: build/dist/my-function.zip`. The base
  plugin's default `destinationDirectory` for a `Zip` is `build/distributions`, so a clean build
  following the docs produced an archive the template could not find. The real
  `streaming-bedrock-example/build.gradle.kts` sets `destinationDirectory` explicitly; the snippets
  now do the same.

- **6. API Gateway access logging has an undocumented account-level prerequisite.** The
  `sam-bedrock` stage carries an `AccessLogSetting`, which a REST API can only honour when the
  account's `cloudwatchRoleArn` is set **per Region**. It was set in this account already
  (`aws apigateway get-account --region eu-west-1 --query cloudwatchRoleArn` returns a role), which
  is why every deploy here worked and why the requirement was invisible. A fresh account can fail on
  the stage, or deploy and write no access log. Now documented in
  `deployment/aws/sam-bedrock/deploy.sh` and the example's Quick Start: create a role trusted by
  `apigateway.amazonaws.com`, attach `AmazonAPIGatewayPushToCloudWatchLogs`, set it as the account's
  CloudWatch role.

- **Resolution / status:** items 1, 3, 4, 5 and 6 fixed (documentation only, no behaviour change).
  Item 2 is **open** and needs a code decision plus a failure-path test against the deployed
  endpoint.

## Codecov showed 0% because the Gradle build cache served a JaCoCo report older than Codecov's 12h age limit

- **Symptom:** after the PR #37 merge, the Codecov dashboard read **0.00%** for `main` with a
  `-85.63%` change, the badge went to 0%, and no Codecov commit status was posted. The commit page
  said *"There is an error processing the coverage reports"*, **1 errored upload**, and named the
  reason: *"Upload exceeds the max age of 12h."*

- **What was NOT wrong**, checked before changing anything:
  - the upload itself succeeded — `==> Token set from input`, `Found 2 coverage files to report`,
    both paths listed, `Sending upload (41922 bytes) to storage`,
    `Upload queued for processing complete`;
  - the aggregate report was healthy — 497 covered lines against 45 missed (~91.7%), all three
    Kotlin packages present;
  - path resolution was fine. The Kover aggregate names files only as package + filename with no
    module prefix, and `nl/vintik/lambda/streaming` now exists in three modules, so path ambiguity
    looked like the obvious culprit. Testing all 34 source entries against the repo tree gave
    **0 unresolvable, 0 ambiguous, 34 uniquely resolvable**. Hypothesis dead;
  - the `check_coverage_paths` quality-signal failure is a **stale check**, not a real defect: it
    greps for `codecov/codecov-action@v5` while the workflow is on `@v7`, where the `files:` input
    is present and correct.

- **Root cause:** the JaCoCo agent stamps its execution data with wall-clock
  `<sessioninfo start=… dump=…>` times, and `jacocoTestReport` copies them into the XML. The CI log
  shows `:streaming-s3-example-java:test FROM-CACHE` and `:streaming-s3-example-java:jacocoTestReport
  FROM-CACHE` — this module's inputs had not changed, so Gradle restored both from the build cache
  and the XML carried session timestamps from whenever the tests had last actually run. Locally the
  same thing was visible: file mtime `2026-11-18 21:20`, `sessioninfo` `2026-11-17 21:39`. Codecov
  rejects a report older than 12 hours, and because **both reports travel in a single upload** (one
  `upload-coverage` call with two `--file` flags) the healthy Kotlin aggregate was discarded along
  with the expired Java report. Hence 0%, not a partial number.

- **Why it appeared now:** nothing about the Java module changed, which is exactly the condition for
  a cache hit. A branch that touches Java sources invalidates the cache and uploads a fresh report,
  which is why this had not bitten before.

- **Fix:** opt `:streaming-s3-example-java:test` out of both up-to-date skipping and the build cache,
  so the exec data is always produced in the run that uploads it:
  ```kotlin
  outputs.upToDateWhen { false }
  outputs.cacheIf { false }   // required: upToDateWhen alone still allows a FROM-CACHE restore
  ```
  `upToDateWhen` on its own is not enough — a cacheable task with up-to-date checks disabled is
  still restored from the cache. Verified: two consecutive runs both execute `test`, and
  `sessioninfo` now matches the run (`18:16:50` for a run started at `18:16:42`). The coverage gates
  are unaffected: all six reports still validate, 158 covered lines in this module.

- **Turning off Codecov's age check** (the "expired reports" toggle the error message offers) was
  rejected as the fix: it would silence a real staleness signal and keep uploading reports that
  describe a different commit's test run.

- **Note on recovery:** Codecov states the errored commit page "will not reflect the latest results,
  even if you have re-run all jobs successfully". Re-running CI on the merge commit does not repair
  it; the dashboard recovers on the next commit that uploads a fresh report.

- **Resolution / status:** **Fixed** in `streaming-s3-example-java/build.gradle.kts`. The Kover
  reports were never affected — Kover emits no `sessioninfo`, so there is nothing for the age check
  to reject.

## Java Bedrock example: SDK callback names (task 6.1)

- **Question:** are the callback names in the design (`Visitor.builder().onChunk(...)`, handler
  `.subscriber(visitor)`, `.onComplete(Runnable)`, `.onError(Consumer<Throwable>)`) correct for
  `InvokeModelWithResponseStreamResponseHandler` in the BOM-pinned AWS SDK for Java v2?
- **Result:** yes. `BedrockAsyncSource` compiled on the first attempt with exactly those names, so
  no correction was needed. `onChunk` receives a `PayloadPart`; `part.bytes().asUtf8String()` gives
  the Nova event JSON.
- **Design choices:** the client is built lazily on first `stream` call (after SnapStart restore) with
  `NettyNioAsyncHttpClient.builder().readTimeout(60s)` via `httpClientBuilder`. `MODEL_ID` defaults to
  `eu.amazon.nova-lite-v1:0` and is never logged. A package-private constructor takes
  `Supplier`s for the client and model id so task 6.2 can inject a Mockito mock.
- **Visitor safety:** the chunk callback catches `RuntimeException`, logs only the exception type and
  never touches the live stream; all writes stay on the invocation thread inside `DeltaBridge.drain`.
## Java Bedrock example: testing the SDK glue with Mockito (task 6.2)
- **Approach:** the full design approach worked, so no "keep it thin" trade-off was needed.
  `BedrockAsyncSourceTest` stubs a Mockito mock of `BedrockRuntimeAsyncClient` with `thenAnswer`,
  grabs the `InvokeModelWithResponseStreamResponseHandler` argument, and drives it directly:
  `handler.onEventStream(publisher)`, then `handler.complete()` or `handler.exceptionOccurred(e)`.
  The returned `CompletableFuture` is completed, failed or left pending per scenario. Covered:
  chunks in order then done, the request carrying model id and Nova body, non-text and malformed
  chunks skipped, error before any chunk, error after some chunks, a failed call future alone, a
  stalled stream timing out and cancelling the call, and lazy single client construction.
- **Gotcha 1: `PayloadPart.builder()` parts are invisible to the visitor.** The first run delivered
  zero deltas with no error. `PayloadPart.accept(Visitor)` throws `UnsupportedOperationException`;
  only the event-stream subtype (`responsestream.DefaultChunk`, built via
  `ResponseStream.chunkBuilder()`) dispatches to `visitChunk` / `onChunk`. The SDK's own unmarshaller
  produces the subtype, so production is unaffected, but test fixtures must use `chunkBuilder()`.
- **Publisher choice (precaution, not an observed bug):** a tiny synchronous `Publisher` test double
  (emits every part inside `request(n)`) is used instead of `SdkPublisher.fromIterable`, so every
  chunk is provably offered to `DeltaBridge` before the test calls `handler.complete()`; with an
  asynchronous publisher `Done` could in principle win and the bridge's first-terminal-wins rule
  would drop later deltas. The swap was made before gotcha 1 was found, so it did not by itself fix
  the empty-delta symptom.
- **Resolution / status:** 8 tests passing in
  `streaming-bedrock-example-java/src/test/java/nl/vintik/streaming/bedrock/BedrockAsyncSourceTest.java`.
  No production code changed.
## Java Bedrock example: async client footprint and the HTTP client exclusion (task 6.3)
- **Baseline:** `:streaming-bedrock-example-java:shadowJar` produced `build/libs/streaming-bedrock-example-java-all.jar`
  at 17,998,650 bytes (about 17.2 MiB) with the Netty async client and no exclusions.
- **Surprise: `apache-client` is not on the classpath.** With the BOM-pinned SDK (2.59.0) the default
  synchronous HTTP client is `software.amazon.awssdk:apache5-client` (Apache HttpClient 5), pulled in
  transitively by the service module. Excluding only `apache-client`, as the task says, would have been a
  silent no-op. The exclusion now covers both module names (`apache-client` and `apache5-client`) in
  `streaming-bedrock-example-java/build.gradle.kts` via `configurations.configureEach { exclude(...) }`.
- **After exclusion:** 15,638,628 bytes (about 14.9 MiB), 2,360,022 bytes (13.1%) smaller. The fat jar
  has no `org/apache/...` classes left.
- **Tests:** `./gradlew :streaming-bedrock-example-java:build -PexcludeTags=integration` is BUILD SUCCESSFUL,
  178 tests, 0 failures, 0 skipped. The exclusion is kept. Unit tests never touch a synchronous client,
  so this does not prove the real runtime path; task 13.1 confirms it on the deployed endpoint. If the
  default credentials chain ever needs a sync client (for example container, IMDS or SSO credentials),
  Lambda environment credentials do not, but that is the thing to watch there.
- **Netty cost (size):** `io/netty/**` plus the SDK's `http/nio` classes are about 9.1 MB uncompressed
  inside the fat jar, the largest single dependency block. The cold-start half is measured in task 13.2.

## Java Bedrock example: Java-from-Kotlin friction, `SseWriter` has no `@Throws` (task 8.2, Req 1.5)
- **Call site:** `BedrockStreamHandler.writeErrorEvent` and `relay` in
  `streaming-bedrock-example-java/src/main/java/nl/vintik/streaming/bedrock/BedrockStreamHandler.java`
  call `SseWriter.writeNamedEvent`, `writeEvent` and `writeEnd`.
- **Symptom:** `catch (IOException e)` around a call that only invokes `SseWriter` does not compile:
  `exception IOException is never thrown in body of corresponding try statement`. Kotlin has no checked
  exceptions, so without `@Throws(IOException::class)` the generated signatures declare nothing, and
  javac refuses a catch clause for a checked exception the body cannot throw.
- **The trap:** the methods still throw `IOException` at runtime, because they write to an
  `OutputStream`. Java sees a signature that says they cannot. A Java caller cannot narrowly handle
  write failures on these calls, and `throws IOException` on the caller is only legal when something
  else in the body declares it.
- **Workaround in the example:** the best-effort error-event write catches `Exception` instead of
  `IOException`, with a comment saying why. In `relay`, `catch (IOException | RuntimeException e)` is
  legal only because the `TokenStream.stream` call declares `IOException`.
- **Library implication:** `SseWriter.writeEvent`, `writeNamedEvent` and `writeEnd` should carry
  `@Throws(IOException::class)`, as `StreamingResponse.stream` and `ResponseBody.write` already do. It
  is additive for Java callers and source-compatible for Kotlin, though it does change the generated
  signature, so run `apiCheck` and treat it as a follow-up library change, not part of this task.
  This is evidence for Req 2: consuming the library from plain Java exposes it.
- **Tests:** `BedrockStreamHandlerTest` covers the write-failure path (an `IOException` from the live
  stream propagates, the best-effort error event is swallowed, one status stays on the wire).

## Task 13.2: cold-start cost of the async client (measured, read-only CloudWatch Logs)

- **Method:** `aws logs filter-log-events --filter-pattern '"REPORT"'` on `/aws/lambda/bedrock-streaming-endpoint-java`
  and `/aws/lambda/bedrock-streaming-endpoint` (eu-west-1; physical ids resolved with
  `cloudformation describe-stack-resources`). Only `INIT_REPORT`, `RESTORE_REPORT` and `REPORT` lines
  were read. No Bedrock call and no AWS change was made. Both functions: java25, arm64, 1024 MB, SnapStart
  on published versions.
- **SnapStart note:** with SnapStart, `Init Duration` is the init phase run when the version is
  published and the snapshot is taken (`INIT_REPORT`), not a per-request cost. What a cold request pays
  is `Restore Duration`. Both are reported.
- **Window: the CD run's invocations** (same publish, minutes apart, both stacks):

  | | Java (`...-java`) | Kotlin (`bedrock-streaming-endpoint`) |
  |---|---|---|
  | Init Duration (snapshot creation) | n=6: mean 4304 ms, range 4154 to 4432 ms | n=6: mean 1640 ms, range 1529 to 1768 ms |
  | Restore Duration (cold request) | n=1: 662.71 ms | n=1: 660.06 ms |
  | Max Memory Used on that request | 170 MB | 141 MB |
  | Deployed package (`CodeSize`) | 13,980,145 bytes | 12,114,140 bytes |

- **All history for context:** Java log group has only that one publish and one restore. The Kotlin log
  group has 15 restores over earlier deploys: mean 572 ms, range 283 to 791 ms. Kotlin Init Duration
  differs by code version (about 0.4 to 0.6 s on older versions, about 1.5 to 2.3 s on later ones), so
  only the CD-window row above is a like-for-like comparison.
- **Reading:** the Java function's snapshot-time init is about 2.6x the Kotlin one (about 2.7 s more).
  The one Java restore (663 ms) sits inside the Kotlin range (283 to 791 ms), so there is no evidence of
  a restore-time cost from the async client; n=1 for Java cannot show more than that. Package is about
  1.9 MB (15%) larger. Request `Duration` (about 10.8 s vs 10.9 s) is dominated by Bedrock generation
  and was not used for comparison.
- **Caveat:** the two functions differ in more than the HTTP client (Kotlin AWS SDK vs AWS SDK for Java
  v2, Netty NIO async client, `DeltaBridge`, the `-javaagent`, different priming code). These numbers
  describe the two examples, not Netty alone. Netty's own cost was not isolated.
- **Earlier run 44801738573:** its log carried no `REPORT` lines; the `ls -h` sizes (14M vs 12M) agree
  with the exact `CodeSize` above.

## Java Bedrock example: OIDC roles verification (task 12.4, Req 13.3, 13.4)

- **Evidence (read-only, `gh run view`):** CD - Deploy On Demand run `44801738573`
  (head `7b10be5`) concluded `success`. All 10 jobs passed, including `bedrock-streaming-endpoint-java`
  (deploy + `scripts/bedrock-streaming-test.sh`). Deploy jobs authenticated via OIDC as session
  `StreamingExampleDeployment`, test jobs as `StreamingExampleTest`. No `AccessDenied` / `not authorized`
  lines were found for the Java leg. No AWS calls were made for this check.
- **Repo comparison (`deployment/aws/oidc/github-oidc-role.yaml`):**
  - Trust policy: `sub` patterns cover `main`, `feature/*`, `dependabot/*` and `environment:*` under the
    immutable `SubjectPrefix`. Nothing is stack-specific, so the new stack needs no trust change.
  - Execution role: `Lambda` is `function:*`, API Gateway `/*`, logs account-wide, IAM role actions on
    `*`. A new function named `bedrock-streaming-endpoint-java*` is already covered. No new service type
    was added by the Java template (Function, Api, two LogGroups, one IAM role with
    `bedrock:InvokeModelWithResponseStream`, same as the Kotlin stack).
- **Caveat 1, the run did not exercise the CloudFormation execution role.** Every deploy leg, Java
  included, printed `No changes to deploy. Stack is up to date - skipping.` So run 44801738573 proves OIDC
  trust, artifact-bucket access and changeset creation, but no resource was created or updated under
  `StreamingCloudFormationExecutionRole`. The stack was created by an earlier deploy, and the earlier
  matrix runs (44772687065 and older) contain no Java Bedrock leg, so that creation is not visible in CD
  history. The "stack deploys under the execution role" claim rests on the template's wildcard scoping
  and on the stack existing, not on a create observed here.
- **Caveat 2 (corrected), the test does not need `lambda:GetAlias` / `lambda:GetFunctionConfiguration`.**
  An earlier version of this entry inferred that the live role was wider than the template because the
  test passed. That inference was wrong. `report_agent_config` in `scripts/bedrock-streaming-test.sh`
  runs both reads with `2>/dev/null || true` and returns early when the alias lookup is empty, so a
  denied call is swallowed. The CI log of run 44801738573 confirms it: neither Bedrock leg printed the
  `alias 'live' is version ... with the streaming agent configured` line, so the diagnostic was skipped
  silently and the test passed without it. The template's test role (`StreamingDeploymentPolicy`) grants
  `cloudformation:DescribeStacks` and `apigateway:GET` on `/apikeys/*`, which is everything the
  assertions need. Nothing here shows the live role differs from the template.
- **Conclusion (one position):** no OIDC role change is needed for the Java Bedrock stack. The template
  stays as it is for the test to pass. Optional follow-up, only if you want the agent-configuration
  diagnostic to run in CI: add `lambda:GetAlias` and `lambda:GetFunctionConfiguration` to the test role,
  scoped to the Bedrock functions. Separately, caveat 1 stands: this run did not exercise
  `StreamingCloudFormationExecutionRole`, so the first create in a fresh account or a rebuilt stack is the
  real test of that role. Requirement 13.4's "extended" wording can be softened to "verified to need no
  change", with caveat 1 noted.
## MEASURED: the Kotlin pre-first-delta 502 path kills the execution environment (`Runtime.ExitError`)

- **What this is:** task 13.3 of `java-bedrock-sse-streaming-endpoint`. It settles item 2 of
  "Review pass on the article and READMEs" (above), which was recorded as a strong inference. It is now
  a **measurement** against the live Kotlin stack `bedrock-streaming-endpoint` (eu-west-1), 2026-11-21,
  alias `live` = version 20 throughout. Run with the user's explicit approval to break that stack.

- **How the failure was injected, and a correction to the plan.** `NovaLiteProfileId` is *not* passed to
  the function. The handler uses the hard-coded constant `NOVA_LITE_PROFILE_ID`, and the parameter only
  appears in the IAM policy `Resource` (`...:inference-profile/${NovaLiteProfileId}`). So pointing it at
  a nonexistent profile (`eu.amazon.nova-lite-v1-nonexistent:0`) did **not** publish a new version and
  did **not** change the function configuration: `live` stayed at version 20, same
  `JAVA_TOOL_OPTIONS`, SnapStart `PublishedVersions`/`On`. The change lives only in the shared execution
  role (a role-policy change applies to every version at once). Requirement 7.7's concern (a config-only
  deploy leaves the alias on the old version) therefore applied in its mirror form: the alias was
  *correctly* unchanged, and the experiment still worked because IAM is not versioned. The effect was
  that Bedrock answered `AccessDeniedException` on `InvokeModelWithResponseStream`, which is a failure
  before the first delta, as intended. The role policy was read back to confirm the nonexistent
  profile was in place before invoking (about 50 s after the update, no IAM propagation delay seen).
  A real "bad profile id" would instead be a `ValidationException` from Bedrock; the handler treats both
  the same way (any exception before the first delta), so the code path is the same.

- **What the client received (2 invocations):** HTTP **200**, `text/event-stream`, **0 body bytes**,
  total 0.16 s and 1.9 s. No 502, no error event. The 200 is committed before the model is called, so the
  502 the handler tries to write is never seen.

- **Whether the invocation reports an error: yes, and it is the expensive kind.** Both invocations
  ended `Status: error  Error Type: Runtime.ExitError` in the platform `REPORT` line. They billed
  **60.4 s and 61.7 s** (the handler itself finished in about 66 ms; the rest is the platform waiting
  on a runtime that had died). The CloudWatch `Errors` metric counted 2. Sequence in the log of each:
  the handler logs `Bedrock stream failed before the first delta; responding 502`, writes the 502 to the
  buffered output, returns normally, and AWS's loop then tries to post a second response:
  `Failed to post handler success response. Http response code: 403 ... State transition from
  RuntimeResponseSentState to InvocationResponse failed ... InvalidStateTransition`. The loop then
  calls `reportInvocationError`, which also gets a 403 and throws
  `LambdaRuntimeClientException`, uncaught on the `main` thread, so the JVM exits.

- **Whether the next invocation reuses the environment: no.** The failing invocation ran in the warm
  environment that had served the baseline request seconds earlier (same log stream, `904b38c6...`).
  The second failing request landed in a **new** log stream (`b73ee553...`) with `RESTORE_START` and a
  402 ms SnapStart restore. So each failure costs the environment, and the next request pays a restore.
  A third environment was not needed: the post-restore request in `b73ee553...` also died the same way.

- **Cost of the failure to a caller:** an empty 200 (no signal that anything went wrong) plus a
  roughly 60 s billed invocation and a lost warm environment per failure. A burst of model failures
  (for example throttling before the first delta) would therefore thrash environments.

- **Restoration.** Parameter set back to `eu.amazon.nova-lite-v1:0` by a second `update-stack` with
  the previous template (stack `UPDATE_COMPLETE`), role policy read back as the original profile ARN,
  alias `live` still version 20, a normal request (`maxTokens=20`) streamed 160 bytes of SSE ending in
  `event: end`/`[DONE]`. The Java stack and every other stack were not touched. No secrets were printed:
  the API key was read into a shell variable only.

- **Implication (task 13.4's decision rule):** the failure is **not** harmless, so the rule says keep
  the branch, mark it unreachable with a comment citing this entry, and record a follow-up to make the
  agent guard `reportInvocationError`. Note the measured trigger is the pre-first-delta path
  (`markAnswered()` never called), exactly as inferred. The post-first-delta rethrow path was **not**
  measured here.

- **Gotchas worth keeping:** (1) `REPORT` and `Runtime.ExitError` lines appear in the log group a few
  seconds after the invocation, so a first filter right after the call finds nothing; (2) filtering the
  `REPORT` pattern needs the quoted `"REPORT RequestId"` form to match; (3) experiment cleanup left no
  stack in a failing configuration.

### Decision applied (task 13.4) and follow-up

- **Decision:** because the failure kills the execution environment (see the measurement above), the
  second branch of the rule applies. The pre-first-delta 502 branch in the Kotlin `BedrockStreamHandler`
  is **kept**, and its KDoc and the branch comment now mark it unreachable in effect and cite this
  entry. No behaviour, test or API change; nothing was redeployed.
- **Java module invariant (Req 5.6) holds:** `streaming-bedrock-example-java/src/main` has no 502
  branch, and `BedrockStreamHandlerTest` asserts no `"statusCode":502` on the wire.
- **Follow-up (open, out of this spec's scope):** make the agent guard `reportInvocationError` (and
  the success post) so a response already sent for an invocation does not trigger a second post, a 403
  and an uncaught `LambdaRuntimeClientException`. Alternatives: defer the prelude until the first body
  write, mark the invocation answered on the failure path, or drop the dead branch. Needs its own spec.

## Deploy workflow hardcoded `LogRetentionDays=7`, below the template's `MinValue: 14` (task 12.1)

- **Symptom:** the deploy of a stack whose template declares `LogRetentionDays` with `MinValue: 14`
  (`sam-bedrock`, `sam-bedrock-java`) fails at CloudFormation parameter validation if the workflow passes
  `7`. `workflow-deploy-aws.yml` had `LogRetentionDays=7` baked into `--parameter-overrides` (commit
  `8d36bc1`, "fix pipeline with retention days"), a value that fit only the older templates. The new Java
  stack would have inherited it.
- **Resolution / status:** **Fixed in task 12.1.** The workflow now takes a `log-retention-days` input and
  passes `LogRetentionDays=$LOG_RETENTION_DAYS`. The Bedrock matrix entries in `cd-deploy-on-demand.yml` and
  `ci-feature-build.yml` pass `'14'` with a comment naming the `MinValue`. **Residual trap:** the
  workflow still falls back to `7` when a caller passes an empty value, so any new caller of a template with
  a higher minimum must pass the input explicitly.

## `@JvmOverloads`: no surprise in task 1.1

- **Result:** adding the two-argument `ResponseMetadata` constructor needed no workaround. `@JvmOverloads`
  produced the overload Java needs, the existing Kotlin call sites compiled unchanged, and `apiDump`
  showed additions only (see the `apiCheck` step in task 1.5). Recorded so a later reader does not look
  for a gotcha that was never there. The Java-side friction that did appear is the missing `@Throws` on
  `SseWriter` (task 8.2, above).

## Demo video gate: only a verified recording may become an MP4 (task 14.3)

- **Problem:** `CAST_IN` re-rendering turns any cast into a publishable MP4, including one recorded against
  a non-streaming or unverified endpoint, which would show a demo that proves nothing.
- **Resolution / status:** **Fixed** in `scripts/record-streaming-demo.sh`. Record mode stamps
  `"streaming_verified": true` into the cast header only after the alias check and the secret scan pass.
  A `CAST_IN` render with `OUT_MP4` set refuses a cast without the marker (`cast_is_verified` reads the
  header line). `OUT_MP4=""` still allows a GIF-only render. A cast recorded before the marker exists
  must be recorded again; there is deliberately no override.
- **Related ffmpeg pad failure (reproduced, not fixed here):** the MP4 filter
  `scale=-2:H,pad=ceil(ih*16/9/2)*2:ih:...` fails with `Padded dimensions cannot be smaller than input
  dimensions` when the rendered GIF is wider than 16:9 (checked with a synthetic 2400x400 input). `pad`
  can only add border, never crop. A terminal that renders wider than 16:9 needs a narrower terminal
  or a `scale` that fits inside the 16:9 box first. I found no git evidence of which cast triggered it
  originally, so treat this as a known limit of the script rather than a closed bug.

## Dependabot alerts on build tooling: FreeMarker and Bouncy Castle (nothing ships in the Lambda zip)

- **Symptom:** open advisories on `org.freemarker:freemarker` (path traversal in template loading, pulled
  in by Kover) and on `org.bouncycastle` 1.84 (pulled in by the Kotlin Gradle plugin). Both live on
  the build-tooling classpath only.
- **Resolution / status:** **Fixed** in the root `build.gradle.kts`:
  - a `buildscript { constraints { classpath("org.freemarker:freemarker:2.3.35") } }` block lifts
    FreeMarker without changing the Kover version;
  - a `subprojects` block rewrites `org.bouncycastle` to `1.85` inside `kotlinBouncyCastleConfiguration`
    only (publishing validation, never a compile or runtime classpath).
- **Why a literal:** a `buildscript` block runs before the version catalog exists, so it cannot use
  `libs.`. The FreeMarker literal is item 6 of the Requirement 9.8 allow-list above, and
  `check_catalog.sh` has a matching `freemarker-constraint` entry. The Bouncy Castle override uses
  `useVersion` in a resolution strategy and is not a catalog-style literal in a dependency block, so it is not on
  the allow-list. Remove both once Kover and the Kotlin plugin ship fixed releases.
