package com.editora.ui;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.editor.EditorBuffer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Run All and Cancel chords typed into a real window (U10): Run All moved off Ctrl+Alt+Shift+R, which GNOME
 * keeps for screen recording, to Ctrl+Alt+Shift+Enter, and Cancel has a chord in every keymap.
 */
@Tag("fx")
class HttpRunChordsFxTest {

    @TempDir
    Path work;

    @Test
    void runAllAndCancelChordsReachTheHttpClientInTheCuaAndEmacsKeymaps() throws Exception {
        FxTestSupport.bootToolkit();
        List<String> hits = new CopyOnWriteArrayList<>();
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", ex -> {
            hits.add(ex.getRequestURI().getPath());
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.start();
        Path file = work.resolve("api.http");
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        Files.writeString(file, "### A\nGET " + url + "/a\n\n### B\nGET " + url + "/b\n");
        Path cfg = Files.createTempDirectory("http-chords-cfg");
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
            for (String keymap : List.of("cua", "emacs")) {
                hits.clear();
                FxTestSupport.runOnFx(() -> {
                    fx.shared.getSettings().setKeymap(keymap);
                    fx.windowManager.reloadSharedKeymap();
                });
                FxTestSupport.runOnFx(() -> {
                    b.getArea().requestFocus();
                    if (keymap.equals("cua")) {
                        // Ctrl+Alt+Shift+Enter; on macOS cua.mac.json binds Option+Cmd+Shift+Enter instead
                        press(b, KeyCode.ENTER, true, !MAC, true, MAC);
                    } else {
                        press(b, KeyCode.C, false, true, false, false); // C-c C-r (the same on macOS)
                        press(b, KeyCode.R, false, true, false, false);
                    }
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (hits.isEmpty()) {
                    assertTrue(System.nanoTime() < deadline, keymap + ": Run All never reached the server");
                    Thread.sleep(20);
                }
                assertTrue(FxTestSupport.callOnFx(() -> http.isRunningForTest(b)), keymap + ": running");
                FxTestSupport.runOnFx(() -> {
                    if (keymap.equals("cua")) {
                        press(b, KeyCode.K, false, !MAC, true, MAC); // Ctrl+Alt+K; Option+Cmd+K on macOS
                    } else {
                        press(b, KeyCode.C, false, true, false, false); // C-c C-k
                        press(b, KeyCode.K, false, true, false, false);
                    }
                });
                assertFalse(FxTestSupport.callOnFx(() -> http.isRunningForTest(b)), keymap + ": Cancel stopped it");
                assertEquals(List.of("/a"), hits, keymap + ": the run went no further");
            }
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    /** The keymap files the app loads on this OS: the {@code .mac.json} variants on macOS. */
    private static final boolean MAC = com.editora.command.KeymapManager.isMac();

    private static void press(EditorBuffer b, KeyCode code, boolean shift, boolean ctrl, boolean alt, boolean meta) {
        b.getArea().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, alt, meta));
        b.getArea().fireEvent(new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, shift, ctrl, alt, meta));
    }
}
