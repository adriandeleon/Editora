package com.editora.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;

import static com.editora.i18n.Messages.tr;

/**
 * A fuzzy-filtered command palette (bound to {@code M-x}). Shown as an <em>in-scene</em> overlay in the
 * main window's scene-root {@link StackPane} — <strong>not</strong> a {@link javafx.stage.Popup}. A Popup
 * is a separate native window, and on Windows it doesn't reliably take OS keyboard focus: {@code
 * input.requestFocus()} then orphans keyboard focus between the popup's scene and the main window, so the
 * whole app stops receiving keystrokes (mouse still works) until restart. Living in the main scene keeps
 * focus on the one window, which works on every platform (the find bar does the same).
 */
public class CommandPalette {

    private static final boolean IS_MAC =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    private final CommandRegistry registry;
    private final KeymapManager keymap;
    private Map<String, String> commandToKey;
    /** Only commands matching this predicate are listed (e.g. project commands hidden when disabled). */
    private final java.util.function.Predicate<Command> visible;

    private final TextField input = new TextField();
    private final ListView<Command> list = new ListView<>();
    private final ObservableList<Command> items = FXCollections.observableArrayList();
    /** One-line description of the highlighted command, shown above the navigation hint. */
    private final Label desc = new Label();

    /** The palette card (header + input + list + hint); shown via the shared {@link OverlayHost}. */
    private VBox content;
    /** Shared in-scene overlay host (injected); shows the card centered with a dim backdrop. */
    private OverlayHost overlayHost;
    /** Shown state for the toolbar button + MainController; flipped by show()/the host's onHidden hook. */
    private final BooleanProperty showing = new SimpleBooleanProperty(false);

    public CommandPalette(CommandRegistry registry, KeymapManager keymap) {
        this(registry, keymap, c -> true);
    }

    public CommandPalette(
            CommandRegistry registry, KeymapManager keymap, java.util.function.Predicate<Command> visible) {
        this.registry = registry;
        this.keymap = keymap;
        this.commandToKey = invert(keymap.bindings());
        this.visible = visible;
        build();
    }

    /** Rebuilds the chord hints from the current keymap (after a live keymap switch). */
    public void refreshBindings() {
        this.commandToKey = invert(keymap.bindings());
        list.refresh();
    }

    private static Map<String, String> invert(Map<String, String> bindings) {
        Map<String, String> byCommand = new LinkedHashMap<>();
        bindings.forEach((sequence, id) -> byCommand.putIfAbsent(id, sequence));
        return byCommand;
    }

    private void build() {
        input.setPromptText(tr("palette.prompt"));
        list.setItems(items);
        list.setPrefHeight(280);
        list.setCellFactory(v -> new CommandCell());

        input.textProperty().addListener((obs, old, now) -> filter(now));
        input.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        // The opening chord (e.g. M-x) is Alt/Meta+key; on macOS that combination also emits a
        // KEY_TYPED for a special character (Option+x => "≈") that would land in the just-focused
        // field. Swallow any character typed while a chord modifier is held; plain query typing
        // (no modifier, or only Shift) passes through. macOS only — elsewhere chord modifiers don't
        // emit query characters, and gating this avoids eating AltGr-composed characters on
        // European layouts (AltGr reports as Ctrl+Alt).
        if (IS_MAC) {
            input.addEventFilter(KeyEvent.KEY_TYPED, e -> {
                if (e.isAltDown() || e.isMetaDown() || e.isControlDown() || e.isShortcutDown()) {
                    e.consume();
                }
            });
        }

        Label header = new Label(tr("palette.header"));
        header.getStyleClass().add("palette-title");
        // Description of the highlighted command, between the list and the navigation hint. Single line
        // with a fixed height (so the card doesn't jitter as descriptions vary in length) and ellipsis.
        desc.getStyleClass().add("palette-desc");
        desc.setMaxWidth(Double.MAX_VALUE);
        desc.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        list.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            String d = sel == null ? "" : sel.description();
            desc.setText(d.isEmpty() ? " " : d); // keep one line tall so the card never collapses/jitters
        });
        Label hint = new Label("↑↓ / C-n C-p move  ·  ↵ run  ·  esc / C-g cancel");
        hint.getStyleClass().add("palette-hint");
        content = new VBox(6, header, input, list, desc, hint);
        content.getStyleClass().add("command-palette");
        content.setPrefWidth(620);
        content.setMaxSize(620, Region.USE_PREF_SIZE); // hug its content; don't stretch to fill the overlay
        // Editor-context chords (C-n/C-p/arrows) are left to the palette's own handler while it's open.
        content.getProperties().put("editora.ownsKeys", Boolean.TRUE);
        // Clicks on the card must not reach the backdrop (which hides the palette).
        content.addEventFilter(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);
    }

    /** Injects the shared overlay host used to show the palette card. */
    public void setOverlayHost(OverlayHost overlayHost) {
        this.overlayHost = overlayHost;
    }

    private void onKey(KeyEvent e) {
        switch (e.getCode()) {
            case ESCAPE -> {
                hide();
                e.consume();
            }
            case ENTER -> {
                runSelected();
                e.consume();
            }
            case DOWN -> {
                move(1);
                e.consume();
            }
            case UP -> {
                move(-1);
                e.consume();
            }
            case N -> {
                if (e.isControlDown()) {
                    move(1);
                    e.consume();
                }
            }
            case P -> {
                if (e.isControlDown()) {
                    move(-1);
                    e.consume();
                }
            }
            case G -> {
                if (e.isControlDown()) {
                    hide();
                    e.consume();
                }
            }
            default -> {}
        }
    }

    private void move(int delta) {
        int size = items.size();
        if (size == 0) {
            return;
        }
        int idx = Math.floorMod(list.getSelectionModel().getSelectedIndex() + delta, size);
        list.getSelectionModel().select(idx);
        list.scrollTo(idx);
    }

    private void runSelected() {
        Command command = list.getSelectionModel().getSelectedItem();
        if (command != null) {
            hide();
            registry.run(command.id());
        }
    }

    private void filter(String query) {
        String q = query.toLowerCase(Locale.ROOT).trim();
        List<Command> matches = new ArrayList<>();
        for (Command command : registry.all()) {
            if (!visible.test(command)) {
                continue; // e.g. project commands when project support is disabled
            }
            if (q.isEmpty() || isSubsequence(q, command.title().toLowerCase(Locale.ROOT))) {
                matches.add(command);
            }
        }
        items.setAll(matches);
        if (!items.isEmpty()) {
            list.getSelectionModel().select(0);
        }
    }

    /** True if every char of {@code needle} appears in {@code haystack} in order (fuzzy match). */
    static boolean isSubsequence(String needle, String haystack) {
        int i = 0;
        for (int j = 0; i < needle.length() && j < haystack.length(); j++) {
            if (needle.charAt(i) == haystack.charAt(j)) {
                i++;
            }
        }
        return i == needle.length();
    }

    public void show() {
        if (overlayHost == null) {
            return; // setOverlayHost() not called yet
        }
        input.clear();
        filter("");
        showing.set(true);
        overlayHost.show(content, input::requestFocus, () -> showing.set(false));
    }

    public void hide() {
        if (overlayHost != null) {
            overlayHost.hide(); // the host's onHidden hook clears `showing`
        }
    }

    public boolean isShown() {
        return showing.get();
    }

    public javafx.beans.value.ObservableValue<Boolean> showingProperty() {
        return showing;
    }

    private final class CommandCell extends ListCell<Command> {
        private final Label title = new Label();
        private final Label key = new Label();
        private final HBox box = new HBox(10, title, spacer(), key);

        CommandCell() {
            box.setAlignment(Pos.CENTER_LEFT);
            key.getStyleClass().add("keybinding");
            // Click a command to run it (the keyboard runs the selected item on Enter).
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && !isEmpty() && getItem() != null) {
                    getListView().getSelectionModel().select(getItem());
                    runSelected();
                }
            });
        }

        private Region spacer() {
            Region r = new Region();
            HBox.setHgrow(r, Priority.ALWAYS);
            return r;
        }

        @Override
        protected void updateItem(Command item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            title.setText(item.title());
            key.setText(commandToKey.getOrDefault(item.id(), ""));
            setGraphic(box);
        }
    }
}
