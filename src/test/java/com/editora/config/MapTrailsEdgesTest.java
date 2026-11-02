package com.editora.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The trail store's edges ({@link MapTrails}): what a hand-edited or odd folder holds — no folder, more files than a
 * menu lists, a folder named like a trail, {@code "stops": null}, a name with nothing usable — and the file names it
 * makes from long or oddly punctuated names.
 */
class MapTrailsEdgesTest {

    @TempDir
    Path project;

    private static final MapTrails.Stop STOP = new MapTrails.Stop("a.go", "", 1, 2, "A", "");

    @Test
    void aProjectWithNoTrailsFolderListsNothing() {
        assertEquals(List.of(), MapTrails.list(project));
        assertEquals(project.resolve(".editora").resolve("map-trails"), MapTrails.folderFor(project));
    }

    @Test
    void aFolderOfMoreThanTwoHundredTrailsListsTwoHundredAndSkipsFoldersNamedLikeTrails() throws Exception {
        Path folder = Files.createDirectories(MapTrails.folderFor(project));
        Files.createDirectory(folder.resolve("a-folder.json"));
        assertTrue(MapTrails.list(project).isEmpty(), "a folder is not a trail");
        for (int i = 0; i < MapTrails.MAX_TRAILS + 5; i++) {
            Files.writeString(folder.resolve(String.format("t%03d.json", i)), "{\"title\": \"T" + i + "\"}");
        }
        List<MapTrails.Listed> listed = MapTrails.list(project);
        assertEquals(MapTrails.MAX_TRAILS, listed.size(), "a hand-filled folder never builds a menu of thousands");
        assertTrue(listed.stream().allMatch(entry -> entry.trail() != null));
    }

    @Test
    void aTrailOfNoStopsOrNullStopsReadsAsEmptyAndTooManyStopsAreCut() throws Exception {
        assertEquals(List.of(), new MapTrails.Trail(1, "x", null).stops());
        List<MapTrails.Stop> many = java.util.stream.IntStream.range(0, MapTrails.MAX_STOPS + 3)
                .mapToObj(i -> new MapTrails.Stop("f" + i, "", i, i, "", ""))
                .toList();
        assertEquals(
                MapTrails.MAX_STOPS, new MapTrails.Trail("many", many).stops().size());

        Path folder = Files.createDirectories(MapTrails.folderFor(project));
        Files.writeString(folder.resolve("nullstops.json"), "{\"title\": \"N\", \"stops\": null}");
        Files.writeString(folder.resolve("nostops.json"), "{\"title\": \"M\"}");
        Files.writeString(folder.resolve("scalar.json"), "42");
        Files.writeString(folder.resolve("null.json"), "null");
        assertEquals(
                List.of(),
                MapTrails.read(folder.resolve("nullstops.json")).trail().stops());
        assertEquals(
                List.of(),
                MapTrails.read(folder.resolve("nostops.json")).trail().stops());
        assertEquals(
                "not a JSON object",
                MapTrails.read(folder.resolve("scalar.json")).problem());
        assertNull(MapTrails.read(folder.resolve("null.json")).trail());
    }

    @Test
    void aFileNotNamedDotJsonKeepsItsWholeNameAndAMultiLineReasonKeepsItsFirstLine() throws Exception {
        Path other = Files.writeString(project.resolve("notes.trail"), "{\"title\": \"\", \"stops\": []}");
        MapTrails.Listed read = MapTrails.read(other);
        assertEquals("notes.trail", read.name());
        assertEquals("notes.trail", read.label(), "a blank title is the file's name");

        Path broken = Files.writeString(project.resolve("broken.json"), "{\"title\": \"x\",\n \"stops\": [ }");
        MapTrails.Listed bad = MapTrails.read(broken);
        assertNull(bad.trail());
        assertNotNull(bad.problem());
        assertFalse(bad.problem().contains("\n"), "one line, for a menu: " + bad.problem());

        MapTrails.Listed missing = MapTrails.read(project.resolve("missing.json"));
        assertNull(missing.trail());
        assertNotNull(missing.problem(), "a file that cannot be read says why rather than throwing");

        MapTrails.Listed rootless = MapTrails.read(project.getRoot());
        assertEquals("", rootless.name(), "a path with no file name");
        assertNull(rootless.trail());
    }

    @Test
    void aListedEntryWithABlankTitleOrNoTrailIsLabelledByItsName() {
        Path file = project.resolve("x.json");
        assertEquals("x", new MapTrails.Listed("x", file, new MapTrails.Trail(" ", List.of()), null, false).label());
        assertEquals("x", new MapTrails.Listed("x", file, null, "bad", false).label());
        assertEquals(
                "Shown", new MapTrails.Listed("x", file, new MapTrails.Trail("Shown", List.of()), null, false).label());
    }

    @Test
    void fileNamesDropLeadingSeparatorsTrailingDotsAndStopAtEightyCharacters() {
        assertEquals("", MapTrails.fileName(null));
        assertEquals("a-b", MapTrails.fileName("--a -- b--"), "runs of separators are one dash, none at the ends");
        assertEquals("v1.2", MapTrails.fileName("v1.2..."));
        assertEquals("x", MapTrails.fileName("x.-.-"));
        String eighty = "a".repeat(100);
        assertEquals(80, MapTrails.fileName(eighty).length());
        assertEquals(MapTrails.folderFor(project).resolve("My-trail.json"), MapTrails.fileFor(project, " My trail "));
    }

    @Test
    void saveAndRenameRefuseANameWithNothingUsable() throws Exception {
        IOException blank = assertThrows(
                IOException.class, () -> MapTrails.save(project, "???", new MapTrails.Trail("???", List.of())));
        assertTrue(blank.getMessage().contains("???"), blank.getMessage());
        MapTrails.save(project, "kept", new MapTrails.Trail("kept", List.of(STOP)));
        MapTrails.Trail kept =
                MapTrails.read(MapTrails.fileFor(project, "kept")).trail();
        assertThrows(IOException.class, () -> MapTrails.rename(project, "kept", "  ", kept));
        assertTrue(Files.exists(MapTrails.fileFor(project, "kept")), "nothing moved");
    }

    @Test
    void renamingToTheSameFileOnlyRetitlesIt() throws Exception {
        MapTrails.save(project, "Tour", new MapTrails.Trail("Tour", List.of(STOP)));
        MapTrails.Trail trail =
                MapTrails.read(MapTrails.fileFor(project, "Tour")).trail();
        Path renamed = MapTrails.rename(project, "Tour", "Tour", trail.withTitle("old"));
        assertEquals(MapTrails.fileFor(project, "Tour"), renamed);
        assertTrue(Files.exists(renamed), "the file is not deleted when it is its own target");
        assertEquals("Tour", MapTrails.read(renamed).trail().title());
        assertEquals(List.of(STOP), MapTrails.read(renamed).trail().stops());
    }

    @Test
    void renamingANewerEditorasTrailIsRefused() throws Exception {
        Path folder = Files.createDirectories(MapTrails.folderFor(project));
        Files.writeString(folder.resolve("future.json"), "{\"version\": 9, \"title\": \"F\", \"stops\": []}");
        MapTrails.Trail trail = MapTrails.read(folder.resolve("future.json")).trail();
        MapTrails.NotWritableException refused = assertThrows(
                MapTrails.NotWritableException.class, () -> MapTrails.rename(project, "future", "Past", trail));
        assertTrue(refused.getMessage().contains("9"), refused.getMessage());
        assertFalse(Files.exists(MapTrails.fileFor(project, "Past")));
    }

    @Test
    void stopsAndTrailsChangeOneFieldAtATime() {
        MapTrails.Stop stop = STOP.withTitle("T").withNote("N");
        assertEquals(new MapTrails.Stop("a.go", "", 1, 2, "T", "N"), stop);
        MapTrails.Trail trail =
                new MapTrails.Trail("A", List.of()).withStops(List.of(stop)).withTitle("B");
        assertEquals(MapTrails.VERSION, trail.version());
        assertEquals("B", trail.title());
        assertEquals(List.of(stop), trail.stops());
    }
}
