package io.casehub.neocortex.memory.cbr;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TrendAnalyzer {

    private TrendAnalyzer() {}

    public static TrendProfile analyze(List<Map<String, FeatureValue>> observations,
                                       FeatureField.TimeSeries schema) {
        TrendSpec trendSpec = schema.trendSpec();
        if (trendSpec == null) {
            return new TrendProfile(Map.of());
        }
        Set<TrendType> types = trendSpec.types();
        String tsName = schema.name();
        String timestampField = schema.timestampField();
        List<FeatureField.Numeric> scorableFields = DtwSimilarity.scorableNumericFields(schema);

        double[] timestamps = extractTimestamps(observations, timestampField);
        Map<String, Double> metrics = new LinkedHashMap<>();

        for (FeatureField.Numeric field : scorableFields) {
            double[] values = extractValues(observations, field.name());
            for (TrendType type : types) {
                if (type.isPerField()) {
                    String key = TrendFieldNaming.name(tsName, type, field.name());
                    metrics.put(key, computePerField(type, timestamps, values));
                }
            }
        }

        for (TrendType type : types) {
            if (!type.isPerField()) {
                String key = TrendFieldNaming.name(tsName, type, null);
                metrics.put(key, computePerTimeSeries(type, timestamps, observations.size()));
            }
        }

        return new TrendProfile(metrics);
    }

    public static Map<String, FeatureValue> enrichFeatures(Map<String, FeatureValue> features,
                                                           CbrFeatureSchema schema) {
        Map<String, FeatureValue> additions = null;
        for (FeatureField field : schema.fields()) {
            if (field instanceof FeatureField.TimeSeries ts && ts.trendSpec() != null) {
                FeatureValue val = features.get(ts.name());
                if (val instanceof FeatureValue.StructListVal obs) {
                    TrendProfile profile = analyze(obs.items(), ts);
                    if (!profile.metrics().isEmpty()) {
                        if (additions == null) {
                            additions = new HashMap<>(features);
                        }
                        additions.putAll(profile.toFeatures());
                    }
                }
            }
        }
        return additions != null ? Map.copyOf(additions) : features;
    }

    public static CbrFeatureSchema expandSchema(CbrFeatureSchema schema) {
        List<FeatureField> additional = new ArrayList<>();
        Set<String> existingNames = new java.util.HashSet<>();
        for (FeatureField f : schema.fields()) {
            existingNames.add(f.name());
        }

        for (FeatureField field : schema.fields()) {
            if (field instanceof FeatureField.TimeSeries ts && ts.trendSpec() != null) {
                TrendSpec spec = ts.trendSpec();
                List<FeatureField.Numeric> scorable = DtwSimilarity.scorableNumericFields(ts);

                for (TrendType type : spec.types()) {
                    if (type.isPerField()) {
                        for (FeatureField.Numeric inner : scorable) {
                            String name = TrendFieldNaming.name(ts.name(), type, inner.name());
                            if (existingNames.contains(name)) {
                                continue;
                            }
                            double[] range = heuristicRange(type, inner, spec.timeUnit());
                            additional.add(FeatureField.numeric(name, range[0], range[1]));
                            existingNames.add(name);
                        }
                    } else {
                        String name = TrendFieldNaming.name(ts.name(), type, null);
                        if (existingNames.contains(name)) {
                            continue;
                        }
                        double[] range = heuristicRangePerTs(type, spec.timeUnit());
                        additional.add(FeatureField.numeric(name, range[0], range[1]));
                        existingNames.add(name);
                    }
                }
            }
        }

        if (additional.isEmpty()) {
            return schema;
        }

        List<FeatureField> allFields = new ArrayList<>(schema.fields());
        allFields.addAll(additional);
        return new CbrFeatureSchema(schema.caseType(), allFields, schema.learningRate());
    }

    // -- Algorithm implementations --

    private static double computePerField(TrendType type, double[] timestamps, double[] values) {
        return switch (type) {
            case SLOPE -> linearSlope(timestamps, values);
            case DELTA -> delta(values);
            case VOLATILITY -> volatility(values);
            case ACCELERATION -> acceleration(timestamps, values);
            case CHANGE_POINTS -> changePoints(values);
            case DURATION, OBSERVATION_COUNT ->
                    throw new IllegalStateException("Per-TimeSeries type in per-field context: " + type);
        };
    }

    private static double computePerTimeSeries(TrendType type, double[] timestamps, int observationCount) {
        return switch (type) {
            case DURATION -> duration(timestamps);
            case OBSERVATION_COUNT -> observationCount;
            case SLOPE, DELTA, VOLATILITY, ACCELERATION, CHANGE_POINTS ->
                    throw new IllegalStateException("Per-field type in per-TimeSeries context: " + type);
        };
    }

    static double linearSlope(double[] timestamps, double[] values) {
        int n = Math.min(timestamps.length, values.length);
        if (n <= 1) {return 0.0;}
        double sumT = 0, sumV = 0, sumTV = 0, sumTT = 0;
        for (int i = 0; i < n; i++) {
            sumT += timestamps[i];
            sumV += values[i];
            sumTV += timestamps[i] * values[i];
            sumTT += timestamps[i] * timestamps[i];
        }
        double denom = n * sumTT - sumT * sumT;
        if (denom == 0.0) {return 0.0;}
        return (n * sumTV - sumT * sumV) / denom;
    }

    static double delta(double[] values) {
        if (values.length <= 1) {return 0.0;}
        return values[values.length - 1] - values[0];
    }

    static double volatility(double[] values) {
        if (values.length < 2) {return 0.0;}
        double mean = 0, m2 = 0;
        for (int i = 0; i < values.length; i++) {
            double delta = values[i] - mean;
            mean += delta / (i + 1);
            m2 += delta * (values[i] - mean);
        }
        return Math.sqrt(m2 / values.length);
    }

    static double acceleration(double[] timestamps, double[] values) {
        int n = Math.min(timestamps.length, values.length);
        if (n < 4) {return 0.0;}
        int mid = n / 2;
        double[] t1 = new double[mid];
        double[] v1 = new double[mid];
        System.arraycopy(timestamps, 0, t1, 0, mid);
        System.arraycopy(values, 0, v1, 0, mid);
        double slope1 = linearSlope(t1, v1);

        int len2 = n - mid;
        double[] t2 = new double[len2];
        double[] v2 = new double[len2];
        System.arraycopy(timestamps, mid, t2, 0, len2);
        System.arraycopy(values, mid, v2, 0, len2);
        double slope2 = linearSlope(t2, v2);

        double midTime1 = (timestamps[0] + timestamps[mid - 1]) / 2.0;
        double midTime2 = (timestamps[mid] + timestamps[n - 1]) / 2.0;
        double timeDelta = midTime2 - midTime1;
        if (timeDelta == 0.0) {return 0.0;}
        return (slope2 - slope1) / timeDelta;
    }

    static int changePoints(double[] values) {
        if (values.length < 3) {return 0;}
        double mean = 0;
        for (double v : values) {mean += v;}
        mean /= values.length;
        double variance = 0;
        for (double v : values) {variance += (v - mean) * (v - mean);}
        double stddev = Math.sqrt(variance / values.length);
        if (stddev == 0.0) {return 0;}
        double threshold = 1.5 * stddev;
        double posSum = 0, negSum = 0;
        int count = 0;
        for (double v : values) {
            double diff = v - mean;
            posSum = Math.max(0, posSum + diff);
            negSum = Math.min(0, negSum + diff);
            if (posSum > threshold || negSum < -threshold) {
                count++;
                posSum = 0;
                negSum = 0;
            }
        }
        return count;
    }

    private static double duration(double[] timestamps) {
        if (timestamps.length <= 1) {return 0.0;}
        return timestamps[timestamps.length - 1] - timestamps[0];
    }

    // -- Extraction helpers --

    private static double[] extractTimestamps(List<Map<String, FeatureValue>> observations,
                                              String timestampField) {
        double[] result = new double[observations.size()];
        for (int i = 0; i < observations.size(); i++) {
            FeatureValue val = observations.get(i).get(timestampField);
            result[i] = (val instanceof FeatureValue.NumberVal nv) ? nv.value() : 0.0;
        }
        return result;
    }

    private static double[] extractValues(List<Map<String, FeatureValue>> observations,
                                          String fieldName) {
        double[] result = new double[observations.size()];
        for (int i = 0; i < observations.size(); i++) {
            FeatureValue val = observations.get(i).get(fieldName);
            result[i] = (val instanceof FeatureValue.NumberVal nv) ? nv.value() : 0.0;
        }
        return result;
    }

    // -- Heuristic range helpers --

    private static double[] heuristicRange(TrendType type, FeatureField.Numeric inner,
                                           ChronoUnit timeUnit) {
        double span = inner.max() - inner.min();
        return switch (type) {
            case SLOPE, DELTA, ACCELERATION -> new double[]{-span, span};
            case VOLATILITY -> new double[]{0, span};
            case CHANGE_POINTS -> new double[]{0, 1000};
            case DURATION, OBSERVATION_COUNT ->
                    throw new IllegalStateException("Per-TimeSeries type in per-field range: " + type);
        };
    }

    private static double[] heuristicRangePerTs(TrendType type, ChronoUnit timeUnit) {
        return switch (type) {
            case DURATION -> new double[]{0, durationMax(timeUnit)};
            case OBSERVATION_COUNT -> new double[]{0, 1000};
            case SLOPE, DELTA, VOLATILITY, ACCELERATION, CHANGE_POINTS ->
                    throw new IllegalStateException("Per-field type in per-TimeSeries range: " + type);
        };
    }

    private static double durationMax(ChronoUnit timeUnit) {
        return Duration.ofDays(365).toSeconds() / (double) timeUnit.getDuration().getSeconds();
    }
}
