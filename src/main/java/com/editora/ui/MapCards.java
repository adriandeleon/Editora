package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ContextMenu;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import com.editora.config.PersonalNote;
import com.editora.text.FoldRegions;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The cards that float over a Project Map (review 3, stage 1 of the refactor plan): code cards
 * ({@link ProjectMapPreview}, a file's or a declaration's), note cards ({@link ProjectMapNotePreview}) and what is stuck
 * on a code card — its sticky notes ({@link ProjectMapStickyNotes}) and its Structure column
 * ({@link ProjectMapCardStructure}). It owns their lifecycle (open, use, eviction under the shared limit, close), their
 * placement over the map, the connector canvas that ties each to its row, and bringing them up to date (a debounced
 * refresh, the once-a-second poll of a card that mirrors an open buffer, a rate-limited re-read after disk changes).
 *
 * <p>What it needs of the map under the cards comes through {@link Host}, which {@link ProjectMapView} implements over
 * its surface. Cards are keyed by path: a file's, or a declaration row's made-up path ({@link
 * ProjectMapStructure#isSymbolPath}). A card's corner is in the map area's pixels and its size is unzoomed; it is drawn at
 * the map's zoom ({@link #moveWithViewport}).
 */
final class MapCards {

    /** Code and note cards together: one bound on what floats over the map, however the eight are split. */
    static final int MAX_OPEN_PREVIEWS = 8;

    private static final double PREVIEW_CASCADE = 28;
    /** Quiet time after a state or filesystem notification before open cards are brought up to date. */
    private static final Duration CARD_REFRESH_DELAY = Duration.millis(250);
    /** How often a card that mirrors an open buffer looks for edits; nothing else announces a keystroke. */
    static final Duration CARD_POLL_INTERVAL = Duration.seconds(1);
    /** The gap between a column and a card placed beside it (the surface's own gap between columns). */
    private static final double COLUMN_GAP = 18;

    /** What the cards need of the map under them, in the map area's pixels. */
    interface Host {
        /** The row of {@code path} as drawn, {@code {x, y, w, h}}, or null when it is not on the map. */
        double @Nullable [] rowBox(Path path);

        /** What placement needs to know about the row of {@code path} (see {@link Site}). */
        Site site(Path path);

        /**
         * The zoom bar and the overview as drawn, {@code {x, y, w, h, weight}} at {@link #CHROME_WEIGHT}: what floats
         * over the map and must not be covered by a card or what is stuck on one.
         */
        List<double[]> chrome();

        /** Every column on screen as {@code {x, y, w, h, 1}}: what a card's Structure column weighs its side by. */
        List<double[]> columns();

        /**
         * The project's own column, the map's first, as drawn {@code {x, y, w, h}} (on screen or not), or null: the
         * Overview card opens before it.
         */
        default double @Nullable [] rootColumn() {
            return null;
        }

        /**
         * Every column as drawn, on screen or off, {@code {x, y, w, h}}: what a new card opening the way a column does
         * steps past ({@link #focusNewCards}).
         */
        default List<double[]> allColumns() {
            return columns();
        }

        /**
         * Whether a new card opens as a new column does ("Focus new column"): past its row's column the way the map
         * flows, the map panned to centre it. Off, it takes the free room in view and the map stays put.
         */
        default boolean focusNewCards() {
            return false;
        }

        /** The map's zoom the cards are drawn at. */
        double zoom();

        /** Whether the map flows across (left to right or back): connectors and cards then sit beside a row. */
        boolean horizontalFlow();

        /**
         * Whether the map flows backwards (right to left, or bottom to top): a row's next column, and a new card, open
         * on the left of it or above it.
         */
        default boolean reverseFlow() {
            return false;
        }

        /** Whether "Hide unmatched" took {@code file}'s row off the map: its cards are hidden with it. */
        boolean narrowsAway(Path file);

        /** The folders the map shows open now: an identity, replaced by every reload (the orphan check). */
        Set<Path> expandedSnapshot();

        @Nullable
        Path root();

        Color accent();

        Color note();

        /** Puts the keyboard on the map. */
        void focusMap();

        /** Whether the map is on screen: hidden, nothing is refreshed or polled. */
        boolean onScreen();

        /** Says something in the Map's status line. */
        void status(String message);

        /** The editor's view of {@code file} (an open buffer's text), or null when it knows nothing. */
        ProjectMapPreview.@Nullable Content content(Path file);

        boolean isOpen(Path file);

        boolean isModified(Path file);

        /** A declaration row's menu, as the Map's own Structure column gives it, with {@code preview} as its Preview. */
        ContextMenu symbolMenu(ProjectMapModel.Entry entry, Runnable preview);

        /** The options menu's "Hide all open Personal Notes". */
        boolean hideOpenNotes();

        /** Turns "Hide all open Personal Notes" off (a card's Notes button asked for its notes). */
        void showOpenNotes();

        void openFile(Path file);

        void openAt(Path file, int line);

        void openNarrowed(Path file, FoldRegions.Region block);

        /** Pans the map (and the cards on it) by {@code dx, dy} pixels. */
        void panBy(double dx, double dy);

        /**
         * Selects the declaration at {@code declaration} in the Map's own Structure column of {@code file}, opening the
         * columns that lead to it (Map explore round, position 8). False when the file's row is not on the map.
         */
        default boolean showInStructure(Path file, Path declaration) {
            return false;
        }

        /** Views (trails): what a code card's menu adds at its end, built each time it opens. */
        default List<javafx.scene.control.MenuItem> viewsCardMenu(ProjectMapPreview card) {
            return List.of();
        }

        /**
         * The code was followed from card {@code from}, its caret at {@code fromCaret} before the step, to card {@code
         * to} (or a place further along {@code from}): both are stops of the Map's Back/Forward history.
         */
        default void followed(ProjectMapPreview from, int fromCaret, ProjectMapPreview to) {}

        /** Back ({@code back}) or Forward typed in {@code card}, along the Map's history; false with nowhere to go. */
        default boolean historyStep(ProjectMapPreview card, boolean back) {
            return false;
        }

        /** {@code card} closed: its stops in the Map's history become their places. */
        default void cardClosed(ProjectMapPreview card) {}

        /** A file card's Dependencies: the Map's Dependencies card opens on its file, beside it (gap 14). */
        default void showDependencies(ProjectMapPreview card, Path file) {}
    }

    /**
     * An open card, for the lists of them (the options menu's Open cards, the overview's marks): its key, the card, a
     * name for it and whether it is a note card.
     */
    record OpenCard(Path key, Region node, String label, boolean note) {
        /** The card as drawn now, {@code {x, y, w, h}} in the map area's pixels. */
        double[] bounds() {
            return drawnBounds(node);
        }
    }

    /**
     * What card placement needs to know about the row at a path, in the map area's pixels (see {@code
     * MapSurface.cardSite}): the row and its column as {@code {x, y, w, h}}, the extent of the columns on screen, and
     * what a card should not cover as {@code {x, y, w, h, weight}}. {@code row} is null when the row is not on the map.
     */
    record Site(double @Nullable [] row, double[] column, double[] band, List<double[]> obstacles) {}

    private final Host host;
    private final ProjectMapStructure structure;
    private final Pane canvasHost;
    /** Linked reference cards: the result cards a card's question opened, and the edges to them (explore 5). */
    final MapCardLinks links;

    final Canvas previewConnectorCanvas = new Canvas(1, 1);
    /** Each code card's connector as last drawn, by key. For tests. */
    final Map<Path, PreviewConnector> previewConnectors = new HashMap<>();

    final Map<Path, ProjectMapPreview> previews = new LinkedHashMap<>(16, 0.75f, true);
    final Map<Path, ProjectMapNotePreview> notePreviews = new LinkedHashMap<>(16, 0.75f, true);
    /** The sticky notes a code card's Notes button stuck on it, by card. */
    private final Map<ProjectMapPreview, ProjectMapStickyNotes> stickyNotes = new java.util.IdentityHashMap<>();
    /** The Structure columns stuck on file cards (their Structure button). */
    final Map<ProjectMapPreview, ProjectMapCardStructure> cardStructures = new java.util.IdentityHashMap<>();
    /** Views (trails): a trail stop's note, stuck on the stop's card while the trail plays there. */
    private final Map<ProjectMapPreview, CardAttachment> viewsAttachments = new java.util.IdentityHashMap<>();
    /** The Overview card, when open (review 3 B4): not counted against the limit and never evicted. */
    private @Nullable ProjectMapOverviewCard overviewCard;
    /** The declaration History card, when open (gap 12): one at a time, not counted against the limit. */
    private @Nullable ProjectMapHistoryCard historyCard;
    /** The Dependencies card, when open (gap 14): one at a time, not counted against the limit. */
    private @Nullable ProjectMapDependencyCard dependencyCard;
    /** Which of the editor's reading aids the code cards show: the editor's settings (round 2). */
    private java.util.function.Supplier<MapCardAids> cardAids = () -> MapCardAids.NONE;

    /** The Symbol Graph card, when open (round 2): one at a time, not counted against the limit. */
    private @Nullable ProjectMapSymbolGraphCard symbolGraphCard;
    /** The Code Metrics card, when open (gap 15): one at a time, not counted against the limit. */
    private @Nullable ProjectMapMetricsCard metricsCard;
    /** The working tree's changes (Map code browser round 2), when open. */
    private @Nullable ProjectMapChangesCard changesCard;
    /** Changes or Blame: what the last History card showed, and the next one opens with. */
    private ProjectMapHistoryCard.Mode historyMode = ProjectMapHistoryCard.Mode.CHANGES;
    /** When each open card was last used (pressed, focused, scrolled or opened); the oldest is evicted. */
    private final Map<Region, Long> cardTouches = new java.util.IdentityHashMap<>();
    /** The pinned code cards: never evicted (Map explore round, position 7). */
    private final Set<Region> pinnedCards = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * Declaration cards last asked for from a file card's Structure column: they stand beside that column and their
     * connector starts there, while one asked for from the Map's own Structure column follows that one (Map Structure
     * previews).
     */
    private final Set<Region> cardColumnCards = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /**
     * Cards opened by Go to Symbol and Go to File (Map code navigation): no row or column asked for them, so they stay
     * until their file's own folder is collapsed (see {@link #cardOrphaned}).
     */
    private final Set<ProjectMapPreview> pickedCards =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /** A card of the last session is being brought back: its Structure column opens without moving it (B6). */
    private boolean restoringCard;

    /** Which Structure column a declaration card was asked for from. */
    private enum From {
        MAP_COLUMN,
        CARD_COLUMN
    }
    /** The last Git request each card made: an older answer is dropped (position 9). */
    private final Map<ProjectMapPreview, Long> cardsGitRequests = new java.util.IdentityHashMap<>();

    private long cardsGitSequence;

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
    private boolean disposed;

    private ProjectMapPreview.@Nullable MarkerActions previewMarkerActions;
    /** Where the cards' gutters read breakpoints, bookmarks, ▶ and change bars (Map gaps round 3, item 1). */
    private MapCardGutter.@Nullable Source gutterSource;
    /** Where a card's Go to Definition, Find Usages and the rest go (Project Map review 3 C12). */
    private ProjectMapPreview.@Nullable FollowActions followActions;

    private ProjectPanel.@Nullable MarkerActions notePreviewActions;
    /** The text zoom of the card last zoomed with −/+: new cards open at it. */
    private double cardTextZoom = 1.0;
    /** The Overview card's last −/+ text zoom: the next Overview opens at it, as code cards do theirs. */
    private double overviewTextZoom = 1.0;
    /** The editor window whose editor font the cards use (see {@link ProjectMapPreview#EDITOR_FONT_KEY}). */
    private java.util.function.Supplier<javafx.stage.@Nullable Window> cardFontSource = () -> null;

    private javafx.stage.@Nullable Window cardFontWindow;
    /** Follows the editor window's font; weakly held by the window, so a closed view is not kept. */
    private final javafx.collections.MapChangeListener<Object, Object> cardFontListener = change -> {
        if (ProjectMapPreview.EDITOR_FONT_KEY.equals(change.getKey())
                && change.getValueAdded() instanceof javafx.scene.text.Font font) {
            previews.values().forEach(card -> card.setEditorFont(font));
        }
    };

    private final javafx.collections.WeakMapChangeListener<Object, Object> weakCardFontListener =
            new javafx.collections.WeakMapChangeListener<>(cardFontListener);

    /**
     * @param structure the Map's outlines, which declaration cards and Structure columns read
     * @param canvasHost the map area the cards are added to, over the map
     */
    MapCards(Host host, ProjectMapStructure structure, Pane canvasHost) {
        this.host = host;
        this.structure = structure;
        this.canvasHost = canvasHost;
        previewConnectorCanvas.setManaged(false);
        previewConnectorCanvas.setMouseTransparent(true);
        cardRefresh.setOnFinished(event -> refreshCards(false));
        cardDiskRefresh.setOnFinished(event -> refreshCards(false));
        cardPoll.setOnFinished(event -> refreshCards(true));
        links = new MapCardLinks(linksCards(), structure, canvasHost, previewConnectorCanvas);
    }

    /** What the linked reference cards need of the cards. */
    private MapCardLinks.Cards linksCards() {
        return new MapCardLinks.Cards() {
            @Override
            public @Nullable Path root() {
                return host.root();
            }

            @Override
            public @Nullable ProjectMapPreview openDeclaration(ProjectMapStructure.Node node) {
                return previewSymbol(symbolEntry(node), null);
            }

            @Override
            public @Nullable ProjectMapPreview openFile(Path file) {
                return previewFile(file.toAbsolutePath().normalize(), file);
            }

            @Override
            public boolean isOpen(ProjectMapPreview card) {
                return !disposed && cardTouches.containsKey(card);
            }

            @Override
            public @Nullable ProjectMapPreview card(Path key) {
                return previews.get(key);
            }

            @Override
            public void touch(ProjectMapPreview card) {
                touchCard(card, false);
            }

            @Override
            public void status(String message) {
                host.status(message);
            }

            @Override
            public void connectorsMoved() {
                MapCards.this.connectorsMoved();
            }

            @Override
            public Color accent() {
                return host.accent();
            }

            @Override
            public List<double[]> columns() {
                return host.columns();
            }

            @Override
            public void openAt(Path file, int line) {
                host.openAt(file, line);
            }

            @Override
            public void follow(ProjectMapPreview from, ProjectMapPreview to) {
                followed(from, to);
            }

            @Override
            public boolean keeps(ProjectMapPreview card) {
                ProjectMapCardStructure column = cardStructures.get(card);
                return pinnedCards.contains(card) || card.userArranged() || column != null && column.shown();
            }

            @Override
            public void close(ProjectMapPreview card) {
                closePreview(card);
            }

            @Override
            public CardAttachment.@Nullable Side structureSide(ProjectMapPreview card) {
                ProjectMapCardStructure column = cardStructures.get(card);
                return column != null && column.shown() ? column.side() : null;
            }
        };
    }

    // --- Linked reference cards (explore 5) ----------------------------------------------------------

    /** Where Show References / Callers as Cards find their places; null takes the menu items away. */
    void setLinkSource(MapCardLinks.@Nullable Source source) {
        links.setSource(source);
        previews.values().forEach(card -> card.setLinkActions(source == null ? null : cardNavigation));
    }

    /** Whether Show References / Callers as Cards are offered. */
    boolean linksAvailable() {
        return links.available();
    }

    /**
     * Show References / Callers as Cards from {@code card}: of its declaration for a declaration card, else of what
     * stands at the file's 0-based {@code line} and {@code column}.
     */
    void showLinks(ProjectMapPreview card, MapCardLinks.Kind kind, int line, int column) {
        showLinks(card, kind, line, column, false);
    }

    /**
     * {@code kind} asked from {@code card} (Map code navigation): of what stands at the file's 0-based {@code line}
     * and {@code column} — with {@code atPlace} false, a declaration card's declaration instead — answered as cards
     * beside it.
     */
    void showLinks(ProjectMapPreview card, MapCardLinks.Kind kind, int line, int column, boolean atPlace) {
        MapCardLinks.Request request = linksRequest(card, kind, line, column, atPlace);
        if (request != null) {
            touchCard(card, true);
            links.show(card, request);
        }
    }

    /**
     * {@code kind}'s hierarchy asked from {@code card} (Call or Type Hierarchy): of what stands at the file's 0-based
     * {@code line} and {@code column} — with {@code atPlace} false, a declaration card's declaration — as a tree beside
     * it.
     */
    void showHierarchy(ProjectMapPreview card, MapCardLinks.Kind kind, int line, int column, boolean atPlace) {
        MapCardLinks.Request request = linksRequest(card, kind, line, column, atPlace);
        if (request != null) {
            touchCard(card, true);
            links.showHierarchy(card, request);
        }
    }

    /** Show Call / Type Hierarchy from a declaration row: its card opens and shows the hierarchy beside it. */
    void showHierarchy(ProjectMapModel.Entry symbolRow, MapCardLinks.Kind kind) {
        ProjectMapPreview card = previewSymbol(symbolRow, null);
        ProjectMapPreview.Range shown = card == null ? null : card.range();
        if (card != null && shown != null) {
            showHierarchy(card, kind, shown.line(), -1, false);
        }
    }

    private MapCardLinks.@Nullable Request linksRequest(
            ProjectMapPreview card, MapCardLinks.Kind kind, int line, int column, boolean atPlace) {
        Path file = card.path();
        if (file == null || !cardTouches.containsKey(card)) {
            return null;
        }
        ProjectMapPreview.Range shown = card.range();
        ProjectMapPreview.Content content = host.content(file);
        String text = content != null && content.open() ? content.text() : null;
        return shown != null && !atPlace
                ? new MapCardLinks.Request(
                        kind, file, shown.line(), -1, ProjectMapView.followName(shown.symbol()), text)
                : new MapCardLinks.Request(kind, file, line, column, null, text);
    }

    /**
     * Quick Documentation from {@code card} at the file's 0-based {@code line} and {@code column}: the server's hover
     * in a popup at the card's caret, or the status line says why there is none.
     */
    void showHover(ProjectMapPreview card, int line, int column) {
        MapCardLinks.Request request = linksRequest(card, MapCardLinks.Kind.DEFINITION, line, column, true);
        MapCardLinks.Source source = links.source();
        Path root = host.root();
        if (request == null || source == null || root == null) {
            return;
        }
        touchCard(card, true);
        source.hover(root, request, answer -> {
            if (disposed || !cardTouches.containsKey(card)) {
                return;
            }
            String markdown = answer.markdown();
            if (markdown != null) {
                ProjectMapPreview.FollowActions actions = followActions;
                card.showHover(markdown, url -> {
                    if (actions != null) {
                        actions.openUrl(url);
                    }
                });
            } else {
                String name = answer.name();
                String none = name == null || name.isBlank()
                        ? tr("project.map.links.hover.noneUnnamed")
                        : tr("project.map.links.hover.none", name);
                String note = answer.note();
                host.status(note == null ? none : note);
            }
        });
    }

    // --- Following the code from card to card (Map code navigation) --------------------------------------

    /** What a card's code navigation does: asks {@link #links}, shows Quick Documentation, walks the Map's history. */
    private final ProjectMapPreview.LinkActions cardNavigation = new ProjectMapPreview.LinkActions() {
        @Override
        public void show(ProjectMapPreview card, MapCardLinks.Kind kind, int line, int column, boolean atPlace) {
            showLinks(card, kind, line, column, atPlace);
        }

        @Override
        public void hover(ProjectMapPreview card, int line, int column) {
            showHover(card, line, column);
        }

        @Override
        public boolean step(ProjectMapPreview card, boolean back) {
            return host.historyStep(card, back);
        }

        @Override
        public void hierarchy(ProjectMapPreview card, MapCardLinks.Kind kind, int line, int column, boolean atPlace) {
            showHierarchy(card, kind, line, column, atPlace);
        }

        @Override
        public void symbolGraph(ProjectMapPreview card, int line, int column) {
            showSymbolGraph(card, line, column, true);
        }

        @Override
        public void documentation(ProjectMapPreview card, int line, int column, Consumer<@Nullable String> answer) {
            showDocumentation(card, line, column, answer);
        }

        @Override
        public @Nullable Runnable watchProblems(
                ProjectMapPreview card,
                Path file,
                String text,
                Consumer<java.util.List<com.editora.text.LspDiagnostic>> problems) {
            MapCardLinks.Source source = links.source();
            return source == null ? null : source.watchProblems(file, text, problems);
        }

        @Override
        public void say(String message) {
            host.status(message);
        }
    };

    /**
     * The server's documentation from {@code card} at the file's 0-based {@code line} and {@code column}, for the
     * mouse resting there: given to {@code answer} once, null when there is none; nothing is said either way.
     */
    private void showDocumentation(ProjectMapPreview card, int line, int column, Consumer<@Nullable String> answer) {
        MapCardLinks.Request request = linksRequest(card, MapCardLinks.Kind.DEFINITION, line, column, true);
        MapCardLinks.Source source = links.source();
        Path root = host.root();
        if (request == null || source == null || root == null) {
            answer.accept(null);
            return;
        }
        source.hover(
                root,
                request,
                hover -> answer.accept(disposed || !cardTouches.containsKey(card) ? null : hover.markdown()));
    }

    /**
     * The code was followed from {@code from} to {@code to}: both are stops of the Map's history (Back from {@code to}
     * returns to {@code from}, its caret where it was), and the keyboard goes to {@code to}, brought into view once it
     * is laid out.
     */
    private void followed(ProjectMapPreview from, ProjectMapPreview to) {
        followed(from, from.caretOffset(), to);
    }

    /** {@link #followed(ProjectMapPreview, ProjectMapPreview)} with {@code from}'s caret as it was before the step. */
    private void followed(ProjectMapPreview from, int fromCaret, ProjectMapPreview to) {
        if (disposed) {
            return;
        }
        host.followed(from, fromCaret, to);
        if (to == from) {
            return; // its caret moves to the place; the keyboard is in it already
        }
        to.focusContent();
        javafx.application.Platform.runLater(() -> {
            if (!disposed && cardTouches.containsKey(to) && to.getWidth() > 0) {
                bringIntoView(to);
            }
        });
    }

    /** Whether {@code card} is open on the Map. */
    boolean isOpen(ProjectMapPreview card) {
        return cardTouches.containsKey(card);
    }

    /** The place {@code card} is open under: its file, or its declaration's path; null for a card of no file. */
    @Nullable
    Path placeOf(ProjectMapPreview card) {
        Path file = card.path();
        return file == null ? null : cardKeyOf(card, file);
    }

    /**
     * Back or Forward to a stop whose card was closed since (Map code browser gap 6): the card of {@code key} — a
     * file, or a declaration of an outlined file — opened again with its caret at the file's 0-based {@code line} and
     * the keyboard in it. Null when it cannot be (a declaration no longer in its file's outline, every card pinned).
     */
    @Nullable
    ProjectMapPreview reopenStop(Path key, int line) {
        ProjectMapPreview card = viewsOpenCard(key);
        if (card == null) {
            return null;
        }
        card.linksRevealPlace(line, null);
        touchCard(card, true);
        card.focusContent();
        javafx.application.Platform.runLater(() -> {
            if (!disposed && isOpen(card) && card.getWidth() > 0) {
                bringIntoView(card);
            }
        });
        return card;
    }

    /**
     * Back or Forward to a stop on {@code card}: its caret put back at {@code caret} (a negative one left where it is),
     * the card brought into view with the keyboard in it.
     */
    void goToStop(ProjectMapPreview card, int caret) {
        if (!isOpen(card)) {
            return;
        }
        if (caret >= 0) {
            card.restoreCaret(caret);
        }
        bringIntoView(card);
        card.focusContent();
    }

    /** Show References / Callers as Cards from a declaration row: its card opens and asks. */
    void showLinks(ProjectMapModel.Entry symbolRow, MapCardLinks.Kind kind) {
        ProjectMapPreview card = previewSymbol(symbolRow, null);
        ProjectMapPreview.Range shown = card == null ? null : card.range();
        if (card != null && shown != null) {
            showLinks(card, kind, shown.line(), -1);
        }
    }

    // --- API ------------------------------------------------------------------------------------------------

    /**
     * Opens (or brings up and focuses) the card of {@code file}, shown whole, with a Structure button when its language
     * can be outlined. Returns the card, or null when every open card is pinned (the status line says so).
     */
    @Nullable
    ProjectMapPreview openFileCard(Path file) {
        return previewFile(file.toAbsolutePath().normalize(), file);
    }

    /**
     * Go to Symbol and Go to File on the Map: opens (or brings up) the card of {@code file}'s 0-based {@code line} —
     * the card of the declaration holding it, the file outlined first when it can be — with {@code name} selected
     * there; a negative line opens the whole file. The keyboard goes to it and, typed in {@code from}, Back returns
     * there. Nothing opens when every open card is pinned (the status line says so).
     */
    void openPlaceCard(@Nullable ProjectMapPreview from, Path file, int line, @Nullable String name) {
        Path normalized = file.toAbsolutePath().normalize();
        ProjectMapPreview stepFrom = from != null && cardTouches.containsKey(from) ? from : null;
        int fromCaret = stepFrom == null ? 0 : stepFrom.caretOffset(); // before the place moves it, if the same card
        Consumer<@Nullable ProjectMapPreview> opened = card -> {
            if (card == null || disposed) {
                return;
            }
            pickedCards.add(card);
            if (stepFrom != null && cardTouches.containsKey(stepFrom)) {
                followed(stepFrom, fromCaret, card);
            } else {
                card.focusContent();
            }
        };
        links.openPlace(normalized, line, name, opened);
    }

    /**
     * A text search's hit stepped to (Map code browser gap 10): the card of the declaration holding {@code file}'s
     * 0-based {@code line}, the match of {@code mark} there selected — a preview the next hit replaces, or, with
     * {@code keep}, a card that stays. The keyboard stays in the search.
     */
    void openSearchHit(Path file, int line, java.util.regex.Pattern mark, boolean keep) {
        links.openSearchHit(file, line, mark, keep, card -> {
            if (card != null) {
                pickedCards.add(card); // asked for: the orphan check leaves it, its folder closed or not
            }
        });
    }

    /**
     * Opens (or brings up and focuses) the card of a declaration row: its lines of its file, beside the Map's
     * Structure column, which an open card asked for from a file card's column moves to.
     */
    void openDeclarationCard(ProjectMapModel.Entry symbolRow) {
        previewSymbol(symbolRow, null, From.MAP_COLUMN);
    }

    /**
     * Brings back a code card of the last session (Project Map review 3 B6): the card of {@code key} — a file, or a
     * declaration whose file is outlined — where it was ({@code x, y} in the map area's pixels, {@code width ×
     * height} unzoomed), at its text zoom and with its Structure column out if it was. Null when a declaration is
     * not (or no longer) in its file's outline.
     */
    @Nullable
    ProjectMapPreview restoreCard(
            Path key, double x, double y, double width, double height, double textZoom, boolean structureShown) {
        ProjectMapPreview.Placement placed = new ProjectMapPreview.Placement(x, y, width, height);
        ProjectMapPreview card;
        if (ProjectMapStructure.isSymbolPath(key)) {
            ProjectMapStructure.Node node = structure.node(key);
            card = node == null ? null : previewSymbol(symbolEntry(node), placed);
        } else {
            card = previewFile(key.toAbsolutePath().normalize(), key, placed);
        }
        if (card != null) {
            card.setContentZoom(textZoom);
            card.keepRestoredGeometry();
            if (structureShown) {
                restoringCard = true;
                try {
                    card.setStructureShown(true); // the column, without the nudge: the card stays where it was
                } finally {
                    restoringCard = false;
                }
            }
        }
        return card;
    }

    /**
     * Views (trails): opens (or brings up) the code card of {@code key} — a file, or a declaration of an outlined file
     * — placed beside its row as a click places it. Null when a declaration is not in its file's outline.
     */
    @Nullable
    ProjectMapPreview viewsOpenCard(Path key) {
        if (ProjectMapStructure.isSymbolPath(key)) {
            ProjectMapStructure.Node node = structure.node(key);
            return node == null ? null : previewSymbol(symbolEntry(node), null);
        }
        return previewFile(key.toAbsolutePath().normalize(), key);
    }

    /**
     * Views (trails): sticks {@code stuck} on {@code card} — one per card, replacing (and disposing) any earlier one
     * — until {@link #viewsDetach} or the card closes. It moves, rises and closes with the card like its other
     * attachments.
     */
    void viewsAttach(ProjectMapPreview card, CardAttachment stuck) {
        CardAttachment old = viewsAttachments.put(card, stuck);
        if (old != null && old != stuck) {
            old.dispose();
        }
        stuck.raise();
        connectorsMoved();
    }

    /** Views (trails): takes what {@link #viewsAttach} stuck on {@code card} off it. */
    void viewsDetach(ProjectMapPreview card) {
        CardAttachment old = viewsAttachments.remove(card);
        if (old != null) {
            old.dispose();
            connectorsMoved();
        }
    }

    /** Views (trails): the attachment {@link #viewsAttach} stuck on {@code card}, or null. */
    @Nullable
    CardAttachment viewsAttachment(ProjectMapPreview card) {
        return viewsAttachments.get(card);
    }

    /** Views (trails): an attachment moved; the connectors are drawn again once for the pulse. */
    void viewsConnectorsMoved() {
        connectorsMoved();
    }

    /** Opens (or brings up) the note card of a file or folder, when it has Personal Notes and notes are on. */
    void openNoteCard(Path path) {
        previewNotes(path);
    }

    /**
     * A row's Personal Notes badge (Map explore round, position 11): a file's opens its code card with its sticky notes
     * out, the one way a file's notes are shown; a folder's opens its note card.
     */
    void openNotes(Path path, boolean file) {
        if (!file) {
            previewNotes(path);
            return;
        }
        ProjectMapPreview card = openFileCard(path);
        if (card != null) {
            card.setNotesShown(true);
        }
    }

    /** Whether the card is pinned (position 7). */
    boolean pinned(Region card) {
        return pinnedCards.contains(card);
    }

    /** Whether {@code card} is still open. For tests. */
    boolean isOpenForTest(Region card) {
        return cardTouches.containsKey(card);
    }

    /** The code card open under {@code key} (a file's path or a declaration row's), or null. */
    @Nullable
    ProjectMapPreview codeCard(Path key) {
        return previews.get(key);
    }

    /** The note card of {@code path}, or null. */
    @Nullable
    ProjectMapNotePreview noteCard(Path path) {
        return notePreviews.get(path);
    }

    /**
     * Opens the Overview card of the project at {@code root} with {@code facts}, or fills the open one again and
     * brings it up (review 3 B4). {@code reveal} is what a press on one of its folders or files does, {@code open} its
     * README's Open button.
     */
    ProjectMapOverviewCard openOverviewCard(
            Path root, ProjectMapOverview.Facts facts, Consumer<Path> reveal, Consumer<Path> open) {
        ProjectMapOverviewCard card = overviewCard;
        if (card != null) {
            card.show(root, facts);
            touchCard(card, true);
            return card;
        }
        card = new ProjectMapOverviewCard();
        ProjectMapOverviewCard created = card;
        created.setOnClose(this::closeOverviewCard);
        created.setOnActivate(() -> touchCard(created, true));
        created.setOnEscape(() -> {
            closeOverviewCard();
            host.focusMap();
        });
        created.setOnReveal(reveal);
        created.setOnOpen(open);
        created.setTextZoom(overviewTextZoom);
        created.setOnTextZoomed(zoom -> overviewTextZoom = zoom);
        created.setMapScale(host.zoom());
        installPreviewListeners(created);
        overviewCard = created;
        cardTouches.put(created, ++cardTouchSequence);
        canvasHost.getChildren().add(created);
        created.show(root, facts);
        created.place(overviewPlacement(created, root));
        return created;
    }

    /**
     * Where the Overview card opens: first on the map, before the project's own column the way the map flows — left
     * of it left to right, right of it right to left, above it top to bottom, below it bottom to top — level with its
     * start, shorter rather than out of line. The map pans along the flow just enough for the card to fit there, never so far that the column leaves
     * the view; with still too little room the card narrows, down to its minimum. With no root column on the map, the
     * one placement path of the other cards ({@link #previewPlacement}).
     */
    private ProjectMapPreview.Placement overviewPlacement(ProjectMapOverviewCard card, Path root) {
        double width = canvasHost.getWidth();
        double height = canvasHost.getHeight();
        double[] column = host.rootColumn();
        if (column == null || width <= 0 || height <= 0) {
            return previewPlacement(
                    card,
                    root,
                    ProjectMapOverviewCard.DEFAULT_WIDTH,
                    ProjectMapOverviewCard.DEFAULT_HEIGHT,
                    ProjectMapOverviewCard.MIN_WIDTH,
                    ProjectMapOverviewCard.MIN_HEIGHT,
                    width,
                    height);
        }
        double scale = cardScale(card);
        double[] spot = overviewSpot(
                column,
                ProjectMapOverviewCard.DEFAULT_WIDTH * scale,
                ProjectMapOverviewCard.DEFAULT_HEIGHT * scale,
                ProjectMapOverviewCard.MIN_WIDTH * scale,
                ProjectMapOverviewCard.MIN_HEIGHT * scale,
                width,
                height,
                host.horizontalFlow(),
                host.reverseFlow(),
                host.chrome());
        if (Math.abs(spot[0]) > 0.5 || Math.abs(spot[1]) > 0.5) {
            host.panBy(spot[0], spot[1]); // the cards already open move with the map
        }
        return new ProjectMapPreview.Placement(spot[2], spot[3], spot[4] / scale, spot[5] / scale);
    }

    /**
     * The Overview card's spot before the root column {@code {x, y, w, h}} in a panel {@code parentWidth ×
     * parentHeight}, every size as drawn: {@code {dx, dy, x, y, w, h}}, where {@code dx, dy} is the pan the map needs
     * first and {@code x, y} is the card's corner after it. Worked out as if the map flowed left to right — the flow
     * along x, the card left of the column — and turned back to the real flow at the end. The zoom bar and the
     * overview ({@code chrome}) are cleared across the flow when they are in the way.
     */
    static double[] overviewSpot(
            double[] column,
            double width,
            double height,
            double minimumWidth,
            double minimumHeight,
            double parentWidth,
            double parentHeight,
            boolean horizontal,
            boolean reverse,
            List<double[]> chrome) {
        double margin = ProjectMapPreview.EDGE_MARGIN;
        // Along the flow (a) and across it (c): the column's start and size, the card's sizes, the panel's.
        double along = horizontal ? parentWidth : parentHeight;
        double across = horizontal ? parentHeight : parentWidth;
        double size = horizontal ? column[2] : column[3];
        double start = horizontal ? column[0] : column[1];
        if (reverse) {
            start = along - start - size; // mirrored: the flow runs forwards
        }
        double crossStart = horizontal ? column[1] : column[0];
        double cardAlong =
                cardBounded(horizontal ? width : height, horizontal ? minimumWidth : minimumHeight, along - margin * 2);
        double minimumAlong = Math.min(horizontal ? minimumWidth : minimumHeight, cardAlong);
        double cardAcross = cardBounded(
                horizontal ? height : width, horizontal ? minimumHeight : minimumWidth, across - margin * 2);
        double pan = 0;
        double behind = start - COLUMN_GAP - margin; // the room before the column
        double missing = cardAlong - behind;
        if (missing > 0) { // the map moves ahead, as far as the column stays in view
            double ahead = Math.max(0, along - margin - (start + size));
            pan = Math.min(missing, ahead);
            start += pan;
            behind += pan;
        }
        if (behind < cardAlong) {
            cardAlong = Math.max(minimumAlong, behind);
        }
        double cardStart = Math.max(margin, start - COLUMN_GAP - cardAlong);
        if (cardStart + cardAlong > along - margin) { // the column is past the far edge: the map comes back
            double back = along - margin - (cardStart + cardAlong);
            pan += back;
            cardStart += back;
        }
        // Level with the column's start, the card shorter rather than higher; it moves only for its minimum.
        double minimumAcross = Math.min(horizontal ? minimumHeight : minimumWidth, cardAcross);
        double cardCross = Math.max(margin, Math.min(crossStart, across - margin - minimumAcross));
        cardAcross = Math.min(cardAcross, across - margin - cardCross);
        if (reverse) {
            cardStart = along - cardStart - cardAlong;
            pan = -pan;
        }
        double x = horizontal ? cardStart : cardCross;
        double y = horizontal ? cardCross : cardStart;
        double w = horizontal ? cardAlong : cardAcross;
        double h = horizontal ? cardAcross : cardAlong;
        for (double[] c : chrome) {
            if (cardOverlapCost(x, y, w, h, List.of(c)) <= 0) {
                continue;
            }
            double gap = COLUMN_GAP / 2;
            if (horizontal) { // across is up and down: end above it, or stand above it
                if (c[1] - gap - y >= Math.min(minimumHeight, h)) {
                    h = Math.min(h, c[1] - gap - y);
                } else if (c[1] - gap - h >= margin) {
                    y = c[1] - gap - h;
                }
            } else if (c[0] + c[2] + gap + w <= parentWidth - margin) { // across is sideways: step past it
                x = c[0] + c[2] + gap;
            } else if (c[0] - gap - w >= margin) {
                x = c[0] - gap - w;
            }
        }
        return new double[] {horizontal ? pan : 0, horizontal ? 0 : pan, x, y, w, h};
    }

    /** The open Overview card, or null. */
    @Nullable
    ProjectMapOverviewCard overviewCard() {
        return overviewCard;
    }

    private void closeOverviewCard() {
        ProjectMapOverviewCard card = overviewCard;
        if (card == null) {
            return;
        }
        boolean focused = focusReturnsFrom(card);
        overviewCard = null;
        cardTouches.remove(card);
        canvasHost.getChildren().remove(card);
        repaintPreviewConnectors();
        focusReturnToMap(focused);
    }

    /** The cards there is one of at most — the Overview, the History and the Dependencies card — that are open. */
    private List<Region> soloCards() {
        List<Region> solo = new ArrayList<>(4);
        if (overviewCard != null) {
            solo.add(overviewCard);
        }
        if (historyCard != null) {
            solo.add(historyCard);
        }
        if (dependencyCard != null) {
            solo.add(dependencyCard);
        }
        if (metricsCard != null) {
            solo.add(metricsCard);
        }
        if (changesCard != null) {
            solo.add(changesCard);
        }
        if (symbolGraphCard != null) {
            solo.add(symbolGraphCard);
        }
        return solo;
    }

    /**
     * Opens the Dependencies card (gap 14) on {@code rel} of the project at {@code root} — a folder ({@code ""}: the
     * project) or a file — beside {@code beside} when given (the file's card it was asked from), else where a card of
     * {@code root.resolve(rel)} would open; or moves the open one onto it and brings it up. {@code setUp} is called once,
     * on a new card, to give it its actions.
     */
    ProjectMapDependencyCard openDependencyCard(
            Path root, String rel, boolean folder, @Nullable Region beside, Consumer<ProjectMapDependencyCard> setUp) {
        ProjectMapDependencyCard card = dependencyCard;
        boolean created = card == null;
        if (card == null) {
            ProjectMapDependencyCard made = new ProjectMapDependencyCard();
            made.setOnOpen((file, line) -> openPlaceCard(null, file, line, null));
            setUp.accept(made);
            dependencyCard = made;
            adoptSoloCard(made, this::closeDependencyCard);
            card = made;
        }
        card.show(root, rel, folder);
        if (created || beside != null) {
            Path at = rel.isEmpty() ? root : root.resolve(rel);
            card.place(besidePlacement(
                    card,
                    beside,
                    at,
                    ProjectMapDependencyCard.DEFAULT_WIDTH,
                    ProjectMapDependencyCard.DEFAULT_HEIGHT,
                    ProjectMapDependencyCard.MIN_WIDTH,
                    ProjectMapDependencyCard.MIN_HEIGHT));
        }
        touchCard(card, true);
        if (created) {
            repaintPreviewConnectors();
        }
        return card;
    }

    /** The open Dependencies card, or null. */
    @Nullable
    ProjectMapDependencyCard dependencyCard() {
        return dependencyCard;
    }

    private void closeDependencyCard() {
        ProjectMapDependencyCard card = dependencyCard;
        dependencyCard = null;
        dropSoloCard(card);
    }

    /**
     * Opens the Code Metrics card (gap 15) on the folder {@code rel} of the project at {@code root} ({@code ""}: the
     * project), where a card of that folder would open, or moves the open one onto it and brings it up. {@code setUp}
     * is called once, on a new card, to give it its actions.
     */
    ProjectMapMetricsCard openMetricsCard(Path root, String rel, Consumer<ProjectMapMetricsCard> setUp) {
        ProjectMapMetricsCard card = metricsCard;
        boolean created = card == null;
        if (card == null) {
            ProjectMapMetricsCard made = new ProjectMapMetricsCard();
            made.setOnOpen(file -> openPlaceCard(null, file, -1, null));
            setUp.accept(made);
            metricsCard = made;
            adoptSoloCard(made, this::closeMetricsCard);
            card = made;
        }
        card.show(root, rel);
        if (created) {
            card.place(besidePlacement(
                    card,
                    null,
                    rel.isEmpty() ? root : root.resolve(rel),
                    ProjectMapMetricsCard.DEFAULT_WIDTH,
                    ProjectMapMetricsCard.DEFAULT_HEIGHT,
                    ProjectMapMetricsCard.MIN_WIDTH,
                    ProjectMapMetricsCard.MIN_HEIGHT));
            repaintPreviewConnectors();
        }
        touchCard(card, true);
        return card;
    }

    /**
     * Opens the Changes card (Map code browser round 2) on the project at {@code root}, where a card of the project
     * would open, or brings the open one up. {@code setUp} is called once, on a new card, to give it its actions.
     */
    ProjectMapChangesCard openChangesCard(Path root, Consumer<ProjectMapChangesCard> setUp) {
        ProjectMapChangesCard card = changesCard;
        boolean created = card == null;
        if (card == null) {
            ProjectMapChangesCard made = new ProjectMapChangesCard();
            setUp.accept(made);
            changesCard = made;
            adoptSoloCard(made, this::closeChangesCard);
            card = made;
        }
        if (created) {
            card.place(besidePlacement(
                    card,
                    null,
                    root,
                    ProjectMapChangesCard.DEFAULT_WIDTH,
                    ProjectMapChangesCard.DEFAULT_HEIGHT,
                    ProjectMapChangesCard.MIN_WIDTH,
                    ProjectMapChangesCard.MIN_HEIGHT));
            repaintPreviewConnectors();
        }
        touchCard(card, true);
        return card;
    }

    /** The open Changes card, or null. */
    @Nullable
    ProjectMapChangesCard changesCard() {
        return changesCard;
    }

    private void closeChangesCard() {
        ProjectMapChangesCard card = changesCard;
        changesCard = null;
        dropSoloCard(card);
    }

    /**
     * The Symbol Graph of what stands at the file's 0-based {@code line} and {@code column} of {@code card} — with
     * {@code atPlace} false, a declaration card's declaration — beside the card (Map code browser round 2).
     */
    void showSymbolGraph(ProjectMapPreview card, int line, int column, boolean atPlace) {
        MapCardLinks.Request request = linksRequest(card, MapCardLinks.Kind.CALLERS, line, column, atPlace);
        if (request != null) {
            touchCard(card, true);
            openSymbolGraph(
                    new ProjectMapSymbolGraphCard.Subject(
                            request.file(), request.line(), request.column(), request.name(), request.text()),
                    card);
        }
    }

    /** Symbol Graph from a declaration row: its card opens, and the graph of its declaration beside it. */
    void showSymbolGraph(ProjectMapModel.Entry symbolRow) {
        ProjectMapPreview card = previewSymbol(symbolRow, null);
        ProjectMapPreview.Range shown = card == null ? null : card.range();
        if (card != null && shown != null) {
            showSymbolGraph(card, shown.line(), -1, false);
        }
    }

    /**
     * Opens the Symbol Graph card on {@code subject}, beside {@code beside} when given — or moves the open one onto it
     * (its last subject on its Back list) — and asks its four sides of the Map's code links.
     */
    ProjectMapSymbolGraphCard openSymbolGraph(ProjectMapSymbolGraphCard.Subject subject, @Nullable Region beside) {
        ProjectMapSymbolGraphCard card = symbolGraphCard;
        boolean created = card == null;
        if (card == null) {
            ProjectMapSymbolGraphCard made = new ProjectMapSymbolGraphCard();
            made.setAsker(links::askLevel);
            made.setOpener((file, line, name) -> openPlaceCard(null, file, line, name));
            symbolGraphCard = made;
            adoptSoloCard(made, this::closeSymbolGraphCard);
            card = made;
        }
        card.show(subject);
        if (created) {
            card.place(besidePlacement(
                    card,
                    beside,
                    subject.file(),
                    ProjectMapSymbolGraphCard.DEFAULT_WIDTH,
                    ProjectMapSymbolGraphCard.DEFAULT_HEIGHT,
                    ProjectMapSymbolGraphCard.MIN_WIDTH,
                    ProjectMapSymbolGraphCard.MIN_HEIGHT));
            repaintPreviewConnectors();
        }
        touchCard(card, true);
        card.focusContent();
        return card;
    }

    /** Where the code cards read which of the editor's reading aids to show: the editor's settings. */
    void setCardAids(java.util.function.Supplier<MapCardAids> aids) {
        cardAids = aids;
        applyCardAids();
    }

    /** The editor's settings changed: every code card shows the aids they name now, asked afresh. */
    void applyCardAids() {
        for (ProjectMapPreview card : List.copyOf(previews.values())) {
            card.setStickyScroll(cardAids.get().stickyScroll());
            askCardAids(card);
        }
    }

    /**
     * Asks the server that holds {@code card}'s file (the card lends it) for its semantic tokens and its inlay hints of
     * the card's lines, as the settings want them; with a setting off, the card's are taken away.
     */
    private void askCardAids(ProjectMapPreview card) {
        MapCardAids aids = cardAids.get();
        MapCardLinks.Source source = links.source();
        Path file = card.path();
        String text = card.fullText();
        if (file == null || text.isEmpty() || disposed) {
            return;
        }
        int lineCount = 1;
        int lastBreak = -1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lineCount++;
                lastBreak = i;
            }
        }
        int lastLineLength = text.length() - lastBreak - 1;
        if (aids.semantic() && source != null) {
            source.semanticTokens(file, lineCount, lastLineLength, tokens -> card.setSemanticTokens(text, tokens));
        } else {
            card.setSemanticTokens(text, List.of());
        }
        if (aids.inlayHints() && source != null) {
            ProjectMapPreview.Range shown = card.range();
            int start = shown == null ? 0 : shown.startLine();
            int end = shown == null ? lineCount - 1 : shown.endLine();
            com.editora.lsp.InlayHintFilter.Mode mode = com.editora.lsp.InlayHintFilter.Mode.of(aids.inlayMode());
            String[] lines = text.split("\n", -1);
            source.inlayHints(
                    file,
                    start,
                    end,
                    lineCount,
                    lastLineLength,
                    hints -> card.setInlayHints(
                            text,
                            com.editora.lsp.InlayHintFilter.filter(
                                    hints, mode, line -> line >= 0 && line < lines.length ? lines[line] : "")));
        } else {
            card.setInlayHints(text, List.of());
        }
    }

    /** The open Symbol Graph card, or null. */
    @Nullable
    ProjectMapSymbolGraphCard symbolGraphCard() {
        return symbolGraphCard;
    }

    private void closeSymbolGraphCard() {
        ProjectMapSymbolGraphCard card = symbolGraphCard;
        symbolGraphCard = null;
        dropSoloCard(card);
    }

    /** The open Code Metrics card, or null. */
    @Nullable
    ProjectMapMetricsCard metricsCard() {
        return metricsCard;
    }

    private void closeMetricsCard() {
        ProjectMapMetricsCard card = metricsCard;
        metricsCard = null;
        dropSoloCard(card);
    }

    /** A new card there is one of at a time joins the map: {@code close} closes it (its button, Escape). */
    private void adoptSoloCard(MapFloatingCard made, Runnable close) {
        made.setOnClose(close);
        made.setOnActivate(() -> touchCard(made, true));
        made.setOnEscape(() -> {
            close.run();
            host.focusMap();
        });
        made.setMapScale(host.zoom());
        installPreviewListeners(made);
        cardTouches.put(made, ++cardTouchSequence);
        canvasHost.getChildren().add(made);
    }

    /** A card there is one of at a time leaves the map (nothing for null); the keyboard goes back to the map. */
    private void dropSoloCard(@Nullable Region card) {
        if (card == null) {
            return;
        }
        boolean focused = focusReturnsFrom(card);
        cardTouches.remove(card);
        canvasHost.getChildren().remove(card);
        repaintPreviewConnectors();
        focusReturnToMap(focused);
    }

    /**
     * Opens the History card of the declaration {@code card} shows (gap 12) beside it, or fills the open History card
     * with it; a card showing a whole file has no declaration, and opens none. Its commits and its lines' blame are
     * asked of Git as it opens.
     */
    @Nullable
    ProjectMapHistoryCard openHistory(ProjectMapPreview card) {
        Path file = card.path();
        ProjectMapPreview.Range range = card.range();
        ProjectMapPreview.FollowActions actions = followActions;
        if (file == null || range == null || actions == null) {
            return null;
        }
        ProjectMapHistoryCard history = historyCard;
        boolean created = history == null;
        if (history == null) {
            ProjectMapHistoryCard made = new ProjectMapHistoryCard();
            made.setOnModeChanged(mode -> historyMode = mode);
            historyCard = made;
            adoptSoloCard(made, this::closeHistoryCard);
            history = made;
        }
        ProjectMapHistoryCard shown = history;
        shown.setMode(historyMode);
        long ask = shown.show(file, range.symbol(), range.startLine(), range.endLine(), card.fullText());
        shown.place(historyPlacement(shown, card, file));
        touchCard(shown, true);
        if (created) {
            repaintPreviewConnectors();
        }
        try {
            actions.lineHistory(file, range.startLine(), range.endLine(), ProjectMapHistoryCard.MAX_COMMITS, result -> {
                if (historyCard != shown) {
                    return;
                }
                shown.setHistory(ask, result);
                Path root = result.root();
                if (root != null) {
                    // The commits listed are this repository's: each opens there.
                    shown.setOnOpenCommit(commit ->
                            actions.openCommitFile(root, commit.hash(), commit.path(), commit.previousPath()));
                    actions.blame(root, file, lines -> {
                        if (historyCard == shown) {
                            shown.setLines(ask, card.fullText()); // read by now, if not when it was shown
                            shown.setBlame(ask, lines);
                        }
                    });
                }
            });
        } catch (RuntimeException failed) {
            ProjectMapLog.unexpected("asking Git for a declaration's history", failed);
        }
        return shown;
    }

    /**
     * Where the History card opens: right of the declaration card it is about, level with its top, or left of it when
     * that covers less of the map's columns, the other cards and the zoom bar — else below it — at its default size or
     * smaller down to its minimum; with no room beside the card at all, as any card is placed ({@link
     * #previewPlacement}). The map is not panned.
     */
    private ProjectMapPreview.Placement historyPlacement(
            ProjectMapHistoryCard history, ProjectMapPreview card, Path file) {
        return besidePlacement(
                history,
                card,
                file,
                ProjectMapHistoryCard.DEFAULT_WIDTH,
                ProjectMapHistoryCard.DEFAULT_HEIGHT,
                ProjectMapHistoryCard.MIN_WIDTH,
                ProjectMapHistoryCard.MIN_HEIGHT);
    }

    /**
     * Where a card {@code width × height} (at least {@code minimumWidth × minimumHeight}, unzoomed) opens beside {@code
     * source}: as {@link #historySpot} finds; with no source, or no room beside it, as any card of {@code file} is
     * placed ({@link #previewPlacement}). The map is not panned.
     */
    private ProjectMapPreview.Placement besidePlacement(
            MapFloatingCard card,
            @Nullable Region source,
            Path file,
            double width,
            double height,
            double minimumWidth,
            double minimumHeight) {
        double parentWidth = canvasHost.getWidth();
        double parentHeight = canvasHost.getHeight();
        double scale = cardScale(card);
        double[] spot = null;
        if (source != null) {
            List<double[]> obstacles = new ArrayList<>(cardsTaken(card));
            obstacles.addAll(host.chrome());
            spot = historySpot(
                    drawnBounds(source),
                    width * scale,
                    height * scale,
                    minimumWidth * scale,
                    minimumHeight * scale,
                    parentWidth,
                    parentHeight,
                    obstacles);
        }
        if (spot == null) {
            return previewPlacement(card, file, width, height, minimumWidth, minimumHeight, parentWidth, parentHeight);
        }
        return new ProjectMapPreview.Placement(spot[0], spot[1], spot[2] / scale, spot[3] / scale);
    }

    /**
     * The History card's spot beside {@code source} ({@code {x, y, w, h}} as drawn) in a panel {@code parentWidth ×
     * parentHeight}: {@code {x, y, w, h}}, right of it, left of it or below it — the first of those that fits the
     * card's minimum and covers least of {@code obstacles} ({@code {x, y, w, h}}, or with a weight after) — or null when
     * none fits. Pure.
     */
    static double @Nullable [] historySpot(
            double[] source,
            double width,
            double height,
            double minimumWidth,
            double minimumHeight,
            double parentWidth,
            double parentHeight,
            List<double[]> obstacles) {
        double margin = ProjectMapPreview.EDGE_MARGIN;
        double gap = COLUMN_GAP;
        double top = Math.max(margin, Math.min(source[1], parentHeight - margin - minimumHeight));
        double h = Math.min(height, parentHeight - margin - top);
        double[] best = null;
        double bestCost = Double.MAX_VALUE;
        // Right of it.
        double rightX = source[0] + source[2] + gap;
        double rightW = Math.min(width, parentWidth - margin - rightX);
        // Left of it.
        double leftW = Math.min(width, source[0] - gap - margin);
        // Below it.
        double belowY = source[1] + source[3] + gap;
        double belowX = Math.max(margin, Math.min(source[0], parentWidth - margin - minimumWidth));
        double belowW = Math.min(width, parentWidth - margin - belowX);
        double belowH = Math.min(height, parentHeight - margin - belowY);
        List<double[]> weighed = new ArrayList<>(obstacles.size());
        for (double[] o : obstacles) {
            weighed.add(o.length > 4 ? o : new double[] {o[0], o[1], o[2], o[3], 1}); // unweighed: as much as a card
        }
        double[][] candidates = {
            {rightX, top, rightW, h},
            {source[0] - gap - leftW, top, leftW, h},
            {belowX, belowY, belowW, belowH}
        };
        for (double[] c : candidates) {
            if (c[2] < minimumWidth || c[3] < minimumHeight || c[0] < margin - 0.5 || c[1] < margin - 0.5) {
                continue;
            }
            double cost = cardOverlapCost(c[0], c[1], c[2], c[3], weighed);
            if (cost < bestCost - 0.5) {
                best = c;
                bestCost = cost;
            }
        }
        return best;
    }

    /** The open History card, or null. */
    @Nullable
    ProjectMapHistoryCard historyCard() {
        return historyCard;
    }

    private void closeHistoryCard() {
        ProjectMapHistoryCard card = historyCard;
        historyCard = null;
        dropSoloCard(card);
    }

    /** Every open card, the most recently used first. */
    List<OpenCard> openCards() {
        List<OpenCard> open = new ArrayList<>();
        previews.forEach((key, card) -> open.add(new OpenCard(key, card, card.title(), false)));
        notePreviews.forEach((key, card) -> open.add(new OpenCard(key, card, card.title(), true)));
        ProjectMapOverviewCard overview = overviewCard;
        Path root = host.root();
        if (overview != null && root != null) {
            open.add(new OpenCard(root, overview, overview.title(), false));
        }
        ProjectMapHistoryCard history = historyCard;
        Path historyFile = history == null ? null : history.file();
        if (history != null && historyFile != null) {
            open.add(new OpenCard(historyFile, history, history.title(), false));
        }
        ProjectMapDependencyCard dependencies = dependencyCard;
        Path dependencyRoot = dependencies == null ? null : dependencies.root();
        if (dependencies != null && dependencyRoot != null) {
            open.add(new OpenCard(
                    dependencyRoot.resolve(dependencies.subject()), dependencies, dependencies.title(), false));
        }
        ProjectMapMetricsCard metrics = metricsCard;
        Path metricsRoot = metrics == null ? null : metrics.root();
        if (metrics != null && metricsRoot != null) {
            open.add(new OpenCard(metricsRoot.resolve(metrics.subject()), metrics, metrics.title(), false));
        }
        ProjectMapChangesCard changes = changesCard;
        Path changesRoot = changes == null ? null : changes.root();
        if (changes != null && changesRoot != null) {
            open.add(new OpenCard(changesRoot, changes, changes.title(), false));
        }
        ProjectMapSymbolGraphCard graph = symbolGraphCard;
        ProjectMapSymbolGraphCard.Subject graphSubject = graph == null ? null : graph.subject();
        if (graph != null && graphSubject != null) {
            open.add(new OpenCard(graphSubject.file(), graph, graph.title(), false));
        }
        open.sort(java.util.Comparator.comparingLong((OpenCard card) -> cardTouches.getOrDefault(card.node(), 0L))
                .reversed());
        return open;
    }

    /** The cards in sight of the map — not hidden by a filter or an option — as marks for the overview. */
    List<OpenCard> visibleCards() {
        return openCards().stream().filter(card -> card.node().isVisible()).toList();
    }

    /** A way to move the keyboard between cards (Map code browser gap 13). */
    enum Toward {
        LEFT,
        RIGHT,
        UP,
        DOWN
    }

    /** The keys that move the keyboard to the nearest card in their direction: Ctrl+Shift+arrows. */
    static final Set<String> TOWARD_KEYS = Set.of("C-S-left", "C-S-right", "C-S-up", "C-S-down");

    /** The way {@code event} moves the keyboard between cards — Ctrl+Shift with an arrow — or null. Pure. */
    static @Nullable Toward toward(javafx.scene.input.KeyEvent event) {
        if (event.getEventType() != javafx.scene.input.KeyEvent.KEY_PRESSED
                || !event.isControlDown()
                || !event.isShiftDown()
                || event.isAltDown()
                || event.isMetaDown()) {
            return null;
        }
        return switch (event.getCode()) {
            case LEFT -> Toward.LEFT;
            case RIGHT -> Toward.RIGHT;
            case UP -> Toward.UP;
            case DOWN -> Toward.DOWN;
            default -> null;
        };
    }

    /**
     * Moves the keyboard to the nearest card {@code toward} from where it is ({@code target}): from a card — or a
     * Structure column or sticky note on one — from that; from the map itself, from {@code mapOrigin} (its selected
     * row, {@code {x, y, w, h}}). The card is brought into view only as far as it is not in view already, so the map
     * stays where it was when it is. With no card that way the status says so. Whether the key was used: false when
     * the keyboard is in neither a card nor the map.
     */
    boolean focusToward(Node target, Toward toward, double @Nullable [] mapOrigin) {
        Node from = null;
        double[] origin = mapOrigin;
        if (origin == null) {
            from = canvasChildOf(target);
            if (from == null) {
                return false;
            }
            origin = drawnOf(from);
        }
        List<Region> candidates = new ArrayList<>();
        List<double[]> boxes = new ArrayList<>();
        for (OpenCard open : visibleCards()) {
            if (open.node() != from) {
                candidates.add(open.node());
                boxes.add(drawnBounds(open.node()));
            }
        }
        int nearest = nearestToward(origin, boxes, toward);
        if (nearest < 0) {
            host.status(tr(
                    switch (toward) {
                        case LEFT -> "project.map.status.noCardToward.left";
                        case RIGHT -> "project.map.status.noCardToward.right";
                        case UP -> "project.map.status.noCardToward.up";
                        case DOWN -> "project.map.status.noCardToward.down";
                    }));
            return true;
        }
        revealCard(candidates.get(nearest));
        return true;
    }

    /** The map area's child that holds {@code node} (a card, or what is stuck on one), or null. */
    private @Nullable Node canvasChildOf(Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current.getParent() == canvasHost) {
                return current;
            }
        }
        return null;
    }

    /** {@code node} as drawn, {@code {x, y, w, h}}: a card by its own size and zoom, anything else by its bounds. */
    private static double[] drawnOf(Node node) {
        if (node instanceof Region card && cardScale(card) != 1) {
            return drawnBounds(card);
        }
        javafx.geometry.Bounds bounds = node.getBoundsInParent();
        return new double[] {bounds.getMinX(), bounds.getMinY(), bounds.getWidth(), bounds.getHeight()};
    }

    /**
     * The index of the box nearest {@code from} {@code toward}, among {@code boxes} ({@code {x, y, w, h}} each), or -1.
     * A box counts when its centre lies past {@code from}'s that way, by no less than the box stands off to the side
     * of {@code from} (a card mostly below is not "to the right"); the nearest is the one with the smallest gap
     * along the way plus twice its offset across it (0 while the two overlap across it), so a card straight ahead
     * beats a nearer one far to the side; the distance between centres breaks a tie. Pure.
     */
    static int nearestToward(double[] from, List<double[]> boxes, Toward toward) {
        boolean horizontal = toward == Toward.LEFT || toward == Toward.RIGHT;
        int sign = toward == Toward.RIGHT || toward == Toward.DOWN ? 1 : -1;
        int a = horizontal ? 0 : 1; // along the way
        int c = horizontal ? 1 : 0; // across it
        double fromCentre = from[a] + from[a + 2] / 2;
        int best = -1;
        double bestScore = Double.MAX_VALUE;
        double bestCentres = Double.MAX_VALUE;
        for (int i = 0; i < boxes.size(); i++) {
            double[] box = boxes.get(i);
            double centre = box[a] + box[a + 2] / 2;
            double ahead = (centre - fromCentre) * sign;
            double across = Math.max(0, Math.max(box[c] - (from[c] + from[c + 2]), from[c] - (box[c] + box[c + 2])));
            if (ahead <= 0.5 || ahead < across) {
                continue; // not that way, or more to the side than that way
            }
            double gap = sign > 0 ? box[a] - (from[a] + from[a + 2]) : from[a] - (box[a] + box[a + 2]);
            double score = Math.max(0, gap) + 2 * across;
            double centres = Math.hypot(centre - fromCentre, (box[c] + box[c + 2] / 2) - (from[c] + from[c + 2] / 2));
            if (score < bestScore - 0.5 || (Math.abs(score - bestScore) <= 0.5 && centres < bestCentres)) {
                best = i;
                bestScore = score;
                bestCentres = centres;
            }
        }
        return best;
    }

    /**
     * Brings an open card into view with the keyboard in it, as {@link #bringIntoView} does, but moving the map only
     * as far as the card is out of view (none when all of it is in view), so moving between cards keeps the map still.
     */
    void revealCard(Region card) {
        if (disposed || !cardTouches.containsKey(card) || !card.isVisible()) {
            return;
        }
        double[] drawn = drawnBounds(card);
        double margin = ProjectMapPreview.EDGE_MARGIN;
        double dx = revealShift(drawn[0], drawn[2], canvasHost.getWidth(), margin);
        double dy = revealShift(drawn[1], drawn[3], canvasHost.getHeight(), margin);
        if (Math.abs(dx) > 0.5 || Math.abs(dy) > 0.5) {
            host.panBy(dx, dy);
        }
        touchCard(card, true);
        focusCardContent(card);
    }

    /**
     * How far a span at {@code start} of {@code size} must move to be in {@code 0..room} with {@code margin} to spare:
     * 0 when it is, else just enough — its start in view first when it is longer than the room. Pure.
     */
    static double revealShift(double start, double size, double room, double margin) {
        if (start < margin || size + margin * 2 > room) {
            return margin - start;
        }
        double end = start + size;
        return end > room - margin ? room - margin - end : 0;
    }

    /** A card as drawn, {@code {x, y, w, h}} in the map area's pixels. */
    static double[] drawnBounds(Region card) {
        double scale = cardScale(card);
        return new double[] {card.getLayoutX(), card.getLayoutY(), card.getWidth() * scale, card.getHeight() * scale};
    }

    /**
     * Brings an open card into view and to the front, with the keyboard in it: the map is panned (and the cards with
     * it) so the card stands in the middle of the map area — or its top-left corner does, for one larger than the area
     * (review 3, C10). Nothing for a card that is closed or hidden by a filter.
     */
    void bringIntoView(Region card) {
        if (disposed || !cardTouches.containsKey(card) || !card.isVisible()) {
            return;
        }
        double[] drawn = drawnBounds(card);
        double width = canvasHost.getWidth();
        double height = canvasHost.getHeight();
        double margin = ProjectMapPreview.EDGE_MARGIN;
        double dx = drawn[2] + margin * 2 <= width ? width / 2 - (drawn[0] + drawn[2] / 2) : margin - drawn[0];
        double dy = drawn[3] + margin * 2 <= height ? height / 2 - (drawn[1] + drawn[3] / 2) : margin - drawn[1];
        if (Math.abs(dx) > 0.5 || Math.abs(dy) > 0.5) {
            host.panBy(dx, dy);
        }
        touchCard(card, true);
        focusCardContent(card);
    }

    /** Moves the keyboard into {@code card}: its text, or its first control. */
    private static void focusCardContent(Region card) {
        if (card instanceof ProjectMapPreview code) {
            code.focusContent();
        } else if (card instanceof ProjectMapNotePreview note) {
            note.focusContent();
        } else if (card instanceof ProjectMapOverviewCard overview) {
            overview.focusContent();
        } else if (card instanceof MapFloatingCard floating) {
            floating.focusContent(); // the History card, the Dependencies card
        }
    }

    /** Whether any card is open. */
    boolean isEmpty() {
        return previews.isEmpty() && notePreviews.isEmpty() && soloCards().isEmpty();
    }

    /** Closes one code or note card, with what is stuck on it. */
    void close(Region card) {
        if (card instanceof ProjectMapPreview code) {
            closePreview(code);
        } else if (card instanceof ProjectMapNotePreview note) {
            closeNotePreview(note);
        } else if (card == overviewCard) {
            closeOverviewCard();
        } else if (card == historyCard) {
            closeHistoryCard();
        } else if (card == dependencyCard) {
            closeDependencyCard();
        } else if (card == metricsCard) {
            closeMetricsCard();
        } else if (card == changesCard) {
            closeChangesCard();
        } else if (card == symbolGraphCard) {
            closeSymbolGraphCard();
        }
    }

    /** Closes every card (a root change, the window's dispose, "close all cards"). */
    void closeAll() {
        closeAllPreviews();
    }

    /**
     * Closes the cards of files at or under {@code directory} (its column closed, Collapse All, Close Other Branches),
     * but not a pinned card nor a linked result card, which the orphan check keeps while its source card is open
     * (review 4, D4).
     */
    void closeUnder(Path directory) {
        closePreviewsUnder(directory);
    }

    /**
     * Escape on the map: closes the cards on screen and says whether there were any. Cards hidden by "Hide unmatched"
     * or "Hide all open Personal Notes" are not on screen and stay.
     */
    boolean closeVisible() {
        // A pinned card is kept until its own × or Escape in it closes it (review 4, position 3).
        List<ProjectMapPreview> cards = previews.values().stream()
                .filter(card -> card.isVisible() && !pinnedCards.contains(card))
                .toList();
        List<ProjectMapNotePreview> notes = notePreviews.values().stream()
                .filter(ProjectMapNotePreview::isVisible)
                .toList();
        cards.forEach(this::closePreview);
        notes.forEach(this::closeNotePreview);
        List<Region> solo = soloCards().stream().filter(Region::isVisible).toList(); // the Overview, the History card
        solo.forEach(this::close);
        return !cards.isEmpty() || !notes.isEmpty() || !solo.isEmpty();
    }

    /** The files whose outline the cards need: declaration cards' files and those with a Structure column out. */
    Set<Path> outlineFiles() {
        return cardOutlineFiles();
    }

    /** Where the cards' editor font comes from: the editor window this Map belongs to. */
    void setEditorFontSource(java.util.function.Supplier<javafx.stage.@Nullable Window> source) {
        cardFontSource = source == null ? () -> null : source;
    }

    void setMarkerActions(ProjectMapPreview.@Nullable MarkerActions actions) {
        previewMarkerActions = actions;
        previews.values().forEach(preview -> preview.setMarkerActions(actions));
    }

    /** Where every card's gutter reads its marks from, and what its clicks do (Map gaps round 3, item 1). */
    void setGutterSource(MapCardGutter.@Nullable Source source) {
        gutterSource = source;
        previews.values().forEach(preview -> preview.setGutterSource(source));
    }

    /** Reads every card's gutter marks again ({@code git}: and its change bars — the Git state changed). */
    void refreshGutters(boolean git) {
        previews.values().forEach(preview -> preview.refreshGutter(git));
    }

    /** Where every card follows the code to: the editor, the Map's reveal and the Git log (review 3 C12). */
    void setFollowActions(ProjectMapPreview.@Nullable FollowActions actions) {
        followActions = actions;
        previews.values().forEach(preview -> preview.setFollowActions(actions));
    }

    void setNoteActions(ProjectPanel.@Nullable MarkerActions actions) {
        notePreviewActions = actions;
        previews.values().forEach(this::updateCardNotes);
    }

    /** Asks for a refresh of every card once the notifications that prompted it have settled. */
    void scheduleRefresh() {
        scheduleCardRefresh();
    }

    /** Brings the cards up to date now; a {@code polled} pass looks only at cards that mirror an open buffer. */
    void refresh(boolean polled) {
        refreshCards(polled);
    }

    /**
     * A file was rewritten on disk: the cards are checked at most once per {@link #CARD_POLL_INTERVAL}, after the
     * first change rather than the last, so a file that never stops changing is still followed.
     */
    void filesChangedOnDisk() {
        if (disposed
                || previews.isEmpty() && !structure.hasOpenColumns()
                || !host.onScreen() // the show that follows refreshes every card
                || cardDiskRefresh.getStatus() == javafx.animation.Animation.Status.RUNNING) {
            return;
        }
        cardDiskRefresh.playFromStart();
    }

    /** The Map window was hidden: nothing is refreshed or polled until it shows again. */
    void stopRefreshing() {
        cardRefresh.stop();
        cardPoll.stop();
        cardDiskRefresh.stop();
    }

    /** The map area took another size: cards stay no larger than it, the connectors follow. */
    void hostResized(double width, double height) {
        previewConnectorCanvas.setWidth(width);
        previewConnectorCanvas.setHeight(height);
        constrainPreviews(width, height);
        repaintPreviewConnectors();
    }

    /** What the map shows changed: cards hide and show with their rows, the connectors are drawn again. */
    void updateVisibility() {
        updateCardVisibility();
    }

    /** Draws every connector now. */
    void drawConnectors() {
        repaintPreviewConnectors();
    }

    /**
     * The map was panned or zoomed from {@code fromX, fromY} at {@code fromZoom} to {@code x, y} at {@code zoom}: each
     * card's corner moves with the map under it, and every card is drawn at the new zoom.
     */
    void moveWithViewport(double fromX, double fromY, double fromZoom, double x, double y, double zoom) {
        double ratio = zoom / fromZoom;
        List<Region> cards = new ArrayList<>(previews.values());
        cards.addAll(notePreviews.values());
        ProjectMapOverviewCard overview = overviewCard;
        if (overview != null) {
            cards.add(overview);
            overview.setMapScale(zoom);
        }
        for (Region solo : soloCards()) {
            if (solo instanceof MapFloatingCard floating) {
                cards.add(floating); // the History card, the Dependencies card
                floating.setMapScale(zoom);
            }
        }
        for (Region card : cards) {
            card.relocate(x + (card.getLayoutX() - fromX) * ratio, y + (card.getLayoutY() - fromY) * ratio);
        }
        previews.values().forEach(card -> card.setMapScale(zoom));
        notePreviews.values().forEach(card -> card.setMapScale(zoom));
    }

    /** Whether {@code node} is inside an open code or note card, a sticky note or a card's Structure column. */
    static boolean holdsFocus(Node node) {
        return cardFocusInCard(node);
    }

    /** After a Tab out of a card: when the keyboard left the map area altogether, it goes to the map. */
    void keepFocusOnMap() {
        cardFocusKeptOnMap();
    }

    void dispose() {
        disposed = true;
        cancelDeclarationMove();
        closeAllPreviews();
        connectorPulse.stop();
    }

    /**
     * The outline of {@code file} arrived or changed: the Structure column of its card lists it, and each of its
     * declaration cards follows its declaration's lines (to its new path when its parameter list was edited). The
     * other cards are left alone (review 3, D8).
     */
    void outlineChanged(Path file) {
        if (disposed || file == null) {
            return;
        }
        cardStructures.forEach((card, column) -> {
            if (file.equals(card.path())) {
                column.update(structure.view(file));
            }
        });
        for (Map.Entry<Path, ProjectMapPreview> entry : List.copyOf(previews.entrySet())) {
            if (!ProjectMapStructure.isSymbolPath(entry.getKey())
                    || !entry.getKey().startsWith(file)) {
                continue;
            }
            Path key = entry.getKey();
            ProjectMapStructure.Node node = structure.node(key);
            ProjectMapPreview card = entry.getValue();
            Path next = node == null ? structure.successor(key) : null;
            if (next != null && !previews.containsKey(next)) {
                // Its parameter list was edited: the card follows the declaration to its new path.
                previews.remove(key);
                previews.put(next, card);
                node = structure.node(next);
            }
            if (node == null) {
                if (structure.hasOutline(file)) {
                    card.setSymbolMissing(true); // keeps its last lines: they may be the last copy to read
                }
                continue;
            }
            card.setSymbolMissing(false);
            card.setRange(rangeOf(node.symbol()));
            updateCardNotes(card); // the declaration's lines may hold other notes now
            cardsUpdateStepping(card);
        }
        links.outlineChanged(file); // a question waiting to group its places by declaration
        declarationMoveOutlined(file); // a file card's caret waiting to move to a declaration
    }

    /** Preview and note cards of a renamed file, or of files under a renamed folder, follow it (C21). */
    void pathRenamed(Path from, Path to) {
        Path source = ProjectMapModel.normalize(from);
        if (source == null) {
            return;
        }
        boolean moved = false;
        for (Map.Entry<Path, ProjectMapPreview> entry : List.copyOf(previews.entrySet())) {
            if (!entry.getKey().startsWith(source)) {
                continue;
            }
            ProjectMapPreview card = entry.getValue();
            previews.remove(entry.getKey());
            Path key = ProjectMapModel.remap(entry.getKey(), from, to);
            Path file = card.path();
            if (key == null || file == null) {
                closePreview(card);
                continue;
            }
            Path movedFile = ProjectMapModel.remap(file, from, to);
            if (movedFile != null) {
                card.pathRenamed(movedFile);
                if (!ProjectMapStructure.isSymbolPath(key)) {
                    card.setStructureAvailable(structure.supports(movedFile)); // another name, maybe another language
                }
                ProjectMapCardStructure column = cardStructures.get(card);
                if (column != null) {
                    column.relabel();
                    structure.ensure(movedFile);
                    column.update(structure.view(movedFile));
                }
            }
            previews.put(key, card);
            cardsRequestLastChange(card);
            moved = true;
        }
        for (Map.Entry<Path, ProjectMapNotePreview> entry : List.copyOf(notePreviews.entrySet())) {
            if (!entry.getKey().startsWith(source)) {
                continue;
            }
            notePreviews.remove(entry.getKey());
            Path key = ProjectMapModel.remap(entry.getKey(), from, to);
            if (key == null) {
                closeNotePreview(entry.getValue());
                continue;
            }
            entry.getValue().pathRenamed(key);
            notePreviews.put(key, entry.getValue());
            moved = true;
        }
        if (moved) {
            repaintPreviewConnectors();
            scheduleCardRefresh(); // note cards re-read the store once the notes have moved with the file
        }
    }

    private static ProjectMapPreview.Range rangeOf(ProjectMapModel.Symbol symbol) {
        return new ProjectMapPreview.Range(symbol.label(), symbol.line(), symbol.startLine(), symbol.endLine());
    }

    /** The editor window's editor font for cards, following the window from now on; null when not known. */
    private javafx.scene.text.@Nullable Font cardEditorFont() {
        javafx.stage.Window window;
        try {
            window = cardFontSource.get();
        } catch (RuntimeException unknown) {
            window = null;
        }
        if (window != cardFontWindow) {
            if (cardFontWindow != null) {
                cardFontWindow.getProperties().removeListener(weakCardFontListener);
            }
            cardFontWindow = window;
            if (window != null) {
                window.getProperties().addListener(weakCardFontListener);
            }
        }
        return window != null
                        && window.getProperties().get(ProjectMapPreview.EDITOR_FONT_KEY)
                                instanceof javafx.scene.text.Font font
                ? font
                : null;
    }

    /** The path a code card is open under now, or null when it is not open. */
    private @Nullable Path cardsKey(ProjectMapPreview card) {
        for (Map.Entry<Path, ProjectMapPreview> entry : previews.entrySet()) {
            if (entry.getValue() == card) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** The path a card is open under now (a declaration card follows its declaration), else {@code fallback}. */
    private Path cardKeyOf(ProjectMapPreview card, Path fallback) {
        for (Map.Entry<Path, ProjectMapPreview> entry : previews.entrySet()) {
            if (entry.getValue() == card) {
                return entry.getKey();
            }
        }
        return fallback;
    }

    /** Usage counts for the cards' Structure columns (gap 11); null: none shown. */
    private @Nullable MapUsageCounts usages;

    void setUsages(@Nullable MapUsageCounts counts) {
        usages = counts;
        cardStructures.values().forEach(column -> column.setUsages(counts));
    }

    /** Usage counts came or went: the cards' Structure columns show them. */
    void usagesChanged() {
        cardStructures.values().forEach(ProjectMapCardStructure::usagesChanged);
    }

    /** {@code card}'s Structure column, or null. For tests. */
    @Nullable
    ProjectMapCardStructure cardStructureForTest(ProjectMapPreview card) {
        return cardStructures.get(card);
    }

    /** The files whose card has its Structure column out. */
    Set<Path> structureColumnFiles() {
        Set<Path> files = new HashSet<>();
        for (ProjectMapPreview card : cardStructures.keySet()) {
            Path file = card.path();
            if (file != null) {
                files.add(file);
            }
        }
        return files;
    }

    /**
     * The files a declaration card is open for, or whose card has its Structure column out: their outlines are
     * kept to follow the cards' lines and to list them.
     */
    private Set<Path> cardOutlineFiles() {
        Set<Path> files = new HashSet<>();
        for (Path key : previews.keySet()) {
            if (ProjectMapStructure.isSymbolPath(key)) {
                Path file = structure.fileOf(key);
                if (file != null) {
                    files.add(file);
                }
            }
        }
        for (ProjectMapPreview card : cardStructures.keySet()) {
            Path file = card.path();
            if (file != null) {
                files.add(file);
            }
        }
        files.addAll(links.outlineFiles());
        DeclarationMove move = declarationMove;
        if (move != null) {
            files.add(move.file());
        }
        for (ProjectMapPreview card : previews.values()) {
            Path file = card.path();
            if (card.range() == null && file != null && structure.hasOutline(file)) {
                files.add(file); // a file card whose caret moved by declarations: its outline is kept up to date
            }
        }
        return files;
    }

    /**
     * A declaration row: opens (or focuses) the card of its lines. The card reads and colours the whole file
     * and shows the declaration's block with its doc comment, numbered as the file numbers it.
     */
    private @Nullable ProjectMapPreview previewSymbol(
            ProjectMapModel.Entry entry, ProjectMapPreview.@Nullable Placement placed) {
        return previewSymbol(entry, placed, null);
    }

    /**
     * A declaration row's card, placed by {@code placed} when given (a card brought back). {@code from} is the
     * Structure column it was asked for from, which it stands beside and connects to; an open card asked for from the
     * other column moves there. Null leaves where it belongs as it was.
     */
    private @Nullable ProjectMapPreview previewSymbol(
            ProjectMapModel.Entry entry, ProjectMapPreview.@Nullable Placement placed, @Nullable From from) {
        ProjectMapModel.Symbol symbol = entry.symbol();
        if (symbol == null) {
            return null;
        }
        Path key = entry.path();
        ProjectMapPreview existing = previews.get(key);
        if (existing != null) {
            touchCard(existing, true);
            if (from != null && (from == From.CARD_COLUMN) != cardColumnCards.contains(existing)) {
                cardFollowColumn(existing, key, from);
            }
            if (placed == null) {
                existing.focusContent();
            }
            return existing;
        }
        Path file = symbol.file();
        if (!makeRoomForCard(cardGroupOwner(file))) {
            host.status(tr("status.projectMap.allCardsPinned", MAX_OPEN_PREVIEWS));
            return null;
        }
        ProjectMapPreview preview = createPreview(key);
        if (from == From.CARD_COLUMN) {
            cardColumnCards.add(preview);
        }
        preview.setOnOpenAt((path, line) -> host.openAt(path, line));
        preview.setOnHistory(() -> openHistory(preview));
        preview.setOnStep(direction -> cardsStep(preview, direction));
        preview.setOnShowInStructure(() -> cardsShowInStructure(preview));
        preview.setOnOpenNarrowed(() -> {
            ProjectMapStructure.Node node = structure.node(cardKeyOf(preview, key));
            ProjectMapModel.Symbol current = node == null ? symbol : node.symbol();
            com.editora.text.FoldRegions.Region block = current.block();
            host.openNarrowed(
                    file,
                    block != null ? block : new com.editora.text.FoldRegions.Region(current.line(), current.endLine()));
        });
        preview.showRange(
                file,
                rangeOf(symbol),
                host.content(file),
                placed != null
                        ? (width, height, parentWidth, parentHeight) -> placed
                        : (width, height, parentWidth, parentHeight) -> openingPlacement(
                                preview,
                                key,
                                width,
                                height,
                                ProjectMapPreview.MIN_WIDTH,
                                ProjectMapPreview.MIN_HEIGHT,
                                parentWidth,
                                parentHeight));
        updateCardNotes(preview);
        cardsUpdateStepping(preview);
        cardsRequestLastChange(preview);
        scheduleCardRefresh();
        return preview;
    }

    /**
     * An open declaration card asked for from the other Structure column — the Map's or its file card's — goes
     * beside that column, as a new card would, and its connector starts there (Map Structure previews). A pinned card
     * keeps its place; only its connector moves.
     */
    private void cardFollowColumn(ProjectMapPreview card, Path key, From from) {
        if (from == From.CARD_COLUMN) {
            cardColumnCards.add(card);
        } else {
            cardColumnCards.remove(card);
        }
        if (!pinnedCards.contains(card)) {
            card.placeAgain((width, height, parentWidth, parentHeight) -> previewPlacement(
                    card,
                    key,
                    width,
                    height,
                    ProjectMapPreview.MIN_WIDTH,
                    ProjectMapPreview.MIN_HEIGHT,
                    parentWidth,
                    parentHeight));
        }
        repaintPreviewConnectors();
    }

    /** A file's card under its normalized path {@code selected}, showing {@code shown} (the row's own path). */
    private @Nullable ProjectMapPreview previewFile(Path selected, Path shown) {
        return previewFile(selected, shown, null);
    }

    /**
     * {@link #previewFile(Path, Path)}, placed by {@code placed} when given (a card brought back, B6). Null when every
     * open card is pinned: the status line says to unpin one.
     */
    private @Nullable ProjectMapPreview previewFile(
            Path selected, Path shown, ProjectMapPreview.@Nullable Placement placed) {
        ProjectMapPreview preview = previews.get(selected);
        if (preview != null) {
            touchCard(preview, true);
            if (placed == null) {
                preview.focusContent();
            }
            return preview;
        }
        if (!makeRoomForCard(null)) {
            host.status(tr("status.projectMap.allCardsPinned", MAX_OPEN_PREVIEWS));
            return null;
        }
        preview = createPreview(selected);
        preview.setStructureAvailable(structure.supports(selected)); // as the row has its Structure icon
        ProjectMapPreview selectedPreview = preview;
        preview.showFile(
                shown,
                host.content(shown),
                placed != null
                        ? (width, height, parentWidth, parentHeight) -> placed
                        : (width, height, parentWidth, parentHeight) -> openingPlacement(
                                selectedPreview,
                                shown,
                                width,
                                height,
                                ProjectMapPreview.MIN_WIDTH,
                                ProjectMapPreview.MIN_HEIGHT,
                                parentWidth,
                                parentHeight));
        updateCardNotes(preview);
        cardsRequestLastChange(preview);
        scheduleCardRefresh(); // starts following the buffer when the card mirrors one
        return preview;
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
        if (!makeRoomForCard(null)) {
            host.status(tr("status.projectMap.allCardsPinned", MAX_OPEN_PREVIEWS));
            return;
        }
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
            host.focusMap();
        });
        preview.setOnBlankRejected(() -> host.status(tr("status.projectMap.noteBlankRestored")));
        preview.setOnOpenAt((file, line) -> host.openAt(file, line)); // a note's heading opens the file there
        preview.setMapScale(host.zoom());
        installPreviewListeners(preview);
        notePreviews.put(selected, preview);
        cardTouches.put(preview, ++cardTouchSequence);
        canvasHost.getChildren().add(preview);
        ProjectMapPreview.Placement placement = openingPlacement(
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

    /** A new, empty code card under {@code key}, room made for it; it is shown by its caller. */
    ProjectMapPreview createPreview(Path path) {
        // A declaration card of a file whose card has its Structure column out belongs to that card's group.
        makeRoomForCard(ProjectMapStructure.isSymbolPath(path) ? cardGroupOwner(structure.fileOf(path)) : null);
        ProjectMapPreview preview = new ProjectMapPreview(host::openFile);
        preview.setMapScale(host.zoom());
        preview.setEditorFont(cardEditorFont());
        preview.setInitialContentZoom(cardTextZoom); // the last −/+ zoom, so it need not be set on every card
        preview.setOnContentZoomed(zoom -> cardTextZoom = zoom);
        preview.setMarkerActions(previewMarkerActions);
        preview.setGutterSource(gutterSource);
        preview.setFollowActions(followActions);
        preview.setOnDependencies(() -> {
            Path shown = preview.path();
            if (shown != null) {
                host.showDependencies(preview, shown);
            }
        });
        preview.setLinkActions(links.available() ? cardNavigation : null);
        preview.setOnClose(() -> closePreview(preview));
        preview.setOnActivate(() -> touchCard(preview, true));
        preview.setOnTouch(() -> touchCard(preview, false));
        preview.setOnEscape(() -> {
            closePreview(preview);
            host.focusMap();
        });
        preview.setOnNotesToggled(shown -> showCardNotes(preview, shown));
        preview.setOnStructureToggled(shown -> showCardStructure(preview, shown));
        preview.setOnDeclarationMove(direction -> cardsMoveCaret(preview, direction));
        preview.setOnFocusStructure(() -> {
            ProjectMapCardStructure column = cardStructures.get(preview);
            if (column != null) {
                column.focus();
            }
        });
        preview.setOnPinned(pinned -> {
            if (pinned) {
                pinnedCards.add(preview);
            } else {
                pinnedCards.remove(preview);
            }
        });
        preview.setNotesSuppressed(host.hideOpenNotes());
        preview.setOnShowSuppressedNotes(() -> host.showOpenNotes()); // the Notes button asks for them
        preview.setOnMoveToFreeSpace(() -> cardMoveToFreeSpace(preview, path));
        preview.setViewsMenu(() -> host.viewsCardMenu(preview));
        preview.setOnTextShown(() -> {
            updateCardNotes(preview); // stored notes are found again in the new text
            restyleCardStructure(preview);
        });
        preview.setOnStylesShown(() -> {
            restyleCardStructure(preview); // its Structure column borrows the colours
            askCardAids(preview); // and the server's semantic tokens and inlay hints go over them (round 2)
        });
        preview.setStickyScroll(cardAids.get().stickyScroll());
        // A sticky note's connector meets its line in the text, which scrolls under it.
        preview.editor().estimatedScrollYProperty().addListener((obs, old, value) -> {
            if (stickyNotes.containsKey(preview)) {
                connectorsMoved();
            }
        });
        installPreviewListeners(preview);
        previews.put(path, preview);
        cardTouches.put(preview, ++cardTouchSequence);
        canvasHost.getChildren().add(preview);
        return preview;
    }

    private void installPreviewListeners(Region preview) {
        // A card's place, size and visibility change many times a frame while it is dragged, panned or zoomed:
        // they only mark the connectors, which are drawn once for the pulse (review 3, D5).
        javafx.beans.InvalidationListener moved = observable -> connectorsMoved();
        preview.layoutXProperty().addListener(moved);
        preview.layoutYProperty().addListener(moved);
        preview.widthProperty().addListener(moved);
        preview.heightProperty().addListener(moved);
        preview.visibleProperty().addListener(moved);
    }

    /** Records use of an open card for eviction, and optionally raises it above the others. */
    private void touchCard(Region card, boolean raise) {
        if (!cardTouches.containsKey(card)) {
            return; // closed meanwhile
        }
        cardTouches.put(card, ++cardTouchSequence);
        if (raise) {
            card.toFront();
            if (card instanceof ProjectMapPreview code) {
                attachments(code).forEach(CardAttachment::raise); // what is stuck on it stays on it
            }
            links.linksRaiseChips(); // a "+N more" chip is never under a card (review 4, C3)
        }
    }

    /** What is stuck on {@code card}: its Structure column and its sticky notes, when out. */
    List<CardAttachment> attachments(ProjectMapPreview card) {
        List<CardAttachment> stuck = new ArrayList<>(2);
        ProjectMapCardStructure column = cardStructures.get(card);
        if (column != null) {
            stuck.add(column);
        }
        ProjectMapStickyNotes notes = stickyNotes.get(card);
        if (notes != null) {
            stuck.add(notes);
        }
        stuck.addAll(links.attachments(card)); // the edges of its questions' answers
        CardAttachment views = viewsAttachments.get(card);
        if (views != null) {
            stuck.add(views);
        }
        return stuck;
    }

    /** Every attachment of every open code card. */
    private List<CardAttachment> allAttachments() {
        List<CardAttachment> all = new ArrayList<>(cardStructures.values());
        all.addAll(stickyNotes.values());
        all.addAll(links.allAttachments());
        all.addAll(viewsAttachments.values());
        return all;
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
            // Found again in the card's text first: a file changed outside Editora moved them (C5).
            return ProjectMapStickyNotes.notesFor(
                    ProjectMapStickyNotes.reanchored(actions.personalNotes(file), card.fullText()), card.range());
        } catch (RuntimeException failed) {
            ProjectMapLog.unexpected("reading a card's Personal Notes", failed);
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
                        this::connectorsMoved,
                        () -> host.status(tr("status.projectMap.noteBlankRestored")),
                        () -> touchCard(card, false)));
        stuck.setOnAllClosed(() -> card.setNotesShown(false)); // every sheet closed: the Notes button goes off
        stuck.setOnMore(() -> {
            Path file = card.path();
            if (file != null) {
                previewNotes(file); // the note card lists every note of the file
            }
        });
        stuck.setHidden(host.hideOpenNotes());
        stuck.setReserved(which -> besideCard(card, which));
        stuck.show(cardNotes(card));
        touchCard(card, true);
    }

    /** What the card's other attachments take beside it on a side, in unzoomed units: its sticky notes go past. */
    private double besideCard(ProjectMapPreview card, CardAttachment.Side which) {
        double taken = 0;
        for (CardAttachment stuck : attachments(card)) {
            if (!(stuck instanceof ProjectMapStickyNotes)) {
                taken += stuck.reserved(which);
            }
        }
        return taken;
    }

    /**
     * The Structure button: sticks the file's Structure column beside the card, or takes it off. It goes on the
     * side away from the card's row, where the connector to the row does not cross it; a declaration clicked in it
     * opens its card beside it. Taking it off closes the declaration cards it opened, as closing the Map's own
     * Structure column closes those of its rows.
     */
    private void showCardStructure(ProjectMapPreview card, boolean shown) {
        Path file = card.path();
        if (!shown || file == null || canvasHost == null) {
            closeCardStructure(card);
            return;
        }
        ProjectMapCardStructure column = cardStructures.computeIfAbsent(
                card,
                ignored -> new ProjectMapCardStructure(
                        card,
                        canvasHost,
                        node -> cardRevealDeclaration(card, node),
                        node -> {
                            touchCard(card, false); // the column's card is in use: never the one evicted
                            previewSymbol(symbolEntry(node), null, From.CARD_COLUMN);
                        },
                        node -> host.symbolMenu(symbolEntry(node), () -> {
                            touchCard(card, false);
                            previewSymbol(symbolEntry(node), null, From.CARD_COLUMN); // beside this column
                        }),
                        () -> touchCard(card, false),
                        this::connectorsMoved,
                        () -> reserveBesideCard(card)));
        structure.ensure(file);
        column.setUsages(usages);
        // Always on the card's right, so the declarations read on from the card (Map Structure right).
        column.show(CardAttachment.Side.RIGHT, false);
        column.update(structure.view(file)); // shown first: its width is measured in the rows' font
        if (!restoringCard) {
            nudgeForColumn(card, column); // a card brought back keeps its place, column and all (B6)
        }
        reserveBesideCard(card); // the sheets step past the column
        touchCard(card, true);
        scheduleCardRefresh(); // the outline follows the buffer from now on
    }

    /**
     * A Shift+click or Shift+Enter on a declaration in a file card's Structure column: the card scrolls to the
     * declaration and marks its lines (review 3, E8). A declaration past what the card shows opens its own card
     * instead.
     */
    private void cardRevealDeclaration(ProjectMapPreview card, ProjectMapStructure.Node node) {
        touchCard(card, false);
        if (!card.revealRange(rangeOf(node.symbol()))) {
            previewSymbol(symbolEntry(node), null, From.CARD_COLUMN);
        }
    }

    // --- Map explore round, cards: declaration stepping, Show in Structure, Git context ------------------------

    /** Every declaration of {@code file}'s outline, depth-first: the order ‹ › step through (position 8). */
    private List<ProjectMapStructure.Node> cardsDeclarations(@Nullable Path file) {
        List<ProjectMapStructure.Node> all = new ArrayList<>();
        if (file != null) {
            cardsFlatten(structure.view(file).roots(), all);
        }
        return all;
    }

    private static void cardsFlatten(List<ProjectMapStructure.Node> nodes, List<ProjectMapStructure.Node> out) {
        for (ProjectMapStructure.Node node : nodes) {
            out.add(node);
            cardsFlatten(node.children(), out);
        }
    }

    /** Where a declaration card's key stands among its file's declarations, or -1. */
    private static int cardsIndexOf(List<ProjectMapStructure.Node> declarations, Path key) {
        for (int i = 0; i < declarations.size(); i++) {
            if (declarations.get(i).path().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /** Tells a declaration card whether its file has a declaration before and after the one it shows. */
    private void cardsUpdateStepping(ProjectMapPreview card) {
        Path key = cardsKey(card);
        if (key == null || !ProjectMapStructure.isSymbolPath(key)) {
            card.setDeclarationStepping(false, false);
            return;
        }
        List<ProjectMapStructure.Node> declarations = cardsDeclarations(structure.fileOf(key));
        int at = cardsIndexOf(declarations, key);
        card.setDeclarationStepping(at > 0, at >= 0 && at < declarations.size() - 1);
    }

    /**
     * A declaration card's ‹ or › (Alt+Page Up / Alt+Page Down): the card shows the previous or next declaration of
     * its file where it stands, under that declaration's key. Another card open on that declaration closes: it would
     * show the same lines twice.
     */
    void cardsStep(ProjectMapPreview card, int direction) {
        Path key = cardsKey(card);
        if (disposed || key == null || !ProjectMapStructure.isSymbolPath(key)) {
            return;
        }
        List<ProjectMapStructure.Node> declarations = cardsDeclarations(structure.fileOf(key));
        int at = cardsIndexOf(declarations, key);
        int next = at + (direction < 0 ? -1 : 1);
        if (at < 0 || next < 0 || next >= declarations.size()) {
            return;
        }
        ProjectMapStructure.Node target = declarations.get(next);
        ProjectMapPreview other = previews.get(target.path());
        if (other != null && other != card && pinnedCards.contains(other)) {
            // The declaration is open on a pinned card, which is kept (review 4, position 3): the keyboard goes there,
            // and this card, unless pinned too, closes rather than show the same lines twice.
            touchCard(other, true);
            other.focusContent();
            if (!pinnedCards.contains(card)) {
                closePreview(card);
            }
            return;
        }
        if (other != null && other != card) {
            closePreview(other);
        }
        // What the card's questions found was about the declaration it showed (review 4, C5).
        List<ProjectMapPreview> results = links.cardChanged(card);
        previews.remove(key);
        previews.put(target.path(), card);
        card.showDeclaration(rangeOf(target.symbol()));
        touchCard(card, false);
        updateCardNotes(card);
        cardsUpdateStepping(card);
        cardsRequestLastChange(card);
        linksCloseResults(results);
        repaintPreviewConnectors(); // its connector now meets the new declaration's row
    }

    /** A file card's caret move waiting for its file's outline (see {@link #cardsMoveCaret}). */
    private record DeclarationMove(
            ProjectMapPreview card, Path file, int direction, javafx.animation.PauseTransition timeout) {}

    private @Nullable DeclarationMove declarationMove;

    /**
     * A file card's Alt+Page Up / Alt+Page Down (card movement): the caret goes to the declaration before or after it,
     * as the file's outline has them. An outline not held yet is asked for, and the caret moves when it comes, for up
     * to {@link MapCardLinks#OUTLINE_WAIT}. A declaration past what the card holds opens as its own card; a file with
     * no outline, or no declaration that way, says so in the status.
     */
    void cardsMoveCaret(ProjectMapPreview card, int direction) {
        Path file = card.path();
        if (disposed || file == null || !cardTouches.containsKey(card)) {
            return;
        }
        cancelDeclarationMove();
        if (!structure.supports(file)) {
            host.status(tr("status.projectMap.noDeclarations"));
            return;
        }
        structure.ensure(file);
        if (structure.view(file).problem() != ProjectMapStructure.Problem.LOADING) {
            moveCaretNow(card, file, direction);
            return;
        }
        javafx.animation.PauseTransition timeout = new javafx.animation.PauseTransition(MapCardLinks.OUTLINE_WAIT);
        DeclarationMove move = new DeclarationMove(card, file, direction, timeout);
        declarationMove = move;
        timeout.setOnFinished(event -> {
            if (declarationMove == move) {
                declarationMove = null;
                host.status(tr("status.projectMap.noDeclarations"));
            }
        });
        timeout.playFromStart();
    }

    private void cancelDeclarationMove() {
        DeclarationMove move = declarationMove;
        declarationMove = null;
        if (move != null) {
            move.timeout().stop();
        }
    }

    /** A waiting caret move, now that {@code file}'s outline came. */
    private void declarationMoveOutlined(Path file) {
        DeclarationMove move = declarationMove;
        if (move == null
                || !move.file().equals(file)
                || structure.view(file).problem() == ProjectMapStructure.Problem.LOADING) {
            return;
        }
        cancelDeclarationMove();
        if (cardTouches.containsKey(move.card()) && file.equals(move.card().path())) {
            moveCaretNow(move.card(), file, move.direction());
        }
    }

    private void moveCaretNow(ProjectMapPreview card, Path file, int direction) {
        List<ProjectMapStructure.Node> declarations = cardsDeclarations(file);
        if (declarations.isEmpty()) {
            host.status(tr("status.projectMap.noDeclarations"));
            return;
        }
        ProjectMapStructure.Node target = declarationFrom(declarations, card.caretFileLine(), direction);
        if (target == null) {
            host.status(tr(
                    direction < 0 ? "status.projectMap.noDeclarationBefore" : "status.projectMap.noDeclarationAfter"));
            return;
        }
        ProjectMapModel.Symbol symbol = target.symbol();
        touchCard(card, false);
        if (!card.moveCaretToLine(symbol.line(), ProjectMapView.followName(symbol.label()))) {
            previewSymbol(symbolEntry(target), null); // past what the card holds: the declaration's own card
        }
    }

    /**
     * The declaration nearest before ({@code direction < 0}) or after {@code line} — the file's 0-based line — by the
     * line its name is on; null when there is none that way. Pure.
     */
    static ProjectMapStructure.@Nullable Node declarationFrom(
            List<ProjectMapStructure.Node> declarations, int line, int direction) {
        ProjectMapStructure.Node best = null;
        for (ProjectMapStructure.Node node : declarations) {
            int at = node.symbol().line();
            boolean ahead = direction < 0 ? at < line : at > line;
            if (ahead
                    && (best == null
                            || (direction < 0
                                    ? at > best.symbol().line()
                                    : at < best.symbol().line()))) {
                best = node;
            }
        }
        return best;
    }

    /**
     * A declaration card's Show in Structure (position 8): its row is selected in its file's Structure column — the
     * file card's column when that card has it out, else the Map's own column of the file (opened, with the columns
     * that lead to it), else the file card's, which is opened for it.
     */
    void cardsShowInStructure(ProjectMapPreview card) {
        Path key = cardsKey(card);
        Path file = key == null ? null : structure.fileOf(key);
        if (disposed || key == null || file == null) {
            return;
        }
        ProjectMapPreview fileCard = previews.get(file);
        ProjectMapCardStructure column = fileCard == null ? null : cardStructures.get(fileCard);
        if (fileCard != null && column != null) {
            touchCard(fileCard, true);
            column.select(key);
            return;
        }
        if (host.showInStructure(file, key)) {
            return;
        }
        ProjectMapPreview opened = fileCard != null ? fileCard : openFileCard(file);
        if (opened == null || !opened.structureAvailable()) {
            return;
        }
        opened.setStructureShown(true);
        ProjectMapCardStructure shown = cardStructures.get(opened);
        if (shown != null) {
            shown.select(key);
        }
    }

    /**
     * Asks Git, off the FX thread, for the last change of what {@code card} shows — its file, or a declaration card's
     * lines — for the end of its status line (position 9). Cached per HEAD by the Git service; a later ask wins.
     */
    private void cardsRequestLastChange(ProjectMapPreview card) {
        ProjectMapPreview.FollowActions actions = followActions;
        Path file = card.path();
        if (actions == null || file == null || disposed) {
            return;
        }
        ProjectMapPreview.Range range = card.range();
        long ask = ++cardsGitSequence;
        cardsGitRequests.put(card, ask);
        try {
            actions.lastChange(
                    file, range == null ? -1 : range.startLine(), range == null ? -1 : range.endLine(), change -> {
                        Long latest = cardsGitRequests.get(card);
                        if (latest != null && latest == ask && file.equals(card.path())) {
                            card.setLastChange(change);
                        }
                    });
        } catch (RuntimeException failed) {
            ProjectMapLog.unexpected("asking Git for a card's last change", failed);
        }
    }

    /** How much what lies outside the map area weighs against a Structure column's side, per pixel: unseen. */
    private static final double OUTSIDE_WEIGHT = 8;

    /**
     * What a card's Structure column would cover on {@code which} side, as drawn: the map's columns (its row's own and
     * those leading to it heaviest, when its row is on the map), the zoom bar and the overview, other cards and what is
     * stuck on them, and the part of it outside the map area, which would not be seen. Over the card it covers the
     * card's own text, so that side is the last resort.
     */
    private double columnSideCost(
            ProjectMapCardStructure column, CardAttachment.Side which, List<double[]> obstacles, boolean rowAfter) {
        double[] r = column.boundsAt(which);
        double area = Math.max(0, r[2] * r[3]);
        double cost = cardOverlapCost(r[0], r[1], r[2], r[3], obstacles);
        double inside = cardOverlapCost(
                r[0], r[1], r[2], r[3], List.of(new double[] {0, 0, canvasHost.getWidth(), canvasHost.getHeight(), 1}));
        cost += (area - inside) * OUTSIDE_WEIGHT;
        if (which == CardAttachment.Side.OVER) {
            cost += area * 3;
        }
        // Toward the row the column crosses the card's connector: between sides that cover alike, the other one.
        if (which == (rowAfter ? CardAttachment.Side.RIGHT : CardAttachment.Side.LEFT)) {
            cost += area * 0.02;
        }
        return cost;
    }

    /** What a card's Structure column weighs its sides against (see {@link #columnSideCost}). */
    private List<double[]> columnObstacles(ProjectMapPreview card) {
        Path file = card.path();
        Site site = file == null ? null : host.site(file);
        List<double[]> obstacles =
                new ArrayList<>(site != null && site.row() != null ? site.obstacles() : host.columns());
        obstacles.addAll(host.chrome());
        List<Region> others = new ArrayList<>(previews.values());
        others.addAll(notePreviews.values());
        others.addAll(soloCards());
        for (Region other : others) {
            if (other != card && other.isVisible() && other.getWidth() > 0) {
                double scale = cardScale(other);
                obstacles.add(new double[] {
                    other.getLayoutX(),
                    other.getLayoutY(),
                    other.getWidth() * scale,
                    other.getHeight() * scale,
                    CARD_WEIGHT_CARD
                });
            }
        }
        for (CardAttachment stuck : allAttachments()) {
            double[] drawn = stuck.card() == card ? null : stuck.bounds();
            if (drawn != null) {
                obstacles.add(new double[] {drawn[0], drawn[1], drawn[2], drawn[3], CARD_WEIGHT_CARD});
            }
        }
        return obstacles;
    }

    /**
     * A Structure column opened on a card, always on its right: the card first steps left as far as the column needs to
     * fit inside the map area. When the column would still cover much of the map or another card, the card moves
     * where the card and its column together cover less, as Move to Free Space places a card, when there is such a
     * spot (review 3, C9). The map is not panned.
     */
    private void nudgeForColumn(ProjectMapPreview card, ProjectMapCardStructure column) {
        Path file = card.path();
        if (file == null || !card.isVisible() || card.getWidth() <= 0) {
            return;
        }
        double[] drawn = column.boundsAt(CardAttachment.Side.RIGHT);
        double over = drawn[0] + drawn[2] - (canvasHost.getWidth() - ProjectMapPreview.EDGE_MARGIN);
        double back = Math.min(over, card.getLayoutX() - ProjectMapPreview.EDGE_MARGIN);
        if (back > 0.5) {
            card.relocate(card.getLayoutX() - back, card.getLayoutY());
            drawn = column.boundsAt(CardAttachment.Side.RIGHT);
        }
        boolean rowAfter = cardRowAfter(card, file);
        double cost = columnSideCost(column, CardAttachment.Side.RIGHT, columnObstacles(card), rowAfter);
        if (cost <= drawn[2] * drawn[3] * 0.15) {
            return; // it covers little where it stands
        }
        double together = card.getWidth() + column.width() + ProjectMapCardStructure.GAP;
        ProjectMapPreview.Placement spot = previewPlacement(
                card,
                file,
                together,
                card.getHeight(),
                together,
                Math.min(card.getHeight(), ProjectMapPreview.MIN_HEIGHT),
                canvasHost.getWidth(),
                canvasHost.getHeight());
        if (spot.width() < together - 0.5) {
            return; // no spot holds both
        }
        double x = card.getLayoutX();
        double y = card.getLayoutY();
        card.relocate(spot.x(), spot.y());
        boolean nextRowAfter = cardRowAfter(card, file);
        if (columnSideCost(column, CardAttachment.Side.RIGHT, columnObstacles(card), nextRowAfter) >= cost) {
            card.relocate(x, y); // no better there: it stays where it was
            return;
        }
        ProjectMapPreview.announceGeometry(card, card.mapScale());
    }

    /** Places a card's sticky notes again past what stands beside the card now. */
    private void reserveBesideCard(ProjectMapPreview card) {
        ProjectMapStickyNotes notes = stickyNotes.get(card);
        if (notes != null) {
            notes.setReserved(which -> besideCard(card, which));
        }
    }

    private void restyleCardStructure(ProjectMapPreview card) {
        ProjectMapCardStructure column = cardStructures.get(card);
        if (column != null) {
            column.restyle();
        }
    }

    /** Whether the card's row is after it along the flow, so its Structure column goes on the left. */
    private boolean cardRowAfter(ProjectMapPreview card, Path file) {
        double[] row = host.rowBox(file);
        if (row == null) {
            return false;
        }
        double cardMiddle = card.getLayoutX() + card.getWidth() * card.mapScale() / 2;
        return row[0] + row[2] / 2 > cardMiddle;
    }

    private void closeCardStructure(ProjectMapPreview card) {
        ProjectMapCardStructure column = cardStructures.remove(card);
        if (column == null) {
            return;
        }
        boolean focused = column.contains(
                canvasHost.getScene() == null ? null : canvasHost.getScene().getFocusOwner());
        column.dispose();
        reserveBesideCard(card);
        if (focused && cardTouches.containsKey(card)) {
            card.focusContent();
        }
        scheduleOrphanCheck(); // the declaration cards it opened go with it
    }

    /** A declaration of a card's Structure column as a Map row, for the row's card and menu. */
    private static ProjectMapModel.Entry symbolEntry(ProjectMapStructure.Node node) {
        Path parent = node.path().getParent();
        return ProjectMapModel.Entry.symbol(node.path(), parent == null ? node.path() : parent, 0, node.symbol());
    }

    /** The Structure column of a file card, when it is out: for a declaration card opened from it. */
    private @Nullable ProjectMapCardStructure cardStructureOf(@Nullable Path file) {
        if (file == null) {
            return null;
        }
        for (Map.Entry<ProjectMapPreview, ProjectMapCardStructure> entry : cardStructures.entrySet()) {
            if (file.equals(entry.getKey().path())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private void scheduleOrphanCheck() {
        if (!orphanCheckPending && !previews.isEmpty()) {
            orphanCheckPending = true;
            Platform.runLater(this::closeOrphanedCards);
        }
    }

    private void closeCardNotes(ProjectMapPreview card) {
        ProjectMapStickyNotes stuck = stickyNotes.remove(card);
        if (stuck != null) {
            stuck.dispose();
        }
    }

    /**
     * Closes the least recently used cards, of either kind, until one more fits under the shared limit. Cards go
     * by group: a file card with its Structure column out, and the declaration cards of its file, which the column
     * keeps. A group's file card goes only after its declaration cards (the oldest of them first), and {@code
     * keep} — the file card whose column a new declaration card comes from — never goes (review 3, C1).
     */
    private boolean makeRoomForCard(@Nullable ProjectMapPreview keep) {
        while (previews.size() + notePreviews.size() >= MAX_OPEN_PREVIEWS) {
            // A pinned card never goes (Map explore round, position 7).
            // Nor, while an answer opens its result cards, the card it was asked from or its other results (C4).
            Region oldest = oldestCard(card -> card != keep
                    && !soloCards().contains(card) // the Overview, History, Dependencies, Code Metrics
                    && !pinnedCards.contains(card)
                    && !links.linksKeepsWhileOpening(card));
            if (oldest instanceof ProjectMapPreview code && cardStructures.containsKey(code)) {
                Set<ProjectMapPreview> members = cardGroupMembers(code);
                Region member = oldestCard(card ->
                        members.contains(card) && !pinnedCards.contains(card) && !links.linksKeepsWhileOpening(card));
                if (member != null) {
                    oldest = member; // its owner waits until the cards it keeps are gone
                }
            }
            if (oldest instanceof ProjectMapPreview code) {
                closePreview(code);
            } else if (oldest instanceof ProjectMapNotePreview note) {
                closeNotePreview(note);
            } else {
                return false; // every card that could go is pinned: the new one is refused
            }
        }
        return true;
    }

    /**
     * Whether one more card fits under the shared limit, closing the least recently used unpinned card when it must;
     * false when every card that could go is pinned. For a caller that opens its own cards through {@link
     * #createPreview}.
     */
    boolean roomForCard() {
        return makeRoomForCard(null);
    }

    /** The least recently used open card among those {@code wanted} accepts, or null. */
    private @Nullable Region oldestCard(Predicate<Region> wanted) {
        Region oldest = null;
        long oldestTouch = Long.MAX_VALUE;
        for (Map.Entry<Region, Long> touch : cardTouches.entrySet()) {
            if (touch.getValue() < oldestTouch && wanted.test(touch.getKey())) {
                oldestTouch = touch.getValue();
                oldest = touch.getKey();
            }
        }
        return oldest;
    }

    /** The declaration cards a file card's Structure column keeps: those of its file. */
    private Set<ProjectMapPreview> cardGroupMembers(ProjectMapPreview owner) {
        Path file = owner.path();
        Set<ProjectMapPreview> members = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        if (file == null || !cardStructures.containsKey(owner)) {
            return members;
        }
        previews.forEach((key, card) -> {
            if (card != owner && ProjectMapStructure.isSymbolPath(key) && file.equals(structure.fileOf(key))) {
                members.add(card);
            }
        });
        return members;
    }

    /** The file card whose Structure column keeps a declaration card of {@code file}, if one is out. */
    private @Nullable ProjectMapPreview cardGroupOwner(@Nullable Path file) {
        if (file == null) {
            return null;
        }
        for (ProjectMapPreview card : cardStructures.keySet()) {
            if (file.equals(card.path())) {
                return card;
            }
        }
        return null;
    }

    private void closeNotePreview(ProjectMapNotePreview preview) {
        if (preview == null) {
            return;
        }
        boolean focused = focusReturnsFrom(preview);
        notePreviews.entrySet().removeIf(entry -> entry.getValue() == preview);
        cardTouches.remove(preview);
        if (canvasHost != null) {
            canvasHost.getChildren().remove(preview);
        }
        preview.dispose();
        repaintPreviewConnectors();
        focusReturnToMap(focused);
    }

    /**
     * Whether the keyboard is in {@code card} (or, for a code card, on one of its sticky notes): closing it
     * must then hand the keyboard back to the map. Left to the scene, it went to the window's first control,
     * the search field, and the next arrow key typed there (C18).
     */
    private boolean focusReturnsFrom(Region card) {
        Scene scene = canvasHost.getScene();
        Node owner = scene == null ? null : scene.getFocusOwner();
        if (owner == null) {
            return false;
        }
        if (card instanceof ProjectMapPreview code
                && attachments(code).stream().anyMatch(stuck -> stuck.contains(owner))) {
            return true; // its Structure column and its sheets are the card's own: they close with it
        }
        for (Node current = owner; current != null; current = current.getParent()) {
            if (current == card) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code node} is inside an open code or note card, or on a sticky note. */
    private static boolean cardFocusInCard(Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current instanceof ProjectMapPreview
                    || current instanceof ProjectMapNotePreview
                    || current instanceof ProjectMapOverviewCard
                    || current instanceof MapFloatingCard
                    || current.getStyleClass().contains("project-map-sticky-note")
                    || current.getStyleClass().contains("project-map-card-structure")
                    || current.getStyleClass().contains("project-map-link-more")) {
                return true;
            }
        }
        return false;
    }

    /** After a Tab out of a card: when the keyboard left the map area altogether, it goes to the map. */
    private void cardFocusKeptOnMap() {
        Scene scene = canvasHost.getScene();
        Node owner = scene == null ? null : scene.getFocusOwner();
        for (Node current = owner; current != null; current = current.getParent()) {
            if (current == canvasHost) {
                return;
            }
        }
        if (!disposed && scene != null) {
            host.focusMap();
        }
    }

    private void focusReturnToMap(boolean focused) {
        if (focused && !disposed) {
            host.focusMap();
        }
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
            entry.getValue().setVisible(file == null || !host.narrowsAway(file));
        }
        boolean notes = !host.hideOpenNotes();
        previews.values().forEach(card -> card.setNotesSuppressed(!notes)); // their Notes buttons say why
        notePreviews.forEach((path, card) -> card.setVisible(notes && !host.narrowsAway(path)));
        stickyNotes.values().forEach(stuck -> stuck.setHidden(!notes)); // a hidden card hides its own as well
        repaintPreviewConnectors();
    }

    private void closePreview(ProjectMapPreview preview) {
        if (preview == null || !cardTouches.containsKey(preview)) {
            return; // closed already (with the card it was found from, say)
        }
        boolean focused = focusReturnsFrom(preview);
        previews.entrySet().removeIf(entry -> entry.getValue() == preview);
        cardTouches.remove(preview);
        pinnedCards.remove(preview);
        cardColumnCards.remove(preview);
        pickedCards.remove(preview);
        cardsGitRequests.remove(preview);
        closeCardNotes(preview);
        closeCardStructure(preview);
        host.cardClosed(preview);
        // Its edges, and the edge to it from the card it was found from; the result cards it opened go with it.
        List<ProjectMapPreview> results = links.cardClosed(preview);
        CardAttachment views = viewsAttachments.remove(preview);
        if (views != null) {
            views.dispose();
        }
        if (canvasHost != null) {
            canvasHost.getChildren().remove(preview);
        }
        preview.dispose();
        repaintPreviewConnectors();
        focusReturnToMap(focused);
        linksCloseResults(results);
    }

    /**
     * Closes the result cards an answer opened, once the card it was asked from closed or stepped (review 4, C11):
     * not a pinned one, nor one another open card's answer still holds.
     */
    private void linksCloseResults(List<ProjectMapPreview> results) {
        for (ProjectMapPreview result : results) {
            if (cardTouches.containsKey(result) && !pinnedCards.contains(result) && !links.isResult(result)) {
                closePreview(result);
            }
        }
    }

    private void closeAllPreviews() {
        cardRefresh.stop();
        cardPoll.stop();
        cardDiskRefresh.stop();
        cardTouches.clear();
        pinnedCards.clear();
        pickedCards.clear();
        cardsGitRequests.clear();
        List<ProjectMapPreview> open = List.copyOf(previews.values());
        open.forEach(host::cardClosed);
        previews.clear();
        List<CardAttachment> stuck = allAttachments();
        stickyNotes.clear();
        viewsAttachments.clear();
        stuck.forEach(CardAttachment::dispose);
        links.closeAll();
        cardStructures.clear();
        for (ProjectMapPreview preview : open) {
            if (canvasHost != null) {
                canvasHost.getChildren().remove(preview);
            }
            preview.dispose();
        }
        closeOverviewCard();
        closeHistoryCard();
        closeDependencyCard();
        closeMetricsCard();
        closeChangesCard();
        closeSymbolGraphCard();
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
                // Pins mean keep (review 4, D4); a result card goes with the card it was found from.
                .filter(card -> !pinnedCards.contains(card) && !links.isResult(card))
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
        Path root = host.root();
        if (disposed || shown == null || root == null) {
            return;
        }
        List<ProjectMapPreview> closing = previews.entrySet().stream()
                // A linked result card stays while the card it was found from does (explore 5), a pinned card always
                // (review 4, position 3).
                .filter(entry -> cardOrphaned(root, entry.getKey(), entry.getValue(), shown)
                        && !links.isResult(entry.getValue())
                        && !pinnedCards.contains(entry.getValue()))
                .map(Map.Entry::getValue)
                .toList();
        closing.forEach(this::closePreview);
        List<ProjectMapNotePreview> closingNotes = notePreviews.entrySet().stream()
                .filter(entry -> orphaned(root, entry.getKey(), shown))
                .map(Map.Entry::getValue)
                .toList();
        closingNotes.forEach(this::closeNotePreview);
    }

    /**
     * {@link #orphaned}, except that a declaration card stays while its file card's Structure column is out and the
     * file's own row is still on the map, and a picked one ({@link #pickedCards}) while its file's row is. One asked
     * for from that column goes with the column, whether or not its row is on the map, as one asked for from the
     * Map's column goes with its row.
     */
    private boolean cardOrphaned(Path root, Path key, ProjectMapPreview card, Set<Path> shown) {
        Path file = ProjectMapStructure.isSymbolPath(key) ? structure.fileOf(key) : null;
        if (pickedCards.contains(card)) {
            return orphaned(root, file != null ? file : key, shown); // a pick goes with its file's folder only
        }
        if (file != null && cardColumnCards.contains(card)) {
            return cardStructureOf(file) == null || orphaned(root, file, shown);
        }
        if (!orphaned(root, key, shown)) {
            return false;
        }
        return file == null || cardStructureOf(file) == null || orphaned(root, file, shown);
    }

    private static boolean orphaned(Path root, Path path, Set<Path> shown) {
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
                || !host.onScreen()
                || previews.isEmpty() && notePreviews.isEmpty() && !structure.hasOpenColumns()) {
            return; // the Map window is closed: setShowing(true) refreshes them when it opens again
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
        // One look at each file per pass, shared by its cards and its outline: a buffer's text is read once.
        Map<Path, ProjectMapPreview.@Nullable Content> cardTick = new HashMap<>();
        Function<Path, ProjectMapPreview.@Nullable Content> cardContent =
                file -> cardTick.containsKey(file) ? cardTick.get(file) : cardTickPut(cardTick, file);
        for (Map.Entry<Path, ProjectMapPreview> entry : List.copyOf(previews.entrySet())) {
            ProjectMapPreview card = entry.getValue();
            Path file = card.path();
            if (!polled || card.showsOpenBuffer()) {
                card.refresh(cardContent.apply(file != null ? file : entry.getKey()));
            }
            mirrors |= card.showsOpenBuffer();
            card.setUnsaved(card.showsOpenBuffer() && file != null && host.isModified(file));
        }
        // Structure columns follow their file too: an open buffer's edits, or a closed file rewritten on disk.
        if (structure.hasOpenColumns() || !cardOutlineFiles().isEmpty()) {
            structure.refresh(cardOutlineFiles(), cardContent);
            mirrors |= structure.openFiles().stream().anyMatch(file -> host.isOpen(file));
        }
        if (!polled) {
            refreshNoteCards();
            previews.values().forEach(this::cardsRequestLastChange); // a commit moved HEAD: cached per HEAD
        }
        if (mirrors && host.onScreen()) { // a closed window's cards are refreshed when it shows again
            cardPoll.playFromStart();
        } else {
            cardPoll.stop();
        }
    }

    private ProjectMapPreview.@Nullable Content cardTickPut(
            Map<Path, ProjectMapPreview.@Nullable Content> tick, Path file) {
        ProjectMapPreview.Content content = host.content(file);
        tick.put(file, content);
        return content;
    }

    private void refreshNoteCards() {
        List.copyOf(previews.values()).forEach(this::updateCardNotes);
        ProjectPanel.MarkerActions actions = notePreviewActions;
        boolean enabled = actions != null && actions.personalNotesEnabled();
        for (Map.Entry<Path, ProjectMapNotePreview> entry : List.copyOf(notePreviews.entrySet())) {
            List<PersonalNote> values;
            try {
                values = enabled && actions != null ? actions.personalNotes(entry.getKey()) : List.of();
            } catch (RuntimeException failed) {
                ProjectMapLog.unexpected("reading a note card's notes", failed);
                continue;
            }
            entry.getValue().refreshNotes(values);
            if (values.isEmpty()) {
                closeNotePreview(entry.getValue()); // its notes were deleted elsewhere: nothing left to show
            }
        }
    }

    /**
     * The card menu's Move to Free Space: the card is placed again, at its present size, where placement would
     * put a new card for its row — the spot that covers the least of the map. The map is not panned.
     */
    private void cardMoveToFreeSpace(Region card, Path key) {
        if (canvasHost == null || !cardTouches.containsKey(card)) {
            return;
        }
        double scale = cardScale(card);
        ProjectMapPreview.Placement placement = previewPlacement(
                card,
                key,
                card.getWidth(),
                card.getHeight(),
                Math.min(card.getWidth(), ProjectMapPreview.MIN_WIDTH),
                Math.min(card.getHeight(), ProjectMapPreview.MIN_HEIGHT),
                canvasHost.getWidth(),
                canvasHost.getHeight());
        card.resize(placement.width(), placement.height());
        card.relocate(placement.x(), placement.y());
        ProjectMapPreview.announceGeometry(card, scale);
    }

    private void constrainPreviews(double width, double height) {
        previews.values().forEach(preview -> preview.constrainTo(width, height));
        notePreviews.values().forEach(preview -> preview.constrainTo(width, height));
        if (overviewCard != null) {
            overviewCard.constrainTo(width, height);
        }
        for (Region solo : soloCards()) {
            if (solo instanceof MapFloatingCard floating) {
                floating.constrainTo(width, height); // the History, Dependencies and Code Metrics cards
            }
        }
    }

    /**
     * Where a new code or note card opens. With {@link Host#focusNewCards} on and the card's row on the map, it opens as
     * a new column would ({@link #cardColumnSpot}): past the row's column the way the map flows, hanging from the row,
     * past the columns and cards already standing there; the map is then panned so the card stands in the middle of the
     * map area, with its row when both fit. Otherwise — the option off, the row off the map, a result card or one from a
     * file card's Structure column — {@link #previewPlacement}, which never pans.
     */
    ProjectMapPreview.Placement openingPlacement(
            Region preview,
            Path path,
            double width,
            double height,
            double minimumWidth,
            double minimumHeight,
            double parentWidth,
            double parentHeight) {
        double scale = cardScale(preview);
        double[] spot = host.focusNewCards()
                ? columnRulePlacement(
                        preview,
                        path,
                        width * scale,
                        height * scale,
                        minimumWidth * scale,
                        minimumHeight * scale,
                        parentWidth,
                        parentHeight)
                : null;
        if (spot == null) {
            return previewPlacement(
                    preview, path, width, height, minimumWidth, minimumHeight, parentWidth, parentHeight);
        }
        return new ProjectMapPreview.Placement(spot[0], spot[1], spot[2] / scale, spot[3] / scale, spot[4] > 0);
    }

    /**
     * {@link #openingPlacement} by the column rule, in panel pixels: {@code {x, y, w, h, growLeft}} after the pan it
     * made, or null when the rule does not apply.
     */
    private double @Nullable [] columnRulePlacement(
            Region preview,
            Path path,
            double width,
            double height,
            double minimumWidth,
            double minimumHeight,
            double parentWidth,
            double parentHeight) {
        if (parentWidth <= 0
                || parentHeight <= 0
                || links.placesBeside(path) // a result card goes beside its source: checked, not taken
                || cardColumnCards.contains(preview)) {
            return null;
        }
        Site site = host.site(path);
        double[] row = site.row();
        if (row == null) {
            return null;
        }
        double margin = ProjectMapPreview.EDGE_MARGIN;
        double w = cardBounded(width, Math.min(minimumWidth, width), parentWidth - margin * 2);
        double h = cardBounded(height, minimumHeight, parentHeight - margin * 2);
        List<double[]> taken = cardsTaken(preview);
        boolean horizontal = host.horizontalFlow();
        boolean reverse = host.reverseFlow();
        // Level with the cards already opened from the row's column, when there are any (Map cards, same level).
        double[] spot = cardLevelSpot(
                cardSiblings(preview, site.column()), taken, w, h, horizontal, false, COLUMN_GAP * host.zoom());
        if (spot == null) {
            spot = cardColumnSpot(row, site.column(), w, h, horizontal, reverse, host.zoom(), taken);
        }
        double[] pan = cardCentringPan(spot, row, parentWidth, parentHeight, margin);
        if (Math.abs(pan[0]) > 0.5 || Math.abs(pan[1]) > 0.5) {
            host.panBy(pan[0], pan[1]); // the cards already open move with the map
        }
        return new double[] {spot[0] + pan[0], spot[1] + pan[1], w, h, horizontal && reverse ? 1 : 0};
    }

    /**
     * What a card opened as a column would goes past: every column on the map, every open card but {@code preview},
     * and what is stuck on them, as drawn {@code {x, y, w, h}}.
     */
    private List<double[]> cardsTaken(Region preview) {
        List<double[]> taken = new ArrayList<>(host.allColumns());
        for (CardAttachment stuck : allAttachments()) {
            double[] drawn = stuck.card() == preview ? null : stuck.bounds();
            if (drawn != null) {
                taken.add(drawn);
            }
        }
        for (OpenCard open : openCards()) {
            Region other = open.node();
            if (other != preview && other.isVisible() && other.getWidth() > 0) {
                taken.add(drawnBounds(other));
            }
        }
        return taken;
    }

    /**
     * The open code cards opened from the rows of the column drawn at {@code column} ({@code {x, y, w, h}}), as
     * drawn, but {@code preview}: not a declaration card of a file card's column nor a linked result card, which go
     * beside their card, and not one hidden with its row. (Map cards, same level)
     */
    private List<double[]> cardSiblings(Region preview, double[] column) {
        List<double[]> siblings = new ArrayList<>();
        for (Map.Entry<Path, ProjectMapPreview> open : previews.entrySet()) {
            ProjectMapPreview other = open.getValue();
            if (other == preview
                    || !other.isVisible()
                    || other.getWidth() <= 0
                    || cardColumnCards.contains(other)
                    || links.besideSource(open.getKey()) != null) {
                continue;
            }
            Site site = host.site(open.getKey());
            if (site.row() != null && java.util.Arrays.equals(site.column(), column)) {
                siblings.add(drawnBounds(other));
            }
        }
        return siblings;
    }

    /**
     * Where a card {@code width × height} opens level with {@code siblings}, the cards already opened from its row's
     * column as drawn ({@code {x, y, w, h}}; Map cards, same level): in a flow across the panel, at the left edge of
     * the lowest of them and {@code gap} below it, so a column's cards stand in a column of their own; in a flow down
     * or up it, level with the top of the rightmost and {@code gap} right of it, in a row. {@code back}: above the
     * topmost, or left of the leftmost, instead (where there is no room on, and the map is not panned). Then on
     * past anything of {@code taken} it would cover, the same way. Null without siblings. Pure.
     */
    static double @Nullable [] cardLevelSpot(
            List<double[]> siblings,
            List<double[]> taken,
            double width,
            double height,
            boolean horizontal,
            boolean back,
            double gap) {
        if (siblings.isEmpty()) {
            return null;
        }
        int axis = horizontal ? 1 : 0; // the stacking axis: down the panel across the flow, along it otherwise
        double[] last = siblings.getFirst();
        for (double[] sibling : siblings) {
            boolean further =
                    back ? sibling[axis] < last[axis] : sibling[axis] + sibling[axis + 2] > last[axis] + last[axis + 2];
            if (further) {
                last = sibling;
            }
        }
        double size = horizontal ? height : width;
        double[] spot = {last[0], last[1], width, height};
        spot[axis] = back ? last[axis] - gap - size : last[axis] + last[axis + 2] + gap;
        for (int pass = 0; pass <= taken.size(); pass++) {
            double[] hit = null;
            for (double[] t : taken) {
                if (spot[0] < t[0] + t[2]
                        && t[0] < spot[0] + width
                        && spot[1] < t[1] + t[3]
                        && t[1] < spot[1] + height) {
                    hit = t;
                    break;
                }
            }
            if (hit == null) {
                break;
            }
            spot[axis] = back
                    ? Math.min(spot[axis], hit[axis] - gap - size)
                    : Math.max(spot[axis], hit[axis] + hit[axis + 2] + gap);
        }
        return spot;
    }

    /** Between a row's column and a card opened from it, as between two columns (unzoomed). */
    static final double CARD_COLUMN_GAP = 40;

    /** How far a card may start before its row across the flow, as a long column hangs from its row (unzoomed). */
    static final double CARD_COLUMN_LEAD = 150;

    /**
     * Where a card {@code width × height} opens from {@code row} in {@code column}, as a new column would, {@code {x,
     * y}} in panel pixels: past the column the way the map flows ({@code reverse}: left of it or above it), centred on
     * the row across the flow or, when long, hanging from it ({@link #CARD_COLUMN_LEAD}); then on past anything of
     * {@code taken} ({@code {x, y, w, h}}) it would cover, as a column goes past the other columns of its level. Pure.
     */
    static double[] cardColumnSpot(
            double[] row,
            double[] column,
            double width,
            double height,
            boolean horizontal,
            boolean reverse,
            double zoom,
            List<double[]> taken) {
        double gap = CARD_COLUMN_GAP * zoom;
        double lead = CARD_COLUMN_LEAD * zoom;
        double x;
        double y;
        if (horizontal) {
            x = reverse ? column[0] - gap - width : column[0] + column[2] + gap;
            y = row[1] + row[3] / 2 - Math.min(height / 2, lead);
        } else {
            x = row[0] + row[2] / 2 - Math.min(width / 2, lead);
            y = reverse ? column[1] - gap - height : column[1] + column[3] + gap;
        }
        for (int pass = 0; pass <= taken.size(); pass++) {
            double[] hit = null;
            for (double[] t : taken) {
                if (x < t[0] + t[2] && t[0] < x + width && y < t[1] + t[3] && t[1] < y + height) {
                    hit = t;
                    break;
                }
            }
            if (hit == null) {
                break;
            }
            if (horizontal) {
                x = reverse ? Math.min(x, hit[0] - gap - width) : Math.max(x, hit[0] + hit[2] + gap);
            } else {
                y = reverse ? Math.min(y, hit[1] - gap - height) : Math.max(y, hit[1] + hit[3] + gap);
            }
        }
        return new double[] {x, y, width, height};
    }

    /**
     * The pan {@code {dx, dy}} that puts {@code card} ({@code {x, y, w, h}}) in the middle of a panel {@code
     * parentWidth × parentHeight} — its top-left corner at the margin when it is larger — and then, when the card and
     * {@code row} fit in the panel together, the least more that brings the row in too. Pure.
     */
    static double[] cardCentringPan(
            double[] card, double[] row, double parentWidth, double parentHeight, double margin) {
        double dx = card[2] + margin * 2 <= parentWidth ? parentWidth / 2 - (card[0] + card[2] / 2) : margin - card[0];
        double dy =
                card[3] + margin * 2 <= parentHeight ? parentHeight / 2 - (card[1] + card[3] / 2) : margin - card[1];
        double left = Math.min(card[0], row[0]);
        double right = Math.max(card[0] + card[2], row[0] + row[2]);
        double top = Math.min(card[1], row[1]);
        double bottom = Math.max(card[1] + card[3], row[1] + row[3]);
        if (right - left + margin * 2 <= parentWidth) {
            dx += Math.max(0, margin - (left + dx)) - Math.max(0, right + dx - (parentWidth - margin));
        }
        if (bottom - top + margin * 2 <= parentHeight) {
            dy += Math.max(0, margin - (top + dy)) - Math.max(0, bottom + dy - (parentHeight - margin));
        }
        return new double[] {dx, dy};
    }

    /**
     * The one placement path for code and note cards: beside the row's column when there is room for the
     * card's minimum size, otherwise over the map inside the panel, then shifted or cascaded clear of the
     * cards already open — of those in view; one dragged or panned off the panel takes no room. The map
     * itself is never panned to make room. Sizes in and out are the card's own, unzoomed; the room is found
     * for the card as drawn at its zoom.
     */
    ProjectMapPreview.Placement previewPlacement(
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

    /**
     * {@link #previewPlacement} in panel pixels: every size here is a card as drawn. The spot that covers the least:
     * beside the row's column after it, before it, above or below the columns' band, or any free part of the
     * panel, scored by how much of the columns, the zoom bar, the overview and the other cards it would cover (see
     * {@link #cardOverlapCost}). The columns that lead to the row weigh most, and so does the band where the row's
     * next column — its folder's, or its file's Structure column — would open — and by where it stands against the
     * way the map flows ({@link #cardFlowCost}). The map is never panned: with no room on its flow side, a card takes
     * the side of its column.
     */
    private ProjectMapPreview.Placement drawnPlacement(
            Region preview,
            Path path,
            double width,
            double height,
            double minimumWidth,
            double minimumHeight,
            double parentWidth,
            double parentHeight) {
        double[] replacing = links.takePlace(path);
        if (replacing != null) {
            // A Go to result replacing the preview it was found from (gap 6): where that card stood, as it was drawn.
            links.besideSource(path); // placed: no longer to go beside its source
            return new ProjectMapPreview.Placement(replacing[0], replacing[1], replacing[2], replacing[3], false);
        }
        Site site = host.site(path);
        Site linked = links.besideSource(path); // a result card goes beside the card it was found from
        // Beside a file card's Structure column when asked for from there, or when its row is not on the map.
        Site beside = linked != null
                ? linked
                : site.row() == null || cardColumnCards.contains(preview) ? cardStructureSite(path) : null;
        if (beside != null) {
            site = beside;
        }
        double margin = ProjectMapPreview.EDGE_MARGIN;
        double roomWidth = parentWidth - margin * 2;
        double roomHeight = parentHeight - margin * 2;
        // A result card is narrower, so that six fit around the card they were found from.
        double wanted = linked != null ? Math.min(width, MapCardLinks.CARD_WIDTH * cardScale(preview)) : width;
        double fullWidth = cardBounded(wanted, Math.min(minimumWidth, wanted), roomWidth);
        double fullHeight = cardBounded(height, minimumHeight, roomHeight);
        if (beside != null && linked == null) {
            // Right of the file card, past its Structure column: the card steps left for it when it must.
            Site room = cardStructureRoom(path, Math.min(minimumWidth, fullWidth), parentWidth);
            if (room != null) {
                beside = room;
                site = room;
            }
        }
        List<double[]> chrome = host.chrome();
        if (site.row() == null) {
            // The row is not on the map (filtered, scrolled off): against the panel's far edge, as before, and ending
            // above the zoom bar or the overview when it would run over them (review 3, E11).
            double x = Math.max(margin, parentWidth - fullWidth - margin);
            double h = fullHeight;
            for (double[] c : chrome) {
                if (cardOverlapCost(x, margin, fullWidth, h, List.of(c)) > 0) {
                    double above = c[1] - COLUMN_GAP / 2 - margin;
                    if (above >= Math.min(minimumHeight, fullHeight)) {
                        h = above;
                    }
                }
            }
            return new ProjectMapPreview.Placement(x, margin, fullWidth, h);
        }
        // Level with the cards already opened from the row's column (Map cards, same level): under the lowest of them
        // at its left edge in a flow across the panel, right of the rightmost at its top in a flow down or up it — or,
        // with no room that way, above the topmost or left of the leftmost — when that keeps the card inside the
        // panel at no less than its minimum (the map is not panned for it).
        if (beside == null && linked == null) {
            List<double[]> siblings = cardSiblings(preview, site.column());
            List<double[]> taken = siblings.isEmpty() ? List.of() : cardsTaken(preview);
            for (boolean back : new boolean[] {false, true}) {
                double[] level = cardLevelSpot(
                        siblings, taken, fullWidth, fullHeight, host.horizontalFlow(), back, COLUMN_GAP * host.zoom());
                if (level == null) {
                    break;
                }
                double w = Math.min(fullWidth, parentWidth - margin - level[0]);
                double h = Math.min(fullHeight, parentHeight - margin - level[1]);
                if (level[0] >= margin
                        && level[1] >= margin
                        && w >= Math.min(minimumWidth, fullWidth)
                        && h >= Math.min(minimumHeight, fullHeight)) {
                    return new ProjectMapPreview.Placement(level[0], level[1], w, h, level[0] + w <= site.row()[0]);
                }
            }
        }
        // The way the map flows from this row: a card opens past the row's column that way, as its next column would.
        double[] forward = cardFlowForward(beside, linked != null);
        List<double[]> obstacles = new ArrayList<>(site.obstacles());
        obstacles.addAll(chrome); // the zoom bar and the overview: never covered when anything else will do
        List<Region> cards = new ArrayList<>(previews.values());
        cards.addAll(notePreviews.values());
        cards.addAll(soloCards());
        for (CardAttachment stuck : allAttachments()) {
            double[] drawn = stuck.card() == preview ? null : stuck.bounds(); // its own move with it
            if (drawn != null) { // what is stuck on a card weighs as a card does
                obstacles.add(new double[] {
                    drawn[0] - PREVIEW_CASCADE / 2,
                    drawn[1] - PREVIEW_CASCADE / 2,
                    drawn[2] + PREVIEW_CASCADE,
                    drawn[3] + PREVIEW_CASCADE,
                    CARD_WEIGHT_CARD
                });
            }
        }
        for (Region other : cards) {
            if (other != preview && other.isVisible() && other.getWidth() > 0) {
                double scale = cardScale(other);
                obstacles.add(new double[] {
                    other.getLayoutX() - PREVIEW_CASCADE / 2,
                    other.getLayoutY() - PREVIEW_CASCADE / 2,
                    other.getWidth() * scale + PREVIEW_CASCADE,
                    other.getHeight() * scale + PREVIEW_CASCADE,
                    CARD_WEIGHT_CARD
                });
            }
        }
        double[] row = site.row();
        double[] column = site.column();
        double[] band = site.band();
        boolean horizontal =
                host.horizontalFlow() || beside != null; // beside a card's Structure column, which stands upright
        double gap = COLUMN_GAP;
        double rowX = row[0] + row[2] / 2;
        double rowY = row[1] + row[3] / 2;
        List<double[]> spots = new ArrayList<>();
        if (horizontal) {
            double y = rowY - fullHeight / 2;
            double afterX = column[0] + column[2] + gap;
            cardSpot(spots, afterX, y, Math.min(fullWidth, parentWidth - margin - afterX), fullHeight, minimumWidth, 1);
            double beforeEnd = column[0] - gap;
            double beforeWidth = Math.min(fullWidth, beforeEnd - margin);
            cardSpot(spots, beforeEnd - beforeWidth, y, beforeWidth, fullHeight, minimumWidth, 1);
            double aboveHeight = Math.min(fullHeight, band[1] - gap - margin);
            cardSpot(
                    spots, rowX - fullWidth / 2, band[1] - gap - aboveHeight, fullWidth, aboveHeight, minimumHeight, 0);
            double belowY = band[1] + band[3] + gap;
            cardSpot(
                    spots,
                    rowX - fullWidth / 2,
                    belowY,
                    fullWidth,
                    Math.min(fullHeight, parentHeight - margin - belowY),
                    minimumHeight,
                    0);
        } else {
            double x = rowX - fullWidth / 2;
            double afterY = column[1] + column[3] + gap;
            cardSpot(
                    spots,
                    x,
                    afterY,
                    fullWidth,
                    Math.min(fullHeight, parentHeight - margin - afterY),
                    minimumHeight,
                    0);
            double beforeEnd = column[1] - gap;
            double beforeHeight = Math.min(fullHeight, beforeEnd - margin);
            cardSpot(spots, x, beforeEnd - beforeHeight, fullWidth, beforeHeight, minimumHeight, 0);
            double leftWidth = Math.min(fullWidth, band[0] - gap - margin);
            cardSpot(spots, band[0] - gap - leftWidth, rowY - fullHeight / 2, leftWidth, fullHeight, minimumWidth, 1);
            double rightX = band[0] + band[2] + gap;
            cardSpot(
                    spots,
                    rightX,
                    rowY - fullHeight / 2,
                    Math.min(fullWidth, parentWidth - margin - rightX),
                    fullHeight,
                    minimumWidth,
                    1);
        }
        // Beside the row's column itself, across the flow and level with the row, cut to the room there: where a card
        // goes when its flow side has no room, as the map is never panned for it. Along the flow it may slide as far
        // as it stays level with the row, to clear what is in the way (the zoom bar, the overview, other cards).
        if (forward != null && beside == null) {
            for (double along :
                    cardSideLevels(row, horizontal ? fullWidth : fullHeight, horizontal ? 0 : 1, obstacles)) {
                if (horizontal) {
                    double aboveHeight = Math.min(fullHeight, column[1] - gap - margin);
                    cardSpot(spots, along, column[1] - gap - aboveHeight, fullWidth, aboveHeight, minimumHeight, 0);
                    double belowY = column[1] + column[3] + gap;
                    double belowHeight = Math.min(fullHeight, parentHeight - margin - belowY);
                    cardSpot(spots, along, belowY, fullWidth, belowHeight, minimumHeight, 0);
                } else {
                    double leftWidth = Math.min(fullWidth, column[0] - gap - margin);
                    cardSpot(spots, column[0] - gap - leftWidth, along, leftWidth, fullHeight, minimumWidth, 1);
                    double rightX = column[0] + column[2] + gap;
                    double rightWidth = Math.min(fullWidth, parentWidth - margin - rightX);
                    cardSpot(spots, rightX, along, rightWidth, fullHeight, minimumWidth, 1);
                }
            }
        }
        // A result card around the card it was found from: beside it at any level that keeps it level with that card,
        // and above or below it anywhere along it, past what already stands there — the result cards opened before it —
        // so several opened together each find a place of their own beside it rather than over it.
        if (linked != null) {
            double afterX = column[0] + column[2] + gap;
            double afterWidth = Math.min(fullWidth, parentWidth - margin - afterX);
            double beforeWidth = Math.min(fullWidth, column[0] - gap - margin);
            for (double along : cardSideLevels(row, fullHeight, 1, obstacles)) {
                cardSpot(spots, afterX, along, afterWidth, fullHeight, minimumWidth, 1);
                cardSpot(spots, column[0] - gap - beforeWidth, along, beforeWidth, fullHeight, minimumWidth, 1);
            }
            double belowY = column[1] + column[3] + gap;
            double belowHeight = Math.min(fullHeight, parentHeight - margin - belowY);
            double aboveHeight = Math.min(fullHeight, column[1] - gap - margin);
            for (double along : cardSideLevels(row, fullWidth, 0, obstacles)) {
                cardSpot(spots, along, belowY, fullWidth, belowHeight, minimumHeight, 0);
                cardSpot(spots, along, column[1] - gap - aboveHeight, fullWidth, aboveHeight, minimumHeight, 0);
            }
        }
        // Any free part of the panel: the card against each obstacle's edges, and against the panel's.
        java.util.TreeSet<Double> xs = new java.util.TreeSet<>(List.of(margin, parentWidth - fullWidth - margin));
        java.util.TreeSet<Double> ys = new java.util.TreeSet<>(List.of(margin, parentHeight - fullHeight - margin));
        // Level with the row too, so a card can stand past what is in the way on its flow side (the band the row's
        // next column would open in, a column already open there) without leaving its row's height.
        xs.add(rowX - fullWidth / 2);
        ys.add(rowY - fullHeight / 2);
        for (double[] o : obstacles) {
            xs.add(o[0] + o[2] + gap);
            xs.add(o[0] - fullWidth - gap);
            ys.add(o[1] + o[3] + gap);
            ys.add(o[1] - fullHeight - gap);
        }
        for (double x : xs) {
            for (double y : ys) {
                spots.add(new double[] {x, y, fullWidth, fullHeight});
                // The largest card from that corner that stays inside the panel, down to the card's minimum:
                // a free spot a little smaller beats covering the path to the row.
                double fitWidth = Math.min(fullWidth, parentWidth - margin - x);
                double fitHeight = Math.min(fullHeight, parentHeight - margin - y);
                if (x >= margin
                        && y >= margin
                        && (fitWidth < fullWidth || fitHeight < fullHeight)
                        && fitWidth >= Math.min(minimumWidth, fullWidth)
                        && fitHeight >= Math.min(minimumHeight, fullHeight)) {
                    spots.add(new double[] {x, y, fitWidth, fitHeight});
                }
            }
        }
        // Each spot also cut short of the zoom bar and the overview, down to the card's minimum: a card a little
        // smaller
        // beats one whose last lines and scroll bar sit under them (review 3, E11).
        List<double[]> clamped = new ArrayList<>(spots.size() * 2);
        for (double[] spot : spots) {
            double w = spot[2];
            double h = spot[3];
            double x = clampInto(spot[0], margin, Math.max(margin, parentWidth - w - margin));
            double y = clampInto(spot[1], margin, Math.max(margin, parentHeight - h - margin));
            clamped.add(new double[] {x, y, w, h});
            for (double[] c : chrome) {
                if (cardOverlapCost(x, y, w, h, List.of(c)) <= 0) {
                    continue;
                }
                double cutHeight = c[1] - gap / 2 - y;
                if (cutHeight >= Math.min(minimumHeight, fullHeight) && cutHeight < h) {
                    clamped.add(new double[] {x, y, w, cutHeight});
                }
                double cutWidth = c[0] - gap / 2 - x;
                if (cutWidth >= Math.min(minimumWidth, fullWidth) && cutWidth < w) {
                    clamped.add(new double[] {x, y, cutWidth, h});
                }
                double after = c[0] + c[2] + gap / 2;
                if (after > x && x + w - after >= Math.min(minimumWidth, fullWidth)) {
                    clamped.add(new double[] {after, y, x + w - after, h});
                }
            }
        }
        double[] best = null;
        double bestCost = Double.MAX_VALUE;
        // A declaration card from a file card's Structure column opens right of the file card when any spot there will
        // do (Map Structure right): the best of those, else the best anywhere.
        double rightOfCard = Double.NaN;
        if (beside != null && linked == null) {
            ProjectMapCardStructure stuck = cardStructureOf(structure.fileOf(path));
            if (stuck != null) {
                double[] card = drawnBounds(stuck.card());
                rightOfCard = card[0] + card[2];
            }
        }
        double[] bestRight = null;
        double bestRightCost = Double.MAX_VALUE;
        // The row's own column and the columns that lead to it: a spot over them weighs as behind, so the way the map
        // flows never buys covering the path to the row.
        List<double[]> leading =
                obstacles.stream().filter(o -> o[4] == 4 || o[4] == 6).toList();
        for (double[] spot : clamped) {
            double x = spot[0];
            double y = spot[1];
            double w = spot[2];
            double h = spot[3];
            // Least covered first; between spots that cover alike, the one nearer its row, then the larger.
            double distance = Math.hypot(x + w / 2 - rowX, y + h / 2 - rowY);
            // A card cut narrower than its lines needs makes them scroll sideways: that weighs more than height.
            double cost = cardOverlapCost(x, y, w, h, obstacles)
                    + distance * 0.5
                    - w * h * 0.05
                    + Math.max(0, fullWidth - w) * h * 0.1
                    + cardFlowWeight(x, y, w, h, column, forward, leading) * fullWidth * fullHeight;
            if (cost < bestCost) {
                bestCost = cost;
                best = new double[] {x, y, w, h};
            }
            if (x >= rightOfCard - 1 && cost < bestRightCost) { // never true while rightOfCard is NaN
                bestRightCost = cost;
                bestRight = new double[] {x, y, w, h};
            }
        }
        if (bestRight != null) {
            best = bestRight;
        }
        if (best == null) {
            return new ProjectMapPreview.Placement(margin, margin, fullWidth, fullHeight);
        }
        // Before its row (or, in a flow down or up the panel, left of the row's column): widened text grows leftwards,
        // away from the column, rather than back over it.
        boolean growLeft = best[0] + best[2] <= (horizontal ? row[0] : column[0]);
        return new ProjectMapPreview.Placement(best[0], best[1], best[2], best[3], growLeft);
    }

    /**
     * The way a new card for {@code path} goes from its row, as {@code {dx, dy}} (one of them ±1): past the row's column
     * the way the map flows; beside a file card's Structure column, rightwards, the way the declarations read on from
     * the card (the column always stands on its card's right). Null for a linked result card, which goes around the
     * card it was found from. (Map card flow)
     */
    private double @Nullable [] cardFlowForward(@Nullable Site beside, boolean linked) {
        if (linked) {
            return null;
        }
        if (beside != null) {
            return new double[] {1, 0};
        }
        int way = host.reverseFlow() ? -1 : 1;
        return host.horizontalFlow() ? new double[] {way, 0} : new double[] {0, way};
    }

    /**
     * What a spot costs for where it stands against the way the card flows from its row, in parts of the card's area:
     * nothing past the row's column that way, a quarter beside it, half behind it, where the columns that lead to the
     * row are. Zero without a way ({@code forward} null). Pure. (Map card flow)
     */
    static double cardFlowCost(double x, double y, double w, double h, double[] column, double @Nullable [] forward) {
        if (forward == null) {
            return 0;
        }
        boolean alongX = forward[0] != 0;
        int way = (int) (alongX ? forward[0] : forward[1]);
        double from = alongX ? x : y;
        double to = from + (alongX ? w : h);
        double start = alongX ? column[0] : column[1];
        double end = start + (alongX ? column[2] : column[3]);
        double slack = 1;
        boolean ahead = way > 0 ? from >= end - slack : to <= start + slack;
        boolean behind = way > 0 ? to <= start + slack : from >= end - slack;
        return ahead ? 0 : behind ? FLOW_BEHIND : FLOW_BESIDE;
    }

    /**
     * {@link #cardFlowCost}, but as behind for a spot that covers any of {@code path} (the row's column and the columns
     * that lead to it): a card over them is in the way of the row however it stands against the flow.
     */
    private static double cardFlowWeight(
            double x, double y, double w, double h, double[] column, double @Nullable [] forward, List<double[]> path) {
        double flow = cardFlowCost(x, y, w, h, column, forward);
        return flow > 0 && cardOverlapCost(x, y, w, h, path) > 0 ? FLOW_BEHIND : flow;
    }

    /** {@link #cardFlowCost} beside the row's column (above or below it across the flow), per card area. */
    static final double FLOW_BESIDE = 0.25;

    /** {@link #cardFlowCost} behind the row's column, over the way that leads to the row, per card area. */
    static final double FLOW_BEHIND = 0.5;

    /**
     * Where a declaration card opened from a file card's Structure column goes when its row is not on the map: beside
     * the column, by the declaration's row in it (or the column's middle when that row is scrolled away), as a card
     * goes beside its row's column. Null when the declaration's file has no Structure column out.
     */
    private @Nullable Site cardStructureSite(Path key) {
        if (!ProjectMapStructure.isSymbolPath(key)) {
            return null;
        }
        ProjectMapCardStructure column = cardStructureOf(structure.fileOf(key));
        double[] drawn = column == null ? null : column.bounds();
        if (column == null || drawn == null) {
            return null;
        }
        double[] row = column.rowBounds(key);
        if (row == null) {
            row = new double[] {drawn[0], drawn[1] + drawn[3] / 2 - 10, drawn[2], 20};
        }
        // The map's columns on screen still weigh: beside the column a card used to cover the very file's column.
        return new Site(row, drawn, drawn, host.columns());
    }

    /**
     * Steps the file card whose Structure column lists {@code path} left, with its column, until a declaration card
     * {@code width} wide fits right of the column inside the panel, as far as the panel's left edge allows (Map
     * Structure right). The site beside the column again when the card moved; null when it did not need to.
     */
    private @Nullable Site cardStructureRoom(Path path, double width, double parentWidth) {
        ProjectMapCardStructure stuck = cardStructureOf(structure.fileOf(path));
        double[] drawn = stuck == null ? null : stuck.bounds();
        if (stuck == null || drawn == null) {
            return null;
        }
        double margin = ProjectMapPreview.EDGE_MARGIN;
        ProjectMapPreview card = stuck.card();
        double need = drawn[0] + drawn[2] + COLUMN_GAP + width + margin - parentWidth;
        double back = Math.min(need, card.getLayoutX() - margin);
        if (back <= 0.5) {
            return null;
        }
        card.relocate(card.getLayoutX() - back, card.getLayoutY()); // its column and notes follow it
        return cardStructureSite(path);
    }

    /**
     * How much the zoom bar and the overview weigh against a spot, per pixel a card would cover: far above anything
     * on the map, so a card covers them only when nothing else is left (review 3, E11).
     */
    static final double CHROME_WEIGHT = 60;

    /** How much a card of the other cards weighs against a spot, per pixel it would cover (columns: 1–6). */
    private static final double CARD_WEIGHT_CARD = 5;

    /** Adds a spot whose extent along {@code axis} (0 height, 1 width) is at least the card's minimum there. */
    private static void cardSpot(
            List<double[]> spots, double x, double y, double w, double h, double minimum, int axis) {
        if ((axis == 1 ? w : h) >= minimum && w > 0 && h > 0) {
            spots.add(new double[] {x, y, w, h});
        }
    }

    /**
     * Where along {@code axis} a card {@code size} long may start and still stand level with {@code row}: centred on
     * it, flush with either end of it, and against each obstacle's edges where that keeps it level. (Map card flow)
     */
    private static List<Double> cardSideLevels(double[] row, double size, int axis, List<double[]> obstacles) {
        double start = row[axis];
        double end = row[axis] + row[axis + 2];
        List<Double> levels = new ArrayList<>(List.of(start + (end - start - size) / 2, start, end - size));
        for (double[] o : obstacles) {
            levels.add(o[axis] - size - COLUMN_GAP);
            levels.add(o[axis] + o[axis + 2] + COLUMN_GAP);
        }
        levels.removeIf(level -> level > end || level + size < start);
        return levels;
    }

    private static double cardBounded(double requested, double minimum, double available) {
        double maximum = Math.max(1, available);
        return Math.min(Math.max(Math.min(minimum, maximum), requested), maximum);
    }

    /**
     * What a card at {@code x, y} sized {@code w × h} covers: the area of each obstacle {@code {x, y, w, h, weight}}
     * under it, times the obstacle's weight. Pure.
     */
    static double cardOverlapCost(double x, double y, double w, double h, List<double[]> obstacles) {
        double cost = 0;
        for (double[] o : obstacles) {
            double across = Math.min(x + w, o[0] + o[2]) - Math.max(x, o[0]);
            double down = Math.min(y + h, o[1] + o[3]) - Math.max(y, o[1]);
            if (across > 0 && down > 0) {
                cost += across * down * o[4];
            }
        }
        return cost;
    }

    /** {@code value} within [{@code minimum}, {@code maximum}]; unlike {@link Math#clamp} it tolerates an empty range. */
    private static double clampInto(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /** The map zoom a card is drawn at: its width and height are unzoomed, its corner is in panel pixels. */
    private static double cardScale(Region card) {
        if (card instanceof ProjectMapPreview code) {
            return code.mapScale();
        }
        if (card instanceof ProjectMapOverviewCard overview) {
            return overview.mapScale();
        }
        if (card instanceof MapFloatingCard floating) {
            return floating.mapScale(); // the History card, the Dependencies card
        }
        return card instanceof ProjectMapNotePreview note ? note.mapScale() : 1;
    }

    /**
     * Something a connector meets moved: the connectors are drawn once, at the map area's next layout pass or the next
     * pulse, whichever comes first, however many cards and attachments moved in between (review 3, D5).
     */
    private void connectorsMoved() {
        if (connectorsPending || disposed) {
            return;
        }
        connectorsPending = true;
        connectorPulse.start();
        canvasHost.requestLayout();
    }

    /**
     * Draws the connectors now if something marked them (see {@link #connectorsMoved}). A card's Structure column stays
     * on its right wherever the card moves (Map Structure right).
     */
    void flushConnectors() {
        if (connectorsPending) {
            repaintPreviewConnectors();
        }
    }

    /** Whether the connectors wait for a draw. */
    private boolean connectorsPending;

    /** How many times the connector canvas was drawn. For tests. */
    long connectorDraws;

    private final javafx.animation.AnimationTimer connectorPulse = new javafx.animation.AnimationTimer() {
        @Override
        public void handle(long now) {
            stop();
            flushConnectors();
        }
    };

    private void repaintPreviewConnectors() {
        connectorsPending = false;
        connectorPulse.stop();
        connectorDraws++;
        GraphicsContext g = previewConnectorCanvas.getGraphicsContext2D();
        g.clearRect(0, 0, previewConnectorCanvas.getWidth(), previewConnectorCanvas.getHeight());
        previewConnectors.clear();
        // Every reload hands the surface a new expansion set: one reference comparison per repaint tells
        // whether a folder may have been collapsed under an open card.
        Set<Path> shown = host.expandedSnapshot();
        if (shown != cardsCheckedAgainst) {
            cardsCheckedAgainst = shown;
            if (!orphanCheckPending && !(previews.isEmpty() && notePreviews.isEmpty())) {
                orphanCheckPending = true;
                Platform.runLater(this::closeOrphanedCards); // not from inside a paint or a layout pass
            }
        }
        paintConnectors(g, host::rowBox, previewConnectors);
    }

    /**
     * Print and PDF: every connector as {@link #drawConnectors} draws it, onto {@code g} in the map area's pixels, with
     * {@code rowBox} for where each row of the map is. Changes nothing.
     */
    void drawOutputConnectors(GraphicsContext g, Function<Path, double @Nullable []> rowBox) {
        paintConnectors(g, rowBox, null);
    }

    /**
     * Print and PDF: what of the cards is drawn now, back to front — each visible card and every part of what is stuck
     * on one (sticky notes, Structure columns, link chips, trail notes) — as the map area's children.
     */
    List<Node> outputNodes() {
        List<CardAttachment> stuck = allAttachments();
        Set<Node> cards = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        cards.addAll(previews.values());
        cards.addAll(notePreviews.values());
        cards.addAll(soloCards());
        List<Node> nodes = new ArrayList<>();
        for (Node child : canvasHost.getChildren()) {
            if (child.isVisible()
                    && (cards.contains(child) || stuck.stream().anyMatch(attachment -> attachment.contains(child)))) {
                nodes.add(child);
            }
        }
        return nodes;
    }

    /** Draws the connectors on {@code g}; {@code drawn}, when given, records each code card's (for tests). */
    private void paintConnectors(
            GraphicsContext g,
            Function<Path, double @Nullable []> rowBox,
            @Nullable Map<Path, PreviewConnector> drawn) {
        g.setStroke(host.accent());
        g.setGlobalAlpha(0.78);
        g.setLineWidth(1.5);
        for (Map.Entry<Path, ProjectMapPreview> entry : previews.entrySet()) {
            ProjectMapPreview preview = entry.getValue();
            if (!preview.isVisible() || preview.getWidth() <= 0 || preview.getHeight() <= 0) {
                continue;
            }
            // A declaration card is tied to the Structure column it was asked for from: its file card's, else the
            // Map's; the other one's row when that one's is not in sight.
            double[] row = cardStructureRow(entry.getKey());
            double[] anchor = rowBox.apply(entry.getKey());
            PreviewConnector connector = null;
            if (row != null && (anchor == null || cardColumnCards.contains(preview))) {
                connector = drawPreviewConnector(g, row[0], row[1], row[2], row[3], true, preview);
            } else if (anchor != null) {
                connector = drawPreviewConnector(g, anchor, preview);
            }
            if (connector != null && drawn != null) {
                drawn.put(entry.getKey(), connector);
            }
        }
        g.setStroke(host.note());
        for (Map.Entry<Path, ProjectMapNotePreview> entry : notePreviews.entrySet()) {
            ProjectMapNotePreview preview = entry.getValue();
            double[] anchor = rowBox.apply(entry.getKey());
            if (!preview.isVisible() || anchor == null || preview.getWidth() <= 0 || preview.getHeight() <= 0) {
                continue;
            }
            drawPreviewConnector(g, anchor, preview);
        }
        g.setFill(host.note());
        allAttachments().forEach(stuck -> stuck.drawConnectors(g));
        g.setGlobalAlpha(1);
    }

    private PreviewConnector drawPreviewConnector(GraphicsContext g, double[] anchor, Region preview) {
        return drawPreviewConnector(g, anchor[0], anchor[1], anchor[2], anchor[3], host.horizontalFlow(), preview);
    }

    /**
     * The row of a declaration card's declaration in its file card's Structure column, in panel pixels, when the
     * column is out and the row is scrolled into sight: where the card's connector starts. Else null.
     */
    private double @Nullable [] cardStructureRow(Path key) {
        if (!ProjectMapStructure.isSymbolPath(key)) {
            return null;
        }
        ProjectMapCardStructure column = cardStructureOf(structure.fileOf(key));
        return column == null ? null : column.rowBounds(key);
    }

    /** A connector from the anchor box {@code ax, ay, aw, ah} to {@code preview}, across the flow or along it. */
    private PreviewConnector drawPreviewConnector(
            GraphicsContext g, double ax, double ay, double aw, double ah, boolean horizontal, Region preview) {
        double x1;
        double y1;
        double x2;
        double y2;
        double left = preview.getLayoutX();
        double top = preview.getLayoutY();
        double width = preview.getWidth() * cardScale(preview);
        double height = preview.getHeight() * cardScale(preview);
        if (horizontal) {
            boolean previewAfter = left + width / 2 >= ax + aw / 2;
            x1 = previewAfter ? ax + aw : ax;
            y1 = ay + ah / 2;
            x2 = previewAfter ? left : left + width;
            y2 = clampInto(y1, top, top + height);
            double control = Math.max(18, Math.abs(x2 - x1) * 0.45);
            g.beginPath();
            g.moveTo(x1, y1);
            g.bezierCurveTo(
                    x1 + (previewAfter ? control : -control), y1, x2 + (previewAfter ? -control : control), y2, x2, y2);
        } else {
            boolean previewAfter = top + height / 2 >= ay + ah / 2;
            x1 = ax + aw / 2;
            y1 = previewAfter ? ay + ah : ay;
            x2 = clampInto(x1, left, left + width);
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

    /** A connector as drawn: from its row's edge to the card's. For tests. */
    record PreviewConnector(double startX, double startY, double endX, double endY) {}
}
