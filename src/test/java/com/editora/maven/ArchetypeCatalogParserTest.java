package com.editora.maven;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
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

    /**
     * Central's catalog lists every published version of every archetype (74,830 rows for 3,758 archetypes in
     * October 2026): the parser folds them into one row per {@code groupId:artifactId} at the newest version
     * by Maven's ordering — not the last one listed, nor the lexically greatest.
     */
    @Test
    void keepsTheNewestVersionPerArchetype() throws Exception {
        String xml = """
                <archetype-catalog><archetypes>
                  <archetype><groupId>g</groupId><artifactId>a</artifactId><version>1.9</version><description>old</description></archetype>
                  <archetype><groupId>g</groupId><artifactId>a</artifactId><version>1.10</version><description>new</description></archetype>
                  <archetype><groupId>g</groupId><artifactId>a</artifactId><version>1.10-SNAPSHOT</version></archetype>
                  <archetype><groupId>g</groupId><artifactId>a</artifactId><version>2.0-M1</version></archetype>
                  <archetype><groupId>g</groupId><artifactId>b</artifactId><version>r09</version></archetype>
                  <archetype><groupId>g</groupId><artifactId>b</artifactId><version>10.0</version></archetype>
                  <archetype><groupId>h</groupId><artifactId>a</artifactId><version>0.1</version></archetype>
                </archetypes></archetype-catalog>
                """;
        List<MavenArchetype> out = ArchetypeCatalogParser.parse(xml);
        assertEquals(
                List.of("g:a:2.0-M1", "g:b:10.0", "h:a:0.1"),
                out.stream().map(MavenArchetype::gav).toList());
        assertEquals("", out.get(0).description(), "the newest row's description, even when empty");
        assertEquals(
                "g:a:1.10",
                ArchetypeCatalogParser.parse(xml.replace("<version>2.0-M1</version>", "<version>1.2</version>"))
                        .get(0)
                        .gav(),
                "1.10 > 1.9 numerically and > 1.2");
    }

    /** No entry cap: a catalog far past the old 20,000-row limit parses whole, and fast. */
    @Test
    void streamsAVeryLargeCatalogWithNoEntryCap() throws Exception {
        StringBuilder sb = new StringBuilder("<archetype-catalog><archetypes>\n");
        for (int i = 0; i < 25_000; i++) {
            sb.append("<archetype><groupId>org.g")
                    .append(i % 5000)
                    .append("</groupId>")
                    .append("<artifactId>a")
                    .append(i % 5000)
                    .append("</artifactId>")
                    .append("<version>1.")
                    .append(i / 5000)
                    .append("</version>")
                    .append("<description>Row ")
                    .append(i)
                    .append("</description></archetype>\n");
        }
        sb.append("</archetypes></archetype-catalog>");
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        long t0 = System.nanoTime();
        List<MavenArchetype> out =
                ArchetypeCatalogParser.parse(new ByteArrayInputStream(bytes), Long.MAX_VALUE, Duration.ofSeconds(30));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(5000, out.size(), "25,000 rows fold into 5,000 archetypes");
        assertEquals("org.g0:a0:1.4", out.get(0).gav(), "the newest of each archetype's five versions");
        assertEquals("org.g4999:a4999:1.4", out.get(4999).gav());
        assertTrue(ms < 5000, "25k rows took " + ms + " ms"); // ~150 ms here; generous for a loaded CI box
    }

    /** The real Central catalog, when a copy is at hand (it is 18 MB, so it is not checked in). */
    @Test
    void parsesTheRealCentralCatalogWhenACopyIsAvailable() throws Exception {
        String copy = System.getProperty("archetype.catalog", "");
        Assumptions.assumeTrue(!copy.isBlank() && Files.isRegularFile(Path.of(copy)), "-Darchetype.catalog=<file>");
        try (InputStream in = Files.newInputStream(Path.of(copy))) {
            List<MavenArchetype> out =
                    ArchetypeCatalogParser.parse(in, ArchetypeCatalogParser.MAX_CATALOG_BYTES, Duration.ofMinutes(1));
            assertTrue(out.size() > 3000, "distinct archetypes: " + out.size());
            assertTrue(
                    out.stream()
                            .anyMatch(a -> a.key().equals("org.apache.maven.archetypes:maven-archetype-quickstart")),
                    "the old 20k cap dropped every org.* archetype");
            assertEquals(
                    out.size(), out.stream().map(MavenArchetype::key).distinct().count(), "one row per key");
        }
    }

    @Test
    void stopsAtTheByteCapAndTheDeadline() {
        byte[] body = ("<archetype-catalog><archetypes>"
                        + "<archetype><groupId>g</groupId><artifactId>a</artifactId><version>1</version></archetype>"
                                .repeat(200)
                        + "</archetypes></archetype-catalog>")
                .getBytes(StandardCharsets.UTF_8);
        IOException tooBig = assertThrows(
                IOException.class, () -> ArchetypeCatalogParser.parse(new ByteArrayInputStream(body), 1024, null));
        assertTrue(tooBig.getMessage().contains("exceeds 1024 bytes"), tooBig.getMessage());
        IOException tooSlow = assertThrows(
                IOException.class,
                () -> ArchetypeCatalogParser.parse(new ByteArrayInputStream(body), Long.MAX_VALUE, Duration.ZERO));
        assertTrue(tooSlow.getMessage().contains("longer than"), tooSlow.getMessage());
    }

    @Test
    void readsTheSameShapeFromAStreamAsFromAString() throws Exception {
        String xml = "<archetype-catalog><archetypes><archetype><groupId>g</groupId><artifactId>a</artifactId>"
                + "<version>1</version><description>  d  </description><repository>r</repository></archetype>"
                + "</archetypes></archetype-catalog>";
        List<MavenArchetype> fromString = ArchetypeCatalogParser.parse(xml);
        List<MavenArchetype> fromStream = ArchetypeCatalogParser.parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), Long.MAX_VALUE, null);
        assertEquals(fromString, fromStream);
        assertEquals("d", fromStream.get(0).description());
        assertEquals("r", fromStream.get(0).repository());
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

    /**
     * Every curated entry is treated as vetted (no consent prompt), so each must really exist at the pinned
     * version. Checked against the local repository when it has the artifact: an archetype that was ever
     * generated on this machine leaves {@code ~/.m2/repository/<g>/<a>/<v>/}. Entries never resolved here are
     * not judged; the real check is the generate-and-build run recorded in the fix pass.
     */
    @Test
    void everyCuratedEntryExistsAtItsPinnedVersionWhereTheLocalRepositoryHasIt() {
        Path m2 = Path.of(System.getProperty("user.home"), ".m2", "repository");
        Assumptions.assumeTrue(Files.isDirectory(m2), "no local Maven repository");
        int checked = 0;
        for (MavenArchetype a : ArchetypeCatalog.curated()) {
            Path artifact = m2.resolve(a.groupId().replace('.', '/')).resolve(a.artifactId());
            if (!Files.isDirectory(artifact)) {
                continue;
            }
            Path pinned = artifact.resolve(a.version());
            assertTrue(
                    Files.isRegularFile(pinned.resolve(a.artifactId() + "-" + a.version() + ".jar")),
                    a.gav() + " is not in " + artifact + " at the pinned version");
            checked++;
        }
        Assumptions.assumeTrue(checked > 0, "no curated archetype has been resolved into " + m2);
    }

    /** The rows that were removed on purpose must not come back by accident. */
    @Test
    void theCuratedListHoldsNoPluginsAndNothingThatCannotBuildOnACurrentJdk() {
        List<String> keys =
                ArchetypeCatalog.curated().stream().map(MavenArchetype::key).toList();
        assertFalse(keys.contains("io.quarkus:quarkus-maven-plugin"), "a plugin, not an archetype");
        assertFalse(keys.contains("org.scala-tools.archetypes:scala-archetype-simple"), "2010; Scala 2.8");
        assertFalse(keys.contains("net.alchim31.maven:scala-archetype-simple"), "Scala 2.12.6 cannot read JDK 25");
        assertTrue(ArchetypeCatalog.curated().stream()
                .noneMatch(
                        a -> a.artifactId().endsWith("-plugin") && !a.groupId().equals("org.apache.maven.archetypes")));
        assertEquals(8, keys.size(), keys.toString());
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
