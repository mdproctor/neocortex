package io.casehub.neocortex.corpus.management.cdi;

import io.casehub.neocortex.corpus.management.CorpusCatalogue;
import io.casehub.neocortex.corpus.management.CorpusDescriptor;
import io.casehub.neocortex.corpus.management.CorpusLifecycle;
import io.casehub.neocortex.corpus.management.CorpusType;
import io.casehub.neocortex.corpus.management.HealthReport;
import io.casehub.neocortex.rag.CorpusRef;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@DefaultBean
@ApplicationScoped
public class NoOpCorpusCatalogue implements CorpusCatalogue {

    @Override
    public List<CorpusDescriptor> list(String tenantId, CorpusLifecycle status) {
        return List.of();
    }

    @Override
    public Optional<CorpusDescriptor> get(CorpusRef corpus) {
        return Optional.empty();
    }

    @Override
    public List<CorpusDescriptor> forType(CorpusType type, String tenantId) {
        return List.of();
    }

    @Override
    public List<CorpusDescriptor> searchScope(CorpusRef corpus) {
        return List.of();
    }

    @Override
    public HealthReport reconcile(String tenantId) {
        return HealthReport.HEALTHY;
    }
}
