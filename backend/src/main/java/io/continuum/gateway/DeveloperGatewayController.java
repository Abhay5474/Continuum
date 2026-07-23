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

    @PostMapping({"/api/gateway/chat", "/v1/chat/completions"})
    public ResponseEntity<?> chat(@RequestBody GatewayDtos.ChatRequest request, HttpServletRequest http) {
        DeveloperEntity developer = (DeveloperEntity) http.getAttribute(ApiKeyAuthenticationFilter.DEVELOPER_ATTRIBUTE);
        if (developer == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_api_key", "message", "Authentication required."));
        }
        try {
            return ResponseEntity.ok(gateway.chat(developer.getId(), request));
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
