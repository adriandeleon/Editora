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

import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
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
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.ZoomEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
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
import javafx.scene.text.TextAlignment;
import javafx.stage.Popup;
import javafx.stage.WindowEvent;
import javafx.util.Duration;
import javafx.util.StringConverter;

import com.editora.config.PersonalNote;
import com.editora.git.GitFileStatus;

import static com.editora.i18n.Messages.tr;

/**
 * Spatial navigator for a project. Native JavaFX controls own filtering and zoom; the hierarchy itself is
 * drawn on a {@link Canvas}. Node context menus reuse the Project tree's file-management actions.
 */
final class ProjectMapView extends VBox {

    /** Code and note cards together: one bound on what floats over the map, however the eight are split. */
    private static final int MAX_OPEN_PREVIEWS = 8;
    /** Selections kept for Back/Forward; the oldest are dropped beyond this. */
    static final int MAX_SELECTION_HISTORY = 100;

    private static final int MAX_REMEMBERED_ROOTS = 8;
    private static final int MAX_REVEAL_PINS = 64;
    private static final double LOADING_NOTICE_MILLIS = 200;
    private static final double PREVIEW_CASCADE = 28;
    /** Quiet time after a state or filesystem notification before open cards are brought up to date. */
    private static final Duration CARD_REFRESH_DELAY = Duration.millis(250);
    /** How often a card that mirrors an open buffer looks for edits; nothing else announces a keystroke. */
    private static final Duration CARD_POLL_INTERVAL = Duration.seconds(1);

    enum FlowDirection {
        LEFT_TO_RIGHT,
        RIGHT_TO_LEFT,
        TOP_TO_BOTTOM,
        BOTTOM_TO_TOP
    }

    /** The layout for anyone with no stored choice: it reads the way the breadcrumb and the Tree do. */
    static final FlowDirection DEFAULT_FLOW = FlowDirection.LEFT_TO_RIGHT;

    /** How long an empty match set must last before the "No files match" message appears. */
    private static final Duration NO_MATCHES_DELAY = Duration.millis(300);

    private final Predicate<Path> isOpen;
    private final Predicate<Path> isModified;
    private final Consumer<Path> onOpenFile;
    private final Function<Path, ProjectMapPreview.Content> previewContent;
    private final ToggleButton openFilter = filterButton("project.map.filter.open");
    private final ToggleButton modifiedFilter = filterButton("project.map.filter.modified");
    private final ToggleButton gitFilter = filterButton("project.map.filter.gitChanged");
    private final ToggleButton bookmarksFilter = filterButton("project.map.filter.bookmarks");
    private final ToggleButton personalNotesFilter = filterButton("project.map.filter.personalNotes");
    // The three session options and the two output actions live in the options (⋯) menu, not in the filter row.
    private final CheckMenuItem hideOpenNotes = optionItem("project.map.filter.hideOpenNotes", false);
    private final CheckMenuItem keepZoomOnOpen = optionItem("project.map.navigation.keepZoom", true);
    private final CheckMenuItem focusNewColumn = optionItem("project.map.navigation.focusNewColumn", true);
    private final ComboBox<ProjectMapModel.TypeFilter> typeFilter = new ComboBox<>();
    private final ComboBox<FlowDirection> flowFilter = new ComboBox<>();
    private final Button backButton = new Button("‹");
    private final Button forwardButton = new Button("›");
    private final MenuItem printButton = new MenuItem(tr("project.map.print"));
    private final MenuItem exportPdfButton = new MenuItem(tr("project.map.exportPdf"));
    private final HBox breadcrumbs = new HBox(2);
    private final Label breadcrumbEllipsis = new Label("…");
    private final Label breadcrumbEllipsisSeparator = new Label("›");
    private final Label noMatchesLabel = new Label();
    private final PauseTransition noMatchesDelay = new PauseTransition(NO_MATCHES_DELAY);
    private Popup helpPopup;
    private long helpHiddenAt;
    private final MapSurface surface = new MapSurface();
    private final Canvas previewConnectorCanvas = new Canvas(1, 1);
    private final Map<Path, PreviewConnector> previewConnectors = new HashMap<>();
    private final Map<Path, ProjectMapPreview> previews = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<Path, ProjectMapNotePreview> notePreviews = new LinkedHashMap<>(16, 0.75f, true);
    /** When each open card was last used (pressed, focused, scrolled or opened); the oldest is evicted. */
    private final Map<Region, Long> cardTouches = new java.util.IdentityHashMap<>();

    private long cardTouchSequence;
    private final javafx.animation.PauseTransition cardRefresh =
            new javafx.animation.PauseTransition(CARD_REFRESH_DELAY);
    private final javafx.animation.PauseTransition cardPoll = new javafx.animation.PauseTransition(CARD_POLL_INTERVAL);
    /** Rate limit for re-reading cards after content-only disk changes: a log may be rewritten many times a second. */
    private final javafx.animation.PauseTransition cardDiskRefresh =
            new javafx.animation.PauseTransition(CARD_POLL_INTERVAL);
    /** The expansion the cards were last checked against (an identity, replaced by every reload). */
    private Set<Path> cardsCheckedAgainst;

    private boolean orphanCheckPending;
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "project-map-loader");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong generation = new AtomicLong();
    /** How many queued loads actually began listing (the rest were superseded first). */
    final java.util.concurrent.atomic.AtomicInteger loadsStartedForTest =
            new java.util.concurrent.atomic.AtomicInteger();

    /** The folders the user opened by hand. A search never writes here, so clearing it restores this set. */
    private final Set<Path> expanded = new HashSet<>();
    /** While a query is active: the ancestors of its matches, opened on top of {@link #expanded}. */
    private Set<Path> searchExpanded = Set.of();
    /** The current query's matches, best first; loaded before anything else so the first is always present. */
    private List<Path> searchMatches = List.of();
    /** Folders opened / closed by hand while a query is active. Dropped with the query. */
    private final Set<Path> searchOpened = new HashSet<>();

    private final Set<Path> searchClosed = new HashSet<>();
    /** The query whose first match was last selected, so re-running it does not steal the selection again. */
    private String searchSelectionQuery = "";
    /** Row limits raised by "+N more", by directory. */
    private final Map<Path, Integer> directoryLimits = new HashMap<>();
    /** Revealed paths that must stay loaded even when they sort beyond their directory's row limit. */
    private final Set<Path> revealPins = new java.util.LinkedHashSet<>();
    /** Expansion and selection of the folders this view showed before, so returning to one restores them. */
    private final Map<String, RootMemory> rootMemory = new LinkedHashMap<>(16, 0.75f, true);

    private final Label loadingLabel = new Label(tr("project.map.loading"));
    private final javafx.animation.PauseTransition loadingDelay =
            new javafx.animation.PauseTransition(Duration.millis(LOADING_NOTICE_MILLIS));
    private final List<Path> selectionHistory = new ArrayList<>();

    private Path root;
    private boolean disposed;
    /** False while the Project panel shows the Tree: loads wait until the Map is on screen. */
    private boolean active = true;
    /** The Project "show hidden files" setting: each column's default, and what the loader lists. */
    private boolean showHiddenDefault = true;

    private boolean loadInFlight;
    private boolean announceLimit;
    private boolean announceMatches;
    private boolean wasCapped;
    private Path loadMoreDirectory;
    private int loadMorePreviousLimit;
    private int loadMorePreviousLoaded;
    private ProjectMapModel.Snapshot snapshot = ProjectMapModel.Snapshot.EMPTY;
    private java.util.function.Supplier<java.util.Collection<Path>> openFiles = List::of;
    private java.util.function.Supplier<java.util.Collection<Path>> markerCandidates = List::of;
    private java.util.function.BiConsumer<Boolean, Boolean> onNavigationChanged = (keepZoom, focusColumn) -> {};
    private String query = "";
    private Map<Path, GitFileStatus> gitStatus = Map.of();
    private Set<Path> gitChangedDirectories = Set.of();
    private Runnable onExpandedChanged = () -> {};
    private int historyIndex = -1;
    private boolean navigatingHistory;
    /** Whether the newest history entry came from a sibling move, so the next one replaces it. */
    private boolean historyHeadFromSiblingMove;

    private Runnable onClearSearch = () -> {};
    private Runnable onLeaveMap = () -> {};
    private Consumer<ProjectMapModel.Entry> onRenameEntry = entry -> {};
    private Consumer<ProjectMapModel.Entry> onDeleteEntry = entry -> {};
    private Path pendingSelection;
    private Consumer<FlowDirection> onFlowChanged = ignored -> {};
    private Consumer<ProjectMapOutput> onPrint = ignored -> {};
    private Consumer<ProjectMapOutput> onExportPdf = ignored -> {};
    private ProjectMapPreview.MarkerActions previewMarkerActions;
    private ProjectPanel.MarkerActions notePreviewActions;
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
        surface.setOnNotesPreview(this::previewNotes);
        surface.setOnSelectionChanged(this::selectionChanged);
        surface.setStatusSuppliers(this.isOpen, this.isModified);
        loadingLabel.getStyleClass().addAll("project-map-zoom-control", "project-map-loading");
        // The zoom-control class makes it transparent; over map rows it needs the overlay fill to be read.
        loadingLabel.setStyle("-fx-background-color: -color-bg-overlay; -fx-background-radius: 5;");
        loadingLabel.setMouseTransparent(true);
        loadingLabel.setVisible(false);
        StackPane.setAlignment(loadingLabel, Pos.TOP_RIGHT);
        StackPane.setMargin(loadingLabel, new Insets(8));
        canvasHost.getChildren().add(loadingLabel);
        loadingDelay.setOnFinished(event -> loadingLabel.setVisible(loadInFlight));
        keepZoomOnOpen.selectedProperty().addListener((obs, old, value) -> navigationChanged());
        focusNewColumn.selectedProperty().addListener((obs, old, value) -> navigationChanged());
        updateFilters();
    }

    private HBox buildNavigation() {
        backButton.getStyleClass().add("project-map-nav-button");
        forwardButton.getStyleClass().add("project-map-nav-button");
        Icons.name(backButton, tr("project.map.navigation.back"));
        Icons.name(forwardButton, tr("project.map.navigation.forward"));
        backButton.setMinWidth(Region.USE_PREF_SIZE);
        forwardButton.setMinWidth(Region.USE_PREF_SIZE);
        backButton.setOnAction(event -> moveHistory(-1));
        forwardButton.setOnAction(event -> moveHistory(1));
        breadcrumbs.getStyleClass().add("project-map-breadcrumbs");
        breadcrumbs.setMinWidth(0);
        breadcrumbs.widthProperty().addListener((obs, old, value) -> fitBreadcrumbs());
        for (Label label : List.of(breadcrumbEllipsis, breadcrumbEllipsisSeparator)) {
            label.getStyleClass().add("project-map-breadcrumb-separator");
            label.setMinWidth(Region.USE_PREF_SIZE);
        }

        Button help = new Button("?");
        help.getStyleClass().addAll("project-map-nav-button", "project-map-help-button");
        help.setMinWidth(Region.USE_PREF_SIZE);
        Icons.name(help, tr("project.map.help"));
        help.setOnAction(event -> toggleHelp(help));

        MenuButton options = new MenuButton();
        options.setGraphic(Icons.more());
        options.getStyleClass().add("project-map-options");
        options.setMinWidth(Region.USE_PREF_SIZE);
        Icons.name(options, tr("project.map.options"));
        options.getItems()
                .addAll(
                        keepZoomOnOpen,
                        focusNewColumn,
                        hideOpenNotes,
                        new SeparatorMenuItem(),
                        printButton,
                        exportPdfButton);
        printButton.setDisable(true);
        exportPdfButton.setDisable(true);
        printButton.setOnAction(event -> onPrint.accept(mapOutput()));
        exportPdfButton.setOnAction(event -> onExportPdf.accept(mapOutput()));
        // Listeners rather than action handlers, so a restored value applies exactly like a click.
        hideOpenNotes.selectedProperty().addListener((obs, old, selected) -> updateNotePreviewVisibility());
        keepZoomOnOpen
                .selectedProperty()
                .addListener((obs, old, selected) -> surface.setKeepZoomOnColumnOpen(selected));
        focusNewColumn.selectedProperty().addListener((obs, old, selected) -> surface.setFocusNewColumn(selected));

        HBox row = new HBox(3, backButton, forwardButton, breadcrumbs, help, options);
        row.getStyleClass().add("project-map-navigation");
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(breadcrumbs, Priority.ALWAYS);
        updateNavigation();
        return row;
    }

    /** Whether opening a folder keeps the current zoom (otherwise the map is fitted again). */
    boolean isKeepZoom() {
        return keepZoomOnOpen.isSelected();
    }

    void setKeepZoom(boolean keep) {
        keepZoomOnOpen.setSelected(keep);
    }

    /** Whether opening a folder brings its new column into view. */
    boolean isFocusNewColumn() {
        return focusNewColumn.isSelected();
    }

    void setFocusNewColumn(boolean focus) {
        focusNewColumn.setSelected(focus);
    }

    private FlowPane buildFilters() {
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
        nameSelector(typeFilter, ProjectMapView::typeLabel);

        flowFilter.getItems().setAll(FlowDirection.values());
        flowFilter.setValue(DEFAULT_FLOW);
        flowFilter.getStyleClass().add("project-map-flow-filter");
        flowFilter.setConverter(new StringConverter<>() {
            @Override
            public String toString(FlowDirection value) {
                return flowName(value);
            }

            @Override
            public FlowDirection fromString(String value) {
                return DEFAULT_FLOW;
            }
        });
        flowFilter.setButtonCell(flowCell());
        flowFilter.setCellFactory(list -> flowCell());
        nameSelector(flowFilter, ProjectMapView::flowLabel);

        Button clear = new Button(tr("project.map.filter.clear"));
        clear.getStyleClass().add("project-map-filter-clear");
        clear.setMinWidth(Region.USE_PREF_SIZE);
        Icons.name(clear, tr("project.map.filter.clear.help"));
        clear.setOnAction(event -> {
            openFilter.setSelected(false);
            modifiedFilter.setSelected(false);
            gitFilter.setSelected(false);
            bookmarksFilter.setSelected(false);
            personalNotesFilter.setSelected(false);
            hideOpenNotes.setSelected(false);
            updateNotePreviewVisibility();
            typeFilter.setValue(ProjectMapModel.TypeFilter.ALL);
            surface.clearColumnFilters();
            updateFilters();
            if (root != null) {
                reload(); // the columns' "Show hidden" choices went back to the setting
            }
        });

        for (ToggleButton button :
                List.of(openFilter, modifiedFilter, gitFilter, bookmarksFilter, personalNotesFilter)) {
            button.setOnAction(event -> updateFilters());
        }
        typeFilter.setOnAction(event -> updateFilters());
        flowFilter.setOnAction(event -> {
            FlowDirection flow = flowFilter.getValue();
            surface.setFlowDirection(flow);
            onFlowChanged.accept(flow);
        });

        // Only filters live here, and the row wraps rather than truncating: every control keeps its full
        // label at any tool-window width. The session options and Print/PDF are in the options menu.
        FlowPane filters = new FlowPane(
                4,
                4,
                openFilter,
                modifiedFilter,
                gitFilter,
                bookmarksFilter,
                personalNotesFilter,
                typeFilter,
                flowFilter,
                clear);
        filters.getStyleClass().add("project-map-filters");
        filters.setAlignment(Pos.CENTER_LEFT);
        filters.setAccessibleText(tr("project.map.filters"));
        return filters;
    }

    /** Names a selector by its current value ("Type: All"), which its compact face no longer spells out. */
    private static <T> void nameSelector(ComboBox<T> selector, Function<T, String> label) {
        selector.setMinWidth(Region.USE_PREF_SIZE);
        Runnable apply = () -> Icons.name(selector, label.apply(selector.getValue()));
        selector.valueProperty().addListener((obs, old, value) -> apply.run());
        apply.run();
    }

    private StackPane buildCanvasHost() {
        Label zoom = new Label("100%");
        zoom.getStyleClass().addAll("project-map-zoom-control", "project-map-zoom-level");
        zoom.setMinWidth(Region.USE_PREF_SIZE);
        Button zoomOut = zoomButton(Icons.minus(), "project.map.zoom.out", () -> surface.zoomBy(0.9));
        Button zoomIn = zoomButton(Icons.plus(), "project.map.zoom.in", () -> surface.zoomBy(1.1));
        Button fit = zoomButton(Icons.fitView(), "project.map.zoom.fitHelp", surface::fitContent);
        Button center = zoomButton(Icons.centerView(), "project.map.navigation.centerHelp", surface::centerSelection);
        Button reset = zoomButton(Icons.resetView(), "project.map.zoom.resetHelp", surface::resetViewport);
        surface.setOnZoomChanged(() -> zoom.setText(surface.zoomPercent()));

        HBox zoomBar = new HBox(1, zoomOut, zoom, zoomIn, fit, center, reset);
        zoomBar.getStyleClass().add("project-map-zoom");
        zoomBar.setAlignment(Pos.CENTER);
        zoomBar.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        zoomBar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        noMatchesLabel.getStyleClass().add("project-map-no-matches");
        noMatchesLabel.setMouseTransparent(true);
        noMatchesLabel.setWrapText(true);
        noMatchesLabel.setVisible(false);
        noMatchesLabel.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        noMatchesDelay.setOnFinished(event -> showNoMatches());
        surface.setOnNoMatchesChanged(this::noMatchesChanged);
        previewConnectorCanvas.setManaged(false);
        previewConnectorCanvas.setMouseTransparent(true);
        StackPane host = new StackPane(surface, previewConnectorCanvas, noMatchesLabel, zoomBar);
        canvasHost = host;
        host.getStyleClass().add("project-map-host");
        StackPane.setAlignment(zoomBar, Pos.BOTTOM_LEFT);
        StackPane.setMargin(zoomBar, new Insets(ZOOM_BAR_MARGIN));
        // Centred across the canvas but near its top: the selection usually sits in the middle.
        StackPane.setAlignment(noMatchesLabel, Pos.TOP_CENTER);
        StackPane.setMargin(noMatchesLabel, new Insets(24, 16, 16, 16));
        // The bar floats over the canvas: the surface keeps selected rows, fitted content and its
        // overview out from under it.
        zoomBar.boundsInParentProperty()
                .addListener((obs, old, bounds) -> surface.setReservedZoomBar(
                        bounds.getMaxX() + ZOOM_BAR_MARGIN, bounds.getHeight() + ZOOM_BAR_MARGIN * 2));
        host.widthProperty().addListener((obs, old, value) -> {
            previewConnectorCanvas.setWidth(value.doubleValue());
            constrainPreviews(value.doubleValue(), host.getHeight());
            repaintPreviewConnectors();
        });
        host.heightProperty().addListener((obs, old, value) -> {
            previewConnectorCanvas.setHeight(value.doubleValue());
            constrainPreviews(host.getWidth(), value.doubleValue());
            repaintPreviewConnectors();
        });
        cardRefresh.setOnFinished(event -> refreshCards(false));
        cardDiskRefresh.setOnFinished(event -> refreshCards(false));
        cardPoll.setOnFinished(event -> refreshCards(true));
        // Cards survive a switch to the Tree; whatever changed meanwhile is picked up when the Map returns.
        host.sceneProperty().addListener((obs, old, scene) -> {
            if (scene != null) {
                scheduleCardRefresh();
            }
        });
        return host;
    }

    private static final double ZOOM_BAR_MARGIN = 8;

    private static Button zoomButton(Node icon, String nameKey, Runnable action) {
        Button button = Icons.button(icon, tr(nameKey), action, "project-map-zoom-control");
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    private void noMatchesChanged(boolean none) {
        // A global query first fades what is loaded and only then opens the folders that hold its matches,
        // so "nothing matches" is believed only once it has lasted.
        noMatchesDelay.stop();
        if (none) {
            noMatchesDelay.playFromStart();
        } else {
            noMatchesLabel.setVisible(false);
        }
    }

    private void showNoMatches() {
        if (disposed || !surface.hasNoMatches()) {
            return;
        }
        noMatchesLabel.setText(
                query.isBlank()
                        ? tr("project.map.noMatches.filters")
                        : tr("project.map.noMatches.query", query.strip()));
        noMatchesLabel.setVisible(true);
    }

    /** The mouse and keyboard model, which nothing else on screen spells out. */
    private void toggleHelp(Node anchor) {
        if (helpPopup != null && helpPopup.isShowing()) {
            helpPopup.hide();
            return;
        }
        // Pressing the button while the popover is open auto-hides it first; that press must not reopen it.
        if (System.nanoTime() - helpHiddenAt < 250_000_000L) {
            return;
        }
        if (helpPopup == null) {
            helpPopup = new Popup();
            helpPopup.setAutoHide(true);
            helpPopup.setHideOnEscape(true);
            helpPopup.getContent().add(buildHelp());
            helpPopup.setOnHidden(event -> helpHiddenAt = System.nanoTime());
        }
        javafx.geometry.Bounds bounds = anchor.localToScreen(anchor.getBoundsInLocal());
        if (bounds != null) {
            helpPopup.show(anchor, bounds.getMinX(), bounds.getMaxY() + 4);
        }
    }

    private static Node buildHelp() {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("project-map-help");
        grid.setHgap(12);
        grid.setVgap(3);
        int row = 0;
        row = helpHeading(grid, row, "project.map.help.mouse");
        for (String id : List.of("click", "chevron", "preview", "wheel", "panWheel", "dragCanvas", "dragHeader")) {
            row = helpRow(grid, row, tr("project.map.help." + id + ".gesture"), "project.map.help." + id);
        }
        row = helpHeading(grid, row, "project.map.help.keyboard");
        row = helpRow(grid, row, "↑ ↓ ← →", "project.map.help.arrows");
        row = helpRow(grid, row, keyNames(KeyCode.ENTER, KeyCode.SPACE), "project.map.help.activate");
        row = helpRow(grid, row, keyNames(KeyCode.BACK_SPACE), "project.map.help.parent");
        row = helpRow(grid, row, keyNames(KeyCode.HOME), "project.map.help.home");
        row = helpRow(grid, row, keyNames(KeyCode.PAGE_UP, KeyCode.PAGE_DOWN), "project.map.help.page");
        row = helpRow(grid, row, "/", "project.map.help.columnFilter");
        row = helpRow(
                grid,
                row,
                chord(KeyCode.LEFT, KeyCombination.ALT_DOWN) + " / " + chord(KeyCode.RIGHT, KeyCombination.ALT_DOWN),
                "project.map.help.history");
        row = helpRow(grid, row, chord(KeyCode.DIGIT0, KeyCombination.SHORTCUT_DOWN), "project.map.help.fit");
        helpRow(grid, row, keyNames(KeyCode.ESCAPE), "project.map.help.escape");
        return grid;
    }

    private static int helpHeading(GridPane grid, int row, String key) {
        Label heading = new Label(tr(key));
        heading.getStyleClass().add("project-map-help-heading");
        grid.add(heading, 0, row, 2, 1);
        return row + 1;
    }

    private static int helpRow(GridPane grid, int row, String gesture, String descriptionKey) {
        Label keys = new Label(gesture);
        keys.getStyleClass().add("project-map-help-gesture");
        keys.setMinWidth(Region.USE_PREF_SIZE);
        Label description = new Label(tr(descriptionKey));
        description.getStyleClass().add("project-map-help-text");
        description.setWrapText(true);
        description.setMaxWidth(250);
        grid.add(keys, 0, row);
        grid.add(description, 1, row);
        return row + 1;
    }

    /** Key names as the platform spells them; these are fixed map keys, not keymap bindings. */
    private static String keyNames(KeyCode... codes) {
        StringBuilder names = new StringBuilder();
        for (KeyCode code : codes) {
            if (!names.isEmpty()) {
                names.append(" / ");
            }
            names.append(code.getName());
        }
        return names.toString();
    }

    private static String chord(KeyCode code, KeyCombination.Modifier modifier) {
        return new KeyCodeCombination(code, modifier).getDisplayText();
    }

    private static ToggleButton filterButton(String key) {
        ToggleButton button = new ToggleButton(tr(key));
        button.getStyleClass().add("project-map-filter-chip");
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setTooltip(new Tooltip(tr(key + ".help")));
        button.setAccessibleHelp(tr(key + ".help"));
        return button;
    }

    private static CheckMenuItem optionItem(String key, boolean selected) {
        CheckMenuItem option = new CheckMenuItem(tr(key));
        option.setSelected(selected);
        return option;
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

    /** The selector's compact face: the value alone ("Source"); {@link #typeLabel} names the control. */
    private static String typeName(ProjectMapModel.TypeFilter type) {
        if (type == null) {
            type = ProjectMapModel.TypeFilter.ALL;
        }
        return tr("project.map.type.name." + type.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static String typeLabel(ProjectMapModel.TypeFilter type) {
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
            flow = DEFAULT_FLOW;
        }
        return tr("project.map.flow.name." + flow.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static String flowLabel(FlowDirection flow) {
        if (flow == null) {
            flow = DEFAULT_FLOW;
        }
        return tr("project.map.flow." + flow.name().toLowerCase(java.util.Locale.ROOT));
    }

    void setRoot(Path root) {
        Path normalized = ProjectMapModel.normalize(root);
        // Not Objects.equals: two SFTP paths on different connections throw from equals() instead of
        // answering false, which made mounting a second host (or reconnecting to the first) fail half-way.
        if (com.editora.config.PathKeys.samePath(this.root, normalized)) {
            return;
        }
        boolean otherFileSystem = this.root != null
                && normalized != null
                && !com.editora.config.PathKeys.sameFileSystem(this.root, normalized);
        rememberRoot();
        this.root = normalized;
        closeAllPreviews();
        expanded.clear();
        clearSearchState();
        directoryLimits.clear();
        revealPins.clear();
        snapshot = ProjectMapModel.Snapshot.EMPTY;
        wasCapped = false;
        loadMoreDirectory = null;
        selectionHistory.clear();
        historyIndex = -1;
        pendingSelection = normalized;
        setOutputEnabled(false);
        surface.resetForRoot();
        if (otherFileSystem) {
            // Until the reload lands the surface would compare the old connection's entries with the new
            // root's paths, and those comparisons throw too.
            surface.setEntries(List.of(), Set.of());
        }
        if (normalized != null) {
            expanded.add(normalized);
            recordSelection(normalized);
            restoreRoot(normalized);
        }
        surface.setSelected(normalized);
        updateNavigation();
        reload();
    }

    /** What the view keeps of a folder it is leaving. Local folders only: their paths compare safely. */
    private record RootMemory(Set<Path> expanded, Map<Path, Integer> limits, Path selected) {}

    /**
     * In the window with no project the root follows the active tab's folder, so every tab switch to another
     * folder is a root change. Remembering the last few folders' open branches and selection means switching
     * back does not start that folder's map from nothing. Zoom and column positions belong to one layout and
     * are not carried over.
     */
    private void rememberRoot() {
        if (root == null || !com.editora.vfs.Vfs.isLocal(root)) {
            return;
        }
        rootMemory.put(
                root.toString(),
                new RootMemory(Set.copyOf(expanded), Map.copyOf(directoryLimits), surface.selectedPath()));
        while (rootMemory.size() > MAX_REMEMBERED_ROOTS) {
            rootMemory.remove(rootMemory.keySet().iterator().next());
        }
    }

    private void restoreRoot(Path next) {
        RootMemory memory = com.editora.vfs.Vfs.isLocal(next) ? rootMemory.get(next.toString()) : null;
        if (memory == null) {
            return;
        }
        expanded.addAll(memory.expanded()); // pruned against the disk by the load that follows
        directoryLimits.putAll(memory.limits());
        if (memory.selected() != null && memory.selected().startsWith(next)) {
            pendingSelection = memory.selected();
        }
    }

    /**
     * Whether the Map is on screen. While it is not, nothing is listed: the first load waits until the Map is
     * first shown, and every return to it reloads, so changes made while the Tree was showing are picked up.
     */
    void setActive(boolean active) {
        if (this.active == active) {
            return;
        }
        this.active = active;
        if (active) {
            reload();
        }
    }

    /** Follows the Project "show hidden files" setting: every column goes back to it and the map reloads. */
    void setShowHidden(boolean showHidden) {
        if (showHiddenDefault == showHidden) {
            return;
        }
        showHiddenDefault = showHidden;
        surface.resetColumnHidden();
        if (root != null) {
            reload();
        }
    }

    /** Open editor files, for the Open / Modified chips to mark the collapsed folders that hold them. */
    void setOpenFiles(java.util.function.Supplier<java.util.Collection<Path>> supplier) {
        openFiles = supplier == null ? List::of : supplier;
    }

    /** Paths that may carry a bookmark or Personal Note (the stores' keys); the predicates decide. */
    void setMarkerCandidates(java.util.function.Supplier<java.util.Collection<Path>> supplier) {
        markerCandidates = supplier == null ? List::of : supplier;
    }

    /** A column's own "Show hidden" checkbox was clicked: its folder has to be listed again. */
    private void columnHiddenChanged() {
        if (root != null) {
            reload();
        }
    }

    private boolean searching() {
        return !query.isBlank();
    }

    private void clearSearchState() {
        searchExpanded = Set.of();
        searchMatches = List.of();
        searchOpened.clear();
        searchClosed.clear();
        searchSelectionQuery = "";
    }

    void setQuery(String query) {
        String value = query == null ? "" : query;
        if (!this.query.equals(value)) {
            boolean hadSearchState = !searchExpanded.isEmpty() || !searchOpened.isEmpty() || !searchClosed.isEmpty();
            this.query = value;
            // What was opened or closed by hand belonged to the previous query's results.
            searchOpened.clear();
            searchClosed.clear();
            updateFilters();
            if (value.isBlank()) {
                clearSearchState();
                if (hadSearchState) {
                    reload();
                    onExpandedChanged.run();
                }
            }
        }
    }

    void setSearchMatches(String query, List<Path> matches) {
        String value = query == null ? "" : query;
        if (disposed || value.isBlank() || !this.query.equals(value)) {
            return;
        }
        List<Path> nextMatches = matches == null
                ? List.of()
                : matches.stream()
                        .map(ProjectMapModel::normalize)
                        .filter(java.util.Objects::nonNull)
                        .filter(path -> root != null && path.startsWith(root))
                        .toList();
        Set<Path> nextExpansion = ProjectMapModel.expandedAncestors(root, nextMatches);
        Path firstMatch = nextMatches.isEmpty() ? null : nextMatches.getFirst();
        boolean changed = !searchExpanded.equals(nextExpansion) || !searchMatches.equals(nextMatches);
        searchExpanded = nextExpansion;
        searchMatches = nextMatches;
        // The same query runs again after every in-app file change; only a new query moves the selection.
        boolean newQuery = !value.equals(searchSelectionQuery);
        searchSelectionQuery = value;
        if (newQuery) {
            pendingSelection = firstMatch;
        }
        if (changed || newQuery && firstMatch != null && !surface.contains(firstMatch)) {
            announceMatches = true;
            reload();
            onExpandedChanged.run();
        } else if (newQuery && firstMatch != null) {
            pendingSelection = null;
            surface.select(firstMatch);
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
        scheduleCardRefresh();
    }

    void setMarkerStates(Predicate<Path> bookmarked, Predicate<Path> noted) {
        surface.setMarkerSuppliers(bookmarked, noted);
        surface.stateChanged();
    }

    void setPreviewMarkerActions(ProjectMapPreview.MarkerActions actions) {
        previewMarkerActions = actions;
        previews.values().forEach(preview -> preview.setMarkerActions(actions));
    }

    void setNotePreviewActions(ProjectPanel.MarkerActions actions) {
        notePreviewActions = actions;
    }

    void refresh() {
        reload();
        scheduleCardRefresh();
    }

    /**
     * The folders whose children the map lists now. Without a query that is the manual set. With one it is
     * the manual set plus the matches' ancestors, as adjusted by hand during the search: a folder closed
     * there hides its whole branch, and none of it reaches the manual set.
     */
    Set<Path> expandedDirectories() {
        Set<Path> result = new HashSet<>(expanded);
        if (searching()) {
            result.addAll(searchExpanded);
            result.addAll(searchOpened);
            if (!searchClosed.isEmpty()) {
                result.removeIf(path -> searchClosed.stream().anyMatch(path::startsWith));
            }
        }
        return Set.copyOf(result);
    }

    /** Carries the map's state across an in-app rename or move, so a renamed open folder stays open. */
    void pathRenamed(Path from, Path to) {
        if (root == null || from == null || to == null) {
            return;
        }
        remapAll(expanded, from, to);
        remapAll(searchOpened, from, to);
        remapAll(searchClosed, from, to);
        remapAll(revealPins, from, to);
        Map<Path, Integer> limits = new HashMap<>();
        directoryLimits.forEach((path, limit) -> limits.put(ProjectMapModel.remap(path, from, to), limit));
        directoryLimits.clear();
        directoryLimits.putAll(limits);
        Path selected = surface.selectedPath();
        Path renamed = ProjectMapModel.normalize(from);
        if (selected != null && renamed != null && selected.startsWith(renamed)) {
            pendingSelection = ProjectMapModel.remap(selected, from, to);
        }
    }

    private static void remapAll(Set<Path> paths, Path from, Path to) {
        List<Path> moved = paths.stream()
                .map(path -> ProjectMapModel.remap(path, from, to))
                .toList();
        paths.clear();
        paths.addAll(moved);
    }

    void focusMap() {
        surface.requestFocus();
    }

    void moveSelection(int delta) {
        surface.moveSibling(delta < 0 ? -1 : 1);
    }

    void openSelection() {
        surface.activateSelection();
    }

    /**
     * What Escape falls back to once no preview or note card is open: {@code clearSearch} empties the shared
     * Project search field while it holds a query, otherwise {@code leave} hands focus back to the editor.
     */
    void setEscapeActions(Runnable clearSearch, Runnable leave) {
        onClearSearch = clearSearch == null ? () -> {} : clearSearch;
        onLeaveMap = leave == null ? () -> {} : leave;
    }

    /** The Project tree's F2 (rename) and Delete row actions, applied to the selected map entry. */
    void setRowActions(Consumer<ProjectMapModel.Entry> rename, Consumer<ProjectMapModel.Entry> delete) {
        onRenameEntry = rename == null ? entry -> {} : rename;
        onDeleteEntry = delete == null ? entry -> {} : delete;
    }

    /** Escape: close the open cards, else clear the search, else leave the map for the editor. */
    private void escapePressed() {
        // Note cards hidden by the "Hide all open Personal Notes" toggle are not on screen: leave them be.
        List<ProjectMapPreview> cards = List.copyOf(previews.values());
        List<ProjectMapNotePreview> notes = notePreviews.values().stream()
                .filter(ProjectMapNotePreview::isVisible)
                .toList();
        if (!cards.isEmpty() || !notes.isEmpty()) {
            cards.forEach(this::closePreview);
            notes.forEach(this::closeNotePreview);
        } else if (!query.isBlank()) {
            onClearSearch.run();
        } else {
            onLeaveMap.run();
        }
    }

    void setOnExpandedChanged(Runnable callback) {
        onExpandedChanged = callback == null ? () -> {} : callback;
    }

    void setRememberedFlow(String name, Consumer<FlowDirection> callback) {
        FlowDirection remembered;
        try {
            remembered = FlowDirection.valueOf(name == null ? "" : name);
        } catch (IllegalArgumentException ignored) {
            remembered = DEFAULT_FLOW; // nothing stored (or a value this build does not know)
        }
        onFlowChanged = callback == null ? ignored -> {} : callback;
        flowFilter.setValue(remembered);
        surface.setFlowDirection(remembered);
    }

    /** Restores the two navigation options and reports later changes to {@code callback} (keep zoom, focus). */
    void setRememberedNavigation(
            boolean keepZoom, boolean focusColumn, java.util.function.BiConsumer<Boolean, Boolean> callback) {
        onNavigationChanged = (keep, focus) -> {};
        keepZoomOnOpen.setSelected(keepZoom);
        focusNewColumn.setSelected(focusColumn);
        surface.setKeepZoomOnColumnOpen(keepZoom);
        surface.setFocusNewColumn(focusColumn);
        onNavigationChanged = callback == null ? (keep, focus) -> {} : callback;
    }

    private void navigationChanged() {
        onNavigationChanged.accept(keepZoomOnOpen.isSelected(), focusNewColumn.isSelected());
    }

    void setContextMenuFactory(Function<ProjectMapModel.Entry, ContextMenu> factory) {
        // A "+N more" or stub row is not a file: it has no file-management menu.
        surface.setContextMenuFactory(
                factory == null ? null : entry -> entry.isPlaceholder() ? null : factory.apply(entry));
    }

    void setOutputActions(Consumer<ProjectMapOutput> print, Consumer<ProjectMapOutput> exportPdf) {
        onPrint = print == null ? ignored -> {} : print;
        onExportPdf = exportPdf == null ? ignored -> {} : exportPdf;
    }

    /** Whether the map has columns to put on a page (the two output buttons are enabled). */
    boolean canOutput() {
        return !printButton.isDisable();
    }

    /** Runs the Print… button's action (the {@code projectMap.print} command); nothing while it is disabled. */
    void print() {
        printButton.fire();
    }

    /** Runs the PDF… button's action (the {@code projectMap.exportPdf} command). */
    void exportPdf() {
        exportPdfButton.fire();
    }

    void hidePreview() {
        closeAllPreviews();
    }

    /**
     * The Map is being swapped for the Tree. Cards stay open where they are for when it returns; only the
     * work that keeps them current stops, and resumes with one refresh when the Map is shown again.
     */
    void suspendPreviews() {
        cardRefresh.stop();
        cardPoll.stop();
        cardDiskRefresh.stop();
    }

    /**
     * A file under the root was rewritten on disk. That changes no listing, so no reload follows; it is
     * only of interest to a preview card showing the file. Checked at most once per
     * {@link #CARD_POLL_INTERVAL}, after the first change rather than after the last, so a file that never
     * stops changing is still followed.
     */
    void filesChangedOnDisk() {
        if (disposed
                || previews.isEmpty()
                || getScene() == null
                || cardDiskRefresh.getStatus() == javafx.animation.Animation.Status.RUNNING) {
            return;
        }
        cardDiskRefresh.playFromStart();
    }

    void dispose() {
        disposed = true;
        cardRefresh.stop();
        cardPoll.stop();
        cardDiskRefresh.stop();
        generation.incrementAndGet();
        loader.shutdownNow();
        loadInFlight = false;
        loadingDelay.stop();
        surface.dispose();
        closeAllPreviews();
        noMatchesDelay.stop();
        if (helpPopup != null) {
            helpPopup.hide();
        }
    }

    private void updateFilters() {
        ProjectMapModel.TypeFilter type = typeFilter.getValue();
        surface.setFilters(new ProjectMapModel.Filters(
                query,
                openFilter.isSelected(),
                modifiedFilter.isSelected(),
                gitFilter.isSelected(),
                bookmarksFilter.isSelected(),
                personalNotesFilter.isSelected(),
                type == null ? ProjectMapModel.TypeFilter.ALL : type));
    }

    /** The complete map as a deferred job: it is rendered only once the receiver knows the page size. */
    private ProjectMapOutput mapOutput() {
        return new ProjectMapOutput() {
            @Override
            public boolean landscape() {
                return !disposed && surface.outputIsLandscape();
            }

            @Override
            public Rendered render(double pageWidth, double pageHeight) {
                return disposed ? null : surface.renderOutput(pageWidth, pageHeight);
            }
        };
    }

    private void setOutputEnabled(boolean enabled) {
        printButton.setDisable(!enabled);
        exportPdfButton.setDisable(!enabled);
    }

    private java.util.function.Consumer<String> onStatus = message -> {};

    /** Where a folder that could not be read is reported (the window's status bar). */
    void setOnStatus(java.util.function.Consumer<String> onStatus) {
        this.onStatus = onStatus == null ? message -> {} : onStatus;
    }

    /** A reload the user asked for (opening a folder, "+N more", a reveal): says so if the limit is hit. */
    private void reloadForUser() {
        announceLimit = true;
        reload();
    }

    private void reload() {
        if (disposed) {
            return; // a late callback after the window closed: the loader is gone
        }
        if (!active) {
            return; // setActive(true) reloads when the Map is next shown
        }
        long requested = generation.incrementAndGet();
        Path requestedRoot = root;
        if (requestedRoot == null) {
            loadFinished();
            snapshot = ProjectMapModel.Snapshot.EMPTY;
            surface.setEntries(List.of(), Set.of());
            return;
        }
        List<Path> pinned = new ArrayList<>(searching() ? searchMatches : List.<Path>of());
        pinned.addAll(revealPins);
        ProjectMapModel.Request request = new ProjectMapModel.Request(
                requestedRoot,
                expandedDirectories(),
                showHiddenDefault,
                surface.hiddenOverrides(),
                directoryLimits,
                pinned,
                ProjectMapView::placeholderLabel,
                () -> requested != generation.get());
        loadStarted();
        try {
            loader.submit(() -> {
                if (requested != generation.get()) {
                    return; // superseded while queued: ten quick clicks list the folders once, not ten times
                }
                loadsStartedForTest.incrementAndGet();
                ProjectMapModel.Snapshot loaded;
                try {
                    loaded = ProjectMapModel.load(request);
                } catch (RuntimeException unreadable) {
                    // a closed SFTP file system throws unchecked; the map must still hear back
                    loaded = ProjectMapModel.Snapshot.EMPTY;
                }
                if (loaded == null) {
                    return; // cancelled part-way by a newer request
                }
                ProjectMapModel.Snapshot result = loaded;
                Platform.runLater(() -> applySnapshot(requested, requestedRoot, result));
            });
        } catch (java.util.concurrent.RejectedExecutionException shutDown) {
            loadFinished();
        }
    }

    private static String placeholderLabel(ProjectMapModel.PlaceholderKind kind, int remaining) {
        return switch (kind) {
            case MORE -> tr("project.map.row.more", remaining);
            case EMPTY -> tr("project.map.row.emptyFolder");
            case UNREADABLE -> tr("project.map.row.unreadableFolder");
        };
    }

    /** A load that outlasts {@link #LOADING_NOTICE_MILLIS} (a slow or remote folder) shows "Loading…". */
    private void loadStarted() {
        loadInFlight = true;
        surface.setLoadPending(true);
        if (loadingDelay.getStatus() != javafx.animation.Animation.Status.RUNNING && !loadingLabel.isVisible()) {
            loadingDelay.playFromStart();
        }
    }

    private void loadFinished() {
        loadInFlight = false;
        loadingDelay.stop();
        loadingLabel.setVisible(false);
        surface.setLoadPending(false);
    }

    private void applySnapshot(long requested, Path requestedRoot, ProjectMapModel.Snapshot loaded) {
        if (disposed || requested != generation.get()) {
            return;
        }
        loadFinished();
        if (RemoteReadFailure.connectionClosed(requestedRoot)) {
            onStatus.accept(RemoteReadFailure.unreadable(requestedRoot)); // not "an empty project"
        }
        snapshot = loaded;
        Set<Path> loadedPaths = new HashSet<>();
        for (ProjectMapModel.Entry entry : loaded.entries()) {
            loadedPaths.add(entry.path());
        }
        boolean expansionChanged = false;
        if (!loaded.entries().isEmpty()) {
            if (!searching()) {
                // Folders that did not load (renamed, deleted, replaced by a file) leave the manual set, and
                // so do those the overall limit left without a single row: neither is drawn as open.
                Set<Path> kept = ProjectMapModel.pruneExpansion(root, expanded, loaded);
                kept.removeAll(loaded.skipped());
                if (!kept.equals(expanded)) {
                    expanded.clear();
                    expanded.addAll(kept);
                    expansionChanged = true;
                }
            }
            directoryLimits.keySet().retainAll(loaded.loadedDirectories());
        }
        revealPins.retainAll(loadedPaths);
        Path selectedBefore = surface.selectedPath();
        surface.setEntries(loaded.entries(), loaded.loadedDirectories());
        setOutputEnabled(!loaded.entries().isEmpty());
        if (pendingSelection != null) {
            if (loadedPaths.contains(pendingSelection)) {
                surface.setSelected(pendingSelection);
            } else {
                dropHistory(pendingSelection); // gone: it must not be selected if it ever reappears
            }
            pendingSelection = null;
        }
        finishLoadMore(loaded, loadedPaths, selectedBefore);
        reportLimits(loaded, loadedPaths);
        updateNavigation();
        if (expansionChanged) {
            onExpandedChanged.run();
        }
    }

    /** After "+N more": follow the rows that arrived, or undo the raise when the overall limit gave none. */
    private void finishLoadMore(ProjectMapModel.Snapshot loaded, Set<Path> loadedPaths, Path selectedBefore) {
        Path directory = loadMoreDirectory;
        loadMoreDirectory = null;
        if (directory == null) {
            return;
        }
        ProjectMapModel.DirectoryFacts facts = loaded.directories().get(directory);
        if (facts == null || facts.loaded() <= loadMorePreviousLoaded) {
            directoryLimits.put(directory, loadMorePreviousLimit);
            return;
        }
        Path moreRow = ProjectMapModel.normalize(directory.resolve(ProjectMapModel.PLACEHOLDER_NAME));
        if (moreRow.equals(selectedBefore) && !loadedPaths.contains(moreRow)) {
            // The whole folder is loaded now, so the row that was selected is gone: move to its last row.
            Path last = null;
            for (ProjectMapModel.Entry entry : loaded.entries()) {
                if (directory.equals(entry.parent()) && !entry.isPlaceholder()) {
                    last = entry.path();
                }
            }
            if (last != null) {
                surface.select(last);
            }
        }
    }

    /** Nothing is dropped silently: say when matches or folders did not fit the overall limit. */
    private void reportLimits(ProjectMapModel.Snapshot loaded, Set<Path> loadedPaths) {
        if (searching()) {
            if (announceMatches && !searchMatches.isEmpty()) {
                int shown = 0;
                for (Path match : searchMatches) {
                    if (loadedPaths.contains(match)) {
                        shown++;
                    }
                }
                if (shown < searchMatches.size()) {
                    onStatus.accept(tr("project.map.status.matchesShown", shown, searchMatches.size()));
                }
            }
        } else if (loaded.capped() && (announceLimit || !wasCapped)) {
            onStatus.accept(tr("project.map.status.limit", ProjectMapModel.MAX_VISIBLE_ITEMS));
        }
        announceMatches = false;
        announceLimit = false;
        wasCapped = loaded.capped();
    }

    /** Removes a path that no longer resolves from the selection history, keeping the index on the selection. */
    private void dropHistory(Path dead) {
        boolean removed = false;
        for (int index = selectionHistory.size() - 1; index >= 0; index--) {
            if (com.editora.config.PathKeys.samePath(selectionHistory.get(index), dead)) {
                selectionHistory.remove(index);
                removed = true;
            }
        }
        if (!removed) {
            return;
        }
        Path current = surface.selectedPath();
        int nearest = -1;
        for (int index = 0; index < selectionHistory.size(); index++) {
            if (com.editora.config.PathKeys.samePath(selectionHistory.get(index), current)
                    && (nearest < 0 || Math.abs(index - historyIndex) < Math.abs(nearest - historyIndex))) {
                nearest = index;
            }
        }
        historyIndex = nearest >= 0 ? nearest : Math.min(historyIndex, selectionHistory.size() - 1);
    }

    private void activate(ProjectMapModel.Entry entry) {
        if (entry.isPlaceholder()) {
            if (entry.isMore()) {
                loadMore(entry.parent());
            }
            return; // "Empty folder" / "Cannot read this folder" do nothing
        }
        if (entry.directory()) {
            toggleDirectory(entry.path());
        } else {
            onOpenFile.accept(entry.path());
        }
    }

    /**
     * Opens or closes one folder. During a search this edits the search's own view of the tree — closing a
     * folder the search opened really closes it — and never the manual set the search will hand back.
     */
    private void toggleDirectory(Path directory) {
        Path path = ProjectMapModel.normalize(directory);
        if (searching()) {
            if (expandedDirectories().contains(path)) {
                closeDuringSearch(path);
            } else {
                searchClosed.remove(path);
                searchOpened.add(path);
            }
        } else {
            Set<Path> nextExpansion = ProjectMapModel.toggleExpansion(root, expanded, path);
            expanded.clear();
            expanded.addAll(nextExpansion);
        }
        reloadForUser();
        onExpandedChanged.run();
    }

    private void closeDuringSearch(Path directory) {
        searchOpened.removeIf(path -> path.startsWith(directory));
        searchClosed.removeIf(path -> path.startsWith(directory));
        searchClosed.add(directory);
    }

    /** Raises one truncated directory's row limit by a chunk; the overall limit still applies. */
    private void loadMore(Path directory) {
        Path path = ProjectMapModel.normalize(directory);
        if (path == null) {
            return;
        }
        ProjectMapModel.DirectoryFacts facts = snapshot.directories().get(path);
        loadMorePreviousLimit = directoryLimits.getOrDefault(path, ProjectMapModel.DIRECTORY_CHUNK);
        loadMorePreviousLoaded = facts == null ? 0 : facts.loaded();
        loadMoreDirectory = path;
        directoryLimits.put(
                path, Math.max(loadMorePreviousLimit, loadMorePreviousLoaded) + ProjectMapModel.DIRECTORY_CHUNK);
        reloadForUser();
    }

    private void closeColumn(Path parent) {
        Path normalized = ProjectMapModel.normalize(parent);
        if (normalized == null) {
            return;
        }
        if (searching()) {
            closeDuringSearch(normalized);
        } else {
            expanded.removeIf(path -> path.startsWith(normalized));
        }
        closePreviewsUnder(normalized);
        if (surface.selectionBelow(normalized)) { // a selection elsewhere stays where it is
            pendingSelection = normalized;
            surface.select(normalized);
        }
        reload();
        onExpandedChanged.run();
    }

    private void selectionChanged(Path path) {
        if (path == null) {
            return;
        }
        if (!navigatingHistory) {
            recordSelection(path, surface.movingSibling);
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
            touchCard(preview, true);
            preview.focusContent();
            return;
        }
        preview = createPreview(selected);
        ProjectMapPreview selectedPreview = preview;
        preview.showFile(
                entry.path(),
                previewContentFor(entry.path()),
                (width, height, parentWidth, parentHeight) -> previewPlacement(
                        selectedPreview,
                        entry.path(),
                        width,
                        height,
                        ProjectMapPreview.MIN_WIDTH,
                        ProjectMapPreview.MIN_HEIGHT,
                        parentWidth,
                        parentHeight));
        scheduleCardRefresh(); // starts following the buffer when the card mirrors one
    }

    private ProjectMapPreview.Content previewContentFor(Path path) {
        try {
            return previewContent.apply(path);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void previewNotes(Path path) {
        if (notePreviewActions == null || !notePreviewActions.personalNotesEnabled()) {
            return;
        }
        Path selected = path.toAbsolutePath().normalize();
        ProjectMapNotePreview existing = notePreviews.get(selected);
        if (existing != null) {
            existing.setVisible(!hideOpenNotes.isSelected());
            touchCard(existing, true);
            existing.focusContent();
            return;
        }
        List<PersonalNote> values = notePreviewActions.personalNotes(selected);
        if (values.isEmpty()) {
            return;
        }
        makeRoomForCard();
        ProjectMapNotePreview preview =
                new ProjectMapNotePreview((note, body) -> notePreviewActions.updatePersonalNote(selected, note, body));
        preview.setOnClose(() -> closeNotePreview(preview));
        preview.setOnActivate(() -> touchCard(preview, true));
        preview.setOnTouch(() -> touchCard(preview, false));
        preview.setOnEscape(() -> {
            closeNotePreview(preview);
            surface.requestFocus();
        });
        preview.setOnBlankRejected(() -> onStatus.accept(tr("status.projectMap.noteBlankRestored")));
        installPreviewListeners(preview);
        notePreviews.put(selected, preview);
        cardTouches.put(preview, ++cardTouchSequence);
        canvasHost.getChildren().add(preview);
        ProjectMapPreview.Placement placement = previewPlacement(
                preview,
                selected,
                ProjectMapNotePreview.DEFAULT_WIDTH,
                ProjectMapNotePreview.DEFAULT_HEIGHT,
                ProjectMapNotePreview.MIN_WIDTH,
                ProjectMapNotePreview.MIN_HEIGHT,
                canvasHost.getWidth(),
                canvasHost.getHeight());
        preview.showNotes(selected, values, placement);
        updateNotePreviewVisibility();
    }

    private ProjectMapPreview createPreview(Path path) {
        makeRoomForCard();
        ProjectMapPreview preview = new ProjectMapPreview(onOpenFile);
        preview.setMarkerActions(previewMarkerActions);
        preview.setOnClose(() -> closePreview(preview));
        preview.setOnActivate(() -> touchCard(preview, true));
        preview.setOnTouch(() -> touchCard(preview, false));
        preview.setOnEscape(() -> {
            closePreview(preview);
            surface.requestFocus();
        });
        installPreviewListeners(preview);
        previews.put(path, preview);
        cardTouches.put(preview, ++cardTouchSequence);
        canvasHost.getChildren().add(preview);
        return preview;
    }

    private void installPreviewListeners(Region preview) {
        preview.layoutXProperty().addListener((obs, old, value) -> repaintPreviewConnectors());
        preview.layoutYProperty().addListener((obs, old, value) -> repaintPreviewConnectors());
        preview.widthProperty().addListener((obs, old, value) -> repaintPreviewConnectors());
        preview.heightProperty().addListener((obs, old, value) -> repaintPreviewConnectors());
        preview.visibleProperty().addListener((obs, old, value) -> repaintPreviewConnectors());
    }

    /** Records use of an open card for eviction, and optionally raises it above the others. */
    private void touchCard(Region card, boolean raise) {
        if (!cardTouches.containsKey(card)) {
            return; // closed meanwhile
        }
        cardTouches.put(card, ++cardTouchSequence);
        if (raise) {
            card.toFront();
        }
    }

    /** Closes the least recently used cards, of either kind, until one more fits under the shared limit. */
    private void makeRoomForCard() {
        while (previews.size() + notePreviews.size() >= MAX_OPEN_PREVIEWS) {
            Region oldest = null;
            long oldestTouch = Long.MAX_VALUE;
            for (Map.Entry<Region, Long> touch : cardTouches.entrySet()) {
                if (touch.getValue() < oldestTouch) {
                    oldestTouch = touch.getValue();
                    oldest = touch.getKey();
                }
            }
            if (oldest instanceof ProjectMapPreview code) {
                closePreview(code);
            } else if (oldest instanceof ProjectMapNotePreview note) {
                closeNotePreview(note);
            } else {
                return;
            }
        }
    }

    private void closeNotePreview(ProjectMapNotePreview preview) {
        if (preview == null) {
            return;
        }
        notePreviews.entrySet().removeIf(entry -> entry.getValue() == preview);
        cardTouches.remove(preview);
        if (canvasHost != null) {
            canvasHost.getChildren().remove(preview);
        }
        preview.dispose();
        repaintPreviewConnectors();
    }

    private void updateNotePreviewVisibility() {
        boolean visible = !hideOpenNotes.isSelected();
        notePreviews.values().forEach(preview -> preview.setVisible(visible));
        repaintPreviewConnectors();
    }

    private void closePreview(ProjectMapPreview preview) {
        if (preview == null) {
            return;
        }
        previews.entrySet().removeIf(entry -> entry.getValue() == preview);
        cardTouches.remove(preview);
        if (canvasHost != null) {
            canvasHost.getChildren().remove(preview);
        }
        preview.dispose();
        repaintPreviewConnectors();
    }

    private void closeAllPreviews() {
        cardRefresh.stop();
        cardPoll.stop();
        cardDiskRefresh.stop();
        cardTouches.clear();
        List<ProjectMapPreview> open = List.copyOf(previews.values());
        previews.clear();
        for (ProjectMapPreview preview : open) {
            if (canvasHost != null) {
                canvasHost.getChildren().remove(preview);
            }
            preview.dispose();
        }
        List<ProjectMapNotePreview> openNotes = List.copyOf(notePreviews.values());
        notePreviews.clear();
        for (ProjectMapNotePreview preview : openNotes) {
            if (canvasHost != null) {
                canvasHost.getChildren().remove(preview);
            }
            preview.dispose();
        }
        repaintPreviewConnectors();
    }

    private void closePreviewsUnder(Path directory) {
        List<ProjectMapPreview> closing = previews.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(directory))
                .map(Map.Entry::getValue)
                .toList();
        closing.forEach(this::closePreview);
        List<ProjectMapNotePreview> closingNotes = notePreviews.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(directory))
                .map(Map.Entry::getValue)
                .toList();
        closingNotes.forEach(this::closeNotePreview);
    }

    /**
     * Closes every card whose row is no longer on the map because a folder above it is not expanded any
     * more — however it was collapsed (its row, its column's close button, history, a search that ended).
     * A row that is merely filtered out or scrolled away keeps its card.
     */
    private void closeOrphanedCards() {
        orphanCheckPending = false;
        Set<Path> shown = cardsCheckedAgainst;
        if (disposed || shown == null || root == null) {
            return;
        }
        List<ProjectMapPreview> closing = previews.entrySet().stream()
                .filter(entry -> orphaned(entry.getKey(), shown))
                .map(Map.Entry::getValue)
                .toList();
        closing.forEach(this::closePreview);
        List<ProjectMapNotePreview> closingNotes = notePreviews.entrySet().stream()
                .filter(entry -> orphaned(entry.getKey(), shown))
                .map(Map.Entry::getValue)
                .toList();
        closingNotes.forEach(this::closeNotePreview);
    }

    private boolean orphaned(Path path, Set<Path> shown) {
        if (!path.startsWith(root)) {
            return false;
        }
        for (Path parent = path.getParent(); parent != null && !parent.equals(root); parent = parent.getParent()) {
            if (!shown.contains(parent)) {
                return true;
            }
        }
        return false;
    }

    /** Asks for {@link #refreshCards} once the notifications that prompted it have settled. */
    private void scheduleCardRefresh() {
        if (disposed || getScene() == null || previews.isEmpty() && notePreviews.isEmpty()) {
            return; // off screen (the Tree is showing): the next time the Map is shown refreshes them
        }
        cardRefresh.playFromStart();
    }

    /**
     * Brings open cards up to date: code cards with their buffer's text or their file on disk, note cards
     * with the store. A {@code polled} pass only looks at cards that mirror an open buffer, and keeps
     * running at {@link #CARD_POLL_INTERVAL} while there is one and the Map is on screen.
     */
    private void refreshCards(boolean polled) {
        if (disposed) {
            return;
        }
        boolean mirrors = false;
        for (Map.Entry<Path, ProjectMapPreview> entry : List.copyOf(previews.entrySet())) {
            ProjectMapPreview card = entry.getValue();
            if (!polled || card.showsOpenBuffer()) {
                card.refresh(previewContentFor(entry.getKey()));
            }
            mirrors |= card.showsOpenBuffer();
        }
        if (!polled) {
            refreshNoteCards();
        }
        if (mirrors && getScene() != null) {
            cardPoll.playFromStart();
        } else {
            cardPoll.stop();
        }
    }

    private void refreshNoteCards() {
        boolean enabled = notePreviewActions != null && notePreviewActions.personalNotesEnabled();
        for (Map.Entry<Path, ProjectMapNotePreview> entry : List.copyOf(notePreviews.entrySet())) {
            List<PersonalNote> values;
            try {
                values = enabled ? notePreviewActions.personalNotes(entry.getKey()) : List.of();
            } catch (RuntimeException ignored) {
                continue;
            }
            entry.getValue().refreshNotes(values);
            if (values.isEmpty()) {
                closeNotePreview(entry.getValue()); // its notes were deleted elsewhere: nothing left to show
            }
        }
    }

    private void constrainPreviews(double width, double height) {
        previews.values().forEach(preview -> preview.constrainTo(width, height));
        notePreviews.values().forEach(preview -> preview.constrainTo(width, height));
    }

    /**
     * The one placement path for code and note cards: beside the row's column when there is room for the
     * card's minimum size, otherwise over the map inside the panel, then shifted or cascaded clear of the
     * cards already open. The map itself is never panned to make room.
     */
    private ProjectMapPreview.Placement previewPlacement(
            Region preview,
            Path path,
            double width,
            double height,
            double minimumWidth,
            double minimumHeight,
            double parentWidth,
            double parentHeight) {
        ProjectMapPreview.Placement preferred =
                surface.previewPlacement(path, width, height, minimumWidth, minimumHeight, parentWidth, parentHeight);
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
        int others = previews.size() + notePreviews.size();
        for (int ring = 1; ring <= others; ring++) {
            for (int sign : new int[] {1, -1}) {
                double shifted = clampPreview(origin + sign * ring * step, ProjectMapPreview.EDGE_MARGIN, maximum);
                double x = horizontal ? preferred.x() : shifted;
                double y = horizontal ? shifted : preferred.y();
                if (!overlapsPreview(preview, x, y, preferred.width(), preferred.height())) {
                    return new ProjectMapPreview.Placement(
                            x, y, preferred.width(), preferred.height(), preferred.growLeft());
                }
            }
        }

        int index = Math.max(1, others - 1);
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
        return new ProjectMapPreview.Placement(x, y, preferred.width(), preferred.height(), preferred.growLeft());
    }

    private boolean overlapsPreview(Region candidate, double x, double y, double width, double height) {
        List<Region> all = new ArrayList<>(previews.values());
        all.addAll(notePreviews.values());
        for (Region other : all) {
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

    private void repaintPreviewConnectors() {
        GraphicsContext g = previewConnectorCanvas.getGraphicsContext2D();
        g.clearRect(0, 0, previewConnectorCanvas.getWidth(), previewConnectorCanvas.getHeight());
        previewConnectors.clear();
        // Every reload hands the surface a new expansion set: one reference comparison per repaint tells
        // whether a folder may have been collapsed under an open card.
        Set<Path> shown = surface.expandedSnapshot;
        if (shown != cardsCheckedAgainst) {
            cardsCheckedAgainst = shown;
            if (!orphanCheckPending && !(previews.isEmpty() && notePreviews.isEmpty())) {
                orphanCheckPending = true;
                Platform.runLater(this::closeOrphanedCards); // not from inside a paint or a layout pass
            }
        }
        g.setStroke(surface.accentColor());
        g.setGlobalAlpha(0.78);
        g.setLineWidth(1.5);
        for (Map.Entry<Path, ProjectMapPreview> entry : previews.entrySet()) {
            ProjectMapPreview preview = entry.getValue();
            MapSurface.NodeBox anchor = surface.nodeBox(entry.getKey());
            if (!preview.isVisible() || anchor == null || preview.getWidth() <= 0 || preview.getHeight() <= 0) {
                continue;
            }
            previewConnectors.put(entry.getKey(), drawPreviewConnector(g, anchor, preview));
        }
        g.setStroke(surface.noteColor());
        for (Map.Entry<Path, ProjectMapNotePreview> entry : notePreviews.entrySet()) {
            ProjectMapNotePreview preview = entry.getValue();
            MapSurface.NodeBox anchor = surface.nodeBox(entry.getKey());
            if (!preview.isVisible() || anchor == null || preview.getWidth() <= 0 || preview.getHeight() <= 0) {
                continue;
            }
            drawPreviewConnector(g, anchor, preview);
        }
        g.setGlobalAlpha(1);
    }

    private PreviewConnector drawPreviewConnector(GraphicsContext g, MapSurface.NodeBox anchor, Region preview) {
        double x1;
        double y1;
        double x2;
        double y2;
        boolean horizontal = flowFilter.getValue() == FlowDirection.LEFT_TO_RIGHT
                || flowFilter.getValue() == FlowDirection.RIGHT_TO_LEFT;
        if (horizontal) {
            boolean previewAfter = preview.getLayoutX() + preview.getWidth() / 2 >= anchor.x() + anchor.width() / 2;
            x1 = previewAfter ? anchor.x() + anchor.width() : anchor.x();
            y1 = anchor.y() + anchor.height() / 2;
            x2 = previewAfter ? preview.getLayoutX() : preview.getLayoutX() + preview.getWidth();
            y2 = clampPreview(y1, preview.getLayoutY(), preview.getLayoutY() + preview.getHeight());
            double control = Math.max(18, Math.abs(x2 - x1) * 0.45);
            g.beginPath();
            g.moveTo(x1, y1);
            g.bezierCurveTo(
                    x1 + (previewAfter ? control : -control), y1, x2 + (previewAfter ? -control : control), y2, x2, y2);
        } else {
            boolean previewAfter = preview.getLayoutY() + preview.getHeight() / 2 >= anchor.y() + anchor.height() / 2;
            x1 = anchor.x() + anchor.width() / 2;
            y1 = previewAfter ? anchor.y() + anchor.height() : anchor.y();
            x2 = clampPreview(x1, preview.getLayoutX(), preview.getLayoutX() + preview.getWidth());
            y2 = previewAfter ? preview.getLayoutY() : preview.getLayoutY() + preview.getHeight();
            double control = Math.max(18, Math.abs(y2 - y1) * 0.45);
            g.beginPath();
            g.moveTo(x1, y1);
            g.bezierCurveTo(
                    x1, y1 + (previewAfter ? control : -control), x2, y2 + (previewAfter ? -control : control), x2, y2);
        }
        g.stroke();
        return new PreviewConnector(x1, y1, x2, y2);
    }

    private record PreviewConnector(double startX, double startY, double endX, double endY) {}

    private void recordSelection(Path path) {
        recordSelection(path, false);
    }

    /**
     * Appends a selection to the Back/Forward history. A run of sibling moves (arrows, Page Up/Down,
     * Ctrl-N/Ctrl-P) is one entry — the row the run ended on — so Back does not retrace every keystroke, and
     * the list is capped at {@link #MAX_SELECTION_HISTORY}.
     */
    private void recordSelection(Path path, boolean siblingMove) {
        Path normalized = ProjectMapModel.normalize(path);
        if (normalized == null
                || historyIndex >= 0
                        && com.editora.config.PathKeys.samePath(selectionHistory.get(historyIndex), normalized)) {
            return;
        }
        boolean atHead = historyIndex == selectionHistory.size() - 1;
        if (!atHead) {
            selectionHistory.subList(historyIndex + 1, selectionHistory.size()).clear();
        }
        if (siblingMove && atHead && historyHeadFromSiblingMove && historyIndex >= 0) {
            selectionHistory.set(historyIndex, normalized);
            if (historyIndex > 0
                    && com.editora.config.PathKeys.samePath(selectionHistory.get(historyIndex - 1), normalized)) {
                selectionHistory.remove(historyIndex--); // the run came back to where it started
                historyHeadFromSiblingMove = false;
            }
        } else {
            selectionHistory.add(normalized);
            if (selectionHistory.size() > MAX_SELECTION_HISTORY) {
                selectionHistory.removeFirst();
            }
            historyIndex = selectionHistory.size() - 1;
            historyHeadFromSiblingMove = siblingMove;
        }
        updateNavigation();
    }

    private void moveHistory(int delta) {
        int target = historyIndex + delta;
        if (target < 0 || target >= selectionHistory.size()) {
            return;
        }
        historyIndex = target;
        historyHeadFromSiblingMove = false;
        navigatingHistory = true;
        try {
            revealPath(selectionHistory.get(target));
        } finally {
            navigatingHistory = false;
        }
        updateNavigation();
    }

    void revealPath(Path path) {
        Path normalized = ProjectMapModel.normalize(path);
        if (normalized == null
                || root == null
                || !com.editora.config.PathKeys.sameFileSystem(normalized, root)
                || !normalized.startsWith(root)) {
            return;
        }
        if (surface.contains(normalized)) {
            surface.select(normalized);
            return;
        }
        // Open the way to the target and leave every other open branch as it is.
        Set<Path> chain = ProjectMapModel.ancestorsWithin(root, List.of(normalized));
        if (searching()) {
            searchClosed.removeIf(normalized::startsWith);
            searchOpened.addAll(chain);
        } else {
            expanded.addAll(chain);
        }
        // Keep the target loaded even when it sorts beyond its folder's row limit.
        revealPins.add(normalized);
        while (revealPins.size() > MAX_REVEAL_PINS) {
            revealPins.remove(revealPins.iterator().next());
        }
        pendingSelection = normalized;
        reloadForUser();
        onExpandedChanged.run();
    }

    private void updateNavigation() {
        backButton.setDisable(historyIndex <= 0);
        forwardButton.setDisable(historyIndex < 0 || historyIndex >= selectionHistory.size() - 1);
        Path selected = surface.selectedEntry().map(ProjectMapModel.Entry::path).orElse(pendingSelection);
        // The surface may still hold the previous root's selection, which can be on another connection.
        if (selected == null
                || root == null
                || !com.editora.config.PathKeys.sameFileSystem(selected, root)
                || !selected.startsWith(root)) {
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
            if (!com.editora.config.PathKeys.samePath(path, selected)) {
                Label separator = new Label("›");
                separator.getStyleClass().add("project-map-breadcrumb-separator");
                separator.setMinWidth(Region.USE_PREF_SIZE);
                nodes.add(separator);
            }
        }
        breadcrumbs.getChildren().setAll(nodes);
        fitBreadcrumbs();
    }

    /**
     * Keeps the trail readable when it is wider than the row: the middle folders collapse into one "…"
     * (its tooltip lists them) while the root and the last two crumbs keep their names. Without this every
     * crumb shrank at once and the row became a string of "…".
     */
    private void fitBreadcrumbs() {
        List<Node> children = breadcrumbs.getChildren();
        children.removeAll(List.of(breadcrumbEllipsis, breadcrumbEllipsisSeparator));
        int crumbs = (children.size() + 1) / 2; // crumb, separator, crumb, … crumb
        for (Node child : children) {
            child.setVisible(true);
            child.setManaged(true);
        }
        double available = breadcrumbs.getWidth();
        if (children.isEmpty() || available <= 0) {
            return;
        }
        breadcrumbs.applyCss();
        double spacing = breadcrumbs.getSpacing();
        double[] widths = new double[children.size()];
        double total = spacing * (children.size() - 1);
        for (int i = 0; i < widths.length; i++) {
            widths[i] = children.get(i).prefWidth(-1);
            total += widths[i];
        }
        double ellipsis = breadcrumbEllipsis.prefWidth(-1);
        int hidden = hiddenBreadcrumbs(widths, spacing, ellipsis, available, total);
        // What is still too wide is taken from the last crumb alone (it elides its own text) for as long as
        // that leaves it something to show; the crumbs before it keep their whole names.
        double leading = hidden == 0 ? 0 : ellipsis + widths[1] + spacing * 2;
        for (int i = 0; i < widths.length - 1; i++) {
            if (i < 2 || i > hidden * 2 + 1) {
                leading += widths[i] + spacing;
            }
        }
        boolean lastGives = available - leading >= 48;
        for (int i = 0; i < widths.length - 1; i += 2) {
            ((Region) children.get(i)).setMinWidth(lastGives ? Region.USE_PREF_SIZE : Region.USE_COMPUTED_SIZE);
        }
        if (hidden == 0) {
            return;
        }
        // Crumb k sits at child 2k and its separator at 2k + 1; crumbs 1..hidden give way to the "…".
        StringBuilder names = new StringBuilder();
        for (int crumb = 1; crumb <= hidden; crumb++) {
            Node node = children.get(crumb * 2);
            Node separator = children.get(crumb * 2 + 1);
            node.setVisible(false);
            node.setManaged(false);
            separator.setVisible(false);
            separator.setManaged(false);
            if (node instanceof Button button) {
                names.append(names.isEmpty() ? "" : " › ").append(button.getText());
            }
        }
        Icons.name(breadcrumbEllipsis, names.toString());
        children.add(2, breadcrumbEllipsis);
        children.add(3, breadcrumbEllipsisSeparator);
    }

    /**
     * How many crumbs after the root must collapse into the "…" for the trail to fit. The root and the
     * last two crumbs are never collapsed; {@code widths} alternates crumb and separator widths.
     */
    static int hiddenBreadcrumbs(double[] widths, double spacing, double ellipsis, double available, double total) {
        int crumbs = (widths.length + 1) / 2;
        int collapsible = crumbs - 3;
        if (total <= available || collapsible <= 0) {
            return 0;
        }
        // The "…" replaces the hidden crumbs but brings its own separator and two more gaps.
        double width = total + ellipsis + widths[1] + spacing * 2;
        for (int hidden = 1; hidden <= collapsible; hidden++) {
            width -= widths[hidden * 2] + widths[hidden * 2 + 1] + spacing * 2;
            if (width <= available) {
                return hidden;
            }
        }
        return collapsible;
    }

    /** Canvas surface with a single keyboard focus target and deterministic screen-space hit boxes. */
    private final class MapSurface extends Region {

        private static final double MIN_NODE_WIDTH = 164;
        private static final double NODE_HEIGHT = 32;
        private static final double COLUMN_GAP = 50;
        private static final double ROW_GAP = 9;
        private static final double WORLD_PADDING = 22;
        private static final double COLUMN_HEADER_HEIGHT = 70;
        /** Header of a column whose filter, hidden-files and lock controls are not shown: the title line. */
        private static final double COMPACT_HEADER_HEIGHT = 28;
        /**
         * How far a column may start before the row that opened it, along the cross axis. A short column
         * is centred on its parent; a long one hangs from it, so its header, its first rows and the
         * parent row can be on screen together.
         */
        private static final double MAX_COLUMN_LEAD = 150;
        /** Trailing strip of a folder row that holds its chevron (and takes the collapse/expand click). */
        private static final double CHEVRON_ZONE = 24;
        /** Trailing strip of a file row that holds the preview affordance. */
        private static final double PREVIEW_ZONE = 29;
        /** Width reserved in a row for one bookmark or Personal Note badge. */
        private static final double MARKER_WIDTH = 13;

        private static final double VIEW_MARGIN = 20;
        /** Width of the hidden-files checkbox once its label has been dropped for lack of room. */
        private static final double CHECK_ONLY_WIDTH = 20;
        /** Style class that turns a rasterised row glyph into on-accent ink (see app.css). */
        private static final String ON_ACCENT_ICON_CLASS = "project-map-icon-on-accent";

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
        /** The smallest scale the map picks by itself; below it labels stop being readable. */
        private static final double MIN_AUTO_FIT_ZOOM = 0.85;
        /** Rows that do not match an active filter keep this much of their ink. */
        private static final double DIMMED_ALPHA = 0.38;

        /** White space around the map on paper, in world pixels. */
        private static final double OUTPUT_MARGIN = 12;
        /**
         * Prism's default texture cap ({@code prism.maxTextureSize}). A Canvas is backed by one texture of
         * its size times the highest screen scale, rounded up — whether or not it is in a scene — and one
         * that would exceed the cap draws nothing. A page is therefore assembled from bounded renders
         * instead of being painted on one page-sized Canvas.
         */
        private static final int OUTPUT_TEXTURE_LIMIT = 4_096;
        /** Longest side of one off-scene render on a 1x or 2x screen; smaller on a denser one. */
        private static final int OUTPUT_TILE = 2_048;

        private static final double CONNECTOR_VIEWPORT_OVERSCAN = 24;
        /** Zoom levels tried, in order, to bring a hidden column filter back for the {@code /} key. */
        private static final double[] FILTER_ZOOM_STEPS = {0.8, 1.0};
        /** The same light palette for the row icons, which are styled nodes rasterized through CSS. */
        private static final String OUTPUT_ICON_STYLE = "-project-folder-color: #0969da; -project-file-color: #59636e;"
                + " -state-amber: #9a6700; -state-olive: #6e7b25; -state-violet: #8250df;"
                + " -color-accent-fg: #0969da; -color-success-fg: #1a7f37; -color-danger-fg: #d1242f;"
                + " -color-fg-muted: #59636e;";

        private final Canvas liveCanvas = new Canvas(1, 1);
        /** What {@link #paint} draws on: the live Canvas, or an off-scene one while output is rendered. */
        private Canvas canvas = liveCanvas;
        /** Non-null only while print/PDF output is rendered: the light colours that replace the theme's. */
        private Map<Rectangle, Color> outputPalette;

        private final Rectangle viewportClip = new Rectangle();
        private final StackPane iconRasterizer = new StackPane();
        private final Rectangle bgProbe = probe("project-map-probe-bg");
        private final Rectangle surfaceProbe = probe("project-map-probe-surface");
        private final Rectangle borderProbe = probe("project-map-probe-border");
        private final Rectangle textProbe = probe("project-map-probe-text");
        private final Rectangle mutedProbe = probe("project-map-probe-muted");
        private final Rectangle accentProbe = probe("project-map-probe-accent");
        /** Accent as ink on a neutral surface ({@code -color-accent-fg}); the emphasis fill is too pale for text. */
        private final Rectangle accentTextProbe = probe("project-map-probe-accent-fg");

        private final Rectangle accentSubtleProbe = probe("project-map-probe-accent-subtle");
        private final Rectangle focusRingProbe = probe("project-map-probe-focus-ring");
        private final Rectangle dangerProbe = probe("project-map-probe-danger");
        /** Ink on the accent fill ({@code -color-fg-emphasis}): white suits a dark accent only. */
        private final Rectangle onAccentProbe = probe("project-map-probe-on-accent");

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
        private final AnimationTimer viewportRepaintTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                stop();
                if (!viewportRepaintPending) {
                    return;
                }
                viewportRepaintPending = false;
                repaint();
            }
        };

        private List<ProjectMapModel.Entry> entries = List.of();
        private Set<Path> expandedSnapshot = Set.of();
        private ProjectMapModel.Filters filters =
                new ProjectMapModel.Filters("", false, false, false, false, false, ProjectMapModel.TypeFilter.ALL);
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
        private FlowDirection flowDirection = DEFAULT_FLOW;
        private Path selected;
        private Path hovered;
        /** The part of the hovered row under the pointer, when it is one that acts differently from the row. */
        private Affordance hoveredAffordance = Affordance.NONE;

        private java.util.function.Consumer<Boolean> onNoMatchesChanged = none -> {};
        private boolean noMatches;
        /** Strip along the bottom edge, and its width from the left, covered by the floating zoom bar. */
        private double reservedBottom;

        private double reservedBarWidth;
        /**
         * Set by the Print/PDF snapshot while it paints the whole map: there are no native column controls
         * in that image, so headers collapse to their title line, and focus, hover and the overview are
         * left out.
         */
        private boolean outputPaint;

        private Consumer<ProjectMapModel.Entry> onActivate = entry -> {};
        private Consumer<Path> onCloseColumn = path -> {};
        private Consumer<Path> onPreview = path -> {};
        private Consumer<Path> onNotesPreview = path -> {};
        private Consumer<Path> onSelectionChanged = path -> {};
        private Function<ProjectMapModel.Entry, ContextMenu> contextMenuFactory = entry -> null;
        private Runnable onZoomChanged = () -> {};
        private double zoom = 1.0;
        private boolean keepZoomOnColumnOpen = true;
        private boolean focusNewColumn = true;
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
        /** The pointer moved the viewport or a column since the last press, so its release is not a click. */
        private boolean dragMoved;
        /** Set while {@link #moveSibling} selects, so the history can fold a run of such moves into one entry. */
        private boolean movingSibling;

        private boolean pointerInside;
        private double pointerX;
        private double pointerY;
        private final javafx.beans.value.ChangeListener<Node> focusOwnerListener = this::focusOwnerChanged;
        private boolean painting;
        private boolean viewportRepaintPending;
        private boolean viewportInitialized;
        private boolean initialFitPending;
        /** A load is in flight: an empty map then reads "Loading…", not "No project items". */
        private boolean loadPending;
        /** Set while the view itself moves the "Show hidden" checkboxes, so that is not taken for a click. */
        private boolean syncingHidden;

        private double showHiddenLabelWidth;
        private Font rowFont;
        private Font rowFontBold;
        private final Map<String, String> elidedTitles = new HashMap<>();
        private final String columnNoMatches = tr("project.map.column.noMatches");
        private final String columnFilterPrompt = tr("project.map.column.filter");
        private final String columnFilterShortPrompt = tr("project.map.column.filterShort");
        private int lastPaintedConnectorCount;
        private long completedPaints;

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
                            accentTextProbe,
                            accentSubtleProbe,
                            focusRingProbe,
                            dangerProbe,
                            onAccentProbe,
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
                    accentTextProbe,
                    accentSubtleProbe,
                    focusRingProbe,
                    dangerProbe,
                    onAccentProbe,
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
            addEventHandler(MouseEvent.MOUSE_EXITED, this::mouseExited);
            addEventHandler(MouseEvent.MOUSE_CLICKED, this::mouseClicked);
            addEventHandler(ContextMenuEvent.CONTEXT_MENU_REQUESTED, this::contextMenuRequested);
            addEventHandler(ScrollEvent.SCROLL, this::scrolled);
            addEventHandler(ZoomEvent.ZOOM, this::pinched);
            addEventFilter(KeyEvent.KEY_PRESSED, this::keyPressed);
            addEventFilter(KeyEvent.KEY_TYPED, this::keyTyped);
            sceneProperty().addListener((obs, old, scene) -> {
                if (old != null) {
                    old.focusOwnerProperty().removeListener(focusOwnerListener);
                }
                if (scene != null) {
                    scene.focusOwnerProperty().addListener(focusOwnerListener);
                }
                claimKeys(scene != null && scene.getFocusOwner() == this);
            });
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

        void setOnNotesPreview(Consumer<Path> onNotesPreview) {
            this.onNotesPreview = onNotesPreview == null ? path -> {} : onNotesPreview;
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

        void setLoadPending(boolean pending) {
            if (loadPending != pending) {
                loadPending = pending;
                if (entries.isEmpty()) {
                    repaint();
                }
            }
        }

        Path selectedPath() {
            return selected;
        }

        /** Each column's own "Show hidden" choice, by the directory it lists; absent means the setting. */
        Map<Path, Boolean> hiddenOverrides() {
            Map<Path, Boolean> result = new HashMap<>();
            columnShowHidden.forEach((id, show) -> {
                if (id.parent() != null) {
                    result.put(id.parent(), show);
                }
            });
            return result;
        }

        /** Puts every column's "Show hidden" checkbox back on the Project setting. */
        void resetColumnHidden() {
            syncingHidden = true;
            try {
                columnControls
                        .values()
                        .forEach(controls -> controls.showHidden().setSelected(showHiddenDefault));
            } finally {
                syncingHidden = false;
            }
            columnShowHidden.clear();
            measuredLabelWidths.clear();
            repaint();
        }

        void setEntries(List<ProjectMapModel.Entry> entries, Set<Path> expanded) {
            Set<ProjectMapModel.ColumnId> oldColumnIds = this.entries.stream()
                    .map(entry -> new ProjectMapModel.ColumnId(entry.depth(), entry.parent()))
                    .collect(java.util.stream.Collectors.toSet());
            this.entries = entries == null ? List.of() : List.copyOf(entries);
            Set<ProjectMapModel.ColumnId> newColumnIds = this.entries.stream()
                    .map(entry -> new ProjectMapModel.ColumnId(entry.depth(), entry.parent()))
                    .collect(java.util.stream.Collectors.toSet());
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
            } else {
                newColumnIds.removeAll(oldColumnIds);
                newColumnIds.stream()
                        .max(java.util.Comparator.comparingInt(ProjectMapModel.ColumnId::depth))
                        .ifPresent(id -> Platform.runLater(() -> showOpenedColumn(id)));
            }
        }

        void setKeepZoomOnColumnOpen(boolean keep) {
            keepZoomOnColumnOpen = keep;
        }

        void setFocusNewColumn(boolean focus) {
            focusNewColumn = focus;
        }

        private void showOpenedColumn(ProjectMapModel.ColumnId id) {
            if (!keepZoomOnColumnOpen) {
                autoFit();
            }
            if (focusNewColumn) {
                revealColumnStart(id);
            }
        }

        /**
         * Brings a newly opened column into view from its start: the whole card when it fits beside the row
         * that opened it, otherwise its header and first rows with that row — never the middle of a long
         * list, which is where centring the card used to land.
         */
        private void revealColumnStart(ProjectMapModel.ColumnId id) {
            double[] pan = new double[2];
            if (panToColumnStart(pan, id)) {
                offsetX += pan[0];
                offsetY += pan[1];
                repaint();
            }
        }

        private boolean panToColumnStart(double[] pan, ProjectMapModel.ColumnId id) {
            ColumnBox box = columnBox(id);
            if (box == null) {
                return false;
            }
            NodeBox parent = id.parent() == null ? null : nodeBox(id.parent());
            double right = box.x() + box.width();
            double bottom = box.y() + box.height();
            // The card's start: its header and first rows (or, in a vertical flow, its first two rows).
            double headRight = right;
            double headBottom = bottom;
            if (isVerticalFlow()) {
                headRight = Math.min(right, box.x() + (2 * (MIN_NODE_WIDTH + ROW_GAP) + 20) * zoom);
            } else {
                double header = columnHeaderHeight(box.column().depth(), box.width() / zoom);
                headBottom =
                        Math.min(bottom, box.y() + (COLUMN_TOP_INSET + header + 3 * (NODE_HEIGHT + ROW_GAP)) * zoom);
            }
            if (parent != null) {
                include(pan, parent.x(), parent.y(), parent.x() + parent.width(), parent.y() + parent.height());
            }
            include(pan, box.x(), box.y(), headRight, headBottom); // the column wins over its parent row
            if (parent != null) {
                double left = Math.min(box.x(), parent.x());
                double top = Math.min(box.y(), parent.y());
                double parentRight = parent.x() + parent.width();
                double parentBottom = parent.y() + parent.height();
                if (fitsView(left, top, Math.max(right, parentRight), Math.max(bottom, parentBottom))) {
                    include(pan, left, top, Math.max(right, parentRight), Math.max(bottom, parentBottom));
                } else if (fitsView(left, top, Math.max(headRight, parentRight), Math.max(headBottom, parentBottom))) {
                    include(pan, left, top, Math.max(headRight, parentRight), Math.max(headBottom, parentBottom));
                }
            } else if (fitsView(box.x(), box.y(), right, bottom)) {
                include(pan, box.x(), box.y(), right, bottom);
            }
            return true;
        }

        /** Adds to {@code pan} the smallest shift that shows the rectangle (given in current screen terms). */
        private void include(double[] pan, double left, double top, double right, double bottom) {
            pan[0] += shiftIntoRange(left + pan[0], right + pan[0], viewLeft(), viewRight());
            pan[1] += shiftIntoRange(top + pan[1], bottom + pan[1], viewTop(), viewBottom());
        }

        private boolean fitsView(double left, double top, double right, double bottom) {
            return right - left <= viewRight() - viewLeft() && bottom - top <= viewBottom() - viewTop();
        }

        // The part of the surface a row may be revealed into: inside a margin and clear of the zoom bar.
        private double viewLeft() {
            return Math.min(VIEW_MARGIN, getWidth() * 0.05);
        }

        private double viewTop() {
            return Math.min(VIEW_MARGIN, getHeight() * 0.05);
        }

        private double viewRight() {
            return getWidth() - viewLeft();
        }

        private double viewBottom() {
            return getHeight() - Math.max(viewTop(), Math.min(reservedBottom, getHeight() * 0.4));
        }

        void setReservedZoomBar(double width, double height) {
            if (reservedBarWidth == width && reservedBottom == height) {
                return;
            }
            reservedBarWidth = Math.max(0, width);
            reservedBottom = Math.max(0, height);
            requestViewportRepaint(); // the overview keeps clear of the bar
        }

        void setOnNoMatchesChanged(java.util.function.Consumer<Boolean> callback) {
            onNoMatchesChanged = callback == null ? none -> {} : callback;
        }

        /** True while a query or filter chip is active and no loaded row matches it. */
        boolean hasNoMatches() {
            return noMatches;
        }

        private ColumnBox columnBox(ProjectMapModel.ColumnId id) {
            for (ColumnBox candidate : columnBoxes) {
                if (candidate.column().id().equals(id)) {
                    return candidate;
                }
            }
            return null;
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
            syncingHidden = true;
            try {
                for (ColumnControls controls : columnControls.values()) {
                    controls.filter().clear();
                    controls.showHidden().setSelected(showHiddenDefault);
                }
            } finally {
                syncingHidden = false;
            }
            columnQueries.clear();
            columnShowHidden.clear();
            measuredLabelWidths.clear();
            repaint();
        }

        void setFlowDirection(FlowDirection requested) {
            FlowDirection next = requested == null ? DEFAULT_FLOW : requested;
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
            Platform.runLater(this::autoFit);
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
            viewportRepaintPending = false;
            viewportRepaintTimer.stop();
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
            viewportRepaintPending = false;
            viewportRepaintTimer.stop();
            painting = true;
            try {
                paint();
                if (syncHover()) {
                    paint(); // the row under a resting pointer changed with the viewport
                }
                completedPaints++;
                repaintPreviewConnectors();
            } finally {
                painting = false;
            }
        }

        /** Coalesces continuous viewport gestures so they submit no more than one Canvas render per pulse. */
        private void requestViewportRepaint() {
            if (viewportRepaintPending || getWidth() <= 0 || getHeight() <= 0) {
                return;
            }
            viewportRepaintPending = true;
            viewportRepaintTimer.start();
        }

        /** World-space bounds of every laid-out column plus the paper margin, or null for an empty map. */
        private ProjectMapOutputPlan.Box outputBounds() {
            if (entries.isEmpty() || columnBoxes.isEmpty()) {
                return null;
            }
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            for (ColumnBox box : columnBoxes) {
                minX = Math.min(minX, (box.x() - offsetX) / zoom);
                minY = Math.min(minY, (box.y() - offsetY) / zoom);
                maxX = Math.max(maxX, (box.x() + box.width() - offsetX) / zoom);
                maxY = Math.max(maxY, (box.y() + box.height() - offsetY) / zoom);
            }
            return new ProjectMapOutputPlan.Box(
                    minX - OUTPUT_MARGIN,
                    minY - OUTPUT_MARGIN,
                    Math.max(1, maxX - minX) + OUTPUT_MARGIN * 2,
                    Math.max(1, maxY - minY) + OUTPUT_MARGIN * 2);
        }

        private ProjectMapOutputPlan.Box worldBox(double x, double y, double width, double height) {
            return new ProjectMapOutputPlan.Box(
                    (x - offsetX) / zoom, (y - offsetY) / zoom, width / zoom, height / zoom);
        }

        boolean outputIsLandscape() {
            outputPaint = true; // measure the layout that output paints: headers collapsed to their title
            try {
                repaint();
                ProjectMapOutputPlan.Box bounds = outputBounds();
                return bounds != null && bounds.width() > bounds.height();
            } finally {
                outputPaint = false;
                repaint();
            }
        }

        /**
         * Renders every laid-out column for pages of the given printable size, independent of the current
         * pan and zoom and always in a light palette. Painting goes to an off-scene Canvas, so the live
         * Canvas, viewport and hover state are untouched; interactive controls and the overview are left out.
         */
        ProjectMapOutput.Rendered renderOutput(double pageWidth, double pageHeight) {
            // Headers collapse when their controls do not fit, so the live layout depends on the zoom. With
            // outputPaint set they are always collapsed (there are no controls on paper): the layout measured
            // here is then the one painted on the pages at the output scale.
            outputPaint = true;
            try {
                return renderOutputPages(pageWidth, pageHeight);
            } finally {
                if (outputPaint) { // left before any page was painted: nothing restored the live layout
                    outputPaint = false;
                    repaint();
                }
            }
        }

        private ProjectMapOutput.Rendered renderOutputPages(double pageWidth, double pageHeight) {
            repaint();
            ProjectMapOutputPlan.Box bounds = outputBounds();
            if (bounds == null) {
                return null;
            }
            List<ProjectMapOutputPlan.Box> obstacles = new ArrayList<>(columnBoxes.size() + boxes.size());
            for (ColumnBox box : columnBoxes) {
                obstacles.add(worldBox(box.x(), box.y(), box.width(), box.height()));
            }
            for (NodeBox box : boxes) {
                obstacles.add(worldBox(box.x(), box.y(), box.width(), box.height()));
            }
            ProjectMapOutputPlan.Plan plan = ProjectMapOutputPlan.plan(bounds, obstacles, pageWidth, pageHeight);
            if (plan.pages().isEmpty() || !Double.isFinite(plan.renderScale()) || plan.renderScale() <= 0) {
                return null;
            }

            double liveZoom = zoom;
            double liveOffsetX = offsetX;
            double liveOffsetY = offsetY;
            Path liveHovered = hovered;
            Map<IconKey, Image> liveIcons = new HashMap<>(iconImages);
            String liveIconStyle = iconRasterizer.getStyle();
            List<Image> pages = new ArrayList<>(plan.pages().size());
            painting = true;
            try {
                canvas = new Canvas(1, 1);
                outputPalette = outputPalette();
                iconImages.clear();
                iconRasterizer.setStyle(OUTPUT_ICON_STYLE);
                zoom = plan.renderScale();
                hovered = null;
                for (ProjectMapOutputPlan.Cell cell : plan.pages()) {
                    pages.add(renderOutputPage(cell));
                }
            } finally {
                canvas = liveCanvas;
                outputPalette = null;
                iconImages.clear();
                iconImages.putAll(liveIcons);
                iconRasterizer.setStyle(liveIconStyle);
                zoom = liveZoom;
                offsetX = liveOffsetX;
                offsetY = liveOffsetY;
                hovered = liveHovered;
                outputPaint = false;
                try {
                    paint();
                } finally {
                    painting = false;
                }
                requestLayout();
            }
            return new ProjectMapOutput.Rendered(List.copyOf(pages), plan.pointsPerPixel(), plan);
        }

        private static int outputTileSize() {
            double scale = 1;
            for (javafx.stage.Screen screen : javafx.stage.Screen.getScreens()) {
                scale = Math.max(scale, Math.max(screen.getOutputScaleX(), screen.getOutputScaleY()));
            }
            return Math.max(256, Math.min(OUTPUT_TILE, (int) (OUTPUT_TEXTURE_LIMIT / Math.ceil(scale))));
        }

        /** Paints one page's world rectangle at the current (output) zoom, a bounded tile at a time. */
        private Image renderOutputPage(ProjectMapOutputPlan.Cell cell) {
            int pageWidth = Math.max(1, (int) Math.ceil(cell.width() * zoom));
            int pageHeight = Math.max(1, (int) Math.ceil(cell.height() * zoom));
            WritableImage page = new WritableImage(pageWidth, pageHeight);
            SnapshotParameters parameters = new SnapshotParameters();
            int step = outputTileSize();
            for (int tileY = 0; tileY < pageHeight; tileY += step) {
                int tileHeight = Math.min(step, pageHeight - tileY);
                for (int tileX = 0; tileX < pageWidth; tileX += step) {
                    int tileWidth = Math.min(step, pageWidth - tileX);
                    canvas.setWidth(tileWidth);
                    canvas.setHeight(tileHeight);
                    offsetX = -cell.x() * zoom - tileX;
                    offsetY = -cell.y() * zoom - tileY;
                    paint();
                    if (tileWidth == pageWidth && tileHeight == pageHeight) {
                        canvas.snapshot(parameters, page);
                    } else {
                        WritableImage tile = canvas.snapshot(parameters, null);
                        page.getPixelWriter()
                                .setPixels(tileX, tileY, tileWidth, tileHeight, tile.getPixelReader(), 0, 0);
                    }
                }
            }
            return page;
        }

        /** Primer Light, whatever the live theme: ink on white is what a printer and a PDF reader expect. */
        private Map<Rectangle, Color> outputPalette() {
            Map<Rectangle, Color> palette = new java.util.IdentityHashMap<>();
            palette.put(bgProbe, Color.WHITE);
            palette.put(surfaceProbe, Color.web("#f6f8fa"));
            palette.put(borderProbe, Color.web("#d0d7de"));
            palette.put(textProbe, Color.web("#1f2328"));
            palette.put(mutedProbe, Color.web("#59636e"));
            palette.put(accentProbe, Color.web("#0969da"));
            palette.put(onAccentProbe, Color.WHITE);
            palette.put(warningProbe, Color.web("#9a6700"));
            palette.put(successProbe, Color.web("#1a7f37"));
            palette.put(folderProbe, Color.web("#0969da"));
            palette.put(fileProbe, Color.web("#59636e"));
            palette.put(oliveProbe, Color.web("#6e7b25"));
            palette.put(violetProbe, Color.web("#8250df"));
            return palette;
        }

        String zoomPercent() {
            return Math.round(zoom * 100) + "%";
        }

        void zoomBy(double factor) {
            setZoom(zoom * factor, getWidth() / 2.0, getHeight() / 2.0);
        }

        /** Fit on request (the Fit button, Shortcut+0): everything, down to the smallest zoom there is. */
        void fitContent() {
            flushPendingRepaint();
            fit(MIN_ZOOM, 1.15);
        }

        /** Fit the map applies by itself; it never shrinks rows below a readable size to do so. */
        private void autoFit() {
            fit(MIN_AUTO_FIT_ZOOM, 1.0);
        }

        /**
         * Scales and centres the whole map inside the view. When the zoom floor stops everything from
         * fitting, the view is anchored on the selected path instead — the selected row, the columns that
         * lead to it and its column header — so both ends of a deep path are not cut off around a centre
         * nobody is looking at.
         */
        private void fit(double floor, double ceiling) {
            if (columnBoxes.isEmpty() || getWidth() <= 0 || getHeight() <= 0) {
                return;
            }
            double margin = Math.min(28, Math.min(getWidth(), getHeight()) * 0.05);
            double left = margin;
            double top = margin;
            double availableWidth = Math.max(1, getWidth() - margin * 2);
            double availableHeight = Math.max(1, getHeight() - margin - Math.max(margin, reservedBottom));
            // Header bands collapse below a zoom threshold, so the size of the content depends on the zoom
            // being chosen: settle in a few passes.
            for (int pass = 0; pass < 3; pass++) {
                double fitted = zoom
                        * Math.min(
                                availableWidth / Math.max(1, contentMaxX() - contentMinX()),
                                availableHeight / Math.max(1, contentMaxY() - contentMinY()));
                double next = Math.max(floor, Math.min(ceiling, fitted));
                if (Math.abs(next - zoom) < 0.0005) {
                    break;
                }
                zoom = next;
                repaint();
            }
            double contentWidth = contentMaxX() - contentMinX();
            double contentHeight = contentMaxY() - contentMinY();
            double[] pan = {
                left + (availableWidth - contentWidth) / 2 - contentMinX(),
                top + (availableHeight - contentHeight) / 2 - contentMinY()
            };
            if (contentWidth > availableWidth + 0.5 || contentHeight > availableHeight + 0.5) {
                panToSelectedPath(pan);
            }
            offsetX += pan[0];
            offsetY += pan[1];
            onZoomChanged.run();
            repaint();
        }

        /** Shows the selected row, then as much of the path to it as the view holds, nearest columns first. */
        private void panToSelectedPath(double[] pan) {
            NodeBox selectedBox = null;
            for (NodeBox box : boxes) { // root first, so each nearer ancestor overrides the ones before it
                if (isOnSelectedPath(box.entry().path())) {
                    include(pan, box.x(), box.y(), box.x() + box.width(), box.y() + box.height());
                    selectedBox = box;
                }
            }
            if (selectedBox == null || !selectedBox.entry().path().equals(selected)) {
                return;
            }
            if (selectedBox.entry().depth() == 0) {
                // Nothing chosen yet: show where the project starts, as the first view does.
                panToColumnStart(
                        pan, new ProjectMapModel.ColumnId(1, selectedBox.entry().path()));
                return;
            }
            ColumnBox column = columnBox(columnId(selectedBox.entry()));
            if (column == null) {
                return;
            }
            double bottom = selectedBox.y() + selectedBox.height();
            double right = selectedBox.x() + selectedBox.width();
            if (fitsView(column.x(), column.y(), Math.max(right, column.x() + column.width()), bottom)) {
                include(pan, column.x(), column.y(), Math.max(right, column.x() + column.width()), bottom);
            } else if (isVerticalFlow() && fitsView(selectedBox.x(), column.y(), right, bottom)) {
                include(pan, selectedBox.x(), column.y(), right, bottom);
            }
        }

        /**
         * The first view of a project, and Reset: 100%, the root column and the start of its first column
         * in view. Everything is centred instead when the whole map fits. {@code mayShrink} lets the first
         * view step down (never below the auto-fit floor) when that is what shows both columns.
         */
        private void showStart(boolean mayShrink) {
            if (columnBoxes.isEmpty() || getWidth() <= 0 || getHeight() <= 0) {
                return;
            }
            if (zoom != 1.0 || offsetX != 0 || offsetY != 0) {
                zoom = 1.0;
                offsetX = 0;
                offsetY = 0;
                repaint();
            }
            if (fitsView(contentMinX(), contentMinY(), contentMaxX(), contentMaxY())) {
                fit(1.0, 1.0);
                return;
            }
            ColumnBox rootColumn = columnBoxes.getFirst();
            ColumnBox first = null;
            for (ColumnBox candidate : columnBoxes) {
                if (candidate.column().depth() == rootColumn.column().depth() + 1) {
                    first = candidate;
                    break;
                }
            }
            if (mayShrink && first != null) {
                double span = isVerticalFlow()
                        ? Math.max(rootColumn.y() + rootColumn.height(), first.y() + first.height())
                                - Math.min(rootColumn.y(), first.y())
                        : Math.max(rootColumn.x() + rootColumn.width(), first.x() + first.width())
                                - Math.min(rootColumn.x(), first.x());
                double available = isVerticalFlow() ? viewBottom() - viewTop() : viewRight() - viewLeft();
                if (span > available) {
                    zoom = Math.max(MIN_AUTO_FIT_ZOOM, available / span);
                    repaint();
                    rootColumn = columnBoxes.getFirst();
                    first = columnBox(first.column().id());
                }
            }
            double[] pan = new double[2];
            include(
                    pan,
                    rootColumn.x(),
                    rootColumn.y(),
                    rootColumn.x() + rootColumn.width(),
                    rootColumn.y() + rootColumn.height());
            if (first != null) {
                panToColumnStart(pan, first.column().id());
            }
            offsetX += pan[0];
            offsetY += pan[1];
            onZoomChanged.run();
            repaint();
        }

        private double contentMinX() {
            double value = Double.POSITIVE_INFINITY;
            for (ColumnBox box : columnBoxes) {
                value = Math.min(value, box.x());
            }
            return value;
        }

        private double contentMinY() {
            double value = Double.POSITIVE_INFINITY;
            for (ColumnBox box : columnBoxes) {
                value = Math.min(value, box.y());
            }
            return value;
        }

        private double contentMaxX() {
            double value = Double.NEGATIVE_INFINITY;
            for (ColumnBox box : columnBoxes) {
                value = Math.max(value, box.x() + box.width());
            }
            return value;
        }

        private double contentMaxY() {
            double value = Double.NEGATIVE_INFINITY;
            for (ColumnBox box : columnBoxes) {
                value = Math.max(value, box.y() + box.height());
            }
            return value;
        }

        void centerSelection() {
            flushPendingRepaint();
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
            showStart(false); // offset 0 alone leaves a reversed flow's columns off-screen
        }

        @Override
        protected void layoutChildren() {
            if (outputPalette != null) {
                return; // rasterizing an icon lays the scene out mid-output; renderOutput asks again afterwards
            }
            liveCanvas.setWidth(Math.max(1, getWidth()));
            liveCanvas.setHeight(Math.max(1, getHeight()));
            repaint();
            fitIfPending();
        }

        private void fitIfPending() {
            if (initialFitPending && getWidth() > 1 && getHeight() > 1 && !columnBoxes.isEmpty()) {
                initialFitPending = false;
                showStart(true);
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
                filter.setOnAction(event -> leaveColumnFilter(id));

                CheckBox showHidden = new CheckBox(tr("project.map.column.showHidden"));
                showHidden.getStyleClass().add("project-map-column-hidden");
                showHidden.setSelected(showHiddenDefault);
                showHidden.setTooltip(new Tooltip(tr("project.map.column.showHiddenHelp")));
                showHidden.setAccessibleText(tr("project.map.column.showHidden"));
                showHidden.selectedProperty().addListener((obs, old, selected) -> {
                    if (syncingHidden) {
                        return;
                    }
                    columnShowHidden.put(id, selected);
                    columnHiddenChanged(); // hidden rows are not loaded while off, so turning it on lists again
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
            Set<Path> loadedPaths = new HashSet<>();
            for (ProjectMapModel.Entry entry : entries) {
                Path path = entry.path();
                loadedPaths.add(path);
                if (entry.isPlaceholder()) {
                    continue;
                }
                if (ProjectMapModel.matches(
                        entry,
                        filters,
                        openPaths.contains(path),
                        modifiedPaths.contains(path),
                        gitState.containsKey(path) || gitDirectories.contains(path),
                        bookmarkedPaths.contains(path),
                        notedPaths.contains(path))) {
                    direct.add(path);
                }
            }
            addUnloadedMatches(direct, loadedPaths);
            emphasized = ProjectMapModel.emphasized(entries, direct, root);
        }

        /**
         * Open, Modified, Bookmarks and Personal Notes matches that sit inside a collapsed folder: they are
         * not rows, but they must light the folders that hold them, as Git changes already do. The candidates
         * come from the open tabs and the marker stores' keys, so this reads nothing from disk.
         */
        private void addUnloadedMatches(Set<Path> direct, Set<Path> loadedPaths) {
            boolean tabs = filters.open() || filters.modified();
            boolean markers = filters.bookmarked() || filters.personalNotes();
            if (root == null || !tabs && !markers) {
                return;
            }
            Set<Path> candidates = new HashSet<>();
            try {
                candidates.addAll(openFiles.get()); // an open buffer may hold markers not stored yet
                if (markers) {
                    candidates.addAll(markerCandidates.get());
                }
            } catch (RuntimeException ignored) {
                return;
            }
            for (Path candidate : candidates) {
                Path path;
                try {
                    path = ProjectMapModel.normalize(candidate);
                    if (path == null
                            || !com.editora.config.PathKeys.sameFileSystem(path, root)
                            || !path.startsWith(root)
                            || loadedPaths.contains(path)) {
                        continue;
                    }
                } catch (RuntimeException ignored) {
                    continue;
                }
                if (ProjectMapModel.matches(
                        new ProjectMapModel.Entry(path, path.getParent(), 0, false),
                        filters,
                        filters.open() && safeTest(openState, path),
                        filters.modified() && safeTest(modifiedState, path),
                        false,
                        filters.bookmarked() && safeTest(bookmarkState, path),
                        filters.personalNotes() && safeTest(noteState, path))) {
                    direct.add(path);
                }
            }
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
            lastPaintedConnectorCount = 0;
            if (entries.isEmpty()) {
                columnControls.values().forEach(controls -> {
                    controls.filter().setVisible(false);
                    controls.showHidden().setVisible(false);
                    controls.pin().setVisible(false);
                    controls.close().setVisible(false);
                });
                g.setFill(color(mutedProbe, Color.web("#8b949e")));
                g.setFont(Font.font(13));
                g.fillText(tr(loadPending ? "project.map.loading" : "project.map.empty"), 18, 28);
                reportNoMatches(false);
                return;
            }

            List<ProjectMapModel.Column> columns =
                    ProjectMapModel.columnsById(entries, columnQueries, columnShowHidden);
            // Column widths allow for the checkbox label, so it is measured before they are computed (it
            // changes once, when the controls are first styled).
            for (ColumnControls controls : columnControls.values()) {
                if (controls.showHidden().getContentDisplay() != ContentDisplay.GRAPHIC_ONLY) {
                    showHiddenLabelWidth(controls.showHidden());
                    break;
                }
            }
            Map<ProjectMapModel.ColumnId, Double> nodeWidths = new HashMap<>();
            for (ProjectMapModel.Column column : columns) {
                nodeWidths.put(column.id(), nodeWidthFor(column));
            }
            Map<Path, NodeBox> byPath = new HashMap<>();
            Map<ProjectMapModel.ColumnId, ColumnBox> columnsById = new HashMap<>();
            Map<Integer, Double> nextCrossEdge = new HashMap<>();
            // Along the flow every depth gets a band of its own, starting beyond the widest card of the
            // depth before it; a card that only started beyond its own parent could run through a wider
            // sibling of that parent. Manual offsets are left out of the band (flowShift), so dragging
            // one column does not move the other branches.
            Map<Integer, Double> flowBand = new HashMap<>();
            Map<ProjectMapModel.ColumnId, Double> flowShift = new HashMap<>();
            boolean verticalFlow = isVerticalFlow();
            double direction = isReverseFlow() ? -1 : 1;
            for (ProjectMapModel.Column column : columns) {
                int depth = column.depth();
                ProjectMapModel.ColumnId id = column.id();
                ColumnLayout layout = columnLayouts.computeIfAbsent(id, ignored -> new ColumnLayout());
                double nodeWidth = nodeWidths.get(id);
                double depthStep = verticalFlow
                        ? COLUMN_TOP_INSET + COLUMN_HEADER_HEIGHT + NODE_HEIGHT + COLUMN_BOTTOM_PADDING + COLUMN_GAP
                        : 0;
                int rowCount = column.entries().size();
                // A column whose filter matches nothing keeps one row of space for its "No matches" line.
                int rowSlots = Math.max(1, rowCount);
                double rowsHeight = rowSlots * NODE_HEIGHT + (rowSlots - 1) * ROW_GAP;
                double rowsWidth = rowSlots * nodeWidth + (rowSlots - 1) * ROW_GAP;
                double cardWidth = verticalFlow ? rowsWidth + 20 : nodeWidth + 20;
                double headerHeight = columnHeaderHeight(depth, cardWidth);
                double cardHeight = COLUMN_TOP_INSET
                        + headerHeight
                        + (verticalFlow ? NODE_HEIGHT : rowsHeight)
                        + COLUMN_BOTTOM_PADDING;
                double baseWorldX = WORLD_PADDING;
                double baseWorldY = WORLD_PADDING + (verticalFlow ? direction * depth * depthStep : 0);
                NodeBox parent = byPath.get(column.parent());
                ColumnBox parentColumn = parent == null ? null : columnsById.get(columnId(parent.entry()));
                double inheritedShift = 0;
                if (parent != null && parentColumn != null) {
                    // Along the cross-axis a card is centred on the row that opened it, or hangs from it
                    // when it is long (MAX_COLUMN_LEAD). Along the flow axis it starts beyond its parent's
                    // card and beyond every other card of the parent's depth.
                    double parentCenterX = (parent.x() + parent.width() / 2 - offsetX) / zoom;
                    double parentCenterY = (parent.y() + parent.height() / 2 - offsetY) / zoom;
                    double horizontalCardGap = COLUMN_GAP - 20;
                    inheritedShift =
                            flowShift.getOrDefault(parentColumn.column().id(), 0.0);
                    Double band = flowBand.get(depth - 1);
                    double leadY = Math.min(cardHeight / 2, MAX_COLUMN_LEAD);
                    double leadX = Math.min(cardWidth / 2, nodeWidth * 1.5 + 10);
                    switch (flowDirection) {
                        case LEFT_TO_RIGHT -> {
                            double edge = (parentColumn.x() + parentColumn.width() - offsetX) / zoom;
                            edge = band == null ? edge : Math.max(edge, band + inheritedShift);
                            baseWorldX = edge + horizontalCardGap + 10;
                            baseWorldY = parentCenterY + COLUMN_TOP_INSET - leadY;
                        }
                        case RIGHT_TO_LEFT -> {
                            double edge = (parentColumn.x() - offsetX) / zoom;
                            edge = band == null ? edge : Math.min(edge, band + inheritedShift);
                            baseWorldX = edge - horizontalCardGap + 10 - cardWidth;
                            baseWorldY = parentCenterY + COLUMN_TOP_INSET - leadY;
                        }
                        case TOP_TO_BOTTOM -> {
                            double edge = (parentColumn.y() + parentColumn.height() - offsetY) / zoom;
                            edge = band == null ? edge : Math.max(edge, band + inheritedShift);
                            baseWorldX = parentCenterX + 10 - leadX;
                            baseWorldY = edge + COLUMN_GAP + COLUMN_TOP_INSET;
                        }
                        case BOTTOM_TO_TOP -> {
                            double edge = (parentColumn.y() - offsetY) / zoom;
                            edge = band == null ? edge : Math.min(edge, band + inheritedShift);
                            baseWorldX = parentCenterX + 10 - leadX;
                            baseWorldY = edge - COLUMN_GAP + COLUMN_TOP_INSET - cardHeight;
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
                double shift = inheritedShift + (verticalFlow ? columnWorldY - baseWorldY : columnWorldX - baseWorldX);
                flowShift.put(id, shift);
                double farEdge =
                        switch (flowDirection) {
                            case LEFT_TO_RIGHT -> columnWorldX - 10 + cardWidth;
                            case RIGHT_TO_LEFT -> columnWorldX - 10;
                            case TOP_TO_BOTTOM -> columnWorldY - COLUMN_TOP_INSET + cardHeight;
                            case BOTTOM_TO_TOP -> columnWorldY - COLUMN_TOP_INSET;
                        };
                flowBand.merge(depth, farEdge - shift, direction > 0 ? Math::max : Math::min);
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
            // Rows were added column by column, so the two lists are walked together: a column's rows
            // share one parent row and, in a vertical flow, one card edge to route to.
            int next = 0;
            for (ColumnBox childColumn : columnBoxes) {
                int rows = childColumn.column().entries().size();
                NodeBox parent =
                        rows == 0 ? null : byPath.get(childColumn.column().parent());
                ColumnBox parentColumn = parent == null ? null : columnsById.get(columnId(parent.entry()));
                for (int row = 0; row < rows; row++) {
                    NodeBox child = boxes.get(next++);
                    if (parent == null || parentColumn == null || !connectorInViewport(child, width, height)) {
                        continue;
                    }
                    boolean selectedPath = isOnSelectedPath(child.entry().path());
                    g.setGlobalAlpha(connectorOpacity(
                            selectedPath, prominence(child.entry().path())));
                    g.setLineWidth(Math.max(1, (selectedPath ? 2.25 : 1.15) * zoom));
                    drawConnector(g, parent, parentColumn, child, childColumn);
                    lastPaintedConnectorCount++;
                }
            }
            rowFont = null;
            for (NodeBox box : boxes) {
                if (inViewport(box, width, height)) {
                    drawNode(g, box);
                }
            }
            if (!outputPaint) {
                drawOverview(g, width, height);
            }
            layoutColumnControls();
            g.setGlobalAlpha(1);
            reportNoMatches(filters != null && filters.active() && emphasized.isEmpty());
        }

        private void reportNoMatches(boolean none) {
            if (none != noMatches) {
                noMatches = none;
                onNoMatchesChanged.accept(none);
            }
        }

        private double nodeWidthFor(ProjectMapModel.Column column) {
            double required = measuredLabelWidth(columnTitle(column)) + 52;
            for (ProjectMapModel.Entry entry : entries) {
                if (columnId(entry).equals(column.id())) {
                    required = Math.max(required, measuredLabelWidth(entry.name()) + rowExtraWidth(entry));
                }
            }
            if (column.depth() > 0 && showHiddenLabelWidth > 0) {
                // At 100% the header row must hold the filter, the labelled checkbox and the lock, whatever
                // the checkbox label measures in the current language.
                required = Math.max(
                        required, MIN_COLUMN_FILTER_WIDTH + showHiddenLabelWidth + 25 + COLUMN_CONTROL_GAP * 2 - 2);
            }
            return Math.max(MIN_NODE_WIDTH, Math.ceil(required));
        }

        /** Width a row needs besides its label: icon and padding, then the marks at its trailing end. */
        private double rowExtraWidth(ProjectMapModel.Entry entry) {
            if (!entry.directory()) {
                // File rows reserve a fixed tail: Git letter and unsaved dot, both badges and Preview.
                return 31 + 20 + MARKER_WIDTH * 2 + PREVIEW_ZONE;
            }
            double markers = (bookmarkedPaths.contains(entry.path()) ? MARKER_WIDTH : 0)
                    + (notedPaths.contains(entry.path()) ? MARKER_WIDTH : 0);
            return 31 + 2 + CHEVRON_ZONE + markers;
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

        private void drawConnector(
                GraphicsContext g, NodeBox parent, ColumnBox parentColumn, NodeBox child, ColumnBox childColumn) {
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
                // A card's header sits between its top edge and its rows: stop at the edge, above the row,
                // instead of drawing every connector through the title and the column controls.
                x1 = parent.x() + parent.width() / 2;
                y1 = parent.y() + parent.height();
                x2 = child.x() + child.width() / 2;
                y2 = childColumn.y();
            } else {
                // Upwards it is the parent's own header that is in the way: leave from its card's edge.
                x1 = parent.x() + parent.width() / 2;
                y1 = parentColumn.y();
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

        /**
         * Header height in world units for a card of {@code cardWidth}. The band for the filter, the
         * hidden-files checkbox and the lock exists only while those controls are shown; without them the
         * rows move up under the title instead of leaving an empty strip.
         */
        private double columnHeaderHeight(int depth, double cardWidth) {
            return depth > 0 && detailControlsFit(cardWidth * zoom) ? COLUMN_HEADER_HEIGHT : COMPACT_HEADER_HEIGHT;
        }

        /** Whether a card this wide on screen has room for its detail controls at the current zoom. */
        private boolean detailControlsFit(double cardScreenWidth) {
            if (outputPaint) {
                return false;
            }
            double availableWidth = cardScreenWidth - 18 * zoom;
            double availableHeight = (COLUMN_HEADER_HEIGHT - 25) * zoom;
            double minimumWidth = MIN_COLUMN_FILTER_WIDTH
                    + CHECK_ONLY_WIDTH
                    + Math.max(MIN_COLUMN_PIN_WIDTH, 25 * zoom)
                    + COLUMN_CONTROL_GAP * 2;
            return availableWidth >= minimumWidth && availableHeight >= Math.max(MIN_COLUMN_CONTROL_HEIGHT, 24 * zoom);
        }

        private boolean closeFits(ColumnBox box) {
            return box.column().depth() > 0 && zoom >= 0.65 && box.width() >= closeSize() + 42;
        }

        private double closeSize() {
            return Math.max(18, 20 * zoom);
        }

        private void layoutColumnControls() {
            Set<ProjectMapModel.ColumnId> visibleIds = new HashSet<>();
            for (ColumnBox box : columnBoxes) {
                ProjectMapModel.ColumnId id = box.column().id();
                visibleIds.add(id);
                ColumnControls controls = columnControls.get(id);
                if (controls == null) {
                    continue;
                }
                double closeSize = closeSize();
                double closeX = box.x() + box.width() - closeSize - 5 * zoom;
                double closeY = box.y() + 2 * zoom;
                boolean closeFits = closeFits(box) && !underOverview(closeX, closeY, closeSize, closeSize);
                controls.close().setVisible(closeFits);
                if (closeFits) {
                    controls.close().resize(closeSize, closeSize);
                    controls.close().relocate(closeX, closeY);
                }
                double controlY = box.y() + 25 * zoom;
                double controlHeight = Math.max(MIN_COLUMN_CONTROL_HEIGHT, 24 * zoom);
                double pinWidth = Math.max(MIN_COLUMN_PIN_WIDTH, 25 * zoom);
                double left = box.x() + 10 * zoom;
                double right = box.x() + box.width() - 8 * zoom;
                double availableWidth = right - left;
                // Native controls are children above the canvas: one that reached into the overview would
                // paint over it and take its clicks.
                boolean controlsFit =
                        detailControlsFit(box.width()) && !underOverview(left, controlY, availableWidth, controlHeight);
                setColumnDetailControlsVisible(controls, controlsFit);
                if (!controlsFit) {
                    continue;
                }
                // The checkbox keeps its whole label while there is room and falls back to the bare box
                // (still named by its tooltip and accessible text) rather than to a clipped "Hid…".
                double labelWidth = showHiddenLabelWidth(controls.showHidden());
                boolean labelFits =
                        availableWidth >= MIN_COLUMN_FILTER_WIDTH + labelWidth + pinWidth + COLUMN_CONTROL_GAP * 2;
                double hiddenWidth = labelFits ? labelWidth : CHECK_ONLY_WIDTH;
                ContentDisplay display = labelFits ? ContentDisplay.LEFT : ContentDisplay.GRAPHIC_ONLY;
                if (controls.showHidden().getContentDisplay() != display) {
                    controls.showHidden().setContentDisplay(display);
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
                // A prompt is not elided: a narrow field takes the short one instead of a cut-off long one.
                String prompt = controls.filter().getWidth() < 100 ? columnFilterShortPrompt : columnFilterPrompt;
                if (!prompt.equals(controls.filter().getPromptText())) {
                    controls.filter().setPromptText(prompt);
                }
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

        /** The checkbox's width with its whole label, remembered while the label itself is hidden. */
        private double showHiddenLabelWidth(CheckBox showHidden) {
            if (showHidden.getContentDisplay() != ContentDisplay.GRAPHIC_ONLY) {
                double measured = Math.ceil(showHidden.prefWidth(-1));
                if (measured > 0) {
                    showHiddenLabelWidth = measured;
                }
            }
            return showHiddenLabelWidth > 0 ? showHiddenLabelWidth : 60;
        }

        private boolean underOverview(double x, double y, double width, double height) {
            OverviewBox overview = overviewBox;
            return overview != null
                    && x < overview.x() + overview.width()
                    && x + width > overview.x()
                    && y < overview.y() + overview.height()
                    && y + height > overview.y();
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
            if (outputPalette != null || columnBoxes.isEmpty()) {
                return;
            }
            double minX = contentMinX();
            double minY = contentMinY();
            double maxX = contentMaxX();
            double maxY = contentMaxY();
            if (minX >= 0 && minY >= 0 && maxX <= viewportWidth && maxY <= viewportHeight) {
                return; // everything is on screen: nothing to navigate to
            }
            double overviewWidth = Math.min(150, Math.max(90, viewportWidth * 0.18));
            double overviewHeight = 86;
            double x = viewportWidth - overviewWidth - 10;
            double y = viewportHeight - overviewHeight - 10;
            if (x < reservedBarWidth + 6) {
                return; // too narrow to sit beside the zoom bar, and anywhere else it would cover rows
            }
            double contentWidth = Math.max(1, maxX - minX);
            double contentHeight = Math.max(1, maxY - minY);
            double scale = Math.min((overviewWidth - 10) / contentWidth, (overviewHeight - 10) / contentHeight);
            overviewBox = new OverviewBox(x, y, overviewWidth, overviewHeight, minX, minY, scale);
            g.setGlobalAlpha(0.94);
            g.setFill(color(surfaceProbe, Color.web("#161d27")));
            g.fillRoundRect(x, y, overviewWidth, overviewHeight, 8, 8);
            g.setStroke(rowBorder());
            g.setLineWidth(1);
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
            // The viewport rectangle is clipped to the frame: panned far from the content it used to be
            // drawn outside the overview altogether.
            double frameLeft = x + 3;
            double frameTop = y + 3;
            double frameRight = x + overviewWidth - 3;
            double frameBottom = y + overviewHeight - 3;
            double left = clampTo(x + 5 - minX * scale, frameLeft, frameRight);
            double top = clampTo(y + 5 - minY * scale, frameTop, frameBottom);
            double right = clampTo(x + 5 + (viewportWidth - minX) * scale, frameLeft, frameRight);
            double bottom = clampTo(y + 5 + (viewportHeight - minY) * scale, frameTop, frameBottom);
            g.setStroke(color(accentProbe, Color.web("#58a6ff")));
            g.setLineWidth(1.5);
            g.strokeRect(left, top, Math.max(2, right - left), Math.max(2, bottom - top));
            g.setGlobalAlpha(1);
        }

        private static double clampTo(double value, double minimum, double maximum) {
            return Math.max(minimum, Math.min(maximum, value));
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

                // The header fonts stop shrinking at a legible size while the card keeps scaling, so the
                // title is measured against the room it really has: it is elided, and the count gives way
                // first, rather than the two running into each other.
                double titleSize = Math.max(9, 11 * zoom);
                double countSize = Math.max(8, 9 * zoom);
                double baseline = y + 12.5 * zoom + titleSize * 0.36;
                String title = columnTitle(column);
                String count = column.countLabel(); // "shown/total" when a filter or a row limit hides some
                double titleLeft = x + 10 * zoom;
                double right = x + w - (closeFits(box) ? closeSize() + 9 * zoom : 8 * zoom);
                double countWidth = measuredLabelWidth(count) * countSize / 12;
                double titleWidth = measuredLabelWidth(title) * titleSize / 12;
                double titleRoom = right - countWidth - 8 - titleLeft;
                boolean showCount = titleRoom >= Math.min(titleWidth, 30);
                if (!showCount) {
                    titleRoom = right - titleLeft;
                }
                g.setGlobalAlpha(0.88);
                g.setFill(color(textProbe, Color.web("#d8dee9")));
                g.setFont(Font.font("System", FontWeight.SEMI_BOLD, titleSize));
                g.fillText(titleWidth <= titleRoom ? title : elide(title, titleSize, titleRoom), titleLeft, baseline);
                g.setFill(color(mutedProbe, Color.web("#8b949e")));
                if (showCount) {
                    g.setFont(Font.font(countSize));
                    g.setTextAlign(TextAlignment.RIGHT);
                    g.fillText(count, right, baseline);
                    g.setTextAlign(TextAlignment.LEFT);
                }
                if (column.entries().isEmpty()) {
                    // Its own filter (or the hidden-files checkbox) removed every row.
                    g.setFont(Font.font(Math.max(9, 11 * zoom)));
                    double header = columnHeaderHeight(column.depth(), w / zoom);
                    g.fillText(
                            columnNoMatches,
                            titleLeft,
                            y + (COLUMN_TOP_INSET + header) * zoom + 20.5 * zoom,
                            Math.max(1, w - 20 * zoom));
                }
            }
            g.setGlobalAlpha(1);
        }

        /** {@code text} cut to fit {@code room} at {@code fontSize}, ending in an ellipsis. */
        private String elide(String text, double fontSize, double room) {
            if (elidedTitles.size() > 256) {
                elidedTitles.clear(); // a zoom gesture asks for a new width on every step
            }
            String key = text + '\u0000' + (int) room + '\u0000' + (int) (fontSize * 8);
            return elidedTitles.computeIfAbsent(key, ignored -> {
                int low = 0;
                int high = text.length();
                while (low < high) { // the longest prefix that still fits with its ellipsis
                    int middle = (low + high + 1) / 2;
                    textMeasurer.setText(text.substring(0, middle) + "…");
                    if (textMeasurer.getLayoutBounds().getWidth() * fontSize / 12 <= room) {
                        low = middle;
                    } else {
                        high = middle - 1;
                    }
                }
                return low == 0 ? "" : text.substring(0, low) + "…";
            });
        }

        private void drawNode(GraphicsContext g, NodeBox box) {
            ProjectMapModel.Entry entry = box.entry();
            if (entry.isPlaceholder()) {
                drawPlaceholderRow(g, box);
                return;
            }
            boolean isSelected = entry.path().equals(selected);
            boolean isHovered = entry.path().equals(hovered) && !outputPaint;
            boolean selectedPath = isOnSelectedPath(entry.path());
            // The accent fill says "keys act here": it is kept for the selection while the surface has
            // keyboard focus. Without focus (and in printed output) the selection is a tinted row.
            boolean focusedSelection = isSelected && isFocused() && !outputPaint;
            double alpha = prominence(entry.path()) || isSelected ? 1.0 : DIMMED_ALPHA;
            g.setGlobalAlpha(alpha);
            Color accent = color(accentProbe, Color.web("#388bfd"));
            Color surface = color(surfaceProbe, Color.web("#202938"));
            Color fill = focusedSelection
                    ? accent
                    : isSelected ? color(accentSubtleProbe, mix(surface, accent, 0.22)) : surface;
            if (isHovered && !isSelected) {
                fill = mix(fill, accent, 0.16);
            }
            g.setFill(fill);
            g.fillRoundRect(box.x(), box.y(), box.width(), box.height(), 8 * zoom, 8 * zoom);
            g.setStroke(isSelected || isHovered || selectedPath ? accent : rowBorder());
            g.setLineWidth((isSelected ? 1.8 : selectedPath ? 1.35 : 1.0) * zoom);
            g.strokeRoundRect(box.x(), box.y(), box.width(), box.height(), 8 * zoom, 8 * zoom);
            if (focusedSelection) {
                // A ring outside the row, separated from it by a sliver of the card.
                g.setStroke(color(focusRingProbe, accent));
                g.setLineWidth(2);
                g.strokeRoundRect(
                        box.x() - 3, box.y() - 3, box.width() + 6, box.height() + 6, 8 * zoom + 6, 8 * zoom + 6);
            }
            drawOpenMarker(g, entry, box, focusedSelection);

            double iconX = box.x() + 7 * zoom;
            double iconY = box.y() + 6 * zoom;
            drawIcon(g, entry, iconX, iconY, focusedSelection);
            g.setFill(
                    focusedSelection
                            ? onAccent()
                            : openPaths.contains(entry.path())
                                    ? color(accentTextProbe, accent)
                                    : color(textProbe, Color.web("#d8dee9")));
            g.setFont(rowFont(isSelected));
            g.fillText(entry.name(), box.x() + 31 * zoom, box.y() + 20.5 * zoom);

            drawStatusMarks(g, entry, box, focusedSelection);
            drawFileMarkers(g, entry, box, focusedSelection);
            if (entry.directory()) {
                drawChevron(g, entry, box, focusedSelection);
            } else {
                drawPreviewAffordance(g, box, focusedSelection, isHovered);
            }
            g.setGlobalAlpha(1);
        }

        /**
         * A row that is not a file: "+N more…" at the end of a truncated column (activating it loads the next
         * chunk), or the single "Empty folder" / "Cannot read this folder" row of a column with nothing to
         * list. Drawn as text on the column card, outlined when selected or hovered, never as a node.
         */
        private void drawPlaceholderRow(GraphicsContext g, NodeBox box) {
            ProjectMapModel.Entry entry = box.entry();
            boolean more = entry.isMore();
            g.setGlobalAlpha(1);
            if (entry.path().equals(selected) || more && entry.path().equals(hovered)) {
                g.setStroke(color(accentProbe, Color.web("#388bfd")));
                g.setLineWidth(1.5 * zoom);
                g.strokeRoundRect(box.x(), box.y(), box.width(), box.height(), 8 * zoom, 8 * zoom);
            }
            g.setFill(more ? color(textProbe, Color.web("#d8dee9")) : color(mutedProbe, Color.web("#8b949e")));
            g.setFont(Font.font("System", more ? FontWeight.SEMI_BOLD : FontWeight.NORMAL, 12 * zoom));
            g.fillText(entry.name(), box.x() + 10 * zoom, box.y() + 20.5 * zoom);
        }

        /** Row label fonts for this paint; every row shares one of the two. */
        private Font rowFont(boolean bold) {
            if (rowFont == null) {
                rowFont = Font.font("System", FontWeight.NORMAL, 12 * zoom);
                rowFontBold = Font.font("System", FontWeight.SEMI_BOLD, 12 * zoom);
            }
            return bold ? rowFontBold : rowFont;
        }

        /** The border that separates a row from its card (see {@link #rowBorderColor}). */
        private Color rowBorder() {
            return rowBorderColor(color(borderProbe, Color.web("#3a4554")), color(mutedProbe, Color.web("#8b949e")));
        }

        /**
         * The strip at the trailing end of a folder row that holds its chevron. A click inside it toggles
         * the folder; a click anywhere else on the row selects it.
         */
        private Rectangle2D chevronZone(NodeBox box) {
            double width = Math.min(box.width(), CHEVRON_ZONE * zoom);
            return new Rectangle2D(box.x() + box.width() - width, box.y(), width, box.height());
        }

        /** Whether {@code x} is over the chevron of the folder row {@code box}. */
        private boolean chevronHit(NodeBox box, double x) {
            return box.entry().directory() && x >= box.x() + box.width() - CHEVRON_ZONE * zoom;
        }

        /**
         * Every folder has a chevron that points along the flow. An expanded folder's sits in a filled
         * disc, so "open" is a shape as well as a colour.
         */
        private void drawChevron(GraphicsContext g, ProjectMapModel.Entry entry, NodeBox box, boolean onAccentFill) {
            boolean expanded = expandedSnapshot.contains(entry.path());
            boolean hot =
                    hoveredAffordance == Affordance.CHEVRON && entry.path().equals(hovered) && !outputPaint;
            double cx = box.x() + box.width() - 13 * zoom;
            double cy = box.y() + box.height() / 2;
            Color ink = onAccentFill ? onAccent() : color(hot ? textProbe : mutedProbe, Color.web("#8b949e"));
            if (expanded || hot) {
                double radius = 8 * zoom;
                g.setFill(ink);
                double previous = g.getGlobalAlpha();
                g.setGlobalAlpha(previous * (expanded ? (hot ? 0.34 : 0.22) : 0.14));
                g.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);
                g.setGlobalAlpha(previous);
            }
            double reach = 2.6 * zoom; // half the chevron's opening
            double depth = 1.5 * zoom; // half its length along the flow
            g.setStroke(ink);
            g.setLineWidth(Math.max(1, 1.5 * zoom));
            g.beginPath();
            switch (flowDirection) {
                case LEFT_TO_RIGHT -> {
                    g.moveTo(cx - depth, cy - reach);
                    g.lineTo(cx + depth, cy);
                    g.lineTo(cx - depth, cy + reach);
                }
                case RIGHT_TO_LEFT -> {
                    g.moveTo(cx + depth, cy - reach);
                    g.lineTo(cx - depth, cy);
                    g.lineTo(cx + depth, cy + reach);
                }
                case TOP_TO_BOTTOM -> {
                    g.moveTo(cx - reach, cy - depth);
                    g.lineTo(cx, cy + depth);
                    g.lineTo(cx + reach, cy - depth);
                }
                case BOTTOM_TO_TOP -> {
                    g.moveTo(cx - reach, cy + depth);
                    g.lineTo(cx, cy - depth);
                    g.lineTo(cx + reach, cy + depth);
                }
            }
            g.stroke();
        }

        /** The eye at the trailing end of a file row: it opens the floating preview, and says so on hover. */
        private void drawPreviewAffordance(GraphicsContext g, NodeBox box, boolean onAccentFill, boolean rowHovered) {
            boolean hot = rowHovered && hoveredAffordance == Affordance.PREVIEW;
            double cx = box.x() + box.width() - PREVIEW_ZONE * zoom / 2;
            double cy = box.y() + box.height() / 2;
            Color ink = onAccentFill ? onAccent() : color(hot ? textProbe : mutedProbe, Color.web("#8b949e"));
            if (hot) {
                double previous = g.getGlobalAlpha();
                g.setGlobalAlpha(previous * 0.16);
                g.setFill(ink);
                g.fillRoundRect(cx - 11 * zoom, cy - 10 * zoom, 22 * zoom, 20 * zoom, 6 * zoom, 6 * zoom);
                g.setGlobalAlpha(previous);
            }
            double halfWidth = 6.5 * zoom;
            double bulge = 7 * zoom;
            g.setStroke(ink);
            g.setLineWidth(Math.max(1, 1.25 * zoom));
            g.beginPath();
            g.moveTo(cx - halfWidth, cy);
            g.quadraticCurveTo(cx, cy - bulge, cx + halfWidth, cy);
            g.quadraticCurveTo(cx, cy + bulge, cx - halfWidth, cy);
            g.closePath();
            g.stroke();
            g.setFill(ink);
            double pupil = 1.9 * zoom;
            g.fillOval(cx - pupil, cy - pupil, pupil * 2, pupil * 2);
        }

        /** Open files get an unmistakable tab-colored rail in addition to their accent-colored label. */
        private void drawOpenMarker(GraphicsContext g, ProjectMapModel.Entry entry, NodeBox box, boolean selectedNode) {
            if (entry.directory() || !openPaths.contains(entry.path())) {
                return;
            }
            g.setFill(selectedNode ? onAccent() : color(accentProbe, Color.web("#388bfd")));
            g.fillRoundRect(
                    box.x() + 2 * zoom, box.y() + 7 * zoom, 3 * zoom, box.height() - 14 * zoom, 3 * zoom, 3 * zoom);
        }

        private void drawIcon(
                GraphicsContext g, ProjectMapModel.Entry entry, double x, double y, boolean onAccentFill) {
            Image icon = iconImage(entry, onAccentFill);
            g.drawImage(icon, x, y, ICON_SIZE * zoom, ICON_SIZE * zoom);
        }

        private Image iconImage(ProjectMapModel.Entry entry, boolean onAccentFill) {
            String kind = entry.directory() ? "folder" : FileIcons.iconKeyFor(entry.name());
            // On the accent fill the glyph takes the on-accent ink whatever its status colour would be.
            String statusClass = onAccentFill ? ON_ACCENT_ICON_CLASS : iconStatusClass(entry);
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

        /**
         * Unsaved and Git states at the trailing end of a file row, told apart by shape as well as hue: a
         * filled dot for unsaved changes (as on a dirty tab) and the Git status letter the Project tree and
         * the Commit window use.
         */
        private void drawStatusMarks(
                GraphicsContext g, ProjectMapModel.Entry entry, NodeBox box, boolean onAccentFill) {
            if (entry.directory()) {
                return;
            }
            double markerWidth = (bookmarkedPaths.contains(entry.path()) ? MARKER_WIDTH : 0)
                    + (notedPaths.contains(entry.path()) ? MARKER_WIDTH : 0);
            // Keep the Preview target in the final strip and the semantic markers immediately to its left.
            double x = box.x() + box.width() - (PREVIEW_ZONE + 3 + markerWidth) * zoom;
            GitFileStatus status = gitState.get(entry.path());
            if (status != null) {
                g.setFill(onAccentFill ? onAccent() : gitColor(status));
                g.setFont(Font.font("System", FontWeight.BOLD, 10 * zoom));
                g.setTextAlign(TextAlignment.RIGHT);
                g.fillText(status.letter(), x, box.y() + 20 * zoom);
                g.setTextAlign(TextAlignment.LEFT);
                x -= 11 * zoom;
            }
            if (modifiedPaths.contains(entry.path())) {
                g.setFill(onAccentFill ? onAccent() : color(warningProbe, Color.web("#d29922")));
                g.fillOval(x - 6 * zoom, box.y() + 13 * zoom, 6 * zoom, 6 * zoom);
            }
        }

        /** The Project tree's colour for each Git status (see {@code .project-tree .git-status-*}). */
        private Color gitColor(GitFileStatus status) {
            return switch (status) {
                case ADDED -> color(successProbe, Color.web("#3fb950"));
                case UNTRACKED -> color(oliveProbe, Color.web("#6e7b25"));
                case RENAMED -> color(violetProbe, Color.web("#8250df"));
                case DELETED -> color(mutedProbe, Color.web("#8b949e"));
                case CONFLICT -> color(dangerProbe, Color.web("#f85149"));
                case MODIFIED -> color(accentTextProbe, Color.web("#58a6ff"));
            };
        }

        /** Small bookmark and note outlines drawn directly on the Canvas (no scene-graph nodes per row). */
        private void drawFileMarkers(
                GraphicsContext g, ProjectMapModel.Entry entry, NodeBox box, boolean selectedNode) {
            // Folders end in the chevron strip, files in the (slightly wider) preview strip.
            double x = box.x() + box.width() - ((entry.directory() ? CHEVRON_ZONE : PREVIEW_ZONE) + 6) * zoom;
            double y = box.y() + 10 * zoom;
            g.setLineWidth(Math.max(1, 1.25 * zoom));
            if (notedPaths.contains(entry.path())) {
                g.setStroke(selectedNode ? onAccent() : color(accentTextProbe, Color.web("#388bfd")));
                g.strokeRoundRect(x - 4 * zoom, y, 8 * zoom, 7 * zoom, 2 * zoom, 2 * zoom);
                g.strokeLine(x - 2 * zoom, y + 7 * zoom, x - 4 * zoom, y + 9 * zoom);
                x -= MARKER_WIDTH * zoom;
            }
            if (bookmarkedPaths.contains(entry.path())) {
                g.setStroke(selectedNode ? onAccent() : color(warningProbe, Color.web("#d29922")));
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

        // Tracks which part of the hovered row the pointer is on. It is registered here, apart from the
        // row-hover handler, because it only feeds painting and the tooltip: the eye and the chevron light
        // up, and the tooltip names what a click on them does.
        {
            addEventHandler(MouseEvent.MOUSE_MOVED, event -> updateAffordance(event.getX(), event.getY()));
            addEventHandler(MouseEvent.MOUSE_EXITED, event -> setAffordance(Affordance.NONE, null));
        }

        private void updateAffordance(double x, double y) {
            NodeBox box = hit(x, y);
            Affordance next = Affordance.NONE;
            if (box != null && !(overviewBox != null && overviewBox.contains(x, y))) {
                if (notePreviewHit(box, x)) {
                    next = Affordance.NOTES;
                } else if (chevronHit(box, x)) {
                    next = Affordance.CHEVRON;
                } else if (!box.entry().directory() && previewHit(box, x)) {
                    next = Affordance.PREVIEW;
                }
            }
            setAffordance(next, box);
        }

        private void setAffordance(Affordance next, NodeBox box) {
            if (next == hoveredAffordance) {
                return;
            }
            hoveredAffordance = next;
            if (box != null && nodeTooltip.getText() != null) {
                nodeTooltip.setText(tooltipText(box.entry())); // still on the same row: only the hint changes
            }
            requestViewportRepaint();
        }

        /** What a click does on the part of the row under the pointer, or null where it is the row itself. */
        private String affordanceHint(ProjectMapModel.Entry entry) {
            return switch (hoveredAffordance) {
                case PREVIEW -> entry.directory() ? null : tr("project.map.node.preview", entry.name());
                case NOTES -> tr("project.map.node.notes", entry.name());
                case CHEVRON ->
                    !entry.directory()
                            ? null
                            : tr(
                                    expandedSnapshot.contains(entry.path())
                                            ? "project.map.node.collapse"
                                            : "project.map.node.expand",
                                    entry.name());
                case NONE -> null;
            };
        }

        private enum Affordance {
            NONE,
            PREVIEW,
            NOTES,
            CHEVRON
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
                if (entry.isPlaceholder()) {
                    continue;
                }
                if (!entry.directory() && safeTest(openState, entry.path())) {
                    open.add(entry.path());
                }
                if (!entry.directory() && safeTest(modifiedState, entry.path())) {
                    modified.add(entry.path());
                }
                // Folders carry bookmarks and Personal Notes too, as in the Tree.
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
            dragMoved = false;
            if (isColumnControl(event.getTarget())) {
                panning = false;
                draggedColumn = null;
                return;
            }
            requestFocus();
            flushPendingRepaint();
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
            panning = event.getButton() == MouseButton.MIDDLE
                    || event.getButton() == MouseButton.PRIMARY && hit(event.getX(), event.getY()) == null;
        }

        private void mouseDragged(MouseEvent event) {
            if (draggedColumn != null) {
                ColumnLayout layout = columnLayouts.get(draggedColumn);
                if (layout != null && !layout.locked) {
                    // Store what the layout will actually use: an offset past the parent-side limit would
                    // have to be dragged back out again before the column moved.
                    boolean limited = draggedColumn.depth() > 0;
                    layout.x = flowLimitedOffset(
                            flowDirection, true, limited, columnPressOffsetX + (event.getX() - pressX) / zoom);
                    layout.y = flowLimitedOffset(
                            flowDirection, false, limited, columnPressOffsetY + (event.getY() - pressY) / zoom);
                    dragMoved = true;
                    requestViewportRepaint();
                }
                event.consume();
                return;
            }
            if (!panning) {
                return;
            }
            offsetX = pressOffsetX + event.getX() - pressX;
            offsetY = pressOffsetY + event.getY() - pressY;
            dragMoved = true;
            pointerX = event.getX();
            pointerY = event.getY();
            requestViewportRepaint();
            event.consume();
        }

        private void mouseMoved(MouseEvent event) {
            if (isColumnControl(event.getTarget())) {
                return;
            }
            flushPendingRepaint();
            pointerInside = true;
            pointerX = event.getX();
            pointerY = event.getY();
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

        /** The pointer left the map: nothing is hovered any more. */
        private void mouseExited(MouseEvent event) {
            pointerInside = false;
            setCursor(Cursor.DEFAULT);
            if (hovered != null) {
                clearNodeTooltip();
                repaint();
            }
        }

        /**
         * Re-points the hover at whatever row now lies under a resting pointer, after a zoom, pan or reload
         * moved the rows. Returns whether the highlighted row changed (the caller repaints).
         */
        private boolean syncHover() {
            if (!pointerInside) {
                return false;
            }
            NodeBox hit = hit(pointerX, pointerY);
            Path next = hit == null ? null : hit.entry().path();
            if (java.util.Objects.equals(next, hovered)) {
                return false;
            }
            if (hit == null) {
                clearNodeTooltip();
            } else {
                hovered = next;
                nodeTooltip.setText(tooltipText(hit.entry()));
            }
            return true;
        }

        private void clearNodeTooltip() {
            hovered = null;
            nodeTooltip.hide();
            nodeTooltip.setText(null);
        }

        private String tooltipText(ProjectMapModel.Entry entry) {
            if (entry.isPlaceholder()) {
                return entry.name();
            }
            List<String> lines = new ArrayList<>(5);
            String hint = affordanceHint(entry);
            if (hint != null) {
                lines.add(hint);
            }
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
            // Binary units, named as such: the divisor is 1024, so "kB" understated every size by 2.4%.
            String[] units = {"KiB", "MiB", "GiB", "TiB"};
            double value = bytes;
            int unit = -1;
            do {
                value /= 1024.0;
                unit++;
            } while (value >= 1024 && unit < units.length - 1);
            return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
        }

        private void mouseClicked(MouseEvent event) {
            // The release that ends a pan or a header drag is not a click on the row it happens to land on.
            if (event.getButton() != MouseButton.PRIMARY || dragMoved || !event.isStillSincePress()) {
                return;
            }
            flushPendingRepaint();
            if (overviewBox != null && overviewBox.contains(event.getX(), event.getY())) {
                event.consume();
                return;
            }
            NodeBox hit = hit(event.getX(), event.getY());
            if (hit == null) {
                return;
            }
            ProjectMapModel.Entry entry = hit.entry();
            select(entry.path());
            if (notePreviewHit(hit, event.getX())) {
                onNotesPreview.accept(entry.path());
            } else if (!entry.directory() && previewHit(hit, event.getX())) {
                onPreview.accept(entry.path());
            } else if (event.getClickCount() == 1) {
                // A folder that is already open is only selected; its chevron (or the column's close
                // button) collapses it. Collapsing on any click threw away the whole subtree's expansion.
                if (entry.directory() && expandedSnapshot.contains(entry.path()) && !chevronHit(hit, event.getX())) {
                    revealColumnOf(entry.path());
                } else {
                    onActivate.accept(entry);
                }
            }
            event.consume();
        }

        private boolean previewHit(NodeBox box, double x) {
            return x >= box.x() + box.width() - PREVIEW_ZONE * zoom;
        }

        private boolean notePreviewHit(NodeBox box, double x) {
            if (!notedPaths.contains(box.entry().path())) {
                return false;
            }
            // The badge sits just before the row's trailing strip: the chevron's on a folder, Preview on a file.
            double right = box.x() + box.width() - (box.entry().directory() ? CHEVRON_ZONE : PREVIEW_ZONE) * zoom;
            return x >= right - MARKER_WIDTH * zoom && x < right;
        }

        private void contextMenuRequested(ContextMenuEvent event) {
            dismissContextMenu();
            flushPendingRepaint();
            NodeBox hit;
            double screenX = event.getScreenX();
            double screenY = event.getScreenY();
            if (event.isKeyboardTrigger()) {
                // The Menu key / Shift+F10 carry a point inside the focus owner that has nothing to do with
                // the selection. The menu is the selected row's, opened at that row (as RowContextMenu does
                // for the trees).
                if (isColumnControl(event.getTarget())) {
                    return;
                }
                revealSelected();
                repaint();
                hit = nodeBox(selected);
                if (hit == null) {
                    event.consume();
                    return;
                }
                javafx.geometry.Point2D anchor =
                        localToScreen(hit.x() + RowContextMenu.anchorX(hit.width()), hit.y() + hit.height());
                if (anchor != null) {
                    screenX = anchor.getX();
                    screenY = anchor.getY();
                }
            } else {
                if (overviewBox != null && overviewBox.contains(event.getX(), event.getY())) {
                    return;
                }
                hit = hit(event.getX(), event.getY());
                if (hit == null) {
                    return;
                }
                select(hit.entry().path());
            }
            requestFocus();
            clearNodeTooltip();
            ContextMenu menu = contextMenuFactory.apply(hit.entry());
            if (menu != null && !menu.getItems().isEmpty()) {
                activeContextMenu = menu;
                menu.addEventHandler(WindowEvent.WINDOW_HIDDEN, hidden -> {
                    if (activeContextMenu == menu) {
                        activeContextMenu = null;
                        removeDismissFilter();
                    }
                });
                menu.show(this, screenX, screenY);
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
            pointerInside = true;
            pointerX = event.getX();
            pointerY = event.getY();
            double deltaX = event.getDeltaX();
            double deltaY = event.getDeltaY();
            // Some platforms report Shift+wheel as a horizontal delta already; take whichever axis moved.
            double wheel = deltaY != 0 ? deltaY : deltaX;
            switch (wheelAction(deltaX, deltaY, event.isShiftDown(), event.isAltDown(), event.isInertia())) {
                case PAN_X -> {
                    offsetX += event.isShiftDown() ? wheel : deltaX;
                    requestViewportRepaint();
                }
                case PAN_Y -> {
                    offsetY += wheel;
                    requestViewportRepaint();
                }
                case ZOOM -> {
                    double speed = event.isControlDown() || event.isMetaDown() ? 0.004 : 0.0025;
                    double exponent = Math.max(-0.45, Math.min(0.45, deltaY * speed));
                    setZoom(zoom * Math.exp(exponent), event.getX(), event.getY());
                }
                case NONE -> {}
            }
            event.consume();
        }

        /** A touchpad or touch-screen pinch zooms around the gesture's centre. */
        private void pinched(ZoomEvent event) {
            double factor = event.getZoomFactor();
            if (Double.isFinite(factor) && factor > 0) {
                setZoom(zoom * factor, event.getX(), event.getY());
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
            requestViewportRepaint();
        }

        private void keyPressed(KeyEvent event) {
            // Parent event filters also see keystrokes targeted at child controls. Text editing owns every
            // key while a per-column filter has focus; otherwise Backspace/Home/arrows become map commands.
            if (isColumnControl(event.getTarget())) {
                return;
            }
            boolean plain = !event.isControlDown() && !event.isAltDown() && !event.isMetaDown();
            // Escape, history and Fit do not need a row, and the chords the surface claims from the global
            // dispatcher (see claimKeys) are always consumed here so they never reach the native menu.
            if (event.getCode() == KeyCode.ESCAPE && plain) {
                escapePressed();
                event.consume();
                return;
            }
            boolean onlyAlt =
                    event.isAltDown() && !event.isControlDown() && !event.isMetaDown() && !event.isShiftDown();
            if (onlyAlt && (event.getCode() == KeyCode.LEFT || event.getCode() == KeyCode.RIGHT)) {
                moveHistory(event.getCode() == KeyCode.LEFT ? -1 : 1);
                event.consume();
                return;
            }
            if (event.isShortcutDown()
                    && !event.isAltDown()
                    && (event.getCode() == KeyCode.DIGIT0 || event.getCode() == KeyCode.NUMPAD0)) {
                fitContent();
                event.consume();
                return;
            }
            if (entries.isEmpty()) {
                return;
            }
            if (selected == null) {
                selected = entries.getFirst().path();
            }
            boolean onlyControl = event.isControlDown() && !event.isAltDown() && !event.isMetaDown();
            switch (event.getCode()) {
                case UP, DOWN, LEFT, RIGHT -> navigateArrow(event.getCode());
                case PAGE_UP -> moveSibling(-10);
                case PAGE_DOWN -> moveSibling(10);
                case BACK_SPACE -> selectParent();
                case ENTER, SPACE -> activateSelection();
                case HOME -> select(entries.getFirst().path());
                default -> {
                    if (onlyControl && event.getCode() == KeyCode.N) {
                        moveSibling(1);
                    } else if (onlyControl && event.getCode() == KeyCode.P) {
                        moveSibling(-1);
                    } else if (plain && !event.isShiftDown() && rowAction(event.getCode())) {
                        // handled: F2 / Delete, the Project tree's row actions
                    } else {
                        return;
                    }
                }
            }
            event.consume();
        }

        /**
         * {@code /} focuses the selected column's filter. It is matched by the character typed, not the key:
         * on most non-US layouts the slash is Shift+7, and the numpad has one too. Acting on KEY_TYPED also
         * means the character has already been delivered here, so it cannot land in the field.
         */
        private void keyTyped(KeyEvent event) {
            if (isColumnControl(event.getTarget()) || entries.isEmpty()) {
                return;
            }
            boolean shortcut = event.isMetaDown() || event.isControlDown() && !event.isAltDown(); // not AltGr
            if (!shortcut && "/".equals(event.getCharacter())) {
                focusSelectedColumnFilter();
                event.consume();
            }
        }

        /** F2 renames and Delete deletes the selected entry, through the Project tree's own row actions. */
        private boolean rowAction(KeyCode code) {
            ProjectMapModel.Entry entry =
                    selectedEntry().filter(row -> !row.isPlaceholder()).orElse(null);
            switch (ProjectPanel.rowKey(code, true, entry != null)) {
                case RENAME -> onRenameEntry.accept(entry);
                case DELETE -> onDeleteEntry.accept(entry);
                default -> {
                    return false;
                }
            }
            return true;
        }

        /**
         * Hands the surface's own chords to it ahead of the keymap while it is the focus owner: history
         * (Alt+Left/Right, otherwise swallowed as an unbound Alt chord), Fit (Shortcut+0, text-zoom reset in
         * every keymap), Ctrl-N/Ctrl-P (New File, Print or Find File outside Emacs) and the row actions F2
         * and Delete. Not while a column filter has the focus: there the keymap keeps its meaning.
         */
        private void claimKeys(boolean claim) {
            if (claim) {
                getProperties()
                        .put(
                                com.editora.command.KeyDispatcher.CLAIMED_KEYS,
                                claimedChords(com.editora.command.KeymapManager.isMac()));
            } else {
                getProperties().remove(com.editora.command.KeyDispatcher.CLAIMED_KEYS);
            }
        }

        private void focusOwnerChanged(
                javafx.beans.value.ObservableValue<? extends Node> property, Node old, Node owner) {
            claimKeys(owner == this);
            // A column control that held the focus was hidden (zoomed out) or removed (column closed): the
            // scene then picks the next focusable node, a zoom-bar button, and the arrow keys stop
            // navigating. Keep the keyboard on the map instead.
            if (old != null
                    && old != this
                    && isColumnControl(old)
                    && (old.getScene() == null || !old.isVisible())
                    && !withinSurface(owner)) {
                // Deferred: the scene may still be half-way through choosing that next node.
                Platform.runLater(() -> {
                    Scene scene = getScene();
                    if (scene != null && !withinSurface(scene.getFocusOwner())) {
                        requestFocus();
                    }
                });
            }
        }

        private boolean withinSurface(Node node) {
            for (Node current = node; current != null; current = current.getParent()) {
                if (current == this) {
                    return true;
                }
            }
            return false;
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
            movingSibling = true;
            try {
                select(column.get(siblingIndex(index < 0 ? 0 : index, delta, column.size()))
                        .path());
            } finally {
                movingSibling = false;
            }
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

        /** Activates the selection — unless a column filter has hidden its row: nobody opens what is not shown. */
        private void activateSelection() {
            flushPendingRepaint();
            selectedEntry().filter(entry -> nodeBox(entry.path()) != null).ifPresent(onActivate);
        }

        /** Whether the selection lies strictly below {@code directory} (in the branch a column close removes). */
        private boolean selectionBelow(Path directory) {
            return selected != null && !selected.equals(directory) && selected.startsWith(directory);
        }

        /**
         * Focuses the selected column's filter. A filter hidden by a low zoom is brought back first — the
         * map zooms in just far enough, around that column's header — and scrolled into the viewport. The
         * project column has no filter; say so instead of swallowing the key.
         */
        private void focusSelectedColumnFilter() {
            ProjectMapModel.ColumnId id =
                    selectedEntry().map(MapSurface::columnId).orElse(null);
            ColumnControls controls = id == null ? null : columnControls.get(id);
            if (controls == null) {
                onStatus.accept(tr("project.map.status.rootColumnNoFilter"));
                return;
            }
            TextField filter = controls.filter();
            repaint();
            for (double step : FILTER_ZOOM_STEPS) {
                ColumnBox column = columnBox(id);
                if (filter.isVisible() || column == null) {
                    break;
                }
                if (zoom < step) {
                    setZoom(step, column.x(), column.y());
                    repaint();
                }
            }
            if (!filter.isVisible()) {
                onStatus.accept(tr("project.map.status.columnFilterCannotShow"));
                return;
            }
            double margin = 12;
            double shiftX = shiftIntoView(filter.getLayoutX(), filter.getWidth(), getWidth(), margin);
            double shiftY = shiftIntoView(filter.getLayoutY(), filter.getHeight(), getHeight(), margin);
            if (shiftX != 0 || shiftY != 0) {
                offsetX += shiftX;
                offsetY += shiftY;
                repaint();
            }
            filter.requestFocus();
            filter.selectAll();
        }

        /**
         * Enter in a column filter returns to the map. The selection moves to that column's first remaining
         * row unless it is already one of them, so the next Enter acts on what the filter found rather than on
         * the folder that owns the column.
         */
        private void leaveColumnFilter(ProjectMapModel.ColumnId id) {
            boolean selectionInColumn = selected != null
                    && boxes.stream()
                            .anyMatch(box -> box.entry().path().equals(selected)
                                    && columnId(box.entry()).equals(id));
            if (!selectionInColumn) {
                boxes.stream()
                        .filter(box -> columnId(box.entry()).equals(id))
                        .findFirst()
                        .ifPresent(box -> select(box.entry().path()));
            }
            requestFocus();
        }

        /** Scrolls the column a folder opened into the viewport, leading edge and header first. */
        private void revealColumnOf(Path directory) {
            ColumnBox column = columnBoxes.stream()
                    .filter(box -> directory.equals(box.column().parent()))
                    .findFirst()
                    .orElse(null);
            if (column == null) {
                return;
            }
            double margin = 20;
            double shiftX = shiftIntoView(column.x(), column.width(), getWidth(), margin);
            double shiftY = shiftIntoView(column.y(), column.height(), getHeight(), margin);
            if (shiftX != 0 || shiftY != 0) {
                offsetX += shiftX;
                offsetY += shiftY;
                repaint();
            }
        }

        /** Input handlers hit-test the boxes of the last paint; bring them up to date with a coalesced pan or zoom. */
        private void flushPendingRepaint() {
            if (viewportRepaintPending) {
                repaint();
            }
        }

        private void revealSelected() {
            flushPendingRepaint();
            NodeBox box = nodeBox(selected);
            if (box == null) {
                return;
            }
            double[] pan = new double[2];
            include(pan, box.x(), box.y(), box.x() + box.width(), box.y() + box.height());
            // The overview floats over the bottom-right corner: a row revealed there would sit under it.
            OverviewBox overview = overviewBox;
            if (overview != null) {
                double left = box.x() + pan[0];
                double top = box.y() + pan[1];
                if (left + box.width() > overview.x() - 4
                        && left < overview.x() + overview.width()
                        && top + box.height() > overview.y() - 4
                        && top < overview.y() + overview.height()) {
                    pan[1] -= top + box.height() - (overview.y() - 8);
                }
            }
            offsetX += pan[0];
            offsetY += pan[1];
        }

        /**
         * The smallest shift that brings the span {@code low..high} inside {@code min..max}. A span larger
         * than the range is aligned on its start, which is where a column's header and a row's name are.
         */
        static double shiftIntoRange(double low, double high, double min, double max) {
            if (high - low > max - min || low < min) {
                return min - low;
            }
            return high > max ? max - high : 0;
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

        /**
         * Where a card for {@code path} opens: beside its column on the side the flow leaves free (else the
         * other side) when at least the card's minimum fits there, otherwise over the map against the
         * panel edge. Reads the layout only — the viewport is never moved to make room.
         */
        private ProjectMapPreview.Placement previewPlacement(
                Path path,
                double requestedWidth,
                double requestedHeight,
                double minimumWidth,
                double minimumHeight,
                double parentWidth,
                double parentHeight) {
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
            double fullWidth = boundedPreviewSize(requestedWidth, minimumWidth, parentWidth - PREVIEW_EDGE_MARGIN * 2);
            double fullHeight =
                    boundedPreviewSize(requestedHeight, minimumHeight, parentHeight - PREVIEW_EDGE_MARGIN * 2);
            double farX = Math.max(PREVIEW_EDGE_MARGIN, parentWidth - fullWidth - PREVIEW_EDGE_MARGIN);
            double farY = Math.max(PREVIEW_EDGE_MARGIN, parentHeight - fullHeight - PREVIEW_EDGE_MARGIN);
            if (node == null || column == null) {
                return new ProjectMapPreview.Placement(farX, PREVIEW_EDGE_MARGIN, fullWidth, fullHeight);
            }

            boolean forward =
                    flowDirection == FlowDirection.LEFT_TO_RIGHT || flowDirection == FlowDirection.TOP_TO_BOTTOM;
            if (flowDirection == FlowDirection.LEFT_TO_RIGHT || flowDirection == FlowDirection.RIGHT_TO_LEFT) {
                double y = clampPreview(node.y() + node.height() / 2 - fullHeight / 2, PREVIEW_EDGE_MARGIN, farY);
                double afterX = Math.max(PREVIEW_EDGE_MARGIN, column.x() + column.width() + PREVIEW_COLUMN_GAP);
                double afterRoom = parentWidth - PREVIEW_EDGE_MARGIN - afterX;
                double beforeEnd = Math.min(parentWidth - PREVIEW_EDGE_MARGIN, column.x() - PREVIEW_COLUMN_GAP);
                double beforeRoom = beforeEnd - PREVIEW_EDGE_MARGIN;
                boolean after =
                        forward ? afterRoom >= minimumWidth || beforeRoom < minimumWidth : beforeRoom < minimumWidth;
                double room = after ? afterRoom : beforeRoom;
                if (room < minimumWidth) {
                    // No side has room (a tool-window-wide panel): lie over the map rather than shrink to a sliver.
                    return new ProjectMapPreview.Placement(
                            forward ? farX : PREVIEW_EDGE_MARGIN, y, fullWidth, fullHeight, !forward);
                }
                double width = Math.min(fullWidth, room);
                return new ProjectMapPreview.Placement(
                        after ? afterX : beforeEnd - width, y, width, fullHeight, !after);
            }

            double x = clampPreview(node.x() + node.width() / 2 - fullWidth / 2, PREVIEW_EDGE_MARGIN, farX);
            double afterY = Math.max(PREVIEW_EDGE_MARGIN, column.y() + column.height() + PREVIEW_COLUMN_GAP);
            double afterRoom = parentHeight - PREVIEW_EDGE_MARGIN - afterY;
            double beforeEnd = Math.min(parentHeight - PREVIEW_EDGE_MARGIN, column.y() - PREVIEW_COLUMN_GAP);
            double beforeRoom = beforeEnd - PREVIEW_EDGE_MARGIN;
            boolean after =
                    forward ? afterRoom >= minimumHeight || beforeRoom < minimumHeight : beforeRoom < minimumHeight;
            double room = after ? afterRoom : beforeRoom;
            if (room < minimumHeight) {
                return new ProjectMapPreview.Placement(x, forward ? farY : PREVIEW_EDGE_MARGIN, fullWidth, fullHeight);
            }
            double height = Math.min(fullHeight, room);
            return new ProjectMapPreview.Placement(x, after ? afterY : beforeEnd - height, fullWidth, height);
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
            ProjectMapModel.Entry current = selectedEntry().orElse(null);
            if (current == null) {
                setAccessibleText(tr("project.map.accessibleSelection", tr("project.map.empty")));
                return;
            }
            // The surface is one accessible node, so its text carries what a tree item would expose: the
            // kind of row, whether a folder is open, and where the row stands among its siblings.
            ProjectMapModel.ColumnId column = columnId(current);
            java.util.Comparator<ProjectMapModel.Entry> order =
                    ProjectPathOrder.directoriesFirst(ProjectMapModel.Entry::directory, ProjectMapModel.Entry::name);
            int position = 1;
            int siblings = 0;
            for (ProjectMapModel.Entry entry : entries) {
                if (columnId(entry).equals(column)) {
                    siblings++;
                    if (order.compare(entry, current) < 0) {
                        position++; // rows are shown in this order, whatever order they were loaded in
                    }
                }
            }
            String key;
            if (current.directory()) {
                key = expandedSnapshot.contains(current.path())
                        ? "project.map.accessible.folderExpanded"
                        : "project.map.accessible.folderCollapsed";
            } else {
                key = openPaths.contains(current.path())
                        ? "project.map.accessible.fileOpen"
                        : "project.map.accessible.file";
            }
            setAccessibleText(tr(key, current.name(), position, siblings));
        }

        private Rectangle probe(String styleClass) {
            Rectangle probe = new Rectangle(0, 0);
            probe.getStyleClass().add(styleClass);
            probe.setManaged(false);
            probe.setMouseTransparent(true);
            return probe;
        }

        /** Text, glyphs and markers drawn over the selected node's accent fill. */
        private Color onAccent() {
            return color(onAccentProbe, Color.WHITE);
        }

        private Color color(Rectangle probe, Color fallback) {
            if (outputPalette != null) {
                return outputPalette.getOrDefault(probe, fallback);
            }
            Paint fill = probe.getFill();
            return fill instanceof Color value ? value : fallback;
        }

        private Color accentColor() {
            return color(accentProbe, Color.web("#58a6ff"));
        }

        /** Ink for a Personal Notes card's connector: the theme's note (amber) colour. */
        private Color noteColor() {
            return color(warningProbe, Color.web("#b08a00"));
        }

        private NodeBox nodeBox(Path path) {
            return boxes.stream()
                    .filter(box -> box.entry().path().equals(path))
                    .findFirst()
                    .orElse(null);
        }

        private boolean inViewport(NodeBox box, double width, double height) {
            return box.x() + box.width() >= 0 && box.y() + box.height() >= 0 && box.x() <= width && box.y() <= height;
        }

        private boolean connectorInViewport(NodeBox child, double width, double height) {
            return child.x() + child.width() >= -CONNECTOR_VIEWPORT_OVERSCAN
                    && child.y() + child.height() >= -CONNECTOR_VIEWPORT_OVERSCAN
                    && child.x() <= width + CONNECTOR_VIEWPORT_OVERSCAN
                    && child.y() <= height + CONNECTOR_VIEWPORT_OVERSCAN;
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

    /** What one wheel or touchpad scroll event does to the viewport. */
    enum WheelAction {
        NONE,
        PAN_X,
        PAN_Y,
        ZOOM
    }

    /**
     * Shift pans across and Alt pans down, whichever axis the platform reports the wheel on. Otherwise a
     * mostly-horizontal delta (a touchpad swipe, a tilt wheel) pans across, and a vertical one zooms — but not
     * the inertia that keeps arriving after the fingers have lifted, which is ignored. Pure — tested.
     */
    static WheelAction wheelAction(double deltaX, double deltaY, boolean shift, boolean alt, boolean inertia) {
        if (deltaX == 0 && deltaY == 0) {
            return WheelAction.NONE;
        }
        if (shift) {
            return WheelAction.PAN_X;
        }
        if (alt) {
            return WheelAction.PAN_Y;
        }
        if (Math.abs(deltaX) > Math.abs(deltaY)) {
            return WheelAction.PAN_X;
        }
        return inertia ? WheelAction.NONE : WheelAction.ZOOM;
    }

    /**
     * The row a sibling move lands on. A single step wraps around the column; a page move (any larger step)
     * stops at the first or last row. Pure — tested.
     */
    static int siblingIndex(int start, int delta, int size) {
        return Math.abs(delta) == 1 ? Math.floorMod(start + delta, size) : Math.clamp(start + delta, 0, size - 1);
    }

    /**
     * A dragged column's manual offset along one axis, as the layout will use it: a column with a parent may
     * move farther out along the flow or anywhere across it, but never back through its parent. Pure — tested.
     */
    static double flowLimitedOffset(FlowDirection flow, boolean xAxis, boolean hasParent, double offset) {
        if (!hasParent) {
            return offset;
        }
        return switch (flow) {
            case LEFT_TO_RIGHT -> xAxis ? Math.max(0, offset) : offset;
            case RIGHT_TO_LEFT -> xAxis ? Math.min(0, offset) : offset;
            case TOP_TO_BOTTOM -> xAxis ? offset : Math.max(0, offset);
            case BOTTOM_TO_TOP -> xAxis ? offset : Math.min(0, offset);
        };
    }

    /**
     * How far to move a span so it lies inside {@code [margin, viewport - margin]}; a span too long for
     * that is aligned at its start. Zero when it already fits. Pure — tested.
     */
    static double shiftIntoView(double start, double length, double viewport, double margin) {
        if (start < margin || length > viewport - margin * 2) {
            return margin - start;
        }
        double overflow = start + length - (viewport - margin);
        return overflow > 0 ? -overflow : 0;
    }

    /** The chord tokens the focused map surface takes ahead of the keymap; see {@code MapSurface.claimKeys}. */
    static Set<String> claimedChords(boolean mac) {
        return Set.of("M-left", "M-right", mac ? "Cmd-0" : "C-0", "C-n", "C-p", "f2", "delete");
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

    /**
     * The border that separates a row from its card. The theme's border token alone is about 1.5:1 against
     * the card in both Editora themes; three fifths of the way to the muted ink clears 3:1 in both.
     */
    static Color rowBorderColor(Color border, Color muted) {
        return border.interpolate(muted, 0.6);
    }

    /**
     * Connector opacity. An ordinary connector must still read as a line on the canvas background (3:1 in
     * the light theme needs most of the accent's ink); filtered-out ones recede but stay traceable.
     */
    static double connectorOpacity(boolean selectedPath, boolean prominent) {
        return selectedPath ? 0.95 : prominent ? 0.8 : 0.2;
    }
}
