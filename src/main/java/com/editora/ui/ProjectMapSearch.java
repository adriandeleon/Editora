package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.BooleanSupplier;

import com.editora.search.FuzzyMatch;
import com.editora.search.ProjectFileList;
import org.jspecify.annotations.Nullable;

/**
 * The Project Map's search over a project's file list (Project Map review 3 A3, A5, A7, E2): what a query asks
 * for, which files and folders answer it, and in what order. Pure; runs off the JavaFX thread on every settled
 * keystroke, over a list of every file of the project rather than the first levels of a bounded walk.
 *
 * <h2>What a query asks for</h2>
 *
 * <ul>
 *   <li>A name ({@code kube-scheduler}, {@code types.go}): files and folders whose name matches. A folder
 *       counts only when the query is somewhere in its name as written (not a scattered match), so that a
 *       component's folder is found by its name without every folder that happens to hold the letters.
 *   <li>A path, with a {@code /} ({@code core/v1/types.go}, {@code cmd/kube-scheduler}): the query is matched
 *       against the whole relative path; a trailing {@code /} asks for folders only.
 *   <li>Several words ({@code kubelet types}): every word must match somewhere in the relative path.
 *   <li>{@code #name}: files only, as Search Everywhere's {@code #} scope.
 *   <li>{@code @name}: declarations, as Search Everywhere's {@code @} scope ({@link #isSymbolQuery}); the
 *       caller looks them up in the symbol index and the language servers and ranks them with {@link
 *       #symbolOrder}.
 * </ul>
 *
 * <h2>Order</h2>
 *
 * How the name answers the query first ({@link ProjectSearchRank#tier}: the exact name, a prefix, whole
 * words…); then, among those that answer it as well: the one typed in its own case; a folder before a file
 * when the name is the query or starts with it as whole words; source before tests, fixtures, resources and
 * docs; project code before vendored code; the better fuzzy score of the path (a root file earns the same
 * basename bonus as a nested one, A6); the shallower path; the shorter name (the extension, last); the name.
 */
final class ProjectMapSearch {

    /**
     * At most this many matches are kept (the best of all that matched): enough for every {@code types.go} of a
     * Kubernetes checkout (318) and every {@code OWNERS} (593). The map opens the folders of the first {@link
     * ProjectMapView#SEARCH_OPEN_LIMIT} only; a results list can hold them all.
     */
    static final int MAX_MATCHES = 2_000;

    /** Path segments that hold tests, fixtures, resources or documentation rather than the code itself. */
    private static final Set<String> SECONDARY_SEGMENTS = Set.of(
            "test",
            "tests",
            "testdata",
            "testing",
            "__tests__",
            "fixtures",
            "fixture",
            "resources",
            "docs",
            "doc",
            "spec");

    /** Path segments that hold somebody else's code, vendored or installed. */
    private static final Set<String> VENDOR_SEGMENTS = com.editora.search.DependencyDirs.with(
            "vendor", "third_party", "third-party", "thirdparty", "bower_components");

    private ProjectMapSearch() {}

    /** What a query asks for once its sigil is read: the text to match and where to look. */
    record Query(
            String raw,
            String text,
            boolean symbols,
            boolean filesOnly,
            boolean foldersOnly,
            boolean path,
            boolean contents) {

        Query(String raw, String text, boolean symbols, boolean filesOnly, boolean foldersOnly, boolean path) {
            this(raw, text, symbols, filesOnly, foldersOnly, path, false);
        }

        static Query parse(@Nullable String raw) {
            String q = raw == null ? "" : raw.strip();
            boolean symbols = q.startsWith("@");
            boolean filesOnly = q.startsWith("#");
            boolean contents = q.startsWith(CONTENTS_SIGIL); // the files' text, as VS Code's quick search
            String text = symbols || filesOnly || contents ? q.substring(1).strip() : q;
            if (contents) {
                return new Query(q, text, false, false, false, false, true);
            }
            boolean path = !symbols && text.indexOf('/') >= 0;
            boolean foldersOnly = path && text.endsWith("/") && !filesOnly;
            if (path) {
                // "/cmd/" and "cmd/" ask for the same thing: the root is where every relative path starts.
                while (text.startsWith("/")) {
                    text = text.substring(1);
                }
                while (text.endsWith("/")) {
                    text = text.substring(0, text.length() - 1);
                }
            }
            return new Query(q, text, symbols, filesOnly, foldersOnly, path);
        }

        /**
         * The part a row's own name is matched against, for the map's highlighting of loaded rows. A search of the
         * files' text matches no name: only the files it found stand out (nothing, while it is too short to run).
         */
        String nameText() {
            if (contents) {
                return text.codePointCount(0, text.length()) >= ProjectPanel.MIN_MAP_SEARCH_LENGTH ? NO_NAME : "";
            }
            if (!path) {
                return text;
            }
            int slash = text.lastIndexOf('/');
            return slash < 0 ? text : text.substring(slash + 1);
        }

        boolean blank() {
            return text.isBlank();
        }

        /** The same query asking for files only (Search Everywhere's file group, the tree's filter). */
        Query onlyFiles() {
            return new Query(raw, text, symbols, true, false, path, false);
        }
    }

    /** What a query starts with to search the files' text rather than their names (Map code browser gap 10). */
    static final String CONTENTS_SIGIL = "%";

    /** A name no row has: a text search's row filter, so only the files it found stand out. */
    static final String NO_NAME = "\u0000";

    /** Whether {@code raw} asks for declarations ({@code @name}). */
    static boolean isSymbolQuery(@Nullable String raw) {
        return raw != null && raw.strip().startsWith("@");
    }

    /**
     * The text the map's own row filter should match names against while {@code raw} is the query: the name a
     * path query ends with, a declaration query's name, the query itself otherwise.
     */
    static String nameFilter(@Nullable String raw) {
        return Query.parse(raw).nameText();
    }

    /** One candidate that matched, with what ranks it. */
    private record Hit(
            boolean folder,
            String rel,
            String name,
            int tier,
            boolean exactCase,
            boolean secondary,
            boolean vendor,
            int score,
            int depth) {}

    private static final Comparator<Hit> ORDER = Comparator.comparingInt(Hit::tier)
            .thenComparing(Hit::exactCase, Comparator.reverseOrder())
            .thenComparingInt(hit -> folderRank(hit))
            .thenComparing(Hit::secondary)
            .thenComparing(Hit::vendor)
            .thenComparing(Comparator.comparingInt(Hit::score).reversed())
            .thenComparingInt(Hit::depth)
            .thenComparingInt(hit ->
                    hit.tier() == ProjectSearchRank.FUZZY ? 0 : hit.name().length())
            .thenComparing(Hit::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Hit::rel);

    /** A folder ahead of a file where its name is the query or starts with it as words; behind it elsewhere. */
    private static int folderRank(Hit hit) {
        if (hit.tier() <= ProjectSearchRank.WORDS) {
            return hit.folder() ? 0 : 1;
        }
        return hit.folder() ? 1 : 0;
    }

    /**
     * Searches {@code list} (the files and folders of the project at {@code root}) for {@code raw}: the best
     * {@code max} matches, best first, out of how many matched. {@code null} when {@code cancelled} said so
     * part-way. A declaration query ({@code @}) finds nothing here.
     */
    static @Nullable ProjectMapSearchResult search(
            Path root, ProjectFileList.Snapshot list, String raw, int max, BooleanSupplier cancelled) {
        return search(root, list, Query.parse(raw), max, cancelled);
    }

    /** As above, for a query already read (one narrowed to files by {@link Query#filesOnly}). */
    static @Nullable ProjectMapSearchResult search(
            Path root, ProjectFileList.Snapshot list, Query query, int max, BooleanSupplier cancelled) {
        String raw = query.raw();
        if (query.blank() || query.symbols()) {
            return new ProjectMapSearchResult(raw, List.of(), 0, list.truncated());
        }
        PriorityQueue<Hit> best = new PriorityQueue<>(ORDER.reversed()); // worst first: the one a better evicts
        int[] total = {0};
        if (!query.foldersOnly()) {
            if (!scan(list.files(), false, query, best, total, max, cancelled)) {
                return null;
            }
        }
        if (!query.filesOnly()) {
            if (!scan(list.folders(), true, query, best, total, max, cancelled)) {
                return null;
            }
        }
        List<Hit> ranked = new ArrayList<>(best);
        ranked.sort(ORDER);
        List<ProjectMapSearchResult.Match> matches = new ArrayList<>(ranked.size());
        for (Hit hit : ranked) {
            Path path = root.resolve(hit.rel());
            matches.add(
                    hit.folder()
                            ? ProjectMapSearchResult.Match.folder(path, hit.rel())
                            : ProjectMapSearchResult.Match.file(path, hit.rel()));
        }
        return new ProjectMapSearchResult(raw, matches, total[0], list.truncated());
    }

    private static boolean scan(
            List<String> rels,
            boolean folders,
            Query query,
            PriorityQueue<Hit> best,
            int[] total,
            int max,
            BooleanSupplier cancelled) {
        String text = query.text();
        boolean words = text.chars().anyMatch(Character::isWhitespace);
        boolean wholePath = query.path() || words;
        for (int i = 0; i < rels.size(); i++) {
            if ((i & 0x3FF) == 0 && cancelled.getAsBoolean()) {
                return false;
            }
            String rel = rels.get(i);
            int slash = rel.lastIndexOf('/');
            String name = slash < 0 ? rel : rel.substring(slash + 1);
            Hit hit = wholePath ? pathHit(folders, rel, name, text, words) : nameHit(folders, rel, name, text);
            if (hit == null) {
                continue;
            }
            total[0]++;
            if (best.size() < max) {
                best.add(hit);
            } else if (ORDER.compare(hit, best.peek()) < 0) {
                best.poll();
                best.add(hit);
            }
        }
        return true;
    }

    /** A name query: the candidate's own name must match; a folder's must hold the query as written. */
    private static @Nullable Hit nameHit(boolean folder, String rel, String name, String text) {
        if (FuzzyMatch.scoreOf(name, text) == FuzzyMatch.NO_SCORE) {
            return null; // the cheap rejection: most of the project
        }
        int tier = ProjectSearchRank.tier(name, text);
        if (folder && tier > ProjectSearchRank.SUBSTRING) {
            return null;
        }
        int score = FuzzyMatch.scoreOfPath(rel, text);
        return new Hit(
                folder,
                rel,
                name,
                tier,
                exactCase(name, text),
                secondary(rel, folder),
                vendor(rel),
                score == FuzzyMatch.NO_SCORE ? Integer.MIN_VALUE : score,
                depth(rel));
    }

    /** A path query (or several words): the whole relative path must match. */
    private static @Nullable Hit pathHit(boolean folder, String rel, String name, String text, boolean words) {
        int score = FuzzyMatch.scoreOfPath(rel, text);
        if (score == FuzzyMatch.NO_SCORE) {
            return null;
        }
        int tier = words ? ProjectSearchRank.FUZZY : pathTier(rel, text);
        return new Hit(
                folder, rel, name, tier, exactCase(rel, text), secondary(rel, folder), vendor(rel), score, depth(rel));
    }

    /**
     * How a path answers a path query, as a {@link ProjectSearchRank} tier: it ends with the query as whole
     * segments ({@code EXACT}); holds it as whole segments elsewhere ({@code WORDS}); holds it from the start of
     * a segment ({@code PREFIX}); holds it anywhere ({@code SUBSTRING}); only scattered ({@code FUZZY}).
     */
    static int pathTier(String rel, String query) {
        String r = rel.toLowerCase(Locale.ROOT);
        String q = query.toLowerCase(Locale.ROOT);
        if (q.isEmpty() || r.length() != rel.length() || q.length() != query.length()) {
            return ProjectSearchRank.FUZZY; // case folding changed the length: no index arithmetic on it
        }
        int bestTier = ProjectSearchRank.FUZZY;
        for (int at = r.indexOf(q); at >= 0; at = r.indexOf(q, at + 1)) {
            boolean starts = at == 0 || r.charAt(at - 1) == '/';
            int end = at + q.length();
            boolean ends = end == r.length() || r.charAt(end) == '/';
            int tier;
            if (starts && end == r.length()) {
                tier = ProjectSearchRank.EXACT;
            } else if (starts && ends) {
                tier = ProjectSearchRank.WORDS;
            } else if (starts) {
                tier = ProjectSearchRank.PREFIX;
            } else {
                tier = ProjectSearchRank.SUBSTRING;
            }
            bestTier = Math.min(bestTier, tier);
            if (bestTier == ProjectSearchRank.EXACT) {
                break;
            }
        }
        return bestTier;
    }

    /**
     * Whether {@code name} holds {@code query} in the case it was typed. Smart case: a query with no capital
     * letter says nothing about case, and every name passes.
     */
    static boolean exactCase(String name, String query) {
        if (query.chars().noneMatch(Character::isUpperCase)) {
            return true;
        }
        return name.contains(query);
    }

    /**
     * Whether {@code rel} is a test, fixture, resource or document rather than source: a folder of that kind on
     * its way ({@code src/test}, {@code testdata}, {@code docs}), or a test file's name ({@code *_test.go},
     * {@code FooTest.java}, {@code test_foo.py}, {@code foo.spec.ts}).
     */
    static boolean secondary(String rel, boolean folder) {
        int start = 0;
        String lower = rel.toLowerCase(Locale.ROOT);
        while (start <= lower.length()) {
            int slash = lower.indexOf('/', start);
            int end = slash < 0 ? lower.length() : slash;
            boolean last = slash < 0;
            String segment = lower.substring(start, end);
            if (SECONDARY_SEGMENTS.contains(segment)) {
                return true;
            }
            if (last && !folder && testFileName(rel.substring(start))) {
                return true;
            }
            if (last) {
                break;
            }
            start = end + 1;
        }
        return false;
    }

    private static boolean testFileName(String name) {
        int dot = name.indexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String lower = name.toLowerCase(Locale.ROOT);
        return stem.endsWith("_test")
                || stem.endsWith("Test")
                || stem.endsWith("Tests")
                || lower.startsWith("test_")
                || lower.contains(".test.")
                || lower.contains(".spec.");
    }

    /** Whether {@code rel} lies in vendored or installed code ({@code vendor/}, {@code third_party/}, …). */
    static boolean vendor(String rel) {
        int start = 0;
        while (start < rel.length()) {
            int slash = rel.indexOf('/', start);
            int end = slash < 0 ? rel.length() : slash;
            if (VENDOR_SEGMENTS.contains(rel.substring(start, end).toLowerCase(Locale.ROOT))) {
                return true;
            }
            if (slash < 0) {
                break;
            }
            start = end + 1;
        }
        return false;
    }

    private static int depth(String rel) {
        int depth = 1;
        for (int i = 0; i < rel.length(); i++) {
            if (rel.charAt(i) == '/') {
                depth++;
            }
        }
        return depth;
    }

    /**
     * The order of declaration matches for {@code query} (without its {@code @}): how the name answers it, the
     * one typed in its own case, source before tests and vendored code, then the matcher's own order ({@code
     * rank}: lower first, a language server's answer ahead of the index's), the shallower file, its path, and
     * the line.
     */
    static Comparator<ProjectMapSearchResult.Match> symbolOrder(
            String query, java.util.function.ToIntFunction<ProjectMapSearchResult.Match> rank) {
        return Comparator.<ProjectMapSearchResult.Match>comparingInt(m -> ProjectSearchRank.tier(m.label(), query))
                .thenComparing(m -> exactCase(m.label(), query), Comparator.reverseOrder())
                .thenComparing(m -> secondary(m.relativePath(), false))
                .thenComparing(m -> vendor(m.relativePath()))
                .thenComparingInt(rank)
                .thenComparingInt(m -> depth(m.relativePath()))
                .thenComparing(ProjectMapSearchResult.Match::relativePath)
                .thenComparingInt(ProjectMapSearchResult.Match::line);
    }
}
