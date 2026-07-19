package io.casehub.neocortex.memory.cbr;

import java.time.Instant;

public record SupersessionStatus(
    String caseId,
    boolean superseded,
    Instant supersededAt,
    String supersedingCaseId,
    String reason,
    Instant reinstatedAt
) {
    public static final SupersessionStatus NOT_SUPERSEDED =
        new SupersessionStatus(null, false, null, null, null, null);

    public boolean wasReinstated() { return reinstatedAt != null; }
}
