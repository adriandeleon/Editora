package com.editora.lsp;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.editora.config.PathKeys;
import com.editora.editor.LspTextEdit;
import org.eclipse.lsp4j.SnippetTextEdit;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/**
 * Maps an LSP {@link WorkspaceEdit} — a quick fix's changes, possibly across several files — into neutral
 * per-file {@link LspTextEdit} batches the editor can apply ({@code EditorBuffer.applyLspEdits}, one undo
 * unit per file). Handles both wire shapes: the legacy {@code changes} URI→edits map and the modern
 * {@code documentChanges} list of {@link TextDocumentEdit}s (what jdtls sends once the client declares
 * {@code workspaceEdit.documentChanges}).
 *
 * <p><b>All-or-nothing:</b> returns {@code null} when the edit contains anything that can't be applied
 * faithfully — a non-{@code file:} URI, a {@link SnippetTextEdit}, or
 * a text edit trailing a {@code RenameFile} — because applying <i>half</i> a refactoring corrupts the
 * workspace; the caller then answers {@code applied=false} so the server knows nothing happened.
 * Create, rename, and delete resource operations retain their overwrite/ignore/recursive options; rename and
 * delete are terminal because a following text edit would address the post-operation filesystem. Pure of
 * JavaFX; unit-tested.
 */
public final class WorkspaceEditMapper {

    private WorkspaceEditMapper() {}

    /** One file's share of a workspace edit. */
    public record FileEdit(Path file, List<LspTextEdit> edits, Integer version, String expectedText) {
        public FileEdit(Path file, List<LspTextEdit> edits) {
            this(file, edits, null, null);
        }
    }

    /** A {@code RenameFile} resource operation — jdtls emits one when a public class is renamed (the
     *  {@code .java} file must move too). {@code overwrite} mirrors the op's option (#676). */
    public record FileRename(Path from, Path to, boolean overwrite) {}

    public record FileCreate(Path file, boolean overwrite, boolean ignoreIfExists) {}

    public record FileDelete(Path file, boolean recursive, boolean ignoreIfNotExists) {}

    /** A whole workspace edit: per-file text batches plus its filesystem operations. */
    public record Mapped(
            List<FileEdit> edits, List<FileRename> renames, List<FileCreate> creates, List<FileDelete> deletes) {
        public Mapped(List<FileEdit> edits, List<FileRename> renames) {
            this(edits, renames, List.of(), List.of());
        }
    }

    /**
     * See the class doc: insertion-ordered per-file batches plus create/rename/delete operations, or
     * {@code null} for a non-file URI, snippet edit, or text edit after a rename/delete. Create may precede
     * edits to the newly created file, which is the standard LSP shape. An empty edit is a valid no-op.
     */
    public static Mapped map(WorkspaceEdit edit) {
        if (edit == null) {
            return new Mapped(List.of(), List.of(), List.of(), List.of());
        }
        Map<Path, List<LspTextEdit>> byFile = new LinkedHashMap<>();
        Map<Path, Integer> versions = new LinkedHashMap<>();
        List<FileRename> renames = new ArrayList<>();
        List<FileCreate> creates = new ArrayList<>();
        List<FileDelete> deletes = new ArrayList<>();
        boolean terminalResourceOperation = false;
        boolean sawTextEdit = false;
        int resourcePhase = 0; // create=0, rename=1, delete=2; application stages in that order
        if (edit.getDocumentChanges() != null) {
            for (var change : edit.getDocumentChanges()) {
                if (change == null) {
                    return null;
                }
                if (change.isLeft()) {
                    if (terminalResourceOperation) {
                        return null; // an edit after rename/delete addresses a changed filesystem world
                    }
                    TextDocumentEdit tde = change.getLeft();
                    List<TextEdit> plain = plainEdits(tde.getEdits());
                    if (tde.getTextDocument() == null
                            || plain == null
                            || !addEdits(byFile, tde.getTextDocument().getUri(), plain)) {
                        return null;
                    }
                    Path file = filePath(tde.getTextDocument().getUri());
                    Integer version = tde.getTextDocument().getVersion();
                    if (versions.containsKey(file) && !java.util.Objects.equals(versions.get(file), version)) {
                        return null; // contradictory versions for one document cannot be applied atomically
                    }
                    versions.put(file, version);
                    sawTextEdit = true;
                } else if (change.getRight() instanceof org.eclipse.lsp4j.RenameFile rf) {
                    if (resourcePhase > 1) {
                        return null; // grouped staging cannot preserve a rename after a delete
                    }
                    resourcePhase = 1;
                    Path from = filePath(rf.getOldUri());
                    Path to = filePath(rf.getNewUri());
                    if (from == null || to == null) {
                        return null;
                    }
                    boolean overwrite = rf.getOptions() != null
                            && Boolean.TRUE.equals(rf.getOptions().getOverwrite());
                    renames.add(new FileRename(from, to, overwrite));
                    terminalResourceOperation = true;
                } else if (change.getRight() instanceof org.eclipse.lsp4j.CreateFile cf) {
                    if (sawTextEdit || resourcePhase > 0) {
                        return null; // grouped staging applies every create before every text/rename/delete
                    }
                    Path file = filePath(cf.getUri());
                    if (file == null) {
                        return null;
                    }
                    boolean overwrite = cf.getOptions() != null
                            && Boolean.TRUE.equals(cf.getOptions().getOverwrite());
                    boolean ignore = cf.getOptions() != null
                            && Boolean.TRUE.equals(cf.getOptions().getIgnoreIfExists());
                    creates.add(new FileCreate(file, overwrite, ignore));
                } else if (change.getRight() instanceof org.eclipse.lsp4j.DeleteFile df) {
                    resourcePhase = 2;
                    Path file = filePath(df.getUri());
                    if (file == null) {
                        return null;
                    }
                    boolean recursive = df.getOptions() != null
                            && Boolean.TRUE.equals(df.getOptions().getRecursive());
                    boolean ignore = df.getOptions() != null
                            && Boolean.TRUE.equals(df.getOptions().getIgnoreIfNotExists());
                    deletes.add(new FileDelete(file, recursive, ignore));
                    terminalResourceOperation = true;
                } else {
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
        byFile.forEach((file, edits) -> out.add(new FileEdit(file, List.copyOf(edits), versions.get(file), null)));
        if (!independentRenames(renames)) {
            return null;
        }
        return new Mapped(out, List.copyOf(renames), List.copyOf(creates), List.copyOf(deletes));
    }

    /** Grouped execution can preserve only renames whose source and destination paths are disjoint. */
    private static boolean independentRenames(List<FileRename> renames) {
        Set<String> touched = new HashSet<>();
        for (FileRename rename : renames) {
            if (!touched.add(PathKeys.normalizedKey(rename.from()))
                    || !touched.add(PathKeys.normalizedKey(rename.to()))) {
                return false;
            }
        }
        return true;
    }

    /** Adds request-time document snapshots to unversioned edits so an async response can be rejected if
     *  any target changes before application. Protocol versions remain authoritative when present. */
    public static Mapped withExpectedText(Mapped mapped, Map<Path, String> expected) {
        if (mapped == null || expected == null || expected.isEmpty()) {
            return mapped;
        }
        List<FileEdit> edits = new ArrayList<>(mapped.edits().size());
        for (FileEdit edit : mapped.edits()) {
            edits.add(new FileEdit(
                    edit.file(),
                    edit.edits(),
                    edit.version(),
                    expected.getOrDefault(edit.file(), edit.expectedText())));
        }
        return new Mapped(List.copyOf(edits), mapped.renames(), mapped.creates(), mapped.deletes());
    }

    /**
     * Unwraps LSP 3.18's {@code Either<TextEdit, SnippetTextEdit>} document edits to plain ones, or
     * {@code null} if any is a {@link SnippetTextEdit}.
     *
     * <p>A snippet edit's text carries tab-stop syntax ({@code ${1:name}}) that the editor's
     * {@code applyLspEdits} would insert <em>verbatim</em>, so treating one as plain text writes the
     * placeholder markup into the user's file. Refusing the whole edit is the class's all-or-nothing rule.
     * In practice this never fires: a server may only send snippet edits once the client advertises the
     * capability, which Editora does not.
     */
    private static List<TextEdit> plainEdits(List<Either<TextEdit, SnippetTextEdit>> edits) {
        if (edits == null) {
            return List.of();
        }
        List<TextEdit> out = new ArrayList<>(edits.size());
        for (Either<TextEdit, SnippetTextEdit> e : edits) {
            if (e == null) {
                continue; // tolerated like a null TextEdit below
            }
            if (!e.isLeft()) {
                return null;
            }
            out.add(e.getLeft());
        }
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
