package com.editora.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LmStudioAgentTest {
    @Test
    void configPinsMainAndSmallModelsAndKeepsTokenOutOfJson() throws Exception {
        var env = LmStudioAgent.environment("http://localhost:1234", " qwen/qwen3-coder-next ", " local-token ");
        String json = env.get("OPENCODE_CONFIG_CONTENT");
        var config = new ObjectMapper().readTree(json);
        assertEquals(
                "editora-lmstudio/qwen/qwen3-coder-next", config.path("model").asText());
        assertEquals(config.path("model"), config.path("small_model"));
        assertEquals("editora-lmstudio", config.path("enabled_providers").get(0).asText());
        assertEquals(1, config.path("enabled_providers").size());
        var provider = config.path("provider").path("editora-lmstudio");
        assertEquals("@ai-sdk/openai-compatible", provider.path("npm").asText());
        assertEquals(
                "http://localhost:1234/v1",
                provider.path("options").path("baseURL").asText());
        assertTrue(provider.path("models").has("qwen/qwen3-coder-next"));
        assertEquals(
                "{env:EDITORA_LMSTUDIO_API_KEY}",
                provider.path("options").path("apiKey").asText());
        assertEquals("local-token", env.get("EDITORA_LMSTUDIO_API_KEY"));
        assertFalse(json.contains("local-token"));
    }

    @Test
    void defaultsAndFullEndpointsWorkWithoutAuthentication() {
        var defaults = LmStudioAgent.environment("", "model", null);
        assertEquals(defaults, LmStudioAgent.environment("http://127.0.0.1:1234/v1", "model", ""));
        assertEquals(defaults, LmStudioAgent.environment("http://127.0.0.1:1234/v1/chat/completions", "model", ""));
        assertEquals("", defaults.get("EDITORA_LMSTUDIO_API_KEY"));
    }

    @Test
    void missingModelAndInvalidEndpointsFailBeforeLaunching() {
        assertThrows(IllegalArgumentException.class, () -> LmStudioAgent.environment("", " ", ""));
        for (String endpoint : new String[] {
            "bad url",
            "file:///tmp/v1/chat/completions",
            "http://host/v1/messages",
            "http://user:secret@host/v1/chat/completions",
            "http://host/v1/chat/completions?key=secret",
            "http://host/v1/chat/completions#fragment"
        }) {
            assertThrows(IllegalArgumentException.class, () -> LmStudioAgent.environment(endpoint, "model", ""));
        }
    }

    @Test
    void remoteCredentialsRequireHttpsButLoopbackAndKeylessLanWork() {
        assertThrows(
                IllegalArgumentException.class,
                () -> LmStudioAgent.environment("http://192.0.2.1:1234", "model", "token"));
        assertDoesNotThrow(() -> LmStudioAgent.environment("https://example.com/v1", "model", "token"));
        assertDoesNotThrow(() -> LmStudioAgent.environment("http://[::1]:1234", "model", "token"));
        assertDoesNotThrow(() -> LmStudioAgent.environment("http://192.0.2.1:1234", "model", ""));
    }
}
