package io.continuum.gateway.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.gateway.GatewayDtos;
import io.continuum.provider.mock.MockProvider;
import io.continuum.provider.model.ImagePart;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.LlmResponse;
import io.continuum.provider.model.Message;
import io.continuum.provider.model.ResponseFormat;
import io.continuum.provider.model.Role;
import io.continuum.provider.model.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenAI compatibility surface.
 *
 * <p>These exist because the failure they guard against is silent. The gateway
 * advertised {@code /v1/chat/completions} for months while answering in
 * Continuum's own shape; nothing failed, no test went red, and any real SDK
 * pointed at it got a 200 followed by a null-pointer on {@code choices}. The
 * only way that stays fixed is if the wire format itself is asserted.
 */
class OpenAiCompatTest {

    private final ObjectMapper json = new ObjectMapper();
    private final OpenAiTranslator translator = new OpenAiTranslator(json);

    /* ---------------------------------------------------------------- *
     * Inbound translation
     * ---------------------------------------------------------------- */

    @Test
    void readsPlainStringContent() throws Exception {
        var req = parse("""
                {"model":"auto","messages":[{"role":"user","content":"hello"}]}""");
        var gw = translator.toGateway(req);
        assertThat(gw.messages()).hasSize(1);
        assertThat(gw.messages().get(0).content()).isEqualTo("hello");
        assertThat(gw.messages().get(0).images()).isNull();
    }

    @Test
    void readsTypedContentPartsAndKeepsTheImages() throws Exception {
        var req = parse("""
                {"model":"auto","messages":[{"role":"user","content":[
                  {"type":"text","text":"what is this"},
                  {"type":"image_url","image_url":{"url":"https://x/a.png","detail":"low"}}]}]}""");
        var gw = translator.toGateway(req);
        var m = gw.messages().get(0);
        assertThat(m.content()).isEqualTo("what is this");
        assertThat(m.images()).hasSize(1);
        assertThat(m.images().get(0).url()).isEqualTo("https://x/a.png");
        assertThat(m.images().get(0).detail()).isEqualTo("low");
    }

    @Test
    void joinsSeveralTextPartsAsSeparateBlocks() throws Exception {
        var req = parse("""
                {"messages":[{"role":"user","content":[
                  {"type":"text","text":"first"},{"type":"text","text":"second"}]}]}""");
        // Not "firstsecond": they were separate blocks to the caller, and gluing
        // them turns two paragraphs into one run-on sentence.
        assertThat(translator.toGateway(req).messages().get(0).content()).isEqualTo("first\nsecond");
    }

    @Test
    void carriesToolsAndToolChoice() throws Exception {
        var req = parse("""
                {"messages":[{"role":"user","content":"hi"}],
                 "tools":[{"type":"function","function":{"name":"get_weather",
                   "description":"w","parameters":{"type":"object"}}}],
                 "tool_choice":{"type":"function","function":{"name":"get_weather"}}}""");
        var gw = translator.toGateway(req);
        assertThat(gw.tools()).hasSize(1);
        assertThat(gw.tools().get(0).name()).isEqualTo("get_weather");
        assertThat(gw.toolChoice()).isEqualTo("get_weather");
    }

    @Test
    void carriesAToolResultBackWithItsCallId() throws Exception {
        var req = parse("""
                {"messages":[
                  {"role":"assistant","content":null,"tool_calls":[
                     {"id":"call_1","type":"function","function":{"name":"f","arguments":"{}"}}]},
                  {"role":"tool","tool_call_id":"call_1","content":"{\\"ok\\":true}"}]}""");
        var gw = translator.toGateway(req);
        assertThat(gw.messages().get(0).toolCalls()).hasSize(1);
        assertThat(gw.messages().get(0).toolCalls().get(0).id()).isEqualTo("call_1");
        // Without the id the conversation is malformed and every provider rejects it.
        assertThat(gw.messages().get(1).toolCallId()).isEqualTo("call_1");
    }

    @Test
    void prefersMaxCompletionTokensOverMaxTokens() throws Exception {
        var req = parse("""
                {"messages":[{"role":"user","content":"x"}],
                 "max_tokens":10,"max_completion_tokens":99}""");
        assertThat(req.effectiveMaxTokens()).isEqualTo(99);
    }

    @Test
    void ignoresFieldsItDoesNotImplementRatherThanRejectingThem() throws Exception {
        // A gateway that 400s on `seed` is worse than one that routes and ignores it.
        var req = parse("""
                {"messages":[{"role":"user","content":"x"}],
                 "seed":42,"user":"u","logit_bias":{"1":2},"frequency_penalty":0.1}""");
        assertThat(req.messages()).hasSize(1);
    }

    /* ---------------------------------------------------------------- *
     * Outbound translation
     * ---------------------------------------------------------------- */

    @Test
    void producesAChatCompletionAnSdkCanRead() throws Exception {
        var r = new GatewayDtos.ChatResponse("hello there", "mock", "mock-1", 42, 19, 0.001, 0, "reason");
        String body = json.writeValueAsString(translator.toCompletion("id-1", r, null));
        Map<?, ?> parsed = json.readValue(body, Map.class);

        assertThat(parsed.get("object")).isEqualTo("chat.completion");
        assertThat(parsed.get("model")).isEqualTo("mock-1");
        List<?> choices = (List<?>) parsed.get("choices");
        assertThat(choices).hasSize(1);
        Map<?, ?> choice = (Map<?, ?>) choices.get(0);
        assertThat(choice.get("finish_reason")).isEqualTo("stop");
        assertThat(((Map<?, ?>) choice.get("message")).get("content")).isEqualTo("hello there");
        assertThat(parsed.get("usage")).isNotNull();
    }

    @Test
    void reportsToolCallsWithTheRightFinishReasonAndNullContent() throws Exception {
        var r = new GatewayDtos.ChatResponse("", "mock", "mock-1", 10, 5, 0, 0, "reason")
                .withCompletion(List.of(new GatewayDtos.ToolCallRef("call_1", "get_weather", "{\"city\":\"Paris\"}")),
                        2, 3);
        var completion = translator.toCompletion("id-2", r, null);
        var choice = completion.choices().get(0);

        assertThat(choice.finishReason()).isEqualTo("tool_calls");
        // OpenAI sends null, not "", and clients branch on it.
        assertThat(choice.message().content()).isNull();
        assertThat(choice.message().toolCalls()).hasSize(1);
        assertThat(choice.message().toolCalls().get(0).function().name()).isEqualTo("get_weather");
    }

    @Test
    void reconstructsTheTokenSplitRatherThanReportingZeroes() {
        // Only the total survived the pipeline; reporting 0/0/19 would make every
        // client's cost arithmetic wrong.
        var r = new GatewayDtos.ChatResponse("x", "mock", "m", 1, 19, 0, 0, "r");
        var usage = translator.usage(r);
        assertThat(usage.totalTokens()).isEqualTo(19);
        assertThat(usage.promptTokens() + usage.completionTokens()).isEqualTo(19);
    }

    @Test
    void putsTheReliabilityFactsUnderTheirOwnKey() throws Exception {
        var r = new GatewayDtos.ChatResponse("x", "groq", "llama", 120, 8, 0.002, 2, "failed over twice");
        Map<?, ?> parsed = json.readValue(
                json.writeValueAsString(translator.toCompletion("id", r, "buffered")), Map.class);
        // Under "continuum" so the payload stays a valid ChatCompletion for a
        // strict client, while still being discoverable by one that cares.
        Map<?, ?> meta = (Map<?, ?>) parsed.get("continuum");
        assertThat(meta.get("provider")).isEqualTo("groq");
        assertThat(meta.get("failovers")).isEqualTo(2);
        assertThat(meta.get("stream_mode")).isEqualTo("buffered");
    }

    /* ---------------------------------------------------------------- *
     * Streaming frames
     * ---------------------------------------------------------------- */

    @Test
    void openingFrameCarriesTheRoleAndNoContent() throws Exception {
        Map<?, ?> frame = json.readValue(translator.openingChunk("id", "m"), Map.class);
        assertThat(frame.get("object")).isEqualTo("chat.completion.chunk");
        Map<?, ?> delta = (Map<?, ?>) ((Map<?, ?>) ((List<?>) frame.get("choices")).get(0)).get("delta");
        assertThat(delta.get("role")).isEqualTo("assistant");
        assertThat(delta.get("content")).isNull();
    }

    @Test
    void splitsOnWordBoundariesNeverMidWord() {
        String text = "the quick brown fox jumps over the lazy dog and keeps running onwards";
        var pieces = OpenAiCompatController.split(text);
        assertThat(String.join("", pieces)).isEqualTo(text);
        // A word arriving in two frames visibly stutters in any client that
        // appends deltas as they land.
        for (int i = 0; i < pieces.size() - 1; i++) {
            assertThat(pieces.get(i)).endsWith(" ");
        }
    }

    @Test
    void splittingHandlesEmptyAndNull() {
        assertThat(OpenAiCompatController.split(null)).isEmpty();
        assertThat(OpenAiCompatController.split("")).isEmpty();
        assertThat(OpenAiCompatController.split("short")).containsExactly("short");
    }

    /* ---------------------------------------------------------------- *
     * The request actually reaching a provider
     * ---------------------------------------------------------------- */

    @Test
    void withModelKeepsToolsAndResponseFormat() {
        // The regression this guards: the failover loop rebuilt the request with
        // the four-argument constructor, so a tool-calling caller silently got
        // prose and no error anywhere said why.
        var req = new LlmRequest("a", List.of(Message.user("hi")), 100, 0.2,
                List.of(new ToolSpec("f", "d", Map.of())), "auto", new ResponseFormat("json_object", null));
        var retargeted = req.withModel("b");
        assertThat(retargeted.model()).isEqualTo("b");
        assertThat(retargeted.hasTools()).isTrue();
        assertThat(retargeted.responseFormat().isJson()).isTrue();
    }

    @Test
    void mockProviderCallsAToolWhenOneIsOffered() throws Exception {
        var provider = new MockProvider();
        var req = new LlmRequest("mock-1", List.of(Message.user("weather in Paris")), 100, 0.2,
                List.of(new ToolSpec("get_weather", "w",
                        Map.of("type", "object", "properties",
                                Map.of("city", Map.of("type", "string"), "days", Map.of("type", "integer"))))),
                "auto", null);
        LlmResponse resp = provider.complete(req);

        assertThat(resp.finishReason()).isEqualTo("tool_calls");
        assertThat(resp.toolCalls()).hasSize(1);
        assertThat(resp.toolCalls().get(0).name()).isEqualTo("get_weather");
        // Arguments must satisfy the caller's own schema, or their validator
        // rejects what the gateway just told them the model asked for.
        Map<String, Object> args = json.readValue(resp.toolCalls().get(0).argumentsJson(), Map.class);
        assertThat(args).containsKeys("city", "days");
        assertThat(args.get("days")).isInstanceOf(Integer.class);
    }

    @Test
    void mockProviderRespectsToolChoiceNone() throws Exception {
        var provider = new MockProvider();
        var req = new LlmRequest("mock-1", List.of(Message.user("x")), 100, 0.2,
                List.of(new ToolSpec("f", "d", Map.of())), "none", null);
        assertThat(provider.complete(req).toolCalls()).isEmpty();
    }

    @Test
    void mockProviderReturnsJsonWhenAskedFor() throws Exception {
        var provider = new MockProvider();
        var req = new LlmRequest("mock-1", List.of(Message.user("assess")), 100, 0.2,
                null, null, new ResponseFormat("json_object", null));
        String content = provider.complete(req).content();
        Map<String, Object> parsed = json.readValue(content, Map.class);
        assertThat(parsed).containsKey("assessment");
    }

    @Test
    void imagesSurviveTheWholeWayToTheProvider() throws Exception {
        var provider = new MockProvider();
        var withImage = new Message(Role.USER, "describe", null,
                List.of(new ImagePart("https://x/a.png", null)), null);
        var resp = provider.complete(new LlmRequest("mock-1", List.of(withImage), 100, 0.2));
        assertThat(resp.content()).contains("1 image");
    }

    private OpenAiDtos.ChatCompletionRequest parse(String body) throws Exception {
        return json.readValue(body, OpenAiDtos.ChatCompletionRequest.class);
    }
}
