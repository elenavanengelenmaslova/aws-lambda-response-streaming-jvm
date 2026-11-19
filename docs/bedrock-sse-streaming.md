# Streaming Amazon Bedrock tokens from a Kotlin Lambda as Server-Sent Events

### Progressive token delivery through API Gateway REST, without a custom runtime or Lambda layer

In the previous article, "AWS Lambda Response Streaming for Kotlin and Java" [1], I introduced
`aws-lambda-streaming-core`, a small JVM library that writes the API Gateway streaming response
format from a Lambda `RequestStreamHandler`. It works well for large payloads: a 12 MB S3 object
reaches the client in full, past the 6 MB buffered Lambda response limit [2] and API Gateway's 10 MB
buffered limit [3].

Amazon Bedrock is a different shape of problem. The model produces its answer a few characters at a
time, and the point of streaming it is that the first words appear while the rest is still being
generated. With `streaming-core` alone, that does not happen: Bedrock streams its deltas, the handler
writes and flushes each one, and the client receives the whole answer in a single burst at the end.

This article explains why, and what the new `streaming-sse` module does about it.

## Demo and architecture

The deployed endpoint, called with `curl`. Words appear a few at a time while the model is still
generating.

![Animated terminal demo: curl streaming Amazon Bedrock Nova Lite tokens from the Kotlin Lambda as Server-Sent Events, with text appearing progressively a few tokens at a time rather than in a single burst.](./bedrock-streaming-demo.gif)

![Architecture diagram for the Bedrock SSE streaming example: a client calls an API Gateway REST endpoint configured for RESPONSE_STREAM, which invokes a Kotlin Lambda RequestStreamHandler; the Lambda calls Amazon Bedrock InvokeModelWithResponseStream and relays each delta back to the client as Server-Sent Events.](./SolutionDesign-Bedrock.png)

The client sends `POST /stream` with `{"prompt":"...","maxTokens":N}`. API Gateway invokes the Lambda
in streaming mode, the handler calls Bedrock Nova Lite with `InvokeModelWithResponseStream`, and each
text delta goes back to the client as an SSE event.

## How the managed JVM runtime sends a response

The `OutputStream` that a `RequestStreamHandler` receives is a `java.io.ByteArrayOutputStream`. The
Java runtime interface client creates it, hands it to `handleRequest`, and sends its contents to the
Lambda Runtime API after the handler returns [6]. There is no connection behind it, so `flush()` has
nothing to flush to:

```
Handler writes:  [ chunk 1 ][ chunk 2 ][ chunk 3 ]   → all into one in-memory buffer
Handler returns: ────────────────────────────────┤
Client receives:                                 [ chunk 1 ][ chunk 2 ][ chunk 3 ]
```

This is not a size threshold or a buffer that can be filled. It applies equally to a 1 KB write and a
15 MB one, and it applies to the response headers as well — they reach API Gateway when the handler
returns, not when they are written. Padding each Server-Sent Event with SSE comment lines, the
approach described in [5] for a Python stack behind the Lambda Web Adapter, does not change it on the
managed JVM runtime: it makes the response larger, so the download that follows takes longer, which
is not the same as streaming it. AWS documents managed-runtime response streaming for Node.js only,
and points other languages at a custom runtime or the Lambda Web Adapter [2, 7].

That leaves two quite different situations.

**Large payloads are fine.** The integration is a genuinely streaming one, which is what lifts the
6 MB and 10 MB buffered limits, and API Gateway forwards the body to the client progressively as it
receives it. For a file download the client's first byte arrives as soon as the transfer starts.
`streaming-core` is the right tool, and nothing in the previous article changes. What it costs is
memory: the whole response exists in the function's heap before any of it moves.

**A token trickle is not.** Here the whole value is bytes leaving while the handler is still working,
and that is exactly what the buffered stream cannot do.

## How `streaming-sse` makes it stream

The Runtime API, which a custom runtime talks to, accepts an invocation response as a **chunked HTTP
request**. It is reachable from handler code at `$AWS_LAMBDA_RUNTIME_API` inside the execution
environment. `StreamingResponse` posts the response there itself and gives the handler that live
stream instead of the buffered one.

Three settings on that request are what make it stream:

```kotlin
setChunkedStreamingMode(0)                                       // chunked transfer encoding
setRequestProperty("Lambda-Runtime-Function-Response-Mode", "streaming")
setRequestProperty("Content-Type", "application/vnd.awslambda.http-integration-response")
```

The content type is the same marker that Node's `awslambda.HttpResponseStream.from()` applies through
`setContentType`: it tells the service the body starts with the response metadata rather than being
the response itself. So the wire format does not change. `streaming-core`'s `ResponseWriter` writes the same metadata, the same eight-byte
delimiter and the same body [1]. Only the destination changes, from a heap buffer to a socket.

One thing then needs handling. AWS's invocation loop does not know the invocation has been answered,
so after the handler returns it sends its own response for the same request, that send fails, and the
runtime process exits — every invocation ends in `Runtime.ExitError`, even though the client received
a correct response.

The module ships a small **Java agent** for this. `premain` runs before the runtime's `main`, and it
patches one method so the loop skips its send when the handler has already answered:

```java
// LambdaRuntimeApiClientImpl
void reportInvocationSuccess(String requestId, byte[] response, String xrayHeader) {
    if (StreamingState.alreadyAnswered()) return;   // ← injected
    …
}
```

Everything else is left as it is: fetching the next invocation, error reporting, logging, and the
CRaC hooks that SnapStart priming depends on. Replacing the whole invocation loop also works, but it
stops those hooks from firing, so priming quietly stops running.

The agent fails safe. If it cannot load, or the runtime method has changed, nothing is patched and the
function keeps working on the normal buffered path. `StreamingAgent.isPatched()` reports which state
you are in, and `StreamingResponse` refuses to stream when the patch is absent, so a runtime change
surfaces as one clear error instead of a failed invocation per request.

## Using the library

`streaming-sse` declares `streaming-core` as an `api` dependency, so one line brings in both:

```kotlin
implementation("nl.vintik:aws-lambda-streaming-sse:3.0.0")
```

Make the fat jar double as the agent, and package it as a zip. `Premain-Class` on the manifest is
what lets the function's own jar act as the agent, so there is no second artifact. `-javaagent`
needs a real jar file, and a `CodeUri` pointing at a bare jar is extracted loose into `/var/task`,
leaving nothing to point at, so the package is a zip with the jar under `lib/` — the standard Java
Lambda layout:

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

The jar name is whatever you set as `archiveFileName`. Lambda unpacks the package into `/var/task`,
so `into("lib")` puts the jar at `/var/task/lib/my-function.jar`, which is the path the
`-javaagent` flag below needs. Those two have to agree:

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
      # Must be exactly RESPONSE_STREAM [4]. Any other value deploys a buffered integration
      # without an error, and nothing streams.
      ResponseTransferMode: RESPONSE_STREAM
```

That is the whole deployment change: one zip task and one flag. The runtime stays `java25`, and
SnapStart, CRaC priming and warm reuse are unaffected. Use a regional API Gateway endpoint — with an
edge-optimized one the response was buffered in my setup, and the idle timeout is 30 seconds rather
than 5 minutes [3].

In the handler, `send` opens the request, writes the metadata, runs your body with the live stream,
and completes the request:

```kotlin
val metadata = ResponseMetadata(
    statusCode = 200,
    headers = mapOf(
        "Content-Type" to "text/event-stream; charset=utf-8",
        "Cache-Control" to "no-cache",
    ),
)

streaming.send(context.awsRequestId, metadata) { live ->
    val sse = SseWriter()
    source.stream(request) { delta -> sse.writeEvent(live, delta) }
    sse.writeEnd(live)
}
```

`SseWriter` handles the event framing: one `data:` line per line of text, a blank line between
events, and a terminal `event: end` / `data: [DONE]` so a client can tell a finished stream from a
dropped connection. The `OutputStream` the runtime passed to the handler is left untouched.

The head-before-stream rule from [1] still applies, and matters more here. The status is committed as
soon as the metadata and delimiter reach the wire, so validate before calling `send`. A Bedrock
failure *before* the first delta — throttling, or missing model access — can still be returned as
HTTP 502 on the handler's buffered stream. After the first delta, a failure can only truncate the
body.

## Limitations

The standard response-streaming limitations [2] still apply, and this module adds three of its own.

- **Undocumented behaviour.** Sending the invocation response from handler code, and the method the
  agent patches, are not part of any AWS contract, and a runtime update can change either. The
  failure mode is that streaming becomes unavailable rather than that the function breaks, but it is
  worth pinning the runtime version and keeping a post-deploy check.
- **You are patching the runtime.** One method, fail-safe, with everything else untouched — but it is
  still instrumentation of AWS internals, and a deliberate choice rather than a default.
- **API Gateway REST only.** `ResponseTransferMode: RESPONSE_STREAM` in SAM [4], `STREAM` at the API
  Gateway integration level [3]. HTTP APIs and Lambda Function URLs are not supported.
- **Standard Lambda only.** Not tested on Lambda Managed Instances.
- **Billing is unchanged.** Streaming does not shorten the invocation. You are billed for the whole
  handler duration, including time spent waiting for the model.
- **The client buffers too.** Without `-N` (`--no-buffer`), curl holds its output and a correctly
  streaming endpoint looks buffered; the API Gateway troubleshooting guide recommends the same flag
  [8]. Later stages of a shell pipeline buffer as well.

## The Bedrock example

[`streaming-bedrock-example`](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/tree/v3.0.0/streaming-bedrock-example)
is a complete, deployable endpoint built on the module: `PromptResolver` parses and clamps the
request, `BedrockSource` calls Nova Lite with `InvokeModelWithResponseStream`, and
`BedrockStreamHandler` relays each delta as an SSE event. It ships with SnapStart and CRaC priming,
least-privilege IAM scoped to one inference profile, API-key auth, and the SAM template shown above.

Its
[Quick Start](https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm/tree/v3.0.0/streaming-bedrock-example#quick-start)
covers building, testing, deploying and resolving the endpoint URL and API key, so you can run it in
your own account. The repository also includes a post-deploy check that invokes paid Bedrock only
when `BEDROCK_TEST_ENABLED=true`. It asserts the things that distinguish streaming from a fast
download: curl's `%{time_starttransfer}` as a fraction of the total time, tokens arriving in several
distinct time windows, a response no larger than the answer itself, and the terminal `[DONE]` event.

## Conclusion

`aws-lambda-streaming-core` does what the previous article described. For large objects — an S3
download, a CSV export, a generated report — it writes the API Gateway streaming format correctly and
carries payloads well past the 6 MB Lambda and 10 MB API Gateway buffered limits, with the body
transferred in bounded chunks rather than assembled as a `String`.

What it cannot do on the managed JVM runtime is send anything before the handler returns, because the
`OutputStream` it writes to is an in-memory buffer. For a large download that barely matters. For
Server-Sent Events it is the whole point, so progressive token delivery needs the new
`aws-lambda-streaming-sse` module, which sends the response to the Lambda Runtime API as it is
produced and keeps AWS's invocation loop from sending a second one.

Both libraries are on Maven Central, and the Kotlin Bedrock example is on GitHub. Code links point to
the `v3.0.0` release tag, the version published to Maven Central, so they keep showing the code
described here.

## References

1. E. van Engelen-Maslova, "AWS Lambda Response Streaming for Kotlin and Java," AWS Builder Center. https://builder.aws.com/content/3KQYoLu7XiQSn6r13bPEE8jeKTj/aws-lambda-response-streaming-for-kotlin-and-java
2. "Response streaming for Lambda Event Functions," AWS Lambda Developer Guide. https://docs.aws.amazon.com/lambda/latest/dg/configuration-response-streaming.html
3. "Stream the integration response for your proxy integrations in API Gateway," Amazon API Gateway Developer Guide. https://docs.aws.amazon.com/apigateway/latest/developerguide/response-transfer-mode.html
4. "Api" event source, `ResponseTransferMode`, AWS Serverless Application Model Developer Guide. https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/sam-property-function-api.html
5. "The Curious Case of SSE Streaming Agent Responses on AWS Lambda," AWS Builder Center. https://builder.aws.com/content/39iqpehwjnn2TuNmGnHAnsMcHmP/the-curious-case-of-sse-streaming-agent-responses-on-aws-lambda
6. AWS Lambda Java runtime interface client, `EventHandlerLoader.wrapRequestStreamHandler` (collects handler output in a `ThreadLocal<ByteArrayOutputStream>`). https://github.com/aws/aws-lambda-java-libs/blob/main/aws-lambda-java-runtime-interface-client/src/main/java/com/amazonaws/services/lambda/runtime/api/client/EventHandlerLoader.java
7. "Response streaming support for java lambda," aws/aws-lambda-java-libs issue #476. https://github.com/aws/aws-lambda-java-libs/issues/476
8. "Troubleshoot issues with response streaming in API Gateway," Amazon API Gateway Developer Guide. https://docs.aws.amazon.com/apigateway/latest/developerguide/response-streaming-troubleshoot.html
