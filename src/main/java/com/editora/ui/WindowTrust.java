package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import static com.editora.i18n.Messages.tr;

/**
 * A window's folder-trust questions and commands: the HTTP Client's secret-read and response-handler gates, Manage
 * and Revoke Trust, and saving a trust decision so every window re-resolves what its project may override. It
 * lived inside {@code MainController}; it is a file of its own only because of that file's size (see
 * {@link WindowCommandHost}).
 */
final class WindowTrust {

    private final MainController mc;

    WindowTrust(MainController mc) {
        this.mc = mc;
    }

    /** Opens Settings on the Workspace page, where the trusted-folder list lives ({@code workspace.manageTrust}). */
    void showTrustedFolders() {
        mc.settingsWindow.refreshTrustedFolders();
        mc.settingsWindow.showWorkspace(mc.stage);
    }

    /** Persists a trust change and lets every window re-resolve what its project may now override. */
    void saveTrustDecision() {
        mc.config.saveTrust();
        if (mc.windowManager != null) {
            mc.windowManager.broadcastTrustChanged();
        }
    }

    /** Another window (or this one) changed folder trust: stop honouring overrides that lost it. */
    void trustChanged() {
        mc.lspCoordinator.reloadProjectSettings();
    }

    /**
     * Revokes trust for the active window's project root ({@code workspace.revokeTrust}) — the quick undo for
     * a folder trusted by mistake, without hunting for it in the Settings list. Only an <em>explicit</em>
     * entry for this exact root is dropped; trust inherited from an ancestor has to be revoked at that
     * ancestor, which the status message says so the user is never told "revoked" while it still runs.
     */
    void revokeTrustForActiveRoot() {
        java.nio.file.Path root = mc.windowProject != null ? mc.windowProject.rootPath() : null;
        if (root == null) {
            mc.setStatus(tr("status.trust.noProject"));
            return;
        }
        mc.config.getTrustStore().revoke(root);
        saveTrustDecision();
        mc.settingsWindow.refreshTrustedFolders();
        mc.setStatus(tr(
                mc.config.getTrustStore().isTrusted(root) ? "status.trust.revokedButInherited" : "status.trust.revoked",
                root.getFileName() == null
                        ? root.toString()
                        : root.getFileName().toString()));
    }

    /**
     * The HTTP Client's secrets gate: a {@code .http} request in {@code file} that reads {@code reads} (the secret
     * sources it resolves, as written — {@code $env TOKEN}, {@code $dotenv KEY}) runs only from a trusted folder,
     * the same trust store as build wrappers. Asked about the open project when the file is inside it, else the
     * file's own folder — both canonical, the way the trust store compares them (see {@link HttpSecretGate#trustRoot}).
     */
    boolean confirmHttpSecretReads(Path file, List<String> hosts, List<String> reads) {
        Path dir = com.editora.config.PathKeys.canonical(
                java.util.Objects.requireNonNull(file.toAbsolutePath().getParent(), "a file is in a folder"));
        if (mc.config.getTrustStore().isTrusted(dir)) {
            return true;
        }
        Path root = HttpSecretGate.trustRoot(file, mc.projectRoot());
        TrustPrompts.Prompt prompt = TrustPrompts.httpSecrets(mc.stage, root, reads, hosts);
        if (Dialogs.showAndWait(prompt.alert()).orElse(Dialogs.cancelOf(prompt.alert())) != prompt.trust()) {
            return false;
        }
        mc.config.getTrustStore().trust(root);
        saveTrustDecision(); // durable: a security decision must survive a crash
        return true;
    }

    /** Whether the response handlers of {@code file} may run: its folder is trusted, or the user trusts it now. */
    boolean confirmHttpScripts(Path file) {
        Path dir = com.editora.config.PathKeys.canonical(
                java.util.Objects.requireNonNull(file.toAbsolutePath().getParent(), "a file is in a folder"));
        if (mc.config.getTrustStore().isTrusted(dir)) {
            return true;
        }
        Path root = HttpSecretGate.trustRoot(file, mc.projectRoot());
        TrustPrompts.Prompt prompt = TrustPrompts.httpScripts(mc.stage, root, file);
        if (Dialogs.showAndWait(prompt.alert()).orElse(Dialogs.cancelOf(prompt.alert())) != prompt.trust()) {
            return false;
        }
        mc.config.getTrustStore().trust(root);
        saveTrustDecision(); // durable: a security decision must survive a crash
        return true;
    }
}
