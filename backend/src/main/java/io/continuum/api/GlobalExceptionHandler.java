package io.continuum.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body(e.getMessage(), 400));
    }

    /** Cross-tenant read attempt: the record exists but belongs to someone else. */
    @ExceptionHandler(io.continuum.portal.RequestScope.ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> forbidden(io.continuum.portal.RequestScope.ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body(e.getMessage(), 403));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> generic(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body(e.getMessage(), 500));
    }

    private Map<String, Object> body(String message, int status) {
        return Map.of("error", message == null ? "Unexpected error" : message,
                "status", status, "timestamp", Instant.now().toString());
    }
}
