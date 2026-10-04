package io.casehub.neocortex.knowledge;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;

@ConfigMapping(prefix = "casehub.knowledge")
public interface KnowledgePipelineConfig {

    @WithDefault("5")
    int geohashPrecision();

    SqliteConfig sqlite();

    CacheConfig cache();

    ResearchConfig research();

    interface SqliteConfig {
        @WithDefault("knowledge-pipeline.db") String path();
    }

    interface CacheConfig {
        @WithDefault("P90D") Duration maxEntityAge();
        @WithDefault("P1D") Duration searchResultsTtl();
        @WithDefault("P30D") Duration coordinatesTtl();
        @WithDefault("P3D") Duration ratingTtl();
        @WithDefault("P7D") Duration contactTtl();
        @WithDefault("P7D") Duration hoursTtl();
        @WithDefault("P3D") Duration reviewsTtl();
        @WithDefault("P14D") Duration imagesTtl();
        @WithDefault("24h") Duration evictionInterval();
    }

    interface ResearchConfig {
        @WithDefault("P180D") Duration maxSessionDuration();
        ResearchSqliteConfig sqlite();
    }

    interface ResearchSqliteConfig {
        @WithDefault("knowledge-research.db") String path();
    }
}
