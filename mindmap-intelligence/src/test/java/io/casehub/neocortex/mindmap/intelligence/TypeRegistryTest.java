package io.casehub.neocortex.mindmap.intelligence;

import io.casehub.neocortex.mindmap.SchemaField;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TypeRegistryTest {

    private InMemoryMindMapStore store;
    private TypeRegistry registry;
    private static final String TENANT = "t1";

    @BeforeEach
    void setUp() {
        store = new InMemoryMindMapStore();
        registry = new TypeRegistry(store);
    }

    @Test
    void bootstrapsTypeSystemSubgraphOnFirstAccess() {
        assertThat(registry.typeExists(SubgraphTypes.PERSON, TENANT)).isTrue();
        assertThat(registry.typeExists(SubgraphTypes.PROJECT, TENANT)).isTrue();
        assertThat(registry.typeExists(SubgraphTypes.ORGANISATION, TENANT)).isTrue();
        assertThat(registry.typeExists(SubgraphTypes.GENERAL, TENANT)).isTrue();
    }

    @Test
    void bootstrapIsIdempotent() {
        registry.typeExists(SubgraphTypes.PERSON, TENANT);
        registry.typeExists(SubgraphTypes.PERSON, TENANT);
        long typeSystemCount = store.listSubgraphs(TENANT).stream()
            .filter(sg -> SubgraphTypes.TYPE_SYSTEM.equals(sg.type()))
            .count();
        assertThat(typeSystemCount).isEqualTo(1);
    }

    @Test
    void registerType_createsDynamicType() {
        registry.registerType("research-topic", TENANT);
        assertThat(registry.typeExists("research-topic", TENANT)).isTrue();
    }

    @Test
    void registerType_withParent() {
        registry.registerType("researcher", SubgraphTypes.PERSON, TENANT);
        assertThat(registry.typeExists("researcher", TENANT)).isTrue();
        assertThat(registry.subtypesOf(SubgraphTypes.PERSON, TENANT))
            .contains("researcher");
    }

    @Test
    void javaClass_returnsMappingForCoreTypes() {
        assertThat(registry.javaClass(SubgraphTypes.PERSON, TENANT))
            .contains(Personable.class);
    }

    @Test
    void javaClass_returnsEmptyForDynamicTypes() {
        registry.registerType("custom-type", TENANT);
        assertThat(registry.javaClass("custom-type", TENANT)).isEmpty();
    }

    @Test
    void schemaFor_derivesCoreTypeSchemaFromInterface() {
        Map<String, SchemaField> schema = registry.schemaFor(SubgraphTypes.PERSON, TENANT);
        assertThat(schema).containsKey("birthday");
        assertThat(schema.get("birthday").type()).isEqualTo("string");
        assertThat(schema.get("birthday").required()).isFalse();
    }

    @Test
    void schemaFor_returnsEmptyForUnregisteredType() {
        assertThat(registry.schemaFor("nonexistent", TENANT)).isEmpty();
    }

    @Test
    void coreTypesAreSubtypesOfGeneral() {
        assertThat(registry.subtypesOf(SubgraphTypes.GENERAL, TENANT))
            .contains(SubgraphTypes.PERSON, SubgraphTypes.PROJECT, SubgraphTypes.ORGANISATION);
    }

    @Test
    void perTenantIsolation() {
        registry.registerType("custom-a", "t1");
        registry.registerType("custom-b", "t2");
        assertThat(registry.typeExists("custom-a", "t1")).isTrue();
        assertThat(registry.typeExists("custom-a", "t2")).isFalse();
        assertThat(registry.typeExists("custom-b", "t2")).isTrue();
        assertThat(registry.typeExists("custom-b", "t1")).isFalse();
    }

    @Test
    void formativeExperience_mapsToBelieflike() {
        assertThat(TypeRegistry.COGNITIVE_TYPES)
            .as("formative-experience should map to Belieflike so BeliefRevisionPhase can revise it")
            .containsKey("formative-experience");
        assertThat(TypeRegistry.COGNITIVE_TYPES.get("formative-experience"))
            .isEqualTo(Belieflike.class);
    }
}
