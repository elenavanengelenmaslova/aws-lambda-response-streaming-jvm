package nl.vintik.streaming.java;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RequestResolver} for an AWS Lambda Function URL file-serving endpoint.
 *
 * <p>The Function URL delivers a Lambda payload format 2.0 event (distinct from the API
 * Gateway proxy event). This resolver reads a minimal lenient projection of that event
 * &mdash; {@code rawPath}, falling back to {@code requestContext.http.path} &mdash; and
 * lifts the requested file name from it, then runs the <strong>shared</strong>
 * {@link FileNameValidator}. All other event fields (including the
 * {@code requestContext.authorizer.iam} section present under {@code AuthType: AWS_IAM})
 * are tolerated and ignored via a lenient Jackson {@link ObjectMapper} that does not fail
 * on unknown properties, matching {@link RequestParser}'s configuration.
 *
 * <p>The status mapping is identical to {@link FileKeyResolver}: a decode failure maps to
 * HTTP 400 with the message {@code "The request could not be parsed."}, every validation
 * rejection maps to HTTP 400 with the same specific message, and no source request is
 * issued for any rejected input. A valid name yields {@link RequestResult.Resolved}
 * carrying a {@link FileRequest}.
 *
 * <p>Java counterpart of the Kotlin example's {@code FunctionUrlResolver}, preserving the
 * same path extraction ({@code trimStart('/').substringAfterLast('/')}), status codes, and
 * message wording.
 */
public final class FunctionUrlResolver implements RequestResolver<FileRequest> {

    private static final Logger logger = LoggerFactory.getLogger(FunctionUrlResolver.class);

    private final FileNameValidator validator;
    private final ObjectMapper mapper;

    /** Wires the shared {@link FileNameValidator} and a lenient {@link ObjectMapper}. */
    public FunctionUrlResolver() {
        this(
            new FileNameValidator(),
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false));
    }

    /**
     * Package-private constructor for unit tests: injects the collaborators so decode and
     * validation outcomes can be exercised without touching the real event shape.
     *
     * @param validator validates the requested file name
     * @param mapper    the lenient JSON reader used to decode the payload-2.0 event
     */
    FunctionUrlResolver(FileNameValidator validator, ObjectMapper mapper) {
        this.validator = validator;
        this.mapper = mapper;
    }

    @Override
    public RequestResult<FileRequest> resolve(InputStream input) {
        FunctionUrlEvent event;
        try {
            event = mapper.readValue(input, FunctionUrlEvent.class);
            if (event == null) {
                logger.warn("Function URL payload-2.0 event could not be parsed; responding 400");
                return new RequestResult.Error<>(400, "The request could not be parsed.");
            }
        } catch (Exception e) {
            logger.warn("Function URL payload-2.0 event could not be parsed; responding 400", e);
            return new RequestResult.Error<>(400, "The request could not be parsed.");
        }
        return resolveFileName(fileNameFrom(event));
    }

    /**
     * Lifts the requested file name from the event path: strip a leading {@code /} then take
     * the last {@code /}-delimited segment, matching the Kotlin resolver's
     * {@code path.trimStart('/').substringAfterLast('/')}.
     */
    private static String fileNameFrom(FunctionUrlEvent event) {
        String path = event.requestedPath();
        String trimmed = stripLeadingSlashes(path);
        int lastSlash = trimmed.lastIndexOf('/');
        return lastSlash < 0 ? trimmed : trimmed.substring(lastSlash + 1);
    }

    private static String stripLeadingSlashes(String path) {
        int start = 0;
        while (start < path.length() && path.charAt(start) == '/') {
            start++;
        }
        return path.substring(start);
    }

    /**
     * Validates the extracted file name and maps every rejection to HTTP 400 with a specific
     * message, issuing no source request for a rejected name. A valid name yields a
     * {@link RequestResult.Resolved} carrying the {@link FileRequest}.
     */
    private RequestResult<FileRequest> resolveFileName(String fileName) {
        return switch (validator.validate(fileName)) {
            case ValidationResult.Invalid invalid -> {
                logger.warn("File name rejected (reason={}); responding 400 with no source request", invalid.reason());
                yield new RequestResult.Error<>(400, messageFor(invalid.reason()));
            }
            case ValidationResult.Valid valid -> new RequestResult.Resolved<>(new FileRequest(valid.fileName()));
        };
    }

    /**
     * Maps a rejection {@link ValidationResult.Reason} to a client-facing 400 message,
     * mirroring {@link FileKeyResolver}'s wording exactly. Missing and too-long names get
     * distinct messages; every path-safety rejection collapses to a single generic message so
     * the response does not disclose which specific rule tripped.
     */
    private static String messageFor(ValidationResult.Reason reason) {
        return switch (reason) {
            case MISSING -> "The file name is missing.";
            case TOO_LONG -> "The file name is invalid.";
            case ILLEGAL_CHARACTER, PATH_SEPARATOR, PARENT_DIR, ABSOLUTE_PATH -> "The file name was rejected.";
        };
    }

    /**
     * Minimal lenient projection of the Lambda Function URL payload-2.0 event: only the
     * request path is read so the requested file name can be lifted out. All other fields
     * (including the IAM authorizer section) are ignored.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FunctionUrlEvent(String rawPath, RequestContext requestContext) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        private record RequestContext(Http http) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Http(String path) {}

        /**
         * The requested path &mdash; {@code rawPath} when present, otherwise
         * {@code requestContext.http.path}, or an empty string when both are absent (left for
         * the validator to reject).
         */
        private String requestedPath() {
            if (rawPath != null) {
                return rawPath;
            }
            if (requestContext != null && requestContext.http() != null && requestContext.http().path() != null) {
                return requestContext.http().path();
            }
            return "";
        }
    }
}
