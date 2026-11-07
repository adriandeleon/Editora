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

import static com.editora.i18n.Messages.tr;

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

    /** The project's ignored html keys last reported (A107): the same list is not said again on every apply. */
    private List<String> reportedIgnored = List.of();

    /** How long after the last file event the pages' links are checked again (B106). */
    static final Duration LINK_RECHECK_DELAY = Duration.millis(300);

    /** Checks the pages' links again once file events stop coming; built on first use. */
    private @Nullable PauseTransition linkRecheck;

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(HtmlLspAids.class.getName());

    HtmlLspAids(CoordinatorHost host, LspManager lsp, LspCoordinator.Ops ops) {
        this.host = host;
        this.lsp = lsp;
        this.ops = ops;
        this.links = new HtmlLinkFollow(host, lsp, ops);
        lsp.setHtmlMarkupChecks(() -> host.settings().isHtmlMarkupChecks());
    }

    /** A new buffer: the markup check for when no server serves it. */
    void wire(EditorBuffer buffer) {
        buffer.addMarkupCheck(new com.editora.editor.http.HttpVariableMarks.MarkupCheck(
                () -> host.settings().isHtmlMarkupChecks()
                        && HtmlLinks.isHtml(buffer.getLanguage())
                        && !buffer.isLspActive()
                        && !buffer.isLargeFile()
                        && (buffer.getPath() == null || HtmlMarkupLint.applies(buffer.getPath())),
                (text, done) -> {
                    Path file = buffer.getPath();
                    Path project = ops.lspProjectRoot(); // a page outside it: its site's root, as the server's (A202)
                    CompletableFuture.supplyAsync(
                                    () -> HtmlMarkupLint.marks(
                                            text, file, com.editora.lsp.RootResolver.htmlRoot(project, file)),
                                    CHECKS)
                            .exceptionally(t -> List.of())
                            .thenAccept(marks -> Platform.runLater(() -> done.accept(marks)));
                }));
    }

    /**
     * Hands the HTML servers their settings: the user's, with the project's {@code html} object ({@code projectHtml})
     * for the project's own root (B2). What that object sets that the server does not take is said once, on the
     * status bar and in the log, until the list changes (HTML review 2, A107, D102).
     */
    void applySettings(Map<String, Object> projectHtml) {
        lsp.setHtmlSettings(host.settings(), projectHtml, ops.lspProjectRoot());
        List<String> ignored = com.editora.lsp.HtmlServerSettings.of(host.settings())
                .withProject(projectHtml)
                .ignoredProjectKeys();
        if (!ignored.equals(reportedIgnored)) {
            reportedIgnored = ignored;
            if (!ignored.isEmpty()) {
                String keys = String.join(", ", ignored);
                LOG.warning(() -> "ignored project html settings: " + keys);
                host.setStatus(tr("status.html.projectKeysIgnored", keys));
            }
        }
    }

    /**
     * Files were created, deleted or renamed (seen by a watcher, or done in Editora): every open page's "File not
     * found" marks are checked again, a moment later so a burst (a checkout) is one check (B106). A page a server
     * serves is pulled again (the markup check joins its answer); any other page re-runs its own check.
     */
    void filesChanged(List<LspManager.WatchedFile> changes) {
        if (changes.stream().anyMatch(c -> c.kind() != LspManager.WatchedKind.CHANGED)) {
            recheckLinksSoon(); // a file's content says nothing about which links resolve
        }
    }

    /** As {@link #filesChanged}, for the Project panel's events, which come with LSP support off too. */
    void panelChanged(@Nullable List<ProjectPanel.FsChange> changes) {
        if (changes != null && changes.stream().anyMatch(c -> c != null && c.kind() != ProjectPanel.FsKind.CHANGED)) {
            recheckLinksSoon();
        }
    }

    private void recheckLinksSoon() {
        if (linkRecheck == null) {
            linkRecheck = new PauseTransition(LINK_RECHECK_DELAY);
            linkRecheck.setOnFinished(e -> host.forEachBuffer(b -> {
                Path path = b.getPath();
                if (path == null || !HtmlLinks.isHtml(b.getLanguage()) || !HtmlMarkupLint.applies(path)) {
                    return;
                }
                if (lsp.isManaged(path)) {
                    lsp.pullDiagnostics(path);
                } else {
                    b.refreshHttpVariableLint(); // the markup check of a page no server serves
                }
            }));
        }
        linkRecheck.playFromStart();
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

    /**
     * Re-bounds the swatches when scrolling settles (from the cached answer: documentColor has no range), and moves
     * them with each edit until the next answer: column-anchored, they stayed put and landed inside the words typed
     * before them (HTML review 2 C111/B108).
     */
    private void followScroll(EditorBuffer buffer) {
        if (followed.put(buffer, Boolean.TRUE) != null) {
            return;
        }
        var area = buffer.getArea();
        area.plainTextChanges().subscribe(change -> {
            List<ColorSwatch> all = colors.get(buffer);
            if (all == null || all.isEmpty()) {
                return;
            }
            var at = area.offsetToPosition(change.getPosition(), org.fxmisc.richtext.model.TwoDimensional.Bias.Forward);
            List<ColorSwatch> moved =
                    ColorSwatch.shifted(all, at.getMajor(), at.getMinor(), change.getRemoved(), change.getInserted());
            if (moved != all) {
                colors.put(buffer, moved);
                showColors(buffer);
            }
        });
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
