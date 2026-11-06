package com.editora.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.lsp.LspManager.CodeActionItem;
import com.editora.lsp.WorkspaceEditDrafts.EditBasis;
import com.editora.lsp.WorkspaceEditDrafts.EditDraft;
import com.editora.lsp.WorkspaceEditDrafts.EditPlan;
import org.eclipse.lsp4j.Position;
import org.jspecify.annotations.Nullable;

/**
 * Code actions and the workspace edits servers send, for {@link LspManager}: listing the actions over a range with
 * the diagnostics it overlaps, resolving and applying one (its edit, then its command), and landing every server
 * edit — an action's, a command's {@code workspace/applyEdit}, a refactoring's — through the UI's applier once
 * {@link WorkspaceEditDrafts} has checked it against what the server saw.
 */
final class LspCodeActions {

    private final LspManager m;

    LspCodeActions(LspManager m) {
        this.m = m;
    }

    /** Told (on the FX thread) which files prevented a workspace edit, just before its caller hears failure. */
    volatile Consumer<List<Path>> onEditBlocked = files -> {};

    public void setOnEditBlocked(Consumer<List<Path>> handler) {
        this.onEditBlocked = handler == null ? files -> {} : handler;
    }

    /**
     * Requests the code actions available over {@code [startLine:startChar .. endLine:endChar]} (0-based),
     * sending the range-overlapping known diagnostics as context, and delivers them on the FX thread —
     * preferred actions first, disabled ones dropped.
     */
    public void codeActions(
            Path file, int startLine, int startChar, int endLine, int endChar, Consumer<List<CodeActionItem>> cb) {
        codeActions(file, startLine, startChar, endLine, endChar, null, cb);
    }

    /**
     * As above, asking only for actions of the {@code only} kinds (null: all): the gutter lightbulb wants quick fixes
     * and quick assists, and every rest computed jdtls's generators and refactorings too (fifth review AV7).
     */
    public void codeActions(
            Path file,
            int startLine,
            int startChar,
            int endLine,
            int endChar,
            @Nullable List<String> only,
            Consumer<List<CodeActionItem>> cb) {
        var range = new org.eclipse.lsp4j.Range(new Position(startLine, startChar), new Position(endLine, endChar));
        requestCodeActions(file, range, contextDiagnostics(m.rawDiagnosticsFor(file), range), only, cb);
    }

    /**
     * The code actions for {@code diagnostic} alone, at its range, as if the server had reported it: lemminx's
     * generate-a-grammar fixes for a diagnostic its settings keep it from reporting (XML review 2 C8).
     */
    public void codeActionsFor(Path file, org.eclipse.lsp4j.Diagnostic diagnostic, Consumer<List<CodeActionItem>> cb) {
        requestCodeActions(file, diagnostic.getRange(), List.of(diagnostic), null, cb);
    }

    private void requestCodeActions(
            Path file,
            org.eclipse.lsp4j.Range range,
            List<org.eclipse.lsp4j.Diagnostic> context,
            @Nullable List<String> only,
            Consumer<List<CodeActionItem>> cb) {
        LanguageServerSession s = m.sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        EditBasis basis = WorkspaceEditDrafts.editBasis(s);
        Map<Path, String> expected = basis.documents();
        long sentAt = basis.sentAtMillis();
        s.codeAction(LspManager.uri(file), range, context, only).whenComplete((result, error) -> {
            List<CodeActionItem> items = new ArrayList<>();
            if (error == null && result != null) {
                for (var either : result) {
                    if (either == null) {
                        continue;
                    }
                    if (either.isLeft() && either.getLeft() != null) {
                        var cmd = either.getLeft();
                        items.add(new CodeActionItem(
                                cmd.getTitle() == null ? "" : cmd.getTitle(), "", false, cmd, expected, sentAt));
                    } else if (either.isRight() && either.getRight() != null) {
                        var action = either.getRight();
                        if (action.getDisabled() != null) {
                            continue; // the server says it doesn't apply here
                        }
                        items.add(new CodeActionItem(
                                action.getTitle() == null ? "" : action.getTitle(),
                                action.getKind() == null ? "" : action.getKind(),
                                Boolean.TRUE.equals(action.getIsPreferred()),
                                action,
                                expected,
                                sentAt));
                    }
                }
                items.sort((a, b) -> Boolean.compare(b.preferred(), a.preferred())); // preferred first (stable)
            }
            if (PythonCodeActions.SERVER_ID.equals(s.serverId())) { // Pyright gives no quick fixes (Python 2 E102)
                PythonCodeActions.withQuickFixes(s, LspManager.uri(file), items, context, only, expected, sentAt)
                        .whenComplete((all, failed) -> Platform.runLater(() -> cb.accept(all != null ? all : items)));
                return;
            }
            List<CodeActionItem> shown = BashCompletions.SERVER_ID.equals(s.serverId()) // titles, suppressions (C10)
                    ? BashCodeActions.shape(
                            items,
                            context,
                            LspManager.uri(file),
                            s.documentSnapshot(LspManager.uri(file)),
                            expected,
                            sentAt)
                    : items;
            Platform.runLater(() -> cb.accept(shown));
        });
    }

    /**
     * Pure: what a code-action request at {@code range} carries as its context — the diagnostics overlapping it,
     * or, for a bare caret on no squiggle, those on the caret's line. The quick fixes come from the context's
     * diagnostics, so with the caret at the start of a line, after its {@code ;} or on the next word, there were
     * none (Java review A5).
     */
    static List<org.eclipse.lsp4j.Diagnostic> contextDiagnostics(
            List<org.eclipse.lsp4j.Diagnostic> all, org.eclipse.lsp4j.Range range) {
        List<org.eclipse.lsp4j.Diagnostic> overlapping = diagnosticsOverlapping(all, range);
        if (!overlapping.isEmpty() || !range.getStart().equals(range.getEnd())) {
            return overlapping;
        }
        int line = range.getStart().getLine();
        List<org.eclipse.lsp4j.Diagnostic> out = new ArrayList<>();
        for (var d : all) {
            if (d != null
                    && d.getRange() != null
                    && d.getRange().getStart().getLine() <= line
                    && line <= d.getRange().getEnd().getLine()) {
                out.add(d);
            }
        }
        return out;
    }

    /** Pure: the diagnostics whose range overlaps {@code range} (what a code-action context should carry). */
    static List<org.eclipse.lsp4j.Diagnostic> diagnosticsOverlapping(
            List<org.eclipse.lsp4j.Diagnostic> all, org.eclipse.lsp4j.Range range) {
        List<org.eclipse.lsp4j.Diagnostic> out = new ArrayList<>();
        for (var d : all) {
            if (d != null && d.getRange() != null && rangesOverlap(d.getRange(), range)) {
                out.add(d);
            }
        }
        return out;
    }

    private static boolean rangesOverlap(org.eclipse.lsp4j.Range a, org.eclipse.lsp4j.Range b) {
        return comparePositions(a.getStart(), b.getEnd()) <= 0 && comparePositions(b.getStart(), a.getEnd()) <= 0;
    }

    private static int comparePositions(Position a, Position b) {
        int byLine = Integer.compare(a.getLine(), b.getLine());
        return byLine != 0 ? byLine : Integer.compare(a.getCharacter(), b.getCharacter());
    }

    /**
     * Applies a picked {@link CodeActionItem#raw()}: an inline {@code edit} applies directly; an edit-less
     * action is first resolved ({@code codeAction/resolve}); a {@code command} (bare, or riding the action)
     * runs via {@code workspace/executeCommand} — whose server-side handler typically answers with a
     * {@code workspace/applyEdit} back to us, which the registered apply-edit handler lands in the editor.
     * {@code cb} gets overall success on the FX thread.
     */
    public void applyCodeAction(Path file, Object raw, Consumer<Boolean> cb) {
        lastCodeActionFailure = null;
        lastCodeActionEmpty = false;
        lastEditUnresolvedUri = null;
        LanguageServerSession s = m.sessionFor(file);
        // No request time: an edit already inside this action was computed at some earlier, unknown moment,
        // so nothing can show a closed file is still what the server saw.
        EditBasis expected =
                s == null ? EditBasis.UNKNOWN : new EditBasis(WorkspaceEditDrafts.expectedDocuments(s), 0, s.uris());
        if (raw instanceof org.eclipse.lsp4j.Command cmd) {
            executeCommandExpectingEdit(file, cmd, expected, cb);
            return;
        }
        if (!(raw instanceof org.eclipse.lsp4j.CodeAction action)) {
            Platform.runLater(() -> cb.accept(false));
            return;
        }
        if (action.getEdit() != null) {
            Platform.runLater(() -> finishCodeAction(file, action, action.getEdit(), expected, cb));
            return;
        }
        if (s == null) {
            Platform.runLater(() -> cb.accept(false));
            return;
        }
        s.resolveCodeAction(action).whenComplete((resolved, error) -> {
            if (error != null) {
                lastCodeActionFailure = LspManager.failureReason(error);
            }
            org.eclipse.lsp4j.CodeAction use = error == null && resolved != null ? resolved : action;
            Platform.runLater(() -> finishCodeAction(file, use, use.getEdit(), expected, cb));
        });
    }

    /** Request-time snapshots and eventual apply result for one command-driven edit per session. */
    record PendingApply(EditBasis expected, java.util.concurrent.atomic.AtomicReference<Boolean> applied) {}

    final Map<LanguageServerSession, PendingApply> pendingApplyExpected = new ConcurrentHashMap<>();

    private void executeCommandExpectingEdit(
            Path file, org.eclipse.lsp4j.Command command, EditBasis expected, Consumer<Boolean> cb) {
        LanguageServerSession session = m.sessionFor(file);
        if (session == null) {
            Platform.runLater(() -> cb.accept(false));
            return;
        }
        // The server computes this edit while it runs the command, so closed files are judged against now.
        PendingApply pending = new PendingApply(
                (expected == null ? EditBasis.UNKNOWN : expected).sentNow(),
                new java.util.concurrent.atomic.AtomicReference<>());
        if (pendingApplyExpected.putIfAbsent(session, pending) != null) {
            Platform.runLater(() -> cb.accept(false));
            return; // do not let concurrent commands share or overwrite each other's stale-edit guard
        }
        m.executeCommand(file, command.getCommand(), command.getArguments(), (result, error) -> {
            if (error != null) {
                lastCodeActionFailure = LspManager.failureReason(error);
            }
            pendingApplyExpected.remove(session, pending);
            Boolean applied = pending.applied().get();
            cb.accept(error == null && !Boolean.FALSE.equals(applied));
        });
    }

    /** Why the last code action failed to apply, when the server said; or null. */
    volatile @Nullable String lastCodeActionFailure;

    /** The server's reason for the last failed code action, or null ("Could not apply: Inline Method" — BN9). */
    public @Nullable String lastCodeActionFailure() {
        return lastCodeActionFailure;
    }

    /** A URI naming no placeable file in the last refused server edit (lemminx's relative paths), or null. */
    private volatile @Nullable String lastEditUnresolvedUri;

    /** Why the last code action's edit was refused when it named a file this client cannot place (XML review B1). */
    public @Nullable String lastEditUnresolvedUri() {
        return lastEditUnresolvedUri;
    }

    /** Whether the last code action resolved to neither an edit nor a command: nothing to apply (ET1). */
    private volatile boolean lastCodeActionEmpty;

    public boolean lastCodeActionEmpty() {
        return lastCodeActionEmpty;
    }

    /** Applies a code action together with the request-time snapshots captured when it was listed. */
    public void applyCodeAction(Path file, CodeActionItem item, Consumer<Boolean> cb) {
        lastCodeActionFailure = null;
        lastCodeActionEmpty = false;
        lastEditUnresolvedUri = null;
        if (item == null) {
            Platform.runLater(() -> cb.accept(false));
            return;
        }
        Object raw = item.raw();
        LanguageServerSession owner = m.sessionFor(file);
        EditBasis basis = item.basis(owner == null ? WireUris.LOCAL : owner.uris());
        if (raw instanceof org.eclipse.lsp4j.Command cmd) {
            executeCommandExpectingEdit(file, cmd, basis, cb);
            return;
        }
        if (!(raw instanceof org.eclipse.lsp4j.CodeAction action)) {
            Platform.runLater(() -> cb.accept(false));
            return;
        }
        if (action.getEdit() != null) {
            Platform.runLater(() -> finishCodeAction(file, action, action.getEdit(), basis, cb));
            return;
        }
        LanguageServerSession s = m.sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(false));
            return;
        }
        s.resolveCodeAction(action).whenComplete((resolved, error) -> {
            if (error != null) {
                lastCodeActionFailure = LspManager.failureReason(error);
            }
            org.eclipse.lsp4j.CodeAction use = error == null && resolved != null ? resolved : action;
            Platform.runLater(() -> finishCodeAction(file, use, use.getEdit(), basis, cb));
        });
    }

    /** FX thread: applies {@code edit} (if any), then runs the action's trailing {@code command} (if any). */
    private void finishCodeAction(
            Path file,
            org.eclipse.lsp4j.CodeAction action,
            org.eclipse.lsp4j.WorkspaceEdit edit,
            EditBasis expected,
            Consumer<Boolean> cb) {
        if (edit != null) {
            applyWorkspaceEdit(
                    edit, expected, editOk -> finishCodeActionAfterEdit(file, action, expected, cb, true, editOk));
            return;
        }
        finishCodeActionAfterEdit(file, action, expected, cb, false, true);
    }

    private void finishCodeActionAfterEdit(
            Path file,
            org.eclipse.lsp4j.CodeAction action,
            EditBasis expected,
            Consumer<Boolean> cb,
            boolean editPresent,
            boolean editOk) {
        if (editPresent && !editOk) {
            cb.accept(false);
            return;
        }
        if (action.getCommand() != null && action.getCommand().getCommand() != null) {
            boolean editApplied = editOk;
            LanguageServerSession session = m.sessionFor(file);
            EditBasis commandExpected =
                    editPresent && editOk && session != null ? WorkspaceEditDrafts.editBasis(session) : expected;
            executeCommandExpectingEdit(
                    file, action.getCommand(), commandExpected, commandOk -> cb.accept(editApplied && commandOk));
            return;
        }
        // No command and no edit either ⇒ nothing was done — report failure, not a silent success. jdtls answers
        // so when a refactoring's final check fails (Inline Method on a method with several returns).
        lastCodeActionEmpty = !editPresent;
        cb.accept(editPresent && editOk);
    }

    /** UI-side workspace-edit applier (set by the coordinator; runs on the FX thread): applies each file's
     *  batch through an undoable buffer, then the trailing file renames — all-or-nothing. Default: refuse. */
    private volatile java.util.function.BiConsumer<WorkspaceEditMapper.Mapped, Consumer<Boolean>> applyEditHandler =
            (edits, done) -> done.accept(false);

    public void setApplyEditHandler(
            java.util.function.BiConsumer<WorkspaceEditMapper.Mapped, Consumer<Boolean>> handler) {
        this.applyEditHandler = handler == null ? (edits, done) -> done.accept(false) : handler;
    }

    /**
     * FX thread: validates {@code edit} against {@code basis} and hands it to the applier. An edit that only
     * touches documents the server had open is applied in the same call, as before. One that reaches a
     * closed file needs that file's modification time, so the check runs on a virtual thread and the apply
     * returns to the FX thread — no file I/O happens here.
     */
    void applyWorkspaceEdit(org.eclipse.lsp4j.WorkspaceEdit edit, EditBasis basis, Consumer<Boolean> done) {
        applyWorkspaceEdit(edit, basis, null, done);
    }

    /**
     * As above; {@code preview}, when given, sees the planned edit first and hands back what to apply (the files
     * the user kept), or never answers when the user cancels.
     */
    void applyWorkspaceEdit(
            org.eclipse.lsp4j.WorkspaceEdit edit,
            EditBasis basis,
            java.util.function.@Nullable BiConsumer<WorkspaceEditMapper.Mapped, Consumer<WorkspaceEditMapper.Mapped>>
                    preview,
            Consumer<Boolean> done) {
        EditDraft draft = WorkspaceEditDrafts.draftWorkspaceEdit(edit, basis);
        if (draft == null) {
            lastEditUnresolvedUri = WorkspaceEditMapper.unresolvedUri(edit, basis.uris()::toPath);
            done.accept(false);
            return;
        }
        Consumer<WorkspaceEditMapper.Mapped> apply = preview == null
                ? mapped -> applyEditHandler.accept(mapped, done)
                : mapped -> preview.accept(mapped, kept -> applyEditHandler.accept(kept, done));
        if (draft.closedTargets().isEmpty()) {
            apply.accept(draft.mapped());
            return;
        }
        Thread.ofVirtual().name("lsp-edit-preimage").start(() -> {
            EditPlan plan = WorkspaceEditDrafts.completeWorkspaceEdit(
                    draft.mapped(), draft.closedTargets(), basis, WorkspaceEditDrafts::lastModifiedMillis);
            Platform.runLater(() -> {
                if (plan.mapped() == null) {
                    onEditBlocked.accept(plan.blocked());
                    done.accept(false);
                } else {
                    apply.accept(plan.mapped());
                }
            });
        });
    }

    /** A server-initiated {@code workspace/applyEdit} (from any session): apply on FX, answer the server. */
    void onServerApplyEdit(
            LanguageServerSession session, org.eclipse.lsp4j.WorkspaceEdit edit, Consumer<Boolean> respond) {
        PendingApply pending = pendingApplyExpected.get(session);
        EditBasis expected = pending == null ? WorkspaceEditDrafts.versionedBasis(session, edit) : pending.expected();
        Platform.runLater(() -> applyWorkspaceEdit(edit, expected, applied -> {
            if (pending != null) {
                pending.applied().set(applied);
            }
            respond.accept(applied);
        }));
    }
}
