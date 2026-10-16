package com.editora.http;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What reaches a 127.0.0.1 server for the third review's format fixes: env files edited by hand, {@code {{variables}}}
 * in a body-file path, an infinite random range, env numbers, Day.js weeks, and requests written by Import cURL from
 * browser and shell copies.
 */
@Tag("fx")
class HttpFormatRound3IntegrationTest {

    private HttpServer server;
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
            String type = ex.getRequestHeaders().getFirst("Content-Type");
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath()
                    + (ex.getRequestURI().getRawQuery() == null
                            ? ""
                            : "?" + ex.getRequestURI().getRawQuery())
                    + (auth == null ? "" : " auth=" + auth)
                    + (type == null ? "" : " type=" + type.replaceAll("boundary=.*", "boundary=…"))
                    + (body.isEmpty() ? "" : " body=" + body.replace("\r\n", "⏎")));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        svc = new HttpClientService();
    }

    @AfterEach
    void stop() {
        svc.shutdown();
        server.stop(0);
    }

    private String host() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private HttpExchange run(String fileText, Map<String, String> env, Path dir) throws Exception {
        return send(HttpFile.parseRequest(HttpFile.parse(fileText).get(0)), env, dir);
    }

    private HttpExchange send(HttpFile.Parsed req, Map<String, String> env, Path dir) throws Exception {
        AtomicReference<HttpExchange> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        svc.run(req, env, dir, null, HttpClientService.SecretGate.UNCHECKED, ex -> {
            got.set(ex);
            done.countDown();
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        return got.get();
    }

    // --- P1: env files as people edit them -------------------------------------------------------------------

    @Test
    void aPrivateEnvFileWithAByteOrderMarkStillSendsItsToken() throws Exception {
        String pub = "{\"dev\": {\"host\": \"" + host() + "\"}}";
        String priv = "\uFEFF{\"dev\": {\"token\": \"FAKE-TOKEN\"}}";
        HttpExchange ex =
                run("GET {{host}}/me\nAuthorization: Bearer {{token}}\n", HttpEnv.merged(pub, priv, "dev"), null);
        assertEquals(
                List.of("GET /me auth=Bearer FAKE-TOKEN"),
                seen,
                ex.result().warnings().toString());
    }

    @Test
    void commentsAndTrailingCommasInAnEnvFileAreAccepted() throws Exception {
        String pub = "{\n  // the local server\n  \"dev\": {\"host\": \"" + host()
                + "\", /* see README */ \"v\": \"2\",},\n}";
        run("GET {{host}}/v{{v}}\n", HttpEnv.merged(pub, null, "dev"), null);
        assertEquals(List.of("GET /v2"), seen);
        assertNull(HttpEnv.problem(pub));
    }

    @Test
    void aBrokenEnvFileIsNamedWithWhereItBroke() {
        String problem = HttpEnv.problem("{\"dev\": {\"host\": \"h\" \"x\": 1}}");
        assertNotNull(problem);
        assertTrue(problem.startsWith("line 1, column "), problem);
    }

    // --- P4: variables in a body-file path ----------------------------------------------------------------

    @Test
    void aBodyFilePathMayUseVariables(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("body-a.json"), "{\"from\":\"a\"}");
        Files.writeString(dir.resolve("tpl-a.json"), "{\"v\":\"{{v}}\"}");
        run("@v = a\nPOST " + host() + "/raw\nContent-Type: application/json\n\n< ./body-{{v}}.json\n", Map.of(), dir);
        run("@v = a\nPOST " + host() + "/sub\nContent-Type: application/json\n\n<@ ./tpl-{{v}}.json\n", Map.of(), dir);
        assertEquals(
                List.of(
                        "POST /raw type=application/json body={\"from\":\"a\"}",
                        "POST /sub type=application/json body={\"v\":\"a\"}"),
                seen);
    }

    @Test
    void anEscapedBraceInABodyFilePathIsALiteralBrace(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("lit-{{x}}.json"), "{\"literal\":true}");
        run("POST " + host() + "/lit\nContent-Type: application/json\n\n< ./lit-\\{{x}}.json\n", Map.of(), dir);
        assertEquals(List.of("POST /lit type=application/json body={\"literal\":true}"), seen);
    }

    @Test
    void aBodyFilePathThatResolvesOutsideTheFolderIsRefused(@TempDir Path dir) throws Exception {
        HttpExchange ex = run("@up = ..\nPOST " + host() + "/x\n\n< ./{{up}}/secret.txt\n", Map.of(), dir);
        assertEquals(List.of(), seen);
        assertTrue(
                ex.result().error().contains("../secret.txt")
                        || ex.result().error().contains("outside"),
                ex.result().error());
    }

    // --- P8: an infinite random range ---------------------------------------------------------------------

    @Test
    void anInfiniteRandomRangeIsSentEmptyWithAReasonNotAnInternalError() throws Exception {
        HttpExchange ex = run("GET " + host() + "/f?x={{$random.float(0, Infinity)}}\n", Map.of(), null);
        assertEquals(List.of("GET /f?x="), seen);
        assertTrue(
                ex.result().warnings().stream().anyMatch(w -> w.contains("not a finite number")),
                ex.result().warnings().toString());
    }

    // --- P9: env numbers as written -----------------------------------------------------------------------

    @Test
    void envNumbersAreSentAsWritten() throws Exception {
        String pub =
                "{\"dev\": {\"tiny\": 0.0001, \"big\": 1e21, \"price\": 100.50, \"n\": 7, \"obj\": {\"r\": 0.0001}}}";
        run(
                "GET " + host() + "/n?a={{tiny}}&b={{big}}&c={{price}}&d={{n}}&e={{obj}}\n",
                HttpEnv.merged(pub, null, "dev"),
                null);
        assertEquals(List.of("GET /n?a=0.0001&b=1000000000000000000000&c=100.50&d=7&e=%7B%22r%22:0.0001%7D"), seen);
    }

    // --- Z4: Day.js week tokens and unknown letters -------------------------------------------------------

    @Test
    void dayjsWeekTokensAndUnknownLetters() {
        ZonedDateTime newYearsEve = ZonedDateTime.of(2026, 12, 31, 12, 0, 0, 0, ZoneOffset.UTC); // a Thursday
        assertEquals(
                "2026-W53 2027-w01 2027 2026",
                DynamicVars.value("$datetime \"GGGG-[W]WW gggg-[w]ww gggg GGGG\"", newYearsEve, null));
        assertEquals("52", DynamicVars.value("$datetime \"w\"", newYearsEve.minusDays(5), null), "Sat 26 Dec");
        String[] why = new String[1];
        assertNull(DynamicVars.resolve("$datetime \"YYYY-MM-DD at HH:mm\"", newYearsEve, null, why));
        assertTrue(why[0].contains("t"), why[0]);
        assertEquals("2026-12-31T12:00", DynamicVars.value("$datetime \"YYYY-MM-DDTHH:mm\"", newYearsEve, null));
    }

    @Test
    void anUnknownDayjsLetterIsReportedNotSentAsAGuess() throws Exception {
        HttpExchange ex = run("GET " + host() + "/d?w={{$datetime \"YYYY-[W]WW kk\"}}\n", Map.of(), null);
        assertEquals(List.of("GET /d?w="), seen);
        assertTrue(
                ex.result().warnings().stream().anyMatch(w -> w.contains("Day.js") && w.contains("k")),
                ex.result().warnings().toString());
    }

    // --- P3, P10, P11: what Import cURL writes, sent ----------------------------------------------------

    private void sendImported(String curl, Path dir) throws Exception {
        String request = CurlImport.toHttpRequest(curl);
        assertNotNull(request, curl);
        send(HttpFile.parseRequest(HttpFile.parse(request).get(0)), Map.of(), dir);
    }

    @Test
    void windowsAndPromptedCurlCommandsKeepTheirUrl() throws Exception {
        sendImported("curl.exe \"" + host() + "/win\" -H \"Authorization: Bearer w\"", null);
        sendImported("$ curl " + host() + "/prompt", null);
        sendImported("/usr/bin/curl -X DELETE " + host() + "/path/1", null);
        sendImported("curl.exe '" + host() + "/ps' `\n  -H 'Authorization: Bearer p' `\n  -X PUT", null);
        assertEquals(List.of("GET /win auth=Bearer w", "GET /prompt", "DELETE /path/1", "PUT /ps auth=Bearer p"), seen);
        assertNull(CurlImport.toHttpRequest("Invoke-WebRequest -Uri http://127.0.0.1:9/x"), "not a curl command");
        assertNull(CurlImport.toHttpRequest("curl -s -L"), "no URL");
    }

    @Test
    void aChromeCmdCopyKeepsAMultiLineBodyAsItWas() throws Exception {
        sendImported(
                "curl ^\"" + host() + "/t^\" ^\n  -H ^\"content-type: text/plain^\" ^\n"
                        + "  --data-raw ^\"line1^\n\nline2 \\^\"q\\^\" end^\" ^\n  --compressed",
                null);
        assertEquals(List.of("POST /t type=text/plain body=line1\nline2 \"q\" end"), seen);
    }

    @Test
    void urlQueryAndARemovedContentTypeAreKept() throws Exception {
        sendImported("curl --url-query 'a=1' --url-query 'b=x y' --url-query '+c=%2F' " + host() + "/q", null);
        sendImported("curl -X POST " + host() + "/plain -H 'Content-Type:' --data-raw 'plain text'", null);
        assertEquals(List.of("GET /q?a=1&b=x%20y&c=%2F", "POST /plain body=plain text"), seen);
    }

    @Test
    void inlineFormPartsKeepTheirTypeAndQuotedFileNamesTheirSemicolons(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("part.txt"), "file part");
        sendImported(
                "curl " + host() + "/up -F 'meta={\"k\":\"v\"};type=application/json'"
                        + " -F 'quoted=\"a;b\";type=text/plain'"
                        + " -F 'f=@\"part.txt\";filename=\"we;ird \\\"n\\\".txt\"'",
                dir);
        String got = seen.get(0);
        assertTrue(got.contains("name=\"meta\"⏎Content-Type: application/json⏎⏎{\"k\":\"v\"}⏎"), got);
        assertTrue(got.contains("name=\"quoted\"⏎Content-Type: text/plain⏎⏎a;b⏎"), got);
        assertTrue(got.contains("name=\"f\"; filename=\"we;ird \\\"n\\\".txt\"⏎"), got);
        assertTrue(got.contains("⏎⏎file part⏎"), got);
    }

    @Test
    void priorKnowledgeIsKeptOnTheRequestLine() {
        String request = CurlImport.toHttpRequest("curl --http2-prior-knowledge " + host() + "/x");
        assertEquals(
                "HTTP/2 (Prior Knowledge)",
                HttpFile.parseRequest(HttpFile.parse(request).get(0)).httpVersion());
    }
}
