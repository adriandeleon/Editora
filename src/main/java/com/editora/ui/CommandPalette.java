package com.editora.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.control.Label;
import javafx.stage.Popup;
import javafx.stage.Window;

/** A fuzzy-filtered command palette shown as a popup overlay (bound to {@code M-x}). */
public class CommandPalette {

    private final CommandRegistry registry;
    private final Map<String, String> commandToKey;

    private final Popup popup = new Popup();
    private final TextField input = new TextField();
    private final ListView<Command> list = new ListView<>();
    private final ObservableList<Command> items = FXCollections.observableArrayList();

    public CommandPalette(CommandRegistry registry, KeymapManager keymap) {
        this.registry = registry;
        this.commandToKey = invert(keymap.bindings());
        build();
    }

    private static Map<String, String> invert(Map<String, String> bindings) {
        Map<String, String> byCommand = new LinkedHashMap<>();
        bindings.forEach((sequence, id) -> byCommand.putIfAbsent(id, sequence));
        return byCommand;
    }

    private void build() {
        input.setPromptText("Run command…");
        list.setItems(items);
        list.setPrefHeight(280);
        list.setCellFactory(v -> new CommandCell());

        input.textProperty().addListener((obs, old, now) -> filter(now));
        input.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);

        VBox content = new VBox(6, input, list);
        content.getStyleClass().add("command-palette");
        content.setPrefWidth(620);

        popup.getContent().add(content);
        popup.setAutoHide(true);
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
            case G -> {
                if (e.isControlDown()) {
                    hide();
                    e.consume();
                }
            }
            default -> {
            }
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

    public void show(Window owner) {
        input.clear();
        filter("");
        double width = 620;
        double x = owner.getX() + (owner.getWidth() - width) / 2;
        double y = owner.getY() + 90;
        popup.show(owner, x, y);
        input.requestFocus();
    }

    public void hide() {
        popup.hide();
    }

    public boolean isShown() {
        return popup.isShowing();
    }

    public javafx.beans.value.ObservableValue<Boolean> showingProperty() {
        return popup.showingProperty();
    }

    private final class CommandCell extends ListCell<Command> {
        private final Label title = new Label();
        private final Label key = new Label();
        private final HBox box = new HBox(10, title, spacer(), key);

        CommandCell() {
            box.setAlignment(Pos.CENTER_LEFT);
            key.getStyleClass().add("keybinding");
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
