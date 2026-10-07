package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.application.Platform;
import javafx.geometry.Bounds;

import com.editora.editor.EditorBuffer;
import com.editora.editor.GitGutterLines;
import com.editora.editor.GitHunk;
import com.editora.editor.HunkRevert;
import com.editora.git.DiffParser;
import com.editora.git.GitChangeBars;
import com.editora.git.GitFileStatus;
import com.editora.git.GitService;
import com.editora.git.HunkStaging;
import org.fxmisc.richtext.CodeArea;

import static com.editora.i18n.Messages.tr;

/**
 * The changes of the file being edited, worked on where they are: step through them, look at what a change
 * replaced, put the old lines back, or stage just that change — without opening a diff tab.
 *
 * <p>The data is the gutter's: the active file against {@code HEAD}, placed on buffer lines through the
 * buffer's unsaved edits ({@link GitGutterLines}). Revert is an edit to the buffer (undoable, nothing is
 * written); Stage goes through {@link DiffCoordinator#stageFromEditor}, the diff viewer's own path to the
 * index.
 */
final class GitHunkCoordinator {

    private final CoordinatorHost host;
    private final GitCoordinator git;
    private final DiffCoordinator diff;
    private GitHunkPopup popup;

    GitHunkCoordinator(CoordinatorHost host, GitCoordinator git, DiffCoordinator diff) {
        this.host = host;
        this.git = git;
        this.diff = diff;
        git.setGutterSink(this::show);
    }

    /** Puts a diff on a buffer's gutter: the bars, and the hunks behind them for the commands here. */
    private void show(EditorBuffer buffer, GitService.GitDiff d) {
        buffer.setChangeBars(GitChangeBars.cssClassesByLine(d.changes()), d.hunks());
        GitGutterLines gutter = buffer.gitGutter();
        gutter.setHunks(toEditorHunks(d.hunkList()));
        gutter.setOnBarClick(line -> peek(buffer, line));
    }

    /** Git's parsed hunks as the editor keeps them (0-based on-disk lines). Pure. */
    static List<GitHunk> toEditorHunks(List<DiffParser.Hunk> hunks) {
        List<GitHunk> out = new ArrayList<>(hunks.size());
        for (DiffParser.Hunk h : hunks) {
            out.add(new GitHunk(h.markerLine(), h.newCount(), h.removed(), h.added(), h.oldUnterminated()));
        }
        return out;
    }

    // --- navigation -----------------------------------------------------------------------------

    void nextChange() {
        navigate(host.activeBuffer(), true);
    }

    void previousChange() {
        navigate(host.activeBuffer(), false);
    }

    /** Moves the caret to the next/previous change; returns the line it landed on, or -1. */
    private int navigate(EditorBuffer b, boolean forward) {
        if (b == null) {
            return -1;
        }
        int[] marks = b.gitGutter().marks();
        CodeArea area = b.getFocusedArea();
        int target = GitHunkNav.target(marks, area.getCurrentParagraph(), forward);
        if (target == Integer.MIN_VALUE) {
            host.setStatus(tr(b.gitGutter().lost() ? "status.git.hunk.cannotLocate" : "status.git.hunk.noChanges"));
            return -1;
        }
        int index = GitHunkNav.index(target);
        int count = GitHunkNav.count(marks);
        int line = marks[index * 3];
        reveal(b, area, line);
        host.setStatus(
                target >= 0
                        ? tr("status.git.hunk.position", index + 1, count)
                        : tr(
                                forward ? "status.git.hunk.wrappedFirst" : "status.git.hunk.wrappedLast",
                                index + 1,
                                count));
        return line;
    }

    /** Caret to the start of {@code line}, out of any collapsed fold, in the middle of the view. */
    private static void reveal(EditorBuffer b, CodeArea area, int line) {
        int target = Math.clamp(line, 0, area.getParagraphs().size() - 1);
        b.getFoldManager().unfoldContaining(target);
        area.moveTo(target, 0);
        try {
            int visible = Math.max(1, area.lastVisibleParToAllParIndex() - area.firstVisibleParToAllParIndex() + 1);
            area.showParagraphAtTop(Math.max(0, target - visible / 2));
        } catch (RuntimeException notLaidOut) {
            area.requestFollowCaret();
        }
    }

    // --- peek -----------------------------------------------------------------------------------

    void peekChange() {
        EditorBuffer b = host.activeBuffer();
        if (b != null) {
            peek(b, b.getFocusedArea().getCurrentParagraph());
        }
    }

    /** Shows the change marked on {@code line} of {@code b} in a card beside it. */
    private void peek(EditorBuffer b, int line) {
        GitGutterLines gutter = b.gitGutter();
        GitHunk hunk = gutter.hunkAt(line);
        if (hunk == null) {
            reportNoChange(gutter);
            return;
        }
        int[] marks = gutter.marks();
        int index = GitHunkNav.at(marks, line);
        int first = index < 0 ? line : marks[index * 3];
        int last = index < 0 ? line : first + marks[index * 3 + 1] - 1;
        CodeArea area = b.getFocusedArea();
        double[] anchor = anchor(area, first, last);
        if (anchor == null) {
            reveal(b, area, first); // off screen (the command, with the caret scrolled away): bring it in first
            area.layout();
            anchor = anchor(area, first, last);
            if (anchor == null) {
                Bounds whole = area.localToScene(area.getBoundsInLocal());
                anchor = new double[] {whole.getMinX() + 48, whole.getMinY() + 24, whole.getMinY() + 24};
            }
        }
        if (popup == null) {
            popup = new GitHunkPopup(host.overlayHost());
        }
        String kind = tr(
                switch (hunk.kind()) {
                    case ADDED -> "git.hunk.kind.added";
                    case MODIFIED -> "git.hunk.kind.modified";
                    case DELETED -> "git.hunk.kind.deleted";
                });
        String title = index < 0 ? kind : tr("git.hunk.title", kind, index + 1, GitHunkNav.count(marks));
        popup.show(
                hunk,
                title,
                host.settings().getFontFamily(),
                host.settings().getFontSize(),
                anchor,
                new GitHunkPopup.Actions(
                        () -> revert(b, line),
                        () -> stage(b, line),
                        () -> step(b, first, false),
                        () -> step(b, first, true),
                        () -> diff.diffPathVsHead(b.getPath()),
                        () -> host.setStatus(tr("status.git.hunk.copied"))));
    }

    /** Previous/Next from the card: move to that change and show it. */
    private void step(EditorBuffer b, int fromLine, boolean forward) {
        CodeArea area = b.getFocusedArea();
        area.moveTo(Math.clamp(fromLine, 0, area.getParagraphs().size() - 1), 0);
        int line = navigate(b, forward);
        if (line >= 0) {
            Platform.runLater(() -> peek(b, line)); // after the scroll has been laid out
        }
    }

    /**
     * Where the card goes for a change on lines {@code first…last}: {@code {x, belowY, aboveY}} in scene
     * coordinates, from the part of it that is on screen; {@code null} when none of it is.
     */
    private static double[] anchor(CodeArea area, int first, int last) {
        try {
            int top = Math.max(first, area.firstVisibleParToAllParIndex());
            int bottom = Math.min(last, area.lastVisibleParToAllParIndex());
            if (top > bottom) {
                return null;
            }
            Bounds upper = area.getParagraphBoundsOnScreen(top).orElse(null);
            Bounds lower = area.getParagraphBoundsOnScreen(bottom).orElse(null);
            if (upper == null || lower == null) {
                return null;
            }
            Bounds a = area.localToScene(area.screenToLocal(upper));
            Bounds z = area.localToScene(area.screenToLocal(lower));
            return new double[] {a.getMinX() + 48, z.getMaxY(), a.getMinY()};
        } catch (RuntimeException notLaidOut) {
            return null;
        }
    }

    private void reportNoChange(GitGutterLines gutter) {
        host.setStatus(tr(
                gutter.lost()
                        ? "status.git.hunk.cannotLocate"
                        : gutter.hasHunks() ? "status.git.hunk.none" : "status.git.hunk.noChanges"));
    }

    // --- revert ---------------------------------------------------------------------------------

    void revertHunk() {
        EditorBuffer b = host.activeBuffer();
        if (b != null) {
            revert(b, b.getFocusedArea().getCurrentParagraph());
        }
    }

    /**
     * Puts the {@code HEAD} lines of the change on {@code line} back into the buffer, as one undoable edit.
     * Nothing is written to disk. Refuses when the change cannot be placed exactly in the unsaved text.
     */
    private void revert(EditorBuffer b, int line) {
        GitGutterLines gutter = b.gitGutter();
        GitHunk hunk = gutter.hunkAt(line);
        if (hunk == null) {
            reportNoChange(gutter);
            return;
        }
        if (!b.isEditable() || b.isTruncatedLoad()) {
            host.setStatus(tr("status.lsp.readOnly"));
            return;
        }
        GitGutterLines.Span span = gutter.locate(hunk);
        if (span == null) {
            host.setStatus(tr("status.git.hunk.cannotLocate"));
            return;
        }
        CodeArea area = b.getArea();
        int lines = area.getParagraphs().size();
        b.getFoldManager().unfoldContaining(Math.min(span.line(), lines - 1));
        b.getFoldManager().unfoldContaining(Math.min(span.line() + Math.max(0, span.count() - 1), lines - 1));
        HunkRevert.Edit edit =
                HunkRevert.plan(area.getText(), span.line(), span.count(), hunk.oldLines(), hunk.oldUnterminated());
        area.replaceText(edit.start(), edit.end(), edit.text());
        gutter.reverted(hunk);
        CodeArea view = b.getFocusedArea();
        view.moveTo(Math.min(edit.start(), view.getLength()));
        view.requestFollowCaret();
        host.setStatus(tr("status.git.hunk.reverted"));
    }

    // --- stage ----------------------------------------------------------------------------------

    void stageHunk() {
        EditorBuffer b = host.activeBuffer();
        if (b != null) {
            stage(b, b.getFocusedArea().getCurrentParagraph());
        }
    }

    /**
     * Stages the change on {@code line}. Git stages what is on disk, so a buffer with unsaved edits is saved
     * first; the change is then looked up again in a fresh diff of the saved file — the line is the buffer's,
     * which after the save is the file's.
     */
    private void stage(EditorBuffer b, int line) {
        Path file = b.getPath();
        Path root = git.repoRoot();
        String rel = file == null || root == null ? null : GitService.repoRelative(root, file);
        if (rel == null) {
            host.setStatus(tr("status.noGitFile"));
            return;
        }
        if (git.statusFor(file) == GitFileStatus.CONFLICT) {
            // Before the hunk lookup: an unmerged file has no bars to find one on, and "no change here"
            // would be the wrong thing to say about it.
            host.setStatus(tr("status.git.hunk.cannotStageUnmerged"));
            return;
        }
        GitGutterLines gutter = b.gitGutter();
        if (gutter.hunkAt(line) == null) {
            reportNoChange(gutter);
            return;
        }
        if (b.isDirty() && !diff.saveBuffer(b)) {
            return; // the save path has said why
        }
        git.service().diff(root, file.toAbsolutePath(), fresh -> {
            if (b.isDisposed() || b.isDirty()) {
                return; // edited again while git ran: the lines no longer name what was asked for
            }
            DiffParser.Hunk head = hunkOn(fresh.hunkList(), line);
            if (head == null) {
                host.setStatus(tr("status.git.hunk.none"));
                return;
            }
            git.service().unstagedHunks(root, file.toAbsolutePath(), unstaged -> {
                if (unstaged == null) {
                    host.setStatus(tr("status.git.hunk.cannotStageUnmerged"));
                    return;
                }
                List<DiffParser.Hunk> chosen = HunkStaging.touching(unstaged, head.markerLine(), head.newCount());
                if (chosen.isEmpty()) {
                    host.setStatus(tr("status.git.hunk.alreadyStaged"));
                    return;
                }
                diff.stageFromEditor(
                        root, rel, file, (index, working) -> HunkStaging.apply(index, working, chosen), () -> {});
            });
        });
    }

    /** The hunk of a fresh diff marked on 0-based file line {@code line}, or {@code null}. */
    static DiffParser.Hunk hunkOn(List<DiffParser.Hunk> hunks, int line) {
        for (DiffParser.Hunk h : hunks) {
            if (line >= h.markerLine() && line < h.markerLine() + h.markerCount()) {
                return h;
            }
        }
        return null;
    }
}
