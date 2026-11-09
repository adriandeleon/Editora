package com.editora.ui;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Map's one Back/Forward history: places and the cards the code was followed through, in one list. */
class MapHistoryTest {

    private final Path root = Path.of("/p").toAbsolutePath();
    private final Path folder = root.resolve("src");
    private final Path use = folder.resolve("Use.java");
    private final Path shape = folder.resolve("Shape.java");
    private final Path area = shape.resolve(ProjectMapStructure.SEGMENT_PREFIX + "area");

    private final Set<String> open = new HashSet<>(Set.of("A", "B", "C"));
    private final Map<String, Integer> carets = new HashMap<>();
    private final MapHistory<String> history = new MapHistory<>(open::contains, card -> carets.getOrDefault(card, 0));

    @Test
    void placesAreRecordedAsBeforeRunsFoldingAndAPlaceNeverReplaced() {
        assertTrue(history.record(root, MapHistory.Head.PLACE));
        assertFalse(history.record(root, MapHistory.Head.STEP), "the place it is at adds nothing");
        history.record(folder, MapHistory.Head.STEP);
        history.record(use, MapHistory.Head.STEP);
        assertEquals(List.of(root, use), history.places(), "a run of steps is one stop: where it got to");
        history.record(root, MapHistory.Head.STEP);
        assertEquals(List.of(root), history.places(), "a run back to where it started folds away");
        history.record(use, MapHistory.Head.LANDING);
        history.record(shape, MapHistory.Head.LANDING);
        assertEquals(List.of(root, shape), history.places(), "a typed query's landings: its last");
        history.record(shape, MapHistory.Head.PLACE);
        history.record(use, MapHistory.Head.LANDING);
        assertEquals(List.of(root, shape, use), history.places(), "a landing stepped to became a place");
        assertFalse(history.canGoForward());
        assertTrue(history.canGoBack());
    }

    @Test
    void followingTheCodeAddsBothCardsAndBackReturnsWithTheirCarets() {
        history.record(root, MapHistory.Head.PLACE);
        history.record(use, MapHistory.Head.PLACE); // Use.java's row picked, its card opened from it
        history.followed("A", use, 40, "B", area);
        assertEquals(List.of(root, use, area), history.places(), "A's stop is its row's place: no second stop");
        assertEquals(List.of("A"), history.cardsBack());

        carets.put("B", 7);
        MapHistory.Stop<String> back = history.step(-1, "B", area);
        assertEquals(new MapHistory.Stop<>(use, "A", 40), back, "A, its caret where it was");
        carets.put("A", 12);
        MapHistory.Stop<String> forward = history.step(1, null, null);
        assertEquals(new MapHistory.Stop<>(area, "B", 7), forward, "B, its caret kept when Back left it");
        assertEquals(new MapHistory.Stop<>(use, "A", 12), history.step(-1, null, null), "from the map too");
        assertEquals(new MapHistory.Stop<>(root, null, -1), history.step(-1, null, null), "then the place before");
        assertNull(history.step(-1, null, null), "nowhere earlier");
        assertEquals(0, history.index());
    }

    @Test
    void aStepAlongTheSameCardIsAStopAndANewStepForgetsForward() {
        history.record(use, MapHistory.Head.PLACE);
        history.followed("A", use, 5, "A", use);
        carets.put("A", 90);
        assertEquals(2, history.stops().size(), "the place it left and the place it went to, on one card");
        assertEquals(new MapHistory.Stop<>(use, "A", 5), history.step(-1, "A", use));
        assertEquals(90, history.stops().get(1).caret(), "where the caret went is Forward's");
        history.followed("A", use, 5, "C", shape);
        assertEquals(List.of(use, shape), history.places(), "Forward's stop forgotten");
    }

    @Test
    void backTypedInACardTheHistoryHasNoStopForAddsItForForward() {
        history.record(root, MapHistory.Head.PLACE);
        history.record(folder, MapHistory.Head.PLACE);
        carets.put("C", 3);
        assertEquals(
                new MapHistory.Stop<>(folder, null, -1),
                history.step(-1, "C", shape),
                "Back from a card not in the history: where the history is");
        assertEquals(new MapHistory.Stop<>(shape, "C", 3), history.step(1, null, null), "Forward returns to C");
        assertEquals(new MapHistory.Stop<>(root, null, -1), history.step(-2, null, null));
        assertEquals(new MapHistory.Stop<>(folder, null, -1), history.step(1, "A", use), "Forward from A: onwards");
        assertEquals(3, history.stops().size(), "and A is not added");
    }

    @Test
    void aClosedCardsStopIsItsPlaceAndTwinsFoldIntoOne() {
        history.record(use, MapHistory.Head.PLACE);
        history.followed("A", use, 1, "B", shape);
        history.record(shape, MapHistory.Head.PLACE); // its row picked: the place it is at already
        history.followed("B", shape, 2, "C", area);
        open.remove("B");
        history.cardClosed("B");
        assertEquals(new MapHistory.Stop<>(shape, null, -1), history.stops().get(1));
        open.remove("A");
        history.cardClosed("A");
        history.cardClosed("A");
        assertEquals(List.of(use, shape, area), history.places(), "closed cards' stops are kept as places");
        assertEquals(new MapHistory.Stop<>(area, "C", -1), history.stops().get(2));

        MapHistory<String> twins = new MapHistory<>(open::contains, card -> 0);
        twins.record(use, MapHistory.Head.PLACE);
        twins.followed("A", use, 0, "C", shape);
        twins.record(shape, MapHistory.Head.PLACE);
        twins.followed("C", shape, 0, "B", shape);
        twins.cardClosed("B");
        twins.cardClosed("C");
        assertEquals(List.of(use, shape), twins.places());
        assertEquals(1, twins.index(), "on the place it was at");

        // A card closed without the history knowing: going to its stop is going to its place.
        open.add("A");
        MapHistory<String> late = new MapHistory<>(open::contains, card -> 0);
        late.followed("A", use, 0, "B", shape);
        open.remove("A");
        assertEquals(new MapHistory.Stop<>(use, null, -1), late.step(-1, null, null));
    }

    @Test
    void aClosedCardsStopKeepsItsLineSoBackOpensItAgain() {
        MapHistory<String> lines =
                new MapHistory<>(open::contains, card -> carets.getOrDefault(card, 0), (card, caret) -> caret / 10);
        lines.record(use, MapHistory.Head.PLACE);
        lines.followed("A", use, 42, "B", shape);
        carets.put("B", 75);
        open.remove("A");
        lines.cardClosed("A");
        assertEquals(new MapHistory.Stop<>(use, null, -1, 4), lines.stops().get(0), "the line its caret was on");
        open.remove("B");
        lines.cardClosed("B");
        assertEquals(new MapHistory.Stop<>(shape, null, -1, 7), lines.stops().get(1), "where the caret is now");
        assertEquals(new MapHistory.Stop<>(use, null, -1, 4), lines.step(-1, null, null));
        lines.reopened("C");
        assertEquals(new MapHistory.Stop<>(use, "C", -1), lines.stops().get(0), "on the card opened again");
        lines.reopened("A");
        assertEquals("C", lines.stops().get(0).card(), "only a stop with no card");
    }

    @Test
    void aCardsPlaceIsItsRowsOrItsDeclarationsFiles() {
        assertTrue(MapHistory.samePlace(use, use));
        assertTrue(MapHistory.samePlace(shape, area), "the file row a declaration card is opened from");
        assertFalse(MapHistory.samePlace(area, shape));
        assertFalse(MapHistory.samePlace(folder, use), "a folder is not its files' card");

        history.record(shape, MapHistory.Head.PLACE);
        history.followed("A", area, 0, "B", use);
        assertEquals(List.of(area, use), history.places(), "the declaration card stands on its file's place");
    }

    @Test
    void deadPlacesAreDroppedRestoredPlacesKeptAndTheListBounded() {
        history.record(root, MapHistory.Head.PLACE);
        history.record(use, MapHistory.Head.PLACE);
        history.record(shape, MapHistory.Head.PLACE);
        history.record(use, MapHistory.Head.PLACE);
        history.drop(use, shape);
        assertEquals(List.of(root, shape), history.places());
        assertEquals(1, history.index(), "kept on the selection");
        history.drop(folder, shape);
        assertEquals(2, history.places().size(), "nothing at a place never visited");
        history.drop(shape, null);
        assertEquals(0, history.index());

        history.clear();
        assertEquals(-1, history.index());
        assertFalse(history.canGoBack());
        assertFalse(history.canGoForward());
        history.restore(List.of(), 0);
        assertEquals(-1, history.index(), "nothing to restore");
        history.restore(List.of(root, use, shape), 1);
        assertEquals(new MapHistory.Stop<>(shape, null, -1), history.jump(2));
        assertNull(history.jump(2), "the stop it is at");
        assertNull(history.jump(7));

        for (int i = 0; i < MapHistory.LIMIT + 20; i++) {
            history.record(folder.resolve("F" + i + ".java"), MapHistory.Head.PLACE);
        }
        assertEquals(MapHistory.LIMIT, history.places().size());
        assertEquals(MapHistory.LIMIT - 1, history.index());
        List<Path> many = new java.util.ArrayList<>();
        for (int i = 0; i < MapHistory.LIMIT + 5; i++) {
            many.add(folder.resolve("R" + i + ".java"));
        }
        history.restore(many, 2);
        assertEquals(MapHistory.LIMIT, history.places().size(), "the newest kept");
        assertEquals(0, history.index(), "a current place dropped with the oldest: the oldest kept");
    }
}
