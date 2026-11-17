package com.editora.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.VBox;

import com.editora.perf.BlockingSites;
import org.jspecify.annotations.Nullable;

/**
 * The Settings window's search: what each row can be found by, how well a row matches, which rows, headings and
 * cards a query leaves visible, and which page it opens.
 *
 * <p>A row is found by the text it shows plus its (English) keywords, its card or section heading and its page's
 * name. That text is read from the row's own nodes — never from inside a control's skin (a list's cells, a
 * scroll pane's content, a combo's popup) — so what a search finds does not depend on which pages happened to be
 * opened before it. A list a page holds (the Keymaps shortcut list) is searched through a {@link ListSource}.
 *
 * <p>A hit in a row's title outweighs one in its description, which outweighs shown text, a heading and, last, a
 * keyword; a whole word outweighs the start of a word, which outweighs a hit inside a word. The page with the best row opens, unless the
 * query names a page, or the page already open ties with the best.
 */
final class SettingsSearch {

    /** A searchable row: its page, its node (hidden when filtered out), its keywords, heading and card. */
    record Row(
            Object page,
            Node node,
            String keywords,
            @Nullable Label section,
            @Nullable VBox card) {}

    /** A row's text by where it shows: each part weighs differently in {@link #score}. */
    record Texts(String title, String description, String shown, String headings, String keywords) {
        /** Every part as one string. */
        String all() {
            return String.join(" ", title, description, shown, headings, keywords);
        }
    }

    /**
     * A list inside one row whose items are searched on their own (they are not part of the row's text): when
     * {@code matches} accepts the query, the row holding {@code inside} stays visible and its page counts as a hit.
     */
    record ListSource(Object page, Node inside, java.util.function.Supplier<Predicate<String>> matcher) {

        /** Whether the list holds a match of {@code query}. */
        boolean matches(String query) {
            return matcher.get().test(query);
        }
    }

    /** What a query found: the pages with a hit, the page to open, and whether the open page {@code sel} ties. */
    record Result(Map<Object, Integer> pageScores, @Nullable Object best) {
        Set<Object> pages() {
            return pageScores.keySet();
        }

        /** Whether {@code page} is as good a place to show the results as {@link #best} (so the page stays). */
        boolean ties(Object page) {
            Integer mine = pageScores.get(page);
            Integer top = best == null ? null : pageScores.get(best); // best is one of pageScores' pages
            return mine != null && top != null && mine >= top;
        }
    }

    /** A row whose title or description shows a query word — a visible reason for the hit. */
    static final PseudoClass HIT = PseudoClass.getPseudoClass("search-hit");

    /** Weights per part of a row, see {@link #score}. */
    private static final int TITLE = 8;

    private static final int DESCRIPTION = 4;
    private static final int SHOWN = 3;
    private static final int HEADING = 2;
    private static final int KEYWORD = 1;
    /** A page named by the query wins whatever its rows score. */
    private static final int NAMED_PAGE = 1000;

    /**
     * Words people search for that a setting goes by another name. Only where a setting exists: a synonym for a
     * setting Editora lacks would open an unrelated page. Each key is matched as the whole query or as one word of it.
     */
    private static final Map<String, List<String>> SYNONYMS = Map.ofEntries(
            Map.entry("zoom", List.of("font size")),
            Map.entry("encoding", List.of("charset")),
            Map.entry("line ending", List.of("end of line")),
            Map.entry("line endings", List.of("end of line")),
            Map.entry("crlf", List.of("end of line")),
            Map.entry("hotkey", List.of("shortcut")),
            Map.entry("hotkeys", List.of("shortcuts")),
            Map.entry("keybinding", List.of("shortcut")),
            Map.entry("color scheme", List.of("theme")),
            Map.entry("colour scheme", List.of("theme")),
            Map.entry("soft wrap", List.of("wrap")),
            Map.entry("gutter", List.of("line numbers")));

    private final List<Row> rows;
    private final List<Label> sections;
    private final List<VBox> cards;
    private final List<?> pageOrder;
    private final Function<Object, String> pageName;
    private final Map<Row, Texts> index = new IdentityHashMap<>();
    private @Nullable ListSource list;

    SettingsSearch(
            List<Row> rows,
            List<Label> sections,
            List<VBox> cards,
            List<?> pageOrder,
            Function<Object, String> pageName) {
        this.rows = rows;
        this.sections = sections;
        this.cards = cards;
        this.pageOrder = pageOrder;
        this.pageName = pageName;
    }

    void setListSource(ListSource list) {
        this.list = list;
    }

    // --- pure ----------------------------------------------------------------------------------------------

    /** Whether every word of {@code query} (or of one of its synonyms) occurs in {@code text}. Pure. */
    static boolean matches(String query, String text) {
        if (query == null || query.isBlank()) {
            return true;
        }
        return text != null && score(query, new Texts("", "", text, "", "")) > 0;
    }

    /**
     * How well a row matches: per query word, the best weight of a part holding it (more for a whole word or a word start);
     * 0 when any word is missing. The query's synonyms are tried too and the best wins. Pure.
     */
    static int score(String query, Texts texts) {
        int best = 0;
        for (List<String> words : alternatives(query)) {
            best = Math.max(best, scoreWords(words, texts));
        }
        return best;
    }

    private static int scoreWords(List<String> words, Texts t) {
        int total = 0;
        for (String w : words) {
            int s = Math.max(
                    Math.max(weigh(t.title(), w) * TITLE, weigh(t.description(), w) * DESCRIPTION),
                    Math.max(
                            weigh(t.shown(), w) * SHOWN,
                            Math.max(weigh(t.headings(), w) * HEADING, weigh(t.keywords(), w) * KEYWORD)));
            if (s == 0) {
                return 0;
            }
            total += s;
        }
        return total;
    }

    /** 4 when {@code word} is a whole word of {@code text}, 3 when it starts one, 1 inside one, 0 when absent. */
    static int weigh(String text, String word) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        String hay = text.toLowerCase(Locale.ROOT);
        int found = 0;
        for (int i = hay.indexOf(word); i >= 0; i = hay.indexOf(word, i + 1)) {
            if (i == 0 || !Character.isLetterOrDigit(hay.charAt(i - 1))) {
                int end = i + word.length();
                if (end == hay.length() || !Character.isLetterOrDigit(hay.charAt(end))) {
                    return 4;
                }
                found = 3;
            } else {
                found = Math.max(found, 1);
            }
        }
        return found;
    }

    /** The query's words, then the words of each synonym of the whole query or of one of its words. */
    static List<List<String>> alternatives(String query) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        List<List<String>> out = new ArrayList<>();
        if (q.isEmpty()) {
            return out;
        }
        List<String> words = List.of(q.split(" "));
        out.add(words);
        for (String alt : SYNONYMS.getOrDefault(q, List.of())) {
            out.add(List.of(alt.split(" ")));
        }
        for (int i = 0; i < words.size(); i++) {
            for (String alt : SYNONYMS.getOrDefault(words.get(i), List.of())) {
                List<String> swapped = new ArrayList<>(words.subList(0, i));
                swapped.addAll(List.of(alt.split(" ")));
                swapped.addAll(words.subList(i + 1, words.size()));
                out.add(swapped);
            }
        }
        return out;
    }

    /**
     * Whether {@code query} is (the start of) a page's sidebar name. Three characters at least: a page is not
     * "named" by the first letter of a word that merely happens to begin like it. Pure.
     */
    static boolean namesPage(String query, String pageName) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        return q.length() >= 3
                && pageName != null
                && pageName.toLowerCase(Locale.ROOT).startsWith(q);
    }

    // --- row text --------------------------------------------------------------------------------------------

    /** Everything a row can be found by, as one string: {@code keywords} plus the text {@code shown} nodes show. */
    static String searchText(String keywords, Node... shown) {
        StringBuilder title = new StringBuilder();
        StringBuilder desc = new StringBuilder();
        StringBuilder other = new StringBuilder();
        for (Node n : shown) {
            collect(n, title, desc, other);
        }
        return new Texts(title.toString(), desc.toString(), other.toString(), "", keywords == null ? "" : keywords)
                .all();
    }

    /**
     * Reads a row's text by part. A control's own text (a label, a checkbox, a button, a field's prompt, a
     * combo's choices) is read; what its skin draws is not — that exists only once the page has been shown.
     */
    private static void collect(Node node, StringBuilder title, StringBuilder desc, StringBuilder other) {
        if (node == null) {
            return;
        }
        if (node instanceof Labeled l && l.getText() != null && !l.getText().isEmpty()) {
            StringBuilder to = node.getStyleClass().contains("settings-row-title") || node instanceof CheckBox
                    ? title
                    : node.getStyleClass().contains("settings-row-desc") ? desc : other;
            to.append(' ').append(l.getText());
        }
        if (node instanceof TextInputControl input && input.getPromptText() != null) {
            other.append(' ').append(input.getPromptText());
        }
        if (node instanceof ComboBox<?> combo) {
            appendChoices(combo, other);
        }
        if (node instanceof Parent parent && !(node instanceof Control)) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, title, desc, other);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void appendChoices(ComboBox combo, StringBuilder out) {
        for (Object item : combo.getItems()) {
            out.append(' ')
                    .append(combo.getConverter() != null ? combo.getConverter().toString(item) : item);
        }
    }

    /** A row's text by part, read once (pages are built once; their texts do not change afterwards). */
    Texts textsOf(Row r) {
        return index.computeIfAbsent(r, row -> {
            StringBuilder title = new StringBuilder();
            StringBuilder desc = new StringBuilder();
            StringBuilder other = new StringBuilder();
            collect(row.node(), title, desc, other);
            StringBuilder headings = new StringBuilder(pageName.apply(row.page()));
            if (row.card() != null
                    && !row.card().getChildren().isEmpty()
                    && row.card().getChildren().get(0) instanceof Label cardTitle) {
                headings.append(' ').append(cardTitle.getText());
            }
            if (row.section() != null) {
                headings.append(' ').append(row.section().getText());
            }
            return new Texts(
                    title.toString(),
                    desc.toString(),
                    other.toString(),
                    headings.toString(),
                    row.keywords() == null ? "" : row.keywords());
        });
    }

    // --- filtering ---------------------------------------------------------------------------------------------

    /** The word that, typed in the search, lists only modified rows (as the "Modified" toggle does). */
    static final String MODIFIED_TOKEN = "@modified";

    /** Whether {@code query} asks for modified rows only. Pure. */
    static boolean wantsModified(String query) {
        return query != null
                && List.of(query.strip().toLowerCase(Locale.ROOT).split("\\s+")).contains(MODIFIED_TOKEN);
    }

    /** {@code query} without the {@link #MODIFIED_TOKEN} word. Pure. */
    static String withoutModifiedToken(String query) {
        return query == null
                ? ""
                : query.replaceAll("(?i)(^|\\s)" + MODIFIED_TOKEN + "(?=\\s|$)", " ")
                        .strip();
    }

    private @Nullable Predicate<Row> onlyRows; // a row filter on top of the query ("Show modified only"); null for none

    void setRowFilter(@Nullable Predicate<Row> filter) {
        this.onlyRows = filter;
    }

    /**
     * Shows the rows, headings and cards {@code query} matches and marks the rows whose title or description shows
     * a query word. A blank query shows everything and returns null. Scores in place: a test's; the window scores
     * off the FX thread ({@link #filterLater}).
     */
    @Nullable
    Result filter(String query) {
        Prepared prepared = prepare(query);
        return prepared == null ? showAll() : apply(prepared, prepared.score());
    }

    /** Where a query is scored against the rows and the shortcut list: a picker thread, never the FX thread. */
    static final String FILTER_SITE = "SettingsSearch.filter";

    /** The scoring in flight: a newer keystroke supersedes it, and only the latest answer is shown. */
    private final LatestOnly scoring = PickerSearch.latest();

    /** Whether the rows shown are not yet those for the query typed (a test waits on it). */
    boolean filtering() {
        return scoring.pending();
    }

    /**
     * As {@link #filter}, with the scoring off the FX thread (PERF-01, Pauta 6.0.1): the rows' texts and what the
     * row filter says are taken here, scored on a picker thread, and shown when the latest query's answer lands.
     * {@code done} gets the result (null for a blank query) and whether the shortcut list holds a match.
     */
    void filterLater(String query, java.util.function.BiConsumer<@Nullable Result, Boolean> done) {
        Prepared prepared = prepare(query);
        if (prepared == null) {
            scoring.cancel();
            done.accept(showAll(), false);
            return;
        }
        scoring.submit(prepared::score, scored -> done.accept(apply(prepared, scored), scored.listMatch()));
    }

    private @Nullable Result showAll() {
        rows.forEach(r -> {
            setShown(r.node(), true);
            r.node().pseudoClassStateChanged(HIT, false);
        });
        sections.forEach(s -> setShown(s, true));
        cards.forEach(c -> setShown(c, true));
        return null;
    }

    /**
     * What a query is scored with, taken on the FX thread: each row's texts (read once from its nodes), whether the
     * row filter keeps it and whether it holds the shortcut list, the page names and a copy of the list's rows. Null
     * when nothing is searched for.
     */
    private @Nullable Prepared prepare(String query) {
        boolean searching = query != null && !query.isBlank() || onlyRows != null;
        if (!searching) {
            return null;
        }
        List<Row> rs = List.copyOf(rows);
        List<Texts> texts = new ArrayList<>(rs.size());
        boolean[] kept = new boolean[rs.size()];
        boolean[] holdsList = new boolean[rs.size()];
        ListSource l = list;
        for (int i = 0; i < rs.size(); i++) {
            Row r = rs.get(i);
            texts.add(textsOf(r));
            kept[i] = onlyRows == null || onlyRows.test(r);
            holdsList[i] = l != null && r.page() == l.page() && isUnder(l.inside(), r.node());
        }
        Map<Object, String> names = new LinkedHashMap<>();
        for (Object page : pageOrder) {
            names.put(page, pageName.apply(page));
        }
        Predicate<String> listMatcher = l == null ? q -> false : l.matcher().get();
        return new Prepared(
                query == null ? "" : query, rs, texts, kept, holdsList, names, listMatcher, onlyRows == null);
    }

    /** {@link #prepare}'s copy: scored on any thread. */
    private record Prepared(
            String query,
            List<Row> rows,
            List<Texts> texts,
            boolean[] kept,
            boolean[] holdsList,
            Map<Object, String> pageNames,
            Predicate<String> listMatcher,
            boolean noRowFilter) {

        Scored score() {
            BlockingSites.enter(FILTER_SITE);
            boolean anyWord = !query.isBlank();
            boolean listMatch = anyWord && listMatcher.test(query.strip());
            boolean listHit = listMatch && noRowFilter;
            int[] scores = new int[rows.size()];
            boolean[] hits = new boolean[rows.size()];
            for (int i = 0; i < rows.size(); i++) {
                Texts t = texts.get(i);
                int s = !anyWord ? KEYWORD : SettingsSearch.score(query, t);
                if (!kept[i]) {
                    s = 0;
                }
                if (s == 0 && listHit && holdsList[i]) {
                    s = KEYWORD; // a command name in the list: the weakest hit, a setting named so outranks it
                }
                scores[i] = s;
                hits[i] = s > 0
                        && anyWord
                        && SettingsSearch.score(query, new Texts(t.title(), t.description(), "", "", "")) > 0;
            }
            Map<Object, Integer> pageScores = new LinkedHashMap<>();
            for (int i = 0; i < rows.size(); i++) {
                if (scores[i] > 0) {
                    pageScores.merge(rows.get(i).page(), scores[i], Math::max);
                }
            }
            Object best = null;
            int bestScore = 0;
            for (Map.Entry<Object, String> page : pageNames.entrySet()) {
                Integer s = pageScores.get(page.getKey());
                if (s == null) {
                    continue;
                }
                if (namesPage(query, page.getValue())) {
                    s += NAMED_PAGE;
                    pageScores.put(page.getKey(), s);
                }
                if (best == null || s > bestScore) {
                    best = page.getKey();
                    bestScore = s;
                }
            }
            return new Scored(scores, hits, new Result(pageScores, best), listMatch);
        }
    }

    /** A query's scores per row, whether each shows a hit, the pages it found, and whether the list holds one. */
    private record Scored(int[] scores, boolean[] hits, Result result, boolean listMatch) {}

    /** Shows what {@code scored} found on the rows {@link #prepare} copied: FX thread. */
    private Result apply(Prepared prepared, Scored scored) {
        Set<Label> visibleSections = new HashSet<>();
        Set<VBox> visibleCards = new HashSet<>();
        for (int i = 0; i < prepared.rows().size(); i++) {
            Row r = prepared.rows().get(i);
            boolean shown = scored.scores()[i] > 0;
            setShown(r.node(), shown);
            r.node().pseudoClassStateChanged(HIT, scored.hits()[i]);
            if (shown) {
                if (r.section() != null) {
                    visibleSections.add(r.section());
                }
                if (r.card() != null) {
                    visibleCards.add(r.card());
                }
            }
        }
        sections.forEach(s -> setShown(s, visibleSections.contains(s)));
        cards.forEach(c -> setShown(c, visibleCards.contains(c)));
        return scored.result();
    }

    private static boolean isUnder(Node node, Node ancestor) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == ancestor) {
                return true;
            }
        }
        return false;
    }

    static void setShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }
}
