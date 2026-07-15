package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.scene.Node;

import com.editora.build.BuildActionsProvider;
import com.editora.build.BuildService;
import com.editora.build.BuildTool;
import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.lsp.RootResolver;
import com.editora.run.ProgramArgs;
import com.editora.vfs.Vfs;

import static com.editora.i18n.Messages.tr;

/**
 * One build tool's feature, running on the generic {@code com.editora.build} framework: it detects the active
 * project's marker file for a {@link BuildTool}, parses it into a {@link BuildActionsProvider}, renders it as
 * an IntelliJ-style {@link BuildActionsTree} tool window (plus a searchable {@link BuildActionsPopup} for the
 * command palette), and streams tasks into its own tab of the <b>shared</b> {@link BuildOutputPanel} "Build
 * Output" window (owned by {@code MainController}, one tabbed window for all tools) via {@link BuildService}.
 * The tasks-tree stripe appears when the marker file is detected; the shared window's stripe appears when
 * <em>any</em> tool is detected. Everything tool-specific comes from the {@link BuildTool} (markers, executable
 * strategy, provider parse, output style, Settings accessors); the strings are generic {@code status.build.*}
 * / {@code buildpopup.*} keys parameterized by the tool's display name, so {@code MainController} wires one
 * instance per tool with no per-tool code. Generalized from the former {@code MavenCoordinator}.
 */
final class BuildCoordinator {

    /** Window hooks beyond {@link CoordinatorHost} that this feature needs. */
    interface Ops {
        /** The active window's project root, or {@code null} when no project is open. */
        Path projectRoot();

        /** Opens (and focuses) this tool's tasks-tree tool window. */
        void openTasks();

        /** Opens (and focuses) the shared Build Output console tool window. */
        void openConsole();

        /** Shows/hides this tool's tasks-tree stripe (and re-derives the shared console's) to match detection. */
        void setToolWindowsAvailable(boolean available);
    }

    private final BuildTool tool;
    private final CoordinatorHost host;
    private final Ops ops;
    private final BuildService service = new BuildService();
    private final BuildOutputPanel panel; // the shared tabbed "Build Output" window (this tool gets its own tab)
    private final BuildActionsTree tree;
    private final BuildActionsPopup popup;

    /** The nearest detected marker-file directory for the current context, or {@code null}. */
    private Path markerRoot;
    /** The provider built from the parsed marker file, or {@code null} (absent/malformed). */
    private BuildActionsProvider provider;
    /** A short label for the detected project (Maven artifactId / npm name), or {@code null}. */
    private String detectedLabel;

    private int detectGeneration;

    /** The most recent launch, for {@code <tool>.rerunLast}. */
    private Path lastRoot;

    private List<String> lastTaskArgs;
    private List<String> lastToggleArgs = List.of();

    BuildCoordinator(BuildTool tool, CoordinatorHost host, Ops ops, BuildOutputPanel sharedConsole) {
        this.tool = tool;
        this.host = host;
        this.ops = ops;
        this.panel = sharedConsole; // the shared tabbed Build Output window (owned by MainController)
        this.tree = new BuildActionsTree();
        this.popup = new BuildActionsPopup(new BuildActionsPopup.Labels(
                tool.displayName(), tr("buildpopup.searchPrompt"), tr("buildpopup.hint"), tr("buildpopup.runCustom")));
        popup.setOnRunCustom(this::runCustom);
        popup.setOnRun(this::runTask);
        tree.setOnRun(this::runTask);
        tree.setOnRefresh(this::refreshMarker);
        tree.setOnRunCustom(this::runCustom);
        tree.setOnStop(this::stop);
        // A DSL-based tool (Gradle) whose tasks can't be statically parsed gets a "Load all tasks…" action.
        tool.taskLoadLabel().ifPresent(key -> {
            popup.setSecondaryAction(tr(key), this::loadAllTasks);
            tree.setSecondaryAction(tr(key), this::loadAllTasks);
        });
    }

    BuildTool tool() {
        return tool;
    }

    /** The IntelliJ-style tasks-tree tool window content. */
    BuildActionsTree tasksPanel() {
        return tree;
    }

    /** This tool's stripe/tool-window icon (also the toolbar button glyph). */
    Supplier<Node> iconSupplier() {
        return iconFor(tool);
    }

    void setOverlayHost(OverlayHost overlayHost) {
        popup.setOverlayHost(overlayHost);
    }

    /** Whether this tool's integration is enabled in Settings (default on — inert until its marker file is
     *  actually detected). Off in Simple UI mode. */
    boolean isEnabled() {
        return tool.enabledIn(host.settings()) && !host.simpleModeActive();
    }

    /** Whether a task can actually run right now: enabled, a marker file was found, and it parsed. */
    private boolean isAvailable() {
        return isEnabled() && markerRoot != null && provider != null;
    }

    /** Whether a marker file is currently detected for the active context (the Settings found/not-found row). */
    boolean isDetected() {
        return markerRoot != null;
    }

    /** A short label for the detected project (artifactId/package name), or {@code null} when absent/malformed. */
    String detectedLabel() {
        return detectedLabel;
    }

    /** The path whose project drives this tool's UI: the active local file, else the active project root. */
    Path contextPath() {
        EditorBuffer b = host.activeBuffer();
        Path file = b == null ? null : b.getPath();
        if (file != null && host.isLocalBuffer(b)) {
            return file;
        }
        return ops.projectRoot();
    }

    /** Re-detects the nearest marker file for the current context and re-parses it, off the FX thread
     *  (generation-guarded). Runs at startup, on tab switch, on window focus-regain, on save, and on every
     *  settings apply — cheap to over-call. */
    void refresh() {
        if (!isEnabled()) {
            applyDetected(null, null, null);
            return;
        }
        Path context = contextPath();
        if (context == null || !Vfs.isLocal(context)) {
            applyDetected(null, null, null);
            return;
        }
        int gen = ++detectGeneration;
        Thread t = new Thread(
                () -> {
                    Path root = RootResolver.findMarkerRoot(context, tool.markers());
                    BuildActionsProvider parsed = null;
                    String label = null;
                    if (root != null) {
                        try {
                            BuildTool.Detected detected = tool.parse(root);
                            parsed = detected.provider();
                            label = detected.label();
                        } catch (Exception e) {
                            parsed = null; // marker found but malformed — applyDetected reports it distinctly
                        }
                    }
                    Path finalRoot = root;
                    BuildActionsProvider finalProvider = parsed;
                    String finalLabel = label;
                    Platform.runLater(() -> {
                        if (gen == detectGeneration) {
                            applyDetected(finalRoot, finalProvider, finalLabel);
                        }
                    });
                },
                tool.id() + "-detect");
        t.setDaemon(true);
        t.start();
    }

    /** Re-derives the tool windows' stripe visibility from the last detection without a fresh re-detect. */
    void reapplyVisibility() {
        ops.setToolWindowsAvailable(isEnabled() && markerRoot != null);
    }

    private void applyDetected(Path root, BuildActionsProvider parsed, String label) {
        this.markerRoot = root;
        this.provider = parsed;
        this.detectedLabel = label;
        tree.setProvider(parsed); // rebuild the tasks tree (null = placeholder when absent/malformed)
        ops.setToolWindowsAvailable(isEnabled() && root != null);
    }

    /** Opens the searchable actions popup, centered (the {@code <tool>.showActions} palette command). */
    void showActionsPopup() {
        if (!isEnabled()) {
            host.setStatus(tr("status.build.disabled", tool.displayName()));
            return;
        }
        if (markerRoot == null) {
            host.setStatus(tr("status.build.notDetected", tool.displayName()));
            return;
        }
        if (provider == null) {
            host.setStatus(tr("status.build.malformed", tool.displayName()));
            return;
        }
        popup.show(host.window(), provider);
    }

    /** Runs a selected task with its args + the active toggles' args (the popup's callback). */
    void runTask(List<String> taskArgs, List<String> toggleArgs) {
        if (!isAvailable()) {
            host.setStatus(tr(isEnabled() ? "status.build.notDetected" : "status.build.disabled", tool.displayName()));
            return;
        }
        if (service.isRunning()) {
            host.setStatus(tr("status.build.busy", tool.displayName()));
            return;
        }
        List<String> argv = new ArrayList<>(executable(markerRoot));
        argv.addAll(taskArgs);
        argv.addAll(toggleArgs);
        launch(markerRoot, argv, taskArgs, toggleArgs);
    }

    /** Prompts for a freeform task string and runs it verbatim (no toggle args — type the modifier flag
     *  directly if one is needed). */
    void runCustom() {
        if (!isAvailable()) {
            host.setStatus(tr(isEnabled() ? "status.build.notDetected" : "status.build.disabled", tool.displayName()));
            return;
        }
        if (service.isRunning()) {
            host.setStatus(tr("status.build.busy", tool.displayName()));
            return;
        }
        host.promptText(
                tr("dialog.buildCustom.title", tool.displayName()), tr("dialog.buildCustom.label"), "", text -> {
                    if (text == null || text.isBlank()) {
                        return;
                    }
                    List<String> tokens = ProgramArgs.tokenize(text.strip());
                    if (tokens.isEmpty()) {
                        return;
                    }
                    List<String> argv = new ArrayList<>(executable(markerRoot));
                    argv.addAll(tokens);
                    launch(markerRoot, argv, tokens, List.of());
                });
    }

    /** Re-runs the most recent invocation (same root + task + toggle args). */
    void rerunLast() {
        if (lastRoot == null || lastTaskArgs == null) {
            host.setStatus(tr("status.build.noRerun", tool.displayName()));
            return;
        }
        if (service.isRunning()) {
            host.setStatus(tr("status.build.busy", tool.displayName()));
            return;
        }
        List<String> argv = new ArrayList<>(executable(lastRoot));
        argv.addAll(lastTaskArgs);
        argv.addAll(lastToggleArgs);
        launch(lastRoot, argv, lastTaskArgs, lastToggleArgs);
    }

    /** The launch argv prefix: the project wrapper when present, else the Settings override, else the tool's
     *  default command. */
    private List<String> executable(Path root) {
        return tool.executable(root, isWindows(), tool.commandIn(host.settings()));
    }

    private void launch(Path root, List<String> argv, List<String> taskArgs, List<String> toggleArgs) {
        lastRoot = root;
        lastTaskArgs = taskArgs;
        lastToggleArgs = toggleArgs;
        ops.openConsole();
        String label = String.join(" ", taskArgs);
        panel.started(this, tool.displayName(), label, tool.outputStyle(), this::stop);
        tree.setRunning(true);
        host.setStatus(tr("status.build.started", tool.displayName(), label));
        service.run(root, argv, new BuildService.Listener() {
            @Override
            public void onStart(String commandLine) {
                panel.started(
                        BuildCoordinator.this,
                        tool.displayName(),
                        commandLine,
                        tool.outputStyle(),
                        BuildCoordinator.this::stop);
            }

            @Override
            public void onOutput(String line, boolean stderr) {
                panel.appendOutput(BuildCoordinator.this, line, stderr);
            }

            @Override
            public void onExit(int code) {
                panel.finished(BuildCoordinator.this, code);
                tree.setRunning(false);
                host.setStatus(
                        code == 0
                                ? tr("status.build.ok", tool.displayName())
                                : tr("status.build.exit", tool.displayName(), code));
            }

            @Override
            public void onError(String message) {
                panel.failed(BuildCoordinator.this, message);
                tree.setRunning(false);
                host.setStatus(tr("status.build.failed", tool.displayName(), message));
            }
        });
    }

    /** Stops the running process (Console Stop button / {@code <tool>.stop}). */
    void stop() {
        if (service.isRunning()) {
            service.stop();
            host.setStatus(tr("status.build.stopped", tool.displayName()));
        }
    }

    /** Force re-parses the marker file right now (e.g. after an external edit) — {@code <tool>.refresh}. */
    void refreshMarker() {
        refresh();
        host.setStatus(tr("status.build.refreshed", tool.displayName()));
    }

    /** Enumerates the tool's full task list on a short-lived process (Gradle's "Load all tasks…"), off the FX
     *  thread, then repopulates the still-open popup in place. Inert for tools without {@code taskLoadLabel}. */
    private void loadAllTasks() {
        if (markerRoot == null) {
            return;
        }
        Path root = markerRoot;
        String override = tool.commandIn(host.settings());
        host.setStatus(tr("status.build.loadingTasks", tool.displayName()));
        Thread t = new Thread(
                () -> {
                    List<String> tasks;
                    try {
                        tasks = tool.loadTasks(root, isWindows(), override);
                    } catch (Exception e) {
                        tasks = List.of();
                    }
                    List<String> finalTasks = tasks;
                    Platform.runLater(() -> {
                        if (provider != null) {
                            provider.addLoadedTasks(finalTasks); // mutate the shared provider once…
                            tree.refreshFromProvider(); // …then re-render both views over it
                            popup.rerender();
                        }
                        host.setStatus(tr("status.build.loadedTasks", tool.displayName(), finalTasks.size()));
                    });
                },
                tool.id() + "-tasks");
        t.setDaemon(true);
        t.start();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    void registerCommands(CommandRegistry registry) {
        String id = tool.id();
        registry.register(Command.of("tool." + id, ops::openTasks));
        registry.register(Command.of(id + ".showActions", this::showActionsPopup));
        registry.register(Command.of(id + ".runCustom", this::runCustom));
        registry.register(Command.of(id + ".stop", this::stop));
        registry.register(Command.of(id + ".rerunLast", this::rerunLast));
        registry.register(Command.of(id + ".refresh", this::refreshMarker));
    }

    void shutdown() {
        service.stop();
    }

    /** The per-tool toolbar/tool-window icon. A single UI switch (icons can't live in the pure {@code build}
     *  package, which {@code ui} depends on — not the reverse). */
    static Supplier<Node> iconFor(BuildTool tool) {
        return switch (tool) {
            case MAVEN -> Icons::maven;
            case NPM -> Icons::npm;
            case CARGO -> Icons::cargo;
            case GO -> Icons::go;
            case GRADLE -> Icons::gradle;
        };
    }
}
