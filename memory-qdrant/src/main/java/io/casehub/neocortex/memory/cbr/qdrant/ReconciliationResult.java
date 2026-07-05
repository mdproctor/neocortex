package io.casehub.neocortex.memory.cbr.qdrant;

public record ReconciliationResult(
    String caseType,
    String tenantId,
    int orphansRemoved,
    int entriesReindexed,
    int errors
) {}
