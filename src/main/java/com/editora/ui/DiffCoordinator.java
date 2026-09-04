package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javafx.scene.input.Clipboard;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import com.editora.diff.BinaryDiff;
import com.editora.diff.ConflictParser;
import com.editora.diff.DiffEngine;
import com.editora.diff.DiffModels.DiffModel;
import com.editora.diff.DiffService;
import com.editora.diff.DiffText;
import com.editora.diff.DirectoryDiff;
import com.editora.diff.PatchParser;
import com.editora.diff.PatchWriter;
import com.editora.diff.ThreeWayMerge;
import com.editora.editor.EditorBuffer;
import com.editora.editor.TabContent;
import com.editora.editorconfig.EditorConfigCharset;
import com.editora.git.GitFormat;
import com.editora.git.GitService;
import com.editora.git.GitStatus;
import com.editora.git.GitStatus.FileEntry;

import static com.editora.i18n.Messages.tr;

/**
 * The diff + merge-conflict viewer, extracted from {@link MainController} via the {@link CoordinatorHost}
 * pattern. Owns the {@link DiffService}, the diff-tab open/refresh machinery, the "apply change" hunk flow
 * (through an undoable editor buffer with Undo/Save), the compare entry points (vs HEAD / vs commit /
 * compare-with-file / Git-panel rows / a commit's file), patch export, and merge-conflict resolution.
 *
 * <p>Git-backed diffs reach the repo via the shared {@link GitCoordinator} (passed in). {@code computeDiff}
 * + {@code applyToLocal}/{@code undoLocal}/{@code saveLocal} are package-visible so the Local File History
 * tool window ({@code FileHistoryPanel}, via {@code HistoryCoordinator}) reuses them for its inline revision
 * diff + per-hunk apply chevrons. {@code MainController} keeps the {@code diff.*}/{@code merge.resolve} command registrations
 * and the tab-menu / Git-panel / project-tree entry points (delegating here), and calls
 * {@link #refreshOpenDiffs()} on window focus-regain + after a git mutation.
 */
final class DiffCoordinator {

    record GitReviewTarget(String path, String leftPath, char status) {}

    private record BuiltDiff(DiffViewerPane pane, DiffModel model) {}

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

        /** Applies window-specific affordances to every diff pane, including panes nested in a review. */
        default void prepareDiffPane(DiffViewerPane pane) {}

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

        /**
         * The {@code .editorconfig} charset name for {@code file} (or {@code null} when EditorConfig is off /
         * the file is remote / has no rule), so a diff decodes git blobs + a closed working file the same way
         * the editor would read them — not force-decoding as UTF-8.
         */
        String editorConfigCharset(Path file);

        /** Opens a changed file and moves the editor to its one-based line. */
        void openAt(Path file, int line);
    }

    private final CoordinatorHost host;
    private final GitCoordinator git;
    private final Ops ops;
    private final DiffService diffService = new DiffService();
    private final ExecutorService fileReadExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "diff-file-read");
        t.setDaemon(true);
        return t;
    });
    private DiffEngine.DiffOptions lastDiffOptions = DiffEngine.DiffOptions.DEFAULT;

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
        openDiff(
                title,
                headerLeft,
                headerRight,
                leftName,
                rightName,
                leftSide,
                rightSide,
                editableSide,
                target,
                p -> {});
    }

    private void openDiff(
            String title,
            String headerLeft,
            String headerRight,
            String leftName,
            String rightName,
            DiffSide leftSide,
            DiffSide rightSide,
            DiffViewerPane.EditableSide editableSide,
            Path target,
            Consumer<DiffViewerPane> configure) {
        buildDiffPane(
                title,
                headerLeft,
                headerRight,
                leftName,
                rightName,
                leftSide,
                rightSide,
                editableSide,
                target,
                configure,
                built -> {
                    if (built == null) {
                        return;
                    }
                    ops.addDiffTab(built.pane());
                    if (built.model().isEmpty()) {
                        host.setStatus(tr("status.diff.identical"));
                    }
                });
    }

    private void buildDiffPane(
            String title,
            String headerLeft,
            String headerRight,
            String leftName,
            String rightName,
            DiffSide leftSide,
            DiffSide rightSide,
            DiffViewerPane.EditableSide editableSide,
            Path target,
            Consumer<DiffViewerPane> configure,
            Consumer<BuiltDiff> onReady) {
        leftSide.fetch(leftText ->
                rightSide.fetch(rightText -> diffService.compute(leftText, rightText, lastDiffOptions, model -> {
                    if (model == null) {
                        host.setStatus(tr("status.diff.tooLarge"));
                        onReady.accept(null);
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
                    ops.prepareDiffPane(pane);
                    pane.setOnExportPatch(this::exportPatch);
                    pane.setOptions(lastDiffOptions);
                    AtomicLong generation = new AtomicLong();
                    String[] current = {leftText, rightText};
                    boolean[] swapped = {false};
                    DiffEngine.DiffOptions[] currentOptions = {lastDiffOptions};
                    pane.setOnSwapRequested((newLeft, newRight) -> {
                        long requested = generation.incrementAndGet();
                        diffService.compute(newLeft, newRight, currentOptions[0], next -> {
                            if (requested != generation.get()) {
                                pane.cancelSwap();
                                return;
                            }
                            if (next == null) {
                                pane.cancelSwap();
                                host.setStatus(tr("status.diff.tooLarge"));
                                return;
                            }
                            current[0] = newLeft;
                            current[1] = newRight;
                            swapped[0] = !swapped[0];
                            pane.swapSides(next);
                        });
                    });
                    pane.setOnOptionsChanged(opts -> {
                        lastDiffOptions = opts;
                        currentOptions[0] = opts;
                        long requested = generation.incrementAndGet();
                        String left = pane.editableSide() == DiffViewerPane.EditableSide.LEFT && pane.hasResultEditor()
                                ? pane.resultText()
                                : current[0];
                        String right =
                                pane.editableSide() == DiffViewerPane.EditableSide.RIGHT && pane.hasResultEditor()
                                        ? pane.resultText()
                                        : current[1];
                        diffService.compute(left, right, opts, next -> {
                            if (requested == generation.get()) {
                                if (pane.hasResultEditor()) {
                                    pane.updateDraftContent(left, right, next);
                                } else {
                                    pane.updateContent(left, right, next);
                                }
                            }
                        });
                    });
                    // "Apply change" arrows write the hunk into the local/editable file (via an undoable
                    // editor buffer), with Undo + Save acting on that buffer.
                    if (editableSide != DiffViewerPane.EditableSide.NONE && target != null) {
                        pane.setEditable(
                                editableSide,
                                newText -> {
                                    if (!pane.matchesEditableText(worktreeText(target))) {
                                        host.setStatus(tr("status.diff.localStale"));
                                        pane.refresh();
                                        return false;
                                    }
                                    if (!applyToLocal(target, newText)) {
                                        return false;
                                    }
                                    current[pane.editableSide() == DiffViewerPane.EditableSide.RIGHT ? 1 : 0] = newText;
                                    return true;
                                },
                                () -> undoLocal(target),
                                () -> saveLocal(target));
                        pane.setOnResultEdited(draft -> {
                            long requested = generation.incrementAndGet();
                            String left = pane.editableSide() == DiffViewerPane.EditableSide.LEFT ? draft : current[0];
                            String right =
                                    pane.editableSide() == DiffViewerPane.EditableSide.RIGHT ? draft : current[1];
                            diffService.compute(left, right, currentOptions[0], next -> {
                                if (requested == generation.get()
                                        && pane.hasResultEditor()
                                        && java.util.Objects.equals(draft, pane.resultText())) {
                                    pane.updateDraftContent(left, right, next);
                                }
                            });
                        });
                    }
                    if (target != null) {
                        pane.setGitHunkActions(
                                Set.of(DiffViewerPane.GitHunkAction.OPEN),
                                request -> ops.openAt(target, request.targetLine()));
                    }
                    // Refresh: re-fetch both sides; re-render only if the content actually changed
                    // (so a focus-regain with no change keeps the view + scroll position).
                    pane.setRefresher(() -> {
                        long requested = generation.incrementAndGet();
                        leftSide.fetch(l -> rightSide.fetch(r -> {
                            if (requested != generation.get()) {
                                return;
                            }
                            String displayLeft = swapped[0] ? r : l;
                            String displayRight = swapped[0] ? l : r;
                            String editable = pane.editableSide() == DiffViewerPane.EditableSide.RIGHT
                                    ? displayRight
                                    : displayLeft;
                            if (pane.hasDirtyResult()) {
                                if (!pane.matchesEditableText(editable)) {
                                    host.setStatus(tr("status.diff.localStale"));
                                }
                                return;
                            }
                            if (pane.matches(displayLeft, displayRight)) {
                                return;
                            }
                            diffService.compute(displayLeft, displayRight, currentOptions[0], m -> {
                                if (requested == generation.get()) {
                                    current[0] = displayLeft;
                                    current[1] = displayRight;
                                    pane.updateContent(displayLeft, displayRight, m);
                                }
                            });
                        }));
                    });
                    configure.accept(pane);
                    onReady.accept(new BuiltDiff(pane, model));
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
     *  background if not already open), marking it dirty, then re-diffs every tab. Returns whether the
     *  buffer accepted the edit. Used by diff apply actions and Local File History restoration. */
    boolean applyToLocal(Path target, String newText) {
        EditorBuffer b = bufferForApply(target);
        if (b == null) {
            host.setStatus(tr("status.diff.applyFailed", target.getFileName()));
            return false;
        }
        b.replaceWholeDocument(newText); // widens first: newText is whole-document text
        host.setStatus(tr("status.diff.applied"));
        refreshOpenDiffs();
        return true;
    }

    /** Undoes the last applied change on {@code target}'s buffer (the buffer's own undo). */
    void undoLocal(Path target) {
        EditorBuffer b = ops.openBufferFor(target);
        if (b != null && b.getArea().isUndoAvailable()) {
            b.getArea().undo();
            refreshOpenDiffs();
        }
    }

    /** Saves {@code target}'s buffer (persisting the applied changes) and re-diffs. */
    void saveLocal(Path target) {
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
                blobSide(root, "HEAD:" + rel, path),
                cb -> cb.accept(worktreeText(path)),
                DiffViewerPane.EditableSide.RIGHT,
                path);
    }

    /**
     * Opens a {@code .patch}/{@code .diff} buffer's first file-section as a read-only structured diff tab
     * (side-by-side, word-level highlighting, prev/next-change nav) — parsed from the buffer's live
     * (possibly unsaved) text via {@link PatchParser}, not re-read from disk. The reconstructed old/new
     * line sequences feed straight into the normal {@link DiffEngine} pipeline, so the tab behaves like any
     * other diff view (just not editable/refreshable — there's no live "other side" to track). No-op with
     * a status when the text doesn't parse as a unified diff; notes when a multi-file patch shows only the
     * first file (v1 scope — one file section per tab).
     */
    void openPatchFile(EditorBuffer buffer) {
        if (buffer == null) {
            host.setStatus(tr("status.diff.noFile"));
            return;
        }
        List<PatchParser.FilePatch> files = PatchParser.parse(buffer.getContent());
        if (files.isEmpty()) {
            host.setStatus(tr("status.diff.patchUnparsable"));
            return;
        }
        List<com.editora.diff.DiffModels.DiffModel> models = new ArrayList<>(Collections.nCopies(files.size(), null));
        AtomicInteger remaining = new AtomicInteger(files.size());
        for (int i = 0; i < files.size(); i++) {
            int index = i;
            PatchParser.FilePatch fp = files.get(i);
            diffService.compute(
                    patchText(fp.oldLines(), fp.oldFinalNewline()),
                    patchText(fp.newLines(), fp.newFinalNewline()),
                    lastDiffOptions,
                    model -> {
                        models.set(index, model);
                        if (remaining.decrementAndGet() == 0) {
                            openPatchReview(buffer, files, models);
                        }
                    });
        }
    }

    private void openPatchReview(
            EditorBuffer buffer,
            List<PatchParser.FilePatch> files,
            List<com.editora.diff.DiffModels.DiffModel> models) {
        List<PatchReviewPane.Entry> entries = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            PatchParser.FilePatch fp = files.get(i);
            String oldLabel = cleanPatchLabel(fp.oldPath());
            String newLabel = cleanPatchLabel(fp.newPath());
            String fallback = buffer.getTitle();
            String leftName = !oldLabel.isEmpty() ? oldLabel : (!newLabel.isEmpty() ? newLabel : fallback);
            String rightName = !newLabel.isEmpty() ? newLabel : leftName;
            String leftText = patchText(fp.oldLines(), fp.oldFinalNewline());
            String rightText = patchText(fp.newLines(), fp.newFinalNewline());
            DiffViewerPane pane = new DiffViewerPane(
                    tr("diff.title.patch", rightName),
                    null,
                    null,
                    leftName,
                    rightName,
                    leftText,
                    rightText,
                    models.get(i),
                    host.settings().getFontFamily(),
                    host.settings().getFontSize(),
                    host.settings().isShowLineNumbers(),
                    rightName);
            pane.setOnExportPatch(this::exportPatch);
            pane.setOptions(lastDiffOptions);
            String[] current = {leftText, rightText};
            pane.setOnSwapRequested((newLeft, newRight) ->
                    diffService.compute(newLeft, newRight, lastDiffOptions, model -> {
                        if (model == null) {
                            pane.cancelSwap();
                            host.setStatus(tr("status.diff.tooLarge"));
                            return;
                        }
                        current[0] = newLeft;
                        current[1] = newRight;
                        pane.swapSides(model);
                    }));
            pane.setOnOptionsChanged(opts -> {
                lastDiffOptions = opts;
                diffService.compute(
                        current[0], current[1], opts, model -> pane.updateContent(current[0], current[1], model));
            });
            entries.add(new PatchReviewPane.Entry(rightName, fp.additions(), fp.deletions(), pane));
        }
        if (entries.size() == 1) {
            ops.addDiffTab(entries.get(0).pane());
        } else {
            ops.addDiffTab(new PatchReviewPane(tr("diff.title.patchSet", entries.size()), entries));
        }
        host.setStatus(tr("status.diff.patchFilesOpened", entries.size()));
    }

    private static String patchText(List<String> lines, boolean finalNewline) {
        return new com.editora.diff.DiffText(lines, "\n", finalNewline).compose(lines);
    }

    /** {@code ""} for a missing/{@code /dev/null} patch-file label, else the label unchanged. */
    private static String cleanPatchLabel(String path) {
        return path == null || path.isBlank() || "/dev/null".equals(path) ? "" : path;
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

    /** Compares clipboard text with the active local file, keeping the file as the editable target. */
    void compareActiveWithClipboard() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null || buffer.getPath() == null) {
            host.setStatus(tr("status.diff.noFile"));
            return;
        }
        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (!clipboard.hasString()) {
            host.setStatus(tr("status.diff.clipboardEmpty"));
            return;
        }
        Path path = buffer.getPath();
        String name = path.getFileName().toString();
        String clipboardText = clipboard.getString();
        openDiff(
                tr("diff.title.clipboard", name),
                tr("diff.side.clipboard"),
                tr("diff.side.working"),
                name,
                name,
                cb -> cb.accept(clipboardText),
                cb -> cb.accept(worktreeText(path)),
                DiffViewerPane.EditableSide.RIGHT,
                path);
    }

    /** Compares an empty document with the active local file, keeping the file as the editable target. */
    void compareActiveWithBlank() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null || buffer.getPath() == null) {
            host.setStatus(tr("status.diff.noFile"));
            return;
        }
        Path path = buffer.getPath();
        String name = path.getFileName().toString();
        openDiff(
                tr("diff.title.blank", name),
                tr("diff.side.empty"),
                tr("diff.side.working"),
                name,
                name,
                cb -> cb.accept(""),
                cb -> cb.accept(worktreeText(path)),
                DiffViewerPane.EditableSide.RIGHT,
                path);
    }

    /** Opens a two-directory picker and compares the selected trees. */
    void compareDirectories() {
        DirectoryChooser leftPicker = new DirectoryChooser();
        leftPicker.setTitle(tr("diff.directory.pickLeft"));
        Path start = ops.finderStartDir();
        if (start != null && Files.isDirectory(start)) {
            leftPicker.setInitialDirectory(start.toFile());
        }
        java.io.File left = leftPicker.showDialog(host.window());
        if (left == null) {
            return;
        }
        DirectoryChooser rightPicker = new DirectoryChooser();
        rightPicker.setTitle(tr("diff.directory.pickRight"));
        Path leftPath = left.toPath().toAbsolutePath().normalize();
        Path rightStart = leftPath.getParent() == null ? leftPath : leftPath.getParent();
        rightPicker.setInitialDirectory(rightStart.toFile());
        java.io.File right = rightPicker.showDialog(host.window());
        if (right != null) {
            compareDirectories(left.toPath(), right.toPath());
        }
    }

    /** Opens the two arbitrary paths supplied by the standalone {@code --diff-ui} launch. */
    void comparePaths(Path left, Path right) {
        if (left != null && right != null && Files.isDirectory(left) && Files.isDirectory(right)) {
            compareDirectories(left, right);
            return;
        }
        if ((left != null && Files.isDirectory(left)) || (right != null && Files.isDirectory(right))) {
            host.setStatus(tr("status.diff.pathTypeMismatch"));
            return;
        }
        compareFiles(left, right);
    }

    /** Opens the two arbitrary files supplied by the standalone {@code --diff-ui} launch. */
    void compareFiles(Path left, Path right) {
        Path leftPath = left == null ? null : left.toAbsolutePath().normalize();
        Path rightPath = right == null ? null : right.toAbsolutePath().normalize();
        if (!readableFile(leftPath) || !readableFile(rightPath)) {
            Path bad = !readableFile(leftPath) ? leftPath : rightPath;
            host.setStatus(tr("status.diff.unreadable", bad == null ? "" : bad));
            return;
        }
        String leftName = leftPath.getFileName() == null
                ? leftPath.toString()
                : leftPath.getFileName().toString();
        String rightName = rightPath.getFileName() == null
                ? rightPath.toString()
                : rightPath.getFileName().toString();
        openDiff(
                tr("diff.title.compare", leftName, rightName),
                leftPath.toString(),
                rightPath.toString(),
                leftName,
                rightName,
                fileSide(leftPath),
                fileSide(rightPath),
                DiffViewerPane.EditableSide.NONE,
                null);
    }

    /** Recursively scans two directory roots away from the FX thread, then opens a lazy file review. */
    void compareDirectories(Path left, Path right) {
        Path leftRoot = left == null ? null : left.toAbsolutePath().normalize();
        Path rightRoot = right == null ? null : right.toAbsolutePath().normalize();
        if (!readableDirectory(leftRoot) || !readableDirectory(rightRoot)) {
            Path bad = !readableDirectory(leftRoot) ? leftRoot : rightRoot;
            host.setStatus(tr("status.diff.unreadableDirectory", bad == null ? "" : bad));
            return;
        }
        host.setStatus(tr("status.diff.scanningDirectories"));
        fileReadExecutor.submit(() -> {
            try {
                DirectoryDiff.Result result = DirectoryDiff.compare(leftRoot, rightRoot);
                javafx.application.Platform.runLater(() -> openDirectoryReview(leftRoot, rightRoot, result));
            } catch (IOException e) {
                javafx.application.Platform.runLater(
                        () -> host.setStatus(tr("status.diff.directoryFailed", e.getMessage())));
            }
        });
    }

    private void openDirectoryReview(Path leftRoot, Path rightRoot, DirectoryDiff.Result result) {
        List<DirectoryReviewPane.Entry> entries = result.entries().stream()
                .map(entry -> new DirectoryReviewPane.Entry(
                        entry.relativePath(), entry.kind(), entry.leftSize(), entry.rightSize()))
                .toList();
        String summary = tr("diff.directory.summary", entries.size(), result.identicalFiles())
                + (result.truncated() ? " · " + tr("diff.directory.truncated") : "")
                + (result.incomplete() ? " · " + tr("diff.directory.incomplete") : "");
        String leftName = pathName(leftRoot);
        String rightName = pathName(rightRoot);
        DirectoryReviewPane review = new DirectoryReviewPane(
                tr("diff.title.directories", leftName, rightName), entries, summary, (entry, ready) -> {
                    Path leftFile = leftRoot.resolve(entry.label());
                    Path rightFile = rightRoot.resolve(entry.label());
                    DiffSide leftSide = entry.kind() == DirectoryDiff.Kind.RIGHT_ONLY
                            ? callback -> callback.accept("")
                            : fileSide(leftFile);
                    DiffSide rightSide = entry.kind() == DirectoryDiff.Kind.LEFT_ONLY
                            ? callback -> callback.accept("")
                            : fileSide(rightFile);
                    Path openTarget = entry.kind() == DirectoryDiff.Kind.LEFT_ONLY ? leftFile : rightFile;
                    buildDiffPane(
                            tr("diff.title.compare", entry.label(), entry.label()),
                            leftRoot.resolve(entry.label()).toString(),
                            rightRoot.resolve(entry.label()).toString(),
                            entry.label(),
                            entry.label(),
                            leftSide,
                            rightSide,
                            DiffViewerPane.EditableSide.NONE,
                            openTarget,
                            pane -> pane.setExitDiffUiAction(null),
                            built -> ready.accept(
                                    built == null
                                            ? null
                                            : new DirectoryReviewPane.Loaded(
                                                    built.pane(),
                                                    built.model().added(),
                                                    built.model().removed())));
                });
        ops.addDiffTab(review);
        host.setStatus(
                entries.isEmpty()
                        ? tr(
                                result.incomplete()
                                        ? "status.diff.directoryNoDifferencesIncomplete"
                                        : result.truncated()
                                                ? "status.diff.directoryNoDifferencesTruncated"
                                                : "status.diff.directoriesIdentical",
                                result.identicalFiles())
                        : tr("status.diff.directoryOpened", entries.size()));
    }

    private static String pathName(Path path) {
        return path.getFileName() == null ? path.toString() : path.getFileName().toString();
    }

    private static boolean readableFile(Path path) {
        return path != null && Files.isRegularFile(path) && Files.isReadable(path);
    }

    private static boolean readableDirectory(Path path) {
        return path != null && Files.isDirectory(path) && Files.isReadable(path);
    }

    /** Reads a standalone diff side away from the FX thread; callbacks return to the FX thread. */
    private DiffSide fileSide(Path path) {
        return callback -> fileReadExecutor.submit(() -> {
            String text = worktreeText(path);
            javafx.application.Platform.runLater(() -> callback.accept(text));
        });
    }

    /** Diff the active file against a commit chosen from its history. */
    void diffActiveVsCommit() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || b.getPath() == null) {
            host.setStatus(tr("status.diff.noFile"));
            return;
        }
        diffPathVsCommit(b.getPath());
    }

    /** Diff a project-tree file against a commit chosen from its history. */
    void diffPathVsCommit(Path path) {
        if (path == null || Files.isDirectory(path)) {
            host.setStatus(tr("status.diff.noFile"));
            return;
        }
        if (git.reportIfNoRepo()) {
            return;
        }
        Path root = git.repoRoot(); // capture at open time; see diffPathVsHead
        String rel = GitService.repoRelative(root, path);
        if (rel == null) {
            host.setStatus(tr("status.diff.notInRepo"));
            return;
        }
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
                            blobSide(root, chosen.hash() + ":" + rel, path),
                            cb -> cb.accept(worktreeText(path)),
                            DiffViewerPane.EditableSide.RIGHT,
                            path));
            picker.setOverlayHost(host.overlayHost());
            picker.show(host.window());
        });
    }

    /** Diff a project-tree file against its version on a branch chosen from the repo's branches. */
    void diffPathVsBranch(Path path) {
        if (path == null || Files.isDirectory(path)) {
            host.setStatus(tr("status.diff.noFile"));
            return;
        }
        if (git.reportIfNoRepo()) {
            return;
        }
        Path root = git.repoRoot(); // capture at open time; see diffPathVsHead
        String rel = GitService.repoRelative(root, path);
        if (rel == null) {
            host.setStatus(tr("status.diff.notInRepo"));
            return;
        }
        String name = path.getFileName().toString();
        git.service().branches(root, branches -> {
            List<String> names = new ArrayList<>();
            for (GitService.BranchInfo bi : branches.local()) {
                names.add(bi.name());
            }
            names.addAll(branches.remote());
            if (names.isEmpty()) {
                host.setStatus(tr("status.diff.noBranches"));
                return;
            }
            Set<String> remote = Set.copyOf(branches.remote());
            QuickOpen<String> picker = new QuickOpen<>(
                    tr("diff.branchPickerTitle"),
                    tr("diff.branchPickerPrompt"),
                    () -> names,
                    b -> b,
                    b -> tr(remote.contains(b) ? "diff.branch.remote" : "diff.branch.local"),
                    chosen -> openDiff(
                            tr("diff.title.vsBranch", name, chosen),
                            chosen,
                            tr("diff.side.working"),
                            name,
                            name,
                            blobSide(root, chosen + ":" + rel, path),
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
                    blobSide(root, "HEAD:" + repoRel, abs),
                    blobSide(root, ":" + repoRel, abs),
                    DiffViewerPane.EditableSide.NONE,
                    null,
                    pane -> configureGitHunks(pane, root, repoRel, abs, true));
        } else {
            openDiff(
                    tr("diff.title.unstaged", name),
                    tr("diff.side.staged"),
                    tr("diff.side.working"),
                    name,
                    name,
                    blobSide(root, ":" + repoRel, abs),
                    cb -> cb.accept(worktreeText(abs)),
                    DiffViewerPane.EditableSide.RIGHT,
                    abs,
                    pane -> configureGitHunks(pane, root, repoRel, abs, false));
        }
    }

    /** Opens every staged or working-tree change in one navigable repository review tab. */
    void reviewGitChanges(boolean staged) {
        Path root = git.repoRoot();
        if (root == null) {
            host.setStatus(tr("status.notARepo"));
            return;
        }
        List<GitReviewTarget> targets = gitReviewTargets(git.status(), staged);
        if (targets.isEmpty()) {
            host.setStatus(tr(staged ? "status.diff.noStagedChanges" : "status.diff.noWorkingChanges"));
            return;
        }

        host.setStatus(tr("status.diff.preparingReview", targets.size()));
        List<BuiltDiff> built = new ArrayList<>(Collections.nCopies(targets.size(), null));
        AtomicInteger remaining = new AtomicInteger(targets.size());
        for (int i = 0; i < targets.size(); i++) {
            int index = i;
            GitReviewTarget target = targets.get(i);
            Path file = root.resolve(target.path());
            String leftPath = target.leftPath();
            DiffSide left = staged
                    ? blobSide(root, "HEAD:" + leftPath, file)
                    : leftPath == null ? callback -> callback.accept("") : blobSide(root, ":" + leftPath, file);
            DiffSide right = staged
                    ? blobSide(root, ":" + target.path(), file)
                    : callback -> callback.accept(worktreeText(file));
            String fileTitle = file.getFileName() == null
                    ? target.path()
                    : file.getFileName().toString();
            buildDiffPane(
                    tr(staged ? "diff.title.staged" : "diff.title.unstaged", fileTitle),
                    tr(staged ? "diff.side.head" : "diff.side.staged"),
                    tr(staged ? "diff.side.staged" : "diff.side.working"),
                    leftPath == null ? target.path() : leftPath,
                    target.path(),
                    left,
                    right,
                    staged ? DiffViewerPane.EditableSide.NONE : DiffViewerPane.EditableSide.RIGHT,
                    staged ? null : file,
                    pane -> configureGitHunks(pane, root, target.path(), file, staged),
                    result -> {
                        built.set(index, result);
                        if (remaining.decrementAndGet() == 0) {
                            openGitReview(staged, targets, built);
                        }
                    });
        }
    }

    private void openGitReview(boolean staged, List<GitReviewTarget> targets, List<BuiltDiff> built) {
        List<PatchReviewPane.Entry> entries = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            BuiltDiff result = built.get(i);
            if (result == null) {
                continue;
            }
            GitReviewTarget target = targets.get(i);
            entries.add(new PatchReviewPane.Entry(
                    target.path(),
                    String.valueOf(target.status()),
                    result.model().added(),
                    result.model().removed(),
                    result.pane()));
        }
        if (entries.isEmpty()) {
            return;
        }
        String title = tr(staged ? "diff.title.gitStagedReview" : "diff.title.gitWorkingReview", entries.size());
        ops.addDiffTab(new PatchReviewPane(title, entries));
        host.setStatus(tr("status.diff.reviewOpened", entries.size()));
    }

    /** Selects one side of the porcelain status and resolves rename/copy source paths for blob lookup. */
    static List<GitReviewTarget> gitReviewTargets(GitStatus status, boolean staged) {
        if (status == null || !status.isRepo()) {
            return List.of();
        }
        List<GitReviewTarget> targets = new ArrayList<>();
        for (FileEntry file : status.files()) {
            if (staged && file.staged()) {
                targets.add(new GitReviewTarget(
                        file.path(), sourcePath(file.path(), file.origPath(), file.index()), file.index()));
            } else if (!staged && (file.unstaged() || file.untracked())) {
                String source = file.untracked() ? null : sourcePath(file.path(), file.origPath(), file.worktree());
                targets.add(new GitReviewTarget(file.path(), source, file.untracked() ? '?' : file.worktree()));
            }
        }
        return List.copyOf(targets);
    }

    private static String sourcePath(String path, String originalPath, char status) {
        return (status == 'R' || status == 'C') && originalPath != null && !originalPath.isBlank()
                ? originalPath
                : path;
    }

    private void configureGitHunks(DiffViewerPane pane, Path root, String repoRel, Path file, boolean staged) {
        Set<DiffViewerPane.GitHunkAction> actions = staged
                ? Set.of(DiffViewerPane.GitHunkAction.UNSTAGE, DiffViewerPane.GitHunkAction.OPEN)
                : Set.of(
                        DiffViewerPane.GitHunkAction.STAGE,
                        DiffViewerPane.GitHunkAction.REVERT,
                        DiffViewerPane.GitHunkAction.OPEN);
        pane.setGitHunkActions(actions, request -> {
            switch (request.action()) {
                case OPEN -> ops.openAt(file, request.targetLine());
                case REVERT -> {
                    if (!pane.matchesEditableText(worktreeText(file))) {
                        host.setStatus(tr("status.diff.localStale"));
                        pane.refresh();
                    } else {
                        applyToLocal(file, request.afterText());
                    }
                }
                case STAGE, UNSTAGE -> {
                    String patch = PatchWriter.unifiedDiff(
                            "a/" + repoRel, "b/" + repoRel, request.beforeText(), request.afterText());
                    git.service().applyPatch(root, patch, true, result -> {
                        if (result.ok()) {
                            host.setStatus(tr(
                                    request.action() == DiffViewerPane.GitHunkAction.STAGE
                                            ? "status.diff.hunkStaged"
                                            : "status.diff.hunkUnstaged"));
                            git.afterMutation();
                        } else {
                            host.setStatus(tr("status.diff.hunkStale", result.message()));
                        }
                    });
                }
            }
        });
    }

    /** Diff a commit's version of a file against its parent (commit~1 ↔ commit), read-only. */
    void diffCommitFile(String hash, String repoRel) {
        diffCommitFile(hash, repoRel, null);
    }

    /**
     * Diff a commit's version of a file against its parent. For a rename ({@code origRepoRel} non-null), the
     * parent side is fetched at the file's <em>original</em> path — otherwise {@code <hash>~1:<newPath>} misses
     * (the file didn't exist under the new name at the parent) and the whole file shows as added instead of the
     * rename.
     */
    void diffCommitFile(String hash, String repoRel, String origRepoRel) {
        Path root = git.repoRoot(); // capture at open time; see diffPathVsHead
        if (root == null) {
            return;
        }
        String name = repoRel.substring(repoRel.lastIndexOf('/') + 1);
        String parentRel = origRepoRel != null && !origRepoRel.isBlank() ? origRepoRel : repoRel;
        openDiff(
                tr("diff.title.commitFile", name, GitFormat.shortHash(hash)),
                tr("diff.side.parent"),
                tr("diff.title.vsCommitShort", GitFormat.shortHash(hash)),
                name,
                name,
                blobSide(root, hash + "~1:" + parentRel, root.resolve(repoRel)),
                blobSide(root, hash + ":" + repoRel, root.resolve(repoRel)),
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
            if (!Files.exists(abs)) {
                return "";
            }
            // Decode the closed working file the same way the editor would (BOM / .editorconfig charset),
            // not force-UTF-8 — else a non-UTF-8 file's working side would disagree with the (now
            // charset-correct) blob side.
            byte[] bytes = Files.readAllBytes(abs);
            if (BinaryDiff.isProbablyBinary(bytes)) {
                return BinaryDiff.describe(bytes);
            }
            return EditorConfigCharset.decode(
                    bytes, EditorConfigCharset.resolveName(bytes, ops.editorConfigCharset(abs)));
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * A diff side that fetches a git blob ({@code spec}, e.g. {@code HEAD:path}) as raw bytes and decodes it
     * with the same charset resolution the editor uses for {@code file} (BOM wins, else the file's
     * {@code .editorconfig} charset, else UTF-8) — so a Latin-1/UTF-16 tracked file shows real text and no
     * spurious whole-file change, instead of UTF-8 mojibake.
     */
    private DiffSide blobSide(Path root, String spec, Path file) {
        String ecCharset = ops.editorConfigCharset(file);
        return onText -> git.service()
                .showBytes(
                        root,
                        spec,
                        bytes -> onText.accept(
                                BinaryDiff.isProbablyBinary(bytes)
                                        ? BinaryDiff.describe(bytes)
                                        : EditorConfigCharset.decode(
                                                bytes, EditorConfigCharset.resolveName(bytes, ecCharset))));
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
        boolean hasMarkers = ConflictParser.hasConflictMarkers(text);
        DiffText format = DiffText.parse(text);
        Path path = b.getPath();
        Path root = git.repoRoot();
        String rel = GitService.repoRelative(root, path);
        if (root == null || rel == null) {
            openMarkerMergeOrReport(b, text, format, hasMarkers);
            return;
        }

        String charset = ops.editorConfigCharset(path);
        git.service()
                .showBlob(
                        root,
                        ":1:" + rel,
                        base -> git.service()
                                .showBlob(
                                        root,
                                        ":2:" + rel,
                                        ours -> git.service().showBlob(root, ":3:" + rel, theirs -> {
                                            if (!b.text().equals(text)) {
                                                host.setStatus(tr("status.merge.stale"));
                                                return;
                                            }
                                            if (!base.found()
                                                    || !ours.found()
                                                    || !theirs.found()
                                                    || BinaryDiff.isProbablyBinary(base.bytes())
                                                    || BinaryDiff.isProbablyBinary(ours.bytes())
                                                    || BinaryDiff.isProbablyBinary(theirs.bytes())) {
                                                openMarkerMergeOrReport(b, text, format, hasMarkers);
                                                return;
                                            }
                                            String baseText = decodeMergeBlob(base.bytes(), charset);
                                            String oursText = decodeMergeBlob(ours.bytes(), charset);
                                            String theirsText = decodeMergeBlob(theirs.bytes(), charset);
                                            fileReadExecutor.submit(() -> {
                                                ThreeWayMerge.Result result =
                                                        ThreeWayMerge.merge(baseText, oursText, theirsText);
                                                javafx.application.Platform.runLater(() -> {
                                                    if (!b.text().equals(text)) {
                                                        host.setStatus(tr("status.merge.stale"));
                                                        return;
                                                    }
                                                    openMergePane(b, text, format, result.file());
                                                });
                                            });
                                        })));
    }

    private static String decodeMergeBlob(byte[] bytes, String editorConfigCharset) {
        return EditorConfigCharset.decode(bytes, EditorConfigCharset.resolveName(bytes, editorConfigCharset));
    }

    private void openMarkerMerge(EditorBuffer buffer, String sourceText, DiffText format) {
        openMergePane(buffer, sourceText, format, ConflictParser.parse(format.lines()));
    }

    private void openMarkerMergeOrReport(EditorBuffer buffer, String sourceText, DiffText format, boolean hasMarkers) {
        if (hasMarkers) {
            openMarkerMerge(buffer, sourceText, format);
        } else {
            host.setStatus(tr("status.merge.noConflicts"));
        }
    }

    private void openMergePane(
            EditorBuffer buffer, String sourceText, DiffText format, ConflictParser.ConflictFile conflictFile) {
        String name = buffer.getPath() == null
                ? buffer.getTitle()
                : buffer.getPath().getFileName().toString();
        MergeViewerPane pane = new MergeViewerPane(
                tr("merge.title", name),
                conflictFile,
                host.settings().getFontFamily(),
                host.settings().getFontSize(),
                format.lineSeparator(),
                format.finalNewline(),
                resolvedText -> {
                    if (!buffer.text().equals(sourceText)) {
                        host.setStatus(tr("status.merge.stale"));
                        return;
                    }
                    buffer.replaceWholeDocument(resolvedText);
                    host.setStatus(tr("status.merge.applied"));
                });
        ops.addDiffTab(pane);
    }

    /** Stops the diff worker thread (window close). */
    public void shutdown() {
        fileReadExecutor.shutdownNow();
        diffService.shutdown();
    }
}
