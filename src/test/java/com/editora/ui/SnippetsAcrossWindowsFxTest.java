package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.snippet.SnippetManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Snippets in two real windows: a change to a user snippet file reaches the other window without a manual
 * reload (N7), a broken file is reported with its name and line (N9), the Tab-expansion switch follows the
 * shared setting (N5), and the status bar shows a running session (N3).
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnippetsAcrossWindowsFxTest {

    private FxWindowFixture fx;
    private MainController a;
    private MainController b;
    private EditorBuffer bufferA;
    private EditorBuffer bufferB;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        a = fx.controller;
        b = FxTestSupport.callOnFx(() -> {
            fx.windowManager.newWindow();
            List<?> holders = FxTestSupport.field(fx.windowManager, "windows");
            return (MainController)
                    FxTestSupport.call(holders.get(holders.size() - 1), "controller", new Class<?>[] {});
        });
        assertNotSame(a, b, "a genuinely second window");
        bufferA = addBuffer(a);
        bufferB = addBuffer(b);
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private static EditorBuffer addBuffer(MainController window) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setLanguageOverride("java");
            buffer.setContent("");
            FxTestSupport.call(window, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
            return buffer;
        });
    }

    private static SnippetManager manager(MainController window) {
        return window.snippetCoordinator().manager();
    }

    private static String echo(MainController window) {
        StatusBar bar = FxTestSupport.field(window, "statusBar");
        return FxTestSupport.<Label>field(bar, "echo").getText();
    }

    @Test
    void aSnippetSavedInOneWindowIsLiveInTheOther() throws Exception {
        FxTestSupport.runOnFx(() -> {
            try {
                assertNotNull(manager(b).byPrefix("java", "fori"), "window B has loaded and cached java");
                assertNull(manager(b).byPrefix("java", "zzed"));
                manager(a) // what Settings → Snippets does in window A
                        .saveUserEntry("java", null, SnippetManager.Entry.user("Zed", List.of("zzed"), "ZED", ""));
                assertNotNull(manager(a).byPrefix("java", "zzed"));
                assertNotNull(manager(b).byPrefix("java", "zzed"), "N7: B used to need Reload Snippets or a restart");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    void savingAUserSnippetFileInTheEditorReloadsItEverywhereAndABrokenOneIsReported() throws Exception {
        Path file = manager(a).userFile("go");
        FxTestSupport.runOnFx(() -> {
            try {
                assertNull(manager(b).byPrefix("go", "zsaved"));
                Files.createDirectories(file.getParent());
                Files.writeString(file, "{ \"Saved\": { \"prefix\": \"zsaved\", \"body\": \"S\" } }");
                a.snippetCoordinator().fileSaved(file); // what a completed save of that file calls
                assertNotNull(manager(a).byPrefix("go", "zsaved"));
                assertNotNull(manager(b).byPrefix("go", "zsaved"), "the other window follows");
                assertEquals(tr("status.snippetFileReloaded", "go.json"), echo(a));

                Files.writeString(file, "{\n  \"Saved\": { \"prefix\": \"zsaved\" \"body\": \"S\" }\n}\n");
                a.snippetCoordinator().fileSaved(file);
                String said = echo(a);
                assertTrue(
                        said.contains("go.json") && said.contains("2"), "N9: the file and the line are named: " + said);
                assertFalse(said.contains("com.fasterxml") || said.contains("java."), said);
                assertNull(manager(a).byPrefix("go", "zsaved"));
                assertNotNull(manager(a).byPrefix("go", "pkgm"), "the bundled snippets are unaffected");

                a.snippetCoordinator().fileSaved(Path.of(fx.configDir.toString(), "settings.json"));
                assertEquals(said, echo(a), "any other file is none of the coordinator's business");
                Files.delete(file);
                a.snippetCoordinator().reload();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    void theTabExpansionSwitchReachesBothWindows() throws Exception {
        Settings settings = fx.shared.getSettings();
        assertTrue(settings.isSnippetTabExpansion(), "on by default");
        CommandRegistry registry = FxTestSupport.field(a, "registry");
        assertTrue(FxTestSupport.callOnFx(() -> registry.run("view.toggleSnippetTabExpansion")));
        for (int i = 0; i < 10; i++) {
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(fx.windowManager::flushPendingSettingsBroadcast);
            Thread.sleep(20);
        }
        assertFalse(settings.isSnippetTabExpansion());
        assertFalse(FxTestSupport.callOnFx(() -> FxTestSupport.<Boolean>field(bufferA, "snippetTabExpansion")));
        assertFalse(
                FxTestSupport.callOnFx(() -> FxTestSupport.<Boolean>field(bufferB, "snippetTabExpansion")),
                "the other window re-applied the shared setting");
        assertTrue(FxTestSupport.callOnFx(() -> registry.run("view.toggleSnippetTabExpansion")));
        for (int i = 0; i < 10; i++) {
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(fx.windowManager::flushPendingSettingsBroadcast);
            Thread.sleep(20);
        }
        assertTrue(FxTestSupport.callOnFx(() -> FxTestSupport.<Boolean>field(bufferB, "snippetTabExpansion")));
    }

    @Test
    void theStatusBarShowsARunningSessionAndTheCommandLeavesIt() throws Exception {
        FxTestSupport.runOnFx(() -> {
            StatusBar bar = FxTestSupport.field(a, "statusBar");
            Label hint = FxTestSupport.field(bar, "snippet");
            assertFalse(hint.isVisible());
            bufferA.setContent("fori");
            bufferA.getArea().moveTo(4);
            bufferA.getArea()
                    .fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, false, false, false, false));
            assertTrue(bufferA.hasActiveSnippet(), "the bundled fori expanded through the window's own wiring");
            assertTrue(hint.isVisible(), "N3: nothing used to say a session was running");
            assertEquals(tr("statusbar.snippet", 1, 4), hint.getText());
            bufferA.getArea()
                    .fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, false, false, false, false));
            assertEquals(tr("statusbar.snippet", 2, 4), hint.getText());

            CommandRegistry registry = FxTestSupport.field(a, "registry");
            assertTrue(registry.run("snippets.endSession"));
            assertFalse(bufferA.hasActiveSnippet());
            assertFalse(hint.isVisible());

            // The bundled global words are offered in lists but are not Tab triggers (N5).
            bufferA.setContent("date");
            bufferA.getArea().moveTo(4);
            bufferA.getArea()
                    .fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, false, false, false, false));
            assertFalse(bufferA.hasActiveSnippet());
            assertTrue(
                    bufferA.getArea().getText().contains("date"),
                    bufferA.getArea().getText());
        });
    }
}
