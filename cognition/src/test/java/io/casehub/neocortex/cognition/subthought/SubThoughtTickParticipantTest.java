package io.casehub.neocortex.cognition.subthought;

import io.casehub.neocortex.cognition.core.CognitionTickContext;
import io.casehub.neocortex.cognition.core.SubjectResolver;
import io.casehub.neocortex.cognition.mentalmodel.MentalModelOrchestrator;
import io.casehub.neocortex.cognition.mentalmodel.MentalStateSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import io.casehub.neocortex.mindmap.intelligence.SubThoughtExtractor;
import io.casehub.neocortex.mindmap.intelligence.SubThoughtsEnriched;
import java.util.List;

class SubThoughtTickParticipantTest {

    private RuleBasedSubThoughtExtractor extractor;
    private MentalModelOrchestrator mentalModel;
    private SubjectResolver resolver;
    private SubThoughtTickParticipant participant;

    @BeforeEach
    void setUp() {
        extractor = new RuleBasedSubThoughtExtractor();
        mentalModel = mock(MentalModelOrchestrator.class);
        resolver = (agentId, tenantId) -> Set.of("Sarah");
        participant = new SubThoughtTickParticipant(extractor, mentalModel, resolver);
        extractor.refreshEntityCache("t1", Set.of("Sarah"));
    }

    @Test
    void tickExtractsAndStores() {
        var context = new CognitionTickContext("agent1", "t1", null, resolver, "Sarah seemed upset.");
        participant.tick(context);

        var result = participant.currentSubThoughts("agent1", "t1");
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertEquals(1, result.subThoughts().size());
        assertEquals("Sarah", result.subThoughts().getFirst().entity());
        assertNotNull(result.observationHash());
    }



    @Test
    void tickPushesSubThoughtCueToMentalModel() {
        var context = new CognitionTickContext("agent1", "t1", null, resolver, "Sarah seemed upset.");
        participant.tick(context);

        verify(mentalModel).record(
            argThat(signal -> signal instanceof MentalStateSignal.SubThoughtCue stc
                    && "Sarah".equals(stc.entity())),
            eq("agent1"), eq("Sarah"), eq("t1")
        );
    }

    @Test
    void tickSkipsNullObservation() {
        var context = new CognitionTickContext("agent1", "t1", null, resolver, null);
        participant.tick(context);

        var result = participant.currentSubThoughts("agent1", "t1");
        assertNull(result);
        verifyNoInteractions(mentalModel);
    }

    @Test
    void tickSkipsMentalModelPushForNonSubjects() {
        resolver = (agentId, tenantId) -> Set.of("Tom");
        participant = new SubThoughtTickParticipant(extractor, mentalModel, resolver);

        var context = new CognitionTickContext("agent1", "t1", null, resolver, "Sarah seemed upset.");
        participant.tick(context);

        verifyNoInteractions(mentalModel);
        var result = participant.currentSubThoughts("agent1", "t1");
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void currentSubThoughtsReturnsNullForUnknownAgent() {
        var result = participant.currentSubThoughts("unknown", "t1");
        assertNull(result);
    }

    @Test
    void tickWithNoKeywordMatchStoresEmptyResult() {
        var context = new CognitionTickContext("agent1", "t1", null, resolver, "The weather was nice.");
        participant.tick(context);

        var result = participant.currentSubThoughts("agent1", "t1");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void asyncCachePopulatedByHandleEnriched() {
        var enriched = new SubThoughtsEnriched(
                "mem-1", "agent1", "t1",
                List.of(
                        new SubThoughtExtractor.ParsedSubThought(
                                "concern", "worried about Sarah", "Sarah", 0.85)
                                 )
        );
        participant.handleEnriched(enriched);

        var context = new CognitionTickContext("agent1", "t1", null, resolver, "The weather was nice.");
        participant.tick(context);

        var result = participant.currentSubThoughts("agent1", "t1");
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertEquals(1, result.subThoughts().size());
        assertEquals("concern", result.subThoughts().getFirst().type());
        assertEquals(SubThought.Source.ASYNC, result.subThoughts().getFirst().source());
        assertEquals(0.85, result.subThoughts().getFirst().confidence());
    }

    @Test
    void asyncWinsOverSyncOnTextOverlap() {
        var enriched = new SubThoughtsEnriched(
                "mem-1", "agent1", "t1",
                List.of(
                        new SubThoughtExtractor.ParsedSubThought(
                                "causal-inference", "Sarah seemed upset.", "Sarah", 0.9)
                                 )
        );
        participant.handleEnriched(enriched);

        var context = new CognitionTickContext("agent1", "t1", null, resolver, "Sarah seemed upset.");
        participant.tick(context);

        var result = participant.currentSubThoughts("agent1", "t1");
        assertNotNull(result);
        assertEquals(1, result.subThoughts().size());
        assertEquals(SubThought.Source.ASYNC, result.subThoughts().getFirst().source());
        assertEquals("causal-inference", result.subThoughts().getFirst().type());
    }

    @Test
    void asyncCacheUsesConfidenceFromParsedSubThought() {
        var enriched = new SubThoughtsEnriched(
                "mem-1", "agent1", "t1",
                List.of(
                        new SubThoughtExtractor.ParsedSubThought(
                                "evaluative", "excellent food", null, 0.65)
                                 )
        );
        participant.handleEnriched(enriched);

        var context = new CognitionTickContext("agent1", "t1", null, resolver, "The weather was nice.");
        participant.tick(context);

        var result = participant.currentSubThoughts("agent1", "t1");
        assertNotNull(result);
        assertEquals(0.65, result.subThoughts().getFirst().confidence());
    }

}
