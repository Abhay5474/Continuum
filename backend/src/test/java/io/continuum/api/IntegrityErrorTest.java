package io.continuum.api;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/** A value too long for its column is the caller's mistake, not a duplicate that is safe to retry. */
class IntegrityErrorTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private int status(String sqlState) {
        var e = new DataIntegrityViolationException("x", new SQLException("db says no", sqlState));
        return handler.conflict(e).getStatusCode().value();
    }

    @Test
    void eachIntegrityErrorSaysWhatItWas() {
        assertThat(status("22001")).isEqualTo(400);
        assertThat(status("23502")).isEqualTo(400);
        assertThat(status("23503")).isEqualTo(409);
        assertThat(status("23505")).isEqualTo(409);
        var tooLong = handler.conflict(new DataIntegrityViolationException("x", new SQLException("", "22001")));
        assertThat(String.valueOf(tooLong.getBody().get("error"))).contains("longer than allowed");
    }
}
