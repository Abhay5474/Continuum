package io.continuum.dag.activities;

import io.continuum.core.activity.Activity;
import io.continuum.core.activity.ActivityContext;
import io.continuum.dag.DagModels.SolverOutput;
import io.continuum.provider.ProviderRouter;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.LlmResponse;
import io.continuum.provider.model.Message;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V6 Step 2 — a solver node: generates one candidate reasoning path for one
 * claim. Runs as a standard activity picked from the Postgres task queue, so a
 * fan-out of N solvers executes concurrently on the existing worker pool.
 */
@Component
public class DagSolverActivity implements Activity {

    public static final String TYPE = "dag.solve";
    private static final Pattern CONFIDENCE = Pattern.compile("confidence[:\\s]+([01]?\\.\\d+)",
            Pattern.CASE_INSENSITIVE);

    private final ProviderRouter router;

    public DagSolverActivity(ProviderRouter router) {
        this.router = router;
    }

    public record Input(int claimId, String statement, String task) {
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Object execute(String inputJson, ActivityContext ctx) {
        Input in = ctx.input(inputJson, Input.class);
        LlmResponse resp = router.complete(LlmRequest.of(List.of(
                Message.system("You are a solver in a verification pipeline. Reason step by step "
                        + "about the claim, end with a one-sentence conclusion, then a line "
                        + "'Confidence: 0.NN' with your honest confidence."),
                Message.user("Task context: " + in.task() + "\n\nClaim to solve/prove: "
                        + in.statement()))));
        String content = resp.content() == null ? "" : resp.content();
        return new SolverOutput(in.claimId(), content, conclusionOf(content),
                confidenceOf(content), resp.provider(), resp.model(),
                resp.promptTokens() + resp.completionTokens(),
                router.estimateCost(resp.provider(), resp.model(),
                        resp.promptTokens(), resp.completionTokens()));
    }

    private static String conclusionOf(String content) {
        String stripped = content.replaceAll("(?i)confidence[:\\s]+[01]?\\.\\d+", "").trim();
        String[] sentences = stripped.split("(?<=[.!?])\\s+");
        return sentences.length == 0 ? stripped : sentences[sentences.length - 1].trim();
    }

    private static double confidenceOf(String content) {
        Matcher m = CONFIDENCE.matcher(content);
        double last = 0.7; // neutral-leaning default when the model reports nothing
        while (m.find()) {
            try {
                last = Double.parseDouble(m.group(1));
            } catch (NumberFormatException ignored) {
                // keep previous
            }
        }
        return Math.max(0.05, Math.min(0.95, last));
    }
}
