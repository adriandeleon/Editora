package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A narrow window must hide toolbar <em>icons</em>, never the pinned tail.
 *
 * <p>A {@link ToolBar} overflows from its END, so while the project switcher and Settings were the last
 * items of one bar they were the first things a narrow window took away — leaving fourteen icons and no
 * way to switch project or open Settings, which is exactly backwards: every icon in the cluster is also in
 * the menus and the palette. The tail is therefore its own container beside the ToolBar.
 *
 * <p>Only a real layout pass at a real width shows this, hence the sized scene.
 */
@Tag("fx")
class ToolbarOverflowFxTest {

    /** Narrow enough that the icon cluster cannot fit, wide enough that the tail comfortably can. */
    private static final double NARROW = 620;

    /** The bar's own right inset, plus a pixel for layout snapping. */
    private static final double EDGE_SLACK = 8;

    private static FxWindowFixture fx;

    @BeforeAll
    static void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        Path dir = Files.createTempDirectory("editora-toolbar-overflow");
        Path file = dir.resolve("sample.md");
        Files.writeString(file, "# Hello\n");
        fx = FxWindowFixture.create(
                dir, false, false, false, List.of(new MainController.OpenTarget(file, 1, 1)), true, c -> {});
        FxTestSupport.runOnFx(() -> {
            Scene scene = FxTestSupport.<Stage>field(fx.controller, "stage").getScene();
            scene.getRoot().resize(NARROW, 700);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
        });
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    /** The precondition the rest of this class rests on: at {@link #NARROW} the cluster really did overflow. */
    @Test
    void theIconClusterOverflowsAtThisWidth() throws Exception {
        assertTrue(
                FxTestSupport.callOnFx(() -> {
                    ToolBar bar = FxTestSupport.field(fx.controller, "toolBar");
                    Node overflow = bar.lookup(".tool-bar-overflow-button");
                    return overflow != null && overflow.isVisible();
                }),
                "the ToolBar should be showing its overflow chevron at " + NARROW + "px");
    }

    @Test
    void settingsStaysFlushWithTheBarsRightEdge() throws Exception {
        double[] probe = FxTestSupport.callOnFx(() -> {
            HBox row = FxTestSupport.field(fx.controller, "toolbarRow");
            Button settings = FxTestSupport.field(fx.controller, "settingsButton");
            return new double[] {
                row.localToScene(row.getBoundsInLocal()).getMaxX(),
                settings.localToScene(settings.getBoundsInLocal()).getMaxX(),
            };
        });
        // Position, not ancestry or width: a ToolBar leaves an overflowed item parented and sized, simply
        // stranded at wherever it last laid out — measured at x=297 of a 620px bar, against 619 when pinned.
        // So "is it still in the scene graph" says nothing; "is it still at the right edge" is the invariant.
        assertTrue(
                probe[0] - probe[1] <= EDGE_SLACK,
                "Settings should sit at the bar's right edge, not be pushed into the overflow menu: " + probe[1]
                        + " vs an edge at " + probe[0]);
    }

    @Test
    void theProjectSwitcherSitsInThePinnedRegion() throws Exception {
        double[] probe = FxTestSupport.callOnFx(() -> {
            ToolBar bar = FxTestSupport.field(fx.controller, "toolBar");
            ProjectCombo combo = FxTestSupport.field(fx.controller, "toolbarProjectCombo");
            return new double[] {
                bar.localToScene(bar.getBoundsInLocal()).getMaxX(),
                combo.localToScene(combo.getBoundsInLocal()).getMinX(),
            };
        });
        assertTrue(
                probe[1] >= probe[0],
                "the project switcher belongs to the pinned tail, past the overflowing cluster: " + probe[1]
                        + " vs a cluster ending at " + probe[0]);
    }
}
