package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.editora.dap.DebugLanguages;
import com.editora.dap.DotnetLaunch;
import com.editora.dap.DotnetTests;
import com.editora.editor.EditorBuffer;
import com.editora.run.RunService;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Debug Test at Caret in C# ({@link DotnetTests}): {@code dotnet test} runs the test at the caret (or its class's) in
 * the Debug console with {@code VSTEST_HOST_DEBUG=1}; the test host it starts waits for a debugger and prints its
 * process id, netcoredbg attaches to that process, and the test runs under the debugger — its breakpoints stop.
 * Stop ends the run too ({@link DebugSession#testHost}); Restart runs the same test again. Part of {@link
 * DebugCoordinator}.
 */
final class CsharpTestDebug {

    private final DebugCoordinator d;
    private final CoordinatorHost host;
    private final DebugCoordinator.Ops ops;

    CsharpTestDebug(DebugCoordinator d) {
        this.d = d;
        this.host = d.host;
        this.ops = d.ops;
    }

    /** Ends {@code session}'s {@code dotnet test} run, if it has one (Stop). */
    static void stopRun(DebugSession session) {
        RunService run = session.testHost;
        if (run != null) {
            run.stop();
        }
    }

    /**
     * Debugs the C# test at the caret of {@code b}, when {@code b} is a file of a test project with a test there (or a
     * test class around the caret). False — nothing done — for any other buffer.
     */
    boolean debugAtCaret(@Nullable EditorBuffer b, Runnable otherwise) {
        Path file = b == null ? null : b.getPath();
        if (b == null || file == null || !"csharp".equals(b.getLanguage())) {
            return false;
        }
        // The project above the file is found and read, and the test at the caret found in the text, off the FX
        // thread (PERF-01, Pauta 6.0.1); a C# file that is not in a test project, or a caret in no test, goes on to
        // {@code otherwise}, as a false answer did.
        String text = b.getContent();
        int caret = DebugCoordinator.caretLine(b);
        com.editora.perf.OffFx.then(
                "CsharpTestDebug.debugAtCaret",
                () -> {
                    Path project = DotnetLaunch.projectFile(file);
                    if (project == null || !DotnetTests.isTestProject(read(project))) {
                        return null;
                    }
                    DotnetTests.Target target = DotnetTests.testAt(text, caret);
                    return target == null ? null : new Found(project, target);
                },
                found -> {
                    Found f = found.value();
                    if (f == null) {
                        otherwise.run();
                    } else {
                        debugInProject(file, f.project(), f.target());
                    }
                });
        return true;
    }

    /** The test project and the test at the caret. */
    private record Found(Path project, DotnetTests.Target target) {}

    private void debugInProject(Path file, Path project, DotnetTests.Target target) {
        if (!d.debugEffectiveFor("csharp")) {
            host.setStatus(d.unavailableMessage("csharp", file));
            return;
        }
        Path root = project.toAbsolutePath().getParent();
        if (root == null || !ops.trustBuild(root, List.of(tr("dialog.trust.item.dotnet", project.getFileName())))) {
            return; // not trusted: nothing is built or run
        }
        d.nameSession(target.displayName());
        Runnable start = () -> start(file, project, root, target);
        d.s().relaunch = start; // Restart runs the same test again
        start.run();
    }

    /** The command that runs {@code target}'s tests of {@code project}, its host waiting for a debugger. */
    static List<String> command(List<String> dotnet, Path project, DotnetTests.Target target) {
        List<String> argv = new ArrayList<>(dotnet);
        argv.addAll(List.of("test", project.toString(), "--filter", DotnetTests.filter(target)));
        return argv;
    }

    /** The environment the run gets: the test host waits for a debugger, and no first-run banner. */
    static final Map<String, String> ENVIRONMENT = Map.of("VSTEST_HOST_DEBUG", "1", "DOTNET_NOLOGO", "1");

    private void start(Path file, Path project, Path root, DotnetTests.Target target) {
        if (!d.savedOrThen(d.buffersUnder(root, "csharp"), () -> start(file, project, root, target))) {
            return; // saved first, off the FX thread (PERF-01); started again once the writes have landed
        }
        DebugSession session = d.s();
        RunService previous = session.testHost;
        if (previous != null && previous.isRunning()) {
            previous.stop();
        }
        RunService run = new RunService();
        session.testHost = run;
        BeforeLaunchStep.Console console = d.beforeLaunchConsole(true);
        List<String> argv = command(d.dotnetCommand, project, target);
        boolean[] attached = {false};
        host.setStatus(tr("status.debug.dotnetTestStarting", target.displayName()));
        console.started(String.join(" ", argv));
        run.runInDir(root, argv, ENVIRONMENT, new RunService.Listener() {
            @Override
            public void onStart(String commandLine) {
                // the header is already up
            }

            @Override
            public void onOutput(String line, boolean stderr) {
                d.inSession(session, () -> {
                    console.output(line, stderr);
                    long pid = DotnetTests.processId(line);
                    if (pid > 0 && !attached[0]) {
                        attached[0] = true;
                        console.ended(0, null);
                        ops.openToolWindow();
                        d.withClosedBreakpoints(
                                () -> d.dap().startExternal(file, DebugLanguages.CSHARP, DotnetLaunch.attach(pid)));
                    }
                });
            }

            @Override
            public void onExit(int code) {
                d.inSession(session, () -> {
                    if (session.testHost == run) {
                        session.testHost = null;
                    }
                    if (!attached[0]) {
                        console.ended(code, null);
                        host.setStatus(tr("status.debug.dotnetTestNoHost", target.displayName(), code));
                    }
                });
            }

            @Override
            public void onError(String message) {
                d.inSession(session, () -> {
                    if (session.testHost == run) {
                        session.testHost = null;
                    }
                    console.ended(-1, message);
                    host.setStatus(tr("status.debug.dotnetTestNoHost", target.displayName(), message));
                });
            }
        });
        run.closeInput();
    }

    private static @Nullable String read(Path file) {
        try {
            return Files.size(file) < 1_000_000 ? Files.readString(file) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
