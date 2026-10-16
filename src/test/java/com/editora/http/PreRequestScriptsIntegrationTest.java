package com.editora.http;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.editora.i18n.Messages;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pre-request scripts end to end, against a server on 127.0.0.1 and the {@code node} on the PATH (skipped without
 * one): what the server receives once a script sets {@code request.variables}, signs the body, makes a JWT or sets a
 * global header; that a script's value is sent as is and never reads a secret; that a request whose script cannot run
 * is not sent while the rest of a Run All is; and what the secrets prompt is told about a host a script chooses.
 */
@Tag("fx")
class PreRequestScriptsIntegrationTest {

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
        assumeTrue(nodeAvailable(), "node is not installed");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "test-http-server");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder(ex.getRequestURI().getPath());
            for (String h : List.of("Authorization", "X-Sig", "X-Trace", "X-Value")) {
                String v = ex.getRequestHeaders().getFirst(h);
                if (v != null) {
                    sb.append(' ').append(h).append('=').append(v);
                }
            }
            if (!body.isEmpty()) {
                sb.append(" body=").append(body);
            }
            seen.add(sb.toString());
            byte[] b = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();
        svc = new HttpClientService();
    }

    @AfterEach
    void stop() {
        if (svc != null) {
            svc.shutdown();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private ResponseScripts scripts(boolean trusted, Duration timeout) {
        return new NodeResponseScripts(
                new NodeResponseScripts.Settings(true, "node", timeout, cache),
                new NodeResponseScripts.Trust() {
                    @Override
                    public SecretGate.Verdict allowRun(java.util.function.BooleanSupplier cancelled) {
                        return trusted ? SecretGate.Verdict.ALLOWED : SecretGate.Verdict.REFUSED;
                    }

                    @Override
                    public boolean isTrusted(Path file) {
                        return trusted;
                    }
                },
                dir.resolve("api.http"),
                dir);
    }

    private List<HttpExchange> runAll(String text, ResponseScripts scripts, Map<String, String> env, SecretGate gate)
            throws Exception {
        AtomicReference<List<HttpExchange>> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        svc.runAll(
                HttpFile.parse(text).stream().map(HttpFile::parseRequest).toList(),
                RunEnvironment.of(env),
                dir,
                new CapturedResponses(),
                gate,
                scripts,
                null,
                all -> {
                    got.set(all);
                    done.countDown();
                });
        assertTrue(done.await(60, TimeUnit.SECONDS), "the run should have completed");
        return got.get();
    }

    private List<HttpExchange> runAll(String text, ResponseScripts scripts) throws Exception {
        return runAll(text, scripts, Map.of(), SecretGate.UNCHECKED);
    }

    @Test
    void aScriptsRequestVariableWinsAndIsSent() throws Exception {
        String text = "@token = from-file\n< {% request.variables.set('token', 'from-script'); client.log('set') %}\n"
                + "GET " + base() + "/me\nAuthorization: Bearer {{token}}\n";
        List<HttpExchange> all = runAll(text, scripts(true, Duration.ofSeconds(20)), Map.of("token", "env"), null);
        assertEquals(List.of("/me Authorization=Bearer from-script"), seen);
        assertEquals(List.of("set"), all.get(0).result().tests().log());
    }

    @Test
    void aScriptSignsTheBodyAndMakesAJwt() throws Exception {
        String text = "< {%\n"
                + "  const body = request.body.tryGetSubstituted();\n"
                + "  request.variables.set('sig', crypto.hmac.sha256().withTextSecret('k3y').updateWithText(body)"
                + ".digest().toHex());\n"
                + "  request.variables.set('jwt', jwt.sign({ sub: 'ada' }, 'k3y', { algorithm: 'HS256' }));\n"
                + "%}\nPOST " + base() + "/signed\nX-Sig: {{sig}}\nAuthorization: Bearer {{jwt}}\n\n{\"n\":{{n}}}\n";
        runAll(text, scripts(true, Duration.ofSeconds(20)), Map.of("n", "7"), null);
        assertEquals(1, seen.size(), seen.toString());
        String line = seen.get(0);
        assertTrue(line.endsWith("body={\"n\":7}"), line);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("k3y".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal("{\"n\":7}".getBytes(StandardCharsets.UTF_8)));
        assertTrue(line.contains("X-Sig=" + expected), line + " / " + expected);
        String jwt = line.substring(line.indexOf("Bearer ") + 7, line.indexOf(' ', line.indexOf("Bearer ") + 7));
        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length, jwt);
        mac.init(new SecretKeySpec("k3y".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8)));
        assertEquals(signature, parts[2], "Java verifies the script's HS256 JWT");
        assertTrue(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .contains("\"sub\":\"ada\""));
    }

    @Test
    void aGlobalHeaderReachesLaterRequestsButARequestsOwnHeaderWins() throws Exception {
        String text = "### one\n< {% client.global.headers.set('X-Trace', 't1') %}\nGET " + base() + "/one\n\n"
                + "### two\nGET " + base() + "/two\n\n"
                + "### three\nGET " + base() + "/three\nX-Trace: own\n\n"
                + "> {% client.global.headers.clear('X-Trace') %}\n\n"
                + "### four\nGET " + base() + "/four\n";
        runAll(text, scripts(true, Duration.ofSeconds(20)));
        assertEquals(List.of("/one X-Trace=t1", "/two X-Trace=t1", "/three X-Trace=own", "/four"), seen);
    }

    @Test
    void aScriptsValueIsSentAsIsAndReadsNoSecret() throws Exception {
        int[] asked = new int[1];
        SecretGate counting = (reads, hosts, cancelled) -> {
            asked[0]++;
            return SecretGate.Verdict.ALLOWED;
        };
        String text =
                "< {% request.variables.set('v', '{{$processEnv PATH}}') %}\nGET " + base() + "/v\nX-Value: {{v}}\n";
        runAll(text, scripts(true, Duration.ofSeconds(20)), Map.of(), counting);
        assertEquals(List.of("/v X-Value={{$processEnv PATH}}"), seen, "the text, not the environment variable");
        assertEquals(0, asked[0], "nothing secret was read, so nothing was asked");
    }

    @Test
    void aRequestWhoseScriptCannotRunIsNotSentAndTheRunGoesOn() throws Exception {
        String text = "### guarded\n< {% request.variables.set('a', 1) %}\nGET " + base() + "/guarded\n\n"
                + "### plain\nGET " + base() + "/plain\n";
        List<HttpExchange> all = runAll(text, scripts(false, Duration.ofSeconds(20)));
        assertEquals(List.of("/plain"), seen, "only the request without a script went out");
        String error = all.get(0).result().error();
        assertTrue(error.startsWith("Not sent: the pre-request script did not run — its folder is not trusted"), error);
        assertTrue(all.get(1).result().ok());
    }

    @Test
    void anErrorOrATimeoutKeepsTheRequestUnsentAndExitDoesNot() throws Exception {
        String text = "### broken\n< {%\n  client.log('before');\n  missing();\n%}\nGET " + base() + "/broken\n\n"
                + "### slow\n< {% while (true) {} %}\nGET " + base() + "/slow\n\n"
                + "### exits\n< {% request.variables.set('x', 'kept'); client.exit(); request.variables.set('x', 'no') %}\n"
                + "GET " + base() + "/exits\nX-Value: {{x}}\n";
        List<HttpExchange> all = runAll(text, scripts(true, Duration.ofSeconds(2)));
        assertEquals(List.of("/exits X-Value=kept"), seen);
        HttpResult broken = all.get(0).result();
        assertTrue(broken.error().contains("it stopped with an error"), broken.error());
        assertEquals(List.of("before"), broken.tests().log());
        assertTrue(
                broken.tests().error().startsWith("ReferenceError: missing is not defined"),
                broken.tests().error());
        assertTrue(broken.tests().error().contains(dir.resolve("api.http").toAbsolutePath() + ":4:"), "line 4");
        assertTrue(
                all.get(1).result().error().contains("did not finish within 2 s"),
                all.get(1).result().error());
    }

    @Test
    void aPreRequestTestComesBeforeTheHandlersOnes() throws Exception {
        String text =
                "< {% client.test('pre', () => {}) %}\nGET " + base() + "/t\n\n> {% client.test('post', () => {}) %}\n";
        HttpExchange ex = runAll(text, scripts(true, Duration.ofSeconds(20))).get(0);
        assertEquals(
                List.of("pre", "post"),
                ex.result().tests().tests().stream().map(HttpTests.Test::name).toList());
    }

    @Test
    void aHostAScriptChoosesIsAskedAboutAsSuchAndCheckedAtTheSend() throws Exception {
        List<List<String>> hostsAsked = new CopyOnWriteArrayList<>();
        SecretGate allowing = (reads, hosts, cancelled) -> {
            hostsAsked.add(hosts);
            return SecretGate.Verdict.ALLOWED;
        };
        String text = "< {% request.variables.set('target', '" + base() + "') %}\nGET {{target}}/s\n"
                + "X-Value: {{$processEnv PATH}}\n";
        List<HttpExchange> all = runAll(text, scripts(true, Duration.ofSeconds(20)), Map.of(), allowing);
        assertEquals(1, hostsAsked.size());
        assertTrue(
                hostsAsked.get(0).contains(Messages.tr("dialog.httpTrust.dynamicHost")),
                hostsAsked.get(0).toString());
        assertTrue(all.get(0).result().ok(), String.valueOf(all.get(0).result().error()));
        assertEquals(1, seen.size());
        assertTrue(seen.get(0).startsWith("/s X-Value="), seen.toString());
    }
}
