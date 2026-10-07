package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import static com.editora.i18n.Messages.tr;

/**
 * A small in-scene manager card — a list of things (remotes, work trees) over a row of buttons that act on
 * the selected one — shown in the shared {@link OverlayHost} like every other picker and form. Each button
 * hides the card before its action runs: the actions open a prompt, a confirmation or start a git command,
 * and the owner shows the card again with a fresh list when that finishes.
 *
 * <p>Pure view: the owner supplies the rows and the actions.
 */
final class GitManagerOverlay<T> {

    /**
     * One button. {@code needsSelection} disables it while no row is selected (its action then never sees
     * {@code null}); {@code danger} styles it as destructive.
     */
    record Action<T>(String label, boolean needsSelection, boolean danger, Consumer<T> run) {}

    private final String title;
    private final String emptyText;
    private final Function<T, String> label;
    private final Function<T, String> detail;
    private final List<Action<T>> actions;
    private final ListView<T> list = new ListView<>();
    private final List<Button> buttons = new ArrayList<>();
    private OverlayHost overlayHost;

    GitManagerOverlay(
            String title,
            String emptyText,
            Function<T, String> label,
            Function<T, String> detail,
            List<Action<T>> actions) {
        this.title = title;
        this.emptyText = emptyText;
        this.label = label;
        this.detail = detail;
        this.actions = List.copyOf(actions);
    }

    /** Shows the card over {@code items}, the first row selected. */
    void show(OverlayHost host, List<T> items) {
        if (host == null) {
            return;
        }
        overlayHost = host;
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("palette-title");

        list.getItems().setAll(items);
        Label empty = new Label(emptyText);
        empty.getStyleClass().add("git-manager-empty");
        list.setPlaceholder(empty);
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean isEmpty) {
                super.updateItem(item, isEmpty);
                if (isEmpty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label name = new Label(label.apply(item));
                name.getStyleClass().add("menu-item-title");
                Label sub = new Label(detail.apply(item));
                sub.getStyleClass().add("git-manager-detail");
                setText(null);
                setGraphic(new VBox(1, name, sub));
            }
        });

        FlowPane bar = new FlowPane(8, 6);
        bar.setAlignment(Pos.CENTER_LEFT);
        buttons.clear();
        for (Action<T> action : actions) {
            Button button = new Button(action.label());
            if (action.danger()) {
                button.getStyleClass().add("danger");
            }
            button.setOnAction(e -> fire(action));
            buttons.add(button);
            bar.getChildren().add(button);
        }
        Button close = new Button(tr("dialog.close"));
        close.setOnAction(e -> host.hide());
        bar.getChildren().add(close);
        list.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> syncButtons());
        if (!items.isEmpty()) {
            list.getSelectionModel().select(0);
        }
        syncButtons();

        VBox card = new VBox(10, titleLabel, list, bar);
        card.getStyleClass().addAll("command-palette", "overlay-form", "git-manager");
        card.setPadding(new Insets(14));
        card.setPrefWidth(560);
        card.setMaxSize(560, Region.USE_PREF_SIZE);
        card.getProperties().put("editora.ownsKeys", Boolean.TRUE);
        host.show(card, true, list::requestFocus, () -> {});
    }

    private void syncButtons() {
        boolean none = list.getSelectionModel().getSelectedItem() == null;
        for (int i = 0; i < buttons.size(); i++) {
            buttons.get(i).setDisable(actions.get(i).needsSelection() && none);
        }
    }

    private void fire(Action<T> action) {
        T selected = list.getSelectionModel().getSelectedItem();
        if (action.needsSelection() && selected == null) {
            return;
        }
        overlayHost.hide();
        action.run().accept(selected);
    }

    /** The rows on screen. */
    List<T> items() {
        return List.copyOf(list.getItems());
    }

    /** Selects {@code item} and presses the button labelled {@code actionLabel}, as a click would. */
    void press(String actionLabel, T item) {
        list.getSelectionModel().select(item);
        for (int i = 0; i < actions.size(); i++) {
            if (actions.get(i).label().equals(actionLabel) && !buttons.get(i).isDisabled()) {
                buttons.get(i).fire();
                return;
            }
        }
    }
}
