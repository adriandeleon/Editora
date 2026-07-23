package com.editora.lsp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.eclipse.lsp4j.CreateFile;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ResourceOperation;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link WorkspaceEditMapper}: both wire shapes map to per-file batches; anything unsupported ⇒ null
 *  (all-or-nothing — applying half a refactoring corrupts the workspace). */
class WorkspaceEditMapperTest {

    private static TextEdit edit(int sl, int sc, int el, int ec, String text) {
        return new TextEdit(new Range(new Position(sl, sc), new Position(el, ec)), text);
    }

    private static String uri(String name) {
        return Path.of(name).toUri().toString();
    }

    @Test
    void legacyChangesMapShapeMaps() {
        WorkspaceEdit we = new WorkspaceEdit(Map.of(uri("/tmp/A.java"), List.of(edit(0, 0, 0, 1, "x"))));
        var files = WorkspaceEditMapper.map(we);
        assertEquals(1, files.size());
        assertEquals(Path.of("/tmp/A.java"), files.get(0).file());
        assertEquals(1, files.get(0).edits().size());
        var e = files.get(0).edits().get(0);
        assertEquals(0, e.startLine());
        assertEquals(1, e.endCol());
        assertEquals("x", e.newText());
    }

    @Test
    void documentChangesShapeMapsAndMergesSameFile() {
        // jdtls's modern shape: TextDocumentEdit[] — two batches touching the same file merge into one
        // FileEdit so the whole file's change is a single undo unit.
        TextDocumentEdit first = new TextDocumentEdit(
                new VersionedTextDocumentIdentifier(uri("/tmp/A.java"), 1), List.of(edit(0, 0, 0, 0, "import x;\n")));
        TextDocumentEdit second = new TextDocumentEdit(
                new VersionedTextDocumentIdentifier(uri("/tmp/A.java"), 1), List.of(edit(5, 0, 5, 3, "y")));
        WorkspaceEdit we = new WorkspaceEdit(List.of(Either.forLeft(first), Either.forLeft(second)));
        var files = WorkspaceEditMapper.map(we);
        assertEquals(1, files.size(), "same-file batches merge");
        assertEquals(2, files.get(0).edits().size());
    }

    @Test
    void multipleFilesKeepTheirOwnBatches() {
        WorkspaceEdit we = new WorkspaceEdit(Map.of(
                uri("/tmp/A.java"), List.of(edit(0, 0, 0, 1, "a")),
                uri("/tmp/B.java"), List.of(edit(1, 0, 1, 1, "b"))));
        var files = WorkspaceEditMapper.map(we);
        assertEquals(2, files.size());
    }

    @Test
    void aResourceOperationRefusesTheWholeEdit() {
        // A create/rename/delete can't be applied faithfully — the whole edit must be refused, not the
        // text half applied around a file that was never created.
        WorkspaceEdit we = new WorkspaceEdit(List.of(
                Either.forLeft(new TextDocumentEdit(
                        new VersionedTextDocumentIdentifier(uri("/tmp/A.java"), 1), List.of(edit(0, 0, 0, 1, "a")))),
                Either.<TextDocumentEdit, ResourceOperation>forRight(new CreateFile(uri("/tmp/New.java")))));
        assertNull(WorkspaceEditMapper.map(we));
    }

    @Test
    void aNonFileUriRefusesTheWholeEdit() {
        WorkspaceEdit we = new WorkspaceEdit(
                Map.of("jdt://contents/java.base/java.lang/String.class", List.of(edit(0, 0, 0, 1, "a"))));
        assertNull(WorkspaceEditMapper.map(we));
    }

    @Test
    void nullAndEmptyEditsAreValidNoOps() {
        assertTrue(WorkspaceEditMapper.map(null).isEmpty());
        assertTrue(WorkspaceEditMapper.map(new WorkspaceEdit()).isEmpty());
    }
}
