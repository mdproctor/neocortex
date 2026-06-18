package io.casehub.rag;

public record RetrievalQuality(
    int totalRetrieved,
    int totalCorrect,
    int totalAmbiguous,
    int totalIncorrect,
    boolean evaluated,
    boolean expandedSearch
) {
    public static final RetrievalQuality NONE =
        new RetrievalQuality(0, 0, 0, 0, false, false);
}
