package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.editora.editor.BlameInfo;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Blame annotations and change bars are keyed by line of the file on disk. With unsaved edits the buffer's
 * lines are numbered differently, and every annotation below an inserted or deleted line used to sit on the
 * wrong line — a click on a blame entry opened another line's commit.
 */
@Tag("fx")
class GitGutterFollowsEditsFxTest {

    private static final String TEXT = "zero\none\ntwo\nthree\nfour\n";

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static EditorBuffer buffer() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent(TEXT);
            List<BlameInfo> blame = new ArrayList<>();
            for (String line : List.of("zero", "one", "two", "three", "four")) {
                blame.add(new BlameInfo("Ann", "2026-01-01", line, "", "hash-" + line));
            }
            b.setBlame(blame);
            b.setChangeBars(Map.of(3, "git-modified"), Map.of(3, "-old three\n+three"));
            return b;
        });
    }

    private static String bar(EditorBuffer b, int line) throws Exception {
        Object gutter = FxTestSupport.field(b, "gitLines");
        return FxTestSupport.callOnFx(
                () -> (String) FxTestSupport.call(gutter, "barAt", new Class<?>[] {int.class}, line));
    }

    private static void settle() throws Exception {
        FxTestSupport.runOnFx(() -> {}); // let the deferred "buffer is clean again" reset run
        FxTestSupport.runOnFx(() -> {});
    }

    @Test
    void anInsertedLineShiftsBlameAndChangeBarsBelowIt() throws Exception {
        EditorBuffer b = buffer();
        assertEquals("hash-three", FxTestSupport.callOnFx(() -> b.blameHashAt(3)));
        assertEquals("git-modified", bar(b, 3));

        FxTestSupport.runOnFx(() -> b.getArea().insertText(0, "new first line\n"));

        assertNull(FxTestSupport.callOnFx(() -> b.blameHashAt(0)), "the typed line belongs to no commit");
        assertEquals("hash-zero", FxTestSupport.callOnFx(() -> b.blameHashAt(1)));
        assertEquals("hash-three", FxTestSupport.callOnFx(() -> b.blameHashAt(4)), "not the commit of line 'four'");
        assertNull(bar(b, 3));
        assertEquals("git-modified", bar(b, 4), "the bar moved with its line");
    }

    @Test
    void aDeletedLinePullsThemUp() throws Exception {
        EditorBuffer b = buffer();
        FxTestSupport.runOnFx(() -> b.getArea().deleteText(0, "zero\none\n".length()));

        assertEquals("hash-two", FxTestSupport.callOnFx(() -> b.blameHashAt(0)));
        assertEquals("hash-three", FxTestSupport.callOnFx(() -> b.blameHashAt(1)));
        assertEquals("git-modified", bar(b, 1));
    }

    @Test
    void freshDataForTheSameDiskFileIsStillPlacedThroughTheUnsavedEdits() throws Exception {
        EditorBuffer b = buffer();
        FxTestSupport.runOnFx(() -> b.getArea().insertText(0, "new first line\n"));
        // A refresh while the buffer is dirty (tab switch, window focus) re-reads the file on disk.
        FxTestSupport.runOnFx(() -> b.setChangeBars(Map.of(1, "git-added"), Map.of()));

        assertEquals("git-added", bar(b, 2), "disk line 1 is buffer line 2");
        assertNull(bar(b, 1));
    }

    @Test
    void undoingBackToTheSavedTextOrSavingRestoresThePlainMapping() throws Exception {
        EditorBuffer b = buffer();
        FxTestSupport.runOnFx(() -> b.getArea().insertText(0, "new first line\n"));
        assertTrue(FxTestSupport.callOnFx(b::isDirty));
        FxTestSupport.runOnFx(() -> b.getArea().deleteText(0, "new first line\n".length()));
        settle();
        assertFalse(FxTestSupport.callOnFx(b::isDirty));
        assertEquals("hash-three", FxTestSupport.callOnFx(() -> b.blameHashAt(3)));

        // A whole-document rewrite cannot be followed line by line: nothing is annotated…
        FxTestSupport.runOnFx(() -> b.replaceWholeDocument("a\nb\nc\nd\ne\nf\n"));
        assertNull(FxTestSupport.callOnFx(() -> b.blameHashAt(2)), "rather than a stale annotation");
        // …until the buffer is saved: its lines are the disk's lines again.
        FxTestSupport.runOnFx(b::markClean);
        settle();
        assertEquals("hash-two", FxTestSupport.callOnFx(() -> b.blameHashAt(2)));
    }

    @Test
    void aNarrowedBufferLooksItsLinesUpInTheWholeDocument() throws Exception {
        EditorBuffer b = buffer();
        FxTestSupport.runOnFx(() -> b.narrowTo(TEXT.indexOf("two"), TEXT.indexOf("four")));
        assertEquals("two\nthree\n", FxTestSupport.callOnFx(() -> b.getArea().getText()));
        assertEquals("hash-two", FxTestSupport.callOnFx(() -> b.blameHashAt(0)), "region line 0 is file line 2");

        FxTestSupport.runOnFx(b::widen);
        assertEquals("hash-two", FxTestSupport.callOnFx(() -> b.blameHashAt(2)), "narrowing is not an edit");
        assertEquals("git-modified", bar(b, 3));
    }
}
