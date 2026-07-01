package io.casehub.neocortex.inference.runtime;

import io.casehub.neocortex.inference.InferenceException;

/**
 * Thrown when an ONNX model or tokenizer cannot be loaded — missing files,
 * unexpected input/output schema, or JNI initialization failure.
 */
public class ModelLoadException extends InferenceException {

    public ModelLoadException(String message) {
        super(message);
    }

    public ModelLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
