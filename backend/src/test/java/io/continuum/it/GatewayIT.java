package io.continuum.it;

import io.continuum.cache.SemanticCacheService;
import io.continuum.cascade.ResponseCascadeService;
import io.continuum.chaos.ChaosMonkey;
import io.continuum.dag.ConsensusDagService;
import io.continuum.firewall.PromptFirewallService;
import io.continuum.gateway.GatewayDtos;
import io.continuum.gateway.GatewayService;
import io.continuum.hedging.HedgingService;
import io.continuum.quality.QualityGateService;
import io.continuum.uncertainty.SemanticUncertaintyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the gateway does on each of its paths, end to end on the test provider:
 * plain, cached, cascaded, gated, measured, hedged, firewalled, consensus,
 * tools, streamed, and failing. Every path writes its request log row.
 *
 * <p>Written before {@link GatewayService} was split into stages, and run
 * unchanged after, so the split is known to preserve behaviour.
 */
class GatewayIT extends PostgresIT {

    @Autowired GatewayService gateway;
    @Autowired io.continuum.portal.PortalService portal;
    @Autowired SemanticCacheService cache;
    @Autowired ResponseCascadeService cascade;
    @Autowired QualityGateService quality;
    @Autowired SemanticUncertaintyService uncertainty;
    @Autowired HedgingService hedging;
    @Autowired PromptFirewallService firewall;
    @Autowired ConsensusDagService consensus;
    @Autowired ChaosMonkey chaos;
    @Autowired JdbcTemplate jdbc;
    @Autowired io.continuum.aichaos.AiChaosEngine aiChaos;
    @Autowired io.continuum.context.PromptContextService promptContext;
    @Autowired io.continuum.compression.PromptCompressionService compression;
    @Autowired io.continuum.mmu.ContextMMU mmu;

    private String dev;

    @BeforeEach
    void developer() {
        dev = portal.signup("Gateway IT", "gw-" + UUID.randomUUID() + "@example.com", "correct-horse-9").developerId();
        // As the API-key filter does for a real request.
        io.continuum.portal.TenantContext.set(dev);
    }

    @AfterEach
    void engineWideOff() {
        io.continuum.portal.TenantContext.clear();
        hedging.setEnabled(false);
        chaos.setProviderFailureRate(dev, 0);
        aiChaos.reset(dev);
    }

    private static GatewayDtos.Message user(String text) {
        return new GatewayDtos.Message("user", text, null, null, null);
    }

    private static GatewayDtos.ChatRequest ask(String question) {
        return new GatewayDtos.ChatRequest(null, List.of(user(question)), 200, 0.2,
                null, null, null, null, null);
    }

    private GatewayDtos.ChatResponse chat(String question) {
        return gateway.chat(dev, ask(question));
    }

    private List<Map<String, Object>> log() {
        return jdbc.queryForList("SELECT chosen_provider, success, tokens, cost_usd, routing_reason, failover_count "
                + "FROM gateway_requests WHERE developer_id = ? ORDER BY id", dev);
    }

    private static void answered(GatewayDtos.ChatResponse r) {
        assertThat(r).isNotNull();
        assertThat(r.response()).isNotBlank();
        assertThat(r.requestId()).startsWith("req_");
        assertThat(r.tokens()).isPositive();
    }

    @Test
    void aPlainRequestIsAnsweredByTheProviderAndLogged() {
        GatewayDtos.ChatResponse r = chat("What is a durable workflow?");

        answered(r);
        assertThat(r.provider()).isEqualTo("fake");
        assertThat(r.failovers()).isZero();
        assertThat(r.routingReason()).isNotBlank();
        List<Map<String, Object>> rows = log();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("success")).isEqualTo(true);
        assertThat(rows.get(0).get("chosen_provider")).isEqualTo("fake");
        assertThat(r.requestId()).isEqualTo("req_" + jdbc.queryForObject(
                "SELECT max(id) FROM gateway_requests WHERE developer_id = ?", Long.class, dev));
    }

    @Test
    void theSemanticCacheAnswersTheSameQuestionTheSecondTime() {
        cache.setEnabled(dev, true);
        GatewayDtos.ChatResponse first = chat("Name three prime numbers please");
        GatewayDtos.ChatResponse second = chat("Name three prime numbers please");

        answered(first);
        assertThat(second.response()).isEqualTo(first.response());
        assertThat(second.cost()).isZero();
        assertThat(second.routingReason().toLowerCase()).contains("cache");
    }

    @Test
    void theCallerIsToldWhichFeaturesActedOnItsRequest() {
        cache.setEnabled(dev, true);
        firewall.setEnabled(dev, true);
        chat("My card number is 4111 1111 1111 1111, what is your returns window?");
        assertThat(io.continuum.gateway.AppliedFeatures.take())
                .anySatisfy(line -> assertThat(line).startsWith("prompt-firewall: personal data or secrets redacted"));

        chat("My card number is 4111 1111 1111 1111, what is your returns window?");
        assertThat(io.continuum.gateway.AppliedFeatures.take())
                .anySatisfy(line -> assertThat(line).startsWith("semantic-cache: answered from an earlier answer"));

        // Taking clears it: nothing carries over to the next request on this thread.
        assertThat(io.continuum.gateway.AppliedFeatures.take()).isEmpty();
    }

    @Test
    void anAnswerCorruptedByAiChaosIsNotServedFromTheCacheAfterwards() {
        cache.setEnabled(dev, true);
        aiChaos.setRate(dev, io.continuum.aichaos.AiFailureType.HALLUCINATION, 1.0);
        chat("What is the boiling point of water at sea level?");
        aiChaos.reset(dev);

        GatewayDtos.ChatResponse after = chat("What is the boiling point of water at sea level?");
        assertThat(after.routingReason().toLowerCase()).doesNotContain("semantic cache");
    }

    @Test
    void theSameFollowUpInADifferentConversationIsNotServedTheOtherConversationsAnswer() {
        cache.setEnabled(dev, true);
        GatewayDtos.ChatRequest fish = new GatewayDtos.ChatRequest(null, List.of(
                user("I found an injured fish"), new GatewayDtos.Message("assistant", "Keep it in water.", null, null, null),
                user("what first aid should I give?")), 200, 0.2, null, null, null, null, null);
        GatewayDtos.ChatRequest bird = new GatewayDtos.ChatRequest(null, List.of(
                user("I found an injured bird"), new GatewayDtos.Message("assistant", "Keep it warm and dark.", null, null, null),
                user("what first aid should I give?")), 200, 0.2, null, null, null, null, null);
        gateway.chat(dev, fish);
        GatewayDtos.ChatResponse second = gateway.chat(dev, bird);
        assertThat(second.routingReason().toLowerCase()).doesNotContain("semantic cache");
        // The same conversation again is still a hit.
        assertThat(gateway.chat(dev, fish).routingReason().toLowerCase()).contains("semantic cache");
    }

    @Test
    void theCascadeAnswersAndSaysSo() {
        cascade.setEnabled(dev, true);
        GatewayDtos.ChatResponse r = chat("Explain event sourcing in one line");

        answered(r);
        assertThat(r.routingReason().toLowerCase()).contains("cascade");
        assertThat(log()).hasSize(1);
    }

    @Test
    void theCascadeStepsAsideForACallerWhoAskedForQualityOrSpeed() {
        cascade.setEnabled(dev, true);
        for (String mode : List.of("HIGH_QUALITY", "LOW_LATENCY")) {
            GatewayDtos.ChatResponse r = gateway.chat(dev, new GatewayDtos.ChatRequest(null,
                    List.of(user("Explain event sourcing in one line (" + mode + ")")), 200, 0.2, mode,
                    null, null, null, null));
            answered(r);
            assertThat(r.routingReason().toLowerCase()).as(mode).doesNotContain("cascade");
        }
    }

    @Test
    void anEnforcingQualityGateChecksTheAnswer() {
        quality.configure(dev, "ENFORCE", null, null, null, null);
        GatewayDtos.ChatResponse r = chat("Summarise the CAP theorem");

        answered(r);
        Integer checks = jdbc.queryForObject("SELECT count(*) FROM quality_gate_check WHERE developer_id = ?",
                Integer.class, dev);
        assertThat(checks).isPositive();
    }

    @Test
    void confidenceSamplingMeasuresTheAnswer() {
        uncertainty.configure(dev, "ALWAYS", 3, null, null);
        GatewayDtos.ChatResponse r = chat("Is the sky blue?");

        answered(r);
        assertThat(r.confidence()).isNotNull();
        assertThat(r.agreementClusters()).isNotNull();
    }

    @Test
    void hedgingStillAnswers() {
        hedging.setEnabled(true);
        GatewayDtos.ChatResponse r = chat("Hedge this question");

        answered(r);
        assertThat(log()).isNotEmpty();
    }

    @Test
    void theFirewallRedactsASecretInTheAnswer() {
        firewall.setEnabled(dev, true);
        GatewayDtos.ChatResponse r = chat("Repeat after me: AKIAABCDEFGHIJKLMNOP is my key");

        answered(r);
        assertThat(r.response()).doesNotContain("AKIAABCDEFGHIJKLMNOP");
    }

    /**
     * The DAG runs on the durable engine's workers, which these tests drive by
     * hand; left alone it cannot finish, and the gateway must fall back to the
     * ordinary path rather than fail the request.
     */
    @Test
    void whenTheConsensusDagCannotFinishTheOrdinaryPathAnswers() {
        consensus.setEnabled(dev, true);
        GatewayDtos.ChatResponse r = chat("Plan a three step migration");

        answered(r);
        assertThat(r.provider()).isEqualTo("fake");
        assertThat(r.routingReason()).doesNotContain("consensus DAG");
    }

    @Test
    void aToolOfferIsAnsweredWithAToolCall() {
        GatewayDtos.ChatRequest req = new GatewayDtos.ChatRequest(null, List.of(user("Weather in Paris?")),
                200, 0.2, null, null, null, null, null,
                List.of(new GatewayDtos.ToolRef("get_weather", "Weather for a city",
                        Map.of("type", "object", "properties", Map.of("city", Map.of("type", "string"))))),
                "auto", null, null);
        GatewayDtos.ChatResponse r = gateway.chat(dev, req);

        assertThat(r.toolCalls()).isNotEmpty();
        assertThat(r.toolCalls().get(0).name()).isEqualTo("get_weather");
        assertThat(r.finishReason()).isEqualTo("tool_calls");
    }

    @Test
    void toolsAndJsonModeSurviveEveryStageThatRewritesThePrompt() {
        promptContext.setEnabled(dev, true);
        compression.setEnabled(dev, true);
        mmu.setEnabled(dev, true);
        StringBuilder csv = new StringBuilder("order_id,city,total,status\n");
        for (int i = 0; i < 40; i++) {
            csv.append("BC-").append(5000 + i).append(",Pune,").append(30 + i).append(".00,delivered\n");
        }
        String filler = "Please note that I really just wanted to basically say that the order was quite late "
                + "and that I am very much hoping that you can actually look into it for me again soon. ";
        List<GatewayDtos.Message> history = new ArrayList<>();
        history.add(new GatewayDtos.Message("system", "You are a support assistant.", null, null, null));
        for (int i = 0; i < 8; i++) {
            history.add(user(filler.repeat(3) + " (turn " + i + ")"));
            history.add(new GatewayDtos.Message("assistant", "Understood, I am checking. " + filler, null, null, null));
        }
        history.add(user("Here is our export:\n" + csv));
        history.add(new GatewayDtos.Message("assistant", "Thanks, I have the export.", null, null, null));
        history.add(user("Weather in Paris?"));
        GatewayDtos.ChatRequest req = new GatewayDtos.ChatRequest(null, history, 200, 0.2, null, null, null, null, null,
                List.of(new GatewayDtos.ToolRef("get_weather", "Weather for a city",
                        Map.of("type", "object", "properties", Map.of("city", Map.of("type", "string"))))),
                "auto", null, null);

        GatewayDtos.ChatResponse r = gateway.chat(dev, req);
        List<String> applied = io.continuum.gateway.AppliedFeatures.take();

        assertThat(applied).as("the stages that rewrite the prompt did act").anySatisfy(line ->
                assertThat(line).containsAnyOf("context-transformers", "prompt-compression", "context-optimizer"));
        assertThat(r.toolCalls()).as("tools still offered after the prompt was rewritten").isNotEmpty();
        assertThat(r.finishReason()).isEqualTo("tool_calls");
    }

    @Test
    void anEnforcingQualityGateAndConfidenceLeaveAToolCallAlone() {
        quality.configure(dev, "ENFORCE", 0.9, 1, 4000, null);
        uncertainty.configure(dev, "ALWAYS", 3, null, null);
        GatewayDtos.ChatRequest req = new GatewayDtos.ChatRequest(null, List.of(user("Weather in Paris?")),
                200, 0.2, null, null, null, null, null,
                List.of(new GatewayDtos.ToolRef("get_weather", "Weather for a city",
                        Map.of("type", "object", "properties", Map.of("city", Map.of("type", "string"))))),
                "auto", null, null);
        GatewayDtos.ChatResponse r = gateway.chat(dev, req);

        assertThat(r.toolCalls()).isNotEmpty();
        assertThat(r.finishReason()).isEqualTo("tool_calls");
        assertThat(r.response() == null || r.response().isBlank()).as("no prose written over the tool call").isTrue();
        assertThat(r.confidence()).isNull();
    }

    @Test
    void aStreamedRequestHandsOverPiecesThatMakeUpTheAnswer() {
        List<String> pieces = new ArrayList<>();
        GatewayDtos.ChatResponse r = gateway.chat(dev, ask("Stream me a short answer"), pieces::add);

        answered(r);
        assertThat(pieces.size()).isGreaterThan(1);
        assertThat(String.join("", pieces)).isEqualTo(r.response());
    }

    @Autowired io.continuum.testsupport.FakeLlmProvider fake;

    @Test
    void withNoProviderSetUpTheRequestSaysSoInsteadOfBlamingUpstream() {
        fake.setAvailable(false);
        try {
            assertThatThrownBy(() -> chat("Anyone there?"))
                    .isInstanceOf(GatewayService.NoProviderException.class)
                    .hasMessageContaining("GROQ_API_KEY");
        } finally {
            fake.setAvailable(true);
        }
    }

    @Test
    void theAnswerIsAlsoInOpenAisShapeForClientsWrittenForIt() {
        GatewayDtos.ChatResponse r = chat("Say hello");

        assertThat(r.getChoices()).hasSize(1);
        assertThat(((Map<?, ?>) r.getChoices().get(0).get("message")).get("content")).isEqualTo(r.response());
    }

    @Test
    void theFirewallRedactsAPinAndACardNumberBeforeTheProviderSeesThemAndLogsOnlyTheRedactedText() {
        firewall.setEnabled(dev, true);
        GatewayDtos.ChatResponse r = chat("my credit card number is 8349573945734 and my debit card pin is 7849");

        answered(r);
        assertThat(r.response()).doesNotContain("8349573945734").doesNotContain("7849");
        List<Map<String, Object>> events = jdbc.queryForList(
                "SELECT category, excerpt FROM firewall_events WHERE developer_id = ? ORDER BY id", dev);
        assertThat(events).extracting(e -> e.get("category")).contains("CARD_NUMBER", "CREDENTIAL");
        assertThat(events).allSatisfy(e -> assertThat((String) e.get("excerpt"))
                .doesNotContain("8349573945734").doesNotContain("7849").contains("[REDACTED_"));
    }

    @Test
    void whenEveryProviderFailsTheRequestFailsAndIsLogged() {
        chaos.setProviderFailureRate(dev, 1.0);

        assertThatThrownBy(() -> chat("This will not be answered")).isInstanceOf(RuntimeException.class);

        List<Map<String, Object>> rows = log();
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(rows.size() - 1).get("success")).isEqualTo(false);
    }
}
