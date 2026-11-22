package com.editora.editor.markdown;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
 * Find in the rendered Markdown preview: finds every occurrence of a query in the preview's text, marks the
 * ones on screen, and steps through them.
 *
 * <p>With only the preview on screen the Find bar used to search the hidden source: it counted matches the
 * reader could not see and scrolled an editor that was not there. Here the search runs over what is shown —
 * the text runs of the rendered blocks — with the same matcher and options as the editor's
 * ({@link SearchMatcher}: case, whole word, regex).
 *
 * <p><b>Only the blocks on screen are marked</b> (PERF-08, Pauta 6.2.0). The preview builds only the blocks
 * near the viewport, so the search reads each block's {@linkplain Run runs}: of its node when it is built, else
 * of a rendering without pictures that is kept as text ({@link MarkdownPreview.Entry#runs}). The matches are a
 * list of {@link Hit}s; a block is marked when it enters the scene, and stepping to a match in a block not on
 * screen scrolls there first. When a block on screen reads differently from what was searched (its code was
 * highlighted since), its matches are found again from the node.
 *
 * <p>A match inside a run of text is cut out of it and shown as a highlighted label in its place (a
 * {@code Text} has no background of its own); the run is put back when the search is cleared. A match is found
 * within one run: a phrase that changes style half way (<code>some **bold** text</code>) is not one run.
 * Inline code and a plain code block are single labels and count as one match each, highlighted whole.
 */
public final class PreviewFind {

    private static final String MATCH = "md-find-match";
    private static final String CURRENT = "md-find-current";

    /** One run of a block's text, in reading order: a {@code Text} of a flow, or a whole label. */
    record Run(String text, boolean label) {}

    /** A match: in block {@code block}, run {@code run}, characters {@code from…to}. */
    record Hit(int block, int run, int from, int to) {}

    private final MarkdownPreview preview;
    private final List<Hit> hits = new ArrayList<>();
    /** The label that shows each marked match. */
    private final Map<Hit, Node> shown = new IdentityHashMap<>();
    /** Each marked block's node, and how to put its runs back. */
    private final Map<Node, List<Runnable>> marked = new IdentityHashMap<>();

    private int current = -1;
    private String query = "";
    private boolean matchCase;
    private boolean regex;
    private boolean wholeWord;

    PreviewFind(MarkdownPreview preview) {
        this.preview = preview;
    }

    /** How many matches there are in the document. */
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
     * Finds every match of {@code query}, makes the first one at or below the top of the view current and
     * scrolls to it, then hands {@code found} the number of matches; an empty query or an invalid regex clears
     * the search. The blocks' text is taken on the FX thread and searched off it (PERF-01, Pauta 6.2.0): the
     * marks land a moment later, and only for the latest search.
     */
    public void search(String query, boolean matchCase, boolean regex, boolean wholeWord, IntConsumer found) {
        clear();
        this.query = query == null ? "" : query;
        this.matchCase = matchCase;
        this.regex = regex;
        this.wholeWord = wholeWord;
        collect(() -> {
            markShown();
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
        hits.clear();
        query = "";
        current = -1;
    }

    /** After a re-render: finds the same query again (rebuilt blocks lost their marks) without scrolling. */
    void reapply() {
        if (!active()) {
            return;
        }
        int was = current;
        collect(() -> {
            markShown();
            current = hits.isEmpty() ? -1 : Math.min(Math.max(was, 0), hits.size() - 1);
            setCurrent(true);
        });
    }

    /** Block {@code block} entered the scene: its matches are marked. */
    void attached(int block) {
        if (active() && block >= 0 && block < preview.blockCount()) {
            mark(block);
        }
    }

    // --- finding -----------------------------------------------------------------------------------------

    /** {@link com.editora.perf.BlockingSites} name of the preview's search, made off the FX thread. */
    public static final String SEARCH_SITE = "PreviewFind.search";

    /** The latest search asked: an answer to an older one is dropped. */
    private long asked;

    /**
     * Takes every block's runs on the FX thread (the text of a block not built is cached), finds the matches in
     * them off it, then — if no newer search was asked — makes them the hits and runs {@code then}.
     */
    private void collect(Runnable then) {
        unmark(); // the runs are read clean: a marked block is cut into pieces
        hits.clear();
        long mine = ++asked;
        if (query.isEmpty()) {
            then.run();
            return;
        }
        List<List<Run>> blocks = new ArrayList<>(preview.blockCount());
        for (int b = 0; b < preview.blockCount(); b++) {
            blocks.add(preview.runsOf(b));
        }
        String q = query;
        boolean mc = matchCase;
        boolean rx = regex;
        boolean ww = wholeWord;
        com.editora.perf.OffFx.then(
                SEARCH_SITE,
                () -> {
                    List<Hit> found = new ArrayList<>();
                    for (int b = 0; b < blocks.size(); b++) {
                        found.addAll(find(b, blocks.get(b), q, mc, rx, ww));
                    }
                    return found;
                },
                answer -> {
                    List<Hit> found = answer.value();
                    if (mine != asked || found == null) {
                        return; // a newer search, or a cleared one
                    }
                    unmark();
                    hits.clear();
                    hits.addAll(found);
                    then.run();
                });
    }

    private List<Hit> find(int block, List<Run> runs) {
        return find(block, runs, query, matchCase, regex, wholeWord);
    }

    private static List<Hit> find(
            int block, List<Run> runs, String query, boolean matchCase, boolean regex, boolean wholeWord) {
        List<Hit> out = new ArrayList<>();
        for (int r = 0; r < runs.size(); r++) {
            Run run = runs.get(r);
            if (run.text().isEmpty()) {
                continue;
            }
            List<int[]> found = matches(run.text(), query, matchCase, regex, wholeWord);
            if (run.label()) {
                if (!found.isEmpty()) {
                    out.add(new Hit(block, r, 0, run.text().length()));
                }
            } else {
                for (int[] m : found) {
                    out.add(new Hit(block, r, m[0], m[1]));
                }
            }
        }
        return out;
    }

    /** The matches of a query in {@code text} (off the FX thread too); a zero-width match has nothing to mark. */
    private static List<int[]> matches(String text, String query, boolean matchCase, boolean regex, boolean ww) {
        List<int[]> all = new ArrayList<>(SearchMatcher.matches(text, query, matchCase, regex, ww));
        all.removeIf(m -> m[1] <= m[0]); // a zero-width match (^, $) has nothing to highlight
        return all;
    }

    /** The runs of a block as Find reads them: the order {@link #mark} walks it in. */
    static List<Run> runs(Node block) {
        List<Run> out = new ArrayList<>();
        readRuns(block, out);
        return out;
    }

    private static void readRuns(Node node, List<Run> out) {
        if (!node.isManaged() || !node.isVisible()) {
            return; // a folded <details>: what cannot be seen is not found
        }
        if (node instanceof TextFlow flow) {
            for (Node child : flow.getChildren()) {
                if (child instanceof Text text) {
                    out.add(new Run(text.getText() == null ? "" : text.getText(), false));
                } else {
                    readRuns(child, out);
                }
            }
        } else if (node instanceof Label label) {
            out.add(new Run(label.getText() == null ? "" : label.getText(), true));
        } else if (node instanceof Parent parent && !(node instanceof javafx.scene.control.Control)) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                readRuns(child, out);
            }
        }
    }

    // --- marking -----------------------------------------------------------------------------------------

    /** The first index of block {@code block}'s hits (where they would be), by binary search. */
    private int firstHitOf(int block) {
        int lo = 0;
        int hi = hits.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (hits.get(mid).block() < block) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private void markShown() {
        if (!active()) {
            return;
        }
        for (Node child : List.copyOf(preview.column().getChildrenUnmodifiable())) {
            int b = preview.blockIndexOf(child);
            if (b >= 0) {
                mark(b);
            }
        }
    }

    /** Marks the matches of block {@code block} when it is built and not marked yet. */
    private void mark(int block) {
        Node node = preview.builtNode(block);
        if (node == null || marked.containsKey(node)) {
            return;
        }
        marked.keySet().removeIf(n -> BlockColumn.slotOf(n) == null); // let go of blocks the column dropped
        int from = firstHitOf(block);
        int to = firstHitOf(block + 1);
        List<Hit> live = find(block, runs(node));
        if (!live.equals(hits.subList(from, to))) {
            // Read differently from what was searched (highlighted since): its matches are the node's.
            Hit was = current >= 0 && current < hits.size() ? hits.get(current) : null;
            hits.subList(from, to).clear();
            hits.addAll(from, live);
            to = from + live.size();
            if (was != null) {
                int at = hits.indexOf(was);
                current = at >= 0 ? at : Math.min(from, hits.size() - 1);
            }
        }
        List<Runnable> restore = new ArrayList<>();
        marked.put(node, restore);
        if (from == to) {
            return;
        }
        Iterator<Hit> mine = hits.subList(from, to).iterator();
        int[] run = {0};
        @Nullable Hit[] next = {mine.next()};
        walk(node, run, next, mine, restore);
        if (current >= from && current < to) {
            setCurrent(true);
        }
    }

    private void walk(Node node, int[] run, @Nullable Hit[] next, Iterator<Hit> rest, List<Runnable> restore) {
        if (!node.isManaged() || !node.isVisible()) {
            return;
        }
        if (node instanceof TextFlow flow) {
            for (Node child : List.copyOf(flow.getChildren())) {
                if (child instanceof Text text) {
                    List<Hit> in = take(run[0]++, next, rest);
                    if (!in.isEmpty()) {
                        markText(flow, text, in, restore);
                    }
                } else {
                    walk(child, run, next, rest, restore);
                }
            }
        } else if (node instanceof Label label) {
            List<Hit> in = take(run[0]++, next, rest);
            if (!in.isEmpty() && !label.getStyleClass().contains(MATCH)) {
                label.getStyleClass().add(MATCH);
                shown.put(in.get(0), label);
                restore.add(() -> label.getStyleClass().removeAll(MATCH, CURRENT));
            }
        } else if (node instanceof Parent parent && !(node instanceof javafx.scene.control.Control)) {
            for (Node child : List.copyOf(parent.getChildrenUnmodifiable())) {
                walk(child, run, next, rest, restore);
            }
        }
    }

    /** The hits in run {@code run}, taken off the front of the block's hits. */
    private static List<Hit> take(int run, @Nullable Hit[] next, Iterator<Hit> rest) {
        List<Hit> out = new ArrayList<>();
        for (Hit h = next[0]; h != null && h.run() == run; h = next[0]) {
            out.add(h);
            next[0] = rest.hasNext() ? rest.next() : null;
        }
        return out;
    }

    /** Cuts the matches out of one run of text: {@code [before][match][between][match][after]}. */
    private void markText(TextFlow flow, Text text, List<Hit> found, List<Runnable> restore) {
        String s = text.getText();
        List<Node> pieces = new ArrayList<>();
        int pos = 0;
        for (Hit m : found) {
            int a = Math.min(m.from(), s.length());
            int b = Math.min(m.to(), s.length());
            if (a > pos) {
                pieces.add(like(text, new Text(s.substring(pos, a))));
            }
            Label hit = new Label(s.substring(a, b));
            like(text, hit).getStyleClass().add(MATCH);
            pieces.add(hit);
            shown.put(m, hit);
            pos = b;
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

    private void unmark() {
        for (List<Runnable> restore : marked.values()) {
            for (int i = restore.size() - 1; i >= 0; i--) {
                restore.get(i).run();
            }
        }
        marked.clear();
        shown.clear();
    }

    // --- the current match -------------------------------------------------------------------------------

    private void show() {
        if (current < 0 || current >= hits.size()) {
            return;
        }
        Hit hit = hits.get(current);
        Node node = shown.get(hit);
        if (node == null || node.getScene() == null) {
            preview.revealBlock(hit.block()); // in a block not on screen: scroll there, which marks it
            hit = current >= 0 && current < hits.size() ? hits.get(current) : hit;
            node = shown.get(hit);
        }
        setCurrent(true);
        if (node != null) {
            preview.reveal(node);
        }
    }

    private void setCurrent(boolean on) {
        if (current >= 0 && current < hits.size()) {
            Node node = shown.get(hits.get(current));
            if (node != null) {
                ObservableList<String> classes = node.getStyleClass();
                classes.remove(CURRENT);
                if (on) {
                    classes.add(CURRENT);
                }
            }
        }
    }

    private int firstAtOrBelow(double y) {
        for (int i = 0; i < hits.size(); i++) {
            Hit hit = hits.get(i);
            Node node = shown.get(hit);
            double at =
                    node != null && node.getScene() != null ? preview.offsetOf(node) : preview.blockTop(hit.block());
            if (at >= y) {
                return i;
            }
        }
        return 0;
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
