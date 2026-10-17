package nl.vintik.streaming.java;

/**
 * Outcome of resolving a typed request from an incoming Lambda event.
 *
 * <p>{@link Resolved} carries the request object {@code R} to pass to {@code S3Source}.
 * {@link Error} carries an HTTP status code and message to return immediately, without
 * issuing any source request. Java counterpart of the Kotlin example's
 * {@code RequestResult}.
 *
 * @param <R> the resolved domain request type
 */
public sealed interface RequestResult<R> permits RequestResult.Resolved, RequestResult.Error {

    /**
     * The event was parsed and validated successfully.
     *
     * @param request the resolved domain request
     * @param <R>     the resolved domain request type
     */
    record Resolved<R>(R request) implements RequestResult<R> {}

    /**
     * The event could not be resolved; the handler writes this status and message as the
     * error response without issuing any source request.
     *
     * @param statusCode the HTTP status code to return (e.g. 400)
     * @param message    the human-readable error message
     * @param <R>        the resolved domain request type
     */
    record Error<R>(int statusCode, String message) implements RequestResult<R> {}
}
