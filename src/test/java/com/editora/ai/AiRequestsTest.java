package com.editora.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link AiRequests}: request shape, prompt content, fence stripping, and input truncation. */
class AiRequestsTest {

    private final ObjectMapper m = new ObjectMapper();

    @Test
    void streamingRequestShape() {
        ObjectNode r = AiRequests.streamingRequest(m, "claude-opus-4-8", "sys", "user text");
        assertEquals("claude-opus-4-8", r.get("model").asText());
        assertTrue(r.get("stream").asBoolean());
        assertEquals(AiRequests.MAX_TOKENS, r.get("max_tokens").asInt());
        assertEquals("sys", r.get("system").asText());
        assertEquals("user", r.get("messages").get(0).get("role").asText());
        assertEquals("user text", r.get("messages").get(0).get("content").asText());
    }

    @Test
    void commitPromptCarriesDiff() {
        String user = AiRequests.commitMessageUser("diff --git a/x b/x\n+added");
        assertTrue(user.contains("+added"));
        assertTrue(AiRequests.commitMessageSystem().contains("commit message"));
    }

    @Test
    void explainPromptFencesCodeWithSanitizedLanguage() {
        String user = AiRequests.explainUser("java$%", "int x = 1;");
        assertTrue(user.contains("```java\n"));
        assertTrue(user.contains("int x = 1;"));
    }

    @Test
    void rewritePromptCarriesInstructionAndCode() {
        String user = AiRequests.rewriteUser("java", "use streams", "for (;;) {}");
        assertTrue(user.contains("Instruction: use streams"));
        assertTrue(user.contains("for (;;) {}"));
    }

    @Test
    void stripCodeFenceRemovesOneFence() {
        assertEquals("int x;", AiRequests.stripCodeFence("```java\nint x;\n```"));
        assertEquals("int x;", AiRequests.stripCodeFence("int x;"));
        // no closing fence → left as-is
        assertEquals("```java\nint x;", AiRequests.stripCodeFence("```java\nint x;"));
    }

    @Test
    void completionRequestCarriesStopSequencesAndCap() {
        ObjectNode r = AiRequests.streamingRequest(
                m, "claude-haiku-4-5", "sys", "u", AiRequests.COMPLETION_MAX_TOKENS, java.util.List.of("\n"));
        assertEquals(AiRequests.COMPLETION_MAX_TOKENS, r.get("max_tokens").asInt());
        assertEquals("\n", r.get("stop_sequences").get(0).asText());
        // the 4-arg form omits stop_sequences entirely
        assertTrue(AiRequests.streamingRequest(m, "x", "s", "u").get("stop_sequences") == null);
    }

    @Test
    void completionPromptMarksCursorBetweenPrefixAndSuffix() {
        String user = AiRequests.completionUser("java", "int x = ", ";");
        assertTrue(user.contains("int x = <CURSOR>;"));
        assertTrue(user.startsWith("Language: java"));
        assertTrue(AiRequests.completionUser("", "a", "b").startsWith("Language: plain text"));
        assertTrue(AiRequests.completionSystem().contains("ONLY the text to insert"));
    }

    @Test
    void truncateCapsHugeInput() {
        String huge = "x".repeat(AiRequests.MAX_INPUT_CHARS + 100);
        String out = AiRequests.truncate(huge);
        assertTrue(out.length() < huge.length());
        assertTrue(out.endsWith("…(truncated)"));
        assertEquals("small", AiRequests.truncate("small"));
    }
}
