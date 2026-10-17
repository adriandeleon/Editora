package com.editora.ui;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

import com.editora.externaltool.ExternalTool;

import static com.editora.i18n.Messages.tr;

/**
 * The master-detail editor of Settings → External Tools: the tools on the left, the selected tool's form on the
 * right. Every edit applies as it is made (a field on Enter, on focus loss or when the window closes; a combo or
 * the Enabled switch at once) — there is no Save button. Remove asks first.
 */
final class ExternalToolsEditor {

    /** What the editor borrows from the Settings window it sits in. */
    record Hooks(
            Supplier<List<ExternalTool>> stored,
            Consumer<List<ExternalTool>> persist,
            Consumer<String> warn,
            Predicate<String> confirmDelete,
            Function<String, String> chordFor,
            BiConsumer<String, String> rebind,
            Consumer<String> unbind,
            BiConsumer<TextField, Runnable> commitOnClose,
            Supplier<Window> owner) {}

    private final Hooks hooks;
    private final ObservableList<ExternalTool> items = FXCollections.observableArrayList();
    private final ListView<ExternalTool> list = new ListView<>(items);
    private final VBox node;
    private boolean loading;

    ExternalToolsEditor(Hooks hooks) {
        this.hooks = hooks;
        reload();
        list.setPrefSize(200, 260);
        list.setMinHeight(140);
        list.setPlaceholder(wrapped(tr("settings.externalTool.empty")));
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ExternalTool t, boolean empty) {
                super.updateItem(t, empty);
                setText(
                        empty || t == null
                                ? null
                                : (t.getName().isBlank() ? tr("settings.externalTool.unnamed") : t.getName()));
            }
        });

        TextField name = new TextField();
        TextField command = new TextField();
        TextField arguments = new TextField();
        TextField workingDir = new TextField();
        workingDir.setPromptText(tr("settings.externalTool.workingDirPrompt"));
        ComboBox<ExternalTool.StdinSource> stdin =
                new ComboBox<>(FXCollections.observableArrayList(ExternalTool.StdinSource.values()));
        stdin.setConverter(converter(s -> tr("externalTool.stdin." + s.name())));
        ComboBox<ExternalTool.OutputTarget> output =
                new ComboBox<>(FXCollections.observableArrayList(ExternalTool.OutputTarget.values()));
        output.setConverter(converter(o -> tr("externalTool.output." + o.name())));
        CheckBox enabled = new CheckBox(tr("settings.externalTool.enabled"));

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(6);
        formRow(form, 0, tr("settings.externalTool.name"), name);
        formRow(form, 1, tr("settings.externalTool.command"), withBrowse(command, false));
        formRow(form, 2, tr("settings.externalTool.arguments"), arguments);
        formRow(form, 3, tr("settings.externalTool.workingDir"), withBrowse(workingDir, true));
        formRow(form, 4, tr("settings.externalTool.stdin"), stdin);
        formRow(form, 5, tr("settings.externalTool.output"), output);
        formRow(form, 6, enabled.getText(), SettingSwitch.boundTo(enabled));
        form.setDisable(true);
        HBox.setHgrow(form, Priority.ALWAYS);

        Runnable commit = () -> {
            ExternalTool t = list.getSelectionModel().getSelectedItem();
            if (t == null || loading) {
                return;
            }
            // Distinct names can produce one externalTool.run.<slug> command ("Format JSON" / "format-json"),
            // and CommandRegistry.register is a put: the second tool would silently shadow the first.
            if (slugTaken(t, name.getText())) {
                hooks.warn().accept(tr("settings.externalTool.idExists", name.getText()));
                name.setText(t.getName());
                return;
            }
            if (Objects.equals(t.getName(), name.getText())
                    && Objects.equals(t.getCommand(), command.getText())
                    && Objects.equals(t.getArguments(), arguments.getText())
                    && Objects.equals(t.getWorkingDir(), workingDir.getText())
                    && (stdin.getValue() == null || t.getStdin() == stdin.getValue())
                    && (output.getValue() == null || t.getOutput() == output.getValue())
                    && t.isEnabled() == enabled.isSelected()) {
                return; // nothing changed: a focus change alone writes nothing
            }
            // The command id is derived from the name, so a rename is a new command: carry the key binding
            // across instead of leaving a chord bound to an id nothing registers.
            String oldId = ExternalTool.commandIdFor(t.getName());
            String newId = ExternalTool.commandIdFor(name.getText());
            String oldChord = newId.equals(oldId) ? null : hooks.chordFor().apply(oldId);
            t.setName(name.getText());
            t.setCommand(command.getText());
            t.setArguments(arguments.getText());
            t.setWorkingDir(workingDir.getText());
            if (stdin.getValue() != null) {
                t.setStdin(stdin.getValue());
            }
            if (output.getValue() != null) {
                t.setOutput(output.getValue());
            }
            t.setEnabled(enabled.isSelected());
            list.refresh();
            persist(); // re-registers externalTool.run.* (incl. the renamed id)
            if (!newId.equals(oldId)) {
                hooks.unbind().accept(oldId); // drop the old id's override BEFORE binding the new one
                if (oldChord != null && !oldChord.isBlank()) {
                    hooks.rebind().accept(newId, oldChord);
                }
            }
        };
        stdin.valueProperty().addListener((o, a, b) -> commit.run());
        output.valueProperty().addListener((o, a, b) -> commit.run());
        enabled.selectedProperty().addListener((o, a, b) -> commit.run());
        for (TextField tf : List.of(name, command, arguments, workingDir)) {
            tf.setOnAction(e -> commit.run());
            tf.focusedProperty().addListener((o, was, now) -> {
                if (!now) {
                    commit.run();
                }
            });
            hooks.commitOnClose().accept(tf, commit);
        }

        list.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            loading = true;
            try {
                form.setDisable(now == null);
                name.setText(now == null ? "" : now.getName());
                command.setText(now == null ? "" : now.getCommand());
                arguments.setText(now == null ? "" : now.getArguments());
                workingDir.setText(now == null ? "" : now.getWorkingDir());
                stdin.setValue(now == null ? null : now.getStdin());
                output.setValue(now == null ? null : now.getOutput());
                enabled.setSelected(now != null && now.isEnabled());
            } finally {
                loading = false;
            }
        });

        Button add = new Button(tr("settings.externalTool.add"));
        add.setOnAction(e -> {
            // Two tools named "New Tool" would share one externalTool.run.<slug> command.
            String newName = tr("settings.externalTool.newName");
            for (int n = 2; slugTaken(null, newName); n++) {
                newName = tr("settings.externalTool.newName") + " " + n;
            }
            ExternalTool t = new ExternalTool(
                    newName, "", "", "", ExternalTool.StdinSource.NONE, ExternalTool.OutputTarget.CONSOLE, true);
            items.add(t);
            persist();
            list.getSelectionModel().select(t);
            name.requestFocus();
            name.selectAll();
        });
        Button remove = new Button(tr("settings.externalTool.remove"));
        remove.disableProperty()
                .bind(list.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(e -> {
            ExternalTool t = list.getSelectionModel().getSelectedItem();
            if (t == null || !hooks.confirmDelete().test(displayName(t))) {
                return;
            }
            String id = ExternalTool.commandIdFor(t.getName());
            items.remove(t);
            persist();
            hooks.unbind().accept(id); // drop its key binding, as deleting a macro does
        });

        VBox left = new VBox(6, list);
        VBox.setVgrow(list, Priority.ALWAYS);
        VBox right = new VBox(8, form);
        HBox.setHgrow(right, Priority.ALWAYS);
        if (!items.isEmpty()) {
            list.getSelectionModel().select(0);
        }
        HBox top = new HBox(12, left, right);
        top.setAlignment(Pos.TOP_LEFT);
        HBox buttons = new HBox(6, add, remove);
        buttons.setAlignment(Pos.CENTER_LEFT);
        node = new VBox(8, top, buttons);
    }

    Node node() {
        return node;
    }

    /**
     * Re-reads the list from the live settings, keeping the selection. A snapshot taken when the page was built
     * went stale as soon as another window added a tool, and writing it back deleted that tool.
     */
    void reload() {
        List<ExternalTool> stored = hooks.stored().get();
        if (sameTools(items, stored)) {
            return; // nothing changed behind the page: keep the rows (and the form's caret) as they are
        }
        ExternalTool selected = list.getSelectionModel().getSelectedItem();
        String selectedName = selected == null ? null : selected.getName();
        items.setAll(copyTools(stored));
        if (selectedName != null) {
            items.stream()
                    .filter(t -> selectedName.equals(t.getName()))
                    .findFirst()
                    .ifPresent(t -> list.getSelectionModel().select(t));
        }
    }

    private void persist() {
        hooks.persist().accept(new ArrayList<>(items));
    }

    private static String displayName(ExternalTool t) {
        return t.getName().isBlank() ? tr("settings.externalTool.unnamed") : t.getName();
    }

    /** True when {@code newName} would give {@code tool} the command id another tool already uses. */
    private boolean slugTaken(ExternalTool tool, String newName) {
        String id = ExternalTool.commandIdFor(newName);
        return items.stream()
                .anyMatch(
                        o -> o != tool && ExternalTool.commandIdFor(o.getName()).equals(id));
    }

    /** {@code field} with a Browse… button that picks a file ({@code directory} false) or a folder into it. */
    private Node withBrowse(TextField field, boolean directory) {
        Button browse = new Button(tr("settings.mermaid.browse"));
        browse.setMinWidth(Region.USE_PREF_SIZE);
        browse.setOnAction(e -> {
            File f;
            if (directory) {
                javafx.stage.DirectoryChooser dc = new javafx.stage.DirectoryChooser();
                f = dc.showDialog(hooks.owner().get());
            } else {
                javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
                f = fc.showOpenDialog(hooks.owner().get());
            }
            if (f != null) {
                field.setText(f.getAbsolutePath());
                field.fireEvent(new javafx.event.ActionEvent()); // commit, as Enter does
            }
        });
        HBox.setHgrow(field, Priority.ALWAYS);
        HBox box = new HBox(6, field, browse);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static void formRow(GridPane form, int row, String labelText, Node field) {
        Label l = new Label(labelText);
        l.setMinWidth(Region.USE_PREF_SIZE); // a GridPane would squeeze it to "…"
        form.add(l, 0, row);
        form.add(field, 1, row);
        if (field instanceof Region r && !(field instanceof SettingSwitch)) {
            r.setMinWidth(180);
            GridPane.setHgrow(r, Priority.ALWAYS);
        }
    }

    private static Label wrapped(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.getStyleClass().add("settings-row-desc");
        return l;
    }

    private static <T> StringConverter<T> converter(Function<T, String> label) {
        return new StringConverter<>() {
            @Override
            public String toString(T value) {
                return value == null ? "" : label.apply(value);
            }

            @Override
            public T fromString(String s) {
                return null;
            }
        };
    }

    private static List<ExternalTool> copyTools(List<ExternalTool> src) {
        List<ExternalTool> out = new ArrayList<>();
        for (ExternalTool t : src) {
            out.add(new ExternalTool(
                    t.getName(),
                    t.getCommand(),
                    t.getArguments(),
                    t.getWorkingDir(),
                    t.getStdin(),
                    t.getOutput(),
                    t.isEnabled()));
        }
        return out;
    }

    private static boolean sameTools(List<ExternalTool> a, List<ExternalTool> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            ExternalTool x = a.get(i);
            ExternalTool y = b.get(i);
            if (x != y
                    && !(Objects.equals(x.getName(), y.getName())
                            && Objects.equals(x.getCommand(), y.getCommand())
                            && Objects.equals(x.getArguments(), y.getArguments())
                            && Objects.equals(x.getWorkingDir(), y.getWorkingDir())
                            && x.getStdin() == y.getStdin()
                            && x.getOutput() == y.getOutput()
                            && x.isEnabled() == y.isEnabled())) {
                return false;
            }
        }
        return true;
    }
}
