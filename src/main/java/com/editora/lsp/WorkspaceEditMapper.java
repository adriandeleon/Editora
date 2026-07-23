package com.editora.lsp;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.editor.LspTextEdit;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;

/**
 * Maps an LSP {@link WorkspaceEdit} — a quick fix's changes, possibly across several files — into neutral
 * per-file {@link LspTextEdit} batches the editor can apply ({@code EditorBuffer.applyLspEdits}, one undo
 * unit per file). Handles both wire shapes: the legacy {@code changes} URI→edits map and the modern
 * {@code documentChanges} list of {@link TextDocumentEdit}s (what jdtls sends once the client declares
 * {@code workspaceEdit.documentChanges}).
 *
 * <p><b>All-or-nothing:</b> returns {@code null} when the edit contains anything that can't be applied
 * faithfully — a resource operation (create/rename/delete file) or a non-{@code file:} URI — because
 * applying <i>half</i> a refactoring corrupts the workspace; the caller then answers {@code applied=false}
 * so the server knows nothing happened. Pure of JavaFX; unit-tested.
 */
public final class WorkspaceEditMapper {

    private WorkspaceEditMapper() {}

    /** One file's share of a workspace edit. */
    public record FileEdit(Path file, List<LspTextEdit> edits) {}

    /** See the class doc: per-file batches (insertion-ordered, same-file entries merged), or {@code null}
     *  when any part is unsupported. An empty/absent edit maps to an empty list (a valid no-op). */
    public static List<FileEdit> map(WorkspaceEdit edit) {
        if (edit == null) {
            return List.of();
        }
        Map<Path, List<LspTextEdit>> byFile = new LinkedHashMap<>();
        if (edit.getDocumentChanges() != null) {
            for (var change : edit.getDocumentChanges()) {
                if (change == null || !change.isLeft()) {
                    return null; // a ResourceOperation (create/rename/delete) — not supported; apply nothing
                }
                TextDocumentEdit tde = change.getLeft();
                if (tde.getTextDocument() == null
                        || !addEdits(byFile, tde.getTextDocument().getUri(), tde.getEdits())) {
                    return null;
                }
            }
        } else if (edit.getChanges() != null) {
            for (var entry : edit.getChanges().entrySet()) {
                if (!addEdits(byFile, entry.getKey(), entry.getValue())) {
                    return null;
                }
            }
        }
        List<FileEdit> out = new ArrayList<>(byFile.size());
        byFile.forEach((file, edits) -> out.add(new FileEdit(file, List.copyOf(edits))));
        return out;
    }

    /** Accumulates one document's edits under its resolved file; false when the URI isn't a local file. */
    private static boolean addEdits(Map<Path, List<LspTextEdit>> byFile, String uri, List<TextEdit> edits) {
        Path file = filePath(uri);
        if (file == null) {
            return false;
        }
        List<LspTextEdit> bucket = byFile.computeIfAbsent(file, f -> new ArrayList<>());
        if (edits != null) {
            for (TextEdit e : edits) {
                if (e == null || e.getRange() == null) {
                    continue;
                }
                var s = e.getRange().getStart();
                var en = e.getRange().getEnd();
                bucket.add(new LspTextEdit(
                        s.getLine(),
                        s.getCharacter(),
                        en.getLine(),
                        en.getCharacter(),
                        e.getNewText() == null ? "" : e.getNewText()));
            }
        }
        return true;
    }

    private static Path filePath(String uri) {
        if (uri == null) {
            return null;
        }
        try {
            return Path.of(URI.create(uri));
        } catch (RuntimeException e) {
            return null; // jdt:// or another non-filesystem scheme — can't be edited as a file
        }
    }
}
