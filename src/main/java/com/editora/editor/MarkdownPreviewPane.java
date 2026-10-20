package com.editora.editor;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.ObjIntConsumer;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import org.jspecify.annotations.Nullable;
import org.reactfx.Subscription;

import static com.editora.i18n.Messages.tr;

/**
 * A Markdown preview in a tab of its own — beside the editor in another editor group, or anywhere a tab can
 * go — showing the rendering of a <em>source</em> buffer that lives in a different tab.
 *
 * <p>It <b>follows</b>: unless locked, its source is whichever Markdown buffer was active last, so one
 * preview serves every document the user moves through; locked ({@link #setLocked}), it stays on the file it
 * shows. It renders with the same {@link MarkdownPreview} as the in-tab Split view — only changed blocks are
 * rebuilt, 250 ms after the last edit, parsed off the FX thread — and keeps the source's top line at its top
 * while the source's editor scrolls. Task boxes tick the source, a double click on a block goes to its line
 * in the source's tab, links are opened as the source would open them.
 *
 * <p>What it does not have (the in-tab preview does): zoom buttons, Find in the rendering, and a place in the
 * restored session — like the other content tabs it is gone after a restart.
 */
public final class MarkdownPreviewPane implements TabContent {

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "markdown-side-preview");
        t.setDaemon(true);
        return t;
    });

    /** As the in-tab preview: a render 250 ms after the last edit. */
    private static final Duration DEBOUNCE = Duration.millis(250);

    private final BorderPane root = new BorderPane();
    private final ScrollPane scroll = new ScrollPane();
    private final MarkdownPreview preview = new MarkdownPreview();
    private final Label name = new Label();
    private final ToggleButton lock = new ToggleButton();
    private final PauseTransition debounce = new PauseTransition(DEBOUNCE);
    private final ObjIntConsumer<EditorBuffer> reveal;

    private @Nullable EditorBuffer source;
    private @Nullable Subscription edits;
    private final ChangeListener<Object> scrolled = (o, was, now) -> followScroll();
    private final ChangeListener<Object> caretLine = (o, was, now) -> markCaret();
    private String themeMode = "";
    private long generation;
    private boolean followQueued;

    /**
     * @param reveal shows a source buffer's tab with the caret on a line (a block was double-clicked)
     */
    public MarkdownPreviewPane(ObjIntConsumer<EditorBuffer> reveal) {
        this.reveal = reveal;
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("markdown-preview-pane");
        scroll.setFocusTraversable(true);
        preview.attach(scroll);
        scroll.setContent(preview.root());
        preview.onTaskToggle(line -> liveSource() instanceof EditorBuffer s
                && s.isEditable()
                && MarkdownPreview.toggleTask(s.getArea(), line));
        preview.onRerender(this::render);
        preview.onJumpToSource(line -> {
            if (live()) {
                reveal.accept(source, line);
            }
        });

        name.getStyleClass().add("md-side-preview-name");
        lock.getStyleClass().addAll("md-side-preview-lock", "flat");
        lock.setFocusTraversable(false);
        lock.setOnAction(e -> setLocked(lock.isSelected()));
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox header = new HBox(8, name, gap, lock);
        header.getStyleClass().add("md-side-preview-header");
        header.setAlignment(Pos.CENTER_LEFT);
        root.setTop(header);
        root.setCenter(scroll);
        root.getStyleClass().add("md-side-preview");
        debounce.setOnFinished(e -> render());
        setLocked(false);
        showName();
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public String title() {
        return tr("markdown.sidePreview.title");
    }

    /** The buffer whose rendering this shows, or null when it has none (yet, or its tab was closed). */
    public @Nullable EditorBuffer source() {
        return live() ? source : null;
    }

    public boolean isLocked() {
        return lock.isSelected();
    }

    /** Locks the preview to the file it shows (it no longer follows the active tab), or lets it follow again. */
    public void setLocked(boolean locked) {
        lock.setSelected(locked);
        lock.setText(tr(locked ? "markdown.sidePreview.locked" : "markdown.sidePreview.following"));
        String hint = tr(locked ? "markdown.sidePreview.unlockHint" : "markdown.sidePreview.lockHint");
        lock.setTooltip(new Tooltip(hint));
        lock.setAccessibleText(hint);
    }

    /**
     * The active tab changed to {@code buffer} (null: not an editor). An unlocked preview takes a Markdown
     * buffer as its new source; a locked one, or any other kind of tab, leaves it where it is — but a source
     * whose tab has been closed is let go either way.
     */
    public void follow(EditorBuffer buffer) {
        if (source != null && source.isDisposed()) {
            setSource(null);
        }
        if (buffer != null && buffer.isMarkdown() && buffer != source && (!isLocked() || source == null)) {
            setSource(buffer);
        }
    }

    /** Shows {@code buffer}'s rendering, whatever the lock says (the command that opened this preview). */
    public void setSource(@Nullable EditorBuffer buffer) {
        detach();
        source = buffer;
        if (buffer != null) {
            edits = buffer.getArea().plainTextChanges().subscribe(change -> debounce.playFromStart());
            buffer.getArea().estimatedScrollYProperty().addListener(scrolled);
            buffer.getArea().currentParagraphProperty().addListener(caretLine);
        }
        showName();
        preview.clear();
        preview.grantRemote(false); // another document: its remote images are asked about afresh
        render();
    }

    private void detach() {
        debounce.stop();
        if (edits != null) {
            edits.unsubscribe();
            edits = null;
        }
        if (source != null) {
            source.getArea().estimatedScrollYProperty().removeListener(scrolled);
            source.getArea().currentParagraphProperty().removeListener(caretLine);
        }
    }

    /** Lets go of the source and its listeners; call when the tab is closed. */
    public void dispose() {
        detach();
        source = null;
        generation++;
        preview.clear();
    }

    private boolean live() {
        return liveSource() != null;
    }

    /** The source unless there is none or it was disposed. */
    private @Nullable EditorBuffer liveSource() {
        EditorBuffer s = source;
        return s != null && !s.isDisposed() ? s : null;
    }

    private void showName() {
        EditorBuffer from = liveSource();
        name.setText(from != null ? from.getTitle() : tr("markdown.sidePreview.noSource"));
    }

    /** The preview's colour theme: "" follows the app, else "light" or "dark" (as the in-tab preview's). */
    public void applyTheme(String mode) {
        String next = mode == null ? "" : mode;
        if (next.equals(themeMode)) {
            return;
        }
        themeMode = next;
        scroll.getStyleClass().removeAll("md-light", "md-dark");
        if (!next.isEmpty()) {
            scroll.getStyleClass().add("light".equals(next) ? "md-light" : "md-dark");
        }
        render(); // formulas and diagrams are drawn for the theme
    }

    /** Renders the source's current text (parse off the FX thread, changed blocks rebuilt on it). */
    public void render() {
        long gen = ++generation;
        EditorBuffer from = liveSource();
        if (from == null) {
            preview.clear();
            return;
        }
        preview.root().setStyle(PreviewPrefs.fontStyle(1));
        String text = from.getContent();
        Path file = from.getPath();
        Path baseDir = file == null ? null : file.getParent();
        POOL.submit(() -> {
            MarkdownPreview.Plan plan;
            try {
                plan = MarkdownPreview.plan(text, baseDir);
            } catch (RuntimeException | Error e) {
                return; // a parse limit (a table of a million cells): keep what is shown
            }
            Platform.runLater(() -> {
                if (gen != generation || source != from) {
                    return;
                }
                Boolean dark = themeMode.isEmpty() ? null : Boolean.valueOf("dark".equals(themeMode));
                preview.show(plan, from.openUrlHandler, dark, MarkdownPreview.KEEP_VIEW);
                markCaret();
            });
        });
    }

    /** The source's editor scrolled: bring its top line to the top here, once the editor has laid out. */
    private void followScroll() {
        if (!PreviewScrollSync.enabled() || followQueued || !live() || scroll.isHover()) {
            return;
        }
        followQueued = true;
        Platform.runLater(() -> {
            followQueued = false;
            EditorBuffer from = liveSource();
            if (from == null || from.getArea().getScene() == null || !preview.isShowing()) {
                return; // the source's tab is not on screen: nothing to keep in step with
            }
            int top = ScrollAnchor.firstVisibleLine(from.getArea());
            if (top >= 0) {
                preview.releaseAnchor();
                preview.setScrollY(top == 0 ? 0 : preview.yOfLine(top));
            }
        });
    }

    private void markCaret() {
        EditorBuffer from = liveSource();
        preview.markLine(
                from != null && from.getArea().getScene() != null
                        ? from.getArea().getCurrentParagraph()
                        : -1);
    }

    /** The rendered document (tests). */
    MarkdownPreview preview() {
        return preview;
    }
}
