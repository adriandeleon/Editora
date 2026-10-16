package com.editora.ui;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;

import com.editora.container.ContainerBindings;
import com.editora.container.ContainerEngine;
import com.editora.container.ContainerExecTarget;

import static com.editora.i18n.Messages.tr;

/**
 * One window's project and the container its tools run in. A project folder bind-mounted into a running
 * container — the dev container started for it, or the container the user named — has its git, builds,
 * tests, language servers and debuggers run there, once the user has agreed for that container.
 *
 * <p>Asking the engine is off the FX thread; the question and the switch-over are on it. The project is
 * looked at again when it changes and when the window comes back to the front (a dev container started or
 * stopped meanwhile), at most once a {@link #RECHECK_AFTER_MILLIS}.
 */
final class ContainerCoordinator {

    /** What the coordinator needs from its window. */
    interface Ops {
        /** This window's project folder, or null. */
        Path projectRoot();

        boolean execAllowed(String targetId);

        void setExecAllowed(String targetId, boolean allowed);

        /** Where the project's tools run has changed: the features that run tools look again. */
        void execChanged();
    }

    static final long RECHECK_AFTER_MILLIS = 30_000;

    private final CoordinatorHost host;
    private final Ops ops;
    /** How the engine is asked, from its configured command (replaceable in tests). */
    Function<ContainerEngine, ContainerBindings.Lookup> lookups = ContainerBindings::lookup;
    /** The one-time question; replaceable in tests. */
    Predicate<ContainerExecTarget> consent = this::askToRunInContainer;

    /** Containers the user said "not now" to this session. */
    private final Set<String> declined = new HashSet<>();
    /** The target this window's project was last found bound to (its id), or null. */
    private String boundId;

    private Path checkedRoot;
    private long checkedAt;

    ContainerCoordinator(CoordinatorHost host, Ops ops) {
        this.host = host;
        this.ops = ops;
    }

    private ContainerEngine engine() {
        return ContainerEngine.of(host.settings().getContainerEngine());
    }

    /** The project changed (or {@code force}: its binding was edited): find its container again. */
    void projectChanged(boolean force) {
        Path root = ops.projectRoot();
        if (root == null
                || !com.editora.vfs.Vfs.isLocal(root)
                || !host.settings().isRemoteExecution()) {
            settle(root, null);
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && root.equals(checkedRoot) && now - checkedAt < RECHECK_AFTER_MILLIS) {
            return;
        }
        checkedRoot = root;
        checkedAt = now;
        String named = host.settings()
                .getContainerBindings()
                .get(root.toAbsolutePath().normalize().toString());
        ContainerBindings.Lookup lookup = lookups.apply(engine());
        Thread.ofVirtual().name("container-binding").start(() -> {
            // The engine is asked only for a folder that has a dev container configuration, or a container
            // the user picked for it: most projects have neither, and need no `docker ps` when they open.
            ContainerExecTarget target = named != null || hasDevContainerConfig(root)
                    ? ContainerBindings.shared().bind(root, named, lookup)
                    : null;
            if (target == null) {
                ContainerBindings.shared().unbind(root);
            }
            Platform.runLater(() -> {
                if (root.equals(ops.projectRoot())) {
                    settle(root, target);
                }
            });
        });
    }

    /** Whether {@code root} has a dev container configuration ({@code .devcontainer/…} or {@code .devcontainer.json}). */
    static boolean hasDevContainerConfig(Path root) {
        return java.nio.file.Files.isRegularFile(root.resolve(".devcontainer").resolve("devcontainer.json"))
                || java.nio.file.Files.isRegularFile(root.resolve(".devcontainer.json"))
                || java.nio.file.Files.isDirectory(root.resolve(".devcontainer"));
    }

    /** The window came to the front: a dev container may have been started or stopped meanwhile. */
    void windowFocused() {
        projectChanged(false);
    }

    /** Where the project's tools run now that {@code target} (or none) was found for {@code root}. */
    private void settle(Path root, ContainerExecTarget target) {
        String id = target == null ? null : target.id();
        if (target != null && !ops.execAllowed(id)) {
            if (declined.contains(id) || !consent.test(target)) {
                declined.add(id);
                id = null; // its tools run here, as before
            } else {
                ops.setExecAllowed(id, true);
            }
        }
        if (!java.util.Objects.equals(id, boundId)) {
            boolean wasBound = boundId != null;
            boundId = id;
            if (id != null) {
                host.setStatus(tr("status.container.bound", target.info().name()));
            } else if (wasBound) {
                host.setStatus(tr("status.container.unbound"));
            }
            ops.execChanged();
        }
    }

    private boolean askToRunInContainer(ContainerExecTarget target) {
        ButtonType allow = new ButtonType(tr("dialog.remoteExec.allow"), ButtonBar.ButtonData.OK_DONE);
        ButtonType notNow = new ButtonType(tr("dialog.remoteExec.notNow"), ButtonBar.ButtonData.CANCEL_CLOSE);
        Path root = ops.projectRoot();
        Alert alert = new Alert(
                Alert.AlertType.CONFIRMATION,
                tr(
                        "dialog.container.content",
                        target.info().name(),
                        root == null ? "" : root.getFileName().toString(),
                        String.join(" ", engine().command())),
                allow,
                notNow);
        alert.initOwner(host.window());
        alert.setTitle(tr("dialog.container.title"));
        alert.setHeaderText(tr("dialog.container.header", target.info().name()));
        return Dialogs.styled(alert).showAndWait().orElse(notNow) == allow;
    }

    /** Containers: Run This Project's Tools in a Container… — the user picks a running container. */
    void attach() {
        Path root = ops.projectRoot();
        if (root == null || !com.editora.vfs.Vfs.isLocal(root)) {
            host.setStatus(tr("status.container.noProject"));
            return;
        }
        ContainerEngine engine = engine();
        Thread.ofVirtual().name("container-list").start(() -> {
            List<String> running = engine.runningContainers();
            Platform.runLater(() -> {
                if (running.isEmpty()) {
                    host.setStatus(tr("status.container.noneRunning", String.join(" ", engine.command())));
                    return;
                }
                QuickOpen<String> picker = new QuickOpen<>(
                        tr("container.pickTitle"),
                        tr("container.pickPrompt"),
                        () -> running,
                        name -> name,
                        name -> "",
                        name -> attachTo(root, name));
                picker.setOverlayHost(host.overlayHost());
                picker.show(host.window());
            });
        });
    }

    /** Binds {@code root} to {@code container}: choosing it is the agreement to run the project's tools there. */
    void attachTo(Path root, String container) {
        if (container == null || container.isBlank()) {
            return;
        }
        Map<String, String> bindings = new LinkedHashMap<>(host.settings().getContainerBindings());
        bindings.put(root.toAbsolutePath().normalize().toString(), container.strip());
        host.settings().setContainerBindings(bindings);
        host.requestSave();
        ContainerBindings.Lookup lookup = lookups.apply(engine());
        Thread.ofVirtual().name("container-binding").start(() -> {
            ContainerExecTarget target = ContainerBindings.shared().bind(root, container, lookup);
            Platform.runLater(() -> {
                if (target == null) {
                    host.setError(tr("status.container.notMounted", container, root.toString()));
                    return;
                }
                ops.setExecAllowed(target.id(), true);
                declined.remove(target.id());
                checkedRoot = root;
                checkedAt = System.currentTimeMillis();
                settle(root, target);
            });
        });
    }

    /** Containers: Run This Project's Tools Here — forgets the container and the agreement to run in it. */
    void detach() {
        Path root = ops.projectRoot();
        if (root == null) {
            return;
        }
        Map<String, String> bindings = new LinkedHashMap<>(host.settings().getContainerBindings());
        if (bindings.remove(root.toAbsolutePath().normalize().toString()) != null) {
            host.settings().setContainerBindings(bindings);
            host.requestSave();
        }
        ContainerExecTarget target = ContainerBindings.shared().boundTo(root);
        if (target != null) {
            ops.setExecAllowed(target.id(), false);
            declined.add(target.id()); // not asked about its dev container again this session
            ContainerBindings.shared().unbind(root);
        }
        settle(root, null);
    }

    /** The id of the container this window's project's tools run in, or null (tests). */
    String boundId() {
        return boundId;
    }
}
