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

    /**
     * Taken from a library class nested in another ({@code Arrays.ArrayList}) unless the query has a dot. Half the
     * exact bonus: an exact nested name ({@code Map.Entry} for "Entry") stays above partial matches but below an
     * exact top-level one. Burying every nested type below everything hid {@code Map.Entry} (third review BT3).
     */
    static final int NESTED_LIBRARY_PENALTY = EXACT_NAME_BONUS / 2;

    private SymbolRanking() {}

    /** {@code name}'s score for {@code query}, or {@link FuzzyMatch#NO_SCORE}; pure. */
    static int score(WorkspaceSymbolMatch m, String query) {
        if (query.contains(".")) {
            // "Map.Entry": matched, and exact, against the enclosing type and the name together (BT3)
            String qualified = qualifiedTail(m);
            int score = FuzzyMatch.scoreOf(qualified, query);
            return score == FuzzyMatch.NO_SCORE ? score : score + exactNameBonus(qualified, query);
        }
        int score = FuzzyMatch.scoreOf(m.name(), query);
        if (score == FuzzyMatch.NO_SCORE) {
            return score;
        }
        return score
                + exactNameBonus(m.name(), query)
                - (nestedLibraryType(m) || shadedLibraryType(m) ? NESTED_LIBRARY_PENALTY : 0);
    }

    /**
     * A library class copied into another library's package ({@code org.junit.jupiter.params.shadow.…Entry}): it
     * ranks as a nested type does, not first for "Entry" (fourth review ET4). Still listed: Excluded types decides
     * what is left out (owner decision BT3).
     */
    static boolean shadedLibraryType(WorkspaceSymbolMatch m) {
        String c = m.container();
        return m.isLibrary()
                && (c.contains(".shadow.")
                        || c.contains(".shaded.")
                        || c.contains(".repackaged.")
                        || c.contains(".internal."));
    }

    /**
     * Between equal scores: the JDK's own types first, then the shorter container, so {@code Map.Entry} leads the
     * exact "Entry" matches instead of arriving 11th in index order behind {@code FinalizerHistogram.Entry}
     * (fourth review ET4). Project symbols are ordered before this applies.
     */
    static final java.util.Comparator<WorkspaceSymbolMatch> TIE_BREAK = java.util.Comparator.comparing(
                    (WorkspaceSymbolMatch m) ->
                            !(m.container().startsWith("java.") || m.container().startsWith("javax.")))
            .thenComparingInt(m -> m.container().length());

    /** {@code Map.Entry} for {@code Entry} in {@code java.util.Map}; {@code List} for one in a package. */
    static String qualifiedTail(WorkspaceSymbolMatch m) {
        String c = m.container();
        String last = c.substring(c.lastIndexOf('.') + 1);
        return !last.isEmpty() && Character.isUpperCase(last.charAt(0)) ? last + "." + m.name() : m.name();
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

    private static final java.util.regex.Pattern TYPE_KEYWORD =
            java.util.regex.Pattern.compile("\\b(?:class|interface|enum|record|@interface)\\s+(\\w+)\\b");

    /** The line declaring type {@code name} in Java source, or -1; comment lines are skipped. Pure. */
    static int typeDeclarationLine(String content, String name) {
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].strip();
            if (t.startsWith("*") || t.startsWith("/")) {
                continue;
            }
            var m = TYPE_KEYWORD.matcher(lines[i]);
            while (m.find()) {
                if (m.group(1).equals(name)) {
                    return i;
                }
            }
        }
        return -1;
    }
}
