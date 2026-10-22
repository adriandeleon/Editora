package com.editora.editor.markdown;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The preview's image fetch connects to the address that was checked, and to nothing else. */
class PinnedHttpTest {

    private HttpServer server;
    private final AtomicReference<String> hostHeader = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/sized", exchange -> {
            hostHeader.set(exchange.getRequestHeaders().getFirst("Host"));
            byte[] body = "0123456789".getBytes(StandardCharsets.US_ASCII);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/chunked", exchange -> {
            exchange.sendResponseHeaders(200, 0); // length unknown: the JDK server sends it chunked
            exchange.getResponseBody().write(new byte[30_000]);
            exchange.close();
        });
        server.createContext("/moved", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/gone", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI uri(String host, String path) {
        return URI.create("http://" + host + ":" + server.getAddress().getPort() + path);
    }

    @Test
    void theConnectionGoesToThePinnedAddressWhateverTheNameWouldResolveTo() throws Exception {
        // "rebind.invalid" resolves to nothing at all: only the address handed in can have answered.
        PinnedHttp.Response r =
                PinnedHttp.get(uri("rebind.invalid", "/sized?x=1"), InetAddress.getLoopbackAddress(), 3000, 1 << 20);
        assertEquals(200, r.status());
        assertArrayEquals("0123456789".getBytes(StandardCharsets.US_ASCII), r.body());
        assertEquals("rebind.invalid:" + server.getAddress().getPort(), hostHeader.get(), "the name is only a header");
    }

    @Test
    void chunkedBodiesRedirectsErrorsAndTheByteCap() throws Exception {
        InetAddress local = InetAddress.getLoopbackAddress();
        assertEquals(
                30_000,
                PinnedHttp.get(uri("h.invalid", "/chunked"), local, 3000, 1 << 20)
                        .body()
                        .length);
        assertNull(
                PinnedHttp.get(uri("h.invalid", "/chunked"), local, 3000, 1000).body(), "over the cap: no body");
        assertNull(PinnedHttp.get(uri("h.invalid", "/sized"), local, 3000, 5).body());
        PinnedHttp.Response moved = PinnedHttp.get(uri("h.invalid", "/moved"), local, 3000, 1 << 20);
        assertEquals(302, moved.status());
        assertEquals("http://169.254.169.254/latest/meta-data", moved.location(), "left to the caller, who checks it");
        assertThrows(IOException.class, () -> PinnedHttp.get(uri("h.invalid", "/gone"), local, 3000, 1 << 20));
    }

    @Test
    void requestTextAndResponseParsing() throws Exception {
        assertEquals(
                "GET /a%20b?q=1 HTTP/1.1\r\nHost: example.org\r\nUser-Agent: Editora\r\nAccept: */*\r\nConnection: close\r\n\r\n",
                PinnedHttp.request(URI.create("https://example.org/a%20b?q=1"), "example.org", 443, true));
        assertTrue(PinnedHttp.request(URI.create("http://example.org"), "example.org", 8080, false)
                .startsWith("GET / HTTP/1.1\r\nHost: example.org:8080\r\n"));
        String untilClose = "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\n\r\nbody until close";
        assertEquals("body until close", new String(read(untilClose, 100).body(), StandardCharsets.US_ASCII));
        String chunked =
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4;ext=1\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n";
        assertEquals("Wikipedia", new String(read(chunked, 100).body(), StandardCharsets.US_ASCII));
        assertThrows(IOException.class, () -> read("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nshort", 100));
        assertThrows(IOException.class, () -> read("SSH-2.0-OpenSSH\r\n", 100), "not HTTP at all");
        assertThrows(IOException.class, () -> read("", 100));
        assertThrows(IOException.class, () -> read("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n", 100));
    }

    private static PinnedHttp.Response read(String response, int max) throws IOException {
        return PinnedHttp.read(new ByteArrayInputStream(response.getBytes(StandardCharsets.ISO_8859_1)), max);
    }

    @Test
    void onlyAHostWhoseEveryAddressIsPublicIsFetched() throws Exception {
        assertNull(PreviewImageLoader.publicAddress(URI.create("http://127.0.0.1/x.png")));
        assertNull(PreviewImageLoader.publicAddress(URI.create("http://localhost/x.png")));
        assertNull(PreviewImageLoader.publicAddress(URI.create("http://100.64.0.1/x.png")), "carrier-grade NAT");
        assertNull(PreviewImageLoader.publicAddress(URI.create("ftp://example.org/x.png")));
        assertNull(PreviewImageLoader.publicAddress(URI.create("http:///x.png")));
        assertEquals(
                InetAddress.getByName("93.184.216.34"),
                PreviewImageLoader.publicAddress(URI.create("https://93.184.216.34/x.png")),
                "a literal public address needs no lookup");
        assertTrue(PreviewImageLoader.isInternalAddress(InetAddress.getByName("100.64.0.1")));
        assertTrue(PreviewImageLoader.isInternalAddress(InetAddress.getByName("100.127.255.254")));
        assertFalse(PreviewImageLoader.isInternalAddress(InetAddress.getByName("100.63.255.255")));
        assertFalse(PreviewImageLoader.isInternalAddress(InetAddress.getByName("100.128.0.1")));
    }

    @Test
    void remoteImagesCanBeSwitchedOff() {
        assertTrue(PreviewImageLoader.isRemote(" HTTPS://example.org/a.png"));
        assertTrue(PreviewImageLoader.isRemote("http://example.org/a.png"));
        assertFalse(PreviewImageLoader.isRemote("file:///tmp/a.png"));
        assertFalse(PreviewImageLoader.isRemote("data:image/png;base64,AAAA"));
        assertFalse(PreviewImageLoader.isRemote(null));
        MarkdownRenderer.ImagePolicy document = MarkdownRenderer.ImagePolicy.DOCUMENT;
        try {
            PreviewImageLoader.setRemoteImages(false);
            assertFalse(document.allows("https://example.org/a.png"), "nothing leaves the machine");
            assertTrue(document.allows("file:///tmp/a.png"), "local files still load");
            assertTrue(document.allows("data:image/png;base64,AAAA"));
            assertNull(PreviewImageLoader.fetchForExport("https://example.org/a.png", null), "nor for an export");
        } finally {
            PreviewImageLoader.setRemoteImages(true);
        }
        assertTrue(document.allows("https://example.org/a.png"), "on by default");
    }
}
