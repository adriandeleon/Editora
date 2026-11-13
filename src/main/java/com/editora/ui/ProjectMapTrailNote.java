package com.editora.ui;

import javafx.beans.InvalidationListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.transform.Scale;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * A trail stop's note (Map explore, position 2), stuck on the stop's card as a sticky note is: a small read-only
 * sheet beside the card, tied by a connector to the stop's first line, that moves, zooms, rises, hides and closes
 * with the card. It looks like the card's Personal Notes sheets ({@link ProjectMapStickyNotes}) but is not one: the
 * note belongs to the trail, in the project, and is edited in the trail's editor. It can be dragged to another
 * spot beside the card, which it keeps while the stop is shown. Its × hides it until the stop is played again.
 */
final class ProjectMapTrailNote extends VBox implements CardAttachment {

    static final double WIDTH = ProjectMapStickyNotes.WIDTH;
    private static final double HEADER_MIDDLE = 12;

    private final ProjectMapPreview card;
    private final Pane host;
    private final Runnable onMoved;
    /** The 0-based file line the connector is pinned to; -1 for the card's header. */
    private final int anchorLine;

    private final Scale zoom = new Scale(1, 1, 0, 0);
    private final InvalidationListener follow = observable -> follow();
    /** Where it sits, in unzoomed units from the card's right edge (or its left, when {@link #fromLeft}). */
    private double dx = ProjectMapStickyNotes.OFFSET;

    private double dy;
    private boolean fromLeft;
    private boolean placed;
    /** The reader dragged the sheet: it keeps that spot and is never placed again. */
    private boolean dragged;

    private boolean disposed;
    /** What × does: the trail takes the sheet off its card; alone, the sheet only goes. */
    private Runnable onClose = this::dispose;

    private double dragScreenX;
    private double dragScreenY;
    private double dragLayoutX;
    private double dragLayoutY;
    /** What the sheet keeps below (the trail bar, in the map area's pixels), or null. */
    private java.util.function.Supplier<javafx.geometry.@Nullable Bounds> avoid = () -> null;
    /** The columns on screen, {@code {x, y, w, h, …}} in the map area's pixels: the side covering less is taken. */
    private final java.util.function.Supplier<java.util.List<double[]>> columns;
    /** Pans the map sideways by so many pixels, the cards with it; null where the sheet may not move the map. */
    private final java.util.function.@Nullable DoubleConsumer panMap;

    ProjectMapTrailNote(
            ProjectMapPreview card, Pane host, String header, String note, int anchorLine, Runnable onMoved) {
        this(card, host, header, note, anchorLine, onMoved, java.util.List::of, null);
    }

    /**
     * {@code columns}: the map's columns on screen, which the sheet keeps off when a side has room (review 4 A8).
     * {@code panMap}: pans the map sideways, for a card that opened centred with no room left beside it for the sheet.
     */
    ProjectMapTrailNote(
            ProjectMapPreview card,
            Pane host,
            String header,
            String note,
            int anchorLine,
            Runnable onMoved,
            java.util.function.@Nullable Supplier<java.util.List<double[]>> columns,
            java.util.function.@Nullable DoubleConsumer panMap) {
        this.card = card;
        this.panMap = panMap;
        this.host = host;
        this.anchorLine = anchorLine;
        this.onMoved = onMoved == null ? () -> {} : onMoved;
        this.columns = columns == null ? java.util.List::of : columns;
        getStyleClass().addAll("project-map-sticky-note", "project-map-trail-note");
        setManaged(false);
        zoom.xProperty().bind(card.mapScaleProperty());
        zoom.yProperty().bind(card.mapScaleProperty());
        getTransforms().add(zoom);

        Label where = new Label(header, Icons.notes());
        where.getStyleClass().add("project-map-sticky-note-where");
        where.setMinWidth(0);
        where.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(where, Priority.ALWAYS);
        Button close = new Button("×");
        close.getStyleClass().addAll("flat", "project-map-sticky-note-close");
        close.setFocusTraversable(false);
        // One name, for the tooltip and a screen reader alike: what closing does, and that the note is kept.
        MapButtonCommands.bind(
                this,
                close,
                "projectMap.cardClose",
                () -> tr("project.map.trails.note.closeHelp"),
                () -> onClose.run());
        MapButtonCommands.stuckOn(this, card);
        HBox top = new HBox(where, close);
        top.getStyleClass().add("project-map-sticky-note-header");
        top.setAlignment(Pos.CENTER_LEFT);
        top.addEventHandler(MouseEvent.MOUSE_PRESSED, this::dragPressed);
        top.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::dragged);
        Label body = new Label(note);
        body.getStyleClass().add("project-map-trail-note-body");
        body.setWrapText(true);
        body.setMinHeight(Label.USE_PREF_SIZE);
        body.setMaxWidth(WIDTH - 16);
        getChildren().setAll(top, body);
        setPrefWidth(WIDTH);
        setMinWidth(WIDTH);
        setMaxWidth(WIDTH);
        setAccessibleText(header + ": " + note);
        setFocusTraversable(false);

        card.layoutXProperty().addListener(follow);
        card.layoutYProperty().addListener(follow);
        card.widthProperty().addListener(follow);
        card.heightProperty().addListener(follow);
        card.mapScaleProperty().addListener(follow);
        card.visibleProperty().addListener(follow);
        host.getChildren().add(this);
        applyCss();
        autosize();
        follow();
        // The card may still settle (its height, the room made for it) after it opened: the side is chosen once more
        // where it then stands, unless the reader has dragged the sheet meanwhile.
        javafx.application.Platform.runLater(this::viewsReplace);
    }

    /**
     * Chooses the sheet's side again where the card stands now (not once the reader dragged it). The card has settled
     * by now: when it left no room beside it on either side, the map may pan to make some.
     */
    private void viewsReplace() {
        if (disposed || dragged || !placed) {
            return;
        }
        placeSide(true);
        follow();
    }

    /** Keeps the sheet below {@code bounds} (the trail bar) where the two would overlap. */
    void setAvoid(java.util.function.@Nullable Supplier<javafx.geometry.@Nullable Bounds> bounds) {
        avoid = bounds == null ? () -> null : bounds;
        follow();
    }

    /** What × does instead of only disposing of the sheet: the trail takes it off the card, so the card forgets it. */
    void setOnClose(Runnable action) {
        onClose = action;
    }

    /** The note's text, as shown. For tests. */
    String text() {
        return ((Label) getChildren().get(1)).getText();
    }

    private void dragPressed(MouseEvent event) {
        if (event.getButton() != MouseButton.PRIMARY) {
            return;
        }
        dragScreenX = event.getScreenX();
        dragScreenY = event.getScreenY();
        dragLayoutX = getLayoutX();
        dragLayoutY = getLayoutY();
        raise();
        event.consume();
    }

    private void dragged(MouseEvent event) {
        if (!event.isPrimaryButtonDown()) {
            return;
        }
        double x = dragLayoutX + event.getScreenX() - dragScreenX;
        double y = dragLayoutY + event.getScreenY() - dragScreenY;
        double scale = card.mapScale();
        double right = card.getLayoutX() + card.getWidth() * scale;
        fromLeft = x + WIDTH * scale / 2 < (card.getLayoutX() + right) / 2;
        // The gap between the sheet's near edge and the card, as place() keeps it: follow() puts the sheet back there.
        dx = fromLeft ? (card.getLayoutX() - x) / scale - WIDTH : (x - right) / scale;
        dy = (y - card.getLayoutY()) / scale;
        dragged = true;
        relocate(x, y);
        onMoved.run();
        event.consume();
    }

    /**
     * Beside the card on the right, else the left (the side with room in the map area), level with its line. When
     * both sides have room, the one that covers less of the map's columns (review 4 A8): the note must not hide the
     * rows the stop is about.
     */
    private void place() {
        double scale = card.mapScale();
        double lineY = anchorLine < 0 ? Double.NaN : card.noteAnchorY(anchorLine);
        dy = Double.isNaN(lineY) ? 0 : Math.max(0, (lineY - card.getLayoutY()) / scale - HEADER_MIDDLE);
        placeSide(false);
    }

    /**
     * The side alone, at the height already chosen: the card moved, or settled after it opened. With {@code mayPan}
     * (the card has just settled, the reader is not moving the map) and no room on either side, the map pans as
     * little as gives the sheet a side when the map area holds the card and the sheet together: a new card opens
     * centred in the map area, which can leave less than a sheet's width on both sides of a wide card, and the sheet
     * would otherwise stand over the very lines the stop is about.
     */
    private void placeSide(boolean mayPan) {
        double scale = card.mapScale();
        double left = card.getLayoutX();
        double right = left + card.getWidth() * scale;
        double width = (WIDTH + ProjectMapStickyNotes.OFFSET) * scale;
        double sheetHeight = Math.max(getHeight(), prefHeight(WIDTH)) * scale;
        Side side = viewsSide(
                left,
                right,
                width,
                host.getWidth(),
                card.getLayoutY() + dy * scale,
                sheetHeight,
                WIDTH * scale,
                columns.get());
        RoomPan pan = side == Side.OVER && mayPan && panMap != null
                ? viewsRoomPan(
                        left,
                        right,
                        width,
                        host.getWidth(),
                        card.getLayoutY() + dy * scale,
                        sheetHeight,
                        WIDTH * scale,
                        columns.get())
                : null;
        if (pan != null) {
            side = pan.side();
        }
        fromLeft = side == Side.LEFT;
        dx = side == Side.OVER ? -(WIDTH + ProjectMapStickyNotes.GAP) : ProjectMapStickyNotes.OFFSET;
        if (pan != null && panMap != null) {
            panMap.accept(pan.dx()); // the card moves with the map, and follow() puts the sheet beside it
        }
    }

    /** The side a sheet takes once the map has panned {@code dx} pixels across. */
    record RoomPan(Side side, double dx) {}

    /**
     * For a card from {@code left} to {@code right} with less than {@code room} free beside it on both sides of a
     * panel {@code panelWidth} wide: the least sideways pan that frees {@code room} on one side, when the panel holds
     * the card and the room together; null when it does not (the sheet then stands over the card). Of the two sides the
     * one covering less of {@code columns} (as {@link #viewsSide} weighs them) wins, then the shorter pan, then the
     * right. Pure.
     */
    static @Nullable RoomPan viewsRoomPan(
            double left,
            double right,
            double room,
            double panelWidth,
            double y,
            double height,
            double sheetWidth,
            java.util.List<double[]> columns) {
        if (panelWidth <= 0 || right - left + room > panelWidth) {
            return null;
        }
        // Whole pixels, rounded away from the card: the sheet ends up inside the map area, not a rounding error past
        // its edge (where follow() would put it over the card again).
        double toRight = Math.min(0, Math.floor(panelWidth - room - right)); // leftwards: room on the card's right
        double toLeft = Math.max(0, Math.ceil(room - left)); // rightwards: room on its left
        double gap = room - sheetWidth;
        double onRight = covered(right + gap, y, sheetWidth, height, columns);
        double onLeft = covered(left - room, y, sheetWidth, height, columns);
        boolean takeLeft = onLeft != onRight ? onLeft < onRight : toLeft < -toRight;
        return takeLeft ? new RoomPan(Side.LEFT, toLeft) : new RoomPan(Side.RIGHT, toRight);
    }

    /**
     * The side of a card from {@code left} to {@code right} for a sheet {@code sheetWidth} wide and {@code height} tall
     * at {@code y} that needs {@code room} beside the card: as {@link CardAttachment#side} picks it (the right first),
     * except that when both sides have room the left is taken if it covers less of {@code columns}' area. Pure.
     */
    static Side viewsSide(
            double left,
            double right,
            double room,
            double panelWidth,
            double y,
            double height,
            double sheetWidth,
            java.util.List<double[]> columns) {
        Side side = CardAttachment.side(left, right, room, panelWidth, false);
        if (side != Side.RIGHT || left < room) {
            return side;
        }
        double gap = room - sheetWidth;
        double onRight = covered(right + gap, y, sheetWidth, height, columns);
        double onLeft = covered(left - room, y, sheetWidth, height, columns);
        return onLeft < onRight ? Side.LEFT : Side.RIGHT;
    }

    /** The area of {@code columns} a rectangle covers. Pure. */
    private static double covered(double x, double y, double w, double h, java.util.List<double[]> columns) {
        double area = 0;
        for (double[] column : columns) {
            double across = Math.min(x + w, column[0] + column[2]) - Math.max(x, column[0]);
            double down = Math.min(y + h, column[1] + column[3]) - Math.max(y, column[1]);
            if (across > 0 && down > 0) {
                area += across * down;
            }
        }
        return area;
    }

    private double sheetX(double scale) {
        return fromLeft ? card.getLayoutX() - (dx + WIDTH) * scale : card.getLayoutX() + (card.getWidth() + dx) * scale;
    }

    private void follow() {
        if (disposed) {
            return;
        }
        if (!placed && card.getWidth() > 0) {
            placed = true;
            place();
        }
        double scale = card.mapScale();
        double x = sheetX(scale);
        if (placed && !dragged && host.getWidth() > 0 && (x < 0 || x + WIDTH * scale > host.getWidth())) {
            placeSide(false); // the card moved: the side it was given is now past the map area's edge
            x = sheetX(scale);
        }
        double y = card.getLayoutY() + dy * scale;
        javafx.geometry.Bounds bar = avoid.get();
        if (bar != null && x < bar.getMaxX() && x + WIDTH * scale > bar.getMinX() && y < bar.getMaxY() + 4) {
            y = bar.getMaxY() + 4; // its header is never under the trail bar
        }
        relocate(x, y);
        setVisible(card.isVisible() && placed);
        onMoved.run();
    }

    @Override
    public ProjectMapPreview card() {
        return card;
    }

    /** It stands past whatever else is beside the card, as the sticky notes do: nothing goes past it. */
    @Override
    public double reserved(Side side) {
        return 0;
    }

    @Override
    public void raise() {
        toFront();
    }

    @Override
    public boolean contains(@Nullable Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current == this) {
                return true;
            }
        }
        return false;
    }

    @Override
    public double @Nullable [] bounds() {
        if (disposed || !isVisible()) {
            return null;
        }
        double scale = card.mapScale();
        return new double[] {getLayoutX(), getLayoutY(), getWidth() * scale, getHeight() * scale};
    }

    /** From the stop's first line on the card's edge to the sheet's header, as a sticky note's connector runs. */
    @Override
    public void drawConnectors(GraphicsContext g) {
        if (disposed || !isVisible()) {
            return;
        }
        double scale = card.mapScale();
        double left = card.getLayoutX();
        double right = left + card.getWidth() * scale;
        double lineY = anchorLine < 0 ? Double.NaN : card.noteAnchorY(anchorLine);
        if (Double.isNaN(lineY)) {
            lineY = card.getLayoutY() + HEADER_MIDDLE * scale;
        }
        double sheetLeft = getLayoutX();
        double sheetRight = sheetLeft + WIDTH * scale;
        boolean after = (sheetLeft + sheetRight) / 2 >= (left + right) / 2;
        double x1 = after ? Math.min(right, sheetLeft) : Math.max(left, sheetRight);
        double x2 = after ? sheetLeft : sheetRight;
        double y2 = getLayoutY() + HEADER_MIDDLE * scale;
        double control = Math.max(14, Math.abs(x2 - x1) * 0.45);
        g.beginPath();
        g.moveTo(x1, lineY);
        g.bezierCurveTo(x1 + (after ? control : -control), lineY, x2 + (after ? -control : control), y2, x2, y2);
        g.stroke();
        g.fillOval(x1 - 2.5, lineY - 2.5, 5, 5);
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        card.layoutXProperty().removeListener(follow);
        card.layoutYProperty().removeListener(follow);
        card.widthProperty().removeListener(follow);
        card.heightProperty().removeListener(follow);
        card.mapScaleProperty().removeListener(follow);
        card.visibleProperty().removeListener(follow);
        zoom.xProperty().unbind();
        zoom.yProperty().unbind();
        host.getChildren().remove(this);
        onMoved.run();
    }
}
