package com.editora.container;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.editora.process.PortForward;

/**
 * A loopback port on this machine that reaches {@code host:port} as the container sees it: each connection
 * accepted here starts one {@code exec} in the container whose stdin and stdout are joined to that port by a
 * relay the container has ({@code nc}, else {@code bash}'s {@code /dev/tcp}, else {@code python3}). It works
 * for every engine and network, including rootless Podman and Docker Desktop, whose containers this machine
 * cannot reach by address.
 */
final class ContainerPortForward implements PortForward {

    private final ServerSocket server;
    private final Set<AutoCloseable> open = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    private ContainerPortForward(ServerSocket server) {
        this.server = server;
    }

    /** Opens the local port; {@code relay} is the inner command that joins its stdio to the container's port. */
    static ContainerPortForward open(ContainerExecTarget target, List<String> relay) throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        ContainerPortForward forward = new ContainerPortForward(server);
        Thread.ofVirtual().name("container-forward-accept").start(() -> {
            while (!forward.closed) {
                try {
                    Socket socket = server.accept();
                    forward.relay(target, relay, socket);
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

    private void relay(ContainerExecTarget target, List<String> relay, Socket socket) {
        Process exec;
        try {
            exec = target.relayProcess(relay);
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
        Thread.ofVirtual().name("container-forward-out").start(() -> {
            pump(socket, exec.getOutputStream(), true);
            // this side finished sending: the relay's stdin ends, and it ends the container side's write half
        });
        Thread.ofVirtual().name("container-forward-in").start(() -> {
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
        for (AutoCloseable c : open) {
            try {
                c.close();
            } catch (Exception ignored) {
                // already closed
            }
        }
        open.clear();
    }
}
