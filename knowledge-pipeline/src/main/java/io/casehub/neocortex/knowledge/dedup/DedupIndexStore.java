package io.casehub.neocortex.knowledge.dedup;

import com.zaxxer.hikari.HikariDataSource;
import io.casehub.neocortex.sqlite.SqliteDataSourceFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

@ApplicationScoped
public class DedupIndexStore {

    private final HikariDataSource ds;

    @Inject
    public DedupIndexStore(HikariDataSource ds) {
        this.ds = ds;
    }

    public DedupIndexStore(String dbPath) {
        this.ds = SqliteDataSourceFactory.create(dbPath, 3, 5000);
        SqliteDataSourceFactory.migrate(ds, "classpath:db/knowledge-pipeline");
    }

    public record DedupEntry(String cacheEntityId, String mindMapNodeId,
                              Instant firstSeen, Instant lastSeen) {}

    public Optional<DedupEntry> lookup(String source, String externalId) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT cache_entity_id, mindmap_node_id, first_seen, last_seen "
                 + "FROM dedup_index WHERE source = ? AND external_id = ?")) {
            ps.setString(1, source);
            ps.setString(2, externalId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            return Optional.of(new DedupEntry(
                rs.getString("cache_entity_id"),
                rs.getString("mindmap_node_id"),
                Instant.parse(rs.getString("first_seen")),
                Instant.parse(rs.getString("last_seen"))));
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void upsert(String source, String externalId, String cacheEntityId) {
        String now = Instant.now().toString();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO dedup_index (source, external_id, cache_entity_id, first_seen, last_seen) "
                 + "VALUES (?, ?, ?, ?, ?) "
                 + "ON CONFLICT(source, external_id) DO UPDATE SET "
                 + "cache_entity_id = excluded.cache_entity_id, last_seen = excluded.last_seen")) {
            ps.setString(1, source);
            ps.setString(2, externalId);
            ps.setString(3, cacheEntityId);
            ps.setString(4, now);
            ps.setString(5, now);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void setMindMapNodeId(String source, String externalId, String nodeId) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE dedup_index SET mindmap_node_id = ? "
                 + "WHERE source = ? AND external_id = ?")) {
            ps.setString(1, nodeId);
            ps.setString(2, source);
            ps.setString(3, externalId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void close() { ds.close(); }
}
