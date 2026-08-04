package com.editora.maven;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenCoordinatesTest {

    @Test
    void acceptsMavensOwnIdShape() {
        assertTrue(MavenCoordinates.isValidGroupId("com.example"));
        assertTrue(MavenCoordinates.isValidGroupId("com.example.my-app"));
        assertTrue(MavenCoordinates.isValidArtifactId("my-app"));
        assertTrue(MavenCoordinates.isValidArtifactId("app_1"));
    }

    @Test
    void rejectsIdsMavenWouldReject() {
        assertFalse(MavenCoordinates.isValidGroupId(""));
        assertFalse(MavenCoordinates.isValidGroupId("com example"), "no spaces");
        assertFalse(MavenCoordinates.isValidGroupId("com/example"), "no path separators");
        assertFalse(MavenCoordinates.isValidArtifactId(".leading-dot"));
        assertFalse(MavenCoordinates.isValidArtifactId("-leading-dash"));
        assertFalse(MavenCoordinates.isValidGroupId(null));
    }

    @Test
    void versionRejectsBlankAndPathSeparators() {
        assertTrue(MavenCoordinates.isValidVersion("1.0-SNAPSHOT"));
        assertFalse(MavenCoordinates.isValidVersion(" "));
        assertFalse(MavenCoordinates.isValidVersion("1.0/2"), "a version must not be able to escape a path");
        assertFalse(MavenCoordinates.isValidVersion(" 1.0"), "untrimmed is a typo, not a version");
    }

    @Test
    void defaultPackageCombinesGroupAndArtifact() {
        assertEquals("com.example.demo", MavenCoordinates.defaultPackage("com.example", "demo"));
    }

    @Test
    void defaultPackageDoesNotDoubleATrailingArtifactId() {
        // A very common shape: groupId already ends in the artifact name.
        assertEquals("com.example.demo", MavenCoordinates.defaultPackage("com.example.demo", "demo"));
        assertEquals("demo", MavenCoordinates.defaultPackage("demo", "demo"));
    }

    @Test
    void defaultPackageSanitisesSegmentsIllegalInJava() {
        // A dashed artifactId is the single most common case and must not produce an uncompilable package.
        assertEquals("com.example.my_app", MavenCoordinates.defaultPackage("com.example", "my-app"));
        // A segment starting with a digit is legal Maven, illegal Java.
        assertEquals("com._2048", MavenCoordinates.defaultPackage("com", "2048"));
        // A java keyword cannot be a package segment.
        assertEquals("com.new_.app", MavenCoordinates.defaultPackage("com.new", "app"));
    }

    @Test
    void projectDirIsParentPlusArtifactId() {
        // archetype:generate creates <cwd>/<artifactId> — the wizard shows this, it is never chosen.
        assertEquals(Path.of("/tmp/work/demo"), MavenCoordinates.projectDir(Path.of("/tmp/work"), "demo"));
        assertNull(MavenCoordinates.projectDir(Path.of("/tmp"), " "));
        assertNull(MavenCoordinates.projectDir(null, "demo"));
    }

    @Test
    void parseGavAcceptsThreeValidParts() {
        MavenArchetype a = MavenCoordinates.parseGav(" org.example : my-arch : 2.1 ");
        assertEquals("org.example", a.groupId());
        assertEquals("my-arch", a.artifactId());
        assertEquals("2.1", a.version());
        assertFalse(a.curated(), "a typed GAV is never curated — it must go through the consent prompt");
    }

    @Test
    void parseGavReturnsNullRatherThanThrowing() {
        assertNull(MavenCoordinates.parseGav("only:two"));
        assertNull(MavenCoordinates.parseGav("a:b:c:d"));
        assertNull(MavenCoordinates.parseGav("bad group:b:1"));
        assertNull(MavenCoordinates.parseGav(""));
        assertNull(MavenCoordinates.parseGav(null));
    }
}
