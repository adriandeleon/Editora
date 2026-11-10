package com.editora.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.git.GitRecentChanges;
import org.jspecify.annotations.Nullable;

/**
 * The project's code metrics, as the Map's Code Metrics card shows them (Map code browser gap 15): each source file's
 * lines of code and of comments and its complexity — its decision points ({@code if}, loops, {@code case}, {@code
 * catch}, {@code &&} and {@code ||}, the conditional operator), counted outside comments and strings, as a cyclomatic
 * complexity less one per function — and, with the last 90 days' commits ({@link GitRecentChanges}), its hotspot score:
 * complex code that changes often, where bugs and work gather. Pure and immutable.
 */
final class ProjectMapMetrics {

    /** How a language writes its comments. */
    enum Comments {
        /** {@code //} and {@code /* … *&#47;}. */
        SLASH,
        /** {@code #} (and Python's docstrings). */
        HASH,
        /** {@code --}. */
        DASH,
        /** {@code ;}. */
        SEMI
    }

    /** A language measured: its name and how it writes comments, and which words branch in it. */
    record Language(String name, Comments comments, Pattern decisions) {}

    /** One file's measures: its language, its lines, those of code and of comments, and its complexity. */
    record Measure(String language, int lines, int code, int comments, int complexity) {}

    /**
     * A row of the card: a file, or a part of a folder (a subfolder, its files summed); how many files it stands for;
     * its lines of code and of comments, its complexity; the commits of the last 90 days that touched it ({@code -1}:
     * no history); and its hotspot score, 0–100.
     */
    record Row(
            String rel, boolean folder, int files, int code, int comments, int complexity, int changes, int hotspot) {}

    /** What a folder holds in all: its files, lines of code and of comments, complexity, and code lines by language. */
    record Totals(int files, int code, int comments, int complexity, Map<String, Integer> languages) {}

    private static final Pattern C_LIKE =
            Pattern.compile("\\b(?:if|for|foreach|while|case|catch)\\b|&&|\\|\\||\\s\\?\\s");
    private static final Pattern PYTHON = Pattern.compile("\\b(?:if|elif|for|while|except|and|or|case)\\b");
    private static final Pattern RUBY =
            Pattern.compile("\\b(?:if|elsif|unless|while|until|for|when|rescue|and|or)\\b|&&|\\|\\||\\s\\?\\s");
    private static final Pattern SHELL = Pattern.compile("\\b(?:if|elif|for|while|until|case)\\b|&&|\\|\\|");
    private static final Pattern LUA = Pattern.compile("\\b(?:if|elseif|for|while|repeat|and|or)\\b");
    private static final Pattern LISP = Pattern.compile("\\((?:if|when|cond|case|loop|for|and|or|if-not|when-not)\\b");
    private static final Pattern STRING = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\\\n]){0,200}'");

    private static final Map<String, Language> LANGUAGES = new HashMap<>();

    private static void language(String name, Comments comments, Pattern decisions, String... extensions) {
        for (String extension : extensions) {
            LANGUAGES.put(extension, new Language(name, comments, decisions));
        }
    }

    static {
        language("Java", Comments.SLASH, C_LIKE, "java");
        language("Kotlin", Comments.SLASH, C_LIKE, "kt", "kts");
        language("Groovy", Comments.SLASH, C_LIKE, "groovy");
        language("Scala", Comments.SLASH, C_LIKE, "scala", "sc");
        language("C#", Comments.SLASH, C_LIKE, "cs");
        language("PHP", Comments.SLASH, C_LIKE, "php");
        language("JavaScript", Comments.SLASH, C_LIKE, "js", "jsx", "mjs", "cjs");
        language("TypeScript", Comments.SLASH, C_LIKE, "ts", "tsx", "mts", "cts");
        language("Vue", Comments.SLASH, C_LIKE, "vue");
        language("Svelte", Comments.SLASH, C_LIKE, "svelte");
        language("Go", Comments.SLASH, C_LIKE, "go");
        language("Rust", Comments.SLASH, C_LIKE, "rs");
        language("C", Comments.SLASH, C_LIKE, "c", "h");
        language("C++", Comments.SLASH, C_LIKE, "cc", "cpp", "cxx", "hpp", "hh", "hxx", "ipp", "inl");
        language("Objective-C", Comments.SLASH, C_LIKE, "m", "mm");
        language("Swift", Comments.SLASH, C_LIKE, "swift");
        language("Dart", Comments.SLASH, C_LIKE, "dart");
        language("Zig", Comments.SLASH, C_LIKE, "zig");
        language("Python", Comments.HASH, PYTHON, "py", "pyi");
        language("Ruby", Comments.HASH, RUBY, "rb");
        language("Shell", Comments.HASH, SHELL, "sh", "bash", "zsh");
        language("Perl", Comments.HASH, RUBY, "pl", "pm");
        language("R", Comments.HASH, C_LIKE, "r");
        language("Elixir", Comments.HASH, RUBY, "ex", "exs");
        language("Lua", Comments.DASH, LUA, "lua");
        language("SQL", Comments.DASH, SHELL, "sql");
        language("Haskell", Comments.DASH, PYTHON, "hs");
        language("Clojure", Comments.SEMI, LISP, "clj", "cljs", "cljc");
    }

    /** The language of a root-relative path, or null for one not measured. */
    static @Nullable Language languageOf(String rel) {
        int dot = rel.lastIndexOf('.');
        if (dot < 0 || dot < rel.lastIndexOf('/')) {
            return null;
        }
        return LANGUAGES.get(rel.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** The measures of the file at {@code rel}, from its {@code text}; null when its language is not measured. */
    static @Nullable Measure measure(String rel, String text) {
        Language language = languageOf(rel);
        if (language == null) {
            return null;
        }
        int lines = 0;
        int code = 0;
        int comments = 0;
        int complexity = 0;
        boolean inBlock = false;
        String blockEnd = "";
        String[] all = text.split("\n", -1);
        int count =
                text.isEmpty() ? 0 : text.endsWith("\n") ? all.length - 1 : all.length; // a last newline starts none
        for (int i = 0; i < count; i++) {
            lines++;
            String line = all[i].strip();
            if (inBlock) {
                int end = line.indexOf(blockEnd);
                if (end < 0) {
                    comments++;
                    continue;
                }
                inBlock = false;
                line = line.substring(end + blockEnd.length()).strip();
                if (line.isEmpty() || isComment(line, language.comments())) {
                    comments++;
                    continue;
                }
            }
            if (line.isEmpty()) {
                continue;
            }
            String opener = blockOpener(line, language.comments());
            if (opener != null) {
                String closer = opener.equals("/*") ? "*/" : opener;
                int end = line.indexOf(closer, opener.length());
                if (end < 0) {
                    inBlock = true;
                    blockEnd = closer;
                }
                String after =
                        end < 0 ? "" : line.substring(end + closer.length()).strip();
                if (after.isEmpty()) {
                    comments++;
                    continue;
                }
                line = after;
            }
            if (isComment(line, language.comments())) {
                comments++;
                continue;
            }
            code++;
            String bare = STRING.matcher(withoutTrailingComment(line, language.comments()))
                    .replaceAll("\"\"");
            Matcher decision = language.decisions().matcher(" " + bare + " ");
            while (decision.find()) {
                complexity++;
            }
            if (language.comments() == Comments.SLASH && bare.contains("/*") && !bare.contains("*/")) {
                inBlock = true; // code, then a block comment that goes on
                blockEnd = "*/";
            }
        }
        return new Measure(language.name(), lines, code, comments, complexity);
    }

    /** The block comment a stripped line starts with ({@code /*}, or a Python docstring's quotes), or null. */
    private static @Nullable String blockOpener(String line, Comments comments) {
        if (comments == Comments.SLASH && line.startsWith("/*")) {
            return "/*";
        }
        if (comments == Comments.HASH) {
            if (line.startsWith("\"\"\"")) {
                return "\"\"\"";
            }
            if (line.startsWith("'''")) {
                return "'''";
            }
        }
        return null;
    }

    private static boolean isComment(String line, Comments comments) {
        return switch (comments) {
            case SLASH -> line.startsWith("//") || line.startsWith("*");
            case HASH -> line.startsWith("#") && !line.startsWith("#[") && !line.startsWith("#{");
            case DASH -> line.startsWith("--");
            case SEMI -> line.startsWith(";");
        };
    }

    /** {@code line} without a comment after its code, as far as a quick look can tell. */
    private static String withoutTrailingComment(String line, Comments comments) {
        String marker = switch (comments) {
            case SLASH -> " //";
            case HASH -> " #";
            case DASH -> " --";
            case SEMI -> " ;";
        };
        int at = line.indexOf(marker);
        return at < 0 ? line : line.substring(0, at);
    }

    // --- the project's ------------------------------------------------------------------------------------------

    static final ProjectMapMetrics EMPTY = new ProjectMapMetrics(Map.of(), false);

    private final NavigableMap<String, Measure> files;
    private final boolean truncated;

    /** {@code files}: each measured file's root-relative path and its measures; {@code truncated}: more were left out. */
    ProjectMapMetrics(Map<String, Measure> files, boolean truncated) {
        this.files = new TreeMap<>(files);
        this.truncated = truncated;
    }

    boolean truncated() {
        return truncated;
    }

    int fileCount() {
        return files.size();
    }

    /** The measures of the file at {@code rel}, or null when it was not measured. */
    @Nullable
    Measure measure(String rel) {
        return files.get(rel);
    }

    private Map<String, Measure> filesIn(String folder) {
        if (folder.isEmpty()) {
            return files;
        }
        String from = folder + "/";
        return files.subMap(from, true, from + Character.MAX_VALUE, false);
    }

    /** What the folder {@code folder} ({@code ""}: the project) holds in all. */
    Totals totals(String folder) {
        int code = 0;
        int comments = 0;
        int complexity = 0;
        Map<String, Integer> languages = new HashMap<>();
        Map<String, Measure> mine = filesIn(folder);
        for (Measure measure : mine.values()) {
            code += measure.code();
            comments += measure.comments();
            complexity += measure.complexity();
            languages.merge(measure.language(), measure.code(), Integer::sum);
        }
        Map<String, Integer> sorted = new LinkedHashMap<>();
        languages.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue()
                        .reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return new Totals(mine.size(), code, comments, complexity, sorted);
    }

    /**
     * The rows of the folder {@code folder}: each file in it, at any depth, or ({@code parts}) each of its subfolders and
     * own files, its files summed; with {@code changes}' commits (none when it has no history) and the hotspot score
     * against the busiest and most complex row. The hottest first — or, with no history, the most complex — then by path.
     */
    List<Row> rows(String folder, boolean parts, GitRecentChanges.Result changes) {
        boolean history = !changes.head().isEmpty() && changes.problem().isEmpty();
        Map<String, int[]> sums = new LinkedHashMap<>(); // files, code, comments, complexity
        Map<String, Boolean> folders = new HashMap<>();
        for (Map.Entry<String, Measure> entry : filesIn(folder).entrySet()) {
            String rel = entry.getKey();
            String key = parts ? ProjectMapDependencies.partOf(rel, folder) : rel;
            folders.put(key, !key.equals(rel));
            int[] sum = sums.computeIfAbsent(key, k -> new int[4]);
            Measure measure = entry.getValue();
            sum[0]++;
            sum[1] += measure.code();
            sum[2] += measure.comments();
            sum[3] += measure.complexity();
        }
        int maxChanges = 0;
        int maxComplexity = 0;
        Map<String, Integer> commits = new HashMap<>();
        for (Map.Entry<String, int[]> entry : sums.entrySet()) {
            GitRecentChanges.Stat stat =
                    history ? changes.stat(entry.getKey(), Boolean.TRUE.equals(folders.get(entry.getKey()))) : null;
            int count = history ? (stat == null ? 0 : stat.commits()) : -1;
            commits.put(entry.getKey(), count);
            maxChanges = Math.max(maxChanges, count);
            maxComplexity = Math.max(maxComplexity, entry.getValue()[3]);
        }
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : sums.entrySet()) {
            int[] sum = entry.getValue();
            int count = commits.getOrDefault(entry.getKey(), -1);
            rows.add(new Row(
                    entry.getKey(),
                    Boolean.TRUE.equals(folders.get(entry.getKey())),
                    sum[0],
                    sum[1],
                    sum[2],
                    sum[3],
                    count,
                    hotspot(sum[3], count, maxComplexity, maxChanges)));
        }
        Comparator<Row> order = history
                ? Comparator.comparingInt(Row::hotspot)
                        .reversed()
                        .thenComparing(Comparator.comparingInt(Row::complexity).reversed())
                : Comparator.comparingInt(Row::complexity).reversed();
        rows.sort(order.thenComparing(Comparator.comparingInt(Row::code).reversed())
                .thenComparing(Row::rel));
        return rows;
    }

    /**
     * How hot a row is, 0–100: its complexity and its commits each against the most of any row, multiplied — complex
     * and often changed. 0 with no history or no change. Pure.
     */
    static int hotspot(int complexity, int changes, int maxComplexity, int maxChanges) {
        if (changes <= 0 || complexity <= 0 || maxChanges <= 0 || maxComplexity <= 0) {
            return 0;
        }
        return (int) Math.round(100.0 * complexity / maxComplexity * changes / maxChanges);
    }

    /** The share of {@code part} in {@code whole}, as a whole percentage. Pure. */
    static int percent(int part, int whole) {
        return whole <= 0 ? 0 : (int) Math.round(100.0 * part / whole);
    }

    /** The languages a project's files are measured in, by name. */
    static Set<String> languageNames() {
        Set<String> names = new java.util.TreeSet<>();
        LANGUAGES.values().forEach(language -> names.add(language.name()));
        return names;
    }
}
