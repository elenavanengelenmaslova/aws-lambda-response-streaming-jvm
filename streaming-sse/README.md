# aws-lambda-streaming-sse

Real HTTP response streaming for Kotlin and Java Lambda functions on the **managed** JVM runtime —
Server-Sent Events that arrive token by token, not in one burst at the end.

No Lambda layer, no custom runtime, no wrapper script, no change to your handler signature.
SnapStart, CRaC priming hooks and warm reuse all keep working.

```kotlin
implementation("nl.vintik:aws-lambda-streaming-sse:<version>")
```

## The problem this solves

On the managed JVM runtime the `OutputStream` a `RequestStreamHandler` receives is a
`java.io.ByteArrayOutputStream`. Everything written accumulates in memory and is posted only **after
`handleRequest` returns**, so no byte can reach the client while the handler is still running.
`flush()` changes nothing — there is no connection behind the stream.

Measured on a managed `java25` function: 15 MB written at **2 ms**, handler idle for 10 s, and API
Gateway received the first byte — including the response headers — at **10 069 ms**. The same holds
at every write size, from 1 KB to 15 MB. There is no internal buffer to defeat and no threshold to
cross.

`StreamingResponse` posts the response itself, incrementally, as a chunked request to the Lambda
Runtime API, and hands you that live stream instead.

| | on the wire | first token | as % of total |
|---|---|---|---|
| buffered handler `OutputStream` | 14 KB | 9.3 s (at the end) | 100% |
| SSE padding workaround (now deprecated) | 18 MB | 8.68 s | 70% |
| **`StreamingResponse` + `SseWriter`** | **14 KB** | **0.199 s** | **2%** |

Measured against Amazon Bedrock Nova Lite through an API Gateway REST endpoint; see `docs/log.md`
in the repository for the full method and raw numbers.

## Setup: three steps

### 1. Depend on the module

```kotlin
dependencies {
    implementation("nl.vintik:aws-lambda-streaming-sse:<version>")
}
```

### 2. Make your fat jar an agent, and package it as a zip

The agent is what stops AWS's invocation loop from posting a second response for an invocation you
have already answered. `-javaagent` needs a real **jar file** on disk, and a `CodeUri` pointing at a
bare jar is extracted loose into `/var/task`, leaving no jar to point at — so the deployment package
must be a zip carrying the jar under `lib/`. That is the standard Java Lambda zip layout.

```kotlin
tasks.shadowJar {
    archiveFileName.set("my-function.jar")
    manifest {
        attributes("Premain-Class" to "nl.vintik.lambda.streaming.sse.StreamingAgent")
    }
}

tasks.register<Zip>("lambdaZip") {
    archiveFileName.set("my-function.zip")
    from(tasks.shadowJar) { into("lib") }
}
```

### 3. Point the runtime at the agent

```yaml
Resources:
  MyFunction:
    Type: AWS::Serverless::Function
    Properties:
      Runtime: java25            # unchanged
      CodeUri: build/dist/my-function.zip
      Handler: com.example.MyHandler
      AutoPublishAlias: live     # SnapStart keeps working
      SnapStart:
        ApplyOn: PublishedVersions
      Environment:
        Variables:
          JAVA_TOOL_OPTIONS: >-
            -XX:+TieredCompilation -XX:TieredStopAtLevel=1
            -javaagent:/var/task/lib/my-function.jar
      Events:
        Stream:
          Type: Api
          Properties:
            Path: /stream
            Method: post
            # Must be exactly RESPONSE_STREAM. Any other value silently deploys a buffered
            # integration, and nothing will stream.
            ResponseTransferMode: RESPONSE_STREAM
```

That is the whole deployment change: one zip task and one flag.

## Usage

```kotlin
class ChatHandler : RequestStreamHandler {

    private val streaming = StreamingResponse()

    override fun handleRequest(input: InputStream, output: OutputStream, context: Context) {
        val prompt = parse(input)                      // validate BEFORE committing a status

        val metadata = ResponseMetadata(
            statusCode = 200,
            headers = mapOf(
                "Content-Type" to "text/event-stream; charset=utf-8",
                "Cache-Control" to "no-cache",
            ),
        )

        streaming.send(context.awsRequestId, metadata) { live ->
            val sse = SseWriter()
            model.tokens().forEach { sse.writeEvent(live, it) }
            sse.writeEnd(live)
        }
    }
}
```

The `output` the runtime handed you is deliberately left untouched.

`send` takes the request id as a `String` rather than a `Context`, so this module depends on no AWS
artifact and adds nothing to your classpath beyond `streaming-core`.

### Ordering rule

The status is committed the moment the metadata and the 8-byte delimiter are written, exactly as on
the buffered path. **Validate before calling `send`.** Once the first byte is out, a later failure
can only truncate the body — it cannot turn a 200 into a 502.

## What the agent does, and what it does not

It patches one method of the Lambda runtime interface client:

```java
// LambdaRuntimeApiClientImpl
void reportInvocationSuccess(String requestId, byte[] response, String xrayHeader) {
    if (StreamingState.alreadyAnswered()) return;   // ← injected
    …
}
```

Everything else is left byte-identical, including `nextInvocation`, the SnapStart `restoreNext`
hook, error reporting and the CRaC coordination AWS's loop performs. That restraint is the point: an
earlier approach replaced the whole invocation loop and silently stopped CRaC `beforeCheckpoint`
hooks from firing, so SnapStart priming stopped running with no error anywhere.

**It fails safe.** If the jar cannot be appended to the class loader search, or the runtime class has
changed, nothing is patched and your function keeps working on AWS's normal buffered path.
`StreamingAgent.isPatched()` reports the truth, and `StreamingResponse.send` refuses to stream when
the patch is absent — so a runtime change surfaces as one clear error instead of a failed invocation
per request. Assert `isPatched()` in a post-deploy check if you want that verified on every deploy.

## Honest limitations

- **Undocumented runtime behaviour.** Posting the invocation response from handler code, and the
  method this patches, are not part of any AWS contract. A runtime update can change them. The
  failure mode is "streaming unavailable", not "function broken", but it is a real risk — pin your
  runtime version and keep a post-deploy check.
- **API Gateway REST only** for streaming responses. HTTP APIs do not support it; Function URLs do,
  but the managed JVM runtime rejects the prelude protocol there.
- **Bandwidth caps** apply past the first 10 MB on REST APIs (6 MB on Function URLs).
- **Billing is unchanged.** Streaming does not shorten the invocation; you are billed for the whole
  handler duration, pauses included.
- **Concurrency.** `StreamingState` is per-thread, which keeps concurrent invocations in one
  execution environment independent. Verified for one invocation at a time; untested on Lambda
  Managed Instances.

## Removed in 3.0: the padding workaround

`PaddedSseWriter` and `PacedSseEmitter` are **gone**. They were built on the belief that the runtime
held small writes in a ~100 KB internal buffer until it filled, so each event was inflated with
SSE-comment padding to cross it. There is no such buffer. Padding only made the finished body large
enough that *downloading* it took long enough to look progressive — about **1 280× the bytes** for a
first token roughly **44× later**. It also dropped everything after the first newline in a delta,
which `SseWriter` fixes by emitting one `data:` line per line.

Migrating from 2.x:

```kotlin
// before — padding, writing into the runtime's buffered stream
val sse = PaddedSseWriter(headPadBytes = 102_400, tailPadBytes = 10_240, headPadEvents = 3)
model.tokens().forEach { sse.writeEvent(output, it) }
sse.writeEnd(output)

// after — real streaming, no padding
StreamingResponse().send(context.awsRequestId, metadata) { live ->
    val sse = SseWriter()
    model.tokens().forEach { sse.writeEvent(live, it) }
    sse.writeEnd(live)
}
```

Plus the two deployment lines from [Setup](#setup-three-steps): the zip layout and the
`-javaagent` flag. Without the agent `send` fails fast with an explanatory message rather than
streaming into a response the runtime will then duplicate.

If you want to pace delivery deliberately — to match a reading speed, or a downstream rate limit —
sleep in your own loop between `SseWriter.writeEvent` calls. That is one line and it drags no
padding along. Note the pause is billed Lambda time.

## Licence

MIT. See [LICENSE](../LICENSE).
