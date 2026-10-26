# streaming-sse

A tiny, reusable library that makes **small-chunk Server-Sent Events actually stream progressively** from a JVM AWS Lambda behind API Gateway REST. It promotes the proven padding/dribble workaround out of [`streaming-bedrock-example`](../streaming-bedrock-example/) into a standalone module so this repo's examples and the external MockNest project can share one implementation.

Built on [`aws-lambda-streaming-core`](../README.md) (declared `api`, so a consumer that depends on `streaming-sse` gets `streaming-core` transitively). No AWS SDK, no coroutines — just the SSE framing and the buffer workaround.

## The problem: Lambda's response-streaming buffer

When a Lambda writes **large** chunks (e.g. the [S3 example](../streaming-s3-example/)'s ~1 MB blocks), response streaming works with no tricks. But an LLM token relay writes only a few bytes per token, and here the **AWS Lambda response-streaming runtime holds small writes in an internal buffer** and only forwards them to the client when the buffer fills (**~100 KB observed**) or the invocation ends.

This buffering happens in the **Lambda streaming runtime itself, below the language layer** — not in the JVM, not in this library, and not in API Gateway. There is **no application-level `flush()` that overrides it**: a naive token relay flushes every write and still arrives as a single terminal burst rather than a progressive trickle.

The behaviour is documented independently (from a Python/FastAPI + Lambda Web Adapter stack) in the AWS Builder Center article [“The Curious Case of SSE Streaming Agent Responses on AWS Lambda”](https://builder.aws.com/content/39iqpehwjnn2TuNmGnHAnsMcHmP/the-curious-case-of-sse-streaming-agent-responses-on-aws-lambda). *(External reference; content not reproduced here.)*

## The workaround: tiered SSE-comment padding (+ optional dribble)

SSE comment lines start with `:` and are silently ignored by compliant clients (and dropped by a plain `sed -n 's/^data: //p'` filter). `PaddedSseWriter` appends a comment-line padding block to each event so the flushed chunk crosses the buffer:

```
data: <token text>\n\n
: <padding spaces>\n\n      <- ignored by the client; only there to trip the buffer
```

Padding is **tiered**, matching what is needed in practice:

- **~100 KB head padding on the first few events** (`DEFAULT_HEAD_PAD_BYTES` = `102400`, `DEFAULT_HEAD_PAD_EVENTS` = `3`) — breaks the initial buffer so the first tokens appear fast.
- **~10 KB tail padding on every event afterwards** (`DEFAULT_TAIL_PAD_BYTES` = `10240`) — keeps the stream flowing; the runtime re-buffers after the first few events without ongoing tail padding.

On top of the writer, `PacedSseEmitter` adds an **optional inter-chunk dribble delay** that spaces events out over time (for a deliberately paced UX) rather than emitting them as fast as the model produces them.

### Delay alone does nothing — it needs padding

This is the key, verifiable claim: an **inter-chunk delay on its own does not force chunks past the runtime buffer**. Without padding, each small write still never crosses the ~100 KB buffer, so the delayed chunks coalesce and burst at the end exactly as they would with no delay (this is what the earlier MockNest "dribble" attempt ran into). The delay is effective **only when combined with padding** — once padding has the stream flowing, the delay paces delivery.

The module enforces this in code: `PacedSseEmitter` **rejects construction** (with an `IllegalArgumentException`) when a non-`NO_DELAY` delay is configured against a writer that carries no padding (`!writer.hasPadding()`). You cannot accidentally configure a delay that would silently do nothing.

### Trade-off: bandwidth for responsiveness

Padding inflates the on-the-wire response size by roughly **100× to 1000×** relative to the original payload (a ~15 KB answer can become several MB on the wire). You trade bandwidth for responsiveness. For a chat-style UX where time-to-first-token matters, that is usually worth it; for bulk output it is not. The padding bytes never reach application code — the client's SSE parser (or a `sed` filter) drops them.

## Public API

Package `nl.vintik.lambda.streaming.sse`. All declarations are under `explicitApi()`; the committed surface lives in [`api/streaming-sse.api`](api/streaming-sse.api).

### `PaddedSseWriter`

A pure, deterministic SSE + padding encoder. It owns only the event framing and padding; the metadata prelude is written by `streaming-core`'s `ResponseWriter` before the first event.

```kotlin
// Explicit tiers…
val writer = PaddedSseWriter(headPadBytes = 102_400, tailPadBytes = 10_240, headPadEvents = 3)
// …or the no-arg convenience constructor applying the DEFAULT_* tiers:
val writer = PaddedSseWriter()

val n = writer.writeEvent(output, "some token")  // data: line + tiered padding comment; flushes; returns bytes written
writer.writeEnd(output)                          // exact terminal bytes: event: end\ndata: [DONE]\n\n; flushes
writer.eventsWritten()                           // zero-based count of events written so far
writer.hasPadding()                              // true when headPadBytes > 0 || tailPadBytes > 0
```

- `writeEvent(output, text): Int` — writes one `data: <text>` event, appends the tier's padding comment (head tier for event index `< headPadEvents`, tail tier afterwards; omitted when that tier's pad is `0`), flushes, and returns the exact UTF-8 byte count written (including padding).
- `writeEnd(output)` — writes the exact terminal bytes `event: end\ndata: [DONE]\n\n` and flushes.
- `eventsWritten(): Int` — events written so far.
- `hasPadding(): Boolean` — whether any tier carries padding bytes.
- Construction rejects a negative value for any tier with `IllegalArgumentException`.
- Default constants: `DEFAULT_HEAD_PAD_BYTES` (`102400`), `DEFAULT_TAIL_PAD_BYTES` (`10240`), `DEFAULT_HEAD_PAD_EVENTS` (`3`).

### `PacedSseEmitter`

A thin wrapper that owns the optional pacing (dribble) concern and delegates all encoding to a `PaddedSseWriter`. The sleeper is injected so tests assert the pacing schedule with zero wall-clock delay.

```kotlin
val emitter = PacedSseEmitter(writer, delayMillis = 100)   // default delayMillis = NO_DELAY, sleep = Thread::sleep

emitter.emit(output, "some token")   // writes + flushes one padded event, then pauses before the NEXT event; returns bytes written
emitter.end(output)                  // delegates to writer.writeEnd; never applies a trailing delay
emitter.eventsWritten()              // delegates to the writer
```

- `emit(output, text): Int` — writes and flushes one padded event via the writer, then (if a delay is configured and this is not the first event) sleeps `delayMillis`. The event is always written and flushed **before** any pause, so each paced chunk crosses the buffer first. Returns the writer's byte count.
- `end(output)` — delegates to `writeEnd`; never sleeps.
- `eventsWritten(): Int` — delegates to the writer.
- Default constants: `NO_DELAY` (`0`), `MIN_DELAY_MILLIS` (`1`), `MAX_DELAY_MILLIS` (`5000`).
- Construction rejects (with `IllegalArgumentException`) a delay above `MAX_DELAY_MILLIS`, a delay below `MIN_DELAY_MILLIS` that is not the `NO_DELAY` sentinel, and a non-`NO_DELAY` delay against a writer with no padding (delay alone cannot cross the buffer — see above).

## Client-side: dropping the padding

The padding lives only in `:` comment lines, so a compliant SSE client ignores it. From the shell, a plain `sed`/`awk` pipeline recovers the original payload byte-for-byte:

```bash
curl -N ... | sed -u -n 's/^data: //p' | awk '{printf "%s",$0; fflush()}'
```

`sed -n 's/^data: //p'` keeps only the token text and drops the `:` padding comments; the terminal event contributes a single trailing `[DONE]` sentinel line.

## See also

- [`streaming-bedrock-example`](../streaming-bedrock-example/) — a deployed Lambda that consumes this module to stream Bedrock Nova Lite tokens, with a measured progressive-arrival test.
- [`aws-lambda-streaming-core`](../README.md) — the API Gateway streaming wire protocol (`ResponseWriter`) this module builds on.
