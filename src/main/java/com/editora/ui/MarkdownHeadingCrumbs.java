package com.editora.ui;

import java.util.List;

import javafx.animation.PauseTransition;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.util.Duration;

import com.editora.editor.EditorBuffer;
import com.editora.markdown.MarkdownOutline;
import com.editora.markdown.MarkdownText;
import org.jspecify.annotations.Nullable;
import org.reactfx.Subscription;

/**
 * The breadcrumb's tail for a Markdown document: the headings that enclose the caret, outermost first, each
 * one a link to its line.
 *
 * <p>The headings are parsed off the JavaFX thread on a snapshot (PERF-01, Pauta 6.0.1), the latest ask winning,
 * and land only on the text they were read from; until then the crumbs show what they showed.
 *
 * <p>Nothing here runs on a keystroke. A caret-line change or an edit only restarts a short pause; the
 * headings are re-read when it ends, and only if the text changed since they were last read — moving the
 * caret through an unchanged document walks a list already in hand. Lines are the editor's own, so a
 * narrowed buffer shows the path within what is on screen.
 */
final class MarkdownHeadingCrumbs extends HBox {

    /** Past this the document is not scanned for headings on caret moves; the bar shows the path only. */
    private static final int MAX_CHARS = 2_000_000;

    private static final int MAX_TITLE = 40;

    /** The name the heading parse is heard by, on its own thread. */
    static final String SITE = "MarkdownHeadingCrumbs.headings";

    private final LatestOnly parse =
            new LatestOnly(task -> Thread.ofVirtual().name(SITE).start(task), javafx.application.Platform::runLater);

    private final PauseTransition pause = new PauseTransition(Duration.millis(180));
    private final ChangeListener<Integer> caretMoved = (obs, was, now) -> pause.playFromStart();

    private @Nullable EditorBuffer buffer;
    private @Nullable Subscription edits;
    private List<MarkdownOutline.Heading> headings = List.of();
    private boolean stale = true;
    private @Nullable List<MarkdownOutline.Heading> shown;

    MarkdownHeadingCrumbs() {
        getStyleClass().add("markdown-heading-crumbs");
        setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        pause.setOnFinished(e -> refresh());
        show(List.of());
    }

    /** Follows {@code target}'s caret when it is a Markdown buffer; null (or any other buffer) clears. */
    void attach(@Nullable EditorBuffer target) {
        EditorBuffer next = target != null && target.isMarkdown() ? target : null;
        if (next == buffer) {
            return;
        }
        detach();
        buffer = next;
        stale = true;
        headings = List.of();
        parse.cancel();
        if (buffer == null) {
            show(List.of());
            return;
        }
        buffer.getArea().currentParagraphProperty().addListener(caretMoved);
        edits = buffer.getArea().plainTextChanges().subscribe(change -> {
            stale = true;
            pause.playFromStart();
        });
        refresh();
    }

    private void detach() {
        pause.stop();
        parse.cancel();
        if (buffer != null) {
            buffer.getArea().currentParagraphProperty().removeListener(caretMoved);
        }
        if (edits != null) {
            edits.unsubscribe();
            edits = null;
        }
    }

    /**
     * Brings the crumbs up to date (the pause calls it; tests call it to skip the wait): at once from the headings
     * in hand, or once the text read since they were is parsed.
     */
    void refresh() {
        if (buffer == null) {
            return;
        }
        EditorBuffer target = buffer;
        var area = target.getArea();
        if (stale) {
            if (area.getLength() > MAX_CHARS) {
                headings = List.of();
                stale = false;
            } else {
                long version = target.docVersion();
                String text = target.text();
                parse.submit(
                        () -> {
                            com.editora.perf.BlockingSites.enter(SITE);
                            return MarkdownOutline.headings(text);
                        },
                        found -> {
                            if (buffer != target || target.docVersion() != version) {
                                return; // read from a text that has moved on: the edit's own pause asks again
                            }
                            headings = found;
                            stale = false;
                            show(MarkdownOutline.pathAt(headings, area.getCurrentParagraph()));
                        });
                return;
            }
        }
        show(MarkdownOutline.pathAt(headings, area.getCurrentParagraph()));
    }

    /** Whether a parse of the headings is out (tests wait for it to land). */
    boolean parsing() {
        return parse.pending();
    }

    /** The titles on show, outermost first. */
    List<String> titles() {
        return shown == null
                ? List.of()
                : shown.stream().map(h -> MarkdownText.plain(h.title())).toList();
    }

    private void show(List<MarkdownOutline.Heading> path) {
        if (path.equals(shown)) {
            return;
        }
        shown = path;
        getChildren().clear();
        for (MarkdownOutline.Heading heading : path) {
            Label divider = new Label("›");
            divider.getStyleClass().add("markdown-heading-divider");
            String title = MarkdownText.plain(heading.title());
            Hyperlink link =
                    new Hyperlink(title.length() > MAX_TITLE ? title.substring(0, MAX_TITLE - 1) + "…" : title);
            link.getStyleClass().addAll("file-breadcrumb-crumb", "markdown-heading-crumb");
            link.setFocusTraversable(false);
            EditorBuffer target = buffer;
            link.setOnAction(e -> {
                link.setVisited(false);
                if (target != null) {
                    target.jumpToLine(heading.line());
                }
            });
            getChildren().addAll(divider, link);
        }
        setVisible(!path.isEmpty());
        setManaged(!path.isEmpty());
    }
}
