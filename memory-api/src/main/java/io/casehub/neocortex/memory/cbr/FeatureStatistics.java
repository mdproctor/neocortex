package io.casehub.neocortex.memory.cbr;

import java.util.Arrays;

public record FeatureStatistics(double min, double max, double median, double p75, int sampleCount) {

    public static FeatureStatistics compute(double[] values) {
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException("values must not be null or empty");
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        return new FeatureStatistics(
                sorted[0],
                sorted[n - 1],
                nearestRank(sorted, 0.5),
                nearestRank(sorted, 0.75),
                n);
    }

    private static double nearestRank(double[] sorted, double rank) {
        int index = (int) Math.ceil(rank * sorted.length) - 1;
        return sorted[Math.max(0, index)];
    }
}
