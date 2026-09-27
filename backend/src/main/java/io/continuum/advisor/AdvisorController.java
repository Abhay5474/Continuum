package io.continuum.advisor;

import io.continuum.portal.PortalAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Recommendations for the signed-in account's configuration. */
@RestController
@RequestMapping("/api/portal/developer/advice")
public class AdvisorController {

    private final ConfigAdvisor advisor;

    public AdvisorController(ConfigAdvisor advisor) {
        this.advisor = advisor;
    }

    @GetMapping
    public Map<String, Object> advice(HttpServletRequest req) {
        return advisor.advise((String) req.getAttribute(PortalAuthFilter.DEVELOPER_ID_ATTRIBUTE));
    }
}
