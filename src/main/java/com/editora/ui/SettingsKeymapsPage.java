package com.editora.ui;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
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
    private @Nullable VBox shortcutListBox; // rebuilt from shortcutActions.rows() on each change/filter
    private @Nullable String recordingCommandId; // command id whose row is currently capturing a chord, or null
    private @Nullable String selectedShortcutId; // command id of the selected row (shows its Record/Reset), or null
    private @Nullable ScrollPane shortcutScroll;
    private final Map<String, HBox> shortcutRowsById = new HashMap<>(); // the rows currently in the list
    private @Nullable HBox shortcutTabStop; // the one row Tab lands on; the arrow keys move between rows

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
        shortcutListBox = UiZoom.spacing(new VBox(), 2);
        shortcutListBox.getStyleClass().add("shortcut-list");
        ScrollPane scroll = new ScrollPane(shortcutListBox);
        shortcutScroll = scroll;
        scroll.setFitToWidth(true);
        // The list takes the height the window has (a fixed 320 px showed ~13 of ~900 rows above empty space).
        scroll.prefHeightProperty()
                .bind(javafx.beans.binding.Bindings.max(
                        UiZoom.factorProperty().multiply(320),
                        w.stage
                                .heightProperty()
                                .subtract(UiZoom.factorProperty().multiply(415))));
        scroll.getStyleClass().add("shortcut-scroll");
        Label note = SettingsWindow.note(tr("settings.shortcuts.note"));
        note.setWrapText(true);
        Button resetAll = new Button(tr("settings.shortcuts.resetAll"));
        resetAll.setOnAction(e -> {
            if (w.shortcutActions != null && confirmResetAllShortcuts()) {
                w.shortcutActions.resetAll();
                refreshShortcuts();
            }
        });
        VBox box = UiZoom.spacing(new VBox(note, shortcutFilter, scroll, resetAll), 6);
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
        ButtonType reset = Dialogs.action(tr("confirm.action.reset"));
        Alert confirm = Dialogs.styled(new Alert(
                Alert.AlertType.CONFIRMATION,
                tr(
                        "dialog.shortcut.resetAll.body",
                        com.editora.command.KeymapManager.displayName(com.editora.command.KeymapManager.resolveName(
                                config.getSettings().getKeymap()))),
                reset,
                Dialogs.cancel()));
        confirm.initOwner(w.stage);
        confirm.setTitle(tr("dialog.shortcut.resetAll.title"));
        confirm.setHeaderText(null);
        UiZoom.minWidth(confirm.getDialogPane(), 460);
        Dialogs.destructive(confirm, reset);
        return confirm.showAndWait().orElse(Dialogs.cancelOf(confirm)) == reset;
    }

    /**
     * Rebuilds the shortcut list from the backend, honoring the filter, and refills the chord chips on the
     * other pages (both show the live keymap, so they go stale together). The list part is a no-op until the
     * backend is set and the page is built.
     */
    void refreshShortcuts() {
        refreshShortcuts(null);
    }

    /** {@link #refreshShortcuts()}, then moves keyboard focus to {@code focusId}'s row (rebuilding drops it). */
    private void refreshShortcuts(@Nullable String focusId) {
        refreshChordChips();
        if (macroPane != null && macroNote != null) { // built together on the Macros page
            macroPane.refreshKeybinding();
            macroNote.setText(macroNoteText());
        }
        if (shortcutListBox == null || w.shortcutActions == null) {
            return;
        }
        shortcutListBox.getChildren().clear();
        shortcutRowsById.clear();
        shortcutTabStop = null;
        String q = shortcutFilter == null ? "" : shortcutFilter.getText();
        String typedChord = com.editora.command.ChordQuery.parse(q);
        String chordShown = typedChord == null ? null : w.shortcutActions.display(typedChord);
        for (Shortcut s : w.shortcutActions.rows()) {
            if (shortcutMatches(s, q, chordShown)) {
                HBox row = shortcutRow(s, w.shortcutActions, shortcutListBox);
                shortcutRowsById.put(s.id(), row);
                shortcutListBox.getChildren().add(row);
            }
        }
        // One Tab stop for the whole list (the selected row, else the first); the arrow keys move within it.
        // ~450 separate Tab stops would put "Reset all shortcuts" and the footer out of reach instead.
        HBox stop = shortcutRowsById.get(selectedShortcutId);
        if (stop == null && !shortcutListBox.getChildren().isEmpty()) {
            stop = (HBox) shortcutListBox.getChildren().get(0);
        }
        if (stop != null && !stop.getStyleClass().contains("shortcut-row-recording")) {
            stop.setFocusTraversable(true);
            shortcutTabStop = stop;
        }
        HBox focus = focusId == null ? null : shortcutRowsById.get(focusId);
        if (focus != null) {
            javafx.application.Platform.runLater(focus::requestFocus);
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
    private HBox shortcutRow(Shortcut s, ShortcutActions actions, VBox list) {
        HBox row = UiZoom.spacing(new HBox(), 8);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        row.getStyleClass().add("shortcut-row");
        Label title = new Label(s.title());
        title.setMaxWidth(Double.MAX_VALUE);
        title.setWrapText(true);
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
            // selects a row; from the keyboard the arrow keys walk the rows and Enter/Space selects.
            row.getStyleClass().add("shortcut-row-clickable");
            row.setAccessibleRole(javafx.scene.AccessibleRole.LIST_ITEM);
            row.setAccessibleText(s.title() + ", " + chordText);
            Button record = new Button(tr("settings.shortcuts.record"));
            Runnable select = () -> {
                if (s.id().equals(selectedShortcutId)) {
                    record.fire(); // a second Enter on the selected row starts recording
                } else {
                    selectedShortcutId = s.id();
                    refreshShortcuts(s.id());
                }
            };
            row.setOnMouseClicked(e -> {
                if (!s.id().equals(selectedShortcutId)) {
                    select.run();
                }
            });
            row.focusedProperty().addListener((o, was, now) -> {
                if (now) {
                    if (shortcutTabStop != null && shortcutTabStop != row) {
                        shortcutTabStop.setFocusTraversable(false);
                    }
                    row.setFocusTraversable(true);
                    shortcutTabStop = row;
                    scrollShortcutIntoView(row);
                }
            });
            row.addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
                if (e.getTarget() != row || e.isControlDown() || e.isAltDown() || e.isMetaDown()) {
                    return; // a key pressed on the row's own Record/Reset button is the button's
                }
                List<Node> all = list.getChildren();
                int target = switch (e.getCode()) {
                    // On the first row Up stays put, as Down does on the last one: left to the
                    // toolkit it moved the focus out of the list, to the filter field above it.
                    case UP -> Math.max(0, all.indexOf(row) - 1);
                    case DOWN -> all.indexOf(row) + 1;
                    case HOME -> 0;
                    case END -> all.size() - 1;
                    default -> -1;
                };
                if (e.getCode() == javafx.scene.input.KeyCode.ENTER
                        || e.getCode() == javafx.scene.input.KeyCode.SPACE) {
                    select.run();
                    e.consume();
                } else if (e.getCode() == javafx.scene.input.KeyCode.DELETE
                        || e.getCode() == javafx.scene.input.KeyCode.BACK_SPACE) {
                    if (s.chord() != null) {
                        removeShortcut(actions, s.id());
                    }
                    e.consume();
                } else if (target >= 0) {
                    if (target < all.size()) {
                        all.get(target).requestFocus();
                    }
                    e.consume(); // also at either end: Down on the last row must not jump out of the list
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
            }
        }
        return row;
    }

    /** Scrolls the shortcut list just far enough to show {@code row} (focus moved to it from the keyboard). */
    private void scrollShortcutIntoView(Node row) {
        if (shortcutScroll == null || shortcutListBox == null) { // built together
            return;
        }
        shortcutListBox.applyCss();
        shortcutListBox.layout();
        double contentHeight = shortcutListBox.getHeight();
        double viewHeight = shortcutScroll.getViewportBounds().getHeight();
        if (contentHeight <= viewHeight) {
            return;
        }
        double range = contentHeight - viewHeight;
        double top = shortcutScroll.getVvalue() * range;
        javafx.geometry.Bounds b = row.getBoundsInParent();
        if (b.getMinY() < top) {
            shortcutScroll.setVvalue(b.getMinY() / range);
        } else if (b.getMaxY() > top + viewHeight) {
            shortcutScroll.setVvalue((b.getMaxY() - viewHeight) / range);
        }
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
            StringBuilder lines = new StringBuilder();
            for (var c : taken) {
                lines.append("\n   ")
                        .append(actions.display(c.chord()))
                        .append("  —  ")
                        .append(titleOf(actions, c.commandId()));
            }
            if (!w.askBeforeLoss(
                    tr("dialog.shortcut.conflict.title"),
                    tr("dialog.shortcut.reclaim.body", titleOf(actions, commandId), lines.toString()),
                    tr("confirm.action.reset"))) {
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
            StringBuilder affected = new StringBuilder();
            for (var c : conflicts) {
                affected.append("\n   ")
                        .append(w.shortcutActions.display(c.chord()))
                        .append("  —  ")
                        .append(titleOf(w.shortcutActions, c.commandId()));
            }
            ButtonType bind = Dialogs.action(tr("confirm.action.bind"));
            Alert confirm = Dialogs.styled(new Alert(
                    Alert.AlertType.CONFIRMATION,
                    tr("dialog.shortcut.conflict.body", w.shortcutActions.display(seq), affected.toString()),
                    bind,
                    Dialogs.cancel()));
            confirm.initOwner(w.stage);
            confirm.setTitle(tr("dialog.shortcut.conflict.title"));
            confirm.setHeaderText(null);
            UiZoom.minWidth(confirm.getDialogPane(), 460);
            Dialogs.destructive(confirm, bind);
            if (confirm.showAndWait().orElse(Dialogs.cancelOf(confirm)) != bind) {
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
        a.showAndWait();
    }

    /**
     * The Keymaps shortcut list is searched as a list of its own (its hundreds of command names are not the
     * page's text): a search it matches carries over into its filter, and clearing the search clears it again.
     */
    void pushShortcutQuery(String query) {
        if (shortcutFilter == null) {
            return;
        }
        String q = query == null ? "" : query.strip();
        String shown = shortcutFilter.getText();
        boolean ours = shown.isEmpty() || shown.equals(pushedShortcutQuery);
        pushedShortcutQuery = q.isEmpty() || !shortcutMatches(q) ? "" : q;
        if (ours) {
            shortcutFilter.setText(pushedShortcutQuery);
        }
    }

    /** Whether a command in the shortcut list matches {@code query} as its own filter would. */
    boolean shortcutMatches(String query) {
        String q = query.toLowerCase(Locale.ROOT);
        return w.shortcutActions != null
                && w.shortcutActions.rows().stream()
                        .anyMatch(s -> s.title().toLowerCase(Locale.ROOT).contains(q)
                                || s.id().toLowerCase(Locale.ROOT).contains(q));
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
