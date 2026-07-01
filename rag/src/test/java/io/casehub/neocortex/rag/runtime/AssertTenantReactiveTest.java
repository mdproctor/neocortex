package io.casehub.neocortex.rag.runtime;

import io.casehub.neocortex.rag.ChunkInput;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.ReactiveCaseRetriever;
import io.casehub.neocortex.rag.ReactiveEmbeddingIngestor;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AssertTenantReactiveTest {

    private static final String TENANT = "tenant-1";
    private static final CorpusRef WRONG_TENANT_CORPUS = new CorpusRef("other-tenant", "corpus");

    @Test
    void ingestorIngestDeliversSecurityExceptionThroughUniFailureChannel() {
        ReactiveEmbeddingIngestor ingestor = createIngestor();

        Uni<Void> uni = ingestor.ingest(WRONG_TENANT_CORPUS, List.of(
            new ChunkInput("text", "doc-1", Map.of())));

        uni.subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitFailure()
            .assertFailedWith(SecurityException.class);
    }

    @Test
    void ingestorDeleteDocumentDeliversSecurityExceptionThroughUniFailureChannel() {
        ReactiveEmbeddingIngestor ingestor = createIngestor();

        Uni<Void> uni = ingestor.deleteDocument(WRONG_TENANT_CORPUS, "doc-1");

        uni.subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitFailure()
            .assertFailedWith(SecurityException.class);
    }

    @Test
    void ingestorDeleteCorpusDeliversSecurityExceptionThroughUniFailureChannel() {
        ReactiveEmbeddingIngestor ingestor = createIngestor();

        Uni<Void> uni = ingestor.deleteCorpus(WRONG_TENANT_CORPUS);

        uni.subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitFailure()
            .assertFailedWith(SecurityException.class);
    }

    @Test
    void ingestorListDocumentsDeliversSecurityExceptionThroughUniFailureChannel() {
        ReactiveEmbeddingIngestor ingestor = createIngestor();

        Uni<List<String>> uni = ingestor.listDocuments(WRONG_TENANT_CORPUS);

        uni.subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitFailure()
            .assertFailedWith(SecurityException.class);
    }

    @Test
    void retrieverDeliversSecurityExceptionThroughUniFailureChannel() {
        ReactiveCaseRetriever retriever = createRetriever();

        Uni<?> uni = retriever.retrieve(RetrievalQuery.of("query"), WRONG_TENANT_CORPUS, 10, null);

        uni.subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitFailure()
            .assertFailedWith(SecurityException.class);
    }

    private ReactiveQdrantEmbeddingIngestor createIngestor() {
        return new ReactiveQdrantEmbeddingIngestor(
            null, RagTestFixtures.stubEmbedder(4),
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            RagTestFixtures.stubConfig());
    }

    private ReactiveHybridCaseRetriever createRetriever() {
        return new ReactiveHybridCaseRetriever(
            null, null,
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            RagTestFixtures.stubConfig());
    }
}
