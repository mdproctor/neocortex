package io.casehub.neocortex.cognition.memory;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class AdaptiveRetentionConfig implements RetentionConfigSupplier {
    private final AtomicReference<RetentionConfig>           globalConfig  =
            new AtomicReference<>(RetentionConfig.DEFAULT);
    private final ConcurrentHashMap<String, RetentionConfig> tenantConfigs =
            new ConcurrentHashMap<>();

    @Override
    public RetentionConfig current() {
        return globalConfig.get();
    }

    @Override
    public RetentionConfig current(String tenantId) {
        return tenantConfigs.getOrDefault(tenantId, globalConfig.get());
    }

    public void update(RetentionConfig newConfig) {
        globalConfig.set(newConfig);
    }

    public void update(String tenantId, RetentionConfig newConfig) {
        tenantConfigs.put(tenantId, newConfig);
    }
}
