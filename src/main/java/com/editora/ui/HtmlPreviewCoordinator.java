package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.stage.Window;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.web.Browsers;
import com.editora.web.Browsers.Browser;
import com.editora.web.HtmlPreviewService;

import static com.editora.i18n.Messages.tr;

/**
 * Owns the HTML Live Preview feature, extracted from {@code MainController} as a feature coordinator (the
 * second after {@link LogViewerCoordinator}): the loopback HTTP server, the detected-browser list, and the
 * browser picker, plus the logic that (un)attaches the floating "open in browser" globe and serves a file.
 * It reaches the window only through the narrow {@link Host} interface; {@code MainController} supplies an
 * anonymous adapter and keeps one-line delegations at the call sites.
 */
final class HtmlPreviewCoordinator {

    /** The window services the coordinator needs — implemented by {@code MainController} (or a test fake). */
    interface Host {
        Settings settings();

        void forEachBuffer(Consumer<EditorBuffer> action);

        EditorBuffer activeBuffer();

        /** Whether {@code buffer} is a local-filesystem file (HTML preview serves sibling assets from disk). */
        boolean isLocalBuffer(EditorBuffer buffer);

        void setStatus(String message);

        /** Durable config save (persists the last-used browser / the feature toggle). */
        void save();

        /** Re-syncs an open Settings window's HTML-preview checkbox after the toggle flips via the palette. */
        void syncSettingsWindow();

        OverlayHost overlayHost();

        Window window();

        /** Opens {@code url} via the platform's default handler (passed to the preview server). */
        void openExternalUrl(String url);
    }

    private final Host host;
    private final HtmlPreviewService service;
    private List<Browser> browsers = List.of();
    private QuickOpen<Browser> browserPalette; // built lazily on first "Open in…"

    HtmlPreviewCoordinator(Host host) {
        this.host = host;
        this.service = new HtmlPreviewService(host::openExternalUrl);
    }

    /** Whether HTML Live Preview is enabled in Settings (default off). */
    boolean isEnabled() {
        return host.settings().isHtmlPreviewSupport();
    }

    /**
     * Reconciles the feature with its setting (mirrors {@code applyMermaidSupport}): (un)attaches the floating
     * "open in browser" globe on open HTML buffers, detects installed browsers when enabled, and stops the HTTP
     * server when disabled. Runs at startup and on every settings apply.
     */
    void applySupport() {
        host.forEachBuffer(this::ensureControl);
        if (isEnabled()) {
            service.detectBrowsers(list -> browsers = list);
        } else {
            browsers = List.of();
            service.stopServer(); // stop serving + clear the preview; the service stays reusable
        }
    }

    /** Attaches/removes the floating browser globe so it shows only on local HTML buffers with the feature on. */
    void ensureControl(EditorBuffer buffer) {
        boolean want = isEnabled() && buffer.isHtml() && host.isLocalBuffer(buffer);
        boolean has = buffer.hasHtmlPreviewControl();
        if (want && !has) {
            buffer.setHtmlPreviewControl(
                    new HtmlPreviewToggle(() -> browsers, this::browserLabel, b -> previewActive(buffer, b)));
        } else if (!want && has) {
            buffer.setHtmlPreviewControl(null);
        }
    }

    /** The debounced edit pulse reloads the browser, but only while this file is the one currently served. */
    void onBufferEdited(EditorBuffer buffer) {
        if (isEnabled() && service.isPreviewing(buffer.getPath())) {
            service.notifyChanged();
        }
    }

    /** Stops the HTTP server + worker (window dispose). */
    void shutdown() {
        service.shutdown();
    }

    /** The menu/label for a browser; localizes the System Default entry, else uses the browser's name. */
    private String browserLabel(Browser browser) {
        return Browsers.SYSTEM_DEFAULT.equals(browser.id()) ? tr("htmlPreview.systemDefault") : browser.displayName();
    }

    /** Serves {@code buffer}'s file (live text) and opens it in {@code browser}; remembers the choice. */
    private void previewActive(EditorBuffer buffer, Browser browser) {
        Path file = buffer.getPath();
        if (file == null) {
            host.setStatus(tr("status.htmlPreview.unsaved")); // need a file on disk so relative assets resolve
            return;
        }
        host.settings().setHtmlPreviewBrowser(browser.id());
        host.save();
        host.setStatus(tr("status.htmlPreview.opening", browserLabel(browser)));
        service.preview(file, buffer::getContent, browser, r -> {
            if (r.ok()) {
                host.setStatus(tr("status.htmlPreview.opened", r.url()));
            } else {
                host.setStatus(tr("status.htmlPreview.failed", r.message() == null ? "" : r.message()));
            }
        });
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
            if (b == null || !b.isHtml()) {
                host.setStatus(tr("status.htmlPreview.notHtml"));
                return;
            }
            previewActive(b, lastUsedBrowser());
        });
    }

    /** {@code htmlPreview.openIn}: pick a detected browser, then open the active HTML file in it. */
    void openIn() {
        ifEnabled(() -> {
            EditorBuffer b = host.activeBuffer();
            if (b == null || !b.isHtml()) {
                host.setStatus(tr("status.htmlPreview.notHtml"));
                return;
            }
            browserPalette().show(host.window());
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
                    this::browserLabel,
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
