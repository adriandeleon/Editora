package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.command.CommandRegistry;
import com.editora.config.NoteScope;
import com.editora.config.PersonalNote;
import com.editora.config.TextAnchor;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
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
            FxTestSupport.call(
                    FxTestSupport.field(fx.controller, "fileWorkflows"), "save", new Class[] {EditorBuffer.class}, b);
        });
        String expected = "one\nXtwo\nthree\nfour\nfive";
        for (int i = 0; i < 100 && !expected.equals(Files.readString(file)); i++) {
            Thread.sleep(20);
            FxTestSupport.runOnFx(() -> {});
        }
        assertEquals(
                expected,
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

    // --- gutter marks across the boundary ---

    /** Bookmarks on lines 0, 2 and 4 of {@code DOC}, breakpoints on 1 and 4, and a note on "three". */
    private EditorBuffer marked() throws Exception {
        EditorBuffer b = open(DOC);
        FxTestSupport.runOnFx(() -> {
            for (int line : new int[] {0, 2, 4}) {
                b.toggleBookmark(line);
            }
            b.getBookmarkManager().setNote(2, "keep me");
            b.toggleBreakpoint(1);
            b.toggleBreakpoint(4);
            b.getNoteManager()
                    .add(PersonalNote.create(
                            null, NoteScope.RANGE, new TextAnchor(2, 0, 2, 5, "three", "", ""), "n", List.of()));
            b.getNoteManager()
                    .add(PersonalNote.create(
                            null, NoteScope.RANGE, new TextAnchor(4, 0, 4, 4, "five", "", ""), "n", List.of()));
        });
        return b;
    }

    private void narrowLines1To2(EditorBuffer b) throws Exception {
        FxTestSupport.runOnFx(() -> b.getArea().selectRange(at(DOC, 1, 0), at(DOC, 2, 5)));
        run("edit.narrowToRegion");
    }

    private static List<Integer> bookmarkLines(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> List.copyOf(b.getBookmarkManager().lines()));
    }

    private static List<Integer> breakpointLines(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> List.copyOf(b.getBreakpointManager().lines()));
    }

    private static List<Integer> noteLines(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> List.copyOf(b.getNoteManager().activeLines()));
    }

    @Test
    void whileNarrowedOnlyTheRegionsMarksShowAndTheyAreRegionRelative() throws Exception {
        EditorBuffer b = marked();
        narrowLines1To2(b);
        assertEquals(List.of(1), bookmarkLines(b), "the bookmark on \"three\" is region line 1");
        assertEquals(List.of(0), breakpointLines(b), "the breakpoint on \"two\" is region line 0");
        assertEquals(List.of(1), noteLines(b));
    }

    @Test
    void aNarrowWidenCycleRestoresEveryBookmarkBreakpointAndNote() throws Exception {
        // The two swaps are whole-document replaces; tracked as edits they deleted nearly every mark, and
        // the loss was then persisted. Nothing in the file changed, so nothing may move.
        EditorBuffer b = marked();
        narrowLines1To2(b);
        run("edit.widen");
        assertEquals(DOC, content(b));
        assertEquals(List.of(0, 2, 4), bookmarkLines(b));
        assertEquals(
                "keep me",
                FxTestSupport.callOnFx(
                        () -> b.getBookmarkManager().snapshot().get(1).note()));
        assertEquals(List.of(1, 4), breakpointLines(b));
        assertEquals(List.of(2, 4), noteLines(b));
        assertEquals(
                List.of("three", "five"),
                FxTestSupport.callOnFx(() -> b.getNoteManager().activeSpans().stream()
                        .map(r -> b.getArea().getText(r[0], r[1]))
                        .toList()),
                "each note still covers its own text");
    }

    @Test
    void linesAddedWhileNarrowedMoveTheMarksBelowTheRegion() throws Exception {
        EditorBuffer b = marked();
        narrowLines1To2(b);
        FxTestSupport.runOnFx(() -> b.getArea().insertText(0, "new\nnew\n")); // two lines above "two"
        run("edit.widen");
        assertEquals("one\nnew\nnew\ntwo\nthree\nfour\nfive", content(b));
        assertEquals(List.of(0, 4, 6), bookmarkLines(b));
        assertEquals(List.of(3, 6), breakpointLines(b));
        assertEquals(List.of(4, 6), noteLines(b));
    }

    @Test
    void reNarrowingFromANarrowedBufferStillKeepsEveryMark() throws Exception {
        EditorBuffer b = marked();
        narrowLines1To2(b);
        FxTestSupport.runOnFx(() -> b.getArea().selectRange(0, 3)); // "two", measured in the region
        run("edit.narrowToRegion");
        assertEquals("two", visible(b));
        run("edit.widen");
        assertEquals(List.of(0, 2, 4), bookmarkLines(b));
        assertEquals(List.of(1, 4), breakpointLines(b));
        assertEquals(List.of(2, 4), noteLines(b));
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

    private String status() throws Exception {
        FxTestSupport.drainFx(); // the notice is posted after the command's own status
        return FxTestSupport.callOnFx(() -> {
            StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
            javafx.scene.control.Label echo = FxTestSupport.field(bar, "echo");
            return echo.getText();
        });
    }

    @Test
    void droppingANonEmptyUndoHistoryAtTheBoundaryIsSaidInTheStatusLine() throws Exception {
        EditorBuffer b = open(DOC);
        FxTestSupport.runOnFx(() -> {
            b.getArea().appendText("!"); // an edit made before the boundary: something to lose
            b.getArea().selectRange(at(DOC, 1, 0), at(DOC, 2, 5));
        });
        run("edit.narrowToRegion");
        assertEquals(
                StatusBar.echoLine(com.editora.i18n.Messages.tr("status.narrow.narrowedHistoryCleared")), status());

        FxTestSupport.runOnFx(() -> b.getArea().insertText(0, "Y"));
        run("edit.widen");
        assertEquals(StatusBar.echoLine(com.editora.i18n.Messages.tr("status.narrow.widenedHistoryCleared")), status());
        assertFalse(FxTestSupport.callOnFx(() -> b.getArea().isUndoAvailable()), "the pinned behaviour stands");
    }

    @Test
    void narrowingAFreshBufferSaysNothingAboutUndoHistory() throws Exception {
        EditorBuffer b = narrowed(); // nothing was edited before the boundary
        assertEquals(StatusBar.echoLine(com.editora.i18n.Messages.tr("status.narrow.narrowed")), status());
        run("edit.widen");
        assertEquals(StatusBar.echoLine(com.editora.i18n.Messages.tr("status.narrow.widened")), status());
        assertEquals(DOC, content(b));
    }

    @Test
    void aWriterThatForcesAWidenDoesNotDropTheHistorySilently() throws Exception {
        EditorBuffer b = narrowed();
        FxTestSupport.runOnFx(() -> {
            b.getArea().insertText(0, "X"); // an edit made while narrowed
            // What Replace in Files, a lint fix or an agent write does: whole-document text, so it widens first.
            b.replaceWholeDocument(DOC.replace("four", "FOUR"));
            StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
            bar.setMessage("Replaced 1 occurrence"); // the writer's own status, set right after the edit
        });
        assertEquals(StatusBar.echoLine(com.editora.i18n.Messages.tr("status.narrow.widenedHistoryCleared")), status());
        assertEquals(DOC.replace("four", "FOUR"), content(b));
        FxTestSupport.runOnFx(() -> b.getArea().undo());
        assertEquals("one\nXtwo\nthree\nfour\nfive", content(b), "the replacement itself is still one undo step");
    }

    @Test
    void theSecondSplitViewCannotUndoAcrossTheBoundaryEither() throws Exception {
        // Each split view keeps its own undo stack over the shared document. Only the primary's was cleared,
        // so Undo in the second view replayed the swap — the whole file back inside the held prefix/suffix.
        EditorBuffer b = open(DOC);
        FxTestSupport.runOnFx(() -> {
            b.setSplit(EditorBuffer.Split.SIDE_BY_SIDE);
            CodeArea second = FxTestSupport.field(b, "area2");
            second.insertText(0, "X"); // an entry on the second view's stack, from before narrowing
            second.deleteText(0, 1);
            b.getArea().selectRange(at(DOC, 1, 0), at(DOC, 2, 5));
        });
        run("edit.narrowToRegion");
        CodeArea second = FxTestSupport.callOnFx(() -> FxTestSupport.field(b, "area2"));
        assertFalse(FxTestSupport.callOnFx(second::isUndoAvailable), "narrowing cleared the second view's history");
        FxTestSupport.runOnFx(second::undo);
        assertEquals("two\nthree", visible(b));
        assertEquals(DOC, content(b));

        FxTestSupport.runOnFx(() -> second.insertText(0, "Y"));
        run("edit.widen");
        assertFalse(FxTestSupport.callOnFx(second::isUndoAvailable), "and widening cleared it again");
        FxTestSupport.runOnFx(second::undo);
        assertEquals("one\nYtwo\nthree\nfour\nfive", content(b));
        FxTestSupport.runOnFx(() -> b.setSplit(EditorBuffer.Split.NONE));
    }

    @Test
    void undoHistoryCheckpointsDoNotSurviveTheBoundary() throws Exception {
        // A checkpoint is a whole-text snapshot of the AREA. One taken before narrowing holds the whole file;
        // restored into the narrowed area it would be saved between the held prefix and suffix — the file
        // twice over. One taken while narrowed holds only the region; restored after widening it truncates.
        EditorBuffer b = open(DOC);
        FxTestSupport.runOnFx(b::captureUndoCheckpoint);
        assertFalse(FxTestSupport.callOnFx(() -> b.getUndoHistory().isEmpty()), "precondition: a checkpoint exists");
        FxTestSupport.runOnFx(() -> b.getArea().selectRange(at(DOC, 1, 0), at(DOC, 2, 5)));
        run("edit.narrowToRegion");
        assertTrue(FxTestSupport.callOnFx(() -> b.getUndoHistory().isEmpty()), "narrowing dropped the checkpoints");

        FxTestSupport.runOnFx(b::captureUndoCheckpoint); // a region-only snapshot
        assertFalse(FxTestSupport.callOnFx(() -> b.getUndoHistory().isEmpty()));
        run("edit.widen");
        assertTrue(FxTestSupport.callOnFx(() -> b.getUndoHistory().isEmpty()), "widening dropped them too");
        assertEquals(DOC, content(b));
    }
}
