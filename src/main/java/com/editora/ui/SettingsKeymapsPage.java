package com.editora.ui;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import com.editora.config.ConfigManager;
import com.editora.editor.UiZoom;
import com.editora.ui.SettingsWindow.Card;
import com.editora.ui.SettingsWindow.Category;
import com.editora.ui.SettingsWindow.Shortcut;
import com.editora.ui.SettingsWindow.ShortcutActions;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Settings → Keymaps (the keymap picker and the searchable shortcut list with Record / Reset / Remove) and Macros
 * (the {@link MacroSettingsPane} and its note), with the chord chips other pages show for a command. The keybinding
 * backend is {@link SettingsWindow}'s {@link SettingsWindow.ShortcutActions}, injected there.
 */
final class SettingsKeymapsPage {

    private final SettingsWindow w;
    private final ConfigManager config;

    SettingsKeymapsPage(SettingsWindow w, ConfigManager config) {
        this.w = w;
        this.config = config;
    }

    @Initializer
    void buildControls() {
        keymapCombo = new ComboBox<>();
        keymapCombo.getItems().addAll(com.editora.command.KeymapManager.AVAILABLE.keySet());
        UiZoom.prefWidth(keymapCombo, 220);
        keymapCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String id) {
                return id == null ? "" : com.editora.command.KeymapManager.displayName(id);
            }

            @Override
            public String fromString(String s) {
                return s;
            }
        });
        keymapCombo.valueProperty().addListener((obs, was, now) -> {
            if (w.loading || now == null) {
                return;
            }
            KeymapLayers.switchKeymap(config.getSettings(), now);
            config.save();
            if (w.onKeymapChanged != null) {
                w.onKeymapChanged.run(); // reload the shared keymap live across all windows
            }
            refreshShortcuts(); // the list below and the chord chips show the keymap that is now live
        });
    }

    private String pushedShortcutQuery = ""; // the search last carried into the Keymaps shortcut filter

    ComboBox<String> keymapCombo;

    /** Command id -> its row's chord chip, filled in once shortcutActions arrives. */
    private final Map<String, Label> chordChips = new java.util.LinkedHashMap<>();

    TextField shortcutFilter; // filters the shortcut list
    /**
     * The shortcuts, one row for each command the filter lets through, in a virtualized list (PERF-08, Pauta
     * 6.0.1): about 450 commands, more with plugins and macros, and only the rows on screen have nodes. The list is
     * one Tab stop; its own selection is the row the arrow keys are on.
     */
    private @Nullable ListView<Shortcut> shortcutList;

    private @Nullable String recordingCommandId; // command id whose row is currently capturing a chord, or null
    private @Nullable String selectedShortcutId; // command id of the selected row (shows its Record/Reset), or null
    private final Map<String, HBox> shortcutRowsById = new HashMap<>(); // the rows on screen, by command id
    /** The list's cells, as many as fit on screen (weakly held: a cell the list lets go of is forgotten). */
    private final java.util.Set<ShortcutCell> shortcutCells =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** How many rows have been built since the page was: a filter keystroke builds the rows on screen only. */
    int shortcutRowsBuilt;

    /** The Macros page's editor (see {@link MacroSettingsPane}); null until that page is built. */
    private @Nullable MacroSettingsPane macroPane;
    /** The Macros page's note, which names the chords of the live keymap. */
    private @Nullable Label macroNote;
    /** Re-registers the {@code macro.run.*} commands across windows after a Macros-page edit. */
    private Runnable onMacrosChanged = () -> {};

    @Initializer
    VBox keymapsPage() {
        VBox p = w.page(tr("settings.cat.keymaps"), tr("settings.keymaps.subtitle"));
        Card mainCard = w.card(p, null);
        w.controlRow(
                mainCard,
                Category.KEYMAPS,
                tr("settings.keymap"),
                tr("settings.keymap.note"),
                keymapCombo,
                "keymap keybindings shortcuts emacs cua sublime vscode intellij");

        // --- Customize shortcuts: searchable list of every command + its current chord ---
        Card sec = w.card(p, tr("settings.shortcuts.title"));
        shortcutFilter = new TextField();
        shortcutFilter.setPromptText(tr("settings.shortcuts.filter"));
        shortcutFilter.textProperty().addListener((o, was, now) -> refreshShortcuts());
        ListView<Shortcut> shortcutList = new ListView<>();
        this.shortcutList = shortcutList;
        shortcutList.getStyleClass().add("shortcut-list");
        shortcutList.setCellFactory(view -> new ShortcutCell());
        shortcutList.addEventHandler(KeyEvent.KEY_PRESSED, this::onShortcutListKey);
        // Tab lands on the selected row (the one that shows Record and Reset), else on the first.
        shortcutList.focusedProperty().addListener((o, was, now) -> {
            if (now
                    && shortcutList.getSelectionModel().isEmpty()
                    && !shortcutList.getItems().isEmpty()) {
                shortcutList.getSelectionModel().select(Math.max(0, indexOfShortcut(selectedShortcutId)));
            }
        });
        // The list takes the height the window has (a fixed 320 px showed ~13 of ~900 rows above empty space).
        shortcutList
                .prefHeightProperty()
                .bind(javafx.beans.binding.Bindings.max(
                        UiZoom.factorProperty().multiply(320),
                        w.stage
                                .heightProperty()
                                .subtract(UiZoom.factorProperty().multiply(415))));
        Label note = SettingsWindow.note(tr("settings.shortcuts.note"));
        note.setWrapText(true);
        Button resetAll =
                FocusCommands.press(new Button(tr("settings.shortcuts.resetAll")), "settings.resetAllShortcuts");
        resetAll.setOnAction(e -> {
            if (w.shortcutActions != null && confirmResetAllShortcuts()) {
                w.shortcutActions.resetAll();
                refreshShortcuts();
            }
        });
        VBox box = UiZoom.spacing(new VBox(note, shortcutFilter, shortcutList, resetAll), 6);
        w.cardRow(
                sec,
                Category.KEYMAPS,
                box,
                "shortcut keybinding customize rebind record reset clear key chord accelerator");
        refreshShortcuts();
        return p;
    }

    /**
     * Asks before every custom binding is dropped. One click used to wipe them all — including the chords
     * given to macros and external tools on other pages — while Reset to Defaults, which keeps them, asked.
     */
    private boolean confirmResetAllShortcuts() {
        return Confirm.ask(
                w.stage,
                resetAllQuestion(
                        com.editora.command.KeymapManager.displayName(com.editora.command.KeymapManager.resolveName(
                                config.getSettings().getKeymap()))),
                460);
    }

    /** The words of the question before every custom binding of the keymap shown as {@code keymap} is dropped. */
    static Confirm.Question resetAllQuestion(String keymap) {
        return new Confirm.Question(
                tr("dialog.shortcut.resetAll.title"),
                null,
                tr("dialog.shortcut.resetAll.body", keymap),
                tr("confirm.action.reset"));
    }

    /**
     * The words of the question before {@code chord} is bound although it is taken: {@code taken} has one
     * {@link #conflictLine} for each binding the new one affects.
     */
    static Confirm.Question bindConflictQuestion(String chord, List<String> taken) {
        StringBuilder affected = new StringBuilder();
        for (String line : taken) {
            affected.append("\n   ").append(line);
        }
        return new Confirm.Question(
                tr("dialog.shortcut.conflict.title"),
                null,
                tr("dialog.shortcut.conflict.body", chord, affected.toString()),
                tr("confirm.action.bind"));
    }

    /**
     * The words of the question before the command titled {@code commandTitle} is reset to its default chord, which
     * other commands hold: {@code taken} has one {@link #conflictLine} for each.
     */
    static Confirm.Question reclaimQuestion(String commandTitle, List<String> taken) {
        StringBuilder lines = new StringBuilder();
        for (String line : taken) {
            lines.append("\n   ").append(line);
        }
        return new Confirm.Question(
                tr("dialog.shortcut.conflict.title"),
                null,
                tr("dialog.shortcut.reclaim.body", commandTitle, lines.toString()),
                tr("confirm.action.reset"));
    }

    /** One binding a new one would replace: its chord as shown, and the title of the command it runs. */
    static String conflictLine(String chord, String commandTitle) {
        return chord + "  —  " + commandTitle;
    }

    /**
     * Fills the shortcut list from the backend, honoring the filter, and refills the chord chips on the other
     * pages (both show the live keymap, so they go stale together). Only the rows on screen are built, here and
     * as the list scrolls. The list part is a no-op until the backend is set and the page is built.
     */
    void refreshShortcuts() {
        refreshShortcuts(null);
    }

    /** {@link #refreshShortcuts()}, then moves the keyboard to {@code focusId}'s row, in the list. */
    private void refreshShortcuts(@Nullable String focusId) {
        refreshChordChips();
        if (macroPane != null && macroNote != null) { // built together on the Macros page
            macroPane.refreshKeybinding();
            macroNote.setText(macroNoteText());
        }
        ListView<Shortcut> list = shortcutList;
        if (list == null || w.shortcutActions == null) {
            return;
        }
        String q = shortcutFilter == null ? "" : shortcutFilter.getText();
        List<Shortcut> rows = List.copyOf(w.shortcutActions.rows());
        if (q.isBlank()) {
            filterSearch.cancel();
            showShortcuts(list, rows, focusId); // nothing to match: every row
            return;
        }
        String typedChord = com.editora.command.ChordQuery.parse(q);
        String chordShown = typedChord == null ? null : w.shortcutActions.display(typedChord);
        // Matched off the FX thread (PERF-01, Pauta 6.0.1): a row per command, several hundred; the rows shown stay
        // until the latest filter's answer lands.
        filterSearch.submit(
                () -> {
                    com.editora.perf.BlockingSites.enter(FILTER_SITE);
                    List<Shortcut> shown = new java.util.ArrayList<>();
                    for (Shortcut s : rows) {
                        if (shortcutMatches(s, q, chordShown)) {
                            shown.add(s);
                        }
                    }
                    return shown;
                },
                shown -> showShortcuts(list, shown, focusId));
    }

    /** Where the shortcut list's filter is matched against the commands: a picker thread, never the FX thread. */
    static final String FILTER_SITE = "SettingsKeymapsPage.filter";

    /** The filter in flight: a newer keystroke supersedes it, and only the latest answer is shown. */
    private final LatestOnly filterSearch = PickerSearch.latest();

    /** Whether the shortcut list is not yet filtered for the text typed (a test waits on it). */
    boolean filtering() {
        return filterSearch.pending();
    }

    /** Shows {@code shown} in the list, keeping the cursor's command (or moving it to {@code focusId}'s). */
    private void showShortcuts(ListView<Shortcut> list, List<Shortcut> shown, @Nullable String focusId) {
        Shortcut cursor = list.getSelectionModel().getSelectedItem();
        String keep = focusId != null ? focusId : cursor == null ? null : cursor.id();
        list.getItems().setAll(shown);
        // The list builds a row again when its shortcut changes. A row that became the selected or the recording
        // row, or stopped being it, has the same shortcut and other controls: the rows on screen are asked.
        List.copyOf(shortcutCells).forEach(ShortcutCell::restate);
        int at = indexOfShortcut(keep);
        if (at >= 0) {
            list.getSelectionModel().select(at);
        }
        if (focusId != null && at >= 0) {
            revealShortcut(at);
            javafx.application.Platform.runLater(list::requestFocus);
        }
    }

    /** The place of command {@code id} in the list as filtered, or -1. */
    private int indexOfShortcut(@Nullable String id) {
        ListView<Shortcut> list = shortcutList;
        if (list == null || id == null) {
            return -1;
        }
        List<Shortcut> items = list.getItems();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /** Scrolls the list just far enough to show row {@code index}; a row already in view stays where it is. */
    private void revealShortcut(int index) {
        ListView<Shortcut> list = shortcutList;
        if (list == null) {
            return;
        }
        if (list.lookup(".virtual-flow") instanceof javafx.scene.control.skin.VirtualFlow<?> flow) {
            flow.scrollTo(index);
        } else {
            list.scrollTo(index); // not laid out yet: the list opens on the row
        }
    }

    /**
     * The keys of the list itself (a key pressed on a row's own Record or Reset button is the button's): Enter and
     * Space select the row the arrow keys are on, or start recording when it is selected already; Delete and
     * Backspace unbind it. The arrow keys, Home and End are the list's own and stop at either end.
     */
    private void onShortcutListKey(KeyEvent e) {
        ListView<Shortcut> list = shortcutList;
        ShortcutActions actions = w.shortcutActions;
        if (list == null
                || actions == null
                || e.getTarget() != list
                || e.isControlDown()
                || e.isAltDown()
                || e.isMetaDown()) {
            return;
        }
        Shortcut s = list.getSelectionModel().getSelectedItem();
        if (s == null) {
            return;
        }
        if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
            selectShortcut(s.id());
            e.consume();
        } else if (e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE) {
            if (s.chord() != null) {
                removeShortcut(actions, s.id());
            }
            e.consume();
        }
    }

    /** Selects command {@code id}'s row (it shows Record, Reset and Remove); on the selected row, starts recording. */
    private void selectShortcut(String id) {
        if (id.equals(selectedShortcutId)) {
            recordingCommandId = id; // a second Enter on the selected row starts recording
            refreshShortcuts();
        } else {
            selectedShortcutId = id;
            refreshShortcuts(id);
        }
    }

    /** A row of the list: the shortcut's own line, built for the rows on screen only. */
    private final class ShortcutCell extends ListCell<Shortcut> {
        private @Nullable String shownId;
        private @Nullable HBox shownRow;
        private boolean shownSelected;
        private boolean shownRecording;

        ShortcutCell() {
            setPrefWidth(0); // never wider than the list
            shortcutCells.add(this);
        }

        /** Builds the row again when it became, or stopped being, the selected or the recording row. */
        void restate() {
            ListView<Shortcut> list = getListView();
            Shortcut s = getItem();
            ShortcutActions actions = w.shortcutActions;
            int at = getIndex();
            if (isEmpty() || s == null || actions == null || list == null || !isVisible()) {
                return;
            }
            if (at < 0
                    || at >= list.getItems().size()
                    || !s.equals(list.getItems().get(at))) {
                return; // the list has another shortcut for this row: its next layout shows it
            }
            if (shownSelected != s.id().equals(selectedShortcutId)
                    || shownRecording != s.id().equals(recordingCommandId)) {
                show(s, actions);
            }
        }

        private void show(Shortcut s, ShortcutActions actions) {
            if (shownId != null && shortcutRowsById.get(shownId) == shownRow) {
                shortcutRowsById.remove(shownId);
            }
            HBox row = shortcutRow(s, actions);
            shownId = s.id();
            shownRow = row;
            shownSelected = s.id().equals(selectedShortcutId);
            shownRecording = s.id().equals(recordingCommandId);
            shortcutRowsById.put(s.id(), row);
            setGraphic(row);
            setAccessibleText(s.title() + ", " + (s.chord() == null ? tr("settings.shortcuts.unbound") : s.chord()));
        }

        @Override
        protected void updateItem(Shortcut s, boolean empty) {
            super.updateItem(s, empty);
            setText(null);
            ShortcutActions actions = w.shortcutActions;
            if (empty || s == null || actions == null) {
                if (shownId != null && shortcutRowsById.get(shownId) == shownRow) {
                    shortcutRowsById.remove(shownId);
                }
                shownId = null;
                shownRow = null;
                setGraphic(null);
                setAccessibleText(null);
                return;
            }
            show(s, actions);
        }
    }

    /**
     * Whether a shortcut row matches the filter: every word of {@code query} in its title or id ("file save"
     * finds "File: Save"), or in its chord as text; or — when the query reads as a chord in any notation — that
     * chord, shown as {@code chordShown}, is the row's chord or the start of it. Pure.
     */
    static boolean shortcutMatches(Shortcut s, String query, @Nullable String chordShown) {
        // A query that reads as a chord is matched as one: as text "Ctrl+S" also hit "Ctrl+Shift+S".
        String chordText = chordShown != null || s.chord() == null ? "" : s.chord();
        String text = (s.title() + ' ' + s.id() + ' ' + chordText).toLowerCase(Locale.ROOT);
        boolean words = true;
        for (String w :
                (query == null ? "" : query).toLowerCase(Locale.ROOT).strip().split("\\s+")) {
            words &= text.contains(w);
        }
        return words
                || (chordShown != null
                        && s.chord() != null
                        && (s.chord().equals(chordShown) || s.chord().startsWith(chordShown + ' ')));
    }

    /** A single command row — either the static chord + Record/Reset buttons, or a live capture field. */
    private HBox shortcutRow(Shortcut s, ShortcutActions actions) {
        shortcutRowsBuilt++;
        HBox row = UiZoom.spacing(new HBox(), 8);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        row.getStyleClass().add("shortcut-row");
        Label title = new Label(s.title());
        title.setMaxWidth(Double.MAX_VALUE);
        title.setMinWidth(0); // a row of a list has one height: a title too long for it ends in an ellipsis
        HBox.setHgrow(title, Priority.ALWAYS);

        if (s.id().equals(recordingCommandId)) {
            row.getStyleClass().add("shortcut-row-recording");
            // Enter saves and Escape cancels from inside the field: it records Tab like any other key, so
            // the Save button beside it cannot be reached from the keyboard.
            TextField capture = ShortcutCapture.field(actions::display, seq -> commitRecording(s.id(), seq), () -> {
                recordingCommandId = null;
                refreshShortcuts(s.id());
            });
            Button save = new Button(tr("settings.shortcuts.save"));
            save.getStyleClass().add("success");
            save.setDefaultButton(false);
            save.setOnAction(e -> commitRecording(s.id(), ShortcutCapture.recorded(capture)));
            Button cancel = new Button(tr("settings.shortcuts.cancel"));
            cancel.setOnAction(e -> {
                recordingCommandId = null;
                refreshShortcuts(s.id());
            });
            row.getChildren().addAll(title, capture, save, cancel);
            javafx.application.Platform.runLater(capture::requestFocus);
        } else {
            String chordText = s.chord() == null ? tr("settings.shortcuts.unbound") : s.chord();
            Label chord = new Label(chordText);
            chord.getStyleClass().add(s.chord() == null ? "shortcut-unbound" : "shortcut-chord");
            UiZoom.minWidth(chord, 150);
            row.getChildren().addAll(title, chord);
            // Record/Reset are shown only for the selected row, keeping the list uncluttered. A click
            // selects a row; from the keyboard the arrow keys walk the rows and Enter/Space selects
            // (onShortcutListKey).
            row.getStyleClass().add("shortcut-row-clickable");
            Button record = new Button(tr("settings.shortcuts.record"));
            row.setOnMouseClicked(e -> {
                if (!s.id().equals(selectedShortcutId)) {
                    selectShortcut(s.id());
                }
            });
            if (s.id().equals(selectedShortcutId)) {
                row.getStyleClass().add("shortcut-row-selected");
                record.setOnAction(e -> {
                    recordingCommandId = s.id();
                    refreshShortcuts();
                });
                Button reset = new Button(tr("settings.shortcuts.reset"));
                reset.setOnAction(e -> resetShortcut(actions, s.id()));
                Button remove = new Button(tr("settings.shortcuts.remove"));
                remove.setDisable(s.chord() == null);
                remove.setOnAction(e -> removeShortcut(actions, s.id()));
                row.getChildren().addAll(record, reset, remove);
                // The selected row's buttons are the only ones: their commands act on that shortcut.
                FocusCommands.press(record, "settings.recordShortcut");
                FocusCommands.press(reset, "settings.resetShortcut");
                FocusCommands.press(remove, "settings.removeShortcut");
            }
        }
        return row;
    }

    /** Unbinds a command from the list (its Remove button, or Delete on its row). */
    private void removeShortcut(ShortcutActions actions, String commandId) {
        actions.clear(commandId);
        refreshShortcuts(commandId);
    }

    /** Resets a command to its keymap default, asking first when that takes a chord back from another command. */
    private void resetShortcut(ShortcutActions actions, String commandId) {
        List<com.editora.command.KeybindingEdits.Conflict> taken = actions.takenDefaults(commandId);
        if (!taken.isEmpty()) {
            List<String> lines = new java.util.ArrayList<>();
            for (var c : taken) {
                lines.add(conflictLine(actions.display(c.chord()), titleOf(actions, c.commandId())));
            }
            if (!w.askBeforeLoss(reclaimQuestion(titleOf(actions, commandId), lines))) {
                return;
            }
        }
        actions.reset(commandId);
        refreshShortcuts(commandId);
    }

    /** Commits a recorded chord sequence to a command, warning first if it steals another command's chord. */
    private void commitRecording(String commandId, String sequence) {
        recordingCommandId = null;
        rebindWithConflictCheck(commandId, sequence);
        refreshShortcuts(commandId);
    }

    /** Rebinds {@code commandId} to {@code sequence}, warning on a conflict; returns whether it bound. Shared
     *  by the Keymaps shortcut editor and the inline Macros keybinding row. */
    private boolean rebindWithConflictCheck(String commandId, String sequence) {
        if (w.shortcutActions == null) {
            return false;
        }
        String seq = sequence.trim();
        if (seq.isEmpty()) {
            return false;
        }
        if (com.editora.command.KeybindingEdits.typesText(seq)) {
            // Bound, the key would run the command instead of typing its character, everywhere.
            macroWarn(tr("settings.shortcuts.typesText", w.shortcutActions.display(seq.split(" ")[0])));
            return false;
        }
        List<com.editora.command.KeybindingEdits.Conflict> conflicts = w.shortcutActions.conflicts(seq, commandId);
        if (!conflicts.isEmpty()) {
            List<String> taken = new java.util.ArrayList<>();
            for (var c : conflicts) {
                taken.add(
                        conflictLine(w.shortcutActions.display(c.chord()), titleOf(w.shortcutActions, c.commandId())));
            }
            if (!Confirm.ask(w.stage, bindConflictQuestion(w.shortcutActions.display(seq), taken), 460)) {
                return false;
            }
        }
        w.shortcutActions.rebind(commandId, seq);
        return true;
    }

    /** The display title of a command id (from the shortcut rows), falling back to the id itself. */
    private String titleOf(ShortcutActions actions, String commandId) {
        return actions.rows().stream()
                .filter(r -> r.id().equals(commandId))
                .map(Shortcut::title)
                .findFirst()
                .orElse(commandId);
    }

    /** Injects the cross-window macro re-register hook (→ {@code MainController}); used by the Macros page. */
    void setMacrosChangedHandler(Runnable handler) {
        this.onMacrosChanged = handler == null ? () -> {} : handler;
    }

    /** Re-reads the Macros page's list from the store — called after a macro changes from outside this
     *  window (e.g. a recording that was just stopped), so an already-open Settings window reflects it live
     *  instead of only on the next time the page is built. Harmless if the page was never opened. */
    void refreshMacrosList() {
        if (macroPane != null) {
            macroPane.refresh();
        }
    }

    VBox macrosPage() {
        VBox p = w.page(tr("settings.cat.macros"), tr("settings.macros.subtitle"));
        Card mainCard = w.card(p, null);
        macroPane = new MacroSettingsPane(new MacroSettingsPane.Host() {
            @Override
            public ConfigManager config() {
                return config;
            }

            @Override
            public @Nullable ShortcutActions shortcuts() {
                return w.shortcutActions;
            }

            @Override
            public void macrosChanged() {
                onMacrosChanged.run();
            }

            @Override
            public boolean rebind(String commandId, String chord) {
                return rebindWithConflictCheck(commandId, chord);
            }

            @Override
            public void shortcutsChanged() {
                refreshShortcuts();
            }
        });
        w.cardRow(
                mainCard,
                Category.MACROS,
                macroPane,
                "macros keyboard record replay steps rename delete keybinding command text key");
        macroNote = SettingsWindow.note(macroNoteText());
        macroNote.setWrapText(true);
        UiZoom.maxWidth(macroNote, 460);
        w.cardRow(mainCard, Category.MACROS, macroNote, "macros record start stop replay keybinding save cancel");
        return p;
    }

    /**
     * How to record, in the chords of the keymap in use. The note used to say "F3 (start) and F4 (stop)",
     * which only the Emacs keymap binds — in CUA and Sublime F3 is Find Next.
     */
    private String macroNoteText() {
        return tr(
                "settings.macro.note",
                macroCommandHint("macro.startRecording"),
                macroCommandHint("macro.stopRecording"),
                macroCommandHint("macro.replayLast"));
    }

    /** A command by title with its chord, or with the fact that it has none. */
    private String macroCommandHint(String commandId) {
        String title = tr("command." + commandId);
        String chord = currentChordFor(commandId);
        return chord == null || chord.isBlank()
                ? tr("settings.macro.note.unbound", title)
                : tr("settings.macro.note.bound", title, chord);
    }

    @Nullable
    String currentChordFor(String commandId) {
        if (w.shortcutActions == null) {
            return null;
        }
        for (Shortcut s : w.shortcutActions.rows()) {
            if (s.id().equals(commandId)) {
                return s.chord();
            }
        }
        return null;
    }

    void macroWarn(String message) {
        Alert a = Dialogs.styled(new Alert(Alert.AlertType.WARNING, message, ButtonType.OK));
        a.initOwner(w.stage);
        a.setHeaderText(null);
        Dialogs.showAndWait(a);
    }

    /**
     * The Keymaps shortcut list is searched as a list of its own (its hundreds of command names are not the
     * page's text): a search it matches carries over into its filter, and clearing the search clears it again.
     */
    void pushShortcutQuery(String query, boolean listHit) {
        if (shortcutFilter == null) {
            return;
        }
        String q = query == null ? "" : query.strip();
        String shown = shortcutFilter.getText();
        boolean ours = shown.isEmpty() || shown.equals(pushedShortcutQuery);
        pushedShortcutQuery = q.isEmpty() || !listHit ? "" : q; // the list was searched with the rows, off FX
        if (ours) {
            shortcutFilter.setText(pushedShortcutQuery);
        }
    }

    /** Whether a command in the shortcut list matches {@code query} as its own filter would. */
    boolean shortcutMatches(String query) {
        return shortcutMatcher().test(query);
    }

    /**
     * {@link #shortcutMatches(String)} over a copy of the rows as they are now: taken on the FX thread, asked on any
     * (the Settings search asks it off the FX thread).
     */
    java.util.function.Predicate<String> shortcutMatcher() {
        List<Shortcut> rows = w.shortcutActions == null ? List.of() : List.copyOf(w.shortcutActions.rows());
        return query -> {
            String q = query.toLowerCase(Locale.ROOT);
            return rows.stream()
                    .anyMatch(s -> s.title().toLowerCase(Locale.ROOT).contains(q)
                            || s.id().toLowerCase(Locale.ROOT).contains(q));
        };
    }

    /**
     * A periwinkle chord chip for {@code commandId} (UI Kit v1: a Settings row teaches the keyboard
     * instead of hiding it). The binding is not known when pages are built — {@link ShortcutActions} is
     * injected afterwards — so the chip starts empty and {@link #refreshChordChips()} fills it in. An
     * unbound command shows nothing at all rather than an empty chip.
     */
    Label chordChip(String commandId) {
        Label chip = new Label();
        chip.getStyleClass().add("chord-chip");
        SettingsSearch.setShown(chip, false);
        chordChips.put(commandId, chip);
        return chip;
    }

    /** Fills every {@link #chordChip} from the live keymap; hides the chip for an unbound command. */
    private void refreshChordChips() {
        if (w.shortcutActions == null || chordChips.isEmpty()) {
            return;
        }
        Map<String, String> byId = new HashMap<>();
        for (Shortcut s : w.shortcutActions.rows()) {
            if (s.chord() != null && !s.chord().isBlank()) {
                byId.put(s.id(), s.chord());
            }
        }
        chordChips.forEach((id, chip) -> {
            String chord = byId.get(id);
            chip.setText(chord == null ? "" : chord);
            SettingsSearch.setShown(chip, chord != null);
        });
    }
}
