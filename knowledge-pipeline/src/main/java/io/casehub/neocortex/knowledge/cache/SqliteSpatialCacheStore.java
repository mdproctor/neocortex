package io.casehub.neocortex.knowledge.cache;

import com.zaxxer.hikari.HikariDataSource;
import io.casehub.connectors.location.model.Coordinates;
import io.casehub.neocortex.knowledge.BoundingBox;
import io.casehub.neocortex.knowledge.CacheFilter;
import io.casehub.neocortex.knowledge.CachedEntity;
import io.casehub.neocortex.knowledge.SpatialCacheStore;
import io.casehub.neocortex.knowledge.resolution.Haversine;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class SqliteSpatialCacheStore implements SpatialCacheStore {

    private final HikariDataSource ds;

    @Inject
    public SqliteSpatialCacheStore(HikariDataSource ds) {
        this.ds = ds;
    }

    @Override
    public void set(CachedEntity entity, String tenantId) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                upsertMetadata(c, entity, tenantId);
                long rowid = getRowid(c, entity.id());
                upsertSpatial(c, rowid, entity);
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CachedEntity get(String entityId, String tenantId) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT * FROM entity_metadata WHERE entity_id = ? AND tenant_id = ?")) {
            ps.setString(1, entityId);
            ps.setString(2, tenantId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;
            return mapRow(rs);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<CachedEntity> nearby(Coordinates center, int radiusMeters,
                                      CacheFilter filters, String tenantId) {
        double latDelta = radiusMeters / 111_320.0;
        double lngDelta = radiusMeters / (111_320.0 * Math.cos(Math.toRadians(center.lat())));

        double minLat = center.lat() - latDelta;
        double maxLat = center.lat() + latDelta;
        double minLng = center.lng() - lngDelta;
        double maxLng = center.lng() + lngDelta;

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT em.* FROM entity_spatial es "
                 + "JOIN entity_metadata em ON em.rowid = es.id "
                 + "WHERE es.min_lat >= ? AND es.max_lat <= ? "
                 + "AND es.min_lng >= ? AND es.max_lng <= ? "
                 + "AND em.tenant_id = ?")) {
            ps.setDouble(1, minLat);
            ps.setDouble(2, maxLat);
            ps.setDouble(3, minLng);
            ps.setDouble(4, maxLng);
            ps.setString(5, tenantId);
            ResultSet rs = ps.executeQuery();

            List<CachedEntity> results = new ArrayList<>();
            while (rs.next()) {
                CachedEntity entity = mapRow(rs);
                if (entity.coordinates() != null
                        && Haversine.distanceMeters(center, entity.coordinates()) <= radiusMeters
                        && matchesFilter(entity, filters)) {
                    results.add(entity);
                }
            }
            return results;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<CachedEntity> within(BoundingBox box, CacheFilter filters, String tenantId) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT em.* FROM entity_spatial es "
                 + "JOIN entity_metadata em ON em.rowid = es.id "
                 + "WHERE es.min_lat >= ? AND es.max_lat <= ? "
                 + "AND es.min_lng >= ? AND es.max_lng <= ? "
                 + "AND em.tenant_id = ?")) {
            ps.setDouble(1, box.minLat());
            ps.setDouble(2, box.maxLat());
            ps.setDouble(3, box.minLng());
            ps.setDouble(4, box.maxLng());
            ps.setString(5, tenantId);
            ResultSet rs = ps.executeQuery();

            List<CachedEntity> results = new ArrayList<>();
            while (rs.next()) {
                CachedEntity entity = mapRow(rs);
                if (matchesFilter(entity, filters)) {
                    results.add(entity);
                }
            }
            return results;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void remove(String entityId, String tenantId) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                long rowid = getRowid(c, entityId);
                if (rowid >= 0) {
                    try (PreparedStatement ps = c.prepareStatement(
                             "DELETE FROM entity_spatial WHERE id = ?")) {
                        ps.setLong(1, rowid);
                        ps.executeUpdate();
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                         "DELETE FROM entity_metadata WHERE entity_id = ? AND tenant_id = ?")) {
                    ps.setString(1, entityId);
                    ps.setString(2, tenantId);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void expire(String entityId, Instant expiresAt, String tenantId) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE entity_metadata SET expires_at = ? "
                 + "WHERE entity_id = ? AND tenant_id = ?")) {
            ps.setString(1, expiresAt.toString());
            ps.setString(2, entityId);
            ps.setString(3, tenantId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }


    @Override
    public List<CachedEntity> listAll(String tenantId) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM entity_metadata WHERE tenant_id = ?")) {
            ps.setString(1, tenantId);
            ResultSet          rs      = ps.executeQuery();
            List<CachedEntity> results = new ArrayList<>();
            while (rs.next()) {
                results.add(mapRow(rs));
            }
            return results;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<String> findExpired(String tenantId, Instant now) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT entity_id FROM entity_metadata "
                 + "WHERE tenant_id = ? AND expires_at < ? AND expires_at != ''")) {
            ps.setString(1, tenantId);
            ps.setString(2, now.toString());
            ResultSet rs = ps.executeQuery();
            List<String> expired = new ArrayList<>();
            while (rs.next()) {
                expired.add(rs.getString("entity_id"));
            }
            return expired;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Set<String> discoverTenants() {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT DISTINCT tenant_id FROM entity_metadata WHERE tenant_id != ''")) {
            ResultSet rs = ps.executeQuery();
            Set<String> tenants = new LinkedHashSet<>();
            while (rs.next()) {
                tenants.add(rs.getString("tenant_id"));
            }
            return tenants;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void upsertMetadata(Connection c, CachedEntity entity, String tenantId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO entity_metadata "
                 + "(entity_id, name, category, source, external_id, properties, "
                 + "fetched_at, detail_fetched_at, has_detail, tenant_id, "
                 + "latitude, longitude, expires_at) "
                 + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                 + "ON CONFLICT(entity_id) DO UPDATE SET "
                 + "name = excluded.name, category = excluded.category, "
                 + "source = excluded.source, external_id = excluded.external_id, "
                 + "properties = excluded.properties, fetched_at = excluded.fetched_at, "
                 + "detail_fetched_at = excluded.detail_fetched_at, "
                 + "has_detail = excluded.has_detail, tenant_id = excluded.tenant_id, "
                 + "latitude = excluded.latitude, longitude = excluded.longitude, "
                 + "expires_at = excluded.expires_at")) {
            ps.setString(1, entity.id());
            ps.setString(2, entity.name());
            ps.setString(3, entity.category());
            ps.setString(4, entity.source());
            ps.setString(5, entity.externalId());
            ps.setString(6, serializeProperties(entity.properties()));
            ps.setString(7, entity.fetchedAt() != null ? entity.fetchedAt().toString() : null);
            ps.setString(8, entity.detailFetchedAt() != null ? entity.detailFetchedAt().toString() : null);
            ps.setInt(9, entity.hasDetail() ? 1 : 0);
            ps.setString(10, tenantId);
            if (entity.coordinates() != null) {
                ps.setDouble(11, entity.coordinates().lat());
                ps.setDouble(12, entity.coordinates().lng());
            } else {
                ps.setNull(11, java.sql.Types.REAL);
                ps.setNull(12, java.sql.Types.REAL);
            }
            ps.setString(13, entity.expiresAt() != null ? entity.expiresAt().toString() : "");
            ps.executeUpdate();
        }
    }

    private void upsertSpatial(Connection c, long rowid, CachedEntity entity)
            throws SQLException {
        try (PreparedStatement del = c.prepareStatement(
                 "DELETE FROM entity_spatial WHERE id = ?")) {
            del.setLong(1, rowid);
            del.executeUpdate();
        }
        if (entity.coordinates() != null) {
            try (PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO entity_spatial (id, min_lat, max_lat, min_lng, max_lng) "
                     + "VALUES (?, ?, ?, ?, ?)")) {
                ps.setLong(1, rowid);
                ps.setDouble(2, entity.coordinates().lat());
                ps.setDouble(3, entity.coordinates().lat());
                ps.setDouble(4, entity.coordinates().lng());
                ps.setDouble(5, entity.coordinates().lng());
                ps.executeUpdate();
            }
        }
    }

    private long getRowid(Connection c, String entityId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                 "SELECT rowid FROM entity_metadata WHERE entity_id = ?")) {
            ps.setString(1, entityId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private CachedEntity mapRow(ResultSet rs) throws SQLException {
        Double lat = rs.getObject("latitude") != null ? rs.getDouble("latitude") : null;
        Double lng = rs.getObject("longitude") != null ? rs.getDouble("longitude") : null;
        Coordinates coords = (lat != null && lng != null) ? new Coordinates(lat, lng) : null;

        String expiresAtStr = rs.getString("expires_at");
        Instant expiresAt = (expiresAtStr != null && !expiresAtStr.isEmpty())
            ? Instant.parse(expiresAtStr) : null;

        String fetchedAtStr = rs.getString("fetched_at");
        Instant fetchedAt = (fetchedAtStr != null && !fetchedAtStr.isEmpty())
            ? Instant.parse(fetchedAtStr) : null;

        String detailFetchedAtStr = rs.getString("detail_fetched_at");
        Instant detailFetchedAt = (detailFetchedAtStr != null && !detailFetchedAtStr.isEmpty())
            ? Instant.parse(detailFetchedAtStr) : null;

        return new CachedEntity(
            rs.getString("entity_id"),
            rs.getString("name"),
            coords,
            rs.getString("category"),
            rs.getString("source"),
            rs.getString("external_id"),
            parseProperties(rs.getString("properties")),
            fetchedAt,
            detailFetchedAt,
            expiresAt,
            Set.of(),
            rs.getInt("has_detail") == 1,
            "location"
        );
    }

    private boolean matchesFilter(CachedEntity entity, CacheFilter filter) {
        if (filter == null) return true;
        if (filter.category() != null && !filter.category().equals(entity.category()))
            return false;
        return true;
    }

    static String serializeProperties(Map<String, String> props) {
        if (props == null || props.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : props.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey().replace("\"", "\\\"")).append("\":");
            sb.append("\"").append(entry.getValue().replace("\"", "\\\"")).append("\"");
            first = false;
        }
        return sb.append("}").toString();
    }

    static Map<String, String> parseProperties(String json) {
        if (json == null || json.isEmpty() || json.equals("{}")) return Map.of();
        Map<String, String> result = new HashMap<>();
        String inner = json.substring(1, json.length() - 1);
        for (String pair : inner.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
            String[] kv = pair.split(":", 2);
            if (kv.length == 2) {
                String key = kv[0].trim().replaceAll("^\"|\"$", "");
                String val = kv[1].trim().replaceAll("^\"|\"$", "");
                result.put(key, val);
            }
        }
        return result;
    }
}
