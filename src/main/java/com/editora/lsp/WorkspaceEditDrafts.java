package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Plans a server's workspace edit before it is applied: the request-time text of every document the server had
 * open ({@link EditBasis}), each target matched to that text, and a closed target accepted only when the file has
 * not changed on disk since the request was sent. Pure apart from the modification-time reads of
 * {@link #planWorkspaceEdit}.
 */
final class WorkspaceEditDrafts {

    private WorkspaceEditDrafts() {}

    /**
     * What a response is validated against: the text of every document the session held when the request
     * was sent (keyed by local path, as opened and canonical), and the wall-clock instant it was sent
     * ({@code 0} when no request time is known, e.g. an edit handed in without the request that produced it).
     */
    record EditBasis(Map<Path, String> documents, long sentAtMillis, WireUris uris) {
        /** Nothing known, for an edit no session stands behind. One that came from a session uses {@link #unknown}. */
        static final EditBasis UNKNOWN = unknown(WireUris.LOCAL);

        /** Nothing known about the documents, but the edit's URIs are still {@code uris}'s server's. */
        static EditBasis unknown(WireUris uris) {
            return new EditBasis(Map.of(), 0, uris);
        }

        /** The same documents, re-stamped with the moment a follow-up request is sent. */
        EditBasis sentNow() {
            return new EditBasis(documents, System.currentTimeMillis(), uris);
        }
    }

    /** A workspace edit ready to apply, or the files that prevented it ({@code mapped} null). */
    public record EditPlan(WorkspaceEditMapper.@Nullable Mapped mapped, List<Path> blocked) {
        static final EditPlan REFUSED = new EditPlan(null, List.of());
    }

    /** Request-time text for every open document in a session, stamped with the current time. */
    static EditBasis editBasis(LanguageServerSession session) {
        return new EditBasis(expectedDocuments(session), System.currentTimeMillis(), session.uris());
    }

    /** Request-time text for every open document in a session, keyed by local path. */
    static Map<Path, String> expectedDocuments(LanguageServerSession session) {
        Map<Path, String> out = new java.util.LinkedHashMap<>();
        session.documentSnapshots().forEach((uri, text) -> {
            Path path = DocumentUris.toPath(uri);
            if (path != null) {
                out.put(path, text);
                out.put(com.editora.config.PathKeys.canonical(path), text);
            }
        });
        return Map.copyOf(out);
    }

    /** The version {@code didOpen} gives every document ({@link LanguageServerSession}); later ones count up. */
    static final int FIRST_DOCUMENT_VERSION = 1;

    /** The no-I/O half of planning: the mapped edit with every server-open target's request-time text
     *  attached, plus the targets the server did not have open (still to be checked against the disk). */
    record EditDraft(WorkspaceEditMapper.Mapped mapped, List<Path> closedTargets) {}

    /**
     * Maps {@code edit} and attaches each target's request-time text. A file the same edit creates starts empty,
     * and a version the server gives it is dropped: the session never opened it, so no version was ever sent
     * for it to name. lemminx's "Generate … and bind" fixes address their new grammar file with version 0, and
     * comparing that against a version that cannot exist refused every one of them (XML review C1).
     */
    static @Nullable EditDraft draftWorkspaceEdit(org.eclipse.lsp4j.WorkspaceEdit edit, EditBasis basis) {
        WorkspaceEditMapper.Mapped mapped = WorkspaceEditMapper.map(edit, basis.uris()::toPath);
        if (mapped == null) {
            return null;
        }
        Map<Path, String> documents = basis == null ? Map.of() : basis.documents();
        java.util.Set<String> created = mapped.creates().stream()
                .map(create -> com.editora.config.PathKeys.key(create.file()))
                .collect(java.util.stream.Collectors.toSet());
        List<WorkspaceEditMapper.FileEdit> edits =
                new ArrayList<>(mapped.edits().size());
        List<Path> closedTargets = new ArrayList<>();
        for (WorkspaceEditMapper.FileEdit fileEdit : mapped.edits()) {
            String expected = documents.get(fileEdit.file());
            if (expected == null) {
                expected = documents.get(com.editora.config.PathKeys.canonical(fileEdit.file()));
            }
            Integer version = fileEdit.version();
            if (expected == null && created.contains(com.editora.config.PathKeys.key(fileEdit.file()))) {
                expected = "";
                version = null;
            }
            if (version != null && version < FIRST_DOCUMENT_VERSION) {
                // A version below the first one this client sends names none of its documents: the server computed
                // this file's edit from the disk, not from the text it was sent. lemminx-maven's "Extract as new
                // property in parent" addresses the parent pom so even while it is open — every such edit was
                // refused, and applied to unsaved text it would land at the wrong lines. It is planned as a closed
                // file's edit: applied when the file is saved and unchanged on disk since the request (review 2, E5).
                edits.add(new WorkspaceEditMapper.FileEdit(fileEdit.file(), fileEdit.edits(), null, null, null));
                closedTargets.add(fileEdit.file());
                continue;
            }
            if (expected != null) {
                edits.add(new WorkspaceEditMapper.FileEdit(fileEdit.file(), fileEdit.edits(), version, expected, null));
            } else {
                edits.add(fileEdit);
                closedTargets.add(fileEdit.file());
            }
        }
        return new EditDraft(
                new WorkspaceEditMapper.Mapped(
                        List.copyOf(edits), mapped.renames(), mapped.creates(), mapped.deletes()),
                List.copyOf(closedTargets));
    }

    /**
     * Completes a draft by establishing a preimage for each target the server did not have open.
     *
     * <p>The server computed those edits from the file on disk. Reading the file once the response is in
     * would bless whatever is there now, so that alone proves nothing; what does is the file's modification
     * time predating the moment the request was sent — then the content on disk is the content the edit was
     * computed from. Such a target is marked with that instant, and the applier re-checks it against a clean
     * buffer. A target that changed since the request, is missing, or belongs to an edit whose request time
     * is unknown is reported as blocking, and nothing is applied.
     *
     * <p>A version the server attached to a document it never had open means nothing to this client (no
     * version was ever sent for it), so it is dropped rather than compared against a version that cannot
     * exist. Pure given {@code lastModified} (millis, negative for a missing file).
     */
    static EditPlan completeWorkspaceEdit(
            WorkspaceEditMapper.Mapped mapped,
            List<Path> closedTargets,
            EditBasis basis,
            java.util.function.ToLongFunction<Path> lastModified) {
        if (closedTargets.isEmpty()) {
            return new EditPlan(mapped, List.of());
        }
        long sentAt = basis == null ? 0 : basis.sentAtMillis();
        java.util.Set<Path> closed = new java.util.HashSet<>(closedTargets);
        List<Path> blocked = new ArrayList<>();
        List<WorkspaceEditMapper.FileEdit> edits =
                new ArrayList<>(mapped.edits().size());
        for (WorkspaceEditMapper.FileEdit fileEdit : mapped.edits()) {
            if (!closed.contains(fileEdit.file())) {
                edits.add(fileEdit);
            } else if (WorkspaceEditMapper.unchangedSince(
                    fileEdit.file(), lastModified.applyAsLong(fileEdit.file()), sentAt)) {
                edits.add(new WorkspaceEditMapper.FileEdit(fileEdit.file(), fileEdit.edits(), null, null, sentAt));
            } else {
                blocked.add(fileEdit.file());
            }
        }
        if (!blocked.isEmpty()) {
            return new EditPlan(null, List.copyOf(blocked));
        }
        return new EditPlan(
                new WorkspaceEditMapper.Mapped(
                        List.copyOf(edits), mapped.renames(), mapped.creates(), mapped.deletes()),
                List.of());
    }

    /** A file's modification time in millis (by its own machine's clock), or -1 when it cannot be read. */
    static long lastModifiedMillis(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (java.io.IOException | RuntimeException e) {
            return -1;
        }
    }

    /** Maps and validates a returned edit in one step. Stats closed targets: call it off the FX thread. */
    static EditPlan planWorkspaceEdit(org.eclipse.lsp4j.WorkspaceEdit edit, EditBasis basis) {
        EditDraft draft = draftWorkspaceEdit(edit, basis);
        return draft == null
                ? EditPlan.REFUSED
                : completeWorkspaceEdit(
                        draft.mapped(), draft.closedTargets(), basis, WorkspaceEditDrafts::lastModifiedMillis);
    }

    /**
     * The basis for a server edit that no tracked command is waiting for (the edit of a completion item's
     * command, one sent after {@code executeCommand} was already answered, an unsolicited one). There is no
     * request to date it by, but a target the server names <em>with the version the session currently
     * holds</em> needs none: that version identifies the text, which is the session's own copy. Such targets
     * get that text as their preimage (and keep the applier's version check); every other target stays
     * unknown and is refused as before.
     */
    static EditBasis versionedBasis(LanguageServerSession session, org.eclipse.lsp4j.WorkspaceEdit edit) {
        if (session == null) {
            return EditBasis.UNKNOWN;
        }
        WireUris uris = session.uris();
        if (edit == null || edit.getDocumentChanges() == null) {
            return EditBasis.unknown(uris);
        }
        Map<String, String> snapshots = null;
        Map<Path, String> documents = new java.util.LinkedHashMap<>();
        for (var change : edit.getDocumentChanges()) {
            var id = change != null && change.isLeft() && change.getLeft() != null
                    ? change.getLeft().getTextDocument()
                    : null;
            if (id == null || id.getUri() == null || id.getVersion() == null) {
                continue;
            }
            Path path = uris.toPath(id.getUri());
            String key = uris.toKey(id.getUri());
            if (path == null || !id.getVersion().equals(session.documentVersion(key))) {
                continue;
            }
            if (snapshots == null) {
                snapshots = session.documentSnapshots();
            }
            String text = snapshots.get(key);
            if (text != null) {
                documents.put(path, text);
                documents.put(com.editora.config.PathKeys.canonical(path), text);
            }
        }
        return documents.isEmpty() ? EditBasis.unknown(uris) : new EditBasis(Map.copyOf(documents), 0, uris);
    }

    /** {@code basis} with {@code file}'s text as {@code now}: an edit in text that has not changed since. */
    static EditBasis rebased(EditBasis basis, Path file, String now) {
        Map<Path, String> documents = new java.util.HashMap<>(basis.documents());
        documents.put(file, now);
        documents.put(com.editora.config.PathKeys.canonical(file), now);
        return new EditBasis(Map.copyOf(documents), basis.sentAtMillis(), basis.uris());
    }
}
