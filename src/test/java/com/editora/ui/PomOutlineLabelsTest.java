package com.editora.ui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Pom-aware outline labels (Maven review C16). */
class PomOutlineLabelsTest {

    private static final List<String> LINES = List.of(
            "<project>",
            "  <dependencies>",
            "    <dependency>",
            "      <groupId>org.junit.jupiter</groupId>",
            "      <artifactId>junit-jupiter-api</artifactId>",
            "      <exclusions>",
            "        <exclusion>",
            "          <groupId>org.opentest4j</groupId>",
            "          <artifactId>opentest4j</artifactId>",
            "        </exclusion>",
            "      </exclusions>",
            "    </dependency>",
            "    <dependency>",
            "      <artifactId>bare</artifactId>",
            "    </dependency>",
            "  </dependencies>",
            "  <profiles>",
            "    <profile>",
            "      <id>ci</id>",
            "    </profile>",
            "  </profiles>",
            "  <build><plugins><plugin>",
            "    <artifactId>maven-surefire-plugin</artifactId>",
            "    <executions><execution><id>it</id></execution></executions>",
            "  </plugin></plugins></build>",
            "</project>");

    private static String label(String element, int start, int end) {
        return PomOutlineLabels.label(element, start, end, LINES.size(), LINES::get);
    }

    @Test
    void dependenciesAndExclusionsAreNamedByTheirCoordinates() {
        assertEquals("dependency org.junit.jupiter:junit-jupiter-api", label("dependency", 2, 11));
        assertEquals("exclusion org.opentest4j:opentest4j", label("exclusion", 6, 9));
        assertEquals("dependency bare", label("dependency", 12, 14), "no groupId: the artifactId alone");
    }

    @Test
    void profilesAndExecutionsAreNamedByTheirId() {
        assertEquals("profile ci", label("profile", 17, 19));
        assertEquals("execution it", label("execution", 23, 23));
    }

    @Test
    void aPluginWithoutAGroupIdIsNamedByItsArtifactIdAlone() {
        assertEquals("plugin maven-surefire-plugin", label("plugin", 21, 24));
    }

    @Test
    void otherElementsKeepTheirName() {
        assertEquals("dependencies", label("dependencies", 1, 15));
        assertEquals("project", label("project", 0, 25));
        assertNull(label(null, 0, 1));
    }

    @Test
    void anUnknownEndScansABoundedWindowOnly() {
        assertEquals("dependency org.junit.jupiter:junit-jupiter-api", label("dependency", 2, -1));
        assertEquals("profile", label("profile", 25, -1), "past the end: nothing found, no exception");
    }
}
