package io.casehub.neocortex.inference.tasks;

final class Softmax {

    private Softmax() {}

    static float[] apply(final float[] logits) {
        float max = logits[0];
        for (int i = 1; i < logits.length; i++) {
            if (logits[i] > max) max = logits[i];
        }
        float sum = 0;
        final float[] result = new float[logits.length];
        for (int i = 0; i < logits.length; i++) {
            result[i] = (float) Math.exp(logits[i] - max);
            sum += result[i];
        }
        for (int i = 0; i < logits.length; i++) {
            result[i] /= sum;
        }
        return result;
    }
}
