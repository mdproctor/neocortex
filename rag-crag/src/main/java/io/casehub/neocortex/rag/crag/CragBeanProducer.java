package io.casehub.neocortex.rag.crag;

import io.casehub.neocortex.inference.tasks.CrossEncoderReranker;
import io.casehub.neocortex.rag.RelevanceEvaluator;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
@IfBuildProperty(name = "casehub.rag.crag.enabled", stringValue = "true")
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
