package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.vfs.Vfs;
import com.editora.web.Browsers;
import com.editora.web.Browsers.Browser;
import com.editora.web.HtmlPreviewService;
import com.editora.web.LivePreviewServer;
import com.editora.web.SiteRoots;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Owns the HTML Live Preview feature, extracted from {@code MainController} as a feature coordinator (the
 * second after {@link LogViewerCoordinator}): the loopback HTTP server, the detected-browser list, and the
 * browser picker, plus the logic that (un)attaches the floating "open in browser" globe and serves a file.
 * It reaches the window only through the narrow {@link CoordinatorHost} interface; {@code MainController} keeps
 * one-line delegations at the call sites.
 *
 * <p>A preview serves a whole site folder ({@link SiteRoots#siteRoot}): the project root for a file in the
 * window's project, else the highest contiguous folder up holding an {@code index.html}. Only local files are
 * served, and never an encrypted one (ADR 0017): an encrypted buffer gets no globe and its text is never handed
 * to the server. While anything is served, every open buffer reports its settled edits ({@link #onBufferEdited}),
 * saves and closes, and those under a served site reload its pages — once it is edited: opening a file to read
 * it reloads nothing.
 */
final class HtmlPreviewCoordinator {

    private final CoordinatorHost host;
    private final HtmlPreviewService service;
    private List<Browser> browsers = List.of();
    /** Whether {@link #browsers} holds a finished detection; until then an Open waits for it ({@link #afterDetection}). */
    private boolean detected;

    private boolean detecting;
    private final List<Runnable> afterDetection = new ArrayList<>();
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
            detect();
        } else {
            browsers = List.of();
            detected = false;
            afterDetection.clear();
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

    /** Detects browsers off-thread, then runs whatever waited for the list (an Open pressed early). */
    private void detect() {
        detecting = true;
        service.detectBrowsers(list -> {
            browsers = list;
            detected = true;
            detecting = false;
            List<Runnable> waiting = new ArrayList<>(afterDetection);
            afterDetection.clear();
            waiting.forEach(Runnable::run);
        });
    }

    /** Runs {@code action} once browsers are detected (at once when they are), so a missing one can be told. */
    private void whenDetected(Runnable action) {
        if (detected) {
            action.run();
            return;
        }
        afterDetection.add(action);
        if (!detecting) {
            detect();
        }
    }

    /**
     * Attaches/removes the floating browser globe so it shows only on local, unencrypted HTML buffers with the
     * feature on (an encrypted page would be served decrypted to every script it runs: ADR 0017).
     */
    void ensureControl(EditorBuffer buffer) {
        boolean want = isEnabled() && buffer.isHtml() && host.isLocalBuffer(buffer) && !sensitive(buffer);
        boolean has = buffer.hasHtmlPreviewControl();
        if (want && !has) {
            buffer.setHtmlPreviewControl(new HtmlPreviewToggle(
                    () -> browsers, HtmlPreviewCoordinator::label, b -> previewActive(buffer, b)));
        } else if (!want && has) {
            buffer.setHtmlPreviewControl(null);
        }
    }

    /**
     * The settled-edit pulse of any buffer: a file under a served site reloads its pages (CSS: a swap). An
     * encrypted buffer's text is never served, nor a large one's (it is served from disk: no copy on the FX
     * thread); a file opened but not edited is already served from disk, so it reloads nothing (E103).
     */
    void onBufferEdited(EditorBuffer buffer) {
        Path path = buffer.getPath();
        if (!isEnabled() || path == null || !service.serves(path)) {
            return;
        }
        if (sensitive(buffer) || tooLargeToServeLive(buffer)) {
            service.closed(path); // drops a snapshot taken before (a no-op without one)
        } else if (buffer.isDirty() || service.hasSnapshot(path)) {
            service.edited(path, buffer.getContent());
        }
    }

    /** An encrypted buffer, or one whose name says it is encrypted (ADR 0017: every surface asks). */
    private static boolean sensitive(EditorBuffer buffer) {
        Path path = buffer.getPath();
        String name = path == null ? buffer.getDisplayName() : String.valueOf(path.getFileName());
        return buffer.encryption().isEncrypted()
                || name != null && com.editora.text.EncryptedNames.extensionOf(name) != null;
    }

    /** Large-file mode, or text over {@link LivePreviewServer#MAX_LIVE_CHARS}: served from disk instead (E111). */
    private static boolean tooLargeToServeLive(EditorBuffer buffer) {
        return buffer.isLargeFile() || buffer.getArea().getLength() > LivePreviewServer.MAX_LIVE_CHARS;
    }

    /**
     * A save reloads too: an edit the pulse has not reported yet (a no-op when the text is what is served), or a
     * file served from disk, which now holds the saved text.
     */
    void onBufferSaved(EditorBuffer buffer) {
        Path path = buffer.getPath();
        if (!isEnabled() || path == null || !service.serves(path)) {
            return;
        }
        if (sensitive(buffer) || tooLargeToServeLive(buffer) || !service.hasSnapshot(path)) {
            service.changedOnDisk(path);
        } else {
            service.edited(path, buffer.getContent());
        }
    }

    /** A closed buffer's file is served from disk again, and its site reloads. */
    void onBufferClosed(EditorBuffer buffer) {
        Path path = buffer.getPath();
        if (path != null) {
            service.closed(path);
        }
    }

    /**
     * {@code buffer}'s file is now its path, no longer {@code old} (Save As, Encrypt File, a Save As rolled back):
     * nothing is served under the old name from the editor any more, the globe follows the new name, and the new
     * path is served as any edited file is — unless it is encrypted (E201).
     */
    void pathChanged(EditorBuffer buffer, @Nullable Path old) {
        if (old != null) {
            service.moved(old);
        }
        ensureControl(buffer);
        onBufferEdited(buffer);
    }

    /**
     * A rename or move on disk ({@code old} → {@code target}: a file, or a folder and the files under it), after
     * this window's buffers were re-pointed: as {@link #pathChanged}, for every buffer now at or under {@code
     * target}.
     */
    void renamed(Path old, Path target) {
        service.moved(old);
        Path moved = target.toAbsolutePath().normalize();
        host.forEachBuffer(b -> {
            Path p = b.getPath();
            // a remote tab's path is on another file system: its startsWith(a local path) throws
            if (p != null
                    && p.getFileSystem() == moved.getFileSystem()
                    && p.toAbsolutePath().normalize().startsWith(moved)) {
                ensureControl(b);
                onBufferEdited(b);
            }
        });
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
        if (sensitive(buffer)) {
            host.setStatus(tr("status.htmlPreview.encrypted"));
            return false;
        }
        return true;
    }

    /**
     * The site folder to serve for {@code buffer}, or null after saying why not (no file yet, or an unsafe root).
     */
    private @Nullable Path siteFor(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null) {
            host.setStatus(tr("status.htmlPreview.unsaved")); // need a file on disk so relative assets resolve
            return null;
        }
        Path root = SiteRoots.siteRoot(file, host.projectScope().root());
        if (SiteRoots.isUnsafeDocRoot(root)) {
            // The server exposes the whole folder; a home, Downloads, temp or drive root is not a web root.
            host.setStatus(tr("status.htmlPreview.unsafeRoot", root));
            return null;
        }
        return root;
    }

    /**
     * Serves {@code buffer}'s site (its own text live) and opens it in {@code browser}; remembers the choice. A
     * remembered browser that is no longer installed opens the page in the system default one, says so, and stays
     * remembered (E107).
     */
    private void previewActive(EditorBuffer buffer, Browser browser) {
        if (!previewable(buffer)) {
            return;
        }
        Path root = siteFor(buffer);
        Path file = buffer.getPath();
        if (root == null || file == null) {
            return;
        }
        boolean missing = detected
                && !Browsers.SYSTEM_DEFAULT.equals(browser.id())
                && browsers.stream().noneMatch(b -> b.id().equals(browser.id()));
        if (!missing) {
            host.settings().setHtmlPreviewBrowser(browser.id());
            host.save();
        }
        String name = label(browser);
        Browser launch = missing ? new Browser(Browsers.SYSTEM_DEFAULT, "System Default") : browser;
        host.setStatus(tr("status.htmlPreview.opening", missing ? label(launch) : name));
        watchEdits(true); // from now on: an edit made before the server is up still reaches the snapshot
        String text = tooLargeToServeLive(buffer) ? null : buffer.getContent();
        service.preview(file, root, text, launch, done -> {
            HtmlPreviewService.Result r = missing && done.outcome() == HtmlPreviewService.Outcome.OPENED
                    ? new HtmlPreviewService.Result(HtmlPreviewService.Outcome.FALLBACK, done.url(), null)
                    : done;
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

    /**
     * Serves {@code buffer}'s site as a preview would (its text live, edits reloading the page) for the debugger to
     * open in a browser of its own: hands {@code onUrl} the page's URL and the folder served — the file's own
     * {@code file:} URL while the preview is off — or null after saying why not (the file is remote, encrypted,
     * unsaved, or in an unsafe folder).
     */
    void serveForDebug(EditorBuffer buffer, Consumer<@Nullable Served> onUrl) {
        Path page = buffer.getPath();
        if (!isEnabled() && page != null && host.isLocalBuffer(buffer) && Vfs.isLocal(page)) {
            onUrl.accept(new Served(page.toUri().toString(), null)); // no server: the file itself
            return;
        }
        if (!isEnabled() || !previewable(buffer)) {
            if (!isEnabled()) {
                host.setStatus(tr("status.htmlPreview.remote"));
            }
            onUrl.accept(null);
            return;
        }
        Path root = siteFor(buffer);
        Path file = buffer.getPath();
        if (root == null || file == null) {
            onUrl.accept(null);
            return;
        }
        watchEdits(true);
        String text = tooLargeToServeLive(buffer) ? null : buffer.getContent();
        service.serve(file, root, text, done -> {
            if (!service.isServing()) {
                watchEdits(false);
            }
            if (done.ok() && done.url() != null) {
                onUrl.accept(new Served(done.url(), root));
            } else {
                host.setError(statusFor(done, ""));
                onUrl.accept(null);
            }
        });
    }

    /** A page served for the debugger: its URL and the site folder behind it (null for a {@code file:} URL). */
    record Served(String url, @Nullable Path root) {}

    /** (Un)wires every buffer's settled-edit report: only while a preview is served is there anything to reload. */
    private void watchEdits(boolean on) {
        host.forEachBuffer(b -> b.setHtmlPreviewDirtyListener(on ? () -> onBufferEdited(b) : null));
    }

    /**
     * The last-used browser (from Settings) — named, not detected, when it is gone, so the preview says so — else
     * System Default, else the first detected.
     */
    private Browser lastUsedBrowser() {
        String id = host.settings().getHtmlPreviewBrowser();
        for (Browser b : browsers) {
            if (b.id().equals(id)) {
                return b;
            }
        }
        if (id != null && !id.isBlank() && !Browsers.SYSTEM_DEFAULT.equals(id)) {
            return new Browser(id, Browsers.knownName(id));
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
            if (previewable(b) && b != null && siteFor(b) != null) {
                whenDetected(() -> previewActive(b, lastUsedBrowser())); // so a browser that is gone is told
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
