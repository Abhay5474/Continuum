package io.continuum.api;

import io.continuum.mmu.ContextMMU;
import io.continuum.persistence.entity.MmuStubEntity;
import io.continuum.portal.RequestScope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Read-only context-profiler APIs for the console.
 *
 * <p>Tenant-scoped from the session; the old {@code ?developerId=} parameter let
 * any caller read another tenant's context stubs.
 */
@RestController
@RequestMapping("/api/mmu")
public class MmuController {

    private final ContextMMU mmu;

    public MmuController(ContextMMU mmu) {
        this.mmu = mmu;
    }

    @GetMapping("/profile")
    public Map<String, Object> profile(HttpServletRequest req) {
        return mmu.profile(RequestScope.developerId(req));
    }

    @GetMapping("/stubs")
    public List<MmuStubEntity> stubs(HttpServletRequest req) {
        String developerId = RequestScope.developerId(req);
        // Stubs are per-developer; an operator has no single tenant to scope to.
        return developerId == null ? List.of() : mmu.recentStubs(developerId);
    }
}
