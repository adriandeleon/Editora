package com.editora.process;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A loopback port joined to a port elsewhere through one relay process per connection ({@link RelayPortForward}).
 * The relay here is a stand-in whose stdin and stdout are a connection to a local echo server; its stdin is
 * buffered, as a real process's is.
 */
class RelayPortForwardTest {

    private ServerSocket echo;
    private final AtomicInteger relays = new AtomicInteger();

    @BeforeEach
    void startEcho() throws IOException {
        echo = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            while (!echo.isClosed()) {
                try {
                    Socket s = echo.accept();
                    Thread.ofVirtual().start(() -> {
                        try (s) {
                            s.getInputStream().transferTo(s.getOutputStream());
                        } catch (IOException ignored) {
                            // the relay went away
                        }
                    });
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    @AfterEach
    void stopEcho() throws IOException {
        echo.close();
    }

    /** A relay process: its stdin goes to the echo server (buffered), its stdout comes back from it. */
    private final class SocketRelay extends Process {
        private final Socket socket;
        private final OutputStream in;
        private final CompletableFuture<Process> exit = new CompletableFuture<>();

        SocketRelay() throws IOException {
            relays.incrementAndGet();
            socket = new Socket(InetAddress.getLoopbackAddress(), echo.getLocalPort());
            in = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024) {
                @Override
                public void close() throws IOException {
                    flush();
                    socket.shutdownOutput(); // stdin ended: the far side's write half ends
                }
            };
        }

        @Override
        public OutputStream getOutputStream() {
            return in;
        }

        @Override
        public InputStream getInputStream() {
            try {
                return socket.getInputStream();
            } catch (IOException e) {
                return new ByteArrayInputStream(new byte[0]);
            }
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() throws InterruptedException {
            try {
                exit.get();
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IllegalStateException(e);
            }
            return 0;
        }

        @Override
        public int exitValue() {
            if (!exit.isDone()) {
                throw new IllegalThreadStateException();
            }
            return 0;
        }

        @Override
        public void destroy() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // already closed
            }
            exit.complete(this);
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }
    }

    private static String exchange(Socket s, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        s.getOutputStream().write(bytes);
        s.getOutputStream().flush();
        return new String(s.getInputStream().readNBytes(bytes.length), StandardCharsets.UTF_8);
    }

    @Test
    void eachConnectionReachesThePortThroughItsOwnRelay() throws Exception {
        try (RelayPortForward forward = RelayPortForward.open(SocketRelay::new);
                Socket a = new Socket(InetAddress.getLoopbackAddress(), forward.localPort());
                Socket b = new Socket(InetAddress.getLoopbackAddress(), forward.localPort())) {
            a.setSoTimeout(10_000);
            b.setSoTimeout(10_000);

            // A small request is answered although the relay's stdin buffers: every read is flushed.
            assertEquals("ping", exchange(a, "ping"));
            assertEquals("pong", exchange(b, "pong"));
            assertEquals("again", exchange(a, "again"));
            assertEquals(2, relays.get());

            a.shutdownOutput();
            assertEquals(-1, a.getInputStream().read(), "the far side's end reaches this side");
        }
    }

    @Test
    void aRelayThatCannotStartClosesItsConnection() throws Exception {
        try (RelayPortForward forward = RelayPortForward.open(() -> {
                    throw new IOException("no relay tool");
                });
                Socket s = new Socket(InetAddress.getLoopbackAddress(), forward.localPort())) {
            s.setSoTimeout(10_000);
            assertEquals(-1, s.getInputStream().read());
        }
    }

    @Test
    void closingTheForwardEndsItsConnectionsAndThePort() throws Exception {
        RelayPortForward forward = RelayPortForward.open(SocketRelay::new);
        int port = forward.localPort();
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            s.setSoTimeout(10_000);
            assertEquals("hi", exchange(s, "hi"));

            forward.close();

            int read;
            try {
                read = s.getInputStream().read();
            } catch (IOException reset) {
                read = -1;
            }
            assertEquals(-1, read, "the open connection ended");
        }
        assertThrows(IOException.class, () -> new Socket(InetAddress.getLoopbackAddress(), port).close());
        forward.close(); // twice is fine
        assertTrue(relays.get() >= 1);
    }

    @Test
    void thePortIsClosedWhenCloseReturns() throws Exception {
        // close() on a server socket an accept thread waits on leaves the port open until that thread runs
        for (int i = 0; i < 50; i++) {
            RelayPortForward forward = RelayPortForward.open(SocketRelay::new);
            int port = forward.localPort();
            Thread.sleep(1); // the accept thread is waiting in accept()
            forward.close();
            assertThrows(
                    IOException.class, () -> new Socket(InetAddress.getLoopbackAddress(), port).close(), "cycle " + i);
        }
    }
}
