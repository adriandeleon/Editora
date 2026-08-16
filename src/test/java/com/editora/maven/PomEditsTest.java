package com.editora.maven;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Format-preserving pom edits. What matters throughout is what is left <em>un</em>changed. */
class PomEditsTest {

    /** Close to what maven-archetype-quickstart:1.5 actually writes, comments and all. */
    private static final String QUICKSTART = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>

              <groupId>com.example</groupId>
              <artifactId>demo</artifactId>
              <version>1.0-SNAPSHOT</version>

              <name>demo</name>
              <!-- FIXME change it to the project's website -->
              <url>http://www.example.com</url>

              <properties>
                <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                <maven.compiler.release>17</maven.compiler.release>
              </properties>

              <dependencies>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter-api</artifactId>
                  <version>5.11.0</version>
                  <scope>test</scope>
                </dependency>
              </dependencies>

              <build>
                <pluginManagement>
                  <plugins>
                    <plugin>
                      <artifactId>maven-compiler-plugin</artifactId>
                      <version>3.13.0</version>
                    </plugin>
                    <plugin>
                      <artifactId>maven-surefire-plugin</artifactId>
                      <version>3.3.0</version>
                    </plugin>
                  </plugins>
                </pluginManagement>
              </build>
            </project>
            """;

    // --- url -------------------------------------------------------------------------------------

    @Test
    void replacesTheProjectUrl() {
        String out = PomEdits.setProjectUrl(QUICKSTART, "https://example.org/demo");
        assertTrue(out.contains("<url>https://example.org/demo</url>"));
        assertTrue(out.contains("<!-- FIXME change it to the project's website -->"), "comments must survive");
        assertEquals(QUICKSTART.lines().count(), out.lines().count(), "a replace must not change the shape");
    }

    /** A {@code <url>} nested in scm or a repository is not the project's own. */
    @Test
    void doesNotTouchANestedUrl() {
        String pom = """
                <project>
                  <artifactId>demo</artifactId>
                  <scm>
                    <url>https://github.com/x/y</url>
                  </scm>
                </project>
                """;
        String out = PomEdits.setProjectUrl(pom, "https://example.org");
        assertTrue(out.contains("<url>https://github.com/x/y</url>"), "the scm url was rewritten");
        assertTrue(out.contains("<url>https://example.org</url>"), "the project url was not added");
    }

    @Test
    void insertsTheUrlAfterNameWhenAbsent() {
        String pom = """
                <project>
                  <artifactId>demo</artifactId>
                  <name>demo</name>
                </project>
                """;
        String out = PomEdits.setProjectUrl(pom, "https://example.org");
        assertTrue(out.contains("  <name>demo</name>\n  <url>https://example.org</url>"), out);
    }

    // --- properties ------------------------------------------------------------------------------

    @Test
    void replacesAnExistingProperty() {
        String out = PomEdits.setProperty(QUICKSTART, "maven.compiler.release", "21");
        assertTrue(out.contains("<maven.compiler.release>21</maven.compiler.release>"));
        assertTrue(out.contains("<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>"));
    }

    @Test
    void addsAPropertyToAnExistingBlock() {
        String out = PomEdits.setProperty(QUICKSTART, "maven.compiler.source", "21");
        assertTrue(out.contains("    <maven.compiler.source>21</maven.compiler.source>\n  </properties>"), out);
    }

    @Test
    void createsThePropertiesBlockWhenThereIsNone() {
        String pom = """
                <project>
                  <artifactId>demo</artifactId>
                  <dependencies>
                  </dependencies>
                </project>
                """;
        String out = PomEdits.setProperty(pom, "maven.compiler.release", "21");
        assertTrue(out.contains("<properties>\n    <maven.compiler.release>21</maven.compiler.release>\n"), out);
        assertTrue(out.indexOf("<properties>") < out.indexOf("<dependencies>"), "conventional position");
    }

    // --- plugin versions -------------------------------------------------------------------------

    @Test
    void rewritesPluginVersionsByGroupAndArtifact() {
        Map<String, String> want = Map.of(
                "org.apache.maven.plugins:maven-compiler-plugin", "3.14.0",
                "org.apache.maven.plugins:maven-surefire-plugin", "3.5.2");
        String out = PomEdits.setPluginVersions(QUICKSTART, want);
        assertTrue(out.contains("<artifactId>maven-compiler-plugin</artifactId>\n          <version>3.14.0"), out);
        assertTrue(out.contains("<artifactId>maven-surefire-plugin</artifactId>\n          <version>3.5.2"), out);
    }

    /** A dependency's version must never be mistaken for a plugin's. */
    @Test
    void leavesDependencyVersionsAlone() {
        String out = PomEdits.setPluginVersions(QUICKSTART, Map.of("org.junit.jupiter:junit-jupiter-api", "9.9.9"));
        assertTrue(out.contains("<version>5.11.0</version>"), "the junit dependency was rewritten as a plugin");
    }

    @Test
    void aPluginWithoutAVersionIsLeftAlone() {
        String pom = """
                <project>
                  <build>
                    <plugins>
                      <plugin>
                        <artifactId>maven-jar-plugin</artifactId>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """;
        String out = PomEdits.setPluginVersions(pom, Map.of("org.apache.maven.plugins:maven-jar-plugin", "3.4.1"));
        assertEquals(pom, out, "its version comes from elsewhere — pinning it would change resolution");
    }

    /** Rewriting several plugins at once must not corrupt the offsets of the ones before them. */
    @Test
    void rewritingSeveralAtOnceKeepsThemAllCorrect() {
        String out = PomEdits.setPluginVersions(
                QUICKSTART,
                Map.of(
                        "org.apache.maven.plugins:maven-compiler-plugin", "3.14.0-with-a-much-longer-version",
                        "org.apache.maven.plugins:maven-surefire-plugin", "3.5.2"));
        assertTrue(out.contains("<version>3.14.0-with-a-much-longer-version</version>"), out);
        assertTrue(out.contains("<version>3.5.2</version>"), out);
    }

    @Test
    void readsTheCurrentPluginVersions() {
        assertEquals(
                Map.of(
                        "org.apache.maven.plugins:maven-compiler-plugin", "3.13.0",
                        "org.apache.maven.plugins:maven-surefire-plugin", "3.3.0"),
                PomEdits.pluginVersions(QUICKSTART));
    }

    /** A plugin with no version has nothing to compare against, so it is not reported. */
    @Test
    void pluginVersionsOmitsUnpinnedPlugins() {
        String pom = """
                <project><build><plugins>
                  <plugin><artifactId>maven-jar-plugin</artifactId></plugin>
                </plugins></build></project>
                """;
        assertTrue(PomEdits.pluginVersions(pom).isEmpty());
    }

    // --- dependencies ----------------------------------------------------------------------------

    @Test
    void readsAndRewritesDependencyVersions() {
        assertEquals(Map.of("org.junit.jupiter:junit-jupiter-api", "5.11.0"), PomEdits.dependencyVersions(QUICKSTART));
        String out =
                PomEdits.setDependencyVersions(QUICKSTART, Map.of("org.junit.jupiter:junit-jupiter-api", "5.13.0"));
        assertTrue(out.contains("<version>5.13.0</version>"), out);
        assertTrue(out.contains("<version>3.13.0</version>"), "a plugin version must not be touched");
    }

    /** A property reference is the indirection the author chose — replacing it is a different edit. */
    @Test
    void skipsPropertyDrivenDependencyVersions() {
        String pom = """
                <project><dependencies><dependency>
                  <groupId>g</groupId><artifactId>a</artifactId><version>${a.version}</version>
                </dependency></dependencies></project>
                """;
        assertTrue(PomEdits.dependencyVersions(pom).isEmpty());
    }

    /** A dependency with no groupId is malformed, not an implicit org.apache.maven.plugins. */
    @Test
    void aDependencyWithoutAGroupIsIgnored() {
        String pom = """
                <project><dependencies><dependency>
                  <artifactId>a</artifactId><version>1.0</version>
                </dependency></dependencies></project>
                """;
        assertTrue(PomEdits.dependencyVersions(pom).isEmpty());
    }

    // --- packaging -------------------------------------------------------------------------------

    @Test
    void readsThePackaging() {
        assertEquals("pom", PomEdits.packaging("<project><packaging>pom</packaging></project>"));
    }

    /** No element means jar, as Maven itself defaults — not "unknown". */
    @Test
    void packagingDefaultsToJar() {
        assertEquals("jar", PomEdits.packaging(QUICKSTART));
        assertEquals("jar", PomEdits.packaging("<project><packaging>  </packaging></project>"));
    }

    /** Null distinguishes "no project here" from "a project that did not say", which decide differently. */
    @Test
    void packagingIsNullWithoutAProjectRoot() {
        assertNull(PomEdits.packaging(""));
        assertNull(PomEdits.packaging(null));
    }

    // --- leaving things alone --------------------------------------------------------------------

    @Test
    void aBlankValueChangesNothing() {
        assertSame(QUICKSTART, PomEdits.setProjectUrl(QUICKSTART, "  "));
        assertSame(QUICKSTART, PomEdits.setProperty(QUICKSTART, "maven.compiler.release", null));
        assertSame(QUICKSTART, PomEdits.setPluginVersions(QUICKSTART, Map.of()));
    }

    @Test
    void escapesCharactersThatWouldEndTheElement() {
        String out = PomEdits.setProjectUrl(QUICKSTART, "https://example.org/?a=1&b=2");
        assertTrue(out.contains("<url>https://example.org/?a=1&amp;b=2</url>"), out);
    }

    @Test
    void survivesAPomItCannotUnderstand() {
        assertEquals("not xml at all", PomEdits.setProjectUrl("not xml at all", "https://example.org"));
        assertEquals("<project>", PomEdits.setProperty("<project>", "a", "b"));
    }
}
