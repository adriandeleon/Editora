package com.editora.dap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.editora.process.BusyExecutable;
import com.editora.process.ProcessRunner;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Sessions of the external debug adapters (a {@link DebugLanguage} whose adapter is a program of its own: Delve,
 * CodeLLDB, GDB, rdbg, bash-debug and the rest): finding the adapter's command, starting it over stdio or a
 * socket it announces, and attaching to a debug server that already listens on a port.
 *
 * <p>Moved out of {@link DapManager} for size: the code is the manager's, reaching its session state through
 * {@link #dap}, and runs on the same threads with the same session checks as it did there.
 */
final class ExternalLaunches {
    private final DapManager dap;

    ExternalLaunches(DapManager dap) {
        this.dap = dap;
    }

    /**
     * Finds external adapter language {@code language}'s adapter: {@code command} (a configured executable; blank:
     * the profile's own command, looked up on the PATH) when {@code on}. Off the FX thread; {@code onDone} hears
     * whether it was found, on the FX thread.
     */
    void configureExternal(
            DebugLanguage language, boolean on, String command, java.util.function.@Nullable Consumer<Boolean> onDone) {
        ExternalAdapter external = language.external();
        if (external == null) {
            throw new IllegalArgumentException(language.id() + " is not an external adapter");
        }
        dap.io.submit(() -> {
            String found = on ? locateExternal(language, command) : null;
            if (found == null) {
                dap.shared.externalLocated.remove(language.id());
            } else {
                dap.shared.externalLocated.put(language.id(), found);
            }
            if (onDone != null) {
                dap.fx(() -> onDone.accept(found != null));
            }
        });
    }

    /** {@code path} with {@code first} before its own folders, none twice. Pure. */
    static String pathWithFirst(List<String> first, @Nullable String path, String separator) {
        java.util.LinkedHashSet<String> dirs = new java.util.LinkedHashSet<>();
        for (String d : first) {
            if (d != null && !d.isBlank()) {
                dirs.add(d);
            }
        }
        if (path != null && !path.isEmpty()) {
            dirs.addAll(List.of(path.split(java.util.regex.Pattern.quote(separator))));
        }
        return String.join(separator, dirs);
    }

    /**
     * External adapter language {@code language}'s adapter as an executable file: {@code command} (a configured path
     * or command), or when blank the profile's own command ({@code dlv}), found on the PATH; null when not found.
     * Blocking: off the FX thread.
     */
    static @Nullable String locateExternal(DebugLanguage language, @Nullable String command) {
        ExternalAdapter external = language.external();
        if (external == null) {
            return null;
        }
        if (DebugLanguages.CODELLDB.equals(external)) {
            // CodeLLDB lives in its extension's folder (Editora's install, VS Code's), or is named by its path.
            Path home = Path.of(System.getProperty("user.home", ""));
            String onPath = command == null || command.isBlank() ? locateExecutable("codelldb") : null;
            return DebugAdapterLocator.locateCodelldb(command, home)
                    .map(Path::toString)
                    .orElse(onPath);
        }
        InstalledAdapter installed = installedAdapter(language);
        if (installed != null) {
            // In Editora's install, a VS Code extension, or named by its path; else the program on the PATH.
            Path home = Path.of(System.getProperty("user.home", ""));
            String onPath = installed.program() && (command == null || command.isBlank())
                    ? locateExecutable(installed.file())
                    : null;
            return installed.locate(command, home).map(Path::toString).orElse(onPath);
        }
        String asked = command == null || command.isBlank() ? external.command().getFirst() : command.strip();
        String found = locateExecutable(asked);
        if (found != null || asked.indexOf('/') >= 0 || asked.indexOf('\\') >= 0) {
            return found; // a path is that file, or nothing
        }
        // `go install` puts Delve in the Go bin folder, which may not have been there when Editora read its PATH.
        boolean windows = com.editora.Os.isWindows();
        Path home = Path.of(System.getProperty("user.home", ""));
        List<Path> toolDirs = DebugLanguages.GO.equals(language)
                ? goBinDirs(System.getenv(), home)
                : DebugLanguages.RUBY.equals(language) ? gemUserBinDirs(home) : List.of();
        for (Path dir : toolDirs) {
            String inDir = locateExecutable(
                    dir.resolve(windows ? asked + ".exe" : asked).toString());
            if (inDir != null) {
                return inDir;
            }
        }
        return null;
    }

    /** The installable adapter {@code language} runs on (C#'s netcoredbg), or null when it has none. */
    static @Nullable InstalledAdapter installedAdapter(DebugLanguage language) {
        if (DebugLanguages.CSHARP.equals(language)) {
            return InstalledAdapter.NETCOREDBG;
        }
        if (DebugLanguages.BASH.equals(language)) {
            return InstalledAdapter.BASH_DEBUG;
        }
        if (DebugLanguages.LUA.equals(language)) {
            return InstalledAdapter.LUA_DEBUG;
        }
        if (DebugLanguages.POWERSHELL.equals(language)) {
            return InstalledAdapter.POWERSHELL;
        }
        if (DebugLanguages.ELIXIR.equals(language)) {
            return InstalledAdapter.ELIXIR_LS;
        }
        if (DebugLanguages.FIREFOX.equals(language)) {
            return InstalledAdapter.FIREFOX_DEBUG;
        }
        return DebugLanguages.PHP.equals(language) ? InstalledAdapter.PHP_DEBUG : null;
    }

    /**
     * Where {@code go install} puts programs: {@code $GOBIN}, else each {@code $GOPATH} folder's {@code bin}, else
     * {@code ~/go/bin}. Pure.
     */
    static List<Path> goBinDirs(Map<String, String> env, Path home) {
        List<Path> out = new ArrayList<>();
        String gobin = env.get("GOBIN");
        if (gobin != null && !gobin.isBlank()) {
            out.add(Path.of(gobin.strip()));
        }
        String gopath = env.get("GOPATH");
        if (gopath != null && !gopath.isBlank()) {
            for (String dir : gopath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                if (!dir.isBlank()) {
                    out.add(Path.of(dir.strip(), "bin"));
                }
            }
        }
        out.add(home.resolve("go").resolve("bin"));
        return out;
    }

    /**
     * Where {@code gem install --user-install} puts programs: {@code ~/.local/share/gem/ruby/<version>/bin} (Ruby 3.3's
     * XDG folder) and {@code ~/.gem/ruby/<version>/bin}, the newest version first. Lists folders.
     */
    static List<Path> gemUserBinDirs(Path home) {
        List<Path> out = new ArrayList<>();
        for (Path base : List.of(
                home.resolve(".local").resolve("share").resolve("gem").resolve("ruby"),
                home.resolve(".gem").resolve("ruby"))) {
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (java.util.stream.Stream<Path> versions = Files.list(base)) {
                versions.filter(Files::isDirectory)
                        .sorted(java.util.Comparator.comparing(
                                        (Path v) -> v.getFileName().toString(),
                                        (a, b) -> DebugAdapterLocator.compareVersions(a, b))
                                .reversed())
                        .forEach(v -> out.add(v.resolve("bin")));
            } catch (java.io.IOException | RuntimeException e) {
                // unreadable: skip
            }
        }
        return out;
    }

    /** {@code command} as an executable file: itself when it is a path, else found on the PATH; null when neither. */
    static @Nullable String locateExecutable(String command) {
        Path p;
        try {
            p = Path.of(ProcessRunner.resolveExecutable(List.of(command)).getFirst());
        } catch (java.nio.file.InvalidPathException e) {
            return null; // not a name a file can have
        }
        return p.isAbsolute() && Files.isRegularFile(p) && Files.isExecutable(p) ? p.toString() : null;
    }

    /**
     * As {@link #startExternal(Path, DebugLanguage, Map)}, with {@code adapterTail} after the adapter's command: the
     * program, for an adapter that is the debugged program itself (Ruby's {@code rdbg … -- ruby script.rb}), which
     * then runs in the request's {@code cwd}.
     */
    void startExternal(Path file, DebugLanguage language, Map<String, Object> launch, List<String> adapterTail) {
        if (dap.managerClosed) {
            return;
        }
        if (!dap.shared.enabled) {
            dap.listener.onError(tr("debug.error.disabled"));
            return;
        }
        ExternalAdapter external = language.external();
        if (external == null) {
            dap.listener.onError(tr(language.unavailableKey()));
            return;
        }
        if (dap.client != null) {
            dap.stop(); // one session at a time
        }
        List<String> tail = List.copyOf(adapterTail);
        dap.restartAction = () -> startExternal(file, language, launch, tail);
        dap.debugFile = file;
        long epoch = dap.beginSession();
        // Delve's program for this session (a restart builds it again): kept from another window's clean-up
        dap.goDebugBinary = launch.get("output");
        GoLaunch.holdDebugBinary(dap.goDebugBinary);
        dap.postMortem = CoreDumps.isCore(launch);
        dap.remoteEntryPending = RemoteNativeAttach.isRemote(launch);
        dap.setState(DapManager.State.STARTING);
        List<DapModels.FileBreakpoints> bps = dap.shared.breakpointsSupplier.get();
        List<String> filters = dap.filtersFor(language.id());
        String executable = dap.shared.externalLocated.get(language.id());
        dap.startupTask = dap.io.submit(() -> {
            try {
                runExternal(file, language, external, executable, bps, filters, epoch, launch, tail);
            } catch (Exception e) {
                dap.fail(epoch, tr("debug.error.startFailed", DapManager.msg(e)));
            }
        });
    }

    private void runExternal(
            Path file,
            DebugLanguage language,
            ExternalAdapter external,
            @Nullable String executable,
            List<DapModels.FileBreakpoints> bps,
            @Nullable List<String> filters,
            long epoch,
            Map<String, Object> request,
            List<String> programTail)
            throws Exception {
        int port = external.transport() == ExternalAdapter.Transport.PORT_ARGUMENT ? DapManager.freePort() : -1;
        // vscode-bash-debug checks each program it names: its bashdb, bash, cat, mkfifo, pkill, by absolute path.
        Map<String, Object> sent = request;
        if (DebugLanguages.GDB.equals(language) && request.containsKey(RemoteNativeAttach.SOURCE_FILE_MAP)) {
            sent = new java.util.LinkedHashMap<>(request);
            sent.remove(RemoteNativeAttach.SOURCE_FILE_MAP); // GDB's command line carries it (below)
        }
        Map<String, Object> launch = DebugLanguages.BASH.equals(language) && executable != null
                ? ScriptLaunch.withBashTools(
                        sent,
                        Path.of(executable),
                        name -> ProcessRunner.resolveExecutable(List.of(name)).getFirst())
                : DebugLanguages.LUA.equals(language) && executable != null
                        ? ScriptLaunch.withLuaRuntime( // where lldebugger.lua is, and which Lua is installed
                                sent,
                                Path.of(executable),
                                name -> ProcessRunner.resolveExecutable(List.of(name))
                                        .getFirst())
                        : sent;
        List<String> argv = new ArrayList<>(external.argv(executable, port));
        boolean powershell = DebugLanguages.POWERSHELL.equals(language) && executable != null;
        if (powershell) { // PSES's start script, on pwsh, as a debug adapter on stdio
            argv = new ArrayList<>(List.of(PowerShellLaunch.executable(
                    name -> Path.of(ProcessRunner.resolveExecutable(List.of(name))
                                    .getFirst())
                            .isAbsolute(),
                    com.editora.Os.isWindows())));
            argv.addAll(PowerShellLaunch.serviceArgs(
                    Path.of(executable), Path.of(System.getProperty("java.io.tmpdir")), true, epoch));
        }
        if (language == DebugLanguages.GDB) {
            argv.addAll(1, EvaluationLimits.gdbArguments(dap.shared.evaluationTimeLimit)); // before -i dap
            // GDB's DAP mode has no source map: the request's is substitute-path on its command line, not sent.
            argv.addAll(RemoteNativeAttach.gdbSubstitutions(
                    RemoteNativeAttach.sourceFileMap(request.get(RemoteNativeAttach.SOURCE_FILE_MAP))));
        }
        argv.addAll(programTail);
        if (argv.getFirst().endsWith(".js")) {
            argv.addFirst(
                    dap.detection.adapterNode()); // a Node.js adapter (vscode-php-debug), on the Node.js Settings names
        }
        ProcessBuilder pb = new ProcessBuilder(ProcessRunner.resolveExecutable(argv));
        ProcessRunner.applyUserEnv(pb);
        if (!programTail.isEmpty() && launch.get("env") instanceof Map<?, ?> env) {
            // the adapter is the program: the configuration's environment is its own
            env.forEach((k, v) -> pb.environment().put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        }
        List<String> first = dap.shared.externalPathDirs.getOrDefault(language.id(), List.of());
        if (!first.isEmpty()) {
            Map<String, String> env = pb.environment();
            String key = env.keySet().stream()
                    .filter(k -> k.equalsIgnoreCase("PATH"))
                    .findFirst()
                    .orElse("PATH");
            env.put(key, pathWithFirst(first, env.get(key), java.io.File.pathSeparator));
        }
        Path cwd = adapterFolder(file, launch, !programTail.isEmpty());
        if (cwd != null && Files.isDirectory(cwd)) {
            pb.directory(cwd.toFile());
        }
        dap.sessionEvents.onOutput(epoch, String.join(" ", pb.command()) + "\n", DapManager.COMMAND_CATEGORY);
        if (external.transport() == ExternalAdapter.Transport.ANNOUNCED_PORT) {
            pb.redirectErrorStream(true); // the announcement may come on either stream
        }
        Process proc = BusyExecutable.start(pb);
        if (!dap.isCurrent(epoch)) {
            com.editora.process.ProcessRegistry.killTree(proc);
            return;
        }
        dap.programAdapter = programTail.isEmpty() ? null : new DapManager.ProgramAdapter(epoch, proc);
        boolean attach = "attach".equals(launch.get("request"));
        DapClient c = new DapClient(dap.sessionHost(epoch));
        c.setPaths(DapPaths.LOCAL);
        c.setBreakpoints(bps);
        c.setExceptionFilters(filters);
        c.setRunsDebuggee(dap.shared.programConsole);
        if (attach) {
            c.setTerminateOnDisconnect(false); // Stop detaches; the program runs on
        }
        c.setTerminateFirst(DebugLanguages.FLUTTER.equals(language) && !attach); // else the app outlives Stop
        c.setLaunchWait(launchWait(language));
        AdapterStderr stderr = null;
        java.util.concurrent.CompletableFuture<org.eclipse.lsp4j.debug.Capabilities> connected;
        switch (external.transport()) {
            case STDIO -> {
                // DAP is on stdin/stdout; an undrained PIPE deadlocks. PSES writes the script's output there.
                stderr = dap.adapterStderr(
                        epoch,
                        powershell
                                ? AdapterStderr.drain(
                                        proc, line -> dap.sessionEvents.onOutput(epoch, line + "\n", "stdout"))
                                : AdapterStderr.drain(proc));
                if (!dap.publishClient(epoch, c)) {
                    com.editora.process.ProcessRegistry.killTree(proc);
                    c.dispose();
                    return;
                }
                connected = c.connectStdio(proc, language.adapterId());
            }
            case PORT_ARGUMENT -> {
                stderr = dap.adapterStderr(epoch, AdapterStderr.drain(proc));
                drainToConsole(proc.getInputStream(), epoch);
                c.setAdapterProcess(proc); // killed on dispose: it serves the socket
                if (!dap.publishClient(epoch, c)) {
                    c.dispose();
                    return;
                }
                connected = c.connect(port, language.adapterId());
            }
            case ANNOUNCED_PORT -> {
                c.setAdapterProcess(proc);
                int announced = awaitAnnouncement(proc, external, epoch);
                if (announced < 0) {
                    c.dispose();
                    dap.fail(
                            epoch,
                            tr(
                                    "debug.error.adapterNoPort",
                                    String.join(" ", pb.command()),
                                    DapManager.ANNOUNCE_TIMEOUT.toSeconds()));
                    return;
                }
                if (!dap.publishClient(epoch, c)) {
                    c.dispose();
                    return;
                }
                connected = c.connect(announced, language.adapterId());
            }
            default -> throw new IllegalStateException(external.transport().toString());
        }
        AdapterStderr tail = stderr;
        connected.whenComplete((caps, e) -> {
            if (e != null) {
                dap.fail(
                        epoch,
                        tr("debug.error.adapterConnect", language.displayName(), DapManager.msg(e))
                                + AdapterStderr.suffix(tail));
                return;
            }
            if (!dap.isCurrent(epoch)) {
                c.dispose();
                return;
            }
            dap.sessionEvents
                    .trackLaunch(epoch, false, () -> attach ? c.attach(launch) : c.launch(launch))
                    .whenComplete((v, le) -> {
                        if (le != null) {
                            String key = attach ? "debug.error.attachFailed" : "debug.error.launchFailed";
                            dap.fail(epoch, tr(key, DapManager.msg(le)) + AdapterStderr.suffix(tail));
                        }
                    });
            dap.fx(() -> {
                if (dap.isCurrent(epoch, c)) {
                    dap.setState(DapManager.State.RUNNING);
                }
            });
        });
    }

    /**
     * How long {@code language}'s adapter is given to answer {@code launch}: ElixirLS compiles the project (a fresh
     * Phoenix app's dependencies, all of them) and interprets its modules before it answers, so it gets {@link
     * ElixirLaunch#LAUNCH_WAIT}; the rest the usual request wait. Pure.
     */
    static java.time.Duration launchWait(DebugLanguage language) {
        return DebugLanguages.ELIXIR.equals(language)
                ? ElixirLaunch.LAUNCH_WAIT
                : java.time.Duration.ofSeconds(DapClient.requestTimeoutSeconds());
    }

    /**
     * The folder an external adapter starts in: the request's {@code cwd} when the adapter is the program itself
     * ({@code programRuns}), else {@code file}'s folder (or {@code file}, a folder). Pure but for the folder check.
     */
    static @Nullable Path adapterFolder(Path file, Map<String, Object> launch, boolean programRuns) {
        if (programRuns && launch.get("cwd") instanceof String c && !c.isBlank()) {
            try {
                Path dir = Path.of(c);
                if (Files.isDirectory(dir)) {
                    return dir;
                }
            } catch (java.nio.file.InvalidPathException e) {
                // not a folder: the file's
            }
        }
        return Files.isDirectory(file) ? file : file.getParent();
    }

    /**
     * Reads {@code proc}'s output until it announces its port (passing the lines before it to the console), then
     * passes the rest on as the program's output — Delve writes its debuggee's there. -1 when it ends, or says
     * nothing, within {@link DapManager#ANNOUNCE_TIMEOUT}.
     */
    private int awaitAnnouncement(Process proc, ExternalAdapter external, long epoch) {
        java.util.concurrent.CompletableFuture<Integer> port = new java.util.concurrent.CompletableFuture<>();
        Thread reader = new Thread(
                () -> {
                    try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(
                            proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = in.readLine()) != null) {
                            int found = port.isDone() ? -1 : external.announcedPort(line);
                            if (found > 0) {
                                port.complete(found);
                            } else {
                                dap.sessionEvents.onOutput(
                                        epoch, line + "\n", port.isDone() ? "stdout" : DapManager.COMMAND_CATEGORY);
                            }
                        }
                    } catch (java.io.IOException e) {
                        // the adapter ended: whoever waits hears -1
                    }
                    port.complete(-1);
                },
                "dap-external-adapter-output");
        reader.setDaemon(true);
        reader.start();
        try {
            return port.get(DapManager.ANNOUNCE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Passes {@code out} (a socket adapter's standard output) to the console as program output, on a daemon thread. */
    private void drainToConsole(java.io.InputStream out, long epoch) {
        Thread t = new Thread(
                () -> {
                    try (java.io.BufferedReader in = new java.io.BufferedReader(
                            new java.io.InputStreamReader(out, java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = in.readLine()) != null) {
                            dap.sessionEvents.onOutput(epoch, line + "\n", "stdout");
                        }
                    } catch (java.io.IOException e) {
                        // the adapter ended
                    }
                },
                "dap-external-adapter-stdout");
        t.setDaemon(true);
        t.start();
    }

    void startSocketAttach(
            Path anchor,
            DebugLanguage language,
            String host,
            int port,
            Map<String, Object> attach,
            boolean releaseOnDetach) {
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
        dap.restartAction = () -> startSocketAttach(anchor, language, host, port, attach, releaseOnDetach);
        dap.debugFile = anchor;
        long epoch = dap.beginSession();
        dap.setState(DapManager.State.STARTING);
        List<DapModels.FileBreakpoints> bps = dap.shared.breakpointsSupplier.get();
        List<String> filters = dap.filtersFor(language.id());
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
                c.setReleaseBeforeDetach(releaseOnDetach);
                if (!dap.publishClient(epoch, c)) {
                    c.dispose();
                    return;
                }
                String address = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
                dap.sessionEvents.onOutput(
                        epoch, tr("debug.console.attaching", address + ":" + port) + "\n", DapManager.COMMAND_CATEGORY);
                c.connect(host, port, language.adapterId()).whenComplete((caps, e) -> {
                    if (e != null) {
                        dap.fail(epoch, tr("debug.error.adapterConnect", language.displayName(), DapManager.msg(e)));
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
}
