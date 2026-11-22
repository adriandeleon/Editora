package com.editora.http;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code editora http}: its options, and whole runs against a local server (checks only, no Node.js needed). */
class HttpCliTest {

    @TempDir
    Path dir;

    private HttpServer server;
    private String base;
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/user", ex -> {
            received.add(ex.getRequestURI() + " token=" + ex.getRequestHeaders().getFirst("X-Token"));
            byte[] body = "{\"id\":7,\"name\":\"Ada\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/orders", ex -> {
            received.add(ex.getRequestURI().toString());
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private int run(String... args) {
        return HttpCli.run(
                List.of(args),
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    private Path write(String name, String text) throws Exception {
        Path p = dir.resolve(name);
        Files.writeString(p, text);
        return p;
    }

    @Test
    void parsesTheIjhttpStyleOptions() {
        HttpCli.Options o = HttpCli.parse(List.of(
                "--env",
                "dev",
                "-V",
                "a=1",
                "--env-variables=b=x=y",
                "-P",
                "secret=hunter22",
                "-L",
                "verbose",
                "--report=out",
                "--no-scripts",
                "--node",
                "/opt/node",
                "one.http",
                "--",
                "--two.http"));
        assertEquals("dev", o.env());
        assertEquals("1", o.variables().get("a"));
        assertEquals("x=y", o.variables().get("b"), "only the first = separates the name");
        assertEquals("hunter22", o.variables().get("secret"));
        assertEquals(List.of("hunter22"), o.privateValues());
        assertEquals(HttpCli.LogLevel.VERBOSE, o.logLevel());
        assertEquals(Path.of("out"), o.reportDir());
        assertFalse(o.scripts());
        assertEquals("/opt/node", o.node());
        assertEquals(List.of(Path.of("one.http"), Path.of("--two.http")), o.files());
    }

    @Test
    void aBareReportWritesToReports() {
        assertEquals(Path.of("reports"), HttpCli.parse(List.of("-r", "a.http")).reportDir());
        assertEquals(
                List.of(Path.of("a.http")),
                HttpCli.parse(List.of("--report", "a.http")).files(),
                "--report takes its folder only after =, so the next argument stays a file");
    }

    @Test
    void refusesWhatItDoesNotKnow() {
        assertThrows(IllegalArgumentException.class, () -> HttpCli.parse(List.of("--bogus", "a.http")));
        assertThrows(IllegalArgumentException.class, () -> HttpCli.parse(List.of("--env")));
        assertThrows(IllegalArgumentException.class, () -> HttpCli.parse(List.of("-V", "novalue", "a.http")));
        assertThrows(IllegalArgumentException.class, () -> HttpCli.parse(List.of("-L", "loud", "a.http")));
        assertThrows(IllegalArgumentException.class, () -> HttpCli.parse(List.of("--no-scripts=yes", "a.http")));
        assertThrows(IllegalArgumentException.class, () -> HttpCli.parse(List.of()));
        assertTrue(HttpCli.parse(List.of("--help")).help(), "--help needs no file");
    }

    @Test
    void usageErrorsExitWithTwo() throws Exception {
        assertEquals(HttpCli.USAGE, run("--bogus", "a.http"));
        assertTrue(err().contains("unknown option: --bogus"), err());
        assertEquals(HttpCli.USAGE, run(dir.resolve("missing.http").toString()));
        assertTrue(err().contains("no such file"), err());
        Path file = write("a.http", "GET " + base + "/user\n");
        assertEquals(HttpCli.USAGE, run("--env", "nope", file.toString()));
        assertTrue(err().contains("environment 'nope' is not defined"), err());
        assertTrue(received.isEmpty(), "nothing is sent when the command line is wrong");
    }

    @Test
    void runsAFileWithItsEnvironmentChecksAndCaptures() throws Exception {
        write(HttpEnv.PUBLIC_FILE, "{\"local\": {\"base\": \"" + base + "\"}}");
        Path file = write("api.http", """
                ### Get the user
                # @assert status == 200
                # @assert $.name == "Ada"
                # @capture uid = $.id
                GET {{base}}/user
                X-Token: {{token}}

                ### Their orders
                # @assert status == 200
                GET {{base}}/orders/{{uid}}
                """);
        Path reports = dir.resolve("reports");
        int status = run(
                "--env",
                "local",
                "--no-scripts",
                "-P",
                "token=s3cr3t-token",
                "-L",
                "HEADERS",
                "--report=" + reports,
                file.toString());

        assertEquals(HttpCli.FAILED, status, out() + err());
        assertEquals(List.of("/user token=s3cr3t-token", "/orders/7"), received, "the capture feeds the next request");
        String out = out();
        assertTrue(out.contains("### Get the user"), out);
        assertTrue(out.contains("<- 200"), out);
        assertTrue(out.contains("PASS $.name == \"Ada\""), out);
        assertTrue(out.contains("FAIL status == 200"), out);
        assertTrue(out.contains("> X-Token: " + SecretMask.MASK), "a -P value is masked: " + out);
        assertFalse(out.contains("s3cr3t-token"), out);
        assertTrue(out.contains("2 requests, 0 failed; 3 tests, 1 failed"), out);

        String xml = Files.readString(reports.resolve("report.xml"));
        assertTrue(xml.contains("tests=\"3\" failures=\"1\" errors=\"0\""), xml);
        assertTrue(xml.contains("<testcase name=\"Get the user: status == 200\""), xml);
        assertTrue(xml.contains("<failure"), xml);
    }

    @Test
    void allPassingExitsWithZero() throws Exception {
        Path file = write("ok.http", "# @assert status == 200\nGET " + base + "/user\n");
        assertEquals(HttpCli.OK, run("--no-scripts", file.toString()), out());
        assertTrue(out().contains("1 request, 0 failed; 1 test, 0 failed"), out());
    }

    @Test
    void aRequestThatGetsNoResponseFails() throws Exception {
        Path file = write("down.http", "GET http://127.0.0.1:1/nothing\n");
        assertEquals(HttpCli.FAILED, run("--no-scripts", "--report=" + dir.resolve("r"), file.toString()));
        assertTrue(out().contains("FAILED: "), out());
        assertTrue(Files.readString(dir.resolve("r/report.xml")).contains("<error"));
    }

    @Test
    void xmlEscapesEverythingItWrites() {
        assertEquals("a&amp;b&lt;c&gt;&quot;&apos;?", HttpCli.Report.xml("a&b<c>\"'\u0001"));
    }
}
