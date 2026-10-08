package io.casehub.neocortex.mindmap.intelligence;

import io.casehub.neocortex.mindmap.EdgeInput;
import io.casehub.neocortex.mindmap.MindMapNode;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.MindMapSubgraph;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SchemaField;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@ApplicationScoped
public class TypeRegistry {

    private static final Logger LOG = Logger.getLogger(TypeRegistry.class.getName());
    private static final String SUBTYPE_OF = "subtype-of";
    private static final String JAVA_CLASS = "java-class";
    private static final String SCHEMA_PREFIX = "schema.";

    private static final Map<String, Class<?>> CORE_TYPES = Map.of(
        SubgraphTypes.PERSON, Personable.class,
        SubgraphTypes.PROJECT, Projectlike.class,
        SubgraphTypes.ORGANISATION, Organisational.class
    );
    static final Map<String, Class<?>> COGNITIVE_TYPES = Map.ofEntries(
            Map.entry("belief", Belieflike.class),
            Map.entry("goal", Goallike.class),
            Map.entry("intention", Goallike.class),
            Map.entry("prediction", Predictive.class),
            Map.entry("judgment", Evaluative.class),
            Map.entry("fear", Fearlike.class),
            Map.entry("desire", Goallike.class),
            Map.entry("formative-experience", Belieflike.class)
                                                                     );
    static final java.util.Set<String> GOAL_SUBTYPES   = java.util.Set.of("intention", "desire");


    private final MindMapStore store;
    private final Map<String, BootstrappedTenant> tenantCache = new ConcurrentHashMap<>();

    @Inject
    TypeRegistry(Instance<MindMapStore> store) {
        this.store = store.isResolvable() ? store.get() : null;
    }

    public TypeRegistry(MindMapStore store) {
        this.store = store;
    }

    public boolean typeExists(String typeName, String tenantId) {
        return ensureBootstrapped(tenantId).resolveTypeNode(typeName) != null;
    }

    public List<String> subtypesOf(String typeName, String tenantId) {
        BootstrappedTenant bt = ensureBootstrapped(tenantId);
        MindMapNode typeNode = bt.resolveTypeNode(typeName);
        if (typeNode == null) return List.of();
        List<String> subtypes = new ArrayList<>();
        store.neighbors(typeNode.id(), SUBTYPE_OF, tenantId).stream()
            .filter(e -> e.targetNodeId().equals(typeNode.id()))
            .forEach(e -> {
                MindMapNode child = store.getNode(e.sourceNodeId(), tenantId);
                if (child != null) subtypes.add(child.name());
            });
        return subtypes;
    }

    public Optional<Class<?>> javaClass(String typeName, String tenantId) {
        MindMapNode typeNode = ensureBootstrapped(tenantId).resolveTypeNode(typeName);
        if (typeNode == null) return Optional.empty();
        return typeNode.property(JAVA_CLASS).flatMap(className -> {
            try {
                return Optional.of(Class.forName(className));
            } catch (ClassNotFoundException e) {
                LOG.warning("java-class not found: " + className);
                return Optional.empty();
            }
        });
    }

    public Map<String, SchemaField> schemaFor(String typeName, String tenantId) {
        MindMapNode typeNode = ensureBootstrapped(tenantId).resolveTypeNode(typeName);
        if (typeNode == null) return Map.of();

        Map<String, SchemaField> schema = new LinkedHashMap<>();
        typeNode.properties().forEach((key, value) -> {
            if (key.startsWith(SCHEMA_PREFIX) && key.endsWith(".type")) {
                String fieldName = key.substring(SCHEMA_PREFIX.length(),
                    key.length() - ".type".length());
                boolean required = Boolean.parseBoolean(
                    typeNode.property(SCHEMA_PREFIX + fieldName + ".required").orElse("false"));
                boolean collection = Boolean.parseBoolean(
                    typeNode.property(SCHEMA_PREFIX + fieldName + ".collection").orElse("false"));
                String description = typeNode.property(SCHEMA_PREFIX + fieldName + ".description").orElse(null);
                String enumStr = typeNode.property(SCHEMA_PREFIX + fieldName + ".enum").orElse(null);
                List<String> enumValues = enumStr != null ? List.of(enumStr.split(",")) : null;
                schema.put(fieldName, new SchemaField(fieldName, value, required, collection, description, enumValues));
            }
        });

        if (schema.isEmpty()) {
            return javaClass(typeName, tenantId)
                .map(TypeRegistry::deriveSchemaFromInterface)
                .orElse(Map.of());
        }
        return schema;
    }

    public void registerType(String typeName, String tenantId) {
        registerType(typeName, null, tenantId);
    }

    public void registerType(String typeName, String parentType, String tenantId) {
        BootstrappedTenant bt = ensureBootstrapped(tenantId);
        String normalized = typeName.strip().toLowerCase();
        if (bt.resolveTypeNode(normalized) != null) return;

        String nodeId = store.addNode(
            NodeInput.of(normalized, bt.typeSystemSubgraphId), tenantId);
        bt.typeNodeIds.put(normalized, nodeId);

        if (parentType != null) {
            MindMapNode parentNode = bt.resolveTypeNode(parentType.strip().toLowerCase());
            if (parentNode != null) {
                store.addEdge(EdgeInput.of(nodeId, parentNode.id(), SUBTYPE_OF)
                    .withProvenance("type-registry"), tenantId);
            }
        }
    }

    public void registerType(String typeName, String parentType, Class<?> javaClass, String tenantId) {
        BootstrappedTenant bt         = ensureBootstrapped(tenantId);
        String             normalized = typeName.strip().toLowerCase();
        if (bt.resolveTypeNode(normalized) != null) {return;}

        Map<String, String> props = new HashMap<>();
        if (javaClass != null) {
            props.put(JAVA_CLASS, javaClass.getName());
            deriveSchemaFromInterface(javaClass).forEach((fieldName, sf) -> {
                props.put(SCHEMA_PREFIX + fieldName + ".type", sf.type());
                props.put(SCHEMA_PREFIX + fieldName + ".source", "java");
            });
        }

        String nodeId = store.addNode(
                NodeInput.of(normalized, bt.typeSystemSubgraphId)
                         .withProperties(props),
                tenantId);
        bt.typeNodeIds.put(normalized, nodeId);

        if (parentType != null) {
            MindMapNode parentNode = bt.resolveTypeNode(parentType.strip().toLowerCase());
            if (parentNode != null) {
                store.addEdge(EdgeInput.of(nodeId, parentNode.id(), SUBTYPE_OF)
                                       .withProvenance("type-registry"), tenantId);
            }
        }
    }


    private BootstrappedTenant ensureBootstrapped(String tenantId) {
        if (store == null) return BootstrappedTenant.EMPTY;
        return tenantCache.computeIfAbsent(tenantId, this::bootstrap);
    }

    private BootstrappedTenant bootstrap(String tenantId) {
        String sgId = findTypeSystemSubgraph(tenantId);
        if (sgId == null) {
            sgId = createTypeSystemSubgraph(tenantId);
        }

        BootstrappedTenant bt = new BootstrappedTenant(sgId, store, tenantId);
        for (MindMapNode node : store.nodesIn(sgId, tenantId)) {
            bt.typeNodeIds.put(node.name(), node.id());
        }

        createCoreTypesIfAbsent(bt, tenantId);
        return bt;
    }

    private String findTypeSystemSubgraph(String tenantId) {
        for (MindMapSubgraph sg : store.listSubgraphs(tenantId)) {
            if (SubgraphTypes.TYPE_SYSTEM.equals(sg.type())) {
                return sg.id();
            }
        }
        return null;
    }

    private String createTypeSystemSubgraph(String tenantId) {
        try {
            return store.createSubgraph(
                new SubgraphInput("Type System", SubgraphTypes.TYPE_SYSTEM, null), tenantId);
        } catch (IllegalStateException e) {
            String existing = findTypeSystemSubgraph(tenantId);
            if (existing != null) return existing;
            throw e;
        }
    }

    private void createCoreTypesIfAbsent(BootstrappedTenant bt, String tenantId) {
        List<NodeInput> newInputs    = new ArrayList<>();
        List<String>    newTypeNames = new ArrayList<>();

        for (String coreType : List.of(
                SubgraphTypes.GENERAL,
                SubgraphTypes.PERSON, SubgraphTypes.PROJECT,
                SubgraphTypes.ORGANISATION, SubgraphTypes.CONCEPT,
                SubgraphTypes.RESEARCH_AREA)) {
            if (!bt.typeNodeIds.containsKey(coreType)) {
                Map<String, String> props     = new HashMap<>();
                Class<?>            javaClass = CORE_TYPES.get(coreType);
                if (javaClass != null) {
                    props.put(JAVA_CLASS, javaClass.getName());
                    deriveSchemaFromInterface(javaClass).forEach((fieldName, sf) -> {
                        props.put(SCHEMA_PREFIX + fieldName + ".type", sf.type());
                        props.put(SCHEMA_PREFIX + fieldName + ".source", "java");
                    });
                }
                newInputs.add(NodeInput.of(coreType, bt.typeSystemSubgraphId)
                                       .withProperties(props));
                newTypeNames.add(coreType);
            }
        }

        if (newInputs.isEmpty()) {return;}

        List<String> newIds = store.addNodes(newInputs, tenantId);
        for (int i = 0; i < newIds.size(); i++) {
            bt.typeNodeIds.put(newTypeNames.get(i), newIds.get(i));
        }

        List<EdgeInput> edgeInputs  = new ArrayList<>();
        MindMapNode     generalNode = bt.resolveTypeNode(SubgraphTypes.GENERAL);
        if (generalNode != null) {
            for (int i = 0; i < newTypeNames.size(); i++) {
                if (!newTypeNames.get(i).equals(SubgraphTypes.GENERAL)) {
                    edgeInputs.add(EdgeInput.of(newIds.get(i), generalNode.id(), SUBTYPE_OF)
                                            .withProvenance("type-registry"));
                }
            }
        }
        if (!edgeInputs.isEmpty()) {
            store.addEdges(edgeInputs, tenantId);
        }
    }

    static Map<String, SchemaField> deriveSchemaFromInterface(Class<?> traitInterface) {
        Map<String, SchemaField> schema = new LinkedHashMap<>();
        for (Method method : traitInterface.getDeclaredMethods()) {
            if (method.isDefault()) continue;
            if (method.getDeclaringClass() == Object.class) continue;
            String schemaType = mapReturnType(method.getReturnType());
            schema.put(method.getName(), new SchemaField(method.getName(), schemaType, false));
        }
        return schema;
    }

    private static String mapReturnType(Class<?> returnType) {
        if (returnType == String.class) return "string";
        if (returnType == Optional.class) return "string";
        if (returnType == int.class || returnType == Integer.class) return "number";
        if (returnType == long.class || returnType == Long.class) return "number";
        if (returnType == double.class || returnType == Double.class) return "number";
        if (returnType == boolean.class || returnType == Boolean.class) return "boolean";
        return "string";
    }

    private static class BootstrappedTenant {
        static final BootstrappedTenant EMPTY = new BootstrappedTenant("", null, null);
        final String typeSystemSubgraphId;
        final MindMapStore store;
        final String tenantId;
        final Map<String, String> typeNodeIds = new ConcurrentHashMap<>();

        BootstrappedTenant(String sgId, MindMapStore store, String tenantId) {
            this.typeSystemSubgraphId = sgId;
            this.store = store;
            this.tenantId = tenantId;
        }

        MindMapNode resolveTypeNode(String typeName) {
            if (store == null) return null;
            String nodeId = typeNodeIds.get(typeName);
            if (nodeId == null) return null;
            return store.getNode(nodeId, tenantId);
        }
    }
}
