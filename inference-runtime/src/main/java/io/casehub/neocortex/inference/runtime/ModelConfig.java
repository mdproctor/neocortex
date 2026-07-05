package io.casehub.neocortex.inference.runtime;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration for loading an ONNX model with its tokenizer.
 *
 * @param modelPath        path to the ONNX model file
 * @param tokenizerPath    path to the HuggingFace tokenizer.json
 * @param maxSequenceLength maximum token sequence length (truncation boundary)
 * @param intraOpThreads   ONNX Runtime intra-op parallelism (0 = ORT default)
 * @param interOpThreads   ONNX Runtime inter-op parallelism (0 = ORT default)
 * @param inputNameOverrides explicit mapping from canonical names to model-specific input names (nullable)
 */
public record ModelConfig(
    Path modelPath,
    Path tokenizerPath,
    int maxSequenceLength,
    int intraOpThreads,
    int interOpThreads,
    Map<String, String> inputNameOverrides
) {

    public ModelConfig {
        Objects.requireNonNull(modelPath, "modelPath must not be null");
        Objects.requireNonNull(tokenizerPath, "tokenizerPath must not be null");
        if (maxSequenceLength <= 0)
            throw new IllegalArgumentException("maxSequenceLength must be positive");
        if (intraOpThreads < 0)
            throw new IllegalArgumentException("intraOpThreads must be non-negative");
        if (interOpThreads < 0)
            throw new IllegalArgumentException("interOpThreads must be non-negative");
        inputNameOverrides = inputNameOverrides == null ? null : Map.copyOf(inputNameOverrides);
    }

    /** Two-arg convenience: 512 max length, ORT-default threading. */
    public ModelConfig(Path modelPath, Path tokenizerPath) {
        this(modelPath, tokenizerPath, 512, 0, 0, null);
    }

    /** Three-arg convenience: custom max length, ORT-default threading. */
    public ModelConfig(Path modelPath, Path tokenizerPath, int maxSequenceLength) {
        this(modelPath, tokenizerPath, maxSequenceLength, 0, 0, null);
    }
}
