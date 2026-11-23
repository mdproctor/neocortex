package io.casehub.neocortex.cognition.core;

import io.casehub.neocortex.cognition.journal.CognitiveEntry;
import io.casehub.neocortex.cognition.journal.CognitiveJournal;
import io.casehub.neocortex.cognition.mood.MoodConfig;
import io.casehub.neocortex.cognition.mood.MoodOrchestrator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import io.casehub.neocortex.cognition.memory.MemoryHygieneOrchestrator;
import io.casehub.neocortex.cognition.mentalmodel.MentalModelOrchestrator;
import io.casehub.neocortex.cognition.narrative.NarrativeOrchestrator;
import io.casehub.neocortex.cognition.strategy.StrategyLearningOrchestrator;
import io.casehub.neocortex.cognition.temporal.ReflectionRetrievalOrchestrator;
import io.casehub.neocortex.cognition.temporal.TemporalFocusOrchestrator;
import io.casehub.neocortex.cognition.usermodel.UserModelOrchestrator;
import io.casehub.neocortex.cognitive.index.AttentionItem;
import io.casehub.neocortex.memory.KnowledgeGapSummary;
import io.casehub.neocortex.memory.ReflectionEntry;
import java.util.Set;
import org.mockito.Mockito;

class CognitionCoreJournalTest {

    private final List<CognitiveEntry> entries = new ArrayList<>();
    private final CognitiveJournal     journal = entries::add;

    @Test
    void tick_records_temporalFocus_entry() {
        var config = CognitionConfig.all().with("temporalFocus", true);
        var mood   = new MoodOrchestrator(MoodConfig.defaults());
        TemporalFocusOrchestrator temporalFocus =
                new TemporalFocusOrchestrator() {
                    @Override
                    public void tick(String agentId, String tenantId, Set<String> subjects)     {}

                    @Override
                    public List<AttentionItem> lastFocus() {return List.of();}
                };
        var core = new CognitionCore(mood, null, null, null, null, null, null, null,
                                     null, config, null, null, null, null, temporalFocus, null, null, null);
        core.configureCognitiveJournal(journal);

        core.tick("a1", "t1", null, (a, t) -> Set.of("subject-1", "subject-2"));

        assertThat(entries)
                .filteredOn(e -> e instanceof CognitiveEntry.TemporalFocusTicked)
                .hasSize(1);
        var entry = (CognitiveEntry.TemporalFocusTicked) entries.stream()
                                                                .filter(e -> e instanceof CognitiveEntry.TemporalFocusTicked).findFirst().orElseThrow();
        assertThat(entry.subjectCount()).isEqualTo(2);
        assertThat(entry.agentId()).isEqualTo("a1");
        assertThat(entry.tenantId()).isEqualTo("t1");
    }

    @Test
    void tick_records_memoryHygiene_entry() {
        var config = CognitionConfig.all().with("memoryHygiene", true);
        var mood   = new MoodOrchestrator(MoodConfig.defaults());
        MemoryHygieneOrchestrator hygiene =
                new MemoryHygieneOrchestrator() {
                    @Override
                    public void tick(String agentId, String tenantId)                                                               {}

                    @Override
                    public KnowledgeGapSummary knowledgeGaps(String agentId, String tenantId) {return null;}
                };
        var core = new CognitionCore(mood, null, null, null, null, null, null, hygiene,
                                     null, config, null, null, null, null, null, null, null, null);
        core.configureCognitiveJournal(journal);

        core.tick("a1", "t1", null, (a, t) -> Set.of());

        assertThat(entries)
                .filteredOn(e -> e instanceof CognitiveEntry.MemoryHygieneTicked)
                .hasSize(1);
    }

    @Test
    void tick_records_narrative_entry() {
        var config    = CognitionConfig.all().with("narrative", true);
        var mood      = new MoodOrchestrator(MoodConfig.defaults());
        var narrative = Mockito.mock(NarrativeOrchestrator.class);
        var core = new CognitionCore(mood, null, null, null, null, narrative, null, null,
                                     null, config, null, null, null, null, null, null, null, null);
        core.configureCognitiveJournal(journal);

        core.tick("a1", "t1", null, (a, t) -> Set.of());

        assertThat(entries)
                .filteredOn(e -> e instanceof CognitiveEntry.NarrativeUpdated)
                .hasSize(1);
    }

    @Test
    void tick_records_strategy_entry() {
        var config   = CognitionConfig.all().with("strategy", true);
        var mood     = new MoodOrchestrator(MoodConfig.defaults());
        var strategy = Mockito.mock(StrategyLearningOrchestrator.class);
        var core = new CognitionCore(mood, null, null, null, strategy, null, null, null,
                                     null, config, null, null, null, null, null, null, null, null);
        core.configureCognitiveJournal(journal);

        core.tick("a1", "t1", null, (a, t) -> Set.of());

        assertThat(entries)
                .filteredOn(e -> e instanceof CognitiveEntry.StrategyUpdated)
                .hasSize(1);
    }

    @Test
    void tick_records_reflection_entry() {
        var config = CognitionConfig.all().with("reflection", true);
        var mood   = new MoodOrchestrator(MoodConfig.defaults());
        ReflectionRetrievalOrchestrator reflection =
                new ReflectionRetrievalOrchestrator() {
                    @Override
                    public void tick(String agentId, String tenantId)                                                {}

                    @Override
                    public List<ReflectionEntry> lastReflections() {return List.of();}
                };
        var core = new CognitionCore(mood, null, null, null, null, null, null, null,
                                     null, config, null, null, null, null, null, reflection, null, null);
        core.configureCognitiveJournal(journal);

        core.tick("a1", "t1", null, (a, t) -> Set.of());

        assertThat(entries)
                .filteredOn(e -> e instanceof CognitiveEntry.ReflectionRetrieved)
                .hasSize(1);
    }

    @Test
    void tick_records_userModel_and_mentalModel_entries_per_subject() {
        var config = CognitionConfig.all()
                                    .with("userModel", true)
                                    .with("mentalModel", true);
        var mood        = new MoodOrchestrator(MoodConfig.defaults());
        var userModel   = Mockito.mock(UserModelOrchestrator.class);
        var mentalModel = Mockito.mock(MentalModelOrchestrator.class);
        var core = new CognitionCore(mood, null, userModel, mentalModel, null, null, null, null,
                                     null, config, null, null, null, null, null, null, null, null);
        core.configureCognitiveJournal(journal);

        core.tick("a1", "t1", null, (a, t) -> Set.of("alice", "bob"));

        var userEntries = entries.stream()
                                 .filter(e -> e instanceof CognitiveEntry.UserModelUpdated)
                                 .map(e -> (CognitiveEntry.UserModelUpdated) e)
                                 .toList();
        assertThat(userEntries).hasSize(2);
        assertThat(userEntries).extracting(CognitiveEntry.UserModelUpdated::subjectId)
                               .containsExactlyInAnyOrder("alice", "bob");

        var mentalEntries = entries.stream()
                                   .filter(e -> e instanceof CognitiveEntry.MentalModelUpdated)
                                   .map(e -> (CognitiveEntry.MentalModelUpdated) e)
                                   .toList();
        assertThat(mentalEntries).hasSize(2);
        assertThat(mentalEntries).extracting(CognitiveEntry.MentalModelUpdated::subjectId)
                                 .containsExactlyInAnyOrder("alice", "bob");
    }
}
