package com.editora.web;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.process.BusyExecutable;
import com.editora.process.ProcessRunner;
import com.editora.web.Browsers.Browser;
import org.jspecify.annotations.Nullable;

/**
 * UI-facing façade for the HTML Live Preview, mirroring {@code MermaidService}: work runs on a single daemon
 * executor and results are posted back on the JavaFX thread via {@link Platform#runLater}. Owns the one
 * {@link LivePreviewServer} for this window (started lazily) and the browser-detection probe.
 *
 * <p>{@link #preview} serves a site folder and opens a page of it in the chosen browser — launched
 * <em>detached</em> (we don't wait on it, so a foreground browser process on Linux/Windows can't block the
 * executor), or via the injected system opener ({@code HostServices}) for the default browser. Files open in
 * the editor are served from immutable snapshots of their text taken on the FX thread ({@link #edited}), and
 * every edit, save or close of a file under a served site reloads that site's open pages.
 */
public final class HtmlPreviewService {

    /** What became of a {@link #preview} call. */
    public enum Outcome {
        /** The page was opened in the chosen browser. */
        OPENED,
        /** The chosen browser was not found, so the page was opened in the system default one. */
        FALLBACK,
        /** The server could not start or refused the folder; nothing was opened. */
        SERVER_FAILED,
        /** The browser process could not be started. */
        LAUNCH_FAILED,
        /** The browser started but quit at once with an error (posted after {@link #OPENED}). */
        EXITED
    }

    /** Outcome of a {@link #preview} call: the outcome, the served URL (when serving), a detail (error text). */
    public record Result(
            Outcome outcome, @Nullable String url, @Nullable String detail) {
        public boolean ok() {
            return outcome == Outcome.OPENED || outcome == Outcome.FALLBACK;
        }
    }

    /** Starts a browser process from its argv (the real one launches it detached; tests stub it). */
    @FunctionalInterface
    interface Launcher {
        Process launch(List<String> argv) throws IOException;
    }

    /** A browser exiting with an error within this long of its launch is reported as {@link Outcome#EXITED}. */
    private static final long EARLY_EXIT_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "html-preview-service");
        t.setDaemon(true);
        return t;
    });

    private final LivePreviewServer server = new LivePreviewServer();
    /** Opens a URL in the OS default browser (injected — {@code HostServices.showDocument}); runs on FX. */
    private final Consumer<String> systemOpener;

    private final Launcher launcher;
    /** The editor text of open files under a served site, by absolute normalized path (replaced, never mutated). */
    private final Map<Path, String> live = new ConcurrentHashMap<>();
    /** The served site roots (absolute, normalized), added on the FX thread as soon as a preview is asked for. */
    private final Set<Path> roots = ConcurrentHashMap.newKeySet();
    /** Advanced by {@link #stopServer}: a preview queued before the stop must not restart anything. */
    private final AtomicLong epoch = new AtomicLong();

    public HtmlPreviewService(Consumer<String> systemOpener) {
        this(systemOpener, argv -> BusyExecutable.start(browserProcess(argv)));
    }

    HtmlPreviewService(Consumer<String> systemOpener, Launcher launcher) {
        this.systemOpener = systemOpener;
        this.launcher = launcher;
        server.setLiveText(live::get);
    }

    /** The note a preview page shows once its server has gone (localized by the caller). */
    public void setServerGoneMessage(String message) {
        server.setGoneMessage(message);
    }

    /** Detects installed browsers off-thread (afresh each time: one installed since may now be there). */
    public void detectBrowsers(Consumer<List<Browser>> onResult) {
        exec.submit(() -> {
            List<Browser> list = Browsers.detect();
            Platform.runLater(() -> onResult.accept(list));
        });
    }

    /**
     * Serves {@code root} (a safe folder holding {@code file}; the caller picked and checked it), with {@code file}
     * itself from {@code liveText}, and opens {@code file}'s page in {@code browser}. Posts the {@link Result} on
     * the FX thread — and, when the browser quits at once with an error, an {@link Outcome#EXITED} one after it.
     */
    public void preview(Path file, Path root, String liveText, Browser browser, Consumer<Result> onResult) {
        long at = epoch.get();
        Path abs = normalized(file);
        Path site = normalized(root);
        live.put(abs, liveText == null ? "" : liveText);
        roots.add(site); // edits right after this (before the worker runs) already update the snapshot
        exec.submit(() -> {
            if (at != epoch.get()) {
                return;
            }
            String url;
            try {
                server.start();
                if (at != epoch.get()) {
                    server.stop();
                    return;
                }
                url = server.serve(site, abs);
            } catch (IOException e) {
                roots.remove(site);
                post(at, onResult, new Result(Outcome.SERVER_FAILED, null, detail(e)));
                return;
            }
            Result result;
            if (Browsers.SYSTEM_DEFAULT.equals(browser.id())) {
                openWithSystem(url, at);
                result = new Result(Outcome.OPENED, url, null);
            } else {
                List<String> argv = Browsers.launchArgv(browser, url);
                if (argv.isEmpty()) {
                    openWithSystem(url, at); // the browser vanished between detection and launch
                    result = new Result(Outcome.FALLBACK, url, null);
                } else {
                    try {
                        Process p = launcher.launch(argv);
                        watchEarlyExit(p, url, at, onResult);
                        result = new Result(Outcome.OPENED, url, null);
                    } catch (IOException e) {
                        result = new Result(Outcome.LAUNCH_FAILED, url, detail(e));
                    }
                }
            }
            post(at, onResult, result);
        });
    }

    /** True when {@code file} lies under a served site (so its edits and saves reload the browser). */
    public boolean serves(Path file) {
        Path p = normalized(file);
        for (Path r : roots) {
            if (p.startsWith(r)) {
                return true;
            }
        }
        return false;
    }

    /** Whether any site is served (only then are edits worth watching). */
    public boolean isServing() {
        return !roots.isEmpty();
    }

    /**
     * Takes {@code text} as the served content of {@code file} (when it lies under a served site) and, when it
     * changed, reloads that site's open pages — or only swaps their stylesheets, for a {@code .css}. Safe to call
     * from the FX thread: it replaces an immutable snapshot and bumps a counter.
     */
    public void edited(Path file, String text) {
        Path p = normalized(file);
        if (!serves(p)) {
            return;
        }
        String now = text == null ? "" : text;
        String was = live.put(p, now);
        if (!now.equals(was)) {
            server.changed(p);
        }
    }

    /** A closed file is served from disk again; its site reloads (its unsaved edits are gone). */
    public void closed(Path file) {
        Path p = normalized(file);
        if (live.remove(p) != null) {
            server.changed(p);
        }
    }

    /** Stops the HTTP server and forgets every site, but keeps the service usable (the feature was disabled). */
    public void stopServer() {
        epoch.incrementAndGet();
        live.clear();
        roots.clear();
        try {
            exec.submit(server::stop);
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
            server.stop();
        }
    }

    /** Stops the server + the background thread (called when the owning window closes). */
    public void shutdown() {
        stopServer();
        exec.shutdown();
    }

    private void post(long at, Consumer<Result> onResult, Result result) {
        Platform.runLater(() -> {
            if (at == epoch.get()) {
                onResult.accept(result);
            }
        });
    }

    private void openWithSystem(String url, long at) {
        Platform.runLater(() -> {
            if (at == epoch.get()) {
                systemOpener.accept(url);
            }
        });
    }

    /**
     * Reports a browser that exits with an error soon after launch. One that exits cleanly at once is normal: it
     * handed the URL to an instance already running.
     */
    private void watchEarlyExit(Process p, String url, long at, Consumer<Result> onResult) {
        long started = System.nanoTime();
        p.onExit().thenAccept(done -> {
            int code = done.exitValue();
            if (code != 0 && System.nanoTime() - started < EARLY_EXIT_NANOS) {
                post(at, onResult, new Result(Outcome.EXITED, url, Integer.toString(code)));
            }
        });
    }

    private static String detail(IOException e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    private static Path normalized(Path p) {
        return p.toAbsolutePath().normalize();
    }

    /** The browser process, before it is started: the user's own locale (a browser forced into {@code
     *  LC_ALL=C} loses its UI language and mangles non-ASCII download names), plus the augmented PATH. */
    static ProcessBuilder browserProcess(List<String> argv) {
        ProcessBuilder pb = new ProcessBuilder(ProcessRunner.resolveExecutable(argv));
        ProcessRunner.applyUserEnv(pb);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        return pb;
    }
}
