package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The folder-trust prompts: trusting a folder to send its secrets from a {@code .http} request, to run the response
 * handlers of its {@code .http} files, to run its build (wrapper, pom plugins, build scripts), and the plain Trust
 * Folder (Maven-aware pom editing, schema downloads, and all of the above). The first three open by themselves while the user may still be typing (the HTTP one after the run has started), and
 * trusting is remembered, so <b>Cancel is the default and focused button</b>: a stray Enter or Space never trusts a
 * folder. Built here, on the FX thread, so a test can check the real dialog without showing a window of its own.
 */
final class TrustPrompts {

    private TrustPrompts() {}

    /** A prompt and the button that trusts. */
    record Prompt(Alert alert, ButtonType trust) {}

    /** The prompt for a {@code .http} request in {@code root} that reads {@code reads} and sends them to {@code hosts}. */
    static Prompt httpSecrets(Window owner, Path root, List<String> reads, List<String> hosts) {
        Path name = root.getFileName();
        ButtonType trust = new ButtonType(tr("dialog.httpTrust.accept"), ButtonBar.ButtonData.OK_DONE);
        Alert confirm = new Alert(
                Alert.AlertType.WARNING,
                tr("dialog.httpTrust.body", String.join(", ", reads), String.join(", ", hosts), root.toString())
                        + "\n\n" + tr("dialog.httpTrust.redirects"),
                ButtonType.CANCEL,
                trust);
        confirm.setTitle(tr("dialog.trust.title"));
        confirm.setHeaderText(tr("dialog.httpTrust.header", name == null ? root.toString() : name.toString()));
        return finish(confirm, owner, trust);
    }

    /** The prompt for running the JavaScript response handlers of {@code file}, in {@code root}, on Node.js. */
    static Prompt httpScripts(Window owner, Path root, Path file) {
        Path name = root.getFileName();
        ButtonType trust = new ButtonType(tr("dialog.httpScripts.accept"), ButtonBar.ButtonData.OK_DONE);
        Path fileName = file.getFileName();
        Alert confirm = new Alert(
                Alert.AlertType.WARNING,
                tr(
                        "dialog.httpScripts.body",
                        fileName == null ? file.toString() : fileName.toString(),
                        root.toString()),
                ButtonType.CANCEL,
                trust);
        confirm.setTitle(tr("dialog.trust.title"));
        confirm.setHeaderText(tr("dialog.httpScripts.header", name == null ? root.toString() : name.toString()));
        return finish(confirm, owner, trust);
    }

    /**
     * The prompt for the first build in {@code root}: what it will run (the wrapper if any, the pom's plugins,
     * {@code .mvn/} files, a project.json override — {@link com.editora.build.BuildTrust}), one line each.
     */
    static Prompt buildProject(Window owner, @Nullable Path root, com.editora.build.BuildTrust.Summary summary) {
        // root is a folder (the wrapper's, or the project's) at every caller; typed nullable by Path.getParent()
        Path name = root == null ? null : root.getFileName();
        String shown = String.valueOf(root);
        ButtonType trust = new ButtonType(tr("dialog.trust.accept"), ButtonBar.ButtonData.OK_DONE);
        StringBuilder items = new StringBuilder();
        for (String item : summary.items()) {
            items.append("• ").append(item).append('\n');
        }
        Alert confirm = new Alert(
                Alert.AlertType.WARNING,
                tr("dialog.trust.projectBody", items.toString().strip(), shown),
                ButtonType.CANCEL,
                trust);
        confirm.setTitle(tr("dialog.trust.title"));
        confirm.setHeaderText(tr("dialog.trust.projectHeader", name == null ? shown : name.toString()));
        return finish(confirm, owner, trust);
    }

    /**
     * The prompt of Trust Folder ({@code workspace.trustFolder}, and the button on an XML file's notice): what a
     * trusted {@code root} may do, Maven-aware pom editing and schema downloads first (XML/pom review 2: E1, C6).
     */
    static Prompt folder(@Nullable Window owner, Path root) {
        Path name = root.getFileName();
        ButtonType trust = new ButtonType(tr("dialog.folderTrust.accept"), ButtonBar.ButtonData.OK_DONE);
        Alert confirm = new Alert(
                Alert.AlertType.WARNING, tr("dialog.folderTrust.body", root.toString()), ButtonType.CANCEL, trust);
        confirm.setTitle(tr("dialog.trust.title"));
        confirm.setHeaderText(tr("dialog.folderTrust.header", name == null ? root.toString() : name.toString()));
        return finish(confirm, owner, trust);
    }

    private static Prompt finish(Alert confirm, @Nullable Window owner, ButtonType trust) {
        if (owner != null) {
            confirm.initOwner(owner);
        }
        confirm.getDialogPane().setMinWidth(520);
        Node trustNode = confirm.getDialogPane().lookupButton(trust);
        Node cancelNode = confirm.getDialogPane().lookupButton(ButtonType.CANCEL);
        if (trustNode instanceof Button t) {
            t.setDefaultButton(false);
        }
        if (cancelNode instanceof Button c) {
            c.setDefaultButton(true); // Enter cancels; Escape already did
            confirm.setOnShown(e -> c.requestFocus()); // the focused button too, so Space cannot trust either
        }
        return new Prompt(confirm, trust);
    }
}
