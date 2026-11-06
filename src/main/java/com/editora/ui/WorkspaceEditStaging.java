package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.ui.LspCoordinator.WorkspaceFileIdentity;
import com.editora.ui.LspCoordinator.WorkspaceFileOperations;
import org.jspecify.annotations.Nullable;

/**
 * The filesystem half of a language server's workspace edit ({@link LspWorkspaceEdits}): its creates, renames and
 * deletes staged as one recoverable batch before any editor text changes, rolled back when a later step fails, and
 * committed (the temporary copies removed) once the edit is in. A transaction that moves files keeps a journal on
 * disk until it ends ({@link com.editora.lsp.WorkspaceEditJournal}). Runs on the workspace-edit worker or, for the
 * synchronous path, on the FX thread; it holds no state between transactions.
 */
final class WorkspaceEditStaging {

    private final WorkspaceFileOperations workspaceFiles;
    /** Where journals are kept, read when a transaction begins; null keeps none. */
    private final java.util.function.Supplier<@Nullable Path> journalDir;

    WorkspaceEditStaging(
            WorkspaceFileOperations workspaceFiles, java.util.function.Supplier<@Nullable Path> journalDir) {
        this.workspaceFiles = workspaceFiles;
        this.journalDir = journalDir;
    }

    /** Normalized rename source → destination for the renames that are staged on disk. */
    static Map<Path, Path> movedTo(java.util.List<StagedRename> renames) {
        Map<Path, Path> moved = new java.util.HashMap<>();
        for (StagedRename item : renames) {
            moved.put(
                    item.rename().from().toAbsolutePath().normalize(),
                    item.rename().to().toAbsolutePath().normalize());
        }
        return moved;
    }

    /**
     * What the server hears of the files an edit created, moved or deleted on disk. A server reads a file it has
     * not got open from disk only when told it changed, and a folder nobody watches tells it nothing (BN1).
     */
    static java.util.List<com.editora.lsp.LspManager.WatchedFile> resourceEvents(
            java.util.List<StagedRename> renames,
            java.util.List<StagedCreate> creates,
            java.util.List<StagedDelete> deletes) {
        java.util.List<com.editora.lsp.LspManager.WatchedFile> out = new java.util.ArrayList<>();
        for (StagedRename r : renames) {
            out.add(new com.editora.lsp.LspManager.WatchedFile(
                    r.rename().from(), com.editora.lsp.LspManager.WatchedKind.DELETED));
            out.add(new com.editora.lsp.LspManager.WatchedFile(
                    r.rename().to(), com.editora.lsp.LspManager.WatchedKind.CREATED));
        }
        for (StagedCreate c : creates) {
            out.add(new com.editora.lsp.LspManager.WatchedFile(
                    c.operation().file(), com.editora.lsp.LspManager.WatchedKind.CREATED));
        }
        for (StagedDelete d : deletes) {
            out.add(new com.editora.lsp.LspManager.WatchedFile(
                    d.operation().file(), com.editora.lsp.LspManager.WatchedKind.DELETED));
        }
        return out;
    }

    boolean resourceStateCurrent(
            WorkspaceFileOperations files,
            java.util.List<StagedCreate> creates,
            java.util.List<StagedRename> renames,
            java.util.List<StagedDelete> deletes) {
        try {
            for (StagedCreate item : creates) {
                Path file = item.operation().file().toAbsolutePath().normalize();
                if (item.created()
                        && (!files.exists(file) || !java.util.Objects.equals(item.identity(), files.identity(file)))) {
                    return false;
                }
            }
            for (StagedRename item : renames) {
                Path from = item.rename().from().toAbsolutePath().normalize();
                Path to = item.rename().to().toAbsolutePath().normalize();
                // After a case-only rename on a case-insensitive volume the old spelling still "exists".
                if ((files.exists(from) && !WorkspaceEditFiles.sameFileInAnotherCase(files, from, to))
                        || !files.exists(to)
                        || !java.util.Objects.equals(item.identity(), files.identity(to))) {
                    return false;
                }
            }
            for (StagedDelete item : deletes) {
                Path file = item.operation().file().toAbsolutePath().normalize();
                if (files.exists(file)
                        || !files.exists(item.stage())
                        || !java.util.Objects.equals(item.identity(), files.identity(item.stage()))) {
                    return false;
                }
            }
            return true;
        } catch (java.io.IOException | RuntimeException failure) {
            return false;
        }
    }

    static final class WorkspaceTransactionStatus {
        boolean rollbackFailed;
        com.editora.lsp.WorkspaceEditJournal journal = com.editora.lsp.WorkspaceEditJournal.begin(null);
    }

    /**
     * Starts a transaction. One that moves files keeps a journal on disk until it has committed or rolled
     * back, so a crash in between leaves a record of what is staged where (see
     * {@link com.editora.lsp.WorkspaceEditJournal}).
     */
    WorkspaceTransactionStatus beginTransaction(com.editora.lsp.WorkspaceEditMapper.Mapped mapped) {
        WorkspaceTransactionStatus transaction = new WorkspaceTransactionStatus();
        if (!mapped.creates().isEmpty()
                || !mapped.renames().isEmpty()
                || !mapped.deletes().isEmpty()) {
            transaction.journal = com.editora.lsp.WorkspaceEditJournal.begin(journalDir.get());
        }
        return transaction;
    }

    /**
     * Ends a transaction whose staged files have all been committed or put back. After an incomplete
     * rollback the journal stays, so the files it could not restore are offered at the next project open.
     */
    void endTransaction(WorkspaceTransactionStatus transaction) {
        if (transaction.rollbackFailed) {
            transaction.journal.abandon();
        } else {
            transaction.journal.close();
        }
    }

    record StagedCreate(
            com.editora.lsp.WorkspaceEditMapper.FileCreate operation,
            @Nullable Path overwrittenBackup,
            boolean created,
            @Nullable WorkspaceFileIdentity identity) {}

    record StagedDelete(
            com.editora.lsp.WorkspaceEditMapper.FileDelete operation,
            Path stage,
            @Nullable WorkspaceFileIdentity identity) {}

    java.util.@Nullable List<StagedCreate> stageCreates(
            java.util.List<com.editora.lsp.WorkspaceEditMapper.FileCreate> operations,
            WorkspaceTransactionStatus transaction) {
        java.util.List<StagedCreate> staged = new java.util.ArrayList<>();
        try {
            for (var operation : operations) {
                Path file = operation.file().toAbsolutePath().normalize();
                if (workspaceFiles.exists(file)) {
                    if (operation.ignoreIfExists()) {
                        staged.add(new StagedCreate(operation, null, false, null));
                        continue;
                    }
                    if (!operation.overwrite() || workspaceFiles.isDirectory(file)) {
                        rollbackCreates(staged, transaction);
                        return null;
                    }
                    Path backup = temporarySibling(file, ".created-overwrite");
                    transaction.journal.creating(file, backup);
                    workspaceFiles.move(file, backup);
                    staged.add(new StagedCreate(operation, backup, false, null));
                    workspaceFiles.createFile(file);
                    staged.set(staged.size() - 1, new StagedCreate(operation, backup, true, null));
                    staged.set(
                            staged.size() - 1,
                            new StagedCreate(operation, backup, true, workspaceFiles.identity(file)));
                } else {
                    Path parent = file.getParent();
                    if (parent != null) {
                        workspaceFiles.createDirectories(parent);
                    }
                    transaction.journal.creating(file, null);
                    workspaceFiles.createFile(file);
                    staged.add(new StagedCreate(operation, null, true, null));
                    staged.set(
                            staged.size() - 1, new StagedCreate(operation, null, true, workspaceFiles.identity(file)));
                }
            }
            return staged;
        } catch (java.io.IOException | RuntimeException failure) {
            rollbackCreates(staged, transaction);
            return null;
        }
    }

    void rollbackCreates(java.util.List<StagedCreate> staged, WorkspaceTransactionStatus transaction) {
        for (int i = staged.size() - 1; i >= 0; i--) {
            StagedCreate item = staged.get(i);
            Path file = item.operation().file().toAbsolutePath().normalize();
            try {
                if (item.created() && workspaceFiles.exists(file)) {
                    if (workspaceFiles.size(file) == 0) {
                        workspaceFiles.deleteIfExists(file);
                    } else {
                        transaction.rollbackFailed = true;
                    }
                }
                if (item.overwrittenBackup() != null && workspaceFiles.exists(item.overwrittenBackup())) {
                    if (workspaceFiles.exists(file)) {
                        transaction.rollbackFailed = true;
                    } else {
                        workspaceFiles.move(item.overwrittenBackup(), file);
                    }
                }
            } catch (java.io.IOException ignored) {
                transaction.rollbackFailed = true;
                // Continue restoring independent paths.
            }
        }
    }

    void commitCreates(java.util.List<StagedCreate> staged) {
        for (StagedCreate item : staged) {
            deleteRecursively(item.overwrittenBackup());
        }
    }

    java.util.@Nullable List<StagedDelete> stageDeletes(
            java.util.List<com.editora.lsp.WorkspaceEditMapper.FileDelete> operations,
            WorkspaceTransactionStatus transaction) {
        java.util.List<StagedDelete> staged = new java.util.ArrayList<>();
        try {
            for (var operation : operations) {
                Path file = operation.file().toAbsolutePath().normalize();
                if (!workspaceFiles.exists(file)) {
                    if (operation.ignoreIfNotExists()) {
                        continue;
                    }
                    rollbackDeletes(staged, transaction);
                    return null;
                }
                if (workspaceFiles.isDirectory(file)
                        && !operation.recursive()
                        && !workspaceFiles.list(file).isEmpty()) {
                    rollbackDeletes(staged, transaction);
                    return null;
                }
                Path stage = temporarySibling(file, ".deleted");
                transaction.journal.deleting(file, stage);
                workspaceFiles.move(file, stage);
                staged.add(new StagedDelete(operation, stage, null));
                staged.set(staged.size() - 1, new StagedDelete(operation, stage, workspaceFiles.identity(stage)));
            }
            return staged;
        } catch (java.io.IOException | RuntimeException failure) {
            rollbackDeletes(staged, transaction);
            return null;
        }
    }

    void rollbackDeletes(java.util.List<StagedDelete> staged, WorkspaceTransactionStatus transaction) {
        for (int i = staged.size() - 1; i >= 0; i--) {
            StagedDelete item = staged.get(i);
            try {
                Path original = item.operation().file().toAbsolutePath().normalize();
                if (workspaceFiles.exists(original)) {
                    transaction.rollbackFailed = true;
                } else {
                    workspaceFiles.move(item.stage(), original);
                }
            } catch (java.io.IOException ignored) {
                transaction.rollbackFailed = true;
                // Continue restoring independent paths.
            }
        }
    }

    void commitDeletes(java.util.List<StagedDelete> staged) {
        for (StagedDelete item : staged) {
            deleteRecursively(item.stage());
        }
    }

    private void deleteRecursively(@Nullable Path path) {
        if (path == null || !workspaceFiles.exists(path)) {
            return;
        }
        try {
            workspaceFiles.walk(path).stream()
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            workspaceFiles.deleteIfExists(p);
                        } catch (java.io.IOException ignored) {
                            // A committed edit should not be rolled back because cleanup of a hidden stage failed.
                        }
                    });
        } catch (java.io.IOException ignored) {
            // Best effort cleanup.
        }
    }

    /** One completed filesystem rename plus the temporary paths used to make the batch transactional. */
    record StagedRename(
            com.editora.lsp.WorkspaceEditMapper.FileRename rename,
            Path stage,
            @Nullable Path overwrittenBackup,
            @Nullable WorkspaceFileIdentity identity) {}

    /**
     * Executes every file move as one recoverable batch before editor text is touched. Sources and overwritten
     * destinations are first moved aside; if any later move fails, every path is restored from those stages.
     */
    java.util.@Nullable List<StagedRename> stageRenames(
            java.util.List<com.editora.lsp.WorkspaceEditMapper.FileRename> renames,
            WorkspaceTransactionStatus transaction) {
        java.util.List<StagedRename> staged = new java.util.ArrayList<>();
        java.util.Set<Path> sources = new java.util.HashSet<>();
        java.util.Set<Path> destinations = new java.util.HashSet<>();
        try {
            for (var r : renames) {
                Path from = r.from().toAbsolutePath().normalize();
                Path to = r.to().toAbsolutePath().normalize();
                if (samePathSpelling(from, to)) {
                    continue;
                }
                if (!sources.add(from) || !destinations.add(to) || !workspaceFiles.isRegularFile(from)) {
                    return null;
                }
            }
            // Validate collisions only after every source is known. A destination may legitimately be
            // another source in the same batch (A→B, B→C), because all sources are staged first.
            for (var r : renames) {
                Path from = r.from().toAbsolutePath().normalize();
                Path to = r.to().toAbsolutePath().normalize();
                if (!samePathSpelling(from, to)
                        && !r.overwrite()
                        && workspaceFiles.exists(to)
                        && !sources.contains(to)
                        && !sameFileInAnotherCase(from, to)) {
                    return null;
                }
            }
            for (var r : renames) {
                Path from = r.from().toAbsolutePath().normalize();
                Path to = r.to().toAbsolutePath().normalize();
                if (samePathSpelling(from, to)) {
                    continue;
                }
                Path stage = temporarySibling(from, ".source");
                transaction.journal.renaming(
                        from, stage, r.to().toAbsolutePath().normalize());
                workspaceFiles.move(from, stage);
                staged.add(new StagedRename(r, stage, null, null));
                staged.set(staged.size() - 1, new StagedRename(r, stage, null, workspaceFiles.identity(stage)));
            }
            for (int i = 0; i < staged.size(); i++) {
                StagedRename item = staged.get(i);
                Path to = item.rename().to().toAbsolutePath().normalize();
                Path parent = to.getParent();
                if (parent != null) {
                    workspaceFiles.createDirectories(parent);
                }
                Path backup = null;
                if (workspaceFiles.exists(to)) {
                    backup = temporarySibling(to, ".destination");
                    transaction.journal.replacing(to, backup);
                    workspaceFiles.move(to, backup);
                    item = new StagedRename(item.rename(), item.stage(), backup, item.identity());
                    staged.set(i, item);
                }
                workspaceFiles.move(item.stage(), to);
            }
            return List.copyOf(staged);
        } catch (java.io.IOException | RuntimeException failure) {
            rollbackRenames(staged, transaction);
            return null;
        }
    }

    /**
     * Whether a rename is a no-op. Compared as text, not with {@code Path.equals}: on Windows that ignores
     * case, so a case-only rename ({@code Httpclient.java} → {@code HttpClient.java}) was dropped from the
     * batch while its text edits were applied.
     */
    private static boolean samePathSpelling(Path from, Path to) {
        return from.toString().equals(to.toString());
    }

    /**
     * Whether {@code to} "exists" only because it is {@code from} itself on a case-insensitive volume — a
     * case-only rename, not a collision with someone else's file. Staging then performs the two-step move
     * such a rename needs.
     */
    private boolean sameFileInAnotherCase(Path from, Path to) {
        return WorkspaceEditFiles.sameFileInAnotherCase(workspaceFiles, from, to);
    }

    void commitRenames(java.util.List<StagedRename> staged) {
        for (StagedRename item : staged) {
            deleteRecursively(item.overwrittenBackup());
        }
    }

    private Path temporarySibling(Path file, String suffix) throws java.io.IOException {
        Path parent = file.getParent();
        if (parent == null) {
            throw new java.io.IOException("file has no parent: " + file);
        }
        Path temp = workspaceFiles.createTempFile(parent, ".editora-lsp-", suffix);
        workspaceFiles.deleteIfExists(temp);
        return temp;
    }

    void rollbackRenames(java.util.List<StagedRename> staged, WorkspaceTransactionStatus transaction) {
        // Put completed destinations back into their per-source stages first. This also handles chains such
        // as A→B and B→C without one restored source overwriting another staged source.
        for (int i = staged.size() - 1; i >= 0; i--) {
            StagedRename item = staged.get(i);
            Path to = item.rename().to().toAbsolutePath().normalize();
            try {
                if (!workspaceFiles.exists(item.stage()) && workspaceFiles.exists(to)) {
                    workspaceFiles.move(to, item.stage());
                }
            } catch (java.io.IOException ignored) {
                transaction.rollbackFailed = true;
                // Continue restoring the other independently staged paths.
            }
        }
        for (int i = staged.size() - 1; i >= 0; i--) {
            StagedRename item = staged.get(i);
            Path from = item.rename().from().toAbsolutePath().normalize();
            Path to = item.rename().to().toAbsolutePath().normalize();
            try {
                if (workspaceFiles.exists(item.stage())) {
                    if (workspaceFiles.exists(from)) {
                        transaction.rollbackFailed = true;
                    } else {
                        workspaceFiles.move(item.stage(), from);
                    }
                }
                if (item.overwrittenBackup() != null
                        && workspaceFiles.exists(item.overwrittenBackup())
                        && !workspaceFiles.exists(to)) {
                    workspaceFiles.move(item.overwrittenBackup(), to);
                } else if (item.overwrittenBackup() != null && workspaceFiles.exists(item.overwrittenBackup())) {
                    // The completed destination could not be moved back to its source stage. Replacing it
                    // here would destroy that source's only surviving copy, so retain both paths for recovery.
                    transaction.rollbackFailed = true;
                }
            } catch (java.io.IOException ignored) {
                transaction.rollbackFailed = true;
                // Best effort after the original failure; all recoverable stages are attempted.
            }
        }
    }
}
