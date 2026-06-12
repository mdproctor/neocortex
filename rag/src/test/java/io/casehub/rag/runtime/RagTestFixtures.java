package io.casehub.rag.runtime;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.casehub.platform.api.identity.CurrentPrincipal;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class RagTestFixtures {

    private RagTestFixtures() {}

    static CurrentPrincipal stubPrincipal(String tenantId) {
        return new CurrentPrincipal() {
            @Override public String actorId() { return "test-actor"; }
            @Override public Set<String> groups() { return Set.of(); }
            @Override public String tenancyId() { return tenantId; }
            @Override public boolean isCrossTenantAdmin() { return false; }
        };
    }

    static final class StubEmbeddingModel implements EmbeddingModel {

        private final int dim;

        StubEmbeddingModel(int dim) {
            this.dim = dim;
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            List<Embedding> embeddings = new ArrayList<>(segments.size());
            float[] vec = new float[dim];
            for (int i = 0; i < dim; i++) vec[i] = 0.1f;
            for (int i = 0; i < segments.size(); i++) {
                embeddings.add(Embedding.from(vec));
            }
            return Response.from(embeddings);
        }

        @Override
        public int dimension() {
            return dim;
        }
    }
}
