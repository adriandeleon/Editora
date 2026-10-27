package com.editora.lsp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.text.LspTextEdit;
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
        return new LspManager.EditBasis(Map.of(), millis, WireUris.LOCAL);
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

    /**
     * lemminx's "Generate 'nogrammar.dtd' and bind with DOCTYPE", verbatim (XML review C1): the new DTD is addressed
     * with {@code version: 0}, which no client ever sent, so it must not be compared — while the XML document keeps
     * its version and request-time text, the guards for the file the user is looking at.
     */
    @Test
    void aFileTheEditCreatesLosesTheServersVersionAndStartsEmpty() {
        Path folder = Path.of("/project/misc").toAbsolutePath();
        Path xml = folder.resolve("nogrammar.xml");
        Path dtd = folder.resolve("nogrammar.dtd");
        var basis = new LspManager.EditBasis(Map.of(xml, LemminxPayloads.NOGRAMMAR_XML), 1_000, WireUris.LOCAL);

        var draft = LspManager.draftWorkspaceEdit(LemminxPayloads.generateDtdAndBind(folder), basis);

        assertNotNull(draft);
        assertTrue(draft.closedTargets().isEmpty(), "the created file is known to be empty, not read from disk");
        assertEquals(
                List.of(dtd),
                draft.mapped().creates().stream().map(c -> c.file()).toList());
        var created = draft.mapped().edits().get(0);
        assertEquals(dtd, created.file());
        assertNull(created.version(), "the server's 0 names a document version this client never sent");
        assertEquals("", created.expectedText());
        var bound = draft.mapped().edits().get(1);
        assertEquals(xml, bound.file());
        assertEquals(1, bound.version());
        assertEquals(LemminxPayloads.NOGRAMMAR_XML, bound.expectedText());
    }

    /** lemminx's "Generate missing file" names its target relatively; the refusal says which URI (XML review B1). */
    @Test
    void aRelativeUriIsNamedAsTheReasonAnEditCannotBePlaced() {
        var edit = new org.eclipse.lsp4j.WorkspaceEdit();
        edit.setDocumentChanges(List.of(org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(
                new org.eclipse.lsp4j.CreateFile("tmp/xmlprobe/nothere.xsd"))));

        assertNull(WorkspaceEditMapper.map(edit));
        assertEquals("tmp/xmlprobe/nothere.xsd", WorkspaceEditMapper.unresolvedUri(edit, WireUris.LOCAL::toPath));
        assertNull(WorkspaceEditMapper.unresolvedUri(
                LemminxPayloads.generateDtdAndBind(Path.of("/project").toAbsolutePath()), WireUris.LOCAL::toPath));
    }
}
