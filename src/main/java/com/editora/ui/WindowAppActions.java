package com.editora.ui;

import javafx.application.Platform;
import javafx.scene.control.Alert;

import static com.editora.i18n.Messages.tr;

/**
 * A window's help and app-level actions: the About dialog, the bundled and online documentation, the Application
 * Log and its find bar, and exporting the configuration folder. It lived inside {@code MainController}; it is a
 * file of its own only because of that file's size (see {@link WindowCommandHost}).
 */
final class WindowAppActions {

    private final MainController mc;

    WindowAppActions(MainController mc) {
        this.mc = mc;
    }

    /**
     * Opens the documentation site in the user's browser.
     *
     * <p>Versioned, via {@link com.editora.AppInfo#releaseVersion()} — the same path the command palette's
     * per-command help uses — so a running build lands on the docs for what it actually is rather than on
     * whatever is current, which for a snapshot build would describe features it does not have.
     */
    void openDocumentation() {
        // The link opener sends it to the copy bundled with this build when there is one (AppDocs).
        mc.openExternalUrl(com.editora.AppInfo.docsUrl());
    }

    /** {@code help.onlineDocumentation}: this version's docs on the website, even when the build carries a copy. */
    void openOnlineDocumentation() {
        if (mc.hostServices != null) {
            mc.hostServices.showDocument(com.editora.AppInfo.docsUrl());
        }
    }

    void onAbout() {
        AboutDialog.show(
                mc.stage,
                mc.config.getSettingsFile(),
                mc.fileWorkflows::openPath,
                mc::openExternalUrl,
                mc.registry::run,
                mc.config.isDev() ? com.editora.AppInfo.gitCommit() : "", // build commit shown only in --dev
                mc.updates); // what the last update check found, and "Check now"
    }

    /** Opens the Application Log's find bar, showing the window first ({@code debugLog.find}). */
    void findInDebugLog() {
        mc.debugLogWindow.find(mc.stage);
    }

    /** Opens the Application Log window: {@code view.debugLog} and Settings → Advanced's "Show Application Log". */
    void showDebugLog() {
        mc.debugLogWindow.show(mc.stage);
    }

    void exportConfig() {
        // Saved HTTP responses (bodies, URLs) and plugin data are asked about, off by default; the AI API keys are
        // never exported. Whether there are any is a listing of the config folder: made off the FX thread, the
        // question asked when it lands.
        var shared = mc.config.shared();
        java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> new boolean[] {shared.hasHttpHistory(), shared.hasPluginData()},
                        com.editora.process.BlockingTasks.named("export-config-scan"))
                .thenAcceptAsync(found -> exportConfig(found[0], found[1]), Platform::runLater);
    }

    void exportConfig(boolean askHttp, boolean askPluginData) {
        var choice = askHttp || askPluginData
                ? ExportConfigDialog.ask(mc.stage, askHttp, askPluginData)
                : java.util.Optional.of(new ExportConfigDialog.Choice(false, false));
        if (choice.isEmpty()) {
            return;
        }
        BackgroundTasks.Handle task = mc.backgroundTasks.start(tr("dialog.exportConfig.title"));
        // Off the FX thread: the export waits for pending writes and compresses the whole config directory.
        mc.config
                .shared()
                .exportConfigAsync(
                        choice.get().includeHttpHistory(), choice.get().includePluginData())
                .whenComplete((zip, failure) -> Platform.runLater(() -> {
                    task.done();
                    String detail = failure == null ? zip.toString() : String.valueOf(failure.getMessage());
                    mc.setStatus(tr(failure == null ? "status.config.exported" : "status.config.exportFailed", detail));
                    Alert alert = Dialogs.styled(
                            new Alert(failure == null ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR));
                    alert.initOwner(mc.stage);
                    alert.setTitle(tr("dialog.exportConfig.title"));
                    alert.setHeaderText(
                            tr(failure == null ? "dialog.exportConfig.done" : "dialog.exportConfig.failed"));
                    alert.setContentText(detail);
                    Dialogs.showAndWait(alert);
                }));
    }
}
