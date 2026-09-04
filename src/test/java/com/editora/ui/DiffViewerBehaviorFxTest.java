package com.editora.ui;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Control;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.diff.DiffEngine;
import com.editora.diff.DiffModels;
import com.editora.diff.DiffService;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fx")
class DiffViewerBehaviorFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void collapsesLongUnchangedRunsByDefault() throws Exception {
        StringBuilder left = new StringBuilder();
        StringBuilder right = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            left.append(i == 15 ? "old" : "line-" + i).append('\n');
            right.append(i == 15 ? "new" : "line-" + i).append('\n');
        }
        DiffViewerPane pane = pane(left.toString(), right.toString());
        CodeArea area = FxTestSupport.field(pane, "leftArea");
        assertTrue(area.getText().contains("unchanged lines"), area.getText());
        assertTrue(area.getParagraphs().size() < 30);
    }

    @Test
    void hunkApplyPreservesEditableSidesCrLfAndFinalNewline() throws Exception {
        String left = "one\r\nsource\r\n";
        String right = "one\r\ntarget\r\n";
        DiffViewerPane pane = pane(left, right);
        AtomicReference<String> applied = new AtomicReference<>();
        FxTestSupport.runOnFx(
                () -> pane.setEditable(DiffViewerPane.EditableSide.RIGHT, applied::set, () -> {}, () -> {}));
        FxTestSupport.runOnFx(() -> FxTestSupport.call(pane, "applyRow", new Class<?>[] {int.class}, 1));
        assertEquals("one\r\nsource\r\n", applied.get());
    }

    @Test
    void leftEditableApplyControlsStayAtCenterSeam() throws Exception {
        DiffViewerPane pane = pane("same\nold one\nold two\nsame\n", "same\nnew one\nnew two\nsame\n");
        FxTestSupport.runOnFx(() -> pane.setEditable(DiffViewerPane.EditableSide.LEFT, text -> {}, () -> {}, () -> {}));
        Stage stage = FxTestSupport.callOnFx(() -> {
            Stage window = new Stage();
            window.setScene(new Scene(new StackPane(pane.node()), 900, 560));
            window.show();
            window.getScene().getRoot().applyCss();
            window.getScene().getRoot().layout();
            return window;
        });
        try {
            CodeArea left = FxTestSupport.field(pane, "leftArea");
            CodeArea right = FxTestSupport.field(pane, "rightArea");
            assertTrue(left.lookupAll(".diff-apply").isEmpty(), "actions leaked to the outer-left gutter");
            assertTrue(!right.lookupAll(".diff-apply").isEmpty(), "center-seam action gutter is empty");
        } finally {
            FxTestSupport.runOnFx(stage::close);
        }
    }

    @Test
    void largeDiffsDegradeThroughLineOnlyAndMetadataModes() throws Exception {
        DiffService service = new DiffService();
        try {
            String lineOnlyLeft = "same\n".repeat(60_001);
            assertEquals(
                    DiffModels.Quality.LINE_ONLY,
                    compute(service, lineOnlyLeft, lineOnlyLeft + "added\n").quality());

            String metadataLeft = "same\n".repeat(120_001);
            assertEquals(
                    DiffModels.Quality.METADATA_ONLY,
                    compute(service, metadataLeft, metadataLeft + "added\n").quality());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void sideBySideConnectorPaintsVisibleMouseTransparentRibbons() throws Exception {
        DiffViewerPane pane = pane(
                "context\nremoved one\nremoved two\ncontext\nold tail\n", "context\nadded\ncontext\nnew tail\nextra\n");
        Stage stage = FxTestSupport.callOnFx(() -> {
            Stage window = new Stage();
            window.setScene(new Scene(new StackPane(pane.node()), 900, 560));
            window.show();
            return window;
        });
        try {
            FxTestSupport.runOnFx(() -> {});
            Canvas canvas = FxTestSupport.field(pane, "connectorCanvas");
            WritableImage image = FxTestSupport.callOnFx(() -> canvas.snapshot(null, null));
            assertTrue(canvas.isMouseTransparent());
            assertTrue(nonTransparentPixels(image) > 20, "the laid-out connector canvas remained empty");
        } finally {
            FxTestSupport.runOnFx(stage::close);
        }
    }

    @Test
    void toolbarControlsHaveDescriptiveTooltips() throws Exception {
        DiffViewerPane pane = pane("old\n", "new\n");
        FxTestSupport.runOnFx(() -> pane.setOnSwapRequested((left, right) -> {}));
        Control whitespace = FxTestSupport.field(pane, "whitespaceButton");
        Control words = FxTestSupport.field(pane, "wordButton");
        Control rules = FxTestSupport.field(pane, "rulesButton");
        Control context = FxTestSupport.field(pane, "contextButton");
        Control wrap = FxTestSupport.field(pane, "wrapButton");
        Control layout = FxTestSupport.field(pane, "toggleButton");
        Control export = FxTestSupport.field(pane, "exportButton");
        Control swap = FxTestSupport.field(pane, "swapButton");

        for (Control control : java.util.List.of(whitespace, words, rules, context, wrap, layout, swap, export)) {
            assertNotNull(control.getTooltip());
            assertTrue(
                    control.getTooltip().getText().length() > 20,
                    control.getTooltip().getText());
            assertTrue(control.getTooltip().isWrapText());
        }

        String exactDescription = whitespace.getTooltip().getText();
        FxTestSupport.runOnFx(() -> ((javafx.scene.control.Button) whitespace).fire());
        assertNotEquals(exactDescription, whitespace.getTooltip().getText(), "whitespace help follows its mode");
    }

    @Test
    void comparisonRuleCommandsPreserveTheOtherOptions() throws Exception {
        DiffViewerPane pane = pane("OLD value\n", "new value\n");
        AtomicReference<DiffEngine.DiffOptions> changed = new AtomicReference<>();
        DiffEngine.DiffOptions initial = new DiffEngine.DiffOptions(DiffEngine.WhitespaceMode.TRIM, false, false, true);
        FxTestSupport.runOnFx(() -> {
            pane.setOptions(initial);
            pane.setOnOptionsChanged(changed::set);
            pane.toggleIgnoreCase();
        });

        assertNotNull(changed.get());
        assertTrue(changed.get().ignoreCase());
        assertTrue(changed.get().smartAlignment());
        assertEquals(DiffEngine.WhitespaceMode.TRIM, changed.get().whitespace());
        assertFalse(changed.get().wordLevel());

        FxTestSupport.runOnFx(pane::toggleSmartAlignment);
        assertFalse(changed.get().smartAlignment());
        assertTrue(changed.get().ignoreCase());
        assertEquals(DiffEngine.WhitespaceMode.TRIM, changed.get().whitespace());
    }

    @Test
    void editableResultIsDebouncedDraftAndAppliesAsOneEdit() throws Exception {
        String left = "one\nreference\n";
        String right = "one\nworking\n";
        String draft = "one\nhand edited\nextra\n";
        DiffViewerPane pane = pane(left, right);
        AtomicReference<String> applied = new AtomicReference<>();
        AtomicReference<String> recomputed = new AtomicReference<>();
        CountDownLatch changed = new CountDownLatch(1);
        FxTestSupport.runOnFx(() -> {
            pane.setEditable(
                    DiffViewerPane.EditableSide.RIGHT,
                    text -> {
                        applied.set(text);
                        return true;
                    },
                    () -> {},
                    () -> {});
            pane.setOnResultEdited(text -> {
                recomputed.set(text);
                pane.updateDraftContent(left, text, DiffEngine.compute(left, text, DiffEngine.DiffOptions.DEFAULT));
                changed.countDown();
            });
            ((javafx.scene.control.ToggleButton) FxTestSupport.field(pane, "editResultButton")).fire();
            ((CodeArea) FxTestSupport.field(pane, "resultArea")).replaceText(draft);
        });

        assertTrue(changed.await(2, TimeUnit.SECONDS), "draft did not trigger its debounced re-diff");
        assertEquals(draft, recomputed.get());
        assertTrue(pane.hasDirtyResult());
        assertTrue(pane.matchesEditableText(right), "the stale guard must retain the pre-draft baseline");
        assertEquals(null, applied.get(), "typing in the draft must not mutate the local file");
        CodeArea resultArea = FxTestSupport.field(pane, "resultArea");
        javafx.scene.control.SplitPane resultSplit = FxTestSupport.field(pane, "resultSplit");
        assertTrue(resultArea.isEditable());
        assertEquals(javafx.geometry.Orientation.VERTICAL, resultSplit.getOrientation());
        assertTrue(((javafx.scene.control.Button) FxTestSupport.field(pane, "applyAllButton")).isDisable());

        FxTestSupport.runOnFx(
                () -> ((javafx.scene.control.Button) FxTestSupport.field(pane, "applyResultButton")).fire());
        assertEquals(draft, applied.get());
        assertFalse(pane.hasDirtyResult());
        assertTrue(pane.matchesEditableText(draft));
    }

    @Test
    void readOnlyDiffDoesNotOfferEditableResult() throws Exception {
        DiffViewerPane pane = pane("old\n", "new\n");
        javafx.scene.control.ToggleButton button = FxTestSupport.field(pane, "editResultButton");
        assertFalse(button.isVisible());
        FxTestSupport.runOnFx(pane::toggleResultEditing);
        assertFalse(pane.hasResultEditor());
    }

    @Test
    void swapMovesContentLabelsAndEditableSideTogether() throws Exception {
        String reference = "one\nreference\n";
        String working = "one\nworking\n";
        DiffViewerPane pane = pane(reference, working);
        AtomicReference<String> applied = new AtomicReference<>();
        FxTestSupport.runOnFx(() -> {
            pane.setEditable(
                    DiffViewerPane.EditableSide.RIGHT,
                    text -> {
                        applied.set(text);
                        return true;
                    },
                    () -> {},
                    () -> {});
            pane.setOnSwapRequested((newLeft, newRight) ->
                    pane.swapSides(DiffEngine.compute(newLeft, newRight, DiffEngine.DiffOptions.DEFAULT)));
            pane.swapComparisonSides();
        });

        assertEquals(DiffViewerPane.EditableSide.LEFT, pane.editableSide());
        assertEquals(working, FxTestSupport.field(pane, "leftText"));
        assertEquals(reference, FxTestSupport.field(pane, "rightText"));
        assertEquals("right", FxTestSupport.field(pane, "headerLeft"));
        assertEquals("left", FxTestSupport.field(pane, "headerRight"));
        FxTestSupport.runOnFx(() -> FxTestSupport.call(pane, "applyRow", new Class<?>[] {int.class}, 1));
        assertEquals(reference, applied.get(), "apply still targets the same local file after swapping");
    }

    @Test
    void dirtyResultDraftPreventsSideSwap() throws Exception {
        DiffViewerPane pane = pane("reference\n", "working\n");
        AtomicInteger swaps = new AtomicInteger();
        FxTestSupport.runOnFx(() -> {
            pane.setEditable(DiffViewerPane.EditableSide.RIGHT, text -> true, () -> {}, () -> {});
            pane.setOnResultEdited(text -> {});
            pane.setOnSwapRequested((newLeft, newRight) -> swaps.incrementAndGet());
            pane.toggleResultEditing();
            ((CodeArea) FxTestSupport.field(pane, "resultArea")).replaceText("draft\n");
            pane.swapComparisonSides();
        });

        assertTrue(pane.hasDirtyResult());
        assertTrue(((javafx.scene.control.Button) FxTestSupport.field(pane, "swapButton")).isDisable());
        assertEquals(0, swaps.get());
        assertEquals(DiffViewerPane.EditableSide.RIGHT, pane.editableSide());
    }

    @Test
    void swappedGitActionsStillMutateTheirSemanticSide() throws Exception {
        String index = "one\nindex\n";
        String working = "one\nworking\n";
        DiffViewerPane pane = pane(index, working);
        AtomicReference<DiffViewerPane.GitHunkRequest> request = new AtomicReference<>();
        FxTestSupport.runOnFx(() -> {
            pane.setGitHunkActions(java.util.Set.of(DiffViewerPane.GitHunkAction.STAGE), request::set);
            pane.setOnSwapRequested((newLeft, newRight) ->
                    pane.swapSides(DiffEngine.compute(newLeft, newRight, DiffEngine.DiffOptions.DEFAULT)));
            pane.swapComparisonSides();
            pane.stageCurrentHunk();
        });

        assertNotNull(request.get());
        assertEquals(index, request.get().beforeText(), "stage must still target the index after swapping");
        assertEquals(working, request.get().afterText());
    }

    private static int nonTransparentPixels(WritableImage image) {
        int count = 0;
        for (int y = 0; y < (int) image.getHeight(); y++) {
            for (int x = 0; x < (int) image.getWidth(); x++) {
                if (image.getPixelReader().getArgb(x, y) >>> 24 != 0) {
                    count++;
                }
            }
        }
        return count;
    }

    private static DiffModels.DiffModel compute(DiffService service, String left, String right)
            throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<DiffModels.DiffModel> result = new AtomicReference<>();
        service.compute(left, right, DiffEngine.DiffOptions.DEFAULT, model -> {
            result.set(model);
            latch.countDown();
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        return result.get();
    }

    private static DiffViewerPane pane(String left, String right) throws Exception {
        var model = DiffEngine.compute(left, right, DiffEngine.DiffOptions.DEFAULT);
        return FxTestSupport.callOnFx(() -> new DiffViewerPane(
                "diff", "left", "right", "x.txt", "x.txt", left, right, model, "Monospaced", 13, true, "x.txt"));
    }
}
