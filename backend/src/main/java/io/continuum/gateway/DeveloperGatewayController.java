package io.continuum.gateway;

import io.continuum.developer.ApiKeyAuthenticationFilter;
import io.continuum.persistence.entity.DeveloperEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Feature 1 — the public Developer Gateway. Authenticated by the API-key filter;
 * the developer is resolved from the request attribute.
 *
 * Developers integrate once against {@code /api/gateway/chat} (or the
 * OpenAI-shaped {@code /v1/chat/completions} alias) and Continuum handles
 * routing, model selection, failover, BYO-key execution and observability.
 */
@RestController
public class DeveloperGatewayController {

    private final GatewayService gateway;

    public DeveloperGatewayController(GatewayService gateway) {
        this.gateway = gateway;
    }

    /**
     * Continuum's own shape, for the console.
     *
     * <p>{@code /v1/chat/completions} used to be aliased here and is not any
     * more: it now belongs to {@code OpenAiCompatController}, which answers in
     * the format the path promises. Two controllers claiming one path is an
     * ambiguous-mapping 500 on every request, so this alias could not simply be
     * left in place beside the new one.
     */
    @PostMapping("/api/gateway/chat")
    public ResponseEntity<?> chat(@RequestBody GatewayDtos.ChatRequest request, HttpServletRequest http) {
        DeveloperEntity developer = (DeveloperEntity) http.getAttribute(ApiKeyAuthenticationFilter.DEVELOPER_ATTRIBUTE);
        if (developer == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_api_key", "message", "Authentication required."));
        }
        try {
            return ResponseEntity.ok(gateway.chat(developer.getId(), request));
        } catch (io.continuum.admission.CostAdmissionService.CostLimitedException e) {
            // Same 429 as any rate limit, but the body says which resource ran
            // out — a caller told only "too many requests" when they are in fact
            // over their token allowance will retry with the same huge prompt.
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(e.decision().retryAfterSeconds()))
                    .body(Map.of("error", "cost_limited", "message", e.getMessage(),
                            "boundBy", e.decision().boundBy().name(),
                            "retryAfterSeconds", e.decision().retryAfterSeconds()));
        } catch (io.continuum.scheduling.SchedulerService.DeadlineUnreachableException e) {
            // Not overload — the caller's own deadline. 422: the request was
            // understood and cannot be satisfied as stated, and retrying it
            // unchanged will fail the same way.
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("error", "deadline_unreachable", "message", e.getMessage()));
        } catch (io.continuum.admission.AdmissionService.SheddedException e) {
            // Deliberately refused, not broken. 429 with Retry-After so a client
            // backs off instead of retrying immediately and deepening the
            // overload it just hit.
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "1")
                    .body(Map.of("error", "capacity", "message", e.getMessage(),
                            "criticality", e.criticality().name(),
                            "inferredLimit", e.limit()));
        } catch (io.continuum.billing.BillingService.QuotaExceededException e) {
            // Over the monthly plan quota — 402 Payment Required.
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .body(Map.of("error", "quota_exceeded", "message", e.getMessage()));
        } catch (io.continuum.firewall.PromptFirewallService.BlockedException e) {
            // V8 Prompt Firewall blocked the request (e.g. prompt injection).
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "request_blocked", "message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_request", "message", e.getMessage()));
        } catch (GatewayService.GatewayException e) {
            // Upstream providers failed — generic 502, no provider internals leaked.
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "upstream_unavailable",
                            "message", "All upstream AI providers are currently unavailable. Please retry."));
        }
    }
}
