package com.editora.http;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.editora.i18n.Messages;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Response handlers and checks end to end, against a server on 127.0.0.1 and the {@code node} on the PATH (skipped
 * without one): what the next request sends after a handler or {@code @capture} stores a value, what the tests say,
 * and what a handler may not do — run from an untrusted folder, read the editor's environment, write a file, start a
 * process, run forever, or see a secret the request read.
 */
@Tag("fx")
class ResponseHandlersIntegrationTest {

    private HttpServer server;
    private int port;
    private HttpClientService svc;
    private final List<String> seen = new CopyOnWriteArrayList<>();

    @TempDir
    Path dir;

    @TempDir
    Path cache;

    @BeforeAll
    static void bootFx() {
        try {
            javafx.application.Platform.startup(() -> {});
        } catch (IllegalStateException alreadyRunning) {
            // another test booted it
        }
        javafx.application.Platform.setImplicitExit(false);
        Messages.init("en");
    }

    private static boolean nodeAvailable() {
        return NodeResponseScripts.version(com.editora.process.ProcessRunner.resolveExecutable(List.of("node"))
                        .get(0))
                != null;
    }

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "test-http-server");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/login", ex -> {
            seen.add("login");
            reply(ex, 200, "application/json", "{\"token\":\"tok-123\",\"user\":{\"id\":7}}");
        });
        server.createContext("/me", ex -> {
            seen.add("me auth=" + ex.getRequestHeaders().getFirst("Authorization"));
            reply(ex, 200, "application/json", "{\"id\":7}");
        });
        server.createContext("/hit", ex -> {
            seen.add("hit " + ex.getRequestURI().getQuery());
            reply(ex, 200, "text/plain", "hit");
        });
        server.start();
        port = server.getAddress().getPort();
        svc = new HttpClientService();
    }

    @AfterEach
    void stop() {
        svc.shutdown();
        server.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String type, String body)
            throws java.io.IOException {
        ex.getRequestBody().readAllBytes();
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    /** Trusts {@code dir} (and what is under it) only, counting the questions. */
    private final AtomicInteger asked = new AtomicInteger();

    private NodeResponseScripts.Trust trust(boolean answer) {
        return new NodeResponseScripts.Trust() {
            @Override
            public SecretGate.Verdict allowRun(java.util.function.BooleanSupplier cancelled) {
                asked.incrementAndGet();
                return answer ? SecretGate.Verdict.ALLOWED : SecretGate.Verdict.REFUSED;
            }

            @Override
            public boolean isTrusted(Path file) {
                try {
                    return answer && file.startsWith(dir.toRealPath());
                } catch (java.io.IOException e) {
                    return false;
                }
            }
        };
    }

    private ResponseScripts scripts(boolean trusted, Duration timeout) {
        return new NodeResponseScripts(
                new NodeResponseScripts.Settings(true, "node", timeout, cache),
                trust(trusted),
                dir.resolve("api.http"),
                dir);
    }

    private List<HttpExchange> runAll(String text, ResponseScripts scripts, Map<String, String> env) throws Exception {
        AtomicReference<List<HttpExchange>> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        svc.runAll(
                HttpFile.parse(text).stream().map(HttpFile::parseRequest).toList(),
                RunEnvironment.of(env),
                dir,
                new CapturedResponses(),
                SecretGate.UNCHECKED,
                scripts,
                null,
                all -> {
                    got.set(all);
                    done.countDown();
                });
        assertTrue(done.await(60, TimeUnit.SECONDS), "the run should have completed");
        return got.get();
    }

    private HttpExchange one(String text, ResponseScripts scripts) throws Exception {
        return runAll(text, scripts, Map.of()).get(0);
    }

    // --- without Node: @assert and @capture --------------------------------------------------------------------

    @Test
    void captureAndAssertWorkWithoutNodeOrTrust() throws Exception {
        String text = "### login\n# @assert status == 200\n# @assert $.user.id == 8\n# @capture token = $.token\n"
                + "POST " + base() + "/login\n\n"
                + "### me\nGET " + base() + "/me\nAuthorization: Bearer {{token}}\n";
        List<HttpExchange> all = runAll(text, scripts(false, Duration.ofSeconds(10)), Map.of());
        assertEquals(List.of("login", "me auth=Bearer tok-123"), seen, "the next request sends the captured value");
        HttpTests t = all.get(0).result().tests();
        assertEquals(2, t.tests().size());
        assertTrue(t.tests().get(0).passed());
        assertFalse(t.tests().get(1).passed());
        assertEquals("expected == 8, got 7", t.tests().get(1).message());
        assertEquals(0, asked.get(), "checks run no code, so nothing is asked");
        assertTrue(all.get(1).result().tests().isEmpty());
    }

    // --- Node handlers --------------------------------------------------------------------------------------------

    @Test
    void aHandlerTestsTheResponseAndItsGlobalIsSentNext() throws Exception {
        assumeTrue(nodeAvailable(), "node is not installed");
        String text = "### login\nPOST " + base() + "/login\n\n> {%\n"
                + "  client.test('status is 200', () => client.assert(response.status === 200, 'status'));\n"
                + "  client.test('user is 8', () => client.assert(response.body.user.id === 8, 'id was ' + response.body.user.id));\n"
                + "  client.global.set('token', response.body.token);\n"
                + "  console.log('token stored', response.headers.valueOf('content-type'));\n"
                + "%}\n\n"
                + "### me\nGET " + base() + "/me\nAuthorization: Bearer {{token}}\n";
        List<HttpExchange> all = runAll(text, scripts(true, Duration.ofSeconds(20)), Map.of("token", "env-token"));
        assertEquals(List.of("login", "me auth=Bearer tok-123"), seen, "the session value beats the environment's");
        HttpTests t = all.get(0).result().tests();
        assertEquals(2, t.tests().size(), t.toString());
        assertTrue(t.tests().get(0).passed());
        assertEquals("id was 7", t.tests().get(1).message());
        assertEquals(List.of("token stored application/json"), t.log());
        assertNull(t.error());
        assertEquals(1, asked.get(), "asked once for the run");
        assertTrue(
                all.get(0).result().warnings().isEmpty(),
                all.get(0).result().warnings().toString());
    }

    @Test
    void anUntrustedFolderRunsNoHandlerAndSaysWhy() throws Exception {
        assumeTrue(nodeAvailable(), "node is not installed");
        String text = "GET " + base() + "/login\n\n> {% fetch('" + base() + "/hit?from=untrusted'); %}\n";
        HttpExchange ex = one(text, scripts(false, Duration.ofSeconds(20)));
        Thread.sleep(300);
        assertEquals(List.of("login"), seen, "the handler never ran");
        assertTrue(
                ex.result().warnings().stream().anyMatch(w -> w.contains("not trusted to run scripts")),
                ex.result().warnings().toString());
    }

    @Test
    void aTrustedHandlerMayUseTheNetwork() throws Exception {
        assumeTrue(nodeAvailable(), "node is not installed");
        String text = "GET " + base() + "/login\n\n> {%\n"
                + "  client.test('fetch', async () => {\n"
                + "    const r = await fetch('" + base() + "/hit?from=handler');\n"
                + "    client.assert(r.status === 200, 'status ' + r.status);\n"
                + "  });\n%}\n";
        HttpExchange ex = one(text, scripts(true, Duration.ofSeconds(20)));
        assertEquals(List.of("login", "hit from=handler"), seen);
        assertTrue(
                ex.result().tests().tests().get(0).passed(), ex.result().tests().toString());
    }

    @Test
    void aHandlerSeesAMinimalEnvironmentAndCannotWriteOrSpawn() throws Exception {
        assumeTrue(nodeAvailable(), "node is not installed");
        Path target = dir.resolve("written.txt");
        String text = "GET " + base() + "/login\n\n> {%\n"
                + "  client.log('env ' + Object.keys(process.env).sort().join(','));\n"
                + "  const req = process.mainModule.require;\n"
                + "  try { req('fs').writeFileSync(" + jsString(target.toString()) + ", 'x'); client.log('write ok'); }"
                + " catch (e) { client.log('write ' + e.code); }\n"
                + "  try { req('child_process').execSync('echo hi'); client.log('spawn ok'); }"
                + " catch (e) { client.log('spawn ' + e.code); }\n"
                + "%}\n";
        HttpExchange ex = one(text, scripts(true, Duration.ofSeconds(20)));
        List<String> log = ex.result().tests().log();
        assertEquals(3, log.size(), log.toString());
        Set<String> allowed = Set.of(
                "PATH",
                "Path",
                "LANG",
                "LC_ALL",
                "LC_CTYPE",
                "TZ",
                "SystemRoot",
                "SYSTEMROOT",
                "SystemDrive",
                "SYSTEMDRIVE",
                "windir",
                "WINDIR",
                "TEMP",
                "TMP",
                "PATHEXT",
                "ComSpec",
                "COMSPEC");
        String keys = log.get(0).substring("env ".length());
        for (String k : keys.split(",")) {
            assertTrue(k.isEmpty() || allowed.contains(k), "Node saw " + k + " (" + keys + ")");
        }
        int[] v = NodeResponseScripts.version(com.editora.process.ProcessRunner.resolveExecutable(List.of("node"))
                .get(0));
        if (HttpScripts.guardFor(v) != HttpScripts.Guard.NONE) {
            assertEquals("write ERR_ACCESS_DENIED", log.get(1));
            assertEquals("spawn ERR_ACCESS_DENIED", log.get(2));
            assertFalse(Files.exists(target));
        }
    }

    @Test
    void aHandlerThatNeverEndsIsStoppedAndTheRunGoesOn() throws Exception {
        assumeTrue(nodeAvailable(), "node is not installed");
        String text = "### spin\nGET " + base() + "/login\n\n> {% while (true) {} %}\n\n### next\nGET " + base()
                + "/hit?n=2\n";
        long start = System.nanoTime();
        List<HttpExchange> all = runAll(text, scripts(true, Duration.ofSeconds(2)), Map.of());
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(ms < 20_000, "stopped near the timeout, took " + ms + " ms");
        assertTrue(all.get(0).result().tests().error().contains("did not finish within 2 s"));
        assertEquals(List.of("login", "hit n=2"), seen);
    }

    @Test
    void anErrorNamesTheLineInTheHttpFile() throws Exception {
        assumeTrue(nodeAvailable(), "node is not installed");
        String text = "### one\nGET " + base() + "/login\n\n> {%\n  client.log('a');\n  missing();\n%}\n";
        HttpExchange ex = one(text, scripts(true, Duration.ofSeconds(20)));
        String error = ex.result().tests().error();
        assertTrue(error.startsWith("ReferenceError: missing is not defined"), error);
        // "missing();" is line 6 of the file (1-based).
        assertTrue(error.contains(dir.resolve("api.http").toAbsolutePath() + ":6:"), error);
        assertEquals(List.of("a"), ex.result().tests().log());
    }

    @Test
    void aHandlerFileRunsFromItsFolderAndOutsideFilesDoNot(@TempDir Path elsewhere) throws Exception {
        assumeTrue(nodeAvailable(), "node is not installed");
        Files.writeString(
                dir.resolve("check.js"), "client.test('from file', () => {\n  client.assert(false, 'nope');\n});\n");
        Path outside = elsewhere.resolve("evil.js");
        Files.writeString(outside, "client.global.set('x', 'y');\n");
        String text = "GET " + base() + "/login\n\n> ./check.js\n> " + outside + "\n";
        HttpExchange ex = one(text, scripts(true, Duration.ofSeconds(20)));
        HttpTests t = ex.result().tests();
        assertEquals(1, t.tests().size(), t.toString());
        assertEquals("nope", t.tests().get(0).message());
        assertTrue(
                ex.result().warnings().stream().anyMatch(w -> w.contains("evil.js") && w.contains("is not trusted")),
                ex.result().warnings().toString());
    }

    @Test
    void aSecretTheRequestReadReachesTheHandlerMasked() throws Exception {
        assumeTrue(nodeAvailable(), "node is not installed");
        String path = System.getenv("PATH");
        assumeTrue(path != null && path.length() >= SecretMask.MIN_LENGTH);
        String text = "GET " + base() + "/me\nAuthorization: {{$processEnv PATH}}\n\n> {%\n"
                + "  client.log(request.headers.findByName('Authorization').value());\n"
                + "  client.log(request.headers.findByName('Authorization').getRawValue());\n%}\n";
        HttpExchange ex = one(text, scripts(true, Duration.ofSeconds(20)));
        assertEquals(
                List.of(SecretMask.MASK, "{{$processEnv PATH}}"),
                ex.result().tests().log());
    }

    @Test
    void handlersOffSayWhyAndChecksStillRun() throws Exception {
        String text = "# @assert status == 200\nGET " + base() + "/login\n\n> {% client.global.set('a', 1) %}\n";
        ResponseScripts off = new NodeResponseScripts(
                new NodeResponseScripts.Settings(false, "node", null, cache), trust(true), dir.resolve("a.http"), dir);
        HttpExchange ex = one(text, off);
        assertEquals(1, ex.result().tests().tests().size());
        assertTrue(ex.result().warnings().stream().anyMatch(w -> w.contains("turned off")));
        assertEquals(0, asked.get());
    }

    @Test
    void aMissingNodeIsAWarningNotAFailure() throws Exception {
        String text = "GET " + base() + "/login\n\n> {% client.log(1) %}\n";
        ResponseScripts missing = new NodeResponseScripts(
                new NodeResponseScripts.Settings(
                        true, dir.resolve("no-such-node").toString(), null, cache),
                trust(true),
                dir.resolve("a.http"),
                dir);
        HttpExchange ex = one(text, missing);
        assertTrue(ex.result().ok());
        assertTrue(
                ex.result().warnings().stream().anyMatch(w -> w.contains("Node.js") && w.contains("was not found")),
                ex.result().warnings().toString());
    }

    private static String jsString(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
