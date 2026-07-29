package io.continuum.api;

import io.continuum.counterfactual.CounterfactualService;
import io.continuum.portal.PortalAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

/** Counterfactual replay. Tenant-scoped; OFF by default. */
@RestController
@RequestMapping("/api/portal/developer/counterfactual")
public class CounterfactualController {

    private final CounterfactualService counterfactual;

    public CounterfactualController(CounterfactualService counterfactual) {
        this.counterfactual = counterfactual;
    }

    private String dev(HttpServletRequest req) {
        return (String) req.getAttribute(PortalAuthFilter.DEVELOPER_ID_ATTRIBUTE);
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest req) {
        return counterfactual.status(dev(req));
    }

    @GetMapping("/arms")
    public Set<String> arms(HttpServletRequest req) {
        return counterfactual.arms(dev(req));
    }

    @PutMapping("/settings")
    public Map<String, Object> settings(HttpServletRequest req, @RequestBody Settings body) {
        return counterfactual.configure(dev(req), body.enabled());
    }

    /** Replays logged traffic against a candidate policy. Reads only; runs nothing. */
    @PostMapping("/evaluate")
    public Map<String, Object> evaluate(HttpServletRequest req, @RequestBody Evaluate body) {
        return counterfactual.evaluate(dev(req), body.alwaysArm(), body.at(),
                body.below(), body.above(), body.limit());
    }

    public record Settings(Boolean enabled) {
    }

    public record Evaluate(String alwaysArm, Double at, String below, String above, Integer limit) {
    }
}
