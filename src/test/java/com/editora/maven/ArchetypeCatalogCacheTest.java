package com.editora.maven;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchetypeCatalogCacheTest {

    private static final List<MavenArchetype> ROWS = List.of(
            new MavenArchetype(
                    "org.apache.maven.archetypes", "maven-archetype-quickstart", "1.5", "A sample project", "", false),
            new MavenArchetype("org.example", "other", "2.0", "", "https://repo.example.com/maven2", false));

    @Test
    void roundTripsRowsAndValidators(@TempDir Path dir) throws Exception {
        Path file = ArchetypeCatalogCache.file(dir);
        assertEquals(dir.resolve("cache").resolve("archetype-catalog.json"), file);
        ArchetypeCatalogCache.Entry entry =
                new ArchetypeCatalogCache.Entry(ROWS, "\"abc\"", "Wed, 14 Oct 2026 10:00:00 GMT", 1_700_000_000_000L);
        ArchetypeCatalogCache.write(file, entry);

        ArchetypeCatalogCache.Entry back = ArchetypeCatalogCache.read(file);
        assertEquals(entry, back);
        assertFalse(back.archetypes().get(0).curated(), "cached rows are never curated");
        assertTrue(back.archetypes().get(1).hasRepository());
        assertTrue(back.hasValidators());
        assertFalse(Files.exists(file.resolveSibling("archetype-catalog.json.tmp")), "written atomically");
    }

    @Test
    void freshnessIsADay() {
        long now = 10_000_000_000L;
        ArchetypeCatalogCache.Entry entry = new ArchetypeCatalogCache.Entry(ROWS, "", "", now);
        assertTrue(entry.isFresh(now));
        assertTrue(entry.isFresh(now + ArchetypeCatalogCache.MAX_AGE.toMillis() - 1));
        assertFalse(entry.isFresh(now + ArchetypeCatalogCache.MAX_AGE.toMillis()));
        assertFalse(entry.isFresh(now - 1), "a cache from the future is not trusted");
        assertFalse(entry.hasValidators());
        assertEquals(now + 5, entry.revalidated(now + 5).fetchedAt());
        assertEquals(ROWS, entry.revalidated(now + 5).archetypes());
    }

    @Test
    void aMissingDamagedOrForeignFileIsNoCache(@TempDir Path dir) throws Exception {
        assertNull(ArchetypeCatalogCache.read(null));
        assertNull(ArchetypeCatalogCache.read(dir.resolve("none.json")));
        Path damaged = Files.writeString(dir.resolve("damaged.json"), "{\"format\": 1, \"archetypes\": [");
        assertNull(ArchetypeCatalogCache.read(damaged));
        Path foreign = Files.writeString(dir.resolve("foreign.json"), "{\"format\": 99, \"archetypes\": []}");
        assertNull(ArchetypeCatalogCache.read(foreign), "a newer format is not guessed at");
        Path list = Files.writeString(dir.resolve("list.json"), "[1, 2]");
        assertNull(ArchetypeCatalogCache.read(list));
    }

    @Test
    void incompleteRowsAreSkippedOnRead(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("c.json"), """
                {"format": 1, "fetchedAt": 5, "archetypes": [
                  {"groupId": "g", "artifactId": "a"},
                  {"groupId": "g", "artifactId": "b", "version": "1"}
                ]}
                """);
        ArchetypeCatalogCache.Entry entry = ArchetypeCatalogCache.read(file);
        assertEquals(1, entry.archetypes().size());
        assertEquals("g:b:1", entry.archetypes().get(0).gav());
        assertEquals(5, entry.fetchedAt());
    }
}
