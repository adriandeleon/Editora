package com.editora.completion;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javafx.application.Platform;

/**
 * Word-completion candidates drawn from the bundled Hunspell {@code .dic} word lists (the same resources
 * the spell checker uses: {@code resources/com/editora/dictionaries/<id>/index.dic}). The base word list
 * is parsed and sorted <b>once per language, off the FX thread, cached</b> (mirroring
 * {@link com.editora.editor.SpellDictionaries#ensureBuilt}); until a language is loaded, it contributes
 * nothing. Prefix lookup is a case-insensitive binary-search range over the sorted array, so it's
 * {@code O(log n + k)} and never touches the editor's hot paths.
 */
public final class DictionaryWords {

    private static final String BASE = "/com/editora/dictionaries/";
    private static final List<String> AVAILABLE = List.of("en_US", "en_GB", "es", "fr");

    /** langId -> sorted (case-insensitive), de-duplicated base words. */
    private static final Map<String, String[]> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> BUILDING = ConcurrentHashMap.newKeySet();
    /** onReady callbacks waiting for an in-flight build (fired once, on the FX thread, when it lands). */
    private static final Map<String, java.util.List<Runnable>> PENDING = new ConcurrentHashMap<>();
    private static final ExecutorService BUILDER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "completion-dictionary-builder");
        t.setDaemon(true);
        return t;
    });

    private DictionaryWords() {
    }

    public static boolean isAvailable(String langId) {
        return AVAILABLE.contains(langId);
    }

    public static boolean isReady(String langId) {
        return langId != null && CACHE.containsKey(langId);
    }

    /**
     * Ensures {@code langId}'s word list is parsed + sorted (off-thread, once), then runs {@code onReady}
     * on the FX thread. No-op if already loaded/loading or unavailable. {@code onReady} may be null.
     */
    public static void ensureLoaded(String langId, Runnable onReady) {
        if (langId == null || !AVAILABLE.contains(langId)) {
            return;
        }
        if (CACHE.containsKey(langId)) {
            if (onReady != null) {
                Platform.runLater(onReady);
            }
            return;
        }
        if (onReady != null) {
            PENDING.computeIfAbsent(langId, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(onReady);
        }
        if (!BUILDING.add(langId)) {
            return; // a build is already in flight; the callback (if any) was queued above
        }
        BUILDER.execute(() -> {
            try {
                String[] sorted = load(langId);
                if (sorted != null) {
                    CACHE.put(langId, sorted);
                }
            } catch (Exception | LinkageError ignored) {
                // Leave uncached; a later ensureLoaded() can retry. Never crash the app over a dictionary.
            } finally {
                BUILDING.remove(langId);
                java.util.List<Runnable> waiting = PENDING.remove(langId);
                if (waiting != null && CACHE.containsKey(langId)) {
                    Platform.runLater(() -> waiting.forEach(Runnable::run));
                }
            }
        });
    }

    private static String[] load(String langId) throws IOException {
        try (InputStream in = DictionaryWords.class.getResourceAsStream(BASE + langId + "/index.dic")) {
            if (in == null) {
                return null;
            }
            List<String> lines = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    lines.add(line);
                }
            }
            return sortedUnique(parseDic(lines));
        }
    }

    /**
     * Parses a Hunspell {@code .dic} file's lines into base words (pure, for tests): drops the leading
     * count line, strips affix flags after {@code /} and any trailing morphological data, and keeps only
     * "word-like" tokens (≥2 chars, letters with optional intra-word apostrophes/hyphens, no digits).
     */
    public static List<String> parseDic(List<String> lines) {
        List<String> out = new ArrayList<>();
        boolean first = true;
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (first) {
                first = false;
                if (line.chars().allMatch(Character::isDigit)) {
                    continue; // the count header
                }
            }
            // Cut affix flags (word/FLAGS) and trailing morphology (word\tpo:noun ...).
            int slash = line.indexOf('/');
            if (slash >= 0) {
                line = line.substring(0, slash);
            }
            int ws = indexOfWhitespace(line);
            if (ws >= 0) {
                line = line.substring(0, ws);
            }
            if (isWordLike(line)) {
                out.add(line);
            }
        }
        return out;
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isWordLike(String w) {
        if (w.length() < 2) {
            return false;
        }
        boolean hasLetter = false;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (Character.isDigit(c)) {
                return false;
            }
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (c != '\'' && c != '-' && c != '.') {
                return false;
            }
        }
        return hasLetter;
    }

    /** Sorts {@code words} case-insensitively and removes duplicates (case-insensitive). Pure. */
    public static String[] sortedUnique(Collection<String> words) {
        TreeSet<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        set.addAll(words);
        return set.toArray(new String[0]);
    }

    /**
     * Case-insensitive prefix matches in a {@code String[]} sorted by {@link String#CASE_INSENSITIVE_ORDER},
     * via a binary-search range. Returns at most {@code limit} words, excluding an exact match to the
     * prefix itself. Pure.
     */
    public static List<String> matchPrefix(String[] sorted, String prefix, int limit) {
        List<String> out = new ArrayList<>();
        if (sorted.length == 0 || prefix == null || prefix.isEmpty() || limit <= 0) {
            return out;
        }
        int lo = lowerBound(sorted, prefix);
        for (int i = lo; i < sorted.length && out.size() < limit; i++) {
            String w = sorted[i];
            if (!startsWithIgnoreCase(w, prefix)) {
                break; // past the prefix range
            }
            if (!w.equalsIgnoreCase(prefix)) {
                out.add(w);
            }
        }
        return out;
    }

    /** First index whose element is >= prefix under case-insensitive order. */
    private static int lowerBound(String[] sorted, String prefix) {
        int lo = 0;
        int hi = sorted.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (String.CASE_INSENSITIVE_ORDER.compare(sorted[mid], prefix) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static boolean startsWithIgnoreCase(String s, String prefix) {
        return s.length() >= prefix.length() && s.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    /**
     * Completion words for {@code langId} starting with {@code prefix} (case-insensitive): the bundled
     * dictionary (if loaded) plus any matching {@code userWords}, de-duplicated, capped at {@code limit}.
     * Triggers a lazy off-thread load of the dictionary if it isn't ready yet (returning user-word
     * matches meanwhile).
     */
    public static List<String> startingWith(String langId, String prefix, Set<String> userWords, int limit) {
        String[] sorted = langId == null ? null : CACHE.get(langId);
        if (sorted == null) {
            ensureLoaded(langId, null);
        }
        List<String> dict = sorted == null ? List.of() : matchPrefix(sorted, prefix, limit);
        if (userWords == null || userWords.isEmpty()) {
            return dict;
        }
        // Merge user words (also prefix-matched), de-duped case-insensitively, capped.
        TreeSet<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        List<String> out = new ArrayList<>();
        for (String u : userWords) {
            if (out.size() >= limit) {
                break;
            }
            if (u != null && startsWithIgnoreCase(u, prefix) && !u.equalsIgnoreCase(prefix) && seen.add(u)) {
                out.add(u);
            }
        }
        for (String w : dict) {
            if (out.size() >= limit) {
                break;
            }
            if (seen.add(w)) {
                out.add(w);
            }
        }
        return out;
    }

    /** Clears the cache (tests). */
    static void clearCache() {
        CACHE.clear();
        BUILDING.clear();
        PENDING.clear();
    }

    /** Installs a prebuilt list for a language (tests, to avoid resource loading). */
    static void putForTest(String langId, Collection<String> words) {
        CACHE.put(langId, sortedUnique(words));
    }
}
