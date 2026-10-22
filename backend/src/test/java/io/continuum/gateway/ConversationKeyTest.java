package io.continuum.gateway;

import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationKeyTest {

    @Test
    void aSingleQuestionIsKeyedAndVerifiedAsItself() {
        LlmRequest one = LlmRequest.of(List.of(Message.user("what is a heron?")));
        assertThat(GatewaySupport.cacheKeyOf(one)).isEqualTo("what is a heron?");
        assertThat(GatewaySupport.conversationPrompt(one)).isEqualTo("what is a heron?");
    }

    @Test
    void theSameFollowUpInTwoConversationsIsTwoDifferentKeys() {
        LlmRequest fish = LlmRequest.of(List.of(
                Message.user("I found an injured fish"), Message.assistant("Keep it in water."),
                Message.user("and what if it is a bird?")));
        LlmRequest cat = LlmRequest.of(List.of(
                Message.user("I found an injured cat"), Message.assistant("Keep it warm."),
                Message.user("and what if it is a bird?")));
        assertThat(GatewaySupport.cacheKeyOf(fish)).isNotEqualTo(GatewaySupport.cacheKeyOf(cat));
    }

    @Test
    void theKeySeparatesTheConversationSoFarFromTheNewQuestion() {
        LlmRequest req = LlmRequest.of(List.of(
                Message.system("Answer as a vet."),
                Message.user("I found an injured fish"), Message.assistant("Keep it in water."),
                Message.user("and a bird?")));
        assertThat(GatewaySupport.cacheKeyOf(req)).isEqualTo(
                "system: Answer as a vet.\nuser: I found an injured fish\nassistant: Keep it in water."
                        + io.continuum.cache.SemanticCacheService.QUESTION + "and a bird?");
    }

    @Test
    void aRoutingModeFilesTheAnswerApartAndAMeasuredRequestIsNotCached() {
        LlmRequest canonical = LlmRequest.of(List.of(Message.system("Brightcart policy."), Message.user("refunds?")));
        String plain = GatewaySupport.cacheKeyOf(
                new GatewayDtos.ChatRequest("auto", List.of(), null, null, null, null, null, null, null), canonical);
        String careful = GatewaySupport.cacheKeyOf(
                new GatewayDtos.ChatRequest("auto", List.of(), null, null, "high_quality", null, null, null, null), canonical);
        assertThat(careful).isNotEqualTo(plain).startsWith("routing: HIGH_QUALITY\n")
                .endsWith(io.continuum.cache.SemanticCacheService.QUESTION + "refunds?");
        assertThat(GatewaySupport.cacheKeyOf(
                new GatewayDtos.ChatRequest("auto", List.of(), null, null, null, null, true, null, null), canonical)).isNull();
    }

    @Test
    void theVerificationPromptCarriesTheConversationAndTheSystemPrompt() {
        LlmRequest req = LlmRequest.of(List.of(
                Message.system("Answer as a vet."),
                Message.user("I found an injured fish"), Message.assistant("Keep it in water."),
                Message.user("and a bird?")));
        String prompt = GatewaySupport.conversationPrompt(req);
        assertThat(prompt).contains("system: Answer as a vet.", "user: I found an injured fish",
                "assistant: Keep it in water.", "user: and a bird?");
    }

    @Test
    void aRequestWithToolsIsNeverAnsweredFromTheCache() {
        LlmRequest base = LlmRequest.of(List.of(Message.user("weather in Pune?")));
        LlmRequest withTools = new LlmRequest(null, base.messages(), 100, 0.2,
                List.of(new io.continuum.provider.model.ToolSpec("weather", "look it up", java.util.Map.of())),
                null, null);
        assertThat(GatewaySupport.cacheKeyOf(withTools)).isNull();
    }
}
