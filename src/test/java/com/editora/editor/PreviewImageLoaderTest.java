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

    // --- file: hardening, byte cap, raster clamp ---

    @Test
    void uncShapedFileUrlsAreBlockedHoweverTheyAreSpelled() {
        assertTrue(blocked("file://attacker/share/x.png"), "an authority is a remote host");
        assertTrue(blocked("file:////attacker/share/x.png"), "no authority, but the path is //attacker/share");
        assertTrue(blocked("file:///%5C%5Cattacker/share/x.png"), "\\\\attacker\\share percent-encoded");
        assertTrue(blocked("file:///%5Cattacker/share/x.png"), "/\\attacker is two separators on Windows");
        assertTrue(blocked("file:relative.png"), "an opaque file: URL resolves against the process cwd");
        assertFalse(blocked("file:///home/u/notes/pic.png"));
        assertFalse(blocked("file:///C:/Users/u/pic.png"));
        assertTrue(PreviewImageLoader.isUncLike("//host/share"));
        assertTrue(PreviewImageLoader.isUncLike("\\\\host\\share"));
        assertTrue(PreviewImageLoader.isUncLike("/\\host"));
        assertFalse(PreviewImageLoader.isUncLike("/home/u"));
        assertFalse(PreviewImageLoader.isUncLike("a"));
        assertFalse(PreviewImageLoader.isUncLike(null));
    }

    @Test
    void aFileUrlIsReadOnlyWhenItIsARegularFile(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Path png = java.nio.file.Files.write(dir.resolve("a.png"), new byte[] {1, 2, 3});
        assertEquals(3, PreviewImageLoader.fetchBytes(png.toUri().toString()).length);
        // a directory used to come back as a generated listing (FileURLConnection); it is not an image
        assertNull(PreviewImageLoader.fetchBytes(dir.toUri().toString()));
        assertNull(
                PreviewImageLoader.fetchBytes(dir.resolve("missing.png").toUri().toString()));
    }

    @Test
    void readsAreCappedInsteadOfUnbounded(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path big = dir.resolve("big.png");
        try (var ch = java.nio.file.Files.newByteChannel(
                big, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
            ch.position(PreviewImageLoader.MAX_IMAGE_BYTES); // sparse: one byte past the cap
            ch.write(java.nio.ByteBuffer.wrap(new byte[] {1}));
        }
        assertNull(PreviewImageLoader.fetchBytes(big.toUri().toString()), "over the cap: not read at all");
        // an endless stream (a slow-drip response, /dev/zero behind a link) stops at the cap
        java.io.InputStream endless = new java.io.InputStream() {
            @Override
            public int read() {
                return 0;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return len;
            }
        };
        assertNull(PreviewImageLoader.readCapped(endless));
        assertEquals(4, PreviewImageLoader.readCapped(new java.io.ByteArrayInputStream(new byte[4])).length);
    }

    @Test
    void anSvgsDeclaredSizeIsClampedBeforeABitmapIsAllocated() {
        PreviewImageLoader.Raster badge = PreviewImageLoader.rasterFor(120, 20);
        assertEquals(240, badge.pixelWidth());
        assertEquals(40, badge.pixelHeight());
        assertEquals(2.0, badge.scale());

        PreviewImageLoader.Raster huge = PreviewImageLoader.rasterFor(100_000, 100_000);
        assertTrue((long) huge.pixelWidth() * huge.pixelHeight() <= PreviewImageLoader.MAX_RASTER_PIXELS);
        assertEquals(huge.pixelWidth(), huge.pixelHeight(), "scaled down uniformly");
        assertEquals(100_000, huge.logicalWidth(), "the logical size is kept; only the bitmap shrinks");

        PreviewImageLoader.Raster sliver = PreviewImageLoader.rasterFor(1e9, 0.001);
        assertTrue(sliver.pixelWidth() <= PreviewImageLoader.MAX_RASTER_SIDE);
        assertEquals(1, sliver.pixelHeight());

        for (double bad : new double[] {0, -5, Double.NaN, Double.POSITIVE_INFINITY}) {
            PreviewImageLoader.Raster fallback = PreviewImageLoader.rasterFor(bad, bad);
            assertEquals(200, fallback.pixelWidth(), "falls back to the badge default: " + bad);
            assertEquals(40, fallback.pixelHeight());
        }
    }

    @Test
    void aGiantDeclaredSvgRasterizesToABoundedBitmapInsteadOfExhaustingTheHeap() throws Exception {
        byte[] png = PreviewImageLoader.svgToPng(b("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"200000\""
                + " height=\"200000\"><rect width=\"10\" height=\"10\" fill=\"red\"/></svg>"));
        assertTrue(png != null && png.length > 8, "rendered, at a clamped size");
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        assertTrue((long) img.getWidth() * img.getHeight() <= PreviewImageLoader.MAX_RASTER_PIXELS);
    }

    // --- the export fetcher is the preview's guarded fetcher ---

    @Test
    void theExportFetcherRefusesWhatThePreviewRefuses(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Files.write(dir.resolve("pic.png"), new byte[] {9, 9});
        // local files: relative to the document, regular files only, never the process cwd
        assertEquals(2, PreviewImageLoader.fetchForExport("pic.png", dir).length);
        assertEquals(
                2,
                PreviewImageLoader.fetchForExport(dir.resolve("pic.png").toUri().toString(), null).length);
        assertNull(PreviewImageLoader.fetchForExport("pic.png", null));
        assertNull(PreviewImageLoader.fetchForExport(".", dir), "a directory");
        assertNull(PreviewImageLoader.fetchForExport("missing.png", dir));
        // internal / metadata / loopback hosts: refused before any connection is opened
        assertNull(PreviewImageLoader.fetchForExport("http://127.0.0.1:1/x.png", dir));
        assertNull(PreviewImageLoader.fetchForExport("http://169.254.169.254/latest/meta-data/", dir));
        assertNull(PreviewImageLoader.fetchForExport("http://10.0.0.1/x.png", dir));
        assertNull(PreviewImageLoader.fetchForExport("http://[::1]/x.png", dir));
        // UNC shapes: never even stat-ed
        assertNull(PreviewImageLoader.fetchForExport("file://attacker/share/x.png", dir));
        assertNull(PreviewImageLoader.fetchForExport("//attacker/share/x.png", dir));
        assertNull(PreviewImageLoader.fetchForExport("\\\\attacker\\share\\x.png", dir));
        assertNull(PreviewImageLoader.fetchForExport("ftp://example.com/x.png", dir));
        assertNull(PreviewImageLoader.fetchForExport(" ", dir));
        assertNull(PreviewImageLoader.fetchForExport(null, dir));
        // data: still works
        assertEquals(3, PreviewImageLoader.fetchForExport("data:image/png;base64,AQID", dir).length);
    }
}
