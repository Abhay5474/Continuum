package io.continuum.registry.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.continuum.common.Json;
import io.continuum.config.LlmProperties;
import io.continuum.persistence.entity.ModelEntity;
import io.continuum.persistence.repository.ModelRepository;
import io.continuum.registry.ModelStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The catalogue's rules, against fake providers that count every call.
 * Each test is one of the situations the catalogue exists for.
 */
class ModelCatalogServiceTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-26T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    private final List<ModelEntity> rows = new ArrayList<>();
    private final ModelRepository repo = Fakes.models(rows);
    private final Fakes.Store store = new Fakes.Store();
    private final MutableClock clock = new MutableClock();
    private final List<Long> sleeps = new ArrayList<>();
    private Fakes.Client groq;
    private Fakes.Client gemini;
    private ModelResolver resolver;
    private ModelCatalogService catalogue;
    private LlmProperties props;

    @BeforeEach
    void setUp() {
        groq = new Fakes.Client("groq");
        gemini = new Fakes.Client("gemini");
        gemini.configured = false;
        props = new LlmProperties();
        props.getGroq().setModel("");
        props.getGemini().setModel("");
        build(ModelCatalogSettings.of(10, 12, 2500));
    }

    private void build(ModelCatalogSettings settings) {
        resolver = new ModelResolver(repo, store, props, List.of(groq, gemini), settings, clock);
        catalogue = new ModelCatalogService(repo, store, List.of(groq, gemini), resolver, settings,
                new Json(new ObjectMapper().registerModule(new JavaTimeModule())), clock, sleeps::add);
    }

    private ModelEntity legacy(String provider, String name) {
        ModelEntity m = new ModelEntity(provider, name, ModelStatus.ACTIVE, 128_000, null, null);
        rows.add(m);
        return m;
    }

    private ModelEntity row(String name) {
        return rows.stream().filter(m -> m.getModelName().equals(name)).findFirst().orElseThrow();
    }

    private void check() {
        assertThat(catalogue.runNow("MANUAL", "dev", List.of("groq", "gemini"), false)).isTrue();
    }

    @Test
    void theGroqRetirementIsCaughtAtTheFirstCheckAndRequestsMoveToAReplacement() {
        // What the deployment had: the hardcoded names, one of them retired by Groq.
        legacy("groq", "llama-3.3-70b-versatile");
        legacy("groq", "llama-3.1-8b-instant");
        groq.listing("openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.6-27b-preview");

        check();

        // Never seen in a list, so the first successful list retires them outright.
        assertThat(row("llama-3.3-70b-versatile").getStatus()).isEqualTo(ModelStatus.REMOVED);
        assertThat(row("llama-3.1-8b-instant").getStatus()).isEqualTo(ModelStatus.REMOVED);
        // The listed chat models answered their test calls.
        assertThat(row("openai/gpt-oss-120b").getStatus()).isEqualTo(ModelStatus.ACTIVE);
        assertThat(row("openai/gpt-oss-120b").getVerifiedAt()).isNotNull();
        // Stable and strongest first; the preview is never the default while a stable one works.
        assertThat(resolver.defaultFor("groq")).isEqualTo("openai/gpt-oss-120b");
        // A request still naming the retired model goes to the replacement.
        assertThat(resolver.resolve("groq", "llama-3.3-70b-versatile")).isEqualTo("openai/gpt-oss-120b");
        assertThat(row("llama-3.3-70b-versatile").getReplacedBy()).isEqualTo("openai/gpt-oss-120b");
        assertThat(store.eventTypes()).contains("RETIRED:llama-3.3-70b-versatile",
                "REPLACED:llama-3.3-70b-versatile", "DEFAULT_CHANGED:openai/gpt-oss-120b");
        // Cost: one list, three tests, spaced.
        assertThat(groq.listCalls).isEqualTo(1);
        assertThat(groq.probed).hasSize(3);
        assertThat(sleeps).containsOnly(2500L).hasSize(2);
        assertThat(gemini.listCalls).isZero();
    }

    @Test
    void aFailedListChangesNothing() {
        legacy("groq", "openai/gpt-oss-120b");
        groq.list = ListResult.failed("Groq model list answered HTTP 503");

        check();

        assertThat(row("openai/gpt-oss-120b").getStatus()).isEqualTo(ModelStatus.ACTIVE);
        assertThat(store.state("groq").lastError).contains("503");
        assertThat(store.state("groq").lastListOkAt).isNull();
        assertThat(groq.probed).isEmpty();
        assertThat(store.eventTypes()).containsExactly("CHECK_FAILED");
    }

    @Test
    void aModelThatWasListedIsRetiredOnlyAfterTwoListsInARowLeaveItOut() {
        groq.listing("a-70b", "b-70b", "c-70b", "d-70b", "e-70b");
        check();
        assertThat(row("e-70b").getStatus()).isEqualTo(ModelStatus.ACTIVE);

        groq.listing("a-70b", "b-70b", "c-70b", "d-70b");
        clock.advance(Duration.ofDays(10));
        check();
        ModelEntity e = row("e-70b");
        assertThat(e.getStatus()).as("missing once: kept, not routed").isEqualTo(ModelStatus.DEPRECATED);
        assertThat(repo.findByProviderAndStatus("groq", ModelStatus.ACTIVE)).extracting(ModelEntity::getModelName)
                .doesNotContain("e-70b");

        clock.advance(Duration.ofDays(10));
        check();
        assertThat(row("e-70b").getStatus()).isEqualTo(ModelStatus.REMOVED);
        assertThat(store.eventTypes()).contains("MISSING:e-70b", "RETIRED:e-70b");
    }

    @Test
    void aModelMissingOnceThatComesBackIsRestoredWithoutRetesting() {
        groq.listing("a-70b", "b-70b");
        check();
        groq.listing("a-70b");
        clock.advance(Duration.ofDays(10));
        check();
        groq.probed.clear();
        groq.listing("a-70b", "b-70b");
        clock.advance(Duration.ofDays(10));
        check();

        assertThat(row("b-70b").getStatus()).isEqualTo(ModelStatus.ACTIVE);
        assertThat(groq.probed).isEmpty();
        assertThat(store.eventTypes()).contains("RETURNED:b-70b");
    }

    @Test
    void aSuspiciouslyShortListRetiresNothing() {
        groq.listing("a-70b", "b-70b", "c-70b", "d-70b", "e-70b", "f-70b");
        check();
        groq.listing("a-70b");
        clock.advance(Duration.ofDays(10));
        check();

        assertThat(rows).filteredOn(m -> m.getStatus() == ModelStatus.ACTIVE).hasSize(6);
        assertThat(store.eventTypes()).contains("CHECK_WARNING");
    }

    @Test
    void testCallsStayWithinTheBudgetAndTheRestWaitUnrouted() {
        String[] ids = new String[20];
        for (int i = 0; i < 20; i++) {
            ids[i] = "model-" + (char) ('a' + i) + "-70b";
        }
        groq.listing(ids);

        check();

        assertThat(groq.probed).hasSize(12);
        assertThat(sleeps).hasSize(11);
        assertThat(rows).filteredOn(m -> m.getStatus() == ModelStatus.DISCOVERED).hasSize(8);
        assertThat(repo.findByProviderAndStatus("groq", ModelStatus.ACTIVE)).hasSize(12);

        // The next check tests the remaining eight, and nothing already verified.
        groq.probed.clear();
        clock.advance(Duration.ofDays(10));
        check();
        assertThat(groq.probed).hasSize(8);
    }

    @Test
    void twoRateLimitsInARowStopTestingForThisCheck() {
        groq.listing("a-70b", "b-70b", "c-70b", "d-70b", "e-70b");
        groq.defaultProbe = ProbeResult.of(ProbeResult.Outcome.RATE_LIMITED, "429");

        check();

        assertThat(groq.probed).hasSize(2);
        assertThat(rows).allMatch(m -> m.getStatus() == ModelStatus.DISCOVERED);
        assertThat(store.eventTypes()).contains("PROBES_PAUSED");
    }

    @Test
    void aRefusedKeyStopsTestingAtOnce() {
        groq.listing("a-70b", "b-70b", "c-70b");
        groq.defaultProbe = ProbeResult.of(ProbeResult.Outcome.KEY_REJECTED, "Groq refused the API key");

        check();

        assertThat(groq.probed).hasSize(1);
        assertThat(store.eventTypes()).contains("PROBES_STOPPED");
    }

    @Test
    void aModelWithoutFreeQuotaIsNotRoutedAndItsNameRedirects() {
        gemini.configured = true;
        gemini.listing("gemini-3.5-flash", "gemini-3.8-flash");
        gemini.probes.put("gemini-3.8-flash", ProbeResult.of(ProbeResult.Outcome.NOT_FREE, "No free-tier quota"));

        catalogue.runNow("MANUAL", "dev", List.of("gemini"), false);

        assertThat(row("gemini-3.8-flash").getStatus()).isEqualTo(ModelStatus.UNAVAILABLE);
        assertThat(resolver.defaultFor("gemini")).isEqualTo("gemini-3.5-flash");
        assertThat(resolver.resolve("gemini", "gemini-3.8-flash")).isEqualTo("gemini-3.5-flash");
    }

    @Test
    void aRetiredGeminiFlashIsReplacedByTheNewestFlashNotTheLite() {
        gemini.configured = true;
        gemini.listing("gemini-3.5-flash", "gemini-3.5-flash-lite");
        catalogue.runNow("MANUAL", "dev", List.of("gemini"), false);
        assertThat(resolver.defaultFor("gemini")).isEqualTo("gemini-3.5-flash");

        // Google ships 3.8 and removes 3.5; the default must not move until 3.5 is gone.
        gemini.listing("gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-3.8-flash");
        clock.advance(Duration.ofDays(10));
        catalogue.runNow("SCHEDULED", null, List.of("gemini"), false);
        assertThat(resolver.defaultFor("gemini")).isEqualTo("gemini-3.5-flash");

        gemini.listing("gemini-3.5-flash-lite", "gemini-3.8-flash");
        clock.advance(Duration.ofDays(10));
        catalogue.runNow("SCHEDULED", null, List.of("gemini"), false);
        clock.advance(Duration.ofDays(10));
        catalogue.runNow("SCHEDULED", null, List.of("gemini"), false);

        assertThat(row("gemini-3.5-flash").getStatus()).isEqualTo(ModelStatus.REMOVED);
        assertThat(resolver.defaultFor("gemini")).isEqualTo("gemini-3.8-flash");
        assertThat(resolver.resolve("gemini", "gemini-3.5-flash")).isEqualTo("gemini-3.8-flash");
    }

    @Test
    void aLiveReportSetsTheModelAsideAtOnceAndOneConfirmingListRetiresIt() {
        groq.listing("openai/gpt-oss-120b", "openai/gpt-oss-20b");
        check();
        assertThat(resolver.defaultFor("groq")).isEqualTo("openai/gpt-oss-120b");
        int listsBefore = groq.listCalls;
        int probesBefore = groq.probed.size();

        // Groq retires the default between checks; a request is told so.
        groq.listing("openai/gpt-oss-20b");
        catalogue.reportUnavailable("groq", "openai/gpt-oss-120b", true, "HTTP 404: model_not_found");

        // Immediately, before any confirmation: nothing more is sent to it.
        assertThat(resolver.isQuarantined("groq", "openai/gpt-oss-120b")).isTrue();
        assertThat(resolver.defaultFor("groq")).isEqualTo("openai/gpt-oss-20b");

        await(() -> row("openai/gpt-oss-120b").getStatus() == ModelStatus.REMOVED);
        // One list, no tests: the confirmation is cheap.
        assertThat(groq.listCalls - listsBefore).isEqualTo(1);
        assertThat(groq.probed.size() - probesBefore).isZero();
        assertThat(resolver.resolve("groq", "openai/gpt-oss-120b")).isEqualTo("openai/gpt-oss-20b");

        // A second report inside the cooldown does not list again.
        catalogue.reportUnavailable("groq", "openai/gpt-oss-20b", true, "HTTP 404");
        sleep(300);
        assertThat(groq.listCalls - listsBefore).isEqualTo(1);
    }

    @Test
    void aReportAboutAModelTheProviderStillListsMarksItUnusableForThisKey() {
        groq.listing("openai/gpt-oss-120b", "openai/gpt-oss-20b");
        check();

        catalogue.reportUnavailable("groq", "openai/gpt-oss-120b", true, "HTTP 404: model_not_found");

        await(() -> row("openai/gpt-oss-120b").getStatus() == ModelStatus.UNAVAILABLE);
        assertThat(resolver.isQuarantined("groq", "openai/gpt-oss-120b")).isFalse();
        assertThat(resolver.defaultFor("groq")).isEqualTo("openai/gpt-oss-20b");
    }

    @Test
    void theButtonIsSingleFlightAndHasACooldown() {
        groq.listing("openai/gpt-oss-120b");
        check();

        ModelCatalogService.Start again = catalogue.requestCheck("dev");
        assertThat(again.outcome()).isEqualTo(ModelCatalogService.StartOutcome.COOLING_DOWN);
        assertThat(again.retryAfterSeconds()).isBetween(1L, 600L);

        clock.advance(Duration.ofMinutes(11));
        store.leaseHolder = "another-instance";
        assertThat(catalogue.requestCheck("dev").outcome()).isEqualTo(ModelCatalogService.StartOutcome.BUSY);
        assertThat(groq.listCalls).isEqualTo(1);
    }

    @Test
    void withNoKeyThereIsNothingToCheckAndNoCallIsMade() {
        groq.configured = false;
        assertThat(catalogue.requestCheck("dev").outcome()).isEqualTo(ModelCatalogService.StartOutcome.NOTHING_TO_CHECK);
        catalogue.runIfDue();
        assertThat(groq.listCalls).isZero();
    }

    @Test
    void aCheckIsDueTenDaysAfterTheLastSuccessWhateverTheDate() {
        ModelCatalogStore.ProviderState s = new ModelCatalogStore.ProviderState("groq");
        assertThat(catalogue.isDue(s)).as("never checked").isTrue();

        s.lastListOkAt = clock.now;
        s.lastAttemptAt = clock.now;
        assertThat(catalogue.isDue(s)).isFalse();
        clock.advance(Duration.ofDays(9));
        assertThat(catalogue.isDue(s)).isFalse();
        // Off on the day it fell due, back two days later: due at once.
        clock.advance(Duration.ofDays(3));
        assertThat(catalogue.isDue(s)).isTrue();

        // A failed attempt waits six hours before trying again, not an hour.
        s.lastAttemptAt = clock.now;
        assertThat(catalogue.isDue(s)).isFalse();
        clock.advance(Duration.ofHours(6));
        assertThat(catalogue.isDue(s)).isTrue();
    }

    @Test
    void speechAndSafetyModelsAreCataloguedButNeverTestedOrRouted() {
        groq.list = ListResult.ok(List.of(
                Fakes.Client.chat("openai/gpt-oss-120b"),
                new ListedModel("whisper-large-v3", ModelKind.SPEECH_TO_TEXT, null, null, 0, 0, 0, false, "speech"),
                new ListedModel("meta-llama/llama-guard-4-12b", ModelKind.SAFETY, null, null, 0, 0, 0, false, "safety")));

        check();

        assertThat(groq.probed).containsExactly("openai/gpt-oss-120b");
        assertThat(row("whisper-large-v3").isChat()).isFalse();
        assertThat(resolver.resolve("groq", null)).isEqualTo("openai/gpt-oss-120b");
    }

    @Test
    void theConfiguredModelIsPreferredWhileUsableAndDroppedWhenRetired() {
        props.getGroq().setModel("openai/gpt-oss-20b");
        groq.listing("openai/gpt-oss-120b", "openai/gpt-oss-20b");
        check();
        assertThat(resolver.defaultFor("groq")).isEqualTo("openai/gpt-oss-20b");

        groq.listing("openai/gpt-oss-120b");
        clock.advance(Duration.ofDays(10));
        check();
        clock.advance(Duration.ofDays(10));
        check();
        assertThat(resolver.defaultFor("groq")).isEqualTo("openai/gpt-oss-120b");
    }

    @Test
    void theFirstCheckKeepsTheModelTheDeploymentWasAlreadyRunning() {
        // Before the catalogue this deployment ran gemini-3.5-flash. A newer
        // flash in the list is no reason to move every request on upgrade.
        gemini.configured = true;
        gemini.seeds = List.of(Fakes.Client.chat("gemini-3.5-flash"));
        gemini.listing("gemini-3.5-flash", "gemini-3.7-flash");

        catalogue.runNow("SCHEDULED", null, List.of("gemini"), false);

        assertThat(resolver.defaultFor("gemini")).isEqualTo("gemini-3.5-flash");
    }

    @Test
    void anAnnouncedRetirementIsShownBeforeAnyKeyIsConfiguredButNeverUndoesALiveModel() {
        Fakes.Client known = new Fakes.Client("groq") {
            @Override
            public Map<String, String> retiredBeforeCatalogue() {
                return Map.of("llama-3.3-70b-versatile", "Retired by Groq on 16 Aug 2026", "openai/gpt-oss-20b", "x");
            }
        };
        known.configured = false;
        known.seeds = List.of(Fakes.Client.chat("openai/gpt-oss-120b"));
        groq = known;
        build(ModelCatalogSettings.of(10, 12, 2500));
        legacy("groq", "llama-3.3-70b-versatile");
        ModelEntity live = legacy("groq", "openai/gpt-oss-20b");
        live.setSource("live");

        catalogue.applySeeds();
        resolver.refresh();

        assertThat(row("llama-3.3-70b-versatile").getStatus()).isEqualTo(ModelStatus.REMOVED);
        assertThat(resolver.resolve("groq", "llama-3.3-70b-versatile")).isEqualTo("openai/gpt-oss-120b");
        assertThat(row("openai/gpt-oss-20b").getStatus()).as("a model a list has named is not touched").isEqualTo(ModelStatus.ACTIVE);
        assertThat(known.listCalls).isZero();
    }

    @Test
    void seedsFillAnEmptyCatalogueWithoutCallingAnyone() {
        groq.seeds = List.of(Fakes.Client.chat("openai/gpt-oss-120b"));
        groq.configured = false;

        catalogue.applySeeds();
        resolver.refresh();

        assertThat(row("openai/gpt-oss-120b").getSource()).isEqualTo("seed");
        assertThat(resolver.defaultFor("groq")).isEqualTo("openai/gpt-oss-120b");
        assertThat(groq.listCalls).isZero();
        assertThat(groq.probed).isEmpty();
    }

    private static void await(java.util.function.BooleanSupplier ok) {
        long end = System.currentTimeMillis() + 5000;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("condition not met in time");
            }
            sleep(20);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> unused() {
        return Map.of();
    }
}
