package io.casehub.inference.quarkus;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Map;
import java.util.Optional;

@ConfigMapping(prefix = "casehub.inference")
public interface InferenceModelConfig {

    Map<String, ModelProperties> models();

    interface ModelProperties {

        String modelPath();

        String tokenizerPath();

        @WithDefault("512")
        int maxSequenceLength();

        @WithDefault("0")
        int intraOpThreads();

        @WithDefault("0")
        int interOpThreads();
    }
}
