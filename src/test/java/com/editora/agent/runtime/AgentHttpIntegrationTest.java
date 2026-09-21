package com.editora.agent.runtime;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.editora.ai.AiProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentHttpIntegrationTest {
    @Test
    void realHttpTransportCarriesToolResultsIntoTheNextModelRequest() throws Exception {
        ObjectMapper json = new ObjectMapper();
        AtomicInteger requests = new AtomicInteger();
        java.util.List<String> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int call = requests.incrementAndGet();
            String event =
                    call == 1 ? """
                    {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"a","function":{"name":"read","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}
                    """ : "{\"choices\":[{\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}";
            byte[] data = ("data: " + event.strip() + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, data.length);
            try (var output = exchange.getResponseBody()) {
                output.write(data);
            }
        });
        server.start();
        try {
            var model = new HttpAgentModel(
                    AiProvider.OPENAI,
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "",
                    "model",
                    new AgentModel.Capabilities(true, true, 16384, 1024));
            var spec = new AgentTool.Spec(
                    "read",
                    "read",
                    json.readTree("{\"type\":\"object\",\"properties\":{}}"),
                    null,
                    AgentTool.Effect.READ,
                    Duration.ofSeconds(5),
                    true,
                    "test");
            var tools = new AgentTools()
                    .register(new AgentTool(spec, (a, c) -> AgentTool.Result.ok("live unsaved document")));
            try (var runtime = new AgentRuntime(
                    model,
                    tools,
                    new AgentPolicy(),
                    (s, a, c) -> fail("read permission"),
                    c -> fail("no edits"),
                    "system",
                    AgentRuntime.Limits.DEFAULT,
                    e -> {},
                    d -> {})) {
                assertEquals(
                        AgentRuntime.State.COMPLETED,
                        runtime.submit("read it").get(5, TimeUnit.SECONDS).state());
                var second = json.readTree(bodies.get(1));
                assertEquals(
                        "live unsaved document",
                        second.get("messages").get(3).get("content").asText());
                assertEquals(
                        "a", second.get("messages").get(3).get("tool_call_id").asText());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cancellingBlockedStreamingBodyClosesTransportPromptly() throws Exception {
        CountDownLatch headers = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().flush();
            headers.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (var worker = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var cancellation = new AgentCancellation();
            var model = new HttpAgentModel(
                    AiProvider.OPENAI,
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "",
                    "model",
                    new AgentModel.Capabilities(true, true, 16384, 1024));
            var result = worker.submit(() -> model.respond(
                    new AgentModel.Request("system", List.of(AgentModel.Message.text("user", "goal")), List.of()),
                    cancellation,
                    d -> {}));
            assertTrue(headers.await(2, TimeUnit.SECONDS));
            cancellation.cancel();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void malformedOrDisconnectedStreamsNeverProduceExecutableCalls() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String data = requests.getAndIncrement() == 0
                    ? "data: {bad json}\n\n"
                    : "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"a\",\"function\":{\"name\":\"read\",\"arguments\":\"{\"}}]}}]}\n\n";
            byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        try {
            var model = new HttpAgentModel(
                    AiProvider.OPENAI,
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "",
                    "model",
                    new AgentModel.Capabilities(true, true, 16384, 1024));
            var request = new AgentModel.Request("system", List.of(AgentModel.Message.text("user", "goal")), List.of());
            assertThrows(java.io.IOException.class, () -> model.respond(request, new AgentCancellation(), d -> {}));
            assertThrows(java.io.IOException.class, () -> model.respond(request, new AgentCancellation(), d -> {}));
        } finally {
            server.stop(0);
        }
    }
}
