package com.editora.ui;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.scene.Node;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.editor.lsp.CodeAction;
import com.editora.http.HttpExchange;
import com.editora.i18n.Messages;
import com.editora.markdown.MarkdownLint;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U3: the environment of a {@code .http} file is the coordinator's, shown on a pill beside the view toggle from the
 * moment the file opens — in every view mode, before any run — and chosen there, in the status bar's menu, or with the
 * "Use environment dev" quick fix on an unresolved {@code {{variable}}}. What a run sends, the marks, the saved
 * history and the run's warnings all follow that choice.
 */
@Tag("fx")
class HttpEnvPillFxTest {

    private HttpServer server;
    private int port;
    private final List<String> hits = new CopyOnWriteArrayList<>();
    private Host host;
    private HttpClientCoordinator coordinator;
    private HttpVariableHints hints;
    private final List<EditorBuffer> buffers = new ArrayList<>();

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        Messages.init("en");
    }

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            hits.add(ex.getRequestURI().getPath() + (auth == null ? "" : " " + auth));
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();
        host = new Host();
        host.settings.setHttpClientSupport(true);
        coordinator = new HttpClientCoordinator(host, new HttpClientCoordinator.WindowOps() {
            @Override
            public void openTab(EditorBuffer buffer) {}

            @Override
            public void updateRunGating() {}
        });
        hints = new HttpVariableHints(new HttpVariableHints.Source() {
            @Override
            public String environment(EditorBuffer buffer) {
                return coordinator.selectedEnvironment(buffer);
            }

            @Override
            public Path envDir(EditorBuffer buffer) {
                return coordinator.envDirFor(buffer);
            }

            @Override
            public void choose(EditorBuffer buffer, String environment) {
                coordinator.chooseEnvironment(buffer, environment);
            }
        });
        coordinator.setEnvironmentListener(hints::refresh);
    }

    @AfterEach
    void tearDown() throws Exception {
        hints.shutdown();
        coordinator.shutdown();
        server.stop(0);
        FxTestSupport.runOnFx(() -> buffers.forEach(EditorBuffer::dispose));
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private void envFile(Path dir) throws Exception {
        Files.writeString(
                dir.resolve("http-client.env.json"),
                "{\"dev\":{\"host\":\"" + url("") + "\",\"token\":\"D\"},\"staging\":{\"host\":\"" + url("")
                        + "\",\"token\":\"S\"},\"prod\":{\"host\":\"" + url("") + "\"}}");
    }

    private EditorBuffer open(Path file, String text) throws Exception {
        Files.writeString(file, text);
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer created = new EditorBuffer();
            created.setPath(file);
            created.setContent(text);
            created.markClean();
            host.buffers.add(created);
            host.active = created;
            hints.wire(created);
            created.setHttpEnabled(true);
            coordinator.ensureHttpPreview(created);
            return created;
        });
        HttpEnvCache.awaitIdleForTest(); // the env files are read off the FX thread
        buffers.add(b);
        return b;
    }

    private HttpExchange run(EditorBuffer b, int line) throws Exception {
        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, line));
        await(() -> !coordinator.isRunningForTest(b));
        FxTestSupport.drainFx();
        return FxTestSupport.callOnFx(() -> coordinator.panelForTest(b).getSelectedExchange());
    }

    private static List<String> marked(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.httpVariableDiagnostics().stream()
                .map(MarkdownLint.Diagnostic::code)
                .toList());
    }

    /** The quick fixes offered on the mark at the first {@code needle} of {@code b}'s text. */
    private static List<CodeAction> fixesAt(EditorBuffer b, String needle) throws Exception {
        return FxTestSupport.callOnFx(
                () -> b.httpVariableFixesAt(b.getArea(), b.getContent().indexOf(needle) + 2));
    }

    private static List<String> titles(List<CodeAction> fixes) {
        return fixes.stream().map(CodeAction::title).toList();
    }

    private static void await(BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!FxTestSupport.callOnFx(done::getAsBoolean)) {
            assertTrue(System.nanoTime() < deadline, "timed out");
            Thread.sleep(20);
        }
    }

    @Test
    void thePillIsThereBeforeAnyRunAndChoosingAnEnvironmentChangesTheMarksAndWhatIsSent(@TempDir Path dir)
            throws Exception {
        envFile(dir);
        EditorBuffer b = open(dir.resolve("api.http"), "GET {{host}}/me\nAuthorization: Bearer {{token}}\n");

        HttpEnvPill pill = FxTestSupport.callOnFx(() -> coordinator.envPillForTest(b));
        assertNotNull(pill, "a pill as soon as the file is open, before any run");
        assertTrue(FxTestSupport.callOnFx(b::hasHttpEnvControl));
        assertEquals("No environment", FxTestSupport.callOnFx(pill::textForTest));
        assertTrue(FxTestSupport.callOnFx(pill::showsNoneForTest), "muted");
        assertEquals("No environment", FxTestSupport.callOnFx(() -> coordinator.environmentLabel(b)));
        await(() -> b.httpVariableDiagnostics().size() == 2);
        assertEquals(List.of("{{host}}", "{{token}}"), marked(b));

        // The mark offers the environments that define the variable; using one selects it.
        // The fixes come from the env files' names, read in the background: that read may land after the marks.
        await(() -> {
            try {
                return !fixesAt(b, "{{token}}").isEmpty();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        List<CodeAction> fixes = fixesAt(b, "{{token}}");
        assertEquals(List.of("Use environment dev", "Use environment staging"), titles(fixes));
        FxTestSupport.runOnFx(() -> ((Runnable) fixes.get(0).token()).run());
        assertEquals("dev", FxTestSupport.callOnFx(() -> coordinator.selectedEnvironment(b)));
        assertEquals("Env: dev", FxTestSupport.callOnFx(pill::textForTest));
        assertFalse(FxTestSupport.callOnFx(pill::showsNoneForTest));
        assertEquals("Env: dev", FxTestSupport.callOnFx(() -> coordinator.environmentLabel(b)));
        await(() -> b.httpVariableDiagnostics().isEmpty());

        HttpExchange sent = run(b, 0);
        assertEquals(List.of("/me Bearer D"), hits, "the run resolves against the pill's environment");
        assertEquals(200, sent.result().status());
        HttpHistoryEntry entry =
                FxTestSupport.callOnFx(() -> coordinator.panelForTest(b).getSelectedEntry());
        assertEquals("dev", entry.environment(), "the history entry records the environment it was sent in");
        assertEquals("dev", entry.toStored().environment(), "…and so does its saved form");
        assertEquals("dev", HttpHistoryEntry.fromStored(entry.toStored()).environment());
        String shown = FxTestSupport.callOnFx(() -> coordinator.panelForTest(b).statusForTest());
        assertTrue(shown.endsWith(tr("httppanel.envNote", "dev")), "the response names its environment: " + shown);

        // The menu (the pill's, the status bar's, the command's): pick staging.
        FxTestSupport.runOnFx(() -> coordinator.showEnvironmentMenu(b, pill, javafx.geometry.Side.BOTTOM));
        List<MenuItem> items = FxTestSupport.callOnFx(
                () -> List.copyOf(coordinator.environmentMenuForTest().getItems()));
        List<RadioMenuItem> radios = items.stream()
                .filter(i -> i instanceof RadioMenuItem)
                .map(i -> (RadioMenuItem) i)
                .toList();
        assertEquals(
                List.of("dev", "staging", "prod", "No environment"),
                radios.stream().map(MenuItem::getText).toList());
        assertTrue(radios.get(0).isSelected(), "the selected environment is checked");
        assertTrue(
                items.stream()
                        .anyMatch(
                                i -> tr("httpenv.open", "http-client.env.json").equals(i.getText())),
                "the env file can be opened from the menu");
        FxTestSupport.runOnFx(() -> radios.get(1).fire());
        assertEquals("staging", FxTestSupport.callOnFx(() -> coordinator.selectedEnvironment(b)));
        run(b, 0);
        assertEquals(List.of("/me Bearer D", "/me Bearer S"), hits);

        // prod lacks the token: the mark says who has it, and offers them.
        FxTestSupport.runOnFx(() -> coordinator.chooseEnvironment(b, "prod"));
        await(() -> b.httpVariableDiagnostics().size() == 1);
        assertEquals(
                "not defined in the environment prod (defined in dev, staging)",
                FxTestSupport.callOnFx(() -> b.httpVariableDiagnostics().get(0).message()));
        assertEquals(List.of("Use environment dev", "Use environment staging"), titles(fixesAt(b, "{{token}}")));
        assertEquals(List.of(), titles(fixesAt(b, "{{host}}")), "a resolved variable has no mark, so no fix");
    }

    @Test
    void thePillStaysBesideTheToggleInEveryViewMode(@TempDir Path dir) throws Exception {
        envFile(dir);
        EditorBuffer b = open(dir.resolve("modes.http"), "GET {{host}}/x\n");
        HttpEnvPill pill = FxTestSupport.callOnFx(() -> coordinator.envPillForTest(b));
        FxTestSupport.runOnFx(() -> new javafx.scene.Scene(b.getNode(), 900, 600)); // a Split's skin mounts its panes
        for (EditorBuffer.MarkdownViewMode mode : List.of(
                EditorBuffer.MarkdownViewMode.SPLIT,
                EditorBuffer.MarkdownViewMode.PREVIEW,
                EditorBuffer.MarkdownViewMode.EDITOR)) {
            FxTestSupport.runOnFx(() -> {
                b.setMarkdownViewMode(mode);
                b.getNode().applyCss();
                b.getNode().layout();
            });
            assertTrue(
                    FxTestSupport.callOnFx(() -> {
                        Node n = pill;
                        while (n != null && n != b.getNode()) {
                            n = n.getParent();
                        }
                        return n != null;
                    }),
                    "the pill is shown in " + mode);
        }
    }

    @Test
    void withoutEnvFilesTheMenuSaysWhereEnvironmentsComeFrom(@TempDir Path dir) throws Exception {
        EditorBuffer b = open(dir.resolve("plain.http"), "GET " + url("/x") + "\nX-T: {{token}}\n");
        HttpEnvPill pill = FxTestSupport.callOnFx(() -> coordinator.envPillForTest(b));
        assertNotNull(pill, "shown even without env files, so environments can be discovered");
        FxTestSupport.runOnFx(() -> coordinator.showEnvironmentMenu(b, pill, javafx.geometry.Side.BOTTOM));
        List<String> texts = FxTestSupport.callOnFx(() -> coordinator.environmentMenuForTest().getItems().stream()
                .map(MenuItem::getText)
                .toList());
        assertEquals(List.of("No environment", "No http-client.env.json beside this file"), texts);
        assertTrue(FxTestSupport.callOnFx(
                () -> coordinator.environmentMenuForTest().getItems().get(1).isDisable()));
        assertEquals(List.of(), titles(fixesAt(b, "{{token}}")), "nothing defines it: no fix");
    }

    @Test
    void aVariableSentEmptyIsWarnedWithTheEnvironmentsThatDefineIt(@TempDir Path dir) throws Exception {
        envFile(dir);
        EditorBuffer b = open(dir.resolve("warn.http"), "GET " + url("/w") + "\nAuthorization: Bearer {{token}}\n");
        HttpExchange ex = run(b, 0);
        assertEquals(List.of("/w Bearer"), hits);
        assertTrue(
                ex.result()
                        .warnings()
                        .contains(
                                "{{token}} was sent as empty text: no environment is selected (defined in dev, staging)."),
                ex.result().warnings().toString());
        HttpHistoryEntry entry =
                FxTestSupport.callOnFx(() -> coordinator.panelForTest(b).getSelectedEntry());
        assertEquals("", entry.environment(), "sent in no environment");
    }

    private static final class Host extends CoordinatorHostStub {
        final Settings settings = new Settings();
        final List<EditorBuffer> buffers = new CopyOnWriteArrayList<>();
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
        public void setStatus(String message) {}
    }
}
