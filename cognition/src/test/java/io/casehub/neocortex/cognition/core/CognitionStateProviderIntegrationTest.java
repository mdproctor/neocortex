package io.casehub.neocortex.cognition.core;

import io.casehub.neocortex.cognition.mood.MoodConfig;
import io.casehub.neocortex.cognition.mood.MoodOrchestrator;
import io.casehub.neocortex.cognition.mood.MoodSignal;
import io.casehub.neocortex.cognition.trace.CognitionStateProvider;
import io.casehub.neocortex.cognition.trace.PipelineTrace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import io.casehub.neocortex.cognitive.observability.testing.InMemoryPipelineSnapshotStore;

class CognitionStateProviderIntegrationTest {

    private MoodOrchestrator mood;
    private CognitionCore core;

    @BeforeEach
    void setUp() {
        mood = new MoodOrchestrator(MoodConfig.defaults());
        core = new CognitionCore(mood, null, null, null, null, null, null, null);
    }

    @Test
    void coreImplementsCognitionStateProvider() {
        assertThat(core).isInstanceOf(CognitionStateProvider.class);
    }

    @Test
    void currentTraceEmptyBeforeFirstTick() {
        var trace = core.currentTrace("agent1", "t1");
        assertThat(trace).isEmpty();
    }

    @Test
    void tickProducesTraceWithPromptSections() {
        mood.record(new MoodSignal.InteractionAppraisal(0.3, 0.2, -0.1, "test"), "agent1", "t1");
        mood.tick("agent1", "t1");

        core.tick("agent1", "t1", null, (a, t) -> Set.of());

        var trace = core.currentTrace("agent1", "t1");
        assertThat(trace).isPresent();
        assertThat(trace.get().promptSections()).isNotEmpty();
        assertThat(trace.get().promptSections()).containsKey("MoodPromptSection");
    }

    @Test
    void traceFieldsAreNullWithoutAppraisalConfigured() {
        core.tick("agent1", "t1", null, (a, t) -> Set.of());

        var trace = core.currentTrace("agent1", "t1");
        assertThat(trace).isPresent();
        assertThat(trace.get().appraisalResult()).isNull();
        assertThat(trace.get().regulationResult()).isNull();
        assertThat(trace.get().capsSettling()).isNull();
        assertThat(trace.get().channelWeights()).isNull();
    }

    @Test
    void activeAgentsReturnsTickedAgent() {
        core.tick("agent1", "t1", null, (a, t) -> Set.of());

        var agents = core.activeAgents("t1");
        assertThat(agents).containsExactly("agent1");
    }

    @Test
    void activeAgentsEmptyForDifferentTenant() {
        core.tick("agent1", "t1", null, (a, t) -> Set.of());

        var agents = core.activeAgents("other-tenant");
        assertThat(agents).isEmpty();
    }

    @Test
    void tickCaptureStoresSnapshotWhenStoreConfigured() {
        var store = new InMemoryPipelineSnapshotStore();
        core.configurePipelineSnapshotStore(store);

        core.tick("agent1", "t1", null, (a, t) -> Set.of());

        assertThat(store.count("t1")).isEqualTo(1);
        var latest = store.findLatest("t1", "agent1", 1);
        assertThat(latest).hasSize(1);
        assertThat(latest.get(0).trace().promptSections()).isNotEmpty();
    }
}
