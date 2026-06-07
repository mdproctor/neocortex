package io.casehub.inference;

import java.util.List;
import java.util.Objects;

/**
 * Immutable input for inference — either a single text or a text pair
 * (premise/hypothesis for NLI, query/document for cross-encoder).
 */
public record InferenceInput(List<String> texts) {

    public InferenceInput {
        if (texts == null || texts.isEmpty())
            throw new IllegalArgumentException("texts must not be empty");
        if (texts.size() > 2)
            throw new IllegalArgumentException("at most 2 texts supported (single text or text pair)");
        texts = List.copyOf(texts);
    }

    /** Single-text input. */
    public static InferenceInput of(String text) {
        return new InferenceInput(List.of(Objects.requireNonNull(text, "text must not be null")));
    }

    /** Text-pair input (NLI premise/hypothesis, cross-encoder query/document). */
    public static InferenceInput pair(String first, String second) {
        return new InferenceInput(List.of(
            Objects.requireNonNull(first, "first must not be null"),
            Objects.requireNonNull(second, "second must not be null")));
    }
}
