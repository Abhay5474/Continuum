package io.continuum.portal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes every row that belongs to an account, found from the schema.
 *
 * <p>Account deletion used to name the tables to clear by hand: credentials,
 * keys, invites, billing, the auth record. Every feature added since — the
 * request log, the semantic cache's stored prompts and answers, memory,
 * provenance, specialist connections, a dozen settings tables — was never
 * added to that list, so "delete my account and all my data" left most of it
 * behind. A list kept by hand drifts again with the next feature; this asks
 * the database instead.
 *
 * <p>An account's rows are found three ways:
 * <ul>
 *   <li>a {@code developer_id} column equal to the account;</li>
 *   <li>a {@code workflow_id} of one of the account's workflows (event logs,
 *       tasks, traces — tables that do not carry the developer themselves);</li>
 *   <li>a memory {@code scope} prefixed with the account id.</li>
 * </ul>
 *
 * <p>Tables are cleared children-first by their foreign keys, each statement
 * under its own savepoint: in Postgres one failed statement aborts the whole
 * transaction, which is how the old best-effort cleanup could quietly skip
 * everything after its first failure.
 */
@Component
public class AccountErasure {

    private static final Logger log = LoggerFactory.getLogger(AccountErasure.class);

    /** Removed by the caller, in its own order, after everything here. */
    private static final Set<String> LEFT_TO_CALLER = Set.of("developers");

    private final JdbcTemplate jdbc;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;

    public AccountErasure(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return rows removed per table, for the log */
    public Map<String, Integer> erase(String developerId) {
        // Changes the caller made through JPA go to the database first, and the
        // session is emptied afterwards: otherwise Hibernate flushes deletes
        // for rows this has already removed, finds nothing to delete, and
        // fails the whole erasure at commit.
        if (em != null && em.isJoinedToTransaction()) {
            em.flush();
        }
        Map<String, Integer> removed = eraseRows(developerId);
        if (em != null && em.isJoinedToTransaction()) {
            em.clear();
        }
        return removed;
    }

    private Map<String, Integer> eraseRows(String developerId) {
        Map<String, Integer> removed = new LinkedHashMap<>();
        List<String> byWorkflow = tablesWith("workflow_id");
        List<String> byDeveloper = tablesWith("developer_id");
        List<String> byScope = tablesWith("scope");

        // Workflow-keyed rows first. The account's workflow ids are collected
        // before anything is deleted, from every table that has both columns
        // (DAG runs carry the developer on dag_runs, not on the instance).
        Set<String> workflows = new HashSet<>();
        for (String t : byDeveloper) {
            if (byWorkflow.contains(t)) {
                workflows.addAll(jdbc.queryForList("select distinct workflow_id from " + q(t)
                        + " where developer_id = ? and workflow_id is not null", String.class, developerId));
            }
        }
        List<String> ids = new ArrayList<>(workflows);
        for (String t : childrenFirst(byWorkflow)) {
            for (int i = 0; i < ids.size(); i += 500) {
                List<String> chunk = ids.subList(i, Math.min(ids.size(), i + 500));
                String marks = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
                run(removed, t, "delete from " + q(t) + " where workflow_id in (" + marks + ")", chunk.toArray());
            }
        }
        for (String t : byScope) {
            run(removed, t, "delete from " + q(t) + " where scope like ?", developerId + "::%");
        }
        // Several passes, because a foreign key the ordering could not see
        // (or a row added meanwhile) can make one delete wait for another.
        List<String> pending = new ArrayList<>(childrenFirst(byDeveloper));
        pending.removeAll(LEFT_TO_CALLER);
        for (int pass = 0; pass < 3 && !pending.isEmpty(); pass++) {
            List<String> failed = new ArrayList<>();
            for (String t : pending) {
                if (!run(removed, t, "delete from " + q(t) + " where developer_id = ?", developerId)) {
                    failed.add(t);
                }
            }
            pending = failed;
        }
        if (!pending.isEmpty()) {
            log.warn("Account {} erasure could not clear {}", developerId, pending);
        }
        return removed;
    }

    private boolean run(Map<String, Integer> removed, String table, String sql, Object... args) {
        String sp = "erase_" + Integer.toHexString(table.hashCode() & 0xffffff);
        try {
            jdbc.execute("SAVEPOINT " + sp);
            int n = jdbc.update(sql, args);
            jdbc.execute("RELEASE SAVEPOINT " + sp);
            if (n > 0) {
                removed.merge(table, n, Integer::sum);
            }
            return true;
        } catch (RuntimeException e) {
            try {
                jdbc.execute("ROLLBACK TO SAVEPOINT " + sp);
            } catch (RuntimeException ignored) {
                // no transaction (auto-commit): nothing to roll back
            }
            log.debug("erase {}: {}", table, e.getMessage());
            return false;
        }
    }

    private List<String> tablesWith(String column) {
        return jdbc.queryForList(
                "select table_name from information_schema.columns "
                        + "where table_schema = current_schema() and column_name = ? order by table_name",
                String.class, column);
    }

    /** Orders tables so each comes before any table it references. */
    private List<String> childrenFirst(List<String> tables) {
        Map<String, Set<String>> parents = new HashMap<>();
        for (Map<String, Object> fk : jdbc.queryForList(
                "select tc.table_name as child, ccu.table_name as parent "
                        + "from information_schema.table_constraints tc "
                        + "join information_schema.constraint_column_usage ccu "
                        + "on tc.constraint_name = ccu.constraint_name and tc.table_schema = ccu.table_schema "
                        + "where tc.constraint_type = 'FOREIGN KEY' and tc.table_schema = current_schema()")) {
            String child = String.valueOf(fk.get("child"));
            String parent = String.valueOf(fk.get("parent"));
            if (!child.equals(parent)) {
                parents.computeIfAbsent(child, k -> new HashSet<>()).add(parent);
            }
        }
        // A table is placed once nothing still waiting references it.
        List<String> out = new ArrayList<>();
        Set<String> waiting = new java.util.LinkedHashSet<>(tables);
        while (!waiting.isEmpty()) {
            String next = waiting.stream()
                    .filter(t -> waiting.stream().noneMatch(o -> parents.getOrDefault(o, Set.of()).contains(t)))
                    .findFirst()
                    .orElse(waiting.iterator().next()); // a cycle: the passes above sort it out
            out.add(next);
            waiting.remove(next);
        }
        return out;
    }

    private static String q(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
