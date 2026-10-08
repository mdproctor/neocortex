package io.casehub.neocortex.cognition.belief;

import io.casehub.neocortex.cognitive.Confidence;
import io.casehub.neocortex.cognitive.ConfidenceOrigin;
import io.casehub.neocortex.mindmap.MindMapNode;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.api.identity.PrincipalId;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BeliefRevisionPhaseTest {

    private static final String TENANT = "test-tenant";
    private InMemoryMindMapStore store;
    private CountingAgentProvider agentProvider;
    private BeliefRevisionPhase phase;
    private String cognitiveSubgraphId;

    @BeforeEach
    void setUp() {
        store = new InMemoryMindMapStore();
        agentProvider = new CountingAgentProvider();
        phase = new BeliefRevisionPhase(store, agentProvider, BeliefRevisionConfig.defaults());
        cognitiveSubgraphId = store.createSubgraph(
                new SubgraphInput("cognitive-agent1", SubgraphTypes.COGNITIVE, null), TENANT);
    }

    @Test
    void noNewEvidence_skipsLlmCall() {
        addBelief("People always abandon me", "agent1");
        addEvidence("Observed: the housekeeper made a cake", "agent1");

        phase.run(TENANT, List.of());
        int firstPassCalls = agentProvider.callCount();
        assertThat(firstPassCalls).isGreaterThan(0);

        phase.run(TENANT, List.of());
        assertThat(agentProvider.callCount())
                .as("Second pass with no new evidence should not call LLM")
                .isEqualTo(firstPassCalls);
    }

    @Test
    void newEvidence_triggersRevision() {
        addBelief("People always abandon me", "agent1");
        addEvidence("Observed: guardian left", "agent1");

        phase.run(TENANT, List.of());
        int firstPassCalls = agentProvider.callCount();

        addEvidence("Observed: Peter-Perfect stayed and helped", "agent1");

        phase.run(TENANT, List.of());
        assertThat(agentProvider.callCount())
                .as("New evidence should trigger another LLM call")
                .isGreaterThan(firstPassCalls);
    }

    @Test
    void sameEvidenceAcrossMultiplePasses_onlyCallsOnce() {
        addBelief("The world is unsafe", "agent1");
        addEvidence("Observed: someone was kind", "agent1");

        phase.run(TENANT, List.of());
        phase.run(TENANT, List.of());
        phase.run(TENANT, List.of());

        assertThat(agentProvider.callCount())
                .as("Three passes with same evidence should produce exactly one LLM call")
                .isEqualTo(1);
    }

    @Test
    void contradictionFound_revisesBelief() {
        addBeliefWithConfidence("Nobody cares about me", "agent1", 0.25);
        addEvidence("The housekeeper made a birthday cake for you", "agent1");

        agentProvider.setResponse("""
                {"contradictions": [{
                    "beliefNodeId": "any",
                    "beliefText": "Nobody cares about me",
                    "contradictingEvidence": "The housekeeper made a birthday cake",
                    "reasoning": "Someone clearly cared enough to make a cake",
                    "contradictionStrength": 0.8,
                    "revisedBelief": "Some people show care in quiet ways"
                }]}
                """);

        phase.run(TENANT, List.of());

        var nodes = store.nodesIn(cognitiveSubgraphId, TENANT);
        var revisedBeliefs = nodes.stream()
                .filter(n -> "belief-revision".equals(n.provenance()))
                .toList();
        assertThat(revisedBeliefs)
                .as("Contradiction should produce a revised belief node")
                .isNotEmpty();
    }

    @Test
    void afterRevision_cursorNotAdvanced_soNextPassReEvaluates() {
        addBeliefWithConfidence("Nobody cares about me", "agent1", 0.25);
        addEvidence("The housekeeper made a birthday cake for you", "agent1");

        agentProvider.setResponse("""
                {"contradictions": [{
                    "beliefNodeId": "any",
                    "beliefText": "Nobody cares about me",
                    "contradictingEvidence": "The housekeeper made a birthday cake",
                    "reasoning": "Someone clearly cared",
                    "contradictionStrength": 0.8,
                    "revisedBelief": "Some people show care in quiet ways"
                }]}
                """);

        phase.run(TENANT, List.of());
        int callsAfterRevision = agentProvider.callCount();

        agentProvider.setResponse("{\"contradictions\": []}");

        phase.run(TENANT, List.of());
        assertThat(agentProvider.callCount())
                .as("After a revision, cursor should not advance — next pass re-evaluates the revised belief against same evidence")
                .isGreaterThan(callsAfterRevision);
    }

    private void addBelief(String text, String agentId) {
        addBeliefWithConfidence(text, agentId, 0.8);
    }

    private void addBeliefWithConfidence(String text, String agentId, double confidence) {
        store.addNode(
                NodeInput.of(text, cognitiveSubgraphId)
                        .withTraits(Set.of("Belieflike"))
                        .withConfidence(Confidence.inferred(confidence, Instant.now()))
                        .withProvenance("experience-consolidation")
                        .withPrincipalId(PrincipalId.agent(agentId))
                        .withProperties(Map.of("cognitiveKind", "belief", "agent-id", agentId)),
                TENANT);
    }

    private void addEvidence(String text, String agentId) {
        store.addNode(
                NodeInput.of(text, cognitiveSubgraphId)
                        .withProvenance("experience-consolidation")
                        .withProperties(Map.of("agent-id", agentId, "event-type", "observation")),
                TENANT);
    }

    static class CountingAgentProvider implements AgentProvider {
        private final AtomicInteger calls = new AtomicInteger();
        private String response = "{\"contradictions\": []}";

        void setResponse(String response) {
            this.response = response;
        }

        int callCount() {
            return calls.get();
        }

        @Override
        public Multi<AgentEvent> invoke(AgentSessionConfig config) {
            calls.incrementAndGet();
            return Multi.createFrom().item(new AgentEvent.TextDelta(response));
        }

        @Override
        public AgentSession openSession(AgentSessionInit init) {
            throw new UnsupportedOperationException();
        }
    }
}
