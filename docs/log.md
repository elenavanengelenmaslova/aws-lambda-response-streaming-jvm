# Development Log

A running log of hiccups, bugs, gotchas, and fixes encountered while building the
S3 file-streaming endpoint. Each entry feeds the final guide in `docs/article.md`.

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
- **Resolution / status:** **RESOLVED — the allow-list is exactly these five, and is
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
  4. **All three modules** — `junit-platform-launcher`, a **versionless** catalog entry.
     Its version is constrained by `junit-jupiter`; pinning it separately invites a split.
  5. **The Java example** — `aws-sdk-java-s3`, also **versionless**. The version comes from
     `platform(libs.aws.sdk.java.bom)`.
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