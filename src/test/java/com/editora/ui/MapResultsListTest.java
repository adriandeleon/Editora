package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An answer's places as its list shows them: grouped, in order, with text, filtered. */
class MapResultsListTest {

    private final Path use = Path.of("/p/src/Use.java").toAbsolutePath();
    private final Path other = Path.of("/p/src/Other.txt").toAbsolutePath();

    @Test
    void eachPlaceBelongsToTheInnermostDeclarationThatHoldsItElseItsFile() {
        MapCardLinks.Group outer = group(use, "Use", 2, 2, 20);
        MapCardLinks.Group inner = group(use, "total()", 3, 3, 6);
        MapCardLinks.Group file = new MapCardLinks.Group(other, null, "Other.txt", 0, 1);
        List<MapCardLinks.Group> groups = List.of(outer, inner, file);
        assertSame(inner, MapResultsList.groupOf(groups, use, 4));
        assertSame(outer, MapResultsList.groupOf(groups, use, 10));
        assertSame(file, MapResultsList.groupOf(groups, other, 7));
        assertNull(MapResultsList.groupOf(groups, use, 30), "outside every declaration of a file with no file group");
        MapCardLinks.Group server = new MapCardLinks.Group(use, null, "twice", 30, 1);
        assertSame(server, MapResultsList.groupOf(List.of(server), use, 30));
        assertSame(server, MapResultsList.groupOf(List.of(server), use, 31), "a group of no declaration: its file's");
        assertNull(MapResultsList.groupOf(List.of(server), other, 31));
        MapCardLinks.Group named = group(use, "named()", 50, 52, 60);
        assertSame(named, MapResultsList.groupOf(List.of(named), use, 50), "on its name's line, above its lines");
        MapCardLinks.Group twin = group(use, "twin()", 3, 3, 6);
        assertSame(inner, MapResultsList.groupOf(List.of(inner, twin), use, 4), "the first of two alike");
    }

    @Test
    void placesFollowTheGroupsOrderInLineOrderWithTheirTextOnceEach() {
        MapCardLinks.Group inner = group(use, "total()", 3, 3, 6);
        MapCardLinks.Group file = new MapCardLinks.Group(other, null, "Other.txt", 0, 1);
        List<MapCardLinks.Hit> hits = List.of(
                new MapCardLinks.Hit(use, 5, 4, 1, null),
                new MapCardLinks.Hit(use, 4, 9, 1, null),
                new MapCardLinks.Hit(use, 4, 20, 1, null), // a second place on that line
                new MapCardLinks.Hit(other, 1, 0, 1, null),
                new MapCardLinks.Hit(use, 40, 0, 1, null)); // in no group: not the answer's
        String longLine = "x".repeat(MapResultsList.MAX_TEXT + 10);
        List<MapResultsList.Place> places = MapResultsList.placesOf(
                hits,
                List.of(file, inner),
                path -> path.equals(use) ? List.of("", "", "", "", "    add(1);  ", longLine) : List.of("a", "b"));
        assertEquals(3, places.size(), "line 4 once, line 40 left out");
        assertEquals(new MapResultsList.Place(file, other, 1, "b"), places.get(0), "the groups' order");
        assertEquals(new MapResultsList.Place(inner, use, 4, "add(1);"), places.get(1), "then line order, stripped");
        assertEquals(MapResultsList.MAX_TEXT + 1, places.get(2).text().length(), "cut, with …");
        assertEquals(
                "",
                MapResultsList.placesOf(hits, List.of(inner), path -> List.of())
                        .getFirst()
                        .text(),
                "unread");
    }

    @Test
    void theFilterMatchesTheFileOrTheTextAndTestsCanBeLeftOut() {
        MapResultsList.Place place =
                new MapResultsList.Place(group(use, "total()", 3, 3, 6), use, 4, "return Shape.area();");
        assertTrue(MapResultsList.keeps(place, "src/Use.java", "", true));
        assertTrue(MapResultsList.keeps(place, "src/Use.java", " use.j ", true), "the file, any case");
        assertTrue(MapResultsList.keeps(place, "src/Use.java", "SHAPE", true), "the text");
        assertFalse(MapResultsList.keeps(place, "src/Use.java", "circle", true));
        assertTrue(MapResultsList.keeps(place, "src/test/UseTest.java", "", true));
        assertFalse(MapResultsList.keeps(place, "src/test/UseTest.java", "", false), "a test left out");
        assertEquals(tr("project.map.places.place", "5", "return Shape.area();"), MapResultsList.placeText(place));
    }

    @Test
    void aRowNamesItsFileByItsProjectPathItsLibraryOrItsWholePath(@org.junit.jupiter.api.io.TempDir Path temp) {
        Path root = Path.of("/p").toAbsolutePath();
        assertEquals("src/Use.java", MapResultsList.relative(root, use));
        Path outside = Path.of("/elsewhere/A.java").toAbsolutePath();
        assertEquals(outside.toString(), MapResultsList.relative(root, outside));
        assertEquals(outside.toString(), MapResultsList.relative(null, outside));
        Path library = MapLibrarySources.write(
                "jdt://contents/java.base/java.util/List.class", temp.resolve("Use.java"), "interface List {}\n");
        assertEquals("java.base/List.java", MapResultsList.relative(root, library));
    }

    private static MapCardLinks.Group group(Path file, String label, int line, int start, int end) {
        ProjectMapStructure.Node node = new ProjectMapStructure.Node(
                file.resolve(ProjectMapStructure.SEGMENT_PREFIX + label),
                new ProjectMapModel.Symbol(file, label, "method", line, start, end, null, false),
                List.of());
        return new MapCardLinks.Group(file, node, label, line, 1);
    }
}
