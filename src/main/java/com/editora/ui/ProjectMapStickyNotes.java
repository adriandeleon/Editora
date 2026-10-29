package com.editora.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.transform.Scale;

import com.editora.config.NoteScope;
import com.editora.config.PersonalNote;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The Personal Notes of one Project Map preview card, shown as sticky notes stuck beside it: one small editable
 * sheet per note, each tied by a connector to its line in the card, as the card is tied to its row. They belong
 * to the card — they move, resize and zoom with it, close with it, and do not count against the open-card limit
 * — and each can be dragged to another spot beside it, which it then keeps while the card is open.
 */
final class ProjectMapStickyNotes {

    static final double WIDTH = 220;
    static final double HEIGHT = 132;
    static final double GAP = 10;
    /** How far outside the card the sheets stand: room for their connectors to read as lines. */
    static final double OFFSET = 36;
    /** Notes beyond this many are not stuck on: a card is not a Notes panel. */
    static final int MAX_NOTES = 24;
    /** Where on a sheet its connector lands: the middle of its header. */
    private static final double HEADER_MIDDLE = 12;

    /** Which side of the card the sheets go on. */
    enum Side {
        /** Beside the card's right edge, outside it. */
        RIGHT,
        /** Beside its left edge, outside it. */
        LEFT,
        /** Neither side has room in the panel: on the card itself, against its right edge. */
        OVER
    }

    /** One sheet: a note's editor and where it sits, in unzoomed units from the card's left or right edge. */
    private final class Sticky extends VBox {
        private PersonalNote note;
        private String saved;
        private final Label where = new Label();
        private final TextArea editor;
        private final Scale zoom = new Scale(1, 1, 0, 0);
        private double dx;
        private double dy;
        private boolean fromRight;
        private double dragScreenX;
        private double dragScreenY;
        private double dragLayoutX;
        private double dragLayoutY;

        private Sticky(PersonalNote note) {
            this.note = note;
            this.saved = note.body().strip();
            editor = new TextArea(note.body());
            getStyleClass().add("project-map-sticky-note");
            getProperties().put("editora.ownsKeys", Boolean.TRUE);
            setManaged(false);
            zoom.xProperty().bind(card.mapScaleProperty());
            zoom.yProperty().bind(card.mapScaleProperty());
            getTransforms().add(zoom);

            where.getStyleClass().add("project-map-sticky-note-where");
            where.setGraphic(Icons.notes());
            where.setMinWidth(0);
            where.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(where, Priority.ALWAYS);
            HBox header = new HBox(where);
            header.getStyleClass().add("project-map-sticky-note-header");
            header.setAlignment(Pos.CENTER_LEFT);
            header.addEventHandler(MouseEvent.MOUSE_PRESSED, this::dragPressed);
            header.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::dragged);

            editor.getStyleClass().add("project-map-sticky-note-editor");
            editor.setWrapText(true);
            VBox.setVgrow(editor, Priority.ALWAYS);
            editor.focusedProperty().addListener((obs, was, focused) -> {
                if (!focused) {
                    saveIfChanged();
                }
            });
            editor.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                if (event.getCode() == KeyCode.ENTER && event.isShortcutDown()) {
                    saveIfChanged();
                    event.consume();
                } else if (event.getCode() == KeyCode.ESCAPE
                        && !event.isShiftDown()
                        && !event.isShortcutDown()
                        && !event.isAltDown()) {
                    saveIfChanged();
                    card.focusContent(); // back to the code the note is about
                    event.consume();
                }
            });
            getChildren().setAll(header, editor);
            addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
                raise();
                onActivate.run();
            });
            adopt(note);
            resize(WIDTH, HEIGHT);
        }

        private void adopt(PersonalNote value) {
            note = value;
            String label = where(value);
            where.setText(label);
            String quoted = value.anchor().selectedText().strip();
            where.setTooltip(quoted.isEmpty() ? null : new Tooltip(quoted));
            editor.setAccessibleText(tr("project.map.sticky.accessible", label));
        }

        private boolean edited() {
            return !editor.getText().strip().equals(saved);
        }

        /** As a note card saves (see {@link ProjectMapNotePreview}): a blanked note gets its text back. */
        private void saveIfChanged() {
            String value = editor.getText().strip();
            if (value.isBlank()) {
                if (!saved.isBlank()) {
                    editor.setText(saved);
                    onBlankRejected.run();
                }
                return;
            }
            if (!value.equals(saved)) {
                saved = value; // before the callback: the store answers with a refresh of these very sheets
                onSave.accept(note, value);
            }
        }

        private void dragPressed(MouseEvent event) {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            dragScreenX = event.getScreenX();
            dragScreenY = event.getScreenY();
            dragLayoutX = getLayoutX();
            dragLayoutY = getLayoutY();
            raise(); // consumed here, so the sheet's own press handler does not see it
            onActivate.run();
            event.consume();
        }

        /** Moves the sheet to another spot beside its card; the card carries it there from now on. */
        private void dragged(MouseEvent event) {
            if (!event.isPrimaryButtonDown()) {
                return;
            }
            double scale = card.mapScale();
            double x = dragLayoutX + event.getScreenX() - dragScreenX;
            double y = dragLayoutY + event.getScreenY() - dragScreenY;
            dx = (x - card.getLayoutX()) / scale - (fromRight ? card.getWidth() : 0);
            dy = (y - card.getLayoutY()) / scale;
            moved.put(note.id(), new double[] {dx, dy, fromRight ? 1 : 0});
            relocate(x, y);
            onMoved.run();
            event.consume();
        }
    }

    private final ProjectMapPreview card;
    private final Pane host;
    private final BiConsumer<PersonalNote, String> onSave;
    private final Runnable onMoved;
    private final Runnable onBlankRejected;
    private final Runnable onActivate;
    private final List<Sticky> stickies = new ArrayList<>();
    /** Where the user dragged each note's sheet: kept across hiding and showing the notes. */
    private final Map<UUID, double[]> moved = new HashMap<>();

    private final ChangeListener<Object> follow = (obs, old, now) -> layout();
    private boolean shown;
    private boolean hidden;

    /**
     * @param onSave saves a sheet's edited body to the store
     * @param onMoved the sheets moved (their connectors must be drawn again)
     * @param onBlankRejected a blanked note got its text back
     * @param onActivate a sheet was pressed: it is use of the card
     */
    ProjectMapStickyNotes(
            ProjectMapPreview card,
            Pane host,
            BiConsumer<PersonalNote, String> onSave,
            Runnable onMoved,
            Runnable onBlankRejected,
            Runnable onActivate) {
        this.card = card;
        this.host = host;
        this.onSave = onSave;
        this.onMoved = onMoved;
        this.onBlankRejected = onBlankRejected;
        this.onActivate = onActivate;
    }

    /** The notes a card shows: its file's, or those on a declaration card's lines, in line order. Pure. */
    static List<PersonalNote> notesFor(List<PersonalNote> notes, ProjectMapPreview.@Nullable Range range) {
        if (notes == null) {
            return List.of();
        }
        return notes.stream()
                .filter(note -> note.scope() != NoteScope.FOLDER)
                .filter(note -> range == null
                        || note.anchor().line() >= range.startLine()
                                && note.anchor().line() <= range.endLine())
                .sorted(Comparator.comparingInt(
                                (PersonalNote note) -> note.anchor().line())
                        .thenComparingLong(PersonalNote::createdAt))
                .limit(MAX_NOTES)
                .toList();
    }

    /** A sheet's header: the line or lines its note is on, 1-based. Pure. */
    static String where(PersonalNote note) {
        int line = note.anchor().line() + 1;
        int end = note.anchor().endLine() + 1;
        return note.scope() == NoteScope.RANGE && end > line
                ? tr("project.map.sticky.lines", line, end)
                : tr("project.map.sticky.line", line);
    }

    /**
     * The side of a card, drawn from {@code cardLeft} to {@code cardRight} in a panel {@code panelWidth} wide, that
     * has room for a sheet {@code width} wide: the right first, then the left, else on the card. Pure.
     */
    static Side side(double cardLeft, double cardRight, double width, double panelWidth) {
        if (panelWidth - cardRight >= width + OFFSET + GAP) {
            return Side.RIGHT;
        }
        if (cardLeft >= width + OFFSET + GAP) {
            return Side.LEFT;
        }
        return Side.OVER;
    }

    /**
     * Stacks sheets {@link #HEIGHT} tall down a card {@code cardHeight} tall, each as near its line's height as the
     * sheets above it allow ({@code wanted}, from the card's top, in order). A sheet that would run past the card's
     * foot starts a further column. Returns {@code {column, top}} per sheet. Pure.
     */
    static List<double[]> stack(List<Double> wanted, double cardHeight) {
        double room = Math.max(HEIGHT, cardHeight);
        List<double[]> placed = new ArrayList<>();
        int column = 0;
        double next = 0;
        for (double top : wanted) {
            double y = Math.max(next, Math.max(0, top));
            if (y + HEIGHT > room && next > 0) {
                column++;
                y = Math.max(0, Math.min(top, room - HEIGHT));
            }
            placed.add(new double[] {column, y});
            next = y + HEIGHT + GAP;
        }
        return placed;
    }

    boolean shown() {
        return shown;
    }

    /** Sticks {@code notes} on the card, keeping any sheet the user moved where they put it. */
    void show(List<PersonalNote> notes) {
        if (!shown) {
            shown = true;
            card.layoutXProperty().addListener(follow);
            card.layoutYProperty().addListener(follow);
            card.widthProperty().addListener(follow);
            card.heightProperty().addListener(follow);
            card.mapScaleProperty().addListener(follow);
            card.visibleProperty().addListener(follow);
        }
        rebuild(notes, Map.of());
    }

    /**
     * Brings the sheets in line with the store, as a note card does: each adopts its stored note and body unless
     * the user is editing it, and sheets are rebuilt only when notes were added or removed.
     */
    void refresh(List<PersonalNote> notes) {
        if (!shown) {
            return;
        }
        boolean same = notes.size() == stickies.size();
        for (int i = 0; same && i < stickies.size(); i++) {
            same = stickies.get(i).note.id().equals(notes.get(i).id());
        }
        if (!same) {
            Map<UUID, String> edits = new HashMap<>();
            for (Sticky sticky : stickies) {
                if (sticky.edited()) {
                    edits.put(sticky.note.id(), sticky.editor.getText());
                }
            }
            rebuild(notes, edits);
            return;
        }
        for (int i = 0; i < stickies.size(); i++) {
            Sticky sticky = stickies.get(i);
            PersonalNote note = notes.get(i);
            boolean edited = sticky.edited();
            sticky.adopt(note);
            sticky.saved = note.body().strip();
            if (!edited && !sticky.editor.getText().strip().equals(sticky.saved)) {
                sticky.editor.setText(note.body());
            }
        }
        mark();
        onMoved.run(); // a note may have moved to another line
    }

    /** Takes the sheets off the card, saving any edit. */
    void hide() {
        if (!shown) {
            return;
        }
        shown = false;
        card.layoutXProperty().removeListener(follow);
        card.layoutYProperty().removeListener(follow);
        card.widthProperty().removeListener(follow);
        card.heightProperty().removeListener(follow);
        card.mapScaleProperty().removeListener(follow);
        card.visibleProperty().removeListener(follow);
        clear();
        mark();
        onMoved.run();
    }

    /** The options menu's "Hide all open Personal Notes": the sheets go out of sight but stay stuck on. */
    void setHidden(boolean value) {
        hidden = value;
        stickies.forEach(sticky -> sticky.setVisible(visible()));
        mark();
        onMoved.run();
    }

    /** Puts the sheets above their card, which was just raised above the others. */
    void raise() {
        stickies.forEach(javafx.scene.Node::toFront);
    }

    void dispose() {
        hide();
        moved.clear();
    }

    /** The sheets' connectors, each from its note's line on the card's edge to the sheet's header. */
    void drawConnectors(GraphicsContext g) {
        if (!shown || !visible()) {
            return;
        }
        double scale = card.mapScale();
        double left = card.getLayoutX();
        double right = left + card.getWidth() * scale;
        for (Sticky sticky : stickies) {
            double lineY = card.noteAnchorY(sticky.note.anchor().line());
            if (Double.isNaN(lineY)) {
                lineY = card.getLayoutY() + HEADER_MIDDLE * scale;
            }
            double sheetLeft = sticky.getLayoutX();
            double sheetRight = sheetLeft + WIDTH * scale;
            boolean after = (sheetLeft + sheetRight) / 2 >= (left + right) / 2;
            double x1 = after ? Math.min(right, sheetLeft) : Math.max(left, sheetRight);
            double x2 = after ? sheetLeft : sheetRight;
            double y2 = sticky.getLayoutY() + HEADER_MIDDLE * scale;
            double control = Math.max(14, Math.abs(x2 - x1) * 0.45);
            g.beginPath();
            g.moveTo(x1, lineY);
            g.bezierCurveTo(x1 + (after ? control : -control), lineY, x2 + (after ? -control : control), y2, x2, y2);
            g.stroke();
            g.fillOval(x1 - 2.5, lineY - 2.5, 5, 5); // where the note is pinned to its line
        }
    }

    private boolean visible() {
        return shown && !hidden && card.isVisible();
    }

    private void rebuild(List<PersonalNote> notes, Map<UUID, String> edits) {
        clear();
        for (PersonalNote note : notes) {
            Sticky sticky = new Sticky(note);
            String edit = edits.get(note.id());
            if (edit != null) {
                sticky.editor.setText(edit);
            }
            sticky.setVisible(visible());
            stickies.add(sticky);
            host.getChildren().add(sticky);
        }
        place();
        layout();
        mark();
    }

    /** Marks the notes' text on the card in the sheets' amber while the sheets are in sight. */
    private void mark() {
        card.markNotes(visible() ? stickies.stream().map(sticky -> sticky.note).toList() : List.of());
    }

    private void clear() {
        for (Sticky sticky : stickies) {
            sticky.saveIfChanged();
            sticky.zoom.xProperty().unbind();
            sticky.zoom.yProperty().unbind();
            host.getChildren().remove(sticky);
        }
        stickies.clear();
    }

    /**
     * Puts each sheet beside the card at its note's line, on the side with room in the panel, stacked clear of the
     * sheets above it. A sheet the user moved keeps its spot.
     */
    private void place() {
        double scale = card.mapScale();
        double left = card.getLayoutX();
        Side side = side(left, left + card.getWidth() * scale, WIDTH * scale, host.getWidth());
        List<Double> wanted = new ArrayList<>();
        for (Sticky sticky : stickies) {
            double lineY = card.noteAnchorY(sticky.note.anchor().line());
            wanted.add(Double.isNaN(lineY) ? 0 : (lineY - card.getLayoutY()) / scale - HEADER_MIDDLE);
        }
        List<double[]> stacked = stack(wanted, card.getHeight());
        for (int i = 0; i < stickies.size(); i++) {
            Sticky sticky = stickies.get(i);
            double[] kept = moved.get(sticky.note.id());
            if (kept != null) {
                sticky.dx = kept[0];
                sticky.dy = kept[1];
                sticky.fromRight = kept[2] != 0;
                continue;
            }
            double column = stacked.get(i)[0] * (WIDTH + GAP);
            sticky.dy = stacked.get(i)[1];
            sticky.fromRight = side != Side.LEFT;
            sticky.dx = switch (side) {
                case RIGHT -> OFFSET + column;
                case LEFT -> -(WIDTH + OFFSET) - column;
                case OVER -> -(WIDTH + GAP) - column;
            };
        }
    }

    /** Follows the card: each sheet keeps its place relative to the card's edge, at the card's zoom. */
    private void layout() {
        double scale = card.mapScale();
        boolean visible = visible();
        for (Sticky sticky : stickies) {
            sticky.relocate(
                    card.getLayoutX() + ((sticky.fromRight ? card.getWidth() : 0) + sticky.dx) * scale,
                    card.getLayoutY() + sticky.dy * scale);
            sticky.setVisible(visible);
        }
        onMoved.run();
    }
}
