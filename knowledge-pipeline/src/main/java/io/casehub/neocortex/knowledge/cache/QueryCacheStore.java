package io.casehub.neocortex.knowledge.cache;

import io.casehub.connectors.location.model.Coordinates;
import io.casehub.neocortex.knowledge.KnowledgeQuery;
import io.casehub.neocortex.knowledge.NormalizedQuery;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class QueryCacheStore {

    private final HikariDataSource ds;

    public QueryCacheStore(HikariDataSource ds) {
        this.ds = ds;
    }

    public record QueryCacheEntry(String cacheKey, String tenantId,
                                   List<String> entityIds, Instant answeredAt,
                                   Instant expiresAt, String queryType,
                                   Double lat, Double lng, Integer radiusMeters,
                                   String category) {

        public NormalizedQuery toNormalizedQuery() {
            KnowledgeQuery query = switch (queryType) {
                case "TEXT" -> new KnowledgeQuery.TextSearch(
                    cacheKey.substring("TEXT:".length()));
                case "NEARBY" -> new KnowledgeQuery.NearbySearch(
                    new Coordinates(lat, lng), radiusMeters, null);
                case "CATEGORY" -> new KnowledgeQuery.CategorySearch(
                    category, new Coordinates(lat, lng), radiusMeters);
                default -> throw new IllegalStateException("Unknown query type: " + queryType);
            };
            return new NormalizedQuery(query, cacheKey);
        }
    }

    public Optional<QueryCacheEntry> lookup(String cacheKey, String tenantId) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT * FROM query_cache WHERE cache_key = ? AND tenant_id = ?")) {
            ps.setString(1, cacheKey);
            ps.setString(2, tenantId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            Instant expiresAt = Instant.parse(rs.getString("expires_at"));
            if (Instant.now().isAfter(expiresAt)) return Optional.empty();
            return Optional.of(mapRow(rs, expiresAt));
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public List<QueryCacheEntry> listForTenant(String tenantId) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT * FROM query_cache WHERE tenant_id = ? AND expires_at > ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, Instant.now().toString());
            ResultSet rs = ps.executeQuery();
            List<QueryCacheEntry> entries = new ArrayList<>();
            while (rs.next()) {
                entries.add(mapRow(rs, Instant.parse(rs.getString("expires_at"))));
            }
            return entries;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void record(String cacheKey, String tenantId, List<String> entityIds,
                       Instant expiresAt) {
        record(cacheKey, tenantId, entityIds, expiresAt, null, null, null, null, null);
    }

    public void record(String cacheKey, String tenantId, List<String> entityIds,
                       Instant expiresAt, String queryType,
                       Double lat, Double lng, Integer radiusMeters, String category) {
        String now = Instant.now().toString();
        String idsJson = serializeEntityIds(entityIds);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT OR REPLACE INTO query_cache "
                 + "(cache_key, tenant_id, entity_ids, answered_at, expires_at, "
                 + "query_type, lat, lng, radius_meters, category) "
                 + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, cacheKey);
            ps.setString(2, tenantId);
            ps.setString(3, idsJson);
            ps.setString(4, now);
            ps.setString(5, expiresAt.toString());
            ps.setString(6, queryType);
            if (lat != null) ps.setDouble(7, lat); else ps.setNull(7, java.sql.Types.REAL);
            if (lng != null) ps.setDouble(8, lng); else ps.setNull(8, java.sql.Types.REAL);
            if (radiusMeters != null) ps.setInt(9, radiusMeters); else ps.setNull(9, java.sql.Types.INTEGER);
            ps.setString(10, category);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private QueryCacheEntry mapRow(ResultSet rs, Instant expiresAt) throws SQLException {
        return new QueryCacheEntry(
            rs.getString("cache_key"),
            rs.getString("tenant_id"),
            parseEntityIds(rs.getString("entity_ids")),
            Instant.parse(rs.getString("answered_at")),
            expiresAt,
            rs.getString("query_type"),
            rs.getObject("lat") != null ? rs.getDouble("lat") : null,
            rs.getObject("lng") != null ? rs.getDouble("lng") : null,
            rs.getObject("radius_meters") != null ? rs.getInt("radius_meters") : null,
            rs.getString("category"));
    }

    static String serializeEntityIds(List<String> ids) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(ids.get(i).replace("\"", "\\\"")).append("\"");
        }
        return sb.append("]").toString();
    }

    static List<String> parseEntityIds(String json) {
        if (json == null || json.equals("[]")) return List.of();
        String inner = json.substring(1, json.length() - 1);
        var result = new java.util.ArrayList<String>();
        for (String part : inner.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                result.add(trimmed.substring(1, trimmed.length() - 1));
            }
        }
        return List.copyOf(result);
    }
}
