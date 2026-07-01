package io.casehub.neocortex.examples.rag;

import io.casehub.platform.api.identity.CurrentPrincipal;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.Set;

@Alternative
@Priority(1)
@ApplicationScoped
public class TestCurrentPrincipal implements CurrentPrincipal {

    @Override
    public String actorId() {
        return "test-actor";
    }

    @Override
    public Set<String> groups() {
        return Set.of();
    }

    @Override
    public String tenancyId() {
        return "demo-tenant";
    }

    @Override
    public boolean isCrossTenantAdmin() {
        return false;
    }
}
