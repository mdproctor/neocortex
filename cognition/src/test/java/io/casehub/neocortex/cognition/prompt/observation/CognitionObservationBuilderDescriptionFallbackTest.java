package io.casehub.neocortex.cognition.prompt.observation;

import io.casehub.neocortex.caps.SituationContext;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CognitionObservationBuilderDescriptionFallbackTest {

    @Test
    void renderInternalState_fallsBackToDriveDescription_whenNoGeneratedDescriptionExists() {
        var store = new InMemoryMindMapStore();
        String tenant = "test-tenant";
        var sgId = store.createSubgraph(
                new SubgraphInput("cognitive-test", SubgraphTypes.COGNITIVE, null), tenant);

        String description = "There's a clarity that comes when a plan locks into place.";
        store.addNode(NodeInput.of("scheming", sgId)
                .withProperties(Map.of(
                        "cognitiveKind", "drive-intensity",
                        "agent-id", "test-agent",
                        "drive-type", "scheming",
                        "intensity", "0.9",
                        "description", description)), tenant);

        var baseDrives = List.of(
                new DriveSnapshot("scheming", 0.9, description));
        var builder = new CognitionObservationBuilder(
                "test-agent", tenant, store, null, null, baseDrives);

        var section = builder.renderInternalState(
                List.of(), "A quiet room with blueprints.",
                List.of(), SituationContext.EMPTY);

        assertThat(section).isNotNull();
        var text = switch (section) {
            case ObservationSection.TextBlock tb -> tb.content();
            default -> section.toString();
        };
        assertThat(text).contains(description);
    }
}
