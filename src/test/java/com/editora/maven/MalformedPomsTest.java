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
}
