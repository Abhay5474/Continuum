package io.continuum.declarative;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The built-in stand-in services that let a workflow run with nothing else deployed. */
class SandboxServicesTest {

    @Test
    void recognisesOnlyTheSandboxScheme() {
        assertTrue(SandboxServices.handles("sandbox://payments/charge"));
        assertTrue(SandboxServices.handles("SANDBOX://x"));
        assertFalse(SandboxServices.handles("https://api.example.com/charge"));
        assertFalse(SandboxServices.handles(null));
    }

    @Test
    void answersDeterministicallyForTheSameIdempotencyKey() throws Exception {
        var a = SandboxServices.call("sandbox://payments/charge", "POST", Map.of("amount", 499), "wf-1:3", 1);
        var b = SandboxServices.call("sandbox://payments/charge", "POST", Map.of("amount", 499), "wf-1:3", 2);
        var c = SandboxServices.call("sandbox://payments/charge", "POST", Map.of("amount", 499), "wf-2:3", 1);
        assertEquals(200, a.status());
        assertEquals(a.body().get("paymentId"), b.body().get("paymentId"));
        assertNotEquals(a.body().get("paymentId"), c.body().get("paymentId"));
        assertEquals(499, a.body().get("amount"));
    }

    @Test
    void failTimesFailsTheFirstAttemptsThenSucceeds() throws Exception {
        Map<String, Object> body = Map.of("failTimes", 2);
        assertEquals(503, SandboxServices.call("sandbox://payments/charge", "POST", body, "k", 1).status());
        assertEquals(503, SandboxServices.call("sandbox://payments/charge", "POST", body, "k", 2).status());
        assertEquals(200, SandboxServices.call("sandbox://payments/charge", "POST", body, "k", 3).status());
    }

    @Test
    void failDeclinesWithoutRetry() throws Exception {
        var r = SandboxServices.call("sandbox://payments/charge", "POST", Map.of("fail", true), "k", 1);
        assertEquals(422, r.status());
    }

    @Test
    void queryParametersReachTheAnswer() throws Exception {
        var r = SandboxServices.call("sandbox://orders/verify?orderId=BC-7", "GET", null, "k", 1);
        assertEquals("BC-7", r.body().get("orderId"));
        assertEquals(true, r.body().get("valid"));
    }

    @Test
    void definitionTargetsNameTheHostsAStepCalls() {
        WorkflowSpec s = new WorkflowSpec();
        WorkflowSpec.Step a = new WorkflowSpec.Step();
        WorkflowSpec.Call ca = new WorkflowSpec.Call();
        ca.setUrl("sandbox://stock/reserve");
        a.setId("a");
        a.setCall(ca);
        WorkflowSpec.Step b = new WorkflowSpec.Step();
        WorkflowSpec.Call cb = new WorkflowSpec.Call();
        cb.setUrl("http://localhost:5050/svc/payments/refund");
        b.setId("b");
        b.setCall(cb);
        s.setSteps(List.of(a, b));
        assertEquals(List.of("sandbox", "localhost:5050"), DefinitionService.targets(s));
    }
}
