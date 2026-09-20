package com.editora.ai;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LmStudioClientTest {
    @Test
    void localProviderStreamsAndChecksWithOnlyItsOptionalBearerToken() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String token : List.of("", "local-token")) {
            try (ServerSocket server = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
                server.setSoTimeout(5000);
                var received = new CompletableFuture<List<String>>();
                Thread.ofVirtual().start(() -> {
                    try {
                        var requests = new ArrayList<String>();
                        for (int i = 0; i < 2; i++) {
                            try (var socket = server.accept()) {
                                socket.setSoTimeout(5000);
                                var reader = new BufferedReader(
                                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                                var headers = new StringBuilder();
                                int length = 0;
                                String line;
                                while (!(line = reader.readLine()).isEmpty()) {
                                    headers.append(line).append('\n');
                                    if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                                        length = Integer.parseInt(line.substring(line.indexOf(':') + 1)
                                                .strip());
                                    }
                                }
                                char[] body = new char[length];
                                int offset = 0;
                                while (offset < length) {
                                    int count = reader.read(body, offset, length - offset);
                                    if (count < 0) throw new java.io.EOFException();
                                    offset += count;
                                }
                                requests.add(headers + "\n" + new String(body));
                                String response = i == 0 ? """
                                        data: {"choices":[{"delta":{"reasoning_content":"hidden"}}]}

                                        data: {"choices":[{"delta":{"content":"OK"},"finish_reason":"stop"}]}

                                        data: [DONE]

                                        """ : "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}";
                                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                                socket.getOutputStream()
                                        .write(("HTTP/1.1 200 OK\r\nContent-Type: "
                                                        + (i == 0 ? "text/event-stream" : "application/json")
                                                        + "\r\nContent-Length: "
                                                        + bytes.length + "\r\nConnection: close\r\n\r\n")
                                                .getBytes(StandardCharsets.UTF_8));
                                socket.getOutputStream().write(bytes);
                                socket.getOutputStream().flush();
                            }
                        }
                        received.complete(requests);
                    } catch (Throwable e) {
                        received.completeExceptionally(e);
                    }
                });
                String endpoint = AiEndpoints.resolve(AiProvider.LMSTUDIO, "http://127.0.0.1:" + server.getLocalPort());
                var text = new StringBuilder();
                var stop = new AtomicReference<String>();
                var error = new AtomicReference<String>();
                var client = new AiClient();
                client.stream(
                        AiProvider.LMSTUDIO,
                        endpoint,
                        token,
                        AiRequests.requestFor(mapper, AiProvider.LMSTUDIO, "local/model", "sys", "user", 8, List.of()),
                        Duration.ofSeconds(5),
                        () -> false,
                        new AiClient.Listener() {
                            public void onText(String delta) {
                                text.append(delta);
                            }

                            public void onDone(String reason) {
                                stop.set(reason);
                            }

                            public void onError(String message) {
                                error.set(message);
                            }
                        });
                assertNull(error.get());
                assertEquals("OK", text.toString());
                assertEquals("end_turn", stop.get());
                assertNull(client.check(
                        AiProvider.LMSTUDIO,
                        endpoint,
                        token,
                        AiRequests.pingRequest(mapper, AiProvider.LMSTUDIO, "local/model"),
                        Duration.ofSeconds(5)));
                var requests = received.get(5, TimeUnit.SECONDS);
                for (int i = 0; i < requests.size(); i++) {
                    String request = requests.get(i);
                    String headers =
                            request.substring(0, request.indexOf("\n\n")).toLowerCase(Locale.ROOT);
                    assertTrue(headers.startsWith("post /v1/chat/completions "));
                    assertFalse(headers.contains("x-api-key"));
                    assertFalse(headers.contains("anthropic-version"));
                    assertEquals(!token.isEmpty(), headers.contains("authorization: bearer local-token"));
                    if (token.isEmpty()) assertFalse(headers.contains("authorization:"));
                    var body = mapper.readTree(request.substring(request.indexOf("\n\n") + 2));
                    assertEquals("local/model", body.path("model").asText());
                    assertEquals(i == 0, body.path("stream").asBoolean());
                    assertEquals(
                            "system", body.path("messages").get(0).path("role").asText());
                }
            }
        }
    }
}
