package io.continuum.registry.catalog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultModelPickerTest {

    private static DefaultModelPicker.Candidate c(String id) {
        return new DefaultModelPicker.Candidate(id, id.contains("preview"), 128_000, 0);
    }

    private static final List<DefaultModelPicker.Candidate> GROQ = List.of(
            c("openai/gpt-oss-20b"), c("openai/gpt-oss-120b"), c("qwen/qwen3.6-27b-preview"));

    @Test
    void theStrongestStableModelWhenNothingElseDecides() {
        assertThat(DefaultModelPicker.pick(GROQ, null, null, null, "llama-3.3-70b-versatile").orElseThrow().model())
                .isEqualTo("openai/gpt-oss-120b");
    }

    @Test
    void pinThenConfiguredThenCurrent() {
        assertThat(DefaultModelPicker.pick(GROQ, "openai/gpt-oss-20b", "openai/gpt-oss-120b", null, null)
                .orElseThrow().model()).isEqualTo("openai/gpt-oss-20b");
        assertThat(DefaultModelPicker.pick(GROQ, "gone", "openai/gpt-oss-20b", null, null)
                .orElseThrow().model()).isEqualTo("openai/gpt-oss-20b");
        assertThat(DefaultModelPicker.pick(GROQ, null, null, "qwen/qwen3.6-27b-preview", null)
                .orElseThrow().model()).as("a working default is not changed for a newer model").isEqualTo("qwen/qwen3.6-27b-preview");
    }

    @Test
    void sameFamilyFirst() {
        List<DefaultModelPicker.Candidate> gemini = List.of(c("gemini-3.5-flash-lite"), c("gemini-3.8-flash"),
                c("gemini-3.7-flash"), c("gemini-3.9-pro-preview"));
        assertThat(DefaultModelPicker.replacementFor("gemini-3.5-flash", gemini, null)).contains("gemini-3.8-flash");
        assertThat(DefaultModelPicker.replacementFor("gemini-3.1-flash-lite", gemini, "gemini-3.7-flash")).contains("gemini-3.5-flash-lite");
    }

    @Test
    void aRetiredModelGoesWhereTheDefaultGoesWhenThatIsItsFamilyOrItHasNone() {
        List<DefaultModelPicker.Candidate> gemini = List.of(c("gemini-3.5-flash"), c("gemini-3.7-flash"), c("gemini-3.5-flash-lite"));
        // Same family as the default: the default, not the newest of the family.
        assertThat(DefaultModelPicker.replacementFor("gemini-2.5-flash", gemini, "gemini-3.5-flash")).contains("gemini-3.5-flash");
        // No family left at all: the default, not whichever model scores highest.
        assertThat(DefaultModelPicker.replacementFor("llama-3.3-70b-versatile", GROQ, "openai/gpt-oss-20b")).contains("openai/gpt-oss-20b");
    }

    @Test
    void namesReadAsFamiliesAndStrengths() {
        assertThat(DefaultModelPicker.family("gemini-3.5-flash")).isEqualTo(DefaultModelPicker.family("gemini-3.8-flash"));
        assertThat(DefaultModelPicker.family("gemini-3.5-flash")).isNotEqualTo(DefaultModelPicker.family("gemini-3.5-flash-lite"));
        assertThat(DefaultModelPicker.strength("openai/gpt-oss-120b")).isEqualTo(3);
        assertThat(DefaultModelPicker.strength("openai/gpt-oss-20b")).isEqualTo(1);
        assertThat(DefaultModelPicker.strength("gemini-3.5-flash-lite")).isEqualTo(1);
        assertThat(DefaultModelPicker.version("gemini-3.8-flash")).isGreaterThan(DefaultModelPicker.version("gemini-3.5-flash"));
        assertThat(DefaultModelPicker.version("openai/gpt-oss-120b")).isZero();
    }

    @Test
    void nothingUsableIsNoChoice() {
        assertThat(DefaultModelPicker.pick(List.of(), null, "x", "y", null)).isEmpty();
    }
}
