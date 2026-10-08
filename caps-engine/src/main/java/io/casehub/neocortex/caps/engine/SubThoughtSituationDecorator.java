package io.casehub.neocortex.caps.engine;

import io.casehub.neocortex.caps.SituationActivation;
import io.casehub.neocortex.caps.SituationClassifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SubThoughtSituationDecorator implements SituationClassifier {

    private static final Map<String, List<SituationActivation>> TYPE_ACTIVATIONS = Map.ofEntries(
        Map.entry("affect-observation", List.of(
            new SituationActivation("acceptance", 0.3),
            new SituationActivation("exclusion", 0.3))),
        Map.entry("causal-inference", List.of(
            new SituationActivation("mastery", 0.4))),
        Map.entry("concern", List.of(
            new SituationActivation("social_threat", 0.6),
            new SituationActivation("psychological_threat", 0.5))),
        Map.entry("intention", List.of(
            new SituationActivation("agency_granted", 0.5),
            new SituationActivation("choice_available", 0.4))),
        Map.entry("self-reflection", List.of(
            new SituationActivation("agency_granted", 0.4))),
        Map.entry("formative-experience", List.of(
            new SituationActivation("secure_attachment", 0.5),
            new SituationActivation("neglect", 0.5),
            new SituationActivation("competence_recognition", 0.4),
            new SituationActivation("rejection", 0.4)))
    );

    private final SituationClassifier delegate;

    public SubThoughtSituationDecorator(SituationClassifier delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<SituationActivation> classify(String description, Map<String, String> metadata) {
        List<SituationActivation> base = delegate.classify(description, metadata);
        String cognitiveKind = metadata != null ? metadata.get("cognitiveKind") : null;
        if (cognitiveKind == null) return base;

        List<SituationActivation> extra = TYPE_ACTIVATIONS.getOrDefault(cognitiveKind, List.of());
        if (extra.isEmpty()) return base;

        var merged = new LinkedHashMap<String, Double>();
        for (var a : base) merged.merge(a.nodeId(), a.confidence(), Math::max);
        for (var a : extra) merged.merge(a.nodeId(), a.confidence(), Math::max);

        return merged.entrySet().stream()
                .map(e -> new SituationActivation(e.getKey(), e.getValue()))
                .toList();
    }
}
