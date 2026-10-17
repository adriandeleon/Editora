package com.editora.ui;

import java.util.List;

import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import com.editora.config.SettingsFileCheck;

import static com.editora.i18n.Messages.tr;

/**
 * The Advanced page's word on the settings file: that saving it in Editora applies it at once while an edit made
 * elsewhere waits for the next start, and which of its values are not used as written (an unknown key, a wrong
 * type, a value pulled into range) — a hand edit that seems to do nothing says why.
 */
final class SettingsFileNotes {

    private final Label issues = new Label();
    private final VBox node;

    SettingsFileNotes() {
        Label apply = new Label(tr("settings.file.applyNote"));
        apply.getStyleClass().add("settings-row-desc");
        apply.setWrapText(true);
        issues.getStyleClass().addAll("settings-row-desc", "settings-file-issues");
        issues.setWrapText(true);
        SettingsSearch.setShown(issues, false);
        node = new VBox(4, apply, issues);
    }

    VBox node() {
        return node;
    }

    /** Shows {@code found} (none: hides the list). */
    void show(List<SettingsFileCheck.Issue> found) {
        SettingsSearch.setShown(issues, !found.isEmpty());
        issues.setText(describe(found));
    }

    /** The issues as lines under a heading; empty for none. */
    static String describe(List<SettingsFileCheck.Issue> found) {
        if (found.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(tr("settings.file.issues"));
        for (SettingsFileCheck.Issue i : found) {
            out.append('\n')
                    .append(
                            switch (i.kind()) {
                                case UNKNOWN -> tr("settings.file.issue.unknown", i.key());
                                case WRONG_TYPE -> tr("settings.file.issue.wrongType", i.key(), i.written(), i.used());
                                case CHANGED -> tr("settings.file.issue.changed", i.key(), i.written(), i.used());
                            });
        }
        return out.toString();
    }
}
