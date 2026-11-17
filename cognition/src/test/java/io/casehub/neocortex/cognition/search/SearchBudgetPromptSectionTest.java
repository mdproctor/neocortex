package io.casehub.neocortex.cognition.search;

import io.casehub.neocortex.cognition.prompt.BlockTag;
import io.casehub.neocortex.cognition.prompt.CognitionRenderContext;
import io.casehub.neocortex.mindmap.MindMapStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SearchBudgetPromptSectionTest {

    @Test
    void renders_high_curiosity_budget() {
        var budget = new SearchBudget(0.8, 0.6, 0.3, 0.2, SearchMotivation.CURIOSITY, false, false, 0.0, 0.0, 0.0, -1.0, -1.0, -1.0, Instant.now());
        var participant = mock(SearchBudgetTickParticipant.class);
        when(participant.currentBudget("agent1", "tenant1")).thenReturn(Optional.of(budget));

        var section = new SearchBudgetPromptSection(participant, mock(MindMapStore.class));
        var context = new CognitionRenderContext("agent1", "tenant1", null, List.of());
        String result = section.render(context);

        assertNotNull(result);
        assertFalse(result.isBlank());
    }

    @Test
    void renders_high_competence_budget() {
        var budget = new SearchBudget(0.3, 0.2, 0.9, 0.7, SearchMotivation.COMPETENCE, false, false, 0.0, 0.0, 0.0, -1.0, -1.0, -1.0, Instant.now());
        var participant = mock(SearchBudgetTickParticipant.class);
        when(participant.currentBudget("agent1", "tenant1")).thenReturn(Optional.of(budget));

        var section = new SearchBudgetPromptSection(participant, mock(MindMapStore.class));
        var context = new CognitionRenderContext("agent1", "tenant1", null, List.of());
        String result = section.render(context);

        assertNotNull(result);
        assertFalse(result.isBlank());
    }

    @Test
    void renders_small_budget() {
        var budget = new SearchBudget(0.1, 0.05, 0.15, 0.8, SearchMotivation.CURIOSITY, false, false, 0.0, 0.0, 0.0, -1.0, -1.0, -1.0, Instant.now());
        var participant = mock(SearchBudgetTickParticipant.class);
        when(participant.currentBudget("agent1", "tenant1")).thenReturn(Optional.of(budget));

        var section = new SearchBudgetPromptSection(participant, mock(MindMapStore.class));
        var context = new CognitionRenderContext("agent1", "tenant1", null, List.of());
        String result = section.render(context);

        assertNotNull(result);
    }

    @Test
    void renders_pathological_fixation() {
        var budget = new SearchBudget(0.05, 0.0, 1.0, 0.0, SearchMotivation.CURIOSITY, false, false, 0.0, 0.0, 0.0, -1.0, -1.0, -1.0, Instant.now());
        var participant = mock(SearchBudgetTickParticipant.class);
        when(participant.currentBudget("agent1", "tenant1")).thenReturn(Optional.of(budget));

        var section = new SearchBudgetPromptSection(participant, mock(MindMapStore.class));
        var context = new CognitionRenderContext("agent1", "tenant1", null, List.of());
        String result = section.render(context);

        assertNotNull(result);
        assertFalse(result.isBlank());
    }

    @Test
    void returns_null_when_no_budget() {
        var participant = mock(SearchBudgetTickParticipant.class);
        when(participant.currentBudget(anyString(), anyString())).thenReturn(Optional.empty());

        var section = new SearchBudgetPromptSection(participant, mock(MindMapStore.class));
        var context = new CognitionRenderContext("agent1", "tenant1", null, List.of());
        assertNull(section.render(context));
    }

    @Test
    void block_tag_is_search_budget() {
        var section = new SearchBudgetPromptSection(
                mock(SearchBudgetTickParticipant.class), mock(MindMapStore.class));
        assertEquals(BlockTag.SEARCH_BUDGET, section.blockTag());
    }

    @Test
    void threat_source_renders_narrowed_text() {
        var budget = new SearchBudget(0.3, 0.0, 0.5, 0.8, SearchMotivation.THREAT, false, false, 0.0, 0.0, 0.0, -1.0, -1.0, -1.0, Instant.now());
        var participant = mock(SearchBudgetTickParticipant.class);
        when(participant.currentBudget("agent1", "tenant1")).thenReturn(Optional.of(budget));

        var section = new SearchBudgetPromptSection(participant, mock(MindMapStore.class));
        var context = new CognitionRenderContext("agent1", "tenant1", null, List.of());
        String result = section.render(context);

        assertNotNull(result);
        assertFalse(result.isBlank());
    }

@Test
void crisisEmotion_injectsCrisisQualifier() {
    var budget = new SearchBudget(0.5, 0.3, 0.6, 0.4, SearchMotivation.CURIOSITY, false, false, 0.0, 0.0, 0.0, -1.0, -1.0, -1.0, Instant.now());
    var participant = mock(SearchBudgetTickParticipant.class);
    when(participant.currentBudget("agent1", "tenant1")).thenReturn(Optional.of(budget));

    var appraisal = new io.casehub.neocortex.cognition.appraisal.AppraisalResult(
            List.of(new io.casehub.neocortex.cognitive.CognitiveEmotion(
                    io.casehub.neocortex.cognitive.EmotionType.FEAR, 0.89, "subject",
                    Instant.now(), io.casehub.neocortex.cognitive.EmotionSource.INTRINSIC,
                    io.casehub.neocortex.cognitive.PadProjection.NEUTRAL)),
            List.of(new io.casehub.neocortex.cognition.appraisal.ActionTendency(
                    io.casehub.neocortex.cognition.appraisal.ActionReadiness.AVOIDANCE, 0.92, "")),
            io.casehub.neocortex.cognition.appraisal.HabituationState.empty());

    var section = new SearchBudgetPromptSection(participant, null, null, () -> appraisal);
    var context = new CognitionRenderContext("agent1", "tenant1", null, List.of());
    String result = section.render(context);

    assertNotNull(result);
    assertTrue(result.contains("something is wrong"), "Crisis emotion should inject urgency qualifier");
}

@Test
void highArousalHighTendency_injectsUrgency() {
    var budget = new SearchBudget(0.5, 0.3, 0.6, 0.4, SearchMotivation.CURIOSITY, false, false, 0.0, 0.0, 0.0, -1.0, -1.0, -1.0, Instant.now());
    var participant = mock(SearchBudgetTickParticipant.class);
    when(participant.currentBudget("agent1", "tenant1")).thenReturn(Optional.of(budget));

    var appraisal = new io.casehub.neocortex.cognition.appraisal.AppraisalResult(
            List.of(new io.casehub.neocortex.cognitive.CognitiveEmotion(
                    io.casehub.neocortex.cognitive.EmotionType.ANGER, 0.8, "subject",
                    Instant.now(), io.casehub.neocortex.cognitive.EmotionSource.INTRINSIC,
                    io.casehub.neocortex.cognitive.PadProjection.NEUTRAL)),
            List.of(new io.casehub.neocortex.cognition.appraisal.ActionTendency(
                    io.casehub.neocortex.cognition.appraisal.ActionReadiness.ANTAGONISM, 0.85, "")),
            io.casehub.neocortex.cognition.appraisal.HabituationState.empty(),
            null, java.util.Map.of("urgency", 0.9));

    var section = new SearchBudgetPromptSection(participant, null, null, () -> appraisal);
    var context = new CognitionRenderContext("agent1", "tenant1", null, List.of());
    String result = section.render(context);

    assertNotNull(result);
    assertTrue(result.contains("urgency is real"), "High arousal + tendency should inject urgency");
}

@Test
void noAppraisal_noModulation() {
    var budget = new SearchBudget(0.5, 0.3, 0.6, 0.4, SearchMotivation.CURIOSITY, false, false, 0.0, 0.0, 0.0, -1.0, -1.0, -1.0, Instant.now());
    var participant = mock(SearchBudgetTickParticipant.class);
    when(participant.currentBudget("agent1", "tenant1")).thenReturn(Optional.of(budget));

    var section = new SearchBudgetPromptSection(participant, null, null, null);
    var context = new CognitionRenderContext("agent1", "tenant1", null, List.of());
    String result = section.render(context);

    assertNotNull(result);
    assertFalse(result.contains("urgency"), "No appraisal should not inject urgency");
    assertFalse(result.contains("wrong"), "No appraisal should not inject crisis");
}
}
