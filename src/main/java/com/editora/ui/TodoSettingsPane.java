package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import com.editora.config.Settings;
import com.editora.todo.TodoPattern;
import com.editora.todo.TodoPatterns;

import static com.editora.i18n.Messages.tr;

/**
 * The editors on the Settings ▸ TODO page: one row per highlight pattern (enabled / name / regex / color /
 * case) with an "Add pattern" button, and the five part-color pickers ({@code [tag]} + the four
 * {@code (priority)} levels). Every edit writes {@link Settings} and applies live; {@link #load} shows the stored
 * values again (on open, after a reset, after another window or a palette command changed them).
 */
final class TodoSettingsPane {

    private final Supplier<Settings> settings;
    private final Runnable apply;
    private final VBox patternsBox = new VBox(4);
    private ColorPicker tagPicker;
    private ColorPicker criticalPicker;
    private ColorPicker highPicker;
    private ColorPicker mediumPicker;
    private ColorPicker lowPicker;
    /** True while {@link #syncPartColors} moves the pickers to the stored values, so that is not an edit. */
    private boolean syncing;

    TodoSettingsPane(Supplier<Settings> settings, Runnable apply) {
        this.settings = settings;
        this.apply = apply;
    }

    /** Shows the stored patterns and part colors again. */
    void load() {
        rebuildRows();
        syncPartColors();
    }

    /** The pattern rows plus "Add pattern". */
    Node patternsEditor() {
        Button add = new Button(tr("settings.todo.add"));
        // A new row starts empty, with the caret in its name: typing BUG makes it match \bBUG\b. It used to
        // start as "NEW" matching \bTODO\b, which counted every TODO twice until the regex was edited.
        add.setOnAction(e -> {
            List<TodoPattern> list = mutablePatterns();
            list.add(new TodoPattern("", "", TodoPatterns.DEFAULT_COLOR, true, true));
            settings.get().setTodoPatterns(list);
            rebuildRows();
            apply.run();
            ((HBox) patternsBox.getChildren().getLast()).getChildren().get(1).requestFocus();
        });
        VBox box = new VBox(6, patternsBox, add);
        rebuildRows();
        return box;
    }

    private List<TodoPattern> mutablePatterns() {
        return new ArrayList<>(settings.get().getTodoPatterns());
    }

    void rebuildRows() {
        patternsBox.getChildren().clear();
        int size = settings.get().getTodoPatterns().size();
        for (int i = 0; i < size; i++) {
            patternsBox.getChildren().add(row(i));
        }
    }

    private Node row(int index) {
        TodoPattern p = settings.get().getTodoPatterns().get(index);
        String shownName = p.getName() == null ? "" : p.getName();
        CheckBox enabled = new CheckBox();
        enabled.setSelected(p.isEnabled());
        enabled.setTooltip(new Tooltip(tr("settings.todo.enabledTip")));
        enabled.setAccessibleText(tr("settings.todo.enabledTip") + " — " + shownName);
        TextField name = new TextField(shownName);
        name.setPromptText(tr("settings.todo.namePrompt"));
        name.setAccessibleText(tr("settings.todo.namePrompt"));
        name.setPrefWidth(110);
        TextField regex = new TextField(p.getPattern());
        regex.setPromptText(tr("settings.todo.regexPrompt"));
        regex.setAccessibleText(tr("settings.todo.regexPrompt") + " — " + shownName);
        HBox.setHgrow(regex, Priority.ALWAYS);
        ColorPicker color = new ColorPicker(parseColor(p.getColor()));
        color.setTooltip(new Tooltip(tr("settings.todo.colorPrompt")));
        color.setAccessibleText(tr("settings.todo.colorPrompt") + " — " + shownName);
        color.setPrefWidth(56);
        CheckBox caseSensitive = new CheckBox(tr("settings.todo.case"));
        caseSensitive.setSelected(p.isCaseSensitive());
        caseSensitive.setTooltip(new Tooltip(tr("settings.todo.caseTip")));
        caseSensitive.setAccessibleText(tr("settings.todo.caseTip") + " — " + shownName);
        Button remove = new Button("✕");
        remove.setTooltip(new Tooltip(tr("settings.todo.removeTip")));
        remove.setAccessibleText(tr("settings.todo.removeTip") + " — " + shownName);

        Runnable commit = () -> {
            List<TodoPattern> cur = mutablePatterns();
            if (index >= cur.size()) {
                return;
            }
            TodoPattern up = cur.get(index);
            up.setEnabled(enabled.isSelected());
            String newName = name.getText() == null ? "" : name.getText().strip();
            // The regex follows the name while it is still the name's own default (or empty): renaming NEW to
            // BUG then matches BUG, as the row suggests. A hand-written regex is left alone.
            String rx = regex.getText() == null ? "" : regex.getText();
            if (!newName.isEmpty() && (rx.isBlank() || rx.equals(TodoPatterns.regexFor(up.getName())))) {
                rx = TodoPatterns.regexFor(newName);
                regex.setText(rx);
            }
            // A row with no name or nothing to match is flagged and that part is not stored; a malformed
            // expression would be skipped by TodoPatterns.compile, silently switching the keyword off.
            String nameProblem = newName.isEmpty() ? tr("settings.todo.nameEmpty") : null;
            flag(name, nameProblem);
            if (nameProblem == null) {
                up.setName(newName);
            }
            String syntax = TodoPatterns.syntaxProblem(rx);
            String regexProblem = rx.isBlank()
                    ? tr("settings.todo.regexEmpty")
                    : syntax == null ? null : tr("settings.todo.regexInvalid", syntax);
            flag(regex, regexProblem);
            if (regexProblem == null) {
                up.setPattern(rx);
            }
            up.setColor(toHex(color.getValue()));
            up.setCaseSensitive(caseSensitive.isSelected());
            settings.get().setTodoPatterns(cur);
            apply.run();
        };
        // Checkboxes + the color picker apply immediately; text fields commit on Enter / focus-loss.
        enabled.selectedProperty().addListener((o, a, b) -> commit.run());
        caseSensitive.selectedProperty().addListener((o, a, b) -> commit.run());
        color.valueProperty().addListener((o, a, b) -> commit.run());
        name.setOnAction(e -> commit.run());
        regex.setOnAction(e -> commit.run());
        Consumer<TextField> onBlur = tf -> tf.focusedProperty().addListener((o, was, now) -> {
            if (!now) {
                commit.run();
            }
        });
        onBlur.accept(name);
        onBlur.accept(regex);
        remove.setOnAction(e -> {
            String label = shownName.isBlank() ? p.getPattern() : shownName;
            if (!confirmDelete(remove, label)) {
                return;
            }
            List<TodoPattern> cur = mutablePatterns();
            if (index < cur.size()) {
                cur.remove(index);
            }
            settings.get().setTodoPatterns(cur);
            rebuildRows();
            apply.run();
        });
        HBox row = new HBox(6, enabled, name, regex, color, caseSensitive, remove);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** Marks {@code field} red with {@code why} as its tooltip, or clears that when {@code why} is null. */
    private static void flag(TextField field, String why) {
        field.pseudoClassStateChanged(atlantafx.base.theme.Styles.STATE_DANGER, why != null);
        field.setTooltip(why == null ? null : new Tooltip(why));
        field.setAccessibleHelp(why);
    }

    /** Asks before a pattern is deleted; Cancel is the default button, so Enter keeps it. */
    private static boolean confirmDelete(Node owner, String label) {
        ButtonType delete = new ButtonType(tr("settings.todo.delete"), ButtonBar.ButtonData.OK_DONE);
        Alert confirm = Dialogs.styled(new Alert(
                Alert.AlertType.CONFIRMATION, tr("settings.todo.deleteConfirm", label), delete, ButtonType.CANCEL));
        confirm.initOwner(owner.getScene() == null ? null : owner.getScene().getWindow());
        confirm.setTitle(tr("settings.todo.deleteTitle"));
        confirm.setHeaderText(null);
        ((Button) confirm.getDialogPane().lookupButton(delete)).setDefaultButton(false);
        ((Button) confirm.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == delete;
    }

    /** The five per-part color pickers; each writes its {@code Settings} field and applies live. */
    Node partColorsEditor() {
        Settings s = settings.get();
        tagPicker = partColorPicker(s.getTodoTagColor(), c -> settings.get().setTodoTagColor(c));
        criticalPicker = partColorPicker(
                s.getTodoPriorityCriticalColor(), c -> settings.get().setTodoPriorityCriticalColor(c));
        highPicker = partColorPicker(
                s.getTodoPriorityHighColor(), c -> settings.get().setTodoPriorityHighColor(c));
        mediumPicker = partColorPicker(
                s.getTodoPriorityMediumColor(), c -> settings.get().setTodoPriorityMediumColor(c));
        lowPicker =
                partColorPicker(s.getTodoPriorityLowColor(), c -> settings.get().setTodoPriorityLowColor(c));
        // A GridPane, not a row of HBoxes: the labels differ in width ("Tag" vs "Critical priority") and in
        // every locale, so a per-row minimum width leaves the pickers ragged the moment one label outgrows
        // it. A shared first column sizes itself to the widest label, whatever that turns out to be.
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        addPartColorRow(grid, 0, tr("settings.todo.part.tag"), tagPicker);
        addPartColorRow(grid, 1, tr("settings.todo.part.critical"), criticalPicker);
        addPartColorRow(grid, 2, tr("settings.todo.part.high"), highPicker);
        addPartColorRow(grid, 3, tr("settings.todo.part.medium"), mediumPicker);
        addPartColorRow(grid, 4, tr("settings.todo.part.low"), lowPicker);
        return grid;
    }

    private ColorPicker partColorPicker(String web, Consumer<String> setter) {
        ColorPicker cp = new ColorPicker(parseColor(web));
        cp.setPrefWidth(56);
        cp.valueProperty().addListener((o, a, b) -> {
            if (syncing) {
                return;
            }
            setter.accept(toHex(b));
            apply.run();
        });
        return cp;
    }

    private static void addPartColorRow(GridPane grid, int row, String label, ColorPicker picker) {
        Label l = new Label(label);
        grid.add(l, 0, row);
        grid.add(picker, 1, row);
        GridPane.setValignment(l, VPos.CENTER);
    }

    /** Re-reads the five part-color pickers from settings (after a palette color change). */
    void syncPartColors() {
        Settings s = settings.get();
        syncing = true;
        try {
            setPicker(tagPicker, s.getTodoTagColor());
            setPicker(criticalPicker, s.getTodoPriorityCriticalColor());
            setPicker(highPicker, s.getTodoPriorityHighColor());
            setPicker(mediumPicker, s.getTodoPriorityMediumColor());
            setPicker(lowPicker, s.getTodoPriorityLowColor());
        } finally {
            syncing = false;
        }
    }

    private static void setPicker(ColorPicker picker, String web) {
        if (picker != null) {
            picker.setValue(parseColor(web));
        }
    }

    static Color parseColor(String web) {
        try {
            return Color.web(web == null || web.isBlank() ? "#E5C07B" : web);
        } catch (RuntimeException e) {
            return Color.web("#E5C07B");
        }
    }

    static String toHex(Color c) {
        return String.format(
                "#%02X%02X%02X",
                (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255), (int)
                        Math.round(c.getBlue() * 255));
    }
}
