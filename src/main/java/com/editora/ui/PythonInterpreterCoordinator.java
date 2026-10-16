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
    private record Snapshot(Path projectRoot, String choice, String setting) {}

    private volatile Snapshot snapshot = new Snapshot(null, "", "");

    /** Bumped per status request, so a slow answer for a tab the user has left is dropped. */
    private int statusGen;

    PythonInterpreterCoordinator(Host host) {
        this.host = host;
    }

    /** Re-reads the project root, the pick and the setting (FX thread). */
    void captureSnapshot() {
        com.editora.config.ConfigManager config = host.config();
        String choice = config == null ? "" : config.getWorkspaceState().getPythonInterpreter();
        String setting = config == null ? "" : config.getSettings().getPythonDebugCommand();
        snapshot = new Snapshot(host.projectRoot(), choice, setting);
    }

    /** The interpreter for {@code file} (null = the project/folder as a whole). Off the FX thread. */
    PythonInterpreter resolve(Path file) {
        Snapshot s = snapshot;
        return PythonInterpreters.resolveLocal(file, s.projectRoot(), "", s.choice(), s.setting());
    }

    /**
     * The interpreter a Python language server rooted at {@code root} analyses with: an absolute executable,
     * or null to let the server look for one. Off the FX thread (the {@code LspManager} start executor).
     */
    String pythonPathForServerRoot(Path root) {
        if (root == null || com.editora.vfs.Vfs.isRemote(root)) {
            return null;
        }
        // A file directly in the root: the venv search starts at the root itself and walks up to the project.
        return PythonInterpreterView.pythonPathForServer(resolve(root.resolve("__editora__.py")));
    }

    /** Updates the status-bar item for the active buffer: shown on local Python files only (FX thread). */
    void refreshStatus(EditorBuffer buffer) {
        int gen = ++statusGen;
        boolean python = buffer != null && buffer.isPython();
        Path path = python ? buffer.getPath() : null;
        if (!python || (path != null && com.editora.vfs.Vfs.isRemote(path))) {
            host.setStatusItem(null, null);
            return;
        }
        captureSnapshot();
        Thread.ofVirtual().name("python-interpreter-status").start(() -> {
            PythonInterpreter interp = resolve(path);
            String version = versionOf(PythonInterpreterView.executable(interp));
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
        boolean windows = PythonInterpreters.isWindows();
        Thread.ofVirtual().name("python-interpreter-choices").start(() -> {
            PythonInterpreter current = resolve(file);
            List<Path> venvs = new java.util.ArrayList<>(PythonInterpreters.listVenvs(root, windows));
            PythonInterpreters.findProjectVenv(file, snapshot.projectRoot(), Files::isRegularFile, windows)
                    .filter(v -> !venvs.contains(v))
                    .ifPresent(venvs::add);
            List<PythonInterpreterView.Choice> choices = PythonInterpreterView.choices(
                    current, venvs, windows, PythonInterpreters::onAugmentedPath, this::versionOf);
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
