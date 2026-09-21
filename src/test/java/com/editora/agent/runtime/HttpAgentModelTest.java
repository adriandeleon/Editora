package com.editora.agent.runtime;

import java.util.List;

import com.editora.ai.AiProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HttpAgentModelTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void tokenEnvelopeOverheadDoesNotConsumeTheContentBudget() throws Exception {
        var collector = new HttpAgentModel.Collector(AiProvider.LMSTUDIO, text -> {});
        var event = json.createObjectNode().put("model", "local-model-metadata".repeat(30));
        event.putArray("choices").addObject().putObject("delta").put("content", "x");
        for (int i = 0; i < 3000; i++) {
            collector.onEvent(event);
            collector.onText("x");
        }
        collector.onEvent(json.readTree("{\"choices\":[{\"finish_reason\":\"stop\"}]}"));
        assertEquals(3000, collector.response().text().length());
    }

    @Test
    void contentAndEnvelopeBudgetsStillRejectOversizedStreams() throws Exception {
        var content = new HttpAgentModel.Collector(AiProvider.OPENAI, text -> {});
        content.onText("x".repeat(1_000_001));
        assertThrows(java.io.IOException.class, content::response);
        var tool = new HttpAgentModel.Collector(AiProvider.ANTHROPIC, text -> {});
        tool.onEvent(
                json.readTree(
                        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"a\",\"name\":\"read\",\"input\":{}}}"));
        var delta = json.createObjectNode().put("type", "content_block_delta").put("index", 0);
        delta.putObject("delta").put("type", "input_json_delta").put("partial_json", "x".repeat(1_000_001));
        tool.onEvent(delta);
        assertThrows(java.io.IOException.class, tool::response);
        var wire = new HttpAgentModel.Collector(AiProvider.OPENAI, text -> {});
        var envelope = json.createObjectNode().put("metadata", "x".repeat(20_000));
        for (int i = 0; i < 810; i++) wire.onEvent(envelope);
        assertThrows(java.io.IOException.class, wire::response);
    }

    @Test
    void assemblesInterleavedOpenAiToolCallsAndUsage() throws Exception {
        var collector = new HttpAgentModel.Collector(AiProvider.LMSTUDIO, text -> {});
        collector.onEvent(json.readTree("""
                {"choices":[{"delta":{"tool_calls":[
                  {"index":0,"id":"a","function":{"name":"read","arguments":"{\\\"path\\\":"}},
                  {"index":1,"id":"b","function":{"name":"read","arguments":"{}"}}]}}]}
                """));
        collector.onEvent(json.readTree("""
                {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\\"a.txt\\\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":30,"completion_tokens":20}}
                """));
        var response = collector.response();
        assertEquals(2, response.calls().size());
        assertEquals("{\"path\":\"a.txt\"}", response.calls().getFirst().arguments());
        assertEquals(30, response.inputTokens());
        assertEquals(20, response.outputTokens());
    }

    @Test
    void acceptsStructuredArgumentsFromOpenAiCompatibleLocalServers() throws Exception {
        var collector = new HttpAgentModel.Collector(AiProvider.LMSTUDIO, text -> {});
        collector.onEvent(json.readTree("""
                {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"a","function":{"name":"read","arguments":{"path":"a.txt"}}}]},"finish_reason":"tool_calls"}]}
                """));
        assertEquals(
                "{\"path\":\"a.txt\"}", collector.response().calls().getFirst().arguments());
    }

    @Test
    void providerArrivalOrderCannotReorderIndexedCalls() throws Exception {
        var collector = new HttpAgentModel.Collector(AiProvider.OPENAI, text -> {});
        collector.onEvent(json.readTree("""
                {"choices":[{"delta":{"tool_calls":[
                  {"index":1,"id":"b","function":{"name":"read","arguments":"{}"}},
                  {"index":0,"id":"a","function":{"name":"read","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}
                """));
        assertEquals(
                List.of("a", "b"),
                collector.response().calls().stream().map(AgentModel.Call::id).toList());
    }

    @Test
    void anthropicStructuredDeltasBecomeNeutralCalls() throws Exception {
        var collector = new HttpAgentModel.Collector(AiProvider.ANTHROPIC, text -> {});
        collector.onEvent(
                json.readTree(
                        "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"read\",\"input\":{}}}"));
        collector.onEvent(
                json.readTree(
                        "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}}"));
        collector.onEvent(
                json.readTree(
                        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":25}}"));
        collector.onEvent(json.readTree("{\"type\":\"message_stop\"}"));
        assertEquals("t1", collector.response().calls().getFirst().id());
        assertEquals("{}", collector.response().calls().getFirst().arguments());
    }

    @Test
    void incompleteOrTokenLimitedToolStreamsAreNeverExecutable() throws Exception {
        var collector = new HttpAgentModel.Collector(AiProvider.OPENAI, text -> {});
        collector.onEvent(
                json.readTree(
                        "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"a\",\"function\":{\"name\":\"read\",\"arguments\":\"{\"}}]}}]}"));
        collector.onDone("end_turn");
        assertThrows(java.io.IOException.class, collector::response);
        collector.onEvent(json.readTree("{\"choices\":[{\"finish_reason\":\"length\"}]}"));
        assertThrows(java.io.IOException.class, collector::response);
    }

    @Test
    void malformedProviderCallSequencesFailClosed() throws Exception {
        var invalidIndex = new HttpAgentModel.Collector(AiProvider.OPENAI, text -> {});
        invalidIndex.onEvent(json.readTree("""
                {"choices":[{"delta":{"tool_calls":[{"index":129,"id":"a","function":{"name":"read","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}
                """));
        assertThrows(java.io.IOException.class, invalidIndex::response);

        var missingStart = new HttpAgentModel.Collector(AiProvider.ANTHROPIC, text -> {});
        missingStart.onEvent(json.readTree("""
                {"type":"content_block_delta","index":3,"delta":{"type":"input_json_delta","partial_json":"{}"}}
                """));
        assertThrows(java.io.IOException.class, missingStart::response);

        var duplicateStart = new HttpAgentModel.Collector(AiProvider.ANTHROPIC, text -> {});
        String start = """
                {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"a","name":"read","input":{}}}
                """;
        duplicateStart.onEvent(json.readTree(start));
        duplicateStart.onEvent(json.readTree(start));
        assertThrows(java.io.IOException.class, duplicateStart::response);

        var conflicting = new HttpAgentModel.Collector(AiProvider.ANTHROPIC, text -> {});
        conflicting.onEvent(json.readTree("""
                {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"a","name":"read","input":{"path":"a"}}}
                """));
        conflicting.onEvent(json.readTree("""
                {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\\"path\\\":\\\"b\\\"}"}}
                """));
        conflicting.onEvent(json.readTree("""
                {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":1}}
                """));
        conflicting.onEvent(json.readTree("{\"type\":\"message_stop\"}"));
        assertThrows(java.io.IOException.class, conflicting::response);
    }

    @Test
    void bothDialectsPreserveMultipleResultsAndAdvertisedToolSchemas() throws Exception {
        var spec = new AgentTool.Spec(
                "read",
                "read a file",
                json.readTree("{\"type\":\"object\",\"properties\":{}}"),
                null,
                AgentTool.Effect.READ,
                java.time.Duration.ofSeconds(5),
                true,
                "test");
        var messages = List.of(
                AgentModel.Message.text("user", "goal"),
                new AgentModel.Message(
                        "assistant",
                        "",
                        List.of(new AgentModel.Call("a", "read", "{}"), new AgentModel.Call("b", "read", "{}")),
                        null,
                        false),
                AgentModel.Message.observation("a", "one", false),
                AgentModel.Message.observation("b", "failed", true));
        var request = new AgentModel.Request("system", messages, List.of(spec));
        var caps = new AgentModel.Capabilities(true, true, 16384, 1024);
        var openai = new HttpAgentModel(AiProvider.OPENAI, "http://127.0.0.1/", "", "", caps).body(request);
        assertEquals(5, openai.get("messages").size());
        assertEquals("b", openai.get("messages").get(4).get("tool_call_id").asText());
        assertFalse(openai.has("model"));
        var anthropic =
                new HttpAgentModel(AiProvider.ANTHROPIC, "https://example.org/", "key", "model", caps).body(request);
        assertEquals(3, anthropic.get("messages").size());
        assertEquals(2, anthropic.get("messages").get(2).get("content").size());
        assertTrue(anthropic
                .get("messages")
                .get(2)
                .get("content")
                .get(1)
                .get("is_error")
                .asBoolean());
        assertEquals(spec.inputSchema(), anthropic.get("tools").get(0).get("input_schema"));
    }
}
