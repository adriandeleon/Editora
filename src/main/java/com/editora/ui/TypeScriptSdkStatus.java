package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import javafx.application.Platform;

import com.editora.lsp.LspManager;
import com.editora.lsp.TypeScriptServer;

import static com.editora.i18n.Messages.tr;

/**
 * The Settings → LSP TypeScript card's "TypeScript" line: which TypeScript the TypeScript / JavaScript server
 * runs outside a trusted folder with its own, or that there is none it can run — the card used to show a green
 * "Installed" while the server could not start (Node review B1, E4). The lookup (PATH, links, package.json) runs
 * off the FX thread; the row is updated on it.
 */
final class TypeScriptSdkStatus {

    private TypeScriptSdkStatus() {}

    /** What the line says: whether a tsserver was found, the pill's tool name, and its detail. */
    record Shown(boolean found, String tool, String detail) {}

    /** The line for a server executable's surroundings. Pure apart from {@code tr}. */
    static Shown shown(Path tsserver, String tsserverVersion, String packageVersion) {
        if (tsserver != null) {
            String detail = tsserverVersion.isEmpty() ? tsserver.toString() : tsserverVersion + " — " + tsserver;
            return new Shown(true, "TypeScript", detail);
        }
        return packageVersion.isEmpty()
                ? new Shown(false, "TypeScript", "")
                : new Shown(false, tr("settings.lsp.typescriptSdkNoTsserver", packageVersion), "");
    }

    /** Probes {@code argv}'s server off the FX thread and shows the result in {@code row}. */
    static void refresh(ToolStatusRow row, List<String> argv, boolean featureOn) {
        if (row == null) {
            return;
        }
        row.checking();
        Thread probe = new Thread(
                () -> {
                    Path exe = LspManager.typeScriptServerExecutable(argv);
                    Path tsserver = TypeScriptServer.installedTsserver(exe);
                    Shown shown = shown(
                            tsserver,
                            TypeScriptServer.sdkVersion(tsserver),
                            TypeScriptServer.installedPackageVersion(exe));
                    Platform.runLater(() -> {
                        row.tool(shown.tool());
                        row.show(shown.found(), shown.detail(), featureOn);
                    });
                },
                "typescript-sdk-probe");
        probe.setDaemon(true);
        probe.start();
    }
}
