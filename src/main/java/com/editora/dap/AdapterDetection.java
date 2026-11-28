package com.editora.dap;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.editora.process.BusyExecutable;
import com.editora.process.ProcessRunner;
import org.jspecify.annotations.Nullable;

/**
 * Finding the debug adapters and telling whether they run: the java-debug bundle, debugpy and
 * vscode-js-debug are located (off the FX thread where it takes a walk of the disk), probed, and what was
 * found is kept in the window's {@link DapSettings}.
 *
 * <p>Moved out of {@link DapManager} for size: the code is the manager's, reaching its session state through
 * {@link #dap}, and runs on the same threads with the same session checks as it did there.
 */
final class AdapterDetection {
    private final DapManager dap;

    AdapterDetection(DapManager dap) {
        this.dap = dap;
    }

    /**
     * Stores the master enable flag + every language's enable/command and re-resolves the java-debug bundle
     * jar(s) — that one synchronously, because it must reach the LSP layer before any jdtls session starts.
     * The python/js adapter <em>locations</em> and their availability are both resolved later, off-thread, by
     * {@link #detectPython}/{@link #detectJs}.
     */
    void configure(
            boolean enabled,
            String javaPluginPath,
            boolean pythonEnabled,
            String pythonCommand,
            boolean jsEnabled,
            String jsPath) {
        dap.shared.enabled = enabled;
        dap.shared.pluginPath = javaPluginPath == null ? "" : javaPluginPath;
        if (!enabled) {
            dap.shared.bundlePaths = List.of();
        } else if (DebugAdapterLocator.locatedKnown(dap.shared.pluginPath, home()) || !onFx()) {
            dap.shared.bundlePaths = locateKept();
        } else {
            // A java-debug path not looked at yet (typed in Settings a moment ago): looked for off the FX thread
            // (PERF-01, Pauta 6.2.0), and the bundles in effect so far kept until it lands; then the owner applies
            // the support again ({@link DapManager#setOnJavaDebugLocated}), which pushes the new bundles to jdtls.
            String asked = dap.shared.pluginPath;
            com.editora.perf.OffFx.then(
                    "java-debug-locate", () -> DebugAdapterLocator.locate(asked, home()), located -> {
                        if (asked.equals(dap.shared.pluginPath) && dap.shared.enabled) {
                            dap.shared.bundlePaths = locateKept();
                            Runnable then = dap.onJavaDebugLocated;
                            if (then != null) {
                                then.run();
                            }
                        }
                    });
        }
        dap.shared.pythonEnabled = pythonEnabled;
        dap.shared.pythonCommand = pythonCommand == null ? "" : pythonCommand;
        dap.shared.jsEnabled = jsEnabled;
        dap.shared.jsPath = jsPath == null ? "" : jsPath;
        // The python/js adapter locations are deliberately NOT resolved here. Locating them walks the VS
        // Code / mason / plugin directories, and the only caller (DebugCoordinator.applySupport) follows
        // this immediately with detectPython/detectJs, which relocate them off-thread and assign these very
        // fields. Resolving here as well duplicated that walk on the FX thread during startup — measured at
        // ~30-50 ms on the path to first paint, for values not read until a debug session actually launches.
        // Only the clearing has to happen synchronously: with the feature off, detect* never runs to null it.
        if (!dap.shared.enabled || !dap.shared.pythonEnabled) {
            dap.shared.debugpyDir = null;
            dap.shared.pythonAvailable = false;
        }
        if (!dap.shared.enabled || !dap.shared.jsEnabled) {
            dap.shared.jsServer = null;
            dap.shared.jsAvailable = false;
        }
    }

    private static boolean onFx() {
        try {
            return javafx.application.Platform.isFxApplicationThread();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    boolean javaDebugUsable() {
        return dap.shared.enabled && (!dap.shared.bundlePaths.isEmpty() || dap.shared.serverProvidesJavaDebug);
    }

    /** Whether a usable debug adapter is available for {@code language} (after detection). */
    boolean isLanguageAvailable(String language) {
        DebugLanguage l = DebugLanguages.byId(language);
        if (l == null) {
            return false;
        }
        return switch (l.availability()) {
            case JAVA_DEBUG_PLUGIN -> javaDebugUsable();
            case LOCATED -> located(l);
            // Editora's own adapter, or Metals': nothing to install, only the debugging switch (the tool, or Metals
            // running, is checked when the session starts).
            case BUILT_IN -> dap.shared.enabled;
        };
    }

    /** Whether detection found {@code l}'s adapter (debugpy, vscode-js-debug, an external adapter's command). */
    private boolean located(DebugLanguage l) {
        return switch (l.id()) {
            case "python" -> dap.shared.pythonAvailable;
            case "javascript" -> dap.shared.jsAvailable;
            default -> dap.shared.enabled && dap.shared.externalLocated.containsKey(l.id());
        };
    }

    /** Re-runs the (filesystem) locate and delivers whether the plugin jar was found, on the FX thread. */
    void detect(Consumer<Boolean> onResult) {
        dap.io.submit(() -> {
            List<String> found = locate();
            dap.fx(() -> {
                dap.shared.bundlePaths = dap.shared.enabled ? found : List.of();
                onResult.accept(!found.isEmpty());
            });
        });
    }

    void detectPython(Consumer<Boolean> onResult, boolean installedOnly) {
        dap.io.submit(() -> {
            Path dir = DebugAdapterLocator.locateDebugpy("", home()).orElse(null);
            boolean on = dap.shared.enabled && dap.shared.pythonEnabled;
            boolean installed = (on || installedOnly) && probeDebugpy(dir);
            dap.fx(() -> {
                dap.shared.debugpyDir = dir;
                dap.shared.pythonAvailable = on && installed;
                onResult.accept(installedOnly ? installed : on && installed);
            });
        });
    }

    /**
     * Off-thread: relocates the vscode-js-debug server and probes {@code node --version}; delivers
     * availability (server found AND node runnable) + caches it, on the FX thread.
     */
    void detectJs(Consumer<Boolean> onResult) {
        dap.io.submit(() -> {
            Path server = DebugAdapterLocator.locateJsDebugServer(dap.shared.jsPath, home())
                    .orElse(null);
            boolean ok = dap.shared.enabled && dap.shared.jsEnabled && server != null && probeNode(adapterNode());
            dap.fx(() -> {
                dap.shared.jsServer = server;
                dap.shared.jsAvailable = ok;
                onResult.accept(ok);
            });
        });
    }

    List<String> locate() {
        return DebugAdapterLocator.locate(dap.shared.pluginPath, home())
                .map(p -> List.of(p.toString()))
                .orElse(List.of());
    }

    /** {@link #locate}, as kept since it was last looked up ({@link DebugAdapterLocator#locateKept}). */
    private List<String> locateKept() {
        return DebugAdapterLocator.locateKept(dap.shared.pluginPath, home())
                .map(p -> List.of(p.toString()))
                .orElse(List.of());
    }

    static Path home() {
        return Path.of(System.getProperty("user.home", ""));
    }

    /** Runs {@code <python> -c "import debugpy"} (debugpyDir on PYTHONPATH); true on a clean exit. */
    private boolean probeDebugpy(@Nullable Path dir) {
        List<String> interp = DapServerRegistry.interpreterArgv("python", dap.shared.pythonCommand);
        if (interp.isEmpty()) {
            return false;
        }
        List<String> argv = new ArrayList<>(interp);
        argv.add("-c");
        argv.add("import debugpy");
        return probe(argv, dir);
    }

    /** Finds {@link DapManager.JsDebugInstall} off the FX thread and delivers it on the FX thread. */
    void detectJsInstalled(Consumer<DapManager.JsDebugInstall> onResult) {
        String configured = dap.shared.jsPath;
        dap.io.submit(() -> {
            Path server =
                    DebugAdapterLocator.locateJsDebugServer(configured, home()).orElse(null);
            String serverVersion =
                    server == null ? null : com.editora.install.PinnedVersions.jsDebugServerVersion(server);
            String node = adapterNode();
            String nodeVersion = nodeVersion(node);
            String runner = dap.jsSessions.adapterNodeFor(node, nodeVersion);
            String runnerVersion = runner == null || runner.equals(node) ? nodeVersion : nodeVersion(runner);
            dap.fx(() -> onResult.accept(new DapManager.JsDebugInstall(server, serverVersion, runnerVersion)));
        });
    }

    /** The Node.js the settings name (or {@code node} on PATH) — what runs js-debug when no file is involved. */
    String adapterNode() {
        return com.editora.run.NodeRuntimes.resolveLocal(null, null, "", dap.shared.nodePath)
                .executable();
    }

    /** Runs {@code <node> --version}; true on a clean exit. */
    private boolean probeNode(String node) {
        return probe(List.of(node, "--version"), null);
    }

    /** What {@code <node> --version} prints ({@code v24.18.1}), or null when it does not run. Off the FX thread. */
    static @Nullable String nodeVersion(String node) {
        try {
            ProcessRunner.Result r =
                    ProcessRunner.run(null, java.time.Duration.ofSeconds(5), List.of(node, "--version"));
            return r.exit() == 0 ? JsLaunch.version(r.out()) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Launches {@code argv} (PATH-augmented; {@code pythonPathDir} prepended to PYTHONPATH when non-null),
     *  discards its output, and returns whether it exits 0 within 5s. */
    static boolean probe(List<String> argv, @Nullable Path pythonPathDir) {
        return probe(argv, pythonPathDir, Map.of());
    }

    /** As {@link #probe(List, Path)}, with {@code env} (a command's own {@code env NAME=value} prefix) applied. */
    static boolean probe(List<String> argv, @Nullable Path pythonPathDir, Map<String, String> env) {
        try {
            List<String> cmd = ProcessRunner.resolveExecutable(argv);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            ProcessRunner.applyStandardEnv(pb);
            pb.environment().putAll(env);
            if (pythonPathDir != null) {
                prependPythonPath(pb, pythonPathDir);
            }
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = BusyExecutable.start(pb);
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    static void prependPythonPath(ProcessBuilder pb, Path dir) {
        String existing = pb.environment().getOrDefault("PYTHONPATH", "");
        pb.environment().put("PYTHONPATH", existing.isEmpty() ? dir.toString() : dir + File.pathSeparator + existing);
    }
}
