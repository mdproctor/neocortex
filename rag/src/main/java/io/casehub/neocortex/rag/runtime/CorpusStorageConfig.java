package io.casehub.neocortex.rag.runtime;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Map;

@ConfigMapping(prefix = "casehub.corpus")
public interface CorpusStorageConfig {

    Map<String, CorpusInstanceConfig> corpora();

    interface CorpusInstanceConfig {

        String source();

        @WithDefault("FLAT")
        String mode();

        @WithDefault("104857600")
        long maxZipSize();
    }
}
