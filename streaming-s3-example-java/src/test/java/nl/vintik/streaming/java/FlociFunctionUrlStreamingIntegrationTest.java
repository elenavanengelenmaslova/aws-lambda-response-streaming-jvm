package nl.vintik.streaming.java;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Floci S3 integration test proving the <b>Lambda Function URL</b> streaming path end to end for
 * the Java example (Req 5.1, 5.2, 5.3, 5.4).
 *
 * <p>This is the Function URL counterpart to {@link SubSixMbStreamingIntegrationTest} and
 * {@link FifteenMbStreamingIntegrationTest}: container lifecycle, the emulated S3 client, object
 * cleanup, and protocol parsing all come from {@link FlociS3IntegrationTestBase} &mdash; one
 * container per class, only object data cleaned between tests (Req 5.4). This class contributes the
 * Function-URL fixtures and the byte-identity assertions.
 *
 * <p><b>What is exercised.</b> The real {@link FunctionUrlResolver} &rarr; {@link S3Source} &rarr;
 * {@link nl.vintik.lambda.streaming.ResponseWriter} pipeline &mdash; the exact wiring
 * {@link S3FunctionUrlHandler} uses &mdash; driven with a Lambda payload format <b>2.0</b> Function
 * URL event, so the Function-URL event shape ({@code rawPath} + {@code requestContext.http}) is
 * parsed and resolved against a live S3 implementation rather than a mock. The event carries an
 * {@code AWS_IAM}-shaped {@code requestContext.authorizer.iam} section (Req 5.3): the resolver must
 * tolerate and ignore it, proving the handler is driven by the same event shape the IAM-
 * authenticated Function URL produces.
 *
 * <p><b>Why a locally wired handler rather than the production entry point.</b>
 * {@link S3FunctionUrlHandler} wires a default {@link S3Source}, whose client resolves credentials
 * and region but <b>not</b> an emulator endpoint, so it cannot be redirected at the emulated S3
 * without changing production code (the same constraint {@link FlociS3IntegrationTestBase}
 * documents for the API Gateway path). This test therefore drives the handler built by
 * {@link FlociS3IntegrationTestBase#functionUrlHandlerForBucket()} &mdash; the production
 * {@link FunctionUrlResolver} and the library {@code ResponseWriter} with an emulator-pointed
 * {@link S3Source} &mdash; identical collaborators to {@link S3FunctionUrlHandler}, differing only
 * in the S3 endpoint.
 *
 * <p><b>What it deliberately does not prove.</b> Real IAM request signing and progressive
 * (first-byte-early) delivery over the wire. No local emulator implements the Function URL
 * streaming invocation path or SigV4 verification, so those are deferred to the signed post-deploy
 * script against real AWS (Req 5.5, Req 9); here the handler is fed an {@code AWS_IAM}-shaped event
 * directly.
 *
 * <p>Validates: Requirements 5.1, 5.2, 5.3, 5.4
 */
@Tag("integration")
class FlociFunctionUrlStreamingIntegrationTest extends FlociS3IntegrationTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Reads the committed metadata JSON prelude and streams it through the Function URL handler. */
    private byte[] streamThroughFunctionUrl(String key) throws IOException {
        StreamHandler handler = functionUrlHandlerForBucket();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        handler.handleRequest(functionUrlEvent(key), output, mock(Context.class));
        return output.toByteArray();
    }

    // ---- sub-6 MB ----------------------------------------------------------------------------

    @Test
    @DisplayName("Given a sub-6 MB S3 object When streamed through the Function URL handler Then the received body is byte-identical")
    void subSixMbObjectStreamsByteIdenticalThroughFunctionUrlHandler() throws IOException {
        // Given: 5 MB of deterministic pseudo-random bytes — under the legacy 6 MB buffered limit,
        // larger than the 1 MB transfer buffer so multiple bounded-buffer chunks are exercised.
        String key = "fn-url-sub-six.bin";
        byte[] payload = new byte[5 * 1024 * 1024];
        new Random(42).nextBytes(payload);
        upload(key, payload);

        // When: the handler resolves the AWS_IAM-shaped payload-2.0 event and streams the object.
        byte[] response = streamThroughFunctionUrl(key);

        // Then: the committed metadata is 200 with a Content-Length equal to the stored size.
        JsonNode metadata = MAPPER.readTree(extractMetadataJson(response));
        assertEquals(200, metadata.get("statusCode").asInt(), "a present object must commit 200");
        assertEquals(
                String.valueOf(payload.length),
                metadata.get("headers").get("Content-Length").asText(),
                "declared Content-Length must equal the uploaded object size");

        // And: the streamed body is byte-identical (same bytes, order, and count) to the upload.
        byte[] body = extractBody(response);
        assertEquals(payload.length, body.length, "received body length must equal the uploaded size");
        assertArrayEquals(payload, body, "streamed body must be byte-identical to the uploaded object");
    }

    // ---- >= 15 MB ----------------------------------------------------------------------------

    @Test
    @DisplayName("Given a ~15 MB S3 object When streamed through the Function URL handler Then status is 200 and the body is byte-identical past the 6 MB limit")
    void fifteenMbObjectStreamsByteIdenticalThroughFunctionUrlHandler() throws IOException {
        // Given: ~15 MB of deterministic pseudo-random bytes — well over the legacy 6 MB buffered
        // limit and many multiples of the 1 MB transfer buffer, so the bounded-buffer copy loops.
        String key = "fn-url-fifteen-mb-test-object.bin";
        byte[] payload = new byte[15 * 1024 * 1024];
        new Random(1515).nextBytes(payload);
        assertTrue(payload.length > 6 * 1024 * 1024, "the fixture must exceed the legacy 6 MB limit");
        upload(key, payload);

        // When: the handler resolves the AWS_IAM-shaped payload-2.0 event and streams the object.
        byte[] response = streamThroughFunctionUrl(key);

        // Then: the committed metadata is 200 with a Content-Length equal to the stored size.
        JsonNode metadata = MAPPER.readTree(extractMetadataJson(response));
        assertEquals(200, metadata.get("statusCode").asInt(), "committed status must be 200 for the ~15 MB object");
        assertEquals(
                String.valueOf(payload.length),
                metadata.get("headers").get("Content-Length").asText(),
                "declared Content-Length must equal the uploaded object size");

        // And: every byte is delivered byte-identically without truncation past the 6 MB limit.
        byte[] body = extractBody(response);
        assertEquals(payload.length, body.length, "received body byte count must equal the stored object size");
        assertArrayEquals(payload, body, "streamed body must be byte-identical to the uploaded ~15 MB object");
    }

    // ---- Property 3: byte-identity across sizes ----------------------------------------------

    /**
     * Property 3: Streaming output over the Function URL path is byte-identical to the source.
     *
     * <p>For an object stored in S3 and requested through the Function URL event, the body
     * delivered after the metadata JSON and the 8 null-byte delimiter is byte-for-byte identical to
     * the stored object &mdash; across both a sub-6 MB case and a &ge; 15 MB case (past the legacy
     * buffered limit).
     *
     * <p>Validates: Requirements 5.1, 5.2, 5.3
     */
    @DisplayName("Feature: function-url-streaming, Property 3: Function URL streamed body is byte-identical to the source")
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("sizeCases")
    void functionUrlStreamedBodyIsByteIdenticalToSource(String label, int sizeBytes, int seed) throws IOException {
        String key = "fn-url-prop3-" + sizeBytes + ".bin";
        byte[] payload = new byte[sizeBytes];
        new Random(seed).nextBytes(payload);
        upload(key, payload);

        byte[] response = streamThroughFunctionUrl(key);

        JsonNode metadata = MAPPER.readTree(extractMetadataJson(response));
        assertEquals(200, metadata.get("statusCode").asInt(), "a present object must commit 200 for size " + sizeBytes);

        byte[] body = extractBody(response);
        assertEquals(payload.length, body.length, "received body length must equal the stored object size (" + sizeBytes + ")");
        assertArrayEquals(payload, body, "received body must be byte-identical to the source for size " + sizeBytes);
    }

    static Stream<Arguments> sizeCases() {
        return Stream.of(
                // sub-6 MB: under the legacy buffered limit, multiple transfer-buffer chunks.
                Arguments.of("sub-6 MB (5 MB)", 5 * 1024 * 1024, 42),
                // >= 15 MB: well past the legacy 6 MB limit, many bounded-buffer loops.
                Arguments.of(">= 15 MB (15 MB)", 15 * 1024 * 1024, 1515));
    }
}
