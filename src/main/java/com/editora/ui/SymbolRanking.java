package com.editora.ui;

import java.util.List;

import com.editora.lsp.LspManager.WorkspaceSymbolMatch;
import com.editora.search.FuzzyMatch;

/**
 * How Go to Symbol and Search Everywhere rank a class name (Java re-review EN2). jdtls answers
 * {@code workspace/symbol} with every JDK internal and private nested class that matches, in index order, and
 * the fuzzy score gives a longer camel-hump match more than an exact one; so "ArrayList" listed
 * {@code java.util.Arrays.ArrayList} first and {@code sun.swing.BakedArrayList} at all.
 */
final class SymbolRanking {

    /** Added to a name that is the whole query: {@code Assertions} above {@code AssertionStatusDirectives}. */
    static final int EXACT_NAME_BONUS = 1000;

    /** Taken from a library class nested in another ({@code Arrays.ArrayList}) unless the query has a dot. */
    static final int NESTED_LIBRARY_PENALTY = 2 * EXACT_NAME_BONUS; // below every other match, exact or not

    private SymbolRanking() {}

    /** {@code name}'s score for {@code query}, or {@link FuzzyMatch#NO_SCORE}; pure. */
    static int score(WorkspaceSymbolMatch m, String query) {
        int score = FuzzyMatch.scoreOf(m.name(), query);
        if (score == FuzzyMatch.NO_SCORE) {
            return score;
        }
        return score
                + exactNameBonus(m.name(), query)
                - (nestedLibraryType(m) && !query.contains(".") ? NESTED_LIBRARY_PENALTY : 0);
    }

    /** {@link #EXACT_NAME_BONUS} when {@code name} is the query (case aside), else 0. */
    static int exactNameBonus(String name, String query) {
        return name.equalsIgnoreCase(query.strip()) ? EXACT_NAME_BONUS : 0;
    }

    /** A library class whose container is a class, not a package: the last segment starts upper-case. */
    static boolean nestedLibraryType(WorkspaceSymbolMatch m) {
        if (!m.isLibrary()) {
            return false;
        }
        String c = m.container();
        String last = c.substring(c.lastIndexOf('.') + 1);
        return !last.isEmpty() && Character.isUpperCase(last.charAt(0));
    }

    /**
     * {@code matches} less the library classes the excluded-types setting covers ({@code java.awt.*}, {@code
     * sun.*}), which jdtls filters out of completion but not out of {@code workspace/symbol}. The project's own
     * symbols always stay.
     */
    static List<WorkspaceSymbolMatch> withoutExcluded(List<WorkspaceSymbolMatch> matches, List<String> excluded) {
        if (excluded.isEmpty()) {
            return matches;
        }
        return matches.stream()
                .filter(m -> !m.isLibrary() || !excluded(qualifiedName(m), excluded))
                .toList();
    }

    /** Whether {@code qualified} matches one of jdtls's type-filter patterns ({@code *} matches anything). */
    static boolean excluded(String qualified, List<String> patterns) {
        for (String p : patterns) {
            String pattern = p.strip();
            if (pattern.isEmpty()) {
                continue;
            }
            StringBuilder regex = new StringBuilder();
            for (String part : pattern.split("\\*", -1)) {
                if (!regex.isEmpty()) {
                    regex.append(".*");
                }
                regex.append(java.util.regex.Pattern.quote(part));
            }
            if (qualified.matches(regex.toString())) {
                return true;
            }
        }
        return false;
    }

    private static String qualifiedName(WorkspaceSymbolMatch m) {
        String c = m.container().strip();
        return c.isEmpty() ? m.name() : c + "." + m.name();
    }
}
