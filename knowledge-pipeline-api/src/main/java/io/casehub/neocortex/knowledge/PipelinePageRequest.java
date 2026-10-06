package io.casehub.neocortex.knowledge;

public record PipelinePageRequest(String cursor, int pageSize) {
    public PipelinePageRequest {
        if (pageSize <= 0) throw new IllegalArgumentException("pageSize must be positive");
    }

    public static PipelinePageRequest first(int pageSize) {
        return new PipelinePageRequest(null, pageSize);
    }
}
