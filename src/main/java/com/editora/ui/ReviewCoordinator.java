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

        /** Whether the AI Agent panel can take a prompt (AI and the agent on). */
        boolean agentEnabled();

        /** Opens the AI Agent panel and sends {@code prompt} as a turn. */
        void sendToAgent(String prompt);

        /** Puts {@code text} on the clipboard. */
        void copyText(String text);

        /** The active tab's diff pane, or null when the active tab is not a diff. */
        @Nullable
        DiffViewerPane activeDiffPane();
    }

    /** Where a buffer's file is in its repository. */
    record Place(Path root, String repoKey, String path) {}

    private final CoordinatorHost host;
    private final SharedConfig shared;
    private final Ops ops;
    private final ReviewThreads threads = ReviewThreads.now();
    private final ReviewStorage storage;
    private final Map<EditorBuffer, Place> places = new WeakHashMap<>();
    /** The diffs whose local side is a file, by pane: the file, and its place once known. */
    private final Map<DiffViewerPane, Path> diffTargets = new WeakHashMap<>();

    private final Map<DiffViewerPane, Place> diffPlaces = new WeakHashMap<>();
    private @Nullable ReviewThreadCard card;
    private @Nullable ReviewsPanel panel;

    ReviewCoordinator(CoordinatorHost host, SharedConfig shared, Ops ops) {
        this.host = host;
        this.shared = shared;
        this.ops = ops;
        this.storage = new ReviewStorage(shared, () -> host.settings().isReviewsInProject());
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
        if (file == null) {
            return;
        }
        findPlace(file, place -> {
            if (file.equals(buffer.getPath())) {
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

    /** What a project file held, read off the FX thread. */
    private record Found(Place place, @Nullable List<ReviewThread> projectThreads) {}

    /**
     * Finds {@code file}'s place among the repositories with threads, off the FX thread, and runs {@code then} with
     * it on the FX thread (not at all when it is in none). With threads kept in the project, that is the nearest
     * repository above it with a {@code .editora/reviews.json}, which is read too.
     */
    private void findPlace(Path file, Consumer<Place> then) {
        boolean project = storage.inProject();
        List<String> repoKeys = storage.repoKeys();
        if (!project && repoKeys.isEmpty()) {
            return;
        }
        com.editora.perf.OffFx.then(
                "review-place",
                () -> {
                    if (!project) {
                        Place p = placeAmong(file, repoKeys);
                        return p == null ? null : new Found(p, null);
                    }
                    Path root = com.editora.config.ProjectReviewFile.rootAbove(file);
                    String rel = root == null ? null : ReviewKeys.relativePath(root, file);
                    if (root == null || rel == null) {
                        return null;
                    }
                    Place p = new Place(root, ReviewKeys.repoKey(root), rel);
                    return new Found(p, com.editora.config.ProjectReviewFile.read(root));
                },
                found -> {
                    Found f = found.value();
                    if (f == null) {
                        return;
                    }
                    if (f.projectThreads() != null) {
                        storage.loaded(f.place().repoKey(), f.place().root(), f.projectThreads());
                    }
                    then.accept(f.place());
                });
    }

    /** Runs {@code then} once {@code place}'s threads are in memory (a project file is read off the FX thread). */
    private void whenLoaded(Place place, Runnable then) {
        if (storage.isLoaded(place.repoKey())) {
            then.run();
            return;
        }
        com.editora.perf.OffFx.then(
                "review-load", () -> com.editora.config.ProjectReviewFile.read(place.root()), read -> {
                    List<ReviewThread> loaded = read.value();
                    storage.loaded(place.repoKey(), place.root(), loaded == null ? List.of() : loaded);
                    then.run();
                });
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
        List<ReviewThread> all = storage.threads(place.repoKey());
        String text = buffer.getContent();
        if (persist) {
            List<ReviewThread> moved = ReviewThreads.reanchored(all, place.path(), text);
            if (!moved.equals(all)) {
                storage.set(place.repoKey(), place.root(), moved);
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

    // --- diffs ------------------------------------------------------------------------------------

    /**
     * A diff whose local side is {@code target}: its threads are marked in that side's gutter, a mark opens the
     * thread in the editor, and Add Review Comment there comments on the file's lines.
     */
    void prepareDiff(DiffViewerPane pane, Path target) {
        diffTargets.put(pane, target);
        pane.setReviewHook(new DiffViewerPane.ReviewHook() {
            @Override
            public java.util.Set<Integer> commentedLines() {
                return diffLines(pane);
            }

            @Override
            public void open(int line) {
                Place place = diffPlaces.get(pane);
                String text = pane.editableBaselineText();
                if (place == null || text == null) {
                    return;
                }
                for (ReviewThread t : ReviewThreads.openOn(storage.threads(place.repoKey()), place.path())) {
                    if (t.side() == ReviewThread.Side.NEW
                            && ReviewAnchors.locate(t, text).endLine() == line) {
                        ReviewCoordinator.this.open(place, t);
                        return;
                    }
                }
            }
        });
        findPlace(target, place -> {
            diffPlaces.put(pane, place);
            pane.refreshReviewMarks();
        });
    }

    /** The 0-based lines of a diff's local file after which an open thread ends. */
    private java.util.Set<Integer> diffLines(DiffViewerPane pane) {
        Place place = diffPlaces.get(pane);
        String text = pane.editableBaselineText();
        if (place == null || text == null) {
            return java.util.Set.of();
        }
        java.util.Set<Integer> lines = new java.util.HashSet<>();
        for (ReviewThread t : ReviewThreads.openOn(storage.threads(place.repoKey()), place.path())) {
            if (t.side() == ReviewThread.Side.NEW) {
                lines.add(ReviewAnchors.locate(t, text).endLine());
            }
        }
        return lines;
    }

    /** Add Review Comment in a diff: on the local file's lines the selection covers on its side. */
    private void startDiffThread(DiffViewerPane pane, Path target, boolean suggest) {
        int[] lines = pane.localSelectionLines();
        String text = pane.editableBaselineText();
        if (lines == null || text == null) {
            host.setStatus(tr("status.review.diffSide"));
            return;
        }
        TextAnchor anchor = ReviewAnchors.capture(text, lines[0], lines[1]);
        ops.inRepositoryOf(target, root -> {
            String rel = ReviewKeys.relativePath(root, target);
            if (rel == null) {
                host.setStatus(tr("status.notARepo"));
                return;
            }
            Place place = new Place(root, ReviewKeys.repoKey(root), rel);
            diffPlaces.put(pane, place);
            EditorBuffer open = ops.bufferForPath(target);
            if (open != null) {
                places.put(open, place);
            }
            whenLoaded(place, () -> newThreadDialog(place, anchor, suggest));
        });
    }

    /** Every open buffer of the repository {@code repoKey} draws its markers again (its threads changed). */
    private void refresh(String repoKey) {
        diffPlaces.forEach((pane, p) -> {
            if (p.repoKey().equals(repoKey)) {
                pane.refreshReviewMarks();
            }
        });
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
        if (buffer == null) {
            DiffViewerPane pane = ops.activeDiffPane();
            Path target = pane == null ? null : diffTargets.get(pane);
            if (pane != null && target != null) {
                startDiffThread(pane, target, suggest);
                return;
            }
        }
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
        ops.inRepositoryOf(file, root -> {
            String rel = ReviewKeys.relativePath(root, file);
            if (rel == null) {
                host.setStatus(tr("status.notARepo"));
                return;
            }
            Place place = new Place(root, ReviewKeys.repoKey(root), rel);
            places.put(buffer, place);
            whenLoaded(place, () -> newThreadDialog(place, anchor, suggest));
        });
    }

    /** The dialog that starts a thread on {@code anchor}'s lines of {@code place}'s file. */
    private void newThreadDialog(Place place, TextAnchor anchor, boolean suggest) {
        String initial = suggest ? ReviewText.suggestionTemplate(anchor.selectedText()) : "";
        commentDialog(tr("review.dialog.newTitle", lineRange(anchor.line(), anchor.endLine())), initial, body -> {
            ReviewText.Parsed parsed = ReviewText.parse(body);
            List<ReviewThread> next = threads.start(
                    storage.threads(place.repoKey()),
                    ReviewThreads.Start.onFile(place.path(), anchor),
                    ReviewAuthor.user(),
                    parsed.body(),
                    parsed.suggestion());
            storage.set(place.repoKey(), place.root(), next);
            refresh(place.repoKey());
            host.setStatus(tr("status.review.added"));
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
        for (ReviewThread t : ReviewThreads.openOn(storage.threads(place.repoKey()), place.path())) {
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
        for (ReviewThread t : storage.threads(repoKey)) {
            if (t.id().equals(threadId)) {
                return t;
            }
        }
        return null;
    }

    // --- the card ----------------------------------------------------------------------------------

    /** Opens thread {@code threadId}'s card under its lines in {@code buffer}, scrolling them into view first. */
    void showThread(EditorBuffer buffer, String threadId) {
        showThread(buffer, threadId, SCROLL_PULSES);
    }

    /** How many pulses a scrolled-to thread's lines get to be laid out before the card gives up. */
    private static final int SCROLL_PULSES = 5;

    /**
     * As {@link #showThread(EditorBuffer, String)}: lines that are not on screen are scrolled to, and the card opens
     * once they are laid out, looked for again on each of the next {@code pulses} pulses. After that, it says so.
     */
    private void showThread(EditorBuffer buffer, String threadId, int pulses) {
        Place place = places.get(buffer);
        ReviewThread t = place == null ? null : find(place.repoKey(), threadId);
        if (place == null || t == null) {
            return;
        }
        ReviewAnchors.Located at = ReviewAnchors.locate(t, buffer.getContent());
        CodeArea area = buffer.getFocusedArea();
        double[] anchor = lineAnchor.find(area, at.startLine(), at.endLine());
        if (anchor == null && pulses <= 0) {
            // The lines never got a place on screen (a window still settling): the card opens at the editor's top
            // rather than not at all, which left a click on a thread with nothing to show for it.
            anchor = areaAnchor(area);
            if (anchor == null) {
                host.setStatus(tr("status.review.scrolledTo"));
                return;
            }
        }
        if (anchor == null) {
            if (pulses == SCROLL_PULSES) {
                buffer.getFoldManager().unfoldContaining(at.startLine());
                area.moveTo(at.startLine(), 0);
                buffer.jumps().reveal(area, at.startLine(), false);
            }
            area.layout();
            javafx.application.Platform.runLater(() -> showThread(buffer, threadId, pulses - 1));
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
                        place,
                        threads.reply(
                                storage.threads(repoKey),
                                t.id(),
                                ReviewAuthor.user(),
                                parsed.body(),
                                parsed.suggestion()));
                host.setStatus(tr("status.review.replied"));
            }

            @Override
            public void resolve(ReviewThread t) {
                change(place, threads.resolve(storage.threads(repoKey), t.id(), null));
                host.setStatus(tr("status.review.resolved"));
            }

            @Override
            public void reopen(ReviewThread t) {
                change(place, threads.reopen(storage.threads(repoKey), t.id()));
            }

            @Override
            public void dismiss(ReviewThread t) {
                host.promptText(tr("review.dismiss.title"), tr("review.dismiss.label"), "", reason -> {
                    change(place, threads.dismiss(storage.threads(repoKey), t.id(), reason.strip()));
                    host.setStatus(tr("status.review.dismissed"));
                });
            }

            @Override
            public void edit(ReviewThread t, ReviewComment c) {
                commentDialog(tr("review.dialog.editTitle"), ReviewText.editable(c), body -> {
                    ReviewText.Parsed parsed = ReviewText.parse(body);
                    change(
                            place,
                            threads.edit(storage.threads(repoKey), t.id(), c.id(), parsed.body(), parsed.suggestion()));
                });
            }

            @Override
            public void delete(ReviewThread t) {
                if (Confirm.ask(host.window(), deleteQuestion())) {
                    change(place, threads.delete(storage.threads(repoKey), t.id()));
                    host.setStatus(tr("status.review.deleted"));
                }
            }

            @Override
            public void fixWithAgent(ReviewThread t) {
                ReviewCoordinator.this.fixWithAgent(place, t);
            }

            @Override
            public void applySuggestion(ReviewThread t, String suggestion) {
                ReviewCoordinator.this.applySuggestion(buffer, place, t, suggestion);
            }
        };
    }

    /** The question before a thread and all its comments are deleted. */
    static Confirm.Question deleteQuestion() {
        return new Confirm.Question(
                tr("review.delete.title"), null, tr("review.delete.content"), tr("confirm.action.delete"));
    }

    private void change(Place place, List<ReviewThread> next) {
        storage.set(place.repoKey(), place.root(), next);
        refresh(place.repoKey());
    }

    /**
     * Replaces the thread's lines with {@code suggestion} in the buffer, as one undoable edit (nothing is saved),
     * and resolves the thread. Refused, with the reason, when its lines have changed since it was written.
     */
    void applySuggestion(EditorBuffer buffer, Place place, ReviewThread t, String suggestion) {
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
        change(place, threads.resolve(storage.threads(place.repoKey()), t.id(), tr("review.note.applied")));
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

                        @Override
                        public void fixWithAgent(Place place, ReviewThread t) {
                            ReviewCoordinator.this.fixWithAgent(place, t);
                        }

                        @Override
                        public void copyFixPrompt(Place place, ReviewThread t) {
                            ReviewCoordinator.this.copyFixPrompt(place, t);
                        }

                        @Override
                        public void dismiss(Place place, ReviewThread t) {
                            ReviewCoordinator.this.dismiss(place, t);
                        }
                    });
        }
        return panel;
    }

    /**
     * Where threads are kept changed (the {@code reviewsInProject} setting): the open files and diffs find their
     * threads again in the other store.
     */
    void storeChanged() {
        places.clear();
        diffPlaces.clear();
        host.forEachBuffer(b -> {
            b.setReviewLenses(null);
            restore(b);
        });
        diffTargets.forEach((pane, target) -> {
            pane.refreshReviewMarks();
            findPlace(target, place -> {
                diffPlaces.put(pane, place);
                pane.refreshReviewMarks();
            });
        });
        activeBufferChanged();
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
        return storage.threads(repoKey);
    }

    /** {@code buffer}'s place in its repository, once known. */
    @Nullable
    Place placeOf(EditorBuffer buffer) {
        return places.get(buffer);
    }

    /** TEST SEAM — waits for the queued project-file writes. */
    void awaitWritesForTest() throws Exception {
        storage.awaitWritesForTest();
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
                place,
                resolved
                        ? threads.resolve(storage.threads(place.repoKey()), t.id(), null)
                        : threads.reopen(storage.threads(place.repoKey()), t.id()));
    }

    void delete(Place place, ReviewThread t) {
        if (Confirm.ask(host.window(), deleteQuestion())) {
            change(place, threads.delete(storage.threads(place.repoKey()), t.id()));
        }
    }

    // --- asking an agent to fix a thread -------------------------------------------------------------

    /** {@code review.fixWithAgent} / {@code review.copyFixPrompt}: for the thread at the caret. */
    void fixAtCaret(boolean copy) {
        EditorBuffer buffer = host.activeBuffer();
        Place place = buffer == null ? null : places.get(buffer);
        ReviewThread t = buffer == null ? null : threadAtCaret(buffer);
        if (place == null || t == null) {
            host.setStatus(tr("status.review.none"));
            return;
        }
        if (copy) {
            copyFixPrompt(place, t);
        } else {
            fixWithAgent(place, t);
        }
    }

    /** Sends the AI Agent panel what to fix for thread {@code t}; you resolve the thread once it is done. */
    void fixWithAgent(Place place, ReviewThread t) {
        if (!ops.agentEnabled()) {
            host.setStatus(tr("status.review.agentOff"));
            return;
        }
        withFixPrompt(place, t, prompt -> {
            ops.sendToAgent(prompt);
            host.setStatus(tr("status.review.sentToAgent"));
        });
    }

    /** Copies what an agent would be asked, for one in a terminal (Claude Code) to paste. */
    void copyFixPrompt(Place place, ReviewThread t) {
        withFixPrompt(place, t, prompt -> {
            ops.copyText(prompt);
            host.setStatus(tr("status.review.promptCopied"));
        });
    }

    /** The fix prompt for {@code t}, built from its file as it is now (read off the FX thread when not open). */
    private void withFixPrompt(Place place, ReviewThread t, Consumer<String> then) {
        Path file = ReviewKeys.resolve(place.root(), t.path());
        EditorBuffer open = ops.bufferForPath(file);
        if (open != null) {
            then.accept(fixPrompt(file, t, open.getContent()));
            return;
        }
        com.editora.perf.OffFx.then(
                "review-fix-text",
                () -> {
                    try {
                        return java.nio.file.Files.readString(file);
                    } catch (java.io.IOException | java.io.UncheckedIOException e) {
                        return "";
                    }
                },
                read -> {
                    String text = read.value();
                    then.accept(fixPrompt(file, t, text == null ? "" : text));
                });
    }

    /** {@code t}'s fix prompt against {@code text}: its lines where they are now. */
    static String fixPrompt(Path file, ReviewThread t, String text) {
        ReviewAnchors.Located at = ReviewAnchors.locate(t, text);
        String[] lines = text.split("\n", -1);
        StringBuilder shown = new StringBuilder();
        for (int i = at.startLine(); i <= at.endLine() && i < lines.length; i++) {
            if (!shown.isEmpty()) {
                shown.append('\n');
            }
            shown.append(lines[i].endsWith("\r") ? lines[i].substring(0, lines[i].length() - 1) : lines[i]);
        }
        String lineText = at.outdated() ? t.anchor().selectedText() : shown.toString();
        return ReviewText.fixPrompt(file.toString(), at.startLine() + 1, at.endLine() + 1, t, lineText);
    }

    /** Dismisses {@code t} from the tool window, asking the reason first. */
    void dismiss(Place place, ReviewThread t) {
        host.promptText(tr("review.dismiss.title"), tr("review.dismiss.label"), "", reason -> {
            change(place, threads.dismiss(storage.threads(place.repoKey()), t.id(), reason.strip()));
            host.setStatus(tr("status.review.dismissed"));
        });
    }

    /**
     * Why an agent may not add a finding on {@code anchor}'s lines of {@code place}'s file: a finding you dismissed on
     * the same lines (the same text), with your reason; null when there is none. Dismissed is meant to stay dismissed.
     */
    @Nullable
    String dismissedOn(Place place, TextAnchor anchor) {
        for (ReviewThread t : storage.threads(place.repoKey())) {
            if (t.state() == ReviewThread.State.DISMISSED
                    && t.severity() != null
                    && t.path().equals(place.path())
                    && t.anchor().selectedText().equals(anchor.selectedText())) {
                String reason = t.stateNote() == null || t.stateNote().isBlank() ? "no reason given" : t.stateNote();
                return "The user dismissed a finding on these lines (" + reason + "); do not raise it again.";
            }
        }
        return null;
    }

    // --- agents (MCP) --------------------------------------------------------------------------------

    /** Whether {@code place}'s threads are in memory (a project file is read by the caller, off the FX thread). */
    boolean isLoaded(Place place) {
        return storage.isLoaded(place.repoKey());
    }

    /** {@code place}'s project file as the caller read it off the FX thread. */
    void loaded(Place place, List<ReviewThread> read) {
        storage.loaded(place.repoKey(), place.root(), read);
    }

    /** Whether threads are kept in the project (the caller reads a project file before {@link #loaded}). */
    boolean keptInProject() {
        return storage.inProject();
    }

    /** A thread an agent starts: the open buffers of its file show it at once. Returns the new thread's id. */
    String agentStart(
            Place place,
            TextAnchor anchor,
            String body,
            @Nullable String suggestion,
            ReviewThread.@Nullable Severity severity,
            @Nullable String category,
            String agent) {
        ReviewThreads.Start what =
                new ReviewThreads.Start(place.path(), ReviewThread.Side.NEW, null, anchor, severity, category);
        List<ReviewThread> next =
                threads.start(storage.threads(place.repoKey()), what, ReviewAuthor.agent(agent), body, suggestion);
        Path file = ReviewKeys.resolve(place.root(), place.path());
        host.forEachBuffer(b -> {
            if (file.equals(b.getPath())) {
                places.putIfAbsent(b, place);
            }
        });
        change(place, next);
        host.setStatus(tr("status.review.agentAdded", agent));
        return next.get(next.size() - 1).id();
    }

    /** An agent's reply to thread {@code threadId}; null when done, else why not. */
    @Nullable
    String agentReply(Place place, String threadId, String body, @Nullable String suggestion, String agent) {
        if (find(place.repoKey(), threadId) == null) {
            return "No review thread " + threadId + " in this repository.";
        }
        change(
                place,
                threads.reply(storage.threads(place.repoKey()), threadId, ReviewAuthor.agent(agent), body, suggestion));
        return null;
    }

    /** An agent resolves thread {@code threadId}; the note says who and what fixed it. Null when done. */
    @Nullable
    String agentResolve(Place place, String threadId, String note, String agent) {
        ReviewThread t = find(place.repoKey(), threadId);
        if (t == null) {
            return "No review thread " + threadId + " in this repository.";
        }
        if (!t.open()) {
            return "Review thread " + threadId + " is not open.";
        }
        String stateNote = tr("review.note.resolvedBy", agent, note.strip());
        change(place, threads.resolve(storage.threads(place.repoKey()), threadId, stateNote));
        host.setStatus(tr("status.review.agentResolved", agent));
        return null;
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

    /** Where a card goes for a thread's lines; a seam so a test can have them never laid out. */
    interface LineAnchor {
        double @Nullable [] find(CodeArea area, int first, int last);
    }

    private LineAnchor lineAnchor = ReviewCoordinator::anchor;

    /** TEST SEAM — where cards are placed for a thread's lines. */
    void setLineAnchorForTest(LineAnchor anchor) {
        this.lineAnchor = anchor;
    }

    /** The card under the editor's top edge: {@code {x, belowY, aboveY}} in scene coordinates, or null off scene. */
    static double @Nullable [] areaAnchor(CodeArea area) {
        if (area.getScene() == null) {
            return null;
        }
        Bounds b = area.localToScene(area.getBoundsInLocal());
        return new double[] {b.getMinX() + 48, b.getMinY() + Math.min(b.getHeight(), 24), b.getMinY()};
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
