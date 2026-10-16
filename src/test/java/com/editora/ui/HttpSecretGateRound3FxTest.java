package com.editora.ui;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.http.HttpExchange;
import com.editora.http.HttpVars;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The third review's trust-gate findings, judged by what a 127.0.0.1 server receives, what the panel, the status bar
 * and the clipboard hold, and what the prompt was asked:
 *
 * <ul>
 *   <li>G1 — a request that is never sent (GraphQL, a missing body file or part) asks nothing and shows no secret;
 *   <li>G2 — a host that comes from an earlier response is named as such, never as nothing or a stale host;
 *   <li>G3 — {@code $dotenv} does not read a {@code .env} that links outside the folder or to a device;
 *   <li>G4 — a run cancelled or replaced before its prompt never puts the prompt up;
 *   <li>G5 — an imported cURL command's {@code {{} are sent literally, also through Copy as cURL and real curl;
 *   <li>G6 — the values a run read are kept on the exchange for masking, and nowhere in its text form.
 * </ul>
 *
 * {@code PATH} stands in for a secret: every platform has it.
 */
@Tag("fx")
class HttpSecretGateRound3FxTest {

    private static final String SECRET = System.getenv("PATH");

    private HttpServer server;
    private HttpServer other;
    private int port;
    private final List<String> hits = new CopyOnWriteArrayList<>();
    private final List<String> otherHits = new CopyOnWriteArrayList<>();
    private volatile String next = "";
    private Host host;
    private Ops ops;
    private HttpClientCoordinator coordinator;
    private final List<EditorBuffer> buffers = new ArrayList<>();

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void setUp() throws Exception {
        assertTrue(SECRET != null && SECRET.length() > 3, "the test needs PATH to be set");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            hits.add(URLDecoder.decode(ex.getRequestURI().getRawPath(), StandardCharsets.UTF_8) + "?"
                    + (ex.getRequestURI().getRawQuery() == null
                            ? ""
                            : URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8))
                    + " x-t=" + ex.getRequestHeaders().getFirst("X-T") + " body=" + body);
            byte[] out = ("{\"next\":\"" + next + "\"}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();
        other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        other.createContext("/", ex -> {
            otherHits.add(URLDecoder.decode(
                    ex.getRequestURI().getRawQuery() == null
                            ? ""
                            : ex.getRequestURI().getRawQuery(),
                    StandardCharsets.UTF_8));
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        other.start();
        host = new Host();
        host.settings.setHttpClientSupport(true);
        ops = new Ops();
        coordinator = new HttpClientCoordinator(host, ops);
    }

    @AfterEach
    void tearDown() throws Exception {
        coordinator.shutdown();
        server.stop(0);
        other.stop(0);
        FxTestSupport.runOnFx(() -> buffers.forEach(EditorBuffer::dispose));
    }

    private String url() {
        return "http://127.0.0.1:" + port;
    }

    private String otherUrl() {
        return "http://127.0.0.1:" + other.getAddress().getPort();
    }

    // --- G1 ---------------------------------------------------------------------------------------------------

    @Test
    void aRequestThatIsNeverSentAsksNothingAndShowsNoSecret(@TempDir Path dir) throws Exception {
        String[] requests = {
            "GRAPHQL " + url() + "/gql?k={{$env PATH}}\nX-T: {{$env PATH}}\n\nquery { a }\n",
            "POST " + url() + "/mb?k={{$env PATH}}\nX-T: {{$env PATH}}\n\n< ./missing.json\n",
            "POST " + url() + "/mp?k={{$env PATH}}\nContent-Type: multipart/form-data; boundary=b\n\n--b\n"
                    + "Content-Disposition: form-data; name=\"f\"; filename=\"x\"\n\n< ./nope.bin\n--b--\n",
        };
        for (int i = 0; i < requests.length; i++) {
            ops.askedReads.clear();
            host.statuses.clear();
            Path sub = Files.createDirectories(dir.resolve("r" + i));
            EditorBuffer b = httpBuffer(sub.resolve("api.http"), requests[i]);
            runAndWait(b, 0);
            assertEquals(List.of(), ops.askedReads, "a request that is never sent asks nothing: " + requests[i]);
            assertEquals(List.of(), hits, "nothing reached the server");
            HttpExchange shown =
                    FxTestSupport.callOnFx(() -> coordinator.panelForTest(b).getSelectedExchange());
            assertTrue(shown.result().failed(), "reported as not sent");
            assertNoSecretIn(shown, "the shown exchange of " + requests[i]);
            for (String s : host.statuses) {
                assertFalse(s.contains(SECRET), "the status bar showed the secret: " + s);
            }
            FxTestSupport.runOnFx(() -> Clipboard.getSystemClipboard().clear());
            FxTestSupport.runOnFx(() -> {
                host.active = b;
                coordinator.copyActiveAsCurl();
            });
            String clip =
                    FxTestSupport.callOnFx(() -> Clipboard.getSystemClipboard().getString());
            // A request that was not sent has no command to copy (Copy as cURL is disabled for it); whatever the
            // clipboard holds, it must not be the secret.
            assertFalse(
                    clip != null && clip.contains(SECRET),
                    "Copy as cURL copied the secret of a request that was not sent");
        }
    }

    // --- G2 ---------------------------------------------------------------------------------------------------

    @Test
    void aHostThatComesFromAnEarlierResponseIsNamedAsSuch(@TempDir Path dir) throws Exception {
        next = otherUrl() + "/steal";
        EditorBuffer b = httpBuffer(
                dir.resolve("api.http"),
                "### login\nGET " + url() + "/login\n\n### use\nGET {{login.response.body.$.next}}?k={{$env PATH}}\n");
        runAll(b);
        assertEquals(
                List.of(List.of(tr("dialog.httpTrust.dynamicHost"))),
                ops.askedHosts,
                "the prompt says the host is chosen by an earlier response, not an empty host");
        assertEquals(List.of(), otherHits, "declined: nothing reached the response's host");

        // A capture left by an earlier run must not be shown as the host either: this run's login decides.
        ops.askedHosts.clear();
        ops.askedReads.clear();
        ops.trust = true;
        next = url() + "/stale";
        runAndWait(b, 1); // login alone: its response, pointing at the main server, is now captured
        next = otherUrl() + "/fresh";
        runAll(b);
        assertEquals(List.of(List.of(tr("dialog.httpTrust.dynamicHost"))), ops.askedHosts, "never the stale host");
        assertEquals(1, otherHits.size(), otherHits.toString());
        assertTrue(otherHits.get(0).contains(SECRET), "approved: it went where the response said");
    }

    // --- G3 ---------------------------------------------------------------------------------------------------

    @Test
    void aDotenvThatLinksOutsideTheFolderIsNotRead(@TempDir Path dir, @TempDir Path outside) throws Exception {
        Path credentials = outside.resolve("credentials");
        Files.writeString(credentials, "[default]\naws_secret_access_key = fake-outside-value-g3\n");
        try {
            Files.createSymbolicLink(dir.resolve(".env"), credentials);
        } catch (IOException | UnsupportedOperationException noLinks) {
            assumeTrue(false, "symbolic links are not available here");
        }
        ops.trust = true;
        EditorBuffer b =
                httpBuffer(dir.resolve("api.http"), "GET " + url() + "/d?k={{$dotenv aws_secret_access_key}}\n");
        runAndWait(b, 0);
        assertEquals(1, hits.size(), hits.toString());
        assertFalse(hits.get(0).contains("fake-outside-value-g3"), "a .env linking outside the folder was read");
    }

    @Test
    void aDotenvThatLinksToADeviceIsNotRead(@TempDir Path dir) throws Exception {
        Path zero = Path.of("/dev/zero");
        assumeTrue(Files.exists(zero), "needs /dev/zero");
        try {
            Files.createSymbolicLink(dir.resolve(".env"), zero);
        } catch (IOException | UnsupportedOperationException noLinks) {
            assumeTrue(false, "symbolic links are not available here");
        }
        ops.trust = true;
        EditorBuffer b = httpBuffer(dir.resolve("api.http"), "GET " + url() + "/z?k={{$dotenv KEY}}\n");
        runAndWait(b, 0);
        assertEquals(List.of("/z?k= x-t=null body="), hits, "sent without the value, after reading nothing");
    }

    // --- G4 ---------------------------------------------------------------------------------------------------

    @Test
    void aRunReplacedOrCancelledBeforeItsPromptNeverPutsItUp(@TempDir Path dir) throws Exception {
        EditorBuffer b = httpBuffer(dir.resolve("api.http"), "GET " + url() + "/c?k={{$env PATH}}\n");
        FxTestSupport.runOnFx(() -> {
            coordinator.runRequest(b, 0);
            coordinator.runRequest(b, 0); // pressed twice: the first run is replaced before its prompt
        });
        awaitWorkers();
        assertEquals(1, ops.askedReads.size(), "one prompt for the run that goes on, none for the replaced one");

        ops.askedReads.clear();
        FxTestSupport.runOnFx(() -> {
            coordinator.runRequest(b, 0);
            coordinator.cancelRun(b); // Run, then Cancel at once
        });
        awaitWorkers();
        assertEquals(List.of(), ops.askedReads, "a cancelled run never prompts");
        assertEquals(List.of(), hits);
    }

    // --- G5 ---------------------------------------------------------------------------------------------------

    @Test
    void anImportedCurlCommandSendsItsBracesAsTyped(@TempDir Path dir) throws Exception {
        EditorBuffer b = httpBuffer(dir.resolve("api.http"), "");
        FxTestSupport.runOnFx(() -> {
            ClipboardContent c = new ClipboardContent();
            c.putString("curl '" + url() + "/imp?x={{$env PATH}}' -H 'X-T: {{token}}'");
            Clipboard.getSystemClipboard().setContent(c);
            host.active = b;
            coordinator.importCurl();
        });
        String text = FxTestSupport.callOnFx(b::getContent);
        assertTrue(text.contains(HttpVars.ESCAPE + "$env PATH}}"), text);
        assertEquals(tr("status.http.curlImportedEscaped"), host.statuses.get(host.statuses.size() - 1));
        int line = List.of(text.split("\n", -1))
                .indexOf(text.lines()
                        .filter(l -> l.startsWith("GET "))
                        .findFirst()
                        .orElseThrow());
        runAndWait(b, line);
        assertEquals(List.of(), ops.askedReads, "the imported text reads no variable, so nothing is asked");
        assertEquals(List.of("/imp?x={{$env PATH}} x-t={{token}} body="), hits, "sent as typed");

        // Copy as cURL writes the literal {{ back: the real curl sends exactly the same.
        Path curl = Path.of("/usr/bin/curl");
        assumeTrue(Files.isExecutable(curl), "needs /usr/bin/curl");
        hits.clear();
        FxTestSupport.runOnFx(() -> coordinator.copyActiveAsCurl());
        String command =
                FxTestSupport.callOnFx(() -> Clipboard.getSystemClipboard().getString());
        Process p = new ProcessBuilder("/bin/sh", "-c", command + " -s -o /dev/null")
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "curl finished");
        assertEquals(0, p.exitValue(), command + "\n" + out);
        assertEquals(List.of("/imp?x={{$env PATH}} x-t={{token}} body="), hits, "curl sent it as typed: " + command);
    }

    // --- G6 ---------------------------------------------------------------------------------------------------

    @Test
    void theValuesARunReadAreKeptForMaskingButNeverInItsText(@TempDir Path dir) throws Exception {
        ops.trust = true;
        EditorBuffer b = httpBuffer(dir.resolve("api.http"), "@k = {{$env PATH}}\nGET " + url() + "/v?k={{k}}\n");
        runAndWait(b, 1);
        assertEquals(1, hits.size());
        HttpExchange shown =
                FxTestSupport.callOnFx(() -> coordinator.panelForTest(b).getSelectedExchange());
        assertEquals(List.of(SECRET), shown.details().secretValues(), "the value read through the @var line");
        assertFalse(shown.details().toString().contains(SECRET), "the details' text form never holds a value");
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private static void assertNoSecretIn(HttpExchange ex, String what) {
        StringBuilder all = new StringBuilder(
                ex.label() + ex.url() + ex.requestBody() + ex.result().error());
        ex.headers().forEach(h -> all.append(h[0]).append(h[1]));
        ex.result().warnings().forEach(all::append);
        assertFalse(all.toString().contains(SECRET), what + " holds the secret: " + all);
    }

    private EditorBuffer httpBuffer(Path file, String text) throws Exception {
        Files.writeString(file, text);
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer created = new EditorBuffer();
            created.setPath(file);
            created.setContent(text);
            created.markClean();
            host.buffers.add(created);
            host.active = created;
            coordinator.ensureHttpPreview(created);
            return created;
        });
        HttpEnvCache.awaitIdleForTest(); // the env files are read off the FX thread
        buffers.add(b);
        return b;
    }

    private void runAndWait(EditorBuffer b, int line) throws Exception {
        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, line));
        awaitWorkers();
    }

    private void runAll(EditorBuffer b) throws Exception {
        FxTestSupport.runOnFx(() -> {
            host.active = b;
            coordinator.runFile();
        });
        awaitWorkers();
    }

    /** Waits until no run of the service is in flight and what they posted has been handled on the FX thread. */
    private void awaitWorkers() throws Exception {
        Object service = FxTestSupport.field(coordinator, "service");
        Set<?> active = FxTestSupport.field(service, "active");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!active.isEmpty()) {
            assertTrue(System.nanoTime() < deadline, "the run never ended");
            FxTestSupport.drainFx();
        }
        FxTestSupport.drainFx();
        FxTestSupport.drainFx();
    }

    private static final class Host extends CoordinatorHostStub {
        final Settings settings = new Settings();
        final List<EditorBuffer> buffers = new CopyOnWriteArrayList<>();
        final List<String> statuses = new CopyOnWriteArrayList<>();
        volatile EditorBuffer active;

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public void forEachBuffer(Consumer<EditorBuffer> action) {
            buffers.forEach(action);
        }

        @Override
        public EditorBuffer activeBuffer() {
            return active;
        }

        @Override
        public void setStatus(String message) {
            statuses.add(message);
        }
    }

    private static final class Ops implements HttpClientCoordinator.WindowOps {
        volatile boolean trust;
        final List<List<String>> askedReads = new CopyOnWriteArrayList<>();
        final List<List<String>> askedHosts = new CopyOnWriteArrayList<>();

        @Override
        public void openTab(EditorBuffer buffer) {}

        @Override
        public void updateRunGating() {}

        @Override
        public String savedEnvironment() {
            return "";
        }

        @Override
        public boolean allowSecretReads(Path file, List<String> hosts, List<String> reads) {
            askedReads.add(List.copyOf(reads));
            askedHosts.add(List.copyOf(hosts));
            return trust;
        }
    }
}
