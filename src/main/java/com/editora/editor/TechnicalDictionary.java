package com.editora.editor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The bundled technical-terms dictionary: common programming / web / data / devops words that standard
 * prose dictionaries flag as misspellings (e.g. {@code config}, {@code async}, {@code middleware},
 * {@code kubernetes}). Treated by {@link SpellChecker} as correctly-spelled, like the user's personal
 * dictionary, but shipped with the app and gated by its own Settings toggle (default on).
 *
 * <p>The word list lives in {@code resources/com/editora/dictionaries/technical.txt} (one lowercase term
 * per line; {@code #} comments and blank lines ignored) and is read once — lazily, off the build — through
 * the module's own classloader (same idiom as the keymap/snippet resources, so no {@code module-info}
 * {@code opens} is needed). The parsed set is immutable and cached for the process lifetime.
 */
public final class TechnicalDictionary {

    private static final String RESOURCE = "/com/editora/dictionaries/technical.txt";

    private static volatile Set<String> words; // lazily loaded, immutable once set

    private TechnicalDictionary() {}

    /** The technical terms (lowercased), loaded once and cached. Never null; empty if the resource is missing. */
    public static Set<String> words() {
        Set<String> w = words;
        if (w == null) {
            synchronized (TechnicalDictionary.class) {
                w = words;
                if (w == null) {
                    w = load();
                    words = w;
                }
            }
        }
        return w;
    }

    /** Whether {@code lowerWord} (already lowercased by the caller) is a known technical term. */
    public static boolean contains(String lowerWord) {
        return lowerWord != null && words().contains(lowerWord);
    }

    private static Set<String> load() {
        Set<String> set = new HashSet<>();
        try (InputStream in = TechnicalDictionary.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return Set.of();
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String t = line.strip();
                    if (t.isEmpty() || t.startsWith("#")) {
                        continue;
                    }
                    set.add(t.toLowerCase(Locale.ROOT));
                }
            }
        } catch (IOException e) {
            return Set.copyOf(set); // partial set is fine; never throw from the spell hot path
        }
        return Set.copyOf(set);
    }
}
