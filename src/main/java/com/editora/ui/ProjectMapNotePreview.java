package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.editora.config.PersonalNote;

import static com.editora.i18n.Messages.tr;

/** An editable Personal Notes card attached to one Project Canvas file or folder row. */
final class ProjectMapNotePreview extends StackPane {

    private static final double DEFAULT_WIDTH = 420;
    private static final double DEFAULT_HEIGHT = 300;
    static final double MIN_WIDTH = 300;
    static final double MIN_HEIGHT = 180;

    private final BorderPane frame = new BorderPane();
    private final HBox titleBar = new HBox(7);
    private final Label title = new Label();
    private final Label kind = new Label();
    private final VBox notes = new VBox(8);
    private final ScrollPane scroll = new ScrollPane(notes);
    private final Button close = new Button("×");
    private final Region resizeGrip = new Region();
    private final BiConsumer<PersonalNote, String> onSave;

    private Runnable onClose = () -> setVisible(false);
    private Runnable onActivate = () -> {};
    private boolean placed;
    private double preferredWidth = DEFAULT_WIDTH;
    private double preferredHeight = DEFAULT_HEIGHT;
    private double dragScreenX;
    private double dragScreenY;
    private double dragLayoutX;
    private double dragLayoutY;
    private double resizeScreenX;
    private double resizeScreenY;
    private double resizeWidth;
    private double resizeHeight;

    ProjectMapNotePreview(BiConsumer<PersonalNote, String> onSave) {
        this.onSave = onSave == null ? (note, body) -> {} : onSave;
        getStyleClass().addAll("project-map-preview", "project-map-note-preview");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setManaged(false);
        setVisible(false);

        title.getStyleClass().add("project-map-preview-title");
        title.setMinWidth(0);
        HBox.setHgrow(title, Priority.ALWAYS);
        kind.setText(tr("project.map.notes.editable"));
        kind.getStyleClass().add("project-map-note-preview-kind");
        close.getStyleClass().add("project-map-preview-button");
        close.setTooltip(new Tooltip(tr("project.map.preview.close")));
        close.setAccessibleText(tr("project.map.preview.close"));
        close.setOnAction(event -> onClose.run());

        titleBar.getStyleClass().addAll("project-map-preview-header", "project-map-note-preview-header");
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.getChildren().setAll(title, kind, close);
        titleBar.addEventHandler(MouseEvent.MOUSE_PRESSED, this::dragPressed);
        titleBar.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::dragged);

        notes.setPadding(new Insets(10));
        notes.getStyleClass().add("project-map-note-preview-notes");
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("project-map-note-preview-scroll");
        frame.getStyleClass().addAll("project-map-preview-frame", "project-map-note-preview-frame");
        frame.setTop(titleBar);
        frame.setCenter(scroll);

        resizeGrip.getStyleClass().add("project-map-preview-resize");
        resizeGrip.setCursor(Cursor.SE_RESIZE);
        resizeGrip.addEventHandler(MouseEvent.MOUSE_PRESSED, this::resizePressed);
        resizeGrip.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::resized);
        getChildren().addAll(frame, resizeGrip);
        addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            toFront();
            onActivate.run();
        });
    }

    void showNotes(Path path, List<PersonalNote> values, ProjectMapPreview.Placement placement) {
        title.setText(
                path.getFileName() == null
                        ? path.toString()
                        : path.getFileName().toString());
        title.setGraphic(Icons.notes());
        title.setTooltip(new Tooltip(path.toString()));
        setAccessibleText(tr("project.map.notes.accessible", path.toString()));
        notes.getChildren().clear();
        for (PersonalNote note : values == null ? List.<PersonalNote>of() : values) {
            TextArea editor = new TextArea(note.body());
            editor.setWrapText(true);
            editor.setUserData(note);
            editor.setPrefRowCount(
                    Math.max(3, Math.min(8, note.body().lines().toList().size() + 1)));
            editor.getStyleClass().add("project-map-note-preview-editor");
            Runnable save = () -> saveIfChanged(note, editor);
            editor.focusedProperty().addListener((obs, was, focused) -> {
                if (!focused) {
                    save.run();
                }
            });
            editor.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                if (event.getCode() == KeyCode.ENTER && event.isShortcutDown()) {
                    save.run();
                    event.consume();
                }
            });
            notes.getChildren().add(editor);
        }
        if (notes.getChildren().isEmpty()) {
            Label empty = new Label(tr("project.map.notes.empty"));
            empty.getStyleClass().add("project-map-note-preview-empty");
            notes.getChildren().add(empty);
        }
        setVisible(true);
        resizeRelocate(placement.x(), placement.y(), placement.width(), placement.height());
        preferredWidth = placement.width();
        preferredHeight = placement.height();
        placed = true;
        toFront();
    }

    private void saveIfChanged(PersonalNote note, TextArea editor) {
        String value = editor.getText().strip();
        if (!value.isBlank() && !value.equals(note.body())) {
            onSave.accept(note, value);
        }
    }

    void setOnClose(Runnable action) {
        onClose = action == null ? () -> setVisible(false) : action;
    }

    void setOnActivate(Runnable action) {
        onActivate = action == null ? () -> {} : action;
    }

    void constrainTo(double width, double height) {
        if (!placed || width <= 0 || height <= 0) {
            return;
        }
        double w = Math.min(preferredWidth, Math.max(MIN_WIDTH, width - ProjectMapPreview.EDGE_MARGIN * 2));
        double h = Math.min(preferredHeight, Math.max(MIN_HEIGHT, height - ProjectMapPreview.EDGE_MARGIN * 2));
        double x = Math.max(
                ProjectMapPreview.EDGE_MARGIN, Math.min(getLayoutX(), width - w - ProjectMapPreview.EDGE_MARGIN));
        double y = Math.max(
                ProjectMapPreview.EDGE_MARGIN, Math.min(getLayoutY(), height - h - ProjectMapPreview.EDGE_MARGIN));
        resizeRelocate(x, y, w, h);
    }

    void dispose() {
        for (var child : notes.getChildren()) {
            if (child instanceof TextArea editor && editor.getUserData() instanceof PersonalNote note) {
                saveIfChanged(note, editor);
            }
        }
        notes.getChildren().clear();
    }

    @Override
    protected void layoutChildren() {
        frame.resizeRelocate(0, 0, getWidth(), getHeight());
        double grip = 18;
        resizeGrip.resizeRelocate(Math.max(0, getWidth() - grip), Math.max(0, getHeight() - grip), grip, grip);
    }

    private void dragPressed(MouseEvent event) {
        if (event.getTarget() instanceof Button || event.getButton() != MouseButton.PRIMARY) {
            return;
        }
        dragScreenX = event.getScreenX();
        dragScreenY = event.getScreenY();
        dragLayoutX = getLayoutX();
        dragLayoutY = getLayoutY();
        event.consume();
    }

    private void dragged(MouseEvent event) {
        if (!(getParent() instanceof Region parent) || !event.isPrimaryButtonDown()) {
            return;
        }
        double x = dragLayoutX + event.getScreenX() - dragScreenX;
        double y = dragLayoutY + event.getScreenY() - dragScreenY;
        relocate(
                Math.max(
                        ProjectMapPreview.EDGE_MARGIN,
                        Math.min(x, parent.getWidth() - getWidth() - ProjectMapPreview.EDGE_MARGIN)),
                Math.max(
                        ProjectMapPreview.EDGE_MARGIN,
                        Math.min(y, parent.getHeight() - getHeight() - ProjectMapPreview.EDGE_MARGIN)));
        event.consume();
    }

    private void resizePressed(MouseEvent event) {
        resizeScreenX = event.getScreenX();
        resizeScreenY = event.getScreenY();
        resizeWidth = getWidth();
        resizeHeight = getHeight();
        event.consume();
    }

    private void resized(MouseEvent event) {
        preferredWidth = Math.max(MIN_WIDTH, resizeWidth + event.getScreenX() - resizeScreenX);
        preferredHeight = Math.max(MIN_HEIGHT, resizeHeight + event.getScreenY() - resizeScreenY);
        resize(preferredWidth, preferredHeight);
        event.consume();
    }
}
