package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.config.Settings;
import com.editora.dap.DebugLanguages;
import com.editora.dap.ElixirLaunch;
import com.editora.editor.EditorBuffer;
import com.editora.run.ElixirRun;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Elixir launches of {@link DebugCoordinator}, on ElixirLS's debug adapter: Debug File on an Elixir file of a Mix project
 * as {@link ElixirRun} plans it (its tests, a script under {@code mix run}, the project), and Debug Test at Caret in a
 * test file ({@code mix test file:line}). A file outside a Mix project is not debugged: ElixirLS runs Mix tasks alone.
 * What the plan reads is read off the FX thread (PERF-01, Pauta 6.2.0). Part of {@link DebugCoordinator}.
 */
final class ElixirDebugLaunch {

    private final DebugCoordinator d; // its host and ops are read when used: this is made with the coordinator

    ElixirDebugLaunch(DebugCoordinator d) {
        this.d = d;
    }

    /** Looks for ElixirLS (as configured, else Editora's install or VS Code's extension). */
    void configure(boolean on, Settings s, ProjectScope scope, Runnable found) {
        d.dap()
                .configureExternal(
                        DebugLanguages.ELIXIR,
                        on && s.isElixirDebugEnabled(),
                        scope.debugTool("elixir", s.getElixirDebugPath()),
                        f -> found.run());
    }

    /** {@code file}'s text, or null when it cannot be read. */
    static @Nullable String read(Path file) {
        try {
            return Files.readString(file);
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    /** Debug File on Elixir file {@code file} (text {@code text}) with program arguments {@code args}. */
    void debug(Path file, String text, List<String> args) {
        plan(file, text, args, -1, file.getFileName().toString());
    }

    /** Debug Test at Caret on an ExUnit test file: the test at the caret's line. False, nothing done, for another. */
    boolean debugTestAtCaret(@Nullable EditorBuffer b) {
        Path file = b == null ? null : b.getPath();
        if (b == null
                || file == null
                || !"elixir".equals(b.getLanguage())
                || !ElixirRun.isTest(String.valueOf(file.getFileName()))) {
            return false;
        }
        if (!d.debugEffectiveFor("elixir", file)) {
            d.host.setStatus(d.unavailableMessage("elixir", file));
            return true;
        }
        int caret = DebugCoordinator.caretLine(b);
        d.ops.openToolWindow();
        plan(file, b.getContent(), List.of(), caret, file.getFileName() + ":" + (caret + 1));
        return true;
    }

    /** Plans {@code file}'s launch off the FX thread, then starts it as session {@code label}. */
    private void plan(Path file, String text, List<String> args, int line, String label) {
        Path top = d.ops.projectRoot();
        d.planOffFx(
                "debug-elixir-plan",
                () -> {
                    com.editora.perf.BlockingSites.enter("ElixirRun.plan");
                    return ElixirRun.plan(file, text, args, line, top, Files::isRegularFile, ElixirDebugLaunch::read);
                },
                a -> {
                    ElixirRun.Plan plan = a.value();
                    if (plan == null) {
                        d.host.setStatus(tr(
                                "status.debug.planFailed",
                                file.getFileName(),
                                String.valueOf(
                                        a.failure() == null ? "" : a.failure().getMessage())));
                        return;
                    }
                    launch(file, plan, label);
                });
    }

    private void launch(Path file, ElixirRun.Plan plan, String label) {
        Path project = plan.project();
        if (project == null) {
            d.host.setStatus(tr("status.debug.elixirNeedsMix"));
            return;
        }
        if (!d.ops.trustBuild(project, List.of(tr("dialog.trust.item.mix", "mix.exs")))) {
            return; // Mix compiles the project and its dependencies: their code runs only in a trusted folder
        }
        Map<String, Object> launch = ElixirLaunch.launch(plan);
        Runnable start = () -> afterServerStarts(
                () -> d.withClosedBreakpoints(() -> d.dap().startExternal(file, DebugLanguages.ELIXIR, launch)));
        Runnable go = () -> {
            d.s().relaunch = start;
            start.run();
        };
        // The project's edited Elixir files are saved first (off the FX thread): Mix compiles them from disk.
        d.nameSession(label);
        if (d.savedOrThen(d.buffersUnder(project, "elixir"), go)) {
            go.run();
        }
    }

    /** How long the debugger waits for the Elixir language server to start before it starts anyway. */
    static final java.time.Duration SERVER_START_WAIT = java.time.Duration.ofMinutes(2);

    /** How often it looks. */
    static final javafx.util.Duration SERVER_START_POLL = javafx.util.Duration.millis(250);

    /**
     * Runs {@code start} once this window's Elixir language server has started (FX thread). Both boot from the one
     * ElixirLS install, whose build lock the later one waits for, and ElixirLS's debug adapter, made to wait while it
     * boots, dies before the program starts ("Waiting for lock on the build directory", ElixirLS 0.31.1 on Elixir
     * 1.18): pressed right after a project's file opened, Debug failed. The server answers {@code initialize} once
     * it has booted. Nothing waits when no server is starting, nor longer than {@link #SERVER_START_WAIT}.
     */
    private void afterServerStarts(Runnable start) {
        whenStarted(
                () -> d.lspManager.isStarting("elixir"),
                System.nanoTime() + SERVER_START_WAIT.toNanos(),
                System::nanoTime,
                again -> {
                    javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(SERVER_START_POLL);
                    pause.setOnFinished(e -> again.run());
                    pause.play();
                },
                () -> d.host.setStatus(tr("status.debug.elixirWaitsForServer")),
                start);
    }

    /**
     * Runs {@code start} now when {@code starting} says no or {@code deadline} (of {@code now}) has passed; else says
     * so once ({@code waiting}) and asks again through {@code later}. Pure apart from what it is given.
     */
    static void whenStarted(
            java.util.function.BooleanSupplier starting,
            long deadline,
            java.util.function.LongSupplier now,
            java.util.function.Consumer<Runnable> later,
            Runnable waiting,
            Runnable start) {
        if (!starting.getAsBoolean() || now.getAsLong() - deadline >= 0) {
            start.run();
            return;
        }
        waiting.run();
        later.accept(() -> whenStarted(starting, deadline, now, later, () -> {}, start));
    }
}
