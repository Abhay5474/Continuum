package io.continuum.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.continuum.chaos.ChaosMonkey;
import io.continuum.config.LlmProperties;
import io.continuum.gateway.FinishReason;
import io.continuum.provider.gemini.GeminiProvider;
import io.continuum.provider.groq.GroqProvider;
import io.continuum.provider.model.ImagePart;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.LlmResponse;
import io.continuum.provider.model.Message;
import io.continuum.provider.model.ResponseFormat;
import io.continuum.provider.model.Role;
import io.continuum.provider.model.ToolCall;
import io.continuum.provider.model.ToolSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the real LLM adapters put on the wire, and what they make of the answer.
 *
 * <p>Tool calling, JSON mode and images were verified end to end against the
 * mock provider, which is the one provider that implements them by
 * construction. Gemini and Groq dropped all three. These tests pin the
 * translation for each real provider against its documented request shape, and
 * capture the actual HTTP request rather than inspecting an intermediate object.
 */
class ProviderWireTest {

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** A local endpoint that records one request and answers with a fixed body. */
    private String serve(String responseBody, AtomicReference<String> body, AtomicReference<String> auth)
            throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(ex.getRequestHeaders().getFirst("Authorization") != null
                    ? ex.getRequestHeaders().getFirst("Authorization")
                    : ex.getRequestHeaders().getFirst("x-goog-api-key"));
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static final ToolSpec WEATHER = new ToolSpec("get_weather", "Current weather for a city",
            Map.of("type", "object",
                    "properties", Map.of("city", Map.of("type", "string")),
                    "required", List.of("city"),
                    "additionalProperties", false));

    private static LlmRequest toolRequest(String choice) {
        return new LlmRequest("m", List.of(Message.user("Weather in Paris?")), 100, 0.2,
                List.of(WEATHER), choice, null);
    }

    // --- Groq: OpenAI's wire format ------------------------------------------

    @Nested
    class Groq {

        private GroqProvider provider(String baseUrl) {
            LlmProperties props = new LlmProperties();
            props.getGroq().setApiKey("gsk_test");
            props.getGroq().setModel("llama");
            props.getGroq().setBaseUrl(baseUrl);
            return new GroqProvider(props, json);
        }

        @Test
        @DisplayName("tools, tool_choice and response_format reach the provider")
        void passesToolsThrough() throws Exception {
            var body = new AtomicReference<String>();
            var auth = new AtomicReference<String>();
            String url = serve("{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}",
                    body, auth);

            provider(url).complete(new LlmRequest("m", List.of(Message.user("hi")), 50, null,
                    List.of(WEATHER), "get_weather", new ResponseFormat("json_object", null)));

            JsonNode sent = json.readTree(body.get());
            assertThat(sent.at("/tools/0/type").asText()).isEqualTo("function");
            assertThat(sent.at("/tools/0/function/name").asText()).isEqualTo("get_weather");
            assertThat(sent.at("/tools/0/function/parameters/required/0").asText()).isEqualTo("city");
            // A named function becomes OpenAI's object form, not a bare string.
            assertThat(sent.at("/tool_choice/function/name").asText()).isEqualTo("get_weather");
            assertThat(sent.at("/response_format/type").asText()).isEqualTo("json_object");
            assertThat(auth.get()).isEqualTo("Bearer gsk_test");
        }

        @Test
        @DisplayName("the model's tool calls come back as tool calls, not as empty prose")
        void parsesToolCalls() throws Exception {
            String url = serve("""
                    {"choices":[{"message":{"content":null,"tool_calls":[{"id":"call_9","type":"function",
                      "function":{"name":"get_weather","arguments":"{\\"city\\":\\"Paris\\"}"}}]},
                      "finish_reason":"tool_calls"}],"usage":{"prompt_tokens":20,"completion_tokens":7}}
                    """, new AtomicReference<>(), new AtomicReference<>());

            LlmResponse r = provider(url).complete(toolRequest("auto"));

            assertThat(r.toolCalls()).singleElement().satisfies(c -> {
                assertThat(c.id()).isEqualTo("call_9");
                assertThat(c.name()).isEqualTo("get_weather");
                assertThat(c.argumentsJson()).isEqualTo("{\"city\":\"Paris\"}");
            });
            assertThat(r.finishReason()).isEqualTo("tool_calls");
        }

        @Test
        @DisplayName("a multi-turn tool conversation keeps its call ids, so the provider accepts it")
        void keepsToolTurnShape() throws Exception {
            var body = new AtomicReference<String>();
            String url = serve("{\"choices\":[{\"message\":{\"content\":\"18C\"}}]}", body, new AtomicReference<>());

            provider(url).complete(new LlmRequest("m", List.of(
                    Message.user("Weather in Paris?"),
                    new Message(Role.ASSISTANT, null,
                            List.of(new ToolCall("call_9", "get_weather", "{\"city\":\"Paris\"}"))),
                    new Message(Role.TOOL, "18C and clear", null, null, "call_9")),
                    50, null, List.of(WEATHER), "auto", null));

            JsonNode msgs = json.readTree(body.get()).path("messages");
            assertThat(msgs.at("/1/content").isNull()).isTrue();
            assertThat(msgs.at("/1/tool_calls/0/id").asText()).isEqualTo("call_9");
            assertThat(msgs.at("/2/role").asText()).isEqualTo("tool");
            assertThat(msgs.at("/2/tool_call_id").asText()).isEqualTo("call_9");
        }
    }

    // --- Gemini: a translation, not a pass-through -----------------------------

    @Nested
    class Gemini {

        private final GeminiProvider gemini = provider("http://unused");

        private GeminiProvider provider(String baseUrl) {
            LlmProperties props = new LlmProperties();
            props.getGemini().setApiKey("AIza_test");
            props.getGemini().setModel("gemini-test");
            props.getGemini().setBaseUrl(baseUrl);
            return new GeminiProvider(props, json, new ChaosMonkey());
        }

        private JsonNode body(LlmRequest r) throws Exception {
            // Round-trip through the real HTTP path so the assertion is on what
            // Gemini would actually receive.
            var body = new AtomicReference<String>();
            String url = serve("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}",
                    body, new AtomicReference<>());
            provider(url).complete(r);
            return json.readTree(body.get());
        }

        @Test
        @DisplayName("tools become function declarations, minus the schema keywords Gemini rejects")
        void declaresFunctions() throws Exception {
            JsonNode sent = body(toolRequest("auto"));

            JsonNode decl = sent.at("/tools/0/functionDeclarations/0");
            assertThat(decl.path("name").asText()).isEqualTo("get_weather");
            assertThat(decl.at("/parameters/properties/city/type").asText()).isEqualTo("string");
            // additionalProperties:false is required by OpenAI strict mode and
            // rejected by Gemini's schema subset. Left in, it fails the call.
            assertThat(decl.path("parameters").has("additionalProperties")).isFalse();
            // auto is Gemini's default, so no toolConfig is sent for it.
            assertThat(sent.has("toolConfig")).isFalse();
        }

        @Test
        @DisplayName("tool_choice maps onto Gemini's function-calling modes")
        void mapsToolChoice() throws Exception {
            assertThat(body(toolRequest("none")).at("/toolConfig/functionCallingConfig/mode").asText())
                    .isEqualTo("NONE");
            assertThat(body(toolRequest("required")).at("/toolConfig/functionCallingConfig/mode").asText())
                    .isEqualTo("ANY");
            JsonNode named = body(toolRequest("get_weather")).at("/toolConfig/functionCallingConfig");
            assertThat(named.path("mode").asText()).isEqualTo("ANY");
            assertThat(named.at("/allowedFunctionNames/0").asText()).isEqualTo("get_weather");
        }

        @Test
        @DisplayName("a tool result is matched back to its function by name, which is how Gemini pairs them")
        void toolResultCarriesName() throws Exception {
            JsonNode sent = body(new LlmRequest("m", List.of(
                    Message.user("Weather in Paris?"),
                    new Message(Role.ASSISTANT, null,
                            List.of(new ToolCall("call_9", "get_weather", "{\"city\":\"Paris\"}"))),
                    new Message(Role.TOOL, "18C and clear", null, null, "call_9")),
                    50, null, List.of(WEATHER), "auto", null));

            JsonNode contents = sent.path("contents");
            assertThat(contents.at("/1/role").asText()).isEqualTo("model");
            assertThat(contents.at("/1/parts/0/functionCall/name").asText()).isEqualTo("get_weather");
            // Arguments are an object for Gemini, not the JSON string OpenAI uses.
            assertThat(contents.at("/1/parts/0/functionCall/args/city").asText()).isEqualTo("Paris");
            assertThat(contents.at("/2/parts/0/functionResponse/name").asText()).isEqualTo("get_weather");
            assertThat(contents.at("/2/parts/0/functionResponse/response/result").asText())
                    .isEqualTo("18C and clear");
        }

        @Test
        @DisplayName("JSON mode sets the response MIME type, with the caller's schema")
        void jsonMode() throws Exception {
            JsonNode sent = body(new LlmRequest("m", List.of(Message.user("x")), 50, null, null, null,
                    new ResponseFormat("json_schema", Map.of("name", "out", "strict", true,
                            "schema", Map.of("type", "object", "additionalProperties", false,
                                    "properties", Map.of("ok", Map.of("type", "boolean")))))));

            assertThat(sent.at("/generationConfig/responseMimeType").asText()).isEqualTo("application/json");
            assertThat(sent.at("/generationConfig/responseSchema/properties/ok/type").asText()).isEqualTo("boolean");
            assertThat(sent.at("/generationConfig/responseSchema").has("additionalProperties")).isFalse();
        }

        @Test
        @DisplayName("an inline image becomes inline data; a remote one is refused, not fetched")
        void images() throws Exception {
            JsonNode sent = body(new LlmRequest("m", List.of(new Message(Role.USER, "What is this?", null,
                    List.of(new ImagePart("data:image/jpeg;base64,/9j/AAAA", null)), null)), 50, null));
            assertThat(sent.at("/contents/0/parts/1/inlineData/mimeType").asText()).isEqualTo("image/jpeg");
            assertThat(sent.at("/contents/0/parts/1/inlineData/data").asText()).isEqualTo("/9j/AAAA");

            assertThatThrownBy(() -> gemini.complete(new LlmRequest("m", List.of(new Message(Role.USER, "x", null,
                    List.of(new ImagePart("https://example.com/cat.png", null)), null)), 50, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("data: URL");
        }

        @Test
        @DisplayName("the key travels in a header, never in the URL")
        void keyInHeader() throws Exception {
            var auth = new AtomicReference<String>();
            var body = new AtomicReference<String>();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var seenQuery = new AtomicReference<String>();
            server.createContext("/", ex -> {
                seenQuery.set(ex.getRequestURI().getRawQuery());
                auth.set(ex.getRequestHeaders().getFirst("x-goog-api-key"));
                byte[] out = "{\"candidates\":[]}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
                ex.close();
            });
            server.start();
            provider("http://127.0.0.1:" + server.getAddress().getPort())
                    .complete(new LlmRequest("m", List.of(Message.user("x")), 10, null));

            assertThat(auth.get()).isEqualTo("AIza_test");
            assertThat(seenQuery.get()).isNull();
        }

        @Test
        @DisplayName("function calls, split text and thought parts are read correctly")
        void parsesResponse() throws Exception {
            LlmResponse r = gemini.parse(json.readTree("""
                    {"candidates":[{"finishReason":"STOP","content":{"parts":[
                      {"text":"weighing the options","thought":true},
                      {"text":"Checking "},
                      {"text":"the weather."},
                      {"functionCall":{"name":"get_weather","args":{"city":"Paris"}}}]}}],
                     "usageMetadata":{"promptTokenCount":12,"candidatesTokenCount":9}}
                    """), toolRequest("auto"), "gemini-test");

            // Every text part, and not the model's reasoning.
            assertThat(r.content()).isEqualTo("Checking the weather.");
            assertThat(r.toolCalls()).singleElement().satisfies(c -> {
                assertThat(c.name()).isEqualTo("get_weather");
                assertThat(json.readTree(c.argumentsJson()).path("city").asText()).isEqualTo("Paris");
                assertThat(c.id()).isNotBlank();
            });
            // Gemini says STOP on a function call; the gateway must say tool_calls.
            assertThat(FinishReason.normalize(r.finishReason(), !r.toolCalls().isEmpty()))
                    .isEqualTo("tool_calls");
        }

        @Test
        @DisplayName("a blocked prompt is reported as filtered, not as an empty success")
        void blockedPrompt() throws Exception {
            LlmResponse r = gemini.parse(json.readTree(
                    "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}"), toolRequest("auto"), "g");

            assertThat(r.content()).isEmpty();
            assertThat(FinishReason.normalize(r.finishReason(), false)).isEqualTo("content_filter");
        }
    }

    // --- finish reasons --------------------------------------------------------

    @Test
    @DisplayName("every provider's stop vocabulary maps onto OpenAI's four values")
    void finishReasons() {
        assertThat(FinishReason.normalize("MAX_TOKENS", false)).isEqualTo("length");
        assertThat(FinishReason.normalize("length", false)).isEqualTo("length");
        assertThat(FinishReason.normalize("max_tokens", false)).isEqualTo("length");
        assertThat(FinishReason.normalize("SAFETY", false)).isEqualTo("content_filter");
        assertThat(FinishReason.normalize("RECITATION", false)).isEqualTo("content_filter");
        assertThat(FinishReason.normalize("STOP", false)).isEqualTo("stop");
        assertThat(FinishReason.normalize("end_turn", false)).isEqualTo("stop");
        assertThat(FinishReason.normalize(null, false)).isEqualTo("stop");
        assertThat(FinishReason.normalize("SOMETHING_NEW", false)).isEqualTo("stop");
        assertThat(FinishReason.normalize("STOP", true)).isEqualTo("tool_calls");
    }
}
