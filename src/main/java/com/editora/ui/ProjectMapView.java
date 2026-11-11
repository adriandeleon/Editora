package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Popup;
import javafx.util.Duration;
import javafx.util.StringConverter;

import com.editora.git.GitFileStatus;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Spatial navigator for a project. Native JavaFX controls own filtering and zoom; the hierarchy itself is
 * drawn on a {@link Canvas}. Node context menus reuse the Project tree's file-management actions.
 */
final class ProjectMapView extends VBox {

    /** Stops kept for Back/Forward; the oldest are dropped beyond this. */
    static final int MAX_SELECTION_HISTORY = MapHistory.LIMIT;

    private static final double LOADING_NOTICE_MILLIS = 200;

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
    /** Files with errors or warnings (Map code browser round 2). */
    private final ToggleButton problemsFilter = filterButton("project.map.filter.problems");
    /** The Problems window's diagnostics, last given; counted again for a new root. */
    private java.util.Map<Path, List<com.editora.text.LspDiagnostic>> problemSource = java.util.Map.of();
    // The three session options and the two output actions live in the options (⋯) menu, not in the filter row.
    private final CheckMenuItem hideOpenNotes = optionItem("project.map.filter.hideOpenNotes", false);
    private final CheckMenuItem keepZoomOnOpen = optionItem("project.map.navigation.keepZoom", true);
    private final CheckMenuItem focusNewColumn = optionItem("project.map.navigation.focusNewColumn", true);
    /** "Always select opened file": the Map selects each file the editor makes active (off by default). */
    private final CheckMenuItem followActiveFile = optionItem("project.map.navigation.followActiveFile", false);
    /** Off: a click only selects a file row; a double-click or Enter opens it (the editor stays behind). */
    private final CheckMenuItem singleClickOpens = optionItem("project.map.navigation.singleClickOpens", true);

    /** "Compact folder chains": a run of single-child folders opens, and is drawn, as one column. */
    private final CheckMenuItem compactChainsOption = optionItem("project.map.layout.compactChains", true);
    /** "Compact rows" (review 3 B15): shorter rows, and each column's controls only when it is in use. */
    private final CheckMenuItem layoutCompactRowsOption = optionItem("project.map.layout.compactRows", false);
    /** "Hide ignored and generated" (review 3 A11): such rows leave the map instead of being dimmed. */
    private final CheckMenuItem layoutHideIgnoredOption = optionItem("project.map.layout.hideIgnored", false);
    /** "Project overview" (review 3 B4): opens the Overview card. */
    private final MenuItem layoutOverviewItem = new MenuItem(tr("project.map.overview.open"));
    /** The card switcher, when the panel gives one: the first item of ⋯ → Open cards. */
    private @Nullable Runnable onSwitchCard;

    /** ⋯ → Project Dependencies: the Dependencies card on the whole project (gap 14). */
    private final MenuItem dependenciesItem = new MenuItem(tr("project.map.deps.projectMenu"));
    /** Which project file uses which, for the Dependencies card (gap 14). */
    private final MapDependencyIndex dependencyIndex = new MapDependencyIndex();
    /** ⋯ → Review Changes: the Changes card on the working tree's changes (Map code browser round 2). */
    private final MenuItem changesItem = new MenuItem(tr("project.map.changes.review"));
    /** ⋯ → Project Code Metrics: the Code Metrics card on the whole project (gap 15). */
    private final MenuItem metricsItem = new MenuItem(tr("project.map.metrics.projectMenu"));
    /** The project's files measured, for the Code Metrics card (gap 15). */
    private final MapMetricsIndex metricsIndex = new MapMetricsIndex();

    private Consumer<Boolean> onCompactRowsChanged = ignored -> {};
    private Consumer<Boolean> onHideIgnoredChanged = ignored -> {};
    /** "Show recent changes" (Map explore position 3): rows tinted by their commits of the last 90 days. */
    private final CheckMenuItem activityRecentChangesOption =
            optionItem("project.map.activity.showRecentChanges", false);

    private Consumer<Boolean> onShowRecentChangesChanged = ignored -> {};
    /** "Show dependencies of the selection" (round 2): the rows the selection uses and that use it, tinted. */
    private final CheckMenuItem dependencyOverlayOption = optionItem("project.map.deps.showOverlay", false);

    private Consumer<Boolean> onShowDependenciesChanged = ignored -> {};
    /** When the overlay last asked for the project's imports to be read again (System.nanoTime), 0 for never. */
    private long dependencyOverlayReadAt;
    /** How often rows changed lately and who owns them (Map explore positions 3, 4). */
    final ProjectMapActivity activity = new ProjectMapActivity(this::activityChanged);
    /** Why rows are dimmed, and the project's facts for the Overview card and the folder rows (review 3 A11, B4). */
    final ProjectMapOrientation orientation = new ProjectMapOrientation(this::layoutOrientationChanged);
    /**
     * Whether the Overview card opens by itself the first time this session shows a folder; false until the owner
     * says (a view nobody configured never opens it by itself).
     */
    private boolean overviewOnFirstShow;
    /** The folders whose Overview card opened by itself this session ({@code PathKeys.key}): once each. */
    private final Set<String> overviewOpenedFor = new HashSet<>();
    /** Whether the Map search leaves ignored files out (the "respect .gitignore" setting): the banner says so. */
    private boolean layoutSearchSkipsIgnored;
    // Where the three options above are remembered (the workspace); see setRememberedOptions.
    private Consumer<Boolean> onSingleClickChanged = ignored -> {};
    private Consumer<Boolean> onCompactChainsChanged = ignored -> {};
    private Consumer<Boolean> onFollowActiveFileChanged = ignored -> {};
    private boolean optionsRemembered;

    private final ComboBox<ProjectMapModel.TypeFilter> typeFilter = new ComboBox<>();
    /** "Hide unmatched": rows the filters would fade are taken off the map, and the badge says how many. */
    private final CheckBox hideUnmatched = new CheckBox(tr("project.map.filter.hideUnmatched"));

    private final Label narrowedBadge = new Label();
    private Consumer<Boolean> onHideUnmatchedChanged = hide -> {};
    /** ⋯ → Flow ▸ the four directions (Map explore, position 17: the selector left the filter row). */
    private final javafx.scene.control.Menu canvasFlowMenu = new javafx.scene.control.Menu(tr("project.map.flow.menu"));

    private final Map<FlowDirection, javafx.scene.control.RadioMenuItem> canvasFlowItems =
            new java.util.EnumMap<>(FlowDirection.class);
    /** ⋯ → Collapse All (position 13). */
    private final MenuItem canvasCollapseAllItem = new MenuItem(tr("project.map.collapseAll"));
    /** ⋯ → Export ▸ PNG… / SVG… (position 16). */
    private final javafx.scene.control.Menu canvasExportMenu =
            new javafx.scene.control.Menu(tr("project.map.export.menu"));

    private final MenuItem canvasExportPngItem = new MenuItem(tr("project.map.export.png"));
    private final MenuItem canvasExportSvgItem = new MenuItem(tr("project.map.export.svg"));
    /** "Reveal active file", beside the breadcrumb and in the ⋯ menu (review 3 B13, E9). */
    private final Button revealActiveButton = new Button();

    private final MenuItem revealActiveItem = new MenuItem(tr("project.map.revealActiveFile"));
    private Runnable onRevealActiveFile = () -> {};
    private final Button backButton = new Button("‹");
    private final Button forwardButton = new Button("›");
    private final MenuItem printButton = new MenuItem(tr("project.map.print"));
    /** The options menu's list of the open cards (review 3, C10). */
    private final javafx.scene.control.Menu openCardsMenu = new javafx.scene.control.Menu(tr("project.map.openCards"));

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
    private final MapSurface surface = new MapSurface(this);
    /** The cards over the map, and what is stuck on them (see {@link MapCards}). */
    final MapCards cards;
    /** How many times each shown declaration is used (Map code browser gap 11), drawn on its row. */
    final MapUsageCounts usages;
    // The card manager's own maps, under the names the tests have long read them by.
    private final Map<Path, ProjectMapPreview> previews;
    private final Map<Path, ProjectMapNotePreview> notePreviews;
    private final Map<ProjectMapPreview, ProjectMapCardStructure> cardStructures;
    private final Map<Path, MapCards.PreviewConnector> previewConnectors;
    /** The thread loads run on: one, so a later load waits behind the one listing. */
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "project-map-loader");
        thread.setDaemon(true);
        return thread;
    });
    /** What the map lists — open folders, row limits, pins — and the listing (Map refactor stage 2). */
    private final MapLoader mapLoader;
    // The loader's own state, under the names the tests have long read them by.
    private final AtomicLong generation;
    /** Directory listings: cached for the folders the Project watcher follows, each one with a timeout. */
    final ListingService listings;
    /** How many queued loads actually began listing (the rest were superseded first). */
    final java.util.concurrent.atomic.AtomicInteger loadsStartedForTest;
    /** The folders the user opened by hand ({@link MapLoader#expanded}). */
    private final Set<Path> expanded;
    /** How many names the current query matched in all, and whether its walk stopped short of the project. */
    private int searchMatchesTotal;

    private boolean searchMatchesPartial;
    /**
     * How many of a search's matches the map opens the folders of and keeps loaded (the best ones), plus the one
     * the results strip stands on; the rest are in {@link #searchResult()} and its list (Map review 3 B1, A9).
     */
    static final int SEARCH_OPEN_LIMIT = 30;

    /** {@link #SEARCH_OPEN_LIMIT}, which a layout test raises to open many branches with one search. */
    private int searchOpenLimit = SEARCH_OPEN_LIMIT;

    /** The current query's whole answer: its files, folders and declarations, best first (review 3 A5, E2). */
    private ProjectMapSearchResult searchResult = ProjectMapSearchResult.EMPTY;
    /** The match the results strip stands on, an index into {@link #searchResult}'s matches; -1 for none (B1). */
    private int resultsCursor = -1;
    /** Told whenever the search's answer or the match the strip stands on changes (the results strip). */
    private Runnable resultsOnChanged = () -> {};
    /** Where the search's own notices go (partial, cut, how many the map opens); the status line by default. */
    private java.util.function.@Nullable Consumer<String> resultsOnNotice;
    /** The file whose Structure column a declaration landing opened (not one the user had open). */
    private @Nullable Path searchStructureOpened;
    /** A declaration the search is landing on, waiting for its file's row and outline. */
    private @Nullable SearchLanding searchPendingSymbol;

    /** A declaration to select once its file is outlined; {@code openCard} also opens its declaration card. */
    private record SearchLanding(Path file, String name, int line, boolean openCard) {}

    /** The query whose first match was last selected, so re-running it does not steal the selection again. */
    private String searchSelectionQuery = "";
    /** The selection when the current query was started: put in the history when the query is cleared (B2). */
    private @Nullable Path searchReturnSelection;
    /** The row the current query selected by itself (its first match), as opposed to one the user picked. */
    private @Nullable Path searchSelected;

    private final Label loadingLabel = new Label(tr("project.map.loading"));
    private final javafx.animation.PauseTransition loadingDelay =
            new javafx.animation.PauseTransition(Duration.millis(LOADING_NOTICE_MILLIS));
    /** Back and Forward: the Map's places and the cards the code was followed through, in one list. */
    final MapHistory<ProjectMapPreview> history;

    @Nullable
    Path root;

    private boolean disposed;
    /**
     * Whether the Map's window is showing ({@link #setShowing}). While it is not, nothing is listed, no card is
     * refreshed or polled and row states are not snapshotted: the window's scene outlives {@code hide()}, so
     * being in a scene no longer means being on screen. True for a view used outside the Map window (tests).
     */
    boolean active = true;
    /** Row states changed while the window was hidden: the next show snapshots them once. */
    private boolean showingStatesStale;
    /** Something asked for a paint while the window was hidden. */
    boolean dataRepaintWhileHidden;
    /** Whether {@link #setOpenFiles} and {@link #setMarkerCandidates} were given, so rows are looked up from them. */
    private boolean showingOpenFilesGiven;

    private boolean showingMarkersGiven;
    /** The Project "show hidden files" setting: each column's default, and what the loader lists. */
    boolean showHiddenDefault = true;

    private boolean loadInFlight;
    private boolean announceLimit;
    private boolean announceMatches;
    private boolean wasCapped;
    java.util.function.Supplier<java.util.Collection<Path>> openFiles = List::of;
    java.util.function.Supplier<java.util.Collection<Path>> markerCandidates = List::of;
    private java.util.function.BiConsumer<Boolean, Boolean> onNavigationChanged = (keepZoom, focusColumn) -> {};
    private String query = "";
    private Map<Path, GitFileStatus> gitStatus = Map.of();
    private Set<Path> gitChangedDirectories = Set.of();
    private Runnable onExpandedChanged = () -> {};
    private boolean navigatingHistory;
    /** Set while the search lands on its first match by itself, so that landing is recorded as one. */
    private boolean historySearchLanding;
    /** Whether the search's current landing is its own (the first match of a typed query), not a step to a match. */
    private boolean historySearchAuto;

    private Runnable onClearSearch = () -> {};
    private Runnable onLeaveMap = () -> {};
    Consumer<ProjectMapModel.Entry> onRenameEntry = entry -> {};
    Consumer<ProjectMapModel.Entry> onDeleteEntry = entry -> {};
    private @Nullable Path pendingSelection;
    private Consumer<FlowDirection> onFlowChanged = ignored -> {};
    private Consumer<ProjectMapOutput> onPrint = ignored -> {};
    private Consumer<ProjectMapOutput> onExportPdf = ignored -> {};
    private ProjectMapPreview.@Nullable MarkerActions previewMarkerActions;
    /** The map area: the surface, the connector canvas, the overlays and the cards over them. */
    private final StackPane canvasHost = new StackPane() {
        @Override
        protected void layoutChildren() {
            super.layoutChildren();
            if (cards != null) {
                // The pass would lay the children out next: done here first — the surface may pan the cards (its
                // first fit), a card may settle — so the connectors are drawn once, after all of it (review 3, D5).
                for (Node child : List.copyOf(getChildren())) {
                    if (child instanceof javafx.scene.Parent parent) {
                        parent.layout();
                    }
                }
                cards.flushConnectors();
                bearingsChanged(); // a card moved, resized or came: remembered a moment later (B6)
                if (usages != null) {
                    usages.shown(usagesFiles(), ProjectMapView.this::usagesOutline); // a column opened or closed
                }
            }
        }
    };
    /** The open structure columns and the outlines behind them. */
    final ProjectMapStructure structure;

    private java.util.function.BiConsumer<Path, Integer> onOpenAt = (file, line) -> {};
    private java.util.function.BiConsumer<MapLibrarySources.Library, Integer> onOpenLibrary = (library, line) -> {};
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
        this.mapLoader = new MapLoader(loaderHost(), loader::submit, Platform::runLater, System::currentTimeMillis);
        this.generation = mapLoader.generation;
        this.listings = mapLoader.listings;
        this.loadsStartedForTest = mapLoader.loadsStartedForTest;
        this.expanded = mapLoader.expanded;
        this.onOpenFile = onOpenFile;
        this.isOpen = isOpen == null ? path -> false : isOpen;
        this.isModified = isModified == null ? path -> false : isModified;
        this.previewContent = previewContent == null ? path -> null : previewContent;
        this.structure = new ProjectMapStructure(this::previewContentFor);
        structure.setOnFileChanged(this::structureChanged);
        getStyleClass().add("project-map-view");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(4);

        cards = new MapCards(cardsHost(), structure, canvasHost);
        usages = new MapUsageCounts(cards.links::source, file -> usagesFiles().contains(file), this::usagesChanged);
        cards.setUsages(usages);
        history = new MapHistory<>(cards::isOpen, ProjectMapPreview::caretOffset, ProjectMapPreview::fileLineAt);
        // Back and Forward typed on the map walk its history, not the editor's (a card's own handler is asked first).
        getProperties()
                .put(com.editora.command.KeyDispatcher.HANDLED_COMMANDS, (Predicate<String>) this::historyCommand);
        previews = cards.previews;
        notePreviews = cards.notePreviews;
        cardStructures = cards.cardStructures;
        previewConnectors = cards.previewConnectors;
        workingSets = new ProjectMapWorkingSets(this);
        trails = new ProjectMapTrails(this);
        FlowPane filters = buildFilters();
        HBox navigation = buildNavigation();
        buildCanvasHost();
        getChildren().addAll(filters, navigation, canvasHost);
        VBox.setVgrow(getChildren().get(2), Priority.ALWAYS);
        surface.setOnActivate(this::activate);
        surface.setOnCloseColumn(this::closeColumn);
        surface.setOnPreview(this::toggleFileCard);
        surface.setOnStructure(this::toggleStructure);
        surface.setOnNotesPreview(this::cardsShowNotes);
        surface.onTogglePreview = this::togglePreview;
        surface.setOnSelectionChanged(this::selectionChanged);
        surface.overviewCards = cards::visibleCards;
        surface.onOverviewCard = cards::bringIntoView;
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
        // Right-click (or the Menu key) on either lists the places, so "five places back" is one pick (B5).
        backButton.setOnContextMenuRequested(event -> historyShowMenu(backButton, event));
        forwardButton.setOnContextMenuRequested(event -> historyShowMenu(forwardButton, event));
        breadcrumbs.getStyleClass().add("project-map-breadcrumbs");
        breadcrumbs.setMinWidth(0);
        breadcrumbs.widthProperty().addListener((obs, old, value) -> fitBreadcrumbs());
        for (Label label : List.of(breadcrumbEllipsis, breadcrumbEllipsisSeparator)) {
            label.getStyleClass().add("project-map-breadcrumb-separator");
            label.setMinWidth(Region.USE_PREF_SIZE);
        }

        // Where am I? The file being edited, selected and brought into view (review 3 B13, E9).
        revealActiveButton.setGraphic(Icons.locateFile());
        revealActiveButton.getStyleClass().addAll("project-map-nav-button", "project-map-reveal-active");
        revealActiveButton.setMinWidth(Region.USE_PREF_SIZE);
        Icons.name(revealActiveButton, tr("project.map.revealActiveFile"));
        revealActiveButton.setOnAction(event -> onRevealActiveFile.run());
        revealActiveItem.setGraphic(Icons.locateFile());
        revealActiveItem.setOnAction(event -> onRevealActiveFile.run());

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
        // Each entry carries a glyph, the check marks of the options sitting in their own column beside it.
        keepZoomOnOpen.setGraphic(Icons.keepZoom());
        focusNewColumn.setGraphic(Icons.focusNewColumn());
        singleClickOpens.setGraphic(Icons.pointer());
        compactChainsOption.setGraphic(Icons.compactChains());
        layoutCompactRowsOption.setGraphic(Icons.compactRows());
        layoutHideIgnoredOption.setGraphic(Icons.hidden());
        activityRecentChangesOption.setGraphic(Icons.recent());
        dependencyOverlayOption.setGraphic(Icons.dependencies());
        hideOpenNotes.setGraphic(Icons.notesHidden());
        followActiveFile.setGraphic(Icons.fileSelected());
        canvasFlowMenu.setGraphic(Icons.flow());
        layoutOverviewItem.setGraphic(Icons.overview());
        dependenciesItem.setGraphic(Icons.dependencies());
        metricsItem.setGraphic(Icons.metrics());
        changesItem.setGraphic(Icons.diff());
        openCardsMenu.setGraphic(Icons.cards());
        canvasCollapseAllItem.setGraphic(Icons.collapseAll());
        workingSets.menu().setGraphic(Icons.layers());
        trails.menu().setGraphic(Icons.trail());
        printButton.setGraphic(Icons.print());
        exportPdfButton.setGraphic(Icons.exportDocument());
        canvasExportMenu.setGraphic(Icons.image());
        options.getItems()
                .addAll(
                        keepZoomOnOpen,
                        focusNewColumn,
                        singleClickOpens,
                        compactChainsOption,
                        layoutCompactRowsOption,
                        layoutHideIgnoredOption,
                        activityRecentChangesOption,
                        dependencyOverlayOption,
                        hideOpenNotes,
                        followActiveFile,
                        canvasFlowMenu,
                        new SeparatorMenuItem(),
                        revealActiveItem,
                        layoutOverviewItem,
                        dependenciesItem,
                        metricsItem,
                        changesItem,
                        openCardsMenu,
                        canvasCollapseAllItem,
                        workingSets.menu(),
                        trails.menu(),
                        new SeparatorMenuItem(),
                        printButton,
                        exportPdfButton,
                        canvasExportMenu);
        canvasCollapseAllItem.setOnAction(event -> canvasCloseOthers(null, false));
        canvasExportMenu.getItems().setAll(canvasExportPngItem, canvasExportSvgItem);
        canvasExportMenu.setDisable(true);
        canvasExportPngItem.setOnAction(event -> canvasExport(true));
        canvasExportSvgItem.setOnAction(event -> canvasExport(false));
        openCardsMenu.setOnShowing(event -> fillOpenCardsMenu());
        fillOpenCardsMenu(); // never empty: a submenu with no items does not open
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
        singleClickOpens.selectedProperty().addListener((obs, old, selected) -> surface.singleClickOpens = selected);
        compactChainsOption.selectedProperty().addListener((obs, old, selected) -> layoutCompactChanged(selected));
        layoutCompactRowsOption.selectedProperty().addListener((obs, old, selected) -> {
            surface.setCompactRows(selected);
            onCompactRowsChanged.accept(selected);
        });
        layoutHideIgnoredOption.selectedProperty().addListener((obs, old, selected) -> {
            surface.setHideIgnored(selected);
            onHideIgnoredChanged.accept(selected);
        });
        layoutOverviewItem.setOnAction(event -> openOverview());
        dependenciesItem.setOnAction(event -> {
            Path current = root;
            if (current != null) {
                openDependencies(current, null);
            }
        });
        metricsItem.setOnAction(event -> {
            Path current = root;
            if (current != null) {
                openMetrics(current);
            }
        });
        changesItem.setOnAction(event -> openChanges(null));
        activity.setOnExplain(message -> onStatus.accept(message)); // why no heat shows (Map review 4 B11)
        activityRecentChangesOption.selectedProperty().addListener((obs, old, selected) -> {
            activity.setHeat(selected);
            onShowRecentChangesChanged.accept(selected);
        });
        dependencyOverlayOption.selectedProperty().addListener((obs, old, selected) -> {
            dependencyOverlayReadAt = 0; // turned on: read the imports afresh
            dependencyOverlayUpdate(true);
            onShowDependenciesChanged.accept(selected);
        });

        HBox row = new HBox(3, backButton, forwardButton, breadcrumbs, revealActiveButton, help, options);
        row.getStyleClass().add("project-map-navigation");
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(breadcrumbs, Priority.ALWAYS);
        updateNavigation();
        return row;
    }

    /**
     * The options menu's Open cards: one item per open card, the most recently used first, that brings the card into
     * view (review 3, C10) — cards stay where they were opened, and the map may have moved far from them.
     */
    private void fillOpenCardsMenu() {
        List<MenuItem> items = new ArrayList<>();
        Runnable switcher = onSwitchCard;
        if (switcher != null && !cards.visibleCards().isEmpty()) {
            MenuItem switchItem = new MenuItem(tr("project.map.switch.menu"));
            switchItem.setOnAction(event -> switcher.run());
            items.add(switchItem);
            items.add(new SeparatorMenuItem());
        }
        for (MapCards.OpenCard card : cards.openCards()) {
            String label = card.note() ? tr("project.map.openCards.notes", card.label()) : card.label();
            MenuItem item = new MenuItem(label, card.note() ? Icons.notes() : null);
            item.setMnemonicParsing(false);
            item.setDisable(!card.node().isVisible()); // hidden by "Hide unmatched" or "Hide all open Personal Notes"
            item.setOnAction(event -> cards.bringIntoView(card.node()));
            items.add(item);
        }
        if (items.isEmpty()) {
            MenuItem none = new MenuItem(tr("project.map.openCards.none"));
            none.setDisable(true);
            items.add(none);
        }
        openCardsMenu.getItems().setAll(items);
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
        if (!compact && mapLoader.keepLoadedFoldersOpen()) {
            onExpandedChanged.run();
        }
        surface.setCompactChains(compact);
        reload();
    }

    /** Whether "Compact rows" is on: shorter rows, and column controls only on the column in use (review 3 B15). */
    boolean isCompactRows() {
        return layoutCompactRowsOption.isSelected();
    }

    void setCompactRows(boolean compact) {
        layoutCompactRowsOption.setSelected(compact);
    }

    /** Told each time "Compact rows" changes, for the workspace to remember it. */
    void setOnCompactRowsChanged(@Nullable Consumer<Boolean> callback) {
        onCompactRowsChanged = callback == null ? ignored -> {} : callback;
    }

    /**
     * Whether "Hide ignored and generated" is on: gitignored, dependency, vendored and generated rows are left out
     * instead of dimmed (review 3 A11).
     */
    boolean isHideIgnored() {
        return layoutHideIgnoredOption.isSelected();
    }

    void setHideIgnored(boolean hide) {
        layoutHideIgnoredOption.setSelected(hide);
    }

    /** Told each time "Hide ignored and generated" changes, for the workspace to remember it. */
    void setOnHideIgnoredChanged(@Nullable Consumer<Boolean> callback) {
        onHideIgnoredChanged = callback == null ? ignored -> {} : callback;
    }

    /**
     * Whether "Show recent changes" is on: rows are tinted by how many commits of the last 90 days touched them, and
     * their tooltips say how many and whose was last (Map explore position 3).
     */
    boolean isShowRecentChanges() {
        return activityRecentChangesOption.isSelected();
    }

    void setShowRecentChanges(boolean show) {
        activityRecentChangesOption.setSelected(show);
    }

    /** Told each time "Show recent changes" changes, for the workspace to remember it. */
    void setOnShowRecentChangesChanged(@Nullable Consumer<Boolean> callback) {
        onShowRecentChangesChanged = callback == null ? ignored -> {} : callback;
    }

    /**
     * Whether "Show dependencies of the selection" is on: the rows the selected file or folder uses, and those that
     * use it, are tinted (round 2).
     */
    boolean isShowDependencies() {
        return dependencyOverlayOption.isSelected();
    }

    void setShowDependencies(boolean show) {
        dependencyOverlayOption.setSelected(show);
    }

    /** Told each time "Show dependencies of the selection" changes, for the workspace to remember it. */
    void setOnShowDependenciesChanged(@Nullable Consumer<Boolean> callback) {
        onShowDependenciesChanged = callback == null ? ignored -> {} : callback;
    }

    /**
     * Works the dependency overlay out for the selection from the imports last read, and — when {@code reread} and
     * the last reading is a few seconds old — reads the changed files again and works it out once more. Says on the
     * status line how many files the selection uses and how many use it, when that changed.
     */
    private void dependencyOverlayUpdate(boolean reread) {
        Path current = root;
        if (!dependencyOverlayOption.isSelected() || current == null || disposed) {
            surface.setDependencyOverlay(MapDependencyOverlay.NONE);
            return;
        }
        ProjectMapModel.Entry entry = surface.selectedEntry().orElse(null);
        ProjectMapModel.Symbol symbol = entry == null ? null : entry.symbol();
        Path subject = entry == null || entry.isPlaceholder() ? null : symbol != null ? symbol.file() : entry.path();
        boolean folder = entry != null && entry.directory() && symbol == null;
        ProjectMapDependencies read = dependencyIndex.latest();
        MapDependencyOverlay before = surface.dependencyOverlay();
        MapDependencyOverlay overlay = subject == null || read == null
                ? MapDependencyOverlay.NONE
                : MapDependencyOverlay.of(read, current, subject, folder);
        surface.setDependencyOverlay(overlay);
        Path at = overlay.subject();
        if (at != null
                && (!at.equals(before.subject())
                        || overlay.uses() != before.uses()
                        || overlay.users() != before.users())) {
            Path name = at.getFileName();
            String label = name == null ? at.toString() : name.toString();
            onStatus.accept(
                    overlay.read()
                            ? tr("project.map.deps.overlayStatus", label, overlay.uses(), overlay.users())
                            : tr("project.map.deps.overlayNotRead", label));
        }
        long now = System.nanoTime();
        if (reread
                && subject != null
                && !dependencyIndex.loading()
                && (read == null || now - dependencyOverlayReadAt > DEPENDENCY_OVERLAY_REREAD_NANOS)) {
            dependencyOverlayReadAt = now;
            dependencyIndex.load(found -> dependencyOverlayUpdate(false));
        }
    }

    /** How old the imports read may be before a new selection reads the changed files again. */
    private static final long DEPENDENCY_OVERLAY_REREAD_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(5);

    /** The window's Git service: the recent changes are read on its activity lane. Null: none are read. */
    void setActivityGit(com.editora.git.@Nullable GitService service) {
        activity.setGit(service);
    }

    /** The history or the owners arrived: the rows and an open Overview card follow. */
    private void activityChanged() {
        surface.repaint();
        ProjectMapMetricsCard metrics = cards.metricsCard();
        if (metrics != null) {
            metrics.setChanges(activity.changes());
        }
        ProjectMapOverviewCard card = cards.overviewCard();
        if (card != null) {
            card.setActivity(activity.summary());
        }
    }

    /** The Overview card opened: it lists the most changed folders and the owners of the top folders. */
    private void activityOverviewShown(ProjectMapOverviewCard card, ProjectMapOverview.Facts facts) {
        activity.setOverviewWanted(true);
        activity.loadOverviewOwners(
                facts.topFolders().stream().map(ProjectMapOverview.Folder::name).toList());
        card.setActivity(activity.summary());
    }

    /**
     * Whether the Overview card opens by itself the first time this session shows a folder — every session, so a
     * Map opened afresh starts from the project's facts; once per folder, so neither a window closed and opened
     * again nor a folder returned to brings it up again. ⋯ → Project overview opens it at any time.
     */
    void setOverviewOnFirstShow(boolean opens) {
        overviewOnFirstShow = opens;
        if (opens) {
            layoutMaybeOpenOverview();
        }
    }

    /**
     * Where the Overview card and the folder tooltips get the project's file list (called off the FX thread): the
     * Map search's list, when the panel has one. Without it the view lists the project itself once.
     */
    void setOverviewFileSource(@Nullable Function<Path, com.editora.search.ProjectFileList.@Nullable Snapshot> source) {
        orientation.setFileSource(source);
        dependencyIndex.setFileSource(source);
        metricsIndex.setFileSource(source);
    }

    /** Whether the Map search leaves ignored files out: its "no files match" banner then says so (review 3 A11). */
    void setSearchSkipsIgnored(boolean skips) {
        layoutSearchSkipsIgnored = skips;
    }

    /** ⋯ → Project overview: the Overview card, with the project's facts worked out again. */
    void openOverview() {
        Path current = root;
        if (current == null) {
            return;
        }
        orientation.loadFacts(true, facts -> layoutShowOverview(current, facts));
    }

    /**
     * Opens the Dependencies card (gap 14) on {@code path} — the project, a folder or a file of it — beside {@code
     * beside} (the card it was asked from) when given, and reads the project's imports again for it: only the files
     * changed since the last reading are read.
     */
    void openDependencies(Path path, @Nullable Region beside) {
        Path current = root;
        if (current == null || disposed) {
            return;
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(current)) {
            return;
        }
        String rel = current.relativize(normalized).toString().replace('\\', '/');
        boolean folder = rel.isEmpty() || java.nio.file.Files.isDirectory(normalized);
        ProjectMapDependencyCard card = cards.openDependencyCard(current, rel, folder, beside, made -> {
            made.setOnReveal(this::revealPath);
            made.setOnRefresh(() -> readDependencies(made));
        });
        readDependencies(card);
        card.focusContent();
    }

    private void readDependencies(ProjectMapDependencyCard card) {
        card.setReading();
        dependencyIndex.load(found -> {
            if (cards.dependencyCard() == card) {
                card.setDependencies(found);
            }
        });
    }

    /** What ⋯ → Open cards → Switch Card… does: the panel's card switcher. */
    void setOnSwitchCard(@Nullable Runnable action) {
        onSwitchCard = action;
    }

    /** Where the code cards read which of the editor's reading aids to show (round 2): the editor's settings. */
    void setCardAids(java.util.function.Supplier<MapCardAids> aids) {
        cards.setCardAids(aids);
    }

    /** The editor's settings changed: the code cards show the reading aids they name now. */
    void applyCardAids() {
        cards.applyCardAids();
    }

    /** The open Dependencies card, or null. */
    @Nullable
    ProjectMapDependencyCard dependencyCard() {
        return cards.dependencyCard();
    }

    /**
     * Opens the Code Metrics card (gap 15) on the folder {@code folder} — the project or a folder of it — and measures
     * the project again for it (only the files changed since the last time); the last 90 days' history is read for its
     * Changes and Hotspot columns.
     */
    void openMetrics(Path folder) {
        Path current = root;
        if (current == null || disposed) {
            return;
        }
        Path normalized = folder.toAbsolutePath().normalize();
        if (!normalized.startsWith(current)) {
            return;
        }
        String rel = current.relativize(normalized).toString().replace('\\', '/');
        ProjectMapMetricsCard card = cards.openMetricsCard(current, rel, made -> {
            made.setOnReveal(this::revealPath);
            made.setOnRefresh(() -> readMetrics(made));
            made.setOnDependencies(path -> openDependencies(path, null));
        });
        activity.setMetricsWanted(true);
        card.setChanges(activity.changes());
        readMetrics(card);
        card.focusContent();
    }

    private void readMetrics(ProjectMapMetricsCard card) {
        card.setReading();
        metricsIndex.load(found -> {
            if (cards.metricsCard() == card) {
                card.setMetrics(found);
            }
        });
    }

    /** The open Code Metrics card, or null. */
    @Nullable
    ProjectMapMetricsCard metricsCard() {
        return cards.metricsCard();
    }

    /**
     * Opens the Changes card (Map code browser round 2) on the working tree's changes against HEAD below the Map's
     * folder, with {@code select} — a changed file, or a folder whose first changed file is wanted — picked, or brings
     * the open one up on it; Git is asked again each time.
     */
    void openChanges(@Nullable Path select) {
        ProjectMapChangesCard card = changesCardOpened(select);
        if (card != null) {
            card.focusContent();
        }
    }

    private @Nullable ProjectMapChangesCard changesCardOpened(@Nullable Path select) {
        Path current = root;
        if (current == null || disposed) {
            return null;
        }
        ProjectMapChangesCard card = cards.openChangesCard(current, made -> {
            made.setOnOpenLine((file, line) -> cards.openPlaceCard(null, file, line, null));
            made.setOnOpenInEditor((file, line) -> {
                FollowCode code = followCode;
                if (code != null) {
                    code.follow(file, line, 0, null, ""); // no command: the file at the line
                }
            });
            made.setOnCompare(file -> {
                FollowCode code = followCode;
                if (code != null) {
                    code.compareWithHead(file);
                }
            });
            made.setOnRefresh(() -> readChanges(made));
            made.setOnStatus(message -> onStatus.accept(message));
        });
        card.show(current, select == null ? null : select.toAbsolutePath().normalize());
        readChanges(card);
        return card;
    }

    private void readChanges(ProjectMapChangesCard card) {
        Path folder = card.root();
        FollowCode code = followCode;
        if (folder == null) {
            return;
        }
        if (code == null) {
            card.setChanges(com.editora.git.GitChanges.Result.UNAVAILABLE);
            return;
        }
        code.workingChanges(folder, found -> {
            if (cards.changesCard() == card && folder.equals(card.root())) {
                card.setChanges(found);
            }
        });
    }

    /**
     * Next or Previous Change typed on the Map: the next hunk of the working tree's changes in the Changes card,
     * opened for it when it is not open.
     */
    void stepChange(boolean forward) {
        ProjectMapChangesCard card = cards.changesCard();
        if (card == null) {
            card = changesCardOpened(null);
            if (card == null) {
                return;
            }
        }
        card.step(forward);
        card.focusContent();
    }

    /** The open Changes card, or null. */
    @Nullable
    ProjectMapChangesCard changesCard() {
        return cards.changesCard();
    }

    /** The open Overview card, or null. */
    @Nullable
    ProjectMapOverviewCard overviewCard() {
        return cards.overviewCard();
    }

    /** The project's facts (README, builds, modules, folder counts) once worked out; null before. */
    ProjectMapOverview.@Nullable Facts overviewFacts() {
        return orientation.facts();
    }

    private void layoutShowOverview(Path forRoot, ProjectMapOverview.Facts facts) {
        if (disposed || !com.editora.config.PathKeys.samePath(forRoot, root)) {
            return;
        }
        activityOverviewShown(cards.openOverviewCard(forRoot, facts, this::revealPath, onOpenFile), facts);
    }

    /** The Overview card opens by itself the first time this session shows a folder ({@link #setOverviewOnFirstShow}). */
    private void layoutMaybeOpenOverview() {
        Path current = root;
        if (!overviewOnFirstShow || !active || current == null || disposed || surface.isEmpty()) {
            return;
        }
        if (!overviewOpenedFor.add(com.editora.config.PathKeys.key(current))) {
            return; // this folder's card opened once this session already
        }
        orientation.loadFacts(false, facts -> layoutShowOverview(current, facts));
    }

    /** New rows are on the map: why each is dimmed is worked out, and the project's facts once per root. */
    void layoutEntriesShown(List<ProjectMapModel.Entry> shown) {
        if (root == null || shown.isEmpty()) {
            return;
        }
        orientation.classify(shown);
        activity.classify(shown);
        if (active) {
            orientation.loadFacts(false, null);
            layoutMaybeOpenOverview();
        }
    }

    /** Dimmed rows or the project's facts arrived: the rows, badges and tooltips follow. */
    private void layoutOrientationChanged() {
        Path current = root;
        ProjectMapOverview.Facts facts = orientation.facts();
        Set<Path> modules = new HashSet<>();
        if (facts != null && current != null) {
            for (String folder : facts.moduleFolders()) {
                if (!folder.isEmpty()) {
                    modules.add(current.resolve(folder));
                }
            }
        }
        surface.layoutOrientationChanged(orientation.version(), Set.copyOf(modules));
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

    /** Whether the Map selects (revealing it) each file the editor makes active: "Always select opened file". */
    boolean isFollowActiveFile() {
        return followActiveFile.isSelected();
    }

    void setFollowActiveFile(boolean follow) {
        followActiveFile.setSelected(follow);
    }

    /** What the breadcrumb's crosshair and ⋯ Reveal Active File do: the panel selects the file being edited. */
    void setOnRevealActiveFile(Runnable action) {
        onRevealActiveFile = action == null ? () -> {} : action;
    }

    /** The selected row's path, or null (the Map window asks whether anything but the root is chosen). */
    @Nullable
    Path windowSelectedPath() {
        return surface.selectedPath();
    }

    /** Whether a single click on a file row opens it (default); otherwise it selects and a double-click opens. */
    boolean isSingleClickOpens() {
        return singleClickOpens.isSelected();
    }

    void setSingleClickOpens(boolean opens) {
        singleClickOpens.setSelected(opens);
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

        javafx.scene.control.ToggleGroup flows = new javafx.scene.control.ToggleGroup();
        for (FlowDirection flow : FlowDirection.values()) {
            javafx.scene.control.RadioMenuItem item = new javafx.scene.control.RadioMenuItem(flowName(flow));
            item.setToggleGroup(flows);
            item.setSelected(flow == DEFAULT_FLOW);
            item.setOnAction(event -> canvasApplyFlow(flow, true));
            canvasFlowItems.put(flow, item);
            canvasFlowMenu.getItems().add(item);
        }

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
            problemsFilter.setSelected(false);
            // The search is a filter too and goes with the rest; "Hide all open Personal Notes" is a view
            // option in the ⋯ menu, not a filter, and is left as it is (B23).
            if (!query.isBlank()) {
                onClearSearch.run();
            }
            typeFilter.setValue(ProjectMapModel.TypeFilter.ALL);
            surface.clearColumnFilters();
            updateFilters();
            if (root != null) {
                reload(); // the columns' "Show hidden" choices went back to the setting
            }
        });

        for (ToggleButton button :
                List.of(openFilter, modifiedFilter, gitFilter, bookmarksFilter, personalNotesFilter, problemsFilter)) {
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
                problemsFilter,
                typeFilter,
                hideUnmatched,
                narrowedBadge,
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

    private void buildCanvasHost() {
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
        StackPane host = canvasHost;
        host.getChildren()
                .setAll(surface, cards.previewConnectorCanvas, noMatchesLabel, zoomBar, surface.overviewLayer());
        // Cards are added after these and raised to the front when used. The zoom bar, the overview and the two
        // notices are drawn and picked above every card whatever the child order (C8); only the zoom bar's
        // buttons take clicks, not the gaps between them (B3).
        for (Node overlay : List.of(zoomBar, noMatchesLabel, loadingLabel)) {
            overlay.setViewOrder(-1);
        }
        zoomBar.setPickOnBounds(false);
        surface.layoutOnZoomBarGiveWay = giveWay -> {
            if (zoomBar.isMouseTransparent() != giveWay) {
                zoomBar.setMouseTransparent(giveWay);
                zoomBar.setOpacity(giveWay ? 0.15 : 1);
            }
        };
        // Tab past a card's last control: the scene's traversal wraps to the window's first control, the search
        // field. Keep the keyboard on the map instead (C18).
        host.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.TAB
                    && !event.isShiftDown()
                    && !event.isControlDown()
                    && !event.isAltDown()
                    && !event.isMetaDown()
                    && event.getTarget() instanceof Node target
                    && MapCards.holdsFocus(target)) {
                Platform.runLater(cards::keepFocusOnMap);
            }
        });
        // Ctrl+Shift+arrows: the keyboard goes to the nearest card that way, from a card or from the map's selected
        // row (gap 13). Claimed here, so a keymap binding of the chord does not take it from the cards or the map.
        host.getProperties().put(com.editora.command.KeyDispatcher.CLAIMED_KEYS, MapCards.TOWARD_KEYS);
        host.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            MapCards.Toward toward = MapCards.toward(event);
            if (toward != null
                    && event.getTarget() instanceof Node target
                    && cards.focusToward(target, toward, towardOrigin(target))) {
                event.consume();
            }
        });
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
        host.widthProperty().addListener((obs, old, value) -> cards.hostResized(value.doubleValue(), host.getHeight()));
        host.heightProperty().addListener((obs, old, value) -> cards.hostResized(host.getWidth(), value.doubleValue()));
        // Cards survive a switch to the Tree; whatever changed meanwhile is picked up when the Map returns.
        host.sceneProperty().addListener((obs, old, scene) -> {
            if (scene != null) {
                scheduleCardRefresh();
            }
        });
    }

    private static final double ZOOM_BAR_MARGIN = 8;

    /**
     * Where Ctrl+Shift+arrows start from on the map ({@code {x, y, w, h}}), when {@code target} is on it: its selected
     * row as drawn, or the middle of the map area when no row is selected or it is out of sight; null when {@code
     * target} is not the map (a card, or what is stuck on one, starts from itself).
     */
    private double @Nullable [] towardOrigin(Node target) {
        for (Node node = target; node != null; node = node.getParent()) {
            if (node == surface) {
                Path selected = surface.selectedPath();
                MapSurface.NodeBox box = selected == null ? null : surface.nodeBox(selected);
                return box != null
                        ? new double[] {box.x(), box.y(), box.width(), box.height()}
                        : new double[] {canvasHost.getWidth() / 2, canvasHost.getHeight() / 2, 0, 0};
            }
        }
        return null;
    }

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
        String message = resultsBannerText();
        // The search skips what .gitignore leaves out, while the map lists it dimmed (review 3 A11).
        noMatchesLabel.setText(
                !query.isBlank() && layoutSearchSkipsIgnored
                        ? message + " " + tr("project.map.noMatches.ignoredNotSearched")
                        : message);
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
            helpPopup.getContent().add(ProjectMapHelp.build());
            helpPopup.setOnHidden(event -> helpHiddenAt = System.nanoTime());
        }
        javafx.geometry.Bounds bounds = anchor.localToScreen(anchor.getBoundsInLocal());
        if (bounds != null) {
            helpPopup.show(anchor, bounds.getMinX(), bounds.getMaxY() + 4);
        }
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

    private static String flowName(FlowDirection flow) {
        return tr("project.map.flow.name." + flow.name().toLowerCase(java.util.Locale.ROOT));
    }

    /** The flow ticked in ⋯ → Flow. */
    FlowDirection canvasFlow() {
        for (Map.Entry<FlowDirection, javafx.scene.control.RadioMenuItem> item : canvasFlowItems.entrySet()) {
            if (item.getValue().isSelected()) {
                return item.getKey();
            }
        }
        return DEFAULT_FLOW;
    }

    /** ⋯ → Flow's item for {@code flow}; firing it is what picking it does. */
    javafx.scene.control.RadioMenuItem canvasFlowItem(FlowDirection flow) {
        return java.util.Objects.requireNonNull(canvasFlowItems.get(flow));
    }

    /** Ticks {@code flow} and lays the map out along it; {@code picked}: the user chose it, so it is stored. */
    private void canvasApplyFlow(FlowDirection flow, boolean picked) {
        canvasFlowItem(flow).setSelected(true);
        surface.setFlowDirection(flow);
        if (picked) {
            onFlowChanged.accept(flow);
        }
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
        mapLoader.rememberRoot(surface.selectedPath());
        bearingsFlush(); // what the workspace keeps is the folder being left, as it was
        bearingsReady = false;
        trails.rootChanged(); // a trail is the folder's being left
        this.root = normalized;
        cards.closeAll();
        structure.clear();
        Path remembered = mapLoader.setRoot(normalized); // what was open there last time comes back
        clearSearchState();
        wasCapped = false;
        history.clear();
        pendingSelection = normalized;
        setOutputEnabled(false);
        orientation.setRoot(normalized);
        surface.setProblems(new ProjectMapProblems(problemSource, normalized)); // counted below the new root
        dependencyIndex.setRoot(normalized);
        dependencyOverlayReadAt = 0;
        surface.setDependencyOverlay(MapDependencyOverlay.NONE);
        metricsIndex.setRoot(normalized);
        activity.setRoot(normalized);
        surface.resetForRoot();
        if (otherFileSystem) {
            // Until the reload lands the surface would compare the old connection's entries with the new
            // root's paths, and those comparisons throw too.
            surface.setEntries(List.of(), Set.of());
        }
        if (normalized != null) {
            recordSelection(normalized);
            if (remembered != null) {
                pendingSelection = remembered;
            }
            if (active) {
                bearingsRestore(); // shown already: the workspace's state for this folder applies now
            }
        }
        surface.setSelected(normalized);
        updateNavigation();
        reload();
    }

    // --- bearings: what the Map keeps of an exploration across restarts (Project Map review 3 B6) ------------

    /** What the workspace remembered, waiting for the first show of its folder; null once applied or for none. */
    private com.editora.config.@Nullable ProjectMapState bearingsStored;

    private Consumer<com.editora.config.ProjectMapState> bearingsSink = state -> {};
    /** Whether the current folder's state may be written: its first show has restored (or had nothing to restore). */
    private boolean bearingsReady;
    /** The last session's code cards, waiting for the first view of the map (declarations: for their outline). */
    private final List<com.editora.config.ProjectMapState.Card> bearingsPendingCards = new ArrayList<>();

    private final javafx.animation.PauseTransition bearingsDebounce =
            new javafx.animation.PauseTransition(BEARINGS_QUIET);
    private boolean bearingsDebounceArmed;
    /** How soon after a change what the map shows is written to the workspace state (at most this often). */
    private static final Duration BEARINGS_QUIET = Duration.millis(600);

    /**
     * Restores what the workspace remembered of the Map — {@code stored}, for its folder only — the first time the
     * window shows that folder, and hands later states to {@code onChanged} (into the workspace state, which the
     * window writes with its session). Local folders only: a remote folder's paths are not checked from here.
     */
    void setRememberedPlace(
            com.editora.config.@Nullable ProjectMapState stored,
            Consumer<com.editora.config.ProjectMapState> onChanged) {
        bearingsStored = stored;
        bearingsSink = onChanged == null ? state -> {} : onChanged;
        bearingsDebounce.setOnFinished(event -> {
            bearingsDebounceArmed = false;
            bearingsFlush();
        });
    }

    /** Something the workspace keeps changed: written {@link #BEARINGS_QUIET} later, with whatever else changed. */
    void bearingsChanged() {
        if (!bearingsReady || disposed || bearingsDebounceArmed) {
            return;
        }
        bearingsDebounceArmed = true;
        bearingsDebounce.playFromStart();
    }

    /** Writes what the Map shows now to the workspace state (nothing before its folder was first shown). */
    void bearingsFlush() {
        bearingsDebounce.stop();
        bearingsDebounceArmed = false;
        com.editora.config.ProjectMapState state = bearingsReady ? bearingsState() : null;
        if (state != null) {
            bearingsSink.accept(state);
        }
    }

    /**
     * What the Map shows now, as the workspace keeps it: the folders opened by hand, the selection, the Back/Forward
     * places, the zoom and pan and the open code cards (least recently used first). Null without a local folder.
     */
    com.editora.config.@Nullable ProjectMapState bearingsState() {
        Path current = root;
        if (current == null || !com.editora.vfs.Vfs.isLocal(current)) {
            return null;
        }
        com.editora.config.ProjectMapState state = new com.editora.config.ProjectMapState();
        state.setRoot(current.toString());
        state.setExpanded(expanded.stream()
                .map(path -> bearingsRelative(current, path))
                .filter(java.util.Objects::nonNull)
                .filter(relative -> !relative.isEmpty())
                .sorted()
                .toList());
        Path selected = surface.selectedPath();
        String selectedRelative = selected == null ? null : bearingsRelative(current, selected);
        state.setSelected(selectedRelative == null ? "" : selectedRelative);
        List<String> places = new ArrayList<>();
        int index = -1;
        List<Path> historyPlaces = history.places();
        for (int i = 0; i < historyPlaces.size(); i++) {
            String relative = bearingsRelative(current, historyPlaces.get(i));
            if (relative == null) {
                continue;
            }
            if (places.isEmpty() || !places.getLast().equals(relative)) {
                places.add(relative); // a card's stop on a row's place is that place once
            }
            if (i <= history.index()) {
                index = places.size() - 1;
            }
        }
        state.setHistory(places);
        state.setHistoryIndex(index);
        state.setZoom(surface.zoom);
        state.setOffsetX(surface.offsetX);
        state.setOffsetY(surface.offsetY);
        List<com.editora.config.ProjectMapState.Card> open = new ArrayList<>();
        for (MapCards.OpenCard card : cards.openCards().reversed()) {
            String relative = bearingsRelative(current, card.key());
            if (!card.note() && card.node() instanceof ProjectMapPreview code && relative != null) {
                com.editora.config.ProjectMapState.Card kept = new com.editora.config.ProjectMapState.Card(
                        relative,
                        code.getLayoutX(),
                        code.getLayoutY(),
                        code.getWidth(),
                        code.getHeight(),
                        code.contentZoom(),
                        code.structureShown());
                kept.setPinned(cards.pinned(code)); // a pinned card stays pinned across a restart or a working set
                kept.setCaretLine(code.caretFileLine()); // where it was read (gap 9)
                kept.setCaretColumn(code.editor().getCaretColumn());
                kept.setTopLine(code.topFileLine());
                kept.setAnswers(cards.links.savedAnswers(code, current)); // its answers and their edges
                open.add(kept);
            }
        }
        state.setCards(open);
        return state;
    }

    /** {@code path} relative to {@code base} with {@code /} separators ("" for the base), or null outside it. */
    static @Nullable String bearingsRelative(Path base, @Nullable Path path) {
        if (path == null
                || !com.editora.config.PathKeys.sameFileSystem(base, path)
                || !path.normalize().startsWith(base)) {
            return null;
        }
        return base.relativize(path.normalize()).toString().replace('\\', '/');
    }

    /** {@code relative} under {@code base}, or null when it would leave it ({@code ..}) or is not a path. */
    static @Nullable Path bearingsResolve(Path base, @Nullable String relative) {
        if (relative == null) {
            return null;
        }
        try {
            Path resolved = (relative.isEmpty() ? base : base.resolve(relative)).normalize();
            return resolved.startsWith(base) ? resolved : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /** Whether {@code path} — a file, a folder, or a declaration below its file — still exists on disk. */
    private static boolean bearingsExists(Path path) {
        Path file = path;
        while (ProjectMapStructure.isSymbolPath(file) && file.getParent() != null) {
            file = file.getParent();
        }
        return java.nio.file.Files.exists(file);
    }

    /**
     * The first show of the folder the workspace remembers: its open folders, selection and Back/Forward places
     * come back now, pruned of what no longer exists; its zoom and pan replace the first view's fit, and its cards
     * open once the map is laid out ({@link #bearingsRestoreCards}). Once per folder; any later state is written.
     */
    private void bearingsRestore() {
        if (bearingsReady) {
            return;
        }
        Path current = root;
        if (current == null) {
            return;
        }
        bearingsReady = true;
        com.editora.config.ProjectMapState stored = bearingsStored;
        if (stored == null
                || !com.editora.vfs.Vfs.isLocal(current)
                || !current.toString().equals(stored.getRoot())) {
            return;
        }
        bearingsStored = null;
        for (String relative : stored.getExpanded()) {
            Path folder = bearingsResolve(current, relative);
            if (folder != null) {
                expanded.add(folder); // pruned against the disk by the load that follows
            }
        }
        List<Path> places = new ArrayList<>();
        int index = -1;
        for (int i = 0; i < stored.getHistory().size(); i++) {
            Path place = bearingsResolve(current, stored.getHistory().get(i));
            if (place != null
                    && bearingsExists(place)
                    && (places.isEmpty() || !places.getLast().equals(place))) {
                places.add(place);
            }
            if (i <= stored.getHistoryIndex()) {
                index = places.size() - 1;
            }
        }
        history.restore(places, index);
        Path selected = bearingsResolve(current, stored.getSelected());
        viewsRestoredSelection = null;
        if (selected != null && bearingsExists(selected)) {
            pendingSelection = ProjectMapStructure.isSymbolPath(selected) ? bearingsFileOf(selected) : selected;
        }
        if (stored.getZoom() > 0) {
            surface.bearingsViewport = new double[] {stored.getZoom(), stored.getOffsetX(), stored.getOffsetY()};
            // The stored zoom and pan already frame the selection: it is selected when its row lands, not revealed
            // as a place, whose zoom-out and pan would replace them (review 4 A1).
            viewsRestoredSelection = pendingSelection;
        }
        bearingsPendingCards.clear();
        viewsCardThen.clear();
        for (com.editora.config.ProjectMapState.Card card : stored.getCards()) {
            Path key = bearingsResolve(current, card.getPath());
            if (key != null && !key.equals(current) && bearingsExists(key)) {
                bearingsPendingCards.add(card);
            }
        }
        updateNavigation();
    }

    /** The file a declaration's path is below (the path itself for a file). */
    private static Path bearingsFileOf(Path path) {
        Path file = path;
        while (ProjectMapStructure.isSymbolPath(file) && file.getParent() != null) {
            file = file.getParent();
        }
        return file;
    }

    /**
     * Opens the last session's code cards where they were, now that the map is laid out at its restored zoom and
     * pan. A declaration card waits for its file's outline ({@link #bearingsRestoreDeclarations}).
     */
    void bearingsRestoreCards() {
        Path current = root;
        if (disposed || current == null || bearingsPendingCards.isEmpty()) {
            return;
        }
        for (com.editora.config.ProjectMapState.Card card : List.copyOf(bearingsPendingCards)) {
            Path key = bearingsResolve(current, card.getPath());
            if (key == null) {
                bearingsPendingCards.remove(card);
            } else if (ProjectMapStructure.isSymbolPath(key)) {
                structure.ensure(bearingsFileOf(key)); // its card opens when the outline arrives
                bearingsRestoreDeclarations(bearingsFileOf(key));
            } else {
                bearingsPendingCards.remove(card);
                bearingsOpenCard(key, card);
            }
        }
    }

    /** The declaration cards of the last session in {@code file}, opened once its outline is in (or dropped). */
    private void bearingsRestoreDeclarations(Path file) {
        Path current = root;
        // Not while the outline is only asked for: ensure() holds a LOADING placeholder at once, and a declaration
        // looked up in that is "gone" (the restart's declaration cards and a trail stop's were dropped).
        if (disposed
                || current == null
                || bearingsPendingCards.isEmpty()
                || !structure.hasOutline(file)
                || structure.view(file).problem() == ProjectMapStructure.Problem.LOADING) {
            return;
        }
        for (com.editora.config.ProjectMapState.Card card : List.copyOf(bearingsPendingCards)) {
            Path key = bearingsResolve(current, card.getPath());
            if (key != null
                    && ProjectMapStructure.isSymbolPath(key)
                    && bearingsFileOf(key).equals(file)) {
                bearingsPendingCards.remove(card); // a declaration no longer in the outline is not brought back
                bearingsOpenCard(key, card);
            }
        }
    }

    private void bearingsOpenCard(Path key, com.editora.config.ProjectMapState.Card card) {
        Consumer<@Nullable ProjectMapPreview> then = viewsCardThen.remove(card);
        ProjectMapPreview opened = Double.isNaN(card.getX())
                ? cards.viewsOpenCard(key) // a trail stop's: placed beside its row, as a click would place it
                : cards.restoreCard(
                        key,
                        card.getX(),
                        card.getY(),
                        card.getWidth() > 0 ? card.getWidth() : ProjectMapPreview.DEFAULT_WIDTH,
                        card.getHeight() > 0 ? card.getHeight() : ProjectMapPreview.DEFAULT_HEIGHT,
                        card.getTextZoom() > 0 ? card.getTextZoom() : 1.0,
                        card.isStructure());
        if (opened != null && card.isPinned()) {
            opened.setPinned(true);
        }
        Path current = root;
        if (opened != null && current != null) {
            opened.restoreView(card.getCaretLine(), card.getCaretColumn(), card.getTopLine());
            cards.links.restoreAnswers(opened, card.getAnswers(), current); // edges to the cards open now
            cards.links.cardRestored(key, opened); // and those that waited for this one
        }
        if (then != null) {
            then.accept(opened);
        }
    }

    // --- views: working sets and trails (Map explore, views group) ------------------------------------------------

    /** The ⋯ menu's Working sets: named views of the Map, kept per workspace. */
    final ProjectMapWorkingSets workingSets;
    /** The ⋯ menu's Trails: guided tours kept in the project's {@code .editora/map-trails/}. */
    final ProjectMapTrails trails;
    /** What to do with a card asked for by {@link #viewsShowCard} once it opened (null: it could not open). */
    private final Map<com.editora.config.ProjectMapState.Card, Consumer<@Nullable ProjectMapPreview>> viewsCardThen =
            new java.util.IdentityHashMap<>();
    /** A card asked for before its file's row was on the map: opened when the next load lands. */
    private boolean viewsCardsAwaitRows;
    /** Where the views ask for a name or a note: the Map window's overlay, set by the panel. */
    OverlayInput.@Nullable Prompt viewsPrompt;
    /**
     * The selection a restart or a working set brought back with its own zoom and pan (review 4 A1): when its row
     * lands it is selected, not revealed as a place. Compared by identity with {@code pendingSelection}, so any later
     * selection asked for meanwhile is revealed as usual.
     */
    private @Nullable Path viewsRestoredSelection;

    /** The map's columns on screen, {@code {x, y, w, h, 1}} in the map area's pixels (a stop's note keeps off them). */
    List<double[]> viewsColumns() {
        return surface.cardColumns();
    }

    /** Pans the map, and the cards on it, {@code dx} pixels across (a stop's note making room beside its card). */
    void viewsPanAcross(double dx) {
        surface.panBy(dx, 0);
    }

    /** Where working sets and trails ask for a name or a note (the panel gives the Map window's own prompt). */
    void setViewsPrompt(OverlayInput.@Nullable Prompt prompt) {
        viewsPrompt = prompt;
    }

    /** The map area, which the trail bar, the trail editor and a stop's note sheet are put over. */
    StackPane viewsCanvasHost() {
        return canvasHost;
    }

    /** The workspace's working sets, and where a change to them is written (see {@link ProjectMapWorkingSets}). */
    void setRememberedWorkingSets(
            List<com.editora.config.ProjectMapWorkingSet> stored,
            Consumer<List<com.editora.config.ProjectMapWorkingSet>> onChanged) {
        workingSets.setRemembered(stored, onChanged);
    }

    /**
     * Shows {@code state} — a working set — the way a restart shows the last session's: the search is cleared, every
     * card closes, the open folders become the set's, and the set's selection, zoom and pan and cards come back once
     * the map is laid out (missing paths pruned, as a restart prunes them). The Back/Forward places stay; the set's
     * selection is one more. Nothing for another folder's state.
     */
    void viewsApply(com.editora.config.ProjectMapState state) {
        Path current = root;
        if (disposed || current == null || state == null || !current.toString().equals(state.getRoot())) {
            return;
        }
        if (!query.isBlank()) {
            onClearSearch.run();
        }
        bearingsFlush(); // the folder's state as it was, before the set replaces it
        trails.close(); // its stop's card closes with the others: the bar would name a card no longer there (A7)
        cards.closeAll();
        cards.links.forgetRestored();
        bearingsPendingCards.clear();
        viewsCardThen.clear();
        viewsCardsAwaitRows = false;
        expanded.clear();
        expanded.add(current); // the root is always open
        bearingsStored = state;
        bearingsReady = false;
        surface.viewsRefit(); // the next rows apply the set's zoom and pan, then open its cards
        bearingsRestore();
        if (pendingSelection != null) {
            recordSelection(pendingSelection); // a place: Back returns to where the switch was made
        }
        reload();
        onExpandedChanged.run();
        bearingsChanged();
    }

    /**
     * Opens the card of {@code key} — a file, or a declaration below it — through the restart's path: once the map is
     * laid out with the file's row on it, and for a declaration once its file is outlined; placed beside its row.
     * {@code then} gets the card, or null when a declaration is no longer in its file's outline. Returns what
     * withdraws the request while the card still waits (it then never opens, and {@code then} is not called): a
     * trail moving on before its stop's card landed (review 4 A2).
     */
    Runnable viewsShowCard(Path key, Consumer<@Nullable ProjectMapPreview> then) {
        Path current = root;
        String relative = current == null ? null : bearingsRelative(current, key);
        if (disposed || relative == null || relative.isEmpty()) {
            then.accept(null);
            return () -> {};
        }
        com.editora.config.ProjectMapState.Card card =
                new com.editora.config.ProjectMapState.Card(relative, Double.NaN, Double.NaN, 0, 0, 1.0, false);
        bearingsPendingCards.add(card);
        viewsCardThen.put(card, then);
        if (surface.viewsLaidOut() && surface.contains(bearingsFileOf(key))) {
            Platform.runLater(this::bearingsRestoreCards);
        } else {
            viewsCardsAwaitRows = true; // the first fit, or the load that brings the row, opens it
        }
        return () -> viewsCancelCard(card);
    }

    /** Withdraws a card {@link #viewsShowCard} asked for and that has not opened yet. */
    private void viewsCancelCard(com.editora.config.ProjectMapState.Card card) {
        bearingsPendingCards.removeIf(pending -> pending == card);
        viewsCardThen.remove(card);
    }

    /** A load landed: cards asked for before their rows were on the map open now, if the map is laid out. */
    private void viewsLoadLanded() {
        if (viewsCardsAwaitRows && surface.viewsLaidOut()) {
            viewsCardsAwaitRows = false;
            Platform.runLater(this::bearingsRestoreCards);
        }
    }

    /**
     * Whether the Map's window is showing; {@link ProjectMapWindow} drives it. While it is not, nothing is
     * listed, no card is refreshed or polled and row states wait: the first load waits until the window is
     * first shown, and every return to it reloads, snapshots the row states once and refreshes the cards
     * (which re-arms the once-a-second poll of a card that mirrors an open buffer), so whatever changed while
     * it was closed is picked up. Hiding stops that work; the cards stay where they are for the next show.
     */
    void setShowing(boolean showing) {
        if (this.active == showing) {
            return;
        }
        this.active = showing;
        if (!showing) {
            cards.stopRefreshing();
            bearingsFlush();
            return;
        }
        bearingsRestore(); // the first show of the folder the workspace remembers brings it back (B6)
        reload();
        if (showingStatesStale) {
            showingStatesStale = false;
            surface.stateChanged();
        }
        if (dataRepaintWhileHidden) {
            dataRepaintWhileHidden = false;
            surface.repaint(); // what changed while hidden (a Git status push) is drawn once now
        }
        surface.warmIcons(); // stopped while hidden
        scheduleCardRefresh();
    }

    /** Whether the view is on screen: its window showing ({@link #setShowing}) and the view in a scene. */
    private boolean showingOnScreen() {
        return active && getScene() != null;
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
        showingOpenFilesGiven = supplier != null;
        showingUpdateStateCandidates();
    }

    /** Paths that may carry a bookmark or Personal Note (the stores' keys); the predicates decide. */
    void setMarkerCandidates(java.util.function.Supplier<java.util.Collection<Path>> supplier) {
        markerCandidates = supplier == null ? List::of : supplier;
        showingMarkersGiven = supplier != null;
        showingUpdateStateCandidates();
    }

    /**
     * Once both the open files and the marker candidates are known, a state snapshot looks those few paths up
     * among the rows instead of asking four predicates of every row (each of which scans the editor's tabs).
     */
    private void showingUpdateStateCandidates() {
        if (showingOpenFilesGiven && showingMarkersGiven) {
            surface.setStateCandidates(() -> {
                List<Path> candidates = new ArrayList<>(openFiles.get());
                candidates.addAll(markerCandidates.get());
                return candidates;
            });
        } else {
            surface.setStateCandidates(null);
        }
    }

    /** A column's own "Show hidden" checkbox was clicked: its folder has to be listed again. */
    void columnHiddenChanged() {
        if (root != null) {
            reload();
        }
    }

    private boolean searching() {
        return !query.isBlank();
    }

    private void clearSearchState() {
        mapLoader.clearSearch();
        searchMatchesTotal = 0;
        searchMatchesPartial = false;
        searchResult = ProjectMapSearchResult.EMPTY;
        searchStructureOpened = null;
        searchPendingSymbol = null;
        surface.setSearchHits(Set.of());
        searchSelectionQuery = "";
        searchReturnSelection = null;
        searchSelected = null;
        resultsCursor = -1;
        resultsOnChanged.run();
    }

    void setQuery(String query) {
        String value = query == null ? "" : query;
        if (!this.query.equals(value)) {
            boolean hadSearchState = mapLoader.hasSearchFolders();
            boolean keepPlace = false;
            if (this.query.isBlank()) {
                searchReturnSelection = surface.selectedPath(); // recorded in history when the search clears
            } else if (value.isBlank()) {
                keepPlace = resultsKeepPlace(); // before the search's folders are dropped
            }
            this.query = value;
            mapLoader.clearSearchEdits(); // what was opened or closed by hand was the previous query's
            updateFilters();
            if (value.isBlank()) {
                clearSearchState();
                if (hadSearchState || keepPlace) {
                    pendingSelection = surface.selectedPath(); // kept in view as the search's columns go
                    reload();
                    onExpandedChanged.run();
                }
            } else {
                resultsOnChanged.run(); // the strip says "Searching…" until this query's answer lands
            }
            resultsRefreshBanner();
        }
    }

    /**
     * The query is being cleared: the map stops filtering but stays where the search took the user (Map review 3
     * B2, reversing review 2's B26). The selection stays; its folders, and the ones the user opened by hand during
     * the search, become ordinary open folders; the row selected before the search goes into the history, so one
     * Back returns to it. Whether anything was promoted (the map then reloads).
     */
    private boolean resultsKeepPlace() {
        Path before = searchReturnSelection;
        searchReturnSelection = null;
        searchSelected = null;
        Path currentRoot = root;
        Path current = surface.selectedPath();
        if (currentRoot == null || current == null || !current.startsWith(currentRoot)) {
            return false;
        }
        Path row = current;
        while (ProjectMapStructure.isSymbolPath(row) && row.getParent() != null) {
            row = row.getParent(); // a declaration row: its file's folders are what stay open
        }
        boolean changed = mapLoader.keepSearchPlace(currentRoot, row);
        if (before != null && before.startsWith(currentRoot) && !before.equals(current)) {
            recordSelection(before);
            recordSelection(current);
        }
        return changed;
    }

    void setSearchMatches(String query, List<Path> matches) {
        setSearchMatches(query, matches, matches == null ? 0 : matches.size(), false);
    }

    /**
     * A file-name search's answer: {@code matches}, best first, out of {@code total} names that matched;
     * {@code partial} when the search did not see the whole project. When fewer are shown than matched, the
     * status line says so (A5): a search that kept only the best few never says it found all.
     */
    void setSearchMatches(String query, List<Path> matches, int total, boolean partial) {
        setSearchMatches(ProjectMapSearchResult.ofFiles(query, matches, total, partial));
    }

    /**
     * The global search's answer: files, folders and declarations, best first (Project Map review 3 A5, E2).
     * The map opens the folders that lead to every match and lands on the first: a file or folder is selected
     * (a folder is opened too); a declaration's file is selected, its Structure column opened, and the
     * declaration selected once the outline is in. Ignored unless {@code result} answers the query in the field.
     */
    void setSearchMatches(ProjectMapSearchResult result) {
        String value = result == null ? "" : result.query();
        if (disposed || result == null || value.isBlank() || !this.query.equals(value)) {
            return;
        }
        Path currentRoot = root;
        List<ProjectMapSearchResult.Match> kept = result.matches().stream()
                .filter(match -> currentRoot != null && match.path().startsWith(currentRoot))
                .toList();
        searchResult = new ProjectMapSearchResult(value, kept, result.total(), result.partial());
        searchMatchesTotal = searchResult.total();
        searchMatchesPartial = searchResult.partial();
        List<Path> allPaths = searchResult.paths();
        // The map opens the folders of the best few; the result keeps every match for the results list.
        List<Path> nextMatches =
                allPaths.size() > searchOpenLimit ? List.copyOf(allPaths.subList(0, searchOpenLimit)) : allPaths;
        ProjectMapSearchResult.Match first = searchResult.first();
        // The same query runs again after every in-app file change; only a new query moves the selection.
        boolean newQuery = !value.equals(searchSelectionQuery);
        searchSelectionQuery = value;
        Path focusBefore = mapLoader.searchFocusFolder();
        Path nextFocus = newQuery
                ? (first != null && first.kind() == ProjectMapSearchResult.Kind.FOLDER ? first.path() : null)
                : (focusBefore != null && allPaths.contains(focusBefore) ? focusBefore : null);
        boolean changed = mapLoader.setSearchMatches(allPaths, nextMatches, nextFocus, newQuery);
        resultsCursor = resultsCursorAfter(newQuery, kept);
        surface.setSearchHits(Set.copyOf(allPaths));
        Path target = first == null ? null : first.path();
        if (newQuery) {
            pendingSelection = target;
            searchSelected = target;
            historySearchAuto = true; // typed: the next query's landing replaces this one in the history
            searchPrepareLanding(first, false);
        }
        if (changed || newQuery && target != null && !surface.contains(target)) {
            announceMatches = true;
            reload();
            onExpandedChanged.run();
        } else if (newQuery && target != null) {
            pendingSelection = null;
            historySelectLanding(target);
            searchResolveSymbol();
        }
        resultsRefreshBanner();
        surface.updateAccessibleText();
        resultsOnChanged.run();
    }

    /** The current query's answer as the map holds it (only matches under its root); empty without one. */
    void setSearchOpenLimitForTest(int limit) {
        searchOpenLimit = limit;
    }

    ProjectMapSearchResult searchResult() {
        return searching() ? searchResult : ProjectMapSearchResult.EMPTY;
    }

    /**
     * Lands on one match of the current query, as the search does on its first: selects and reveals it; a
     * folder is opened; a declaration's file gets its Structure column and the declaration is selected.
     * {@code open} also opens it: a file in the editor, a declaration's card (a folder is opened either way).
     * For stepping through the results (Project Map review 3 B1).
     */
    void showSearchMatch(ProjectMapSearchResult.Match match, boolean open) {
        Path currentRoot = root;
        if (match == null
                || !searching()
                || currentRoot == null
                || !match.path().startsWith(currentRoot)) {
            return;
        }
        Path target = match.path();
        boolean refocus = mapLoader.showMatch(target, match.kind() == ProjectMapSearchResult.Kind.FOLDER);
        searchSelected = target;
        int index = searchResult.matches().indexOf(match);
        if (index >= 0 && index != resultsCursor) {
            resultsCursor = index;
            resultsOnChanged.run();
        }
        historySearchAuto = false; // a match the user stepped to is a place of its own
        searchPrepareLanding(match, open);
        if (refocus || !surface.contains(target)) {
            pendingSelection = target;
            reloadForUser();
            onExpandedChanged.run();
        } else {
            surface.select(target);
            searchResolveSymbol();
        }
        if (open && match.kind() == ProjectMapSearchResult.Kind.FILE) {
            onOpenFile.accept(target);
        }
        if (match.kind() == ProjectMapSearchResult.Kind.TEXT) {
            // The hit itself, in a card: a preview the next step replaces; opened (Ctrl+Enter), a card that stays.
            cards.openSearchHit(target, match.line(), textMark(query), open);
        }
    }

    /**
     * What marks a text search's hit in its card: the text of {@code query} ({@code %text}) as typed, any case
     * unless it has a capital (smart case, as the search itself). Pure.
     */
    static java.util.regex.Pattern textMark(String query) {
        String text = ProjectMapSearch.Query.parse(query).text();
        return java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote(text),
                text.equals(text.toLowerCase(java.util.Locale.ROOT))
                        ? java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE
                        : 0);
    }

    // --- Search results: the strip's cursor, the banner, the notices (Project Map review 3 B1, A2, B12) ---

    /** Told whenever the search's answer or the match the results strip stands on changes. */
    void setOnResultsChanged(Runnable callback) {
        resultsOnChanged = callback == null ? () -> {} : callback;
    }

    /**
     * Where the search's own notices go — the search did not reach the whole project, the list was cut, the map
     * opens the folders of the best few only. Without one they go to {@link #setOnStatus}'s sink.
     */
    void setOnSearchNotice(java.util.function.@Nullable Consumer<String> callback) {
        resultsOnNotice = callback;
    }

    /** Whether the answer the map holds is the one for the query in the field (not an earlier query's). */
    boolean resultsAnswered() {
        return searching() && searchResult.query().equals(query);
    }

    /** The index of the match the strip stands on in {@link #searchResult()}'s matches; -1 for none. */
    int resultsCursor() {
        return resultsAnswered() && resultsCursor < searchResult.matches().size() ? resultsCursor : -1;
    }

    /** The match the strip stands on, or null. */
    ProjectMapSearchResult.@Nullable Match resultsCurrent() {
        int index = resultsCursor();
        return index < 0 ? null : searchResult.matches().get(index);
    }

    /**
     * Steps {@code delta} matches on from the current one, wrapping at either end, and lands on it (selected and
     * revealed; a match past the opened ones gets its folders opened). False when there is nothing to step to.
     */
    boolean resultsStep(int delta) {
        int count = resultsAnswered() ? searchResult.matches().size() : 0;
        if (count == 0) {
            return false;
        }
        int from = resultsCursor();
        int next = from < 0 ? (delta >= 0 ? 0 : count - 1) : Math.floorMod(from + delta, count);
        resultsGoTo(next, false);
        return true;
    }

    /** Lands on match {@code index}; {@code open} also opens it (a file in the editor, a declaration's card). */
    void resultsGoTo(int index, boolean open) {
        if (!resultsAnswered() || index < 0 || index >= searchResult.matches().size()) {
            return;
        }
        ProjectMapSearchResult.Match match = searchResult.matches().get(index);
        resultsCursor = index;
        showSearchMatch(match, open);
        resultsOnChanged.run();
    }

    /** Ctrl/Cmd+Enter in the search field: opens the current match, or the selection when there is none. */
    void resultsOpenCurrent() {
        int index = resultsCursor();
        if (index >= 0) {
            resultsGoTo(index, true);
        } else {
            openSelection();
        }
    }

    /** The match the strip stands on after a new answer: the first for a new query, else the same one if kept. */
    private int resultsCursorAfter(boolean newQuery, List<ProjectMapSearchResult.Match> kept) {
        if (kept.isEmpty()) {
            return -1;
        }
        if (newQuery || resultsCursor < 0) {
            return 0;
        }
        List<ProjectMapSearchResult.Match> before = searchResult.matches();
        ProjectMapSearchResult.Match was = resultsCursor < before.size() ? before.get(resultsCursor) : null;
        int same = was == null ? -1 : kept.indexOf(was);
        return same >= 0 ? same : Math.min(resultsCursor, kept.size() - 1);
    }

    /** A row the user selected that is a match (a file or folder): the strip stands on it from now on. */
    private void resultsFollowSelection(Path path) {
        if (!resultsAnswered() || ProjectMapStructure.isSymbolPath(path)) {
            return;
        }
        ProjectMapSearchResult.Match current = resultsCurrent();
        if (current != null && current.path().equals(path)) {
            return;
        }
        List<ProjectMapSearchResult.Match> matches = searchResult.matches();
        for (int index = 0; index < matches.size(); index++) {
            if (matches.get(index).kind() != ProjectMapSearchResult.Kind.SYMBOL
                    && matches.get(index).path().equals(path)) {
                resultsCursor = index;
                resultsOnChanged.run();
                return;
            }
        }
    }

    /**
     * What a screen reader hears after the row's own description while a search stands: which match of how many
     * it is, and whether the search was partial (B11). Empty when the row is not a match or nothing is searched.
     */
    String resultsAccessibleSuffix(Path path) {
        if (!resultsAnswered() || searchResult.isEmpty()) {
            return "";
        }
        ProjectMapSearchResult.Match current = resultsCurrent();
        int index = current != null && current.path().equals(path) ? resultsCursor() : -1;
        if (index < 0) {
            List<ProjectMapSearchResult.Match> matches = searchResult.matches();
            for (int at = 0; at < matches.size() && index < 0; at++) {
                if (matches.get(at).path().equals(path)) {
                    index = at;
                }
            }
        }
        if (index < 0) {
            return "";
        }
        return tr(
                searchMatchesPartial
                        ? "project.map.accessible.searchMatchPartial"
                        : "project.map.accessible.searchMatch",
                index + 1,
                searchResult.matches().size());
    }

    /** The folder that holds {@code path}, relative to the root, for a screen reader (B11); the root's own name. */
    String resultsAccessibleFolder(Path path) {
        Path currentRoot = root;
        Path row = path;
        while (ProjectMapStructure.isSymbolPath(row) && row.getParent() != null) {
            row = row.getParent();
        }
        Path parent = row.getParent();
        if (currentRoot == null || parent == null || !parent.startsWith(currentRoot) || row.equals(currentRoot)) {
            return "";
        }
        Path rootName = currentRoot.getFileName();
        String relative = currentRoot.relativize(parent).toString().replace('\\', '/');
        return relative.isEmpty() ? (rootName == null ? currentRoot.toString() : rootName.toString()) : relative;
    }

    /** Keeps "No files match" naming the query in the field, and saying when the search was partial (B12, A2). */
    private void resultsRefreshBanner() {
        if (noMatchesLabel.isVisible()) {
            noMatchesLabel.setText(resultsBannerText());
        }
    }

    /** The no-match banner's text for the query and filters as they are now. */
    private String resultsBannerText() {
        if (query.isBlank()) {
            return tr("project.map.noMatches.filters");
        }
        boolean partial = resultsAnswered() && searchMatchesPartial;
        return tr(partial ? "project.map.noMatches.partial" : "project.map.noMatches.query", query.strip());
    }

    /**
     * The search's notice once its folders are loaded: everything that applies, together — how many matched and
     * how many the list holds, that the map opens the folders of the best {@link #SEARCH_OPEN_LIMIT} only, how
     * many of those the row limit left off, and that the search did not reach the whole project (A2). Empty when
     * there is nothing to say.
     */
    private String resultsNotice(int shown) {
        List<String> parts = new ArrayList<>();
        int kept = searchResult.matches().size();
        if (searchMatchesTotal > kept) {
            parts.add(tr("project.map.status.search.listed", searchMatchesTotal, kept));
        }
        if (kept > searchOpenLimit) {
            parts.add(tr("project.map.status.search.opened", searchOpenLimit));
        }
        int opened = mapLoader.openedMatches().size();
        if (shown < opened) {
            parts.add(tr("project.map.status.search.rowLimit", shown, opened));
        }
        if (searchMatchesPartial && ProjectMapSearch.Query.parse(query).contents()) {
            parts.add(tr("project.map.status.search.textPartial")); // Find in Files' own limits (gap 10)
        } else if (searchMatchesPartial) {
            parts.add(tr(
                    "project.map.status.search.partial",
                    com.editora.search.ProjectFileList.MAX_FILES,
                    com.editora.search.ProjectFileList.MAX_DEPTH));
        }
        return parts.isEmpty() ? "" : tr("project.map.status.search.notice", String.join(" ", parts));
    }

    private void resultsNotify(String notice) {
        java.util.function.Consumer<String> sink = resultsOnNotice;
        (sink == null ? onStatus : sink).accept(notice);
    }

    /**
     * Before landing on {@code match}: a declaration's file gets its Structure column (the one an earlier
     * landing opened closes), and the declaration waits for the outline. Nothing for a file or folder.
     */
    private void searchPrepareLanding(ProjectMapSearchResult.@Nullable Match match, boolean openCard) {
        searchPendingSymbol = null;
        Path file = match == null || match.kind() != ProjectMapSearchResult.Kind.SYMBOL ? null : match.path();
        Path previous = searchStructureOpened;
        if (previous != null && !previous.equals(file)) {
            structure.close(previous);
            searchStructureOpened = null;
        }
        if (match == null || file == null) {
            return;
        }
        Path parent = file.getParent();
        if (!structure.isOpen(file) && parent != null && structure.supports(file)) {
            if (structure.toggle(new ProjectMapModel.Entry(file, parent, 0, false))) {
                searchStructureOpened = file;
            }
        }
        if (structure.isOpen(file)) {
            String name = match.symbol();
            searchPendingSymbol = new SearchLanding(file, name == null ? "" : name, match.line(), openCard);
        }
    }

    /**
     * Selects the declaration a landing waits for once its file's row is on the map and its outline is in,
     * opening the columns of the declarations that hold it. A file that cannot be outlined stays selected.
     */
    private void searchResolveSymbol() {
        SearchLanding landing = searchPendingSymbol;
        if (landing == null || !searching() || disposed) {
            return;
        }
        ProjectMapStructure.View view = structure.view(landing.file());
        if (view.problem() == ProjectMapStructure.Problem.LOADING || !surface.contains(landing.file())) {
            return; // the outline or the row is still on its way: an arriving one calls again
        }
        searchPendingSymbol = null;
        if (view.problem() != ProjectMapStructure.Problem.NONE) {
            return;
        }
        List<ProjectMapStructure.Node> chain = searchDeclarationChain(view.roots(), landing.name(), landing.line());
        if (chain.isEmpty()) {
            return;
        }
        boolean opened = false;
        Path parent = landing.file();
        for (int index = 0; index < chain.size() - 1; index++) {
            ProjectMapStructure.Node holder = chain.get(index);
            if (!structure.isOpen(holder.path())) {
                opened |= structure.toggle(ProjectMapModel.Entry.symbol(holder.path(), parent, 0, holder.symbol()));
            }
            parent = holder.path();
        }
        Path declaration = chain.getLast().path();
        if (opened || !surface.contains(declaration)) {
            showRows(); // the column the landing opened, with an outline that was already held
        }
        if (!surface.contains(declaration)) {
            return; // past the column's row limit: its file stays selected
        }
        searchSelected = declaration;
        historySelectLanding(declaration);
        surface.revealAfterLoad(declaration);
        ProjectMapModel.Entry entry = surface.entryByPath.get(declaration);
        if (landing.openCard() && entry != null) {
            previewSymbol(entry);
        }
    }

    /**
     * The declarations from an outline's root down to the one a symbol search found: the one on {@code line}
     * named {@code name}, else the nearest named so, else the one on that line. Empty when none fits.
     */
    static List<ProjectMapStructure.Node> searchDeclarationChain(
            List<ProjectMapStructure.Node> roots, String name, int line) {
        List<List<ProjectMapStructure.Node>> found = new ArrayList<>();
        searchCollectChains(roots, new ArrayList<>(), found);
        List<ProjectMapStructure.Node> best = List.of();
        long bestCost = Long.MAX_VALUE;
        for (List<ProjectMapStructure.Node> chain : found) {
            ProjectMapModel.Symbol symbol = chain.getLast().symbol();
            boolean named = !name.isEmpty() && searchLabelNames(symbol.label(), name);
            int distance = Math.abs(symbol.line() - line);
            if (!named && distance != 0) {
                continue;
            }
            long cost = (named ? 0 : 1_000_000L) + distance;
            if (cost < bestCost) {
                bestCost = cost;
                best = chain;
            }
        }
        return best;
    }

    private static void searchCollectChains(
            List<ProjectMapStructure.Node> nodes,
            List<ProjectMapStructure.Node> above,
            List<List<ProjectMapStructure.Node>> out) {
        for (ProjectMapStructure.Node node : nodes) {
            List<ProjectMapStructure.Node> chain = new ArrayList<>(above);
            chain.add(node);
            out.add(chain);
            searchCollectChains(node.children(), chain, out);
        }
    }

    /** Whether an outline label ({@code "func NewScheduler(…)"}, {@code "PodSpec"}) declares {@code name}. */
    static boolean searchLabelNames(String label, String name) {
        if (label == null || name.isEmpty()) {
            return false;
        }
        for (int at = label.indexOf(name); at >= 0; at = label.indexOf(name, at + 1)) {
            int end = at + name.length();
            boolean starts = at == 0 || !Character.isJavaIdentifierPart(label.charAt(at - 1));
            boolean ends = end == label.length() || !Character.isJavaIdentifierPart(label.charAt(end));
            if (starts && ends) {
                return true;
            }
        }
        return false;
    }

    /**
     * A Git refresh came in, whether or not the status changed: it may follow a commit, checkout or pull, which on a
     * clean tree moves HEAD and changes no status (Map review 4, B1). The recent changes are asked for again (one
     * rev-parse while HEAD has not moved).
     */
    void activityGitRefreshed() {
        activity.setOverviewWanted(cards.overviewCard() != null);
        activity.setMetricsWanted(cards.metricsCard() != null);
        activity.headMaybeMoved();
    }

    void setGitStatus(Map<Path, GitFileStatus> status) {
        activityGitRefreshed();
        Map<Path, GitFileStatus> given = status == null ? Map.of() : status;
        if (given.equals(dataGitStatusGiven) && java.util.Objects.equals(root, dataGitStatusRoot)) {
            return; // every Git refresh (a tab switch, a save, a focus regain) pushes the status, mostly unchanged
        }
        dataGitStatusGiven = given;
        dataGitStatusRoot = root;
        Map<Path, GitFileStatus> normalized = new HashMap<>();
        given.forEach((path, value) -> normalized.put(ProjectMapModel.normalize(path), value));
        gitStatus = Map.copyOf(normalized);
        Set<Path> directories = new HashSet<>();
        for (Path file : normalized.keySet()) {
            for (Path parent = file.getParent();
                    parent != null && root != null && parent.startsWith(root);
                    parent = parent.getParent()) {
                if (!directories.add(parent)) {
                    break; // this folder, and every one above it, is recorded already
                }
                if (parent.equals(root)) {
                    break;
                }
            }
        }
        gitChangedDirectories = Set.copyOf(directories);
        surface.setGitState(gitStatus, gitChangedDirectories);
        updateFilters();
        ProjectMapChangesCard changes = cards.changesCard();
        if (changes != null) {
            readChanges(changes); // a save, a commit, a checkout: the card follows
        }
    }

    /** The status last given to {@link #setGitStatus}, and the root it was given for. */
    private Map<Path, GitFileStatus> dataGitStatusGiven = Map.of();

    private @Nullable Path dataGitStatusRoot;

    void refreshStates() {
        if (!active) {
            showingStatesStale = true; // nobody sees the rows: snapshot them once when the window shows again
            return;
        }
        surface.stateChanged();
        scheduleCardRefresh();
    }

    void setMarkerStates(Predicate<Path> bookmarked, Predicate<Path> noted) {
        surface.setMarkerSuppliers(bookmarked, noted);
        surface.stateChanged();
    }

    void setPreviewMarkerActions(ProjectMapPreview.@Nullable MarkerActions actions) {
        previewMarkerActions = actions;
        cards.setMarkerActions(actions);
    }

    void setNotePreviewActions(ProjectPanel.MarkerActions actions) {
        cards.setNoteActions(actions);
    }

    /** Anything on disk may have changed (an in-app file operation): every folder is listed again. */
    void refresh() {
        mapLoader.refresh();
        scheduleCardRefresh();
    }

    /**
     * Editora came back from another program (a real focus regain): each watched folder's cached listing is
     * kept when the folder's modification time did not move, which the loader reads off the FX thread; only
     * the folders that changed, and the unwatched ones, are listed again (position 22 of Map review 3).
     */
    void revalidate() {
        mapLoader.revalidate();
        scheduleCardRefresh();
    }

    /** The Project watcher saw entries come or go in {@code directories}: those are listed again, the rest kept. */
    void refresh(java.util.Collection<Path> directories) {
        activity.ownerFilesMayHaveChanged(directories);
        mapLoader.refresh(directories);
        scheduleCardRefresh();
    }

    /**
     * Files in {@code directories} were rewritten (sizes and times changed, no entry came or went): their next
     * listing reads them again, but nothing is reloaded for it now.
     */
    void listingsChanged(java.util.Collection<Path> directories) {
        activity.ownerFilesMayHaveChanged(directories); // an edited CODEOWNERS or OWNERS (Map review 4 B9)
        mapLoader.listingsChanged(directories);
    }

    /** The folders the Project watcher follows now: only their listings may be reused between loads. */
    void setWatchedDirectories(Set<Path> directories) {
        mapLoader.setWatchedDirectories(directories);
    }

    /**
     * The folders whose children the map lists now. Without a query that is the manual set. With one it is
     * the manual set plus the matches' ancestors, as adjusted by hand during the search: a folder closed
     * there hides its whole branch, and none of it reaches the manual set.
     */
    Set<Path> expandedDirectories() {
        return mapLoader.expandedDirectories();
    }

    /** Carries the map's state across an in-app rename or move, so a renamed open folder stays open. */
    void pathRenamed(Path from, Path to) {
        if (root == null || from == null || to == null) {
            return;
        }
        structure.pathRenamed(
                from.toAbsolutePath().normalize(), to.toAbsolutePath().normalize());
        Path selected = surface.selectedPath();
        Path renamed = ProjectMapModel.normalize(from);
        if (selected != null && renamed != null && selected.startsWith(renamed)) {
            pendingSelection = ProjectMapModel.remap(selected, from, to);
        }
        cards.pathRenamed(from, to);
        mapLoader.pathRenamed(from, to); // the open folders follow; the new tree is listed, not the old one
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
    void escapePressed() {
        // A playing trail closes first: its bar would otherwise outlive the cards Escape closes (review 4 A4).
        if (trails.viewsEscape()) {
            return;
        }
        // Cards hidden by "Hide unmatched" or "Hide all open Personal Notes" are not on screen: leave them be.
        if (cards.closeVisible()) {
            return;
        } else if (!query.isBlank()) {
            onClearSearch.run();
        } else {
            onLeaveMap.run();
        }
    }

    void setOnExpandedChanged(Runnable callback) {
        Runnable then = callback == null ? () -> {} : callback;
        onExpandedChanged = () -> {
            then.run();
            bearingsChanged();
        };
    }

    void setRememberedFlow(String name, Consumer<FlowDirection> callback) {
        FlowDirection remembered;
        try {
            remembered = FlowDirection.valueOf(name == null ? "" : name);
        } catch (IllegalArgumentException ignored) {
            remembered = DEFAULT_FLOW; // nothing stored (or a value this build does not know)
        }
        onFlowChanged = callback == null ? ignored -> {} : callback;
        canvasApplyFlow(remembered, false);
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

    /**
     * Restores the ⋯ menu's Single-click opens files, Compact folder chains and Always select opened file, and
     * reports each later change to its callback.
     */
    void setRememberedOptions(
            boolean singleClick,
            Consumer<Boolean> onSingleClick,
            boolean compact,
            Consumer<Boolean> onCompact,
            boolean follow,
            Consumer<Boolean> onFollow) {
        onSingleClickChanged = ignored -> {};
        onCompactChainsChanged = ignored -> {};
        onFollowActiveFileChanged = ignored -> {};
        singleClickOpens.setSelected(singleClick);
        compactChainsOption.setSelected(compact);
        followActiveFile.setSelected(follow);
        if (!optionsRemembered) {
            optionsRemembered = true;
            singleClickOpens.selectedProperty().addListener((obs, old, on) -> onSingleClickChanged.accept(on));
            compactChainsOption.selectedProperty().addListener((obs, old, on) -> onCompactChainsChanged.accept(on));
            followActiveFile.selectedProperty().addListener((obs, old, on) -> onFollowActiveFileChanged.accept(on));
        }
        onSingleClickChanged = onSingleClick == null ? ignored -> {} : onSingleClick;
        onCompactChainsChanged = onCompact == null ? ignored -> {} : onCompact;
        onFollowActiveFileChanged = onFollow == null ? ignored -> {} : onFollow;
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
                                : menuWithMapItems(entry, entry.isSymbol() ? symbolMenu(entry) : factory.apply(entry)));
    }

    /**
     * Puts the Map's own row actions at the top of a row's menu (after a declaration's own items) — Preview,
     * Show / Hide Structure, Personal Notes and Close Column — so the Menu key or Shift+F10 reaches every card and column the pointer can (B6). They
     * act in the Map, so the shared menu's "raise the editor window" wrapping leaves them alone.
     */
    private @Nullable ContextMenu menuWithMapItems(ProjectMapModel.Entry entry, @Nullable ContextMenu menu) {
        if (menu == null) {
            return null;
        }
        List<MenuItem> items = new ArrayList<>();
        if (entry.isFile()) {
            items.add(mapMenuItem("project.map.menu.preview", () -> previewFileEntry(entry)));
            if (followCode != null) {
                items.add(mapMenuItem("project.map.follow.related", () -> followRelated(entry.path())));
            }
        }
        if (entry.isFile() && surface.hasStructure(entry) || entry.isSymbol() && surface.branches(entry)) {
            String key = structure.isOpen(entry.path())
                    ? "project.map.menu.hideStructure"
                    : "project.map.menu.showStructure";
            items.add(mapMenuItem(key, () -> toggleStructure(entry)));
        }
        if (!entry.isSymbol() && surface.notedPaths.contains(entry.path())) {
            items.add(mapMenuItem("project.map.menu.personalNotes", () -> cardsShowNotes(entry.path())));
        }
        FollowCode code = followCode;
        if (code != null && (entry.isFile() || entry.directory())) {
            // The file's or folder's web address at HEAD (Map code browser round 2).
            items.add(mapMenuItem("project.map.permalink.copy", () -> code.copyPermalink(entry.path(), -1, -1)));
        }
        if (entry.directory()
                || entry.isFile() && ProjectMapImports.languageOf(entry.path().toString()) != null) {
            // What uses it and what it uses, as a card (gap 14).
            items.add(mapMenuItem("project.map.deps.menu", () -> openDependencies(entry.path(), null)));
        }
        if (!entry.isSymbol() && surface.gitChanged(entry.path())) {
            // Its changes against HEAD (a folder's: its first changed file's), in the Changes card (round 2).
            items.add(mapMenuItem("project.map.changes.menu", () -> openChanges(entry.path())));
        }
        if (entry.directory()) {
            // Its files' sizes, complexity and hotspots, as a card (gap 15).
            items.add(mapMenuItem("project.map.metrics.menu", () -> openMetrics(entry.path())));
        }
        Path closable = surface.closeTargetFor(entry);
        if (closable != null) {
            items.add(mapMenuItem("project.map.menu.closeColumn", () -> closeColumn(closable)));
        }
        if (canvasHasOtherBranches(entry.path())) {
            items.add(mapMenuItem("project.map.menu.closeOtherBranches", () -> canvasCloseOthers(entry.path(), true)));
        }
        if (items.isEmpty()) {
            return menu;
        }
        if (entry.isSymbol()) {
            // A declaration's own menu already starts with its card: the column actions follow it.
            menu.getItems().add(new SeparatorMenuItem());
            menu.getItems().addAll(items);
        } else {
            if (!menu.getItems().isEmpty()) {
                items.add(new SeparatorMenuItem());
            }
            menu.getItems().addAll(0, items);
        }
        return menu;
    }

    /** Whether a column off the way to {@code row} is open, so its Close Other Branches has something to do. */
    private boolean canvasHasOtherBranches(Path row) {
        Path current = root;
        if (current == null) {
            return false;
        }
        for (Path open : mapLoader.expandedDirectories()) {
            if (!open.equals(current) && !row.startsWith(open)) {
                return true;
            }
        }
        for (Path open : structure.openFiles()) {
            if (!row.startsWith(open)) {
                return true;
            }
        }
        return false;
    }

    private static MenuItem mapMenuItem(String key, Runnable action) {
        MenuItem item = new MenuItem(tr(key));
        item.setOnAction(event -> action.run());
        item.getProperties().put(ProjectPanel.STAYS_IN_MAP, Boolean.TRUE);
        return item;
    }

    /** Shift+Enter on a file or declaration row: opens its preview card, or closes the one that is open. */
    private void togglePreview(ProjectMapModel.Entry entry) {
        ProjectMapPreview open = previews.get(entry.path().toAbsolutePath().normalize());
        if (open == null && entry.isSymbol()) {
            open = previews.get(entry.path());
        }
        if (open != null) {
            closePreview(open);
            surface.requestFocus(); // the keyboard stays on the map, ready for the next Shift+Enter
        } else if (entry.isSymbol()) {
            previewSymbol(entry);
        } else if (entry.isFile()) {
            previewFileEntry(entry);
        }
    }

    /** The eye on a file row: its card opens, or closes when it is open already. */
    private void toggleFileCard(Path path) {
        ProjectMapPreview open = fileCard(path);
        if (open != null) {
            closePreview(open);
        } else {
            previewSelection(path);
        }
    }

    /** The open card of the file at {@code path}, or null. */
    private @Nullable ProjectMapPreview fileCard(Path path) {
        return previews.get(path.toAbsolutePath().normalize());
    }

    /** Whether the file at {@code path} has its card open, so its eye closes it. */
    boolean hasFileCard(Path path) {
        return fileCard(path) != null;
    }

    /** The eye's action for {@code entry}, from a key or the row menu: the row is selected first. */
    private void previewFileEntry(ProjectMapModel.Entry entry) {
        surface.select(entry.path());
        previewSelection(entry.path());
    }

    /** A declaration row's menu: its card, the file opened at it, the file opened narrowed to its block. */
    private ContextMenu symbolMenu(ProjectMapModel.Entry entry) {
        return symbolMenu(entry, () -> previewSymbol(entry));
    }

    /** {@link #symbolMenu(ProjectMapModel.Entry)} whose Preview runs {@code previewAction}: a file card's column's. */
    private ContextMenu symbolMenu(ProjectMapModel.Entry entry, Runnable previewAction) {
        ProjectMapModel.Symbol symbol = entry.symbol();
        ContextMenu menu = new ContextMenu();
        if (symbol == null) {
            return menu;
        }
        MenuItem preview = new MenuItem(tr("project.map.structure.preview"));
        preview.setOnAction(event -> previewAction.run());
        MenuItem openAt = new MenuItem(tr("project.map.structure.openAt"));
        openAt.setOnAction(event -> onOpenAt.accept(symbol.file(), symbol.line()));
        MenuItem openNarrowed = new MenuItem(tr("project.map.preview.openNarrowed"));
        openNarrowed.setOnAction(event -> onOpenNarrowed.accept(
                symbol.file(),
                symbol.block() != null
                        ? symbol.block()
                        : new com.editora.text.FoldRegions.Region(symbol.line(), symbol.endLine())));
        menu.getItems().setAll(preview, openAt, openNarrowed);
        menu.getItems().addAll(linksItems(entry));
        menu.getItems().addAll(followItems(symbol.file(), symbol.line(), followName(symbol.label()), () -> {
            previewAction.run(); // the declaration's card first: its History card opens beside it
            ProjectMapPreview card = cards.codeCard(entry.path());
            if (card != null) {
                cards.openHistory(card);
            }
        }));
        return menu;
    }

    /**
     * What a declaration row's menu asks about it on the Map (explore position 5, Map code navigation): its card
     * opens and what is found — its references, callers, implementations, the method it overrides, its type, its
     * supertypes and subtypes — opens as cards beside it, joined to it by labelled edges. Its definition and
     * declaration are the row itself, so those are not offered. Empty without a source.
     */
    private List<MenuItem> linksItems(ProjectMapModel.Entry entry) {
        if (!cards.linksAvailable()) {
            return List.of();
        }
        List<MenuItem> items = new ArrayList<>();
        items.add(new SeparatorMenuItem());
        for (MapCardLinks.Kind kind : MapCardLinks.MENU_KINDS) {
            if (kind == MapCardLinks.Kind.DEFINITION || kind == MapCardLinks.Kind.DECLARATION) {
                continue;
            }
            MenuItem item = new MenuItem(MapCardLinks.menuLabel(kind));
            item.setOnAction(event -> cards.showLinks(entry, kind));
            items.add(item);
        }
        for (MapCardLinks.Kind kind : MapCardLinks.HIERARCHY_MENU_KINDS) {
            MenuItem item = new MenuItem(MapCardLinks.hierarchyMenuLabel(kind));
            item.setOnAction(event -> cards.showHierarchy(entry, kind));
            items.add(item);
        }
        MenuItem graph = new MenuItem(tr("project.map.graph.menu"), Icons.symbolGraph());
        graph.setOnAction(event -> cards.showSymbolGraph(entry));
        items.add(graph);
        return items;
    }

    /**
     * The editor's code navigation typed on the map with a declaration row selected (Go to Implementation, Find
     * Usages, Call and Type Hierarchy, …): asked about that declaration, as its menu would, answered as cards. False
     * for any other command, another row, or with the keyboard elsewhere.
     */
    boolean runRowNavigation(String commandId) {
        return surface.isFocused() && rowNavigation(commandId);
    }

    /**
     * A navigation command run from outside the Map window — Show Callees as Cards from the command palette: asked in
     * the card the Map's keyboard was last in, else of the selected declaration row. False with neither.
     */
    boolean runNavigation(String commandId) {
        ProjectMapPreview card = focusedCard();
        return card != null ? card.runMarkerCommand(commandId) : rowNavigation(commandId);
    }

    private boolean rowNavigation(String commandId) {
        List<MapCardLinks.Kind> kinds = MapCardLinks.NAVIGATION_COMMANDS.get(commandId);
        MapCardLinks.Kind hierarchy = MapCardLinks.HIERARCHY_COMMANDS.get(commandId);
        if (kinds == null && hierarchy == null || !cards.linksAvailable()) {
            return false;
        }
        ProjectMapModel.Entry entry =
                surface.selectedEntry().filter(ProjectMapModel.Entry::isSymbol).orElse(null);
        if (entry == null) {
            return false;
        }
        if (hierarchy != null) {
            cards.showHierarchy(entry, hierarchy);
        } else if (kinds != null) {
            kinds.forEach(kind -> cards.showLinks(entry, kind));
        }
        return true;
    }

    // --- problems on the Map (Map code browser round 2) -------------------------------------------------------

    /**
     * The errors and warnings the editor's Problems window holds (each file's): the rows show them, folders add up
     * theirs, the Problems chip filters by them, and Next and Previous Problem step through them on the Map.
     */
    void setProblems(java.util.Map<Path, List<com.editora.text.LspDiagnostic>> byFile) {
        problemSource = java.util.Map.copyOf(byFile);
        surface.setProblems(new ProjectMapProblems(problemSource, root));
    }

    /** The problem Next or Previous Problem last went to, or null. */
    private ProjectMapProblems.@Nullable Place lastProblem;

    /** The problems the Map shows. */
    ProjectMapProblems problems() {
        return surface.problems();
    }

    /**
     * Next ({@code forward}) or Previous Problem typed on the Map: the card of the next problem after the card the
     * keyboard is in (its caret) or the selected row, going round; the status line says which of how many and what
     * it says. With none, it says so.
     */
    void stepProblem(boolean forward) {
        ProjectMapProblems known = surface.problems();
        if (known.isEmpty()) {
            onStatus.accept(tr("project.map.problems.none"));
            return;
        }
        ProjectMapPreview card = focusedCard();
        Path file = card != null
                ? card.path()
                : surface.selectedEntry().map(ProjectMapModel.Entry::path).orElse(null);
        int line = card != null ? card.fileLineAt(card.caretOffset()) : forward ? -1 : Integer.MAX_VALUE;
        int column = card != null ? card.editor().getCaretColumn() : 0;
        ProjectMapProblems.Place last = lastProblem;
        if (card != null && last != null && last.file().equals(file) && last.line() == line) {
            column = last.column(); // still on the problem last stepped to: on from it, not from where the caret fell
        }
        ProjectMapProblems.Place place = known.step(file, line, column, forward);
        if (place == null) {
            return;
        }
        lastProblem = place;
        cards.openPlaceCard(card, place.file(), place.line(), null);
        onStatus.accept(tr(
                place.error() ? "project.map.problems.stepError" : "project.map.problems.stepWarning",
                known.indexOf(place),
                known.places().size(),
                place.message()));
    }

    // --- the card switcher (Map code browser round 2, item 6) ------------------------------------------------

    /** The open cards in sight, the most recently used first: what the card switcher lists. */
    List<MapCards.OpenCard> switchableCards() {
        return cards.visibleCards();
    }

    /**
     * Where the switcher starts among {@code open} (the most recently used first): the card before the one the keyboard
     * is in, so Enter goes back to it; else the most recent. Null for none.
     */
    static MapCards.@Nullable OpenCard switcherStart(List<MapCards.OpenCard> open, @Nullable Node focused) {
        if (open.isEmpty()) {
            return null;
        }
        boolean inFirst = false;
        for (Node node = focused; node != null; node = node.getParent()) {
            if (node == open.get(0).node()) {
                inFirst = true;
                break;
            }
        }
        return inFirst && open.size() > 1 ? open.get(1) : open.get(0);
    }

    /** The card the switcher starts on now. */
    MapCards.@Nullable OpenCard switcherStart(List<MapCards.OpenCard> open) {
        javafx.scene.Scene scene = getScene();
        return switcherStart(open, scene == null ? null : scene.getFocusOwner());
    }

    /** What the switcher says under a card's name: where it is, below the Map's folder; a note card says so. */
    String switcherDetail(MapCards.OpenCard card) {
        Path file = card.node() instanceof ProjectMapPreview code && code.path() != null ? code.path() : card.key();
        Path base = root;
        String where = base != null && file.startsWith(base) && !file.equals(base)
                ? base.relativize(file).toString().replace('\\', '/')
                : file.toString();
        return card.note() ? tr("project.map.switch.notes", where) : where;
    }

    /** The switcher's icon of a card: its file's, or its kind's. */
    static Node switcherIcon(MapCards.OpenCard card) {
        Region node = card.node();
        if (card.note()) {
            return Icons.notes();
        }
        if (node instanceof ProjectMapOverviewCard) {
            return Icons.overview();
        }
        if (node instanceof ProjectMapHistoryCard) {
            return Icons.gitLog();
        }
        if (node instanceof ProjectMapDependencyCard) {
            return Icons.dependencies();
        }
        if (node instanceof ProjectMapMetricsCard) {
            return Icons.metrics();
        }
        if (node instanceof ProjectMapChangesCard) {
            return Icons.diff();
        }
        if (node instanceof ProjectMapSymbolGraphCard) {
            return Icons.symbolGraph();
        }
        Path file = node instanceof ProjectMapPreview code && code.path() != null ? code.path() : card.key();
        Path name = file.getFileName();
        return FileIcons.forFileName(name == null ? file.toString() : name.toString());
    }

    /** The card picked in the switcher comes into view, with the keyboard in it. */
    void switchToCard(MapCards.OpenCard card) {
        cards.bringIntoView(card.node());
    }

    /** The card the keyboard is in, or null: the place Go to File and Go to Symbol typed on the Map start from. */
    @Nullable
    ProjectMapPreview focusedCard() {
        javafx.scene.Scene scene = surface.getScene();
        for (Node node = scene == null ? null : scene.getFocusOwner(); node != null; node = node.getParent()) {
            if (node instanceof ProjectMapPreview card) {
                return card;
            }
        }
        return null;
    }

    /**
     * The file Go to File and Go to Symbol typed on the Map start from: {@code card}'s file, else the selected row's
     * (a declaration's file), else null.
     */
    @Nullable
    Path gotoAnchor(@Nullable ProjectMapPreview card) {
        Path own = card == null ? null : card.path();
        if (own != null) {
            return own;
        }
        Path selected = surface.selectedEntry()
                .filter(entry -> !entry.isPlaceholder())
                .map(ProjectMapModel.Entry::path)
                .orElse(null);
        while (selected != null && ProjectMapStructure.isSymbolPath(selected) && selected.getParent() != null) {
            selected = selected.getParent();
        }
        return selected;
    }

    /**
     * Go to File and Go to Symbol typed on the Map (Map code navigation): reveals {@code target} on the Map and opens
     * its card — at the 0-based {@code line} with {@code name} selected, in the card of the declaration holding it,
     * or the whole file with a negative line — the keyboard in it and, picked from {@code from}, Back returning
     * there. A folder is only revealed. False, with nothing done, for a place outside the Map's root.
     */
    boolean showOnMap(@Nullable ProjectMapPreview from, Path target, int line, @Nullable String name) {
        Path normalized = ProjectMapModel.normalize(target);
        Path currentRoot = root;
        if (normalized == null
                || currentRoot == null
                || !com.editora.config.PathKeys.sameFileSystem(normalized, currentRoot)
                || !normalized.startsWith(currentRoot)) {
            return false;
        }
        boolean file = !java.nio.file.Files.isDirectory(normalized);
        if (from != null && file) {
            // Picked in a card: the history's stops are that card and the card the place opens in, not the row the
            // place is revealed at between them (one Back returns to the card it was typed in).
            navigatingHistory = true;
            try {
                revealPath(normalized);
            } finally {
                navigatingHistory = false;
            }
        } else {
            revealPath(normalized);
        }
        if (file) {
            if (from == null) {
                historyMarkPlace(normalized);
            }
            cards.openPlaceCard(from, normalized, line, name == null ? null : followName(name));
        }
        return true;
    }

    /** Where Show References / Callers as Cards find their places (the panel's language servers and file list). */
    void setLinkSource(MapCardLinks.@Nullable Source source) {
        cards.setLinkSource(source);
    }

    /** How the Map follows the code out of a card or a declaration row (review 3 C12); the panel provides it. */
    interface FollowCode {
        /**
         * Opens {@code file} in the editor at the 0-based {@code line} and {@code column} (or, with a negative
         * column, on {@code name} in that line) and runs {@code commandId} there.
         */
        void follow(Path file, int line, int column, @Nullable String name, String commandId);

        /** The files that exist among {@code file}'s related tests or subjects, best first, given on the FX thread. */
        void relatedFiles(Path file, Consumer<List<Path>> found);

        /** Shows {@code file}'s Git history. */
        void fileHistory(Path file);

        /** Opens {@code file} in the editor annotated with Git blame (Map explore round, position 9). */
        default void annotate(Path file) {}

        /**
         * The commit that last changed {@code file}, or its 0-based lines {@code startLine..endLine} when {@code
         * startLine >= 0}; null, on the FX thread, when Git knows none (Map explore round, position 9).
         */
        default void lastChange(
                Path file,
                int startLine,
                int endLine,
                Consumer<com.editora.git.GitService.@Nullable LastChange> found) {
            found.accept(null);
        }

        /** Opens a link of a card's Quick Documentation in the browser, as the editor's hover does. */
        default void openUrl(String url) {}

        /**
         * Every commit that changed {@code file}'s 0-based lines {@code startLine..endLine}, newest first, at most
         * {@code max}, with the part of each diff that touched them; on the FX thread (Map gap 12).
         */
        default void lineHistory(
                Path file, int startLine, int endLine, int max, Consumer<com.editora.git.LineHistory.Result> found) {
            found.accept(com.editora.git.LineHistory.Result.unavailable());
        }

        /** Who last changed each line of {@code file} in the repository at {@code root}; on the FX thread. */
        default void blame(Path root, Path file, Consumer<List<com.editora.git.BlameParser.BlameLine>> found) {
            found.accept(List.of());
        }

        /**
         * Opens commit {@code hash}'s diff of the file at {@code path} (repository-relative, at {@code previousPath}
         * before the commit when it moved) in the repository at {@code root}, in the editor window.
         */
        default void openCommitFile(Path root, String hash, String path, @Nullable String previousPath) {}

        /** Copy Permalink of a file (its lines), or a folder, at HEAD (Map code browser round 2). */
        default void copyPermalink(Path target, int startLine, int endLine) {}

        /**
         * The working tree's changes against HEAD below {@code folder}, file by file with their hunks, for the Changes
         * card (Map code browser round 2); on the FX thread.
         */
        default void workingChanges(Path folder, Consumer<com.editora.git.GitChanges.Result> found) {
            found.accept(com.editora.git.GitChanges.Result.UNAVAILABLE);
        }

        /** Opens the editor's diff of {@code file} against HEAD. */
        default void compareWithHead(Path file) {}
    }

    private @Nullable FollowCode followCode;

    /** Where cards and declaration rows follow the code to; null takes their follow items away. */
    void setFollowCode(@Nullable FollowCode code) {
        followCode = code;
        cards.setFollowActions(
                code == null
                        ? null
                        : new ProjectMapPreview.FollowActions() {
                            @Override
                            public void follow(Path file, int line, int column, String commandId) {
                                code.follow(file, line, column, null, commandId);
                            }

                            @Override
                            public void related(Path file) {
                                followRelated(file);
                            }

                            @Override
                            public void fileHistory(Path file) {
                                code.fileHistory(file);
                            }

                            @Override
                            public void annotate(Path file) {
                                code.annotate(file);
                            }

                            @Override
                            public void openUrl(String url) {
                                code.openUrl(url);
                            }

                            @Override
                            public void lastChange(
                                    Path file,
                                    int startLine,
                                    int endLine,
                                    Consumer<com.editora.git.GitService.@Nullable LastChange> found) {
                                code.lastChange(file, startLine, endLine, found);
                            }

                            @Override
                            public void lineHistory(
                                    Path file,
                                    int startLine,
                                    int endLine,
                                    int max,
                                    Consumer<com.editora.git.LineHistory.Result> found) {
                                code.lineHistory(file, startLine, endLine, max, found);
                            }

                            @Override
                            public void blame(
                                    Path root, Path file, Consumer<List<com.editora.git.BlameParser.BlameLine>> found) {
                                code.blame(root, file, found);
                            }

                            @Override
                            public void openCommitFile(
                                    Path root, String hash, String path, @Nullable String previousPath) {
                                code.openCommitFile(root, hash, path, previousPath);
                            }

                            @Override
                            public void copyPermalink(Path target, int startLine, int endLine) {
                                code.copyPermalink(target, startLine, endLine);
                            }
                        });
    }

    /**
     * A declaration row's follow items: Go to Definition, Find Usages, Go to Implementation, Type and Call
     * Hierarchy at the declaration in the editor — in an In the Editor submenu when the Map answers them as cards —
     * then Related Test / Subject, the declaration's History card ({@code declarationHistory}, gap 12) and File
     * History of its file. Empty without a {@link FollowCode}.
     */
    private List<MenuItem> followItems(Path file, int line, String name, Runnable declarationHistory) {
        FollowCode code = followCode;
        if (code == null) {
            return List.of();
        }
        List<MenuItem> items = new ArrayList<>();
        items.add(new SeparatorMenuItem());
        List<MenuItem> inEditor = new ArrayList<>();
        for (String commandId : ProjectMapPreview.FOLLOW_COMMANDS) {
            MenuItem item = new MenuItem(ProjectMapPreview.followLabel(commandId));
            item.setOnAction(event -> code.follow(file, line, -1, name, commandId));
            inEditor.add(item);
        }
        if (cards.linksAvailable()) {
            javafx.scene.control.Menu editorMenu = new javafx.scene.control.Menu(tr("project.map.follow.inEditor"));
            editorMenu.getItems().setAll(inEditor);
            items.add(editorMenu);
        } else {
            items.addAll(inEditor);
        }
        items.add(new SeparatorMenuItem());
        items.add(mapMenuItem("project.map.follow.related", () -> followRelated(file)));
        MenuItem lines = new MenuItem(tr("project.map.history.menu"), Icons.gitLog());
        lines.setOnAction(event -> declarationHistory.run());
        items.add(lines);
        MenuItem history = new MenuItem(tr("project.map.follow.fileHistory"), Icons.gitLog());
        history.setOnAction(event -> code.fileHistory(file));
        items.add(history);
        return items;
    }

    /**
     * The name a declaration's label declares, for the caret to land on: the label up to its parameters, past a
     * Go receiver, its last word ({@code "func (s *Server) Start(ctx)"} → {@code Start}). Pure.
     */
    static String followName(@Nullable String label) {
        String value = label == null ? "" : label.strip();
        int receiver = value.indexOf('(');
        int receiverEnd = receiver < 0 ? -1 : value.indexOf(')', receiver);
        if (receiverEnd > 0 && value.indexOf('(', receiverEnd) > receiverEnd) {
            value = value.substring(receiverEnd + 1).strip(); // past a receiver: "func (s *Server) Start(ctx)"
        }
        int open = value.indexOf('(');
        if (open > 0) {
            value = value.substring(0, open).strip();
        }
        int colon = value.indexOf(':');
        if (colon > 0) {
            value = value.substring(0, colon).strip(); // "name: Type"
        }
        String[] words = value.split("\\s+");
        String last = words.length == 0 ? "" : words[words.length - 1];
        int start = 0;
        int end = last.length();
        while (start < end && !Character.isJavaIdentifierPart(last.charAt(start))) {
            start++;
        }
        while (end > start && !Character.isJavaIdentifierPart(last.charAt(end - 1))) {
            end--;
        }
        return last.substring(start, end);
    }

    /**
     * Related Test / Subject (review 3 C12): the file's test, or the subject a test tests, found by {@link
     * com.editora.search.RelatedFiles} and revealed on the Map. When several exist the rest are offered in a menu
     * under the breadcrumb.
     */
    private void followRelated(Path file) {
        FollowCode code = followCode;
        if (code == null) {
            return;
        }
        code.relatedFiles(file, found -> {
            Path currentRoot = root;
            List<Path> shown = currentRoot == null
                    ? List.of()
                    : found.stream()
                            .filter(path -> com.editora.config.PathKeys.sameFileSystem(path, currentRoot)
                                    && path.startsWith(currentRoot))
                            .toList();
            if (disposed) {
                return;
            }
            if (shown.isEmpty()) {
                onStatus.accept(tr("status.related.none", com.editora.config.PathDisplay.fileName(file)));
                return;
            }
            revealPath(shown.getFirst());
            if (shown.size() > 1) {
                ContextMenu menu = new ContextMenu();
                for (Path candidate : shown) {
                    MenuItem item = new MenuItem(historyLabel(candidate));
                    item.setMnemonicParsing(false);
                    item.setOnAction(event -> revealPath(candidate));
                    menu.getItems().add(item);
                }
                if (breadcrumbs.getScene() != null) {
                    menu.show(breadcrumbs, javafx.geometry.Side.BOTTOM, 0, 0);
                }
            }
        });
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
        cards.closeAll();
    }

    /**
     * A file under the root was rewritten on disk. That changes no listing, so no reload follows; it is
     * only of interest to a preview card showing the file (see {@link MapCards#filesChangedOnDisk}).
     */
    void filesChangedOnDisk() {
        if (!disposed) {
            cards.filesChangedOnDisk();
        }
    }

    void dispose() {
        bearingsFlush(); // into the workspace state; the window's session write is what saves it
        disposed = true;
        cards.stopRefreshing();
        mapLoader.dispose();
        loader.shutdownNow();
        structure.dispose();
        loadInFlight = false;
        loadingDelay.stop();
        surface.dispose();
        trails.close();
        cards.dispose();
        usages.dispose();
        orientation.dispose();
        dependencyIndex.dispose();
        metricsIndex.dispose();
        activity.dispose();
        noMatchesDelay.stop();
        if (helpPopup != null) {
            helpPopup.hide();
        }
    }

    private void updateFilters() {
        ProjectMapModel.TypeFilter type = typeFilter.getValue();
        surface.setFilters(new ProjectMapModel.Filters(
                ProjectMapSearch.nameFilter(query), // a path query's last name, a declaration query's name
                openFilter.isSelected(),
                modifiedFilter.isSelected(),
                gitFilter.isSelected(),
                bookmarksFilter.isSelected(),
                personalNotesFilter.isSelected(),
                problemsFilter.isSelected(),
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
        canvasExportMenu.setDisable(!enabled);
    }

    /** Pixels per point of the PNG export: the current view at twice its size, as a sharp screenshot is. */
    static final double CANVAS_PNG_SCALE = 2;

    /** Where an Export ▸ PNG… / SVG… is written: asks with a Save dialog; a test hands in its own file. */
    java.util.function.BiFunction<String, String, java.io.@Nullable File> canvasExportChooser =
            this::canvasChooseExportFile;

    /** How the PNG and SVG files are written: on a thread of their own, so a big map never holds the window. */
    java.util.concurrent.Executor canvasExportWriter =
            task -> Thread.ofVirtual().name("project-map-export").start(task);

    /**
     * ⋯ → Export ▸ PNG… (the current view at 2×, cards and all, without the zoom bar and overview) and SVG… (the
     * whole map as Print/PDF lays it out, every name as text) — Map explore, position 16. The file is chosen
     * first, then rendered on the FX thread and written off it; the status line says where it went.
     */
    private void canvasExport(boolean png) {
        Path current = root;
        if (disposed || current == null || surface.isEmpty()) {
            onStatus.accept(tr("status.projectMap.outputEmpty"));
            return;
        }
        Path folder = current.getFileName();
        String base = (folder == null ? "project" : folder.toString()) + "-map";
        java.io.File file = canvasExportChooser.apply(png ? "png" : "svg", base);
        if (file == null) {
            return;
        }
        if (png) {
            javafx.scene.image.WritableImage image = canvasViewImage();
            if (image == null) {
                onStatus.accept(tr("status.projectMap.outputEmpty"));
                return;
            }
            int width = (int) image.getWidth();
            int height = (int) image.getHeight();
            int[] argb = new int[width * height];
            image.getPixelReader()
                    .getPixels(
                            0, 0, width, height, javafx.scene.image.PixelFormat.getIntArgbInstance(), argb, 0, width);
            canvasWrite(file, out -> {
                java.awt.image.BufferedImage buffered =
                        new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                buffered.setRGB(0, 0, width, height, argb, 0, width);
                if (!javax.imageio.ImageIO.write(buffered, "png", out)) {
                    throw new java.io.IOException("no PNG writer");
                }
            });
        } else {
            String svg = surface.canvasSvg(tr("project.map.export.svgTitle", folder == null ? current : folder));
            if (svg == null) {
                onStatus.accept(tr("status.projectMap.outputEmpty"));
                return;
            }
            byte[] bytes = svg.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            canvasWrite(file, out -> out.write(bytes));
        }
    }

    /** What a writer puts in the file it is given. */
    private interface CanvasFileWrite {
        void write(java.io.OutputStream out) throws java.io.IOException;
    }

    /** Writes {@code file} through a temporary sibling, off the FX thread, and says how it went. */
    private void canvasWrite(java.io.File file, CanvasFileWrite write) {
        Path target = file.toPath().toAbsolutePath();
        canvasExportWriter.execute(() -> {
            String message;
            try {
                Path parent = target.getParent();
                Path temp = java.nio.file.Files.createTempFile(
                        parent == null ? Path.of(".") : parent, ".editora-map-", ".part");
                try {
                    try (java.io.OutputStream out =
                            new java.io.BufferedOutputStream(java.nio.file.Files.newOutputStream(temp))) {
                        write.write(out);
                    }
                    java.nio.file.Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    java.nio.file.Files.deleteIfExists(temp);
                }
                message = tr("project.map.export.done", target.getFileName());
            } catch (java.io.IOException | RuntimeException failed) {
                message = tr("project.map.export.failed", String.valueOf(failed.getMessage()));
            }
            String said = message;
            Platform.runLater(() -> onStatus.accept(said));
        });
    }

    /**
     * The map as it is on screen, at {@link #CANVAS_PNG_SCALE}× (less when that would pass 8,192 pixels a
     * side): canvas, connectors and cards, without the zoom bar, the overview and the loading notice. Null when
     * the map has no size.
     */
    javafx.scene.image.@Nullable WritableImage canvasViewImage() {
        double width = canvasHost.getWidth();
        double height = canvasHost.getHeight();
        if (width < 1 || height < 1) {
            return null;
        }
        double scale = Math.min(CANVAS_PNG_SCALE, 8_192 / Math.max(width, height));
        List<Node> chrome = new ArrayList<>(canvasHost.lookupAll(".project-map-zoom"));
        chrome.add(loadingLabel);
        chrome.add(noMatchesLabel);
        Map<Node, Boolean> visible = new HashMap<>();
        for (Node node : chrome) {
            visible.put(node, node.isVisible());
            node.setVisible(false);
        }
        surface.canvasHoldOverview(true);
        try {
            javafx.scene.SnapshotParameters parameters = new javafx.scene.SnapshotParameters();
            parameters.setTransform(javafx.scene.transform.Transform.scale(scale, scale));
            // A node's snapshot is taken in its parent's coordinates, where the map area starts below the filter
            // row and the breadcrumbs: the viewport starts there too, not at the parent's top (Map review 4 D2).
            javafx.geometry.Point2D origin = canvasHost.localToParent(0, 0);
            parameters.setViewport(new javafx.geometry.Rectangle2D(
                    origin.getX() * scale, origin.getY() * scale, width * scale, height * scale));
            return canvasHost.snapshot(parameters, null);
        } finally {
            visible.forEach(Node::setVisible);
            surface.canvasHoldOverview(false);
        }
    }

    /** The Save dialog of Export ▸ PNG… / SVG…, over the Map's window, in the project folder. */
    private java.io.@Nullable File canvasChooseExportFile(String extension, String baseName) {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(tr(extension.equals("png") ? "project.map.export.pngTitle" : "project.map.export.svgDialog"));
        chooser.setInitialFileName(baseName + "." + extension);
        chooser.getExtensionFilters()
                .add(new javafx.stage.FileChooser.ExtensionFilter(
                        extension.toUpperCase(java.util.Locale.ROOT), "*." + extension));
        Path current = root;
        if (current != null && com.editora.vfs.Vfs.isLocal(current) && java.nio.file.Files.isDirectory(current)) {
            chooser.setInitialDirectory(current.toFile());
        }
        javafx.stage.Window owner = getScene() == null ? null : getScene().getWindow();
        return chooser.showSaveDialog(owner);
    }

    /** ⋯ → Export ▸ PNG… or SVG…, as the menu runs it. */
    void canvasExportForTest(boolean png) {
        (png ? canvasExportPngItem : canvasExportSvgItem).fire();
    }

    java.util.function.Consumer<String> onStatus = message -> {};

    /** Where a folder that could not be read, or the limits, are reported (the window's status bar). */
    void setOnStatus(java.util.function.Consumer<String> onStatus) {
        this.onStatus = onStatus == null ? message -> {} : onStatus;
    }

    /** A reload the user asked for (opening a folder, "+N more", a reveal): says so if the limit is hit. */
    private void reloadForUser() {
        announceLimit = true;
        reload();
    }

    private void reload() {
        mapLoader.reload();
    }

    /** What the loader asks of the view: the window's state, the columns' settings, where a load lands. */
    private MapLoader.Host loaderHost() {
        return new MapLoader.Host() {
            @Override
            public boolean active() {
                return active;
            }

            @Override
            public boolean searching() {
                return ProjectMapView.this.searching();
            }

            @Override
            public boolean showHidden() {
                return showHiddenDefault;
            }

            @Override
            public Map<Path, Boolean> hiddenOverrides() {
                return surface.hiddenOverrides();
            }

            @Override
            public Map<Path, String> columnFilterQueries() {
                return surface.columnFilterQueries();
            }

            @Override
            public boolean compactChains() {
                return compactChainsOption.isSelected();
            }

            @Override
            public @Nullable Path selectedColumn() {
                return dataSelectedColumn();
            }

            @Override
            public void loadStarted() {
                ProjectMapView.this.loadStarted();
            }

            @Override
            public void loadFinished() {
                ProjectMapView.this.loadFinished();
            }

            @Override
            public void cleared() {
                surface.setEntries(List.of(), Set.of());
            }

            @Override
            public void loaded(
                    Path requestedRoot,
                    ProjectMapModel.Snapshot loaded,
                    Set<Path> loadedPaths,
                    boolean expansionChanged) {
                applySnapshot(requestedRoot, loaded, loadedPaths, expansionChanged);
            }
        };
    }

    /** The folder whose column holds the selected row, or null without a selection there. */
    private @Nullable Path dataSelectedColumn() {
        Path selected = surface.selectedPath();
        ProjectMapModel.Entry entry = selected == null ? null : surface.entryByPath.get(selected);
        if (entry == null || entry.isSymbol()) {
            return null;
        }
        return entry.parent();
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

    /** A load landed ({@link MapLoader}): the rows, the selection waiting for them, and what to report. */
    private void applySnapshot(
            Path requestedRoot, ProjectMapModel.Snapshot loaded, Set<Path> loadedPaths, boolean expansionChanged) {
        if (RemoteReadFailure.connectionClosed(requestedRoot)) {
            onStatus.accept(RemoteReadFailure.unreadable(requestedRoot)); // not "an empty project"
        }
        Path selectedBefore = surface.selectedPath();
        Set<Path> loadedFiles = new HashSet<>();
        for (ProjectMapModel.Entry entry : loaded.entries()) {
            if (entry.isFile()) {
                loadedFiles.add(entry.path());
            }
        }
        structure.prune(loadedFiles, cards.outlineFiles());
        showRows();
        structure.refresh(cards.outlineFiles()); // a file outlined before may have changed meanwhile
        setOutputEnabled(!loaded.entries().isEmpty());
        if (pendingSelection != null) {
            if (loadedPaths.contains(pendingSelection) || surface.contains(pendingSelection)) {
                surface.setSelected(pendingSelection);
                if (searching() && pendingSelection.equals(searchSelected) && !navigatingHistory) {
                    // The search's landing, loaded first: a place in the history like one that was loaded (B5).
                    recordSelection(
                            pendingSelection, historySearchAuto ? MapHistory.Head.LANDING : MapHistory.Head.PLACE);
                }
                // A search's first match, a reveal or a history step that had to be loaded is brought into
                // view as one that was already loaded is (E1). A restored selection keeps its restored view (A1).
                if (pendingSelection != viewsRestoredSelection) {
                    surface.revealAfterLoad(pendingSelection);
                }
            } else {
                dropHistory(pendingSelection); // gone: it must not be selected if it ever reappears
            }
            pendingSelection = null;
            viewsRestoredSelection = null;
        }
        // A selection that landed with the load (a reveal, a search, a history step) is not a select(): the
        // dependency overlay follows it here.
        dependencyOverlayUpdate(!java.util.Objects.equals(surface.selectedPath(), selectedBefore));
        searchResolveSymbol(); // a search landing on a declaration: its file's row may have just arrived
        viewsLoadLanded(); // a trail stop's card waiting for its row
        finishLoadMore(loaded, loadedPaths, selectedBefore);
        reportLimits(loaded, loadedPaths);
        windowReportUnreadable(requestedRoot, loaded);
        updateNavigation();
        if (expansionChanged) {
            onExpandedChanged.run();
        }
    }

    /** After "+N more": follow the rows that arrived, or undo the raise when the overall limit gave none. */
    private void finishLoadMore(ProjectMapModel.Snapshot loaded, Set<Path> loadedPaths, @Nullable Path selectedBefore) {
        MapLoader.Raised raised = mapLoader.finishLoadMore(loaded);
        if (raised == null) {
            return;
        }
        Path directory = raised.directory();
        int previousLoaded = raised.previousLoaded();
        Path moreRow = directory
                .resolve(ProjectMapModel.PLACEHOLDER_NAME)
                .toAbsolutePath()
                .normalize();
        if (moreRow.equals(selectedBefore)) {
            // The "+N more" row keeps its path from chunk to chunk; left on it, the selection would sit on the
            // next placeholder far below. Continue where the user was reading: the first row that arrived.
            List<Path> rows = new ArrayList<>();
            ProjectMapModel.Entry folder = surface.entryByPath.get(directory);
            if (folder != null) {
                for (ProjectMapModel.Entry entry : columnRowsInOrder(
                        loaded.entries(), new ProjectMapModel.ColumnId(folder.depth() + 1, directory))) {
                    if (!entry.isPlaceholder()) {
                        rows.add(entry.path());
                    }
                }
            }
            if (!rows.isEmpty()) {
                surface.select(rows.get(Math.min(previousLoaded, rows.size() - 1)));
            }
        }
    }

    /** The folders the last load could not read, so each is reported once, when it becomes unreadable. */
    private Set<Path> windowUnreadable = Set.of();

    /**
     * A folder that newly could not be read is said in the status bar, not only by the stub in its column,
     * which may be scrolled away or zoomed out. A closed remote connection is reported above instead.
     */
    private void windowReportUnreadable(Path requestedRoot, ProjectMapModel.Snapshot loaded) {
        Set<Path> now = new HashSet<>();
        loaded.directories().forEach((directory, facts) -> {
            if (facts.unreadable()) {
                now.add(directory);
            }
        });
        if (!RemoteReadFailure.connectionClosed(requestedRoot)) {
            for (Path directory : now) {
                if (!windowUnreadable.contains(directory)) {
                    Path name = directory.getFileName();
                    onStatus.accept(tr("project.map.status.unreadable", name == null ? directory : name));
                    break; // one line: the stubs show the rest
                }
            }
        }
        windowUnreadable = Set.copyOf(now);
    }

    /** Nothing is dropped silently: say when matches or folders did not fit the overall limit. */
    private void reportLimits(ProjectMapModel.Snapshot loaded, Set<Path> loadedPaths) {
        if (searching()) {
            List<Path> opened = mapLoader.openedMatches();
            if (announceMatches && (!opened.isEmpty() || searchMatchesPartial)) {
                int shown = 0;
                for (Path match : opened) {
                    if (loadedPaths.contains(match)) {
                        shown++;
                    }
                }
                // Everything that applies, in the Map window's own status line (A2), not one notice of three.
                String notice = resultsNotice(shown);
                if (!notice.isEmpty()) {
                    resultsNotify(notice);
                }
            }
        } else if (loaded.capped() && (announceLimit || !wasCapped)) {
            Path raised = mapLoader.lastRaised();
            Path reading = raised != null ? raised : dataSelectedColumn();
            if (reading != null && mapLoader.onlyAncestorsOpen(reading)) {
                // Closing a column would close the folder itself: its filter reaches the rest (A10).
                Path name = reading.getFileName();
                onStatus.accept(tr(
                        "project.map.status.limitUseFilter",
                        ProjectMapModel.MAX_VISIBLE_ITEMS,
                        name == null ? reading : name));
            } else {
                onStatus.accept(tr("project.map.status.limit", ProjectMapModel.MAX_VISIBLE_ITEMS));
            }
        }
        announceMatches = false;
        announceLimit = false;
        wasCapped = loaded.capped();
    }

    /** Removes a path that no longer resolves from the history, keeping it on the selection. */
    private void dropHistory(Path dead) {
        history.drop(dead, surface.selectedPath());
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
            historyMarkPlace(entry.path()); // an opened file is a place the history keeps (review 3 B5)
            onOpenFile.accept(entry.path());
        }
    }

    /**
     * Opens or closes one folder. During a search this edits the search's own view of the tree — closing a
     * folder the search opened really closes it — and never the manual set the search will hand back.
     */
    private void toggleDirectory(Path directory) {
        Path path = ProjectMapModel.normalize(directory);
        if (path == null) {
            return;
        }
        mapLoader.toggle(path);
        reloadForUser();
        onExpandedChanged.run();
    }

    /**
     * A column's own filter changed. In a folder with more rows than its limit loads, the filter has to see
     * the rows that did not load as well, so the folder is listed again with the filter (B8); the listing
     * cache makes that cheap. A folder that loaded whole is filtered where it is.
     */
    void truncatedColumnFilterChanged(@Nullable Path directory) {
        mapLoader.columnFilterChanged(directory);
    }

    /** Raises one truncated directory's row limit by a chunk; the overall limit still applies. */
    private void loadMore(@Nullable Path directory) {
        if (mapLoader.loadMore(directory)) {
            reloadForUser();
        }
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
        mapLoader.close(normalized);
        cards.closeUnder(normalized);
        if (surface.selectionBelow(normalized)) { // a selection elsewhere stays where it is
            pendingSelection = normalized;
            surface.select(normalized);
        }
        reload();
        onExpandedChanged.run();
    }

    /**
     * ⋯ → Collapse All ({@code row} null: the selection; {@code keepOwn} false) and a row's Close Other Branches
     * ({@code keepOwn} true): every column but the first and the ones on the way to the row closes, folder and
     * Structure columns alike, with the cards opened from them (Map explore, position 13). The selection stays
     * where it is. The status line says so when there was nothing to close.
     */
    void canvasCloseOthers(@Nullable Path row, boolean keepOwn) {
        Path current = root;
        if (current == null) {
            return;
        }
        Path target = row != null ? row : surface.selectedPath();
        if (target == null || !target.startsWith(current)) {
            target = current;
        }
        List<Path> closed = mapLoader.canvasCloseOthers(target, keepOwn);
        boolean structureClosed = structure.canvasCloseOthers(target, keepOwn);
        if (closed.isEmpty() && !structureClosed) {
            onStatus.accept(tr("project.map.status.nothingToCollapse"));
            return;
        }
        closed.forEach(cards::closeUnder);
        Path chosen = target;
        if (closed.stream().anyMatch(surface::selectionBelow)) {
            pendingSelection = chosen; // the selection was in a branch that closed: the row it was asked on
            surface.select(chosen);
        }
        if (closed.isEmpty()) {
            showRows();
        } else {
            reload();
            onExpandedChanged.run();
        }
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
        ProjectMapModel.Snapshot snapshot = mapLoader.snapshot();
        List<ProjectMapModel.Entry> rows = structure.withStructure(snapshot.entries());
        Set<Path> open = new HashSet<>(snapshot.loadedDirectories());
        open.addAll(structure.openAmong(rows));
        surface.setEntries(rows, open);
        updateNavigation();
    }

    /** An outline arrived or changed: the columns are redrawn and every declaration card follows its lines. */
    private void structureChanged(Path file) {
        if (disposed) {
            return;
        }
        // The map's rows change only when the file has a Structure column of the Map's own open: an outline that
        // feeds only a card's column leaves the rows, their caches and the tooltip alone (review 3, D8).
        if (structure.isOpen(file)) {
            showRows();
        }
        searchResolveSymbol(); // a search landing on a declaration waited for this outline
        cards.outlineChanged(file);
        if (structure.view(file).problem() != ProjectMapStructure.Problem.LOADING) {
            usages.outlineChanged(file, usagesOutline(file)); // counted again once the outline settles (gap 11)
        }
        bearingsRestoreDeclarations(file); // a declaration card of the last session waited for this outline
    }

    /** The files whose declarations are listed now: a Structure column of the Map's, or a card's (gap 11). */
    private Set<Path> usagesFiles() {
        Set<Path> files = new java.util.HashSet<>(structure.openFiles());
        files.addAll(cards.structureColumnFiles());
        return files;
    }

    /** {@code file}'s outline, or null while it has none. */
    private @Nullable List<ProjectMapStructure.Node> usagesOutline(Path file) {
        ProjectMapStructure.View view = structure.view(file);
        return view.problem() == ProjectMapStructure.Problem.NONE ? view.roots() : null;
    }

    /** Usage counts came or went: the declaration rows are drawn again. */
    private void usagesChanged() {
        surface.repaint();
        cards.usagesChanged();
    }

    /** Where the cards' editor font comes from: the editor window this Map belongs to. */
    void setEditorFontSource(java.util.function.Supplier<javafx.stage.@Nullable Window> source) {
        cards.setEditorFontSource(source);
    }

    /** A declaration row: opens (or focuses) the card of its lines (see {@link MapCards#openDeclarationCard}). */
    private void previewSymbol(ProjectMapModel.Entry entry) {
        historyMarkPlace(entry.path());
        cards.openDeclarationCard(entry);
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

    /**
     * Where a library card's Open goes (Map code browser gap 5): the editor's library tab of the class, at a 0-based
     * line — never the Map's read-only copy of its source.
     */
    void setOpenLibrary(java.util.function.BiConsumer<MapLibrarySources.Library, Integer> open) {
        onOpenLibrary = open == null ? (library, line) -> {} : open;
    }

    /** Opens {@code file}'s library tab at {@code line} when it is a library card's file; false for any other file. */
    private boolean openLibrary(Path file, int line) {
        MapLibrarySources.Library library = MapLibrarySources.of(file);
        if (library == null) {
            return false;
        }
        onOpenLibrary.accept(library, line);
        return true;
    }

    /** The language server's outline of an open buffer, when the editor has one (see ProjectMapStructure). */
    void setServerSymbols(Function<Path, java.util.@Nullable List<com.editora.lsp.SymbolNode>> symbols) {
        structure.setServerSymbols(symbols);
    }

    /** A running language server's outline of a file with no tab (Map explore round, position 10). */
    void setClosedFileSymbols(ProjectMapStructure.@Nullable ClosedFileSymbols symbols) {
        structure.setClosedFileSymbols(symbols);
    }

    private void selectionChanged(Path path) {
        if (path == null) {
            return;
        }
        if (!navigatingHistory) {
            recordSelection(
                    path,
                    surface.movingSibling
                            ? MapHistory.Head.STEP
                            : historySearchLanding ? MapHistory.Head.LANDING : MapHistory.Head.PLACE);
        }
        updateNavigation();
        resultsFollowSelection(path);
        bearingsChanged();
        dependencyOverlayUpdate(true);
    }

    private void previewSelection(Path path) {
        ProjectMapModel.Entry entry = surface.selectedEntry()
                .filter(candidate -> candidate.path().equals(path))
                .orElse(null);
        if (entry != null && entry.isFile()) {
            historyMarkPlace(entry.path()); // a previewed file is a place too
            cards.openFileCard(entry.path());
        }
    }

    private ProjectMapPreview.@Nullable Content previewContentFor(Path path) {
        try {
            return previewContent.apply(path);
        } catch (RuntimeException failed) {
            ProjectMapLog.unexpected("reading a card's open buffer", failed);
            return null;
        }
    }

    // --- cards (MapCards): what the view and its tests still name here ---------------------------------------

    /** Opens the note card of {@code path}: a folder's notes, or every note of a file (its sticky notes' "+K more"). */
    private void previewNotes(Path path) {
        cards.openNoteCard(path);
    }

    /**
     * A row's Personal Notes (its badge, its menu): a file's open in its code card with the sticky notes out, a
     * folder's in its note card (Map explore round, position 11).
     */
    private void cardsShowNotes(Path path) {
        ProjectMapModel.Entry entry = surface.entryByPath.get(path);
        cards.openNotes(path, entry != null && entry.isFile()); // no row: the note card, as before
    }

    private ProjectMapPreview createPreview(Path path) {
        return cards.createPreview(path);
    }

    private void closePreview(ProjectMapPreview preview) {
        cards.close(preview);
    }

    private void refreshCards(boolean polled) {
        cards.refresh(polled);
    }

    private void repaintPreviewConnectors() {
        cards.drawConnectors();
    }

    private void scheduleCardRefresh() {
        cards.scheduleRefresh();
    }

    void updateCardVisibility() {
        cards.updateVisibility();
    }

    /** What the cards need of the map: the surface's rows, layout, zoom and colours, and the view's actions. */
    private MapCards.Host cardsHost() {
        return new MapCards.Host() {
            @Override
            public void followed(ProjectMapPreview from, int fromCaret, ProjectMapPreview to) {
                Path fromPlace = cards.placeOf(from);
                Path toPlace = cards.placeOf(to);
                if (fromPlace != null && toPlace != null) {
                    history.followed(from, fromPlace, fromCaret, to, toPlace);
                    updateNavigation();
                }
            }

            @Override
            public boolean historyStep(ProjectMapPreview card, boolean back) {
                return ProjectMapView.this.historyStep(card, back);
            }

            @Override
            public void showDependencies(ProjectMapPreview card, Path file) {
                openDependencies(file, card);
            }

            @Override
            public void cardClosed(ProjectMapPreview card) {
                history.cardClosed(card);
                updateNavigation();
            }

            @Override
            public double @Nullable [] rowBox(Path path) {
                MapSurface.NodeBox box = surface.nodeBox(path);
                return box == null ? null : new double[] {box.x(), box.y(), box.width(), box.height()};
            }

            @Override
            public MapCards.Site site(Path path) {
                return surface.cardSite(path);
            }

            @Override
            public List<double[]> chrome() {
                return surface.cardChrome();
            }

            @Override
            public List<double[]> columns() {
                return surface.cardColumns();
            }

            @Override
            public List<double[]> allColumns() {
                return surface.cardAllColumns();
            }

            @Override
            public boolean focusNewCards() {
                return focusNewColumn.isSelected();
            }

            @Override
            public double @Nullable [] rootColumn() {
                return surface.cardRootColumn();
            }

            @Override
            public double zoom() {
                return surface.cardZoom();
            }

            @Override
            public boolean horizontalFlow() {
                return surface.flowDirection == FlowDirection.LEFT_TO_RIGHT
                        || surface.flowDirection == FlowDirection.RIGHT_TO_LEFT;
            }

            @Override
            public boolean reverseFlow() {
                return surface.flowDirection == FlowDirection.RIGHT_TO_LEFT
                        || surface.flowDirection == FlowDirection.BOTTOM_TO_TOP;
            }

            @Override
            public boolean narrowsAway(Path file) {
                return surface.narrowsAway(file);
            }

            @Override
            public Set<Path> expandedSnapshot() {
                return surface.expandedSnapshot;
            }

            @Override
            public @Nullable Path root() {
                return root;
            }

            @Override
            public Color accent() {
                return surface.accentColor();
            }

            @Override
            public Color note() {
                return surface.noteColor();
            }

            @Override
            public List<MenuItem> viewsCardMenu(ProjectMapPreview card) {
                return trails.cardMenu(card); // Add this card as a stop
            }

            @Override
            public void focusMap() {
                if (!disposed) {
                    surface.requestFocus();
                }
            }

            @Override
            public boolean onScreen() {
                return !disposed && showingOnScreen();
            }

            @Override
            public void status(String message) {
                onStatus.accept(message);
            }

            @Override
            public ProjectMapPreview.@Nullable Content content(Path file) {
                return previewContentFor(file);
            }

            @Override
            public boolean isOpen(Path file) {
                return safeTest(isOpen, file);
            }

            @Override
            public boolean isModified(Path file) {
                return safeTest(isModified, file);
            }

            @Override
            public ContextMenu symbolMenu(ProjectMapModel.Entry entry, Runnable preview) {
                return ProjectMapView.this.symbolMenu(entry, preview);
            }

            @Override
            public boolean hideOpenNotes() {
                return hideOpenNotes.isSelected();
            }

            @Override
            public void showOpenNotes() {
                hideOpenNotes.setSelected(false);
            }

            @Override
            public void openFile(Path file) {
                if (!openLibrary(file, 0)) {
                    onOpenFile.accept(file);
                }
            }

            @Override
            public void openAt(Path file, int line) {
                if (!openLibrary(file, line)) {
                    onOpenAt.accept(file, line);
                }
            }

            @Override
            public void openNarrowed(Path file, com.editora.text.FoldRegions.Region block) {
                if (!openLibrary(file, block.startLine())) {
                    onOpenNarrowed.accept(file, block);
                }
            }

            @Override
            public void panBy(double dx, double dy) {
                surface.panBy(dx, dy);
            }

            @Override
            public boolean showInStructure(Path file, Path declaration) {
                return cardsShowInMapStructure(file, declaration);
            }
        };
    }

    /**
     * A declaration card's Show in Structure, on the Map's own Structure column of {@code file} (Map explore round,
     * position 8): the column and those of the declarations that hold it open, and its row is selected and brought
     * into view. False when the file's row is not on the map, or the declaration not in its outline.
     */
    private boolean cardsShowInMapStructure(Path file, Path declaration) {
        Path parent = file.getParent();
        if (disposed || parent == null || !surface.contains(file) || structure.node(declaration) == null) {
            return false;
        }
        boolean opened = false;
        if (!structure.isOpen(file)) {
            opened = structure.toggle(new ProjectMapModel.Entry(file, parent, 0, false));
            if (!opened) {
                return false;
            }
        }
        java.util.ArrayDeque<Path> holders = new java.util.ArrayDeque<>();
        for (Path holder = declaration.getParent(); holder != null && !holder.equals(file); ) {
            holders.push(holder);
            holder = holder.getParent();
        }
        Path holderParent = file;
        for (Path holder : holders) {
            ProjectMapStructure.Node node = structure.node(holder);
            if (node != null && !structure.isOpen(holder)) {
                opened |= structure.toggle(ProjectMapModel.Entry.symbol(holder, holderParent, 0, node.symbol()));
            }
            holderParent = holder;
        }
        if (opened || !surface.contains(declaration)) {
            showRows();
        }
        if (!surface.contains(declaration)) {
            return false;
        }
        surface.select(declaration);
        surface.revealAfterLoad(declaration);
        surface.requestFocus();
        return true;
    }

    /** {@code value} within [{@code minimum}, {@code maximum}]; unlike {@link Math#clamp} it tolerates an empty range. */
    static double clampInto(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /** How long a pause starts a new type-ahead prefix in a column (review 3 B14). */
    static final long TYPE_AHEAD_RESET_MILLIS = 1000;

    /**
     * The row a column's type-ahead goes to: the first of {@code names}, from {@code current} on and wrapping,
     * whose name starts with {@code prefix} (ignoring case). {@code advance} starts after {@code current} — a new
     * initial steps on — while a longer prefix keeps a row that still matches. -1 when none does. Pure.
     */
    static int typeAheadTarget(List<String> names, int current, String prefix, boolean advance) {
        if (names.isEmpty() || prefix.isEmpty()) {
            return -1;
        }
        String wanted = prefix.toLowerCase(java.util.Locale.ROOT);
        int start = current < 0 ? 0 : advance ? current + 1 : current;
        for (int step = 0; step < names.size(); step++) {
            int index = Math.floorMod(start + step, names.size());
            if (names.get(index).toLowerCase(java.util.Locale.ROOT).startsWith(wanted)) {
                return index;
            }
        }
        return -1;
    }

    /** The name type-ahead matches a row by: a declaration's label, else the file or folder name. */
    static String keysRowName(ProjectMapModel.Entry entry) {
        ProjectMapModel.Symbol symbol = entry.symbol();
        if (symbol != null) {
            return symbol.label();
        }
        Path name = entry.path().getFileName();
        return name == null ? entry.path().toString() : name.toString();
    }

    /** {@link MapCards#cardOverlapCost}, where the placement tests find it. Pure. */
    static double cardOverlapCost(double x, double y, double w, double h, List<double[]> obstacles) {
        return MapCards.cardOverlapCost(x, y, w, h, obstacles);
    }

    private void recordSelection(Path path) {
        recordSelection(path, MapHistory.Head.PLACE);
    }

    /** Records a selection in the Back/Forward history as a place or as the head of a run (see {@link MapHistory}). */
    private void recordSelection(Path path, MapHistory.Head kind) {
        Path normalized = ProjectMapModel.normalize(path);
        if (normalized == null || !history.record(normalized, kind)) {
            return;
        }
        updateNavigation();
        bearingsChanged();
    }

    /**
     * {@code path} became a place — a file opened or a card opened on it: the history keeps it, so a later step
     * goes after it instead of replacing it (review 3 B5: the file that was opened used to vanish from the trail).
     */
    private void historyMarkPlace(@Nullable Path path) {
        if (path != null && !navigatingHistory) {
            recordSelection(path, MapHistory.Head.PLACE);
        }
    }

    /** Selects where the search landed; the history records it as a landing or, stepped to, a place. */
    private void historySelectLanding(Path target) {
        historySearchLanding = historySearchAuto;
        try {
            surface.select(target);
        } finally {
            historySearchLanding = false;
        }
    }

    /** The Back/Forward menu: every place in the history, the newest first, the current one marked. */
    private void historyShowMenu(Button owner, ContextMenuEvent event) {
        ContextMenu menu = historyMenu();
        if (menu.getItems().isEmpty()) {
            return;
        }
        menu.show(owner, javafx.geometry.Side.BOTTOM, 0, 0);
        event.consume();
    }

    /**
     * The history as menu items (review 3 B5): each place's name and the folder it is in, the newest first; a stop on
     * a card says the line its caret was on.
     */
    ContextMenu historyMenu() {
        ContextMenu menu = new ContextMenu();
        menu.getStyleClass().add("project-map-history-menu");
        List<MapHistory.Stop<ProjectMapPreview>> stops = history.stops();
        for (int index = stops.size() - 1; index >= 0; index--) {
            MapHistory.Stop<ProjectMapPreview> stop = stops.get(index);
            int target = index;
            CheckMenuItem item = new CheckMenuItem(historyLabel(stop));
            item.setMnemonicParsing(false);
            item.setSelected(index == history.index());
            item.setOnAction(event -> historyGo(history.jump(target)));
            menu.getItems().add(item);
        }
        return menu;
    }

    /** A stop's label: its place's, and on an open card the line its caret was on ("name — folder, line 12"). */
    String historyLabel(MapHistory.Stop<ProjectMapPreview> stop) {
        String label = historyLabel(stop.place());
        ProjectMapPreview card = stop.card();
        if (card == null || !cards.isOpen(card)) {
            return label;
        }
        int caret = stop.caret() >= 0 ? stop.caret() : card.caretOffset();
        return tr("project.map.history.cardEntry", label, String.valueOf(card.fileLineAt(caret) + 1));
    }

    /** A history entry's label: its name, then the project-relative folder it is in ("name — folder"). */
    String historyLabel(Path place) {
        Path currentRoot = root;
        Path file = place;
        String name = crumbLabel(place);
        while (ProjectMapStructure.isSymbolPath(file) && file.getParent() != null) {
            file = file.getParent(); // a declaration: named by its label, placed by its file
        }
        if (file != place) {
            name = name + " · " + crumbLabel(file);
        }
        if (currentRoot == null || file.equals(currentRoot)) {
            return name;
        }
        Path folder = file.getParent();
        if (folder == null || !folder.startsWith(currentRoot)) {
            return name;
        }
        String relative = currentRoot.relativize(folder).toString().replace('\\', '/');
        return relative.isEmpty() ? name : tr("project.map.history.entry", name, relative);
    }

    /** Back ({@code delta} -1) or Forward (+1) on the map: Alt+←/→, the ‹ › buttons, Back typed on the map. */
    void moveHistory(int delta) {
        if (!historyGo(history.step(delta, null, null))) {
            historyNowhere(delta < 0);
        }
    }

    /**
     * Back ({@code back}) or Forward typed in {@code card}: the same history, the card's caret kept on its stop. False,
     * and the status line says so, when there is nowhere to go.
     */
    boolean historyStep(ProjectMapPreview card, boolean back) {
        if (historyGo(history.step(back ? -1 : 1, card, cards.placeOf(card)))) {
            return true;
        }
        historyNowhere(back);
        return false;
    }

    private void historyNowhere(boolean back) {
        onStatus.accept(tr(back ? "project.map.history.noBack" : "project.map.history.noForward"));
    }

    /**
     * Goes to {@code stop}, now the history's current one: a card still open comes into view with its caret where it
     * was and the keyboard in it; any other stop's place is revealed. False for none.
     */
    private boolean historyGo(MapHistory.@Nullable Stop<ProjectMapPreview> stop) {
        if (stop == null) {
            return false;
        }
        navigatingHistory = true;
        try {
            ProjectMapPreview card = stop.card();
            if (card != null) {
                cards.goToStop(card, stop.caret());
            } else if (stop.line() >= 0 && (card = cards.reopenStop(stop.place(), stop.line())) != null) {
                history.reopened(card); // a card closed since (a preview another Go to replaced): open again
            } else {
                revealPath(stop.place());
            }
        } finally {
            navigatingHistory = false;
        }
        updateNavigation();
        bearingsChanged();
        return true;
    }

    /**
     * {@code nav.back} and {@code nav.forward} typed on the map, or in a card that leaves them to it: the Map's Back and
     * Forward, from the card the keyboard is in. False for any other command.
     */
    private boolean historyCommand(String commandId) {
        boolean back = ProjectMapPreview.BACK_COMMAND.equals(commandId);
        if (!back && !ProjectMapPreview.FORWARD_COMMAND.equals(commandId)) {
            return false;
        }
        ProjectMapPreview card = focusedCard();
        if (card != null) {
            historyStep(card, back);
        } else {
            moveHistory(back ? -1 : 1);
        }
        return true;
    }

    /** The places of the history, the oldest first. For tests. */
    List<Path> historyPlaces() {
        return history.places();
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
            surface.layoutRevealPlace(); // a place, not a step: its path in view, a third down, flashed (B7)
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
        // Kept loaded even when it sorts beyond its folder's row limit.
        mapLoader.openTo(ProjectMapModel.ancestorsWithin(root, List.of(normalized)), normalized);
        pendingSelection = normalized;
        if (!navigatingHistory) {
            recordSelection(normalized); // as select() records a loaded target; dropped again if it never loads
        }
        reloadForUser();
        onExpandedChanged.run();
    }

    private void updateNavigation() {
        backButton.setDisable(!history.canGoBack());
        forwardButton.setDisable(!history.canGoForward());
        // A "+N more" or stub row is not a place: the trail ends at its folder, never at a "…" crumb.
        Path selected = surface.selectedEntry()
                .map(entry -> entry.isPlaceholder() ? entry.parent() : entry.path())
                .orElse(pendingSelection);
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
        crumb.setMnemonicParsing(false); // "zz_generated.go" keeps its underscore (review 3 E4)
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

    /** One column's rows, filters aside, in the order the map shows them (the model's sort). */
    static List<ProjectMapModel.Entry> columnRowsInOrder(
            List<ProjectMapModel.Entry> rows, ProjectMapModel.ColumnId id) {
        for (ProjectMapModel.Column column : ProjectMapModel.columnsById(rows, Map.of(), Map.of(), null)) {
            if (column.id().equals(id)) {
                return column.entries();
            }
        }
        return List.of();
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
        return Set.of(
                "M-left", "M-right", mac ? "Cmd-0" : "C-0", "C-n", "C-p", "f2", "delete", "S-enter", "f3", "S-f3");
    }

    static boolean safeTest(Predicate<Path> predicate, Path path) {
        try {
            return predicate != null && predicate.test(path);
        } catch (RuntimeException failed) {
            ProjectMapLog.unexpected("checking a row's state", failed);
            return false;
        }
    }

    static Color mix(Color base, Color overlay, double amount) {
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
