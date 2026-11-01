package com.editora.ui;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
import javafx.scene.AccessibleAttribute;
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
import javafx.scene.control.Control;
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

import com.editora.git.GitFileStatus;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Spatial navigator for a project. Native JavaFX controls own filtering and zoom; the hierarchy itself is
 * drawn on a {@link Canvas}. Node context menus reuse the Project tree's file-management actions.
 */
final class ProjectMapView extends VBox {

    /** Selections kept for Back/Forward; the oldest are dropped beyond this. */
    static final int MAX_SELECTION_HISTORY = 100;

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

    private Consumer<Boolean> onCompactRowsChanged = ignored -> {};
    private Consumer<Boolean> onHideIgnoredChanged = ignored -> {};
    /** Why rows are dimmed, and the project's facts for the Overview card and the folder rows (review 3 A11, B4). */
    private final ProjectMapOrientation orientation = new ProjectMapOrientation(this::layoutOrientationChanged);
    /**
     * Whether this workspace's Overview card was seen; null until the owner says (a view nobody configured never
     * opens it by itself). False opens it on the project's first Map open.
     */
    private @Nullable Boolean overviewSeen;

    private Runnable onOverviewSeen = () -> {};
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
    private final ComboBox<FlowDirection> flowFilter = new ComboBox<>();
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
    private final MapSurface surface = new MapSurface();
    /** The cards over the map, and what is stuck on them (see {@link MapCards}). */
    private final MapCards cards;
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
    private final List<Path> selectionHistory = new ArrayList<>();

    private @Nullable Path root;
    private boolean disposed;
    /**
     * Whether the Map's window is showing ({@link #setShowing}). While it is not, nothing is listed, no card is
     * refreshed or polled and row states are not snapshotted: the window's scene outlives {@code hide()}, so
     * being in a scene no longer means being on screen. True for a view used outside the Map window (tests).
     */
    private boolean active = true;
    /** Row states changed while the window was hidden: the next show snapshots them once. */
    private boolean showingStatesStale;
    /** Something asked for a paint while the window was hidden. */
    private boolean dataRepaintWhileHidden;
    /** Whether {@link #setOpenFiles} and {@link #setMarkerCandidates} were given, so rows are looked up from them. */
    private boolean showingOpenFilesGiven;

    private boolean showingMarkersGiven;
    /** The Project "show hidden files" setting: each column's default, and what the loader lists. */
    private boolean showHiddenDefault = true;

    private boolean loadInFlight;
    private boolean announceLimit;
    private boolean announceMatches;
    private boolean wasCapped;
    private java.util.function.Supplier<java.util.Collection<Path>> openFiles = List::of;
    private java.util.function.Supplier<java.util.Collection<Path>> markerCandidates = List::of;
    private java.util.function.BiConsumer<Boolean, Boolean> onNavigationChanged = (keepZoom, focusColumn) -> {};
    private String query = "";
    private Map<Path, GitFileStatus> gitStatus = Map.of();
    private Set<Path> gitChangedDirectories = Set.of();
    private Runnable onExpandedChanged = () -> {};
    private int historyIndex = -1;
    private boolean navigatingHistory;
    /** What the newest history entry is: a place, or where a run of steps or of search landings has got to. */
    private HistoryHead historyHead = HistoryHead.PLACE;
    /** Set while the search lands on its first match by itself, so that landing is recorded as one. */
    private boolean historySearchLanding;
    /** Whether the search's current landing is its own (the first match of a typed query), not a step to a match. */
    private boolean historySearchAuto;

    /**
     * How the newest Back/Forward entry got there (Project Map review 3 B5). History records places — a file
     * opened or previewed, a search landing, a reveal, a breadcrumb jump, a row picked with the pointer — and a
     * run of keyboard steps (sibling, parent and child moves, Home/End, type-ahead) is one entry, the row the run
     * leads to. The landings the search makes as a query is typed are one entry too, its last.
     */
    enum HistoryHead {
        /** A place: never replaced; the next entry goes after it. */
        PLACE,
        /** Where a run of keyboard steps has got to: the next step replaces it. */
        STEP,
        /** Where the search landed by itself: the next such landing replaces it. */
        LANDING
    }

    private Runnable onClearSearch = () -> {};
    private Runnable onLeaveMap = () -> {};
    private Consumer<ProjectMapModel.Entry> onRenameEntry = entry -> {};
    private Consumer<ProjectMapModel.Entry> onDeleteEntry = entry -> {};
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
            }
        }
    };
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
        previews = cards.previews;
        notePreviews = cards.notePreviews;
        cardStructures = cards.cardStructures;
        previewConnectors = cards.previewConnectors;
        FlowPane filters = buildFilters();
        HBox navigation = buildNavigation();
        buildCanvasHost();
        getChildren().addAll(filters, navigation, canvasHost);
        VBox.setVgrow(getChildren().get(2), Priority.ALWAYS);
        surface.setOnActivate(this::activate);
        surface.setOnCloseColumn(this::closeColumn);
        surface.setOnPreview(this::previewSelection);
        surface.setOnStructure(this::toggleStructure);
        surface.setOnNotesPreview(this::previewNotes);
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
        options.getItems()
                .addAll(
                        keepZoomOnOpen,
                        focusNewColumn,
                        singleClickOpens,
                        compactChainsOption,
                        layoutCompactRowsOption,
                        layoutHideIgnoredOption,
                        hideOpenNotes,
                        followActiveFile,
                        new SeparatorMenuItem(),
                        revealActiveItem,
                        layoutOverviewItem,
                        openCardsMenu,
                        new SeparatorMenuItem(),
                        printButton,
                        exportPdfButton);
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

    /** Whether this workspace's Overview card was seen (true while the owner has not said). */
    boolean isOverviewSeen() {
        return !Boolean.FALSE.equals(overviewSeen);
    }

    /**
     * What the workspace remembers: false opens the Overview card on the project's next Map open (once; it is then
     * seen, and {@link #setOnOverviewSeen} is told).
     */
    void setOverviewSeen(boolean seen) {
        overviewSeen = seen;
        if (!seen) {
            layoutMaybeOpenOverview();
        }
    }

    /** Told when the Overview card opened by itself, for the workspace to remember that it was seen. */
    void setOnOverviewSeen(@Nullable Runnable callback) {
        onOverviewSeen = callback == null ? () -> {} : callback;
    }

    /**
     * Where the Overview card and the folder tooltips get the project's file list (called off the FX thread): the
     * Map search's list, when the panel has one. Without it the view lists the project itself once.
     */
    void setOverviewFileSource(@Nullable Function<Path, com.editora.search.ProjectFileList.@Nullable Snapshot> source) {
        orientation.setFileSource(source);
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
        cards.openOverviewCard(forRoot, facts, this::revealPath, onOpenFile);
    }

    /** The Overview card opens by itself on a project's first Map open, when the workspace has not seen it. */
    private void layoutMaybeOpenOverview() {
        Path current = root;
        if (!Boolean.FALSE.equals(overviewSeen) || !active || current == null || disposed || surface.isEmpty()) {
            return;
        }
        overviewSeen = true;
        onOverviewSeen.run();
        orientation.loadFacts(false, facts -> layoutShowOverview(current, facts));
    }

    /** New rows are on the map: why each is dimmed is worked out, and the project's facts once per root. */
    private void layoutEntriesShown(List<ProjectMapModel.Entry> shown) {
        if (root == null || shown.isEmpty()) {
            return;
        }
        orientation.classify(shown);
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
            helpPopup.getContent().add(buildHelp());
            helpPopup.setOnHidden(event -> helpHiddenAt = System.nanoTime());
        }
        javafx.geometry.Bounds bounds = anchor.localToScreen(anchor.getBoundsInLocal());
        if (bounds != null) {
            helpPopup.show(anchor, bounds.getMinX(), bounds.getMaxY() + 4);
        }
    }

    /**
     * The popover's content: what the Map is for, then the pointer, cards and notes, keyboard and filter-row
     * models in two columns. It follows the doc's interaction reference (E8). Key names are spelled in the
     * user's language and modifiers as the platform shows them (⌘ ⌥ ⇧ on macOS), never English key-code names.
     */
    private static Node buildHelp() {
        Label intro = new Label(tr("project.map.help.intro"));
        intro.getStyleClass().addAll("project-map-help-text", "project-map-help-intro");
        intro.setWrapText(true);
        intro.setMaxWidth(560);

        GridPane pointer = helpGrid();
        int row = helpHeading(pointer, 0, "project.map.help.mouse");
        for (String id : List.of(
                "click",
                "doubleClick",
                "chevron",
                "preview",
                "structure",
                "symbol",
                "symbolChevron",
                "noteBadge",
                "rightClick")) {
            row = helpRow(pointer, row, tr("project.map.help." + id + ".gesture"), "project.map.help." + id);
        }
        row = helpRow(pointer, row, tr("project.map.help.wheel.gesture"), "project.map.help.wheel");
        row = helpRow(
                pointer,
                row,
                tr("project.map.help.fastWheel.gesture", helpModifier("shortcut")),
                "project.map.help.fastWheel");
        for (String id : List.of(
                "swipe", "pinch", "panWheel", "dragCanvas", "middleDrag", "dragHeader", "lock", "close", "overview")) {
            row = helpRow(pointer, row, tr("project.map.help." + id + ".gesture"), "project.map.help." + id);
        }
        row = helpHeading(pointer, row, "project.map.help.cards");
        for (String id : List.of("cardTitle", "cardGrip", "cardStructure", "cardNotes", "addNote", "sticky")) {
            row = helpRow(pointer, row, tr("project.map.help." + id + ".gesture"), "project.map.help." + id);
        }

        GridPane keys = helpGrid();
        row = helpHeading(keys, 0, "project.map.help.keyboard");
        row = helpRow(keys, row, "↑ ↓ ← →", "project.map.help.arrows");
        row = helpRow(
                keys, row, helpChord("control", "N") + " / " + helpChord("control", "P"), "project.map.help.siblings");
        row = helpRow(keys, row, helpKey("enter") + " / " + helpKey("space"), "project.map.help.activate");
        row = helpRow(keys, row, helpChord("shift", helpKey("enter")), "project.map.help.shiftEnter");
        row = helpRow(keys, row, helpKey("backspace"), "project.map.help.parent");
        row = helpRow(keys, row, helpKey("home") + " / " + helpKey("end"), "project.map.help.home");
        row = helpRow(keys, row, tr("project.map.help.typeAhead.gesture"), "project.map.help.typeAhead");
        row = helpRow(keys, row, helpKey("pageUp") + " / " + helpKey("pageDown"), "project.map.help.page");
        row = helpRow(keys, row, "/", "project.map.help.columnFilter");
        row = helpRow(keys, row, helpChord("alt", "←") + " / " + helpChord("alt", "→"), "project.map.help.history");
        row = helpRow(keys, row, helpChord("shortcut", "0"), "project.map.help.fit");
        row = helpRow(keys, row, "F2", "project.map.help.rename");
        row = helpRow(keys, row, helpKey("delete"), "project.map.help.delete");
        row = helpRow(keys, row, helpKey("menu") + " / " + helpChord("shift", "F10"), "project.map.help.menu");
        row = helpRow(keys, row, helpKey("escape"), "project.map.help.escape");
        // What the search field finds and how to move through its matches, and how to find where you are
        // (review 3 B19): the popover said nothing about either.
        row = helpHeading(keys, row, "project.map.help.search");
        row = helpRow(
                keys,
                row,
                tr("project.map.help.searchField.gesture", tr("project.map.filterPrompt")),
                "project.map.help.searchField");
        row = helpRow(keys, row, "@", "project.map.help.searchSymbols");
        row = helpRow(keys, row, "#", "project.map.help.searchFiles");
        row = helpRow(keys, row, "↓", "project.map.help.searchDown");
        row = helpRow(
                keys,
                row,
                helpKey("enter") + " / " + helpChord("shift", helpKey("enter")),
                "project.map.help.searchStep");
        row = helpRow(keys, row, helpChord("shortcut", helpKey("enter")), "project.map.help.searchOpen");
        row = helpRow(keys, row, "F3 / " + helpChord("shift", "F3"), "project.map.help.searchStepMap");
        row = helpHeading(keys, row, "project.map.help.where");
        row = helpRow(keys, row, tr("project.map.help.reveal.gesture"), "project.map.help.reveal");
        row = helpRow(keys, row, tr("project.map.navigation.followActiveFile"), "project.map.help.follow");
        row = helpRow(keys, row, tr("project.map.overview.open"), "project.map.help.overviewCard");
        row = helpHeading(keys, row, "project.map.help.controls");
        row = helpRow(keys, row, tr("project.map.help.chips.gesture"), "project.map.help.chips");
        row = helpRow(keys, row, tr("project.map.filter.hideUnmatched"), "project.map.help.hideUnmatched");
        helpRow(keys, row, tr("project.map.filter.clear"), "project.map.help.clear");

        HBox columns = new HBox(24, pointer, keys);
        VBox help = new VBox(8, intro, columns);
        help.getStyleClass().add("project-map-help");
        return help;
    }

    private static GridPane helpGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(3);
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

    /** A key's name in the user's language ("Eingabe", "Rücktaste"); these are fixed map keys, not bindings. */
    static String helpKey(String id) {
        return tr("project.map.help.key." + id);
    }

    /** A modifier as the platform writes it: ⌘ ⌥ ⇧ ⌃ on macOS, the localised name ("Strg", "Umschalt") elsewhere. */
    static String helpModifier(String id) {
        if (com.editora.command.KeymapManager.isMac()) {
            return switch (id) {
                case "shortcut" -> "⌘";
                case "alt" -> "⌥";
                case "shift" -> "⇧";
                default -> "⌃";
            };
        }
        return helpKey(id.equals("shortcut") ? "control" : id);
    }

    /** {@code modifier} with {@code key}: "Strg+N", "⌃N". */
    static String helpChord(String modifier, String key) {
        String name = helpModifier(modifier);
        return com.editora.command.KeymapManager.isMac() ? name + key : name + "+" + key;
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
        mapLoader.rememberRoot(surface.selectedPath());
        bearingsFlush(); // what the workspace keeps is the folder being left, as it was
        bearingsReady = false;
        this.root = normalized;
        cards.closeAll();
        structure.clear();
        Path remembered = mapLoader.setRoot(normalized); // what was open there last time comes back
        clearSearchState();
        wasCapped = false;
        selectionHistory.clear();
        historyIndex = -1;
        pendingSelection = normalized;
        setOutputEnabled(false);
        orientation.setRoot(normalized);
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
    private void bearingsChanged() {
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
        for (int i = 0; i < selectionHistory.size(); i++) {
            String relative = bearingsRelative(current, selectionHistory.get(i));
            if (relative != null) {
                places.add(relative);
                if (i <= historyIndex) {
                    index = places.size() - 1;
                }
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
                open.add(new com.editora.config.ProjectMapState.Card(
                        relative,
                        code.getLayoutX(),
                        code.getLayoutY(),
                        code.getWidth(),
                        code.getHeight(),
                        code.contentZoom(),
                        code.structureShown()));
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
        if (!places.isEmpty()) {
            selectionHistory.clear();
            selectionHistory.addAll(places.subList(Math.max(0, places.size() - MAX_SELECTION_HISTORY), places.size()));
            historyIndex = Math.clamp(
                    index - Math.max(0, places.size() - MAX_SELECTION_HISTORY), 0, selectionHistory.size() - 1);
            historyHead = HistoryHead.PLACE;
        }
        Path selected = bearingsResolve(current, stored.getSelected());
        if (selected != null && bearingsExists(selected)) {
            pendingSelection = ProjectMapStructure.isSymbolPath(selected) ? bearingsFileOf(selected) : selected;
        }
        if (stored.getZoom() > 0) {
            surface.bearingsViewport = new double[] {stored.getZoom(), stored.getOffsetX(), stored.getOffsetY()};
        }
        bearingsPendingCards.clear();
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
    private void bearingsRestoreCards() {
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
        if (disposed || current == null || bearingsPendingCards.isEmpty() || !structure.hasOutline(file)) {
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
        cards.restoreCard(
                key,
                card.getX(),
                card.getY(),
                card.getWidth() > 0 ? card.getWidth() : ProjectMapPreview.DEFAULT_WIDTH,
                card.getHeight() > 0 ? card.getHeight() : ProjectMapPreview.DEFAULT_HEIGHT,
                card.getTextZoom() > 0 ? card.getTextZoom() : 1.0,
                card.isStructure());
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
    private void columnHiddenChanged() {
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
    private String resultsAccessibleSuffix(Path path) {
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
    private String resultsAccessibleFolder(Path path) {
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
        if (searchMatchesPartial) {
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

    void setGitStatus(Map<Path, GitFileStatus> status) {
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
        mapLoader.refresh(directories);
        scheduleCardRefresh();
    }

    /**
     * Files in {@code directories} were rewritten (sizes and times changed, no entry came or went): their next
     * listing reads them again, but nothing is reloaded for it now.
     */
    void listingsChanged(java.util.Collection<Path> directories) {
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
    private void escapePressed() {
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
            items.add(mapMenuItem("project.map.menu.personalNotes", () -> previewNotes(entry.path())));
        }
        Path closable = surface.closeTargetFor(entry);
        if (closable != null) {
            items.add(mapMenuItem("project.map.menu.closeColumn", () -> closeColumn(closable)));
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

    /** The eye's action for {@code entry}, from a key or the row menu: the row is selected first. */
    private void previewFileEntry(ProjectMapModel.Entry entry) {
        surface.select(entry.path());
        previewSelection(entry.path());
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
        menu.getItems().addAll(followItems(symbol.file(), symbol.line(), followName(symbol.label())));
        return menu;
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
                        });
    }

    /**
     * A declaration row's follow items: Go to Definition, Find Usages, Go to Implementation, Type and Call
     * Hierarchy at the declaration, then Related Test / Subject and File History of its file. Empty without a
     * {@link FollowCode}.
     */
    private List<MenuItem> followItems(Path file, int line, String name) {
        FollowCode code = followCode;
        if (code == null) {
            return List.of();
        }
        List<MenuItem> items = new ArrayList<>();
        items.add(new SeparatorMenuItem());
        for (String commandId : ProjectMapPreview.FOLLOW_COMMANDS) {
            MenuItem item = new MenuItem(ProjectMapPreview.followLabel(commandId));
            item.setOnAction(event -> code.follow(file, line, -1, name, commandId));
            items.add(item);
        }
        items.add(new SeparatorMenuItem());
        items.add(mapMenuItem("project.map.follow.related", () -> followRelated(file)));
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
        cards.dispose();
        orientation.dispose();
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
                    recordSelection(pendingSelection, historySearchAuto ? HistoryHead.LANDING : HistoryHead.PLACE);
                }
                // A search's first match, a reveal or a history step that had to be loaded is brought into
                // view as one that was already loaded is (E1).
                surface.revealAfterLoad(pendingSelection);
            } else {
                dropHistory(pendingSelection); // gone: it must not be selected if it ever reappears
            }
            pendingSelection = null;
        }
        searchResolveSymbol(); // a search landing on a declaration: its file's row may have just arrived
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
    private void truncatedColumnFilterChanged(@Nullable Path directory) {
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
        bearingsRestoreDeclarations(file); // a declaration card of the last session waited for this outline
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

    /** The language server's outline of an open buffer, when the editor has one (see ProjectMapStructure). */
    void setServerSymbols(Function<Path, java.util.@Nullable List<com.editora.lsp.SymbolNode>> symbols) {
        structure.setServerSymbols(symbols);
    }

    private void selectionChanged(Path path) {
        if (path == null) {
            return;
        }
        if (!navigatingHistory) {
            recordSelection(
                    path,
                    surface.movingSibling
                            ? HistoryHead.STEP
                            : historySearchLanding ? HistoryHead.LANDING : HistoryHead.PLACE);
        }
        updateNavigation();
        resultsFollowSelection(path);
        bearingsChanged();
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

    private void previewNotes(Path path) {
        cards.openNoteCard(path);
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

    private void updateCardVisibility() {
        cards.updateVisibility();
    }

    /** What the cards need of the map: the surface's rows, layout, zoom and colours, and the view's actions. */
    private MapCards.Host cardsHost() {
        return new MapCards.Host() {
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
            public double zoom() {
                return surface.cardZoom();
            }

            @Override
            public boolean horizontalFlow() {
                return surface.flowDirection == FlowDirection.LEFT_TO_RIGHT
                        || surface.flowDirection == FlowDirection.RIGHT_TO_LEFT;
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
            public ContextMenu symbolMenu(ProjectMapModel.Entry entry) {
                return ProjectMapView.this.symbolMenu(entry);
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
                onOpenFile.accept(file);
            }

            @Override
            public void openAt(Path file, int line) {
                onOpenAt.accept(file, line);
            }

            @Override
            public void openNarrowed(Path file, com.editora.text.FoldRegions.Region block) {
                onOpenNarrowed.accept(file, block);
            }

            @Override
            public void panBy(double dx, double dy) {
                surface.panBy(dx, dy);
            }
        };
    }

    /** {@code value} within [{@code minimum}, {@code maximum}]; unlike {@link Math#clamp} it tolerates an empty range. */
    private static double clampInto(double value, double minimum, double maximum) {
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
    private static String keysRowName(ProjectMapModel.Entry entry) {
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
        recordSelection(path, HistoryHead.PLACE);
    }

    /**
     * Appends a selection to the Back/Forward history, as a place or as the head of a run (see {@link
     * HistoryHead}): a step replaces a step head and a landing a landing head, so Back does not retrace every
     * keystroke; a run that comes back to the entry before it folds away. A place is never replaced, and the list
     * is capped at {@link #MAX_SELECTION_HISTORY}.
     */
    private void recordSelection(Path path, HistoryHead kind) {
        Path normalized = ProjectMapModel.normalize(path);
        if (normalized == null) {
            return;
        }
        if (historyIndex >= 0 && com.editora.config.PathKeys.samePath(selectionHistory.get(historyIndex), normalized)) {
            if (kind == HistoryHead.PLACE && historyIndex == selectionHistory.size() - 1) {
                historyHead = HistoryHead.PLACE; // the run's row became a place: the next step goes after it
            }
            return;
        }
        boolean atHead = historyIndex == selectionHistory.size() - 1;
        if (!atHead) {
            selectionHistory.subList(historyIndex + 1, selectionHistory.size()).clear();
        }
        if (kind != HistoryHead.PLACE && atHead && historyHead == kind && historyIndex >= 0) {
            selectionHistory.set(historyIndex, normalized);
            if (historyIndex > 0
                    && com.editora.config.PathKeys.samePath(selectionHistory.get(historyIndex - 1), normalized)) {
                selectionHistory.remove(historyIndex--); // the run came back to where it started
                historyHead = HistoryHead.PLACE;
            }
        } else {
            selectionHistory.add(normalized);
            if (selectionHistory.size() > MAX_SELECTION_HISTORY) {
                selectionHistory.removeFirst();
            }
            historyIndex = selectionHistory.size() - 1;
            historyHead = kind;
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
            recordSelection(path, HistoryHead.PLACE);
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

    /** The history as menu items (review 3 B5): each place's name and the folder it is in, the newest first. */
    ContextMenu historyMenu() {
        ContextMenu menu = new ContextMenu();
        menu.getStyleClass().add("project-map-history-menu");
        for (int index = selectionHistory.size() - 1; index >= 0; index--) {
            Path place = selectionHistory.get(index);
            int target = index;
            CheckMenuItem item = new CheckMenuItem(historyLabel(place));
            item.setMnemonicParsing(false);
            item.setSelected(index == historyIndex);
            item.setOnAction(event -> historyJumpTo(target));
            menu.getItems().add(item);
        }
        return menu;
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

    private void moveHistory(int delta) {
        historyJumpTo(historyIndex + delta);
    }

    /** Goes to the history's entry {@code target}, as that many Back or Forward presses would. */
    private void historyJumpTo(int target) {
        if (target < 0 || target >= selectionHistory.size() || target == historyIndex) {
            return;
        }
        historyIndex = target;
        historyHead = HistoryHead.PLACE;
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
        backButton.setDisable(historyIndex <= 0);
        forwardButton.setDisable(historyIndex < 0 || historyIndex >= selectionHistory.size() - 1);
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

    /** Canvas surface with a single keyboard focus target and deterministic screen-space hit boxes. */
    private final class MapSurface extends Region {

        private static final double MIN_NODE_WIDTH = 164;
        /** The widest a column's rows grow for a long name; the name is elided in the middle beyond it. */
        private static final double MAX_NODE_WIDTH = 360;

        private static final String FALLBACK_FONT_FAMILY = "System";
        /** Between the folder names in a compacted column's title: {@code src / main / java}. */
        private static final String CHAIN_SEPARATOR = " / ";

        private static final double NODE_HEIGHT = 32;
        /** A row's height with "Compact rows" on (review 3 B15): about two thirds of the default pitch. */
        private static final double COMPACT_NODE_HEIGHT = 26;

        private static final double COMPACT_ROW_GAP = 4;
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
        private static final double PREVIEW_COLUMN_GAP = 18;
        private static final DateTimeFormatter TOOLTIP_TIME = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                .withLocale(Locale.getDefault())
                .withZone(ZoneId.systemDefault());
        private static final double ICON_SIZE = 20;
        private static final double ICON_RASTER_SCALE = 2;
        /** The smallest zoom: far enough out for a big map to fit as labelled blocks (review 3 B8). */
        private static final double MIN_ZOOM = 0.15;
        /** Below this zoom each column is drawn as a labelled block instead of rows of text (review 3 B8). */
        private static final double BLOCK_ZOOM = 0.6;

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
        /** How far a primary press on a row must move before it pans the map. */
        private static final double ROW_PAN_THRESHOLD = 5;
        /** Below this surface size the overview is not drawn: it would cover too much of the map (B3). */
        private static final double OVERVIEW_MIN_SURFACE_WIDTH = 600;

        private static final double OVERVIEW_MIN_SURFACE_HEIGHT = 400;
        /** The overview frame's longest sides; it shrinks to the map's shape inside them (B2). */
        private static final double OVERVIEW_MAX_WIDTH = 150;

        private static final double OVERVIEW_MAX_HEIGHT = 86;
        private static final double OVERVIEW_MIN_SIDE = 40;
        /** The overview's narrowest frame (review 3 B8). */
        private static final double OVERVIEW_MIN_WIDTH = 90;
        /** How far the overview's picture may be stretched along its short side to fill that frame. */
        private static final double OVERVIEW_MAX_STRETCH = 3;

        private static final double OVERVIEW_INSET = 5;
        /** Zoom levels tried, in order, to bring a hidden column filter back for the {@code /} key. */
        private static final double[] FILTER_ZOOM_STEPS = {0.8, 1.0};
        /** The same light palette for the row icons, which are styled nodes rasterized through CSS. */
        private static final String OUTPUT_ICON_STYLE = "-project-folder-color: #0969da; -project-file-color: #59636e;"
                + " -state-amber: #9a6700; -state-olive: #4f5c10; -state-violet: #6538c2;"
                + " -color-accent-fg: #0969da; -color-success-fg: #1a7f37; -color-danger-fg: #d1242f;"
                + " -color-fg-muted: #59636e;";

        private final Canvas liveCanvas = new Canvas(1, 1);
        /** The overview, on a canvas of its own above the cards; see {@link #drawOverview}. */
        private final Canvas overviewCanvas = new Canvas(1, 1);
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
        private java.util.function.@Nullable Supplier<java.util.Collection<Path>> stateCandidates;
        private Map<Path, GitFileStatus> gitState = Map.of();
        private Set<Path> gitDirectories = Set.of();
        /** Paths Git reports as untracked; a folder among them makes everything under it untracked too. */
        private Set<Path> gitUntrackedRoots = Set.of();

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
        private Consumer<ProjectMapModel.Entry> onTogglePreview = entry -> {};
        private Consumer<Path> onSelectionChanged = path -> {};
        private Function<ProjectMapModel.Entry, @Nullable ContextMenu> contextMenuFactory = entry -> null;
        private Runnable onZoomChanged = () -> {};
        private double zoom = 1.0;
        private boolean keepZoomOnColumnOpen = true;
        private boolean focusNewColumn = true;
        /** The ⋯ menu's "Single-click opens files" (see ProjectMapView.singleClickOpens). */
        private boolean singleClickOpens = true;

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
        /** The pan was pressed on a row: it starts only past {@link #ROW_PAN_THRESHOLD}. */
        private boolean panFromRow;
        /** The press landed in the overview: drags keep moving the view rectangle. */
        private boolean overviewDragging;
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
        /** "Compact rows" (review 3 B15): shorter rows closer together. */
        private boolean layoutCompactRows;
        /** "Hide ignored and generated" (review 3 A11): such rows are left out instead of dimmed. */
        private boolean layoutHideIgnored;
        /** Bumped whenever more rows were found ignored or generated, so the layout is computed again. */
        private int layoutIgnoredVersion;
        /**
         * With "Compact rows", the columns that show their filter, "Show hidden" and lock (review 3 B15): the one
         * hovered, the one holding the selection or the keyboard, and any with a filter, a "Show hidden" of its own or
         * a lock. The others keep only their title line.
         */
        private Set<ProjectMapModel.ColumnId> layoutChromeShown = Set.of();
        /** The column under the pointer, for {@link #layoutChromeShown}. */
        private ProjectMapModel.@Nullable ColumnId layoutHoverColumn;
        /** The folders that hold a build file (a module's root): their rows carry a badge (review 3 B4). */
        private Set<Path> layoutModuleFolders = Set.of();
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
            overviewForwardsInput();
            setFocusTraversable(true);
            // PARENT, not TREE_VIEW: the canvas has no item nodes, and a tree role made screen readers look for
            // rows, find none and announce an empty tree. PARENT is a plain group (AXGroup on macOS, a pane in
            // UI Automation) that promises no items; its TEXT names the selection and its states and changes
            // with it (updateAccessibleText), and its children are the real column controls (B5).
            setAccessibleRole(AccessibleRole.PARENT);
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
                    warmIcons(); // the new theme's icons, ahead of the rows that will need them
                    repaint();
                });
            }

            addEventHandler(MouseEvent.MOUSE_PRESSED, this::mousePressed);
            addEventHandler(MouseEvent.MOUSE_DRAGGED, this::mouseDragged);
            addEventHandler(MouseEvent.MOUSE_RELEASED, event -> {
                panning = false;
                panFromRow = false;
                overviewDragging = false;
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
                    warmIcons();
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

        /** The few paths that can be open or marked, looked up among the rows; null: every row is asked. */
        void setStateCandidates(java.util.function.@Nullable Supplier<java.util.Collection<Path>> candidates) {
            stateCandidates = candidates;
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

        /** Each column's own filter text, by the directory it lists (the loader matches a long folder's rest). */
        Map<Path, String> columnFilterQueries() {
            Map<Path, String> result = new HashMap<>();
            columnQueries.forEach((id, value) -> {
                if (id.parent() != null && value != null && !value.isBlank()) {
                    result.put(id.parent(), value);
                }
            });
            return result;
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
            repaint();
            keepSelectionDrawn();
            updateAccessibleText();
            layoutEntriesShown(this.entries);
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

        void setCompactRows(boolean compact) {
            if (layoutCompactRows != compact) {
                layoutCompactRows = compact;
                repaint();
                keepSelectionDrawn();
            }
        }

        void setHideIgnored(boolean hide) {
            if (layoutHideIgnored != hide) {
                layoutHideIgnored = hide;
                repaint();
                keepSelectionDrawn();
                updateAccessibleText();
            }
        }

        /** Whether the map has no rows at all. */
        boolean isEmpty() {
            return entries.isEmpty();
        }

        /** More rows were found dimmed, or the project's module folders are known (review 3 A11, B4). */
        void layoutOrientationChanged(int version, Set<Path> modules) {
            layoutIgnoredVersion = version;
            layoutModuleFolders = modules;
            repaint();
        }

        /** Why the row of {@code entry} is dimmed as ignored, vendored or generated; null for the project's own. */
        private ProjectMapIgnored.@Nullable Reason layoutIgnoredReason(ProjectMapModel.Entry entry) {
            return entry.isPlaceholder() || entry.isSymbol() ? null : orientation.reason(entry.path());
        }

        /** The rows the layout lays out: all, or without the ignored and generated ones and what is in them. */
        private List<ProjectMapModel.Entry> layoutLaidEntries() {
            if (!layoutHideIgnored) {
                return entries;
            }
            List<ProjectMapModel.Entry> kept = new ArrayList<>(entries.size());
            for (ProjectMapModel.Entry entry : entries) {
                Path where = entry.isPlaceholder() ? entry.parent() : entry.path();
                if (entry.isSymbol() || where == null || !orientation.dimmedOrInside(where)) {
                    kept.add(entry);
                }
            }
            return kept;
        }

        /** A row's height in world units: 32, or 26 with "Compact rows". */
        private double rowHeight() {
            return layoutCompactRows ? COMPACT_NODE_HEIGHT : NODE_HEIGHT;
        }

        /** The gap between two rows of a column in world units: 9, or 4 with "Compact rows". */
        private double rowGap() {
            return layoutCompactRows ? COMPACT_ROW_GAP : ROW_GAP;
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
                headRight = Math.min(right, box.x() + (2 * (MIN_NODE_WIDTH + rowGap()) + 20) * zoom);
            } else {
                double header = layoutHeaderHeight(box.column(), box.width() / zoom);
                headBottom =
                        Math.min(bottom, box.y() + (COLUMN_TOP_INSET + header + 3 * (rowHeight() + rowGap())) * zoom);
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
                keepSelectionDrawn();
                updateAccessibleText();
            }
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
            repaint();
            updateAccessibleText();
        }

        /**
         * Brings a selection that arrived with a load into view: now, and once more after the deferred
         * "show the opened column" and first-view passes that {@link #setEntries} queued, so the selected row
         * wins over the start of its new column.
         */
        void revealAfterLoad(Path path) {
            layoutRevealPlace();
            Platform.runLater(() -> {
                if (path.equals(selected)) {
                    layoutRevealPlace();
                }
            });
        }

        /** The lowest zoom a reveal steps down to so that more of the path to its row shows (review 3 B7). */
        private static final double REVEAL_MIN_ZOOM = 0.7;
        /** How long a revealed row is flashed. */
        private static final double FLASH_MILLIS = 900;

        /** The row last revealed as a place, flashed for {@link #FLASH_MILLIS} from {@link #layoutFlashStart}. */
        private @Nullable Path layoutFlashPath;

        private long layoutFlashStart;
        private final AnimationTimer layoutFlashTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (layoutFlashPath == null || !active || (now - layoutFlashStart) / 1e6 > FLASH_MILLIS) {
                    layoutFlashPath = null;
                    stop();
                }
                scheduleDraw();
            }
        };

        /**
         * Shows the selection as a place: Reveal Active File, a search landing, a history or breadcrumb step (review 3
         * B7). The row sits about a third of the way down, with as much of the path to it — its column's title, then
         * each ancestor, nearest first — as the view holds, zoomed out to no less than 70% when that shows more of it;
         * clear of the overview; and it is flashed. A step from row to row by key keeps to the smallest scroll
         * ({@link #revealSelected}).
         */
        void layoutRevealPlace() {
            flushPendingRepaint();
            NodeBox box = nodeBox(selected);
            if (box == null) {
                revealSelected(); // the root or a compacted folder: its column's title, no row to flash
                repaint();
                return;
            }
            Path target = box.entry().path();
            List<double[]> chain = layoutAncestorRects(box);
            double availableWidth = viewRight() - viewLeft();
            double availableHeight = viewBottom() - viewTop();
            if (!chain.isEmpty() && zoom > REVEAL_MIN_ZOOM + 1e-9) {
                double[] all = layoutRect(box);
                for (double[] rect : chain) {
                    all = layoutUnion(all, rect);
                }
                double need = Math.min(availableWidth / (all[2] - all[0]), availableHeight / (all[3] - all[1]));
                if (need < 1) {
                    setZoom(
                            Math.max(REVEAL_MIN_ZOOM, zoom * need),
                            box.x() + box.width() / 2,
                            box.y() + box.height() / 2);
                    repaint();
                    NodeBox zoomed = nodeBox(target);
                    if (zoomed == null) {
                        return;
                    }
                    box = zoomed;
                    chain = layoutAncestorRects(box);
                }
            }
            // Across and down are taken apart: a long column puts its title and the row that opened it far above
            // the row, but the columns that lead to it still fit beside it.
            double[] across = layoutRect(box);
            double[] down = layoutRect(box);
            boolean acrossFull = false;
            boolean downFull = false;
            for (double[] rect : chain) {
                double[] wider = layoutUnion(across, rect);
                acrossFull |= wider[2] - wider[0] > availableWidth;
                if (!acrossFull) {
                    across = wider;
                }
                double[] taller = layoutUnion(down, rect);
                downFull |= taller[3] - taller[1] > availableHeight;
                if (!downFull) {
                    down = taller;
                }
            }
            double dy = viewTop() + availableHeight / 3 - (box.y() + box.height() / 2);
            dy = Math.max(viewTop() - down[1], Math.min(viewBottom() - down[3], dy));
            double dx = (viewLeft() + viewRight()) / 2 - (across[0] + across[2]) / 2;
            if (across[2] - across[0] > availableWidth) {
                dx = viewLeft() - box.x(); // the row alone is wider than the view: its start
            }
            OverviewBox overview = overviewBox;
            if (overview != null) {
                double left = box.x() + dx;
                double top = box.y() + dy;
                if (left + box.width() > overview.x() - 4
                        && left < overview.x() + overview.width()
                        && top + box.height() > overview.y() - 4
                        && top < overview.y() + overview.height()) {
                    dy -= top + box.height() - (overview.y() - 8);
                }
            }
            offsetX += dx;
            offsetY += dy;
            repaint();
            layoutFlash(target);
        }

        /** Flashes the row of {@code path} once (a reveal). */
        private void layoutFlash(@Nullable Path path) {
            if (path == null || outputPaint) {
                return;
            }
            layoutFlashPath = path;
            layoutFlashStart = System.nanoTime();
            layoutFlashTimer.start();
        }

        /** The revealed row's flash: an accent ring that fades out. */
        private void layoutDrawFlash(GraphicsContext g) {
            Path path = layoutFlashPath;
            NodeBox box = path == null ? null : nodeBox(path);
            if (box == null) {
                return;
            }
            double elapsed = (System.nanoTime() - layoutFlashStart) / 1e6;
            double strength = Math.max(0, 1 - elapsed / FLASH_MILLIS);
            if (strength <= 0) {
                return;
            }
            g.setGlobalAlpha(0.85 * strength);
            g.setStroke(color(accentProbe, Color.web("#388bfd")));
            g.setLineWidth(3);
            double grow = 4 + 6 * (1 - strength);
            g.strokeRoundRect(
                    box.x() - grow,
                    box.y() - grow,
                    box.width() + grow * 2,
                    box.height() + grow * 2,
                    8 * zoom + grow * 2,
                    8 * zoom + grow * 2);
            g.setGlobalAlpha(1);
        }

        /**
         * The path to a row as drawn, nearest first, {@code {left, top, right, bottom}}: its column's title strip, then
         * each folder above it — its row, or for the root and the folders of a compacted chain the title of the column
         * that stands for them.
         */
        private List<double[]> layoutAncestorRects(NodeBox box) {
            List<double[]> rects = new ArrayList<>();
            ColumnBox own = columnBox(columnId(box.entry()));
            if (own != null) {
                rects.add(layoutTitleRect(own));
            }
            Path base = root;
            for (Path current = box.entry().parent();
                    current != null && base != null && current.startsWith(base);
                    current = current.getParent()) {
                NodeBox row = nodeBox(current);
                if (row != null) {
                    rects.add(layoutRect(row));
                } else {
                    ColumnBox header = headerFor(current);
                    if (header != null && header != own) {
                        rects.add(layoutTitleRect(header));
                    }
                }
                if (current.equals(base)) {
                    break;
                }
            }
            return rects;
        }

        private double[] layoutTitleRect(ColumnBox column) {
            return new double[] {
                column.x(), column.y(), column.x() + column.width(), column.y() + COMPACT_HEADER_HEIGHT * zoom
            };
        }

        private static double[] layoutRect(NodeBox box) {
            return new double[] {box.x(), box.y(), box.x() + box.width(), box.y() + box.height()};
        }

        private static double[] layoutUnion(double[] a, double[] b) {
            return new double[] {Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[2], b[2]), Math.max(a[3], b[3])
            };
        }

        /** The row the zoom bar or the overview gives way to while the pointer is on it (review 3 B7); or null. */
        private @Nullable Path layoutGivingWayTo;
        /** Fades the zoom bar and lets the pointer through it (true), or brings it back (false). */
        private Consumer<Boolean> layoutOnZoomBarGiveWay = giveWay -> {};

        /**
         * The zoom bar and the overview float over the map's corners and used to hide the rows under them for good:
         * their icons and eyes could not be reached. Now, when the pointer is on the part of such a row they leave
         * free, the one in the way fades and lets the pointer through until it leaves that row. A pointer that comes
         * onto them from elsewhere finds them as usual.
         */
        private void layoutOverlaysGiveWay(@Nullable NodeBox hit, double x, double y) {
            boolean overview = false;
            boolean bar = false;
            if (hit != null && !outputPaint) {
                boolean same = hit.entry().path().equals(layoutGivingWayTo);
                OverviewBox frame = overviewBox;
                if (frame != null && layoutCovers(hit, frame.x(), frame.y(), frame.width(), frame.height())) {
                    overview = same || !frame.contains(x, y);
                }
                double barTop = getHeight() - reservedBottom;
                if (reservedBarWidth > 0 && layoutCovers(hit, 0, barTop, reservedBarWidth, reservedBottom)) {
                    bar = same || !(x <= reservedBarWidth && y >= barTop);
                }
            }
            layoutGivingWayTo = (overview || bar) && hit != null ? hit.entry().path() : null;
            if (overviewCanvas.isMouseTransparent() != overview) {
                overviewCanvas.setMouseTransparent(overview);
                overviewCanvas.setOpacity(overview ? 0.12 : 1);
            }
            layoutOnZoomBarGiveWay.accept(bar);
        }

        private static boolean layoutCovers(NodeBox box, double x, double y, double width, double height) {
            return box.x() < x + width
                    && box.x() + box.width() > x
                    && box.y() < y + height
                    && box.y() + box.height() > y;
        }

        /**
         * The global search's matches (files, folders, declarations' files): each counts as matching the query
         * whatever its name, so a path or declaration query lights what it found (review 3 A5, E2).
         */
        private Set<Path> searchHits = Set.of();

        void setSearchHits(Set<Path> hits) {
            Set<Path> next = hits == null ? Set.of() : hits;
            if (next.equals(searchHits)) {
                return;
            }
            searchHits = next;
            recomputeEmphasis();
            repaint();
        }

        void setFilters(ProjectMapModel.Filters filters) {
            this.filters = filters;
            recomputeEmphasis();
            repaint();
            keepSelectionDrawn();
            updateAccessibleText();
        }

        void setGitState(Map<Path, GitFileStatus> status, Set<Path> directories) {
            gitState = status == null ? Map.of() : status;
            gitDirectories = directories == null ? Set.of() : directories;
            Set<Path> untracked = new HashSet<>();
            gitState.forEach((path, value) -> {
                if (value == GitFileStatus.UNTRACKED) {
                    untracked.add(path);
                }
            });
            gitUntrackedRoots = Set.copyOf(untracked);
            recomputeEmphasis();
            repaint();
            keepSelectionDrawn();
            updateAccessibleText();
        }

        void stateChanged() {
            snapshotStates();
            recomputeEmphasis();
            entries.stream()
                    .filter(entry -> entry.path().equals(hovered))
                    .findFirst()
                    .ifPresent(entry -> nodeTooltip.setText(tooltipText(entry)));
            repaint();
            keepSelectionDrawn();
            updateAccessibleText();
        }

        void dispose() {
            viewportRepaintPending = false;
            boxesStale = false;
            viewportRepaintTimer.stop();
            layoutFlashTimer.stop();
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
            if (!active) {
                dataRepaintWhileHidden = true; // nobody sees it: setShowing(true) paints once (D9)
                return;
            }
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
            cards.moveWithViewport(fromX, fromY, fromZoom, offsetX, offsetY, zoom);
            bearingsChanged();
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
                double[] restored = bearingsViewport;
                bearingsViewport = null;
                if (restored != null) {
                    // The last session's zoom and pan, instead of the first view's fit (review 3 B6).
                    zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, restored[0]));
                    offsetX = restored[1];
                    offsetY = restored[2];
                    onZoomChanged.run();
                    repaint();
                } else {
                    showStart(true);
                }
                Platform.runLater(ProjectMapView.this::bearingsRestoreCards); // placed on the map as it is now
            }
        }

        /** The last session's {@code {zoom, offsetX, offsetY}}, applied by the first view in place of its fit. */
        private double @Nullable [] bearingsViewport;

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
            // A column's controls are made when it is first laid out on screen at a zoom that shows them
            // (dataEnsureColumnControls): a search that opens 300 columns no longer builds 1,200 native
            // controls in one pulse for columns nobody can see (A8).
        }

        /**
         * Makes the controls of the columns on screen, or within one viewport of it (a pan's worth: they are
         * ready when it brings them in), that show them at this zoom and have none yet.
         */
        private void dataEnsureColumnControls() {
            if (outputPaint) {
                return;
            }
            double width = getWidth();
            double height = getHeight();
            for (ColumnBox box : columnBoxes) {
                ProjectMapModel.ColumnId id = box.column().id();
                if (id.depth() <= 0 || columnControls.containsKey(id)) {
                    continue;
                }
                boolean nearScreen = box.x() < 2 * width
                        && box.x() + box.width() > -width
                        && box.y() < 2 * height
                        && box.y() + box.height() > -height;
                if (nearScreen && (closeFits(box) || detailControlsFit(box.width()))) {
                    dataColumnControls(id);
                }
            }
        }

        /**
         * The controls of column {@code id}, made now if it has none: they start from what the column holds
         * already (its filter text, its "Show hidden", its lock), since a column can be filtered or locked
         * before its controls exist ({@code /} on an off-screen column, a lock kept from before).
         */
        private ColumnControls dataColumnControls(ProjectMapModel.ColumnId id) {
            ColumnControls existing = columnControls.get(id);
            if (existing != null) {
                return existing;
            }
            TextField filter = new TextField();
            filter.getStyleClass().add("project-map-column-filter");
            filter.setPromptText(tr("project.map.column.filter"));
            filter.setAccessibleHelp(tr("project.map.column.filterHelp"));
            String query = columnQueries.get(id);
            if (query != null) {
                filter.setText(query);
            }
            filter.textProperty().addListener((obs, old, value) -> {
                if (value == null || value.isBlank()) {
                    columnQueries.remove(id);
                } else {
                    columnQueries.put(id, value);
                }
                truncatedColumnFilterChanged(id.parent());
                repaint();
                updateAccessibleText(); // the column's count of drawn rows changed
                if (selected != null
                        && boxes.stream().noneMatch(box -> box.entry().path().equals(selected))) {
                    boxes.stream()
                            .filter(box -> columnId(box.entry()).equals(id))
                            .findFirst()
                            .ifPresent(box -> select(box.entry().path()));
                }
            });
            filter.setOnAction(event -> leaveColumnFilter(id));

            CheckBox showHidden = new CheckBox(tr("project.map.column.showHidden"));
            showHidden.getStyleClass().add("project-map-column-hidden");
            showHidden.setSelected(columnShowHidden.getOrDefault(id, showHiddenDefault));
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
                        && boxes.stream().noneMatch(box -> box.entry().path().equals(this.selected))) {
                    boxes.stream()
                            .filter(box -> columnId(box.entry()).equals(id))
                            .findFirst()
                            .ifPresent(box -> select(box.entry().path()));
                }
            });

            ToggleButton pin = new ToggleButton();
            pin.getStyleClass().add("project-map-column-pin");
            pin.setGraphic(Icons.pin());
            ColumnLayout layout = columnLayouts.get(id);
            boolean locked = layout != null && layout.locked;
            pin.setSelected(locked);
            pin.setTooltip(new Tooltip(tr(locked ? "project.map.column.unpin" : "project.map.column.pin")));
            pin.setAccessibleText(tr("project.map.column.pin"));
            pin.selectedProperty().addListener((obs, old, selected) -> {
                columnLayouts.computeIfAbsent(id, ignored -> new ColumnLayout()).locked = selected;
                pin.setTooltip(new Tooltip(tr(selected ? "project.map.column.unpin" : "project.map.column.pin")));
            });

            Button close = new Button("×");
            close.getStyleClass().add("project-map-column-close");
            close.setTooltip(new Tooltip(tr("project.map.column.close")));
            close.setAccessibleText(tr("project.map.column.close"));
            close.setOnAction(event -> onCloseColumn.accept(closeTarget(id)));

            ColumnControls made = new ColumnControls(filter, showHidden, pin, close);
            columnControls.put(id, made);
            getChildren().addAll(filter, showHidden, pin, close);
            if (getScene() != null) {
                for (Control control : List.of(filter, showHidden, pin, close)) {
                    control.applyCss(); // styled before it is first measured and laid out
                }
            }
            return made;
        }

        /**
         * A row's Git status: its own, or untracked when it lies under a folder Git reports as untracked. In
         * its default mode {@code git status} names a new folder ({@code ? deep/}), not the files in it.
         */
        private @Nullable GitFileStatus gitStatusOf(Path path) {
            GitFileStatus own = gitState.get(path);
            if (own != null || gitUntrackedRoots.isEmpty()) {
                return own;
            }
            for (Path parent = path.getParent(); parent != null; parent = parent.getParent()) {
                if (gitUntrackedRoots.contains(parent)) {
                    return GitFileStatus.UNTRACKED;
                }
                if (parent.equals(root)) {
                    break;
                }
            }
            return null;
        }

        /** Whether the Git chip counts a row: it has a status, or is a folder holding a change. */
        private boolean gitChanged(Path path) {
            return gitDirectories.contains(path) || gitStatusOf(path) != null;
        }

        private void recomputeEmphasis() {
            if (filters == null || !filters.active()) {
                emphasized = Set.of();
                return;
            }
            Set<Path> direct = new HashSet<>();
            Set<Path> loadedPaths = new HashSet<>();
            List<ProjectMapModel.Entry> symbolRows = new ArrayList<>();
            ProjectMapModel.Filters hitFilters = searchHits.isEmpty()
                    ? filters
                    : new ProjectMapModel.Filters(
                            "",
                            filters.open(),
                            filters.modified(),
                            filters.gitChanged(),
                            filters.bookmarked(),
                            filters.personalNotes(),
                            filters.type());
            for (ProjectMapModel.Entry entry : entries) {
                Path path = entry.path();
                loadedPaths.add(path);
                if (entry.isSymbol()) {
                    symbolRows.add(entry);
                }
                if (entry.isPlaceholder() || entry.isSymbol()) {
                    continue;
                }
                if (ProjectMapModel.matches(
                        entry,
                        searchHits.contains(path) ? hitFilters : filters,
                        openPaths.contains(path),
                        modifiedPaths.contains(path),
                        gitChanged(path),
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
            result.addAll(ProjectMapModel.emphasized(entries, searchMatchingSymbols(symbolRows), root));
            emphasized = result;
        }

        /**
         * The declarations on screen whose name the query matches (E6): an open Structure column is searched
         * as its file's rows are, without reading anything. A match lights itself and the rows that lead to
         * it, not its siblings. The other filters still have to accept the declaration's file.
         */
        private Set<Path> searchMatchingSymbols(List<ProjectMapModel.Entry> symbolRows) {
            if (filters.query().isEmpty() || symbolRows.isEmpty()) {
                return Set.of();
            }
            ProjectMapModel.Filters fileFilters = new ProjectMapModel.Filters(
                    "",
                    filters.open(),
                    filters.modified(),
                    filters.gitChanged(),
                    filters.bookmarked(),
                    filters.personalNotes(),
                    filters.type());
            Set<Path> result = new HashSet<>();
            Map<Path, Boolean> fileAccepted = new HashMap<>();
            for (ProjectMapModel.Entry entry : symbolRows) {
                ProjectMapModel.Symbol symbol = entry.symbol();
                if (symbol == null || com.editora.search.FuzzyMatch.of(entry.name(), filters.query()) == null) {
                    continue;
                }
                Path file = symbol.file();
                boolean accepted = fileAccepted.computeIfAbsent(
                        file,
                        ignored -> ProjectMapModel.matches(
                                new ProjectMapModel.Entry(file, file.getParent(), 0, false),
                                fileFilters,
                                openPaths.contains(file),
                                modifiedPaths.contains(file),
                                gitChanged(file),
                                bookmarkedPaths.contains(file),
                                notedPaths.contains(file)));
                if (accepted) {
                    result.add(entry.path());
                }
            }
            return result;
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
            } catch (RuntimeException failed) {
                ProjectMapLog.unexpected("listing open and marked files", failed);
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
            dataEnsureColumnControls();
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
            layoutChromeShown = layoutChromeColumns();
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
                    compactChains,
                    new LayoutExtras(
                            layoutCompactRows,
                            layoutChromeShown,
                            layoutHideIgnored,
                            layoutIgnoredVersion,
                            layoutModuleFolders));
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
            List<ProjectMapModel.Entry> laid = layoutLaidEntries();
            List<ProjectMapModel.Column> columns = ProjectMapModel.columnsById(
                    laid, columnQueries, columnShowHidden, narrowing ? emphasized : null, compactChains);
            int narrowedTotal = 0;
            for (ProjectMapModel.Column column : columns) {
                narrowedTotal += column.narrowed();
            }
            // Every row of a column counts for its width, filtered or not, so a filter does not make it jump.
            Map<ProjectMapModel.ColumnId, List<ProjectMapModel.Entry>> rowsByColumn = new HashMap<>();
            for (ProjectMapModel.Entry entry : laid) {
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
            // Every level's band is known before any card is placed (review 3 B3). The listing is breadth-first on
            // disk while a level counts columns from the first one, so a compacted chain deep on disk is listed after
            // shallower branches' next levels: built as the cards came, its wider band arrived too late to push
            // them, and its card was laid over theirs.
            double[][] sizes = new double[columns.size()][];
            Map<Integer, Double> finalBand = layoutFinalBands(columns, rowsByColumn, sizes);
            for (int columnNumber = 0; columnNumber < columns.size(); columnNumber++) {
                ProjectMapModel.Column column = columns.get(columnNumber);
                if (column.depth() == 0) {
                    continue; // the root row: the first column's title stands for it
                }
                ProjectMapModel.ColumnId id = column.id();
                ColumnLayout layout = columnLayouts.computeIfAbsent(id, ignored -> new ColumnLayout());
                double[] size = sizes[columnNumber];
                double nodeWidth = size[0];
                Integer anchorRow = column.anchor() == null ? null : rowIndex.get(column.anchor());
                NodeBox parent = anchorRow == null ? null : rowList.get(anchorRow);
                Integer anchorColumn = parent == null ? null : columnIndex.get(columnId(parent.entry()));
                ColumnBox parentColumn = anchorColumn == null ? null : columnList.get(anchorColumn);
                int level = parentColumn == null
                        ? 0
                        : levels.getOrDefault(parentColumn.column().id(), 0) + 1;
                levels.put(id, level);
                double depthStep = verticalFlow
                        ? COLUMN_TOP_INSET + COLUMN_HEADER_HEIGHT + rowHeight() + COLUMN_BOTTOM_PADDING + COLUMN_GAP
                        : 0;
                double cardWidth = size[1];
                double headerHeight = size[2];
                double cardHeight = size[3];
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
                    Double band = layoutBand(flowBand.get(level - 1), finalBand.get(level - 1), direction);
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
                layout.pushX = 0;
                layout.pushY = 0;
                if (parentColumn != null) {
                    if (verticalFlow) {
                        double minimum = nextCrossEdge.getOrDefault(level, Double.NEGATIVE_INFINITY);
                        double cardLeft = columnWorldX - 10;
                        if (cardLeft < minimum) {
                            layout.pushX = minimum - cardLeft; // read back by a header drag (B4)
                            columnWorldX += minimum - cardLeft;
                        }
                        nextCrossEdge.put(level, columnWorldX - 10 + cardWidth + COLUMN_GAP);
                    } else {
                        double minimum = nextCrossEdge.getOrDefault(level, Double.NEGATIVE_INFINITY);
                        double cardTop = columnWorldY - COLUMN_TOP_INSET;
                        if (cardTop < minimum) {
                            layout.pushY = minimum - cardTop; // read back by a header drag (B4)
                            columnWorldY += minimum - cardTop;
                        }
                        nextCrossEdge.put(level, columnWorldY - COLUMN_TOP_INSET + cardHeight + COLUMN_GAP);
                    }
                }
                for (int row = 0; row < column.entries().size(); row++) {
                    double worldX = columnWorldX + (verticalFlow ? row * (nodeWidth + rowGap()) : 0);
                    double worldY = columnWorldY + headerHeight + (verticalFlow ? 0 : row * (rowHeight() + rowGap()));
                    NodeBox box = new NodeBox(column.entries().get(row), worldX, worldY, nodeWidth, rowHeight());
                    rowIndex.put(box.entry().path(), rowList.size());
                    rowList.add(box);
                }
                columnIndex.put(id, columnList.size());
                double extension = size[4];
                columnList.add(new ColumnBox(
                        column,
                        columnWorldX - 10,
                        columnWorldY - COLUMN_TOP_INSET - extension,
                        cardWidth,
                        cardHeight + extension));
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

        /**
         * A column card's size in world units, {@code {nodeWidth, cardWidth, headerHeight, cardHeight, extension}}: the
         * same for the band pass and the placement pass. {@code extension} is how far the card reaches above its
         * placed top for its controls (only with "Compact rows").
         */
        private double[] layoutCardSize(ProjectMapModel.Column column, List<ProjectMapModel.Entry> rows) {
            boolean verticalFlow = isVerticalFlow();
            double nodeWidth = nodeWidthFor(column, rows);
            // A column whose filter matches nothing keeps one row of space for its "No matches" line.
            int rowSlots = Math.max(1, column.entries().size());
            double rowsHeight = rowSlots * rowHeight() + (rowSlots - 1) * rowGap();
            double rowsWidth = rowSlots * nodeWidth + (rowSlots - 1) * rowGap();
            double cardWidth = verticalFlow ? rowsWidth + 20 : nodeWidth + 20;
            // With "Compact rows" every column is placed with the title line alone; one showing its controls grows
            // upward by their band (into the gap before it), so its rows never move when the controls come and go.
            double headerHeight =
                    layoutCompactRows ? COMPACT_HEADER_HEIGHT : columnHeaderHeight(column.depth(), cardWidth);
            double cardHeight = COLUMN_TOP_INSET
                    + headerHeight
                    + (verticalFlow ? rowHeight() : rowsHeight)
                    + narrowedFooterHeight(column)
                    + COLUMN_BOTTOM_PADDING;
            double extension = layoutHeaderHeight(column, cardWidth) - headerHeight;
            return new double[] {nodeWidth, cardWidth, headerHeight, cardHeight, extension};
        }

        /**
         * The far edge of every level's band along the flow, before any card is placed, measured without the manual
         * offsets (as {@code flowBand} is): the widest card of a level sets where the next level starts, whichever
         * branch it is in and whenever it is listed (review 3 B3). Fills {@code sizes} with each column's
         * {@link #layoutCardSize}.
         */
        private Map<Integer, Double> layoutFinalBands(
                List<ProjectMapModel.Column> columns,
                Map<ProjectMapModel.ColumnId, List<ProjectMapModel.Entry>> rowsByColumn,
                double[][] sizes) {
            boolean verticalFlow = isVerticalFlow();
            boolean reverse = isReverseFlow();
            double gap = verticalFlow ? COLUMN_GAP : COLUMN_GAP - 20;
            double start = WORLD_PADDING - (verticalFlow ? COLUMN_TOP_INSET : 10);
            Map<Path, Integer> rowLevel = new HashMap<>(); // a row's path → the level of the column it is in
            Map<Integer, Double> bands = new HashMap<>();
            for (int index = 0; index < columns.size(); index++) {
                ProjectMapModel.Column column = columns.get(index);
                if (column.depth() == 0) {
                    sizes[index] = new double[5];
                    continue;
                }
                double[] size = layoutCardSize(column, rowsByColumn.getOrDefault(column.id(), List.of()));
                sizes[index] = size;
                Integer parentLevel = column.anchor() == null ? null : rowLevel.get(column.anchor());
                int level = parentLevel == null ? 0 : parentLevel + 1;
                for (ProjectMapModel.Entry row : column.entries()) {
                    rowLevel.put(row.path(), level);
                }
                double along = verticalFlow ? size[3] : size[1];
                double edge;
                if (level == 0) {
                    edge = reverse ? start : start + along;
                } else {
                    Double before = bands.get(level - 1);
                    double from = before == null ? start : before;
                    edge = reverse ? from - gap - along : from + gap + along;
                }
                bands.merge(level, edge, reverse ? Math::min : Math::max);
            }
            return bands;
        }

        /** The band a level's cards start beyond: the final one, or the one built so far when it reaches farther. */
        private static @Nullable Double layoutBand(@Nullable Double sofar, @Nullable Double fixed, double direction) {
            if (sofar == null) {
                return fixed;
            }
            if (fixed == null) {
                return sofar;
            }
            return direction > 0 ? Math.max(sofar, fixed) : Math.min(sofar, fixed);
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
            boolean blocks = layoutBlockMode();
            for (NodeBox box : boxes) {
                if (inViewport(box, width, height)) {
                    if (blocks) {
                        layoutDrawBlockRow(g, box);
                    } else {
                        drawNode(g, box);
                    }
                }
            }
            if (blocks) {
                layoutDrawBlockLabels(g, width, height);
            }
            layoutDrawFlash(g);
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
                    + (notedPaths.contains(entry.path()) ? MARKER_WIDTH : 0)
                    + (layoutModuleFolders.contains(entry.path()) ? MODULE_BADGE_WIDTH : 0);
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

        /**
         * The header a column is drawn with, in world units: as {@link #columnHeaderHeight}, except that with "Compact
         * rows" only a column in {@link #layoutChromeShown} has the band for its controls (review 3 B15).
         */
        private double layoutHeaderHeight(ProjectMapModel.Column column, double cardWidth) {
            if (!layoutCompactRows) {
                return columnHeaderHeight(column.depth(), cardWidth);
            }
            return column.depth() > 0 && layoutChromeShown.contains(column.id()) && detailControlsFit(cardWidth * zoom)
                    ? COLUMN_HEADER_HEIGHT
                    : COMPACT_HEADER_HEIGHT;
        }

        /** See {@link #layoutChromeShown}: the columns whose controls show with "Compact rows". */
        private Set<ProjectMapModel.ColumnId> layoutChromeColumns() {
            if (!layoutCompactRows || outputPaint) {
                return Set.of();
            }
            Set<ProjectMapModel.ColumnId> shown = new HashSet<>();
            if (layoutHoverColumn != null) {
                shown.add(layoutHoverColumn);
            }
            ProjectMapModel.Entry chosen = selected == null ? null : entryByPath.get(selected);
            if (chosen != null && chosen.depth() > 0) {
                shown.add(columnId(chosen));
            }
            columnQueries.forEach((id, text) -> {
                if (text != null && !text.isBlank()) {
                    shown.add(id);
                }
            });
            columnShowHidden.forEach((id, hidden) -> {
                if (hidden != null && hidden != showHiddenDefault) {
                    shown.add(id);
                }
            });
            columnLayouts.forEach((id, layout) -> {
                if (layout.locked) {
                    shown.add(id);
                }
            });
            columnControls.forEach((id, controls) -> {
                if (controls.filter().isFocused()
                        || controls.showHidden().isFocused()
                        || controls.pin().isFocused()) {
                    shown.add(id);
                }
            });
            return Set.copyOf(shown);
        }

        /** The column whose card is under {@code x, y} (screen), the topmost; or null. */
        private ProjectMapModel.@Nullable ColumnId layoutColumnAt(double x, double y) {
            for (int i = columnBoxes.size() - 1; i >= 0; i--) {
                ColumnBox box = columnBoxes.get(i);
                if (x >= box.x() && x <= box.x() + box.width() && y >= box.y() && y <= box.y() + box.height()) {
                    return box.column().depth() > 0 ? box.column().id() : null;
                }
            }
            return null;
        }

        /** Whether a card this wide on screen has room for its detail controls at the current zoom. */
        private boolean detailControlsFit(double cardScreenWidth) {
            if (outputPaint || layoutBlockMode()) {
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
                boolean controlsFit = detailControlsFit(box.width())
                        && (!layoutCompactRows || layoutChromeShown.contains(id))
                        && !underOverview(left, controlY, availableWidth, controlHeight);
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
                syncControlTraversal(controls);
            });
        }

        /**
         * Tab stops only at column controls that lie inside the viewport. Every laid-out column keeps its
         * controls, wherever it is, and Tab used to walk through dozens of them off-screen, leaving the keyboard
         * in a filter nobody could see (B7). The column filter of an off-screen column is still reached with
         * {@code /}, which scrolls it into view.
         */
        private void syncControlTraversal(ColumnControls controls) {
            for (Control control :
                    List.of(controls.filter(), controls.showHidden(), controls.pin(), controls.close())) {
                boolean onScreen = control.isVisible()
                        && control.getLayoutX() >= 0
                        && control.getLayoutY() >= 0
                        && control.getLayoutX() + control.getWidth() <= getWidth()
                        && control.getLayoutY() + control.getHeight() <= getHeight();
                if (control.isFocusTraversable() != onScreen) {
                    control.setFocusTraversable(onScreen);
                }
            }
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

        /**
         * Where the overview sits for the projected columns, or null when everything is on screen or the map
         * area is too small. The frame takes the map's shape inside at most 150 × 86, with the map centred in it,
         * so there is little empty frame that would hide rows for nothing (B2).
         */
        private @Nullable OverviewBox overviewGeometry(double viewportWidth, double viewportHeight) {
            if (outputPalette != null || columnBoxes.isEmpty()) {
                return null;
            }
            double minX = contentMinX();
            double minY = contentMinY();
            double maxX = contentMaxX();
            double maxY = contentMaxY();
            // The open cards are part of the picture: their marks stand where they are, and a card left out of view
            // brings the overview up as a column out of view does (review 3, C10).
            for (MapCards.OpenCard card : overviewCards.get()) {
                double[] drawn = card.bounds();
                minX = Math.min(minX, drawn[0]);
                minY = Math.min(minY, drawn[1]);
                maxX = Math.max(maxX, drawn[0] + drawn[2]);
                maxY = Math.max(maxY, drawn[1] + drawn[3]);
            }
            if (minX >= 0 && minY >= 0 && maxX <= viewportWidth && maxY <= viewportHeight) {
                return null; // everything is on screen: nothing to navigate to
            }
            if (viewportWidth < OVERVIEW_MIN_SURFACE_WIDTH || viewportHeight < OVERVIEW_MIN_SURFACE_HEIGHT) {
                return null; // a small window: the frame would hide too many rows (Fit and Center still navigate)
            }
            double contentWidth = Math.max(1, maxX - minX);
            double contentHeight = Math.max(1, maxY - minY);
            double maxWidth = Math.min(OVERVIEW_MAX_WIDTH, Math.max(OVERVIEW_MIN_WIDTH, viewportWidth * 0.18));
            double scale = Math.min(
                    (maxWidth - OVERVIEW_INSET * 2) / contentWidth,
                    (OVERVIEW_MAX_HEIGHT - OVERVIEW_INSET * 2) / contentHeight);
            // A tall, narrow map (sibling columns stacked) used to give a 28-pixel stripe: the frame is at least 90
            // wide and the picture is stretched along its short side, up to three times, to fill it (review 3 B8).
            double scaleX = Math.max(
                    scale,
                    Math.min(scale * OVERVIEW_MAX_STRETCH, (OVERVIEW_MIN_WIDTH - OVERVIEW_INSET * 2) / contentWidth));
            double scaleY = Math.max(
                    scale,
                    Math.min(scale * OVERVIEW_MAX_STRETCH, (OVERVIEW_MIN_SIDE - OVERVIEW_INSET * 2) / contentHeight));
            double overviewWidth = Math.max(OVERVIEW_MIN_WIDTH, contentWidth * scaleX + OVERVIEW_INSET * 2);
            double overviewHeight = Math.max(OVERVIEW_MIN_SIDE, contentHeight * scaleY + OVERVIEW_INSET * 2);
            double x = viewportWidth - overviewWidth - 10;
            double y = viewportHeight - overviewHeight - 10;
            if (x < reservedBarWidth + 6) {
                return null; // too narrow to sit beside the zoom bar, and anywhere else it would cover rows
            }
            double insetX = (overviewWidth - contentWidth * scaleX) / 2;
            double insetY = (overviewHeight - contentHeight * scaleY) / 2;
            return new OverviewBox(
                    x,
                    y,
                    overviewWidth,
                    overviewHeight,
                    minX,
                    minY,
                    scaleX,
                    scaleY,
                    insetX,
                    insetY,
                    contentWidth,
                    contentHeight);
        }

        /**
         * Compact overview for large or manually spread layouts; the bright rectangle is the viewport. It is
         * drawn on its own small canvas ({@link #overviewCanvas}) that sits above the cards and the zoom bar's
         * layer, so a card never hides it (C8).
         */
        private void drawOverview(GraphicsContext ignored, double viewportWidth, double viewportHeight) {
            OverviewBox overview = overviewBox;
            if (overview == null) {
                overviewCanvas.setVisible(false);
                return;
            }
            double x = overview.x();
            double y = overview.y();
            double overviewWidth = overview.width();
            double overviewHeight = overview.height();
            double minX = overview.minX();
            double minY = overview.minY();
            double scaleX = overview.scaleX();
            double scaleY = overview.scaleY();
            double insetX = overview.insetX();
            double insetY = overview.insetY();
            if (overviewCanvas.getWidth() != overviewWidth || overviewCanvas.getHeight() != overviewHeight) {
                overviewCanvas.setWidth(overviewWidth);
                overviewCanvas.setHeight(overviewHeight);
            }
            overviewCanvas.relocate(getLayoutX() + x, getLayoutY() + y);
            overviewCanvas.setVisible(true);
            GraphicsContext g = overviewCanvas.getGraphicsContext2D();
            g.clearRect(0, 0, overviewWidth, overviewHeight);
            g.setGlobalAlpha(0.94);
            g.setFill(color(surfaceProbe, Color.web("#161d27")));
            g.fillRoundRect(0.5, 0.5, overviewWidth - 1, overviewHeight - 1, 8, 8);
            g.setStroke(rowBorder());
            g.setLineWidth(1);
            g.strokeRoundRect(0.5, 0.5, overviewWidth - 1, overviewHeight - 1, 8, 8);
            Color muted = color(mutedProbe, Color.web("#8b949e"));
            Color structureMuted = mix(muted, structureTint(), 0.6);
            for (ColumnBox box : columnBoxes) {
                g.setFill(isStructureColumn(box.column().id()) ? structureMuted : muted);
                g.fillRoundRect(
                        insetX + (box.x() - minX) * scaleX,
                        insetY + (box.y() - minY) * scaleY,
                        Math.max(2, box.width() * scaleX),
                        Math.max(3, box.height() * scaleY),
                        2,
                        2);
            }
            drawOverviewCards(g, overview);
            // The viewport rectangle is clipped to the frame: panned far from the content it used to be
            // drawn outside the overview altogether.
            double frameRight = overviewWidth - 3;
            double frameBottom = overviewHeight - 3;
            double left = clampInto(insetX - minX * scaleX, 3, frameRight);
            double top = clampInto(insetY - minY * scaleY, 3, frameBottom);
            double right = clampInto(insetX + (viewportWidth - minX) * scaleX, 3, frameRight);
            double bottom = clampInto(insetY + (viewportHeight - minY) * scaleY, 3, frameBottom);
            g.setStroke(color(accentProbe, Color.web("#58a6ff")));
            g.setLineWidth(1.5);
            g.strokeRect(left, top, Math.max(2, right - left), Math.max(2, bottom - top));
            g.setGlobalAlpha(1);
        }

        /** The open cards the overview marks, and what a press on one of its marks does (the view's cards). */
        private java.util.function.Supplier<List<MapCards.OpenCard>> overviewCards = List::of;

        private Consumer<Region> onOverviewCard = card -> {};

        /**
         * The open cards as small accent marks in the overview, kept inside its frame: the cards built up in one place
         * still show, and a press on a mark brings its card back (review 3, C10).
         */
        private void drawOverviewCards(GraphicsContext g, OverviewBox overview) {
            List<MapCards.OpenCard> cards = overviewCards.get();
            if (cards.isEmpty()) {
                return;
            }
            Color accent = color(accentProbe, Color.web("#58a6ff"));
            g.setStroke(accent);
            g.setLineWidth(1);
            for (MapCards.OpenCard card : cards) {
                double[] mark = overviewMark(overview, card.bounds());
                g.setFill(card.note() ? noteColor() : accent);
                g.setGlobalAlpha(0.55);
                g.fillRect(mark[0], mark[1], mark[2], mark[3]);
                g.setGlobalAlpha(0.94);
                g.strokeRect(mark[0], mark[1], mark[2], mark[3]);
            }
        }

        /** Where a card drawn at {@code bounds} is marked in the overview, in its own pixels, inside its frame. */
        private static double[] overviewMark(OverviewBox overview, double[] bounds) {
            double w = Math.max(4, bounds[2] * overview.scaleX());
            double h = Math.max(4, bounds[3] * overview.scaleY());
            double x = clampInto(
                    overview.insetX() + (bounds[0] - overview.minX()) * overview.scaleX(), 3, overview.width() - 3 - w);
            double y = clampInto(
                    overview.insetY() + (bounds[1] - overview.minY()) * overview.scaleY(),
                    3,
                    overview.height() - 3 - h);
            return new double[] {x, y, w, h};
        }

        /** The open card whose overview mark is under {@code x, y} (the surface's pixels), the newest first; or null. */
        private @Nullable Region overviewCardAt(OverviewBox overview, double x, double y) {
            for (MapCards.OpenCard card : overviewCards.get()) {
                double[] mark = overviewMark(overview, card.bounds());
                double left = overview.x() + mark[0] - 2;
                double top = overview.y() + mark[1] - 2;
                if (x >= left && x <= left + mark[2] + 4 && y >= top && y <= top + mark[3] + 4) {
                    return card.node();
                }
            }
            return null;
        }

        /** Pans the map, and the cards on it, by {@code dx, dy} pixels. */
        void panBy(double dx, double dy) {
            offsetX += dx;
            offsetY += dy;
            repaint();
        }

        /** The overview's own canvas, a child of the map area above the cards (see {@link #drawOverview}). */
        Node overviewLayer() {
            return overviewCanvas;
        }

        /**
         * The overview canvas lies above the surface, so the pointer's events reach it first. They are the
         * surface's to handle — its press, drag and click logic already knows the overview — and are passed on
         * in the surface's coordinates.
         */
        private void overviewForwardsInput() {
            overviewCanvas.setManaged(false);
            overviewCanvas.setVisible(false);
            overviewCanvas.setViewOrder(-1);
            overviewCanvas.getStyleClass().add("project-map-overview");
            overviewCanvas.setCursor(Cursor.HAND);
            for (javafx.event.EventType<MouseEvent> type : List.of(
                    MouseEvent.MOUSE_PRESSED,
                    MouseEvent.MOUSE_DRAGGED,
                    MouseEvent.MOUSE_RELEASED,
                    MouseEvent.MOUSE_CLICKED,
                    MouseEvent.MOUSE_MOVED)) {
                overviewCanvas.addEventHandler(type, event -> {
                    javafx.event.Event.fireEvent(this, event.copyFor(this, this));
                    event.consume();
                });
            }
            overviewCanvas.addEventHandler(ScrollEvent.ANY, event -> {
                javafx.event.Event.fireEvent(this, event.copyFor(this, this));
                event.consume();
            });
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
                if (layoutBlockMode()) {
                    continue; // a block's label is drawn over its rows (layoutDrawBlockLabels)
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
                    double header = layoutHeaderHeight(column, w / zoom);
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

        /** Each column's names with what they share with their siblings, for the layout they were taken from. */
        private final Map<ProjectMapModel.ColumnId, Map<String, int[]>> layoutAffixes = new HashMap<>();

        private @Nullable WorldLayout layoutAffixesFor;

        /**
         * A row's name cut to {@code room}: what it shares with the other names of its column goes first, so twins
         * such as {@code AbstractRefreshableApplicationContext.java} and {@code
         * AbstractRefreshableConfigApplicationContext.java} stay apart (review 3 B10); the middle is cut only when the
         * part that tells it apart does not fit either.
         */
        private String layoutElide(ProjectMapModel.Entry entry, String text, double fontSize, double room) {
            WorldLayout world = projected;
            if (world != layoutAffixesFor) {
                layoutAffixes.clear();
                layoutAffixesFor = world;
            }
            ProjectMapModel.ColumnId id = columnId(entry);
            Map<String, int[]> shared = layoutAffixes.computeIfAbsent(id, ignored -> {
                List<String> names = new ArrayList<>();
                for (ProjectMapModel.Entry row : entries) {
                    if (!row.isPlaceholder() && id.equals(columnId(row))) {
                        names.add(row.name());
                    }
                }
                return ProjectMapElision.sharedAffixes(names);
            });
            int[] affixes = shared.get(text);
            if (affixes == null || affixes[0] + affixes[1] == 0) {
                return elideMiddle(text, fontSize, room);
            }
            if (elidedTitles.size() > 256) {
                elidedTitles.clear();
            }
            String key = text
                    + '\u0002'
                    + (int) room
                    + '\u0002'
                    + (int) (fontSize * 8)
                    + '\u0002'
                    + affixes[0]
                    + ','
                    + affixes[1];
            return elidedTitles.computeIfAbsent(key, ignored -> {
                // Below the length of its distinct part there is no such cut; above it, the longer the wider.
                int low = 0;
                while (low < text.length()
                        && ProjectMapElision.distinctCut(text, affixes[0], affixes[1], low) == null) {
                    low++;
                }
                String best = null;
                int high = text.length() - 1;
                while (low <= high) {
                    int middle = (low + high) / 2;
                    String cut = ProjectMapElision.distinctCut(text, affixes[0], affixes[1], middle);
                    if (cut != null && measuredLabelWidth(cut) * fontSize / 12 <= room) {
                        best = cut;
                        low = middle + 1;
                    } else {
                        high = middle - 1;
                    }
                }
                return best != null ? best : elideMiddle(text, fontSize, room);
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
            // The row's marks are laid out for the default height: a compact row draws them on a 32-unit band
            // centred on itself (review 3 B15).
            NodeBox content = layoutRowContent(box);
            boolean isSelected = entry.path().equals(selected);
            boolean isHovered = entry.path().equals(hovered) && !outputPaint;
            boolean selectedPath = isOnSelectedPath(entry.path());
            // The accent fill says "keys act here": it is kept for the selection while the surface has
            // keyboard focus. Without focus (and in printed output) the selection is a tinted row.
            boolean focusedSelection = isSelected && isFocused() && !outputPaint;
            // Ignored, vendored and generated rows recede as faded ones do (review 3 A11); the tooltip says why.
            boolean dimmed = (!prominence(entry.path()) || layoutIgnoredReason(entry) != null) && !isSelected;
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
            drawOpenMarker(g, entry, content, focusedSelection);

            double iconX = content.x() + 7 * zoom;
            double iconY = content.y() + 6 * zoom;
            drawIcon(g, entry, iconX, iconY, focusedSelection);
            if (entry.isSymbol()) {
                drawSymbolRow(g, entry, content, focusedSelection, isSelected);
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
            double room = content.width() - rowExtraWidth(entry) * zoom;
            if (measuredLabelWidth(label) * zoom > room) {
                label = layoutElide(entry, label, 12 * zoom, room);
            }
            fillLabel(g, ink, label, content.x() + 31 * zoom, content.y() + 20.5 * zoom);

            drawStatusMarks(g, entry, content, focusedSelection);
            drawFileMarkers(g, entry, content, focusedSelection);
            if (entry.directory()) {
                layoutDrawModuleBadge(g, entry, content, focusedSelection);
                drawChevron(g, entry, content, focusedSelection);
            } else {
                drawPreviewAffordance(g, content, focusedSelection, isHovered);
                if (hasStructure(entry)) {
                    drawStructureAffordance(g, entry, content, focusedSelection, isHovered);
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

        /** Whether the map is zoomed out far enough to be drawn as labelled blocks (review 3 B8); never in print. */
        private boolean layoutBlockMode() {
            return zoom < BLOCK_ZOOM - 1e-9 && !outputPaint;
        }

        /**
         * A row zoomed far out: a bar, no text. Matches of the search and the filters are in the accent, the selection
         * strongest; dimmed rows fade. The column's block label says what it is.
         */
        private void layoutDrawBlockRow(GraphicsContext g, NodeBox box) {
            ProjectMapModel.Entry entry = box.entry();
            Color accent = color(accentProbe, Color.web("#388bfd"));
            Color muted = color(mutedProbe, Color.web("#8b949e"));
            boolean isSelected = entry.path().equals(selected);
            boolean match = filters != null && filters.active() && emphasized.contains(entry.path());
            boolean faded = !prominence(entry.path()) || layoutIgnoredReason(entry) != null;
            double inset = Math.min(2, box.width() * 0.05);
            double barHeight = Math.max(1, box.height() - Math.max(1, 2 * zoom));
            g.setGlobalAlpha(isSelected ? 1 : match ? 0.9 : faded ? 0.16 : entry.isPlaceholder() ? 0.2 : 0.42);
            g.setFill(
                    isSelected || match ? accent : openPaths.contains(entry.path()) ? mix(muted, accent, 0.5) : muted);
            g.fillRoundRect(box.x() + inset, box.y(), box.width() - inset * 2, barHeight, 3, 3);
            if (isSelected) {
                g.setStroke(color(focusRingProbe, accent));
                g.setLineWidth(1.5);
                g.strokeRoundRect(box.x() - 1, box.y() - 1, box.width() + 2, barHeight + 2, 4, 4);
            }
            g.setGlobalAlpha(1);
        }

        /**
         * Each block's label, over its rows at a legible size: the column's title, then how many items it lists and,
         * while a search or a filter is on, how many of them match (review 3 B8).
         */
        private void layoutDrawBlockLabels(GraphicsContext g, double width, double height) {
            Color surface = color(surfaceProbe, Color.web("#161d27"));
            Color text = color(textProbe, Color.web("#d8dee9"));
            Color muted = color(mutedProbe, Color.web("#8b949e"));
            Color accent = color(accentProbe, Color.web("#388bfd"));
            boolean filtering = filters != null && filters.active();
            Font titleFont = mapFont(FontWeight.SEMI_BOLD, FontPosture.REGULAR, 11);
            Font detailFont = mapFont(FontWeight.NORMAL, FontPosture.REGULAR, 10);
            for (ColumnBox box : columnBoxes) {
                if (box.x() > width || box.y() > height || box.x() + box.width() < 0 || box.y() + box.height() < 0) {
                    continue;
                }
                double room = box.width() - 6;
                if (room < 14) {
                    continue; // too narrow for even an ellipsis: the overview and the tooltips still name it
                }
                ProjectMapModel.Column column = box.column();
                String title = columnTitle(column);
                int matches = 0;
                if (filtering) {
                    for (ProjectMapModel.Entry entry : column.entries()) {
                        if (emphasized.contains(entry.path())) {
                            matches++;
                        }
                    }
                }
                String detail = matches > 0
                        ? tr("project.map.block.countMatches", column.countLabel(), matches)
                        : tr("project.map.block.count", column.countLabel());
                if (matches > 0 && measuredLabelWidth(detail) * 10 / 12 > room) {
                    detail = tr("project.map.block.matches", matches); // the matches matter more than the count
                }
                String shownTitle = measuredLabelWidth(title) * 11 / 12 <= room ? title : elide(title, 11, room);
                String shownDetail = measuredLabelWidth(detail) * 10 / 12 <= room ? detail : elide(detail, 10, room);
                double labelWidth = Math.min(
                        room,
                        Math.max(measuredLabelWidth(shownTitle) * 11 / 12, measuredLabelWidth(shownDetail) * 10 / 12));
                double left = box.x() + 3;
                // A long block keeps its label in sight while its top is scrolled off.
                double top = Math.max(box.y() + 2, Math.min(2, box.y() + box.height() - 31));
                g.setGlobalAlpha(0.92);
                g.setFill(surface);
                g.fillRoundRect(left, top, labelWidth + 6, 29, 6, 6);
                g.setGlobalAlpha(1);
                g.setFont(titleFont);
                g.setFill(box == headerFor(selected) ? accent : text);
                g.fillText(shownTitle, left + 3, top + 12);
                g.setFont(detailFont);
                g.setFill(matches > 0 ? accent : muted);
                g.fillText(shownDetail, left + 3, top + 25);
            }
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
            g.fillText(entry.name(), box.x() + 10 * zoom, layoutRowContent(box).y() + 20.5 * zoom);
        }

        /** The 32-unit band a row's icon, label and marks are drawn on: the row itself, or centred on a compact one. */
        private NodeBox layoutRowContent(NodeBox box) {
            double nominal = NODE_HEIGHT * zoom;
            if (box.height() >= nominal - 0.01) {
                return box;
            }
            return new NodeBox(box.entry(), box.x(), box.y() + (box.height() - nominal) / 2, box.width(), nominal);
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

        /** The width a module folder's badge takes before its other marks (review 3 B4). */
        private static final double MODULE_BADGE_WIDTH = 16;

        /**
         * A module folder — one holding a build file (go.mod, pom.xml, build.gradle, package.json, Cargo.toml,
         * pyproject.toml) — carries a small package glyph before its marks, so a project's modules stand out from
         * the folders around them (review 3 B4). The tooltip names the build files.
         */
        private void layoutDrawModuleBadge(
                GraphicsContext g, ProjectMapModel.Entry entry, NodeBox box, boolean onAccentFill) {
            if (!layoutModuleFolders.contains(entry.path())) {
                return;
            }
            double markers = (bookmarkedPaths.contains(entry.path()) ? MARKER_WIDTH : 0)
                    + (notedPaths.contains(entry.path()) ? MARKER_WIDTH : 0);
            double cx = box.x() + box.width() - (CHEVRON_ZONE + markers + MODULE_BADGE_WIDTH / 2 + 2) * zoom;
            double cy = box.y() + box.height() / 2;
            double half = 5 * zoom;
            g.setStroke(onAccentFill ? onAccent() : color(structureProbe, Color.web("#8250df")));
            g.setLineWidth(Math.max(1, 1.2 * zoom));
            // A box seen from the front, with its lid: the package glyph.
            g.strokeRect(cx - half, cy - half * 0.55, half * 2, half * 1.55);
            g.strokeLine(cx - half, cy - half * 0.55, cx - half * 0.6, cy - half);
            g.strokeLine(cx + half, cy - half * 0.55, cx + half * 0.6, cy - half);
            g.strokeLine(cx - half * 0.6, cy - half, cx + half * 0.6, cy - half);
            g.strokeLine(cx, cy - half * 0.55, cx, cy + half * 0.2);
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
            String kind = entry.directory() ? FOLDER_ICON : fileIconKind(entry.name());
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
                return gitChanged(entry.path()) ? "git-status-dir-changed" : "";
            }
            if (modifiedPaths.contains(entry.path())) {
                return "modified-file";
            }
            GitFileStatus status = gitStatusOf(entry.path());
            return status == null ? "" : status.cssClass();
        }

        private static final String FOLDER_ICON = "folder";

        /** A file's icon kind: its glyph, and whether it carries the lock of an encrypted file inside it. */
        private static String fileIconKind(String fileName) {
            String glyph = FileIcons.iconKeyFor(fileName);
            return FileIcons.isEncryptedWithInnerType(fileName) ? glyph + "+lock" : glyph;
        }

        /** How many icons the warm-up rasterises per pulse: a few milliseconds, spread out. */
        private static final int ICONS_PER_PULSE = 4;

        /** The icons still to rasterise ahead of need (A8). */
        private final java.util.Deque<IconKey> iconWarmup = new java.util.ArrayDeque<>();

        private final AnimationTimer iconWarmupTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                warmSomeIcons();
            }
        };

        /**
         * Rasterises the theme's icon set, a few per pulse, while the Map shows. A big search used to meet a
         * hundred icon kinds it had not drawn yet in one paint and snapshot each of them on the spot, which
         * froze every window for a visible moment (Map review 3 A8). Statuses other than plain (and the
         * selection's on-accent ink) stay on demand: they are a few rows at a time.
         */
        private void warmIcons() {
            iconWarmup.clear();
            iconWarmup.add(new IconKey(FOLDER_ICON, ""));
            iconWarmup.add(new IconKey(FOLDER_ICON, "git-status-dir-changed"));
            for (String glyph : FileIcons.iconKeys()) {
                iconWarmup.add(new IconKey(glyph, ""));
            }
            iconWarmupTimer.start();
        }

        private void warmSomeIcons() {
            if (!active || getScene() == null || outputPaint) {
                iconWarmupTimer.stop(); // shown again (setShowing, a new scene) starts it over
                return;
            }
            for (int done = 0; done < ICONS_PER_PULSE && !iconWarmup.isEmpty(); ) {
                IconKey key = iconWarmup.removeFirst();
                if (iconImages.containsKey(key)) {
                    continue;
                }
                iconImages.put(key, rasterizeKind(key.kind(), key.statusClass()));
                done++;
            }
            if (iconWarmup.isEmpty()) {
                iconWarmupTimer.stop();
            }
        }

        /** The image for an icon kind, as {@link #rasterizeIcon} draws it for a file of that kind. */
        private Image rasterizeKind(String kind, String statusClass) {
            if (kind.equals(FOLDER_ICON)) {
                return rasterize(FileIcons.forProjectItem("", true), "folder-cell", statusClass);
            }
            return rasterize(FileIcons.forIconKey(kind), "file-cell", statusClass);
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
            GitFileStatus status = gitStatusOf(entry.path());
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
            java.util.function.Supplier<java.util.Collection<Path>> candidates = stateCandidates;
            Iterable<ProjectMapModel.Entry> rows = entries;
            java.util.Collection<Path> candidatePaths = null;
            if (candidates != null) {
                try {
                    candidatePaths = candidates.get();
                } catch (RuntimeException unavailable) {
                    candidatePaths = null; // ask every row, as without candidates
                }
            }
            if (candidatePaths != null) {
                // Open tabs and marked paths are few; the rows can be 1,200, each predicate a scan of the tabs.
                List<ProjectMapModel.Entry> found = new ArrayList<>();
                Set<Path> seen = new HashSet<>();
                for (Path candidate : candidatePaths) {
                    try {
                        Path path = candidate == null ? null : ProjectMapModel.normalize(candidate);
                        ProjectMapModel.Entry entry = path == null ? null : entryByPath.get(path);
                        if (entry != null && seen.add(path)) {
                            found.add(entry);
                        }
                    } catch (RuntimeException otherConnection) {
                        // a path on another SFTP connection throws from equals(): it is not one of these rows
                    }
                }
                rows = found;
            }
            for (ProjectMapModel.Entry entry : rows) {
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
            overviewDragging = false;
            if (event.getButton() == MouseButton.PRIMARY && navigateFromOverview(event.getX(), event.getY())) {
                overviewDragging = true; // dragging on in the overview moves the view rectangle along (B2)
                event.consume();
                return;
            }
            ColumnBox header = headerHit(event.getX(), event.getY());
            if (header != null && event.getButton() == MouseButton.PRIMARY) {
                ProjectMapModel.ColumnId id = header.column().id();
                ColumnLayout layout = columnLayouts.computeIfAbsent(id, ignored -> new ColumnLayout());
                if (!layout.locked) {
                    draggedColumn = id;
                    // Start from where the column is drawn: a push from a packed neighbour in the last paint
                    // becomes part of its offset, so the drag moves it at once (B4).
                    columnPressOffsetX = layout.x + layout.pushX;
                    columnPressOffsetY = layout.y + layout.pushY;
                    layout.pushX = 0;
                    layout.pushY = 0;
                    event.consume();
                    return;
                }
            }
            // Rows have no drag action of their own, so a primary drag pans wherever it starts — past a small
            // threshold when it starts on a row, so a click that wobbles stays a click (B24).
            panning = event.getButton() == MouseButton.MIDDLE || event.getButton() == MouseButton.PRIMARY;
            panFromRow = event.getButton() == MouseButton.PRIMARY && hit(event.getX(), event.getY()) != null;
        }

        private void mouseDragged(MouseEvent event) {
            if (overviewDragging) {
                flushPendingRepaint();
                navigateFromOverview(event.getX(), event.getY());
                dragMoved = true;
                event.consume();
                return;
            }
            if (draggedColumn != null) {
                ColumnLayout layout = columnLayouts.get(draggedColumn);
                if (layout != null && !layout.locked) {
                    // The last paint pushed the column clear of a packed neighbour: take that push into the
                    // offset, so dragging back moves it at once instead of first unwinding a hidden overshoot.
                    columnPressOffsetX += layout.pushX;
                    columnPressOffsetY += layout.pushY;
                    layout.pushX = 0;
                    layout.pushY = 0;
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
            if (panFromRow
                    && !dragMoved
                    && Math.hypot(event.getX() - pressX, event.getY() - pressY) < ROW_PAN_THRESHOLD) {
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
            layoutHover(layoutColumnAt(event.getX(), event.getY()));
            layoutOverlaysGiveWay(hit, event.getX(), event.getY());
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

        /** The column under the pointer changed: with "Compact rows" its controls come or go (review 3 B15). */
        private void layoutHover(ProjectMapModel.@Nullable ColumnId column) {
            if (!java.util.Objects.equals(column, layoutHoverColumn)) {
                layoutHoverColumn = column;
                if (layoutCompactRows) {
                    repaint();
                }
            }
        }

        /** The pointer left the map: nothing is hovered any more. */
        private void mouseExited(MouseEvent event) {
            pointerInside = false;
            layoutHover(null);
            layoutOverlaysGiveWay(null, 0, 0);
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
            Path base = root;
            // A folder says where it is in the project and what it holds (review 3 B4); a file keeps its full path.
            lines.add(
                    entry.directory()
                                    && base != null
                                    && entry.path().startsWith(base)
                                    && !entry.path().equals(base)
                            ? layoutRelative(base, entry.path()) + "/"
                            : entry.path().toString());
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
            layoutTooltipLines(entry, lines);

            List<String> statuses = new ArrayList<>(5);
            if (openPaths.contains(entry.path())) {
                statuses.add(tr("project.map.tooltip.open"));
            }
            if (modifiedPaths.contains(entry.path())) {
                statuses.add(tr("project.map.tooltip.unsaved"));
            }
            if (gitChanged(entry.path())) {
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

        /** What review 3 adds to a row's tooltip: a folder's counts and build files, why a row is dimmed. */
        private void layoutTooltipLines(ProjectMapModel.Entry entry, List<String> lines) {
            Path base = root;
            ProjectMapOverview.Facts facts = orientation.facts();
            if (entry.directory()
                    && base != null
                    && facts != null
                    && entry.path().startsWith(base)) {
                String rel = layoutRelative(base, entry.path());
                int[] counts = facts.folderCounts().get(rel);
                if (counts != null) {
                    java.text.NumberFormat number =
                            java.text.NumberFormat.getIntegerInstance(FileHistoryPanel.locale());
                    lines.add(tr(
                            "project.map.tooltip.contains",
                            number.format(counts[0]),
                            number.format(counts[1]),
                            number.format(counts[2])));
                }
                for (ProjectMapOverview.Module module : facts.modules()) {
                    if (module.folder().equals(rel) && !rel.isEmpty()) {
                        lines.add(tr("project.map.tooltip.module", String.join(", ", module.markers())));
                    }
                }
            }
            ProjectMapIgnored.Reason reason = layoutIgnoredReason(entry);
            if (reason != null) {
                lines.add(tr(reason.key()));
            }
        }

        /** {@code path} relative to {@code base}, {@code /}-separated. */
        private static String layoutRelative(Path base, Path path) {
            StringBuilder rel = new StringBuilder();
            for (Path part : base.relativize(path)) {
                if (!rel.isEmpty()) {
                    rel.append('/');
                }
                rel.append(part);
            }
            return rel.toString();
        }

        private static String formatSize(long bytes) {
            return formatSize(bytes, FileHistoryPanel.locale());
        }

        /** A size in binary units, its number written as {@code locale} writes numbers ("1,5 KiB" in German). */
        private static String formatSize(long bytes, Locale locale) {
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
            java.text.NumberFormat oneDecimal = java.text.NumberFormat.getNumberInstance(locale);
            oneDecimal.setMinimumFractionDigits(1);
            oneDecimal.setMaximumFractionDigits(1);
            return oneDecimal.format(value) + " " + units[unit];
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
            } else if (entry.isFile() && !singleClickOpens) {
                // "Single-click opens files" off: the click above selected the row, a double-click opens it.
                if (event.getClickCount() == 2) {
                    onActivate.accept(entry);
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
            if (event.getCode() == KeyCode.F3 && plain) {
                // F3 / Shift+F3 step through a search's matches (B1); claimed, so consumed either way.
                resultsStep(event.isShiftDown() ? -1 : 1);
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
                case UP, DOWN, LEFT, RIGHT -> keysStep(() -> navigateArrow(event.getCode()));
                case PAGE_UP -> moveSibling(-10);
                case PAGE_DOWN -> moveSibling(10);
                case BACK_SPACE -> keysStep(this::selectParent);
                case ENTER, SPACE -> {
                    if (event.getCode() == KeyCode.ENTER && event.isShiftDown() && plain) {
                        flushPendingRepaint();
                        // Shift+Enter: the selected file's or declaration's preview card, opened or closed (B6).
                        selectedEntry()
                                .filter(entry -> nodeBox(entry.path()) != null)
                                .ifPresent(onTogglePreview);
                    } else {
                        activateSelection();
                    }
                }
                case HOME, END -> {
                    if (!plain) {
                        return;
                    }
                    keysStep(() -> keysHomeEnd(event.getCode() == KeyCode.END, event.isShiftDown()));
                }
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
            } else if (!shortcut && keysTypeAhead(event.getCharacter())) {
                event.consume();
            }
        }

        /** The typed prefix of the column's type-ahead, and when its last character came (review 3 B14). */
        private final StringBuilder keysTyped = new StringBuilder();

        private long keysTypedAt = Long.MIN_VALUE;
        /** The type-ahead's clock, in milliseconds; replaced by tests. */
        java.util.function.LongSupplier keysClock = System::currentTimeMillis;

        /**
         * Runs a keyboard move as one step of a run, which the Back/Forward history folds into the row the run
         * ends on (review 3 B5): the arrows, Backspace, Home/End and type-ahead.
         */
        private void keysStep(Runnable move) {
            boolean was = movingSibling;
            movingSibling = true;
            try {
                move.run();
            } finally {
                movingSibling = was;
            }
        }

        /**
         * Home and End in the selected row's column: its first row and its last (review 3 B14). Shift+Home goes to
         * the first column's first row, where Home used to go whatever the column.
         */
        private void keysHomeEnd(boolean end, boolean shift) {
            if (shift && !end) {
                select(
                        boxes.isEmpty()
                                ? entries.getFirst().path()
                                : boxes.getFirst().entry().path());
                return;
            }
            List<ProjectMapModel.Entry> rows = keysColumnRows();
            if (!rows.isEmpty()) {
                select((end ? rows.getLast() : rows.getFirst()).path());
            }
        }

        /**
         * The rows drawn in the selection's column, in order: the column it is a row of, or — for the root or a
         * folder of a compacted chain, which stand as a column's header — the column it heads.
         */
        private List<ProjectMapModel.Entry> keysColumnRows() {
            ColumnBox header = headerFor(selected);
            ProjectMapModel.ColumnId id = header != null
                    ? header.column().id()
                    : selectedEntry().map(MapSurface::columnId).orElse(null);
            if (id == null) {
                return List.of();
            }
            return boxes.stream()
                    .map(NodeBox::entry)
                    .filter(entry -> columnId(entry).equals(id))
                    .toList();
        }

        /**
         * Type-ahead in the selected row's column (review 3 B14): the typed letters select the next row whose name
         * starts with them; a pause of {@value #TYPE_AHEAD_RESET_MILLIS} ms starts a new prefix, and the same
         * letter typed again steps to the next row with that initial. False for what is not a name's character.
         */
        private boolean keysTypeAhead(@Nullable String character) {
            if (character == null
                    || character.isEmpty()
                    || Character.isISOControl(character.codePointAt(0))
                    || keysTyped.isEmpty() && Character.isWhitespace(character.codePointAt(0))) {
                return false;
            }
            long now = keysClock.getAsLong();
            if (keysTypedAt == Long.MIN_VALUE || now - keysTypedAt > TYPE_AHEAD_RESET_MILLIS) {
                keysTyped.setLength(0);
            }
            keysTypedAt = now;
            boolean again = keysTyped.length() == 1 && keysTyped.toString().equalsIgnoreCase(character);
            if (!again) {
                keysTyped.append(character);
            }
            List<ProjectMapModel.Entry> rows = keysColumnRows().stream()
                    .filter(entry -> !entry.isPlaceholder())
                    .toList();
            List<String> names = rows.stream().map(ProjectMapView::keysRowName).toList();
            int current = -1;
            for (int index = 0; index < rows.size(); index++) {
                if (rows.get(index).path().equals(selected)) {
                    current = index;
                }
            }
            int target = typeAheadTarget(names, current, keysTyped.toString(), keysTyped.length() == 1);
            if (target >= 0) {
                keysStep(() -> select(rows.get(target).path()));
            }
            return true;
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
         * every keymap), Ctrl-N/Ctrl-P (New File, Print or Find File outside Emacs), the row actions F2
         * and Delete, and F3/Shift+F3 (a search's next/previous match; Find again in some keymaps). Not while a column filter has the focus: there the keymap keeps its meaning.
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
            int target = index >= 0
                    ? siblingIndex(index, delta, column.size())
                    : selectionStepFromHidden(current, column, delta);
            movingSibling = true;
            try {
                select(column.get(target).path());
            } finally {
                movingSibling = false;
            }
        }

        /**
         * A sibling step from a selection a filter hid: the first drawn row after it (or before it, upwards)
         * counts as the first step, so the move starts from where the row would be, not from the top (B16).
         */
        private int selectionStepFromHidden(
                ProjectMapModel.Entry current, List<ProjectMapModel.Entry> drawn, int delta) {
            List<ProjectMapModel.Entry> all = selectionSiblings(current);
            int position = all.indexOf(current);
            int anchor = -1;
            if (delta > 0) {
                for (int i = position + 1; i < all.size() && anchor < 0; i++) {
                    anchor = drawn.indexOf(all.get(i));
                }
                if (anchor < 0) {
                    return Math.abs(delta) == 1 ? 0 : drawn.size() - 1;
                }
                return Math.clamp(anchor + delta - 1, 0, drawn.size() - 1);
            }
            for (int i = position - 1; i >= 0 && anchor < 0; i--) {
                anchor = drawn.indexOf(all.get(i));
            }
            if (anchor < 0) {
                return Math.abs(delta) == 1 ? drawn.size() - 1 : 0;
            }
            return Math.clamp(anchor + delta + 1, 0, drawn.size() - 1);
        }

        /** Every loaded row of {@code entry}'s column, drawn or not, in the order the column shows them. */
        private List<ProjectMapModel.Entry> selectionSiblings(ProjectMapModel.Entry entry) {
            return columnRowsInOrder(entries, columnId(entry));
        }

        /**
         * "Hide unmatched" took the selected row off the map: the selection moves to the nearest row still drawn
         * in its column, else to its nearest drawn ancestor, so the keyboard and the breadcrumb keep a visible
         * anchor (B16).
         */
        private void keepSelectionDrawn() {
            Path current = selected;
            ProjectMapModel.Entry entry = current == null ? null : entryByPath.get(current);
            if (current == null || entry == null || !narrowsAway(current) || nodeBox(current) != null) {
                return;
            }
            Path target = null;
            List<ProjectMapModel.Entry> siblings = selectionSiblings(entry);
            int position = siblings.indexOf(entry);
            for (int distance = 1; target == null && distance < siblings.size(); distance++) {
                for (int index : new int[] {position + distance, position - distance}) {
                    if (target == null && index >= 0 && index < siblings.size()) {
                        ProjectMapModel.Entry candidate = siblings.get(index);
                        if (!candidate.isPlaceholder() && nodeBox(candidate.path()) != null) {
                            target = candidate.path();
                        }
                    }
                }
            }
            for (Path parent = entry.parent(); target == null && parent != null; parent = parent.getParent()) {
                // The root and a compacted chain's folders have no row: their column's title stands for them.
                if (nodeBox(parent) != null || headerFor(parent) != null) {
                    target = parent;
                }
            }
            if (target != null) {
                movingSibling = true; // a forced move, folded into the history like a sibling step
                try {
                    select(target);
                } finally {
                    movingSibling = false;
                }
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

        /** What closing {@code id}'s column closes: a compacted column folds back into the row its chain starts at. */
        private @Nullable Path closeTarget(ProjectMapModel.ColumnId id) {
            ColumnBox box = columnBox(id);
            return box != null && box.column().compacted() ? box.column().anchor() : id.parent();
        }

        /**
         * What the row menu's Close Column closes for {@code entry}'s column, or null for the first column (the
         * root's children), which has no close button.
         */
        @Nullable
        Path closeTargetFor(ProjectMapModel.Entry entry) {
            Path parent = entry.parent();
            if (parent == null || parent.equals(root)) {
                return null;
            }
            return closeTarget(columnId(entry));
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
            revealSelected();
            repaint();
            updateAccessibleText();
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
            if (id == null || id.depth() <= 0) {
                onStatus.accept(tr("project.map.status.rootColumnNoFilter"));
                return;
            }
            ColumnControls controls = dataColumnControls(id); // an off-screen column has none yet
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

        /**
         * Centres the view on the map point under an overview press or drag. The point is clamped to the map's
         * bounds, so a press on the frame's margin never pans the view into empty space (B2).
         */
        private boolean navigateFromOverview(double x, double y) {
            OverviewBox overview = overviewBox;
            if (overview == null || !overview.contains(x, y)) {
                return false;
            }
            Region card = overviewDragging ? null : overviewCardAt(overview, x, y);
            if (card != null) {
                onOverviewCard.accept(card); // a card's mark: the card itself comes into view (review 3, C10)
                return true;
            }
            double mapX =
                    clampInto((x - overview.x() - overview.insetX()) / overview.scaleX(), 0, overview.contentWidth());
            double mapY =
                    clampInto((y - overview.y() - overview.insetY()) / overview.scaleY(), 0, overview.contentHeight());
            offsetX += getWidth() / 2 - (overview.minX() + mapX);
            offsetY += getHeight() / 2 - (overview.minY() + mapY);
            repaint();
            return true;
        }

        private boolean contains(Path path) {
            return path != null
                    && entries.stream().anyMatch(entry -> entry.path().equals(path));
        }

        /**
         * The zoom bar and the overview as drawn, {@code {x, y, w, h, weight}}: cards and their Structure columns keep
         * clear of them (review 3, E11).
         */
        private List<double[]> cardChrome() {
            List<double[]> chrome = new ArrayList<>(2);
            if (reservedBarWidth > 0 && reservedBottom > 0) {
                chrome.add(new double[] {
                    0, getHeight() - reservedBottom, reservedBarWidth, reservedBottom, MapCards.CHROME_WEIGHT
                });
            }
            OverviewBox overview = overviewBox;
            if (overview != null) {
                chrome.add(new double[] {
                    overview.x(), overview.y(), overview.width(), overview.height(), MapCards.CHROME_WEIGHT
                });
            }
            return chrome;
        }

        /** Every column on screen as {@code {x, y, w, h, 1}}, for a card's Structure column choosing its side. */
        private List<double[]> cardColumns() {
            List<double[]> columns = new ArrayList<>();
            for (ColumnBox column : columnBoxes) {
                if (column.x() <= getWidth()
                        && column.x() + column.width() >= 0
                        && column.y() <= getHeight()
                        && column.y() + column.height() >= 0) {
                    columns.add(new double[] {column.x(), column.y(), column.width(), column.height(), 1});
                }
            }
            return columns;
        }

        /**
         * What card placement needs to know about the row at {@code path}, in panel pixels (see {@code
         * drawnPlacement}): the row and its column as {@code {x, y, w, h}}, the extent of the columns on screen, and
         * what a card should not cover, as {@code {x, y, w, h, weight}} — every column on screen (the row's own
         * weighs 6, those that lead to it 4, others 1), and the band where the row's next column would open (3); the
         * zoom bar and the overview are added by the cards themselves ({@link #cardChrome}). Reads the layout only.
         */
        private MapCards.Site cardSite(Path path) {
            NodeBox node = boxes.stream()
                    .filter(box -> box.entry().path().equals(path))
                    .findFirst()
                    .orElse(null);
            ColumnBox own = node == null
                    ? null
                    : columnBoxes.stream()
                            .filter(box -> box.column().id().equals(columnId(node.entry())))
                            .findFirst()
                            .orElse(null);
            if (node == null || own == null) {
                return new MapCards.Site(null, new double[4], new double[4], List.of());
            }
            List<double[]> obstacles = new ArrayList<>();
            double top = Double.MAX_VALUE;
            double bottom = -Double.MAX_VALUE;
            double left = Double.MAX_VALUE;
            double right = -Double.MAX_VALUE;
            for (ColumnBox column : columnBoxes) {
                if (column.x() > getWidth()
                        || column.x() + column.width() < 0
                        || column.y() > getHeight()
                        || column.y() + column.height() < 0) {
                    continue; // off screen: a card there covers nothing of it
                }
                Path listed = column.column().parent();
                double weight = column == own
                        ? 6
                        : listed != null && path.startsWith(listed) ? 4 : 1; // a column that leads to the row
                obstacles.add(new double[] {column.x(), column.y(), column.width(), column.height(), weight});
                top = Math.min(top, column.y());
                bottom = Math.max(bottom, column.y() + column.height());
                left = Math.min(left, column.x());
                right = Math.max(right, column.x() + column.width());
            }
            boolean horizontal =
                    flowDirection == FlowDirection.LEFT_TO_RIGHT || flowDirection == FlowDirection.RIGHT_TO_LEFT;
            boolean forward =
                    flowDirection == FlowDirection.LEFT_TO_RIGHT || flowDirection == FlowDirection.TOP_TO_BOTTOM;
            ProjectMapModel.Entry entry = node.entry();
            ProjectMapModel.Symbol symbol = entry.symbol();
            boolean opensColumn = entry.directory() && !entry.isPlaceholder()
                    || hasStructure(entry)
                    || entry.isSymbol() && symbol != null && symbol.branches();
            if (opensColumn) { // where its next column — a folder's, or a file's Structure column — opens
                double step = PREVIEW_COLUMN_GAP + (horizontal ? own.width() : own.height());
                double nextX = horizontal ? own.x() + (forward ? step : -step) : own.x();
                double nextY = horizontal ? own.y() : own.y() + (forward ? step : -step);
                obstacles.add(new double[] {nextX, nextY, own.width(), own.height(), 3});
            }
            // The zoom bar and the overview are the cards' own obstacles (cardChrome), heavier than any column.
            if (top > bottom) {
                top = own.y();
                bottom = own.y() + own.height();
                left = own.x();
                right = own.x() + own.width();
            }
            return new MapCards.Site(
                    new double[] {node.x(), node.y(), node.width(), node.height()},
                    new double[] {own.x(), own.y(), own.width(), own.height()},
                    new double[] {left, Math.max(0, top), right - left, Math.min(getHeight(), bottom) - Math.max(0, top)
                    },
                    obstacles);
        }

        private double screenX(double worldX) {
            return offsetX + worldX * zoom;
        }

        private double screenY(double worldY) {
            return offsetY + worldY * zoom;
        }

        private void updateAccessibleText() {
            String text = accessibleSelectionText();
            if (!text.equals(getAccessibleText())) {
                setAccessibleText(text);
                // The property alone tells no one: a screen reader hears of the change only through this.
                notifyAccessibleAttributeChanged(AccessibleAttribute.TEXT);
            }
        }

        /**
         * What a tree item would expose, in one string: the row, its kind, its states and where it stands among
         * the rows its column draws — not those a column filter or "Hide unmatched" took out, nor a "+N more"
         * row (B5).
         */
        private String accessibleSelectionText() {
            ProjectMapModel.Entry current = selectedEntry().orElse(null);
            if (current == null) {
                return tr("project.map.accessibleSelection", tr("project.map.empty"));
            }
            ProjectMapModel.ColumnId id = columnId(current);
            boolean narrowing = hideUnmatched && filters != null && filters.active();
            List<ProjectMapModel.Entry> drawn = List.of();
            for (ProjectMapModel.Column column : ProjectMapModel.columnsById(
                    entries, columnQueries, columnShowHidden, narrowing ? emphasized : null)) {
                if (column.id().equals(id)) {
                    drawn = column.entries().stream()
                            .filter(row -> !row.isPlaceholder())
                            .toList();
                    break;
                }
            }
            int index = drawn.indexOf(current);
            List<String> states = new ArrayList<>();
            int position;
            if (index >= 0) {
                position = index + 1;
            } else {
                // Hidden by its column's filter: say so, and where it would stand.
                List<ProjectMapModel.Entry> all = selectionSiblings(current);
                int at = all.indexOf(current);
                position = 1
                        + (int) drawn.stream()
                                .filter(row -> all.indexOf(row) < at)
                                .count();
                states.add(tr("project.map.accessible.state.filtered"));
            }
            int count = drawn.size();
            ProjectMapModel.Symbol symbol = current.symbol();
            String base;
            if (symbol != null) {
                base = tr(
                        "project.map.accessible.symbol",
                        current.name(),
                        symbol.kind() == null ? "" : symbol.kind(),
                        symbol.line() + 1,
                        position,
                        count);
            } else {
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
                base = tr(key, current.name(), position, count);
                Path path = current.path();
                if (modifiedPaths.contains(path)) {
                    states.add(tr("project.map.tooltip.unsaved"));
                }
                GitFileStatus git = gitState.get(path);
                if (git != null || gitDirectories.contains(path)) {
                    states.add(tr("project.map.tooltip.gitChanged"));
                }
                if (bookmarkedPaths.contains(path)) {
                    states.add(tr("project.map.tooltip.bookmarked"));
                }
                if (notedPaths.contains(path)) {
                    states.add(tr("project.map.tooltip.personalNotes"));
                }
                if (current.isFile() && structure.isOpen(path)) {
                    states.add(tr("project.map.accessible.state.structureOpen"));
                }
            }
            String folder = resultsAccessibleFolder(current.path());
            if (!folder.isEmpty()) {
                base = tr("project.map.accessible.inFolder", base, folder); // which of four proxier.go (B11)
            }
            String match = resultsAccessibleSuffix(current.path());
            if (!match.isEmpty()) {
                base = base + ", " + match;
            }
            return states.isEmpty() ? base : tr("project.map.accessible.withStates", base, String.join(", ", states));
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
                boolean compactChains,
                LayoutExtras layout) {}

        /** What the review 3 layout options add to the layout's inputs: row density, ignored rows, module badges. */
        private record LayoutExtras(
                boolean compactRows,
                Set<ProjectMapModel.ColumnId> chrome,
                boolean hideIgnored,
                int ignoredVersion,
                Set<Path> modules) {}

        private record FontKey(String family, FontWeight weight, FontPosture posture, double size) {}

        private record ColumnBox(ProjectMapModel.Column column, double x, double y, double width, double height) {}

        private record ColumnControls(TextField filter, CheckBox showHidden, ToggleButton pin, Button close) {}

        /** The overview frame in surface pixels, and where the map's bounds lie inside it. */
        private record OverviewBox(
                double x,
                double y,
                double width,
                double height,
                double minX,
                double minY,
                double scaleX,
                double scaleY,
                double insetX,
                double insetY,
                double contentWidth,
                double contentHeight) {
            private boolean contains(double px, double py) {
                return px >= x && px <= x + width && py >= y && py <= y + height;
            }
        }

        private static final class ColumnLayout {
            private double x;
            private double y;
            /** How far the last paint pushed the column past {@link #x}/{@link #y}, clear of a packed neighbour. */
            private double pushX;

            private double pushY;
            private boolean locked;
        }

        private record IconKey(String kind, String statusClass) {}
    }

    /** One column's rows, filters aside, in the order the map shows them (the model's sort). */
    private static List<ProjectMapModel.Entry> columnRowsInOrder(
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

    private static boolean safeTest(Predicate<Path> predicate, Path path) {
        try {
            return predicate != null && predicate.test(path);
        } catch (RuntimeException failed) {
            ProjectMapLog.unexpected("checking a row's state", failed);
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
