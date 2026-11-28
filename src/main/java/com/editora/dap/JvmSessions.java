package com.editora.dap;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Debug sessions of the other JVM languages: a script or program run under Editora's own JVM adapter, an
 * attach to a running JVM, and the sessions a language server hosts (Metals).
 *
 * <p>Moved out of {@link DapManager} for size: the code is the manager's, reaching its session state through
 * {@link #dap}, and runs on the same threads with the same session checks as it did there.
 */
final class JvmSessions {
    private final DapManager dap;

    JvmSessions(DapManager dap) {
        this.dap = dap;
    }

    /**
     * Debugs JVM-language script {@code file} ({@code groovy}, {@code clojure}) in {@code cwd} — a run
     * configuration's folder, or null for the one the tool runs it in —
     * with the arguments and environment set beforehand. Restart repeats it.
     */
    void startJvmScript(Path file, String language, @Nullable Path cwd) {
        dap.jvmCwd = cwd;
        dap.jvmJar = null;
        dap.startProgram(file, language);
        dap.restartAction = () -> startJvmScript(file, language, cwd);
    }

    /** The {@code java} that runs {@link DapManager#jvmJar}: the session JDK's. */
    private volatile String jvmJava = "java";

    /**
     * Debugs compiled Kotlin program {@code file} — its {@code jar}, built beforehand — as
     * {@code java [VM arguments] -jar <jar> args…} on {@code java}, in {@code cwd} (null: the file's folder), with
     * the arguments, VM arguments and environment set beforehand. The debugger's agent goes on that command line.
     * Restart repeats it with the same jar; the coordinator's own restart compiles an edited file again.
     */
    void startJvmProgram(Path file, String language, @Nullable Path cwd, Path jar, String java) {
        dap.jvmCwd = cwd;
        dap.jvmJar = jar;
        jvmJava = java == null || java.isBlank() ? "java" : java;
        dap.startProgram(file, language);
        dap.restartAction = () -> startJvmProgram(file, language, cwd, jar, java);
    }

    /**
     * Attaches Editora's JVM adapter to a JVM listening at {@code host:port} — one a build tool started under a
     * suspended JDWP agent — for a program in a language java-debug cannot map ({@code language}), anchored at
     * {@code file}. Ending the session detaches; the JVM's owner stops it. Restart attaches again.
     */
    void startJvmAttach(Path file, String language, String host, int port) {
        DapServerRegistry.DapServerSpec spec = DapServerRegistry.specFor(language);
        if (spec == null || spec.kind() != DapServerRegistry.Kind.JVM) {
            dap.listener.onError(tr("debug.error.noAdapter"));
            return;
        }
        dap.restartAction = () -> startJvmAttach(file, language, host, port);
        dap.debugFile = file;
        long epoch = dap.beginSession();
        dap.setState(DapManager.State.STARTING);
        List<DapModels.FileBreakpoints> bps = dap.shared.breakpointsSupplier.get();
        List<String> filters = dap.filtersFor(language);
        dap.startupTask = dap.io.submit(() -> {
            try {
                Map<String, Object> attach = new java.util.LinkedHashMap<>();
                attach.put("hostName", host == null || host.isBlank() ? "localhost" : host);
                attach.put("port", port);
                attach.put("language", language);
                Path root = file == null ? null : jvmSourceRoot(file);
                if (root != null) {
                    attach.put("cwd", root.toString());
                }
                if (file != null) {
                    attach.put("sourceFiles", List.of(file.toString()));
                }
                int adapterPort = com.editora.dap.jvm.JvmDebugServer.start(new com.editora.dap.jvm.JvmDebugAdapter());
                DapClient c = new DapClient(dap.sessionHost(epoch));
                c.setPaths(DapPaths.LOCAL);
                c.setBreakpoints(bps);
                c.setExceptionFilters(filters);
                if (!dap.publishClient(epoch, c)) {
                    c.dispose();
                    return;
                }
                c.connect(adapterPort, spec.adapterId()).whenComplete((caps, e) -> {
                    if (e != null) {
                        dap.fail(epoch, tr("debug.error.connectFailed", DapManager.msg(e)));
                        return;
                    }
                    if (!dap.isCurrent(epoch)) {
                        c.dispose();
                        return;
                    }
                    c.attach(attach).whenComplete((v, ae) -> {
                        if (ae != null) {
                            dap.fail(epoch, tr("debug.error.launchFailed", DapManager.msg(ae)));
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

    /** The folder an attached program's sources are looked up under: the file's build root, else its folder. */
    private static @Nullable Path jvmSourceRoot(Path file) {
        Path dir = file.toAbsolutePath().getParent();
        for (Path p = dir; p != null; p = p.getParent()) {
            for (String marker :
                    List.of("settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle", "pom.xml")) {
                if (java.nio.file.Files.isRegularFile(p.resolve(marker))) {
                    return p;
                }
            }
        }
        return dir;
    }

    /**
     * Editora's own JVM adapter ({@link com.editora.dap.jvm.JvmDebugAdapter}), served in this process on a
     * loopback port: the program runs as Run runs it ({@code groovy Script.groovy args…} on the session's JDK),
     * with the JDWP agent in the variable its launcher hands the JVM, started by this client
     * ({@code runInTerminal}) so its output, input and exit code are the Debug console's.
     */
    void startJvm(
            Path file,
            DapServerRegistry.DapServerSpec spec,
            List<DapModels.FileBreakpoints> bps,
            @Nullable List<String> filters,
            long epoch)
            throws java.io.IOException {
        String language = spec.language();
        if ("clojure".equals(language)
                && file.getFileName() != null
                && !com.editora.run.ClojureRun.runnable(file.getFileName().toString())) {
            dap.fail(
                    epoch,
                    tr(
                            "debug.error.notDebuggable.clojureFile",
                            file.getFileName().toString()));
            return;
        }
        String toolName = com.editora.run.JvmScriptRun.tool(language, file);
        if (!com.editora.run.JvmScriptRun.debuggable(toolName)) {
            // Babashka is a native program: there is no JVM for a debugger to attach to.
            dap.fail(epoch, tr("debug.error.notDebuggable." + toolName));
            return;
        }
        String tool = toolName == null ? null : dap.jvmToolLocator.apply(toolName);
        if (toolName == null || tool == null) {
            dap.fail(epoch, tr(com.editora.run.JvmScriptRun.missingToolKeyFor(toolName)));
            return;
        }
        Map<String, Object> launch = new java.util.LinkedHashMap<>();
        Map<String, String> launchEnv = dap.env;
        Path directory = null;
        Path jar = dap.jvmJar;
        List<String> argv;
        if (jar != null) {
            // A Kotlin program: `java` is its own launcher, its VM arguments on that command line.
            argv = com.editora.run.KotlinRun.programArgv(jvmJava, jvmOptions(), jar.toString(), dap.programArgs);
            launch.put("jvmOptionsInArgv", true); // the agent goes first
        } else if (com.editora.run.JvmScriptRun.takesJvmOptionsInArgv(language)) {
            // Scala CLI: the agent (and the VM arguments) as --java-opt; the file, or its project folder with
            // the file's main class. It compiles before the JVM starts — a first run fetches the compiler too.
            com.editora.run.ScalaRun.Owner owner =
                    com.editora.run.ScalaRun.ownerOf(file, null, java.nio.file.Files::isRegularFile);
            List<String> inputs = com.editora.run.ScalaRun.cliInputs(
                    file.toString(),
                    owner,
                    owner.root() == null ? null : owner.root().toString(),
                    com.editora.run.ScalaRun.mainClass(DapManager.readQuietly(file)));
            argv = com.editora.run.JvmScriptRun.scalaCliDebug(tool, inputs, jvmOptions(), dap.programArgs);
            launch.put("agentArgument", com.editora.run.JvmScriptRun.AGENT_ARGUMENT);
            launch.put("connectTimeoutMs", SCALA_CLI_CONNECT_TIMEOUT_MS);
            if (owner.root() != null) {
                launch.put("sourceRoots", List.of(owner.root().toString()));
            }
        } else {
            // The configuration's VM arguments go where the tool hands them to its JVM, beside the debugger's agent.
            com.editora.run.JvmScriptRun.Launch how = com.editora.run.JvmScriptRun.launch(
                    language,
                    toolName,
                    tool,
                    file,
                    file.toString(),
                    dap.programArgs,
                    dap.vmArgsFor(LaunchConfig.Target.LOCAL),
                    true,
                    dap.env,
                    System.getenv());
            argv = how.argv();
            launchEnv = how.env();
            directory = how.directory();
            launch.put("jvmOptionsVariable", com.editora.run.JvmScriptRun.jvmOptionsVariable(language, toolName));
            String prefix = com.editora.run.JvmScriptRun.jvmOptionPrefix(toolName);
            if (prefix != null) {
                launch.put("jvmOptionPrefix", prefix);
            }
            if (directory != null) {
                launch.put("sourceRoots", List.of(directory.toString()));
            }
        }
        Map<String, String> programEnv = launchEnv;
        launch.put("argv", argv);
        Path folder = dap.jvmCwd != null ? dap.jvmCwd : directory;
        launch.put("cwd", folder != null ? folder.toString() : DapManager.cwdOf(file));
        launch.put("env", programEnv);
        launch.put("language", language);
        launch.put("sourceFiles", List.of(file.toString())); // its frames find it whatever folder it sits in
        dap.sessionEvents.onOutput(
                epoch, com.editora.run.RunService.commandLine(argv) + "\n", DapManager.COMMAND_CATEGORY);
        int port = com.editora.dap.jvm.JvmDebugServer.start(new com.editora.dap.jvm.JvmDebugAdapter());
        DapClient c = new DapClient(dap.sessionHost(epoch));
        c.setPaths(DapPaths.LOCAL);
        c.setBreakpoints(bps);
        c.setExceptionFilters(filters);
        c.setRunsDebuggee(true); // the program is this client's: the Debug console's output, input and exit code
        if (!dap.publishClient(epoch, c)) {
            c.dispose();
            return;
        }
        c.connect(port, spec.adapterId()).whenComplete((caps, e) -> {
            if (e != null) {
                dap.fail(epoch, tr("debug.error.connectFailed", DapManager.msg(e)));
                return;
            }
            if (!dap.isCurrent(epoch)) {
                c.dispose();
                return;
            }
            c.launch(launch).whenComplete((v, le) -> {
                if (le != null) {
                    dap.fail(epoch, tr("debug.error.launchFailed", DapManager.msg(le)));
                }
            });
            dap.fx(() -> {
                if (dap.isCurrent(epoch, c)) {
                    dap.setState(DapManager.State.RUNNING);
                    dap.listener.onDebuggeeRuntime(List.of(tool), DapManager.runtimeWhere(file), programEnv);
                }
            });
        });
    }

    /** How long a Scala CLI program may take to compile and connect back (a first run downloads the compiler). */
    private static final long SCALA_CLI_CONNECT_TIMEOUT_MS = 5 * 60_000;

    /** How long a language server may take to start a debug adapter: Metals compiles the build target first. */
    private static final java.time.Duration HOSTED_START_TIMEOUT = java.time.Duration.ofMinutes(10);

    /** The VM arguments set for the next launch, as arguments. */
    private List<String> jvmOptions() {
        List<String> list = dap.vmArgList;
        return list != null ? list : com.editora.run.ProgramArgs.tokenize(dap.vmArgs);
    }

    /**
     * Whether {@code file} is debugged by its language server's own adapter ({@link DapServerRegistry.Kind
     * #LANGUAGE_SERVER}) rather than the fallback: for Scala, a file of an sbt, Mill, Gradle or Maven build —
     * Metals has imported it and knows its classpath. A script, or a Scala CLI project, runs under Scala CLI.
     */
    static boolean serverDebugs(Path file, String language) {
        if (!"scala".equals(language)) {
            return true;
        }
        com.editora.run.ScalaRun.Build build = com.editora.run.ScalaRun.ownerOf(
                        file, null, java.nio.file.Files::isRegularFile)
                .build();
        return build == com.editora.run.ScalaRun.Build.SBT
                || build == com.editora.run.ScalaRun.Build.MILL
                || build == com.editora.run.ScalaRun.Build.OTHER;
    }

    /**
     * A debug adapter the file's language server starts ({@link HostedDebugAdapter}): Metals'
     * {@code debug-adapter-start} with the file, its arguments, VM arguments and environment compiles the build
     * target, starts scala-debug-adapter and answers its socket. The adapter runs the program and reports its
     * output; this client only connects, sends {@code launch} and the breakpoints.
     */
    void startHosted(
            Path file,
            DapServerRegistry.DapServerSpec spec,
            List<DapModels.FileBreakpoints> bps,
            @Nullable List<String> filters,
            long epoch) {
        String language = spec.language();
        String command = HostedDebugAdapter.startCommand(language);
        Map<String, Object> params =
                HostedDebugAdapter.discovery(file.toUri().toString(), false, dap.programArgs, jvmOptions(), dap.env);
        dap.fx(() -> {
            if (!dap.isCurrent(epoch)) {
                return;
            }
            if (command == null || !dap.lsp.isManaged(file)) {
                dap.fail(
                        epoch,
                        tr("debug.error.hostedNoServer", file.getFileName().toString()));
                return;
            }
            dap.sessionEvents.onOutput(epoch, tr("debug.hosted.starting") + "\n", DapManager.COMMAND_CATEGORY);
            dap.executeCommand(file, command, List.of(params), HOSTED_START_TIMEOUT, (res, err) -> {
                if (!dap.isCurrent(epoch)) {
                    return;
                }
                if (err != null) {
                    dap.fail(epoch, tr("debug.error.sessionFailed", DapManager.msg(err)));
                    return;
                }
                HostedDebugAdapter.Address address = HostedDebugAdapter.address(res);
                if (address == null) {
                    dap.fail(epoch, tr("debug.error.noPort"));
                    return;
                }
                // The adapter (and the program it starts) live inside that server: pin it for the session.
                dap.lspLease.run();
                dap.lspLease = dap.lsp.retain(file);
                DapClient c = new DapClient(dap.sessionHost(epoch));
                c.setPaths(DapPaths.LOCAL);
                c.setBreakpoints(bps);
                c.setExceptionFilters(filters);
                if (!dap.publishClient(epoch, c)) {
                    c.dispose();
                    return;
                }
                Map<String, Object> launch = new java.util.LinkedHashMap<>();
                launch.put("name", address.name().isBlank() ? file.getFileName().toString() : address.name());
                launch.put("noDebug", false);
                dap.io.submit(() -> c.connect(address.port(), spec.adapterId()).whenComplete((caps, e) -> {
                    if (e != null) {
                        dap.fail(epoch, tr("debug.error.connectFailed", DapManager.msg(e)));
                        return;
                    }
                    if (!dap.isCurrent(epoch, c)) {
                        c.dispose();
                        return;
                    }
                    c.launch(launch).whenComplete((v, le) -> {
                        if (le != null) {
                            dap.fail(epoch, tr("debug.error.launchFailed", DapManager.msg(le)));
                            return;
                        }
                        dap.fx(() -> {
                            if (dap.isCurrent(epoch, c) && dap.state == DapManager.State.STARTING) {
                                dap.setState(DapManager.State.RUNNING);
                            }
                        });
                    });
                }));
            });
        });
    }
}
