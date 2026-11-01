package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

        /** The map's zoom the cards are drawn at. */
        double zoom();

        /** Whether the map flows across (left to right or back): connectors and cards then sit beside a row. */
        boolean horizontalFlow();

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

        /** A declaration row's menu, as the Map's own Structure column gives it. */
        ContextMenu symbolMenu(ProjectMapModel.Entry entry);

        /** The options menu's "Hide all open Personal Notes". */
        boolean hideOpenNotes();

        /** Turns "Hide all open Personal Notes" off (a card's Notes button asked for its notes). */
        void showOpenNotes();

        void openFile(Path file);

        void openAt(Path file, int line);

        void openNarrowed(Path file, FoldRegions.Region block);
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

    final Canvas previewConnectorCanvas = new Canvas(1, 1);
    /** Each code card's connector as last drawn, by key. For tests. */
    final Map<Path, PreviewConnector> previewConnectors = new HashMap<>();

    final Map<Path, ProjectMapPreview> previews = new LinkedHashMap<>(16, 0.75f, true);
    final Map<Path, ProjectMapNotePreview> notePreviews = new LinkedHashMap<>(16, 0.75f, true);
    /** The sticky notes a code card's Notes button stuck on it, by card. */
    private final Map<ProjectMapPreview, ProjectMapStickyNotes> stickyNotes = new java.util.IdentityHashMap<>();
    /** The Structure columns stuck on file cards (their Structure button). */
    final Map<ProjectMapPreview, ProjectMapCardStructure> cardStructures = new java.util.IdentityHashMap<>();
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
    private boolean disposed;

    private ProjectMapPreview.@Nullable MarkerActions previewMarkerActions;
    private ProjectPanel.@Nullable MarkerActions notePreviewActions;
    /** The text zoom of the card last zoomed with −/+: new cards open at it. */
    private double cardTextZoom = 1.0;
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
    }

    // --- API ------------------------------------------------------------------------------------------------

    /**
     * Opens (or brings up and focuses) the card of {@code file}, shown whole, with a Structure button when its language
     * can be outlined. Returns the card.
     */
    ProjectMapPreview openFileCard(Path file) {
        return previewFile(file.toAbsolutePath().normalize(), file);
    }

    /** Opens (or brings up and focuses) the card of a declaration row: its lines of its file. */
    void openDeclarationCard(ProjectMapModel.Entry symbolRow) {
        previewSymbol(symbolRow);
    }

    /** Opens (or brings up) the note card of a file or folder, when it has Personal Notes and notes are on. */
    void openNoteCard(Path path) {
        previewNotes(path);
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

    /** Whether any card is open. */
    boolean isEmpty() {
        return previews.isEmpty() && notePreviews.isEmpty();
    }

    /** Closes one code or note card, with what is stuck on it. */
    void close(Region card) {
        if (card instanceof ProjectMapPreview code) {
            closePreview(code);
        } else if (card instanceof ProjectMapNotePreview note) {
            closeNotePreview(note);
        }
    }

    /** Closes every card (a root change, the window's dispose, "close all cards"). */
    void closeAll() {
        closeAllPreviews();
    }

    /** Closes the cards of files at or under {@code directory} (its column closed). */
    void closeUnder(Path directory) {
        closePreviewsUnder(directory);
    }

    /**
     * Escape on the map: closes the cards on screen and says whether there were any. Cards hidden by "Hide unmatched"
     * or "Hide all open Personal Notes" are not on screen and stay.
     */
    boolean closeVisible() {
        List<ProjectMapPreview> cards =
                previews.values().stream().filter(ProjectMapPreview::isVisible).toList();
        List<ProjectMapNotePreview> notes = notePreviews.values().stream()
                .filter(ProjectMapNotePreview::isVisible)
                .toList();
        cards.forEach(this::closePreview);
        notes.forEach(this::closeNotePreview);
        return !cards.isEmpty() || !notes.isEmpty();
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
        }
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

    /** The path a card is open under now (a declaration card follows its declaration), else {@code fallback}. */
    private Path cardKeyOf(ProjectMapPreview card, Path fallback) {
        for (Map.Entry<Path, ProjectMapPreview> entry : previews.entrySet()) {
            if (entry.getValue() == card) {
                return entry.getKey();
            }
        }
        return fallback;
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
        preview.setOnOpenAt((path, line) -> host.openAt(path, line));
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

    /** A file's card under its normalized path {@code selected}, showing {@code shown} (the row's own path). */
    private ProjectMapPreview previewFile(Path selected, Path shown) {
        ProjectMapPreview preview = previews.get(selected);
        if (preview != null) {
            touchCard(preview, true);
            preview.focusContent();
            return preview;
        }
        preview = createPreview(selected);
        preview.setStructureAvailable(structure.supports(selected)); // as the row has its Structure icon
        ProjectMapPreview selectedPreview = preview;
        preview.showFile(
                shown,
                host.content(shown),
                (width, height, parentWidth, parentHeight) -> previewPlacement(
                        selectedPreview,
                        shown,
                        width,
                        height,
                        ProjectMapPreview.MIN_WIDTH,
                        ProjectMapPreview.MIN_HEIGHT,
                        parentWidth,
                        parentHeight));
        updateCardNotes(preview);
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
        makeRoomForCard(null);
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
        preview.setOnClose(() -> closePreview(preview));
        preview.setOnActivate(() -> touchCard(preview, true));
        preview.setOnTouch(() -> touchCard(preview, false));
        preview.setOnEscape(() -> {
            closePreview(preview);
            host.focusMap();
        });
        preview.setOnNotesToggled(shown -> showCardNotes(preview, shown));
        preview.setOnStructureToggled(shown -> showCardStructure(preview, shown));
        preview.setOnFocusStructure(() -> {
            ProjectMapCardStructure column = cardStructures.get(preview);
            if (column != null) {
                column.focus();
            }
        });
        preview.setNotesSuppressed(host.hideOpenNotes());
        preview.setOnShowSuppressedNotes(() -> host.showOpenNotes()); // the Notes button asks for them
        preview.setOnMoveToFreeSpace(() -> cardMoveToFreeSpace(preview, path));
        preview.setOnTextShown(() -> {
            updateCardNotes(preview); // stored notes are found again in the new text
            restyleCardStructure(preview);
        });
        preview.setOnStylesShown(() -> restyleCardStructure(preview)); // its Structure column borrows the colours
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
        return stuck;
    }

    /** Every attachment of every open code card. */
    private List<CardAttachment> allAttachments() {
        List<CardAttachment> all = new ArrayList<>(cardStructures.values());
        all.addAll(stickyNotes.values());
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
                            previewSymbol(symbolEntry(node));
                        },
                        node -> host.symbolMenu(symbolEntry(node)),
                        () -> touchCard(card, false),
                        this::connectorsMoved,
                        () -> reserveBesideCard(card)));
        structure.ensure(file);
        column.show(cardRowAfter(card, file));
        column.update(structure.view(file)); // shown first: its width is measured in the rows' font
        reserveBesideCard(card); // the sheets step past the column
        touchCard(card, true);
        scheduleCardRefresh(); // the outline follows the buffer from now on
    }

    /**
     * A click or Enter on a declaration in a file card's Structure column: the card scrolls to the declaration and
     * marks its lines (review 3, E8). A declaration past what the card shows opens its own card instead.
     */
    private void cardRevealDeclaration(ProjectMapPreview card, ProjectMapStructure.Node node) {
        touchCard(card, false);
        if (!card.revealRange(rangeOf(node.symbol()))) {
            previewSymbol(symbolEntry(node));
        }
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
    private void makeRoomForCard(@Nullable ProjectMapPreview keep) {
        while (previews.size() + notePreviews.size() >= MAX_OPEN_PREVIEWS) {
            Region oldest = oldestCard(card -> card != keep);
            if (oldest instanceof ProjectMapPreview code && cardStructures.containsKey(code)) {
                Set<ProjectMapPreview> members = cardGroupMembers(code);
                Region member = oldestCard(members::contains);
                if (member != null) {
                    oldest = member; // its owner waits until the cards it keeps are gone
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
                    || current.getStyleClass().contains("project-map-sticky-note")
                    || current.getStyleClass().contains("project-map-card-structure")) {
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
        if (preview == null) {
            return;
        }
        boolean focused = focusReturnsFrom(preview);
        previews.entrySet().removeIf(entry -> entry.getValue() == preview);
        cardTouches.remove(preview);
        closeCardNotes(preview);
        closeCardStructure(preview);
        if (canvasHost != null) {
            canvasHost.getChildren().remove(preview);
        }
        preview.dispose();
        repaintPreviewConnectors();
        focusReturnToMap(focused);
    }

    private void closeAllPreviews() {
        cardRefresh.stop();
        cardPoll.stop();
        cardDiskRefresh.stop();
        cardTouches.clear();
        List<ProjectMapPreview> open = List.copyOf(previews.values());
        previews.clear();
        List<CardAttachment> stuck = allAttachments();
        stickyNotes.clear();
        stuck.forEach(CardAttachment::dispose);
        cardStructures.clear();
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
        Path root = host.root();
        if (disposed || shown == null || root == null) {
            return;
        }
        List<ProjectMapPreview> closing = previews.entrySet().stream()
                .filter(entry -> cardOrphaned(root, entry.getKey(), shown))
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
     * {@link #orphaned}, except that a declaration card opened from a file card's Structure column stays while that
     * column is out and the file's own row is still on the map.
     */
    private boolean cardOrphaned(Path root, Path key, Set<Path> shown) {
        if (!orphaned(root, key, shown)) {
            return false;
        }
        Path file = ProjectMapStructure.isSymbolPath(key) ? structure.fileOf(key) : null;
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

    /**
     * {@link #previewPlacement} in panel pixels: every size here is a card as drawn. The spot that covers the least:
     * beside the row's column after it, before it, above or below the columns' band, or any free part of the
     * panel, scored by how much of the columns, the zoom bar, the overview and the other cards it would cover (see
     * {@link #cardOverlapCost}). The columns that lead to the row weigh most, and so does the band where the row's
     * next column — its folder's, or its file's Structure column — would open. The map is never panned. A card
     * used to take the side after its column, else the side before it, which is where the columns leading to it
     * are, and so often covered the very path to its row.
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
        Site site = host.site(path);
        Site beside = site.row() == null ? cardStructureSite(path) : null;
        if (beside != null) {
            site = beside;
        }
        double margin = ProjectMapPreview.EDGE_MARGIN;
        double roomWidth = parentWidth - margin * 2;
        double roomHeight = parentHeight - margin * 2;
        double fullWidth = cardBounded(width, minimumWidth, roomWidth);
        double fullHeight = cardBounded(height, minimumHeight, roomHeight);
        if (site.row() == null) {
            // The row is not on the map (filtered, scrolled off): against the panel's far edge, as before.
            return new ProjectMapPreview.Placement(
                    Math.max(margin, parentWidth - fullWidth - margin), margin, fullWidth, fullHeight);
        }
        List<double[]> obstacles = new ArrayList<>(site.obstacles());
        List<Region> cards = new ArrayList<>(previews.values());
        cards.addAll(notePreviews.values());
        for (CardAttachment stuck : allAttachments()) {
            double[] drawn = stuck.bounds();
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
        // Any free part of the panel: the card against each obstacle's edges, and against the panel's.
        java.util.TreeSet<Double> xs = new java.util.TreeSet<>(List.of(margin, parentWidth - fullWidth - margin));
        java.util.TreeSet<Double> ys = new java.util.TreeSet<>(List.of(margin, parentHeight - fullHeight - margin));
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
        double[] best = null;
        double bestCost = Double.MAX_VALUE;
        for (double[] spot : spots) {
            double w = spot[2];
            double h = spot[3];
            double x = clampInto(spot[0], margin, Math.max(margin, parentWidth - w - margin));
            double y = clampInto(spot[1], margin, Math.max(margin, parentHeight - h - margin));
            // Least covered first; between spots that cover alike, the one nearer its row, then the larger.
            double distance = Math.hypot(x + w / 2 - rowX, y + h / 2 - rowY);
            // A card cut narrower than its lines needs makes them scroll sideways: that weighs more than height.
            double cost = cardOverlapCost(x, y, w, h, obstacles)
                    + distance * 0.5
                    - w * h * 0.05
                    + Math.max(0, fullWidth - w) * h * 0.1;
            if (cost < bestCost) {
                bestCost = cost;
                best = new double[] {x, y, w, h};
            }
        }
        if (best == null) {
            return new ProjectMapPreview.Placement(margin, margin, fullWidth, fullHeight);
        }
        boolean growLeft = horizontal && best[0] + best[2] <= row[0];
        return new ProjectMapPreview.Placement(best[0], best[1], best[2], best[3], growLeft);
    }

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
        return new Site(row, drawn, drawn, List.of());
    }

    /** How much a card of the other cards weighs against a spot, per pixel it would cover (columns: 1–6). */
    private static final double CARD_WEIGHT_CARD = 5;

    /** Adds a spot whose extent along {@code axis} (0 height, 1 width) is at least the card's minimum there. */
    private static void cardSpot(
            List<double[]> spots, double x, double y, double w, double h, double minimum, int axis) {
        if ((axis == 1 ? w : h) >= minimum && w > 0 && h > 0) {
            spots.add(new double[] {x, y, w, h});
        }
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

    /** Draws the connectors now if something marked them (see {@link #connectorsMoved}). */
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
        g.setStroke(host.accent());
        g.setGlobalAlpha(0.78);
        g.setLineWidth(1.5);
        for (Map.Entry<Path, ProjectMapPreview> entry : previews.entrySet()) {
            ProjectMapPreview preview = entry.getValue();
            if (!preview.isVisible() || preview.getWidth() <= 0 || preview.getHeight() <= 0) {
                continue;
            }
            // A declaration in a file card's Structure column is tied to its card from there, as a row is.
            double[] row = cardStructureRow(entry.getKey());
            if (row != null) {
                previewConnectors.put(
                        entry.getKey(), drawPreviewConnector(g, row[0], row[1], row[2], row[3], true, preview));
                continue;
            }
            double[] anchor = host.rowBox(entry.getKey());
            if (anchor != null) {
                previewConnectors.put(entry.getKey(), drawPreviewConnector(g, anchor, preview));
            }
        }
        g.setStroke(host.note());
        for (Map.Entry<Path, ProjectMapNotePreview> entry : notePreviews.entrySet()) {
            ProjectMapNotePreview preview = entry.getValue();
            double[] anchor = host.rowBox(entry.getKey());
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
