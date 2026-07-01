package io.casehub.neocortex.rag;

/**
 * Carries both the original query and optional pre-retrieval expansion text.
 * {@code expandedText} covers any pre-retrieval query transformation —
 * HyDE hypothetical documents, step-back prompts, template expansions.
 * Not related to CRAG's result-set expansion (higher top-K).
 */
public record RetrievalQuery(String text, String expandedText) {

    public RetrievalQuery {
        if (text == null || text.isBlank())
            throw new IllegalArgumentException("text must not be null or blank");
        if (expandedText != null && expandedText.isBlank())
            throw new IllegalArgumentException("expandedText must not be blank when provided");
    }

    public static RetrievalQuery of(String text) {
        return new RetrievalQuery(text, null);
    }

    public String searchText() {
        return expandedText != null ? expandedText : text;
    }

    public RetrievalQuery withExpansion(String expandedText) {
        return new RetrievalQuery(text, expandedText);
    }
}
