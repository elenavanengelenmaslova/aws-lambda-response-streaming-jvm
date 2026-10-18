package nl.vintik.streaming.java;

import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RequestResolver} for an API Gateway {@code /{proxy+}} file-serving endpoint.
 *
 * <p>Combines {@link RequestParser} (extracts the file name from the proxy path parameter)
 * and {@link FileNameValidator} (rejects path traversal, illegal characters, etc.) to
 * produce a {@link RequestResult}. A parse failure maps to HTTP 400; every validation
 * rejection maps to HTTP 400 with a specific message. No source request is issued for any
 * rejected input.
 *
 * <p>Returns {@link RequestResult.Resolved} carrying a {@link FileRequest} with the
 * validated file name, or {@link RequestResult.Error} with the HTTP status and message.
 *
 * <p>Java counterpart of the Kotlin example's {@code FileKeyResolver}. It wraps the same
 * parse-then-validate flow the handler performed inline, preserving its exact status codes,
 * messages, and warn-level log wording so the handler refactor onto this resolver is
 * behavior-preserving.
 *
 * @param <R> resolved request type is fixed to {@link FileRequest}
 */
public final class FileKeyResolver implements RequestResolver<FileRequest> {

    private static final Logger logger = LoggerFactory.getLogger(FileKeyResolver.class);

    private final RequestParser parser;
    private final FileNameValidator validator;

    /** Wires the real {@link RequestParser} and {@link FileNameValidator} collaborators. */
    public FileKeyResolver() {
        this(new RequestParser(), new FileNameValidator());
    }

    /**
     * Package-private constructor for unit tests: injects the collaborators so parse and
     * validation outcomes can be exercised without touching the real event shape.
     *
     * @param parser    parses the proxy event into a {@link StreamRequest}
     * @param validator validates the requested file name
     */
    FileKeyResolver(RequestParser parser, FileNameValidator validator) {
        this.parser = parser;
        this.validator = validator;
    }

    @Override
    public RequestResult<FileRequest> resolve(InputStream input) {
        return switch (parser.parse(input)) {
            case ParseResult.ParseError _ -> {
                logger.warn("API Gateway proxy event could not be parsed; responding 400");
                yield new RequestResult.Error<>(400, "The request could not be parsed.");
            }
            case ParseResult.Parsed parsed -> resolveFileName(parsed.request().fileName());
        };
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
     * mirroring the Kotlin example's wording and the existing handler's messages. Missing and
     * too-long names get distinct messages; every path-safety rejection collapses to a single
     * generic message so the response does not disclose which specific rule tripped.
     */
    private static String messageFor(ValidationResult.Reason reason) {
        return switch (reason) {
            case MISSING -> "The file name is missing.";
            case TOO_LONG -> "The file name is invalid.";
            case ILLEGAL_CHARACTER, PATH_SEPARATOR, PARENT_DIR, ABSOLUTE_PATH -> "The file name was rejected.";
        };
    }
}
