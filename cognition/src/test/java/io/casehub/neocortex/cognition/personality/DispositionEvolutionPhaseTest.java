package io.casehub.neocortex.cognition.personality;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.eidos.api.DispositionEvolution;
import io.casehub.eidos.api.DispositionHealth;
import io.casehub.eidos.api.DispositionHealth.DispositionStatus;
import io.casehub.eidos.api.DispositionSignalStore;
import io.casehub.eidos.api.DispositionValue;
import io.casehub.eidos.api.EvolutionType;
import io.casehub.neocortex.cognition.journal.CognitiveEntry;
import io.casehub.neocortex.cognition.journal.CognitiveJournal;
import io.casehub.neocortex.cognition.situational.PersonalityStateService;
import io.casehub.neocortex.cognition.situational.ReflectionLedger;
import io.casehub.neocortex.cognitive.index.CognitiveDefaults;
import io.casehub.neocortex.cognitive.index.EffectiveCognitiveDefaults;
import io.casehub.neocortex.memory.cbr.CbrRecordStore;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import io.casehub.neocortex.cognitive.CognitiveCapacityConfig;
import io.casehub.neocortex.cognitive.index.LearningProfile;

class DispositionEvolutionPhaseTest {

    private static final String TENANT = "t1";
    private static final String AGENT = "agent-1";
    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");
    private static final Duration REFLECTION_PERIOD = Duration.ofHours(24);
    private static final List<DispositionValue> TI_NE_PROFILE = List.of(
            new DispositionValue("Ti", 0.35), new DispositionValue("Ne", 0.20));
    private static final Set<String> FACET_NAMES = Set.of("Anxiousness", "Emotional Lability");

    private DispositionHealth health;
    private DispositionEvolution evolution;
    private AgentRegistry agentRegistry;
    private CbrRecordStore cbrStore;
    private PersonalityStateService stateService;
    private DispositionSignalWriter signalWriter;
    private EffectiveCognitiveDefaults effectiveDefaults;
    private List<CognitiveEntry> journalEntries;
    private CognitiveJournal journal;
    private DispositionEvolutionPhase phase;
    private AgentDescriptor descriptor;

    @BeforeEach
    void setUp() {
        health = mock(DispositionHealth.class);
        evolution = mock(DispositionEvolution.class);
        agentRegistry = mock(AgentRegistry.class);
        cbrStore = mock(CbrRecordStore.class);

        var mindMapStore = new InMemoryMindMapStore();
        stateService = new PersonalityStateService(mindMapStore, FACET_NAMES);

        var signalStore = mock(DispositionSignalStore.class);
        signalWriter = new DispositionSignalWriter(signalStore, Runnable::run);

        effectiveDefaults = mock(EffectiveCognitiveDefaults.class);
        when(effectiveDefaults.agentIds(TENANT)).thenReturn(Set.of(AGENT));

        var defaults = CognitiveDefaults.empty(AGENT);
        when(effectiveDefaults.restingFor(eq(TENANT), eq(AGENT))).thenReturn(
                LearningProfile.from(defaults));

        journalEntries = new ArrayList<>();
        journal = journalEntries::add;

        descriptor = AgentDescriptor.builder()
                .agentId(AGENT).name("Test").slot("test").tenancyId(TENANT)
                .disposition(AgentDisposition.builder()
                        .socialOrient("cooperative")
                        .dispositionProfile(TI_NE_PROFILE)
                        .build())
                .build();
        when(agentRegistry.findById(AGENT, TENANT)).thenReturn(Optional.of(descriptor));

        phase = new DispositionEvolutionPhase(
                health, evolution, agentRegistry, cbrStore,
                effectiveDefaults, stateService, signalWriter,
                DispositionEvolutionConfig.defaults(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        phase.setCognitiveJournal(journal);
    }

    private void seedState(Duration agoFromNow) {
        var reflection = ReflectionLedger.initial(NOW.minus(agoFromNow));
        stateService.transition(TENANT, AGENT, NOW.minus(agoFromNow),
                st -> st.withReflection(reflection));
    }

    @Test
    void stableReflection_noSignals_advancesLedger() {
        seedState(Duration.ofHours(25));
        when(health.probe(any(), any()))
                .thenReturn(new DispositionStatus.Aligned(Map.of("Ti", 0.35, "Ne", 0.20)));

        phase.run(TENANT, List.of());

        var state = stateService.read(TENANT, AGENT, NOW);
        assertThat(state.reflection()).isNotNull();
        assertThat(state.reflection().lastReflectionAt())
                .isAfter(NOW.minus(Duration.ofHours(25)));
        var reflected = journalEntries.stream()
                .filter(e -> e instanceof CognitiveEntry.DispositionReflected)
                .map(e -> (CognitiveEntry.DispositionReflected) e)
                .findFirst();
        assertThat(reflected).isPresent();
        assertThat(reflected.get().outcomeType()).isEqualTo("Stable");
    }

    @Test
    void wallClockGate_notDueYet_skipsReflection() {
        seedState(Duration.ofHours(12));
        phase.run(TENANT, List.of());

        verify(health, never()).probe(any(), any());
        assertThat(journalEntries.stream()
                .filter(e -> e instanceof CognitiveEntry.DispositionReflected)
                .count()).isZero();
    }

    @Test
    void evolvedPath_commitsLivedProfile() {
        seedState(Duration.ofHours(25));
        var newProfile = List.of(
                new DispositionValue("Ne", 0.35), new DispositionValue("Ti", 0.20));
        EvolutionType swapType = () -> "DOMINANT_AUXILIARY_SWAP";
        when(health.probe(any(), any()))
                .thenReturn(new DispositionStatus.EvolutionPending(
                        swapType, "Ne", Map.of("Ne", 0.36, "Ti", 0.30)));
        when(evolution.evaluate(any(), any()))
                .thenReturn(new DispositionEvolution.EvolutionResult.Evolved(
                        newProfile, "TI-NE", "NE-TI"));

        phase.run(TENANT, List.of());

        var state = stateService.read(TENANT, AGENT, NOW);
        assertThat(state.livedProfile()).isNotNull();
        assertThat(state.livedProfile().profile()).hasSize(2);
        assertThat(state.livedProfile().profile().get(0).term()).isEqualTo("Ne");
        assertThat(state.reflection().pendingEvolution()).isNull();
    }

    @Test
    void evolvedPath_rateBound_defersTooSoon() {
        seedState(Duration.ofHours(25));
        var reflection = stateService.read(TENANT, AGENT, NOW).reflection()
                .withLastTransitionAt(NOW.minus(Duration.ofDays(10)));
        stateService.transition(TENANT, AGENT, NOW, st -> st.withReflection(reflection));

        var newProfile = List.of(
                new DispositionValue("Ne", 0.35), new DispositionValue("Ti", 0.20));
        EvolutionType swapType = () -> "DOMINANT_AUXILIARY_SWAP";
        when(health.probe(any(), any()))
                .thenReturn(new DispositionStatus.EvolutionPending(
                        swapType, "Ne", Map.of("Ne", 0.36, "Ti", 0.30)));
        when(evolution.evaluate(any(), any()))
                .thenReturn(new DispositionEvolution.EvolutionResult.Evolved(
                        newProfile, "TI-NE", "NE-TI"));

        phase.run(TENANT, List.of());

        var state = stateService.read(TENANT, AGENT, NOW);
        assertThat(state.livedProfile()).isNull();
        var reflected = journalEntries.stream()
                .filter(e -> e instanceof CognitiveEntry.DispositionReflected)
                .map(e -> (CognitiveEntry.DispositionReflected) e)
                .findFirst();
        assertThat(reflected).isPresent();
        assertThat(reflected.get().outcomeType()).isEqualTo("Deferred");
    }

    @Test
    void disabledAgent_rcZero_skipsProbe() {
        seedState(Duration.ofHours(25));
        var disabledDefaults = CognitiveDefaults.empty(AGENT)
                .withCapacityConfig(new CognitiveCapacityConfig(0.0, 0.75, 0.75));
        when(effectiveDefaults.restingFor(eq(TENANT), eq(AGENT))).thenReturn(
                LearningProfile.from(disabledDefaults));

        phase.run(TENANT, List.of());

        verify(health, never()).probe(any(), any());
        var reflected = journalEntries.stream()
                .filter(e -> e instanceof CognitiveEntry.DispositionReflected)
                .map(e -> (CognitiveEntry.DispositionReflected) e)
                .findFirst();
        assertThat(reflected).isPresent();
        assertThat(reflected.get().outcomeType()).isEqualTo("Disabled");
    }

    @Test
    void dampenedPath_decaysSignals() {
        seedState(Duration.ofHours(25));
        EvolutionType swapType = () -> "DOMINANT_AUXILIARY_SWAP";
        when(health.probe(any(), any()))
                .thenReturn(new DispositionStatus.EvolutionPending(
                        swapType, "Ne", Map.of()));
        when(evolution.evaluate(any(), any()))
                .thenReturn(new DispositionEvolution.EvolutionResult.Dampened(0.2));

        phase.run(TENANT, List.of());

        var reflected = journalEntries.stream()
                .filter(e -> e instanceof CognitiveEntry.DispositionReflected)
                .map(e -> (CognitiveEntry.DispositionReflected) e)
                .findFirst();
        assertThat(reflected).isPresent();
        assertThat(reflected.get().outcomeType()).isEqualTo("Dampened");
    }

    @Test
    void recovery_pendingEvolution_clearsAndCloses() {
        seedState(Duration.ofHours(25));
        var pending = new ReflectionLedger.PendingEvolution(
                "intent-1", "TI-NE", "NE-TI", null,
                NOW.minus(Duration.ofHours(26)), Duration.ofDays(30));
        stateService.transition(TENANT, AGENT, NOW,
                st -> st.withReflection(st.reflection().withPendingEvolution(pending)));

        when(health.probe(any(), any()))
                .thenReturn(new DispositionStatus.Aligned(Map.of()));

        phase.run(TENANT, List.of());

        var state = stateService.read(TENANT, AGENT, NOW);
        assertThat(state.reflection().pendingEvolution()).isNull();
        assertThat(state.reflection().lastTransitionAt()).isNotNull();
    }

    @Test
    void reversalDetection_streakIncrementsAndJournalsAtThreshold() {
        seedState(Duration.ofHours(25));

        // Simulate a prior A→B transition by seeding lastTransitionFromLabel="TI-NE"
        // (meaning the previous transition was FROM "TI-NE" TO something else)
        stateService.transition(TENANT, AGENT, NOW,
                                st -> st.withReflection(st.reflection().withLastTransitionFromLabel("TI-NE")));

        // Now evolve back TO "TI-NE" — this is a reversal (B→A where A="TI-NE")
        var newProfile = List.of(
                new DispositionValue("Ti", 0.35), new DispositionValue("Ne", 0.20));
        EvolutionType swapType = () -> "DOMINANT_AUXILIARY_SWAP";
        when(health.probe(any(), any()))
                .thenReturn(new DispositionStatus.EvolutionPending(
                        swapType, "Ti", Map.of("Ti", 0.36, "Ne", 0.30)));
        when(evolution.evaluate(any(), any()))
                .thenReturn(new DispositionEvolution.EvolutionResult.Evolved(
                        newProfile, "NE-TI", "TI-NE"));

        phase.run(TENANT, List.of());

        // First reversal — streak=1, no journal entry yet
        var state = stateService.read(TENANT, AGENT, NOW);
        assertThat(state.reflection().reversalStreak()).isEqualTo(1);
        assertThat(journalEntries.stream()
                                 .filter(e -> e instanceof CognitiveEntry.DispositionReversal)
                                 .count()).isZero();

        // Now seed for second reversal: evolve away and back again
        // After closeEpoch, lastTransitionFromLabel = "NE-TI" (previous transition's previousLabel)
        assertThat(state.reflection().lastTransitionFromLabel()).isEqualTo("NE-TI");

        // Advance ledger so the wall-clock gate passes
        var t2 = NOW.plus(Duration.ofDays(31));
        var phase2 = new DispositionEvolutionPhase(
                health, evolution, agentRegistry, cbrStore,
                effectiveDefaults, stateService, signalWriter,
                DispositionEvolutionConfig.defaults(),
                Clock.fixed(t2, ZoneOffset.UTC));
        phase2.setCognitiveJournal(journal);
        journalEntries.clear();

        // Evolve back to "NE-TI" — second reversal
        var reverseProfile = List.of(
                new DispositionValue("Ne", 0.35), new DispositionValue("Ti", 0.20));
        when(evolution.evaluate(any(), any()))
                .thenReturn(new DispositionEvolution.EvolutionResult.Evolved(
                        reverseProfile, "TI-NE", "NE-TI"));

        phase2.run(TENANT, List.of());

        var state2 = stateService.read(TENANT, AGENT, t2);
        assertThat(state2.reflection().reversalStreak()).isEqualTo(2);
        var reversalJournal = journalEntries.stream()
                                            .filter(e -> e instanceof CognitiveEntry.DispositionReversal)
                                            .map(e -> (CognitiveEntry.DispositionReversal) e)
                                            .findFirst();
        assertThat(reversalJournal).isPresent();
        assertThat(reversalJournal.get().streak()).isEqualTo(2);
    }

    @Test
    void nonReversal_resetsStreak() {
        seedState(Duration.ofHours(25));

        // Seed a prior reversal streak and lastTransitionFromLabel
        stateService.transition(TENANT, AGENT, NOW,
                                st -> st.withReflection(st.reflection()
                                                          .withLastTransitionFromLabel("TI-NE")
                                                          .withReversalStreak(1)));

        // Evolve to "NE-FI" — NOT a reversal (new label != "TI-NE")
        var newProfile = List.of(
                new DispositionValue("Ne", 0.35), new DispositionValue("Fi", 0.20));
        EvolutionType swapType = () -> "DOMINANT_AUXILIARY_SWAP";
        when(health.probe(any(), any()))
                .thenReturn(new DispositionStatus.EvolutionPending(
                        swapType, "Ne", Map.of("Ne", 0.36, "Fi", 0.30)));
        when(evolution.evaluate(any(), any()))
                .thenReturn(new DispositionEvolution.EvolutionResult.Evolved(
                        newProfile, "TI-NE", "NE-FI"));

        phase.run(TENANT, List.of());

        var state = stateService.read(TENANT, AGENT, NOW);
        assertThat(state.reflection().reversalStreak()).isZero();
    }

    @Test
    void evolvedPath_populatesOutboxAndFlushes() {
        seedState(Duration.ofHours(25));
        var newProfile = List.of(
                new DispositionValue("Ne", 0.35), new DispositionValue("Ti", 0.20));
        EvolutionType swapType = () -> "DOMINANT_AUXILIARY_SWAP";
        when(health.probe(any(), any()))
                .thenReturn(new DispositionStatus.EvolutionPending(
                        swapType, "Ne", Map.of("Ne", 0.36, "Ti", 0.30)));
        when(evolution.evaluate(any(), any()))
                .thenReturn(new DispositionEvolution.EvolutionResult.Evolved(
                        newProfile, "TI-NE", "NE-TI"));

        phase.run(TENANT, List.of());

        // After evolution + closeEpoch + flushAuditOutbox, the outbox should be drained
        var state = stateService.read(TENANT, AGENT, NOW);
        // CbrStore is a mock — store() succeeds by default, so the entry is drained
        assertThat(state.reflection().auditOutbox()).isEmpty();
        // The entry went through closeEpoch (lastTransitionAt set)
        assertThat(state.reflection().lastTransitionAt()).isNotNull();
    }

    @Test
    void auditOutbox_givesUpAfterMaxAttempts() {
        seedState(Duration.ofHours(25));

        // Seed an outbox entry that has already exceeded max attempts
        var exhausted = new ReflectionLedger.AuditEntry("old-intent", Map.of("k", "v"), 5, Instant.EPOCH);
        stateService.transition(TENANT, AGENT, NOW,
                                st -> st.withReflection(new ReflectionLedger(
                                        st.reflection().lastReflectionAt(), st.reflection().lastTransitionAt(),
                                        st.reflection().pendingEvolution(), st.reflection().disabled(),
                                        st.reflection().decayedThrough(), st.reflection().reauthor(),
                                        st.reflection().epochFingerprint(), st.reflection().lastTransitionFromLabel(),
                                        st.reflection().reversalStreak(), List.of(exhausted), st.reflection().inert())));

        when(health.probe(any(), any()))
                .thenReturn(new DispositionStatus.Aligned(Map.of()));

        phase.run(TENANT, List.of());

        // Outbox should be empty — exhausted entry removed
        var state = stateService.read(TENANT, AGENT, NOW);
        assertThat(state.reflection().auditOutbox()).isEmpty();

        // Should have journaled the failure
        var auditFailed = journalEntries.stream()
                                        .filter(e -> e instanceof CognitiveEntry.DispositionTransitionAuditFailed)
                                        .map(e -> (CognitiveEntry.DispositionTransitionAuditFailed) e)
                                        .findFirst();
        assertThat(auditFailed).isPresent();
        assertThat(auditFailed.get().intentId()).isEqualTo("old-intent");
        assertThat(auditFailed.get().attempts()).isEqualTo(5);
    }


}
