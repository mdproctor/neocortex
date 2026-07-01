package io.casehub.neocortex.memory.mem0;

import io.casehub.neocortex.memory.*;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

public class Mem0WireMockResource implements QuarkusTestResourceLifecycleManager {

    static volatile WireMockServer INSTANCE;
    private WireMockServer server;

    @Override
    public Map<String, String> start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        INSTANCE = server;
        return Map.of(
            "quarkus.rest-client.mem0.url", "http://localhost:" + server.port(),
            "casehub.memory.mem0.api-key",  "test-key",
            "casehub.memory.mem0.infer",    "false"
        );
    }

    @Override
    public void stop() {
        if (server != null) server.stop();
    }
}
