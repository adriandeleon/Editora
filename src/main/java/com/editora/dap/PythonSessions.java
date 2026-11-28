package com.editora.dap;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.editora.process.BusyExecutable;
import com.editora.process.ProcessRunner;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Python debug sessions over debugpy: planning the interpreter and the launch, starting debugpy's adapter over
 * stdio (here or on a remote host), attaching to a listening debugpy, and the subprocess sessions debugpy
 * asks for.
 *
 * <p>Moved out of {@link DapManager} for size: the code is the manager's, reaching its session state through
 * {@link #dap}, and runs on the same threads with the same session checks as it did there.
 */
final class PythonSessions {
    private final DapManager dap;

    PythonSessions(DapManager dap) {
        this.dap = dap;
    }

    /**
     * Attaches to a Python listening for a debugger ({@code python -m debugpy --listen host:port …}, or one that
     * called {@code debugpy.listen}): that debugpy runs its own adapter on the port, so the session connects to it
     * over the socket and sends {@code attach}, as VS Code's {@code connect} configuration does. {@code anchor} names the session (a file or folder of this
     * machine). Restart attaches again; Stop detaches and leaves the program running.
     */
    void startPythonAttach(Path anchor, String host, int port) {
        // Not readyProgram: the program brings its own debugpy, so none need be installed here.
        if (dap.managerClosed) {
            return;
        }
        if (!dap.shared.enabled) {
            dap.listener.onError(tr("debug.error.disabled"));
            return;
        }
        if (dap.client != null) {
            dap.stop(); // one session at a time
        }
        DapServerRegistry.DapServerSpec spec = DapServerRegistry.specFor("python");
        if (spec == null) {
            dap.listener.onError(tr("debug.error.noAdapter"));
            return;
        }
        dap.restartAction = () -> startPythonAttach(anchor, host, port);
        dap.debugFile = anchor;
        long epoch = dap.beginSession();
        dap.setState(DapManager.State.STARTING);
        List<DapModels.FileBreakpoints> bps = dap.shared.breakpointsSupplier.get();
        List<String> filters = dap.filtersFor("python");
        dap.startupTask = dap.io.submit(() -> {
            try {
                if (!dap.isCurrent(epoch)) {
                    return;
                }
                DapClient c = new DapClient(dap.sessionHost(epoch));
                c.setPaths(DapPaths.LOCAL);
                c.setBreakpoints(bps);
                c.setExceptionFilters(filters);
                c.setTerminateOnDisconnect(false); // Stop detaches; the program runs on
                if (!dap.publishClient(epoch, c)) {
                    c.dispose();
                    return;
                }
                dap.sessionEvents.onOutput(
                        epoch, tr("debug.console.attaching", host + ":" + port) + "\n", DapManager.COMMAND_CATEGORY);
                // `debugpy --listen` runs debugpy's own adapter on that port: the client talks DAP to it directly.
                c.connect(host, port, spec.adapterId()).whenComplete((caps, e) -> {
                    if (e != null) {
                        dap.fail(epoch, tr("debug.error.debugpyConnect", DapManager.msg(e)));
                        return;
                    }
                    if (!dap.isCurrent(epoch)) {
                        c.dispose();
                        return;
                    }
                    c.attach(LaunchConfig.pythonAttach(host, port)).whenComplete((v, ae) -> {
                        if (ae != null) {
                            dap.fail(epoch, tr("debug.error.attachFailed", DapManager.msg(ae)));
                        }
                    });
                    dap.fx(() -> {
                        if (dap.isCurrent(epoch, c)) {
                            dap.setState(DapManager.State.RUNNING);
                        }
                    });
                });
            } catch (Exception e) {
                dap.fail(epoch, tr("debug.error.startFailed", DapManager.msg(e)));
            }
        });
    }

    /** Hands each subprocess debugpy asks for to the window ({@link DapManager.Listener#onChildSession}), while it is current. */
    private java.util.function.Function<Map<String, Object>, java.util.concurrent.CompletableFuture<Void>>
            childSessions(long epoch, DapClient c) {
        return configuration -> {
            dap.fx(() -> {
                if (dap.isCurrent(epoch, c)) {
                    dap.listener.onChildSession(configuration);
                }
            });
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        };
    }

    /**
     * Debugs a subprocess of a Python session in this (new) manager: attaches to the port debugpy named for it in
     * {@code configuration} ({@link DapManager.Listener#onChildSession}), anchored at the parent's file {@code anchor}. Stop
     * detaches: the subprocess runs on, and ends with its program.
     */
    void startPythonChild(@Nullable Path anchor, Map<String, Object> configuration) {
        if (dap.managerClosed) {
            return;
        }
        int port = configuration.get("connect") instanceof Map<?, ?> connect && connect.get("port") instanceof Number n
                ? n.intValue()
                : -1;
        DapServerRegistry.DapServerSpec spec = DapServerRegistry.specFor("python");
        if (spec == null || port <= 0) {
            dap.listener.onError(tr("debug.error.noAdapter"));
            return;
        }
        if (dap.client != null) {
            dap.stop(); // one session at a time
        }
        dap.restartAction = null; // a subprocess is the program's to start again, not this session's
        dap.debugFile = anchor;
        long epoch = dap.beginSession();
        dap.setState(DapManager.State.STARTING);
        List<DapModels.FileBreakpoints> bps = dap.shared.breakpointsSupplier.get();
        List<String> filters = dap.filtersFor("python");
        Map<String, Object> attach = new java.util.LinkedHashMap<>(configuration);
        dap.startupTask = dap.io.submit(() -> {
            try {
                if (!dap.isCurrent(epoch)) {
                    return;
                }
                DapClient c = new DapClient(dap.sessionHost(epoch));
                c.setPaths(DapPaths.LOCAL);
                c.setBreakpoints(bps);
                c.setExceptionFilters(filters);
                c.setTerminateOnDisconnect(false);
                // A subprocess's own subprocesses (multiprocessing's fork server forks the workers) are sessions of
                // their own too, not connections folded into this one.
                c.setChildSessions(childSessions(epoch, c));
                if (!dap.publishClient(epoch, c)) {
                    c.dispose();
                    return;
                }
                c.connect(port, spec.adapterId()).whenComplete((caps, e) -> {
                    if (e != null) {
                        dap.fail(epoch, tr("debug.error.debugpyConnect", DapManager.msg(e)));
                        return;
                    }
                    if (!dap.isCurrent(epoch)) {
                        c.dispose();
                        return;
                    }
                    c.attach(attach).whenComplete((v, ae) -> {
                        if (ae != null) {
                            dap.fail(epoch, tr("debug.error.attachFailed", DapManager.msg(ae)));
                        }
                    });
                    dap.fx(() -> {
                        if (dap.isCurrent(epoch, c)) {
                            dap.setState(DapManager.State.RUNNING);
                        }
                    });
                });
            } catch (Exception e) {
                dap.fail(epoch, tr("debug.error.startFailed", DapManager.msg(e)));
            }
        });
    }

    /**
     * Debugs a pytest run ({@code python -m pytest args}) in {@code cwd}, anchored at test file {@code anchor}.
     * When the interpreter has no pytest, {@code unittestArgs} (non-empty) run under {@code python -m unittest}
     * instead; otherwise the session ends saying pytest is missing for that interpreter.
     */
    void startPythonTest(Path anchor, List<String> pytestArgs, List<String> unittestArgs, Path cwd) {
        Path dir = cwd != null ? cwd : anchor.getParent();
        String where = dir == null
                ? null
                : com.editora.process.ExecTargets.forPath(dir).toTargetPath(dir);
        PythonLaunch.Target unittest = unittestArgs == null || unittestArgs.isEmpty()
                ? null
                : PythonLaunch.Target.module("unittest", unittestArgs, where);
        dap.startProgram(
                anchor,
                "python",
                PythonLaunch.Target.module("pytest", pytestArgs, where).orElse(unittest));
    }

    /**
     * Debugs {@code python -m module args} in {@code cwd} — a pytest test, say — under {@code anchor}'s
     * interpreter, with {@code anchor} naming the session. Restart repeats it.
     */
    void startPythonModule(Path anchor, String module, List<String> args, Path cwd) {
        Path dir = cwd != null ? cwd : anchor.getParent();
        String where = dir == null
                ? null
                : com.editora.process.ExecTargets.forPath(dir).toTargetPath(dir);
        dap.startProgram(anchor, "python", PythonLaunch.Target.module(module, args, where));
    }

    /**
     * Debugs the Python {@code script} with {@code args} in {@code cwd} — a run configuration's — under the
     * interpreter {@link DapManager#setPythonPlan} planned. Restart repeats it.
     */
    void startPythonScript(Path script, List<String> args, Path cwd) {
        var exec = com.editora.process.ExecTargets.forPath(script);
        String where = cwd == null ? null : exec.toTargetPath(cwd);
        dap.startProgram(script, "python", PythonLaunch.Target.script(exec.toTargetPath(script), args, where));
    }

    /**
     * debugpy over stdio: spawn {@code <python> -m debugpy.adapter}, wire DAP to its streams, launch. The
     * program runs under the interpreter resolved for {@code file} (the project's venv, the picked one, …);
     * the adapter runs under that one too when it can import debugpy, else under the Debugging setting's.
     */
    void startStdio(
            Path file,
            DapServerRegistry.DapServerSpec spec,
            List<DapModels.FileBreakpoints> bps,
            @Nullable List<String> filters,
            long epoch,
            PythonLaunch.@Nullable Target module,
            @Nullable Map<String, Object> configured)
            throws Exception {
        DapManager.HostPython program = planProgram(file, com.editora.process.ExecTargets.forPath(file), true);
        DapManager.HostPython adapter = AdapterDetection.probe(
                        program.argv(List.of("-c", "import debugpy")), dap.shared.debugpyDir, program.env())
                ? program
                : DapManager.HostPython.parse(DapServerRegistry.interpreterArgv("python", dap.shared.pythonCommand));
        List<String> cmd = ProcessRunner.resolveExecutable(adapter.argv(spec.adapterArgs())); // -m debugpy.adapter
        ProcessBuilder pb = new ProcessBuilder(cmd);
        ProcessRunner.applyUserEnv(pb);
        pb.environment().putAll(adapter.env());
        if (dap.shared.debugpyDir != null) {
            AdapterDetection.prependPythonPath(pb, dap.shared.debugpyDir);
        }
        if (module != null && "pytest".equals(module.module())) {
            module =
                    pytestOrFallback(module, program, epoch, argv -> AdapterDetection.probe(argv, null, program.env()));
            if (module == null) {
                return; // reported
            }
        }
        Process proc = BusyExecutable.start(pb);
        // DAP is on stdin/stdout; an undrained PIPE deadlocks
        AdapterStderr stderr = dap.adapterStderr(epoch, AdapterStderr.drain(proc));
        boolean console = dap.shared.programConsole;
        String content = module == null ? DapManager.readQuietly(file) : null;
        PythonLaunch.Target target = module != null ? module : webOrScript(file, content, dap.programArgs, true);
        // What runs, first in the console (muted): which Python, in which venv, with which arguments.
        dap.sessionEvents.onOutput(
                epoch, PythonLaunch.commandLine(program, target) + "\n", DapManager.COMMAND_CATEGORY);
        // The plan's PYTHONPATH (project root, src/, the user's) when it set one; else the user's own, or "" —
        // never the adapter's, which carries Editora's debugpy folder.
        String inherited = System.getenv("PYTHONPATH");
        Map<String, Object> planned = PythonLaunch.request(
                target, program, List.of(), inherited == null ? "" : inherited, File.pathSeparator, console);
        if (dap.shared.pythonSubprocesses || servesWeb(target, content, configured)) {
            // Each subprocess a session of its own (onChildSession): a web server's reloader serves from a child.
            planned.put("subProcess", true);
        }
        Map<String, Object> launch = DapManager.overlay(planned, configured);
        attachStdio(proc, spec, bps, filters, epoch, DapPaths.LOCAL, launch, console, stderr);
    }

    /**
     * A pytest run when {@code program} can import pytest; else its unittest fallback; else null, with the
     * session ended saying which interpreter lacks pytest.
     */
    private PythonLaunch.@Nullable Target pytestOrFallback(
            PythonLaunch.Target pytest,
            DapManager.HostPython program,
            long epoch,
            java.util.function.Predicate<List<String>> runs) {
        if (!"pytest".equals(pytest.module()) || runs.test(program.argv(List.of("-c", "import pytest")))) {
            return pytest;
        }
        if (pytest.fallback() != null) {
            return pytest.fallback();
        }
        dap.fail(epoch, tr("debug.error.pytestMissing", program.executable()));
        return null;
    }

    /**
     * The program's interpreter and environment for a Python session on {@code file}, planned by Run's planner
     * ({@link com.editora.run.PythonRunPlan}) so Run and Debug start the same Python the same way: the
     * resolved interpreter (a project venv found on the target, named as the target names it; a picked one; a
     * host's {@code python} tool), the file's shebang, and the project root with {@code src/} on {@code
     * PYTHONPATH}. Taken apart as debugpy takes it ({@code python}, {@code pythonArgs}, {@code env}). Off the FX
     * thread: elsewhere the lookups are network round trips.
     */
    private DapManager.HostPython planProgram(Path file, com.editora.process.ExecTarget target, boolean local) {
        com.editora.run.PythonRunPlan.Request asked = dap.pythonRequest;
        String firstLine = asked != null ? asked.firstLine() : null;
        if (com.editora.run.PythonLaunch.isRunner(com.editora.run.PythonLaunch.shebang(firstLine))) {
            firstLine = null; // debugpy needs an interpreter, not `uv run --script`: the project's Python runs it
        }
        // A run configuration's Interpreter, its options and its variables (Debug of a configuration) are kept.
        com.editora.run.PythonRunPlan.Request request = new com.editora.run.PythonRunPlan.Request(
                file,
                asked != null ? asked.projectRoot() : pythonRootFor(file),
                target.toTargetPath(file),
                false,
                List.of(),
                firstLine,
                asked != null ? asked.configured() : "",
                asked != null ? asked.interpreterOptions() : List.of(),
                asked != null ? asked.projectChoice() : dap.pythonProjectChoice,
                asked != null ? asked.setting() : local ? dap.shared.pythonCommand : "",
                asked != null ? asked.userEnv() : java.util.Map.of());
        com.editora.run.PythonRunPlan.Host host = dap.pythonHost != null
                ? dap.pythonHost
                : local
                        ? com.editora.run.PythonRunPlan.Host.here()
                        : com.editora.run.PythonRunPlan.Host.elsewhere(target::toTargetPath);
        com.editora.run.PythonRunPlan plan;
        try {
            plan = com.editora.run.PythonRunPlan.plan(request, host);
        } catch (RuntimeException e) {
            if (host.local()) {
                throw e;
            }
            // A lookup over a dropped connection: the host's own python3, as Run does.
            plan = com.editora.run.PythonRunPlan.plan(
                    request,
                    com.editora.run.PythonRunPlan.Host.elsewhere(host.toTarget())
                            .withoutLookups());
        }
        List<String> command = plan.argv().subList(0, plan.argv().size() - 1); // the plan's argv ends in the script
        // debugpy is handed a local interpreter by path: the adapter's PATH is not the one it was found on.
        String exe = host.local()
                ? ProcessRunner.resolveExecutable(List.of(command.get(0))).getFirst()
                : command.get(0);
        return new DapManager.HostPython(exe, List.copyOf(command.subList(1, command.size())), plan.env());
    }

    /** The project root {@code file} belongs to (the window's project, when the file is inside it), else null. */
    private @Nullable Path pythonRootFor(Path file) {
        Path root = dap.pythonProjectRoot;
        if (root == null || file == null) {
            return null;
        }
        try {
            return file.toAbsolutePath()
                            .normalize()
                            .startsWith(root.toAbsolutePath().normalize())
                    ? root
                    : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Wires a started stdio adapter ({@code proc}) to a new session and sends it {@code launch}, whose paths are
     * named as the adapter's machine names them. With {@code runsDebuggee}, the adapter hands the program's
     * command line back and Editora starts it (its standard input is then the Debug console's).
     */
    private void attachStdio(
            Process proc,
            DapServerRegistry.DapServerSpec spec,
            List<DapModels.FileBreakpoints> bps,
            @Nullable List<String> filters,
            long epoch,
            DapPaths paths,
            Map<String, Object> launch,
            boolean runsDebuggee,
            @Nullable AdapterStderr stderr) {
        if (!dap.isCurrent(epoch)) {
            com.editora.process.ProcessRegistry.killTree(proc);
            return;
        }
        DapClient c = new DapClient(dap.sessionHost(epoch));
        c.setPaths(paths);
        c.setBreakpoints(bps);
        c.setExceptionFilters(filters);
        c.setRunsDebuggee(runsDebuggee);
        if (Boolean.TRUE.equals(launch.get("subProcess")) && paths == DapPaths.LOCAL) {
            // debugpy asks for each subprocess with startDebugging, naming a port of its own to attach a session to.
            c.setChildSessions(childSessions(epoch, c));
        }
        if (!dap.publishClient(epoch, c)) {
            com.editora.process.ProcessRegistry.killTree(proc);
            c.dispose();
            return;
        }
        c.connectStdio(proc, spec.adapterId()).whenComplete((caps, e) -> {
            if (e != null) {
                dap.fail(epoch, tr("debug.error.debugpyConnect", DapManager.msg(e)) + AdapterStderr.suffix(stderr));
                return;
            }
            if (!dap.isCurrent(epoch)) {
                c.dispose();
                return;
            }
            dap.sessionEvents.trackLaunch(epoch, false, () -> c.launch(launch)).whenComplete((v, le) -> {
                if (le != null) {
                    dap.fail(epoch, launchFailure(le, launch) + AdapterStderr.suffix(stderr));
                }
            });
            dap.fx(() -> {
                if (dap.isCurrent(epoch, c)) {
                    dap.setState(DapManager.State.RUNNING);
                    List<String> runtime = pythonRuntime(launch);
                    if (!runtime.isEmpty()) {
                        dap.listener.onDebuggeeRuntime(
                                runtime,
                                DapManager.runtimeWhere(paths, launch.get("cwd") instanceof String cwd ? cwd : null),
                                stringMap(launch.get("env")));
                    }
                }
            });
        });
    }

    /**
     * What a failed Python launch says. A timeout is debugpy waiting for a program it could not start — an
     * interpreter that does not run — rather than anything the user can read in a Java exception's name.
     */
    static String launchFailure(Throwable error, Map<String, Object> launch) {
        Throwable t = error;
        while (t.getCause() != null && !(t instanceof java.util.concurrent.TimeoutException)) {
            t = t.getCause();
        }
        if (t instanceof java.util.concurrent.TimeoutException) {
            return tr(
                    "debug.error.debugpyNoStart",
                    String.valueOf(DapClient.requestTimeoutSeconds()),
                    String.valueOf(launch.get("python")));
        }
        return tr("debug.error.launchFailed", DapManager.msg(error));
    }

    /**
     * The Python that runs debugpy on {@code file}'s host: the host's {@code dap.python} tool, else the
     * Debugging setting when it is a bare command name (a path names a file on this machine), else
     * {@code python3}.
     */
    private List<String> remotePython(Path file) {
        String tool = com.editora.process.ExecTargets.remoteTool(file, "dap.python");
        if (tool != null && !tool.isBlank()) {
            return DapServerRegistry.tokenize(tool);
        }
        List<String> configured = DapServerRegistry.interpreterArgv("python", dap.shared.pythonCommand);
        if (!configured.isEmpty()
                && configured.get(0).indexOf('/') < 0
                && configured.get(0).indexOf('\\') < 0) {
            return configured;
        }
        return List.of(DapServerRegistry.DEFAULT_PYTHON_INTERPRETER);
    }

    /** debugpy on the host of {@code file}: asked for first, then run over an SSH channel as its stdio adapter. */
    void startRemoteStdio(
            Path file,
            DapServerRegistry.DapServerSpec spec,
            List<DapModels.FileBreakpoints> bps,
            @Nullable List<String> filters,
            long epoch,
            PythonLaunch.@Nullable Target module)
            throws Exception {
        var target = com.editora.process.ExecTargets.forPath(file);
        Path dir = file.getParent();
        DapManager.HostPython python = DapManager.HostPython.parse(remotePython(file));
        List<String> probe = python.argv(List.of("-c", "import debugpy"));
        if (!ProcessRunner.run(dir, java.time.Duration.ofSeconds(20), probe, python.env())
                .ok()) {
            dap.fail(epoch, tr("debug.error.remoteDebugpyMissing", python.executable(), target.id()));
            return;
        }
        if (!dap.isCurrent(epoch)) {
            return;
        }
        // The program runs as Run runs it there (the project's venv on the host, its python tool, PYTHONPATH);
        // the adapter stays on the host's debugpy-capable Python.
        DapManager.HostPython program = planProgram(file, target, false);
        if (module != null && "pytest".equals(module.module())) {
            module = pytestOrFallback(
                    module,
                    program,
                    epoch,
                    run -> ProcessRunner.run(dir, java.time.Duration.ofSeconds(20), run, program.env())
                            .ok());
            if (module == null) {
                return; // reported
            }
        }
        if (!dap.isCurrent(epoch)) {
            return;
        }
        List<String> argv = python.argv(spec.adapterArgs()); // -m debugpy.adapter
        Process proc = target.start(
                com.editora.process.ExecSpec.of(argv, dir, python.env(), com.editora.process.ExecSpec.Locale.USER)
                        .withLongLived(true)
                        .withDiscardedOutput(false, true));
        // A web server's reloader serves from a child process, which a host's launch does not follow: it is off.
        PythonLaunch.Target what = module != null
                ? withoutReload(module)
                : withoutReload(webOrScript(file, DapManager.readQuietly(file), dap.programArgs, false));
        dap.sessionEvents.onOutput(epoch, PythonLaunch.commandLine(program, what) + "\n", DapManager.COMMAND_CATEGORY);
        // The host's own PYTHONPATH is not known here (null): the plan's is used, else it is left as the host has it.
        Map<String, Object> launch = PythonLaunch.request(what, program, List.of(), null, ":", false);
        attachStdio(proc, spec, bps, filters, epoch, DapPaths.on(target, file.getFileSystem()), launch, false, null);
    }

    /**
     * What Debug File runs for the Python {@code file}: the development server of a web app it is
     * ({@link com.editora.run.PythonWebApps#serve}: {@code manage.py runserver}, Flask's, uvicorn's), else the file
     * with {@code args}. Paths as the machine the program runs on names them ({@code local}: this one).
     */
    static PythonLaunch.Target webOrScript(Path file, @Nullable String content, List<String> args, boolean local) {
        var exec = com.editora.process.ExecTargets.forPath(file);
        com.editora.run.PythonWebApps.Server web = com.editora.run.PythonWebApps.serve(
                file, content, args, d -> java.nio.file.Files.isRegularFile(d.resolve("__init__.py")));
        String program = local ? file.toString() : exec.toTargetPath(file);
        Path dir = file.getParent();
        String cwd = dir == null ? null : local ? DapManager.cwdOf(file) : exec.toTargetPath(dir);
        if (web == null) {
            return PythonLaunch.Target.script(program, args, cwd);
        }
        if (web.module() == null) {
            return PythonLaunch.Target.script(program, web.args(), cwd);
        }
        String folder = local ? web.folder().toString() : exec.toTargetPath(web.folder());
        return PythonLaunch.Target.module(web.module(), web.args(), folder);
    }

    /**
     * Whether a local launch serves a web app — its target a server ({@code python -m flask}, {@code manage.py}),
     * a launch.json configuration's {@code module} or {@code program} one, or the file a web app that starts its own
     * server ({@code app.run(debug=True)}) — and so follows subprocesses: the reloader's child is what serves.
     */
    static boolean servesWeb(
            PythonLaunch.Target target, @Nullable String content, @Nullable Map<String, Object> configured) {
        if (com.editora.run.PythonWebApps.servesWeb(target.module(), target.program())) {
            return true;
        }
        if (configured != null
                && com.editora.run.PythonWebApps.servesWeb(
                        configured.get("module") instanceof String m ? m : null,
                        configured.get("program") instanceof String p ? p : null)) {
            return true;
        }
        if (target.module() != null || target.program() == null || content == null) {
            return false;
        }
        Path name = Path.of(target.program()).getFileName();
        return com.editora.run.PythonWebApps.framework(name == null ? null : name.toString(), content) != null;
    }

    /** {@code target} with its web server's reloader off ({@link com.editora.run.PythonWebApps#withoutReload}). */
    static PythonLaunch.Target withoutReload(PythonLaunch.Target target) {
        List<String> args =
                com.editora.run.PythonWebApps.withoutReload(target.module(), target.program(), target.args());
        return new PythonLaunch.Target(target.program(), target.module(), args, target.cwd(), target.fallback());
    }

    /** The interpreter argv a debugpy {@code launch} runs the program with ({@code python} + {@code pythonArgs});
     *  empty when it names none and debugpy uses its own. */
    static List<String> pythonRuntime(Map<String, Object> launch) {
        if (!(launch.get("python") instanceof String python) || python.isBlank()) {
            return List.of();
        }
        List<String> argv = new ArrayList<>(List.of(python));
        if (launch.get("pythonArgs") instanceof List<?> args) {
            args.forEach(a -> argv.add(String.valueOf(a)));
        }
        return argv;
    }

    /** A launch body's {@code env} as strings (a null value, which unsets, stays null); empty when absent. */
    private static Map<String, String> stringMap(@Nullable Object env) {
        if (!(env instanceof Map<?, ?> m)) {
            return Map.of();
        }
        Map<String, String> out = new java.util.LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        return out;
    }
}
