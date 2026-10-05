package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.stage.Stage;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A split shows one buffer in two views. They share the document, so they must share its view settings,
 * and everything that says "at the caret" must mean the caret of the view the user is in — driven here
 * through the real commands against a wired window.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SplitViewFxTest {

    private FxWindowFixture fx;
    private CommandRegistry registry;
    private int files;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        registry = FxTestSupport.field(fx.controller, "registry");
        FxTestSupport.runOnFx(() -> {
            Stage stage = FxTestSupport.field(fx.controller, "stage");
            stage.setWidth(1100);
            stage.setHeight(700);
            if (!stage.isShowing()) {
                stage.show(); // focus is only ever granted inside a showing window
            }
        });
        settle();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private void run(String id) throws Exception {
        FxTestSupport.runOnFx(() -> registry.run(id));
        FxTestSupport.drainFx();
    }

    private static void settle() throws Exception {
        FxTestSupport.drainFx();
        Thread.sleep(150);
        FxTestSupport.drainFx();
        FxTestSupport.drainFx();
    }

    /** Opens {@code content} as a real file (bookmarks need a path) and waits for it to be the active buffer. */
    private EditorBuffer open(String name, String content) throws Exception {
        Path file = fx.configDir.resolve((files++) + name);
        Files.writeString(file, content);
        FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(file, 0));
        for (int i = 0; i < 100; i++) {
            settle();
            EditorBuffer b = FxTestSupport.callOnFx(
                    () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class[] {}));
            if (b != null && file.equals(b.getPath()) && !b.isLoading()) {
                settle();
                return b;
            }
        }
        throw new IllegalStateException("did not open " + file);
    }

    /** Splits the active buffer and returns its second view. */
    private CodeArea split(EditorBuffer b) throws Exception {
        run("view.splitVertical");
        settle();
        CodeArea second = FxTestSupport.callOnFx(() -> FxTestSupport.field(b, "area2"));
        assertNotNull(second, "the split created a second view");
        return second;
    }

    /** Puts keyboard focus in {@code view}; skips the test where the headless window cannot grant it. */
    private void focus(EditorBuffer b, CodeArea view) throws Exception {
        FxTestSupport.runOnFx(() -> {
            ((Stage) FxTestSupport.field(fx.controller, "stage")).requestFocus();
            view.requestFocus();
        });
        settle();
        Assumptions.assumeTrue(
                FxTestSupport.callOnFx(() -> view.isFocused() && b.getFocusedArea() == view),
                "headless window could not focus the editor view");
    }

    private static String lines(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            sb.append("line ").append(i).append(" lorem ipsum\n");
        }
        return sb.toString();
    }

    // --- E4-9: the status bar shows the focused pane's caret --------------------------------------

    @Test
    void statusBarFollowsTheFocusedPane() throws Exception {
        EditorBuffer b = open("status.txt", lines(30));
        CodeArea first = b.getArea();
        CodeArea second = split(b);
        Label position = FxTestSupport.field(FxTestSupport.field(fx.controller, "statusBar"), "position");
        FxTestSupport.runOnFx(() -> first.moveTo(2, 3));
        focus(b, second);
        FxTestSupport.runOnFx(() -> second.moveTo(10, 5));
        assertEquals("Ln 11, Col 6", FxTestSupport.callOnFx(position::getText));
        FxTestSupport.runOnFx(() -> second.selectRange(10, 0, 10, 4));
        assertEquals("Ln 11, Col 5 (4 selected)", FxTestSupport.callOnFx(position::getText));

        focus(b, first);
        assertEquals("Ln 3, Col 4", FxTestSupport.callOnFx(position::getText), "back in pane 1");
        FxTestSupport.runOnFx(() -> second.moveTo(20, 0));
        assertEquals("Ln 3, Col 4", FxTestSupport.callOnFx(position::getText), "pane 2 no longer drives it");
    }

    // --- E4-10: "at caret" commands use the focused pane's caret ----------------------------------

    @Test
    void foldBookmarkBreakpointAndNoteCommandsUseTheFocusedPanesCaret() throws Exception {
        StringBuilder src = new StringBuilder("class Big {\n");
        for (int m = 0; m < 3; m++) {
            src.append("    void m").append(m).append("() {\n");
            for (int i = 0; i < 20; i++) {
                src.append("        call").append(i).append("();\n");
            }
            src.append("    }\n");
        }
        src.append("}\n");
        EditorBuffer b = open("Big.java", src.toString());
        FxTestSupport.runOnFx(() -> b.getFoldManager().recompute());
        CodeArea first = b.getArea();
        CodeArea second = split(b);
        // m0 spans lines 1..22, m1 lines 23..44.
        FxTestSupport.runOnFx(() -> first.moveTo(5, 4));
        focus(b, second);
        FxTestSupport.runOnFx(() -> second.moveTo(30, 4));

        run("bookmarks.toggle");
        assertEquals(
                java.util.List.of(30),
                java.util.List.copyOf(
                        FxTestSupport.callOnFx(() -> b.getBookmarkManager().lines())));
        // debug.toggleBreakpoint is gated on the debugger feature (off by default), and so is the toggle itself.
        FxTestSupport.runOnFx(() -> fx.shared.getSettings().setDebugSupport(true));
        Object debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        FxTestSupport.runOnFx(() -> FxTestSupport.invoke(debug, "toggleBreakpointAtCaret"));
        assertTrue(FxTestSupport.callOnFx(() -> b.getBreakpointManager().isBreakpoint(30)));
        assertFalse(FxTestSupport.callOnFx(() -> b.getBreakpointManager().isBreakpoint(5)));
        assertEquals(30, (int)
                FxTestSupport.callOnFx(() -> b.captureNoteDraft().anchor().line()));

        run("view.toggleFold");
        settle();
        assertTrue(FxTestSupport.callOnFx(() -> b.getFoldManager().isCollapsed(23)), "the method under pane 2's caret");
        assertFalse(FxTestSupport.callOnFx(() -> b.getFoldManager().isCollapsed(1)), "not the one under pane 1's");
        int line = FxTestSupport.callOnFx(second::getCurrentParagraph);
        assertFalse(FxTestSupport.callOnFx(() -> second.isFolded(line)), "pane 2's caret is not left in hidden text");
    }

    // --- E4-11: closing the split from pane 2 keeps the keyboard in the editor --------------------

    @Test
    void closingTheSplitFromTheSecondPaneFocusesTheRemainingEditor() throws Exception {
        EditorBuffer b = open("focus.txt", lines(10));
        CodeArea second = split(b);
        focus(b, second);
        run("view.splitVertical");
        settle();
        assertSame(b.getArea(), FxTestSupport.callOnFx(b::getFocusedArea));
        Node owner = FxTestSupport.callOnFx(() -> b.getArea().getScene().getFocusOwner());
        assertSame(b.getArea(), owner, "focus must land on the remaining editor, not on a toolbar button");
    }

    // --- E4-12: pane 2 honours the same view settings ---------------------------------------------

    private static Node whitespaceOverlay(Parent pane) {
        return pane.getChildrenUnmodifiable().stream()
                .filter(n -> n.getStyleClass().contains("whitespace-overlay"))
                .findFirst()
                .orElse(null);
    }

    @Test
    void theSecondPaneHonoursTheViewSettings() throws Exception {
        EditorBuffer b = open("view.txt", lines(30));
        CodeArea first = b.getArea();
        boolean highlight = FxTestSupport.callOnFx(first::isLineHighlighterOn);
        run("view.toggleWordWrap");
        run("view.toggleWhitespace");
        run("view.toggleLineNumbers");
        try {
            assertTrue(FxTestSupport.callOnFx(first::isWrapText), "precondition: word wrap is on");
            CodeArea second = split(b);
            Parent pane2 = FxTestSupport.callOnFx(() -> FxTestSupport.field(b, "root2"));
            assertTrue(FxTestSupport.callOnFx(second::isWrapText), "wraps like pane 1 from the start");
            assertEquals(highlight, FxTestSupport.callOnFx(second::isLineHighlighterOn));
            // Line numbers are off in both panes. Pane 2 used to have no gutter at all then (its gutter was line
            // numbers only); it now has the same gutter as pane 1 — markers and fold chevrons, no numbers.
            assertEquals(0, gutterNodes(second, ".lineno"), "line numbers are off");
            assertEquals(0, gutterNodes(first, ".lineno"));
            Node markers = FxTestSupport.callOnFx(() -> whitespaceOverlay(pane2));
            assertNotNull(markers, "pane 2 has its own whitespace markers");
            assertTrue(FxTestSupport.callOnFx(markers::isVisible));
            double textWidth = FxTestSupport.callOnFx(
                    () -> second.getParent().getBoundsInParent().getWidth());
            assertEquals(
                    textWidth,
                    FxTestSupport.callOnFx(() -> markers.getBoundsInParent().getWidth()),
                    0.5);

            run("view.toggleWordWrap");
            run("view.toggleWhitespace");
            run("view.toggleLineNumbers");
            run("view.toggleLineHighlight");
            assertFalse(FxTestSupport.callOnFx(second::isWrapText), "and follows every later change");
            assertFalse(FxTestSupport.callOnFx(markers::isVisible));
            assertNotNull(FxTestSupport.callOnFx(second::getParagraphGraphicFactory));
            settle();
            assertTrue(gutterNodes(second, ".lineno") > 0, "and line numbers come back in pane 2 as well");
            assertEquals(!highlight, FxTestSupport.callOnFx(second::isLineHighlighterOn));
        } finally {
            if (FxTestSupport.callOnFx(first::isWrapText)) {
                run("view.toggleWordWrap");
                run("view.toggleWhitespace");
                run("view.toggleLineNumbers");
            }
            if (FxTestSupport.callOnFx(first::isLineHighlighterOn) != highlight) {
                run("view.toggleLineHighlight");
            }
        }
    }

    // --- E4-12 (rest): pane 2 shows the same marks ------------------------------------------------

    /** How many nodes matching {@code selector} the gutter rows currently on screen in {@code view} hold. */
    private int gutterNodes(CodeArea view, String selector) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            view.applyCss();
            view.layout();
            return (int) view.lookupAll(selector).stream()
                    .filter(n -> n.getScene() != null
                            && !(n instanceof Label l && l.getText().isBlank()))
                    .count();
        });
    }

    /** A screenshot for a human to look at; written only with {@code -Deditora.test.shots=<dir>}. */
    private void shot(String name) throws Exception {
        String dir = System.getProperty("editora.test.shots");
        if (dir == null) {
            return;
        }
        settle();
        javafx.scene.image.WritableImage img = FxTestSupport.callOnFx(() ->
                ((Stage) FxTestSupport.field(fx.controller, "stage")).getScene().snapshot(null));
        javax.imageio.ImageIO.write(
                javafx.embed.swing.SwingFXUtils.fromFXImage(img, null),
                "png",
                Path.of(dir, name + ".png").toFile());
    }

    @Test
    void theSecondPaneShowsBookmarkBreakpointAndFoldMarksAndKeepsThemInStep() throws Exception {
        StringBuilder src = new StringBuilder("class Marks {\n");
        for (int m = 0; m < 2; m++) {
            src.append("    void m").append(m).append("() {\n        call();\n        call();\n    }\n");
        }
        src.append("}\n");
        EditorBuffer b = open("Marks.java", src.toString());
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setDebugSupport(true);
            b.setBreakpointsEnabled(true);
            b.getFoldManager().recompute();
            b.toggleBookmark(2);
        });
        CodeArea first = b.getArea();
        CodeArea second = split(b);
        settle();

        assertEquals(1, gutterNodes(first, ".bookmark-marker"), "precondition: pane 1 shows the bookmark");
        assertEquals(1, gutterNodes(second, ".bookmark-marker"), "pane 2 shows it from the start");
        int chevrons = gutterNodes(first, ".fold-chevron");
        assertTrue(chevrons >= 3, "the class and both methods can be folded: " + chevrons);
        assertEquals(chevrons, gutterNodes(second, ".fold-chevron"), "pane 2 has the fold chevrons");

        // Marks added while the split is open appear in both panes; removed ones go from both.
        FxTestSupport.runOnFx(() -> {
            b.toggleBookmark(6);
            b.toggleBreakpoint(3);
        });
        settle();
        assertEquals(2, gutterNodes(second, ".bookmark-marker"));
        assertEquals(1, gutterNodes(second, ".breakpoint-marker"));
        assertEquals(1, gutterNodes(first, ".breakpoint-marker"));
        FxTestSupport.runOnFx(() -> {
            b.toggleBookmark(2);
            b.toggleBreakpoint(3);
        });
        settle();
        assertEquals(1, gutterNodes(second, ".bookmark-marker"));
        assertEquals(0, gutterNodes(second, ".breakpoint-marker"));
        FxTestSupport.runOnFx(() -> b.toggleBreakpoint(7));

        shot("split-marks");

        // A chevron in pane 2 folds the region, and both panes then show it collapsed.
        Label chevron = FxTestSupport.callOnFx(() -> second.lookupAll(".fold-chevron").stream()
                .map(n -> (Label) n)
                .filter(l -> !l.getText().isBlank() && l.getScene() != null)
                .skip(1) // the first belongs to the class; take the first method
                .findFirst()
                .orElseThrow());
        FxTestSupport.runOnFx(() -> javafx.event.Event.fireEvent(
                chevron,
                new javafx.scene.input.MouseEvent(
                        javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                        1,
                        1,
                        1,
                        1,
                        javafx.scene.input.MouseButton.PRIMARY,
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
        settle();
        assertTrue(FxTestSupport.callOnFx(() -> b.getFoldManager().isCollapsed(1)), "folded from pane 2's gutter");
        assertTrue(FxTestSupport.callOnFx(() -> second.isFolded(2)), "its body is hidden in pane 2");
    }

    @Test
    void theSecondPaneHasTheEditorContextMenuAndItActsOnThatPane() throws Exception {
        EditorBuffer b = open("menu.txt", lines(30));
        CodeArea first = b.getArea();
        CodeArea second = split(b);
        FxTestSupport.runOnFx(() -> first.selectRange(2, 0, 2, 4));
        focus(b, second);
        FxTestSupport.runOnFx(() -> second.selectRange(10, 5, 10, 7));
        assertNotNull(FxTestSupport.callOnFx(second::getOnContextMenuRequested), "pane 2 answers a right-click");

        javafx.scene.control.ContextMenu menu = FxTestSupport.field(b, "contextMenu");
        FxTestSupport.runOnFx(() -> {
            // Inside pane 2's selection: a right-click outside it moves the caret there and drops it.
            second.showParagraphAtTop(8);
            second.layout();
            int inSelection = second.getAbsolutePosition(10, 6);
            javafx.geometry.Bounds on = second.getCharacterBoundsOnScreen(inSelection, inSelection + 1)
                    .orElseThrow();
            javafx.geometry.Point2D at = new javafx.geometry.Point2D(on.getMinX() + 1, on.getCenterY());
            javafx.geometry.Point2D local = second.screenToLocal(at);
            second.getOnContextMenuRequested()
                    .handle(new javafx.scene.input.ContextMenuEvent(
                            javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                            local.getX(),
                            local.getY(),
                            at.getX(),
                            at.getY(),
                            false,
                            null));
        });
        settle();
        try {
            assertTrue(FxTestSupport.callOnFx(menu::isShowing));
            assertSame(second, FxTestSupport.callOnFx(menu::getOwnerNode), "shown on the pane that was clicked");
            javafx.scene.control.MenuItem copy = FxTestSupport.callOnFx(() -> menu.getItems().stream()
                    .filter(i -> com.editora.i18n.Messages.tr("editmenu.copy").equals(i.getText()))
                    .findFirst()
                    .orElseThrow());
            assertFalse(FxTestSupport.callOnFx(copy::isDisable), "pane 2 has a selection");
            FxTestSupport.runOnFx(copy::fire);
            assertEquals(
                    "11",
                    FxTestSupport.callOnFx(() ->
                            javafx.scene.input.Clipboard.getSystemClipboard().getString()),
                    "pane 2's selection (\"11\" of \"line 11\"), not pane 1's (\"line\")");
        } finally {
            FxTestSupport.runOnFx(menu::hide);
        }
    }

    @Test
    void theSecondPaneDrawsSpellAndNoteOverlays() throws Exception {
        EditorBuffer b = open("overlays.txt", lines(10));
        split(b);
        Parent pane2 = FxTestSupport.callOnFx(() -> FxTestSupport.field(b, "root2"));
        Node spell = overlay(pane2, "spellcheck-overlay");
        Node notes = overlay(pane2, "note-highlight-overlay");
        assertNotNull(spell, "pane 2 has its own spell-check overlay");
        assertNotNull(notes, "and its own note overlay");
        Node primarySpell = FxTestSupport.callOnFx(() -> FxTestSupport.field(b, "spellOverlay"));
        Node primaryNotes = FxTestSupport.callOnFx(() -> FxTestSupport.field(b, "noteOverlay"));
        assertEquals(FxTestSupport.callOnFx(primarySpell::isVisible), FxTestSupport.callOnFx(spell::isVisible));
        assertEquals(FxTestSupport.callOnFx(primaryNotes::isVisible), FxTestSupport.callOnFx(notes::isVisible));

        // Switched together with pane 1's, in both directions.
        boolean on = FxTestSupport.callOnFx(primarySpell::isVisible);
        FxTestSupport.runOnFx(() -> b.setSpellCheckEnabled(!on));
        assertEquals(!on, FxTestSupport.callOnFx(spell::isVisible), "spell check toggles in pane 2 as well");
        FxTestSupport.runOnFx(() -> b.setSpellCheckEnabled(on));
        assertEquals(on, FxTestSupport.callOnFx(spell::isVisible));

        // Laid over pane 2's text, not left at 0x0.
        settle();
        CodeArea second = FxTestSupport.callOnFx(() -> FxTestSupport.field(b, "area2"));
        double textWidth = FxTestSupport.callOnFx(
                () -> second.getParent().getBoundsInParent().getWidth());
        assertEquals(
                textWidth,
                FxTestSupport.callOnFx(() -> notes.getBoundsInParent().getWidth()),
                0.5);
        assertEquals(
                textWidth,
                FxTestSupport.callOnFx(() -> spell.getBoundsInParent().getWidth()),
                0.5);
    }

    private static Node overlay(Parent pane, String styleClass) throws Exception {
        return FxTestSupport.callOnFx(() -> pane.getChildrenUnmodifiable().stream()
                .filter(n -> n.getStyleClass().contains(styleClass))
                .findFirst()
                .orElse(null));
    }

    // --- E4-11 (rest): the remaining pane takes over where the user was ---------------------------

    @Test
    void closingTheSplitFromTheSecondPaneCarriesItsCaretAndScrollPositionOver() throws Exception {
        EditorBuffer b = open("carry.txt", lines(400));
        CodeArea first = b.getArea();
        CodeArea second = split(b);
        FxTestSupport.runOnFx(() -> first.moveTo(3, 2));
        focus(b, second);
        FxTestSupport.runOnFx(() -> {
            second.selectRange(300, 1, 300, 6);
            second.showParagraphAtTop(290);
        });
        settle();
        int top = FxTestSupport.callOnFx(second::firstVisibleParToAllParIndex);
        assertTrue(top >= 280, "precondition: pane 2 is scrolled far down: " + top);

        run("view.splitVertical"); // closes the split from pane 2
        settle();
        settle();

        assertEquals(300, (int) FxTestSupport.callOnFx(first::getCurrentParagraph), "pane 2's caret line");
        assertEquals(6, (int) FxTestSupport.callOnFx(first::getCaretColumn));
        assertEquals("ine 3", FxTestSupport.callOnFx(first::getSelectedText), "and its selection");
        assertEquals(
                top, (int) FxTestSupport.callOnFx(first::firstVisibleParToAllParIndex), "and where it was scrolled to");
    }

    @Test
    void closingTheSplitFromTheFirstPaneLeavesItWhereItWas() throws Exception {
        EditorBuffer b = open("stay.txt", lines(400));
        CodeArea first = b.getArea();
        CodeArea second = split(b);
        FxTestSupport.runOnFx(() -> second.moveTo(300, 0));
        focus(b, first);
        FxTestSupport.runOnFx(() -> first.moveTo(3, 2));
        run("view.splitVertical");
        settle();
        assertEquals(3, (int) FxTestSupport.callOnFx(first::getCurrentParagraph));
    }
}
