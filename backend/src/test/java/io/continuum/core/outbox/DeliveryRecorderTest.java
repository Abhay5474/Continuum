package io.continuum.core.outbox;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeliveryRecorderTest {

    @Test
    void keepsTheRecentHistoryBoundedButCountsEverything() {
        DeliveryRecorder r = new DeliveryRecorder();
        int n = DeliveryRecorder.KEEP + 250;
        for (int i = 0; i < n; i++) {
            r.record("email", "wf-" + i + ":1", "{}", "wf-" + i);
        }
        List<DeliveryRecorder.Delivery> kept = r.deliveries();
        assertEquals(DeliveryRecorder.KEEP, kept.size());
        assertEquals("wf-250:1", kept.get(0).idempotencyKey());
        assertEquals("wf-" + (n - 1), kept.get(kept.size() - 1).workflowId());
        assertEquals(n, r.totalDeliveries());
        assertEquals(List.of(), r.duplicates());
    }

    @Test
    void aKeyDeliveredTwiceIsReportedOnce() {
        DeliveryRecorder r = new DeliveryRecorder();
        r.record("payment", "wf-1:3", "{}", "wf-1");
        r.record("payment", "wf-1:3", "{}", "wf-1");
        r.record("payment", "wf-1:3", "{}", "wf-1");
        assertEquals(List.of("wf-1:3"), r.duplicates());
        assertEquals(3, r.deliveries().get(2).deliveryCount());
    }
}
