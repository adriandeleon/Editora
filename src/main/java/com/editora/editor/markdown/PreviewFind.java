package com.editora.editor.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import com.editora.text.SearchMatcher;
import org.jspecify.annotations.Nullable;

/**
 * Find in the rendered Markdown preview: marks every occurrence of a query in the preview's text, and steps
 * through them.
 *
 * <p>With only the preview on screen the Find bar used to search the hidden source: it counted matches the
 * reader could not see and scrolled an editor that was not there. Here the search runs over what is shown —
 * the text runs of the rendered blocks — with the same matcher and options as the editor's
 * ({@link SearchMatcher}: case, whole word, regex).
 *
 * <p>A match inside a run of text is cut out of it and shown as a highlighted label in its place (a
 * {@code Text} has no background of its own); the run is put back when the search is cleared. A match is found
 * within one run: a phrase that changes style half way (<code>some **bold** text</code>) is not one run.
 * Inline code and a plain code block are single labels and count as one match each, highlighted whole.
 */
public final class PreviewFind {

    private static final String MATCH = "md-find-match";
    private static final String CURRENT = "md-find-current";

    private final MarkdownPreview preview;
    private final List<Node> hits = new ArrayList<>();
    private final List<Runnable> restore = new ArrayList<>();
    private int current = -1;
    private String query = "";
    private boolean matchCase;
    private boolean regex;
    private boolean wholeWord;

    PreviewFind(MarkdownPreview preview) {
        this.preview = preview;
    }

    /** How many matches are marked. */
    public int count() {
        return hits.size();
    }

    /** The 0-based index of the current match, or -1 when there is none. */
    public int current() {
        return current;
    }

    /** Whether a search is in force (its marks are re-applied to blocks a re-render rebuilds). */
    boolean active() {
        return !query.isEmpty();
    }

    /**
     * Marks every match of {@code query}, makes the first one at or below the top of the view current and
     * scrolls to it, then hands {@code found} the number of matches; an empty query or an invalid regex clears the
     * search. The preview's text is taken on the FX thread and searched off it (PERF-01, Pauta 6.0.1): the marks
     * land a moment later, and only for the latest search and the preview as it was taken.
     */
    public void search(String query, boolean matchCase, boolean regex, boolean wholeWord, IntConsumer found) {
        clear();
        this.query = query == null ? "" : query;
        this.matchCase = matchCase;
        this.regex = regex;
        this.wholeWord = wholeWord;
        mark(() -> {
            current = hits.isEmpty() ? -1 : firstAtOrBelow(preview.scrollY());
            show();
            found.accept(hits.size());
        });
    }

    /** Makes the next (or previous) match current, wrapping around, and scrolls to it. Returns its index. */
    public int step(boolean forward) {
        if (hits.isEmpty()) {
            return -1;
        }
        setCurrent(false);
        current = Math.floorMod(current + (forward ? 1 : -1), hits.size());
        show();
        return current;
    }

    /** Removes every mark and forgets the query. */
    public void clear() {
        asked++; // a search still out is dropped
        unmark();
        query = "";
        current = -1;
    }

    /** After a re-render: marks the same query again (rebuilt blocks lost theirs) without scrolling. */
    void reapply() {
        if (!active()) {
            return;
        }
        int was = current;
        unmark();
        mark(() -> {
            current = hits.isEmpty() ? -1 : Math.min(Math.max(was, 0), hits.size() - 1);
            setCurrent(true);
        });
    }

    private void unmark() {
        for (int i = restore.size() - 1; i >= 0; i--) {
            restore.get(i).run();
        }
        restore.clear();
        hits.clear();
    }

    /** {@link com.editora.perf.BlockingSites} name of the preview's search, made off the FX thread. */
    public static final String SEARCH_SITE = "PreviewFind.search";

    /** The latest search asked: an answer to an older one is dropped. */
    private long asked;

    /** A run of the preview's text as taken on the FX thread: a label, or a {@code Text} in its flow. */
    private record Run(Node node, @Nullable TextFlow flow, String text) {}

    /**
     * Takes the text runs on the FX thread, finds the matches in them off it, then marks them and runs {@code then}
     * — if no newer search was asked and each run is still where it was, with the same text.
     */
    private void mark(Runnable then) {
        if (query.isEmpty()) {
            then.run();
            return;
        }
        List<Run> runs = new ArrayList<>();
        walk(preview.column(), runs);
        long mine = ++asked;
        String q = query;
        boolean mc = matchCase;
        boolean rx = regex;
        boolean ww = wholeWord;
        com.editora.perf.OffFx.then(
                SEARCH_SITE,
                () -> {
                    List<List<int[]>> found = new ArrayList<>();
                    for (Run run : runs) {
                        found.add(matches(run.text(), q, mc, rx, ww));
                    }
                    return found;
                },
                answer -> {
                    List<List<int[]>> found = answer.value();
                    if (mine != asked || found == null) {
                        return;
                    }
                    for (int i = 0; i < runs.size(); i++) {
                        Run run = runs.get(i);
                        List<int[]> at = found.get(i);
                        if (at.isEmpty()) {
                            continue;
                        }
                        TextFlow flow = run.flow();
                        if (flow == null && run.node() instanceof Label label) {
                            if (run.text().equals(label.getText())
                                    && !label.getStyleClass().contains(MATCH)) {
                                label.getStyleClass().add(MATCH);
                                hits.add(label);
                                restore.add(() -> label.getStyleClass().removeAll(MATCH, CURRENT));
                            }
                        } else if (flow != null
                                && run.node() instanceof Text text
                                && run.text().equals(text.getText())
                                && flow.getChildren().contains(text)) {
                            markText(flow, text, at);
                        }
                    }
                    then.run();
                });
    }

    private void show() {
        setCurrent(true);
        if (current >= 0) {
            preview.reveal(hits.get(current));
        }
    }

    private void setCurrent(boolean on) {
        if (current >= 0 && current < hits.size()) {
            ObservableList<String> classes = hits.get(current).getStyleClass();
            classes.remove(CURRENT);
            if (on) {
                classes.add(CURRENT);
            }
        }
    }

    private int firstAtOrBelow(double y) {
        for (int i = 0; i < hits.size(); i++) {
            if (preview.offsetOf(hits.get(i)) >= y) {
                return i;
            }
        }
        return 0;
    }

    /** Collects the text runs under {@code node} that can be seen. FX thread; no matching here. */
    private void walk(Node node, List<Run> runs) {
        if (!node.isManaged() || !node.isVisible()) {
            return; // a folded <details>: what cannot be seen is not found
        }
        if (node instanceof TextFlow flow) {
            for (Node child : List.copyOf(flow.getChildren())) {
                if (child instanceof Text text) {
                    String s = text.getText();
                    if (s != null && !s.isEmpty()) {
                        runs.add(new Run(text, flow, s));
                    }
                } else {
                    walk(child, runs);
                }
            }
        } else if (node instanceof Label label) {
            String text = label.getText();
            if (text != null) {
                runs.add(new Run(label, null, text));
            }
        } else if (node instanceof Parent parent && !(node instanceof javafx.scene.control.Control)) {
            for (Node child : List.copyOf(parent.getChildrenUnmodifiable())) {
                walk(child, runs);
            }
        }
    }

    /** The matches of a query in {@code text}, off the FX thread; a zero-width match has nothing to highlight. */
    private static List<int[]> matches(String text, String query, boolean matchCase, boolean regex, boolean ww) {
        List<int[]> all = new ArrayList<>(SearchMatcher.matches(text, query, matchCase, regex, ww));
        all.removeIf(m -> m[1] <= m[0]);
        return all;
    }

    /** Cuts the matches out of one run of text: {@code [before][match][between][match][after]}. */
    private void markText(TextFlow flow, Text text, List<int[]> found) {
        String s = text.getText();
        List<Node> pieces = new ArrayList<>();
        int pos = 0;
        for (int[] m : found) {
            if (m[0] > pos) {
                pieces.add(like(text, new Text(s.substring(pos, m[0]))));
            }
            Label hit = new Label(s.substring(m[0], m[1]));
            like(text, hit).getStyleClass().add(MATCH);
            pieces.add(hit);
            hits.add(hit);
            pos = m[1];
        }
        if (pos < s.length()) {
            pieces.add(like(text, new Text(s.substring(pos))));
        }
        ObservableList<Node> children = flow.getChildren();
        int at = children.indexOf(text);
        children.remove(at);
        children.addAll(at, pieces);
        restore.add(() -> {
            int first = children.indexOf(pieces.get(0));
            if (first >= 0) { // (a block rebuilt meanwhile took its pieces with it)
                children.removeAll(pieces);
                children.add(Math.min(first, children.size()), text);
            }
        });
    }

    /** Gives {@code piece} the look and the behaviour (a link's click, its menu entry) of the run it replaces. */
    private static <T extends Node> T like(Text original, T piece) {
        piece.getStyleClass().addAll(original.getStyleClass());
        piece.setCursor(original.getCursor());
        piece.setOnMouseClicked(original.getOnMouseClicked());
        if (original.hasProperties()) {
            piece.getProperties().putAll(original.getProperties());
        }
        return piece;
    }
}
