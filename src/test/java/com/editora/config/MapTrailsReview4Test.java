package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Project Map review 4, the trail store: a trail file named by hand (not with the safe stem the app makes) is found
 * and written under its own name (A3), and a file this build would cut on its way back — more stops than it keeps, a
 * field it does not know — is read but never written, as a newer Editora's is (A10).
 */
class MapTrailsReview4Test {

    @TempDir
    Path project;

    private static final String STOP = "{\"file\": \"a.go\", \"startLine\": 1, \"endLine\": 2}";

    @Test
    void aHandNamedTrailIsFoundAndWrittenUnderItsOwnName() throws Exception {
        Path folder = Files.createDirectories(MapTrails.folderFor(project));
        Path hand = Files.writeString(
                folder.resolve("Kubelet tour.json"), "{\"title\": \"Kubelet tour\", \"stops\": [" + STOP + "]}");
        Files.writeString(folder.resolve("tour--two.json"), "{\"stops\": []}");

        List<MapTrails.Listed> listed = MapTrails.list(project);
        assertEquals(
                List.of("Kubelet tour", "tour--two"),
                listed.stream().map(MapTrails.Listed::name).toList());
        assertEquals(hand, MapTrails.fileFor(project, "Kubelet tour"), "the listed name is its file's");
        assertEquals(folder.resolve("tour--two.json"), MapTrails.fileFor(project, "tour--two"));
        assertEquals(folder.resolve("New-one.json"), MapTrails.fileFor(project, "New one"), "a new name: safe stem");

        MapTrails.Trail trail = MapTrails.read(hand).trail();
        Path written = MapTrails.save(project, "Kubelet tour", trail.withStops(List.of()));
        assertEquals(hand, written, "written over the file it was read from");
        assertFalse(Files.exists(folder.resolve("Kubelet-tour.json")), "no second file beside it");
        assertTrue(MapTrails.read(hand).trail().stops().isEmpty());

        Files.writeString(project.resolve(".editora").resolve("x.json"), "{}");
        assertEquals(
                folder, MapTrails.fileFor(project, "../x").getParent(), "a name is never a path out of the folder");
        assertEquals(folder.resolve("x.json"), MapTrails.fileFor(project, "x"), "a name not on disk: its stem");
    }

    @Test
    void aTrailThisBuildWouldCutIsReadOnlyAndLeftAsItIs() throws Exception {
        Path folder = Files.createDirectories(MapTrails.folderFor(project));
        String many = "{\"version\": 1, \"title\": \"Long\", \"stops\": ["
                + String.join(", ", java.util.Collections.nCopies(MapTrails.MAX_STOPS + 50, STOP)) + "]}";
        Path longFile = Files.writeString(folder.resolve("long.json"), many);
        Path authored = Files.writeString(
                folder.resolve("authored.json"),
                "{\"title\": \"A\", \"stops\": [{\"file\": \"a.go\", \"author\": \"kim\"}]}");
        Path tagged = Files.writeString(
                folder.resolve("tagged.json"), "{\"title\": \"T\", \"tags\": [\"onboarding\"], \"stops\": []}");
        Path plain = Files.writeString(
                folder.resolve("plain.json"), "{\"version\": 1, \"title\": \"P\", \"stops\": [" + STOP + ", null]}");

        MapTrails.Listed cut = MapTrails.read(longFile);
        assertTrue(cut.readOnly(), "250 stops would be written back as 200");
        assertEquals(MapTrails.MAX_STOPS, cut.trail().stops().size(), "it still plays what this build keeps");
        assertTrue(cut.problem().contains(String.valueOf(MapTrails.MAX_STOPS + 50)), cut.problem());
        MapTrails.NotWritableException refused =
                assertThrows(MapTrails.NotWritableException.class, () -> MapTrails.save(project, "long", cut.trail()));
        assertEquals(cut.problem(), refused.getMessage());
        assertEquals(many, Files.readString(longFile), "the team's file stays as it was");

        MapTrails.Listed byAuthor = MapTrails.read(authored);
        assertTrue(byAuthor.readOnly());
        assertTrue(byAuthor.problem().contains("author"), byAuthor.problem());
        MapTrails.Listed withTags = MapTrails.read(tagged);
        assertTrue(withTags.readOnly());
        assertTrue(withTags.problem().contains("tags"), withTags.problem());
        assertThrows(
                MapTrails.NotWritableException.class,
                () -> MapTrails.rename(project, "tagged", "Other", withTags.trail()));
        assertFalse(Files.exists(folder.resolve("Other.json")));

        MapTrails.Listed kept = MapTrails.read(plain);
        assertFalse(kept.readOnly(), "a null stop holds nothing to lose");
        assertNull(kept.problem());
        MapTrails.save(project, "plain", kept.trail());

        MapTrails.Listed newer = MapTrails.read(Files.writeString(
                folder.resolve("newer.json"), "{\"version\": 3, \"title\": \"N\", \"later\": 1, \"stops\": []}"));
        assertTrue(newer.readOnly());
        assertEquals("version 3", newer.problem(), "a newer file says its version, as before");
    }
}
