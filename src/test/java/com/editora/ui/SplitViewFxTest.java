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
import static org.junit.jupiter.api.Assertions.assertNull;
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
            assertNull(FxTestSupport.callOnFx(second::getParagraphGraphicFactory), "line numbers are off");
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
}
