package io.casehub.neocortex.cognition.goal;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.GoalOutcomeCounts;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.eidos.api.GoalSignalStore;
import io.casehub.eidos.api.Visibility;
import io.casehub.neocortex.cognition.drive.DriveAxis;
import io.casehub.neocortex.cognition.drive.DriveIntensity;
import io.casehub.neocortex.cognition.drive.DriveOrchestrator;
import io.casehub.neocortex.cognition.drive.DriveProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoalProposalOrchestratorTest {

    private DriveOrchestrator driveOrchestrator;
    private GoalProposalOrchestrator orchestrator;
    private DriveGoalMapper curiosityMapper;
    private DriveGoalMapper competenceMapper;
    private Clock clock;

    @BeforeEach
    void setUp() {
        driveOrchestrator = mock(DriveOrchestrator.class);
        curiosityMapper = mock(DriveGoalMapper.class);
        competenceMapper = mock(DriveGoalMapper.class);

        clock = Clock.fixed(Instant.parse("2026-08-23T12:00:00Z"), ZoneId.of("UTC"));

        orchestrator = new GoalProposalOrchestrator(
                driveOrchestrator,
                List.of(curiosityMapper, competenceMapper),
                Optional.empty(),
                GoalProposalConfig.defaults(),
                clock);
    }

    @Test
    void returnsNoChange_whenNoDrives() {
        when(driveOrchestrator.currentDrives("a1", "t1")).thenReturn(Optional.empty());

        var tick = orchestrator.tick("a1", "t1", descriptorWithGoals());
        assertThat(tick).isInstanceOf(GoalProposalTick.NoChange.class);
    }

    @Test
    void proposesGoal_whenDriveAboveThreshold() {
        setupDrives("a1", "t1", DriveAxis.CURIOSITY, 0.7);
        var proposal = new DriveGoalProposal(
                DriveAxis.CURIOSITY, "explore-knowledge-gaps",
                "Explore fragmented knowledge areas", "curiosity: 5 low-retention", 0.7);
        when(curiosityMapper.evaluate("a1", "t1", driveIntensity(DriveAxis.CURIOSITY, 0.7)))
                .thenReturn(proposal);

        var tick = orchestrator.tick("a1", "t1", descriptorWithGoals());

        assertThat(tick).isInstanceOf(GoalProposalTick.Changes.class);
        var proposed = (GoalProposalTick.Changes) tick;
        assertThat(proposed.newProposals()).hasSize(1);
        assertThat(proposed.newProposals().get(0).goalName()).isEqualTo("explore-knowledge-gaps");
    }

    @Test
    void returnsNoChange_whenDriveBelowThreshold() {
        setupDrives("a1", "t1", DriveAxis.CURIOSITY, 0.2);

        var tick = orchestrator.tick("a1", "t1", descriptorWithGoals());
        assertThat(tick).isInstanceOf(GoalProposalTick.NoChange.class);
    }

    @Test
    void respectsMaxDriveGoals() {
        var config = new GoalProposalConfig(0.4, 0.2, 2, Duration.ofMinutes(120),
                Duration.ofMinutes(60), 5);
        var orch = new GoalProposalOrchestrator(
                driveOrchestrator, List.of(curiosityMapper, competenceMapper), Optional.empty(), config, clock);

        var existingDriveGoals = List.of(
                driveGoal("goal-a"), driveGoal("goal-b"));
        var descriptor = AgentDescriptor.builder()
                .agentId("a1").name("A").slot("s").tenancyId("t1")
                .goals(existingDriveGoals).build();

        setupDrives("a1", "t1", DriveAxis.CURIOSITY, 0.8);
        when(curiosityMapper.evaluate("a1", "t1", driveIntensity(DriveAxis.CURIOSITY, 0.8)))
                .thenReturn(new DriveGoalProposal(
                        DriveAxis.CURIOSITY, "new-goal", "d", "r", 0.8));

        var tick = orch.tick("a1", "t1", descriptor);
        assertThat(tick).isInstanceOf(GoalProposalTick.NoChange.class);
    }

    @Test
    void ranksProposalsByIntensity() {
        var config = new GoalProposalConfig(0.4, 0.2, 1, Duration.ofMinutes(120),
                Duration.ofMinutes(60), 5);
        var orch = new GoalProposalOrchestrator(
                driveOrchestrator, List.of(curiosityMapper, competenceMapper), Optional.empty(), config, clock);

        var profile = new DriveProfile("a1", "t1",
                Map.of(DriveAxis.CURIOSITY, driveIntensity(DriveAxis.CURIOSITY, 0.5),
                       DriveAxis.COMPETENCE, driveIntensity(DriveAxis.COMPETENCE, 0.9)),
                0.7, DriveAxis.COMPETENCE, Instant.now(clock));
        when(driveOrchestrator.currentDrives("a1", "t1")).thenReturn(Optional.of(profile));

        when(curiosityMapper.evaluate("a1", "t1", driveIntensity(DriveAxis.CURIOSITY, 0.5)))
                .thenReturn(new DriveGoalProposal(
                        DriveAxis.CURIOSITY, "explore", "d", "r", 0.5));
        when(competenceMapper.evaluate("a1", "t1", driveIntensity(DriveAxis.COMPETENCE, 0.9)))
                .thenReturn(new DriveGoalProposal(
                        DriveAxis.COMPETENCE, "improve", "d", "r", 0.9));

        var tick = orch.tick("a1", "t1", descriptorWithGoals());

        assertThat(tick).isInstanceOf(GoalProposalTick.Changes.class);
        var proposed = (GoalProposalTick.Changes) tick;
        assertThat(proposed.newProposals()).hasSize(1);
        assertThat(proposed.newProposals().get(0).goalName()).isEqualTo("improve");
    }

    @Test
    void abandonsGoal_whenDriveWeakensAndStaleElapsed() {
        var config = new GoalProposalConfig(0.4, 0.2, 3, Duration.ZERO,
                Duration.ZERO, 5);
        var orch = new GoalProposalOrchestrator(
                driveOrchestrator, List.of(curiosityMapper), Optional.empty(), config, clock);

        var driveGoal = driveGoal("explore-knowledge-gaps");
        var descriptor = AgentDescriptor.builder()
                .agentId("a1").name("A").slot("s").tenancyId("t1")
                .goals(List.of(driveGoal)).build();

        setupDrives("a1", "t1", DriveAxis.CURIOSITY, 0.1);
        var tick = orch.tick("a1", "t1", descriptor);

        assertThat(tick).isInstanceOf(GoalProposalTick.Changes.class);
        var proposed = (GoalProposalTick.Changes) tick;
        assertThat(proposed.abandonedGoalNames()).contains("explore-knowledge-gaps");
    }

    @Test
    void respectsCooldown() {
        var config = new GoalProposalConfig(0.4, 0.2, 3, Duration.ofMinutes(120),
                Duration.ofMinutes(60), 5);
        var orch = new GoalProposalOrchestrator(
                driveOrchestrator, List.of(curiosityMapper), Optional.empty(), config, clock);

        setupDrives("a1", "t1", DriveAxis.CURIOSITY, 0.7);
        when(curiosityMapper.evaluate("a1", "t1", driveIntensity(DriveAxis.CURIOSITY, 0.7)))
                .thenReturn(new DriveGoalProposal(
                        DriveAxis.CURIOSITY, "explore", "d", "r", 0.7));

        orch.tick("a1", "t1", descriptorWithGoals());
        var second = orch.tick("a1", "t1", descriptorWithGoals());

        assertThat(second).isInstanceOf(GoalProposalTick.NoChange.class);
    }

    @Test
    void compositorGuarantee_noSideEffects() {
        setupDrives("a1", "t1", DriveAxis.CURIOSITY, 0.7);
        when(curiosityMapper.evaluate("a1", "t1", driveIntensity(DriveAxis.CURIOSITY, 0.7)))
                .thenReturn(new DriveGoalProposal(
                        DriveAxis.CURIOSITY, "explore", "d", "r", 0.7));

        var tick1 = orchestrator.tick("a1", "t1", descriptorWithGoals());
        assertThat(tick1).isInstanceOf(GoalProposalTick.Changes.class);

        assertThat(orchestrator.currentProposals("a1", "t1")).isPresent();
    }

    @Test
    void currentProposals_emptyWhenNoTick() {
        assertThat(orchestrator.currentProposals("a1", "t1")).isEmpty();
    }

    @Test
    void mapperReturningNull_isSkipped() {
        setupDrives("a1", "t1", DriveAxis.CURIOSITY, 0.7);
        when(curiosityMapper.evaluate("a1", "t1", driveIntensity(DriveAxis.CURIOSITY, 0.7)))
                .thenReturn(null);

        var tick = orchestrator.tick("a1", "t1", descriptorWithGoals());
        assertThat(tick).isInstanceOf(GoalProposalTick.NoChange.class);
    }

    @Test
    void failureAbandonment_whenThresholdExceeded() {
        GoalSignalStore store = mock(GoalSignalStore.class);
        when(store.outcomeCounts("a1", "t1"))
                .thenReturn(Map.of("explore-knowledge-gaps", new GoalOutcomeCounts(0, 5)));

        var config = new GoalProposalConfig(0.4, 0.2, 3, Duration.ofMinutes(120),
                Duration.ZERO, 5);
        var orch = new GoalProposalOrchestrator(
                driveOrchestrator, List.of(curiosityMapper), Optional.of(store), config, clock);

        var descriptor = AgentDescriptor.builder()
                .agentId("a1").name("A").slot("s").tenancyId("t1")
                .goals(List.of(driveGoal("explore-knowledge-gaps"))).build();

        setupDrives("a1", "t1", DriveAxis.CURIOSITY, 0.7);

        var tick = orch.tick("a1", "t1", descriptor);

        assertThat(tick).isInstanceOf(GoalProposalTick.Changes.class);
        var proposed = (GoalProposalTick.Changes) tick;
        assertThat(proposed.abandonedGoalNames()).contains("explore-knowledge-gaps");
    }

    @Test
    void failureSuppression_preventsReproposal() {
        GoalSignalStore store = mock(GoalSignalStore.class);
        when(store.outcomeCounts("a1", "t1"))
                .thenReturn(Map.of("explore-knowledge-gaps", new GoalOutcomeCounts(0, 5)));

        var config = new GoalProposalConfig(0.4, 0.2, 3, Duration.ofMinutes(120),
                Duration.ZERO, 5);
        var orch = new GoalProposalOrchestrator(
                driveOrchestrator, List.of(curiosityMapper), Optional.of(store), config, clock);

        var descriptor = AgentDescriptor.builder()
                .agentId("a1").name("A").slot("s").tenancyId("t1")
                .goals(List.of(driveGoal("explore-knowledge-gaps"))).build();

        setupDrives("a1", "t1", DriveAxis.CURIOSITY, 0.7);
        when(curiosityMapper.evaluate("a1", "t1", driveIntensity(DriveAxis.CURIOSITY, 0.7)))
                .thenReturn(new DriveGoalProposal(
                        DriveAxis.CURIOSITY, "explore-knowledge-gaps", "d", "r", 0.7));

        orch.tick("a1", "t1", descriptor);

        var emptyDescriptor = descriptorWithGoals();
        when(store.outcomeCounts("a1", "t1")).thenReturn(Map.of());
        var tick2 = orch.tick("a1", "t1", emptyDescriptor);

        if (tick2 instanceof GoalProposalTick.Changes p) {
            assertThat(p.newProposals().stream().map(DriveGoalProposal::goalName).toList())
                    .doesNotContain("explore-knowledge-gaps");
        }
    }

    @Test
    void usesFormationStrategy_whenPresent() {
        DriveGoalFormationStrategy strategy = mock(DriveGoalFormationStrategy.class);
        var orch = new GoalProposalOrchestrator(
                driveOrchestrator, List.of(curiosityMapper), strategy,
                Optional.empty(), GoalProposalConfig.defaults(), clock);

        setupDrives("a1", "t1", DriveAxis.CURIOSITY, 0.7);
        when(strategy.propose(org.mockito.ArgumentMatchers.any(DriveGoalFormationContext.class)))
                .thenReturn(new DriveGoalProposal(
                        DriveAxis.CURIOSITY, "llm-explore-physics",
                        "Deep exploration of physics concepts", "curiosity: LLM refined", 0.7));

        var tick = orch.tick("a1", "t1", descriptorWithGoals());

        assertThat(tick).isInstanceOf(GoalProposalTick.Changes.class);
        var proposed = (GoalProposalTick.Changes) tick;
        assertThat(proposed.newProposals()).hasSize(1);
        assertThat(proposed.newProposals().get(0).goalName()).isEqualTo("llm-explore-physics");
    }

    @Test
    void fallsBackToMapper_whenStrategyReturnsNull() {
        DriveGoalFormationStrategy strategy = mock(DriveGoalFormationStrategy.class);
        var orch = new GoalProposalOrchestrator(
                driveOrchestrator, List.of(curiosityMapper), strategy,
                Optional.empty(), GoalProposalConfig.defaults(), clock);

        setupDrives("a1", "t1", DriveAxis.CURIOSITY, 0.7);
        when(strategy.propose(org.mockito.ArgumentMatchers.any(DriveGoalFormationContext.class)))
                .thenReturn(null);
        when(curiosityMapper.evaluate("a1", "t1", driveIntensity(DriveAxis.CURIOSITY, 0.7)))
                .thenReturn(new DriveGoalProposal(
                        DriveAxis.CURIOSITY, "explore-knowledge-gaps",
                        "Heuristic fallback", "curiosity: heuristic", 0.7));

        var tick = orch.tick("a1", "t1", descriptorWithGoals());

        assertThat(tick).isInstanceOf(GoalProposalTick.Changes.class);
        var proposed = (GoalProposalTick.Changes) tick;
        assertThat(proposed.newProposals().get(0).goalName()).isEqualTo("explore-knowledge-gaps");
    }

    @Test
    void strategyReceivesCorrectContext() {
        org.mockito.ArgumentCaptor<DriveGoalFormationContext> captor =
                org.mockito.ArgumentCaptor.forClass(DriveGoalFormationContext.class);
        DriveGoalFormationStrategy strategy = mock(DriveGoalFormationStrategy.class);
        when(strategy.propose(captor.capture())).thenReturn(null);

        var existing = new AgentGoal("assigned-goal", "Do work",
                                     GoalPriority.PRIMARY, Visibility.PUBLIC, List.of(), null);
        var descriptor = AgentDescriptor.builder()
                                        .agentId("a1").name("A").slot("s").tenancyId("t1")
                                        .goals(List.of(existing)).build();

        var orch = new GoalProposalOrchestrator(
                driveOrchestrator, List.of(), strategy,
                Optional.empty(), GoalProposalConfig.defaults(), clock);

        setupDrives("a1", "t1", DriveAxis.CURIOSITY, 0.65);
        orch.tick("a1", "t1", descriptor);

        var ctx = captor.getValue();
        assertThat(ctx.agentId()).isEqualTo("a1");
        assertThat(ctx.tenantId()).isEqualTo("t1");
        assertThat(ctx.axis()).isEqualTo(DriveAxis.CURIOSITY);
        assertThat(ctx.intensity()).isEqualTo(0.65);
        assertThat(ctx.existingGoals()).containsExactly(existing);
        assertThat(ctx.remainingCapacity()).isEqualTo(3);
    }

    @Test
    void fallsBackToMapper_whenStrategyThrows() {
        DriveGoalFormationStrategy strategy = mock(DriveGoalFormationStrategy.class);
        when(strategy.propose(org.mockito.ArgumentMatchers.any(DriveGoalFormationContext.class)))
                .thenThrow(new RuntimeException("LLM unavailable"));

        var orch = new GoalProposalOrchestrator(
                driveOrchestrator, List.of(curiosityMapper), strategy,
                Optional.empty(), GoalProposalConfig.defaults(), clock);

        setupDrives("a1", "t1", DriveAxis.CURIOSITY, 0.7);
        when(curiosityMapper.evaluate("a1", "t1", driveIntensity(DriveAxis.CURIOSITY, 0.7)))
                .thenReturn(new DriveGoalProposal(
                        DriveAxis.CURIOSITY, "explore-knowledge-gaps",
                        "Heuristic fallback after exception", "curiosity: heuristic", 0.7));

        var tick = orch.tick("a1", "t1", descriptorWithGoals());

        assertThat(tick).isInstanceOf(GoalProposalTick.Changes.class);
        var proposed = (GoalProposalTick.Changes) tick;
        assertThat(proposed.newProposals().get(0).goalName()).isEqualTo("explore-knowledge-gaps");
    }


    @Test
    void registeredGoals_returnsOnlyRegisteredNotCached() {
        var proposal = new DriveGoalProposal(
                DriveAxis.CURIOSITY, "Learn AI",
                "Study artificial intelligence fundamentals",
                "High curiosity drive", 0.8);
        orchestrator.registerGoals("agent1", "tenant1", List.of(proposal));

        List<DriveGoalProposal> registered = orchestrator.registeredGoals("agent1", "tenant1");

        assertThat(registered).hasSize(1);
        assertThat(registered.getFirst().goalName()).isEqualTo("Learn AI");
    }

    @Test
    void registeredGoals_returnsEmptyListWhenNoState() {
        List<DriveGoalProposal> registered = orchestrator.registeredGoals("unknown", "tenant");

        assertThat(registered).isEmpty();
    }

    private void setupDrives(String agentId, String tenantId, DriveAxis axis, double intensity) {
        var profile = new DriveProfile(agentId, tenantId,
                Map.of(axis, driveIntensity(axis, intensity)),
                intensity, axis, Instant.now(clock));
        when(driveOrchestrator.currentDrives(agentId, tenantId)).thenReturn(Optional.of(profile));
    }

    private DriveIntensity driveIntensity(DriveAxis axis, double intensity) {
        return new DriveIntensity(axis, intensity, "test");
    }

    private AgentDescriptor descriptorWithGoals(AgentGoal... goals) {
        return AgentDescriptor.builder()
                .agentId("a1").name("Agent").slot("default").tenancyId("t1")
                .goals(List.of(goals)).build();
    }

    private AgentGoal driveGoal(String name) {
        return new AgentGoal(name, "desc-" + name, GoalPriority.SECONDARY,
                Visibility.PUBLIC, List.of(),
                Map.of("source", "drive", "driveAxis", "CURIOSITY"));
    }

    @Test
    void registerGoalsAppearsInCurrentProposals() {
        var external = new DriveGoalProposal(
                DriveAxis.CURIOSITY, "understand-em-force",
                "Understand what Tesla means by electromagnetic force",
                "character briefing", 0.8);
        orchestrator.registerGoals("agent", "tenant", List.of(external));

        var proposals = orchestrator.currentProposals("agent", "tenant");
        assertThat(proposals).isPresent();
        assertThat(proposals.get()).hasSize(1);
        assertThat(proposals.get().get(0).goalName()).isEqualTo("understand-em-force");
    }

    @Test
    void registerGoalsMergesWithDriveProposals() {
        var external = new DriveGoalProposal(
                DriveAxis.CURIOSITY, "char-goal",
                "Character-specific goal", "character briefing", 0.7);
        orchestrator.registerGoals("agent", "tenant", List.of(external));

        orchestrator.tick("agent", "tenant", descriptorWithGoals());

        var proposals = orchestrator.currentProposals("agent", "tenant");
        assertThat(proposals).isPresent();
        assertThat(proposals.get().stream().map(DriveGoalProposal::goalName))
                .contains("char-goal");
    }

    @Test
    void deductiveStrategyProposalsAppearInChanges() {
        DeductiveGoalFormationStrategy deductive = ctx -> List.of(
                new DeductiveGoalProposal("seize-inheritance",
                                          "Eliminate benefactor", "greed + opportunity",
                                          Map.of(DriveAxis.AUTONOMY, 0.9), null, null));

        var orch = new GoalProposalOrchestrator(
                driveOrchestrator, List.of(curiosityMapper), null, deductive,
                Optional.empty(), null, null, null,
                GoalProposalConfig.defaults(), GoalEscalationConfig.defaults(), clock);

        setupDrives("a1", "t1", DriveAxis.CURIOSITY, 0.7);

        var tick = orch.tick("a1", "t1", descriptorWithGoals());

        assertThat(tick).isInstanceOf(GoalProposalTick.Changes.class);
        var changes = (GoalProposalTick.Changes) tick;
        assertThat(changes.newProposals().stream().map(DriveGoalProposal::goalName))
                .contains("seize-inheritance");
    }

    @Test
    void deductiveProposalsReduceCapacityForPerAxis() {
        var config = new GoalProposalConfig(0.4, 0.2, 2, Duration.ofMinutes(120),
                                            Duration.ofMinutes(60), 5);

        DeductiveGoalFormationStrategy deductive = ctx -> List.of(
                new DeductiveGoalProposal("deductive-goal-1", "d", "r",
                                          Map.of(DriveAxis.AUTONOMY, 0.8), null, null),
                new DeductiveGoalProposal("deductive-goal-2", "d", "r",
                                          Map.of(DriveAxis.COMPETENCE, 0.7), null, null));

        var orch = new GoalProposalOrchestrator(
                driveOrchestrator, List.of(curiosityMapper), null, deductive,
                Optional.empty(), null, null, null,
                config, GoalEscalationConfig.defaults(), clock);

        setupDrives("a1", "t1", DriveAxis.CURIOSITY, 0.8);
        when(curiosityMapper.evaluate("a1", "t1", driveIntensity(DriveAxis.CURIOSITY, 0.8)))
                .thenReturn(new DriveGoalProposal(
                        DriveAxis.CURIOSITY, "per-axis-goal", "d", "r", 0.8));

        var tick = orch.tick("a1", "t1", descriptorWithGoals());

        assertThat(tick).isInstanceOf(GoalProposalTick.Changes.class);
        var changes = (GoalProposalTick.Changes) tick;
        assertThat(changes.newProposals().stream().map(DriveGoalProposal::goalName))
                .contains("deductive-goal-1", "deductive-goal-2")
                .doesNotContain("per-axis-goal");
    }

    @Test
    void noDeductiveStrategyWorksAsBeforeWithExistingConstructor() {
        setupDrives("a1", "t1", DriveAxis.CURIOSITY, 0.7);
        when(curiosityMapper.evaluate("a1", "t1", driveIntensity(DriveAxis.CURIOSITY, 0.7)))
                .thenReturn(new DriveGoalProposal(
                        DriveAxis.CURIOSITY, "explore", "d", "r", 0.7));

        var tick = orchestrator.tick("a1", "t1", descriptorWithGoals());

        assertThat(tick).isInstanceOf(GoalProposalTick.Changes.class);
        var changes = (GoalProposalTick.Changes) tick;
        assertThat(changes.newProposals().get(0).goalName()).isEqualTo("explore");
    }

    @Test
    void appendGoalsAddsToExistingRegisteredGoals() {
        var goal1 = new DriveGoalProposal(DriveAxis.AUTONOMY, "goal-1", "desc1", "reason1", 0.5);
        var goal2 = new DriveGoalProposal(DriveAxis.AUTONOMY, "goal-2", "desc2", "reason2", 0.6);
        var goal3 = new DriveGoalProposal(DriveAxis.AUTONOMY, "goal-3", "desc3", "reason3", 0.7);

        orchestrator.registerGoals("agent1", "tenant1", List.of(goal1));
        orchestrator.appendGoals("agent1", "tenant1", List.of(goal2, goal3));

        var registered = orchestrator.registeredGoals("agent1", "tenant1");
        assertThat(registered).hasSize(3);
        assertThat(registered).extracting(DriveGoalProposal::goalName)
                              .containsExactly("goal-1", "goal-2", "goal-3");
    }

    @Test
    void registerGoalsReplacesAppendedGoals() {
        var goal1 = new DriveGoalProposal(DriveAxis.AUTONOMY, "appended", "desc", "reason", 0.5);
        var goal2 = new DriveGoalProposal(DriveAxis.AUTONOMY, "replaced", "desc", "reason", 0.6);

        orchestrator.appendGoals("agent1", "tenant1", List.of(goal1));
        orchestrator.registerGoals("agent1", "tenant1", List.of(goal2));

        var registered = orchestrator.registeredGoals("agent1", "tenant1");
        assertThat(registered).hasSize(1);
        assertThat(registered.getFirst().goalName()).isEqualTo("replaced");
    }

    @Test
    void registerParticipantGoalsDoesNotClobberOtherNamespace() {
        var proactiveGoal = new DriveGoalProposal(
                DriveAxis.AFFILIATION, "proactive-avoidance-library", "desc", "reason", 0.8);
        var regulationGoal = new DriveGoalProposal(
                DriveAxis.AUTONOMY, "regulation-reappraise", "desc", "reason", 0.6);

        orchestrator.registerParticipantGoals("proactive", "a1", "t1",
                                              List.of(proactiveGoal));
        orchestrator.registerParticipantGoals("regulation", "a1", "t1",
                                              List.of(regulationGoal));

        var proposals = orchestrator.currentProposals("a1", "t1");
        assertThat(proposals).isPresent();
        assertThat(proposals.get()).hasSize(2);
        assertThat(proposals.get()).extracting(DriveGoalProposal::goalName)
                                   .containsExactlyInAnyOrder("proactive-avoidance-library", "regulation-reappraise");
    }

    @Test
    void registerParticipantGoalsReplacesOnlySameNamespace() {
        var goal1 = new DriveGoalProposal(DriveAxis.AFFILIATION, "goal-1", "desc", "reason", 0.8);
        var goal2 = new DriveGoalProposal(DriveAxis.AUTONOMY, "goal-2", "desc", "reason", 0.6);
        var goal3 = new DriveGoalProposal(DriveAxis.AFFILIATION, "goal-3", "desc", "reason", 0.9);

        orchestrator.registerParticipantGoals("ns-a", "a1", "t1", List.of(goal1));
        orchestrator.registerParticipantGoals("ns-b", "a1", "t1", List.of(goal2));
        orchestrator.registerParticipantGoals("ns-a", "a1", "t1", List.of(goal3));

        var proposals = orchestrator.currentProposals("a1", "t1");
        assertThat(proposals).isPresent();
        assertThat(proposals.get()).hasSize(2);
        assertThat(proposals.get()).extracting(DriveGoalProposal::goalName)
                                   .containsExactlyInAnyOrder("goal-3", "goal-2");
    }

    @Test
    void registerParticipantGoalsEmptyListClearsNamespace() {
        var goal1 = new DriveGoalProposal(DriveAxis.AFFILIATION, "goal-1", "desc", "reason", 0.8);

        orchestrator.registerParticipantGoals("ns-a", "a1", "t1", List.of(goal1));
        orchestrator.registerParticipantGoals("ns-a", "a1", "t1", List.of());

        var proposals = orchestrator.currentProposals("a1", "t1");
        assertThat(proposals).isEmpty();
    }


    @Test
    void requestAbandonment_includesGoalInNextTick() {
        setupDrives("a1", "t1", DriveAxis.CURIOSITY, 0.7);

        var goal       = driveGoal("doomed-goal");
        var descriptor = descriptorWithGoals(goal);

        orchestrator.requestAbandonment("a1", "t1", "doomed-goal", "resignation: perceived as uncontrollable");

        var tick = orchestrator.tick("a1", "t1", descriptor);
        assertThat(tick).isInstanceOf(GoalProposalTick.Changes.class);
        var changes = (GoalProposalTick.Changes) tick;
        assertThat(changes.abandonedGoalNames()).contains("doomed-goal");
    }

    @Test
    void requestDemotion_includesAdjustmentInNextTick() {
        setupDrives("a1", "t1", DriveAxis.CURIOSITY, 0.7);

        var goal = new AgentGoal("deferred-goal", "Test goal", GoalPriority.PRIMARY,
                                 Visibility.PUBLIC, List.of(),
                                 Map.of("source", "drive", "driveAxis", "CURIOSITY"));
        var descriptor = descriptorWithGoals(goal);

        orchestrator.requestDemotion("a1", "t1", "deferred-goal",
                                     GoalPriority.SECONDARY, "procrastination: deferring to reduce cognitive load");

        var tick = orchestrator.tick("a1", "t1", descriptor);
        assertThat(tick).isInstanceOf(GoalProposalTick.Changes.class);
        var changes = (GoalProposalTick.Changes) tick;
        assertThat(changes.priorityAdjustments()).hasSize(1);
        assertThat(changes.priorityAdjustments().get(0).goalName()).isEqualTo("deferred-goal");
        assertThat(changes.priorityAdjustments().get(0).newPriority()).isEqualTo(GoalPriority.SECONDARY);
        assertThat(changes.priorityAdjustments().get(0).reason()).contains("procrastination");
    }

    @Test
    void evictIdleRemovesStaleEntries() {
        var goal = new DriveGoalProposal(DriveAxis.CURIOSITY, "test-goal", "desc", "reason", 0.5);
        orchestrator.registerGoals("a1", "t1", List.of(goal));
        setupDrives("a1", "t1", DriveAxis.CURIOSITY, 0.5);
        orchestrator.tick("a1", "t1", descriptorWithGoals());

        assertThat(orchestrator.registeredGoals("a1", "t1")).isNotEmpty();

        orchestrator.evictIdle(Duration.ZERO);

        assertThat(orchestrator.registeredGoals("a1", "t1")).isEmpty();
    }
}
