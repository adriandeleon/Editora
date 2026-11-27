package com.editora.mcp.ide;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

/**
 * A minimal WebSocket server (RFC 6455) on the loopback interface, for Claude Code's IDE connection: the HTTP upgrade
 * (refused unless {@code accept} approves the request's headers), text messages (fragmented or not), ping and close.
 * No extensions, no subprotocol. One thread accepts and one runs each connection; {@link Connection#send} may be
 * called from any thread.
 */
public final class WebSocketServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(WebSocketServer.class.getName());
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /** The largest message read; a longer one closes the connection (1009). */
    static final int MAX_MESSAGE = 16 * 1024 * 1024;

    /** How the server hears of a connection: its messages, and its end. */
    public interface Handler {
        void opened(Connection c);

        void message(Connection c, String text);

        void closed(Connection c);
    }

    private final ServerSocket server;
    private final Predicate<Map<String, String>> accept;
    private final Handler handler;
    private final List<Connection> connections = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;

    /**
     * Listens on 127.0.0.1 at {@code port} (0: any free port). {@code accept} sees the upgrade request's headers, names
     * in lower case, and says whether it may connect.
     */
    public WebSocketServer(int port, Predicate<Map<String, String>> accept, Handler handler) throws IOException {
        this.server = new ServerSocket(port, 8, InetAddress.getLoopbackAddress());
        this.accept = accept;
        this.handler = handler;
        Thread t = new Thread(this::acceptLoop, "claude-ide-accept");
        t.setDaemon(true);
        t.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    public List<Connection> connections() {
        return List.copyOf(connections);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(() -> serve(s), "claude-ide-connection");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) {
                    LOG.log(Level.FINE, "accept failed", e);
                }
            }
        }
    }

    private void serve(Socket socket) {
        Connection c = null;
        try (socket) {
            socket.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            Map<String, String> headers = readRequest(in);
            String key = headers.get("sec-websocket-key");
            boolean upgrade =
                    headers.getOrDefault("upgrade", "").toLowerCase(Locale.ROOT).contains("websocket");
            if (headers.isEmpty() || key == null || !upgrade) {
                out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                return;
            }
            if (!accept.test(headers)) {
                out.write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                return;
            }
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                            + "Sec-WebSocket-Accept: " + acceptKey(key) + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            c = new Connection(out);
            connections.add(c);
            handler.opened(c);
            readMessages(in, c);
        } catch (IOException e) {
            LOG.log(Level.FINE, "connection ended", e);
        } finally {
            if (c != null) {
                connections.remove(c);
                c.open = false;
                handler.closed(c);
            }
        }
    }

    /** The request line and headers, names lower-cased; empty when they cannot be read. Pure over {@code in}. */
    static Map<String, String> readRequest(InputStream in) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        String line = readLine(in);
        if (line == null || !line.startsWith("GET ")) {
            return headers;
        }
        headers.put(":request", line);
        for (int n = 0; n < 100; n++) {
            String h = readLine(in);
            if (h == null || h.isEmpty()) {
                return headers;
            }
            int colon = h.indexOf(':');
            if (colon > 0) {
                headers.put(
                        h.substring(0, colon).strip().toLowerCase(Locale.ROOT),
                        h.substring(colon + 1).strip());
            }
        }
        return Map.of(); // too many headers
    }

    private static @Nullable String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                String s = b.toString(StandardCharsets.ISO_8859_1);
                return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
            }
            if (b.size() > 8192) {
                return null;
            }
            b.write(c);
        }
        return null;
    }

    /** The {@code Sec-WebSocket-Accept} answer to {@code key}. Pure. */
    static String acceptKey(String key) {
        try {
            byte[] sha =
                    MessageDigest.getInstance("SHA-1").digest((key.strip() + GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(sha);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void readMessages(InputStream in, Connection c) throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        while (running && c.open) {
            int b0 = in.read();
            int b1 = in.read();
            if (b0 < 0 || b1 < 0) {
                return;
            }
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) {
                len = (readByte(in) << 8) | readByte(in);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) {
                    len = (len << 8) | readByte(in);
                }
            }
            if (!masked || len > MAX_MESSAGE || message.size() + len > MAX_MESSAGE) {
                c.close(masked ? 1009 : 1002); // a client's frames are masked (RFC 6455 5.1)
                return;
            }
            byte[] mask = in.readNBytes(4);
            byte[] payload = in.readNBytes((int) len);
            if (payload.length < len) {
                return;
            }
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i % 4];
            }
            switch (opcode) {
                case 0x0, 0x1 -> {
                    message.write(payload);
                    if (fin) {
                        String text = message.toString(StandardCharsets.UTF_8);
                        message.reset();
                        handler.message(c, text);
                    }
                }
                case 0x8 -> {
                    c.close(1000);
                    return;
                }
                case 0x9 -> c.frame(0xA, payload); // ping: pong with its data
                default -> {} // pong, binary: nothing to do
            }
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new IOException("Connection closed in a frame header");
        }
        return b;
    }

    @Override
    public void close() {
        running = false;
        for (Connection c : connections) {
            c.close(1001);
        }
        try {
            server.close();
        } catch (IOException e) {
            // closing anyway
        }
    }

    /** One client. */
    public static final class Connection {
        private final OutputStream out;
        private volatile boolean open = true;

        Connection(OutputStream out) {
            this.out = out;
        }

        public boolean isOpen() {
            return open;
        }

        /** Sends {@code text} as one text message; false when the connection is gone. */
        public boolean send(String text) {
            return frame(0x1, text.getBytes(StandardCharsets.UTF_8));
        }

        synchronized boolean frame(int opcode, byte[] payload) {
            if (!open && opcode != 0x8) {
                return false;
            }
            try {
                out.write(0x80 | opcode);
                if (payload.length < 126) {
                    out.write(payload.length);
                } else if (payload.length <= 0xFFFF) {
                    out.write(126);
                    out.write(payload.length >>> 8);
                    out.write(payload.length & 0xFF);
                } else {
                    out.write(127);
                    long n = payload.length;
                    for (int i = 7; i >= 0; i--) {
                        out.write((int) (n >>> (8 * i)) & 0xFF);
                    }
                }
                out.write(payload);
                out.flush();
                return true;
            } catch (IOException e) {
                open = false;
                return false;
            }
        }

        void close(int code) {
            if (open) {
                frame(0x8, new byte[] {(byte) (code >>> 8), (byte) code});
                open = false;
            }
        }
    }

    /** For a handler that ignores opens and closes. */
    public static Handler onMessage(java.util.function.BiConsumer<Connection, String> message) {
        Consumer<Connection> none = c -> {};
        return new Handler() {
            @Override
            public void opened(Connection c) {
                none.accept(c);
            }

            @Override
            public void message(Connection c, String text) {
                message.accept(c, text);
            }

            @Override
            public void closed(Connection c) {
                none.accept(c);
            }
        };
    }
}
