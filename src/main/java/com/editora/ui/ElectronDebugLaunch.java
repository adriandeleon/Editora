package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.editora.dap.BrowserLaunch;
import com.editora.dap.LaunchJson;
import com.editora.run.ElectronApps;
import org.jspecify.annotations.Nullable;

/**
 * Electron launches of {@link DebugCoordinator}: Debug File on a main-process file of an Electron app ({@link
 * ElectronApps}) debugs the whole app as one: its main process under js-debug, on Electron's own program, and its
 * windows' renderer attached to through Electron's DevTools port — VS Code's "Electron: Main" and "Electron: Renderer"
 * configurations, started together as a compound whose Stop ends both. Breakpoints stop in main-process files and in
 * the pages' scripts alike. The app is looked for off the FX thread (PERF-01, Pauta 6.2.0). Part of {@link
 * DebugCoordinator}.
 */
final class ElectronDebugLaunch {

    /** The configurations' names, as VS Code's Electron recipe calls them. */
    static final String MAIN = "Electron: Main";

    static final String RENDERER = "Electron: Renderer";

    /** How long the renderer's attach waits for the app's window to be up, in milliseconds. */
    static final int ATTACH_TIMEOUT_MILLIS = 30_000;

    private final DebugCoordinator d; // its host and ops are read when used: this is made with the coordinator

    ElectronDebugLaunch(DebugCoordinator d) {
        this.d = d;
    }

    /** {@code file}'s text, or null when it cannot be read. */
    static @Nullable String read(Path file) {
        try {
            return Files.readString(file);
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    /** The app {@code file} starts, looked for on this machine's disk (off the FX thread). */
    static Optional<ElectronApps.App> appOf(Path file) {
        if (!com.editora.process.ExecTargets.runsHere(file)) {
            return Optional.empty(); // Electron needs this machine's display
        }
        String text = read(file);
        return ElectronApps.of(file, text == null ? "" : text, Files::isRegularFile, ElectronDebugLaunch::read);
    }

    /**
     * Debug File on JavaScript file {@code file} with program arguments {@code args}: the Electron app it starts, else
     * {@code otherwise} (Node.js on the file).
     */
    void debugOr(Path file, List<String> args, Runnable otherwise) {
        d.planOffFx("debug-electron-plan", () -> appOf(file), a -> {
            Optional<ElectronApps.App> app = a.value();
            if (app == null || app.isEmpty()) {
                otherwise.run();
            } else if (notDownloaded(app.get()) != null) {
                d.host.setStatus(java.util.Objects.requireNonNull(notDownloaded(app.get())));
            } else {
                debug(app.get(), args, d.host.activeBuffer());
            }
        });
    }

    /**
     * What to say when {@code app}'s Electron is installed but its program not downloaded yet (Electron fetches it on
     * its first start, not on {@code npm install}), or null when it is there.
     */
    static @Nullable String notDownloaded(ElectronApps.App app) {
        return app.electron() != null
                ? null
                : com.editora.i18n.Messages.tr(
                        "status.run.electronNotDownloaded",
                        String.valueOf(app.root().getFileName()));
    }

    /** Debugs {@code app}: its main process and its renderer, together. */
    void debug(ElectronApps.App app, List<String> args, com.editora.editor.@Nullable EditorBuffer b) {
        int port = freePort();
        LaunchJson.Compound group =
                new LaunchJson.Compound(String.valueOf(app.root().getFileName()), List.of(MAIN, RENDERER), true, "");
        LaunchJson.File launch = new LaunchJson.File(
                List.of(
                        new LaunchJson.Configuration(MAIN, "pwa-node", "launch", main(app, port, args)),
                        new LaunchJson.Configuration(RENDERER, BrowserLaunch.CHROME, "attach", renderer(app, port))),
                Map.of(),
                List.of(),
                List.of(group));
        d.ops.openToolWindow();
        d.launchJson.runLaunchCompound(
                new DebugCoordinator.LaunchFile(app.root().resolve(".vscode/launch.json"), app.root(), launch),
                group,
                b);
    }

    /** "Electron: Main": Electron's program on the app's folder under js-debug, its renderer's port open. */
    static Map<String, Object> main(ElectronApps.App app, int port, List<String> args) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "pwa-node");
        m.put("request", "launch");
        m.put("name", MAIN);
        m.put("runtimeExecutable", String.valueOf(app.electron()));
        m.put("runtimeArgs", ElectronApps.args(port, args));
        m.put("cwd", app.root().toString());
        m.put("outputCapture", "std"); // Electron writes to its standard streams, not through Node's inspector
        return m;
    }

    /** "Electron: Renderer": the app's windows, attached to on Electron's DevTools port. */
    static Map<String, Object> renderer(ElectronApps.App app, int port) {
        Map<String, Object> m =
                new LinkedHashMap<>(BrowserLaunch.attach(BrowserLaunch.CHROME, "127.0.0.1", port, app.root()));
        m.put("name", RENDERER);
        m.put("timeout", ATTACH_TIMEOUT_MILLIS);
        return m;
    }

    /** A port free on this machine's loopback address now. */
    static int freePort() {
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            return 9223; // VS Code's recipe's
        }
    }
}
