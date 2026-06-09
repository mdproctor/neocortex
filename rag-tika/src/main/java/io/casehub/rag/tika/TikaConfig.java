package io.casehub.rag.tika;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "casehub.rag.tika")
public interface TikaConfig {

    @WithDefault("512")
    int chunkSize();

    @WithDefault("64")
    int chunkOverlap();
}
