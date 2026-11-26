/*
 * Copyright 2026-Present The Case Hub Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.casehub.neocortex.cognitive.index;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class EffectiveCognitiveDefaultsScheduler {

    private static final Logger LOG = Logger.getLogger(
            EffectiveCognitiveDefaultsScheduler.class.getName());

    private final EffectiveCognitiveDefaults defaults;
    private final long staleRefreshMinutes;
    private final long idleEvictionHours;
    private final long scanIntervalMinutes;
    private volatile ScheduledExecutorService executor;

    @Inject
    EffectiveCognitiveDefaultsScheduler(
            EffectiveCognitiveDefaults defaults,
            @ConfigProperty(name = "casehub.cognitive.defaults.stale-refresh-minutes",
                            defaultValue = "60") long staleRefreshMinutes,
            @ConfigProperty(name = "casehub.cognitive.defaults.idle-eviction-hours",
                            defaultValue = "24") long idleEvictionHours,
            @ConfigProperty(name = "casehub.cognitive.defaults.scan-interval-minutes",
                            defaultValue = "10") long scanIntervalMinutes) {
        this.defaults = defaults;
        this.staleRefreshMinutes = staleRefreshMinutes;
        this.idleEvictionHours = idleEvictionHours;
        this.scanIntervalMinutes = scanIntervalMinutes;
    }

    @PostConstruct
    void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cognitive-defaults-scheduler");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::tick, scanIntervalMinutes, scanIntervalMinutes, TimeUnit.MINUTES);
        LOG.info("Cognitive defaults scheduler started: staleRefresh=" + staleRefreshMinutes
                + "m, idleEviction=" + idleEvictionHours + "h, scanInterval=" + scanIntervalMinutes + "m");
    }

    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    LOG.warning("Cognitive defaults scheduler did not terminate within 30s");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    void tick() {
        try {
            defaults.refreshStale(Duration.ofMinutes(staleRefreshMinutes));
            defaults.evictIdle(Duration.ofHours(idleEvictionHours));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Cognitive defaults maintenance tick failed", e);
        }
    }
}
