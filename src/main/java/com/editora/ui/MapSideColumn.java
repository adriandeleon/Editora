package com.editora.ui;

import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.transform.Scale;

import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

/**
 * A column stuck beside a Project Map card — a hierarchy tree ({@link MapHierarchyTree}), an answer's places as a list
 * ({@link MapResultsList}) — drawn as the card's Structure column is: a title, the column's own buttons and ×, then its
 * body. It moves, zooms and hides with its card, on the side with room (over the card's right edge when neither side
 * has), and what else stands beside the card goes past it. Escape or × closes it, the keyboard back in its card.
 */
abstract class MapSideColumn implements CardAttachment {

    /** The column's width, unzoomed. */
    static final double WIDTH = 320;

    /** Between the card's edge and the column. */
    static final double GAP = 8;

    /** The least height it takes, unzoomed, beside a short card. */
    static final double MIN_HEIGHT = 160;

    protected final ProjectMapPreview card;
    private final Pane host;
    private final Runnable onClose;
    private final Runnable onMoved;

    private final StackPane column = new StackPane();
    private final Label title = new Label();
    private final HBox header;
    private final VBox content;
    private final Scale zoom = new Scale(1, 1, 0, 0);
    private final ChangeListener<Object> follow = (obs, old, now) -> layout();

    private Side side = Side.LEFT;
    private boolean shown;
    private boolean disposed;

    /**
     * @param styleClass the column's own style class, besides the Structure column's
     * @param closeText the × button's tooltip and spoken name
     * @param onClose the column asked to be closed (×, Escape)
     * @param onActivate the column was used: it is use of its card
     * @param onMoved the column moved, showed or hid (connectors and placement must follow)
     */
    MapSideColumn(
            ProjectMapPreview card,
            Pane host,
            String styleClass,
            String closeText,
            Runnable onClose,
            Runnable onActivate,
            Runnable onMoved) {
        this.card = card;
        this.host = host;
        this.onClose = onClose;
        this.onMoved = onMoved;

        column.getStyleClass().addAll("project-map-card-structure", styleClass);
        column.getProperties().put("editora.ownsKeys", Boolean.TRUE);
        column.setManaged(false);
        zoom.xProperty().bind(card.mapScaleProperty());
        zoom.yProperty().bind(card.mapScaleProperty());
        column.getTransforms().add(zoom);

        title.getStyleClass().add("project-map-card-structure-title");
        title.setMinWidth(0);
        title.setMaxWidth(Double.MAX_VALUE);
        title.setTextOverrun(OverrunStyle.ELLIPSIS);
        title.setTooltip(new Tooltip());
        HBox.setHgrow(title, Priority.ALWAYS);
        Button close = new Button("×");
        close.getStyleClass().add("project-map-preview-button");
        MapButtonCommands.bind(column, close, "projectMap.cardClose", () -> closeText, this::close);
        close.setFocusTraversable(false);
        MapButtonCommands.stuckOn(column, card);
        header = UiZoom.spacing(new HBox(title, close), 6);
        header.getStyleClass().add("project-map-card-structure-header");
        header.setAlignment(Pos.CENTER_LEFT);

        Region tint = new Region();
        tint.getStyleClass().add("project-map-card-structure-tint");
        tint.setMouseTransparent(true);
        Region border = new Region();
        border.getStyleClass().add("project-map-card-structure-border");
        border.setMouseTransparent(true);
        content = new VBox(header);
        column.getChildren().setAll(tint, content, border);

        column.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            raise();
            onActivate.run();
        });
        column.focusWithinProperty().addListener((obs, was, within) -> {
            if (within) {
                onActivate.run();
            }
        });
        column.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                onClose.run();
                card.focusContent();
                event.consume();
            }
        });
    }

    /** Adds {@code button} to the header, before ×. */
    protected final void addHeaderButton(Button button) {
        button.getStyleClass().add("project-map-preview-button");
        button.setFocusTraversable(false);
        header.getChildren().add(header.getChildren().size() - 1, button);
    }

    /** Adds {@code button} to the header: it runs {@code action}, as card command {@code id} does in the column. */
    protected final void addHeaderButton(
            Button button, String id, java.util.function.Supplier<String> title, Runnable action) {
        addHeaderButton(button);
        MapButtonCommands.bind(column, button, id, title, action);
    }

    /** Puts {@code parts} under the header, the last one taking the height left. */
    protected final void setBody(Node... parts) {
        content.getChildren().setAll(header);
        content.getChildren().addAll(parts);
        VBox.setVgrow(parts[parts.length - 1], Priority.ALWAYS);
    }

    /** Sets the title, its tooltip, and {@code spoken}'s accessible name. */
    protected final void setTitle(String text, Node spoken) {
        title.setText(text);
        title.getTooltip().setText(text);
        spoken.setAccessibleText(text);
    }

    /** The title's text. */
    final String titleText() {
        return title.getText();
    }

    /** What takes the keyboard when the column is shown. */
    protected abstract Node focusTarget();

    /** Something it shows moved (a row scrolled, opened): the connectors follow. */
    protected final void moved() {
        if (shown && !disposed) {
            onMoved.run();
        }
    }

    /** The column asks its owner to close it. */
    protected final void close() {
        onClose.run();
    }

    // --- placement ------------------------------------------------------------------------------------------

    /** Sticks the column on its card on the side with room, {@code preferLeft} first, and puts the keyboard in it. */
    void show(boolean preferLeft) {
        show(preferLeft, true);
    }

    /** As {@link #show(boolean)}; the keyboard goes to it only with {@code focus}. */
    void show(boolean preferLeft, boolean focus) {
        if (!shown) {
            shown = true;
            card.layoutXProperty().addListener(follow);
            card.layoutYProperty().addListener(follow);
            card.widthProperty().addListener(follow);
            card.heightProperty().addListener(follow);
            card.mapScaleProperty().addListener(follow);
            card.visibleProperty().addListener(follow);
            host.getChildren().add(column);
        }
        double scale = card.mapScale();
        double left = card.getLayoutX();
        double right = left + card.getWidth() * scale;
        side = CardAttachment.side(left, right, (WIDTH + GAP) * scale, host.getWidth(), preferLeft);
        layout();
        if (focus) {
            focusTarget().requestFocus();
        }
    }

    private void layout() {
        if (disposed) {
            return;
        }
        column.setVisible(card.isVisible());
        double[] at = boundsAt(side);
        column.relocate(at[0], at[1]);
        column.resize(WIDTH, Math.max(MIN_HEIGHT, card.getHeight()));
        moved();
    }

    /** Where the column is drawn on {@code which} side of its card, {@code {x, y, w, h}} in the host's pixels. */
    double[] boundsAt(Side which) {
        double scale = card.mapScale();
        double dx = switch (which) {
            case RIGHT -> card.getWidth() + GAP;
            case LEFT -> -(WIDTH + GAP);
            case OVER -> card.getWidth() - WIDTH;
        };
        return new double[] {
            card.getLayoutX() + dx * scale,
            card.getLayoutY(),
            WIDTH * scale,
            Math.max(MIN_HEIGHT, card.getHeight()) * scale
        };
    }

    /** The side it stands on. */
    Side side() {
        return side;
    }

    @Override
    public ProjectMapPreview card() {
        return card;
    }

    @Override
    public double reserved(Side which) {
        return shown && !disposed && side == which && which != Side.OVER ? WIDTH + GAP : 0;
    }

    @Override
    public void raise() {
        column.toFront();
    }

    @Override
    public boolean contains(@Nullable Node node) {
        for (Node at = node; at != null; at = at.getParent()) {
            if (at == column) {
                return true;
            }
        }
        return false;
    }

    @Override
    public double @Nullable [] bounds() {
        return shown && !disposed && column.isVisible() ? boundsAt(side) : null;
    }

    @Override
    public void drawConnectors(javafx.scene.canvas.GraphicsContext g) {}

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        onDisposed();
        card.layoutXProperty().removeListener(follow);
        card.layoutYProperty().removeListener(follow);
        card.widthProperty().removeListener(follow);
        card.heightProperty().removeListener(follow);
        card.mapScaleProperty().removeListener(follow);
        card.visibleProperty().removeListener(follow);
        host.getChildren().remove(column);
    }

    /** Told once when the column is taken off its card: answers still on their way are to be dropped. */
    protected void onDisposed() {}

    /** Whether it was taken off its card. */
    boolean disposed() {
        return disposed;
    }
}
