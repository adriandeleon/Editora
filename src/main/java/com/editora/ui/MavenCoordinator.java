package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javafx.application.Platform;
import javafx.scene.Node;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.lsp.RootResolver;
import com.editora.maven.MavenArgs;
import com.editora.maven.MavenExecutable;
import com.editora.maven.MavenService;
import com.editora.maven.PomModel;
import com.editora.maven.PomParser;
import com.editora.run.ProgramArgs;
import com.editora.run.StackTraceLinks;
import com.editora.vfs.Vfs;

import static com.editora.i18n.Messages.tr;

/**
 * The Maven feature (toolbar icon → actions popup, parsed from the active project's pom.xml, streaming
 * output to a console), extracted via the {@link CoordinatorHost} pattern. Owns the {@link MavenService} +
 * the {@link MavenPanel} console + the {@link MavenActionsPopup}, and mirrors {@link GitCoordinator}'s
 * per-project detect/cache/refresh model: {@link #contextPath()} picks the active file (else the active
 * project root), {@link #refresh()} walks up for the nearest {@code pom.xml} and parses it off-thread
 * (generation-guarded), and {@link #applyDetected} flips the toolbar button's visibility. {@code
 * MainController} keeps the {@code ToolWindow} (built with {@link #panel()}) and the toolbar button itself,
 * reaching this coordinator's window needs through the shared host plus a small {@link Ops} extension.
 */
final class MavenCoordinator {

    /** Window hooks beyond {@link CoordinatorHost} that this feature needs. */
    interface Ops {
        /** The active window's project root, or {@code null} when no project is open. */
        Path projectRoot();

        /** Opens (and focuses) the Maven console tool window. */
        void openConsole();

        /** A clicked file path in the console output (jump to it). */
        void onOutputLink(StackTraceLinks.Link link);

        /** Shows/hides (and un-manages) the Maven toolbar button. */
        void setToolbarButtonVisible(boolean visible);
    }

    private final CoordinatorHost host;
    private final Ops ops;
    private final MavenService service = new MavenService();
    private final MavenPanel panel = new MavenPanel(this::stop);
    private final MavenActionsPopup popup = new MavenActionsPopup();

    private Node toolbarButton;

    /** The nearest detected pom.xml for the current context, or {@code null}. */
    private Path pomPath;
    /** The last successfully parsed model for {@link #pomPath}, or {@code null} (absent/malformed). */
    private PomModel model;

    private int detectGeneration;

    /** The most recent launch, for {@code maven.rerunLast}. */
    private Path lastRoot;

    private List<String> lastGoals;
    private List<String> lastProfiles = List.of();

    MavenCoordinator(CoordinatorHost host, Ops ops) {
        this.host = host;
        this.ops = ops;
        panel.setOnLink(ops::onOutputLink);
        popup.setOnRunCustom(this::runCustom);
        popup.setOnRun(this::runGoals);
    }

    MavenPanel panel() {
        return panel;
    }

    void setOverlayHost(OverlayHost overlayHost) {
        popup.setOverlayHost(overlayHost);
    }

    /** The toolbar button node — recorded so the palette command {@code maven.showActions} (as well as
     *  the button's own click) can anchor the popup below it. */
    void setToolbarButton(Node button) {
        this.toolbarButton = button;
    }

    /** Whether the Maven integration is enabled in Settings (default on — inert until a pom.xml is
     *  actually detected). Off in Simple UI mode. */
    boolean isEnabled() {
        return host.settings().isMavenSupport() && !host.simpleModeActive();
    }

    /** Whether a goal can actually be run right now: enabled, a pom.xml was found, and it parsed. */
    private boolean isAvailable() {
        return isEnabled() && pomPath != null && model != null;
    }

    /** Whether a pom.xml is currently detected for the active context (the Settings page's found/not-found
     *  status row). */
    boolean hasPom() {
        return pomPath != null;
    }

    /** The detected project's artifactId, or {@code null} when no pom.xml is detected or it failed to parse. */
    String detectedArtifactId() {
        return model == null ? null : model.artifactId();
    }

    /** The path whose project drives the Maven UI: the active local file, else the active project root. */
    Path contextPath() {
        EditorBuffer b = host.activeBuffer();
        Path file = b == null ? null : b.getPath();
        if (file != null && host.isLocalBuffer(b)) {
            return file;
        }
        return ops.projectRoot();
    }

    /** Re-detects the nearest pom.xml for the current context and re-parses it, off the FX thread
     *  (generation-guarded against a stale/superseded detect). Runs at startup, on tab switch, on window
     *  focus-regain, on save, and on every settings apply — cheap to over-call. */
    void refresh() {
        if (!isEnabled()) {
            applyDetected(null, null);
            return;
        }
        Path context = contextPath();
        if (context == null || !Vfs.isLocal(context)) {
            applyDetected(null, null);
            return;
        }
        int gen = ++detectGeneration;
        Thread t = new Thread(
                () -> {
                    Path root = RootResolver.findMarkerRoot(context, List.of("pom.xml"));
                    Path pom = root == null ? null : root.resolve("pom.xml");
                    PomModel parsed = null;
                    if (pom != null) {
                        try {
                            parsed = PomParser.parseFile(pom);
                        } catch (Exception e) {
                            parsed = null; // malformed pom — applyDetected(pom, null) reports it distinctly
                        }
                    }
                    Path finalPom = pom;
                    PomModel finalModel = parsed;
                    Platform.runLater(() -> {
                        if (gen == detectGeneration) {
                            applyDetected(finalPom, finalModel);
                        }
                    });
                },
                "maven-detect");
        t.setDaemon(true);
        t.start();
    }

    /** Re-derives the toolbar button's visibility from the last detection without a fresh re-detect (e.g.
     *  after Simple Mode toggles, which only changes {@link #isEnabled()}, not what's on disk). */
    void reapplyVisibility() {
        ops.setToolbarButtonVisible(isEnabled() && pomPath != null);
    }

    private void applyDetected(Path pom, PomModel parsed) {
        this.pomPath = pom;
        this.model = parsed;
        ops.setToolbarButtonVisible(isEnabled() && pom != null);
    }

    /** Opens the actions popup (the toolbar button's click, and the {@code maven.showActions} command). */
    void showActionsPopup(Node anchor) {
        if (!isEnabled()) {
            host.setStatus(tr("statusbar.tip.mavenDisabled"));
            return;
        }
        if (pomPath == null) {
            host.setStatus(tr("status.maven.noPom"));
            return;
        }
        if (model == null) {
            host.setStatus(tr("status.maven.malformedPom"));
            return;
        }
        popup.show(host.window(), anchor, model);
    }

    /** Runs a lifecycle phase or plugin goal with the given active profiles (the popup's callback). */
    void runGoals(List<String> goalsOrPhases, List<String> profiles) {
        if (!isAvailable()) {
            host.setStatus(tr(isEnabled() ? "status.maven.noPom" : "statusbar.tip.mavenDisabled"));
            return;
        }
        if (service.isRunning()) {
            host.setStatus(tr("status.maven.busy"));
            return;
        }
        Path root = pomPath.getParent();
        List<String> argv = new ArrayList<>(mavenArgv(root));
        argv.addAll(MavenArgs.build(goalsOrPhases, profiles));
        launch(root, argv, goalsOrPhases, profiles);
    }

    /** Prompts for a freeform goal string and runs it verbatim (no profile flag — type {@code -P<id>}
     *  directly if one is needed). */
    void runCustom() {
        if (!isAvailable()) {
            host.setStatus(tr(isEnabled() ? "status.maven.noPom" : "statusbar.tip.mavenDisabled"));
            return;
        }
        if (service.isRunning()) {
            host.setStatus(tr("status.maven.busy"));
            return;
        }
        host.promptText(tr("dialog.mavenCustomGoal.title"), tr("dialog.mavenCustomGoal.label"), "", text -> {
            if (text == null || text.isBlank()) {
                return;
            }
            List<String> tokens = ProgramArgs.tokenize(text.strip());
            if (tokens.isEmpty()) {
                return;
            }
            Path root = pomPath.getParent();
            List<String> argv = new ArrayList<>(mavenArgv(root));
            argv.addAll(tokens);
            launch(root, argv, tokens, List.of());
        });
    }

    /** Re-runs the most recent invocation (same root + goals + profiles). */
    void rerunLast() {
        if (lastRoot == null || lastGoals == null) {
            host.setStatus(tr("status.maven.noRerun"));
            return;
        }
        if (service.isRunning()) {
            host.setStatus(tr("status.maven.busy"));
            return;
        }
        List<String> argv = new ArrayList<>(mavenArgv(lastRoot));
        argv.addAll(MavenArgs.build(lastGoals, lastProfiles));
        launch(lastRoot, argv, lastGoals, lastProfiles);
    }

    private List<String> mavenArgv(Path root) {
        return MavenExecutable.chooseArgv(
                Files.isRegularFile(root.resolve("mvnw")),
                Files.isRegularFile(root.resolve("mvnw.cmd")),
                isWindows(),
                host.settings().getMavenCommand());
    }

    private void launch(Path root, List<String> argv, List<String> goalsOrPhases, List<String> profiles) {
        lastRoot = root;
        lastGoals = goalsOrPhases;
        lastProfiles = profiles;
        ops.openConsole();
        String label = String.join(" ", goalsOrPhases);
        panel.started(label);
        host.setStatus(tr("status.maven.started", label));
        service.run(root, argv, new MavenService.Listener() {
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
                host.setStatus(code == 0 ? tr("status.maven.ok") : tr("status.maven.exit", code));
            }

            @Override
            public void onError(String message) {
                panel.failed(message);
                host.setStatus(tr("status.maven.failed", message));
            }
        });
    }

    /** Stops the running Maven process (Console Stop button / {@code maven.stop}). */
    void stop() {
        if (service.isRunning()) {
            service.stop();
            host.setStatus(tr("status.maven.stopped"));
        }
    }

    /** Force re-parses pom.xml right now (e.g. after an external edit) — {@code maven.refresh}. */
    void refreshPom() {
        refresh();
        host.setStatus(tr("status.maven.refreshed"));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    void registerCommands(CommandRegistry registry) {
        registry.register(Command.of("tool.maven", ops::openConsole));
        registry.register(Command.of("maven.showActions", () -> showActionsPopup(toolbarButton)));
        registry.register(Command.of("maven.runCustom", this::runCustom));
        registry.register(Command.of("maven.stop", this::stop));
        registry.register(Command.of("maven.rerunLast", this::rerunLast));
        registry.register(Command.of("maven.refresh", this::refreshPom));
    }

    void shutdown() {
        service.stop();
    }
}
