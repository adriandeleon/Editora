package com.editora.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for #482: a dead-but-accepting AI endpoint must not wedge the streaming client forever.
 * The response timeout only bounds the wait for the *headers*, so an endpoint that returns {@code 200 …} and
 * then writes nothing would block the read forever — and {@code AiService} runs on a single-thread executor,
 * so every later request would queue behind it permanently. The idle-read watchdog closes the stream once no
 * data has arrived for the response-timeout, freeing the worker; a slow-but-alive stream is untouched.
 *
 * <p>Driven against a real loopback {@link ServerSocket} that controls exactly what it writes after the
 * headers.
 */
class AiClientIdleTimeoutTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ObjectNode body = mapper.createObjectNode();

    /** Runs a server that writes {@code 200} headers then does {@code afterHeaders} with the client socket. */
    private interface AfterHeaders {
        void accept(Socket s, OutputStream out) throws IOException, InterruptedException;
    }

    @Test
    void aDeadEndpointThatSendsHeadersThenNothingDoesNotHangForever() throws Exception {
        AtomicReference<String> error = new AtomicReference<>();
        AtomicBoolean textSeen = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);

        // The endpoint sends the headers, then holds the connection open writing no body at all.
        try (ServerSocket server = startServer((s, out) -> {
            Thread.sleep(30_000); // hold open; the client's idle watchdog must give up long before this
        })) {
            String endpoint = "http://127.0.0.1:" + server.getLocalPort() + "/v1/chat/completions";
            Thread caller = new Thread(() -> {
                new AiClient()
                        .stream(
                                AiProvider.OPENAI,
                                endpoint,
                                "", // keyless local server (loopback), so the #481 guard allows it
                                body,
                                Duration.ofSeconds(2), // idle-read deadline
                                () -> false,
                                listener(error, textSeen, done));
            });
            caller.setDaemon(true);
            caller.start();

            // The old code would block in readLine() forever; the watchdog must free it within a few seconds.
            assertTrue(done.await(15, TimeUnit.SECONDS), "stream() must return (not wedge) on a dead endpoint");
            caller.join(2_000);
            assertNotNull(error.get(), "a dead endpoint must surface an error");
            assertTrue(error.get().toLowerCase().contains("timed out"), error.get());
            assertTrue(!textSeen.get(), "no content should have been produced");
        }
    }

    @Test
    void aSlowButAliveStreamIsNotCutOff() throws Exception {
        AtomicReference<String> error = new AtomicReference<>();
        AtomicBoolean textSeen = new AtomicBoolean(false);
        AtomicReference<String> stop = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        // Three data chunks, each ~1s apart (< the 3s idle deadline), then [DONE]. Total span > one idle
        // period, so this only passes if each received line resets the watchdog's clock.
        try (ServerSocket server = startServer((s, out) -> {
            for (int i = 0; i < 3; i++) {
                Thread.sleep(1_000);
                out.write(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"tok\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            Thread.sleep(1_000);
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        })) {
            String endpoint = "http://127.0.0.1:" + server.getLocalPort() + "/v1/chat/completions";
            new AiClient()
                    .stream(
                            AiProvider.OPENAI,
                            endpoint,
                            "",
                            body,
                            Duration.ofSeconds(3), // idle deadline > each 1s gap, < the total span
                            () -> false,
                            new AiClient.Listener() {
                                @Override
                                public void onText(String delta) {
                                    textSeen.set(true);
                                }

                                @Override
                                public void onDone(String stopReason) {
                                    stop.set(stopReason);
                                    done.countDown();
                                }

                                @Override
                                public void onError(String message) {
                                    error.set(message);
                                    done.countDown();
                                }
                            });
            assertTrue(done.await(15, TimeUnit.SECONDS), "the slow stream should complete");
            assertEquals(null, error.get(), "a slow-but-alive stream must not be cut off: " + error.get());
            assertTrue(textSeen.get(), "content should have streamed");
            assertNotNull(stop.get());
        }
    }

    private AiClient.Listener listener(AtomicReference<String> error, AtomicBoolean textSeen, CountDownLatch done) {
        return new AiClient.Listener() {
            @Override
            public void onText(String delta) {
                textSeen.set(true);
            }

            @Override
            public void onDone(String stopReason) {
                done.countDown();
            }

            @Override
            public void onError(String message) {
                error.set(message);
                done.countDown();
            }
        };
    }

    /** A loopback server that reads the request headers, writes a 200 SSE header, then runs {@code after}. */
    private ServerSocket startServer(AfterHeaders after) throws IOException {
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        AtomicInteger ignore = new AtomicInteger();
        Thread t = new Thread(() -> {
            try (Socket s = server.accept()) {
                drainRequestHeaders(s.getInputStream());
                OutputStream out = s.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\n" + "Content-Type: text/event-stream\r\n" + "Connection: close\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                after.accept(s, out);
            } catch (IOException | InterruptedException ignored) {
                ignore.incrementAndGet(); // the client closing the socket is expected
            }
        });
        t.setDaemon(true);
        t.start();
        return server;
    }

    private void drainRequestHeaders(InputStream in) throws IOException {
        int state = 0; // counts the \r\n\r\n terminator
        int b;
        while ((b = in.read()) != -1) {
            if ((state == 0 || state == 2) && b == '\r') {
                state++;
            } else if ((state == 1 || state == 3) && b == '\n') {
                if (state == 3) {
                    return;
                }
                state++;
            } else {
                state = 0;
            }
        }
    }
}
