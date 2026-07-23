package com.editora.ui;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.editor.EditorBuffer;
import com.editora.editor.QueryReplace;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The interactive query-replace session driven through the real key filters against a wired window. The
 * pure {@code QueryReplaceTest} covers the matching/replacement; this covers the part it cannot — that a
 * typed {@code y}/{@code n}/{@code !} is taken as a command and applied to the document, that the session
 * owns the keys while active and releases them on quit, and that offsets stay correct as edits shift them.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueryReplaceFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private EditorBuffer begin(String content, String query, String replacement) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent(content);
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            b.getArea().moveTo(0);
            FxTestSupport.call(
                    fx.controller,
                    "beginQueryReplace",
                    new Class[] {EditorBuffer.class, QueryReplace.Spec.class},
                    b,
                    new QueryReplace.Spec(query, replacement, false, false, false, false));
            return b;
        });
    }

    /** Sends one command character to the active session (as a real KEY_TYPED event on the area). */
    private void type(EditorBuffer b, char c) throws Exception {
        FxTestSupport.runOnFx(() -> b.getArea()
                .fireEvent(new KeyEvent(
                        KeyEvent.KEY_TYPED, String.valueOf(c), "", KeyCode.UNDEFINED, false, false, false, false)));
    }

    private void press(EditorBuffer b, KeyCode code) throws Exception {
        FxTestSupport.runOnFx(() ->
                b.getArea().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false)));
    }

    private String text(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea().getText());
    }

    private boolean sessionActive() throws Exception {
        return FxTestSupport.callOnFx(() -> FxTestSupport.field(fx.controller, "queryReplaceSession") != null);
    }

    private boolean ownsKeys(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(
                () -> Boolean.TRUE.equals(b.getArea().getProperties().get("editora.ownsKeys")));
    }

    // --- the per-match loop ----------------------------------------------------------------------

    @Test
    void yReplacesAndNSkips() throws Exception {
        EditorBuffer b = begin("foo foo foo", "foo", "bar");
        assertTrue(ownsKeys(b), "the session owns the keys while active");
        type(b, 'y'); // replace the first
        type(b, 'n'); // skip the second
        type(b, 'y'); // replace the third; no more matches → session ends
        assertEquals("bar foo bar", text(b));
        assertFalse(sessionActive(), "the session ended when the matches ran out");
        assertFalse(ownsKeys(b), "and released the keys");
    }

    @Test
    void spaceIsASynonymForYAndBackspaceForN() throws Exception {
        EditorBuffer b = begin("a a a", "a", "X");
        type(b, ' ');
        press(b, KeyCode.BACK_SPACE);
        type(b, ' ');
        assertEquals("X a X", text(b));
    }

    @Test
    void bangReplacesAllRemainingInOneStep() throws Exception {
        EditorBuffer b = begin("x x x x", "x", "y");
        type(b, 'y'); // replace the first interactively
        type(b, '!'); // then all the rest at once
        assertEquals("y y y y", text(b));
        assertFalse(sessionActive());
    }

    @Test
    void dotReplacesThisMatchAndStops() throws Exception {
        EditorBuffer b = begin("one one one", "one", "two");
        type(b, '.');
        assertEquals("two one one", text(b), "only the first match is replaced");
        assertFalse(sessionActive());
    }

    @Test
    void qQuitsWithoutTouchingTheCurrentMatch() throws Exception {
        EditorBuffer b = begin("keep keep", "keep", "gone");
        type(b, 'q');
        assertEquals("keep keep", text(b));
        assertFalse(sessionActive());
    }

    @Test
    void escapeQuits() throws Exception {
        EditorBuffer b = begin("keep keep", "keep", "gone");
        press(b, KeyCode.ESCAPE);
        assertEquals("keep keep", text(b));
        assertFalse(sessionActive());
    }

    // --- correctness under shifting offsets ------------------------------------------------------

    @Test
    void replacementsThatChangeLengthKeepLaterMatchesAligned() throws Exception {
        // "ab" → "wxyz" grows the text; the second match's original offset is stale after the first edit.
        EditorBuffer b = begin("ab cd ab", "ab", "wxyz");
        type(b, 'y');
        type(b, 'y');
        assertEquals("wxyz cd wxyz", text(b));
    }

    @Test
    void aReplacementContainingTheSearchTextDoesNotReMatchItself() throws Exception {
        // Replacing "a" with "aa" must advance past the inserted text, not re-find the new "a"s forever.
        EditorBuffer b = begin("a a", "a", "aa");
        type(b, '!');
        assertEquals("aa aa", text(b), "each original match replaced exactly once");
    }

    @Test
    void searchStartsFromTheCaretNotTheTopOfTheBuffer() throws Exception {
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer buf = new EditorBuffer();
            buf.setContent("foo foo foo");
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, buf, true);
            buf.getArea().moveTo(4); // just before the second foo
            FxTestSupport.call(
                    fx.controller,
                    "beginQueryReplace",
                    new Class[] {EditorBuffer.class, QueryReplace.Spec.class},
                    buf,
                    new QueryReplace.Spec("foo", "X", false, false, false, false));
            return buf;
        });
        type(b, '!');
        assertEquals("foo X X", text(b), "the first foo, behind the caret, is left alone");
    }

    @Test
    void noMatchesFromTheCaretEndsImmediatelyWithoutOwningKeys() throws Exception {
        EditorBuffer b = begin("nothing here", "zzz", "X");
        assertFalse(sessionActive(), "a session with no matches never starts");
        assertFalse(ownsKeys(b));
    }
}
