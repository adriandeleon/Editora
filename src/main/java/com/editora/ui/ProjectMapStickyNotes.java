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
import com.editora.config.NoteStatus;
import com.editora.config.PersonalNote;
import com.editora.config.TextAnchor;
import com.editora.editor.notes.NoteAnchors;
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
    /**
     * Stacks of sheets beside a card: notes past what two stacks hold go into one "+K more notes" sheet that opens
     * the file's note card. Thirty notes used to spread 24 sheets across the map and off its edge.
     */
    static final int MAX_STACKS = 2;
    /** The key the "+K more notes" sheet's dragged place is kept under. */
    private static final UUID MORE_KEY = new UUID(0, 0);
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

    /** A sheet beside the card, and where it sits, in unzoomed units from the card's left or right edge. */
    private abstract class Sheet extends VBox {
        final Scale zoom = new Scale(1, 1, 0, 0);
        double dx;
        double dy;
        boolean fromRight;
        private double dragScreenX;
        private double dragScreenY;
        private double dragLayoutX;
        private double dragLayoutY;

        Sheet() {
            getStyleClass().add("project-map-sticky-note");
            getProperties().put("editora.ownsKeys", Boolean.TRUE);
            // Alt+arrows move a sheet, as they move its card (see ProjectMapPreview.geometryStep).
            getProperties().put(com.editora.command.KeyDispatcher.CLAIMED_KEYS, ProjectMapPreview.GEOMETRY_KEYS);
            setManaged(false);
            zoom.xProperty().bind(card.mapScaleProperty());
            zoom.yProperty().bind(card.mapScaleProperty());
            getTransforms().add(zoom);
            addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                double[] step = ProjectMapPreview.geometryStep(event);
                if (step != null && (step[0] != 0 || step[1] != 0)) {
                    moveTo(getLayoutX() + step[0], getLayoutY() + step[1]);
                    ProjectMapPreview.announceGeometry(this, card.mapScale());
                    event.consume();
                }
            });
        }

        /** The key its dragged place is kept under in {@link #moved}. */
        abstract UUID key();

        void dragPressed(MouseEvent event) {
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
        void dragged(MouseEvent event) {
            if (!event.isPrimaryButtonDown()) {
                return;
            }
            moveTo(dragLayoutX + event.getScreenX() - dragScreenX, dragLayoutY + event.getScreenY() - dragScreenY);
            event.consume();
        }

        void moveTo(double x, double y) {
            double scale = card.mapScale();
            dx = (x - card.getLayoutX()) / scale - (fromRight ? card.getWidth() : 0);
            dy = (y - card.getLayoutY()) / scale;
            moved.put(key(), new double[] {dx, dy, fromRight ? 1 : 0});
            relocate(x, y);
            onMoved.run();
        }
    }

    /** The sheet for the notes two stacks cannot hold: it says how many and opens the file's note card. */
    private final class More extends Sheet {
        private final javafx.scene.control.Button open = new javafx.scene.control.Button();

        private More(int count, int total) {
            getStyleClass().add("project-map-sticky-more");
            open.setText(tr("project.map.sticky.more", count));
            open.setTooltip(new Tooltip(tr("project.map.sticky.moreHelp", total - count, total)));
            open.setAccessibleText(tr("project.map.sticky.moreHelp", total - count, total));
            open.getStyleClass().addAll("flat", "project-map-sticky-more-button");
            open.setMaxWidth(Double.MAX_VALUE);
            open.setOnAction(event -> onMore.run());
            HBox header = new HBox(new Label(tr("project.map.sticky.moreTitle"), Icons.notes()));
            header.getStyleClass().add("project-map-sticky-note-header");
            header.setAlignment(Pos.CENTER_LEFT);
            header.addEventHandler(MouseEvent.MOUSE_PRESSED, this::dragPressed);
            header.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::dragged);
            VBox.setVgrow(open, Priority.ALWAYS);
            open.setMaxHeight(Double.MAX_VALUE);
            getChildren().setAll(header, open);
            addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
                raise();
                onActivate.run();
            });
            resize(WIDTH, HEIGHT);
        }

        @Override
        UUID key() {
            return MORE_KEY;
        }
    }

    /** One sheet: a note's editor. */
    private final class Sticky extends Sheet {
        private PersonalNote note;
        private String saved;
        private final Label where = new Label();
        private final TextArea editor;

        private Sticky(PersonalNote note) {
            this.note = note;
            this.saved = note.body().strip();
            editor = new TextArea(note.body());

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

        @Override
        UUID key() {
            return note.id();
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
    }

    private final ProjectMapPreview card;
    private final Pane host;
    private final BiConsumer<PersonalNote, String> onSave;
    private final Runnable onMoved;
    private final Runnable onBlankRejected;
    private final Runnable onActivate;
    private Runnable onMore = () -> {};
    private final List<Sticky> stickies = new ArrayList<>();
    /** The "+K more notes" sheet, when the notes outnumber what two stacks hold. */
    private @Nullable More more;
    /** Where the user dragged each note's sheet: kept across hiding and showing the notes. */
    private final Map<UUID, double[]> moved = new HashMap<>();

    private final ChangeListener<Object> follow = (obs, old, now) -> layout();
    private boolean shown;
    private boolean hidden;
    /** How many notes the card has, sheets and "+K more" together. */
    private int total;

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

    /** Called by the "+K more notes" sheet: the owner opens the file's note card, which lists them all. */
    void setOnMore(Runnable callback) {
        onMore = callback == null ? () -> {} : callback;
    }

    /**
     * The notes a card shows: its file's, or those on a declaration card's lines, in line order — all of them,
     * for the Notes button's count; {@link #show} sticks on what fits. Pure.
     */
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
                .toList();
    }

    /**
     * {@code notes} found again in {@code text}, the file as the card shows it — as an editor tab finds them when it
     * opens the file ({@code NoteManager}, {@link NoteAnchors#relocate}): a file edited outside Editora moves its
     * notes' lines, and the store still has the old ones. A note whose text is gone is marked orphaned, as the
     * Notes panel shows it. The store is not changed. Empty text leaves the notes as stored. Pure.
     */
    static List<PersonalNote> reanchored(List<PersonalNote> notes, String text) {
        if (notes == null || notes.isEmpty() || text == null || text.isEmpty()) {
            return notes == null ? List.of() : notes;
        }
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = text.indexOf('\n'); i >= 0; i = text.indexOf('\n', i + 1)) {
            starts.add(i + 1);
        }
        List<PersonalNote> out = new ArrayList<>(notes.size());
        for (PersonalNote note : notes) {
            if (note.scope() == NoteScope.FOLDER) {
                out.add(note);
                continue;
            }
            TextAnchor anchor = note.anchor();
            int start = offsetOf(starts, text.length(), anchor.line(), anchor.column());
            int end = offsetOf(starts, text.length(), anchor.endLine(), anchor.endColumn());
            int[] found = NoteAnchors.relocate(
                    text, start, end, anchor.selectedText(), anchor.prefix(), anchor.suffix(), anchor.length());
            if (found == null) {
                out.add(note.status() == NoteStatus.ACTIVE ? note.withStatus(NoteStatus.ORPHANED) : note);
                continue;
            }
            int[] from = positionOf(starts, found[0]);
            int[] to = positionOf(starts, found[1]);
            PersonalNote moved = from[0] == anchor.line()
                            && from[1] == anchor.column()
                            && to[0] == anchor.endLine()
                            && to[1] == anchor.endColumn()
                    ? note
                    : note.withAnchor(anchor.at(from[0], from[1], to[0], to[1]));
            out.add(moved.status() == NoteStatus.ORPHANED ? moved.withStatus(NoteStatus.ACTIVE) : moved);
        }
        return out;
    }

    private static int offsetOf(List<Integer> starts, int length, int line, int column) {
        int clamped = Math.max(0, Math.min(line, starts.size() - 1));
        int lineEnd = clamped + 1 < starts.size() ? starts.get(clamped + 1) - 1 : length;
        return Math.min(lineEnd, starts.get(clamped) + Math.max(0, column));
    }

    /** {@code {line, column}} of a text offset. */
    private static int[] positionOf(List<Integer> starts, int offset) {
        int index = java.util.Collections.binarySearch(starts, offset);
        int line = index >= 0 ? index : -index - 2;
        line = Math.max(0, line);
        return new int[] {line, offset - starts.get(line)};
    }

    /** A sheet's header: the line or lines its note is on, 1-based; an orphaned note's says its text is gone. Pure. */
    static String where(PersonalNote note) {
        if (note.status() == NoteStatus.ORPHANED) {
            return tr("project.map.sticky.orphaned");
        }
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
    /**
     * How many of {@code total} notes, wanting the tops {@code wanted} (in order), are stuck on as sheets: all that
     * fit {@link #MAX_STACKS} stacks beside a card {@code cardHeight} tall (at most {@link #MAX_NOTES}), else one
     * fewer than fit, the last place going to the "+K more notes" sheet. Pure.
     */
    static int sheetsShown(List<Double> wanted, double cardHeight) {
        int total = wanted.size();
        List<double[]> stacked = stack(wanted, cardHeight);
        int fit = 0;
        while (fit < total && fit < MAX_NOTES && stacked.get(fit)[0] < MAX_STACKS) {
            fit++;
        }
        if (fit == total) {
            return total;
        }
        for (int shown = Math.max(0, fit - 1); shown > 0; shown--) {
            List<Double> withMore = new ArrayList<>(wanted.subList(0, shown));
            withMore.add(Double.MAX_VALUE); // the "+K more" sheet goes after the last one shown
            if (stack(withMore, cardHeight).getLast()[0] < MAX_STACKS) {
                return shown;
            }
        }
        return 0;
    }

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
        boolean same = notes.size() == total && stickies.size() <= notes.size();
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
        sheets().forEach(sticky -> sticky.setVisible(visible()));
        mark();
        onMoved.run();
    }

    /** Puts the sheets above their card, which was just raised above the others. */
    void raise() {
        stickies.forEach(javafx.scene.Node::toFront);
        if (more != null) {
            more.toFront();
        }
    }

    /** The notes stuck on as sheets now, and how many went into the "+K more notes" sheet. For tests. */
    int sheetCount() {
        return stickies.size();
    }

    int moreCount() {
        return more == null ? 0 : total - stickies.size();
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
        total = notes.size();
        int shownCount = sheetsShown(wantedTops(notes), card.getHeight());
        for (PersonalNote note : notes.subList(0, shownCount)) {
            Sticky sticky = new Sticky(note);
            String edit = edits.get(note.id());
            if (edit != null) {
                sticky.editor.setText(edit);
            }
            sticky.setVisible(visible());
            stickies.add(sticky);
            host.getChildren().add(sticky);
        }
        if (shownCount < notes.size()) {
            more = new More(notes.size() - shownCount, notes.size());
            more.setVisible(visible());
            host.getChildren().add(more);
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
            unbind(sticky);
        }
        stickies.clear();
        if (more != null) {
            unbind(more);
            more = null;
        }
    }

    private void unbind(Sheet sheet) {
        sheet.zoom.xProperty().unbind();
        sheet.zoom.yProperty().unbind();
        host.getChildren().remove(sheet);
    }

    /** The sheets in place order: the notes', then the "+K more" one. */
    private List<Sheet> sheets() {
        List<Sheet> all = new ArrayList<>(stickies);
        if (more != null) {
            all.add(more);
        }
        return all;
    }

    /** Where each note's sheet would like its top, from the card's top, in unzoomed units. */
    private List<Double> wantedTops(List<PersonalNote> notes) {
        double scale = card.mapScale();
        List<Double> wanted = new ArrayList<>(notes.size());
        for (PersonalNote note : notes) {
            double lineY = card.noteAnchorY(note.anchor().line());
            wanted.add(Double.isNaN(lineY) ? 0 : (lineY - card.getLayoutY()) / scale - HEADER_MIDDLE);
        }
        return wanted;
    }

    /**
     * Puts each sheet beside the card at its note's line, on the side with room in the panel, stacked clear of the
     * sheets above it. A sheet the user moved keeps its spot.
     */
    private void place() {
        double scale = card.mapScale();
        double left = card.getLayoutX();
        List<Double> wanted = new ArrayList<>(
                wantedTops(stickies.stream().map(sticky -> sticky.note).toList()));
        if (more != null) {
            wanted.add(Double.MAX_VALUE); // below the last note's sheet
        }
        List<double[]> stacked = stack(wanted, card.getHeight());
        // The side is chosen by the room the whole stack needs, not one sheet's.
        int columns = 1;
        for (double[] at : stacked) {
            columns = Math.max(columns, (int) at[0] + 1);
        }
        double stackWidth = (columns * WIDTH + (columns - 1) * GAP) * scale;
        double right = left + card.getWidth() * scale;
        Side side = side(left, right, stackWidth, host.getWidth());
        if (side == Side.OVER && columns > 1) {
            side = side(left, right, WIDTH * scale, host.getWidth());
        }
        List<Sheet> sheets = sheets();
        for (int i = 0; i < sheets.size(); i++) {
            Sheet sticky = sheets.get(i);
            double[] kept = moved.get(sticky.key());
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
        for (Sheet sticky : sheets()) {
            sticky.relocate(
                    card.getLayoutX() + ((sticky.fromRight ? card.getWidth() : 0) + sticky.dx) * scale,
                    card.getLayoutY() + sticky.dy * scale);
            sticky.setVisible(visible);
        }
        onMoved.run();
    }
}
