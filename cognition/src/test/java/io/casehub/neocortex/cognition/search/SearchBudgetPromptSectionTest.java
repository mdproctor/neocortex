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
}
