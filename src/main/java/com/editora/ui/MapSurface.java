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
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.event.EventHandler;
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
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Control;
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
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.stage.WindowEvent;
import javafx.util.Duration;

import com.editora.git.GitFileStatus;
import com.editora.ui.ProjectMapView.FlowDirection;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;
import static com.editora.ui.ProjectMapView.DEFAULT_FLOW;
import static com.editora.ui.ProjectMapView.TYPE_AHEAD_RESET_MILLIS;
import static com.editora.ui.ProjectMapView.claimedChords;
import static com.editora.ui.ProjectMapView.clampInto;
import static com.editora.ui.ProjectMapView.columnRowsInOrder;
import static com.editora.ui.ProjectMapView.connectorOpacity;
import static com.editora.ui.ProjectMapView.fadedInk;
import static com.editora.ui.ProjectMapView.flowLimitedOffset;
import static com.editora.ui.ProjectMapView.mix;
import static com.editora.ui.ProjectMapView.rowBorderColor;
import static com.editora.ui.ProjectMapView.safeTest;
import static com.editora.ui.ProjectMapView.shiftIntoView;
import static com.editora.ui.ProjectMapView.siblingIndex;
import static com.editora.ui.ProjectMapView.typeAheadTarget;
import static com.editora.ui.ProjectMapView.wheelAction;

/**
 * The Project Map's canvas: a single keyboard focus target with deterministic screen-space hit boxes. It lays the
 * view's rows out in columns, paints them, and takes the pointer and the keyboard; the view ({@link ProjectMapView})
 * owns what is listed, the search, the cards and the chrome around it. (An inner class of the view until the Map's
 * refactor stage 2 gave it its own file; it still reaches the view through {@link #view}.)
 */
final class MapSurface extends Region {

    /** The view this canvas belongs to: its rows, cards, structure columns and actions. */
    private final ProjectMapView view;

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
    /** About how wide one character of a row's usage count is, unzoomed, at its 10px font. */
    private static final double USES_CHAR_WIDTH = 5.6;
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
    /** The heat of "Show recent changes" (Map explore position 3): a row's tint and its meter. */
    private final Rectangle activityHeatProbe = probe("project-map-probe-heat");
    /** The heat meter's ink by heat, row fill and text colour ({@link ProjectMapActivity#meterInk}). */
    private final Map<List<Color>, Color> activityMeterInks = new HashMap<>();
    /** Heat-tinted row fills by fill, heat, text, muted ink and step ({@link ProjectMapActivity#tinted}). */
    private final Map<List<Object>, Color> activityTints = new HashMap<>();

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
    Map<Path, ProjectMapModel.Entry> entryByPath = Map.of();

    Set<Path> expandedSnapshot = Set.of();
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
    Set<Path> notedPaths = Set.of();
    /** The errors and warnings of the files, and of the folders that hold them (Map code browser round 2). */
    private ProjectMapProblems problems = ProjectMapProblems.NONE;

    private Set<Path> emphasized = Set.of();
    /** The rows the selection uses and that use it, under ⋯ "Show dependencies of the selection" (round 2). */
    private MapDependencyOverlay dependencyOverlay = MapDependencyOverlay.NONE;
    /** The files the Changes card's branch changes, with their status letters (round 3, item 3). */
    private Map<Path, Character> reviewFiles = Map.of();
    /** The folders holding any of {@link #reviewFiles}. */
    private Set<Path> reviewFolders = Set.of();

    FlowDirection flowDirection = DEFAULT_FLOW;
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
    /** Export ▸ PNG is taking its snapshot: the overview stays hidden ({@link #canvasHoldOverview}). */
    private boolean canvasOverviewHeld;

    private Consumer<ProjectMapModel.Entry> onActivate = entry -> {};
    private Consumer<Path> onCloseColumn = path -> {};
    private Consumer<Path> onPreview = path -> {};
    private Consumer<ProjectMapModel.Entry> onStructure = entry -> {};
    private Consumer<Path> onNotesPreview = path -> {};
    Consumer<ProjectMapModel.Entry> onTogglePreview = entry -> {};
    private Consumer<Path> onSelectionChanged = path -> {};
    private Function<ProjectMapModel.Entry, @Nullable ContextMenu> contextMenuFactory = entry -> null;
    private Runnable onZoomChanged = () -> {};
    double zoom = 1.0;
    private boolean keepZoomOnColumnOpen = true;
    private boolean focusNewColumn = true;
    /** The ⋯ menu's "Single-click opens files" (see ProjectMapView.singleClickOpens). */
    boolean singleClickOpens = true;

    double offsetX;
    double offsetY;
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
    boolean movingSibling;

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

    /** The rows on screen as accessible tree items (Map explore, position 14). */
    private final MapAccessibleRows canvasA11y = new MapAccessibleRows(new MapAccessibleRows.Actions() {
        @Override
        public void select(Path path) {
            MapSurface.this.select(path);
            requestFocus();
        }

        @Override
        public void activate(Path path) {
            ProjectMapModel.Entry entry = entryByPath.get(path);
            if (entry != null) {
                MapSurface.this.select(path);
                onActivate.accept(entry);
            }
        }

        @Override
        public void expand(Path path, boolean expand) {
            canvasExpand(path, expand);
        }

        @Override
        public javafx.geometry.@Nullable Bounds toScreen(double x, double y, double width, double height) {
            return localToScreen(new javafx.geometry.BoundingBox(x, y, width, height));
        }
    });
    /** The rows on screen changed since {@link #canvasA11y} was last told (a paint). */
    private boolean canvasA11yStale = true;

    MapSurface(ProjectMapView view) {
        this.view = view;
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
                        canvasA11y,
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
                        structureProbe,
                        activityHeatProbe);
        setMinSize(80, 100);
        overviewForwardsInput();
        setFocusTraversable(true);
        // PARENT, not TREE_VIEW: the canvas has no item nodes, and a tree role made screen readers look for
        // rows, find none and announce an empty tree. PARENT is a plain group (AXGroup on macOS, a pane in
        // UI Automation) that promises no items; its TEXT names the selection and its states and changes
        // with it (updateAccessibleText), and its children are the real column controls (B5). Since Map explore
        // position 14 one more child, canvasA11y, is a tree of the rows on screen, each a tree item.
        setAccessibleRole(AccessibleRole.PARENT);
        setAccessibleHelp(tr("project.map.accessibleHelp"));
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
                structureProbe,
                activityHeatProbe)) {
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
            columnControls.values().forEach(controls -> controls.showHidden().setSelected(view.showHiddenDefault));
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
        if (selected == null || !byPath.containsKey(selected)) {
            selected = this.entries.isEmpty() ? null : this.entries.getFirst().path();
        }
        snapshotStates();
        recomputeEmphasis();
        syncColumnControls();
        repaint();
        keepSelectionDrawn();
        updateAccessibleText();
        view.layoutEntriesShown(this.entries);
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
        return entry.isPlaceholder() || entry.isSymbol() ? null : view.orientation.reason(entry.path());
    }

    /** The rows the layout lays out: all, or without the ignored and generated ones and what is in them. */
    private List<ProjectMapModel.Entry> layoutLaidEntries() {
        if (!layoutHideIgnored) {
            return entries;
        }
        List<ProjectMapModel.Entry> kept = new ArrayList<>(entries.size());
        for (ProjectMapModel.Entry entry : entries) {
            Path where = entry.isPlaceholder() ? entry.parent() : entry.path();
            if (entry.isSymbol() || where == null || !view.orientation.dimmedOrInside(where)) {
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
            headBottom = Math.min(bottom, box.y() + (COLUMN_TOP_INSET + header + 3 * (rowHeight() + rowGap())) * zoom);
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
                controls.showHidden().setSelected(view.showHiddenDefault);
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
            if (layoutFlashPath == null || !view.active || (now - layoutFlashStart) / 1e6 > FLASH_MILLIS) {
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
                setZoom(Math.max(REVEAL_MIN_ZOOM, zoom * need), box.x() + box.width() / 2, box.y() + box.height() / 2);
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
        Path base = view.root;
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
        return new double[] {Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[2], b[2]), Math.max(a[3], b[3])};
    }

    /** The row the zoom bar or the overview gives way to while the pointer is on it (review 3 B7); or null. */
    private @Nullable Path layoutGivingWayTo;
    /** Fades the zoom bar and lets the pointer through it (true), or brings it back (false). */
    Consumer<Boolean> layoutOnZoomBarGiveWay = giveWay -> {};

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
        return box.x() < x + width && box.x() + box.width() > x && box.y() < y + height && box.y() + box.height() > y;
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
        ProjectMapModel.Entry hoveredEntry = hovered == null ? null : entryByPath.get(hovered);
        if (hoveredEntry != null) {
            nodeTooltip.setText(tooltipText(hoveredEntry));
        }
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
        if (!view.active) {
            view.dataRepaintWhileHidden = true; // nobody sees it: setShowing(true) paints once (D9)
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
                view.updateCardVisibility(); // what "Hide unmatched" leaves may have changed
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
            canvasA11yPainted();
            moveCardsWithViewport();
            view.updateCardVisibility(); // repaints the connectors
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
        view.cards.moveWithViewport(fromX, fromY, fromZoom, offsetX, offsetY, zoom);
        view.bearingsChanged();
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

    /**
     * World-space bounds of every laid-out column, and of every open card (and what is stuck on one) when {@code
     * withCards}, plus the paper margin, or null for an empty map.
     */
    private ProjectMapOutputPlan.@Nullable Box outputBounds(boolean withCards) {
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
        for (ProjectMapOutputPlan.Box card : withCards ? outputCardBoxes() : List.<ProjectMapOutputPlan.Box>of()) {
            minX = Math.min(minX, card.x());
            minY = Math.min(minY, card.y());
            maxX = Math.max(maxX, card.maxX());
            maxY = Math.max(maxY, card.maxY());
        }
        return new ProjectMapOutputPlan.Box(
                minX - OUTPUT_MARGIN,
                minY - OUTPUT_MARGIN,
                Math.max(1, maxX - minX) + OUTPUT_MARGIN * 2,
                Math.max(1, maxY - minY) + OUTPUT_MARGIN * 2);
    }

    /** The open cards and what is stuck on them as drawn now, in world space: a printed map shows them too. */
    private List<ProjectMapOutputPlan.Box> outputCardBoxes() {
        List<ProjectMapOutputPlan.Box> cards = new ArrayList<>();
        for (javafx.scene.Node node : view.cards.outputNodes()) {
            javafx.geometry.Bounds drawn = node.getBoundsInParent();
            if (drawn.getWidth() > 0 && drawn.getHeight() > 0) {
                cards.add(worldBox(drawn.getMinX(), drawn.getMinY(), drawn.getWidth(), drawn.getHeight()));
            }
        }
        return cards;
    }

    /**
     * The cards of a Print or PDF job, taken once from the live map area: each card (and what is stuck on one) as a
     * picture with where it is drawn, back to front, and the row each connector starts at, all in the live map area's
     * pixels at {@code zoom} and {@code offsetX, offsetY}. The pages put them on top of the map.
     */
    private record OutputCards(
            List<OutputCard> cards,
            java.util.function.Function<Path, double @Nullable []> rowBox,
            double zoom,
            double offsetX,
            double offsetY) {}

    /** One card's picture and the rectangle it covers, in the live map area's pixels. */
    private record OutputCard(Image image, double x, double y, double width, double height) {}

    /** Most image pixels a card's picture is taken at across, however large the card is drawn on paper. */
    private static final double MAX_CARD_IMAGE = 4_096;

    /**
     * Takes the pictures of the open cards for pages rendered at {@code renderScale} image pixels per world pixel, and
     * keeps the rows (as the output lays them out) for their connectors. Null when no card is open.
     */
    private @Nullable OutputCards outputCards(double renderScale) {
        List<javafx.scene.Node> nodes = view.cards.outputNodes();
        if (nodes.isEmpty()) {
            return null;
        }
        double liveZoom = zoom;
        double liveOffsetX = offsetX;
        double liveOffsetY = offsetY;
        double scale = renderScale / liveZoom; // map-area pixels → page image pixels
        List<OutputCard> cards = new ArrayList<>(nodes.size());
        for (javafx.scene.Node node : nodes) {
            javafx.geometry.Bounds drawn = node.getBoundsInParent();
            if (drawn.getWidth() <= 0 || drawn.getHeight() <= 0) {
                continue;
            }
            double imageScale = Math.min(scale, MAX_CARD_IMAGE / Math.max(drawn.getWidth(), drawn.getHeight()));
            SnapshotParameters parameters = new SnapshotParameters();
            parameters.setFill(Color.TRANSPARENT);
            parameters.setTransform(javafx.scene.transform.Transform.scale(imageScale, imageScale));
            // The snapshot is taken in the parent's coordinates and covers the card's bounds there: its picture
            // stands at the card's top-left, whatever transforms and shadow the card has.
            Image image = node.snapshot(parameters, null);
            cards.add(new OutputCard(image, drawn.getMinX(), drawn.getMinY(), drawn.getWidth(), drawn.getHeight()));
        }
        // A snapshot may run a layout pass: the rows are kept after it, in the output's layout at the live zoom.
        repaint();
        List<NodeBox> rows = List.copyOf(boxes);
        WorldLayout world = projected;
        java.util.function.Function<Path, double @Nullable []> rowBox = path -> {
            Integer index = world == null ? null : world.firstRows().get(path);
            if (index == null || index >= rows.size()) {
                return null;
            }
            NodeBox box = rows.get(index);
            return new double[] {box.x(), box.y(), box.width(), box.height()};
        };
        return new OutputCards(List.copyOf(cards), rowBox, liveZoom, liveOffsetX, liveOffsetY);
    }

    /**
     * Draws the cards and their connectors over the map just painted on the output canvas: the canvas shows the world
     * at {@link #zoom}, shifted by {@link #offsetX}, {@link #offsetY}, and the cards were taken from the live map area.
     */
    private void paintOutputCards(OutputCards cards) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double scale = zoom / cards.zoom();
        g.save();
        try {
            g.setTransform(scale, 0, 0, scale, offsetX - cards.offsetX() * scale, offsetY - cards.offsetY() * scale);
            view.cards.drawOutputConnectors(g, cards.rowBox());
            g.setGlobalAlpha(1);
            for (OutputCard card : cards.cards()) {
                g.drawImage(card.image(), card.x(), card.y(), card.width(), card.height());
            }
        } finally {
            g.restore();
        }
    }

    private ProjectMapOutputPlan.Box worldBox(double x, double y, double width, double height) {
        return new ProjectMapOutputPlan.Box((x - offsetX) / zoom, (y - offsetY) / zoom, width / zoom, height / zoom);
    }

    boolean outputIsLandscape() {
        outputPaint = true; // measure the layout that output paints: headers collapsed to their title
        try {
            repaint();
            ProjectMapOutputPlan.Box bounds = outputBounds(true);
            return bounds != null && bounds.width() > bounds.height();
        } finally {
            outputPaint = false;
            repaint();
        }
    }

    /**
     * Renders every laid-out column for pages of the given printable size, independent of the current
     * pan and zoom and always in a light palette, with the open cards (and what is stuck on them) and their
     * connectors drawn over it where they stand on the map, as on screen. Painting goes to an off-scene Canvas, so
     * the live Canvas, viewport and hover state are untouched; interactive controls and the overview are left out.
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
        ProjectMapOutputPlan.Box bounds = outputBounds(true);
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
        obstacles.addAll(outputCardBoxes());
        ProjectMapOutputPlan.Plan plan = ProjectMapOutputPlan.plan(bounds, obstacles, pageWidth, pageHeight);
        if (plan.pages().isEmpty() || !Double.isFinite(plan.renderScale()) || plan.renderScale() <= 0) {
            return null;
        }

        OutputCards cards = outputCards(plan.renderScale()); // what is on screen over the map: on paper too
        List<List<ProjectMapOutput.Label>> labels = canvasOutputLabels(plan); // before the pages move the offsets
        List<List<ProjectMapOutput.Label>> outputLabels = List.of();
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
                pages.add(renderOutputPage(cell, cards));
            }
            outputLabels = labels;
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
        return new ProjectMapOutput.Rendered(List.copyOf(pages), plan.pointsPerPixel(), plan, outputLabels);
    }

    /**
     * The searchable text of the PDF pages (Map explore, position 16): each row's whole name and each column's
     * title, where the page image draws it, in that page's image pixels. The PDF lays it over the picture as
     * invisible text, so a reader finds, selects and copies the names the picture shows. Uses the layout of the
     * output (headers collapsed), before the pages are painted.
     */
    private List<List<ProjectMapOutput.Label>> canvasOutputLabels(ProjectMapOutputPlan.Plan plan) {
        List<ProjectMapOutputPlan.Cell> cells = plan.pages();
        List<List<ProjectMapOutput.Label>> pages = new ArrayList<>(cells.size());
        for (int i = 0; i < cells.size(); i++) {
            pages.add(new ArrayList<>());
        }
        double scale = plan.renderScale();
        for (NodeBox screen : boxes) {
            ProjectMapModel.Entry entry = screen.entry();
            if (entry.isPlaceholder()) {
                continue;
            }
            NodeBox content = layoutRowContent(screen);
            double left = (content.x() - offsetX) / zoom + 31;
            double top = (content.y() - offsetY) / zoom + 20.5 - ProjectMapOutputPlan.LABEL_SIZE;
            double room = content.width() / zoom - rowExtraWidth(entry);
            double width = Math.max(1, Math.min(measuredLabelWidth(entry.name()), room));
            canvasPlaceLabel(pages, cells, scale, entry.name(), left, top, width, ProjectMapOutputPlan.LABEL_SIZE);
        }
        for (ColumnBox column : columnBoxes) {
            String title = columnTitle(column.column());
            double left = (column.x() - offsetX) / zoom + 10;
            double top = (column.y() - offsetY) / zoom + 16.5 - 11;
            double width = Math.max(1, Math.min(measuredLabelWidth(title) * 11 / 12, column.width() / zoom - 20));
            canvasPlaceLabel(pages, cells, scale, title, left, top, width, 11);
        }
        return pages;
    }

    /** Puts one label on the page its middle falls on, in that page's image pixels. */
    private static void canvasPlaceLabel(
            List<List<ProjectMapOutput.Label>> pages,
            List<ProjectMapOutputPlan.Cell> cells,
            double scale,
            String text,
            double left,
            double top,
            double width,
            double height) {
        double middleX = left + Math.min(width, 40) / 2;
        double middleY = top + height / 2;
        for (int i = 0; i < cells.size(); i++) {
            ProjectMapOutputPlan.Cell cell = cells.get(i);
            if (middleX >= cell.x()
                    && middleX < cell.x() + cell.width()
                    && middleY >= cell.y()
                    && middleY < cell.y() + cell.height()) {
                double right = Math.min(left + width, cell.x() + cell.width());
                pages.get(i)
                        .add(new ProjectMapOutput.Label(
                                text,
                                (left - cell.x()) * scale,
                                (top - cell.y()) * scale,
                                Math.max(1, (right - left) * scale),
                                height * scale));
                return;
            }
        }
    }

    /**
     * ⋯ → Export ▸ SVG… (Map explore, position 16): the whole map, as Print/PDF lays it out (every laid-out
     * column, headers collapsed, the light palette) but without the open cards, with each row's name and each
     * column's title as text.
     * Null for an empty map.
     */
    @Nullable
    String canvasSvg(String title) {
        outputPaint = true;
        Map<Rectangle, Color> livePalette = outputPalette;
        outputPalette = outputPalette();
        try {
            repaint();
            ProjectMapOutputPlan.Box bounds = outputBounds(false); // the SVG has no cards
            WorldLayout world = projected;
            if (bounds == null || world == null) {
                return null;
            }
            List<ProjectMapSvg.Column> columns = new ArrayList<>(world.columns().size());
            for (ColumnBox box : world.columns()) {
                ProjectMapModel.Column column = box.column();
                columns.add(new ProjectMapSvg.Column(
                        columnTitle(column),
                        column.countLabel(),
                        isStructureColumn(column.id()),
                        box.x(),
                        box.y(),
                        box.width(),
                        box.height()));
            }
            List<ProjectMapSvg.Row> rows = new ArrayList<>(world.rows().size());
            for (NodeBox box : world.rows()) {
                ProjectMapModel.Entry entry = box.entry();
                double top = box.y() + (box.height() - NODE_HEIGHT) / 2; // a compact row's centred band
                String label = entry.name(); // a "+N more" or stub row's text is its name
                double room = box.width() - (entry.isPlaceholder() ? 20 : rowExtraWidth(entry));
                String shown = measuredLabelWidth(label) > room && room > 12
                        ? (entry.isPlaceholder() ? elide(label, 12, room) : layoutElide(entry, label, 12, room))
                        : label;
                rows.add(new ProjectMapSvg.Row(
                        label,
                        shown,
                        entry.directory(),
                        entry.isSymbol(),
                        entry.isPlaceholder(),
                        entry.path().equals(selected),
                        !entry.isPlaceholder() && layoutIgnoredReason(entry) != null,
                        box.x(),
                        box.y(),
                        box.width(),
                        box.height(),
                        box.x() + (entry.isPlaceholder() ? 10 : 31),
                        top + 20.5));
            }
            List<ProjectMapSvg.Link> links = canvasSvgLinks(world);
            return ProjectMapSvg.write(
                    new double[] {bounds.x(), bounds.y(), bounds.width(), bounds.height()},
                    columns,
                    rows,
                    links,
                    new ProjectMapSvg.Palette(
                            ProjectMapSvg.hex(color(bgProbe, Color.WHITE)),
                            ProjectMapSvg.hex(color(surfaceProbe, Color.web("#f6f8fa"))),
                            ProjectMapSvg.hex(color(borderProbe, Color.web("#d0d7de"))),
                            ProjectMapSvg.hex(mix(color(surfaceProbe, Color.web("#f6f8fa")), structureTint(), 0.16)),
                            "#ffffff",
                            ProjectMapSvg.hex(color(borderProbe, Color.web("#d0d7de"))),
                            ProjectMapSvg.hex(color(textProbe, Color.web("#1f2328"))),
                            ProjectMapSvg.hex(color(mutedProbe, Color.web("#59636e"))),
                            ProjectMapSvg.hex(color(accentProbe, Color.web("#0969da"))),
                            ProjectMapSvg.hex(color(folderProbe, Color.web("#0969da"))),
                            ProjectMapSvg.hex(color(fileProbe, Color.web("#59636e"))),
                            ProjectMapSvg.hex(structureTint())),
                    title);
        } finally {
            outputPalette = livePalette;
            outputPaint = false;
            repaint();
        }
    }

    /** One link per row of a column, from the row that opened it (one per column across a vertical flow). */
    private List<ProjectMapSvg.Link> canvasSvgLinks(WorldLayout world) {
        List<ProjectMapSvg.Link> links = new ArrayList<>();
        boolean vertical = isVerticalFlow();
        boolean reverse = isReverseFlow();
        int next = 0;
        for (int index = 0; index < world.columns().size(); index++) {
            ColumnBox column = world.columns().get(index);
            int count = column.column().entries().size();
            int first = next;
            next += count;
            int anchor = world.anchorRows().get(index);
            if (anchor < 0 || anchor >= world.rows().size() || count == 0) {
                continue;
            }
            NodeBox parent = world.rows().get(anchor);
            if (vertical) {
                double fromY = reverse ? parent.y() : parent.y() + parent.height();
                double toY = reverse ? column.y() + column.height() : column.y();
                links.add(new ProjectMapSvg.Link(
                        parent.x() + parent.width() / 2, fromY, column.x() + column.width() / 2, toY, true));
                continue;
            }
            for (int row = first; row < next && row < world.rows().size(); row++) {
                NodeBox child = world.rows().get(row);
                double fromX = reverse ? parent.x() : parent.x() + parent.width();
                double toX = reverse ? child.x() + child.width() : child.x();
                links.add(new ProjectMapSvg.Link(
                        fromX, parent.y() + parent.height() / 2, toX, child.y() + child.height() / 2, false));
            }
        }
        return links;
    }

    private static int outputTileSize() {
        double scale = 1;
        for (javafx.stage.Screen screen : javafx.stage.Screen.getScreens()) {
            scale = Math.max(scale, Math.max(screen.getOutputScaleX(), screen.getOutputScaleY()));
        }
        return Math.max(256, Math.min(OUTPUT_TILE, (int) (OUTPUT_TEXTURE_LIMIT / Math.ceil(scale))));
    }

    /** Paints one page's world rectangle at the current (output) zoom, a bounded tile at a time. */
    private Image renderOutputPage(ProjectMapOutputPlan.Cell cell, @Nullable OutputCards cards) {
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
                if (cards != null) {
                    paintOutputCards(cards);
                }
                if (tileWidth == pageWidth && tileHeight == pageHeight) {
                    canvas.snapshot(parameters, page);
                } else {
                    WritableImage tile = canvas.snapshot(parameters, null);
                    page.getPixelWriter().setPixels(tileX, tileY, tileWidth, tileHeight, tile.getPixelReader(), 0, 0);
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
        palette.put(activityHeatProbe, Color.web("#9a6700"));
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
        NodeBox box = nodeBox(selected);
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
            Platform.runLater(view::bearingsRestoreCards); // placed on the map as it is now
        }
    }

    /** The last session's {@code {zoom, offsetX, offsetY}}, applied by the first view in place of its fit. */
    double @Nullable [] bearingsViewport;

    /** Views (working sets): the next rows are fitted again as a first view's are, with {@link #bearingsViewport}. */
    void viewsRefit() {
        viewportInitialized = false;
        initialFitPending = false;
    }

    /** Views (trails): whether the first view's fit is done, so a card placed now goes beside its row. */
    boolean viewsLaidOut() {
        return viewportInitialized && !initialFitPending;
    }

    private void syncColumnControls() {
        Set<ProjectMapModel.ColumnId> ids = entries.stream()
                .filter(entry -> entry.depth() > 0)
                .map(entry -> new ProjectMapModel.ColumnId(entry.depth(), entry.parent()))
                .collect(java.util.stream.Collectors.toSet());
        List<ProjectMapModel.ColumnId> removed =
                columnControls.keySet().stream().filter(id -> !ids.contains(id)).toList();
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
            view.truncatedColumnFilterChanged(id.parent());
            repaint();
            updateAccessibleText(); // the column's count of drawn rows changed
            if (selected != null && nodeBox(selected) == null) {
                boxes.stream()
                        .filter(box -> columnId(box.entry()).equals(id))
                        .findFirst()
                        .ifPresent(box -> select(box.entry().path()));
            }
        });
        filter.setOnAction(event -> leaveColumnFilter(id));

        CheckBox showHidden = new CheckBox(tr("project.map.column.showHidden"));
        showHidden.getStyleClass().add("project-map-column-hidden");
        showHidden.setSelected(columnShowHidden.getOrDefault(id, view.showHiddenDefault));
        showHidden.setTooltip(new Tooltip(tr("project.map.column.showHiddenHelp")));
        showHidden.setAccessibleText(tr("project.map.column.showHidden"));
        showHidden.selectedProperty().addListener((obs, old, selected) -> {
            if (syncingHidden) {
                return;
            }
            columnShowHidden.put(id, selected);
            view.columnHiddenChanged(); // hidden rows are not loaded while off, so turning it on lists again
            repaint();
            if (this.selected != null && nodeBox(this.selected) == null) {
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
        Icons.name(pin, tr(locked ? "project.map.column.unpin" : "project.map.column.pin"));
        pin.selectedProperty().addListener((obs, old, selected) -> {
            columnLayouts.computeIfAbsent(id, ignored -> new ColumnLayout()).locked = selected;
            Icons.name(pin, tr(selected ? "project.map.column.unpin" : "project.map.column.pin"));
        });

        Button close = new Button("×");
        close.getStyleClass().add("project-map-column-close");
        Icons.name(close, tr("project.map.column.close"));
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
            if (parent.equals(view.root)) {
                break;
            }
        }
        return null;
    }

    /** Whether the Git chip counts a row: it has a status, or is a folder holding a change. */
    boolean gitChanged(Path path) {
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
                        filters.problems(),
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
                    notedPaths.contains(path),
                    problems.has(path))) {
                direct.add(path);
            }
        }
        addUnloadedMatches(direct, loadedPaths);
        Set<Path> result = ProjectMapModel.emphasized(entries, direct, view.root);
        // A declaration is as prominent as its file: the filters are about files, not what is in them.
        for (ProjectMapModel.Entry entry : entries) {
            ProjectMapModel.Symbol symbol = entry.symbol();
            if (symbol != null && result.contains(symbol.file())) {
                result.add(entry.path());
            }
        }
        result.addAll(ProjectMapModel.emphasized(entries, searchMatchingSymbols(symbolRows), view.root));
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
                filters.problems(),
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
                            notedPaths.contains(file),
                            problems.has(file)));
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
        if (view.root == null || !tabs && !markers && !filters.problems()) {
            return;
        }
        Set<Path> candidates = new HashSet<>();
        try {
            candidates.addAll(view.openFiles.get()); // an open buffer may hold markers not stored yet
            if (markers) {
                candidates.addAll(view.markerCandidates.get());
            }
            if (filters.problems()) {
                candidates.addAll(problems.files()); // a file with problems inside a collapsed folder lights it
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
                        || !com.editora.config.PathKeys.sameFileSystem(path, view.root)
                        || !path.startsWith(view.root)
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
                    filters.personalNotes() && safeTest(noteState, path),
                    filters.problems() && problems.has(path))) {
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
        if (outputPaint) {
            // Print, PDF and SVG show a whole folder by its first rows and "+N more" (Map review 4 D1).
            columns = ProjectMapModel.canvasOutputColumns(columns, MapLoader::placeholderLabel);
        }
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
        Map<Path, Integer> firstRows = new HashMap<>(); // nodeBox: a path's first row, in O(1) (Map review 4 D3)
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
                inheritedShift = flowShift.getOrDefault(parentColumn.column().id(), 0.0);
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
                firstRows.putIfAbsent(box.entry().path(), rowList.size());
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
                narrowedTotal,
                java.util.Collections.unmodifiableMap(firstRows));
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
        double headerHeight = layoutCompactRows ? COMPACT_HEADER_HEIGHT : columnHeaderHeight(column.depth(), cardWidth);
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
                g.setGlobalAlpha(
                        connectorOpacity(selectedPath, prominence(child.entry().path())));
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
        for (ProjectMapModel.Entry entry : canvasWidestCandidates(rows)) {
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

    /** Rows past which a column's width is measured on its longest names only (position 15). */
    private static final int CANVAS_MEASURE_ALL_ROWS = 512;

    /** How many of a long column's longest names are measured for its width. */
    private static final int CANVAS_MEASURED_NAMES = 96;

    /**
     * The rows whose labels decide a column's width: all of them in an ordinary column; in a long folder listed
     * whole (thousands of rows, position 15) the ones with the longest names, so its first layout measures a
     * hundred labels, not every one. The width is capped at {@link #MAX_NODE_WIDTH} and a longer label is cut
     * where it differs, so a name a few characters shorter never decides it.
     */
    private static List<ProjectMapModel.Entry> canvasWidestCandidates(List<ProjectMapModel.Entry> rows) {
        if (rows.size() <= CANVAS_MEASURE_ALL_ROWS) {
            return rows;
        }
        java.util.PriorityQueue<ProjectMapModel.Entry> longest = new java.util.PriorityQueue<>(
                CANVAS_MEASURED_NAMES + 1,
                java.util.Comparator.comparingInt(entry -> entry.name().length()));
        for (ProjectMapModel.Entry entry : rows) {
            longest.add(entry);
            if (longest.size() > CANVAS_MEASURED_NAMES) {
                longest.poll();
            }
        }
        return List.copyOf(longest);
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
            if (hidden != null && hidden != view.showHiddenDefault) {
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
            double hiddenX = controls.filter().getLayoutX() + controls.filter().getWidth() + COLUMN_CONTROL_GAP;
            controls.showHidden().resize(hiddenWidth, controlHeight);
            controls.showHidden().relocate(hiddenX, controlY);
            controls.pin().resize(pinWidth, controlHeight);
            controls.pin().relocate(Math.min(right - pinWidth, hiddenX + hiddenWidth + COLUMN_CONTROL_GAP), controlY);
            controls.pin().setVisible(canvasLockShown(id, controls));
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
        for (Control control : List.of(controls.filter(), controls.showHidden(), controls.pin(), controls.close())) {
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
        if (overview == null || canvasOverviewHeld) {
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
    java.util.function.Supplier<List<MapCards.OpenCard>> overviewCards = List::of;

    Consumer<Region> onOverviewCard = card -> {};

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
                overview.insetY() + (bounds[1] - overview.minY()) * overview.scaleY(), 3, overview.height() - 3 - h);
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
     * Keeps the overview hidden while Export ▸ PNG takes its snapshot: the snapshot's layout pass paints the map,
     * and a paint shows the overview again (Map review 4 D2). Releasing it paints the overview back.
     */
    void canvasHoldOverview(boolean held) {
        canvasOverviewHeld = held;
        if (held) {
            overviewCanvas.setVisible(false);
        } else {
            requestViewportRepaint();
        }
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
     * An elided label from {@link #elidedTitles}, computed and stored when missing (Map review 4 D5). Not
     * {@code computeIfAbsent}: an affix cut falls back to {@link #elideMiddle}, which stores into the same map, and
     * a nested {@code computeIfAbsent} on one {@code HashMap} throws {@code ConcurrentModificationException} mid-paint.
     */
    private String canvasElided(String key, java.util.function.Supplier<String> compute) {
        String known = elidedTitles.get(key);
        if (known != null) {
            return known;
        }
        String value = compute.get();
        if (elidedTitles.size() > 256) {
            elidedTitles.clear(); // a zoom gesture asks for a new width on every step
        }
        elidedTitles.put(key, value);
        return value;
    }

    /**
     * {@code text} cut to fit {@code room} at {@code fontSize} by taking characters out of its middle: a
     * long file name keeps its start and its extension, a chain its first and last folders.
     */
    private String elideMiddle(String text, double fontSize, double room) {
        String key = text + '\u0001' + (int) room + '\u0001' + (int) (fontSize * 8);
        return canvasElided(key, () -> {
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
        String key = text
                + '\u0002'
                + (int) room
                + '\u0002'
                + (int) (fontSize * 8)
                + '\u0002'
                + affixes[0]
                + ','
                + affixes[1];
        return canvasElided(key, () -> {
            // Below the length of its distinct part there is no such cut; above it, the longer the wider.
            int low = 0;
            while (low < text.length() && ProjectMapElision.distinctCut(text, affixes[0], affixes[1], low) == null) {
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
        String key = text + '\u0000' + (int) room + '\u0000' + (int) (fontSize * 8);
        return canvasElided(key, () -> {
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
        Color fill =
                focusedSelection ? accent : isSelected ? color(accentSubtleProbe, mix(surface, accent, 0.22)) : surface;
        int heat = activityHeatLevel(entry);
        if (heat > 0 && !isSelected) {
            fill = activityTinted(fill, heat);
        }
        int dependency = isSelected || entry.isSymbol() ? 0 : dependencyOverlay.mark(entry.path());
        Color dependencyInk = dependency == 0 ? null : dependencyInk(dependency);
        if (dependencyInk != null) {
            fill = mix(fill, dependencyInk, 0.16);
        }
        if (isHovered && !isSelected) {
            fill = mix(fill, accent, 0.16);
        }
        g.setFill(fill);
        g.fillRoundRect(box.x(), box.y(), box.width(), box.height(), 8 * zoom, 8 * zoom);
        g.setStroke(
                isSelected || isHovered || selectedPath ? accent : dependencyInk != null ? dependencyInk : rowBorder());
        g.setLineWidth((isSelected ? 1.8 : selectedPath || dependencyInk != null ? 1.35 : 1.0) * zoom);
        g.strokeRoundRect(box.x(), box.y(), box.width(), box.height(), 8 * zoom, 8 * zoom);
        if (focusedSelection) {
            // A ring outside the row, separated from it by a sliver of the card.
            g.setStroke(color(focusRingProbe, accent));
            g.setLineWidth(2);
            g.strokeRoundRect(box.x() - 3, box.y() - 3, box.width() + 6, box.height() + 6, 8 * zoom + 6, 8 * zoom + 6);
        }
        g.setGlobalAlpha(dimmed ? DIMMED_MARK_ALPHA : 1.0);
        char review = entry.isSymbol() ? 0 : reviewMark(entry.path());
        if (review != 0) {
            drawReviewMark(g, box, review);
        }
        activityDrawHeatMeter(g, box, heat, focusedSelection, fill);
        drawOpenMarker(g, entry, content, focusedSelection);

        double iconX = content.x() + 7 * zoom;
        double iconY = content.y() + 6 * zoom;
        drawIcon(g, entry, iconX, iconY, focusedSelection);
        ProjectMapProblems.Count problemCount =
                entry.isSymbol() ? ProjectMapProblems.Count.ZERO : problems.of(entry.path());
        if (problemCount.any()) {
            drawProblemMark(g, problemCount, iconX, iconY, fill);
        }
        if (entry.isSymbol()) {
            drawSymbolRow(g, entry, content, focusedSelection, isSelected);
            g.setGlobalAlpha(1);
            dimmedGround = null;
            return;
        }
        Color ink = focusedSelection
                ? onAccent()
                : problemCount.errors() > 0
                        ? color(
                                dangerProbe,
                                Color.web("#f85149")) // as the editor's explorer: a name with errors is red
                        : problemCount.warnings() > 0
                                ? color(warningProbe, Color.web("#d29922"))
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
        // How many times it is used, before its line, as the editor's code lens says it (gap 11).
        Integer count = symbol == null ? null : view.usages.count(symbol.file(), symbol.line());
        double usesWidth = 0;
        if (count != null) {
            String uses = MapUsageCounts.text(count);
            usesWidth = (uses.length() * USES_CHAR_WIDTH + 6) * zoom;
            g.fillText(uses, right - SYMBOL_LINE_WIDTH * zoom, box.y() + 20 * zoom);
        }
        g.setTextAlign(TextAlignment.LEFT);
        double labelLeft = box.x() + 31 * zoom;
        double room = right - SYMBOL_LINE_WIDTH * zoom - usesWidth - labelLeft;
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
        boolean open = view.structure.isOpen(entry.path());
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
    boolean hasStructure(ProjectMapModel.Entry entry) {
        return view.structure.supports(entry);
    }

    /** A folder, or a declaration that holds others: its row ends in a chevron. */
    boolean branches(ProjectMapModel.Entry entry) {
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
        int dependency = isSelected || match ? 0 : dependencyOverlay.mark(entry.path());
        g.setGlobalAlpha(
                isSelected ? 1 : match || dependency != 0 ? 0.9 : faded ? 0.16 : entry.isPlaceholder() ? 0.2 : 0.42);
        g.setFill(
                isSelected || match
                        ? accent
                        : dependency != 0
                                ? dependencyInk(dependency)
                                : openPaths.contains(entry.path()) ? mix(muted, accent, 0.5) : muted);
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
        boolean hot = hoveredAffordance == Affordance.CHEVRON && entry.path().equals(hovered) && !outputPaint;
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

    /** The eye at the trailing end of a file row: it opens the floating preview, or closes it, and says so on hover. */
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
        g.fillRoundRect(box.x() + 2 * zoom, box.y() + 7 * zoom, 3 * zoom, box.height() - 14 * zoom, 3 * zoom, 3 * zoom);
    }

    /**
     * A row's problems: a dot on its icon's lower corner, red with an error, amber with only warnings, ringed in the
     * row's {@code ground} so it stands off the icon. The tooltip says how many.
     */
    private void drawProblemMark(
            GraphicsContext g, ProjectMapProblems.Count count, double iconX, double iconY, Color ground) {
        double r = 3.6 * zoom;
        double cx = iconX + 15 * zoom;
        double cy = iconY + 15 * zoom;
        g.setFill(ground);
        g.fillOval(cx - r - 1.2 * zoom, cy - r - 1.2 * zoom, 2 * (r + 1.2 * zoom), 2 * (r + 1.2 * zoom));
        g.setFill(
                count.errors() > 0
                        ? color(dangerProbe, Color.web("#f85149"))
                        : color(warningProbe, Color.web("#d29922")));
        g.fillOval(cx - r, cy - r, 2 * r, 2 * r);
    }

    /** The errors and warnings the rows show (the Problems window's), and the Problems chip filters by. */
    void setProblems(ProjectMapProblems found) {
        problems = found;
        if (filters != null && filters.problems()) {
            recomputeEmphasis();
        }
        repaint();
    }

    ProjectMapProblems problems() {
        return problems;
    }

    /** The rows the selection uses and that use it, tinted: violet, olive, or amber for both (round 2). */
    void setDependencyOverlay(MapDependencyOverlay overlay) {
        if (overlay != dependencyOverlay) {
            dependencyOverlay = overlay;
            repaint();
        }
    }

    MapDependencyOverlay dependencyOverlay() {
        return dependencyOverlay;
    }

    /**
     * The files the Changes card's comparison with a branch changes (round 3, item 3), by path with their status
     * letters: each of their rows, and the rows of the folders holding them, get a stripe at their left edge — green
     * for an added file, red for a removed one, the accent for the rest. Empty takes the stripes away.
     */
    void setReviewChanges(Map<Path, Character> files) {
        Map<Path, Character> given = Map.copyOf(files);
        if (given.equals(reviewFiles)) {
            return;
        }
        Set<Path> folders = new HashSet<>();
        for (Path file : given.keySet()) {
            for (Path parent = file.getParent(); parent != null && folders.add(parent); parent = parent.getParent()) {
                // every folder above it, once
            }
        }
        reviewFiles = given;
        reviewFolders = Set.copyOf(folders);
        repaint();
    }

    /** The status letter of the branch's change to {@code path}: its own, {@code '*'} for a folder holding some, or 0. */
    char reviewMark(Path path) {
        Character own = reviewFiles.get(path);
        return own != null ? own : reviewFolders.contains(path) ? '*' : 0;
    }

    private void drawReviewMark(GraphicsContext g, NodeBox box, char mark) {
        g.setFill(
                switch (mark) {
                    case 'A', 'U' -> color(successProbe, Color.web("#3fb950"));
                    case 'D' -> color(dangerProbe, Color.web("#f85149"));
                    case '*' -> color(mutedProbe, Color.web("#8b949e"));
                    default -> color(accentProbe, Color.web("#388bfd"));
                });
        double inset = 5 * zoom;
        g.fillRoundRect(box.x() + 1.5 * zoom, box.y() + inset, 3 * zoom, box.height() - 2 * inset, 3 * zoom, 3 * zoom);
    }

    /**
     * The ink of a dependency mark: violet for a row the selection uses, olive for one that uses it, and the
     * Dependencies card's amber for one on both sides.
     */
    private Color dependencyInk(int mark) {
        return switch (mark) {
            case MapDependencyOverlay.USES -> color(violetProbe, Color.web("#b392f0"));
            case MapDependencyOverlay.USED_BY -> color(oliveProbe, Color.web("#a9b84a"));
            default -> color(warningProbe, Color.web("#d29922"));
        };
    }

    private void drawIcon(GraphicsContext g, ProjectMapModel.Entry entry, double x, double y, boolean onAccentFill) {
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
                cached = rasterize(StructureIcons.forKind(symbol.kind()), "project-map-symbol-icon", key.statusClass());
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
    void warmIcons() {
        iconWarmup.clear();
        iconWarmup.add(new IconKey(FOLDER_ICON, ""));
        iconWarmup.add(new IconKey(FOLDER_ICON, "git-status-dir-changed"));
        for (String glyph : FileIcons.iconKeys()) {
            iconWarmup.add(new IconKey(glyph, ""));
        }
        iconWarmupTimer.start();
    }

    private void warmSomeIcons() {
        if (!view.active || getScene() == null || outputPaint) {
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
                FileIcons.forProjectItem(fileName, directory), directory ? "folder-cell" : "file-cell", statusClass);
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
    private void drawStatusMarks(GraphicsContext g, ProjectMapModel.Entry entry, NodeBox box, boolean onAccentFill) {
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
    private void drawFileMarkers(GraphicsContext g, ProjectMapModel.Entry entry, NodeBox box, boolean selectedNode) {
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
            case PREVIEW ->
                !entry.isFile()
                        ? null
                        : tr(
                                view.hasFileCard(entry.path())
                                        ? "project.map.node.closePreview"
                                        : "project.map.node.preview",
                                entry.name());
            case STRUCTURE ->
                tr(
                        view.structure.isOpen(entry.path())
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
        if (panFromRow && !dragMoved && Math.hypot(event.getX() - pressX, event.getY() - pressY) < ROW_PAN_THRESHOLD) {
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
            } else {
                canvasSyncLocks(); // the lock comes and goes with the pointer in every mode (position 17)
            }
        }
    }

    /**
     * Whether a column's lock shows (Map explore, position 17): only while the column is hovered, one of its
     * controls has the focus, or it is locked; the rest of the time the header shows its filter alone. The
     * lock keeps its place, so nothing moves when it appears.
     */
    private boolean canvasLockShown(ProjectMapModel.ColumnId id, ColumnControls controls) {
        ColumnLayout layout = columnLayouts.get(id);
        return layout != null && layout.locked
                || id.equals(layoutHoverColumn)
                || controls.filter().isFocused()
                || controls.showHidden().isFocused()
                || controls.pin().isFocused();
    }

    /** Shows or hides each laid-out column's lock for the hover and focus now, without laying anything out. */
    private void canvasSyncLocks() {
        for (ColumnBox box : columnBoxes) {
            ColumnControls controls = columnControls.get(box.column().id());
            if (controls == null || !controls.filter().isVisible()) {
                continue; // no detail controls on this column now: no lock either
            }
            boolean shown = canvasLockShown(box.column().id(), controls);
            if (controls.pin().isVisible() != shown) {
                controls.pin().setVisible(shown);
                syncControlTraversal(controls);
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
        Path base = view.root;
        // A folder says where it is in the project and what it holds (review 3 B4); a file keeps its full path.
        lines.add(
                entry.directory()
                                && base != null
                                && entry.path().startsWith(base)
                                && !entry.path().equals(base)
                        ? layoutRelative(base, entry.path()) + "/"
                        : entry.path().toString());
        entry = canvasWithAttributes(entry);
        String type = entry.symbolicLink()
                ? tr("project.map.tooltip.symbolicLink")
                : tr(entry.directory() ? "project.map.tooltip.folder" : "project.map.tooltip.file");
        lines.add(tr("project.map.tooltip.type", type));
        if (!entry.directory() && entry.size() >= 0) {
            lines.add(tr("project.map.tooltip.size", formatSize(entry.size())));
        }
        if (entry.modifiedMillis() >= 0) {
            lines.add(tr(
                    "project.map.tooltip.modified", TOOLTIP_TIME.format(Instant.ofEpochMilli(entry.modifiedMillis()))));
        }
        layoutTooltipLines(entry, lines);
        activityTooltipLines(entry, lines);

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
        ProjectMapProblems.Count count = problems.of(entry.path());
        if (count.any()) {
            statuses.add(tr("project.map.tooltip.problems", count.errors(), count.warnings()));
        }
        switch (entry.path().equals(selected) ? 0 : dependencyOverlay.mark(entry.path())) {
            case MapDependencyOverlay.USES -> statuses.add(tr("project.map.tooltip.depUsed"));
            case MapDependencyOverlay.USED_BY -> statuses.add(tr("project.map.tooltip.depUses"));
            case MapDependencyOverlay.BOTH -> statuses.add(tr("project.map.tooltip.depBoth"));
            default -> {}
        }
        char review = reviewMark(entry.path());
        if (review == '*') {
            statuses.add(tr("project.map.tooltip.reviewHolds"));
        } else if (review != 0) {
            statuses.add(tr("project.map.tooltip.reviewChanged"));
        }
        if (!statuses.isEmpty()) {
            lines.add(tr("project.map.tooltip.status", String.join(", ", statuses)));
        }
        return String.join("\n", lines);
    }

    /** What review 3 adds to a row's tooltip: a folder's counts and build files, why a row is dimmed. */
    private void layoutTooltipLines(ProjectMapModel.Entry entry, List<String> lines) {
        Path base = view.root;
        ProjectMapOverview.Facts facts = view.orientation.facts();
        if (entry.directory() && base != null && facts != null && entry.path().startsWith(base)) {
            String rel = layoutRelative(base, entry.path());
            int[] counts = facts.folderCounts().get(rel);
            if (counts != null) {
                java.text.NumberFormat number = java.text.NumberFormat.getIntegerInstance(FileHistoryPanel.locale());
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

    /** How hot a row is under "Show recent changes", 0 to {@link ProjectMapActivity#LEVELS} (0 when it is off). */
    private int activityHeatLevel(ProjectMapModel.Entry entry) {
        return entry.isPlaceholder() || entry.isSymbol() ? 0 : view.activity.heatLevel(entry.path(), entry.directory());
    }

    /**
     * A hot row's heat meter along its foot, longer the hotter it is: the heat stays visible where the row's fill is
     * the selection's, and it keeps clear of the open file's bar at the leading edge (Map explore position 3). In the
     * on-accent ink on the focused selection's accent fill.
     */
    private void activityDrawHeatMeter(GraphicsContext g, NodeBox box, int heat, boolean onAccentFill, Color ground) {
        if (heat <= 0) {
            return;
        }
        double inset = 8 * zoom;
        double length = (box.width() - inset * 2) * heat / ProjectMapActivity.LEVELS;
        double thickness = Math.max(2, 2.5 * zoom);
        Color heatInk = color(activityHeatProbe, Color.web("#d29922"));
        Color text = color(textProbe, Color.web("#d8dee9"));
        g.setFill(
                onAccentFill
                        ? onAccent()
                        : activityMeterInks.computeIfAbsent(
                                List.of(heatInk, ground, text),
                                key -> ProjectMapActivity.meterInk(heatInk, ground, text)));
        g.fillRoundRect(box.x() + inset, box.y() + box.height() - 4 * zoom, length, thickness, thickness, thickness);
    }

    /** A row's fill at a heat step, kept readable ({@link ProjectMapActivity#tinted}); cached by its inputs. */
    private Color activityTinted(Color fill, int heat) {
        Color heatInk = color(activityHeatProbe, Color.web("#d29922"));
        Color text = color(textProbe, Color.web("#d8dee9"));
        Color muted = color(mutedProbe, Color.web("#8b949e"));
        return activityTints.computeIfAbsent(
                List.of(fill, heatInk, text, muted, heat),
                key -> ProjectMapActivity.tinted(fill, heatInk, heat, text, muted));
    }

    /** What Map explore adds to a row's tooltip: its commits of the last 90 days, and who owns it. */
    private void activityTooltipLines(ProjectMapModel.Entry entry, List<String> lines) {
        if (entry.isPlaceholder() || entry.isSymbol()) {
            return;
        }
        com.editora.git.GitRecentChanges.Stat stat = view.activity.stat(entry.path(), entry.directory());
        if (stat != null) {
            com.editora.git.GitRecentChanges.Result read = view.activity.changes();
            lines.add(tr(
                    read.truncated() ? "project.map.activity.commitsNewest" : "project.map.activity.commits",
                    stat.commits(),
                    stat.lastAuthor(),
                    GitBlameCoordinator.relativeTimeLabel(stat.lastEpochSeconds(), System.currentTimeMillis() / 1000),
                    read.commits())); // a capped read is the newest N commits, not 90 days (Map review 4 B11)
        }
        ProjectMapOwners.Owners owners = view.activity.owners(entry.path());
        if (owners != null) {
            lines.addAll(ProjectMapActivity.ownerLines(owners));
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

    /**
     * A row of a long folder listed by name only (position 15) carries no size or time: the tooltip reads them
     * for the one row it describes, from a local disk only. Any other row is returned as it is.
     */
    private static ProjectMapModel.Entry canvasWithAttributes(ProjectMapModel.Entry entry) {
        if (entry.directory()
                || entry.isPlaceholder()
                || entry.isSymbol()
                || entry.size() >= 0
                || entry.modifiedMillis() >= 0
                || !com.editora.vfs.Vfs.isLocal(entry.path())) {
            return entry;
        }
        try {
            java.nio.file.attribute.BasicFileAttributes read = java.nio.file.Files.readAttributes(
                    entry.path(),
                    java.nio.file.attribute.BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
            return new ProjectMapModel.Entry(
                    entry.path(),
                    entry.parent(),
                    entry.depth(),
                    read.isDirectory(),
                    read.size(),
                    read.lastModifiedTime().toMillis(),
                    read.isSymbolicLink());
        } catch (java.io.IOException | RuntimeException gone) {
            return entry;
        }
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
            view.escapePressed();
            event.consume();
            return;
        }
        if (event.getCode() == KeyCode.F3 && plain) {
            // F3 / Shift+F3 step through a search's matches (B1); claimed, so consumed either way.
            view.resultsStep(event.isShiftDown() ? -1 : 1);
            event.consume();
            return;
        }
        boolean onlyAlt = event.isAltDown() && !event.isControlDown() && !event.isMetaDown() && !event.isShiftDown();
        if (onlyAlt && (event.getCode() == KeyCode.LEFT || event.getCode() == KeyCode.RIGHT)) {
            view.moveHistory(event.getCode() == KeyCode.LEFT ? -1 : 1);
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
            case RENAME -> view.onRenameEntry.accept(entry);
            case DELETE -> view.onDeleteEntry.accept(entry);
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

    private void focusOwnerChanged(javafx.beans.value.ObservableValue<? extends Node> property, Node old, Node owner) {
        claimKeys(owner == this);
        canvasSyncLocks(); // a column whose control took or lost the focus shows or hides its lock
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

    void moveSibling(int delta) {
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
    private int selectionStepFromHidden(ProjectMapModel.Entry current, List<ProjectMapModel.Entry> drawn, int delta) {
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
        if (parent == null || parent.equals(view.root)) {
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

    java.util.Optional<ProjectMapModel.Entry> selectedEntry() {
        return java.util.Optional.ofNullable(selected == null ? null : entryByPath.get(selected));
    }

    void select(Path path) {
        selected = path;
        revealSelected();
        repaint();
        updateAccessibleText();
        onSelectionChanged.accept(path);
    }

    /** Activates the selection — unless a column filter has hidden its row: nobody opens what is not shown. */
    void activateSelection() {
        flushPendingRepaint();
        selectedEntry().filter(entry -> nodeBox(entry.path()) != null).ifPresent(onActivate);
    }

    /** Whether the selection lies strictly below {@code directory} (in the branch a column close removes). */
    boolean selectionBelow(Path directory) {
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
            view.onStatus.accept(tr("project.map.status.rootColumnNoFilter"));
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
            view.onStatus.accept(tr("project.map.status.columnFilterCannotShow"));
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
        double mapX = clampInto((x - overview.x() - overview.insetX()) / overview.scaleX(), 0, overview.contentWidth());
        double mapY =
                clampInto((y - overview.y() - overview.insetY()) / overview.scaleY(), 0, overview.contentHeight());
        offsetX += getWidth() / 2 - (overview.minX() + mapX);
        offsetY += getHeight() / 2 - (overview.minY() + mapY);
        repaint();
        return true;
    }

    boolean contains(Path path) {
        return path != null && entryByPath.containsKey(path);
    }

    /**
     * The zoom bar and the overview as drawn, {@code {x, y, w, h, weight}}: cards and their Structure columns keep
     * clear of them (review 3, E11).
     */
    List<double[]> cardChrome() {
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

    /**
     * The project's own column — the first the map shows, listing the root — as drawn, {@code {x, y, w, h}} in panel
     * pixels, on screen or not; null when there is none. The Overview card opens before it, the way the map flows.
     */
    double @Nullable [] cardRootColumn() {
        ColumnBox root = columnOpenedBy(view.root);
        return root == null ? null : new double[] {root.x(), root.y(), root.width(), root.height()};
    }

    /** Every column as drawn, on screen or off, {@code {x, y, w, h}}: what a new card steps past (MapCards). */
    List<double[]> cardAllColumns() {
        List<double[]> columns = new ArrayList<>(columnBoxes.size());
        for (ColumnBox column : columnBoxes) {
            columns.add(new double[] {column.x(), column.y(), column.width(), column.height()});
        }
        return columns;
    }

    /** Every column on screen as {@code {x, y, w, h, 1}}, for a card's Structure column choosing its side. */
    List<double[]> cardColumns() {
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
    MapCards.Site cardSite(Path path) {
        NodeBox node = nodeBox(path);
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
        boolean forward = flowDirection == FlowDirection.LEFT_TO_RIGHT || flowDirection == FlowDirection.TOP_TO_BOTTOM;
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
                new double[] {left, Math.max(0, top), right - left, Math.min(getHeight(), bottom) - Math.max(0, top)},
                obstacles);
    }

    private double screenX(double worldX) {
        return offsetX + worldX * zoom;
    }

    private double screenY(double worldY) {
        return offsetY + worldY * zoom;
    }

    /**
     * Hands the rows drawn in the viewport to {@link #canvasA11y}, as tree items named like the selection's
     * summary. Columns off screen are skipped whole and only on-screen rows get a node, so the cost follows the
     * screen, not the map (1,200 rows or a 50,000-row folder alike). The selected row is the focus item.
     */
    private void canvasA11yRebuild() {
        canvasA11yStale = false;
        List<MapAccessibleRows.Spec> specs = new ArrayList<>();
        double width = getWidth();
        double height = getHeight();
        int next = 0;
        if (!outputPaint && !boxes.isEmpty()) {
            for (ColumnBox column : columnBoxes) {
                List<ProjectMapModel.Entry> rows = column.column().entries();
                int first = next;
                next += rows.size();
                if (column.x() > width
                        || column.y() > height
                        || column.x() + column.width() < 0
                        || column.y() + column.height() < 0
                        || next > boxes.size()) {
                    continue;
                }
                int count = 0;
                for (ProjectMapModel.Entry row : rows) {
                    if (!row.isPlaceholder()) {
                        count++;
                    }
                }
                int position = 0;
                for (int index = first; index < next; index++) {
                    NodeBox box = boxes.get(index);
                    ProjectMapModel.Entry entry = box.entry();
                    if (entry.isPlaceholder()) {
                        continue;
                    }
                    position++;
                    if (!inViewport(box, width, height)) {
                        continue;
                    }
                    Path path = entry.path();
                    boolean folder = entry.directory() || entry.isSymbol() && branches(entry);
                    boolean opens = folder || entry.isFile() && hasStructure(entry);
                    boolean expanded =
                            entry.directory() ? expandedSnapshot.contains(path) : opens && view.structure.isOpen(path);
                    specs.add(new MapAccessibleRows.Spec(
                            path,
                            entry.parent(),
                            canvasRowText(entry, position, count, false, true),
                            Math.max(0, entry.depth() - 1),
                            path.equals(selected),
                            expanded,
                            !opens,
                            box.x(),
                            box.y(),
                            box.width(),
                            box.height()));
                }
            }
        }
        MapAccessibleRows.Row focus = canvasA11y.update(specs);
        if (focus != canvasA11yFocus) {
            canvasA11yFocus = focus;
            notifyAccessibleAttributeChanged(AccessibleAttribute.FOCUS_ITEM);
        }
    }

    private MapAccessibleRows.@Nullable Row canvasA11yFocus;

    /** A paint changed what is on screen: the rows are handed over now if a screen reader listens, else on ask. */
    private void canvasA11yPainted() {
        canvasA11yStale = true;
        if (Platform.isAccessibilityActive()) {
            canvasA11yRebuild();
        }
    }

    /** The accessible rows as a reader would get them now (tests). */
    MapAccessibleRows canvasAccessibleRows() {
        if (canvasA11yStale) {
            canvasA11yRebuild();
        }
        return canvasA11y;
    }

    /** A tree item's Expand / Collapse: opens or closes the folder or Structure column the row opens. */
    private void canvasExpand(Path path, boolean expand) {
        ProjectMapModel.Entry entry = entryByPath.get(path);
        if (entry == null || entry.isPlaceholder()) {
            return;
        }
        boolean open = entry.directory() ? expandedSnapshot.contains(path) : view.structure.isOpen(path);
        if (open == expand) {
            return;
        }
        select(path);
        if (entry.directory()) {
            onActivate.accept(entry); // a folder row's click opens or closes its column
        } else if (entry.isFile() && hasStructure(entry) || entry.isSymbol() && branches(entry)) {
            onStructure.accept(entry);
        }
    }

    @Override
    public @Nullable Object queryAccessibleAttribute(AccessibleAttribute attribute, Object... parameters) {
        switch (attribute) {
            case CHILDREN, FOCUS_ITEM -> {
                if (canvasA11yStale) {
                    canvasA11yRebuild(); // asked before the next pulse would hand them over
                }
                if (attribute == AccessibleAttribute.FOCUS_ITEM) {
                    MapAccessibleRows.Row row = canvasA11y.row(selected);
                    return row != null ? row : this;
                }
                return super.queryAccessibleAttribute(attribute, parameters);
            }
            default -> {
                return super.queryAccessibleAttribute(attribute, parameters);
            }
        }
    }

    void updateAccessibleText() {
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
        List<ProjectMapModel.Entry> drawn = null;
        if (!outputPaint) {
            // The columns the layout draws (cached, and laid out for the paint anyway): not columnsById over every row
            // again, which sorts a whole folder's 50,000 rows on each selection change (Map review 4 D3).
            for (ColumnBox box : worldLayout().columns()) {
                if (box.column().id().equals(id)) {
                    drawn = canvasRealRows(box.column());
                    break;
                }
            }
        }
        if (drawn == null) { // the root's own row, which the layout draws as the first column's title
            drawn = List.of();
            for (ProjectMapModel.Column column : ProjectMapModel.columnsById(
                    entries, columnQueries, columnShowHidden, narrowing ? emphasized : null)) {
                if (column.id().equals(id)) {
                    drawn = canvasRealRows(column);
                    break;
                }
            }
        }
        int index = drawn.indexOf(current);
        int position;
        boolean filtered = index < 0;
        if (!filtered) {
            position = index + 1;
        } else {
            // Hidden by its column's filter: say so, and where it would stand.
            List<ProjectMapModel.Entry> all = selectionSiblings(current);
            int at = all.indexOf(current);
            position = 1
                    + (int) drawn.stream().filter(row -> all.indexOf(row) < at).count();
        }
        return canvasRowText(current, position, drawn.size(), filtered, false);
    }

    private static List<ProjectMapModel.Entry> canvasRealRows(ProjectMapModel.Column column) {
        return column.entries().stream().filter(row -> !row.isPlaceholder()).toList();
    }

    /**
     * What a row says to a screen reader: its name, kind and place among its column's {@code count} rows, then
     * its states (unsaved, Git, bookmarked, notes, Structure open; {@code filtered}: a column filter hides it).
     * The surface's own summary of the selection and each row on screen (position 14) say it the same way.
     */
    private String canvasRowText(
            ProjectMapModel.Entry current, int position, int count, boolean filtered, boolean row) {
        List<String> states = new ArrayList<>();
        if (filtered) {
            states.add(tr("project.map.accessible.state.filtered"));
        }
        ProjectMapModel.Symbol symbol = current.symbol();
        String base;
        Integer uses = symbol == null ? null : view.usages.count(symbol.file(), symbol.line());
        if (uses != null) {
            states.add(MapUsageCounts.text(uses)); // as its row says it (gap 11)
        }
        if (symbol != null) {
            base = tr(
                    row ? "project.map.accessible.row.symbol" : "project.map.accessible.symbol",
                    current.name(),
                    symbol.kind() == null ? "" : symbol.kind(),
                    symbol.line() + 1,
                    position,
                    count);
        } else {
            String key;
            if (current.directory()) {
                boolean expanded = expandedSnapshot.contains(current.path());
                key = row
                        ? expanded
                                ? "project.map.accessible.row.folderExpanded"
                                : "project.map.accessible.row.folderCollapsed"
                        : expanded ? "project.map.accessible.folderExpanded" : "project.map.accessible.folderCollapsed";
            } else {
                boolean open = openPaths.contains(current.path());
                key = row
                        ? open ? "project.map.accessible.row.fileOpen" : "project.map.accessible.row.file"
                        : open ? "project.map.accessible.fileOpen" : "project.map.accessible.file";
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
            if (current.isFile() && view.structure.isOpen(path)) {
                states.add(tr("project.map.accessible.state.structureOpen"));
            }
        }
        // A row in the tree of rows is in its column's folder already: only the selection's summary names it.
        String folder = row ? "" : view.resultsAccessibleFolder(current.path());
        if (!folder.isEmpty()) {
            base = tr("project.map.accessible.inFolder", base, folder); // which of four proxier.go (B11)
        }
        String match = view.resultsAccessibleSuffix(current.path());
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

    Color accentColor() {
        return color(accentProbe, Color.web("#58a6ff"));
    }

    /** Ink for a Personal Notes card's connector: the theme's note (amber) colour. */
    Color noteColor() {
        return color(warningProbe, Color.web("#b08a00"));
    }

    /**
     * The row box of {@code path} in the last projection, or null. A lookup in the layout's index, not a scan:
     * the paint asks for it per column header and per flash, and a whole folder lays out tens of thousands of
     * rows (Map review 4 D3). {@link #boxes} is projected from {@link #projected}'s rows in their order.
     */
    @Nullable
    NodeBox nodeBox(@Nullable Path path) {
        WorldLayout world = projected;
        Integer index = path == null || world == null ? null : world.firstRows().get(path);
        return index == null || index >= boxes.size() ? null : boxes.get(index);
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

    record NodeBox(ProjectMapModel.Entry entry, double x, double y, double width, double height) {}

    /**
     * Columns and rows in world units, in drawing order. {@code anchorRows} and {@code anchorColumns} give,
     * per column, the index of the row it hangs from and of that row's column (-1 for the first column).
     */
    private record WorldLayout(
            List<ColumnBox> columns,
            List<NodeBox> rows,
            List<Integer> anchorRows,
            List<Integer> anchorColumns,
            int narrowed,
            Map<Path, Integer> firstRows) {}

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
