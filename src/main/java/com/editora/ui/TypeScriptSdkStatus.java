package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import javafx.application.Platform;

import com.editora.install.TypeScriptServerInstall;
import com.editora.lsp.LspManager;
import com.editora.lsp.TypeScriptServer;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The Settings → LSP TypeScript card's "TypeScript" line: which TypeScript the TypeScript / JavaScript server
 * runs outside a trusted folder with its own, or that there is none it can run — the card used to show a green
 * "Installed" while the server could not start (Node review B1, E4). A TypeScript 6 or newer is shown in amber with
 * Install… offered: it leaves out an installed {@code @types/node} (JS review B101), and Install puts TypeScript 5.9
 * in Editora's own folder, which the server then prefers. The lookup (PATH, links, package.json) runs off the FX
 * thread; the row is updated on it.
 */
final class TypeScriptSdkStatus {

    private TypeScriptSdkStatus() {}

    /** How the line looks. */
    enum Look {
        FOUND,
        WARNING,
        PROBLEM,
        MISSING
    }

    /** What the line says: how it looks, the pill's text (the tool name for FOUND/MISSING), and its detail. */
    record Shown(Look look, String text, String detail) {}

    /** The line for an {@link TypeScriptServer.Sdk}. Pure apart from {@code tr}. */
    static Shown shown(TypeScriptServer.Sdk sdk) {
        Path tsserver = sdk.tsserver();
        if (tsserver != null) {
            String version = sdk.version();
            String where = version.isEmpty() ? tsserver.toString() : version + " — " + tsserver;
            if (sdk.own()) {
                return new Shown(Look.FOUND, "TypeScript", tr("settings.lsp.typescriptSdkOwn", where));
            }
            if (!sdk.suitable()) {
                return new Shown(
                        Look.WARNING,
                        tr("settings.lsp.typescriptSdkTooNew", version),
                        tr("doctor.tip.typescriptSdkTooNewOwn", version));
            }
            return new Shown(Look.FOUND, "TypeScript", where);
        }
        return sdk.besideVersion().isEmpty()
                ? new Shown(Look.MISSING, "TypeScript", "")
                : new Shown(Look.PROBLEM, tr("settings.lsp.typescriptSdkNoTsserver", sdk.besideVersion()), "");
    }

    /** Probes {@code argv}'s server off the FX thread and shows the result in {@code row}. */
    static void refresh(@Nullable ToolStatusRow row, List<String> argv, @Nullable Path configDir, boolean featureOn) {
        if (row == null) {
            return;
        }
        row.checking();
        Thread probe = new Thread(
                () -> {
                    Path exe = LspManager.typeScriptServerExecutable(argv);
                    Shown shown = shown(TypeScriptServer.sdk(exe, TypeScriptServerInstall.ownTsserver(configDir)));
                    Platform.runLater(() -> {
                        switch (shown.look()) {
                            case FOUND -> {
                                row.tool(shown.text());
                                row.show(true, shown.detail(), featureOn);
                            }
                            case MISSING -> {
                                row.tool(shown.text());
                                row.show(false, "", featureOn);
                            }
                            case WARNING -> row.showWarning(shown.text(), shown.detail());
                            case PROBLEM -> row.showProblem(shown.text(), featureOn);
                        }
                    });
                },
                "typescript-sdk-probe");
        probe.setDaemon(true);
        probe.start();
    }
}
