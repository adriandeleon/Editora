package com.editora.maven;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchetypeCatalogParserTest {

    @Test
    void parsesRealCatalogShape() throws Exception {
        String xml = """
                <archetype-catalog>
                  <archetypes>
                    <archetype>
                      <groupId>org.apache.maven.archetypes</groupId>
                      <artifactId>maven-archetype-quickstart</artifactId>
                      <version>1.5</version>
                      <description>An archetype which contains a sample Maven project.</description>
                    </archetype>
                    <archetype>
                      <groupId>org.example</groupId>
                      <artifactId>other</artifactId>
                      <version>2.0</version>
                      <repository>https://repo.example.com/maven2</repository>
                    </archetype>
                  </archetypes>
                </archetype-catalog>
                """;
        List<MavenArchetype> out = ArchetypeCatalogParser.parse(xml);
        assertEquals(2, out.size());
        assertEquals("maven-archetype-quickstart", out.get(0).artifactId());
        assertEquals(
                "An archetype which contains a sample Maven project.",
                out.get(0).description());
        assertEquals("https://repo.example.com/maven2", out.get(1).repository());
        assertTrue(out.get(1).hasRepository());
        assertFalse(out.get(0).curated(), "catalog entries are never curated");
    }

    @Test
    void skipsIncompleteEntriesInsteadOfFailingTheWholeFetch() throws Exception {
        // Maven Central's catalog has thousands of rows; one bad row must not cost the user the rest.
        String xml = """
                <archetype-catalog><archetypes>
                  <archetype><groupId>a</groupId><artifactId>b</artifactId></archetype>
                  <archetype><groupId>g</groupId><artifactId>a</artifactId><version>1</version></archetype>
                </archetypes></archetype-catalog>
                """;
        List<MavenArchetype> out = ArchetypeCatalogParser.parse(xml);
        assertEquals(1, out.size());
        assertEquals("g:a:1", out.get(0).gav());
    }

    @Test
    void emptyInputIsEmptyListNotAnError() throws Exception {
        assertTrue(ArchetypeCatalogParser.parse("").isEmpty());
        assertTrue(ArchetypeCatalogParser.parse(null).isEmpty());
    }

    @Test
    void malformedXmlThrows() {
        assertThrows(PomParseException.class, () -> ArchetypeCatalogParser.parse("<archetype-catalog>"));
    }

    /** This document arrives over the network, so an external entity would be attacker-controlled. */
    @Test
    void externalEntitiesAreBlocked() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE catalog [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
                <archetype-catalog><archetypes><archetype>
                  <groupId>&xxe;</groupId><artifactId>a</artifactId><version>1</version>
                </archetype></archetypes></archetype-catalog>
                """;
        assertThrows(
                PomParseException.class,
                () -> ArchetypeCatalogParser.parse(xxe),
                "a DOCTYPE must be refused outright, not resolved");
    }

    @Test
    void bundledCuratedCatalogLoadsAndContainsQuickstart() {
        List<MavenArchetype> curated = ArchetypeCatalog.curated();
        assertFalse(curated.isEmpty(), "the bundled catalog.json resource must be readable");
        assertTrue(curated.stream().allMatch(MavenArchetype::curated));
        assertEquals(
                "maven-archetype-quickstart",
                ArchetypeCatalog.defaultArchetype().artifactId());
    }

    @Test
    void mergeKeepsCuratedPinsAndAppendsTheRest() {
        MavenArchetype olderQuickstart =
                new MavenArchetype("org.apache.maven.archetypes", "maven-archetype-quickstart", "1.0", "", "", false);
        MavenArchetype novel = new MavenArchetype("org.example", "novel", "9.9", "", "", false);
        List<MavenArchetype> merged = ArchetypeCatalog.merge(List.of(olderQuickstart, novel));

        MavenArchetype quickstart = merged.stream()
                .filter(a -> a.artifactId().equals("maven-archetype-quickstart"))
                .findFirst()
                .orElseThrow();
        assertEquals("1.5", quickstart.version(), "the curated pin wins over the catalog's older version");
        assertTrue(quickstart.curated());
        assertTrue(merged.contains(novel));
    }
}
