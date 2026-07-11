package io.casehub.neocortex.memory.cbr;

import java.util.List;
import java.util.Objects;

public record EditDistanceResult(double score, List<EditStep> alignment) {
    public EditDistanceResult {
        Objects.requireNonNull(alignment, "alignment");
        alignment = List.copyOf(alignment);
    }
}
