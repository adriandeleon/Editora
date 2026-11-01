package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.beans.value.ChangeListener;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
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
 * The Structure column of one Project Map file card: the file's declarations, as the Map's own Structure column
 * lists them, stuck beside the card. It belongs to the card — it moves, resizes and zooms with it and closes with
 * it — so it works wherever the file's row is, filtered or scrolled off the map included. A declaration clicked
 * (or Enter on it) opens its card, as a row of the Map's Structure column does. The outline is the Map's
 * ({@link ProjectMapStructure}); nothing is read here.
 */
final class ProjectMapCardStructure {

    /** The column fits its longest row between these widths; a longer one is cut at the far edge. */
    static final double MIN_WIDTH = 240;

    static final double MAX_WIDTH = 560;
    /** Between the card's edge and the column. */
    static final double GAP = 8;

    /** Which side of the card the column goes on. */
    enum Side {
        /** Beside the card's right edge, outside it. */
        RIGHT,
        /** Beside its left edge, outside it. */
        LEFT,
        /** Neither side has room in the panel: on the card itself, against its right edge. */
        OVER
    }

    /** A row of the column: a declaration, or a line that says why there are none or how many were left out. */
    record Row(ProjectMapStructure.@Nullable Node node, String message) {
        static Row of(ProjectMapStructure.Node node) {
            return new Row(node, "");
        }

        static Row message(String text) {
            return new Row(null, text);
        }
    }

    private final ProjectMapPreview card;
    private final Pane host;
    private final Consumer<ProjectMapStructure.Node> onPreview;
    private final Function<ProjectMapStructure.Node, @Nullable ContextMenu> menuFor;
    private final Runnable onActivate;
    private final Runnable onMoved;
    private final Runnable onResized;

    /** The column: its tint and border over the card's background, the header and the list on top. */
    private final StackPane column = new StackPane();

    private final Label title = new Label(tr("project.map.cardStructure.title"), Icons.structure());
    private final TreeView<Row> tree = new TreeView<>(new TreeItem<>(Row.message("")));
    private final Scale zoom = new Scale(1, 1, 0, 0);
    private final ChangeListener<Object> follow = (obs, old, now) -> layout();
    /** Declarations whose members are listed, by path: kept across outlines, as the Map keeps its open columns. */
    private final Set<Path> expanded = new HashSet<>();
    /** A lone top-level declaration with members was opened for the user (once, as the Map's column does). */
    private boolean autoExpanded;
    /** Measures labels in the rows' font, for the column's width. */
    private final javafx.scene.text.Text measure = new javafx.scene.text.Text();
    /** Bumped when the card's colours change: every row takes its label's colours again. */
    private long styles;

    private double width = MIN_WIDTH;
    private ProjectMapStructure.View view = ProjectMapStructure.View.LOADING;
    private boolean movePending;

    private boolean shown;
    private Side side = Side.RIGHT;

    /**
     * @param onPreview opens (or brings up) the card of a declaration
     * @param menuFor a declaration's menu, as its row's in the Map's Structure column
     * @param onActivate the column was used: it is use of the card
     * @param onMoved the column moved, showed or hid, or its list scrolled (connectors must follow)
     * @param onResized the column took another width (what stands beside the card must make room)
     */
    ProjectMapCardStructure(
            ProjectMapPreview card,
            Pane host,
            Consumer<ProjectMapStructure.Node> onPreview,
            Function<ProjectMapStructure.Node, @Nullable ContextMenu> menuFor,
            Runnable onActivate,
            Runnable onMoved,
            Runnable onResized) {
        this.card = card;
        this.host = host;
        this.onPreview = onPreview;
        this.menuFor = menuFor;
        this.onActivate = onActivate;
        this.onMoved = onMoved;
        this.onResized = onResized;

        column.getStyleClass().add("project-map-card-structure");
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
        close.setTooltip(new Tooltip(tr("project.map.preview.hideStructure")));
        close.setAccessibleText(tr("project.map.preview.hideStructure"));
        close.setOnAction(event -> card.setStructureShown(false));
        HBox header = new HBox(6, title, close);
        header.getStyleClass().add("project-map-card-structure-header");
        header.setAlignment(Pos.CENTER_LEFT);

        tree.setShowRoot(false);
        tree.getStyleClass().add("project-map-card-structure-tree");
        tree.setCellFactory(view -> new RowCell());
        relabel();
        tree.setAccessibleHelp(tr("project.map.cardStructure.help"));
        VBox.setVgrow(tree, Priority.ALWAYS);
        // The Map's Structure columns lean toward violet; so does this one, over an opaque background.
        Region tint = new Region();
        tint.getStyleClass().add("project-map-card-structure-tint");
        tint.setMouseTransparent(true);
        Region border = new Region();
        border.getStyleClass().add("project-map-card-structure-border");
        border.setMouseTransparent(true);
        VBox content = new VBox(header, tree);
        measure.getStyleClass().add("project-map-card-structure-measure");
        measure.setManaged(false);
        measure.setVisible(false);
        column.getChildren().setAll(tint, content, border, measure);
        // A row scrolled or expanded moves under its card's connector, which is drawn after the list's layout.
        tree.expandedItemCountProperty().addListener((obs, old, now) -> moved());
        tree.skinProperty().addListener((obs, old, skin) -> {
            if (tree.lookup(".virtual-flow") instanceof javafx.scene.control.skin.VirtualFlow<?> flow) {
                flow.positionProperty().addListener((o, was, now) -> moved());
            }
        });

        column.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            raise();
            onActivate.run();
        });
        column.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                card.setStructureShown(false);
                card.focusContent();
                event.consume();
            }
        });
        tree.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER && !event.isShortcutDown() && !event.isAltDown()) {
                ProjectMapStructure.Node node = selectedNode();
                if (node != null) {
                    onPreview.accept(node);
                    event.consume();
                }
            }
        });
        // The pointer's menu and the Menu key's (Shift+F10) both reach the tree: the selected declaration's menu.
        tree.setOnContextMenuRequested(event -> {
            ProjectMapStructure.Node node = selectedNode();
            ContextMenu menu = node == null ? null : menuFor.apply(node);
            if (menu != null) {
                menu.show(tree, event.getScreenX(), event.getScreenY());
            }
            event.consume();
        });
    }

    /** Names the card's file again (it was renamed): the title's tooltip and the list's spoken name. */
    void relabel() {
        Path file = card.path();
        Path fileName = file == null ? null : file.getFileName();
        String text = tr("project.map.column.structure", fileName == null ? "" : fileName.toString());
        title.getTooltip().setText(text);
        tree.setAccessibleText(text);
    }

    /**
     * The side of a card, drawn from {@code cardLeft} to {@code cardRight} in a panel {@code panelWidth} wide, that
     * has room for a column {@code width} wide: {@code preferLeft} picks which side is tried first; on the card when
     * neither has room. Pure.
     */
    static Side side(double cardLeft, double cardRight, double width, double panelWidth, boolean preferLeft) {
        boolean right = panelWidth - cardRight >= width;
        boolean left = cardLeft >= width;
        if (preferLeft ? left : right) {
            return preferLeft ? Side.LEFT : Side.RIGHT;
        }
        if (preferLeft ? right : left) {
            return preferLeft ? Side.RIGHT : Side.LEFT;
        }
        return Side.OVER;
    }

    /**
     * The rows for {@code nodes}, the top level of {@code view} or a declaration's members: one per declaration, at
     * most {@link ProjectMapStructure#MAX_ROWS} and then one saying how many were left out. The top level says why
     * it has none, and that the outline stops at the read cap when it does. Pure.
     */
    static List<Row> rows(ProjectMapStructure.View view, List<ProjectMapStructure.Node> nodes) {
        boolean top = nodes == view.roots();
        if (top
                && (view.problem() != ProjectMapStructure.Problem.NONE
                        || view.roots().isEmpty())) {
            return List.of(Row.message(
                    switch (view.problem()) {
                        case LOADING -> tr("project.map.structure.loading");
                        case UNREADABLE -> tr("project.map.structure.unreadable");
                        case NONE -> tr("project.map.structure.none");
                    }));
        }
        int shown = Math.min(nodes.size(), ProjectMapStructure.MAX_ROWS);
        List<Row> rows = new ArrayList<>(shown + 2);
        for (ProjectMapStructure.Node node : nodes.subList(0, shown)) {
            rows.add(Row.of(node));
        }
        if (nodes.size() > shown) {
            rows.add(Row.message(tr("project.map.structure.more", nodes.size() - shown)));
        }
        if (top && view.partial()) {
            rows.add(Row.message(tr("project.map.structure.partial")));
        }
        return rows;
    }

    boolean shown() {
        return shown;
    }

    /** The side the column went on when it was shown. */
    Side side() {
        return side;
    }

    /**
     * What the column takes beside the card on {@code which} side, in unzoomed units: its width and gap there,
     * nothing on the other side or while it stands over the card. Sticky notes go past it.
     */
    double reserved(Side which) {
        return shown && side == which && which != Side.OVER ? width + GAP : 0;
    }

    /** The column's width, unzoomed: what its longest row needs, within {@link #MIN_WIDTH}–{@link #MAX_WIDTH}. */
    double width() {
        return width;
    }

    /**
     * The width a row needs: its indent and disclosure arrow at {@code depth}, the kind's glyph, the label
     * {@code labelWidth} wide and the line number {@code lineWidth} wide, and the list's own padding and scroll bar,
     * within {@link #MIN_WIDTH}–{@link #MAX_WIDTH}. Pure.
     */
    static double fit(int depth, double labelWidth, double lineWidth) {
        // Indent 10 a level, arrow 18, glyph 16 + gap 5, gap 5, cell padding 6, list padding 4, scroll bar 14,
        // border 2, and room for a bold keyword run the measure did not see.
        double needed = depth * 10 + 18 + 21 + labelWidth * 1.06 + 5 + lineWidth + 6 + 4 + 14 + 2 + 8;
        return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, Math.ceil(needed)));
    }

    /** The width the column's longest row needs, measured in the rows' font; members of closed rows count too. */
    private double fittedWidth() {
        if (column.getScene() == null) {
            return width;
        }
        measure.applyCss();
        double[] widest = {MIN_WIDTH};
        measureRows(view.roots(), 0, widest, new int[] {0});
        return widest[0];
    }

    private void measureRows(List<ProjectMapStructure.Node> nodes, int depth, double[] widest, int[] budget) {
        for (ProjectMapStructure.Node node : nodes) {
            if (++budget[0] > ProjectMapStructure.MAX_ROWS || widest[0] >= MAX_WIDTH) {
                return; // enough measured: a huge outline must not cost a pass over every row
            }
            ProjectMapModel.Symbol symbol = node.symbol();
            measure.setText(symbol.label());
            double label = measure.getLayoutBounds().getWidth();
            measure.setText(tr("structure.line", symbol.line() + 1));
            double line = measure.getLayoutBounds().getWidth() * 10 / 12; // the line number is 10px, the label 12px
            widest[0] = Math.max(widest[0], fit(depth, label, line));
            measureRows(node.children(), depth + 1, widest, budget);
        }
    }

    /** Takes the width the rows need, and tells the card's side neighbours when it changed. */
    private void refit() {
        double fitted = fittedWidth();
        if (Math.abs(fitted - width) < 0.5) {
            return;
        }
        width = fitted;
        layout();
        onResized.run();
    }

    /** The card's text or colours changed: each row takes its label's colours from it again. */
    void restyle() {
        styles++;
        tree.refresh();
    }

    /**
     * The runs of a declaration's label, coloured as the card colours its declaration line — a whole-file card's
     * text is the file's, line for line. A declaration on a line the card does not colour (past the highlighting
     * cap) takes its kind's colour, as the Structure window does.
     */
    private List<StructureNode.SyntaxRun> runsOf(ProjectMapModel.Symbol symbol) {
        int line = card.range() == null ? symbol.line() : -1;
        return StructurePanel.labelRuns(card.editor(), line, symbol.label(), symbol.kind());
    }

    private void moved() {
        if (!shown || movePending) {
            return;
        }
        movePending = true;
        javafx.application.Platform.runLater(() -> {
            movePending = false;
            if (shown) {
                onMoved.run();
            }
        });
    }

    /** Sticks the column on the card, on the side with room — {@code preferLeft} first. */
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
        width = fittedWidth();
        side = side(left, right, (width + GAP) * scale, host.getWidth(), preferLeft);
        layout();
    }

    /** Lists {@code view}'s declarations, keeping what was expanded and selected where it still is. */
    void update(ProjectMapStructure.View view) {
        this.view = view;
        if (!autoExpanded
                && view.problem() == ProjectMapStructure.Problem.NONE
                && !view.roots().isEmpty()) {
            autoExpanded = true;
            if (view.roots().size() == 1 && view.roots().getFirst().symbol().branches()) {
                expanded.add(view.roots().getFirst().path()); // most Java files: one class, its members are the point
            }
        }
        ProjectMapStructure.Node was = selectedNode();
        Path selected = was == null ? null : was.path();
        sync(tree.getRoot(), view, view.roots());
        if (selected != null) {
            TreeItem<Row> item = find(tree.getRoot(), selected);
            if (item != null && tree.getSelectionModel().getSelectedItem() != item) {
                tree.getSelectionModel().select(item);
            }
        }
        if (shown) {
            refit();
        }
    }

    /**
     * Brings {@code parent}'s items in line with {@code nodes}: items whose path is unchanged take the new
     * declaration in place, so an edit that only moves lines neither scrolls the list nor loses its selection.
     */
    private void sync(TreeItem<Row> parent, ProjectMapStructure.View view, List<ProjectMapStructure.Node> nodes) {
        List<Row> rows = rows(view, nodes);
        javafx.collections.ObservableList<TreeItem<Row>> items = parent.getChildren();
        boolean same = items.size() == rows.size();
        for (int i = 0; same && i < rows.size(); i++) {
            same = samePlace(items.get(i).getValue(), rows.get(i));
        }
        if (!same) {
            List<TreeItem<Row>> built = new ArrayList<>(rows.size());
            for (Row row : rows) {
                built.add(item(row));
            }
            items.setAll(built);
        }
        for (int i = 0; i < rows.size(); i++) {
            TreeItem<Row> item = items.get(i);
            Row row = rows.get(i);
            if (!row.equals(item.getValue())) {
                item.setValue(row);
            }
            ProjectMapStructure.Node node = row.node();
            if (node != null && node.symbol().branches()) {
                sync(item, view, node.children());
                item.setExpanded(expanded.contains(node.path()));
            } else if (!item.getChildren().isEmpty()) {
                item.getChildren().clear();
            }
        }
    }

    private static boolean samePlace(Row a, Row b) {
        ProjectMapStructure.Node x = a.node();
        ProjectMapStructure.Node y = b.node();
        return x == null
                ? y == null && a.message().equals(b.message())
                : y != null && x.path().equals(y.path());
    }

    private TreeItem<Row> item(Row row) {
        TreeItem<Row> item = new TreeItem<>(row);
        item.expandedProperty().addListener((obs, was, now) -> {
            ProjectMapStructure.Node node = item.getValue().node();
            if (node == null) {
                return;
            }
            if (now) {
                expanded.add(node.path());
            } else {
                expanded.remove(node.path());
            }
        });
        return item;
    }

    private static @Nullable TreeItem<Row> find(TreeItem<Row> parent, Path path) {
        for (TreeItem<Row> item : parent.getChildren()) {
            ProjectMapStructure.Node node = item.getValue().node();
            if (node != null && node.path().equals(path)) {
                return item;
            }
            if (node != null && path.startsWith(node.path())) {
                TreeItem<Row> below = find(item, path);
                if (below != null) {
                    return below;
                }
            }
        }
        return null;
    }

    private ProjectMapStructure.@Nullable Node selectedNode() {
        TreeItem<Row> item = tree.getSelectionModel().getSelectedItem();
        return item == null ? null : item.getValue().node();
    }

    /** Puts the column above its card, which was just raised above the others. */
    void raise() {
        column.toFront();
    }

    /** The column as drawn, in the host's pixels: {@code x, y, width, height}; null while it is not in sight. */
    double @Nullable [] bounds() {
        if (!shown || !column.isVisible()) {
            return null;
        }
        double scale = card.mapScale(); // its own box: the bounds in its parent take its shadow in too
        return new double[] {
            column.getLayoutX(), column.getLayoutY(), column.getWidth() * scale, column.getHeight() * scale
        };
    }

    /**
     * The row of the declaration at {@code path} as drawn, in the host's pixels, when it is scrolled into sight;
     * else null. A declaration card opened from the column is placed beside it.
     */
    double @Nullable [] rowBounds(Path path) {
        if (!shown || !column.isVisible()) {
            return null;
        }
        for (Node node : tree.lookupAll(".tree-cell")) {
            Row row = node instanceof RowCell cell && !cell.isEmpty() ? cell.getItem() : null;
            ProjectMapStructure.Node shownNode = row == null ? null : row.node();
            if (shownNode != null && shownNode.path().equals(path) && node.isVisible()) {
                RowCell cell = (RowCell) node;
                Bounds drawn = host.sceneToLocal(cell.localToScene(cell.getBoundsInLocal()));
                return new double[] {drawn.getMinX(), drawn.getMinY(), drawn.getWidth(), drawn.getHeight()};
            }
        }
        return null;
    }

    /** Whether the keyboard is in the column. */
    boolean contains(@Nullable Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current == column) {
                return true;
            }
        }
        return false;
    }

    /** Puts the keyboard on the list. */
    void focus() {
        tree.requestFocus();
        if (tree.getSelectionModel().isEmpty() && !tree.getRoot().getChildren().isEmpty()) {
            tree.getSelectionModel().selectFirst();
        }
    }

    /** The rows listed now, depth-first through what is expanded. For tests. */
    List<Row> visibleRows() {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < tree.getExpandedItemCount(); i++) {
            TreeItem<Row> item = tree.getTreeItem(i);
            if (item != null) {
                rows.add(item.getValue());
            }
        }
        return rows;
    }

    /** The list itself. For tests. */
    TreeView<Row> tree() {
        return tree;
    }

    Region node() {
        return column;
    }

    void dispose() {
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
        zoom.xProperty().unbind();
        zoom.yProperty().unbind();
        host.getChildren().remove(column);
        onMoved.run();
    }

    /** Follows the card: beside its edge on the column's side, as tall as the card, at the card's zoom. */
    private void layout() {
        if (!shown) {
            return;
        }
        double scale = card.mapScale();
        double dx = switch (side) {
            case RIGHT -> card.getWidth() + GAP;
            case LEFT -> -(width + GAP);
            case OVER -> card.getWidth() - width;
        };
        column.resize(width, card.getHeight());
        column.relocate(card.getLayoutX() + dx * scale, card.getLayoutY());
        column.setVisible(card.isVisible());
        onMoved.run();
    }

    /** A declaration's row: its kind's glyph, its label and its line, as the Map's Structure column draws it. */
    private final class RowCell extends TreeCell<Row> {
        /** The label's runs, coloured as the card colours them; cut at the far edge when the column is too narrow. */
        private final HBox name = new HBox();

        private final Label line = new Label();
        private final HBox box = new HBox(5, name, line);
        private final javafx.scene.shape.Rectangle nameClip = new javafx.scene.shape.Rectangle();
        private @Nullable String kind;
        private boolean hasKind;
        /** What the runs were built for: the row and the card's colours then. */
        private @Nullable Row shownRow;

        private long shownStyles = -1;

        private final Tooltip tip = new Tooltip();

        RowCell() {
            getStyleClass().add("project-map-card-structure-row");
            // As wide as the list, never wider: a long signature is cut short, its line still in sight.
            setPrefWidth(0);
            name.getStyleClass().add("project-map-card-structure-name");
            name.setMinWidth(0);
            name.setAlignment(Pos.CENTER_LEFT);
            nameClip.widthProperty().bind(name.widthProperty());
            nameClip.heightProperty().bind(name.heightProperty());
            name.setClip(nameClip); // the line number follows the name, as in the Structure window
            line.getStyleClass().add("project-map-card-structure-line");
            line.setMinWidth(Region.USE_PREF_SIZE);
            box.setAlignment(Pos.CENTER_LEFT);
            addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
                Row row = isEmpty() ? null : getItem();
                ProjectMapStructure.Node node = row == null ? null : row.node();
                if (event.getButton() != MouseButton.PRIMARY
                        || event.getClickCount() != 1
                        || !event.isStillSincePress()
                        || node == null
                        || onDisclosure(event)) {
                    return;
                }
                onPreview.accept(node);
                event.consume();
            });
        }

        private boolean onDisclosure(MouseEvent event) {
            Node disclosure = getDisclosureNode();
            if (disclosure == null || !disclosure.isVisible()) {
                return false;
            }
            for (Node current = event.getPickResult().getIntersectedNode();
                    current != null;
                    current = current.getParent()) {
                if (current == disclosure) {
                    return true;
                }
                if (current == this) {
                    return false;
                }
            }
            return false;
        }

        @Override
        protected void updateItem(Row item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                pseudoClassStateChanged(MESSAGE, false);
                setTooltip(null);
                shownRow = null;
                return;
            }
            ProjectMapStructure.Node node = item.node();
            pseudoClassStateChanged(MESSAGE, node == null);
            if (node == null) {
                setText(item.message());
                setGraphic(null);
                setTooltip(null);
                shownRow = null;
                return;
            }
            setText(null);
            ProjectMapModel.Symbol symbol = node.symbol();
            if (!hasKind || !java.util.Objects.equals(kind, symbol.kind())) {
                hasKind = true;
                kind = symbol.kind();
                Node icon = StructureIcons.forKind(kind);
                icon.getStyleClass().add("project-map-card-structure-icon");
                box.getChildren().setAll(icon, name, line);
            }
            if (item != shownRow || shownStyles != styles) {
                // Rebuilt only when the row or the card's colours changed: a selection change runs this again
                // from layout, after the CSS pass, and fresh runs would paint unstyled for a frame.
                shownRow = item;
                shownStyles = styles;
                List<javafx.scene.text.Text> runs = new ArrayList<>();
                for (StructureNode.SyntaxRun run : runsOf(symbol)) {
                    javafx.scene.text.Text text = new javafx.scene.text.Text(run.text());
                    // `text` + the token classes are what syntax.css and every editor theme colour, as in an editor.
                    text.getStyleClass()
                            .addAll(
                                    run.styles().isEmpty()
                                            ? List.of("project-map-card-structure-plain")
                                            : List.of("project-map-card-structure-run", "text"));
                    text.getStyleClass().addAll(run.styles());
                    runs.add(text);
                }
                name.getChildren().setAll(runs);
                name.applyCss();
            }
            line.setText(tr("structure.line", symbol.line() + 1));
            tip.setText(symbol.label());
            setTooltip(tip);
            setGraphic(box);
        }
    }

    private static final javafx.css.PseudoClass MESSAGE = javafx.css.PseudoClass.getPseudoClass("message");
}
