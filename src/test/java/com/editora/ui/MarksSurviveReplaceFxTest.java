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
}
