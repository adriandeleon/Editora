package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.editora.editor.EditorBuffer;
import com.editora.run.ProgramArgs;
import com.editora.run.RunService;
import com.editora.run.StackTraceLinks;

import static com.editora.i18n.Messages.tr;

/**
 * Run-a-file feature (the gutter ▶ / "Run File" flow + the Run tool-window console), extracted from
 * {@link MainController} via the {@link CoordinatorHost} pattern. Owns the {@link RunService} + the
 * {@link RunPanel}; {@code MainController} keeps the {@code ToolWindow} (built with {@link #panel()}),
 * the run-feature gating (via the LSP feature), and the shared stack-trace link resolver
 * ({@code openRunLink}, also used by the Debug + External-Tool consoles) — which reuses {@link #lastRunDir()}.
 */
final class RunCoordinator {

    /** Window hooks beyond {@link CoordinatorHost} (Run tool window, buffer save, program args, link jump). */
    interface Ops {
        void openToolWindow();

        /** Saves {@code buffer} (dirty → write, untitled → Save-As); {@code false} if cancelled/failed. */
        boolean saveBuffer(EditorBuffer buffer);

        /** The remembered program-arguments string for {@code path} ("" when none). */
        String programArgs(Path path);

        /** Persists the program-arguments string for {@code path} (workspace state + durable save). */
        void setProgramArgs(Path path, String args);

        /** A stack-trace location double-clicked in the console: resolve + jump (shared resolver). */
        void openLink(StackTraceLinks.Link link);
    }

    private final CoordinatorHost host;
    private final Ops ops;
    private final RunService service = new RunService();
    private final RunPanel panel;

    /** The most recent launch, for {@code run.rerun}. */
    private Path lastRunFile;

    private List<String> lastRunCommand;

    RunCoordinator(CoordinatorHost host, Ops ops) {
        this.host = host;
        this.ops = ops;
        this.panel = new RunPanel(this::stopRun);
        panel.setOnInput(service::sendInput); // stdin field → the running process
        panel.setOnLink(ops::openLink); // double-clicked stack-trace line → jump
    }

    RunPanel panel() {
        return panel;
    }

    /** Parent dir of the most recent run file, or {@code null} — used by the shared link resolver. */
    Path lastRunDir() {
        return lastRunFile != null ? lastRunFile.getParent() : null;
    }

    void runActiveFile() {
        runActiveFile(false);
    }

    /** Prompts for program arguments (pre-filled with the file's remembered ones), then runs. */
    void runActiveFileWithArgs() {
        runActiveFile(true);
    }

    /** Re-runs the most recent run (same file + argv) without touching the active tab. */
    void rerunLast() {
        if (lastRunFile == null || lastRunCommand == null) {
            host.setStatus(tr("status.run.noRerun"));
            return;
        }
        if (service.isRunning()) {
            host.setStatus(tr("status.run.busy"));
            return;
        }
        launchRun(lastRunFile, lastRunCommand);
    }

    /**
     * Runs a single Makefile target ({@code make -f <file> [target]}) in the Makefile's directory, saving
     * the buffer first so the on-disk targets/recipes are current. A blank/null {@code target} runs the
     * default goal (bare {@code make}). Backs the per-target gutter ▶ and the generic "Run File" command.
     */
    void runMakeTarget(EditorBuffer buffer, String target) {
        if (buffer == null) {
            return;
        }
        if ((buffer.isDirty() || buffer.getPath() == null) && !ops.saveBuffer(buffer)) {
            return; // user cancelled Save-As, or the save failed — don't run against stale/missing content
        }
        Path path = buffer.getPath();
        if (path == null) {
            return;
        }
        if (service.isRunning()) {
            host.setStatus(tr("status.run.busy"));
            return;
        }
        List<String> command = new ArrayList<>();
        command.add("make");
        // -f the buffer's own file name (basename; RunService runs in its dir): make otherwise reads the
        // dir's default Makefile, so a `build.mk` / `Makefile.inc` buffer would run the wrong file.
        command.add("-f");
        command.add(path.getFileName().toString());
        if (target != null && !target.isBlank()) {
            command.add(target);
        }
        launchRun(path, command);
    }

    private void runActiveFile(boolean promptArgs) {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null || !buffer.isRunnable()) {
            host.setStatus(tr("status.run.notCompact"));
            return;
        }
        if (buffer.isMakefile()) {
            runMakeTarget(buffer, null); // "Run File" on a Makefile ⇒ the default goal
            return;
        }
        if ((buffer.isDirty() || buffer.getPath() == null) && !ops.saveBuffer(buffer)) {
            return; // user cancelled Save-As, or the save failed — don't run stale/missing content
        }
        Path path = buffer.getPath();
        if (path == null) {
            return;
        }
        if (service.isRunning()) {
            host.setStatus(tr("status.run.busy"));
            return;
        }
        boolean java = !buffer.isPython() && !buffer.isShell();
        Runnable proceed = () -> {
            String stored = ops.programArgs(path);
            if (promptArgs) {
                host.promptText(tr("dialog.runArgs.title"), tr("dialog.runArgs.label"), stored, args -> {
                    ops.setProgramArgs(path, args == null ? "" : args.strip());
                    launchRun(path, buildRunCommand(buffer, path));
                });
            } else {
                launchRun(path, buildRunCommand(buffer, path));
            }
        };
        if (java) {
            // Compact source files need the JDK 25+ source-file launcher; preflight so an older java
            // on PATH yields a clear message instead of a cryptic launcher error. Cached after once.
            service.detectJavaMajor(major -> {
                if (major > 0 && major < 25) {
                    host.setStatus(tr("status.run.needJdk25", major));
                    return;
                }
                proceed.run();
            });
        } else {
            proceed.run();
        }
    }

    /** The launcher argv for the buffer's language: interpreter + file + the remembered args. */
    private List<String> buildRunCommand(EditorBuffer buffer, Path path) {
        List<String> command = new ArrayList<>();
        if (buffer.isPython()) {
            command.add("python3");
        } else if (buffer.isShell()) {
            command.add("bash");
        } else {
            command.add("java");
            Integer javaSource = buffer.getShebangJavaSource();
            if (javaSource != null) {
                // An extensionless `java --source N` shebang file: the source launcher needs the flag
                // (a plain `java <file>` only works when the name ends in .java).
                command.add("--source");
                command.add(String.valueOf(javaSource));
            }
        }
        command.add(path.toString());
        command.addAll(ProgramArgs.tokenize(ops.programArgs(path)));
        return command;
    }

    private void launchRun(Path path, List<String> command) {
        lastRunFile = path;
        lastRunCommand = command;
        ops.openToolWindow();
        panel.started(path.getFileName().toString());
        host.setStatus(tr("status.run.started", path.getFileName().toString()));
        service.run(path, command, new RunService.Listener() {
            @Override
            public void onStart(String commandLine) {
                panel.started(commandLine);
            }

            @Override
            public void onOutput(String line, boolean stderr) {
                panel.appendOutput(line, stderr);
            }

            @Override
            public void onExit(int code) {
                panel.finished(code);
                host.setStatus(code == 0 ? tr("status.run.ok") : tr("status.run.exit", code));
            }

            @Override
            public void onError(String message) {
                panel.failed(message);
                host.setStatus(tr("status.run.failed", message));
            }
        });
    }

    /** Stops the currently running program (Run tool window Stop button / {@code run.stop} command). */
    void stopRun() {
        if (service.isRunning()) {
            service.stop();
            host.setStatus(tr("status.run.stopped"));
        }
    }

    void clearConsole() {
        panel.clearConsole();
    }

    void shutdown() {
        service.stop();
    }
}
