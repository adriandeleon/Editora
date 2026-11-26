package com.editora.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import com.editora.toolchain.JkitCli;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * jkit's own settings ({@code jkit config}): the vendor {@code jdk@…} and {@code graalvm@…} install when none is
 * named, and switching versions on {@code cd} in a shell with jkit's hook. They live in jkit's
 * {@code ~/.jkit/config.toml}, not Editora's settings, so jkit reads and writes them; a value set by an environment
 * variable wins there and is shown but cannot be changed here.
 */
final class JkitSettingsDialog {

    private JkitSettingsDialog() {}

    /** One editable row: its key and how to read the value the user chose ("" for jkit's default). */
    record Row(String key, String initial, Supplier<String> chosen) {}

    /**
     * The changes between what each row started as and what it holds now: key → new value, "" to go back to
     * jkit's default. Pure.
     */
    static Map<String, String> changes(List<Row> rows) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Row r : rows) {
            String now = r.chosen().get().strip();
            if (!now.equals(r.initial())) {
                out.put(r.key(), now);
            }
        }
        return out;
    }

    /** What a row starts as: the config file's own value ("" when jkit's default or the environment applies). Pure. */
    static String initial(JkitCli.ConfigSetting s) {
        return "file".equals(s.source()) ? s.value() : "";
    }

    static void show(
            @Nullable Window owner,
            String jkit,
            @Nullable String version,
            List<JkitCli.ConfigSetting> settings,
            Consumer<Map<String, String>> apply) {
        Dialog<ButtonType> dialog = Dialogs.styled(new Dialog<>());
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle(tr("toolchain.settings.title"));
        dialog.setHeaderText(tr("toolchain.status.header", version == null ? "?" : version, jkit));
        GridPane grid = Dialogs.labelColumn(new GridPane());
        grid.setHgap(12);
        grid.setVgap(8);
        List<Row> rows = new ArrayList<>();
        int line = 0;
        for (JkitCli.ConfigSetting s : settings) {
            Label name = new Label(s.key());
            String init = initial(s);
            boolean fromEnv = "env".equals(s.source());
            javafx.scene.Node editor;
            Supplier<String> chosen;
            if (s.key().endsWith(".vendor")) {
                ComboBox<String> combo = new ComboBox<>();
                combo.setEditable(true);
                combo.getItems().add("");
                combo.getItems().addAll(ToolchainCommands.vendorChoices(s.key().startsWith("graalvm")));
                combo.setValue(init);
                combo.setPromptText(tr("toolchain.settings.default", s.defaultValue() == null ? "" : s.defaultValue()));
                combo.setId("jkit-setting-" + s.key());
                editor = combo;
                chosen = () -> combo.getEditor().getText() == null
                        ? ""
                        : combo.getEditor().getText();
            } else if ("true".equals(s.value()) || "false".equals(s.value())) {
                CheckBox check = new CheckBox();
                check.setSelected("true".equals(s.value()));
                check.setId("jkit-setting-" + s.key());
                editor = check;
                String start = "file".equals(s.source()) ? s.value() : "";
                chosen = () -> {
                    String v = Boolean.toString(check.isSelected());
                    return start.isEmpty() && v.equals(s.value()) ? "" : v; // unchanged default stays unset
                };
            } else {
                TextField field = new TextField(init);
                field.setPromptText(tr("toolchain.settings.default", s.defaultValue() == null ? "" : s.defaultValue()));
                field.setId("jkit-setting-" + s.key());
                editor = field;
                chosen = field::getText;
            }
            name.setLabelFor(editor); // the key names the box for a screen reader (ACC-01, Pauta 6.2.0)
            editor.setDisable(fromEnv);
            Label about = new Label(
                    fromEnv
                            ? s.description() + " — "
                                    + tr("toolchain.settings.fromEnv", s.envVariable() == null ? "" : s.envVariable())
                            : s.description());
            about.setWrapText(true);
            about.getStyleClass().add("hint");
            grid.add(name, 0, line);
            grid.add(new VBox(4, editor, about), 1, line);
            line++;
            if (!fromEnv) {
                rows.add(new Row(s.key(), init, chosen));
            }
        }
        dialog.getDialogPane().setContent(grid);
        ButtonType save = Dialogs.action(tr("toolchain.settings.save"));
        dialog.getDialogPane().getButtonTypes().setAll(save, Dialogs.cancel());
        Dialogs.defaultTo(dialog, save);
        Dialogs.show(dialog, answer -> {
            if (answer == save) {
                Map<String, String> changed = changes(rows);
                if (!changed.isEmpty()) {
                    apply.accept(changed);
                }
            }
        });
    }
}
