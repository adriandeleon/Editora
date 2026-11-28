package com.editora.dap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;

import com.editora.process.ProcessRunner;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Java debug sessions run through jdtls's java-debug: resolving the main class, the classpath and the Java
 * executable, compiling a loose or shebang file first, then starting the debug session jdtls hosts and
 * connecting to its socket. Also the alias that maps a compiled shebang copy back to the file the user has open.
 *
 * <p>Moved out of {@link DapManager} for size: the code is the manager's, reaching its session state through
 * {@link #dap}, and runs on the same threads with the same session checks as it did there.
 */
final class JavaLaunches {
    private final DapManager dap;

    JavaLaunches(DapManager dap) {
        this.dap = dap;
    }

    /** Temporary .java copy used to compile an extensionless shebang, scoped to its launch epoch. */
    private volatile DapManager.@Nullable SourceAlias sourceAlias;

    /** Removes {@link DapManager#compilationDirectory} on its target ({@code null}: a folder of this machine). */
    private volatile @Nullable Runnable compilationRemoval;

    /**
     * As above, with the folder a loose file runs in ({@code looseCwd}, null: its own folder) — the project
     * root of a plain-folder project window, where a program reads its files as it would from a terminal there.
     */
    void startLaunch(
            Path file,
            DapManager.MainClassPicker picker,
            String javaExecOverride,
            @Nullable Path projectRoot,
            @Nullable Path looseCwd) {
        if (!ready(file)) {
            return;
        }
        dap.restartAction = () -> startLaunch(file, picker, javaExecOverride, projectRoot, looseCwd);
        dap.debugFile = file;
        long epoch = dap.beginSession();
        dap.setState(DapManager.State.STARTING);
        Runnable resolve =
                () -> resolveMainClassAndLaunch(file, picker, javaExecOverride, projectRoot, looseCwd, epoch);
        if (projectRoot == null) {
            correctSourceRoot(file, epoch, resolve);
        } else {
            resolve.run();
        }
    }

    private void resolveMainClassAndLaunch(
            Path file,
            DapManager.MainClassPicker picker,
            String javaExecOverride,
            @Nullable Path projectRoot,
            @Nullable Path looseCwd,
            long epoch) {
        dap.executeCommand(file, "vscode.java.resolveMainClass", List.of(), (res, err) -> {
            if (!dap.isCurrent(epoch)) {
                return;
            }
            if (err != null) {
                dap.fail(epoch, tr("debug.error.resolveMainClassFailed", DapManager.msg(err)));
                return;
            }
            // The reply's parse and the file's own main are read off the FX thread (PERF-01, Pauta 6.2.0).
            DapPaths paths = DapManager.pathsFor(file);
            JAVA_LAUNCH_PLAN.execute(() -> {
                com.editora.perf.BlockingSites.enter("DapManager.ownMainClass");
                List<DapManager.MainClassOption> options = JdtlsReplies.parseMainClasses(res);
                String own = ownMainClass(file);
                String fromFile = options.isEmpty() && own == null ? JavaCompilation.mainClassFromFile(file) : null;
                // which option is this file's: real paths compared here, off the FX thread
                DapManager.MainClassOption match = options.stream()
                        .filter(o -> JavaLaunchSupport.sameFile(o.filePath(), file, paths))
                        .findFirst()
                        .orElse(null);
                dap.fx(() -> {
                    if (dap.isCurrent(epoch)) {
                        launchMainClass(
                                file,
                                picker,
                                javaExecOverride,
                                projectRoot,
                                looseCwd,
                                epoch,
                                options,
                                own,
                                fromFile,
                                match);
                    }
                });
            });
        });
    }

    /** {@link #resolveMainClassAndLaunch} once jdtls answered and the file was read: picks and launches. FX thread. */
    private void launchMainClass(
            Path file,
            DapManager.MainClassPicker picker,
            String javaExecOverride,
            @Nullable Path projectRoot,
            @Nullable Path looseCwd,
            long epoch,
            List<DapManager.MainClassOption> options,
            @Nullable String own,
            @Nullable String fromFile,
            DapManager.@Nullable MainClassOption match) {
        DapPaths paths = DapManager.pathsFor(file);
        // A project's main class runs in the project root — what Run, the gutter and a saved
        // configuration use — and a loose file in the window's project folder, else its own folder.
        Path dir = projectRoot != null ? projectRoot : looseCwd != null ? looseCwd : file.getParent();
        String cwd = dir == null ? null : paths.toAdapter(dir);
        if (options.isEmpty()) {
            // jdtls enumerated no project main class (a standalone file, or the project is still
            // importing). Derive the fully-qualified class name from the file (package + type name)
            // and resolve its classpath — the adapter needs a real class name + classPaths, not a path.
            String fqn = own != null ? own : fromFile;
            if (fqn == null) {
                dap.fail(epoch, tr("debug.error.noMainClass"));
                return;
            }
            compileAndLaunch(file, fqn, javaExecOverride, epoch, null, dir);
            return;
        }
        if (match == null && own != null) {
            // The file declares a main that jdtls does not list: it is outside the project's source
            // folders (a script beside the project), or jdtls has not seen it yet. Debug the file — never
            // the project's other main class, which is what used to run instead.
            compileAndLaunch(file, own, javaExecOverride, epoch, null, dir);
            return;
        }
        if (match != null) {
            launchOption(file, match, cwd, javaExecOverride, projectRoot, epoch);
        } else if (options.size() == 1) {
            // No main here: the project's only one is what Debug means, as in VS Code — said so, because
            // the program that runs is not the file in front of the user.
            DapManager.MainClassOption only = options.get(0);
            notice(
                    epoch,
                    com.editora.i18n.Messages.tr(
                            "debug.console.noMainInFile", file.getFileName(), String.valueOf(only.className())));
            launchOption(file, only, cwd, javaExecOverride, projectRoot, epoch);
        } else {
            picker.pick(options, chosen -> {
                if (chosen != null) {
                    launchOption(file, chosen, cwd, javaExecOverride, projectRoot, epoch);
                } else {
                    if (dap.isCurrent(epoch)) {
                        dap.setState(DapManager.State.INACTIVE);
                    }
                }
            });
        }
    }

    /**
     * Launches {@code opt}. Outside a build project the classes come from jdtls's "invisible project" only when
     * the build before this launch brought them up to date; otherwise the file is compiled here, from its own
     * source ({@link LooseJavaLaunch#classesFor}): jdtls builds that output once, at import, and an old class
     * file there ran old code after every edit (review 2, A101).
     */
    private void launchOption(
            Path file,
            DapManager.MainClassOption opt,
            @Nullable String cwd,
            String javaExecOverride,
            @Nullable Path projectRoot,
            long epoch) {
        if (projectRoot != null) {
            resolveAndLaunch(file, opt, cwd, javaExecOverride, epoch);
            return;
        }
        Path source = optionSource(file, opt);
        Path dir = cwd == null ? null : DapManager.pathsFor(file).fromAdapter(cwd);
        // The source's package clause is read off the FX thread (PERF-01, Pauta 6.2.0).
        Runnable compile = () -> JAVA_LAUNCH_PLAN.execute(() -> {
            com.editora.perf.BlockingSites.enter("DapManager.declaredPackage");
            String pkg = JavaLaunchSupport.declaredPackage(source);
            dap.fx(() -> {
                if (dap.isCurrent(epoch)) {
                    compileAndLaunch(
                            source,
                            JavaLaunchSupport.qualified(opt.className(), pkg),
                            javaExecOverride,
                            epoch,
                            null,
                            dir,
                            file);
                }
            });
        });
        boolean built = takeLooseBuild();
        if (!built) {
            compile.run(); // no build ran for this launch: whatever jdtls's output holds may be from any earlier one
            return;
        }
        resolveAndLaunch(file, opt, cwd, javaExecOverride, epoch, compile);
    }

    private boolean takeLooseBuild() {
        boolean built = dap.looseBuilt;
        dap.looseBuilt = false;
        return built;
    }

    /** The source file of {@code opt} as Editora reaches it ({@code file} when jdtls named none). */
    private static Path optionSource(Path file, DapManager.MainClassOption opt) {
        if (opt.filePath() == null || opt.filePath().isBlank()) {
            return file;
        }
        try {
            Path named = DapManager.pathsFor(file).fromAdapter(opt.filePath());
            return named == null ? file : named;
        } catch (RuntimeException e) {
            return file;
        }
    }

    /** The fully-qualified name of the first {@code main} {@code file} declares itself, or null (bounded read). */
    static @Nullable String ownMainClass(Path file) {
        try {
            if (file == null || Files.size(file) > 2_000_000) {
                return null;
            }
            List<com.editora.run.MainMethodScanner.MainMethod> mains =
                    com.editora.run.MainMethodScanner.scan(Files.readString(file));
            return mains.isEmpty() ? null : mains.get(0).fqn();
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    /** A line of Editora's own in the Debug console for the session of {@code epoch} (FX thread). */
    private void notice(long epoch, String text) {
        if (dap.isCurrent(epoch)) {
            dap.listener.onOutput(text + "\n", "console");
        }
    }

    /** {@link JavaSourceRootFix} before a launch from a folder with no build file, then {@code then}. */
    private void correctSourceRoot(Path file, long epoch, Runnable then) {
        // The file's package clause is read off the FX thread (PERF-01, Pauta 6.2.0).
        JAVA_LAUNCH_PLAN.execute(() -> {
            com.editora.perf.BlockingSites.enter("DapManager.declaredPackage");
            String declared = JavaLaunchSupport.declaredPackage(file);
            dap.fx(() -> {
                if (dap.isCurrent(epoch)) {
                    JavaSourceRootFix.correct(dap.lsp, file, declared, () -> dap.isCurrent(epoch), fixed -> then.run());
                }
            });
        });
    }

    /** Debug a compact {@code .java} file as the implicit class named after the file. */
    void startCompactSource(Path file, String javaExecOverride) {
        if (file == null || !file.getFileName().toString().endsWith(".java")) {
            throw new IllegalArgumentException("Compact source debugging needs a .java file");
        }
        if (!ready(file)) {
            return;
        }
        dap.restartAction = () -> startCompactSource(file, javaExecOverride);
        dap.debugFile = file;
        long epoch = dap.beginSession();
        dap.setState(DapManager.State.STARTING);
        compileAndLaunch(file, JavaCompilation.compactMainClassFromFile(file), javaExecOverride, epoch, null);
    }

    /** Debug an extensionless Java script via a line-preserving temporary {@code .java} copy. */
    void startCompactShebang(Path file, int sourceVersion, String javaExecOverride) {
        if (sourceVersion < 25) {
            throw new IllegalArgumentException("Compact source debugging requires --source 25 or newer");
        }
        if (!ready(file)) {
            return;
        }
        dap.restartAction = () -> startCompactShebang(file, sourceVersion, javaExecOverride);
        dap.debugFile = file;
        long epoch = dap.beginSession();
        dap.setState(DapManager.State.STARTING);
        compileAndLaunch(file, file.getFileName().toString(), javaExecOverride, epoch, sourceVersion);
    }

    /** Attaches to a running JVM at {@code host:port} (the file provides the jdtls session for the adapter). */
    void startAttach(@Nullable Path file, String host, int port) {
        if (!ready(file) || file == null) { // ready() reports a missing file
            return;
        }
        dap.restartAction = () -> startAttach(file, host, port);
        dap.debugFile = file;
        long epoch = dap.beginSession();
        dap.setState(DapManager.State.STARTING);
        // java-debug compiles conditions, logpoints and evaluated expressions against a JDT project and an
        // attach names no main class to infer one from: without the name every evaluation fails, and a
        // condition that cannot be evaluated counts as a hit.
        projectNameOf(file, project -> {
            if (dap.isCurrent(epoch)) {
                startDebugSessionAndConnect(file, LaunchConfig.attach(host, port, project), true, epoch);
            }
        });
    }

    /**
     * Names the jdtls project {@code file} belongs to (null when jdtls cannot say), on the FX thread. Asks
     * for the file's main method first — the only form that covers a compact source, which declares no type
     * — then for the element at its first type declaration, which covers a class with no {@code main} (a
     * test class, the anchor of Debug Test).
     */
    private void projectNameOf(Path file, Consumer<String> cb) {
        String uri = dap.lsp.serverUri(file);
        dap.executeCommand(file, "vscode.java.resolveMainMethod", List.of(uri), (res, err) -> {
            String name = err == null ? JavaLaunchSupport.projectName(res) : null;
            if (name != null) {
                cb.accept(name);
                return;
            }
            // The type's position is read from the file off the FX thread (PERF-01, Pauta 6.2.0).
            JAVA_LAUNCH_PLAN.execute(() -> {
                com.editora.perf.BlockingSites.enter("DapManager.typeNamePosition");
                int[] at = JavaLaunchSupport.typeNamePosition(file);
                dap.fx(() -> {
                    if (at == null) {
                        cb.accept(null);
                        return;
                    }
                    dap.executeCommand(
                            file,
                            "vscode.java.resolveElementAtSelection",
                            List.of(uri, at[0], at[1]),
                            (el, e2) -> cb.accept(e2 == null ? JavaLaunchSupport.projectName(el) : null));
                });
            });
        });
    }

    private boolean ready(@Nullable Path file) {
        if (dap.managerClosed) {
            return false;
        }
        boolean hostPlugin = file != null
                && !com.editora.process.ExecTargets.runsHere(file)
                && dap.shared.enabled
                && com.editora.process.ExecTargets.remoteTool(file, "dap.java") != null;
        if (!dap.detection.javaDebugUsable() && !hostPlugin) {
            dap.listener.onError(tr("debug.error.javaUnavailable"));
            return false;
        }
        if (file == null) {
            dap.listener.onError(tr("debug.error.openJavaFile"));
            return false;
        }
        if (dap.client != null) {
            dap.stop(); // one session at a time
        }
        return true;
    }

    private void resolveAndLaunch(
            Path file, DapManager.MainClassOption opt, @Nullable String cwd, String javaExecOverride, long epoch) {
        resolveAndLaunch(file, opt, cwd, javaExecOverride, epoch, null);
    }

    /**
     * Resolves {@code opt}'s classpath + java executable via jdtls, then launches the debug session with the
     * given working directory. {@code file} routes the jdtls {@code executeCommand}s (must be an open,
     * LSP-managed document in the same project); {@code cwd} is the debuggee's working directory (the project
     * root for a project main class, else the file's own folder). {@code ifNoClassFile}, when given, runs
     * instead of the launch if jdtls's classpath does not hold the main class's class file.
     */
    private void resolveAndLaunch(
            Path file,
            DapManager.MainClassOption opt,
            @Nullable String cwd,
            String javaExecOverride,
            long epoch,
            @Nullable Runnable ifNoClassFile) {
        String proj = opt.projectName() == null ? "" : opt.projectName();
        resolveLaunch(file, opt, r -> {
            if (!dap.isCurrent(epoch)) {
                return;
            }
            // What the launch is made of is the session's (read here); what it decides by is on the disk: the
            // class files, and the JDK's release file when the classpath is too long for a command line. Those are
            // looked at off the FX thread (PERF-01, Pauta 6.2.0), and the launch goes on back on it.
            String javaExec = dap.javaExecFor(file, javaExecOverride, r.javaExec());
            List<String> args = dap.programArgs;
            String vmArgs = JavaLaunchSupport.withPreview(dap.vmArgsFor(DapManager.targetFor(file)), r.enablePreview());
            Map<String, String> environment = dap.env;
            LaunchConfig.Target target = DapManager.targetFor(file);
            JAVA_LAUNCH_PLAN.execute(() -> {
                com.editora.perf.BlockingSites.enter("DapManager.planJavaLaunch");
                boolean compileHere = ifNoClassFile != null
                        && LooseJavaLaunch.classesFor(
                                        true,
                                        JavaLaunchSupport.classFilePresent(r.classPaths(), opt.mainClass())
                                                || JavaLaunchSupport.classFilePresent(r.modulePaths(), opt.mainClass()))
                                == LooseJavaLaunch.Classes.COMPILE_HERE;
                if (com.editora.process.ExecTargets.runsHere(file)) {
                    DebuggeeProcesses.prepareExitWrapper(
                            javaExec); // its exit-code wrapper, ready when the session starts
                }
                String warning = compileHere || !r.ok() ? null : classesWarning(file, opt, r);
                Map<String, Object> config = compileHere || !r.ok()
                        ? null
                        : LaunchConfig.launch(
                                opt.mainClass(),
                                proj,
                                r.classPaths(),
                                r.modulePaths(),
                                javaExec,
                                cwd,
                                args,
                                vmArgs,
                                environment,
                                false,
                                target);
                dap.fx(() -> {
                    if (!dap.isCurrent(epoch)) {
                        return;
                    }
                    if (compileHere && ifNoClassFile != null) {
                        ifNoClassFile.run();
                        return;
                    }
                    if (!r.ok() || config == null) {
                        dap.fail(epoch, r.error());
                        return;
                    }
                    if (warning != null) {
                        notice(epoch, warning); // jdtls's output too: it is warned about, never trusted blindly
                    }
                    startDebugSessionAndConnect(file, config, false, epoch);
                });
            });
        });
    }

    /** Where a Java launch reads the disk it decides by: a virtual thread per launch (PERF-01, Pauta 6.2.0). */
    private static final java.util.concurrent.Executor JAVA_LAUNCH_PLAN =
            com.editora.process.BlockingTasks.named("java-launch-plan");

    /**
     * The last word on a project launch's class files, whatever built them (the jdtls build before the launch,
     * a before-launch step — or nothing, when no build could run): a main class with no class file, or one
     * older than its source, is launched with a warning in the console that says to build the project first —
     * the ClassNotFoundException or the old code that follows then has its explanation (review A2). A remote
     * project's files are the host's and are not read from here.
     */
    private static @Nullable String classesWarning(
            Path file, DapManager.MainClassOption opt, DapManager.ResolvedLaunch r) {
        if (!com.editora.process.ExecTargets.runsHere(file)) {
            return null;
        }
        List<String> paths = new ArrayList<>(r.classPaths());
        paths.addAll(r.modulePaths());
        String cls = opt.className();
        if (cls == null) {
            return null; // classState has nothing to judge by either
        }
        return switch (JavaLaunchSupport.classState(paths, opt.mainClass(), optionSource(file, opt))) {
            case MISSING -> com.editora.i18n.Messages.tr("debug.console.classesMissing", cls);
            case STALE -> com.editora.i18n.Messages.tr("debug.console.classesStale", cls);
            default -> null; // current, or nothing to judge by
        };
    }

    /**
     * Resolves {@code opt}'s classpath + java executable via jdtls (no debug session), delivering a
     * {@link DapManager.ResolvedLaunch} (with {@link DapManager.ResolvedLaunch#error()} set on failure) on the FX thread. Shared by
     * the debug launch and the non-debug Run path; {@code routingFile} must be an open, LSP-managed Java file
     * in the same project.
     */
    void resolveLaunch(Path routingFile, DapManager.MainClassOption opt, Consumer<DapManager.ResolvedLaunch> cb) {
        // The chokepoint guard, so no future caller can reproduce the class of bug: jdtls builds an Eclipse
        // SearchPattern from the main class, and createPattern("") returns null, surfacing as an internal
        // NPE from its search engine instead of anything actionable. Callers check first and say something
        // useful; this makes the unchecked path fail cleanly rather than incomprehensibly.
        String mainClass = opt.mainClass();
        if (mainClass == null || mainClass.isBlank()) {
            cb.accept(DapManager.ResolvedLaunch.failed(tr("debug.error.configNeedsMainClass")));
            return;
        }
        String proj = opt.projectName() == null ? "" : opt.projectName();
        dap.executeCommand(routingFile, "vscode.java.resolveClasspath", List.of(mainClass, proj), (res, err) -> {
            if (err != null) {
                cb.accept(DapManager.ResolvedLaunch.failed(tr("debug.error.classpathFailed", DapManager.msg(err))));
                return;
            }
            List<String> modulepaths = new ArrayList<>();
            List<String> classpaths = new ArrayList<>();
            JdtlsReplies.parseClasspath(res, modulepaths, classpaths);
            if (classpaths.isEmpty() && modulepaths.isEmpty()) {
                // Log what the server ACTUALLY said. An empty classpath has two very different causes —
                // the project genuinely isn't importable, or we failed to read a perfectly good reply — and
                // the message below can only guess at the first. Without this the two are indistinguishable
                // from the outside, which cost a long debugging session: jdtls was returning
                // [[], ["…/target/classes"]] while the user was told the project hadn't imported.
                DapManager.LOG.log(
                        Level.WARNING,
                        () -> "resolveClasspath returned no entries for " + mainClass + " (project='"
                                + proj + "'); raw result type="
                                + (res == null ? "null" : res.getClass().getName())
                                + " value=" + JdtlsReplies.abbreviate(String.valueOf(res)));
                cb.accept(DapManager.ResolvedLaunch.failed(tr("debug.error.classpathEmpty", mainClass)));
                return;
            }
            dap.executeCommand(
                    routingFile,
                    "vscode.java.resolveJavaExecutable",
                    List.of(mainClass, proj),
                    (jx, e2) -> dap.executeCommand(
                            routingFile,
                            "vscode.java.checkProjectSettings",
                            List.of(JavaLaunchSupport.previewSettingsQuery(mainClass, proj)),
                            // A project compiled with --enable-preview needs the flag to load its classes;
                            // a failed check (an older java-debug) leaves the launch as it was.
                            (preview, e3) -> cb.accept(new DapManager.ResolvedLaunch(
                                    JdtlsReplies.asString(jx),
                                    modulepaths,
                                    classpaths,
                                    null,
                                    e3 == null && JavaLaunchSupport.isTrue(preview)))));
        });
    }

    /**
     * Enumerates <b>every</b> main class in {@code routingFile}'s jdtls project (not just the file's own),
     * for a project-level "Run/Debug Main Class…" picker. {@code routingFile} must be an open, LSP-managed
     * Java document (jdtls's {@code executeCommand} routes by open-document URI). Delivers an empty list on
     * any error / when debugging isn't available. Callback runs on the FX thread.
     */
    void resolveMainClasses(Path routingFile, Consumer<List<DapManager.MainClassOption>> cb) {
        if (!dap.detection.javaDebugUsable() || routingFile == null) {
            cb.accept(List.of());
            return;
        }
        dap.executeCommand(routingFile, "vscode.java.resolveMainClass", List.of(), (res, err) -> {
            if (err != null) {
                // The caller cannot tell this from "the project has none" — it reports the same thing either
                // way — so the log is the only place the difference can survive.
                DapManager.LOG.log(Level.WARNING, err, () -> "vscode.java.resolveMainClass failed for " + routingFile);
                cb.accept(List.of());
                return;
            }
            cb.accept(JdtlsReplies.parseMainClasses(res));
        });
    }

    /**
     * As above; {@code plainFolder} for a project with no build file, whose classes jdtls never compiles —
     * the chosen class is then compiled from its own source when its class file is missing (review A16).
     */
    void startLaunchMainClass(
            Path routingFile, DapManager.MainClassOption opt, Path cwd, String javaExecOverride, boolean plainFolder) {
        if (!ready(routingFile)) {
            return;
        }
        dap.restartAction = () -> startLaunchMainClass(routingFile, opt, cwd, javaExecOverride, plainFolder);
        dap.debugFile = routingFile;
        long epoch = dap.beginSession();
        dap.setState(DapManager.State.STARTING);
        String adapterCwd =
                cwd == null ? null : DapManager.pathsFor(routingFile).toAdapter(cwd);
        if (plainFolder) {
            correctSourceRoot(
                    routingFile,
                    epoch,
                    () -> launchOption(routingFile, opt, adapterCwd, javaExecOverride, null, epoch));
        } else {
            resolveAndLaunch(routingFile, opt, adapterCwd, javaExecOverride, epoch);
        }
    }

    /**
     * Standalone-file fallback: jdtls enumerated no project main class (a loose file with no build
     * project, where jdtls's invisible-project classpath resolution is unreliable). Compile the file
     * ourselves with {@code javac -g} (debug info) to a temp dir, then launch with that dir as the
     * classpath — self-contained, like the Run feature. jdtls is still used only to start the adapter.
     * Runs off the FX thread (javac is a subprocess).
     */
    private void compileAndLaunch(
            Path file, String fqn, String javaExecOverride, long epoch, @Nullable Integer sourceVersion) {
        compileAndLaunch(file, fqn, javaExecOverride, epoch, sourceVersion, null);
    }

    /**
     * As above, running the program in {@code workingDir} (null: the file's own folder). The file is compiled
     * with its package's source folder as javac's source path, so the other classes of that package compile
     * with it, and the class is launched by the package the file declares.
     */
    private void compileAndLaunch(
            Path file,
            String fqn,
            String javaExecOverride,
            long epoch,
            @Nullable Integer sourceVersion,
            @Nullable Path workingDir) {
        compileAndLaunch(file, fqn, javaExecOverride, epoch, sourceVersion, workingDir, file);
    }

    /**
     * As above, for a {@code file} that may not be open in the editor: {@code routing} — an open Java file of
     * the same jdtls project — carries the jdtls commands that start the adapter.
     */
    private void compileAndLaunch(
            Path file,
            @Nullable String fqn,
            String javaExecOverride,
            long epoch,
            @Nullable Integer sourceVersion,
            @Nullable Path workingDir,
            Path routing) {
        dap.javacEpoch = epoch; // this session runs javac's classes, not jdtls's output (review 3 verify, A201)
        // A remote file is compiled on its host, with its javac, into a folder of the host's cache.
        boolean remote = !com.editora.process.ExecTargets.runsHere(file);
        DapPaths paths = DapManager.pathsFor(file);
        Map<String, String> launchEnvironment = dap.env;
        dap.startupTask = dap.io.submit(() -> {
            Path out = null;
            Runnable removal = null;
            boolean retained = false;
            try {
                Path compileFile = file;
                if (remote) {
                    JavaCompilation.HostCompilation host = JavaCompilation.hostCompilation(
                            file,
                            sourceVersion == null ? null : JavaCompilation.withoutShebang(Files.readString(file)));
                    out = host.directory();
                    removal = host.removal();
                    if (host.source() != null) {
                        compileFile = host.source();
                    }
                } else {
                    out = Files.createTempDirectory("editora-dap-");
                    if (sourceVersion != null) {
                        compileFile = out.resolve(file.getFileName() + ".java");
                        Files.writeString(compileFile, JavaCompilation.withoutShebang(Files.readString(file)));
                    }
                }
                String javaExec = remote
                        ? JavaCompilation.hostJava(file)
                        : javaExecOverride == null || javaExecOverride.isBlank()
                                ? DapManager.firstOrNull(ProcessRunner.resolveExecutable(List.of("java")))
                                : javaExecOverride;
                // Route through ProcessRunner.run so the compile is bounded by a hard timeout (a hung javac
                // would otherwise pin the single-threaded `io` executor and block every later DAP start/detect)
                // and both pipes are drained concurrently (no fill-the-buffer deadlock).
                Path sourceRoot = sourceVersion != null
                        ? null
                        : JavaLaunchSupport.sourceRoot(file, JavaLaunchSupport.declaredPackage(file));
                ProcessRunner.Result r = remote
                        ? JavaCompilation.compileOnHost(
                                file, compileFile, out, javaExec, launchEnvironment, sourceVersion, sourceRoot)
                        : JavaCompilation.compileStandalone(
                                file, compileFile, out, javaExec, launchEnvironment, sourceVersion, sourceRoot);
                if (!r.ok()) {
                    // javac's own report — file, line, the caret under the mistake — belongs in the Debug
                    // console, where its "File.java:3:" lines open the file; the status line only says so.
                    dap.failWithConsole(
                            epoch,
                            JavaLaunchSupport.compilerReport(r.out(), r.err(), compileFile, file),
                            com.editora.i18n.Messages.tr("status.debug.compileFailed", file.getFileName()),
                            true);
                    return;
                }
                synchronized (dap.sessionLock) {
                    if (!dap.isCurrent(epoch)) {
                        return;
                    }
                    dap.compilationDirectory = out;
                    compilationRemoval = removal;
                    // A script is compiled from a copy, and a loose file's class may come back with no source
                    // path (jdtls cannot always place it): either way its frames are this file's.
                    sourceAlias = new DapManager.SourceAlias(epoch, file, compileFile);
                    retained = true;
                }
                Runnable compiled = dap.compiledForTest;
                if (compiled != null) {
                    compiled.run();
                }
                Path runIn = workingDir != null ? workingDir : file.getParent();
                String cwd = runIn == null ? null : paths.toAdapter(runIn);
                String classes = paths.toAdapter(out);
                // The classes come from a temp dir, so java-debug cannot infer the project it compiles
                // conditions, logpoints and evaluated expressions against: name the one jdtls keeps the
                // source in (its invisible project for a loose file).
                dap.fx(() -> projectNameOf(
                        routing,
                        project -> startDebugSessionAndConnect(
                                routing,
                                LaunchConfig.launch(
                                        fqn,
                                        project,
                                        List.of(classes),
                                        List.of(),
                                        javaExec,
                                        cwd,
                                        dap.programArgs,
                                        dap.vmArgsFor(DapManager.targetFor(file)),
                                        launchEnvironment,
                                        false,
                                        DapManager.targetFor(file)),
                                false,
                                epoch)));
            } catch (Exception e) {
                dap.fail(epoch, tr("debug.error.compileLaunchFailed", fqn, DapManager.msg(e)));
            } finally {
                if (!retained && out != null) {
                    JavaCompilation.removeCompilation(out, removal);
                }
            }
        });
    }

    DapManager.@Nullable SourceAlias activeSourceAlias() {
        DapManager.SourceAlias alias = sourceAlias;
        return alias != null && alias.epoch() == dap.sessionEpoch ? alias : null;
    }

    /** Remove both the temporary source and compiled classes after the adapter releases them. */
    void releaseCompilationDirectory() {
        Path directory;
        Runnable removal;
        synchronized (dap.sessionLock) { // the compile thread retains a folder under this lock
            directory = dap.compilationDirectory;
            removal = compilationRemoval;
            dap.compilationDirectory = null;
            compilationRemoval = null;
            sourceAlias = null;
        }
        if (directory != null) {
            java.util.concurrent.CompletableFuture.runAsync(
                    () -> JavaCompilation.removeCompilation(directory, removal),
                    com.editora.process.BlockingTasks.named("dap-remove-compilation"));
        }
    }

    private List<DapModels.FileBreakpoints> adapterBreakpoints(List<DapModels.FileBreakpoints> breakpoints) {
        return breakpoints.stream().map(this::adapterBreakpoint).toList();
    }

    DapModels.FileBreakpoints adapterBreakpoint(DapModels.FileBreakpoints breakpoint) {
        DapManager.SourceAlias alias = activeSourceAlias();
        return alias != null && alias.original().equals(breakpoint.file())
                ? new DapModels.FileBreakpoints(alias.compiled(), breakpoint.breakpoints())
                : breakpoint;
    }

    List<DapModels.StackFrameInfo> originalFrames(@Nullable List<DapModels.StackFrameInfo> frames) {
        if (frames == null || frames.isEmpty()) {
            return List.of();
        }
        // java-debug finds a class's source through jdtls, which knows a Java script by the .java stand-in
        // it is shown (LspManager.scriptForStandIn): the frame is the script's.
        frames = frames.stream()
                .map(frame -> {
                    Path script = dap.lsp == null ? null : dap.lsp.scriptForStandIn(frame.file());
                    return script == null
                            ? frame
                            : new DapModels.StackFrameInfo(
                                    frame.id(), frame.name(), script, frame.line(), frame.column());
                })
                .toList();
        DapManager.SourceAlias alias = activeSourceAlias();
        if (alias == null) {
            return frames;
        }
        return frames.stream()
                .map(frame -> isAliasedFrame(alias, frame)
                        ? new DapModels.StackFrameInfo(
                                frame.id(), frame.name(), alias.original(), frame.line(), frame.column())
                        : frame)
                .toList();
    }

    private static boolean isAliasedFrame(DapManager.SourceAlias alias, DapModels.StackFrameInfo frame) {
        if (alias.compiled().equals(frame.file())) {
            return true;
        }
        // java-debug can omit Source.path for a class compiled from a temporary source file. Its
        // frame name still identifies the implicit class; leave unrelated library frames untouched.
        String fileName = alias.original().getFileName().toString();
        String className =
                fileName.endsWith(".java") ? fileName.substring(0, fileName.length() - ".java".length()) : fileName;
        return frame.file() == null
                && frame.name() != null
                && (frame.name().startsWith(className + ".") || frame.name().startsWith(className + "$"));
    }

    private void startDebugSessionAndConnect(
            @Nullable Path file, java.util.Map<String, Object> args, boolean attach, long epoch) {
        if (!dap.isCurrent(epoch)) {
            return;
        }
        // java-debug keeps one set of settings per jdtls: give it this session's before the session starts. A
        // failure (an older java-debug) is no reason not to debug, on its defaults as before.
        dap.executeCommand(file, JavaDebugSettings.COMMAND, List.of(dap.javaSettingsJson()), (r, e) -> {
            if (e != null) {
                DapManager.LOG.log(Level.FINE, "java-debug settings were not applied", e);
            }
            startDebugSession(file, args, attach, epoch);
        });
    }

    private void startDebugSession(
            @Nullable Path file, java.util.Map<String, Object> args, boolean attach, long epoch) {
        if (!dap.isCurrent(epoch)) {
            return;
        }
        dap.javaAdapterEpoch = epoch;
        dap.executeCommand(file, "vscode.java.startDebugSession", List.of(), (res, err) -> {
            if (!dap.isCurrent(epoch)) {
                return;
            }
            if (err != null) {
                dap.fail(epoch, tr("debug.error.sessionFailed", DapManager.msg(err)));
                return;
            }
            int port = JdtlsReplies.asInt(res);
            if (port <= 0) {
                dap.fail(epoch, tr("debug.error.noPort"));
                return;
            }
            // The adapter (and the debuggee it launches) live inside that jdtls: pin it for this session.
            dap.lspLease.run();
            dap.lspLease = dap.lsp.retain(file);
            DapClient c = new DapClient(dap.sessionHost(epoch));
            c.setPaths(DapManager.pathsFor(file));
            boolean wrap = false;
            if (!attach && dap.shared.programConsole) {
                c.setRunsDebuggee(true);
                LaunchConfig.inClientConsole(args);
            } else if (!attach) {
                wrap = true; // on the connect thread below: making a wrapper writes files (PERF-01)
            }
            boolean wrapExit = wrap;
            c.setBreakpoints(adapterBreakpoints(dap.shared.breakpointsSupplier.get())); // snapshot on the FX thread
            c.setExceptionFilters(dap.filtersFor("java"));
            if (!dap.publishClient(epoch, c)) {
                c.dispose();
                return;
            }
            // Connect off the FX thread (the socket open blocks with retries), then launch/attach. The
            // connect future completes on the DAP reader thread, so every state change here must be
            // marshaled back to the FX thread (it touches the Debug panel + status bar).
            dap.io.submit(() -> {
                if (wrapExit) {
                    // Before the launch request carries args: every launch path gets its wrapper, also one whose
                    // plan made none ahead (a loose file compiled here, a compact file, a launch configuration).
                    dap.debuggees.wrapForExitCode(file, args, epoch);
                }
                connectJava(c, file, port).whenComplete((caps, e) -> {
                    if (e != null) {
                        dap.fail(epoch, tr("debug.error.connectFailed", DapManager.msg(e)));
                        return;
                    }
                    if (!dap.isCurrent(epoch, c)) {
                        c.dispose();
                        return;
                    }
                    (attach ? c.attach(args) : c.launch(args)).whenComplete((v, le) -> {
                        if (le != null) {
                            String refused = attach ? DapErrors.refusedAttach(DapManager.msg(le)) : null;
                            if (refused != null) {
                                // Nothing listens there: say so, and how a JVM is made to listen (review 2, A109).
                                dap.failWithConsole(
                                        epoch,
                                        tr("debug.console.attachRefused", refused),
                                        tr("status.debug.attachRefused", refused));
                                return;
                            }
                            dap.fail(
                                    epoch,
                                    tr(
                                            attach ? "debug.error.attachFailed" : "debug.error.launchFailed",
                                            DapManager.msg(le)));
                            return;
                        }
                        // Running once the adapter has the program, not when the request left: until then
                        // java-debug is still starting the JVM. A stop that came first has already said SUSPENDED.
                        dap.fx(() -> {
                            if (!dap.isCurrent(epoch, c)) {
                                return;
                            }
                            if (dap.state == DapManager.State.STARTING) {
                                dap.setState(DapManager.State.RUNNING);
                            }
                            if (!attach && args.get("javaExec") instanceof String javaExec && !javaExec.isBlank()) {
                                dap.listener.onDebuggeeRuntime(
                                        List.of(dap.debuggees.realJavaExec(epoch, javaExec)),
                                        DapManager.runtimeWhere(file),
                                        Map.of());
                            }
                        });
                    });
                });
            });
        });
    }

    /**
     * Connects to the java-debug adapter jdtls opened on {@code port}: on this machine directly, on a remote
     * file's host through a tunnel that closes with the session.
     */
    private CompletableFuture<org.eclipse.lsp4j.debug.Capabilities> connectJava(
            DapClient c, @Nullable Path file, int port) {
        if (com.editora.process.ExecTargets.runsHere(file)) {
            return c.connect(port, "java");
        }
        try {
            com.editora.process.PortForward forward =
                    com.editora.process.ExecTargets.forPath(file).forwardLocal("127.0.0.1", port);
            c.closeOnDispose(forward);
            return c.connect(forward.localPort(), "java");
        } catch (java.io.IOException | RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
