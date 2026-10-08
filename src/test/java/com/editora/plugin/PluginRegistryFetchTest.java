package com.editora.plugin;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import com.editora.io.LoopbackDownloads;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fetching a plugin registry and deciding whether it is <em>signed</em>: the index and its detached
 * {@code .sig} are served from a loopback server ({@link LoopbackDownloads}) and checked against a key pair
 * generated here. "Signed" is what lets the "require signed plugins" setting admit a registry, so every way a
 * signature can be absent, unreadable or about something else has to come out as <b>unsigned</b> — never as
 * signed, and never as an exception.
 */
class PluginRegistryFetchTest {

    private static final String INDEX_URL = "https://plugins.example.org/registry/index.json";
    private static final String SIG_URL = INDEX_URL + ".sig";
    private static final long MAX_INDEX_BYTES = 8L * 1024 * 1024;
    private static final int MAX_SIG_BYTES = 4096;

    private static final byte[] INDEX = ("{\"schemaVersion\":1,\"plugins\":["
                    + "{\"id\":\"notes\",\"name\":\"Notes\",\"version\":\"1.2.0\","
                    + "\"download\":\"https://plugins.example.org/notes-1.2.0.zip\",\"sha256\":\"abc123\"},"
                    + "{\"id\":\"clock\",\"name\":\"Clock\",\"version\":\"0.3\"}]}")
            .getBytes(StandardCharsets.UTF_8);

    private KeyPair registryKeys;
    private LoopbackDownloads web;
    private PluginRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        registryKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        web = new LoopbackDownloads();
        registry = new PluginRegistry(web.client(), () -> registryKeys.getPublic());
    }

    @AfterEach
    void tearDown() {
        registry.shutdown();
        web.close();
    }

    private static String sign(PrivateKey key, byte[] data) throws Exception {
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(key);
        s.update(data);
        return Base64.getEncoder().encodeToString(s.sign());
    }

    private String signature() throws Exception {
        return sign(registryKeys.getPrivate(), INDEX);
    }

    /** An index that parsed and listed its plugins, but must not count as signed. */
    private static void assertListedButUnsigned(PluginRegistry.Result result) {
        assertTrue(result.ok(), String.valueOf(result.error()));
        assertFalse(result.signed(), "must not be reported as signed");
        assertEquals(
                List.of("notes", "clock"),
                result.entries().stream().map(e -> e.id).toList());
    }

    // --- signed -------------------------------------------------------------------------------------

    @Test
    void anIndexWithAValidSignatureIsSigned() throws Exception {
        web.serve(INDEX_URL, INDEX);
        web.serve(SIG_URL, signature() + "\n"); // as written by a shell: base64 and a newline

        PluginRegistry.Result result = registry.fetchSync(INDEX_URL);

        assertTrue(result.ok(), String.valueOf(result.error()));
        assertNull(result.error());
        assertTrue(result.signed());
        assertEquals(2, result.entries().size());
        RegistryEntry notes = result.entries().get(0);
        assertEquals("notes", notes.id);
        assertEquals("1.2.0", notes.version);
        assertEquals("https://plugins.example.org/notes-1.2.0.zip", notes.download);
        assertEquals("abc123", notes.sha256);
        assertEquals(List.of(INDEX_URL, SIG_URL), web.requests());
    }

    @Test
    void whitespaceAroundTheConfiguredUrlIsIgnored() throws Exception {
        web.serve(INDEX_URL, INDEX);
        web.serve(SIG_URL, signature());

        PluginRegistry.Result result = registry.fetchSync("  " + INDEX_URL + "\n");

        assertTrue(result.signed());
        assertEquals(List.of(INDEX_URL, SIG_URL), web.requests());
    }

    // --- unsigned: every way a signature can be missing or wrong ---------------------------------------

    @Test
    void aRegistryWithNoSignatureFileIsUnsigned() {
        web.serve(INDEX_URL, INDEX); // the .sig answers 404

        assertListedButUnsigned(registry.fetchSync(INDEX_URL));
        assertEquals(List.of(INDEX_URL, SIG_URL), web.requests(), "the signature was looked for");
    }

    @Test
    void aSignatureRequestThatFailsLeavesTheRegistryUnsigned() {
        web.serve(INDEX_URL, INDEX);
        for (int status : new int[] {403, 500, 503}) {
            web.status(SIG_URL, status);
            assertListedButUnsigned(registry.fetchSync(INDEX_URL));
        }
    }

    /** An error page served with 200 — what a captive portal or a misconfigured host does. */
    @Test
    void aSignatureThatIsNotASignatureLeavesTheRegistryUnsigned() throws Exception {
        web.serve(INDEX_URL, INDEX);
        byte[] random = new byte[64];
        Arrays.fill(random, (byte) 7);
        for (String garbage : List.of(
                "<html><body>Not Found</body></html>",
                "!!not base64!!",
                "",
                "   \n",
                Base64.getEncoder().encodeToString(random), // the right length, the wrong bytes
                signature().substring(0, 40), // truncated
                signature() + signature())) { // too long
            web.serve(SIG_URL, garbage);
            assertListedButUnsigned(registry.fetchSync(INDEX_URL));
        }
    }

    /** The signature is over the exact bytes served: an index changed after signing is not signed. */
    @Test
    void aSignatureOverDifferentBytesLeavesTheRegistryUnsigned() throws Exception {
        byte[] tampered = new String(INDEX, StandardCharsets.UTF_8)
                .replace("notes-1.2.0.zip", "notes-1.2.1.zip")
                .getBytes(StandardCharsets.UTF_8);
        web.serve(INDEX_URL, tampered);
        web.serve(SIG_URL, signature()); // a real signature — of the original index

        PluginRegistry.Result result = registry.fetchSync(INDEX_URL);

        assertTrue(result.ok());
        assertFalse(result.signed());
        assertEquals(
                "https://plugins.example.org/notes-1.2.1.zip", result.entries().get(0).download);

        // Not even a change no parser would notice.
        web.serve(INDEX_URL, (new String(INDEX, StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8));
        assertListedButUnsigned(registry.fetchSync(INDEX_URL));
    }

    @Test
    void aSignatureMadeWithAnotherKeyLeavesTheRegistryUnsigned() throws Exception {
        KeyPair someoneElse = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        web.serve(INDEX_URL, INDEX);
        web.serve(SIG_URL, sign(someoneElse.getPrivate(), INDEX));

        assertListedButUnsigned(registry.fetchSync(INDEX_URL));
    }

    @Test
    void aSignatureDownloadCutOffPartWayLeavesTheRegistryUnsigned() throws Exception {
        byte[] sig = signature().getBytes(StandardCharsets.UTF_8);
        web.serve(INDEX_URL, INDEX);
        web.cutOff(SIG_URL, Arrays.copyOf(sig, sig.length / 2), sig.length);

        assertListedButUnsigned(registry.fetchSync(INDEX_URL));
    }

    /**
     * The signature download is capped. The body here is a <em>valid</em> signature followed by padding the
     * reader would strip, so only the cap stands between it and "signed".
     */
    @Test
    void anOversizeSignatureFileIsNotRead() throws Exception {
        web.serve(INDEX_URL, INDEX);
        String valid = signature();

        web.serve(SIG_URL, valid + " ".repeat(MAX_SIG_BYTES - valid.length()));
        assertTrue(registry.fetchSync(INDEX_URL).signed(), "exactly at the cap is still read");

        web.serve(SIG_URL, valid + " ".repeat(MAX_SIG_BYTES - valid.length() + 1));
        assertListedButUnsigned(registry.fetchSync(INDEX_URL));
    }

    /** With no registry key in the build there is nothing to verify against — and nothing to ask for. */
    @Test
    void withoutARegistryKeyNothingIsEverSigned() throws Exception {
        PluginRegistry keyless = new PluginRegistry(web.client(), () -> null);
        try {
            web.serve(INDEX_URL, INDEX);
            web.serve(SIG_URL, signature());

            assertListedButUnsigned(keyless.fetchSync(INDEX_URL));
            assertEquals(List.of(INDEX_URL), web.requests());
        } finally {
            keyless.shutdown();
        }
    }

    // --- errors -------------------------------------------------------------------------------------

    /** A registry that cannot be listed is an error with a message, with no entries and never signed. */
    private static void assertError(PluginRegistry.Result result, String expectedInMessage) {
        assertFalse(result.ok());
        assertNotNull(result.error());
        assertTrue(result.error().contains(expectedInMessage), result.error());
        assertFalse(result.signed());
        assertEquals(List.of(), result.entries());
    }

    @Test
    void aRegistryUrlThatIsNotHttpsIsRefusedWithoutARequest() {
        for (String url : Arrays.asList(
                "http://plugins.example.org/registry/index.json",
                "file:///etc/passwd",
                "ftp://plugins.example.org/index.json",
                "plugins.example.org/index.json",
                "",
                null)) {
            assertError(registry.fetchSync(url), "registry url must be https");
        }
        assertEquals(List.of(), web.requests());
    }

    @Test
    void anIndexThatCannotBeFetchedIsAnErrorCarryingTheStatus() {
        web.status(INDEX_URL, 404);
        assertError(registry.fetchSync(INDEX_URL), "HTTP 404");

        web.status(INDEX_URL, 500);
        assertError(registry.fetchSync(INDEX_URL), "HTTP 500");
        assertFalse(web.requests().contains(SIG_URL), "no signature is fetched for an index that is not there");
    }

    @Test
    void anIndexThatIsNotJsonIsAnError() throws Exception {
        byte[] page = "<html>It works!</html>".getBytes(StandardCharsets.UTF_8);
        web.serve(INDEX_URL, page);
        web.serve(SIG_URL, sign(registryKeys.getPrivate(), page)); // signed, even

        PluginRegistry.Result result = registry.fetchSync(INDEX_URL);

        assertFalse(result.ok());
        assertFalse(result.error().isBlank());
        assertFalse(result.signed());
        assertEquals(List.of(), result.entries());
    }

    @Test
    void anIndexDownloadCutOffPartWayIsAnError() {
        web.cutOff(INDEX_URL, Arrays.copyOf(INDEX, INDEX.length / 2), INDEX.length);

        PluginRegistry.Result result = registry.fetchSync(INDEX_URL);

        assertFalse(result.ok());
        assertFalse(result.signed());
        assertEquals(List.of(), result.entries());
    }

    @Test
    void aUrlThatIsNotAUrlIsAnErrorNotAnException() {
        PluginRegistry.Result result = registry.fetchSync("https://plugins.example.org/has a space/index.json");

        assertFalse(result.ok());
        assertFalse(result.signed());
        assertEquals(List.of(), web.requests());
    }

    /**
     * The index download is capped at 8 MB. The body is valid JSON padded with whitespace, correctly signed,
     * so the cap is the only reason it is refused.
     */
    @Test
    void anOversizeIndexIsRefusedEvenWhenCorrectlySigned() throws Exception {
        byte[] huge = Arrays.copyOf(INDEX, (int) MAX_INDEX_BYTES + 1);
        Arrays.fill(huge, INDEX.length, huge.length, (byte) ' ');
        web.serve(INDEX_URL, huge);
        web.serve(SIG_URL, sign(registryKeys.getPrivate(), huge));

        assertError(registry.fetchSync(INDEX_URL), "exceeds " + MAX_INDEX_BYTES + " bytes");
        assertEquals(List.of(INDEX_URL), web.requests());
    }

    @Test
    void anIndexExactlyAtTheCapIsRead() throws Exception {
        byte[] full = Arrays.copyOf(INDEX, (int) MAX_INDEX_BYTES);
        Arrays.fill(full, INDEX.length, full.length, (byte) ' ');
        web.serve(INDEX_URL, full);
        web.serve(SIG_URL, sign(registryKeys.getPrivate(), full));

        PluginRegistry.Result result = registry.fetchSync(INDEX_URL);

        assertTrue(result.ok(), String.valueOf(result.error()));
        assertTrue(result.signed());
        assertEquals(2, result.entries().size());
    }
}
