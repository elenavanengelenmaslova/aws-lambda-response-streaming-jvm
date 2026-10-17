package nl.vintik.streaming.java;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for the Java {@link FunctionUrlResolver} (Req 4.1, 4.2, 4.3).
 *
 * <p>Covers the three top-level resolution paths against a representative Lambda payload format 2.0
 * Function URL event ({@code rawPath} + {@code requestContext.http}, including an
 * {@code AWS_IAM}-shaped {@code requestContext.authorizer.iam} section that must be tolerated and
 * ignored):
 *
 * <ul>
 *   <li>empty / malformed input &rarr; {@link RequestResult.Error} 400 (parse failure);</li>
 *   <li>well-formed payload-2.0 event with a rejected file name &rarr; {@link RequestResult.Error}
 *       400;</li>
 *   <li>well-formed payload-2.0 event with an accepted file name &rarr;
 *       {@link RequestResult.Resolved} carrying the extracted {@link FileRequest}.</li>
 * </ul>
 *
 * <p>Assertions are on the typed {@link RequestResult} (Resolved vs. Error + status code), not on
 * whole-response string equality (Req 4.3). Exhaustive validation-rule coverage lives in
 * {@link FileNameValidatorTest}. Mirrors the Kotlin example's {@code FunctionUrlResolverTest} for
 * case parity.
 *
 * <p>Requirements: 4.1, 4.2, 4.3
 */
// assertInstanceOf(RequestResult.Error.class, ...) returns the raw record type; binding it to the
// RequestResult.Error<FileRequest> local is an unchecked conversion with no runtime risk here.
@SuppressWarnings("unchecked")
class FunctionUrlResolverTest {

    private final FunctionUrlResolver resolver = new FunctionUrlResolver();

    private RequestResult<FileRequest> resolve(byte[] event) {
        return resolver.resolve(new ByteArrayInputStream(event));
    }

    // --- Parse error -> 400 ------------------------------------------------------------------

    @Test
    @DisplayName("Given empty input When resolved Then it returns Error 400")
    void emptyInputYields400() {
        RequestResult<FileRequest> result = resolve(new byte[0]);

        RequestResult.Error<FileRequest> error = assertInstanceOf(RequestResult.Error.class, result);
        assertEquals(400, error.statusCode());
    }

    @ParameterizedTest(name = "[{index}] json={0}")
    @ValueSource(strings = {"not-json", "{ broken :", "}{", "{\"rawPath\":"})
    @DisplayName("Given malformed JSON When resolved Then it returns Error 400")
    void malformedJsonYields400(String json) {
        RequestResult<FileRequest> result = resolve(json.getBytes(UTF_8));

        RequestResult.Error<FileRequest> error = assertInstanceOf(RequestResult.Error.class, result);
        assertEquals(400, error.statusCode());
    }

    // --- Validation rejection -> 400 ---------------------------------------------------------

    @ParameterizedTest(name = "[{index}] rawPath=\"{0}\"")
    @ValueSource(
            strings = {
                "/..", // parent-directory segment
                "/dir/", // trailing slash leaves an empty last segment -> MISSING
                "/re port.bin", // illegal character (space)
            })
    @DisplayName("Given a well-formed event with a rejected file name When resolved Then it returns Error 400")
    void rejectedNameYields400(String rawPath) {
        RequestResult<FileRequest> result = resolve(functionUrlEvent(rawPath));

        RequestResult.Error<FileRequest> error = assertInstanceOf(RequestResult.Error.class, result);
        assertEquals(400, error.statusCode());
    }

    @Test
    @DisplayName("Given a file name that exceeds the length limit When resolved Then it returns Error 400")
    void overLengthNameYields400() {
        RequestResult<FileRequest> result = resolve(functionUrlEvent("/" + "a".repeat(1025)));

        RequestResult.Error<FileRequest> error = assertInstanceOf(RequestResult.Error.class, result);
        assertEquals(400, error.statusCode());
    }

    // --- Valid name -> Resolved(FileRequest(name)) -------------------------------------------

    @Test
    @DisplayName("Given a well-formed event with an accepted file name When resolved Then it returns Resolved")
    void acceptedNameYieldsResolved() {
        RequestResult<FileRequest> result = resolve(functionUrlEvent("/report.bin"));

        RequestResult.Resolved<FileRequest> resolved =
                assertInstanceOf(RequestResult.Resolved.class, result);
        assertEquals(new FileRequest("report.bin"), resolved.request());
    }

    @Test
    @DisplayName("Given a nested path When resolved Then it extracts the last segment as the file name")
    void nestedPathExtractsLastSegment() {
        RequestResult<FileRequest> result = resolve(functionUrlEvent("/some/nested/report.bin"));

        RequestResult.Resolved<FileRequest> resolved =
                assertInstanceOf(RequestResult.Resolved.class, result);
        assertEquals(new FileRequest("report.bin"), resolved.request());
    }

    /**
     * Builds a representative Lambda payload format 2.0 Function URL event with both {@code rawPath}
     * and {@code requestContext.http.path} set to {@code path}, plus an {@code AWS_IAM}-shaped
     * {@code requestContext.authorizer.iam} section the resolver must tolerate and ignore.
     */
    private static byte[] functionUrlEvent(String path) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("version", "2.0");
        root.put("routeKey", "$default");
        root.put("rawPath", path);
        root.put("rawQueryString", "");

        ObjectNode headers = root.putObject("headers");
        headers.put("host", "abc123.lambda-url.eu-west-1.on.aws");
        headers.put("x-forwarded-proto", "https");

        ObjectNode requestContext = root.putObject("requestContext");
        requestContext.put("accountId", "123456789012");
        requestContext.put("apiId", "abc123");
        requestContext.put("domainName", "abc123.lambda-url.eu-west-1.on.aws");

        ObjectNode http = requestContext.putObject("http");
        http.put("method", "GET");
        http.put("path", path);
        http.put("protocol", "HTTP/1.1");
        http.put("sourceIp", "203.0.113.1");

        ObjectNode iam = requestContext.putObject("authorizer").putObject("iam");
        iam.put("accessKey", "AKIAEXAMPLE");
        iam.put("accountId", "123456789012");
        iam.put("callerId", "AROAEXAMPLE:session");
        iam.put("userArn", "arn:aws:sts::123456789012:assumed-role/example/session");

        requestContext.put("requestId", "id");
        requestContext.put("routeKey", "$default");
        requestContext.put("stage", "$default");
        requestContext.put("time", "01/Jan/2025:00:00:00 +0000");
        requestContext.put("timeEpoch", 1735689600000L);

        root.putArray("cookies").add("session=abc");
        root.put("isBase64Encoded", false);

        try {
            return mapper.writeValueAsBytes(root);
        } catch (Exception e) {
            throw new IllegalStateException("failed to build test event", e);
        }
    }
}
