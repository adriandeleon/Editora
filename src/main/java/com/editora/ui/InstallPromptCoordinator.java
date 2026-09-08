package com.editora.ui;

import javafx.stage.Stage;

import com.editora.config.ConfigManager;
import com.editora.editor.EditorBuffer;

import static com.editora.i18n.Messages.tr;

/** Offers language-support installation and remembers dismissed buffer prompts. */
final class InstallPromptCoordinator {
    interface Host {
        Stage stage();

        ConfigManager config();

        OverlayHost overlayHost();

        InstallCoordinator installCoordinator();

        WindowChromeCoordinator chrome();

        MermaidCoordinator mermaid();

        LspCoordinator lspCoordinator();

        boolean isLocalBuffer(EditorBuffer b);

        boolean lspEnabled();
    }

    private final java.util.Set<EditorBuffer> installDismissed =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    private final Host host;

    InstallPromptCoordinator(Host host) {
        this.host = host;
    }

    /**
     * Shows the in-editor "install language support?" banner on {@code buffer} when the file's language has an
     * installer (Java/Python/JS/Mermaid), the relevant feature is enabled but that language server (or the
     * Mermaid CLI) isn't installed, the user hasn't dismissed it for this buffer, and the master nudge toggle
     * is on. Otherwise it hides the banner. Driven on tab switch / addBuffer / after an install.
     */
    void maybeOfferInstall(EditorBuffer buffer) {
        if (buffer == null) {
            return;
        }
        if (!installPromptsEligible(buffer)) {
            buffer.showInstallBar(false);
            return;
        }
        // A live LSP session already serving this file ⇒ its server is present; never nag.
        if (host.lspCoordinator().isManaged(buffer.getPath())) {
            buffer.showInstallBar(false);
            return;
        }
        // The rich bundles (Java/Python/JS LSP+DAP, Mermaid CLI) first…
        java.util.Optional<com.editora.install.InstallCatalog.Lang> lang =
                com.editora.install.InstallCatalog.forBufferLanguage(buffer.getLanguage());
        if (lang.isPresent() && langSupportMissing(lang.get())) {
            com.editora.install.InstallCatalog.Lang l = lang.get();
            offerInstall(
                    buffer,
                    tr("install.lang." + l.name().toLowerCase(java.util.Locale.ROOT)),
                    "install.banner.message",
                    cb -> host.installCoordinator().installSupport(l, cb));
            return;
        }
        // A pom.xml prefers the Maven-aware server (JVM lemminx + lemminx-maven) — offer *that* when it's
        // enabled but not installed, rather than the native lemminx the generic branch below would pick (which
        // gives base XML but no dependency/plugin/GAV completion — the whole point of opening this banner).
        String pomServer = com.editora.lsp.LspServerRegistry.MAVEN_POM_SERVER_ID;
        boolean isPom = buffer.getPath() != null
                && buffer.getPath().getFileName() != null
                && com.editora.lsp.LspServerRegistry.isPomFile(
                        buffer.getPath().getFileName().toString());
        if (isPom
                && host.lspEnabled()
                && host.lspCoordinator().serverEnabled(pomServer)
                && host.lspCoordinator().isServerMissing(pomServer)
                && com.editora.install.InstallCatalog.installableServerIds().contains(pomServer)) {
            offerInstall(
                    buffer,
                    host.installCoordinator().serverName(pomServer),
                    "install.banner.serverMessage",
                    cb -> host.installCoordinator().installServer(pomServer, cb));
            return;
        }
        // …then the LSP-only servers (json/bash/yaml/dockerfile/toml/typst/…). This offers the *language
        // server* (code intelligence) — distinct from a language's render/run tool that may already work
        // (e.g. the typst preview renders via the typst CLI even when tinymist isn't installed), so the
        // banner says "language server", not "language support".
        String serverId = com.editora.lsp.LspServerRegistry.serverIdFor(buffer.getLanguage());
        if (serverId != null
                && host.lspEnabled()
                && host.lspCoordinator().isServerMissing(serverId)
                && com.editora.install.InstallCatalog.installableServerIds().contains(serverId)) {
            String id = serverId;
            offerInstall(
                    buffer,
                    host.installCoordinator().serverName(id),
                    "install.banner.serverMessage",
                    cb -> host.installCoordinator().installServer(id, cb));
            return;
        }
        buffer.showInstallBar(false);
    }

    /** Builds + shows the install banner for {@code buffer} with a display name and an install trigger that
     *  is handed a settled-callback (so it can spin the banner + hide on success). */
    void offerInstall(
            EditorBuffer buffer,
            String displayName,
            String messageKey,
            java.util.function.Consumer<java.util.function.Consumer<Boolean>> installer) {
        buffer.setInstallPrompt(
                tr(messageKey, displayName),
                tr("install.banner.install"),
                () -> {
                    buffer.setInstallBarBusy(true);
                    installer.accept(ok -> {
                        buffer.setInstallBarBusy(false);
                        if (ok) {
                            buffer.showInstallBar(false);
                        }
                    });
                },
                () -> {
                    installDismissed.add(buffer);
                    buffer.showInstallBar(false);
                });
        buffer.showInstallBar(true);
    }

    /** The common gates for offering the install banner (toggle on, not Simple, not dismissed, local file). */
    boolean installPromptsEligible(EditorBuffer buffer) {
        return host.config().getSettings().isLspInstallPrompts()
                && !host.chrome().simpleModeActive()
                && !installDismissed.contains(buffer)
                && host.isLocalBuffer(buffer);
    }

    /** Whether the rich bundle for {@code lang} is missing its primary tool (feature on but tool absent). */
    boolean langSupportMissing(com.editora.install.InstallCatalog.Lang lang) {
        return switch (lang) {
            case JAVA -> host.lspEnabled() && host.lspCoordinator().isServerMissing("java");
            case PYTHON -> host.lspEnabled() && host.lspCoordinator().isServerMissing("python");
            case JAVASCRIPT -> host.lspEnabled() && host.lspCoordinator().isServerMissing("typescript");
            case MERMAID ->
                host.mermaid().isEnabled()
                        && host.mermaid().mmdcDetected()
                        && !host.mermaid().mmdcAvailable();
        };
    }

    /** {@code install.languageServer}: pick an installable LSP server (json/bash/go/…) and install it. */
    void chooseInstallServer() {
        QuickOpen<String> picker = new QuickOpen<>(
                tr("command.install.languageServer"),
                tr("palette.install.prompt"),
                () -> new java.util.ArrayList<>(com.editora.install.InstallCatalog.installableServerIds()),
                host.installCoordinator()::serverName,
                id -> "",
                id -> {
                    if (id != null) {
                        host.installCoordinator().installServer(id);
                    }
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.stage());
    }
}
