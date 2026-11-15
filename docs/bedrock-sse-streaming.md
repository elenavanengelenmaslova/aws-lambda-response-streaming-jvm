# Streaming Amazon Bedrock tokens from a Kotlin Lambda as Server-Sent Events

### Progressive token delivery through API Gateway REST, without a custom runtime or Lambda layer

Small-write token relays do not stream progressively on AWS Lambda by default. A large-object
relay — the subject of the [previous article](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-s3-example/README.md),
where we streamed an S3 object through a Lambda behind API Gateway REST — writes roughly a
megabyte per chunk, and progressive delivery follows for free. An LLM token relay has the
opposite shape: Amazon Bedrock hands back a few bytes at a time, and those small writes are held
in the Lambda response-streaming runtime's internal buffer until it fills or the invocation ends.
The result arrives as a single terminal burst rather than a progressive trickle.

This is a follow-on to that previous piece [1]. We reuse its shared setup — SnapStart with CRaC
priming, the SAM `RESPONSE_STREAM` wiring, arm64, and tiered compilation — unchanged, so we do not
repeat it here. What is new is getting token-by-token output to arrive token-by-token, and it
needs **no custom runtime and no Lambda layer**: the function runs on the managed `java25` runtime.
The running example is the `streaming-bedrock-example` module, built on this repo's `streaming-core`
and `streaming-sse` libraries, and every number below was measured against a deployed endpoint.

## Demo and architecture

The deployed endpoint, called with `curl` and with the SSE padding stripped client-side so only
the tokens remain. Words appear a few at a time while the model is still generating, rather than
all at once at the end.

![Animated terminal demo: curl streaming Amazon Bedrock Nova Lite tokens from the Kotlin Lambda as Server-Sent Events, with text appearing progressively a few tokens at a time rather than in a single burst.](./bedrock-streaming-demo.gif)

The architecture is the same skeleton as the S3 example, with Amazon Bedrock in place of the
generic data source.

![Architecture diagram for the Bedrock SSE streaming example: a client calls an API Gateway REST endpoint configured for RESPONSE_STREAM, which invokes a Kotlin Lambda RequestStreamHandler; the Lambda calls Amazon Bedrock InvokeModelWithResponseStream and relays each delta back to the client as padded Server-Sent Events.](./SolutionDesign-Bedrock.png)

*The diagram traces the request flow end to end: client → API Gateway REST
(`RESPONSE_STREAM`) → Lambda `RequestStreamHandler` → Bedrock
`InvokeModelWithResponseStream` → padded SSE relay → client. Each hop and the component behind it
is covered in the next section.*

## The problem

In the S3 example each write was roughly a megabyte and progressive delivery came for free: we
wrote a chunk, flushed, and the client saw it. A token relay is the opposite shape. Bedrock returns
a few bytes at a time, so each write is tiny, and the AWS Lambda response-streaming runtime holds
those small writes in an internal buffer. It forwards them to the client only once that buffer
fills — around 100 KB in what we observed — or when the invocation ends.

There is no application-level `flush()` that overrides this. Calling `flush()` after every token
does not force the bytes out; the stream still arrives as one terminal burst at the end rather than
a progressive trickle. The flush happens at the language layer, but the buffer that gates delivery
sits below it, inside the Lambda streaming runtime.

That placement identifies where the behaviour originates. It is not a JVM quirk, not something in
these libraries, and not API Gateway adding a buffer to the path. It is the Lambda streaming runtime
itself, and it reproduces across languages and even with Lambda Function URLs — removing API Gateway
from the path does not remove it. The fix is therefore not on the JVM side; it is in how bytes are
written, which is the subject of the rest of this article.

None of this requires special runtime machinery. There are **no Lambda layers** and **no custom
runtime** here — the function runs on the managed `java25` runtime. The workaround is entirely in
how bytes are written, not in how the function is packaged or provisioned.

## Architecture walkthrough

A single request runs as follows. The client sends `POST /stream` with a small JSON body —
`{"prompt":"...","maxTokens":N}` — and the hop sequence is:

```
client  POST /stream  {"prompt":"...","maxTokens":N}
   │
   ▼
API Gateway REST  (RESPONSE_STREAM)
   │
   ▼
Lambda  RequestStreamHandler  (reads InputStream, writes OutputStream)
   │   ├─ PromptResolver     parse + validate + clamp the request
   │   ├─ ResponseWriter     write the metadata prelude (status + headers)
   │   ▼
   ├─ Bedrock  InvokeModelWithResponseStream   ← BedrockSource
   │   │
   │   ▼  token deltas
   └─ PaddedSseWriter / PacedSseEmitter   padded SSE relay
   │
   ▼
client   (drops the ':' padding, sees tokens arrive progressively)
```

Each hop maps to a named component.

**[`BedrockStreamingHandler`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-bedrock-example/src/main/kotlin/nl/vintik/lambda/bedrock/BedrockStreamingHandler.kt)**
is the entrypoint AWS invokes — the no-arg `RequestStreamHandler` named in the SAM template. It
gives the runtime a zero-arg constructor to build and hands off immediately to the orchestrator.
Keeping it thin is deliberate: the logic lives one level down, where it is testable without the
Lambda runtime.

**[`BedrockStreamHandler`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-bedrock-example/src/main/kotlin/nl/vintik/lambda/bedrock/BedrockStreamHandler.kt)**
is that orchestrator and the spine of the request. It runs three steps in order: resolve the
incoming request, write the response metadata, then relay the model's tokens out as padded SSE.
Everything else is a collaborator it calls into.

**[`PromptResolver`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-bedrock-example/src/main/kotlin/nl/vintik/lambda/bedrock/PromptResolver.kt)**
is the first step. It is a `RequestResolver` that takes the raw API Gateway proxy event, parses the
JSON body, validates it, and clamps the cost-and-size knobs (the prompt plus values such as
`maxTokens`) to safe bounds before anything reaches Bedrock. A malformed or oversized request is
rejected or trimmed here, so the paid model call downstream only sees a bounded request.

**[`BedrockSource`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-bedrock-example/src/main/kotlin/nl/vintik/lambda/bedrock/BedrockSource.kt)**
is the token producer. It implements `TokenStreamSource` — the streaming-source contract, a
token-stream analogue of the S3 example's `StreamSource` — and calls Bedrock Nova Lite via
`InvokeModelWithResponseStream` using the Kotlin AWS SDK (not the Java one), surfacing each text
delta as the model generates it. The handler pulls deltas from this source and knows nothing about
Bedrock directly.

**[`PaddedSseWriter`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-sse/src/main/kotlin/nl/vintik/lambda/streaming/sse/PaddedSseWriter.kt)**
carries the workaround and comes from the `streaming-sse` module rather than the example. It encodes
each token delta as a Server-Sent Event and appends tiered SSE-comment padding — the mechanism that
forces the Lambda runtime buffer open so small writes leave the function. The next section covers
how the padding is tiered; here it is the component between "tokens the model produced" and "bytes
on the wire".

**[`PacedSseEmitter`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-sse/src/main/kotlin/nl/vintik/lambda/streaming/sse/PacedSseEmitter.kt)**
is an optional wrapper around that writer, also from `streaming-sse`. For a deliberately paced,
dribbled UX — tokens spaced out over time rather than emitted as fast as Bedrock produces them — it
adds the inter-chunk delay. It enforces padding before delay: it refuses to be constructed with a
delay against a writer that carries no padding, because a delay with nothing to push past the buffer
has no effect. Pacing is a layer on top of the writer, not a separate path.

**[`ResponseWriter`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-core/src/main/kotlin/nl/vintik/lambda/streaming/ResponseWriter.kt)**
comes from the `streaming-core` library and owns the API Gateway streaming wire protocol. As covered
in the previous article [1], it writes the metadata prelude — the status code and headers — in the
exact shape the streaming response format expects, before the first SSE event. The status is
committed once the prelude is written, so the handler resolves and validates the request first, then
writes the prelude, then starts relaying. `PaddedSseWriter` handles the event framing; `ResponseWriter`
handles the head-before-stream sequence that precedes it.

**[`Priming`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-bedrock-example/src/main/kotlin/nl/vintik/lambda/bedrock/Priming.kt)**
sits off the request flow but is worth naming, because it is why the first real request is not slow.
It is a CRaC `beforeCheckpoint` warm-up hook that exercises the critical path — building the Bedrock
client, the handler, serialization — during SnapStart's snapshot phase, so the restored snapshot
comes up warm. It is part of the shared cold-start setup reused from the S3 example.

In sum: `BedrockStreamingHandler` receives the invocation and delegates to `BedrockStreamHandler`,
which uses `PromptResolver` to resolve the request, `ResponseWriter` to open the response,
`BedrockSource` to pull tokens from Bedrock, and `PaddedSseWriter` (optionally paced by
`PacedSseEmitter`) to relay each token out as a padded SSE event — with `Priming` having warmed the
path ahead of time.

## The padding workaround

The buffer is real, it sits below the language layer, and `flush()` does not move it. The fix is
direct: if the runtime forwards bytes only once its buffer fills, make the first writes large enough
to fill it. The SSE format provides a place to put the padding.

In Server-Sent Events, any line beginning with a colon (`:`) is a comment, and compliant clients
ignore it. It never reaches the `onmessage` handler, and a shell filter such as `sed -n 's/^data: //p'`
drops it as well. Appending a comment line to every event is therefore invisible to the consumer.
Each event on the wire looks like this:

```
data: <token text>\n\n
: <padding spaces>\n\n      <- ignored by the client; only there to trip the buffer
```

The top line is the real token. The bottom line is ballast — a comment of spaces whose only purpose
is to help cross the ~100 KB buffer. The client discards it; it costs only wire bytes.

### Tiered padding

The padding is not uniform across events. The runtime behaves in two phases: the first writes must
be large enough to open the initial buffer, but once the stream is flowing it takes far less to keep
it moving. `PaddedSseWriter` therefore pads in two tiers:

- **~100 KB of head padding on the first 3 events** (`DEFAULT_HEAD_PAD_BYTES` = `102400`,
  `DEFAULT_HEAD_PAD_EVENTS` = `3`) — the heavy hit that breaks the initial buffer so the first
  tokens appear quickly.
- **~10 KB of tail padding on every event afterwards** (`DEFAULT_TAIL_PAD_BYTES` = `10240`) —
  lighter, but required. The runtime re-buffers after the first few events, so without ongoing tail
  padding the stream stalls again. The tail keeps it flowing.

That asymmetry — a large upfront push, then a steady trickle — is the shape that produced progressive
delivery in practice, and it is the default tiers.

### The trade-off: bandwidth

Padding is not free. It inflates the on-the-wire response size by roughly **100× to 1000×** relative
to the original payload. In our measurements a ~15 KB answer became several MB on the wire. The cost
is bandwidth spent to buy responsiveness.

For a chat-style UX, where time to first token is the experience, that is usually worth it; a few
megabytes of invisible comments are acceptable if the words start appearing immediately. For bulk
output, where only the bytes matter and the first one's timing does not, it is not worth it — skip
the padding entirely. The padding bytes never reach application code: the client's SSE parser (or the
`sed` filter) strips every comment line, so the consumer sees byte-for-byte the payload that was sent.

### Prior work

This buffering behaviour was documented independently from a Python/FastAPI stack running behind the
Lambda Web Adapter — a different language and framework from this Kotlin/JVM one — in the AWS Builder
Center article "The Curious Case of SSE Streaming Agent Responses on AWS Lambda" [2]. That the same
buffer appears from an entirely different stack is what confirms it sits in the Lambda streaming
runtime itself rather than in any one application stack. This article is the JVM equivalent of the
same finding.

## Using the libraries

Both pieces — the wire protocol and the padding — ship as libraries to depend on rather than code to
copy.

### `streaming-core`: the wire protocol via `ResponseWriter`

The lower layer is `streaming-core`, published to Maven Central as `nl.vintik:aws-lambda-streaming-core`:

```kotlin
implementation("nl.vintik:aws-lambda-streaming-core:2.4.1")
```

The group is `nl.vintik` and the artifact is `aws-lambda-streaming-core` — the artifact id is the
longer published name, not the module directory name (`streaming-core`). In code, import the one type
needed:

```kotlin
import nl.vintik.lambda.streaming.ResponseWriter
```

`ResponseWriter` owns the API Gateway streaming wire format. It writes the **metadata prelude** — the
status code and headers — before the first SSE event. The prelude lands first and exactly once; the
status is committed the moment it is written, so the request is resolved and validated before the
call, then the prelude is written, then tokens are relayed.

The headers shape matters. They are a plain `Map<String, String>` — a single value per header name,
not a `Map<String, List<String>>`. The multi-value shape is valid JSON but makes API Gateway return a
502 (covered in the server-side pitfalls). The prelude is:

```kotlin
private val responseWriter = ResponseWriter()

responseWriter.writeMetadata(
    output,
    ResponseMetadata(
        statusCode = 200,
        headers = mapOf(
            "Content-Type" to "text/event-stream",
            "Cache-Control" to "no-cache",
        ),
    ),
)
```

### `streaming-sse`: `PaddedSseWriter` and `PacedSseEmitter`

The padding workaround lives in `streaming-sse`, under the package `nl.vintik.lambda.streaming.sse`:

```kotlin
import nl.vintik.lambda.streaming.sse.PaddedSseWriter
import nl.vintik.lambda.streaming.sse.PacedSseEmitter
```

`streaming-core` does not need to be declared separately with this module. `streaming-sse` declares
`streaming-core` as an `api` dependency, so depending on `streaming-sse` pulls `streaming-core` in
transitively — `ResponseWriter` arrives alongside the SSE types.

`PaddedSseWriter` is the encoder: it frames each token as an SSE `data:` event and appends the tiered
comment padding from the previous section. The no-arg constructor applies the default tiers (~100 KB
head on the first 3 events, ~10 KB tail after), or the tiers can be set explicitly:

```kotlin
// Default tiers — the DEFAULT_* constants from the previous section:
val writer = PaddedSseWriter()

// …or explicit tiers if you want to tune them:
val tuned = PaddedSseWriter(headPadBytes = 102_400, tailPadBytes = 10_240, headPadEvents = 3)

writer.writeEvent(output, "some token")  // one data: event + its tier's padding; flushes; returns bytes written
writer.writeEnd(output)                  // terminal bytes: event: end / data: [DONE]; flushes
```

`writeEvent(output, text)` writes one event, appends the correct padding tier (head while the event
index is below `headPadEvents`, tail afterwards), flushes, and returns the UTF-8 byte count it wrote.
`writeEnd(output)` emits the terminal `event: end` / `data: [DONE]` sentinel and flushes. That is the
full surface for a straight, as-fast-as-possible relay.

`PacedSseEmitter` wraps the writer for a deliberately paced, dribbled UX — tokens spaced out over
time rather than emitted the instant Bedrock produces them:

```kotlin
val emitter = PacedSseEmitter(writer, delayMillis = 100)  // default delayMillis = NO_DELAY

emitter.emit(output, "some token")  // writes + flushes the padded event, THEN pauses before the next one
emitter.end(output)                 // delegates to writer.writeEnd; never adds a trailing delay
```

It delegates encoding to `PaddedSseWriter` and only adds the inter-chunk delay. The event is always
written and flushed before any pause, so each paced chunk crosses the buffer first and the emitter
sleeps only afterwards. It enforces padding before delay at construction: a non-`NO_DELAY` delay
against a writer that carries no padding throws `IllegalArgumentException`, because a delay with
nothing to push past the buffer has no effect (covered in the pitfalls). Without pacing, drive the
`PaddedSseWriter` directly.

### How the Bedrock example wires them together

The `streaming-bedrock-example` module assembles these pieces in `BedrockStreamHandler`. The sequence
is the one the types imply:

1. **Resolve** the incoming request with `PromptResolver` — parse, validate, clamp the prompt and
   `maxTokens` to safe bounds.
2. **Open the response** by writing the metadata prelude with `ResponseWriter` (status +
   `text/event-stream` headers), committing the status before any token goes out.
3. **Pull token deltas** from `BedrockSource`, which drives Bedrock Nova Lite's
   `InvokeModelWithResponseStream` and surfaces each text delta as the model generates it.
4. **Relay each delta** through `PaddedSseWriter.writeEvent`, so every token leaves the function as a
   padded SSE event that crosses the runtime buffer — optionally paced through `PacedSseEmitter` for
   the dribbled cadence. When the model is done, `writeEnd` closes out the stream.

The two libraries split cleanly: `streaming-core`'s `ResponseWriter` handles everything up to and
including the prelude, and `streaming-sse`'s `PaddedSseWriter` / `PacedSseEmitter` handle every event
after it. The example module supplies the Bedrock-specific glue (`PromptResolver`, `BedrockSource`)
and lets the libraries do the streaming.

## Build, test, validate, deploy

A few prerequisites. The Gradle wrapper handles the JDK 25 toolchain (no manual Java install), but
the **AWS CLI** and the **AWS SAM CLI** must be configured against an account, and **Amazon Bedrock
Nova Lite model access must be enabled in your region** — the example runs in `eu-west-1`. Without
that model access the deploy succeeds but the first real request fails when it invokes Bedrock.

### Build

The build produces the Lambda fat jar — `bedrock-streaming-endpoint.jar`, assembled via
`:streaming-bedrock-example:shadowJar` — through the build script in the SAM directory:

```bash
cd deployment/aws/sam-bedrock
./build.sh
```

### Test

Run the module's unit and property tests — no AWS and no Docker needed:

```bash
./gradlew :streaming-bedrock-example:test
```

For the coverage report and gate:

```bash
./gradlew :streaming-bedrock-example:koverHtmlReport :streaming-bedrock-example:koverVerify
```

### Validate the SAM template

Before deploying, check the template is well formed:

```bash
sam validate --template-file deployment/aws/sam-bedrock/template.yaml
```

### Deploy

Deploying is the build followed by `deploy.sh`, which runs `sam deploy` against the config in
`samconfig.toml`:

```bash
cd deployment/aws/sam-bedrock
./build.sh
./deploy.sh
```

Once the stack is up, calling it needs two values — the endpoint URL and an API key — both from the
CloudFormation stack. The URL is the `StreamingEndpointUrl` output; the API key is referenced
indirectly, with the `ApiKeyId` output giving the key's id and `aws apigateway get-api-key
--include-value` resolving that id to the secret value:

```bash
ENDPOINT_URL=$(aws cloudformation describe-stacks --stack-name bedrock-streaming-endpoint \
  --query "Stacks[0].Outputs[?OutputKey=='StreamingEndpointUrl'].OutputValue" --output text)
API_KEY=$(aws apigateway get-api-key --api-key \
  "$(aws cloudformation describe-stacks --stack-name bedrock-streaming-endpoint \
     --query "Stacks[0].Outputs[?OutputKey=='ApiKeyId'].OutputValue" --output text)" \
  --include-value --query 'value' --output text)
```

### Stream tokens progressively

Call the `/stream` endpoint with `curl -N` and drop the padding client-side with a `sed`/`awk`
pipeline:

```bash
curl -N -H "x-api-key: $API_KEY" -H "Content-Type: application/json" \
  "${ENDPOINT_URL}stream" -d '{"prompt":"Explain why response streaming matters for serverless.","maxTokens":1000}' \
  | sed -u -n 's/^data: //p' | awk '{printf "%s",$0; fflush()}'
```

`sed -u -n 's/^data: //p'` keeps only the `data:` token lines and discards the `:` padding comments;
`awk '{printf "%s",$0; fflush()}'` joins the tokens into flowing prose and flushes as it goes so the
terminal does not buffer them. Words appear progressively, a few tokens at a time, while the model is
still generating, rather than all at once at the end. (The `curl -N` flag and the `sed`/`awk` filter
are both required for this; the client-side pitfalls explain why.)

## Verification and measured results

The repo ships a reproducible check:
[`scripts/bedrock-streaming-test.sh`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/scripts/bedrock-streaming-test.sh).
It calls the deployed endpoint and measures whether tokens arrive progressively or land in one lump
at the end.

It measures two signals that distinguish real streaming from a buffered burst. First,
**time to first byte (TTFB)** in milliseconds — how long the client waits before the first byte
arrives. Second, **the distribution of received bytes across fixed 500 ms time buckets** — it stamps
each arrival against a monotonic clock and counts how many distinct 500 ms windows the bytes land in.
One bucket means everything arrived in a single wave (buffered); many buckets spread over the
generation mean the bytes trickled out as the model produced them (progressive). It also checks for
the terminal `[DONE]` sentinel, so a passing run has proven a complete stream, not a truncated one.

Run it with:

```bash
BEDROCK_TEST_ENABLED=true AWS_REGION=eu-west-1 ./scripts/bedrock-streaming-test.sh
```

The `BEDROCK_TEST_ENABLED=true` prefix is required by design. The script invokes **paid Bedrock** —
every run costs money for the model call — so it is **opt-in and disabled by default**. Without
`BEDROCK_TEST_ENABLED=true` the script runs but exits cleanly without calling the model, which is the
intended behaviour on an ordinary feature push. Spending money is an explicit opt-in.

### Results

The result from running it against the deployed endpoint in `eu-west-1`, comparing the relay with
padding switched off against the tiered padding scheme:

| Configuration | TTFB | Arrival | Verdict |
|---|---|---|---|
| No padding | ≈ total | 1 time bucket | single terminal burst |
| Tiered 100 KB × 3 + 10 KB | ~0 ms | 9 distinct 500 ms buckets over ~4.4 s | **progressive** |

With **no padding**, TTFB is essentially the total response time: the client waits the entire
generation and everything arrives at once, in a single time bucket. That is the single terminal
burst, with no intermediate progressive arrival — the buffer held every write until the stream ended.
With the **tiered 100 KB × 3 + 10 KB** padding, the shape inverts: TTFB drops to near 0 ms and the
bytes spread across 9 distinct 500 ms buckets over roughly 4.4 seconds of total delivery. The first
tokens appear almost immediately and continue in waves while the model is still generating — that is
progressive delivery.

These numbers came out of `scripts/bedrock-streaming-test.sh` run against a real deployed endpoint,
and are reproducible with the command above.

## Server-side pitfalls

The findings below are the ways this went wrong before the solution settled. Most of them fail
silently, with clean logs and a normally reporting function, so there is nothing to grep for. They
are stated symptom-first, because the symptom is what is in front of you when you hit one.

**1. A naive `flush()` does nothing — the buffer is below the language layer.**
*Symptom:* tokens are relayed from Bedrock, `flush()` is called after every write, and the stream
still arrives as one terminal burst at the end. No error, no warning; it is simply not progressive.
*Fix:* `flush()` cannot help here. The flush happens at the language layer, but the buffer that gates
delivery lives below it, inside the Lambda streaming runtime, which forwards only once it is filled
(~100 KB observed) or the invocation ends. The only lever is filling it, which is the padding
workaround. This reframes every fix that follows.

**2. An inter-chunk delay alone does nothing either.**
*Symptom:* the next move after the flush fails is to space the writes out with a small `sleep`
between tokens. It does not help. The delayed small writes still never cross the ~100 KB buffer, so
they coalesce and burst at the end exactly as they did with no delay. This was the earlier MockNest
"dribble" attempt.
*Fix:* a delay is effective only combined with padding. Padding gets the stream flowing across the
buffer; the delay shapes the cadence once it is already flowing. On its own it does nothing.

**3. `PacedSseEmitter` prevents pitfall #2 by construction.**
This is enforced in the library rather than left as a bug. Because "delay alone does nothing" is easy
to fall for, `PacedSseEmitter` refuses to be constructed with a non-`NO_DELAY` delay against a writer
that carries no padding — it throws `IllegalArgumentException` at construction time
(`!writer.hasPadding()`).
*Why:* a delay with no padding is a configuration that looks deliberate and has no effect — a silent
no-op. Padding fills the runtime buffer, forcing the earlier chunks out where the client can observe
them; without it the pacing reshuffles writes that never leave the buffer until the end. A hard
failure surfaces the mistake at startup, not after a deploy and a non-progressive stream.

**4. The `OutputStream` must be flushed *and* closed, or warm invocations truncate.**
*Symptom:* everything works on the first invocation after a SnapStart restore. Every warm invocation
afterward delivers a partial body and drops the connection — `curl` reports `curl: (18) transfer
closed with 4112195 bytes remaining to read`. CloudWatch logs are clean, the `REPORT` line is normal
(no timeout, ~1 s duration), and there is no exception. The bug appears only under timing pressure on
fast warm invocations; the slower cold path leaves enough time for the runtime's own teardown to flush
everything, which is why it hides.
*Fix:* two things together. Wrap the handler body in `output.use { }` so `close()` runs on every exit
path — normal return, error response, uncaught exception — because Lambda streaming uses the stream
close as the "response complete" signal. And add an explicit `output.flush()` before that close, so
the runtime drains its buffer before it gets the close signal; without the explicit flush a race in
the streaming channel occasionally left the last chunk undelivered even after `close()`. Flush, then
close, on every path.

**5. Metadata headers must be `Map<String, String>`, not `Map<String, List<String>>`.**
*Symptom:* the Lambda runs correctly — completes in time, no CloudWatch errors — and API Gateway
returns `HTTP 502 {"message": "Internal server error"}`. Nothing in your logs is wrong, because
nothing in your code failed; the break is in how API Gateway parses the prelude. It surfaces only
against a real deployed API Gateway — a local emulator does not enforce the prelude format, so
integration tests stay green while the deployed endpoint 502s.
*Fix:* model the prelude's `headers` field as `Map<String, String>` — one string per header name. The
`Map<String, List<String>>` form serializes to JSON arrays (`"Content-Type":["text/event-stream"]`),
which is valid JSON but not a shape the API Gateway streaming parser recognizes, so it cannot extract
the status code and headers and returns 502. Repeatable headers such as `Set-Cookie` do not go in a
JSON array inside `headers` either — the streaming format carries them in a separate `cookies` field.
This was the most consequential issue in porting the Node.js streaming helper to the JVM, because the
prelude format is documented only implicitly through that helper's behaviour.

**6. `STREAM` vs `RESPONSE_STREAM` — same idea, two names, two layers, not interchangeable.**
*Symptom:* the transfer mode is wired up, deployed, and the endpoint returns buffered — non-streaming
— with no error. The right concept was used with the wrong name for the layer, and the only feedback
is a full, slow deploy cycle ending in a buffered response.
*Fix:* keep the two layers straight. At the SAM `Api` event level the property is
`ResponseTransferMode: RESPONSE_STREAM` [4]. At the lower-level API Gateway *integration* the
equivalent value is `STREAM`, and that path additionally needs the integration URI to carry the
`/response-streaming-invocations` suffix. They are not interchangeable and apply at different layers;
the wrong one, or the right one at the wrong layer, yields a buffered endpoint. The convention here is
to use `RESPONSE_STREAM` at the SAM `Api` level and run `sam validate --template-file
deployment/aws/sam-bedrock/template.yaml` (exit code 0) before committing any template change. This
is the shared-setup piece from the S3 example, re-flagged because it can consume a whole deploy cycle
before it is noticed.

## Client-side pitfalls

Getting the server to stream progressively is half the work. Even with the padding working and the
bytes leaving the function in waves, the client can still buffer the response, choke on the protocol,
or drown in padding. These three occurred on the consuming side, and like the server-side ones they
tend to fail in ways that look like something else. They are stated symptom-first, with the
invocation that broke and the one that worked.

**1. `curl` over HTTP/2 fails with error 92 — force `--http1.1`.**
*Symptom:* in CI (GitHub Actions) and on any machine with a newer `curl` that defaults to HTTP/2, a
streaming request against the API Gateway endpoint fails with:

```
curl: (92) HTTP/2 stream 1 was not closed cleanly: INTERNAL_ERROR (err 2)
```

The body may have transferred completely, but `curl` treats the unclean stream close as fatal and
exits non-zero — so the test reports the endpoint as unreachable when it actually streamed. This is
the failing call:

```bash
# Fails on HTTP/2 (newer curl defaults to it) with curl: (92)
curl -N -H "x-api-key: $API_KEY" -H "Content-Type: application/json" \
  "${ENDPOINT_URL}stream" -d '{"prompt":"...","maxTokens":1000}'
```

*Fix:* force HTTP/1.1 on every `curl` call. API Gateway's streaming response uses chunked transfer
encoding; over HTTP/2 the frontend does not terminate the stream cleanly after the Lambda finishes
writing, and `curl` reads that as an INTERNAL_ERROR. HTTP/1.1 chunked transfer terminates cleanly with
a zero-length chunk:

```bash
# Add --http1.1 and it's clean regardless of curl's default protocol
curl --http1.1 -N -H "x-api-key: $API_KEY" -H "Content-Type: application/json" \
  "${ENDPOINT_URL}stream" -d '{"prompt":"...","maxTokens":1000}'
```

The AWS docs do not cover this. The API Gateway response-streaming troubleshooting page [5] mentions
`curl: (18) transfer closed with outstanding read data remaining` (a timeout symptom), but error 92 —
the HTTP/2 stream-close incompatibility — is not documented. `--http1.1` is the fix.

**2. Drop the SSE padding, or read megabytes of comments.**
*Symptom:* the stream carries all the `:` comment-line padding added server-side to trip the runtime
buffer. A proper SSE client ignores comment lines, but reading the raw stream from the shell shows the
padding drowning the actual tokens.
*Fix:* keep only the `data:` lines and reassemble them. A `sed`/`awk` pipeline recovers the original
payload byte-for-byte:

```bash
curl --http1.1 -N -H "x-api-key: $API_KEY" -H "Content-Type: application/json" \
  "${ENDPOINT_URL}stream" -d '{"prompt":"...","maxTokens":1000}' \
  | sed -u -n 's/^data: //p' | awk '{printf "%s",$0; fflush()}'
```

`sed -u -n 's/^data: //p'` keeps only the text after `data: ` and discards every `:` comment line —
the padding — so the on-the-wire inflation never reaches the consumer. The terminal event contributes
a single `[DONE]` sentinel line, the one marker at the end indicating the stream completed rather than
truncated. The padding exists only to fill the Lambda runtime buffer and force the early writes out; a
compliant SSE client (or this filter) discards all of it, which is why hiding the workaround in comment
lines works.

**3. `curl -N` (`--no-buffer`) is required, or `curl` buffers the response.**
*Symptom:* the server is streaming correctly, yet the whole response appears at once. The bytes are
arriving in waves, but `curl` is collecting them into its own output buffer and flushing only when
ready. The result looks like a buffered endpoint, masking the streaming:

```bash
# Without -N, curl buffers the response and it appears all at once
curl --http1.1 -H "x-api-key: $API_KEY" -H "Content-Type: application/json" \
  "${ENDPOINT_URL}stream" -d '{"prompt":"...","maxTokens":1000}'
```

*Fix:* add `-N` (long form `--no-buffer`), which tells `curl` not to buffer the output and to hand each
chunk straight through as it arrives:

```bash
# -N / --no-buffer disables curl's own output buffering
curl --http1.1 -N -H "x-api-key: $API_KEY" -H "Content-Type: application/json" \
  "${ENDPOINT_URL}stream" -d '{"prompt":"...","maxTokens":1000}'
```

The same discipline applies one hop further down the pipe: the `fflush()` in the `awk` stage is the
same fix at the `awk` layer — flush each token as it is written so `awk` does not re-buffer what `curl`
handed over unbuffered. Every stage in the pipe can buffer, and progressive delivery survives only if
none of them do.

## Conclusion

The S3 example streamed for free because each write was a megabyte; this one required real work because
Bedrock returns a few bytes at a time and the Lambda streaming runtime buffers small writes below the
language layer, where no `flush()` reaches. The fix is not runtime machinery — no Lambda layers, no
custom runtime, just the managed `java25` runtime — it is padding: SSE comment lines, ~100 KB on the
first few events and ~10 KB after, that fill the buffer and force the tokens out where the client can
see them. The cost is bandwidth, hidden in `:` comment lines the client discards, and the trade-off is
worth it for a chat-style UX and not for bulk output. The remaining care is the set of server- and
client-side pitfalls that fail silently with clean logs. Wrapping `ResponseWriter` and `PaddedSseWriter`
/ `PacedSseEmitter` around that approach produces the token-by-token delivery.

For the shared setup — SnapStart with CRaC priming, the SAM `RESPONSE_STREAM` wiring, arm64, tiered
compilation — see the [S3-streaming example](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-s3-example/README.md),
the other half of this series [1]. Both libraries — `streaming-core` and `streaming-sse` — are on
Maven Central. The links below point at the exact code this article describes.

## Source

All links are pinned to the `main` branch so they keep matching the code.

**Bedrock example** (`streaming-bedrock-example/src/main/kotlin/nl/vintik/lambda/bedrock/`):

- [`BedrockStreamingHandler.kt`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-bedrock-example/src/main/kotlin/nl/vintik/lambda/bedrock/BedrockStreamingHandler.kt) — the `RequestStreamHandler` entrypoint AWS invokes
- [`BedrockStreamHandler.kt`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-bedrock-example/src/main/kotlin/nl/vintik/lambda/bedrock/BedrockStreamHandler.kt) — the orchestrator: resolve → write prelude → relay tokens
- [`PromptResolver.kt`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-bedrock-example/src/main/kotlin/nl/vintik/lambda/bedrock/PromptResolver.kt) — parses, validates, and clamps the request
- [`PromptRequest.kt`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-bedrock-example/src/main/kotlin/nl/vintik/lambda/bedrock/PromptRequest.kt) — the resolved request shape (prompt + `maxTokens`)
- [`BedrockSource.kt`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-bedrock-example/src/main/kotlin/nl/vintik/lambda/bedrock/BedrockSource.kt) — drives Bedrock Nova Lite's `InvokeModelWithResponseStream`
- [`TokenStreamSource.kt`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-bedrock-example/src/main/kotlin/nl/vintik/lambda/bedrock/TokenStreamSource.kt) — the token-stream source contract
- [`Priming.kt`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-bedrock-example/src/main/kotlin/nl/vintik/lambda/bedrock/Priming.kt) — the CRaC `beforeCheckpoint` warm-up hook

**`streaming-sse` library** (`streaming-sse/src/main/kotlin/nl/vintik/lambda/streaming/sse/`):

- [`PaddedSseWriter.kt`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-sse/src/main/kotlin/nl/vintik/lambda/streaming/sse/PaddedSseWriter.kt) — the tiered-padding SSE encoder
- [`PacedSseEmitter.kt`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-sse/src/main/kotlin/nl/vintik/lambda/streaming/sse/PacedSseEmitter.kt) — the optional pacing wrapper that enforces padding-before-delay

**`streaming-core` library** (`streaming-core/src/main/kotlin/nl/vintik/lambda/streaming/`):

- [`ResponseWriter.kt`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-core/src/main/kotlin/nl/vintik/lambda/streaming/ResponseWriter.kt) — the API Gateway streaming wire protocol / metadata prelude

**Deploy and verify:**

- [`deployment/aws/sam-bedrock/template.yaml`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/deployment/aws/sam-bedrock/template.yaml) — the SAM template (Lambda + API Gateway `RESPONSE_STREAM`, SnapStart)
- [`scripts/bedrock-streaming-test.sh`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/scripts/bedrock-streaming-test.sh) — the opt-in progressive-arrival / TTFB check

**Further reading:**

- [`streaming-bedrock-example/README.md`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-bedrock-example/README.md) — the example module's own README
- [`streaming-sse/README.md`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-sse/README.md) — the `streaming-sse` library README

## References

1. E. van Engelen-Maslova, "AWS Lambda Response Streaming for Kotlin and Java," AWS Builder Center. <!-- TODO: paste the exact article URL --> https://builder.aws.com/content/
2. "The Curious Case of SSE Streaming Agent Responses on AWS Lambda," AWS Builder Center. https://builder.aws.com/content/39iqpehwjnn2TuNmGnHAnsMcHmP/the-curious-case-of-sse-streaming-agent-responses-on-aws-lambda
3. "Response streaming for Lambda functions," AWS Lambda Developer Guide. https://docs.aws.amazon.com/lambda/latest/dg/configuration-response-streaming.html
4. "Stream the integration response for your proxy integrations," Amazon API Gateway Developer Guide. https://docs.aws.amazon.com/apigateway/latest/developerguide/response-transfer-mode.html
5. "Configure a Lambda proxy integration with payload response streaming," Amazon API Gateway Developer Guide. https://docs.aws.amazon.com/apigateway/latest/developerguide/response-streaming-lambda-configure.html
