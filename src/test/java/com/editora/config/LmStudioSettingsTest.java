package com.editora.config;

import com.editora.ai.AiProvider;
import com.editora.config.migration.ConfigSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LmStudioSettingsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void localSettingsRoundTripWithoutOverwritingExistingProviderConfiguration() throws Exception {
        Settings settings = new Settings();
        settings.setAiModel("cloud-model");
        settings.setAiCompletionModel("cloud-small");
        settings.setAiEndpoint("https://cloud.example/v1/messages");
        settings.setAiApiKey("cloud-secret");
        settings.setAiApiKeyOpenai("hosted-secret");
        settings.setAiProvider("lmstudio");
        settings.setAiModelFor(AiProvider.LMSTUDIO, "local-model");
        settings.setAiCompletionModelFor(AiProvider.LMSTUDIO, "local-small");
        settings.setAiEndpointFor(AiProvider.LMSTUDIO, "http://localhost:1234");
        settings.setApiKeyFor(AiProvider.LMSTUDIO, "local-token");
        settings.setLmstudioAgentCommand("\"/path with spaces/opencode\" acp");
        settings = mapper.readValue(mapper.writeValueAsString(settings), Settings.class);
        assertEquals("lmstudio", settings.getAiProvider());
        assertEquals("local-model", settings.getAiModelFor(AiProvider.LMSTUDIO));
        assertEquals("local-small", settings.getAiCompletionModelFor(AiProvider.LMSTUDIO));
        assertEquals("http://localhost:1234", settings.getAiEndpointFor(AiProvider.LMSTUDIO));
        assertEquals("local-token", settings.getApiKeyFor(AiProvider.LMSTUDIO));
        assertEquals("\"/path with spaces/opencode\" acp", settings.getLmstudioAgentCommand());
        assertEquals("cloud-model", settings.getAiModelFor(AiProvider.ANTHROPIC));
        assertEquals("cloud-small", settings.getAiCompletionModelFor(AiProvider.ANTHROPIC));
        assertEquals("https://cloud.example/v1/messages", settings.getAiEndpointFor(AiProvider.ANTHROPIC));
        assertEquals("cloud-secret", settings.getApiKeyFor(AiProvider.ANTHROPIC));
        assertEquals("hosted-secret", settings.getApiKeyFor(AiProvider.OPENAI));
    }

    @Test
    void migrationPreservesOldChoicesAndDefaultsLocalFieldsToEmpty() throws Exception {
        var old = mapper.readTree("""
                {"schemaVersion":103,"aiProvider":"openai","aiModel":"existing-model",
                 "aiEndpoint":"http://localhost:9999/v1/chat/completions", "aiApiKeyOpenai":"existing-key",
                 "agentClient":"opencode","opencodeAgentCommand":"my-opencode acp"}
                """);
        var step = ConfigSchema.SETTINGS.step(103);
        assertNotNull(step);
        var migrated = step.apply(old);
        assertEquals(old, migrated);
        Settings settings = mapper.treeToValue(migrated, Settings.class);
        assertEquals("openai", settings.getAiProvider());
        assertEquals("existing-model", settings.getAiModel());
        assertEquals("existing-key", settings.getAiApiKeyOpenai());
        assertEquals("opencode", settings.getAgentClient());
        assertEquals("my-opencode acp", settings.getOpencodeAgentCommand());
        assertEquals("", settings.getAiLmStudioModel());
        assertEquals("", settings.getAiLmStudioEndpoint());
        assertEquals("", settings.getAiApiKeyLmstudio());
        assertEquals("", settings.getLmstudioAgentCommand());
    }
}
