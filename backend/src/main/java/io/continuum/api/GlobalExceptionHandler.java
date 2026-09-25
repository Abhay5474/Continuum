package io.continuum.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body(e.getMessage(), 400));
    }

    /**
     * A path variable of the wrong type, or a body that is not valid JSON.
     * Neither implements {@link ErrorResponse}, so they need naming here.
     */
    @ExceptionHandler({org.springframework.beans.TypeMismatchException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> malformed(Exception e) {
        String message = e.getMessage();
        if (e instanceof org.springframework.http.converter.HttpMessageNotReadableException) {
            // Well-formed JSON of the wrong shape is a different mistake from a
            // syntax error, and saying "not valid JSON" about it sends the caller
            // hunting for a missing comma. Name the field instead.
            message = e.getCause() instanceof com.fasterxml.jackson.databind.exc.MismatchedInputException mi
                    && !mi.getPath().isEmpty()
                    ? "The field '" + fieldPath(mi) + "' has the wrong type."
                    : e.getCause() == null && e.getMessage() != null && e.getMessage().startsWith("Required request body")
                            ? "A request body is required."
                            : "The request body is not valid JSON.";
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body(message, 400));
    }

    @ExceptionHandler(io.continuum.core.engine.WorkflowIdInUseException.class)
    public ResponseEntity<Map<String, Object>> idInUse(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body(e.getMessage(), 409));
    }

    @ExceptionHandler(io.continuum.portal.RequestScope.NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body(e.getMessage(), 404));
    }

    /** Cross-tenant read attempt: the record exists but belongs to someone else. */
    @ExceptionHandler(io.continuum.portal.RequestScope.ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> forbidden(io.continuum.portal.RequestScope.ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body(e.getMessage(), 403));
    }

    /**
     * An upgrade without a settled payment, or on a deployment that cannot take
     * one. 402 rather than 403: the request is legitimate, it just is not paid.
     */
    @ExceptionHandler({io.continuum.billing.BillingService.PaymentRequiredException.class,
            io.continuum.billing.PaymentProvider.PaymentNotConfiguredException.class})
    public ResponseEntity<Map<String, Object>> paymentRequired(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(body(e.getMessage(), 402));
    }

    /**
     * Anything else.
     *
     * <p>Spring's own request errors — an unknown path, an id that is not a
     * number, malformed JSON, the wrong HTTP method — carry their real status on
     * {@link ErrorResponse}. Catching them here as {@code Exception} used to turn
     * every one into a 500, so a client's typo looked like a server crash on the
     * error-rate dashboard and in the caller's retry logic.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> generic(Exception e) {
        if (e instanceof ErrorResponse er && er.getStatusCode().is4xxClientError()) {
            int status = er.getStatusCode().value();
            // The detail is the sentence meant for the caller; getMessage() wraps
            // it as '404 NOT_FOUND "..."'.
            String detail = er.getBody().getDetail();
            return ResponseEntity.status(status).body(body(detail != null ? detail : e.getMessage(), status));
        }
        // Every 500 gets a reference that is both in the response and in the log
        // line carrying the stack trace, so "it failed" from a user can be found.
        // Before, nothing was logged here at all.
        String ref = java.util.UUID.randomUUID().toString().substring(0, 8);
        log.error("Unhandled error ref={}", ref, e);
        // A state error thrown on purpose explains itself. Anything else — a
        // database error, a null — has a message written for us, not the caller:
        // it used to hand out SQL statements and table names.
        String message = e instanceof IllegalStateException && !(e.getCause() instanceof java.sql.SQLException)
                ? e.getMessage()
                : "Something failed on our side. Nothing you sent was wrong; try again, and quote ref " + ref
                        + " if it keeps happening.";
        Map<String, Object> out = new java.util.HashMap<>(body(message, 500));
        out.put("ref", ref);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(out);
    }

    /**
     * A write that collides with a row already there — typically the same
     * request arriving twice at once. The caller's retry is what resolves it.
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> conflict(org.springframework.dao.DataIntegrityViolationException e) {
        log.warn("Write conflict: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body(
                "This conflicts with a record that was just written, probably by the same request sent twice. "
                        + "Retrying is safe.", 409));
    }

    private static String fieldPath(com.fasterxml.jackson.databind.exc.MismatchedInputException mi) {
        StringBuilder sb = new StringBuilder();
        for (var ref : mi.getPath()) {
            if (ref.getFieldName() != null) {
                sb.append(sb.isEmpty() ? "" : ".").append(ref.getFieldName());
            } else if (ref.getIndex() >= 0) {
                sb.append('[').append(ref.getIndex()).append(']');
            }
        }
        return sb.toString();
    }

    private Map<String, Object> body(String message, int status) {
        return Map.of("error", message == null ? "Unexpected error" : message,
                "status", status, "timestamp", Instant.now().toString());
    }
}
