package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javafx.application.Platform;

import com.editora.editor.EditorBuffer;
import com.editora.run.PythonInterpreters;
import com.editora.run.PythonInterpreters.PythonInterpreter;
import com.editora.run.PythonRunPlan;

import static com.editora.i18n.Messages.tr;

/**
 * The window's Python interpreter: the status-bar item on Python files, its picker
 * ({@code python.selectInterpreter}), and the interpreter the Python language server is told to analyse with
 * (review B1, adl's decision 1).
 *
 * <p>Every answer comes from {@link PythonInterpreters} — the one resolver Run and Debug use too — fed with the
 * project's pick ({@code WorkspaceState.pythonInterpreter}) and the global setting. Resolution touches the file
 * system and PATH, so it runs off the FX thread; results come back with {@link Platform#runLater}. A no-project
 * window keeps its pick in its own (no-project) workspace state, so it applies to every loose file there.
 */
final class PythonInterpreterCoordinator {

    /** What this coordinator needs from its window. */
    interface Host {
        /** The window's project root, or null without a project. */
        Path projectRoot();

        /** The window's configuration (settings + workspace state). */
        com.editora.config.ConfigManager config();

        /** Shows or hides the status-bar item ({@code text == null} hides it). */
        void setStatusItem(String text, String tooltip);

        /** Called after the pick changed, so servers (and anything else) follow it. */
        void interpreterChanged();

        OverlayHost overlayHost();

        javafx.stage.Window window();

        void setStatus(String message);
    }

    private final Host host;

    /** Executable → its {@code --version} line; filled off the FX thread, never re-probed. */
    private final Map<String, String> versions = new ConcurrentHashMap<>();

    /** The values a resolution reads, captured on the FX thread so a background thread can use them. */
    private record Snapshot(Path projectRoot, String choice, com.editora.config.Settings settings) {}

    private volatile Snapshot snapshot = new Snapshot(null, "", null);

    /** Bumped per status request, so a slow answer for a tab the user has left is dropped. */
    private int statusGen;

    PythonInterpreterCoordinator(Host host) {
        this.host = host;
    }

    /** Re-reads the project root, the pick and the setting (FX thread). */
    void captureSnapshot() {
        com.editora.config.ConfigManager config = host.config();
        String choice = config == null ? "" : config.getWorkspaceState().getPythonInterpreter();
        snapshot = new Snapshot(host.projectRoot(), choice, config == null ? null : config.getSettings());
    }

    /**
     * The interpreter for {@code file} (null = the project/folder as a whole), named as the machine it runs on
     * names it. Off the FX thread: on a remote host, a WSL distribution or a container the venv is looked for
     * there, through Run's own planning ({@link PythonRunLauncher}, {@link PythonRunPlan}); a lookup over a
     * dropped connection falls back to that host's own {@code python3}, as Run does.
     */
    PythonInterpreter resolve(Path file) {
        Snapshot s = snapshot;
        Path where = file != null ? file : s.projectRoot();
        String setting = PythonRunLauncher.setting(where, s.settings());
        PythonRunPlan.Host machine = PythonRunLauncher.hostFor(where);
        if (machine.local()) {
            return PythonInterpreters.resolveLocal(file, s.projectRoot(), "", s.choice(), setting);
        }
        PythonRunPlan.Request request =
                PythonRunPlan.Request.file(file, s.projectRoot(), "", List.of(), "", s.choice(), setting);
        try {
            return PythonRunPlan.plan(request, machine).interpreter();
        } catch (RuntimeException e) {
            return PythonRunPlan.plan(request, machine.withoutLookups()).interpreter();
        }
    }

    /** Whether {@code file} (or the project, for null) runs its tools on another machine. */
    boolean elsewhere(Path file) {
        Path where = file != null ? file : snapshot.projectRoot();
        return where != null && !com.editora.process.ExecTargets.runsHere(where);
    }

    /**
     * The interpreter a Python language server rooted at {@code root} analyses with: an absolute executable,
     * or null to let the server look for one. Off the FX thread (the {@code LspManager} start executor).
     */
    String pythonPathForServerRoot(Path root) {
        if (root == null) {
            return null;
        }
        // A file directly in the root: the venv search starts at the root itself and walks up to the project.
        return PythonInterpreterView.pythonPathForServer(resolve(root.resolve("__editora__.py")));
    }

    /** Updates the status-bar item for the active buffer: shown on Python files, local or remote (FX thread). */
    void refreshStatus(EditorBuffer buffer) {
        int gen = ++statusGen;
        boolean python = buffer != null && buffer.isPython();
        Path path = python ? buffer.getPath() : null;
        if (!python) {
            host.setStatusItem(null, null);
            return;
        }
        captureSnapshot();
        boolean remote = elsewhere(path);
        Thread.ofVirtual().name("python-interpreter-status").start(() -> {
            PythonInterpreter interp = resolve(path);
            // A remote interpreter is not run here for its version: the item names it and says why.
            String version = remote ? "" : versionOf(interp.executablePath());
            Platform.runLater(() -> {
                if (gen == statusGen) {
                    host.setStatusItem(
                            PythonInterpreterView.statusText(interp, PythonInterpreterView.parseVersion(version)),
                            PythonInterpreterView.tooltip(interp));
                }
            });
        });
    }

    /** The {@code --version} line of {@code executable} ("" when it does not run). Off the FX thread. */
    String versionOf(String executable) {
        return versions.computeIfAbsent(executable, exe -> {
            com.editora.doctor.DoctorProbes.Presence p = com.editora.doctor.DoctorProbes.version(List.of(exe));
            return p.present() ? p.version() : "";
        });
    }

    /** {@code python.selectInterpreter}: lists the choices (found off the FX thread), then shows the picker. */
    void selectInterpreter(EditorBuffer buffer) {
        captureSnapshot();
        Path file = buffer == null ? null : buffer.getPath();
        Path root = snapshot.projectRoot() != null ? snapshot.projectRoot() : file == null ? null : file.getParent();
        PythonRunPlan.Host machine = PythonRunLauncher.hostFor(file != null ? file : root);
        boolean windows = machine.windows();
        Thread.ofVirtual().name("python-interpreter-choices").start(() -> {
            PythonInterpreter current = resolve(file);
            List<Path> found = new java.util.ArrayList<>();
            try {
                found.addAll(PythonInterpreters.listVenvs(root, windows));
                PythonInterpreters.findProjectVenv(file, snapshot.projectRoot(), Files::isRegularFile, windows)
                        .filter(v -> !found.contains(v))
                        .ifPresent(found::add);
            } catch (RuntimeException e) {
                // a dropped connection: only what resolved, PATH and Browse… are offered
            }
            // Elsewhere an environment is offered as that machine names it (toTarget), and nothing is run here for
            // a version.
            List<PythonInterpreterView.Choice> choices = PythonInterpreterView.choices(
                    current,
                    found,
                    windows,
                    machine.toTarget(),
                    machine.onPath(),
                    machine.local() ? this::versionOf : exe -> "");
            Platform.runLater(() -> showPicker(choices, root));
        });
    }

    private void showPicker(List<PythonInterpreterView.Choice> choices, Path root) {
        QuickOpen<PythonInterpreterView.Choice> picker = new QuickOpen<>(
                tr("python.interpreter.pick.title"),
                tr("python.interpreter.pick.prompt"),
                () -> choices,
                PythonInterpreterView.Choice::label,
                PythonInterpreterView.Choice::detail,
                c -> {
                    if (c != null) {
                        choose(c, root);
                    }
                });
        picker.setOverlayHost(host.overlayHost());
        picker.setCurrentItem(() -> choices.stream()
                .filter(c -> c.kind() == PythonInterpreterView.Kind.CURRENT)
                .findFirst()
                .orElse(null));
        picker.show(host.window());
    }

    /** Applies a picker row (FX thread). */
    void choose(PythonInterpreterView.Choice choice, Path browseFrom) {
        switch (choice.kind()) {
            case AUTO -> store("");
            case BROWSE -> browse(browseFrom);
            default -> store(choice.command());
        }
    }

    private void browse(Path from) {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(tr("python.interpreter.pick.browseTitle"));
        if (from != null && Files.isDirectory(from)) {
            chooser.setInitialDirectory(from.toFile());
        }
        java.io.File picked = chooser.showOpenDialog(host.window());
        if (picked != null) {
            store(picked.getAbsolutePath());
        }
    }

    /** Stores {@code executable} as the project's pick ("" = detect automatically) and tells everyone. */
    void store(String executable) {
        com.editora.config.ConfigManager config = host.config();
        if (config == null) {
            return;
        }
        String stored = executable.isEmpty() ? "" : PythonInterpreterView.storedChoice(host.projectRoot(), executable);
        config.getWorkspaceState().setPythonInterpreter(stored);
        config.saveAsync();
        captureSnapshot();
        host.setStatus(
                stored.isEmpty()
                        ? tr("python.interpreter.status.auto")
                        : tr("python.interpreter.status.picked", stored));
        host.interpreterChanged();
    }
}
