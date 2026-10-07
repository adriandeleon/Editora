package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.command.CommandRegistry;
import com.editora.config.HistoryRevision;
import com.editora.config.PathKeys;
import com.editora.editor.EditorBuffer;
import com.editora.history.HistoryBlobStore;
import com.editora.search.SearchQuery;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A large-file buffer has no undo. A programmatic bulk edit there used to destroy the previous text —
 * unsaved edits included — with nothing to bring it back. {@link NoUndoGuard} stores that text in Local
 * History before the edit, or refuses the edit when it cannot. Driven against the real window.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NoUndoGuardFxTest {

    private static final SearchQuery QUERY = new SearchQuery("old", true, false, false);
    private static final String SAVED = "old one\nold two\nkeep\n";
    private static final String UNSAVED = "typed and not saved\n" + SAVED;

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

    /** A buffer in large-file mode (no undo) over {@code file} (null: untitled), holding an unsaved edit. */
    private EditorBuffer largeBuffer(Path file) throws Exception {
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setLargeFile(true);
            buffer.setContent(SAVED);
            if (file != null) {
                buffer.setPath(file);
            }
            FxTestSupport.call(
                    fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, buffer, true);
            buffer.getArea().insertText(0, "typed and not saved\n");
            return buffer;
        });
        assertFalse(FxTestSupport.callOnFx(() -> b.getArea().isUndoAvailable()), "large-file mode has no undo");
        return b;
    }

    private List<HistoryRevision> revisions(Path file) throws Exception {
        String key = PathKeys.normalizedKey(file);
        return FxTestSupport.callOnFx(() -> {
            for (Map<String, List<HistoryRevision>> bucket :
                    fx.shared.historyByProject().values()) {
                if (bucket.containsKey(key)) {
                    return List.copyOf(bucket.get(key));
                }
            }
            return List.<HistoryRevision>of();
        });
    }

    private String status() throws Exception {
        FxTestSupport.drainFx(); // the guard posts its message after the writer's own status
        return FxTestSupport.callOnFx(() -> {
            StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
            javafx.scene.control.Label echo = FxTestSupport.field(bar, "echo");
            return echo.getText();
        });
    }

    private void setLocalHistory(boolean on) throws Exception {
        FxTestSupport.runOnFx(() -> fx.shared.getSettings().setLocalHistory(on));
    }

    @Test
    void aBulkEditInANoUndoBufferFirstStoresTheTextIncludingUnsavedEdits(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("big.txt"), SAVED);
        EditorBuffer b = largeBuffer(file);

        SearchCoordinator.ClosedReplace result =
                FxTestSupport.callOnFx(() -> SearchCoordinator.replaceOpenBuffer(b, QUERY, "new", false));

        assertTrue(result.changed());
        assertEquals(UNSAVED.replace("old", "new"), FxTestSupport.callOnFx(b::getContent));
        List<HistoryRevision> revisions = revisions(file);
        assertEquals(1, revisions.size(), "one safety copy");
        HistoryRevision copy = revisions.get(0);
        String operation = tr("noUndo.op.replaceInFiles");
        assertEquals(tr("history.label.beforeNoUndoEdit", operation), copy.label(), "named, so retention keeps it");
        // On disk already — not queued: the edit above happened after this was written.
        assertEquals(
                UNSAVED,
                new HistoryBlobStore(fx.shared.getHistoryBlobsDir()).get(copy.sha256()),
                "the copy is the buffer text, unsaved edits included — not the file on disk");
        assertEquals(StatusBar.echoLine(tr("status.noUndo.safetyCopy", operation)), status());
        assertTrue(fx.shared.flushWrites());
        assertTrue(Files.readString(fx.shared.getHistoryFile()).contains(copy.sha256()), "and the index names it");
    }

    @Test
    void withLocalHistoryOffTheEditIsRefusedAndTheTextIsUntouched(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("big.txt"), SAVED);
        EditorBuffer b = largeBuffer(file);
        setLocalHistory(false);
        try {
            SearchCoordinator.ClosedReplace result =
                    FxTestSupport.callOnFx(() -> SearchCoordinator.replaceOpenBuffer(b, QUERY, "new", false));

            assertTrue(result.failed(), "reported with the files that could not be changed");
            assertFalse(result.changed());
            assertEquals(UNSAVED, FxTestSupport.callOnFx(b::getContent));
            assertEquals(
                    StatusBar.echoLine(tr("status.noUndo.cannotHistoryOff", tr("noUndo.op.replaceInFiles"))), status());
            assertTrue(revisions(file).isEmpty());
        } finally {
            setLocalHistory(true);
        }
    }

    @Test
    void anUntitledNoUndoBufferRefusesALineTransform() throws Exception {
        EditorBuffer b = largeBuffer(null);
        FxTestSupport.runOnFx(() -> registry.run("edit.sortLinesAsc"));
        assertEquals(UNSAVED, FxTestSupport.callOnFx(b::getContent), "nowhere to keep a copy: nothing is changed");
        assertEquals(
                StatusBar.echoLine(tr("status.noUndo.cannotNoLocalFile", tr("noUndo.op.lineTransform"))), status());
    }

    @Test
    void aLineTransformInANoUndoFileBufferKeepsACopyAndRuns(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("lines.txt"), SAVED);
        EditorBuffer b = largeBuffer(file);
        FxTestSupport.runOnFx(() -> registry.run("edit.sortLinesAsc"));
        assertEquals("keep\nold one\nold two\ntyped and not saved\n", FxTestSupport.callOnFx(b::getContent));
        List<HistoryRevision> revisions = revisions(file);
        assertEquals(1, revisions.size());
        assertEquals(
                UNSAVED,
                new HistoryBlobStore(fx.shared.getHistoryBlobsDir())
                        .get(revisions.get(0).sha256()));
    }

    @Test
    void anOrdinaryBufferIsEditedWithoutASafetyCopy(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("small.txt"), SAVED);
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setContent(SAVED);
            buffer.setPath(file);
            FxTestSupport.call(
                    fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, buffer, true);
            return buffer;
        });
        assertTrue(FxTestSupport.callOnFx(() -> NoUndoGuard.check(b, "anything") == NoUndoGuard.Verdict.UNDOABLE));
        SearchCoordinator.ClosedReplace result =
                FxTestSupport.callOnFx(() -> SearchCoordinator.replaceOpenBuffer(b, QUERY, "new", false));
        assertTrue(result.changed());
        assertTrue(revisions(file).isEmpty(), "undo is the safety net here");
        FxTestSupport.runOnFx(() -> b.getArea().undo());
        assertEquals(SAVED, FxTestSupport.callOnFx(b::getContent));
    }

    @Test
    void aNoUndoBufferWithoutAnInstalledGuardIsRefused() throws Exception {
        EditorBuffer detached = FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setLargeFile(true);
            buffer.setContent(SAVED);
            return buffer;
        });
        try {
            NoUndoGuard.Verdict verdict = FxTestSupport.callOnFx(() -> NoUndoGuard.check(detached, "Op"));
            assertFalse(verdict.allowed());
            assertFalse(verdict.safetyCopy());
            assertEquals(tr("status.noUndo.cannotCopy", "Op"), verdict.message());
        } finally {
            FxTestSupport.runOnFx(detached::dispose);
        }
    }

    @Test
    void verdictsFollowTheCopyOutcome() {
        assertEquals("status.noUndo.safetyCopy", NoUndoGuard.messageKey(NoUndoGuard.Copy.STORED));
        assertEquals("status.noUndo.cannotHistoryOff", NoUndoGuard.messageKey(NoUndoGuard.Copy.HISTORY_OFF));
        assertEquals("status.noUndo.cannotNoLocalFile", NoUndoGuard.messageKey(NoUndoGuard.Copy.NO_LOCAL_FILE));
        assertEquals("status.noUndo.cannotCopy", NoUndoGuard.messageKey(NoUndoGuard.Copy.FAILED));
        NoUndoGuard.Verdict stored = NoUndoGuard.verdict(NoUndoGuard.Copy.STORED, "Op");
        assertTrue(stored.allowed() && stored.safetyCopy());
        for (NoUndoGuard.Copy failed :
                List.of(NoUndoGuard.Copy.HISTORY_OFF, NoUndoGuard.Copy.NO_LOCAL_FILE, NoUndoGuard.Copy.FAILED)) {
            NoUndoGuard.Verdict verdict = NoUndoGuard.verdict(failed, "Op");
            assertFalse(verdict.allowed(), failed.name());
            assertFalse(verdict.safetyCopy(), failed.name());
            assertTrue(verdict.message().contains("Op"), "the message names the operation");
        }
        assertNull(NoUndoGuard.Verdict.UNDOABLE.message());
    }
}
