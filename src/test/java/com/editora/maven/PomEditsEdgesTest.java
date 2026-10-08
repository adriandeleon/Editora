package com.editora.maven;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The poms the text-level editor has to get right without a parser: odd shapes, and inputs that are not poms. */
class PomEditsEdgesTest {

    @Test
    void nothingToEditOrNothingToWriteReturnsThePomUnchanged() {
        String pom = "<project><artifactId>a</artifactId></project>";
        assertNull(PomEdits.setProjectUrl(null, "https://x"));
        assertSame(pom, PomEdits.setProjectUrl(pom, null));
        assertSame(pom, PomEdits.setProjectUrl(pom, "  "));
        assertEquals("  ", PomEdits.setProjectUrl("  ", "https://x"));
        assertSame(pom, PomEdits.setProperty(pom, null, "1"));
        assertSame(pom, PomEdits.setProperty(pom, "p", " "));
        assertNull(PomEdits.setProperty(null, "p", "1"));
        assertSame(pom, PomEdits.setPluginVersions(pom, null));
        assertSame(pom, PomEdits.setPluginVersions(pom, Map.of()));
        assertNull(PomEdits.setDependencyVersions(null, Map.of("a:b", "1")));
        assertEquals(Map.of(), PomEdits.pluginVersions(null));
        assertEquals(Map.of(), PomEdits.pluginVersions(" "));
        assertEquals(Map.of(), PomEdits.dependencyVersions(""));
        assertNull(PomEdits.packaging(" "));
    }

    @Test
    void textWithoutAProjectElementIsLeftAlone() {
        for (String notAPom : new String[] {"just text", "<project", "<>", "</project>"}) {
            assertEquals(notAPom, PomEdits.setProjectUrl(notAPom, "https://x"), notAPom);
            assertEquals(notAPom, PomEdits.setProperty(notAPom, "p", "1"), notAPom);
            assertNull(PomEdits.packaging(notAPom), notAPom);
        }
    }

    @Test
    void theUrlGoesAfterTheBestAnchorThereIs() {
        assertEquals(
                "<project>\n  <version>1</version>\n  <url>https://x</url>\n</project>",
                PomEdits.setProjectUrl("<project>\n  <version>1</version>\n</project>", "https://x"));
        assertEquals(
                "<project>\n    <artifactId>a</artifactId>\n    <url>https://x</url>\n</project>",
                PomEdits.setProjectUrl("<project>\n    <artifactId>a</artifactId>\n</project>", "https://x"));
        // Nothing to hang it after: no guess is made.
        String bare = "<project>\n  <modules/>\n</project>";
        assertEquals(bare, PomEdits.setProjectUrl(bare, "https://x"));
    }

    @Test
    void aPropertiesBlockIsCreatedBeforeDependenciesElseAtTheEnd() {
        assertEquals(
                "<project>\n  <properties>\n    <p>1</p>\n  </properties>\n  <dependencies/>\n</project>",
                PomEdits.setProperty("<project>\n  <dependencies/>\n</project>", "p", "1"));
        String out = PomEdits.setProperty("<project>\n  <artifactId>a</artifactId>\n</project>", "p", "1");
        assertTrue(out.contains("<properties>\n    <p>1</p>\n  </properties>"), out);
        assertTrue(out.indexOf("<artifactId>") < out.indexOf("<properties>"), out);
        assertTrue(out.endsWith("</project>"), out);
    }

    @Test
    void aPropertyIsAddedToAnEmptyOrSelfClosedBlock() {
        String empty = PomEdits.setProperty("<project>\n  <properties>\n  </properties>\n</project>", "p", "1");
        assertTrue(empty.contains("<p>1</p>"), empty);
        assertEquals("1", PomEdits.setProperty(empty, "p", "1").split("<p>")[1].split("</p>")[0]);
        // Existing entries set the indentation of the new one.
        assertEquals(
                "<project>\n  <properties>\n      <a>1</a>\n      <b>2</b>\n  </properties>\n</project>",
                PomEdits.setProperty(
                        "<project>\n  <properties>\n      <a>1</a>\n  </properties>\n</project>", "b", "2"));
    }

    @Test
    void commentsProcessingInstructionsAndCdataAreSteppedOver() {
        String pom = """
                <?xml version="1.0"?>
                <!-- <project><packaging>war</packaging></project> -->
                <!DOCTYPE project>
                <project attr="a>b" other='c>d'>
                  <!-- <packaging>ear</packaging> -->
                  <description><![CDATA[ <packaging>pom</packaging> ]]></description>
                  <packaging> bundle </packaging>
                </project>
                """;
        assertEquals("bundle", PomEdits.packaging(pom));
        assertEquals("jar", PomEdits.packaging("<project><packaging>  </packaging></project>"));
        assertEquals("jar", PomEdits.packaging("<project><packaging/></project>"));
    }

    @Test
    void anUnterminatedCommentOrElementEndsTheSearchQuietly() {
        // A project element that never closes is not a project to edit.
        assertNull(PomEdits.packaging("<project><!-- never closed <packaging>war</packaging>"));
        assertNull(PomEdits.packaging("<project><packaging>war"));
        assertNull(PomEdits.packaging("<project><name>x</name><broken"));
        assertEquals(Map.of(), PomEdits.pluginVersions("<project><plugin><artifactId>a</artifactId><version>1"));
        // Inside a closed project, a child that never closes hides only itself.
        assertEquals("jar", PomEdits.packaging("<project><name>x<packaging>war</packaging></project>"));
        assertNull(
                PomEdits.packaging("<project><!-- open <packaging>war</packaging></project>"),
                "the comment swallows the rest");
    }

    @Test
    void elementsNestedInOneOfTheSameNameAreMatchedToTheRightClose() {
        String pom = """
                <project>
                  <build><plugins>
                    <plugin>
                      <artifactId>outer</artifactId>
                      <configuration><plugin><artifactId>inner</artifactId><version>9</version></plugin></configuration>
                      <version>1</version>
                    </plugin>
                    <plugins-extra/>
                    <plugin />
                    <plugin><version>3</version></plugin>
                    <plugin><groupId>g</groupId><artifactId>versionless</artifactId><version> </version></plugin>
                  </plugins></build>
                </project>
                """;
        Map<String, String> versions = PomEdits.pluginVersions(pom);
        // Its own version, not the one of the element nested in its configuration; a plugin with no
        // artifactId, or with a blank version, is not listed.
        assertEquals(Map.of("org.apache.maven.plugins:outer", "1"), versions);

        String updated = PomEdits.setPluginVersions(
                pom, Map.of("org.apache.maven.plugins:outer", "2", "org.apache.maven.plugins:inner", " ", "x:y", "5"));
        assertTrue(updated.contains("<version>2</version>\n    </plugin>"), updated);
        assertTrue(updated.contains("<version>9</version>"), "a blank target version changes nothing");
        assertTrue(updated.contains("<plugin><version>3</version></plugin>"), updated);
    }

    @Test
    void dependenciesAreReadAndRewrittenByGroupAndArtifact() {
        String pom = """
                <project><dependencies>
                  <dependency><groupId>g</groupId><artifactId>a</artifactId><version>1.0</version></dependency>
                  <dependency><groupId>g</groupId><artifactId>b</artifactId><version>${b.version}</version></dependency>
                  <dependency><groupId>g</groupId><artifactId>c</artifactId></dependency>
                  <dependency><groupId>g</groupId><version>4.0</version></dependency>
                  <dependency><artifactId>orphan</artifactId><version>5.0</version></dependency>
                  <dependency><groupId>g</groupId><artifactId>d</artifactId><version></version></dependency>
                </dependencies></project>
                """;
        assertEquals(Map.of("g:a", "1.0"), PomEdits.dependencyVersions(pom));
        String out = PomEdits.setDependencyVersions(pom, Map.of("g:a", "2.0 & up", "g:c", "9", "g:b", "3"));
        assertTrue(out.contains("<artifactId>a</artifactId><version>2.0 &amp; up</version>"), out);
        assertTrue(
                out.contains("<artifactId>b</artifactId><version>3</version>"),
                "an explicit request replaces a property reference");
        assertTrue(out.contains("<artifactId>c</artifactId></dependency>"), "no version element: none is invented");
        assertTrue(out.contains("<artifactId>orphan</artifactId><version>5.0</version>"), out);
    }
}
