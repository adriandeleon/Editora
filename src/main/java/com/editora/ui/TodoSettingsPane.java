package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
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
        add.setOnAction(e -> {
            List<TodoPattern> list = mutablePatterns();
            list.add(new TodoPattern(
                    tr("settings.todo.newName"), "\\bTODO\\b", TodoPatterns.DEFAULT_COLOR, false, true));
            settings.get().setTodoPatterns(list);
            rebuildRows();
            apply.run();
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
        CheckBox enabled = new CheckBox();
        enabled.setSelected(p.isEnabled());
        enabled.setTooltip(new Tooltip(tr("settings.todo.enabledTip")));
        enabled.setAccessibleText(tr("settings.todo.enabledTip") + " — " + p.getName());
        TextField name = new TextField(p.getName());
        name.setPromptText(tr("settings.todo.namePrompt"));
        name.setPrefWidth(110);
        TextField regex = new TextField(p.getPattern());
        regex.setPromptText(tr("settings.todo.regexPrompt"));
        HBox.setHgrow(regex, Priority.ALWAYS);
        ColorPicker color = new ColorPicker(parseColor(p.getColor()));
        color.setTooltip(new Tooltip(tr("settings.todo.colorPrompt")));
        color.setPrefWidth(56);
        CheckBox caseSensitive = new CheckBox(tr("settings.todo.case"));
        caseSensitive.setSelected(p.isCaseSensitive());
        Button remove = new Button("✕");
        remove.setTooltip(new Tooltip(tr("settings.todo.removeTip")));
        remove.setAccessibleText(tr("settings.todo.removeTip") + " — " + p.getName());

        Runnable commit = () -> {
            List<TodoPattern> cur = mutablePatterns();
            if (index >= cur.size()) {
                return;
            }
            TodoPattern up = cur.get(index);
            up.setEnabled(enabled.isSelected());
            up.setName(name.getText());
            // A malformed expression is skipped by TodoPatterns.compile, so saving it silently switched the
            // keyword off. Flag the field and keep the stored pattern until the expression is valid.
            String problem = TodoPatterns.syntaxProblem(regex.getText());
            regex.pseudoClassStateChanged(atlantafx.base.theme.Styles.STATE_DANGER, problem != null);
            regex.setTooltip(problem == null ? null : new Tooltip(tr("settings.todo.regexInvalid", problem)));
            if (problem == null) {
                up.setPattern(regex.getText());
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
