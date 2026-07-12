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
    }

    private final CoordinatorHost host;
    private final Ops ops;
    private final InstallService service = new InstallService();
    /** Languages with an install currently running (so a second trigger no-ops). */
    private final Set<Lang> installing = EnumSet.noneOf(Lang.class);
    /** LSP-only server ids (json/bash/go/…) with an install currently running. */
    private final Set<String> installingServers = new java.util.HashSet<>();

    InstallCoordinator(CoordinatorHost host, Ops ops) {
        this.host = host;
        this.ops = ops;
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
        if (installing.contains(lang)) {
            host.setStatus(tr("status.install.inProgress"));
            onResult.accept(false);
            return;
        }
        List<Step> all = InstallCatalog.steps(lang);
        List<Step> missing = all.stream().filter(s -> !stepInstalled(s)).toList();
        if (missing.isEmpty()) {
            host.setStatus(tr("status.install.already", langName(lang)));
            onResult.accept(true);
            return;
        }
        service.detectPrereqs(present -> {
            Set<Prereq> needed = missing.stream()
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
            installing.add(lang);
            host.setStatus(tr("status.install.installing", langName(lang)));
            service.install(
                    missing, ops.configDir(), id -> host.setStatus(tr("status.install.installingTool", id)), result -> {
                        installing.remove(lang);
                        if (result.ok()) {
                            postInstall(lang);
                            host.setStatus(tr("status.install.done", langName(lang)));
                        } else {
                            host.setStatus(tr("status.install.failed", langName(lang)));
                            alert(Alert.AlertType.ERROR, result.message());
                        }
                        onResult.accept(result.ok());
                    });
        });
    }

    /** Installs an LSP-only server ({@code json}/{@code bash}/{@code go}/…) by its registry server id. */
    void installServer(String serverId) {
        installServer(serverId, ok -> {});
    }

    /** As {@link #installServer(String)} but reports the settled outcome (banner spinner/hide). */
    void installServer(String serverId, java.util.function.Consumer<Boolean> onResult) {
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
        if (ops.lspAvailable(serverId)) {
            host.setStatus(tr("status.install.already", serverName(serverId)));
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
            installingServers.add(serverId);
            host.setStatus(tr("status.install.installing", serverName(serverId)));
            service.install(
                    steps, ops.configDir(), id -> host.setStatus(tr("status.install.installingTool", id)), result -> {
                        installingServers.remove(serverId);
                        if (result.ok()) {
                            if (result.installedCommand() != null) {
                                applyServerCommand(host.settings(), serverId, result.installedCommand());
                            }
                            ops.reapplyToolSupport();
                            host.setStatus(tr("status.install.done", serverName(serverId)));
                        } else {
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
            case "lua" -> s.setLuaLspCommand(command);
            case "xml" -> s.setXmlLspCommand(command);
            case "terraform" -> s.setTerraformLspCommand(command);
            case "typst" -> s.setTypstLspCommand(command); // tinymist: a binary archive, path must be persisted
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
                host.settings().setJavaLspCommand(launcher.toString());
            }
        }
        ops.reapplyToolSupport();
    }

    /** Maps a catalog step to its current detected-availability via the existing per-feature detectors. */
    private boolean stepInstalled(Step step) {
        return switch (step.id()) {
            case "jdtls" -> ops.lspAvailable("java");
            case "java-debug" -> ops.dapAvailable("java");
            case "pyright" -> ops.lspAvailable("python");
            case "debugpy" -> ops.dapAvailable("python");
            case "typescript-language-server" -> ops.lspAvailable("typescript");
            case "js-debug" -> ops.dapAvailable("javascript");
            case "mmdc" -> ops.mmdcAvailable();
            default -> false;
        };
    }

    private String langName(Lang lang) {
        return tr("install.lang." + lang.name().toLowerCase(Locale.ROOT));
    }

    private String prereqName(Prereq p) {
        return tr("install.prereq." + p.name().toLowerCase(Locale.ROOT));
    }

    private void alert(Alert.AlertType type, String message) {
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
