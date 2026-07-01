package io.casehub.neocortex.inference.bgem3;

import io.casehub.neocortex.inference.*;
import java.util.*;

public final class BgeM3Embedder implements MultiModalEmbedder {

    private static final float SPARSE_THRESHOLD = 0.01f;

    private final InferenceModel model;

    public BgeM3Embedder(InferenceModel model) {
        this.model = Objects.requireNonNull(model);
    }

    @Override
    public MultiModalEmbedding embed(String text) {
        InferenceOutput output = model.run(InferenceInput.of(text));
        return toEmbedding(output);
    }

    @Override
    public List<MultiModalEmbedding> embedBatch(List<String> texts) {
        List<InferenceInput> inputs = texts.stream()
            .map(InferenceInput::of).toList();
        List<InferenceOutput> outputs = model.runBatch(inputs);
        return outputs.stream().map(this::toEmbedding).toList();
    }

    @Override
    public Set<EmbeddingMode> supportedModes() {
        return Set.of(EmbeddingMode.DENSE, EmbeddingMode.SPARSE, EmbeddingMode.COLBERT);
    }

    @Override
    public int denseDimension() { return 1024; }

    @Override
    public OptionalInt colbertDimension() { return OptionalInt.of(1024); }

    private MultiModalEmbedding toEmbedding(InferenceOutput output) {
        float[] dense = normalize(output.vector("dense"));
        Map<Integer, Float> sparse = extractSparse(output.vector("sparse"));
        float[][] colbert = normalizeRows(output.output("colbert"));
        return new MultiModalEmbedding(dense, sparse, colbert);
    }

    private static float[] normalize(float[] v) {
        double norm = 0;
        for (float f : v) norm += f * f;
        norm = Math.sqrt(norm);
        if (norm < 1e-10) return v;
        float[] result = new float[v.length];
        for (int i = 0; i < v.length; i++) result[i] = (float)(v[i] / norm);
        return result;
    }

    private Map<Integer, Float> extractSparse(float[] raw) {
        Map<Integer, Float> sparse = new HashMap<>();
        for (int i = 0; i < raw.length; i++) {
            float activated = Math.max(0f, raw[i]);
            if (activated >= SPARSE_THRESHOLD) {
                sparse.put(i, activated);
            }
        }
        return Map.copyOf(sparse);
    }

    private static float[][] normalizeRows(float[][] rows) {
        float[][] result = new float[rows.length][];
        for (int r = 0; r < rows.length; r++) {
            result[r] = normalize(rows[r]);
        }
        return result;
    }
}
