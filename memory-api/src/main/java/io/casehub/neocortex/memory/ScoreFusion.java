package io.casehub.neocortex.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

public final class ScoreFusion {

    public record ScoredLeg<T>(List<T> items, ToDoubleFunction<T> scoreExtractor, double weight) {}

    public record FusedResult<T>(T item, double score) {}

    private ScoreFusion() {}

    public static <T> List<FusedResult<T>> rrf(
            List<ScoredLeg<T>> legs,
            Function<T, String> idExtractor,
            int topK,
            double k) {
        if (legs.isEmpty()) return List.of();

        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, T> items = new LinkedHashMap<>();

        for (ScoredLeg<T> leg : legs) {
            List<T> sorted = new ArrayList<>(leg.items());
            sorted.sort(Comparator.comparingDouble(leg.scoreExtractor()).reversed());
            for (int rank = 0; rank < sorted.size(); rank++) {
                T item = sorted.get(rank);
                String id = idExtractor.apply(item);
                scores.merge(id, 1.0 / (k + rank + 1), Double::sum);
                items.putIfAbsent(id, item);
            }
        }

        double maxScore = (double) legs.size() / (k + 1);

        List<Map.Entry<String, Double>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort(Map.Entry.<String, Double>comparingByValue().reversed());

        List<FusedResult<T>> result = new ArrayList<>(Math.min(sorted.size(), topK));
        for (int i = 0; i < Math.min(sorted.size(), topK); i++) {
            Map.Entry<String, Double> entry = sorted.get(i);
            double normalized = maxScore > 0 ? entry.getValue() / maxScore : 0.0;
            result.add(new FusedResult<>(items.get(entry.getKey()), normalized));
        }
        return List.copyOf(result);
    }

    public static <T> List<FusedResult<T>> convexCombination(
            List<ScoredLeg<T>> legs,
            Function<T, String> idExtractor,
            int topK) {
        if (legs.isEmpty()) return List.of();

        double totalWeight = legs.stream().mapToDouble(ScoredLeg::weight).sum();
        if (totalWeight <= 0) return List.of();

        Map<String, Double> composites = new LinkedHashMap<>();
        Map<String, T> items = new LinkedHashMap<>();

        for (ScoredLeg<T> leg : legs) {
            double normalizedWeight = leg.weight() / totalWeight;
            double min = leg.items().stream()
                .mapToDouble(leg.scoreExtractor()).min().orElse(0);
            double max = leg.items().stream()
                .mapToDouble(leg.scoreExtractor()).max().orElse(0);
            double range = max - min;

            for (T item : leg.items()) {
                String id = idExtractor.apply(item);
                double raw = leg.scoreExtractor().applyAsDouble(item);
                double norm = range > 0 ? (raw - min) / range : 1.0;
                composites.merge(id, normalizedWeight * norm, Double::sum);
                items.putIfAbsent(id, item);
            }
        }

        List<Map.Entry<String, Double>> sorted = new ArrayList<>(composites.entrySet());
        sorted.sort(Map.Entry.<String, Double>comparingByValue().reversed());

        List<FusedResult<T>> result = new ArrayList<>(Math.min(sorted.size(), topK));
        for (int i = 0; i < Math.min(sorted.size(), topK); i++) {
            Map.Entry<String, Double> entry = sorted.get(i);
            result.add(new FusedResult<>(items.get(entry.getKey()), entry.getValue()));
        }
        return List.copyOf(result);
    }
}
