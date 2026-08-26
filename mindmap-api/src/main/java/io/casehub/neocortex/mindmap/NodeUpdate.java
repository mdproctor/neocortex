package io.casehub.neocortex.mindmap;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

public record NodeUpdate(
    String name,
    ConfidenceOrigin confidenceOrigin,
    Double confidence,
    Instant confirmedAt,
    Set<String> traitsToAdd,
    Set<String> traitsToRemove,
    Set<NodeRef> refsToAdd,
    Set<NodeRef> refsToRemove,
    Instant validFrom,
    Instant validUntil,
    Double pleasure,
    Double arousal,
    Double dominance,
    Map<String, String> propertiesToSet,
    Set<String> propertiesToRemove
) {

    public NodeUpdate {
        traitsToAdd = traitsToAdd == null ? Set.of() : Set.copyOf(traitsToAdd);
        traitsToRemove = traitsToRemove == null ? Set.of() : Set.copyOf(traitsToRemove);
        refsToAdd = refsToAdd == null ? Set.of() : Set.copyOf(refsToAdd);
        refsToRemove = refsToRemove == null ? Set.of() : Set.copyOf(refsToRemove);
        propertiesToSet = propertiesToSet == null ? Map.of() : Map.copyOf(propertiesToSet);
        propertiesToRemove = propertiesToRemove == null ? Set.of() : Set.copyOf(propertiesToRemove);
    }
}
