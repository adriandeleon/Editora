package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javafx.scene.control.Button;
import javafx.util.Duration;

import com.editora.editor.ControlNames;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The main toolbar's buttons are disabled while their command cannot act, by the answer the menus give, and
 * that includes a button added from the catalog; the debugger's toolbar is named again when the keymap changes;
 * and a tooltip made by the factory shows after the one delay.
 */
@Tag("fx")
class ToolbarStateFxTest {

    private static FxWindowFixture fx;

    @TempDir
    static Path dir;

    @BeforeAll
    static void window() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    static void close() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private static void awaitTab(Path file) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (FxTestSupport.callOnFx(() -> fx.controller.tabForPath(file)) == null) {
            assertTrue(System.nanoTime() < deadline, file + " opened");
            Thread.sleep(20);
        }
    }

    @Test
    void aToolbarButtonIsDisabledWhileItsCommandCannotAct() throws Exception {
        Path text = Files.writeString(dir.resolve("plain.txt"), "text\n");
        FxTestSupport.runOnFx(() -> {
            MainController c = fx.controller;
            c.closeAllTabs();
            c.registry.run("view.welcome");
            assertTrue(c.saveAsButton.isDisabled(), "Save As with no file in front");
            assertFalse(c.newButton.isDisabled());
            assertFalse(c.paletteButton.isDisabled());
            c.fileWorkflows.openPath(text);
        });
        awaitTab(text);
        FxTestSupport.runOnFx(() -> {
            MainController c = fx.controller;
            c.editorArea.select(c.tabForPath(text));
            c.refreshMenuEnablement();
            assertFalse(c.saveAsButton.isDisabled(), "a file in front: Save As can act");

            // A button added from the catalog follows its command too: Commit outside a repository.
            c.toolbarCoordinator.setLayout(List.of("file.new", "file.saveAs", "git.commit"));
        });
        FxTestSupport.runOnFx(() -> {}); // the toolbar is rebuilt a turn later, never inside the event that asked
        FxTestSupport.runOnFx(() -> {
            MainController c = fx.controller;
            Map<Button, String> tracked = FxTestSupport.field(c.toolbar, "commandButtons");
            Button commit = tracked.entrySet().stream()
                    .filter(e -> e.getValue().equals("git.commit") && e.getKey().getScene() != null)
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElseThrow();
            assertTrue(commit.isDisabled(), "Commit outside a repository");
            assertEquals(
                    !Chrome.paletteEnabled("git.commit", c.paletteGates(), c.paletteContext()),
                    commit.isDisabled(),
                    "the menus' answer");
            c.toolbarCoordinator.restoreDefault();
            c.closeAllTabs();
        });
    }

    @Test
    void theDebuggersToolbarIsNamedAgainWhenTheKeymapChanges() throws Exception {
        FxTestSupport.runOnFx(() -> {
            MainController c = fx.controller;
            fx.shared.getSettings().setDebugSupport(true);
            DebugPanel panel = c.debugCoordinator.panel();
            Button stepOver = FxTestSupport.field(panel, "stepOver");
            fx.shared.getSettings().setKeymap("emacs");
            fx.windowManager.reloadSharedKeymap();
            String emacs = stepOver.getTooltip().getText();
            assertNotNull(emacs);
            assertTrue(emacs.contains(c.keymap.displayChord("debug.stepOver")), emacs);

            fx.shared.getSettings().setKeymap("intellij");
            fx.windowManager.reloadSharedKeymap();
            String intellij = stepOver.getTooltip().getText();
            assertTrue(intellij.contains(c.keymap.displayChord("debug.stepOver")), intellij);
            assertFalse(intellij.equals(emacs), "the old keymap's chord is gone: " + intellij);
            assertEquals(intellij, stepOver.getAccessibleText(), "the tooltip and the accessible text are one name");

            fx.shared.getSettings().setKeymap("emacs");
            fx.windowManager.reloadSharedKeymap();
            fx.shared.getSettings().setDebugSupport(false);
        });
    }

    @Test
    void aTooltipFromTheFactoryShowsAfterTheOneDelay() throws Exception {
        FxTestSupport.runOnFx(() -> {
            assertEquals(Duration.millis(400), ControlNames.TOOLTIP_DELAY);
            assertEquals(
                    ControlNames.TOOLTIP_DELAY, ControlNames.tooltip("named").getShowDelay());
            assertEquals(ControlNames.TOOLTIP_DELAY, ControlNames.tooltip().getShowDelay());
            Button button = new Button();
            Icons.name(button, "A button");
            assertEquals(ControlNames.TOOLTIP_DELAY, button.getTooltip().getShowDelay());
            // A tab's and a status bar segment's tooltip, made where `new Tooltip` used to be.
            assertEquals(
                    ControlNames.TOOLTIP_DELAY,
                    TabHeaders.tooltip(Path.of("a.txt")).getShowDelay());
        });
    }
}
