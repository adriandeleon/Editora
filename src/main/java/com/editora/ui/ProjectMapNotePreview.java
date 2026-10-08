package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import javafx.scene.input.ScrollEvent;
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

    static final double DEFAULT_WIDTH = 420;
    static final double DEFAULT_HEIGHT = 300;
    static final double MIN_WIDTH = 300;
    static final double MIN_HEIGHT = 180;

    /** One note's editor, with the body the store last held: an edit is whatever differs from that. */
    private static final class Row {
        private PersonalNote note;
        private String saved;
        private final TextArea editor;

        private Row(PersonalNote note, TextArea editor) {
            this.note = note;
            this.saved = note.body().strip();
            this.editor = editor;
        }

        private boolean edited() {
            return !editor.getText().strip().equals(saved);
        }
    }

    private final BorderPane frame = new BorderPane();
    private final HBox titleBar = new HBox(7);
    private final Label title = new Label();
    private final Label kind = new Label();
    private final VBox notes = new VBox(8);
    private final ScrollPane scroll = new ScrollPane(notes);
    private final Button close = new Button("×");
    private final Region resizeGrip = new Region();
    private final BiConsumer<PersonalNote, String> onSave;
    private final List<Row> rows = new ArrayList<>();

    private Runnable onClose = () -> setVisible(false);
    private Runnable onActivate = () -> {};
    private Runnable onTouch = () -> {};
    private Runnable onEscape = () -> onClose.run();
    private Runnable onBlankRejected = () -> {};
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
        // Keyboard focus and scrolling are use too (see ProjectMapPreview): they keep the card from eviction.
        focusWithinProperty().addListener((obs, was, within) -> {
            if (within) {
                toFront();
                onActivate.run();
            }
        });
        addEventFilter(ScrollEvent.SCROLL, event -> onTouch.run());
        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE
                    && !event.isShiftDown()
                    && !event.isControlDown()
                    && !event.isAltDown()
                    && !event.isMetaDown()) {
                onEscape.run(); // closing saves any edit, exactly as the close button does
                event.consume();
            }
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
        rebuild(values == null ? List.of() : values, Map.of());
        setVisible(true);
        resizeRelocate(placement.x(), placement.y(), placement.width(), placement.height());
        preferredWidth = placement.width();
        preferredHeight = placement.height();
        placed = true;
        toFront();
    }

    /**
     * Brings the card in line with the store after notes changed elsewhere (the Notes panel, the editor
     * gutter, this card's own save). Every row adopts the stored note — a later save must carry its current
     * anchor, not the one the card opened with — and shows its body, unless the user has an edit in
     * progress there, which is kept and saved over it as usual. Rows are rebuilt only when notes were added
     * or removed, so typing is never interrupted by a refresh.
     */
    void refreshNotes(List<PersonalNote> values) {
        List<PersonalNote> current = values == null ? List.of() : values;
        boolean sameNotes = current.size() == rows.size();
        for (int i = 0; sameNotes && i < rows.size(); i++) {
            sameNotes = rows.get(i).note.id().equals(current.get(i).id());
        }
        if (!sameNotes) {
            Map<UUID, String> edits = new HashMap<>();
            for (Row row : rows) {
                if (row.edited()) {
                    edits.put(row.note.id(), row.editor.getText());
                }
            }
            rebuild(current, edits);
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            PersonalNote note = current.get(i);
            String body = note.body().strip();
            boolean edited = row.edited();
            row.note = note;
            row.editor.setUserData(note);
            row.saved = body;
            if (!edited && !row.editor.getText().strip().equals(body)) {
                row.editor.setText(note.body());
            }
        }
    }

    private void rebuild(List<PersonalNote> values, Map<UUID, String> edits) {
        rows.clear();
        notes.getChildren().clear();
        for (PersonalNote note : values) {
            TextArea editor = new TextArea(note.body());
            editor.setWrapText(true);
            editor.setUserData(note);
            editor.setPrefRowCount(
                    Math.max(3, Math.min(8, note.body().lines().toList().size() + 1)));
            editor.getStyleClass().add("project-map-note-preview-editor");
            Row row = new Row(note, editor);
            String edit = edits.get(note.id());
            if (edit != null) {
                editor.setText(edit);
            }
            editor.focusedProperty().addListener((obs, was, focused) -> {
                if (!focused) {
                    saveIfChanged(row);
                }
            });
            editor.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                if (event.getCode() == KeyCode.ENTER && event.isShortcutDown()) {
                    saveIfChanged(row);
                    event.consume();
                }
            });
            rows.add(row);
            notes.getChildren().add(editor);
        }
        if (rows.isEmpty()) {
            Label empty = new Label(tr("project.map.notes.empty"));
            empty.getStyleClass().add("project-map-note-preview-empty");
            notes.getChildren().add(empty);
        }
    }

    /**
     * Saves a row whose text differs from the body the store holds. A blanked note is not a deletion here
     * (the Notes panel deletes): its text comes back and the owner is told, instead of the card silently
     * showing an empty note the store still has.
     */
    private void saveIfChanged(Row row) {
        String value = row.editor.getText().strip();
        if (value.isBlank()) {
            if (!row.saved.isBlank()) {
                row.editor.setText(row.saved);
                onBlankRejected.run();
            }
            return;
        }
        if (!value.equals(row.saved)) {
            row.saved = value; // before the callback: the store answers with a refresh of this very card
            onSave.accept(row.note, value);
        }
    }

    /** Moves keyboard focus to the first note. */
    void focusContent() {
        if (rows.isEmpty()) {
            close.requestFocus();
        } else {
            rows.getFirst().editor.requestFocus();
        }
    }

    void setOnTouch(Runnable action) {
        onTouch = action == null ? () -> {} : action;
    }

    void setOnEscape(Runnable action) {
        onEscape = action == null ? () -> onClose.run() : action;
    }

    /** Called when a note was blanked and its text restored. */
    void setOnBlankRejected(Runnable action) {
        onBlankRejected = action == null ? () -> {} : action;
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
        double margin = ProjectMapPreview.EDGE_MARGIN;
        double w = ProjectMapPreview.boundedSize(preferredWidth, MIN_WIDTH, width - margin * 2);
        double h = ProjectMapPreview.boundedSize(preferredHeight, MIN_HEIGHT, height - margin * 2);
        resizeRelocate(
                ProjectMapPreview.clamp(getLayoutX(), margin, Math.max(margin, width - w - margin)),
                ProjectMapPreview.clamp(getLayoutY(), margin, Math.max(margin, height - h - margin)),
                w,
                h);
    }

    void dispose() {
        for (Row row : rows) {
            saveIfChanged(row);
        }
        rows.clear();
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
        if (event.getButton() != MouseButton.PRIMARY) {
            return;
        }
        resizeScreenX = event.getScreenX();
        resizeScreenY = event.getScreenY();
        resizeWidth = getWidth();
        resizeHeight = getHeight();
        event.consume();
    }

    private void resized(MouseEvent event) {
        if (!(getParent() instanceof Region parent) || !event.isPrimaryButtonDown()) {
            return;
        }
        double margin = ProjectMapPreview.EDGE_MARGIN;
        preferredWidth = ProjectMapPreview.boundedSize(
                resizeWidth + event.getScreenX() - resizeScreenX,
                MIN_WIDTH,
                Math.max(1, parent.getWidth() - getLayoutX() - margin));
        preferredHeight = ProjectMapPreview.boundedSize(
                resizeHeight + event.getScreenY() - resizeScreenY,
                MIN_HEIGHT,
                Math.max(1, parent.getHeight() - getLayoutY() - margin));
        resize(preferredWidth, preferredHeight);
        event.consume();
    }
}
