package io.casehub.neocortex.memory.cbr;

import java.util.List;
import java.util.Objects;

public record DtwResult(double score, List<AlignmentPair> alignment) {
    public DtwResult {
        Objects.requireNonNull(alignment, "alignment");
        alignment = List.copyOf(alignment);
    }
}
