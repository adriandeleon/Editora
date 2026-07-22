package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Narrowing, driven through the real {@link CommandRegistry} against a wired window.
 *
 * <p>Narrowing really does replace the document text with the region, so the tests that matter most here
 * are the <b>data-integrity</b> ones: while narrowed, everything that means "the file" — saving above all,
 * but also autosave, diff, local history, find-in-files and the plugin API, all of which read
 * {@link EditorBuffer#getContent()} — must still see the whole document. If that inversion ever regresses,
 * a narrowed buffer silently truncates the user's file on the next save.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NarrowingFxTest {

    private static final String DOC = "one\ntwo\nthree\nfour\nfive";

    private FxWindowFixture fx;
    private CommandRegistry registry;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        registry = FxTestSupport.field(fx.controller, "registry");
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private void run(String id) throws Exception {
        FxTestSupport.runOnFx(() -> registry.run(id));
    }

    private static int at(String text, int line, int col) {
        int off = 0;
        for (int i = 0; i < line; i++) {
            off = text.indexOf('\n', off) + 1;
        }
        return off + col;
    }

    private EditorBuffer open(String content) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent(content);
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            return b;
        });
    }

    /** Opens {@code DOC} narrowed to the "two\nthree" region. */
    private EditorBuffer narrowed() throws Exception {
        EditorBuffer b = open(DOC);
        FxTestSupport.runOnFx(() -> b.getArea().selectRange(at(DOC, 1, 0), at(DOC, 2, 5)));
        run("edit.narrowToRegion");
        return b;
    }

    private String visible(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea().getText());
    }

    private String content(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(b::getContent);
    }

    // --- data integrity: the whole point ---------------------------------------------------------

    @Test
    void narrowingHidesTheRestFromTheAreaButNotFromTheFile() throws Exception {
        EditorBuffer b = narrowed();
        assertTrue(FxTestSupport.callOnFx(b::isNarrowed));
        assertEquals("two\nthree", visible(b), "the area holds only the region");
        assertEquals(DOC, content(b), "getContent still means the whole document");
    }

    @Test
    void savingWhileNarrowedWritesTheWholeFile() throws Exception {
        Path file = Files.createTempFile("narrow", ".txt");
        Files.writeString(file, DOC);
        EditorBuffer b = open(DOC);
        FxTestSupport.runOnFx(() -> {
            b.setPath(file);
            b.getArea().selectRange(at(DOC, 1, 0), at(DOC, 2, 5));
        });
        run("edit.narrowToRegion");
        assertEquals("two\nthree", visible(b), "precondition: really narrowed");

        FxTestSupport.runOnFx(() -> {
            b.getArea().insertText(0, "X");
            FxTestSupport.call(fx.controller, "save", new Class[] {EditorBuffer.class}, b);
        });
        assertEquals(
                "one\nXtwo\nthree\nfour\nfive",
                Files.readString(file),
                "a save while narrowed must not truncate the file to the visible region");
        Files.deleteIfExists(file);
    }

    @Test
    void editingWhileNarrowedKeepsTheHiddenTextIntactOnWiden() throws Exception {
        EditorBuffer b = narrowed();
        FxTestSupport.runOnFx(() -> b.getArea().insertText(0, "X"));
        run("edit.widen");
        assertEquals("one\nXtwo\nthree\nfour\nfive", visible(b));
        assertFalse(FxTestSupport.callOnFx(b::isNarrowed));
    }

    @Test
    void aCleanBufferStaysCleanAcrossNarrowAndWiden() throws Exception {
        EditorBuffer b = open(DOC);
        FxTestSupport.runOnFx(b::markClean);
        FxTestSupport.runOnFx(() -> b.getArea().selectRange(at(DOC, 1, 0), at(DOC, 2, 5)));
        run("edit.narrowToRegion");
        assertFalse(FxTestSupport.callOnFx(b::isDirty), "narrowing is not an edit to the file");
        run("edit.widen");
        assertFalse(FxTestSupport.callOnFx(b::isDirty), "and neither is widening");
    }

    @Test
    void aWholeDocumentWriteWidensRatherThanDuplicatingTheFile() throws Exception {
        // The find-and-replace-across-files shape: compute from getContent() (the whole document),
        // then write it back. Against a narrowed area that would strand the hidden text alongside it.
        EditorBuffer b = narrowed();
        String replaced = content(b).replace("three", "THREE");
        FxTestSupport.runOnFx(() -> b.replaceWholeDocument(replaced));
        assertFalse(FxTestSupport.callOnFx(b::isNarrowed), "the write widened the buffer");
        assertEquals("one\ntwo\nTHREE\nfour\nfive", content(b), "and the file is not duplicated");
    }

    @Test
    void reloadingFromDiskWidens() throws Exception {
        EditorBuffer b = narrowed();
        FxTestSupport.runOnFx(() -> b.setContent("replaced\ncontent"));
        assertFalse(FxTestSupport.callOnFx(b::isNarrowed));
        assertEquals("replaced\ncontent", content(b));
    }

    // --- behaviour ---------------------------------------------------------------------------------

    @Test
    void narrowingTwiceMeasuresAgainstTheWholeDocumentNotTheRegion() throws Exception {
        EditorBuffer b = narrowed();
        FxTestSupport.runOnFx(() -> b.getArea().selectRange(0, 3)); // "two" within the region
        run("edit.narrowToRegion");
        assertEquals("two", visible(b));
        run("edit.widen");
        assertEquals(DOC, visible(b), "widening once restores the whole document, not the previous region");
    }

    @Test
    void widenWithoutNarrowingIsANoOp() throws Exception {
        EditorBuffer b = open(DOC);
        run("edit.widen");
        assertEquals(DOC, content(b));
    }

    @Test
    void narrowingWithNoSelectionIsRefused() throws Exception {
        EditorBuffer b = open(DOC);
        FxTestSupport.runOnFx(() -> b.getArea().moveTo(4));
        run("edit.narrowToRegion");
        assertFalse(FxTestSupport.callOnFx(b::isNarrowed));
        assertEquals(DOC, visible(b));
    }

    @Test
    void undoHistoryIsDroppedAtTheBoundarySoItCannotDuplicateTheDocument() throws Exception {
        // Undoing the narrowing swap would restore the whole document into the narrowed area while the
        // hidden text is still held aside. The history is cleared instead; this pins that it is not
        // reachable rather than merely unlikely.
        EditorBuffer b = narrowed();
        assertFalse(
                FxTestSupport.callOnFx(() -> b.getArea().isUndoAvailable()),
                "no undo entry may span the narrowing boundary");
        FxTestSupport.runOnFx(() -> b.getArea().insertText(0, "X"));
        assertTrue(FxTestSupport.callOnFx(() -> b.getArea().isUndoAvailable()), "edits while narrowed undo normally");
        FxTestSupport.runOnFx(() -> b.getArea().undo());
        assertEquals("two\nthree", visible(b));
        assertEquals(DOC, content(b), "and the hidden text is untouched throughout");
    }
}
