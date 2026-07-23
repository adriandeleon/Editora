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
 * faithfully — a create/delete resource operation, a non-{@code file:} URI, or a text edit trailing a
 * {@code RenameFile} — because applying <i>half</i> a refactoring corrupts the workspace; the caller then
 * answers {@code applied=false} so the server knows nothing happened. {@code RenameFile} ops <em>are</em>
 * supported (#676) when they follow the text edits. Pure of JavaFX; unit-tested.
 */
public final class WorkspaceEditMapper {

    private WorkspaceEditMapper() {}

    /** One file's share of a workspace edit. */
    public record FileEdit(Path file, List<LspTextEdit> edits) {}

    /** A {@code RenameFile} resource operation — jdtls emits one when a public class is renamed (the
     *  {@code .java} file must move too). {@code overwrite} mirrors the op's option (#676). */
    public record FileRename(Path from, Path to, boolean overwrite) {}

    /** A whole workspace edit: per-file text batches plus the file renames to perform <b>after</b> them. */
    public record Mapped(List<FileEdit> edits, List<FileRename> renames) {}

    /**
     * See the class doc: per-file batches (insertion-ordered, same-file entries merged) plus trailing file
     * renames, or {@code null} when any part is unsupported — a create/delete resource operation, a
     * non-{@code file:} URI, or a text edit appearing <b>after</b> a rename (its URI would address the
     * post-rename world; supporting that means path remapping mid-apply, refused instead — jdtls and
     * friends emit renames last). An empty/absent edit maps to empty lists (a valid no-op).
     */
    public static Mapped map(WorkspaceEdit edit) {
        if (edit == null) {
            return new Mapped(List.of(), List.of());
        }
        Map<Path, List<LspTextEdit>> byFile = new LinkedHashMap<>();
        List<FileRename> renames = new ArrayList<>();
        if (edit.getDocumentChanges() != null) {
            for (var change : edit.getDocumentChanges()) {
                if (change == null) {
                    return null;
                }
                if (change.isLeft()) {
                    if (!renames.isEmpty()) {
                        return null; // a text edit AFTER a rename addresses the post-rename world — refused
                    }
                    TextDocumentEdit tde = change.getLeft();
                    if (tde.getTextDocument() == null
                            || !addEdits(byFile, tde.getTextDocument().getUri(), tde.getEdits())) {
                        return null;
                    }
                } else if (change.getRight() instanceof org.eclipse.lsp4j.RenameFile rf) {
                    Path from = filePath(rf.getOldUri());
                    Path to = filePath(rf.getNewUri());
                    if (from == null || to == null) {
                        return null;
                    }
                    boolean overwrite = rf.getOptions() != null
                            && Boolean.TRUE.equals(rf.getOptions().getOverwrite());
                    renames.add(new FileRename(from, to, overwrite));
                } else {
                    return null; // CreateFile/DeleteFile — not supported; apply nothing
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
        return new Mapped(out, List.copyOf(renames));
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
