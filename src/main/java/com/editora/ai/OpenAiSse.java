package com.editora.ai;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Pure readers for the OpenAI-compatible streaming dialect (LM Studio, Ollama, vLLM): data-only SSE
 * events terminated by a literal {@code [DONE]}, with text at {@code choices[0].delta.content}.
 * {@link AiClient} drives these from its response loop; everything here is side-effect-free and
 * unit-tested.
 */
public final class OpenAiSse {

    /** The stream-end sentinel sent as a bare data payload. */
    public static final String DONE = "[DONE]";

    private OpenAiSse() {}

    /** True when {@code data} is the {@code [DONE]} terminator (never valid JSON — check first). */
    public static boolean isDone(String data) {
        return data != null && DONE.equals(data.strip());
    }

    /** The chunk's text delta ({@code choices[0].delta.content}), or null when none. */
    public static String textDelta(JsonNode data) {
        JsonNode content = choice(data).path("delta").path("content");
        return content.isTextual() ? content.asText() : null;
    }

    /** The finish reason mapped to the Anthropic-style stop reasons the callers already handle
     *  ({@code stop} → {@code end_turn}, {@code length} → {@code max_tokens}); null when absent. */
    public static String finishReason(JsonNode data) {
        JsonNode reason = choice(data).path("finish_reason");
        if (!reason.isTextual()) {
            return null;
        }
        return switch (reason.asText()) {
            case "stop" -> "end_turn";
            case "length" -> "max_tokens";
            case "content_filter" -> "refusal";
            default -> reason.asText();
        };
    }

    /** The error message of an {@code {"error":{…}}} payload, or null when this isn't an error. */
    public static String errorMessage(JsonNode data) {
        if (data == null || !data.has("error")) {
            return null;
        }
        JsonNode msg = data.path("error").path("message");
        return msg.isTextual() ? msg.asText() : "stream error";
    }

    private static JsonNode choice(JsonNode data) {
        return data == null
                ? com.fasterxml.jackson.databind.node.MissingNode.getInstance()
                : data.path("choices").path(0);
    }
}
