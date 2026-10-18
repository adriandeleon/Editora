package com.editora.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import static com.editora.i18n.Messages.tr;

/**
 * The plugin system's dialogs, one builder for every caller (the install flow, Settings, Remove): styled with
 * the app's stylesheet, owned by the window the user is working in, and titled "Plugins". The consent dialog
 * shows its capability list in a read-only, height-limited, scrollable box under a fixed header, so however
 * long a manifest is, the header, the list's start and the buttons stay on screen.
 */
final class PluginDialogs {

    private PluginDialogs() {}

    /** Rows of the capability box before it scrolls. */
    static final int CONSENT_ROWS = 12;

    /** Columns the dialogs' text boxes are sized for. */
    static final int COLUMNS = 64;

    /**
     * The consent question: {@code header} (already escaped), {@code intro} above the box, {@code capabilities}
     * inside it, the trust warning below, and {@code acceptLabel} / Cancel. Returns whether the user accepted.
     */
    static boolean consent(Window owner, String header, String intro, String capabilities, String acceptLabel) {
        ButtonType accept = new ButtonType(acceptLabel, ButtonBar.ButtonData.OK_DONE);
        Alert alert = Dialogs.styled(new Alert(Alert.AlertType.CONFIRMATION, "", accept, ButtonType.CANCEL));
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.setTitle(tr("dialog.plugins.title"));
        alert.setHeaderText(header);
        TextArea list = new TextArea(capabilities);
        list.setEditable(false);
        list.setWrapText(true);
        list.setPrefColumnCount(COLUMNS);
        list.setPrefRowCount(Math.min(CONSENT_ROWS, rows(capabilities)));
        list.setMaxHeight(320);
        list.getStyleClass().add("plugin-consent-list");
        list.setAccessibleText(header);
        Label introLabel = new Label(intro);
        introLabel.setWrapText(true);
        Label warn = new Label(tr("dialog.plugins.trustWarning"));
        warn.setWrapText(true);
        warn.getStyleClass().add("settings-hint");
        VBox content = new VBox(8, introLabel, list, warn);
        content.setPrefWidth(520);
        alert.getDialogPane().setContent(content);
        // Cancel is the default: Enter on a dialog that appeared mid-typing must not arm code.
        ((javafx.scene.control.Button) alert.getDialogPane().lookupButton(accept)).setDefaultButton(false);
        ((javafx.scene.control.Button) alert.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == accept;
    }

    /** A yes/no question with its own accept label ("Install", "Remove"); Cancel is the default button. */
    static boolean confirm(Window owner, String header, String body, String acceptLabel) {
        ButtonType accept = new ButtonType(acceptLabel, ButtonBar.ButtonData.OK_DONE);
        Alert alert = Dialogs.styled(new Alert(Alert.AlertType.CONFIRMATION, body, accept, ButtonType.CANCEL));
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.setTitle(tr("dialog.plugins.title"));
        alert.setHeaderText(header);
        alert.getDialogPane().setMinWidth(480);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == accept;
    }

    /** An error: {@code summary} as the header, {@code detail} in a box sized for its wrapped lines. */
    static void error(Window owner, String summary, String detail) {
        String body = detail == null || detail.isBlank() ? summary : detail.strip();
        Alert alert = Dialogs.styled(new Alert(Alert.AlertType.ERROR));
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.setTitle(tr("dialog.plugins.title"));
        alert.setHeaderText(summary);
        TextArea area = new TextArea(body);
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefColumnCount(COLUMNS);
        area.setPrefRowCount(Math.min(14, rows(body)));
        area.getStyleClass().add("git-error-text");
        alert.getDialogPane().setContent(area);
        alert.showAndWait();
    }

    /**
     * Rows {@code text} needs at {@link #COLUMNS} columns with wrapping, plus one — counting each line's wraps,
     * not just its line breaks (one long sentence used to get a two-row box that cut it off). Pure.
     */
    static int rows(String text) {
        if (text == null || text.isEmpty()) {
            return 2;
        }
        int rows = 0;
        for (String line : text.split("\n", -1)) {
            rows += Math.max(1, (line.length() + COLUMNS - 1) / COLUMNS);
        }
        return rows + 1;
    }
}
