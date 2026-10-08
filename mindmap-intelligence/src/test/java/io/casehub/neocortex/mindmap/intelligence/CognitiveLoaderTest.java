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
package io.casehub.neocortex.mindmap.intelligence;

import io.casehub.neocortex.cognitive.index.CognitiveDefaults;
import io.casehub.neocortex.mindmap.EdgeTypeDefinition;
import io.casehub.neocortex.mindmap.MindMapVocabulary;
import io.casehub.neocortex.mindmap.VocabularyConflictException;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CognitiveLoaderTest {

    @Test
    void registersVocabularyFromProfiles() {
        var vocab = new MindMapVocabulary(List.of(
                new EdgeTypeDefinition("knows", Set.of("knows-about"), null)));
        var defaults = CognitiveDefaults.empty("alice").withVocabulary(vocab);
        var store = new InMemoryMindMapStore();

        var loader = new CognitiveLoader(store, null, List.of(defaults));
        loader.init();

        // Vocabulary was registered — conflicting alias should throw
        var conflict = new MindMapVocabulary(List.of(
                new EdgeTypeDefinition("other", Set.of("knows-about"), null)));
        assertThatThrownBy(() -> store.registerVocabulary(conflict))
                .isInstanceOf(VocabularyConflictException.class);
    }

    @Test
    void nullVocabulary_skippedGracefully() {
        var defaults = CognitiveDefaults.empty("bob");
        var store = new InMemoryMindMapStore();

        var loader = new CognitiveLoader(store, null, List.of(defaults));
        loader.init();

        // No vocabulary registered — should accept any registration
        store.registerVocabulary(new MindMapVocabulary(List.of(
                new EdgeTypeDefinition("anything", Set.of(), null))));
    }

    @Test
    void emptyProfiles_noOp() {
        var store  = new InMemoryMindMapStore();
        var loader = new CognitiveLoader(store, null, List.of());
        loader.init();
    }

    @Test
    void multipleProfiles_allVocabulariesRegistered() {
        var vocab1 = new MindMapVocabulary(List.of(
                new EdgeTypeDefinition("knows", Set.of("knows-about"), null)));
        var vocab2 = new MindMapVocabulary(List.of(
                new EdgeTypeDefinition("works-at", Set.of("employed-by"), null)));
        var alice = CognitiveDefaults.empty("alice").withVocabulary(vocab1);
        var carol = CognitiveDefaults.empty("carol").withVocabulary(vocab2);
        var store = new InMemoryMindMapStore();

        var loader = new CognitiveLoader(store, null, List.of(alice, carol));
        loader.init();

        // Both vocabularies registered
        var conflict1 = new MindMapVocabulary(List.of(
                new EdgeTypeDefinition("x", Set.of("knows-about"), null)));
        assertThatThrownBy(() -> store.registerVocabulary(conflict1))
                .isInstanceOf(VocabularyConflictException.class);
        var conflict2 = new MindMapVocabulary(List.of(
                new EdgeTypeDefinition("y", Set.of("employed-by"), null)));
        assertThatThrownBy(() -> store.registerVocabulary(conflict2))
                .isInstanceOf(VocabularyConflictException.class);
    }

    @Test
    void registersCognitiveTypesInTypeRegistry() {
        var store    = new InMemoryMindMapStore();
        var registry = new TypeRegistry(store);
        var loader   = new CognitiveLoader(store, registry, List.of());
        loader.init();

        assertThat(registry.typeExists("cognitive", "default")).isTrue();
        assertThat(registry.typeExists("belief", "default")).isTrue();
        assertThat(registry.typeExists("intention", "default")).isTrue();
        assertThat(registry.typeExists("prediction", "default")).isTrue();
        assertThat(registry.typeExists("judgment", "default")).isTrue();
        assertThat(registry.typeExists("fear", "default")).isTrue();
        assertThat(registry.typeExists("desire", "default")).isTrue();
    }

    @Test
    void cognitiveTypesAreSubtypesOfCognitive() {
        var store    = new InMemoryMindMapStore();
        var registry = new TypeRegistry(store);
        var loader   = new CognitiveLoader(store, registry, List.of());
        loader.init();

        var subtypes = registry.subtypesOf("cognitive", "default");
        assertThat(subtypes).containsExactlyInAnyOrder(
                "belief", "goal", "prediction", "judgment", "fear", "formative-experience");
    }

    @Test
    void cognitiveTypesHaveJavaClassAssociations() {
        var store    = new InMemoryMindMapStore();
        var registry = new TypeRegistry(store);
        var loader   = new CognitiveLoader(store, registry, List.of());
        loader.init();

        assertThat(registry.javaClass("belief", "default")).contains(Belieflike.class);
        assertThat(registry.javaClass("goal", "default")).contains(Goallike.class);
        assertThat(registry.javaClass("intention", "default")).contains(Goallike.class);
        assertThat(registry.javaClass("prediction", "default")).contains(Predictive.class);
        assertThat(registry.javaClass("judgment", "default")).contains(Evaluative.class);
        assertThat(registry.javaClass("fear", "default")).contains(Fearlike.class);
        assertThat(registry.javaClass("desire", "default")).contains(Goallike.class);
    }

    @Test
    void schemaForBelief_derivesFromInterface() {
        var store    = new InMemoryMindMapStore();
        var registry = new TypeRegistry(store);
        var loader   = new CognitiveLoader(store, registry, List.of());
        loader.init();

        var schema = registry.schemaFor("belief", "default");
        assertThat(schema).containsKeys("subject", "status", "basis");
    }

    @Test
    void goalTypeRegisteredUnderCognitive() {
        var store    = new InMemoryMindMapStore();
        var registry = new TypeRegistry(store);
        var loader   = new CognitiveLoader(store, registry, List.of());
        loader.init();

        assertThat(registry.typeExists("goal", "default")).isTrue();
        assertThat(registry.subtypesOf("cognitive", "default")).contains("goal");
        assertThat(registry.javaClass("goal", "default")).contains(Goallike.class);
    }

    @Test
    void intentionAndDesireAreSubtypesOfGoal() {
        var store    = new InMemoryMindMapStore();
        var registry = new TypeRegistry(store);
        var loader   = new CognitiveLoader(store, registry, List.of());
        loader.init();

        assertThat(registry.subtypesOf("goal", "default"))
                .containsExactlyInAnyOrder("intention", "desire");
        assertThat(registry.javaClass("intention", "default")).contains(Goallike.class);
        assertThat(registry.javaClass("desire", "default")).contains(Goallike.class);
    }

    @Test
    void goalVocabularyRegistered() {
        var store  = new InMemoryMindMapStore();
        var loader = new CognitiveLoader(store, null, List.of());
        loader.init();

        // Goal vocabulary already registered — conflicting alias should throw
        var conflict = new MindMapVocabulary(List.of(
                new EdgeTypeDefinition("other", Set.of("unblocks"), null)));
        assertThatThrownBy(() -> store.registerVocabulary(conflict))
                .isInstanceOf(VocabularyConflictException.class);
    }

    @Test
    void goalSchemaDerivesFromGoallikeInterface() {
        var store    = new InMemoryMindMapStore();
        var registry = new TypeRegistry(store);
        var loader   = new CognitiveLoader(store, registry, List.of());
        loader.init();

        var schema = registry.schemaFor("goal", "default");
        assertThat(schema).containsKeys("description", "status", "horizon",
                                        "origin", "resolution", "urgency", "feasibility");
    }


}
