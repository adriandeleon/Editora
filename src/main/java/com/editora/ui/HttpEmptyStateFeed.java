package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.animation.PauseTransition;
import javafx.beans.InvalidationListener;
import javafx.beans.value.ChangeListener;
import javafx.util.Duration;

import com.editora.editor.EditorBuffer;
import com.editora.http.HttpFile;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;
import org.reactfx.Subscription;

/**
 * Feeds a response view's empty state ({@link HttpEmptyState}) from its {@code .http} buffer: the file's requests and
 * the one at the caret. Only while the empty state is on show (and the panel is in a scene): it then follows the caret
 * line and the text, debounced, so typing parses nothing until it pauses, and moving the caret within the text
 * already parsed parses nothing at all. FX thread only.
 */
final class HttpEmptyStateFeed {

    /** How long the caret or the text must rest before the empty state follows it. */
    static final Duration SETTLE = Duration.millis(150);

    private final EditorBuffer buffer;
    private final HttpClientPanel panel;
    private final PauseTransition settle = new PauseTransition(SETTLE);
    private final ChangeListener<Integer> caretMoved = (o, was, now) -> settle.playFromStart();
    private final InvalidationListener gate = o -> follow();
    private @Nullable Subscription textEdits;
    /** The text changed since it was last parsed (or is not known yet). */
    private boolean stale = true;

    private List<HttpFile.Request> requests = List.of();
    private List<HttpEmptyState.Row> rows = List.of();

    private HttpEmptyStateFeed(EditorBuffer buffer, HttpClientPanel panel) {
        this.buffer = buffer;
        this.panel = panel;
        settle.setOnFinished(e -> refresh());
    }

    /** Starts feeding {@code panel}'s empty state from {@code buffer}; stops when the panel is disposed. */
    static HttpEmptyStateFeed attach(EditorBuffer buffer, HttpClientPanel panel) {
        HttpEmptyStateFeed feed = new HttpEmptyStateFeed(buffer, panel);
        panel.emptyStateShowingProperty().addListener(feed.gate);
        panel.sceneProperty().addListener(feed.gate);
        panel.setOnDisposed(feed::dispose);
        feed.follow();
        return feed;
    }

    /** Follows the buffer while the empty state is on show; lets go of it otherwise. */
    private void follow() {
        boolean want = panel.emptyStateShowingProperty().get() && panel.getScene() != null;
        CodeArea area = buffer.getArea();
        if (want && textEdits == null) {
            textEdits = area.plainTextChanges().subscribe(c -> {
                stale = true;
                settle.playFromStart();
            });
            area.currentParagraphProperty().addListener(caretMoved);
            stale = true; // edits made while it was hidden were not seen
            refresh();
        } else if (!want && textEdits != null) {
            stopFollowing(textEdits, area);
        }
    }

    private void stopFollowing(Subscription edits, CodeArea area) {
        edits.unsubscribe();
        textEdits = null;
        area.currentParagraphProperty().removeListener(caretMoved);
        settle.stop();
    }

    private void refresh() {
        CodeArea area = buffer.getArea();
        if (stale) {
            requests = HttpFile.parse(buffer.getContent());
            List<HttpEmptyState.Row> built = new ArrayList<>(requests.size());
            int paragraphs = area.getParagraphs().size();
            for (HttpFile.Request r : requests) {
                int start = r.sectionStart();
                String titleLine = start >= 0 && start < paragraphs
                        ? area.getParagraph(start).getText()
                        : null;
                built.add(new HttpEmptyState.Row(
                        r.startLine(),
                        r.method(),
                        HttpPanelStates.requestLabel(titleLine, r.name(), r.method(), r.url()),
                        r.url()));
            }
            rows = built;
            stale = false;
        }
        panel.showEmptyRequests(rows, indexAt(requests, area.getCurrentParagraph()));
    }

    /** The index of the request whose section holds {@code line}, or -1. */
    static int indexAt(List<HttpFile.Request> requests, int line) {
        for (int i = 0; i < requests.size(); i++) {
            HttpFile.Request r = requests.get(i);
            if (line >= r.sectionStart() && line <= r.sectionEnd()) {
                return i;
            }
        }
        return -1;
    }

    private void dispose() {
        panel.emptyStateShowingProperty().removeListener(gate);
        panel.sceneProperty().removeListener(gate);
        if (textEdits != null) {
            stopFollowing(textEdits, buffer.getArea());
        }
    }
}
