package com.editora.ui;

import javafx.beans.InvalidationListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.transform.Scale;

import org.jspecify.annotations.Nullable;

/**
 * A trail stop's note (Map explore, position 2), stuck on the stop's card as a sticky note is: a small read-only
 * sheet beside the card, tied by a connector to the stop's first line, that moves, zooms, rises, hides and closes
 * with the card. It looks like the card's Personal Notes sheets ({@link ProjectMapStickyNotes}) but is not one: the
 * note belongs to the trail, in the project, and is edited in the trail's editor. It can be dragged to another
 * spot beside the card, which it keeps while the stop is shown.
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
    private boolean disposed;
    private double dragScreenX;
    private double dragScreenY;
    private double dragLayoutX;
    private double dragLayoutY;
    /** What the sheet keeps below (the trail bar, in the map area's pixels), or null. */
    private java.util.function.Supplier<javafx.geometry.@Nullable Bounds> avoid = () -> null;

    ProjectMapTrailNote(
            ProjectMapPreview card, Pane host, String header, String note, int anchorLine, Runnable onMoved) {
        this.card = card;
        this.host = host;
        this.anchorLine = anchorLine;
        this.onMoved = onMoved == null ? () -> {} : onMoved;
        getStyleClass().addAll("project-map-sticky-note", "project-map-trail-note");
        setManaged(false);
        zoom.xProperty().bind(card.mapScaleProperty());
        zoom.yProperty().bind(card.mapScaleProperty());
        getTransforms().add(zoom);

        Label where = new Label(header, Icons.notes());
        where.getStyleClass().add("project-map-sticky-note-where");
        where.setMinWidth(0);
        where.setMaxWidth(Double.MAX_VALUE);
        HBox top = new HBox(where);
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
    }

    /** Keeps the sheet below {@code bounds} (the trail bar) where the two would overlap. */
    void setAvoid(java.util.function.@Nullable Supplier<javafx.geometry.@Nullable Bounds> bounds) {
        avoid = bounds == null ? () -> null : bounds;
        follow();
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
        relocate(x, y);
        onMoved.run();
        event.consume();
    }

    /** Beside the card on the right, else the left (the side with room in the map area), level with its line. */
    private void place() {
        double scale = card.mapScale();
        double left = card.getLayoutX();
        double right = left + card.getWidth() * scale;
        double width = (WIDTH + ProjectMapStickyNotes.OFFSET) * scale;
        Side side = CardAttachment.side(left, right, width, host.getWidth(), false);
        fromLeft = side == Side.LEFT;
        dx = side == Side.OVER ? -(WIDTH + ProjectMapStickyNotes.GAP) : ProjectMapStickyNotes.OFFSET;
        double lineY = anchorLine < 0 ? Double.NaN : card.noteAnchorY(anchorLine);
        dy = Double.isNaN(lineY) ? 0 : Math.max(0, (lineY - card.getLayoutY()) / scale - HEADER_MIDDLE);
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
        double x = fromLeft
                ? card.getLayoutX() - (dx + WIDTH) * scale
                : card.getLayoutX() + (card.getWidth() + dx) * scale;
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
