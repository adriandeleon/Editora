package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

import static com.editora.i18n.Messages.tr;

/**
 * The "Detected" control of an external tool's Settings rows, the same on every page (LSP, Debugging, Mermaid,
 * Diagrams, Typst, Build Tools, Web, AI Agent): one pill that reads "<tool>: found (<version or path>)" or
 * "<tool>: not found", coloured by {@link SettingsWindow#pillClass}, then an optional Install… button and an
 * optional link (Check again, How to install). The pages used to word and lay this out each their own way.
 */
final class ToolStatusRow {

    private String tool;
    private final Label pill = new Label();
    private final HBox node = new HBox(8, pill);
    private Button install;
    private Hyperlink missingOnly;
    private final javafx.scene.control.Tooltip tooltip = new javafx.scene.control.Tooltip();

    ToolStatusRow(String tool) {
        this.tool = tool;
        pill.getStyleClass().add("settings-git-status");
        // One line: a long path is cut in the middle (the tool name and the file name stay), whole in the tooltip.
        pill.setMaxWidth(380);
        pill.setTextOverrun(javafx.scene.control.OverrunStyle.CENTER_ELLIPSIS);
        pill.setTooltip(tooltip);
        node.setAlignment(Pos.CENTER_RIGHT);
        node.getStyleClass().add("settings-tool-status");
        checking();
    }

    /** Adds an Install… button running {@code action}; it reads "Installed" and greys out once the tool is found. */
    ToolStatusRow install(Runnable action) {
        install = new Button(tr("settings.install.button"));
        install.getStyleClass().add("settings-install-button");
        install.setMinWidth(Region.USE_PREF_SIZE);
        install.setOnAction(e -> action.run());
        node.getChildren().add(install);
        return this;
    }

    /** Adds a link after the pill (and the button): "Check again", "How to install". */
    ToolStatusRow link(String text, Runnable action) {
        Hyperlink link = new Hyperlink(text);
        link.setMinWidth(Region.USE_PREF_SIZE);
        link.setOnAction(e -> action.run());
        node.getChildren().add(link);
        return this;
    }

    /** Adds a link shown only while the tool is not found (Diagrams' "How to install": it has no installer). */
    ToolStatusRow linkWhenMissing(String text, Runnable action) {
        link(text, action);
        missingOnly = (Hyperlink) node.getChildren().get(node.getChildren().size() - 1);
        missingOnly.managedProperty().bind(missingOnly.visibleProperty());
        return this;
    }

    /** Puts {@code first} before the pill (the AI Agent page's "In use" marker). */
    ToolStatusRow lead(Node first) {
        node.getChildren().add(0, first);
        return this;
    }

    Node node() {
        return node;
    }

    Label pill() {
        return pill;
    }

    /** The Install… button, or null when the row has none. */
    Button installButton() {
        return install;
    }

    /** Renames the tool (its command changed, so its program did). */
    void tool(String name) {
        this.tool = name;
    }

    /** "<tool>: checking…", in the plain pill, while a probe runs. */
    void checking() {
        pill.getStyleClass().setAll("settings-git-status");
        pill.setText(tr("settings.tool.checking", tool));
        tooltip.setText(pill.getText());
    }

    /**
     * Shows a probe's result: {@code detail} is the version or path found (blank: none to show). Red only while
     * {@code featureOn}; a tool missing for a feature that is off is a fact, not a fault.
     */
    void show(boolean found, String detail, boolean featureOn) {
        pill.getStyleClass().setAll("settings-git-status", SettingsWindow.pillClass(found, featureOn));
        pill.setText(text(tool, found, detail));
        tooltip.setText(pill.getText());
        if (missingOnly != null) {
            missingOnly.setVisible(!found);
        }
        if (install != null) {
            install.setDisable(found);
            install.setText(found ? tr("settings.install.installed") : tr("settings.install.button"));
        }
    }

    /**
     * Shows a tool that is there but unusable, in the pill's own words ("lemminx-maven: installed but incomplete —
     * click Install… to repair"): red while {@code featureOn}, with Install… offered as for a missing tool.
     */
    void showProblem(String text, boolean featureOn) {
        pill.getStyleClass().setAll("settings-git-status", SettingsWindow.pillClass(false, featureOn));
        pill.setText(text);
        tooltip.setText(text);
        if (missingOnly != null) {
            missingOnly.setVisible(true);
        }
        if (install != null) {
            install.setDisable(false);
            install.setText(tr("settings.install.button"));
        }
    }

    /** The pill's text. Pure. */
    static String text(String tool, boolean found, String detail) {
        if (!found) {
            return tr("settings.tool.notFound", tool);
        }
        return detail == null || detail.isBlank()
                ? tr("settings.tool.foundBare", tool)
                : tr("settings.tool.found", tool, detail.strip());
    }

    /** The program's name: the file name of the command's first word ({@code fallback} when there is none). */
    static String toolName(List<String> argv, String fallback) {
        if (argv == null || argv.isEmpty() || argv.get(0).isBlank()) {
            return fallback;
        }
        try {
            Path name = Path.of(argv.get(0)).getFileName();
            return name == null ? fallback : name.toString();
        } catch (RuntimeException e) {
            return argv.get(0);
        }
    }

    /** Where the command's program is (the configured path, or the PATH entry) — a lookup, no process — or "". */
    static String pathOf(List<String> argv) {
        return com.editora.doctor.DoctorProbes.resolvedPath(argv);
    }
}
