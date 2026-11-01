package com.editora.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MalformedPoms}: the poms that stop lemminx-maven's coordinate completion (review 2, E3), found by
 * lemminx-maven 0.12.0's own walk over the text on disk (review 3, E21, E22).
 */
class MalformedPomsTest {

    @TempDir
    Path root;

    private static final String GOOD =
            "<project><modelVersion>4.0.0</modelVersion><artifactId>a</artifactId></project>";

    private static final String BROKEN = "<project><artifactId>a\n</project>";

    private Path file(String rel, String text) throws IOException {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        return Files.writeString(f, text);
    }

    private Set<Path> found() {
        return MalformedPoms.allIn(root).stream()
                .map(MalformedPoms.Problem::file)
                .collect(Collectors.toSet());
    }

    @Test
    void theMalformedPomIsFoundWithItsLine() throws IOException {
        file("pom.xml", GOOD);
        file("core/pom.xml", GOOD);
        Path broken =
                file("broken/pom.xml", "<project>\n  <dependencies>\n    <groupId>x\n  </dependencies>\n</project>\n");

        List<MalformedPoms.Problem> all = MalformedPoms.allIn(root);

        assertEquals(1, all.size());
        assertEquals(broken, all.get(0).file());
        assertEquals(4, all.get(0).line());
    }

    @Test
    void anInvalidModelOrADoctypeIsNotMalformed() throws IOException {
        file("pom.xml", GOOD);
        file("nogav/pom.xml", "<project><dependencies><dependency/></dependencies></project>"); // well-formed
        file("dtd/pom.xml", "<?xml version=\"1.0\"?>\n<!DOCTYPE project>\n<project/>");
        assertEquals(Set.of(), found());
        assertEquals(List.of(), MalformedPoms.allIn(root.resolve("missing")));
    }

    @Test
    void theNamesAreLemminxMavensMatch() {
        for (String yes : List.of("pom.xml", "pom-java8.xml", "pom-default.xml", "my-pom.xml", "broken-1.0.pom")) {
            assertTrue(MalformedPoms.isWorkspacePom(Path.of("a", yes)), yes);
        }
        for (String no : List.of("build.xml", "pom.txt", "settings.xml", "POM.XML", "apom.json")) {
            assertFalse(MalformedPoms.isWorkspacePom(Path.of("a", no)), no);
        }
        assertFalse(MalformedPoms.isWorkspacePom(null));
    }

    @Test
    void theWalkReadsWhatLemminxMavenReads() throws IOException {
        file("pom.xml", GOOD);
        Set<Path> expected = Set.of(
                file("alt/pom-old.xml", BROKEN),
                file("alt/broken-1.0.pom", BROKEN),
                file("node_modules/x/pom.xml", BROKEN),
                file("build/x/pom-default.xml", BROKEN),
                file("out/pom.xml", BROKEN),
                file("bin/pom.xml", BROKEN),
                file("a/b/c/d/e/f/g/h/i/pom.xml", BROKEN)); // depth 10: the walk's last level
        file("target/classes/META-INF/pom.xml", BROKEN);
        file(".git/x/pom.xml", BROKEN);
        file("sub/.hidden/pom.xml", BROKEN);
        file("sub/target/pom.xml", BROKEN);
        file("a/b/c/d/e/f/g/h/i/j/pom.xml", BROKEN); // depth 11: beyond it
        file("alt/notes.xml", BROKEN);

        assertEquals(expected, found());
    }

    @Test
    void aRootNamedLikeASkippedFolderIsSkippedWhole() throws IOException {
        file("target/pom.xml", BROKEN);
        assertEquals(List.of(), MalformedPoms.allIn(root.resolve("target")), "lemminx-maven skips it too");
    }

    @Test
    void theTextOnDiskIsWhatCounts() throws IOException {
        Path pom = file("pom.xml", BROKEN);
        assertEquals(Set.of(pom), found(), "whatever an open tab holds: the server built from the disk");
        Files.writeString(pom, GOOD);
        assertEquals(Set.of(), found());
        assertNull(MalformedPoms.check(root.resolve("gone/pom.xml"), null), "a missing file is no problem");
    }

    private static final String PARENT = "<parent><groupId>com.acme.corp</groupId><artifactId>corp-parent</artifactId>"
            + "<version>42</version></parent>";

    @Test
    void aWellFormedPomMavensStrictReaderRefusesIsNamedWithWhy() throws IOException {
        Path dup = file(
                "dup/pom.xml",
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n" + PARENT
                        + "\n<artifactId>dup</artifactId>\n<dependencies/>\n<dependencies/>\n</project>");
        var u = MalformedPoms.unbuildable(dup, Files.readString(dup));
        assertEquals(MalformedPoms.Why.DUPLICATE, u.why());
        assertEquals("dependencies", u.element());
        assertEquals(5, u.line());
        assertTrue(u.hasParent());

        var odd = MalformedPoms.unbuildable(
                Path.of("odd/pom.xml"),
                "<project>\n<artifactId>o</artifactId>\n" + "<dependencie>\n</dependencie>\n</project>");
        assertEquals(MalformedPoms.Why.UNKNOWN, odd.why());
        assertEquals("dependencie", odd.element());
        assertEquals(3, odd.line());
        assertFalse(odd.hasParent());

        var dt = MalformedPoms.unbuildable(
                Path.of("dt/pom.xml"),
                "<?xml version=\"1.0\"?>\n"
                        + "<!DOCTYPE project [ <!ENTITY v \"1.0\"> ]>\n<project><version>&v;</version></project>");
        assertEquals(MalformedPoms.Why.ENTITIES, dt.why());
        assertEquals(2, dt.line());
    }

    @Test
    void whatMavenReadsIsNotNamed() {
        Path p = Path.of("pom.xml");
        assertNull(MalformedPoms.unbuildable(p, GOOD));
        assertNull(
                MalformedPoms.unbuildable(p, "<?xml version=\"1.0\"?>\n<!DOCTYPE project>\n" + GOOD), "plain DOCTYPE");
        assertNull(
                MalformedPoms.unbuildable(Path.of("pomegranate.xml"), "<fruits><fruit/><fruit/></fruits>"),
                "not a project: lemminx-maven ignores it");
        assertNull(MalformedPoms.unbuildable(p, BROKEN), "malformed: that is check's business");
        assertNull(
                MalformedPoms.unbuildable(p, "<project><build><plugins/><plugins/></build></project>"),
                "only <project>'s own children are checked");
        assertNull(MalformedPoms.unbuildable(p, null));
    }

    @Test
    void theScanSeparatesMalformedFromUnbuildableAndKeepsEachPomsTime() throws IOException {
        Path good = file("pom.xml", GOOD);
        Path broken = file("broken/pom.xml", BROKEN);
        Path dup = file("dup/pom.xml", "<project><artifactId>d</artifactId><artifactId>d</artifactId></project>");
        Files.setLastModifiedTime(good, java.nio.file.attribute.FileTime.fromMillis(1_000_000));

        MalformedPoms.Scan scan = MalformedPoms.scan(root);

        assertEquals(
                List.of(broken),
                scan.malformed().stream().map(MalformedPoms.Problem::file).toList());
        assertEquals(
                List.of(dup),
                scan.unbuildable().stream().map(MalformedPoms.Unbuildable::file).toList());
        assertEquals(Set.of(good, broken, dup), scan.modified().keySet());
        assertEquals(1_000_000L, scan.modified().get(good));
        assertTrue(scan.changedAfter(1_000_000 - 1));
        Files.setLastModifiedTime(broken, java.nio.file.attribute.FileTime.fromMillis(1_000_000));
        Files.setLastModifiedTime(dup, java.nio.file.attribute.FileTime.fromMillis(1_000_000));
        assertFalse(MalformedPoms.scan(root).changedAfter(1_000_000), "nothing after the server's start");
    }
}
