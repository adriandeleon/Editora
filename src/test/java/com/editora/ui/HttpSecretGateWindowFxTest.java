package com.editora.ui;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Labeled;
import javafx.scene.input.Clipboard;
import javafx.stage.Window;

import com.editora.command.CommandRegistry;
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
 * The secrets trust gate in a real window, with the real prompt (third review):
 *
 * <ul>
 *   <li>G1 — a request that reads a secret but is never sent (its body file is missing) puts up no prompt, and the
 *       value is in neither the status bar, the history file on disk, nor what Copy as cURL copies;
 *   <li>X1 — the prompt's default and focused button is Cancel, so a stray Enter or Space never trusts a folder.
 * </ul>
 *
 * {@code PATH} stands in for a secret: every platform has it.
 */
@Tag("fx")
class HttpSecretGateWindowFxTest {

    private static final String SECRET = System.getenv("PATH");

    @TempDir
    Path work;

    private HttpServer server;
    private final List<String> hits = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        assertTrue(SECRET != null && SECRET.length() > 3, "the test needs PATH to be set");
        FxTestSupport.bootToolkit();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            hits.add(ex.getRequestURI().toString());
            ex.sendResponseHeaders(204, -1);
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
    void aRequestThatIsNeverSentPutsUpNoPromptAndKeepsNoSecret() throws Exception {
        Path dir = Files.createDirectories(work.resolve("cloned"));
        Path file = dir.resolve("api.http");
        Files.writeString(file, "POST " + url() + "/mb?k={{$env PATH}}\nX-Key: {{$env PATH}}\n\n< ./missing.json\n");
        Path cfg = Files.createDirectories(work.resolve("cfg"));
        try (FxWindowFixture fx = open(cfg, file)) {
            HttpClientCoordinator http = FxTestSupport.field(fx.controller, "httpClient");
            EditorBuffer b = FxTestSupport.callOnFx(fx.controller::activeBuffer);
            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            FxTestSupport.runOnFx(() -> registry.run("http.runRequest"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (FxTestSupport.callOnFx(() -> http.isRunningForTest(b))) {
                DialogPane prompt = FxTestSupport.callOnFx(HttpSecretGateWindowFxTest::showingDialog);
                if (prompt != null) {
                    FxTestSupport.runOnFx(() -> ((Button) prompt.lookupButton(ButtonType.CANCEL)).fire());
                    throw new AssertionError("a trust prompt came up for a request that is never sent");
                }
                assertTrue(System.nanoTime() < deadline, "the run never ended");
                FxTestSupport.drainFx();
            }
            FxTestSupport.drainFx();
            assertEquals(List.of(), hits, "nothing was sent");
            String echo = FxTestSupport.callOnFx(() -> {
                Object bar = FxTestSupport.field(fx.controller, "statusBar");
                Labeled label = FxTestSupport.field(bar, "echo");
                return label.getText();
            });
            assertTrue(echo.contains("missing.json"), "the status bar says why: " + echo);
            assertFalse(echo.contains(SECRET), "the status bar showed the secret: " + echo);

            FxTestSupport.runOnFx(() -> Clipboard.getSystemClipboard().clear());
            FxTestSupport.runOnFx(() -> registry.run("http.copyAsCurl"));
            String clip =
                    FxTestSupport.callOnFx(() -> Clipboard.getSystemClipboard().getString());
            // A request that was not sent has no command to copy (Copy as cURL is disabled for it); whatever the
            // clipboard holds, it must not be the secret.
            assertFalse(clip != null && clip.contains(SECRET), "Copy as cURL copied the secret");

            Path history = cfg.resolve("http-history");
            String saved = awaitSavedHistory(history, "/mb");
            assertFalse(saved.contains(SECRET), "the history file on disk holds the secret");
        }
    }

    @Test
    void theTrustPromptsDefaultAndFocusedButtonIsCancel() throws Exception {
        Path dir = Files.createDirectories(work.resolve("untrusted"));
        Path file = dir.resolve("api.http");
        Files.writeString(file, "GET " + url() + "/x?k={{$env PATH}}\n");
        Path cfg = Files.createDirectories(work.resolve("cfg2"));
        try (FxWindowFixture fx = open(cfg, file)) {
            HttpClientCoordinator http = FxTestSupport.field(fx.controller, "httpClient");
            EditorBuffer b = FxTestSupport.callOnFx(fx.controller::activeBuffer);
            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            FxTestSupport.runOnFx(() -> registry.run("http.runRequest"));
            DialogPane prompt = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (prompt == null) {
                assertTrue(System.nanoTime() < deadline, "the trust prompt never came up");
                FxTestSupport.drainFx();
                prompt = FxTestSupport.callOnFx(HttpSecretGateWindowFxTest::showingDialog);
            }
            DialogPane pane = prompt;
            Button cancel = FxTestSupport.callOnFx(() -> (Button) pane.lookupButton(ButtonType.CANCEL));
            Button trust = FxTestSupport.callOnFx(() -> pane.getButtonTypes().stream()
                    .filter(t -> t != ButtonType.CANCEL)
                    .map(t -> (Button) pane.lookupButton(t))
                    .findFirst()
                    .orElseThrow());
            FxTestSupport.drainFx();
            boolean cancelDefault = FxTestSupport.callOnFx(cancel::isDefaultButton);
            boolean trustDefault = FxTestSupport.callOnFx(trust::isDefaultButton);
            Node focused = FxTestSupport.callOnFx(() -> pane.getScene().getFocusOwner());
            FxTestSupport.runOnFx(cancel::fire);
            while (FxTestSupport.callOnFx(() -> http.isRunningForTest(b))) {
                assertTrue(System.nanoTime() < deadline, "the run never ended");
                FxTestSupport.drainFx();
            }
            assertTrue(cancelDefault, "Cancel is the default button: Enter does not trust the folder");
            assertFalse(trustDefault, "the trust button is not the default");
            assertEquals(cancel, focused, "Cancel has the focus: Space does not trust the folder either");
            assertEquals(List.of(), hits, "cancelled: nothing was sent");
        }
    }

    @Test
    void theBuildWrapperTrustPromptDefaultsToCancelToo() throws Exception {
        TrustPrompts.Prompt prompt = FxTestSupport.callOnFx(() -> TrustPrompts.buildProject(
                null,
                work,
                com.editora.build.BuildTrust.describe(
                        com.editora.build.BuildTool.MAVEN, work, work.resolve("mvnw"), "")));
        FxTestSupport.runOnFx(() -> prompt.alert().show());
        try {
            DialogPane pane = prompt.alert().getDialogPane();
            Button cancel = FxTestSupport.callOnFx(() -> (Button) pane.lookupButton(ButtonType.CANCEL));
            Button trust = FxTestSupport.callOnFx(() -> (Button) pane.lookupButton(prompt.trust()));
            FxTestSupport.drainFx();
            assertTrue(FxTestSupport.callOnFx(cancel::isDefaultButton));
            assertFalse(FxTestSupport.callOnFx(trust::isDefaultButton));
            assertEquals(cancel, FxTestSupport.callOnFx(() -> pane.getScene().getFocusOwner()));
        } finally {
            FxTestSupport.runOnFx(() -> prompt.alert().close());
        }
    }

    private static FxWindowFixture open(Path cfg, Path file) throws Exception {
        FxWindowFixture fx = FxWindowFixture.create(
                cfg, false, false, false, List.of(new MainController.OpenTarget(file, 1, 1)), true, c -> {});
        FxTestSupport.runOnFx(() -> fx.shared.getSettings().setHttpClientSupport(true));
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                fx.controller,
                "onSettingsApplied",
                new Class<?>[] {com.editora.config.Settings.class},
                fx.shared.getSettings()));
        return fx;
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
