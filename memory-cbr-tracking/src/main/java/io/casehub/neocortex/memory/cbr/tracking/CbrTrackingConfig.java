package io.casehub.neocortex.memory.cbr.tracking;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "casehub.cbr.tracking")
public interface CbrTrackingConfig {
    @WithDefault("false")
    boolean enabled();

    @WithDefault("90")
    int retentionDays();

    Sqlite sqlite();

    interface Sqlite {
        String path();

        @WithDefault("5")
        int poolMaxSize();

        @WithDefault("5000")
        int busyTimeoutMs();
    }
}
