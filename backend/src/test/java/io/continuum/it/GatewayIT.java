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
 * What the gateway does on each of its paths, end to end on the mock provider:
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
        assertThat(r.provider()).isEqualTo("mock");
        assertThat(r.failovers()).isZero();
        assertThat(r.routingReason()).isNotBlank();
        List<Map<String, Object>> rows = log();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("success")).isEqualTo(true);
        assertThat(rows.get(0).get("chosen_provider")).isEqualTo("mock");
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
    void theCascadeAnswersAndSaysSo() {
        cascade.setEnabled(dev, true);
        GatewayDtos.ChatResponse r = chat("Explain event sourcing in one line");

        answered(r);
        assertThat(r.routingReason().toLowerCase()).contains("cascade");
        assertThat(log()).hasSize(1);
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
        assertThat(r.provider()).isEqualTo("mock");
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
    void aStreamedRequestHandsOverPiecesThatMakeUpTheAnswer() {
        List<String> pieces = new ArrayList<>();
        GatewayDtos.ChatResponse r = gateway.chat(dev, ask("Stream me a short answer"), pieces::add);

        answered(r);
        assertThat(pieces.size()).isGreaterThan(1);
        assertThat(String.join("", pieces)).isEqualTo(r.response());
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
