package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;

import com.editora.command.KeymapManager;
import com.editora.config.ConfigManager;
import com.editora.config.SharedConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Maximizing a tool window expands it over its split and hands the space back on restore.
 *
 * <p>The cases that need a real scene are the ones a pure test cannot reach: a {@code SplitPane} honours
 * its items' min sizes, so "the divider actually reaches the end" is a layout fact; and the interaction
 * between a maximize and the <em>remembered size</em> only exists once real dividers are being read back.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToolWindowMaximizeFxTest {

    /** The stand-in editor's min width/height, in px — see {@link #rig()}. */
    private static final double EDITOR_MIN = 200;
    /**
     * Tolerance for "the divider reached the end". It cannot land exactly on 0 or 1: the divider itself
     * has width, so a fully collapsed neighbour still leaves a couple of pixels of the split behind it.
     */
    private static final double AT_END = 0.02;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private record Rig(
            ToolWindowManager manager, ToolWindow right, ToolWindow bottom, ConfigManager config, Scene scene) {
        void layout() {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
        }

        SplitPane hSplit() {
            return FxTestSupport.field(manager, "hSplit");
        }

        SplitPane vSplit() {
            return FxTestSupport.field(manager, "vSplit");
        }
    }

    private static ToolWindow window(String id, ToolWindow.Side side) {
        return new ToolWindow(id, id, side, () -> new Label("i"), new Label(id), "tool." + id);
    }

    private static Rig rig() throws Exception {
        Path dir = Files.createTempDirectory("editora-tw-max");
        SharedConfig shared = new SharedConfig(dir, false);
        shared.load();
        ConfigManager config = new ConfigManager(shared);
        BorderPane workspace = new BorderPane();
        // An explicit min size, so the editor resists being squeezed exactly as the real one does: without
        // zeroing it a "maximize" would stop at EDITOR_MIN, which is what these tests are here to catch.
        // Stated rather than derived from content — a computed min silently clamps the positions the tests
        // set up, which reads as a maximize bug when it is really the rig.
        Region editor = new Region();
        editor.setMinSize(EDITOR_MIN, EDITOR_MIN);
        ToolWindowManager m = new ToolWindowManager(workspace, editor, config, new KeymapManager());
        ToolWindow right = window("alpha", ToolWindow.Side.RIGHT);
        ToolWindow bottom = window("beta", ToolWindow.Side.BOTTOM);
        m.register(right);
        m.register(bottom);
        Scene scene = new Scene(workspace, 1200, 800);
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        return new Rig(m, right, bottom, config, scene);
    }

    /** Opens a window and parks its divider where the "user" left it, past the deferred set in {@code open}. */
    private static void openAt(Rig r, ToolWindow tw, SplitPane split, double position) {
        r.manager().open(tw);
        r.layout();
        split.setDividerPosition(0, position);
        r.layout();
    }

    @Test
    void maximizingGivesTheWindowTheWholeSplit() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> {
            SplitPane split = r.hSplit();
            openAt(r, r.right(), split, 0.70);

            r.manager().toggleMaximized(r.right());
            r.layout();

            assertTrue(r.manager().isMaximized(r.right()));
            assertEquals(
                    0.0,
                    split.getDividerPositions()[0],
                    AT_END,
                    "the editor's min width blocked the maximize — the other items' minimums must be zeroed");
        });
    }

    @Test
    void restoringHandsTheSpaceBack() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> {
            SplitPane split = r.hSplit();
            openAt(r, r.right(), split, 0.70);

            r.manager().toggleMaximized(r.right());
            r.layout();
            r.manager().toggleMaximized(r.right());
            r.layout();

            assertFalse(r.manager().isMaximized(r.right()));
            assertEquals(0.70, split.getDividerPositions()[0], 0.001);
        });
    }

    /** The bottom window lives in the vertical split, so maximizing it must drive that one. */
    @Test
    void aBottomWindowMaximizesOverItsOwnSplit() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> {
            SplitPane split = r.vSplit();
            openAt(r, r.bottom(), split, 0.60);

            r.manager().toggleMaximized(r.bottom());
            r.layout();

            assertEquals(0.0, split.getDividerPositions()[0], AT_END);
        });
    }

    /**
     * Closing while maximized must remember the size the user chose, not the maximized extreme.
     *
     * <p>This is the regression that makes maximizing dangerous rather than merely cosmetic: {@code close}
     * writes the live divider into the session as the window's size, so without un-maximizing first the
     * window would reopen next session covering the editor — permanently, since reopening it that way
     * would just re-persist the same value.
     */
    @Test
    void closingWhileMaximizedRemembersThePreMaximizeSize() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> {
            openAt(r, r.right(), r.hSplit(), 0.70);

            r.manager().toggleMaximized(r.right());
            r.layout();
            r.manager().close(r.right());

            assertEquals(
                    0.70,
                    r.config().getWorkspaceState().getToolWindowSizes().get("alpha"),
                    0.001,
                    "the maximized divider was persisted as the window's size");
            assertFalse(r.manager().isMaximized(r.right()));
        });
    }

    @Test
    void reopeningAClosedMaximizedWindowMaximizesItAgain() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> {
            openAt(r, r.right(), r.hSplit(), 0.70);
            r.manager().toggleMaximized(r.right());
            r.layout();
            r.manager().close(r.right());

            r.manager().open(r.right());
            r.layout();

            assertTrue(r.manager().isMaximized(r.right()));
            assertEquals(
                    "MAXIMIZED",
                    r.config()
                            .getWorkspaceState()
                            .getToolWindowPresentationModes()
                            .get("alpha"));
            assertEquals(0.0, r.hSplit().getDividerPositions()[0], AT_END);
        });
    }

    @Test
    void explicitlyRestoringMakesDockedTheRememberedPresentation() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> {
            openAt(r, r.right(), r.hSplit(), 0.70);
            r.manager().toggleMaximized(r.right());
            r.manager().toggleMaximized(r.right());
            r.manager().close(r.right());

            r.manager().open(r.right());
            r.layout();

            assertFalse(r.manager().isMaximized(r.right()));
            assertEquals(
                    "DOCKED",
                    r.config()
                            .getWorkspaceState()
                            .getToolWindowPresentationModes()
                            .get("alpha"));
        });
    }

    @Test
    void sessionRestoreAppliesMaximizeAfterOpeningTheOtherSides() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> {
            r.config().getWorkspaceState().getOpenToolWindows().put("RIGHT", List.of("alpha"));
            r.config().getWorkspaceState().getOpenToolWindows().put("BOTTOM", List.of("beta"));
            r.config().getWorkspaceState().getToolWindowPresentationModes().put("alpha", "MAXIMIZED");

            r.manager().restore();
            r.layout();

            assertTrue(r.manager().isOpen(r.right()));
            assertTrue(r.manager().isOpen(r.bottom()));
            assertTrue(r.manager().isMaximized(r.right()));
        });
    }

    /** Same trap on the quit path, which captures dividers for windows left open. */
    @Test
    void persistingDividersWhileMaximizedRecordsThePreMaximizeSize() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> {
            openAt(r, r.right(), r.hSplit(), 0.65);

            r.manager().toggleMaximized(r.right());
            r.layout();
            r.manager().persistDividers();

            assertEquals(
                    0.65, r.config().getWorkspaceState().getToolWindowSizes().get("alpha"), 0.001);
        });
    }

    /**
     * Opening a second window cancels the maximize: it changes the split's contents, so the divider
     * indices the saved positions were captured against no longer describe the same layout.
     */
    @Test
    void openingAnotherWindowCancelsTheMaximize() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> {
            openAt(r, r.right(), r.hSplit(), 0.70);
            r.manager().toggleMaximized(r.right());
            r.layout();

            r.manager().open(r.bottom());
            r.layout();

            assertFalse(r.manager().isMaximized(r.right()), "the maximize outlived the layout it was captured in");
        });
    }

    /** With one window open and focus elsewhere, the command still has an unambiguous target. */
    @Test
    void theOnlyOpenWindowIsTheMaximizeTarget() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> {
            r.manager().open(r.right());
            r.layout();
            assertSame(r.right(), r.manager().maximizeTarget());

            r.manager().open(r.bottom());
            r.layout();
            org.junit.jupiter.api.Assertions.assertNull(
                    r.manager().maximizeTarget(), "two open windows have no unambiguous target");
        });
    }

    /** Closing a window that was never open must not disturb another window's maximize. */
    @Test
    void closingAnAlreadyClosedWindowLeavesTheMaximizeAlone() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> {
            openAt(r, r.right(), r.hSplit(), 0.70);
            r.manager().toggleMaximized(r.right());
            r.layout();

            r.manager().close(r.bottom()); // never opened

            assertTrue(r.manager().isMaximized(r.right()));
        });
    }
}
