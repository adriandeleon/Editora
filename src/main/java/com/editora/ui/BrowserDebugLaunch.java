package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.application.Platform;

import com.editora.config.Settings;
import com.editora.dap.DebugLanguages;
import com.editora.dap.FirefoxLaunch;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Debugging in a browser, a part of {@link DebugCoordinator}: Debug in Browser (the active HTML file, a page debugged
 * before, a URL), F5 on an HTML file, Attach to Browser, and a launch.json {@code firefox} configuration. A Chromium
 * browser — Chrome, Edge, Chromium, Brave, Vivaldi — runs under vscode-js-debug; Firefox under vscode-firefox-debug
 * ({@link DebugLanguages#FIREFOX}, {@link FirefoxLaunch}), when it is the browser Settings → Debugging names or the
 * only one found.
 */
final class BrowserDebugLaunch {

    private final DebugCoordinator d; // its host and ops are read when used: this is made with the coordinator

    BrowserDebugLaunch(DebugCoordinator d) {
        this.d = d;
    }

    /** Looks for vscode-firefox-debug (as configured, else Editora's install or VS Code's extension). */
    void configure(boolean on, Settings s, ProjectScope scope, Runnable found) {
        d.dap()
                .configureExternal(
                        DebugLanguages.FIREFOX,
                        on && s.isFirefoxDebugEnabled(),
                        scope.debugTool(FirefoxLaunch.TYPE, s.getFirefoxDebugPath()),
                        f -> found.run());
    }

    /** Whether Firefox can be debugged in: its debugger found and on; else said why, and false. */
    boolean firefoxReady() {
        if (d.debugSupportEnabled() && d.dap().isExternalLocated(DebugLanguages.FIREFOX)) {
            return true;
        }
        d.host.setStatus(tr("status.debug.firefoxMissing"));
        return false;
    }

    /**
     * Starts launch.json configuration {@code name} of type {@code firefox} ({@code body}): a page opened in Firefox, or
     * a Firefox attached to — Editora's Firefox profile and the workspace as its {@code webRoot} unless it says
     * otherwise ({@link FirefoxLaunch#configured}).
     */
    void startConfigured(Path workspace, String name, Path anchor, java.util.Map<String, Object> body) {
        if (!firefoxReady()) {
            return;
        }
        java.util.Map<String, Object> request = FirefoxLaunch.configured(
                body, workspace, browserProfile(FirefoxLaunch.TYPE), ElectronDebugLaunch.freePort());
        d.ops.openToolWindow();
        d.nameSession(name);
        d.s().relaunch = null;
        d.withClosedBreakpoints(() -> d.dap().startExternal(anchor, DebugLanguages.FIREFOX, request));
    }

    /** The browsers found on this machine, by {@link com.editora.web.Browsers} id (run off the FX thread). */
    java.util.function.Supplier<List<String>> browserIds = () -> com.editora.web.Browsers.detect().stream()
            .map(com.editora.web.Browsers.Browser::id)
            .toList();

    /** A browser's program, for the debugger to start ({@link com.editora.web.Browsers#executable}). */
    java.util.function.Function<String, @Nullable String> browserExecutable = com.editora.web.Browsers::executable;

    /** More command-line arguments for a debugged browser (a test runs it headless). */
    List<String> browserArgs = List.of();

    /** More command-line arguments for a debugged Firefox ({@code -headless} in a test). */
    List<String> firefoxArgs = List.of();

    /** More of vscode-firefox-debug's launch options (a test's {@code log}, to see what it did). */
    java.util.Map<String, Object> firefoxOptions = java.util.Map.of();

    /** The Debug in Browser picker's row for the active HTML file (no URL can look like it). */
    private static final String THIS_PAGE_ROW = "\u0000page";

    /**
     * Debug: Debug in Browser… — opens a page in Chrome, Edge or another Chromium browser under the JavaScript
     * debugger: the active HTML file (served as the live preview serves it), a page debugged before, or a URL typed
     * in (a dev server's {@code localhost:5173}). Breakpoints in the page's scripts and in the project's sources
     * (through source maps) stop it; Stop closes the browser.
     */
    void debugInBrowser() {
        if (!d.debugEffectiveFor("javascript")) {
            d.host.setStatus(d.unavailableMessage("javascript", null));
            return;
        }
        EditorBuffer active = d.host.activeBuffer();
        EditorBuffer b = active != null && active.isHtml() ? active : null;
        boolean page = b != null;
        List<String> earlier = d.ops.debugBrowserUrls();
        if (!page && earlier.isEmpty()) {
            promptBrowserUrl("");
            return;
        }
        List<String> rows = new ArrayList<>();
        if (page) {
            rows.add(THIS_PAGE_ROW);
        }
        rows.addAll(earlier);
        rows.add(""); // "Another page…"
        String name = b == null ? "" : java.util.Objects.requireNonNullElse(b.getDisplayName(), "");
        QuickOpen<String> picker = new QuickOpen<>(
                tr("debug.browser.title"),
                tr("debug.browser.pickPrompt"),
                () -> rows,
                r -> r.isEmpty()
                        ? tr("debug.browser.another")
                        : THIS_PAGE_ROW.equals(r) ? tr("debug.browser.thisPage", name) : r,
                r -> "",
                r -> {
                    if (r == null) {
                        return;
                    }
                    if (r.isEmpty()) {
                        promptBrowserUrl(earlier.isEmpty() ? "" : earlier.getFirst());
                    } else if (THIS_PAGE_ROW.equals(r) && b != null) {
                        debugHtmlPage(b);
                    } else {
                        openInBrowser(r, null, null);
                    }
                });
        picker.setOverlayHost(d.host.overlayHost());
        picker.show(d.host.window());
    }

    /** Asks for the URL of a page to debug, pre-filled with {@code initial} (a dev server's address when blank). */
    private void promptBrowserUrl(String initial) {
        d.host.promptText(
                tr("debug.browser.title"),
                tr("debug.browser.urlPrompt"),
                initial.isBlank() ? "http://localhost:3000" : initial,
                input -> {
                    if (input == null) {
                        return; // cancelled
                    }
                    java.util.Optional<String> url = com.editora.dap.BrowserLaunch.url(input);
                    if (url.isEmpty()) {
                        d.host.setStatus(tr("status.debug.badUrl", input.strip()));
                        return;
                    }
                    openInBrowser(url.get(), null, null);
                });
    }

    /**
     * Debugs an HTML file's page in a browser (F5 on it): served as the live preview serves it, so its scripts,
     * modules and stylesheets load as on a web server, its text live; a {@code file:} URL while the preview is off.
     */
    void debugHtmlPage(EditorBuffer b) {
        if (!d.debugEffectiveFor("javascript")) {
            d.host.setStatus(d.unavailableMessage("javascript", null));
            return;
        }
        if (b.getPath() == null) { // saved first, off the FX thread (PERF-01)
            d.savedOrThen(List.of(b), () -> debugHtmlPage(b));
            return;
        }
        Path file = b.getPath();
        if (file == null) {
            return;
        }
        d.ops.servePage(b, served -> {
            if (served != null) {
                openInBrowser(served.url(), served.root(), file);
            }
        });
    }

    /**
     * Opens {@code url} in the browser the Debugging setting names (else one found: {@link
     * com.editora.dap.BrowserLaunch#pick}) under the debugger. {@code servedRoot} is the folder Editora serves the
     * page from (null for any other URL), {@code pageFile} the HTML file it is (null for a URL typed in, which is
     * remembered).
     */
    private void openInBrowser(String url, @Nullable Path servedRoot, @Nullable Path pageFile) {
        d.host.setStatus(tr("status.debug.findingBrowser"));
        String chosen = d.host.settings().getDebugBrowser();
        String preview = d.host.settings().getHtmlPreviewBrowser();
        Thread.ofVirtual().name("editora-debug-browser").start(() -> {
            List<String> found;
            try {
                found = browserIds.get();
            } catch (RuntimeException e) {
                found = List.of();
            }
            String id = com.editora.dap.BrowserLaunch.pick(found, chosen, preview);
            String exe = id == null ? null : browserExecutable.apply(id);
            Platform.runLater(() -> launchBrowser(url, servedRoot, pageFile, chosen, id, exe));
        });
    }

    private void launchBrowser(
            String url,
            @Nullable Path servedRoot,
            @Nullable Path pageFile,
            String chosen,
            @Nullable String id,
            @Nullable String exe) {
        String type = id == null ? null : com.editora.dap.BrowserLaunch.typeFor(id);
        if (id == null || type == null) {
            d.host.setError(tr("status.debug.noBrowser"));
            return;
        }
        Path project = d.ops.projectRoot();
        Path webRoot = servedRoot != null
                ? servedRoot
                : project != null ? project : pageFile != null ? pageFile.getParent() : null;
        com.editora.dap.BrowserLaunch.Served served = servedRoot == null
                ? null
                : com.editora.dap.BrowserLaunch.Served.of(url, servedRoot).orElse(null);
        boolean firefox = FirefoxLaunch.TYPE.equals(type);
        if (firefox && !firefoxReady()) {
            return; // said why
        }
        java.util.Map<String, Object> planned = firefox
                ? FirefoxLaunch.launch(
                        url, webRoot, served, exe, browserProfile(id), firefoxArgs, ElectronDebugLaunch.freePort())
                : com.editora.dap.BrowserLaunch.launch(
                        type, url, webRoot, served, exe, browserProfile(id), browserArgs);
        java.util.Map<String, Object> request = new java.util.LinkedHashMap<>(planned);
        if (firefox) {
            request.putAll(firefoxOptions);
        }
        if (pageFile == null) {
            d.ops.persistDebugBrowserUrls(DebugAttach.rememberAttachTarget(d.ops.debugBrowserUrls(), url));
        }
        String browser = com.editora.web.Browsers.knownName(id);
        d.ops.openToolWindow();
        d.nameSession(tr("debug.browser.session", browser, com.editora.dap.BrowserLaunch.label(url, pageFile)));
        d.s().relaunch = null; // Restart opens the same page again
        if (!chosen.isBlank() && !chosen.equals(id)) {
            d.host.setStatus(tr("status.debug.browserFallback", com.editora.web.Browsers.knownName(chosen), browser));
        } else {
            d.host.setStatus(tr("status.debug.browserOpening", browser));
        }
        Path anchor = pageFile != null ? pageFile : webRoot;
        d.withClosedBreakpoints(() -> {
            if (firefox) { // vscode-firefox-debug, its own adapter: the page's files' breakpoints go to it
                Path file = anchor != null ? anchor : Path.of(System.getProperty("user.home")); // a URL typed in
                d.dap().startExternal(file, DebugLanguages.FIREFOX, request);
            } else {
                d.dap().startBrowser(anchor, request);
            }
        });
    }

    /**
     * The browser profile Editora keeps for debugging in browser {@code id}, apart from the everyday one; null (js-debug
     * keeps its own) without a config folder.
     */
    @Nullable
    Path browserProfile(String id) {
        Path config = d.host.configDir();
        return config == null ? null : config.resolve("browser-debug").resolve(id);
    }

    /**
     * Debug: Attach to Browser… — asks for the debugging port of a Chrome or Edge started with
     * {@code --remote-debugging-port} (9222) and attaches the JavaScript debugger to its pages. Stop detaches and
     * leaves the browser open.
     */
    void debugAttachToBrowser() {
        if (!d.debugEffectiveFor("javascript")) {
            d.host.setStatus(d.unavailableMessage("javascript", null));
            return;
        }
        // Firefox when it is the browser Settings → Debugging names: its debugger server, on its own adapter.
        boolean firefox = FirefoxLaunch.TYPE.equals(d.host.settings().getDebugBrowser());
        if (firefox && !firefoxReady()) {
            return; // said why
        }
        d.host.promptText(
                tr("debug.browser.attachTitle"),
                tr(firefox ? "debug.browser.firefoxAttachPrompt" : "debug.browser.attachPrompt"),
                "localhost:" + (firefox ? FirefoxLaunch.DEFAULT_PORT : com.editora.dap.BrowserLaunch.DEFAULT_PORT),
                input -> {
                    if (input == null) {
                        return;
                    }
                    var address = com.editora.dap.JsLaunch.parseAddress(input);
                    if (address.isEmpty()) {
                        d.host.setStatus(tr("status.debug.badAddress", input.strip()));
                        return;
                    }
                    String label = address.get().host() + ":" + address.get().port();
                    Path root = d.ops.projectRoot();
                    java.util.Map<String, Object> request = firefox
                            ? FirefoxLaunch.attach(
                                    address.get().host(), address.get().port(), root)
                            : com.editora.dap.BrowserLaunch.attach(
                                    com.editora.dap.BrowserLaunch.CHROME,
                                    address.get().host(),
                                    address.get().port(),
                                    root);
                    d.ops.openToolWindow();
                    d.nameSession(tr("debug.browser.attachSession", label));
                    d.s().relaunch = null;
                    d.withClosedBreakpoints(() -> {
                        if (firefox) {
                            Path anchor = root != null ? root : Path.of(System.getProperty("user.home"));
                            d.dap().startExternal(anchor, DebugLanguages.FIREFOX, request);
                        } else {
                            d.dap().startBrowser(root, request);
                        }
                    });
                });
    }
}
