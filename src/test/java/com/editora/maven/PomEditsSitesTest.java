package com.editora.maven;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The version-site reading behind Update Versions: every dependency and plugin, however its version is written. */
class PomEditsSitesTest {

    private static final String POM = """
            <project>
              <parent>
                <groupId>org.example</groupId>
                <artifactId>parent</artifactId>
                <version>1.2.3</version>
                <relativePath>../parent/pom.xml</relativePath>
              </parent>
              <artifactId>child</artifactId>
              <properties>
                <!-- a comment inside properties -->
                <junit.version>5.10.0</junit.version>
                <empty></empty>
              </properties>
              <dependencies>
                <dependency>
                  <groupId>org.junit</groupId>
                  <artifactId>junit-bom</artifactId>
                  <version>5.10.0</version>
                  <type>pom</type>
                  <scope>import</scope>
                </dependency>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId>
                  <version>${junit.version}</version>
                </dependency>
                <dependency>
                  <groupId>com.google.guava</groupId>
                  <artifactId>guava</artifactId>
                </dependency>
              </dependencies>
              <build>
                <plugins>
                  <plugin>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.11.0</version>
                    <dependencies>
                      <dependency>
                        <groupId>org.ow2.asm</groupId>
                        <artifactId>asm</artifactId>
                        <version>9.7</version>
                      </dependency>
                    </dependencies>
                  </plugin>
                </plugins>
              </build>
            </project>
            """;

    @Test
    void everySiteInDocumentOrderWithItsVersionAsWritten() {
        List<PomEdits.VersionSite> sites = PomEdits.versionSites(POM);
        assertEquals(
                List.of(
                        new PomEdits.VersionSite("dependency", "org.junit:junit-bom", "5.10.0", true),
                        new PomEdits.VersionSite(
                                "dependency", "org.junit.jupiter:junit-jupiter", "${junit.version}", false),
                        new PomEdits.VersionSite("dependency", "com.google.guava:guava", "", false),
                        new PomEdits.VersionSite(
                                "plugin", "org.apache.maven.plugins:maven-compiler-plugin", "3.11.0", false),
                        new PomEdits.VersionSite("dependency", "org.ow2.asm:asm", "9.7", false)),
                sites);
        assertEquals("junit.version", sites.get(1).propertyName());
        assertTrue(sites.get(1).isPropertyDriven());
        assertTrue(sites.get(2).isInherited());
        assertTrue(sites.get(3).isPlugin());
        assertNull(new PomEdits.VersionSite("dependency", "g:a", "${a}-${b}", false).propertyName());
    }

    @Test
    void theParentAndItsRelativePath() {
        assertArrayEquals(new String[] {"org.example", "parent", "1.2.3"}, PomEdits.parent(POM));
        assertEquals("../parent/pom.xml", PomEdits.parentRelativePath(POM));
        assertNull(PomEdits.parent("<project><artifactId>x</artifactId></project>"));
        assertNull(PomEdits.parentRelativePath(
                "<project><parent><groupId>g</groupId><artifactId>a</artifactId></parent></project>"));
        assertEquals(
                "",
                PomEdits.parentRelativePath(
                        "<project><parent><groupId>g</groupId><artifactId>a</artifactId><relativePath/></parent></project>"));
    }

    @Test
    void setParentVersionTouchesOnlyTheParentsVersion() {
        String out = PomEdits.setParentVersion(POM, "2.0.0");
        assertTrue(out.contains("<artifactId>parent</artifactId>\n    <version>2.0.0</version>"), out);
        assertTrue(out.contains("<version>5.10.0</version>"), "the bom's version is not the parent's");
        assertEquals(POM, PomEdits.setParentVersion(POM, ""));
        String orphan = "<project><artifactId>x</artifactId><version>1</version></project>";
        assertEquals(
                orphan, PomEdits.setParentVersion(orphan, "2"), "no parent: the project's own version is not touched");
    }

    @Test
    void propertiesAndReferenceCounts() {
        assertEquals(Map.of("junit.version", "5.10.0", "empty", ""), PomEdits.properties(POM));
        assertEquals(1, PomEdits.referenceCount(POM, "junit.version"));
        assertEquals(0, PomEdits.referenceCount(POM, "nope"));
        assertEquals(Map.of(), PomEdits.properties("<project/>"));
    }
}
