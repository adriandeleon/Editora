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
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

import com.editora.config.NoteScope;
import com.editora.config.TextAnchor;
import com.editora.editor.GrammarRegistry;
import com.editora.editor.LineEndings;
import com.editora.editor.NoteDraft;
import com.editora.editor.TextMateHighlighter;
import com.editora.editorconfig.EditorConfigCharset;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.TwoDimensional;

import static com.editora.i18n.Messages.tr;

/** A bounded, read-only editor card that floats above the Project map without participating in its zoom. */
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
    private static final int TAB_COLUMNS = 4;
    /** A card never widens itself past this many columns; longer lines scroll. */
    private static final int MAX_FIT_COLUMNS = 120;
    /** Under this width the title bar keeps only the name, the read-only badge, Open and Close. */
    private static final double COMPACT_WIDTH = 330;

    /**
     * What the editor knows about a file the card shows. For a file that is open in a tab it is the buffer's
     * current text (captured on the FX thread, unsaved changes included); for any other file only the
     * {@code .editorconfig} charset the editor would decode it with.
     */
    record Content(String text, boolean truncated, boolean open, String editorConfigCharset) {
        Content {
            text = text == null ? "" : text;
        }

        /** An open buffer's text. */
        Content(String text, boolean truncated) {
            this(text, truncated, true, null);
        }

        /** A file that is not open: it is read from disk, decoded the way the editor would open it. */
        static Content closed(String editorConfigCharset) {
            return new Content("", false, false, editorConfigCharset);
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

    interface MarkerActions {
        boolean personalNotesEnabled();

        void addBookmark(Path file, int line);

        void addPersonalNote(Path file, NoteDraft draft);
    }

    private enum LoadProblem {
        NONE,
        BINARY,
        FAILED,
        MISSING
    }

    /** What a file looked like when it was read, to tell later whether it changed on disk. */
    private record Stamp(long size, long modifiedMillis) {}

    private record Loaded(String text, Image image, boolean truncated, LoadProblem problem, Stamp stamp) {}

    private final Consumer<Path> onOpenFile;
    private final BorderPane frame = new BorderPane();
    private final HBox titleBar = new HBox(7);
    private final Label title = new Label();
    private final Label status = new Label();
    private final Label readOnly = new Label();
    private final Button zoomOut = new Button("−");
    private final Button zoomIn = new Button("+");
    private final Button open = new Button();
    private final Button close = new Button("×");
    private final CodeArea editor = AreaUndo.none(new CodeArea());
    private final VirtualizedScrollPane<CodeArea> editorScroll = new VirtualizedScrollPane<>(editor);
    private final ContextMenu editorContextMenu = new ContextMenu();
    private final ImageView imageView = new ImageView();
    private final ScrollPane imageScroll = new ScrollPane(imageView);
    private final Region resizeGrip = new Region();
    private final ThreadPoolExecutor loader =
            new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r -> {
                Thread thread = new Thread(r, "project-map-preview-loader");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicLong generation = new AtomicLong();

    private Path path;
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

    private boolean loading;
    private boolean statPending;
    private boolean missing;
    private String editorConfigCharset;
    private Stamp loadedStamp;
    private PlacementResolver placementResolver;
    private MarkerActions markerActions;
    private double preferredWidth = DEFAULT_WIDTH;
    private double preferredHeight = DEFAULT_HEIGHT;
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

    ProjectMapPreview(Consumer<Path> onOpenFile) {
        this.onOpenFile = onOpenFile == null ? ignored -> {} : onOpenFile;
        getStyleClass().add("project-map-preview");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
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
        zoomOut.setOnAction(event -> setContentZoom(contentZoom / 1.1));
        zoomIn.setOnAction(event -> setContentZoom(contentZoom * 1.1));

        open.setText(tr("project.map.preview.open"));
        open.getStyleClass().add("project-map-preview-button");
        open.setTooltip(new Tooltip(tr("project.map.preview.openHelp")));
        open.setOnAction(event -> {
            Path selected = path;
            if (selected != null) {
                onOpenFile.accept(selected);
            }
        });
        close.getStyleClass().add("project-map-preview-button");
        close.setTooltip(new Tooltip(tr("project.map.preview.close")));
        close.setAccessibleText(tr("project.map.preview.close"));
        close.setOnAction(event -> onClose.run());

        titleBar.getStyleClass().add("project-map-preview-header");
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.getChildren().setAll(title, readOnly, status, zoomOut, zoomIn, open, close);
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
        frame.setCenter(editorScroll);

        resizeGrip.getStyleClass().add("project-map-preview-resize");
        resizeGrip.setCursor(Cursor.SE_RESIZE);
        resizeGrip.setAccessibleText(tr("project.map.preview.resize"));
        resizeGrip.addEventHandler(MouseEvent.MOUSE_PRESSED, this::resizePressed);
        resizeGrip.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::resized);
        getChildren().addAll(frame, resizeGrip);

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
    }

    /**
     * Escape closes the card (its owner then returns focus to the map), and Tab leaves the text: a read-only
     * area has no use for a tab character, and swallowing the key trapped keyboard focus in the card.
     */
    private void keyPressed(KeyEvent event) {
        if (event.isControlDown() || event.isAltDown() || event.isMetaDown()) {
            return;
        }
        if (event.getCode() == KeyCode.ESCAPE && !event.isShiftDown()) {
            if (editorContextMenu.isShowing()) {
                editorContextMenu.hide();
            } else {
                onEscape.run();
            }
            event.consume();
        } else if (event.getCode() == KeyCode.TAB && event.getTarget() == editor) {
            editor.requestFocusTraversal(event.isShiftDown() ? TraversalDirection.PREVIOUS : TraversalDirection.NEXT);
            event.consume();
        }
    }

    void showFile(Path file, Content content, PlacementResolver placementResolver) {
        if (disposed || file == null) {
            return;
        }
        long requested = generation.incrementAndGet();
        path = file.toAbsolutePath().normalize();
        this.placementResolver = placementResolver;
        preferredWidth = DEFAULT_WIDTH;
        preferredHeight = DEFAULT_HEIGHT;
        placed = false;
        placementPending = true;
        userMoved = false;
        userSized = false;
        missing = false;
        loadedStamp = null;
        boolean openBuffer = content != null && content.open();
        fromBuffer = openBuffer;
        editorConfigCharset = content == null ? null : content.editorConfigCharset();
        String name = fileName(path);
        title.setText(name);
        title.setGraphic(FileIcons.forProjectItem(name, false));
        title.setTooltip(new Tooltip(path.toString()));
        setAccessibleText(tr("project.map.preview.accessible", path.toString()));
        // The text area is what a screen reader lands on, and it looks like an editor: name the file it
        // shows and say it cannot be edited, since global chords typed here still act on the active tab.
        editor.setAccessibleText(tr("project.map.preview.editorAccessible", name));
        open.setDisable(false);
        getStyleClass().remove("project-map-preview-missing");
        status.setText(openBuffer ? "" : tr("project.map.preview.loading"));
        shownText = "";
        editor.replaceText("");
        setVisible(true);
        toFront();

        if (openBuffer) {
            String text = capText(content.text(), content.truncated());
            boolean truncated =
                    content.truncated() || text.length() < content.text().length();
            preferredWidth = fittedWidth(text); // known before the card is placed, so it is placed at this size
            ensurePlaced();
            showLoaded(requested, path, new Loaded(text, null, truncated, LoadProblem.NONE, null));
            highlight(requested, path, text, truncated);
            return;
        }
        ensurePlaced();
        loadFromDisk(requested);
    }

    private void loadFromDisk(long requested) {
        Path requestedPath = path;
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
    void refresh(Content content) {
        if (disposed || path == null) {
            return;
        }
        if (content != null && content.open()) {
            String text = capText(content.text(), content.truncated());
            boolean truncated =
                    content.truncated() || text.length() < content.text().length();
            if (fromBuffer && !missing && text.equals(shownText) && frame.getCenter() == editorScroll) {
                return;
            }
            long requested = generation.incrementAndGet();
            loading = false;
            fromBuffer = true;
            loadedStamp = null;
            setMissing(false);
            showText(text, truncated);
            highlight(requested, path, text, truncated);
            return;
        }
        editorConfigCharset = content == null ? editorConfigCharset : content.editorConfigCharset();
        if (fromBuffer) {
            // Its tab was closed: what is on disk is the file again (unsaved text went with the tab).
            fromBuffer = false;
            loadedStamp = null;
            loadFromDisk(generation.incrementAndGet());
            return;
        }
        if (loading || statPending) {
            return;
        }
        long requested = generation.get();
        Path requestedPath = path;
        statPending = true;
        loader.execute(() -> {
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
                    loadFromDisk(generation.incrementAndGet());
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
        path = null;
        shownText = "";
        editor.replaceText("");
        status.setText("");
        setVisible(false);
    }

    Path path() {
        return path;
    }

    CodeArea editor() {
        return editor;
    }

    void setMarkerActions(MarkerActions actions) {
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

    void constrainTo(double parentWidth, double parentHeight) {
        if (!placed || parentWidth <= 0 || parentHeight <= 0) {
            return;
        }
        double width = boundedSize(preferredWidth, MIN_WIDTH, parentWidth - EDGE_MARGIN * 2);
        double height = boundedSize(preferredHeight, MIN_HEIGHT, parentHeight - EDGE_MARGIN * 2);
        resize(width, height);
        relocate(
                clamp(getLayoutX(), EDGE_MARGIN, Math.max(EDGE_MARGIN, parentWidth - width - EDGE_MARGIN)),
                clamp(getLayoutY(), EDGE_MARGIN, Math.max(EDGE_MARGIN, parentHeight - height - EDGE_MARGIN)));
    }

    void dispose() {
        disposed = true;
        generation.incrementAndGet();
        editorContextMenu.hide();
        loader.shutdownNow();
        path = null;
        shownText = "";
        editor.replaceText("");
        status.setText("");
        setVisible(false);
    }

    private void installEditorContextMenu() {
        editorContextMenu.getStyleClass().add("editor-context-menu");
        editor.setOnContextMenuRequested(this::showEditorContextMenu);
        editor.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() == MouseButton.PRIMARY && editorContextMenu.isShowing()) {
                editorContextMenu.hide();
            }
        });
    }

    private void showEditorContextMenu(ContextMenuEvent event) {
        rebuildEditorContextMenu(clickLineAt(event.getX(), event.getY()));
        editorContextMenu.show(editor, event.getScreenX(), event.getScreenY());
        event.consume();
    }

    private void rebuildEditorContextMenu(int clickedLine) {
        boolean empty = editor.getLength() == 0;
        MenuItem copy = new MenuItem(tr("editmenu.copy"), Icons.copy());
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
        MarkerActions actions = markerActions;
        if (!empty && selected != null && actions != null) {
            items.add(new SeparatorMenuItem());
            MenuItem bookmark = new MenuItem(tr("editmenu.addBookmark"), Icons.bookmark());
            bookmark.setOnAction(ignored -> actions.addBookmark(selected, clickedLine));
            items.add(bookmark);

            MenuItem note = new MenuItem(tr("editmenu.addNote"), Icons.notes());
            note.setDisable(!actions.personalNotesEnabled());
            NoteDraft draft = noteDraftAt(clickedLine);
            note.setOnAction(ignored -> actions.addPersonalNote(selected, draft));
            items.add(note);
        }
        editorContextMenu.getItems().setAll(items);
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

    private NoteDraft noteDraftAt(int clickedLine) {
        String document = editor.getText();
        IndexRange selection = editor.getSelection();
        if (selection.getLength() > 0) {
            int start = selection.getStart();
            int end = selection.getEnd();
            var startPosition = editor.offsetToPosition(start, TwoDimensional.Bias.Forward);
            var endPosition = editor.offsetToPosition(end, TwoDimensional.Bias.Forward);
            NoteScope scope = startPosition.getMajor() == endPosition.getMajor() ? NoteScope.WORD : NoteScope.RANGE;
            String prefix = document.substring(Math.max(0, start - TextAnchor.MAX_CONTEXT), start);
            String suffix = document.substring(end, Math.min(document.length(), end + TextAnchor.MAX_CONTEXT));
            TextAnchor anchor = new TextAnchor(
                    startPosition.getMajor(),
                    startPosition.getMinor(),
                    endPosition.getMajor(),
                    endPosition.getMinor(),
                    editor.getSelectedText(),
                    prefix,
                    suffix);
            return new NoteDraft(scope, anchor);
        }

        int line = Math.max(0, Math.min(clickedLine, editor.getParagraphs().size() - 1));
        String lineText = editor.getParagraph(line).getText();
        int lineStart = editor.getAbsolutePosition(line, 0);
        int lineEnd = lineStart + lineText.length();
        String prefix = document.substring(Math.max(0, lineStart - TextAnchor.MAX_CONTEXT), lineStart);
        String suffix = document.substring(lineEnd, Math.min(document.length(), lineEnd + TextAnchor.MAX_CONTEXT));
        TextAnchor anchor = new TextAnchor(line, 0, line, lineText.length(), lineText, prefix, suffix);
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

    private void ensurePlaced() {
        if (disposed) {
            return;
        }
        if (!(getParent() instanceof Region parent) || parent.getWidth() <= 0 || parent.getHeight() <= 0) {
            Platform.runLater(this::ensurePlaced);
            return;
        }
        if (placed) {
            return;
        }
        double width = boundedSize(preferredWidth, MIN_WIDTH, parent.getWidth() - EDGE_MARGIN * 2);
        double height = boundedSize(preferredHeight, MIN_HEIGHT, parent.getHeight() - EDGE_MARGIN * 2);
        placed = true;
        placementPending = false;
        if (placementResolver != null) {
            Placement placement = placementResolver.resolve(width, height, parent.getWidth(), parent.getHeight());
            preferredWidth = boundedSize(placement.width(), MIN_WIDTH, parent.getWidth() - EDGE_MARGIN * 2);
            preferredHeight = boundedSize(placement.height(), MIN_HEIGHT, parent.getHeight() - EDGE_MARGIN * 2);
            growLeft = placement.growLeft();
            resize(preferredWidth, preferredHeight);
            relocate(placement.x(), placement.y());
        } else {
            resize(width, height);
            relocate(Math.max(EDGE_MARGIN, parent.getWidth() - width - 24), 24);
        }
        constrainTo(parent.getWidth(), parent.getHeight());
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

    private void dragged(MouseEvent event) {
        if (!(getParent() instanceof Region parent) || !event.isPrimaryButtonDown()) {
            return;
        }
        double x = dragLayoutX + event.getScreenX() - dragScreenX;
        double y = dragLayoutY + event.getScreenY() - dragScreenY;
        relocate(
                clamp(x, EDGE_MARGIN, Math.max(EDGE_MARGIN, parent.getWidth() - getWidth() - EDGE_MARGIN)),
                clamp(y, EDGE_MARGIN, Math.max(EDGE_MARGIN, parent.getHeight() - getHeight() - EDGE_MARGIN)));
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
        double maxWidth = Math.max(1, parent.getWidth() - getLayoutX() - EDGE_MARGIN);
        double maxHeight = Math.max(1, parent.getHeight() - getLayoutY() - EDGE_MARGIN);
        preferredWidth = boundedSize(resizeWidth + event.getScreenX() - resizeScreenX, MIN_WIDTH, maxWidth);
        preferredHeight = boundedSize(resizeHeight + event.getScreenY() - resizeScreenY, MIN_HEIGHT, maxHeight);
        resize(preferredWidth, preferredHeight);
        requestLayout();
        event.consume();
    }

    private static Loaded load(Path requestedPath, String editorConfigCharset) {
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
            if (looksBinary(bytes)) {
                return new Loaded("", null, false, LoadProblem.BINARY, stamp);
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

    private static Stamp stamp(Path file) {
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
    static String decode(byte[] bytes, boolean cut, String editorConfigCharset) {
        byte[] source = cut ? trimIncompleteTail(bytes, editorConfigCharset) : bytes;
        return LineEndings.toLf(
                EditorConfigCharset.decodeLossless(source, editorConfigCharset).text());
    }

    /** {@code bytes} without a trailing UTF-8 sequence or UTF-16 unit / surrogate pair cut short. */
    static byte[] trimIncompleteTail(byte[] bytes, String editorConfigCharset) {
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

    private void highlight(long requested, Path requestedPath, String text, boolean truncated) {
        if (text.isEmpty() || text.length() > MAX_HIGHLIGHT_CHARS) {
            return;
        }
        submitLatest(() -> {
            StyleSpans<Collection<String>> spans = styles(requestedPath, text);
            Platform.runLater(() -> showStyles(requested, requestedPath, text, truncated, spans));
        });
    }

    /** Keeps only the latest pending preview/highlight so rapid keyboard navigation cannot build a backlog. */
    private void submitLatest(Runnable task) {
        loader.getQueue().clear();
        statPending = false; // a queued stat went with the queue
        loader.execute(task);
    }

    private static StyleSpans<Collection<String>> styles(Path requestedPath, String text) {
        if (text.isEmpty() || text.length() > MAX_HIGHLIGHT_CHARS) {
            return null;
        }
        try {
            IGrammar grammar = GrammarRegistry.shared().forFileName(requestedPath.toString());
            return TextMateHighlighter.compute(text, grammar);
        } catch (RuntimeException | LinkageError ignored) {
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
                frame.setCenter(editorScroll);
                editor.setPlaceholder(new Label(tr("project.map.preview.missing")));
            }
            setMissing(true);
            return false;
        }
        setMissing(false);
        if (loaded.problem() != LoadProblem.NONE) {
            frame.setCenter(editorScroll);
            imageView.setImage(null);
            shownText = "";
            editor.replaceText("");
            status.setText("");
            editor.setPlaceholder(new Label(tr(
                    loaded.problem() == LoadProblem.BINARY
                            ? "project.map.preview.binary"
                            : "project.map.preview.failed")));
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
        boolean first = shownText.isEmpty() && frame.getCenter() == editorScroll && editor.getLength() == 0;
        showText(loaded.text(), loaded.truncated());
        if (first) {
            editor.moveTo(0);
            editor.scrollToPixel(0, 0);
            fitTextWidth(loaded.text());
        }
        return true;
    }

    /**
     * Puts {@code text} in the editor by replacing only the range that differs from what is shown, so a
     * refresh keeps the scroll position, the selection outside the edit and the styles of unchanged lines.
     */
    private void showText(String text, boolean truncated) {
        frame.setCenter(editorScroll);
        imageView.setImage(null);
        editor.setPlaceholder(null);
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
        }
        status.setText(truncated ? tr("project.map.preview.truncated") : "");
    }

    /** Where an offset lands after {@code prefix..oldEnd} was replaced by text {@code shift} chars longer. */
    private static int movedOffset(int offset, int prefix, int oldEnd, int shift) {
        return offset <= prefix ? offset : offset >= oldEnd ? offset + shift : prefix;
    }

    /** The width that shows the text's longest line, up to {@value #MAX_FIT_COLUMNS} columns. */
    private double fittedWidth(String text) {
        int columns = Math.min(MAX_FIT_COLUMNS, widestLineColumns(text));
        Text glyph = new Text("M");
        glyph.setFont(Font.font("Monospaced", 12 * contentZoom));
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
            double right = getLayoutX() + getWidth();
            double x = Math.max(EDGE_MARGIN, right - wanted);
            preferredWidth = right - x;
            resize(preferredWidth, getHeight());
            relocate(x, getLayoutY());
        } else {
            preferredWidth = Math.max(getWidth(), Math.min(wanted, parent.getWidth() - EDGE_MARGIN - getLayoutX()));
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
            long requested, Path requestedPath, String text, boolean truncated, StyleSpans<Collection<String>> spans) {
        if (disposed || requested != generation.get() || !requestedPath.equals(path) || spans == null) {
            return;
        }
        if (!shownText.equals(text) || editor.getLength() != text.length()) {
            return;
        }
        editor.setStyleSpans(0, spans);
        status.setText(truncated ? tr("project.map.preview.truncated") : "");
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
        String name = fileName(path).toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".png")
                || name.endsWith(".jpg")
                || name.endsWith(".jpeg")
                || name.endsWith(".gif")
                || name.endsWith(".bmp");
    }

    private void setContentZoom(double requested) {
        contentZoom = clamp(requested, 0.5, 3.0);
        editor.setStyle("-fx-font-size: " + (12 * contentZoom) + "px;");
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

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
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
