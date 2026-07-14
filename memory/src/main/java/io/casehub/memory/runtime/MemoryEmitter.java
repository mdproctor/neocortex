package io.casehub.memory.runtime;

import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.MemoryInput;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

@ApplicationScoped
public class MemoryEmitter {

    private static final Logger LOG = Logger.getLogger(MemoryEmitter.class);

    private final CaseMemoryStore store;

    @Inject
    MemoryEmitter(CaseMemoryStore store) {
        this.store = store;
    }

    public void emit(MemoryInput input) {
        try {
            store.store(input);
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            LOG.warnf(e, "Memory emission failed for entity=%s domain=%s tenant=%s",
                input.entityId(), input.domain().name(), input.tenantId());
        }
    }

    public void emitAll(List<MemoryInput> inputs) {
        if (inputs.isEmpty()) return;
        try {
            var result = store.storeAll(inputs);
            if (!result.allSucceeded()) {
                LOG.warnf("Memory batch partial failure: %d/%d inputs failed (first entity=%s domain=%s)",
                    result.failures().size(), inputs.size(),
                    inputs.getFirst().entityId(), inputs.getFirst().domain().name());
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            LOG.warnf(e, "Memory batch emission failed (%d inputs, first entity=%s domain=%s)",
                inputs.size(), inputs.getFirst().entityId(), inputs.getFirst().domain().name());
        }
    }
}
