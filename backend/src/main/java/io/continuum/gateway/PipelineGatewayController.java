package io.continuum.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.api.PipelineController;
import io.continuum.developer.ApiKeyAuthenticationFilter;
import io.continuum.persistence.entity.DeveloperEntity;
import io.continuum.specialist.PipelineService;
import io.continuum.specialist.SpecialistConnectionService;
import io.continuum.tool.UploadedInput;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * What an external application actually calls.
 *
 * <p>It sends an image and a question, and receives advice. It is never told
 * that a detector ran, what the detector was called, or who hosts it — that
 * concealment is the point of the layer, and it is what lets a developer swap
 * Roboflow for their own endpoint without their customers noticing.
 *
 * <p>What it <em>is</em> given is a trace id and the chain itself, so the
 * application can show its own user the work: image received, specialist
 * analysis, context enrichment, model reasoning, answer. An application that
 * wants to stay quiet ignores the field; one that wants to show its user why the
 * answer is trustworthy has everything it needs without a second round trip.
 */
@RestController
public class PipelineGatewayController {

    private final PipelineService pipelines;
    private final ObjectMapper mapper;

    public PipelineGatewayController(PipelineService pipelines, ObjectMapper mapper) {
        this.pipelines = pipelines;
        this.mapper = mapper;
    }

    @PostMapping("/api/gateway/pipeline/{name}")
    public ResponseEntity<?> run(@PathVariable String name,
                                 @RequestBody PipelineController.RunRequest body,
                                 HttpServletRequest http) {
        return execute(name, body == null ? null : body.input(),
                body == null ? null : body.prompt(), http);
    }

    /**
     * The same pipeline, called with a file.
     *
     * <p>An application that already holds a PDF or a photo can post it
     * directly instead of base64-encoding it into JSON first. It is the same
     * run, the same trace and the same response shape — only the way the bytes
     * arrive differs.
     */
    @PostMapping(value = "/api/gateway/pipeline/{name}/upload", consumes = "multipart/form-data")
    public ResponseEntity<?> runUpload(@PathVariable String name,
                                       @RequestPart("file") MultipartFile file,
                                       @RequestParam(required = false) String prompt,
                                       @RequestParam(required = false) String input,
                                       HttpServletRequest http) {
        Map<String, Object> built;
        try {
            built = UploadedInput.from(file, input, mapper);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "invalid_upload", "message", e.getMessage()));
        }
        return execute(name, built, prompt, http);
    }

    private ResponseEntity<?> execute(String name, Map<String, Object> input, String prompt,
                                      HttpServletRequest http) {
        DeveloperEntity developer =
                (DeveloperEntity) http.getAttribute(ApiKeyAuthenticationFilter.DEVELOPER_ATTRIBUTE);
        if (developer == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_api_key", "message", "Authentication required."));
        }
        try {
            PipelineService.Run r = pipelines.run(developer.getId(), name, input, prompt);
            return ResponseEntity.ok(PipelineController.describe(r));
        } catch (SpecialistConnectionService.InvalidConnectionException e) {
            // Unknown or disabled pipeline, or a misconfiguration the developer
            // can fix. The message is theirs, written for them.
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "invalid_pipeline", "message", e.getMessage()));
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
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .body(Map.of("error", "quota_exceeded", "message", e.getMessage()));
        } catch (io.continuum.firewall.PromptFirewallService.BlockedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "request_blocked", "message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "invalid_request", "message", e.getMessage()));
        } catch (GatewayService.GatewayException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "upstream_unavailable",
                            "message", "All upstream AI providers are currently unavailable. Please retry."));
        }
    }
}
