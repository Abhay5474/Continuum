package io.continuum.registry.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** {@link ModelCatalogStore} on the V51 tables. */
@Component
public class JdbcModelCatalogStore implements ModelCatalogStore {

    private final JdbcTemplate jdbc;

    public JdbcModelCatalogStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ProviderState state(String provider) {
        List<ProviderState> rows = jdbc.query("select * from model_provider_state where provider = ?", STATE, provider);
        return rows.isEmpty() ? new ProviderState(provider) : rows.get(0);
    }

    @Override
    public List<ProviderState> states() {
        return jdbc.query("select * from model_provider_state order by provider", STATE);
    }

    @Override
    public void saveState(ProviderState s) {
        jdbc.update("""
                insert into model_provider_state (provider, last_list_ok_at, last_attempt_at, last_error,
                    listed_count, default_model, pinned_model, last_confirm_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (provider) do update set
                    last_list_ok_at = excluded.last_list_ok_at, last_attempt_at = excluded.last_attempt_at,
                    last_error = excluded.last_error, listed_count = excluded.listed_count,
                    default_model = excluded.default_model, pinned_model = excluded.pinned_model,
                    last_confirm_at = excluded.last_confirm_at
                """, s.provider, ts(s.lastListOkAt), ts(s.lastAttemptAt), s.lastError, s.listedCount,
                s.defaultModel, s.pinnedModel, ts(s.lastConfirmAt));
    }

    @Override
    public long startRun(String trigger, String requestedBy) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "insert into model_check_runs (trigger_kind, requested_by) values (?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, trigger);
            ps.setString(2, requestedBy);
            return ps;
        }, keys);
        Object id = keys.getKeys() == null ? null : keys.getKeys().get("id");
        return id == null ? -1 : ((Number) id).longValue();
    }

    @Override
    public void finishRun(long id, String outcome, String summaryJson, int listCalls, int probeCalls) {
        jdbc.update("update model_check_runs set finished_at = now(), outcome = ?, summary = ?, "
                + "list_calls = ?, probe_calls = ? where id = ?", outcome, summaryJson, listCalls, probeCalls, id);
    }

    @Override
    public Optional<Run> lastRun() {
        return jdbc.query("select * from model_check_runs where trigger_kind <> 'CONFIRM' "
                + "order by started_at desc limit 1", RUN).stream().findFirst();
    }

    @Override
    public List<Run> recentRuns(int limit) {
        return jdbc.query("select * from model_check_runs order by started_at desc limit ?", RUN,
                Math.max(1, Math.min(100, limit)));
    }

    @Override
    public void event(String provider, String model, String type, String detail, Long runId) {
        jdbc.update("insert into model_events (provider, model_name, type, detail, run_id) values (?, ?, ?, ?, ?)",
                provider, model, type, detail, runId);
    }

    @Override
    public List<Event> events(int limit) {
        return jdbc.query("select * from model_events order by created_at desc, id desc limit ?",
                (rs, i) -> new Event(rs.getLong("id"), rs.getString("provider"), rs.getString("model_name"),
                        rs.getString("type"), rs.getString("detail"), instant(rs, "created_at")),
                Math.max(1, Math.min(500, limit)));
    }

    @Override
    public boolean acquireLease(String holder, Duration ttl) {
        return jdbc.update("update model_check_lease set holder = ?, acquired_at = now() where id = 1 "
                + "and (acquired_at is null or acquired_at < now() - (? * interval '1 second'))",
                holder, ttl.toSeconds()) == 1;
    }

    @Override
    public void releaseLease(String holder) {
        jdbc.update("update model_check_lease set holder = null, acquired_at = null where id = 1 and holder = ?", holder);
    }

    private static final RowMapper<ProviderState> STATE = (rs, i) -> {
        ProviderState s = new ProviderState(rs.getString("provider"));
        s.lastListOkAt = instant(rs, "last_list_ok_at");
        s.lastAttemptAt = instant(rs, "last_attempt_at");
        s.lastError = rs.getString("last_error");
        s.listedCount = rs.getInt("listed_count");
        s.defaultModel = rs.getString("default_model");
        s.pinnedModel = rs.getString("pinned_model");
        s.lastConfirmAt = instant(rs, "last_confirm_at");
        return s;
    };

    private static final RowMapper<Run> RUN = (rs, i) -> new Run(rs.getLong("id"), rs.getString("trigger_kind"),
            rs.getString("requested_by"), instant(rs, "started_at"), instant(rs, "finished_at"),
            rs.getString("outcome"), rs.getString("summary"), rs.getInt("list_calls"), rs.getInt("probe_calls"));

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
