package nl.vintik.streaming.java;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.floci.testcontainers.FlociContainer;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import kotlinx.serialization.json.Json;
import nl.vintik.lambda.streaming.ResponseWriter;
import nl.vintik.lambda.streaming.ResponseWriterKt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Shared Floci S3 harness for the Java example's end-to-end integration tests
 * (Req 13.2, 13.4). Streams a real S3 object THROUGH the production {@link StreamHandler}
 * against an emulated S3 reached with the AWS SDK for Java v2, so the wire protocol,
 * bounded-buffer copy, and S3 source are all exercised together against a real S3
 * implementation &mdash; not a mock.
 *
 * <p><b>Why Floci and not LocalStack.</b> Both are local AWS emulators; Floci is a drop-in
 * replacement on the same port 4566, MIT licensed, and needs no auth token. Neither, however,
 * emulates Lambda <i>response streaming</i>: Floci lists {@code InvokeWithResponseStream} as not
 * implemented, and LocalStack documents response streaming as unsupported. Progressive delivery
 * can therefore only be proven against a deployed AWS endpoint &mdash; see {@code docs/article.md},
 * "Test it in layers".
 *
 * <p><b>One container per class.</b> A single {@link FlociContainer} is started in
 * {@link #startContainer()} and stopped in {@link #stopContainer()}. The class is
 * {@link TestInstance.Lifecycle#PER_CLASS} so those hooks are instance methods and each
 * concrete subclass gets its own container shared across all of its test methods. Readiness
 * needs no explicit wait strategy &mdash; {@link FlociContainer} gates startup on its own
 * {@code /_floci/init} endpoint. Between tests only object data is cleaned
 * ({@link #cleanObjects()}) &mdash; the container and bucket keep running.
 *
 * <p><b>Reuse.</b> This base is self-contained and holds no test methods (it is abstract, so
 * JUnit does not run it directly). Subclasses add {@code @Test} methods and drive the handler
 * via {@link #handlerForBucket()} / {@link #upload(String, byte[])} / {@link #proxyEvent(String)},
 * then read the protocol response with {@link #extractMetadataJson(byte[])} and
 * {@link #extractBody(byte[])}. The sub-6 MB and ~15 MB integration tests both build on it.
 *
 * <p><b>Container runtime.</b> This project runs on Colima, not Docker Desktop (see
 * {@code tech.md}); TestContainers connects via the Colima Docker socket
 * ({@code DOCKER_HOST} + {@code TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE}), and
 * {@link FlociContainer} re-binds that same socket into the emulator so it can spawn sibling
 * containers. Subclasses are tagged {@code integration} so they are excluded by
 * {@code -PexcludeTags=integration} when no container runtime is available; they are not
 * skip-annotated, so they run whenever one is.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class FlociS3IntegrationTestBase {

    /**
     * Pinned emulator image. Supplied by the build from the root version catalog so the pin lives
     * in one place; the fallback keeps the test runnable from an IDE without Gradle.
     */
    private static final String FLOCI_IMAGE =
            System.getProperty("floci.image", "floci/floci:1.7.0");

    /** Shared source bucket, created once per class in {@link #startContainer()}. */
    protected static final String BUCKET = "streaming-test-bucket";

    /**
     * Protocol delimiter length, read from the library facade so the metadata/body boundary
     * constant is never duplicated in the Java example.
     */
    private static final int DELIMITER_LEN = ResponseWriterKt.DELIMITER_LEN;

    private FlociContainer floci;
    private S3Client s3;

    // The S3Client is a long-lived field closed in stopContainer(); its lifecycle spans methods.
    @BeforeAll
    void startContainer() {
        floci = new FlociContainer(FLOCI_IMAGE);
        floci.start();

        s3 = S3Client.builder()
                .endpointOverride(URI.create(floci.getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(floci.getAccessKey(), floci.getSecretKey())))
                .region(Region.of(floci.getRegion()))
                // Emulators serve S3 path-style (bucket in the path, not the host).
                .forcePathStyle(true)
                .build();

        s3.createBucket(b -> b.bucket(BUCKET));
    }

    @AfterAll
    void stopContainer() {
        if (s3 != null) {
            s3.close();
        }
        if (floci != null) {
            floci.stop();
        }
    }

    /** Clean only object data between tests &mdash; the shared container and bucket keep running. */
    @AfterEach
    void cleanObjects() {
        ListObjectsV2Response listed = s3.listObjectsV2(b -> b.bucket(BUCKET));
        for (S3Object object : listed.contents()) {
            s3.deleteObject(b -> b.bucket(BUCKET).key(object.key()));
        }
    }

    // ---- helpers for subclasses -------------------------------------------------------------

    /** Uploads {@code payload} under {@code key} into the shared bucket via SDK v2. */
    protected void upload(String key, byte[] payload) {
        s3.putObject(b -> b.bucket(BUCKET).key(key), RequestBody.fromBytes(payload));
    }

    /**
     * Builds the production {@link StreamHandler} wired to the emulated S3. Real
     * {@link RequestParser}, {@link FileNameValidator}, and library {@link ResponseWriter}
     * collaborators are injected via the package-private constructor; only the {@link S3Source}
     * is pointed at the container (its client + bucket), so the full parse &rarr; validate
     * &rarr; head &rarr; stream pipeline runs against real S3.
     */
    protected StreamHandler handlerForBucket() {
        return new StreamHandler(
                new RequestParser(),
                new FileNameValidator(),
                new S3Source(s3, BUCKET),
                new ResponseWriter(Json.Default, ResponseWriterKt.OBSERVED_MAX_PRELUDE_LEN));
    }

    /** A minimal API Gateway {@code /{proxy+}} event carrying the requested file name. */
    protected static InputStream proxyEvent(String fileName) {
        String json = "{\"pathParameters\":{\"proxy\":\"" + fileName + "\"}}";
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Start index of the first run of {@link #DELIMITER_LEN} consecutive zero bytes (the
     * metadata/body delimiter), or {@code -1} if absent. The metadata JSON never contains a raw
     * NUL byte, so the first such run reliably separates the metadata prelude from the body.
     */
    private static int indexOfDelimiter(byte[] response) {
        int run = 0;
        for (int i = 0; i < response.length; i++) {
            if (response[i] == 0) {
                run++;
                if (run == DELIMITER_LEN) {
                    return i - DELIMITER_LEN + 1;
                }
            } else {
                run = 0;
            }
        }
        return -1;
    }

    /** The metadata JSON prelude: every byte before the 8 null-byte delimiter. */
    protected static String extractMetadataJson(byte[] response) {
        int end = indexOfDelimiter(response);
        assertTrue(end >= 0, "the 8 null-byte metadata/body delimiter must be present in the response");
        return new String(response, 0, end, StandardCharsets.UTF_8);
    }

    /** The streamed body: every byte after the metadata JSON and the 8 null-byte delimiter. */
    protected static byte[] extractBody(byte[] response) {
        int start = indexOfDelimiter(response);
        assertTrue(start >= 0, "the 8 null-byte metadata/body delimiter must be present in the response");
        return Arrays.copyOfRange(response, start + DELIMITER_LEN, response.length);
    }
}
