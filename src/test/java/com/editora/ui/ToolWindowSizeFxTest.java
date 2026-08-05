package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tool-window sizes are per window, not per side.
 *
 * <p>They used to be three numbers — one per side — shared by every window docked there, so sizing the
 * Project tree and then opening the Structure outline left both at whatever the last one was dragged to.
 * The per-side value survives as the default a never-opened window inherits, which is what keeps a first
 * open behaving as it always did; the tests below pin both halves of that.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToolWindowSizeFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private record Rig(ToolWindowManager manager, ToolWindow a, ToolWindow b, ConfigManager config, Scene scene) {
        /** A real layout pass. In the app one happens between {@code open} and the deferred divider set. */
        void layout() {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
        }
    }

    private static ToolWindow window(String id, Region content) {
        return new ToolWindow(id, id, ToolWindow.Side.RIGHT, () -> new Label("i"), content, "tool." + id);
    }

    private static Rig rig(Region contentA, Region contentB) throws Exception {
        Path dir = Files.createTempDirectory("editora-tw-size");
        SharedConfig shared = new SharedConfig(dir, false);
        shared.load();
        ConfigManager config = new ConfigManager(shared);
        BorderPane workspace = new BorderPane();
        ToolWindowManager m = new ToolWindowManager(workspace, new Label("editor"), config, new KeymapManager());
        ToolWindow a = window("alpha", contentA);
        ToolWindow b = window("beta", contentB);
        m.register(a);
        m.register(b);
        // A real scene so the split actually lays out — divider positions are meaningless without one.
        Scene scene = new Scene(workspace, 1200, 800);
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        return new Rig(m, a, b, config, scene);
    }

    /** What {@code open} would use for this window — its own remembered size, else its side's. */
    private static double dividerFor(ToolWindowManager m, ToolWindow tw) {
        return (double) FxTestSupport.call(
                m, "dividerFor", new Class[] {ToolWindow.class, ToolWindow.Side.class}, tw, ToolWindow.Side.RIGHT);
    }

    /**
     * Sizing one window must not resize the other.
     *
     * <p>Asserted on the stored state rather than the live divider: {@code open} sets the divider in a
     * {@code runLater}, so a live read here would be racing the very thing under test.
     */
    @Test
    void eachWindowRemembersItsOwnSize() throws Exception {
        Rig r = rig(new Label("a"), new Label("b"));
        FxTestSupport.runOnFx(() -> {
            SplitPane split = FxTestSupport.field(r.manager(), "hSplit");

            r.manager().open(r.a());
            split.setDividerPosition(0, 0.80);
            r.manager().close(r.a());

            r.manager().open(r.b());
            split.setDividerPosition(0, 0.55);
            r.manager().close(r.b());

            var sizes = r.config().getWorkspaceState().getToolWindowSizes();
            assertEquals(0.80, sizes.get("alpha"), 0.001, "alpha's own size was overwritten by beta's");
            assertEquals(0.55, sizes.get("beta"), 0.001);
            assertEquals(0.80, dividerFor(r.manager(), r.a()), 0.001, "reopening alpha should restore its size");
            assertEquals(0.55, dividerFor(r.manager(), r.b()), 0.001);
        });
    }

    /** A window opened for the first time still inherits its side's last-used size, as it always did. */
    @Test
    void aNeverOpenedWindowInheritsTheSideSize() throws Exception {
        Rig r = rig(new Label("a"), new Label("b"));
        FxTestSupport.runOnFx(() -> {
            SplitPane split = FxTestSupport.field(r.manager(), "hSplit");
            r.manager().open(r.a());
            split.setDividerPosition(0, 0.62);
            r.manager().close(r.a());

            assertEquals(
                    0.62,
                    r.config().getWorkspaceState().getRightDividerPosition(),
                    0.001,
                    "the side default should track the last window sized there");
            assertEquals(0.62, dividerFor(r.manager(), r.b()), 0.001, "beta has no size of its own yet");
        });
    }

    /**
     * The gate the fit hangs on: is anything inside the panel showing a horizontal scrollbar?
     *
     * <p>Only the gate is asserted here, not the resulting divider — a {@code SplitPane} settles a position
     * on a layout pulse, which a headless test does not get, so a divider read back here reports whatever
     * the previous pref-size layout left. The arithmetic the gate leads to is pinned by
     * {@code ToolWindowFitTest} instead.
     */
    @Test
    void overflowingContentIsDetectedAndFittingContentIsNot() throws Exception {
        Pane wide = new Pane();
        wide.setPrefSize(4000, 100);
        Rig overflowing = rig(new ScrollPane(wide), new Label("b"));
        Rig fitting = rig(new ScrollPane(new Label("narrow")), new Label("b"));
        FxTestSupport.runOnFx(() -> {
            assertTrue(overflows(overflowing), "content far wider than the panel should show a scrollbar");
            assertFalse(overflows(fitting), "content that fits must not be widened");
        });
    }

    private static boolean overflows(Rig r) {
        r.manager().open(r.a());
        r.layout();
        Region panel = FxTestSupport.<java.util.Map<ToolWindow, Region>>field(r.manager(), "panels")
                .get(r.a());
        return (boolean) FxTestSupport.call(r.manager(), "overflowsHorizontally", new Class[] {Region.class}, panel);
    }
}
