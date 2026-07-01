package io.casehub.neocortex.rag.runtime;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@ConfigMapping(prefix = "casehub.rag.ingestion")
public interface IngestionConfig {

    @WithDefault("30s")
    Duration interval();

    @WithDefault("${java.io.tmpdir}/casehub-ingestion-cursors")
    String cursorDir();

    Map<String, CorpusIngestionConfig> corpora();

    interface CorpusIngestionConfig {
        @WithDefault("AUTO")
        IngestionMode mode();

        String tenantId();
        String corpusName();

        @WithDefault("none")
        String chunking();

        Optional<Integer> chunkingMaxSize();
        Optional<Integer> chunkingOverlapSize();
    }
}
