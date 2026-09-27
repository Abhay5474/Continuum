package io.continuum.api;

import io.continuum.features.FeatureCatalog;
import io.continuum.portal.PortalAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Every feature under its real name, with its state for the signed-in account. */
@RestController
@RequestMapping("/api/portal/developer/features")
public class FeatureController {

    private final FeatureCatalog catalog;

    public FeatureController(FeatureCatalog catalog) {
        this.catalog = catalog;
    }

    private static String dev(HttpServletRequest req) {
        return (String) req.getAttribute(PortalAuthFilter.DEVELOPER_ID_ATTRIBUTE);
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest req) {
        return catalog.list(dev(req));
    }

    @PutMapping("/{key}")
    public Map<String, Object> set(@PathVariable String key, @RequestBody Toggle body, HttpServletRequest req) {
        return catalog.set(dev(req), key, Boolean.TRUE.equals(body.enabled()));
    }

    public record Toggle(Boolean enabled) {
    }
}
