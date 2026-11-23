/*
 * Copyright 2026-Present The Case Hub Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.casehub.neocortex.cognition.core;

import io.casehub.neocortex.cognition.drive.DriveAxis;
import io.casehub.neocortex.cognition.drive.DriveComposer;
import io.casehub.neocortex.cognition.drive.DriveConfig;
import io.casehub.neocortex.cognition.drive.DriveIntensity;
import io.casehub.neocortex.cognition.drive.DriveOrchestrator;
import io.casehub.neocortex.cognition.drive.DriveSource;
import io.casehub.neocortex.cognition.mood.MoodConfig;
import io.casehub.neocortex.cognition.mood.MoodOrchestrator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import io.casehub.neocortex.cognition.goal.CognitiveGoalConfig;
import io.casehub.neocortex.cognition.goal.CognitiveGoalOrchestrator;
import io.casehub.neocortex.cognition.goal.GoalEscalationConfig;
import io.casehub.neocortex.cognition.goal.GoalProposalConfig;
import io.casehub.neocortex.cognition.goal.GoalProposalOrchestrator;
import io.casehub.neocortex.cognition.innerlife.InnerLifeConfig;
import io.casehub.neocortex.cognition.innerlife.InnerLifeOrchestrator;
import io.casehub.neocortex.cognition.mentalmodel.MentalModelConfig;
import io.casehub.neocortex.cognition.mentalmodel.MentalModelOrchestrator;
import io.casehub.neocortex.cognition.mood.GoalEmotionMoodBridge;
import io.casehub.neocortex.cognition.prompt.EntityKnowledgePromptSection;
import io.casehub.neocortex.cognition.strategy.StrategyLearningConfig;
import io.casehub.neocortex.cognition.strategy.StrategyLearningOrchestrator;
import io.casehub.neocortex.cognition.usermodel.UserModelConfig;
import io.casehub.neocortex.cognition.usermodel.UserModelOrchestrator;
import io.casehub.neocortex.cognitive.PadProjection;
import io.casehub.neocortex.cognitive.index.CognitiveMemoryRetriever;
import io.casehub.neocortex.cognitive.index.CognitiveProfile;
import io.casehub.neocortex.cognitive.index.DomainActivation;
import io.casehub.neocortex.memory.inmem.InMemoryMemoryStore;
import io.casehub.neocortex.mindmap.GoalAppraisal;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import io.casehub.platform.api.identity.CurrentPrincipal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;

class CognitivePipelineBuilderTest {

    private static final DriveSource NOOP_DRIVE = (a, t) -> new DriveIntensity(DriveAxis.CURIOSITY, 0.0, "none");

    private final MoodOrchestrator mood = new MoodOrchestrator(MoodConfig.defaults());
    private final DriveOrchestrator drives = new DriveOrchestrator(
            NOOP_DRIVE, NOOP_DRIVE, NOOP_DRIVE, NOOP_DRIVE,
            mood, new DriveComposer(), DriveConfig.defaults());

    private static InMemoryMemoryStore createMemoryStore() {
        return new InMemoryMemoryStore(
                new CurrentPrincipal() {
                    @Override
                    public String actorId()               {return "test-actor";}

                    @Override
                    public Set<String> groups() {return Set.of();}

                    @Override
                    public String tenancyId()             {return "test-tenant";}

                    @Override
                    public boolean isCrossTenantAdmin()   {return true;}
                });
    }


    @Test
    void buildWithRequiredParamsOnlyProducesCognitionCore() {
        var core = CognitivePipelineBuilder.create(mood, drives)
                .build();

        assertThat(core).isNotNull();
        assertThat(core.mood()).isSameAs(mood);
        assertThat(core.drives()).isSameAs(drives);
        assertThat(core.config()).isEqualTo(CognitionConfig.all());
    }

    @Test
    void buildWithOptionalOrchestratorsWiresThem() {
        var userModel   = new UserModelOrchestrator(null, UserModelConfig.defaults(), null);
        var mentalModel = new MentalModelOrchestrator(null, MentalModelConfig.defaults(), Clock.systemUTC(), null);
        var strategy    = new StrategyLearningOrchestrator(null, null, StrategyLearningConfig.defaults(), Clock.systemUTC(), null);
        var innerLife   = new InnerLifeOrchestrator(null, List.of(), InnerLifeConfig.defaults(), drives, null);
        var goals       = new GoalProposalOrchestrator(drives, List.of(), null, Optional.empty(), null, null, null, GoalProposalConfig.defaults(), GoalEscalationConfig.defaults(), Clock.systemUTC());

        var core = CognitivePipelineBuilder.create(mood, drives)
                                           .userModel(userModel)
                                           .mentalModel(mentalModel)
                                           .strategy(strategy)
                                           .innerLife(innerLife)
                                           .goals(goals)
                                           .build();

        assertThat(core.userModel()).isSameAs(userModel);
        assertThat(core.mentalModel()).isSameAs(mentalModel);
        assertThat(core.strategy()).isSameAs(strategy);
        assertThat(core.innerLife()).isSameAs(innerLife);
        assertThat(core.goals()).isSameAs(goals);
    }

    @Test
    void buildWithCustomConfigUsesIt() {
        var config = CognitionConfig.none();

        var core = CognitivePipelineBuilder.create(mood, drives)
                                           .config(config)
                                           .build();

        assertThat(core.config()).isSameAs(config);
    }

    @Test
    void buildDefaultsToAllConfig() {
        var core = CognitivePipelineBuilder.create(mood, drives).build();

        assertThat(core.config()).isEqualTo(CognitionConfig.all());
    }

    @Test
    void buildWithCognitiveProfileWiresParticipant() {
        var mindMapStore = new InMemoryMindMapStore();
        var memoryStore  = createMemoryStore();
        var profile      = new CognitiveProfile(mindMapStore, memoryStore);

        var core = CognitivePipelineBuilder.create(mood, drives)
                                           .cognitiveProfile(profile)
                                           .build();

        assertThat(core).isNotNull();
        assertThat(core.promptSections()).isNotNull();
    }

    @Test
    void buildWithDomainActivationAndMindMapStoreWiresParticipant() {
        var mindMapStore     = new InMemoryMindMapStore();
        var memoryStore      = createMemoryStore();
        var domainActivation = new DomainActivation(mindMapStore, memoryStore);

        var core = CognitivePipelineBuilder.create(mood, drives)
                                           .mindMapStore(mindMapStore)
                                           .domainActivation(domainActivation)
                                           .build();

        assertThat(core).isNotNull();
        assertThat(core.promptSections()).isNotNull();
    }

    @Test
    void buildWithDomainActivationButNoMindMapStoreIsNoOp() {
        var mindMapStore     = new InMemoryMindMapStore();
        var memoryStore      = createMemoryStore();
        var domainActivation = new DomainActivation(mindMapStore, memoryStore);

        var core = CognitivePipelineBuilder.create(mood, drives)
                                           .domainActivation(domainActivation)
                                           .build();

        assertThat(core).isNotNull();
        assertThat(core.promptSections()).isNotNull();
    }

    @Test
    void cognitiveProfileProducesEntityKnowledgeSectionAfterTick() {
        var mindMapStore = new InMemoryMindMapStore();
        var memoryStore  = createMemoryStore();
        var tenantId     = "test-tenant";

        var sgId = mindMapStore.createSubgraph(
                new SubgraphInput("people", SubgraphTypes.PERSON, null), tenantId);
        mindMapStore.addNode(NodeInput.of("Alice", sgId), tenantId);

        var profile = new CognitiveProfile(mindMapStore, memoryStore);

        var core = CognitivePipelineBuilder.create(mood, drives)
                                           .config(CognitionConfig.all().with("entityKnowledge", true))
                                           .cognitiveProfile(profile)
                                           .build();

        SubjectResolver resolver = (agentId, tid) -> Set.of("Alice");
        core.tick("agent-1", tenantId, null, resolver);

        var sections = core.promptSections();
        var hasEntityKnowledge = sections.stream()
                                         .anyMatch(s -> s instanceof EntityKnowledgePromptSection);
        assertThat(hasEntityKnowledge)
                .as("EntityKnowledgePromptSection should be present when entityKnowledge explicitly enabled")
                .isTrue();
    }

    @Test
    void cognitiveProfileSectionAbsentWhenEntityKnowledgeDisabled() {
        var mindMapStore = new InMemoryMindMapStore();
        var memoryStore  = createMemoryStore();
        var tenantId     = "test-tenant";

        var sgId = mindMapStore.createSubgraph(
                new SubgraphInput("people", SubgraphTypes.PERSON, null), tenantId);
        mindMapStore.addNode(NodeInput.of("Bob", sgId), tenantId);

        var profile = new CognitiveProfile(mindMapStore, memoryStore);
        var config  = CognitionConfig.all().with("entityKnowledge", false);

        var core = CognitivePipelineBuilder.create(mood, drives)
                                           .config(config)
                                           .cognitiveProfile(profile)
                                           .build();

        SubjectResolver resolver = (agentId, tid) -> Set.of("Bob");
        core.tick("agent-1", tenantId, null, resolver);

        var sections = core.promptSections();
        var hasEntityKnowledge = sections.stream()
                                         .anyMatch(s -> s instanceof EntityKnowledgePromptSection);
        assertThat(hasEntityKnowledge)
                .as("EntityKnowledgePromptSection should be absent when entityKnowledgeEnabled=false")
                .isFalse();
    }

    @Test
    void buildWithGoalEmotionMoodBridgeAndCognitiveGoalOrchestratorWiresThem() {
        var                                        mindMapStore = new InMemoryMindMapStore();
        var                                        memoryStore  = createMemoryStore();
        GoalAppraisal appraisal    = (goal, ctx) -> List.of();
        var goalOrchestrator = new CognitiveGoalOrchestrator(
                mindMapStore, appraisal, memoryStore,
                CognitiveGoalConfig.defaults(),
                (a, t) -> new PadProjection(0, 0, 0),
                Clock.systemUTC());
        var bridge = new GoalEmotionMoodBridge(
                goalOrchestrator, mood);

        var core = CognitivePipelineBuilder.create(mood, drives)
                                           .cognitiveGoalOrchestrator(goalOrchestrator)
                                           .goalEmotionMoodBridge(bridge)
                                           .build();

        assertThat(core).isNotNull();
    }

    @Test
    void buildWithCognitiveMemoryRetrieverWiresIt() {
        var store = createMemoryStore();
        var retriever = new CognitiveMemoryRetriever(
                store, List.of());

        var core = CognitivePipelineBuilder.create(mood, drives)
                                           .cognitiveMemoryRetriever(retriever)
                                           .build();

        assertThat(core.cognitiveMemoryRetriever()).isSameAs(retriever);
    }


    @Test
    void innerLifeDisabledNullsOutOrchestrator() {
        var config = CognitionConfig.all().with("innerLife", false);
        var innerLifeOrch = new InnerLifeOrchestrator(
                null, List.of(),
                InnerLifeConfig.defaults(),
                drives, null);

        var core = CognitivePipelineBuilder.create(mood, drives)
                                           .config(config)
                                           .innerLife(innerLifeOrch)
                                           .build();

        assertThat(core.innerLife())
                .as("innerLife() should be null when innerLifeEnabled=false")
                .isNull();
    }

    @Test
    void innerLifeEnabledPreservesOrchestrator() {
        var config = CognitionConfig.all();
        var innerLifeOrch = new InnerLifeOrchestrator(
                null, List.of(),
                InnerLifeConfig.defaults(),
                drives, null);

        var core = CognitivePipelineBuilder.create(mood, drives)
                                           .config(config)
                                           .innerLife(innerLifeOrch)
                                           .build();

        assertThat(core.innerLife())
                .as("innerLife() should be set when innerLifeEnabled=true")
                .isSameAs(innerLifeOrch);
    }


}
