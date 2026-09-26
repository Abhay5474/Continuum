package io.continuum.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** LLM provider configuration, bound from {@code continuum.llm.*}. */
@ConfigurationProperties(prefix = "continuum.llm")
public class LlmProperties {

    /** Ordered failover chain by provider name, e.g. [gemini, groq, mock]. */
    private List<String> failoverOrder = List.of("gemini", "groq", "mock");

    // Last-resort names, used only when nothing is configured and the catalogue
    // has no usable model yet. The catalogue decides the real default.
    private final Provider gemini = new Provider("gemini-3.5-flash", "https://generativelanguage.googleapis.com");
    private final Provider groq = new Provider("openai/gpt-oss-120b", "https://api.groq.com/openai/v1");

    public List<String> getFailoverOrder() {
        return failoverOrder;
    }

    public void setFailoverOrder(List<String> failoverOrder) {
        this.failoverOrder = failoverOrder;
    }

    public Provider getGemini() {
        return gemini;
    }

    public Provider getGroq() {
        return groq;
    }

    public static class Provider {
        private String apiKey = "";
        /**
         * A preferred model, not a requirement. The catalogue uses it while the
         * provider lists it and it answers; when the provider retires it, requests
         * move to the catalogue's replacement instead of failing. Blank: let the
         * catalogue choose.
         */
        private String model;
        private String baseUrl;
        /**
         * The key is on the provider's free tier, so a model that answers it is
         * free to use. Declared, because no provider API says which tier a key is on.
         */
        private boolean freeTier = true;

        public boolean isFreeTier() {
            return freeTier;
        }

        public void setFreeTier(boolean freeTier) {
            this.freeTier = freeTier;
        }

        public Provider(String model, String baseUrl) {
            this.model = model;
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }
}
