package io.casehub.examples.rag;

import io.casehub.inference.InferenceModel;
import io.casehub.inference.quarkus.Inference;
import io.casehub.inference.splade.SparseEmbedder;
import io.casehub.inference.tasks.CrossEncoderReranker;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class ExampleModelProducer {

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
