package io.continuum.portal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Serialises account creation per email address.
 *
 * <p>Every path that creates an account checked "does this email exist?" and
 * then inserted — two requests arriving together both saw "no" and both
 * inserted. Twelve concurrent sign-ups for one address made ten accounts, and
 * sign-in then picked one of them arbitrarily. A transaction-scoped Postgres
 * advisory lock keyed on the lower-cased email makes the check and the insert
 * one step; it is released when the transaction ends.
 *
 * <p>Call it inside the transaction that checks and inserts. On a database
 * without advisory locks (the in-memory one some tests use) it does nothing.
 */
@Component
public class EmailLock {

    private final JdbcTemplate jdbc;
    private volatile Boolean postgres;

    public EmailLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lock(String email) {
        if (email == null || !isPostgres()) {
            return;
        }
        jdbc.queryForList("select pg_advisory_xact_lock(hashtext(?))",
                email.trim().toLowerCase(Locale.ROOT));
    }

    private boolean isPostgres() {
        Boolean p = postgres;
        if (p == null) {
            p = Boolean.TRUE.equals(jdbc.execute((java.sql.Connection c) ->
                    c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres")));
            postgres = p;
        }
        return p;
    }
}
