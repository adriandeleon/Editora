package com.editora.ui;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.AccessibleRole;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.input.ContextMenuEvent;
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
import javafx.scene.text.Text;
import javafx.stage.WindowEvent;
import javafx.util.Duration;
import javafx.util.StringConverter;

import com.editora.git.GitFileStatus;

import static com.editora.i18n.Messages.tr;

/**
 * Spatial navigator for a project. Native JavaFX controls own filtering and zoom; the hierarchy itself is
 * drawn on a {@link Canvas}. Node context menus reuse the Project tree's file-management actions.
 */
final class ProjectMapView extends VBox {

    private static final int MAX_OPEN_PREVIEWS = 8;
    private static final double PREVIEW_CASCADE = 28;

    enum FlowDirection {
        LEFT_TO_RIGHT,
        RIGHT_TO_LEFT,
        TOP_TO_BOTTOM,
        BOTTOM_TO_TOP
    }

    private final Predicate<Path> isOpen;
    private final Predicate<Path> isModified;
    private final Consumer<Path> onOpenFile;
    private final Function<Path, ProjectMapPreview.Content> previewContent;
    private final ToggleButton openFilter = filterButton("project.map.filter.open");
    private final ToggleButton modifiedFilter = filterButton("project.map.filter.modified");
    private final ToggleButton gitFilter = filterButton("project.map.filter.gitChanged");
    private final ComboBox<ProjectMapModel.TypeFilter> typeFilter = new ComboBox<>();
    private final ComboBox<FlowDirection> flowFilter = new ComboBox<>();
    private final Button backButton = new Button("‹");
    private final Button forwardButton = new Button("›");
    private final Button printButton = new Button(tr("project.map.print"));
    private final Button exportPdfButton = new Button(tr("project.map.exportPdf"));
    private final HBox breadcrumbs = new HBox(2);
    private final MapSurface surface = new MapSurface();
    private final Map<Path, ProjectMapPreview> previews = new LinkedHashMap<>(16, 0.75f, true);
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "project-map-loader");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong generation = new AtomicLong();
    private final Set<Path> expanded = new HashSet<>();
    private final List<Path> selectionHistory = new ArrayList<>();

    private Path root;
    private boolean disposed;
    private String query = "";
    private Map<Path, GitFileStatus> gitStatus = Map.of();
    private Set<Path> gitChangedDirectories = Set.of();
    private Runnable onExpandedChanged = () -> {};
    private int historyIndex = -1;
    private boolean navigatingHistory;
    private Path pendingSelection;
    private Consumer<FlowDirection> onFlowChanged = ignored -> {};
    private Consumer<Image> onPrint = ignored -> {};
    private Consumer<Image> onExportPdf = ignored -> {};
    private ProjectMapPreview.MarkerActions previewMarkerActions;
    private StackPane canvasHost;

    ProjectMapView(Consumer<Path> onOpenFile, Predicate<Path> isOpen, Predicate<Path> isModified) {
        this(onOpenFile, isOpen, isModified, path -> null);
    }

    ProjectMapView(
            Consumer<Path> onOpenFile,
            Predicate<Path> isOpen,
            Predicate<Path> isModified,
            Function<Path, ProjectMapPreview.Content> previewContent) {
        this.onOpenFile = onOpenFile;
        this.isOpen = isOpen == null ? path -> false : isOpen;
        this.isModified = isModified == null ? path -> false : isModified;
        this.previewContent = previewContent == null ? path -> null : previewContent;
        getStyleClass().add("project-map-view");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(4);

        getChildren().addAll(buildFilters(), buildNavigation(), buildCanvasHost());
        VBox.setVgrow(getChildren().get(2), Priority.ALWAYS);
        surface.setOnActivate(this::activate);
        surface.setOnCloseColumn(this::closeColumn);
        surface.setOnPreview(this::previewSelection);
        surface.setOnSelectionChanged(this::selectionChanged);
        surface.setStatusSuppliers(this.isOpen, this.isModified);
        updateFilters();
    }

    private HBox buildNavigation() {
        backButton.getStyleClass().add("project-map-nav-button");
        forwardButton.getStyleClass().add("project-map-nav-button");
        backButton.setTooltip(new Tooltip(tr("project.map.navigation.back")));
        forwardButton.setTooltip(new Tooltip(tr("project.map.navigation.forward")));
        backButton.setOnAction(event -> moveHistory(-1));
        forwardButton.setOnAction(event -> moveHistory(1));
        breadcrumbs.getStyleClass().add("project-map-breadcrumbs");
        HBox row = new HBox(3, backButton, forwardButton, breadcrumbs);
        row.getStyleClass().add("project-map-navigation");
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(breadcrumbs, Priority.ALWAYS);
        updateNavigation();
        return row;
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

        flowFilter.getItems().setAll(FlowDirection.values());
        flowFilter.setValue(FlowDirection.RIGHT_TO_LEFT);
        flowFilter.getStyleClass().add("project-map-flow-filter");
        flowFilter.setConverter(new StringConverter<>() {
            @Override
            public String toString(FlowDirection value) {
                return flowName(value);
            }

            @Override
            public FlowDirection fromString(String value) {
                return FlowDirection.RIGHT_TO_LEFT;
            }
        });
        flowFilter.setButtonCell(flowCell());
        flowFilter.setCellFactory(list -> flowCell());

        Button clear = new Button(tr("project.map.filter.clear"));
        clear.getStyleClass().add("project-map-filter-clear");
        clear.setOnAction(event -> {
            openFilter.setSelected(false);
            modifiedFilter.setSelected(false);
            gitFilter.setSelected(false);
            typeFilter.setValue(ProjectMapModel.TypeFilter.ALL);
            surface.clearColumnFilters();
            updateFilters();
        });

        for (ToggleButton button : List.of(openFilter, modifiedFilter, gitFilter)) {
            button.setOnAction(event -> updateFilters());
        }
        typeFilter.setOnAction(event -> updateFilters());
        flowFilter.setOnAction(event -> {
            FlowDirection flow = flowFilter.getValue();
            surface.setFlowDirection(flow);
            onFlowChanged.accept(flow);
        });

        FlowPane filters =
                new FlowPane(6, 4, heading, openFilter, modifiedFilter, gitFilter, typeFilter, flowFilter, clear);
        filters.getStyleClass().add("project-map-filters");
        filters.setAlignment(Pos.CENTER_LEFT);
        return filters;
    }

    private StackPane buildCanvasHost() {
        Button zoomOut = new Button("−");
        Label zoom = new Label("100%");
        Button zoomIn = new Button("+");
        Button fit = new Button(tr("project.map.zoom.fit"));
        Button center = new Button(tr("project.map.navigation.center"));
        Button reset = new Button(tr("project.map.zoom.reset"));
        for (var node : List.of(zoomOut, zoom, zoomIn, fit, center, reset, printButton, exportPdfButton)) {
            node.getStyleClass().add("project-map-zoom-control");
        }
        zoomOut.setTooltip(new Tooltip(tr("project.map.zoom.out")));
        zoomIn.setTooltip(new Tooltip(tr("project.map.zoom.in")));
        fit.setTooltip(new Tooltip(tr("project.map.zoom.fitHelp")));
        center.setTooltip(new Tooltip(tr("project.map.navigation.centerHelp")));
        reset.setTooltip(new Tooltip(tr("project.map.zoom.reset")));
        printButton.setTooltip(new Tooltip(tr("project.map.print.help")));
        exportPdfButton.setTooltip(new Tooltip(tr("project.map.exportPdf.help")));
        printButton.setDisable(true);
        exportPdfButton.setDisable(true);
        zoomOut.setOnAction(event -> {
            surface.zoomBy(0.9);
            zoom.setText(surface.zoomPercent());
        });
        zoomIn.setOnAction(event -> {
            surface.zoomBy(1.1);
            zoom.setText(surface.zoomPercent());
        });
        fit.setOnAction(event -> surface.fitContent());
        center.setOnAction(event -> surface.centerSelection());
        reset.setOnAction(event -> {
            surface.resetViewport();
            zoom.setText(surface.zoomPercent());
        });
        printButton.setOnAction(event -> snapshotForOutput(onPrint));
        exportPdfButton.setOnAction(event -> snapshotForOutput(onExportPdf));
        surface.setOnZoomChanged(() -> zoom.setText(surface.zoomPercent()));

        HBox zoomBar = new HBox(2, zoomOut, zoom, zoomIn, fit, center, reset, printButton, exportPdfButton);
        zoomBar.getStyleClass().add("project-map-zoom");
        zoomBar.setAlignment(Pos.CENTER);
        zoomBar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane host = new StackPane(surface, zoomBar);
        canvasHost = host;
        host.getStyleClass().add("project-map-host");
        StackPane.setAlignment(zoomBar, Pos.BOTTOM_LEFT);
        StackPane.setMargin(zoomBar, new Insets(8));
        host.widthProperty().addListener((obs, old, value) -> constrainPreviews(value.doubleValue(), host.getHeight()));
        host.heightProperty().addListener((obs, old, value) -> constrainPreviews(host.getWidth(), value.doubleValue()));
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

    private static ListCell<FlowDirection> flowCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(FlowDirection item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : flowName(item));
            }
        };
    }

    private static String flowName(FlowDirection flow) {
        if (flow == null) {
            flow = FlowDirection.RIGHT_TO_LEFT;
        }
        return tr("project.map.flow." + flow.name().toLowerCase(java.util.Locale.ROOT));
    }

    void setRoot(Path root) {
        Path normalized = ProjectMapModel.normalize(root);
        if (java.util.Objects.equals(this.root, normalized)) {
            return;
        }
        this.root = normalized;
        closeAllPreviews();
        expanded.clear();
        selectionHistory.clear();
        historyIndex = -1;
        pendingSelection = normalized;
        setOutputEnabled(false);
        surface.resetForRoot();
        if (normalized != null) {
            expanded.add(normalized);
            recordSelection(normalized);
        }
        surface.setSelected(normalized);
        updateNavigation();
        reload();
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

    void setMarkerStates(Predicate<Path> bookmarked, Predicate<Path> noted) {
        surface.setMarkerSuppliers(bookmarked, noted);
        surface.stateChanged();
    }

    void setPreviewMarkerActions(ProjectMapPreview.MarkerActions actions) {
        previewMarkerActions = actions;
        previews.values().forEach(preview -> preview.setMarkerActions(actions));
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
        surface.moveSibling(delta < 0 ? -1 : 1);
    }

    void openSelection() {
        surface.selectedEntry().ifPresent(surface.onActivate);
    }

    void setOnExpandedChanged(Runnable callback) {
        onExpandedChanged = callback == null ? () -> {} : callback;
    }

    void setRememberedFlow(String name, Consumer<FlowDirection> callback) {
        FlowDirection remembered;
        try {
            remembered = FlowDirection.valueOf(name == null ? "" : name);
        } catch (IllegalArgumentException ignored) {
            remembered = FlowDirection.RIGHT_TO_LEFT;
        }
        onFlowChanged = callback == null ? ignored -> {} : callback;
        flowFilter.setValue(remembered);
        surface.setFlowDirection(remembered);
    }

    void setContextMenuFactory(Function<ProjectMapModel.Entry, ContextMenu> factory) {
        surface.setContextMenuFactory(factory);
    }

    void setOutputActions(Consumer<Image> print, Consumer<Image> exportPdf) {
        onPrint = print == null ? ignored -> {} : print;
        onExportPdf = exportPdf == null ? ignored -> {} : exportPdf;
    }

    void hidePreview() {
        closeAllPreviews();
    }

    void dispose() {
        disposed = true;
        generation.incrementAndGet();
        loader.shutdownNow();
        surface.dispose();
        closeAllPreviews();
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

    private void snapshotForOutput(Consumer<Image> output) {
        Image image = surface.snapshotContent();
        if (image != null) {
            output.accept(image);
        }
    }

    private void setOutputEnabled(boolean enabled) {
        printButton.setDisable(!enabled);
        exportPdfButton.setDisable(!enabled);
    }

    private void reload() {
        long requested = generation.incrementAndGet();
        Path requestedRoot = root;
        Set<Path> requestedExpanded = Set.copyOf(expanded);
        if (requestedRoot == null) {
            surface.setEntries(List.of(), Set.of());
            return;
        }
        loader.submit(() -> {
            List<ProjectMapModel.Entry> entries = ProjectMapModel.loadVisible(requestedRoot, requestedExpanded, true);
            Platform.runLater(() -> {
                if (disposed || requested != generation.get()) {
                    return;
                }
                surface.setEntries(entries, requestedExpanded);
                setOutputEnabled(!entries.isEmpty());
                if (pendingSelection != null
                        && entries.stream().anyMatch(entry -> entry.path().equals(pendingSelection))) {
                    surface.setSelected(pendingSelection);
                    pendingSelection = null;
                }
                updateNavigation();
            });
        });
    }

    private void activate(ProjectMapModel.Entry entry) {
        if (entry.directory()) {
            Set<Path> nextExpansion = ProjectMapModel.toggleExpansion(root, expanded, entry.path());
            expanded.clear();
            expanded.addAll(nextExpansion);
            reload();
            onExpandedChanged.run();
        } else {
            onOpenFile.accept(entry.path());
        }
    }

    private void closeColumn(Path parent) {
        Path normalized = ProjectMapModel.normalize(parent);
        if (normalized == null) {
            return;
        }
        expanded.removeIf(path -> path.startsWith(normalized));
        closePreviewsUnder(normalized);
        pendingSelection = normalized;
        surface.setSelected(normalized);
        reload();
        onExpandedChanged.run();
    }

    private void selectionChanged(Path path) {
        if (path == null) {
            return;
        }
        if (!navigatingHistory) {
            recordSelection(path);
        }
        updateNavigation();
    }

    private void previewSelection(Path path) {
        ProjectMapModel.Entry entry = surface.selectedEntry()
                .filter(candidate -> candidate.path().equals(path))
                .orElse(null);
        if (entry == null || entry.directory()) {
            return;
        }
        Path selected = entry.path().toAbsolutePath().normalize();
        ProjectMapPreview preview = previews.get(selected);
        if (preview != null) {
            preview.toFront();
            preview.requestFocus();
            return;
        }
        ProjectMapPreview.Content content;
        try {
            content = previewContent.apply(entry.path());
        } catch (RuntimeException ignored) {
            content = null;
        }
        preview = createPreview(selected);
        ProjectMapPreview selectedPreview = preview;
        preview.showFile(
                entry.path(),
                content,
                (width, height, parentWidth, parentHeight) ->
                        previewPlacement(selectedPreview, entry.path(), width, height, parentWidth, parentHeight));
    }

    private ProjectMapPreview createPreview(Path path) {
        if (previews.size() >= MAX_OPEN_PREVIEWS) {
            closePreview(previews.values().iterator().next());
        }
        ProjectMapPreview preview = new ProjectMapPreview(onOpenFile);
        preview.setMarkerActions(previewMarkerActions);
        preview.setOnClose(() -> closePreview(preview));
        preview.setOnActivate(() -> touchPreview(path, preview));
        previews.put(path, preview);
        canvasHost.getChildren().add(preview);
        return preview;
    }

    private void closePreview(ProjectMapPreview preview) {
        if (preview == null) {
            return;
        }
        previews.entrySet().removeIf(entry -> entry.getValue() == preview);
        if (canvasHost != null) {
            canvasHost.getChildren().remove(preview);
        }
        preview.dispose();
    }

    private void touchPreview(Path path, ProjectMapPreview preview) {
        if (previews.get(path) != preview) {
            return;
        }
        preview.toFront();
    }

    private void closeAllPreviews() {
        List<ProjectMapPreview> open = List.copyOf(previews.values());
        previews.clear();
        for (ProjectMapPreview preview : open) {
            if (canvasHost != null) {
                canvasHost.getChildren().remove(preview);
            }
            preview.dispose();
        }
    }

    private void closePreviewsUnder(Path directory) {
        List<ProjectMapPreview> closing = previews.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(directory))
                .map(Map.Entry::getValue)
                .toList();
        closing.forEach(this::closePreview);
    }

    private void constrainPreviews(double width, double height) {
        previews.values().forEach(preview -> preview.constrainTo(width, height));
    }

    private ProjectMapPreview.Placement previewPlacement(
            ProjectMapPreview preview,
            Path path,
            double width,
            double height,
            double parentWidth,
            double parentHeight) {
        ProjectMapPreview.Placement preferred =
                surface.previewPlacement(path, width, height, parentWidth, parentHeight);
        if (!overlapsPreview(preview, preferred.x(), preferred.y(), preferred.width(), preferred.height())) {
            return preferred;
        }

        boolean horizontal = flowFilter.getValue() == FlowDirection.LEFT_TO_RIGHT
                || flowFilter.getValue() == FlowDirection.RIGHT_TO_LEFT;
        double maximum = horizontal
                ? Math.max(
                        ProjectMapPreview.EDGE_MARGIN,
                        parentHeight - preferred.height() - ProjectMapPreview.EDGE_MARGIN)
                : Math.max(
                        ProjectMapPreview.EDGE_MARGIN, parentWidth - preferred.width() - ProjectMapPreview.EDGE_MARGIN);
        double origin = horizontal ? preferred.y() : preferred.x();
        double step = (horizontal ? preferred.height() : preferred.width()) + PREVIEW_CASCADE;
        for (int ring = 1; ring <= previews.size(); ring++) {
            for (int sign : new int[] {1, -1}) {
                double shifted = clampPreview(origin + sign * ring * step, ProjectMapPreview.EDGE_MARGIN, maximum);
                double x = horizontal ? preferred.x() : shifted;
                double y = horizontal ? shifted : preferred.y();
                if (!overlapsPreview(preview, x, y, preferred.width(), preferred.height())) {
                    return new ProjectMapPreview.Placement(x, y, preferred.width(), preferred.height());
                }
            }
        }

        int index = Math.max(1, previews.size() - 1);
        double x = horizontal
                ? preferred.x()
                : clampPreview(
                        preferred.x() + index * PREVIEW_CASCADE,
                        ProjectMapPreview.EDGE_MARGIN,
                        Math.max(
                                ProjectMapPreview.EDGE_MARGIN,
                                parentWidth - preferred.width() - ProjectMapPreview.EDGE_MARGIN));
        double y = horizontal
                ? clampPreview(
                        preferred.y() + index * PREVIEW_CASCADE,
                        ProjectMapPreview.EDGE_MARGIN,
                        Math.max(
                                ProjectMapPreview.EDGE_MARGIN,
                                parentHeight - preferred.height() - ProjectMapPreview.EDGE_MARGIN))
                : preferred.y();
        return new ProjectMapPreview.Placement(x, y, preferred.width(), preferred.height());
    }

    private boolean overlapsPreview(ProjectMapPreview candidate, double x, double y, double width, double height) {
        for (ProjectMapPreview other : previews.values()) {
            if (other == candidate || !other.isVisible()) {
                continue;
            }
            if (x < other.getLayoutX() + other.getWidth() + PREVIEW_CASCADE
                    && x + width + PREVIEW_CASCADE > other.getLayoutX()
                    && y < other.getLayoutY() + other.getHeight() + PREVIEW_CASCADE
                    && y + height + PREVIEW_CASCADE > other.getLayoutY()) {
                return true;
            }
        }
        return false;
    }

    private static double clampPreview(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private void recordSelection(Path path) {
        Path normalized = ProjectMapModel.normalize(path);
        if (normalized == null
                || historyIndex >= 0 && selectionHistory.get(historyIndex).equals(normalized)) {
            return;
        }
        if (historyIndex + 1 < selectionHistory.size()) {
            selectionHistory.subList(historyIndex + 1, selectionHistory.size()).clear();
        }
        selectionHistory.add(normalized);
        historyIndex = selectionHistory.size() - 1;
        updateNavigation();
    }

    private void moveHistory(int delta) {
        int target = historyIndex + delta;
        if (target < 0 || target >= selectionHistory.size()) {
            return;
        }
        historyIndex = target;
        navigatingHistory = true;
        try {
            revealPath(selectionHistory.get(target));
        } finally {
            navigatingHistory = false;
        }
        updateNavigation();
    }

    private void revealPath(Path path) {
        Path normalized = ProjectMapModel.normalize(path);
        if (normalized == null || root == null || !normalized.startsWith(root)) {
            return;
        }
        if (surface.contains(normalized)) {
            surface.select(normalized);
            return;
        }
        expanded.clear();
        expanded.add(root);
        for (Path parent = normalized.getParent();
                parent != null && parent.startsWith(root) && !parent.equals(root);
                parent = parent.getParent()) {
            expanded.add(parent);
        }
        pendingSelection = normalized;
        reload();
        onExpandedChanged.run();
    }

    private void updateNavigation() {
        backButton.setDisable(historyIndex <= 0);
        forwardButton.setDisable(historyIndex < 0 || historyIndex >= selectionHistory.size() - 1);
        Path selected = surface.selectedEntry().map(ProjectMapModel.Entry::path).orElse(pendingSelection);
        if (selected == null || root == null || !selected.startsWith(root)) {
            breadcrumbs.getChildren().clear();
            return;
        }
        List<Path> trail = new ArrayList<>();
        for (Path current = selected; current != null && current.startsWith(root); current = current.getParent()) {
            trail.add(current);
            if (current.equals(root)) {
                break;
            }
        }
        List<Node> nodes = new ArrayList<>();
        for (Path path : trail.reversed()) {
            Path fileName = path.getFileName();
            Button crumb = new Button(fileName == null ? path.toString() : fileName.toString());
            crumb.getStyleClass().add("project-map-breadcrumb");
            crumb.setTooltip(new Tooltip(path.toString()));
            crumb.setOnAction(event -> revealPath(path));
            nodes.add(crumb);
            if (!path.equals(selected)) {
                Label separator = new Label("›");
                separator.getStyleClass().add("project-map-breadcrumb-separator");
                nodes.add(separator);
            }
        }
        breadcrumbs.getChildren().setAll(nodes);
    }

    /** Canvas surface with a single keyboard focus target and deterministic screen-space hit boxes. */
    private final class MapSurface extends Region {

        private static final double MIN_NODE_WIDTH = 164;
        private static final double NODE_HEIGHT = 32;
        private static final double COLUMN_GAP = 50;
        private static final double ROW_GAP = 9;
        private static final double WORLD_PADDING = 22;
        private static final double COLUMN_HEADER_HEIGHT = 70;
        private static final double COLUMN_TOP_INSET = 5;
        private static final double COLUMN_BOTTOM_PADDING = 12;
        private static final double COLUMN_CONTROL_GAP = 4;
        private static final double MIN_COLUMN_CONTROL_HEIGHT = 20;
        private static final double MIN_COLUMN_FILTER_WIDTH = 46;
        private static final double MIN_COLUMN_HIDDEN_WIDTH = 50;
        private static final double MIN_COLUMN_PIN_WIDTH = 22;
        private static final double PREVIEW_EDGE_MARGIN = 14;
        private static final double PREVIEW_COLUMN_GAP = 18;
        private static final DateTimeFormatter TOOLTIP_TIME = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                .withLocale(Locale.getDefault())
                .withZone(ZoneId.systemDefault());
        private static final double ICON_SIZE = 20;
        private static final double ICON_RASTER_SCALE = 2;
        private static final double MIN_ZOOM = 0.4;
        private static final double MAX_ZOOM = 2.25;
        private static final double OUTPUT_RENDER_SCALE = 2.0;
        private static final double OUTPUT_MARGIN = 24;
        private static final double MAX_OUTPUT_DIMENSION = 8_192;
        private static final double MAX_OUTPUT_PIXELS = 12_000_000;

        private final Canvas canvas = new Canvas(1, 1);
        private final Rectangle viewportClip = new Rectangle();
        private final StackPane iconRasterizer = new StackPane();
        private final Rectangle bgProbe = probe("project-map-probe-bg");
        private final Rectangle surfaceProbe = probe("project-map-probe-surface");
        private final Rectangle borderProbe = probe("project-map-probe-border");
        private final Rectangle textProbe = probe("project-map-probe-text");
        private final Rectangle mutedProbe = probe("project-map-probe-muted");
        private final Rectangle accentProbe = probe("project-map-probe-accent");
        private final Rectangle warningProbe = probe("project-map-probe-warning");
        private final Rectangle successProbe = probe("project-map-probe-success");
        private final Rectangle folderProbe = probe("project-map-probe-folder");
        private final Rectangle fileProbe = probe("project-map-probe-file");
        private final Rectangle oliveProbe = probe("project-map-probe-olive");
        private final Rectangle violetProbe = probe("project-map-probe-violet");
        private final Tooltip nodeTooltip = new Tooltip();
        private final Text textMeasurer = new Text();
        private final List<NodeBox> boxes = new ArrayList<>();
        private final List<ColumnBox> columnBoxes = new ArrayList<>();
        private final Map<IconKey, Image> iconImages = new HashMap<>();
        private final Map<ProjectMapModel.ColumnId, ColumnControls> columnControls = new HashMap<>();
        private final Map<ProjectMapModel.ColumnId, ColumnLayout> columnLayouts = new HashMap<>();
        private final Map<ProjectMapModel.ColumnId, String> columnQueries = new HashMap<>();
        private final Map<ProjectMapModel.ColumnId, Boolean> columnShowHidden = new HashMap<>();
        private final Map<String, Double> measuredLabelWidths = new HashMap<>();

        private List<ProjectMapModel.Entry> entries = List.of();
        private Set<Path> expandedSnapshot = Set.of();
        private ProjectMapModel.Filters filters =
                new ProjectMapModel.Filters("", false, false, false, ProjectMapModel.TypeFilter.ALL);
        private Predicate<Path> openState = path -> false;
        private Predicate<Path> modifiedState = path -> false;
        private Predicate<Path> bookmarkState = path -> false;
        private Predicate<Path> noteState = path -> false;
        private Map<Path, GitFileStatus> gitState = Map.of();
        private Set<Path> gitDirectories = Set.of();
        private Set<Path> openPaths = Set.of();
        private Set<Path> modifiedPaths = Set.of();
        private Set<Path> bookmarkedPaths = Set.of();
        private Set<Path> notedPaths = Set.of();
        private Set<Path> emphasized = Set.of();
        private FlowDirection flowDirection = FlowDirection.RIGHT_TO_LEFT;
        private Path selected;
        private Path hovered;
        private Consumer<ProjectMapModel.Entry> onActivate = entry -> {};
        private Consumer<Path> onCloseColumn = path -> {};
        private Consumer<Path> onPreview = path -> {};
        private Consumer<Path> onSelectionChanged = path -> {};
        private Function<ProjectMapModel.Entry, ContextMenu> contextMenuFactory = entry -> null;
        private Runnable onZoomChanged = () -> {};
        private double zoom = 1.0;
        private double offsetX;
        private double offsetY;
        private double pressX;
        private double pressY;
        private double pressOffsetX;
        private double pressOffsetY;
        private double columnPressOffsetX;
        private double columnPressOffsetY;
        private ProjectMapModel.ColumnId draggedColumn;
        private OverviewBox overviewBox;
        private ContextMenu activeContextMenu;
        private Scene dismissScene;
        private EventHandler<MouseEvent> dismissFilter;
        private boolean panning;
        private boolean painting;
        private boolean viewportInitialized;
        private boolean initialFitPending;
        private int laidOutColumnCount;

        MapSurface() {
            // The project-tree class supplies the same per-editor-theme folder/file looked-up colors used
            // by PathCell. It has no TreeView skin effect on this Region.
            getStyleClass().addAll("project-map-surface", "project-tree");
            setClip(viewportClip);
            viewportClip.widthProperty().bind(widthProperty());
            viewportClip.heightProperty().bind(heightProperty());
            textMeasurer.setFont(Font.font("System", FontWeight.SEMI_BOLD, 12));
            iconRasterizer.setManaged(false);
            iconRasterizer.setMouseTransparent(true);
            iconRasterizer.setMinSize(ICON_SIZE, ICON_SIZE);
            iconRasterizer.setPrefSize(ICON_SIZE, ICON_SIZE);
            iconRasterizer.setMaxSize(ICON_SIZE, ICON_SIZE);
            iconRasterizer.resize(ICON_SIZE, ICON_SIZE);
            iconRasterizer.relocate(-ICON_SIZE * 2, -ICON_SIZE * 2);
            getChildren()
                    .addAll(
                            canvas,
                            iconRasterizer,
                            bgProbe,
                            surfaceProbe,
                            borderProbe,
                            textProbe,
                            mutedProbe,
                            accentProbe,
                            warningProbe,
                            successProbe,
                            folderProbe,
                            fileProbe,
                            oliveProbe,
                            violetProbe);
            setMinSize(80, 100);
            setFocusTraversable(true);
            setAccessibleRole(AccessibleRole.TREE_VIEW);
            setAccessibleHelp(tr("project.map.accessibleHelp"));
            nodeTooltip.setShowDelay(Duration.millis(350));
            nodeTooltip.setHideDelay(Duration.millis(100));
            nodeTooltip.setWrapText(true);
            nodeTooltip.setMaxWidth(520);
            Tooltip.install(this, nodeTooltip);

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
                    successProbe,
                    folderProbe,
                    fileProbe,
                    oliveProbe,
                    violetProbe)) {
                probe.fillProperty().addListener((obs, old, value) -> {
                    iconImages.clear();
                    repaint();
                });
            }

            addEventHandler(MouseEvent.MOUSE_PRESSED, this::mousePressed);
            addEventHandler(MouseEvent.MOUSE_DRAGGED, this::mouseDragged);
            addEventHandler(MouseEvent.MOUSE_RELEASED, event -> {
                panning = false;
                draggedColumn = null;
            });
            addEventHandler(MouseEvent.MOUSE_MOVED, this::mouseMoved);
            addEventHandler(MouseEvent.MOUSE_CLICKED, this::mouseClicked);
            addEventHandler(ContextMenuEvent.CONTEXT_MENU_REQUESTED, this::contextMenuRequested);
            addEventHandler(ScrollEvent.SCROLL, this::scrolled);
            addEventFilter(KeyEvent.KEY_PRESSED, this::keyPressed);
        }

        void setOnActivate(Consumer<ProjectMapModel.Entry> onActivate) {
            this.onActivate = onActivate;
        }

        void setOnCloseColumn(Consumer<Path> callback) {
            onCloseColumn = callback == null ? path -> {} : callback;
        }

        void setOnPreview(Consumer<Path> onPreview) {
            this.onPreview = onPreview == null ? path -> {} : onPreview;
        }

        void setOnSelectionChanged(Consumer<Path> callback) {
            onSelectionChanged = callback == null ? path -> {} : callback;
        }

        void setContextMenuFactory(Function<ProjectMapModel.Entry, ContextMenu> factory) {
            contextMenuFactory = factory == null ? entry -> null : factory;
        }

        void setOnZoomChanged(Runnable onZoomChanged) {
            this.onZoomChanged = onZoomChanged;
        }

        void setStatusSuppliers(Predicate<Path> open, Predicate<Path> modified) {
            openState = open;
            modifiedState = modified;
        }

        void setMarkerSuppliers(Predicate<Path> bookmarked, Predicate<Path> noted) {
            bookmarkState = bookmarked == null ? path -> false : bookmarked;
            noteState = noted == null ? path -> false : noted;
        }

        void setEntries(List<ProjectMapModel.Entry> entries, Set<Path> expanded) {
            int oldColumnCount = laidOutColumnCount;
            this.entries = entries == null ? List.of() : List.copyOf(entries);
            laidOutColumnCount = this.entries.stream()
                    .map(entry -> new ProjectMapModel.ColumnId(entry.depth(), entry.parent()))
                    .collect(java.util.stream.Collectors.toSet())
                    .size();
            measuredLabelWidths.clear();
            expandedSnapshot = expanded == null ? Set.of() : Set.copyOf(expanded);
            clearNodeTooltip();
            if (selected == null
                    || this.entries.stream().noneMatch(entry -> entry.path().equals(selected))) {
                selected =
                        this.entries.isEmpty() ? null : this.entries.getFirst().path();
            }
            snapshotStates();
            recomputeEmphasis();
            syncColumnControls();
            updateAccessibleText();
            repaint();
            if (!viewportInitialized && !this.entries.isEmpty()) {
                viewportInitialized = true;
                initialFitPending = true;
                Platform.runLater(this::fitIfPending);
            } else if (laidOutColumnCount > oldColumnCount) {
                // A freshly opened column should arrive inside the viewport and keep the complete path
                // centred. This also prevents reverse/vertical flows from placing it above existing cards.
                Platform.runLater(this::fitContent);
            }
        }

        void resetForRoot() {
            for (ColumnControls controls : columnControls.values()) {
                getChildren().removeAll(controls.filter(), controls.showHidden(), controls.pin(), controls.close());
            }
            columnControls.clear();
            columnLayouts.clear();
            columnQueries.clear();
            columnShowHidden.clear();
            measuredLabelWidths.clear();
            viewportInitialized = false;
            initialFitPending = false;
            resetViewport();
        }

        void clearColumnFilters() {
            for (ColumnControls controls : columnControls.values()) {
                controls.filter().clear();
                controls.showHidden().setSelected(true);
            }
            columnQueries.clear();
            columnShowHidden.clear();
            measuredLabelWidths.clear();
            repaint();
        }

        void setFlowDirection(FlowDirection requested) {
            FlowDirection next = requested == null ? FlowDirection.RIGHT_TO_LEFT : requested;
            if (flowDirection == next) {
                return;
            }
            flowDirection = next;
            for (ColumnLayout layout : columnLayouts.values()) {
                layout.x = 0;
                layout.y = 0;
                layout.locked = false;
            }
            columnControls.values().forEach(controls -> controls.pin().setSelected(false));
            repaint();
            Platform.runLater(this::fitContent);
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
            updateAccessibleText();
            entries.stream()
                    .filter(entry -> entry.path().equals(hovered))
                    .findFirst()
                    .ifPresent(entry -> nodeTooltip.setText(tooltipText(entry)));
            repaint();
        }

        void dispose() {
            dismissContextMenu();
            clearNodeTooltip();
            Tooltip.uninstall(this, nodeTooltip);
            columnControls.clear();
            columnLayouts.clear();
            columnQueries.clear();
            columnShowHidden.clear();
            measuredLabelWidths.clear();
        }

        void repaint() {
            if (painting || getWidth() <= 0 || getHeight() <= 0) {
                return;
            }
            painting = true;
            try {
                paint();
            } finally {
                painting = false;
            }
        }

        /**
         * Renders every laid-out column into one bounded image, independent of the current pan and zoom.
         * The live Canvas is restored before this method returns, so exporting has no visible navigation
         * side effects. Interactive controls and the overview are intentionally omitted from printed output.
         */
        Image snapshotContent() {
            repaint();
            if (entries.isEmpty() || columnBoxes.isEmpty()) {
                return null;
            }
            double minWorldX = columnBoxes.stream()
                    .mapToDouble(box -> (box.x() - offsetX) / zoom)
                    .min()
                    .orElse(0);
            double minWorldY = columnBoxes.stream()
                    .mapToDouble(box -> (box.y() - offsetY) / zoom)
                    .min()
                    .orElse(0);
            double maxWorldX = columnBoxes.stream()
                    .mapToDouble(box -> (box.x() + box.width() - offsetX) / zoom)
                    .max()
                    .orElse(minWorldX + 1);
            double maxWorldY = columnBoxes.stream()
                    .mapToDouble(box -> (box.y() + box.height() - offsetY) / zoom)
                    .max()
                    .orElse(minWorldY + 1);
            double contentWidth = Math.max(1, maxWorldX - minWorldX);
            double contentHeight = Math.max(1, maxWorldY - minWorldY);
            double availableDimension = MAX_OUTPUT_DIMENSION - OUTPUT_MARGIN * 2;
            double outputScale = Math.min(
                    OUTPUT_RENDER_SCALE,
                    Math.min(
                            Math.min(availableDimension / contentWidth, availableDimension / contentHeight),
                            Math.sqrt(MAX_OUTPUT_PIXELS / (contentWidth * contentHeight))));
            if (!Double.isFinite(outputScale) || outputScale <= 0) {
                return null;
            }
            int outputWidth = Math.max(1, Math.min((int) MAX_OUTPUT_DIMENSION, (int)
                    Math.ceil(contentWidth * outputScale + OUTPUT_MARGIN * 2)));
            int outputHeight = Math.max(1, Math.min((int) MAX_OUTPUT_DIMENSION, (int)
                    Math.ceil(contentHeight * outputScale + OUTPUT_MARGIN * 2)));

            double liveCanvasWidth = canvas.getWidth();
            double liveCanvasHeight = canvas.getHeight();
            double liveZoom = zoom;
            double liveOffsetX = offsetX;
            double liveOffsetY = offsetY;
            Path liveHovered = hovered;
            painting = true;
            try {
                canvas.setWidth(outputWidth);
                canvas.setHeight(outputHeight);
                zoom = outputScale;
                offsetX = OUTPUT_MARGIN - minWorldX * outputScale;
                offsetY = OUTPUT_MARGIN - minWorldY * outputScale;
                hovered = null;
                paint();
                WritableImage image = new WritableImage(outputWidth, outputHeight);
                return canvas.snapshot(new SnapshotParameters(), image);
            } finally {
                canvas.setWidth(liveCanvasWidth);
                canvas.setHeight(liveCanvasHeight);
                zoom = liveZoom;
                offsetX = liveOffsetX;
                offsetY = liveOffsetY;
                hovered = liveHovered;
                try {
                    paint();
                } finally {
                    painting = false;
                }
            }
        }

        String zoomPercent() {
            return Math.round(zoom * 100) + "%";
        }

        void zoomBy(double factor) {
            setZoom(zoom * factor, getWidth() / 2.0, getHeight() / 2.0);
        }

        void fitContent() {
            if (columnBoxes.isEmpty() || getWidth() <= 0 || getHeight() <= 0) {
                return;
            }
            double minWorldX = columnBoxes.stream()
                    .mapToDouble(box -> (box.x() - offsetX) / zoom)
                    .min()
                    .orElse(0);
            double minWorldY = columnBoxes.stream()
                    .mapToDouble(box -> (box.y() - offsetY) / zoom)
                    .min()
                    .orElse(0);
            double maxWorldX = columnBoxes.stream()
                    .mapToDouble(box -> (box.x() + box.width() - offsetX) / zoom)
                    .max()
                    .orElse(getWidth());
            double maxWorldY = columnBoxes.stream()
                    .mapToDouble(box -> (box.y() + box.height() - offsetY) / zoom)
                    .max()
                    .orElse(getHeight());
            double margin = 28;
            double contentWidth = Math.max(1, maxWorldX - minWorldX);
            double contentHeight = Math.max(1, maxWorldY - minWorldY);
            zoom = Math.max(
                    MIN_ZOOM,
                    Math.min(
                            1.15,
                            Math.min(
                                    (getWidth() - margin * 2) / contentWidth,
                                    (getHeight() - margin * 2) / contentHeight)));
            offsetX = (getWidth() - contentWidth * zoom) / 2 - minWorldX * zoom;
            offsetY = (getHeight() - contentHeight * zoom) / 2 - minWorldY * zoom;
            onZoomChanged.run();
            repaint();
        }

        void centerSelection() {
            NodeBox box = boxes.stream()
                    .filter(candidate -> candidate.entry().path().equals(selected))
                    .findFirst()
                    .orElse(null);
            if (box == null) {
                return;
            }
            offsetX += getWidth() / 2 - (box.x() + box.width() / 2);
            offsetY += getHeight() / 2 - (box.y() + box.height() / 2);
            repaint();
        }

        void resetViewport() {
            zoom = 1.0;
            offsetX = 0;
            offsetY = 0;
            for (ColumnLayout layout : columnLayouts.values()) {
                layout.x = 0;
                layout.y = 0;
                layout.locked = false;
            }
            columnControls.values().forEach(controls -> controls.pin().setSelected(false));
            onZoomChanged.run();
            repaint();
        }

        @Override
        protected void layoutChildren() {
            canvas.setWidth(Math.max(1, getWidth()));
            canvas.setHeight(Math.max(1, getHeight()));
            repaint();
            fitIfPending();
        }

        private void fitIfPending() {
            if (initialFitPending && getWidth() > 1 && getHeight() > 1 && !columnBoxes.isEmpty()) {
                initialFitPending = false;
                fitContent();
            }
        }

        private void syncColumnControls() {
            Set<ProjectMapModel.ColumnId> ids = entries.stream()
                    .filter(entry -> entry.depth() > 0)
                    .map(entry -> new ProjectMapModel.ColumnId(entry.depth(), entry.parent()))
                    .collect(java.util.stream.Collectors.toSet());
            List<ProjectMapModel.ColumnId> removed = columnControls.keySet().stream()
                    .filter(id -> !ids.contains(id))
                    .toList();
            for (ProjectMapModel.ColumnId id : removed) {
                ColumnControls controls = columnControls.remove(id);
                getChildren().removeAll(controls.filter(), controls.showHidden(), controls.pin(), controls.close());
                columnLayouts.remove(id);
                columnQueries.remove(id);
                columnShowHidden.remove(id);
            }
            for (ProjectMapModel.ColumnId id : ids) {
                if (columnControls.containsKey(id)) {
                    continue;
                }
                TextField filter = new TextField();
                filter.getStyleClass().add("project-map-column-filter");
                filter.setPromptText(tr("project.map.column.filter"));
                filter.setAccessibleHelp(tr("project.map.column.filterHelp"));
                filter.textProperty().addListener((obs, old, value) -> {
                    if (value == null || value.isBlank()) {
                        columnQueries.remove(id);
                    } else {
                        columnQueries.put(id, value);
                    }
                    repaint();
                    if (selected != null
                            && boxes.stream()
                                    .noneMatch(box -> box.entry().path().equals(selected))) {
                        boxes.stream()
                                .filter(box -> columnId(box.entry()).equals(id))
                                .findFirst()
                                .ifPresent(box -> select(box.entry().path()));
                    }
                });
                filter.setOnAction(event -> requestFocus());

                CheckBox showHidden = new CheckBox(tr("project.map.column.showHidden"));
                showHidden.getStyleClass().add("project-map-column-hidden");
                showHidden.setSelected(true);
                showHidden.setTooltip(new Tooltip(tr("project.map.column.showHiddenHelp")));
                showHidden.setAccessibleText(tr("project.map.column.showHidden"));
                showHidden.selectedProperty().addListener((obs, old, selected) -> {
                    columnShowHidden.put(id, selected);
                    repaint();
                    if (this.selected != null
                            && boxes.stream()
                                    .noneMatch(box -> box.entry().path().equals(this.selected))) {
                        boxes.stream()
                                .filter(box -> columnId(box.entry()).equals(id))
                                .findFirst()
                                .ifPresent(box -> select(box.entry().path()));
                    }
                });

                ToggleButton pin = new ToggleButton();
                pin.getStyleClass().add("project-map-column-pin");
                pin.setGraphic(Icons.pin());
                pin.setTooltip(new Tooltip(tr("project.map.column.pin")));
                pin.setAccessibleText(tr("project.map.column.pin"));
                pin.selectedProperty().addListener((obs, old, selected) -> {
                    columnLayouts.computeIfAbsent(id, ignored -> new ColumnLayout()).locked = selected;
                    pin.setTooltip(new Tooltip(tr(selected ? "project.map.column.unpin" : "project.map.column.pin")));
                });

                Button close = new Button("×");
                close.getStyleClass().add("project-map-column-close");
                close.setTooltip(new Tooltip(tr("project.map.column.close")));
                close.setAccessibleText(tr("project.map.column.close"));
                close.setOnAction(event -> onCloseColumn.accept(id.parent()));

                columnControls.put(id, new ColumnControls(filter, showHidden, pin, close));
                getChildren().addAll(filter, showHidden, pin, close);
            }
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
            columnBoxes.clear();
            overviewBox = null;
            if (entries.isEmpty()) {
                columnControls.values().forEach(controls -> {
                    controls.filter().setVisible(false);
                    controls.showHidden().setVisible(false);
                    controls.pin().setVisible(false);
                    controls.close().setVisible(false);
                });
                g.setFill(color(mutedProbe, Color.web("#8b949e")));
                g.setFont(Font.font(13));
                g.fillText(tr("project.map.empty"), 18, 28);
                return;
            }

            List<ProjectMapModel.Column> columns =
                    ProjectMapModel.columnsById(entries, columnQueries, columnShowHidden);
            Map<ProjectMapModel.ColumnId, Double> nodeWidths = new HashMap<>();
            for (ProjectMapModel.Column column : columns) {
                nodeWidths.put(column.id(), nodeWidthFor(column));
            }
            Map<Path, NodeBox> byPath = new HashMap<>();
            Map<ProjectMapModel.ColumnId, ColumnBox> columnsById = new HashMap<>();
            Map<Integer, Double> nextCrossEdge = new HashMap<>();
            for (ProjectMapModel.Column column : columns) {
                int depth = column.depth();
                ProjectMapModel.ColumnId id = column.id();
                ColumnLayout layout = columnLayouts.computeIfAbsent(id, ignored -> new ColumnLayout());
                double headerHeight = columnHeaderHeight(depth);
                boolean verticalFlow = isVerticalFlow();
                double direction = isReverseFlow() ? -1 : 1;
                double nodeWidth = nodeWidths.get(id);
                double depthStep = verticalFlow
                        ? COLUMN_TOP_INSET + COLUMN_HEADER_HEIGHT + NODE_HEIGHT + COLUMN_BOTTOM_PADDING + COLUMN_GAP
                        : 0;
                int rowCount = column.entries().size();
                double rowsHeight = rowCount == 0 ? 0 : rowCount * NODE_HEIGHT + (rowCount - 1) * ROW_GAP;
                double rowsWidth = rowCount == 0 ? nodeWidth : rowCount * nodeWidth + (rowCount - 1) * ROW_GAP;
                double cardWidth = verticalFlow ? rowsWidth + 20 : nodeWidth + 20;
                double cardHeight = COLUMN_TOP_INSET
                        + headerHeight
                        + (verticalFlow ? NODE_HEIGHT : rowsHeight)
                        + COLUMN_BOTTOM_PADDING;
                double baseWorldX = WORLD_PADDING;
                double baseWorldY = WORLD_PADDING + (verticalFlow ? direction * depth * depthStep : 0);
                NodeBox parent = byPath.get(column.parent());
                ColumnBox parentColumn = parent == null ? null : columnsById.get(columnId(parent.entry()));
                if (parent != null && parentColumn != null) {
                    // Centre the new card on the item that opened it along the cross-axis. Along the
                    // flow axis, start beyond the parent's actual card bounds so differently sized
                    // columns cannot overlap.
                    double parentCenterX = (parent.x() + parent.width() / 2 - offsetX) / zoom;
                    double parentCenterY = (parent.y() + parent.height() / 2 - offsetY) / zoom;
                    double horizontalCardGap = COLUMN_GAP - 20;
                    switch (flowDirection) {
                        case LEFT_TO_RIGHT -> {
                            baseWorldX =
                                    (parentColumn.x() + parentColumn.width() - offsetX) / zoom + horizontalCardGap + 10;
                            baseWorldY = parentCenterY + COLUMN_TOP_INSET - cardHeight / 2;
                        }
                        case RIGHT_TO_LEFT -> {
                            baseWorldX = (parentColumn.x() - offsetX) / zoom - horizontalCardGap + 10 - cardWidth;
                            baseWorldY = parentCenterY + COLUMN_TOP_INSET - cardHeight / 2;
                        }
                        case TOP_TO_BOTTOM -> {
                            baseWorldX = parentCenterX + 10 - cardWidth / 2;
                            baseWorldY = (parentColumn.y() + parentColumn.height() - offsetY) / zoom
                                    + COLUMN_GAP
                                    + COLUMN_TOP_INSET;
                        }
                        case BOTTOM_TO_TOP -> {
                            baseWorldX = parentCenterX + 10 - cardWidth / 2;
                            baseWorldY =
                                    (parentColumn.y() - offsetY) / zoom - COLUMN_GAP + COLUMN_TOP_INSET - cardHeight;
                        }
                    }
                }
                double columnWorldX = baseWorldX + layout.x;
                double columnWorldY = baseWorldY + layout.y;
                if (parentColumn != null) {
                    // Manual column offsets may move a child farther out or along the cross-axis, but
                    // never back through its parent column.
                    switch (flowDirection) {
                        case LEFT_TO_RIGHT -> columnWorldX = Math.max(columnWorldX, baseWorldX);
                        case RIGHT_TO_LEFT -> columnWorldX = Math.min(columnWorldX, baseWorldX);
                        case TOP_TO_BOTTOM -> columnWorldY = Math.max(columnWorldY, baseWorldY);
                        case BOTTOM_TO_TOP -> columnWorldY = Math.min(columnWorldY, baseWorldY);
                    }
                }
                if (parentColumn != null) {
                    if (verticalFlow) {
                        double minimum = nextCrossEdge.getOrDefault(depth, Double.NEGATIVE_INFINITY);
                        double cardLeft = columnWorldX - 10;
                        if (cardLeft < minimum) {
                            columnWorldX += minimum - cardLeft;
                        }
                        nextCrossEdge.put(depth, columnWorldX - 10 + cardWidth + COLUMN_GAP);
                    } else {
                        double minimum = nextCrossEdge.getOrDefault(depth, Double.NEGATIVE_INFINITY);
                        double cardTop = columnWorldY - COLUMN_TOP_INSET;
                        if (cardTop < minimum) {
                            columnWorldY += minimum - cardTop;
                        }
                        nextCrossEdge.put(depth, columnWorldY - COLUMN_TOP_INSET + cardHeight + COLUMN_GAP);
                    }
                }
                for (int row = 0; row < column.entries().size(); row++) {
                    double worldX = columnWorldX + (verticalFlow ? row * (nodeWidth + ROW_GAP) : 0);
                    double worldY = columnWorldY + headerHeight + (verticalFlow ? 0 : row * (NODE_HEIGHT + ROW_GAP));
                    NodeBox box = new NodeBox(
                            column.entries().get(row),
                            screenX(worldX),
                            screenY(worldY),
                            nodeWidth * zoom,
                            NODE_HEIGHT * zoom);
                    boxes.add(box);
                    byPath.put(box.entry().path(), box);
                }
                ColumnBox columnBox = new ColumnBox(
                        column,
                        screenX(columnWorldX - 10),
                        screenY(columnWorldY - COLUMN_TOP_INSET),
                        cardWidth * zoom,
                        cardHeight * zoom);
                columnBoxes.add(columnBox);
                columnsById.put(id, columnBox);
            }

            drawColumns(g);
            g.setStroke(color(accentProbe, Color.web("#58a6ff")));
            for (NodeBox child : boxes) {
                NodeBox parent = byPath.get(child.entry().parent());
                if (parent == null || !inViewport(parent, width, height) && !inViewport(child, width, height)) {
                    continue;
                }
                boolean selectedPath = isOnSelectedPath(child.entry().path());
                double alpha = selectedPath ? 0.95 : prominence(child.entry().path()) ? 0.55 : 0.12;
                g.setGlobalAlpha(alpha);
                g.setLineWidth(Math.max(1, (selectedPath ? 2.25 : 1.15) * zoom));
                drawConnector(g, parent, child);
            }
            for (NodeBox box : boxes) {
                if (inViewport(box, width, height)) {
                    drawNode(g, box);
                }
            }
            drawOverview(g, width, height);
            layoutColumnControls();
            g.setGlobalAlpha(1);
        }

        private double nodeWidthFor(ProjectMapModel.Column column) {
            double required = measuredLabelWidth(columnTitle(column)) + 52;
            for (ProjectMapModel.Entry entry : entries) {
                if (columnId(entry).equals(column.id())) {
                    // File rows reserve a fixed tail for status dots, bookmark/note badges and Preview.
                    required = Math.max(required, measuredLabelWidth(entry.name()) + (entry.directory() ? 57 : 92));
                }
            }
            return Math.max(MIN_NODE_WIDTH, Math.ceil(required));
        }

        private double measuredLabelWidth(String value) {
            return measuredLabelWidths.computeIfAbsent(value == null ? "" : value, text -> {
                textMeasurer.setText(text);
                return textMeasurer.getLayoutBounds().getWidth();
            });
        }

        private boolean isVerticalFlow() {
            return flowDirection == FlowDirection.TOP_TO_BOTTOM || flowDirection == FlowDirection.BOTTOM_TO_TOP;
        }

        private boolean isReverseFlow() {
            return flowDirection == FlowDirection.RIGHT_TO_LEFT || flowDirection == FlowDirection.BOTTOM_TO_TOP;
        }

        private void drawConnector(GraphicsContext g, NodeBox parent, NodeBox child) {
            double x1;
            double y1;
            double x2;
            double y2;
            if (flowDirection == FlowDirection.LEFT_TO_RIGHT) {
                x1 = parent.x() + parent.width();
                y1 = parent.y() + parent.height() / 2;
                x2 = child.x();
                y2 = child.y() + child.height() / 2;
            } else if (flowDirection == FlowDirection.RIGHT_TO_LEFT) {
                x1 = parent.x();
                y1 = parent.y() + parent.height() / 2;
                x2 = child.x() + child.width();
                y2 = child.y() + child.height() / 2;
            } else if (flowDirection == FlowDirection.TOP_TO_BOTTOM) {
                x1 = parent.x() + parent.width() / 2;
                y1 = parent.y() + parent.height();
                x2 = child.x() + child.width() / 2;
                y2 = child.y();
            } else {
                x1 = parent.x() + parent.width() / 2;
                y1 = parent.y();
                x2 = child.x() + child.width() / 2;
                y2 = child.y() + child.height();
            }
            g.beginPath();
            g.moveTo(x1, y1);
            if (isVerticalFlow()) {
                double bend = Math.max(12, Math.abs(y2 - y1) * 0.48);
                double sign = Math.signum(y2 - y1);
                g.bezierCurveTo(x1, y1 + sign * bend, x2, y2 - sign * bend, x2, y2);
            } else {
                double bend = Math.max(12, Math.abs(x2 - x1) * 0.48);
                double sign = Math.signum(x2 - x1);
                g.bezierCurveTo(x1 + sign * bend, y1, x2 - sign * bend, y2, x2, y2);
            }
            g.stroke();
        }

        private String columnTitle(ProjectMapModel.Column column) {
            if (column.depth() == 0) {
                return tr("project.map.column.project");
            }
            Path parent = column.parent();
            if (parent == null) {
                return tr("project.map.column.level", column.depth());
            }
            Path name = parent.getFileName();
            return name == null ? parent.toString() : name.toString();
        }

        private double columnHeaderHeight(int depth) {
            return depth == 0 ? 28 : COLUMN_HEADER_HEIGHT;
        }

        private void layoutColumnControls() {
            Set<ProjectMapModel.ColumnId> visibleIds = new HashSet<>();
            for (ColumnBox box : columnBoxes) {
                int depth = box.column().depth();
                ProjectMapModel.ColumnId id = box.column().id();
                visibleIds.add(id);
                ColumnControls controls = columnControls.get(id);
                if (controls == null) {
                    continue;
                }
                double closeSize = Math.max(18, 20 * zoom);
                boolean closeFits = zoom >= 0.65 && box.width() >= closeSize + 42;
                controls.close().setVisible(closeFits);
                if (closeFits) {
                    controls.close().resize(closeSize, closeSize);
                    controls.close().relocate(box.x() + box.width() - closeSize - 5 * zoom, box.y() + 2 * zoom);
                }
                double controlY = box.y() + 25 * zoom;
                double controlHeight = Math.max(MIN_COLUMN_CONTROL_HEIGHT, 24 * zoom);
                double pinWidth = Math.max(MIN_COLUMN_PIN_WIDTH, 25 * zoom);
                double hiddenWidth = Math.max(MIN_COLUMN_HIDDEN_WIDTH, 55 * zoom);
                double left = box.x() + 10 * zoom;
                double right = box.x() + box.width() - 8 * zoom;
                double availableWidth = right - left;
                double availableHeight = box.y() + columnHeaderHeight(depth) * zoom - controlY;
                double minimumWidth = MIN_COLUMN_FILTER_WIDTH + hiddenWidth + pinWidth + COLUMN_CONTROL_GAP * 2;
                boolean controlsFit = availableWidth >= minimumWidth && availableHeight >= controlHeight;
                setColumnDetailControlsVisible(controls, controlsFit);
                if (!controlsFit) {
                    continue;
                }
                controls.filter()
                        .resize(
                                Math.max(
                                        MIN_COLUMN_FILTER_WIDTH,
                                        Math.min(
                                                150 * zoom,
                                                availableWidth - hiddenWidth - pinWidth - COLUMN_CONTROL_GAP * 2)),
                                controlHeight);
                controls.filter().relocate(left, controlY);
                double hiddenX =
                        controls.filter().getLayoutX() + controls.filter().getWidth() + COLUMN_CONTROL_GAP;
                controls.showHidden().resize(hiddenWidth, controlHeight);
                controls.showHidden().relocate(hiddenX, controlY);
                controls.pin().resize(pinWidth, controlHeight);
                controls.pin()
                        .relocate(Math.min(right - pinWidth, hiddenX + hiddenWidth + COLUMN_CONTROL_GAP), controlY);
            }
            columnControls.forEach((id, controls) -> {
                if (!visibleIds.contains(id)) {
                    setColumnControlsVisible(controls, false);
                }
            });
        }

        private void setColumnDetailControlsVisible(ColumnControls controls, boolean visible) {
            controls.filter().setVisible(visible);
            controls.showHidden().setVisible(visible);
            controls.pin().setVisible(visible);
        }

        private void setColumnControlsVisible(ColumnControls controls, boolean visible) {
            setColumnDetailControlsVisible(controls, visible);
            controls.close().setVisible(visible);
        }

        /** Compact overview for large or manually spread layouts; the bright rectangle is the viewport. */
        private void drawOverview(GraphicsContext g, double viewportWidth, double viewportHeight) {
            if (columnBoxes.isEmpty()) {
                return;
            }
            double minX = columnBoxes.stream().mapToDouble(ColumnBox::x).min().orElse(0);
            double minY = columnBoxes.stream().mapToDouble(ColumnBox::y).min().orElse(0);
            double maxX = columnBoxes.stream()
                    .mapToDouble(box -> box.x() + box.width())
                    .max()
                    .orElse(viewportWidth);
            double maxY = columnBoxes.stream()
                    .mapToDouble(box -> box.y() + box.height())
                    .max()
                    .orElse(viewportHeight);
            if (minX >= 0 && minY >= 0 && maxX <= viewportWidth && maxY <= viewportHeight) {
                return;
            }
            double overviewWidth = Math.min(150, Math.max(90, viewportWidth * 0.18));
            double overviewHeight = 86;
            double x = viewportWidth - overviewWidth - 10;
            double y = viewportHeight - overviewHeight - 10;
            double contentWidth = Math.max(1, maxX - minX);
            double contentHeight = Math.max(1, maxY - minY);
            double scale = Math.min((overviewWidth - 10) / contentWidth, (overviewHeight - 10) / contentHeight);
            overviewBox = new OverviewBox(x, y, overviewWidth, overviewHeight, minX, minY, scale);
            g.setGlobalAlpha(0.9);
            g.setFill(color(surfaceProbe, Color.web("#161d27")));
            g.fillRoundRect(x, y, overviewWidth, overviewHeight, 8, 8);
            g.setStroke(color(borderProbe, Color.web("#303946")));
            g.strokeRoundRect(x, y, overviewWidth, overviewHeight, 8, 8);
            g.setFill(color(mutedProbe, Color.web("#8b949e")));
            for (ColumnBox box : columnBoxes) {
                g.fillRoundRect(
                        x + 5 + (box.x() - minX) * scale,
                        y + 5 + (box.y() - minY) * scale,
                        Math.max(2, box.width() * scale),
                        Math.max(3, box.height() * scale),
                        2,
                        2);
            }
            g.setStroke(color(accentProbe, Color.web("#58a6ff")));
            g.setLineWidth(1.5);
            g.strokeRect(
                    x + 5 + (0 - minX) * scale,
                    y + 5 + (0 - minY) * scale,
                    Math.min(overviewWidth - 10, viewportWidth * scale),
                    Math.min(overviewHeight - 10, viewportHeight * scale));
            g.setGlobalAlpha(1);
        }

        private void drawColumns(GraphicsContext g) {
            Color surface = color(surfaceProbe, Color.web("#161d27"));
            Color border = color(borderProbe, Color.web("#303946"));
            for (ColumnBox box : columnBoxes) {
                ProjectMapModel.Column column = box.column();
                double x = box.x();
                double y = box.y();
                double w = box.width();
                double h = box.height();
                g.setGlobalAlpha(0.64);
                g.setFill(surface);
                g.fillRoundRect(x, y, w, h, 12 * zoom, 12 * zoom);
                g.setStroke(border);
                g.setLineWidth(1);
                g.strokeRoundRect(x, y, w, h, 12 * zoom, 12 * zoom);

                g.setGlobalAlpha(0.88);
                g.setFill(color(textProbe, Color.web("#d8dee9")));
                g.setFont(Font.font("System", FontWeight.SEMI_BOLD, Math.max(9, 11 * zoom)));
                String title = columnTitle(column);
                String count = column.entries().size() == column.totalEntries()
                        ? String.valueOf(column.totalEntries())
                        : column.entries().size() + "/" + column.totalEntries();
                g.fillText(title, x + 10 * zoom, y + 17 * zoom);
                g.setFill(color(mutedProbe, Color.web("#8b949e")));
                g.setFont(Font.font(Math.max(8, 9 * zoom)));
                double countOffset = column.depth() == 0 ? 30 : 52;
                g.fillText(count, x + w - countOffset * zoom, y + 17 * zoom, 24 * zoom);
            }
            g.setGlobalAlpha(1);
        }

        private void drawNode(GraphicsContext g, NodeBox box) {
            ProjectMapModel.Entry entry = box.entry();
            boolean isSelected = entry.path().equals(selected);
            boolean isHovered = entry.path().equals(hovered);
            boolean selectedPath = isOnSelectedPath(entry.path());
            double alpha = prominence(entry.path()) || isSelected ? 1.0 : 0.2;
            g.setGlobalAlpha(alpha);
            Color accent = color(accentProbe, Color.web("#388bfd"));
            Color fill = isSelected ? accent : color(surfaceProbe, Color.web("#202938"));
            if (isHovered && !isSelected) {
                fill = mix(fill, accent, 0.16);
            }
            g.setFill(fill);
            g.fillRoundRect(box.x(), box.y(), box.width(), box.height(), 8 * zoom, 8 * zoom);
            g.setStroke(isSelected || isHovered || selectedPath ? accent : color(borderProbe, Color.web("#3a4554")));
            g.setLineWidth((isSelected ? 1.8 : selectedPath ? 1.35 : 1.0) * zoom);
            g.strokeRoundRect(box.x(), box.y(), box.width(), box.height(), 8 * zoom, 8 * zoom);
            drawOpenMarker(g, entry, box, isSelected);

            double iconX = box.x() + 7 * zoom;
            double iconY = box.y() + 6 * zoom;
            drawIcon(g, entry, iconX, iconY);
            g.setFill(
                    isSelected
                            ? Color.WHITE
                            : openPaths.contains(entry.path()) ? accent : color(textProbe, Color.web("#d8dee9")));
            g.setFont(Font.font("System", isSelected ? FontWeight.SEMI_BOLD : FontWeight.NORMAL, 12 * zoom));
            g.fillText(entry.name(), box.x() + 31 * zoom, box.y() + 20.5 * zoom);

            drawStatusDots(g, entry, box);
            if (!entry.directory()) {
                drawFileMarkers(g, entry, box, isSelected);
                g.setFill(isSelected ? Color.WHITE : color(mutedProbe, Color.web("#8b949e")));
                g.setFont(Font.font(Math.max(9, 11 * zoom)));
                g.fillText("◉", box.x() + box.width() - 21 * zoom, box.y() + 20.5 * zoom);
            }
            if (entry.directory() && expandedSnapshot.contains(entry.path())) {
                g.setFill(isSelected ? Color.WHITE : color(mutedProbe, Color.web("#8b949e")));
                String indicator =
                        switch (flowDirection) {
                            case LEFT_TO_RIGHT -> "›";
                            case RIGHT_TO_LEFT -> "‹";
                            case TOP_TO_BOTTOM -> "⌄";
                            case BOTTOM_TO_TOP -> "⌃";
                        };
                g.fillText(indicator, box.x() + box.width() - 15 * zoom, box.y() + 21 * zoom);
            }
            g.setGlobalAlpha(1);
        }

        /** Open files get an unmistakable tab-colored rail in addition to their accent-colored label. */
        private void drawOpenMarker(GraphicsContext g, ProjectMapModel.Entry entry, NodeBox box, boolean selectedNode) {
            if (entry.directory() || !openPaths.contains(entry.path())) {
                return;
            }
            g.setFill(selectedNode ? Color.WHITE : color(accentProbe, Color.web("#388bfd")));
            g.fillRoundRect(
                    box.x() + 2 * zoom, box.y() + 7 * zoom, 3 * zoom, box.height() - 14 * zoom, 3 * zoom, 3 * zoom);
        }

        private void drawIcon(GraphicsContext g, ProjectMapModel.Entry entry, double x, double y) {
            Image icon = iconImage(entry);
            g.drawImage(icon, x, y, ICON_SIZE * zoom, ICON_SIZE * zoom);
        }

        private Image iconImage(ProjectMapModel.Entry entry) {
            String kind = entry.directory() ? "folder" : FileIcons.iconKeyFor(entry.name());
            String statusClass = iconStatusClass(entry);
            IconKey key = new IconKey(kind, statusClass);
            Image cached = iconImages.get(key);
            if (cached != null) {
                return cached;
            }
            Image image = rasterizeIcon(entry.name(), entry.directory(), statusClass);
            iconImages.put(key, image);
            return image;
        }

        private String iconStatusClass(ProjectMapModel.Entry entry) {
            if (entry.directory()) {
                return gitDirectories.contains(entry.path()) ? "git-status-dir-changed" : "";
            }
            if (modifiedPaths.contains(entry.path())) {
                return "modified-file";
            }
            GitFileStatus status = gitState.get(entry.path());
            return status == null ? "" : status.cssClass();
        }

        /** Snapshots the shared Project-tree SVG once; subsequent Canvas paints only blit the cached image. */
        private Image rasterizeIcon(String fileName, boolean directory, String statusClass) {
            Node glyph = FileIcons.forProjectItem(fileName, directory);
            StackPane cell = new StackPane(glyph);
            cell.getStyleClass().add(directory ? "folder-cell" : "file-cell");
            if (statusClass != null && !statusClass.isBlank()) {
                cell.getStyleClass().add(statusClass);
            }
            cell.setMinSize(ICON_SIZE, ICON_SIZE);
            cell.setPrefSize(ICON_SIZE, ICON_SIZE);
            cell.setMaxSize(ICON_SIZE, ICON_SIZE);
            iconRasterizer.getChildren().setAll(cell);
            iconRasterizer.applyCss();
            iconRasterizer.layout();

            SnapshotParameters parameters = new SnapshotParameters();
            parameters.setFill(Color.TRANSPARENT);
            parameters.setViewport(new Rectangle2D(0, 0, ICON_SIZE, ICON_SIZE));
            parameters.setTransform(javafx.scene.transform.Transform.scale(ICON_RASTER_SCALE, ICON_RASTER_SCALE));
            WritableImage image =
                    new WritableImage((int) (ICON_SIZE * ICON_RASTER_SCALE), (int) (ICON_SIZE * ICON_RASTER_SCALE));
            cell.snapshot(parameters, image);
            iconRasterizer.getChildren().clear();
            return image;
        }

        private void drawStatusDots(GraphicsContext g, ProjectMapModel.Entry entry, NodeBox box) {
            if (entry.directory()) {
                return;
            }
            List<Color> dots = new ArrayList<>(2);
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
            double markerWidth =
                    (bookmarkedPaths.contains(entry.path()) ? 13 : 0) + (notedPaths.contains(entry.path()) ? 13 : 0);
            // Keep the Preview target in the final 29 px and the semantic markers immediately to its left.
            double x = box.x() + box.width() - (33 + markerWidth) * zoom;
            for (Color dot : dots.reversed()) {
                g.setFill(dot);
                g.fillOval(x - 5 * zoom, box.y() + 13 * zoom, 5 * zoom, 5 * zoom);
                x -= 7 * zoom;
            }
        }

        /** Small bookmark and note outlines drawn directly on the Canvas (no scene-graph nodes per row). */
        private void drawFileMarkers(
                GraphicsContext g, ProjectMapModel.Entry entry, NodeBox box, boolean selectedNode) {
            if (entry.directory()) {
                return;
            }
            double x = box.x() + box.width() - 35 * zoom;
            double y = box.y() + 10 * zoom;
            g.setLineWidth(Math.max(1, 1.25 * zoom));
            if (notedPaths.contains(entry.path())) {
                g.setStroke(selectedNode ? Color.WHITE : color(accentProbe, Color.web("#388bfd")));
                g.strokeRoundRect(x - 4 * zoom, y, 8 * zoom, 7 * zoom, 2 * zoom, 2 * zoom);
                g.strokeLine(x - 2 * zoom, y + 7 * zoom, x - 4 * zoom, y + 9 * zoom);
                x -= 13 * zoom;
            }
            if (bookmarkedPaths.contains(entry.path())) {
                g.setStroke(selectedNode ? Color.WHITE : color(warningProbe, Color.web("#d29922")));
                g.beginPath();
                g.moveTo(x - 3.5 * zoom, y);
                g.lineTo(x + 3.5 * zoom, y);
                g.lineTo(x + 3.5 * zoom, y + 9 * zoom);
                g.lineTo(x, y + 6.5 * zoom);
                g.lineTo(x - 3.5 * zoom, y + 9 * zoom);
                g.closePath();
                g.stroke();
            }
        }

        private boolean prominence(Path path) {
            return !filters.active() || emphasized.contains(path);
        }

        private boolean isOnSelectedPath(Path path) {
            return selected != null && path != null && selected.startsWith(path);
        }

        private void snapshotStates() {
            Set<Path> open = new HashSet<>();
            Set<Path> modified = new HashSet<>();
            Set<Path> bookmarked = new HashSet<>();
            Set<Path> noted = new HashSet<>();
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
                if (safeTest(bookmarkState, entry.path())) {
                    bookmarked.add(entry.path());
                }
                if (safeTest(noteState, entry.path())) {
                    noted.add(entry.path());
                }
            }
            openPaths = Set.copyOf(open);
            modifiedPaths = Set.copyOf(modified);
            bookmarkedPaths = Set.copyOf(bookmarked);
            notedPaths = Set.copyOf(noted);
        }

        private void mousePressed(MouseEvent event) {
            if (isColumnControl(event.getTarget())) {
                panning = false;
                draggedColumn = null;
                return;
            }
            requestFocus();
            pressX = event.getX();
            pressY = event.getY();
            pressOffsetX = offsetX;
            pressOffsetY = offsetY;
            if (event.getButton() == MouseButton.PRIMARY && navigateFromOverview(event.getX(), event.getY())) {
                event.consume();
                return;
            }
            ColumnBox header = headerHit(event.getX(), event.getY());
            if (header != null && event.getButton() == MouseButton.PRIMARY) {
                ProjectMapModel.ColumnId id = header.column().id();
                ColumnLayout layout = columnLayouts.computeIfAbsent(id, ignored -> new ColumnLayout());
                if (!layout.locked) {
                    draggedColumn = id;
                    columnPressOffsetX = layout.x;
                    columnPressOffsetY = layout.y;
                    event.consume();
                    return;
                }
            }
            panning = hit(event.getX(), event.getY()) == null
                    && (event.getButton() == MouseButton.PRIMARY || event.getButton() == MouseButton.MIDDLE);
        }

        private void mouseDragged(MouseEvent event) {
            if (draggedColumn != null) {
                ColumnLayout layout = columnLayouts.get(draggedColumn);
                if (layout != null && !layout.locked) {
                    layout.x = columnPressOffsetX + (event.getX() - pressX) / zoom;
                    layout.y = columnPressOffsetY + (event.getY() - pressY) / zoom;
                    repaint();
                }
                event.consume();
                return;
            }
            if (!panning) {
                return;
            }
            offsetX = pressOffsetX + event.getX() - pressX;
            offsetY = pressOffsetY + event.getY() - pressY;
            repaint();
            event.consume();
        }

        private void mouseMoved(MouseEvent event) {
            if (isColumnControl(event.getTarget())) {
                return;
            }
            NodeBox hit = hit(event.getX(), event.getY());
            Path next = hit == null ? null : hit.entry().path();
            ColumnBox header = headerHit(event.getX(), event.getY());
            boolean movableHeader = header != null
                    && !columnLayouts.computeIfAbsent(header.column().id(), ignored -> new ColumnLayout()).locked;
            setCursor(movableHeader ? Cursor.MOVE : hit == null ? Cursor.DEFAULT : Cursor.HAND);
            if (overviewBox != null && overviewBox.contains(event.getX(), event.getY())) {
                setCursor(Cursor.HAND);
            }
            if (!java.util.Objects.equals(next, hovered)) {
                hovered = next;
                if (hit == null) {
                    clearNodeTooltip();
                } else {
                    nodeTooltip.setText(tooltipText(hit.entry()));
                }
                repaint();
            }
        }

        private void clearNodeTooltip() {
            hovered = null;
            nodeTooltip.hide();
            nodeTooltip.setText(null);
        }

        private String tooltipText(ProjectMapModel.Entry entry) {
            List<String> lines = new ArrayList<>(4);
            lines.add(entry.path().toString());
            String type = entry.symbolicLink()
                    ? tr("project.map.tooltip.symbolicLink")
                    : tr(entry.directory() ? "project.map.tooltip.folder" : "project.map.tooltip.file");
            lines.add(tr("project.map.tooltip.type", type));
            if (!entry.directory() && entry.size() >= 0) {
                lines.add(tr("project.map.tooltip.size", formatSize(entry.size())));
            }
            if (entry.modifiedMillis() >= 0) {
                lines.add(tr(
                        "project.map.tooltip.modified",
                        TOOLTIP_TIME.format(Instant.ofEpochMilli(entry.modifiedMillis()))));
            }

            List<String> statuses = new ArrayList<>(5);
            if (openPaths.contains(entry.path())) {
                statuses.add(tr("project.map.tooltip.open"));
            }
            if (modifiedPaths.contains(entry.path())) {
                statuses.add(tr("project.map.tooltip.unsaved"));
            }
            if (gitState.containsKey(entry.path()) || gitDirectories.contains(entry.path())) {
                statuses.add(tr("project.map.tooltip.gitChanged"));
            }
            if (bookmarkedPaths.contains(entry.path())) {
                statuses.add(tr("project.map.tooltip.bookmarked"));
            }
            if (notedPaths.contains(entry.path())) {
                statuses.add(tr("project.map.tooltip.personalNotes"));
            }
            if (!statuses.isEmpty()) {
                lines.add(tr("project.map.tooltip.status", String.join(", ", statuses)));
            }
            return String.join("\n", lines);
        }

        private static String formatSize(long bytes) {
            if (bytes < 1024) {
                return bytes + " B";
            }
            String[] units = {"kB", "MB", "GB", "TB"};
            double value = bytes;
            int unit = -1;
            do {
                value /= 1024.0;
                unit++;
            } while (value >= 1024 && unit < units.length - 1);
            return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
        }

        private void mouseClicked(MouseEvent event) {
            if (event.getButton() != MouseButton.PRIMARY || panning) {
                return;
            }
            if (overviewBox != null && overviewBox.contains(event.getX(), event.getY())) {
                event.consume();
                return;
            }
            NodeBox hit = hit(event.getX(), event.getY());
            if (hit == null) {
                return;
            }
            select(hit.entry().path());
            if (!hit.entry().directory() && previewHit(hit, event.getX())) {
                onPreview.accept(hit.entry().path());
            } else if (event.getClickCount() == 1) {
                onActivate.accept(hit.entry());
            }
            event.consume();
        }

        private boolean previewHit(NodeBox box, double x) {
            return x >= box.x() + box.width() - 29 * zoom;
        }

        private void contextMenuRequested(ContextMenuEvent event) {
            dismissContextMenu();
            if (overviewBox != null && overviewBox.contains(event.getX(), event.getY())) {
                return;
            }
            NodeBox hit = hit(event.getX(), event.getY());
            if (hit == null) {
                return;
            }
            requestFocus();
            clearNodeTooltip();
            select(hit.entry().path());
            ContextMenu menu = contextMenuFactory.apply(hit.entry());
            if (menu != null && !menu.getItems().isEmpty()) {
                activeContextMenu = menu;
                menu.addEventHandler(WindowEvent.WINDOW_HIDDEN, hidden -> {
                    if (activeContextMenu == menu) {
                        activeContextMenu = null;
                        removeDismissFilter();
                    }
                });
                menu.show(this, event.getScreenX(), event.getScreenY());
                installDismissFilter(menu);
            }
            event.consume();
        }

        /**
         * Guarantees that the map menu closes on the next press anywhere in its owner window. JavaFX's
         * popup auto-hide normally does this, but native popup grabs can miss a press on some platforms.
         * Arming on the next pulse prevents the opening gesture from immediately closing the menu.
         */
        private void installDismissFilter(ContextMenu menu) {
            Scene scene = getScene();
            if (scene == null) {
                return;
            }
            removeDismissFilter();
            EventHandler<MouseEvent> filter = pressed -> menu.hide();
            Platform.runLater(() -> {
                if (activeContextMenu != menu || !menu.isShowing()) {
                    return;
                }
                dismissScene = scene;
                dismissFilter = filter;
                scene.addEventFilter(MouseEvent.MOUSE_PRESSED, filter);
            });
        }

        private void dismissContextMenu() {
            ContextMenu menu = activeContextMenu;
            activeContextMenu = null;
            if (menu != null) {
                menu.hide();
            }
            removeDismissFilter();
        }

        private void removeDismissFilter() {
            if (dismissScene != null && dismissFilter != null) {
                dismissScene.removeEventFilter(MouseEvent.MOUSE_PRESSED, dismissFilter);
            }
            dismissScene = null;
            dismissFilter = null;
        }

        private void scrolled(ScrollEvent event) {
            if (event.isShiftDown()) {
                offsetX += event.getDeltaY();
                repaint();
            } else if (event.isAltDown()) {
                offsetY += event.getDeltaY();
                repaint();
            } else {
                double speed = event.isControlDown() || event.isMetaDown() ? 0.004 : 0.0025;
                double exponent = Math.max(-0.45, Math.min(0.45, event.getDeltaY() * speed));
                setZoom(zoom * Math.exp(exponent), event.getX(), event.getY());
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
            // Parent event filters also see keystrokes targeted at child controls. Text editing owns every
            // key while a per-column filter has focus; otherwise Backspace/Home/arrows become map commands.
            if (isColumnControl(event.getTarget())) {
                return;
            }
            if (entries.isEmpty()) {
                return;
            }
            if (event.isAltDown() && event.getCode() == KeyCode.LEFT) {
                moveHistory(-1);
                event.consume();
                return;
            }
            if (event.isAltDown() && event.getCode() == KeyCode.RIGHT) {
                moveHistory(1);
                event.consume();
                return;
            }
            if (event.isShortcutDown() && event.getCode() == KeyCode.DIGIT0) {
                fitContent();
                event.consume();
                return;
            }
            if (selected == null) {
                selected = entries.getFirst().path();
            }
            switch (event.getCode()) {
                case UP, DOWN, LEFT, RIGHT -> navigateArrow(event.getCode());
                case PAGE_UP -> moveSibling(-10);
                case PAGE_DOWN -> moveSibling(10);
                case BACK_SPACE -> selectParent();
                case ENTER, SPACE -> selectedEntry().ifPresent(onActivate);
                case HOME -> select(entries.getFirst().path());
                case ESCAPE -> fitContent();
                case SLASH -> focusSelectedColumnFilter();
                default -> {
                    if (event.isControlDown() && event.getCode() == KeyCode.N) {
                        moveSibling(1);
                    } else if (event.isControlDown() && event.getCode() == KeyCode.P) {
                        moveSibling(-1);
                    } else {
                        return;
                    }
                }
            }
            event.consume();
        }

        private void navigateArrow(KeyCode code) {
            switch (flowDirection) {
                case LEFT_TO_RIGHT -> {
                    if (code == KeyCode.UP) moveSibling(-1);
                    else if (code == KeyCode.DOWN) moveSibling(1);
                    else if (code == KeyCode.LEFT) selectParent();
                    else selectChildOrExpand();
                }
                case RIGHT_TO_LEFT -> {
                    if (code == KeyCode.UP) moveSibling(-1);
                    else if (code == KeyCode.DOWN) moveSibling(1);
                    else if (code == KeyCode.RIGHT) selectParent();
                    else selectChildOrExpand();
                }
                case TOP_TO_BOTTOM -> {
                    if (code == KeyCode.LEFT) moveSibling(-1);
                    else if (code == KeyCode.RIGHT) moveSibling(1);
                    else if (code == KeyCode.UP) selectParent();
                    else selectChildOrExpand();
                }
                case BOTTOM_TO_TOP -> {
                    if (code == KeyCode.LEFT) moveSibling(-1);
                    else if (code == KeyCode.RIGHT) moveSibling(1);
                    else if (code == KeyCode.DOWN) selectParent();
                    else selectChildOrExpand();
                }
            }
        }

        private void moveSibling(int delta) {
            ProjectMapModel.Entry current = selectedEntry().orElse(entries.getFirst());
            ProjectMapModel.ColumnId currentColumn = columnId(current);
            List<ProjectMapModel.Entry> column = boxes.stream()
                    .map(NodeBox::entry)
                    .filter(entry -> columnId(entry).equals(currentColumn))
                    .toList();
            if (column.isEmpty()) {
                return;
            }
            int index = column.indexOf(current);
            int start = index < 0 ? 0 : index;
            select(column.get(Math.floorMod(start + delta, column.size())).path());
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
            List<ProjectMapModel.Entry> children = boxes.stream()
                    .map(NodeBox::entry)
                    .filter(entry -> current.get().path().equals(entry.parent()))
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
            onSelectionChanged.accept(path);
        }

        private void focusSelectedColumnFilter() {
            selectedEntry().map(MapSurface::columnId).map(columnControls::get).ifPresent(controls -> {
                controls.filter().requestFocus();
                controls.filter().selectAll();
            });
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

        private ColumnBox headerHit(double x, double y) {
            for (int i = columnBoxes.size() - 1; i >= 0; i--) {
                ColumnBox box = columnBoxes.get(i);
                if (x >= box.x() && x <= box.x() + box.width() && y >= box.y() && y <= box.y() + 22 * zoom) {
                    return box;
                }
            }
            return null;
        }

        private boolean isColumnControl(Object target) {
            if (!(target instanceof Node node)) {
                return false;
            }
            for (Node current = node; current != null && current != this; current = current.getParent()) {
                if (current.getStyleClass().contains("project-map-column-filter")
                        || current.getStyleClass().contains("project-map-column-hidden")
                        || current.getStyleClass().contains("project-map-column-pin")
                        || current.getStyleClass().contains("project-map-column-close")) {
                    return true;
                }
            }
            return false;
        }

        private boolean navigateFromOverview(double x, double y) {
            OverviewBox overview = overviewBox;
            if (overview == null || !overview.contains(x, y)) {
                return false;
            }
            double contentX = overview.minX() + (x - overview.x() - 5) / overview.scale();
            double contentY = overview.minY() + (y - overview.y() - 5) / overview.scale();
            offsetX += getWidth() / 2 - contentX;
            offsetY += getHeight() / 2 - contentY;
            repaint();
            return true;
        }

        private boolean contains(Path path) {
            return path != null
                    && entries.stream().anyMatch(entry -> entry.path().equals(path));
        }

        private ProjectMapPreview.Placement previewPlacement(
                Path path, double requestedWidth, double requestedHeight, double parentWidth, double parentHeight) {
            NodeBox node = boxes.stream()
                    .filter(box -> box.entry().path().equals(path))
                    .findFirst()
                    .orElse(null);
            ColumnBox column = node == null
                    ? null
                    : columnBoxes.stream()
                            .filter(box -> box.column().id().equals(columnId(node.entry())))
                            .findFirst()
                            .orElse(null);
            double fullWidth = boundedPreviewSize(
                    requestedWidth, ProjectMapPreview.MIN_WIDTH, parentWidth - PREVIEW_EDGE_MARGIN * 2);
            double fullHeight = boundedPreviewSize(
                    requestedHeight, ProjectMapPreview.MIN_HEIGHT, parentHeight - PREVIEW_EDGE_MARGIN * 2);
            if (node == null || column == null) {
                return new ProjectMapPreview.Placement(
                        Math.max(PREVIEW_EDGE_MARGIN, parentWidth - fullWidth - PREVIEW_EDGE_MARGIN),
                        PREVIEW_EDGE_MARGIN,
                        fullWidth,
                        fullHeight);
            }

            if (flowDirection == FlowDirection.LEFT_TO_RIGHT || flowDirection == FlowDirection.RIGHT_TO_LEFT) {
                double availableWidth = parentWidth - PREVIEW_EDGE_MARGIN * 2 - PREVIEW_COLUMN_GAP - column.width();
                double width = boundedPreviewSize(requestedWidth, ProjectMapPreview.MIN_WIDTH, availableWidth);
                double columnX;
                if (flowDirection == FlowDirection.LEFT_TO_RIGHT) {
                    double maximum = parentWidth - PREVIEW_EDGE_MARGIN - PREVIEW_COLUMN_GAP - width - column.width();
                    columnX = clampPreview(column.x(), PREVIEW_EDGE_MARGIN, Math.max(PREVIEW_EDGE_MARGIN, maximum));
                } else {
                    double minimum = PREVIEW_EDGE_MARGIN + width + PREVIEW_COLUMN_GAP;
                    double maximum = parentWidth - PREVIEW_EDGE_MARGIN - column.width();
                    columnX = clampPreview(column.x(), minimum, Math.max(minimum, maximum));
                }
                double shift = columnX - column.x();
                if (shift != 0) {
                    offsetX += shift;
                    repaint();
                }
                double x = flowDirection == FlowDirection.LEFT_TO_RIGHT
                        ? columnX + column.width() + PREVIEW_COLUMN_GAP
                        : columnX - PREVIEW_COLUMN_GAP - width;
                double y = clampPreview(
                        node.y() + node.height() / 2 - fullHeight / 2,
                        PREVIEW_EDGE_MARGIN,
                        Math.max(PREVIEW_EDGE_MARGIN, parentHeight - fullHeight - PREVIEW_EDGE_MARGIN));
                return new ProjectMapPreview.Placement(x, y, width, fullHeight);
            }

            double availableHeight = parentHeight - PREVIEW_EDGE_MARGIN * 2 - PREVIEW_COLUMN_GAP - column.height();
            double height = boundedPreviewSize(requestedHeight, ProjectMapPreview.MIN_HEIGHT, availableHeight);
            double columnY;
            if (flowDirection == FlowDirection.TOP_TO_BOTTOM) {
                double maximum = parentHeight - PREVIEW_EDGE_MARGIN - PREVIEW_COLUMN_GAP - height - column.height();
                columnY = clampPreview(column.y(), PREVIEW_EDGE_MARGIN, Math.max(PREVIEW_EDGE_MARGIN, maximum));
            } else {
                double minimum = PREVIEW_EDGE_MARGIN + height + PREVIEW_COLUMN_GAP;
                double maximum = parentHeight - PREVIEW_EDGE_MARGIN - column.height();
                columnY = clampPreview(column.y(), minimum, Math.max(minimum, maximum));
            }
            double shift = columnY - column.y();
            if (shift != 0) {
                offsetY += shift;
                repaint();
            }
            double x = clampPreview(
                    node.x() + node.width() / 2 - fullWidth / 2,
                    PREVIEW_EDGE_MARGIN,
                    Math.max(PREVIEW_EDGE_MARGIN, parentWidth - fullWidth - PREVIEW_EDGE_MARGIN));
            double y = flowDirection == FlowDirection.TOP_TO_BOTTOM
                    ? columnY + column.height() + PREVIEW_COLUMN_GAP
                    : columnY - PREVIEW_COLUMN_GAP - height;
            return new ProjectMapPreview.Placement(x, y, fullWidth, height);
        }

        private double boundedPreviewSize(double requested, double minimum, double available) {
            double maximum = Math.max(1, available);
            return Math.min(Math.max(Math.min(minimum, maximum), requested), maximum);
        }

        private double clampPreview(double value, double minimum, double maximum) {
            return Math.max(minimum, Math.min(maximum, value));
        }

        private double screenX(double worldX) {
            return offsetX + worldX * zoom;
        }

        private double screenY(double worldY) {
            return offsetY + worldY * zoom;
        }

        private void updateAccessibleText() {
            var current = selectedEntry();
            String name = current.map(ProjectMapModel.Entry::name).orElse(tr("project.map.empty"));
            boolean open = current.filter(entry -> !entry.directory())
                    .map(ProjectMapModel.Entry::path)
                    .filter(openPaths::contains)
                    .isPresent();
            setAccessibleText(
                    tr(open ? "project.map.accessibleSelectionOpen" : "project.map.accessibleSelection", name));
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

        private static ProjectMapModel.ColumnId columnId(ProjectMapModel.Entry entry) {
            return new ProjectMapModel.ColumnId(entry.depth(), entry.parent());
        }

        private record NodeBox(ProjectMapModel.Entry entry, double x, double y, double width, double height) {}

        private record ColumnBox(ProjectMapModel.Column column, double x, double y, double width, double height) {}

        private record ColumnControls(TextField filter, CheckBox showHidden, ToggleButton pin, Button close) {}

        private record OverviewBox(
                double x, double y, double width, double height, double minX, double minY, double scale) {
            private boolean contains(double px, double py) {
                return px >= x && px <= x + width && py >= y && py <= y + height;
            }
        }

        private static final class ColumnLayout {
            private double x;
            private double y;
            private boolean locked;
        }

        private record IconKey(String kind, String statusClass) {}
    }

    private static boolean safeTest(Predicate<Path> predicate, Path path) {
        try {
            return predicate != null && predicate.test(path);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static Color mix(Color base, Color overlay, double amount) {
        return base.interpolate(overlay, amount);
    }
}
