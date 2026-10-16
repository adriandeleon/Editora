package com.editora.ui;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.http.NodeResponseScripts;
import com.editora.process.ProcessRunner;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Response handlers in a real window: an untrusted folder puts up the scripts prompt, trusting it runs the handler
 * (its test shows in the Tests block, its token reaches the server) and is remembered, so the next run asks nothing.
 */
@Tag("fx")
class HttpScriptTrustWindowFxTest {

    @TempDir
    Path work;

    private HttpServer server;
    private final List<String> hits = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            hits.add(ex.getRequestURI().getPath() + " " + ex.getRequestHeaders().getFirst("X-Token"));
            byte[] body = "{\"token\":\"t-99\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void trustingTheFolderRunsTheHandlerAndIsRemembered() throws Exception {
        assumeTrue(
                NodeResponseScripts.version(
                                ProcessRunner.resolveExecutable(List.of("node")).get(0))
                        != null,
                "node is not installed");
        Path dir = Files.createDirectories(work.resolve("cloned"));
        Path file = dir.resolve("api.http");
        Files.writeString(
                file,
                "### login\nGET " + url() + "/login\n\n> {%\n  client.test('has token', () => "
                        + "client.assert(response.body.token === 't-99', 'token'));\n"
                        + "  client.global.set('token', response.body.token);\n%}\n\n"
                        + "### me\nGET " + url() + "/me\nX-Token: {{token}}\n");
        Path cfg = Files.createDirectories(work.resolve("cfg"));
        FxWindowFixture fx = FxWindowFixture.create(
                cfg, false, false, false, List.of(new MainController.OpenTarget(file, 1, 1)), true, c -> {});
        try (fx) {
            FxTestSupport.runOnFx(() -> fx.shared.getSettings().setHttpClientSupport(true));
            FxTestSupport.runOnFx(() -> FxTestSupport.call(
                    fx.controller,
                    "onSettingsApplied",
                    new Class<?>[] {com.editora.config.Settings.class},
                    fx.shared.getSettings()));
            HttpClientCoordinator http = FxTestSupport.field(fx.controller, "httpClient");
            EditorBuffer b = FxTestSupport.callOnFx(fx.controller::activeBuffer);
            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            FxTestSupport.runOnFx(() -> registry.run("http.runFile"));

            DialogPane prompt = awaitDialog();
            String header = FxTestSupport.callOnFx(prompt::getHeaderText);
            assertTrue(header.contains("Run scripts from the folder"), header);
            Button trust = FxTestSupport.callOnFx(() -> prompt.getButtonTypes().stream()
                    .filter(t -> t != ButtonType.CANCEL)
                    .map(t -> (Button) prompt.lookupButton(t))
                    .findFirst()
                    .orElseThrow());
            FxTestSupport.runOnFx(trust::fire);
            awaitIdle(http, b);
            assertEquals(List.of("/login null", "/me t-99"), hits, "the handler's token reached the next request");
            HttpClientPanel panel = http.panelForTest(b);
            String tests = FxTestSupport.callOnFx(panel::testsForTest);
            assertTrue(tests.contains("✓  has token"), tests);
            assertTrue(FxTestSupport.callOnFx(() -> fx.shared.getTrustStore().isTrusted(dir)), "remembered");

            // The next run asks nothing.
            hits.clear();
            FxTestSupport.runOnFx(() -> registry.run("http.runFile"));
            awaitIdle(http, b);
            assertEquals(List.of("/login null", "/me t-99"), hits);
            assertTrue(FxTestSupport.callOnFx(HttpScriptTrustWindowFxTest::showingDialog) == null);
        }
    }

    private static void awaitIdle(HttpClientCoordinator http, EditorBuffer b) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        do {
            assertTrue(System.nanoTime() < deadline, "the run never ended");
            FxTestSupport.drainFx();
            Thread.sleep(30);
        } while (FxTestSupport.callOnFx(() -> http.isRunningForTest(b)));
        FxTestSupport.drainFx();
    }

    private static DialogPane awaitDialog() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            assertTrue(System.nanoTime() < deadline, "the trust prompt never came up");
            FxTestSupport.drainFx();
            DialogPane pane = FxTestSupport.callOnFx(HttpScriptTrustWindowFxTest::showingDialog);
            if (pane != null) {
                return pane;
            }
            Thread.sleep(20);
        }
    }

    private static DialogPane showingDialog() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && w.getScene() != null && w.getScene().getRoot() instanceof DialogPane pane) {
                return pane;
            }
        }
        return null;
    }
}
