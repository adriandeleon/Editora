package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import javafx.scene.control.Alert;

import com.editora.install.InstallCatalog;
import com.editora.install.InstallCatalog.Lang;
import com.editora.install.InstallCatalog.Prereq;
import com.editora.install.InstallCatalog.Step;
import com.editora.install.InstallService;
import com.editora.install.PinnedVersions;
import com.editora.install.XmlServerInstall;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Owns the in-app, cross-platform install of language support — the LSP server + DAP adapter for Java,
 * Python, and Node/JS, plus the Mermaid render CLI — extracted from {@code MainController} as a feature
 * coordinator (the {@link MermaidCoordinator} pattern). It runs {@link InstallCatalog} recipes through
 * {@link InstallService} off the FX thread, checks prerequisites (Node/Python/tar) first, then re-detects so
 * the now-installed tools light up (LSP servers, debug adapters, the {@code .mmd} preview) without a restart.
 *
 * <p>It reaches the window through the shared {@link CoordinatorHost} plus a small {@link Ops} extension for
 * the install dir + the cross-feature availability reads + the post-install re-detection.
 */
final class InstallCoordinator {

    /** Window reach beyond {@link CoordinatorHost}: install dir, availability reads, re-detect. */
    interface Ops {
        /** The active config dir — the install destination root (its {@code plugins/…} subtree). */
        Path configDir();

        /** Whether the given LSP {@code serverId} (java/python/typescript) is currently detected. */
        boolean lspAvailable(String serverId);

        /** Whether a debug adapter for {@code language} (java/python/javascript) is currently detected. */
        boolean dapAvailable(String language);

        /** Whether the Mermaid {@code mmdc} CLI is currently detected. */
        boolean mmdcAvailable();

        /** Whether the {@code typst} render CLI is currently detected (cached). */
        boolean typstCliAvailable();

        /** Invalidates the cached tool detection and re-applies LSP/Debug/Mermaid support (+ persists). */
        void reapplyToolSupport();

        /** Whether the command bash-language-server would run for {@code toolId} (shellcheck/shfmt) is there. */
        default boolean toolAvailable(String toolId) {
            return false;
        }

        /**
         * Stops a running server whose install is about to be replaced (an update); the {@link
         * #reapplyToolSupport} that follows the install starts it again on whichever copy is then in place.
         */
        default void stopServer(String serverId) {}
    }

    private final CoordinatorHost host;
    private final Ops ops;
    private final InstallService service;
    /** Languages with an install currently running (so a second trigger no-ops). */
    private final Set<Lang> installing = EnumSet.noneOf(Lang.class);
    /** LSP-only server ids (json/bash/go/…) with an install currently running. */
    private final Set<String> installingServers = new java.util.HashSet<>();

    InstallCoordinator(CoordinatorHost host, Ops ops) {
        this(host, ops, new InstallService());
    }

    /** As above with the service supplied — the seam that lets a test answer the downloads itself. */
    InstallCoordinator(CoordinatorHost host, Ops ops, InstallService service) {
        this.host = host;
        this.ops = ops;
        this.service = service;
        // The Build Tools JDK satisfies the Java prerequisite and runs the JVM servers' post-install smoke (C4).
        service.setJdkHomeSupplier(
                () -> host.settings() == null ? "" : host.settings().getMavenJdkHome());
    }

    /** Whether every tool that makes up {@code lang}'s support is already installed/detected. */
    boolean isSupportInstalled(Lang lang) {
        return InstallCatalog.steps(lang).stream().allMatch(this::stepInstalled);
    }

    /**
     * Installs whatever of {@code lang}'s support is missing. Detects prerequisites first; if a required
     * runtime (Node/Python/tar) is absent it reports that instead (we never install runtimes). On success it
     * persists the jdtls path (Java) and re-detects so the tools activate without a restart.
     */
    void installSupport(Lang lang) {
        installSupport(lang, ok -> {});
    }

    /**
     * As {@link #installSupport(Lang)} but reports the settled outcome to {@code onResult} on the FX thread
     * ({@code true} = nothing-to-do or installed OK; {@code false} = a prereq was missing, an install was
     * already running, or it failed). Used by the editor banner to clear its spinner + hide on success.
     */
    void installSupport(Lang lang, java.util.function.Consumer<Boolean> onResult) {
        installSupport(lang, null, onResult);
    }

    /**
     * As {@link #installSupport(Lang, java.util.function.Consumer)}; {@code updateStep} (Doctor's Update) is a step
     * to install again although it is there — an outdated pinned server, or one a newer release exists for — so it
     * ends up with the pinned or the latest version.
     */
    void installSupport(Lang lang, @Nullable String updateStep, java.util.function.Consumer<Boolean> onResult) {
        if (installing.contains(lang)) {
            host.setStatus(tr("status.install.inProgress"));
            onResult.accept(false);
            return;
        }
        List<Step> all = InstallCatalog.steps(lang);
        List<Step> missing = all.stream()
                .filter(s -> !stepInstalled(s) || s.id().equals(updateStep))
                .toList();
        if (missing.isEmpty()) {
            host.setStatus(tr("status.install.already", langName(lang)));
            onResult.accept(true);
            return;
        }
        service.detectPrereqs(present -> {
            InstallCatalog.Plan plan = InstallCatalog.plan(missing, present);
            if (plan.runnable().isEmpty()) {
                String names = plan.need().stream().map(this::prereqName).collect(Collectors.joining(", "));
                host.setStatus(tr("status.install.needPrereq", names));
                alert(Alert.AlertType.WARNING, tr("status.install.needPrereq", names));
                onResult.accept(false);
                return;
            }
            installing.add(lang);
            List<String> replaced = runningServersOf(plan.runnable());
            stopWhileReplaced(replaced);
            host.setStatus(tr("status.install.installing", langName(lang)));
            service.install(
                    plan.runnable(),
                    ops.configDir(),
                    id -> host.setStatus(tr("status.install.installingTool", id)),
                    result -> {
                        installing.remove(lang);
                        replaced.forEach(com.editora.lsp.LspManager::releaseAfterInstall);
                        if (result.ok()) {
                            postInstall(lang);
                            host.setStatus(doneMessage(lang, plan));
                        } else {
                            if (!replaced.isEmpty()) {
                                ops.reapplyToolSupport(); // a failed install leaves the old copy: run it again
                            }
                            host.setStatus(tr("status.install.failed", langName(lang)));
                            alert(Alert.AlertType.ERROR, result.message());
                        }
                        onResult.accept(result.ok());
                    });
        });
    }

    /**
     * What a finished language install says: done, or which tool still waits for a missing runtime — and, when
     * it brought a debugger while debugging is off, where to turn it on (installing one did not).
     */
    String doneMessage(Lang lang, InstallCatalog.Plan plan) {
        String done = plan.blocked().isEmpty()
                ? tr("status.install.done", langName(lang))
                : tr(
                        "status.install.partial",
                        langName(lang),
                        plan.blocked().stream().map(Step::id).collect(Collectors.joining(", ")),
                        plan.need().stream().map(this::prereqName).collect(Collectors.joining(", ")));
        boolean debugger = plan.runnable().stream()
                .anyMatch(s -> s.destSubpath() != null && s.destSubpath().startsWith("plugins/dap/"));
        return debugger && !host.settings().isDebugSupport() ? done + " " + tr("status.install.enableDebugging") : done;
    }

    /** Installs an LSP-only server ({@code json}/{@code bash}/{@code go}/…) by its registry server id. */
    void installServer(String serverId) {
        installServer(serverId, ok -> {});
    }

    /** As {@link #installServer(String)} but reports the settled outcome (banner spinner/hide). */
    void installServer(String serverId, java.util.function.Consumer<Boolean> onResult) {
        installServer(serverId, false, onResult);
    }

    /**
     * As {@link #installServer(String, java.util.function.Consumer)}; {@code update} (Doctor's Update) installs
     * the pinned version even though a server is found, and points the server's command at it.
     */
    void installServer(String serverId, boolean update, java.util.function.Consumer<Boolean> onResult) {
        List<Step> steps = InstallCatalog.serverInstall(serverId).orElse(List.of());
        if (steps.isEmpty()) {
            onResult.accept(false);
            return;
        }
        if (installingServers.contains(serverId)) {
            host.setStatus(tr("status.install.inProgress"));
            onResult.accept(false);
            return;
        }
        boolean outdated = PinnedVersions.forServer(serverId)
                .map(pin -> editoraCopyOutdated(pin.stepId()))
                .orElse(false);
        if (ops.lspAvailable(serverId) && !update && !outdated) {
            host.setStatus(tr("status.install.already", serverName(serverId)));
            onResult.accept(true);
            return;
        }
        int[] large = InstallCatalog.largeDownload(serverId);
        if (large != null
                && !confirm.test(tr("dialog.install.largeDownload", serverName(serverId), large[0], large[1]))) {
            onResult.accept(false); // said before anything is fetched: hundreds of MB is the user's call
            return;
        }
        service.detectPrereqs(present -> {
            Set<Prereq> needed = steps.stream()
                    .flatMap(s -> s.prereqs().stream())
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(Prereq.class)));
            needed.removeAll(present);
            if (!needed.isEmpty()) {
                String names = needed.stream().map(this::prereqName).collect(Collectors.joining(", "));
                host.setStatus(tr("status.install.needPrereq", names));
                alert(Alert.AlertType.WARNING, tr("status.install.needPrereq", names));
                onResult.accept(false);
                return;
            }
            installingServers.add(serverId);
            List<String> replaced = runningServersOf(steps);
            stopWhileReplaced(replaced);
            host.setStatus(tr("status.install.installing", serverName(serverId)));
            service.install(
                    steps, ops.configDir(), id -> host.setStatus(tr("status.install.installingTool", id)), result -> {
                        installingServers.remove(serverId);
                        replaced.forEach(com.editora.lsp.LspManager::releaseAfterInstall);
                        if (result.ok()) {
                            if (result.installedCommand() != null) {
                                applyServerCommand(host.settings(), serverId, result.installedCommand());
                            }
                            ops.reapplyToolSupport();
                            String fellBack = result.message();
                            host.setStatus(
                                    fellBack == null || fellBack.isBlank()
                                            ? tr("status.install.done", serverName(serverId))
                                            : tr("status.install.javaFallback", serverName(serverId), fellBack));
                        } else {
                            if (!replaced.isEmpty()) {
                                ops.reapplyToolSupport(); // a failed install leaves the old copy: run it again
                            }
                            host.setStatus(tr("status.install.failed", serverName(serverId)));
                            alert(Alert.AlertType.ERROR, result.message());
                        }
                        onResult.accept(result.ok());
                    });
        });
    }

    /**
     * Installs the {@code typst} render CLI — a per-OS binary archive from typst's GitHub releases — and
     * points {@code Settings.typstPath} at the extracted binary, then re-detects. This is the Typst
     * <em>preview</em> tool (distinct from the tinymist LSP server, which has its own installer).
     */
    void installTypstCli() {
        installTypstCli(ok -> {});
    }

    void installTypstCli(java.util.function.Consumer<Boolean> onResult) {
        final String key = "typst-cli";
        List<Step> steps = InstallCatalog.typstCliSteps();
        if (steps.isEmpty()) {
            onResult.accept(false);
            return;
        }
        if (installingServers.contains(key)) {
            host.setStatus(tr("status.install.inProgress"));
            onResult.accept(false);
            return;
        }
        if (ops.typstCliAvailable()) {
            host.setStatus(tr("status.install.already", tr("install.lang.typst")));
            onResult.accept(true);
            return;
        }
        service.detectPrereqs(present -> {
            Set<Prereq> needed = steps.stream()
                    .flatMap(s -> s.prereqs().stream())
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(Prereq.class)));
            needed.removeAll(present);
            if (!needed.isEmpty()) {
                String names = needed.stream().map(this::prereqName).collect(Collectors.joining(", "));
                host.setStatus(tr("status.install.needPrereq", names));
                alert(Alert.AlertType.WARNING, tr("status.install.needPrereq", names));
                onResult.accept(false);
                return;
            }
            installingServers.add(key);
            host.setStatus(tr("status.install.installing", tr("install.lang.typst")));
            service.install(
                    steps, ops.configDir(), id -> host.setStatus(tr("status.install.installingTool", id)), result -> {
                        installingServers.remove(key);
                        if (result.ok()) {
                            if (result.installedCommand() != null) {
                                host.settings().setTypstPath(result.installedCommand());
                            }
                            ops.reapplyToolSupport(); // re-applies Typst support (+ persists) → preview lights up
                            host.setStatus(tr("status.install.done", tr("install.lang.typst")));
                        } else {
                            host.setStatus(tr("status.install.failed", tr("install.lang.typst")));
                            alert(Alert.AlertType.ERROR, result.message());
                        }
                        onResult.accept(result.ok());
                    });
        });
    }

    /**
     * Installs ShellCheck and shfmt — whichever of the two is missing — from their pinned, hash-checked
     * releases into the config dir's {@code plugins/tools}. bash-language-server is then pointed at them (a
     * blank path setting means "Editora's copy if installed") by the re-applied LSP support.
     */
    void installShellTools() {
        installTools(com.editora.install.InstallCatalog.PINNED_TOOL_IDS, ok -> {});
    }

    /** Installs the pinned tools {@code toolIds} that are missing and reports the settled outcome. */
    void installTools(List<String> toolIds, java.util.function.Consumer<Boolean> onResult) {
        installTools(toolIds, false, onResult);
    }

    /**
     * As {@link #installTools(List, java.util.function.Consumer)}; {@code update} (Doctor's Update) installs the
     * pinned version of each even where one is found.
     */
    void installTools(List<String> toolIds, boolean update, java.util.function.Consumer<Boolean> onResult) {
        final String key = "tools:" + String.join(",", toolIds);
        List<String> missing = toolIds.stream()
                .filter(id -> com.editora.install.InstallCatalog.pinnedTool(id).isPresent())
                .filter(id -> update || !ops.toolAvailable(id) || editoraCopyOutdated(id))
                .toList();
        String names = String.join(
                ", ", toolIds.stream().map(InstallCoordinator::toolName).toList());
        if (missing.isEmpty()) {
            host.setStatus(tr("status.install.already", names));
            onResult.accept(true);
            return;
        }
        if (installingServers.contains(key)) {
            host.setStatus(tr("status.install.inProgress"));
            onResult.accept(false);
            return;
        }
        List<Step> steps = missing.stream()
                .flatMap(id -> com.editora.install.InstallCatalog.toolInstall(id).orElse(List.of()).stream())
                .toList();
        String missingNames = String.join(
                ", ", missing.stream().map(InstallCoordinator::toolName).toList());
        service.detectPrereqs(present -> {
            Set<Prereq> needed = steps.stream()
                    .flatMap(s -> s.prereqs().stream())
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(Prereq.class)));
            needed.removeAll(present);
            if (!needed.isEmpty()) {
                String prereqs = needed.stream().map(this::prereqName).collect(Collectors.joining(", "));
                host.setStatus(tr("status.install.needPrereq", prereqs));
                alert(Alert.AlertType.WARNING, tr("status.install.needPrereq", prereqs));
                onResult.accept(false);
                return;
            }
            installingServers.add(key);
            host.setStatus(tr("status.install.installing", missingNames));
            service.install(
                    steps, ops.configDir(), id -> host.setStatus(tr("status.install.installingTool", id)), result -> {
                        installingServers.remove(key);
                        if (result.ok()) {
                            ops.reapplyToolSupport(); // the Bash server is pointed at the installed copies
                            host.setStatus(tr("status.install.done", missingNames));
                        } else {
                            host.setStatus(tr("status.install.failed", missingNames));
                            alert(Alert.AlertType.ERROR, result.message());
                        }
                        onResult.accept(result.ok());
                    });
        });
    }

    /** A pinned tool's display name. */
    static String toolName(String toolId) {
        return "shellcheck".equals(toolId) ? "ShellCheck" : toolId;
    }

    /** Friendly display name for an LSP server id (e.g. {@code json} → "JSON"). */
    String serverName(String serverId) {
        return tr("install.lang." + serverId);
    }

    /** Persists the extracted-binary command into the right per-server Settings field (binary-archive servers).
     *  Static + package-visible so {@code InstallCoordinatorTest} guards that every archive-installable server
     *  has a persist case (a missing one leaves the command on PATH-only, so detection never finds the just-
     *  installed binary and the install banner never clears — the tinymist/typst bug). */
    static void applyServerCommand(com.editora.config.Settings s, String serverId, String command) {
        switch (serverId) {
            case "clangd" -> s.setClangdLspCommand(command);
            case "kotlin" -> s.setKotlinLspCommand(command);
            case "groovy" -> s.setGroovyLspCommand(command); // java -jar over the downloaded gls jar
            case "scala" -> s.setScalaLspCommand(command); // java -cp over the jars coursier resolved for Metals
            // clojure-lsp: a pinned native binary; the installer returns its bare path
            case "clojure" -> s.setClojureLspCommand(InstallCatalog.quoteCommandPath(command));
            case "lua" -> s.setLuaLspCommand(command);
            // lemminx: Editora's own copy needs no setting (a blank command finds it, review A9); the Java lemminx it
            // falls back to where the native build cannot run is a command of its own
            case "xml" -> s.setXmlLspCommand(XmlServerInstall.isNativeInstall(command) ? "" : command);
            case "terraform" -> s.setTerraformLspCommand(command);
            case "typst" -> s.setTypstLspCommand(command); // tinymist: a binary archive, path must be persisted
            case "maven-pom" -> s.setMavenPomLspCommand(command); // java -cp launcher over the extracted bundle
            default -> {
                /* npm/toolchain servers resolve on PATH; no command to persist */
            }
        }
    }

    /** After a successful install: point jdtls at its bundled launcher (Java), then re-detect everything. */
    private void postInstall(Lang lang) {
        if (lang == Lang.JAVA) {
            Path bin = ops.configDir().resolve("plugins/lsp/java/bin");
            Path launcher = isWindows() ? bin.resolve("jdtls.bat") : bin.resolve("jdtls");
            if (Files.exists(launcher)) {
                host.settings().setJavaLspCommand(InstallCatalog.quoteCommandPath(launcher.toString()));
            }
        }
        ops.reapplyToolSupport();
    }

    /** Maps a catalog step to its current detected-availability via the existing per-feature detectors. */
    private boolean stepInstalled(Step step) {
        return switch (step.id()) {
            // Editora's own jdtls older than the pinned one counts as missing, so Install replaces it (D8).
            case "jdtls" -> ops.lspAvailable("java") && !editoraCopyOutdated(step.id());
            case "java-debug" -> ops.dapAvailable("java");
            case "pyright" -> ops.lspAvailable("python");
            // Found where the installer puts it, whether or not debugging is switched on (detection only runs
            // then, so an installed debugpy was downloaded again by every install while it was off).
            case "debugpy" ->
                ops.dapAvailable("python")
                        || com.editora.dap.DebugAdapterLocator.locateDebugpy(
                                        "", Path.of(System.getProperty("user.home", "")))
                                .isPresent();
            case "typescript-language-server" -> ops.lspAvailable("typescript");
            case "js-debug" -> ops.dapAvailable("javascript") && !editoraCopyOutdated(step.id());
            case "mmdc" -> ops.mmdcAvailable();
            default -> false;
        };
    }

    /**
     * Stops {@code serverIds} while their install folder is swapped: here, and in every other window, which kept
     * running the old copy's deleted jars, or restarted it from the folder mid-swap (third review CT6). No window
     * starts them again until the install is done.
     */
    private void stopWhileReplaced(List<String> serverIds) {
        for (String id : serverIds) {
            com.editora.lsp.LspManager.holdForInstall(id); // every window's sessions stop, and none starts
            ops.stopServer(id); // this window's diagnostics cleared too
        }
    }

    /**
     * The servers that {@code steps} reinstall while they run (an update, or an outdated copy Install replaces) —
     * those of a step whose server is detected ({@link InstallCatalog#serversOfStep}):
     * they are stopped first, so none runs on files being swapped out; the support re-applied afterwards starts
     * them again.
     */
    private List<String> runningServersOf(List<Step> steps) {
        return steps.stream()
                .flatMap(s -> InstallCatalog.serversOfStep(s.id()).stream())
                .filter(ops::lspAvailable)
                .distinct()
                .toList();
    }

    /**
     * Whether Editora's own copy of a pinned step is older than the version this Editora installs — its receipt
     * says so, or its files do — so Install replaces it rather than saying it is already there (D8). Reads files
     * only. A copy whose version cannot be told is left alone here; Doctor offers Update for it.
     */
    private boolean editoraCopyOutdated(String stepId) {
        return PinnedVersions.forStep(stepId)
                .map(pin ->
                        PinnedVersions.installedFreshness(pin, ops.configDir()) == PinnedVersions.Freshness.OUTDATED)
                .orElse(false);
    }

    private String langName(Lang lang) {
        return tr("install.lang." + lang.name().toLowerCase(Locale.ROOT));
    }

    private String prereqName(Prereq p) {
        return tr("install.prereq." + p.name().toLowerCase(Locale.ROOT));
    }

    private void alert(Alert.AlertType type, @Nullable String message) {
        alert.accept(type, message);
    }

    /** Shows a prerequisite warning or an install error. Replaceable so a test sees it without a modal dialog. */
    private java.util.function.BiConsumer<Alert.AlertType, String> alert = this::alertDialog;

    /** Test seam for {@link #alert}. */
    void setAlertForTest(java.util.function.BiConsumer<Alert.AlertType, String> alert) {
        this.alert = java.util.Objects.requireNonNull(alert, "alert");
    }

    /** Asks before a large download starts; replaceable so a test answers without a modal dialog. */
    private java.util.function.Predicate<String> confirm = this::confirmDialog;

    /** Test seam for {@link #confirm}. */
    void setConfirmForTest(java.util.function.Predicate<String> confirm) {
        this.confirm = java.util.Objects.requireNonNull(confirm, "confirm");
    }

    private boolean confirmDialog(String message) {
        Alert a = new Alert(
                Alert.AlertType.CONFIRMATION,
                message,
                javafx.scene.control.ButtonType.OK,
                javafx.scene.control.ButtonType.CANCEL);
        a.initOwner(host.window());
        a.setTitle(tr("dialog.install.title"));
        a.setHeaderText(null);
        return a.showAndWait()
                .filter(r -> r == javafx.scene.control.ButtonType.OK)
                .isPresent();
    }

    private void alertDialog(Alert.AlertType type, String message) {
        Alert a = new Alert(type);
        a.initOwner(host.window());
        a.setTitle(tr("dialog.install.title"));
        a.setHeaderText(null);
        a.setContentText(message);
        a.showAndWait();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    void shutdown() {
        service.shutdown();
    }
}
