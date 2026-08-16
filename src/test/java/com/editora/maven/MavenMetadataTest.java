package com.editora.maven;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenMetadataTest {

    private static String metadata(String... versions) {
        StringBuilder sb = new StringBuilder("<metadata><versioning><versions>");
        for (String v : versions) {
            sb.append("<version>").append(v).append("</version>");
        }
        return sb.append("</versions></versioning></metadata>").toString();
    }

    @Test
    void picksTheHighestStableVersion() {
        assertEquals("3.14.0", MavenMetadata.latestStable(metadata("3.13.0", "3.14.0", "3.9.0")));
    }

    /** Not lexical: 3.9.0 must not beat 3.14.0. */
    @Test
    void comparesNumerically() {
        assertEquals("3.14.0", MavenMetadata.latestStable(metadata("3.9.0", "3.14.0")));
    }

    /**
     * The case {@code <release>} would get wrong. maven-surefire-plugin published 3.0.0-M<n> as its release
     * for years; handing a milestone to a brand-new project is worse than not updating at all.
     */
    @Test
    void skipsMilestonesEvenWhenTheyAreNewest() {
        assertEquals("2.22.2", MavenMetadata.latestStable(metadata("2.22.2", "3.0.0-M5", "3.0.0-M7")));
    }

    @Test
    void skipsEveryPreReleaseShape() {
        for (String v : List.of(
                "2.0-SNAPSHOT",
                "2.0-alpha-1",
                "2.0-beta3",
                "2.0-rc1",
                "2.0.RC2",
                "2.0-M1",
                "2.0-preview",
                "2.0-ea",
                "2.0-incubating")) {
            assertFalse(MavenMetadata.isStable(v), v + " should not be offered");
        }
    }

    @Test
    void acceptsOrdinaryReleases() {
        for (String v : List.of("1", "1.0", "3.14.0", "5.11.0", "1.0.0.Final", "2.22.2")) {
            assertTrue(MavenMetadata.isStable(v), v + " should be offered");
        }
    }

    @Test
    void nothingStableMeansNoAnswer() {
        assertNull(MavenMetadata.latestStable(metadata("1.0-SNAPSHOT", "2.0-M1")));
        assertNull(MavenMetadata.latestStable(metadata()));
    }

    /** A truncated or unparseable download must degrade to "no answer", never throw. */
    @Test
    void survivesGarbage() {
        assertNull(MavenMetadata.latestStable(null));
        assertNull(MavenMetadata.latestStable("<metadata><versioning><versions><version>1.0"));
        assertNull(MavenMetadata.latestStable("not xml"));
    }

    @Test
    void readsVersionsInDocumentOrder() {
        assertEquals(List.of("1.0", "2.0"), MavenMetadata.versions(metadata("1.0", "2.0")));
    }

    @Test
    void buildsTheRepositoryPath() {
        assertEquals(
                "org/apache/maven/plugins/maven-compiler-plugin/maven-metadata.xml",
                MavenMetadata.metadataPath("org.apache.maven.plugins", "maven-compiler-plugin"));
    }

    @Test
    void refusesIncompleteCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> MavenMetadata.metadataPath("", "a"));
        assertThrows(IllegalArgumentException.class, () -> MavenMetadata.metadataPath("g", null));
    }
}
