package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.scene.Node;

import com.editora.build.BuildActionsProvider;
import com.editora.build.BuildService;
import com.editora.build.BuildTool;
import com.editora.build.BuildTrust;
import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.lsp.RootResolver;
import com.editora.run.ProgramArgs;
import com.editora.test.TestRunRecognizer;

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

        /** Opens (and focuses) the shared Output console tool window. */
        void openConsole();

        /** Shows/hides this tool's tasks-tree stripe (and re-derives the shared console's) to match detection. */
        void setToolWindowsAvailable(boolean available);

        /** Whether {@code root} is a trusted workspace (it, or an ancestor, was granted trust). */
        boolean isTrusted(Path root);

        /**
         * Asks the user whether to trust {@code root}, whose build would run what {@code summary} lists (the
         * wrapper if any, the pom's plugins, {@code .mvn/} files, a project.json override). Blocking (a
         * security decision must not be dismissible by clicking past it). Returns true to grant trust.
         */
        boolean confirmTrust(Path root, BuildTrust.Summary summary);

        /** Records {@code root} as trusted and persists it. */
        void trust(Path root);

        /** Drops {@code root}'s own trust entry (the tasks window's Revoke); inherited trust stays. */
        default void revokeTrust(Path root) {}

        /** Opens {@code file} in the editor (the broken-pom notice's link). */
        default void openFile(Path file) {}

        /** The remembered "Run custom…" strings of {@code toolId}, newest first (this project's). */
        default List<String> customHistory(String toolId) {
            return List.of();
        }

        /** Remembers {@code text} as the newest custom string of {@code toolId}. */
        default void rememberCustom(String toolId, String text) {}
    }

    private final BuildTool tool;
    private final CoordinatorHost host;
    private final Ops ops;
    private final BuildService service = new BuildService();
    private final BuildOutputPanel panel; // the shared tabbed "Output" window (this tool gets its own tab)
    /** The tasks-tree tool window content, built on its first open; see {@link #tasksPanel()}. */
    private BuildActionsTree tree;

    private final BuildActionsPopup popup;

    /** The nearest detected marker-file directory for the current context, or {@code null}. */
    private Path markerRoot;
    /** Gradle's on-demand task list + the root it was enumerated for, so a re-detect can restore it. */
    private Path loadedTasksRoot;

    private List<String> loadedTasks = List.of();
    /** The provider built from the parsed marker file, or {@code null} (absent/malformed). */
    private BuildActionsProvider provider;
    /** A short label for the detected project (Maven artifactId / npm name), or {@code null}. */
    private String detectedLabel;

    private int detectGeneration;

    /**
     * The stamp of the detection currently applied, or {@code null} when the next refresh must parse (nothing
     * applied yet, the tool was off, an explicit refresh, or a build just ran — a run can download the plugins
     * a Maven goal list is read from). FX thread only; a worker compares against the copy it was handed.
     */
    private BuildDetection.Stamp appliedStamp;

    /** The most recent launch, for {@code <tool>.rerunLast}. */
    private Path lastRoot;

    /** The root the open popup acts on when it is not the active tab's ({@link #showActionsAt}); null = that one. */
    private Path popupRoot;

    private List<String> lastTaskArgs;
    private List<String> lastToggleArgs = List.of();

    /**
     * The active toggle ids (profiles, the reactor switch) per detected root — one set, read and written by
     * both the tasks tree and the popup. Each view used to keep its own, so a profile ticked in the tool
     * window was not the one a run from the popup used, and the popup forgot its set on every open (review
     * B8). Kept per root so switching to another module and back keeps the choice; bounded.
     */
    private final Map<Path, Set<String>> togglesByRoot = new LinkedHashMap<>();

    private static final int MAX_TOGGLE_ROOTS = 16;

    /** The last string typed into "Run custom…" this session (prefills the next prompt). */
    private String lastCustomText;

    /** The build roots found below the open project (the tree's "Projects" section) and the project they
     *  were scanned for. */
    private List<Path> projectRoots = List.of();

    private Path scannedProject;

    /**
     * A root picked in the tree's "Projects" section: it drives detection instead of the active file until
     * the active file changes ({@link #pinnedContext} is what it was when the pick was made).
     */
    private Path pinnedRoot;

    private Path pinnedContext;

    /** When set, a recognized test run is forwarded here (the Test Results window); null = feature absent. */
    private TestRunHook testRunHook;

    /** A one-shot per-line output observer for the next run (the "Debug via build tool" flow watches the
     *  streamed output for the JDWP banner). Cleared when the run ends. */
    private java.util.function.BiConsumer<String, Boolean> outputWatcher;

    BuildCoordinator(BuildTool tool, CoordinatorHost host, Ops ops, BuildOutputPanel sharedConsole) {
        this.tool = tool;
        this.host = host;
        this.ops = ops;
        this.panel = sharedConsole; // the shared tabbed Output window (owned by MainController)
        this.popup = new BuildActionsPopup(new BuildActionsPopup.Labels(
                tool.displayName(), tr("buildpopup.searchPrompt"), tr("buildpopup.runCustom")));
        // The popup runs where it was opened for: the active tab's module, or the pom a context menu named.
        popup.setOnRunCustom(() -> runCustomAt(popupRoot));
        popup.setOnRun((taskArgs, toggleArgs) -> runTaskAt(popupRoot, taskArgs, toggleArgs));
        // A DSL-based tool (Gradle) whose tasks can't be statically parsed gets a "Load all tasks…" action.
        tool.taskLoadLabel().ifPresent(key -> popup.setSecondaryAction(tr(key), this::loadAllTasks));
    }

    BuildTool tool() {
        return tool;
    }

    /**
     * The argv prefix a launch may start this tool with from {@code root} — the tasks tree's own choice
     * (wrapper, override, PATH command) after the tasks tree's own trust gate — or null when the user declined
     * to trust the project's wrapper, in which case the gate has said so and nothing may run. For Run and
     * Debug's build before a launch and the Maven classpath fallback (reviews D2, D3).
     */
    List<String> trustedExecutable(Path root) {
        return allowRun(root) ? executable(root) : null;
    }

    /**
     * The IntelliJ-style tasks-tree tool window content, built on first use and brought up to the state
     * detection and a running build have reached meanwhile. Every window used to build one per build tool at
     * open — five trees, most for tools the project does not use and whose stripe is never even shown.
     */
    BuildActionsTree tasksPanel() {
        if (tree == null) {
            tree = new BuildActionsTree();
            tree.setOnRun(this::runTask);
            tree.setOnRefresh(this::refreshMarker);
            tree.setOnRunCustom(this::runCustom);
            tree.setOnStop(this::stop);
            tree.setOnSelectProject(this::selectRoot);
            tree.setOnRevokeTrust(this::revokeTrust);
            tool.taskLoadLabel().ifPresent(key -> tree.setSecondaryAction(tr(key), this::loadAllTasks));
            showDetectedInTree();
            tree.setRunning(service.isRunning());
        }
        return tree;
    }

    /** Pushes the current detection into the tree, when there is one to push it to. */
    private void showDetectedInTree() {
        if (tree == null) {
            return;
        }
        tree.setActiveToggles(togglesFor(markerRoot));
        // A marker that exists but doesn't parse is not the same as no marker at all: the stripe stays (the
        // root is real), so the tree must say the build file is broken rather than "no build tool detected".
        tree.setProvider(
                provider,
                markerRoot != null && provider == null ? tr("status.build.malformed", tool.displayName()) : null);
        // The reason, with a way to the file — it used to appear only in the status bar after Show Actions.
        if (markerRoot != null && parseError != null && !parseError.isBlank()) {
            Path file = markerRoot.resolve(tool.markers().get(0));
            tree.setNotice(
                    tr("status.build.malformedAt", tool.displayName(), parseError),
                    tr("buildtree.openBuildFile", tool.markers().get(0)),
                    () -> ops.openFile(file));
        } else {
            tree.setNotice(null, null, null);
        }
        tree.setProjects(projectRoots);
        tree.setHeader(markerRoot, ops.projectRoot(), trustState(markerRoot));
    }

    /** The toggle set of {@code root} (created on first use); a throwaway set for no root. */
    private Set<String> togglesFor(Path root) {
        if (root == null) {
            return new LinkedHashSet<>();
        }
        Path key = root.toAbsolutePath().normalize();
        Set<String> set = togglesByRoot.get(key);
        if (set == null) {
            if (togglesByRoot.size() >= MAX_TOGGLE_ROOTS) {
                togglesByRoot.remove(togglesByRoot.keySet().iterator().next());
            }
            set = new LinkedHashSet<>();
            togglesByRoot.put(key, set);
        }
        return set;
    }

    /**
     * The extra argv the active toggles of the detected root contribute right now — Maven's {@code -P}
     * profiles and the reactor switch — for Run and Debug's build-before-launch and the classpath fallback,
     * so a program is compiled the way the tasks tree builds it. Empty with nothing detected.
     */
    List<String> activeToggleArgs() {
        return provider == null || markerRoot == null ? List.of() : provider.toggleArgs(togglesFor(markerRoot));
    }

    /** The folder whose trust a build from {@code root} needs: the wrapper's, else the project (or root). */
    private Path trustRoot(Path root) {
        Path wrapper = tool.repoWrapper(root, wrapperBoundary(root), isWindows());
        return wrapper != null ? wrapper.getParent() : wrapperBoundary(root);
    }

    private BuildActionsTree.TrustState trustState(Path root) {
        if (root == null || !BuildTrust.gates(tool)) {
            return BuildActionsTree.TrustState.NOT_GATED;
        }
        return ops.isTrusted(trustRoot(root))
                ? BuildActionsTree.TrustState.TRUSTED
                : BuildActionsTree.TrustState.UNTRUSTED;
    }

    /** The tree header's Revoke: drops the detected root's trust and restates the header. */
    private void revokeTrust() {
        if (markerRoot == null) {
            return;
        }
        ops.revokeTrust(trustRoot(markerRoot));
        if (tree != null) {
            tree.setHeader(markerRoot, ops.projectRoot(), trustState(markerRoot));
        }
    }

    /** A row of the tree's "Projects" section was chosen: detect that root instead of the active file's. */
    void selectRoot(Path root) {
        if (root == null) {
            return;
        }
        pinnedRoot = root;
        pinnedContext = contextPath();
        appliedStamp = null;
        refresh();
    }

    /**
     * What a build from the detected root runs as, for Settings' project row and Doctor: the wrapper and its
     * trust state ({@code ./mvnw (trusted)}), the command override, or the tool on PATH. Null when nothing
     * is detected.
     */
    String executableSummary() {
        if (markerRoot == null) {
            return null;
        }
        Path wrapper = tool.repoWrapper(markerRoot, wrapperBoundary(markerRoot), isWindows());
        if (wrapper != null) {
            String name = "./" + wrapper.getFileName();
            return ops.isTrusted(wrapper.getParent())
                    ? tr("settings.buildTools.exec.wrapperTrusted", name)
                    : tr("settings.buildTools.exec.wrapperUntrusted", name);
        }
        String override = host.projectScope().buildCommand(tool, host.settings());
        if (override != null && !override.isBlank()) {
            return tr("settings.buildTools.exec.override", override.strip());
        }
        List<String> argv = executable(markerRoot);
        return tr("settings.buildTools.exec.onPath", argv.isEmpty() ? tool.commandExample() : argv.get(0));
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

    /** Why the tool is off: Simple UI mode (the Settings toggle may well be on), else the Settings toggle. */
    private String disabledMessage() {
        return host.simpleModeActive()
                ? tr("status.build.simpleMode", tool.displayName())
                : tr("status.build.disabled", tool.displayName());
    }

    /** Whether a marker file is currently detected for the active context (the Settings found/not-found row). */
    boolean isDetected() {
        return markerRoot != null;
    }

    /** Where the detected marker file lives, or null when none was found. */
    Path markerRoot() {
        return markerRoot;
    }

    /** A short label for the detected project (artifactId/package name), or {@code null} when absent/malformed. */
    String detectedLabel() {
        return detectedLabel;
    }

    /** Why the build file could not be parsed (with its line and column), or null. */
    private String parseError;

    /** For npm: the package manager the detected project uses and what said so; null otherwise. */
    private com.editora.build.NpmPackageManager.Detection detectedManager;

    /** For npm: {@code pnpm (pnpm-lock.yaml)} for the detected project, or null (Settings shows it). */
    com.editora.build.NpmPackageManager.Detection detectedManager() {
        return markerRoot == null ? null : detectedManager;
    }

    /** Whether a launched build is still alive — the same state {@link #runTask} / {@link #rerunLast} gate on. */
    boolean isRunning() {
        return service.isRunning();
    }

    /** The path whose project drives this tool's UI: the active local file, else the active project root. */
    Path contextPath() {
        EditorBuffer b = host.activeBuffer();
        Path file = b == null ? null : b.getPath();
        if (file != null && RemoteExec.runsFor(file)) {
            return file;
        }
        return ops.projectRoot();
    }

    /** Re-detects the nearest marker file for the current context and re-parses it, off the FX thread
     *  (generation-guarded). Runs at startup, on tab switch, on window focus-regain, on save, and on every
     *  settings apply — cheap to over-call. */
    void refresh() {
        // Bumped before the guards below, not just for the async path: a detect started by an earlier call is
        // still running, and without a new generation its (now stale) result would land on top of the null
        // these early returns apply — leaving the stripe up for a tool that was just switched off.
        int gen = ++detectGeneration;
        if (!isEnabled()) {
            appliedStamp = null;
            applyDetected(null, null, null);
            return;
        }
        Path context = contextPath();
        if (context != null && com.editora.run.NodeRuntimes.inNodeModules(context)) {
            // A dependency's file (Go to Definition into node_modules) still belongs to the project that holds
            // node_modules: its own package.json must not take over the tool window, or install/scripts run
            // inside node_modules/<pkg>.
            context = com.editora.run.NodeRuntimes.outsideNodeModules(context);
        }
        if (context == null || !RemoteExec.runsFor(context)) {
            appliedStamp = null;
            applyDetected(null, null, null);
            return;
        }
        BuildDetection.Stamp known = appliedStamp;
        Path project = ops.projectRoot();
        // A root picked in the Projects section holds until the active file changes; then the file leads.
        if (pinnedRoot != null && !java.util.Objects.equals(pinnedContext, context)) {
            pinnedRoot = null;
        }
        Path searchFrom = pinnedRoot != null ? pinnedRoot : context;
        boolean scanProjects = project != null && !project.equals(scannedProject);
        BuildDetection.execute(() -> {
            // filesOnly: every build marker is a file (pom.xml/package.json/go.mod/…), so a directory
            // merely named like one must not root the tool there (it would then fail to parse) — #451.
            // The walk stops at the open project: a project opened inside a module must not build the pom
            // above the folder the user opened (review B27).
            Path root = RootResolver.findMarkerRoot(searchFrom, tool.markers(), true, project);
            List<Path> found = scanProjects
                    ? RootResolver.findMarkerRootsBelow(project, tool.markers(), PROJECT_SCAN_DEPTH, PROJECT_SCAN_DIRS)
                    : null;
            if (found != null) {
                Platform.runLater(() -> {
                    if (gen == detectGeneration) {
                        scannedProject = project;
                        projectRoots = found.size() > 1 || root == null ? found : List.of();
                        if (tree != null) {
                            tree.setProjects(projectRoots);
                        }
                    }
                });
            }
            BuildDetection.Stamp stamp = BuildDetection.Stamp.of(root, tool.markers());
            if (stamp.equals(known)) {
                // Same build file, untouched: what is applied is what a parse would produce, so keep the
                // provider and the tree as they are. The stripe/toolbar/gutter gating is still re-derived —
                // it also reads state this stamp does not cover (the window's project root, for one).
                Platform.runLater(() -> {
                    if (gen == detectGeneration) {
                        reapplyVisibility();
                    }
                });
                return;
            }
            BuildActionsProvider parsed = null;
            String label = null;
            String error = null;
            com.editora.build.NpmPackageManager.Detection manager = null;
            if (root != null) {
                BuildDetection.countParse();
                try {
                    BuildTool.Detected detected = tool.parse(root, project);
                    parsed = detected.provider();
                    label = detected.label();
                } catch (Exception e) {
                    // Marker found but malformed — reported distinctly (the error under the tree, with a
                    // link to the file); Maven still offers its lifecycle, so `validate` can say what's wrong.
                    parsed = tool.brokenFileProvider();
                    error = e.getMessage();
                }
                if (tool == BuildTool.NPM) {
                    try {
                        manager = BuildTool.npmManager(root, project);
                    } catch (RuntimeException e) {
                        manager = null;
                    }
                }
            }
            BuildActionsProvider finalProvider = parsed;
            String finalLabel = label;
            String finalError = error;
            com.editora.build.NpmPackageManager.Detection finalManager = manager;
            Platform.runLater(() -> {
                if (gen == detectGeneration) {
                    appliedStamp = stamp;
                    parseError = finalError;
                    detectedManager = finalManager;
                    applyDetected(root, finalProvider, finalLabel);
                }
            });
        });
    }

    /** How far below the project root build markers are looked for, and how many folders at most. */
    private static final int PROJECT_SCAN_DEPTH = 2;

    private static final int PROJECT_SCAN_DIRS = 400;

    /** Re-derives the tool windows' stripe visibility from the last detection without a fresh re-detect. */
    void reapplyVisibility() {
        ops.setToolWindowsAvailable(isEnabled() && markerRoot != null);
    }

    private void applyDetected(Path root, BuildActionsProvider parsed, String label) {
        this.markerRoot = root;
        this.provider = parsed;
        this.detectedLabel = label;
        // Every detect builds a FRESH provider, and detect re-runs on tab switch / focus-regain / every save
        // — which silently threw away the tasks the user waited ~90s for "Load all tasks…" to enumerate
        // (Ctrl+S was enough to make the All-tasks section vanish). Re-apply them for the same root.
        if (parsed != null && root != null && root.equals(loadedTasksRoot) && !loadedTasks.isEmpty()) {
            parsed.addLoadedTasks(loadedTasks);
        }
        if (root != null && projectRoots.size() == 1 && projectRoots.get(0).equals(root)) {
            projectRoots = List.of(); // one root, and it is this one: nothing to pick between
        }
        showDetectedInTree();
        ops.setToolWindowsAvailable(isEnabled() && root != null);
    }

    /** Whether the detected build file exists but did not parse (the tree shows the reason). */
    boolean isMalformed() {
        return markerRoot != null && parseError != null && !parseError.isBlank();
    }

    /** Opens the searchable actions popup, centered (the {@code <tool>.showActions} palette command). */
    void showActionsPopup() {
        if (!isEnabled()) {
            host.setStatus(disabledMessage());
            return;
        }
        if (markerRoot == null) {
            host.setStatus(tr("status.build.notDetected", tool.displayName()));
            return;
        }
        if (provider == null || isMalformed()) {
            host.setStatus(
                    parseError == null || parseError.isBlank()
                            ? tr("status.build.malformed", tool.displayName())
                            : tr("status.build.malformedAt", tool.displayName(), parseError));
            if (provider == null) {
                return;
            }
        }
        popupRoot = null;
        showPopup(provider, markerRoot);
    }

    /** Shows the popup over {@code provider} for {@code root}, with that root's toggle set and this tool's
     *  recent custom strings. */
    private void showPopup(BuildActionsProvider forRoot, Path root) {
        popup.setRecent(ops.customHistory(tool.id()), text -> runCustomText(root, text));
        popup.show(host.window(), forRoot, togglesFor(root));
    }

    /**
     * As {@link #showActionsPopup}, for the project whose marker directory is {@code root} — the Maven
     * submenu on a {@code pom.xml} row or folder. The active tab's module and its already-parsed provider are
     * reused when {@code root} is that one; any other root is parsed off the FX thread first, and the popup's
     * tasks then run <em>there</em>. A right-click on {@code moduleB/pom.xml} used to open the popup of the
     * module the editor was in, so every task ran in the wrong place.
     */
    void showActionsAt(Path root) {
        if (root == null || (markerRoot != null && markerRoot.equals(root))) {
            showActionsPopup();
            return;
        }
        if (!isEnabled()) {
            host.setStatus(disabledMessage());
            return;
        }
        if (!Files.isDirectory(root)) {
            host.setStatus(tr("status.build.notDetected", tool.displayName()));
            return;
        }
        Path project = ops.projectRoot();
        BuildDetection.execute(() -> {
            BuildActionsProvider parsed;
            String error = null;
            try {
                parsed = tool.parse(root, project).provider();
            } catch (Exception e) {
                parsed = null;
                error = e.getMessage();
            }
            BuildActionsProvider finalParsed = parsed;
            String finalError = error;
            Platform.runLater(() -> {
                if (finalParsed == null) {
                    host.setStatus(
                            finalError == null || finalError.isBlank()
                                    ? tr("status.build.malformed", tool.displayName())
                                    : tr("status.build.malformedAt", tool.displayName(), finalError));
                    return;
                }
                popupRoot = root;
                showPopup(finalParsed, root);
            });
        });
    }

    /** Runs a selected task with its args + the active toggles' args (the popup's callback). */
    void runTask(List<String> taskArgs, List<String> toggleArgs) {
        if (!isAvailable()) {
            host.setStatus(isEnabled() ? tr("status.build.notDetected", tool.displayName()) : disabledMessage());
            return;
        }
        if (busy(String.join(" ", taskArgs), () -> runTask(taskArgs, toggleArgs))) {
            return;
        }
        List<String> argv = new ArrayList<>(executable(markerRoot));
        argv.addAll(taskArgs);
        argv.addAll(toggleArgs);
        launch(markerRoot, argv, taskArgs, toggleArgs);
    }

    /**
     * As {@link #runTask}, but in {@code root} rather than wherever the active tab's marker was last
     * detected — for repeating a run (Test Results' Rerun / Rerun failed / Rerun this test), which must
     * happen where the original did. {@link #markerRoot} follows the active tab, so after opening a file of
     * another module the rerun ran that module's build: a filter matching nothing there, reported as passed.
     */
    void runTaskAt(Path root, List<String> taskArgs, List<String> toggleArgs) {
        if (root == null) {
            runTask(taskArgs, toggleArgs);
            return;
        }
        if (!isEnabled()) {
            host.setStatus(disabledMessage());
            return;
        }
        if (busy(String.join(" ", taskArgs), () -> runTaskAt(root, taskArgs, toggleArgs))) {
            return;
        }
        if (!Files.isDirectory(root)) {
            host.setStatus(tr("status.build.notDetected", tool.displayName()));
            return;
        }
        List<String> argv = new ArrayList<>(executable(root));
        argv.addAll(taskArgs);
        argv.addAll(toggleArgs);
        launch(root, argv, taskArgs, toggleArgs);
    }

    /** Prompts for a freeform task string and runs it verbatim (no toggle args — type the modifier flag
     *  directly if one is needed). */
    void runCustom() {
        runCustomAt(null);
    }

    /**
     * As {@link #runCustom}, in {@code root} — the Maven submenu's "Run custom" on a {@code pom.xml} row runs
     * in that pom's folder, not the active tab's. {@code null} means the active tab's module.
     */
    void runCustomAt(Path root) {
        if (root == null ? !isAvailable() : !isEnabled()) {
            host.setStatus(isEnabled() ? tr("status.build.notDetected", tool.displayName()) : disabledMessage());
            return;
        }
        if (busy(tr("buildtree.runCustom"), () -> runCustomAt(root))) {
            return;
        }
        // Capture the root now: the prompt is an in-scene overlay, so a focus-regain can re-detect (and null
        // markerRoot, e.g. the pom was deleted meanwhile) while it is open — executable(null) then NPEs.
        Path at = root == null ? markerRoot : root;
        // Prefilled with the last string (this session's, else the project's history), and the label says
        // how quoting works and that the checked toggles are NOT added — they used to be dropped silently.
        List<String> history = ops.customHistory(tool.id());
        String initial = lastCustomText != null ? lastCustomText : history.isEmpty() ? "" : history.get(0);
        List<String> toggles = at == null ? List.of() : providerToggleArgs(at);
        String label = tr("dialog.buildCustom.label") + " — "
                + (toggles.isEmpty()
                        ? tr("dialog.buildCustom.hintNoToggles")
                        : tr("dialog.buildCustom.hint", String.join(" ", toggles)));
        host.promptText(tr("dialog.buildCustom.title", tool.displayName()), label, initial, text -> {
            if (text == null || text.isBlank()) {
                return;
            }
            runCustomText(at, text.strip());
        });
    }

    /** The toggle args of {@code root}'s set, through the current provider when {@code root} is the detected
     *  one (another root's popup has its own parsed provider, which the toggles were checked against). */
    private List<String> providerToggleArgs(Path root) {
        if (provider == null || markerRoot == null || !markerRoot.equals(root)) {
            return List.of();
        }
        return provider.toggleArgs(togglesFor(root));
    }

    /** Runs the custom string {@code text} verbatim in {@code root} and remembers it (the prompt's accept, a
     *  "Recent" row of the popup). */
    void runCustomText(Path root, String text) {
        List<String> tokens = ProgramArgs.tokenize(text);
        if (tokens.isEmpty()) {
            return;
        }
        Path at = root == null ? markerRoot : root;
        if (at == null || !Files.isDirectory(at)) {
            host.setStatus(tr("status.build.notDetected", tool.displayName()));
            return;
        }
        if (service.isRunning()) {
            host.setStatus(tr("status.build.busy", tool.displayName()));
            return;
        }
        lastCustomText = text;
        ops.rememberCustom(tool.id(), text);
        List<String> argv = new ArrayList<>(executable(at));
        argv.addAll(tokens);
        launch(at, argv, tokens, List.of());
    }

    /** Re-runs the most recent invocation (same root + task + toggle args). */
    void rerunLast() {
        if (lastRoot == null || lastTaskArgs == null) {
            host.setStatus(tr("status.build.noRerun", tool.displayName()));
            return;
        }
        if (busy(String.join(" ", lastTaskArgs), this::rerunLast)) {
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
        // On a host the user names the tool there (Settings → Remote → tools, e.g. a Maven outside the login
        // PATH); this machine's command override names a path here. The host's wrapper still comes first.
        String hostTool = com.editora.process.ExecTargets.remoteTool(root, tool.id());
        return tool.executable(
                root,
                wrapperBoundary(root),
                isWindows(),
                hostTool != null ? hostTool : host.projectScope().buildCommand(tool, host.settings()));
    }

    /**
     * How far above {@code root} a build wrapper is looked for: the open project's root when {@code root} is
     * inside it (a module of a multi-module build keeps its {@code mvnw}/{@code gradlew} at the top), else
     * {@code root} itself — never a folder the user has not opened.
     */
    private Path wrapperBoundary(Path root) {
        Path project = ops.projectRoot();
        if (project == null) {
            return root;
        }
        Path normalized = project.toAbsolutePath().normalize();
        return root.toAbsolutePath().normalize().startsWith(normalized) ? normalized : root;
    }

    /**
     * The workspace-trust gate: whether a build may execute from {@code root} right now.
     *
     * <p>Every build of a Maven or Gradle project runs code the repository supplies — not only a shipped
     * {@code ./mvnw}/{@code ./gradlew} (the one case this used to gate) but the pom's plugins and
     * extensions, the files under {@code .mvn/}, every Gradle script — on the user's own {@code mvn} just
     * the same. So the first build of any kind in an untrusted folder asks once, with a prompt that lists
     * what it found ({@link BuildTrust}); trust is remembered per folder (the wrapper's, else the open
     * project — a module must not silently cover a script one level up), inherited by subfolders, shown in
     * the tasks window's header with a Revoke, and revocable in Settings → Workspace. Tools whose builds
     * run nothing of the repository's (npm, Cargo, Go, …) pass straight through.
     *
     * <p>Declining does <b>not</b> silently fall back to anything: "no" has to mean no build, not a quieter
     * one. The same gate covers Gradle's "Load all tasks…", the Run/Debug build-before-launch step and the
     * Maven classpath fallback ({@link #trustedExecutable}).
     */
    private boolean allowRun(Path root) {
        if (root == null) {
            return false;
        }
        if (!BuildTrust.gates(tool)) {
            return true;
        }
        Path wrapper = tool.repoWrapper(root, wrapperBoundary(root), isWindows());
        Path trustRoot = wrapper != null ? wrapper.getParent() : wrapperBoundary(root);
        if (ops.isTrusted(trustRoot)) {
            return true;
        }
        BuildTrust.Summary summary =
                BuildTrust.describe(tool, root, wrapper, host.projectScope().buildCommand(tool, host.settings()));
        if (!ops.confirmTrust(trustRoot, summary)) {
            host.setStatus(tr("status.build.untrusted", tool.displayName()));
            return false;
        }
        ops.trust(trustRoot);
        if (tree != null && markerRoot != null) {
            tree.setHeader(markerRoot, ops.projectRoot(), trustState(markerRoot));
        }
        return true;
    }

    private void launch(Path root, List<String> argv, List<String> taskArgs, List<String> toggleArgs) {
        if (!allowRun(root)) {
            return;
        }
        lastRoot = root;
        lastTaskArgs = taskArgs;
        lastToggleArgs = toggleArgs;
        stopRequested = false;

        // Intercept a recognized test run for the Test Results window, without duplicating BuildService: the
        // same process runs, and its stream is forwarded to the hook. The hook may decline (feature off), in
        // which case the run proceeds unchanged — and, crucially, Go's argv is only augmented with -json when
        // the test window will actually consume it (else the console would show raw JSON for nothing).
        boolean recognized = testRunHook != null && TestRunRecognizer.isTestRun(tool, taskArgs);
        List<String> augmented = recognized ? TestRunRecognizer.augmentArgv(tool, argv) : argv;
        boolean claimed = recognized && testRunHook.onTestRunStart(tool, root, taskArgs, toggleArgs, augmented);
        List<String> runArgv = claimed ? augmented : argv;

        // A claimed test run shows the Test Results window (the hook already opened it); opening the shared
        // console too would displace it — both live on the single-window bottom stripe. The console TAB is
        // still created + fed below (panel.started/appendOutput), so switching to Output shows the raw
        // stream; it's just not fronted over the Test Results tree.
        if (!claimed) {
            ops.openConsole();
        }
        String label = String.join(" ", taskArgs);
        // npm's tab is named after the manager that runs (pnpm, yarn, bun), not always "npm".
        String tabName =
                tool == BuildTool.NPM ? com.editora.run.MissingProgram.baseName(runArgv.get(0)) : tool.displayName();
        panel.started(this, tabName, label, tool.outputStyle(), this::stop);
        if (tree != null) {
            tree.setRunning(true);
        }
        // Always names the folder: Rerun Last runs where the last build did, which may be a root whose tool
        // windows are no longer shown (review B21).
        Path rootName = root.toAbsolutePath().normalize().getFileName();
        host.setStatus(tr("status.build.startedIn", tool.displayName(), label, String.valueOf(rootName)));
        // Maven and the Scala tools (sbt, Mill, Scala CLI) run on the JDK chosen in Settings, as Run does; Gradle
        // picks its own through its toolchains.
        boolean onSettingsJdk = tool == BuildTool.MAVEN
                || tool == BuildTool.SBT
                || tool == BuildTool.MILL
                || tool == BuildTool.SCALA_CLI;
        java.util.Map<String, String> environment = !onSettingsJdk
                ? java.util.Map.of()
                : !com.editora.process.ExecTargets.runsHere(root)
                        // Where the build runs elsewhere: the JDK there, or the one configured for it; never
                        // this machine's JDK or PATH.
                        ? RunCoordinator.remoteJdkEnvironment(
                                com.editora.process.ExecTargets.remoteTool(root, "jdk.home"))
                        : com.editora.run.JdkToolchain.environment(
                                host.settings().getMavenJdkHome(), com.editora.process.ProcessRunner.augmentedPath());
        if (tool == BuildTool.NPM) {
            environment = NpmRunSupport.environment(System.getenv("NO_COLOR"));
            long generation = ++runtimeGeneration;
            com.editora.run.RuntimeProbe.probe(runArgv, root, environment, version -> {
                if (generation == runtimeGeneration) {
                    panel.setRuntime(BuildCoordinator.this, version); // the Node (or Bun) the script runs under
                }
            });
        }
        service.run(root, runArgv, environment, new BuildService.Listener() {
            @Override
            public void onStart(String commandLine) {
                panel.started(
                        BuildCoordinator.this, tabName, commandLine, tool.outputStyle(), BuildCoordinator.this::stop);
            }

            @Override
            public void onOutput(String line, boolean stderr) {
                if (outputWatcher != null) {
                    outputWatcher.accept(line, stderr); // e.g. the Debug-via-build JDWP-banner watcher
                }
                if (claimed) {
                    testRunHook.onTestOutput(line, stderr);
                    String shown = testRunHook.consoleLine(line, stderr);
                    if (shown != null) {
                        panel.appendOutput(BuildCoordinator.this, shown, stderr);
                    }
                } else {
                    panel.appendOutput(BuildCoordinator.this, line, stderr);
                }
            }

            @Override
            public void onExit(int code) {
                outputWatcher = null; // the run is over; drop any JDWP watcher that never fired
                appliedStamp = null; // the run may have changed what a parse reads (downloaded plugins)
                // A deliberate Stop is "Stopped" everywhere — the console header, the status bar, Test Results —
                // whatever code the killed tree reported (143 after SIGTERM): not a crash (Maven review B5).
                boolean stopped = stopRequested;
                stopRequested = false;
                if (stopped) {
                    panel.stopped(BuildCoordinator.this);
                } else {
                    panel.finished(BuildCoordinator.this, code);
                }
                if (tree != null) {
                    tree.setRunning(false);
                }
                if (claimed) {
                    if (stopped) {
                        testRunHook.onTestStopped(code);
                    } else {
                        testRunHook.onTestExit(code);
                    }
                }
                host.setStatus(
                        stopped
                                ? tr("status.build.stopped", tool.displayName())
                                : code == 0
                                        ? tr("status.build.ok", tool.displayName())
                                        : tr("status.build.exit", tool.displayName(), code));
                runAfterStop();
                if (!stopped && code > 0 && tool == BuildTool.NPM) {
                    // A script that failed because nothing is installed yet (a fresh clone, a new template):
                    // say so, instead of leaving "tsc: not found" or a missing-module trace to be decoded.
                    NpmRunSupport.whenDependenciesMissing(root, ops.projectRoot(), manager -> {
                        String hint = tr("status.build.npmInstallFirst", manager);
                        panel.appendOutput(BuildCoordinator.this, hint, true);
                        host.setStatus(hint);
                    });
                }
            }

            @Override
            public void onError(String message) {
                outputWatcher = null;
                stopRequested = false;
                afterStop = null; // nothing ran, so nothing was stopped: a queued run would start from a failure
                panel.failed(BuildCoordinator.this, message);
                if (tree != null) {
                    tree.setRunning(false);
                }
                if (claimed) {
                    testRunHook.onTestError(message);
                }
                runtimeGeneration++; // nothing ran: drop the runtime chip and any late probe answer
                panel.setRuntime(BuildCoordinator.this, null);
                String friendly = NpmRunSupport.missingMessage(
                        runArgv.get(0), message, detectedManager == null ? "" : detectedManager.reason());
                if (friendly != null) {
                    panel.appendOutput(BuildCoordinator.this, friendly, true);
                    host.setStatus(friendly);
                } else {
                    host.setStatus(tr("status.build.failed", tool.displayName(), message));
                }
            }
        });
    }

    /** Guards a runtime probe's late answer against a newer run. */
    private long runtimeGeneration;

    /** Observes the next run's output lines (line, isStderr) until the run ends — the Debug-via-build flow
     *  watches for the JDWP banner. Set before {@link #runTask}; auto-cleared on run completion. */
    void setOutputWatcher(java.util.function.BiConsumer<String, Boolean> watcher) {
        this.outputWatcher = watcher;
    }

    /** Injects the Test Results hook (a recognized {@code test} run is mirrored there). Null = feature absent. */
    void setTestRunHook(TestRunHook hook) {
        this.testRunHook = hook;
    }

    /** Stops the running process (Console Stop button / {@code <tool>.stop}). */
    void stop() {
        if (service.isRunning()) {
            stopRequested = true; // the exit that follows is "Stopped", not the killed tree's code (B5)
            service.stop();
            host.setStatus(tr("status.build.stopped", tool.displayName()));
        }
    }

    /**
     * Set by {@link #stop()} for the run in flight and read once by its exit: a build the user stopped ends as
     * "Stopped" in the console header, the status bar and Test Results, whatever code the killed process tree
     * reported (SIGTERM's 143, a force-kill's 137). Cleared by every launch and launch failure.
     */
    private boolean stopRequested;

    /**
     * A run the user asked to start while another was in progress, accepted through {@link #busy}'s
     * "Stop and run" prompt; started by the running build's exit, once the service can take it.
     */
    private Runnable afterStop;

    /**
     * What {@link #busy} asks: (tool name, the task about to run) → whether to stop the running build for it.
     * Null = the modal {@link #confirmStopAndRun} dialog; a test installs an answer instead.
     */
    private java.util.function.BiPredicate<String, String> stopAndRunPrompt;

    /** For tests: answers the "Stop and run" question without a dialog ({@code null} restores the dialog). */
    void setStopAndRunPrompt(java.util.function.BiPredicate<String, String> prompt) {
        this.stopAndRunPrompt = prompt;
    }

    /**
     * The busy guard (Maven review B13). A second run while one is in progress used to be refused with one
     * status line; now it asks "Stop the running build and run <task>?" — accepted, the running build is
     * stopped and {@code rerun} is queued for the moment its exit has been delivered ({@code BuildService}
     * refuses a start before that, and the tree's Run button is greyed meanwhile); declined, the old status
     * line says a run is in progress. Returns true when the caller must return: a run was in progress.
     */
    private boolean busy(String task, Runnable rerun) {
        if (!service.isRunning()) {
            return false;
        }
        boolean accepted = stopAndRunPrompt == null
                ? confirmStopAndRun(host.window(), tool.displayName(), task)
                : stopAndRunPrompt.test(tool.displayName(), task);
        if (!accepted) {
            host.setStatus(tr("status.build.busy", tool.displayName()));
            return true;
        }
        if (!service.isRunning()) {
            rerun.run(); // it ended while the question was open: nothing to stop
            return true;
        }
        afterStop = rerun;
        stop();
        return true;
    }

    /** Starts the run queued behind a stop, if any — from the stopped build's exit. */
    private void runAfterStop() {
        Runnable next = afterStop;
        afterStop = null;
        if (next != null) {
            next.run();
        }
    }

    /** The "Stop and run" question: a modal confirmation whose default is Cancel (Enter must not kill a build). */
    private static boolean confirmStopAndRun(javafx.stage.Window owner, String toolName, String task) {
        javafx.scene.control.ButtonType accept = new javafx.scene.control.ButtonType(
                tr("dialog.buildStopAndRun.accept"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        javafx.scene.control.Alert alert = Dialogs.styled(new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                tr("dialog.buildStopAndRun.body", task),
                accept,
                javafx.scene.control.ButtonType.CANCEL));
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.setTitle(tr("dialog.buildStopAndRun.title", toolName));
        alert.setHeaderText(tr("dialog.buildStopAndRun.header", toolName));
        ((javafx.scene.control.Button) alert.getDialogPane().lookupButton(accept)).setDefaultButton(false);
        ((javafx.scene.control.Button) alert.getDialogPane().lookupButton(javafx.scene.control.ButtonType.CANCEL))
                .setDefaultButton(true);
        return alert.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL) == accept;
    }

    /** Force re-parses the marker file right now (e.g. after an external edit) — {@code <tool>.refresh}. */
    void refreshMarker() {
        appliedStamp = null; // asked for by hand: always re-read
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
        // Gated too: this spawns `./gradlew tasks --all` on its own, bypassing launch() entirely — enumerating
        // tasks runs the repo's wrapper (and its build scripts) exactly like running one does.
        if (!allowRun(root)) {
            return;
        }
        String override =
                host.projectScope().buildCommand(tool, host.settings()); // a trusted project's, else the user's
        Path boundary = wrapperBoundary(root);
        host.setStatus(tr("status.build.loadingTasks", tool.displayName()));
        Thread t = new Thread(
                () -> {
                    List<String> tasks;
                    try {
                        tasks = tool.loadTasks(root, boundary, isWindows(), override);
                    } catch (Exception e) {
                        tasks = List.of();
                    }
                    List<String> finalTasks = tasks;
                    Platform.runLater(() -> {
                        // The result belongs to `root`, so it is always remembered for it (applyDetected
                        // re-applies it whenever that root is detected again). It is only shown now if that
                        // project is still the detected one: this can take ~90s, and the callback used to read
                        // the CURRENT provider — so switching to another Gradle project meanwhile merged this
                        // project's task names into that one's tree, where running one fails "task not found".
                        // Not guarded by detectGeneration: refresh() bumps that on every tab switch, save
                        // and focus-regain, which threw the whole enumeration away for the same project.
                        loadedTasksRoot = root;
                        loadedTasks = List.copyOf(finalTasks);
                        if (!root.equals(markerRoot)) {
                            return;
                        }
                        if (provider != null) {
                            provider.addLoadedTasks(finalTasks); // mutate the shared provider once…
                            if (tree != null) {
                                tree.refreshFromProvider(); // …then re-render both views over it
                            }
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
        service.shutdown();
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
            case SBT -> Icons::sbt;
            case MILL -> Icons::mill;
            case SCALA_CLI -> Icons::scalaCli;
            case CLOJURE -> Icons::clojureCli;
            case LEIN -> Icons::leiningen;
            case BABASHKA -> Icons::babashka;
        };
    }
}
