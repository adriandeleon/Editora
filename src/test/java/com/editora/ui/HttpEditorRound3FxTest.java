package com.editora.ui;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.completion.Completion;
import com.editora.editor.EditorBuffer;
import com.editora.markdown.MarkdownLint;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The third review's editor-side format fixes, in a real window: what the {@code {{variable}}} marks say with no
 * environment and with an unreadable env file, what a run reports then, the marks after a Save As to Markdown and
 * after an env file changed while the window was away, Import cURL's status, and the {@code {{} popup.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpEditorRound3FxTest {

    private FxWindowFixture fx;
    private HttpServer server;
    private final List<String> seen = new CopyOnWriteArrayList<>();

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI() + (auth == null ? "" : " auth=" + auth));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (server != null) {
            server.stop(0);
        }
        if (fx != null) {
            fx.dispose();
        }
    }

    private String host() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private MainController c() {
        return fx.controller;
    }

    private EditorBuffer open(Path file) throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                FxTestSupport.field(c(), "fileWorkflows"), "openPath", new Class<?>[] {Path.class}, file));
        FxTestSupport.drainFx();
        return FxTestSupport.callOnFx(c()::activeBuffer);
    }

    private static List<String> marks(EditorBuffer b) {
        return b.httpVariableDiagnostics().stream()
                .map(d -> d.code() + ": " + d.message())
                .toList();
    }

    private static void await(String what, BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!FxTestSupport.callOnFx(done::getAsBoolean)) {
            assertTrue(System.nanoTime() < deadline, what);
            Thread.sleep(25);
        }
    }

    private HttpClientPanel panel(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> c().httpClient.panelForTest(b));
    }

    private void select(EditorBuffer b, String env) throws Exception {
        FxTestSupport.runOnFx(() -> c().httpClient.chooseEnvironment(b, env));
        FxTestSupport.drainFx();
    }

    private static Path project(String envJson) throws Exception {
        Path dir = Files.createTempDirectory("editora-http-r3-");
        Files.createDirectories(dir.resolve(".git"));
        if (envJson != null) {
            Files.writeString(dir.resolve("http-client.env.json"), envJson);
        }
        return dir;
    }

    private String status() throws Exception {
        return FxTestSupport.callOnFx(
                () -> ((Label) FxTestSupport.field(FxTestSupport.field(c(), "statusBar"), "echo")).getText());
    }

    @Test
    void withNoEnvironmentSelectedTheMarkNamesTheEnvironmentsThatDefineIt() throws Exception {
        Path dir = project("{\"dev\": {\"host\": \"h\"}, \"prod\": {\"host\": \"p\", \"only\": \"x\"}}");
        Path file = dir.resolve("api.http");
        Files.writeString(file, "@base = {{host}}/v1\nGET {{base}}/a\nX-Only: {{only}}\nX-Typo: {{nope}}\n");
        EditorBuffer b = open(file);
        await("the marks arrive", () -> marks(b).size() == 4);
        List<String> m = FxTestSupport.callOnFx(() -> marks(b));
        assertEquals("{{host}}: no environment is selected (defined in dev, prod)", m.get(0), m.toString());
        assertTrue(
                m.get(1).startsWith("{{base}}: uses {{host}}, which cannot be resolved: no environment is selected"),
                m.toString());
        assertEquals("{{only}}: no environment is selected (defined in prod)", m.get(2), m.toString());
        assertEquals("{{nope}}: not defined in the file or the selected environment", m.get(3), m.toString());
    }

    @Test
    void anUnreadableEnvFileIsNamedByTheMarksAndByTheRun() throws Exception {
        Path dir = project("{\"dev\": {\"host\": \"" + host() + "\"}}");
        Files.writeString(dir.resolve("http-client.private.env.json"), "{\"dev\": {\"token\": \"FAKE\" \"x\": 1}}");
        Path file = dir.resolve("broken.http");
        Files.writeString(file, "GET {{host}}/me\nAuthorization: Bearer {{token}}\n");
        EditorBuffer b = open(file);
        select(b, "dev");
        await(
                "the mark names the private file",
                () -> marks(b).stream()
                        .anyMatch(
                                s -> s.startsWith("{{token}}: not defined, and an environment file could not be read: "
                                        + "http-client.private.env.json could not be read: line 1, column ")));
        seen.clear();
        FxTestSupport.runOnFx(() -> c().httpClient.runRequest(b, 0));
        HttpClientPanel p = panel(b);
        await(
                "the run lists the file first",
                () -> p.notesForTest().contains("http-client.private.env.json could not"));
        assertEquals(List.of("GET /me auth=Bearer"), seen);
        String notes = FxTestSupport.callOnFx(p::notesForTest);
        assertTrue(notes.indexOf("could not be read") < notes.indexOf("{{token}}"), notes);
    }

    @Test
    void anEnvFileChangedWhileTheWindowWasAwayIsPickedUpOnTheNextFocus() throws Exception {
        Path dir = project("{\"dev\": {\"host\": \"h\"}}");
        Path file = dir.resolve("focus.http");
        Files.writeString(file, "GET {{host}}/x\nX-New: {{added}}\n");
        EditorBuffer b = open(file);
        select(b, "dev");
        await("{{added}} is marked", () -> marks(b).stream().anyMatch(s -> s.startsWith("{{added}}")));
        Files.writeString(
                dir.resolve("http-client.env.json"),
                "{\"dev\": {\"host\": \"h\", \"added\": \"a\"}, \"staging\": {\"host\": \"s\"}}");
        FxTestSupport.runOnFx(() -> {
            if (c().startupFocus.startupFocusPending()) {
                c().startupFocus.onFocusGained(); // the show-time focus, so the next one is a real regain
            }
            c().onWindowFocusGained();
        });
        await("the mark is gone", () -> marks(b).isEmpty());
        assertTrue(FxTestSupport.callOnFx(
                () -> c().httpClient.environmentNamesForTest(b).contains("staging")));
    }

    @Test
    void savingAnHttpFileAsMarkdownSwapsTheMarksForMarkdownLint() throws Exception {
        Path dir = project(null);
        Path file = dir.resolve("notes.http");
        Files.writeString(file, "#Heading without a space\nGET http://h/{{nope}}\n");
        EditorBuffer b = open(file);
        await("the HTTP mark", () -> marks(b).stream().anyMatch(s -> s.startsWith("{{nope}}")));
        Path md = dir.resolve("notes.md");
        List<MarkdownLint.Diagnostic> atSwap = FxTestSupport.callOnFx(() -> {
            FxTestSupport.call(
                    FxTestSupport.field(c(), "fileWorkflows"),
                    "applySaveAsTarget",
                    new Class<?>[] {EditorBuffer.class, Path.class},
                    b,
                    md);
            return b.markdownLintDiagnostics(); // same FX turn: Markdown lint's async result cannot be in yet
        });
        assertFalse(
                atSwap.stream().anyMatch(d -> d.code().startsWith("{{")),
                "the HTTP marks go with the HTTP file, not when Markdown lint replaces them: " + atSwap);
        await(
                "Markdown lint runs on the Markdown file",
                () -> b.markdownLintDiagnostics().stream()
                        .anyMatch(d -> !d.code().startsWith("{{")));
        await("no HTTP marks are left", () -> marks(b).isEmpty());
        List<MarkdownLint.Diagnostic> shown = FxTestSupport.callOnFx(b::markdownLintDiagnostics);
        assertFalse(shown.stream().anyMatch(d -> d.code().startsWith("{{")), shown.toString());
        FxTestSupport.runOnFx(() -> b.getArea().appendText("more {{text}}\n"));
        FxTestSupport.drainFx();
        Thread.sleep(700); // past the settle pause: an HTTP check would land now
        FxTestSupport.drainFx();
        assertFalse(FxTestSupport.callOnFx(b::markdownLintDiagnostics).stream()
                .anyMatch(d -> d.code().startsWith("{{")));
    }

    private void clipboard(String text) throws Exception {
        FxTestSupport.runOnFx(() -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(text);
            Clipboard.getSystemClipboard().setContent(cc);
        });
    }

    @Test
    void importCurlRefusesACommandWithoutAUrlAndSaysWhenItChangesTheBody() throws Exception {
        Path dir = project(null);
        Path file = dir.resolve("import.http");
        Files.writeString(file, "GET http://h/first\n");
        EditorBuffer b = open(file);
        clipboard("curl -s -L --compressed");
        FxTestSupport.runOnFx(() ->
                ((com.editora.command.CommandRegistry) FxTestSupport.field(c(), "registry")).run("http.importCurl"));
        FxTestSupport.drainFx();
        assertEquals(
                "GET http://h/first\n", FxTestSupport.callOnFx(() -> b.getArea().getText()));
        assertEquals("No curl command on the clipboard", status());

        clipboard("curl.exe \"http://127.0.0.1:9/win\" -H \"Accept: application/json\"");
        FxTestSupport.runOnFx(() ->
                ((com.editora.command.CommandRegistry) FxTestSupport.field(c(), "registry")).run("http.importCurl"));
        FxTestSupport.drainFx();
        assertTrue(FxTestSupport.callOnFx(() -> b.getArea().getText()).contains("\nGET http://127.0.0.1:9/win\n"));
        assertEquals("Imported cURL command as a request", status());

        clipboard("curl -H 'Content-Type: text/markdown' --data-raw $'# Title\\ntext\\n> quote' http://127.0.0.1:9/md");
        FxTestSupport.runOnFx(() ->
                ((com.editora.command.CommandRegistry) FxTestSupport.field(c(), "registry")).run("http.importCurl"));
        FxTestSupport.drainFx();
        assertTrue(
                status().startsWith("Imported cURL command as a request — check the body: 2 of its lines"), status());
    }

    @Test
    void theCompletionPopupKeepsResponsesAndBuiltInsBesideABigEnvironment() throws Exception {
        StringBuilder users = new StringBuilder("[");
        for (int i = 0; i < 30; i++) {
            users.append(i == 0 ? "" : ",").append("{\"id\":").append(i).append(",\"a\":{\"c\":\"x\"}}");
        }
        Path dir = project("{\"dev\": {\"host\": \"h\", \"users\": " + users + "]}}");
        Path file = dir.resolve("complete.http");
        Files.writeString(file, "# @name login\nPOST {{host}}/login\n\n###\nGET {{host}}/x?u=");
        EditorBuffer b = open(file);
        select(b, "dev");
        await("the context is in", () -> marks(b).isEmpty());
        Thread.sleep(600);
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            b.getArea().requestFocus();
            b.getArea().moveTo(b.getArea().getLength());
        });
        for (String ch : List.of("{", "{")) {
            FxTestSupport.runOnFx(() -> b.getArea()
                    .fireEvent(
                            new KeyEvent(KeyEvent.KEY_TYPED, ch, ch, KeyCode.UNDEFINED, false, false, false, false)));
        }
        await("the popup shows", b::completionShowing);
        Object popup = FxTestSupport.field(FxTestSupport.field(b, "completionActions"), "completionPopup");
        ListView<Completion> list = FxTestSupport.field(popup, "list");
        List<String> rows = FxTestSupport.callOnFx(
                () -> list.getItems().stream().map(Completion::label).toList());
        assertTrue(rows.contains("login.response."), rows.toString());
        assertTrue(rows.contains("$uuid"), rows.toString());
        assertFalse(rows.contains("users[0]"), rows.toString());
        FxTestSupport.runOnFx(() -> b.getArea()
                .fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false)));
    }
}
