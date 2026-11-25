package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.editora.dap.RustLaunch;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Debug File for a file that is built first: Go on Delve, Rust on CodeLLDB after Cargo builds it, C and C++ after
 * their CMake, Make or compiler build, and C# on netcoredbg after {@code dotnet build}. Part of
 * {@link DebugCoordinator}.
 */
final class CompiledDebugLaunch {

    private final DebugCoordinator d; // its host and ops are read when used: this is made with the coordinator

    CompiledDebugLaunch(DebugCoordinator d) {
        this.d = d;
    }

    /**
     * Debugs Go file {@code path} on Delve: in a test file the test at the caret (its package's tests outside any), else
     * its package — the file alone outside a module — with {@code args} as the program's or the test binary's. Delve
     * builds it with the {@code go} the Go build tool runs, after the module's trust as a Go task asks it.
     */
    void debugGoFile(EditorBuffer b, Path path, List<String> args) {
        debugGo(path, b.getContent(), d.caretLine(b), args, DebugCoordinator.LaunchExtras.NONE);
    }

    /** Names the session and its Restart for a file's launch; a configuration's launch did both itself already. */
    private void named(DebugCoordinator.LaunchExtras extras, String name, Runnable relaunch) {
        if (!extras.config()) {
            d.nameSession(name);
            d.s().relaunch = relaunch;
        }
    }

    /** {@link #debugGoFile} for {@code path} with text {@code text} and the caret on {@code caret} (-1: nowhere). */
    void debugGo(Path path, String text, int caret, List<String> args, DebugCoordinator.LaunchExtras extras) {
        d.planOffFx( // the module is a folder walk, the test at the caret a scan: off the FX thread (PERF-01)
                "debug-go-plan",
                () -> {
                    Path module = com.editora.dap.GoLaunch.moduleRoot(path);
                    return new GoPlan(
                            module,
                            com.editora.dap.GoLaunch.launch(
                                    path, text, caret, module != null, args, extras.env(), false, extras.cwd()),
                            com.editora.dap.GoLaunch.sessionName(path, text, caret));
                },
                a -> {
                    if (a.value() != null) {
                        debugGoIn(path, extras, a.value());
                    } else {
                        d.planFailed(path, a.failure());
                    }
                });
    }

    /** A Go launch planned off the FX thread: its module (null: a loose file), launch arguments and session name. */
    private record GoPlan(@Nullable Path module, java.util.Map<String, Object> launch, String name) {}

    private void debugGoIn(Path path, DebugCoordinator.LaunchExtras extras, GoPlan plan) {
        Path module = plan.module();
        if (module != null
                && !d.factsKnown(
                        "debug-go-trust-look",
                        d.facts.executable(com.editora.build.BuildTool.GO, module),
                        () -> debugGoIn(path, extras, plan))) {
            return;
        }
        List<String> go = module != null
                ? d.ops.buildExecutable(com.editora.build.BuildTool.GO, module)
                : com.editora.build.BuildExecutable.resolve(
                        List.of(), d.host.settings().getGoCommand(), "go");
        if (go == null) {
            return; // the module was not trusted: nothing is built
        }
        java.util.Map<String, Object> launch = plan.launch();
        d.dap().setExternalPathDirs("go", goToolDirs(go));
        Path built = module != null ? module : path.toAbsolutePath().getParent();
        named(extras, plan.name(), () -> startGo(path, built, launch));
        startGo(path, built, launch);
    }

    /**
     * Saves the edited Go files under {@code built} (the module, or the loose file's folder: Delve builds them from
     * disk), then starts {@code launch} for {@code path}.
     */
    private void startGo(Path path, @Nullable Path built, java.util.Map<String, Object> launch) {
        if (!d.savedOrThen(d.buffersUnder(built, "go"), () -> startGo(path, built, launch))) {
            return;
        }
        d.withClosedBreakpoints(() -> d.dap().startExternal(path, com.editora.dap.DebugLanguages.GO, launch));
    }

    /** The folder of {@code go} (its argv) when it is a path, for Delve's PATH: Delve builds with the {@code go} found there. */
    static List<String> goToolDirs(List<String> go) {
        if (go.isEmpty()) {
            return List.of();
        }
        Path exe;
        try {
            exe = Path.of(go.getFirst());
        } catch (java.nio.file.InvalidPathException e) {
            return List.of();
        }
        Path dir = exe.isAbsolute() ? exe.getParent() : null;
        return dir == null ? List.of() : List.of(dir.toString());
    }

    /**
     * Debugs Rust file {@code path} on CodeLLDB: the test at the caret (its test binary, filtered to it), else in an
     * integration test all its tests, else the package's program — or, in a library, the file's tests. Cargo builds
     * it first in the Debug console (after the package's trust, as a Cargo task asks it); its JSON messages then say
     * which executable to launch.
     */
    void debugRustFile(EditorBuffer b, Path path, List<String> args) {
        debugRust(path, b.getContent(), d.caretLine(b), args, DebugCoordinator.LaunchExtras.NONE);
    }

    /** {@link #debugRustFile} for {@code path} with text {@code text} and the caret on {@code caret} (-1: nowhere). */
    void debugRust(Path path, String text, int caret, List<String> args, DebugCoordinator.LaunchExtras extras) {
        d.planOffFx( // the package and its programs are looked for off the FX thread (PERF-01)
                "debug-rust-plan",
                () -> {
                    Path root = com.editora.dap.RustLaunch.packageRoot(path);
                    boolean program = root != null
                            && (java.nio.file.Files.isRegularFile(
                                            root.resolve("src").resolve("main.rs"))
                                    || java.nio.file.Files.isDirectory(
                                            root.resolve("src").resolve("bin")));
                    // and the plan, whose test at the caret is a scan of the text
                    return root == null ? null : new Object[] {root, RustLaunch.plan(root, path, text, caret, program)};
                },
                a -> {
                    Object[] found = a.value();
                    debugRustIn(
                            path,
                            args,
                            extras,
                            found == null ? null : (Path) found[0],
                            found == null ? null : (RustLaunch.Plan) found[1]);
                });
    }

    private void debugRustIn(
            Path path,
            List<String> args,
            DebugCoordinator.LaunchExtras extras,
            @Nullable Path root,
            RustLaunch.@Nullable Plan plan) {
        if (root == null || plan == null) {
            d.host.setStatus(tr("status.debug.rustNoPackage", path.getFileName()));
            return;
        }
        if (!d.factsKnown(
                "debug-rust-trust-look",
                d.facts.executable(com.editora.build.BuildTool.CARGO, root),
                () -> debugRustIn(path, args, extras, root, plan))) {
            return;
        }
        List<String> cargo = d.ops.buildExecutable(com.editora.build.BuildTool.CARGO, root);
        if (cargo == null) {
            return; // the package was not trusted: nothing is built
        }
        named(extras, plan.sessionName(), () -> startRust(path, root, cargo, plan, args, extras)); // the test again
        startRust(path, root, cargo, plan, args, extras);
    }

    /**
     * Saves the package's edited Rust files, builds {@code plan} with Cargo as a before-launch step (its output in the
     * Debug console; Stop ends it), asks Cargo again for the executables it built (nothing is left to build), and
     * launches the one for {@code path} on CodeLLDB.
     */
    private void startRust(
            Path path,
            Path root,
            List<String> cargo,
            com.editora.dap.RustLaunch.Plan plan,
            List<String> args,
            DebugCoordinator.LaunchExtras extras) {
        if (!d.savedOrThen(d.buffersUnder(root, "rust"), () -> startRust(path, root, cargo, plan, args, extras))) {
            return;
        }
        if (d.beforeLaunchActive() || d.s().pendingBuild != null) {
            d.host.setStatus(tr("status.run.busy"));
            return;
        }
        List<String> build = new java.util.ArrayList<>(cargo);
        build.addAll(plan.buildArgs());
        List<String> json = new java.util.ArrayList<>(cargo);
        json.addAll(plan.jsonArgs());
        boolean terminal = d.host.settings().isDebugProgramConsole();
        d.beforeLaunch()
                .run(
                        d.host,
                        new BeforeLaunchStep.Command(
                                String.valueOf(path.getFileName()), build, "status.run.cargoBuild"),
                        root,
                        java.util.Map.of(),
                        d.beforeLaunchConsole(!extras.afterStep()),
                        () -> d.startOffFx("editora-cargo-artifacts", session -> () -> {
                            var listed = com.editora.dap.RustLaunch.listArtifacts(root, json, d.CARGO_LIST_TIMEOUT);
                            d.laterIn(
                                    session,
                                    () -> launchRustArtifact(path, root, plan, args, terminal, listed, extras));
                        }));
        d.updateDebugAvailability();
    }

    /** Launches the executable Cargo's {@code listed} messages name for {@code path}, or says why there is none. */
    private void launchRustArtifact(
            Path path,
            Path root,
            com.editora.dap.RustLaunch.Plan plan,
            List<String> args,
            boolean terminal,
            com.editora.process.ProcessRunner.Result listed,
            DebugCoordinator.LaunchExtras extras) {
        if (!listed.ok()) {
            String why = listed.err().strip();
            d.host.setStatus(tr(
                    "status.debug.rustBuildFailed",
                    why.isEmpty()
                            ? "exit " + listed.exit()
                            : why.lines().reduce((a, z) -> z).orElse(why)));
            return;
        }
        com.editora.dap.RustLaunch.Artifact artifact =
                com.editora.dap.RustLaunch.pick(com.editora.dap.RustLaunch.artifacts(listed.out()), path, plan.mode());
        if (artifact == null) {
            d.host.setStatus(tr("status.debug.rustNoProgram", path.getFileName()));
            return;
        }
        List<String> programArgs = new java.util.ArrayList<>(plan.testArgs());
        programArgs.addAll(args);
        java.util.Map<String, Object> launch = com.editora.dap.RustLaunch.launch(
                artifact.executable(),
                programArgs,
                extras.cwd() != null ? extras.cwd() : root,
                extras.env(),
                false,
                terminal);
        d.ops.openToolWindow();
        d.withClosedBreakpoints(() -> d.dap().startExternal(path, com.editora.dap.DebugLanguages.RUST, launch));
    }

    /**
     * Debugs C or C++ file {@code path} on CodeLLDB: built first — its CMake project's Debug build, its Make project's
     * default goal (each after the folder's trust: their build files are code), or the file alone by the C or C++
     * compiler — then the executable that made is launched ({@link com.editora.dap.NativeBuild}).
     */
    void debugNativeFile(EditorBuffer b, Path path, List<String> args) {
        debugNative(path, "cpp".equals(b.getLanguage()), args, DebugCoordinator.LaunchExtras.NONE);
    }

    /** {@link #debugNativeFile} for {@code path}, a lone file compiled as C++ ({@code cpp}) or as C. */
    void debugNative(Path path, boolean cpp, List<String> args, DebugCoordinator.LaunchExtras extras) {
        Path out = nativeOutputDir();
        d.planOffFx( // the project's build files are looked for off the FX thread (PERF-01)
                "debug-native-plan",
                () -> {
                    Path cmake = com.editora.dap.NativeBuild.cmakeRoot(path);
                    Path make = cmake == null ? com.editora.dap.NativeBuild.makeRoot(path) : null;
                    return java.util.Optional.ofNullable(com.editora.dap.NativeBuild.plan(path, cpp, cmake, make, out));
                },
                a -> debugNativePlanned(
                        path, args, extras, a.value() == null ? null : a.value().orElse(null)));
    }

    private void debugNativePlanned(
            Path path,
            List<String> args,
            DebugCoordinator.LaunchExtras extras,
            com.editora.dap.NativeBuild.@Nullable Plan plan) {
        if (plan == null) {
            d.host.setStatus(tr("status.debug.nativeHeaderAlone", path.getFileName()));
            return;
        }
        if (plan.tool() != com.editora.dap.NativeBuild.Tool.COMPILER) {
            String buildFile = plan.tool() == com.editora.dap.NativeBuild.Tool.CMAKE ? "CMakeLists.txt" : "Makefile";
            String key = plan.tool() == com.editora.dap.NativeBuild.Tool.CMAKE
                    ? "dialog.trust.item.cmake"
                    : "dialog.trust.item.make";
            if (!d.ops.trustBuild(plan.root(), List.of(tr(key, buildFile)))) {
                return; // not trusted: nothing is built
            }
        }
        named(extras, plan.sessionName(), () -> startNative(path, plan, args, extras));
        startNative(path, plan, args, extras);
    }

    /** Where a lone C or C++ file's program is compiled to: the config folder's cache, else the temporary folder. */
    private Path nativeOutputDir() {
        Path config = d.host.configDir();
        return config != null
                ? config.resolve("cache").resolve("native-debug")
                : Path.of(System.getProperty("java.io.tmpdir"), "editora-native-debug");
    }

    /** Saves the project's C and C++ files, runs {@code plan}'s build steps in the Debug console, then launches. */
    private void startNative(
            Path path, com.editora.dap.NativeBuild.Plan plan, List<String> args, DebugCoordinator.LaunchExtras extras) {
        List<EditorBuffer> sources = new ArrayList<>(d.buffersUnder(plan.root(), "c"));
        sources.addAll(d.buffersUnder(plan.root(), "cpp"));
        if (!d.savedOrThen(sources, () -> startNative(path, plan, args, extras))) {
            return;
        }
        if (d.beforeLaunchActive() || d.s().pendingBuild != null) {
            d.host.setStatus(tr("status.run.busy"));
            return;
        }
        boolean terminal = d.host.settings().isDebugProgramConsole();
        d.planOffFx( // CMake's codemodel request and the output folder are written off the FX thread
                "debug-native-prepare",
                () -> {
                    if (plan.buildDir() != null) {
                        com.editora.dap.NativeBuild.requestCodemodel(plan.buildDir());
                    }
                    if (plan.output() != null && plan.output().getParent() != null) {
                        java.nio.file.Files.createDirectories(plan.output().getParent());
                    }
                    return plan;
                },
                a -> {
                    if (a.failure() != null) {
                        d.host.setStatus(tr("debug.error.startFailed", a.why()));
                        return;
                    }
                    runNativeSteps(
                            path, plan, 0, !extras.afterStep(), () -> launchNative(path, plan, args, terminal, extras));
                });
        d.updateDebugAvailability();
    }

    /**
     * Runs {@code plan}'s build step {@code index} and those after it, then {@code then}; a failed one ends it. The
     * first one starts the Debug console afresh when {@code fresh}.
     */
    private void runNativeSteps(
            Path path, com.editora.dap.NativeBuild.Plan plan, int index, boolean fresh, Runnable then) {
        if (index >= plan.steps().size()) {
            then.run();
            return;
        }
        d.beforeLaunch()
                .run(
                        d.host,
                        new BeforeLaunchStep.Command(
                                String.valueOf(path.getFileName()), plan.steps().get(index), "status.run.nativeBuild"),
                        plan.root(),
                        java.util.Map.of(),
                        d.beforeLaunchConsole(fresh && index == 0),
                        // after the step has ended: the next one is not refused as "still running"
                        () -> d.later(() -> runNativeSteps(path, plan, index + 1, fresh, then)));
    }

    /** Finds the executable {@code plan} built (off the FX thread) and launches it, or says there is none. */
    private void launchNative(
            Path path,
            com.editora.dap.NativeBuild.Plan plan,
            List<String> args,
            boolean terminal,
            DebugCoordinator.LaunchExtras extras) {
        d.startOffFx("editora-native-executable", session -> () -> {
            Path exe = com.editora.dap.NativeBuild.executable(plan, path);
            d.laterIn(session, () -> {
                if (exe == null) {
                    d.host.setStatus(tr("status.debug.nativeNoProgram", path.getFileName()));
                    return;
                }
                boolean gdb = d.gdbForCpp();
                Path cwd = extras.cwd() != null ? extras.cwd() : plan.root();
                java.util.Map<String, Object> launch = gdb
                        ? com.editora.dap.NativeBuild.gdbLaunch(exe, args, cwd, extras.env())
                        : com.editora.dap.NativeBuild.launch(exe, args, cwd, extras.env(), terminal);
                com.editora.dap.DebugLanguage profile =
                        gdb ? com.editora.dap.DebugLanguages.GDB : com.editora.dap.DebugLanguages.CPP;
                d.ops.openToolWindow();
                d.withClosedBreakpoints(() -> d.dap().startExternal(path, profile, launch));
            });
        });
    }

    /**
     * Debugs C# file {@code path} on netcoredbg: its project — after the folder's trust, as MSBuild runs the project's
     * targets — or, outside every project, the file as a .NET 10 file-based app, is built with {@code dotnet build} in
     * the Debug console; the assembly that made is then launched in the project's folder
     * ({@link com.editora.dap.DotnetLaunch}).
     */
    void debugCsharpFile(Path path, List<String> args) {
        d.planOffFx( // the project file is a folder walk with listings: off the FX thread (PERF-01)
                "debug-dotnet-plan",
                () -> com.editora.dap.DotnetLaunch.plan(path, com.editora.dap.DotnetLaunch.projectFile(path)),
                a -> {
                    if (a.value() != null) {
                        debugCsharpPlanned(path, args, a.value());
                    }
                });
    }

    private void debugCsharpPlanned(Path path, List<String> args, com.editora.dap.DotnetLaunch.Plan plan) {
        // A lone file too: the SDK's build of it imports the folders' Directory.Build.props / .targets, which are code.
        if (!d.ops.trustBuild(
                plan.root(),
                List.of(tr("dialog.trust.item.dotnet", plan.target().getFileName())))) {
            return; // not trusted: nothing is built
        }
        d.nameSession(plan.sessionName());
        d.s().relaunch = () -> startCsharp(path, plan, args);
        startCsharp(path, plan, args);
    }

    /** The .NET SDK's command (a test's stand-in replaces it). */
    List<String> dotnetCommand = List.of("dotnet");

    /** How long {@code dotnet} has to name the assembly it built (it only evaluates the project). */
    private static final java.time.Duration DOTNET_QUERY_TIMEOUT = java.time.Duration.ofMinutes(2);

    /** Saves the C# files under the project, builds it in the Debug console, then launches its assembly. */
    private void startCsharp(Path path, com.editora.dap.DotnetLaunch.Plan plan, List<String> args) {
        if (!d.savedOrThen(d.buffersUnder(plan.root(), "csharp"), () -> startCsharp(path, plan, args))) {
            return;
        }
        if (d.beforeLaunchActive() || d.s().pendingBuild != null) {
            d.host.setStatus(tr("status.run.busy"));
            return;
        }
        List<String> dotnet = dotnetCommand;
        List<String> build = new java.util.ArrayList<>(dotnet);
        build.addAll(plan.buildArgs());
        d.beforeLaunch()
                .run(
                        d.host,
                        new BeforeLaunchStep.Command(
                                String.valueOf(path.getFileName()), build, "status.run.dotnetBuild"),
                        plan.root(),
                        com.editora.dap.DotnetLaunch.BUILD_ENV,
                        d.beforeLaunchConsole(),
                        () -> d.startOffFx("editora-dotnet-target", session -> () -> {
                            var listed =
                                    com.editora.dap.DotnetLaunch.queryTargetPath(dotnet, plan, DOTNET_QUERY_TIMEOUT);
                            Path built = listed.ok() ? com.editora.dap.DotnetLaunch.targetPath(listed.out()) : null;
                            boolean there = built != null && java.nio.file.Files.isRegularFile(built); // off FX
                            d.laterIn(session, () -> launchCsharpAssembly(path, plan, args, listed, there));
                        }));
        d.updateDebugAvailability();
    }

    /** Launches the assembly {@code listed} names for {@code path} on netcoredbg, or says why there is none. */
    private void launchCsharpAssembly(
            Path path,
            com.editora.dap.DotnetLaunch.Plan plan,
            List<String> args,
            com.editora.process.ProcessRunner.Result listed,
            boolean there) {
        Path assembly = listed.ok() ? com.editora.dap.DotnetLaunch.targetPath(listed.out()) : null;
        if (!listed.ok()) {
            String why = (listed.err().isBlank() ? listed.out() : listed.err()).strip();
            d.host.setStatus(tr(
                    "status.debug.csharpBuildFailed",
                    why.isEmpty()
                            ? "exit " + listed.exit()
                            : why.lines().reduce((a, z) -> z).orElse(why)));
            return;
        }
        if (assembly == null || !there) {
            d.host.setStatus(tr("status.debug.csharpNoProgram", path.getFileName()));
            return;
        }
        java.util.Map<String, Object> launch = com.editora.dap.DotnetLaunch.launch(assembly, args, plan.root());
        d.ops.openToolWindow();
        d.withClosedBreakpoints(() -> d.dap().startExternal(path, com.editora.dap.DebugLanguages.CSHARP, launch));
    }
}
