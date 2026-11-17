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
 * <p>Nothing here runs on a keystroke. A caret-line change or an edit only restarts a short pause; the
 * headings are re-read when it ends, and only if the text changed since they were last read — moving the
 * caret through an unchanged document walks a list already in hand. The re-read takes the buffer's shared text
 * and scans it off the FX thread (PERF-08, Pauta 6.0.1); an answer for text edited since is dropped. Lines are
 * the editor's own, so a narrowed buffer shows the path within what is on screen.
 */
final class MarkdownHeadingCrumbs extends HBox {

    /** Past this the document is not scanned for headings on caret moves; the bar shows the path only. */
    private static final int MAX_CHARS = 2_000_000;

    private static final int MAX_TITLE = 40;

    private final PauseTransition pause = new PauseTransition(Duration.millis(180));
    private final ChangeListener<Integer> caretMoved = (obs, was, now) -> pause.playFromStart();

    private @Nullable EditorBuffer buffer;
    private @Nullable Subscription edits;
    private List<MarkdownOutline.Heading> headings = List.of();
    private boolean stale = true;
    /** Bumped at each edit and attach: a scan of an older text that lands later is dropped. */
    private long editCount;
    /** A scan is running for {@link #editCount}: a pause before it lands starts no second one. */
    private boolean scanning;

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
        if (buffer == null) {
            show(List.of());
            return;
        }
        buffer.getArea().currentParagraphProperty().addListener(caretMoved);
        editCount++;
        scanning = false;
        edits = buffer.getArea().plainTextChanges().subscribe(change -> {
            stale = true;
            editCount++;
            scanning = false;
            pause.playFromStart();
        });
        refresh();
    }

    private void detach() {
        pause.stop();
        if (buffer != null) {
            buffer.getArea().currentParagraphProperty().removeListener(caretMoved);
        }
        if (edits != null) {
            edits.unsubscribe();
            edits = null;
        }
    }

    /** Brings the crumbs up to date now (the pause calls it; tests call it to skip the wait). */
    void refresh() {
        if (buffer == null) {
            return;
        }
        var area = buffer.getArea();
        if (stale && area.getLength() > MAX_CHARS) {
            headings = List.of();
            stale = false;
        }
        if (!stale) {
            show(MarkdownOutline.pathAt(headings, area.getCurrentParagraph()));
            return;
        }
        if (scanning) {
            return; // the scan of this text shows the path when it lands
        }
        scanning = true;
        EditorBuffer scanned = buffer;
        long asked = editCount;
        String text = scanned.text(); // the buffer's shared snapshot, not a copy of its own
        com.editora.perf.OffFx.then("markdown-heading-crumbs", () -> MarkdownOutline.headings(text), found -> {
            List<MarkdownOutline.Heading> list = found.value();
            if (asked != editCount || buffer != scanned) {
                return; // edited since (its pause asks again), or another buffer is followed
            }
            scanning = false;
            if (list == null) {
                return;
            }
            headings = list;
            stale = false;
            show(MarkdownOutline.pathAt(headings, scanned.getArea().getCurrentParagraph()));
        });
    }

    /** Whether a scan of the text is running (tests wait for it). */
    boolean scanning() {
        return scanning;
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
