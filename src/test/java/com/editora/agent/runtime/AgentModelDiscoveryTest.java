package com.editora.agent.runtime;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.editora.ai.AiProvider;
import com.editora.config.AgentModelProfileConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentModelDiscoveryTest {
    @Test
    void stalledDiscoveryCancelsWithItsParent() throws Exception {
        var reached = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/models", exchange -> {
            reached.countDown();
            try {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var cancellation = new AgentCancellation();
            var request = executor.submit(() -> AgentModelDiscovery.discover(
                    AiProvider.LMSTUDIO,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "",
                    "m",
                    32768,
                    AgentModelProfileConfig.automatic("lmstudio", "m"),
                    cancellation));
            assertTrue(reached.await(2, java.util.concurrent.TimeUnit.SECONDS));
            cancellation.cancel();
            var failure = assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> request.get(2, java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(java.util.concurrent.CancellationException.class, failure.getCause());
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void refusesRemoteCleartextCredentialsBeforeConnecting() {
        var profile = AgentModelDiscovery.discover(
                AiProvider.LMSTUDIO,
                "http://example.invalid/v1/chat/completions",
                "test-placeholder",
                "m",
                32768,
                AgentModelProfileConfig.automatic("lmstudio", "m"),
                new AgentCancellation());
        assertEquals("REFUSED_CLEARTEXT_CREDENTIAL", profile.discoveryStatus());
    }

    @Test
    void refreshOccursAfterInferenceAndNeverLoadsModels() throws Exception {
        var gets = new AtomicInteger();
        var posts = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/models", exchange -> {
            assertEquals("GET", exchange.getRequestMethod());
            gets.incrementAndGet();
            String loaded = posts.get() == 0
                    ? ""
                    : ",\"loaded_instances\":[{\"id\":\"m\",\"config\":{\"context_length\":16384}}]";
            byte[] bytes = ("{\"models\":[{\"type\":\"llm\",\"key\":\"m\",\"max_context_length\":65536" + loaded
                            + "}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/v1/chat/completions", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            posts.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] bytes =
                    "data: {\"choices\":[{\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                            .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var model = new HttpAgentModel(
                    AiProvider.LMSTUDIO,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "",
                    "m",
                    32768,
                    AgentModelProfileConfig.automatic("lmstudio", "m"));
            var c = new AgentCancellation();
            model.prepare(c);
            model.prepare(c);
            assertEquals(1, gets.get());
            assertEquals(32768, model.capabilities().contextTokens());
            model.respond(new AgentModel.Request("system", List.of(), List.of()), c, t -> {});
            model.prepare(c);
            model.prepare(c);
            assertEquals(2, gets.get());
            assertEquals(1, posts.get());
            assertEquals(16384, model.capabilities().contextTokens());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void malformedOversizedAndRedirectedMetadataNeverEstablishesCapabilities() throws Exception {
        for (int scenario = 0; scenario < 3; scenario++) {
            int which = scenario;
            var redirected = new AtomicInteger();
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/models", exchange -> {
                byte[] bytes =
                        (which == 0 ? "{\"unrelated\":true}" : "x".repeat(270000)).getBytes(StandardCharsets.UTF_8);
                if (which == 2) {
                    exchange.getResponseHeaders().add("Location", "/trap");
                    exchange.sendResponseHeaders(302, -1);
                } else {
                    exchange.sendResponseHeaders(200, bytes.length);
                    try {
                        exchange.getResponseBody().write(bytes);
                    } catch (java.io.IOException ignored) {
                    }
                }
                exchange.close();
            });
            server.createContext("/trap", e -> {
                redirected.incrementAndGet();
                e.sendResponseHeaders(200, -1);
                e.close();
            });
            server.start();
            try {
                var profile = AgentModelDiscovery.discover(
                        AiProvider.LMSTUDIO,
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                        "",
                        "m",
                        32768,
                        AgentModelProfileConfig.automatic("lmstudio", "m"),
                        new AgentCancellation());
                assertEquals("UNKNOWN", profile.server());
                assertEquals(0, profile.loadedContext().value());
                assertEquals(AgentModel.Support.UNKNOWN, profile.tools().value());
                assertEquals(0, redirected.get());
            } finally {
                server.stop(0);
            }
        }
    }
}
