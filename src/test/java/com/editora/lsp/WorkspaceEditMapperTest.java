package com.editora.lsp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.eclipse.lsp4j.CreateFile;
import org.eclipse.lsp4j.DeleteFile;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ResourceOperation;
import org.eclipse.lsp4j.SnippetTextEdit;
import org.eclipse.lsp4j.StringValue;
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

    /** A {@code documentChanges} entry. LSP 3.18 widened an edit to {@code Either<TextEdit, SnippetTextEdit>}. */
    private static TextDocumentEdit docEdit(String path, TextEdit... edits) {
        return new TextDocumentEdit(
                new VersionedTextDocumentIdentifier(uri(path), 1),
                Stream.of(edits).map(Either::<TextEdit, SnippetTextEdit>forLeft).toList());
    }

    @Test
    void legacyChangesMapShapeMaps() {
        WorkspaceEdit we = new WorkspaceEdit(Map.of(uri("/tmp/A.java"), List.of(edit(0, 0, 0, 1, "x"))));
        var files = WorkspaceEditMapper.map(we).edits();
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
        TextDocumentEdit first = docEdit("/tmp/A.java", edit(0, 0, 0, 0, "import x;\n"));
        TextDocumentEdit second = docEdit("/tmp/A.java", edit(5, 0, 5, 3, "y"));
        WorkspaceEdit we = new WorkspaceEdit(List.of(Either.forLeft(first), Either.forLeft(second)));
        var files = WorkspaceEditMapper.map(we).edits();
        assertEquals(1, files.size(), "same-file batches merge");
        assertEquals(2, files.get(0).edits().size());
        assertEquals(1, files.get(0).version());
    }

    @Test
    void contradictoryVersionsForOneFileRefuseTheWholeEdit() {
        TextDocumentEdit first = docEdit("/tmp/A.java", edit(0, 0, 0, 0, "a"));
        TextDocumentEdit second = new TextDocumentEdit(
                new VersionedTextDocumentIdentifier(uri("/tmp/A.java"), 2),
                List.of(Either.forLeft(edit(0, 0, 0, 0, "b"))));

        assertNull(WorkspaceEditMapper.map(new WorkspaceEdit(List.of(Either.forLeft(first), Either.forLeft(second)))));
    }

    @Test
    void requestSnapshotIsAttachedToAnUnversionedEdit() {
        WorkspaceEdit mappedFrom = new WorkspaceEdit(Map.of(uri("/tmp/A.java"), List.of(edit(0, 0, 0, 1, "x"))));
        var mapped = WorkspaceEditMapper.withExpectedText(
                WorkspaceEditMapper.map(mappedFrom), Map.of(Path.of("/tmp/A.java"), "class A {}"));

        assertEquals("class A {}", mapped.edits().get(0).expectedText());
    }

    @Test
    void multipleFilesKeepTheirOwnBatches() {
        WorkspaceEdit we = new WorkspaceEdit(Map.of(
                uri("/tmp/A.java"), List.of(edit(0, 0, 0, 1, "a")),
                uri("/tmp/B.java"), List.of(edit(1, 0, 1, 1, "b"))));
        var files = WorkspaceEditMapper.map(we).edits();
        assertEquals(2, files.size());
    }

    @Test
    void createAndDeleteResourceOperationsMap() {
        WorkspaceEdit we = new WorkspaceEdit(List.of(
                Either.<TextDocumentEdit, ResourceOperation>forRight(new CreateFile(uri("/tmp/New.java"))),
                Either.<TextDocumentEdit, ResourceOperation>forRight(new DeleteFile(uri("/tmp/Old.java")))));
        var mapped = WorkspaceEditMapper.map(we);
        assertEquals(Path.of("/tmp/New.java"), mapped.creates().get(0).file());
        assertEquals(Path.of("/tmp/Old.java"), mapped.deletes().get(0).file());
    }

    @Test
    void aNonFileUriRefusesTheWholeEdit() {
        WorkspaceEdit we = new WorkspaceEdit(
                Map.of("jdt://contents/java.base/java.lang/String.class", List.of(edit(0, 0, 0, 1, "a"))));
        assertNull(WorkspaceEditMapper.map(we));
    }

    @Test
    void nullAndEmptyEditsAreValidNoOps() {
        assertTrue(WorkspaceEditMapper.map(null).edits().isEmpty());
        assertTrue(WorkspaceEditMapper.map(new WorkspaceEdit()).edits().isEmpty());
    }

    @Test
    void rejectsResourceOperationsWhoseOrderGroupedStagingCannotPreserve() {
        var deleteThenCreate = new WorkspaceEdit(List.of(
                Either.<TextDocumentEdit, ResourceOperation>forRight(new DeleteFile(uri("/tmp/A.java"))),
                Either.<TextDocumentEdit, ResourceOperation>forRight(new CreateFile(uri("/tmp/A.java")))));
        var deleteThenRename = new WorkspaceEdit(List.of(
                Either.<TextDocumentEdit, ResourceOperation>forRight(new DeleteFile(uri("/tmp/A.java"))),
                Either.<TextDocumentEdit, ResourceOperation>forRight(
                        new org.eclipse.lsp4j.RenameFile(uri("/tmp/B.java"), uri("/tmp/C.java")))));
        var textThenCreate = new WorkspaceEdit(List.of(
                Either.<TextDocumentEdit, ResourceOperation>forLeft(docEdit("/tmp/A.java", edit(0, 0, 0, 0, "text"))),
                Either.<TextDocumentEdit, ResourceOperation>forRight(new CreateFile(uri("/tmp/B.java")))));

        assertNull(WorkspaceEditMapper.map(deleteThenCreate));
        assertNull(WorkspaceEditMapper.map(deleteThenRename));
        assertNull(WorkspaceEditMapper.map(textThenCreate));
    }

    // --- RenameFile resource operations (#676) -------------------------------------------------

    @Test
    void renameAfterTextEditsMaps() {
        // jdtls's class-rename shape: the references' text edits, then the RenameFile — supported.
        org.eclipse.lsp4j.RenameFile rf = new org.eclipse.lsp4j.RenameFile(uri("/tmp/A.java"), uri("/tmp/B.java"));
        WorkspaceEdit we = new WorkspaceEdit(List.of(
                Either.forLeft(docEdit("/tmp/A.java", edit(0, 0, 0, 1, "B"))),
                Either.<TextDocumentEdit, ResourceOperation>forRight(rf)));
        var mapped = WorkspaceEditMapper.map(we);
        assertEquals(1, mapped.edits().size());
        assertEquals(1, mapped.renames().size());
        assertEquals(Path.of("/tmp/A.java"), mapped.renames().get(0).from());
        assertEquals(Path.of("/tmp/B.java"), mapped.renames().get(0).to());
    }

    @Test
    void aTextEditAfterARenameRefusesTheWholeEdit() {
        // A text edit AFTER a rename addresses the post-rename world — path remapping mid-apply is not
        // supported, so the whole edit is refused rather than applied against the wrong file.
        org.eclipse.lsp4j.RenameFile rf = new org.eclipse.lsp4j.RenameFile(uri("/tmp/A.java"), uri("/tmp/B.java"));
        WorkspaceEdit we = new WorkspaceEdit(List.of(
                Either.<TextDocumentEdit, ResourceOperation>forRight(rf),
                Either.forLeft(docEdit("/tmp/B.java", edit(0, 0, 0, 1, "x")))));
        assertNull(WorkspaceEditMapper.map(we));
    }

    @Test
    void renameOverwriteOptionIsCarried() {
        org.eclipse.lsp4j.RenameFile rf = new org.eclipse.lsp4j.RenameFile(uri("/tmp/A.java"), uri("/tmp/B.java"));
        rf.setOptions(new org.eclipse.lsp4j.RenameFileOptions(true, false));
        WorkspaceEdit we = new WorkspaceEdit(List.of(Either.<TextDocumentEdit, ResourceOperation>forRight(rf)));
        assertTrue(WorkspaceEditMapper.map(we).renames().get(0).overwrite());
    }

    // --- LSP 3.18 snippet edits -----------------------------------------------------------------

    @Test
    void aSnippetEditRefusesTheWholeEdit() {
        // A SnippetTextEdit's text carries tab-stop syntax the editor would insert verbatim, writing
        // "${1:name}" into the user's file. Refuse the whole edit rather than corrupt it.
        TextDocumentEdit tde = new TextDocumentEdit(
                new VersionedTextDocumentIdentifier(uri("/tmp/A.java"), 1),
                List.of(
                        Either.forLeft(edit(0, 0, 0, 1, "a")),
                        Either.forRight(new SnippetTextEdit(
                                new Range(new Position(1, 0), new Position(1, 1)),
                                new StringValue("snippet", "${1:name}")))));
        assertNull(WorkspaceEditMapper.map(new WorkspaceEdit(List.of(Either.forLeft(tde)))));
    }
}
