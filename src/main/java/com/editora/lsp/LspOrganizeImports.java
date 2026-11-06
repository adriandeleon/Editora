package com.editora.lsp;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.lsp.LspManager.CodeActionItem;
import com.editora.lsp.LspManager.OrganizeOutcome;
import com.editora.lsp.WorkspaceEditDrafts.EditBasis;
import org.eclipse.lsp4j.Position;

/**
 * Organize Imports the way each server does it, for {@link LspManager}: jdtls's {@code java/organizeImports} (with
 * its questions about ambiguous names), Pyright's command, the TypeScript server's own, else a
 * {@code source.organizeImports} code action. Outcomes reach their callbacks on the FX thread.
 */
final class LspOrganizeImports {

    private final LspManager m;

    LspOrganizeImports(LspManager m) {
        this.m = m;
    }

    /**
     * Organizes {@code file}'s imports the way its server does it: jdtls's {@code java/organizeImports},
     * Pyright's {@code pyright.organizeimports} command (basedpyright's own name for it), else a
     * {@code source.organizeImports} code action. Before this every server was sent the jdtls request, so on a
     * Python file it failed and the status bar said "Imports already organized" (review B5).
     */
    public void organizeImportsAny(Path file, int lastLine, int lastChar, Consumer<OrganizeOutcome> cb) {
        LanguageServerSession s = m.sessionFor(file);
        if (s == null || file == null) {
            Platform.runLater(() -> cb.accept(OrganizeOutcome.UNSUPPORTED));
            return;
        }
        String serverId = s.serverId();
        if (LspServerRegistry.JAVA_SERVER_ID.equals(serverId)) {
            organizeJavaImports(s, file, lastLine, lastChar, cb);
            return;
        }
        if (LspManager.PYTHON_SERVER_ID.equals(serverId)) {
            EditBasis expected = WorkspaceEditDrafts.editBasis(s);
            String command = pyrightOrganizeCommand(s.command());
            s.executeCommand(command, List.of(m.serverUri(file)))
                    .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .whenComplete((r, e) -> {
                        org.eclipse.lsp4j.WorkspaceEdit edit = e != null ? null : JdtlsCommands.asWorkspaceEdit(r);
                        Platform.runLater(() -> {
                            if (e != null) {
                                cb.accept(OrganizeOutcome.UNSUPPORTED); // pylsp, ruff, jedi: no such command
                            } else if (edit == null || !changesText(edit)) {
                                cb.accept(OrganizeOutcome.UNCHANGED);
                            } else {
                                m.codeActions.applyWorkspaceEdit(
                                        edit,
                                        expected,
                                        ok -> cb.accept(ok ? OrganizeOutcome.ORGANIZED : OrganizeOutcome.UNCHANGED));
                            }
                        });
                    });
            return;
        }
        if (TypeScriptServer.SERVER_ID.equals(serverId)) {
            organizeTypeScriptImports(s, file, cb);
            return;
        }
        m.codeActions(file, 0, 0, lastLine, lastChar, items -> {
            CodeActionItem organize = items.stream()
                    .filter(i -> i.kind().startsWith("source.organizeImports"))
                    .findFirst()
                    .orElse(null);
            if (organize == null) {
                cb.accept(OrganizeOutcome.UNSUPPORTED);
            } else {
                m.applyCodeAction(
                        file, organize, ok -> cb.accept(ok ? OrganizeOutcome.ORGANIZED : OrganizeOutcome.UNCHANGED));
            }
        });
    }

    /**
     * typescript-language-server's own Organize Imports ({@code _typescript.organizeImports}): it sorts, combines
     * and removes unused imports, and sends the edit back as {@code workspace/applyEdit}. Its
     * {@code source.organizeImports} code action only sorts, and was never offered at all without {@code only}
     * (Node review B5). No edit coming back means the imports were already organized.
     */
    private void organizeTypeScriptImports(LanguageServerSession s, Path file, Consumer<OrganizeOutcome> cb) {
        LspCodeActions.PendingApply pending = new LspCodeActions.PendingApply(
                WorkspaceEditDrafts.editBasis(s).sentNow(), new java.util.concurrent.atomic.AtomicReference<>());
        if (m.codeActions.pendingApplyExpected.putIfAbsent(s, pending) != null) {
            Platform.runLater(() -> cb.accept(OrganizeOutcome.UNCHANGED));
            return;
        }
        String serverPath;
        try {
            serverPath = Path.of(java.net.URI.create(m.serverUri(file))).toString();
        } catch (RuntimeException e) {
            serverPath = java.net.URI.create(m.serverUri(file)).getPath();
        }
        m.executeCommand(file, "_typescript.organizeImports", List.of(serverPath), (result, error) -> {
            m.codeActions.pendingApplyExpected.remove(s, pending);
            Boolean applied = pending.applied().get();
            cb.accept(
                    error != null
                            ? OrganizeOutcome.UNSUPPORTED
                            : Boolean.TRUE.equals(applied) ? OrganizeOutcome.ORGANIZED : OrganizeOutcome.UNCHANGED);
        });
    }

    /**
     * Pyright's organize-imports command, under basedpyright's name when that is the server — whichever word of the
     * command names it: {@code npx basedpyright-langserver --stdio} ran basedpyright under Pyright's name, which it
     * does not answer (Python review 2, D110). Pure.
     */
    static String pyrightOrganizeCommand(List<String> serverCommand) {
        boolean based = serverCommand != null
                && serverCommand.stream().anyMatch(word -> word != null && word.contains("basedpyright"));
        return based ? "basedpyright.organizeimports" : "pyright.organizeimports";
    }

    /** Whether {@code edit} carries any text change (Pyright answers an already-sorted file with none). */
    static boolean changesText(org.eclipse.lsp4j.WorkspaceEdit edit) {
        if (edit.getChanges() != null && edit.getChanges().values().stream().anyMatch(l -> l != null && !l.isEmpty())) {
            return true;
        }
        return edit.getDocumentChanges() != null && !edit.getDocumentChanges().isEmpty();
    }

    /**
     * Organize Imports as a save runs it: no question for an ambiguous name (it is left unimported), and the
     * outcome reported. {@code cb} on the FX thread.
     */
    public void organizeImportsQuietly(Path file, int lastLine, int lastChar, Consumer<OrganizeOutcome> cb) {
        LanguageServerSession s = m.javaSessionFor(file);
        if (s == null || file == null || m.sessionFor(file) != s) {
            Platform.runLater(() -> cb.accept(OrganizeOutcome.FAILED));
            return;
        }
        String quiet = s.uris().toWire(LspManager.uri(file));
        s.quietImportChoices.add(quiet);
        organizeJavaImports(s, file, lastLine, lastChar, outcome -> {
            s.quietImportChoices.remove(quiet);
            cb.accept(outcome);
        });
    }

    /**
     * Organize imports for a whole file ({@code java/organizeImports}, #746) and apply the resulting edit.
     *
     * <p>The same operation is also reachable as the {@code source.organizeImports} code action, but only by
     * opening the code-action picker and choosing it. This is the direct, bindable form.
     *
     * <p>Signature read off the jdtls jar: it takes {@code CodeActionParams} — the whole file's range, not a
     * position — and answers a {@code WorkspaceEdit}.
     */
    public void organizeImports(Path file, int lastLine, int lastChar, Consumer<Boolean> cb) {
        LanguageServerSession s = m.sessionFor(file);
        if (s == null || file == null) {
            Platform.runLater(() -> cb.accept(false));
            return;
        }
        organizeJavaImports(s, file, lastLine, lastChar, outcome -> cb.accept(outcome == OrganizeOutcome.ORGANIZED));
    }

    /**
     * jdtls's {@code java/organizeImports}. For a name with several candidates jdtls asks the client
     * ({@code java.action.organizeImports.chooseImports}, answered by {@link LanguageServerSession}). Unanswered,
     * it cancelled the whole operation and answered null, which reads exactly like "nothing to change" — so the
     * question is what had to be answered (Java review A3); a request that fails is {@link OrganizeOutcome#FAILED}.
     */
    private void organizeJavaImports(
            LanguageServerSession s, Path file, int lastLine, int lastChar, Consumer<OrganizeOutcome> cb) {
        var range = new org.eclipse.lsp4j.Range(new Position(0, 0), new Position(lastLine, lastChar));
        var params = new org.eclipse.lsp4j.CodeActionParams(
                new org.eclipse.lsp4j.TextDocumentIdentifier(m.serverUri(file)),
                range,
                new org.eclipse.lsp4j.CodeActionContext(List.of()));
        EditBasis expected = WorkspaceEditDrafts.editBasis(s);
        // Bounded by the session, which waits while an import question is open (fifth review DV1).
        s.rawRequest("java/organizeImports", params).whenComplete((r, e) -> {
            org.eclipse.lsp4j.WorkspaceEdit edit = e != null ? null : JdtlsCommands.asWorkspaceEdit(r);
            Platform.runLater(() -> {
                if (e != null) {
                    cb.accept(OrganizeOutcome.FAILED);
                } else if (edit == null || !changesText(edit)) {
                    cb.accept(OrganizeOutcome.UNCHANGED);
                } else {
                    m.codeActions.applyWorkspaceEdit(edit, expected, ok -> {
                        // refused because the file changed under the question, not because the server gave up
                        // (seventh review AR1)
                        String then = expected.documents().get(file);
                        boolean changed = then != null
                                && !then.equals(
                                        WorkspaceEditDrafts.expectedDocuments(s).get(file));
                        cb.accept(
                                ok
                                        ? OrganizeOutcome.ORGANIZED
                                        : changed ? OrganizeOutcome.STALE : OrganizeOutcome.FAILED);
                    });
                }
            });
        });
    }
}
