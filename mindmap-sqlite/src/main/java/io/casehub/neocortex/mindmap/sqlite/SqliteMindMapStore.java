package io.casehub.neocortex.mindmap.sqlite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.casehub.neocortex.mindmap.ConfidenceOrigin;
import io.casehub.neocortex.mindmap.EdgeInput;
import io.casehub.neocortex.mindmap.EdgeTypeDefinition;
import io.casehub.neocortex.mindmap.MergeConflict;
import io.casehub.neocortex.mindmap.MergeResult;
import io.casehub.neocortex.mindmap.MindMapCapability;
import io.casehub.neocortex.mindmap.MindMapEdge;
import io.casehub.neocortex.mindmap.MindMapNode;
import io.casehub.neocortex.mindmap.MindMapQuery;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.MindMapSubgraph;
import io.casehub.neocortex.mindmap.MindMapVocabulary;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.NodeRef;
import io.casehub.neocortex.mindmap.NodeUpdate;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.neocortex.mindmap.SubgraphType;
import io.casehub.neocortex.mindmap.SupersessionStatus;
import io.casehub.neocortex.mindmap.ValidationTier;
import io.casehub.neocortex.mindmap.VocabularyConflictException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;
import org.sqlite.SQLiteConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Alternative
@Priority(1)
@ApplicationScoped
public class SqliteMindMapStore implements MindMapStore {

    @ConfigProperty(name = "casehub.mindmap.sqlite.path")
    String path;

    @ConfigProperty(name = "casehub.mindmap.sqlite.pool.max-size", defaultValue = "5")
    int maxPoolSize;

    @ConfigProperty(name = "casehub.mindmap.sqlite.busy-timeout-ms", defaultValue = "5000")
    int busyTimeoutMs;

    @Inject ObjectMapper objectMapper;

    private HikariDataSource dataSource;

    private final Map<String, String> canonicalEdgeTypes = new ConcurrentHashMap<>();
    private final Map<String, EdgeTypeDefinition> edgeTypeDefinitions = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        boolean isMemory = ":memory:".equals(path) || path.isBlank();
        int effectivePoolSize = isMemory ? 1 : maxPoolSize;

        SQLiteConfig sqLiteConfig = new SQLiteConfig();
        if (!isMemory) {
            sqLiteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
        }
        sqLiteConfig.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        sqLiteConfig.setBusyTimeout(busyTimeoutMs);
        sqLiteConfig.setCacheSize(64000);

        org.sqlite.SQLiteDataSource sqLiteDataSource = new org.sqlite.SQLiteDataSource(sqLiteConfig);
        sqLiteDataSource.setUrl("jdbc:sqlite:" + path);

        HikariConfig hikari = new HikariConfig();
        hikari.setDataSource(sqLiteDataSource);
        hikari.setMaximumPoolSize(effectivePoolSize);
        hikari.setMinimumIdle(1);

        dataSource = new HikariDataSource(hikari);

        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/mindmap-sqlite/migration")
            .load()
            .migrate();
    }

    @PreDestroy
    void shutdown() {
        if (dataSource != null) dataSource.close();
    }

    @Override
    public Set<MindMapCapability> capabilities() {
        return EnumSet.allOf(MindMapCapability.class);
    }

    // --- Vocabulary (in-memory, same as InMemoryMindMapStore) ---

    @Override
    public void registerVocabulary(MindMapVocabulary vocabulary) {
        for (EdgeTypeDefinition def : vocabulary.edgeTypes()) {
            String canonical = def.canonical();
            if (canonicalEdgeTypes.containsKey(canonical)
                && !edgeTypeDefinitions.containsKey(canonical)) {
                throw new VocabularyConflictException(
                    "'" + canonical + "' is already registered as an alias of another canonical type");
            }
            for (String alias : def.aliases()) {
                String existingCanonical = canonicalEdgeTypes.get(alias);
                if (existingCanonical != null && !existingCanonical.equals(canonical)) {
                    throw new VocabularyConflictException(
                        "'" + alias + "' is already mapped to canonical '" + existingCanonical + "'");
                }
                if (edgeTypeDefinitions.containsKey(alias) && !alias.equals(canonical)) {
                    throw new VocabularyConflictException(
                        "'" + alias + "' is already a canonical type and cannot be an alias of '" + canonical + "'");
                }
            }
            edgeTypeDefinitions.put(canonical, def);
            canonicalEdgeTypes.put(canonical, canonical);
            for (String alias : def.aliases()) {
                canonicalEdgeTypes.put(alias, canonical);
            }
        }
    }

    // --- Subgraph ---

    @Override
    public String createSubgraph(SubgraphInput input, String tenantId) {
        String id = UUID.randomUUID().toString();
        String now = ts(Instant.now());
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO mindmap_subgraph (subgraph_id, tenant_id, name, type, root_node_id, created_at) VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, tenantId);
            ps.setString(3, input.name());
            ps.setString(4, input.type().name());
            ps.setString(5, input.rootNodeId());
            ps.setString(6, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("createSubgraph() failed", e);
        }
        return id;
    }

    @Override
    public MindMapSubgraph getSubgraph(String subgraphId, String tenantId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM mindmap_subgraph WHERE subgraph_id = ? AND tenant_id = ?")) {
            ps.setString(1, subgraphId);
            ps.setString(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new MindMapSubgraph(
                    rs.getString("subgraph_id"),
                    rs.getString("name"),
                    SubgraphType.valueOf(rs.getString("type")),
                    rs.getString("root_node_id"),
                    rs.getString("tenant_id"),
                    Instant.parse(rs.getString("created_at")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("getSubgraph() failed", e);
        }
    }

    @Override
    public void updateSubgraph(String subgraphId, String rootNodeId, String tenantId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE mindmap_subgraph SET root_node_id = ? WHERE subgraph_id = ? AND tenant_id = ?")) {
            ps.setString(1, rootNodeId);
            ps.setString(2, subgraphId);
            ps.setString(3, tenantId);
            int updated = ps.executeUpdate();
            if (updated == 0) throw new IllegalArgumentException("Subgraph not found: " + subgraphId);
        } catch (SQLException e) {
            throw new IllegalStateException("updateSubgraph() failed", e);
        }
    }

    // --- Node ---

    @Override
    public String addNode(NodeInput input, String tenantId) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        double confidence = input.confidence() != null
            ? input.confidence()
            : input.confidenceOrigin().initialConfidence();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO mindmap_node (node_id, tenant_id, name, subgraph_id, confidence_origin, confidence, provenance, created_at, updated_at, confirmed_at, valid_from, valid_until, traits, refs, pleasure, arousal, dominance, properties) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            String nowTs = ts(now);
            ps.setString(1, id);
            ps.setString(2, tenantId);
            ps.setString(3, input.name());
            ps.setString(4, input.subgraphId());
            ps.setString(5, input.confidenceOrigin().name());
            ps.setDouble(6, confidence);
            ps.setString(7, input.provenance());
            ps.setString(8, nowTs);
            ps.setString(9, nowTs);
            ps.setString(10, nowTs);
            ps.setString(11, input.validFrom() != null ? ts(input.validFrom()) : null);
            ps.setString(12, input.validUntil() != null ? ts(input.validUntil()) : null);
            ps.setString(13, toJson(input.traits()));
            ps.setString(14, refsToJson(input.refs()));
            setNullableDouble(ps, 15, input.pleasure());
            setNullableDouble(ps, 16, input.arousal());
            setNullableDouble(ps, 17, input.dominance());
            ps.setString(18, mapToJson(input.properties()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("addNode() failed", e);
        }
        return id;
    }

    @Override
    public MindMapNode getNode(String nodeId, String tenantId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM mindmap_node WHERE node_id = ? AND tenant_id = ?")) {
            ps.setString(1, nodeId);
            ps.setString(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return toNode(rs);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("getNode() failed", e);
        }
    }

    @Override
    public void updateNode(String nodeId, NodeUpdate update, String tenantId) {
        try (Connection conn = dataSource.getConnection()) {
            PreparedStatement selectPs = conn.prepareStatement(
                "SELECT * FROM mindmap_node WHERE node_id = ? AND tenant_id = ?");
            selectPs.setString(1, nodeId);
            selectPs.setString(2, tenantId);
            ResultSet rs = selectPs.executeQuery();
            if (!rs.next()) throw new IllegalArgumentException("Node not found: " + nodeId);

            String name = update.name() != null ? update.name() : rs.getString("name");
            String coStr = update.confidenceOrigin() != null ? update.confidenceOrigin().name() : rs.getString("confidence_origin");
            double confidence = rs.getDouble("confidence");
            String confirmedAt = rs.getString("confirmed_at");

            if (update.confirmedAt() != null) {
                confirmedAt = ts(update.confirmedAt());
                confidence = update.confidence() != null ? update.confidence() : 1.0;
            } else if (update.confidence() != null) {
                confidence = update.confidence();
            }

            String validFrom = update.validFrom() != null ? ts(update.validFrom()) : rs.getString("valid_from");
            String validUntil = update.validUntil() != null ? ts(update.validUntil()) : rs.getString("valid_until");
            Double pleasure = update.pleasure() != null ? update.pleasure() : getNullableDouble(rs, "pleasure");
            Double arousal = update.arousal() != null ? update.arousal() : getNullableDouble(rs, "arousal");
            Double dominance = update.dominance() != null ? update.dominance() : getNullableDouble(rs, "dominance");

            Set<String> traits = new HashSet<>(fromJsonStringSet(rs.getString("traits")));
            traits.addAll(update.traitsToAdd());
            traits.removeAll(update.traitsToRemove());

            Set<NodeRef> refs = new HashSet<>(refsFromJson(rs.getString("refs")));
            refs.addAll(update.refsToAdd());
            refs.removeAll(update.refsToRemove());

            Map<String, String> properties = new HashMap<>(mapFromJson(rs.getString("properties")));
            update.propertiesToSet().forEach(properties::put);
            update.propertiesToRemove().forEach(properties::remove);

            rs.close();
            selectPs.close();

            PreparedStatement updatePs = conn.prepareStatement(
                "UPDATE mindmap_node SET name=?, confidence_origin=?, confidence=?, confirmed_at=?, valid_from=?, valid_until=?, pleasure=?, arousal=?, dominance=?, traits=?, refs=?, properties=?, updated_at=? WHERE node_id=? AND tenant_id=?");
            updatePs.setString(1, name);
            updatePs.setString(2, coStr);
            updatePs.setDouble(3, confidence);
            updatePs.setString(4, confirmedAt);
            updatePs.setString(5, validFrom);
            updatePs.setString(6, validUntil);
            setNullableDouble(updatePs, 7, pleasure);
            setNullableDouble(updatePs, 8, arousal);
            setNullableDouble(updatePs, 9, dominance);
            updatePs.setString(10, toJson(traits));
            updatePs.setString(11, refsToJson(refs));
            updatePs.setString(12, mapToJson(properties));
            updatePs.setString(13, ts(Instant.now()));
            updatePs.setString(14, nodeId);
            updatePs.setString(15, tenantId);
            updatePs.executeUpdate();
            updatePs.close();
        } catch (SQLException e) {
            throw new IllegalStateException("updateNode() failed", e);
        }
    }

    @Override
    public List<MindMapNode> nodesIn(String subgraphId, String tenantId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM mindmap_node WHERE tenant_id = ? AND subgraph_id = ? AND (superseded_at IS NULL OR reinstated_at IS NOT NULL)")) {
            ps.setString(1, tenantId);
            ps.setString(2, subgraphId);
            return collectNodes(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("nodesIn() failed", e);
        }
    }

    // --- Alias ---

    @Override
    public void addAlias(String nodeId, String alias, String tenantId) {
        try (Connection conn = dataSource.getConnection()) {
            PreparedStatement check = conn.prepareStatement(
                "SELECT 1 FROM mindmap_node WHERE node_id = ? AND tenant_id = ?");
            check.setString(1, nodeId);
            check.setString(2, tenantId);
            ResultSet rs = check.executeQuery();
            if (!rs.next()) throw new IllegalArgumentException("Node not found: " + nodeId);
            rs.close();
            check.close();

            PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO mindmap_alias (tenant_id, alias, node_id) VALUES (?,?,?)");
            ps.setString(1, tenantId);
            ps.setString(2, alias.toLowerCase());
            ps.setString(3, nodeId);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            throw new IllegalStateException("addAlias() failed", e);
        }
    }

    @Override
    public void removeAlias(String nodeId, String alias, String tenantId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM mindmap_alias WHERE tenant_id = ? AND alias = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, alias.toLowerCase());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("removeAlias() failed", e);
        }
    }

    @Override
    public MindMapNode resolveNode(String nameOrAlias, String subgraphId, String tenantId) {
        try (Connection conn = dataSource.getConnection()) {
            PreparedStatement aliasPs = conn.prepareStatement(
                    "SELECT node_id FROM mindmap_alias WHERE tenant_id = ? AND alias = ?");
            aliasPs.setString(1, tenantId);
            aliasPs.setString(2, nameOrAlias.toLowerCase());
            ResultSet aliasRs = aliasPs.executeQuery();
            if (aliasRs.next()) {
                String nodeId = aliasRs.getString("node_id");
                aliasRs.close();
                aliasPs.close();
                MindMapNode node = getNodeInternal(conn, nodeId, tenantId);
                if (node != null && (subgraphId == null || node.subgraphId().equals(subgraphId))) {
                    return node;
                }
            } else {
                aliasRs.close();
                aliasPs.close();
            }

            String sql = subgraphId != null
                         ? "SELECT * FROM mindmap_node WHERE tenant_id = ? AND name = ? COLLATE NOCASE AND subgraph_id = ? LIMIT 1"
                         : "SELECT * FROM mindmap_node WHERE tenant_id = ? AND name = ? COLLATE NOCASE LIMIT 1";
            PreparedStatement namePs = conn.prepareStatement(sql);
            namePs.setString(1, tenantId);
            namePs.setString(2, nameOrAlias);
            if (subgraphId != null) {namePs.setString(3, subgraphId);}
            ResultSet   nameRs = namePs.executeQuery();
            MindMapNode result = nameRs.next() ? toNode(nameRs) : null;
            nameRs.close();
            namePs.close();
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("resolveNode() failed", e);
        }
    }

    // --- Edge ---

    @Override
    public String addEdge(EdgeInput input, String tenantId) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String resolvedType = canonicalEdgeTypes.getOrDefault(input.edgeType(), input.edgeType());
        ValidationTier tier = edgeTypeDefinitions.containsKey(resolvedType)
            ? ValidationTier.REGISTERED : ValidationTier.UNVALIDATED;
        double confidence = input.confidence() != null
            ? input.confidence()
            : input.confidenceOrigin().initialConfidence();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO mindmap_edge (edge_id, tenant_id, source_node_id, target_node_id, edge_type, tier, confidence_origin, confidence, provenance, created_at, updated_at, valid_from, valid_until, pleasure, arousal, dominance, properties) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            String nowTs = ts(now);
            ps.setString(1, id);
            ps.setString(2, tenantId);
            ps.setString(3, input.sourceNodeId());
            ps.setString(4, input.targetNodeId());
            ps.setString(5, resolvedType);
            ps.setString(6, tier.name());
            ps.setString(7, input.confidenceOrigin().name());
            ps.setDouble(8, confidence);
            ps.setString(9, input.provenance());
            ps.setString(10, nowTs);
            ps.setString(11, nowTs);
            ps.setString(12, input.validFrom() != null ? ts(input.validFrom()) : null);
            ps.setString(13, input.validUntil() != null ? ts(input.validUntil()) : null);
            setNullableDouble(ps, 14, input.pleasure());
            setNullableDouble(ps, 15, input.arousal());
            setNullableDouble(ps, 16, input.dominance());
            ps.setString(17, mapToJson(input.properties()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("addEdge() failed", e);
        }
        return id;
    }

    @Override
    public MindMapEdge getEdge(String edgeId, String tenantId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM mindmap_edge WHERE edge_id = ? AND tenant_id = ?")) {
            ps.setString(1, edgeId);
            ps.setString(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return toEdge(rs);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("getEdge() failed", e);
        }
    }

    @Override
    public void removeEdge(String edgeId, String tenantId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM mindmap_edge WHERE edge_id = ? AND tenant_id = ?")) {
            ps.setString(1, edgeId);
            ps.setString(2, tenantId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("removeEdge() failed", e);
        }
    }

    // --- Traversal ---

    @Override
    public List<MindMapEdge> neighbors(String nodeId, String tenantId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM mindmap_edge WHERE tenant_id = ? AND (source_node_id = ? OR target_node_id = ?)")) {
            ps.setString(1, tenantId);
            ps.setString(2, nodeId);
            ps.setString(3, nodeId);
            return collectEdges(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("neighbors() failed", e);
        }
    }

    @Override
    public List<MindMapEdge> neighbors(String nodeId, String edgeType, String tenantId) {
        String resolved = canonicalEdgeTypes.getOrDefault(edgeType, edgeType);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM mindmap_edge WHERE tenant_id = ? AND (source_node_id = ? OR target_node_id = ?) AND edge_type = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, nodeId);
            ps.setString(3, nodeId);
            ps.setString(4, resolved);
            return collectEdges(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("neighbors(edgeType) failed", e);
        }
    }

    @Override
    public List<MindMapEdge> bridgeEdges(String subgraphId, String tenantId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT e.* FROM mindmap_edge e WHERE e.tenant_id = ? AND (" +
                 "(e.source_node_id IN (SELECT node_id FROM mindmap_node WHERE tenant_id = ? AND subgraph_id = ?) AND e.target_node_id NOT IN (SELECT node_id FROM mindmap_node WHERE tenant_id = ? AND subgraph_id = ?)) OR " +
                 "(e.target_node_id IN (SELECT node_id FROM mindmap_node WHERE tenant_id = ? AND subgraph_id = ?) AND e.source_node_id NOT IN (SELECT node_id FROM mindmap_node WHERE tenant_id = ? AND subgraph_id = ?)))")) {
            ps.setString(1, tenantId);
            ps.setString(2, tenantId);
            ps.setString(3, subgraphId);
            ps.setString(4, tenantId);
            ps.setString(5, subgraphId);
            ps.setString(6, tenantId);
            ps.setString(7, subgraphId);
            ps.setString(8, tenantId);
            ps.setString(9, subgraphId);
            return collectEdges(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("bridgeEdges() failed", e);
        }
    }

    // --- Search ---

    @Override
    public List<MindMapNode> search(MindMapQuery query) {
        var sql = new StringBuilder("SELECT n.* FROM mindmap_node n WHERE n.tenant_id = ?");
        var params = new ArrayList<Object>();
        params.add(query.tenantId());

        if (!query.includeSuperseded()) {
            sql.append(" AND (n.superseded_at IS NULL OR n.reinstated_at IS NOT NULL)");
        }

        if (query.subgraphId() != null) {
            sql.append(" AND n.subgraph_id = ?");
            params.add(query.subgraphId());
        }
        if (query.minConfidence() != null) {
            sql.append(" AND n.confidence >= ?");
            params.add(query.minConfidence());
        }
        if (query.confidenceOrigin() != null) {
            sql.append(" AND n.confidence_origin = ?");
            params.add(query.confidenceOrigin().name());
        }
        if (query.edgeType() != null) {
            String resolved = canonicalEdgeTypes.getOrDefault(query.edgeType(), query.edgeType());
            sql.append(" AND n.node_id IN (SELECT source_node_id FROM mindmap_edge WHERE tenant_id = ? AND edge_type = ? UNION SELECT target_node_id FROM mindmap_edge WHERE tenant_id = ? AND edge_type = ?)");
            params.add(query.tenantId());
            params.add(resolved);
            params.add(query.tenantId());
            params.add(resolved);
        }
        if (query.text() != null) {
            sql.append(" AND (n.name LIKE ? COLLATE NOCASE OR n.properties LIKE ? COLLATE NOCASE)");
            String pattern = "%" + query.text() + "%";
            params.add(pattern);
            params.add(pattern);
        }

        sql.append(" LIMIT ?");
        params.add(query.limit());

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object p = params.get(i);
                if (p instanceof String s) ps.setString(i + 1, s);
                else if (p instanceof Double d) ps.setDouble(i + 1, d);
                else if (p instanceof Integer n) ps.setInt(i + 1, n);
            }
            List<MindMapNode> results = collectNodes(ps);
            if (query.traits() != null && !query.traits().isEmpty()) {
                results = results.stream()
                    .filter(n -> n.traits().containsAll(query.traits()))
                    .toList();
            }
            return results;
        } catch (SQLException e) {
            throw new IllegalStateException("search() failed", e);
        }
    }

    // --- Merge ---

    @Override
    public MergeResult mergeNodes(String keepNodeId, String removeNodeId, String tenantId) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                MindMapNode keepNode = getNodeInternal(conn, keepNodeId, tenantId);
                MindMapNode removeNode = getNodeInternal(conn, removeNodeId, tenantId);
                if (keepNode == null) throw new IllegalArgumentException("Keep node not found: " + keepNodeId);
                if (removeNode == null) throw new IllegalArgumentException("Remove node not found: " + removeNodeId);

                // Union aliases
                int aliasesMerged = 0;
                try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE mindmap_alias SET node_id = ? WHERE tenant_id = ? AND node_id = ?")) {
                    ps.setString(1, keepNodeId);
                    ps.setString(2, tenantId);
                    ps.setString(3, removeNodeId);
                    aliasesMerged = ps.executeUpdate();
                }

                // Union refs
                Set<NodeRef> keepRefs = new HashSet<>(keepNode.refs());
                keepRefs.addAll(removeNode.refs());

                // Union traits
                Set<String> keepTraits = new HashSet<>(keepNode.traits());
                Set<String> mergedTraits = new HashSet<>(removeNode.traits());
                mergedTraits.removeAll(keepTraits);
                keepTraits.addAll(removeNode.traits());

                // Merge properties
                Map<String, String> keepProps = new HashMap<>(keepNode.properties());
                List<MergeConflict> propertyConflicts = new ArrayList<>();
                for (Map.Entry<String, String> entry : removeNode.properties().entrySet()) {
                    String key = entry.getKey();
                    if (keepProps.containsKey(key)) {
                        String keptVal = keepProps.get(key);
                        String discardedVal = entry.getValue();
                        if (!keptVal.equals(discardedVal)) {
                            if (removeNode.updatedAt().isAfter(keepNode.updatedAt())) {
                                keepProps.put(key, discardedVal);
                                propertyConflicts.add(new MergeConflict(key, discardedVal, keptVal));
                            } else {
                                propertyConflicts.add(new MergeConflict(key, keptVal, discardedVal));
                            }
                        }
                    } else {
                        keepProps.put(key, entry.getValue());
                    }
                }

                // Repoint edges
                int edgesRepointed = 0;
                try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE mindmap_edge SET source_node_id = ? WHERE tenant_id = ? AND source_node_id = ?")) {
                    ps.setString(1, keepNodeId);
                    ps.setString(2, tenantId);
                    ps.setString(3, removeNodeId);
                    edgesRepointed += ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE mindmap_edge SET target_node_id = ? WHERE tenant_id = ? AND target_node_id = ?")) {
                    ps.setString(1, keepNodeId);
                    ps.setString(2, tenantId);
                    ps.setString(3, removeNodeId);
                    edgesRepointed += ps.executeUpdate();
                }

                // Remove self-loops
                try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM mindmap_edge WHERE tenant_id = ? AND source_node_id = ? AND target_node_id = ?")) {
                    ps.setString(1, tenantId);
                    ps.setString(2, keepNodeId);
                    ps.setString(3, keepNodeId);
                    ps.executeUpdate();
                }

                // Deduplicate edges
                int duplicateEdgesRemoved = 0;
                try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT edge_id, source_node_id, target_node_id, edge_type, updated_at FROM mindmap_edge WHERE tenant_id = ? AND (source_node_id = ? OR target_node_id = ?) ORDER BY updated_at DESC")) {
                    ps.setString(1, tenantId);
                    ps.setString(2, keepNodeId);
                    ps.setString(3, keepNodeId);
                    ResultSet rs = ps.executeQuery();
                    Map<String, String> seen = new HashMap<>();
                    List<String> toDelete = new ArrayList<>();
                    while (rs.next()) {
                        String sig = rs.getString("source_node_id") + "|" + rs.getString("target_node_id") + "|" + rs.getString("edge_type");
                        if (seen.containsKey(sig)) {
                            toDelete.add(rs.getString("edge_id"));
                            duplicateEdgesRemoved++;
                        } else {
                            seen.put(sig, rs.getString("edge_id"));
                        }
                    }
                    rs.close();
                    for (String eid : toDelete) {
                        try (PreparedStatement del = conn.prepareStatement("DELETE FROM mindmap_edge WHERE edge_id = ?")) {
                            del.setString(1, eid);
                            del.executeUpdate();
                        }
                    }
                }

                // Update keep node
                try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE mindmap_node SET traits=?, refs=?, properties=?, updated_at=? WHERE node_id=? AND tenant_id=?")) {
                    ps.setString(1, toJson(keepTraits));
                    ps.setString(2, refsToJson(keepRefs));
                    ps.setString(3, mapToJson(keepProps));
                    ps.setString(4, ts(Instant.now()));
                    ps.setString(5, keepNodeId);
                    ps.setString(6, tenantId);
                    ps.executeUpdate();
                }

                // Delete remove node
                try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM mindmap_node WHERE node_id = ? AND tenant_id = ?")) {
                    ps.setString(1, removeNodeId);
                    ps.setString(2, tenantId);
                    ps.executeUpdate();
                }

                conn.commit();
                return new MergeResult(keepNodeId, edgesRepointed, aliasesMerged,
                    duplicateEdgesRemoved, mergedTraits, propertyConflicts);
            } catch (Exception e) {
                conn.rollback();
                throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("mergeNodes() failed", e);
        }
    }

    // --- Supersession ---

    @Override
    public void supersede(String targetId, String supersedingId, String reason, String tenantId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE mindmap_node SET superseded_at=?, superseding_id=?, supersession_reason=?, reinstated_at=NULL WHERE node_id=? AND tenant_id=?")) {
            ps.setString(1, ts(Instant.now()));
            ps.setString(2, supersedingId);
            ps.setString(3, reason);
            ps.setString(4, targetId);
            ps.setString(5, tenantId);
            int updated = ps.executeUpdate();
            if (updated == 0) throw new IllegalArgumentException("Node not found: " + targetId);
        } catch (SQLException e) {
            throw new IllegalStateException("supersede() failed", e);
        }
    }

    @Override
    public void reinstate(String targetId, String tenantId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE mindmap_node SET reinstated_at=? WHERE node_id=? AND tenant_id=?")) {
            ps.setString(1, ts(Instant.now()));
            ps.setString(2, targetId);
            ps.setString(3, tenantId);
            int updated = ps.executeUpdate();
            if (updated == 0) throw new IllegalArgumentException("Node not found: " + targetId);
        } catch (SQLException e) {
            throw new IllegalStateException("reinstate() failed", e);
        }
    }

    @Override
    public SupersessionStatus getSupersessionStatus(String targetId, String tenantId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT superseded_at, superseding_id, supersession_reason, reinstated_at FROM mindmap_node WHERE node_id=? AND tenant_id=?")) {
            ps.setString(1, targetId);
            ps.setString(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Node not found: " + targetId);
                String supersededAt = rs.getString("superseded_at");
                if (supersededAt == null) return SupersessionStatus.NOT_SUPERSEDED;
                String reinstatedAt = rs.getString("reinstated_at");
                return new SupersessionStatus(targetId,
                    reinstatedAt == null,
                    Instant.parse(supersededAt),
                    rs.getString("superseding_id"),
                    rs.getString("supersession_reason"),
                    reinstatedAt != null ? Instant.parse(reinstatedAt) : null);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("getSupersessionStatus() failed", e);
        }
    }

    // --- Erasure ---

    @Override
    public int eraseNode(String nodeId, String tenantId) {
        try (Connection conn = dataSource.getConnection()) {
            return eraseNodeInternal(conn, nodeId, tenantId);
        } catch (SQLException e) {
            throw new IllegalStateException("eraseNode() failed", e);
        }
    }

    private int eraseNodeInternal(Connection conn, String nodeId, String tenantId) throws SQLException {
        int count = 0;

        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM mindmap_node WHERE node_id = ? AND tenant_id = ?")) {
            ps.setString(1, nodeId);
            ps.setString(2, tenantId);
            int deleted = ps.executeUpdate();
            if (deleted == 0) {return 0;}
            count += deleted;
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM mindmap_edge WHERE tenant_id = ? AND (source_node_id = ? OR target_node_id = ?)")) {
            ps.setString(1, tenantId);
            ps.setString(2, nodeId);
            ps.setString(3, nodeId);
            count += ps.executeUpdate();
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM mindmap_alias WHERE tenant_id = ? AND node_id = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, nodeId);
            count += ps.executeUpdate();
        }

        return count;
    }


    @Override
    public int eraseSubgraph(String subgraphId, String tenantId) {
        try (Connection conn = dataSource.getConnection()) {
            List<String> nodeIds = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT node_id FROM mindmap_node WHERE tenant_id = ? AND subgraph_id = ?")) {
                ps.setString(1, tenantId);
                ps.setString(2, subgraphId);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {nodeIds.add(rs.getString("node_id"));}
            }

            int count = 0;
            for (String nodeId : nodeIds) {
                count += eraseNodeInternal(conn, nodeId, tenantId);
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM mindmap_subgraph WHERE subgraph_id = ? AND tenant_id = ?")) {
                ps.setString(1, subgraphId);
                ps.setString(2, tenantId);
                int deleted = ps.executeUpdate();
                if (deleted == 0 && nodeIds.isEmpty()) {return 0;}
                count += deleted;
            }
            return count;
        } catch (SQLException e) {
            throw new IllegalStateException("eraseSubgraph() failed", e);
        }
    }

    @Override
    public int eraseEntity(String entityName, String tenantId) {
        try (Connection conn = dataSource.getConnection()) {
            Set<String> nodeIds = new LinkedHashSet<>();

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT node_id FROM mindmap_node WHERE tenant_id = ? AND name = ? COLLATE NOCASE")) {
                ps.setString(1, tenantId);
                ps.setString(2, entityName);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {nodeIds.add(rs.getString("node_id"));}
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT node_id FROM mindmap_alias WHERE tenant_id = ? AND alias = ?")) {
                ps.setString(1, tenantId);
                ps.setString(2, entityName.toLowerCase());
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {nodeIds.add(rs.getString("node_id"));}
            }

            int count = 0;
            for (String nodeId : nodeIds) {
                count += eraseNodeInternal(conn, nodeId, tenantId);
            }
            return count;
        } catch (SQLException e) {
            throw new IllegalStateException("eraseEntity() failed", e);
        }
    }

    @Override
    public int eraseEntityAcrossTenants(String entityName, Set<String> tenantIds) {
        int count = 0;
        for (String tid : tenantIds) {
            count += eraseEntity(entityName, tid);
        }
        return count;
    }

    // --- Internal helpers ---

    private MindMapNode getNodeInternal(Connection conn, String nodeId, String tenantId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT * FROM mindmap_node WHERE node_id = ? AND tenant_id = ?")) {
            ps.setString(1, nodeId);
            ps.setString(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return toNode(rs);
            }
        }
    }

    private String ts(Instant instant) {
        return instant.toString();
    }

    private void setNullableDouble(PreparedStatement ps, int idx, Double value) throws SQLException {
        if (value != null) ps.setDouble(idx, value);
        else ps.setNull(idx, java.sql.Types.REAL);
    }

    private Double getNullableDouble(ResultSet rs, String column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }

    private List<MindMapNode> collectNodes(PreparedStatement ps) throws SQLException {
        List<MindMapNode> results = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) results.add(toNode(rs));
        }
        return results;
    }

    private List<MindMapEdge> collectEdges(PreparedStatement ps) throws SQLException {
        List<MindMapEdge> results = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) results.add(toEdge(rs));
        }
        return results;
    }

    private MindMapNode toNode(ResultSet rs) throws SQLException {
        return new SqliteNode(
            rs.getString("node_id"),
            rs.getString("name"),
            rs.getString("subgraph_id"),
            ConfidenceOrigin.valueOf(rs.getString("confidence_origin")),
            rs.getDouble("confidence"),
            rs.getString("provenance"),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at")),
            Instant.parse(rs.getString("confirmed_at")),
            rs.getString("valid_from") != null ? Instant.parse(rs.getString("valid_from")) : null,
            rs.getString("valid_until") != null ? Instant.parse(rs.getString("valid_until")) : null,
            fromJsonStringSet(rs.getString("traits")),
            refsFromJson(rs.getString("refs")),
            getNullableDouble(rs, "pleasure"),
            getNullableDouble(rs, "arousal"),
            getNullableDouble(rs, "dominance"),
            mapFromJson(rs.getString("properties")));
    }

    private MindMapEdge toEdge(ResultSet rs) throws SQLException {
        return new SqliteEdge(
            rs.getString("edge_id"),
            rs.getString("source_node_id"),
            rs.getString("target_node_id"),
            rs.getString("edge_type"),
            ValidationTier.valueOf(rs.getString("tier")),
            ConfidenceOrigin.valueOf(rs.getString("confidence_origin")),
            rs.getDouble("confidence"),
            rs.getString("provenance"),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at")),
            rs.getString("valid_from") != null ? Instant.parse(rs.getString("valid_from")) : null,
            rs.getString("valid_until") != null ? Instant.parse(rs.getString("valid_until")) : null,
            getNullableDouble(rs, "pleasure"),
            getNullableDouble(rs, "arousal"),
            getNullableDouble(rs, "dominance"),
            mapFromJson(rs.getString("properties")));
    }

    // --- JSON helpers ---

    private String toJson(Set<String> set) {
        try { return objectMapper.writeValueAsString(set != null ? set : Set.of()); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private Set<String> fromJsonStringSet(String json) {
        try { return new HashSet<>(objectMapper.readValue(json, new TypeReference<Set<String>>() {})); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private String refsToJson(Set<NodeRef> refs) {
        try { return objectMapper.writeValueAsString(refs != null ? refs : Set.of()); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private Set<NodeRef> refsFromJson(String json) {
        try { return new HashSet<>(objectMapper.readValue(json, new TypeReference<Set<NodeRef>>() {})); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private String mapToJson(Map<String, String> map) {
        try { return objectMapper.writeValueAsString(map != null ? map : Map.of()); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private Map<String, String> mapFromJson(String json) {
        try { return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {}); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    // --- Value types ---

    private record SqliteNode(
        String id, String name, String subgraphId,
        ConfidenceOrigin confidenceOrigin, double confidence, String provenance,
        Instant createdAt, Instant updatedAt, Instant confirmedAt,
        Instant validFrom, Instant validUntil,
        Set<String> traits, Set<NodeRef> refs,
        Double pleasure, Double arousal, Double dominance,
        Map<String, String> props
    ) implements MindMapNode {
        @Override public Set<String> traits() { return Set.copyOf(traits); }
        @Override public Set<NodeRef> refs() { return Set.copyOf(refs); }
        @Override public Optional<String> property(String key) { return Optional.ofNullable(props.get(key)); }
        @Override public Map<String, String> properties() { return Map.copyOf(props); }
    }

    private record SqliteEdge(
        String id, String sourceNodeId, String targetNodeId,
        String edgeType, ValidationTier tier,
        ConfidenceOrigin confidenceOrigin, double confidence, String provenance,
        Instant createdAt, Instant updatedAt,
        Instant validFrom, Instant validUntil,
        Double pleasure, Double arousal, Double dominance,
        Map<String, String> props
    ) implements MindMapEdge {
        @Override public Optional<String> property(String key) { return Optional.ofNullable(props.get(key)); }
        @Override public Map<String, String> properties() { return Map.copyOf(props); }
    }
}
