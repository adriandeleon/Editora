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
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.stage.Popup;
import javafx.stage.WindowEvent;
import javafx.util.Duration;
import javafx.util.StringConverter;

import com.editora.config.PersonalNote;
import com.editora.git.GitFileStatus;
import org.jspecify.annotations.Nullable;

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
    private final Function<Path, ProjectMapPreview.@Nullable Content> previewContent;
    private final ToggleButton openFilter = filterButton("project.map.filter.open");
    private final ToggleButton modifiedFilter = filterButton("project.map.filter.modified");
    private final ToggleButton gitFilter = filterButton("project.map.filter.gitChanged");
    private final ToggleButton bookmarksFilter = filterButton("project.map.filter.bookmarks");
    private final ToggleButton personalNotesFilter = filterButton("project.map.filter.personalNotes");
    // The three session options and the two output actions live in the options (⋯) menu, not in the filter row.
    private final CheckMenuItem hideOpenNotes = optionItem("project.map.filter.hideOpenNotes", false);
    private final CheckMenuItem keepZoomOnOpen = optionItem("project.map.navigation.keepZoom", true);
    private final CheckMenuItem focusNewColumn = optionItem("project.map.navigation.focusNewColumn", true);
    /** "Compact folder chains": a run of single-child folders opens, and is drawn, as one column. */
    private final CheckMenuItem compactChainsOption = optionItem("project.map.layout.compactChains", true);

    private final ComboBox<ProjectMapModel.TypeFilter> typeFilter = new ComboBox<>();
    /** "Hide unmatched": rows the filters would fade are taken off the map, and the badge says how many. */
    private final CheckBox hideUnmatched = new CheckBox(tr("project.map.filter.hideUnmatched"));

    private final Label narrowedBadge = new Label();
    private Consumer<Boolean> onHideUnmatchedChanged = hide -> {};
    private final ComboBox<FlowDirection> flowFilter = new ComboBox<>();
    private final Button backButton = new Button("‹");
    private final Button forwardButton = new Button("›");
    private final MenuItem printButton = new MenuItem(tr("project.map.print"));
    private final MenuItem exportPdfButton = new MenuItem(tr("project.map.exportPdf"));
    private final HBox breadcrumbs = new HBox(2);
    private final Label breadcrumbEllipsis = new Label("…");
    /** The paths the breadcrumb buttons stand for, and the buttons, root first. */
    private final List<Path> crumbTrail = new ArrayList<>();

    private final List<Button> crumbButtons = new ArrayList<>();
    private final Label breadcrumbEllipsisSeparator = new Label("›");
    private final Label noMatchesLabel = new Label();
    private final PauseTransition noMatchesDelay = new PauseTransition(NO_MATCHES_DELAY);
    private @Nullable Popup helpPopup;
    private long helpHiddenAt;
    private final MapSurface surface = new MapSurface();
    private final Canvas previewConnectorCanvas = new Canvas(1, 1);
    private final Map<Path, PreviewConnector> previewConnectors = new HashMap<>();
    private final Map<Path, ProjectMapPreview> previews = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<Path, ProjectMapNotePreview> notePreviews = new LinkedHashMap<>(16, 0.75f, true);
    /** The sticky notes a code card's Notes button stuck on it, by card. */
    private final Map<ProjectMapPreview, ProjectMapStickyNotes> stickyNotes = new java.util.IdentityHashMap<>();
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
    private @Nullable Set<Path> cardsCheckedAgainst;

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

    private @Nullable Path root;
    private boolean disposed;
    /** False while the Project panel shows the Tree: loads wait until the Map is on screen. */
    private boolean active = true;
    /** The Project "show hidden files" setting: each column's default, and what the loader lists. */
    private boolean showHiddenDefault = true;

    private boolean loadInFlight;
    private boolean announceLimit;
    private boolean announceMatches;
    private boolean wasCapped;
    private @Nullable Path loadMoreDirectory;
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
    private @Nullable Path pendingSelection;
    private Consumer<FlowDirection> onFlowChanged = ignored -> {};
    private Consumer<ProjectMapOutput> onPrint = ignored -> {};
    private Consumer<ProjectMapOutput> onExportPdf = ignored -> {};
    private ProjectMapPreview.@Nullable MarkerActions previewMarkerActions;
    private ProjectPanel.@Nullable MarkerActions notePreviewActions;
    private StackPane canvasHost;
    /** The open structure columns and the outlines behind them. */
    private final ProjectMapStructure structure;

    private java.util.function.BiConsumer<Path, Integer> onOpenAt = (file, line) -> {};
    private java.util.function.BiConsumer<Path, com.editora.text.FoldRegions.Region> onOpenNarrowed =
            (file, block) -> {};

    ProjectMapView(Consumer<Path> onOpenFile, Predicate<Path> isOpen, Predicate<Path> isModified) {
        this(onOpenFile, isOpen, isModified, path -> null);
    }

    ProjectMapView(
            Consumer<Path> onOpenFile,
            Predicate<Path> isOpen,
            Predicate<Path> isModified,
            Function<Path, ProjectMapPreview.@Nullable Content> previewContent) {
        this.onOpenFile = onOpenFile;
        this.isOpen = isOpen == null ? path -> false : isOpen;
        this.isModified = isModified == null ? path -> false : isModified;
        this.previewContent = previewContent == null ? path -> null : previewContent;
        this.structure = new ProjectMapStructure(this::previewContentFor);
        structure.setOnChanged(this::structureChanged);
        getStyleClass().add("project-map-view");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(4);

        FlowPane filters = buildFilters();
        HBox navigation = buildNavigation();
        canvasHost = buildCanvasHost();
        getChildren().addAll(filters, navigation, canvasHost);
        VBox.setVgrow(getChildren().get(2), Priority.ALWAYS);
        surface.setOnActivate(this::activate);
        surface.setOnCloseColumn(this::closeColumn);
        surface.setOnPreview(this::previewSelection);
        surface.setOnStructure(this::toggleStructure);
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
                        compactChainsOption,
                        hideOpenNotes,
                        new SeparatorMenuItem(),
                        printButton,
                        exportPdfButton);
        printButton.setDisable(true);
        exportPdfButton.setDisable(true);
        printButton.setOnAction(event -> onPrint.accept(mapOutput()));
        exportPdfButton.setOnAction(event -> onExportPdf.accept(mapOutput()));
        // Listeners rather than action handlers, so a restored value applies exactly like a click.
        hideOpenNotes.selectedProperty().addListener((obs, old, selected) -> updateCardVisibility());
        keepZoomOnOpen
                .selectedProperty()
                .addListener((obs, old, selected) -> surface.setKeepZoomOnColumnOpen(selected));
        focusNewColumn.selectedProperty().addListener((obs, old, selected) -> surface.setFocusNewColumn(selected));
        compactChainsOption.selectedProperty().addListener((obs, old, selected) -> layoutCompactChanged(selected));

        HBox row = new HBox(3, backButton, forwardButton, breadcrumbs, help, options);
        row.getStyleClass().add("project-map-navigation");
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(breadcrumbs, Priority.ALWAYS);
        updateNavigation();
        return row;
    }

    /** Whether a run of single-child folders opens, and is drawn, as one column ("Compact folder chains"). */
    boolean isCompactChains() {
        return compactChainsOption.isSelected();
    }

    void setCompactChains(boolean compact) {
        compactChainsOption.setSelected(compact);
    }

    /**
     * The chains open with their first folder while the option is on. Turning it off keeps the folders such a
     * chain opened open, so the columns stay where they are, only drawn one per folder again.
     */
    private void layoutCompactChanged(boolean compact) {
        if (!compact && !searching()) {
            boolean changed = expanded.addAll(snapshot.loadedDirectories());
            if (changed) {
                onExpandedChanged.run();
            }
        }
        surface.setCompactChains(compact);
        reload();
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
            updateCardVisibility();
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
        hideUnmatched.getStyleClass().add("project-map-hide-unmatched");
        hideUnmatched.setMinWidth(Region.USE_PREF_SIZE);
        hideUnmatched.setTooltip(new Tooltip(tr("project.map.filter.hideUnmatched.help")));
        hideUnmatched.setAccessibleHelp(tr("project.map.filter.hideUnmatched.help"));
        hideUnmatched.selectedProperty().addListener((obs, old, hide) -> {
            surface.setHideUnmatched(hide);
            onHideUnmatchedChanged.accept(hide);
        });
        narrowedBadge.getStyleClass().add("project-map-narrowed-badge");
        narrowedBadge.setMinWidth(Region.USE_PREF_SIZE);
        narrowedBadge.setTooltip(new Tooltip(tr("project.map.narrowed.help")));
        narrowedBadge.setAccessibleHelp(tr("project.map.narrowed.help"));
        showNarrowed(0);
        surface.setOnNarrowedChanged(this::showNarrowed);
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
                hideUnmatched,
                narrowedBadge,
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
        host.getStyleClass().add("project-map-host");
        // Cards sit on the canvas and may reach past its edges (dragged, panned or zoomed there): the part
        // outside is not drawn, and takes no clicks, over the filter bar above or anything else around.
        Rectangle hostClip = new Rectangle();
        hostClip.widthProperty().bind(host.widthProperty());
        hostClip.heightProperty().bind(host.heightProperty());
        host.setClip(hostClip);
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
        for (String id : List.of(
                "click",
                "chevron",
                "preview",
                "structure",
                "symbol",
                "wheel",
                "panWheel",
                "dragCanvas",
                "dragHeader")) {
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

    void setRoot(@Nullable Path root) {
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
        structure.clear();
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
    private record RootMemory(
            Set<Path> expanded,
            Map<Path, Integer> limits,
            @Nullable Path selected) {}

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

    void setPreviewMarkerActions(ProjectMapPreview.@Nullable MarkerActions actions) {
        previewMarkerActions = actions;
        previews.values().forEach(preview -> preview.setMarkerActions(actions));
    }

    void setNotePreviewActions(ProjectPanel.MarkerActions actions) {
        notePreviewActions = actions;
        previews.values().forEach(this::updateCardNotes);
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
        structure.pathRenamed(
                from.toAbsolutePath().normalize(), to.toAbsolutePath().normalize());
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
        // Cards hidden by "Hide unmatched" or "Hide all open Personal Notes" are not on screen: leave them be.
        List<ProjectMapPreview> cards =
                previews.values().stream().filter(ProjectMapPreview::isVisible).toList();
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

    /** Restores "Hide unmatched" and reports later changes to {@code callback}. */
    void setRememberedHideUnmatched(boolean hide, Consumer<Boolean> callback) {
        onHideUnmatchedChanged = ignored -> {};
        hideUnmatched.setSelected(hide);
        surface.setHideUnmatched(hide);
        onHideUnmatchedChanged = callback == null ? ignored -> {} : callback;
    }

    /** The badge beside "Hide unmatched": how many rows are off the map, shown only while some are. */
    private void showNarrowed(int count) {
        boolean any = count > 0;
        narrowedBadge.setText(any ? tr("project.map.narrowed", count) : "");
        narrowedBadge.setVisible(any);
        narrowedBadge.setManaged(any);
    }

    private void navigationChanged() {
        onNavigationChanged.accept(keepZoomOnOpen.isSelected(), focusNewColumn.isSelected());
    }

    void setContextMenuFactory(Function<ProjectMapModel.Entry, ContextMenu> factory) {
        // A "+N more" or stub row is not a file: it has no file-management menu. A declaration has its own.
        surface.setContextMenuFactory(
                factory == null
                        ? null
                        : entry -> entry.isPlaceholder()
                                ? null
                                : entry.isSymbol() ? symbolMenu(entry) : factory.apply(entry));
    }

    /** A declaration row's menu: its card, the file opened at it, the file opened narrowed to its block. */
    private ContextMenu symbolMenu(ProjectMapModel.Entry entry) {
        ProjectMapModel.Symbol symbol = entry.symbol();
        ContextMenu menu = new ContextMenu();
        if (symbol == null) {
            return menu;
        }
        MenuItem preview = new MenuItem(tr("project.map.structure.preview"));
        preview.setOnAction(event -> previewSymbol(entry));
        MenuItem openAt = new MenuItem(tr("project.map.structure.openAt"));
        openAt.setOnAction(event -> onOpenAt.accept(symbol.file(), symbol.line()));
        MenuItem openNarrowed = new MenuItem(tr("project.map.preview.openNarrowed"));
        openNarrowed.setOnAction(event -> onOpenNarrowed.accept(
                symbol.file(),
                symbol.block() != null
                        ? symbol.block()
                        : new com.editora.text.FoldRegions.Region(symbol.line(), symbol.endLine())));
        menu.getItems().setAll(preview, openAt, openNarrowed);
        return menu;
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
                || previews.isEmpty() && !structure.hasOpenColumns()
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
        structure.dispose();
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
            public @Nullable Rendered render(double pageWidth, double pageHeight) {
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
                () -> requested != generation.get(),
                // A search opens exactly the folders that lead to its matches: no chain opens on its own.
                compactChainsOption.isSelected() && !searching());
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
        Set<Path> loadedFiles = new HashSet<>();
        for (ProjectMapModel.Entry entry : loaded.entries()) {
            if (entry.isFile()) {
                loadedFiles.add(entry.path());
            }
        }
        structure.prune(loadedFiles, symbolCardFiles());
        showRows();
        structure.refresh(symbolCardFiles()); // a file outlined before may have changed meanwhile
        setOutputEnabled(!loaded.entries().isEmpty());
        if (pendingSelection != null) {
            if (loadedPaths.contains(pendingSelection) || surface.contains(pendingSelection)) {
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
    private void finishLoadMore(ProjectMapModel.Snapshot loaded, Set<Path> loadedPaths, @Nullable Path selectedBefore) {
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
        Path moreRow = directory
                .resolve(ProjectMapModel.PLACEHOLDER_NAME)
                .toAbsolutePath()
                .normalize();
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
        if (entry.isSymbol()) {
            previewSymbol(entry);
            return;
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

    private void closeDuringSearch(@Nullable Path directory) {
        searchOpened.removeIf(path -> path.startsWith(directory));
        searchClosed.removeIf(path -> path.startsWith(directory));
        searchClosed.add(directory);
    }

    /** Raises one truncated directory's row limit by a chunk; the overall limit still applies. */
    private void loadMore(@Nullable Path directory) {
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
        if (structure.isOpen(normalized)) {
            closeStructure(normalized);
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

    /**
     * The Structure icon of a file row, or the chevron of a declaration that holds others: opens or closes its
     * structure column. Nothing is listed again; the rows come from the file's outline.
     */
    private void toggleStructure(ProjectMapModel.Entry entry) {
        if (structure.isOpen(entry.path())) {
            closeStructure(entry.path());
            return;
        }
        if (structure.toggle(entry)) {
            showRows();
        }
    }

    /** Closes a structure column and the columns below it; a selection inside it moves to its row. */
    private void closeStructure(Path path) {
        structure.close(path);
        if (surface.selectionBelow(path)) {
            surface.select(path);
        }
        showRows(); // the cards of its declarations close with it (closeOrphanedCards)
    }

    /**
     * Hands the surface the last listing plus the open structure columns. Nothing is read: a structure column
     * opening or an outline arriving needs no new listing.
     */
    private void showRows() {
        List<ProjectMapModel.Entry> rows = structure.withStructure(snapshot.entries());
        Set<Path> open = new HashSet<>(snapshot.loadedDirectories());
        open.addAll(structure.openAmong(rows));
        surface.setEntries(rows, open);
        updateNavigation();
    }

    /** An outline arrived or changed: the columns are redrawn and every declaration card follows its lines. */
    private void structureChanged() {
        if (disposed) {
            return;
        }
        showRows();
        for (Map.Entry<Path, ProjectMapPreview> entry : List.copyOf(previews.entrySet())) {
            if (!ProjectMapStructure.isSymbolPath(entry.getKey())) {
                continue;
            }
            ProjectMapStructure.Node node = structure.node(entry.getKey());
            ProjectMapPreview card = entry.getValue();
            Path file = structure.fileOf(entry.getKey());
            if (node == null) {
                if (file != null && structure.hasOutline(file)) {
                    card.setSymbolMissing(true); // keeps its last lines: they may be the last copy to read
                }
                continue;
            }
            card.setSymbolMissing(false);
            card.setRange(rangeOf(node.symbol()));
            updateCardNotes(card); // the declaration's lines may hold other notes now
        }
    }

    private static ProjectMapPreview.Range rangeOf(ProjectMapModel.Symbol symbol) {
        return new ProjectMapPreview.Range(symbol.label(), symbol.line(), symbol.startLine(), symbol.endLine());
    }

    /** The files a declaration card is open for: their outlines are kept to follow the cards' lines. */
    private Set<Path> symbolCardFiles() {
        Set<Path> files = new HashSet<>();
        for (Path key : previews.keySet()) {
            if (ProjectMapStructure.isSymbolPath(key)) {
                Path file = structure.fileOf(key);
                if (file != null) {
                    files.add(file);
                }
            }
        }
        return files;
    }

    /**
     * A declaration row: opens (or focuses) the card of its lines. The card reads and colours the whole file
     * and shows the declaration's block with its doc comment, numbered as the file numbers it.
     */
    private void previewSymbol(ProjectMapModel.Entry entry) {
        ProjectMapModel.Symbol symbol = entry.symbol();
        if (symbol == null) {
            return;
        }
        Path key = entry.path();
        ProjectMapPreview existing = previews.get(key);
        if (existing != null) {
            touchCard(existing, true);
            existing.focusContent();
            return;
        }
        Path file = symbol.file();
        ProjectMapPreview preview = createPreview(key);
        preview.setOnOpenAt((path, line) -> onOpenAt.accept(path, line));
        preview.setOnOpenNarrowed(() -> {
            ProjectMapStructure.Node node = structure.node(key);
            ProjectMapModel.Symbol current = node == null ? symbol : node.symbol();
            com.editora.text.FoldRegions.Region block = current.block();
            onOpenNarrowed.accept(
                    file,
                    block != null ? block : new com.editora.text.FoldRegions.Region(current.line(), current.endLine()));
        });
        preview.showRange(
                file,
                rangeOf(symbol),
                previewContentFor(file),
                (width, height, parentWidth, parentHeight) -> previewPlacement(
                        preview,
                        key,
                        width,
                        height,
                        ProjectMapPreview.MIN_WIDTH,
                        ProjectMapPreview.MIN_HEIGHT,
                        parentWidth,
                        parentHeight));
        updateCardNotes(preview);
        scheduleCardRefresh();
    }

    /**
     * Where a declaration card's Open and Open Narrowed go: the file opened at a line, and the file opened
     * narrowed to a block (both 0-based lines, the file's own).
     */
    void setOpenActions(
            java.util.function.BiConsumer<Path, Integer> openAt,
            java.util.function.BiConsumer<Path, com.editora.text.FoldRegions.Region> openNarrowed) {
        onOpenAt = openAt == null ? (file, line) -> {} : openAt;
        onOpenNarrowed = openNarrowed == null ? (file, block) -> {} : openNarrowed;
    }

    /** The language server's outline of an open buffer, when the editor has one (see ProjectMapStructure). */
    void setServerSymbols(Function<Path, java.util.@Nullable List<com.editora.lsp.SymbolNode>> symbols) {
        structure.setServerSymbols(symbols);
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
        if (entry == null || !entry.isFile()) {
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
        updateCardNotes(preview);
        scheduleCardRefresh(); // starts following the buffer when the card mirrors one
    }

    private ProjectMapPreview.@Nullable Content previewContentFor(Path path) {
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
            updateCardVisibility();
            touchCard(existing, true);
            existing.focusContent();
            return;
        }
        List<PersonalNote> values = notePreviewActions.personalNotes(selected);
        if (values.isEmpty()) {
            return;
        }
        makeRoomForCard();
        ProjectMapNotePreview preview = new ProjectMapNotePreview((note, body) -> {
            ProjectPanel.MarkerActions actions = notePreviewActions;
            if (actions != null) {
                actions.updatePersonalNote(selected, note, body);
            }
        });
        preview.setOnClose(() -> closeNotePreview(preview));
        preview.setOnActivate(() -> touchCard(preview, true));
        preview.setOnTouch(() -> touchCard(preview, false));
        preview.setOnEscape(() -> {
            closeNotePreview(preview);
            surface.requestFocus();
        });
        preview.setOnBlankRejected(() -> onStatus.accept(tr("status.projectMap.noteBlankRestored")));
        preview.setMapScale(surface.cardZoom());
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
        updateCardVisibility();
    }

    private ProjectMapPreview createPreview(Path path) {
        makeRoomForCard();
        ProjectMapPreview preview = new ProjectMapPreview(onOpenFile);
        preview.setMapScale(surface.cardZoom());
        preview.setMarkerActions(previewMarkerActions);
        preview.setOnClose(() -> closePreview(preview));
        preview.setOnActivate(() -> touchCard(preview, true));
        preview.setOnTouch(() -> touchCard(preview, false));
        preview.setOnEscape(() -> {
            closePreview(preview);
            surface.requestFocus();
        });
        preview.setOnNotesToggled(shown -> showCardNotes(preview, shown));
        // A sticky note's connector meets its line in the text, which scrolls under it.
        preview.editor().estimatedScrollYProperty().addListener((obs, old, value) -> {
            if (stickyNotes.containsKey(preview)) {
                repaintPreviewConnectors();
            }
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
            ProjectMapStickyNotes notes = card instanceof ProjectMapPreview code ? stickyNotes.get(code) : null;
            if (notes != null) {
                notes.raise(); // a card's sticky notes stay on it
            }
        }
    }

    /**
     * Counts, on a code card's Notes button, the Personal Notes of what it shows — and brings the sticky notes it
     * has up, if any, in line with the store.
     */
    private void updateCardNotes(ProjectMapPreview card) {
        ProjectPanel.MarkerActions actions = notePreviewActions;
        boolean enabled = actions != null && actions.personalNotesEnabled();
        List<PersonalNote> notes = cardNotes(card);
        card.setNoteCount(notes.size(), enabled); // may bring the notes up: one the user asked for arrived
        ProjectMapStickyNotes stuck = stickyNotes.get(card);
        if (stuck != null && !notes.isEmpty()) {
            stuck.refresh(notes);
        }
    }

    private List<PersonalNote> cardNotes(ProjectMapPreview card) {
        ProjectPanel.MarkerActions actions = notePreviewActions;
        Path file = card.path();
        if (actions == null || !actions.personalNotesEnabled() || file == null) {
            return List.of();
        }
        try {
            return ProjectMapStickyNotes.notesFor(actions.personalNotes(file), card.range());
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    /** The Notes button: sticks the card's notes beside it, or takes them off. */
    private void showCardNotes(ProjectMapPreview card, boolean shown) {
        if (!shown) {
            ProjectMapStickyNotes stuck = stickyNotes.remove(card);
            if (stuck != null) {
                stuck.dispose();
            }
            return;
        }
        ProjectMapStickyNotes stuck = stickyNotes.computeIfAbsent(
                card,
                ignored -> new ProjectMapStickyNotes(
                        card,
                        canvasHost,
                        (note, body) -> {
                            ProjectPanel.MarkerActions actions = notePreviewActions;
                            Path file = card.path();
                            if (actions != null && file != null) {
                                actions.updatePersonalNote(file, note, body);
                            }
                        },
                        this::repaintPreviewConnectors,
                        () -> onStatus.accept(tr("status.projectMap.noteBlankRestored")),
                        () -> touchCard(card, false)));
        stuck.setHidden(hideOpenNotes.isSelected());
        stuck.show(cardNotes(card));
        touchCard(card, true);
    }

    private void closeCardNotes(ProjectMapPreview card) {
        ProjectMapStickyNotes stuck = stickyNotes.remove(card);
        if (stuck != null) {
            stuck.dispose();
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

    /**
     * A card stays open but out of sight while "Hide unmatched" has taken its file's row off the map, and comes
     * back with the row; a note card, and a code card's sticky notes, are also hidden by "Hide all open Personal
     * Notes".
     */
    private void updateCardVisibility() {
        for (Map.Entry<Path, ProjectMapPreview> entry : previews.entrySet()) {
            Path key = entry.getKey();
            Path file = ProjectMapStructure.isSymbolPath(key) ? structure.fileOf(key) : key;
            entry.getValue().setVisible(file == null || !surface.narrowsAway(file));
        }
        boolean notes = !hideOpenNotes.isSelected();
        notePreviews.forEach((path, card) -> card.setVisible(notes && !surface.narrowsAway(path)));
        stickyNotes.values().forEach(stuck -> stuck.setHidden(!notes)); // a hidden card hides its own as well
        repaintPreviewConnectors();
    }

    private void closePreview(ProjectMapPreview preview) {
        if (preview == null) {
            return;
        }
        previews.entrySet().removeIf(entry -> entry.getValue() == preview);
        cardTouches.remove(preview);
        closeCardNotes(preview);
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
        List.copyOf(stickyNotes.values()).forEach(ProjectMapStickyNotes::dispose);
        stickyNotes.clear();
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
        if (disposed
                || getScene() == null
                || previews.isEmpty() && notePreviews.isEmpty() && !structure.hasOpenColumns()) {
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
                Path file = card.path();
                card.refresh(previewContentFor(file != null ? file : entry.getKey()));
            }
            mirrors |= card.showsOpenBuffer();
        }
        // Structure columns follow their file too: an open buffer's edits, or a closed file rewritten on disk.
        if (structure.hasOpenColumns() || !symbolCardFiles().isEmpty()) {
            structure.refresh(symbolCardFiles());
            mirrors |= structure.openFiles().stream().anyMatch(file -> safeTest(isOpen, file));
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
        List.copyOf(previews.values()).forEach(this::updateCardNotes);
        ProjectPanel.MarkerActions actions = notePreviewActions;
        boolean enabled = actions != null && actions.personalNotesEnabled();
        for (Map.Entry<Path, ProjectMapNotePreview> entry : List.copyOf(notePreviews.entrySet())) {
            List<PersonalNote> values;
            try {
                values = enabled && actions != null ? actions.personalNotes(entry.getKey()) : List.of();
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
     * cards already open — of those in view; one dragged or panned off the panel takes no room. The map
     * itself is never panned to make room. Sizes in and out are the card's own, unzoomed; the room is found
     * for the card as drawn at its zoom.
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
        double scale = cardScale(preview);
        ProjectMapPreview.Placement drawn = drawnPlacement(
                preview,
                path,
                width * scale,
                height * scale,
                minimumWidth * scale,
                minimumHeight * scale,
                parentWidth,
                parentHeight);
        return new ProjectMapPreview.Placement(
                drawn.x(), drawn.y(), drawn.width() / scale, drawn.height() / scale, drawn.growLeft());
    }

    /** {@link #previewPlacement} in panel pixels: every size here is a card as drawn. */
    private ProjectMapPreview.Placement drawnPlacement(
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
            double scale = cardScale(other);
            if (x < other.getLayoutX() + other.getWidth() * scale + PREVIEW_CASCADE
                    && x + width + PREVIEW_CASCADE > other.getLayoutX()
                    && y < other.getLayoutY() + other.getHeight() * scale + PREVIEW_CASCADE
                    && y + height + PREVIEW_CASCADE > other.getLayoutY()) {
                return true;
            }
        }
        return false;
    }

    private static double clampPreview(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /** The map zoom a card is drawn at: its width and height are unzoomed, its corner is in panel pixels. */
    private static double cardScale(Region card) {
        if (card instanceof ProjectMapPreview code) {
            return code.mapScale();
        }
        return card instanceof ProjectMapNotePreview note ? note.mapScale() : 1;
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
        g.setFill(surface.noteColor());
        stickyNotes.values().forEach(notes -> notes.drawConnectors(g));
        g.setGlobalAlpha(1);
    }

    private PreviewConnector drawPreviewConnector(GraphicsContext g, MapSurface.NodeBox anchor, Region preview) {
        double x1;
        double y1;
        double x2;
        double y2;
        double left = preview.getLayoutX();
        double top = preview.getLayoutY();
        double width = preview.getWidth() * cardScale(preview);
        double height = preview.getHeight() * cardScale(preview);
        boolean horizontal = flowFilter.getValue() == FlowDirection.LEFT_TO_RIGHT
                || flowFilter.getValue() == FlowDirection.RIGHT_TO_LEFT;
        if (horizontal) {
            boolean previewAfter = left + width / 2 >= anchor.x() + anchor.width() / 2;
            x1 = previewAfter ? anchor.x() + anchor.width() : anchor.x();
            y1 = anchor.y() + anchor.height() / 2;
            x2 = previewAfter ? left : left + width;
            y2 = clampPreview(y1, top, top + height);
            double control = Math.max(18, Math.abs(x2 - x1) * 0.45);
            g.beginPath();
            g.moveTo(x1, y1);
            g.bezierCurveTo(
                    x1 + (previewAfter ? control : -control), y1, x2 + (previewAfter ? -control : control), y2, x2, y2);
        } else {
            boolean previewAfter = top + height / 2 >= anchor.y() + anchor.height() / 2;
            x1 = anchor.x() + anchor.width() / 2;
            y1 = previewAfter ? anchor.y() + anchor.height() : anchor.y();
            x2 = clampPreview(x1, left, left + width);
            y2 = previewAfter ? top : top + height;
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
        if (ProjectMapStructure.isSymbolPath(normalized)) {
            // A declaration whose column was closed: its file is what can be revealed (a file is never listed).
            Path file = normalized;
            while (ProjectMapStructure.isSymbolPath(file) && file.getParent() != null) {
                file = file.getParent();
            }
            revealPath(file);
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
        javafx.collections.ObservableList<Node> children = breadcrumbs.getChildren();
        // The surface may still hold the previous root's selection, which can be on another connection.
        if (selected == null
                || root == null
                || !com.editora.config.PathKeys.sameFileSystem(selected, root)
                || !selected.startsWith(root)) {
            children.clear();
            crumbTrail.clear();
            crumbButtons.clear();
            return;
        }
        List<Path> trail = new ArrayList<>();
        for (Path current = selected; current != null && current.startsWith(root); current = current.getParent()) {
            trail.add(current);
            if (current.equals(root)) {
                break;
            }
        }
        List<Path> paths = trail.reversed();
        // Only the crumbs after the part the old and new trails share are replaced: an arrow key in one column
        // changes the last crumb, not the whole row of buttons (Map review D15).
        children.removeAll(List.of(breadcrumbEllipsis, breadcrumbEllipsisSeparator));
        int kept = 0;
        while (kept < paths.size()
                && kept < crumbTrail.size()
                && com.editora.config.PathKeys.samePath(crumbTrail.get(kept), paths.get(kept))
                && crumbButtons.get(kept).getText().equals(crumbLabel(paths.get(kept)))) {
            kept++;
        }
        if (kept < crumbTrail.size() || kept < paths.size() || children.size() != crumbTrail.size() * 2 - 1) {
            // crumb, separator, crumb, … crumb: the separator after the last kept crumb stays when one follows.
            boolean separatorKept = kept > 0 && kept < crumbTrail.size() && kept < paths.size();
            int keptNodes = Math.min(children.size(), kept == 0 ? 0 : kept * 2 - (separatorKept ? 0 : 1));
            children.remove(keptNodes, children.size());
            crumbTrail.subList(kept, crumbTrail.size()).clear();
            crumbButtons.subList(kept, crumbButtons.size()).clear();
            List<Node> added = new ArrayList<>();
            for (int index = kept; index < paths.size(); index++) {
                if (index > 0 && !(index == kept && separatorKept)) {
                    Label separator = new Label("›");
                    separator.getStyleClass().add("project-map-breadcrumb-separator");
                    separator.setMinWidth(Region.USE_PREF_SIZE);
                    added.add(separator);
                }
                Button crumb = breadcrumbButton(paths.get(index));
                crumbTrail.add(paths.get(index));
                crumbButtons.add(crumb);
                added.add(crumb);
            }
            children.addAll(added);
        }
        fitBreadcrumbs();
    }

    /** One crumb; its tooltip (the full path) is made the first time the pointer rests on it. */
    private Button breadcrumbButton(Path path) {
        Button crumb = new Button(crumbLabel(path));
        crumb.getStyleClass().add("project-map-breadcrumb");
        crumb.setOnMouseEntered(event -> {
            if (crumb.getTooltip() == null) {
                crumb.setTooltip(new Tooltip(path.toString()));
            }
        });
        crumb.setOnAction(event -> revealPath(path));
        return crumb;
    }

    /** A breadcrumb's text: the folder or file name, or a declaration's label. */
    private String crumbLabel(Path path) {
        if (ProjectMapStructure.isSymbolPath(path)) {
            ProjectMapStructure.Node node = structure.node(path);
            if (node != null) {
                return node.symbol().label();
            }
        }
        Path fileName = path.getFileName();
        return fileName == null ? path.toString() : fileName.toString();
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
        /** The widest a column's rows grow for a long name; the name is elided in the middle beyond it. */
        private static final double MAX_NODE_WIDTH = 360;

        private static final String FALLBACK_FONT_FAMILY = "System";
        /** Between the folder names in a compacted column's title: {@code src / main / java}. */
        private static final String CHAIN_SEPARATOR = " / ";

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
        /** Strip before the preview eye of a file that has an outline: its Structure icon. */
        private static final double STRUCTURE_ZONE = 24;
        /** Room for a declaration row's line number. */
        private static final double SYMBOL_LINE_WIDTH = 56;
        /** Width reserved in a row for one bookmark or Personal Note badge. */
        private static final double MARKER_WIDTH = 13;

        private static final double VIEW_MARGIN = 20;
        /** Width of the hidden-files checkbox once its label has been dropped for lack of room. */
        private static final double CHECK_ONLY_WIDTH = 20;
        /** Style class that turns a rasterised row glyph into on-accent ink (see app.css). */
        private static final String ON_ACCENT_ICON_CLASS = "project-map-icon-on-accent";

        private static final double COLUMN_TOP_INSET = 5;
        private static final double COLUMN_BOTTOM_PADDING = 12;
        /** The line under a column's rows that says how many "Hide unmatched" took out. */
        private static final double NARROWED_FOOTER_HEIGHT = 16;

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
        /** Rows that do not match an active filter keep this much of their fill and border. */
        private static final double DIMMED_ALPHA = 0.38;
        /** …this much of their icons and marks… */
        private static final double DIMMED_MARK_ALPHA = 0.7;
        /** …and their labels fade no further than this contrast with the row (Map review B19). */
        private static final double FADED_LABEL_CONTRAST = 3.5;
        /** How far a Structure column's card, and its declaration rows, lean toward the structure tint. */
        private static final double STRUCTURE_COLUMN_TINT = 0.16;

        private static final double STRUCTURE_ROW_TINT = 0.07;

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
                + " -state-amber: #9a6700; -state-olive: #4f5c10; -state-violet: #6538c2;"
                + " -color-accent-fg: #0969da; -color-success-fg: #1a7f37; -color-danger-fg: #d1242f;"
                + " -color-fg-muted: #59636e;";

        private final Canvas liveCanvas = new Canvas(1, 1);
        /** What {@link #paint} draws on: the live Canvas, or an off-scene one while output is rendered. */
        private Canvas canvas = liveCanvas;
        /** Non-null only while print/PDF output is rendered: the light colours that replace the theme's. */
        private @Nullable Map<Rectangle, Color> outputPalette;

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
        /** The tint that sets a file's Structure columns (and their rows) apart from the folder columns. */
        private final Rectangle structureProbe = probe("project-map-probe-structure");

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
                paintNow();
            }
        };

        private List<ProjectMapModel.Entry> entries = List.of();
        /** {@link #entries} by path, for the rows a column title or a symbol's emphasis looks up. */
        private Map<Path, ProjectMapModel.Entry> entryByPath = Map.of();

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
        private @Nullable Path selected;
        private @Nullable Path hovered;
        /** The part of the hovered row under the pointer, when it is one that acts differently from the row. */
        private Affordance hoveredAffordance = Affordance.NONE;

        private java.util.function.Consumer<Boolean> onNoMatchesChanged = none -> {};
        private boolean noMatches;
        /** "Hide unmatched": rows the filters would fade are taken off the map instead. */
        private boolean hideUnmatched;

        private java.util.function.IntConsumer onNarrowedChanged = count -> {};
        /** Rows the last paint left out for "Hide unmatched", over every column on the map. */
        private int narrowedRows;
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
        private Consumer<ProjectMapModel.Entry> onStructure = entry -> {};
        private Consumer<Path> onNotesPreview = path -> {};
        private Consumer<Path> onSelectionChanged = path -> {};
        private Function<ProjectMapModel.Entry, @Nullable ContextMenu> contextMenuFactory = entry -> null;
        private Runnable onZoomChanged = () -> {};
        private double zoom = 1.0;
        private boolean keepZoomOnColumnOpen = true;
        private boolean focusNewColumn = true;
        private double offsetX;
        private double offsetY;
        /** The viewport the open cards were last moved for; NaN until the first paint. */
        private double cardsOffsetX = Double.NaN;

        private double cardsOffsetY = Double.NaN;
        private double cardsZoom = Double.NaN;
        private double pressX;
        private double pressY;
        private double pressOffsetX;
        private double pressOffsetY;
        private double columnPressOffsetX;
        private double columnPressOffsetY;
        private ProjectMapModel.@Nullable ColumnId draggedColumn;
        private @Nullable OverviewBox overviewBox;
        private @Nullable ContextMenu activeContextMenu;
        private @Nullable Scene dismissScene;
        private @Nullable EventHandler<MouseEvent> dismissFilter;
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
        private final Map<String, String> elidedTitles = new HashMap<>();
        private final String columnNoMatches = tr("project.map.column.noMatches");
        private final String columnFilterPrompt = tr("project.map.column.filter");
        private final String columnFilterShortPrompt = tr("project.map.column.filterShort");
        private int lastPaintedConnectorCount;
        /** Canvas draws so far: at most one per pulse (Map review D5). */
        private long completedPaints;
        /** The last world layout and what it was computed from (see {@link #worldLayout}). */
        private @Nullable WorldLayout cachedLayout;

        private @Nullable LayoutKey cachedLayoutKey;
        /** The world layout the current boxes were projected from; null while the map is empty. */
        private @Nullable WorldLayout projected;

        private @Nullable WorldLayout cachedFirstColumnsFor;
        /** World layouts computed so far: a pan, a hover or a new selection computes none. */
        private long layoutsComputed;
        /** "Compact folder chains": a run of single-child folders is drawn as one column (Map review E4). */
        private boolean compactChains = true;
        /** A pan or zoom moved the map since the boxes were last projected. */
        private boolean boxesStale;
        /** Inside {@link #layoutChildren}: a draw asked for now is left to the pulse. */
        private boolean laying;
        /** The columns that hang from no row: the root's children (the root is their title). */
        private Set<ProjectMapModel.ColumnId> firstColumns = Set.of();
        /** An empty Text the UI font is resolved on by CSS, as the colours are on the probes (Map review B13). */
        private final Text fontProbe = new Text();

        private final Map<FontKey, Font> fonts = new HashMap<>();
        private @Nullable String measuredFamily;
        /** The ground a faded row's label is drawn on, while {@link #drawNode} draws one; null otherwise. */
        private @Nullable Color dimmedGround;

        private final Map<List<Color>, Color> fadedInks = new HashMap<>();

        MapSurface() {
            // The project-tree class supplies the same per-editor-theme folder/file looked-up colors used
            // by PathCell. It has no TreeView skin effect on this Region.
            getStyleClass().addAll("project-map-surface", "project-tree");
            setClip(viewportClip);
            viewportClip.widthProperty().bind(widthProperty());
            viewportClip.heightProperty().bind(heightProperty());
            textMeasurer.setFont(Font.font(FALLBACK_FONT_FAMILY, FontWeight.SEMI_BOLD, 12));
            iconRasterizer.setManaged(false);
            iconRasterizer.setMouseTransparent(true);
            iconRasterizer.setMinSize(ICON_SIZE, ICON_SIZE);
            iconRasterizer.setPrefSize(ICON_SIZE, ICON_SIZE);
            iconRasterizer.setMaxSize(ICON_SIZE, ICON_SIZE);
            iconRasterizer.resize(ICON_SIZE, ICON_SIZE);
            iconRasterizer.relocate(-ICON_SIZE * 2, -ICON_SIZE * 2);
            fontProbe.getStyleClass().add("project-map-probe-font");
            fontProbe.setManaged(false);
            fontProbe.setMouseTransparent(true);
            fontProbe.fontProperty().addListener((obs, old, value) -> repaint());
            getChildren()
                    .addAll(
                            canvas,
                            iconRasterizer,
                            fontProbe,
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
                            violetProbe,
                            structureProbe);
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
                    violetProbe,
                    structureProbe)) {
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

        void setOnStructure(Consumer<ProjectMapModel.Entry> callback) {
            onStructure = callback == null ? entry -> {} : callback;
        }

        void setOnNotesPreview(Consumer<Path> onNotesPreview) {
            this.onNotesPreview = onNotesPreview == null ? path -> {} : onNotesPreview;
        }

        void setOnSelectionChanged(Consumer<Path> callback) {
            onSelectionChanged = callback == null ? path -> {} : callback;
        }

        void setContextMenuFactory(@Nullable Function<ProjectMapModel.Entry, @Nullable ContextMenu> factory) {
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

        @Nullable
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
            Map<Path, ProjectMapModel.Entry> byPath = new HashMap<>();
            for (ProjectMapModel.Entry entry : this.entries) {
                byPath.putIfAbsent(entry.path(), entry);
            }
            entryByPath = byPath;
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

        void setCompactChains(boolean compact) {
            if (compactChains != compact) {
                compactChains = compact;
                repaint();
            }
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

        void setHideUnmatched(boolean hide) {
            if (hide != hideUnmatched) {
                hideUnmatched = hide;
                repaint();
            }
        }

        boolean isHideUnmatched() {
            return hideUnmatched;
        }

        /** Whether "Hide unmatched" takes {@code path}'s row off the map: the filters are on and would fade it. */
        boolean narrowsAway(Path path) {
            return hideUnmatched && filters != null && filters.active() && !emphasized.contains(path);
        }

        /** Told how many rows "Hide unmatched" takes off the map, whenever that number changes. */
        void setOnNarrowedChanged(java.util.function.IntConsumer callback) {
            onNarrowedChanged = callback == null ? count -> {} : callback;
        }

        /** Rows "Hide unmatched" took off the map at the last paint; 0 while nothing is hidden. */
        int narrowedRows() {
            return narrowedRows;
        }

        /** True while a query or filter chip is active and no loaded row matches it. */
        boolean hasNoMatches() {
            return noMatches;
        }

        private @Nullable ColumnBox columnBox(ProjectMapModel.@Nullable ColumnId id) {
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

        void setSelected(@Nullable Path selected) {
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
            boxesStale = false;
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

        /**
         * Something the map shows changed. The hit boxes, the overview frame and the column controls follow at
         * once, so whatever reads them next sees the new layout; the Canvas itself is drawn at the next pulse,
         * once for any number of changes in between (Map review D5). {@link #paintNow} draws at once.
         */
        void repaint() {
            if (painting || getWidth() <= 0 || getHeight() <= 0) {
                return;
            }
            boolean layoutBefore = cachedLayout != null;
            long layoutsBefore = layoutsComputed;
            painting = true;
            try {
                boxesStale = false;
                projectLayout();
                syncHover(); // the row under a resting pointer changed with the rows
                moveCardsWithViewport();
                if (!layoutBefore || layoutsComputed != layoutsBefore) {
                    updateCardVisibility(); // what "Hide unmatched" leaves may have changed
                }
            } finally {
                painting = false;
            }
            scheduleDraw();
        }

        /** Projects and draws now: the pulse's draw, Print/PDF, and tests that read the Canvas. */
        void paintNow() {
            if (painting || getWidth() <= 0 || getHeight() <= 0) {
                return;
            }
            viewportRepaintPending = false;
            viewportRepaintTimer.stop();
            painting = true;
            try {
                boxesStale = false;
                projectLayout();
                syncHover(); // the row under a resting pointer changed with the viewport
                drawCanvas();
                completedPaints++;
                moveCardsWithViewport();
                updateCardVisibility(); // repaints the connectors
            } finally {
                painting = false;
            }
        }

        /**
         * Asks for one draw: at the next pulse, or at the next layout pass if one comes first (a snapshot runs
         * one, so a snapshot always shows what was asked for).
         */
        private void scheduleDraw() {
            if (!viewportRepaintPending) {
                viewportRepaintPending = true;
                viewportRepaintTimer.start();
            }
            if (!laying) {
                setNeedsLayout(true); // this node only: its parents are not laid out again
            }
        }

        /**
         * Cards sit on the canvas: a pan or a zoom since the last paint carries each card's corner along with
         * the map under it, as it does the columns, and draws the card at the new zoom from that corner.
         */
        private void moveCardsWithViewport() {
            double fromX = cardsOffsetX;
            double fromY = cardsOffsetY;
            double fromZoom = cardsZoom;
            cardsOffsetX = offsetX;
            cardsOffsetY = offsetY;
            cardsZoom = zoom;
            if (Double.isNaN(fromZoom) || fromX == offsetX && fromY == offsetY && fromZoom == zoom) {
                return;
            }
            double ratio = zoom / fromZoom;
            List<Region> cards = new ArrayList<>(previews.values());
            cards.addAll(notePreviews.values());
            for (Region card : cards) {
                card.relocate(
                        offsetX + (card.getLayoutX() - fromX) * ratio, offsetY + (card.getLayoutY() - fromY) * ratio);
            }
            previews.values().forEach(card -> card.setMapScale(zoom));
            notePreviews.values().forEach(card -> card.setMapScale(zoom));
        }

        /** The zoom of the last paint, which the open cards are drawn at and a new card opens at. */
        double cardZoom() {
            return Double.isNaN(cardsZoom) ? zoom : cardsZoom;
        }

        /**
         * Coalesces continuous viewport gestures so they submit no more than one Canvas render per pulse. The
         * boxes are projected again when something reads them ({@link #flushPendingRepaint}) or at the pulse.
         */
        private void requestViewportRepaint() {
            if (getWidth() <= 0 || getHeight() <= 0) {
                return;
            }
            boxesStale = true;
            scheduleDraw();
        }

        /** World-space bounds of every laid-out column plus the paper margin, or null for an empty map. */
        private ProjectMapOutputPlan.@Nullable Box outputBounds() {
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
        ProjectMapOutput.@Nullable Rendered renderOutput(double pageWidth, double pageHeight) {
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

        private ProjectMapOutput.@Nullable Rendered renderOutputPages(double pageWidth, double pageHeight) {
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
            palette.put(structureProbe, Color.web("#8250df"));
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
                // The root (nothing chosen yet) or a folder of a compacted chain: its column's header stands
                // for it, so show where that column starts, as the first view does.
                ColumnBox header = headerFor(selected);
                if (header != null) {
                    panToColumnStart(pan, header.column().id());
                }
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
            // The first column holds the root's children (the root is its title, not a column of its own).
            ColumnBox first = columnBoxes.getFirst();
            if (mayShrink) {
                double span = isVerticalFlow() ? first.height() : first.width();
                double available = isVerticalFlow() ? viewBottom() - viewTop() : viewRight() - viewLeft();
                if (span > available) {
                    zoom = Math.max(MIN_AUTO_FIT_ZOOM, available / span);
                    repaint();
                    ColumnBox scaled = columnBox(first.column().id());
                    first = scaled != null ? scaled : columnBoxes.getFirst();
                }
            }
            double[] pan = new double[2];
            panToColumnStart(pan, first.column().id());
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
            if (outputPalette != null || painting) {
                // Rasterizing an icon lays the scene out mid-paint (and mid-output); the pending draw and the
                // first-view fit wait for the next pass instead of moving the map under the paint.
                return;
            }
            laying = true;
            try {
                double width = Math.max(1, getWidth());
                double height = Math.max(1, getHeight());
                boolean resized = liveCanvas.getWidth() != width || liveCanvas.getHeight() != height;
                liveCanvas.setWidth(width);
                liveCanvas.setHeight(height);
                if (resized || viewportRepaintPending || boxesStale || completedPaints == 0) {
                    paintNow();
                }
                fitIfPending();
            } finally {
                laying = false;
            }
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
                if (controls != null) { // listed from its keys just above
                    getChildren().removeAll(controls.filter(), controls.showHidden(), controls.pin(), controls.close());
                }
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
                close.setOnAction(event -> {
                    // A compacted column folds back into the row its chain starts at.
                    ColumnBox box = columnBox(id);
                    Path folder = box != null && box.column().compacted()
                            ? box.column().anchor()
                            : id.parent();
                    onCloseColumn.accept(folder);
                });

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
                if (entry.isPlaceholder() || entry.isSymbol()) {
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
            Set<Path> result = ProjectMapModel.emphasized(entries, direct, root);
            // A declaration is as prominent as its file: the filters are about files, not what is in them.
            for (ProjectMapModel.Entry entry : entries) {
                ProjectMapModel.Symbol symbol = entry.symbol();
                if (symbol != null && result.contains(symbol.file())) {
                    result.add(entry.path());
                }
            }
            emphasized = result;
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

        /**
         * Brings the hit boxes, the overview frame and the native column controls up to date with the current
         * rows, filters and viewport. The world layout behind them is reused while nothing it depends on
         * changed ({@link #worldLayout}); only the projection to the screen is redone, which is what a pan, a
         * hover or a new selection needs.
         */
        private void projectLayout() {
            boxes.clear();
            columnBoxes.clear();
            overviewBox = null;
            projected = null;
            if (entries.isEmpty()) {
                columnControls.values().forEach(controls -> {
                    controls.filter().setVisible(false);
                    controls.showHidden().setVisible(false);
                    controls.pin().setVisible(false);
                    controls.close().setVisible(false);
                });
                reportNoMatches(false);
                reportNarrowed(0);
                return;
            }
            WorldLayout world = worldLayout();
            for (ColumnBox column : world.columns()) {
                columnBoxes.add(new ColumnBox(
                        column.column(),
                        screenX(column.x()),
                        screenY(column.y()),
                        column.width() * zoom,
                        column.height() * zoom));
            }
            for (NodeBox row : world.rows()) {
                boxes.add(new NodeBox(
                        row.entry(), screenX(row.x()), screenY(row.y()), row.width() * zoom, row.height() * zoom));
            }
            projected = world;
            if (world != cachedFirstColumnsFor) {
                Set<ProjectMapModel.ColumnId> unanchored = new HashSet<>();
                for (int index = 0; index < world.columns().size(); index++) {
                    if (world.anchorRows().get(index) < 0) {
                        unanchored.add(world.columns().get(index).column().id());
                    }
                }
                firstColumns = Set.copyOf(unanchored);
                cachedFirstColumnsFor = world;
            }
            overviewBox = outputPaint ? null : overviewGeometry(canvas.getWidth(), canvas.getHeight());
            layoutColumnControls();
            reportNoMatches(filters != null && filters.active() && emphasized.isEmpty());
            reportNarrowed(world.narrowed());
        }

        /** The world layout for the current inputs: the cached one while they are unchanged (Map review D8). */
        private WorldLayout worldLayout() {
            // Column widths allow for the checkbox label, so it is measured before they are computed (it
            // changes once, when the controls are first styled).
            for (ColumnControls controls : columnControls.values()) {
                if (controls.showHidden().getContentDisplay() != ContentDisplay.GRAPHIC_ONLY) {
                    showHiddenLabelWidth(controls.showHidden());
                    break;
                }
            }
            boolean narrowing = hideUnmatched && filters != null && filters.active();
            Map<ProjectMapModel.ColumnId, javafx.geometry.Point2D> offsets = new HashMap<>();
            columnLayouts.forEach((id, layout) -> {
                if (layout.x != 0 || layout.y != 0) {
                    offsets.put(id, new javafx.geometry.Point2D(layout.x, layout.y));
                }
            });
            LayoutKey key = new LayoutKey(
                    entries,
                    Map.copyOf(columnQueries),
                    Map.copyOf(columnShowHidden),
                    narrowing ? emphasized : null,
                    flowDirection,
                    zoom,
                    outputPaint,
                    offsets,
                    showHiddenLabelWidth,
                    bookmarkedPaths,
                    notedPaths,
                    fontFamily(),
                    compactChains);
            WorldLayout cached = cachedLayout;
            if (cached != null && key.equals(cachedLayoutKey)) {
                return cached;
            }
            WorldLayout computed = computeWorldLayout(narrowing);
            cachedLayout = computed;
            cachedLayoutKey = key;
            layoutsComputed++;
            return computed;
        }

        /**
         * Lays the columns out in world units. The root is not a column of its own: its children are the first
         * column, titled with its name (Map review E5). A compacted column hangs from the first folder of its
         * chain (E4).
         */
        private WorldLayout computeWorldLayout(boolean narrowing) {
            List<ProjectMapModel.Column> columns = ProjectMapModel.columnsById(
                    entries, columnQueries, columnShowHidden, narrowing ? emphasized : null, compactChains);
            int narrowedTotal = 0;
            for (ProjectMapModel.Column column : columns) {
                narrowedTotal += column.narrowed();
            }
            // Every row of a column counts for its width, filtered or not, so a filter does not make it jump.
            Map<ProjectMapModel.ColumnId, List<ProjectMapModel.Entry>> rowsByColumn = new HashMap<>();
            for (ProjectMapModel.Entry entry : entries) {
                rowsByColumn
                        .computeIfAbsent(columnId(entry), ignored -> new ArrayList<>())
                        .add(entry);
            }
            List<ColumnBox> columnList = new ArrayList<>(columns.size());
            List<NodeBox> rowList = new ArrayList<>();
            List<Integer> anchorRows = new ArrayList<>(columns.size());
            List<Integer> anchorColumns = new ArrayList<>(columns.size());
            Map<Path, Integer> rowIndex = new HashMap<>();
            Map<ProjectMapModel.ColumnId, Integer> columnIndex = new HashMap<>();
            Map<ProjectMapModel.ColumnId, Integer> levels = new HashMap<>();
            Map<Integer, Double> nextCrossEdge = new HashMap<>();
            // Along the flow every level gets a band of its own, starting beyond the widest card of the
            // level before it; a card that only started beyond its own parent could run through a wider
            // sibling of that parent. Manual offsets are left out of the band (flowShift), so dragging
            // one column does not move the other branches. A level is a column's distance from the first
            // column, which a compacted chain keeps at one step whatever its depth on disk.
            Map<Integer, Double> flowBand = new HashMap<>();
            Map<ProjectMapModel.ColumnId, Double> flowShift = new HashMap<>();
            boolean verticalFlow = isVerticalFlow();
            double direction = isReverseFlow() ? -1 : 1;
            for (ProjectMapModel.Column column : columns) {
                if (column.depth() == 0) {
                    continue; // the root row: the first column's title stands for it
                }
                ProjectMapModel.ColumnId id = column.id();
                ColumnLayout layout = columnLayouts.computeIfAbsent(id, ignored -> new ColumnLayout());
                double nodeWidth = nodeWidthFor(column, rowsByColumn.getOrDefault(id, List.of()));
                Integer anchorRow = column.anchor() == null ? null : rowIndex.get(column.anchor());
                NodeBox parent = anchorRow == null ? null : rowList.get(anchorRow);
                Integer anchorColumn = parent == null ? null : columnIndex.get(columnId(parent.entry()));
                ColumnBox parentColumn = anchorColumn == null ? null : columnList.get(anchorColumn);
                int level = parentColumn == null
                        ? 0
                        : levels.getOrDefault(parentColumn.column().id(), 0) + 1;
                levels.put(id, level);
                double depthStep = verticalFlow
                        ? COLUMN_TOP_INSET + COLUMN_HEADER_HEIGHT + NODE_HEIGHT + COLUMN_BOTTOM_PADDING + COLUMN_GAP
                        : 0;
                int rowCount = column.entries().size();
                // A column whose filter matches nothing keeps one row of space for its "No matches" line.
                int rowSlots = Math.max(1, rowCount);
                double rowsHeight = rowSlots * NODE_HEIGHT + (rowSlots - 1) * ROW_GAP;
                double rowsWidth = rowSlots * nodeWidth + (rowSlots - 1) * ROW_GAP;
                double cardWidth = verticalFlow ? rowsWidth + 20 : nodeWidth + 20;
                double headerHeight = columnHeaderHeight(column.depth(), cardWidth);
                double cardHeight = COLUMN_TOP_INSET
                        + headerHeight
                        + (verticalFlow ? NODE_HEIGHT : rowsHeight)
                        + narrowedFooterHeight(column)
                        + COLUMN_BOTTOM_PADDING;
                double baseWorldX = WORLD_PADDING;
                double baseWorldY = WORLD_PADDING + (verticalFlow ? direction * level * depthStep : 0);
                double inheritedShift = 0;
                if (parent != null && parentColumn != null) {
                    // Along the cross-axis a card is centred on the row that opened it, or hangs from it
                    // when it is long (MAX_COLUMN_LEAD). Along the flow axis it starts beyond its parent's
                    // card and beyond every other card of the parent's level.
                    double parentCenterX = parent.x() + parent.width() / 2;
                    double parentCenterY = parent.y() + parent.height() / 2;
                    double horizontalCardGap = COLUMN_GAP - 20;
                    inheritedShift =
                            flowShift.getOrDefault(parentColumn.column().id(), 0.0);
                    Double band = flowBand.get(level - 1);
                    double leadY = Math.min(cardHeight / 2, MAX_COLUMN_LEAD);
                    double leadX = Math.min(cardWidth / 2, nodeWidth * 1.5 + 10);
                    switch (flowDirection) {
                        case LEFT_TO_RIGHT -> {
                            double edge = parentColumn.x() + parentColumn.width();
                            edge = band == null ? edge : Math.max(edge, band + inheritedShift);
                            baseWorldX = edge + horizontalCardGap + 10;
                            baseWorldY = parentCenterY + COLUMN_TOP_INSET - leadY;
                        }
                        case RIGHT_TO_LEFT -> {
                            double edge = parentColumn.x();
                            edge = band == null ? edge : Math.min(edge, band + inheritedShift);
                            baseWorldX = edge - horizontalCardGap + 10 - cardWidth;
                            baseWorldY = parentCenterY + COLUMN_TOP_INSET - leadY;
                        }
                        case TOP_TO_BOTTOM -> {
                            double edge = parentColumn.y() + parentColumn.height();
                            edge = band == null ? edge : Math.max(edge, band + inheritedShift);
                            baseWorldX = parentCenterX + 10 - leadX;
                            baseWorldY = edge + COLUMN_GAP + COLUMN_TOP_INSET;
                        }
                        case BOTTOM_TO_TOP -> {
                            double edge = parentColumn.y();
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
                double farEdge = switch (flowDirection) {
                    case LEFT_TO_RIGHT -> columnWorldX - 10 + cardWidth;
                    case RIGHT_TO_LEFT -> columnWorldX - 10;
                    case TOP_TO_BOTTOM -> columnWorldY - COLUMN_TOP_INSET + cardHeight;
                    case BOTTOM_TO_TOP -> columnWorldY - COLUMN_TOP_INSET;
                };
                flowBand.merge(level, farEdge - shift, direction > 0 ? Math::max : Math::min);
                if (parentColumn != null) {
                    if (verticalFlow) {
                        double minimum = nextCrossEdge.getOrDefault(level, Double.NEGATIVE_INFINITY);
                        double cardLeft = columnWorldX - 10;
                        if (cardLeft < minimum) {
                            columnWorldX += minimum - cardLeft;
                        }
                        nextCrossEdge.put(level, columnWorldX - 10 + cardWidth + COLUMN_GAP);
                    } else {
                        double minimum = nextCrossEdge.getOrDefault(level, Double.NEGATIVE_INFINITY);
                        double cardTop = columnWorldY - COLUMN_TOP_INSET;
                        if (cardTop < minimum) {
                            columnWorldY += minimum - cardTop;
                        }
                        nextCrossEdge.put(level, columnWorldY - COLUMN_TOP_INSET + cardHeight + COLUMN_GAP);
                    }
                }
                for (int row = 0; row < column.entries().size(); row++) {
                    double worldX = columnWorldX + (verticalFlow ? row * (nodeWidth + ROW_GAP) : 0);
                    double worldY = columnWorldY + headerHeight + (verticalFlow ? 0 : row * (NODE_HEIGHT + ROW_GAP));
                    NodeBox box = new NodeBox(column.entries().get(row), worldX, worldY, nodeWidth, NODE_HEIGHT);
                    rowIndex.put(box.entry().path(), rowList.size());
                    rowList.add(box);
                }
                columnIndex.put(id, columnList.size());
                columnList.add(new ColumnBox(
                        column, columnWorldX - 10, columnWorldY - COLUMN_TOP_INSET, cardWidth, cardHeight));
                anchorRows.add(anchorRow == null || anchorColumn == null ? -1 : anchorRow);
                anchorColumns.add(anchorRow == null || anchorColumn == null ? -1 : anchorColumn);
            }
            return new WorldLayout(
                    List.copyOf(columnList),
                    List.copyOf(rowList),
                    List.copyOf(anchorRows),
                    List.copyOf(anchorColumns),
                    narrowedTotal);
        }

        /** Draws the last projection: cards, connectors, rows and the overview. */
        private void paint() {
            projectLayout();
            drawCanvas();
        }

        private void drawCanvas() {
            double width = canvas.getWidth();
            double height = canvas.getHeight();
            GraphicsContext g = canvas.getGraphicsContext2D();
            g.setGlobalAlpha(1);
            g.setFill(color(bgProbe, Color.web("#0f141c")));
            g.fillRect(0, 0, width, height);
            lastPaintedConnectorCount = 0;
            WorldLayout world = projected;
            if (entries.isEmpty() || world == null) {
                g.setFill(color(mutedProbe, Color.web("#8b949e")));
                g.setFont(mapFont(FontWeight.NORMAL, FontPosture.REGULAR, 13));
                g.fillText(tr(loadPending ? "project.map.loading" : "project.map.empty"), 18, 28);
                return;
            }
            drawColumns(g);
            g.setStroke(color(accentProbe, Color.web("#58a6ff")));
            // Rows were added column by column, so the two lists are walked together: a column's rows
            // share one parent row and, in a vertical flow, one card edge to route to.
            int next = 0;
            for (int index = 0; index < columnBoxes.size(); index++) {
                ColumnBox childColumn = columnBoxes.get(index);
                int rows = childColumn.column().entries().size();
                int anchorRow = world.anchorRows().get(index);
                NodeBox parent = rows == 0 || anchorRow < 0 ? null : boxes.get(anchorRow);
                ColumnBox parentColumn = parent == null
                        ? null
                        : columnBoxes.get(world.anchorColumns().get(index));
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
            for (NodeBox box : boxes) {
                if (inViewport(box, width, height)) {
                    drawNode(g, box);
                }
            }
            if (!outputPaint) {
                drawOverview(g, width, height);
            }
            g.setGlobalAlpha(1);
        }

        private void reportNarrowed(int count) {
            if (count != narrowedRows) {
                narrowedRows = count;
                onNarrowedChanged.accept(count);
            }
        }

        /**
         * A column that "Hide unmatched" took rows out of says so under the rows that are left. One with none
         * left says it on its empty row instead.
         */
        private static double narrowedFooterHeight(ProjectMapModel.Column column) {
            return column.narrowed() > 0 && !column.entries().isEmpty() ? NARROWED_FOOTER_HEIGHT : 0;
        }

        private void reportNoMatches(boolean none) {
            if (none != noMatches) {
                noMatches = none;
                onNoMatchesChanged.accept(none);
            }
        }

        /**
         * A column's row width: its widest label with its row's marks, at least {@link #MIN_NODE_WIDTH} and at
         * most {@link #MAX_NODE_WIDTH} (a longer name is elided in the middle; its tooltip has it whole).
         */
        private double nodeWidthFor(ProjectMapModel.Column column, List<ProjectMapModel.Entry> rows) {
            double required = measuredLabelWidth(columnTitle(column)) + 52;
            for (ProjectMapModel.Entry entry : rows) {
                required = Math.max(required, measuredLabelWidth(entry.name()) + rowExtraWidth(entry));
            }
            if (column.depth() > 0 && showHiddenLabelWidth > 0 && !isStructureColumn(column.id())) {
                // At 100% the header row must hold the filter, the labelled checkbox and the lock, whatever
                // the checkbox label measures in the current language.
                required = Math.max(
                        required, MIN_COLUMN_FILTER_WIDTH + showHiddenLabelWidth + 25 + COLUMN_CONTROL_GAP * 2 - 2);
            }
            return Math.max(MIN_NODE_WIDTH, Math.min(MAX_NODE_WIDTH, Math.ceil(required)));
        }

        /** Width a row needs besides its label: icon and padding, then the marks at its trailing end. */
        private double rowExtraWidth(ProjectMapModel.Entry entry) {
            if (entry.isSymbol()) {
                return 31 + SYMBOL_LINE_WIDTH + (branches(entry) ? CHEVRON_ZONE : 8);
            }
            if (!entry.directory()) {
                // File rows reserve a fixed tail: Git letter and unsaved dot, both badges, Structure and Preview.
                return 31 + 20 + MARKER_WIDTH * 2 + PREVIEW_ZONE + (hasStructure(entry) ? STRUCTURE_ZONE : 0);
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
            if (column.compacted()) {
                return String.join(CHAIN_SEPARATOR, chainNames(column));
            }
            Path parent = column.parent();
            if (parent == null) {
                return tr("project.map.column.level", column.depth());
            }
            ProjectMapModel.Entry owner = entryByPath.get(parent);
            if (owner != null && owner.isFile()) {
                return tr("project.map.column.structure", owner.name());
            }
            if (owner != null && owner.isSymbol()) {
                return owner.name();
            }
            Path name = parent.getFileName();
            return name == null ? parent.toString() : name.toString();
        }

        /** The folder names of a compacted column's chain, first to last. */
        private static List<String> chainNames(ProjectMapModel.Column column) {
            List<String> names = new ArrayList<>(column.chain().size());
            for (Path folder : column.chain()) {
                Path name = folder.getFileName();
                names.add(name == null ? folder.toString() : name.toString());
            }
            return names;
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

        /** The first column (the root's children) has no ×: there is no row it could fold back into. */
        private boolean closeFits(ColumnBox box) {
            return box.column().depth() > 0
                    && !firstColumns.contains(box.column().id())
                    && zoom >= 0.65
                    && box.width() >= closeSize() + 42;
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
                // A structure column lists declarations, which are never hidden: it has no checkbox.
                boolean structureColumn = isStructureColumn(id);
                if (structureColumn) {
                    controls.showHidden().setVisible(false);
                }
                double labelWidth = structureColumn ? 0 : showHiddenLabelWidth(controls.showHidden());
                boolean labelFits =
                        availableWidth >= MIN_COLUMN_FILTER_WIDTH + labelWidth + pinWidth + COLUMN_CONTROL_GAP * 2;
                double hiddenWidth = structureColumn ? 0 : labelFits ? labelWidth : CHECK_ONLY_WIDTH;
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
        /** Where the overview sits for the projected columns, or null when everything is on screen. */
        private @Nullable OverviewBox overviewGeometry(double viewportWidth, double viewportHeight) {
            if (outputPalette != null || columnBoxes.isEmpty()) {
                return null;
            }
            double minX = contentMinX();
            double minY = contentMinY();
            double maxX = contentMaxX();
            double maxY = contentMaxY();
            if (minX >= 0 && minY >= 0 && maxX <= viewportWidth && maxY <= viewportHeight) {
                return null; // everything is on screen: nothing to navigate to
            }
            double overviewWidth = Math.min(150, Math.max(90, viewportWidth * 0.18));
            double overviewHeight = 86;
            double x = viewportWidth - overviewWidth - 10;
            double y = viewportHeight - overviewHeight - 10;
            if (x < reservedBarWidth + 6) {
                return null; // too narrow to sit beside the zoom bar, and anywhere else it would cover rows
            }
            double contentWidth = Math.max(1, maxX - minX);
            double contentHeight = Math.max(1, maxY - minY);
            double scale = Math.min((overviewWidth - 10) / contentWidth, (overviewHeight - 10) / contentHeight);
            return new OverviewBox(x, y, overviewWidth, overviewHeight, minX, minY, scale);
        }

        private void drawOverview(GraphicsContext g, double viewportWidth, double viewportHeight) {
            OverviewBox overview = overviewBox;
            if (overview == null) {
                return;
            }
            double x = overview.x();
            double y = overview.y();
            double overviewWidth = overview.width();
            double overviewHeight = overview.height();
            double minX = overview.minX();
            double minY = overview.minY();
            double scale = overview.scale();
            g.setGlobalAlpha(0.94);
            g.setFill(color(surfaceProbe, Color.web("#161d27")));
            g.fillRoundRect(x, y, overviewWidth, overviewHeight, 8, 8);
            g.setStroke(rowBorder());
            g.setLineWidth(1);
            g.strokeRoundRect(x, y, overviewWidth, overviewHeight, 8, 8);
            Color muted = color(mutedProbe, Color.web("#8b949e"));
            Color structureMuted = mix(muted, structureTint(), 0.6);
            for (ColumnBox box : columnBoxes) {
                g.setFill(isStructureColumn(box.column().id()) ? structureMuted : muted);
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
            Color structureSurface = mix(surface, structureTint(), STRUCTURE_COLUMN_TINT);
            Color structureBorder = mix(border, structureTint(), 0.35);
            Color accent = color(accentProbe, Color.web("#388bfd"));
            // The root and the folders of a compacted chain have no row: their column's title is selected.
            ColumnBox selectedHeader = headerFor(selected);
            for (ColumnBox box : columnBoxes) {
                ProjectMapModel.Column column = box.column();
                double x = box.x();
                double y = box.y();
                double w = box.width();
                double h = box.height();
                boolean structure = isStructureColumn(column.id());
                boolean headerSelected = box == selectedHeader;
                g.setGlobalAlpha(0.64);
                g.setFill(structure ? structureSurface : surface);
                g.fillRoundRect(x, y, w, h, 12 * zoom, 12 * zoom);
                g.setStroke(structure ? structureBorder : border);
                g.setLineWidth(1);
                g.strokeRoundRect(x, y, w, h, 12 * zoom, 12 * zoom);
                if (headerSelected) {
                    g.setGlobalAlpha(1);
                    g.setStroke(accent);
                    g.setLineWidth(1.8 * zoom);
                    g.strokeRoundRect(x, y, w, h, 12 * zoom, 12 * zoom);
                    if (isFocused() && !outputPaint) {
                        g.setStroke(color(focusRingProbe, accent));
                        g.setLineWidth(2);
                        g.strokeRoundRect(x + 3 * zoom, y + 2 * zoom, w - 6 * zoom, 22 * zoom, 7 * zoom, 7 * zoom);
                    }
                }

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
                g.setGlobalAlpha(headerSelected ? 1 : 0.88);
                g.setFill(headerSelected ? color(accentTextProbe, accent) : color(textProbe, Color.web("#d8dee9")));
                g.setFont(mapFont(FontWeight.SEMI_BOLD, FontPosture.REGULAR, titleSize));
                boolean titleFits = titleWidth <= titleRoom;
                // A chain keeps its last folders (the ones its rows are in) when it must be cut.
                g.fillText(
                        titleFits
                                ? title
                                : column.compacted()
                                        ? elideMiddle(title, titleSize, titleRoom)
                                        : elide(title, titleSize, titleRoom),
                        titleLeft,
                        baseline);
                if (headerSelected && titleFits && column.compacted()) {
                    underlineChainFolder(g, column, titleLeft, baseline, titleSize);
                }
                g.setFill(color(mutedProbe, Color.web("#8b949e")));
                if (showCount) {
                    g.setFont(mapFont(FontWeight.NORMAL, FontPosture.REGULAR, countSize));
                    g.setTextAlign(TextAlignment.RIGHT);
                    g.fillText(count, right, baseline);
                    g.setTextAlign(TextAlignment.LEFT);
                }
                String narrowed = column.narrowed() > 0 ? tr("project.map.column.narrowed", column.narrowed()) : null;
                if (column.entries().isEmpty()) {
                    // Its own filter (or the hidden-files checkbox, or "Hide unmatched") removed every row.
                    g.setFont(mapFont(FontWeight.NORMAL, FontPosture.REGULAR, Math.max(9, 11 * zoom)));
                    double header = columnHeaderHeight(column.depth(), w / zoom);
                    g.fillText(
                            narrowed != null ? narrowed : columnNoMatches,
                            titleLeft,
                            y + (COLUMN_TOP_INSET + header) * zoom + 20.5 * zoom,
                            Math.max(1, w - 20 * zoom));
                } else if (narrowed != null) {
                    g.setFont(mapFont(FontWeight.NORMAL, FontPosture.ITALIC, Math.max(8, 10 * zoom)));
                    g.fillText(
                            narrowed,
                            titleLeft,
                            y + h - (COLUMN_BOTTOM_PADDING + NARROWED_FOOTER_HEIGHT * 0.3) * zoom,
                            Math.max(1, w - 20 * zoom));
                }
            }
            g.setGlobalAlpha(1);
        }

        /** Underlines, in a compacted column's title, the folder of the chain that is selected. */
        private void underlineChainFolder(
                GraphicsContext g, ProjectMapModel.Column column, double left, double baseline, double size) {
            List<String> names = chainNames(column);
            StringBuilder before = new StringBuilder();
            for (int index = 0; index < names.size(); index++) {
                if (column.chain().get(index).equals(selected)) {
                    double start = left + measuredLabelWidth(before.toString()) * size / 12;
                    double width = measuredLabelWidth(names.get(index)) * size / 12;
                    g.setLineWidth(Math.max(1, 1.2 * zoom));
                    g.strokeLine(start, baseline + 2.5 * zoom, start + width, baseline + 2.5 * zoom);
                    return;
                }
                before.append(names.get(index)).append(CHAIN_SEPARATOR);
            }
        }

        /**
         * {@code text} cut to fit {@code room} at {@code fontSize} by taking characters out of its middle: a
         * long file name keeps its start and its extension, a chain its first and last folders.
         */
        private String elideMiddle(String text, double fontSize, double room) {
            if (elidedTitles.size() > 256) {
                elidedTitles.clear();
            }
            String key = text + '\u0001' + (int) room + '\u0001' + (int) (fontSize * 8);
            return elidedTitles.computeIfAbsent(key, ignored -> {
                int low = 0;
                int high = text.length();
                while (low < high) { // the most characters that still fit around the ellipsis
                    int middle = (low + high + 1) / 2;
                    if (measuredLabelWidth(middleCut(text, middle)) * fontSize / 12 <= room) {
                        low = middle;
                    } else {
                        high = middle - 1;
                    }
                }
                return low == 0 ? "" : middleCut(text, low);
            });
        }

        /** {@code text} with {@code kept} of its characters around an ellipsis, a little more of the end. */
        static String middleCut(String text, int kept) {
            if (kept >= text.length()) {
                return text;
            }
            int tail = (kept + 1) / 2;
            int head = kept - tail;
            int end = text.length() - tail;
            // Never split a surrogate pair (an emoji in a file name).
            if (head > 0 && Character.isHighSurrogate(text.charAt(head - 1))) {
                head--;
            }
            if (end < text.length() && Character.isLowSurrogate(text.charAt(end))) {
                end++;
            }
            return text.substring(0, head) + "…" + text.substring(end);
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
            boolean dimmed = !prominence(entry.path()) && !isSelected;
            // A faded row recedes through its fill and border; its label only fades as far as a readable
            // floor, and its marks keep most of their ink (Map review B19).
            g.setGlobalAlpha(dimmed ? DIMMED_ALPHA : 1.0);
            Color accent = color(accentProbe, Color.web("#388bfd"));
            Color surface = color(surfaceProbe, Color.web("#202938"));
            if (entry.isSymbol()) {
                surface = mix(surface, structureTint(), STRUCTURE_ROW_TINT);
            }
            dimmedGround = dimmed
                    ? mix(mix(color(bgProbe, Color.web("#0f141c")), color(surfaceProbe, surface), 0.64), surface, 0.38)
                    : null;
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
            g.setGlobalAlpha(dimmed ? DIMMED_MARK_ALPHA : 1.0);
            drawOpenMarker(g, entry, box, focusedSelection);

            double iconX = box.x() + 7 * zoom;
            double iconY = box.y() + 6 * zoom;
            drawIcon(g, entry, iconX, iconY, focusedSelection);
            if (entry.isSymbol()) {
                drawSymbolRow(g, entry, box, focusedSelection, isSelected);
                g.setGlobalAlpha(1);
                dimmedGround = null;
                return;
            }
            Color ink = focusedSelection
                    ? onAccent()
                    : openPaths.contains(entry.path())
                            ? color(accentTextProbe, accent)
                            : color(textProbe, Color.web("#d8dee9"));
            g.setFont(rowFont(isSelected));
            // A name wider than the row (the width is capped) loses its middle; the tooltip has it whole.
            String label = entry.name();
            double room = box.width() - rowExtraWidth(entry) * zoom;
            if (measuredLabelWidth(label) * zoom > room) {
                label = elideMiddle(label, 12 * zoom, room);
            }
            fillLabel(g, ink, label, box.x() + 31 * zoom, box.y() + 20.5 * zoom);

            drawStatusMarks(g, entry, box, focusedSelection);
            drawFileMarkers(g, entry, box, focusedSelection);
            if (entry.directory()) {
                drawChevron(g, entry, box, focusedSelection);
            } else {
                drawPreviewAffordance(g, box, focusedSelection, isHovered);
                if (hasStructure(entry)) {
                    drawStructureAffordance(g, entry, box, focusedSelection, isHovered);
                }
            }
            g.setGlobalAlpha(1);
            dimmedGround = null;
        }

        /**
         * The rest of a declaration row: its label, its 1-based line in the file (muted, at the trailing end)
         * and, when it holds declarations of its own, the chevron that opens their column.
         */
        private void drawSymbolRow(
                GraphicsContext g, ProjectMapModel.Entry entry, NodeBox box, boolean onAccentFill, boolean bold) {
            ProjectMapModel.Symbol symbol = entry.symbol();
            boolean chevron = branches(entry);
            double right = box.x() + box.width() - (chevron ? CHEVRON_ZONE : 8) * zoom;
            String line = symbol == null ? "" : tr("structure.line", symbol.line() + 1);
            Color muted = onAccentFill ? onAccent() : color(mutedProbe, Color.web("#8b949e"));
            g.setFill(muted);
            g.setFont(mapFont(FontWeight.NORMAL, FontPosture.REGULAR, Math.max(8, 10 * zoom)));
            g.setTextAlign(TextAlignment.RIGHT);
            g.fillText(line, right - 2 * zoom, box.y() + 20 * zoom);
            g.setTextAlign(TextAlignment.LEFT);
            double labelLeft = box.x() + 31 * zoom;
            double room = right - SYMBOL_LINE_WIDTH * zoom - labelLeft;
            g.setFont(rowFont(bold));
            String label = entry.name();
            double width = measuredLabelWidth(label) * zoom;
            fillLabel(
                    g,
                    onAccentFill ? onAccent() : color(textProbe, Color.web("#d8dee9")),
                    width <= room ? label : elide(label, 12 * zoom, room),
                    labelLeft,
                    box.y() + 20.5 * zoom);
            if (chevron) {
                drawChevron(g, entry, box, onAccentFill);
            }
        }

        /**
         * The Structure icon before a file row's eye — the Structure tool window's glyph of stepped lines. It
         * opens the file's structure column, and sits in a filled disc while that column is open, as an open
         * folder's chevron does.
         */
        private void drawStructureAffordance(
                GraphicsContext g, ProjectMapModel.Entry entry, NodeBox box, boolean onAccentFill, boolean rowHovered) {
            boolean hot = rowHovered && hoveredAffordance == Affordance.STRUCTURE;
            boolean open = structure.isOpen(entry.path());
            double cx = box.x() + box.width() - (PREVIEW_ZONE + STRUCTURE_ZONE / 2) * zoom;
            double cy = box.y() + box.height() / 2;
            Color ink = onAccentFill ? onAccent() : color(hot ? textProbe : mutedProbe, Color.web("#8b949e"));
            if (open || hot) {
                double radius = 9 * zoom;
                double previous = g.getGlobalAlpha();
                g.setGlobalAlpha(previous * (open ? (hot ? 0.34 : 0.22) : 0.16));
                g.setFill(ink);
                g.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);
                g.setGlobalAlpha(previous);
            }
            // Icons.structure(): M3 4h10 M5.5 8h7.5 M8 12h5, on a 16-unit box centred on (8, 8).
            double unit = 0.75 * zoom;
            g.setStroke(ink);
            g.setLineWidth(Math.max(1, 1.3 * zoom));
            g.strokeLine(cx - 5 * unit, cy - 4 * unit, cx + 5 * unit, cy - 4 * unit);
            g.strokeLine(cx - 2.5 * unit, cy, cx + 5 * unit, cy);
            g.strokeLine(cx, cy + 4 * unit, cx + 5 * unit, cy + 4 * unit);
        }

        /** Whether a file row has the Structure icon: its language can have an outline. */
        private boolean hasStructure(ProjectMapModel.Entry entry) {
            return structure.supports(entry);
        }

        /** A folder, or a declaration that holds others: its row ends in a chevron. */
        private boolean branches(ProjectMapModel.Entry entry) {
            ProjectMapModel.Symbol symbol = entry.symbol();
            return symbol != null ? symbol.branches() : entry.directory() && !entry.isPlaceholder();
        }

        /** A column of declarations: its owner row is a file or a declaration. */
        private boolean isStructureColumn(ProjectMapModel.ColumnId id) {
            ProjectMapModel.Entry owner = id.parent() == null ? null : entryByPath.get(id.parent());
            return owner != null && (owner.isFile() || owner.isSymbol());
        }

        /** The strip at a row's trailing end that its own affordances take: chevron, or Structure and eye. */
        private double trailingZone(ProjectMapModel.Entry entry) {
            if (entry.isSymbol()) {
                return branches(entry) ? CHEVRON_ZONE : 8;
            }
            if (entry.directory()) {
                return CHEVRON_ZONE;
            }
            return PREVIEW_ZONE + (hasStructure(entry) ? STRUCTURE_ZONE : 0);
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
            g.setFont(mapFont(more ? FontWeight.SEMI_BOLD : FontWeight.NORMAL, FontPosture.REGULAR, 12 * zoom));
            g.fillText(entry.name(), box.x() + 10 * zoom, box.y() + 20.5 * zoom);
        }

        /**
         * A row's label in {@code ink}: on a faded row the ink is mixed toward the row's ground only as far as
         * {@link #FADED_LABEL_CONTRAST} allows, and drawn at full opacity.
         */
        private void fillLabel(GraphicsContext g, Color ink, String label, double x, double y) {
            Color ground = dimmedGround;
            if (ground == null) {
                g.setFill(ink);
                g.fillText(label, x, y);
                return;
            }
            double alpha = g.getGlobalAlpha();
            if (fadedInks.size() > 32) {
                fadedInks.clear();
            }
            g.setGlobalAlpha(1);
            g.setFill(fadedInks.computeIfAbsent(
                    List.of(ink, ground), key -> fadedInk(ink, ground, FADED_LABEL_CONTRAST, 1 - DIMMED_ALPHA)));
            g.fillText(label, x, y);
            g.setGlobalAlpha(alpha);
        }

        /** Row label font at the current zoom; every row shares one of the two. */
        private Font rowFont(boolean bold) {
            return mapFont(bold ? FontWeight.SEMI_BOLD : FontWeight.NORMAL, FontPosture.REGULAR, 12 * zoom);
        }

        /**
         * A canvas font in the UI's family (read from CSS through {@link #fontProbe}: Inter, as the controls
         * around the canvas use), created once per weight, posture and size rather than on every paint.
         */
        private Font mapFont(FontWeight weight, FontPosture posture, double size) {
            if (fonts.size() > 64) {
                fonts.clear(); // a zoom gesture asks for a new size on every step
            }
            String family = fontFamily();
            return fonts.computeIfAbsent(
                    new FontKey(family, weight, posture, size), key -> Font.font(family, weight, posture, size));
        }

        /** The UI font family, as CSS resolves it for the map; the measurer follows it. */
        private String fontFamily() {
            Font font = fontProbe.getFont();
            String family = font == null ? FALLBACK_FONT_FAMILY : font.getFamily();
            if (!family.equals(measuredFamily)) {
                measuredFamily = family;
                textMeasurer.setFont(Font.font(family, FontWeight.SEMI_BOLD, 12));
                measuredLabelWidths.clear();
                elidedTitles.clear();
                fonts.clear();
            }
            return family;
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
            return branches(box.entry()) && x >= box.x() + box.width() - CHEVRON_ZONE * zoom;
        }

        /** Whether {@code x} is over a file row's Structure icon. */
        private boolean structureHit(NodeBox box, double x) {
            if (!hasStructure(box.entry())) {
                return false;
            }
            double right = box.x() + box.width() - PREVIEW_ZONE * zoom;
            return x >= right - STRUCTURE_ZONE * zoom && x < right;
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
            if (!entry.isFile() || !openPaths.contains(entry.path())) {
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
            ProjectMapModel.Symbol symbol = entry.symbol();
            if (symbol != null) {
                // A declaration has the Structure tool window's glyph for its kind.
                String glyph = StructureIcons.pathFor(symbol.kind());
                IconKey key = new IconKey("symbol:" + glyph, onAccentFill ? ON_ACCENT_ICON_CLASS : "");
                Image cached = iconImages.get(key);
                if (cached == null) {
                    cached = rasterize(
                            StructureIcons.forKind(symbol.kind()), "project-map-symbol-icon", key.statusClass());
                    iconImages.put(key, cached);
                }
                return cached;
            }
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
            return rasterize(
                    FileIcons.forProjectItem(fileName, directory),
                    directory ? "folder-cell" : "file-cell",
                    statusClass);
        }

        private Image rasterize(Node glyph, String cellClass, String statusClass) {
            StackPane cell = new StackPane(glyph);
            cell.getStyleClass().add(cellClass);
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
            if (!entry.isFile()) {
                return;
            }
            double markerWidth = (bookmarkedPaths.contains(entry.path()) ? MARKER_WIDTH : 0)
                    + (notedPaths.contains(entry.path()) ? MARKER_WIDTH : 0);
            // Keep Structure and Preview in the final strip and the semantic markers immediately to its left.
            double x = box.x() + box.width() - (trailingZone(entry) + 3 + markerWidth) * zoom;
            GitFileStatus status = gitState.get(entry.path());
            if (status != null) {
                g.setFill(onAccentFill ? onAccent() : gitColor(status));
                g.setFont(mapFont(FontWeight.BOLD, FontPosture.REGULAR, 10 * zoom));
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
            // Folders end in the chevron strip, files in the (wider) Structure and preview strip.
            double x = box.x() + box.width() - (trailingZone(entry) + 6) * zoom;
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
                } else if (box.entry().isFile() && previewHit(box, x)) {
                    next = Affordance.PREVIEW;
                } else if (structureHit(box, x)) {
                    next = Affordance.STRUCTURE;
                }
            }
            setAffordance(next, box);
        }

        private void setAffordance(Affordance next, @Nullable NodeBox box) {
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
        private @Nullable String affordanceHint(ProjectMapModel.Entry entry) {
            return switch (hoveredAffordance) {
                case PREVIEW -> entry.isFile() ? tr("project.map.node.preview", entry.name()) : null;
                case STRUCTURE ->
                    tr(
                            structure.isOpen(entry.path())
                                    ? "project.map.node.hideStructure"
                                    : "project.map.node.showStructure",
                            entry.name());
                case NOTES -> tr("project.map.node.notes", entry.name());
                case CHEVRON ->
                    !branches(entry)
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
            STRUCTURE,
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
                if (entry.isPlaceholder() || entry.isSymbol()) {
                    continue; // a declaration is not a file: it is neither open nor marked
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
                    boolean limited = draggedColumn.depth() > 0 && !firstColumns.contains(draggedColumn);
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
            ProjectMapModel.Symbol symbol = entry.symbol();
            if (symbol != null) {
                lines.add(entry.name());
                if (symbol.kind() != null && !symbol.kind().isBlank()) {
                    lines.add(tr("project.map.tooltip.type", symbol.kind()));
                }
                lines.add(tr("project.map.structure.lines", symbol.startLine() + 1, symbol.endLine() + 1));
                lines.add(symbol.file().toString());
                if (hint == null) {
                    lines.add(tr("project.map.node.symbolPreview"));
                }
                return String.join("\n", lines);
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
            } else if (entry.isFile() && previewHit(hit, event.getX())) {
                onPreview.accept(entry.path());
            } else if (structureHit(hit, event.getX()) || entry.isSymbol() && chevronHit(hit, event.getX())) {
                onStructure.accept(entry);
            } else if (entry.isSymbol()) {
                if (event.getClickCount() == 1) {
                    onActivate.accept(entry); // its card
                }
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
            double right = box.x() + box.width() - trailingZone(box.entry()) * zoom;
            return x >= right - MARKER_WIDTH * zoom && x < right;
        }

        private void contextMenuRequested(ContextMenuEvent event) {
            dismissContextMenu();
            flushPendingRepaint();
            NodeBox hit;
            ProjectMapModel.Entry target;
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
                ColumnBox header = hit == null ? headerFor(selected) : null;
                target = hit != null ? hit.entry() : header != null ? entryByPath.get(selected) : null;
                if (target == null) {
                    event.consume();
                    return;
                }
                // The root and a folder of a compacted chain open their menu under their column's title.
                double anchorLeft;
                double anchorWidth;
                double anchorBottom;
                if (hit != null) {
                    anchorLeft = hit.x();
                    anchorWidth = hit.width();
                    anchorBottom = hit.y() + hit.height();
                } else if (header != null) {
                    anchorLeft = header.x();
                    anchorWidth = header.width();
                    anchorBottom = header.y() + 22 * zoom;
                } else {
                    event.consume();
                    return;
                }
                javafx.geometry.Point2D anchor =
                        localToScreen(anchorLeft + RowContextMenu.anchorX(anchorWidth), anchorBottom);
                if (anchor != null) {
                    screenX = anchor.getX();
                    screenY = anchor.getY();
                }
            } else {
                if (overviewBox != null && overviewBox.contains(event.getX(), event.getY())) {
                    return;
                }
                hit = hit(event.getX(), event.getY());
                if (hit != null) {
                    target = hit.entry();
                } else {
                    // A column's title stands for the folder (or file) whose rows it lists: the first column's
                    // for the root, a compacted column's for the last folder of its chain.
                    ColumnBox header = headerHit(event.getX(), event.getY());
                    Path owner = header == null ? null : header.column().parent();
                    target = owner == null ? null : entryByPath.get(owner);
                }
                if (target == null) {
                    return;
                }
                select(target.path());
            }
            requestFocus();
            clearNodeTooltip();
            ContextMenu menu = contextMenuFactory.apply(target);
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
                case HOME ->
                    select(
                            boxes.isEmpty()
                                    ? entries.getFirst().path()
                                    : boxes.getFirst().entry().path());
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
            ProjectMapModel.Entry entry = selectedEntry()
                    .filter(row -> !row.isPlaceholder() && !row.isSymbol())
                    .orElse(null);
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
            ColumnBox header = headerFor(selected);
            if (header != null) {
                // The root or a folder of a compacted chain is its column's header: step into the column.
                stepIntoColumn(header, delta < 0);
                return;
            }
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

        /**
         * The row the selection's column hangs from. A compacted column's rows (and the folders of its chain)
         * go to the chain's first folder, the row on the map; the first column's rows go to the root, which is
         * that column's header.
         */
        private void selectParent() {
            Path parent = selectedEntry().map(ProjectMapModel.Entry::parent).orElse(null);
            while (parent != null && nodeBox(parent) == null && headerFor(parent) == null) {
                ProjectMapModel.Entry folder = entryByPath.get(parent);
                parent = folder == null ? null : folder.parent();
            }
            if (parent == null) {
                return;
            }
            ColumnBox header = headerFor(parent);
            if (header != null
                    && header.column().compacted()
                    && nodeBox(header.column().anchor()) != null) {
                parent = header.column().anchor(); // a folder inside the chain: the row the chain starts at
            }
            if (parent != null) {
                select(parent);
            }
        }

        /** Selects a column's first row, or its last with {@code last}. */
        private void stepIntoColumn(ColumnBox column, boolean last) {
            List<ProjectMapModel.Entry> rows = column.column().entries();
            if (!rows.isEmpty()) {
                select((last ? rows.getLast() : rows.getFirst()).path());
            }
        }

        /**
         * The column a path's children are drawn in: the column it opens, or the compacted column whose chain
         * it starts or belongs to. Null when that column is not on the map.
         */
        private @Nullable ColumnBox columnOpenedBy(@Nullable Path path) {
            if (path == null) {
                return null;
            }
            for (ColumnBox box : columnBoxes) {
                ProjectMapModel.Column column = box.column();
                if (path.equals(column.parent()) || column.chain().contains(path)) {
                    return box;
                }
            }
            return null;
        }

        /**
         * The column whose header stands for {@code path}: the root's (the first column), or a compacted
         * column's for a folder of its chain that has no row of its own. Null when the path has a row.
         */
        private @Nullable ColumnBox headerFor(@Nullable Path path) {
            if (path == null || nodeBox(path) != null) {
                return null;
            }
            return columnOpenedBy(path);
        }

        /**
         * The arrow along the flow: into the selected folder, file structure or declaration, opening its column
         * first when it is closed.
         */
        private void selectChildOrExpand() {
            var current = selectedEntry();
            if (current.isEmpty()) {
                return;
            }
            ProjectMapModel.Entry entry = current.get();
            boolean folder = entry.directory() && !entry.isPlaceholder();
            boolean structured = entry.isFile() && hasStructure(entry) || entry.isSymbol() && branches(entry);
            if (!folder && !structured) {
                return;
            }
            ColumnBox opened = columnOpenedBy(entry.path());
            if (opened != null && !opened.column().entries().isEmpty()) {
                stepIntoColumn(opened, false); // into a compacted column too, past the folders of its chain
                return;
            }
            List<ProjectMapModel.Entry> children = boxes.stream()
                    .map(NodeBox::entry)
                    .filter(child -> entry.path().equals(child.parent()))
                    .toList();
            if (children.isEmpty()) {
                if (!expandedSnapshot.contains(entry.path())) {
                    if (folder) {
                        onActivate.accept(entry);
                    } else {
                        onStructure.accept(entry);
                    }
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
            ColumnBox header = headerFor(selected);
            ProjectMapModel.ColumnId id = header != null
                    ? header.column().id()
                    : selectedEntry().map(MapSurface::columnId).orElse(null);
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

        /**
         * Input handlers hit-test the boxes of the last projection; bring them up to date with a coalesced pan or
         * zoom. The Canvas is still drawn at the pulse.
         */
        private void flushPendingRepaint() {
            if (boxesStale) {
                repaint();
            }
        }

        private void revealSelected() {
            flushPendingRepaint();
            NodeBox box = nodeBox(selected);
            ColumnBox header = box == null ? headerFor(selected) : null;
            double x;
            double y;
            double width;
            double height;
            if (box != null) {
                x = box.x();
                y = box.y();
                width = box.width();
                height = box.height();
            } else if (header != null) {
                // The root or a folder of a compacted chain: its column's title strip.
                x = header.x();
                y = header.y();
                width = header.width();
                height = Math.min(header.height(), COMPACT_HEADER_HEIGHT * zoom);
            } else {
                return;
            }
            double[] pan = new double[2];
            include(pan, x, y, x + width, y + height);
            // The overview floats over the bottom-right corner: a row revealed there would sit under it.
            OverviewBox overview = overviewBox;
            if (overview != null) {
                double left = x + pan[0];
                double top = y + pan[1];
                if (left + width > overview.x() - 4
                        && left < overview.x() + overview.width()
                        && top + height > overview.y() - 4
                        && top < overview.y() + overview.height()) {
                    pan[1] -= top + height - (overview.y() - 8);
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

        private @Nullable NodeBox hit(double x, double y) {
            for (int i = boxes.size() - 1; i >= 0; i--) {
                NodeBox box = boxes.get(i);
                if (x >= box.x() && x <= box.x() + box.width() && y >= box.y() && y <= box.y() + box.height()) {
                    return box;
                }
            }
            return null;
        }

        private @Nullable ColumnBox headerHit(double x, double y) {
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
            ProjectMapModel.Symbol symbol = current.symbol();
            if (symbol != null) {
                // A structure column is shown in the order its declarations are written: count in that order.
                int index = 1;
                int count = 0;
                for (ProjectMapModel.Entry entry : entries) {
                    if (columnId(entry).equals(column) && !entry.isPlaceholder()) {
                        count++;
                        if (entry == current) {
                            index = count;
                        }
                    }
                }
                setAccessibleText(tr(
                        "project.map.accessible.symbol",
                        current.name(),
                        symbol.kind() == null ? "" : symbol.kind(),
                        symbol.line() + 1,
                        index,
                        count));
                return;
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

        private Color structureTint() {
            return color(structureProbe, Color.web("#8250df"));
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

        private @Nullable NodeBox nodeBox(@Nullable Path path) {
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

        /**
         * Columns and rows in world units, in drawing order. {@code anchorRows} and {@code anchorColumns} give,
         * per column, the index of the row it hangs from and of that row's column (-1 for the first column).
         */
        private record WorldLayout(
                List<ColumnBox> columns,
                List<NodeBox> rows,
                List<Integer> anchorRows,
                List<Integer> anchorColumns,
                int narrowed) {}

        /**
         * Everything a world layout depends on. Lists and sets are the surface's own (replaced, never mutated,
         * when they change), so comparing two keys is cheap while nothing changed.
         */
        private record LayoutKey(
                List<ProjectMapModel.Entry> entries,
                Map<ProjectMapModel.ColumnId, String> queries,
                Map<ProjectMapModel.ColumnId, Boolean> hidden,
                @Nullable Set<Path> keep,
                FlowDirection flow,
                double zoom,
                boolean output,
                Map<ProjectMapModel.ColumnId, javafx.geometry.Point2D> offsets,
                double hiddenLabelWidth,
                Set<Path> bookmarks,
                Set<Path> notes,
                String fontFamily,
                boolean compactChains) {}

        private record FontKey(String family, FontWeight weight, FontPosture posture, double size) {}

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
     * {@code ink} mixed toward {@code ground} by as much as {@code maxFade} while it keeps {@code floor}
     * contrast with it: a faded label recedes but stays readable. Ink already under the floor is kept. Pure —
     * tested.
     */
    static Color fadedInk(Color ink, Color ground, double floor, double maxFade) {
        if (contrastRatio(ink, ground) <= floor) {
            return ink;
        }
        Color full = ink.interpolate(ground, maxFade);
        if (contrastRatio(full, ground) >= floor) {
            return full;
        }
        double low = 0;
        double high = maxFade;
        for (int step = 0; step < 14; step++) {
            double middle = (low + high) / 2;
            if (contrastRatio(ink.interpolate(ground, middle), ground) >= floor) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return ink.interpolate(ground, low);
    }

    /** WCAG 2 contrast ratio of two opaque colours. */
    static double contrastRatio(Color a, Color b) {
        double la = relativeLuminance(a);
        double lb = relativeLuminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double relativeLuminance(Color c) {
        return 0.2126 * linear(c.getRed()) + 0.7152 * linear(c.getGreen()) + 0.0722 * linear(c.getBlue());
    }

    private static double linear(double channel) {
        return channel <= 0.03928 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
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
