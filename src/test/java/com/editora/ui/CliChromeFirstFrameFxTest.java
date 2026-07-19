package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.TabPane;
import javafx.scene.control.ToolBar;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code --expert} / {@code --zen} / {@code --simple} must shape the chrome on the window's <b>first frame</b>.
 *
 * <p>They used to ride the deferred CLI-target runnable, which only fires once the pulse-paced session restore
 * has finished — so the window appeared in full chrome, sat there for the whole restore, and then visibly
 * stripped itself. The flash grew with the number of restored files (very obvious with a few tabs open).
 * These flags depend on nothing the restore produces, so they're applied in {@code buildWindow} before
 * {@code stage.show()}.
 *
 * <p>The assertions run inside the same FX runnable as the build (see
 * {@link FxWindowFixture#create(boolean, boolean, boolean, java.util.function.Consumer)}), so <em>no</em>
 * queued {@code Platform.runLater} has executed yet. Asserting after the window settles would pass even with
 * the old deferred code and catch nothing.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CliChromeFirstFrameFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** Chrome state sampled at build time, before any deferred startup work has run. */
    private record Chrome(boolean toolbarVisible, boolean statusBarVisible, boolean tabHeaderCollapsed) {}

    private Chrome firstFrame(boolean zen, boolean expert, boolean simple) throws Exception {
        List<Chrome> captured = new ArrayList<>();
        FxWindowFixture fx = FxWindowFixture.create(zen, expert, simple, controller -> {
            ToolBar toolBar = FxTestSupport.field(controller, "toolBar");
            javafx.scene.layout.Region statusBar = FxTestSupport.field(controller, "statusBar");
            TabPane tabPane = FxTestSupport.field(controller, "tabPane");
            captured.add(new Chrome(
                    toolBar.isVisible(),
                    statusBar.isVisible(),
                    tabPane.getStyleClass().contains("no-tab-header")));
        });
        try {
            return captured.get(0);
        } finally {
            fx.dispose();
        }
    }

    @Test
    void plainLaunchShowsFullChrome() throws Exception {
        Chrome c = firstFrame(false, false, false);
        assertTrue(c.toolbarVisible(), "baseline: a flagless launch has its toolbar");
        assertTrue(c.statusBarVisible(), "baseline: and its status bar");
        assertFalse(c.tabHeaderCollapsed(), "baseline: and its tab bar");
    }

    @Test
    void expertFlagIsAppliedBeforeTheWindowIsShown() throws Exception {
        Chrome c = firstFrame(false, true, false);
        assertFalse(c.toolbarVisible(), "--expert must hide the toolbar on the FIRST frame, not after restore");
        assertTrue(c.tabHeaderCollapsed(), "--expert must collapse the tab bar on the first frame");
        // Expert is the lighter focus mode: unlike Zen it deliberately KEEPS the status bar.
        assertTrue(c.statusBarVisible(), "--expert keeps the status bar");
    }

    @Test
    void zenFlagIsAppliedBeforeTheWindowIsShown() throws Exception {
        Chrome c = firstFrame(true, false, false);
        assertFalse(c.toolbarVisible(), "--zen must hide the toolbar on the first frame");
        assertFalse(c.statusBarVisible(), "--zen hides the status bar too (unlike --expert)");
        assertTrue(c.tabHeaderCollapsed(), "--zen must collapse the tab bar on the first frame");
    }

    @Test
    void simpleFlagIsAppliedBeforeTheWindowIsShown() throws Exception {
        Chrome c = firstFrame(false, false, true);
        // Simple mode keeps the toolbar (a curated subset of buttons) and the status bar, but strips the
        // tool stripe/breadcrumb — assert the mode itself is live rather than re-testing SimpleModeFxTest.
        assertTrue(c.toolbarVisible(), "--simple keeps a (reduced) toolbar");
        assertTrue(c.statusBarVisible(), "--simple keeps a (reduced) status bar");
    }

    @Test
    void simpleModeIsLiveOnTheFirstFrame() throws Exception {
        List<Boolean> active = new ArrayList<>();
        FxWindowFixture fx = FxWindowFixture.create(false, false, true, controller -> {
            active.add((Boolean) FxTestSupport.call(controller, "simpleModeActive", new Class<?>[] {}));
        });
        try {
            assertTrue(active.get(0), "--simple must be in effect before show(), not applied after restore");
        } finally {
            fx.dispose();
        }
    }
}
