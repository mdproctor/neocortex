package io.casehub.neocortex.memory.graphiti;

import io.casehub.neocortex.memory.*;
import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

public class KnownDomainsTestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
            "casehub.memory.graphiti.known-domains", "investigation",
            "quarkus.rest-client.graphiti.url", "http://localhost:39201"
        );
    }
}
