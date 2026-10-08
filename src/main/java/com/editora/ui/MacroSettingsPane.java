package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.event.EventHandler;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

import com.editora.command.KeyDispatcher;
import com.editora.command.KeymapManager;
import com.editora.command.TextInputKeymap;
import com.editora.config.ConfigManager;
import com.editora.config.MacroStore;
import com.editora.macro.Macro;
import com.editora.macro.MacroKey;
import com.editora.macro.MacroService;
import com.editora.macro.MacroStep;

import static com.editora.i18n.Messages.tr;

/**
 * The Settings → Macros editor: the saved macros on the left, and for the selected one its name, key binding
 * and steps on the right.
 *
 * <p>Each step is shown and edited as what it is. A <b>command</b> step is picked from the list of commands
 * (and flagged when its id names none); a <b>text</b> step is edited in a multi-line field, so an Enter in it
 * stays an Enter; a <b>key</b> step is set by pressing the key. The previous editor had one single-line field
 * for all three, which showed a key step as the text {@code "BACK_SPACE"}, could not add one, and stripped
 * line breaks from text.
 *
 * <p>Edits go to a working copy. <b>Save</b> writes it; switching to another macro or closing the window
 * writes it too, so an edit is never dropped without a word — <b>Revert</b> is how to drop it.
 */
final class MacroSettingsPane extends HBox {

    /** What the pane needs from the Settings window that owns it. */
    interface Host {
        ConfigManager config();

        /** The key-binding backend; null until the window has been given one. */
        SettingsWindow.ShortcutActions shortcuts();

        /** The saved macros changed: re-register their commands in every window. */
        void macrosChanged();

        /** Bind {@code chord} to {@code commandId}, asking first when it collides with another binding. */
        boolean rebind(String commandId, String chord);

        /** A binding changed: refresh every place that shows one. */
        void shortcutsChanged();
    }

    private final Host host;
    private final ObservableList<Macro> macros = FXCollections.observableArrayList();
    private final ObservableList<MacroStep> steps = FXCollections.observableArrayList();
    private final ListView<Macro> list = new ListView<>(macros);
    private final ListView<MacroStep> stepList = new ListView<>(steps);
    private final TextField name = new TextField();
    /** The key-binding row: a chord label and its buttons, or a capture field while recording a chord. */
    private final WrapRow keybinding = new WrapRow(8, 6);

    private final javafx.scene.layout.GridPane form = new javafx.scene.layout.GridPane();
    private final VBox stepDetail = new VBox(6);
    private final BooleanProperty dirty = new SimpleBooleanProperty(false);

    /** The id of the macro whose working copy is on screen, or null. */
    private String editingId;

    private boolean loading;
    private final EventHandler<WindowEvent> saveOnHide = e -> saveIfDirty();

    MacroSettingsPane(Host host) {
        super(12);
        this.host = host;
        setAlignment(Pos.TOP_LEFT);

        list.setPrefSize(220, 440);
        Label none = new Label(tr("settings.macro.empty"));
        none.getStyleClass().add("settings-hint");
        none.setWrapText(true);
        none.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        none.setMaxWidth(190);
        list.setPlaceholder(none);
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Macro m, boolean empty) {
                super.updateItem(m, empty);
                setText(
                        empty || m == null
                                ? null
                                : MacroCoordinator.displayName(m) + "  ("
                                        + m.steps().size() + ")");
            }
        });

        stepList.setPrefHeight(190);
        stepList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(MacroStep s, boolean empty) {
                super.updateItem(s, empty);
                setText(empty || s == null ? null : stepLabel(s));
                getStyleClass().remove("macro-step-unknown");
                if (!empty && s != null && s.isCommand() && !commandExists(s.value())) {
                    getStyleClass().add("macro-step-unknown");
                }
            }
        });
        stepList.getSelectionModel().selectedIndexProperty().addListener((o, was, now) -> showStepDetail());

        Button up = new Button("▲");
        Button down = new Button("▼");
        up.setAccessibleText(tr("bookmarks.moveUp"));
        up.setTooltip(new Tooltip(tr("bookmarks.moveUp")));
        down.setAccessibleText(tr("bookmarks.moveDown"));
        down.setTooltip(new Tooltip(tr("bookmarks.moveDown")));
        up.getStyleClass().addAll("flat", "reorder-button");
        down.getStyleClass().addAll("flat", "reorder-button");
        up.setOnAction(e -> moveStep(-1));
        down.setOnAction(e -> moveStep(1));
        Button remove = new Button(tr("settings.macro.removeStep"));
        remove.setOnAction(e -> {
            int i = stepList.getSelectionModel().getSelectedIndex();
            if (i >= 0) {
                steps.remove(i);
                touched();
            }
        });
        Button addCommand = new Button(tr("settings.macro.addCommand"));
        addCommand.setOnAction(e -> {
            String id = chooseCommand(null);
            if (id != null) {
                addStep(MacroStep.command(id));
            }
        });
        Button addText = new Button(tr("settings.macro.addText"));
        addText.setOnAction(e -> addStep(MacroStep.text("")));
        Button addKey = new Button(tr("settings.macro.addKey"));
        addKey.setOnAction(e -> addStep(MacroStep.key("")));
        HBox reorder = new HBox(6, up, down);
        // No button may shrink below its label: a squeezed button reads "…" (and wraps to the next line instead).
        fitToLabel(addCommand, addText, addKey, remove, up, down, reorder);
        WrapRow stepButtons = new WrapRow(6, 6, addCommand, addText, addKey, reorder, remove);

        form.setHgap(8);
        form.setVgap(6);
        formRow(form, 0, tr("settings.macro.name"), name);
        formRow(form, 1, tr("settings.macro.keybinding"), keybinding);
        javafx.scene.layout.GridPane.setHgrow(name, Priority.ALWAYS);
        javafx.scene.layout.GridPane.setHgrow(keybinding, Priority.ALWAYS);
        name.textProperty().addListener((o, was, now) -> touched());

        Label stepsLabel = new Label(tr("settings.macro.steps"));
        stepsLabel.getStyleClass().add("settings-section");
        VBox stepEditor = new VBox(6, stepsLabel, stepList, stepDetail, stepButtons);
        VBox.setVgrow(stepList, Priority.ALWAYS);
        form.setDisable(true);
        // No macro selected, no steps to edit: Add Command used to add a step that belonged to nothing.
        stepEditor.disableProperty().bind(form.disabledProperty());

        Button save = new Button(tr("settings.save"));
        save.getStyleClass().add("success");
        save.disableProperty().bind(dirty.not());
        save.setOnAction(e -> save());
        Button revert = new Button(tr("settings.macro.revert"));
        revert.disableProperty().bind(dirty.not());
        revert.setOnAction(e -> load(selectedStored()));
        Label unsaved = new Label(tr("settings.macro.unsaved"));
        unsaved.getStyleClass().add("settings-hint");
        unsaved.visibleProperty().bind(dirty);
        unsaved.managedProperty().bind(dirty);
        Button delete = new Button(tr("settings.macro.delete"));
        delete.disableProperty()
                .bind(list.getSelectionModel().selectedItemProperty().isNull());
        delete.setOnAction(e -> deleteSelected());
        Region gap = new Region();
        fitToLabel(save, revert, delete, unsaved);
        WrapRow formButtons = new WrapRow(8, 6, delete, WrapRow.setGrow(gap), unsaved, revert, save);

        VBox right = new VBox(8, form, stepEditor, formButtons);
        VBox.setVgrow(stepEditor, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        right.setMinWidth(0);
        VBox left = new VBox(6, list);
        left.setMinWidth(Region.USE_PREF_SIZE);
        VBox.setVgrow(list, Priority.ALWAYS);
        getChildren().addAll(left, right);

        list.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            if (loading) {
                return;
            }
            String target = now == null ? null : now.id();
            if (dirty.get()) {
                save(); // the edits to the macro being left are kept, not dropped
                loading = true; // saving re-read the list and re-selected the macro just saved
                try {
                    Macro again = target == null ? null : find(target);
                    if (again != null) {
                        list.getSelectionModel().select(again);
                    }
                } finally {
                    loading = false;
                }
            }
            load(target == null ? null : host.config().getMacroStore().findById(target));
        });
        // Closing Settings with an edit pending keeps the edit too.
        sceneProperty().addListener((o, was, now) -> {
            if (was != null && was.getWindow() != null) {
                was.getWindow().removeEventHandler(WindowEvent.WINDOW_HIDING, saveOnHide);
            }
            if (now != null) {
                now.windowProperty().addListener((w, old, window) -> watch(old, window));
                watch(null, now.getWindow());
            }
        });
        refresh();
    }

    private void watch(Window old, Window window) {
        if (old != null) {
            old.removeEventHandler(WindowEvent.WINDOW_HIDING, saveOnHide);
        }
        if (window != null) {
            window.removeEventHandler(WindowEvent.WINDOW_HIDING, saveOnHide);
            window.addEventHandler(WindowEvent.WINDOW_HIDING, saveOnHide);
        }
    }

    private static void fitToLabel(Region... controls) {
        for (Region control : controls) {
            control.setMinWidth(Region.USE_PREF_SIZE);
        }
    }

    private static void formRow(javafx.scene.layout.GridPane form, int row, String text, Node field) {
        Label l = new Label(text);
        l.setMinWidth(Region.USE_PREF_SIZE);
        form.add(l, 0, row);
        form.add(field, 1, row);
    }

    // ---------------------------------------------------------------- list ⇄ store

    /**
     * Re-reads the list from the store — after a macro changed from outside this pane (a recording was
     * stopped, another window saved). The selection follows its macro by id, and an edit in progress is left
     * alone.
     */
    void refresh() {
        String keep = editingId;
        loading = true;
        try {
            macros.setAll(host.config().getMacroStore().macros);
            Macro again = keep == null ? null : find(keep);
            if (again != null) {
                list.getSelectionModel().select(again);
            } else {
                list.getSelectionModel().clearSelection();
            }
        } finally {
            loading = false;
        }
        Macro selected = list.getSelectionModel().getSelectedItem();
        if (selected == null) {
            if (!macros.isEmpty()) {
                list.getSelectionModel().select(0); // through the listener: loads it
            } else {
                load(null);
            }
        } else if (!dirty.get()) {
            load(selected);
        }
    }

    private Macro find(String id) {
        for (Macro m : macros) {
            if (m.id().equals(id)) {
                return m;
            }
        }
        return null;
    }

    private Macro selectedStored() {
        return editingId == null ? null : host.config().getMacroStore().findById(editingId);
    }

    /** Puts {@code m} (or nothing) into the working copy. */
    private void load(Macro m) {
        loading = true;
        try {
            editingId = m == null ? null : m.id();
            form.setDisable(m == null);
            name.setText(m == null ? "" : m.name());
            // The unnamed last recording has no name of its own: the field shows the label it goes by.
            name.setPromptText(m != null && m.name().isBlank() ? tr("macro.unnamedName") : "");
            steps.setAll(m == null ? List.of() : m.steps());
            dirty.set(false);
        } finally {
            loading = false;
        }
        if (!steps.isEmpty()) {
            stepList.getSelectionModel().select(0);
        }
        showStepDetail();
        refreshKeybinding();
    }

    private void touched() {
        if (!loading && editingId != null) {
            dirty.set(true);
        }
    }

    private void saveIfDirty() {
        if (dirty.get()) {
            save();
        }
    }

    /** Writes the working copy over the stored macro. The id — and so the key binding — never changes. */
    private void save() {
        Macro stored = selectedStored();
        if (stored == null) {
            dirty.set(false);
            return;
        }
        MacroStore store = host.config().getMacroStore();
        String newName = name.getText() == null ? "" : name.getText().strip();
        if (newName.isEmpty() && !store.isPlaceholder(stored)) {
            newName = stored.name(); // a macro keeps its name rather than lose it to an emptied field
        }
        store.put(new Macro(stored.id(), newName, new ArrayList<>(steps)));
        host.config().saveMacros();
        dirty.set(false);
        host.macrosChanged(); // re-registers macro.run.* (the title follows a rename) and refreshes this list
        refresh();
    }

    private void deleteSelected() {
        Macro sel = list.getSelectionModel().getSelectedItem();
        if (sel == null) {
            return;
        }
        Alert confirm = Dialogs.styled(new Alert(
                Alert.AlertType.CONFIRMATION,
                tr("settings.macro.deleteConfirm", MacroCoordinator.displayName(sel)),
                ButtonType.OK,
                ButtonType.CANCEL));
        confirm.initOwner(getScene() == null ? null : getScene().getWindow());
        confirm.setTitle(tr("settings.macro.deleteConfirmTitle"));
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        if (host.shortcuts() != null) {
            host.shortcuts().reset(MacroService.commandIdFor(sel)); // drop its keybinding
        }
        dirty.set(false);
        editingId = null;
        host.config().getMacroStore().removeById(sel.id());
        host.config().saveMacros();
        host.macrosChanged();
        refresh();
    }

    // ---------------------------------------------------------------- key binding row

    /** Re-reads the key-binding row from the live keymap; left alone while a chord is being recorded in it. */
    void refreshKeybinding() {
        boolean recording =
                !keybinding.getChildren().isEmpty() && keybinding.getChildren().get(0) instanceof TextField;
        if (!recording) {
            rebuildKeybinding();
        }
    }

    private void rebuildKeybinding() {
        keybinding.getChildren().clear();
        Macro m = selectedStored();
        if (m == null) {
            return;
        }
        String commandId = MacroService.commandIdFor(m);
        String chord = chordFor(commandId);
        boolean bound = chord != null && !chord.isBlank();
        Label chordLabel = new Label(bound ? chord : tr("settings.shortcuts.unbound"));
        chordLabel.getStyleClass().add(bound ? "shortcut-chord" : "shortcut-unbound");
        chordLabel.setMinWidth(Region.USE_PREF_SIZE);
        Button record = new Button(tr("settings.shortcuts.record"));
        Button clear = new Button(tr("settings.shortcuts.reset"));
        fitToLabel(record, clear);
        clear.setOnAction(e -> {
            if (host.shortcuts() != null) {
                host.shortcuts().reset(commandId);
            }
            rebuildKeybinding();
            host.shortcutsChanged();
        });
        record.setOnAction(e -> captureChord(commandId));
        keybinding.getChildren().addAll(chordLabel, record, clear);
    }

    /** Swaps the key-binding row into a live chord-capture field, mirroring the Keymaps recorder. */
    private void captureChord(String commandId) {
        keybinding.getChildren().clear();
        Runnable done = () -> {
            rebuildKeybinding();
            host.shortcutsChanged(); // the Keymaps list and the chord chips show this binding too
        };
        java.util.function.Consumer<String> commit = seq -> {
            host.rebind(commandId, seq);
            done.run();
        };
        TextField capture = ShortcutCapture.field(commit, done);
        Button save = new Button(tr("settings.shortcuts.save"));
        save.getStyleClass().add("success");
        save.setOnAction(e -> commit.accept(capture.getText()));
        Button cancel = new Button(tr("settings.shortcuts.cancel"));
        cancel.setOnAction(e -> done.run());
        fitToLabel(save, cancel);
        keybinding.getChildren().addAll(capture, save, cancel);
        Platform.runLater(capture::requestFocus);
    }

    private String chordFor(String commandId) {
        if (host.shortcuts() == null) {
            return null;
        }
        for (SettingsWindow.Shortcut s : host.shortcuts().rows()) {
            if (s.id().equals(commandId)) {
                return s.chord();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- steps

    private void addStep(MacroStep step) {
        steps.add(step);
        touched();
        stepList.getSelectionModel().selectLast();
        stepList.scrollTo(steps.size() - 1);
        Platform.runLater(() -> {
            for (Node n : stepDetail.lookupAll(".macro-step-input")) {
                n.requestFocus();
                break;
            }
        });
    }

    private void moveStep(int delta) {
        int i = stepList.getSelectionModel().getSelectedIndex();
        int j = i + delta;
        if (i < 0 || j < 0 || j >= steps.size()) {
            return;
        }
        MacroStep s = steps.remove(i);
        steps.add(j, s);
        stepList.getSelectionModel().select(j);
        touched();
    }

    private void replaceStep(int index, MacroStep step) {
        if (index >= 0 && index < steps.size() && !Objects.equals(steps.get(index), step)) {
            loading = true; // the list re-selects on set(); the detail must not be rebuilt under the user's hands
            try {
                steps.set(index, step);
                stepList.getSelectionModel().select(index);
            } finally {
                loading = false;
            }
            dirty.set(true);
        }
    }

    /** Rebuilds the editor under the list for the selected step, by kind. */
    private void showStepDetail() {
        if (loading) {
            return;
        }
        stepDetail.getChildren().clear();
        int index = stepList.getSelectionModel().getSelectedIndex();
        if (index < 0 || index >= steps.size()) {
            return;
        }
        MacroStep step = steps.get(index);
        Label kind = new Label(tr(kindKey(step)));
        kind.getStyleClass().add("settings-section");
        kind.setMinWidth(Region.USE_PREF_SIZE);
        if (step.isCommand()) {
            Label value = new Label(commandLabel(step.value()));
            value.setWrapText(true);
            HBox.setHgrow(value, Priority.ALWAYS);
            value.setMaxWidth(Double.MAX_VALUE);
            Button choose = new Button(tr("settings.macro.chooseCommand"));
            choose.getStyleClass().add("macro-step-input");
            fitToLabel(choose);
            choose.setOnAction(e -> {
                String id = chooseCommand(step.value());
                if (id != null) {
                    replaceStep(index, MacroStep.command(id));
                    showStepDetail();
                }
            });
            HBox row = new HBox(8, kind, value, choose);
            row.setAlignment(Pos.CENTER_LEFT);
            stepDetail.getChildren().add(row);
            return;
        }
        CheckBox prompt = new CheckBox(tr("settings.macro.inPrompt"));
        prompt.setSelected(step.isPrompt());
        prompt.setWrapText(true);
        if (step.isKey()) {
            TextField capture = new TextField(keyLabel(step.value()));
            capture.getStyleClass().add("macro-step-input");
            capture.setEditable(false);
            capture.setPromptText(tr("settings.macro.pressKey"));
            HBox.setHgrow(capture, Priority.ALWAYS);
            // A filter, and every key consumed: Tab, Enter and Escape are exactly the keys worth capturing,
            // and the field would otherwise traverse, fire or close on them.
            capture.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                e.consume();
                if (e.getCode() == null || e.getCode().isModifierKey() || e.getCode() == KeyCode.UNDEFINED) {
                    return;
                }
                String token = MacroKey.encode(
                        e.isControlDown(),
                        e.isAltDown(),
                        e.isMetaDown(),
                        e.isShiftDown(),
                        e.getCode().name());
                capture.setText(keyLabel(token));
                replaceStep(index, MacroStep.key(token, prompt.isSelected()));
            });
            capture.addEventFilter(KeyEvent.KEY_TYPED, KeyEvent::consume);
            prompt.setOnAction(
                    e -> replaceStep(index, MacroStep.key(steps.get(index).value(), prompt.isSelected())));
            Label hint = new Label(tr("settings.macro.pressKey"));
            hint.getStyleClass().add("settings-hint");
            HBox row = new HBox(8, kind, capture);
            row.setAlignment(Pos.CENTER_LEFT);
            stepDetail.getChildren().addAll(row, hint, prompt);
            return;
        }
        // Multi-line: an Enter typed here is an Enter in the macro. (The single-line field this replaces
        // dropped every line break and tab on the way in.)
        TextArea text = new TextArea(step.value().replace("\r\n", "\n").replace('\r', '\n'));
        text.getStyleClass().add("macro-step-input");
        text.setPrefRowCount(3);
        text.setWrapText(false);
        HBox.setHgrow(text, Priority.ALWAYS);
        text.textProperty()
                .addListener((o, was, now) ->
                        replaceStep(index, MacroStep.text(now == null ? "" : now, prompt.isSelected())));
        prompt.setOnAction(
                e -> replaceStep(index, MacroStep.text(steps.get(index).value(), prompt.isSelected())));
        HBox row = new HBox(8, kind, text);
        row.setAlignment(Pos.TOP_LEFT);
        Label hint = new Label(tr("settings.macro.textHint"));
        hint.getStyleClass().add("settings-hint");
        hint.setWrapText(true);
        stepDetail.getChildren().addAll(row, hint, prompt);
    }

    private static String kindKey(MacroStep step) {
        return step.isCommand()
                ? "settings.macro.kind.command"
                : step.isKey() ? "settings.macro.kind.key" : "settings.macro.kind.text";
    }

    /** One line of the step list: what kind of step it is, then its value in a form that can be read. */
    String stepLabel(MacroStep s) {
        String value;
        if (s.isCommand()) {
            value = commandLabel(s.value());
        } else if (s.isKey()) {
            value = keyLabel(s.value());
        } else {
            value = "\"" + visible(s.value()) + "\"";
        }
        String label = tr(kindKey(s)) + "   " + value;
        return s.isPrompt() ? tr("settings.macro.step.inPrompt", label) : label;
    }

    /** Text with its line breaks and tabs spelled out, so a step holding only an Enter is not an empty row. */
    static String visible(String text) {
        return (text == null ? "" : text)
                .replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    /**
     * A key step as the user reads keys elsewhere in the app: through the active keymap's notation
     * ({@code S-tab} / {@code Shift+Tab} / {@code ⇧⇥}). An empty token is a step still waiting for its key;
     * one that names no key is shown as written.
     */
    static String keyLabel(String token) {
        MacroKey.Decoded k = MacroKey.decode(token);
        if (k == null) {
            return "";
        }
        KeyCode code;
        try {
            code = KeyCode.valueOf(k.keyCodeName());
        } catch (IllegalArgumentException e) {
            return token;
        }
        String chord = KeyDispatcher.chord(
                new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, k.shift(), k.ctrl(), k.alt(), k.meta()));
        KeymapManager keymap = TextInputKeymap.sharedKeymap();
        return chord == null ? token : keymap == null ? chord : keymap.display(chord);
    }

    private boolean commandExists(String id) {
        if (host.shortcuts() == null) {
            return true; // nothing to check against yet
        }
        for (SettingsWindow.Shortcut s : host.shortcuts().rows()) {
            if (s.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private String commandLabel(String id) {
        if (id == null || id.isBlank()) {
            return "…";
        }
        if (host.shortcuts() != null) {
            for (SettingsWindow.Shortcut s : host.shortcuts().rows()) {
                if (s.id().equals(id)) {
                    return s.title() + "  (" + id + ")";
                }
            }
            return tr("settings.macro.step.unknownCommand", id);
        }
        return id;
    }

    /**
     * Asks for a command from the ones that exist, by title or id — rather than a free-text id, which could
     * name nothing and then fail without a word at replay. Returns null when cancelled.
     */
    private String chooseCommand(String current) {
        if (host.shortcuts() == null) {
            return null;
        }
        ObservableList<SettingsWindow.Shortcut> all =
                FXCollections.observableArrayList(host.shortcuts().rows());
        FilteredList<SettingsWindow.Shortcut> shown = new FilteredList<>(all);
        TextField filter = new TextField();
        filter.setPromptText(tr("settings.macro.chooseCommand.filter"));
        ListView<SettingsWindow.Shortcut> commands = new ListView<>(shown);
        commands.setPrefSize(520, 360);
        commands.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(SettingsWindow.Shortcut s, boolean empty) {
                super.updateItem(s, empty);
                setText(empty || s == null ? null : s.title() + "  —  " + s.id());
            }
        });
        filter.textProperty().addListener((o, was, now) -> {
            String q = now == null ? "" : now.strip().toLowerCase(Locale.ROOT);
            shown.setPredicate(s -> q.isEmpty()
                    || s.title().toLowerCase(Locale.ROOT).contains(q)
                    || s.id().toLowerCase(Locale.ROOT).contains(q));
            if (!shown.isEmpty()) {
                commands.getSelectionModel().select(0);
            }
        });
        for (SettingsWindow.Shortcut s : all) {
            if (s.id().equals(current)) {
                commands.getSelectionModel().select(s);
                commands.scrollTo(s);
                break;
            }
        }
        Dialog<ButtonType> dialog = Dialogs.styled(new Dialog<ButtonType>());
        dialog.initOwner(getScene() == null ? null : getScene().getWindow());
        dialog.setTitle(tr("settings.macro.chooseCommand.title"));
        dialog.getDialogPane().setContent(new VBox(8, filter, commands));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Node ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.disableProperty()
                .bind(commands.getSelectionModel().selectedItemProperty().isNull());
        filter.setOnKeyPressed(e -> {
            int i = commands.getSelectionModel().getSelectedIndex();
            if (e.getCode() == KeyCode.DOWN) {
                commands.getSelectionModel().select(Math.min(shown.size() - 1, i + 1));
                commands.scrollTo(commands.getSelectionModel().getSelectedIndex());
                e.consume();
            } else if (e.getCode() == KeyCode.UP) {
                commands.getSelectionModel().select(Math.max(0, i - 1));
                commands.scrollTo(commands.getSelectionModel().getSelectedIndex());
                e.consume();
            }
        });
        commands.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && commands.getSelectionModel().getSelectedItem() != null) {
                dialog.setResult(ButtonType.OK);
                dialog.close();
            }
        });
        Platform.runLater(filter::requestFocus);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return null;
        }
        SettingsWindow.Shortcut picked = commands.getSelectionModel().getSelectedItem();
        return picked == null ? null : picked.id();
    }

    /** The working copy's steps, for tests. */
    ObservableList<MacroStep> steps() {
        return steps;
    }

    ListView<Macro> macroList() {
        return list;
    }

    ListView<MacroStep> stepList() {
        return stepList;
    }

    WrapRow keybindingRow() {
        return keybinding;
    }

    boolean isDirty() {
        return dirty.get();
    }
}
