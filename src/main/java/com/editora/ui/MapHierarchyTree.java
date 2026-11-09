package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.beans.value.ChangeListener;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.transform.Scale;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * A call or type hierarchy as a tree, stuck beside the Project Map card it was asked from (Map code browser gap 4).
 * Show Callers / Callees / Supertypes / Subtypes as Cards answer one level, as cards; this goes as deep as the reader
 * opens it. The root is the declaration asked about; each row is a declaration of the next level — a caller, a
 * callee, a subtype or a supertype — and opening a row asks the same question of it, one level at a time, by the
 * language server or, for the callers, by name as the cards are. A row that is one of its own ancestors (recursion)
 * is marked and not asked again. ⇄ turns the tree the other way (callers ⇄ callees, subtypes ⇄ supertypes). Enter or
 * a double-click opens a row's declaration as a card beside the card asked from, the keyboard following, so Back
 * returns. It moves, zooms and closes with its card; Escape closes it.
 */
final class MapHierarchyTree implements CardAttachment {

    /** The column's width, unzoomed. */
    static final double WIDTH = 320;

    /** Between the card's edge and the column. */
    static final double GAP = 8;

    private static final PseudoClass MESSAGE = PseudoClass.getPseudoClass("message");

    /** One level of the tree: its rows' declarations, how they were found, and what to say besides. */
    record Level(
            List<MapCardLinks.Group> groups,
            MapCardLinks.Via via,
            @Nullable String note,
            boolean partial,
            @Nullable String name) {}

    /** Asks one level: {@code request}'s answer, grouped by declaration, handed to {@code answer} on the FX thread. */
    interface Asker {
        void ask(MapCardLinks.Request request, Consumer<Level> answer);
    }

    /** Opens a row's declaration: {@code group} found for {@code kind} of what is called {@code name}. */
    interface Opener {
        void open(MapCardLinks.Group group, MapCardLinks.Kind kind, @Nullable String name);
    }

    /**
     * A row: the declaration asked about (the root), one found ({@code group}), or a line that says something (still
     * asking, none found, stopped early). {@code recursive}: the declaration is one of its own ancestors.
     */
    record Row(MapCardLinks.@Nullable Group group, String text, boolean recursive, boolean message) {
        static Row message(String text) {
            return new Row(null, text, false, true);
        }
    }

    private final ProjectMapPreview card;
    private final Pane host;
    private final MapCardLinks.Request asked;
    private final Asker asker;
    private final Opener opener;
    private final Runnable onClose;
    private final Runnable onActivate;
    private final Runnable onMoved;

    private final StackPane column = new StackPane();
    private final Label title = new Label();
    private final Button flip = new Button("⇄");
    private final TreeView<Row> tree = new TreeView<>();
    private final Scale zoom = new Scale(1, 1, 0, 0);
    private final ChangeListener<Object> follow = (obs, old, now) -> layout();

    private MapCardLinks.Kind kind;
    private @Nullable String name;
    /** Bumped when the tree turns the other way or closes: answers asked before are dropped. */
    private long generation;

    private Side side = Side.LEFT;
    private boolean shown;
    private boolean disposed;

    /**
     * @param asked the question about the root (its kind is the tree's first direction)
     * @param onClose the tree asked to be closed (×, Escape)
     * @param onActivate the tree was used: it is use of its card
     * @param onMoved the tree moved, showed or hid (connectors and placement must follow)
     */
    MapHierarchyTree(
            ProjectMapPreview card,
            Pane host,
            MapCardLinks.Request asked,
            Asker asker,
            Opener opener,
            Runnable onClose,
            Runnable onActivate,
            Runnable onMoved) {
        this.card = card;
        this.host = host;
        this.asked = asked;
        this.asker = asker;
        this.opener = opener;
        this.onClose = onClose;
        this.onActivate = onActivate;
        this.onMoved = onMoved;
        this.kind = asked.kind();
        this.name = asked.name();

        column.getStyleClass().addAll("project-map-card-structure", "project-map-hierarchy");
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
        flip.getStyleClass().add("project-map-preview-button");
        flip.setTooltip(new Tooltip());
        flip.setFocusTraversable(false);
        flip.setOnAction(event -> turn());
        Button close = new Button("×");
        close.getStyleClass().add("project-map-preview-button");
        close.setTooltip(new Tooltip(tr("project.map.hierarchy.close")));
        close.setAccessibleText(tr("project.map.hierarchy.close"));
        close.setFocusTraversable(false);
        close.setOnAction(event -> onClose.run());
        HBox header = new HBox(6, title, flip, close);
        header.getStyleClass().add("project-map-card-structure-header");
        header.setAlignment(Pos.CENTER_LEFT);

        tree.getStyleClass().addAll("project-map-card-structure-tree", "project-map-hierarchy-tree");
        tree.setCellFactory(view -> new RowCell());
        tree.setAccessibleHelp(tr("project.map.hierarchy.help"));
        VBox.setVgrow(tree, Priority.ALWAYS);
        Region tint = new Region();
        tint.getStyleClass().add("project-map-card-structure-tint");
        tint.setMouseTransparent(true);
        Region border = new Region();
        border.getStyleClass().add("project-map-card-structure-border");
        border.setMouseTransparent(true);
        column.getChildren().setAll(tint, new VBox(header, tree), border);

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
        tree.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER && !event.isShortcutDown() && !event.isAltDown()) {
                if (openSelected()) {
                    event.consume();
                }
            }
        });
        relabel();
        root();
    }

    // --- the rows -------------------------------------------------------------------------------------------

    /** A row that asks its level when it is first opened (the root at once). */
    private final class LevelItem extends TreeItem<Row> {
        final MapCardLinks.Request request;
        private boolean asked;

        LevelItem(Row row, MapCardLinks.Request request) {
            super(row);
            this.request = request;
            getChildren().add(new TreeItem<>(Row.message(tr("project.map.hierarchy.asking"))));
            expandedProperty().addListener((obs, was, open) -> {
                if (open && !this.asked) {
                    load(this);
                }
            });
        }
    }

    /** The root: the declaration asked about, its first level asked at once. */
    private void root() {
        LevelItem top = new LevelItem(
                new Row(null, rootText(), false, false),
                new MapCardLinks.Request(kind, asked.file(), asked.line(), asked.column(), asked.name(), asked.text()));
        tree.setRoot(top);
        tree.setShowRoot(true);
        top.setExpanded(true);
        tree.getSelectionModel().select(top);
    }

    private String rootText() {
        return name == null || name.isBlank() ? tr("project.map.hierarchy.unnamed") : name;
    }

    /** Asks {@code item}'s level and lists it, unless the tree turned or closed meanwhile. */
    private void load(LevelItem item) {
        item.asked = true;
        long at = generation;
        asker.ask(item.request, level -> {
            if (at != generation || disposed) {
                return;
            }
            if (item == tree.getRoot() && level.name() != null && !level.name().isBlank()) {
                name = level.name(); // asked at a caret: the name the answer found there
                item.setValue(new Row(null, rootText(), false, false));
                relabel();
            }
            item.getChildren().setAll(children(item, level));
            moved();
        });
    }

    /** {@code level}'s rows under {@code parent}: a declaration already above it is marked, not asked again. */
    private List<TreeItem<Row>> children(LevelItem parent, Level level) {
        List<TreeItem<Row>> rows = new ArrayList<>();
        for (MapCardLinks.Group group : level.groups()) {
            String text = rowText(group);
            if (ancestorOf(parent, group)) {
                rows.add(new TreeItem<>(new Row(group, tr("project.map.hierarchy.recursive", text), true, false)));
                continue;
            }
            MapCardLinks.Request next = requestOf(group, kind, level.via());
            Row row = new Row(group, text, false, false);
            rows.add(next == null ? new TreeItem<>(row) : new LevelItem(row, next));
        }
        if (rows.isEmpty()) {
            String none = MapCardLinks.noneText(kind, level.name() != null ? level.name() : parent.request.name());
            rows.add(new TreeItem<>(Row.message(level.note() == null ? none : level.note() + " " + none)));
        } else if (level.partial()) {
            rows.add(new TreeItem<>(Row.message(tr("project.map.links.partial"))));
        }
        return rows;
    }

    /** A row's text: the declaration, its file and line, and how many calls when more than one. Pure. */
    static String rowText(MapCardLinks.Group group) {
        Path file = group.file();
        Path fileName = file.getFileName();
        String where = fileName == null ? file.toString() : fileName.toString();
        String text = tr("project.map.hierarchy.row", group.label(), where, String.valueOf(group.line() + 1));
        return group.count() > 1 ? tr("project.map.hierarchy.rowCalls", text, String.valueOf(group.count())) : text;
    }

    /**
     * The question that opens {@code group}'s own level: the same kind, asked at its declaration's name; null when it
     * cannot be asked — a place matched by name outside any declaration is a use, not a declaration to ask about.
     * Pure.
     */
    static MapCardLinks.@Nullable Request requestOf(
            MapCardLinks.Group group, MapCardLinks.Kind kind, MapCardLinks.Via via) {
        ProjectMapStructure.Node node = group.declaration();
        if (node != null) {
            String declared = ProjectMapView.followName(node.symbol().label());
            return new MapCardLinks.Request(kind, group.file(), node.symbol().line(), -1, declared, null);
        }
        String mark = group.mark();
        if (via == MapCardLinks.Via.NAME || mark == null || mark.isBlank()) {
            return null;
        }
        return new MapCardLinks.Request(kind, group.file(), group.line(), -1, mark, null);
    }

    /** Whether {@code group} is the root's declaration or a row's above {@code parent} (it, included). */
    private boolean ancestorOf(TreeItem<Row> parent, MapCardLinks.Group group) {
        for (TreeItem<Row> at = parent; at != null; at = at.getParent()) {
            if (at instanceof LevelItem level && sameDeclaration(level.request, group)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code group}'s declaration is the one {@code request} asks about (its file and line). Pure. */
    static boolean sameDeclaration(MapCardLinks.Request request, MapCardLinks.Group group) {
        if (!group.file().equals(request.file())) {
            return false;
        }
        ProjectMapStructure.Node node = group.declaration();
        return (node != null ? node.symbol().line() : group.line()) == request.line();
    }

    /** Turns the tree the other way: callers ⇄ callees, subtypes ⇄ supertypes; the root asked again. */
    void turn() {
        kind = opposite(kind);
        generation++;
        relabel();
        root();
        tree.requestFocus();
    }

    /** The other way of {@code kind}'s hierarchy. Pure. */
    static MapCardLinks.Kind opposite(MapCardLinks.Kind kind) {
        return switch (kind) {
            case CALLERS -> MapCardLinks.Kind.CALLEES;
            case CALLEES -> MapCardLinks.Kind.CALLERS;
            case SUBTYPES -> MapCardLinks.Kind.SUPERTYPES;
            case SUPERTYPES -> MapCardLinks.Kind.SUBTYPES;
            default -> kind;
        };
    }

    private void relabel() {
        String text = titleText(kind, rootText());
        title.setText(text);
        title.getTooltip().setText(text);
        tree.setAccessibleText(text);
        String other = turnText(opposite(kind));
        flip.getTooltip().setText(other);
        flip.setAccessibleText(other);
    }

    /** The tree's title: what it lists of {@code name}. Pure. */
    static String titleText(MapCardLinks.Kind kind, String name) {
        return switch (kind) {
            case CALLEES -> tr("project.map.hierarchy.title.callees", name);
            case SUBTYPES -> tr("project.map.hierarchy.title.subtypes", name);
            case SUPERTYPES -> tr("project.map.hierarchy.title.supertypes", name);
            default -> tr("project.map.hierarchy.title.callers", name);
        };
    }

    /** What ⇄ does: shows {@code kind} instead. Pure. */
    static String turnText(MapCardLinks.Kind kind) {
        return switch (kind) {
            case CALLEES -> tr("project.map.hierarchy.turn.callees");
            case SUBTYPES -> tr("project.map.hierarchy.turn.subtypes");
            case SUPERTYPES -> tr("project.map.hierarchy.turn.supertypes");
            default -> tr("project.map.hierarchy.turn.callers");
        };
    }

    /** Opens the selected row's declaration as a card; false when the selection is no declaration. */
    boolean openSelected() {
        TreeItem<Row> item = tree.getSelectionModel().getSelectedItem();
        Row row = item == null ? null : item.getValue();
        MapCardLinks.Group group = row == null ? null : row.group();
        if (item == null || group == null) {
            return false;
        }
        TreeItem<Row> parent = item.getParent();
        String called = parent instanceof LevelItem level ? level.request.name() : name;
        opener.open(group, kind, called);
        return true;
    }

    private final class RowCell extends TreeCell<Row> {
        RowCell() {
            addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && !isEmpty()) {
                    openSelected();
                    event.consume();
                }
            });
        }

        @Override
        protected void updateItem(@Nullable Row row, boolean empty) {
            super.updateItem(row, empty);
            setText(empty || row == null ? null : row.text());
            pseudoClassStateChanged(MESSAGE, !empty && row != null && (row.message() || row.recursive()));
        }
    }

    // --- placement ------------------------------------------------------------------------------------------

    /** Sticks the tree on its card on the side with room, {@code preferLeft} first, and puts the keyboard in it. */
    void show(boolean preferLeft) {
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
        tree.requestFocus();
    }

    private void layout() {
        if (!shown || disposed) {
            return;
        }
        column.setVisible(card.isVisible());
        double[] at = boundsAt(side);
        column.relocate(at[0], at[1]);
        column.resize(WIDTH, Math.max(160, card.getHeight()));
        moved();
    }

    private void moved() {
        if (shown && !disposed) {
            onMoved.run();
        }
    }

    /** Where the tree is drawn on {@code which} side of its card, {@code {x, y, w, h}} in the host's pixels. */
    double[] boundsAt(Side which) {
        double scale = card.mapScale();
        double dx = switch (which) {
            case RIGHT -> card.getWidth() + GAP;
            case LEFT -> -(WIDTH + GAP);
            case OVER -> card.getWidth() - WIDTH;
        };
        return new double[] {
            card.getLayoutX() + dx * scale, card.getLayoutY(), WIDTH * scale, Math.max(160, card.getHeight()) * scale
        };
    }

    /** The side it stands on. */
    Side side() {
        return side;
    }

    /** The kind it lists now. */
    MapCardLinks.Kind kind() {
        return kind;
    }

    /** The tree, for tests. */
    TreeView<Row> tree() {
        return tree;
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
        generation++;
        card.layoutXProperty().removeListener(follow);
        card.layoutYProperty().removeListener(follow);
        card.widthProperty().removeListener(follow);
        card.heightProperty().removeListener(follow);
        card.mapScaleProperty().removeListener(follow);
        card.visibleProperty().removeListener(follow);
        host.getChildren().remove(column);
    }

    /** Whether it was taken off its card. */
    boolean disposed() {
        return disposed;
    }
}
