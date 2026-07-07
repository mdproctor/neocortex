package io.casehub.neocortex.memory.cbr;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public sealed interface SimilaritySpec {

    record CategoricalTable(Map<String, Map<String, Double>> similarities) implements SimilaritySpec {
        public CategoricalTable {
            Objects.requireNonNull(similarities, "similarities");
            similarities = mirrorAndValidate(similarities);
        }

        private static Map<String, Map<String, Double>> mirrorAndValidate(
                Map<String, Map<String, Double>> input) {
            Map<String, Map<String, Double>> result = new HashMap<>();
            Set<String> seen = new TreeSet<>();

            for (var outer : input.entrySet()) {
                for (var inner : outer.getValue().entrySet()) {
                    String a = outer.getKey();
                    String b = inner.getKey();
                    double score = inner.getValue();

                    if (score < 0.0 || score > 1.0) {
                        throw new IllegalArgumentException(
                            "Score for (" + a + ", " + b + ") must be in [0, 1], got: " + score);
                    }
                    if (a.equals(b)) continue;

                    String pairKey = a.compareTo(b) < 0 ? a + "\0" + b : b + "\0" + a;
                    if (seen.contains(pairKey)) {
                        double existing = result
                            .getOrDefault(a, Map.of()).getOrDefault(b,
                            result.getOrDefault(b, Map.of()).getOrDefault(a, Double.NaN));
                        if (existing != score) {
                            throw new IllegalArgumentException(
                                "Conflicting scores for (" + a + ", " + b + "): " + existing + " vs " + score);
                        }
                        continue;
                    }
                    seen.add(pairKey);

                    result.computeIfAbsent(a, k -> new HashMap<>()).put(b, score);
                    result.computeIfAbsent(b, k -> new HashMap<>()).put(a, score);
                }
            }

            Map<String, Map<String, Double>> immutable = new HashMap<>();
            for (var e : result.entrySet()) {
                immutable.put(e.getKey(), Collections.unmodifiableMap(e.getValue()));
            }
            return Collections.unmodifiableMap(immutable);
        }
    }

    record GaussianDecay(double sigma) implements SimilaritySpec {
        public GaussianDecay {
            if (sigma <= 0) throw new IllegalArgumentException("sigma must be > 0, got: " + sigma);
        }
    }

    record StepDecay(double tolerance) implements SimilaritySpec {
        public StepDecay {
            if (tolerance < 0 || tolerance > 1)
                throw new IllegalArgumentException("tolerance must be in [0, 1], got: " + tolerance);
        }
    }

    record ExponentialDecay(double decayRate) implements SimilaritySpec {
        public ExponentialDecay {
            if (decayRate <= 0) throw new IllegalArgumentException("decayRate must be > 0, got: " + decayRate);
        }
    }

    static CategoricalTableBuilder categoricalTableBuilder() {
        return new CategoricalTableBuilder();
    }

    final class CategoricalTableBuilder {
        private final Map<String, Map<String, Double>> entries = new HashMap<>();
        private final Set<String> pairKeys = new TreeSet<>();

        private CategoricalTableBuilder() {}

        public CategoricalTableBuilder add(String a, String b, double score) {
            Objects.requireNonNull(a, "a");
            Objects.requireNonNull(b, "b");
            if (score < 0.0 || score > 1.0) {
                throw new IllegalArgumentException(
                    "Score for (" + a + ", " + b + ") must be in [0, 1], got: " + score);
            }
            if (a.equals(b)) return this;

            String pairKey = a.compareTo(b) < 0 ? a + "\0" + b : b + "\0" + a;
            if (!pairKeys.add(pairKey)) {
                throw new IllegalArgumentException(
                    "Pair (" + a + ", " + b + ") already registered");
            }

            entries.computeIfAbsent(a, k -> new HashMap<>()).put(b, score);
            entries.computeIfAbsent(b, k -> new HashMap<>()).put(a, score);
            return this;
        }

        public CategoricalTable build() {
            Map<String, Map<String, Double>> immutable = new HashMap<>();
            for (var e : entries.entrySet()) {
                immutable.put(e.getKey(), Collections.unmodifiableMap(new HashMap<>(e.getValue())));
            }
            return new CategoricalTable(Collections.unmodifiableMap(immutable));
        }
    }
}
