package com.editora.web;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.application.Platform;

import com.editora.process.ProcessRunner;
import com.editora.web.Browsers.Browser;

/**
 * UI-facing façade for the HTML Live Preview, mirroring {@code MermaidService}: work runs on a single daemon
 * executor and results are posted back on the JavaFX thread via {@link Platform#runLater}. Owns the one
 * {@link LivePreviewServer} for this window (started lazily) and a cached browser-detection probe.
 *
 * <p>{@link #preview} serves a file's folder (the file itself from its live in-memory text) and opens it in
 * the chosen browser — launched <em>detached</em> (we don't wait on it, so a foreground browser process on
 * Linux/Windows can't block the executor), or via the injected system opener ({@code HostServices}) for the
 * default browser. {@link #notifyChanged()} bumps the server version so open browsers live-reload.
 */
public final class HtmlPreviewService {

    /** Outcome of a {@link #preview} call: whether the browser was launched, the served URL, an error. */
    public record Result(boolean ok, String url, String message) {}

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "html-preview-service");
        t.setDaemon(true);
        return t;
    });

    private final LivePreviewServer server = new LivePreviewServer();
    /** Opens a URL in the OS default browser (injected — {@code HostServices.showDocument}); runs on FX. */
    private final Consumer<String> systemOpener;

    private volatile List<Browser> cachedBrowsers;
    private volatile Path previewedFile; // absolute, normalized; guards notifyChanged to the served file

    public HtmlPreviewService(Consumer<String> systemOpener) {
        this.systemOpener = systemOpener;
    }

    /** Detects installed browsers off-thread (cached), posting the list on the FX thread. */
    public void detectBrowsers(Consumer<List<Browser>> onResult) {
        List<Browser> hit = cachedBrowsers;
        if (hit != null) {
            Platform.runLater(() -> onResult.accept(hit));
            return;
        }
        exec.submit(() -> {
            List<Browser> list = Browsers.detect();
            cachedBrowsers = list;
            Platform.runLater(() -> onResult.accept(list));
        });
    }

    /**
     * Serves {@code file} (the file itself from {@code liveText}) and opens it in {@code browser}; posts the
     * {@link Result} on the FX thread. The server starts on first use and rebinds to the file's folder.
     */
    public void preview(Path file, Supplier<String> liveText, Browser browser, Consumer<Result> onResult) {
        exec.submit(() -> {
            Result result;
            try {
                server.start();
                server.setPreview(file, liveText);
                previewedFile = file.toAbsolutePath().normalize();
                String url = server.previewUrl();
                if (Browsers.SYSTEM_DEFAULT.equals(browser.id())) {
                    openWithSystem(url);
                    result = new Result(true, url, null);
                } else {
                    List<String> argv = Browsers.launchArgv(browser, url);
                    if (argv.isEmpty()) {
                        openWithSystem(url); // browser vanished between detect and launch — use the default
                        result = new Result(true, url, "fallback-default");
                    } else {
                        launchDetached(argv);
                        result = new Result(true, url, null);
                    }
                }
            } catch (IOException e) {
                result = new Result(false, null, e.getMessage() == null ? "failed to start" : e.getMessage());
            }
            Result posted = result;
            Platform.runLater(() -> onResult.accept(posted));
        });
    }

    /** True when {@code file} is the file currently served (so its edit pulse should reload the browser). */
    public boolean isPreviewing(Path file) {
        Path p = previewedFile;
        return p != null && file != null && p.equals(file.toAbsolutePath().normalize());
    }

    /** Bumps the server version so every open browser live-reloads (no-op when the server isn't running). */
    public void notifyChanged() {
        if (server.isRunning()) {
            server.bumpVersion();
        }
    }

    /** Stops the HTTP server + clears the preview, but keeps the service usable (the feature was disabled). */
    public void stopServer() {
        server.stop();
        previewedFile = null;
    }

    /** Stops the server + the background thread (called when the owning window closes). */
    public void shutdown() {
        stopServer();
        exec.shutdownNow();
    }

    private void openWithSystem(String url) {
        Platform.runLater(() -> systemOpener.accept(url));
    }

    /** Launches the browser without waiting (fire-and-forget) so a foreground process can't stall us. */
    private static void launchDetached(List<String> argv) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(ProcessRunner.resolveExecutable(argv));
        ProcessRunner.applyStandardEnv(pb);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.start();
    }
}
