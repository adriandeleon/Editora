package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

import javafx.geometry.Bounds;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;

import com.editora.config.ReviewAuthor;
import com.editora.config.ReviewComment;
import com.editora.config.ReviewThread;
import com.editora.config.SharedConfig;
import com.editora.config.TextAnchor;
import com.editora.editor.EditorBuffer;
import com.editora.editor.UiZoom;
import com.editora.review.ReviewAnchors;
import com.editora.review.ReviewKeys;
import com.editora.review.ReviewText;
import com.editora.review.ReviewThreads;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Local code review in a window: the review threads on the open files, shown as a marker after each thread's last
 * line, a card at the line to read and answer a thread, the commands that start, move between and change threads,
 * and the Review Comments tool window. The threads are kept in {@code reviews.json} per repository
 * ({@link SharedConfig#reviewThreads}); see {@code docs/subsystems/code-review.md}.
 *
 * <p>A buffer's markers follow edits as code lenses do. The threads themselves are found again from the text
 * ({@link ReviewAnchors#locate}) when the file opens, when it is saved and when a card opens, and only a save writes
 * where they moved to, so what is stored always matches a text that was on disk.
 */
final class ReviewCoordinator {

    /** What the coordinator needs from its window. */
    interface Ops {
        /** Runs {@code withRoot} with the root of the Git repository {@code file} is in, or reports why there is none. */
        void inRepositoryOf(Path file, Consumer<Path> withRoot);

        /** Opens {@code file} in this window and moves to 0-based {@code line}. */
        void openAndNavigate(Path file, int line);

        @Nullable
        EditorBuffer bufferForPath(Path file);

        void installEmacsKeys(javafx.scene.control.TextInputControl control);
    }

    /** Where a buffer's file is in its repository. */
    record Place(Path root, String repoKey, String path) {}

    private final CoordinatorHost host;
    private final SharedConfig shared;
    private final Ops ops;
    private final ReviewThreads threads = ReviewThreads.now();
    private final Map<EditorBuffer, Place> places = new WeakHashMap<>();
    private @Nullable ReviewThreadCard card;
    private @Nullable ReviewsPanel panel;

    ReviewCoordinator(CoordinatorHost host, SharedConfig shared, Ops ops) {
        this.host = host;
        this.shared = shared;
        this.ops = ops;
    }

    // --- buffers -----------------------------------------------------------------------------------

    /** Wires a new buffer: a click on a review marker opens that thread's card. */
    void wire(EditorBuffer buffer) {
        buffer.setReviewCommentHandler((b, suggest) -> {
            if (suggest) {
                suggestChange();
            } else {
                addComment();
            }
        });
        buffer.setReviewLensHandler((line, lenses) -> {
            if (!lenses.isEmpty()) {
                showThread(buffer, lenses.get(0).threadId());
            }
        });
    }

    /**
     * Shows the threads of a file that was just opened. Nothing is asked of Git: the file is matched against the
     * repositories that have threads, off the FX thread (the paths are made canonical, which reads the disk).
     */
    void restore(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null || shared.allReviewThreads().isEmpty()) {
            return;
        }
        List<String> repoKeys = new ArrayList<>(shared.allReviewThreads().keySet());
        com.editora.perf.OffFx.then("review-place", () -> placeAmong(file, repoKeys), found -> {
            Place place = found.value();
            if (place != null && file.equals(buffer.getPath())) {
                places.put(buffer, place);
                showMarkers(buffer, false);
            }
        });
    }

    /** The saved file's threads follow its text: they are found again, and where they moved to is written. */
    void saved(EditorBuffer buffer) {
        Place place = places.get(buffer);
        if (place != null) {
            showMarkers(buffer, true);
        }
    }

    /** {@code file}'s place in the repository among {@code repoKeys} that holds it (the deepest), or null. */
    static @Nullable Place placeAmong(Path file, List<String> repoKeys) {
        Place best = null;
        for (String key : repoKeys) {
            Path root = Path.of(key);
            String rel = ReviewKeys.relativePath(root, file);
            if (rel != null && (best == null || key.length() > best.repoKey().length())) {
                best = new Place(root, key, rel);
            }
        }
        return best;
    }

    /**
     * Draws the open threads of {@code buffer}'s file after their lines, where their text is now. With {@code
     * persist}, threads that moved are written where they are.
     */
    void showMarkers(EditorBuffer buffer, boolean persist) {
        Place place = places.get(buffer);
        if (place == null) {
            buffer.setReviewLenses(null);
            return;
        }
        List<ReviewThread> all = shared.reviewThreads(place.repoKey());
        String text = buffer.getContent();
        if (persist) {
            List<ReviewThread> moved = ReviewThreads.reanchored(all, place.path(), text);
            if (!moved.equals(all)) {
                shared.setReviewThreads(place.repoKey(), moved);
                all = moved;
                activeBufferChanged();
            }
        }
        Map<Integer, List<ReviewThread>> byLine = new java.util.TreeMap<>();
        for (ReviewThread t : ReviewThreads.openOn(all, place.path())) {
            if (t.side() != ReviewThread.Side.NEW) {
                continue;
            }
            ReviewAnchors.Located at = ReviewAnchors.locate(t, text);
            byLine.computeIfAbsent(at.endLine(), k -> new ArrayList<>()).add(t);
        }
        List<EditorBuffer.ReviewLens> lenses = new ArrayList<>();
        byLine.forEach((line, onLine) -> {
            List<String> labels = ReviewText.labels(onLine);
            List<ReviewThread> sorted = onLine.stream()
                    .sorted(java.util.Comparator.comparingInt(t -> t.severity() == null
                            ? Integer.MAX_VALUE
                            : t.severity().ordinal()))
                    .toList();
            for (int i = 0; i < sorted.size(); i++) {
                lenses.add(new EditorBuffer.ReviewLens(
                        line, labels.get(i), sorted.get(i).id()));
            }
        });
        buffer.setReviewLenses(lenses);
    }

    /** Every open buffer of the repository {@code repoKey} draws its markers again (its threads changed). */
    private void refresh(String repoKey) {
        host.forEachBuffer(b -> {
            Place p = places.get(b);
            if (p != null && p.repoKey().equals(repoKey)) {
                showMarkers(b, false);
            }
        });
        activeBufferChanged(); // the tool window lists them
    }

    // --- commands ----------------------------------------------------------------------------------

    /** {@code review.addComment}: a comment on the selected lines, or the caret's line. */
    void addComment() {
        startThread(false);
    }

    /** {@code review.suggestChange}: a comment that starts with the selected lines as a suggestion to edit. */
    void suggestChange() {
        startThread(true);
    }

    private void startThread(boolean suggest) {
        EditorBuffer buffer = host.activeBuffer();
        Path file = buffer == null ? null : buffer.getPath();
        if (buffer == null || file == null) {
            host.setStatus(tr("status.review.needsFile"));
            return;
        }
        CodeArea area = buffer.getFocusedArea();
        int first = area.offsetToPosition(
                        area.getSelection().getStart(), org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                .getMajor();
        int end = area.getSelection().getLength() == 0
                ? area.getSelection().getEnd()
                : Math.max(area.getSelection().getStart(), area.getSelection().getEnd() - 1);
        int last = area.offsetToPosition(end, org.fxmisc.richtext.model.TwoDimensional.Bias.Backward)
                .getMajor();
        String text = buffer.getContent();
        TextAnchor anchor = ReviewAnchors.capture(text, first, last);
        String initial = suggest ? ReviewText.suggestionTemplate(anchor.selectedText()) : "";
        ops.inRepositoryOf(file, root -> {
            String rel = ReviewKeys.relativePath(root, file);
            if (rel == null) {
                host.setStatus(tr("status.notARepo"));
                return;
            }
            Place place = new Place(root, ReviewKeys.repoKey(root), rel);
            commentDialog(tr("review.dialog.newTitle", lineRange(anchor.line(), anchor.endLine())), initial, body -> {
                ReviewText.Parsed parsed = ReviewText.parse(body);
                List<ReviewThread> next = threads.start(
                        shared.reviewThreads(place.repoKey()),
                        ReviewThreads.Start.onFile(place.path(), anchor),
                        ReviewAuthor.user(),
                        parsed.body(),
                        parsed.suggestion());
                shared.setReviewThreads(place.repoKey(), next);
                places.put(buffer, place);
                refresh(place.repoKey());
                host.setStatus(tr("status.review.added"));
            });
        });
    }

    /** {@code review.showThread}: the card of the thread on the caret's line (or the nearest marker below it). */
    void showThreadAtCaret() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            return;
        }
        ReviewThread t = threadAtCaret(buffer);
        if (t == null) {
            host.setStatus(tr("status.review.none"));
            return;
        }
        showThread(buffer, t.id());
    }

    /** {@code review.next} / {@code review.previous}: the caret to the next (previous) thread's marker. */
    void jump(boolean forward) {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            return;
        }
        java.util.NavigableSet<Integer> lines = buffer.reviewLensLines();
        if (lines.isEmpty()) {
            host.setStatus(tr("status.review.none"));
            return;
        }
        int caret = buffer.getFocusedArea().getCurrentParagraph();
        Integer to = forward ? lines.higher(caret) : lines.lower(caret);
        if (to == null) {
            to = forward ? lines.first() : lines.last(); // wraps around, as next/previous change do
        }
        CodeArea area = buffer.getFocusedArea();
        buffer.getFoldManager().unfoldContaining(to);
        area.moveTo(to, 0);
        buffer.jumps().reveal(area, to, false);
        host.setStatus(tr("status.review.at", lines.headSet(to + 1).size(), lines.size()));
    }

    /** The thread whose lines hold the caret, else the first one marked on the caret's line. */
    private @Nullable ReviewThread threadAtCaret(EditorBuffer buffer) {
        Place place = places.get(buffer);
        if (place == null) {
            return null;
        }
        int caret = buffer.getFocusedArea().getCurrentParagraph();
        String text = buffer.getContent();
        ReviewThread onLine = null;
        for (ReviewThread t : ReviewThreads.openOn(shared.reviewThreads(place.repoKey()), place.path())) {
            ReviewAnchors.Located at = ReviewAnchors.locate(t, text);
            if (caret >= at.startLine() && caret <= at.endLine()) {
                return t;
            }
        }
        List<EditorBuffer.ReviewLens> lenses = buffer.reviewLensesOn(caret);
        if (!lenses.isEmpty()) {
            onLine = find(place.repoKey(), lenses.get(0).threadId());
        }
        return onLine;
    }

    private @Nullable ReviewThread find(String repoKey, String threadId) {
        for (ReviewThread t : shared.reviewThreads(repoKey)) {
            if (t.id().equals(threadId)) {
                return t;
            }
        }
        return null;
    }

    // --- the card ----------------------------------------------------------------------------------

    /** Opens thread {@code threadId}'s card under its lines in {@code buffer}, scrolling them into view first. */
    void showThread(EditorBuffer buffer, String threadId) {
        showThread(buffer, threadId, true);
    }

    /**
     * As {@link #showThread(EditorBuffer, String)}; with {@code scroll}, lines that are not on screen are scrolled to
     * and the card opens once they are laid out (one pulse later). Without it, a thread still off screen says so.
     */
    private void showThread(EditorBuffer buffer, String threadId, boolean scroll) {
        Place place = places.get(buffer);
        ReviewThread t = place == null ? null : find(place.repoKey(), threadId);
        if (place == null || t == null) {
            return;
        }
        ReviewAnchors.Located at = ReviewAnchors.locate(t, buffer.getContent());
        CodeArea area = buffer.getFocusedArea();
        double[] anchor = anchor(area, at.startLine(), at.endLine());
        if (anchor == null) {
            if (!scroll) {
                host.setStatus(tr("status.review.scrolledTo"));
                return;
            }
            buffer.getFoldManager().unfoldContaining(at.startLine());
            area.moveTo(at.startLine(), 0);
            buffer.jumps().reveal(area, at.startLine(), false);
            area.layout();
            javafx.application.Platform.runLater(() -> showThread(buffer, threadId, false));
            return;
        }
        if (card == null) {
            card = new ReviewThreadCard(host.overlayHost(), ops::installEmacsKeys);
        }
        card.show(t, place.path(), at.outdated(), at.startLine(), at.endLine(), anchor, actions(buffer, place));
    }

    private ReviewThreadCard.Actions actions(EditorBuffer buffer, Place place) {
        String repoKey = place.repoKey();
        return new ReviewThreadCard.Actions() {
            @Override
            public void reply(ReviewThread t, String body) {
                ReviewText.Parsed parsed = ReviewText.parse(body);
                change(
                        repoKey,
                        threads.reply(
                                shared.reviewThreads(repoKey),
                                t.id(),
                                ReviewAuthor.user(),
                                parsed.body(),
                                parsed.suggestion()));
                host.setStatus(tr("status.review.replied"));
            }

            @Override
            public void resolve(ReviewThread t) {
                change(repoKey, threads.resolve(shared.reviewThreads(repoKey), t.id(), null));
                host.setStatus(tr("status.review.resolved"));
            }

            @Override
            public void reopen(ReviewThread t) {
                change(repoKey, threads.reopen(shared.reviewThreads(repoKey), t.id()));
            }

            @Override
            public void dismiss(ReviewThread t) {
                host.promptText(tr("review.dismiss.title"), tr("review.dismiss.label"), "", reason -> {
                    change(repoKey, threads.dismiss(shared.reviewThreads(repoKey), t.id(), reason.strip()));
                    host.setStatus(tr("status.review.dismissed"));
                });
            }

            @Override
            public void edit(ReviewThread t, ReviewComment c) {
                commentDialog(tr("review.dialog.editTitle"), ReviewText.editable(c), body -> {
                    ReviewText.Parsed parsed = ReviewText.parse(body);
                    change(
                            repoKey,
                            threads.edit(
                                    shared.reviewThreads(repoKey), t.id(), c.id(), parsed.body(), parsed.suggestion()));
                });
            }

            @Override
            public void delete(ReviewThread t) {
                if (Confirm.ask(host.window(), deleteQuestion())) {
                    change(repoKey, threads.delete(shared.reviewThreads(repoKey), t.id()));
                    host.setStatus(tr("status.review.deleted"));
                }
            }

            @Override
            public void applySuggestion(ReviewThread t, String suggestion) {
                ReviewCoordinator.this.applySuggestion(buffer, repoKey, t, suggestion);
            }
        };
    }

    /** The question before a thread and all its comments are deleted. */
    static Confirm.Question deleteQuestion() {
        return new Confirm.Question(
                tr("review.delete.title"), null, tr("review.delete.content"), tr("confirm.action.delete"));
    }

    private void change(String repoKey, List<ReviewThread> next) {
        shared.setReviewThreads(repoKey, next);
        refresh(repoKey);
    }

    /**
     * Replaces the thread's lines with {@code suggestion} in the buffer, as one undoable edit (nothing is saved),
     * and resolves the thread. Refused, with the reason, when its lines have changed since it was written.
     */
    void applySuggestion(EditorBuffer buffer, String repoKey, ReviewThread t, String suggestion) {
        if (buffer.isReadOnly()) {
            host.setStatus(tr("status.review.readOnly"));
            return;
        }
        String text = buffer.getContent();
        ReviewAnchors.Located at = ReviewAnchors.locate(t, text);
        String applied = ReviewAnchors.applySuggestion(t, text, suggestion);
        if (applied == null) {
            host.setStatus(tr("status.review.outdated"));
            return;
        }
        CodeArea area = buffer.getArea();
        int from = area.getAbsolutePosition(at.startLine(), 0);
        int to = area.getAbsolutePosition(at.endLine(), area.getParagraphLength(at.endLine()));
        area.replaceText(from, to, suggestion);
        change(repoKey, threads.resolve(shared.reviewThreads(repoKey), t.id(), tr("review.note.applied")));
        host.setStatus(tr("status.review.applied"));
    }

    // --- the tool window ---------------------------------------------------------------------------

    /** The Review Comments tool window's content, built on first use. */
    ReviewsPanel panel() {
        if (panel == null) {
            panel = new ReviewsPanel(
                    () -> {
                        Place p = activePlace();
                        return new ReviewsPanel.Scope(p, p == null ? List.of() : threadsOf(p.repoKey()));
                    },
                    new ReviewsPanel.Actions() {
                        @Override
                        public void open(Place place, ReviewThread t) {
                            ReviewCoordinator.this.open(place, t);
                        }

                        @Override
                        public void resolve(Place place, ReviewThread t, boolean resolved) {
                            ReviewCoordinator.this.resolve(place, t, resolved);
                        }

                        @Override
                        public void delete(Place place, ReviewThread t) {
                            ReviewCoordinator.this.delete(place, t);
                        }
                    });
        }
        return panel;
    }

    /** The tool window shows the threads of the active file's repository: it follows the active tab. */
    void activeBufferChanged() {
        if (panel != null) {
            panel.refresh();
        }
    }

    /** Whether the panel was built (a closed window's panel is not refreshed). */
    boolean panelBuilt() {
        return panel != null;
    }

    /** Every thread of the active buffer's repository (none when it is in none), for the tool window. */
    @Nullable
    Place activePlace() {
        EditorBuffer b = host.activeBuffer();
        return b == null ? null : places.get(b);
    }

    /** The threads of {@code repoKey}. */
    List<ReviewThread> threadsOf(String repoKey) {
        return shared.reviewThreads(repoKey);
    }

    /** Opens thread {@code t} of {@code place}'s repository: its file at its lines, then its card. */
    void open(Place place, ReviewThread t) {
        Path file = ReviewKeys.resolve(place.root(), t.path());
        EditorBuffer open = ops.bufferForPath(file);
        int line = open == null
                ? t.anchor().line()
                : ReviewAnchors.locate(t, open.getContent()).startLine();
        ops.openAndNavigate(file, line);
        EditorBuffer now = ops.bufferForPath(file);
        if (now != null) {
            places.putIfAbsent(now, new Place(place.root(), place.repoKey(), t.path()));
            javafx.application.Platform.runLater(() -> showThread(now, t.id())); // after the jump has laid out
        }
    }

    /** Changes a thread from the tool window: resolve, reopen, delete. */
    void resolve(Place place, ReviewThread t, boolean resolved) {
        change(
                place.repoKey(),
                resolved
                        ? threads.resolve(shared.reviewThreads(place.repoKey()), t.id(), null)
                        : threads.reopen(shared.reviewThreads(place.repoKey()), t.id()));
    }

    void delete(Place place, ReviewThread t) {
        if (Confirm.ask(host.window(), deleteQuestion())) {
            change(place.repoKey(), threads.delete(shared.reviewThreads(place.repoKey()), t.id()));
        }
    }

    // --- helpers -----------------------------------------------------------------------------------

    private void commentDialog(String title, String initial, Consumer<String> onAccept) {
        TextArea editor = new TextArea(initial);
        editor.setWrapText(true);
        editor.setPrefRowCount(7);
        editor.setPrefColumnCount(48);
        ops.installEmacsKeys(editor);
        Label prompt = new Label(tr("review.dialog.prompt"));
        Label hint = new Label(tr("review.dialog.hint"));
        hint.getStyleClass().add("note-dialog-hint");
        hint.setWrapText(true);
        VBox body = UiZoom.spacing(new VBox(prompt, editor, hint), 6);
        OverlayInput.show(
                host.overlayHost(),
                title,
                body,
                editor,
                tr("review.dialog.save"),
                null,
                () -> {
                    String text = editor.getText().strip();
                    if (!text.isBlank()) {
                        onAccept.accept(text);
                    }
                },
                null,
                true);
    }

    /** "line 4" or "lines 4–7" (1-based), for a dialog's title. */
    static String lineRange(int first, int last) {
        return first == last ? tr("review.line", first + 1) : tr("review.lines", first + 1, last + 1);
    }

    /**
     * Where the card goes for lines {@code first…last}: {@code {x, belowY, aboveY}} in scene coordinates, from the
     * part of them that is on screen; {@code null} when none of it is (as {@code GitHunkCoordinator} places its card).
     */
    private static double @Nullable [] anchor(CodeArea area, int first, int last) {
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
}
