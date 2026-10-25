package io.casehub.neocortex.memory.seeding.biography;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EntityEnrichmentEntry(
    String id,
    @JsonProperty("entity_ref") String entityRef,
    @JsonProperty("drive_baselines") Map<String, DriveBaseline> driveBaselines,
    @JsonProperty("caps_patterns") List<CapsPattern> capsPatterns,
    @JsonProperty("behavioural_attractors") List<BehaviouralAttractor> behaviouralAttractors,
    @JsonProperty("relationship_stages") List<RelationshipStage> relationshipStages
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DriveBaseline(Double baseline, String justification) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CapsPattern(
        String pattern,
        Double frequency,
        Double confidence,
        @JsonProperty("input_activations") Map<String, Double> inputActivations
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BehaviouralAttractor(String name, String description, String trigger, String response,
                                       Double strength) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RelationshipStage(
        @JsonProperty("other_agent") String otherAgent,
        List<Transition> transitions
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Transition(String from, String to, String period, String trigger) {}
}
