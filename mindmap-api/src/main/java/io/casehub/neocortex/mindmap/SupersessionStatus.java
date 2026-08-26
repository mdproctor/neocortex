package io.casehub.neocortex.mindmap;

import java.time.Instant;

public record SupersessionStatus(
    String targetId,
    boolean superseded,
    Instant supersededAt,
    String supersedingId,
    String reason,
    Instant reinstatedAt
) {

    public static final SupersessionStatus NOT_SUPERSEDED =
        new SupersessionStatus(null, false, null, null, null, null);

    public boolean wasReinstated() {
        return reinstatedAt != null;
    }
}
