package io.casehub.neocortex.corpus;

import java.util.List;

public record IntegrityReport(
    String corpusName,
    int chainLength,
    long totalEntries,
    String status,
    List<IntegrityIssue> issues,
    List<String> recovered
) {
    public IntegrityReport {
        if (corpusName == null || corpusName.isBlank())
            throw new IllegalArgumentException("corpusName must not be null or blank");
        if (status == null || status.isBlank())
            throw new IllegalArgumentException("status must not be null or blank");
        issues = issues == null ? List.of() : List.copyOf(issues);
        recovered = recovered == null ? List.of() : List.copyOf(recovered);
    }
}
