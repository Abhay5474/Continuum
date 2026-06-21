package io.continuum.aichaos;

import io.continuum.aichaos.injectors.HallucinationInjector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HallucinationInjectorTest {

    private final HallucinationInjector injector = new HallucinationInjector();

    @Test
    void reversesDecisionInSinglePass() {
        String out = injector.corrupt("Assessment: LOW RISK. Recommend approve.");
        // Single pass must not flip back: low->high and approve->reject, exactly once each.
        assertTrue(out.toLowerCase().contains("high risk"), out);
        assertFalse(out.toLowerCase().contains("low risk"), out);
        assertTrue(out.toLowerCase().contains("reject"), out);
        assertTrue(out.startsWith("[HALLUCINATED]"));
    }
}
