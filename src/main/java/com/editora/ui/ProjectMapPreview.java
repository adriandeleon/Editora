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
import com.editora.editor.ControlNames;
import com.editora.editor.MenuChords;
import com.editora.editor.UiZoom;
import com.editora.editor.highlight.GrammarRegistry;
import com.editora.editor.highlight.TextMateHighlighter;
import com.editora.editor.lsp.LspDiagnosticOverlay;
import com.editora.editor.markdown.MarkdownFormatBar;
import com.editora.editor.notes.NoteDraft;
import com.editora.editor.notes.NoteHighlightOverlay;
import com.editora.editorconfig.EditorConfigCharset;
import com.editora.text.LspDiagnostic;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.event.MouseOverTextEvent;
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
    /**
     * What an outline reads of a file, and a declaration card its file: far more than a whole-file card shows, so
     * the declarations past the card's cap (k8s's {@code core/v1/types.go}: Node, Namespace, Secret…) are listed and
     * open (Project Map review 3, C2). Plain text only; read off the FX thread.
     */
    static final int MAX_OUTLINE_CHARS = 4_000_000;

    private static final int MAX_OUTLINE_BYTES = 4_000_000;
    /** Past this size a card does not stream the rest of a cut file to count its lines. */
    private static final long MAX_LINE_COUNT_BYTES = 64_000_000;

    private static final int MAX_PREVIEW_BYTES = 1_000_000;
    private static final int MAX_IMAGE_BYTES = 20_000_000;
    private static final int MAX_HIGHLIGHT_CHARS = 160_000;
    static final double DEFAULT_WIDTH = 640;
    static final double DEFAULT_HEIGHT = 420;
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

    /**
     * Following the code from a card (Project Map review 3 C12): Go to Definition, Find Usages and the rest open
     * the card's file in the editor at a place of the card and run there; Related Test / Subject is revealed on the
     * Map; File History is the file's Git log.
     */
    interface FollowActions {
        /** Opens {@code file} at the 0-based {@code line} and {@code column} and runs {@code commandId} there. */
        void follow(Path file, int line, int column, String commandId);

        /** Reveals {@code file}'s related test or subject on the Map. */
        void related(Path file);

        /** Shows {@code file}'s Git history. */
        void fileHistory(Path file);

        /** Opens {@code file} in the editor annotated with Git blame (Map explore round, position 9). */
        default void annotate(Path file) {}

        /** Opens a link of the card's Quick Documentation in the browser. */
        default void openUrl(String url) {}

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

        /**
         * Every commit that changed {@code file}'s 0-based lines {@code startLine..endLine}, newest first, at most
         * {@code max}, with the part of each diff that touched them; on the FX thread (Map gap 12).
         */
        default void lineHistory(
                Path file, int startLine, int endLine, int max, Consumer<com.editora.git.LineHistory.Result> found) {
            found.accept(com.editora.git.LineHistory.Result.unavailable());
        }

        /** Who last changed each line of {@code file} in the repository at {@code root}; on the FX thread. */
        default void blame(
                Path root, Path file, Consumer<java.util.List<com.editora.git.BlameParser.BlameLine>> found) {
            found.accept(java.util.List.of());
        }

        /**
         * Opens commit {@code hash}'s diff of the file at {@code path} (repository-relative, at {@code previousPath}
         * before the commit when it moved) in the repository at {@code root}, in the editor window.
         */
        default void openCommitFile(Path root, String hash, String path, @Nullable String previousPath) {}

        /**
         * Copy Permalink: the web address of {@code target} — a file, its 0-based lines {@code startLine..endLine}
         * marked (none for a negative {@code startLine}), or a folder — at the HEAD commit, on the clipboard.
         */
        default void copyPermalink(Path target, int startLine, int endLine) {}
    }

    /** The editor commands a card and a declaration row follow the code with, in their menus' order (C12). */
    static final java.util.List<String> FOLLOW_COMMANDS = java.util.List.of(
            "lsp.gotoDefinition",
            "lsp.findReferences",
            "lsp.gotoImplementation",
            "lsp.typeHierarchy",
            "lsp.callHierarchy");

    /** The menu label of one of {@link #FOLLOW_COMMANDS}. */
    static String followLabel(String commandId) {
        return switch (commandId) {
            case "lsp.gotoDefinition" -> tr("project.map.follow.gotoDefinition");
            case "lsp.findReferences" -> tr("project.map.follow.findReferences");
            case "lsp.gotoImplementation" -> tr("project.map.follow.gotoImplementation");
            case "lsp.typeHierarchy" -> tr("project.map.follow.typeHierarchy");
            case "lsp.callHierarchy" -> tr("project.map.follow.callHierarchy");
            default -> commandId;
        };
    }

    /**
     * The code navigation a card does on the Map (Project Map explore, position 5, and Map code navigation): Go to
     * Definition, Find Usages and the rest answered as cards beside it, Quick Documentation, and Back / Forward along
     * the cards the code was followed through.
     */
    interface LinkActions {
        /**
         * {@code kind} of what stands at the file's 0-based {@code line} and {@code column}; with {@code atPlace}
         * false a declaration card asks about its declaration instead (its menu's References and Callers as Cards).
         */
        void show(ProjectMapPreview card, MapCardLinks.Kind kind, int line, int column, boolean atPlace);

        /** Quick Documentation at the file's 0-based {@code line} and {@code column}. */
        default void hover(ProjectMapPreview card, int line, int column) {}

        /**
         * {@code kind}'s hierarchy (Call or Type Hierarchy) of what stands at the file's 0-based {@code line} and
         * {@code column} — with {@code atPlace} false, a declaration card's declaration — as a tree beside the card.
         */
        default void hierarchy(ProjectMapPreview card, MapCardLinks.Kind kind, int line, int column, boolean atPlace) {}

        /** The Symbol Graph of what stands at the file's 0-based {@code line} and {@code column}, as a card. */
        default void symbolGraph(ProjectMapPreview card, int line, int column) {}

        /** Back ({@code back}) or Forward along the Map's one history; false when there is nowhere to go. */
        default boolean step(ProjectMapPreview card, boolean back) {
            return false;
        }

        /**
         * The server's documentation at the file's 0-based {@code line} and {@code column}, given once on the FX
         * thread — null when there is none, or no server — for the mouse resting there; nothing is said.
         */
        default void documentation(ProjectMapPreview card, int line, int column, Consumer<@Nullable String> answer) {
            answer.accept(null);
        }

        /**
         * Keeps {@code file} — its text {@code text} — before its running server and tells {@code problems} its
         * diagnostics, now and as they change; the handle that stops, or null when no server runs for it.
         */
        default @Nullable Runnable watchProblems(
                ProjectMapPreview card, Path file, String text, Consumer<java.util.List<LspDiagnostic>> problems) {
            return null;
        }

        /** Says {@code message} in the Map's status line. */
        default void say(String message) {}
    }

    /** Next and Previous Problem typed in a card: through the card's own problems, not the active tab's. */
    static final String NEXT_PROBLEM_COMMAND = "lsp.nextProblem";

    static final String PREVIOUS_PROBLEM_COMMAND = "lsp.previousProblem";

    /** How long the mouse rests on the text before its problems and documentation show. */
    static final java.time.Duration MOUSE_HOVER_DELAY = java.time.Duration.ofMillis(500);

    /** Quick Documentation typed in a card: the server's hover at the card's caret. */
    static final String HOVER_COMMAND = "lsp.hover";

    /** Back and Forward typed in a card: along the Map's one history (not the editor's trail). */
    static final String BACK_COMMAND = "nav.back";

    static final String FORWARD_COMMAND = "nav.forward";

    /** The Go To Related File command: typed in a card, it reveals the card's file's counterpart on the Map. */
    static final String RELATED_COMMAND = "nav.relatedFile";

    /** The file-history command: typed in a card, the card's file's Git history rather than the active tab's. */
    static final String FILE_HISTORY_COMMAND = "git.fileHistory";

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
            @Nullable Stamp stamp,
            int fileLines) {
        Loaded(String text, @Nullable Image image, boolean truncated, LoadProblem problem, @Nullable Stamp stamp) {
            this(text, image, truncated, problem, stamp, -1);
        }
    }

    private final Consumer<Path> onOpenFile;
    private final BorderPane frame = new BorderPane();
    private final HBox titleBar = UiZoom.spacing(new HBox(), 7);
    private final Label title = new Label();
    private final Label status = new Label();
    private final Label readOnly = new Label();
    private final Button zoomOut = new Button("−");
    private final Button zoomIn = new Button("+");
    /** Shows or hides this card's Structure column, stuck beside it (see {@link ProjectMapCardStructure}). */
    private final ToggleButton structure = new ToggleButton();
    /** Shows or hides this card's Personal Notes, stuck beside it (see {@link ProjectMapStickyNotes}). */
    private final ToggleButton notes = new ToggleButton();

    private final Button open = new Button();
    private final Button close = new Button("×");
    /**
     * Pins the card: a pinned card is never the one closed to make room for a new card (Map explore round, position
     * 7). The card menu has the same toggle.
     */
    private final ToggleButton pin = new ToggleButton();
    /** A declaration card's ‹ and ›: the previous and next declaration of its file, in this card (position 8). */
    private final Button previousDeclaration = new Button();

    private final Button nextDeclaration = new Button();
    /** The title bar, and the find bar under it while it is open (see {@link #showFind}). */
    private final javafx.scene.layout.VBox header = new javafx.scene.layout.VBox();
    /** Find in the card (position 6): the field, "k of N", previous / next, match case and close. */
    private final HBox findBar = UiZoom.spacing(new HBox(), 4);

    private final javafx.scene.control.TextField findField = new javafx.scene.control.TextField();
    private final Label findCount = new Label();
    private final Button findPrevious = new Button();
    private final Button findNext = new Button();
    private final ToggleButton findCase = new ToggleButton("Aa");
    private final Button findClose = new Button("×");
    /** Where the find started: typing searches from here, so each letter refines the same match. */
    private int findOrigin;
    /** The floating bar over a selection in the text, as in a Markdown buffer: here it adds a Personal Note. */
    private final HBox selectionBar = UiZoom.spacing(new HBox(), 2);

    private final Button selectionNote = new Button();
    private final CodeArea editor = AreaUndo.none(new CodeArea());
    private final VirtualizedScrollPane<CodeArea> editorScroll = new VirtualizedScrollPane<>(editor);
    /** The amber wash and corner marker an editor tab paints on a Personal Note's text, here for the notes shown. */
    private final NoteHighlightOverlay noteMarks = new NoteHighlightOverlay(editor);
    /** The wavy underlines an editor tab draws under its server's diagnostics, here for the card's lines. */
    private final LspDiagnosticOverlay problemMarks = new LspDiagnosticOverlay(editor);
    /** Paints every match of the card's find bar over its text, the one the find is on more strongly. */
    private final com.editora.editor.search.SearchHighlightOverlay findMarks =
            new com.editora.editor.search.SearchHighlightOverlay(editor);

    /** The text with its note, problem and find marks over it: what the card shows for anything but an image. */
    private final StackPane textPane = new StackPane(editorScroll, noteMarks, problemMarks, findMarks);
    /** Go to Line, the matching bracket, definitions and the wash of a name's other uses, in the card's text. */
    private final MapCardMoves moves = new MapCardMoves(editor, header, new MapCardMoves.Card() {
        @Override
        public int firstFileLine() {
            Range shown = range;
            return shown == null ? 0 : shown.startLine();
        }

        @Override
        public @Nullable String fileName() {
            Path file = path;
            Path name = file == null ? null : file.getFileName();
            return name == null ? null : name.toString();
        }

        @Override
        public boolean textShown() {
            return !disposed && frame.getCenter() == textPane;
        }
    });
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
    /** The user dragged, resized or moved the card by key: a preview that was is kept (gap 6). */
    private boolean arranged;
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
    /** Lines in the whole file when the text shown is cut short, or -1 when not known. */
    private int fileLines = -1;
    /** The last line of a cut card: the text stops here, and Open shows the rest (C2). */
    private final Label cutFooter = new Label();
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
    /** The file's diagnostics, in the file's lines, as its server last gave them (Map code browser gap 8). */
    private java.util.List<LspDiagnostic> fileProblems = java.util.List.of();
    /** {@link #fileProblems} on the card's own lines: what {@link #problemMarks} draws. */
    private java.util.List<LspDiagnostic> cardProblems = java.util.List.of();
    /** Ends the watch on the file's problems (and its loan to the server); null while none runs. */
    private @Nullable Runnable problemWatch;
    /** What {@link #problemWatch} was started for: the file, from an open buffer or the disk text it was lent. */
    private @Nullable Path watchedPath;

    private boolean watchedFromBuffer;
    private String watchedText = "";
    /** The mouse rests on the text: {@link #MOUSE_HOVER_DELAY} passed and it has not moved off. */
    private boolean mouseResting;
    /** Bumped by every rest and every move off: a documentation answer for an older rest is dropped. */
    private long mouseRest;
    /** The popup showing is the mouse's (it closes when the mouse moves off), not Quick Documentation's. */
    private boolean hoverFromMouse;
    /** What the card last showed for the mouse, until it closed it (the popup may also go by itself). For tests. */
    private @Nullable String mouseHoverText;

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
    private Runnable onStylesShown = () -> {};

    // --- the editor's reading aids (Map code browser round 2) --------------------------------------------------

    /** The card's colours as last applied, before the semantic tokens over them; null until coloured. */
    private @Nullable StyleSpans<Collection<String>> baseSpans;
    /** The language server's semantic tokens of {@link #semanticText}, the file's text they were asked for. */
    private java.util.List<com.editora.text.SemanticToken> semanticTokens = java.util.List.of();

    private String semanticText = "";
    /** The server's inlay hints, by the card's own 0-based line. */
    private java.util.Map<Integer, java.util.List<org.fxmisc.richtext.Inlay>> hintsByLine = java.util.Map.of();

    private boolean inlayFactoryInstalled;
    /** The pinned headers of the scopes scrolled past, over the card's text (sticky scroll). */
    private final com.editora.editor.fold.StickyScrollBar stickyBar = new com.editora.editor.fold.StickyScrollBar();

    private boolean stickyOn;
    private boolean stickyPending;
    /** The text {@link #stickyRegions} were found in: found again when the card's text changes. */
    private @Nullable String stickyRegionsText;

    private java.util.List<com.editora.text.FoldRegions.Region> stickyRegions = java.util.List.of();
    private java.util.function.DoubleConsumer onContentZoomed = zoom -> {};

    private java.util.function.@Nullable BiConsumer<Path, Integer> onOpenAt;
    /** A declaration card's Declaration History: the Map's History card opens beside it (gap 12). */
    private @Nullable Runnable onHistory;
    /** Dependencies: the Map's Dependencies card opens on the card's file, beside it (gap 14). */
    private @Nullable Runnable onDependencies;

    private @Nullable Runnable onOpenNarrowed;

    private boolean loading;
    private boolean statPending;
    /**
     * A refresh came while a look at the disk or a read was under way: that look or read may have begun before the
     * file changed, so the disk is looked at once more when it ends.
     */
    private boolean statAgain;

    private boolean missing;
    private @Nullable String editorConfigCharset;
    private @Nullable Stamp loadedStamp;
    private @Nullable PlacementResolver placementResolver;
    private @Nullable MarkerActions markerActions;
    private @Nullable FollowActions followActions;
    /** Where the card menu's Show References / Callers as Cards go; null hides them. */
    private @Nullable LinkActions linkActions;
    /** What joins this card to the card it was found from, said after its name; null when it was not. */
    private @Nullable String linkDescription;

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
    private Consumer<Boolean> onStructureToggled = shown -> {};
    private Runnable onFocusStructure = () -> {};
    /** How many of the store's notes this card shows (see {@link #setNoteCount}). */
    private int noteCount;
    /** A note was asked for here: the notes come up when the store has one more. */
    private boolean revealNotesOnAdd;

    private boolean selectionBarPending;

    private Consumer<Boolean> onPinned = pinned -> {};
    /** Told -1 or +1: a declaration card's ‹ or › (and Alt+Page Up / Alt+Page Down). */
    private java.util.function.IntConsumer onStep = direction -> {};
    /** Told -1 or +1: a file card's Alt+Page Up / Alt+Page Down, the caret to the declaration before or after it. */
    private java.util.function.IntConsumer onDeclarationMove = direction -> {};
    /** Whether the file has a declaration before / after the one a declaration card shows. */
    private boolean stepBack;

    private boolean stepForward;
    /** A declaration card's Show in Structure, or null where it has none. */
    private @Nullable Runnable onShowInStructure;
    /** Notes are on: the Notes button is in the title bar (it gives way, as the others do, on a narrow card). */
    private boolean notesEnabled;
    /** The status line's last part, "Last change: author, 3 days ago", or "" (position 9). */
    private String lastChangeText = "";
    /** What the status's tooltip adds under it for the last change: its subject and commit. */
    private String lastChangeTip = "";
    /** Where the gutter's breakpoints, bookmarks, ▶ and change bars come from (Map gaps round 3, 1); null: numbers. */
    private MapCardGutter.@Nullable Source gutterSource;
    /** The card's file's marks as last read. */
    private MapCardGutter.Marks gutterMarks = MapCardGutter.Marks.NONE;
    /** The file's change bars, or null when Git does not track it (or has not said yet). */
    private MapCardGutter.@Nullable Changes gutterChanges;
    /** The ▶ lines of the card's file. */
    private java.util.Map<Integer, MapCardGutter.Run> gutterRuns = java.util.Map.of();
    /** The last ask for change bars: a later one wins. */
    private long gutterAsk;
    /** The file's 0-based line the debugger is stopped on, marked on the card (Map gaps round 3, item 2), or -1. */
    private int executionLine = -1;
    /** The stop there is on an exception: the line is drawn red, as in the editor. */
    private boolean executionException;
    /** The paragraph the execution line's style is on, or -1. */
    private int executionParagraph = -1;

    private final Tooltip statusTip = ControlNames.tooltip();

    ProjectMapPreview(Consumer<Path> onOpenFile) {
        this.onOpenFile = onOpenFile == null ? ignored -> {} : onOpenFile;
        getStyleClass().add("project-map-preview");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        // The note and bookmark chords (C-c n, C-c m in Emacs) mark this card's file, not the active tab's.
        getProperties().put(com.editora.command.KeyDispatcher.HANDLED_COMMANDS, (java.util.function.Predicate<String>)
                this::runMarkerCommand);
        // Alt+arrows move the card and Alt+Shift+arrows resize it (history and word moves elsewhere).
        getProperties().put(com.editora.command.KeyDispatcher.CLAIMED_KEYS, DECLARATION_CARD_KEYS);
        setManaged(false);
        setVisible(false);

        title.getStyleClass().add("project-map-preview-title");
        title.setMinWidth(0); // see titleMinWidth: the name keeps its room, the status gives way first
        title.setTextOverrun(OverrunStyle.ELLIPSIS);
        status.getStyleClass().add("project-map-preview-status");
        status.setMinWidth(0);
        status.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(status, Priority.ALWAYS);
        // The status gives way first: cut short, it is read in full from its tooltip (review 3, C11), with the
        // subject of the last change under it (position 9).
        status.textProperty().addListener((obs, old, now) -> updateStatusTip());
        // Read-only is a lock, not a word that took the file's name's room; it says what a card is for: a glance,
        // with Open for reading and editing in the editor (position 12).
        readOnly.setGraphic(Icons.lock());
        readOnly.setTooltip(ControlNames.tooltip(tr("project.map.preview.glance")));
        readOnly.setAccessibleText(tr("project.map.preview.glance"));
        readOnly.getStyleClass().add("project-map-preview-readonly");
        readOnly.setMinWidth(Region.USE_PREF_SIZE);

        for (Button button : java.util.List.of(zoomOut, zoomIn)) {
            button.getStyleClass().add("project-map-preview-button");
        }
        MapButtonCommands.bind(this, zoomOut, "projectMap.cardZoomOut", () -> zoomContent(false));
        MapButtonCommands.bind(this, zoomIn, "projectMap.cardZoomIn", () -> zoomContent(true));

        open.setText(tr("project.map.preview.open"));
        open.getStyleClass().add("project-map-preview-button");
        open.setTooltip(ControlNames.tooltip(tr("project.map.preview.openHelp")));
        open.setOnAction(event -> {
            Path selected = path;
            Range shown = range;
            Runnable narrowed = onOpenNarrowed;
            if (selected != null && shown != null && narrowed != null && !symbolMissing && !missing) {
                narrowed.run(); // a declaration card opens the file narrowed to its declaration
            } else if (selected != null && shown != null && onOpenAt != null) {
                onOpenAt.accept(selected, shown.line()); // one that is gone: the file at its last line
            } else if (selected != null) {
                onOpenFile.accept(selected);
            }
        });
        structure.getStyleClass().addAll("project-map-preview-button", "project-map-preview-structure");
        structure.setGraphic(Icons.structure());
        structure.setVisible(false);
        structure.setManaged(false);
        structure.setOnAction(event -> {
            updateStructureButton();
            onStructureToggled.accept(structure.isSelected());
        });
        MapButtonCommands.bind(this, "projectMap.cardStructure", () -> setStructureShown(!structureShown()));
        CommandTips.name(structure, "projectMap.cardStructure", this::structureHelp);
        notes.getStyleClass().addAll("project-map-preview-button", "project-map-preview-notes");
        notes.setGraphic(Icons.notes());
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
        MapButtonCommands.bind(this, "projectMap.cardNotes", notes::fire); // a press: nothing while it has no note
        CommandTips.name(notes, "projectMap.cardNotes", this::notesHelp);
        close.getStyleClass().add("project-map-preview-button");
        installPinAndStepping();
        for (javafx.scene.control.Control button : java.util.List.of(
                zoomOut, zoomIn, structure, notes, pin, previousDeclaration, nextDeclaration, open, close)) {
            button.setMinWidth(Region.USE_PREF_SIZE); // "Open" stays "Open", never "O…"
        }
        MapButtonCommands.bind(this, close, "projectMap.cardClose", () -> onClose.run());

        titleBar.getStyleClass().add("project-map-preview-header");
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.getChildren()
                .setAll(
                        title,
                        readOnly,
                        status,
                        previousDeclaration,
                        nextDeclaration,
                        zoomOut,
                        zoomIn,
                        pin,
                        structure,
                        notes,
                        open,
                        close);
        titleBar.addEventHandler(MouseEvent.MOUSE_PRESSED, this::dragPressed);
        titleBar.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::dragged);
        header.getChildren().setAll(titleBar);
        installFindBar();
        textPane.getChildren().add(moves.usesOverlay());

        editor.getStyleClass().addAll("editor-area", "project-map-preview-editor");
        editor.setEditable(false);
        editor.setWrapText(false);
        // The keymap's own Copy and Select All (M-w in Emacs, or a chord the user chose) act on the card's text.
        ConsoleNav.onCommand(editor, "edit.copy", editor::copy);
        ConsoleNav.onCommand(editor, "edit.selectAll", editor::selectAll);
        installGutter();
        editor.setAccessibleHelp(tr("project.map.preview.accessibleHelp"));
        installEditorContextMenu();
        // Sized in the pass that lays its lines out: scrolled here, the card opens where it was asked to.
        frame.getStyleClass().add("project-map-preview-frame");
        frame.setTop(header);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        imageScroll.setPannable(true);
        imageScroll.setFitToWidth(false);
        imageScroll.setFitToHeight(false);
        frame.setCenter(textPane);
        cutFooter.setText(tr("project.map.preview.endsHere"));
        cutFooter.getStyleClass().add("project-map-preview-cut");
        cutFooter.setMaxWidth(Double.MAX_VALUE);
        cutFooter.setOnMouseClicked(event -> {
            if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY && !open.isDisabled()) {
                open.fire(); // "Open to read the rest"
                event.consume();
            }
        });
        frame.centerProperty().addListener((observable, before, now) -> updateCutFooter());
        noteMarks.setSpans(this::noteSpans, () -> !marked.isEmpty()); // inert until notes are stuck on the card
        noteMarks.setActive(true);
        editor.setMouseOverTextDelay(MOUSE_HOVER_DELAY);
        editor.addEventHandler(MouseOverTextEvent.MOUSE_OVER_TEXT_BEGIN, this::mouseRests);
        editor.addEventHandler(MouseEvent.MOUSE_MOVED, this::mouseMovedOnText);
        editor.addEventHandler(MouseEvent.MOUSE_EXITED, event -> {
            if (mouseSpan != null) {
                leaveMouseHover();
            }
        });

        resizeGrip.getStyleClass().add("project-map-preview-resize");
        resizeGrip.setCursor(Cursor.SE_RESIZE);
        resizeGrip.setAccessibleText(tr("project.map.preview.resize"));
        resizeGrip.addEventHandler(MouseEvent.MOUSE_PRESSED, this::resizePressed);
        resizeGrip.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::resized);
        installSelectionBar();
        getChildren().addAll(frame, resizeGrip, selectionBar);
        getTransforms().add(mapZoom);

        // A filter, not a handler: the title bar (its drag) and the content (the text area) consume the press, so a
        // handler never heard a click there and a card under another stayed under it.
        addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
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
        addEventFilter(ScrollEvent.SCROLL, event -> {
            openingPending = -1; // the user's own scroll is not undone by the opening line
            linksPlacePending = null; // nor by a linked card's place
            onTouch.run();
        });
        addEventFilter(KeyEvent.KEY_PRESSED, this::keyPressed);
        editor.addEventFilter(MouseEvent.MOUSE_PRESSED, this::navigationClick);
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
        openingPending = -1;
        linksPlacePending = null;
        double[] step = geometryStep(event);
        if (step != null) {
            moveOrResizeBy(step);
            event.consume();
            return;
        }
        int declarationStep = declarationStepOf(event);
        if (declarationStep != 0) {
            stepDeclaration(declarationStep);
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
            } else if (hoverShown()) {
                hideHover(); // Quick Documentation goes first, as in the editor
            } else if (selectionBar.isVisible()) {
                selectionBar.setVisible(false); // as in an editor: the bar goes first, the card on the next Escape
            } else if (moves.lineBarShown()) {
                moves.closeLineBar();
            } else if (findShown()) {
                closeFind(); // the find bar goes before the card, and the match stays selected
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
     * The Structure tool window's command: typed in a card that has a Structure button, it shows the card's own
     * Structure column and puts the keyboard there (review 3, D7), whatever key the keymap gives it.
     */
    static final String STRUCTURE_COMMAND = "tool.structure";

    /**
     * Runs a note or bookmark command typed in the card on the card's file: a note on the selection or the caret
     * line, a bookmark on the caret line, and Edit Note brings up the notes stuck on the card. Every other command
     * is left to the keymap. A marker command is taken even when there is nothing to mark here, so it can never
     * fall through to the active editor tab's file.
     */
    boolean runMarkerCommand(String commandId) {
        if (STRUCTURE_COMMAND.equals(commandId)) {
            return focusStructure();
        }
        if (FIND_COMMANDS.contains(commandId)) {
            return runFindCommand(commandId);
        }
        if (moves.run(commandId)) {
            return true;
        }
        if (problemRunsCommand(commandId)) {
            return true;
        }
        if (followRunsCommand(commandId)) {
            return true;
        }
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

    /**
     * Go to Definition, Find Usages and the other code commands typed in the card — F12 and Shift+F12 in most
     * keymaps — follow the code from the card's caret instead of acting on the active editor tab: on the Map, as
     * cards beside this one (Map code navigation), or, where the Map cannot answer, in the editor (review 3 C12);
     * Related File and File History act on the card's file. False for any other command, or with nothing to follow.
     */
    private boolean followRunsCommand(String commandId) {
        if (navigateRunsCommand(commandId)) {
            return true;
        }
        FollowActions actions = followActions;
        Path file = path;
        boolean follows = FOLLOW_COMMANDS.contains(commandId)
                || RELATED_COMMAND.equals(commandId)
                || FILE_HISTORY_COMMAND.equals(commandId);
        if (!follows || actions == null || file == null || disposed) {
            return false;
        }
        if (RELATED_COMMAND.equals(commandId)) {
            actions.related(file);
        } else if (FILE_HISTORY_COMMAND.equals(commandId)) {
            actions.fileHistory(file);
        } else if (!symbolMissing && frame.getCenter() == textPane && editor.getLength() > 0) {
            actions.follow(file, fileLine(editor.getCurrentParagraph()), editor.getCaretColumn(), commandId);
        }
        return true;
    }

    /**
     * The code navigation typed in the card, answered on the Map: Go to Definition, Declaration, Type Definition,
     * Implementation, Super Method, Find Usages, Call and Type Hierarchy as cards, Quick Documentation as a popup,
     * Back and Forward along the Map's history. False without {@link LinkActions} or for any other command.
     */
    private boolean navigateRunsCommand(String commandId) {
        LinkActions links = linkActions;
        java.util.List<MapCardLinks.Kind> kinds = MapCardLinks.NAVIGATION_COMMANDS.get(commandId);
        MapCardLinks.Kind hierarchy = MapCardLinks.HIERARCHY_COMMANDS.get(commandId);
        boolean back = BACK_COMMAND.equals(commandId);
        boolean step = back || FORWARD_COMMAND.equals(commandId);
        boolean hover = HOVER_COMMAND.equals(commandId);
        if (links == null || path == null || disposed || kinds == null && hierarchy == null && !step && !hover) {
            return false;
        }
        if (step) {
            links.step(this, back);
            return true;
        }
        if (symbolMissing || frame.getCenter() != textPane || editor.getLength() == 0) {
            return true; // nothing to follow here, and never the active tab's
        }
        int line = fileLine(editor.getCurrentParagraph());
        int column = editor.getCaretColumn();
        if (hover) {
            links.hover(this, line, column);
        } else if (hierarchy != null) {
            links.hierarchy(this, hierarchy, line, column, true);
        } else if (kinds != null) {
            kinds.forEach(kind -> links.show(this, kind, line, column, true));
        }
        return true;
    }

    /**
     * Ctrl+click (Cmd+click on macOS) on a name in the card: Go to Definition from there, on the Map — the editor's
     * gesture. Nothing without {@link LinkActions} or off the text.
     */
    private void navigationClick(MouseEvent event) {
        LinkActions links = linkActions;
        if (!event.isShortcutDown()
                || event.getButton() != javafx.scene.input.MouseButton.PRIMARY
                || links == null
                || path == null
                || disposed
                || symbolMissing
                || editor.getLength() == 0) {
            return;
        }
        try {
            int offset = editor.hit(event.getX(), event.getY()).getInsertionIndex();
            editor.moveTo(offset);
            editor.requestFocus();
            links.show(
                    this,
                    MapCardLinks.Kind.DEFINITION,
                    fileLine(editor.getCurrentParagraph()),
                    editor.getCaretColumn(),
                    true);
            event.consume();
        } catch (RuntimeException offTheText) {
            // a press past the text: nothing to follow
        }
    }

    /** The Quick Documentation popup showing over the card's text, or null. */
    private javafx.stage.@Nullable Popup hoverPopup;

    /**
     * Shows Quick Documentation — the server's hover {@code markdown} — at the card's caret, as the editor's Show
     * Documentation does; Escape, a click elsewhere, a caret move or a scroll close it. Links open through {@code
     * openUrl}.
     */
    void showHover(String markdown, Consumer<String> openUrl) {
        hideHover();
        if (disposed || getScene() == null) {
            return;
        }
        showPopup(rendered(markdown, openUrl), markdown, null);
    }

    /** {@code markdown} as the editor's documentation popup shows it; links open through {@code openUrl}. */
    private static javafx.scene.Node rendered(String markdown, Consumer<String> openUrl) {
        try {
            return com.editora.editor.markdown.MarkdownRenderer.renderPopup(
                    com.editora.editor.markdown.MarkdownRenderer.parseForRender(markdown), openUrl);
        } catch (RuntimeException unrendered) {
            Label label = new Label(markdown);
            label.setWrapText(true);
            return label;
        }
    }

    /**
     * Shows {@code content} in the documentation popup, sized for {@code text}: at the caret, or under the mouse's
     * screen point {@code mouse} — then it is the mouse's, and goes when the mouse moves off.
     */
    private void showPopup(javafx.scene.Node content, String text, javafx.geometry.@Nullable Point2D mouse) {
        javafx.stage.Popup[] shown = new javafx.stage.Popup[1];
        shown[0] = HoverCard.show(
                editor,
                content,
                text,
                () -> {
                    if (hoverPopup == shown[0]) {
                        hoverPopup = null;
                        hoverFromMouse = false;
                    }
                },
                mouse);
        hoverPopup = shown[0];
        hoverFromMouse = mouse != null;
        if (mouse != null) {
            // Its own exit, not the one a popup being replaced or closed is sent as it goes: that one would drop the
            // documentation on its way and close the popup replacing it.
            shown[0].getContent().get(0).setOnMouseExited(event -> {
                if (hoverPopup == shown[0]) {
                    leaveMouseHover();
                }
            });
        }
    }

    /** Whether Quick Documentation is showing over the card. */
    boolean hoverShown() {
        javafx.stage.Popup popup = hoverPopup;
        return popup != null && popup.isShowing();
    }

    private void hideHover() {
        javafx.stage.Popup popup = hoverPopup;
        hoverPopup = null;
        hoverFromMouse = false;
        mouseHoverText = null;
        if (popup != null) {
            popup.hide();
        }
    }

    // --- The file's problems, and what shows where the mouse rests (Map code browser gap 8) --------------------

    /**
     * Watches the file's problems while the card shows its text — an open buffer's for as long as the card mirrors
     * it, the disk text's for that text (new text on disk is lent anew) — when nothing watches them for it yet. Ends
     * the watch when the card shows no text, or disk text cut short: the server would check half a file.
     */
    private void watchProblems() {
        LinkActions links = linkActions;
        Path file = path;
        if (links == null
                || file == null
                || disposed
                || missing
                || frame.getCenter() != textPane
                || truncatedShown && !fromBuffer) {
            endProblemWatch();
            return;
        }
        if (problemWatch != null
                && file.equals(watchedPath)
                && watchedFromBuffer == fromBuffer
                && (fromBuffer || watchedText.equals(fullText))) {
            return;
        }
        endProblemWatch();
        Runnable watch = links.watchProblems(this, file, fullText, this::setProblems);
        if (watch != null) {
            problemWatch = watch;
            watchedPath = file;
            watchedFromBuffer = fromBuffer;
            watchedText = fromBuffer ? "" : fullText;
        }
    }

    /** Stops watching the file's problems and lets the server go of the file; the card's marks go. */
    private void endProblemWatch() {
        Runnable watch = problemWatch;
        problemWatch = null;
        watchedPath = null;
        watchedText = "";
        if (watch != null) {
            watch.run();
        }
        if (!fileProblems.isEmpty()) {
            fileProblems = java.util.List.of();
            showProblems();
        }
    }

    /** The file's diagnostics, in its 0-based lines, as its server gave them: marked on the card's lines. */
    void setProblems(java.util.List<LspDiagnostic> problems) {
        fileProblems = java.util.List.copyOf(problems); // never after dispose: it ended the watch
        showProblems();
    }

    private void showProblems() {
        cardProblems = frame.getCenter() == textPane
                ? onCard(fileProblems, fileLine(0), editor.getParagraphs().size())
                : java.util.List.of();
        problemMarks.setActive(!cardProblems.isEmpty());
        problemMarks.setDiagnostics(cardProblems);
        if (!loading && !disposed) {
            status.setText(idleStatus(truncatedShown));
        }
    }

    /** The diagnostics the card marks, on its own lines. For tests. */
    java.util.List<LspDiagnostic> cardProblems() {
        return cardProblems;
    }

    /** Whether the card watches its file's problems. For tests. */
    boolean watchesProblems() {
        return problemWatch != null;
    }

    /**
     * {@code problems}, in a file's 0-based lines, moved onto a card showing {@code lines} lines from the file's
     * {@code firstLine}: those wholly outside it left out, those that run past its edge cut to it. Pure.
     */
    static java.util.List<LspDiagnostic> onCard(java.util.List<LspDiagnostic> problems, int firstLine, int lines) {
        java.util.List<LspDiagnostic> shown = new java.util.ArrayList<>();
        int lastLine = firstLine + lines - 1;
        for (LspDiagnostic d : problems) {
            if (lines <= 0 || d.endLine() < firstLine || d.startLine() > lastLine) {
                continue;
            }
            boolean cutAbove = d.startLine() < firstLine;
            boolean cutBelow = d.endLine() > lastLine;
            shown.add(new LspDiagnostic(
                    cutAbove ? 0 : d.startLine() - firstLine,
                    cutAbove ? 0 : d.startCol(),
                    cutBelow ? lines - 1 : d.endLine() - firstLine,
                    cutBelow ? Integer.MAX_VALUE : d.endCol(),
                    d.severity(),
                    d.message(),
                    d.code(),
                    d.source(),
                    d.href(),
                    d.unnecessary(),
                    d.deprecated()));
        }
        return java.util.List.copyOf(shown);
    }

    /** "2 errors, 1 warning" for {@code problems}, faded tags left out; null with no error or warning. Pure. */
    static @Nullable String problemsSummary(java.util.List<LspDiagnostic> problems) {
        int errors = 0;
        int warnings = 0;
        for (LspDiagnostic d : problems) {
            if (d.tagOnlyHint()) {
                continue;
            }
            if (d.severity() == LspDiagnostic.Severity.ERROR) {
                errors++;
            } else if (d.severity() == LspDiagnostic.Severity.WARNING) {
                warnings++;
            }
        }
        if (errors == 0 && warnings == 0) {
            return null;
        }
        if (warnings == 0) {
            return tr("project.map.preview.errors", errors);
        }
        return errors == 0
                ? tr("project.map.preview.warnings", warnings)
                : tr("project.map.preview.errors", errors) + ", " + tr("project.map.preview.warnings", warnings);
    }

    /**
     * Next and Previous Problem typed in the card: the caret to the card's next (or previous) error — any problem
     * when it has no error — wrapping, as the editor's; the status line says it. Taken even with none here, so it
     * never steps through the active tab's.
     */
    private boolean problemRunsCommand(String commandId) {
        boolean forward = NEXT_PROBLEM_COMMAND.equals(commandId);
        if (!forward && !PREVIOUS_PROBLEM_COMMAND.equals(commandId)) {
            return false;
        }
        java.util.List<LspDiagnostic> listed =
                cardProblems.stream().filter(d -> !d.tagOnlyHint()).toList();
        java.util.List<LspDiagnostic> errors = listed.stream()
                .filter(d -> d.severity() == LspDiagnostic.Severity.ERROR)
                .toList();
        java.util.List<LspDiagnostic> candidates = new java.util.ArrayList<>(errors.isEmpty() ? listed : errors);
        if (candidates.isEmpty()) {
            say(tr("project.map.preview.noProblems")); // no text shown: none marked
            return true;
        }
        candidates.sort(
                java.util.Comparator.comparingInt(LspDiagnostic::startLine).thenComparingInt(LspDiagnostic::startCol));
        LspDiagnostic target =
                LspCoordinator.nextProblem(candidates, editor.getCurrentParagraph(), editor.getCaretColumn(), forward);
        int line = Math.min(target.startLine(), editor.getParagraphs().size() - 1);
        int column = Math.min(target.startCol(), editor.getParagraph(line).length());
        hideHover();
        editor.moveTo(line, column);
        editor.requestFollowCaret();
        say(tr("status.lsp.problem", fileLine(line) + 1, target.message()));
        return true;
    }

    /** Says {@code message} in the Map's status line, when the card is on a Map. */
    private void say(String message) {
        LinkActions links = linkActions;
        if (links != null) {
            links.say(message);
        }
    }

    /** The offsets of the word the mouse rests on — or of the character, off a word — while it shows something. */
    private int @Nullable [] mouseSpan;

    /**
     * The mouse rested on the card's text: the problems there show at once, and the server's documentation for the
     * name there joins them when it comes — the popup the editor's Show Documentation shows, under the mouse. A
     * Quick Documentation asked for with the keyboard stays.
     */
    private void mouseRests(MouseOverTextEvent event) {
        long rest = ++mouseRest;
        if (disposed || hoverShown() && !hoverFromMouse || editorContextMenu.isShowing()) {
            return; // (an empty text, or an image shown, has no text to rest on)
        }
        int offset = Math.max(0, Math.min(event.getCharacterIndex(), editor.getLength() - 1));
        var at = editor.offsetToPosition(offset, TwoDimensional.Bias.Forward);
        int paragraph = at.getMajor();
        int column = at.getMinor();
        String lineText = editor.getParagraph(paragraph).getText();
        int[] word = wordAround(lineText, column);
        int lineStart = offset - column;
        mouseSpan =
                word == null ? new int[] {offset, offset + 1} : new int[] {lineStart + word[0], lineStart + word[1]};
        java.util.List<LspDiagnostic> here = problemMarks.at(paragraph, column);
        javafx.geometry.Point2D screen = event.getScreenPosition();
        if (!here.isEmpty()) {
            showMouseHover(here, null, screen);
        } else if (hoverFromMouse) {
            hideHover();
        }
        LinkActions links = linkActions;
        if (links == null || symbolMissing || word == null) {
            return;
        }
        links.documentation(this, fileLine(paragraph), column, docs -> {
            if (docs != null && rest == mouseRest && (hoverFromMouse || !hoverShown())) {
                showMouseHover(here, docs, screen);
            }
        });
    }

    /** The mouse moved on the text: off what it rested on, what showed for it goes. */
    private void mouseMovedOnText(MouseEvent event) {
        int[] span = mouseSpan;
        if (span == null) {
            return;
        }
        try {
            int offset = editor.hit(event.getX(), event.getY()).getInsertionIndex();
            if (offset >= span[0] && offset <= span[1]) {
                return;
            }
        } catch (RuntimeException offTheText) {
            // past the text: off what it rested on
        }
        leaveMouseHover();
    }

    /**
     * The mouse left what it rested on (or the popup): an answer still on its way is dropped, and the popup goes in a
     * moment unless the mouse is over it — it may be on its way there, to scroll or follow a link.
     */
    private void leaveMouseHover() {
        mouseRest++;
        mouseSpan = null;
        if (mouseHoverText == null) {
            return; // nothing shows for the mouse (or the popup went by itself)
        }
        var later = new javafx.animation.PauseTransition(javafx.util.Duration.millis(300));
        // Unless it rests again meanwhile, Quick Documentation replaced it (no mouse text), or the pointer is on it.
        later.setOnFinished(event -> {
            if (mouseSpan == null && mouseHoverText != null && !mousePopupHovered()) {
                hideHover();
            }
        });
        later.play();
    }

    /** Closes the documentation popup, as Escape does. For tests. */
    void hideHoverForTest() {
        hideHover();
    }

    /** Whether the pointer is over the mouse's popup, which then stays. For tests. */
    boolean mousePopupHovered() {
        javafx.stage.Popup popup = hoverPopup;
        return popup != null && hoverFromMouse && popup.getContent().get(0).isHover();
    }

    /** {@code here}'s problems, then {@code docs} rendered, in the popup under the mouse's {@code screen} point. */
    private void showMouseHover(
            java.util.List<LspDiagnostic> here, @Nullable String docs, javafx.geometry.Point2D screen) {
        javafx.scene.layout.VBox content = UiZoom.spacing(new javafx.scene.layout.VBox(), 6);
        content.getStyleClass().add("project-map-hover");
        StringBuilder sizing = new StringBuilder();
        for (LspDiagnostic d : here) {
            Label problem = new Label(problemText(d));
            problem.setWrapText(true);
            problem.getStyleClass().add("project-map-hover-problem");
            problem.pseudoClassStateChanged(
                    javafx.css.PseudoClass.getPseudoClass(d.severity().name().toLowerCase(java.util.Locale.ROOT)),
                    true);
            problem.setAccessibleText(severityWord(d.severity()) + ": " + problemText(d));
            content.getChildren().add(problem);
            sizing.append(problemText(d)).append('\n');
        }
        if (docs != null) {
            if (!here.isEmpty()) {
                content.getChildren().add(new javafx.scene.control.Separator());
            }
            FollowActions actions = followActions;
            content.getChildren().add(rendered(docs, url -> {
                if (actions != null) {
                    actions.openUrl(url);
                }
            }));
            sizing.append(docs);
        }
        hideHover();
        showPopup(content, sizing.toString(), screen);
        mouseHoverText = sizing.toString();
    }

    /** What the card shows for the mouse now — its problems' and documentation's text — or null. For tests. */
    @Nullable
    String mouseHoverText() {
        return mouseHoverText;
    }

    /** A problem's message and, after it, where it comes from — as the editor's tooltip says it. Pure. */
    static String problemText(LspDiagnostic d) {
        String origin = d.origin();
        return origin.isEmpty() ? d.message() : d.message() + "  (" + origin + ")";
    }

    private static String severityWord(LspDiagnostic.Severity severity) {
        return switch (severity) {
            case ERROR -> tr("lsp.problem.severity.error");
            case WARNING -> tr("lsp.problem.severity.warning");
            case INFO -> tr("lsp.problem.severity.info");
            case HINT -> tr("lsp.problem.severity.hint");
        };
    }

    /** The {@code [start, end)} columns of the name {@code column} is on in {@code line}, or null off a name. Pure. */
    static int @Nullable [] wordAround(String line, int column) {
        if (column < 0 || column >= line.length() || !Character.isJavaIdentifierPart(line.charAt(column))) {
            return null;
        }
        int start = column;
        while (start > 0 && Character.isJavaIdentifierPart(line.charAt(start - 1))) {
            start--;
        }
        int end = column + 1;
        while (end < line.length() && Character.isJavaIdentifierPart(line.charAt(end))) {
            end++;
        }
        return new int[] {start, end};
    }

    /** The caret on the card's 0-based {@code line} and {@code column}, clamped to the text. */
    private void moveCaretTo(int line, int column) {
        int paragraphs = editor.getParagraphs().size();
        if (paragraphs == 0) {
            return;
        }
        int paragraph = Math.max(0, Math.min(line, paragraphs - 1));
        editor.moveTo(
                paragraph,
                Math.max(0, Math.min(column, editor.getParagraph(paragraph).length())));
    }

    /** The caret's offset in the card's text, for Back to return to. */
    int caretOffset() {
        return editor.getCaretPosition();
    }

    /** Puts the caret back at {@code offset} of the card's text (Back, Forward), the line in sight. */
    void restoreCaret(int offset) {
        if (disposed || frame.getCenter() != textPane) {
            return;
        }
        int at = Math.max(0, Math.min(offset, editor.getLength()));
        editor.moveTo(at);
        editor.requestFollowCaret();
    }

    /** Where the card's follow actions go (review 3 C12); null leaves the code commands to the keymap. */
    void setFollowActions(@Nullable FollowActions actions) {
        followActions = actions;
    }

    /** Where the card menu's Show References / Callers as Cards go (Project Map linked cards); null hides them. */
    void setLinkActions(@Nullable LinkActions actions) {
        if (linkActions == actions) {
            return;
        }
        linkActions = actions;
        endProblemWatch();
        watchProblems();
    }

    /**
     * Says, after the card's accessible name and in its title's tooltip, what joins it to the card it was found
     * from ("BookService.add calls save"); null when nothing does any more.
     */
    void setLinkDescription(@Nullable String description) {
        if (!java.util.Objects.equals(linkDescription, description)) {
            linkDescription = description;
            updateRangeChrome();
        }
    }

    // --- links: the place a result card was opened for (Map review 4, links group) ---

    /**
     * A place a result card is to show: the file's 0-based line and what marks the place from there on — or, for a
     * card brought back by a working set or a restart, the caret's {@code column} on that line and the file's line
     * at the top of its text ({@code topLine}).
     */
    private record LinksPlace(int fileLine, java.util.regex.@Nullable Pattern mark, int column, int topLine) {
        LinksPlace(int fileLine, java.util.regex.@Nullable Pattern mark) {
            this(fileLine, mark, -1, -1);
        }
    }

    /** The place asked for before the card's text was shown and coloured, applied once it is. */
    private @Nullable LinksPlace linksPlacePending;
    /** Whether the card's text has been coloured once: a place shown since stays where it was put. */
    private boolean linksStyled;

    /**
     * Linked cards (review 4, C2): scrolls the card — a file card or a declaration card — to the file's 0-based line
     * {@code fileLine} and selects the first match of {@code mark} from that line on (the call or the use, as find
     * selects a match), else that line, now if its text is laid out and again once it is coloured, which a new card's
     * opening scroll would otherwise undo. The user's own scroll or key first cancels the wait.
     */
    void linksRevealPlace(int fileLine, java.util.regex.@Nullable Pattern mark) {
        if (disposed) {
            return;
        }
        LinksPlace place = new LinksPlace(fileLine, mark);
        boolean shown = linksApplyPlace(place);
        linksPlacePending = shown && linksStyled ? null : place;
    }

    /**
     * A card brought back by a working set or a restart (gap 9): its caret on the file's 0-based {@code fileLine} and
     * {@code column}, and the file's {@code topLine} at the top of its text, now if its text is laid out and again
     * once it is coloured; the user's own scroll or key first cancels the wait.
     */
    void restoreView(int fileLine, int column, int topLine) {
        if (fileLine < 0) {
            return; // none was kept (a card kept before v31)
        }
        LinksPlace place = new LinksPlace(fileLine, null, Math.max(0, column), topLine);
        boolean shown = linksApplyPlace(place);
        linksPlacePending = shown && linksStyled ? null : place;
    }

    /** The file's 0-based line at the top of the card's text. */
    int topFileLine() {
        try {
            return fileLine(Math.max(0, editor.firstVisibleParToAllParIndex()));
        } catch (RuntimeException notLaidOut) {
            return fileLine(0);
        }
    }

    private boolean linksApplyPlace(LinksPlace place) {
        if (frame.getCenter() != textPane || editor.getLength() == 0) {
            return false;
        }
        Range shown = range;
        int paragraphs = editor.getParagraphs().size();
        int line = place.fileLine() - (shown == null ? 0 : shown.startLine());
        if (line >= paragraphs) {
            return false; // past what the card holds (its read cap)
        }
        line = Math.max(0, line);
        if (place.column() >= 0) {
            editor.moveTo(
                    line, Math.min(place.column(), editor.getParagraph(line).length()));
            openingPending = -1;
            int top = place.topLine() - fileLine(0); // none kept (-1): the card's top
            editor.showParagraphAtTop(Math.max(0, Math.min(top, paragraphs - 1)));
            return true;
        }
        int start = editor.getAbsolutePosition(line, 0);
        int end = editor.getAbsolutePosition(line, editor.getParagraph(line).length());
        java.util.regex.Pattern mark = place.mark();
        if (mark != null) {
            java.util.regex.Matcher found = mark.matcher(editor.getText());
            if (found.find(start)) {
                start = found.start();
                end = found.end();
            }
        }
        editor.selectRange(start, end);
        openingPending = -1;
        int at = editor.offsetToPosition(start, TwoDimensional.Bias.Forward).getMajor();
        editor.showParagraphAtTop(at <= 3 ? 0 : at - 3); // a few lines of what leads to it above it
        return true;
    }

    private void linksApplyPendingPlace() {
        LinksPlace place = linksPlacePending;
        linksPlacePending = null;
        linksStyled = true;
        if (place != null && !disposed) {
            linksApplyPlace(place);
        }
    }

    /** {@code text}, then what joins the card to the card it was found from, if anything does. */
    private String linked(String text) {
        String link = linkDescription;
        return link == null ? text : text + ". " + link;
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
        arranged = true;
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

    /** What Dependencies does: the Dependencies card opens on the card's file, beside it (gap 14). */
    void setOnDependencies(Runnable callback) {
        onDependencies = callback;
    }

    /** What a declaration card's Declaration History does: its History card opens beside it (gap 12). */
    void setOnHistory(Runnable callback) {
        onHistory = callback;
    }

    /** Where a declaration card's Open goes when its declaration is gone: its file, at the declaration's line. */
    void setOnOpenAt(java.util.function.BiConsumer<Path, Integer> callback) {
        onOpenAt = callback;
    }

    /** A declaration card's Open (and its menu's Open Narrowed): the file opens narrowed to the declaration's block. */
    void setOnOpenNarrowed(Runnable callback) {
        onOpenNarrowed = callback;
        open.getTooltip()
                .setText(
                        tr(callback == null ? "project.map.preview.openHelp" : "project.map.preview.openNarrowedHelp"));
    }

    private void updateRangeChrome() {
        Path shownPath = path;
        if (shownPath == null) {
            return;
        }
        String name = PathDisplay.fileName(shownPath);
        Range shown = range;
        // Alt+Page Up / Alt+Page Down: a declaration card steps to another declaration, a file card's caret moves to
        // one.
        getProperties().put(com.editora.command.KeyDispatcher.CLAIMED_KEYS, DECLARATION_CARD_KEYS);
        updateStepButtons();
        if (shown == null) {
            title.setText(name);
            title.setTooltip(ControlNames.tooltip(linked(shownPath.toString())));
            setAccessibleText(linked(tr("project.map.preview.accessible", shownPath.toString())));
            editor.setAccessibleText(tr("project.map.preview.editorAccessible", name));
            editor.getStyleClass().remove("narrowed");
            installGutter();
            return;
        }
        title.setText(name + " › " + shown.symbol());
        String link = linkDescription;
        title.setTooltip(
                ControlNames.tooltip(shownPath + "\n" + rangeLabel(shown) + (link == null ? "" : "\n" + link)));
        setAccessibleText(linked(tr("project.map.preview.symbolAccessible", shown.symbol(), shownPath.toString())));
        editor.setAccessibleText(tr("project.map.preview.symbolEditorAccessible", shown.symbol(), name));
        if (!editor.getStyleClass().contains("narrowed")) {
            editor.getStyleClass().add("narrowed"); // the editor's narrowed gutter colour
        }
        installGutter();
    }

    private String rangeLabel(Range shown) {
        return tr("project.map.preview.lines", shown.startLine() + 1, shown.endLine() + 1, Math.max(1, fullLines));
    }

    /** The status line when nothing is happening: truncated, a declaration that went, or a card's lines. */
    private String idleStatus(boolean truncated) {
        java.util.List<String> parts = new java.util.ArrayList<>(3);
        Range shown = range;
        if (truncated && shown == null && fullLines > 0) {
            // How much of the file the card holds, not only that it is cut (C2).
            parts.add(
                    fileLines > fullLines
                            ? tr("project.map.preview.showingLines", fullLines, fileLines)
                            : tr("project.map.preview.showingFirstLines", fullLines));
        } else if (truncated) {
            parts.add(tr("project.map.preview.truncated"));
        } else if (shown != null && symbolMissing) {
            parts.add(tr("project.map.preview.symbolMissing"));
        } else if (shown != null && fullLines > 0) {
            parts.add(rangeLabel(shown));
        }
        if (colouredLines > 0) {
            parts.add(tr("project.map.preview.partlyColoured", colouredLines));
        }
        MapLibrarySources.Library library = MapLibrarySources.of(path);
        if (library != null) {
            parts.add(tr("project.map.preview.library", library.where())); // a JDK or dependency class, read-only
        }
        String problems = problemsSummary(cardProblems);
        if (problems != null) {
            parts.add(problems);
        }
        if (unsaved && fromBuffer) {
            parts.add(tr("project.map.preview.unsaved"));
        }
        if (!lastChangeText.isEmpty() && !(shown != null && symbolMissing)) {
            parts.add(lastChangeText); // last: the status gives way from its end
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

    // --- Gutter (Map gaps round 3, item 1) ---

    /** Where the card's gutter marks come from; null leaves it numbers only. */
    void setGutterSource(MapCardGutter.@Nullable Source source) {
        gutterSource = source;
        refreshGutter();
    }

    /** Reads the card's marks again, asks Git for its change bars, and redraws the gutter. */
    void refreshGutter() {
        refreshGutter(true);
    }

    /** Reads the card's marks again ({@code askGit}: and its change bars) and redraws the gutter. */
    void refreshGutter(boolean askGit) {
        Path file = path;
        MapCardGutter.Source source = gutterSource;
        if (disposed) {
            return;
        }
        if (file == null || source == null) {
            gutterMarks = MapCardGutter.Marks.NONE;
            gutterChanges = null;
            gutterRuns = java.util.Map.of();
            installGutter();
            return;
        }
        try {
            gutterMarks = source.marks(file);
        } catch (RuntimeException failed) {
            ProjectMapLog.unexpected("reading a card's gutter marks", failed);
            gutterMarks = MapCardGutter.Marks.NONE;
        }
        boolean javaFile =
                PathDisplay.fileName(file).toLowerCase(java.util.Locale.ROOT).endsWith(".java");
        gutterRuns = javaFile && (gutterMarks.tests() || gutterMarks.mains())
                ? MapCardGutter.runs(fullText, gutterMarks.tests(), gutterMarks.mains())
                : java.util.Map.of();
        installGutter();
        if (askGit) {
            long ask = ++gutterAsk;
            source.changes(file, found -> {
                if (ask == gutterAsk && file.equals(path) && !disposed) {
                    gutterChanges = found;
                    installGutter();
                }
            });
        }
    }

    /** Puts the gutter of the marks as they are: a declaration card's numbers count from its first line. */
    private void installGutter() {
        Range shown = range;
        int first = shown == null ? 0 : shown.startLine();
        int last = shown == null ? Math.max(0, editor.getParagraphs().size() - 1) : shown.endLine();
        editor.setParagraphGraphicFactory(MapCardGutter.rows(
                first,
                last,
                gutterMarks,
                gutterChanges,
                gutterRuns,
                executionLine,
                this::gutterBreakpoint,
                this::gutterRun));
    }

    // --- The debugger's stop (Map gaps round 3, item 2) ---

    /**
     * Marks the file's 0-based {@code fileLine} as where the debugger is stopped — the editor's execution line and its
     * arrow, red for an {@code exception} — and draws the card as the stop card.
     */
    void setExecutionLine(int fileLine, boolean exception) {
        if (disposed) {
            return;
        }
        executionLine = fileLine;
        executionException = exception;
        if (!getStyleClass().contains(DEBUG_STOP_STYLE)) {
            getStyleClass().add(DEBUG_STOP_STYLE);
        }
        applyExecutionStyle();
        installGutter();
    }

    /** The debugger left the card's line: the mark goes. */
    void clearExecutionLine() {
        if (executionLine < 0) {
            return;
        }
        executionLine = -1;
        executionException = false;
        getStyleClass().remove(DEBUG_STOP_STYLE);
        applyExecutionStyle();
        installGutter();
    }

    /** The file's line the card marks as the debugger's, or -1. */
    int executionLine() {
        return executionLine;
    }

    /** The paragraph the execution line is drawn on, or -1. For tests. */
    int executionParagraph() {
        return executionParagraph;
    }

    /** The card's style while it marks where the debugger is stopped. */
    static final String DEBUG_STOP_STYLE = "project-map-debug-stop";

    /** Puts the execution line's style on its paragraph, if the card shows it, and takes it off the one before. */
    private void applyExecutionStyle() {
        int paragraphs = editor.getParagraphs().size();
        if (executionParagraph >= 0 && executionParagraph < paragraphs) {
            editor.setParagraphStyle(executionParagraph, java.util.List.of());
        }
        executionParagraph = -1;
        if (executionLine < 0) {
            return;
        }
        Range shown = range;
        int at = executionLine - (shown == null ? 0 : shown.startLine());
        if (at >= 0 && at < paragraphs && !shownText.isEmpty()) {
            editor.setParagraphStyle(
                    at,
                    executionException
                            ? java.util.List.of("exec-line", "exec-exception")
                            : java.util.List.of("exec-line"));
            executionParagraph = at;
        }
    }

    /** A click in the breakpoint strip on the file's {@code line}. */
    private void gutterBreakpoint(int line) {
        Path file = path;
        MapCardGutter.Source source = gutterSource;
        if (file == null || source == null || disposed) {
            return;
        }
        source.toggleBreakpoint(file, line, fullText);
        refreshGutter(false); // the store is written later; the card shows the click now
    }

    /** A click on a ▶. */
    private void gutterRun(MapCardGutter.Run run) {
        Path file = path;
        MapCardGutter.Source source = gutterSource;
        if (file != null && source != null && !disposed) {
            source.run(file, run);
        }
    }

    /** The gutter's marks as last read. For tests. */
    MapCardGutter.Marks gutterMarks() {
        return gutterMarks;
    }

    /** The change bars as last read. For tests. */
    MapCardGutter.@Nullable Changes gutterChanges() {
        return gutterChanges;
    }

    /** The ▶ lines. For tests. */
    java.util.Map<Integer, MapCardGutter.Run> gutterRuns() {
        return gutterRuns;
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
        moves.reset();
        path = file.toAbsolutePath().normalize();
        if (!path.equals(watchedPath)) {
            endProblemWatch();
        }
        this.placementResolver = placementResolver;
        preferredWidth = DEFAULT_WIDTH;
        preferredHeight = range == null ? DEFAULT_HEIGHT : fittedHeight(range);
        placed = false;
        placementPending = true;
        userMoved = false;
        userSized = false;
        missing = false;
        loadedStamp = null;
        gutterChanges = null; // another file's bars
        gutterRuns = java.util.Map.of();
        executionLine = -1; // another file: the debugger's line is put back by whoever shows it
        executionException = false;
        executionParagraph = -1;
        getStyleClass().remove(DEBUG_STOP_STYLE);
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
        fileLines = -1;
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
            String text = cutContent(content);
            boolean truncated =
                    content.truncated() || text.length() < content.text().length();
            int lines = truncated && !content.truncated() ? lineCount(content.text()) : -1;
            // Known before the card is placed, so it is placed at this size.
            preferredWidth = fittedWidth(visible(text));
            ensurePlaced();
            showLoaded(requested, path, new Loaded(text, null, truncated, LoadProblem.NONE, null, lines));
            highlight(requested, path, text, truncated);
            return;
        }
        ensurePlaced();
        loadFromDisk(requested, path);
    }

    private void loadFromDisk(long requested, Path requestedPath) {
        String charset = editorConfigCharset;
        boolean whole = range != null; // a declaration card reads past the display cap: it shows only its lines
        loading = true;
        submitLatest(() -> {
            Loaded loaded = load(requestedPath, charset, whole);
            Platform.runLater(() -> {
                boolean latest = requested == generation.get();
                if (latest) {
                    loading = false;
                }
                if (showLoaded(requested, requestedPath, loaded)) {
                    highlight(requested, requestedPath, loaded.text(), loaded.truncated());
                }
                if (latest && statAgain && !disposed && !fromBuffer && !statPending) {
                    statDisk(); // a refresh came during the read: the file may have changed after it was read
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
        if (problemWatch == null) {
            watchProblems(); // its server may have started since
        }
        if (content != null && content.open()) {
            if (fromBuffer
                    && !missing
                    && content.version() >= 0
                    && content.version() == shownVersion
                    && frame.getCenter() == textPane) {
                return; // the buffer was not edited: its text is not even read
            }
            String text = cutContent(content);
            boolean truncated =
                    content.truncated() || text.length() < content.text().length();
            int lines = truncated && !content.truncated() ? lineCount(content.text()) : -1;
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
            fileLines = lines;
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
            statAgain = true;
            return;
        }
        statDisk();
    }

    /** Off the FX thread, looks at the shown disk file's size and time; re-reads it when they changed. */
    private void statDisk() {
        Path requestedPath = path;
        if (requestedPath == null) {
            return;
        }
        statAgain = false;
        long requested = generation.get();
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
                } else if (statAgain && !fromBuffer) {
                    statDisk(); // a refresh came after this look began: the file may have changed since
                }
            });
        });
    }

    /**
     * Scrolls a whole-file card to {@code shown}'s lines and marks them as a selection, the declaration line first
     * on screen, as an outline beside a code view moves the view (review 3, E8). False — nothing moved — for a
     * declaration card, a card that shows no text, or lines past what the card holds (the preview's read cap).
     */
    boolean revealRange(Range shown) {
        if (disposed || range != null || shown == null || frame.getCenter() != textPane) {
            return false;
        }
        int paragraphs = editor.getParagraphs().size();
        if (shown.line() >= paragraphs || shown.startLine() >= paragraphs || editor.getLength() == 0) {
            return false;
        }
        int first = Math.max(0, shown.startLine());
        int last = Math.max(first, Math.min(shown.endLine(), paragraphs - 1));
        int start = editor.getAbsolutePosition(first, 0);
        int end = editor.getAbsolutePosition(last, editor.getParagraph(last).length());
        editor.selectRange(end, start); // the caret at the top: keyboard reading carries on from there
        openingPending = -1;
        editor.showParagraphAtTop(first);
        return true;
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

    /** The card's title: its file's name, or {@code file › declaration}. */
    String title() {
        return title.getText();
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

    /** Called when the Structure button shows ({@code true}) or hides this card's Structure column. */
    void setOnStructureToggled(Consumer<Boolean> callback) {
        onStructureToggled = callback == null ? shown -> {} : callback;
    }

    /**
     * Whether the card's file can have an outline: the Structure button is there only then, as a Map row's
     * Structure icon is. Taking it away takes the column away too.
     */
    void setStructureAvailable(boolean available) {
        structure.setVisible(available);
        structure.setManaged(available);
        if (!available) {
            setStructureShown(false);
        }
    }

    boolean structureAvailable() {
        return structure.isVisible();
    }

    boolean structureShown() {
        return structure.isSelected();
    }

    /**
     * Shows the card's Structure column, if it has one, and puts the keyboard on it: the card menu's Show Structure
     * and the Structure command typed in the card. False when the card's file has no outline.
     */
    boolean focusStructure() {
        if (disposed || !structure.isVisible()) {
            return false;
        }
        setStructureShown(true);
        onFocusStructure.run();
        return true;
    }

    /** Puts the keyboard on the card's Structure column once it is out (see {@link #focusStructure}). */
    void setOnFocusStructure(Runnable callback) {
        onFocusStructure = callback == null ? () -> {} : callback;
    }

    /** Shows or hides the card's Structure column as the Structure button does. */
    void setStructureShown(boolean shown) {
        boolean wanted = shown && structure.isVisible();
        if (structure.isSelected() == wanted) {
            return;
        }
        structure.setSelected(wanted);
        updateStructureButton();
        onStructureToggled.accept(wanted);
    }

    private void updateStructureButton() {
        CommandTips.refresh(structure);
    }

    private String structureHelp() {
        return structure.isSelected()
                ? tr("project.map.preview.hideStructure")
                : tr("project.map.preview.showStructure");
    }

    /**
     * How many Personal Notes the store holds for what this card shows — its file, or a declaration's lines —
     * and whether notes are on at all; the Notes button counts them, and is gone while notes are off. A note
     * the user just asked for here brings the card's notes up when it arrives.
     */
    void setNoteCount(int count, boolean enabled) {
        int before = noteCount;
        noteCount = enabled ? Math.max(0, count) : 0;
        if (notesEnabled != enabled) {
            notesEnabled = enabled;
            notes.setVisible(enabled);
            notes.setManaged(enabled);
            requestLayout(); // what the title bar has room for is weighed again
        }
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
        CommandTips.refresh(notes);
        requestLayout(); // pressed, the button no longer gives way
    }

    private String notesHelp() {
        return noteCount == 0
                ? tr("project.map.preview.noNotes")
                : notesSuppressed
                        ? tr("project.map.preview.notesHiddenByOption")
                        : notesOn
                                ? tr("project.map.preview.hideNotes")
                                : tr("project.map.preview.showNotes", noteCount);
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
        hideHover();
        mouseRest++; // an answer still on its way for the mouse is dropped
        endProblemWatch();
        moves.reset();
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
        UiZoom.padding(selectionBar, 3);
        selectionBar.setAlignment(Pos.CENTER_LEFT);
        selectionBar.setVisible(false);
        selectionNote.setText(tr("editmenu.addNote"));
        selectionNote.setGraphic(Icons.notes());
        selectionNote.getStyleClass().addAll("flat", "md-fmt-btn");
        selectionNote.setFocusTraversable(false); // a click must leave the selection's text focused
        selectionNote.setTooltip(ControlNames.tooltip(tr("project.map.preview.addNoteHelp")));
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
        double width = Math.min(selectionBar.prefWidth(-1), Math.max(UiZoom.px(60), text.getWidth()));
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
        followAt = event.isKeyboardTrigger()
                ? new int[] {editor.getCurrentParagraph(), editor.getCaretColumn()}
                : clickPositionAt(event.getX(), event.getY());
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
        if (selection) {
            MenuChords.tag(copy, "edit.copy"); // the key copies the selection; it never copies the whole text
        }
        MenuItem selectAll =
                MenuChords.tag(new MenuItem(tr("editmenu.selectAll"), Icons.selectAll()), "edit.selectAll");
        selectAll.setDisable(empty);
        selectAll.setOnAction(ignored -> {
            editor.selectAll();
            editor.requestFocus();
        });

        MenuItem find = MenuChords.tag(new MenuItem(tr("project.map.preview.find"), Icons.find()), "find.show");
        find.setDisable(empty);
        find.setOnAction(ignored -> showFind());
        MenuItem goToLine = MenuChords.tag(new MenuItem(tr("project.map.preview.goToLine.menu")), "nav.goToLine");
        goToLine.setDisable(empty);
        goToLine.setOnAction(ignored -> moves.showLineBar());
        java.util.List<MenuItem> items = new java.util.ArrayList<>(java.util.List.of(copy, selectAll, find, goToLine));
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
        java.util.List<MenuItem> links = linkMenuItems();
        if (!links.isEmpty()) {
            items.add(new SeparatorMenuItem());
            items.addAll(links);
        }
        java.util.List<MenuItem> follow = followMenuItems();
        if (!follow.isEmpty()) {
            items.add(new SeparatorMenuItem());
            items.addAll(follow);
        }
        MarkerActions actions = markerActions;
        if (!empty && selected != null && actions != null) {
            int fileLine = clickedLine + (shown == null ? 0 : shown.startLine());
            items.add(new SeparatorMenuItem());
            MenuItem bookmark =
                    MenuChords.tag(new MenuItem(tr("editmenu.addBookmark"), Icons.bookmark()), "bookmarks.toggle");
            bookmark.setOnAction(ignored -> actions.addBookmark(selected, fileLine));
            // A declaration that is gone keeps its last lines on the card, but they are no longer the file's.
            bookmark.setDisable(symbolMissing);
            items.add(bookmark);

            MenuItem note = MenuChords.tag(new MenuItem(tr("editmenu.addNote"), Icons.notes()), "notes.add");
            note.setDisable(!actions.personalNotesEnabled() || symbolMissing);
            NoteDraft draft = noteDraftAt(clickedLine);
            note.setOnAction(ignored -> requestNote(actions, selected, draft));
            items.add(note);
        }
        items.add(new SeparatorMenuItem());
        if (structure.isVisible()) {
            // The keyboard way to the card's Structure column (review 3, D7); Hide takes it off again.
            boolean out = structure.isSelected();
            MenuItem column = new MenuItem(
                    tr(out ? "project.map.preview.hideStructure" : "project.map.preview.showStructure"),
                    Icons.structure());
            if (!out) {
                // The Structure command typed in the card shows the column and goes to it; it never hides it.
                MenuChords.tag(column, "tool.structure");
            }
            column.setOnAction(ignored -> {
                if (out) {
                    setStructureShown(false);
                    editor.requestFocus();
                } else {
                    focusStructure();
                }
            });
            items.add(column);
            if (shown == null) {
                // A file card's caret to the declaration before or after it (Alt+Page Up / Alt+Page Down).
                MenuItem previous =
                        new MenuItem(tr("project.map.preview.previousDeclaration.menu"), Icons.chevronLeft());
                previous.setDisable(empty);
                previous.setOnAction(ignored -> stepDeclaration(-1));
                MenuItem next = new MenuItem(tr("project.map.preview.nextDeclaration.menu"), Icons.chevronRight());
                next.setDisable(empty);
                next.setOnAction(ignored -> stepDeclaration(1));
                items.add(previous);
                items.add(next);
            }
        }
        if (shown != null) {
            // A declaration card's ‹ › and Show in Structure (position 8): the buttons may have given way.
            MenuItem previous = new MenuItem(tr("project.map.preview.previousDeclaration.menu"), Icons.chevronLeft());
            previous.setDisable(!stepBack);
            previous.setOnAction(ignored -> stepDeclaration(-1));
            MenuItem next = new MenuItem(tr("project.map.preview.nextDeclaration.menu"), Icons.chevronRight());
            next.setDisable(!stepForward);
            next.setOnAction(ignored -> stepDeclaration(1));
            items.add(previous);
            items.add(next);
            Runnable inStructure = onShowInStructure;
            if (inStructure != null) {
                MenuItem showInStructure = new MenuItem(tr("project.map.preview.showInStructure"), Icons.structure());
                showInStructure.setDisable(symbolMissing);
                showInStructure.setOnAction(ignored -> inStructure.run());
                items.add(showInStructure);
            }
        }
        MenuItem pinItem =
                new MenuItem(tr(pin.isSelected() ? "project.map.preview.unpin.menu" : "project.map.preview.pin.menu"));
        pinItem.setGraphic(Icons.pin());
        pinItem.setOnAction(ignored -> setPinned(!pin.isSelected()));
        items.add(pinItem);
        MenuItem freeSpace = new MenuItem(tr("project.map.preview.moveToFreeSpace"));
        freeSpace.setOnAction(ignored -> onMoveToFreeSpace.run());
        items.add(freeSpace);
        java.util.List<MenuItem> views = viewsMenu.get();
        if (!views.isEmpty()) {
            items.add(new SeparatorMenuItem());
            items.addAll(views);
        }
        editorContextMenu.getItems().setAll(items);
    }

    // --- views: trails (Map explore, views group) -------------------------------------------------------------

    /** What the owner adds at the end of the card menu (Add this card as a stop), built as the menu opens. */
    private java.util.function.Supplier<java.util.List<MenuItem>> viewsMenu = java.util.List::of;
    /** Lines a trail stop asked for before the text was laid out: {@code {first, last}}, 0-based file lines. */
    private int @Nullable [] viewsRevealPending;
    /** What to run when the lines asked for are past the end of the file the card shows (review 4 A9). */
    private Runnable viewsPastEnd = () -> {};
    /** Whether the card's text has been laid out and coloured once: a line past its end is then known to be. */
    private boolean viewsStyled;

    void setViewsMenu(java.util.function.@Nullable Supplier<java.util.List<MenuItem>> items) {
        viewsMenu = items == null ? java.util.List::of : items;
    }

    /**
     * Views (trails): scrolls a whole-file card to the 0-based file lines {@code first..last} and marks them, now if
     * its text is laid out, else once it is (after the opening line a new card scrolls to). A declaration card
     * shows its own lines and is left as it is.
     */
    void viewsRevealLines(int first, int last) {
        viewsRevealLines(first, last, () -> {});
    }

    /**
     * As {@link #viewsRevealLines(int, int)}; {@code pastEnd} runs instead when the file the card shows (all of it,
     * not a truncated start) ends before line {@code first}, so the caller can say so (review 4 A9).
     */
    void viewsRevealLines(int first, int last, @Nullable Runnable pastEnd) {
        if (disposed || range != null) {
            return;
        }
        viewsRevealPending = null;
        viewsPastEnd = pastEnd == null ? () -> {} : pastEnd;
        Range shown = new Range("", first, first, Math.max(first, last));
        if (openingPending >= 0 || !revealRange(shown)) {
            if (openingPending < 0 && viewsStyled && viewsPastTheEnd(first)) {
                viewsPastEnd.run();
                return;
            }
            viewsRevealPending = new int[] {first, Math.max(first, last)};
        }
    }

    private void viewsApplyPendingReveal() {
        viewsStyled = true;
        int[] lines = viewsRevealPending;
        viewsRevealPending = null;
        if (lines != null && !disposed) {
            if (!revealRange(new Range("", lines[0], lines[0], lines[1])) && viewsPastTheEnd(lines[0])) {
                viewsPastEnd.run();
            }
        }
    }

    /** Whether 0-based line {@code first} is past the end of the whole file shown (never for a truncated start). */
    private boolean viewsPastTheEnd(int first) {
        return !disposed
                && !truncatedShown
                && frame.getCenter() == textPane
                && (editor.getLength() == 0 || first >= editor.getParagraphs().size());
    }

    /**
     * Views (trails): the 1-based file lines a stop made of this card covers, {@code {first, last}}: a declaration
     * card's lines (known before its text arrives); else the selected lines, or the lines in sight. Null while a
     * whole-file card shows no text.
     */
    int @Nullable [] viewsStopLines() {
        if (range != null) {
            return disposed ? null : new int[] {range.startLine() + 1, range.endLine() + 1};
        }
        if (disposed || frame.getCenter() != textPane || editor.getLength() == 0) {
            return null;
        }
        int first;
        int last;
        if (editor.getSelection().getLength() > 0) {
            first = editor.offsetToPosition(
                            editor.getSelection().getStart(), org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                    .getMajor();
            last = editor.offsetToPosition(
                            editor.getSelection().getEnd(), org.fxmisc.richtext.model.TwoDimensional.Bias.Backward)
                    .getMajor();
        } else {
            try {
                first = editor.firstVisibleParToAllParIndex();
                last = editor.lastVisibleParToAllParIndex();
            } catch (RuntimeException notLaidOut) {
                first = editor.getCurrentParagraph();
                last = first;
            }
        }
        return new int[] {first + 1, Math.max(first, last) + 1};
    }

    /** Colours the card with {@code spans} (of its text), the semantic tokens of its text laid over them. */
    private void applyBaseSpans(StyleSpans<Collection<String>> spans) {
        baseSpans = spans;
        editor.setStyleSpans(0, withSemantic(spans));
    }

    private StyleSpans<Collection<String>> withSemantic(StyleSpans<Collection<String>> spans) {
        return semanticTokens.isEmpty() || !semanticText.equals(fullText)
                ? spans
                : MapCardSemantics.overlay(spans, shownText, semanticTokens, fileLine(0));
    }

    /**
     * The language server's semantic tokens of the card's file, asked when its text was {@code forText}: laid over
     * the card's colours, as in the editor; dropped when the text has changed since. Empty tokens take them away.
     */
    void setSemanticTokens(String forText, java.util.List<com.editora.text.SemanticToken> tokens) {
        if (disposed || !forText.equals(fullText)) {
            return;
        }
        semanticTokens = java.util.List.copyOf(tokens);
        semanticText = forText;
        StyleSpans<Collection<String>> base = baseSpans;
        if (base != null && base.length() <= editor.getLength()) {
            editor.setStyleSpans(0, withSemantic(base));
        }
    }

    /**
     * The server's inlay hints of the card's file (its lines), asked when its text was {@code forText}: drawn between
     * the card's characters at their columns, as the editor draws them; dropped when the text has changed since.
     */
    void setInlayHints(String forText, java.util.List<com.editora.lsp.LspManager.InlayHintSpan> hints) {
        if (disposed || !forText.equals(fullText)) {
            return;
        }
        int first = fileLine(0);
        int lines = editor.getParagraphs().size();
        java.util.Map<Integer, java.util.List<org.fxmisc.richtext.Inlay>> byLine = new java.util.HashMap<>();
        java.util.List<com.editora.lsp.LspManager.InlayHintSpan> sorted = new java.util.ArrayList<>(hints);
        sorted.sort(java.util.Comparator.comparingInt(com.editora.lsp.LspManager.InlayHintSpan::line)
                .thenComparingInt(com.editora.lsp.LspManager.InlayHintSpan::col));
        for (com.editora.lsp.LspManager.InlayHintSpan hint : sorted) {
            int line = hint.line() - first;
            if (line >= 0 && line < lines && hint.col() <= editor.getParagraphLength(line)) {
                byLine.computeIfAbsent(line, k -> new java.util.ArrayList<>())
                        .add(new org.fxmisc.richtext.Inlay(hint.col(), hint.paddedLabel(), "inlay-hint"));
            }
        }
        if (byLine.isEmpty() && hintsByLine.isEmpty()) {
            return;
        }
        hintsByLine = byLine;
        if (!inlayFactoryInstalled) {
            inlayFactoryInstalled = true;
            editor.setInlayFactory(line -> hintsByLine.get(line));
        }
        editor.refreshInlays();
    }

    /** The inlay hints drawn on the card's 0-based {@code line}, as labels. For tests. */
    java.util.List<String> inlayLabels(int line) {
        java.util.List<org.fxmisc.richtext.Inlay> on = hintsByLine.get(line);
        return on == null
                ? java.util.List.of()
                : on.stream().map(org.fxmisc.richtext.Inlay::getText).toList();
    }

    /**
     * Turns the pinned headers on or off: the lines of the scopes the card has scrolled into but no longer shows
     * the start of, over the top of its text (the editor's sticky scroll). A press on one scrolls the card there.
     */
    void setStickyScroll(boolean on) {
        if (on == stickyOn) {
            return;
        }
        stickyOn = on;
        if (on) {
            javafx.scene.Node bar = stickyBar.node();
            if (!textPane.getChildren().contains(bar)) {
                ((Region) bar).setMaxHeight(Region.USE_PREF_SIZE);
                StackPane.setAlignment(bar, Pos.TOP_LEFT);
                textPane.getChildren().add(bar);
                editor.estimatedScrollYProperty().addListener((obs, was, now) -> scheduleSticky());
            }
            scheduleSticky();
        } else {
            stickyBar.hide();
        }
    }

    private void scheduleSticky() {
        if (!stickyOn || stickyPending) {
            return;
        }
        stickyPending = true;
        Platform.runLater(() -> {
            stickyPending = false;
            int first;
            try {
                first = Math.max(0, editor.firstVisibleParToAllParIndex());
            } catch (RuntimeException notLaidOut) {
                return; // the next scroll brings it back
            }
            stickyLinesFor(first);
        });
    }

    /** The pinned headers for the card scrolled to its 0-based line {@code firstVisible}, shown; their lines. */
    java.util.List<Integer> stickyLinesFor(int firstVisible) {
        if (!stickyOn || disposed) {
            stickyBar.hide();
            return java.util.List.of();
        }
        String text = shownText;
        if (!text.equals(stickyRegionsText)) {
            Path file = path;
            Path name = file == null ? null : file.getFileName();
            String language = com.editora.text.LanguageRegistry.forFileName(name == null ? "" : name.toString());
            stickyRegions = com.editora.text.FoldRegions.detect(text, language);
            stickyRegionsText = text;
        }
        java.util.List<Integer> lines = stickyBar.pinnedLines(stickyRegions, firstVisible);
        stickyBar.update(editor, lines, 4, line -> editor.showParagraphAtTop(line));
        return lines;
    }

    /** Called when the card's text has been coloured: what borrows its colours takes them again. */
    void setOnStylesShown(Runnable callback) {
        onStylesShown = callback == null ? () -> {} : callback;
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

    /** The card line and column the menu's follow items act on: the caret's, or the right-click's. */
    private int[] followAt = {0, 0};

    /** {@code {line, column}} of the card's text under a point, else the caret's. */
    private int[] clickPositionAt(double x, double y) {
        try {
            int offset = editor.hit(x, y).getInsertionIndex();
            var position = editor.offsetToPosition(offset, TwoDimensional.Bias.Forward);
            return new int[] {position.getMajor(), position.getMinor()};
        } catch (RuntimeException ignored) {
            return new int[] {editor.getCurrentParagraph(), editor.getCaretColumn()};
        }
    }

    /** The card menu's follow-the-code items (review 3 C12), acting at {@link #followAt}; empty without actions. */
    private java.util.List<MenuItem> followMenuItems() {
        FollowActions actions = followActions;
        Path file = path;
        if (actions == null || file == null || missing) {
            return java.util.List.of();
        }
        java.util.List<MenuItem> items = new java.util.ArrayList<>();
        boolean text = !symbolMissing && frame.getCenter() == textPane && editor.getLength() > 0;
        int line = fileLine(followAt[0]);
        int column = followAt[1];
        java.util.List<MenuItem> inEditor = new java.util.ArrayList<>();
        for (String commandId : FOLLOW_COMMANDS) {
            MenuItem item = new MenuItem(followLabel(commandId));
            item.setDisable(!text);
            item.setOnAction(ignored -> actions.follow(file, line, column, commandId));
            if (linkActions == null) {
                // Typed in the card the command does this only when the Map does not answer it as cards itself.
                MenuChords.tag(item, commandId);
            }
            inEditor.add(item);
        }
        if (linkActions != null) {
            // The Map answers them as cards (above): the editor's own run stays one submenu away.
            javafx.scene.control.Menu editorMenu = new javafx.scene.control.Menu(tr("project.map.follow.inEditor"));
            editorMenu.getItems().setAll(inEditor);
            items.add(editorMenu);
        } else {
            items.addAll(inEditor);
        }
        items.add(new SeparatorMenuItem());
        MenuItem related = MenuChords.tag(new MenuItem(tr("project.map.follow.related")), RELATED_COMMAND);
        related.setOnAction(ignored -> actions.related(file));
        MenuItem history =
                MenuChords.tag(new MenuItem(tr("project.map.follow.fileHistory"), Icons.gitLog()), "git.fileHistory");
        history.setOnAction(ignored -> actions.fileHistory(file));
        // Annotate: the editor's blame, on the card's file — a declaration card's opens at its declaration (position
        // 9).
        MenuItem annotate = new MenuItem(tr("project.map.preview.annotate"));
        Range shown = range;
        java.util.function.BiConsumer<Path, Integer> openAt = onOpenAt;
        annotate.setOnAction(ignored -> {
            if (shown != null && openAt != null && !symbolMissing) {
                openAt.accept(file, shown.line());
            }
            actions.annotate(file);
        });
        items.add(related);
        Runnable declarationHistory = onHistory;
        if (shown != null && declarationHistory != null) {
            // The declaration's own commits and blame, as a card on the Map beside this one (gap 12).
            MenuItem lines = new MenuItem(tr("project.map.history.menu"), Icons.gitLog());
            lines.setDisable(symbolMissing);
            lines.setOnAction(ignored -> declarationHistory.run());
            items.add(lines);
        }
        items.add(history);
        items.add(annotate);
        // The web address of what the card shows, at HEAD: its selected lines, else its declaration, else the caret's.
        MenuItem permalink = new MenuItem(tr("project.map.permalink.copy"));
        int[] lines = permalinkLines();
        permalink.setOnAction(ignored -> actions.copyPermalink(file, lines[0], lines[1]));
        items.add(permalink);
        Runnable dependencies = onDependencies;
        if (dependencies != null && ProjectMapImports.languageOf(file.toString()) != null) {
            // What uses the file and what it uses, as a card on the Map beside this one (gap 14).
            MenuItem uses = new MenuItem(tr("project.map.deps.menu"), Icons.dependencies());
            uses.setOnAction(ignored -> dependencies.run());
            items.add(uses);
        }
        return items;
    }

    /**
     * The file's 0-based lines Copy Permalink marks: the selected ones, else the declaration a declaration card shows,
     * else the caret's.
     */
    int[] permalinkLines() {
        if (editor.getSelection().getLength() > 0) {
            int[] lines = GitPermalinks.selectedLines(editor);
            return new int[] {fileLine(lines[0]), fileLine(lines[1])};
        }
        Range shown = range;
        if (shown != null && !symbolMissing) {
            return new int[] {shown.startLine(), shown.endLine()};
        }
        int caret = fileLine(editor.getCurrentParagraph());
        return new int[] {caret, caret};
    }

    /** Show References as Cards and Show Callers as Cards, at {@link #followAt} (Project Map linked cards). */
    private java.util.List<MenuItem> linkMenuItems() {
        LinkActions actions = linkActions;
        if (actions == null || path == null || missing) {
            return java.util.List.of();
        }
        boolean text = !symbolMissing && frame.getCenter() == textPane && editor.getLength() > 0;
        int line = fileLine(followAt[0]);
        int column = followAt[1];
        java.util.List<MenuItem> items = new java.util.ArrayList<>();
        for (MapCardLinks.Kind kind : MapCardLinks.MENU_KINDS) {
            MenuItem item = new MenuItem(MapCardLinks.menuLabel(kind));
            item.setDisable(!text);
            // References, Callers and Callees of a declaration card are its declaration's (explore position 5); the
            // rest go from where the menu was opened, as the editor's commands do.
            boolean atPlace = kind != MapCardLinks.Kind.REFERENCES
                    && kind != MapCardLinks.Kind.CALLERS
                    && kind != MapCardLinks.Kind.CALLEES;
            item.setOnAction(ignored -> actions.show(this, kind, line, column, atPlace));
            // The command typed in the card asks from its caret: this row's question, unless the row asks about a
            // declaration card's declaration instead.
            String command = linkCommand(kind);
            if (command != null && (atPlace || range == null)) {
                MenuChords.tag(item, command);
            }
            items.add(item);
        }
        for (MapCardLinks.Kind kind : MapCardLinks.HIERARCHY_MENU_KINDS) {
            MenuItem item = new MenuItem(MapCardLinks.hierarchyMenuLabel(kind));
            item.setDisable(!text);
            // A call hierarchy of a declaration card is its declaration's, as its callers are; a type's from the place.
            boolean atPlace = kind != MapCardLinks.Kind.CALLERS;
            item.setOnAction(ignored -> actions.hierarchy(this, kind, line, column, atPlace));
            String command = hierarchyCommand(kind);
            if (command != null && (atPlace || range == null)) {
                MenuChords.tag(item, command);
            }
            items.add(item);
        }
        // Its callers, callees, supertypes and subtypes around it, walked as in a code browser (round 2).
        MenuItem graph = new MenuItem(tr("project.map.graph.menu"), Icons.symbolGraph());
        graph.setDisable(!text);
        graph.setOnAction(ignored -> actions.symbolGraph(this, line, column));
        items.add(graph);
        MenuItem hover = MenuChords.tag(new MenuItem(tr("project.map.links.hover.show")), "lsp.hover");
        hover.setDisable(!text);
        int[] at = followAt.clone();
        hover.setOnAction(ignored -> {
            moveCaretTo(at[0], at[1]); // the popup stands at the caret: where the menu was opened
            actions.hover(this, line, column);
        });
        items.add(hover);
        return items;
    }

    /**
     * The command that, typed in a card or on a declaration row, asks for {@code kind} alone as cards — what a menu
     * row of that kind shows the key of (Go to Definition before Peek Definition); null when no command does.
     */
    static @Nullable String linkCommand(MapCardLinks.Kind kind) {
        for (java.util.Map.Entry<String, java.util.List<MapCardLinks.Kind>> command :
                MapCardLinks.NAVIGATION_COMMANDS.entrySet()) {
            if (command.getValue().equals(java.util.List.of(kind))) {
                return command.getKey();
            }
        }
        return null;
    }

    /** The command that, typed in a card or on a declaration row, shows {@code kind}'s hierarchy; null when none. */
    static @Nullable String hierarchyCommand(MapCardLinks.Kind kind) {
        for (java.util.Map.Entry<String, MapCardLinks.Kind> command : MapCardLinks.HIERARCHY_COMMANDS.entrySet()) {
            if (command.getValue() == kind) {
                return command.getKey();
            }
        }
        return null;
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

    // --- Map explore round, cards: pin, declaration stepping, find, Git context ----------------------------

    /** The find commands a focused card runs on its own text (position 6), whatever keys the keymap gives them. */
    static final java.util.Set<String> FIND_COMMANDS =
            java.util.Set.of("find.show", "find.showBackward", "find.next", "find.previous");

    /** A declaration card's keys: its moves and resizes, and Alt+Page Up / Alt+Page Down to step (position 8). */
    static final java.util.Set<String> DECLARATION_CARD_KEYS = java.util.Set.copyOf(
            java.util.stream.Stream.concat(GEOMETRY_KEYS.stream(), java.util.stream.Stream.of("M-pageup", "M-pagedown"))
                    .toList());

    /** The status line's room, past a whole title, under which a card's −/+ give way to it. */
    private static final double STATUS_ROOM = 140;

    /** At most this many matches are counted in a card; past it the count reads "k of N+". */
    static final int MAX_FIND_MATCHES = 10_000;

    private void installPinAndStepping() {
        pin.getStyleClass().addAll("project-map-preview-button", "project-map-preview-pin");
        pin.setGraphic(Icons.pin());
        pin.setOnAction(event -> {
            updatePinButton();
            onPinned.accept(pin.isSelected());
        });
        MapButtonCommands.bind(this, "projectMap.cardPin", () -> setPinned(!pinned()));
        CommandTips.name(
                pin,
                "projectMap.cardPin",
                () -> pin.isSelected() ? tr("project.map.preview.unpin") : tr("project.map.preview.pin"));
        previousDeclaration.setGraphic(Icons.chevronLeft());
        nextDeclaration.setGraphic(Icons.chevronRight());
        for (Button button : java.util.List.of(previousDeclaration, nextDeclaration)) {
            button.getStyleClass().addAll("project-map-preview-button", "project-map-preview-step");
        }
        MapButtonCommands.bind(
                this, previousDeclaration, "projectMap.cardPreviousDeclaration", () -> stepDeclaration(-1));
        MapButtonCommands.bind(this, nextDeclaration, "projectMap.cardNextDeclaration", () -> stepDeclaration(1));
        updateStepButtons();
    }

    private void updatePinButton() {
        CommandTips.refresh(pin);
        requestLayout(); // pressed, the pin no longer gives way
    }

    /** Whether the card is pinned: never closed to make room for another (position 7). */
    boolean pinned() {
        return pin.isSelected();
    }

    /** Pins or unpins the card as its pin button does. */
    void setPinned(boolean pinned) {
        if (pin.isSelected() != pinned) {
            pin.setSelected(pinned);
            updatePinButton();
            onPinned.accept(pinned);
        }
    }

    /** Told when the card is pinned ({@code true}) or unpinned. */
    void setOnPinned(Consumer<Boolean> callback) {
        onPinned = callback == null ? pinned -> {} : callback;
    }

    /** Where a declaration card's ‹ › go: told -1 for the previous declaration of its file, +1 for the next. */
    void setOnStep(java.util.function.IntConsumer callback) {
        onStep = callback == null ? direction -> {} : callback;
    }

    /** Whether the declaration card's file has a declaration before and after the one it shows. */
    void setDeclarationStepping(boolean previous, boolean next) {
        stepBack = previous;
        stepForward = next;
        updateStepButtons();
    }

    private void updateStepButtons() {
        previousDeclaration.setDisable(!stepBack);
        nextDeclaration.setDisable(!stepForward);
        requestLayout(); // a file card has no ‹ ›: the title bar is weighed again
    }

    /** -1 for Alt+Page Up, +1 for Alt+Page Down, else 0. Pure. */
    static int declarationStepOf(KeyEvent event) {
        if (event.getEventType() != KeyEvent.KEY_PRESSED
                || !event.isAltDown()
                || event.isControlDown()
                || event.isMetaDown()
                || event.isShiftDown()) {
            return 0;
        }
        return switch (event.getCode()) {
            case PAGE_UP -> -1;
            case PAGE_DOWN -> 1;
            default -> 0;
        };
    }

    /**
     * Shows the previous ({@code -1}) or next declaration of the file in this declaration card; in a file card, moves
     * the caret to the declaration before or after it.
     */
    void stepDeclaration(int direction) {
        if (disposed) {
            return;
        }
        if (range == null) {
            if (frame.getCenter() == textPane && editor.getLength() > 0) {
                onDeclarationMove.accept(direction < 0 ? -1 : 1);
            }
            return;
        }
        if (direction < 0 && !stepBack || direction > 0 && !stepForward) {
            return;
        }
        onStep.accept(direction < 0 ? -1 : 1);
    }

    /** Where a file card's Alt+Page Up / Alt+Page Down go: told -1 for the declaration before the caret, +1 after. */
    void setOnDeclarationMove(java.util.function.IntConsumer callback) {
        onDeclarationMove = callback == null ? direction -> {} : callback;
    }

    /**
     * Whether the card is a preview (Map code browser gap 6): a Go to result the next Go to from it replaces. Its title
     * is in italics, and its title's tooltip says how to keep it.
     */
    void setPreview(boolean preview) {
        if (preview) {
            if (!getStyleClass().contains("project-map-preview-transient")) {
                getStyleClass().add("project-map-preview-transient");
            }
        } else {
            getStyleClass().remove("project-map-preview-transient");
        }
    }

    /** Whether it is a preview (see {@link #setPreview}). */
    boolean preview() {
        return getStyleClass().contains("project-map-preview-transient");
    }

    /** Whether the user moved or resized the card: a preview that was is kept. */
    boolean userArranged() {
        return arranged;
    }

    /** The file's 0-based line the card's caret is on. */
    int caretFileLine() {
        return fileLine(editor.getCurrentParagraph());
    }

    /** The file's 0-based line of the card's text offset {@code offset} (clamped to the text). */
    int fileLineAt(int offset) {
        int at = Math.max(0, Math.min(offset, editor.getLength()));
        return fileLine(editor.offsetToPosition(at, TwoDimensional.Bias.Forward).getMajor());
    }

    /**
     * A file card's caret to the file's 0-based {@code fileLine}, on {@code name} where the line has it as a word, else
     * at the line's first non-blank; the line in sight, a few lines of what leads to it above it. False — nothing
     * moved — past what the card holds.
     */
    boolean moveCaretToLine(int fileLine, @Nullable String name) {
        int line = fileLine - fileLine(0);
        if (disposed
                || frame.getCenter() != textPane
                || line < 0
                || line >= editor.getParagraphs().size()) {
            return false;
        }
        String text = editor.getParagraph(line).getText();
        int column = name == null || name.isBlank() ? -1 : ProjectMapLinkSearch.wordAt(text, name);
        if (column < 0) {
            column = text.length() - text.stripLeading().length();
        }
        editor.moveTo(line, column);
        openingPending = -1;
        linksPlacePending = null;
        editor.showParagraphAtTop(Math.max(0, line - 3));
        editor.requestFollowCaret();
        return true;
    }

    /** The card's movement commands: Go to Line's bar and the uses wash. For tests. */
    MapCardMoves moves() {
        return moves;
    }

    /**
     * Moves a declaration card to another declaration of its file (‹ ›): the title, the lines and the gutter follow,
     * the card stays where it is, and its text starts at the declaration's first line.
     */
    void showDeclaration(Range shown) {
        if (disposed || range == null || shown == null) {
            return;
        }
        setRange(shown);
        if (editor.getLength() > 0) {
            editor.moveTo(0, 0);
            editor.showParagraphAtTop(0);
        }
    }

    /** A declaration card's Show in Structure (position 8); null takes the menu item away. */
    void setOnShowInStructure(@Nullable Runnable callback) {
        onShowInStructure = callback;
    }

    /**
     * Fits the title bar to the card: the title keeps {@code titleMin} (review 3, C11) and the status gives way first;
     * then, when even an empty status leaves too little room, the optional buttons go in turn — −/+, then the pin
     * (unless pressed), then ‹ ›, then Notes (unless pressed). The card menu has every one of them. Returns the title's
     * minimum width: {@code titleMin}, raised by what room is left up to the whole title, so the status has room only
     * once the title has all it wants.
     */
    private double fitTitleBar(double titleMin) {
        boolean declaration = range != null;
        boolean notesPressed = notesOn && !notesSuppressed;
        java.util.List<java.util.List<javafx.scene.control.Control>> optional = java.util.List.of(
                java.util.List.of(zoomOut, zoomIn),
                pin.isSelected() ? java.util.List.of() : java.util.List.of(pin),
                declaration ? java.util.List.of(previousDeclaration, nextDeclaration) : java.util.List.of(),
                notesEnabled && !notesPressed ? java.util.List.of(notes) : java.util.List.of());
        java.util.Set<javafx.scene.Node> wanted =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        wanted.addAll(java.util.List.of(readOnly, open, close));
        if (structure.isVisible()) {
            wanted.add(structure);
        }
        if (notesEnabled) {
            wanted.add(notes);
        }
        if (pin.isSelected()) {
            wanted.add(pin);
        }
        optional.forEach(wanted::addAll);
        double width = getWidth();
        int dropped = width < UiZoom.px(COMPACT_WIDTH) ? 1 : 0; // under the compact width −/+ always go
        while (dropped < optional.size() && titleBarWidth(titleMin, wanted, optional, dropped) > width) {
            dropped++;
        }
        double whole = Math.ceil(title.prefWidth(-1));
        if (dropped == 0
                && !status.getText().isEmpty()
                && width - titleBarWidth(Math.max(titleMin, whole), wanted, optional, 0) < UiZoom.px(STATUS_ROOM)) {
            dropped = 1; // −/+ give way before the status is squeezed out by a whole title
        }
        double left = width - titleBarWidth(titleMin, wanted, optional, dropped);
        for (int group = 0; group < optional.size(); group++) {
            for (javafx.scene.control.Control control : optional.get(group)) {
                boolean shown = group >= dropped;
                if (control.isVisible() != shown) {
                    control.setVisible(shown);
                    control.setManaged(shown);
                }
            }
        }
        // Outside the groups: a pressed pin and pressed Notes stay, a file card has no ‹ ›, Notes go with notes off.
        for (javafx.scene.control.Control control :
                java.util.List.of(pin, previousDeclaration, nextDeclaration, notes)) {
            boolean shown = control == pin ? pin.isSelected() : control == notes && notesEnabled;
            boolean inGroup = optional.stream().anyMatch(group -> group.contains(control));
            if (!inGroup && control.isVisible() != shown) {
                control.setVisible(shown);
                control.setManaged(shown);
            }
        }
        return left <= 0 || whole <= titleMin ? titleMin : Math.min(whole, titleMin + Math.floor(left));
    }

    /** The title bar's width with the first {@code dropped} groups of {@code optional} left out. */
    private double titleBarWidth(
            double titleMin,
            java.util.Set<javafx.scene.Node> wanted,
            java.util.List<java.util.List<javafx.scene.control.Control>> optional,
            int dropped) {
        java.util.Set<javafx.scene.Node> out = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (int group = 0; group < dropped; group++) {
            out.addAll(optional.get(group));
        }
        javafx.geometry.Insets insets = titleBar.getInsets();
        double total = insets.getLeft() + insets.getRight() + titleMin;
        int children = 2; // the title and the status
        for (javafx.scene.Node node : wanted) {
            if (!out.contains(node)) {
                total += node.prefWidth(-1);
                children++;
            }
        }
        return total + titleBar.getSpacing() * (children - 1);
    }

    // --- find in a card (position 6) ---

    private void installFindBar() {
        findBar.getStyleClass().add("project-map-preview-find");
        findBar.setAlignment(Pos.CENTER_LEFT);
        UiZoom.padding(findBar, 3, 6, 3, 6);
        findField.setPromptText(tr("project.map.preview.find.prompt"));
        findField.setAccessibleText(tr("project.map.preview.find.prompt"));
        findField.getStyleClass().add("project-map-preview-find-field");
        HBox.setHgrow(findField, Priority.ALWAYS);
        UiZoom.minWidth(findField, 60);
        findCount.getStyleClass().add("project-map-preview-find-count");
        findCount.setMinWidth(Region.USE_PREF_SIZE);
        findPrevious.setGraphic(Icons.chevronLeft());
        findNext.setGraphic(Icons.chevronRight());
        Icons.name(findPrevious, tr("project.map.preview.find.previous"));
        Icons.name(findNext, tr("project.map.preview.find.next"));
        Icons.name(findCase, tr("project.map.preview.find.matchCase"));
        Icons.name(findClose, tr("project.map.preview.find.close"));
        for (javafx.scene.control.Control control : java.util.List.of(findPrevious, findNext, findCase, findClose)) {
            control.getStyleClass().add("project-map-preview-button");
            control.setMinWidth(Region.USE_PREF_SIZE);
            // Previous, Next and Close leave the Tab order: Shift+Enter, Enter and Escape in the field do the
            // same. Match case stays in it, since no key and no command toggles it.
            control.setFocusTraversable(control == findCase);
        }
        findPrevious.setOnAction(event -> findStep(-1));
        findNext.setOnAction(event -> findStep(1));
        findCase.setOnAction(event -> findStep(0));
        findClose.setOnAction(event -> closeFind());
        findField.textProperty().addListener((obs, old, now) -> findStep(0));
        // F3 / Shift+F3 step through the matches whatever the keymap binds them to (Emacs: macro recording), as
        // the spec promises (review 4, C10).
        findField.getProperties().put(com.editora.command.KeyDispatcher.CLAIMED_KEYS, LINKS_FIND_KEYS);
        findField.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER && !event.isControlDown() && !event.isMetaDown()) {
                findStep(event.isShiftDown() ? -1 : 1);
                event.consume();
            } else if (event.getCode() == KeyCode.F3) {
                findStep(event.isShiftDown() ? -1 : 1);
                event.consume();
            }
        });
        // The keymap's caret and editing chords (C-a, C-e, C-k, C-y; Ctrl+V/X/C/Z) act on the field.
        com.editora.command.TextInputKeymap.installShared(findField);
        findBar.getChildren().setAll(findField, findCount, findPrevious, findNext, findCase, findClose);
    }

    /** The keys the find field takes from the keymap: F3 and Shift+F3 step through the matches. */
    static final java.util.Set<String> LINKS_FIND_KEYS = java.util.Set.of("f3", "S-f3");

    /** Whether the card's find bar is open. */
    boolean findShown() {
        return header.getChildren().contains(findBar);
    }

    /**
     * Opens the card's find bar (Find, typed in the card: Ctrl+F, Cmd+F, Emacs C-s) with the keyboard in its field: a
     * one-line selection is what it looks for, and typing searches from where the selection or caret was.
     */
    void showFind() {
        if (disposed || frame.getCenter() != textPane) {
            return;
        }
        String selected = editor.getSelectedText();
        boolean opening = !findShown();
        if (opening) {
            header.getChildren().add(findBar);
            findOrigin = editor.getSelection().getStart();
        }
        if (opening && selected != null && !selected.isEmpty() && selected.indexOf('\n') < 0) {
            findField.setText(selected);
        }
        findField.requestFocus();
        findField.selectAll();
        findStep(0);
    }

    /** Closes the find bar; the match it found stays selected, and the keyboard goes back to the text. */
    void closeFind() {
        if (!findShown()) {
            return;
        }
        header.getChildren().remove(findBar);
        findMarks.setMatches(com.editora.text.SearchMatches.EMPTY, -1); // the selected match stays; the rest go
        editor.requestFocus();
    }

    /** The find bar's field and its "k of N". For tests. */
    javafx.scene.control.TextField findField() {
        return findField;
    }

    String findCountText() {
        return findCount.getText();
    }

    /** Find, Find Backward, Find Next and Find Previous typed in the card act on its text, never on the editor's. */
    private boolean runFindCommand(String commandId) {
        if (disposed || frame.getCenter() != textPane) {
            return false;
        }
        boolean focusedInBar = findShown() && findField.isFocused();
        switch (commandId) {
            case "find.show" -> {
                if (focusedInBar) {
                    findStep(1); // C-s again in the field: the next match, as an incremental search
                } else {
                    showFind();
                }
            }
            case "find.showBackward" -> {
                if (focusedInBar) {
                    findStep(-1);
                } else {
                    showFind();
                }
            }
            default -> {
                if (findField.getText().isEmpty()) {
                    showFind();
                } else {
                    findStep("find.previous".equals(commandId) ? -1 : 1);
                }
            }
        }
        return true;
    }

    /**
     * Selects the next ({@code 1}) or previous ({@code -1}) match of the find field's text after or before the current
     * one, wrapping, or ({@code 0}) the first at or after where the find began; then says "k of N".
     */
    private void findStep(int direction) {
        String query = findField.getText();
        String text = editor.getText();
        if (query == null || query.isEmpty()) {
            findCount.setText("");
            findField.pseudoClassStateChanged(FIND_MISSING, false);
            findMarks.setMatches(com.editora.text.SearchMatches.EMPTY, -1);
            return;
        }
        int[] starts = findMatches(text, query, findCase.isSelected(), MAX_FIND_MATCHES);
        if (starts.length == 0) {
            findCount.setText(tr("project.map.preview.find.none"));
            findField.pseudoClassStateChanged(FIND_MISSING, true);
            findMarks.setMatches(com.editora.text.SearchMatches.EMPTY, -1);
            return;
        }
        findField.pseudoClassStateChanged(FIND_MISSING, false);
        int current =
                editor.getSelection().getLength() > 0 ? editor.getSelection().getStart() : editor.getCaretPosition();
        int index = findPick(starts, direction == 0 ? findOrigin : current, direction);
        int start = starts[index];
        editor.selectRange(start, start + query.length());
        editor.requestFollowCaret();
        findMarks.setMatches(findSpans(starts, query.length()), index);
        findCount.setText(
                starts.length >= MAX_FIND_MATCHES
                        ? tr("project.map.preview.find.countMore", index + 1, starts.length)
                        : tr("project.map.preview.find.count", index + 1, starts.length));
    }

    private static final javafx.css.PseudoClass FIND_MISSING = javafx.css.PseudoClass.getPseudoClass("missing");

    /** The matches that start at {@code starts}, each {@code length} long, for the layer that paints them. Pure. */
    static com.editora.text.SearchMatches findSpans(int[] starts, int length) {
        java.util.List<int[]> spans = new java.util.ArrayList<>(starts.length);
        for (int start : starts) {
            spans.add(new int[] {start, start + length});
        }
        return com.editora.text.SearchMatches.ofPairs(spans);
    }

    /** Where {@code query} starts in {@code text}, in order, at most {@code max} of them; overlaps count. Pure. */
    static int[] findMatches(String text, String query, boolean matchCase, int max) {
        if (text == null || query == null || query.isEmpty() || text.length() < query.length()) {
            return new int[0];
        }
        String haystack = matchCase ? text : text.toLowerCase(java.util.Locale.ROOT);
        String needle = matchCase ? query : query.toLowerCase(java.util.Locale.ROOT);
        if (haystack.length() != text.length() || needle.length() != query.length()) {
            haystack = text; // a case fold that changed lengths: offsets must stay the text's
            needle = query;
        }
        int[] found = new int[16];
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0 && count < max; at = haystack.indexOf(needle, at + 1)) {
            if (count == found.length) {
                found = Arrays.copyOf(found, found.length * 2);
            }
            found[count++] = at;
        }
        return Arrays.copyOf(found, count);
    }

    /**
     * The index in {@code starts} (ascending, non-empty) of the match a step lands on from {@code from}: the first at or
     * after it ({@code direction 0}), the first after it ({@code 1}) or the last before it ({@code -1}), wrapping. Pure.
     */
    static int findPick(int[] starts, int from, int direction) {
        if (direction < 0) {
            for (int i = starts.length - 1; i >= 0; i--) {
                if (starts[i] < from) {
                    return i;
                }
            }
            return starts.length - 1;
        }
        for (int i = 0; i < starts.length; i++) {
            if (direction == 0 ? starts[i] >= from : starts[i] > from) {
                return i;
            }
        }
        return 0;
    }

    // --- Git context (position 9) ---

    /**
     * The commit that last changed what the card shows — its file, or a declaration card's lines — at the end of its
     * status line ("Last change: Ada, 3 days ago") with its subject in the status's tooltip; null takes it away.
     */
    void setLastChange(com.editora.git.GitService.@Nullable LastChange change) {
        if (change == null) {
            lastChangeText = "";
            lastChangeTip = "";
        } else if (change.shallow()) {
            // The clone's cut, not a change: what changed it last is older than the history here (Map review 4 C7).
            lastChangeText = tr("project.map.preview.lastChangeShallow");
            lastChangeTip = tr("project.map.preview.lastChangeShallowTip");
        } else {
            long now = System.currentTimeMillis() / 1000;
            String when = GitLogPanel.relativeDate(change.epochSeconds(), "", now);
            lastChangeText = when.isEmpty()
                    ? tr("project.map.preview.lastChangeBy", change.author())
                    : tr("project.map.preview.lastChange", change.author(), when);
            String shortHash =
                    change.hash().substring(0, Math.min(9, change.hash().length()));
            lastChangeTip = tr(
                    range != null && change.lines()
                            ? "project.map.preview.lastChangeLinesTip"
                            : "project.map.preview.lastChangeFileTip",
                    change.subject(),
                    shortHash);
        }
        if (!loading && !missing) {
            status.setText(idleStatus(truncatedShown));
        }
        updateStatusTip();
    }

    /** The status line's Git part ("Last change: …"), or "". For tests. */
    String lastChangeText() {
        return lastChangeText;
    }

    private void updateStatusTip() {
        String text = status.getText();
        String full = lastChangeTip.isEmpty() || !text.contains(lastChangeText) || lastChangeText.isEmpty()
                ? text
                : text + "\n" + lastChangeTip;
        statusTip.setText(full);
        status.setTooltip(full.isEmpty() ? null : statusTip);
    }

    /** The status's tooltip text, or "" without one. For tests. */
    String statusTipText() {
        return status.getTooltip() == null ? "" : statusTip.getText();
    }

    @Override
    protected void layoutChildren() {
        double titleMin = fitTitleBar(titleMinWidth(title.getText(), range != null, getWidth()));
        if (title.getMinWidth() != titleMin) {
            title.setMinWidth(titleMin);
        }
        frame.resizeRelocate(0, 0, getWidth(), getHeight());
        double grip = UiZoom.px(18);
        resizeGrip.resizeRelocate(Math.max(0, getWidth() - grip), Math.max(0, getHeight() - grip), grip, grip);
    }

    /** What {@link #titleMinWidth} last measured: the title's text and the width of its name in it. */
    private String measuredTitle = "";

    private double measuredTitleWidth;

    /**
     * The title's minimum width: room for the file's name, or a declaration card's file and declaration name up to its
     * parameters ({@code kubelet.go › Run}), with its icon — at most half the card. The status line and the buttons
     * give way before it does, so several cards can be told apart (review 3, C11).
     */
    private double titleMinWidth(String text, boolean declaration, double cardWidth) {
        if (text == null || text.isEmpty() || cardWidth <= 0) {
            return 0;
        }
        if (!text.equals(measuredTitle)) {
            measuredTitle = text;
            Text measure = new Text(titleKey(text, declaration));
            measure.setFont(title.getFont());
            javafx.scene.Node icon = title.getGraphic();
            double iconWidth = icon == null ? 0 : icon.prefWidth(-1) + title.getGraphicTextGap();
            measuredTitleWidth = Math.ceil(measure.getLayoutBounds().getWidth() + iconWidth + UiZoom.px(4));
        }
        return Math.min(measuredTitleWidth, cardWidth * 0.5);
    }

    /**
     * The part of a card's title that must stay readable: a declaration card's title up to its parameter list, a file
     * card's whole name. Pure.
     */
    static String titleKey(String title, boolean declaration) {
        if (!declaration) {
            return title;
        }
        int parameters = title.indexOf('(', Math.max(0, title.indexOf('›')));
        return parameters > 0 ? title.substring(0, parameters) : title;
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
        arranged = true;
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
        arranged = true;
        preferredHeight =
                boundedSize(resizeHeight + (event.getScreenY() - resizeScreenY) / scale, MIN_HEIGHT, maxHeight);
        resize(preferredWidth, preferredHeight);
        requestLayout();
        event.consume();
    }

    private static Loaded load(Path requestedPath, @Nullable String editorConfigCharset) {
        return load(requestedPath, editorConfigCharset, false);
    }

    /**
     * Reads a card's file. {@code whole}: up to {@link #MAX_OUTLINE_BYTES}, with no display cap — an outline's read
     * and a declaration card's, which shows only its own lines of it.
     */
    private static Loaded load(Path requestedPath, @Nullable String editorConfigCharset, boolean whole) {
        Stamp stamp = stamp(requestedPath);
        try (InputStream in = Files.newInputStream(requestedPath)) {
            boolean imageFile = isImage(requestedPath);
            int limit = imageFile ? MAX_IMAGE_BYTES : whole ? MAX_OUTLINE_BYTES : MAX_PREVIEW_BYTES;
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
            String text = whole ? capOutlineText(decoded, false) : capText(decoded, false);
            boolean truncated = byteTruncated || text.length() < decoded.length();
            int lines = -1;
            if (truncated && !whole) {
                lines = byteTruncated ? countLines(bytes, in, stamp) : lineCount(decoded);
            }
            return new Loaded(text, null, truncated, LoadProblem.NONE, stamp, lines);
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

    /**
     * {@code file}'s text as an outline reads it: decoded as a card reads it, but up to {@link #MAX_OUTLINE_BYTES}
     * and not cut at the card's display cap, so the declarations past it are outlined too (C2). Off the FX thread.
     */
    static @Nullable ReadText readOutlineText(Path file, @Nullable String editorConfigCharset) {
        if (isImage(file)) {
            return null;
        }
        Loaded loaded = load(file, editorConfigCharset, true);
        return loaded.problem() == LoadProblem.NONE && loaded.image() == null
                ? new ReadText(loaded.text(), loaded.truncated())
                : null;
    }

    /** {@code text} within the outline cap ({@link #MAX_OUTLINE_CHARS}), as {@link #capText} cuts. */
    static String capOutlineText(String text, boolean alreadyCut) {
        int end = Math.min(text.length(), MAX_OUTLINE_CHARS);
        if ((end < text.length() || alreadyCut) && end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return end == text.length() ? text : text.substring(0, end);
    }

    /** What a card shows of an open buffer: a whole-file card its first part, a declaration card all it was given. */
    private String cutContent(Content content) {
        return range == null
                ? capText(content.text(), content.truncated())
                : capOutlineText(content.text(), content.truncated());
    }

    /** Lines in {@code text}. */
    static int lineCount(String text) {
        int lines = 1;
        for (int i = text.indexOf('\n'); i >= 0; i = text.indexOf('\n', i + 1)) {
            lines++;
        }
        return lines;
    }

    /**
     * Lines in a file of which {@code read} are the first bytes, the rest still to come from {@code in}; -1 when the
     * file is too big to count (or the count fails). Off the FX thread.
     */
    private static int countLines(byte[] read, InputStream in, @Nullable Stamp stamp) {
        if (stamp == null || stamp.size() > MAX_LINE_COUNT_BYTES) {
            return -1;
        }
        long lines = 1;
        for (byte b : read) {
            lines += b == '\n' ? 1 : 0;
        }
        try {
            byte[] chunk = new byte[64 * 1024];
            for (int got = in.read(chunk); got > 0; got = in.read(chunk)) {
                for (int i = 0; i < got; i++) {
                    lines += chunk[i] == '\n' ? 1 : 0;
                }
            }
        } catch (IOException unreadable) {
            return -1;
        }
        return (int) Math.min(Integer.MAX_VALUE, lines);
    }

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
            Platform.runLater(this::applyOpening); // nothing to colour: the opening scroll waits for no styles
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
        fileLines = loaded.fileLines();
        if (!showText(loaded.text(), loaded.truncated())) {
            return false; // held until the outline says where the declaration went
        }
        if (first) {
            // A whole-file card opens past a leading licence header, on the package or first declaration line;
            // the numbers stay the file's (review 3, B18).
            int opening = range == null ? openingLine(shownText) : 0;
            editor.moveTo(opening, 0);
            if (opening == 0) {
                editor.scrollToPixel(0, 0);
            } else {
                editor.showParagraphAtTop(opening);
                openingPending = opening;
            }
            fitTextWidth(shownText);
        }
        return true;
    }

    /** The line a new card is to open on, until its text is coloured (see {@link #applyOpening}); -1 when none. */
    private int openingPending = -1;

    /** How far {@link #openingLine} looks for the end of a leading comment. */
    private static final int OPENING_SCAN_LINES = 200;

    /**
     * The 0-based line a whole-file card opens on: the first non-blank line after a leading licence header — a
     * comment block of at least three lines (after an optional {@code #!} or {@code <?xml} line) that names a
     * copyright or licence — else 0. Block comments ({@code /* … *}{@code /}, {@code <!-- … -->}) and runs of
     * {@code //}, {@code #}, {@code --} or {@code ;} lines count. Pure.
     */
    static int openingLine(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        String[] lines = text.split("\n", OPENING_SCAN_LINES + 1);
        int count = Math.min(lines.length, OPENING_SCAN_LINES);
        int at = 0;
        if (at < count && (lines[at].startsWith("#!") || lines[at].startsWith("<?xml"))) {
            at++;
        }
        while (at < count && lines[at].isBlank()) {
            at++;
        }
        if (at >= count) {
            return 0;
        }
        int start = at;
        String head = lines[at].strip();
        int end = -1;
        if (head.startsWith("/*") || head.startsWith("<!--")) {
            String close = head.startsWith("/*") ? "*/" : "-->";
            for (int i = at; i < count; i++) {
                String line = i == at ? lines[i].strip().substring(2) : lines[i];
                if (line.contains(close)) {
                    end = i;
                    break;
                }
            }
        } else {
            String prefix = java.util.stream.Stream.of("//", "#", "--", ";")
                    .filter(head::startsWith)
                    .findFirst()
                    .orElse(null);
            if (prefix != null) {
                end = at;
                while (end + 1 < count && lines[end + 1].strip().startsWith(prefix)) {
                    end++;
                }
            }
        }
        if (end < 0 || end - start < 2) {
            return 0; // not a comment, an unclosed one, or a line or two that is worth reading
        }
        String block = String.join("\n", java.util.Arrays.asList(lines).subList(start, end + 1))
                .toLowerCase(java.util.Locale.ROOT);
        if (!block.contains("copyright") && !block.contains("licen") && !block.contains("spdx")) {
            return 0; // a file's own doc comment is its opening, not boilerplate
        }
        int next = end + 1;
        while (next < count && lines[next].isBlank()) {
            next++;
        }
        return next < lines.length && next < count ? next : 0;
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
        javafx.scene.layout.VBox info = UiZoom.spacing(new javafx.scene.layout.VBox(heading), 4);
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
        UiZoom.padding(info, 12);
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
        updateCutFooter();
        watchProblems();
        if (changed) {
            showProblems();
        }
        status.setText(idleStatus(truncated));
        if (changed) {
            refreshGutter(true);
            applyExecutionStyle();
            onTextShown.run();
        }
    }

    /** A whole-file card cut short ends in a last line saying so; any other card has none. */
    private void updateCutFooter() {
        boolean cut = truncatedShown && range == null && frame.getCenter() == textPane;
        if (cut != (frame.getBottom() == cutFooter)) {
            frame.setBottom(cut ? cutFooter : null);
        }
    }

    /** The last line a cut card shows under its text, or null when the card's text is whole. For tests. */
    @Nullable
    Label cutFooter() {
        return frame.getBottom() == cutFooter ? cutFooter : null;
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
        return Math.max(MIN_HEIGHT, Math.min(DEFAULT_HEIGHT, lines * lineHeight + UiZoom.px(TEXT_CHROME_HEIGHT)));
    }

    /** The width that shows the text's longest line, up to {@value #MAX_FIT_COLUMNS} columns. */
    private double fittedWidth(String text) {
        int columns = Math.min(MAX_FIT_COLUMNS, widestLineColumns(text));
        Text glyph = new Text("M");
        glyph.setFont(textFont());
        double glyphWidth = Math.max(1, glyph.getLayoutBounds().getWidth());
        return Math.max(DEFAULT_WIDTH, columns * glyphWidth + UiZoom.px(TEXT_CHROME_WIDTH));
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
        if (disposed || requested != generation.get() || !requestedPath.equals(path)) {
            return;
        }
        if (spans == null) {
            applyOpening();
            return;
        }
        if (!fullText.equals(text) || editor.getLength() != shownText.length()) {
            return;
        }
        if (prefix) {
            if (spans.length() > shownText.length()) {
                return;
            }
            applyBaseSpans(spans);
            colouredLines = editor.offsetToPosition(spans.length(), TwoDimensional.Bias.Backward)
                            .getMajor()
                    + 1;
        } else if (range == null || !whole) {
            if (spans.length() != shownText.length()) {
                return;
            }
            applyBaseSpans(spans);
        } else {
            int end = sliceOffset + shownText.length();
            if (end > spans.length()) {
                return;
            }
            applyBaseSpans(spans.subView(sliceOffset, end));
        }
        status.setText(idleStatus(truncated));
        applyOpening();
        onStylesShown.run();
    }

    /**
     * Scrolls a new card to its opening line once its text is coloured (or known not to be): the text area forgets a
     * scroll asked for before its new lines were first laid out and styled. Nothing once the user scrolled or typed.
     */
    private void applyOpening() {
        int line = openingPending;
        openingPending = -1;
        if (line > 0 && !disposed && line < editor.getParagraphs().size()) {
            editor.showParagraphAtTop(line);
        }
        viewsApplyPendingReveal(); // a trail stop's lines, once the text can be scrolled
        linksApplyPendingPlace(); // a linked card's place (review 4, C2)
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

    /**
     * A result card of a linked question (Project Map explore 5) keeps the narrower width it was placed at: text that
     * arrives later does not widen it, so the cards around the one they were found from stay clear of each other.
     */
    void keepPlacedWidth() {
        userSized = true;
    }

    /**
     * Places the card again by {@code resolver}, as a new card is placed, at the size it has: an open declaration
     * card asked for from another Structure column goes beside that one (Map Structure previews).
     */
    void placeAgain(PlacementResolver resolver) {
        if (disposed) {
            return;
        }
        placementResolver = resolver;
        placed = false;
        placementPending = true;
        userMoved = false;
        ensurePlaced();
    }

    /** A card brought back where it was (B6): nothing moves or resizes it for the user, as after a drag. */
    void keepRestoredGeometry() {
        userMoved = true;
        userSized = true;
    }

    /** The card's text zoom (−/+), 1.0 at the editor's font size: what the Map remembers of a card (B6). */
    double contentZoom() {
        return contentZoom;
    }

    /** The −/+ buttons, {@code projectMap.cardZoomOut} and {@code .cardZoomIn}: a step smaller or larger. */
    void zoomContent(boolean in) {
        setContentZoom(in ? contentZoom * 1.1 : contentZoom / 1.1);
        onContentZoomed.accept(contentZoom); // the owner opens its next card at this zoom
    }

    /** Sets the card's text zoom, clamped to 0.5–3.0: a card brought back from the last session (B6). */
    void setContentZoom(double requested) {
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
