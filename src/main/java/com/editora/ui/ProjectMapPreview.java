package com.editora.ui;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.TraversalDirection;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.IndexRange;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.transform.Scale;

import com.editora.config.NoteScope;
import com.editora.config.PathDisplay;
import com.editora.config.PersonalNote;
import com.editora.config.TextAnchor;
import com.editora.editops.LineEndings;
import com.editora.editor.highlight.GrammarRegistry;
import com.editora.editor.highlight.TextMateHighlighter;
import com.editora.editor.markdown.MarkdownFormatBar;
import com.editora.editor.notes.NoteDraft;
import com.editora.editor.notes.NoteHighlightOverlay;
import com.editora.editorconfig.EditorConfigCharset;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.TwoDimensional;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * A bounded, read-only editor card on the Project map. It pans and zooms with the map — drawn at the map's
 * zoom from its top-left corner, so its own size is in unzoomed units — and like a column it may be dragged
 * past the visible part of the canvas.
 */
final class ProjectMapPreview extends StackPane {

    static final int MAX_PREVIEW_CHARS = 400_000;

    private static final int MAX_PREVIEW_BYTES = 1_000_000;
    private static final int MAX_IMAGE_BYTES = 20_000_000;
    private static final int MAX_HIGHLIGHT_CHARS = 160_000;
    private static final double DEFAULT_WIDTH = 640;
    private static final double DEFAULT_HEIGHT = 420;
    static final double MIN_WIDTH = 340;
    static final double MIN_HEIGHT = 220;
    static final double EDGE_MARGIN = 14;
    private static final double TEXT_CHROME_WIDTH = 72;
    /** A card's title bar, borders and scroll-bar room around its text, vertically. */
    private static final double TEXT_CHROME_HEIGHT = 58;

    private static final int TAB_COLUMNS = 4;
    /** A card never widens itself past this many columns; longer lines scroll. */
    private static final int MAX_FIT_COLUMNS = 120;
    /** Under this width the title bar keeps only the name, the read-only badge, Open and Close. */
    private static final double COMPACT_WIDTH = 330;

    /**
     * What the editor knows about a file the card shows. For a file that is open in a tab it is the buffer's
     * current text (captured on the FX thread, unsaved changes included); for any other file only the
     * {@code .editorconfig} charset the editor would decode it with.
     *
     * <p>An open buffer's text may be handed over unread, with the buffer's edit counter as its {@link #version}:
     * the once-a-second poll then skips a buffer that did not change without copying its text, and the text is
     * read at most once per poll however many cards and structure columns show the file.
     */
    static final class Content {
        private final java.util.function.@Nullable Supplier<String> source;
        private @Nullable String text;
        private final boolean truncated;
        private final boolean open;
        private final @Nullable String editorConfigCharset;
        private final long version;

        Content(@Nullable String text, boolean truncated, boolean open, @Nullable String editorConfigCharset) {
            this.source = null;
            this.text = text == null ? "" : text;
            this.truncated = truncated;
            this.open = open;
            this.editorConfigCharset = editorConfigCharset;
            this.version = -1;
        }

        /** An open buffer's text. */
        Content(String text, boolean truncated) {
            this(text, truncated, true, null);
        }

        private Content(java.util.function.Supplier<String> source, boolean truncated, long version) {
            this.source = source;
            this.truncated = truncated;
            this.open = true;
            this.editorConfigCharset = null;
            this.version = version;
        }

        /**
         * An open buffer's text, read from {@code text} when first asked for (on the FX thread, in the same pass);
         * {@code version} changes whenever the buffer's text does.
         */
        static Content buffer(java.util.function.Supplier<String> text, boolean truncated, long version) {
            return new Content(text, truncated, version);
        }

        /** A file that is not open: it is read from disk, decoded the way the editor would open it. */
        static Content closed(@Nullable String editorConfigCharset) {
            return new Content("", false, false, editorConfigCharset);
        }

        String text() {
            String read = text;
            if (read == null) {
                try {
                    read = source == null ? "" : source.get();
                } catch (RuntimeException unreadable) {
                    ProjectMapLog.unexpected("reading a card's buffer text", unreadable);
                    read = "";
                }
                text = read == null ? "" : read;
                read = text;
            }
            return read;
        }

        boolean truncated() {
            return truncated;
        }

        boolean open() {
            return open;
        }

        @Nullable
        String editorConfigCharset() {
            return editorConfigCharset;
        }

        /** The buffer's edit counter when the text was handed over, or -1 when unknown (then compare the text). */
        long version() {
            return version;
        }
    }

    /**
     * Where a card opens.
     *
     * @param growLeft the card sits before its column, so it widens leftwards and keeps its right edge
     */
    record Placement(double x, double y, double width, double height, boolean growLeft) {
        Placement(double x, double y, double width, double height) {
            this(x, y, width, height, false);
        }
    }

    @FunctionalInterface
    interface PlacementResolver {
        Placement resolve(double width, double height, double parentWidth, double parentHeight);
    }

    /**
     * The part of its file a declaration card shows (see {@link #showRange}).
     *
     * @param symbol the declaration's label, shown after the file name in the title
     * @param line the 0-based line it is declared on: where Open puts the caret
     * @param startLine the 0-based first line shown
     * @param endLine the 0-based last line shown
     */
    record Range(String symbol, int line, int startLine, int endLine) {}

    interface MarkerActions {
        boolean personalNotesEnabled();

        void addBookmark(Path file, int line);

        void addPersonalNote(Path file, NoteDraft draft);
    }

    private enum LoadProblem {
        NONE,
        BINARY,
        /** An image over {@link #MAX_IMAGE_BYTES}: not decoded. */
        IMAGE_TOO_LARGE,
        FAILED,
        MISSING
    }

    /** What a file looked like when it was read, to tell later whether it changed on disk. */
    private record Stamp(long size, long modifiedMillis) {}

    private record Loaded(
            String text,
            @Nullable Image image,
            boolean truncated,
            LoadProblem problem,
            @Nullable Stamp stamp) {}

    private final Consumer<Path> onOpenFile;
    private final BorderPane frame = new BorderPane();
    private final HBox titleBar = new HBox(7);
    private final Label title = new Label();
    private final Label status = new Label();
    private final Label readOnly = new Label();
    private final Button zoomOut = new Button("−");
    private final Button zoomIn = new Button("+");
    /** Shows or hides this card's Personal Notes, stuck beside it (see {@link ProjectMapStickyNotes}). */
    private final ToggleButton notes = new ToggleButton();

    private final Button open = new Button();
    private final Button close = new Button("×");
    /** The floating bar over a selection in the text, as in a Markdown buffer: here it adds a Personal Note. */
    private final HBox selectionBar = new HBox(2);

    private final Button selectionNote = new Button();
    private final CodeArea editor = AreaUndo.none(new CodeArea());
    private final VirtualizedScrollPane<CodeArea> editorScroll = new VirtualizedScrollPane<>(editor);
    /** The amber wash and corner marker an editor tab paints on a Personal Note's text, here for the notes shown. */
    private final NoteHighlightOverlay noteMarks = new NoteHighlightOverlay(editor);
    /** The text with its note marks over it: what the card shows for anything but an image. */
    private final StackPane textPane = new StackPane(editorScroll, noteMarks);
    /** The notes stuck on the card, whose text {@link #noteMarks} paints (see {@link #markNotes}). */
    private java.util.List<PersonalNote> marked = java.util.List.of();

    private final ContextMenu editorContextMenu = new ContextMenu();
    private final ImageView imageView = new ImageView();
    private final ScrollPane imageScroll = new ScrollPane(imageView);
    private final Region resizeGrip = new Region();
    /**
     * The reads, stats, decodes and highlights of every card, in every window: two daemon threads that time out
     * when idle. A card used to own a thread for as long as it was open (eight cards, eight idle threads, kept
     * while the Map window was closed). Each card still runs its own work in order, through {@link #tasks}; the
     * per-card {@link #generation} makes a result that arrives late harmless.
     */
    private static final ThreadPoolExecutor SHARED_LOADER = sharedLoader();

    private static ThreadPoolExecutor sharedLoader() {
        ThreadPoolExecutor executor =
                new ThreadPoolExecutor(2, 2, 3, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
                    Thread thread = new Thread(r, "project-map-preview-loader");
                    thread.setDaemon(true);
                    return thread;
                });
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /** This card's queued background work, run one task at a time on {@link #SHARED_LOADER}. Guarded by itself. */
    private final java.util.ArrayDeque<Runnable> tasks = new java.util.ArrayDeque<>();
    /** A {@link #drainTasks} pass is queued or running for this card. Guarded by {@link #tasks}. */
    private boolean draining;

    private final AtomicLong generation = new AtomicLong();

    private @Nullable Path path;
    private boolean disposed;
    private boolean placed;
    private boolean placementPending;
    /** The card opened before its column: fitting the text widens it leftwards. */
    private boolean growLeft;
    /** The user dragged the card, so nothing may move it for them again. */
    private boolean userMoved;
    /** The user resized the card, so it keeps that size. */
    private boolean userSized;
    /** The text in the editor, kept to tell a real change from a refresh that changes nothing. */
    private String shownText = "";
    /** The text came from an open buffer rather than from disk. */
    private boolean fromBuffer;
    /** The buffer's edit counter the shown text came with ({@link Content#version}), or -1. */
    private long shownVersion = -1;
    /** A declaration card's lines, or null for a card that shows its whole file. */
    private @Nullable Range range;
    /** The whole text a declaration card's lines were cut from ({@link #shownText} is the cut). */
    private String fullText = "";
    /** Where the shown lines start in {@link #fullText}. */
    private int sliceOffset;
    /** Lines in {@link #fullText}. */
    private int fullLines;
    /** The text shown is cut short at the read or display cap. */
    private boolean truncatedShown;
    /** The declaration this card shows is no longer in its file: the card keeps its last lines. */
    private boolean symbolMissing;
    /** A declaration card's declaration line as last shown: where to look for it in edited text. */
    private String declarationText = "";
    /**
     * Text that arrived for a declaration card in which its declaration line could not be found: held, the old
     * lines still shown, until the new outline says where the declaration went or that it is gone. Cut by the
     * old lines, it used to show whatever now stood there under the declaration's title.
     */
    private @Nullable String heldText;

    private boolean heldTruncated;
    /** The card mirrors a buffer with unsaved changes (see {@link #setUnsaved}). */
    private boolean unsaved;
    /** Over the highlighting cap, only this many first lines were coloured; 0 when all or none were. */
    private int colouredLines;
    /** Shows the card's notes even while the Notes button looks off ("Hide all open Personal Notes" is on). */
    private boolean notesOn;
    /** "Hide all open Personal Notes" is on: the card's notes are out of sight whatever the button says. */
    private boolean notesSuppressed;

    private Runnable onShowSuppressedNotes = () -> {};
    private Consumer<Path> onReveal = file -> com.editora.process.DesktopActions.reveal(file, false, error -> {});
    private Runnable onMoveToFreeSpace = () -> {};
    private Runnable onTextShown = () -> {};
    private java.util.function.DoubleConsumer onContentZoomed = zoom -> {};

    private java.util.function.@Nullable BiConsumer<Path, Integer> onOpenAt;
    private @Nullable Runnable onOpenNarrowed;

    private boolean loading;
    private boolean statPending;
    private boolean missing;
    private @Nullable String editorConfigCharset;
    private @Nullable Stamp loadedStamp;
    private @Nullable PlacementResolver placementResolver;
    private @Nullable MarkerActions markerActions;
    private double preferredWidth = DEFAULT_WIDTH;
    private double preferredHeight = DEFAULT_HEIGHT;
    /** The map's zoom, applied from the card's top-left corner. */
    private final Scale mapZoom = new Scale(1, 1, 0, 0);

    private double dragScreenX;
    private double dragScreenY;
    private double dragLayoutX;
    private double dragLayoutY;
    private double resizeScreenX;
    private double resizeScreenY;
    private double resizeWidth;
    private double resizeHeight;
    private double contentZoom = 1.0;
    private Runnable onClose = this::hidePreview;
    private Runnable onActivate = () -> {};
    private Runnable onTouch = () -> {};
    private Runnable onEscape = () -> onClose.run();
    private Consumer<Boolean> onNotesToggled = shown -> {};
    /** How many of the store's notes this card shows (see {@link #setNoteCount}). */
    private int noteCount;
    /** A note was asked for here: the notes come up when the store has one more. */
    private boolean revealNotesOnAdd;

    private boolean selectionBarPending;

    ProjectMapPreview(Consumer<Path> onOpenFile) {
        this.onOpenFile = onOpenFile == null ? ignored -> {} : onOpenFile;
        getStyleClass().add("project-map-preview");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        // The note and bookmark chords (C-c n, C-c m in Emacs) mark this card's file, not the active tab's.
        getProperties().put(com.editora.command.KeyDispatcher.HANDLED_COMMANDS, (java.util.function.Predicate<String>)
                this::runMarkerCommand);
        // Alt+arrows move the card and Alt+Shift+arrows resize it (history and word moves elsewhere).
        getProperties().put(com.editora.command.KeyDispatcher.CLAIMED_KEYS, GEOMETRY_KEYS);
        setManaged(false);
        setVisible(false);

        title.getStyleClass().add("project-map-preview-title");
        title.setMinWidth(0);
        title.setTextOverrun(OverrunStyle.ELLIPSIS);
        status.getStyleClass().add("project-map-preview-status");
        status.setMinWidth(0);
        status.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(status, Priority.ALWAYS);
        readOnly.setText(tr("project.map.preview.readOnly"));
        readOnly.getStyleClass().add("project-map-preview-readonly");

        for (Button button : java.util.List.of(zoomOut, zoomIn)) {
            button.getStyleClass().add("project-map-preview-button");
        }
        zoomOut.setTooltip(new Tooltip(tr("project.map.preview.zoomOut")));
        zoomOut.setAccessibleText(tr("project.map.preview.zoomOut"));
        zoomIn.setTooltip(new Tooltip(tr("project.map.preview.zoomIn")));
        zoomIn.setAccessibleText(tr("project.map.preview.zoomIn"));
        zoomOut.setOnAction(event -> {
            setContentZoom(contentZoom / 1.1);
            onContentZoomed.accept(contentZoom); // the owner opens its next card at this zoom
        });
        zoomIn.setOnAction(event -> {
            setContentZoom(contentZoom * 1.1);
            onContentZoomed.accept(contentZoom);
        });

        open.setText(tr("project.map.preview.open"));
        open.getStyleClass().add("project-map-preview-button");
        open.setTooltip(new Tooltip(tr("project.map.preview.openHelp")));
        open.setOnAction(event -> {
            Path selected = path;
            Range shown = range;
            if (selected != null && shown != null && onOpenAt != null) {
                onOpenAt.accept(selected, shown.line()); // a declaration card opens the file at the declaration
            } else if (selected != null) {
                onOpenFile.accept(selected);
            }
        });
        notes.getStyleClass().addAll("project-map-preview-button", "project-map-preview-notes");
        notes.setGraphic(Icons.notes());
        notes.setTooltip(new Tooltip());
        notes.setVisible(false);
        notes.setManaged(false);
        notes.setOnAction(event -> {
            revealNotesOnAdd = false;
            if (notesSuppressed) {
                // "Hide all open Personal Notes" is on: the press asks to see notes again, so it turns that off.
                updateNotesButton();
                onShowSuppressedNotes.run();
                if (!notesOn) {
                    setNotesShown(true);
                }
                return;
            }
            notesOn = notes.isSelected();
            updateNotesButton();
            onNotesToggled.accept(notesOn);
        });
        close.getStyleClass().add("project-map-preview-button");
        close.setTooltip(new Tooltip(tr("project.map.preview.close")));
        close.setAccessibleText(tr("project.map.preview.close"));
        close.setOnAction(event -> onClose.run());

        titleBar.getStyleClass().add("project-map-preview-header");
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.getChildren().setAll(title, readOnly, status, zoomOut, zoomIn, notes, open, close);
        titleBar.addEventHandler(MouseEvent.MOUSE_PRESSED, this::dragPressed);
        titleBar.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::dragged);

        editor.getStyleClass().addAll("editor-area", "project-map-preview-editor");
        editor.setEditable(false);
        editor.setWrapText(false);
        editor.setParagraphGraphicFactory(LineNumberFactory.get(editor));
        editor.setAccessibleHelp(tr("project.map.preview.accessibleHelp"));
        installEditorContextMenu();
        frame.getStyleClass().add("project-map-preview-frame");
        frame.setTop(titleBar);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        imageScroll.setPannable(true);
        imageScroll.setFitToWidth(false);
        imageScroll.setFitToHeight(false);
        frame.setCenter(textPane);
        noteMarks.setSpans(this::noteSpans, () -> !marked.isEmpty()); // inert until notes are stuck on the card
        noteMarks.setActive(true);

        resizeGrip.getStyleClass().add("project-map-preview-resize");
        resizeGrip.setCursor(Cursor.SE_RESIZE);
        resizeGrip.setAccessibleText(tr("project.map.preview.resize"));
        resizeGrip.addEventHandler(MouseEvent.MOUSE_PRESSED, this::resizePressed);
        resizeGrip.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::resized);
        installSelectionBar();
        getChildren().addAll(frame, resizeGrip, selectionBar);
        getTransforms().add(mapZoom);

        addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            toFront();
            onActivate.run();
        });
        // Reading by keyboard or wheel is use as much as a click is: it must keep the card from being the
        // one evicted when a ninth opens.
        focusWithinProperty().addListener((obs, was, within) -> {
            if (within) {
                toFront();
                onActivate.run();
            }
        });
        addEventFilter(ScrollEvent.SCROLL, event -> onTouch.run());
        addEventFilter(KeyEvent.KEY_PRESSED, this::keyPressed);
        setContentZoom(1.0);
    }

    /** The text zoom the card opens at: the owner's last one, so −/+ need not be pressed on every new card. */
    void setInitialContentZoom(double zoom) {
        setContentZoom(zoom);
    }

    /** Told the card's text zoom after −/+. */
    void setOnContentZoomed(java.util.function.DoubleConsumer callback) {
        onContentZoomed = callback == null ? zoom -> {} : callback;
    }

    /**
     * Escape closes the card (its owner then returns focus to the map), and Tab leaves the text: a read-only
     * area has no use for a tab character, and swallowing the key trapped keyboard focus in the card.
     */
    private void keyPressed(KeyEvent event) {
        double[] step = geometryStep(event);
        if (step != null) {
            moveOrResizeBy(step);
            event.consume();
            return;
        }
        if (event.isControlDown() || event.isAltDown() || event.isMetaDown()) {
            return;
        }
        if (event.getCode() == KeyCode.ESCAPE && !event.isShiftDown()) {
            if (editorContextMenu.isShowing() || menuDismissedByThisKey) {
                menuDismissedByThisKey = false;
                editorContextMenu.hide();
            } else if (selectionBar.isVisible()) {
                selectionBar.setVisible(false); // as in an editor: the bar goes first, the card on the next Escape
            } else {
                onEscape.run();
            }
            event.consume();
        } else if (event.getCode() == KeyCode.TAB && event.getTarget() == editor) {
            editor.requestFocusTraversal(event.isShiftDown() ? TraversalDirection.PREVIOUS : TraversalDirection.NEXT);
            event.consume();
        }
    }

    /** The marker commands a focused card runs on its own file and caret (see {@code KeyDispatcher}). */
    static final java.util.Set<String> MARKER_COMMANDS =
            java.util.Set.of("notes.add", "notes.editNote", "bookmarks.toggle");

    /**
     * Runs a note or bookmark command typed in the card on the card's file: a note on the selection or the caret
     * line, a bookmark on the caret line, and Edit Note brings up the notes stuck on the card. Every other command
     * is left to the keymap. A marker command is taken even when there is nothing to mark here, so it can never
     * fall through to the active editor tab's file.
     */
    boolean runMarkerCommand(String commandId) {
        if (!MARKER_COMMANDS.contains(commandId)) {
            return false;
        }
        Path file = path;
        MarkerActions actions = markerActions;
        if (symbolMissing
                || disposed
                || file == null
                || actions == null
                || frame.getCenter() != textPane
                || editor.getLength() == 0) {
            return true;
        }
        int line = editor.getCurrentParagraph();
        switch (commandId) {
            case "bookmarks.toggle" -> actions.addBookmark(file, fileLine(line));
            case "notes.add" -> {
                if (actions.personalNotesEnabled()) {
                    requestNote(actions, file, noteDraftAt(line));
                }
            }
            default -> setNotesShown(true); // notes.editNote: the notes stuck on the card are where they are edited
        }
        return true;
    }

    /** The file's 0-based line for a line of the card's text (a declaration card starts at its first line). */
    private int fileLine(int cardLine) {
        Range shown = range;
        return cardLine + (shown == null ? 0 : shown.startLine());
    }

    /** How far one Alt+arrow moves a card, or one Alt+Shift+arrow resizes it, in panel pixels. */
    static final double KEYBOARD_STEP = 16;

    /** The keys a card takes for moving and resizing itself, whatever the keymap binds them to. */
    static final java.util.Set<String> GEOMETRY_KEYS =
            java.util.Set.of("M-left", "M-right", "M-up", "M-down", "M-S-left", "M-S-right", "M-S-up", "M-S-down");

    /**
     * {@code {dx, dy, dWidth, dHeight}} in panel pixels for a card's move and resize keys — Alt+arrows move it
     * {@value #KEYBOARD_STEP} px, Alt+Shift+arrows resize it — or null for any other key. Pure.
     */
    static double @Nullable [] geometryStep(KeyEvent event) {
        if (event.getEventType() != KeyEvent.KEY_PRESSED
                || !event.isAltDown()
                || event.isControlDown()
                || event.isMetaDown()) {
            return null;
        }
        double x = switch (event.getCode()) {
            case LEFT -> -KEYBOARD_STEP;
            case RIGHT -> KEYBOARD_STEP;
            default -> 0;
        };
        double y = switch (event.getCode()) {
            case UP -> -KEYBOARD_STEP;
            case DOWN -> KEYBOARD_STEP;
            default -> 0;
        };
        if (x == 0 && y == 0) {
            return null;
        }
        return event.isShiftDown() ? new double[] {0, 0, x, y} : new double[] {x, y, 0, 0};
    }

    /** One keyboard move or resize step; the card then keeps its place or size, as after a drag. */
    private void moveOrResizeBy(double[] step) {
        double scale = mapScale();
        if (step[0] != 0 || step[1] != 0) {
            userMoved = true;
            relocate(getLayoutX() + step[0], getLayoutY() + step[1]);
        } else if (getParent() instanceof Region parent) {
            userSized = true;
            preferredWidth = boundedSize(
                    getWidth() + step[2] / scale, MIN_WIDTH, Math.max(1, unzoomedRoom(parent.getWidth(), scale)));
            preferredHeight = boundedSize(
                    getHeight() + step[3] / scale, MIN_HEIGHT, Math.max(1, unzoomedRoom(parent.getHeight(), scale)));
            resize(preferredWidth, preferredHeight);
            requestLayout();
        }
        announceGeometry(this, scale);
    }

    /** Puts a card's place and size in its accessible help, and tells a screen reader it changed. */
    static void announceGeometry(Region card, double scale) {
        card.setAccessibleHelp(tr(
                "project.map.preview.geometry",
                Math.round(card.getLayoutX()),
                Math.round(card.getLayoutY()),
                Math.round(card.getWidth() * scale),
                Math.round(card.getHeight() * scale)));
        card.notifyAccessibleAttributeChanged(javafx.scene.AccessibleAttribute.HELP);
    }

    void showFile(Path file, @Nullable Content content, PlacementResolver placementResolver) {
        range = null;
        show(file, content, placementResolver);
    }

    /**
     * Shows only {@code shown}'s lines of {@code file}: a declaration's block, picked from a structure column. The
     * gutter keeps the file's line numbers (in the narrowed colour), the title names the declaration, and Open
     * opens the file at the declaration. The whole file is still what is read and highlighted, so the lines are
     * coloured as they are in the editor.
     */
    void showRange(Path file, Range shown, @Nullable Content content, PlacementResolver placementResolver) {
        range = shown;
        show(file, content, placementResolver);
    }

    /**
     * Moves a declaration card to {@code shown}'s lines — its file was edited and the declaration moved or grew.
     * The card is not placed again; its lines are cut afresh from the text it holds.
     */
    void setRange(Range shown) {
        String held = heldText;
        if (disposed || range == null || shown == null || shown.equals(range) && held == null) {
            return;
        }
        range = shown;
        updateRangeChrome();
        String source = held != null ? held : fullText;
        boolean truncated = held != null ? heldTruncated : truncatedShown;
        heldText = null;
        if (!source.isEmpty() && path != null) {
            applyText(source, truncated);
            highlight(generation.incrementAndGet(), path, source, truncated);
        }
    }

    /** The declaration this card shows was not found in its file the last time it was outlined. */
    void setSymbolMissing(boolean gone) {
        if (range == null || symbolMissing == gone) {
            return;
        }
        symbolMissing = gone; // the held text, if any, stays held: the old lines are the last copy to read
        status.setText(idleStatus(truncatedShown));
        if (gone) {
            getStyleClass().add("project-map-preview-missing");
        } else if (!missing) {
            getStyleClass().remove("project-map-preview-missing");
        }
    }

    @Nullable
    Range range() {
        return range;
    }

    /** Where a declaration card's Open goes: its file, at its declaration's line. */
    void setOnOpenAt(java.util.function.BiConsumer<Path, Integer> callback) {
        onOpenAt = callback;
    }

    /** The card menu's Open Narrowed: the file opens narrowed to the declaration's block. */
    void setOnOpenNarrowed(Runnable callback) {
        onOpenNarrowed = callback;
    }

    private void updateRangeChrome() {
        Path shownPath = path;
        if (shownPath == null) {
            return;
        }
        String name = PathDisplay.fileName(shownPath);
        Range shown = range;
        if (shown == null) {
            title.setText(name);
            title.setTooltip(new Tooltip(shownPath.toString()));
            setAccessibleText(tr("project.map.preview.accessible", shownPath.toString()));
            editor.setAccessibleText(tr("project.map.preview.editorAccessible", name));
            editor.getStyleClass().remove("narrowed");
            editor.setParagraphGraphicFactory(LineNumberFactory.get(editor));
            return;
        }
        title.setText(name + " › " + shown.symbol());
        title.setTooltip(new Tooltip(shownPath + "\n" + rangeLabel(shown)));
        setAccessibleText(tr("project.map.preview.symbolAccessible", shown.symbol(), shownPath.toString()));
        editor.setAccessibleText(tr("project.map.preview.symbolEditorAccessible", shown.symbol(), name));
        if (!editor.getStyleClass().contains("narrowed")) {
            editor.getStyleClass().add("narrowed"); // the editor's narrowed gutter colour
        }
        editor.setParagraphGraphicFactory(offsetLineNumbers(shown.startLine(), shown.endLine()));
    }

    private String rangeLabel(Range shown) {
        return tr("project.map.preview.lines", shown.startLine() + 1, shown.endLine() + 1, Math.max(1, fullLines));
    }

    /** The status line when nothing is happening: truncated, a declaration that went, or a card's lines. */
    private String idleStatus(boolean truncated) {
        java.util.List<String> parts = new java.util.ArrayList<>(3);
        Range shown = range;
        if (truncated) {
            parts.add(tr("project.map.preview.truncated"));
        } else if (shown != null && symbolMissing) {
            parts.add(tr("project.map.preview.symbolMissing"));
        } else if (shown != null && fullLines > 0) {
            parts.add(rangeLabel(shown));
        }
        if (colouredLines > 0) {
            parts.add(tr("project.map.preview.partlyColoured", colouredLines));
        }
        if (unsaved && fromBuffer) {
            parts.add(tr("project.map.preview.unsaved"));
        }
        return String.join(" · ", parts);
    }

    /**
     * Whether the open buffer the card mirrors has unsaved changes: the status says so, since the card then
     * shows text that is not what is on disk.
     */
    void setUnsaved(boolean modified) {
        if (unsaved == modified) {
            return;
        }
        unsaved = modified;
        if (!loading) {
            status.setText(idleStatus(truncatedShown));
        }
    }

    /** Gutter numbers that count from the declaration's first line, as the file numbers it. */
    private static java.util.function.IntFunction<javafx.scene.Node> offsetLineNumbers(int startLine, int endLine) {
        String format = "%" + String.valueOf(endLine + 1).length() + "d";
        return paragraph -> {
            Label number = new Label(String.format(java.util.Locale.ROOT, format, startLine + paragraph + 1));
            number.getStyleClass().add("lineno");
            number.setAlignment(Pos.TOP_RIGHT);
            number.setFont(Font.font("monospace", 12));
            return number;
        };
    }

    /**
     * What the card shows of {@code text}: all of it, or a declaration card's lines (and where they start, in
     * {@link #sliceOffset}). The whole text is kept in {@link #fullText}.
     */
    private String visible(String text) {
        fullText = text;
        fullLines = 1;
        for (int i = text.indexOf('\n'); i >= 0; i = text.indexOf('\n', i + 1)) {
            fullLines++;
        }
        Range shown = range;
        if (shown == null) {
            sliceOffset = 0;
            return text;
        }
        int first = Math.max(0, Math.min(shown.startLine(), fullLines - 1));
        int last = Math.max(first, Math.min(shown.endLine(), fullLines - 1));
        int start = com.editora.editor.fold.NarrowLines.offsetOfLine(text, first);
        int lastStart = com.editora.editor.fold.NarrowLines.offsetOfLine(text, last);
        int end = text.indexOf('\n', lastStart);
        sliceOffset = start;
        return text.substring(start, end < 0 ? text.length() : end);
    }

    private void show(Path file, @Nullable Content content, PlacementResolver placementResolver) {
        if (disposed || file == null) {
            return;
        }
        long requested = generation.incrementAndGet();
        path = file.toAbsolutePath().normalize();
        this.placementResolver = placementResolver;
        preferredWidth = DEFAULT_WIDTH;
        preferredHeight = range == null ? DEFAULT_HEIGHT : fittedHeight(range);
        placed = false;
        placementPending = true;
        userMoved = false;
        userSized = false;
        missing = false;
        loadedStamp = null;
        boolean openBuffer = content != null && content.open();
        fromBuffer = openBuffer;
        shownVersion = openBuffer && content != null ? content.version() : -1;
        editorConfigCharset = content == null ? null : content.editorConfigCharset();
        String name = PathDisplay.fileName(path);
        title.setGraphic(FileIcons.forProjectItem(name, false));
        // The text area is what a screen reader lands on, and it looks like an editor: name the file it
        // shows and say it cannot be edited, since global chords typed here still act on the active tab.
        symbolMissing = false;
        heldText = null;
        declarationText = "";
        colouredLines = 0;
        fullText = "";
        fullLines = 0;
        truncatedShown = false;
        updateRangeChrome();
        open.setDisable(false);
        frame.setCenter(textPane);
        getStyleClass().remove("project-map-preview-missing");
        status.setText(openBuffer ? "" : tr("project.map.preview.loading"));
        shownText = "";
        editor.replaceText("");
        setVisible(true);
        toFront();

        if (openBuffer && content != null) {
            String text = capText(content.text(), content.truncated());
            boolean truncated =
                    content.truncated() || text.length() < content.text().length();
            // Known before the card is placed, so it is placed at this size.
            preferredWidth = fittedWidth(visible(text));
            ensurePlaced();
            showLoaded(requested, path, new Loaded(text, null, truncated, LoadProblem.NONE, null));
            highlight(requested, path, text, truncated);
            return;
        }
        ensurePlaced();
        loadFromDisk(requested, path);
    }

    private void loadFromDisk(long requested, Path requestedPath) {
        String charset = editorConfigCharset;
        loading = true;
        submitLatest(() -> {
            Loaded loaded = load(requestedPath, charset);
            Platform.runLater(() -> {
                if (requested == generation.get()) {
                    loading = false;
                }
                if (showLoaded(requested, requestedPath, loaded)) {
                    highlight(requested, requestedPath, loaded.text(), loaded.truncated());
                }
            });
        });
    }

    /**
     * Brings an open card up to date without moving, resizing or re-scrolling it. {@code content} is the
     * editor's current view of the file (see {@link Content}), or null when it knows nothing. An open
     * buffer's text replaces only the range that changed; any other file is re-read when its size or
     * modification time differs from what was loaded, and marked when it no longer exists.
     */
    void refresh(@Nullable Content content) {
        if (disposed || path == null) {
            return;
        }
        if (content != null && content.open()) {
            if (fromBuffer
                    && !missing
                    && content.version() >= 0
                    && content.version() == shownVersion
                    && frame.getCenter() == textPane) {
                return; // the buffer was not edited: its text is not even read
            }
            String text = capText(content.text(), content.truncated());
            boolean truncated =
                    content.truncated() || text.length() < content.text().length();
            shownVersion = content.version();
            if (fromBuffer
                    && !missing
                    && (text.equals(fullText) || text.equals(heldText))
                    && frame.getCenter() == textPane) {
                if (text.equals(fullText)) {
                    heldText = null; // back to the text on show (an undo): nothing is waiting any more
                }
                return;
            }
            long requested = generation.incrementAndGet();
            loading = false;
            fromBuffer = true;
            loadedStamp = null;
            setMissing(false);
            if (showText(text, truncated)) {
                highlight(requested, path, text, truncated);
            }
            return;
        }
        editorConfigCharset = content == null ? editorConfigCharset : content.editorConfigCharset();
        if (fromBuffer) {
            // Its tab was closed: what is on disk is the file again (unsaved text went with the tab).
            fromBuffer = false;
            shownVersion = -1;
            loadedStamp = null;
            loadFromDisk(generation.incrementAndGet(), path);
            return;
        }
        if (loading || statPending) {
            return;
        }
        long requested = generation.get();
        Path requestedPath = path;
        statPending = true;
        runInBackground(() -> {
            Stamp current = stamp(requestedPath);
            boolean gone = current == null && !Files.exists(requestedPath);
            Platform.runLater(() -> {
                statPending = false;
                if (disposed || requested != generation.get() || !requestedPath.equals(path)) {
                    return;
                }
                if (gone) {
                    setMissing(true);
                } else if (missing || current != null && !current.equals(loadedStamp)) {
                    loadFromDisk(generation.incrementAndGet(), requestedPath); // equals path
                }
            });
        });
    }

    /** True while the card mirrors an open buffer, whose edits arrive without any filesystem event. */
    boolean showsOpenBuffer() {
        return fromBuffer;
    }

    /** Moves keyboard focus into the card's content. */
    void focusContent() {
        if (frame.getCenter() == imageScroll) {
            imageScroll.requestFocus();
        } else {
            editor.requestFocus();
        }
    }

    private void setMissing(boolean gone) {
        if (missing == gone) {
            return;
        }
        missing = gone;
        open.setDisable(gone);
        if (gone) {
            // The text stays: it may be the last copy the user can read. The card says what happened.
            getStyleClass().add("project-map-preview-missing");
            status.setText(tr("project.map.preview.missing"));
        } else {
            getStyleClass().remove("project-map-preview-missing");
            status.setText("");
        }
    }

    void hidePreview() {
        generation.incrementAndGet();
        editorContextMenu.hide();
        selectionBar.setVisible(false);
        path = null;
        shownText = "";
        editor.replaceText("");
        status.setText("");
        setVisible(false);
    }

    @Nullable
    Path path() {
        return path;
    }

    /**
     * The file this card shows was renamed or moved inside Editora: the card follows it to {@code to} where
     * it is, keeping its place, size and declaration range, and reads it again there (a card that mirrors a
     * buffer gets the buffer's text with the next refresh instead).
     */
    void pathRenamed(Path to) {
        if (disposed || path == null || to == null) {
            return;
        }
        path = to.toAbsolutePath().normalize();
        title.setGraphic(FileIcons.forProjectItem(PathDisplay.fileName(path), false));
        updateRangeChrome();
        setMissing(false);
        if (!fromBuffer) {
            loadedStamp = null;
            loadFromDisk(generation.incrementAndGet(), path); // a load of the old path would be dropped
        }
    }

    CodeArea editor() {
        return editor;
    }

    void setMarkerActions(@Nullable MarkerActions actions) {
        markerActions = actions;
    }

    void setOnClose(Runnable callback) {
        onClose = callback == null ? this::hidePreview : callback;
    }

    void setOnActivate(Runnable callback) {
        onActivate = callback == null ? () -> {} : callback;
    }

    /** Called for use that should not raise the card (scrolling it), only count as recent. */
    void setOnTouch(Runnable callback) {
        onTouch = callback == null ? () -> {} : callback;
    }

    /** Called for Escape inside the card; the default just closes it. */
    void setOnEscape(Runnable callback) {
        onEscape = callback == null ? () -> onClose.run() : callback;
    }

    /** Called when the Notes button shows ({@code true}) or hides this card's Personal Notes. */
    void setOnNotesToggled(Consumer<Boolean> callback) {
        onNotesToggled = callback == null ? shown -> {} : callback;
    }

    /**
     * How many Personal Notes the store holds for what this card shows — its file, or a declaration's lines —
     * and whether notes are on at all; the Notes button counts them, and is gone while notes are off. A note
     * the user just asked for here brings the card's notes up when it arrives.
     */
    void setNoteCount(int count, boolean enabled) {
        int before = noteCount;
        noteCount = enabled ? Math.max(0, count) : 0;
        notes.setVisible(enabled);
        notes.setManaged(enabled);
        if (noteCount == 0 && notesOn) {
            setNotesShown(false);
        } else if (revealNotesOnAdd && noteCount > before) {
            revealNotesOnAdd = false;
            setNotesShown(true);
        }
        updateNotesButton();
    }

    int noteCount() {
        return noteCount;
    }

    boolean notesShown() {
        return notesOn;
    }

    /** Shows or hides the card's notes as the Notes button does. */
    void setNotesShown(boolean shown) {
        boolean wanted = shown && noteCount > 0;
        if (notesOn == wanted) {
            return;
        }
        notesOn = wanted;
        updateNotesButton();
        onNotesToggled.accept(wanted);
    }

    /**
     * The options menu's "Hide all open Personal Notes" is on: the card's notes stay stuck on but out of sight,
     * so the Notes button must not look pressed — it says why nothing shows, and a press turns the option off.
     */
    void setNotesSuppressed(boolean suppressed) {
        if (notesSuppressed != suppressed) {
            notesSuppressed = suppressed;
            updateNotesButton();
        }
    }

    /** Called when the Notes button is pressed while "Hide all open Personal Notes" hides the notes. */
    void setOnShowSuppressedNotes(Runnable callback) {
        onShowSuppressedNotes = callback == null ? () -> {} : callback;
    }

    private void updateNotesButton() {
        notes.setDisable(noteCount == 0);
        notes.setSelected(notesOn && !notesSuppressed);
        notes.setText(noteCount == 0 ? "" : Integer.toString(noteCount));
        String help = noteCount == 0
                ? tr("project.map.preview.noNotes")
                : notesSuppressed
                        ? tr("project.map.preview.notesHiddenByOption")
                        : notesOn
                                ? tr("project.map.preview.hideNotes")
                                : tr("project.map.preview.showNotes", noteCount);
        notes.getTooltip().setText(help);
        notes.setAccessibleText(help);
    }

    /**
     * Marks the text of {@code notes} — the ones stuck on the card — as an editor tab marks a note's: an amber wash
     * over it and a marker at its start, the sheet's colour. Empty clears the marks.
     */
    void markNotes(java.util.List<PersonalNote> notes) {
        marked = notes == null ? java.util.List.of() : java.util.List.copyOf(notes);
        noteMarks.refresh();
    }

    /**
     * Where, in the card's text, each marked note is: its selection for a word or range note, the whole line for
     * a line note (a first-line note from the Project tree has no columns). A declaration card's lines are the
     * file's from its first; a note past what is shown is left out, and one partly shown is cut to it.
     */
    java.util.List<int[]> noteSpans() {
        int lines = editor.getParagraphs().size();
        if (marked.isEmpty() || lines == 0 || frame.getCenter() != textPane) {
            return java.util.List.of();
        }
        int base = range == null ? 0 : range.startLine();
        java.util.List<int[]> spans = new java.util.ArrayList<>(marked.size());
        for (PersonalNote note : marked) {
            if (note.status() == com.editora.config.NoteStatus.ORPHANED) {
                continue; // its text is not in the file any more: nothing to mark
            }
            TextAnchor anchor = note.anchor();
            int first = anchor.line() - base;
            int last = Math.max(anchor.line(), anchor.endLine()) - base;
            if (last < 0 || first >= lines) {
                continue;
            }
            boolean wholeLines =
                    note.scope() == NoteScope.LINE || anchor.endColumn() <= anchor.column() && first == last;
            int startLine = Math.max(0, first);
            int endLine = Math.min(lines - 1, last);
            int startLength = editor.getParagraph(startLine).length();
            int endLength = editor.getParagraph(endLine).length();
            int startColumn = wholeLines || first < 0 ? 0 : Math.min(anchor.column(), startLength);
            int endColumn = wholeLines || last >= lines ? endLength : Math.min(anchor.endColumn(), endLength);
            int start = editor.getAbsolutePosition(startLine, startColumn);
            int end = editor.getAbsolutePosition(endLine, endColumn);
            spans.add(new int[] {start, Math.max(start, end)});
        }
        return spans;
    }

    /** The map's zoom the card is drawn at, to follow: its sticky notes are drawn at it too. */
    javafx.beans.property.DoubleProperty mapScaleProperty() {
        return mapZoom.xProperty();
    }

    /**
     * Where, in the parent's coordinates, a sticky note's connector meets the file's 0-based line {@code fileLine}:
     * the middle of that line when it is on screen, otherwise the top or bottom of the text it is scrolled past.
     * {@code NaN} when the card shows no text.
     */
    double noteAnchorY(int fileLine) {
        if (frame.getCenter() != textPane || editor.getParagraphs().isEmpty()) {
            return Double.NaN;
        }
        javafx.geometry.Bounds view = localToParent(frame.localToParent(textPane.getBoundsInParent()));
        double top = view.getMinY() + 4;
        double bottom = Math.max(top, view.getMaxY() - 4);
        Range shown = range;
        int line = Math.max(
                0, Math.min(editor.getParagraphs().size() - 1, fileLine - (shown == null ? 0 : shown.startLine())));
        int first;
        int last;
        try {
            first = editor.firstVisibleParToAllParIndex();
            last = editor.lastVisibleParToAllParIndex();
        } catch (RuntimeException notLaidOut) {
            return top;
        }
        if (line < first) {
            return top;
        }
        if (line > last) {
            return bottom;
        }
        javafx.geometry.Bounds screen = editor.getParagraphBoundsOnScreen(line).orElse(null);
        javafx.geometry.Bounds local = screen == null ? null : screenToLocal(screen);
        if (local != null) {
            return clamp(
                    localToParent(0, (local.getMinY() + local.getMaxY()) / 2).getY(), top, bottom);
        }
        // Not in a window (nothing has a screen position): the visible lines share the text's height evenly.
        return top + (bottom - top) * (line - first + 0.5) / Math.max(1, last - first + 1);
    }

    /**
     * Keeps the card, as drawn at the map's zoom, no larger than the panel. Where it stands is left alone: it
     * sits on the canvas, which may have it partly or wholly out of view.
     */
    void constrainTo(double parentWidth, double parentHeight) {
        if (!placed || parentWidth <= 0 || parentHeight <= 0) {
            return;
        }
        resize(
                boundedSize(preferredWidth, MIN_WIDTH, unzoomedRoom(parentWidth, mapScale())),
                boundedSize(preferredHeight, MIN_HEIGHT, unzoomedRoom(parentHeight, mapScale())));
    }

    /** The map's zoom the card is drawn at. A zoom only rescales the card; it never resizes it. */
    void setMapScale(double scale) {
        mapZoom.setX(scale);
        mapZoom.setY(scale);
    }

    double mapScale() {
        return mapZoom.getX();
    }

    /** The unzoomed size that, drawn at {@code scale}, fills a panel extent inside its margins. */
    static double unzoomedRoom(double extent, double scale) {
        return (extent - EDGE_MARGIN * 2) / scale;
    }

    void dispose() {
        disposed = true;
        generation.incrementAndGet();
        editorContextMenu.hide();
        selectionBar.setVisible(false);
        synchronized (tasks) {
            tasks.clear(); // a task already running finishes, and its result is dropped by the generation check
        }
        stopWaitingForSize();
        path = null;
        shownText = "";
        editor.replaceText("");
        status.setText("");
        setVisible(false);
    }

    /**
     * The context menu closed during the event now being delivered. An open popup is handed Escape before
     * this card's own key filter and hides itself, so the filter never finds the menu showing — and used to
     * close the card as well.
     */
    private boolean menuDismissedByThisKey;

    /** Asks for a note on {@code draft}'s text; once the store has it the card's notes come up to show it. */
    private void requestNote(MarkerActions actions, Path file, NoteDraft draft) {
        selectionBar.setVisible(false);
        revealNotesOnAdd = true;
        actions.addPersonalNote(file, draft);
    }

    private void installSelectionBar() {
        selectionBar.getStyleClass().addAll("markdown-format-bar", "project-map-selection-bar");
        selectionBar.setManaged(false); // placed over the selection by updateSelectionBar
        selectionBar.setFocusTraversable(false);
        selectionBar.setPadding(new javafx.geometry.Insets(3));
        selectionBar.setAlignment(Pos.CENTER_LEFT);
        selectionBar.setVisible(false);
        selectionNote.setText(tr("editmenu.addNote"));
        selectionNote.setGraphic(Icons.notes());
        selectionNote.getStyleClass().addAll("flat", "md-fmt-btn");
        selectionNote.setFocusTraversable(false); // a click must leave the selection's text focused
        selectionNote.setTooltip(new Tooltip(tr("project.map.preview.addNoteHelp")));
        selectionNote.setOnAction(event -> {
            Path selected = path;
            MarkerActions actions = markerActions;
            if (selected != null && actions != null && actions.personalNotesEnabled()) {
                requestNote(actions, selected, noteDraftAt(editor.getCurrentParagraph()));
            }
        });
        selectionBar.getChildren().setAll(selectionNote);

        MarkdownFormatBar.trackMouse(editor, this::scheduleSelectionBar);
        editor.selectionProperty().addListener((obs, old, now) -> scheduleSelectionBar());
        editor.estimatedScrollYProperty().addListener((obs, old, now) -> scheduleSelectionBar());
        editor.estimatedScrollXProperty().addListener((obs, old, now) -> scheduleSelectionBar());
        editor.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                selectionBar.setVisible(false);
            }
        });
    }

    /** Places the bar once per pulse, and only when it could come up or is up: these fire on every scroll step. */
    private void scheduleSelectionBar() {
        if (selectionBarPending || editor.getSelection().getLength() == 0 && !selectionBar.isVisible()) {
            return;
        }
        selectionBarPending = true;
        Platform.runLater(this::updateSelectionBar);
    }

    private void updateSelectionBar() {
        selectionBarPending = false;
        MarkerActions actions = markerActions;
        boolean wanted = !disposed
                && path != null
                && actions != null
                && actions.personalNotesEnabled()
                && !symbolMissing
                && editor.isFocused()
                && frame.getCenter() == textPane
                && MarkdownFormatBar.wanted(editor);
        javafx.geometry.Bounds caret = wanted ? editor.getCaretBounds().orElse(null) : null;
        javafx.geometry.Bounds local = caret == null ? null : screenToLocal(caret);
        if (local == null) {
            selectionBar.setVisible(false);
            return;
        }
        // The bar sits inside the text, next to the caret on the side the selection is not on (as a Markdown
        // buffer's does), in the card's own unzoomed units: it is drawn at the map's zoom with the card.
        javafx.geometry.Bounds text = frame.localToParent(textPane.getBoundsInParent());
        selectionBar.setVisible(true);
        selectionBar.applyCss();
        double width = Math.min(selectionBar.prefWidth(-1), Math.max(60, text.getWidth()));
        double height = selectionBar.prefHeight(width);
        var selection = editor.getSelection();
        boolean oneLine = editor.offsetToPosition(selection.getStart(), TwoDimensional.Bias.Forward)
                        .getMajor()
                == editor.offsetToPosition(selection.getEnd(), TwoDimensional.Bias.Backward)
                        .getMajor();
        boolean below = !oneLine && editor.getCaretPosition() >= selection.getEnd();
        double x = clamp(local.getMinX(), text.getMinX(), Math.max(text.getMinX(), text.getMaxX() - width));
        double y = text.getMinY()
                + MarkdownFormatBar.placeY(
                        local.getMinY() - text.getMinY(),
                        local.getMaxY() - text.getMinY(),
                        height,
                        text.getHeight(),
                        below);
        selectionBar.resizeRelocate(x, y, width, height);
        selectionBar.toFront();
    }

    private void installEditorContextMenu() {
        editorContextMenu.getStyleClass().add("editor-context-menu");
        editorContextMenu.showingProperty().addListener((obs, was, showing) -> {
            if (!showing) {
                menuDismissedByThisKey = true;
                Platform.runLater(() -> menuDismissedByThisKey = false); // only for the rest of this event
            }
        });
        editor.setOnContextMenuRequested(this::showEditorContextMenu);
    }

    /**
     * The card's menu. Opened from the keyboard (the Menu key, Shift+F10) it acts on the caret's line and opens at
     * the caret: JavaFX reports the middle of the focused text as the point of such a request, which used to put a
     * bookmark or a line note on whatever line happened to be in the middle of the card.
     */
    private void showEditorContextMenu(ContextMenuEvent event) {
        if (event.isKeyboardTrigger()) {
            rebuildEditorContextMenu(editor.getCurrentParagraph());
            javafx.geometry.Bounds caret = editor.getCaretBounds().orElse(null);
            if (caret != null) {
                editorContextMenu.show(editor, caret.getMinX(), caret.getMaxY());
            } else {
                editorContextMenu.show(editor, event.getScreenX(), event.getScreenY());
            }
        } else {
            rebuildEditorContextMenu(clickLineAt(event.getX(), event.getY()));
            editorContextMenu.show(editor, event.getScreenX(), event.getScreenY());
        }
        event.consume();
    }

    private void rebuildEditorContextMenu(int clickedLine) {
        boolean empty = editor.getLength() == 0;
        // With nothing selected Copy takes the whole text, and says so.
        boolean selection = editor.getSelection().getLength() > 0;
        MenuItem copy = new MenuItem(tr(selection ? "editmenu.copy" : "project.map.preview.copyAll"), Icons.copy());
        copy.setDisable(empty);
        copy.setOnAction(ignored -> copySelectionOrAll());
        MenuItem selectAll = new MenuItem(tr("editmenu.selectAll"), Icons.selectAll());
        selectAll.setDisable(empty);
        selectAll.setOnAction(ignored -> {
            editor.selectAll();
            editor.requestFocus();
        });

        java.util.List<MenuItem> items = new java.util.ArrayList<>(java.util.List.of(copy, selectAll));
        Path selected = path;
        Range shown = range;
        Runnable narrowed = onOpenNarrowed;
        if (selected != null && shown != null && narrowed != null) {
            MenuItem openNarrowed = new MenuItem(tr("project.map.preview.openNarrowed"));
            openNarrowed.setDisable(symbolMissing || missing);
            openNarrowed.setOnAction(ignored -> narrowed.run());
            items.add(0, openNarrowed);
            items.add(1, new SeparatorMenuItem());
        }
        MarkerActions actions = markerActions;
        if (!empty && selected != null && actions != null) {
            int fileLine = clickedLine + (shown == null ? 0 : shown.startLine());
            items.add(new SeparatorMenuItem());
            MenuItem bookmark = new MenuItem(tr("editmenu.addBookmark"), Icons.bookmark());
            bookmark.setOnAction(ignored -> actions.addBookmark(selected, fileLine));
            // A declaration that is gone keeps its last lines on the card, but they are no longer the file's.
            bookmark.setDisable(symbolMissing);
            items.add(bookmark);

            MenuItem note = new MenuItem(tr("editmenu.addNote"), Icons.notes());
            note.setDisable(!actions.personalNotesEnabled() || symbolMissing);
            NoteDraft draft = noteDraftAt(clickedLine);
            note.setOnAction(ignored -> requestNote(actions, selected, draft));
            items.add(note);
        }
        items.add(new SeparatorMenuItem());
        MenuItem freeSpace = new MenuItem(tr("project.map.preview.moveToFreeSpace"));
        freeSpace.setOnAction(ignored -> onMoveToFreeSpace.run());
        items.add(freeSpace);
        editorContextMenu.getItems().setAll(items);
    }

    /** The card menu's Move to Free Space: the owner places the card again where it covers least. */
    void setOnMoveToFreeSpace(Runnable callback) {
        onMoveToFreeSpace = callback == null ? () -> {} : callback;
    }

    private void copySelectionOrAll() {
        String text = editor.getSelectedText();
        if (text == null || text.isEmpty()) {
            text = editor.getText();
        }
        if (text.isEmpty()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private int clickLineAt(double x, double y) {
        try {
            int offset = editor.hit(x, y).getInsertionIndex();
            return editor.offsetToPosition(offset, TwoDimensional.Bias.Forward).getMajor();
        } catch (RuntimeException ignored) {
            return editor.getCurrentParagraph();
        }
    }

    /**
     * The note a click or selection asks for, anchored in the file: a declaration card shows some of its lines,
     * so its positions are moved to the file's and the anchor's context is read from the whole text.
     */
    private NoteDraft noteDraftAt(int clickedLine) {
        boolean ranged = range != null && fullText.length() >= sliceOffset + editor.getLength();
        String document = ranged ? fullText : editor.getText();
        int base = ranged ? sliceOffset : 0;
        int lineBase = ranged && range != null ? range.startLine() : 0;
        IndexRange selection = editor.getSelection();
        if (selection.getLength() > 0) {
            int start = selection.getStart();
            int end = selection.getEnd();
            var startPosition = editor.offsetToPosition(start, TwoDimensional.Bias.Forward);
            var endPosition = editor.offsetToPosition(end, TwoDimensional.Bias.Forward);
            NoteScope scope = startPosition.getMajor() == endPosition.getMajor() ? NoteScope.WORD : NoteScope.RANGE;
            String prefix = document.substring(Math.max(0, base + start - TextAnchor.MAX_CONTEXT), base + start);
            String suffix =
                    document.substring(base + end, Math.min(document.length(), base + end + TextAnchor.MAX_CONTEXT));
            TextAnchor anchor = new TextAnchor(
                    lineBase + startPosition.getMajor(),
                    startPosition.getMinor(),
                    lineBase + endPosition.getMajor(),
                    endPosition.getMinor(),
                    editor.getSelectedText(),
                    prefix,
                    suffix);
            return new NoteDraft(scope, anchor);
        }

        int line = Math.max(0, Math.min(clickedLine, editor.getParagraphs().size() - 1));
        String lineText = editor.getParagraph(line).getText();
        int lineStart = base + editor.getAbsolutePosition(line, 0);
        int lineEnd = lineStart + lineText.length();
        String prefix = document.substring(Math.max(0, lineStart - TextAnchor.MAX_CONTEXT), lineStart);
        String suffix = document.substring(lineEnd, Math.min(document.length(), lineEnd + TextAnchor.MAX_CONTEXT));
        TextAnchor anchor =
                new TextAnchor(lineBase + line, 0, lineBase + line, lineText.length(), lineText, prefix, suffix);
        return new NoteDraft(NoteScope.LINE, anchor);
    }

    @Override
    protected void layoutChildren() {
        boolean compact = getWidth() < COMPACT_WIDTH;
        for (javafx.scene.Node node : java.util.List.of(zoomOut, zoomIn)) {
            node.setVisible(!compact);
            node.setManaged(!compact);
        }
        frame.resizeRelocate(0, 0, getWidth(), getHeight());
        double grip = 18;
        resizeGrip.resizeRelocate(Math.max(0, getWidth() - grip), Math.max(0, getHeight() - grip), grip, grip);
    }

    /** Retries {@link #ensurePlaced} once the card has a parent with a size; null while not waiting. */
    private javafx.beans.@Nullable InvalidationListener sizeWait;

    private @Nullable Region sizeWaitParent;

    private void ensurePlaced() {
        if (disposed) {
            return;
        }
        if (!(getParent() instanceof Region parent) || parent.getWidth() <= 0 || parent.getHeight() <= 0) {
            waitForSize(); // not a runLater loop: that spun the FX thread until layout gave the host a size
            return;
        }
        stopWaitingForSize();
        if (placed) {
            return;
        }
        double scale = mapScale();
        double width = boundedSize(preferredWidth, MIN_WIDTH, unzoomedRoom(parent.getWidth(), scale));
        double height = boundedSize(preferredHeight, MIN_HEIGHT, unzoomedRoom(parent.getHeight(), scale));
        placed = true;
        placementPending = false;
        if (placementResolver != null) {
            Placement placement = placementResolver.resolve(width, height, parent.getWidth(), parent.getHeight());
            preferredWidth = boundedSize(placement.width(), MIN_WIDTH, unzoomedRoom(parent.getWidth(), scale));
            preferredHeight = boundedSize(placement.height(), MIN_HEIGHT, unzoomedRoom(parent.getHeight(), scale));
            growLeft = placement.growLeft();
            resize(preferredWidth, preferredHeight);
            relocate(placement.x(), placement.y());
        } else {
            resize(width, height);
            relocate(Math.max(EDGE_MARGIN, parent.getWidth() - width * scale - 24), 24);
        }
        constrainTo(parent.getWidth(), parent.getHeight());
        // A new card opens in view; only the user (or a pan) takes it elsewhere.
        relocate(
                clamp(
                        getLayoutX(),
                        EDGE_MARGIN,
                        Math.max(EDGE_MARGIN, parent.getWidth() - getWidth() * scale - EDGE_MARGIN)),
                clamp(
                        getLayoutY(),
                        EDGE_MARGIN,
                        Math.max(EDGE_MARGIN, parent.getHeight() - getHeight() * scale - EDGE_MARGIN)));
    }

    /** Listens once for a parent, and for the parent's size, then places the card. */
    private void waitForSize() {
        if (sizeWait == null) {
            sizeWait = observable -> {
                if (!placementPending) {
                    stopWaitingForSize();
                } else if (getParent() != sizeWaitParent) {
                    stopWaitingForSize();
                    ensurePlaced(); // re-attaches to the new parent, or places the card
                } else if (getParent() instanceof Region parent && parent.getWidth() > 0 && parent.getHeight() > 0) {
                    ensurePlaced();
                }
            };
            parentProperty().addListener(sizeWait);
        }
        Region parent = getParent() instanceof Region region ? region : null;
        if (parent != sizeWaitParent) {
            if (sizeWaitParent != null) {
                sizeWaitParent.widthProperty().removeListener(sizeWait);
                sizeWaitParent.heightProperty().removeListener(sizeWait);
            }
            sizeWaitParent = parent;
            if (parent != null) {
                parent.widthProperty().addListener(sizeWait);
                parent.heightProperty().addListener(sizeWait);
            }
        }
    }

    private void stopWaitingForSize() {
        javafx.beans.InvalidationListener listener = sizeWait;
        if (listener == null) {
            return;
        }
        sizeWait = null;
        parentProperty().removeListener(listener);
        if (sizeWaitParent != null) {
            sizeWaitParent.widthProperty().removeListener(listener);
            sizeWaitParent.heightProperty().removeListener(listener);
            sizeWaitParent = null;
        }
    }

    /** Whether the card is waiting for its host to be laid out before it is placed. For tests. */
    boolean waitingForSize() {
        return sizeWait != null;
    }

    private void dragPressed(MouseEvent event) {
        if (event.getTarget() instanceof Button || event.getButton() != javafx.scene.input.MouseButton.PRIMARY) {
            return;
        }
        dragScreenX = event.getScreenX();
        dragScreenY = event.getScreenY();
        dragLayoutX = getLayoutX();
        dragLayoutY = getLayoutY();
        userMoved = true;
        titleBar.setCursor(Cursor.MOVE);
        event.consume();
    }

    /** Follows the pointer anywhere, as a column header drag does: panning brings the card back into view. */
    private void dragged(MouseEvent event) {
        if (!event.isPrimaryButtonDown()) {
            return;
        }
        relocate(dragLayoutX + event.getScreenX() - dragScreenX, dragLayoutY + event.getScreenY() - dragScreenY);
        event.consume();
    }

    private void resizePressed(MouseEvent event) {
        if (event.getButton() != javafx.scene.input.MouseButton.PRIMARY) {
            return;
        }
        resizeScreenX = event.getScreenX();
        resizeScreenY = event.getScreenY();
        resizeWidth = getWidth();
        resizeHeight = getHeight();
        userSized = true;
        event.consume();
    }

    private void resized(MouseEvent event) {
        if (!(getParent() instanceof Region parent) || !event.isPrimaryButtonDown()) {
            return;
        }
        // As large as the panel, wherever the card stands: one partly out of view must not shrink to fit. The
        // pointer moves in zoomed pixels, the card's size is unzoomed: the grip stays under the pointer.
        double scale = mapScale();
        double maxWidth = Math.max(1, unzoomedRoom(parent.getWidth(), scale));
        double maxHeight = Math.max(1, unzoomedRoom(parent.getHeight(), scale));
        preferredWidth = boundedSize(resizeWidth + (event.getScreenX() - resizeScreenX) / scale, MIN_WIDTH, maxWidth);
        preferredHeight =
                boundedSize(resizeHeight + (event.getScreenY() - resizeScreenY) / scale, MIN_HEIGHT, maxHeight);
        resize(preferredWidth, preferredHeight);
        requestLayout();
        event.consume();
    }

    private static Loaded load(Path requestedPath, @Nullable String editorConfigCharset) {
        Stamp stamp = stamp(requestedPath);
        try (InputStream in = Files.newInputStream(requestedPath)) {
            boolean imageFile = isImage(requestedPath);
            int limit = imageFile ? MAX_IMAGE_BYTES : MAX_PREVIEW_BYTES;
            byte[] raw = in.readNBytes(limit + 1);
            boolean byteTruncated = raw.length > limit;
            byte[] bytes = byteTruncated ? Arrays.copyOf(raw, limit) : raw;
            if (imageFile && !byteTruncated) {
                // Decoded here, off the FX thread: a 20 MB bitmap takes long enough to stall a frame.
                Image image = new Image(new ByteArrayInputStream(bytes));
                return new Loaded("", image, false, image.isError() ? LoadProblem.FAILED : LoadProblem.NONE, stamp);
            }
            if (imageFile) {
                return new Loaded(fileType(requestedPath), null, false, LoadProblem.IMAGE_TOO_LARGE, stamp);
            }
            if (looksBinary(bytes)) {
                // A binary file's card lists its facts; the text field carries its type.
                return new Loaded(fileType(requestedPath), null, false, LoadProblem.BINARY, stamp);
            }
            String decoded = decode(bytes, byteTruncated, editorConfigCharset);
            String text = capText(decoded, false);
            boolean truncated = byteTruncated || text.length() < decoded.length();
            return new Loaded(text, null, truncated, LoadProblem.NONE, stamp);
        } catch (NoSuchFileException gone) {
            return new Loaded("", null, false, LoadProblem.MISSING, null);
        } catch (IOException | RuntimeException error) {
            return new Loaded("", null, false, LoadProblem.FAILED, stamp);
        }
    }

    /**
     * {@code file}'s text read and decoded the way a card reads it (the read cap, BOM, {@code .editorconfig}
     * charset, {@code \n} line endings), for the Map's structure columns; null when it is not text a card would
     * show — missing, unreadable, binary or an image. Off the FX thread.
     */
    static @Nullable String readText(Path file, @Nullable String editorConfigCharset) {
        ReadText read = readTextCut(file, editorConfigCharset);
        return read == null ? null : read.text();
    }

    /** A file's text as {@link #readText} reads it, and whether the read cap cut it short. */
    record ReadText(String text, boolean truncated) {}

    /** As {@link #readText}, saying whether the text stops at the read cap. Off the FX thread. */
    static @Nullable ReadText readTextCut(Path file, @Nullable String editorConfigCharset) {
        if (isImage(file)) {
            return null;
        }
        Loaded loaded = load(file, editorConfigCharset);
        return loaded.problem() == LoadProblem.NONE && loaded.image() == null
                ? new ReadText(loaded.text(), loaded.truncated())
                : null;
    }

    /** Off the FX thread: a file's media type as the platform knows it, else its extension; "" for neither. */
    private static String fileType(Path file) {
        try {
            String probed = Files.probeContentType(file);
            if (probed != null && !probed.isBlank()) {
                return probed;
            }
        } catch (IOException | RuntimeException unknown) {
            // fall back to the name
        }
        String name = PathDisplay.fileName(file);
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1).toUpperCase(java.util.Locale.ROOT) : "";
    }

    private static @Nullable Stamp stamp(@Nullable Path file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            return new Stamp(attributes.size(), attributes.lastModifiedTime().toMillis());
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * Decodes file bytes the way the editor opens a file — a BOM, then the {@code .editorconfig} charset,
     * then UTF-8, falling back to a single-byte charset instead of U+FFFD when the bytes are not valid in it
     * — and in the editor's line-ending form (bare {@code \n}), which the highlighter's offsets assume.
     * {@code cut} says the bytes stop at the read cap: a character the cap split is dropped first, or the
     * strict decode would reject the whole file for its last byte.
     */
    static String decode(byte[] bytes, boolean cut, @Nullable String editorConfigCharset) {
        byte[] source = cut ? trimIncompleteTail(bytes, editorConfigCharset) : bytes;
        return LineEndings.toLf(
                EditorConfigCharset.decodeLossless(source, editorConfigCharset).text());
    }

    /** {@code bytes} without a trailing UTF-8 sequence or UTF-16 unit / surrogate pair cut short. */
    static byte[] trimIncompleteTail(byte[] bytes, @Nullable String editorConfigCharset) {
        String declared = EditorConfigCharset.resolveName(bytes, editorConfigCharset);
        int end = bytes.length;
        if (EditorConfigCharset.UTF_8.equals(declared) || EditorConfigCharset.UTF_8_BOM.equals(declared)) {
            int lead = end - 1;
            while (lead >= 0 && end - lead < 4 && (bytes[lead] & 0xC0) == 0x80) {
                lead--;
            }
            if (lead >= 0) {
                int first = bytes[lead] & 0xFF;
                int needed = first >= 0xF0 ? 4 : first >= 0xE0 ? 3 : first >= 0xC0 ? 2 : 1;
                if (needed > 1 && end - lead < needed) {
                    end = lead;
                }
            }
        } else if (EditorConfigCharset.UTF_16LE.equals(declared) || EditorConfigCharset.UTF_16BE.equals(declared)) {
            end -= end % 2;
            if (end >= 2) {
                int high = EditorConfigCharset.UTF_16LE.equals(declared) ? bytes[end - 1] : bytes[end - 2];
                if ((high & 0xFC) == 0xD8) {
                    end -= 2; // a high surrogate whose low half is past the cap
                }
            }
        }
        return end == bytes.length ? bytes : Arrays.copyOf(bytes, end);
    }

    /**
     * Colours {@code text} off the FX thread. A declaration card colours its whole file and shows the spans of
     * its lines, so a line inside a comment or a string starts in the right state; when the whole file is past
     * the highlighting cap its lines alone are coloured, which is right for nearly every declaration.
     */
    private void highlight(long requested, Path requestedPath, String text, boolean truncated) {
        boolean whole = text.length() <= MAX_HIGHLIGHT_CHARS;
        // Past the cap a whole-file card colours its first lines (up to the last line break under the cap) and
        // says so; it used to show the whole file uncoloured with nothing to tell why.
        boolean prefix = !whole && range == null;
        String source;
        if (whole) {
            source = text;
        } else if (range != null) {
            source = shownText;
        } else {
            int cut = text.lastIndexOf('\n', MAX_HIGHLIGHT_CHARS);
            source = cut > 0 ? text.substring(0, cut) : "";
        }
        if (source.isEmpty() || source.length() > MAX_HIGHLIGHT_CHARS) {
            return;
        }
        submitLatest(() -> {
            StyleSpans<Collection<String>> spans = styles(requestedPath, source);
            Platform.runLater(() -> showStyles(requested, requestedPath, text, whole, prefix, truncated, spans));
        });
    }

    /** Keeps only the latest pending preview/highlight so rapid keyboard navigation cannot build a backlog. */
    private void submitLatest(Runnable task) {
        synchronized (tasks) {
            tasks.clear();
        }
        statPending = false; // a queued stat went with the queue
        runInBackground(task);
    }

    /** Queues {@code task} after this card's earlier ones, on the shared loader. */
    private void runInBackground(Runnable task) {
        synchronized (tasks) {
            tasks.add(task);
            if (draining) {
                return;
            }
            draining = true;
        }
        try {
            SHARED_LOADER.execute(this::drainTasks);
        } catch (java.util.concurrent.RejectedExecutionException never) {
            synchronized (tasks) {
                draining = false;
            }
        }
    }

    /** Off the FX thread: runs this card's queued tasks in order until there are none. */
    private void drainTasks() {
        while (true) {
            Runnable next;
            synchronized (tasks) {
                next = tasks.poll();
                if (next == null) {
                    draining = false;
                    return;
                }
            }
            try {
                next.run();
            } catch (RuntimeException | LinkageError failed) {
                // one card's bad read must not stop the next task (each reports its own result)
                ProjectMapLog.unexpected("a card's background read", failed);
            }
        }
    }

    private static @Nullable StyleSpans<Collection<String>> styles(Path requestedPath, String text) {
        if (text.isEmpty() || text.length() > MAX_HIGHLIGHT_CHARS) {
            return null;
        }
        try {
            IGrammar grammar = GrammarRegistry.shared().forFileName(requestedPath.toString());
            return grammar == null ? null : TextMateHighlighter.compute(text, grammar); // no grammar: no colours
        } catch (RuntimeException | LinkageError failed) {
            ProjectMapLog.unexpected("highlighting a card", failed);
            return null;
        }
    }

    private boolean showLoaded(long requested, Path requestedPath, Loaded loaded) {
        if (disposed || requested != generation.get() || !requestedPath.equals(path)) {
            return false;
        }
        loadedStamp = loaded.stamp();
        if (loaded.problem() == LoadProblem.MISSING) {
            status.setText("");
            if (shownText.isEmpty() && imageView.getImage() == null) {
                frame.setCenter(textPane);
                editor.setPlaceholder(new Label(tr("project.map.preview.missing")));
            }
            setMissing(true);
            return false;
        }
        setMissing(false);
        if (loaded.problem() == LoadProblem.BINARY || loaded.problem() == LoadProblem.IMAGE_TOO_LARGE) {
            showFileInfo(requestedPath, loaded);
            return false;
        }
        if (loaded.problem() != LoadProblem.NONE) {
            frame.setCenter(textPane);
            imageView.setImage(null);
            shownText = "";
            editor.replaceText("");
            status.setText("");
            editor.setPlaceholder(new Label(tr("project.map.preview.failed")));
            return false;
        }
        if (loaded.image() != null) {
            Image image = loaded.image();
            imageView.setImage(image);
            frame.setCenter(imageScroll);
            status.setText(
                    image.getWidth() > 0 ? Math.round(image.getWidth()) + " × " + Math.round(image.getHeight()) : "");
            setContentZoom(contentZoom);
            return false;
        }
        boolean first = shownText.isEmpty() && frame.getCenter() == textPane && editor.getLength() == 0;
        if (!showText(loaded.text(), loaded.truncated())) {
            return false; // held until the outline says where the declaration went
        }
        if (first) {
            editor.moveTo(0);
            editor.scrollToPixel(0, 0);
            fitTextWidth(shownText);
        }
        return true;
    }

    /**
     * What a card shows for a file it cannot show as text or as an image: a compact card with the file's size,
     * type and time, and a way to it in the file manager — no gutter, no zoom, and no Open, which would load the
     * bytes into an editor tab.
     */
    private void showFileInfo(Path file, Loaded loaded) {
        imageView.setImage(null);
        shownText = "";
        editor.replaceText("");
        status.setText("");
        open.setDisable(true);
        Stamp stamp = loaded.stamp();
        long size = stamp == null ? -1 : stamp.size();
        Label heading = new Label(
                loaded.problem() == LoadProblem.IMAGE_TOO_LARGE
                        ? tr("project.map.preview.imageTooLarge", StatusBar.formatSize(Math.max(0, size)))
                        : tr("project.map.preview.binaryTitle"));
        heading.getStyleClass().add("project-map-preview-info-heading");
        heading.setWrapText(true);
        javafx.scene.layout.VBox info = new javafx.scene.layout.VBox(4, heading);
        if (size >= 0) {
            info.getChildren().add(new Label(tr("project.map.preview.infoSize", StatusBar.formatSize(size))));
        }
        String type = loaded.text().isBlank() ? tr("project.map.preview.infoTypeUnknown") : loaded.text();
        info.getChildren().add(new Label(tr("project.map.preview.infoType", type)));
        if (stamp != null && stamp.modifiedMillis() > 0) {
            String when = java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM)
                    .withLocale(java.util.Locale.getDefault())
                    .format(java.time.Instant.ofEpochMilli(stamp.modifiedMillis())
                            .atZone(java.time.ZoneId.systemDefault()));
            info.getChildren().add(new Label(tr("project.map.preview.infoModified", when)));
        }
        Button reveal = new Button(tr("project.menu.revealInFileManager"), Icons.revealInFiles());
        reveal.getStyleClass().add("project-map-preview-button");
        reveal.setOnAction(event -> onReveal.accept(file));
        info.getChildren().add(reveal);
        info.getStyleClass().add("project-map-preview-info");
        info.setPadding(new javafx.geometry.Insets(12));
        frame.setCenter(info);
        if (!userSized) {
            // Compact: the facts need no more than the card's minimum.
            double scale = mapScale();
            double right = getLayoutX() + getWidth() * scale;
            preferredWidth = MIN_WIDTH;
            preferredHeight = MIN_HEIGHT;
            if (placed) {
                resize(MIN_WIDTH, MIN_HEIGHT);
                if (growLeft && !userMoved) {
                    relocate(right - MIN_WIDTH * scale, getLayoutY());
                }
            }
        }
    }

    /** Where the card's Reveal in File Manager goes (a binary file's card). */
    void setOnReveal(Consumer<Path> callback) {
        onReveal = callback == null ? ignored -> {} : callback;
    }

    /** Whether the card shows a file's facts instead of its content (a binary file, an image over the cap). */
    boolean showsFileInfo() {
        return frame.getCenter() != textPane && frame.getCenter() != imageScroll;
    }

    /**
     * Puts {@code full} in the editor — a declaration card's lines of it — by replacing only the range that
     * differs from what is shown, so a refresh keeps the scroll position, the selection outside the edit and
     * the styles of unchanged lines.
     */
    private boolean showText(String full, boolean truncated) {
        Range shown = range;
        if (shown != null && !shownText.isEmpty() && !fullText.isEmpty() && !full.equals(fullText)) {
            Range moved = followDeclaration(full, shown);
            if (moved == null) {
                heldText = full;
                heldTruncated = truncated;
                return false;
            }
            if (!moved.equals(shown)) {
                range = moved; // lines were added or removed above it: the same lines, further down or up
                updateRangeChrome();
            }
        }
        heldText = null;
        applyText(full, truncated);
        return true;
    }

    /**
     * {@code shown} in {@code full}, a new version of the card's file: unchanged when its declaration line is
     * still where it was, moved by as many lines as that line moved (the nearest copy of it), or null when the
     * line is gone — then only the outline can tell where the declaration went. Pure but for {@link
     * #declarationText}.
     */
    private @Nullable Range followDeclaration(String full, Range shown) {
        String wanted = declarationText;
        if (wanted.isBlank()) {
            return shown;
        }
        String[] lines = full.split("\n", -1);
        int at = shown.line();
        if (at >= 0 && at < lines.length && lines[at].equals(wanted)) {
            return shown;
        }
        for (int distance = 1; distance < lines.length; distance++) {
            for (int candidate : new int[] {at - distance, at + distance}) {
                if (candidate >= 0 && candidate < lines.length && lines[candidate].equals(wanted)) {
                    int shift = candidate - at;
                    return new Range(
                            shown.symbol(), shown.line() + shift, shown.startLine() + shift, shown.endLine() + shift);
                }
            }
            if (at - distance < 0 && at + distance >= lines.length) {
                break;
            }
        }
        return null;
    }

    /** Puts {@code full} in the editor (see {@link #showText}), whatever lines it has. */
    private void applyText(String full, boolean truncated) {
        frame.setCenter(textPane);
        imageView.setImage(null);
        editor.setPlaceholder(null);
        boolean changed = !full.equals(fullText);
        String text = visible(full);
        truncatedShown = truncated;
        String old = shownText;
        if (!text.equals(old)) {
            int limit = Math.min(old.length(), text.length());
            int prefix = 0;
            while (prefix < limit && old.charAt(prefix) == text.charAt(prefix)) {
                prefix++;
            }
            int suffix = 0;
            while (suffix < limit - prefix
                    && old.charAt(old.length() - 1 - suffix) == text.charAt(text.length() - 1 - suffix)) {
                suffix++;
            }
            int oldEnd = old.length() - suffix;
            int shift = text.length() - old.length();
            int anchor = movedOffset(editor.getAnchor(), prefix, oldEnd, shift);
            int caret = movedOffset(editor.getCaretPosition(), prefix, oldEnd, shift);
            editor.replaceText(prefix, oldEnd, text.substring(prefix, text.length() - suffix));
            editor.selectRange(anchor, caret); // replaceText leaves the caret after the replaced range
            shownText = text;
            colouredLines = 0; // until the new text is coloured
            changed = true;
        }
        Range shown = range;
        declarationText = shown == null ? "" : lineOf(fullText, shown.line());
        status.setText(idleStatus(truncated));
        if (changed) {
            onTextShown.run();
        }
    }

    /** The text the card cuts its lines from: the whole file as last shown (see {@link #applyText}). */
    String fullText() {
        return fullText;
    }

    /** Called after the card's text changed: the owner finds its stored notes again in the new text. */
    void setOnTextShown(Runnable callback) {
        onTextShown = callback == null ? () -> {} : callback;
    }

    /** The 0-based {@code line} of {@code text}, or "" past its end. */
    private static String lineOf(String text, int line) {
        if (line < 0) {
            return "";
        }
        int start = com.editora.editor.fold.NarrowLines.offsetOfLine(text, line);
        if (start >= text.length()) {
            return ""; // past the last line (or the empty last line)
        }
        int end = text.indexOf('\n', start);
        return text.substring(start, end < 0 ? text.length() : end);
    }

    /** Where an offset lands after {@code prefix..oldEnd} was replaced by text {@code shift} chars longer. */
    private static int movedOffset(int offset, int prefix, int oldEnd, int shift) {
        return offset <= prefix ? offset : offset >= oldEnd ? offset + shift : prefix;
    }

    /**
     * A declaration card's height: all of its lines when they fit the default height, which most methods do —
     * a five-line method in a card sized for a whole file was mostly empty.
     */
    private double fittedHeight(Range shown) {
        Text glyph = new Text("M");
        glyph.setFont(textFont());
        double lineHeight = Math.max(1, glyph.getLayoutBounds().getHeight());
        int lines = Math.max(1, shown.endLine() - shown.startLine() + 1);
        return Math.max(MIN_HEIGHT, Math.min(DEFAULT_HEIGHT, lines * lineHeight + TEXT_CHROME_HEIGHT));
    }

    /** The width that shows the text's longest line, up to {@value #MAX_FIT_COLUMNS} columns. */
    private double fittedWidth(String text) {
        int columns = Math.min(MAX_FIT_COLUMNS, widestLineColumns(text));
        Text glyph = new Text("M");
        glyph.setFont(textFont());
        double glyphWidth = Math.max(1, glyph.getLayoutBounds().getWidth());
        return Math.max(DEFAULT_WIDTH, columns * glyphWidth + TEXT_CHROME_WIDTH);
    }

    /**
     * Widens the card for text that arrived after it was placed. The card grows where it stands — away from
     * its column, never past the panel — and is neither placed again nor moved: by now the user may have
     * dragged it or panned the map. A card the user sized keeps that size.
     */
    private void fitTextWidth(String text) {
        double wanted = fittedWidth(text);
        if (!placed) {
            preferredWidth = wanted; // still waiting for the panel to have a size: placed at this width
            return;
        }
        if (userSized || wanted <= getWidth() || !(getParent() instanceof Region parent)) {
            return;
        }
        if (growLeft && !userMoved) {
            double scale = mapScale();
            double right = getLayoutX() + getWidth() * scale;
            preferredWidth = Math.max(getWidth(), (right - Math.max(EDGE_MARGIN, right - wanted * scale)) / scale);
            resize(preferredWidth, getHeight());
            relocate(right - preferredWidth * scale, getLayoutY());
        } else {
            double room = (parent.getWidth() - EDGE_MARGIN - getLayoutX()) / mapScale();
            preferredWidth = Math.max(getWidth(), Math.min(wanted, room));
            resize(preferredWidth, getHeight());
        }
        requestLayout();
    }

    private static int widestLineColumns(String text) {
        int widest = 0;
        int current = 0;
        for (int offset = 0; offset < text.length() && widest < MAX_FIT_COLUMNS; ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '\n' || codePoint == '\r') {
                widest = Math.max(widest, current);
                current = 0;
            } else if (codePoint == '\t') {
                current += TAB_COLUMNS - current % TAB_COLUMNS;
            } else {
                current += codePoint >= 0x1100 ? 2 : 1;
            }
            current = Math.min(current, MAX_FIT_COLUMNS);
        }
        return Math.max(widest, current);
    }

    private void showStyles(
            long requested,
            Path requestedPath,
            String text,
            boolean whole,
            boolean prefix,
            boolean truncated,
            @Nullable StyleSpans<Collection<String>> spans) {
        if (disposed || requested != generation.get() || !requestedPath.equals(path) || spans == null) {
            return;
        }
        if (!fullText.equals(text) || editor.getLength() != shownText.length()) {
            return;
        }
        if (prefix) {
            if (spans.length() > shownText.length()) {
                return;
            }
            editor.setStyleSpans(0, spans);
            colouredLines = editor.offsetToPosition(spans.length(), TwoDimensional.Bias.Backward)
                            .getMajor()
                    + 1;
        } else if (range == null || !whole) {
            if (spans.length() != shownText.length()) {
                return;
            }
            editor.setStyleSpans(0, spans);
        } else {
            int end = sliceOffset + shownText.length();
            if (end > spans.length()) {
                return;
            }
            editor.setStyleSpans(0, spans.subView(sliceOffset, end));
        }
        status.setText(idleStatus(truncated));
    }

    private static boolean looksBinary(byte[] bytes) {
        if (EditorConfigCharset.detectByBom(bytes) != null) {
            return false;
        }
        int inspected = Math.min(bytes.length, 8192);
        for (int i = 0; i < inspected; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isImage(Path path) {
        String name = PathDisplay.fileName(path).toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".png")
                || name.endsWith(".jpg")
                || name.endsWith(".jpeg")
                || name.endsWith(".gif")
                || name.endsWith(".bmp");
    }

    /**
     * The editor window's property that holds its editor font for the Project Map's cards: its family and size at
     * the text zoom, put there as a Diff tab or a console gets them ({@code
     * EditorSettingsCoordinator.applyFontsAndPerBufferView}) and read by the window's Map ({@code
     * ProjectMapView.setEditorFontSource}).
     */
    static final String EDITOR_FONT_KEY = "editora.projectMapCardFont";

    /** Publishes {@code window}'s editor font for its Map's cards (see {@link #EDITOR_FONT_KEY}). FX thread. */
    static void publishEditorFont(javafx.stage.@Nullable Window window, @Nullable String family, double size) {
        if (window == null) {
            return;
        }
        Font font = Font.font(family == null || family.isBlank() ? "Monospaced" : family, size > 0 ? size : 12);
        if (!font.equals(window.getProperties().get(EDITOR_FONT_KEY))) {
            window.getProperties().put(EDITOR_FONT_KEY, font);
        }
    }

    /** The editor's font, at text zoom 1. */
    private Font editorFont = Font.font("Monospaced", 12);

    /** The editor's font family and size changed (or were first known); null keeps the default. */
    void setEditorFont(@Nullable Font font) {
        if (font != null && !font.equals(editorFont)) {
            editorFont = font;
            setContentZoom(contentZoom);
        }
    }

    /** The card's text font: the editor's family at its size times the card's text zoom. */
    private Font textFont() {
        return Font.font(editorFont.getFamily(), editorFont.getSize() * contentZoom);
    }

    private void setContentZoom(double requested) {
        contentZoom = clamp(requested, 0.5, 3.0);
        Font font = textFont();
        editor.setStyle("-fx-font-family: '" + font.getFamily().replace("'", "\\'") + "'; -fx-font-size: "
                + font.getSize() + "px;");
        Image image = imageView.getImage();
        if (image != null) {
            imageView.setFitWidth(image.getWidth() * contentZoom);
        }
    }

    /**
     * {@code text} within the display cap. A cut — made here, or by the caller when {@code alreadyCut} —
     * never ends on the first half of a surrogate pair, which would render as a stray replacement glyph.
     */
    static String capText(String text, boolean alreadyCut) {
        int end = Math.min(text.length(), MAX_PREVIEW_CHARS);
        if ((end < text.length() || alreadyCut) && end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return end == text.length() ? text : text.substring(0, end);
    }

    /** {@code requested} held between the card's minimum and the room there is; the room wins when smaller. */
    static double boundedSize(double requested, double minimum, double available) {
        double maximum = Math.max(1, available);
        return Math.min(Math.max(Math.min(minimum, maximum), requested), maximum);
    }

    static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
