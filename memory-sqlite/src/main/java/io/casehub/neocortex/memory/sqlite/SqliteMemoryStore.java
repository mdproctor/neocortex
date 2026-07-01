package io.casehub.neocortex.memory.sqlite;

import io.casehub.neocortex.memory.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.micrometer.core.annotation.Timed;
import io.quarkus.arc.Arc;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;
import org.sqlite.SQLiteConfig;

import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Alternative
@Priority(1)
@ApplicationScoped
public class SqliteMemoryStore implements CaseMemoryStore {

    @Override
    public Set<MemoryCapability> capabilities() {
        return Set.of(
            MemoryCapability.CHRONOLOGICAL_ORDER,
            MemoryCapability.DOMAIN_SCOPED,
            MemoryCapability.CASE_SCOPED,
            MemoryCapability.SINCE_FILTER,
            MemoryCapability.BATCH_STORE,
            MemoryCapability.FULL_TEXT_SEARCH,
            MemoryCapability.ERASE_BY_ID,
            MemoryCapability.ERASE_ENTITY,
            MemoryCapability.ERASE_DOMAIN_CASE,
            MemoryCapability.CROSS_TENANT_ERASE
        );
    }

    @ConfigProperty(name = "casehub.memory.sqlite.path")
    String path;

    @ConfigProperty(name = "casehub.memory.sqlite.pool.max-size", defaultValue = "5")
    int maxPoolSize;

    @ConfigProperty(name = "casehub.memory.sqlite.busy-timeout-ms", defaultValue = "5000")
    int busyTimeoutMs;

    @ConfigProperty(name = "casehub.memory.sqlite.fts.enabled", defaultValue = "true")
    boolean ftsEnabled;

    @Inject CurrentPrincipal principal;
    @Inject ObjectMapper objectMapper;

    private HikariDataSource dataSource;

    private boolean requestContextActive() {
        var c = Arc.container();
        return c == null || c.requestContext().isActive();
    }

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

        // Use SQLiteDataSource(SQLiteConfig) constructor so pragma config is type-safe.
        // Wrap in HikariCP using setDataSource() — avoids PropertyElf string-coercion problems.
        org.sqlite.SQLiteDataSource sqLiteDataSource = new org.sqlite.SQLiteDataSource(sqLiteConfig);
        sqLiteDataSource.setUrl("jdbc:sqlite:" + path);

        HikariConfig hikari = new HikariConfig();
        hikari.setDataSource(sqLiteDataSource);
        hikari.setMaximumPoolSize(effectivePoolSize);
        hikari.setMinimumIdle(1);

        dataSource = new HikariDataSource(hikari);

        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/memory-sqlite/migration")
            .load()
            .migrate();
    }

    @PreDestroy
    void shutdown() {
        if (dataSource != null) dataSource.close();
    }

    @Timed(value = "casehub.memory.sqlite", histogram = true, extraTags = {"operation", "store"})
    @Override
    public String store(MemoryInput input) {
        MemoryPermissions.assertTenant(input.tenantId(), principal, requestContextActive());
        String memoryId = UUID.randomUUID().toString();
        String createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
        String sql = "INSERT INTO memory_entry (memory_id, tenant_id, entity_id, domain, case_id, text, attributes, created_at) VALUES (?,?,?,?,?,?,?,?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, memoryId);
            ps.setString(2, input.tenantId());
            ps.setString(3, input.entityId());
            ps.setString(4, input.domain().name());
            ps.setString(5, input.caseId());
            ps.setString(6, input.text());
            ps.setString(7, toJson(input.attributes()));
            ps.setString(8, createdAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("store() failed", e);
        }
        return memoryId;
    }

    @Timed(value = "casehub.memory.sqlite", histogram = true, extraTags = {"operation", "storeAll"})
    @Override
    public StoreAllResult storeAll(List<MemoryInput> inputs) {
        if (inputs.isEmpty()) return StoreAllResult.empty();
        inputs.forEach(i -> MemoryPermissions.assertTenant(i.tenantId(), principal, requestContextActive()));
        String sql = "INSERT INTO memory_entry (memory_id, tenant_id, entity_id, domain, case_id, text, attributes, created_at) VALUES (?,?,?,?,?,?,?,?)";
        List<String> ids = new ArrayList<>(inputs.size());
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (MemoryInput input : inputs) {
                    // guard each item — detects mixed-tenant batches where item 0 passes but a later item has a different tenantId
                    MemoryPermissions.assertTenant(input.tenantId(), principal, requestContextActive());
                    String memoryId = UUID.randomUUID().toString();
                    String createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
                    ps.setString(1, memoryId);
                    ps.setString(2, input.tenantId());
                    ps.setString(3, input.entityId());
                    ps.setString(4, input.domain().name());
                    ps.setString(5, input.caseId());
                    ps.setString(6, input.text());
                    ps.setString(7, toJson(input.attributes()));
                    ps.setString(8, createdAt);
                    ps.executeUpdate();
                    ids.add(memoryId);
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("storeAll() failed", e);
        }
        return new StoreAllResult(ids, List.of());
    }

    @Timed(value = "casehub.memory.sqlite", histogram = true, extraTags = {"operation", "query"})
    @Override
    public List<Memory> query(MemoryQuery query) {
        MemoryPermissions.assertTenant(query.tenantId(), principal, requestContextActive());
        if (ftsEnabled && query.order() == MemoryOrder.RELEVANCE && query.question() != null) {
            return queryFts(query);
        }
        return queryChronological(query);
    }

    @Timed(value = "casehub.memory.sqlite", histogram = true, extraTags = {"operation", "erase"})
    @Override
    public int erase(EraseRequest request) {
        MemoryPermissions.assertTenant(request.tenantId(), principal, requestContextActive());
        final StringBuilder sql = new StringBuilder(
            "DELETE FROM memory_entry WHERE tenant_id = ? AND entity_id = ? AND domain = ?");
        if (request.caseId() != null) sql.append(" AND case_id = ?");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setString(idx++, request.tenantId());
            ps.setString(idx++, request.entityId());
            ps.setString(idx++, request.domain().name());
            if (request.caseId() != null) ps.setString(idx, request.caseId());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("erase() failed", e);
        }
    }

    @Timed(value = "casehub.memory.sqlite", histogram = true, extraTags = {"operation", "eraseById"})
    @Override
    public void eraseById(String memoryId, String entityId, String tenantId) {
        MemoryPermissions.assertTenant(tenantId, principal, requestContextActive());
        // entity_id in WHERE: mismatch → 0 rows deleted, silent no-op.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM memory_entry WHERE memory_id = ? AND entity_id = ? AND tenant_id = ?")) {
            ps.setString(1, memoryId);
            ps.setString(2, entityId);
            ps.setString(3, tenantId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("eraseById() failed", e);
        }
    }

    @Timed(value = "casehub.memory.sqlite", histogram = true, extraTags = {"operation", "eraseEntity"})
    @Override
    public int eraseEntity(String entityId, String tenantId) {
        MemoryPermissions.assertTenant(tenantId, principal, requestContextActive());
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM memory_entry WHERE tenant_id = ? AND entity_id = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, entityId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("eraseEntity() failed", e);
        }
    }

    private static final int SQLITE_IN_CHUNK = 500;

    @Timed(value = "casehub.memory.sqlite", histogram = true, extraTags = {"operation", "eraseEntityAcrossTenants"})
    @Override
    public int eraseEntityAcrossTenants(String entityId, Set<String> tenantIds) {
        MemoryPermissions.assertCrossTenantAdmin(principal);
        if (tenantIds.isEmpty()) return 0;
        // ArrayList needed for subList() chunking
        var tenantList = new ArrayList<>(tenantIds);
        int total = 0;
        for (int offset = 0; offset < tenantList.size(); offset += SQLITE_IN_CHUNK) {
            var chunk = tenantList.subList(offset, Math.min(offset + SQLITE_IN_CHUNK, tenantList.size()));
            total += deleteChunk(entityId, chunk);
        }
        return total;
    }

    private int deleteChunk(String entityId, List<String> tenantChunk) {
        String placeholders = tenantChunk.stream().map(t -> "?").collect(Collectors.joining(", "));
        String sql = "DELETE FROM memory_entry WHERE entity_id = ? AND tenant_id IN (" + placeholders + ")";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entityId);
            int idx = 2;
            for (String t : tenantChunk) ps.setString(idx++, t);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("eraseEntityAcrossTenants() failed", e);
        }
    }

    // --- private helpers ---

    private List<Memory> queryChronological(MemoryQuery query) {
        StringBuilder sql = new StringBuilder(
            "SELECT * FROM memory_entry WHERE tenant_id = ? AND entity_id IN (")
            .append(placeholders(query.entityIds().size()))
            .append(") AND domain = ?");
        if (query.caseId() != null) sql.append(" AND case_id = ?");
        if (query.since()  != null) sql.append(" AND created_at >= ?");
        sql.append(" ORDER BY created_at DESC, rowid DESC LIMIT ?");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setString(idx++, query.tenantId());
            for (String entityId : query.entityIds()) ps.setString(idx++, entityId);
            ps.setString(idx++, query.domain().name());
            if (query.caseId() != null) ps.setString(idx++, query.caseId());
            if (query.since()  != null) ps.setString(idx++, query.since().truncatedTo(ChronoUnit.MILLIS).toString());
            ps.setInt(idx, query.limit());

            List<Memory> results = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(toMemory(rs));
            }
            return results;
        } catch (SQLException e) {
            throw new IllegalStateException("query() failed", e);
        }
    }

    private static final String FTS_OPERATOR_CHARS = "[\"*^:()+\\-]";

    private String sanitiseForFts(String question) {
        return question.replaceAll(FTS_OPERATOR_CHARS, " ").trim().replaceAll("\\s+", " ");
    }

    private List<Memory> queryFts(MemoryQuery query) {
        String sanitised = sanitiseForFts(query.question());
        if (sanitised.isBlank()) {
            return queryChronological(query);
        }

        StringBuilder sql = new StringBuilder(
            "SELECT m.* FROM memory_entry m JOIN memory_fts ON memory_fts.rowid = m.rowid WHERE m.tenant_id = ? AND m.entity_id IN (")
            .append(placeholders(query.entityIds().size()))
            .append(") AND m.domain = ? AND memory_fts MATCH ?");
        if (query.caseId() != null) sql.append(" AND m.case_id = ?");
        if (query.since()  != null) sql.append(" AND m.created_at >= ?");
        sql.append(" ORDER BY rank LIMIT ?");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setString(idx++, query.tenantId());
            for (String entityId : query.entityIds()) ps.setString(idx++, entityId);
            ps.setString(idx++, query.domain().name());
            ps.setString(idx++, sanitised);
            if (query.caseId() != null) ps.setString(idx++, query.caseId());
            if (query.since()  != null) ps.setString(idx++, query.since().truncatedTo(ChronoUnit.MILLIS).toString());
            ps.setInt(idx, query.limit());

            List<Memory> results = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(toMemory(rs));
            }
            return results;
        } catch (SQLException e) {
            throw new IllegalStateException("queryFts() failed", e);
        }
    }

    private String toJson(Map<String, String> attrs) {
        try {
            return objectMapper.writeValueAsString(attrs);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize attributes", e);
        }
    }

    private Map<String, String> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize attributes: " + json, e);
        }
    }

    private Memory toMemory(ResultSet rs) throws SQLException {
        return new Memory(
            rs.getString("memory_id"),
            rs.getString("entity_id"),
            new MemoryDomain(rs.getString("domain")),
            rs.getString("tenant_id"),
            rs.getString("case_id"),
            rs.getString("text"),
            fromJson(rs.getString("attributes")),
            Instant.parse(rs.getString("created_at"))
        );
    }

    private String placeholders(int count) {
        return ",?".repeat(count).substring(1);
    }
}
