package com.editora.ui;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;

import com.editora.command.CommandRegistry;
import com.editora.config.HttpResponseHistory;
import com.editora.config.PathKeys;
import com.editora.editor.EditorBuffer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The secrets trust prompt answered "Trust" in a real window, end to end: the real {@code MainController} prompt names
 * the folder it offers, trusting it reaches the server with the value and is remembered in the trust store (the next
 * run asks nothing), and the value the run read is masked in what is kept — the history file on disk and the History
 * label — while the live request as sent still showed it. {@code PATH} stands in for a secret: every platform has it.
 */
@Tag("fx")
class HttpSecretTrustWindowFxTest {

    private static final String SECRET = System.getenv("PATH");

    @TempDir
    Path work;

    private HttpServer server;
    private final List<String> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        assertTrue(SECRET != null && SECRET.length() > 3, "the test needs PATH to be set");
        FxTestSupport.bootToolkit();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String note = ex.getRequestHeaders().getFirst("X-Note");
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(ex.getRequestURI().getPath() + " note=" + note + " body=" + body);
            byte[] reply = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, reply.length);
            ex.getResponseBody().write(reply);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void trustingTheFolderSendsTheValueRemembersTheFolderAndMasksWhatIsKept() throws Exception {
        Path dir = Files.createDirectories(work.resolve("cloned-api"));
        Path file = dir.resolve("api.http");
        Files.writeString(
                file,
                "POST http://127.0.0.1:" + server.getAddress().getPort() + "/send?k={{$processEnv PATH}}\n"
                        + "X-Note: {{$processEnv PATH}}\nContent-Type: text/plain\n\nnote={{$processEnv PATH}}\n");
        Path cfg = Files.createDirectories(work.resolve("cfg"));
        try (FxWindowFixture fx = FxWindowFixture.create(
                cfg, false, false, false, List.of(new MainController.OpenTarget(file, 1, 1)), true, c -> {})) {
            FxTestSupport.runOnFx(() -> fx.shared.getSettings().setHttpClientSupport(true));
            FxTestSupport.runOnFx(() -> FxTestSupport.call(
                    fx.controller,
                    "onSettingsApplied",
                    new Class<?>[] {com.editora.config.Settings.class},
                    fx.shared.getSettings()));
            HttpClientCoordinator http = FxTestSupport.field(fx.controller, "httpClient");
            EditorBuffer b = FxTestSupport.callOnFx(fx.controller::activeBuffer);
            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            Path root = PathKeys.canonical(dir);
            assertFalse(fx.shared.getTrustStore().isTrusted(root), "a fresh folder is not trusted");

            // The real prompt names the folder it offers (no project open: the file's own folder) and the read.
            FxTestSupport.runOnFx(() -> registry.run("http.runRequest"));
            DialogPane prompt = awaitPrompt();
            String header = FxTestSupport.callOnFx(prompt::getHeaderText);
            String body = FxTestSupport.callOnFx(prompt::getContentText);
            assertTrue(header.contains("cloned-api"), "the header names the folder: " + header);
            assertTrue(body.contains(root.toString()), "the text names the folder it trusts: " + body);
            assertTrue(body.contains("$processEnv PATH"), "the text names the read as written: " + body);
            assertFalse(body.contains(SECRET), "the prompt shows the value: " + body);
            Button trust = FxTestSupport.callOnFx(() -> prompt.getButtonTypes().stream()
                    .filter(t -> t != ButtonType.CANCEL)
                    .map(t -> (Button) prompt.lookupButton(t))
                    .findFirst()
                    .orElseThrow());
            FxTestSupport.runOnFx(trust::fire);
            awaitRunEnd(http, b);

            assertEquals(List.of("/send note=" + SECRET + " body=note=" + SECRET), received, "trusted: the value went");
            assertTrue(fx.shared.getTrustStore().isTrusted(root), "the folder is remembered as trusted");

            // Kept: the history file on disk and the History label hold the mask, never the value.
            HttpClientPanel panel = FxTestSupport.callOnFx(() -> http.panelForTest(b));
            String label = FxTestSupport.callOnFx(
                    () -> panel.controlsForTest().historyPicker().getValue().label(ZoneId.systemDefault()));
            assertFalse(label.contains(SECRET), "the History label shows the value: " + label);
            String saved = awaitSavedHistory(cfg.resolve("http-history"), "/send");
            assertFalse(saved.contains(SECRET), "the history file on disk holds the value");
            assertTrue(
                    saved.contains(HttpResponseHistory.MASK) || saved.contains("\\u2022"),
                    "the history file holds the mask where the value was");

            // Trusted now: the next run asks nothing and is sent.
            FxTestSupport.runOnFx(() -> registry.run("http.runRequest"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (received.size() < 2) {
                assertTrue(System.nanoTime() < deadline, "the second run was never sent");
                assertEquals(null, FxTestSupport.callOnFx(HttpSecretTrustWindowFxTest::showingDialog), "asked again");
                FxTestSupport.drainFx();
            }
            awaitRunEnd(http, b);
        }
    }

    private static DialogPane awaitPrompt() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            DialogPane prompt = FxTestSupport.callOnFx(HttpSecretTrustWindowFxTest::showingDialog);
            if (prompt != null) {
                return prompt;
            }
            assertTrue(System.nanoTime() < deadline, "the trust prompt never came up");
            FxTestSupport.drainFx();
        }
    }

    private static void awaitRunEnd(HttpClientCoordinator http, EditorBuffer b) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (FxTestSupport.callOnFx(() -> http.isRunningForTest(b))) {
            assertTrue(System.nanoTime() < deadline, "the run never ended");
            FxTestSupport.drainFx();
        }
        FxTestSupport.drainFx();
    }

    /** A dialog showing on the FX thread now, or null. */
    private static DialogPane showingDialog() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && w.getScene() != null && w.getScene().getRoot() instanceof DialogPane pane) {
                return pane;
            }
        }
        return null;
    }

    /** The saved history file whose text mentions {@code marker}, waited for (the store writes it queued). */
    private static String awaitSavedHistory(Path dir, String marker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            if (Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path f :
                            files.filter(p -> p.toString().endsWith(".json")).toList()) {
                        String text = Files.readString(f, StandardCharsets.UTF_8);
                        if (text.contains(marker)) {
                            return text;
                        }
                    }
                }
            }
            assertTrue(System.nanoTime() < deadline, "no history file was saved in " + dir);
            FxTestSupport.drainFx();
            assertNotNull(dir);
        }
    }
}
