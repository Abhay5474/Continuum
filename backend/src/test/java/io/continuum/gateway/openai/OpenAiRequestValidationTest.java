package io.continuum.gateway.openai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Requests OpenAI would refuse are refused here too, with the same param named. */
class OpenAiRequestValidationTest {

    private static OpenAiDtos.ChatCompletionRequest req(List<OpenAiDtos.ChatMessage> messages, Integer maxTokens,
                                                        Double temperature, Double topP) {
        return new OpenAiDtos.ChatCompletionRequest("auto", messages, maxTokens, null, temperature, topP,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static List<OpenAiDtos.ChatMessage> say(String role) {
        return List.of(new OpenAiDtos.ChatMessage(role, "hi", null, null, null));
    }

    @Test
    void ordinaryRequestPasses() {
        assertNull(OpenAiCompatController.validate(req(say("user"), 100, 0.7, 1.0)));
        assertNull(OpenAiCompatController.validate(req(say("developer"), null, 0.0, 0.0)));
    }

    @Test
    void outOfRangeParametersNameTheirParam() {
        assertEquals("max_tokens", OpenAiCompatController.validate(req(say("user"), -5, null, null)).param());
        assertEquals("temperature", OpenAiCompatController.validate(req(say("user"), null, 9.0, null)).param());
        assertEquals("top_p", OpenAiCompatController.validate(req(say("user"), null, null, 1.5)).param());
    }

    @Test
    void unknownOrMissingRoleIsRefused() {
        assertEquals("messages[0].role", OpenAiCompatController.validate(req(say("alien"), null, null, null)).param());
        assertEquals("messages[0].role", OpenAiCompatController.validate(req(say(null), null, null, null)).param());
    }
}
