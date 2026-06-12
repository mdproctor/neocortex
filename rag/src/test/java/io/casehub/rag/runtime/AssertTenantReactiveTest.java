package io.casehub.rag.runtime;

import io.casehub.rag.ChunkInput;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.ReactiveCaseRetriever;
import io.casehub.rag.ReactiveEmbeddingIngestor;
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

        Uni<?> uni = retriever.retrieve("query", WRONG_TENANT_CORPUS, 10);

        uni.subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitFailure()
            .assertFailedWith(SecurityException.class);
    }

    private ReactiveQdrantEmbeddingIngestor createIngestor() {
        return new ReactiveQdrantEmbeddingIngestor(
            null, null, null,
            TenancyStrategy.SEPARATE_COLLECTIONS,
            "dense", "sparse", 4,
            RagTestFixtures.stubPrincipal(TENANT));
    }

    private ReactiveHybridCaseRetriever createRetriever() {
        return new ReactiveHybridCaseRetriever(
            null, null, null,
            TenancyStrategy.SEPARATE_COLLECTIONS,
            "dense", "sparse",
            64, 64, 60,
            false, 10, null,
            RagTestFixtures.stubPrincipal(TENANT));
    }
}
