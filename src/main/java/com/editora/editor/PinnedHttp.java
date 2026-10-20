package com.editora.editor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.jspecify.annotations.Nullable;

/**
 * One HTTP(S) GET to an address that has already been chosen.
 *
 * <p>The preview refuses to fetch an image from the local machine or the internal network, and decides that
 * from the addresses the URL's host resolves to. {@code URLConnection} then resolves the host <em>again</em> to
 * connect — and a hostile DNS server can answer the check with a public address and the connection with
 * {@code 127.0.0.1} or {@code 169.254.169.254} (DNS rebinding). Here the socket is opened to the very address
 * that was checked; the host name is used only for the {@code Host} header and, over TLS, for SNI and the
 * certificate check.
 *
 * <p>Deliberately small: one request, {@code Connection: close}, no cookies, no authentication, no
 * compression, redirects left to the caller (who checks each hop). Bodies by {@code Content-Length}, chunked,
 * or until the server closes.
 */
final class PinnedHttp {

    private PinnedHttp() {}

    /** A response: {@code location} for a 3xx, {@code body} otherwise (null when over the byte cap). */
    record Response(int status, @Nullable String location, byte @Nullable [] body) {}

    /**
     * Fetches {@code uri} from {@code address}. Throws on anything that is not a well-formed HTTP response,
     * a TLS failure included; the body is null when it exceeds {@code maxBytes}.
     */
    static Response get(URI uri, InetAddress address, int timeoutMs, int maxBytes) throws IOException {
        boolean tls = "https".equalsIgnoreCase(uri.getScheme());
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : (tls ? 443 : 80);
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(address, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            if (tls) {
                SSLSocket ssl = (SSLSocket)
                        ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(socket, host, port, true);
                SSLParameters params = ssl.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS"); // the certificate must be the host's
                ssl.setSSLParameters(params);
                ssl.startHandshake();
                socket = ssl;
            }
            OutputStream out = socket.getOutputStream();
            out.write(request(uri, host, port, tls).getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return read(socket.getInputStream(), maxBytes);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // nothing left to release
            }
        }
    }

    /** The request text for {@code uri}. Pure; unit-tested. */
    static String request(URI uri, String host, int port, boolean tls) {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path += "?" + uri.getRawQuery();
        }
        boolean defaultPort = port == (tls ? 443 : 80);
        String hostHeader = (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + (defaultPort ? "" : ":" + port);
        return "GET " + path + " HTTP/1.1\r\nHost: " + hostHeader
                + "\r\nUser-Agent: Editora\r\nAccept: */*\r\nConnection: close\r\n\r\n"; // some CDNs reject a missing
        // UA
    }

    /** Parses one response from {@code in}. Package-private for tests. */
    static Response read(InputStream in, int maxBytes) throws IOException {
        String statusLine = line(in);
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
            throw new IOException("not an HTTP response");
        }
        int status;
        try {
            status = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("not an HTTP response", e);
        }
        long length = -1;
        boolean chunked = false;
        String location = null;
        for (int headers = 0; ; headers++) {
            String header = line(in);
            if (header.isEmpty()) {
                break;
            }
            if (headers > 200) {
                throw new IOException("too many headers");
            }
            int colon = header.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = header.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = header.substring(colon + 1).strip();
            switch (name) {
                case "content-length" -> {
                    try {
                        length = Long.parseLong(value);
                    } catch (NumberFormatException e) {
                        throw new IOException("bad Content-Length", e);
                    }
                }
                case "transfer-encoding" ->
                    chunked = value.toLowerCase(Locale.ROOT).contains("chunked");
                case "location" -> location = value;
                default -> {}
            }
        }
        if (status >= 300 && status < 400) {
            return new Response(status, location, null);
        }
        if (status != 200) {
            throw new IOException("HTTP " + status);
        }
        if (length > maxBytes) {
            return new Response(status, null, null);
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        boolean fits =
                chunked ? chunks(in, body, maxBytes) : copy(in, body, length < 0 ? Long.MAX_VALUE : length, maxBytes);
        if (fits && !chunked && length >= 0 && body.size() < length) {
            throw new IOException("the response was cut short");
        }
        return new Response(status, null, fits ? body.toByteArray() : null);
    }

    /** Copies up to {@code count} bytes; false as soon as {@code out} would pass {@code maxBytes}. */
    private static boolean copy(InputStream in, ByteArrayOutputStream out, long count, int maxBytes)
            throws IOException {
        byte[] buf = new byte[8192];
        long left = count;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) {
                break;
            }
            if (out.size() + (long) n > maxBytes) {
                return false;
            }
            out.write(buf, 0, n);
            left -= n;
        }
        return true;
    }

    private static boolean chunks(InputStream in, ByteArrayOutputStream out, int maxBytes) throws IOException {
        while (true) {
            String sizeLine = line(in);
            int semi = sizeLine.indexOf(';');
            long size;
            try {
                size = Long.parseLong((semi < 0 ? sizeLine : sizeLine.substring(0, semi)).strip(), 16);
            } catch (NumberFormatException e) {
                throw new IOException("bad chunk size", e);
            }
            if (size < 0) {
                throw new IOException("bad chunk size");
            }
            if (size == 0) {
                return true; // trailers, if any, are not needed
            }
            int before = out.size();
            if (!copy(in, out, size, maxBytes)) {
                return false;
            }
            if (out.size() - before < size) {
                throw new IOException("the response was cut short");
            }
            line(in); // the CRLF after the chunk
        }
    }

    /** One CRLF- (or LF-) terminated line of the header section, without its terminator. */
    private static String line(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int c; (c = in.read()) >= 0; ) {
            if (c == '\n') {
                return sb.toString();
            }
            if (c != '\r') {
                sb.append((char) c);
            }
            if (sb.length() > 16_384) {
                throw new IOException("header line too long");
            }
        }
        if (sb.length() == 0) {
            throw new IOException("the connection closed before a response");
        }
        return sb.toString();
    }
}
