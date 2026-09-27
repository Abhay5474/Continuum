package io.continuum.firewall;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What a person types when they share something they should not. */
class SecretDetectionTest {

    private final PromptFirewall fw = new PromptFirewall();

    private String in(String text) {
        return fw.scanInbound(text, true, false).sanitized();
    }

    @Test
    void aPinPasswordCvvOrCodeIsRedactedButTheSentenceKept() {
        assertThat(in("my debit card pin is 7849")).isEqualTo("my debit card pin is [REDACTED_CREDENTIAL]");
        assertThat(in("password: hunter2!")).isEqualTo("password: [REDACTED_CREDENTIAL]");
        assertThat(in("the CVV 123 on the back")).contains("[REDACTED_CREDENTIAL]").doesNotContain("123");
        assertThat(in("OTP is 482913")).doesNotContain("482913");
    }

    @Test
    void aNumberCalledACardOrAccountNumberIsRedactedEvenIfItFailsTheChecksum() {
        assertThat(in("my credit card number is 8349573945734"))
                .isEqualTo("my credit card number is [REDACTED_CARD_NUMBER]");
        assertThat(in("account no. 1234 5678 9012")).doesNotContain("5678");
    }

    @Test
    void aValidCardNumberWithNoContextIsStillCaught() {
        assertThat(in("use 4111 1111 1111 1111 please")).doesNotContain("4111 1111");
    }

    @Test
    void ordinaryTalkAboutCardsAndPinsIsLeftAlone() {
        String[] fine = {
                "my card was declined at the shop",
                "what is a pin code used for?",
                "I found 3 injured birds and 2 fish",
                "if i found a fish injured what measures to take"};
        for (String s : fine) {
            assertThat(in(s)).as(s).isEqualTo(s);
        }
    }

    @Test
    void anAnswerThatRepeatsAPinBackIsRedactedOnTheWayOut() {
        assertThat(fw.scanOutbound("Noted: your pin is 7849.", true).sanitized()).doesNotContain("7849");
    }
}
