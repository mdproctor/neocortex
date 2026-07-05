package io.casehub.neocortex.memory.cbr.qdrant;

/**
 * Shared constants for CBR case attributes stored in CaseMemoryStore.
 * Used by both {@link CbrMemorySerializer} and {@link CbrMemoryDeserializer}
 * to ensure consistent attribute keys.
 */
public final class CbrAttributeKeys {
    public static final String CBR_TYPE = "cbr.type";
    public static final String CBR_FEATURES = "cbr.features";
    public static final String CBR_PLAN_TRACE = "cbr.planTrace";
    public static final String CBR_CASE_TYPE = "cbr.caseType";
    private CbrAttributeKeys() {}
}
