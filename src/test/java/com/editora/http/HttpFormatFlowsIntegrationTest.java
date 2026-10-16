package com.editora.http;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What actually goes over the wire for the format/variable fixes: a server on 127.0.0.1 records each request
 * line, the headers that matter, and the body.
 */
@Tag("fx")
class HttpFormatFlowsIntegrationTest {

    private HttpServer server;
    private int port;
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private HttpClientService svc;

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

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath()
                    + (ex.getRequestURI().getRawQuery() == null
                            ? ""
                            : "?" + ex.getRequestURI().getRawQuery())
                    + (auth == null ? "" : " auth=" + auth)
                    + (body.isEmpty() ? "" : " body=" + body.replace("\r\n", "⏎")));
            byte[] out = "{\"token\":\"T123\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
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

    private String host() {
        return "http://127.0.0.1:" + port;
    }

    private HttpExchange run(String fileText, int index, Map<String, String> env, Path dir, CapturedResponses s)
            throws Exception {
        HttpFile.Parsed req = HttpFile.parseRequest(HttpFile.parse(fileText).get(index));
        AtomicReference<HttpExchange> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        svc.run(req, env, dir, s, SecretGate.UNCHECKED, ex -> {
            got.set(ex);
            done.countDown();
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        return got.get();
    }

    private List<HttpExchange> runAll(String fileText, Map<String, String> env, Path dir) throws Exception {
        List<HttpFile.Parsed> reqs =
                HttpFile.parse(fileText).stream().map(HttpFile::parseRequest).toList();
        AtomicReference<List<HttpExchange>> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        svc.runAll(reqs, env, dir, new CapturedResponses(), SecretGate.UNCHECKED, null, all -> {
            got.set(all);
            done.countDown();
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        return got.get();
    }

    @Test
    void aChainedRequestRunOnItsOwnUsesTheResponseOfAnEarlierRun() throws Exception {
        String file = "### Login\n# @name login\nPOST " + host() + "/login\n\n" + "### Me\nGET " + host()
                + "/me\nAuthorization: Bearer {{login.response.body.$.token}}\n";
        CapturedResponses session = new CapturedResponses();
        HttpExchange alone = run(file, 1, Map.of(), null, session);
        assertTrue(
                alone.result().warnings().stream().anyMatch(w -> w.contains("login.response") && w.contains("not run")),
                alone.result().warnings().toString());
        run(file, 0, Map.of(), null, session);
        run(file, 1, Map.of(), null, session);
        assertEquals(List.of("GET /me auth=Bearer", "POST /login", "GET /me auth=Bearer T123"), seen);
    }

    @Test
    void unresolvedVariablesAreSentEmptyAndListed() throws Exception {
        HttpExchange ex = run(
                "GET " + host() + "/x?a={{missing}}\nAuthorization: Bearer {{token}}\n",
                0,
                Map.of(),
                null,
                new CapturedResponses());
        assertEquals(List.of("GET /x?a= auth=Bearer"), seen);
        List<String> w = ex.result().warnings();
        assertTrue(w.stream().anyMatch(s -> s.startsWith("{{missing}}")), w.toString());
        assertTrue(w.stream().anyMatch(s -> s.startsWith("{{token}}")), w.toString());
    }

    @Test
    void timestampsAreTheRealEpochInANonUtcZone() throws Exception {
        TimeZone saved = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Monterrey"));
            long before = System.currentTimeMillis() / 1000;
            run("GET " + host() + "/t?ts={{$timestamp}}\n", 0, Map.of(), null, new CapturedResponses());
            long sent = Long.parseLong(seen.get(0).substring("GET /t?ts=".length()));
            assertTrue(Math.abs(sent - before) < 10, "sent " + sent + ", now " + before);
        } finally {
            TimeZone.setDefault(saved);
        }
    }

    @Test
    void aFileVariableReadsTheDotenvBesideTheFile(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(".env"), "TOKEN=abc123\n");
        run(
                "@token = {{$dotenv TOKEN}}\nGET " + host() + "/d\nAuthorization: Bearer {{token}}\n",
                0,
                Map.of(),
                dir,
                new CapturedResponses());
        assertEquals(List.of("GET /d auth=Bearer abc123"), seen);
    }

    @Test
    void eachRequestSeesTheDeclarationsAboveIt() throws Exception {
        runAll(
                "@id = 1\n### one\nGET " + host() + "/i/{{id}}\n\n### two\n@id = 2\nGET " + host() + "/i/{{id}}\n",
                Map.of(),
                null);
        assertEquals(List.of("GET /i/1", "GET /i/2"), seen);
    }

    @Test
    void environmentValuesThatUseOtherValuesResolve() throws Exception {
        run("GET {{api}}/e\n", 0, Map.of("base", host(), "api", "{{base}}/v1"), null, new CapturedResponses());
        assertEquals(List.of("GET /v1/e"), seen);
    }

    @Test
    void bodyCommentsAreNotSent() throws Exception {
        run(
                "POST " + host() + "/b\nContent-Type: application/json\n\n{\n  # note\n  \"a\": 1\n}\n",
                0,
                Map.of(),
                null,
                new CapturedResponses());
        assertEquals("POST /b body={\n  \"a\": 1\n}", seen.get(0));
    }

    @Test
    void emojiLowercaseMethodsAndSchemelessTargets() throws Exception {
        run("get " + host() + "/q?e=😀\n", 0, Map.of(), null, new CapturedResponses());
        run("127.0.0.1:" + port + "/noscheme\n", 0, Map.of(), null, new CapturedResponses());
        run("GET /origin\nHost: 127.0.0.1:" + port + "\n", 0, Map.of(), null, new CapturedResponses());
        assertEquals(List.of("GET /q?e=%F0%9F%98%80", "GET /noscheme", "GET /origin"), seen);
    }

    @Test
    void graphqlIsNotSent() throws Exception {
        HttpExchange ex =
                run("GRAPHQL " + host() + "/graphql\n\nquery { a }\n", 0, Map.of(), null, new CapturedResponses());
        assertTrue(ex.result().failed());
        assertTrue(ex.result().error().contains("GRAPHQL"), ex.result().error());
        assertTrue(seen.isEmpty());
    }

    @Test
    void multipartPartHeadersTakeVariables(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("up.txt"), "UP");
        run(
                "POST " + host() + "/m\nContent-Type: multipart/form-data; boundary=B\n\n--B\n"
                        + "Content-Disposition: form-data; name=\"f\"; filename=\"{{fname}}\"\n\n< ./up.txt\n--B\n"
                        + "Content-Disposition: form-data; name=\"note\"\n\nhello\n\n--B--\n",
                0,
                Map.of("fname", "up.txt"),
                dir,
                new CapturedResponses());
        String body = seen.get(0);
        assertTrue(body.contains("filename=\"up.txt\""), body);
        assertTrue(body.contains("⏎⏎hello⏎--B--"), body);
    }

    @Test
    void aRedirectPathTakesVariables(@TempDir Path dir) throws Exception {
        HttpExchange ex = run(
                "GET " + host() + "/r\n\n>> ./out-{{id}}.json\n", 0, Map.of("id", "7"), dir, new CapturedResponses());
        assertFalse(ex.result().failed());
        assertTrue(
                Files.exists(dir.resolve("out-7.json")), ex.result().warnings().toString());
    }

    @Test
    void aTimeoutWithAUnitIsHonoured() throws Exception {
        HttpFile.Parsed p = HttpFile.parseRequest(
                HttpFile.parse("# @timeout 2 m\n# @connection-timeout 1500 ms\nGET " + host() + "/x\n")
                        .get(0));
        assertEquals(120_000, p.directives().timeoutMillis());
        assertEquals(1_500, p.directives().connectionTimeoutMillis());
    }

    // --- round-2 fixes (F5, F6, F10, F11): what the server receives -----------------------------------------

    @Test
    void fullMonthAndWeekdayNamesReachTheServer() throws Exception {
        java.time.format.DateTimeFormatter english =
                java.time.format.DateTimeFormatter.ofPattern("MMMM EEEE", java.util.Locale.ENGLISH);
        String before = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).format(english);
        run(
                "POST " + host() + "/d\nContent-Type: text/plain\n\n{{$datetime 'MMMM dddd'}}\n",
                0,
                Map.of(),
                null,
                new CapturedResponses());
        String after = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).format(english);
        String got = seen.get(0);
        assertTrue(got.endsWith(" body=" + before) || got.endsWith(" body=" + after), got);
    }

    @Test
    void anIndentedBodyKeepsItsFirstLineIndentation() throws Exception {
        run(
                "POST " + host() + "/y\nContent-Type: application/yaml\n\n  a: 1\n  b:\n    - x\n\n\n",
                0,
                Map.of(),
                null,
                new CapturedResponses());
        assertEquals("POST /y body=  a: 1\n  b:\n    - x", seen.get(0));
    }

    @Test
    void anEnvironmentValueThatUsesAFileVariableSaysSo() throws Exception {
        HttpExchange ex = run(
                "@base = " + host() + "\nGET {{base}}/e?u={{url}}\n",
                0,
                Map.of("url", "{{base}}/x"),
                null,
                new CapturedResponses());
        assertEquals("GET /e?u=/x", seen.get(0));
        assertTrue(
                ex.result().warnings().stream().anyMatch(w -> w.contains("{{base}}") && w.contains("@variables")),
                ex.result().warnings().toString());
    }

    @Test
    void copyAsCurlOfAMultipartUploadReimportsAndSendsTheSameFile(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("body.json"), "{\"from\":\"file\"}");
        HttpExchange first = run(
                "POST " + host() + "/up\nContent-Type: multipart/form-data; boundary=B\n\n--B\n"
                        + "Content-Disposition: form-data; name=\"f\"; filename=\"b.json\"\n\n< ./body.json\n--B--\n",
                0,
                Map.of(),
                dir,
                new CapturedResponses());
        String imported = CurlImport.toHttpRequest(CurlExport.toCurl(first));
        run(imported, 0, Map.of(), dir, new CapturedResponses());
        assertEquals(2, seen.size(), seen.toString());
        for (String got : seen) {
            assertTrue(got.contains("filename=\"b.json\"") && got.contains("{\"from\":\"file\"}"), got);
        }
    }

    @Test
    void anImportedUploadFileIsPut(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("data.bin"), "DATA");
        run(
                CurlImport.toHttpRequest("curl -T ./data.bin " + host() + "/put"),
                0,
                Map.of(),
                dir,
                new CapturedResponses());
        assertEquals("PUT /put body=DATA", seen.get(0));
    }
}
