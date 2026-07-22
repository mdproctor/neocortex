package io.casehub.neocortex.memory.cbr;

import java.util.Objects;

public record CbrEnsembleRecorded(EnsembleTrace trace) {
    public CbrEnsembleRecorded {
        Objects.requireNonNull(trace, "trace");
    }
}
