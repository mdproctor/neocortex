package io.casehub.neocortex.caps.llm.eval;

import io.casehub.neocortex.caps.CapsTopology;
import io.casehub.neocortex.caps.engine.CapsTopologyLoader;
import io.casehub.platform.agent.AgentBackend;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.agent.BackendInstanceRegistry;
import io.casehub.platform.agent.claude.ClaudeAgentClient;
import io.casehub.platform.agent.claude.ClaudeAgentProvider;
import io.casehub.platform.agent.claude.ClaudeAgentProperties;
import io.casehub.platform.agent.config.PoolDeclaration;
import io.casehub.platform.agent.gate.AimdAdmissionStrategy;
import io.casehub.platform.agent.session.SessionLifecycleManager;
import io.casehub.platform.agent.session.SessionPipeline;
import io.casehub.platform.agent.session.SessionPoolRegistry;
import io.smallrye.mutiny.Multi;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class EvalTestSupport {

    private static volatile ClaudeAgentClient sharedClient;
    private static volatile AgentBackend sharedBackend;

    static CapsTopology loadTopology() {
        return new CapsTopologyLoader().loadFromClasspath("caps-topology.yaml");
    }

    static AgentBackend backend() {
        if (sharedBackend == null) {
            synchronized (EvalTestSupport.class) {
                if (sharedBackend == null) {
                    ClaudeAgentProperties props = new ClaudeAgentProperties() {
                        @Override public java.util.Optional<String> binaryPath() { return java.util.Optional.empty(); }
                        @Override public java.time.Duration defaultTimeout() { return java.time.Duration.ofMinutes(5); }
                        @Override public int maxConcurrentSessions() { return 10; }
                    };
                    sharedClient = new ClaudeAgentClient(props);
                    sharedBackend = new ClaudeAgentProvider(sharedClient);
                }
            }
        }
        return sharedBackend;
    }

    static AgentProvider agentProvider() {
        var backend = backend();
        return new AgentProvider() {
            @Override public Multi<AgentEvent> invoke(AgentSessionConfig config) { return backend.invoke(config); }
            @Override public AgentSession openSession(AgentSessionInit init) { return backend.openSession(init); }
        };
    }

    static SessionPipeline sessionPipeline() {
        var backend = backend();
        BackendInstanceRegistry registry = new BackendInstanceRegistry() {
            @Override public void register(AgentBackend b) {}
            @Override public Optional<AgentBackend> resolve(String key, String instanceId) { return Optional.of(backend); }
            @Override public List<AgentBackend> resolveByKey(String key) { return List.of(backend); }
        };
        var pool = new PoolDeclaration("eval", "eval", "claude", 0, 10, null, Map.of(), Map.of());
        var poolRegistry = new SessionPoolRegistry(List.of(pool), registry);
        var lifecycleManager = new SessionLifecycleManager(poolRegistry);
        return new SessionPipeline(lifecycleManager, "claude",
                new AimdAdmissionStrategy(10, 8, Duration.ofSeconds(120)), 50_000);
    }

    private EvalTestSupport() {}
}
