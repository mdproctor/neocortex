package io.casehub.neocortex.inference.tasks;

import io.casehub.neocortex.inference.InferenceException;
import io.casehub.neocortex.inference.InferenceInput;
import io.casehub.neocortex.inference.InferenceModel;
import io.casehub.neocortex.inference.InferenceOutput;

import java.util.Set;

public final class NliClassifier {

    private static final int EXPECTED_SIZE = 3;

    private final InferenceModel model;
    private final int entailmentIndex;
    private final int neutralIndex;
    private final int contradictionIndex;

    public NliClassifier(final InferenceModel model) {
        this(model, 2, 1, 0);
    }

    public NliClassifier(final InferenceModel model,
                         final int entailmentIndex,
                         final int neutralIndex,
                         final int contradictionIndex) {
        if (model == null) throw new IllegalArgumentException("model must not be null");
        if (entailmentIndex < 0 || entailmentIndex > 2
                || neutralIndex < 0 || neutralIndex > 2
                || contradictionIndex < 0 || contradictionIndex > 2) {
            throw new IllegalArgumentException(
                "indices must be in range [0,2], got: entailment=" + entailmentIndex
                    + ", neutral=" + neutralIndex + ", contradiction=" + contradictionIndex);
        }
        if (Set.of(entailmentIndex, neutralIndex, contradictionIndex).size() != EXPECTED_SIZE) {
            throw new IllegalArgumentException(
                "indices must be distinct, got: entailment=" + entailmentIndex
                    + ", neutral=" + neutralIndex + ", contradiction=" + contradictionIndex);
        }
        model.outputSize().ifPresent(size -> {
            if (size != EXPECTED_SIZE) {
                throw new IllegalArgumentException(
                    "NliClassifier requires outputSize " + EXPECTED_SIZE + ", got " + size);
            }
        });
        this.model = model;
        this.entailmentIndex = entailmentIndex;
        this.neutralIndex = neutralIndex;
        this.contradictionIndex = contradictionIndex;
    }

    public NliResult classify(final String premise, final String hypothesis) {
        if (premise == null) throw new IllegalArgumentException("premise must not be null");
        if (hypothesis == null) throw new IllegalArgumentException("hypothesis must not be null");

        final InferenceOutput output = model.run(InferenceInput.pair(premise, hypothesis));
        final float[] values = output.values();

        if (values.length != EXPECTED_SIZE) {
            throw new InferenceException(
                "Expected " + EXPECTED_SIZE + " output values, got " + values.length);
        }

        final float[] probs = Softmax.apply(values);
        return new NliResult(probs[entailmentIndex], probs[neutralIndex], probs[contradictionIndex]);
    }
}
