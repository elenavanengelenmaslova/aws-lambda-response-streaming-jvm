package nl.vintik.streaming.bedrock;

/** The model stream produced no terminal signal before the {@link Deadline}. */
final class ModelStreamTimeoutException extends ModelStreamException {

    private static final long serialVersionUID = 1L;

    ModelStreamTimeoutException(String message) {
        super(message);
    }
}
