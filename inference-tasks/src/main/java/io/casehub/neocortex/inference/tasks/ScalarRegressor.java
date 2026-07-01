package io.casehub.neocortex.inference.tasks;

import io.casehub.neocortex.inference.InferenceException;
import io.casehub.neocortex.inference.InferenceInput;
import io.casehub.neocortex.inference.InferenceModel;
import io.casehub.neocortex.inference.InferenceOutput;

public final class ScalarRegressor {

    private static final int EXPECTED_SIZE = 1;

    private final InferenceModel model;

    public ScalarRegressor(final InferenceModel model) {
        if (model == null) throw new IllegalArgumentException("model must not be null");
        model.outputSize().ifPresent(size -> {
            if (size != EXPECTED_SIZE) {
                throw new IllegalArgumentException(
                    "ScalarRegressor requires outputSize " + EXPECTED_SIZE + ", got " + size);
            }
        });
        this.model = model;
    }

    public float predict(final String text) {
        if (text == null) throw new IllegalArgumentException("text must not be null");

        final InferenceOutput output = model.run(InferenceInput.of(text));
        final float[] values = output.values();

        if (values.length != EXPECTED_SIZE) {
            throw new InferenceException(
                "Expected " + EXPECTED_SIZE + " output values, got " + values.length);
        }

        return values[0];
    }
}
