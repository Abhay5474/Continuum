package io.continuum.registry;

/**
 * Capability + pricing metadata for a model, used by capability-aware routing
 * (e.g. only route an image request to {@code vision=true} models) and by cost
 * scoring.
 */
public record ModelCapabilities(
        int contextWindow,
        boolean vision,
        boolean tools,
        boolean json,
        double costInputPer1k,
        double costOutputPer1k,
        String tier) {   // "flash" | "pro" | "instant" | "versatile" ... relative strength hint

    public static ModelCapabilities basic(int contextWindow, String tier,
                                          double costInput, double costOutput) {
        return new ModelCapabilities(contextWindow, false, true, true, costInput, costOutput, tier);
    }
}
