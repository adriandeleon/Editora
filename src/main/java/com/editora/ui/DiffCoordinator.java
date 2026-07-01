package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import javafx.stage.FileChooser;

import com.editora.diff.ConflictParser;
import com.editora.diff.DiffService;
import com.editora.editor.EditorBuffer;
import com.editora.editor.TabContent;
import com.editora.git.GitFormat;
import com.editora.git.GitService;

import static com.editora.i18n.Messages.tr;

/**
 * The diff + merge-conflict viewer, extracted from {@link MainController} via the {@link CoordinatorHost}
 * pattern. Owns the {@link DiffService}, the diff-tab open/refresh machinery, the "apply change" hunk flow
 * (through an undoable editor buffer with Undo/Save), the compare entry points (vs HEAD / vs commit /
 * compare-with-file / Git-panel rows / a commit's file), patch export, and merge-conflict resolution.
 *
 * <p>Git-backed diffs reach the repo via the shared {@link GitCoordinator} (passed in). {@code openDiff} +
 * the {@link DiffSide} interface are package-visible so the Local File History flow ({@code MainController})
 * can reuse them. {@code MainController} keeps the {@code diff.*}/{@code merge.resolve} command registrations
 * and the tab-menu / Git-panel / project-tree entry points (delegating here), and calls
 * {@link #refreshOpenDiffs()} on window focus-regain + after a git mutation.
 */
final class DiffCoordinator {

    /** A re-fetchable side of a diff: delivers the current text (a git blob or the working copy) to a
     *  callback. Re-invoked on refresh so the diff tracks on-disk / git changes. */
    @FunctionalInterface
    interface DiffSide {
        void fetch(Consumer<String> onText);
    }

    /** Window hooks beyond {@link CoordinatorHost} that the diff flows need. */
    interface Ops {
        /** Adds a diff/merge viewer as a selected tab. */
        void addDiffTab(TabContent pane);

        /** The open buffer for {@code target} (canonical-path match), or {@code null} if not open. */
        EditorBuffer openBufferFor(Path target);

        /** Opens {@code target} in a <em>background</em> buffer (no tab switch) and returns it, or null on error. */
        EditorBuffer openBackgroundBuffer(Path target);

        /** Saves {@code buffer}; {@code true} on success. */
        boolean saveBuffer(EditorBuffer buffer);

        /** Every open diff-viewer tab's pane (for {@link #refreshOpenDiffs()}). */
        List<DiffViewerPane> openDiffPanes();

        /** The active tab's diff pane, or {@code null} when the active tab isn't a diff. */
        DiffViewerPane activeDiffPane();

        /** Start directory for the compare-with-file picker. */
        Path finderStartDir();
    }

    private final CoordinatorHost host;
    private final GitCoordinator git;
    private final Ops ops;
    private final DiffService diffService = new DiffService();

    DiffCoordinator(CoordinatorHost host, GitCoordinator git, Ops ops) {
        this.host = host;
        this.git = git;
        this.ops = ops;
    }

    /**
     * Opens a diff tab comparing two re-fetchable sides (diff computed off-thread); reports identical /
     * too-large. {@code headerLeft}/{@code headerRight} label the panes; the clean {@code leftName}/
     * {@code rightName} (real file names) drive grammar + patch labels. The pane's refresher re-fetches both
     * sides and re-renders only when the content changed.
     */
    /** Off-thread diff compute passthrough (used by the embedded Local History window's live re-diff). */
    void computeDiff(
            String left,
            String right,
            com.editora.diff.DiffEngine.DiffOptions opts,
            java.util.function.Consumer<com.editora.diff.DiffModels.DiffModel> cb) {
        diffService.compute(left, right, opts, cb);
    }

    void openDiff(
            String title,
            String headerLeft,
            String headerRight,
            String leftName,
            String rightName,
            DiffSide leftSide,
            DiffSide rightSide,
            DiffViewerPane.EditableSide editableSide,
            Path target) {
        leftSide.fetch(leftText -> rightSide.fetch(rightText -> diffService.compute(leftText, rightText, model -> {
            if (model == null) {
                host.setStatus(tr("status.diff.tooLarge"));
                return;
            }
            DiffViewerPane pane = new DiffViewerPane(
                    title,
                    headerLeft,
                    headerRight,
                    leftName,
                    rightName,
                    leftText,
                    rightText,
                    model,
                    host.settings().getFontFamily(),
                    host.settings().getFontSize(),
                    host.settings().isShowLineNumbers(),
                    target == null ? null : target.toString());
            pane.setOnExportPatch(this::exportPatch);
            // "Apply change" arrows write the hunk into the local/editable file (via an undoable
            // editor buffer), with Undo + Save acting on that buffer.
            if (editableSide != DiffViewerPane.EditableSide.NONE && target != null) {
                pane.setEditable(
                        editableSide,
                        newText -> applyToLocal(target, newText),
                        () -> undoLocal(target),
                        () -> saveLocal(target));
            }
            // Refresh: re-fetch both sides; re-render only if the content actually changed
            // (so a focus-regain with no change keeps the view + scroll position).
            pane.setRefresher(() -> leftSide.fetch(l -> rightSide.fetch(r -> {
                if (pane.matches(l, r)) {
                    return;
                }
                diffService.compute(l, r, m -> {
                    if (m != null) {
                        pane.updateContent(l, r, m);
                    }
                });
            })));
            ops.addDiffTab(pane);
            if (model.isEmpty()) {
                host.setStatus(tr("status.diff.identical"));
            }
        })));
    }

    /** Re-fetches every open diff tab's sides (run on window focus-regain + after a git mutation), so a
     *  file changed on disk or by a git command is reflected. Each pane skips the rebuild when unchanged. */
    void refreshOpenDiffs() {
        for (DiffViewerPane dp : ops.openDiffPanes()) {
            dp.refresh();
        }
    }

    /** Runs {@code op} on the active diff tab's pane, or reports there isn't one. */
    void withActiveDiff(Consumer<DiffViewerPane> op) {
        DiffViewerPane dp = ops.activeDiffPane();
        if (dp != null) {
            op.accept(dp);
        } else {
            host.setStatus(tr("status.diff.noActiveDiff"));
        }
    }

    /** Writes new text into the local file {@code target} via an undoable editor buffer (opened in the
     *  background if not already open), marking it dirty, then re-diffs every tab. Used by the diff
     *  "apply change" arrows and by the Local File History "restore revision" flow. */
    void applyToLocal(Path target, String newText) {
        EditorBuffer b = bufferForApply(target);
        if (b == null) {
            host.setStatus(tr("status.diff.applyFailed", target.getFileName()));
            return;
        }
        b.getArea().replaceText(newText);
        host.setStatus(tr("status.diff.applied"));
        refreshOpenDiffs();
    }

    /** Undoes the last applied change on {@code target}'s buffer (the buffer's own undo). */
    private void undoLocal(Path target) {
        EditorBuffer b = ops.openBufferFor(target);
        if (b != null && b.getArea().isUndoAvailable()) {
            b.getArea().undo();
            refreshOpenDiffs();
        }
    }

    /** Saves {@code target}'s buffer (persisting the applied changes) and re-diffs. */
    private void saveLocal(Path target) {
        EditorBuffer b = ops.openBufferFor(target);
        if (b == null) {
            return;
        }
        if (ops.saveBuffer(b)) {
            host.setStatus(tr("status.diff.saved", target.getFileName()));
            refreshOpenDiffs();
        }
    }

    /** The editable buffer to apply a diff hunk into: the open buffer for {@code target}, else a fresh one
     *  opened in the background (no tab switch, so the diff stays focused). */
    private EditorBuffer bufferForApply(Path target) {
        EditorBuffer open = ops.openBufferFor(target);
        return open != null ? open : ops.openBackgroundBuffer(target);
    }

    /** Diff the active file's working copy against its committed (HEAD) version. */
    void diffActiveVsHead() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || b.getPath() == null) {
            host.setStatus(tr("status.diff.noFile"));
            return;
        }
        diffPathVsHead(b.getPath());
    }

    /** Opens a read-only diff of {@code path} at HEAD (left) vs its working-tree text (right). */
    void diffPathVsHead(Path path) {
        if (path == null || Files.isDirectory(path)) {
            host.setStatus(tr("status.diff.noFile"));
            return;
        }
        if (git.reportIfNoRepo()) {
            return;
        }
        // Capture the repo root at open time and close over it: the refresher re-runs this fetcher, and the
        // live git.repoRoot() goes null while a (non-buffer) diff tab is the active tab in a No-Project window
        // — re-reading it then would fetch HEAD against a null root and blank the left pane.
        Path root = git.repoRoot();
        String rel = GitService.repoRelative(root, path);
        if (rel == null) {
            host.setStatus(tr("status.diff.notInRepo"));
            return;
        }
        String name = path.getFileName().toString();
        openDiff(
                tr("diff.title.vsHead", name),
                tr("diff.side.head"),
                tr("diff.side.working"),
                name,
                name,
                cb -> git.service().show(root, "HEAD:" + rel, cb),
                cb -> cb.accept(worktreeText(path)),
                DiffViewerPane.EditableSide.RIGHT,
                path);
    }

    /** Pick a second file and diff it against the active file. */
    void compareActiveWithFile() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || b.getPath() == null) {
            host.setStatus(tr("status.diff.noFile"));
            return;
        }
        Path basePath = b.getPath();
        String leftName = basePath.getFileName().toString();
        FileFinder picker = new FileFinder(
                ops::finderStartDir,
                chosen -> {
                    String rightName = chosen.getFileName().toString();
                    // Both sides re-fetch via worktreeText (open buffer's live text if open, else disk), so the
                    // diff tracks either file changing on disk.
                    openDiff(
                            tr("diff.title.compare", leftName, rightName),
                            leftName,
                            rightName,
                            leftName,
                            rightName,
                            cb -> cb.accept(worktreeText(basePath)),
                            cb -> cb.accept(worktreeText(chosen)),
                            DiffViewerPane.EditableSide.LEFT,
                            basePath);
                },
                false,
                tr("diff.compareTitle"));
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    /** Diff the active file against a commit chosen from its history. */
    void diffActiveVsCommit() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || b.getPath() == null) {
            host.setStatus(tr("status.diff.noFile"));
            return;
        }
        if (git.reportIfNoRepo()) {
            return;
        }
        Path root = git.repoRoot(); // capture at open time; see diffPathVsHead
        String rel = GitService.repoRelative(root, b.getPath());
        if (rel == null) {
            host.setStatus(tr("status.diff.notInRepo"));
            return;
        }
        Path path = b.getPath();
        String name = path.getFileName().toString();
        git.service().log(root, path, 80, commits -> {
            if (commits.isEmpty()) {
                host.setStatus(tr("status.diff.noHistory"));
                return;
            }
            QuickOpen<GitService.Commit> picker = new QuickOpen<>(
                    tr("diff.commitPickerTitle"),
                    tr("diff.commitPickerPrompt"),
                    () -> commits,
                    c -> c.shortHash() + "  " + c.subject(),
                    c -> c.date() + " · " + c.author(),
                    c -> c.shortHash() + " " + c.subject() + " " + c.author() + " " + c.date(),
                    chosen -> openDiff(
                            tr("diff.title.vsCommit", name, chosen.shortHash()),
                            chosen.shortHash(),
                            tr("diff.side.working"),
                            name,
                            name,
                            cb -> git.service().show(root, chosen.hash() + ":" + rel, cb),
                            cb -> cb.accept(worktreeText(path)),
                            DiffViewerPane.EditableSide.RIGHT,
                            path));
            picker.setOverlayHost(host.overlayHost());
            picker.show(host.window());
        });
    }

    /** Diff a Git-panel file row: staged → index↔HEAD, unstaged → worktree↔index. */
    void diffGitPanelFile(String repoRel, boolean staged) {
        Path root = git.repoRoot(); // capture at open time; see diffPathVsHead
        if (root == null) {
            return;
        }
        Path abs = root.resolve(repoRel);
        String name = abs.getFileName().toString();
        if (staged) {
            // index↔HEAD: neither side is the working file, so no "apply" (read-only diff).
            openDiff(
                    tr("diff.title.staged", name),
                    tr("diff.side.head"),
                    tr("diff.side.staged"),
                    name,
                    name,
                    cb -> git.service().show(root, "HEAD:" + repoRel, cb),
                    cb -> git.service().show(root, ":" + repoRel, cb),
                    DiffViewerPane.EditableSide.NONE,
                    null);
        } else {
            openDiff(
                    tr("diff.title.unstaged", name),
                    tr("diff.side.staged"),
                    tr("diff.side.working"),
                    name,
                    name,
                    cb -> git.service().show(root, ":" + repoRel, cb),
                    cb -> cb.accept(worktreeText(abs)),
                    DiffViewerPane.EditableSide.RIGHT,
                    abs);
        }
    }

    /** Diff a commit's version of a file against its parent (commit~1 ↔ commit), read-only. */
    void diffCommitFile(String hash, String repoRel) {
        Path root = git.repoRoot(); // capture at open time; see diffPathVsHead
        if (root == null) {
            return;
        }
        String name = repoRel.substring(repoRel.lastIndexOf('/') + 1);
        openDiff(
                tr("diff.title.commitFile", name, GitFormat.shortHash(hash)),
                tr("diff.side.parent"),
                tr("diff.title.vsCommitShort", GitFormat.shortHash(hash)),
                name,
                name,
                cb -> git.service().show(root, hash + "~1:" + repoRel, cb),
                cb -> git.service().show(root, hash + ":" + repoRel, cb),
                DiffViewerPane.EditableSide.NONE,
                null);
    }

    /** The current working-tree text of {@code abs}: an open buffer's (incl. unsaved edits) if open,
     *  else the file on disk ("" when unreadable / deleted). */
    private String worktreeText(Path abs) {
        EditorBuffer b = ops.openBufferFor(abs);
        if (b != null) {
            return b.text();
        }
        try {
            return Files.exists(abs) ? Files.readString(abs) : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** Saves a unified-diff patch (the diff viewer's export action) via a file chooser. */
    private void exportPatch(String patch) {
        if (patch == null || patch.isEmpty()) {
            host.setStatus(tr("status.diff.identical"));
            return;
        }
        FileChooser fc = new FileChooser();
        fc.setTitle(tr("diff.exportPatch"));
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Patch (*.patch)", "*.patch"));
        fc.setInitialFileName("changes.patch");
        java.io.File f = fc.showSaveDialog(host.window());
        if (f == null) {
            return;
        }
        try {
            Files.writeString(f.toPath(), patch);
            host.setStatus(tr("status.diff.patchSaved", f.getName()));
        } catch (IOException e) {
            host.setStatus(tr("status.diff.patchFailed", e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    /** Opens the merge-conflict resolution view for the active buffer (if it has conflict markers). */
    void resolveConflicts() {
        EditorBuffer b = host.activeBuffer();
        if (b == null) {
            host.setStatus(tr("status.diff.noFile"));
            return;
        }
        String text = b.text();
        if (!ConflictParser.hasConflictMarkers(text)) {
            host.setStatus(tr("status.merge.noConflicts"));
            return;
        }
        List<String> raw = List.of(text.replace("\r\n", "\n").split("\n", -1));
        ConflictParser.ConflictFile cf = ConflictParser.parse(raw);
        String name =
                b.getPath() == null ? b.getTitle() : b.getPath().getFileName().toString();
        MergeViewerPane pane = new MergeViewerPane(
                tr("merge.title", name),
                cf,
                host.settings().getFontFamily(),
                host.settings().getFontSize(),
                resolvedLines -> {
                    b.getArea().replaceText(String.join("\n", resolvedLines));
                    host.setStatus(tr("status.merge.applied"));
                });
        ops.addDiffTab(pane);
    }
}
