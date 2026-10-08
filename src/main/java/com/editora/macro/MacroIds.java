package com.editora.macro;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Locale;

/**
 * Builds the stable id of a saved macro from its name. The id is what {@code macro.run.<id>} and therefore a
 * key binding is keyed by, so it must be unique among the saved macros and must not change afterwards. Pure.
 *
 * <p>The id used to be recomputed from the name every time ({@code [^a-z0-9]+ → -}), which collapsed every
 * name without a Latin letter to the one id {@code macro} and made {@code Build} and {@code build} share an
 * id. Now a letter or digit outside ASCII is first transliterated (accents are dropped: {@code café → cafe})
 * and otherwise written as its code point ({@code 日本 → u65e5-u672c}), and a taken id gets a numeric suffix.
 */
public final class MacroIds {

    private MacroIds() {}

    /** What a name with nothing usable in it becomes. */
    static final String FALLBACK = "macro";

    /** The readable, command-id-safe form of {@code name}: {@code [a-z0-9]} runs joined by single hyphens. */
    public static String slug(String name) {
        String s = Normalizer.normalize(name == null ? "" : name.strip(), Normalizer.Form.NFKD);
        StringBuilder out = new StringBuilder(s.length());
        boolean gap = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK) {
                continue; // the accent NFKD split off its letter
            }
            String piece = null;
            if (cp < 0x80) {
                char c = Character.toLowerCase((char) cp);
                if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                    out.append(gap && !out.isEmpty() ? "-" : "").append(c);
                    gap = false;
                    continue;
                }
            } else if (Character.isLetterOrDigit(cp)) {
                piece = "u" + Integer.toHexString(Character.toLowerCase(cp));
            }
            if (piece == null) {
                gap = true;
                continue;
            }
            // A code-point piece is its own word: "u65e5-u672c", never "u65e5u672c" or "abu65e5".
            out.append(out.isEmpty() ? "" : "-").append(piece);
            gap = true;
        }
        return out.isEmpty() ? FALLBACK : out.toString();
    }

    /**
     * The id macros had before ids were stored: the name lower-cased with every run outside {@code [a-z0-9]}
     * turned into a hyphen. Used only to give existing macros the id their key binding already points at.
     */
    public static String legacySlug(String name) {
        String s = (name == null ? "" : name)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return s.isEmpty() ? FALLBACK : s;
    }

    /** {@code base}, or {@code base-2}, {@code base-3}, … — the first that is not in {@code taken}. */
    public static String unique(String base, Collection<String> taken) {
        String b = base == null || base.isBlank() ? FALLBACK : base;
        if (!taken.contains(b)) {
            return b;
        }
        for (int n = 2; ; n++) {
            String candidate = b + "-" + n;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
    }

    /** A fresh id for a macro called {@code name}, distinct from every id in {@code taken}. */
    public static String forName(String name, Collection<String> taken) {
        return unique(slug(name), taken);
    }
}
