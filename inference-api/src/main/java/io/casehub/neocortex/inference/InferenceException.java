package io.casehub.neocortex.inference;

/**
 * Unchecked exception thrown by inference operations — model loading
 * failures, post-close calls, malformed inputs that survive validation.
 */
public class InferenceException extends RuntimeException {

    public InferenceException(String message) {
        super(message);
    }

    public InferenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
