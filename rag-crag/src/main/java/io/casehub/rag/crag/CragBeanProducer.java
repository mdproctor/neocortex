package io.casehub.rag.crag;

import io.casehub.inference.tasks.CrossEncoderReranker;
import io.casehub.rag.RelevanceEvaluator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class CragBeanProducer {

    @Inject CragConfig config;
    @Inject Instance<CrossEncoderReranker> rerankerInstance;

    @Produces
    @ApplicationScoped
    RelevanceEvaluator evaluator() {
        if (!rerankerInstance.isResolvable()) {
            throw new IllegalStateException(
                "rag-crag requires a CrossEncoderReranker bean. "
                    + "Configure casehub.inference.models.<name> and produce a CrossEncoderReranker.");
        }
        return new CrossEncoderRelevanceEvaluator(
            rerankerInstance.get(),
            config.correctThreshold(),
            config.incorrectThreshold());
    }
}
