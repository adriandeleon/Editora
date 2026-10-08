package com.editora.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.editora.editor.LspDiagnostic;
import org.eclipse.lsp4j.CreateFile;
import org.eclipse.lsp4j.CreateFileOptions;
import org.eclipse.lsp4j.DeleteFile;
import org.eclipse.lsp4j.DeleteFileOptions;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RenameFile;
import org.eclipse.lsp4j.RenameFileOptions;
import org.eclipse.lsp4j.ResourceOperation;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkspaceEditMapper} is all-or-nothing: a workspace edit with one part it cannot read is refused
 * whole (a null), never applied without that part — half a refactoring leaves a project that does not
 * compile and an undo that does not put it back. These are the unreadable parts the existing tests do not
 * send: a resource operation with no path, an edit with no range, an entry that is not there at all.
 *
 * <p>{@link DiagnosticMapper} takes the opposite line for the opposite reason — one malformed diagnostic
 * must not hide the others — and is checked here on the same kind of input.
 */
class WorkspaceEditRefusalsTest {

    @TempDir
    Path root;

    private static <T> T wire(String json, Class<T> type) {
        return LanguageServerSession.LSP_GSON.fromJson(json, type);
    }

    private String uri(String name) {
        return root.resolve(name).toUri().toString();
    }

    private static WorkspaceEdit operations(ResourceOperation... operations) {
        var edit = new WorkspaceEdit();
        List<Either<TextDocumentEdit, ResourceOperation>> changes = new ArrayList<>();
        for (ResourceOperation operation : operations) {
            changes.add(Either.forRight(operation));
        }
        edit.setDocumentChanges(changes);
        return edit;
    }

    @Test
    void resourceOperationsCarryTheirOptions() {
        var create = new CreateFile(uri("New.java"), new CreateFileOptions(true, true));
        var plainCreate = new CreateFile(uri("Plain.java"));
        var rename = new RenameFile(uri("A.java"), uri("B.java"), new RenameFileOptions(true, false));
        var delete = new DeleteFile(uri("old"), new DeleteFileOptions(true, true));
        var plainDelete = new DeleteFile(uri("Gone.java"));

        WorkspaceEditMapper.Mapped mapped =
                WorkspaceEditMapper.map(operations(create, plainCreate, rename, delete, plainDelete));

        assertEquals(
                List.of(
                        new WorkspaceEditMapper.FileCreate(root.resolve("New.java"), true, true),
                        new WorkspaceEditMapper.FileCreate(root.resolve("Plain.java"), false, false)),
                mapped.creates());
        assertEquals(
                List.of(new WorkspaceEditMapper.FileRename(root.resolve("A.java"), root.resolve("B.java"), true)),
                mapped.renames());
        assertEquals(
                List.of(
                        new WorkspaceEditMapper.FileDelete(root.resolve("old"), true, true),
                        new WorkspaceEditMapper.FileDelete(root.resolve("Gone.java"), false, false)),
                mapped.deletes(),
                "absent options mean the cautious default: not recursive, and a missing file is an error");
    }

    @Test
    void aResourceOperationThatNamesNoFileRefusesTheWholeEdit() {
        var good = new CreateFile(uri("New.java"));

        assertNull(WorkspaceEditMapper.map(operations(good, wire("{\"kind\":\"create\"}", CreateFile.class))));
        assertNull(WorkspaceEditMapper.map(operations(good, wire("{\"kind\":\"delete\"}", DeleteFile.class))));
        assertNull(WorkspaceEditMapper.map(operations(
                good, wire("{\"kind\":\"rename\",\"oldUri\":\"" + uri("A.java") + "\"}", RenameFile.class))));
        assertNull(WorkspaceEditMapper.map(operations(
                good, wire("{\"kind\":\"rename\",\"newUri\":\"" + uri("B.java") + "\"}", RenameFile.class))));
        assertNull(
                WorkspaceEditMapper.map(operations(good, new CreateFile("jdt://contents/not/a/file"))),
                "a path that is not on the filesystem cannot be created");
        assertNull(
                WorkspaceEditMapper.map(operations(good, new ResourceOperation("vendor/unknown") {})),
                "an operation of a kind this client does not know is not skipped");
    }

    @Test
    void anEntryThatIsMissingRefusesTheWholeEdit() {
        var edit = operations(new CreateFile(uri("New.java")));
        edit.getDocumentChanges().add(null);
        assertNull(WorkspaceEditMapper.map(edit));

        var noDocument = new WorkspaceEdit();
        noDocument.setDocumentChanges(List.of(Either.forLeft(wire("{\"edits\":[]}", TextDocumentEdit.class))));
        assertNull(WorkspaceEditMapper.map(noDocument), "edits for no named document");
    }

    @Test
    void renamesThatShareAPathAreRefused() {
        assertNull(
                WorkspaceEditMapper.map(operations(
                        new RenameFile(uri("A.java"), uri("B.java")), new RenameFile(uri("C.java"), uri("B.java")))),
                "two files cannot both become B.java");
        assertNull(WorkspaceEditMapper.map(operations(
                new RenameFile(uri("A.java"), uri("B.java")), new RenameFile(uri("A.java"), uri("C.java")))));
    }

    @Test
    void aTextEditThatCannotBePlacedRefusesTheWholeEdit() {
        String file = uri("A.java");
        var good = new TextEdit(new Range(new Position(0, 0), new Position(0, 1)), "x");
        for (String unplaceable : List.of(
                "{\"newText\":\"no range\"}",
                "{\"newText\":\"no end\",\"range\":{\"start\":{\"line\":0,\"character\":0}}}",
                "{\"newText\":\"no start\",\"range\":{\"end\":{\"line\":0,\"character\":0}}}",
                "{\"newText\":\"x\",\"range\":{\"start\":{\"line\":-1,\"character\":0},\"end\":{\"line\":0,\"character\":0}}}",
                "{\"newText\":\"x\",\"range\":{\"start\":{\"line\":0,\"character\":-1},\"end\":{\"line\":0,\"character\":0}}}",
                "{\"newText\":\"x\",\"range\":{\"start\":{\"line\":0,\"character\":0},\"end\":{\"line\":-1,\"character\":0}}}",
                "{\"newText\":\"x\",\"range\":{\"start\":{\"line\":0,\"character\":0},\"end\":{\"line\":0,\"character\":-1}}}")) {
            var edits = new ArrayList<TextEdit>(List.of(good));
            edits.add(wire(unplaceable, TextEdit.class));
            assertNull(WorkspaceEditMapper.map(new WorkspaceEdit(Map.of(file, edits))), unplaceable);
        }
        var holed = new ArrayList<TextEdit>(List.of(good));
        holed.add(null);
        assertNull(WorkspaceEditMapper.map(new WorkspaceEdit(Map.of(file, holed))));
        assertNull(
                WorkspaceEditMapper.map(new WorkspaceEdit(Map.of("jdt://contents/x.class", List.of(good)))),
                "an edit to something that is not a file");
    }

    @Test
    void aDeletionIsAnEditWithNoText() {
        String file = uri("A.java");
        var deletion = wire(
                "{\"range\":{\"start\":{\"line\":1,\"character\":2},\"end\":{\"line\":1,\"character\":5}}}",
                TextEdit.class);

        WorkspaceEditMapper.Mapped mapped = WorkspaceEditMapper.map(new WorkspaceEdit(Map.of(file, List.of(deletion))));

        assertEquals(1, mapped.edits().size());
        assertEquals("", mapped.edits().get(0).edits().get(0).newText());
        assertEquals(root.resolve("A.java"), mapped.edits().get(0).file());
    }

    @Test
    void anEditWithNothingInItMapsToNothing() {
        for (WorkspaceEdit empty : List.of(new WorkspaceEdit(), new WorkspaceEdit(Map.of()))) {
            WorkspaceEditMapper.Mapped mapped = WorkspaceEditMapper.map(empty);
            assertNotNull(mapped, "nothing to do is not a refusal");
            assertTrue(mapped.edits().isEmpty() && mapped.renames().isEmpty());
            assertTrue(mapped.creates().isEmpty() && mapped.deletes().isEmpty());
        }
        assertTrue(WorkspaceEditMapper.map(null).edits().isEmpty());
    }

    // --- diagnostics: the opposite rule --------------------------------------------------------------

    @Test
    void aMalformedDiagnosticIsSkippedAndTheRestKept() {
        var good = new Diagnostic(new Range(new Position(2, 1), new Position(2, 4)), "unused");
        good.setCode(Either.forRight(1234));
        var markup = wire(
                "{\"range\":{\"start\":{\"line\":3,\"character\":0},\"end\":{\"line\":3,\"character\":1}},"
                        + "\"message\":{\"kind\":\"markdown\",\"value\":\"**bold** text\"},\"code\":\"E7\"}",
                Diagnostic.class);
        var silent = wire(
                "{\"range\":{\"start\":{\"line\":4,\"character\":0},\"end\":{\"line\":4,\"character\":1}}}",
                Diagnostic.class);
        var diagnostics = new ArrayList<Diagnostic>();
        diagnostics.add(wire("{\"message\":\"nowhere\"}", Diagnostic.class));
        diagnostics.add(
                wire("{\"message\":\"no end\",\"range\":{\"start\":{\"line\":0,\"character\":0}}}", Diagnostic.class));
        diagnostics.add(
                wire("{\"message\":\"no start\",\"range\":{\"end\":{\"line\":0,\"character\":0}}}", Diagnostic.class));
        diagnostics.add(good);
        diagnostics.add(markup);
        diagnostics.add(silent);

        List<LspDiagnostic> mapped = DiagnosticMapper.map(diagnostics);

        assertEquals(
                List.of("unused", "**bold** text", ""),
                mapped.stream().map(LspDiagnostic::message).toList());
        assertEquals("1234", mapped.get(0).code(), "a numeric code is shown as its digits");
        assertEquals("E7", mapped.get(1).code());
        assertNull(mapped.get(2).code());
        assertEquals(2, mapped.get(0).startLine());
        assertEquals(4, mapped.get(0).endCol());
        assertFalse(mapped.isEmpty());
        assertEquals(List.of(), DiagnosticMapper.map(null));
    }

    @Test
    void aMarkupMessageWithoutAValueIsBlankNotMissing() {
        var d = wire(
                "{\"range\":{\"start\":{\"line\":0,\"character\":0},\"end\":{\"line\":0,\"character\":1}},"
                        + "\"message\":{\"kind\":\"markdown\"}}",
                Diagnostic.class);
        assertEquals("", DiagnosticMapper.map(List.of(d)).get(0).message());
        d.setMessage(Either.forRight((MarkupContent) null));
        assertEquals("", DiagnosticMapper.map(List.of(d)).get(0).message());
        d.setMessage(Either.forLeft(null));
        assertEquals("", DiagnosticMapper.map(List.of(d)).get(0).message());
        d.setCode(Either.forRight(null));
        assertNull(DiagnosticMapper.map(List.of(d)).get(0).code());
    }
}
