package com.editora.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.editora.search.FuzzyMatch.Match;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ranking <em>is</em> the product here, so most of this asserts which candidate wins rather than
 * merely that both matched — a matcher that returns the right set in the wrong order is the bug this
 * class exists to prevent. Absolute scores are deliberately never asserted: they are relative, and
 * pinning them would make every future tuning change a test rewrite.
 */
class FuzzyMatchTest {

    /** The candidates that match {@code query}, best first — the picker's eye view. */
    private static List<String> rank(String query, String... candidates) {
        record Scored(String name, int score) {}
        List<Scored> scored = new ArrayList<>();
        for (String c : candidates) {
            Match m = FuzzyMatch.of(c, query);
            if (m != null) {
                scored.add(new Scored(c, m.score()));
            }
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed());
        return scored.stream().map(Scored::name).toList();
    }

    /** Asserts {@code winner} outranks {@code loser} for {@code query} (both must match). */
    private static void beats(String query, String winner, String loser) {
        Match w = FuzzyMatch.of(winner, query);
        Match l = FuzzyMatch.of(loser, query);
        assertNotNull(w, () -> "'" + query + "' should match " + winner);
        assertNotNull(l, () -> "'" + query + "' should match " + loser);
        assertTrue(
                w.score() > l.score(),
                () -> "'" + query + "': expected " + winner + " (" + w.score() + ") to outrank " + loser + " ("
                        + l.score() + ")");
    }

    /** Flattens ranges into the substring they cover, so an expectation reads as the emboldened text. */
    private static String matched(String candidate, String query) {
        Match m = FuzzyMatch.of(candidate, query);
        if (m == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int[] r : m.ranges()) {
            if (!sb.isEmpty()) {
                sb.append('|');
            }
            sb.append(candidate, r[0], r[1]);
        }
        return sb.toString();
    }

    @Nested
    @DisplayName("matching at all")
    class Matching {

        @Test
        void matchesAScatteredSubsequence() {
            assertNotNull(FuzzyMatch.of("MainController", "mcon"));
        }

        @Test
        void rejectsCharactersNotPresent() {
            assertNull(FuzzyMatch.of("MainController", "xyz"));
        }

        @Test
        void rejectsCharactersPresentButOutOfOrder() {
            assertNull(FuzzyMatch.of("MainController", "rniam")); // all present, wrong order
        }

        @Test
        void rejectsAQueryLongerThanTheCandidate() {
            assertNull(FuzzyMatch.of("ab", "abcdef"));
        }

        @Test
        void blankQueryIsNotAMatch() {
            // "show everything" is the caller's decision, not a neutral match from here.
            assertNull(FuzzyMatch.of("MainController", ""));
            assertNull(FuzzyMatch.of("MainController", "   "));
        }

        @Test
        void nullsAreNotAMatch() {
            assertNull(FuzzyMatch.of(null, "x"));
            assertNull(FuzzyMatch.of("x", null));
            assertNull(FuzzyMatch.of("", "x"));
        }

        @Test
        void matchIsCaseInsensitive() {
            assertNotNull(FuzzyMatch.of("MainController", "MAINCONTROLLER"));
            assertNotNull(FuzzyMatch.of("maincontroller", "MC"));
        }
    }

    @Nested
    @DisplayName("ranking")
    class Ranking {

        @Test
        void prefixBeatsInternalSubstring() {
            beats("main", "MainController", "DomainModel");
        }

        @Test
        void contiguousBeatsScattered() {
            beats("undo", "Edit: Undo", "Unsplit Editor Down");
        }

        @Test
        void acronymOnHumpsBeatsMidWordMatch() {
            // The headline case: `mc` should mean the two capitals, not a `c` buried inside a word.
            beats("mc", "MainController", "MyMacro");
        }

        @Test
        void wordBoundaryBeatsMidWord() {
            beats("gp", "git push", "gzip");
        }

        @Test
        void shorterSpreadWins() {
            // Same characters, same boundaries — the tighter match is the better one.
            beats("mc", "MainController", "MainXXXXXXXXController");
        }

        @Test
        void positionInTheCandidateIsNotItselfAScore() {
            // Two identical matches differing only in how much text precedes them score the SAME. Position
            // is a caller-level tiebreak (shorter title, or the supplier's original index), not a signal
            // here — scoring it made "Undo History" outrank "Edit: Undo" for `undo`, purely because the
            // command palette names things "Category: Verb" and the verb is what was typed.
            Match early = FuzzyMatch.of("cfg loader", "cfg");
            Match late = FuzzyMatch.of("the cfg loader", "cfg");
            assertNotNull(early);
            assertNotNull(late);
            assertEquals(early.score(), late.score());
        }

        @Test
        void aWholeWordVerbTiesWithATitleThatMerelyStartsWithIt() {
            // The regression this guards: the palette breaks the tie by the shorter title, which is what
            // puts "Edit: Undo" above "Undo History". See CommandPaletteRankTest.
            Match verb = FuzzyMatch.of("Edit: Undo", "undo");
            Match prefix = FuzzyMatch.of("Undo History", "undo");
            assertNotNull(verb);
            assertNotNull(prefix);
            assertEquals(verb.score(), prefix.score());
        }

        @Test
        void exactCaseBreaksATie() {
            beats("MC", "MC", "mc");
        }

        @Test
        void rankingIsAWholeOrderNotJustAWinner() {
            // A realistic slice of the command palette for the query "tog".
            List<String> ranked = rank(
                    "tog", "Toggle Fold", "View: Toggle Git Blame", "Stop Debugging", "Tool Windows: Go to Terminal");
            assertEquals("Toggle Fold", ranked.get(0), "a leading exact prefix must win outright");
            assertTrue(
                    ranked.indexOf("View: Toggle Git Blame") < ranked.indexOf("Stop Debugging"),
                    "a whole-word hit must outrank a scattered one: " + ranked);
        }
    }

    @Nested
    @DisplayName("best alignment, not the first one")
    class Alignment {

        @Test
        void picksTheHumpOverTheEarlierMidWordCharacter() {
            // Greedy left-to-right takes the `c` in "Mac" (index 4). The right answer is the `C` at index 5.
            assertEquals("M|C", matched("MyMacController", "mc"));
        }

        @Test
        void prefersAContiguousRunOverAnEarlierScatteredOne() {
            // `ab` could match a(0)+b(6), but "ab" at 5 is one clean run.
            assertEquals("ab", matched("a_x_y_ab", "ab"));
        }
    }

    @Nested
    @DisplayName("matched ranges")
    class Ranges {

        @Test
        void contiguousMatchIsOneRange() {
            Match m = FuzzyMatch.of("MainController", "Main");
            assertNotNull(m);
            assertArrayEquals(new int[][] {{0, 4}}, m.ranges());
        }

        @Test
        void adjacentCharactersCoalesceIntoRuns() {
            // Two separated runs collapse to exactly two ranges, not eight single-character ones.
            assertEquals("Main|roller", matched("MainController", "mainroller"));
        }

        @Test
        void rangesAreAscendingAndNonOverlapping() {
            Match m = FuzzyMatch.of("src/main/java/com/editora/ui/MainController.java", "smjc");
            assertNotNull(m);
            int prevEnd = -1;
            for (int[] r : m.ranges()) {
                assertTrue(r[0] < r[1], "range must be non-empty");
                assertTrue(r[0] >= prevEnd, "ranges must not overlap: " + java.util.Arrays.deepToString(m.ranges()));
                prevEnd = r[1];
            }
        }

        @Test
        void rangesStayInsideTheCandidateAcrossCaseFoldingHazards() {
            // "İ" (U+0130) lowercases to TWO chars, so a matcher working on a lowercased copy would return
            // indices that overrun the original — and the caller substrings by these.
            String candidate = "İstanbulTimeZone";
            Match m = FuzzyMatch.of(candidate, "tz");
            assertNotNull(m);
            for (int[] r : m.ranges()) {
                assertTrue(r[0] >= 0 && r[1] <= candidate.length(), "range " + r[0] + ".." + r[1] + " overruns");
                candidate.substring(r[0], r[1]); // must not throw — this is what the picker cell does
            }
        }
    }

    @Nested
    @DisplayName("multi-term queries")
    class MultiTerm {

        @Test
        void allTermsMustMatch() {
            assertNotNull(FuzzyMatch.of("Git: Toggle Blame", "toggle git"));
            assertNull(FuzzyMatch.of("Git: Toggle Blame", "toggle mercurial"));
        }

        @Test
        void termsMatchOutOfOrder() {
            // The whole point: a single ordered subsequence cannot find this.
            assertNull(FuzzyMatch.of("Git: Toggle Blame", "toggleGit".toLowerCase(java.util.Locale.ROOT)));
            assertNotNull(FuzzyMatch.of("Git: Toggle Blame", "toggle git"));
        }

        @Test
        void extraWhitespaceIsHarmless() {
            assertNotNull(FuzzyMatch.of("Git: Toggle Blame", "  toggle   git  "));
        }

        @Test
        void rangesCoverEveryTerm() {
            assertEquals("Git|Toggle", matched("Git: Toggle Blame", "toggle git"));
        }
    }

    @Nested
    @DisplayName("paths")
    class Paths {

        @Test
        void basenameMatchBeatsDirectoryMatch() {
            Match base = FuzzyMatch.ofPath("src/ui/MainController.java", "main");
            Match dir = FuzzyMatch.ofPath("src/main/java/Foo.java", "main");
            assertNotNull(base);
            assertNotNull(dir);
            assertTrue(base.score() > dir.score(), "a file named Main… must outrank a file inside main/");
        }

        @Test
        void aQuerySpanningDirectoryAndNameStillMatches() {
            Match m = FuzzyMatch.ofPath("src/ui/MainController.java", "ui main");
            assertNotNull(m);
        }

        @Test
        void basenameRangesAreOffsetToThePath() {
            String path = "src/ui/MainController.java";
            Match m = FuzzyMatch.ofPath(path, "main");
            assertNotNull(m);
            assertEquals("Main", path.substring(m.ranges()[0][0], m.ranges()[0][1]));
        }

        @Test
        void windowsSeparatorsAreUnderstood() {
            Match m = FuzzyMatch.ofPath("src\\ui\\MainController.java", "main");
            assertNotNull(m);
            assertEquals("Main", "src\\ui\\MainController.java".substring(m.ranges()[0][0], m.ranges()[0][1]));
        }

        @Test
        void aPathWithNoSeparatorFallsBackCleanly() {
            assertNotNull(FuzzyMatch.ofPath("MainController.java", "main"));
        }
    }

    @Nested
    @DisplayName("bounds")
    class Bounds {

        @Test
        void aVeryLongCandidateIsMatchedOnItsTailWithCorrectIndices() {
            String candidate = "x".repeat(FuzzyMatch.MAX_SCAN + 120) + "MainController";
            Match m = FuzzyMatch.of(candidate, "maincontroller");
            assertNotNull(m);
            assertEquals("MainController", candidate.substring(m.ranges()[0][0], m.ranges()[0][1]));
        }

        @Test
        void aSingleCharacterQueryMatchesAndRanksByPosition() {
            beats("m", "Main", "Format");
        }

        @Test
        void repeatedCharactersDoNotConfuseTheBacktrack() {
            assertEquals("aaa", matched("aaaa", "aaa"));
        }
    }
}
