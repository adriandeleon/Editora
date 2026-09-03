package com.editora.ui;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
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
    private static final int MAX_MEASURED_COLUMNS = 1_000;

    /** An already-open buffer snapshot. The caller captures it on the FX thread, including unsaved text. */
    record Content(String text, boolean truncated) {
        Content {
            text = text == null ? "" : text;
        }
    }

    record Placement(double x, double y, double width, double height) {}

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
        FAILED
    }

    private record Loaded(String text, byte[] imageBytes, boolean truncated, LoadProblem problem) {}

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
    private final CodeArea editor = new CodeArea();
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
        zoomIn.setTooltip(new Tooltip(tr("project.map.preview.zoomIn")));
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
    }

    void showFile(Path file, Content openContent, PlacementResolver placementResolver) {
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
        title.setText(fileName(path));
        title.setGraphic(FileIcons.forProjectItem(fileName(path), false));
        title.setTooltip(new Tooltip(path.toString()));
        setAccessibleText(tr("project.map.preview.accessible", path.toString()));
        status.setText(openContent == null ? tr("project.map.preview.loading") : "");
        editor.replaceText("");
        setVisible(true);
        toFront();
        ensurePlaced();

        if (openContent != null) {
            String text = cap(openContent.text());
            boolean truncated = openContent.truncated()
                    || text.length() < openContent.text().length();
            showLoaded(requested, path, new Loaded(text, null, truncated, LoadProblem.NONE));
            highlight(requested, path, text, truncated);
            return;
        }

        Path requestedPath = path;
        submitLatest(() -> {
            Loaded loaded = load(requestedPath);
            Platform.runLater(() -> {
                if (showLoaded(requested, requestedPath, loaded)) {
                    highlight(requested, requestedPath, loaded.text(), loaded.truncated());
                }
            });
        });
    }

    void hidePreview() {
        generation.incrementAndGet();
        editorContextMenu.hide();
        path = null;
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
        frame.resizeRelocate(0, 0, getWidth(), getHeight());
        double grip = 18;
        resizeGrip.resizeRelocate(Math.max(0, getWidth() - grip), Math.max(0, getHeight() - grip), grip, grip);
    }

    private void ensurePlaced() {
        if (!(getParent() instanceof Region parent) || parent.getWidth() <= 0 || parent.getHeight() <= 0) {
            Platform.runLater(this::ensurePlaced);
            return;
        }
        double width = boundedSize(preferredWidth, MIN_WIDTH, parent.getWidth() - EDGE_MARGIN * 2);
        double height = boundedSize(preferredHeight, MIN_HEIGHT, parent.getHeight() - EDGE_MARGIN * 2);
        if (placementPending && placementResolver != null) {
            Placement placement = placementResolver.resolve(width, height, parent.getWidth(), parent.getHeight());
            width = boundedSize(placement.width(), MIN_WIDTH, parent.getWidth() - EDGE_MARGIN * 2);
            height = boundedSize(placement.height(), MIN_HEIGHT, parent.getHeight() - EDGE_MARGIN * 2);
            preferredWidth = width;
            preferredHeight = height;
            resize(width, height);
            placed = true;
            placementPending = false;
            relocate(placement.x(), placement.y());
        } else {
            resize(width, height);
            if (!placed) {
                placed = true;
                relocate(Math.max(EDGE_MARGIN, parent.getWidth() - width - 24), 24);
            }
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

    private Loaded load(Path requestedPath) {
        try (InputStream in = Files.newInputStream(requestedPath)) {
            boolean imageFile = isImage(requestedPath);
            int limit = imageFile ? MAX_IMAGE_BYTES : MAX_PREVIEW_BYTES;
            byte[] raw = in.readNBytes(limit + 1);
            boolean byteTruncated = raw.length > limit;
            byte[] bytes = byteTruncated ? Arrays.copyOf(raw, limit) : raw;
            if (imageFile && !byteTruncated) {
                return new Loaded("", bytes, false, LoadProblem.NONE);
            }
            if (looksBinary(bytes)) {
                return new Loaded("", null, false, LoadProblem.BINARY);
            }
            String charset = EditorConfigCharset.resolveName(bytes, null);
            String decoded = EditorConfigCharset.decode(bytes, charset);
            String text = cap(decoded);
            boolean truncated = byteTruncated || text.length() < decoded.length();
            return new Loaded(text, null, truncated, LoadProblem.NONE);
        } catch (IOException | RuntimeException error) {
            return new Loaded("", null, false, LoadProblem.FAILED);
        }
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
        if (loaded.problem() != LoadProblem.NONE) {
            frame.setCenter(editorScroll);
            editor.replaceText("");
            status.setText("");
            editor.setPlaceholder(new Label(tr(
                    loaded.problem() == LoadProblem.BINARY
                            ? "project.map.preview.binary"
                            : "project.map.preview.failed")));
            return false;
        }
        if (loaded.imageBytes() != null) {
            Image image = new Image(new ByteArrayInputStream(loaded.imageBytes()));
            if (image.isError()) {
                editor.setPlaceholder(new Label(tr("project.map.preview.failed")));
                frame.setCenter(editorScroll);
                return false;
            }
            imageView.setImage(image);
            frame.setCenter(imageScroll);
            status.setText(
                    image.getWidth() > 0 ? Math.round(image.getWidth()) + " × " + Math.round(image.getHeight()) : "");
            setContentZoom(1.0);
            return false;
        }
        frame.setCenter(editorScroll);
        imageView.setImage(null);
        editor.setPlaceholder(null);
        editor.replaceText(loaded.text());
        editor.moveTo(0);
        editor.scrollToPixel(0, 0);
        fitTextWidth(loaded.text());
        status.setText(loaded.truncated() ? tr("project.map.preview.truncated") : "");
        return true;
    }

    private void fitTextWidth(String text) {
        int columns = widestLineColumns(text);
        Text glyph = new Text("M");
        glyph.setFont(Font.font("Monospaced", 12 * contentZoom));
        double glyphWidth = Math.max(1, glyph.getLayoutBounds().getWidth());
        preferredWidth = Math.max(DEFAULT_WIDTH, columns * glyphWidth + TEXT_CHROME_WIDTH);
        placementPending = true;
        ensurePlaced();
        requestLayout();
    }

    private static int widestLineColumns(String text) {
        int widest = 0;
        int current = 0;
        for (int offset = 0; offset < text.length() && widest < MAX_MEASURED_COLUMNS; ) {
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
            current = Math.min(current, MAX_MEASURED_COLUMNS);
        }
        return Math.max(widest, current);
    }

    private void showStyles(
            long requested, Path requestedPath, String text, boolean truncated, StyleSpans<Collection<String>> spans) {
        if (disposed || requested != generation.get() || !requestedPath.equals(path) || spans == null) {
            return;
        }
        if (!editor.getText().equals(text)) {
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

    private static String cap(String text) {
        return text.length() <= MAX_PREVIEW_CHARS ? text : text.substring(0, MAX_PREVIEW_CHARS);
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    private static double boundedSize(double requested, double minimum, double available) {
        double maximum = Math.max(1, available);
        return Math.min(Math.max(Math.min(minimum, maximum), requested), maximum);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
