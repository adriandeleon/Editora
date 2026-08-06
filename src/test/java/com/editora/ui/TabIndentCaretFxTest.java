package com.editora.ui;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tab inside a block puts the caret where typing starts, even when the line is already indented.
 *
 * <p>Pressing Enter after {@code {} leaves the new line already carrying its indent, so Tab has no text to
 * add. Coming back to column 0 (C-a, Home, a click) and pressing Tab therefore did <em>nothing at all</em>:
 * the keystroke was consumed by the "don't pile on indentation" guard and the caret stayed at the start of
 * the line, which reads as Tab being broken.
 *
 * <p>Fires a real TAB through the buffer's own key filters — the pure {@code Indenter} test cannot show that
 * the keystroke reaches the indent logic rather than being taken by an earlier filter.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TabIndentCaretFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static void tab(EditorBuffer b) {
        b.getArea().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, false, false, false, false));
    }

    /** Loads {@code content} as Java, puts the caret at {@code caret}, presses Tab, returns {text, caret}. */
    private static Object[] pressTab(String content, int caret) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("java");
            b.setContent(content);
            b.getNode();
            b.getArea().moveTo(caret);
            tab(b);
            return new Object[] {b.getArea().getText(), b.getArea().getCaretPosition()};
        });
    }

    /** The state Enter-inside-a-block leaves: a blank body line already carrying its 8-space indent. */
    private static final String IN_BLOCK = "public class C {\n    public int totalUnits() {\n        \n    }\n}\n";

    private static final int BODY_LINE_START = IN_BLOCK.indexOf("() {\n") + "() {\n".length();

    @Test
    void tabFromColumnZeroMovesToTheIndentWithoutChangingTheText() throws Exception {
        Object[] r = pressTab(IN_BLOCK, BODY_LINE_START);
        assertEquals(IN_BLOCK, r[0], "the line was already indented — Tab must not add more");
        assertEquals(BODY_LINE_START + 8, r[1], "the caret should sit where typing starts");
    }

    @Test
    void tabIsStillATrueNoOpOnceTheCaretIsAtTheIndent() throws Exception {
        Object[] r = pressTab(IN_BLOCK, BODY_LINE_START + 8);
        assertEquals(IN_BLOCK, r[0]);
        assertEquals(BODY_LINE_START + 8, r[1], "repeated Tab must not pile on indentation");
    }

    @Test
    void tabOnAnUnindentedBlankLineStillInsertsTheIndent() throws Exception {
        // The other half of the same branch: nothing there yet, so Tab adds the block's indent.
        String bare = "public class C {\n    public int totalUnits() {\n\n    }\n}\n";
        int lineStart = bare.indexOf("() {\n") + "() {\n".length();
        Object[] r = pressTab(bare, lineStart);
        assertEquals("public class C {\n    public int totalUnits() {\n        \n    }\n}\n", r[0]);
        assertEquals(lineStart + 8, r[1]);
    }
}
