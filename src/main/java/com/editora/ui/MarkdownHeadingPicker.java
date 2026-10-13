package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import javafx.application.Platform;

import com.editora.markdown.MarkdownOutline;
import com.editora.markdown.MarkdownText;
import com.editora.vfs.Vfs;

import static com.editora.i18n.Messages.tr;

/**
 * {@code project.gotoMarkdownHeading}: every heading of every Markdown file in the project, in one picker.
 *
 * <p>The files are read when the command runs, on a thread of their own — the walk the symbol index uses,
 * with its ignore rules and a cap — and an open buffer contributes its text as it is now, saved or not. A
 * second run, a project switch or closing the window abandons a scan still under way.
 */
final class MarkdownHeadingPicker {

    /** One heading and the file it is in ({@code rel} is the path shown, relative to the project). */
    record Hit(Path file, String rel, MarkdownOutline.Heading heading) {}

    /** More headings than this and the list is cut (and the status line says so). */
    static final int MAX_HEADINGS = 50_000;

    private final CoordinatorHost host;
    private final IndexCoordinator.Ops ops;
    private final QuickOpen<Hit> picker;
    private final AtomicLong generation = new AtomicLong();

    private List<Hit> hits = List.of();

    /** Completes with the hits of the last run once they are on show (tests wait on it). */
    CompletableFuture<List<Hit>> lastRun = CompletableFuture.completedFuture(List.of());

    MarkdownHeadingPicker(CoordinatorHost host, IndexCoordinator.Ops ops) {
        this.host = host;
        this.ops = ops;
        this.picker = new QuickOpen<>(
                tr("markdown.gotoHeading.title"),
                tr("markdown.gotoHeading.prompt"),
                () -> new ArrayList<>(hits),
                MarkdownHeadingPicker::label,
                hit -> hit.rel() + ":" + (hit.heading().line() + 1),
                hit -> ops.openAndGoto(hit.file(), hit.heading().line(), 0));
    }

    void setOverlayHost(OverlayHost overlayHost) {
        picker.setOverlayHost(overlayHost);
    }

    void dispose() {
        generation.incrementAndGet();
    }

    /** Sub-headings are indented under their parents' level, so a file's headings read as an outline. */
    static String label(Hit hit) {
        return "  ".repeat(Math.max(0, hit.heading().level() - 1))
                + MarkdownText.plain(hit.heading().title());
    }

    void show() {
        Path root = ops.projectRoot();
        if (root == null || !Vfs.isLocal(root)) {
            host.setStatus(tr("status.noProjectOpen"));
            return;
        }
        Path base = root.toAbsolutePath().normalize();
        Map<Path, String> open = new LinkedHashMap<>();
        host.forEachBuffer(buffer -> {
            Path path = buffer.getPath();
            if (path != null
                    && buffer.isMarkdown()
                    && path.toAbsolutePath().normalize().startsWith(base)) {
                open.put(path.toAbsolutePath().normalize(), buffer.getContent());
            }
        });
        boolean ignore = ops.respectGitignore();
        long gen = generation.incrementAndGet();
        CompletableFuture<List<Hit>> run = new CompletableFuture<>();
        lastRun = run;
        AutoCloseable task = host.startBackgroundTask(tr("status.markdown.headingsScanning"));
        Thread thread = new Thread(
                () -> {
                    boolean[] truncated = {false};
                    List<Hit> found;
                    try {
                        found = scan(base, ignore, open, () -> gen != generation.get(), truncated);
                    } catch (RuntimeException e) {
                        found = List.of();
                    }
                    List<Hit> result = found;
                    Platform.runLater(() -> {
                        try {
                            task.close();
                        } catch (Exception ignored) {
                            // the progress chip is cosmetic
                        }
                        if (gen != generation.get()) {
                            run.complete(List.of());
                            return;
                        }
                        hits = result;
                        if (result.isEmpty()) {
                            host.setStatus(tr("status.markdown.noHeadings"));
                        } else {
                            if (truncated[0]) {
                                host.setStatus(tr("status.markdown.headingsTruncated", result.size()));
                            }
                            picker.show(host.window());
                        }
                        run.complete(result);
                    });
                },
                "markdown-headings");
        thread.setDaemon(true);
        thread.start();
    }

    /** Blocking. Files in walk order, headings in document order. */
    static List<Hit> scan(
            Path root, boolean ignore, Map<Path, String> open, BooleanSupplier cancelled, boolean[] truncated) {
        MarkdownProjectFiles.Listing listing = MarkdownProjectFiles.list(root, ignore, cancelled);
        truncated[0] = listing.truncated();
        List<Path> files = new ArrayList<>(listing.files());
        for (Path path : open.keySet()) {
            if (!files.contains(path)) {
                files.add(path);
            }
        }
        List<Hit> out = new ArrayList<>();
        for (Path file : files) {
            if (cancelled.getAsBoolean()) {
                return List.of();
            }
            Path doc = file.toAbsolutePath().normalize();
            String text = open.containsKey(doc) ? open.get(doc) : MarkdownProjectFiles.read(doc);
            if (text == null) {
                continue;
            }
            String rel = root.relativize(doc).toString().replace('\\', '/');
            for (MarkdownOutline.Heading heading : MarkdownOutline.headings(text)) {
                if (out.size() >= MAX_HEADINGS) {
                    truncated[0] = true;
                    return out;
                }
                out.add(new Hit(doc, rel, heading));
            }
        }
        return out;
    }
}
