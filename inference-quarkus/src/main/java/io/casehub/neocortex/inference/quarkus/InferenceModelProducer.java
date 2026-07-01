package io.casehub.neocortex.inference.quarkus;

import io.casehub.neocortex.inference.InferenceModel;
import io.casehub.neocortex.inference.runtime.ModelConfig;
import io.casehub.neocortex.inference.runtime.OnnxInferenceModel;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class InferenceModelProducer {

    private final InferenceModelConfig config;
    private final ConcurrentHashMap<String, InferenceModel> models = new ConcurrentHashMap<>();

    @Inject
    public InferenceModelProducer(InferenceModelConfig config) {
        this.config = config;
    }

    @Produces
    @DefaultBean
    @Inference("")
    @Dependent
    InferenceModel produce(InjectionPoint ip) {
        String name = extractName(ip);
        return models.computeIfAbsent(name, this::createModel);
    }

    void shutdown(@Observes io.quarkus.runtime.ShutdownEvent event) {
        models.values().forEach(InferenceModel::close);
        models.clear();
    }

    private InferenceModel createModel(String name) {
        InferenceModelConfig.ModelProperties props = config.models().get(name);
        if (props == null) {
            throw new IllegalStateException(
                "No inference model configured for name '" + name
                    + "'. Add casehub.inference.models." + name
                    + ".model-path to your configuration.");
        }
        return new OnnxInferenceModel(new ModelConfig(
            Path.of(props.modelPath()),
            Path.of(props.tokenizerPath()),
            props.maxSequenceLength(),
            props.intraOpThreads(),
            props.interOpThreads()));
    }

    private static String extractName(InjectionPoint ip) {
        Inference qualifier = ip.getAnnotated().getAnnotation(Inference.class);
        if (qualifier == null) {
            throw new IllegalStateException(
                "Injection point missing @Inference qualifier: " + ip);
        }
        return qualifier.value();
    }
}
