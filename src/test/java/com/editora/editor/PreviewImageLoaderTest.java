package com.editora.editor;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the pure SVG content sniff + JSVG rasterization used by the preview image loader. */
class PreviewImageLoaderTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void detectsPlainSvg() {
        assertTrue(PreviewImageLoader.looksLikeSvg(b("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>")));
    }

    @Test
    void detectsSvgAfterXmlProlog() {
        assertTrue(PreviewImageLoader.looksLikeSvg(
                b("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!-- a badge -->\n<svg width=\"100\"/>")));
        assertTrue(PreviewImageLoader.looksLikeSvg(b("<SVG />"))); // case-insensitive
    }

    @Test
    void rejectsRasterAndOtherContent() {
        assertFalse(PreviewImageLoader.looksLikeSvg(new byte[] {(byte) 0x89, 'P', 'N', 'G'})); // PNG magic
        assertFalse(PreviewImageLoader.looksLikeSvg(b("<html><body>not svg</body></html>")));
        assertFalse(PreviewImageLoader.looksLikeSvg(b("just some text")));
        assertFalse(PreviewImageLoader.looksLikeSvg(new byte[0]));
    }

    @Test
    void rasterizesValidSvgToPng() {
        byte[] png = PreviewImageLoader.svgToPng(b("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"20\""
                + " height=\"20\"><rect width=\"20\" height=\"20\" fill=\"red\"/></svg>"));
        assertTrue(png != null && png.length > 8, "expected non-empty PNG bytes");
        // PNG signature: 0x89 'P' 'N' 'G'
        assertEquals((byte) 0x89, png[0]);
        assertEquals((byte) 'P', png[1]);
        assertEquals((byte) 'N', png[2]);
        assertEquals((byte) 'G', png[3]);
    }

    @Test
    void svgToPngReturnsNullOnGarbage() {
        assertNull(PreviewImageLoader.svgToPng(b("this is not an svg at all")));
    }

    // --- SSRF / local-network guard (isBlockedTarget + isInternalAddress) ---

    private static boolean blocked(String url) {
        return PreviewImageLoader.isBlockedTarget(java.net.URI.create(url));
    }

    @Test
    void blocksCloudMetadataAndInternalHosts() {
        // The classic blind-SSRF targets an untrusted markdown ![](…) could reach when the preview renders.
        assertTrue(blocked("http://169.254.169.254/latest/meta-data/"), "AWS/GCP metadata (link-local)");
        assertTrue(blocked("http://127.0.0.1:8080/"), "loopback");
        assertTrue(blocked("http://localhost/admin"), "loopback by name");
        assertTrue(blocked("http://10.0.0.5/"), "RFC-1918");
        assertTrue(blocked("http://192.168.1.1/"), "RFC-1918");
        assertTrue(blocked("http://172.16.0.1/"), "RFC-1918");
        assertTrue(blocked("http://[::1]/"), "IPv6 loopback");
        assertTrue(blocked("http://0.0.0.0/"), "any-local");
    }

    @Test
    void blocksUncFileUrlsButAllowsLocalFiles() {
        assertTrue(blocked("file://attacker-host/share/x.png"), "UNC path — SMB credential leak");
        assertFalse(blocked("file:///Users/me/doc/pic.png"), "a local file (how relative images load) is fine");
    }

    @Test
    void blocksNonImageSchemes() {
        assertTrue(blocked("ftp://example.com/x.png"));
        assertTrue(blocked("jar:file:///x.jar!/y.png"));
    }

    @Test
    void allowsPublicHttpAndDataUris() {
        // Literal public IPs so the test needs no DNS (hermetic): 8.8.8.8 / 1.1.1.1 are public unicast.
        assertFalse(blocked("http://8.8.8.8/logo.png"), "a public host is fine");
        assertFalse(blocked("https://1.1.1.1/badge.svg"));
        assertFalse(blocked("data:image/png;base64,iVBORw0KGgo="), "inline data URIs never touch the network");
    }

    @Test
    void internalAddressClassifierCoversIpv6Ula() throws Exception {
        assertTrue(PreviewImageLoader.isInternalAddress(java.net.InetAddress.getByName("fc00::1")), "IPv6 ULA");
        assertTrue(PreviewImageLoader.isInternalAddress(java.net.InetAddress.getByName("fd12:3456::1")), "IPv6 ULA");
        assertFalse(
                PreviewImageLoader.isInternalAddress(java.net.InetAddress.getByName("2606:4700:4700::1111")),
                "a public IPv6 (1.1.1.1's v6) is not internal");
    }
}
