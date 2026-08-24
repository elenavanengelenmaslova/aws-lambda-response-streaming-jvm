package nl.vintik.lambda.streaming

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.createBucket
import aws.sdk.kotlin.services.s3.deleteObject
import aws.sdk.kotlin.services.s3.listObjectsV2
import aws.sdk.kotlin.services.s3.putObject
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.net.url.Url
import io.floci.testcontainers.FlociContainer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance

/**
 * Shared Floci harness for the Kotlin example's end-to-end integration tests.
 *
 * Streams a real S3 object THROUGH the production [StreamHandler] against an emulated S3 reached
 * with the Kotlin AWS SDK, so the wire protocol, bounded-buffer copy, and S3 source are exercised
 * together against a real S3 implementation — not a mock.
 *
 * ## Why Floci and not LocalStack
 * Both are local AWS emulators; Floci is a drop-in replacement on the same port 4566, MIT
 * licensed, and needs no auth token. Neither, however, emulates Lambda **response streaming**:
 * Floci lists `InvokeWithResponseStream` as not implemented, and LocalStack documents response
 * streaming as unsupported. That gap is deliberate and asserted in
 * [FlociLambdaApiGatewayIntegrationTest] rather than glossed over — progressive delivery can only
 * be proven against a deployed AWS endpoint (see `docs/article.md`, "Test it in layers").
 *
 * ## One container per class
 * A single [FlociContainer] is started in [startFloci] and stopped in [stopFloci]. The class is
 * [TestInstance.Lifecycle.PER_CLASS] so those hooks are instance methods and each concrete
 * subclass gets its own container shared across all of its test methods. Readiness needs no
 * explicit wait strategy — [FlociContainer] gates startup on its own `/_floci/init` endpoint.
 * Between tests only object data is cleaned ([cleanObjects]); the container and bucket keep
 * running.
 *
 * ## Reuse
 * Subclasses add `@Test` methods and drive the handler via [handlerForBucket] / [upload] /
 * [proxyEvent], then read the protocol response with [extractMetadata] / [extractBody]. A
 * subclass needing extra emulator wiring (a dedicated network for sibling Lambda containers, for
 * instance) overrides [createContainer].
 *
 * ## Container runtime
 * This project runs on Colima, not Docker Desktop (see `tech.md`). TestContainers connects via the
 * Colima Docker socket (`DOCKER_HOST` + `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`), and
 * [FlociContainer] re-binds that same socket into the emulator so it can spawn sibling containers.
 * Subclasses are tagged `integration` so they are excluded by `-PexcludeTags=integration` when no
 * container runtime is available; they are not skip-annotated, so they run whenever one is.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class FlociS3IntegrationTestBase {

    /** The running emulator. Available from [startFloci] onwards. */
    protected lateinit var floci: FlociContainer
        private set

    /** Kotlin SDK client aimed at the emulated S3. Available from [startFloci] onwards. */
    protected lateinit var s3: S3Client
        private set

    @BeforeAll
    fun startFloci() {
        floci = createContainer()
        floci.start()
        s3 = buildS3Client()
        runBlocking { s3.createBucket { bucket = BUCKET } }
    }

    @AfterAll
    fun stopFloci() {
        if (::s3.isInitialized) s3.close()
        if (::floci.isInitialized) floci.stop()
    }

    /** Clean only object data between tests — the shared container and bucket keep running. */
    @AfterEach
    fun cleanObjects() {
        runBlocking {
            val listed = s3.listObjectsV2 { bucket = BUCKET }
            listed.contents?.forEach { obj ->
                obj.key?.let { objectKey ->
                    s3.deleteObject {
                        bucket = BUCKET
                        key = objectKey
                    }
                }
            }
        }
    }

    /**
     * Builds the emulator. Overridden by subclasses that need more than the default service set —
     * the pinned image is shared so every integration test runs the same emulator build.
     */
    protected open fun createContainer(): FlociContainer = FlociContainer(FLOCI_IMAGE)

    /** An [S3Client] aimed at the emulator (path-style addressing, static credentials). */
    private fun buildS3Client(): S3Client = S3Client {
        region = floci.region
        endpointUrl = Url.parse(floci.endpoint)
        // Emulators serve S3 path-style (bucket in the path, not the host).
        forcePathStyle = true
        credentialsProvider = StaticCredentialsProvider {
            accessKeyId = floci.accessKey
            secretAccessKey = floci.secretKey
        }
    }

    // ---- helpers for subclasses -----------------------------------------------------------------

    /** Uploads [payload] under [key] into the shared bucket. */
    protected fun upload(key: String, payload: ByteArray) {
        runBlocking {
            s3.putObject {
                bucket = BUCKET
                this.key = key
                body = ByteStream.fromBytes(payload)
            }
        }
    }

    /**
     * The production handler wired to the emulated S3: the real [FileKeyResolver] and [S3Source]
     * collaborators, so the full parse -> validate -> head -> stream pipeline runs against real S3.
     */
    protected fun handlerForBucket(): StreamHandler<FileRequest> = StreamHandler(
        requestResolver = ::FileKeyResolver,
        source = { S3Source(bucket = BUCKET, client = s3) },
    )

    companion object {
        /** Shared source bucket, created once per class in [startFloci]. */
        const val BUCKET: String = "streaming-test-bucket"

        /**
         * Pinned emulator image. Supplied by the build from the root version catalog so the pin
         * lives in one place; the fallback keeps the test runnable from an IDE without Gradle.
         */
        @JvmStatic
        val FLOCI_IMAGE: String = System.getProperty("floci.image", "floci/floci:1.7.0")

        /** A minimal API Gateway `/{proxy+}` event carrying the requested file name. */
        @JvmStatic
        fun proxyEvent(fileName: String): String = """{"pathParameters":{"proxy":"$fileName"}}"""

        /**
         * Start index of the first run of [DELIMITER_LEN] consecutive zero bytes (the
         * metadata/body delimiter), or -1 if absent. The metadata JSON never contains a raw NUL
         * byte, so the first such run reliably separates the prelude from the body.
         */
        @JvmStatic
        fun indexOfDelimiter(bytes: ByteArray): Int {
            var run = 0
            for (i in bytes.indices) {
                if (bytes[i].toInt() == 0) {
                    run++
                    if (run == DELIMITER_LEN) return i - DELIMITER_LEN + 1
                } else {
                    run = 0
                }
            }
            return -1
        }

        /** Decodes the metadata JSON prelude: every byte before the 8 null-byte delimiter. */
        @JvmStatic
        fun extractMetadata(response: ByteArray): ResponseMetadata {
            val end = indexOfDelimiter(response)
            assertTrue(end >= 0, "the 8 null-byte metadata/body delimiter must be present")
            return Json.decodeFromString(
                ResponseMetadata.serializer(),
                response.copyOfRange(0, end).decodeToString(),
            )
        }

        /** The streamed body: every byte after the metadata JSON and the 8 null-byte delimiter. */
        @JvmStatic
        fun extractBody(response: ByteArray): ByteArray {
            val start = indexOfDelimiter(response)
            assertTrue(start >= 0, "the 8 null-byte metadata/body delimiter must be present")
            return response.copyOfRange(start + DELIMITER_LEN, response.size)
        }
    }
}
