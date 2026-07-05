package io.continuum.api;

import io.continuum.mmu.ContextMMU;
import io.continuum.persistence.entity.MmuStubEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** V7 — read-only profiler APIs for the Context Memory Profiler UI. */
@RestController
@RequestMapping("/api/mmu")
public class MmuController {

    private final ContextMMU mmu;

    public MmuController(ContextMMU mmu) {
        this.mmu = mmu;
    }

    @GetMapping("/profile")
    public Map<String, Object> profile(@RequestParam(required = false) String developerId) {
        return mmu.profile(developerId);
    }

    @GetMapping("/stubs")
    public List<MmuStubEntity> stubs(@RequestParam String developerId) {
        return mmu.recentStubs(developerId);
    }
}
