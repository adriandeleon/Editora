package com.editora.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.editora.editor.SearchMatcher.Located;
import com.editora.editor.SearchMatcher.Query;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The find bar's bounded match pages ({@link SearchMatcher#around} / {@link SearchMatcher#locate}) against the
 * plain "list of every match" they replace: a page must be a true slice of that list that knows its place in
 * it, and next/previous through pages must visit exactly the matches the full list gives, in the same order.
 */
class SearchMatchesTest {

    private static final String[] TEXTS = {
        "",
        "aaaaaaaaaaaaaaaa",
        "foo bar foo\nbar foo baz\n\nfoo",
        "Foo foo FOO fOo ffoo foo_ foo",
        "line one\nline two\n\nline four\n",
        "ß SS ss ﬁ FI straße STRASSE",
        "x",
    };

    private static final Query[] QUERIES = {
        new Query("a", true, false, false),
        new Query("aa", true, false, false),
        new Query("foo", false, false, false),
        new Query("foo", true, false, true),
        new Query("ss", false, false, false),
        new Query("^", true, true, false),
        new Query("$", true, true, false),
        new Query("^$", true, true, false),
        new Query("o+", true, true, false),
        new Query("\\bline\\b", true, true, false),
        new Query("x*", true, true, false),
        new Query("line", true, false, false, 9, 27),
        new Query("^", true, true, false, 4, 12),
        new Query("nomatch", true, false, false),
    };

    private static List<int[]> everyMatch(String text, Query q) {
        List<int[]> all = SearchMatcher.matches(text, q.text(), q.caseSensitive(), q.regex(), q.wholeWord());
        List<int[]> scoped = new ArrayList<>();
        for (int[] m : all) {
            if (q.inScope(m[0], m[1])) {
                scoped.add(m);
            }
        }
        return scoped;
    }

    @Test
    void aPageIsASliceOfEveryMatchThatKnowsItsPlace() {
        for (String text : TEXTS) {
            for (Query q : QUERIES) {
                List<int[]> all = everyMatch(text, q);
                for (int cap : new int[] {2, 3, 4, 7, 1000}) {
                    for (int from = -1; from <= text.length() + 1; from++) {
                        SearchMatches page = SearchMatcher.around(text, q, from, cap);
                        String where = "'" + text + "' /" + q + "/ from " + from + " cap " + cap;
                        assertTrue(page.size() <= cap, where + ": holds " + page.size());
                        assertTrue(page.complete(), where);
                        int first = (int) page.before();
                        assertEquals(first + page.size() < all.size(), page.moreAfter(), where + ": moreAfter");
                        assertEquals(Math.min(cap, all.size()), page.size(), where + ": a full page while it can be");
                        for (int i = 0; i < page.size(); i++) {
                            assertEquals(all.get(first + i)[0], page.start(i), where + ": start " + i);
                            assertEquals(all.get(first + i)[1], page.end(i), where + ": end " + i);
                            assertEquals(first + i + 1, page.ordinal(i), where);
                        }
                        // The page sits next to `from`: the first match at or after it is held whenever it exists.
                        int target = SearchMatcher.nextIndex(all, from, true);
                        if (target >= 0 && all.get(target)[0] >= from) {
                            assertTrue(target >= first && target < first + page.size(), where + ": holds the target");
                        }
                    }
                }
            }
        }
    }

    @Test
    void locatingThroughPagesGivesTheMatchTheFullListGives() {
        for (String text : TEXTS) {
            for (Query q : QUERIES) {
                List<int[]> all = everyMatch(text, q);
                for (int cap : new int[] {2, 3, 5, 1000}) {
                    for (boolean forward : new boolean[] {true, false}) {
                        for (int from = 0; from <= text.length() + 1; from++) {
                            String where =
                                    "'" + text + "' /" + q + "/ from " + from + " cap " + cap + " fwd " + forward;
                            int expected = SearchMatcher.nextIndex(all, from, forward);
                            Located found = SearchMatcher.locate(text, q, from, forward, cap);
                            if (expected < 0) {
                                assertEquals(-1, found.index(), where);
                                assertTrue(found.matches().isEmpty(), where);
                                continue;
                            }
                            assertEquals(all.get(expected)[0], found.matches().start(found.index()), where);
                            assertEquals(all.get(expected)[1], found.matches().end(found.index()), where);
                            assertEquals(expected + 1, found.matches().ordinal(found.index()), where + ": ordinal");
                            assertTrue(found.matches().size() <= cap, where);

                            // And the page answers by itself exactly when it can be sure.
                            int step = found.matches().step(from, forward);
                            if (step >= 0) {
                                assertEquals(found.index(), step, where + ": step");
                            }
                            if (all.size() <= cap) {
                                assertEquals(
                                        expected,
                                        SearchMatcher.around(text, q, 0, cap).step(from, forward),
                                        where);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void walkingNextAndPreviousThroughSmallPagesVisitsEveryMatchInOrder() {
        Random random = new Random(42);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            sb.append(random.nextInt(3) == 0 ? "needle" : "hay").append(random.nextBoolean() ? ' ' : '\n');
        }
        String text = sb.toString();
        Query q = new Query("needle", true, false, false);
        List<int[]> all = everyMatch(text, q);
        assertTrue(all.size() > 50);
        int cap = 8;

        // Next, as the find bar does it: from the active match, the first one starting after it.
        SearchMatches page = SearchMatcher.around(text, q, 0, cap);
        int index = page.step(0, true);
        int searches = 0;
        for (int n = 0; n < all.size() * 2 + 3; n++) {
            int[] expected = all.get(n % all.size());
            assertEquals(expected[0], page.start(index), "next #" + n);
            assertEquals((n % all.size()) + 1, page.ordinal(index), "ordinal of next #" + n);
            int from = page.start(index) + 1;
            int step = page.step(from, true);
            if (step < 0) {
                Located found = SearchMatcher.locate(text, q, from, true, cap);
                page = found.matches();
                step = found.index();
                searches++;
            }
            index = step;
        }
        assertTrue(searches < all.size(), "most steps stay inside the page held: " + searches + " searches");

        // Previous, from the first match: wraps to the last and walks back.
        page = SearchMatcher.around(text, q, 0, cap);
        index = 0;
        for (int n = 0; n < all.size() + 2; n++) {
            int from = page.start(index);
            int step = page.step(from, false);
            if (step < 0) {
                Located found = SearchMatcher.locate(text, q, from, false, cap);
                page = found.matches();
                step = found.index();
            }
            index = step;
            int[] expected = all.get(Math.floorMod(-1 - n, all.size()));
            assertEquals(expected[0], page.start(index), "previous #" + n);
        }
    }

    @Test
    void aDenseQueryHoldsOnlyThePageAndStopsReadingOnceItIsFull() {
        String text = "e".repeat(1_000_000);
        Query q = new Query("e", true, false, false);

        SearchMatches top = SearchMatcher.around(text, q, 0, 1000);
        assertEquals(1000, top.size());
        assertEquals(0, top.before());
        assertTrue(top.moreAfter());
        assertEquals(1000, top.counted());

        SearchMatches middle = SearchMatcher.around(text, q, 500_000, 1000);
        assertEquals(1000, middle.size());
        assertEquals(500_000 - 500, middle.before());
        assertEquals(499_500, middle.start(0));
        assertTrue(middle.moreAfter());

        SearchMatches end = SearchMatcher.around(text, q, text.length(), 1000);
        assertEquals(1000, end.size());
        assertEquals(1_000_000 - 1000, end.before());
        assertFalse(end.moreAfter());
        assertEquals(1_000_000, end.counted()); // reaching the end is the one way the total becomes known

        // The search itself stops at the page's edge: it is offered one match past it and no more.
        int[] offered = new int[1];
        SearchMatcher.scan(text, q, (start, e) -> ++offered[0] < 1001);
        assertEquals(1001, offered[0]);
    }

    @Test
    void lookupsAgreeWithALinearScan() {
        for (String text : TEXTS) {
            for (Query q : QUERIES) {
                List<int[]> all = everyMatch(text, q);
                SearchMatches held = SearchMatcher.all(text, q);
                assertEquals(all.size(), held.size());
                assertEquals(all.size(), held.toList().size());
                for (int offset = -1; offset <= text.length() + 1; offset++) {
                    String where = "'" + text + "' /" + q + "/ @" + offset;
                    assertEquals(SearchMatcher.indexAt(all, offset), held.indexAt(offset), where + " indexAt");
                    assertEquals(SearchMatcher.nextIndex(all, offset, true), held.step(offset, true), where);
                    assertEquals(SearchMatcher.nextIndex(all, offset, false), held.step(offset, false), where);
                    for (int end = offset; end <= offset + 7; end++) {
                        boolean isMatch = false;
                        for (int[] m : all) {
                            isMatch |= m[0] == offset && m[1] == end;
                        }
                        assertEquals(isMatch, held.indexOf(offset, end) >= 0, where + ".." + end + " indexOf");
                        assertEquals(isMatch, SearchMatcher.isMatch(text, q, offset, end), where + ".." + end);
                    }
                }
            }
        }
    }

    @Test
    void theVisibleWindowIsFoundWithoutWalkingWhatPrecedesIt() {
        // 200,000 matches of width 2, four characters apart; the "viewport" is characters 400,000..400,400.
        int[] flat = new int[400_000];
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < 200_000; i++) {
            pairs.add(new int[] {4 * i, 4 * i + 2});
        }
        SearchMatches held = SearchMatches.ofPairs(pairs);
        int firstOffset = 400_001;
        int lastOffset = 400_400;

        List<Integer> walked = new ArrayList<>();
        for (int i = held.firstEndingAtOrAfter(firstOffset); i < held.size() && held.start(i) <= lastOffset; i++) {
            walked.add(i);
        }
        List<Integer> linear = new ArrayList<>();
        for (int i = 0; i < pairs.size(); i++) {
            if (!(pairs.get(i)[1] < firstOffset || pairs.get(i)[0] > lastOffset)) {
                linear.add(i);
            }
        }
        assertEquals(linear, walked); // the same matches the old whole-list scan painted
        assertEquals(101, walked.size()); // and only those are visited, of 200,000
        assertEquals(100_000, walked.get(0));
        assertEquals(flat.length / 2, held.size());
    }

    @Test
    void aPageSaysWhichOffsetsItCanAnswerFor() {
        String text = "a a a a a a a a a a";
        Query q = new Query("a", true, false, false);
        SearchMatches middle = SearchMatcher.around(text, q, 10, 4); // matches at 6, 8 | 10, 12
        assertEquals(6, middle.start(0));
        assertEquals(12, middle.start(3));
        assertTrue(middle.covers(6));
        assertTrue(middle.covers(9));
        assertTrue(middle.covers(12));
        assertFalse(middle.covers(4)); // a match there would be before the page
        assertFalse(middle.covers(14)); // and one there after it
        assertTrue(SearchMatcher.all(text, q).covers(0));
        assertTrue(SearchMatches.EMPTY.covers(5));
    }
}
