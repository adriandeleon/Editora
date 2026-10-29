package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.vfs.Vfs;
import com.editora.web.Browsers;
import com.editora.web.Browsers.Browser;
import com.editora.web.HtmlPreviewService;
import com.editora.web.LivePreviewServer;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Owns the HTML Live Preview feature, extracted from {@code MainController} as a feature coordinator (the
 * second after {@link LogViewerCoordinator}): the loopback HTTP server, the detected-browser list, and the
 * browser picker, plus the logic that (un)attaches the floating "open in browser" globe and serves a file.
 * It reaches the window only through the narrow {@link CoordinatorHost} interface; {@code MainController} keeps
 * one-line delegations at the call sites.
 *
 * <p>A preview serves a whole site folder ({@link LivePreviewServer#siteRoot}): the project root for a file in
 * the window's project, else the nearest folder up holding an {@code index.html}. Only local files are served.
 * While anything is served, every open buffer reports its settled edits ({@link #onBufferEdited}), saves and
 * closes, and those under a served site reload its pages.
 */
final class HtmlPreviewCoordinator {

    private final CoordinatorHost host;
    private final HtmlPreviewService service;
    private List<Browser> browsers = List.of();
    private @Nullable QuickOpen<Browser> browserPalette; // built lazily on first "Open in…"

    HtmlPreviewCoordinator(CoordinatorHost host) {
        this.host = host;
        this.service = new HtmlPreviewService(host::openExternalUrl);
        service.setServerGoneMessage(tr("htmlPreview.serverGone"));
    }

    /** Whether HTML Live Preview is enabled in Settings (on by default). */
    boolean isEnabled() {
        return host.settings().isHtmlPreviewSupport();
    }

    /**
     * Reconciles the feature with its setting (mirrors {@code applyMermaidSupport}): (un)attaches the floating
     * "open in browser" globe on open HTML buffers, re-detects installed browsers when enabled (one installed
     * since shows up), and stops the HTTP server when disabled. Runs at startup and on every settings apply.
     */
    void applySupport() {
        host.forEachBuffer(this::ensureControl);
        if (isEnabled()) {
            service.detectBrowsers(list -> browsers = list);
        } else {
            browsers = List.of();
            service.stopServer(); // stop serving + clear the preview; the service stays reusable
            watchEdits(false);
        }
    }

    /**
     * Per-buffer wiring at creation: the globe follows a language that arrives later (a modeline, a sniffed or
     * picked language), and the buffer reports its settled edits while a preview is being served.
     */
    void wireBuffer(EditorBuffer buffer) {
        buffer.addLanguageListener(() -> ensureControl(buffer));
        if (service.isServing()) {
            buffer.setHtmlPreviewDirtyListener(() -> onBufferEdited(buffer));
        }
    }

    /** Attaches/removes the floating browser globe so it shows only on local HTML buffers with the feature on. */
    void ensureControl(EditorBuffer buffer) {
        boolean want = isEnabled() && buffer.isHtml() && host.isLocalBuffer(buffer);
        boolean has = buffer.hasHtmlPreviewControl();
        if (want && !has) {
            buffer.setHtmlPreviewControl(new HtmlPreviewToggle(
                    () -> browsers, HtmlPreviewCoordinator::label, b -> previewActive(buffer, b)));
        } else if (!want && has) {
            buffer.setHtmlPreviewControl(null);
        }
    }

    /** The settled-edit pulse of any buffer: a file under a served site reloads its pages (CSS: a swap). */
    void onBufferEdited(EditorBuffer buffer) {
        Path path = buffer.getPath();
        if (isEnabled() && path != null && service.serves(path)) {
            service.edited(path, buffer.getContent());
        }
    }

    /** A save reloads too (an edit the pulse has not reported yet; a no-op when the text is what is served). */
    void onBufferSaved(EditorBuffer buffer) {
        onBufferEdited(buffer);
    }

    /** A closed buffer's file is served from disk again, and its site reloads. */
    void onBufferClosed(EditorBuffer buffer) {
        Path path = buffer.getPath();
        if (path != null) {
            service.closed(path);
        }
    }

    /** Stops the HTTP server + worker (window dispose). */
    void shutdown() {
        service.shutdown();
    }

    /** The menu/label for a browser; localizes the System Default entry, else uses the browser's name. */
    static String label(Browser browser) {
        return Browsers.SYSTEM_DEFAULT.equals(browser.id()) ? tr("htmlPreview.systemDefault") : browser.displayName();
    }

    /** The status line for a preview's {@code result} in {@code browserName}. */
    static String statusFor(HtmlPreviewService.Result result, String browserName) {
        String url = result.url() == null ? "" : result.url();
        String detail = result.detail() == null ? "" : result.detail();
        return switch (result.outcome()) {
            case OPENED -> tr("status.htmlPreview.opened", url);
            case FALLBACK -> tr("status.htmlPreview.fallback", browserName, url);
            case SERVER_FAILED -> tr("status.htmlPreview.serverFailed", detail);
            case LAUNCH_FAILED -> tr("status.htmlPreview.failed", browserName + ": " + detail);
            case EXITED -> tr("status.htmlPreview.exited", browserName, detail, url);
        };
    }

    /** Whether {@code buffer} can be previewed; reports why not. Only local files: assets are read from disk. */
    private boolean previewable(@Nullable EditorBuffer buffer) {
        if (buffer == null || !buffer.isHtml()) {
            host.setStatus(tr("status.htmlPreview.notHtml"));
            return false;
        }
        Path file = buffer.getPath();
        if (!host.isLocalBuffer(buffer) || (file != null && !Vfs.isLocal(file))) {
            host.setStatus(tr("status.htmlPreview.remote"));
            return false;
        }
        return true;
    }

    /** Serves {@code buffer}'s site (its own text live) and opens it in {@code browser}; remembers the choice. */
    private void previewActive(EditorBuffer buffer, Browser browser) {
        if (!previewable(buffer)) {
            return;
        }
        Path file = buffer.getPath();
        if (file == null) {
            host.setStatus(tr("status.htmlPreview.unsaved")); // need a file on disk so relative assets resolve
            return;
        }
        Path root = LivePreviewServer.siteRoot(file, host.projectScope().root());
        if (LivePreviewServer.isUnsafeDocRoot(root)) {
            // The server exposes the whole folder; a home, Downloads, temp or drive root is not a web root.
            host.setStatus(tr("status.htmlPreview.unsafeRoot", root));
            return;
        }
        host.settings().setHtmlPreviewBrowser(browser.id());
        host.save();
        String name = label(browser);
        host.setStatus(tr("status.htmlPreview.opening", name));
        watchEdits(true); // from now on: an edit made before the server is up still reaches the snapshot
        service.preview(file, root, buffer.getContent(), browser, r -> {
            String status = statusFor(r, name);
            if (!service.isServing()) {
                watchEdits(false);
            }
            if (r.ok()) {
                host.setStatus(status);
            } else {
                host.setError(status);
            }
        });
    }

    /** (Un)wires every buffer's settled-edit report: only while a preview is served is there anything to reload. */
    private void watchEdits(boolean on) {
        host.forEachBuffer(b -> b.setHtmlPreviewDirtyListener(on ? () -> onBufferEdited(b) : null));
    }

    /** The last-used browser (from Settings), else System Default, else the first detected. */
    private Browser lastUsedBrowser() {
        String id = host.settings().getHtmlPreviewBrowser();
        for (Browser b : browsers) {
            if (b.id().equals(id)) {
                return b;
            }
        }
        for (Browser b : browsers) {
            if (Browsers.SYSTEM_DEFAULT.equals(b.id())) {
                return b;
            }
        }
        return browsers.isEmpty() ? new Browser(Browsers.SYSTEM_DEFAULT, "System Default") : browsers.get(0);
    }

    // --- commands ---

    /** {@code htmlPreview.open}: open the active HTML file in the last-used / default browser. */
    void open() {
        ifEnabled(() -> {
            EditorBuffer b = host.activeBuffer();
            if (previewable(b) && b != null) {
                previewActive(b, lastUsedBrowser());
            }
        });
    }

    /** {@code htmlPreview.openIn}: pick a detected browser, then open the active HTML file in it. */
    void openIn() {
        ifEnabled(() -> {
            if (previewable(host.activeBuffer())) {
                browserPalette().show(host.window());
            }
        });
    }

    /** {@code view.toggleHtmlPreview}: flip the feature, re-apply, re-sync the Settings checkbox. */
    void toggle() {
        Settings s = host.settings();
        s.setHtmlPreviewSupport(!s.isHtmlPreviewSupport());
        host.save();
        applySupport();
        host.syncSettingsWindow();
        host.setStatus(tr("status.toggle.htmlPreview", tr(s.isHtmlPreviewSupport() ? "common.on" : "common.off")));
    }

    private QuickOpen<Browser> browserPalette() {
        if (browserPalette == null) {
            browserPalette = new QuickOpen<>(
                    tr("htmlPreview.pickBrowser"),
                    tr("htmlPreview.pickBrowser.prompt"),
                    () -> new ArrayList<>(browsers),
                    HtmlPreviewCoordinator::label,
                    b -> "",
                    b -> {
                        EditorBuffer active = host.activeBuffer();
                        if (active != null && active.isHtml()) {
                            previewActive(active, b);
                        }
                    });
            browserPalette.setOverlayHost(host.overlayHost());
        }
        return browserPalette;
    }

    /** Runs {@code action} only when HTML Live Preview is enabled; otherwise reports it (no-op). */
    private void ifEnabled(Runnable action) {
        if (isEnabled()) {
            action.run();
        } else {
            host.setStatus(tr("statusbar.tip.htmlPreviewDisabled"));
        }
    }
}
