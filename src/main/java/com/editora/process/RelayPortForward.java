package com.editora.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A loopback port on this machine that reaches a port where a program runs elsewhere: each connection
 * accepted here starts one relay process ({@link Relay}: a launcher running {@link PortRelays#argv} there)
 * whose stdin and stdout are joined to that port. It needs no network path from here to there — a rootless
 * container, Docker Desktop, a WSL distribution.
 */
public final class RelayPortForward implements PortForward {

    /** Starts one relay: a process whose stdin and stdout are the connection to the port. */
    @FunctionalInterface
    public interface Relay {
        Process start() throws IOException;
    }

    /** How long {@link #close} waits for the port to close. */
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(5);

    private final ServerSocket server;
    private final Set<AutoCloseable> open = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private volatile Thread acceptor;

    private RelayPortForward(ServerSocket server) {
        this.server = server;
    }

    /** Opens the local port; each connection to it gets a relay from {@code relay}. */
    public static RelayPortForward open(Relay relay) throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        RelayPortForward forward = new RelayPortForward(server);
        forward.acceptor = Thread.ofVirtual().name("relay-forward-accept").start(() -> {
            while (!forward.closed) {
                try {
                    Socket socket = server.accept();
                    forward.relay(relay, socket);
                } catch (IOException e) {
                    if (!forward.closed) {
                        forward.close();
                    }
                    return;
                }
            }
        });
        return forward;
    }

    private void relay(Relay relay, Socket socket) {
        Process exec;
        try {
            exec = relay.start();
        } catch (IOException e) {
            closeQuietly(socket);
            return;
        }
        AutoCloseable both = () -> {
            closeQuietly(socket);
            exec.destroyForcibly();
        };
        open.add(both);
        Runnable done = () -> {
            open.remove(both);
            try {
                both.close();
            } catch (Exception ignored) {
                // already closed
            }
        };
        Thread.ofVirtual().name("relay-forward-out").start(() -> {
            pump(socket, exec.getOutputStream(), true);
            // this side finished sending: the relay's stdin ends, and it ends the container side's write half
        });
        Thread.ofVirtual().name("relay-forward-in").start(() -> {
            pump(exec.getInputStream(), socket);
            done.run(); // the container side closed, or the relay ended: so does this connection
        });
    }

    private static void pump(Socket from, OutputStream to, boolean closeTo) {
        try (InputStream in = from.getInputStream()) {
            // Flushed after every read: a process's stdin is buffered, and a request held in the buffer is
            // never answered — the other side waits for it, and this side for the answer.
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                to.write(buffer, 0, n);
                to.flush();
            }
        } catch (IOException ignored) {
            // the connection ended
        } finally {
            if (closeTo) {
                try {
                    to.close();
                } catch (IOException ignored) {
                    // already closed
                }
            }
        }
    }

    private static void pump(InputStream from, Socket to) {
        try (OutputStream out = to.getOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = from.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
            // the connection ended
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    @Override
    public int localPort() {
        return server.getLocalPort();
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
            // already closed
        }
        awaitPortClosed();
        for (AutoCloseable c : open) {
            try {
                c.close();
            } catch (Exception ignored) {
                // already closed
            }
        }
        open.clear();
    }

    /**
     * Waits for the accept thread to leave {@code accept()}. Closing a server socket that a virtual thread is
     * blocked on only stops the poll: the thread closes the port when it next runs, and until then the port
     * still takes connections (Windows CI hit this).
     */
    private void awaitPortClosed() {
        Thread thread = acceptor;
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        try {
            thread.join(CLOSE_WAIT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
