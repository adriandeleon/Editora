package com.editora.ui;

import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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
import javafx.scene.transform.Scale;

import static com.editora.i18n.Messages.tr;

/**
 * A card of the Project Map there is one of at a time — the History card (gap 12), the Dependencies card (gap 14):
 * a code card's frame with a title bar and a close button, which floats over the map, moves by its title bar or
 * Alt+arrows, resizes by its corner or Alt+Shift+arrows, zooms with the map, and closes on Escape. The content is the
 * subclass's: {@link #frame}'s centre.
 */
abstract class MapFloatingCard extends StackPane {

    /** The card's frame: the title bar on top, the subclass's content in the centre. */
    protected final BorderPane frame = new BorderPane();

    protected final Label title = new Label();
    private final HBox titleBar = new HBox(7);
    private final Button close = new Button("×");
    private final Region resizeGrip = new Region();
    private final Scale mapZoom = new Scale(1, 1, 0, 0);
    private final double minimumWidth;
    private final double minimumHeight;

    private Runnable onClose = () -> setVisible(false);
    private Runnable onActivate = () -> {};
    private Runnable onEscape = () -> onClose.run();

    private boolean placed;
    private double preferredWidth;
    private double preferredHeight;
    private double dragScreenX;
    private double dragScreenY;
    private double dragLayoutX;
    private double dragLayoutY;
    private double resizeScreenX;
    private double resizeScreenY;
    private double resizeWidth;
    private double resizeHeight;

    /** A card styled {@code styleClass}, its title led by {@code icon}, never smaller than {@code minimumWidth × …}. */
    MapFloatingCard(String styleClass, Node icon, double minimumWidth, double minimumHeight) {
        this.minimumWidth = minimumWidth;
        this.minimumHeight = minimumHeight;
        this.preferredWidth = minimumWidth;
        this.preferredHeight = minimumHeight;
        getStyleClass().addAll("project-map-preview", styleClass);
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setManaged(false);
        setVisible(false);
        title.getStyleClass().add("project-map-preview-title");
        title.setMinWidth(0);
        title.setMnemonicParsing(false);
        title.setGraphic(icon);
        HBox.setHgrow(title, Priority.ALWAYS);
        close.getStyleClass().add("project-map-preview-button");
        close.setMinWidth(Region.USE_PREF_SIZE);
        close.setTooltip(new Tooltip(tr("project.map.preview.close")));
        close.setAccessibleText(tr("project.map.preview.close"));
        close.setOnAction(event -> onClose.run());
        titleBar.getStyleClass().add("project-map-preview-header");
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.getChildren().setAll(title, close);
        titleBar.addEventHandler(MouseEvent.MOUSE_PRESSED, this::dragPressed);
        titleBar.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::dragged);
        frame.getStyleClass().add("project-map-preview-frame");
        frame.setTop(titleBar);
        resizeGrip.getStyleClass().add("project-map-preview-resize");
        resizeGrip.setCursor(Cursor.SE_RESIZE);
        resizeGrip.addEventHandler(MouseEvent.MOUSE_PRESSED, this::resizePressed);
        resizeGrip.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::resized);
        getChildren().addAll(frame, resizeGrip);
        getTransforms().add(mapZoom);
        addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            toFront();
            onActivate.run();
        });
        focusWithinProperty().addListener((obs, was, within) -> {
            if (within) {
                toFront();
                onActivate.run();
            }
        });
        addEventFilter(ScrollEvent.SCROLL, event -> onActivate.run());
        getProperties().put(com.editora.command.KeyDispatcher.CLAIMED_KEYS, ProjectMapPreview.GEOMETRY_KEYS);
        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            double[] step = ProjectMapPreview.geometryStep(event);
            if (step != null) {
                moveOrResizeBy(step);
                event.consume();
                return;
            }
            if (event.getCode() == KeyCode.ESCAPE
                    && !event.isShiftDown()
                    && !event.isControlDown()
                    && !event.isAltDown()
                    && !event.isMetaDown()) {
                onEscape.run();
                event.consume();
            }
        });
    }

    /** Moves the keyboard into the card's content. */
    abstract void focusContent();

    String title() {
        return title.getText();
    }

    void setOnClose(Runnable action) {
        onClose = action;
    }

    void setOnActivate(Runnable action) {
        onActivate = action;
    }

    void setOnEscape(Runnable action) {
        onEscape = action;
    }

    /** Places the card and shows it. */
    void place(ProjectMapPreview.Placement placement) {
        setVisible(true);
        resizeRelocate(placement.x(), placement.y(), placement.width(), placement.height());
        preferredWidth = placement.width();
        preferredHeight = placement.height();
        placed = true;
        toFront();
    }

    boolean placed() {
        return placed;
    }

    /** Keeps the card within a map area {@code width × height}, at its size or smaller, never below its minimum. */
    void constrainTo(double width, double height) {
        if (!placed || width <= 0 || height <= 0) {
            return;
        }
        resize(
                ProjectMapPreview.boundedSize(
                        preferredWidth, minimumWidth, ProjectMapPreview.unzoomedRoom(width, mapScale())),
                ProjectMapPreview.boundedSize(
                        preferredHeight, minimumHeight, ProjectMapPreview.unzoomedRoom(height, mapScale())));
    }

    void setMapScale(double scale) {
        mapZoom.setX(scale);
        mapZoom.setY(scale);
    }

    double mapScale() {
        return mapZoom.getX();
    }

    @Override
    protected void layoutChildren() {
        frame.resizeRelocate(0, 0, getWidth(), getHeight());
        double grip = 18;
        resizeGrip.resizeRelocate(Math.max(0, getWidth() - grip), Math.max(0, getHeight() - grip), grip, grip);
    }

    // --- moving and sizing ------------------------------------------------------------------------------------

    private void moveOrResizeBy(double[] step) {
        double scale = mapScale();
        if (step[0] != 0 || step[1] != 0) {
            relocate(getLayoutX() + step[0], getLayoutY() + step[1]);
        } else if (getParent() instanceof Region parent) {
            preferredWidth = ProjectMapPreview.boundedSize(
                    getWidth() + step[2] / scale,
                    minimumWidth,
                    Math.max(1, ProjectMapPreview.unzoomedRoom(parent.getWidth(), scale)));
            preferredHeight = ProjectMapPreview.boundedSize(
                    getHeight() + step[3] / scale,
                    minimumHeight,
                    Math.max(1, ProjectMapPreview.unzoomedRoom(parent.getHeight(), scale)));
            resize(preferredWidth, preferredHeight);
        }
        ProjectMapPreview.announceGeometry(this, scale);
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
        if (!event.isPrimaryButtonDown()) {
            return;
        }
        relocate(dragLayoutX + event.getScreenX() - dragScreenX, dragLayoutY + event.getScreenY() - dragScreenY);
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
        double scale = mapScale();
        preferredWidth = ProjectMapPreview.boundedSize(
                resizeWidth + (event.getScreenX() - resizeScreenX) / scale,
                minimumWidth,
                Math.max(1, ProjectMapPreview.unzoomedRoom(parent.getWidth(), scale)));
        preferredHeight = ProjectMapPreview.boundedSize(
                resizeHeight + (event.getScreenY() - resizeScreenY) / scale,
                minimumHeight,
                Math.max(1, ProjectMapPreview.unzoomedRoom(parent.getHeight(), scale)));
        resize(preferredWidth, preferredHeight);
        event.consume();
    }
}
