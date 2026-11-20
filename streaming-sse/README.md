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

Padding each Server-Sent Event with SSE comment lines, as described in
["The Curious Case of SSE Streaming Agent Responses on AWS Lambda"](https://builder.aws.com/content/39iqpehwjnn2TuNmGnHAnsMcHmP/the-curious-case-of-sse-streaming-agent-responses-on-aws-lambda)
for a Python stack behind the Lambda Web Adapter, does not help here: it only makes the response
larger, so the download that follows takes longer.

`StreamingResponse` posts the response itself, incrementally, as a chunked request to the Lambda
Runtime API, and hands you that live stream instead. Against Amazon Bedrock Nova Lite through an API
Gateway REST endpoint, the first token reaches the client at about 3% of the total response time.

## Setup: three steps

### 1. Depend on the module

```kotlin
dependencies {
    implementation("nl.vintik:aws-lambda-streaming-sse:<version>")
}
```

### 2. Make your fat jar an agent, and package it as a zip

The agent is what stops AWS's invocation loop from sending a second response for an invocation you
have already answered. `-javaagent` needs a real **jar file** on disk, and a `CodeUri` pointing at a
bare jar is extracted loose into `/var/task`, leaving no jar to point at — so the deployment package
must be a zip carrying the jar under `lib/`. That is the standard Java Lambda zip layout.

`Premain-Class` on the jar's manifest is what makes your own fat jar usable as the agent, so there is
no second artifact to build.

```kotlin
tasks.shadowJar {
    archiveFileName.set("my-function.jar")        // ← name it whatever you like
    manifest {
        attributes("Premain-Class" to "nl.vintik.lambda.streaming.sse.StreamingAgent")
    }
}

tasks.register<Zip>("lambdaZip") {
    archiveFileName.set("my-function.zip")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))   // ← must match CodeUri
    from(tasks.shadowJar) { into("lib") }         // ← puts the jar at lib/ inside the zip
}
```

A `Zip` task writes to `build/distributions` unless you say otherwise, so set
`destinationDirectory` to whatever path the template's `CodeUri` uses, or point `CodeUri` at
`build/distributions`. The two have to name the same file.

`my-function.jar` is not a convention — it is whatever you set as the shadow jar's
`archiveFileName`. Lambda unpacks the deployment package into `/var/task`, so the layout above gives
you this, and that is where the `-javaagent` path in step 3 comes from:

```
build/dist/my-function.zip          →  CodeUri
└── lib/my-function.jar             →  /var/task/lib/my-function.jar   (the -javaagent path)
```

So the jar name appears in two places that must agree: `archiveFileName` in the build, and the
`-javaagent` flag in the template. Get that wrong and the failure is loud, not silent: a
`-javaagent` path that does not resolve to a jar with a `Premain-Class` aborts JVM startup
(`Error opening zip file or JAR manifest missing`, then `agent library failed Agent_OnLoad`), so
the function fails to initialise on every invocation. Fix the path so it matches the jar name.

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

The `output` the runtime handed you is deliberately left untouched. Note that this handler lets
anything `send` raises propagate, which is the right default: if the agent is missing, the
invocation fails loudly with the message above instead of quietly serving buffered responses. Wrap
the `send` call yourself if you would rather degrade to a buffered reply.

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

**It fails safe, but it does not fail over.** If the jar cannot be appended to the class loader
search, or the runtime class has changed, the agent patches nothing and leaves AWS's loop intact, so
the buffered path still works for handlers that use it. A handler built on `StreamingResponse` is a
different matter: `send` checks `StreamingAgent.isPatched()` and throws
`IllegalStateException` before opening a channel, so every invocation fails with that message until
you act. There is no automatic fallback to a buffered response — if you want one, catch the
exception and write your response to the `OutputStream` the runtime gave you, which is still
untouched at that point.

Recovery is to restore the agent, not to wait it out: check `JAVA_TOOL_OPTIONS` carries
`-javaagent` and that `CodeUri` is the zip rather than a bare jar, look for the
`[streaming-sse-agent]` lines in the function's logs, and roll back to the previous published
version if a runtime update broke the patch. Assert `isPatched()` in a post-deploy check so this
surfaces at deploy time rather than on live traffic.

## Limitations
- **API Gateway REST only.** HTTP APIs and Lambda Function URLs are not supported.
- **Standard Lambda only.** Not tested on Lambda Managed Instances.
- **Undocumented behaviour.** Sending the invocation response from handler code, and the runtime
  method the agent patches, are not part of any AWS contract, so a runtime update can change them.
  The agent fails safe, but pin your runtime version and keep a post-deploy check.
- **Billing is unchanged.** Streaming does not shorten the invocation; you are billed for the whole
  handler duration, pauses included.

## Licence

MIT. See [LICENSE](../LICENSE).
