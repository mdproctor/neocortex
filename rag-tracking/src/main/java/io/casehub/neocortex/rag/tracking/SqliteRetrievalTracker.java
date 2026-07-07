package io.casehub.neocortex.rag.tracking;

import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.RetrievalFeedback;
import io.casehub.neocortex.rag.RetrievalOutcome;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.RetrievalRecord;
import io.casehub.neocortex.rag.RetrievalTracker;
import io.casehub.neocortex.rag.RetrievedChunk;
import io.casehub.neocortex.rag.RetrievedDocumentRef;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;
import org.sqlite.SQLiteConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class SqliteRetrievalTracker implements RetrievalTracker {

    @ConfigProperty(name = "casehub.rag.tracking.sqlite.path")
    String path;

    @ConfigProperty(name = "casehub.rag.tracking.sqlite.pool.max-size", defaultValue = "5")
    int maxPoolSize;

    @ConfigProperty(name = "casehub.rag.tracking.sqlite.busy-timeout-ms", defaultValue = "5000")
    int busyTimeoutMs;

    private HikariDataSource dataSource;

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
            .locations("classpath:db/rag-tracking/migration")
            .load()
            .migrate();
    }

    @PreDestroy
    void shutdown() {
        if (dataSource != null) dataSource.close();
    }

    @Override
    public String record(RetrievalQuery query, CorpusRef corpus,
                         List<RetrievedChunk> results, int maxResults) {
        String retrievalId = UUID.randomUUID().toString();
        String timestamp = toIso(Instant.now());

        // Deduplicate chunks by sourceDocumentId, keeping max relevanceScore
        Map<String, Double> deduped = results.stream()
            .collect(Collectors.toMap(
                RetrievedChunk::sourceDocumentId,
                RetrievedChunk::relevanceScore, Math::max));

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Insert retrieval record
                try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO retrieval_records (retrieval_id, query_text, expanded_text, tenant_id, corpus_name, max_results, timestamp) VALUES (?,?,?,?,?,?,?)")) {
                    ps.setString(1, retrievalId);
                    ps.setString(2, query.text());
                    ps.setString(3, query.expandedText());
                    ps.setString(4, corpus.tenantId());
                    ps.setString(5, corpus.corpusName());
                    ps.setInt(6, maxResults);
                    ps.setString(7, timestamp);
                    ps.executeUpdate();
                }

                // Insert retrieved documents
                try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO retrieved_documents (retrieval_id, source_document_id, relevance_score) VALUES (?,?,?)")) {
                    for (var entry : deduped.entrySet()) {
                        ps.setString(1, retrievalId);
                        ps.setString(2, entry.getKey());
                        ps.setDouble(3, entry.getValue());
                        ps.executeUpdate();
                    }
                }

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("record() failed", e);
        }

        return retrievalId;
    }

    @Override
    public void feedback(String retrievalId, String sourceDocumentId,
                         RetrievalOutcome outcome) {
        String timestamp = toIso(Instant.now());
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT OR REPLACE INTO retrieval_feedback (retrieval_id, source_document_id, outcome, timestamp) VALUES (?,?,?,?)")) {
            ps.setString(1, retrievalId);
            ps.setString(2, sourceDocumentId);
            ps.setString(3, outcome.name());
            ps.setString(4, timestamp);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("feedback() failed", e);
        }
    }

    @Override
    public List<RetrievalRecord> findRecords(CorpusRef corpus,
                                              Instant since, Instant until) {
        var sql = new StringBuilder("SELECT retrieval_id, query_text, expanded_text, tenant_id, corpus_name, max_results, timestamp FROM retrieval_records WHERE tenant_id = ? AND corpus_name = ?");
        boolean hasSince = hasSinceFilter(since);
        boolean hasUntil = hasUntilFilter(until);
        if (hasSince) sql.append(" AND timestamp >= ?");
        if (hasUntil) sql.append(" AND timestamp < ?");
        sql.append(" ORDER BY timestamp ASC");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setString(idx++, corpus.tenantId());
            ps.setString(idx++, corpus.corpusName());
            if (hasSince) ps.setString(idx++, toIso(since));
            if (hasUntil) ps.setString(idx++, toIso(until));

            List<RetrievalRecord> records = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String retrievalId = rs.getString("retrieval_id");
                    String queryText = rs.getString("query_text");
                    String expandedText = rs.getString("expanded_text");
                    String tenantId = rs.getString("tenant_id");
                    String corpusName = rs.getString("corpus_name");
                    int maxResults = rs.getInt("max_results");
                    Instant timestamp = fromIso(rs.getString("timestamp"));

                    RetrievalQuery query = expandedText != null
                        ? new RetrievalQuery(queryText, expandedText)
                        : RetrievalQuery.of(queryText);
                    CorpusRef ref = new CorpusRef(tenantId, corpusName);
                    List<RetrievedDocumentRef> docs = findDocumentRefs(conn, retrievalId);

                    records.add(new RetrievalRecord(retrievalId, query, ref,
                        docs, maxResults, timestamp));
                }
            }
            return List.copyOf(records);
        } catch (SQLException e) {
            throw new IllegalStateException("findRecords() failed", e);
        }
    }

    @Override
    public List<RetrievalFeedback> findFeedback(CorpusRef corpus,
                                                 Instant since, Instant until) {
        var sql = new StringBuilder("SELECT f.retrieval_id, f.source_document_id, f.outcome, f.timestamp FROM retrieval_feedback f JOIN retrieval_records r ON f.retrieval_id = r.retrieval_id WHERE r.tenant_id = ? AND r.corpus_name = ?");
        boolean hasSince = hasSinceFilter(since);
        boolean hasUntil = hasUntilFilter(until);
        if (hasSince) sql.append(" AND f.timestamp >= ?");
        if (hasUntil) sql.append(" AND f.timestamp < ?");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setString(idx++, corpus.tenantId());
            ps.setString(idx++, corpus.corpusName());
            if (hasSince) ps.setString(idx++, toIso(since));
            if (hasUntil) ps.setString(idx++, toIso(until));

            List<RetrievalFeedback> feedbacks = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    feedbacks.add(new RetrievalFeedback(
                        rs.getString("retrieval_id"),
                        rs.getString("source_document_id"),
                        RetrievalOutcome.valueOf(rs.getString("outcome")),
                        fromIso(rs.getString("timestamp"))
                    ));
                }
            }
            return List.copyOf(feedbacks);
        } catch (SQLException e) {
            throw new IllegalStateException("findFeedback() failed", e);
        }
    }

    @Override
    public Set<String> findRetrievedDocumentIds(CorpusRef corpus,
                                                 Instant since, Instant until) {
        var sql = new StringBuilder("SELECT DISTINCT d.source_document_id FROM retrieved_documents d JOIN retrieval_records r ON d.retrieval_id = r.retrieval_id WHERE r.tenant_id = ? AND r.corpus_name = ?");
        boolean hasSince = hasSinceFilter(since);
        boolean hasUntil = hasUntilFilter(until);
        if (hasSince) sql.append(" AND r.timestamp >= ?");
        if (hasUntil) sql.append(" AND r.timestamp < ?");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setString(idx++, corpus.tenantId());
            ps.setString(idx++, corpus.corpusName());
            if (hasSince) ps.setString(idx++, toIso(since));
            if (hasUntil) ps.setString(idx++, toIso(until));

            Set<String> ids = new LinkedHashSet<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
            }
            return Set.copyOf(ids);
        } catch (SQLException e) {
            throw new IllegalStateException("findRetrievedDocumentIds() failed", e);
        }
    }

    @Override
    public int purgeOlderThan(Instant cutoff) {
        String cutoffIso = toIso(cutoff);
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM retrieval_feedback WHERE retrieval_id IN "
                        + "(SELECT retrieval_id FROM retrieval_records WHERE timestamp < ?)")) {
                    ps.setString(1, cutoffIso);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM retrieved_documents WHERE retrieval_id IN "
                        + "(SELECT retrieval_id FROM retrieval_records WHERE timestamp < ?)")) {
                    ps.setString(1, cutoffIso);
                    ps.executeUpdate();
                }
                int deleted;
                try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM retrieval_records WHERE timestamp < ?")) {
                    ps.setString(1, cutoffIso);
                    deleted = ps.executeUpdate();
                }
                conn.commit();
                return deleted;
            } catch (Exception e) {
                conn.rollback();
                throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("purgeOlderThan() failed", e);
        }
    }

    // --- package-private for tests ---

    void clearAll() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM retrieval_feedback");
            stmt.executeUpdate("DELETE FROM retrieved_documents");
            stmt.executeUpdate("DELETE FROM retrieval_records");
        } catch (SQLException e) {
            throw new IllegalStateException("clearAll() failed", e);
        }
    }

    // --- private helpers ---

    private List<RetrievedDocumentRef> findDocumentRefs(Connection conn, String retrievalId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT source_document_id, relevance_score FROM retrieved_documents WHERE retrieval_id = ?")) {
            ps.setString(1, retrievalId);
            List<RetrievedDocumentRef> refs = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    refs.add(new RetrievedDocumentRef(
                        rs.getString("source_document_id"),
                        rs.getDouble("relevance_score")));
                }
            }
            return List.copyOf(refs);
        }
    }

    /**
     * Instant.EPOCH and Instant.MIN produce ISO strings that sort correctly
     * against normal timestamps, so they are safe to use. But skip them
     * entirely for cleaner SQL when they represent "no lower bound".
     */
    private static boolean hasSinceFilter(Instant since) {
        return since != null && !since.equals(Instant.EPOCH) && !since.equals(Instant.MIN);
    }

    /**
     * Instant.MAX encodes to "+1000000000-12-31T..." which sorts
     * lexicographically before normal year-prefixed timestamps ('+' < '0').
     * Skip the upper bound entirely when it represents "no upper bound".
     */
    private static boolean hasUntilFilter(Instant until) {
        return until != null && !until.equals(Instant.MAX);
    }

    private static String toIso(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private static Instant fromIso(String iso) {
        return Instant.parse(iso);
    }
}
