package nl.vintik.streaming.bedrock;

/** The model stream failed. Unchecked; the cause carries the underlying SDK error. */
class ModelStreamException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    ModelStreamException(String message, Throwable cause) {
        super(message, cause);
    }

    ModelStreamException(String message) {
        super(message);
    }
}
