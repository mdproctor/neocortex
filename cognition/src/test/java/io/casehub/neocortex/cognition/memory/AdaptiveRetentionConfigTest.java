package io.casehub.neocortex.cognition.memory;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveRetentionConfigTest {

    @Test
    void startsWithDefault() {
        var holder = new AdaptiveRetentionConfig();
        assertThat(holder.current()).isEqualTo(RetentionConfig.DEFAULT);
    }

    @Test
    void updateSwapsConfig() {
        var holder = new AdaptiveRetentionConfig();
        var custom = new RetentionConfig(0.2, 1.0, 1.0, 0.5, 0.5);
        holder.update(custom);
        assertThat(holder.current()).isEqualTo(custom);
    }

    @Test
    void updateIsVisibleAcrossReads() {
        var holder = new AdaptiveRetentionConfig();
        var c1 = new RetentionConfig(0.15, 1.0, 1.0, 0.5, 0.5);
        var c2 = new RetentionConfig(0.25, 1.0, 1.0, 0.5, 0.5);
        holder.update(c1);
        assertThat(holder.current().retentionThreshold()).isEqualTo(0.15);
        holder.update(c2);
        assertThat(holder.current().retentionThreshold()).isEqualTo(0.25);
    }

    @Test
    void perTenantConfigIsIsolated() {
        var holder   = new AdaptiveRetentionConfig();
        var t1Config = new RetentionConfig(0.2, 1.0, 1.0, 0.5, 0.5);
        var t2Config = new RetentionConfig(0.3, 1.0, 1.0, 0.5, 0.5);
        holder.update("t1", t1Config);
        holder.update("t2", t2Config);
        assertThat(holder.current("t1")).isEqualTo(t1Config);
        assertThat(holder.current("t2")).isEqualTo(t2Config);
    }

    @Test
    void perTenantFallsBackToGlobalWhenNotSet() {
        var holder = new AdaptiveRetentionConfig();
        var global = new RetentionConfig(0.15, 1.0, 1.0, 0.5, 0.5);
        holder.update(global);
        assertThat(holder.current("unknown-tenant")).isEqualTo(global);
    }

    @Test
    void perTenantDoesNotAffectGlobal() {
        var holder = new AdaptiveRetentionConfig();
        holder.update("t1", new RetentionConfig(0.4, 1.0, 1.0, 0.5, 0.5));
        assertThat(holder.current()).isEqualTo(RetentionConfig.DEFAULT);
    }
}
