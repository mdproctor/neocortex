package io.casehub.neocortex.inference.tasks;

import io.casehub.neocortex.inference.InferenceException;
import io.casehub.neocortex.inference.InferenceInput;
import io.casehub.neocortex.inference.InferenceModel;
import io.casehub.neocortex.inference.InferenceOutput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class CrossEncoderReranker {

    private static final int EXPECTED_SIZE = 1;

    private final InferenceModel model;

    public CrossEncoderReranker(final InferenceModel model) {
        if (model == null) throw new IllegalArgumentException("model must not be null");
        model.outputSize().ifPresent(size -> {
            if (size != EXPECTED_SIZE) {
                throw new IllegalArgumentException(
                    "CrossEncoderReranker requires outputSize " + EXPECTED_SIZE + ", got " + size);
            }
        });
        this.model = model;
    }

    public float score(final String query, final String candidate) {
        if (query == null) throw new IllegalArgumentException("query must not be null");
        if (candidate == null) throw new IllegalArgumentException("candidate must not be null");

        final InferenceOutput output = model.run(InferenceInput.pair(query, candidate));
        final float[] values = output.values();

        if (values.length != EXPECTED_SIZE) {
            throw new InferenceException(
                "Expected " + EXPECTED_SIZE + " output values, got " + values.length);
        }

        return values[0];
    }

    public List<RankedResult> rerank(final String query, final List<String> candidates) {
        if (query == null) throw new IllegalArgumentException("query must not be null");
        if (candidates == null) throw new IllegalArgumentException("candidates must not be null");
        if (candidates.isEmpty())
            throw new IllegalArgumentException("candidates must not be empty");
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i) == null) {
                throw new IllegalArgumentException("candidates[" + i + "] must not be null");
            }
        }

        final List<InferenceInput> inputs = new ArrayList<>(candidates.size());
        for (final String candidate : candidates) {
            inputs.add(InferenceInput.pair(query, candidate));
        }

        final List<InferenceOutput> outputs = model.runBatch(inputs);

        final List<RankedResult> results = new ArrayList<>(candidates.size());
        for (int i = 0; i < outputs.size(); i++) {
            final float[] values = outputs.get(i).values();
            if (values.length != EXPECTED_SIZE) {
                throw new InferenceException(
                    "Expected " + EXPECTED_SIZE + " output values, got " + values.length);
            }
            results.add(new RankedResult(candidates.get(i), values[0], i));
        }

        results.sort(Comparator.comparingDouble(RankedResult::score).reversed());
        return Collections.unmodifiableList(results);
    }
}
