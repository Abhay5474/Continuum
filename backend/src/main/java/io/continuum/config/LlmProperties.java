package io.continuum.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** LLM provider configuration, bound from {@code continuum.llm.*}. */
@ConfigurationProperties(prefix = "continuum.llm")
public class LlmProperties {

    /** Ordered failover chain by provider name, e.g. [gemini, groq, mock]. */
    private List<String> failoverOrder = List.of("gemini", "groq", "mock");

    private final Provider gemini = new Provider("gemini-3.5-flash", "https://generativelanguage.googleapis.com");
    private final Provider groq = new Provider("llama-3.3-70b-versatile", "https://api.groq.com/openai/v1");

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
        private String model;
        private String baseUrl;

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
