package io.casehub.neocortex.inference.splade;

import io.casehub.neocortex.inference.InferenceInput;
import io.casehub.neocortex.inference.InferenceModel;
import io.casehub.neocortex.inference.InferenceOutput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SparseEmbedder {

    private static final float DEFAULT_THRESHOLD = 0.01f;

    private final InferenceModel model;
    private final float threshold;

    public SparseEmbedder(InferenceModel model) {
        this(model, DEFAULT_THRESHOLD);
    }

    public SparseEmbedder(InferenceModel model, float threshold) {
        if (model == null) throw new IllegalArgumentException("model must not be null");
        if (threshold <= 0 || !Float.isFinite(threshold)) {
            throw new IllegalArgumentException(
                "threshold must be positive and finite, got: " + threshold);
        }
        model.outputSize().orElseThrow(() -> new IllegalArgumentException(
            "model outputSize must be present for SparseEmbedder (SPLADE vocab size is fixed)"));
        this.model = model;
        this.threshold = threshold;
    }

    public Map<Integer, Float> embed(String text) {
        if (text == null) throw new IllegalArgumentException("text must not be null");
        InferenceOutput output = model.run(InferenceInput.of(text));
        return logSaturate(output.values());
    }

    public List<Map<Integer, Float>> embedBatch(List<String> texts) {
        if (texts == null) throw new IllegalArgumentException("texts must not be null");
        if (texts.isEmpty()) return List.of();
        for (int i = 0; i < texts.size(); i++) {
            if (texts.get(i) == null) {
                throw new IllegalArgumentException("texts[" + i + "] must not be null");
            }
        }

        List<InferenceInput> inputs = new ArrayList<>(texts.size());
        for (String text : texts) {
            inputs.add(InferenceInput.of(text));
        }

        List<InferenceOutput> outputs = model.runBatch(inputs);
        List<Map<Integer, Float>> results = new ArrayList<>(outputs.size());
        for (InferenceOutput output : outputs) {
            results.add(logSaturate(output.values()));
        }
        return Collections.unmodifiableList(results);
    }

    private Map<Integer, Float> logSaturate(float[] values) {
        Map<Integer, Float> sparse = new HashMap<>();
        for (int i = 0; i < values.length; i++) {
            float activated = Math.max(0f, values[i]);
            float weight = (float) Math.log1p(activated);
            if (weight >= threshold) {
                sparse.put(i, weight);
            }
        }
        return Map.copyOf(sparse);
    }
}
