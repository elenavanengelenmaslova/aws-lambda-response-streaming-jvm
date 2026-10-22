# streaming-bedrock-example

A complete AWS Lambda deployment that streams **Amazon Bedrock (Nova Lite)** token output to the client using the [`aws-lambda-streaming-core`](../README.md) library — and shows how to make token-by-token delivery actually arrive progressively through **API Gateway REST**.

![Bedrock Nova Lite streaming demo](../docs/bedrock-streaming-demo.gif)

## What It Does

1. Accepts a `POST` with a JSON body `{"prompt": "...", "maxTokens": 2000}` (via API Gateway `/stream`)
2. Resolves and validates the prompt (`PromptResolver`), clamping cost/size knobs to safe bounds
3. Invokes Bedrock Nova Lite with `InvokeModelWithResponseStream` (`BedrockSource`, Kotlin AWS SDK)
4. Relays each text delta to the client as a Server-Sent Event, **padded** so it is delivered immediately
5. Writes the API Gateway streaming protocol via `streaming-core`'s `ResponseWriter`

## The catch: Lambda's response-streaming buffer

Unlike the [S3 example](../streaming-s3-example/), which writes ~1 MB chunks and streams progressively with no tricks, an LLM token relay writes only a few bytes per token. The **AWS Lambda response-streaming runtime holds small writes in an internal buffer** and only forwards them to the client when the buffer fills (**~100 KB**) or the stream ends — there is **no `flush()` that overrides it**. A naive Bedrock relay therefore arrives as a single terminal burst, not a progressive trickle.

This is a property of the Lambda streaming runtime itself — not the JVM, not this library, and not API Gateway. It reproduces across languages and even with Lambda Function URLs. It is documented independently in the AWS Builder Center article [“The Curious Case of SSE Streaming Agent Responses on AWS Lambda”](https://builder.aws.com/content/39iqpehwjnn2TuNmGnHAnsMcHmP/the-curious-case-of-sse-streaming-agent-responses-on-aws-lambda). *(External reference; content not reproduced here.)*

### The workaround: tiered SSE-comment padding

SSE comment lines start with `:` and are silently ignored by compliant clients. `PaddedSseWriter` appends a comment-line padding block to each event. The first events carry enough padding to cross the ~100 KB buffer and break it open; later events carry much less (see the tiers below), so they are not individually large enough to force a flush on their own — instead they ride the stream that the head padding has already got flowing:

```
data: <token text>\n\n
: <padding spaces>\n\n      <- ignored by the client; only there to trip the buffer
```

Padding is **tiered**, matching what is needed in practice:

- **~100 KB on the first 3 events** — breaks the initial buffer so the first tokens appear fast.
- **~10 KB on every event afterwards** — keeps the stream flowing (the runtime re-buffers after the first few events without ongoing tail padding).

**Trade-off:** the padding inflates the response. In our measurements a ~15 KB answer became several MB on the wire (~hundreds of ×). You trade bandwidth for responsiveness. For a chat-style UX where time-to-first-token matters, that is usually worth it; for bulk output it is not. The padding bytes never reach application code — the client's SSE parser (or a `sed` filter) drops them.

## Measured result (deployed endpoint, eu-west-1)

| Configuration | TTFB | Arrival | Verdict |
|---|---|---|---|
| No padding | ≈ total | 1 time bucket | single terminal burst |
| Tiered 100 KB × 3 + 10 KB | ~0 ms | 9 distinct 500 ms buckets over ~4.4 s | **progressive** |

See [`scripts/bedrock-streaming-test.sh`](../scripts/bedrock-streaming-test.sh) for the reproducible post-deploy check.

## Stack

- **Kotlin 2.3.x / Java 25** (Gradle 9, multi-module)
- **AWS SAM** — Lambda + API Gateway REST (RESPONSE_STREAM)
- **SnapStart** + CRaC priming + arm64 + tiered compilation (L1)
- **Amazon Bedrock Nova Lite** via the Kotlin AWS SDK (`aws.sdk.kotlin:bedrockruntime`)

## Quick Start

### Build and Test

```bash
# Unit + property tests (no AWS, no Docker)
./gradlew :streaming-bedrock-example:test

# Coverage report + gate (80%)
./gradlew :streaming-bedrock-example:koverHtmlReport :streaming-bedrock-example:koverVerify

# Validate SAM template
sam validate --template-file deployment/aws/sam-bedrock/template.yaml
```

### Deploy

```bash
cd deployment/aws/sam-bedrock
./build.sh    # builds bedrock-streaming-endpoint.jar via :streaming-bedrock-example:shadowJar
./deploy.sh   # sam build + sam deploy
```

The function's execution role is granted **only** `bedrock:InvokeModelWithResponseStream`, scoped to the Nova Lite inference profile and the regional foundation-model copies it routes to.

### Try It — stream tokens progressively

```bash
# Resolve endpoint + API key (see the root README for the full snippet)
ENDPOINT_URL=$(aws cloudformation describe-stacks --stack-name bedrock-streaming-endpoint \
  --query "Stacks[0].Outputs[?OutputKey=='StreamingEndpointUrl'].OutputValue" --output text)
API_KEY=$(aws apigateway get-api-key --api-key \
  "$(aws cloudformation describe-stacks --stack-name bedrock-streaming-endpoint \
     --query "Stacks[0].Outputs[?OutputKey=='ApiKeyId'].OutputValue" --output text)" \
  --include-value --query 'value' --output text)

# Stream tokens, dropping the padding client-side with a plain sed|awk pipeline:
curl -N -H "x-api-key: $API_KEY" -H "Content-Type: application/json" \
  "${ENDPOINT_URL}stream" -d '{"prompt":"Explain why response streaming matters for serverless.","maxTokens":1000}' \
  | sed -u -n 's/^data: //p' | awk '{printf "%s",$0; fflush()}'
```

`sed -n 's/^data: //p'` keeps only the token text and drops the `:` padding comments; `awk '{printf...}'` joins tokens into flowing prose. Tokens appear progressively as the model generates them.

### Post-deploy progressive-arrival test (opt-in, cost-guarded)

```bash
# Invokes paid Bedrock — opt in explicitly:
BEDROCK_TEST_ENABLED=true AWS_REGION=eu-west-1 ./scripts/bedrock-streaming-test.sh
```

In CI/CD this runs as a matrix leg of the shared deploy → streaming-test pipeline, alongside the
two S3 examples (`.github/workflows/ci-feature-build.yml` and `cd-deploy-on-demand.yml`). Because
it invokes paid Bedrock it is **disabled on every feature push** (the job runs but the script
exits 0 without calling the model). To run the paid test against a fresh deploy, trigger
**CD - Deploy On Demand** with `run-bedrock-test = true`.

## Project Structure

```
streaming-bedrock-example/
├── src/main/kotlin/nl/vintik/lambda/bedrock/
│   ├── PromptRequest.kt          # Typed request (prompt + padding/token knobs)
│   ├── PromptResolver.kt         # RequestResolver: parse + validate + clamp
│   ├── TokenStreamSource.kt      # Streaming-source contract (token-stream analogue of StreamSource)
│   ├── BedrockSource.kt          # TokenStreamSource: InvokeModelWithResponseStream via Kotlin SDK
│   ├── PaddedSseWriter.kt        # Tiered SSE-comment padding — the buffer workaround
│   ├── BedrockStreamHandler.kt   # Orchestration: resolve -> metadata -> relay padded SSE
│   ├── BedrockStreamingHandler.kt# No-arg Lambda entrypoint
│   └── Priming.kt                # CRaC warm-up hook for SnapStart
├── src/test/kotlin/              # Unit + property tests (MockK)
deployment/aws/sam-bedrock/       # SAM template + config + build/deploy scripts
scripts/bedrock-streaming-test.sh # Opt-in post-deploy progressive-arrival test
```
