package io.continuum.mmu;

import io.continuum.persistence.entity.DeveloperAuthEntity;
import io.continuum.persistence.entity.MmuStubEntity;
import io.continuum.persistence.entity.MmuStubEventEntity;
import io.continuum.persistence.repository.DeveloperAuthRepository;
import io.continuum.persistence.repository.MmuRequestMetricRepository;
import io.continuum.persistence.repository.MmuStubEventRepository;
import io.continuum.persistence.repository.MmuStubRepository;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.LlmResponse;
import io.continuum.provider.model.Message;
import io.continuum.provider.model.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The full MMU pipeline with an in-memory L2/L3: eviction to semantic stubs
 * within the L1 budget, predictive prefetching, page-fault interception with
 * lazy materialization, coherence (base ⊕ deltas), dirty write-behind, and the
 * off-by-default gate.
 */
class ContextMmuTest {

    private static final int BUDGET = 500; // small budget so tests evict readily

    private final DeveloperAuthRepository devAuth = mock(DeveloperAuthRepository.class);
    private final MmuStubRepository stubs = mock(MmuStubRepository.class);
    private final MmuStubEventRepository stubEvents = mock(MmuStubEventRepository.class);
    private final MmuRequestMetricRepository metrics = mock(MmuRequestMetricRepository.class);
    // Working set is off unless a tenant turns it on, so an empty settings
    // repository is the default the existing assertions were written against.
    private final io.continuum.persistence.repository.MmuSettingRepository mmuSettings =
            mock(io.continuum.persistence.repository.MmuSettingRepository.class);
    private final ContextMMU mmu =
            new ContextMMU(devAuth, stubs, stubEvents, metrics, BUDGET, mmuSettings);

    private final Map<String, MmuStubEntity> stubStore = new HashMap<>();
    private final Map<String, List<MmuStubEventEntity>> eventStore = new HashMap<>();

    @BeforeEach
    void wireInMemoryStores() {
        DeveloperAuthEntity on = new DeveloperAuthEntity("dev-1", "hash");
        on.setV7MmuEnabled(true);
        when(devAuth.findById("dev-1")).thenReturn(Optional.of(on));

        when(stubs.findById(anyString())).thenAnswer(inv ->
                Optional.ofNullable(stubStore.get(inv.getArgument(0, String.class))));
        when(stubs.save(any(MmuStubEntity.class))).thenAnswer(inv -> {
            MmuStubEntity e = inv.getArgument(0);
            stubStore.put(e.getStubId(), e);
            return e;
        });
        when(stubEvents.save(any(MmuStubEventEntity.class))).thenAnswer(inv -> {
            MmuStubEventEntity e = inv.getArgument(0);
            eventStore.computeIfAbsent(e.getStubId(), k -> new ArrayList<>()).add(e);
            return e;
        });
        when(stubEvents.findByStubIdOrderBySeqAsc(anyString())).thenAnswer(inv ->
                new ArrayList<>(eventStore.getOrDefault(inv.getArgument(0, String.class), List.of())));
        when(stubEvents.countByStubId(anyString())).thenAnswer(inv ->
                eventStore.getOrDefault(inv.getArgument(0, String.class), List.of()).size());
        when(metrics.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private LlmRequest longConversation(String newestUserPrompt) {
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.system("You are a helpful assistant."));
        for (int i = 0; i < 10; i++) {
            msgs.add(Message.user("Earlier discussion " + i + ": we debated OAuth versus JWT "
                    + "session tokens for the payments service authentication design at length, "
                    + "covering token rotation, revocation lists and gateway integration tradeoffs."));
            msgs.add(Message.assistant("Analysis " + i + ": after weighing revocation complexity, "
                    + "the decision was approved to use JWT with short expiry for the payments "
                    + "service, with refresh tokens handled at the gateway layer."));
        }
        msgs.add(Message.user(newestUserPrompt));
        return LlmRequest.of(msgs);
    }

    @Test
    void offPathIsNullAndUntouched() {
        when(devAuth.findById("dev-2")).thenReturn(Optional.empty());
        assertNull(mmu.open("dev-2", longConversation("hello")), "unknown developer ⇒ legacy path");
        assertNull(mmu.open(null, longConversation("hello")), "null developer ⇒ legacy path");
        DeveloperAuthEntity off = new DeveloperAuthEntity("dev-3", "h");
        when(devAuth.findById("dev-3")).thenReturn(Optional.of(off));
        assertNull(mmu.open("dev-3", longConversation("hello")), "OFF by default ⇒ legacy path");
        verifyNoInteractions(stubs);
    }

    @Test
    void evictionBoundsL1AndCreatesDeterministicSemanticStubs() {
        var session = mmu.open("dev-1", longConversation("What's the weather like?"));
        assertNotNull(session);
        LlmRequest v = session.request();

        int originalTokens = longConversation("What's the weather like?").messages().stream()
                .mapToInt(m -> Math.max(1, m.content().length() / 4)).sum();
        int sentTokens = v.messages().stream()
                .mapToInt(m -> Math.max(1, m.content().length() / 4)).sum();
        assertTrue(sentTokens <= BUDGET * 1.35,
                "L1 must be bounded near the budget (stub summaries are the compressed floor), got "
                        + sentTokens);
        assertTrue(sentTokens < originalTokens * 0.6,
                "virtualization must substantially shrink the payload: "
                        + sentTokens + " vs " + originalTokens);
        assertTrue(v.messages().stream().anyMatch(m -> m.content().contains("[MEMORY_REF: id=\"seg_")),
                "evicted segments are replaced by semantic stubs");
        assertTrue(v.messages().stream().anyMatch(m -> m.content().contains("PAGE_REQUEST")),
                "the model is told how to fault a page back in");
        assertEquals("You are a helpful assistant.", v.messages().get(0).content(),
                "system prompt is pinned, never evicted");
        assertEquals("What's the weather like?", v.messages().get(v.messages().size() - 1).content(),
                "the recent window is pinned, never evicted");
        assertFalse(stubStore.isEmpty(), "stubs persisted to L2");
        stubStore.keySet().forEach(id ->
                assertEquals(1, eventStore.get(id).size(), "one CREATED event per stub in L3"));

        // Determinism/dedup: the same resent history maps to the SAME stub ids.
        int stubsBefore = stubStore.size();
        mmu.open("dev-1", longConversation("What's the weather like?"));
        assertEquals(stubsBefore, stubStore.size(),
                "identical history re-pages to identical stubs (content-hash dedup)");
    }

    @Test
    void predictivePrefetchPromotesReferencedPagesBeforeTheModelRuns() {
        // First request creates the stubs.
        mmu.open("dev-1", longConversation("unrelated question about the weather"));
        // Second request's newest prompt references the paged topic.
        var session = mmu.open("dev-1",
                longConversation("Remind me: what did we decide about OAuth versus JWT for the payments service authentication?"));
        LlmRequest v = session.request();

        assertTrue(v.messages().stream().anyMatch(m ->
                        m.content().startsWith("[PAGE_IN") && m.content().contains("reason=\"prefetch\"")),
                "a relevant page is promoted from L3 into L1 before dispatch");
    }

    @Test
    void pageFaultSuspendsMaterializesInjectsAndResumes() {
        var session = mmu.open("dev-1", longConversation("What's the weather like?"));
        String stubId = stubStore.keySet().iterator().next();

        AtomicInteger dispatches = new AtomicInteger();
        LlmResponse faulting = new LlmResponse(
                "[PAGE_REQUEST: id=\"" + stubId + "\"]", List.of(), 10, 5, "mock", "m", "stop");
        LlmResponse resolved = session.interceptFaults(faulting, req -> {
            dispatches.incrementAndGet();
            assertTrue(req.messages().stream().anyMatch(m ->
                            m.content().contains("[PAGE_IN id=\"" + stubId + "\"]")
                                    && m.content().contains("OAuth versus JWT")),
                    "the materialized page is injected before resuming");
            return new LlmResponse("Based on the restored context, the decision was JWT.",
                    List.of(), 20, 10, "mock", "m", "stop");
        });

        assertEquals(1, dispatches.get(), "exactly one bounded re-dispatch per fault");
        assertTrue(resolved.content().contains("JWT"), "generation resumed with the paged context");
    }

    @Test
    void coherenceMaterializesBasePlusSubsequentMutations() {
        var session = mmu.open("dev-1", longConversation("weather?"));
        String stubId = stubStore.keySet().iterator().next();
        // A later exchange mutated the entity: an UPDATED delta exists in L3.
        eventStore.get(stubId).add(new MmuStubEventEntity(stubId, "dev-1", 2, "UPDATED",
                "Decision superseded: migrated from JWT to PASETO tokens."));

        LlmResponse faulting = new LlmResponse(
                "[PAGE_REQUEST: id=\"" + stubId + "\"]", List.of(), 1, 1, "mock", "m", "stop");
        session.interceptFaults(faulting, req -> {
            String injected = req.messages().get(req.messages().size() - 1).content();
            assertTrue(injected.contains("[STATE UPDATE 2]"), "delta events are folded in");
            assertTrue(injected.contains("PASETO"), "the model sees the LATEST state, never stale");
            return new LlmResponse("ok", List.of(), 1, 1, "mock", "m", "stop");
        });
    }

    @Test
    void dirtyReversalIsFlushedWriteBehindAtTheNextCheckpoint() {
        var session = mmu.open("dev-1", longConversation("weather?"));
        String stubId = stubStore.keySet().iterator().next();
        int eventsBefore = eventStore.get(stubId).size();

        // The response REVERSES the paged decision (approved → rejected).
        session.finish(new LlmResponse(
                "On reflection the JWT approach is rejected; the design is invalid.",
                List.of(), 10, 5, "mock", "m", "stop"));
        assertEquals(eventsBefore, eventStore.get(stubId).size(),
                "no heavy write on the hot path — dirty is in-memory only");

        // The next MMU request is the natural checkpoint: the delta is flushed.
        mmu.open("dev-1", longConversation("next question"));
        assertEquals(eventsBefore + 1, eventStore.get(stubId).size(),
                "the UPDATED event lands at the checkpoint");
        assertEquals("UPDATED", eventStore.get(stubId).get(eventsBefore).getEventType());
        assertEquals(2, stubStore.get(stubId).getVersion(), "stub version bumped");
        assertTrue(stubStore.get(stubId).getSummary().contains("[updated:"),
                "the L2 summary now reflects the mutation");
    }

    @Test
    void shortConversationsPassThroughUnmodified() {
        LlmRequest small = LlmRequest.of(List.of(
                Message.system("You are helpful."), Message.user("hi")));
        var session = mmu.open("dev-1", small);
        assertEquals(small.messages(), session.request().messages(),
                "under-budget requests are untouched — zero paging overhead");
    }
}
