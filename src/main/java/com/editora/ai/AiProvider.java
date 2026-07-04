package com.editora.ai;

import java.util.Locale;

/**
 * Which wire dialect the AI features speak: Anthropic's Messages API, or the OpenAI-compatible
 * chat-completions API that local inference servers expose (LM Studio, Ollama, vLLM, llama.cpp).
 * Pure (java.base only) and unit-tested; persisted in {@code Settings.aiProvider} by {@link #id()}.
 */
public enum AiProvider {
    /** api.anthropic.com — needs an API key. */
    ANTHROPIC("https://api.anthropic.com/v1/messages"),
    /** An OpenAI-compatible server; the default endpoint is LM Studio's local server. */
    OPENAI("http://127.0.0.1:1234/v1/chat/completions");

    private final String defaultEndpoint;

    AiProvider(String defaultEndpoint) {
        this.defaultEndpoint = defaultEndpoint;
    }

    /** The endpoint used when {@code Settings.aiEndpoint} is blank. */
    public String defaultEndpoint() {
        return defaultEndpoint;
    }

    /** Local OpenAI-compatible servers need no key; Anthropic always does. */
    public boolean requiresApiKey() {
        return this == ANTHROPIC;
    }

    /** The persisted id ({@code "anthropic"}/{@code "openai"}). */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses a persisted id; blank/unknown → {@link #ANTHROPIC} (the pre-provider default). */
    public static AiProvider from(String id) {
        return id != null && id.strip().equalsIgnoreCase("openai") ? OPENAI : ANTHROPIC;
    }
}
