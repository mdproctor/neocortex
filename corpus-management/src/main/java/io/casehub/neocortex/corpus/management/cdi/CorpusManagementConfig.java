package io.casehub.neocortex.corpus.management.cdi;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;

@ConfigMapping(prefix = "casehub.corpus-management")
public interface CorpusManagementConfig {

    SqliteConfig sqlite();

    FederationConfig federation();

    interface SqliteConfig {
        Optional<String> path();

        @WithDefault("5")
        int maxPoolSize();

        @WithDefault("5000")
        int busyTimeoutMs();
    }

    interface FederationConfig {
        @WithDefault("0.3")
        double relevanceThreshold();
    }
}
