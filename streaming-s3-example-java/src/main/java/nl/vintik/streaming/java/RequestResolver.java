package nl.vintik.streaming.java;

import java.io.InputStream;

/**
 * Resolves the incoming Lambda {@link InputStream} to a typed request {@code R}.
 *
 * <p>Implementations read and parse the incoming event (e.g. an API Gateway proxy event
 * or a Lambda Function URL payload-2.0 event), validate the input, and return either a
 * {@link RequestResult.Resolved} carrying the request object that {@code StreamHandler}
 * passes to {@code S3Source}, or a {@link RequestResult.Error} carrying an HTTP status
 * code and message that {@code StreamHandler} writes as the error response — without
 * issuing any source request.
 *
 * <p>The interface is intentionally narrow: it has no knowledge of what {@code R} means
 * or how the source uses it. Parsing, validation, and error-message wording are entirely
 * the implementor's responsibility. Java counterpart of the Kotlin example's
 * {@code RequestResolver}.
 *
 * @param <R> the resolved domain request type
 */
@FunctionalInterface
public interface RequestResolver<R> {

    /**
     * Reads and resolves the incoming event into a typed request or an error.
     *
     * @param input the raw Lambda event input stream
     * @return {@link RequestResult.Resolved} on success, otherwise {@link RequestResult.Error}
     */
    RequestResult<R> resolve(InputStream input);
}
