package com.editora.ui;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.diff.DiffEngine;
import com.editora.diff.DiffModels;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The diff viewer's unified view, change navigation, per-hunk menu and toolbar options: what each shows and
 * what it hands to the controller.
 */
@Tag("fx")
class DiffViewerUnifiedAndNavigationFxTest {

    /** Two separate change blocks: line 2 modified, and one line added near the end. */
    private static final String LEFT = "one\nold two\nthree\nfour\nfive\n";

    private static final String RIGHT = "one\nnew two\nthree\nfour\nadded\nfive\n";

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void unifiedViewListsRemovedThenAddedLinesWithTheirStyles() throws Exception {
        DiffViewerPane pane = pane(LEFT, RIGHT);

        FxTestSupport.runOnFx(pane::toggleViewMode);

        CodeArea unified = FxTestSupport.field(pane, "unifiedArea");
        assertEquals("one\nold two\nnew two\nthree\nfour\nadded\nfive", FxTestSupport.callOnFx(unified::getText));
        assertEquals(
                List.of("diff-removed"),
                FxTestSupport.callOnFx(() -> List.copyOf(unified.getParagraph(1).getParagraphStyle())));
        assertEquals(
                List.of("diff-added"),
                FxTestSupport.callOnFx(() -> List.copyOf(unified.getParagraph(2).getParagraphStyle())));
        assertEquals(
                List.of("diff-added"),
                FxTestSupport.callOnFx(() -> List.copyOf(unified.getParagraph(5).getParagraphStyle())));
        assertTrue(FxTestSupport.callOnFx(
                () -> unified.getParagraph(0).getParagraphStyle().isEmpty()));
        assertSame(
                unified.getParent(),
                FxTestSupport.callOnFx(() -> ((javafx.scene.layout.BorderPane) pane.node()).getCenter()),
                "the unified area replaces the two panes");
        ToggleableLabels toggle = toggleLabels(pane);
        assertEquals(tr("diff.viewSideBySide"), toggle.accessible(), "the button now offers the way back");

        // And back: the two panes return, and the toolbar cluster is aligned to the left pane again.
        FxTestSupport.runOnFx(pane::toggleViewMode);
        CodeArea left = FxTestSupport.field(pane, "leftArea");
        assertNotNull(left);
        assertEquals(tr("diff.viewUnified"), toggleLabels(pane).accessible());
        javafx.beans.property.DoubleProperty leftPaneWidth = FxTestSupport.field(pane, "leftPaneWidth");
        assertTrue(FxTestSupport.callOnFx(leftPaneWidth::isBound));
    }

    @Test
    void unifiedViewCollapsesLongUnchangedRunsAndTheContextButtonExpandsThem() throws Exception {
        StringBuilder left = new StringBuilder();
        StringBuilder right = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            left.append(i == 20 ? "old" : "line-" + i).append('\n');
            right.append(i == 20 ? "new" : "line-" + i).append('\n');
        }
        DiffViewerPane pane = pane(left.toString(), right.toString());
        ToggleButton context = FxTestSupport.field(pane, "contextButton");
        boolean collapsedBefore = FxTestSupport.callOnFx(context::isSelected);
        try {
            FxTestSupport.runOnFx(() -> {
                if (!context.isSelected()) {
                    context.fire();
                }
                pane.toggleViewMode();
            });
            CodeArea collapsed = FxTestSupport.field(pane, "unifiedArea");
            String text = FxTestSupport.callOnFx(collapsed::getText);
            // 20 unchanged lines before the change: 3 kept beside it, 17 folded; 19 after: 3 kept, 16 folded.
            assertEquals(
                    List.of(
                            tr("diff.unchangedLines", 17),
                            "line-17",
                            "line-18",
                            "line-19",
                            "old",
                            "new",
                            "line-21",
                            "line-22",
                            "line-23",
                            tr("diff.unchangedLines", 16)),
                    text.lines().toList());
            assertEquals(
                    List.of("diff-collapsed"),
                    FxTestSupport.callOnFx(
                            () -> List.copyOf(collapsed.getParagraph(0).getParagraphStyle())));

            FxTestSupport.runOnFx(context::fire);
            CodeArea expanded = FxTestSupport.field(pane, "unifiedArea");
            assertEquals(
                    41, FxTestSupport.callOnFx(() -> expanded.getParagraphs().size()), "every line, plus one");
            assertFalse(FxTestSupport.callOnFx(expanded::getText).contains("unchanged lines"));
        } finally {
            FxTestSupport.runOnFx(() -> {
                if (context.isSelected() != collapsedBefore) {
                    context.fire(); // the choice is remembered for the next diff: put it back
                }
            });
        }
    }

    @Test
    void nextAndPreviousStepThroughTheChangeBlocksAndStopAtTheEnds() throws Exception {
        DiffViewerPane pane = pane(LEFT, RIGHT);
        Label nav = FxTestSupport.field(pane, "changeNav");
        assertEquals(tr("diff.changeCount", 2), FxTestSupport.callOnFx(nav::getText));

        FxTestSupport.runOnFx(pane::goNextChange);
        assertEquals(tr("diff.changePos", 1, 2), FxTestSupport.callOnFx(nav::getText));
        CodeArea left = FxTestSupport.field(pane, "leftArea");
        CodeArea right = FxTestSupport.field(pane, "rightArea");
        assertEquals("old two", FxTestSupport.callOnFx(left::getSelectedText), "the block is highlighted");
        assertEquals("new two", FxTestSupport.callOnFx(right::getSelectedText));

        FxTestSupport.runOnFx(pane::goNextChange);
        assertEquals(tr("diff.changePos", 2, 2), FxTestSupport.callOnFx(nav::getText));
        assertEquals("added", FxTestSupport.callOnFx(right::getSelectedText));
        assertEquals(4, FxTestSupport.callOnFx(right::getCurrentParagraph), "the caret is at the block's top");

        FxTestSupport.runOnFx(pane::goNextChange);
        assertEquals(tr("diff.changePos", 2, 2), FxTestSupport.callOnFx(nav::getText), "no wrap past the last");

        FxTestSupport.runOnFx(pane::goPreviousChange);
        FxTestSupport.runOnFx(pane::goPreviousChange);
        assertEquals(tr("diff.changePos", 1, 2), FxTestSupport.callOnFx(nav::getText), "nor before the first");
        assertEquals("new two", FxTestSupport.callOnFx(right::getSelectedText));
        FxTestSupport.runOnFx(() -> {}); // the deferred scroll pin
    }

    @Test
    void navigationInUnifiedViewSelectsBothLinesOfAModifiedRow() throws Exception {
        DiffViewerPane pane = pane(LEFT, RIGHT);
        FxTestSupport.runOnFx(() -> {
            pane.toggleViewMode();
            pane.goNextChange();
        });
        CodeArea unified = FxTestSupport.field(pane, "unifiedArea");
        assertEquals("old two\nnew two", FxTestSupport.callOnFx(unified::getSelectedText));

        FxTestSupport.runOnFx(pane::goNextChange);
        assertEquals("added", FxTestSupport.callOnFx(unified::getSelectedText));
        FxTestSupport.runOnFx(() -> {});
    }

    @Test
    void identicalSidesHaveNothingToStepThrough() throws Exception {
        DiffViewerPane pane = pane("same\n", "same\n");
        Label nav = FxTestSupport.field(pane, "changeNav");

        FxTestSupport.runOnFx(() -> {
            pane.goNextChange();
            pane.goPreviousChange();
            pane.copyCurrentHunk();
        });

        assertEquals(tr("diff.changeCount", 0), FxTestSupport.callOnFx(nav::getText));
    }

    @Test
    void aSingleChangeIsCountedInTheSingular() throws Exception {
        DiffViewerPane pane = pane("a\n", "b\n");
        Label nav = FxTestSupport.field(pane, "changeNav");
        assertEquals(tr("diff.changeCount.one"), FxTestSupport.callOnFx(nav::getText));
    }

    /** {@code n}/{@code p} step through a read-only diff, but are plain typing keys next to an editable side. */
    @Test
    void plainNAndPNavigateOnlyAReadOnlyDiff() throws Exception {
        DiffViewerPane pane = pane(LEFT, RIGHT);
        Label nav = FxTestSupport.field(pane, "changeNav");

        assertTrue(press(pane, KeyCode.N, false), "n is taken");
        assertEquals(tr("diff.changePos", 1, 2), FxTestSupport.callOnFx(nav::getText));
        assertTrue(press(pane, KeyCode.N, false));
        assertTrue(press(pane, KeyCode.P, false), "p is taken");
        assertEquals(tr("diff.changePos", 1, 2), FxTestSupport.callOnFx(nav::getText));

        assertFalse(press(pane, KeyCode.N, true), "with a modifier the key is left to the text area");
        assertFalse(press(pane, KeyCode.X, false), "another key is not the viewer's");
        assertEquals(tr("diff.changePos", 1, 2), FxTestSupport.callOnFx(nav::getText));

        FxTestSupport.runOnFx(
                () -> pane.setEditable(DiffViewerPane.EditableSide.RIGHT, text -> {}, () -> {}, () -> {}));
        assertFalse(press(pane, KeyCode.N, false), "an editable diff keeps n for typing");
        assertEquals(tr("diff.changePos", 1, 2), FxTestSupport.callOnFx(nav::getText));
    }

    @Test
    void copyHunkPutsTheCurrentBlockOnTheClipboardAsRemovedAndAddedLines() throws Exception {
        DiffViewerPane pane = pane(LEFT, RIGHT);

        FxTestSupport.runOnFx(pane::copyCurrentHunk);
        assertEquals(
                "-old two\n+new two\n",
                FxTestSupport.callOnFx(() -> Clipboard.getSystemClipboard().getString()),
                "before any navigation the first block is the current one");

        FxTestSupport.runOnFx(() -> {
            pane.goNextChange();
            pane.goNextChange();
            pane.copyCurrentHunk();
        });
        assertEquals(
                "+added\n",
                FxTestSupport.callOnFx(() -> Clipboard.getSystemClipboard().getString()));
        FxTestSupport.runOnFx(() -> {});
    }

    @Test
    void theHunkMenuOffersOnlyTheActionsTheDiffSupportsAndRunsThemOnTheClickedBlock() throws Exception {
        DiffViewerPane pane = pane("old first\nsame\n", "new first\nsame\n");
        List<DiffViewerPane.GitHunkRequest> requests = new ArrayList<>();
        FxTestSupport.runOnFx(() -> pane.setGitHunkActions(
                EnumSet.of(DiffViewerPane.GitHunkAction.STAGE, DiffViewerPane.GitHunkAction.OPEN), requests::add));
        Stage stage = show(pane);
        try {
            CodeArea left = FxTestSupport.field(pane, "leftArea");
            ContextMenu menu = contextMenuAt(left, 0);
            assertEquals(
                    List.of(tr("diff.stageHunk"), tr("diff.stageLine"), tr("diff.copyHunk"), tr("diff.openInEditor")),
                    OverlayTestKit.labels(menu.getItems()),
                    "no Unstage or Revert on a diff that cannot do them");
            Label nav = FxTestSupport.field(pane, "changeNav");
            assertEquals(tr("diff.changePos", 1, 1), FxTestSupport.callOnFx(nav::getText), "the block is current");

            FxTestSupport.runOnFx(() -> {
                OverlayTestKit.item(menu.getItems(), tr("diff.stageLine")).fire();
                OverlayTestKit.item(menu.getItems(), tr("diff.openInEditor")).fire();
                OverlayTestKit.item(menu.getItems(), tr("diff.copyHunk")).fire();
                menu.hide();
            });
            assertEquals(2, requests.size());
            DiffViewerPane.GitHunkRequest stage1 = requests.get(0);
            assertEquals(DiffViewerPane.GitHunkAction.STAGE, stage1.action());
            assertEquals("old first\nsame\n", stage1.beforeText());
            assertEquals("new first\nsame\n", stage1.afterText());
            assertTrue(stage1.wholeFile(), "the one changed line is everything that differs");
            DiffViewerPane.GitHunkRequest open = requests.get(1);
            assertEquals(DiffViewerPane.GitHunkAction.OPEN, open.action());
            assertEquals(open.beforeText(), open.afterText(), "opening changes nothing");
            assertEquals(1, open.targetLine());
            assertEquals(
                    "-old first\n+new first\n",
                    FxTestSupport.callOnFx(() -> Clipboard.getSystemClipboard().getString()));

            // An unchanged line has no hunk to act on: no menu.
            FxTestSupport.runOnFx(() -> requestContextMenu(left, 1));
            assertEquals(List.of(), FxTestSupport.callOnFx(OverlayTestKit::openContextMenus));
        } finally {
            FxTestSupport.runOnFx(stage::close);
        }
    }

    @Test
    void thePaletteHunkCommandsActOnTheCurrentBlockOnlyWhenTheDiffOffersThem() throws Exception {
        DiffViewerPane pane = pane(LEFT, RIGHT);
        List<DiffViewerPane.GitHunkRequest> requests = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            pane.setGitHunkActions(EnumSet.of(DiffViewerPane.GitHunkAction.UNSTAGE), requests::add);
            pane.stageCurrentHunk();
            pane.revertCurrentHunk();
            pane.openCurrentChange();
        });
        assertEquals(List.of(), requests, "Stage, Revert and Open are not offered here");

        FxTestSupport.runOnFx(() -> {
            pane.goNextChange();
            pane.goNextChange();
            pane.unstageCurrentHunk();
        });
        assertEquals(1, requests.size());
        DiffViewerPane.GitHunkRequest unstage = requests.get(0);
        assertEquals(DiffViewerPane.GitHunkAction.UNSTAGE, unstage.action());
        assertEquals(RIGHT, unstage.beforeText(), "unstaging rewrites the right, index side");
        assertEquals("one\nnew two\nthree\nfour\nfive\n", unstage.afterText(), "only the second block is taken back");
        assertFalse(unstage.wholeFile());
        assertEquals(5, unstage.targetLine());

        FxTestSupport.runOnFx(() -> pane.setGitHunkActions(null, null));
        Button unstageButton = FxTestSupport.field(pane, "unstageHunkButton");
        assertFalse(FxTestSupport.callOnFx(unstageButton::isVisible), "no actions: no hunk buttons");
        FxTestSupport.runOnFx(pane::unstageCurrentHunk);
        assertEquals(1, requests.size());
    }

    @Test
    void theWhitespaceButtonCyclesExactTrimIgnoreAndReportsEachChoice() throws Exception {
        DiffViewerPane pane = pane(LEFT, RIGHT);
        List<DiffEngine.DiffOptions> seen = new ArrayList<>();
        Button whitespace = FxTestSupport.field(pane, "whitespaceButton");
        FxTestSupport.runOnFx(() -> pane.setOnOptionsChanged(seen::add));
        assertEquals(tr("diff.whitespace.none"), FxTestSupport.callOnFx(whitespace::getText));

        FxTestSupport.runOnFx(whitespace::fire);
        assertEquals(tr("diff.whitespace.trim"), FxTestSupport.callOnFx(whitespace::getText));
        FxTestSupport.runOnFx(whitespace::fire);
        assertEquals(tr("diff.whitespace.all"), FxTestSupport.callOnFx(whitespace::getText));
        assertEquals(
                tr("diff.tooltip.whitespace.all"),
                FxTestSupport.callOnFx(() -> whitespace.getTooltip().getText()));
        FxTestSupport.runOnFx(whitespace::fire);
        assertEquals(tr("diff.whitespace.none"), FxTestSupport.callOnFx(whitespace::getText));

        assertEquals(
                List.of(DiffEngine.WhitespaceMode.TRIM, DiffEngine.WhitespaceMode.ALL, DiffEngine.WhitespaceMode.NONE),
                seen.stream().map(DiffEngine.DiffOptions::whitespace).toList());

        // A null listener and null options fall back to "tell nobody" and the defaults.
        FxTestSupport.runOnFx(() -> {
            pane.setOnOptionsChanged(null);
            pane.setOptions(null);
            whitespace.fire();
        });
        assertEquals(3, seen.size());
        assertEquals(tr("diff.whitespace.trim"), FxTestSupport.callOnFx(whitespace::getText));
    }

    @Test
    void theWordAndWrapTogglesReportAndApplyTheirChoice() throws Exception {
        DiffViewerPane pane = pane(LEFT, RIGHT);
        List<DiffEngine.DiffOptions> seen = new ArrayList<>();
        ToggleButton words = FxTestSupport.field(pane, "wordButton");
        ToggleButton wrap = FxTestSupport.field(pane, "wrapButton");
        boolean wrappedBefore = FxTestSupport.callOnFx(wrap::isSelected);
        try {
            FxTestSupport.runOnFx(() -> {
                pane.setOnOptionsChanged(seen::add);
                pane.setOnResultEdited(text -> {});
                pane.setEditable(DiffViewerPane.EditableSide.RIGHT, text -> {}, () -> {}, () -> {});
                pane.toggleResultEditing();
                words.fire();
            });
            assertEquals(1, seen.size());
            assertFalse(seen.get(0).wordLevel(), "word highlighting was on, and is now off");

            FxTestSupport.runOnFx(() -> {
                if (wrap.isSelected()) {
                    wrap.fire();
                }
                wrap.fire();
            });
            CodeArea left = FxTestSupport.field(pane, "leftArea");
            CodeArea right = FxTestSupport.field(pane, "rightArea");
            CodeArea result = FxTestSupport.field(pane, "resultArea");
            assertTrue(FxTestSupport.callOnFx(left::isWrapText));
            assertTrue(FxTestSupport.callOnFx(right::isWrapText));
            assertTrue(FxTestSupport.callOnFx(result::isWrapText), "the Result editor wraps with the comparison");

            FxTestSupport.runOnFx(() -> {
                pane.toggleViewMode();
                wrap.fire();
            });
            CodeArea unified = FxTestSupport.field(pane, "unifiedArea");
            assertFalse(FxTestSupport.callOnFx(unified::isWrapText));
            assertFalse(FxTestSupport.callOnFx(result::isWrapText));

            // Text zoom reaches whichever areas exist: here the unified one and the Result editor.
            FxTestSupport.runOnFx(() -> pane.setFont("Serif", 21));
            assertTrue(FxTestSupport.callOnFx(unified::getStyle).contains("21px"));
            assertTrue(FxTestSupport.callOnFx(result::getStyle).contains("21px"));
        } finally {
            FxTestSupport.runOnFx(() -> {
                if (wrap.isSelected() != wrappedBefore) {
                    wrap.fire(); // remembered for the next diff: put it back
                }
                pane.dispose();
            });
        }
    }

    @Test
    void applyAllAsksFirstAndReplacesTheLocalSideOnlyOnYes() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            DiffViewerPane pane = pane(LEFT, RIGHT);
            List<String> applied = new ArrayList<>();
            FxTestSupport.runOnFx(() -> pane.setEditable(
                    DiffViewerPane.EditableSide.RIGHT,
                    (java.util.function.Consumer<String>) applied::add,
                    () -> {},
                    () -> {}));
            Stage stage = show(pane);
            try {
                AtomicReference<OverlayTestKit.Shown> question = new AtomicReference<>();
                CountDownLatch declined =
                        OverlayTestKit.answerAnyDialog(async, ButtonBar.ButtonData.CANCEL_CLOSE, question);
                FxTestSupport.runOnFx(pane::applyAllChanges);
                async.await(declined, "the Apply All question");
                assertEquals(tr("diff.applyAll.confirm"), question.get().content());
                assertEquals(List.of(), applied, "declined: the local file is untouched");

                CountDownLatch agreed = OverlayTestKit.answerAnyDialog(async, ButtonBar.ButtonData.OK_DONE, null);
                FxTestSupport.runOnFx(pane::applyAllChanges);
                async.await(agreed, "the Apply All question, answered yes");
                assertEquals(List.of(LEFT), applied, "the whole local side becomes the other side's exact text");
                Button undo = FxTestSupport.field(pane, "undoButton");
                Button save = FxTestSupport.field(pane, "saveButton");
                assertFalse(FxTestSupport.callOnFx(undo::isDisable), "the apply can be undone");
                assertFalse(FxTestSupport.callOnFx(save::isDisable), "and saved");
            } finally {
                FxTestSupport.runOnFx(stage::close);
            }
        }
    }

    @Test
    void applyAllDoesNothingOnAReadOnlyDiff() throws Exception {
        DiffViewerPane pane = pane(LEFT, RIGHT);
        FxTestSupport.runOnFx(pane::applyAllChanges);
        assertEquals(List.of(), FxTestSupport.callOnFx(OverlayTestKit::openDialogHeaders), "nothing is asked");
    }

    @Test
    void theFinalNewlineButtonGivesTheLocalSideTheOtherSidesEndOfFile() throws Exception {
        DiffViewerPane pane = pane("a\nb\n", "a\nb");
        Button eof = FxTestSupport.field(pane, "applyEofButton");
        assertFalse(FxTestSupport.callOnFx(eof::isVisible), "a read-only diff has nothing to apply it to");
        Label summary = FxTestSupport.field(pane, "summary");
        assertTrue(FxTestSupport.callOnFx(summary::getText).contains(tr("diff.finalNewlineDiffers")));

        AtomicReference<String> applied = new AtomicReference<>();
        FxTestSupport.runOnFx(
                () -> pane.setEditable(DiffViewerPane.EditableSide.RIGHT, applied::set, () -> {}, () -> {}));
        assertTrue(FxTestSupport.callOnFx(eof::isVisible));
        FxTestSupport.runOnFx(eof::fire);
        assertEquals("a\nb\n", applied.get(), "the right side gains the left side's final newline");

        DiffViewerPane other = pane("a\nb\n", "a\nb");
        FxTestSupport.runOnFx(() -> {
            other.setEditable(DiffViewerPane.EditableSide.LEFT, applied::set, () -> {}, () -> {});
            ((Button) FxTestSupport.field(other, "applyEofButton")).fire();
        });
        assertEquals("a\nb", applied.get(), "and an editable left side loses it");
    }

    /** The chevrons at the centre seam: a click applies the hunk, Enter or Space on a line chevron that line. */
    @Test
    void theSeamChevronsApplyAHunkByMouseAndALineByKeyboard() throws Exception {
        DiffViewerPane pane = pane("same\nsource one\nsource two\nsame\n", "same\ntarget one\ntarget two\nsame\n");
        List<String> applied = new ArrayList<>();
        FxTestSupport.runOnFx(() -> pane.setEditable(
                DiffViewerPane.EditableSide.RIGHT,
                (java.util.function.Consumer<String>) applied::add,
                () -> {},
                () -> {}));
        Stage stage = show(pane);
        try {
            CodeArea right = FxTestSupport.field(pane, "rightArea");
            List<Node> chevrons = FxTestSupport.callOnFx(() -> List.copyOf(right.lookupAll(".diff-apply")));
            // Row 1 opens the block: a hunk chevron and a line chevron. Row 2 has only its line chevron.
            Node hunk = chevrons.stream()
                    .filter(node -> tr("diff.applyChange").equals(node.getAccessibleText()))
                    .findFirst()
                    .orElseThrow();
            List<Node> lines = chevrons.stream()
                    .filter(node -> tr("diff.applyLine").equals(node.getAccessibleText()))
                    .toList();
            assertEquals(2, lines.size(), "one line chevron per changed row");
            assertEquals(1, chevrons.size() - lines.size(), "one hunk chevron for the one block");

            FxTestSupport.runOnFx(() -> javafx.event.Event.fireEvent(
                    hunk,
                    new MouseEvent(
                            MouseEvent.MOUSE_CLICKED,
                            1,
                            1,
                            1,
                            1,
                            MouseButton.PRIMARY,
                            1,
                            false,
                            false,
                            false,
                            false,
                            true,
                            false,
                            false,
                            true,
                            false,
                            true,
                            null)));
            assertEquals(List.of("same\nsource one\nsource two\nsame\n"), applied);

            Node secondLine = lines.stream()
                    .max(java.util.Comparator.comparingDouble(
                            node -> node.localToScene(node.getBoundsInLocal()).getMinY()))
                    .orElseThrow();
            FxTestSupport.runOnFx(() -> {
                javafx.event.Event.fireEvent(
                        secondLine,
                        new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, false, false, false, false));
                javafx.event.Event.fireEvent(
                        secondLine,
                        new KeyEvent(KeyEvent.KEY_PRESSED, " ", " ", KeyCode.SPACE, false, false, false, false));
            });
            assertEquals(2, applied.size(), "Tab applies nothing; Space does");
            assertEquals("same\ntarget one\nsource two\nsame\n", applied.get(1), "only the second line is taken");
        } finally {
            FxTestSupport.runOnFx(stage::close);
        }
    }

    @Test
    void swappingAnEditableLeftSideMovesTheEditableMarkerToTheRight() throws Exception {
        DiffViewerPane pane = pane(LEFT, RIGHT);
        AtomicReference<String[]> asked = new AtomicReference<>();
        FxTestSupport.runOnFx(() -> {
            pane.setEditable(DiffViewerPane.EditableSide.LEFT, text -> {}, () -> {}, () -> {});
            pane.setOnResultEdited(text -> {});
            pane.setOnSwapRequested((left, right) -> asked.set(new String[] {left, right}));
            pane.toggleResultEditing();
            pane.toggleViewMode();
            pane.goNextChange();
            pane.swapComparisonSides();
        });
        assertEquals(RIGHT, asked.get()[0], "the swap asks for the right text on the left");
        Button swap = FxTestSupport.field(pane, "swapButton");
        assertTrue(FxTestSupport.callOnFx(swap::isDisable), "no second swap while the first is computed");

        FxTestSupport.runOnFx(() -> pane.swapSides(DiffEngine.compute(RIGHT, LEFT, DiffEngine.DiffOptions.DEFAULT)));
        assertEquals(DiffViewerPane.EditableSide.RIGHT, FxTestSupport.callOnFx(pane::editableSide));
        assertEquals(LEFT, FxTestSupport.callOnFx(pane::resultText), "a clean Result follows the local side");
        assertEquals("a/x.txt", FxTestSupport.callOnFx(() -> pane.patchRequest().leftLabel()));
        assertTrue(FxTestSupport.callOnFx(() -> pane.matches(RIGHT, LEFT)));
        Label nav = FxTestSupport.field(pane, "changeNav");
        assertEquals(tr("diff.changePos", 1, 2), FxTestSupport.callOnFx(nav::getText), "the change stays current");
        assertFalse(FxTestSupport.callOnFx(swap::isDisable));
        CodeArea unified = FxTestSupport.field(pane, "unifiedArea");
        assertTrue(FxTestSupport.callOnFx(unified::getText).startsWith("one\nnew two\nold two"), "still unified");

        // A swap whose recomputation failed leaves everything as it was and can be asked for again.
        FxTestSupport.runOnFx(() -> {
            pane.swapComparisonSides();
            pane.swapSides(null);
        });
        assertEquals(DiffViewerPane.EditableSide.RIGHT, FxTestSupport.callOnFx(pane::editableSide));
        assertFalse(FxTestSupport.callOnFx(swap::isDisable));
        FxTestSupport.runOnFx(() -> {});
        FxTestSupport.runOnFx(pane::dispose);
    }

    @Test
    void aRefreshedComparisonKeepsTheCurrentChangeAndSyncsACleanResult() throws Exception {
        DiffViewerPane pane = pane(LEFT, RIGHT);
        String newRight = "one\nnew two\nthree\nfour\nadded\nmore\nfive\n";
        FxTestSupport.runOnFx(() -> {
            pane.setOnResultEdited(text -> {});
            pane.setEditable(DiffViewerPane.EditableSide.RIGHT, text -> {}, () -> {}, () -> {});
            pane.toggleResultEditing();
            pane.toggleViewMode();
            pane.goNextChange();
            pane.goNextChange();
            pane.updateContent(LEFT, newRight, DiffEngine.compute(LEFT, newRight, DiffEngine.DiffOptions.DEFAULT));
        });
        Label nav = FxTestSupport.field(pane, "changeNav");
        assertEquals(tr("diff.changePos", 2, 2), FxTestSupport.callOnFx(nav::getText));
        assertEquals(newRight, FxTestSupport.callOnFx(pane::resultText));
        assertTrue(FxTestSupport.callOnFx(() -> pane.matchesEditableText(newRight)));
        CodeArea unified = FxTestSupport.field(pane, "unifiedArea");
        assertTrue(FxTestSupport.callOnFx(unified::getText).contains("added\nmore"));
        FxTestSupport.runOnFx(() -> {});
        FxTestSupport.runOnFx(pane::dispose);
    }

    /** Reset puts the draft back and re-diffs; switching the editor off with a draft in it is refused. */
    @Test
    void aDirtyResultDraftSurvivesTheToolbarToggleAndResetRestoresTheBaseline() throws Exception {
        DiffViewerPane pane = pane(LEFT, RIGHT);
        List<String> edited = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            pane.setOnResultEdited(edited::add);
            pane.setEditable(DiffViewerPane.EditableSide.RIGHT, text -> {}, () -> {}, () -> {});
            pane.toggleResultEditing();
        });
        CodeArea result = FxTestSupport.field(pane, "resultArea");
        ToggleButton edit = FxTestSupport.field(pane, "editResultButton");
        Button reset = FxTestSupport.field(pane, "resetResultButton");
        FxTestSupport.runOnFx(() -> result.appendText("draft\n"));
        assertTrue(FxTestSupport.callOnFx(pane::hasDirtyResult));
        assertEquals(RIGHT + "draft\n", FxTestSupport.callOnFx(pane::unsavedStateToken));

        FxTestSupport.runOnFx(pane::toggleResultEditing);
        assertTrue(FxTestSupport.callOnFx(edit::isSelected), "the button stays down");
        assertTrue(FxTestSupport.callOnFx(pane::hasResultEditor), "and the draft is still there");
        assertEquals(RIGHT + "draft\n", FxTestSupport.callOnFx(pane::resultText));

        FxTestSupport.runOnFx(reset::fire);
        assertEquals(RIGHT, FxTestSupport.callOnFx(pane::resultText));
        assertFalse(FxTestSupport.callOnFx(pane::hasDirtyResult));
        assertNull(FxTestSupport.callOnFx(pane::unsavedStateToken));
        assertEquals(RIGHT, edited.get(edited.size() - 1), "the comparison is recomputed for the baseline");

        FxTestSupport.runOnFx(pane::toggleResultEditing);
        assertFalse(FxTestSupport.callOnFx(pane::hasResultEditor), "a clean editor closes");
        // Taking the Result feature away closes an open editor too.
        FxTestSupport.runOnFx(() -> {
            pane.toggleResultEditing();
            pane.setOnResultEdited(null);
        });
        assertFalse(FxTestSupport.callOnFx(pane::hasResultEditor));
        assertFalse(FxTestSupport.callOnFx(edit::isVisible));
        FxTestSupport.runOnFx(pane::dispose);
    }

    @Test
    void theSummaryNamesADegradedComparisonAndAMetadataOnlyOneCannotBeActedOn() throws Exception {
        DiffModels.DiffModel full = DiffEngine.compute(LEFT, RIGHT, DiffEngine.DiffOptions.DEFAULT);
        DiffViewerPane lineOnly = pane(LEFT, RIGHT, withQuality(full, DiffModels.Quality.LINE_ONLY));
        Label summary = FxTestSupport.field(lineOnly, "summary");
        assertEquals(
                tr("diff.summary", full.added(), full.removed()) + "  ·  " + tr("diff.simplified"),
                FxTestSupport.callOnFx(summary::getText));

        DiffViewerPane metadata = pane(LEFT, RIGHT, withQuality(full, DiffModels.Quality.METADATA_ONLY));
        Label metadataSummary = FxTestSupport.field(metadata, "summary");
        assertTrue(FxTestSupport.callOnFx(metadataSummary::getText).endsWith(tr("diff.metadataOnly")));
        List<Object> seen = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            metadata.setGitHunkActions(EnumSet.allOf(DiffViewerPane.GitHunkAction.class), seen::add);
            metadata.setEditable(
                    DiffViewerPane.EditableSide.RIGHT,
                    (java.util.function.Consumer<String>) seen::add,
                    () -> {},
                    () -> {});
            metadata.stageCurrentHunk();
            metadata.revertCurrentHunk();
            metadata.applyAllChanges();
        });
        assertEquals(List.of(), seen, "rows that are only a summary are never written anywhere");
        Button applyAll = FxTestSupport.field(metadata, "applyAllButton");
        assertFalse(FxTestSupport.callOnFx(applyAll::isVisible));
    }

    @Test
    void revertOnTheEditableSideIsAnApplyThatCountsForUndoAndSave() throws Exception {
        DiffViewerPane pane = pane(LEFT, RIGHT);
        List<String> applied = new ArrayList<>();
        List<DiffViewerPane.GitHunkRequest> requests = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            pane.setEditable(
                    DiffViewerPane.EditableSide.RIGHT,
                    (java.util.function.Consumer<String>) applied::add,
                    () -> {},
                    () -> {});
            pane.setGitHunkActions(EnumSet.of(DiffViewerPane.GitHunkAction.REVERT), requests::add);
            pane.revertCurrentHunk();
        });
        assertEquals(List.of("one\nold two\nthree\nfour\nadded\nfive\n"), applied);
        assertEquals(List.of(), requests, "the Git handler is not asked to edit the buffer behind the pane");
        Button undo = FxTestSupport.field(pane, "undoButton");
        assertFalse(FxTestSupport.callOnFx(undo::isDisable));
    }

    @Test
    void sideFormatsDefaultToLfUtf8AndTravelWithTheExportRequest() throws Exception {
        DiffViewerPane pane = pane(LEFT, RIGHT);
        AtomicReference<DiffViewerPane.PatchRequest> exported = new AtomicReference<>();
        Button export = FxTestSupport.field(pane, "exportButton");
        FxTestSupport.runOnFx(() -> {
            pane.setSideFormats(new DiffViewerPane.SideFormat(null, null), null);
            pane.setOnExportPatch(exported::set);
            export.fire();
        });
        DiffViewerPane.PatchRequest request = exported.get();
        assertEquals(DiffViewerPane.SideFormat.DEFAULT, request.leftFormat());
        assertEquals(DiffViewerPane.SideFormat.DEFAULT, request.rightFormat());
        assertEquals("b/x.txt", request.rightLabel());
        assertEquals(LEFT, request.leftText());
        assertEquals(
                com.editora.diff.PatchWriter.unifiedDiff("a/x.txt", "b/x.txt", LEFT, RIGHT),
                FxTestSupport.callOnFx(() -> pane.patchText("a/x.txt", "b/x.txt")));

        FxTestSupport.runOnFx(() -> {
            pane.setOnExportPatch(null);
            export.fire(); // nobody to hand it to: nothing happens, nothing throws
        });
        assertSame(request, exported.get());
    }

    @Test
    void theUnifiedGutterShowsALineNumberAndASignPerRow() throws Exception {
        DiffViewerPane pane = pane("keep\nold\n", "keep\nnew\n");
        FxTestSupport.runOnFx(pane::toggleViewMode);
        Stage stage = show(pane);
        try {
            CodeArea unified = FxTestSupport.field(pane, "unifiedArea");
            List<String> signs = FxTestSupport.callOnFx(() -> unified.lookupAll(".diff-sign").stream()
                    .sorted(java.util.Comparator.comparingDouble(
                            node -> node.localToScene(node.getBoundsInLocal()).getMinY()))
                    .map(node -> ((Label) node).getText())
                    .toList());
            assertEquals(List.of(" ", "-", "+"), signs);
            List<String> numbers = FxTestSupport.callOnFx(() -> unified.lookupAll(".diff-lineno").stream()
                    .sorted(java.util.Comparator.comparingDouble(
                            node -> node.localToScene(node.getBoundsInLocal()).getMinY()))
                    .map(node -> ((Label) node).getText())
                    .toList());
            assertEquals(List.of("1", "2", "2"), numbers, "a removed row shows its old number, an added row its new");
        } finally {
            FxTestSupport.runOnFx(stage::close);
        }
    }

    /** Whichever pane the user scrolls leads; the other follows it, and never the other way round. */
    @Test
    void theScrolledPaneLeadsTheOther() throws Exception {
        StringBuilder left = new StringBuilder();
        StringBuilder right = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            left.append(i % 7 == 0 ? "left-" : "line-").append(i).append('\n');
            right.append(i % 7 == 0 ? "right-" : "line-").append(i).append('\n');
        }
        DiffViewerPane pane = pane(left.toString(), right.toString());
        Stage stage = show(pane);
        try {
            CodeArea leftArea = FxTestSupport.field(pane, "leftArea");
            CodeArea rightArea = FxTestSupport.field(pane, "rightArea");
            FxTestSupport.runOnFx(() -> {
                javafx.event.Event.fireEvent(
                        rightArea,
                        new javafx.scene.input.ScrollEvent(
                                javafx.scene.input.ScrollEvent.SCROLL,
                                10,
                                10,
                                10,
                                10,
                                false,
                                false,
                                false,
                                false,
                                false,
                                false,
                                0,
                                -40,
                                0,
                                -40,
                                javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE,
                                0,
                                javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.NONE,
                                0,
                                0,
                                null));
                rightArea.estimatedScrollYProperty().setValue(300.0);
                layout(pane); // a scroll position lands with the next layout pass
            });
            assertEquals(300.0, FxTestSupport.callOnFx(leftArea::getEstimatedScrollY), "the left pane follows");

            FxTestSupport.runOnFx(() -> {
                leftArea.estimatedScrollYProperty().setValue(60.0);
                layout(pane);
            });
            assertEquals(60.0, FxTestSupport.callOnFx(leftArea::getEstimatedScrollY));
            assertEquals(
                    300.0,
                    FxTestSupport.callOnFx(rightArea::getEstimatedScrollY),
                    "the follower moving does not drag the leader back");
        } finally {
            FxTestSupport.runOnFx(stage::close);
        }
    }

    // --- plumbing ----------------------------------------------------------------------------------

    private record ToggleableLabels(String accessible) {}

    private static ToggleableLabels toggleLabels(DiffViewerPane pane) throws Exception {
        Button toggle = FxTestSupport.field(pane, "toggleButton");
        return new ToggleableLabels(FxTestSupport.callOnFx(toggle::getAccessibleText));
    }

    /** Presses {@code code} on the pane; true when the viewer consumed it. */
    private static boolean press(DiffViewerPane pane, KeyCode code, boolean shift) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            boolean[] consumed = new boolean[1];
            javafx.event.EventHandler<KeyEvent> probe = event -> consumed[0] = false;
            // A consumed event never reaches a handler on the same node: seeing it means "not consumed".
            consumed[0] = true;
            pane.node().addEventHandler(KeyEvent.KEY_PRESSED, probe);
            try {
                javafx.event.Event.fireEvent(
                        pane.node(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false));
            } finally {
                pane.node().removeEventHandler(KeyEvent.KEY_PRESSED, probe);
            }
            return consumed[0];
        });
    }

    private static void layout(DiffViewerPane pane) {
        javafx.scene.Parent root = pane.node().getScene().getRoot();
        root.applyCss();
        root.layout();
    }

    private static Stage show(DiffViewerPane pane) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            Stage window = new Stage();
            window.setScene(new Scene(new StackPane(pane.node()), 900, 560));
            window.show();
            window.getScene().getRoot().applyCss();
            window.getScene().getRoot().layout();
            return window;
        });
    }

    /** Asks for the context menu on paragraph {@code paragraph} of {@code area} and returns the one shown. */
    private static ContextMenu contextMenuAt(CodeArea area, int paragraph) throws Exception {
        FxTestSupport.runOnFx(() -> requestContextMenu(area, paragraph));
        List<ContextMenu> menus = FxTestSupport.callOnFx(OverlayTestKit::openContextMenus);
        assertEquals(1, menus.size(), "one context menu");
        return menus.get(0);
    }

    private static void requestContextMenu(CodeArea area, int paragraph) {
        area.moveTo(paragraph, 0);
        javafx.geometry.Bounds bounds = area.getParagraphBoundsOnScreen(paragraph)
                .map(area::screenToLocal)
                .orElseThrow(() -> new AssertionError("paragraph " + paragraph + " is not laid out"));
        double x = bounds.getMaxX() - 4;
        double y = (bounds.getMinY() + bounds.getMaxY()) / 2;
        javafx.geometry.Point2D screen = area.localToScreen(x, y);
        // An event fired without a pick result takes its x/y as scene coordinates and localises them itself.
        javafx.geometry.Point2D scene = area.localToScene(x, y);
        javafx.event.Event.fireEvent(
                area,
                new ContextMenuEvent(
                        ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                        scene.getX(),
                        scene.getY(),
                        screen.getX(),
                        screen.getY(),
                        false,
                        null));
    }

    private static DiffModels.DiffModel withQuality(DiffModels.DiffModel model, DiffModels.Quality quality) {
        return new DiffModels.DiffModel(
                model.rows(),
                model.unified(),
                model.added(),
                model.removed(),
                model.changeBlockStarts(),
                model.leftFinalNewline(),
                model.rightFinalNewline(),
                quality);
    }

    private static DiffViewerPane pane(String left, String right) throws Exception {
        return pane(left, right, DiffEngine.compute(left, right, DiffEngine.DiffOptions.DEFAULT));
    }

    private static DiffViewerPane pane(String left, String right, DiffModels.DiffModel model) throws Exception {
        return FxTestSupport.callOnFx(() -> new DiffViewerPane(
                "diff", "left", "right", "x.txt", "x.txt", left, right, model, "Monospaced", 13, true, "x.txt"));
    }
}
