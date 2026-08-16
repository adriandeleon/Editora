package com.editora.maven;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CentralVersionsTest {

    private static String metadata(String... versions) {
        StringBuilder sb = new StringBuilder("<metadata><versioning><versions>");
        for (String v : versions) {
            sb.append("<version>").append(v).append("</version>");
        }
        return sb.append("</versions></versioning></metadata>").toString();
    }

    private static final String COMPILER = "org.apache.maven.plugins:maven-compiler-plugin";
    private static final String SUREFIRE = "org.apache.maven.plugins:maven-surefire-plugin";

    private static final Function<String, String> REPO = path -> switch (path) {
        case "org/apache/maven/plugins/maven-compiler-plugin/maven-metadata.xml" -> metadata("3.13.0", "3.14.0");
        case "org/apache/maven/plugins/maven-surefire-plugin/maven-metadata.xml" -> metadata("3.5.2", "3.0.0-M7");
        default -> null;
    };

    @Test
    void resolvesEachCoordinate() {
        assertEquals(
                Map.of(COMPILER, "3.14.0", SUREFIRE, "3.5.2"),
                CentralVersions.latest(List.of(COMPILER, SUREFIRE), REPO));
    }

    /** One unreachable coordinate must not cost the others their update. */
    @Test
    void skipsWhatItCannotResolve() {
        Map<String, String> out = CentralVersions.latest(List.of(COMPILER, "com.example:missing"), REPO);
        assertEquals(Map.of(COMPILER, "3.14.0"), out);
    }

    @Test
    void aFetchThatThrowsIsJustAMiss() {
        Function<String, String> boom = path -> {
            throw new IllegalStateException("network down");
        };
        assertTrue(CentralVersions.latest(List.of(COMPILER), boom).isEmpty());
    }

    @Test
    void ignoresMalformedCoordinates() {
        assertTrue(CentralVersions.latest(List.of("nogroup", ":", "g:"), REPO).isEmpty());
    }

    // --- upgradesOnly ----------------------------------------------------------------------------

    @Test
    void keepsOnlyRealUpgrades() {
        Map<String, String> current = Map.of(COMPILER, "3.13.0", SUREFIRE, "3.5.2");
        Map<String, String> latest = Map.of(COMPILER, "3.14.0", SUREFIRE, "3.5.2");
        assertEquals(Map.of(COMPILER, "3.14.0"), CentralVersions.upgradesOnly(current, latest));
    }

    /** An "update" must never walk a version backwards. */
    @Test
    void neverDowngrades() {
        Map<String, String> current = Map.of(COMPILER, "4.0.0");
        Map<String, String> latest = Map.of(COMPILER, "3.14.0");
        assertTrue(CentralVersions.upgradesOnly(current, latest).isEmpty());
    }

    /** A plugin with no version in the pom is not ours to pin — see PomEdits.setPluginVersions. */
    @Test
    void ignoresArtifactsWithNoCurrentVersion() {
        assertTrue(CentralVersions.upgradesOnly(Map.of(), Map.of(COMPILER, "3.14.0"))
                .isEmpty());
    }
}
