# Streaming Amazon Bedrock tokens from a Kotlin Lambda as Server-Sent Events

### Progressive token delivery through API Gateway REST, without a custom runtime or Lambda layer

In the previous article, "AWS Lambda Response Streaming for Kotlin and Java" [1], we streamed a large
S3 object from a Kotlin Lambda through API Gateway REST, aiming at large blobs that exceed the
Lambda and API Gateway buffered response limits. That worked in a straightforward way: `copy()` in
`streaming-core` writes the body in 1 MiB chunks from any `InputStream`, S3 or otherwise, and the
client receives it progressively. When I applied the same approach to Amazon Bedrock output, it did
not work the way I wanted it to. Bedrock produced its tokens progressively, the handler wrote and flushed every delta, and the client still
received the whole answer in a single burst at the end.

The difference is the size of each write. `copy()` writes 1 MiB at a time; a token delta is a few
bytes. In our deployed environment, large writes reached the client as they were written, while small writes
were held back until the response completed. The AWS Builder Center article "The Curious Case of SSE
Streaming Agent Responses on AWS Lambda" [2] reports the same behaviour from a Python/FastAPI stack
behind the Lambda Web Adapter, estimates the buffer at about 100 KB, and describes a solution:
padding each Server-Sent Event with SSE comment lines. We applied the same approach, and it worked for
us as well. What I added is a JVM implementation of it — the new
[`streaming-sse`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/v2.4.1/streaming-sse/README.md)
module on top of the existing `streaming-core` library — together with a deployable Kotlin/Bedrock
example and tests.

The pattern fits any JVM Lambda that produces its output incrementally and should deliver each
increment as it is produced: most commonly a Lambda that calls Amazon Bedrock as part of its
processing, for example one that runs an agent or LLM graph in-process, or a longer job that reports
progress events. The shared setup from the previous article — a regional API Gateway endpoint, SnapStart with CRaC
priming, arm64 and tiered compilation — is reused unchanged and not repeated here.

## Demo and architecture

The deployed endpoint, called with `curl` and with the SSE padding filtered out client-side. Words
appear a few at a time while the model is still generating.

![Animated terminal demo: curl streaming Amazon Bedrock Nova Lite tokens from the Kotlin Lambda as Server-Sent Events, with text appearing progressively a few tokens at a time rather than in a single burst.](./bedrock-streaming-demo.gif)

The architecture is the same as the S3 example, with Amazon Bedrock in place of the S3 data source.

![Architecture diagram for the Bedrock SSE streaming example: a client calls an API Gateway REST endpoint configured for RESPONSE_STREAM, which invokes a Kotlin Lambda RequestStreamHandler; the Lambda calls Amazon Bedrock InvokeModelWithResponseStream and relays each delta back to the client as padded Server-Sent Events.](./SolutionDesign-Bedrock.png)

The client sends `POST /stream` with `{"prompt":"...","maxTokens":N}`. API Gateway invokes the Lambda
in streaming mode, the handler calls Bedrock Nova Lite with `InvokeModelWithResponseStream`, and each
text delta is written back to the client as a padded SSE event.

## The problem: small writes arrive as one burst

It helps to separate what we observed, what AWS documents, and what is inferred.

**Observed.** Relaying Nova Lite deltas through API Gateway without padding, the full answer (about
800 bytes) arrived at the client as a single chunk; on the warm-up request curl reported a time to
first byte (TTFB) equal to the total time, 5.79 s. Calling `flush()` after every delta made no
difference. The same model called directly, outside Lambda, emitted 71 deltas spread over about
607 ms — so the model streams, and the delay happens downstream. On the same stack, the S3 example's
1 MiB writes streamed progressively.

**Documented.** With the response transfer mode set to `STREAM`, API Gateway does not wait for the
complete response before sending it to the client [4]; in SAM this is configured as
`ResponseTransferMode: RESPONSE_STREAM` [6]. AWS documents managed-runtime response
streaming for Node.js and points other languages to a custom runtime or the Lambda Web Adapter [3].
Neither page describes a size threshold, or a flush mechanism for small writes.

**Inferred.** The bytes are held somewhere below the handler's `OutputStream.flush()` and above the
client. Our measurements do not isolate which layer that is. The authors of [2] attribute it to the
Lambda response-streaming runtime and report the same behaviour with a Lambda Function URL, without
API Gateway. We could not repeat that check: on the managed Java runtime, a plain
`RequestStreamHandler` behind a Function URL returned HTTP 502 in our tests. The ~100 KB threshold is
the value observed in [2]; we confirmed that padding of that size produces progressive delivery, not
the exact threshold.

## The solution: SSE-comment padding

In Server-Sent Events, a line beginning with a colon is a comment, and compliant clients ignore it.
Appending a large comment to each event makes every write big enough to be forwarded, while the
consumer still sees only the `data:` lines. On the wire, each event looks like this:

```
data: <token text>\n\n
: <padding spaces>\n\n
```

The following excerpt is the core of the repository's
[`PaddedSseWriter.kt`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/v2.4.1/streaming-sse/src/main/kotlin/nl/vintik/lambda/streaming/sse/PaddedSseWriter.kt):

```kotlin
public fun writeEvent(output: OutputStream, text: String): Int {
    val pad = if (eventIndex < headPadEvents) headPadBytes else tailPadBytes
    val sb = StringBuilder(text.length + pad + 16)
    sb.append("data: ").append(text).append("\n\n")
    if (pad > 0) sb.append(": ").append(" ".repeat(pad)).append("\n\n")
    val bytes = sb.toString().toByteArray(Charsets.UTF_8)
    output.write(bytes)
    output.flush()
    eventIndex++
    return bytes.size
}
```

Each event is written as one byte array and flushed once. The padding is tiered, following [2]:
`DEFAULT_HEAD_PAD_BYTES = 102_400` on the first `DEFAULT_HEAD_PAD_EVENTS = 3` events, then
`DEFAULT_TAIL_PAD_BYTES = 10_240` on every later event. The authors of [2] found that padding only the
first events was not enough — buffering resumed afterwards — which is why the smaller tail padding
continues for the rest of the stream. A padded event adds exactly the padding size plus four bytes
(`": "` and `"\n\n"`). `writeEnd` closes the stream with `event: end` and `data: [DONE]`.

Padding is not an AWS recommendation. It addresses undocumented behaviour, and it may stop
being necessary — or stop working — if that behaviour changes.

## The JVM libraries

`streaming-sse` is published to Maven Central and declares `streaming-core` as an `api` dependency, so
one line brings in both:

```kotlin
implementation("nl.vintik:aws-lambda-streaming-sse:2.4.1")
```

`streaming-core` still owns the API Gateway wire format: `ResponseWriter` writes the metadata prelude
(status and headers), the eight-byte delimiter and error responses, as described in [1].
`streaming-sse` only adds the event framing and the padding.

### Ordering in the handler

The example's
[`BedrockStreamHandler.kt`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/v2.4.1/streaming-bedrock-example/src/main/kotlin/nl/vintik/lambda/bedrock/BedrockStreamHandler.kt)
runs in this order:

1. Parse and validate the request
   ([`PromptResolver`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/v2.4.1/streaming-bedrock-example/src/main/kotlin/nl/vintik/lambda/bedrock/PromptResolver.kt)).
   An over-long prompt returns HTTP 400; `maxTokens` and the padding values are clamped to safe bounds.
2. Start the Bedrock generation
   ([`BedrockSource`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/v2.4.1/streaming-bedrock-example/src/main/kotlin/nl/vintik/lambda/bedrock/BedrockSource.kt)).
3. Receive the first model delta.
4. Write the response metadata, committing HTTP 200.
5. Stream each delta as a padded SSE event.
6. Write the terminal event, then flush and close the output stream.

The following excerpt is adapted from that handler:

```kotlin
fun commitMetadataOnce() {
    if (committed) return
    responseWriter.writeMetadata(
        output,
        ResponseMetadata(
            statusCode = 200,
            headers = mapOf(
                "Content-Type" to "text/event-stream; charset=utf-8",
                "Cache-Control" to "no-cache",
                "Transfer-Encoding" to "chunked",
            ),
        ),
    )
    committed = true
}

val outcome = runCatching {
    runBlocking {
        source.stream(request) { delta ->
            commitMetadataOnce()          // first delta commits HTTP 200
            sse.writeEvent(output, delta)
        }
    }
}.rethrowFatal()                          // fatal failures propagate; the rest is handled below

outcome.fold(
    onSuccess = {
        commitMetadataOnce()              // zero deltas still returns 200
        sse.writeEnd(output)
    },
    onFailure = { e ->
        if (!committed) {
            logger.warn(e) { "Bedrock stream failed before the first delta; responding 502" }
            responseWriter.writeError(output, 502, "Model streaming failed.")
        } else {
            logger.error(e) { "Bedrock relay failed after status commit; body truncated" }
            throw e                       // status already committed: body is truncated
        }
    },
)
```

Deferring the metadata until the first delta is the head-before-stream rule from [1], applied to a
model call. Once the prelude is written, the status is committed. Before that point, a Bedrock failure
— throttling, missing model access, an invalid request — can still be returned as HTTP 502. After the
first delta, a failure is logged and rethrown, and can only truncate the body. `rethrowFatal()` is
a small `Result` extension in the example that lets cancellation and JVM errors propagate instead of
turning them into an HTTP error; every failure the example does handle is logged. A successful generation with no deltas still
returns HTTP 200 and the terminal event. The handler wraps everything in `output.use { }` and calls
`output.flush()` before the stream closes. Each of these paths is covered in
[`BedrockStreamHandlerTest`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/v2.4.1/streaming-bedrock-example/src/test/kotlin/nl/vintik/lambda/bedrock/BedrockStreamHandlerTest.kt).

### SAM configuration

The relevant part of
[`deployment/aws/sam-bedrock/template.yaml`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/v2.4.1/deployment/aws/sam-bedrock/template.yaml):

```yaml
BedrockStreamingApi:
  Type: AWS::Serverless::Api
  Properties:
    StageName: prod
    EndpointConfiguration:
      Type: REGIONAL
# ...
Events:
  StreamPost:
    Type: Api
    Properties:
      RestApiId: !Ref BedrockStreamingApi
      Path: /stream
      Method: post
      ResponseTransferMode: RESPONSE_STREAM
```

`ResponseTransferMode: RESPONSE_STREAM` is the SAM setting for the `Api` event [6]. It maps to the
response transfer mode `STREAM` documented for API Gateway [4]: SAM does not pass the value through
unchanged, but sets the integration's transfer mode to `STREAM` and switches the integration URI to Lambda's
`/response-streaming-invocations` path [7]. The function runs on the managed `java25` runtime, and the
API endpoint is regional rather than edge-optimized, as in the S3 example — see the lessons below for
why.

### Optional pacing

[`PacedSseEmitter`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/v2.4.1/streaming-sse/src/main/kotlin/nl/vintik/lambda/streaming/sse/PacedSseEmitter.kt)
wraps a writer and adds a fixed delay between events, for a deliberately paced display. The Bedrock
example does not use it.

```kotlin
val emitter = PacedSseEmitter(writer, delayMillis = 100)  // default delayMillis = NO_DELAY
emitter.emit(output, "some token")  // writes + flushes the padded event, then pauses before the next event
emitter.end(output)
```

Its constructor rejects a delay when the writer carries no padding, for the reason described in the
lessons below.

### Trying it

The example's
[Quick Start](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/tree/v2.4.1/streaming-bedrock-example#quick-start)
covers building, testing, deploying and resolving the endpoint URL and API key. Once deployed, the
call used for the demo is:

```bash
curl --http1.1 -N -H "x-api-key: $API_KEY" -H "Content-Type: application/json" \
  "${ENDPOINT_URL}stream" -d '{"prompt":"Explain why response streaming matters for serverless.","maxTokens":1000}' \
  | sed -u -n 's/^data: //p' | awk '{printf "%s",$0; fflush()}'
```

This pipeline is a display aid, not a decoder. `sed` keeps the text after each `data: ` prefix and
prints it as its own line, and `awk` joins those lines, so newlines in the model output are lost and
`[DONE]` appears at the end. A real client should use an SSE parser. Padding can be switched off for
a single request by adding `"headPadBytes":0,"tailPadBytes":0` to the body.

## Measured results

The first measurements came from a temporary experiment stack, using a script that timestamped every
chunk as it arrived at the client. Arrival spread is the time from the first to the last data chunk.

| Run | Padding | Arrival spread | Distinct arrival windows | Result |
|---|---|---|---|---|
| Control | none | 1 ms | 1 | single burst |
| Short generation | 100 KB × 3 + 10 KB | 305 ms | 4 × 100 ms | progressive, brief |
| Long generation (5,000 tokens) | 100 KB × 3 + 10 KB | 2,684 ms | 6 × 500 ms | progressive |

Both factors matter: padding is what makes the stream progressive, and the generation has to last long
enough for that to be visible. Nova Lite is fast, so a short answer lands in well under a second even
when padded.

The deployed example is checked by
[`scripts/bedrock-streaming-test.sh`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/blob/v2.4.1/scripts/bedrock-streaming-test.sh),
which invokes paid Bedrock and therefore runs only with `BEDROCK_TEST_ENABLED=true`. Against the
deployed endpoint in `eu-west-1`, with default padding, data lines arrived over about 4.4 s across
9 distinct 500 ms windows, and the terminal `[DONE]` event was received. An unpadded run against the
same endpoint was recorded as a single burst in one window.

What the script does not measure is HTTP TTFB. Its clock starts when the first line reaches the
script, not when the request is sent, so its `ttf_ms` value is close to zero by construction and is
not a time to first byte. The reliable assertions are the window count (at least 3) and the presence
of `[DONE]`. Measuring a real TTFB would need curl's `%{time_starttransfer}`, as the S3 scripts use;
until then, the only HTTP TTFB we have is for the unpadded case.

What the tests prove, and what they do not: the unit and property tests in
[`streaming-sse`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/tree/v2.4.1/streaming-sse/src/test/kotlin/nl/vintik/lambda/streaming/sse)
verify the byte format, the tier sizes, one flush per event and the padding-before-delay rule, and the
handler tests verify the status ordering. None of them reproduce the buffering itself — local
emulators do not implement streaming invocation — so progressive delivery is shown only by the
deployed measurements above: one region, one model and a small number of runs.

### The bandwidth trade-off

Padding costs bandwidth. A padded response carries about 300 KB for the first three events plus
roughly 10 KB per later event, so the overhead grows with the number of deltas rather than with the
size of the answer. In our experiment, a ~15 KB essay became about 5 MB on the wire. In [2], with
fewer and larger events, 81 KB became 2.8 MB. The client discards the padding, but it still counts
toward Lambda's 200 MB streamed-response limit and the 2 MB/s bandwidth cap that applies after the
first 6 MB [3]. API Gateway itself allows up to 15 minutes of streaming and only throttles after
10 MB [4], so the Lambda limits are the binding ones.

Whether that is acceptable depends on how much a fast first token matters, how many events a response
produces, how large responses are, and what the extra bytes cost the client and the account. For an
interactive chat interface it may be worth it. For long, high-frequency streams, mobile clients or
bulk output, batching deltas into fewer events, or not streaming at all, may be the better choice.

## Lessons learned

**A delay alone does not help.** An earlier attempt spaced the small writes out in time instead of
padding them. Each write was still small, so the output still arrived as one burst. A delay only paces events
once padding is already pushing them out, which is why `PacedSseEmitter` throws
`IllegalArgumentException` when a delay is combined with a writer that has no padding.

**Flush, then close.** In the S3 example, warm invocations ended early with
`curl: (18) transfer closed with 4112195 bytes remaining to read`, while CloudWatch logs were clean.
The handler never closed the output stream. `output.use { }` plus an explicit `output.flush()` before
the close fixed it, and the Bedrock handler uses the same structure.

**`RESPONSE_STREAM` versus `STREAM`.** SAM's valid values are `BUFFERED` and `RESPONSE_STREAM` [6];
there is no `STREAM` value in SAM. Writing `STREAM` — the API Gateway name from [4] — in a SAM template
is not rejected: neither `sam validate` nor `sam validate --lint` flagged it in our tests, and the SAM
translator only enables streaming for the exact string `RESPONSE_STREAM` [7]. Any other value produces
an ordinary buffered integration, so the mistake only shows after a deployment.

**Use a regional endpoint.** This applies to the S3 and Bedrock examples alike. With an
edge-optimized API Gateway endpoint, the response was buffered in our setup and reached the client in
one burst; switching the API to `EndpointConfiguration: Type: REGIONAL` restored progressive delivery,
and every SAM template in the repository uses it. AWS documents response streaming for all REST API endpoint
types, but with different idle timeouts: 5 minutes for regional endpoints and 30 seconds for
edge-optimized ones [4], which matters when a model pauses before producing output.

**The client buffers too.** Without `-N` (`--no-buffer`), curl holds the output, and a correctly
streaming endpoint looks buffered; the API Gateway troubleshooting guide also recommends
`--no-buffer` [5]. Every later stage of a shell pipeline can buffer as well, which is why the demo uses
`sed -u` and `fflush()` in `awk`.

## Limitations

- **Bandwidth.** The padding overhead described above, which also counts toward Lambda's streaming
  limits [3].
- **Multi-line deltas.** `PaddedSseWriter` writes each delta as a single `data:` line. A delta that
  contains a newline is not split into several `data:` lines, so an SSE client drops the text after
  the newline. `PaddedSseWriterRoundTripTest` documents this. Deltas with newlines need splitting
  before they are written.
- **API Gateway REST only.** The solution is specific to API Gateway REST APIs with
  `ResponseTransferMode: RESPONSE_STREAM` in SAM [6] (`STREAM` in API Gateway [4]), and does not
  cover Lambda Function URLs. The metadata
  prelude written by `streaming-core` is parsed by API Gateway's integration layer; a Function URL has
  no such layer, and with the managed Java runtime the same handler returned HTTP 502 behind a
  Function URL in our tests. For Function URLs, AWS points to a custom runtime or the Lambda Web
  Adapter [3].
- **Undocumented behaviour.** The padding approach depends on buffering behaviour that AWS does not
  document, and it was measured in one region with one model.

## Conclusion

When I started on the Bedrock example, I assumed the approach from the S3 example would work in the same way: write each chunk, flush, and let API Gateway stream it to the client. However, I discovered that Bedrock streaming in smaller chunks caused the same handler to deliver the
whole answer in one burst, because each write was too small to be forwarded. Getting progressive
delivery took one addition on top of the S3 setup: padding each event with SSE comment lines, the
approach described in [2]. The rest of that setup still applies, including the regional API Gateway
endpoint, which both examples require. The padding costs a significant amount of bandwidth, so it is
a deliberate trade-off rather than a default.

I packaged the padding in a new `streaming-sse` module on top of `streaming-core`, so Kotlin and Java
projects can use it without reimplementing it. Both libraries are on Maven Central, and the
[`streaming-bedrock-example`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/tree/v2.4.1/streaming-bedrock-example)
module is a complete, deployable example — its
[Quick Start](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/tree/v2.4.1/streaming-bedrock-example#quick-start)
lets you try it in your own account. Code links in this article point to the `v2.4.1` release tag,
the version published to Maven Central, so they keep showing the code described here.

## References

1. E. van Engelen-Maslova, "AWS Lambda Response Streaming for Kotlin and Java," AWS Builder Center. https://builder.aws.com/content/3KQYoLu7XiQSn6r13bPEE8jeKTj/aws-lambda-response-streaming-for-kotlin-and-java
2. "The Curious Case of SSE Streaming Agent Responses on AWS Lambda," AWS Builder Center. https://builder.aws.com/content/39iqpehwjnn2TuNmGnHAnsMcHmP/the-curious-case-of-sse-streaming-agent-responses-on-aws-lambda
3. "Response streaming for Lambda Event Functions," AWS Lambda Developer Guide. https://docs.aws.amazon.com/lambda/latest/dg/configuration-response-streaming.html
4. "Stream the integration response for your proxy integrations in API Gateway," Amazon API Gateway Developer Guide. https://docs.aws.amazon.com/apigateway/latest/developerguide/response-transfer-mode.html
5. "Troubleshoot issues with response streaming in API Gateway," Amazon API Gateway Developer Guide. https://docs.aws.amazon.com/apigateway/latest/developerguide/response-streaming-troubleshoot.html
6. "Api" event source, `ResponseTransferMode`, AWS Serverless Application Model Developer Guide. https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/sam-property-function-api.html
7. AWS SAM translator source, `samtranslator/swagger/swagger.py` (sets `responseTransferMode: STREAM`) and `samtranslator/model/eventsources/push.py` (`/response-streaming-invocations` URI). https://github.com/aws/serverless-application-model/blob/3dfbd2b29a82161c47f6f956091db0a0f3608bb1/samtranslator/swagger/swagger.py
