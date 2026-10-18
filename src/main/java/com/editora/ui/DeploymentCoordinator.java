package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.util.Duration;

import com.editora.config.ConfigManager;
import com.editora.config.Deployment;
import com.editora.config.RunConfiguration;
import com.editora.deploy.DeployManifest;
import com.editora.deploy.DeployPaths;
import com.editora.deploy.DeployPlan;
import com.editora.deploy.DeployRoot;
import com.editora.deploy.Deployer;
import com.editora.editor.EditorBuffer;
import com.editora.process.ExecTarget;
import com.editora.process.ExecTargets;

import static com.editora.i18n.Messages.tr;

/**
 * A window's deployments: running a configuration's program on a host, in a copy of the local project there
 * (see {@link Deployment} and {@code docs/subsystems/deployment.md}). Holds the SSH connection in this window
 * without opening the host's folder, brings the copy up to date ({@link Deployer}) before a run or on Deploy
 * Now, and answers the commands that look at the copy: compare a file with it, open a terminal in it. Upload on
 * save sends a saved file's project to every deployment that asks for it.
 */
final class DeploymentCoordinator {

    interface Host {
        ConfigManager config();

        /** This window's project root (local), or null. */
        Path projectRoot();

        /**
         * Holds a connection to {@code conn}'s host in this window and makes sure it may run commands; {@code
         * onReady} gets its {@code /}, or null (said why), on the FX thread. See
         * {@link RemoteCoordinator#holdForDeployment}.
         */
        void hold(com.editora.vfs.RemoteConnection conn, Consumer<Path> onReady);

        javafx.stage.Window window();

        void setStatus(String message);

        void setError(String message);

        EditorBuffer activeBuffer();

        /** The configuration selected in the run toolbar, or null. */
        RunConfiguration selectedRunConfiguration();

        /** Opens the Deployments editor on {@code name} (null: whatever it shows). */
        void editDeployments(String name);

        /** Opens a diff of {@code local} against {@code deployed} ({@code hostLabel} names the right side). */
        void compare(Path local, Path deployed, String hostLabel);

        /** Opens a Terminal tab in {@code dir} (a folder on a host). */
        void openTerminal(Path dir);

        /** Lets the user pick one of {@code deployments}. */
        void pick(List<Deployment> deployments, Consumer<Deployment> onPick);
    }

    /** Where a deploy's progress is shown: the Run console for a run, the status bar for upload on save. */
    interface Console {
        void started(String title);

        void line(String text);

        void ended(boolean ok, String message);
    }

    /**
     * A deployment ready to run on: its folder on the host, on this window's connection.
     *
     * @param summary one line on what the deploy did, for the program's console
     */
    record Deployed(Deployment deployment, DeployPaths paths, String summary) {
        Path remoteRoot() {
            return paths.remoteRoot();
        }

        Path localRoot() {
            return paths.localRoot();
        }
    }

    private final Host host;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private boolean busy;

    /** Deployments whose upload on save failed this session: not tried again on every save (a login each). */
    private final Set<String> saveUploadsOff = new HashSet<>();

    private final PauseTransition saveDebounce = new PauseTransition(Duration.millis(600));
    private final Set<String> pendingSaveUploads = new java.util.LinkedHashSet<>();

    /** Test seam: answers the decisions a deploy asks for (production: dialogs). */
    Deployer.Decisions decisions = new DialogDecisions();

    DeploymentCoordinator(Host host) {
        this.host = host;
        saveDebounce.setOnFinished(e -> flushSaveUploads());
    }

    /** Whether a deploy is running in this window (the toolbar's Stop applies to it). */
    boolean isBusy() {
        return busy;
    }

    /** Stops the running deploy after the file being sent; nothing on the host is half-written by it. */
    void cancel() {
        if (busy) {
            cancelled.set(true);
        }
    }

    /** This project's deployments. */
    List<Deployment> deployments() {
        return host.projectRoot() == null
                ? List.of()
                : List.copyOf(host.config().getWorkspaceState().getDeployments());
    }

    /**
     * Brings the deployment {@code name} up to date, showing progress on {@code console}, then hands the
     * deployed copy to {@code then} on the FX thread. Nothing happens after a failure but saying why.
     * {@code console} is told {@link Console#ended} exactly once, also when the deploy never started (with a
     * null message: the status bar says why).
     */
    void deployThen(String name, Console console, Consumer<Deployed> then) {
        Path localRoot = host.projectRoot();
        if (localRoot == null) {
            host.setStatus(tr("status.deploy.needsLocalProject"));
            console.ended(false, null);
            return;
        }
        Deployment d = host.config().getWorkspaceState().deployment(name);
        if (d == null) {
            host.setStatus(tr("status.deploy.unknown", name));
            console.ended(false, null);
            host.editDeployments(null);
            return;
        }
        if (!d.complete()) {
            host.setStatus(tr("status.deploy.incomplete", d.name()));
            console.ended(false, null);
            host.editDeployments(d.name());
            return;
        }
        if (busy) {
            host.setStatus(tr("status.deploy.busy"));
            console.ended(false, null);
            return;
        }
        busy = true;
        cancelled.set(false);
        host.hold(d.connection(), top -> {
            if (top == null) {
                busy = false; // the status bar says why (no login, not allowed, remote commands off)
                console.ended(false, null);
                return;
            }
            Path remoteRoot = top.getFileSystem().getPath(DeployRoot.normalize(d.remoteRoot()));
            DeployPaths paths = new DeployPaths(localRoot, remoteRoot);
            console.started(tr("deploy.console.started", d.displayTarget()));
            Thread.ofVirtual().name("deploy").start(() -> {
                Deployer.Outcome outcome = run(d, paths, top, console);
                Platform.runLater(() -> finish(d, paths, outcome, console, then));
            });
        });
    }

    private Deployer.Outcome run(Deployment d, DeployPaths paths, Path top, Console console) {
        ExecTarget target = ExecTargets.forPath(top);
        String home;
        try {
            Path h = target.homeDir();
            home = h == null ? null : target.toTargetPath(h);
        } catch (Exception e) {
            home = null;
        }
        Path manifest = manifestFile(paths.localRoot(), d);
        Deployer.Request request =
                new Deployer.Request(paths.localRoot(), d, paths.remoteRoot(), target, home, manifest, cancelled::get);
        Deployer.Listener listener = new Deployer.Listener() {
            @Override
            public void listed(int localFiles) {
                post(console, tr("deploy.console.listed", localFiles));
            }

            @Override
            public void planned(DeployPlan plan) {
                post(
                        console,
                        tr(
                                "deploy.console.planned",
                                plan.uploads().size(),
                                plan.deletes().size(),
                                plan.unchanged()));
            }

            @Override
            public void sending(int done, int total, long bytes) {
                post(console, tr("deploy.console.sending", done, total, humanBytes(bytes)));
            }
        };
        return Deployer.deploy(request, listener, decisions);
    }

    private void finish(
            Deployment d, DeployPaths paths, Deployer.Outcome outcome, Console console, Consumer<Deployed> then) {
        busy = false;
        switch (outcome.status()) {
            case OK -> {
                String summary =
                        tr("deploy.summary", d.displayTarget(), outcome.sent(), outcome.deleted(), outcome.unchanged());
                console.ended(true, summary);
                if (outcome.keptOnHost() > 0) {
                    host.setStatus(tr("status.deploy.keptOnHost", outcome.keptOnHost(), d.name()));
                } else {
                    host.setStatus(summary);
                }
                saveUploadsOff.remove(d.name());
                if (then != null) {
                    then.accept(new Deployed(d, paths, summary));
                }
            }
            case CANCELLED -> {
                console.ended(false, tr("status.deploy.cancelled", d.name()));
                host.setStatus(tr("status.deploy.cancelled", d.name()));
            }
            case REFUSED -> {
                String why = tr(
                        "deploy.root." + outcome.rootProblem().name().toLowerCase(java.util.Locale.ROOT),
                        d.remoteRoot());
                console.ended(false, why);
                host.setStatus(tr("status.deploy.refused", d.name(), why));
                host.editDeployments(d.name());
            }
            case FAILED -> {
                console.ended(false, outcome.error());
                host.setError(tr("status.deploy.failed", d.name(), outcome.error()));
            }
        }
    }

    /** Where this machine remembers what the last deploy of {@code d} from {@code project} left on the host. */
    Path manifestFile(Path project, Deployment d) {
        return DeployManifest.fileFor(
                host.config().getConfigDir(), project, d.connectionId() + ":" + DeployRoot.normalize(d.remoteRoot()));
    }

    private static void post(Console console, String line) {
        Platform.runLater(() -> console.line(line));
    }

    static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }

    /** A console on the status bar alone: Deploy Now and upload on save have no program to show. */
    private Console statusConsole() {
        return new Console() {
            @Override
            public void started(String title) {
                host.setStatus(title);
            }

            @Override
            public void line(String text) {
                host.setStatus(text);
            }

            @Override
            public void ended(boolean ok, String message) {
                // finish() puts the outcome on the status bar
            }
        };
    }

    /** The deployment a command means: the selected configuration's, the only one, or one the user picks. */
    private void withDeployment(Consumer<Deployment> action) {
        if (host.projectRoot() == null) {
            host.setStatus(tr("status.deploy.needsLocalProject"));
            return;
        }
        List<Deployment> all = deployments();
        if (all.isEmpty()) {
            host.setStatus(tr("status.deploy.none"));
            host.editDeployments(null);
            return;
        }
        RunConfiguration selected = host.selectedRunConfiguration();
        if (selected != null && selected.isDeployed()) {
            Deployment d = host.config().getWorkspaceState().deployment(selected.deployment());
            if (d != null) {
                action.accept(d);
                return;
            }
        }
        if (all.size() == 1) {
            action.accept(all.get(0));
            return;
        }
        host.pick(all, d -> {
            if (d != null) {
                action.accept(d);
            }
        });
    }

    /** {@code deploy.now}: brings a deployment's copy up to date without running anything. */
    void deployNow() {
        withDeployment(d -> deployThen(d.name(), statusConsole(), null));
    }

    /** {@code deploy.edit}: the Deployments editor. */
    void editDeployments() {
        editDeployments(null);
    }

    /** The Deployments editor on {@code name} (null: the first). */
    void editDeployments(String name) {
        if (host.projectRoot() == null) {
            host.setStatus(tr("status.deploy.needsLocalProject"));
            return;
        }
        host.editDeployments(name);
    }

    /** Deploys {@code name} now (the Deployments window's button). */
    void deployNamed(String name) {
        deployThen(name, statusConsole(), null);
    }

    /** {@code deploy.compare}: the active file beside its copy on the host. */
    void compareWithDeployed() {
        EditorBuffer b = host.activeBuffer();
        Path root = host.projectRoot();
        if (b == null || b.getPath() == null || root == null || DeployPaths.relative(root, b.getPath()) == null) {
            host.setStatus(tr("status.deploy.compareNeedsProjectFile"));
            return;
        }
        Path local = b.getPath();
        withDeployment(d -> connected(d, paths -> {
            Path copy = paths.toHost(local);
            if (copy != null) {
                host.compare(local, copy, d.displayTarget());
            }
        }));
    }

    /** {@code deploy.terminal}: a Terminal tab in the deployment's folder on the host. */
    void openTerminal() {
        withDeployment(d -> connected(d, paths -> host.openTerminal(paths.remoteRoot())));
    }

    /** The paths of {@code d}'s copy once its host is connected (no deploy). */
    private void connected(Deployment d, Consumer<DeployPaths> then) {
        if (!d.complete()) {
            host.setStatus(tr("status.deploy.incomplete", d.name()));
            host.editDeployments(d.name());
            return;
        }
        Path localRoot = host.projectRoot();
        host.hold(d.connection(), top -> {
            if (top != null && localRoot != null) {
                then.accept(
                        new DeployPaths(localRoot, top.getFileSystem().getPath(DeployRoot.normalize(d.remoteRoot()))));
            }
        });
    }

    /** A file was saved in this window: sends it to every deployment that uploads on save (debounced). */
    void fileSaved(Path file) {
        Path root = host.projectRoot();
        if (file == null || root == null || DeployPaths.relative(root, file) == null) {
            return;
        }
        for (Deployment d : deployments()) {
            if (d.uploadOnSave() && d.complete() && !saveUploadsOff.contains(d.name())) {
                pendingSaveUploads.add(d.name());
            }
        }
        if (!pendingSaveUploads.isEmpty()) {
            saveDebounce.playFromStart();
        }
    }

    private void flushSaveUploads() {
        if (pendingSaveUploads.isEmpty()) {
            return;
        }
        if (busy) {
            saveDebounce.playFromStart(); // after the deploy that is running now
            return;
        }
        String name = pendingSaveUploads.iterator().next();
        pendingSaveUploads.remove(name);
        Console status = statusConsole();
        deployThen(
                name,
                new Console() {
                    @Override
                    public void started(String title) {
                        status.started(title);
                    }

                    @Override
                    public void line(String text) {
                        status.line(text);
                    }

                    @Override
                    public void ended(boolean ok, String message) {
                        if (!ok) {
                            saveUploadsOff.add(name); // until a Deploy Now or a run succeeds: not a login on every save
                        }
                    }
                },
                d -> {
                    if (!pendingSaveUploads.isEmpty()) {
                        saveDebounce.playFromStart();
                    }
                });
    }

    /** The decisions as dialogs, asked from the deploy's thread and answered on the FX thread. */
    private final class DialogDecisions implements Deployer.Decisions {
        @Override
        public Conflicts conflicts(List<String> files) {
            return onFx(() -> {
                ButtonType overwrite = new ButtonType(tr("dialog.deploy.overwrite"), ButtonBar.ButtonData.OK_DONE);
                ButtonType keep = new ButtonType(tr("dialog.deploy.keepHost"), ButtonBar.ButtonData.OTHER);
                ButtonType cancel = new ButtonType(tr("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
                Alert alert = new Alert(
                        Alert.AlertType.WARNING,
                        tr("dialog.deploy.conflicts.content", list(files)),
                        overwrite,
                        keep,
                        cancel);
                alert.initOwner(host.window());
                alert.setTitle(tr("dialog.deploy.conflicts.title"));
                alert.setHeaderText(tr("dialog.deploy.conflicts.header", files.size()));
                ButtonType answer = Dialogs.styled(alert).showAndWait().orElse(cancel);
                return answer == overwrite
                        ? Conflicts.OVERWRITE
                        : answer == keep ? Conflicts.KEEP_HOST : Conflicts.CANCEL;
            });
        }

        @Override
        public boolean overwriteExisting(List<String> files) {
            return onFx(() -> {
                ButtonType deploy = new ButtonType(tr("dialog.deploy.replace"), ButtonBar.ButtonData.OK_DONE);
                ButtonType cancel = new ButtonType(tr("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
                Alert alert = new Alert(
                        Alert.AlertType.CONFIRMATION,
                        tr("dialog.deploy.existing.content", list(files)),
                        deploy,
                        cancel);
                alert.initOwner(host.window());
                alert.setTitle(tr("dialog.deploy.existing.title"));
                alert.setHeaderText(tr("dialog.deploy.existing.header", files.size()));
                return Dialogs.styled(alert).showAndWait().orElse(cancel) == deploy;
            });
        }
    }

    /** Up to a dozen names, one per line, and how many more. */
    static String list(List<String> files) {
        List<String> shown = new ArrayList<>(files.subList(0, Math.min(12, files.size())));
        if (files.size() > shown.size()) {
            shown.add(tr("dialog.deploy.more", files.size() - shown.size()));
        }
        return String.join("\n", shown);
    }

    /** {@code question}'s answer, asked on the FX thread from another one. */
    private static <T> T onFx(Supplier<T> question) {
        if (Platform.isFxApplicationThread()) {
            return question.get();
        }
        CompletableFuture<T> answer = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                answer.complete(question.get());
            } catch (RuntimeException e) {
                answer.completeExceptionally(e);
            }
        });
        return answer.join();
    }
}
