package com.editora.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.editora.agent.runtime.AgentCancellation;
import com.editora.process.ProcessRegistry;
import com.editora.process.ProcessRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Bounded newline JSON-RPC. No sampling/elicitation, secret logging or server-initiated editor writes. */
public final class StdioMcpConnection implements McpConnection {
    private final Process process;
    private final ObjectMapper json = new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());
    private final AtomicLong ids = new AtomicLong();
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService writer = java.util.concurrent.Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("mcp-write").factory());
    private volatile boolean closed;
    private volatile boolean changed;

    public StdioMcpConnection(List<String> command, Path cwd) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(ProcessRunner.resolveExecutable(command));
        builder.directory(cwd.toFile());
        ProcessRunner.applyStandardEnv(builder);
        ProcessRunner.retainAgentEnvironment(builder.environment());
        process = builder.start();
        ProcessRegistry.track(process);
        Thread.ofVirtual().name("mcp-reader").start(this::read);
        Thread.ofVirtual().name("mcp-stderr").start(() -> {
            try (var input = process.getErrorStream()) {
                byte[] buffer = new byte[4096];
                while (input.read(buffer) != -1) {
                    /* Drain without persisting untrusted secrets. */
                }
            } catch (IOException ignored) {
            }
        });
    }

    @Override
    public boolean alive() {
        return !closed && process.isAlive();
    }

    @Override
    public boolean catalogChanged() {
        return changed;
    }

    @Override
    public JsonNode request(String method, JsonNode arguments, Duration timeout, AgentCancellation c) throws Exception {
        c.check();
        if (!alive()) throw new IOException("MCP server disconnected");
        long id = ids.incrementAndGet();
        var future = new CompletableFuture<JsonNode>();
        pending.put(id, future);
        var message =
                json.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
        message.set("params", arguments);
        try (var hook = c.onCancel(() -> {
            future.cancel(false);
            close();
        })) {
            c.check();
            send(message);
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException timeoutFailure) {
            close();
            throw timeoutFailure;
        } finally {
            pending.remove(id);
        }
    }

    @Override
    public void notification(String method, JsonNode arguments) {
        var message = json.createObjectNode().put("jsonrpc", "2.0").put("method", method);
        message.set("params", arguments);
        send(message);
    }

    private void send(ObjectNode message) {
        if (!alive()) throw new IllegalStateException("MCP server disconnected");
        if (message.toString().length() > 1_000_000) throw new IllegalArgumentException("MCP message too large");
        writer.execute(() -> {
            if (closed) return;
            try {
                var output = process.getOutputStream();
                output.write((message + "\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
            } catch (IOException failure) {
                close();
            }
        });
    }

    private void read() {
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = line(reader)) != null) {
                var message = json.readTree(line);
                if (message == null
                        || !message.isObject()
                        || !"2.0".equals(message.path("jsonrpc").asText()))
                    throw new IOException("Malformed MCP envelope");
                if (message.has("method")) {
                    String method = message.path("method").asText();
                    if (method.equals("notifications/tools/list_changed")) changed = true;
                    if (message.has("id")) {
                        var reply = json.createObjectNode().put("jsonrpc", "2.0");
                        reply.set("id", message.get("id"));
                        if (method.equals("ping")) reply.putObject("result");
                        else
                            reply.putObject("error")
                                    .put("code", -32601)
                                    .put("message", "Client capability not supported");
                        send(reply);
                    }
                    continue;
                }
                if (!message.path("id").isIntegralNumber() || message.has("result") == message.has("error"))
                    throw new IOException("Malformed MCP response");
                var result = pending.remove(message.path("id").asLong());
                if (result == null) continue;
                if (message.has("error"))
                    result.completeExceptionally(new IOException("MCP request failed (code "
                            + message.path("error").path("code").asInt() + ")"));
                else result.complete(message.get("result"));
            }
        } catch (Exception failure) {
            /* Fail the transport without logging server payloads. */
        } finally {
            close();
        }
    }

    static String line(BufferedReader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int ch;
        while ((ch = reader.read()) != -1) {
            if (ch == '\n') return line.toString();
            if (line.length() >= 1_000_000) throw new IOException("MCP message exceeds limit");
            if (ch != '\r') line.append((char) ch);
        }
        if (!line.isEmpty()) throw new IOException("Incomplete MCP frame");
        return null;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        ProcessRegistry.forceKillTree(process);
        writer.shutdownNow();
        pending.values()
                .forEach(f -> f.completeExceptionally(
                        new IOException("MCP server disconnected; effects of an interrupted call are unknown")));
        pending.clear();
    }
}
