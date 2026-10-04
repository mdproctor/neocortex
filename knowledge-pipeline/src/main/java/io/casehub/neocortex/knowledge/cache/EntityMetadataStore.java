package io.casehub.neocortex.knowledge.cache;

import com.zaxxer.hikari.HikariDataSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class EntityMetadataStore {

    private final HikariDataSource ds;

    @Inject
    public EntityMetadataStore(HikariDataSource ds) {
        this.ds = ds;
    }

    public record EntityMetadata(
        String entityId, String name, String category, String source,
        String externalId, String propertiesJson, Instant fetchedAt,
        Instant detailFetchedAt, boolean hasDetail
    ) {}

    public void save(EntityMetadata metadata) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT OR REPLACE INTO entity_metadata "
                 + "(entity_id, name, category, source, external_id, properties, "
                 + "fetched_at, detail_fetched_at, has_detail) "
                 + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, metadata.entityId());
            ps.setString(2, metadata.name());
            ps.setString(3, metadata.category());
            ps.setString(4, metadata.source());
            ps.setString(5, metadata.externalId());
            ps.setString(6, metadata.propertiesJson());
            ps.setString(7, metadata.fetchedAt().toString());
            ps.setString(8, metadata.detailFetchedAt() != null
                ? metadata.detailFetchedAt().toString() : null);
            ps.setInt(9, metadata.hasDetail() ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public Optional<EntityMetadata> get(String entityId) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT * FROM entity_metadata WHERE entity_id = ?")) {
            ps.setString(1, entityId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            return Optional.of(mapRow(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public Map<String, EntityMetadata> getBatch(List<String> entityIds) {
        if (entityIds.isEmpty()) return Map.of();
        String placeholders = entityIds.stream().map(id -> "?").collect(Collectors.joining(","));
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT * FROM entity_metadata WHERE entity_id IN (" + placeholders + ")")) {
            for (int i = 0; i < entityIds.size(); i++) {
                ps.setString(i + 1, entityIds.get(i));
            }
            ResultSet rs = ps.executeQuery();
            Map<String, EntityMetadata> result = new LinkedHashMap<>();
            while (rs.next()) {
                EntityMetadata m = mapRow(rs);
                result.put(m.entityId(), m);
            }
            return result;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void delete(String entityId) {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM entity_metadata WHERE entity_id = ?")) {
                ps.setString(1, entityId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM entity_sessions WHERE entity_id = ?")) {
                ps.setString(1, entityId);
                ps.executeUpdate();
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void addSession(String entityId, String sessionId) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT OR IGNORE INTO entity_sessions (entity_id, session_id) VALUES (?, ?)")) {
            ps.setString(1, entityId);
            ps.setString(2, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public Set<String> sessionsFor(String entityId) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT session_id FROM entity_sessions WHERE entity_id = ?")) {
            ps.setString(1, entityId);
            ResultSet rs = ps.executeQuery();
            var sessions = new java.util.LinkedHashSet<String>();
            while (rs.next()) sessions.add(rs.getString("session_id"));
            return sessions;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public Set<String> entitiesForSession(String sessionId) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT entity_id FROM entity_sessions WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            ResultSet rs       = ps.executeQuery();
            var       entities = new LinkedHashSet<String>();
            while (rs.next()) {entities.add(rs.getString("entity_id"));}
            return entities;
        } catch (SQLException e) {throw new RuntimeException(e);}
    }


    private EntityMetadata mapRow(ResultSet rs) throws SQLException {
        String detailFetched = rs.getString("detail_fetched_at");
        return new EntityMetadata(
            rs.getString("entity_id"),
            rs.getString("name"),
            rs.getString("category"),
            rs.getString("source"),
            rs.getString("external_id"),
            rs.getString("properties"),
            Instant.parse(rs.getString("fetched_at")),
            detailFetched != null ? Instant.parse(detailFetched) : null,
            rs.getInt("has_detail") == 1
        );
    }
}
