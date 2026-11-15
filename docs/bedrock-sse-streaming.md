# Streaming Bedrock tokens from a Kotlin Lambda, one SSE event at a time

This is the second stop in a little streaming series. In the [first piece](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-s3-example/README.md) I
streamed a large object out of S3 through an AWS Lambda behind API Gateway REST, and the
whole thing just worked — write ~1 MB chunks, flush, and the bytes trickle out
progressively with no tricks at all. This time I wanted to stream something that produces
only a handful of bytes at a time: Amazon Bedrock Nova Lite tokens, relayed to the client
as Server-Sent Events as the model generates them. That turned out to be a very different
problem, and most of this article is about why.

If you want the shared plumbing — SnapStart with CRaC priming, the SAM `RESPONSE_STREAM`
wiring, arm64, and tiered compilation — I'm not going to repeat it here. I covered all of
that in the [S3-streaming example](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-s3-example/README.md) and its guide, and the Bedrock example
reuses it wholesale. So assume that groundwork is in place. What you'll get in this piece
is the part that's genuinely new: getting token-by-token output to actually *arrive*
token-by-token instead of landing in your lap as one big burst at the end.

The running example is the `streaming-bedrock-example` module, built on this repo's own
`streaming-core` and `streaming-sse` libraries. Everything here is grounded in that code
and in what I measured against a deployed endpoint — no hand-waving.

## See it first: demo and architecture

Before I drag you through the buffering rabbit hole, here's what we're building toward.
This is the deployed endpoint, hit with `curl`, with the SSE padding stripped client-side
so you only see the tokens. Watch the left edge: words show up a few at a time while the
model is still thinking, not all at once at the end.

![Animated terminal demo: curl streaming Amazon Bedrock Nova Lite tokens from the Kotlin Lambda as Server-Sent Events, with text appearing progressively a few tokens at a time rather than in a single burst.](./bedrock-streaming-demo.gif)

And here's the shape of the whole thing. It's the same skeleton as the S3 example, with
Amazon Bedrock standing in where the generic data source used to be.

![Architecture diagram for the Bedrock SSE streaming example: a client calls an API Gateway REST endpoint configured for RESPONSE_STREAM, which invokes a Kotlin Lambda RequestStreamHandler; the Lambda calls Amazon Bedrock InvokeModelWithResponseStream and relays each delta back to the client as padded Server-Sent Events.](./SolutionDesign-Bedrock.png)

*The diagram traces the request flow end to end: client → API Gateway REST
(`RESPONSE_STREAM`) → Lambda `RequestStreamHandler` → Bedrock
`InvokeModelWithResponseStream` → padded SSE relay → client — each hop and the component
behind it is what the next section unpacks.*

## The problem

Here's the thing that cost me the most time, so let me put it up front. In the S3 example
each write was roughly a megabyte, and progressive delivery came for free — I wrote a
chunk, flushed, and the client saw it. A token relay is the opposite shape: Bedrock hands
you a few bytes at a time, so each write is tiny. And that's where the AWS Lambda
response-streaming runtime quietly gets in your way. It holds your small writes in an
internal buffer and only forwards them to the client once that buffer fills — around
100 KB in what I observed — or when the invocation ends.

The part that trips everyone up is that there is no application-level `flush()` that
overrides this. You can call `flush()` after every single token, which feels like it
should force the bytes out, and the stream still lands in the client's lap as one terminal
burst at the very end — not the progressive trickle you were after. The flush happens at
your language layer, but the buffer that's actually gating delivery sits *below* it, inside
the Lambda streaming runtime.

That placement matters, because it tells you where this is *not* coming from. This is not a
JVM quirk, it is not something in these libraries, and it is not API Gateway sneaking a
buffer into the path. It is the Lambda streaming runtime itself, and it reproduces across
languages and even with Lambda Function URLs — take API Gateway out of the picture entirely
and the behavior is still there. So no amount of cleverness on the JVM side makes it go
away; you have to work *with* the buffer, which is what the rest of this article is about.

One more thing worth saying plainly, because it shapes how you'll think about the fix: none
of this needs any special runtime machinery. There are **no Lambda layers** here, and
**no custom runtime** — the function runs on the managed `java25` runtime, nothing more.
The workaround is entirely in how you write bytes, not in how you package or provision the
function.

## Architecture walkthrough

Let me trace a single request end to end before naming the parts. You `POST` to
`/stream` with a tiny JSON body — `{"prompt":"...","maxTokens":N}` — and here's the hop
sequence that follows:

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

Each hop maps to a named component, so here's the cast and what each one does.

**[`BedrockStreamingHandler`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-bedrock-example/src/main/kotlin/nl/vintik/lambda/bedrock/BedrockStreamingHandler.kt)**
is the entrypoint AWS actually invokes — the no-arg
`RequestStreamHandler` named in the SAM template. It exists so the runtime has a zero-arg
constructor to build, and it hands off immediately to the orchestrator. Keeping it thin is
deliberate: the interesting logic lives one level down where it's easy to test without the
Lambda runtime in the way.

**[`BedrockStreamHandler`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-bedrock-example/src/main/kotlin/nl/vintik/lambda/bedrock/BedrockStreamHandler.kt)**
is that orchestrator, and it's the spine of the whole request.
It runs the three steps in order: resolve the incoming request, write the response
metadata, then relay the model's tokens out as padded SSE. Everything else is a
collaborator it calls into.

**[`PromptResolver`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-bedrock-example/src/main/kotlin/nl/vintik/lambda/bedrock/PromptResolver.kt)**
is the first step. It's a `RequestResolver` that takes the raw API
Gateway proxy event, parses the JSON body, validates it, and clamps the cost-and-size knobs
(the prompt plus things like `maxTokens`) to safe bounds before anything hits Bedrock. This
is where a malformed or oversized request gets rejected or trimmed, so the paid model call
downstream only ever sees something sane.

**[`BedrockSource`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-bedrock-example/src/main/kotlin/nl/vintik/lambda/bedrock/BedrockSource.kt)**
is the token producer. It implements `TokenStreamSource` — the
streaming-source contract, a token-stream analogue of the S3 example's `StreamSource` — and
its job is to call Bedrock Nova Lite via `InvokeModelWithResponseStream` using the Kotlin
AWS SDK (not the Java one) and surface each text delta as the model generates it. The
handler pulls deltas from this source rather than knowing anything about Bedrock directly.

**[`PaddedSseWriter`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-sse/src/main/kotlin/nl/vintik/lambda/streaming/sse/PaddedSseWriter.kt)**
is where the real trick lives, and it comes from the `streaming-sse`
module rather than the example itself. It encodes each token delta as a Server-Sent Event
and appends tiered SSE-comment padding — the workaround that forces the Lambda runtime
buffer open so small writes actually leave the function. I dig into exactly how the padding
is tiered in the next section; for now, just know this is the component standing between
"tokens the model produced" and "bytes on the wire."

**[`PacedSseEmitter`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-sse/src/main/kotlin/nl/vintik/lambda/streaming/sse/PacedSseEmitter.kt)**
is an optional wrapper around that writer, also from `streaming-sse`.
If you want a deliberately paced, dribbled UX — tokens spaced out over time rather than
emitted as fast as Bedrock produces them — this is what adds the inter-chunk delay. Its one
firm rule is that it enforces padding *before* delay: it refuses to be constructed with a
delay against a writer that carries no padding, because a delay with nothing to push past
the buffer does nothing. More on that trap later; here it's enough that pacing is a layer
you opt into on top of the writer, not a separate path.

**[`ResponseWriter`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-core/src/main/kotlin/nl/vintik/lambda/streaming/ResponseWriter.kt)**
comes from the `streaming-core` library and owns the API Gateway
streaming wire protocol. Before the first SSE event goes out, it writes the metadata
prelude — the status code and headers — in the exact shape the streaming response format
expects. That ordering matters: once the prelude is committed the status is locked in, so
the handler resolves and validates the request first, then writes the prelude, then starts
relaying. `PaddedSseWriter` handles the event framing; `ResponseWriter` handles everything
that has to come before it.

**[`Priming`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-bedrock-example/src/main/kotlin/nl/vintik/lambda/bedrock/Priming.kt)**
is off to the side of the request flow but worth naming, because it's why the
first real request isn't slow. It's a CRaC `beforeCheckpoint` warm-up hook that exercises
the critical path — building the Bedrock client, the handler, serialization — during
SnapStart's snapshot phase, so the restored snapshot comes up warm. This is part of the
shared cold-start setup I'm leaning on from the S3 example rather than re-explaining here;
it just quietly does its job before any token ever streams.

Put together: `BedrockStreamingHandler` receives the invocation and delegates to
`BedrockStreamHandler`, which uses `PromptResolver` to make sense of the request,
`ResponseWriter` to open the response, `BedrockSource` to pull tokens from Bedrock, and
`PaddedSseWriter` (optionally paced by `PacedSseEmitter`) to relay each token out as a
padded SSE event — with `Priming` having warmed the whole path ahead of time.

## The padding workaround (and credit where it's due)

So the buffer is real, it lives below you, and `flush()` won't move it. The fix is almost
embarrassingly blunt: if the runtime only forwards bytes once its buffer fills, then make
the first few writes big enough to fill it. Pad them. That's the whole idea — and the SSE
format happens to give you a perfect place to hide the padding.

Server-Sent Events have a quiet little feature: any line that begins with a colon (`:`) is
a comment, and compliant clients silently ignore it. It never reaches your `onmessage`
handler, and a plain shell filter like `sed -n 's/^data: //p'` drops it too. So you can
append a fat comment line to every event, and as far as the consumer is concerned it isn't
there. Each event on the wire ends up looking like this:

```
data: <token text>\n\n
: <padding spaces>\n\n      <- ignored by the client; only there to trip the buffer
```

The top line is the real token the application cares about. The bottom line is pure
ballast — a comment stuffed with spaces whose only job is to help cross that ~100 KB
buffer. The client throws it away; you only pay for it on the wire.

### Tiered, because the buffer behaves differently over time

The one subtlety is that you don't pad every event the same. Watching how the runtime
behaves, two things are true: the *first* writes need to be large enough to blow the
initial buffer wide open, but once the stream is genuinely flowing it takes far less to
keep it moving. So `PaddedSseWriter` pads in two tiers:

- **~100 KB of head padding on the first 3 events** (`DEFAULT_HEAD_PAD_BYTES` = `102400`,
  `DEFAULT_HEAD_PAD_EVENTS` = `3`) — this is the heavy hit that breaks the initial buffer
  so the first tokens show up fast.
- **~10 KB of tail padding on every event afterwards** (`DEFAULT_TAIL_PAD_BYTES` = `10240`)
  — lighter, but not optional. The runtime re-buffers after those first few events, so
  without a bit of ongoing tail padding the stream stalls again. The tail keeps it flowing.

That asymmetry — a big upfront shove, then a steady trickle — is the shape that actually
produced progressive delivery in practice, and it's baked in as the default tiers.

### The trade-off: you're paying in bandwidth

None of this is free, and I want to be honest about the cost. Padding inflates the
on-the-wire response size by roughly **100× to 1000×** relative to the original payload. In
my own measurements a ~15 KB answer ballooned to several MB on the wire. That's the deal
you're making: you spend bandwidth to buy responsiveness.

For a chat-style UX, where time-to-first-token is the whole experience, that's usually a
trade worth making — nobody minds a few megabytes of invisible comments if the words start
appearing instantly. For bulk output, where you just want the bytes and don't care when the
first one lands, it is absolutely not worth it; skip the padding entirely. And the one
genuinely reassuring part: the padding bytes never reach your application code. The client's
SSE parser (or that `sed` filter) strips every comment line, so what your consumer sees is
byte-for-byte the payload you meant to send.

### Credit where it's due

I'd love to claim I discovered this buffering behaviour, but I didn't. It was written up
independently — from a Python/FastAPI stack running behind the Lambda Web Adapter, so a
completely different world from this Kotlin/JVM one — in the AWS Builder Center article
["The Curious Case of SSE Streaming Agent Responses on AWS Lambda"](https://builder.aws.com/content/39iqpehwjnn2TuNmGnHAnsMcHmP/the-curious-case-of-sse-streaming-agent-responses-on-aws-lambda).
That piece got there first, and the fact that the same buffer shows up from an entirely
different language and framework is exactly what convinced me this sits in the Lambda
streaming runtime itself rather than anywhere in my own stack. Worth a read if you want the
original account; what you've got here is the JVM-flavoured version of the same fight.

## Consuming the libraries

Everything above is mechanism. Here's how you actually pull it into a project of your own,
because both pieces — the wire protocol and the padding — ship as libraries you can depend
on rather than code you have to copy.

### `streaming-core`: the wire protocol via `ResponseWriter`

The lower layer is `streaming-core`, published to Maven Central under the coordinate
`nl.vintik:aws-lambda-streaming-core`. One Gradle line gets it:

```kotlin
implementation("nl.vintik:aws-lambda-streaming-core:2.4.1")
```

The group is `nl.vintik` and the artifact is `aws-lambda-streaming-core` — note the artifact
id isn't the module directory name (`streaming-core`); it's the longer, more descriptive
published name. In code you import the one type you need:

```kotlin
import nl.vintik.lambda.streaming.ResponseWriter
```

`ResponseWriter` owns the API Gateway streaming wire format. Its job in this flow is to write
the **metadata prelude** — the status code and headers — before the first SSE event goes out.
That prelude has to land first and exactly once: the moment it's written the status is
committed, so you resolve and validate the request *before* you call it, then write the
prelude, then start relaying tokens.

The one shape to get right is the headers. They're a plain `Map<String, String>` — single
value per header name, not a `Map<String, List<String>>`. That looks like a small detail but
it's load-bearing; the multi-value shape is valid JSON yet makes API Gateway hand back a 502.
(I walk through that failure in the server-side lessons.) So the prelude you write looks like:

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

The padding workaround lives one module up, in `streaming-sse`, under the package
`nl.vintik.lambda.streaming.sse`:

```kotlin
import nl.vintik.lambda.streaming.sse.PaddedSseWriter
import nl.vintik.lambda.streaming.sse.PacedSseEmitter
```

You don't have to declare `streaming-core` separately when you use this module.
`streaming-sse` declares `streaming-core` as an `api` dependency, so depending on
`streaming-sse` pulls `streaming-core` in transitively — you get `ResponseWriter` for free
alongside the SSE types.

`PaddedSseWriter` is the pure encoder: it frames each token as an SSE `data:` event and
appends the tiered comment padding from the previous section. The no-arg constructor applies
the default tiers (`~100 KB` head on the first 3 events, `~10 KB` tail after), or you can
spell them out:

```kotlin
// Default tiers — the DEFAULT_* constants from the previous section:
val writer = PaddedSseWriter()

// …or explicit tiers if you want to tune them:
val tuned = PaddedSseWriter(headPadBytes = 102_400, tailPadBytes = 10_240, headPadEvents = 3)

writer.writeEvent(output, "some token")  // one data: event + its tier's padding; flushes; returns bytes written
writer.writeEnd(output)                  // terminal bytes: event: end / data: [DONE]; flushes
```

`writeEvent(output, text)` writes one event, appends the right padding tier (head while the
event index is below `headPadEvents`, tail afterwards), flushes, and returns the exact UTF-8
byte count it wrote. `writeEnd(output)` emits the terminal `event: end` / `data: [DONE]`
sentinel and flushes. That's the whole surface you need for a straight, as-fast-as-possible
relay.

`PacedSseEmitter` wraps the writer when you want a deliberately paced, dribbled UX instead —
tokens spaced out over time rather than fired off the instant Bedrock produces them:

```kotlin
val emitter = PacedSseEmitter(writer, delayMillis = 100)  // default delayMillis = NO_DELAY

emitter.emit(output, "some token")  // writes + flushes the padded event, THEN pauses before the next one
emitter.end(output)                 // delegates to writer.writeEnd; never adds a trailing delay
```

It delegates all the encoding to the `PaddedSseWriter` and only adds the inter-chunk delay.
Crucially the event is always written and flushed *before* any pause, so each paced chunk
crosses the buffer first and only then does the emitter sleep. And it enforces
padding-before-delay at construction: hand it a non-`NO_DELAY` delay against a writer that
carries no padding and it throws `IllegalArgumentException`, because a delay with nothing to
push past the buffer does nothing (more on that in the lessons). If you don't want pacing,
skip the emitter and drive the `PaddedSseWriter` directly.

### How the Bedrock example wires them together

The `streaming-bedrock-example` module is just these pieces assembled, and
`BedrockStreamHandler` is where the assembly happens. The sequence it runs is exactly the one
the types imply:

1. **Resolve** the incoming request with `PromptResolver` — parse, validate, clamp the
   prompt and `maxTokens` to safe bounds.
2. **Open the response** by writing the metadata prelude with `ResponseWriter` (status +
   `text/event-stream` headers), committing the status before any token goes out.
3. **Pull token deltas** from `BedrockSource`, which drives Bedrock Nova Lite's
   `InvokeModelWithResponseStream` and surfaces each text delta as the model generates it.
4. **Relay each delta** through `PaddedSseWriter.writeEvent`, so every token leaves the
   function as a padded SSE event that crosses the runtime buffer — optionally paced through
   `PacedSseEmitter` if you want the dribbled cadence. When the model is done, `writeEnd`
   closes out the stream.

So the two libraries split cleanly: `streaming-core`'s `ResponseWriter` handles everything up
to and including the prelude, and `streaming-sse`'s `PaddedSseWriter` / `PacedSseEmitter`
handle every event after it. The example module supplies the Bedrock-specific glue
(`PromptResolver`, `BedrockSource`) and lets the libraries do the streaming.

## Build, test, validate, deploy, try it

Enough mechanism — here's how you run the whole thing end to end. A few things need to be
in place before you start: the Gradle wrapper handles the JDK 25 toolchain for you (you
don't install Java yourself), but you do need the **AWS CLI** and the **AWS SAM CLI**
configured against an account, and you need **Amazon Bedrock Nova Lite model access enabled
in your region** — the example runs in `eu-west-1`. Without that model access the deploy
succeeds but the first real request fails when it tries to invoke Bedrock.

### Build

The build produces the Lambda fat jar — `bedrock-streaming-endpoint.jar`, assembled via
`:streaming-bedrock-example:shadowJar` — through the build script in the SAM directory:

```bash
cd deployment/aws/sam-bedrock
./build.sh
```

### Test

Run the module's unit and property tests — no AWS and no Docker needed for these:

```bash
./gradlew :streaming-bedrock-example:test
```

If you want the coverage report and the gate as well:

```bash
./gradlew :streaming-bedrock-example:koverHtmlReport :streaming-bedrock-example:koverVerify
```

### Validate the SAM template

Before you deploy, check the template is well formed:

```bash
sam validate --template-file deployment/aws/sam-bedrock/template.yaml
```

### Deploy

Deploying is the build followed by `deploy.sh`, which runs `sam deploy` against the config
in `samconfig.toml`:

```bash
cd deployment/aws/sam-bedrock
./build.sh
./deploy.sh
```

Once the stack is up, you need two things to call it: the endpoint URL and an API key, both
of which come out of the CloudFormation stack. The URL is the `StreamingEndpointUrl` output;
the API key is referenced indirectly — the `ApiKeyId` output gives you the key's id, and
`aws apigateway get-api-key --include-value` resolves that id to the actual secret value:

```bash
ENDPOINT_URL=$(aws cloudformation describe-stacks --stack-name bedrock-streaming-endpoint \
  --query "Stacks[0].Outputs[?OutputKey=='StreamingEndpointUrl'].OutputValue" --output text)
API_KEY=$(aws apigateway get-api-key --api-key \
  "$(aws cloudformation describe-stacks --stack-name bedrock-streaming-endpoint \
     --query "Stacks[0].Outputs[?OutputKey=='ApiKeyId'].OutputValue" --output text)" \
  --include-value --query 'value' --output text)
```

### Try it — stream tokens progressively

Now the payoff. Hit the `/stream` endpoint with `curl -N` and drop the padding client-side
with a plain `sed`/`awk` pipeline:

```bash
curl -N -H "x-api-key: $API_KEY" -H "Content-Type: application/json" \
  "${ENDPOINT_URL}stream" -d '{"prompt":"Explain why response streaming matters for serverless.","maxTokens":1000}' \
  | sed -u -n 's/^data: //p' | awk '{printf "%s",$0; fflush()}'
```

`sed -u -n 's/^data: //p'` keeps only the `data:` token lines and throws away the `:` padding
comments; `awk '{printf "%s",$0; fflush()}'` joins the tokens into flowing prose and flushes
as it goes so your terminal doesn't buffer them. What you'll see is the whole point of this
article: words appear progressively, a few tokens at a time, while the model is still
generating — not all at once at the end. (The `curl -N` and the `sed`/`awk` filter both
matter for this to work; I unpack why in the client-side lessons.)

## Did it actually stream? Verification and measured results

A demo GIF is nice, but I don't want you to take my word for it — and I didn't want to take
my own word for it either. So the repo ships a reproducible check:
[`scripts/bedrock-streaming-test.sh`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/scripts/bedrock-streaming-test.sh). It hits the
deployed endpoint and measures whether tokens actually arrive progressively or just land in
one lump at the end.

Two things it measures, both the signals that distinguish real streaming from a buffered
burst. First, **time-to-first-byte** (TTFB) in milliseconds — how long the client waits
before the very first byte shows up. Second, **the distribution of received bytes across
fixed 500 ms time buckets** — it stamps each arrival against a monotonic clock and counts
how many distinct 500 ms windows the bytes land in. One bucket means everything arrived in a
single wave (buffered). Many buckets spread over the generation means the bytes trickled out
as the model produced them (progressive). It also checks for the terminal `[DONE]` sentinel,
so a passing run has proven a complete stream, not a truncated one.

You run it like this:

```bash
BEDROCK_TEST_ENABLED=true AWS_REGION=eu-west-1 ./scripts/bedrock-streaming-test.sh
```

That `BEDROCK_TEST_ENABLED=true` in front is not optional, and it's there on purpose. This
script invokes **paid Bedrock** — every run costs real money for the model call — so it is
**opt-in and disabled by default**. Without `BEDROCK_TEST_ENABLED=true` the script runs but
exits cleanly without ever calling the model, which is exactly what you want on an ordinary
feature push. You have to explicitly opt in to spend the money.

### What it measured

Here's the result from running it against the deployed endpoint in `eu-west-1`, comparing the
relay with padding switched off against the tiered padding scheme from earlier:

| Configuration | TTFB | Arrival | Verdict |
|---|---|---|---|
| No padding | ≈ total | 1 time bucket | single terminal burst |
| Tiered 100 KB × 3 + 10 KB | ~0 ms | 9 distinct 500 ms buckets over ~4.4 s | **progressive** |

Read that top to bottom and you can see the whole argument of this article in two rows. With
**no padding**, TTFB is essentially the total response time — the client waits the entire
generation and then everything arrives at once, in a single time bucket. That's the single
terminal burst, with no intermediate progressive arrival at all: the buffer held every write
until the stream ended. Turn on the **tiered 100 KB × 3 + 10 KB** padding and the shape
flips completely: TTFB drops to near 0 ms, and the bytes spread across 9 distinct 500 ms
buckets over roughly 4.4 seconds of total delivery. The first tokens show up almost
immediately and keep coming in waves while the model is still generating — that's
progressive delivery, which is the entire point.

Those numbers aren't a guess or a best case I'm hoping holds. They came out of
`scripts/bedrock-streaming-test.sh` run against a real deployed endpoint, so you can
reproduce them yourself with the one command above.

## Lessons learned — server side

Everything above is the tidy version — the shape of the solution once I knew where the
traps were. Here's the honest version: the list of ways I got this wrong on the way there.
Each of these cost me a chunk of time, and most of them share a nasty quality — they fail
*silently*, with clean logs and a happy-looking function, so there's nothing to grep for.
I'm writing them down symptom-first, because the symptom is the only thing you'll actually
have in front of you when you hit one.

**1. A naive `flush()` does nothing — the buffer is below you.**
*Symptom:* you relay Bedrock tokens, call `flush()` after every single write because of
course you do, and the stream still lands as one terminal burst at the very end. No error,
no warning — it just isn't progressive.
*Fix:* stop expecting `flush()` to help. The flush happens at your language layer, but the
buffer that gates delivery lives *below* it, inside the Lambda streaming runtime — it only
forwards once it's filled (~100 KB observed) or the invocation ends. The only lever you
have is filling it, which is the padding workaround. This is the whole reason the rest of
this list exists; I led with it in the problem section because it reframes every fix that
follows.

**2. An inter-chunk delay alone does nothing either.**
*Symptom:* the intuitive next move after the flush fails is to slow down — space the writes
out with a small `sleep` between tokens, figuring that'll force them out one at a time. It
doesn't. The delayed small writes still never cross the ~100 KB buffer, so they coalesce
and burst at the end exactly as they did with no delay. This was the earlier MockNest
"dribble" attempt, and it's a seductive dead end because pacing *feels* like it should
matter.
*Fix:* a delay is only effective *combined with padding*. Padding is what gets the stream
flowing across the buffer; the delay only shapes the cadence once it's already flowing. On
its own it buys you nothing.

**3. `PacedSseEmitter` won't let you make mistake #2.**
This one's a fix baked into the library rather than a bug I left in. Because "delay alone
does nothing" is so easy to fall for, `PacedSseEmitter` refuses to be constructed with a
non-`NO_DELAY` delay against a writer that carries no padding — it throws
`IllegalArgumentException` at construction time (`!writer.hasPadding()`).
*Why it has to:* a delay with no padding is a configuration that looks deliberate and does
absolutely nothing — the worst kind of silent no-op. Padding is required because it's what
fills the runtime buffer, forcing the earlier chunks out where the client can actually
observe them; without it the pacing just reshuffles writes that never leave the buffer
until the end. Making it a hard failure means you find out at startup, not after a deploy
and a confusing non-progressive stream.

**4. The `OutputStream` has to be flushed *and* closed, or warm invocations truncate.**
*Symptom:* this is the meanest one. Everything works on the first invocation after a
SnapStart restore. Then every warm invocation afterward delivers a *partial* body and drops
the connection — `curl` reports `curl: (18) transfer closed with 4112195 bytes remaining to
read`. The CloudWatch logs are clean, the `REPORT` line is normal (no timeout, ~1 s
duration), there's no exception anywhere. The bug only shows up under timing pressure on
fast warm invocations — the slow cold path happened to leave enough time for the runtime's
own teardown to flush everything, which is exactly why it hides.
*Fix:* two things, together. Wrap the handler body in `output.use { }` so `close()` runs on
every exit path — normal return, error response, uncaught exception — because Lambda
streaming uses the stream close as the "response is complete" signal. And add an explicit
`output.flush()` *before* that close, so the runtime drains its buffer completely before it
gets the close signal; without the explicit flush a race in the streaming channel
occasionally left the last chunk undelivered even after `close()`. Flush, then close, on
every path.

**5. Metadata headers must be `Map<String, String>`, not `Map<String, List<String>>`.**
*Symptom:* the Lambda runs perfectly — completes in time, no errors in CloudWatch — and API
Gateway hands the client back `HTTP 502 {"message": "Internal server error"}`. Nothing in
*your* logs is wrong, because nothing in your code failed; the break is in how API Gateway
parses what you wrote. And it only surfaces against a real deployed API Gateway — a local
emulator won't enforce the prelude format, so your integration tests stay green while the
deployed endpoint 502s.
*Fix:* model the prelude's `headers` field as `Map<String, String>` — one plain string per
header name. The `Map<String, List<String>>` form serializes to JSON arrays
(`"Content-Type":["text/event-stream"]`), which is valid JSON but *not* a shape API
Gateway's streaming parser recognizes, so it can't extract the status code and headers and
returns 502. Repeatable headers like `Set-Cookie` don't go in a JSON array inside `headers`
either — the streaming format carries them in a separate `cookies` field. This was the
single most consequential gotcha in porting the Node.js streaming helper to the JVM,
because the prelude format is only ever implicitly documented through that helper's
behavior.

**6. `STREAM` vs `RESPONSE_STREAM` — same idea, two names, two layers, not interchangeable.**
*Symptom:* you wire up the transfer mode, deploy, and the endpoint comes back buffered —
non-streaming — with no error to tell you why. You've used the right concept with the wrong
name for the layer you're at, and the only feedback is a full, slow deploy cycle that ends
in a buffered response.
*Fix:* keep the two layers straight. At the SAM `Api` event level the property is
`ResponseTransferMode: RESPONSE_STREAM`. At the lower-level API Gateway *integration* the
equivalent value is `STREAM`, and that path *additionally* needs the integration URI to
carry the `/response-streaming-invocations` suffix. They are not interchangeable and they
apply at different layers — set the wrong one, or the right one at the wrong layer, and you
silently get a buffered endpoint. For this project the convention is simple: use
`RESPONSE_STREAM` at the SAM `Api` level, and run `sam validate --template-file
deployment/aws/sam-bedrock/template.yaml` (exit code 0) before committing any template
change. This is the shared-setup piece from the S3 example, and it's worth re-flagging here
precisely because it's the kind of trap that eats a whole deploy cycle before you notice.

## Lessons learned — client side

Getting the server to stream progressively is only half the fight. Even with the padding
working and the bytes genuinely leaving the function in waves, the *client* can still
quietly undo all of it — buffer the response, choke on the protocol, or drown you in
padding. These three bit me on the consuming side, and like the server-side ones they
mostly fail in ways that look like something else. Here they are symptom-first, with the
invocation that broke and the one that worked.

**1. `curl` over HTTP/2 fails with error 92 — force `--http1.1`.**
*Symptom:* in CI (GitHub Actions) and on any machine with a newer `curl` that defaults to
HTTP/2, a streaming request against the API Gateway endpoint dies with:

```
curl: (92) HTTP/2 stream 1 was not closed cleanly: INTERNAL_ERROR (err 2)
```

The body may have transferred completely, but `curl` treats the unclean stream close as
fatal and exits non-zero — so your test reports the endpoint as unreachable when it
actually streamed fine. This is the failing call:

```bash
# Fails on HTTP/2 (newer curl defaults to it) with curl: (92)
curl -N -H "x-api-key: $API_KEY" -H "Content-Type: application/json" \
  "${ENDPOINT_URL}stream" -d '{"prompt":"...","maxTokens":1000}'
```

*Fix:* force HTTP/1.1 on every `curl` call. API Gateway's streaming response uses chunked
transfer encoding; over HTTP/2 the frontend doesn't terminate the stream cleanly after the
Lambda finishes writing, and `curl` reads that as an INTERNAL_ERROR. HTTP/1.1 chunked
transfer terminates cleanly with a zero-length chunk and just works:

```bash
# Add --http1.1 and it's clean regardless of curl's default protocol
curl --http1.1 -N -H "x-api-key: $API_KEY" -H "Content-Type: application/json" \
  "${ENDPOINT_URL}stream" -d '{"prompt":"...","maxTokens":1000}'
```

One thing worth flagging: the AWS docs don't cover this. The API Gateway response-streaming
troubleshooting page mentions `curl: (18) transfer closed with outstanding read data
remaining` (a timeout symptom), but error 92 — the HTTP/2 stream-close incompatibility —
isn't documented. So if you hit it, you won't find it in the official guidance; `--http1.1`
is the fix.

**2. Drop the SSE padding, or you'll read megabytes of comments.**
*Symptom:* the stream carries all that `:` comment-line padding you added server-side to
trip the runtime buffer. A proper SSE client ignores comment lines for you, but if you're
poking at the raw stream from the shell you'll see the padding drowning the actual tokens —
the signal buried in a sea of ballast.
*Fix:* keep only the `data:` lines and reassemble them. A plain `sed`/`awk` pipeline
recovers the original payload byte-for-byte:

```bash
curl --http1.1 -N -H "x-api-key: $API_KEY" -H "Content-Type: application/json" \
  "${ENDPOINT_URL}stream" -d '{"prompt":"...","maxTokens":1000}' \
  | sed -u -n 's/^data: //p' | awk '{printf "%s",$0; fflush()}'
```

`sed -u -n 's/^data: //p'` keeps only the text after the `data: ` token and discards every
`:` comment line — which is exactly the padding — so the inflation you pay for on the wire
never reaches your eyes. The terminal event contributes a single `[DONE]` sentinel line, so
that's the one marker you'll see at the end telling you the stream completed rather than
truncated. The padding is only there to fill the Lambda runtime buffer and force the early
writes out; a compliant SSE client (or this filter) throws all of it away, which is the
whole reason hiding the workaround in comment lines works at all.

**3. `curl -N` (`--no-buffer`) is required, or `curl` buffers it for you.**
*Symptom:* this is the sneaky one, because the server is streaming *correctly* — and you
still see the whole response appear at once. Everything you did server-side worked, the
bytes are arriving in waves, but `curl` is collecting them into its own output buffer and
only flushing when it's good and ready. The result looks exactly like a buffered endpoint,
masking the streaming you just worked so hard to prove:

```bash
# Without -N, curl buffers the response and it appears all at once
curl --http1.1 -H "x-api-key: $API_KEY" -H "Content-Type: application/json" \
  "${ENDPOINT_URL}stream" -d '{"prompt":"...","maxTokens":1000}'
```

*Fix:* add `-N` (long form `--no-buffer`), which tells `curl` not to buffer the output and
to hand each chunk straight through as it arrives:

```bash
# -N / --no-buffer disables curl's own output buffering
curl --http1.1 -N -H "x-api-key: $API_KEY" -H "Content-Type: application/json" \
  "${ENDPOINT_URL}stream" -d '{"prompt":"...","maxTokens":1000}'
```

And the same discipline applies one hop further down the pipe: the `fflush()` in the `awk`
stage above is the exact same fix at the `awk` layer — flush each token as it's written so
`awk` doesn't re-buffer what `curl` just handed over unbuffered. Every stage in the pipe
gets its own chance to buffer, and progressive delivery only survives if none of them do.

## Closing

So that's the whole fight. The S3 example streamed for free because each write was a
megabyte; this one needed real work because Bedrock hands you a few bytes at a time and the
Lambda streaming runtime quietly buffers small writes below the language layer where no
`flush()` can reach. The fix wasn't clever runtime machinery — no Lambda layers, no custom
runtime, just the managed `java25` runtime — it was padding: fat SSE comment lines, ~100 KB
on the first few events and ~10 KB after, that fill the buffer and force your tokens out
where the client can see them. You pay for it in bandwidth, you hide it in `:` comment lines
the client throws away, and you watch for the handful of traps on both the server and client
side that fail silently with clean logs. Wrap `ResponseWriter` and `PaddedSseWriter` /
`PacedSseEmitter` around that idea and the token-by-token trickle you wanted is just there.

If you landed here first, go back and read the [S3-streaming example](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/main/streaming-s3-example/README.md) — it's the
other half of this series and it's where all the shared setup lives (SnapStart with CRaC
priming, the SAM `RESPONSE_STREAM` wiring, arm64, tiered compilation) that I leaned on
without re-explaining. And if you want to actually build this, the two libraries —
`streaming-core` and `streaming-sse` — are on Maven Central, so you can skip straight to
depending on them and wiring up your own token source. The links below point at the exact
code this article describes.

## Source

Everything above is pinned to the `main` branch so the links keep matching the code as you
read it.

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
