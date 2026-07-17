package com.editora.ui;

import com.editora.ai.AiProvider;
import com.editora.config.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which key the AI feature actually sends. The {@code ANTHROPIC_API_KEY} environment variable is Anthropic's
 * credential by name, and it is exported on most developer machines — so it must never be handed to the
 * OpenAI-compatible provider, whose endpoint is user-supplied (LM Studio, a LAN proxy, any host) and whose
 * {@link AiProvider#requiresApiKey()} is false precisely because a local server needs no key.
 */
class AiKeyScopeTest {

    private static final String ENV = "sk-ant-api03-THE-USERS-REAL-BILLABLE-KEY";

    @Test
    void theAnthropicEnvironmentKeyIsNeverSentToTheOpenAiCompatibleProvider() {
        // The user typed nothing into Settings (the key field is empty on screen) and picked a local model.
        // Sending the environment's Anthropic key here put it in a bearer token bound for a user-supplied
        // endpoint — and inline completion fires it on every idle pause, unprompted.
        assertEquals("", AiCoordinator.effectiveKey("", AiProvider.OPENAI, ENV));
        assertEquals("", AiCoordinator.effectiveKey(null, AiProvider.OPENAI, ENV));
        assertEquals("", AiCoordinator.effectiveKey("   ", AiProvider.OPENAI, ENV));
    }

    @Test
    void theAnthropicEnvironmentKeyStillBacksTheAnthropicProvider() {
        assertEquals(ENV, AiCoordinator.effectiveKey("", AiProvider.ANTHROPIC, ENV));
        assertEquals(ENV, AiCoordinator.effectiveKey(null, AiProvider.ANTHROPIC, "  " + ENV + "  "));
    }

    @Test
    void anExplicitlyConfiguredKeyAlwaysWins() {
        assertEquals("sk-typed", AiCoordinator.effectiveKey("sk-typed", AiProvider.OPENAI, ENV));
        assertEquals("sk-typed", AiCoordinator.effectiveKey("  sk-typed  ", AiProvider.ANTHROPIC, ENV));
    }

    @Test
    void noKeyAnywhereIsEmptyNotNull() {
        assertEquals("", AiCoordinator.effectiveKey(null, AiProvider.ANTHROPIC, null));
        assertEquals("", AiCoordinator.effectiveKey("", AiProvider.OPENAI, null));
    }

    // --- per-provider key scoping (#480) ------------------------------------------------------------

    @Test
    void aConfiguredKeyIsScopedToItsProviderAndNeverLeaksToTheOther() {
        Settings s = new Settings();
        s.setAiApiKey("sk-ant-REAL"); // the Anthropic key
        // The #480 leak: switching to the OpenAI provider used to resend this key to that endpoint.
        assertEquals("", s.getApiKeyFor(AiProvider.OPENAI), "Anthropic key must not surface for OPENAI");
        assertEquals("sk-ant-REAL", s.getApiKeyFor(AiProvider.ANTHROPIC));

        // A key configured for the OpenAI-compatible host stays out of Anthropic requests, and vice versa.
        s.setApiKeyFor(AiProvider.OPENAI, "sk-openrouter");
        assertEquals("sk-openrouter", s.getApiKeyFor(AiProvider.OPENAI));
        assertEquals("sk-ant-REAL", s.getApiKeyFor(AiProvider.ANTHROPIC));

        // setApiKeyFor routes to the right backing field.
        s.setApiKeyFor(AiProvider.ANTHROPIC, "sk-ant-2");
        assertEquals("sk-ant-2", s.getAiApiKey());
        assertEquals("sk-openrouter", s.getAiApiKeyOpenai());
    }

    @Test
    void aNullProviderIsTreatedAsAnthropic() {
        Settings s = new Settings();
        s.setApiKeyFor(null, "sk-ant");
        assertEquals("sk-ant", s.getAiApiKey());
        assertEquals("sk-ant", s.getApiKeyFor(null));
    }
}
