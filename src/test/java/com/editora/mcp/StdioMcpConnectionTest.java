package com.editora.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.editora.agent.runtime.AgentCancellation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** The child is a local deterministic fixture, not an installed or network MCP server. */
class StdioMcpConnectionTest {
    @TempDir
    Path root;

    @Test
    void actualStdioFramesRoundTripAndCancellationKillsBlockedServer() throws Exception {
        Path source = root.resolve("Fixture.java");
        Files.writeString(source, """
                import java.io.*;
                public class Fixture {
                    public static void main(String[] args) throws Exception {
                        var reader=new BufferedReader(new InputStreamReader(System.in));
                        for(String line;(line=reader.readLine())!=null;) {
                            if(line.contains("block")) {java.nio.file.Files.writeString(java.nio.file.Path.of("blocked"),"ready");Thread.sleep(60000);continue;}
                            var matcher=java.util.regex.Pattern.compile("\\\"id\\\":([0-9]+)").matcher(line);
                            if(matcher.find()) {System.out.println("{\\\"jsonrpc\\\":\\\"2.0\\\",\\\"id\\\":"+matcher.group(1)+",\\\"result\\\":{\\\"ok\\\":true}}");System.out.flush();}
                        }
                    }
                }
                """);
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        try (var connection = new StdioMcpConnection(List.of(java, source.toString()), root)) {
            var args = new ObjectMapper().createObjectNode();
            assertTrue(connection
                    .request("ping", args, Duration.ofSeconds(15), new AgentCancellation())
                    .path("ok")
                    .asBoolean());
            var c = new AgentCancellation();
            var waiting = CompletableFuture.runAsync(() -> {
                try {
                    connection.request("block", args, Duration.ofSeconds(30), c);
                } catch (Exception e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(root.resolve("blocked")) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(root.resolve("blocked")), "fixture received the request before cancellation");
            c.cancel();
            assertThrows(Exception.class, () -> waiting.get(3, TimeUnit.SECONDS));
            assertFalse(connection.alive());
        }
    }
}
