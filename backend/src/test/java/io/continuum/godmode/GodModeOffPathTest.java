package io.continuum.godmode;

import io.continuum.godmode.memory.MemoryEngine;
import io.continuum.godmode.twin.DigitalTwinSimulator;
import io.continuum.godmode.twin.TwinCanaryPreflight;
import io.continuum.persistence.entity.GodModeConfigEntity;
import io.continuum.persistence.repository.GodModeConfigRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * THE God Mode compatibility guarantee: while a developer has not opted in,
 * every hook is a strict no-op — no memory writes, no twin runs, no canary
 * interference. This mirrors V4's PolicyResolverTest contract.
 */
class GodModeOffPathTest {

    private final GodModeConfigRepository configs = mock(GodModeConfigRepository.class);
    private final MemoryEngine memory = mock(MemoryEngine.class);
    private final GodModeService service = new GodModeService(configs, memory);

    @Test
    void noConfigMeansEveryHookIsEmpty() {
        when(configs.findById("dev-1")).thenReturn(Optional.empty());

        assertTrue(service.configIfEnabled("dev-1").isEmpty());
        service.observeExchange("dev-1", "s", "user text", "assistant text");
        verifyNoInteractions(memory);
    }

    @Test
    void disabledConfigMeansEveryHookIsEmpty() {
        GodModeConfigEntity off = new GodModeConfigEntity("dev-1"); // enabled = false by default
        when(configs.findById("dev-1")).thenReturn(Optional.of(off));

        assertTrue(service.configIfEnabled("dev-1").isEmpty(), "God Mode must be OFF by default");
        service.observeExchange("dev-1", "s", "user text", "assistant text");
        verifyNoInteractions(memory);
    }

    @Test
    void enabledConfigActivatesTheHooks() {
        GodModeConfigEntity on = new GodModeConfigEntity("dev-1");
        on.setEnabled(true);
        when(configs.findById("dev-1")).thenReturn(Optional.of(on));

        assertTrue(service.configIfEnabled("dev-1").isPresent());
        service.observeExchange("dev-1", "s", "user text", "assistant text");
        verify(memory, times(2)).ingest(eq(on), eq("s"), anyString(), anyString());
    }

    @Test
    void twinPreflightAllowsEverythingWhenGodModeIsOff() {
        when(configs.findById("dev-1")).thenReturn(Optional.empty());
        DigitalTwinSimulator twin = mock(DigitalTwinSimulator.class);
        TwinCanaryPreflight preflight = new TwinCanaryPreflight(service, twin);

        var result = preflight.check("dev-1", 42L);

        assertFalse(result.veto(), "V4 canary path must be untouched when God Mode is off");
        verifyNoInteractions(twin);
    }

    @Test
    void memoryObservationFailuresNeverPropagateToTheRequestPath() {
        GodModeConfigEntity on = new GodModeConfigEntity("dev-1");
        on.setEnabled(true);
        when(configs.findById("dev-1")).thenReturn(Optional.of(on));
        doThrow(new RuntimeException("db down")).when(memory).ingest(any(), any(), any(), any());

        assertDoesNotThrow(() -> service.observeExchange("dev-1", "s", "u", "a"));
    }
}
