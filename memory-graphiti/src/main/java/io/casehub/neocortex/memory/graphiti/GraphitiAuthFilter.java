package io.casehub.neocortex.memory.graphiti;

import io.casehub.neocortex.memory.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;

import java.io.IOException;

/**
 * Adds {@code Authorization: Bearer {apiKey}} to every Graphiti REST request when
 * {@code casehub.memory.graphiti.api-key} is configured.
 *
 * <p>Must NOT be annotated {@code @Provider} — that would register it globally as a JAX-RS
 * provider, bypassing CDI injection. Register via {@code @RegisterProvider} on
 * {@link GraphitiClient} instead.
 */
@ApplicationScoped
public class GraphitiAuthFilter implements ClientRequestFilter {

    @Inject
    GraphitiConfig config;

    @Override
    public void filter(final ClientRequestContext ctx) throws IOException {
        config.apiKey().ifPresent(key ->
            ctx.getHeaders().add("Authorization", "Bearer " + key));
    }
}
