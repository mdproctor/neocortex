package io.casehub.neocortex.memory.cbr.tracking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.casehub.neocortex.fusion.FusionStrategy;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrFilter;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRetrievalTrace;
import io.casehub.neocortex.memory.cbr.CbrRetrievalTracker;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.NumericRange;
import io.casehub.neocortex.memory.cbr.RetrievalMode;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.flywaydb.core.Flyway;
import org.jboss.logging.Logger;
import org.sqlite.SQLiteConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class SqliteCbrRetrievalTracker implements CbrRetrievalTracker {

    private static final Logger LOG = Logger.getLogger(SqliteCbrRetrievalTracker.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<CbrRetrievalTrace.TracedCase>> TRACED_LIST_TYPE =
            new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {};

    private HikariDataSource dataSource;
    private int retentionDays;

    @Inject
    SqliteCbrRetrievalTracker(CbrTrackingConfig config) {
        this(config.sqlite().path(), config.sqlite().poolMaxSize(), config.sqlite().busyTimeoutMs());
        this.retentionDays = config.retentionDays();
    }

    SqliteCbrRetrievalTracker(String path, int poolSize, int busyTimeoutMs) {
        this.retentionDays = 90;
        initDataSource(path, poolSize, busyTimeoutMs);
    }

    private void initDataSource(String path, int poolSize, int busyTimeoutMs) {
        boolean isMemory = ":memory:".equals(path) || path == null || path.isBlank();
        int effectivePoolSize = isMemory ? 1 : poolSize;

        SQLiteConfig sqLiteConfig = new SQLiteConfig();
        if (!isMemory) {
            sqLiteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
        }
        sqLiteConfig.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        sqLiteConfig.setBusyTimeout(busyTimeoutMs);

        org.sqlite.SQLiteDataSource sqLiteDataSource = new org.sqlite.SQLiteDataSource(sqLiteConfig);
        sqLiteDataSource.setUrl("jdbc:sqlite:" + (isMemory ? ":memory:" : path));

        HikariConfig hikari = new HikariConfig();
        hikari.setDataSource(sqLiteDataSource);
        hikari.setMaximumPoolSize(effectivePoolSize);
        hikari.setMinimumIdle(1);

        dataSource = new HikariDataSource(hikari);
    }

    @PostConstruct
    void init() {
        if (dataSource == null) return;
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/cbr-tracking/migration")
                .load()
                .migrate();
    }

    @PreDestroy
    void shutdown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Override
    public String record(CbrQuery query, List<ScoredCbrCase<?>> results) {
        String traceId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        List<CbrRetrievalTrace.TracedCase> traced = results.stream()
                .map(s -> new CbrRetrievalTrace.TracedCase(
                        s.caseId(), s.score(), s.reranked(),
                        s.featureSimilarities(), s.cbrCase().confidence()))
                .toList();

        String resultsJson;
        String queryJson;
        try {
            resultsJson = MAPPER.writeValueAsString(traced);
            queryJson = MAPPER.writeValueAsString(serializeQuery(query));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize trace", e);
        }

        String sql = "INSERT INTO cbr_retrieval_traces (trace_id, case_type, tenant_id, domain, query_json, results_json, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, traceId);
            ps.setString(2, query.caseType());
            ps.setString(3, query.tenantId());
            ps.setString(4, query.domain().name());
            ps.setString(5, queryJson);
            ps.setString(6, resultsJson);
            ps.setString(7, now.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record CBR retrieval trace", e);
        }

        return traceId;
    }

    @Override
    public List<CbrRetrievalTrace> findTraces(String caseType, String tenantId,
                                               MemoryDomain domain,
                                               Instant since, Instant until) {
        String sql = "SELECT trace_id, query_json, results_json, timestamp FROM cbr_retrieval_traces WHERE case_type = ? AND tenant_id = ? AND domain = ? AND timestamp >= ? AND timestamp < ? ORDER BY timestamp";
        List<CbrRetrievalTrace> traces = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, caseType);
            ps.setString(2, tenantId);
            ps.setString(3, domain.name());
            ps.setString(4, since.toString());
            ps.setString(5, until.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String traceId = rs.getString("trace_id");
                    String queryJsonStr = rs.getString("query_json");
                    String resultsJson = rs.getString("results_json");
                    String timestamp = rs.getString("timestamp");

                    List<CbrRetrievalTrace.TracedCase> results = MAPPER.readValue(resultsJson, TRACED_LIST_TYPE);
                    CbrQuery query = deserializeQuery(MAPPER.readValue(queryJsonStr, MAP_TYPE));
                    traces.add(new CbrRetrievalTrace(traceId, query, results, Instant.parse(timestamp)));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to find CBR retrieval traces", e);
        }
        return traces;
    }

    @Override
    public int purgeOlderThan(Instant cutoff) {
        String sql = "DELETE FROM cbr_retrieval_traces WHERE timestamp < ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cutoff.toString());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to purge CBR retrieval traces", e);
        }
    }

    @Scheduled(every = "24h")
    void purgeExpired() {
        try {
            int purged = purgeOlderThan(Instant.now().minus(retentionDays, ChronoUnit.DAYS));
            if (purged > 0) {
                LOG.infof("Purged %d expired CBR retrieval traces (retention: %d days)", purged, retentionDays);
            }
        } catch (Exception e) {
            LOG.warn("Failed to purge expired CBR retrieval traces", e);
        }
    }

    static Map<String, Object> serializeQuery(CbrQuery query) {
        var map = new LinkedHashMap<String, Object>();
        map.put("caseType", query.caseType());
        map.put("tenantId", query.tenantId());
        map.put("domain", query.domain().name());
        map.put("retrievalMode", query.retrievalMode().name());
        map.put("topK", query.topK());
        map.put("minSimilarity", query.minSimilarity());
        map.put("vectorWeight", query.vectorWeight());
        map.put("fusionStrategy", query.fusionStrategy().name());
        map.put("features", FeatureValue.toRawMap(query.features()));
        if (query.problem() != null) map.put("problem", query.problem());
        if (query.notBefore() != null) map.put("notBefore", query.notBefore().toString());
        if (!query.weights().isEmpty()) map.put("weights", query.weights());
        if (!query.filters().isEmpty()) {
            var filterMap = new LinkedHashMap<String, Object>();
            query.filters().forEach((k, v) -> filterMap.put(k, serializeFilter(v)));
            map.put("filters", filterMap);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    static CbrQuery deserializeQuery(Map<String, Object> map) {
        String ct = (String) map.get("caseType");
        String tid = (String) map.get("tenantId");
        MemoryDomain dom = new MemoryDomain((String) map.get("domain"));
        RetrievalMode mode = RetrievalMode.valueOf((String) map.get("retrievalMode"));
        int topK = ((Number) map.get("topK")).intValue();
        double minSim = ((Number) map.get("minSimilarity")).doubleValue();
        double vecW = ((Number) map.getOrDefault("vectorWeight", 0.5)).doubleValue();
        FusionStrategy fusion = FusionStrategy.valueOf((String) map.getOrDefault("fusionStrategy", "RRF"));
        Map<String, FeatureValue> features = map.containsKey("features")
                ? FeatureValue.toFeatureMap((Map<String, Object>) map.get("features"))
                : Map.of();
        String problem = (String) map.get("problem");
        Instant notBefore = map.containsKey("notBefore") ? Instant.parse((String) map.get("notBefore")) : null;
        Map<String, Double> weights = map.containsKey("weights")
                ? ((Map<String, Object>) map.get("weights")).entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> ((Number) e.getValue()).doubleValue()))
                : Map.of();
        Map<String, CbrFilter> filters = map.containsKey("filters")
                ? ((Map<String, Object>) map.get("filters")).entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> deserializeFilter((Map<String, Object>) e.getValue())))
                : Map.of();
        return new CbrQuery(tid, dom, ct, features, filters, weights,
                topK, minSim, notBefore, problem, vecW, mode, fusion, null);
    }

    static Map<String, Object> serializeFilter(CbrFilter filter) {
        return switch (filter) {
            case CbrFilter.Contains c -> Map.of("type", "Contains", "value", c.value());
            case CbrFilter.ContainsAll c -> Map.of("type", "ContainsAll", "values", c.values());
            case CbrFilter.ContainsAny c -> Map.of("type", "ContainsAny", "values", c.values());
            case CbrFilter.HasMatch c -> Map.of("type", "HasMatch", "subFields", FeatureValue.toRawMap(c.subFields()));
            case CbrFilter.NotContains c -> Map.of("type", "NotContains", "value", c.value());
            case CbrFilter.NotContainsAny c -> Map.of("type", "NotContainsAny", "values", c.values());
            case CbrFilter.ContainsRange c -> Map.of("type", "ContainsRange", "min", c.range().min(), "max", c.range().max());
            case CbrFilter.AllOf c -> Map.of("type", "AllOf", "filters", c.filters().stream().map(SqliteCbrRetrievalTracker::serializeFilter).toList());
        };
    }

    @SuppressWarnings("unchecked")
    static CbrFilter deserializeFilter(Map<String, Object> map) {
        String type = (String) map.get("type");
        return switch (type) {
            case "Contains" -> CbrFilter.contains((String) map.get("value"));
            case "ContainsAll" -> CbrFilter.containsAll((List<String>) map.get("values"));
            case "ContainsAny" -> CbrFilter.containsAny((List<String>) map.get("values"));
            case "HasMatch" -> CbrFilter.hasMatch(FeatureValue.toFeatureMap((Map<String, Object>) map.get("subFields")));
            case "NotContains" -> CbrFilter.notContains((String) map.get("value"));
            case "NotContainsAny" -> CbrFilter.notContainsAny((List<String>) map.get("values"));
            case "ContainsRange" -> CbrFilter.containsRange(NumericRange.of(
                    ((Number) map.get("min")).doubleValue(), ((Number) map.get("max")).doubleValue()));
            case "AllOf" -> CbrFilter.allOf(((List<Map<String, Object>>) map.get("filters")).stream()
                    .map(SqliteCbrRetrievalTracker::deserializeFilter).toArray(CbrFilter[]::new));
            default -> throw new IllegalArgumentException("Unknown filter type: " + type);
        };
    }
}
