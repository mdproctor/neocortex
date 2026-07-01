package io.casehub.neocortex.corpus.zip;

import java.time.LocalDate;
import java.util.Map;

public record ChainEntry(
    String uuid,
    String file,
    int sequence,
    String status,
    String predecessor,
    int entryCount,
    int cumulativeEntryCount,
    String contentHash,
    Map<String, Integer> domains,
    LocalDate earliest,
    LocalDate latest,
    String replacedBy
) {
    public ChainEntry {
        if (uuid == null || uuid.isBlank())
            throw new IllegalArgumentException("uuid must not be null or blank");
        if (file == null || file.isBlank())
            throw new IllegalArgumentException("file must not be null or blank");
        if (status == null || status.isBlank())
            throw new IllegalArgumentException("status must not be null or blank");
        if (sequence < 0)
            throw new IllegalArgumentException("sequence must be >= 0");
        domains = domains == null ? Map.of() : Map.copyOf(domains);
    }
}
