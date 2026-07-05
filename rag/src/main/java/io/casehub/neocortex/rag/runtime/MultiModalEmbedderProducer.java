package io.casehub.neocortex.rag.runtime;

import dev.langchain4j.model.embedding.EmbeddingModel;
import io.casehub.neocortex.inference.MultiModalEmbedder;
import io.casehub.neocortex.inference.splade.SparseEmbedder;
import io.quarkus.arc.DefaultBean;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

/**
 * CDI producer for MultiModalEmbedder. Creates SeparateModelEmbedder by composing
 * EmbeddingModel (dense, required) and SparseEmbedder (sparse, optional).
 * <p>
 * Displaced by higher-priority beans (e.g., native BGE-M3 producer) when present.
 */
@ApplicationScoped
public class MultiModalEmbedderProducer {

    @Inject RagConfig config;

    @Produces
    @DefaultBean
    @ApplicationScoped
    @IfBuildProperty(name = "casehub.rag.embedder.enabled", stringValue = "true",
                     enableIfMissing = true)
    MultiModalEmbedder separateModelEmbedder(
            Instance<EmbeddingModel> denseModel,
            Instance<SparseEmbedder> sparseEmbedder) {
        if (denseModel.isUnsatisfied()) {
            throw new IllegalStateException(
                "No EmbeddingModel available — provide a LangChain4j EmbeddingModel bean "
                + "or configure BGE-M3. Set casehub.rag.embedder.enabled=false to disable RAG.");
        }
        int maxSeqLen = config.maxSequenceLength().orElse(512);
        if (sparseEmbedder.isResolvable()) {
            return new SeparateModelEmbedder(denseModel.get(), sparseEmbedder.get(), maxSeqLen);
        }
        return new SeparateModelEmbedder(denseModel.get(), maxSeqLen);
    }
}
