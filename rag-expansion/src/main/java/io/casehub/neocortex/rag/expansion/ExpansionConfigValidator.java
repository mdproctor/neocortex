package io.casehub.neocortex.rag.expansion;

import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.logging.Logger;

@ApplicationScoped
@IfBuildProperty(name = "casehub.rag.expansion.enabled", stringValue = "true")
public class ExpansionConfigValidator {

    private static final Logger LOG = Logger.getLogger(ExpansionConfigValidator.class.getName());

    @Inject
    ExpansionConfig config;

    void onStartup(@Observes StartupEvent event) {
        if (config.mode().isEmpty()) {
            LOG.warning("Query expansion is enabled but no mode is set"
                + " — queries will pass through unchanged."
                + " Set casehub.rag.expansion.mode to llm, template, or step-back.");
        }
    }
}
