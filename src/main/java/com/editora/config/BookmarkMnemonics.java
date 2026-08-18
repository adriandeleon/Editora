package com.editora.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Assigning and finding the single-character shortcuts that give a bookmark a one-chord jump.
 *
 * <p>A mnemonic is <b>unique within a project</b>: {@code 3} means one place, not one place per file.
 * That is the whole value — the chord is a name for a location, and a name that resolves to several
 * locations is not a shortcut, it is a menu. So assigning a mnemonic that is already in use <em>moves</em>
 * it rather than duplicating it, which is also the only behaviour that needs no error message.
 *
 * <p>Pure and toolkit-free: it operates on the {@code file path → bookmarks} map the store already holds
 * and hands back a new one.
 */
public final class BookmarkMnemonics {

    private BookmarkMnemonics() {}

    /** The characters offered as mnemonics: the ten digits, matching the ten chords that can carry them. */
    public static final String DIGITS = "0123456789";

    /** Normalizes typed input to a usable mnemonic, or {@code ""} when it is not one. */
    public static String normalize(String typed) {
        if (typed == null) {
            return "";
        }
        String t = typed.strip();
        if (t.length() != 1) {
            return "";
        }
        char c = Character.toLowerCase(t.charAt(0));
        // Digits and letters both work as a stored value; only the digits get a bound chord, and a letter
        // is still reachable from the picker, so there is no reason to refuse one.
        return Character.isLetterOrDigit(c) ? String.valueOf(c) : "";
    }

    /**
     * Assigns {@code mnemonic} to the bookmark at {@code file}:{@code line}, returning the updated map.
     *
     * <p>Any previous holder of that mnemonic loses it — see the uniqueness rule above. Passing {@code ""}
     * clears the mnemonic on the target bookmark. A target that does not exist leaves the map untouched
     * rather than inventing a bookmark: creating one is the caller's decision, not a side effect of
     * labelling.
     */
    public static Map<String, List<Bookmark>> assign(
            Map<String, List<Bookmark>> bookmarks, String file, int line, String mnemonic) {
        String m = normalize(mnemonic);
        Map<String, List<Bookmark>> out = new LinkedHashMap<>();
        boolean targetExists = false;
        for (Map.Entry<String, List<Bookmark>> e : bookmarks.entrySet()) {
            List<Bookmark> updated = new ArrayList<>(e.getValue().size());
            for (Bookmark b : e.getValue()) {
                boolean isTarget = e.getKey().equals(file) && b.line() == line;
                if (isTarget) {
                    targetExists = true;
                    updated.add(b.withMnemonic(m));
                } else if (!m.isEmpty() && m.equals(b.mnemonic())) {
                    updated.add(b.withMnemonic("")); // the previous holder gives it up
                } else {
                    updated.add(b);
                }
            }
            out.put(e.getKey(), List.copyOf(updated));
        }
        return targetExists ? Map.copyOf(out) : bookmarks;
    }

    /** Where {@code mnemonic} points, or {@code null} when nothing holds it. */
    public static Located find(Map<String, List<Bookmark>> bookmarks, String mnemonic) {
        String m = normalize(mnemonic);
        if (m.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, List<Bookmark>> e : bookmarks.entrySet()) {
            for (Bookmark b : e.getValue()) {
                if (m.equals(b.mnemonic())) {
                    return new Located(e.getKey(), b);
                }
            }
        }
        return null;
    }

    /** A mnemonic's target: the file path key it lives under, and the bookmark itself. */
    public record Located(String file, Bookmark bookmark) {}

    /** Every assigned mnemonic, in sorted order — for a picker, or to show what is taken. */
    public static List<String> assigned(Map<String, List<Bookmark>> bookmarks) {
        List<String> out = new ArrayList<>();
        for (List<Bookmark> list : bookmarks.values()) {
            for (Bookmark b : list) {
                if (b.hasMnemonic() && !out.contains(b.mnemonic())) {
                    out.add(b.mnemonic());
                }
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(out);
    }

    /** The label a UI shows for a bookmark's mnemonic, e.g. {@code [3]}; {@code ""} when it has none. */
    public static String label(Bookmark b) {
        return b == null || !b.hasMnemonic() ? "" : "[" + b.mnemonic().toUpperCase(Locale.ROOT) + "]";
    }
}
