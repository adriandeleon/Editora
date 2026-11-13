package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.config.MapTrails;
import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * A trail's editor (Map explore, position 2): a card over the map listing the trail's stops in order — title, file
 * and lines, and the note — with Move Up and Move Down (also Alt+↑ and Alt+↓), Remove (Delete), Rename… and Note…
 * for the selected stop, Rename Trail…, and Play from Here (Enter, or a double-click). Every change is written to the
 * trail's file at once ({@link ProjectMapTrails#writeOver}), unless the file changed on disk since the card showed it:
 * the card then shows the file as it is now and writes nothing. It is not a code card: it takes nothing from the card limit
 * and is never evicted. Its header drags it; Escape or × closes it.
 */
final class ProjectMapTrailEditor extends VBox {

    static final double WIDTH = 400;
    static final double HEIGHT = 360;

    private final ProjectMapTrails trails;
    private final Pane host;
    private final Label heading = new Label();
    private final ListView<MapTrails.Stop> stops = new ListView<>();
    private final Label empty = new Label(tr("project.map.trails.editor.empty"));
    private final Button up = new Button(tr("project.map.trails.editor.up"));
    private final Button down = new Button(tr("project.map.trails.editor.down"));
    private final Button remove = new Button(tr("project.map.trails.editor.remove"));
    private final Button rename = new Button(tr("project.map.trails.editor.rename"));
    private final Button note = new Button(tr("project.map.trails.editor.note"));
    private final Button play = new Button(tr("project.map.trails.editor.play"));
    private final Button renameTrail = new Button(tr("project.map.trails.editor.renameTrail"));

    private MapTrails.@Nullable Listed listed;
    private double dragScreenX;
    private double dragScreenY;
    private double dragLayoutX;
    private double dragLayoutY;

    ProjectMapTrailEditor(ProjectMapTrails trails, Pane host) {
        this.trails = trails;
        this.host = host;
        getStyleClass().addAll("project-map-preview-frame", "project-map-trail-editor");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setManaged(false);
        UiZoom.spacing(this, 6);
        setViewOrder(-0.5); // above the cards, below the zoom bar and the trail bar

        heading.getStyleClass().add("project-map-trail-editor-title");
        heading.setMaxWidth(Double.MAX_VALUE);
        heading.setMinWidth(0);
        HBox.setHgrow(heading, Priority.ALWAYS);
        Button close = new Button("×");
        close.getStyleClass().addAll("flat", "project-map-preview-close");
        MapButtonCommands.bind(
                this, close, "projectMap.cardClose", () -> tr("project.map.trails.editor.close"), this::dispose);
        HBox header = UiZoom.spacing(new HBox(heading, renameTrail, close), 6);
        header.getStyleClass().add("project-map-trail-editor-header");
        header.setAlignment(Pos.CENTER_LEFT);
        header.addEventHandler(MouseEvent.MOUSE_PRESSED, this::dragPressed);
        header.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::dragged);

        stops.getStyleClass().add("project-map-trail-editor-stops");
        stops.setCellFactory(list -> new StopCell());
        stops.setPlaceholder(empty);
        empty.setWrapText(true);
        VBox.setVgrow(stops, Priority.ALWAYS);
        stops.getSelectionModel().selectedIndexProperty().addListener((obs, old, index) -> updateButtons());
        stops.addEventFilter(KeyEvent.KEY_PRESSED, this::keyPressed);
        stops.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                playSelected();
            }
        });

        for (Button button : List.of(up, down, remove, rename, note, play, renameTrail)) {
            button.getStyleClass().add("small");
            button.setMinWidth(Region.USE_PREF_SIZE);
        }
        up.setOnAction(event -> move(-1));
        down.setOnAction(event -> move(1));
        remove.setOnAction(event -> removeSelected());
        rename.setOnAction(event -> renameSelected());
        note.setOnAction(event -> noteSelected());
        play.setOnAction(event -> playSelected());
        renameTrail.setOnAction(event -> askRenameTrail());
        javafx.scene.layout.FlowPane buttons = new javafx.scene.layout.FlowPane(up, down, remove, rename, note, play);
        UiZoom.follow(buttons, () -> {
            buttons.setHgap(UiZoom.px(4));
            buttons.setVgap(UiZoom.px(4));
        });
        buttons.getStyleClass().add("project-map-trail-editor-buttons");

        getChildren().setAll(header, stops, buttons);
        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                dispose();
                event.consume();
            }
        });
        addEventFilter(MouseEvent.MOUSE_PRESSED, event -> toFront()); // a filter: the fields consume the press
        UiZoom.follow(this, () -> resize(UiZoom.px(WIDTH), UiZoom.px(HEIGHT)));
        host.getChildren().add(this);
        relocate(UiZoom.px(16), UiZoom.px(48));
    }

    /** The file name stem of the trail shown. */
    String name() {
        MapTrails.Listed shown = listed;
        return shown == null ? "" : shown.name();
    }

    /** The stops listed now. For tests. */
    List<MapTrails.Stop> shownStops() {
        return List.copyOf(stops.getItems());
    }

    ListView<MapTrails.Stop> list() {
        return stops;
    }

    /** Shows {@code trail}'s stops, keeping the selection where it was. */
    void show(MapTrails.Listed trail) {
        listed = trail;
        heading.setText(tr("project.map.trails.editor.title", trail.label()));
        int selected = stops.getSelectionModel().getSelectedIndex();
        List<MapTrails.Stop> items =
                trail.trail() == null ? List.of() : trail.trail().stops();
        stops.getItems().setAll(items);
        if (!items.isEmpty()) {
            stops.getSelectionModel().select(Math.clamp(selected, 0, items.size() - 1));
        }
        updateButtons();
        toFront();
        stops.requestFocus();
    }

    private void updateButtons() {
        int index = stops.getSelectionModel().getSelectedIndex();
        int count = stops.getItems().size();
        boolean none = index < 0 || index >= count;
        up.setDisable(none || index == 0);
        down.setDisable(none || index == count - 1);
        remove.setDisable(none);
        rename.setDisable(none);
        note.setDisable(none);
        play.setDisable(none);
    }

    private void keyPressed(KeyEvent event) {
        if (event.isAltDown() && event.getCode() == KeyCode.UP) {
            move(-1);
        } else if (event.isAltDown() && event.getCode() == KeyCode.DOWN) {
            move(1);
        } else if (event.getCode() == KeyCode.DELETE) {
            removeSelected();
        } else if (event.getCode() == KeyCode.ENTER) {
            playSelected();
        } else {
            return;
        }
        event.consume();
    }

    /** Moves the selected stop one place earlier ({@code -1}) or later ({@code 1}). */
    void move(int delta) {
        int index = stops.getSelectionModel().getSelectedIndex();
        int target = index + delta;
        List<MapTrails.Stop> items = new ArrayList<>(stops.getItems());
        if (index < 0 || target < 0 || target >= items.size()) {
            return;
        }
        items.add(target, items.remove(index));
        stops.getSelectionModel().clearSelection();
        if (save(items)) {
            stops.getSelectionModel().select(target);
        }
    }

    void removeSelected() {
        int index = stops.getSelectionModel().getSelectedIndex();
        List<MapTrails.Stop> items = new ArrayList<>(stops.getItems());
        if (index < 0 || index >= items.size()) {
            return;
        }
        items.remove(index);
        save(items);
    }

    private void renameSelected() {
        int index = stops.getSelectionModel().getSelectedIndex();
        if (index < 0) {
            return;
        }
        MapTrails.Stop stop = stops.getItems().get(index);
        trails.ask(
                tr("project.map.trails.editor.renameStopTitle"),
                tr("project.map.trails.editor.renameStopLabel"),
                ProjectMapTrails.stopTitle(stop),
                value -> retitle(index, value));
    }

    /** Gives the stop at {@code index} the title {@code value} (blank: its file's name). */
    void retitle(int index, @Nullable String value) {
        List<MapTrails.Stop> items = new ArrayList<>(stops.getItems());
        if (index >= 0 && index < items.size()) {
            items.set(index, items.get(index).withTitle(value == null ? "" : value.strip()));
            save(items);
        }
    }

    private void noteSelected() {
        int index = stops.getSelectionModel().getSelectedIndex();
        if (index < 0) {
            return;
        }
        trails.ask(
                tr("project.map.trails.editor.noteTitle"),
                tr("project.map.trails.stopLabel"),
                stops.getItems().get(index).note(),
                value -> renote(index, value));
    }

    /** Gives the stop at {@code index} the note {@code value} (blank: none). */
    void renote(int index, @Nullable String value) {
        List<MapTrails.Stop> items = new ArrayList<>(stops.getItems());
        if (index >= 0 && index < items.size()) {
            items.set(index, items.get(index).withNote(value == null ? "" : value.strip()));
            save(items);
        }
    }

    private void playSelected() {
        int index = stops.getSelectionModel().getSelectedIndex();
        if (index >= 0) {
            trails.play(name(), index);
        }
    }

    private void askRenameTrail() {
        MapTrails.Listed shown = listed;
        if (shown == null) {
            return;
        }
        trails.ask(
                tr("project.map.trails.editor.renameTrailTitle"),
                tr("project.map.trails.newLabel"),
                shown.label(),
                value -> trails.rename(shown.name(), value));
    }

    private boolean save(List<MapTrails.Stop> items) {
        MapTrails.Listed shown = listed;
        if (shown == null || shown.trail() == null) {
            return false;
        }
        // Written over the trail it showed only: one changed on disk meanwhile is shown again instead (review 4 A5).
        return trails.writeOver(shown.name(), shown.trail(), shown.trail().withStops(items));
    }

    private void dragPressed(MouseEvent event) {
        if (event.getButton() != MouseButton.PRIMARY) {
            return;
        }
        dragScreenX = event.getScreenX();
        dragScreenY = event.getScreenY();
        dragLayoutX = getLayoutX();
        dragLayoutY = getLayoutY();
        toFront();
        event.consume();
    }

    private void dragged(MouseEvent event) {
        if (!event.isPrimaryButtonDown()) {
            return;
        }
        double maxX = Math.max(0, host.getWidth() - UiZoom.px(40));
        double maxY = Math.max(0, host.getHeight() - UiZoom.px(30));
        relocate(
                Math.clamp(dragLayoutX + event.getScreenX() - dragScreenX, 0, maxX),
                Math.clamp(dragLayoutY + event.getScreenY() - dragScreenY, 0, maxY));
        event.consume();
    }

    void dispose() {
        host.getChildren().remove(this);
        trails.editorClosed(this);
    }

    /** One stop: its number and title, then its file and lines, then its note. */
    private static final class StopCell extends ListCell<MapTrails.Stop> {
        @Override
        protected void updateItem(MapTrails.@Nullable Stop item, boolean blank) {
            super.updateItem(item, blank);
            if (blank || item == null) {
                setText(null);
                setGraphic(null);
                setAccessibleText(null);
                return;
            }
            Label name = new Label((getIndex() + 1) + ". " + ProjectMapTrails.stopTitle(item));
            name.getStyleClass().add("project-map-trail-editor-stop");
            String where = item.startLine() > 0
                    ? tr(
                            "project.map.trails.stop.lines",
                            item.file(),
                            Integer.toString(item.startLine()),
                            Integer.toString(item.endLine()))
                    : item.file();
            Label at = new Label(where);
            at.getStyleClass().add("project-map-trail-editor-where");
            VBox lines = UiZoom.spacing(new VBox(name, at), 1);
            if (!item.note().isBlank()) {
                Label body = new Label(item.note());
                body.getStyleClass().add("project-map-trail-editor-note");
                body.setWrapText(true);
                UiZoom.maxWidth(body, WIDTH - 40);
                lines.getChildren().add(body);
            }
            setText(null);
            setGraphic(lines);
            setAccessibleText(name.getText() + ", " + where + (item.note().isBlank() ? "" : ", " + item.note()));
        }
    }
}
