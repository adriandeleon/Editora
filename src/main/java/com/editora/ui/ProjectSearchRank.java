package com.editora.ui;

/**
 * How well a file name answers a Project filename query, as a tier (lower is better), for ranking the Project
 * tree's filter and the Project Map's search. A fuzzy score over the whole path alone ranked {@code
 * SettingSwitch.java} above {@code Settings.java} for "Settings", and {@code DoctorPaneLayoutFxTest.java} 53
 * places above {@code ProjectPanel.java} for "panel": the name the user typed has to win first.
 *
 * <ol start="0">
 *   <li>{@link #EXACT}: the name, or the name without its extension, is the query ({@code Settings.java})
 *   <li>{@link #PREFIX_WORDS}: the name starts with it as whole words ({@code SettingsPane.java})
 *   <li>{@link #WORDS}: it is one or more whole words elsewhere in the name ({@code ProjectPanel.java})
 *   <li>{@link #PREFIX}: the name starts with it, part-way into a word ({@code SettingSwitch.java})
 *   <li>{@link #WORD_START}: it starts at a word of the name ({@code DoctorPaneLayout} for "panel")
 *   <li>{@link #SUBSTRING}: it is somewhere in the name ({@code ReviewPane} for "view")
 *   <li>{@link #FUZZY}: only a scattered (fuzzy) match
 * </ol>
 *
 * A word starts at the beginning, after a character that is not a letter or digit, at a camel-case hump and
 * where letters and digits meet. A query with whitespace (several terms) is always {@link #FUZZY}. Pure.
 */
final class ProjectSearchRank {

    static final int EXACT = 0;
    static final int PREFIX_WORDS = 1;
    static final int WORDS = 2;
    static final int PREFIX = 3;
    static final int WORD_START = 4;
    static final int SUBSTRING = 5;
    static final int FUZZY = 6;

    private ProjectSearchRank() {}

    static int tier(String name, String query) {
        if (name == null || query == null) {
            return FUZZY;
        }
        String q = query.strip();
        if (q.isEmpty() || q.chars().anyMatch(Character::isWhitespace)) {
            return FUZZY;
        }
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        if (name.equalsIgnoreCase(q) || stem.equalsIgnoreCase(q)) {
            return EXACT;
        }
        int best = FUZZY;
        for (int at = 0; at + q.length() <= name.length(); at++) {
            if (!name.regionMatches(true, at, q, 0, q.length())) {
                continue;
            }
            boolean whole = wordEnd(name, at + q.length());
            int tier;
            if (at == 0) {
                tier = whole ? PREFIX_WORDS : PREFIX;
            } else if (wordStart(name, at)) {
                tier = whole ? WORDS : WORD_START;
            } else {
                tier = SUBSTRING;
            }
            best = Math.min(best, tier);
            if (best == PREFIX_WORDS) {
                break;
            }
        }
        return best;
    }

    /** Whether a word of {@code name} begins at {@code index}. */
    static boolean wordStart(String name, int index) {
        if (index <= 0) {
            return true;
        }
        char previous = name.charAt(index - 1);
        char current = name.charAt(index);
        if (!Character.isLetterOrDigit(previous)) {
            return true;
        }
        if (Character.isUpperCase(current) && Character.isLowerCase(previous)) {
            return true;
        }
        return Character.isDigit(current) != Character.isDigit(previous) && Character.isLetterOrDigit(current);
    }

    /** Whether a word of {@code name} ends just before {@code index} (exclusive). */
    static boolean wordEnd(String name, int index) {
        return index >= name.length() || !Character.isLetterOrDigit(name.charAt(index)) || wordStart(name, index);
    }
}
