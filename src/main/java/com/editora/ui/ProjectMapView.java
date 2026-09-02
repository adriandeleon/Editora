package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.StringConverter;

import com.editora.git.GitFileStatus;

import static com.editora.i18n.Messages.tr;

/**
 * Read-only spatial navigator for a project. Native JavaFX controls own filtering and zoom; the hierarchy
 * itself is drawn on a {@link Canvas}. This intentionally offers no rename/delete/move affordances — the
 * existing Project tree remains the file-management surface.
 */
final class ProjectMapView extends VBox {

    private final Predicate<Path> isOpen;
    private final Predicate<Path> isModified;
    private final Consumer<Path> onOpenFile;
    private final ToggleButton openFilter = filterButton("project.map.filter.open");
    private final ToggleButton modifiedFilter = filterButton("project.map.filter.modified");
    private final ToggleButton gitFilter = filterButton("project.map.filter.gitChanged");
    private final ComboBox<ProjectMapModel.TypeFilter> typeFilter = new ComboBox<>();
    private final MapSurface surface = new MapSurface();
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "project-map-loader");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong generation = new AtomicLong();
    private final Set<Path> expanded = new HashSet<>();

    private Path root;
    private boolean showHidden;
    private boolean disposed;
    private String query = "";
    private Map<Path, GitFileStatus> gitStatus = Map.of();
    private Set<Path> gitChangedDirectories = Set.of();
    private Runnable onExpandedChanged = () -> {};

    ProjectMapView(Consumer<Path> onOpenFile, Predicate<Path> isOpen, Predicate<Path> isModified) {
        this.onOpenFile = onOpenFile;
        this.isOpen = isOpen == null ? path -> false : isOpen;
        this.isModified = isModified == null ? path -> false : isModified;
        getStyleClass().add("project-map-view");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(4);

        getChildren().addAll(buildFilters(), buildCanvasHost());
        VBox.setVgrow(getChildren().get(1), Priority.ALWAYS);
        surface.setOnActivate(this::activate);
        surface.setStatusSuppliers(this.isOpen, this.isModified);
        updateFilters();
    }

    private FlowPane buildFilters() {
        Label heading = new Label(tr("project.map.filters"));
        heading.getStyleClass().add("project-map-filter-heading");

        typeFilter.getItems().setAll(ProjectMapModel.TypeFilter.values());
        typeFilter.setValue(ProjectMapModel.TypeFilter.ALL);
        typeFilter.getStyleClass().add("project-map-type-filter");
        typeFilter.setConverter(new StringConverter<>() {
            @Override
            public String toString(ProjectMapModel.TypeFilter value) {
                return typeName(value);
            }

            @Override
            public ProjectMapModel.TypeFilter fromString(String value) {
                return ProjectMapModel.TypeFilter.ALL;
            }
        });
        typeFilter.setButtonCell(typeCell());
        typeFilter.setCellFactory(list -> typeCell());

        Button clear = new Button(tr("project.map.filter.clear"));
        clear.getStyleClass().add("project-map-filter-clear");
        clear.setOnAction(event -> {
            openFilter.setSelected(false);
            modifiedFilter.setSelected(false);
            gitFilter.setSelected(false);
            typeFilter.setValue(ProjectMapModel.TypeFilter.ALL);
            updateFilters();
        });

        for (ToggleButton button : List.of(openFilter, modifiedFilter, gitFilter)) {
            button.setOnAction(event -> updateFilters());
        }
        typeFilter.setOnAction(event -> updateFilters());

        FlowPane filters = new FlowPane(6, 4, heading, openFilter, modifiedFilter, gitFilter, typeFilter, clear);
        filters.getStyleClass().add("project-map-filters");
        filters.setAlignment(Pos.CENTER_LEFT);
        return filters;
    }

    private StackPane buildCanvasHost() {
        Button zoomOut = new Button("−");
        Label zoom = new Label("100%");
        Button zoomIn = new Button("+");
        Button reset = new Button(tr("project.map.zoom.reset"));
        for (var node : List.of(zoomOut, zoom, zoomIn, reset)) {
            node.getStyleClass().add("project-map-zoom-control");
        }
        zoomOut.setTooltip(new Tooltip(tr("project.map.zoom.out")));
        zoomIn.setTooltip(new Tooltip(tr("project.map.zoom.in")));
        reset.setTooltip(new Tooltip(tr("project.map.zoom.reset")));
        zoomOut.setOnAction(event -> {
            surface.zoomBy(0.9);
            zoom.setText(surface.zoomPercent());
        });
        zoomIn.setOnAction(event -> {
            surface.zoomBy(1.1);
            zoom.setText(surface.zoomPercent());
        });
        reset.setOnAction(event -> {
            surface.resetViewport();
            zoom.setText(surface.zoomPercent());
        });
        surface.setOnZoomChanged(() -> zoom.setText(surface.zoomPercent()));

        HBox zoomBar = new HBox(2, zoomOut, zoom, zoomIn, reset);
        zoomBar.getStyleClass().add("project-map-zoom");
        zoomBar.setAlignment(Pos.CENTER);
        zoomBar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane host = new StackPane(surface, zoomBar);
        host.getStyleClass().add("project-map-host");
        StackPane.setAlignment(zoomBar, Pos.BOTTOM_LEFT);
        StackPane.setMargin(zoomBar, new Insets(8));
        return host;
    }

    private static ToggleButton filterButton(String key) {
        ToggleButton button = new ToggleButton(tr(key));
        button.getStyleClass().add("project-map-filter-chip");
        return button;
    }

    private static ListCell<ProjectMapModel.TypeFilter> typeCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(ProjectMapModel.TypeFilter item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : typeName(item));
            }
        };
    }

    private static String typeName(ProjectMapModel.TypeFilter type) {
        if (type == null) {
            type = ProjectMapModel.TypeFilter.ALL;
        }
        return tr("project.map.type." + type.name().toLowerCase(java.util.Locale.ROOT));
    }

    void setRoot(Path root) {
        Path normalized = ProjectMapModel.normalize(root);
        if (java.util.Objects.equals(this.root, normalized)) {
            return;
        }
        this.root = normalized;
        expanded.clear();
        if (normalized != null) {
            expanded.add(normalized);
        }
        surface.setSelected(normalized);
        reload();
    }

    void setShowHidden(boolean showHidden) {
        if (this.showHidden != showHidden) {
            this.showHidden = showHidden;
            reload();
        }
    }

    void setQuery(String query) {
        String value = query == null ? "" : query;
        if (!this.query.equals(value)) {
            this.query = value;
            updateFilters();
        }
    }

    void setGitStatus(Map<Path, GitFileStatus> status) {
        Map<Path, GitFileStatus> normalized = new HashMap<>();
        if (status != null) {
            status.forEach((path, value) -> normalized.put(ProjectMapModel.normalize(path), value));
        }
        gitStatus = Map.copyOf(normalized);
        Set<Path> directories = new HashSet<>();
        for (Path file : normalized.keySet()) {
            for (Path parent = file.getParent();
                    parent != null && root != null && parent.startsWith(root);
                    parent = parent.getParent()) {
                directories.add(parent);
                if (parent.equals(root)) {
                    break;
                }
            }
        }
        gitChangedDirectories = Set.copyOf(directories);
        surface.setGitState(gitStatus, gitChangedDirectories);
        updateFilters();
    }

    void refreshStates() {
        surface.stateChanged();
    }

    void refresh() {
        reload();
    }

    Set<Path> expandedDirectories() {
        return Set.copyOf(expanded);
    }

    void focusMap() {
        surface.requestFocus();
    }

    void moveSelection(int delta) {
        surface.moveVertical(delta < 0 ? -1 : 1);
    }

    void openSelection() {
        surface.selectedEntry().ifPresent(surface.onActivate);
    }

    void setOnExpandedChanged(Runnable callback) {
        onExpandedChanged = callback == null ? () -> {} : callback;
    }

    void dispose() {
        disposed = true;
        generation.incrementAndGet();
        loader.shutdownNow();
    }

    private void updateFilters() {
        ProjectMapModel.TypeFilter type = typeFilter.getValue();
        surface.setFilters(new ProjectMapModel.Filters(
                query,
                openFilter.isSelected(),
                modifiedFilter.isSelected(),
                gitFilter.isSelected(),
                type == null ? ProjectMapModel.TypeFilter.ALL : type));
    }

    private void reload() {
        long requested = generation.incrementAndGet();
        Path requestedRoot = root;
        Set<Path> requestedExpanded = Set.copyOf(expanded);
        boolean requestedHidden = showHidden;
        if (requestedRoot == null) {
            surface.setEntries(List.of(), Set.of());
            return;
        }
        loader.submit(() -> {
            List<ProjectMapModel.Entry> entries =
                    ProjectMapModel.loadVisible(requestedRoot, requestedExpanded, requestedHidden);
            Platform.runLater(() -> {
                if (disposed || requested != generation.get()) {
                    return;
                }
                surface.setEntries(entries, requestedExpanded);
            });
        });
    }

    private void activate(ProjectMapModel.Entry entry) {
        if (entry.directory()) {
            Path path = entry.path();
            if (!expanded.add(path)) {
                expanded.removeIf(candidate -> candidate.startsWith(path) && !candidate.equals(root));
            }
            reload();
            onExpandedChanged.run();
        } else {
            onOpenFile.accept(entry.path());
        }
    }

    /** Canvas surface with a single keyboard focus target and deterministic screen-space hit boxes. */
    private final class MapSurface extends Region {

        private static final double NODE_WIDTH = 164;
        private static final double NODE_HEIGHT = 32;
        private static final double COLUMN_GAP = 50;
        private static final double ROW_GAP = 9;
        private static final double WORLD_PADDING = 22;
        private static final double MIN_ZOOM = 0.65;
        private static final double MAX_ZOOM = 1.55;

        private final Canvas canvas = new Canvas(1, 1);
        private final Rectangle bgProbe = probe("project-map-probe-bg");
        private final Rectangle surfaceProbe = probe("project-map-probe-surface");
        private final Rectangle borderProbe = probe("project-map-probe-border");
        private final Rectangle textProbe = probe("project-map-probe-text");
        private final Rectangle mutedProbe = probe("project-map-probe-muted");
        private final Rectangle accentProbe = probe("project-map-probe-accent");
        private final Rectangle warningProbe = probe("project-map-probe-warning");
        private final Rectangle successProbe = probe("project-map-probe-success");
        private final List<NodeBox> boxes = new ArrayList<>();

        private List<ProjectMapModel.Entry> entries = List.of();
        private Set<Path> expandedSnapshot = Set.of();
        private ProjectMapModel.Filters filters =
                new ProjectMapModel.Filters("", false, false, false, ProjectMapModel.TypeFilter.ALL);
        private Predicate<Path> openState = path -> false;
        private Predicate<Path> modifiedState = path -> false;
        private Map<Path, GitFileStatus> gitState = Map.of();
        private Set<Path> gitDirectories = Set.of();
        private Set<Path> openPaths = Set.of();
        private Set<Path> modifiedPaths = Set.of();
        private Set<Path> emphasized = Set.of();
        private Path selected;
        private Path hovered;
        private Consumer<ProjectMapModel.Entry> onActivate = entry -> {};
        private Runnable onZoomChanged = () -> {};
        private double zoom = 1.0;
        private double offsetX;
        private double offsetY;
        private double pressX;
        private double pressY;
        private double pressOffsetX;
        private double pressOffsetY;
        private boolean panning;

        MapSurface() {
            getStyleClass().add("project-map-surface");
            getChildren()
                    .addAll(
                            canvas,
                            bgProbe,
                            surfaceProbe,
                            borderProbe,
                            textProbe,
                            mutedProbe,
                            accentProbe,
                            warningProbe,
                            successProbe);
            setMinSize(80, 100);
            setFocusTraversable(true);
            setAccessibleRole(AccessibleRole.TREE_VIEW);
            setAccessibleHelp(tr("project.map.accessibleHelp"));

            widthProperty().addListener((obs, old, value) -> repaint());
            heightProperty().addListener((obs, old, value) -> repaint());
            focusedProperty().addListener((obs, old, value) -> repaint());
            for (Rectangle probe : List.of(
                    bgProbe,
                    surfaceProbe,
                    borderProbe,
                    textProbe,
                    mutedProbe,
                    accentProbe,
                    warningProbe,
                    successProbe)) {
                probe.fillProperty().addListener((obs, old, value) -> repaint());
            }

            addEventHandler(MouseEvent.MOUSE_PRESSED, this::mousePressed);
            addEventHandler(MouseEvent.MOUSE_DRAGGED, this::mouseDragged);
            addEventHandler(MouseEvent.MOUSE_RELEASED, event -> panning = false);
            addEventHandler(MouseEvent.MOUSE_MOVED, this::mouseMoved);
            addEventHandler(MouseEvent.MOUSE_CLICKED, this::mouseClicked);
            addEventHandler(ScrollEvent.SCROLL, this::scrolled);
            addEventFilter(KeyEvent.KEY_PRESSED, this::keyPressed);
        }

        void setOnActivate(Consumer<ProjectMapModel.Entry> onActivate) {
            this.onActivate = onActivate;
        }

        void setOnZoomChanged(Runnable onZoomChanged) {
            this.onZoomChanged = onZoomChanged;
        }

        void setStatusSuppliers(Predicate<Path> open, Predicate<Path> modified) {
            openState = open;
            modifiedState = modified;
        }

        void setEntries(List<ProjectMapModel.Entry> entries, Set<Path> expanded) {
            this.entries = entries == null ? List.of() : List.copyOf(entries);
            expandedSnapshot = expanded == null ? Set.of() : Set.copyOf(expanded);
            if (selected == null
                    || this.entries.stream().noneMatch(entry -> entry.path().equals(selected))) {
                selected =
                        this.entries.isEmpty() ? null : this.entries.getFirst().path();
            }
            snapshotStates();
            recomputeEmphasis();
            updateAccessibleText();
            repaint();
        }

        void setSelected(Path selected) {
            this.selected = selected;
            updateAccessibleText();
            repaint();
        }

        void setFilters(ProjectMapModel.Filters filters) {
            this.filters = filters;
            recomputeEmphasis();
            repaint();
        }

        void setGitState(Map<Path, GitFileStatus> status, Set<Path> directories) {
            gitState = status == null ? Map.of() : status;
            gitDirectories = directories == null ? Set.of() : directories;
            recomputeEmphasis();
            repaint();
        }

        void stateChanged() {
            snapshotStates();
            recomputeEmphasis();
            repaint();
        }

        void repaint() {
            if (getWidth() > 0 && getHeight() > 0) {
                paint();
            }
        }

        String zoomPercent() {
            return Math.round(zoom * 100) + "%";
        }

        void zoomBy(double factor) {
            setZoom(zoom * factor, getWidth() / 2.0, getHeight() / 2.0);
        }

        void resetViewport() {
            zoom = 1.0;
            offsetX = 0;
            offsetY = 0;
            onZoomChanged.run();
            repaint();
        }

        @Override
        protected void layoutChildren() {
            canvas.setWidth(Math.max(1, getWidth()));
            canvas.setHeight(Math.max(1, getHeight()));
            repaint();
        }

        private void recomputeEmphasis() {
            if (filters == null || !filters.active()) {
                emphasized = Set.of();
                return;
            }
            Set<Path> direct = new HashSet<>();
            for (ProjectMapModel.Entry entry : entries) {
                Path path = entry.path();
                if (ProjectMapModel.matches(
                        entry,
                        filters,
                        openPaths.contains(path),
                        modifiedPaths.contains(path),
                        gitState.containsKey(path) || gitDirectories.contains(path))) {
                    direct.add(path);
                }
            }
            emphasized = ProjectMapModel.emphasized(entries, direct);
        }

        private void paint() {
            double width = canvas.getWidth();
            double height = canvas.getHeight();
            GraphicsContext g = canvas.getGraphicsContext2D();
            g.setGlobalAlpha(1);
            g.setFill(color(bgProbe, Color.web("#0f141c")));
            g.fillRect(0, 0, width, height);
            boxes.clear();
            if (entries.isEmpty()) {
                g.setFill(color(mutedProbe, Color.web("#8b949e")));
                g.setFont(Font.font(13));
                g.fillText(tr("project.map.empty"), 18, 28);
                return;
            }

            Map<Integer, List<ProjectMapModel.Entry>> columns = new java.util.TreeMap<>();
            for (ProjectMapModel.Entry entry : entries) {
                columns.computeIfAbsent(entry.depth(), ignored -> new ArrayList<>())
                        .add(entry);
            }
            for (List<ProjectMapModel.Entry> column : columns.values()) {
                column.sort(Comparator.comparing(ProjectMapModel.Entry::name, String.CASE_INSENSITIVE_ORDER));
            }
            for (var columnEntry : columns.entrySet()) {
                int depth = columnEntry.getKey();
                List<ProjectMapModel.Entry> column = columnEntry.getValue();
                for (int row = 0; row < column.size(); row++) {
                    double worldX = WORLD_PADDING + depth * (NODE_WIDTH + COLUMN_GAP);
                    double worldY = WORLD_PADDING + 22 + row * (NODE_HEIGHT + ROW_GAP);
                    boxes.add(new NodeBox(
                            column.get(row), screenX(worldX), screenY(worldY), NODE_WIDTH * zoom, NODE_HEIGHT * zoom));
                }
            }

            drawColumns(g, columns, height);
            Map<Path, NodeBox> byPath = new HashMap<>();
            for (NodeBox box : boxes) {
                byPath.put(box.entry().path(), box);
            }
            g.setStroke(color(accentProbe, Color.web("#58a6ff")));
            g.setLineWidth(Math.max(1, 1.35 * zoom));
            for (NodeBox child : boxes) {
                NodeBox parent = byPath.get(child.entry().parent());
                if (parent == null || !inViewport(parent, width, height) && !inViewport(child, width, height)) {
                    continue;
                }
                double alpha = prominence(child.entry().path()) ? 0.72 : 0.16;
                g.setGlobalAlpha(alpha);
                double x1 = parent.x() + parent.width();
                double y1 = parent.y() + parent.height() / 2;
                double x2 = child.x();
                double y2 = child.y() + child.height() / 2;
                double bend = Math.max(12, (x2 - x1) * 0.48);
                g.beginPath();
                g.moveTo(x1, y1);
                g.bezierCurveTo(x1 + bend, y1, x2 - bend, y2, x2, y2);
                g.stroke();
            }
            for (NodeBox box : boxes) {
                if (inViewport(box, width, height)) {
                    drawNode(g, box);
                }
            }
            g.setGlobalAlpha(1);
        }

        private void drawColumns(
                GraphicsContext g, Map<Integer, List<ProjectMapModel.Entry>> columns, double viewportHeight) {
            Color surface = color(surfaceProbe, Color.web("#161d27"));
            Color border = color(borderProbe, Color.web("#303946"));
            for (var entry : columns.entrySet()) {
                double x = screenX(WORLD_PADDING + entry.getKey() * (NODE_WIDTH + COLUMN_GAP) - 10);
                double y = screenY(WORLD_PADDING - 5);
                double w = (NODE_WIDTH + 20) * zoom;
                double contentHeight = (49 + entry.getValue().size() * (NODE_HEIGHT + ROW_GAP)) * zoom;
                double h = Math.max(contentHeight, Math.max(80, viewportHeight - y - 42));
                g.setGlobalAlpha(0.64);
                g.setFill(surface);
                g.fillRoundRect(x, y, w, h, 12 * zoom, 12 * zoom);
                g.setStroke(border);
                g.setLineWidth(1);
                g.strokeRoundRect(x, y, w, h, 12 * zoom, 12 * zoom);
            }
            g.setGlobalAlpha(1);
        }

        private void drawNode(GraphicsContext g, NodeBox box) {
            ProjectMapModel.Entry entry = box.entry();
            boolean isSelected = entry.path().equals(selected);
            boolean isHovered = entry.path().equals(hovered);
            double alpha = prominence(entry.path()) || isSelected ? 1.0 : 0.2;
            g.setGlobalAlpha(alpha);
            Color accent = color(accentProbe, Color.web("#388bfd"));
            Color fill = isSelected ? accent : color(surfaceProbe, Color.web("#202938"));
            if (isHovered && !isSelected) {
                fill = mix(fill, accent, 0.16);
            }
            g.setFill(fill);
            g.fillRoundRect(box.x(), box.y(), box.width(), box.height(), 8 * zoom, 8 * zoom);
            g.setStroke(isSelected || isHovered ? accent : color(borderProbe, Color.web("#3a4554")));
            g.setLineWidth((isSelected ? 1.5 : 1.0) * zoom);
            g.strokeRoundRect(box.x(), box.y(), box.width(), box.height(), 8 * zoom, 8 * zoom);

            double iconX = box.x() + 10 * zoom;
            double iconY = box.y() + 9 * zoom;
            drawIcon(g, entry.directory(), iconX, iconY);
            g.setFill(isSelected ? Color.WHITE : color(textProbe, Color.web("#d8dee9")));
            g.setFont(Font.font("System", isSelected ? FontWeight.SEMI_BOLD : FontWeight.NORMAL, 12 * zoom));
            String label = ellipsize(entry.name(), Math.max(4, (int) (15 / zoom)));
            g.fillText(label, box.x() + 31 * zoom, box.y() + 20.5 * zoom, box.width() - 51 * zoom);

            drawStatusDots(g, entry, box);
            if (entry.directory() && expandedSnapshot.contains(entry.path())) {
                g.setFill(isSelected ? Color.WHITE : color(mutedProbe, Color.web("#8b949e")));
                g.fillText("›", box.x() + box.width() - 15 * zoom, box.y() + 21 * zoom);
            }
            g.setGlobalAlpha(1);
        }

        private void drawIcon(GraphicsContext g, boolean directory, double x, double y) {
            g.setStroke(color(accentProbe, Color.web("#58a6ff")));
            g.setLineWidth(Math.max(1, 1.2 * zoom));
            if (directory) {
                g.strokeRoundRect(x, y + 2 * zoom, 13 * zoom, 10 * zoom, 2 * zoom, 2 * zoom);
                g.strokeLine(x + 1 * zoom, y + 2 * zoom, x + 5 * zoom, y - 1 * zoom);
                g.strokeLine(x + 5 * zoom, y - 1 * zoom, x + 9 * zoom, y + 2 * zoom);
            } else {
                g.strokeRoundRect(x + 1 * zoom, y - 1 * zoom, 11 * zoom, 14 * zoom, 1.5 * zoom, 1.5 * zoom);
                g.strokeLine(x + 7 * zoom, y - 1 * zoom, x + 12 * zoom, y + 4 * zoom);
            }
        }

        private void drawStatusDots(GraphicsContext g, ProjectMapModel.Entry entry, NodeBox box) {
            if (entry.directory()) {
                return;
            }
            List<Color> dots = new ArrayList<>(3);
            if (openPaths.contains(entry.path())) {
                dots.add(color(accentProbe, Color.web("#58a6ff")));
            }
            if (modifiedPaths.contains(entry.path())) {
                dots.add(color(warningProbe, Color.web("#d29922")));
            }
            GitFileStatus status = gitState.get(entry.path());
            if (status != null) {
                dots.add(
                        status == GitFileStatus.ADDED || status == GitFileStatus.UNTRACKED
                                ? color(successProbe, Color.web("#3fb950"))
                                : color(accentProbe, Color.web("#58a6ff")));
            }
            double x = box.x() + box.width() - 10 * zoom;
            for (Color dot : dots.reversed()) {
                g.setFill(dot);
                g.fillOval(x - 5 * zoom, box.y() + 13 * zoom, 5 * zoom, 5 * zoom);
                x -= 7 * zoom;
            }
        }

        private boolean prominence(Path path) {
            return !filters.active() || emphasized.contains(path);
        }

        private void snapshotStates() {
            Set<Path> open = new HashSet<>();
            Set<Path> modified = new HashSet<>();
            for (ProjectMapModel.Entry entry : entries) {
                if (entry.directory()) {
                    continue;
                }
                if (safeTest(openState, entry.path())) {
                    open.add(entry.path());
                }
                if (safeTest(modifiedState, entry.path())) {
                    modified.add(entry.path());
                }
            }
            openPaths = Set.copyOf(open);
            modifiedPaths = Set.copyOf(modified);
        }

        private void mousePressed(MouseEvent event) {
            requestFocus();
            pressX = event.getX();
            pressY = event.getY();
            pressOffsetX = offsetX;
            pressOffsetY = offsetY;
            panning = hit(event.getX(), event.getY()) == null && event.getButton() == MouseButton.PRIMARY;
        }

        private void mouseDragged(MouseEvent event) {
            if (!panning) {
                return;
            }
            offsetX = pressOffsetX + event.getX() - pressX;
            offsetY = pressOffsetY + event.getY() - pressY;
            repaint();
            event.consume();
        }

        private void mouseMoved(MouseEvent event) {
            NodeBox hit = hit(event.getX(), event.getY());
            Path next = hit == null ? null : hit.entry().path();
            if (!java.util.Objects.equals(next, hovered)) {
                hovered = next;
                setCursor(hit == null ? Cursor.DEFAULT : Cursor.HAND);
                repaint();
            }
        }

        private void mouseClicked(MouseEvent event) {
            if (event.getButton() != MouseButton.PRIMARY || panning) {
                return;
            }
            NodeBox hit = hit(event.getX(), event.getY());
            if (hit == null) {
                return;
            }
            selected = hit.entry().path();
            updateAccessibleText();
            repaint();
            if (event.getClickCount() >= 2) {
                onActivate.accept(hit.entry());
            }
            event.consume();
        }

        private void scrolled(ScrollEvent event) {
            if (event.isControlDown() || event.isMetaDown()) {
                setZoom(zoom * (event.getDeltaY() > 0 ? 1.1 : 0.9), event.getX(), event.getY());
            } else if (event.isShiftDown()) {
                offsetX += event.getDeltaY();
                repaint();
            } else {
                offsetY += event.getDeltaY();
                repaint();
            }
            event.consume();
        }

        private void setZoom(double requested, double pivotX, double pivotY) {
            double next = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, requested));
            if (next == zoom) {
                return;
            }
            double ratio = next / zoom;
            offsetX = pivotX - (pivotX - offsetX) * ratio;
            offsetY = pivotY - (pivotY - offsetY) * ratio;
            zoom = next;
            onZoomChanged.run();
            repaint();
        }

        private void keyPressed(KeyEvent event) {
            if (entries.isEmpty()) {
                return;
            }
            if (selected == null) {
                selected = entries.getFirst().path();
            }
            switch (event.getCode()) {
                case UP -> moveVertical(-1);
                case DOWN -> moveVertical(1);
                case LEFT -> selectParent();
                case RIGHT -> selectChildOrExpand();
                case ENTER, SPACE -> selectedEntry().ifPresent(onActivate);
                case HOME -> select(entries.getFirst().path());
                case ESCAPE -> resetViewport();
                default -> {
                    if (event.isControlDown() && event.getCode() == KeyCode.N) {
                        moveVertical(1);
                    } else if (event.isControlDown() && event.getCode() == KeyCode.P) {
                        moveVertical(-1);
                    } else {
                        return;
                    }
                }
            }
            event.consume();
        }

        private void moveVertical(int delta) {
            ProjectMapModel.Entry current = selectedEntry().orElse(entries.getFirst());
            List<ProjectMapModel.Entry> column = entries.stream()
                    .filter(entry -> entry.depth() == current.depth())
                    .sorted(Comparator.comparing(ProjectMapModel.Entry::name, String.CASE_INSENSITIVE_ORDER))
                    .toList();
            int index = column.indexOf(current);
            select(column.get(Math.floorMod(index + delta, column.size())).path());
        }

        private void selectParent() {
            selectedEntry()
                    .map(ProjectMapModel.Entry::parent)
                    .filter(java.util.Objects::nonNull)
                    .ifPresent(this::select);
        }

        private void selectChildOrExpand() {
            var current = selectedEntry();
            if (current.isEmpty() || !current.get().directory()) {
                return;
            }
            List<ProjectMapModel.Entry> children = entries.stream()
                    .filter(entry -> current.get().path().equals(entry.parent()))
                    .sorted(Comparator.comparing(ProjectMapModel.Entry::name, String.CASE_INSENSITIVE_ORDER))
                    .toList();
            if (children.isEmpty()) {
                if (!expandedSnapshot.contains(current.get().path())) {
                    onActivate.accept(current.get());
                }
            } else {
                select(children.getFirst().path());
            }
        }

        private java.util.Optional<ProjectMapModel.Entry> selectedEntry() {
            return entries.stream()
                    .filter(entry -> entry.path().equals(selected))
                    .findFirst();
        }

        private void select(Path path) {
            selected = path;
            updateAccessibleText();
            revealSelected();
            repaint();
        }

        private void revealSelected() {
            NodeBox box = boxes.stream()
                    .filter(candidate -> candidate.entry().path().equals(selected))
                    .findFirst()
                    .orElse(null);
            if (box == null) {
                return;
            }
            double margin = 20;
            if (box.x() < margin) {
                offsetX += margin - box.x();
            } else if (box.x() + box.width() > getWidth() - margin) {
                offsetX -= box.x() + box.width() - getWidth() + margin;
            }
            if (box.y() < margin) {
                offsetY += margin - box.y();
            } else if (box.y() + box.height() > getHeight() - margin) {
                offsetY -= box.y() + box.height() - getHeight() + margin;
            }
        }

        private NodeBox hit(double x, double y) {
            for (int i = boxes.size() - 1; i >= 0; i--) {
                NodeBox box = boxes.get(i);
                if (x >= box.x() && x <= box.x() + box.width() && y >= box.y() && y <= box.y() + box.height()) {
                    return box;
                }
            }
            return null;
        }

        private double screenX(double worldX) {
            return offsetX + worldX * zoom;
        }

        private double screenY(double worldY) {
            return offsetY + worldY * zoom;
        }

        private void updateAccessibleText() {
            String name = selectedEntry().map(ProjectMapModel.Entry::name).orElse(tr("project.map.empty"));
            setAccessibleText(tr("project.map.accessibleSelection", name));
        }

        private Rectangle probe(String styleClass) {
            Rectangle probe = new Rectangle(0, 0);
            probe.getStyleClass().add(styleClass);
            probe.setManaged(false);
            probe.setMouseTransparent(true);
            return probe;
        }

        private Color color(Rectangle probe, Color fallback) {
            Paint fill = probe.getFill();
            return fill instanceof Color value ? value : fallback;
        }

        private boolean inViewport(NodeBox box, double width, double height) {
            return box.x() + box.width() >= 0 && box.y() + box.height() >= 0 && box.x() <= width && box.y() <= height;
        }

        private record NodeBox(ProjectMapModel.Entry entry, double x, double y, double width, double height) {}
    }

    private static boolean safeTest(Predicate<Path> predicate, Path path) {
        try {
            return predicate != null && predicate.test(path);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String ellipsize(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, Math.max(1, max - 1)) + "…";
    }

    private static Color mix(Color base, Color overlay, double amount) {
        return base.interpolate(overlay, amount);
    }
}
