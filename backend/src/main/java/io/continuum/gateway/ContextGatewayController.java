package io.continuum.gateway;

import io.continuum.context.ContextTransformService;
import io.continuum.context.RenderBudget;
import io.continuum.developer.ApiKeyAuthenticationFilter;
import io.continuum.persistence.entity.DeveloperEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * The context layer, for an external application.
 *
 * <p>What a developer integrates against. They post the data they already have —
 * a workbook their customer uploaded, the logs from a failing job, an email
 * thread — and receive a canonical representation their own prompt can use,
 * with the provenance and the token cost attached.
 *
 * <p>Deliberately separate from the pipeline route. A pipeline runs a model; this
 * does not, and pretending otherwise would mean a developer who only wants the
 * transformation has to configure a model they will not call.
 */
@RestController
public class ContextGatewayController {

    private final ContextTransformService service;

    public ContextGatewayController(ContextTransformService service) {
        this.service = service;
    }

    @PostMapping(value = "/api/gateway/context/transform", consumes = "multipart/form-data")
    public ResponseEntity<?> transform(@RequestPart("file") MultipartFile file,
                                       @RequestParam(defaultValue = "standard") String budget,
                                       HttpServletRequest http) {
        DeveloperEntity developer =
                (DeveloperEntity) http.getAttribute(ApiKeyAuthenticationFilter.DEVELOPER_ATTRIBUTE);
        if (developer == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_api_key", "message", "Authentication required."));
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "invalid_request", "message", "No file was uploaded."));
        }
        try {
            return ResponseEntity.ok(service.transformAndRecord(developer.getId(), file.getBytes(),
                    file.getOriginalFilename(),
                    RenderBudget.named(budget)).describe());
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "invalid_request",
                            "message", "The upload could not be read."));
        }
    }
}
