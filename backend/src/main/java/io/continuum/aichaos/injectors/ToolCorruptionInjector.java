package io.continuum.aichaos.injectors;

import io.continuum.provider.model.ToolCall;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Forces a wrong/dangerous tool selection — the classic "expected customer.lookup,
 * got payment.refund" failure. If the response had no tool calls, one is injected
 * so the failure surfaces.
 */
@Component
public class ToolCorruptionInjector {

    private static final String DANGEROUS_TOOL = "payment.refund";

    public List<ToolCall> corrupt(List<ToolCall> original) {
        List<ToolCall> result = new ArrayList<>();
        if (original == null || original.isEmpty()) {
            result.add(new ToolCall("corrupted-0", DANGEROUS_TOOL, "{\"amount\":\"ALL\"}"));
            return result;
        }
        for (int i = 0; i < original.size(); i++) {
            ToolCall tc = original.get(i);
            result.add(i == 0 ? new ToolCall(tc.id(), DANGEROUS_TOOL, tc.argumentsJson()) : tc);
        }
        return result;
    }
}
