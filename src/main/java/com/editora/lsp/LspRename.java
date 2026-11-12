package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.lsp.LspManager.RenameFallback;
import com.editora.lsp.LspManager.RenamePrep;
import com.editora.lsp.LspManager.WatchedFile;
import com.editora.lsp.LspManager.WatchedKind;
import com.editora.lsp.WorkspaceEditDrafts.EditBasis;
import com.editora.lsp.WorkspaceEditDrafts.EditPlan;
import org.eclipse.lsp4j.Position;
import org.jspecify.annotations.Nullable;

/**
 * Renames for {@link LspManager}: a symbol's (prepare, preview, apply) and a file's or folder's, where the server
 * moves the code that refers to it along ({@code workspace/willRenameFiles}: jdtls for package lines and imports,
 * the TypeScript server for import paths). Callbacks run on the FX thread.
 */
final class LspRename {

    private final LspManager m;

    LspRename(LspManager m) {
        this.m = m;
    }

    /** True if {@code file}'s server is ready and advertises rename (#676). */
    public boolean supportsRename(Path file) {
        LanguageServerSession s = m.sessionFor(file);
        return s != null && renameProvider(s.capabilities());
    }

    /** True if the server also advertises {@code prepareRename} (validate + placeholder). */
    public boolean supportsPrepareRename(Path file) {
        LanguageServerSession s = m.sessionFor(file);
        if (s == null || s.capabilities() == null) {
            return false;
        }
        var p = s.capabilities().getRenameProvider();
        return p != null
                && p.isRight()
                && p.getRight() != null
                && Boolean.TRUE.equals(p.getRight().getPrepareProvider());
    }

    /** Pure: whether a server's capabilities include {@code renameProvider} (null-safe, either form). */
    static boolean renameProvider(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        if (caps == null) {
            return false;
        }
        var p = caps.getRenameProvider();
        return p != null && (p.isRight() || Boolean.TRUE.equals(p.getLeft()));
    }

    /** Validates a rename at a 0-based position; the result arrives on the FX thread (#676). */
    public void prepareRename(Path file, int line, int character, Consumer<RenamePrep> cb) {
        LanguageServerSession s = m.sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(RenamePrep.REFUSED));
            return;
        }
        s.prepareRename(LspManager.uri(file), new Position(line, character))
                .whenComplete(
                        (r, e) -> Platform.runLater(() -> cb.accept(e != null ? RenamePrep.REFUSED : mapPrepare(r))));
    }

    /** Pure: maps a {@code prepareRename} response (any of its three shapes; null = refused). */
    static RenamePrep mapPrepare(
            org.eclipse.lsp4j.jsonrpc.messages.Either3<
                            org.eclipse.lsp4j.Range,
                            org.eclipse.lsp4j.PrepareRenameResult,
                            org.eclipse.lsp4j.PrepareRenameDefaultBehavior>
                    r) {
        if (r == null) {
            return RenamePrep.REFUSED; // per spec: null/undefined ⇒ rename not valid at this position
        }
        if (r.isFirst() && r.getFirst() != null) {
            var range = r.getFirst();
            return new RenamePrep(
                    true,
                    "",
                    range.getStart().getLine(),
                    range.getStart().getCharacter(),
                    range.getEnd().getLine(),
                    range.getEnd().getCharacter());
        }
        if (r.isSecond() && r.getSecond() != null) {
            var prep = r.getSecond();
            var range = prep.getRange();
            return new RenamePrep(
                    true,
                    prep.getPlaceholder() == null ? "" : prep.getPlaceholder(),
                    range == null ? 0 : range.getStart().getLine(),
                    range == null ? 0 : range.getStart().getCharacter(),
                    range == null ? 0 : range.getEnd().getLine(),
                    range == null ? 0 : range.getEnd().getCharacter());
        }
        if (r.isThird() && r.getThird() != null) {
            // {defaultBehavior: true}: rename the word at the caret. lemminx answers {defaultBehavior: false} where
            // nothing can be renamed (an attribute value), which VS Code's client reports as "can't be renamed"
            // instead of opening a dialog that leads nowhere (XML review B11).
            return r.getThird().isDefaultBehavior() ? new RenamePrep(true, "", 0, 0, 0, 0) : RenamePrep.REFUSED;
        }
        return RenamePrep.REFUSED;
    }

    /**
     * Asks the server what a rename would change, <b>without applying it</b>, so the caller can preview and
     * filter first (#768). Delivers null when the server refuses or the edit contains an operation the mapper
     * will not perform.
     */
    public void previewRename(
            Path file, int line, int character, String newName, Consumer<WorkspaceEditMapper.Mapped> cb) {
        previewRename(file, line, character, newName, (mapped, why) -> cb.accept(mapped));
    }

    /** As above, with the server's reason when it refused the rename ("Rename to x failed" said nothing — BN9). */
    public void previewRename(
            Path file,
            int line,
            int character,
            String newName,
            java.util.function.BiConsumer<WorkspaceEditMapper.@Nullable Mapped, @Nullable String> cb) {
        LanguageServerSession s = m.sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(null, null));
            return;
        }
        EditBasis basis = WorkspaceEditDrafts.editBasis(s);
        s.rename(LspManager.uri(file), new Position(line, character), newName).whenComplete((edit, e) -> {
            EditPlan plan =
                    e != null || edit == null ? EditPlan.REFUSED : WorkspaceEditDrafts.planWorkspaceEdit(edit, basis);
            String why = e == null ? null : LspManager.failureReason(e);
            Platform.runLater(() -> {
                if (!plan.blocked().isEmpty()) {
                    m.codeActions.onEditBlocked.accept(plan.blocked());
                }
                cb.accept(plan.mapped(), why);
            });
        });
    }

    /** Renames the symbol at a 0-based position to {@code newName}; the workspace edit applies through the
     *  registered handler and {@code cb} gets overall success on the FX thread (#676). */
    public void rename(Path file, int line, int character, String newName, Consumer<Boolean> cb) {
        LanguageServerSession s = m.sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(false));
            return;
        }
        EditBasis basis = WorkspaceEditDrafts.editBasis(s);
        s.rename(LspManager.uri(file), new Position(line, character), newName)
                .whenComplete((edit, e) -> Platform.runLater(() -> {
                    if (e != null || edit == null) {
                        cb.accept(false);
                    } else {
                        m.codeActions.applyWorkspaceEdit(edit, basis, cb);
                    }
                }));
    }

    /**
     * Before {@code from} is renamed to {@code to} on disk (a Project tree rename): asks the Java server of its
     * project for the edits that go with it ({@code workspace/willRenameFiles} — the class renamed with its
     * {@code .java} file, the package declarations and imports of a moved folder) and applies them, then calls
     * {@code proceed} on the FX thread with whether the edits are in. With no Java server, or none it wants
     * made, {@code proceed} runs at once with true (Java review B15).
     */
    public void willRenameFile(Path from, Path to, Consumer<Boolean> proceed) {
        willRenameFile(from, to, proceed, (f, t, answered) -> true);
    }

    private static final java.util.regex.Pattern PACKAGE_LINE =
            java.util.regex.Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", java.util.regex.Pattern.MULTILINE);

    /** Whether {@code file}'s {@code package} line is what the folder {@code into} implies: it ends with the package. */
    static boolean packageFits(Path file, Path into) {
        String text;
        try (var in = Files.newInputStream(file)) {
            text = new String(in.readNBytes(16_384), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        }
        var m = PACKAGE_LINE.matcher(text);
        if (!m.find()) {
            return false; // the default package: whether the folder is a source root is not known here
        }
        Path tail = Path.of(m.group(1).replace('.', '/'));
        return into.toAbsolutePath().normalize().endsWith(tail);
    }

    /**
     * Whether {@code dir} is a folder with a {@code .java} file in it, at any depth. Build output, dependency and VCS
     * folders are not walked: a 40,000-file {@code node_modules} took 61 ms (sixth review BV5).
     */
    static boolean holdsJava(Path dir) {
        return holdsJava(dir, Integer.MAX_VALUE);
    }

    /**
     * {@link #holdsJava}, fast enough for the UI thread: after 5,000 entries it stops and says yes. A
     * drag's question counted a folder of text files as a Java item (eighth review BV3).
     */
    public static boolean mayHoldJava(Path dir) {
        return holdsJava(dir, QUICK_WALK);
    }

    private static final int QUICK_WALK = 5_000;

    private static boolean holdsJava(Path dir, int maxEntries) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        boolean[] found = {false};
        int[] seen = {0};
        try {
            Files.walkFileTree(dir, java.util.Set.of(), 32, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(
                        Path d, java.nio.file.attribute.BasicFileAttributes attrs) {
                    String name = d.getFileName() == null ? "" : d.getFileName().toString();
                    return !d.equals(dir) && notSources(d, name)
                            ? java.nio.file.FileVisitResult.SKIP_SUBTREE
                            : java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(
                        Path f, java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (f.toString().endsWith(".java") || ++seen[0] >= maxEntries) {
                        found[0] = true;
                        return java.nio.file.FileVisitResult.TERMINATE;
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFileFailed(Path f, java.io.IOException e) {
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (java.io.IOException | RuntimeException e) {
            return found[0];
        }
        return found[0];
    }

    private static boolean notSources(Path folder, String name) {
        if (NOT_SOURCES.contains(name)) {
            return true;
        }
        Path module = folder.getParent();
        return BUILD_OUTPUTS.contains(name)
                && module != null
                && (Files.exists(module.resolve("pom.xml"))
                        || Files.exists(module.resolve("build.gradle"))
                        || Files.exists(module.resolve("build.gradle.kts")));
    }

    /** As {@link #willRenameFile(Path, Path, Consumer)}, asking {@code fallback} when jdtls has no edit for it. */
    public void willRenameFile(Path from, Path to, Consumer<Boolean> proceed, RenameFallback fallback) {
        // a folder a Java server serves stays jdtls's: its package lines and imports are what a move breaks
        LanguageServerSession ts =
                TypeScriptServer.renamesImports(from) && (!Files.isDirectory(from) || m.javaSessionFor(from) == null)
                        ? m.serverSessionFor(TypeScriptServer.SERVER_ID, from)
                        : null;
        if (ts != null && ts.isInitialized() && ts.capabilities() != null && willRenameAdvertised(ts)) {
            // the imports of a moved or renamed .ts/.js file (or folder) follow it (JS review B105)
            EditBasis expected = WorkspaceEditDrafts.editBasis(ts);
            ts.willRenameFiles(ts.uris().toWire(LspManager.uri(from)), ts.uris().toWire(LspManager.uri(to)))
                    .whenComplete((edit, e) -> Platform.runLater(() -> {
                        if (e != null || edit == null || !LspOrganizeImports.changesText(edit)) {
                            proceed.accept(true); // nothing to update: the file is moved as it is
                        } else {
                            m.codeActions.applyWorkspaceEdit(edit, expected, proceed);
                        }
                    }));
            return;
        }
        LanguageServerSession s = m.javaSessionFor(from);
        // No Java server for the folder: nothing to ask, and no folder walked (fifth review BV5)
        if (s == null || (!from.toString().endsWith(".java") && !Files.isDirectory(from))) {
            Platform.runLater(() -> proceed.accept(true));
            return;
        }
        if (Files.isDirectory(from)) {
            // A folder is walked for .java files off the UI thread: the drop no longer waits on it (sixth BV5)
            CompletableFuture.supplyAsync(
                            () -> holdsJava(from), com.editora.process.BlockingTasks.named("lsp-rename-folder-scan"))
                    .whenComplete((java, e) -> Platform.runLater(() -> {
                        if (Boolean.TRUE.equals(java)) {
                            askWillRename(s, from, to, proceed, fallback);
                        } else {
                            proceed.accept(true);
                        }
                    }));
            return;
        }
        askWillRename(s, from, to, proceed, fallback);
    }

    /** Whether {@code s} said it wants {@code workspace/willRenameFiles}. */
    private static boolean willRenameAdvertised(LanguageServerSession s) {
        var caps = s.capabilities();
        var ws = caps == null ? null : caps.getWorkspace();
        var ops = ws == null ? null : ws.getFileOperations();
        return ops != null && ops.getWillRename() != null;
    }

    private void askWillRename(
            LanguageServerSession s, Path from, Path to, Consumer<Boolean> proceed, RenameFallback fallback) {
        Path into = to.getParent();
        if (into != null && !into.equals(from.getParent()) && Files.isDirectory(into)) {
            // jdtls answers null for a package it has not heard of: a folder made a moment ago, before the
            // watcher's batch reached it, or any new folder of an SSH project (fourth review DF3). Saying it
            // exists is idempotent, and goes out before the request on the same channel.
            s.didChangeWatchedFiles(LspFileWatching.eventsUnderRoot(
                    List.of(new WatchedFile(into, WatchedKind.CREATED)), s.root(), s.uris()));
        }
        // A .java file moved to another folder whose package line does not fit it: the case where an empty answer
        // means jdtls left it wrong. Moved main → test in the same package, or back by Undo Move, nothing is wrong
        // (fifth review BV4).
        boolean moved = into != null
                && !into.equals(from.getParent())
                && from.toString().endsWith(".java")
                && !packageFits(from, into);
        EditBasis expected = WorkspaceEditDrafts.editBasis(s);
        s.willRenameFiles(s.uris().toWire(LspManager.uri(from)), s.uris().toWire(LspManager.uri(to)))
                .whenComplete((edit, e) -> Platform.runLater(() -> {
                    if (e != null || edit == null) {
                        proceed.accept(fallback.renameWithoutEdits(from, to, false));
                    } else if (!LspOrganizeImports.changesText(edit)) {
                        proceed.accept(!moved || fallback.renameWithoutEdits(from, to, true));
                    } else {
                        m.codeActions.applyWorkspaceEdit(edit, expected, proceed);
                    }
                }));
    }

    /** Folders that hold no Java sources a move could need edits for, whatever their place. */
    private static final java.util.Set<String> NOT_SOURCES =
            com.editora.search.DependencyDirs.with(".git", ".svn", ".hg", ".gradle", ".idea");

    /** Build output folders, skipped only beside a build file: elsewhere they are packages (seventh review BR3). */
    private static final java.util.Set<String> BUILD_OUTPUTS = java.util.Set.of("target", "build", "out");
}
