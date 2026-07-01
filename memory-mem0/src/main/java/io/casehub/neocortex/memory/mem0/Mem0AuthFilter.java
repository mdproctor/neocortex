package io.casehub.neocortex.memory.mem0;

import io.casehub.neocortex.memory.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;

// @Provider intentionally absent — see GE-20260530-385dbb.
// @Provider causes Quarkus to instantiate the filter directly, bypassing CDI injection.
// Use @RegisterProvider on the client interface instead.
@ApplicationScoped
public class Mem0AuthFilter implements ClientRequestFilter {

    @Inject Mem0Config config;

    @Override
    public void filter(ClientRequestContext ctx) {
        ctx.getHeaders().putSingle("Authorization", "Bearer " + config.apiKey());
    }
}
