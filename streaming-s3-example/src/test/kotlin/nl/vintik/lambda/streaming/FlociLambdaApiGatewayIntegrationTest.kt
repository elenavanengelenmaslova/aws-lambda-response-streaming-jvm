package nl.vintik.lambda.streaming

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.apigateway.ApiGatewayClient
import aws.sdk.kotlin.services.apigateway.createDeployment
import aws.sdk.kotlin.services.apigateway.createResource
import aws.sdk.kotlin.services.apigateway.createRestApi
import aws.sdk.kotlin.services.apigateway.getResources
import aws.sdk.kotlin.services.apigateway.model.IntegrationType
import aws.sdk.kotlin.services.apigateway.putIntegration
import aws.sdk.kotlin.services.apigateway.putMethod
import aws.sdk.kotlin.services.lambda.LambdaClient
import aws.sdk.kotlin.services.lambda.createFunction
import aws.sdk.kotlin.services.lambda.getFunction
import aws.sdk.kotlin.services.lambda.invoke
import aws.sdk.kotlin.services.lambda.model.Environment
import aws.sdk.kotlin.services.lambda.model.FunctionCode
import aws.sdk.kotlin.services.lambda.model.InvokeWithResponseStreamRequest
import aws.sdk.kotlin.services.lambda.model.Runtime as LambdaRuntime
import aws.smithy.kotlin.runtime.net.url.Url
import io.floci.testcontainers.FlociContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Integration test that runs the handler as a **real deployed Lambda behind a real API Gateway
 * REST API**, both emulated by Floci — the layer between the in-process tests
 * ([FlociS3IntegrationTestBase] and its subclasses) and the post-deploy tests against AWS.
 *
 * ## What this layer proves
 *  1. The shadow jar is a valid Lambda deployment package and `S3StreamingHandler` resolves,
 *     loads, and runs inside a real Lambda container on the `java25` runtime — a packaging or
 *     class-loading break that in-process tests cannot see fails here.
 *  2. The wire protocol survives the managed runtime: the buffered `Invoke` response really is
 *     metadata JSON, then eight null bytes, then the body, with the status committed in the
 *     prelude — produced by the library's `ResponseWriter` inside the container, not in-process.
 *  3. An API Gateway `/{proxy+}` route with an `AWS_PROXY` integration reaches that function.
 *
 * ## Why these assertions avoid S3
 * Both invocation paths below use requests that fail *before* the source is touched (an unparseable
 * event, and a file name rejected by [FileNameValidator]), so no S3 call is made. That is
 * deliberate: [S3Source.defaultClient] builds its client with `S3Client { }`, and that constructor
 * resolves region and credentials from the default chain but **not** the endpoint — only
 * `S3Client.fromEnvironment()` honours `AWS_ENDPOINT_URL` / `AWS_ENDPOINT_URL_S3`. A function
 * deployed into an emulator therefore cannot be redirected at the emulated S3 without changing
 * production code, so pointing it there was dropped rather than papered over. The byte-identical
 * large-body path is covered in-process instead, by [FifteenMbStreamingIntegrationTest] and
 * [S3StreamingSub6MbIntegrationTest] against the emulated S3 — where the client *is* built with an
 * explicit endpoint.
 *
 * ## What it deliberately does not prove
 * Progressive delivery. Floci does not implement `InvokeWithResponseStream` and models no
 * `responseTransferMode`, so the streaming invocation path is unreachable locally — asserted
 * outright below rather than left implicit. Time-to-first-byte can only be measured against
 * deployed AWS; see `scripts/post-deploy-test.sh` and `docs/article.md` ("Test it in layers",
 * references [14][15]). If a future Floci release implements the streaming API, that assertion
 * turns red, which is the signal to revisit both this file and the article.
 *
 * ## Networking
 * The emulator runs on a dedicated Docker network ([FlociContainer.withDedicatedNetwork]) so the
 * sibling Lambda containers it spawns can reach its Runtime API.
 */
class FlociLambdaApiGatewayIntegrationTest : FlociS3IntegrationTestBase() {

    private lateinit var lambda: LambdaClient
    private lateinit var apiGateway: ApiGatewayClient
    private lateinit var restApiId: String

    /** Adds the dedicated network the sibling Lambda containers join. */
    override fun createContainer(): FlociContainer =
        FlociContainer(FLOCI_IMAGE).withDedicatedNetwork()

    /**
     * Runs after the base class's `@BeforeAll` (JUnit orders superclass hooks first), so the
     * emulator already exists. Deploys the fat jar and fronts it with a REST API.
     */
    @BeforeAll
    fun deployFunctionAndApi() {
        lambda = LambdaClient { applyEmulatorConfig() }
        apiGateway = ApiGatewayClient { applyEmulatorConfig() }

        runBlocking {
            val functionArn = createFunctionFromFatJar()
            awaitFunctionActive()
            restApiId = createProxyRestApi(functionArn)
        }
    }

    @AfterAll
    fun closeClients() {
        if (::lambda.isInitialized) lambda.close()
        if (::apiGateway.isInitialized) apiGateway.close()
    }

    // ---- 1 + 2: the jar runs in a real Lambda container and emits the protocol ------------------

    @Test
    fun `Given the fat jar deployed to an emulated Lambda When invoked Then the response is a well-formed protocol prelude, delimiter, and body`() {
        // An unparseable event: the resolver rejects it before any source is consulted, so this
        // exercises packaging, class loading, and the protocol writer without needing S3.
        val response = invokeFunction(UNPARSEABLE_EVENT)

        assertTrue(response != null && response.isNotEmpty(), "the function must return a payload")
        requireNotNull(response)

        // Segment 1 + 2: metadata JSON, then the 8 null bytes — extractMetadata asserts the
        // delimiter is present and that everything before it is valid ResponseMetadata JSON.
        val metadata = extractMetadata(response)
        assertEquals(
            400,
            metadata.statusCode,
            "an unparseable event must commit 400 in the prelude, inside the real runtime",
        )

        // Segment 3: the error body the handler wrote after the delimiter.
        val body = extractBody(response).decodeToString()
        assertTrue(
            body.contains("could not be parsed"),
            "the body after the delimiter must carry the handler's error message; was: $body",
        )
    }

    @Test
    fun `Given a request rejected by validation When invoked in the runtime Then the prelude commits 400 before any body`() {
        // ".." trips FileNameValidator's parent-directory rule — a second pre-source path, proving
        // the status-committed-early ordering holds inside the managed runtime too.
        val response = invokeFunction(proxyEvent(REJECTED_FILE_NAME))

        requireNotNull(response) { "the function must return a payload" }
        assertEquals(400, extractMetadata(response).statusCode, "validation failure must commit 400")
    }

    // ---- 3: API Gateway routing reaches the function -------------------------------------------

    @Test
    fun `Given a proxy+ REST API in front of the function When called over HTTP Then the request reaches the handler`() {
        val response = callDeployedApi(REJECTED_FILE_NAME)

        // The route resolved and the integration ran — API Gateway answered from the function
        // rather than 404-ing the path itself, and the status the handler committed in the prelude
        // came back to the client.
        assertEquals(
            400,
            response.statusCode(),
            "the status committed in the prelude must reach the client through the proxy route",
        )

        // But the body does not survive. In BUFFERED transfer mode an AWS_PROXY integration expects
        // proxy-shaped JSON; this handler writes the streaming prelude instead (metadata JSON, 8
        // null bytes, then the body). The emulator leniently reads statusCode out of that leading
        // JSON object and, finding no "body" key, returns nothing — the classic "right status, no
        // bytes" symptom. Real API Gateway is stricter: reference [13] documents a 500 for a
        // response that does not match the format, and a 502 is what the article observed. Either
        // way the payload is lost, which is precisely why ResponseTransferMode: RESPONSE_STREAM is
        // required on the real deployment (Step 3 of docs/article.md).
        assertTrue(
            response.body().isBlank(),
            "the streamed body must not survive a buffered proxy integration; " +
                "got: ${response.body().take(ERROR_BODY_PREVIEW)}",
        )
    }

    // ---- the gap, asserted --------------------------------------------------------------------

    @Test
    fun `Given the emulator When InvokeWithResponseStream is called Then it is unsupported`() {
        val error = runCatching {
            runBlocking {
                lambda.invokeWithResponseStream(
                    InvokeWithResponseStreamRequest {
                        functionName = FUNCTION_NAME
                        this.payload = UNPARSEABLE_EVENT.encodeToByteArray()
                    },
                ) { it.eventStream?.collect { } }
            }
        }.exceptionOrNull()

        // Reference [14] in docs/article.md: Floci lists InvokeWithResponseStream as not
        // implemented. This is the executable form of the article's claim that no local emulator
        // can exercise the streaming invocation path. If this ever stops throwing, the emulator has
        // gained streaming support and both this test and the article need revisiting.
        assertTrue(
            error != null,
            "InvokeWithResponseStream is expected to be unsupported by the emulator; " +
                "if it now succeeds, revisit docs/article.md and reference [14]",
        )
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun invokeFunction(eventJson: String): ByteArray? = runBlocking {
        lambda.invoke {
            functionName = FUNCTION_NAME
            this.payload = eventJson.encodeToByteArray()
        }.payload
    }

    // The generated client configs expose the same knobs but share no supertype, so each gets its
    // own small extension rather than one generic helper.
    private fun LambdaClient.Config.Builder.applyEmulatorConfig() {
        region = floci.region
        endpointUrl = Url.parse(floci.endpoint)
        credentialsProvider = emulatorCredentials()
    }

    private fun ApiGatewayClient.Config.Builder.applyEmulatorConfig() {
        region = floci.region
        endpointUrl = Url.parse(floci.endpoint)
        credentialsProvider = emulatorCredentials()
    }

    private fun emulatorCredentials() = StaticCredentialsProvider {
        accessKeyId = floci.accessKey
        secretAccessKey = floci.secretKey
    }

    /** Creates the function from the Gradle-built shadow jar (a jar is a valid Lambda zip). */
    private suspend fun createFunctionFromFatJar(): String {
        val jar = fatJarPath()
        val created = lambda.createFunction {
            functionName = FUNCTION_NAME
            // Matches `Runtime: java25` in deployment/aws/sam/template.yaml. fromValue rather than
            // an enum constant so a newer runtime than the SDK knows about still works.
            runtime = LambdaRuntime.fromValue("java25")
            role = "arn:aws:iam::000000000000:role/streaming-lambda-role"
            handler = "nl.vintik.lambda.streaming.S3StreamingHandler"
            code = FunctionCode { zipFile = Files.readAllBytes(jar) }
            timeout = FUNCTION_TIMEOUT_SECONDS
            memorySize = FUNCTION_MEMORY_MB
            // S3Source reads its bucket from BUCKET_NAME (never a literal). No endpoint override is
            // set: see the class KDoc — `S3Client { }` would ignore it, so the assertions here stay
            // on paths that never reach S3.
            environment = Environment { variables = mapOf("BUCKET_NAME" to BUCKET) }
        }
        return requireNotNull(created.functionArn) { "emulator returned no function ARN" }
    }

    /** Polls until the function reports a terminal state, so the first invoke is not a race. */
    private suspend fun awaitFunctionActive() {
        repeat(FUNCTION_READY_ATTEMPTS) {
            val state = runCatching {
                lambda.getFunction { functionName = FUNCTION_NAME }.configuration?.state?.value
            }.getOrNull()
            if (state == null || state == "Active") return
            delay(FUNCTION_READY_POLL_MS)
        }
    }

    /** Builds `/{proxy+}` + `ANY` + AWS_PROXY integration and deploys it to a stage. */
    private suspend fun createProxyRestApi(targetArn: String): String {
        val api = apiGateway.createRestApi { name = "streaming-example-api" }
        val apiId = requireNotNull(api.id) { "emulator returned no REST API id" }

        val rootId = requireNotNull(
            apiGateway.getResources { restApiId = apiId }.items?.first { it.path == "/" }?.id,
        ) { "REST API has no root resource" }

        val proxyId = requireNotNull(
            apiGateway.createResource {
                restApiId = apiId
                parentId = rootId
                pathPart = "{proxy+}"
            }.id,
        ) { "failed to create the {proxy+} resource" }

        apiGateway.putMethod {
            restApiId = apiId
            resourceId = proxyId
            httpMethod = "ANY"
            authorizationType = "NONE"
        }

        apiGateway.putIntegration {
            restApiId = apiId
            resourceId = proxyId
            httpMethod = "ANY"
            type = IntegrationType.AwsProxy
            // Lambda proxy integrations are always invoked with POST regardless of the client verb.
            integrationHttpMethod = "POST"
            // The buffered invoke path. The streaming deployment uses the 2021-11-15 date and a
            // /response-streaming-invocations suffix instead — see Step 3 of docs/article.md.
            uri = "arn:aws:apigateway:${floci.region}:lambda:path/2015-03-31/functions/$targetArn/invocations"
        }

        apiGateway.createDeployment {
            restApiId = apiId
            stageName = STAGE
        }
        return apiId
    }

    /** Calls the deployed stage through the emulator's execute-api path. */
    private fun callDeployedApi(path: String): HttpResponse<String> {
        val uri = URI.create("${floci.endpoint}/restapis/$restApiId/$STAGE/_user_request_/$path")
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
            .GET()
            .build()
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS)).build()
            .use { client ->
                return client.send(request, HttpResponse.BodyHandlers.ofString())
            }
    }

    private fun fatJarPath(): Path {
        val configured = requireNotNull(System.getProperty("streaming.fatJar")) {
            "system property 'streaming.fatJar' is not set — run this test through Gradle so the " +
                "shadow jar is built and its path injected"
        }
        val path = Path.of(configured)
        require(Files.isRegularFile(path)) {
            "shadow jar not found at $path — run ./gradlew :streaming-s3-example:shadowJar first"
        }
        return path
    }

    private companion object {
        const val FUNCTION_NAME = "streaming-example-function"
        const val STAGE = "test"

        /** Not JSON, so RequestParser fails and the resolver answers 400 without touching S3. */
        const val UNPARSEABLE_EVENT = "this is not a proxy event"

        /** Contains "..", which FileNameValidator rejects before the source is consulted. */
        const val REJECTED_FILE_NAME = "bad..name.bin"

        const val FUNCTION_TIMEOUT_SECONDS = 60
        const val FUNCTION_MEMORY_MB = 1024
        const val FUNCTION_READY_ATTEMPTS = 30
        const val FUNCTION_READY_POLL_MS = 1_000L
        const val HTTP_TIMEOUT_SECONDS = 120L
        const val ERROR_BODY_PREVIEW = 300
    }
}
