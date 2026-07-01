package io.casehub.neocortex.examples.rag;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.OnnxEmbeddingModel;
import dev.langchain4j.model.embedding.onnx.PoolingMode;
import io.casehub.neocortex.inference.InferenceModel;
import io.casehub.neocortex.inference.quarkus.Inference;
import io.casehub.neocortex.inference.splade.SparseEmbedder;
import io.casehub.neocortex.inference.tasks.CrossEncoderReranker;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class ExampleModelProducer {

    @Produces
    @ApplicationScoped
    EmbeddingModel embeddingModel(
            @ConfigProperty(name = "casehub.examples.embedding.model-path") String modelPath,
            @ConfigProperty(name = "casehub.examples.embedding.tokenizer-path") String tokenizerPath) {
        return new OnnxEmbeddingModel(modelPath, tokenizerPath, PoolingMode.MEAN);
    }

    @Produces
    @ApplicationScoped
    SparseEmbedder sparseEmbedder(@Inference("splade") InferenceModel model) {
        return new SparseEmbedder(model);
    }

    @Produces
    @ApplicationScoped
    CrossEncoderReranker reranker(@Inference("reranker") InferenceModel model) {
        return new CrossEncoderReranker(model);
    }
}
