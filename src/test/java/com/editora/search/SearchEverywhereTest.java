package com.editora.search;

import java.util.ArrayList;
import java.util.List;

import com.editora.search.SearchEverywhere.Group;
import com.editora.search.SearchEverywhere.Item;
import com.editora.search.SearchEverywhere.Kind;
import com.editora.search.SearchEverywhere.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole point of this class is that a big source must not drown a small one, so most of these assert
 * that every source is still reachable rather than that the ordering is pretty.
 */
class SearchEverywhereTest {

    private static Item item(Kind kind, String label, int score) {
        return new Item(kind, label, "", score, label);
    }

    private static Item disabled(Kind kind, String label, int score) {
        return new Item(kind, label, "", "", score, false, label);
    }

    private static List<Item> many(Kind kind, int count, int baseScore) {
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(item(kind, kind + "-" + i, baseScore - i));
        }
        return out;
    }

    private static List<Kind> kinds(List<Group> groups) {
        return groups.stream().map(Group::kind).toList();
    }

    @Nested
    @DisplayName("scope sigils")
    class Scopes {

        @Test
        void readsTheVsCodeSigils() {
            assertEquals(Kind.COMMAND, SearchEverywhere.scopeOf(">undo").kind());
            assertEquals(Kind.FILE, SearchEverywhere.scopeOf("#Main").kind());
            assertEquals(Kind.SYMBOL, SearchEverywhere.scopeOf("@render").kind());
        }

        @Test
        void stripsTheSigilFromTheQuery() {
            assertEquals("undo", SearchEverywhere.scopeOf(">undo").query());
            assertEquals("undo", SearchEverywhere.scopeOf("> undo ").query());
        }

        @Test
        void noSigilMeansEverything() {
            Scope s = SearchEverywhere.scopeOf("main");
            assertNull(s.kind());
            assertEquals("main", s.query());
        }

        @Test
        void aBareSigilIsStillAScope() {
            // It is what the user types before starting the name. Treating it as an unscoped empty query
            // would flash the whole unfiltered list at them mid-keystroke.
            Scope s = SearchEverywhere.scopeOf(">");
            assertEquals(Kind.COMMAND, s.kind());
            assertEquals("", s.query());
        }

        @Test
        void anEmptyQueryIsUnscoped() {
            assertNull(SearchEverywhere.scopeOf("").kind());
            assertNull(SearchEverywhere.scopeOf("   ").kind());
            assertNull(SearchEverywhere.scopeOf(null).kind());
        }
    }

    @Nested
    @DisplayName("merging")
    class Merging {

        @Test
        void aHugeSourceCannotDrownASmallOne() {
            // The failure this class exists to prevent: 5000 symbols and one command, and the command
            // must still be on screen.
            List<Item> items = new ArrayList<>(many(Kind.SYMBOL, 5000, 500));
            items.add(item(Kind.COMMAND, "Edit: Undo", 400));
            List<Group> groups = SearchEverywhere.merge(items);
            assertTrue(kinds(groups).contains(Kind.COMMAND), "the command vanished: " + kinds(groups));
        }

        @Test
        void eachGroupIsCapped() {
            List<Item> items = new ArrayList<>(many(Kind.SYMBOL, 100, 500));
            items.addAll(many(Kind.FILE, 100, 400));
            for (Group g : SearchEverywhere.merge(items, 8, 100)) {
                assertTrue(
                        g.items().size() <= 8, g.kind() + " kept " + g.items().size());
            }
        }

        @Test
        void groupsAreOrderedByTheirBestResultNotTheirSize() {
            List<Item> items = new ArrayList<>(many(Kind.SYMBOL, 50, 100)); // many, all mediocre
            items.add(item(Kind.COMMAND, "exact", 900)); // one, excellent
            assertEquals(Kind.COMMAND, kinds(SearchEverywhere.merge(items)).get(0));
        }

        @Test
        void itemsWithinAGroupAreRankedBestFirst() {
            List<Item> items = List.of(item(Kind.FILE, "b", 10), item(Kind.FILE, "a", 90));
            assertEquals(
                    List.of("a", "b"),
                    SearchEverywhere.merge(items).get(0).items().stream()
                            .map(Item::label)
                            .toList());
        }

        @Test
        void theTotalCapTrimsATailRatherThanDroppingASource() {
            List<Item> items = new ArrayList<>(many(Kind.SYMBOL, 20, 500));
            items.addAll(many(Kind.FILE, 20, 490));
            items.addAll(many(Kind.COMMAND, 20, 480));
            List<Group> groups = SearchEverywhere.merge(items, 8, 20);
            assertEquals(3, groups.size(), "every source must survive the total cap: " + kinds(groups));
            assertEquals(20, SearchEverywhere.flatten(groups).size());
        }

        @Test
        void aTinyTotalStillFillsFromTheBestGroupFirst() {
            List<Item> items = new ArrayList<>(many(Kind.SYMBOL, 5, 100));
            items.addAll(many(Kind.COMMAND, 5, 900));
            List<Group> groups = SearchEverywhere.merge(items, 8, 2);
            assertEquals(List.of(Kind.COMMAND), kinds(groups));
            assertEquals(2, SearchEverywhere.flatten(groups).size());
        }

        @Test
        void flattenPreservesDisplayOrder() {
            List<Item> items = List.of(item(Kind.SYMBOL, "s", 10), item(Kind.COMMAND, "c", 90));
            assertEquals(
                    List.of("c", "s"),
                    SearchEverywhere.flatten(SearchEverywhere.merge(items)).stream()
                            .map(Item::label)
                            .toList());
        }

        @Test
        void theConvenienceConstructorMakesAnActionableRowWithNoDescription() {
            Item i = item(Kind.FILE, "a", 1);
            assertTrue(i.enabled());
            assertEquals("", i.description());
        }

        @Test
        void aDisabledRowNeverOutranksOneTheUserCanRun() {
            // The disabled row scores higher, so only the enabled-first rule can put "run" ahead of it.
            List<Item> items = List.of(disabled(Kind.COMMAND, "gray", 90), item(Kind.COMMAND, "run", 10));
            assertEquals(
                    List.of("run", "gray"),
                    SearchEverywhere.flatten(SearchEverywhere.merge(items)).stream()
                            .map(Item::label)
                            .toList());
        }

        @Test
        void aGroupCompetesOnItsBestActionableRow() {
            // COMMAND's top row is disabled and outscores everything; FILE's best is a row you can open,
            // so FILE must lead. Ranking groups on the raw first row would put COMMAND first.
            List<Item> items =
                    List.of(disabled(Kind.COMMAND, "gray", 99), item(Kind.COMMAND, "cmd", 5), item(Kind.FILE, "f", 50));
            assertEquals(List.of(Kind.FILE, Kind.COMMAND), kinds(SearchEverywhere.merge(items)));
        }

        @Test
        void aGroupOfNothingButDisabledRowsStillAppears() {
            List<Item> items = List.of(disabled(Kind.COMMAND, "gray", 99), item(Kind.FILE, "f", 1));
            List<Group> groups = SearchEverywhere.merge(items);
            assertTrue(kinds(groups).contains(Kind.COMMAND));
        }

        @Test
        void degenerateInputIsSafe() {
            assertEquals(List.of(), SearchEverywhere.merge(null));
            assertEquals(List.of(), SearchEverywhere.merge(List.of()));
            assertEquals(List.of(), SearchEverywhere.merge(List.of(item(Kind.FILE, "a", 1)), 0, 10));
            assertEquals(List.of(), SearchEverywhere.merge(List.of(item(Kind.FILE, "a", 1)), 10, 0));
            assertEquals(List.of(), SearchEverywhere.flatten(List.of()));
        }
    }
}
