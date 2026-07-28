package io.continuum.api;

import io.continuum.loops.LoopGuardService;
import io.continuum.portal.PortalAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Agent loop detection. Tenant-scoped; OFF by default. */
@RestController
@RequestMapping("/api/portal/developer/loops")
public class LoopController {

    private final LoopGuardService loops;

    public LoopController(LoopGuardService loops) {
        this.loops = loops;
    }

    private String dev(HttpServletRequest req) {
        return (String) req.getAttribute(PortalAuthFilter.DEVELOPER_ID_ATTRIBUTE);
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest req) {
        return loops.status(dev(req));
    }

    @PutMapping("/settings")
    public Map<String, Object> settings(HttpServletRequest req, @RequestBody Settings body) {
        return loops.configure(dev(req), body.enabled(), body.mode());
    }

    /**
     * Inspects a sequence of steps.
     *
     * <p>Exposed so an agent framework Continuum does not run can still use the
     * detector — the loop it needs to catch is in the caller's control flow, not
     * in Continuum's.
     */
    @PostMapping("/inspect")
    public ResponseEntity<Map<String, Object>> inspect(HttpServletRequest req,
                                                       @RequestBody Inspect body) {
        try {
            var v = loops.check(dev(req), body.workflowId(), body.steps(), body.progress());
            return ResponseEntity.ok(v == null
                    ? Map.of("enabled", false, "looping", false)
                    : v.describe());
        } catch (LoopGuardService.LoopHaltedException e) {
            // In HALT mode this is the answer, not a server fault: the caller
            // asked whether to continue and the answer is no. 409 so a client
            // that only checks the status code still stops.
            Map<String, Object> out = new java.util.LinkedHashMap<>(e.verdict().describe());
            out.put("halted", true);
            return ResponseEntity.status(409).body(out);
        }
    }

    @DeleteMapping
    public Map<String, Object> clear(HttpServletRequest req) {
        return loops.clear(dev(req));
    }

    public record Settings(Boolean enabled, String mode) {
    }

    public record Inspect(String workflowId, List<String> steps, List<Boolean> progress) {
    }
}
