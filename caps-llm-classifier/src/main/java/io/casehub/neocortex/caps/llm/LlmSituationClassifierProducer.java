package io.casehub.neocortex.caps.llm;

import io.casehub.neocortex.caps.CapsEngine;
import io.casehub.neocortex.caps.SituationClassifier;
import io.casehub.platform.agent.session.SessionPipeline;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class LlmSituationClassifierProducer {

    @Inject CapsEngine capsEngine;
    @Inject SessionPipeline sessionPipeline;

    @Inject
    @ConfigProperty(name = "casehub.caps.classification.model-version",
                    defaultValue = "haiku")
    String model;
    @Inject
    @ConfigProperty(name = "casehub.caps.classification.tension-threshold",
                    defaultValue = "0.5")
    double tensionThreshold;


    @Produces
    @ApplicationScoped
    public SituationClassifier produce() {
        return new LlmSituationClassifier(sessionPipeline,
                                          capsEngine.topology(),
                                          model, tensionThreshold);
    }
}
