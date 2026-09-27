package io.continuum.api;

import io.continuum.retention.RetentionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Data retention, for the operator: what is kept and for how long, what the
 * last passes removed, and a button to run one now. Under {@code /api/admin},
 * so only the operator reaches it.
 */
@RestController
@RequestMapping("/api/admin/retention")
public class RetentionController {

    private final RetentionService retention;

    public RetentionController(RetentionService retention) {
        this.retention = retention;
    }

    @GetMapping
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>(retention.settings());
        out.put("runs", retention.recentRuns(10));
        return out;
    }

    @PostMapping("/run")
    public Map<String, Object> runNow() {
        Map<String, Integer> deleted = retention.run("MANUAL");
        if (deleted == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A retention pass is already running.");
        }
        return Map.of("deleted", deleted);
    }
}
