package com.editora.ai;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real ACP process/JSON transport with a deterministic local peer, never a model. */
class CodexAiClientTest {
    private static List<String> command(String scenario) {
        return List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                Peer.class.getName(),
                scenario);
    }

    @Test
    void streamsOnlyAnswerTextAndRefusesAgentEditsAndPermissions() {
        Capture result = new Capture();
        new CodexAiClient()
                .run(command("answer"), "test-model", "rewrite this text", Duration.ofSeconds(10), () -> false, result);
        assertNull(result.error);
        assertEquals("answer", result.text.toString());
        assertEquals("test-model", result.model);
        assertEquals("end_turn", result.stop);
        assertEquals(1, result.completions);
        assertFalse(Files.exists(Path.of(result.cwd)), "the private working directory is cleaned up");
    }

    @Test
    void healthCheckDoesNotSendPrompt() {
        Capture result = new Capture();
        new CodexAiClient().run(command("ping"), "", null, Duration.ofSeconds(10), () -> false, result);
        assertNull(result.error);
        assertTrue(result.text.isEmpty());
        assertEquals("default-model", result.model);
        assertEquals("end_turn", result.stop);
    }

    @Test
    void cancellationInterruptsSilentTurnAndReapsProcess() {
        AtomicBoolean cancelled = new AtomicBoolean();
        Capture result = new Capture() {
            @Override
            public void onText(String text) {
                super.onText(text);
                cancelled.set(true);
            }
        };
        new CodexAiClient().run(command("cancel"), "", "prompt", Duration.ofSeconds(10), cancelled::get, result);
        assertNull(result.error);
        assertEquals("cancelled", result.stop);
        assertEquals(1, result.completions);
    }

    @Test
    void silentInitializationTimesOut() {
        Capture result = new Capture();
        new CodexAiClient().run(command("hang"), "", "prompt", Duration.ofMillis(300), () -> false, result);
        assertEquals("timed out", result.error);
        assertEquals(1, result.completions);
    }

    @Test
    void authenticationFailureIsReportedWithoutPrompting() {
        Capture result = new Capture();
        new CodexAiClient().run(command("auth"), "", "prompt", Duration.ofSeconds(10), () -> false, result);
        assertNotNull(result.error);
        assertTrue(result.error.contains("Run codex login"));
        assertTrue(result.text.isEmpty());
        assertEquals(1, result.completions);
    }

    @Test
    void missingAdapterProvidesInstallationGuidance() {
        Capture result = new Capture();
        new CodexAiClient()
                .run(
                        List.of("editora-test-nonexistent-codex-adapter"),
                        "",
                        null,
                        Duration.ofSeconds(2),
                        () -> false,
                        result);
        assertNotNull(result.error);
        assertTrue(result.error.contains("@agentclientprotocol/codex-acp"));
        assertEquals(1, result.completions);
    }

    private static class Capture implements AiClient.Listener {
        final StringBuilder text = new StringBuilder();
        String error;
        String stop;
        String model;
        String cwd;
        int completions;

        @Override
        public void onModel(String model) {
            this.model = model;
        }

        @Override
        public void onText(String delta) {
            if (delta.startsWith("cwd:")) {
                cwd = delta.substring(4);
            } else {
                text.append(delta);
            }
        }

        @Override
        public void onDone(String reason) {
            stop = reason;
            completions++;
        }

        @Override
        public void onError(String message) {
            error = message;
            completions++;
        }
    }

    /** Deliberately accepts only the expected handshake; prompts before read-only/model setup fail. */
    public static class Peer {
        public static void main(String[] args) throws Exception {
            var mapper = new ObjectMapper();
            var reader = new BufferedReader(new InputStreamReader(System.in));
            boolean readOnly = false;
            boolean modelSelected = false;
            boolean writeDenied = false;
            String cwd = null;
            Long promptId = null;
            for (String line; (line = reader.readLine()) != null; ) {
                var request = mapper.readTree(line);
                String method = request.path("method").asText();
                long id = request.path("id").asLong();
                ObjectNode response =
                        mapper.createObjectNode().put("jsonrpc", "2.0").put("id", id);
                ObjectNode result = mapper.createObjectNode();
                switch (method) {
                    case "initialize" -> {
                        if (args[0].equals("hang")) {
                            continue;
                        }
                        result.put("protocolVersion", 1);
                    }
                    case "session/new" -> {
                        if (args[0].equals("auth")) {
                            response.putObject("error").put("code", -32000).put("message", "Run codex login");
                            System.out.println(response);
                            continue;
                        }
                        cwd = request.path("params").path("cwd").asText();
                        result.put("sessionId", "one-shot");
                        result.putObject("models").put("currentModelId", "default-model");
                    }
                    case "session/set_mode" -> {
                        readOnly =
                                request.path("params").path("modeId").asText().equals("read-only");
                    }
                    case "session/set_model" -> {
                        modelSelected =
                                request.path("params").path("modelId").asText().equals("test-model");
                    }
                    case "session/prompt" -> {
                        if (!readOnly || args[0].equals("ping")) {
                            System.exit(2);
                        }
                        if (args[0].equals("cancel")) {
                            chunk(mapper, "agent_message_chunk", "started");
                            continue;
                        }
                        if (!modelSelected) {
                            System.exit(3);
                        }
                        promptId = id;
                        chunk(mapper, "agent_thought_chunk", "must not become replacement text");
                        if (!args[0].equals("ui")) {
                            chunk(mapper, "agent_message_chunk", "cwd:" + cwd);
                        }
                        System.out.println("{\"jsonrpc\":\"2.0\",\"id\":900,\"method\":\"fs/write_text_file\","
                                + "\"params\":{\"path\":\"must-not-write.txt\",\"content\":\"bad\"}}");
                        continue;
                    }
                    case "" -> {
                        if (id == 900) {
                            writeDenied = request.has("error");
                            System.out.println(
                                    "{\"jsonrpc\":\"2.0\",\"id\":901,\"method\":\"session/request_permission\","
                                            + "\"params\":{\"options\":[{\"optionId\":\"yes\",\"name\":\"Allow\",\"kind\":\"allow_once\"}]}}");
                            continue;
                        }
                        if (id != 901
                                || !writeDenied
                                || !request.path("result")
                                        .path("outcome")
                                        .path("outcome")
                                        .asText()
                                        .equals("cancelled")) {
                            System.exit(4);
                        }
                        chunk(mapper, "agent_message_chunk", "answer");
                        response.put("id", promptId);
                        result.put("stopReason", "end_turn");
                    }
                    default -> System.exit(5);
                }
                response.set("result", result);
                System.out.println(response);
            }
        }

        private static void chunk(ObjectMapper mapper, String kind, String text) {
            var message = mapper.createObjectNode().put("jsonrpc", "2.0").put("method", "session/update");
            var params = message.putObject("params").put("sessionId", "one-shot");
            params.putObject("update")
                    .put("sessionUpdate", kind)
                    .putObject("content")
                    .put("type", "text")
                    .put("text", text);
            System.out.println(message);
        }
    }
}
