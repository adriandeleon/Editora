package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import javafx.scene.Node;
import javafx.scene.control.MenuBar;
import javafx.scene.layout.HeaderBar;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import com.editora.Os;
import com.editora.config.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pauta WIN-03: with the settings a new install starts with, the main window on Windows and Linux is
 * {@code StageStyle.EXTENDED} with a {@link HeaderBar} that holds the menu bar; on macOS it is decorated and the
 * menu bar is the system one. Built through the production boot path on a fresh config folder, in the style
 * the settings ask for ({@link FxWindowFixture#createWithDefaultWindowStyle}; other test windows are decorated).
 */
@Tag("fx")
class ExtendedWindowDefaultFxTest {

    @AfterEach
    void reset() {
        Os.override(null);
    }

    /** What the window built with the default setting looks like on {@code os}. */
    private record Shell(StageStyle style, Node firstRow, Node menuBar, boolean systemMenuBar) {}

    private static Shell defaultWindowOn(Os.Kind os) throws Exception {
        FxTestSupport.bootToolkit();
        Os.override(os);
        Path dir = Files.createTempDirectory("editora-win03");
        try (FxWindowFixture fx = FxWindowFixture.createWithDefaultWindowStyle(dir, shared -> {})) {
            return FxTestSupport.callOnFx(() -> {
                Stage stage = FxTestSupport.field(fx.controller, "stage");
                VBox top = FxTestSupport.field(fx.controller, "topBox");
                MenuBar bar = FxTestSupport.<MainMenuBar>field(fx.controller, "menuBar")
                        .node();
                return new Shell(stage.getStyle(), top.getChildren().getFirst(), bar, bar.isUseSystemMenuBar());
            });
        }
    }

    @Test
    void theMenuBarIsInTheTitleBarByDefaultOnLinux() throws Exception {
        assertInTheTitleBar(defaultWindowOn(Os.Kind.LINUX));
    }

    @Test
    void theMenuBarIsInTheTitleBarByDefaultOnWindows() throws Exception {
        assertInTheTitleBar(defaultWindowOn(Os.Kind.WINDOWS));
    }

    @Test
    void onMacOsTheWindowIsDecoratedAndTheMenusAreTheSystemMenuBar() throws Exception {
        Shell mac = defaultWindowOn(Os.Kind.MAC);
        assertEquals(StageStyle.DECORATED, mac.style());
        assertFalse(mac.firstRow() instanceof HeaderBar, "no header, so no header holds menus");
        assertSame(mac.menuBar(), mac.firstRow());
        assertTrue(mac.systemMenuBar(), "the menus are in the system menu bar");
        assertTrue(new Settings().isExtendedWindow(), "the setting is on by default even here, and ignored");
    }

    private static void assertInTheTitleBar(Shell shell) {
        assertEquals(StageStyle.EXTENDED, shell.style());
        assertTrue(shell.firstRow() instanceof HeaderBar, "the window's first row is the header: " + shell.firstRow());
        assertSame(shell.menuBar(), ((HeaderBar) shell.firstRow()).getLeft(), "the header holds the menu bar");
        assertFalse(shell.systemMenuBar());
    }
}
