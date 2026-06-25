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

        /** Invalidates the cached tool detection and re-applies LSP/Debug/Mermaid support (+ persists). */
        void reapplyToolSupport();
    }

    private final CoordinatorHost host;
    private final Ops ops;
    private final InstallService service = new InstallService();
    /** Languages with an install currently running (so a second trigger no-ops). */
    private final Set<Lang> installing = EnumSet.noneOf(Lang.class);

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
        if (installing.contains(lang)) {
            host.setStatus(tr("status.install.inProgress"));
            return;
        }
        List<Step> all = InstallCatalog.steps(lang);
        List<Step> missing = all.stream().filter(s -> !stepInstalled(s)).toList();
        if (missing.isEmpty()) {
            host.setStatus(tr("status.install.already", langName(lang)));
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
                    });
        });
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
