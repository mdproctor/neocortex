package io.casehub.neocortex.corpus;

import java.time.Instant;

public record VersionInfo(int version, Instant timestamp, String zipFile) {
    public VersionInfo {
        if (version < 1)
            throw new IllegalArgumentException("version must be >= 1");
        if (timestamp == null)
            throw new IllegalArgumentException("timestamp must not be null");
    }
}
