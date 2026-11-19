# Streaming Amazon Bedrock tokens from a Kotlin Lambda as Server-Sent Events

### Progressive token delivery through API Gateway REST, without a custom runtime or a Lambda layer

In my previous article I introduced `aws-lambda-streaming-core`, a small JVM library that writes the
API Gateway streaming response format from a Lambda `RequestStreamHandler` [1]. It takes care of the
response framing and the bounded byte copy, so a Kotlin or Java function can return a body well past
the 6 MB buffered response limit [2].

Then I pointed it at Amazon Bedrock. Nova Lite produces its answer a few characters at a time, and
the reason to put that behind an SSE endpoint is that the first words should appear while the rest is
still being generated. My handler received each delta, wrote it as an SSE event and flushed. The
client received the complete answer in a single burst when the invocation finished.

This article covers why that happens, what I measured, and what I had to build to get tokens
delivered as they are produced.

## The endpoint

![Animated terminal demo: curl streaming Amazon Bedrock Nova Lite tokens from the Kotlin Lambda as Server-Sent Events, with text appearing progressively a few tokens at a time rather than in a single burst.](./bedrock-streaming-demo.gif)

![Architecture diagram for the Bedrock SSE streaming example: a client calls an API Gateway REST endpoint configured for RESPONSE_STREAM, which invokes a Kotlin Lambda RequestStreamHandler; the Lambda calls Amazon Bedrock InvokeModelWithResponseStream and relays each delta back to the client as Server-Sent Events.](./SolutionDesign-Bedrock.png)

The client sends `POST /stream` with `{"prompt":"...","maxTokens":N}`. API Gateway invokes the Lambda
with response streaming enabled, the handler calls Nova Lite through
`InvokeModelWithResponseStream`, and each text delta goes back as an SSE event.

## What I tried first

I started with the suspects on my own side of the wire. A `flush()` after every event changed
nothing, and neither did spacing the tokens out with a small delay in case something was coalescing
small writes.

Then I found "The Curious Case of SSE Streaming Agent Responses on AWS Lambda" on AWS Builder
Center [5], which describes exactly my symptom: silence, then every event at once. Its diagnosis is
an undocumented buffer of roughly 100 KB, and its fix is to pad each SSE event with a large comment
block to push that buffer past its threshold. That application is Python and FastAPI behind the
Lambda Web Adapter, where the workaround worked according to that article[5].

On my managed JVM Lambda it did not. I built a tiered version of it, and the demo looked convincing
enough that I believed it until I measured the client's real time to first byte. With roughly 10 MB
of padding, curl reported `ttfb_s=8.68` against `total_s=12.48`. The first byte arrived after 70% of
the total time, so the client had waited out the entire generation and then downloaded 18 MB. The
tokens did appear to trickle in, but that was the download of an inflated body.

My own post-deploy test had been passing throughout, because it started its clock on the first line it
read, making its TTFB near zero by construction.

## What the managed JVM runtime actually does

The `OutputStream` a `RequestStreamHandler` receives is a `java.io.ByteArrayOutputStream`. I log
`output::class.java.name` on every invocation, so that part is observed rather than inferred, and the
mechanism is visible in AWS's runtime interface client: `EventHandlerLoader.wrapRequestStreamHandler`
keeps a `ThreadLocal<ByteArrayOutputStream>`, resets it per invocation, hands it to `handleRequest`,
and returns it as the invocation result [6]. Nothing in that path can transmit while the handler is
still inside `handleRequest`, and a maintainer reply on the same repository confirms response
streaming is a Node.js-only feature of the managed runtimes [7].

![Sequence diagram of the buffered response path: the client sends a request, the Lambda Java runtime invokes the handler, the handler writes chunk 1, 2 and 3 into the runtime's in-memory buffer while the client has received nothing, and only after the handler returns does the runtime send all three chunks to the client as one response.](./buffered-response.png)

To measure it rather than infer it, I joined handler timing lines to an API Gateway access log
carrying `$context.integration.timeToAllHeaders`, `timeToFirstContent` and `latency`, matched per
invocation through `$context.integration.requestId`. Three results came out of that.

**Headers and body always arrive together.** Across 15 requests, warm and cold, `timeToAllHeaders`
and `timeToFirstContent` were identical to the millisecond, so the metadata the handler writes first
does not travel ahead of the body.

**First content lands just after the handler returns.** One Bedrock request wrote its metadata at
422 ms and closed at 8787 ms; API Gateway saw first content at 8816 ms. An S3 request wrote all
12.58 MB inside the first second and produced nothing until 1102 ms, again just after close. Across
the set the gap was 26 to 204 ms after the handler returned.

**There is no threshold to fill.** On a throwaway probe I wrote 15 MB at 2 ms, then slept ten seconds
inside the handler: first content at 10069 ms. Another request had 11.4 MB in the stream at 1347 ms
and showed nothing until 8599 ms. A buffer releasing at 100 KB, 1 MB, 6 MB or 10 MB would have been
obvious in those runs.

That is why padding cannot work here: the volume written has no bearing on when the response is
submitted, so bigger events only lengthen the eventual download.

For a large file download, a response that starts after the handler returns is fine. For Server-Sent
Events it defeats the purpose, so the bytes have to take a path that does not go through that buffer.

## Posting the response myself

The Runtime API that a custom runtime talks to accepts an invocation response as a chunked HTTP
request, and it is reachable from handler code at `$AWS_LAMBDA_RUNTIME_API` inside the execution
environment. Nothing stops a handler from posting its own response there instead of returning bytes
through the buffer.

That is what `StreamingResponse` in the new `streaming-sse` module does. It opens the POST, writes
the metadata, and hands the handler the live stream:

```kotlin
val url = URI("http://$host/2018-06-01/runtime/invocation/$requestId/response").toURL()
(url.openConnection() as HttpURLConnection).apply {
    requestMethod = "POST"
    doOutput = true
    setChunkedStreamingMode(0)                                    // chunked transfer encoding
    setRequestProperty("Lambda-Runtime-Function-Response-Mode", "streaming")
    setRequestProperty("Content-Type", "application/vnd.awslambda.http-integration-response")
}
```

That content type is the same marker Node's `awslambda.HttpResponseStream.from()` sets through
`setContentType`, telling the service the body opens with the response metadata rather than being the
response itself. So the wire format is unchanged, and `streaming-core`'s `ResponseWriter` still
writes the metadata and the eight-byte delimiter exactly as before [1]. Only the destination changes,
from a heap buffer to a socket. Chunked mode plus a `flush()` per write is what emits each small
event as its own chunk.

## The second response, and why there is an agent

The first time this worked, every invocation still ended in `Runtime.ExitError`. The client had its
correct, progressively delivered response, and the function was reported as failed.

AWS's invocation loop does not know the invocation has been answered, so when the handler returns it
posts its own response for the same request id. That post fails, and it takes the runtime process
down with it. `System.exit(0)` and `Runtime.halt(0)` from the handler do not avoid it, which I tried
before accepting that the loop itself has to stop posting.

Three ways to do that, two of which I rejected:

- **Shadowing the runtime class from the function jar.** Not possible: `/var/task` is not on the JVM
  classpath, and customer code loads through a child class loader.
- **Replacing the invocation loop with `AWS_LAMBDA_EXEC_WRAPPER`.** This worked, and I nearly shipped
  it. Then I checked the CRaC hooks and found `beforeCheckpoint` was no longer firing: SnapStart
  priming had stopped running, silently. It also needs an executable wrapper script, which a library
  on Maven Central cannot deliver.
- **A Java agent.** A dependency plus one flag, and AWS's loop stays AWS's loop.

`streaming-sse` uses the agent. `premain` runs before the runtime's `main`, appends the function jar
to the system class loader search, and registers a `ClassFileTransformer` that prepends one guard to
a single method:

```java
// com.amazonaws.services.lambda.runtime.api.client.runtimeapi.LambdaRuntimeApiClientImpl
void reportInvocationSuccess(String requestId, byte[] response, String xrayHeader) {
    if (StreamingState.alreadyAnswered()) return;   // ← injected
    ...
}
```

The method is `reportInvocationSuccess`, which I had to read off the deployed runtime interface
client; the published sources point at `postInvocationResponse` instead. Everything else in the loop
stays byte-identical, including the SnapStart restore hook and the CRaC coordination priming depends
on. `StreamingState` is a `ThreadLocal` flag, because the handler and the runtime's post run on the
same thread for an invocation, and a reused environment must not carry an answered flag into the next
request.

To be clear about what this is: instrumenting an internal class of the managed Java runtime is not a
documented AWS feature, and nothing about that method is covered by an AWS contract. A runtime update
can rename or reshape it. So the failure behaviour runs in two directions. The transformer catches
everything and returns the class untouched on any failure, which leaves the function working on AWS's
normal buffered path. And `StreamingAgent.isPatched()` reports whether the patch landed, which
`StreamingResponse` checks before it streams anything, so a runtime change surfaces as one clear
error instead of a failed invocation per request. Unit tests cover the generated bytecode against a
synthetic class, including that the JVM verifier accepts it and that a missing target method fails
rather than reporting success.

## Using it

`streaming-sse` declares `streaming-core` as an `api` dependency, so one line brings in both:

```kotlin
implementation("nl.vintik:aws-lambda-streaming-sse:3.0.0")
```

Two packaging details follow from `-javaagent`. It needs a real jar file, and a bare-jar `CodeUri` is
extracted loose into `/var/task`, leaving nothing to point at, so the package is a zip with the fat
jar under `lib/`. `Premain-Class` on the manifest makes the function's own jar the agent, so there is
no second artifact:

```kotlin
tasks.shadowJar {
    archiveFileName.set("my-function.jar")
    manifest { attributes("Premain-Class" to "nl.vintik.lambda.streaming.sse.StreamingAgent") }
}

tasks.register<Zip>("lambdaZip") {
    archiveFileName.set("my-function.zip")
    from(tasks.shadowJar) { into("lib") }
}
```

Lambda unpacks that into `/var/task`, so the jar lands at `/var/task/lib/my-function.jar`, which is
the path the flag needs. The two have to agree:

```yaml
Runtime: java25                    # unchanged
CodeUri: build/dist/my-function.zip
AutoPublishAlias: live             # SnapStart keeps working
SnapStart:
  ApplyOn: PublishedVersions
Environment:
  Variables:
    JAVA_TOOL_OPTIONS: >-
      -XX:+TieredCompilation -XX:TieredStopAtLevel=1
      -javaagent:/var/task/lib/my-function.jar
Events:
  StreamPost:
    Type: Api
    Properties:
      Path: /stream
      Method: post
      # Must be exactly RESPONSE_STREAM [4]. Any other value deploys a buffered
      # integration with no error, and nothing streams.
      ResponseTransferMode: RESPONSE_STREAM
```

That is the whole deployment change: one zip task and one flag. The runtime stays `java25`, and
SnapStart, CRaC priming and warm reuse are unaffected. Use a regional endpoint; with an
edge-optimized one the response came back buffered in my setup [3].

In the handler, `send` opens the request, writes the metadata, runs your body against the live
stream, and completes the request:

```kotlin
val metadata = ResponseMetadata(
    statusCode = 200,
    headers = mapOf(
        "Content-Type" to "text/event-stream; charset=utf-8",
        "Cache-Control" to "no-cache",
        "Transfer-Encoding" to "chunked",
    ),
)

streaming.send(context.awsRequestId, metadata) { live ->
    val sse = SseWriter()
    source.stream(request) { delta -> sse.writeEvent(live, delta) }
    sse.writeEnd(live)
}
```

`SseWriter` handles the framing: one `data:` line per line of text, a blank line between events, and
a terminal `event: end` carrying `[DONE]` so a client can tell a finished stream from a dropped
connection. It flushes after each event, which on a live stream is what releases it. The
`OutputStream` the runtime handed the handler is left alone.

The head-before-stream rule from the first article matters more here [1]. The status is committed as
soon as the metadata and delimiter reach the wire, so validate first. In the Bedrock example a bad
request and a model failure before the first delta are both answered buffered, on the runtime's own
stream, as 400 and 502; the 200 is committed only when the first delta arrives, and after that a
failure can truncate the body and nothing else.

## What the deployed endpoint measures

The repository's post-deploy check runs against the deployed stack, and only invokes paid Bedrock when
`BEDROCK_TEST_ENABLED=true`. It asserts four things, each one guarding a failure I hit: curl's
`%{time_starttransfer}` has to stay under 35% of `%{time_total}`, which on the buffered path is around
1.0; token arrivals have to fall into at least four distinct 500 ms buckets; bytes on the wire have to
stay within 4x the answer's own character count, against roughly 1,280x for the padded version; and
the terminal `[DONE]` event has to arrive, so a truncated response is not read as a success.

From the deploy pipeline run of the current version, two sequential requests at `maxTokens=5000`:

```
tokens=676 answer_chars=7649 spread_ms=5869 buckets=12 ttfb_s=0.316920 total_s=6.456277 bytes=13012
tokens=806 answer_chars=9233 spread_ms=7561 buckets=16 ttfb_s=0.289055 total_s=8.177257 bytes=15630
→ OK ttfb=0.289s (3.5% of 8.177s) buckets=16 wire=15630B inflation=1.7x
```

First byte at 0.289 s of an 8.18 s response, tokens spread across 16 distinct windows, and 15.6 KB on
the wire for a 9.2 KB answer.

The server-side half comes from the instrumented probe I used during the investigation, running the
same mechanism: API Gateway had the response headers at 31 ms, before any token existed, and content
flowed from around 307 ms until the generation ended at 9.3 s, in events of 9 to 39 bytes.
`Max Memory Used` stayed at 145 MB and did not track the response size. I have not repeated that
server-side join on the example stack, so read those as the mechanism's numbers.

## Limitations

The standard response-streaming limitations still apply [2], and this approach adds its own.

- **API Gateway REST only.** `ResponseTransferMode: RESPONSE_STREAM` in SAM [4], `STREAM` at the
  integration level [3]. HTTP APIs and Lambda Function URLs are not supported.
- **Standard Lambda only.** Not tested on Lambda Managed Instances.
- **Billing is unchanged.** You are billed for the whole handler duration, including the time spent
  waiting for the model.
- **The client can buffer too.** Without `-N`, curl holds its output and a correctly streaming endpoint
  looks buffered; the troubleshooting guide recommends the same flag [8].

## The Bedrock example

[`streaming-bedrock-example`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/tree/v3.0.0/streaming-bedrock-example)
is the endpoint this article describes, with SnapStart and CRaC priming, IAM scoped to one inference
profile, API-key auth and the SAM template above. Its
[Quick Start](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/tree/v3.0.0/streaming-bedrock-example#quick-start)
covers building, deploying and resolving the endpoint URL and API key, so you can run it in your own
account.

## Conclusion

`aws-lambda-streaming-core` does what I built it for: large objects, an S3 download, a CSV export, a
generated report, framed correctly and carried well past the buffered limits. On the managed JVM
runtime, though, the stream a handler writes to is an in-memory buffer, so none of those bytes leave
while the handler is running. For a download that is a detail. For Server-Sent Events it is the whole
point.

Progressive delivery needed a different mechanism: post the response to the Lambda Runtime API as it
is produced, and stop AWS's invocation loop from posting a second one. That is
`aws-lambda-streaming-sse`, and using it is a dependency plus a flag rather than a layer, a custom
runtime or a wrapper script. It is also an experimental workaround resting on a runtime internal,
tested against a deployed endpoint rather than blessed by AWS, and I would happily retire it for a
managed JVM streaming helper.

Both libraries are on Maven Central and the Kotlin Bedrock example is on GitHub. Code links point at
the `v3.0.0` tag, the version published to Maven Central.

## References

1. E. van Engelen-Maslova, "AWS Lambda Response Streaming for Kotlin and Java," AWS Builder Center. https://builder.aws.com/content/3KQYoLu7XiQSn6r13bPEE8jeKTj/aws-lambda-response-streaming-for-kotlin-and-java
2. "Response streaming for Lambda Event Functions," AWS Lambda Developer Guide. https://docs.aws.amazon.com/lambda/latest/dg/configuration-response-streaming.html
3. "Stream the integration response for your proxy integrations in API Gateway," Amazon API Gateway Developer Guide. https://docs.aws.amazon.com/apigateway/latest/developerguide/response-transfer-mode.html
4. "Api" event source, `ResponseTransferMode`, AWS Serverless Application Model Developer Guide. https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/sam-property-function-api.html
5. "The Curious Case of SSE Streaming Agent Responses on AWS Lambda," AWS Builder Center. https://builder.aws.com/content/39iqpehwjnn2TuNmGnHAnsMcHmP/the-curious-case-of-sse-streaming-agent-responses-on-aws-lambda
6. AWS Lambda Java runtime interface client, `EventHandlerLoader.wrapRequestStreamHandler` (collects handler output in a `ThreadLocal<ByteArrayOutputStream>`). https://github.com/aws/aws-lambda-java-libs/blob/main/aws-lambda-java-runtime-interface-client/src/main/java/com/amazonaws/services/lambda/runtime/api/client/EventHandlerLoader.java
7. "Response streaming support for java lambda," aws/aws-lambda-java-libs issue #476. https://github.com/aws/aws-lambda-java-libs/issues/476
8. "Troubleshoot issues with response streaming in API Gateway," Amazon API Gateway Developer Guide. https://docs.aws.amazon.com/apigateway/latest/developerguide/response-streaming-troubleshoot.html
