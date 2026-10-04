package io.casehub.neocortex.knowledge.research;

import com.zaxxer.hikari.HikariDataSource;
import io.casehub.neocortex.knowledge.ResearchSession;
import io.casehub.neocortex.knowledge.ResearchState;
import io.casehub.neocortex.sqlite.SqliteDataSourceFactory;

import jakarta.annotation.PreDestroy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ResearchSessionStore {

    private final HikariDataSource ds;

    public ResearchSessionStore(HikariDataSource ds) {
        this.ds = ds;
    }

    public ResearchSessionStore(String dbPath) {
        this.ds = SqliteDataSourceFactory.create(dbPath, 3, 5000);
        SqliteDataSourceFactory.migrate(ds, "classpath:db/knowledge-research");
    }

    public void insert(ResearchSession session) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO research_sessions "
                 + "(id, name, tenant_id, criteria, subgraph_id, state, created_at, last_active) "
                 + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, session.id());
            ps.setString(2, session.name());
            ps.setString(3, session.tenantId());
            ps.setString(4, session.criteria());
            ps.setString(5, session.mindMapSubgraphId());
            ps.setString(6, session.state().name());
            ps.setString(7, session.createdAt().toString());
            ps.setString(8, session.lastActive().toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void updateState(String sessionId, ResearchState state, Instant lastActive) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE research_sessions SET state = ?, last_active = ? WHERE id = ?")) {
            ps.setString(1, state.name());
            ps.setString(2, lastActive.toString());
            ps.setString(3, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public List<ResearchSession> listByState(String tenantId, ResearchState state) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT * FROM research_sessions WHERE tenant_id = ? AND state = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, state.name());
            ResultSet rs = ps.executeQuery();
            List<ResearchSession> result = new ArrayList<>();
            while (rs.next()) result.add(mapRow(rs));
            return result;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public Optional<ResearchSession> get(String sessionId) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT * FROM research_sessions WHERE id = ?")) {
            ps.setString(1, sessionId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            return Optional.of(mapRow(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private ResearchSession mapRow(ResultSet rs) throws SQLException {
        return new ResearchSession(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("criteria"),
            rs.getString("subgraph_id"),
            ResearchState.valueOf(rs.getString("state")),
            rs.getString("tenant_id"),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("last_active"))
        );
    }

    @PreDestroy
    public void close() { ds.close(); }
}
