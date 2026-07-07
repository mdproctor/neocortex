package io.casehub.neocortex.rag.tracking;

import io.casehub.neocortex.rag.RetrievalTracker;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
@IfBuildProperty(name = "casehub.rag.tracking.enabled", stringValue = "true")
public class RetentionScheduler {

    private static final Logger LOG = Logger.getLogger(RetentionScheduler.class);

    @Inject RetrievalTracker tracker;

    @ConfigProperty(name = "casehub.rag.tracking.retention.days", defaultValue = "90")
    int retentionDays;

    private ScheduledExecutorService executor;

    @PostConstruct
    void start() {
        if (retentionDays <= 0) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rag-retention-purge");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::purge, 1, 24, TimeUnit.HOURS);
        LOG.infof("Retention scheduler started: %d-day retention, purge every 24h", retentionDays);
    }

    @PreDestroy
    void stop() {
        if (executor != null) executor.shutdown();
    }

    void purge() {
        try {
            Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
            int deleted = tracker.purgeOlderThan(cutoff);
            LOG.infof("Retention purge: deleted %d records older than %s", deleted, cutoff);
        } catch (Exception e) {
            LOG.error("Retention purge failed — will retry next cycle", e);
        }
    }
}
