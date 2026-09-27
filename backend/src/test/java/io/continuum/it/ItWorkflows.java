package io.continuum.it;

import io.continuum.core.activity.Activity;
import io.continuum.core.activity.ActivityContext;
import io.continuum.core.workflow.ActivityOptions;
import io.continuum.core.workflow.Workflow;
import io.continuum.core.workflow.WorkflowContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Workflows and activities that exist only for the integration tests. */
@TestConfiguration
public class ItWorkflows {

    /** How many times each activity body has run, by idempotency key. */
    public static final Map<String, AtomicInteger> RUNS = new ConcurrentHashMap<>();

    /**
     * Held open by {@code it.gate} until a test releases it, so a test can
     * decide exactly when a "slow" attempt finishes.
     */
    public static final Map<String, CountDownLatch> GATES = new ConcurrentHashMap<>();

    public static int runs(String key) {
        AtomicInteger n = RUNS.get(key);
        return n == null ? 0 : n.get();
    }

    public record Echo(String value, int timeoutSeconds) {
    }

    /** Two steps, the second fed by the first, then a notification through the outbox. */
    @Bean
    Workflow itTwoStep() {
        return new Workflow() {
            @Override
            public String type() {
                return "it.twoStep";
            }

            @Override
            public Object execute(WorkflowContext ctx) {
                Echo in = ctx.input(Echo.class);
                ActivityOptions opts = ActivityOptions.defaults().maxAttempts(3).timeoutSeconds(in.timeoutSeconds());
                Map<?, ?> first = ctx.executeActivity("it.echo", Map.of("value", in.value()), opts, Map.class);
                Map<?, ?> second = ctx.executeActivity("it.notify", Map.of("value", first.get("value") + "!"), opts, Map.class);
                return Map.of("result", second.get("value"));
            }
        };
    }

    /** One step that waits on a gate, for lease and fencing scenarios. */
    @Bean
    Workflow itGated() {
        return new Workflow() {
            @Override
            public String type() {
                return "it.gated";
            }

            @Override
            public Object execute(WorkflowContext ctx) {
                Echo in = ctx.input(Echo.class);
                ActivityOptions opts = ActivityOptions.defaults().maxAttempts(3).timeoutSeconds(in.timeoutSeconds());
                return ctx.executeActivity("it.gate", Map.of("value", in.value()), opts, Map.class);
            }
        };
    }

    @Bean
    Activity itEcho() {
        return new Activity() {
            @Override
            public String type() {
                return "it.echo";
            }

            @Override
            public Object execute(String inputJson, ActivityContext ctx) {
                RUNS.computeIfAbsent(ctx.idempotencyKey(), k -> new AtomicInteger()).incrementAndGet();
                return ctx.input(inputJson, Map.class);
            }
        };
    }

    @Bean
    Activity itNotify() {
        return new Activity() {
            @Override
            public String type() {
                return "it.notify";
            }

            @Override
            public Object execute(String inputJson, ActivityContext ctx) {
                RUNS.computeIfAbsent(ctx.idempotencyKey(), k -> new AtomicInteger()).incrementAndGet();
                Map<?, ?> in = ctx.input(inputJson, Map.class);
                ctx.enqueueOutbox("notification", "it.done", in);
                return in;
            }
        };
    }

    /**
     * Blocks until its gate opens (or 60s pass), then returns which attempt it was.
     * Honours interruption, as a well-behaved body should.
     */
    @Bean
    Activity itGate() {
        return new Activity() {
            @Override
            public String type() {
                return "it.gate";
            }

            @Override
            public Object execute(String inputJson, ActivityContext ctx) throws Exception {
                int run = RUNS.computeIfAbsent(ctx.idempotencyKey(), k -> new AtomicInteger()).incrementAndGet();
                Map<?, ?> in = ctx.input(inputJson, Map.class);
                CountDownLatch gate = GATES.computeIfAbsent(ctx.workflowId(), k -> new CountDownLatch(1));
                gate.await(60, TimeUnit.SECONDS);
                return Map.of("value", in.get("value"), "run", run);
            }
        };
    }
}
