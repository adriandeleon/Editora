package com.editora.config;

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
 * The Project Map's trails as files of the project ({@code .editora/map-trails/<name>.json}, Map explore position 2):
 * written atomically and readably, listed by title, and a file that is not a trail — or comes from a newer Editora —
 * is listed with the reason and never overwritten.
 */
class MapTrailsTest {

    @TempDir
    Path project;

    private static final MapTrails.Stop KUBELET =
            new MapTrails.Stop("pkg/kubelet/kubelet.go", "…sKubelet~0", 120, 180, "The kubelet", "Where pods start.");

    @Test
    void aTrailIsWrittenUnderEditoraMapTrailsAndReadBack() throws Exception {
        MapTrails.Trail trail = new MapTrails.Trail(
                "Pod lifecycle", List.of(KUBELET, new MapTrails.Stop("README.md", "", 0, 0, "", "")));
        Path file = MapTrails.save(project, "Pod lifecycle", trail);

        assertEquals(project.resolve(".editora/map-trails/Pod-lifecycle.json"), file);
        String json = Files.readString(file);
        assertTrue(json.contains("\"version\" : 1"), json);
        assertTrue(json.contains("\"file\" : \"pkg/kubelet/kubelet.go\""), "readable, for a review: " + json);
        assertFalse(Files.exists(file.resolveSibling("Pod-lifecycle.json.tmp")), "written atomically");

        List<MapTrails.Listed> listed = MapTrails.list(project);
        assertEquals(1, listed.size());
        MapTrails.Listed read = listed.getFirst();
        assertEquals("Pod-lifecycle", read.name());
        assertEquals("Pod lifecycle", read.label());
        assertNull(read.problem());
        assertFalse(read.readOnly());
        assertEquals(trail.stops(), read.trail().stops());
    }

    @Test
    void aMalformedFileIsListedWithItsReasonAndNeverOverwritten() throws Exception {
        Path folder = Files.createDirectories(MapTrails.folderFor(project));
        Files.writeString(folder.resolve("broken.json"), "{\"title\": \"half\", \"stops\": [{\"file\": ");
        Files.writeString(folder.resolve("list.json"), "[1, 2]");
        Files.writeString(folder.resolve("stops.json"), "{\"stops\": \"many\"}");
        Files.writeString(folder.resolve("nulls.json"), "{\"title\": null, \"stops\": [null, {\"file\": null}]}");
        Files.writeString(folder.resolve("notes.txt"), "not a trail at all");
        MapTrails.save(project, "good", new MapTrails.Trail("good", List.of(KUBELET)));

        List<MapTrails.Listed> listed = MapTrails.list(project);
        assertEquals(
                List.of("broken", "good", "list", "nulls", "stops"),
                listed.stream().map(MapTrails.Listed::name).toList(),
                "every .json file, by label; other files are not trails");
        for (String bad : List.of("broken", "list", "stops")) {
            MapTrails.Listed entry = listed.stream()
                    .filter(candidate -> candidate.name().equals(bad))
                    .findFirst()
                    .orElseThrow();
            assertNull(entry.trail(), bad);
            assertNotNull(entry.problem(), bad);
        }
        MapTrails.Listed nulls = listed.stream()
                .filter(candidate -> candidate.name().equals("nulls"))
                .findFirst()
                .orElseThrow();
        assertNotNull(nulls.trail(), "nulls are read as blanks");
        assertEquals("nulls", nulls.label(), "no title: the file's name");
        assertEquals(
                List.of(new MapTrails.Stop("", "", 0, 0, "", "")), nulls.trail().stops());

        String before = Files.readString(folder.resolve("broken.json"));
        assertThrows(
                MapTrails.NotWritableException.class,
                () -> MapTrails.save(project, "broken", new MapTrails.Trail("broken", List.of())));
        assertEquals(before, Files.readString(folder.resolve("broken.json")), "the team's file stays as it was");
    }

    @Test
    void aFileFromANewerEditoraIsReadButNotWritten() throws Exception {
        Path folder = Files.createDirectories(MapTrails.folderFor(project));
        Path file = folder.resolve("future.json");
        String newer = "{\"version\": 7, \"title\": \"Future\", \"stops\": [{\"file\": \"a.go\", \"startLine\": 3,"
                + " \"endLine\": 9, \"zoomTo\": \"later\"}], \"chapters\": []}";
        Files.writeString(file, newer);

        MapTrails.Listed read = MapTrails.read(file);
        assertTrue(read.readOnly());
        assertEquals(1, read.trail().stops().size(), "what it understands is read: it can be played");
        assertEquals(3, read.trail().stops().getFirst().startLine());
        assertThrows(MapTrails.NotWritableException.class, () -> MapTrails.save(project, "future", read.trail()));
        assertEquals(newer, Files.readString(file));
    }

    @Test
    void aRenameMovesTheFileAndRefusesATakenName() throws Exception {
        MapTrails.save(project, "first", new MapTrails.Trail("first", List.of(KUBELET)));
        MapTrails.save(project, "second", new MapTrails.Trail("second", List.of()));
        MapTrails.Trail trail =
                MapTrails.read(MapTrails.fileFor(project, "first")).trail();

        Path moved = MapTrails.rename(project, "first", "Start here", trail);
        assertEquals(MapTrails.fileFor(project, "Start here"), moved);
        assertFalse(Files.exists(MapTrails.fileFor(project, "first")));
        assertEquals("Start here", MapTrails.read(moved).label());
        assertThrows(
                java.nio.file.FileAlreadyExistsException.class,
                () -> MapTrails.rename(project, "Start-here", "second", trail));
    }

    @Test
    void fileNamesAreSafeOnEveryOs() {
        assertEquals("Pod-lifecycle", MapTrails.fileName("  Pod   lifecycle "));
        assertEquals("api-v1-types", MapTrails.fileName("api/v1\\types"));
        assertEquals("what", MapTrails.fileName("what?*:<>|\""));
        assertEquals("hidden", MapTrails.fileName("..hidden."));
        assertEquals("", MapTrails.fileName("???"));
        assertEquals("Café_2", MapTrails.fileName("Café_2"));
    }

    @Test
    void aStopKeepsItsLinesInOrder() {
        MapTrails.Stop stop = new MapTrails.Stop("a\\b.go", null, 9, 3, null, null);
        assertEquals("a/b.go", stop.file());
        assertEquals(9, stop.endLine(), "an end before the start is the start");
        assertEquals("", stop.note());
    }
}
