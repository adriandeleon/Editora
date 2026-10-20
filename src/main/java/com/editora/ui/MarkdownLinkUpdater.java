package com.editora.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.io.AtomicFileWrite;
import com.editora.markdown.MarkdownLinkIndex;
import com.editora.markdown.MarkdownLinkRewrite;
import com.editora.markdown.WikiTargets;
import com.editora.vfs.Vfs;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Keeps the project's Markdown links pointing at a file after it is renamed or moved.
 *
 * <p>Every rename the editor performs (the project tree's rename, move and their undo, and File: Rename)
 * lands in {@link #onRenamed}. The project's link index ({@link MarkdownLinkGraph}) names the documents
 * whose links pointed at the old place; only those are read, off the FX thread, and rewritten by
 * {@link MarkdownLinkRewrite} — paths, raw HTML {@code src}/{@code href} and wiki links alike. A document
 * open in any window is edited in its buffer as one undo step; a closed file has its text recorded in
 * Local History and is then replaced by a staged write that refuses to go over a file that changed
 * meanwhile. Nothing outside the project is read or written, and {@link Settings#getMarkdownUpdateLinksOnRename()} decides whether the user is asked
 * first, never asked, or the links are left alone.
 */
final class MarkdownLinkUpdater {

    /** The links one file would have rewritten. */
    record FileChange(Path file, int count) {}

    /** Everything one rename would change. */
    record Plan(
            Path root,
            Path from,
            Path to,
            List<FileChange> changes,
            boolean truncated,
            @Nullable WikiTargets wiki) {

        int links() {
            return changes.stream().mapToInt(FileChange::count).sum();
        }
    }

    /** What was done: links rewritten, files changed, and the files that could not be. */
    record Outcome(int links, int files, List<Path> failed) {

        static final Outcome NOTHING = new Outcome(0, 0, List.of());
    }

    /** One rename is handled once, however many windows have the project open. */
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private static final int LISTED = 10;

    private final CoordinatorHost host;
    private final MarkdownLinkGraph graph;
    private final HistoryRecorder history;

    private volatile boolean disposed;

    /** Records a closed file's text in Local History before it is rewritten; reports whether that is durable. */
    @FunctionalInterface
    interface HistoryRecorder {
        void record(Path file, String content, java.util.function.Consumer<Boolean> done);
    }

    /** Asks whether to apply a plan; replaced by tests, which have no one to click. */
    Predicate<Plan> confirm = this::ask;

    /** Completes when the last rename has been fully handled (tests wait on it). */
    CompletableFuture<Outcome> lastRun = CompletableFuture.completedFuture(Outcome.NOTHING);

    MarkdownLinkUpdater(CoordinatorHost host, MarkdownLinkGraph graph, HistoryRecorder history) {
        this.host = host;
        this.graph = graph;
        this.history = history;
    }

    void dispose() {
        disposed = true;
    }

    /** {@code from} (a file or a folder) is now {@code to}. FX thread; returns at once. */
    void onRenamed(Path from, Path to) {
        Settings settings = host.settings();
        String mode = settings == null ? "never" : settings.getMarkdownUpdateLinksOnRename();
        Path base = graph.rootOrNull();
        if (disposed || from == null || to == null || !Vfs.isLocal(from) || !Vfs.isLocal(to)) {
            return;
        }
        Path source = from.toAbsolutePath().normalize();
        Path target = to.toAbsolutePath().normalize();
        if ("never".equals(mode) || base == null || !source.startsWith(base) || source.equals(target)) {
            graph.onRenamed(source, target); // nothing to rewrite, but the index still has to follow the files
            return;
        }
        String key = source + "\n" + target;
        if (!IN_FLIGHT.add(key)) {
            return; // another window with this project is already on it
        }
        CompletableFuture<Outcome> run = new CompletableFuture<>();
        run.whenComplete((outcome, failure) -> IN_FLIGHT.remove(key));
        lastRun = run;
        // Later, not now: the caller is in the middle of moving the open buffer to its new path.
        Platform.runLater(() -> {
            Map<Path, String> open = openDocuments(base);
            graph.submit(index -> {
                        Plan plan = plan(index, base, source, target, open);
                        Platform.runLater(() -> decide(plan, "always".equals(mode), run));
                    })
                    .whenComplete((v, failure) -> Platform.runLater(() -> {
                        if (disposed) {
                            run.complete(Outcome.NOTHING);
                        }
                    }));
        });
    }

    /** The text of every open Markdown buffer under {@code root}, in any window, by path. */
    private static Map<Path, String> openDocuments(Path root) {
        Map<Path, String> open = new LinkedHashMap<>();
        MarkdownLinkGraph.openBuffers().forEach((path, buffer) -> {
            if (path.startsWith(root) && MarkdownProjectFiles.isMarkdown(path)) {
                open.put(path, buffer.getContent());
            }
        });
        return open;
    }

    /**
     * Worker: which documents hold links the rename broke. Asked of the index — the documents whose links
     * pointed under {@code from}, and the ones that moved themselves — so only those are read, not the
     * project; an open buffer is judged by its text as it is now.
     */
    Plan plan(MarkdownLinkIndex index, Path root, Path from, Path to, Map<Path, String> open) {
        if (index == null) {
            return new Plan(root, from, to, List.of(), false, null);
        }
        boolean knowsOldPaths = index.files().stream().anyMatch(f -> f.startsWith(from));
        if (knowsOldPaths) {
            index.renamed(from, to); // built before the rename: bring its paths up to date first
        }
        List<Path> filesBefore = new ArrayList<>(index.files().size());
        for (Path file : index.files()) {
            filesBefore.add(before(file, from, to));
        }
        WikiTargets wikiBefore = index.wikiLinks() ? new WikiTargets(root, filesBefore) : null;
        Set<Path> candidates = new LinkedHashSet<>();
        for (Path doc : index.documents()) {
            if (!open.containsKey(doc) && (doc.startsWith(to) || pointedUnder(index, doc, from, to, wikiBefore))) {
                candidates.add(doc);
            }
        }
        candidates.addAll(open.keySet()); // few, and their text may differ from what was indexed
        List<FileChange> changes = new ArrayList<>();
        for (Path doc : candidates) {
            if (disposed) {
                break;
            }
            String text = open.containsKey(doc) ? open.get(doc) : MarkdownProjectFiles.read(doc);
            if (text == null) {
                continue;
            }
            int count = MarkdownLinkRewrite.rewrite(text, before(doc, from, to), doc, from, to, wikiBefore)
                    .count();
            if (count > 0) {
                changes.add(new FileChange(doc, count));
            }
        }
        return new Plan(root, from, to, List.copyOf(changes), graph.truncated(), wikiBefore);
    }

    /** Whether an indexed link of {@code doc} pointed at {@code from} (or under it) before the rename. */
    private static boolean pointedUnder(
            MarkdownLinkIndex index, Path doc, Path from, Path to, @Nullable WikiTargets wikiBefore) {
        Path docBefore = before(doc, from, to);
        for (MarkdownLinkRewrite.Link link : index.outgoing(doc)) {
            Path target;
            if (link.isWiki()) {
                WikiTargets.Resolved r = wikiBefore == null ? null : wikiBefore.resolve(docBefore, link.path());
                target = r == null ? null : r.file();
            } else {
                target = index.target(docBefore, link);
            }
            if (target != null && target.startsWith(from)) {
                return true;
            }
        }
        return false;
    }

    /** Where {@code doc} was before the rename: it moved too when it is, or is under, the renamed path. */
    private static Path before(Path doc, Path from, Path to) {
        return doc.startsWith(to) ? from.resolve(to.relativize(doc)).normalize() : doc;
    }

    private void decide(Plan plan, boolean always, CompletableFuture<Outcome> run) {
        if (plan.changes().isEmpty() || disposed || (!always && !confirm.test(plan))) {
            run.complete(Outcome.NOTHING);
            return;
        }
        apply(plan, run);
    }

    /** FX thread: open buffers — of every window — are edited here, closed files on the worker. */
    private void apply(Plan plan, CompletableFuture<Outcome> run) {
        Map<Path, EditorBuffer> buffers = MarkdownLinkGraph.openBuffers();
        int[] totals = new int[2];
        List<Path> failed = new ArrayList<>();
        List<Path> closed = new ArrayList<>();
        for (FileChange change : plan.changes()) {
            EditorBuffer buffer = buffers.get(change.file());
            if (buffer == null) {
                closed.add(change.file());
                continue;
            }
            int count = rewriteBuffer(buffer, change.file(), plan);
            if (count < 0) {
                failed.add(change.file());
            } else if (count > 0) {
                totals[0] += count;
                totals[1]++;
            }
        }
        graph.submit(index -> {
            for (Path file : closed) {
                String[] written = new String[1];
                int count = disposed ? -1 : rewriteFile(file, plan, this::recordHistory, written);
                if (count < 0) {
                    failed.add(file);
                } else if (count > 0) {
                    totals[0] += count;
                    totals[1]++;
                    if (index != null) {
                        index.put(file, written[0]);
                    }
                }
            }
            Outcome outcome = new Outcome(totals[0], totals[1], List.copyOf(failed));
            Platform.runLater(() -> {
                report(outcome);
                run.complete(outcome);
            });
        });
    }

    /** How long the worker waits for Local History to take a file's text before giving the file up. */
    private static final long HISTORY_TIMEOUT_SECONDS = 10;

    /** Worker: true once {@code original} is safely in Local History (or there is no history to keep it). */
    private boolean recordHistory(Path file, String original) {
        if (history == null) {
            return true;
        }
        CompletableFuture<Boolean> durable = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                history.record(file, original, ok -> durable.complete(Boolean.TRUE.equals(ok)));
            } catch (RuntimeException e) {
                durable.complete(false);
            }
        });
        try {
            return durable.get(HISTORY_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            return false;
        }
    }

    /** Links rewritten in an open buffer, as one undo step; -1 when the buffer may not be edited. */
    private int rewriteBuffer(EditorBuffer buffer, Path file, Plan plan) {
        MarkdownLinkRewrite.Result result = MarkdownLinkRewrite.rewrite(
                buffer.getContent(), before(file, plan.from(), plan.to()), file, plan.from(), plan.to(), plan.wiki());
        if (result.count() == 0) {
            return 0;
        }
        if (!buffer.isEditable()
                || buffer.isTruncatedLoad()
                || !NoUndoGuard.allow(buffer, tr("noUndo.op.updateLinks"))) {
            return -1;
        }
        var area = buffer.getArea();
        int caret = area.getCaretPosition();
        buffer.replaceWholeDocument(result.text());
        area.moveTo(Math.min(caret, area.getLength()));
        return result.count();
    }

    /**
     * Links rewritten in a closed file; -1 when it could not be written safely. The text it held goes to
     * Local History first ({@code keep}); a file whose previous text could not be kept is left alone.
     */
    static int rewriteFile(Path file, Plan plan, java.util.function.BiPredicate<Path, String> keep, String[] written) {
        try {
            if (!file.startsWith(plan.root()) || Files.isSymbolicLink(file)) {
                return -1; // never follow a path out of the project
            }
            String original = Files.readString(file);
            MarkdownLinkRewrite.Result result = MarkdownLinkRewrite.rewrite(
                    original, before(file, plan.from(), plan.to()), file, plan.from(), plan.to(), plan.wiki());
            if (result.count() == 0) {
                return 0;
            }
            if (!Files.isWritable(file) || !keep.test(file, original)) {
                return -1;
            }
            boolean ok = AtomicFileWrite.replaceIfUnchanged(
                    file,
                    original.getBytes(StandardCharsets.UTF_8),
                    result.text().getBytes(StandardCharsets.UTF_8),
                    () -> true);
            written[0] = result.text();
            return ok ? result.count() : -1;
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    private void report(Outcome outcome) {
        if (outcome.failed().isEmpty()) {
            if (outcome.links() > 0) {
                host.setStatus(tr("status.markdown.linksUpdated", outcome.links(), outcome.files()));
            }
            return;
        }
        String paths = outcome.failed().stream()
                .limit(8)
                .map(Path::toString)
                .collect(java.util.stream.Collectors.joining(", "));
        if (outcome.failed().size() > 8) {
            paths += ", …";
        }
        host.setError(tr(
                        "status.markdown.linksPartial",
                        outcome.links(),
                        outcome.files(),
                        outcome.failed().size()) + ": " + paths);
    }

    /** The prompt's body: each file with its link count, the first few of them. */
    static String describe(Plan plan) {
        StringBuilder sb = new StringBuilder();
        List<FileChange> changes = plan.changes();
        for (int i = 0; i < Math.min(LISTED, changes.size()); i++) {
            FileChange change = changes.get(i);
            String rel = plan.root().relativize(change.file()).toString().replace('\\', '/');
            sb.append(tr("markdown.links.update.item", rel, change.count())).append('\n');
        }
        if (changes.size() > LISTED) {
            sb.append(tr("markdown.links.update.more", changes.size() - LISTED)).append('\n');
        }
        if (plan.truncated()) {
            sb.append('\n').append(tr("markdown.links.update.truncated")).append('\n');
        }
        return sb.append('\n').append(tr("markdown.links.update.hint")).toString();
    }

    private boolean ask(Plan plan) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(host.window());
        alert.setTitle(tr("markdown.links.update.title"));
        alert.setHeaderText(tr(
                "markdown.links.update.header",
                plan.links(),
                plan.changes().size(),
                String.valueOf(plan.to().getFileName())));
        alert.setContentText(describe(plan));
        ButtonType update = new ButtonType(tr("markdown.links.update.apply"), ButtonBar.ButtonData.OK_DONE);
        ButtonType leave = new ButtonType(tr("markdown.links.update.skip"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(update, leave);
        return Dialogs.styled(alert).showAndWait().filter(update::equals).isPresent();
    }
}
