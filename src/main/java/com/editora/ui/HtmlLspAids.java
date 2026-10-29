package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.util.Duration;

import com.editora.editops.HtmlLinks;
import com.editora.editor.EditorBuffer;
import com.editora.lsp.HtmlMarkupLint;
import com.editora.lsp.LspManager;
import com.editora.text.ColorSwatch;
import org.jspecify.annotations.Nullable;

/**
 * The HTML review's link, check and colour aids, kept out of {@link LspCoordinator} (which owns one and calls it
 * from a line each):
 *
 * <ul>
 *   <li>{@link #links}: Go to Definition and Ctrl-click on {@code href}/{@code src} (B6);
 *   <li>the markup check (D5): joined to the HTML server's diagnostics by {@link LspManager}, and shown on the
 *       buffer's own squiggle overlay while no server serves the page;
 *   <li>colour swatches (D9): {@code textDocument/documentColor} of the HTML and CSS servers (any server with a
 *       colour provider), asked on the same pulses as folding ranges and bounded to the lines around the viewport.
 * </ul>
 */
final class HtmlLspAids {

    /** Lines kept either side of the viewport, as for semantic tokens: a short scroll needs no new answer. */
    static final int COLOR_WINDOW_PAD = 200;

    private static final ExecutorService CHECKS = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "html-markup-check");
        t.setDaemon(true);
        return t;
    });

    private final CoordinatorHost host;
    private final LspManager lsp;
    private final LspCoordinator.Ops ops;
    final HtmlLinkFollow links;

    /** Each buffer's last colours, whole-document, so a scroll re-bounds them without asking again. */
    private final Map<EditorBuffer, List<ColorSwatch>> colors = new WeakHashMap<>();
    /** The buffers whose scroll is followed (each gets one settle timer, once); weak, and nothing points back. */
    private final Map<EditorBuffer, Boolean> followed = new WeakHashMap<>();
    /**
     * The markup check's setting as last applied, so a settings apply that did not change it re-checks nothing;
     * null before the first (the window's settings are not loaded yet when this is built).
     */
    private @Nullable Boolean markupChecksOn;

    HtmlLspAids(CoordinatorHost host, LspManager lsp, LspCoordinator.Ops ops) {
        this.host = host;
        this.lsp = lsp;
        this.ops = ops;
        this.links = new HtmlLinkFollow(host, lsp, ops);
        lsp.setHtmlMarkupChecks(() -> host.settings().isHtmlMarkupChecks());
    }

    /** A new buffer: the markup check for when no server serves it. */
    void wire(EditorBuffer buffer) {
        buffer.setHtmlMarkupCheck(new com.editora.editor.http.HttpVariableMarks.MarkupCheck(
                () -> host.settings().isHtmlMarkupChecks()
                        && HtmlLinks.isHtml(buffer.getLanguage())
                        && !buffer.isLspActive()
                        && !buffer.isLargeFile()
                        && (buffer.getPath() == null || HtmlMarkupLint.applies(buffer.getPath())),
                (text, done) -> {
                    Path file = buffer.getPath();
                    Path root = ops.lspProjectRoot();
                    CompletableFuture.supplyAsync(() -> HtmlMarkupLint.marks(text, file, root), CHECKS)
                            .exceptionally(t -> List.of())
                            .thenAccept(marks -> Platform.runLater(() -> done.accept(marks)));
                }));
    }

    /** A settings apply: when the markup check was switched, every page is checked again (or cleared). */
    void settingsChanged() {
        boolean on = host.settings().isHtmlMarkupChecks();
        if (Boolean.valueOf(on).equals(markupChecksOn)) {
            return;
        }
        markupChecksOn = on;
        host.forEachBuffer(b -> {
            b.refreshHttpVariableLint(); // the shared side marks: re-evaluates the markup check's gate
            Path path = b.getPath();
            if (path != null && HtmlMarkupLint.applies(path) && lsp.isManaged(path)) {
                lsp.pullDiagnostics(path);
            }
        });
    }

    /**
     * Asks {@code buffer}'s server for its colours and draws them (D9); clears them when the buffer has no server
     * with a colour provider. Rides the folding-range pulses: an edit's debounce, the server becoming ready, a
     * capability change.
     */
    void requestColors(@Nullable EditorBuffer buffer, @Nullable Path path) {
        if (buffer == null) {
            return;
        }
        if (path == null || buffer.isLargeFile() || !lsp.isManaged(path) || !lsp.supportsDocumentColor(path)) {
            colors.remove(buffer);
            buffer.setColorSwatches(null);
            return;
        }
        followScroll(buffer);
        long version = buffer.docVersion();
        lsp.documentColors(path, found -> {
            // Positions are measured against the text the server saw: a later edit's own pulse asks again.
            if (buffer.docVersion() == version) {
                colors.put(buffer, found);
                showColors(buffer);
            }
        });
    }

    /** Draws the buffer's colours on the lines around its viewport. */
    private void showColors(EditorBuffer buffer) {
        List<ColorSwatch> all = colors.get(buffer);
        if (all == null) {
            return;
        }
        int[] window = LspCoordinator.paddedWindow(buffer.visibleLineWindow(), COLOR_WINDOW_PAD, buffer.lineCount());
        buffer.setColorSwatches(inWindow(all, window[0], window[1]));
    }

    /** Pure: the swatches on lines {@code first}..{@code last} (inclusive). */
    static List<ColorSwatch> inWindow(List<ColorSwatch> all, int first, int last) {
        return all.stream().filter(s -> s.line() >= first && s.line() <= last).toList();
    }

    /** Re-bounds the swatches when scrolling settles (from the cached answer: documentColor has no range). */
    private void followScroll(EditorBuffer buffer) {
        if (followed.put(buffer, Boolean.TRUE) != null) {
            return;
        }
        PauseTransition settle = new PauseTransition(Duration.millis(150));
        settle.setOnFinished(e -> showColors(buffer));
        buffer.getArea().estimatedScrollYProperty().addListener((obs, was, now) -> {
            List<ColorSwatch> all = colors.get(buffer);
            if (all != null && !all.isEmpty()) {
                settle.playFromStart();
            }
        });
    }
}
