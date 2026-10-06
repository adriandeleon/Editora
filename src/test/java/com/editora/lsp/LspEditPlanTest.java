package com.editora.lsp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.editor.LspTextEdit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure decisions behind closed-file workspace edits: when is the file on disk a trustworthy preimage? */
class LspEditPlanTest {

    private static final Path CLOSED = Path.of("/project/B.java");
    private static final Path OTHER = Path.of("/project/C.java");

    private static WorkspaceEditMapper.Mapped mapped(Path... files) {
        List<WorkspaceEditMapper.FileEdit> edits = new java.util.ArrayList<>();
        for (Path file : files) {
            edits.add(new WorkspaceEditMapper.FileEdit(file, List.of(new LspTextEdit(0, 0, 0, 1, "x")), 3, null));
        }
        return new WorkspaceEditMapper.Mapped(edits, List.of());
    }

    private static LspManager.EditBasis sentAt(long millis) {
        return new LspManager.EditBasis(Map.of(), millis);
    }

    @Test
    void aFileUntouchedSinceBeforeTheRequestIsItsOwnPreimage() {
        assertTrue(WorkspaceEditMapper.unchangedSince(1_000_123, 1_000_124));
    }

    @Test
    void aFileModifiedAtOrAfterTheRequestIsNot() {
        assertFalse(WorkspaceEditMapper.unchangedSince(1_000_123, 1_000_123), "same instant: cannot be shown");
        assertFalse(WorkspaceEditMapper.unchangedSince(1_000_500, 1_000_123));
    }

    @Test
    void anUnknownRequestTimeOrAMissingFileProvesNothing() {
        assertFalse(WorkspaceEditMapper.unchangedSince(1_000_123, 0));
        assertFalse(WorkspaceEditMapper.unchangedSince(-1, 5_000_000));
    }

    /** A whole-second timestamp may be a truncated later write, so it needs a margin before the request. */
    @Test
    void aCoarseTimestampNeedsAMargin() {
        long coarse = 1_000_000;
        assertFalse(WorkspaceEditMapper.unchangedSince(coarse, coarse + 500));
        assertFalse(WorkspaceEditMapper.unchangedSince(
                coarse, coarse + WorkspaceEditMapper.COARSE_TIMESTAMP_MARGIN_MILLIS));
        assertTrue(WorkspaceEditMapper.unchangedSince(
                coarse, coarse + WorkspaceEditMapper.COARSE_TIMESTAMP_MARGIN_MILLIS + 1));
    }

    @Test
    void closedTargetsAreMarkedWithTheRequestTimeAndLoseAMeaninglessVersion() {
        var plan =
                LspManager.completeWorkspaceEdit(mapped(CLOSED), List.of(CLOSED), sentAt(2_000_001), file -> 1_000_001);

        assertNotNull(plan.mapped());
        var edit = plan.mapped().edits().get(0);
        assertEquals(2_000_001L, edit.diskPreimageAt());
        assertNull(edit.version(), "the server never had this document open; its version means nothing here");
    }

    @Test
    void everyBlockingFileIsReportedAndNothingIsApplied() {
        var plan = LspManager.completeWorkspaceEdit(
                mapped(CLOSED, OTHER),
                List.of(CLOSED, OTHER),
                sentAt(2_000_001),
                file -> file.equals(CLOSED) ? 3_000_001 : -1);

        assertNull(plan.mapped());
        assertEquals(List.of(CLOSED, OTHER), plan.blocked());
    }

    @Test
    void anEditWithoutARequestTimeCannotTouchAClosedFile() {
        var plan = LspManager.completeWorkspaceEdit(
                mapped(CLOSED), List.of(CLOSED), LspManager.EditBasis.UNKNOWN, file -> 1_000_001);

        assertNull(plan.mapped());
        assertEquals(List.of(CLOSED), plan.blocked());
    }

    @Test
    void anEditWithNoClosedTargetsIsPassedThroughUntouched() {
        var original = mapped(CLOSED);
        var plan = LspManager.completeWorkspaceEdit(original, List.of(), sentAt(0), file -> -1);
        assertEquals(original, plan.mapped());
        assertTrue(plan.blocked().isEmpty());
    }
}
