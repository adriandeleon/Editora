package com.editora.ai;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The stop reason {@link AiClient#stream} reports must be one the provider actually sent. A response body
 * that just ends used to be reported as {@code end_turn}, which let a caller apply half an answer.
 */
class AiClientStopReasonTest {

    private record Result(String text, String stop, String error) {}

    private static Result stream(AiProvider provider, String sse) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write(sse.getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        AiClient client = new AiClient();
        try {
            StringBuilder text = new StringBuilder();
            AtomicReference<String> stop = new AtomicReference<>();
            AtomicReference<String> error = new AtomicReference<>();
            ObjectMapper mapper = new ObjectMapper();
            client.stream(
                    provider,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/",
                    "",
                    AiRequests.requestFor(mapper, provider, "m", "sys", "user", 8, List.of()),
                    Duration.ofSeconds(10),
                    () -> false,
                    new AiClient.Listener() {
                        @Override
                        public void onText(String delta) {
                            text.append(delta);
                        }

                        @Override
                        public void onDone(String reason) {
                            stop.set(reason);
                        }

                        @Override
                        public void onError(String message) {
                            error.set(message);
                        }
                    });
            return new Result(text.toString(), stop.get(), error.get());
        } finally {
            client.close();
            server.stop(0);
        }
    }

    private static String openAi(String content, String finish) {
        return "data: {\"choices\":[{\"delta\":{" + (content == null ? "" : "\"content\":\"" + content + "\"")
                + "},\"finish_reason\":" + (finish == null ? "null" : "\"" + finish + "\"") + "}]}\n\n";
    }

    private static String anthropicText(String text) {
        return "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":"
                + "{\"type\":\"text_delta\",\"text\":\"" + text + "\"}}\n\n";
    }

    private static String anthropicStop(String reason) {
        return "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + reason
                + "\"}}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
    }

    @Test
    void openAiStreamThatEndsWithoutATerminatorIsIncomplete() throws Exception {
        Result r = stream(AiProvider.OPENAI, openAi("half an ans", null));
        assertNull(r.error());
        assertEquals("half an ans", r.text());
        assertEquals(AiStop.INCOMPLETE, r.stop());
    }

    @Test
    void openAiLengthStopIsReportedAsMaxTokens() throws Exception {
        Result r = stream(AiProvider.OPENAI, openAi("half", null) + openAi(null, "length") + "data: [DONE]\n\n");
        assertEquals("max_tokens", r.stop());
    }

    @Test
    void openAiNormalStopIsEndTurnWithOrWithoutAFinishReason() throws Exception {
        assertEquals(
                "end_turn",
                stream(AiProvider.OPENAI, openAi("ok", "stop") + "data: [DONE]\n\n")
                        .stop());
        // [DONE] is itself an explicit end of the answer.
        assertEquals(
                "end_turn",
                stream(AiProvider.OPENAI, openAi("ok", null) + "data: [DONE]\n\n")
                        .stop());
        // A finish reason was received; a proxy that drops the [DONE] line does not make the answer partial.
        assertEquals("end_turn", stream(AiProvider.OPENAI, openAi("ok", "stop")).stop());
    }

    @Test
    void anthropicStreamThatEndsWithoutAStopReasonIsIncomplete() throws Exception {
        Result r = stream(AiProvider.ANTHROPIC, anthropicText("half an ans"));
        assertNull(r.error());
        assertEquals("half an ans", r.text());
        assertEquals(AiStop.INCOMPLETE, r.stop());
    }

    @Test
    void anthropicStopReasonsPassThrough() throws Exception {
        assertEquals(
                "end_turn",
                stream(AiProvider.ANTHROPIC, anthropicText("ok") + anthropicStop("end_turn"))
                        .stop());
        assertEquals(
                "max_tokens",
                stream(AiProvider.ANTHROPIC, anthropicText("ha") + anthropicStop("max_tokens"))
                        .stop());
    }
}
