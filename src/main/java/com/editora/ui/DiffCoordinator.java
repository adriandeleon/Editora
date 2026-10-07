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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javafx.scene.input.Clipboard;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import com.editora.diff.BinaryDiff;
import com.editora.diff.BlobRewrite;
import com.editora.diff.ConflictParser;
import com.editora.diff.DiffEngine;
import com.editora.diff.DiffModels.DiffModel;
import com.editora.diff.DiffService;
import com.editora.diff.DiffText;
import com.editora.diff.DirectoryDiff;
import com.editora.diff.PatchLineNumbers;
import com.editora.diff.PatchParser;
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
 * + {@code applyToLocalIfUnchangedAsync}/{@code undoLocal}/{@code saveLocal} are package-visible so the
 * Local File History tool window ({@code FileHistoryPanel}, via {@code HistoryCoordinator}) reuses them for
 * its inline revision diff + per-hunk apply chevrons; its whole-file restore uses {@code applyToLocal}. {@code MainController} keeps the {@code diff.*}/{@code merge.resolve} command registrations
 * and the tab-menu / Git-panel / project-tree entry points (delegating here), and calls
 * {@link #refreshOpenDiffs()} on window focus-regain + after a git mutation.
 */
final class DiffCoordinator {

    record GitReviewTarget(String path, String leftPath, char status) {}

    private record BuiltDiff(DiffViewerPane pane, DiffModel model) {}

    /** Exact source text or a render-only surrogate such as a binary description. */
    record DiffContent(String text, boolean applicable, DiffViewerPane.SideFormat format) {
        static DiffContent text(String text) {
            return new DiffContent(text == null ? "" : text, true, DiffViewerPane.SideFormat.DEFAULT);
        }

        /** Exact text plus how its source spells it (line-ending label, charset) for patch export. */
        static DiffContent text(String text, String lineEnding, String charset) {
            return new DiffContent(text == null ? "" : text, true, new DiffViewerPane.SideFormat(lineEnding, charset));
        }

        /** Decoded source bytes: the text in the editor's form, the format read off the raw decode. */
        static DiffContent decoded(EditorConfigCharset.Decoded raw) {
            return text(
                    com.editora.editor.LineEndings.toLf(raw.text()),
                    com.editora.editor.LineEndings.dominant(raw.text()),
                    raw.charset());
        }

        static DiffContent presentation(String text) {
            return new DiffContent(text == null ? "" : text, false, DiffViewerPane.SideFormat.DEFAULT);
        }
    }

    /** A re-fetchable side of a diff. Re-invoked on refresh so the view tracks disk/Git changes. */
    @FunctionalInterface
    interface DiffSide {
        void fetch(Consumer<DiffContent> onText);
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

        /** Loads a closed apply target away from the FX thread, then returns its attached buffer on FX. */
        default void openBackgroundBufferAsync(Path target, Consumer<EditorBuffer> done) {
            done.accept(openBackgroundBuffer(target));
        }

        /** Removes a background buffer opened solely for an apply that was rejected as stale. */
        default void discardBackgroundBuffer(EditorBuffer buffer) {}

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

    /** Largest working-tree file read as a diff side; matches the cap on a captured Git blob. */
    static final long MAX_SIDE_BYTES = 10L * 1024 * 1024;

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

    /** The editor-side change commands (next/previous, peek, revert, stage); they stage through this class. */
    private final GitHunkCoordinator hunks;

    DiffCoordinator(CoordinatorHost host, GitCoordinator git, Ops ops) {
        this.host = host;
        this.git = git;
        this.ops = ops;
        this.hunks = new GitHunkCoordinator(host, git, this);
    }

    GitHunkCoordinator hunks() {
        return hunks;
    }

    /** Saves {@code buffer} through the window's normal save path; false when it could not be saved. */
    boolean saveBuffer(EditorBuffer buffer) {
        return ops.saveBuffer(buffer);
    }

    /**
     * Stages part of {@code file}'s unstaged changes on behalf of the editor's Stage Hunk: the index entry
     * becomes {@code edit(index text, working text)}, written through the same blob rewrite and index
     * compare-and-swap as the diff viewer's Stage Hunk (so a CRLF or Latin-1 blob keeps its bytes). The
     * working text is read from disk — what git diffed — not from the buffer. {@code edit} answering
     * {@code null} means the hunk no longer fits and nothing is staged.
     */
    void stageFromEditor(
            Path root, String repoRel, Path file, java.util.function.BinaryOperator<String> edit, Runnable staged) {
        String ecCharset = ops.editorConfigCharset(file);
        String openCharset = openCharset(file);
        git.service().showBlob(root, ":" + repoRel, index -> {
            if (index.truncated() || !index.found()) {
                host.setStatus(tr("status.diff.hunkStale", repoRel));
                return;
            }
            submitFileRead(() -> {
                byte[] working;
                try {
                    working = Files.size(file) > MAX_SIDE_BYTES ? null : Files.readAllBytes(file);
                } catch (IOException unreadable) {
                    working = null;
                }
                byte[] bytes = working;
                javafx.application.Platform.runLater(() -> {
                    String before = DiffSideText.decode(index.bytes(), ecCharset, openCharset)
                            .text();
                    String after = bytes == null
                            ? null
                            : edit.apply(
                                    before,
                                    DiffSideText.decode(bytes, ecCharset, openCharset)
                                            .text());
                    if (after == null) {
                        host.setStatus(tr("status.diff.hunkStale", repoRel));
                        return;
                    }
                    stageRewritten(
                            root, repoRel, index, index.bytes(), before, ecCharset, openCharset, after, result -> {
                                if (result.ok()) {
                                    host.setStatus(tr("status.diff.hunkStaged"));
                                    git.afterMutation();
                                    staged.run();
                                } else {
                                    host.setStatus(tr("status.diff.hunkStale", result.message()));
                                }
                            });
                });
            });
        });
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
        leftSide.fetch(leftContent -> rightSide.fetch(rightContent -> {
            String leftText = leftContent.text();
            String rightText = rightContent.text();
            diffService.compute(leftText, rightText, lastDiffOptions, model -> {
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
                pane.setMutationAllowed(leftContent.applicable() && rightContent.applicable());
                pane.setSideFormats(leftContent.format(), rightContent.format());
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
                    String right = pane.editableSide() == DiffViewerPane.EditableSide.RIGHT && pane.hasResultEditor()
                            ? pane.resultText()
                            : current[1];
                    diffService.compute(left, right, opts, next -> {
                        if (next == null) {
                            host.setStatus(tr("status.diff.tooLarge"));
                        } else if (requested == generation.get()) {
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
                    pane.setEditableAsync(
                            editableSide,
                            (newText, done) -> {
                                // The new text was derived from the rows the pane is showing, so the file
                                // must still equal the text those rows came from. current[] is not that:
                                // it advances as soon as an apply lands, before the re-diff reaches the
                                // pane, and a second hunk applied in that window passed the check while
                                // being built from the old rows — silently reverting the first.
                                String expected = pane.editableBaselineText();
                                applyToLocalIfUnchangedAsync(target, expected, newText, applied -> {
                                    if (!applied) {
                                        host.setStatus(tr("status.diff.localStale"));
                                        pane.refresh();
                                    } else {
                                        current[pane.editableSide() == DiffViewerPane.EditableSide.RIGHT ? 1 : 0] =
                                                newText;
                                    }
                                    done.accept(applied);
                                });
                            },
                            () -> undoLocal(target),
                            () -> saveLocal(target));
                    pane.setUndoAvailable(() -> {
                        EditorBuffer open = ops.openBufferFor(target);
                        return open != null && open.getArea().isUndoAvailable();
                    });
                    pane.setOnResultEdited(draft -> {
                        long requested = generation.incrementAndGet();
                        String left = pane.editableSide() == DiffViewerPane.EditableSide.LEFT ? draft : current[0];
                        String right = pane.editableSide() == DiffViewerPane.EditableSide.RIGHT ? draft : current[1];
                        diffService.compute(left, right, currentOptions[0], next -> {
                            if (next != null
                                    && requested == generation.get()
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
                    leftSide.fetch(lContent -> rightSide.fetch(rContent -> {
                        if (requested != generation.get()) {
                            return;
                        }
                        String l = lContent.text();
                        String r = rContent.text();
                        String displayLeft = swapped[0] ? r : l;
                        String displayRight = swapped[0] ? l : r;
                        pane.setMutationAllowed(lContent.applicable() && rContent.applicable());
                        pane.setSideFormats(
                                swapped[0] ? rContent.format() : lContent.format(),
                                swapped[0] ? lContent.format() : rContent.format());
                        String editable =
                                pane.editableSide() == DiffViewerPane.EditableSide.RIGHT ? displayRight : displayLeft;
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
                            if (m != null && requested == generation.get()) {
                                current[0] = displayLeft;
                                current[1] = displayRight;
                                pane.updateContent(displayLeft, displayRight, m);
                            }
                        });
                    }));
                });
                configure.accept(pane);
                onReady.accept(new BuiltDiff(pane, model));
            });
        }));
    }

    /** The {@code .editorconfig} charset the editor would read {@code file} with, or {@code null} (see {@link Ops}). */
    String editorConfigCharset(Path file) {
        return ops.editorConfigCharset(file);
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

    /**
     * Writes new text into the local file {@code target} via an undoable editor buffer (opened in the
     * background if not already open), marking it dirty, then re-diffs every tab. Returns whether the buffer
     * accepted the edit.
     *
     * <p><b>Unguarded:</b> it replaces whatever the buffer holds now. Only a caller that has already proved
     * the buffer is the version it means to replace may use it — Local File History's whole-file restore
     * checks the buffer's document version first. Anything applying a <em>hunk</em> (text computed from a
     * displayed diff) must use {@link #applyToLocalIfUnchangedAsync} with the text that diff was showing.
     */
    boolean applyToLocal(Path target, String newText) {
        EditorBuffer b = bufferForApply(target);
        if (b == null || !b.isEditable() || b.isDisposed() || b.isTruncatedLoad()) {
            host.setStatus(tr("status.diff.applyFailed", target.getFileName()));
            return false;
        }
        if (!NoUndoGuard.allow(b, tr("noUndo.op.diff"))) {
            return false;
        }
        b.replaceWholeDocument(newText); // widens first: newText is whole-document text
        host.setStatus(tr("status.diff.applied"));
        refreshOpenDiffs();
        return true;
    }

    /** Opens/snapshots the target on FX and mutates only the exact source version shown by the diff. */
    private boolean applyToLocalIfUnchanged(Path target, String expectedText, String newText) {
        EditorBuffer existing = ops.openBufferFor(target);
        EditorBuffer buffer = existing != null ? existing : ops.openBackgroundBuffer(target);
        if (buffer == null
                || !buffer.isEditable()
                || buffer.isDisposed()
                || buffer.isTruncatedLoad()
                || !java.util.Objects.equals(expectedText, buffer.getContent())
                || !NoUndoGuard.allow(buffer, tr("noUndo.op.diff"))) {
            if (existing == null && buffer != null) {
                ops.discardBackgroundBuffer(buffer);
            }
            return false;
        }
        buffer.replaceWholeDocument(newText);
        host.setStatus(tr("status.diff.applied"));
        refreshOpenDiffs();
        return true;
    }

    /**
     * Applies {@code newText} only while the target still holds {@code expectedText} — the exact text the
     * hunk was computed from — so edits typed since the diff was drawn are never overwritten. Async so
     * opening and decoding a closed target never blocks JavaFX. Shared by every diff surface, including the
     * Local File History panel's per-hunk restore.
     */
    void applyToLocalIfUnchangedAsync(Path target, String expectedText, String newText, Consumer<Boolean> done) {
        EditorBuffer existing = ops.openBufferFor(target);
        if (existing != null) {
            done.accept(applyToBufferIfUnchanged(existing, expectedText, newText));
            return;
        }
        ops.openBackgroundBufferAsync(target, buffer -> {
            boolean applied = applyToBufferIfUnchanged(buffer, expectedText, newText);
            if (!applied && buffer != null) {
                ops.discardBackgroundBuffer(buffer);
            }
            done.accept(applied);
        });
    }

    private boolean applyToBufferIfUnchanged(EditorBuffer buffer, String expectedText, String newText) {
        if (buffer == null
                || !buffer.isEditable()
                || buffer.isDisposed()
                || buffer.isTruncatedLoad()
                || !java.util.Objects.equals(expectedText, buffer.getContent())
                || !NoUndoGuard.allow(buffer, tr("noUndo.op.diff"))) {
            return false;
        }
        buffer.replaceWholeDocument(newText);
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

    /** Opens a diff of a file or a multi-file review of a folder at HEAD vs the working tree. */
    void diffPathVsHead(Path path) {
        if (path == null) {
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
        if (Files.isDirectory(path)) {
            diffDirectoryVsRef(path, root, rel, "HEAD", tr("diff.side.head"));
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
                fileSide(path),
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
                        models.set(index, PatchLineNumbers.renumber(model, fp.oldLineNumbers(), fp.newLineNumbers()));
                        if (remaining.decrementAndGet() == 0) {
                            if (models.contains(null)) {
                                host.setStatus(tr("status.diff.tooLarge"));
                                return;
                            }
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
            DiffViewerPane pane = patchPane(null, null, null, buffer.getTitle(), fp, models.get(i));
            String rightName = patchNames(fp, buffer.getTitle())[1];
            entries.add(new PatchReviewPane.Entry(rightName, fp.additions(), fp.deletions(), pane));
        }
        // Inside a repository the patch can be applied, so it always opens as a review tab, which carries
        // the two Apply buttons; elsewhere a one-file patch stays the plain diff it always was.
        boolean applicable = git.isAvailable();
        if (entries.size() == 1 && !applicable) {
            ops.addDiffTab(entries.get(0).pane());
        } else {
            PatchReviewPane review = new PatchReviewPane(tr("diff.title.patchSet", entries.size()), entries);
            if (applicable) {
                // The text that was parsed for this tab: what is applied is what is being reviewed.
                byte[] patch = GitPatchCoordinator.patchBytes(buffer);
                Path root = git.repoRoot(); // the repository it was opened in; see GitPatchCoordinator.apply
                review.setApplyActions(
                        () -> git.patches().apply(root, patch, false),
                        () -> git.patches().apply(root, patch, true));
            }
            ops.addDiffTab(review);
        }
        host.setStatus(tr("status.diff.patchFilesOpened", entries.size()));
    }

    /** The left and right file names of a patch section; {@code fallback} when it names neither. */
    private static String[] patchNames(PatchParser.FilePatch fp, String fallback) {
        String oldLabel = cleanPatchLabel(fp.oldPath());
        String newLabel = cleanPatchLabel(fp.newPath());
        String leftName = !oldLabel.isEmpty() ? oldLabel : (!newLabel.isEmpty() ? newLabel : fallback);
        String rightName = !newLabel.isEmpty() ? newLabel : leftName;
        return new String[] {leftName, rightName};
    }

    /**
     * The read-only diff pane of one patch section: its reconstructed sides, numbered as the patch numbers
     * them ({@code initial} is already {@linkplain PatchLineNumbers#renumber renumbered}), and re-numbered again
     * whenever the sides are swapped or a diff option changes. {@code title} / the headers default to the
     * patch-file wording when {@code null}.
     */
    private DiffViewerPane patchPane(
            String title,
            String headerLeft,
            String headerRight,
            String fallback,
            PatchParser.FilePatch fp,
            com.editora.diff.DiffModels.DiffModel initial) {
        String[] names = patchNames(fp, fallback);
        String leftName = names[0];
        String rightName = names[1];
        String leftText = patchText(fp.oldLines(), fp.oldFinalNewline());
        String rightText = patchText(fp.newLines(), fp.newFinalNewline());
        DiffViewerPane pane = new DiffViewerPane(
                title != null ? title : tr("diff.title.patch", rightName),
                headerLeft,
                headerRight,
                leftName,
                rightName,
                leftText,
                rightText,
                initial,
                host.settings().getFontFamily(),
                host.settings().getFontSize(),
                host.settings().isShowLineNumbers(),
                rightName);
        pane.setOnExportPatch(this::exportPatch);
        pane.setOptions(lastDiffOptions);
        String[] current = {leftText, rightText};
        // One generation for both requests, as in buildDiffPane: an option toggle issued while a swap
        // was pending was computed for the unswapped texts and then installed beside the swapped ones.
        AtomicLong generation = new AtomicLong();
        // The patch's own line numbers, in the order the sides are displayed now.
        List<List<Integer>> numbers = new ArrayList<>(List.of(fp.oldLineNumbers(), fp.newLineNumbers()));
        pane.setOnSwapRequested((newLeft, newRight) -> {
            long requested = generation.incrementAndGet();
            diffService.compute(newLeft, newRight, lastDiffOptions, model -> {
                if (requested != generation.get()) {
                    pane.cancelSwap();
                    return;
                }
                if (model == null) {
                    pane.cancelSwap();
                    host.setStatus(tr("status.diff.tooLarge"));
                    return;
                }
                current[0] = newLeft;
                current[1] = newRight;
                Collections.reverse(numbers);
                pane.swapSides(PatchLineNumbers.renumber(model, numbers.get(0), numbers.get(1)));
            });
        });
        pane.setOnOptionsChanged(opts -> {
            lastDiffOptions = opts;
            long requested = generation.incrementAndGet();
            String left = current[0];
            String right = current[1];
            diffService.compute(left, right, opts, model -> {
                if (model != null && requested == generation.get()) {
                    pane.updateContent(left, right, PatchLineNumbers.renumber(model, numbers.get(0), numbers.get(1)));
                }
            });
        });
        return pane;
    }

    /**
     * Opens one file of a change set that exists only as a patch — a pull request's file — as a read-only
     * diff tab, through the same path as a {@code .patch} file: each hunk keeps the line numbers its header
     * states (so separate hunks do not read as one contiguous block starting at line 1) and a side that does
     * not end in a newline says so.
     */
    void openPatchFileDiff(
            String title, String headerLeft, String headerRight, String fallback, PatchParser.FilePatch fp) {
        diffService.compute(
                patchText(fp.oldLines(), fp.oldFinalNewline()),
                patchText(fp.newLines(), fp.newFinalNewline()),
                lastDiffOptions,
                model -> {
                    if (model == null) {
                        host.setStatus(tr("status.diff.tooLarge"));
                        return;
                    }
                    ops.addDiffTab(patchPane(
                            title,
                            headerLeft,
                            headerRight,
                            fallback,
                            fp,
                            PatchLineNumbers.renumber(model, fp.oldLineNumbers(), fp.newLineNumbers())));
                });
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
                            fileSide(basePath),
                            fileSide(chosen),
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
                cb -> cb.accept(DiffContent.text(clipboardText)),
                fileSide(path),
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
                cb -> cb.accept(DiffContent.text("")),
                fileSide(path),
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
        submitFileRead(() -> {
            try {
                DirectoryDiff.Result result = DirectoryDiff.compare(leftRoot, rightRoot);
                List<DirectoryReviewPane.Entry> entries = result.entries().stream()
                        .map(entry -> new DirectoryReviewPane.Entry(
                                entry.relativePath(), entry.kind(), entry.leftSize(), entry.rightSize()))
                        .toList();
                javafx.application.Platform.runLater(() -> openDirectoryReview(leftRoot, rightRoot, result, entries));
            } catch (IOException e) {
                javafx.application.Platform.runLater(
                        () -> host.setStatus(tr("status.diff.directoryFailed", e.getMessage())));
            }
        });
    }

    private void openDirectoryReview(
            Path leftRoot, Path rightRoot, DirectoryDiff.Result result, List<DirectoryReviewPane.Entry> entries) {
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
                            ? callback -> callback.accept(DiffContent.text(""))
                            : fileSide(leftFile);
                    DiffSide rightSide = entry.kind() == DirectoryDiff.Kind.LEFT_ONLY
                            ? callback -> callback.accept(DiffContent.text(""))
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
        return callback -> {
            EditorBuffer open = ops.openBufferFor(path);
            if (open != null) {
                // The whole document: text() is only the accessible region of a narrowed buffer.
                callback.accept(DiffContent.text(open.getContent(), open.getLineEnding(), open.getEffectiveCharset()));
                return;
            }
            submitFileRead(() -> {
                DiffContent content = diskContent(path);
                javafx.application.Platform.runLater(() -> callback.accept(content));
            });
        };
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

    /** Diff a project-tree file or folder against a commit chosen from its history. */
    void diffPathVsCommit(Path path) {
        if (path == null) {
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
                    chosen -> openPathVsRef(path, root, rel, chosen.hash(), chosen.shortHash()));
            picker.setOverlayHost(host.overlayHost());
            picker.show(host.window());
        });
    }

    /** Diff a project-tree file or folder against a branch chosen from the repo's branches. */
    void diffPathVsBranch(Path path) {
        if (path == null) {
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
                    chosen -> openPathVsRef(path, root, rel, chosen, chosen));
            picker.setOverlayHost(host.overlayHost());
            picker.show(host.window());
        });
    }

    /** Diff a project-tree file or folder against a tag chosen from the repository. */
    void diffPathVsTag(Path path) {
        if (path == null) {
            host.setStatus(tr("status.diff.noFile"));
            return;
        }
        if (git.reportIfNoRepo()) {
            return;
        }
        Path root = git.repoRoot();
        String rel = GitService.repoRelative(root, path);
        if (rel == null) {
            host.setStatus(tr("status.diff.notInRepo"));
            return;
        }
        git.service().tags(root, tags -> {
            if (tags.isEmpty()) {
                host.setStatus(tr("status.diff.noTags"));
                return;
            }
            QuickOpen<String> picker = new QuickOpen<>(
                    tr("diff.tagPickerTitle"),
                    tr("diff.tagPickerPrompt"),
                    () -> tags,
                    tag -> tag,
                    tag -> tr("diff.tag"),
                    tag -> openPathVsRef(path, root, rel, tag, tag));
            picker.setOverlayHost(host.overlayHost());
            picker.show(host.window());
        });
    }

    /**
     * Lets the user pick one of {@code root}'s tags (newest first) for a tag command; reports when there are
     * none. {@code onChoose} runs with the tag's short name.
     */
    void pickTag(Path root, String title, Consumer<String> onChoose) {
        git.service().tags(root, tags -> {
            if (tags.isEmpty()) {
                host.setStatus(tr("status.git.noTags"));
                return;
            }
            QuickOpen<String> picker = new QuickOpen<>(
                    title, tr("diff.tagPickerPrompt"), () -> tags, tag -> tag, tag -> tr("diff.tag"), onChoose);
            picker.setOverlayHost(host.overlayHost());
            picker.show(host.window());
        });
    }

    private void openPathVsRef(Path path, Path root, String rel, String ref, String displayRef) {
        if (Files.isDirectory(path)) {
            diffDirectoryVsRef(path, root, rel, ref, displayRef);
            return;
        }
        String name = path.getFileName().toString();
        openDiff(
                tr("diff.title.vsBranch", name, displayRef),
                displayRef,
                tr("diff.side.working"),
                name,
                name,
                blobSide(root, ref + ":" + rel, path),
                fileSide(path),
                DiffViewerPane.EditableSide.RIGHT,
                path);
    }

    private void diffDirectoryVsRef(Path folder, Path root, String rel, String ref, String displayRef) {
        host.setStatus(tr("status.diff.scanningGitFolder", displayRef));
        git.service().workingTreeDiff(root, folder, ref, result -> {
            if (!result.ok()) {
                host.setStatus(tr("status.diff.gitFolderFailed", result.error()));
                return;
            }
            openGitDirectoryReview(folder, root, rel, ref, displayRef, null, result);
        });
    }

    /**
     * Opens the changed-files review between two revisions of the repository at {@code root} — the ref↔ref
     * form of "compare with branch", on the same review surface. Both sides are blobs, so nothing is editable.
     */
    void compareRefs(Path root, String leftRef, String rightRef) {
        if (root == null) {
            git.reportIfNoRepo();
            return;
        }
        host.setStatus(tr("status.diff.comparingRefs", leftRef, rightRef));
        git.service().refDiff(root, leftRef, rightRef, result -> {
            if (!result.ok()) {
                host.setStatus(tr("status.diff.gitFolderFailed", result.error()));
                return;
            }
            openGitDirectoryReview(root, root, "", leftRef, leftRef, rightRef, result);
        });
    }

    /**
     * The review of {@code result}: {@code ref} on the left and, on the right, the working tree
     * ({@code rightRef == null}, editable) or another revision (read-only).
     */
    private void openGitDirectoryReview(
            Path folder,
            Path root,
            String folderRel,
            String ref,
            String displayRef,
            String rightRef,
            GitService.WorkingTreeDiff result) {
        boolean working = rightRef == null;
        String prefix = folderRel.isEmpty() ? "" : folderRel + "/";
        List<DirectoryReviewPane.Entry> entries = result.files().stream()
                .map(file -> new DirectoryReviewPane.Entry(
                        file.path().startsWith(prefix) ? file.path().substring(prefix.length()) : file.path(),
                        switch (file.status()) {
                            case 'A' -> DirectoryDiff.Kind.RIGHT_ONLY;
                            case 'D' -> DirectoryDiff.Kind.LEFT_ONLY;
                            default -> DirectoryDiff.Kind.MODIFIED;
                        },
                        -1,
                        -1))
                .toList();
        String summary = (working
                        ? tr("diff.directory.gitSummary", entries.size(), displayRef)
                        : tr("diff.directory.refSummary", entries.size(), displayRef, rightRef))
                + (result.truncated() ? " · " + tr("diff.directory.truncated") : "");
        String reviewTitle = working
                ? tr("diff.title.vsBranch", pathName(folder), displayRef)
                : tr("diff.title.refVsRef", displayRef, rightRef);
        DirectoryReviewPane review = new DirectoryReviewPane(reviewTitle, entries, summary, (entry, ready) -> {
            String repoPath = prefix + entry.label();
            Path workingFile = root.resolve(repoPath);
            DiffSide leftSide = entry.kind() == DirectoryDiff.Kind.RIGHT_ONLY
                    ? callback -> callback.accept(DiffContent.text(""))
                    : blobSide(root, ref + ":" + repoPath, workingFile);
            DiffSide rightSide = entry.kind() == DirectoryDiff.Kind.LEFT_ONLY
                    ? callback -> callback.accept(DiffContent.text(""))
                    : working ? fileSide(workingFile) : blobSide(root, rightRef + ":" + repoPath, workingFile);
            buildDiffPane(
                    tr("diff.title.vsBranch", entry.label(), working ? displayRef : rightRef),
                    displayRef + ":" + repoPath,
                    working ? workingFile.toString() : rightRef + ":" + repoPath,
                    entry.label(),
                    entry.label(),
                    leftSide,
                    rightSide,
                    working ? DiffViewerPane.EditableSide.RIGHT : DiffViewerPane.EditableSide.NONE,
                    working ? workingFile : null,
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
                        ? (working
                                ? tr("status.diff.gitFolderIdentical", displayRef)
                                : tr("status.diff.refsIdentical", displayRef, rightRef))
                        : tr("status.diff.directoryOpened", entries.size()));
    }

    /** Diff a Git-panel file row: staged → index↔HEAD, unstaged → worktree↔index. */
    void diffGitPanelFile(String repoRel, boolean staged) {
        Path root = git.repoRoot(); // capture at open time; see diffPathVsHead
        if (root == null) {
            return;
        }
        Path abs = root.resolve(repoRel);
        String name = abs.getFileName().toString();
        java.util.concurrent.atomic.AtomicReference<GitService.BlobResult> expectedIndex =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<GitService.BlobResult> headBlob =
                new java.util.concurrent.atomic.AtomicReference<>();
        // A staged rename lives in HEAD under its old path: HEAD:<new path> does not exist, and the failed
        // lookup used to show the whole file as added. Same resolution as the review tab's targets.
        String headRel = leftSourcePath(git.status(), repoRel, staged);
        if (staged) {
            // index↔HEAD: neither side is the working file, so no "apply" (read-only diff).
            openDiff(
                    tr("diff.title.staged", name),
                    tr("diff.side.head"),
                    tr("diff.side.staged"),
                    name,
                    name,
                    blobSide(root, "HEAD:" + headRel, abs, headBlob::set),
                    indexBlobSide(root, repoRel, abs, expectedIndex),
                    DiffViewerPane.EditableSide.NONE,
                    null,
                    pane -> configureGitHunks(pane, root, repoRel, abs, true, expectedIndex, headBlob));
        } else {
            openDiff(
                    tr("diff.title.unstaged", name),
                    tr("diff.side.staged"),
                    tr("diff.side.working"),
                    name,
                    name,
                    indexBlobSide(root, repoRel, abs, expectedIndex),
                    fileSide(abs),
                    DiffViewerPane.EditableSide.RIGHT,
                    abs,
                    pane -> configureGitHunks(pane, root, repoRel, abs, false, expectedIndex, headBlob));
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
            java.util.concurrent.atomic.AtomicReference<GitService.BlobResult> expectedIndex =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<GitService.BlobResult> headBlob =
                    new java.util.concurrent.atomic.AtomicReference<>();
            DiffSide left = staged
                    ? blobSide(root, "HEAD:" + leftPath, file, headBlob::set)
                    : leftPath == null
                            ? callback -> {
                                expectedIndex.set(new GitService.BlobResult(false, new byte[0]));
                                callback.accept(DiffContent.text(""));
                            }
                            : indexBlobSide(root, leftPath, file, expectedIndex);
            DiffSide right = staged ? indexBlobSide(root, target.path(), file, expectedIndex) : fileSide(file);
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
                    pane -> configureGitHunks(pane, root, target.path(), file, staged, expectedIndex, headBlob),
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

    /**
     * One file of a review between two sets of Git blobs: {@code leftSpec}/{@code rightSpec} are
     * {@code <rev>:<path>} blob specs, {@code null} for a side on which the file does not exist.
     */
    record BlobReviewTarget(String path, char status, String leftSpec, String rightSpec) {}

    /**
     * Opens a read-only multi-file review tab — the one {@link #reviewGitChanges} builds — for files given
     * as pairs of blob specs in the repository at {@code root}: a stash against the commit it was made on,
     * or any two revisions.
     */
    void openBlobReview(
            String title, String headerLeft, String headerRight, Path root, List<BlobReviewTarget> targets) {
        if (root == null || targets.isEmpty()) {
            return;
        }
        host.setStatus(tr("status.diff.preparingReview", targets.size()));
        List<BuiltDiff> built = new ArrayList<>(Collections.nCopies(targets.size(), null));
        AtomicInteger remaining = new AtomicInteger(targets.size());
        DiffSide absent = callback -> callback.accept(DiffContent.text(""));
        for (int i = 0; i < targets.size(); i++) {
            int index = i;
            BlobReviewTarget target = targets.get(i);
            Path file = root.resolve(target.path());
            String name = target.path().substring(target.path().lastIndexOf('/') + 1);
            buildDiffPane(
                    name,
                    headerLeft,
                    headerRight,
                    target.path(),
                    target.path(),
                    target.leftSpec() == null ? absent : blobSide(root, target.leftSpec(), file),
                    target.rightSpec() == null ? absent : blobSide(root, target.rightSpec(), file),
                    DiffViewerPane.EditableSide.NONE,
                    null,
                    pane -> {},
                    result -> {
                        built.set(index, result);
                        if (remaining.decrementAndGet() > 0) {
                            return;
                        }
                        List<PatchReviewPane.Entry> entries = new ArrayList<>();
                        for (int j = 0; j < targets.size(); j++) {
                            BuiltDiff diff = built.get(j);
                            if (diff != null) {
                                entries.add(new PatchReviewPane.Entry(
                                        targets.get(j).path(),
                                        String.valueOf(targets.get(j).status()),
                                        diff.model().added(),
                                        diff.model().removed(),
                                        diff.pane()));
                            }
                        }
                        if (!entries.isEmpty()) {
                            ops.addDiffTab(new PatchReviewPane(title, entries));
                            host.setStatus(tr("status.diff.reviewOpened", entries.size()));
                        }
                    });
        }
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

    /**
     * The path the left (HEAD for a staged row, index for an unstaged one) side of {@code repoRel}'s Git-panel
     * diff is read from: the rename/copy source when the status says the row is one, else the path itself.
     */
    static String leftSourcePath(GitStatus status, String repoRel, boolean staged) {
        if (status == null || !staged) {
            return repoRel;
        }
        for (FileEntry file : status.files()) {
            if (file.staged() && repoRel.equals(file.path())) {
                return sourcePath(file.path(), file.origPath(), file.index());
            }
        }
        return repoRel;
    }

    private static String sourcePath(String path, String originalPath, char status) {
        return (status == 'R' || status == 'C') && originalPath != null && !originalPath.isBlank()
                ? originalPath
                : path;
    }

    private void configureGitHunks(
            DiffViewerPane pane,
            Path root,
            String repoRel,
            Path file,
            boolean staged,
            java.util.concurrent.atomic.AtomicReference<GitService.BlobResult> expectedIndexBlob,
            java.util.concurrent.atomic.AtomicReference<GitService.BlobResult> headBlob) {
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
                    applyToLocalIfUnchangedAsync(file, request.beforeText(), request.afterText(), applied -> {
                        if (!applied) {
                            host.setStatus(tr("status.diff.localStale"));
                            pane.refresh();
                        }
                    });
                }
                case STAGE, UNSTAGE -> {
                    GitService.BlobResult expectedBlob = expectedIndexBlob.get();
                    if (expectedBlob == null) {
                        host.setStatus(tr("status.diff.hunkStale", "index snapshot is still loading"));
                        return;
                    }
                    stageHunk(root, repoRel, file, request, expectedBlob, headBlob.get());
                }
            }
        });
    }

    /**
     * Writes the index side a Stage/Unstage request asks for. The desired blob <em>bytes</em> are staged,
     * not a text patch: the displayed text has forgotten each line's terminator and the blob's charset, so a
     * patch built from it never applied to a CRLF blob and could put UTF-8 bytes into a Latin-1 one.
     *
     * <p>Three results are not a rewrite of the entry's blob. Unstaging everything of a path HEAD does not
     * have, and staging everything of a file that is gone from the working tree, remove the entry (a blob
     * would leave the path staged as an empty file). Unstaging everything of a path HEAD has puts HEAD's own
     * bytes back. And a path that is not in the index yet takes its encoding, byte-order mark and line
     * terminators from the working file, since there is no blob to take them from.
     */
    private void stageHunk(
            Path root,
            String repoRel,
            Path file,
            DiffViewerPane.GitHunkRequest request,
            GitService.BlobResult expectedBlob,
            GitService.BlobResult head) {
        boolean stage = request.action() == DiffViewerPane.GitHunkAction.STAGE;
        Consumer<com.editora.process.ProcessRunner.Result> done = result -> {
            if (result.ok()) {
                host.setStatus(tr(stage ? "status.diff.hunkStaged" : "status.diff.hunkUnstaged"));
                git.afterMutation();
            } else {
                host.setStatus(tr("status.diff.hunkStale", result.message()));
            }
        };
        EditorBuffer open = ops.openBufferFor(file);
        String ecCharset = ops.editorConfigCharset(file);
        String openCharset = open == null ? null : open.getEffectiveCharset();
        if (request.wholeFile() && request.afterText().isEmpty()) {
            boolean gone = stage ? open == null && !Files.exists(file) : head != null && !head.found();
            if (gone) {
                git.service().removeIndexEntry(root, repoRel, expectedBlob, done);
                return;
            }
        }
        if (request.wholeFile()
                && !stage
                && head != null
                && head.found()
                && DiffSideText.decode(head.bytes(), ecCharset, openCharset)
                        .text()
                        .equals(request.afterText())) {
            git.service().stageBlob(root, repoRel, expectedBlob, head.bytes(), done);
            return;
        }
        if (expectedBlob.found()) {
            stageRewritten(
                    root,
                    repoRel,
                    expectedBlob,
                    expectedBlob.bytes(),
                    request.beforeText(),
                    ecCharset,
                    openCharset,
                    request.afterText(),
                    done);
            return;
        }
        if (open != null) {
            byte[] working = EditorConfigCharset.encode(
                    com.editora.editor.LineEndings.apply(open.getContent(), open.getLineEnding()),
                    open.getEffectiveCharset());
            stageRewritten(
                    root, repoRel, expectedBlob, working, null, ecCharset, openCharset, request.afterText(), done);
            return;
        }
        submitFileRead(() -> {
            byte[] working;
            try {
                working = Files.isRegularFile(file) ? Files.readAllBytes(file) : new byte[0];
            } catch (IOException unreadable) {
                working = new byte[0];
            }
            byte[] bytes = working;
            javafx.application.Platform.runLater(() -> stageRewritten(
                    root, repoRel, expectedBlob, bytes, null, ecCharset, null, request.afterText(), done));
        });
    }

    /**
     * Stages {@code original} rewritten to the request's desired text. {@code shownText} is the text the hunk
     * was computed against when {@code original} is the index blob; {@code null} when {@code original} is the
     * working file standing in for a path with no index entry, which only lends its encoding and terminators.
     */
    private void stageRewritten(
            Path root,
            String repoRel,
            GitService.BlobResult expectedBlob,
            byte[] original,
            String shownText,
            String ecCharset,
            String openCharset,
            String afterText,
            Consumer<com.editora.process.ProcessRunner.Result> done) {
        EditorConfigCharset.Decoded decoded = DiffSideText.decodeRaw(original, ecCharset, openCharset);
        byte[] blob = BlobRewrite.rewrite(
                original,
                EditorConfigCharset.charsetFor(decoded.charset()),
                EditorConfigCharset.bomFor(decoded.charset()),
                shownText == null ? decoded.text() : shownText,
                afterText);
        if (blob == null) {
            host.setStatus(tr("status.diff.hunkEncoding"));
            return;
        }
        git.service().stageBlob(root, repoRel, expectedBlob, blob, done);
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
        diffCommitFile(git.repoRoot(), hash, repoRel, origRepoRel); // capture at open time; see diffPathVsHead
    }

    /** {@link #diffCommitFile(String, String, String)} in the repository the commit was listed from. */
    void diffCommitFile(Path root, String hash, String repoRel, String origRepoRel) {
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

    /**
     * Opens the files that differ between two commits as one navigable review tab — a whole commit against
     * its parent, or two commits picked in the Git Log. Read-only; each file's diff is built when it is first
     * selected ({@link DirectoryReviewPane}), so a commit touching thousands of files opens at once. A
     * renamed file's left side is read at its old path; an added or deleted file has an empty side.
     *
     * @param leftRev the older side; never read for an added file, so {@code <root commit>^1} is harmless
     */
    void reviewRevisions(
            Path root,
            String title,
            String leftRev,
            String leftLabel,
            String rightRev,
            String rightLabel,
            List<GitService.CommitFile> files,
            boolean truncated) {
        if (root == null) {
            return;
        }
        java.util.Map<String, GitService.CommitFile> byPath = new java.util.HashMap<>();
        List<DirectoryReviewPane.Entry> entries = new ArrayList<>(files.size());
        for (GitService.CommitFile file : files) {
            byPath.put(file.path(), file);
            entries.add(new DirectoryReviewPane.Entry(
                    file.path(),
                    switch (file.status()) {
                        case 'A' -> DirectoryDiff.Kind.RIGHT_ONLY;
                        case 'D' -> DirectoryDiff.Kind.LEFT_ONLY;
                        default -> DirectoryDiff.Kind.MODIFIED;
                    },
                    -1,
                    -1));
        }
        String summary = tr("diff.revisions.summary", entries.size(), leftLabel, rightLabel)
                + (truncated ? " · " + tr("diff.directory.truncated") : "");
        DiffSide empty = callback -> callback.accept(DiffContent.text(""));
        DirectoryReviewPane review = new DirectoryReviewPane(title, entries, summary, (entry, ready) -> {
            GitService.CommitFile file = byPath.get(entry.label());
            Path workingFile = root.resolve(file.path());
            String leftPath = sourcePath(file.path(), file.origPath(), file.status());
            String name = file.path().substring(file.path().lastIndexOf('/') + 1);
            buildDiffPane(
                    tr("diff.title.commitFile", name, rightLabel),
                    leftLabel,
                    rightLabel,
                    leftPath.substring(leftPath.lastIndexOf('/') + 1),
                    name,
                    entry.kind() == DirectoryDiff.Kind.RIGHT_ONLY
                            ? empty
                            : blobSide(root, leftRev + ":" + leftPath, workingFile),
                    entry.kind() == DirectoryDiff.Kind.LEFT_ONLY
                            ? empty
                            : blobSide(root, rightRev + ":" + file.path(), workingFile),
                    DiffViewerPane.EditableSide.NONE,
                    null,
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
        host.setStatus(tr("status.diff.reviewOpened", entries.size()));
    }

    /**
     * Diff a file across a pull: what it was at the commit the branch stood on against what the pull brought,
     * read-only. Opened from the file's change graph in the Git transcript, which is where the two commits
     * come from; a file the pull added or deleted has an empty side.
     */
    void diffPulledFile(com.editora.git.GitOutputDiffs.Target pulled) {
        Path root = git.repoRoot();
        if (root == null) {
            return;
        }
        String newPath = pulled.newPath();
        String name = newPath.substring(newPath.lastIndexOf('/') + 1);
        String oldHash = GitFormat.shortHash(pulled.oldRev());
        String newHash = GitFormat.shortHash(pulled.newRev());
        openDiff(
                tr("diff.title.commitFile", name, oldHash + ".." + newHash),
                tr("diff.title.vsCommitShort", oldHash),
                tr("diff.title.vsCommitShort", newHash),
                name,
                name,
                blobSide(root, pulled.oldRev() + ":" + pulled.oldPath(), root.resolve(newPath)),
                blobSide(root, pulled.newRev() + ":" + newPath, root.resolve(newPath)),
                DiffViewerPane.EditableSide.NONE,
                null);
    }

    /**
     * Compares a commit's version of a file with its current working-tree copy. The working side is the
     * editable target, so the normal line, hunk, whole-file, Result, Undo, and Save controls are available.
     * {@code repoRel} names the blob in the selected commit while {@code workingFile} may name the file's
     * current path after a rename.
     */
    void diffCommitFileVsWorking(String hash, String repoRel, Path workingFile) {
        diffCommitFileVsWorking(git.repoRoot(), hash, repoRel, workingFile); // capture at open time
    }

    /** {@link #diffCommitFileVsWorking(String, String, Path)} in the repository the commit was listed from. */
    void diffCommitFileVsWorking(Path root, String hash, String repoRel, Path workingFile) {
        if (root == null || workingFile == null) {
            return;
        }
        Path target = workingFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(target) && ops.openBufferFor(target) == null) {
            host.setStatus(tr("status.git.fileGone", repoRel));
            return;
        }
        String name =
                target.getFileName() == null ? repoRel : target.getFileName().toString();
        String displayHash = GitFormat.shortHash(hash);
        openDiff(
                tr("diff.title.vsCommit", name, displayHash),
                tr("diff.title.vsCommitShort", displayHash),
                tr("diff.side.working"),
                name,
                name,
                blobSide(root, hash + ":" + repoRel, target),
                fileSide(target),
                DiffViewerPane.EditableSide.RIGHT,
                target);
    }

    /** The current working-tree text of {@code abs}: an open buffer's (incl. unsaved edits) if open,
     *  else the file on disk ("" when unreadable / deleted). */
    private DiffContent diskContent(Path abs) {
        try {
            if (!Files.exists(abs)) {
                return DiffContent.text("");
            }
            // Same ceiling as a Git blob side: an untracked build artefact or data dump must not be read
            // whole into memory and handed to the line differ.
            if (Files.size(abs) > MAX_SIDE_BYTES) {
                return DiffContent.presentation(tooLargeSide(BinaryDiff.describeLarge(abs)));
            }
            // Decode the closed working file exactly as the editor would load it (lossless charset fallback,
            // bare \n): this text is later compared with the buffer the file is opened into for an apply.
            byte[] bytes = Files.readAllBytes(abs);
            if (BinaryDiff.isProbablyBinary(bytes)) {
                return DiffContent.presentation(BinaryDiff.describe(bytes));
            }
            return DiffContent.decoded(DiffSideText.decodeRaw(bytes, ops.editorConfigCharset(abs), null));
        } catch (IOException e) {
            return DiffContent.presentation("");
        }
    }

    /**
     * A diff side that fetches a git blob ({@code spec}, e.g. {@code HEAD:path}) as raw bytes and decodes it
     * the way the editor reads {@code file} ({@link DiffSideText}: the open buffer's charset when the file is
     * open, else BOM, {@code .editorconfig} charset, UTF-8, with the editor's lossless fallback) — so a
     * Latin-1/UTF-16 tracked file shows real text and no spurious whole-file change, instead of mojibake or
     * U+FFFD that an apply would then write into the document.
     */
    private DiffSide blobSide(Path root, String spec, Path file) {
        return blobSide(root, spec, file, ignored -> {});
    }

    /** The effective charset of {@code file}'s open buffer, or {@code null} when it is not open. */
    private String openCharset(Path file) {
        EditorBuffer open = file == null ? null : ops.openBufferFor(file);
        return open == null ? null : open.getEffectiveCharset();
    }

    /** Index side whose exact raw blob becomes the compare-and-swap preimage for hunk actions. */
    private DiffSide indexBlobSide(
            Path root,
            String path,
            Path file,
            java.util.concurrent.atomic.AtomicReference<GitService.BlobResult> snapshot) {
        return blobSide(root, ":" + path, file, snapshot::set);
    }

    private DiffSide blobSide(Path root, String spec, Path file, Consumer<GitService.BlobResult> onSnapshot) {
        String ecCharset = ops.editorConfigCharset(file);
        return onText -> git.service().showBlob(root, spec, result -> {
            if (result.truncated()) {
                // Complete the callback: a review surface waits on both sides, so returning here left it on
                // "Loading…" forever. The surrogate text is not applicable, which disables every mutation.
                host.setStatus(tr("status.git.blobTooLarge"));
                onText.accept(DiffContent.presentation(tooLargeSide(spec)));
                return;
            }
            onSnapshot.accept(result);
            byte[] bytes = result.found() ? result.bytes() : new byte[0];
            onText.accept(
                    BinaryDiff.isProbablyBinary(bytes)
                            ? DiffContent.presentation(BinaryDiff.describe(bytes))
                            : DiffContent.decoded(DiffSideText.decodeRaw(bytes, ecCharset, openCharset(file))));
        });
    }

    /**
     * The stand-in text for a side over {@link #MAX_SIDE_BYTES}. It names what it stands for (a file's size
     * and digest, a blob's spec): one constant text for every oversized side made two different 10 MB files
     * compare as "No differences".
     */
    static String tooLargeSide(String identity) {
        return tr("diff.side.tooLarge") + " ⟦" + identity + "⟧";
    }

    /**
     * Saves a unified-diff patch (the diff viewer's export action) via a file chooser. The patch is written
     * for the sides' <em>source bytes</em>, not the viewer's text: each side's lines get their file's line
     * ending back (the viewer holds bare {@code \n}, and a patch without the {@code \r} never applied to a
     * CRLF file), and a legacy single-byte charset both sides share is kept. Computed off the FX thread.
     */
    private void exportPatch(DiffViewerPane.PatchRequest request) {
        String[] sides = patchSides(request);
        diffService.patch(request.leftLabel(), request.rightLabel(), sides[0], sides[1], patch -> {
            if (patch == null) {
                host.setStatus(tr("status.diff.tooLarge"));
                return;
            }
            if (patch.isEmpty()) {
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
                // Staged: a failed write must not empty a patch the Save dialog agreed to replace.
                com.editora.io.StagedExport.write(
                        f.toPath(),
                        patchBytes(
                                patch,
                                request.leftFormat().charset(),
                                request.rightFormat().charset()));
                host.setStatus(tr("status.diff.patchSaved", f.getName()));
            } catch (IOException e) {
                host.setStatus(tr("status.diff.patchFailed", e.getMessage() == null ? "" : e.getMessage()));
            }
        });
    }

    /** The two texts a patch is written from: each displayed side with its source's line ending put back. */
    static String[] patchSides(DiffViewerPane.PatchRequest request) {
        return new String[] {
            com.editora.editor.LineEndings.apply(
                    request.leftText(), request.leftFormat().lineEnding()),
            com.editora.editor.LineEndings.apply(
                    request.rightText(), request.rightFormat().lineEnding())
        };
    }

    /**
     * The bytes of an exported patch: in the sides' own charset when both are the same single-byte legacy
     * charset that can spell every character of it (so the patch's lines are the file's bytes), else UTF-8.
     * No byte-order mark either way — it would become part of the first header line.
     */
    static byte[] patchBytes(String patch, String leftCharset, String rightCharset) {
        boolean legacy =
                EditorConfigCharset.LATIN1.equals(leftCharset) || EditorConfigCharset.WINDOWS_1252.equals(leftCharset);
        if (legacy && leftCharset.equals(rightCharset) && EditorConfigCharset.canEncode(patch, leftCharset)) {
            return patch.getBytes(EditorConfigCharset.charsetFor(leftCharset));
        }
        return patch.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Opens the merge-conflict resolution view for the active buffer (if it has conflict markers). */
    void resolveConflicts() {
        EditorBuffer b = host.activeBuffer();
        if (b == null) {
            host.setStatus(tr("status.diff.noFile"));
            return;
        }
        // The whole document, not text(): on a narrowed buffer that is only the region, and Apply replaces the
        // whole document with the resolution.
        String text = b.getContent();
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
        String openCharset = b.getEffectiveCharset();
        git.service()
                .showBlob(
                        root,
                        ":1:" + rel,
                        base -> git.service()
                                .showBlob(
                                        root,
                                        ":2:" + rel,
                                        ours -> git.service().showBlob(root, ":3:" + rel, theirs -> {
                                            if (!b.getContent().equals(text)) {
                                                host.setStatus(tr("status.merge.stale"));
                                                return;
                                            }
                                            if (base.truncated() || ours.truncated() || theirs.truncated()) {
                                                host.setStatus(tr("status.git.blobTooLarge"));
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
                                            String baseText = decodeMergeBlob(base.bytes(), charset, openCharset);
                                            String oursText = decodeMergeBlob(ours.bytes(), charset, openCharset);
                                            String theirsText = decodeMergeBlob(theirs.bytes(), charset, openCharset);
                                            submitFileRead(() -> {
                                                ThreeWayMerge.Result result =
                                                        ThreeWayMerge.merge(baseText, oursText, theirsText);
                                                javafx.application.Platform.runLater(() -> {
                                                    if (!b.getContent().equals(text)) {
                                                        host.setStatus(tr("status.merge.stale"));
                                                        return;
                                                    }
                                                    openStageMerge(b, text, format, hasMarkers, result.file());
                                                });
                                            });
                                        })));
    }

    /** Where the resolver takes its conflicts from when the file no longer matches Git's three versions. */
    enum MergeSource {
        /** Merge Git's three versions again; applying replaces what the file holds now. */
        GIT_VERSIONS,
        /** Resolve the conflict markers the file contains, keeping everything else in it. */
        FILE_MARKERS,
        CANCEL
    }

    /** Asks which {@link MergeSource} to use; replaceable so a test need not show a dialog. */
    java.util.function.Function<Boolean, MergeSource> mergeSourceChooser = this::askMergeSource;

    /**
     * Opens the resolver on the merge of Git's three versions when that is the merge the file holds. When it
     * is not, the conflict regions still written in the file are used as they are ({@link
     * ThreeWayMerge#sourceFor}): they are Git's own answer, and conflicts already resolved by hand stay
     * resolved. Only a file with no conflict left is put to the user, because showing one then means starting
     * again from Git's versions and replacing what the file holds.
     */
    private void openStageMerge(
            EditorBuffer buffer,
            String sourceText,
            DiffText format,
            boolean hasMarkers,
            ConflictParser.ConflictFile merged) {
        ConflictParser.ConflictFile written = ConflictParser.parse(format.lines());
        MergeSource source =
                switch (ThreeWayMerge.sourceFor(merged, written)) {
                    case MERGE -> MergeSource.GIT_VERSIONS;
                    case FILE_MARKERS -> MergeSource.FILE_MARKERS;
                    case ASK -> mergeSourceChooser.apply(hasMarkers);
                };
        switch (source) {
            case GIT_VERSIONS -> openMergePane(buffer, sourceText, format, merged);
            case FILE_MARKERS -> openMergePane(buffer, sourceText, format, written);
            case CANCEL -> host.setStatus(tr("status.merge.cancelled"));
        }
    }

    private MergeSource askMergeSource(boolean hasMarkers) {
        javafx.scene.control.ButtonType git = new javafx.scene.control.ButtonType(
                tr("merge.edited.useGit"), javafx.scene.control.ButtonBar.ButtonData.OTHER);
        javafx.scene.control.ButtonType file = new javafx.scene.control.ButtonType(
                tr("merge.edited.useFile"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        javafx.scene.control.Alert ask = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                tr(hasMarkers ? "merge.edited.message" : "merge.edited.messageNoMarkers"));
        ask.getButtonTypes().setAll(git, javafx.scene.control.ButtonType.CANCEL);
        if (hasMarkers) {
            ask.getButtonTypes().add(0, file);
        }
        ask.initOwner(host.window());
        ask.setTitle(tr("merge.edited.title"));
        ask.setHeaderText(null);
        javafx.scene.control.ButtonType chosen = ask.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL);
        return chosen == git
                ? MergeSource.GIT_VERSIONS
                : chosen == file ? MergeSource.FILE_MARKERS : MergeSource.CANCEL;
    }

    /** A merge stage in the buffer's own form; the resolution built from it replaces the buffer's text. */
    private static String decodeMergeBlob(byte[] bytes, String editorConfigCharset, String openCharset) {
        return DiffSideText.decode(bytes, editorConfigCharset, openCharset).text();
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
        if (!buffer.isEditable() || buffer.isDisposed() || buffer.isTruncatedLoad()) {
            host.setStatus(tr("status.diff.applyFailed", buffer.getTitle()));
            return;
        }
        String name = buffer.getPath() == null
                ? buffer.getTitle()
                : buffer.getPath().getFileName().toString();
        // The text the document must still hold for an Apply: the text the resolver opened on, then whatever
        // it last applied. Left at the opening text, a second Apply (a change of mind) was always "stale".
        String[] expected = {sourceText};
        MergeViewerPane pane = new MergeViewerPane(
                tr("merge.title", name),
                conflictFile,
                host.settings().getFontFamily(),
                host.settings().getFontSize(),
                format.lineSeparator(),
                format.finalNewline(),
                (java.util.function.Predicate<String>) resolvedText -> {
                    boolean applied = applyMergeResolution(buffer, expected[0], resolvedText);
                    if (applied) {
                        expected[0] = resolvedText;
                    }
                    return applied;
                });
        ops.addDiffTab(pane);
    }

    /**
     * Writes a merge resolution into the document it was computed for, found <em>at apply time</em>. The
     * resolver tab can outlive the source tab: the buffer captured when it opened is then disposed, its
     * text still equals the baseline, and writing into it reported "applied" while the resolution went
     * nowhere. A file-backed buffer is therefore looked up by path (reopened in the background when its tab
     * was closed) and re-checked for disposed / read-only / truncated before its <em>current</em> text is
     * compared with the baseline. An untitled buffer has no path to find it by, so a closed one is refused.
     */
    boolean applyMergeResolution(EditorBuffer opened, String sourceText, String resolvedText) {
        Path path = opened.getPath();
        EditorBuffer open = path == null ? (opened.isDisposed() ? null : opened) : ops.openBufferFor(path);
        EditorBuffer target = open != null || path == null ? open : ops.openBackgroundBuffer(path);
        boolean reopened = open == null && target != null;
        if (target == null || target.isDisposed() || !target.isEditable() || target.isTruncatedLoad()) {
            if (reopened) {
                ops.discardBackgroundBuffer(target);
            }
            host.setStatus(tr("status.diff.applyFailed", path == null ? opened.getTitle() : path.getFileName()));
            return false;
        }
        if (!target.getContent().equals(sourceText)) {
            if (reopened) {
                ops.discardBackgroundBuffer(target);
            }
            host.setStatus(tr("status.merge.stale"));
            return false;
        }
        if (!NoUndoGuard.allow(target, tr("noUndo.op.diff"))) {
            if (reopened) {
                ops.discardBackgroundBuffer(target);
            }
            return false;
        }
        target.replaceWholeDocument(resolvedText);
        host.setStatus(tr("status.merge.applied"));
        git.resolutionApplied(target); // a finished resolution of an unmerged path is saved and staged
        return true;
    }

    /** Stops the diff worker thread (window close). */
    public void shutdown() {
        try {
            ops.openDiffPanes().forEach(DiffViewerPane::dispose); // incl. panes nested in review tabs
        } catch (RuntimeException e) {
            // best effort: the workers below must still stop
        }
        fileReadExecutor.shutdownNow();
        diffService.shutdown();
    }

    private void submitFileRead(Runnable task) {
        try {
            fileReadExecutor.submit(task);
        } catch (RejectedExecutionException e) {
            if (!fileReadExecutor.isShutdown()) {
                throw e;
            }
        }
    }
}
