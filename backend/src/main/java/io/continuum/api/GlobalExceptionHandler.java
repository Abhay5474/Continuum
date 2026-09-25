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
        String message = e instanceof org.springframework.http.converter.HttpMessageNotReadableException
                ? "The request body is not valid JSON."
                : e.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body(message, 400));
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
            return ResponseEntity.status(status).body(body(e.getMessage(), status));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body(e.getMessage(), 500));
    }

    private Map<String, Object> body(String message, int status) {
        return Map.of("error", message == null ? "Unexpected error" : message,
                "status", status, "timestamp", Instant.now().toString());
    }
}
