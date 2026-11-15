package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.editor.MenuChords;
import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * A small in-scene manager card — a list of things (remotes, work trees) over a row of buttons that act on
 * the selected one — shown in the shared {@link OverlayHost} like every other picker and form. Each button
 * hides the card before its action runs: the actions open a prompt, a confirmation or start a git command,
 * and the owner shows the card again with a fresh list when that finishes.
 *
 * <p>Each button is also a row of the list's right-click menu, and each is a command's
 * ({@link Action#commandId}): the command acts on the row selected here while the card is up, and on a row picked
 * from a list otherwise (KBD-01 and MENU-05, Pauta 6.0.1).
 *
 * <p>Pure view: the owner supplies the rows and the actions.
 */
final class GitManagerOverlay<T> {

    /**
     * One button, and the row of the list's menu that does the same. {@code needsSelection} disables both while no
     * row is selected (its action then never sees {@code null}); {@code danger} styles them as destructive;
     * {@code commandId} is the command that does the same, whose chord the row shows.
     */
    record Action<T>(String label, boolean needsSelection, boolean danger, String commandId, Consumer<T> run) {}

    private final String title;
    private final String emptyText;
    private final Function<T, String> label;
    private final Function<T, String> detail;
    private final List<Action<T>> actions;
    private final ListView<T> list = new ListView<>();
    private final List<Button> buttons = new ArrayList<>();
    private @Nullable OverlayHost overlayHost;
    private boolean showing;

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
                setGraphic(UiZoom.spacing(new VBox(name, sub), 1));
            }
        });

        FlowPane bar = new FlowPane();
        UiZoom.follow(bar, () -> {
            bar.setHgap(UiZoom.px(8));
            bar.setVgap(UiZoom.px(6));
        });
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
        List<MenuItem> rows = new ArrayList<>();
        for (Action<T> action : actions) {
            MenuItem row = MenuChords.tag(new MenuItem(action.label()), action.commandId());
            if (action.danger()) {
                row.getStyleClass().add("danger");
            }
            row.setOnAction(e -> fire(action));
            rows.add(ListRowCommands.enabledWhen(
                    row,
                    () -> !action.needsSelection() || list.getSelectionModel().getSelectedItem() != null,
                    "row.unavailable.noSelection"));
        }
        RowMenus.install(list, () -> true, rows.toArray(MenuItem[]::new));
        list.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> syncButtons());
        if (!items.isEmpty()) {
            list.getSelectionModel().select(0);
        }
        syncButtons();

        VBox card = UiZoom.spacing(new VBox(titleLabel, list, bar), 10);
        card.getStyleClass().addAll("command-palette", "overlay-form", "git-manager");
        UiZoom.padding(card, 14);
        UiZoom.prefWidth(card, 560);
        UiZoom.maxSize(card, 560, Region.USE_PREF_SIZE);
        card.getProperties().put("editora.ownsKeys", Boolean.TRUE);
        showing = true;
        host.show(card, true, list::requestFocus, () -> showing = false);
    }

    /** The row selected while the card is up; null when it is not up, or no row is selected. */
    @Nullable
    T selectedWhileShowing() {
        return showing ? list.getSelectionModel().getSelectedItem() : null;
    }

    /** Whether the card is up. */
    boolean isShowing() {
        return showing;
    }

    /** Takes the card down, as a button does before its action runs. */
    void hide() {
        OverlayHost shownIn = overlayHost;
        if (showing && shownIn != null) {
            shownIn.hide();
        }
    }

    private void syncButtons() {
        boolean none = list.getSelectionModel().getSelectedItem() == null;
        for (int i = 0; i < buttons.size(); i++) {
            buttons.get(i).setDisable(actions.get(i).needsSelection() && none);
        }
    }

    private void fire(Action<T> action) {
        T selected = list.getSelectionModel().getSelectedItem();
        OverlayHost shownIn = overlayHost; // set by show(), which made the buttons
        if (shownIn == null || (action.needsSelection() && selected == null)) {
            return;
        }
        shownIn.hide();
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
