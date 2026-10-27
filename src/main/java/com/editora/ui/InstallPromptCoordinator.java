package com.editora.ui;

import javafx.stage.Stage;

import com.editora.config.ConfigManager;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

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

        /** Turns the language-server feature on (the Python banner's "Turn on", review B15). */
        default void turnOnLsp() {}

        /** Opens Settings on the language-server page (a configured server command was not found, B8). */
        default void openLspSettings() {}
    }

    /**
     * The languages whose first file in this window already asked to turn language servers on: Python (review
     * B15) and XML (XML review C13) — asked once each, answered or not.
     */
    private final java.util.Set<String> turnOnOffered = new java.util.HashSet<>();

    /** The buffer showing that offer until it is answered (weakly: a closed tab lets it go). */
    private java.lang.ref.WeakReference<EditorBuffer> turnOnOfferBuffer = new java.lang.ref.WeakReference<>(null);

    private final java.util.Set<EditorBuffer> installDismissed =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /**
     * What "Not now" was said to in this window, by server id (or language bundle): every file of that kind stops
     * asking until the window closes. Per tab, the XML banner came back on every XML and SVG file (XML review
     * A17/C12).
     */
    private final java.util.Set<String> dismissedKinds = new java.util.HashSet<>();

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
    void maybeOfferInstall(@Nullable EditorBuffer buffer) {
        if (buffer == null) {
            return;
        }
        if (!installPromptsEligible(buffer)) {
            buffer.showInstallBar(false);
            return;
        }
        // Language servers are off (the default): the first Python or XML file this window opens asks once whether
        // to turn them on, since nothing else says their code intelligence exists (review B15, XML review C13).
        if (!host.lspEnabled() && buffer == turnOnOfferBuffer.get()) {
            return; // still unanswered: the offer stays on its tab
        }
        String turnOnKind = host.lspEnabled() || host.chrome().simpleModeActive() ? null : turnOnKind(buffer);
        if (turnOnKind != null && turnOnOffered.add(turnOnKind)) {
            turnOnOfferBuffer = new java.lang.ref.WeakReference<>(buffer);
            buffer.setInstallPrompt(
                    tr("python".equals(turnOnKind) ? "install.banner.enablePython" : "install.banner.enableXml"),
                    tr("install.banner.turnOn"),
                    () -> {
                        turnOnOfferBuffer.clear();
                        buffer.showInstallBar(false);
                        host.turnOnLsp();
                    },
                    () -> {
                        turnOnOfferBuffer.clear();
                        buffer.showInstallBar(false);
                    });
            buffer.showInstallBar(true);
            return;
        }
        // A pom.xml prefers the Maven-aware server (JVM lemminx + lemminx-maven). When that one is missing, the pom
        // is served by the plain XML server, so the "already served" check below would hide the offer for good —
        // it is asked first (review 2, A1/E2).
        if (offerPomServer(buffer)) {
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
        String custom = lang.isPresent() && langSupportMissing(lang.get())
                ? host.lspCoordinator().customServerCommand(bundleServerId(lang.get()))
                : null;
        if (custom != null) {
            // The user chose another server (pylsp, basedpyright, a path) and it is missing: installing the
            // bundle would install Pyright and leave that setting pointing at nothing (review B8).
            offerSettings(buffer, "lang:" + lang.get().name(), custom);
            return;
        }
        if (lang.isPresent() && langSupportMissing(lang.get())) {
            com.editora.install.InstallCatalog.Lang l = lang.get();
            offerInstall(
                    buffer,
                    "lang:" + l.name(),
                    tr("install.banner.message", tr("install.lang." + l.name().toLowerCase(java.util.Locale.ROOT))),
                    cb -> host.installCoordinator().installSupport(l, cb));
            return;
        }
        // …then the LSP-only servers (json/bash/yaml/dockerfile/toml/typst/…). This offers the *language
        // server* (code intelligence) — distinct from a language's render/run tool that may already work
        // (e.g. the typst preview renders via the typst CLI even when tinymist isn't installed), so the
        // banner says "language server", not "language support".
        // Only for a server that is switched on: one the user turned off is not asked about, and installing it
        // would still run nothing (XML review A5).
        String serverId = com.editora.lsp.LspServerRegistry.serverIdFor(buffer.getLanguage());
        if (serverId != null
                && host.lspEnabled()
                && host.lspCoordinator().serverEnabled(serverId)
                && host.lspCoordinator().isServerMissing(serverId)
                && com.editora.install.InstallCatalog.installableServerIds().contains(serverId)) {
            String id = serverId;
            String customCommand = host.lspCoordinator().customServerCommand(id);
            if (customCommand != null) {
                // The user's own command for it is missing: Install would replace that setting with Editora's
                // download, so lead to Settings instead, as for the language bundles (review B8, XML review A6).
                offerSettings(buffer, id, customCommand);
                return;
            }
            offerInstall(
                    buffer,
                    id,
                    serverInstallMessage(id),
                    cb -> host.installCoordinator().installServer(id, cb));
            return;
        }
        buffer.showInstallBar(false);
    }

    /**
     * The pom.xml server's own offer, made whether or not the plain XML server already serves the pom: Install
     * (with what it adds and downloads) when the Maven server is switched on and not installed, or Settings… when
     * the user's own command for it is missing (Install would overwrite it). Not for a pom kept on the plain XML
     * server because its folder is not trusted: Installing would change nothing there, and the trust offer is
     * shown instead. True when this decided the banner.
     */
    private boolean offerPomServer(EditorBuffer buffer) {
        String pomServer = com.editora.lsp.LspServerRegistry.MAVEN_POM_SERVER_ID;
        java.nio.file.Path path = buffer.getPath();
        boolean isPom = path != null
                && path.getFileName() != null
                && com.editora.lsp.LspServerRegistry.isPomFile(
                        path.getFileName().toString());
        LspCoordinator lsp = host.lspCoordinator();
        if (!isPom
                || !host.lspEnabled()
                || !lsp.serverEnabled(pomServer)
                || !lsp.isServerMissing(pomServer)
                || !com.editora.install.InstallCatalog.installableServerIds().contains(pomServer)
                || lsp.pomWithheldUntrusted(buffer)) {
            return false;
        }
        String custom = lsp.customServerCommand(pomServer);
        if (custom != null) {
            offerSettings(buffer, pomServer, custom);
        } else {
            offerInstall(
                    buffer,
                    pomServer,
                    tr(
                            "install.banner.mavenPomServer",
                            com.editora.install.InstallCatalog.LEMMINX_MAVEN_VERSION,
                            com.editora.install.InstallCatalog.LEMMINX_MAVEN_DOWNLOAD_MB),
                    cb -> host.installCoordinator().installServer(pomServer, cb));
        }
        return true;
    }

    /**
     * Which language's first file asks to turn language servers on: {@code python}, or {@code xml} unless the XML
     * server is switched off; else null.
     */
    private @Nullable String turnOnKind(EditorBuffer buffer) {
        if (buffer.isPython()) {
            return "python";
        }
        boolean xml = "xml".equals(com.editora.lsp.LspServerRegistry.serverIdFor(buffer.getLanguage()));
        return xml && host.lspCoordinator().serverEnabled("xml") ? "xml" : null;
    }

    /**
     * The install banner's words for an LSP-only server: for XML, what the server adds and what Install downloads
     * from where (XML review A17/C12); for the others, that it is not installed.
     */
    private String serverInstallMessage(String serverId) {
        if ("xml".equals(serverId)) {
            return tr(
                    "install.banner.xmlServer",
                    com.editora.install.InstallCatalog.LEMMINX_SERVER_VERSION,
                    com.editora.install.InstallCatalog.LEMMINX_DOWNLOAD_MB);
        }
        return tr("install.banner.serverMessage", host.installCoordinator().serverName(serverId));
    }

    /** The banner for a configured server command that is not there: Settings…, not Install. */
    private void offerSettings(EditorBuffer buffer, String kind, String command) {
        if (dismissedKinds.contains(kind)) {
            buffer.showInstallBar(false);
            return;
        }
        buffer.setInstallPrompt(
                tr("install.banner.commandMissing", command),
                tr("install.banner.openSettings"),
                host::openLspSettings,
                () -> {
                    dismissedKinds.add(kind);
                    installDismissed.add(buffer);
                    buffer.showInstallBar(false);
                });
        buffer.showInstallBar(true);
    }

    /** Builds + shows the install banner for {@code buffer} with its message and an install trigger that is
     *  handed a settled-callback (so it can spin the banner + hide on success). "Not now" holds for every file
     *  of the same {@code kind} (a server id, or {@code lang:<bundle>}) until the window closes. */
    void offerInstall(
            EditorBuffer buffer,
            String kind,
            String message,
            java.util.function.Consumer<java.util.function.Consumer<Boolean>> installer) {
        if (dismissedKinds.contains(kind)) {
            buffer.showInstallBar(false);
            return;
        }
        buffer.setInstallPrompt(
                message,
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
                    dismissedKinds.add(kind);
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

    /** The language server a bundle installs ({@code null} for Mermaid, which has none). */
    static @Nullable String bundleServerId(com.editora.install.InstallCatalog.Lang lang) {
        return switch (lang) {
            case JAVA -> "java";
            case PYTHON -> "python";
            case JAVASCRIPT -> "typescript";
            case MERMAID -> null;
        };
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
