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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.io.AtomicFileWrite;
import com.editora.markdown.MarkdownLinkRewrite;
import com.editora.vfs.Vfs;

import static com.editora.i18n.Messages.tr;

/**
 * Keeps the project's Markdown links pointing at a file after it is renamed or moved.
 *
 * <p>Every rename the editor performs (the project tree's rename, move and their undo, and File: Rename)
 * lands in {@link #onRenamed}. The project's Markdown files are then read off the FX thread — the same
 * bounded, ignore-aware walk the symbol index uses — and the links that pointed at the old place are
 * rewritten by {@link MarkdownLinkRewrite}: in an open buffer as one undo step, in a closed file by a
 * staged replace that refuses to write over a file that changed meanwhile. Nothing outside the project is
 * read or written, and {@link Settings#getMarkdownUpdateLinksOnRename()} decides whether the user is asked
 * first, never asked, or the links are left alone.
 */
final class MarkdownLinkUpdater {

    /** The links one file would have rewritten. */
    record FileChange(Path file, int count) {}

    /** Everything one rename would change. */
    record Plan(Path root, Path from, Path to, List<FileChange> changes, boolean truncated) {

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
    private final Supplier<Path> projectRoot;
    private final BooleanSupplier respectGitignore;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "markdown-links");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean disposed;

    /** Asks whether to apply a plan; replaced by tests, which have no one to click. */
    Predicate<Plan> confirm = this::ask;

    /** Completes when the last rename has been fully handled (tests wait on it). */
    CompletableFuture<Outcome> lastRun = CompletableFuture.completedFuture(Outcome.NOTHING);

    MarkdownLinkUpdater(CoordinatorHost host, Supplier<Path> projectRoot, BooleanSupplier respectGitignore) {
        this.host = host;
        this.projectRoot = projectRoot;
        this.respectGitignore = respectGitignore;
    }

    void dispose() {
        disposed = true;
        worker.shutdownNow();
    }

    /** {@code from} (a file or a folder) is now {@code to}. FX thread; returns at once. */
    void onRenamed(Path from, Path to) {
        Settings settings = host.settings();
        String mode = settings == null ? "never" : settings.getMarkdownUpdateLinksOnRename();
        Path root = projectRoot.get();
        if ("never".equals(mode) || disposed || root == null || from == null || to == null) {
            return;
        }
        Path base = root.toAbsolutePath().normalize();
        Path source = from.toAbsolutePath().normalize();
        Path target = to.toAbsolutePath().normalize();
        if (!Vfs.isLocal(base) || !Vfs.isLocal(source) || !source.startsWith(base) || source.equals(target)) {
            return;
        }
        String key = source + "\n" + target;
        if (!IN_FLIGHT.add(key)) {
            return; // another window with this project is already on it
        }
        CompletableFuture<Outcome> run = new CompletableFuture<>();
        run.whenComplete((outcome, failure) -> IN_FLIGHT.remove(key));
        lastRun = run;
        boolean ignore = respectGitignore.getAsBoolean();
        // Later, not now: the caller is in the middle of moving the open buffer to its new path.
        Platform.runLater(() -> {
            Map<Path, String> open = openDocuments(base);
            submit(run, () -> {
                Plan plan = plan(base, source, target, open, ignore);
                Platform.runLater(() -> decide(plan, "always".equals(mode), run));
            });
        });
    }

    private void submit(CompletableFuture<Outcome> run, Runnable work) {
        try {
            worker.submit(() -> {
                try {
                    work.run();
                } catch (RuntimeException | Error e) {
                    run.complete(Outcome.NOTHING);
                }
            });
        } catch (RuntimeException rejected) {
            run.complete(Outcome.NOTHING);
        }
    }

    /** The text of every open Markdown buffer saved under {@code root}, by path. */
    private Map<Path, String> openDocuments(Path root) {
        Map<Path, String> open = new LinkedHashMap<>();
        host.forEachBuffer(buffer -> {
            Path path = normalized(buffer);
            if (path != null && path.startsWith(root) && MarkdownProjectFiles.isMarkdown(path)) {
                open.put(path, buffer.getContent());
            }
        });
        return open;
    }

    private static Path normalized(EditorBuffer buffer) {
        Path path = buffer == null ? null : buffer.getPath();
        return path == null || !Vfs.isLocal(path) ? null : path.toAbsolutePath().normalize();
    }

    /** Blocking: which files hold links the rename broke. */
    Plan plan(Path root, Path from, Path to, Map<Path, String> open, boolean ignore) {
        MarkdownProjectFiles.Listing listing = MarkdownProjectFiles.list(root, ignore, () -> disposed);
        Set<Path> files = new LinkedHashSet<>(listing.files());
        files.addAll(open.keySet()); // an open document counts even when the walk would skip it
        List<FileChange> changes = new ArrayList<>();
        for (Path file : files) {
            if (disposed) {
                break;
            }
            Path doc = file.toAbsolutePath().normalize();
            String text = open.containsKey(doc) ? open.get(doc) : MarkdownProjectFiles.read(doc);
            if (text == null) {
                continue;
            }
            int count = MarkdownLinkRewrite.rewrite(text, before(doc, from, to), doc, from, to)
                    .count();
            if (count > 0) {
                changes.add(new FileChange(doc, count));
            }
        }
        return new Plan(root, from, to, List.copyOf(changes), listing.truncated());
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

    /** FX thread: open buffers are edited here, closed files on the worker. */
    private void apply(Plan plan, CompletableFuture<Outcome> run) {
        Map<Path, EditorBuffer> buffers = new LinkedHashMap<>();
        host.forEachBuffer(buffer -> {
            Path path = normalized(buffer);
            if (path != null) {
                buffers.putIfAbsent(path, buffer);
            }
        });
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
        submit(run, () -> {
            for (Path file : closed) {
                int count = disposed ? -1 : rewriteFile(file, plan);
                if (count < 0) {
                    failed.add(file);
                } else if (count > 0) {
                    totals[0] += count;
                    totals[1]++;
                }
            }
            Outcome outcome = new Outcome(totals[0], totals[1], List.copyOf(failed));
            Platform.runLater(() -> {
                report(outcome);
                run.complete(outcome);
            });
        });
    }

    /** Links rewritten in an open buffer, as one undo step; -1 when the buffer may not be edited. */
    private int rewriteBuffer(EditorBuffer buffer, Path file, Plan plan) {
        MarkdownLinkRewrite.Result result = MarkdownLinkRewrite.rewrite(
                buffer.getContent(), before(file, plan.from(), plan.to()), file, plan.from(), plan.to());
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

    /** Links rewritten in a closed file; -1 when it could not be written safely. */
    static int rewriteFile(Path file, Plan plan) {
        try {
            if (!file.startsWith(plan.root()) || Files.isSymbolicLink(file)) {
                return -1; // never follow a path out of the project
            }
            String original = Files.readString(file);
            MarkdownLinkRewrite.Result result = MarkdownLinkRewrite.rewrite(
                    original, before(file, plan.from(), plan.to()), file, plan.from(), plan.to());
            if (result.count() == 0) {
                return 0;
            }
            if (!Files.isWritable(file)) {
                return -1;
            }
            boolean written = AtomicFileWrite.replaceIfUnchanged(
                    file,
                    original.getBytes(StandardCharsets.UTF_8),
                    result.text().getBytes(StandardCharsets.UTF_8),
                    () -> true);
            return written ? result.count() : -1;
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
