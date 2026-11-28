package com.editora.dap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.editora.process.BusyExecutable;
import com.editora.process.ProcessRunner;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * JavaScript and TypeScript debug sessions over vscode-js-debug: starting its DAP server on the right Node.js,
 * launching or attaching a Node.js or Deno program or a browser through it, and doing the same on a remote host.
 *
 * <p>Moved out of {@link DapManager} for size: the code is the manager's, reaching its session state through
 * {@link #dap}, and runs on the same threads with the same session checks as it did there.
 */
final class JsSessions {
    private final DapManager dap;

    JsSessions(DapManager dap) {
        this.dap = dap;
    }

    /**
     * vscode-js-debug for a file on this machine: the Node.js {@link com.editora.run.NodeRuntimes} resolves for it
     * runs the program (and js-debug, when it is new enough), from the file's package root; then connect, launch.
     */
    void startSocket(
            Path file,
            DapServerRegistry.DapServerSpec spec,
            List<DapModels.FileBreakpoints> bps,
            @Nullable List<String> filters,
            long epoch,
            @Nullable Map<String, Object> configured)
            throws Exception {
        Path server = dap.shared.jsServer;
        if (server == null) {
            dap.fail(epoch, tr("debug.error.jsNotFound"));
            return;
        }
        DapManager.JsProgram planned = dap.jsProgram;
        List<String> runtime;
        String program;
        List<String> args;
        Path cwd;
        Path workspace;
        Map<String, String> env;
        String adapterNode;
        Path projectRoot = dap.jsProjectRoot;
        if (planned != null) {
            // A run configuration's launch, planned as Run plans it (NodeRunPlan / the package manager).
            runtime = planned.runtime();
            program = planned.program();
            args = planned.args();
            cwd = planned.cwd();
            workspace = projectRoot != null && cwd.toAbsolutePath().startsWith(projectRoot.toAbsolutePath())
                    ? projectRoot
                    : cwd;
            env = planned.env();
            String exe = runtime.get(0);
            boolean node = com.editora.run.NodeRunPlan.isNodeName(exe);
            String version = node ? AdapterDetection.nodeVersion(exe) : null;
            adapterNode = node
                    ? adapterNodeFor(exe, version)
                    : adapterNodeFor(
                            dap.detection.adapterNode(), AdapterDetection.nodeVersion(dap.detection.adapterNode()));
            if (adapterNode == null) {
                String used = node ? exe : dap.detection.adapterNode();
                dap.fail(
                        epoch,
                        tr(
                                "debug.error.jsNodeTooOld",
                                String.valueOf(node ? version : AdapterDetection.nodeVersion(used)),
                                used,
                                JsLaunch.MIN_ADAPTER_NODE_MAJOR));
                return;
            }
        } else {
            com.editora.run.NodeRuntimes.NodeRuntime resolved =
                    com.editora.run.NodeRuntimes.resolveLocal(file, projectRoot, "", dap.shared.nodePath);
            String version = AdapterDetection.nodeVersion(resolved.executable());
            String problem = JsLaunch.launchProblemKey(file.getFileName().toString(), version);
            if (problem != null) {
                dap.fail(epoch, tr(problem, version == null ? "?" : version));
                return;
            }
            adapterNode = adapterNodeFor(resolved.executable(), version);
            if (adapterNode == null) {
                dap.fail(
                        epoch,
                        tr(
                                "debug.error.jsNodeTooOld",
                                String.valueOf(version),
                                resolved.executable(),
                                JsLaunch.MIN_ADAPTER_NODE_MAJOR));
                return;
            }
            runtime = JsLaunch.withNodeArgs(resolved.command(), dap.jsNodeArgs);
            program = file.toString();
            args = dap.programArgs;
            cwd = com.editora.run.NodeRuntimes.workingDirectory(file, projectRoot);
            // The workspace folder js-debug resolves ${workspaceFolder} from (source maps): the project when the
            // file is in it, else its package root.
            workspace = projectRoot != null && file.toAbsolutePath().startsWith(projectRoot.toAbsolutePath())
                    ? projectRoot
                    : cwd;
            env = JsLaunch.colourEnvironment(System.getenv("NO_COLOR")); // as Run (review D104)
        }
        int denoPort = -1;
        if (!runtime.isEmpty() && com.editora.run.DenoRun.isDeno(runtime.get(0))) {
            // Deno is not started through NODE_OPTIONS: it waits on an inspector port, and js-debug attaches there.
            denoPort = DapManager.freePort();
            runtime = com.editora.run.DenoRun.inspected(runtime, denoPort);
        }
        boolean clientConsole = dap.shared.programConsole;
        Map<String, Object> launch = DapManager.overlay(
                JsLaunch.request(
                        spec.launchType(),
                        program,
                        cwd.toString(),
                        workspace.toString(),
                        runtime,
                        args,
                        clientConsole,
                        env),
                configured);
        if (denoPort > 0) {
            launch.put("attachSimplePort", denoPort);
            // js-debug gives up on the port after 10 s by default; a cold Deno (its first type load) can take longer.
            launch.putIfAbsent("timeout", com.editora.run.DenoRun.ATTACH_TIMEOUT_MS);
        }
        dap.jsClientConsole = clientConsole;
        // The command line that runs is printed when js-debug asks Editora to start it (startDebuggee): js-debug may
        // run another file than the one planned — a .ts source's compiled output (review D106).
        List<String> chip = runtime;
        JsAdapter adapter = startJsAdapter(server, adapterNode, epoch);
        if (adapter == null) {
            return; // reported
        }
        attachSocket(
                adapter.process(),
                null,
                adapter.port(),
                spec,
                bps,
                filters,
                epoch,
                DapPaths.LOCAL,
                launch,
                clientConsole,
                chip);
    }

    /**
     * The Node.js that runs js-debug for a program on {@code programNode} ({@code version}): that one when it is
     * new enough, else the Node.js setting's when that is, else the newest nvm one or {@code node} on PATH — found
     * without the setting, so an old Node.js chosen in Settings does not stop debugging (review D101); null when
     * none is new enough. The program still runs on its own Node.
     */
    @Nullable
    String adapterNodeFor(String programNode, @Nullable String version) {
        if (JsLaunch.runsAdapter(version)) {
            return programNode;
        }
        List<String> fallbacks = new ArrayList<>();
        fallbacks.add(dap.detection.adapterNode());
        fallbacks.addAll(dap.adapterNodeFallbacks.get());
        return JsAdapterNode.choose(programNode, version, fallbacks, DapManager::nodeVersion);
    }

    /** A started js-debug server: its process, the port it announced on this machine, and its stderr. */
    private record JsAdapter(Process process, int port, AdapterStderr stderr) {}

    /**
     * Starts js-debug on a port it picks itself and waits for the line announcing it ({@link #listeningPort}) —
     * as on a remote host. A guessed free port, then connect retries for about two seconds, lost to any slower
     * start (review D15). A server that dies first is reported with what it wrote to stderr; null then.
     */
    private @Nullable JsAdapter startJsAdapter(Path server, String node, long epoch) throws java.io.IOException {
        List<String> cmd = ProcessRunner.resolveExecutable(List.of(node, server.toString(), "0", "127.0.0.1"));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        ProcessRunner.applyUserEnv(pb);
        Process proc = BusyExecutable.start(pb);
        AdapterStderr stderr = dap.adapterStderr(epoch, AdapterStderr.drain(proc));
        int port = listeningPort(proc.getInputStream(), java.time.Duration.ofSeconds(20));
        if (port < 0) {
            boolean ended = false;
            try {
                ended = proc.waitFor(500, TimeUnit.MILLISECONDS); // let its last stderr lines arrive
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            com.editora.process.ProcessRegistry.killTree(proc);
            dap.fail(
                    epoch,
                    tr(ended ? "debug.error.jsAdapterExited" : "debug.error.jsAdapterNoStart", node)
                            + AdapterStderr.suffix(stderr));
            return null;
        }
        return new JsAdapter(proc, port, stderr);
    }

    /**
     * Attaches the JavaScript debugger to the Node.js inspector at {@code host:port} (Debug ▸ Attach on a JavaScript
     * file, the HTTP Client's Debug Request). js-debug is started as for a launch and asked to {@code attach}
     * ({@link LaunchConfig#nodeAttach}); {@code continueOnAttach} resumes a program held by {@code --inspect-brk}
     * once the breakpoints are set. The session is called {@code name}; Stop detaches and leaves the program
     * running unless it was waiting for this debugger.
     */
    void startJsAttach(
            String host, int port, String name, @Nullable Path cwd, List<String> skipFiles, boolean continueOnAttach) {
        DapServerRegistry.DapServerSpec spec = DapServerRegistry.specFor("javascript");
        String type = spec == null ? "pwa-node" : spec.launchType();
        startJsRequest(
                cwd,
                () -> LaunchConfig.nodeAttach(type, name, host, port, skipFiles, continueOnAttach),
                () -> startJsAttach(host, port, name, cwd, skipFiles, continueOnAttach));
    }

    /** Starts js-debug and sends it the request {@code body} makes; {@code restart} repeats it. */
    void startJsRequest(
            @Nullable Path anchor, java.util.function.Supplier<Map<String, Object>> body, Runnable restart) {
        if (dap.managerClosed) {
            return;
        }
        if (!dap.shared.enabled) {
            dap.listener.onError(tr("debug.error.disabled"));
            return;
        }
        DapServerRegistry.DapServerSpec spec = DapServerRegistry.specFor("javascript");
        if (spec == null || !dap.isLanguageAvailable("javascript")) {
            dap.listener.onError(tr("debug.error.jsUnavailable"));
            return;
        }
        if (dap.client != null) {
            dap.stop(); // one session at a time
        }
        dap.restartAction = restart;
        dap.debugFile = anchor;
        long epoch = dap.beginSession();
        dap.setState(DapManager.State.STARTING);
        List<DapModels.FileBreakpoints> bps = dap.shared.breakpointsSupplier.get();
        List<String> filters = dap.filtersFor("javascript");
        Map<String, Object> request = body.get();
        dap.jsClientConsole = false;
        dap.startupTask = dap.io.submit(() -> {
            try {
                Path server = dap.shared.jsServer;
                if (server == null) {
                    dap.fail(epoch, tr("debug.error.jsNotFound"));
                    return;
                }
                String attachNode = dap.detection.adapterNode();
                String runner = adapterNodeFor(attachNode, AdapterDetection.nodeVersion(attachNode));
                if (runner == null) {
                    dap.fail(
                            epoch,
                            tr(
                                    "debug.error.jsNodeTooOld",
                                    String.valueOf(AdapterDetection.nodeVersion(attachNode)),
                                    attachNode,
                                    JsLaunch.MIN_ADAPTER_NODE_MAJOR));
                    return;
                }
                JsAdapter adapter = startJsAdapter(server, runner, epoch);
                if (adapter == null) {
                    return; // reported
                }
                attachSocket(
                        adapter.process(),
                        null,
                        adapter.port(),
                        spec,
                        bps,
                        filters,
                        epoch,
                        DapPaths.LOCAL,
                        request,
                        false,
                        null);
            } catch (Exception e) {
                dap.fail(epoch, tr("debug.error.startFailed", DapManager.msg(e)));
            }
        });
    }

    /**
     * Wires a started socket adapter ({@code proc}, listening on this machine's {@code port} — through
     * {@code forward} for one on a remote host) to a new session and sends it {@code request} (a {@code launch} or
     * an {@code attach}). With {@code runsDebuggee} the adapter hands the program's command line back and Editora
     * starts it (standard input, all output, exit code). {@code runtime} is the launched program's Node command,
     * for the runtime chip (null for an attach).
     */
    private void attachSocket(
            Process proc,
            com.editora.process.@Nullable PortForward forward,
            int port,
            DapServerRegistry.DapServerSpec spec,
            List<DapModels.FileBreakpoints> bps,
            @Nullable List<String> filters,
            long epoch,
            DapPaths paths,
            Map<String, Object> request,
            boolean runsDebuggee,
            @Nullable List<String> runtime) {
        if (!dap.isCurrent(epoch)) {
            com.editora.process.ProcessRegistry.killTree(proc);
            if (forward != null) {
                forward.close();
            }
            return;
        }
        boolean attach = "attach".equals(request.get("request"));
        DapClient c = new DapClient(dap.sessionHost(epoch));
        c.setPaths(paths);
        c.setBreakpoints(bps);
        c.setExceptionFilters(filters);
        c.setRunsDebuggee(runsDebuggee);
        // Stop detaches from a program it attached to: vscode-js-debug ends it on terminateDebuggee (seen with 1.117).
        c.setTerminateOnDisconnect(!attach);
        c.setAdapterProcess(proc); // killed on dispose (it serves the socket)
        if (forward != null) {
            c.closeOnDispose(forward);
        }
        if (!dap.publishClient(epoch, c)) {
            c.dispose();
            return;
        }
        c.connect(port, spec.adapterId()).whenComplete((caps, e) -> {
            if (e != null) {
                dap.fail(
                        epoch,
                        tr("debug.error.jsConnect", DapManager.msg(e))
                                + AdapterStderr.suffix(dap.adapterStderr(epoch)));
                return;
            }
            if (!dap.isCurrent(epoch)) {
                c.dispose();
                return;
            }
            dap.sessionEvents
                    .trackLaunch(
                            epoch,
                            BrowserLaunch.isBrowserType(String.valueOf(request.get("type"))),
                            () -> attach ? c.attach(request) : c.launch(request))
                    .whenComplete((v, le) -> {
                        if (le != null) {
                            dap.fail(
                                    epoch,
                                    tr(
                                            attach ? "debug.error.attachFailed" : "debug.error.launchFailed",
                                            DapManager.msg(le)));
                        } else if (attach && Boolean.FALSE.equals(request.get("continueOnAttach"))) {
                            // A user's Node.js attach: nothing else says it worked, a running server prints nothing
                            // (D103).
                            dap.sessionEvents.onOutput(
                                    epoch,
                                    tr(
                                                    "debug.js.attached",
                                                    request.getOrDefault("address", "127.0.0.1") + ":"
                                                            + request.get("port"))
                                            + "\n",
                                    DapManager.COMMAND_CATEGORY);
                        }
                    });
            dap.fx(() -> {
                if (dap.isCurrent(epoch, c)) {
                    dap.setState(DapManager.State.RUNNING);
                    if (runtime != null && !runtime.isEmpty()) {
                        Object cwd = request.get("cwd");
                        dap.listener.onDebuggeeRuntime(
                                runtime, DapManager.runtimeWhere(paths, cwd instanceof String s ? s : null), Map.of());
                    }
                }
            });
        });
    }

    /** vscode-js-debug on the host: started on a free port there, reached through a tunnel from this machine. */
    void startRemoteSocket(
            Path file,
            DapServerRegistry.DapServerSpec spec,
            List<DapModels.FileBreakpoints> bps,
            @Nullable List<String> filters,
            long epoch)
            throws Exception {
        dap.jsClientConsole = false; // js-debug starts the program on the host
        var target = com.editora.process.ExecTargets.forPath(file);
        Path dir = file.getParent();
        String configured = com.editora.process.ExecTargets.remoteTool(file, "dap.js");
        String nodeTool = com.editora.process.ExecTargets.remoteTool(file, "node");
        String node = nodeTool == null || nodeTool.isBlank() ? spec.defaultInterpreter() : nodeTool.strip();
        ProcessRunner.Result found = ProcessRunner.run(
                dir,
                java.time.Duration.ofSeconds(20),
                List.of(
                        "sh",
                        "-c",
                        DapManager.FIND_JS_DEBUG,
                        "sh",
                        configured == null ? "" : configured.strip(),
                        node));
        String server = found.ok() ? found.out().strip() : "";
        if (server.isEmpty()) {
            dap.fail(
                    epoch,
                    found.exit() == 2
                            ? tr("debug.error.remoteNodeMissing", target.id())
                            : tr("debug.error.remoteJsMissing", target.id()));
            return;
        }
        if (!dap.isCurrent(epoch)) {
            return;
        }
        // The package root as the working folder and workspace, as on this machine (review D110): a compiled
        // project's source maps map, and process.cwd() is what a local Debug gets. Looked up on the host's files.
        Path projectRoot = dap.jsProjectRoot;
        Path root = projectRoot != null && projectRoot.getFileSystem() == file.getFileSystem() ? projectRoot : null;
        Path remoteCwd = com.editora.run.NodeRuntimes.packageRoot(file, root, null, Files::isRegularFile)
                .orElse(dir);
        Path remoteWorkspace =
                root != null && file.toAbsolutePath().startsWith(root.toAbsolutePath()) ? root : remoteCwd;
        // Port 0: the host picks a free one and the server prints it; 127.0.0.1, so the tunnel's end is known.
        Process proc = target.start(com.editora.process.ExecSpec.of(
                        List.of(node, server, "0", "127.0.0.1"),
                        dir,
                        java.util.Map.of(),
                        com.editora.process.ExecSpec.Locale.USER)
                .withLongLived(true)
                .withDiscardedOutput(false, true));
        int remotePort = listeningPort(proc.getInputStream(), java.time.Duration.ofSeconds(20));
        if (remotePort < 0) {
            com.editora.process.ProcessRegistry.killTree(proc);
            dap.fail(epoch, tr("debug.error.remoteJsNoStart", target.id()));
            return;
        }
        com.editora.process.PortForward forward;
        try {
            forward = target.forwardLocal("127.0.0.1", remotePort);
        } catch (java.io.IOException e) {
            com.editora.process.ProcessRegistry.killTree(proc);
            throw e;
        }
        attachSocket(
                proc,
                forward,
                forward.localPort(),
                spec,
                bps,
                filters,
                epoch,
                DapPaths.on(target, file.getFileSystem()),
                // js-debug starts the program on the host (Editora cannot start it there), so its output is read
                // with outputCapture "std"; its folder is the file's, which is what the host can be asked for.
                JsLaunch.request(
                        spec.launchType(),
                        target.toTargetPath(file),
                        target.toTargetPath(remoteCwd),
                        target.toTargetPath(remoteWorkspace),
                        List.of(node),
                        dap.programArgs,
                        false),
                false,
                List.of(node));
    }

    /** Matches the line vscode-js-debug's server prints once it listens: {@code … listening at host:port}. */
    private static final java.util.regex.Pattern LISTENING =
            java.util.regex.Pattern.compile("listening at .*:(\\d+)\\s*$");

    /**
     * The port an adapter announces on {@code out} ({@link #LISTENING}), or -1 when it ends or says nothing
     * in {@code timeout}. Whatever it prints afterwards is read and dropped, so its channel never fills.
     */
    static int listeningPort(java.io.InputStream out, java.time.Duration timeout) {
        CompletableFuture<Integer> port = new CompletableFuture<>();
        Thread.ofVirtual().name("dap-adapter-stdout").start(() -> {
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(out, java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    var m = LISTENING.matcher(line);
                    if (!port.isDone() && m.find()) {
                        port.complete(Integer.parseInt(m.group(1)));
                    }
                }
            } catch (java.io.IOException | RuntimeException ended) {
                // the adapter is gone
            } finally {
                port.complete(-1);
            }
        });
        try {
            return port.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return -1;
        }
    }
}
