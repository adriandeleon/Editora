package com.editora.agent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AcpClientConfigTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void modernSelectorsUseAdvertisedIdsAndRefreshStateWhileLegacyAgentsStillWork() throws Exception {
        var updates = new AtomicReference<AcpJson.SessionInfo>();
        var process = new CapturingProcess();
        var client = new AcpClient(List.of("unused"), Path.of("."), new AcpClient.Host() {
            public void onUpdate(AcpJson.Update update) {}

            public void onSessionConfig(String sessionId, AcpJson.SessionInfo info) {
                assertEquals("s1", sessionId);
                updates.set(info);
            }

            public void onExit(int code) {}

            public String readTextFile(String path, Integer line, Integer limit) {
                return "";
            }

            public void writeTextFile(String path, String content) {}

            public CompletableFuture<String> requestPermission(String title, List<AcpJson.PermissionOption> options) {
                return CompletableFuture.completedFuture(null);
            }
        });
        Field processField = AcpClient.class.getDeclaredField("process");
        processField.setAccessible(true);
        processField.set(client, process);
        try {
            var modern = mapper.readTree("""
                    {"sessionId":"s1","configOptions":[
                      {"id":"local-model","category":"model","type":"select","currentValue":"model-a",
                       "options":[{"value":"model-a","name":"A"},{"value":"model-b","name":"B"}]},
                      {"id":"permission-mode","category":"mode","type":"select","currentValue":"plan",
                       "options":[{"value":"plan","name":"Plan"}]}]}
                    """);
            var started = client.newSession(Path.of("."));
            respond(client, process.take(), modern);
            assertEquals("model-a", started.join().currentModelId());
            var changed = client.setModel("s1", "model-b");
            var request = process.take();
            assertEquals("session/set_config_option", request.path("method").asText());
            assertEquals("local-model", request.path("params").path("configId").asText());
            assertEquals("model-b", request.path("params").path("value").asText());
            ((com.fasterxml.jackson.databind.node.ObjectNode)
                            modern.path("configOptions").get(0))
                    .put("currentValue", "model-b");
            respond(client, request, modern);
            changed.join();
            assertEquals("model-b", updates.get().currentModelId());
            var mode = client.setMode("s1", "plan");
            request = process.take();
            assertEquals("session/set_config_option", request.path("method").asText());
            assertEquals(
                    "permission-mode", request.path("params").path("configId").asText());
            respond(client, request, modern);
            mode.join();
            ((com.fasterxml.jackson.databind.node.ObjectNode)
                            modern.path("configOptions").get(0))
                    .put("currentValue", "model-a");
            var params = mapper.createObjectNode().put("sessionId", "s1");
            params.putObject("update")
                    .put("sessionUpdate", "config_option_update")
                    .set("configOptions", modern.path("configOptions"));
            deliver(client, AcpJson.notification(mapper, "session/update", params));
            assertEquals("model-a", updates.get().currentModelId());
            var resumed = client.resumeSession("s1", Path.of("."));
            respond(client, process.take(), modern);
            assertEquals("model-a", resumed.join().currentModelId());
            // A legacy session clears any modern selector ids from the prior session.
            var legacy = client.newSession(Path.of("."));
            respond(client, process.take(), mapper.createObjectNode().put("sessionId", "s2"));
            legacy.join();
            var legacyModel = client.setModel("s2", "opus");
            request = process.take();
            assertEquals("session/set_model", request.path("method").asText());
            assertEquals("opus", request.path("params").path("modelId").asText());
            respond(client, request, mapper.createObjectNode());
            legacyModel.join();
            var legacyMode = client.setMode("s2", "code");
            request = process.take();
            assertEquals("session/set_mode", request.path("method").asText());
            respond(client, request, mapper.createObjectNode());
            legacyMode.join();
        } finally {
            processField.set(client, null); // fake transport has no operating-system process to reap
            client.dispose();
        }
    }

    private void respond(AcpClient client, JsonNode request, JsonNode result) throws Exception {
        deliver(client, AcpJson.response(mapper, request.path("id"), result));
    }

    private void deliver(AcpClient client, JsonNode message) throws Exception {
        var method = AcpClient.class.getDeclaredMethod("handleLine", String.class);
        method.setAccessible(true);
        method.invoke(client, message.toString());
    }

    private final class CapturingProcess extends Process {
        private final ByteArrayOutputStream sent = new ByteArrayOutputStream();

        JsonNode take() throws Exception {
            JsonNode request = mapper.readTree(sent.toString(StandardCharsets.UTF_8));
            sent.reset();
            return request;
        }

        public OutputStream getOutputStream() {
            return sent;
        }

        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        public int waitFor() {
            return 0;
        }

        public int exitValue() {
            return 0;
        }

        public void destroy() {}

        public boolean isAlive() {
            return true;
        }
    }
}
