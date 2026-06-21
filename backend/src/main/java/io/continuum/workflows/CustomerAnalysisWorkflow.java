package io.continuum.workflows;

import io.continuum.activities.*;
import io.continuum.core.workflow.ActivityOptions;
import io.continuum.core.workflow.Workflow;
import io.continuum.core.workflow.WorkflowContext;
import org.springframework.stereotype.Component;

/**
 * Phase 4 — a complete agentic workflow built entirely from durable activities:
 *
 * <pre>
 *   fetch customer → AI risk analysis → AI report → send email → update database
 * </pre>
 *
 * Every step is checkpointed. If a worker dies after the AI analysis but before
 * the email, recovery replays the history, returns the already-recorded AI
 * answer (no second model call, no extra cost) and resumes at the email step.
 * The email and DB write are idempotent, so they can never double-fire.
 */
@Component
public class CustomerAnalysisWorkflow implements Workflow {

    public static final String TYPE = "CustomerAnalysis";

    private static final String ANALYST_SYSTEM =
            "You are a meticulous risk analyst. Given customer data, respond with a concise risk "
                    + "assessment (LOW/MEDIUM/HIGH) and one sentence of justification.";
    private static final String WRITER_SYSTEM =
            "You are a business writer. Turn the analysis into a short, professional customer report.";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Object execute(WorkflowContext ctx) {
        Input in = ctx.input(Input.class);

        ActivityOptions ioOpts = ActivityOptions.defaults().maxAttempts(5).timeoutSeconds(15);
        ActivityOptions llmOpts = ActivityOptions.defaults().maxAttempts(3).timeoutSeconds(45);

        // 1. Fetch customer (durable read)
        FetchCustomerActivity.Customer customer = ctx.executeActivity(
                FetchCustomerActivity.TYPE,
                new FetchCustomerActivity.Input(in.customerId()),
                ioOpts, FetchCustomerActivity.Customer.class);

        // 2. AI risk analysis (recorded; never re-run on replay)
        LlmActivity.Output analysis = ctx.executeActivity(
                LlmActivity.TYPE,
                new LlmActivity.Input(ANALYST_SYSTEM,
                        "Customer: " + customer.name() + " (tier " + customer.tier() + ").\n"
                                + "History: " + customer.history() + "\nAssess risk.",
                        null, 512, 0.2),
                llmOpts, LlmActivity.Output.class);

        // 3. AI report generation
        LlmActivity.Output report = ctx.executeActivity(
                LlmActivity.TYPE,
                new LlmActivity.Input(WRITER_SYSTEM,
                        "Write a customer report for " + customer.name()
                                + " based on this analysis:\n" + analysis.content(),
                        null, 768, 0.3),
                llmOpts, LlmActivity.Output.class);

        // 4. Email the report (idempotent side effect via outbox)
        ctx.executeActivity(
                EmailActivity.TYPE,
                new EmailActivity.Input(customer.email(),
                        "Your account review", report.content()),
                ioOpts, EmailActivity.Output.class);

        // 5. Persist the outcome
        ctx.executeActivity(
                UpdateDatabaseActivity.TYPE,
                new UpdateDatabaseActivity.Input(customer.id(), "REVIEWED"),
                ioOpts, UpdateDatabaseActivity.Output.class);

        return new Output(customer.id(), customer.name(), analysis.content(), report.content(),
                analysis.provider(), true);
    }

    public record Input(String customerId, String notifyEmail) {
    }

    public record Output(String customerId, String customerName, String riskAnalysis,
                         String report, String analysisProvider, boolean emailSent) {
    }
}
