package com.editora.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link OpenAiSse} + {@link AiProvider}: OpenAI-compatible streaming readers and provider defaults. */
class OpenAiSseTest {

    private final ObjectMapper m = new ObjectMapper();

    private JsonNode json(String s) throws Exception {
        return m.readTree(s);
    }

    @Test
    void detectsDoneSentinel() {
        assertTrue(OpenAiSse.isDone("[DONE]"));
        assertTrue(OpenAiSse.isDone("  [DONE] "));
        assertFalse(OpenAiSse.isDone("{\"x\":1}"));
        assertFalse(OpenAiSse.isDone(null));
    }

    @Test
    void readsTextDelta() throws Exception {
        JsonNode chunk = json("{\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}");
        assertEquals("Hello", OpenAiSse.textDelta(chunk));
        assertNull(OpenAiSse.textDelta(json("{\"choices\":[{\"delta\":{}}]}")));
        assertNull(OpenAiSse.textDelta(json("{}")));
    }

    @Test
    void mapsFinishReasonToAnthropicStops() throws Exception {
        assertEquals("end_turn", OpenAiSse.finishReason(json("{\"choices\":[{\"finish_reason\":\"stop\"}]}")));
        assertEquals("max_tokens", OpenAiSse.finishReason(json("{\"choices\":[{\"finish_reason\":\"length\"}]}")));
        assertNull(OpenAiSse.finishReason(json("{\"choices\":[{\"finish_reason\":null}]}")));
    }

    @Test
    void readsErrorPayload() throws Exception {
        assertEquals("bad model", OpenAiSse.errorMessage(json("{\"error\":{\"message\":\"bad model\"}}")));
        assertNull(OpenAiSse.errorMessage(json("{\"choices\":[]}")));
    }

    @Test
    void providerDefaultsAndKeyRequirement() {
        assertEquals(AiProvider.ANTHROPIC, AiProvider.from(""));
        assertEquals(AiProvider.ANTHROPIC, AiProvider.from("unknown"));
        assertEquals(AiProvider.OPENAI, AiProvider.from("openai"));
        assertEquals(AiProvider.OPENAI, AiProvider.from("OpenAI"));
        assertTrue(AiProvider.ANTHROPIC.requiresApiKey());
        assertFalse(AiProvider.OPENAI.requiresApiKey());
        assertTrue(AiProvider.ANTHROPIC.defaultEndpoint().contains("api.anthropic.com"));
        assertTrue(AiProvider.OPENAI.defaultEndpoint().contains("/v1/chat/completions"));
        assertEquals("openai", AiProvider.OPENAI.id());
    }

    @Test
    void openAiRequestShapeOmitsBlankModelAndCarriesStop() {
        var r = AiRequests.openAiStreamingRequest(m, "", "sys", "user", 128, java.util.List.of("\n"));
        assertFalse(r.has("model")); // blank model omitted → server uses the loaded model
        assertTrue(r.get("stream").asBoolean());
        assertEquals("system", r.get("messages").get(0).get("role").asText());
        assertEquals("user", r.get("messages").get(1).get("role").asText());
        assertEquals("\n", r.get("stop").get(0).asText());
        var named = AiRequests.openAiStreamingRequest(m, "qwen2.5-coder", "s", "u", 64, java.util.List.of());
        assertEquals("qwen2.5-coder", named.get("model").asText());
        assertFalse(named.has("stop"));
    }

    @Test
    void requestForSelectsDialect() {
        assertTrue(AiRequests.requestFor(m, AiProvider.OPENAI, "x", "s", "u", 64, java.util.List.of())
                .has("messages"));
        // Anthropic keeps the system prompt as a top-level field, not a message
        var anthropic = AiRequests.requestFor(m, AiProvider.ANTHROPIC, "x", "s", "u", 64, java.util.List.of());
        assertEquals("s", anthropic.get("system").asText());
    }
}
