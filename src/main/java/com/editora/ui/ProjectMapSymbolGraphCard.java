package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.CubicCurve;

import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The Project Map's Symbol Graph card (Map code browser round 2): a declaration in the middle with what calls it on
 * the left, what it calls on the right, the types it extends or implements above and those that extend or implement it
 * below — each side one level of the language server's call or type hierarchy, as the hierarchy tree asks it ({@link
 * MapHierarchyTree.Asker}). Enter or a double-click on a declaration moves the graph onto it, so the reader walks the
 * code as in a code browser; Back (‹, Backspace) returns. A press on the middle, or Open as Card, opens the
 * declaration's card. One is open at a time.
 */
final class ProjectMapSymbolGraphCard extends MapFloatingCard {

    static final double DEFAULT_WIDTH = 720;
    static final double DEFAULT_HEIGHT = 520;
    static final double MIN_WIDTH = 420;
    static final double MIN_HEIGHT = 300;
    /** The most declarations drawn on a side; the rest are counted. */
    static final int MAX_NODES = 30;

    /** A side of the graph: the question asked of the declaration for it. */
    enum Side {
        CALLERS(MapCardLinks.Kind.CALLERS),
        CALLEES(MapCardLinks.Kind.CALLEES),
        SUPERTYPES(MapCardLinks.Kind.SUPERTYPES),
        SUBTYPES(MapCardLinks.Kind.SUBTYPES);

        final MapCardLinks.Kind kind;

        Side(MapCardLinks.Kind kind) {
            this.kind = kind;
        }
    }

    /** The declaration the graph is about: where it is (file, 0-based line and column), its name, its file's text. */
    record Subject(
            Path file,
            int line,
            int column,
            @Nullable String name,
            @Nullable String text) {
        MapCardLinks.Request request(Side side) {
            return new MapCardLinks.Request(side.kind, file, line, column, name, text);
        }
    }

    /** Opens a declaration's card: the 0-based {@code line} of {@code file}, {@code name} selected there. */
    interface Opener {
        void open(Path file, int line, @Nullable String name);
    }

    private final Button back = new Button("‹");
    private final Label summary = new Label();
    private final GraphPane graph = new GraphPane();
    private final ScrollPane scroll = new ScrollPane(graph);
    private final Label detail = new Label();

    private MapHierarchyTree.Asker asker = (request, answer) -> {};
    private Opener opener = (file, line, name) -> {};
    private @Nullable Subject subject;
    private final Deque<Subject> backStack = new ArrayDeque<>();
    private final Map<Side, MapHierarchyTree.Level> levels = new EnumMap<>(Side.class);
    /** Bumped by each {@link #show}: answers asked before are dropped. */
    private long generation;

    ProjectMapSymbolGraphCard() {
        super("project-map-graph-card", Icons.symbolGraph(), MIN_WIDTH, MIN_HEIGHT);
        back.getStyleClass().add("flat");
        back.setMnemonicParsing(false);
        Icons.name(back, tr("project.map.graph.backTip"));
        back.setOnAction(event -> goBack());
        summary.getStyleClass().add("project-map-graph-summary");
        summary.setWrapText(true);
        summary.setMnemonicParsing(false);
        summary.setMinHeight(Region.USE_PREF_SIZE);
        summary.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(summary, Priority.ALWAYS);
        HBox bar = UiZoom.spacing(new HBox(back, summary), 6);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("project-map-graph-bar");
        UiZoom.padding(bar, 4, 8, 4, 6);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.getStyleClass().add("project-map-graph-scroll");
        detail.getStyleClass().add("project-map-graph-detail");
        detail.setMnemonicParsing(false);
        UiZoom.padding(detail, 4, 8, 4, 8);
        detail.setMaxWidth(Double.MAX_VALUE);
        VBox body = new VBox(bar, scroll, detail);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        frame.setCenter(body);
        addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.BACK_SPACE && !event.isShortcutDown() && goBack()) {
                event.consume();
            }
        });
        render();
    }

    /** Where the graph's questions go: the Map's code links (the language server, or names). */
    void setAsker(MapHierarchyTree.Asker asker) {
        this.asker = asker;
    }

    /** What opening a declaration does: its card opens on the Map. */
    void setOpener(Opener opener) {
        this.opener = opener;
    }

    /** Moves the graph onto {@code next}, the declaration before it on its Back list, and asks its four sides. */
    void show(Subject next) {
        Subject was = subject;
        if (was != null && !samePlace(was, next)) {
            backStack.push(was);
        }
        moveTo(next);
    }

    private static boolean samePlace(Subject a, Subject b) {
        return a.file().equals(b.file()) && a.line() == b.line();
    }

    private void moveTo(Subject next) {
        subject = next;
        long asked = ++generation;
        levels.clear();
        render();
        for (Side side : Side.values()) {
            asker.ask(next.request(side), level -> {
                if (asked != generation) {
                    return; // the graph moved on
                }
                levels.put(side, level);
                Subject shown = subject;
                if (shown != null
                        && (shown.name() == null || shown.name().isBlank())
                        && level.name() != null
                        && !level.name().isBlank()) {
                    subject = new Subject(shown.file(), shown.line(), shown.column(), level.name(), shown.text());
                }
                render();
            });
        }
    }

    /** Goes back to the declaration the graph was on before; whether there was one. */
    boolean goBack() {
        Subject previous = backStack.poll();
        if (previous == null) {
            return false;
        }
        moveTo(previous);
        return true;
    }

    private void render() {
        back.setDisable(backStack.isEmpty());
        Subject at = subject;
        String name = at == null ? "" : nameOf(at);
        String heading = tr("project.map.graph.title", name);
        title.setText(heading);
        setAccessibleText(heading);
        summary.setText(at == null ? "" : summaryText(at, levels));
        graph.fill(at, levels);
    }

    private static String nameOf(Subject subject) {
        String name = subject.name();
        if (name != null && !name.isBlank()) {
            return name;
        }
        Path file = subject.file().getFileName();
        return tr(
                "project.map.graph.at", file == null ? subject.file().toString() : file.toString(), subject.line() + 1);
    }

    /**
     * What the line above the graph says: where the declaration is, and how many of each side were found — or still
     * asked, or why none (the server's note). Pure but for the language.
     */
    static String summaryText(Subject subject, Map<Side, MapHierarchyTree.Level> levels) {
        Path fileName = subject.file().getFileName();
        String where = tr(
                "project.map.graph.where",
                fileName == null ? subject.file().toString() : fileName.toString(),
                subject.line() + 1);
        if (levels.size() < Side.values().length) {
            return where + " " + tr("project.map.graph.asking");
        }
        int callers = count(levels, Side.CALLERS);
        int callees = count(levels, Side.CALLEES);
        int supertypes = count(levels, Side.SUPERTYPES);
        int subtypes = count(levels, Side.SUBTYPES);
        if (callers + callees + supertypes + subtypes == 0) {
            for (MapHierarchyTree.Level level : levels.values()) {
                if (level.note() != null && !level.note().isBlank()) {
                    return where + " " + level.note();
                }
            }
            return where + " " + tr("project.map.graph.none");
        }
        return where + " " + tr("project.map.graph.counts", callers, callees, supertypes, subtypes);
    }

    private static int count(Map<Side, MapHierarchyTree.Level> levels, Side side) {
        MapHierarchyTree.Level level = levels.get(side);
        return level == null ? 0 : level.groups().size();
    }

    // --- one declaration of a side --------------------------------------------------------------------------

    /** The declaration {@code group} found on {@code side} as a subject of its own, or null when it cannot be asked. */
    static @Nullable Subject subjectOf(MapCardLinks.Group group, Side side, MapCardLinks.Via via) {
        MapCardLinks.Request request = MapHierarchyTree.requestOf(group, side.kind, via);
        return request == null
                ? null
                : new Subject(request.file(), request.line(), request.column(), request.name(), null);
    }

    /** Whether {@code group} is the subject's own declaration (a recursive call). */
    private boolean isSubject(MapCardLinks.Group group) {
        Subject at = subject;
        return at != null && MapHierarchyTree.sameDeclaration(at.request(Side.CALLERS), group);
    }

    private void walk(MapCardLinks.Group group, Side side, MapCardLinks.Via via) {
        Subject next = subjectOf(group, side, via);
        if (next != null && !isSubject(group)) {
            show(next);
            focusContent();
        }
    }

    private void open(MapCardLinks.Group group) {
        ProjectMapStructure.Node node = group.declaration();
        int line = node != null ? node.symbol().line() : group.line();
        String name = node != null ? ProjectMapView.followName(node.symbol().label()) : group.mark();
        opener.open(group.file(), line, name);
    }

    private void openSubject() {
        Subject at = subject;
        if (at != null) {
            opener.open(at.file(), at.line(), at.name());
        }
    }

    /** A declaration of a side, as drawn. */
    private final class NodeButton extends Button {
        final MapCardLinks.Group group;
        final Side side;

        NodeButton(MapCardLinks.Group group, Side side, MapCardLinks.Via via) {
            this.group = group;
            this.side = side;
            setText(group.label());
            setMnemonicParsing(false);
            setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
            getStyleClass().add("project-map-graph-node");
            boolean self = isSubject(group);
            boolean walkable = !self && subjectOf(group, side, via) != null;
            if (self) {
                getStyleClass().add("project-map-graph-self");
            }
            if (group.count() > 1) {
                Label count = new Label("×" + group.count());
                count.getStyleClass().add("project-map-graph-count");
                setGraphic(count);
                setContentDisplay(ContentDisplay.RIGHT);
            }
            String where = MapHierarchyTree.rowText(group);
            String tip = self
                    ? tr("project.map.graph.selfTip", where)
                    : walkable ? tr("project.map.graph.nodeTip", where) : where;
            setTooltip(new Tooltip(tip));
            setAccessibleText(tip);
            setOnAction(event -> detail.setText(where));
            addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                if (event.getCode() == KeyCode.ENTER) {
                    walk(group, side, via);
                    event.consume();
                }
            });
            addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                    walk(group, side, via);
                    event.consume();
                }
            });
            MenuItem graphHere = new MenuItem(tr("project.map.graph.walk"));
            graphHere.setDisable(!walkable);
            graphHere.setOnAction(event -> walk(group, side, via));
            MenuItem card = new MenuItem(tr("project.map.graph.openCard"));
            card.setOnAction(event -> open(group));
            setContextMenu(new ContextMenu(graphHere, card));
        }
    }

    // --- the picture ----------------------------------------------------------------------------------------

    /**
     * The graph laid out by hand: callers in a column on the left, callees on the right, supertypes in rows above,
     * subtypes below, the declaration in the middle, each joined to it by a curve.
     */
    private final class GraphPane extends Region {
        private static final double PAD = 10;
        private static final double GAP = 44;
        private static final double ROW = 4;

        final Button middle = new Button();
        private final Group curves = new Group();
        /** A side's header, its declarations and their curves, and its last line (none, more, partial). */
        private record Parts(Label header, Label extra, List<NodeButton> nodes, List<CubicCurve> lines) {}

        private final Parts[] parts = new Parts[Side.values().length];

        private Parts at(Side side) {
            return parts[side.ordinal()];
        }

        GraphPane() {
            getStyleClass().add("project-map-graph");
            middle.getStyleClass().add("project-map-graph-subject");
            middle.setMnemonicParsing(false);
            middle.setWrapText(true);
            middle.setOnAction(event -> openSubject());
            middle.setTooltip(new Tooltip(tr("project.map.graph.subjectTip")));
            curves.setManaged(false);
            curves.setMouseTransparent(true);
            UiZoom.follow(this, this::requestLayout);
            for (Side side : Side.values()) {
                Label header = new Label();
                header.getStyleClass().add("project-map-graph-side");
                Label extra = new Label();
                extra.getStyleClass().add("project-map-graph-more");
                extra.setWrapText(true);
                parts[side.ordinal()] = new Parts(header, extra, new ArrayList<>(), new ArrayList<>());
            }
        }

        void fill(@Nullable Subject at, Map<Side, MapHierarchyTree.Level> known) {
            getChildren().clear();
            curves.getChildren().clear();
            if (at == null) {
                return;
            }
            middle.setText(nameOf(at));
            getChildren().addAll(curves, middle);
            for (Side side : Side.values()) {
                List<NodeButton> drawn = at(side).nodes();
                List<CubicCurve> joined = at(side).lines();
                drawn.clear();
                joined.clear();
                MapHierarchyTree.Level level = known.get(side);
                boolean typeSide = side == Side.SUPERTYPES || side == Side.SUBTYPES;
                if (typeSide && (level == null || level.groups().isEmpty())) {
                    continue; // a method has no types to draw: no empty row above and below it
                }
                Label header = at(side).header();
                header.setText(
                        sideHeader(side, level == null ? 0 : level.groups().size()));
                getChildren().add(header);
                Label extra = at(side).extra();
                if (level == null) {
                    extra.setText(tr("project.map.graph.asking"));
                    getChildren().add(extra);
                    continue;
                }
                List<MapCardLinks.Group> groups = level.groups();
                for (int i = 0; i < Math.min(groups.size(), MAX_NODES); i++) {
                    NodeButton button = new NodeButton(groups.get(i), side, level.via());
                    CubicCurve curve = new CubicCurve();
                    curve.getStyleClass().add("project-map-graph-edge");
                    double stroke =
                            1 + Math.min(2, Math.log(Math.max(1, groups.get(i).count())) / Math.log(2));
                    UiZoom.follow(curve, () -> curve.setStrokeWidth(UiZoom.px(stroke)));
                    drawn.add(button);
                    joined.add(curve);
                    curves.getChildren().add(curve);
                    getChildren().add(button);
                }
                String more = groups.isEmpty()
                        ? sideEmpty(side)
                        : groups.size() > MAX_NODES
                                ? tr("project.map.graph.more", groups.size() - MAX_NODES)
                                : level.partial() ? tr("project.map.links.partial") : "";
                if (!more.isEmpty()) {
                    extra.setText(more);
                    getChildren().add(extra);
                }
            }
            requestLayout();
        }

        private static String sideHeader(Side side, int count) {
            return switch (side) {
                case CALLERS -> tr("project.map.graph.side.callers", count);
                case CALLEES -> tr("project.map.graph.side.callees", count);
                case SUPERTYPES -> tr("project.map.graph.side.supertypes", count);
                case SUBTYPES -> tr("project.map.graph.side.subtypes", count);
            };
        }

        private static String sideEmpty(Side side) {
            return switch (side) {
                case CALLERS -> tr("project.map.graph.empty.callers");
                case CALLEES -> tr("project.map.graph.empty.callees");
                case SUPERTYPES, SUBTYPES -> ""; // a type side with nothing is not drawn
            };
        }

        List<NodeButton> drawn(Side side) {
            return at(side).nodes();
        }

        private double column(double width) {
            return Math.max(UiZoom.px(110), (width - 2 * UiZoom.px(PAD) - 2 * UiZoom.px(GAP)) / 3);
        }

        private boolean shows(javafx.scene.Node node) {
            return getChildren().contains(node);
        }

        /** The height of a left or right column: its header, its declarations, its last line. */
        private double columnHeight(Side side, double width) {
            if (!shows(at(side).header())) {
                return 0;
            }
            double h = at(side).header().prefHeight(width) + UiZoom.px(ROW);
            for (NodeButton button : at(side).nodes()) {
                h += button.prefHeight(width) + UiZoom.px(ROW);
            }
            if (shows(at(side).extra())) {
                h += at(side).extra().prefHeight(width);
            }
            return h;
        }

        /** The rows of a top or bottom side, as many declarations to a row as fit {@code width}. */
        private List<List<NodeButton>> rows(Side side, double width) {
            List<List<NodeButton>> rows = new ArrayList<>();
            List<NodeButton> row = new ArrayList<>();
            double used = 0;
            for (NodeButton button : at(side).nodes()) {
                double w = Math.min(width, button.prefWidth(-1));
                if (!row.isEmpty() && used + UiZoom.px(ROW) * 2 + w > width) {
                    rows.add(row);
                    row = new ArrayList<>();
                    used = 0;
                }
                row.add(button);
                used += w + UiZoom.px(ROW) * 2;
            }
            if (!row.isEmpty()) {
                rows.add(row);
            }
            return rows;
        }

        private double bandHeight(Side side, double width) {
            if (!shows(at(side).header())) {
                return 0;
            }
            double h = at(side).header().prefHeight(width) + UiZoom.px(ROW);
            for (List<NodeButton> row : rows(side, width)) {
                h += row.get(0).prefHeight(-1) + UiZoom.px(ROW);
            }
            if (shows(at(side).extra())) {
                h += at(side).extra().prefHeight(width);
            }
            return h + UiZoom.px(GAP) / 2;
        }

        @Override
        protected double computePrefWidth(double height) {
            return 3 * UiZoom.px(110) + 2 * UiZoom.px(GAP) + 2 * UiZoom.px(PAD);
        }

        @Override
        protected double computePrefHeight(double width) {
            double w = width > 0 ? width : computePrefWidth(-1);
            double column = column(w);
            double band = Math.max(
                    middle.prefHeight(column),
                    Math.max(columnHeight(Side.CALLERS, column), columnHeight(Side.CALLEES, column)));
            return 2 * UiZoom.px(PAD)
                    + bandHeight(Side.SUPERTYPES, w - 2 * UiZoom.px(PAD))
                    + band
                    + bandHeight(Side.SUBTYPES, w - 2 * UiZoom.px(PAD));
        }

        @Override
        protected void layoutChildren() {
            if (!shows(middle)) {
                return;
            }
            double width = getWidth();
            double inner = width - 2 * UiZoom.px(PAD);
            double column = column(width);
            double top = bandHeight(Side.SUPERTYPES, inner);
            double bottom = bandHeight(Side.SUBTYPES, inner);
            double bandTop = UiZoom.px(PAD) + top;
            double band = Math.max(
                    middle.prefHeight(column),
                    Math.max(
                            Math.max(columnHeight(Side.CALLERS, column), columnHeight(Side.CALLEES, column)),
                            getHeight() - 2 * UiZoom.px(PAD) - top - bottom));
            double middleHeight = middle.prefHeight(column);
            double middleWidth = Math.min(column, Math.max(middle.prefWidth(-1), UiZoom.px(90)));
            double middleX = UiZoom.px(PAD) + column + UiZoom.px(GAP) + (column - middleWidth) / 2;
            double middleY = bandTop + (band - middleHeight) / 2;
            middle.resizeRelocate(middleX, middleY, middleWidth, middleHeight);
            double midY = middleY + middleHeight / 2;
            double midX = middleX + middleWidth / 2;
            layoutColumn(Side.CALLERS, UiZoom.px(PAD), column, bandTop, band, true, middleX, midY);
            layoutColumn(
                    Side.CALLEES,
                    UiZoom.px(PAD) + 2 * (column + UiZoom.px(GAP)),
                    column,
                    bandTop,
                    band,
                    false,
                    middleX + middleWidth,
                    midY);
            layoutBand(Side.SUPERTYPES, UiZoom.px(PAD), inner, true, midX, middleY);
            layoutBand(Side.SUBTYPES, bandTop + band + UiZoom.px(GAP) / 2, inner, false, midX, middleY + middleHeight);
        }

        private void layoutColumn(
                Side side,
                double x,
                double column,
                double bandTop,
                double band,
                boolean left,
                double edge,
                double midY) {
            if (!shows(at(side).header())) {
                return;
            }
            double y = bandTop + Math.max(0, (band - columnHeight(side, column)) / 2);
            Label header = at(side).header();
            double headerHeight = header.prefHeight(column);
            header.resizeRelocate(x, y, column, headerHeight);
            header.setAlignment(left ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
            y += headerHeight + UiZoom.px(ROW);
            List<NodeButton> buttons = at(side).nodes();
            List<CubicCurve> joined = at(side).lines();
            for (int i = 0; i < buttons.size(); i++) {
                NodeButton button = buttons.get(i);
                double w = Math.min(column, button.prefWidth(-1));
                double h = button.prefHeight(column);
                double bx = left ? x + column - w : x;
                button.resizeRelocate(bx, y, w, h);
                double startX = left ? bx + w : bx;
                double startY = y + h / 2;
                double bend = Math.abs(edge - startX) / 2;
                CubicCurve curve = joined.get(i);
                curve.setStartX(startX);
                curve.setStartY(startY);
                curve.setControlX1(left ? startX + bend : startX - bend);
                curve.setControlY1(startY);
                curve.setControlX2(left ? edge - bend : edge + bend);
                curve.setControlY2(midY);
                curve.setEndX(edge);
                curve.setEndY(midY);
                y += h + UiZoom.px(ROW);
            }
            Label extra = at(side).extra();
            if (shows(extra)) {
                extra.resizeRelocate(x, y, column, extra.prefHeight(column));
                extra.setAlignment(left ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
            }
        }

        private void layoutBand(Side side, double y0, double inner, boolean above, double midX, double edgeY) {
            if (!shows(at(side).header())) {
                return;
            }
            Label header = at(side).header();
            double headerHeight = header.prefHeight(inner);
            double y = y0;
            List<List<NodeButton>> rows = rows(side, inner);
            header.resizeRelocate(UiZoom.px(PAD), y, inner, headerHeight);
            y += headerHeight + UiZoom.px(ROW);
            header.setAlignment(Pos.CENTER);
            List<NodeButton> buttons = at(side).nodes();
            List<CubicCurve> joined = at(side).lines();
            for (List<NodeButton> row : rows) {
                double total = 0;
                for (NodeButton button : row) {
                    total += Math.min(inner, button.prefWidth(-1)) + UiZoom.px(ROW) * 2;
                }
                double x = UiZoom.px(PAD) + Math.max(0, (inner - total) / 2) + UiZoom.px(ROW);
                double h = row.get(0).prefHeight(-1);
                for (NodeButton button : row) {
                    double w = Math.min(inner, button.prefWidth(-1));
                    button.resizeRelocate(x, y, w, h);
                    CubicCurve curve = joined.get(buttons.indexOf(button));
                    double startX = x + w / 2;
                    double startY = above ? y + h : y;
                    double bend = Math.abs(edgeY - startY) / 2;
                    curve.setStartX(startX);
                    curve.setStartY(startY);
                    curve.setControlX1(startX);
                    curve.setControlY1(above ? startY + bend : startY - bend);
                    curve.setControlX2(midX);
                    curve.setControlY2(above ? edgeY - bend : edgeY + bend);
                    curve.setEndX(midX);
                    curve.setEndY(edgeY);
                    x += w + UiZoom.px(ROW) * 2;
                }
                y += h + UiZoom.px(ROW);
            }
            Label extra = at(side).extra();
            if (shows(extra)) {
                extra.resizeRelocate(UiZoom.px(PAD), y, inner, extra.prefHeight(inner));
                extra.setAlignment(Pos.CENTER);
            }
        }
    }

    // --- for the Map and tests ------------------------------------------------------------------------------

    @Override
    void focusContent() {
        graph.middle.requestFocus();
    }

    @Nullable
    Subject subject() {
        return subject;
    }

    String summaryText() {
        return summary.getText();
    }

    String detailText() {
        return detail.getText();
    }

    /** The labels of a side's declarations, as drawn. For tests. */
    List<String> drawn(Side side) {
        return graph.drawn(side).stream().map(button -> button.group.label()).toList();
    }

    /** Presses the {@code index}th declaration of {@code side}, as a click does. For tests. */
    void press(Side side, int index) {
        graph.drawn(side).get(index).fire();
    }

    /** Enter on the {@code index}th declaration of {@code side}: the graph moves onto it. For tests. */
    void enter(Side side, int index) {
        NodeButton button = graph.drawn(side).get(index);
        button.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
    }

    /** A double-click on the {@code index}th declaration of {@code side}. For tests. */
    void doubleClick(Side side, int index) {
        graph.drawn(side)
                .get(index)
                .fireEvent(new MouseEvent(
                        MouseEvent.MOUSE_CLICKED,
                        1,
                        1,
                        1,
                        1,
                        MouseButton.PRIMARY,
                        2,
                        false,
                        false,
                        false,
                        false,
                        true,
                        false,
                        false,
                        false,
                        false,
                        false,
                        null));
    }

    /** The menu of the {@code index}th declaration of {@code side}. For tests. */
    ContextMenu menuOf(Side side, int index) {
        return graph.drawn(side).get(index).getContextMenu();
    }

    /** Whether the {@code index}th declaration of {@code side} is the subject's own (a recursive call). For tests. */
    boolean isSelf(Side side, int index) {
        return graph.drawn(side).get(index).getStyleClass().contains("project-map-graph-self");
    }

    /** Presses the declaration in the middle. For tests. */
    void pressMiddle() {
        graph.middle.fire();
    }
}
