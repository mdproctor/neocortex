package io.casehub.neocortex.memory.cbr;

import java.util.Objects;

public record ScoredCbrCase<C extends CbrCase>(C cbrCase, double score) {
    public ScoredCbrCase {
        Objects.requireNonNull(cbrCase, "cbrCase required");
    }
}
