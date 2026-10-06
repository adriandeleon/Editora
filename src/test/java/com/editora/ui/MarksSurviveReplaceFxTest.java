package com.editora.ui;

import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import com.editora.config.NoteScope;
import com.editora.config.PersonalNote;
import com.editora.config.TextAnchor;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Bookmarks, breakpoints and notes through a <em>multi-line replace</em> on a real {@link EditorBuffer}.
 *
 * <p>The managers used to treat every removed newline as a deleted line whatever was inserted in its place,
 * so one ranged replace — which is what in-file Replace All, a formatter edit, a history restore and a diff
 * apply all are — deleted the marks inside it, persisted the loss and told a live debug session the
 * breakpoints were gone.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MarksSurviveReplaceFxTest {

    private static final String DOC = "zero\nfoo one\nfoo two\nfoo three\nfour\nfoo five\nsix";

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static EditorBuffer buffer(String text) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent(text);
            new Scene(new VBox(b.getNode()), 800, 600);
            return b;
        });
    }

    private static List<Integer> bookmarks(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> List.copyOf(b.getBookmarkManager().lines()));
    }

    private static List<Integer> breakpoints(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> List.copyOf(b.getBreakpointManager().lines()));
    }

    @Test
    void oneRangedReplaceWithTheSameLineCountKeepsTheMarksInsideIt() throws Exception {
        EditorBuffer b = buffer(DOC);
        FxTestSupport.runOnFx(() -> {
            b.toggleBookmark(2);
            b.toggleBookmark(3);
            b.toggleBreakpoint(2);
            b.toggleBreakpoint(4);
            int from = DOC.indexOf("foo one");
            int to = DOC.indexOf("foo five") + 3;
            b.getArea().replaceText(from, to, DOC.substring(from, to).replace("foo", "bar"));
        });
        assertEquals(List.of(2, 3), bookmarks(b));
        assertEquals(List.of(2, 4), breakpoints(b));
    }

    @Test
    void replaceAllInTheFindBarKeepsTheMarksBetweenItsFirstAndLastMatch() throws Exception {
        EditorBuffer b = buffer(DOC);
        FxTestSupport.runOnFx(() -> {
            b.toggleBookmark(2);
            b.toggleBookmark(4); // a line with no match, inside the replaced span
            b.toggleBreakpoint(3);
            FindReplaceBar bar = new FindReplaceBar(() -> b, status -> {});
            FxTestSupport.<TextField>field(bar, "findField").setText("foo");
            FxTestSupport.<TextField>field(bar, "replaceField").setText("bar");
            bar.replaceAllMatches();
        });
        assertEquals(DOC.replace("foo", "bar"), FxTestSupport.callOnFx(b::getContent));
        assertEquals(List.of(2, 4), bookmarks(b));
        assertEquals(List.of(3), breakpoints(b));
    }

    @Test
    void aWholeDocumentReplaceThatAddsLinesMovesTheMarksWithTheirText() throws Exception {
        EditorBuffer b = buffer(DOC);
        FxTestSupport.runOnFx(() -> {
            b.toggleBookmark(2); // "foo two"
            b.toggleBookmark(4); // "four"
            b.toggleBreakpoint(5); // "foo five"
            // What a formatter, a history restore or a diff apply does: the same lines, two more above.
            b.replaceWholeDocument("// header\n\n" + DOC);
        });
        assertEquals(List.of(4, 6), bookmarks(b));
        assertEquals(List.of(7), breakpoints(b));
    }

    @Test
    void deletingTheMarkedLinesStillRemovesTheirMarks() throws Exception {
        EditorBuffer b = buffer(DOC);
        FxTestSupport.runOnFx(() -> {
            b.toggleBookmark(2);
            b.toggleBookmark(5);
            b.getArea().deleteText(DOC.indexOf("foo one"), DOC.indexOf("four")); // lines 1..3
        });
        assertEquals(List.of(2), bookmarks(b), "line 2's bookmark went with its line; line 5's moved up to 2");
    }

    @Test
    void aNoteInsideAReplacedSpanStaysOnItsText() throws Exception {
        EditorBuffer b = buffer(DOC);
        List<String> noted = FxTestSupport.callOnFx(() -> {
            b.getNoteManager()
                    .add(PersonalNote.create(
                            null, NoteScope.RANGE, new TextAnchor(4, 0, 4, 4, "four", "", ""), "n", List.of()));
            int from = DOC.indexOf("foo one");
            int to = DOC.indexOf("foo five") + 3;
            // Same lines, each "foo" now two characters longer: "four" moves 6 characters right.
            b.getArea().replaceText(from, to, DOC.substring(from, to).replace("foo", "fooxx"));
            return b.getNoteManager().activeSpans().stream()
                    .map(r -> b.getArea().getText(r[0], r[1]))
                    .toList();
        });
        assertEquals(List.of("four"), noted);
    }

    // --- a batch of edits: Format Document, a workspace edit, undo of a multi-caret edit -----------

    private static final String FORMAT_DOC = "class A {\n" + "s1();\n".repeat(6)
            + "    keep1();\n    keep2();\n    wrapped(a,\n        b);\n    after();\n}\n";

    @Test
    void aMultiEditFormatLeavesTheMarksOnUntouchedLinesAlone() throws Exception {
        // Six re-indents (each lengthens a line above) and one edit joining the wrapped call below them.
        // The batch is applied bottom-to-top and reported only afterwards, each change with the offset it
        // had when it ran — so the join used to be resolved several lines too high and dragged the marks
        // between the two up by one.
        EditorBuffer b = buffer(FORMAT_DOC);
        FxTestSupport.runOnFx(() -> {
            b.toggleBookmark(7); // keep1();
            b.toggleBreakpoint(8); // keep2();
            b.toggleBreakpoint(11); // after();
            List<com.editora.editor.LspTextEdit> edits = new java.util.ArrayList<>();
            for (int line = 1; line <= 6; line++) {
                edits.add(new com.editora.editor.LspTextEdit(line, 0, line, 0, "        "));
            }
            edits.add(new com.editora.editor.LspTextEdit(9, 14, 10, 8, " ")); // "wrapped(a, b);"
            b.applyLspEdits(edits);
        });
        assertEquals(
                "    wrapped(a, b);",
                FxTestSupport.callOnFx(() -> b.getArea().getParagraph(9).getText()));
        assertEquals(List.of(7), bookmarks(b), "keep1(); was not touched");
        assertEquals(List.of(8, 10), breakpoints(b), "keep2(); stays, after(); moves up with the join");
    }

    @Test
    void undoingAMultiCaretEditPutsEveryMarkBack() throws Exception {
        String doc = "l0\nl1\nl2\nl3\nl4\nl5\nl6\nl7\n";
        EditorBuffer b = buffer(doc);
        FxTestSupport.runOnFx(() -> {
            for (int line : new int[] {1, 2, 4, 5, 6}) {
                b.toggleBookmark(line);
            }
            // One batch: a newline at the start of lines 1, 4 and 6 (as three carets pressing Enter).
            var area = b.getArea();
            var batch = area.createMultiChange(3);
            for (int line : new int[] {1, 4, 6}) {
                int at = area.getAbsolutePosition(line, 0);
                batch.replaceTextAbsolutely(at, at, "\n");
            }
            batch.commit();
        });
        assertEquals(List.of(2, 3, 6, 7, 9), bookmarks(b));
        FxTestSupport.runOnFx(() -> b.getArea().undo());
        assertEquals(doc, FxTestSupport.callOnFx(b::getContent));
        assertEquals(List.of(1, 2, 4, 5, 6), bookmarks(b));
    }

    // --- marks follow their statement ---------------------------------------------------------------

    @Test
    void movingALineKeepsItsMarksOnIt() throws Exception {
        EditorBuffer b = buffer(DOC);
        FxTestSupport.runOnFx(() -> {
            b.toggleBookmark(1); // "foo one"
            b.toggleBreakpoint(1);
            // What edit.moveLineDown does: one ranged replace of the two lines, swapped.
            int from = DOC.indexOf("foo one");
            int to = DOC.indexOf("foo three") - 1;
            b.getArea().replaceText(from, to, "foo two\nfoo one");
        });
        assertEquals(List.of(2), bookmarks(b));
        assertEquals(List.of(2), breakpoints(b));
    }

    @Test
    void deletingToTheEndOfAMarkedLineDropsItsMark() throws Exception {
        EditorBuffer b = buffer(DOC);
        FxTestSupport.runOnFx(() -> {
            b.toggleBreakpoint(2); // "foo two"
            // Selection from the end of "foo one" to the end of "foo two".
            b.getArea().deleteText(DOC.indexOf("\nfoo two"), DOC.indexOf("\nfoo three"));
        });
        assertEquals(List.of(), breakpoints(b), "the breakpoint must not reappear on 'foo one'");
    }

    @Test
    void pastingOverMarkedLinesLeavesTheLineBelowUnmarked() throws Exception {
        EditorBuffer b = buffer(DOC);
        FxTestSupport.runOnFx(() -> {
            b.toggleBreakpoint(1);
            b.toggleBreakpoint(2);
            // Lines 1..3 selected whole (to the start of "four"), one line pasted over them.
            b.getArea().replaceText(DOC.indexOf("foo one"), DOC.indexOf("four"), "pasted\n");
        });
        assertEquals(List.of(1), breakpoints(b), "'four' was never marked and was not edited");
    }

    @Test
    void editingAMarkedLineRefreshesItsStoredText() throws Exception {
        EditorBuffer b = buffer(DOC);
        java.util.concurrent.atomic.AtomicInteger changes = new java.util.concurrent.atomic.AtomicInteger();
        String stored = FxTestSupport.callOnFx(() -> {
            b.toggleBreakpoint(2); // "foo two"
            b.setOnBreakpointsChanged(changes::incrementAndGet);
            int at = DOC.indexOf("foo two");
            b.getArea().replaceText(at, at + 3, "bar");
            b.getArea().insertText(at + 3, "!");
            return b.getBreakpointManager().snapshot().get(0).lineText();
        });
        // A stale snapshot is what moved the breakpoint to another line with the old text on reopen.
        assertEquals("bar! two", stored);
        assertEquals(1, changes.get(), "reported once for the persist, not once per keystroke");
    }
}
