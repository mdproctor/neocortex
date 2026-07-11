package io.casehub.neocortex.memory.cbr;

import java.util.Objects;

public record EditStep(int queryIndex, int caseIndex, EditOp operation) {
    public EditStep {
        Objects.requireNonNull(operation, "operation");
    }
}
