package com.editora.editor;

import java.util.List;
import java.util.Optional;

import com.editora.editor.QueryReplace.Match;
import com.editora.editor.QueryReplace.Spec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the pure query-replace kernel (no toolkit). */
class QueryReplaceTest {

    private static Spec literal(String q, String r) {
        return new Spec(q, r, false, false, false, false);
    }

    private static Spec regex(String q, String r) {
        return new Spec(q, r, false, true, false, false);
    }

    private static Match found(Optional<Match> m) {
        assertTrue(m.isPresent(), "expected a match");
        return m.get();
    }

    // --- literal ---------------------------------------------------------------------------------

    @Test
    void findsTheFirstMatchAtOrAfterTheOffset() {
        Match m = found(QueryReplace.next("foo bar foo", 0, literal("foo", "X")));
        assertEquals(0, m.start());
        assertEquals(3, m.end());
        assertEquals("X", m.replacement());
    }

    @Test
    void searchesForwardFromTheGivenOffsetNotTheStart() {
        Match m = found(QueryReplace.next("foo bar foo", 3, literal("foo", "X")));
        assertEquals(8, m.start(), "the first foo is behind the search point");
    }

    @Test
    void returnsEmptyWhenNothingRemains() {
        assertTrue(QueryReplace.next("foo", 3, literal("foo", "X")).isEmpty());
        assertTrue(QueryReplace.next("abc", 0, literal("z", "X")).isEmpty());
    }

    @Test
    void literalIsCaseInsensitiveByDefault() {
        Match m = found(QueryReplace.next("FOO", 0, literal("foo", "bar")));
        assertEquals("bar", m.replacement(), "the replacement is inserted verbatim without preserve-case");
    }

    @Test
    void emptyOrNullQueryFindsNothing() {
        assertTrue(QueryReplace.next("abc", 0, literal("", "X")).isEmpty());
        assertTrue(QueryReplace.next("abc", 0, new Spec(null, "X", false, false, false, false))
                .isEmpty());
        assertTrue(QueryReplace.next(null, 0, literal("a", "X")).isEmpty());
    }

    // --- preserve case ---------------------------------------------------------------------------

    @Test
    void preserveCaseRecasesTheReplacementToTheMatch() {
        Spec s = new Spec("foo", "bar", false, false, false, true);
        assertEquals("BAR", found(QueryReplace.next("FOO", 0, s)).replacement());
        assertEquals("Bar", found(QueryReplace.next("Foo", 0, s)).replacement());
        assertEquals("bar", found(QueryReplace.next("foo", 0, s)).replacement());
    }

    // --- regex -----------------------------------------------------------------------------------

    @Test
    void regexExpandsGroupReferences() {
        Match m = found(QueryReplace.next("2026-07-23", 0, regex("(\\d+)-(\\d+)", "$2/$1")));
        assertEquals("07/2026", m.replacement());
    }

    @Test
    void regexHonoursSurroundingContextViaLookbehind() {
        // The lookbehind can only match against the full text, not the matched substring in isolation.
        Match m = found(QueryReplace.next("$5 and 5", 0, regex("(?<=\\$)\\d", "N")));
        assertEquals(1, m.start(), "only the digit after the dollar sign matches");
        assertEquals("N", m.replacement());
    }

    @Test
    void aBadGroupReferenceThrowsRatherThanCorruptingTheEdit() {
        assertThrows(RuntimeException.class, () -> QueryReplace.next("abc", 0, regex("(a)", "$9")));
    }

    @Test
    void regexPreserveCaseRecasesTheExpandedText() {
        Spec s = new Spec("(name)", "$1_field", false, true, false, true);
        assertEquals("NAME_FIELD", found(QueryReplace.next("NAME", 0, s)).replacement());
    }

    // --- planRemaining ---------------------------------------------------------------------------

    @Test
    void planRemainingResolvesEveryMatchAgainstTheOriginalText() {
        List<Match> plan = QueryReplace.planRemaining("a a a", 0, literal("a", "bb"));
        assertEquals(3, plan.size());
        assertEquals(List.of(0, 2, 4), plan.stream().map(Match::start).toList(), "offsets are original, not shifted");
        assertTrue(plan.stream().allMatch(m -> m.replacement().equals("bb")));
    }

    @Test
    void planRemainingStartsFromTheOffset() {
        assertEquals(
                1, QueryReplace.planRemaining("a a a", 1, literal("a", "b")).size() - 1);
    }

    @Test
    void planRemainingDoesNotLoopOnAZeroWidthMatch() {
        // "x*" matches empty between characters; without a step this would never terminate.
        List<Match> plan = QueryReplace.planRemaining("abc", 0, regex("x*", "-"));
        assertEquals(4, plan.size(), "one empty match at each of the 4 positions in a 3-char string");
    }

    // --- advance ---------------------------------------------------------------------------------

    @Test
    void advanceMovesPastTheReplacementLength() {
        assertEquals(15, QueryReplace.advance(new Match(10, 13, "hello"), 5), "resume after the inserted text");
    }

    @Test
    void advanceAlwaysMakesProgressPastAZeroWidthMatch() {
        assertEquals(6, QueryReplace.advance(new Match(5, 5, ""), 0), "a zero-width no-op still steps forward");
    }
}
