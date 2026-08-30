package com.editora.maven;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure coverage of the plugin-descriptor reader: the {@code plugin.xml} parse, the local-repository layout,
 *  and the "newest local version" fallback for a plugin whose pom declares no usable version. */
class MavenPluginGoalsTest {

    /** A descriptor shaped like a real one: mojos whose {@code <parameters>} carry their own descriptions. */
    private static final String DESCRIPTOR = """
            <plugin>
              <name>javafx-maven-plugin Maven Mojo</name>
              <description>The plugin-level blurb, which is not a goal description</description>
              <groupId>org.openjfx</groupId>
              <artifactId>javafx-maven-plugin</artifactId>
              <goalPrefix>javafx</goalPrefix>
              <mojos>
                <mojo>
                  <goal>jlink</goal>
                  <description>Builds a runtime image</description>
                  <parameters>
                    <parameter><name>async</name><description>A parameter blurb</description></parameter>
                  </parameters>
                </mojo>
                <mojo>
                  <goal>run</goal>
                  <parameters>
                    <parameter><name>mainClass</name><description>Another parameter blurb</description></parameter>
                  </parameters>
                </mojo>
              </mojos>
            </plugin>
            """;

    @Test
    void readsThePrefixAndEveryGoalInOrder() {
        MavenPluginGoals.Descriptor d = MavenPluginGoals.parseDescriptor(DESCRIPTOR);
        assertNotNull(d);
        assertEquals("javafx", d.goalPrefix());
        assertEquals(
                List.of("jlink", "run"),
                d.goals().stream().map(MavenPluginGoals.Goal::name).toList());
    }

    /**
     * The whole point of the direct-child reads: a mojo's {@code <parameters>} subtree is full of
     * {@code <description>} elements, so a descendant search would attach a parameter's prose to the goal —
     * and {@code javafx:run}, the goal this feature exists for, declares no description of its own.
     */
    @Test
    void aGoalNeverBorrowsItsParametersDescription() {
        MavenPluginGoals.Descriptor d = MavenPluginGoals.parseDescriptor(DESCRIPTOR);
        assertEquals("Builds a runtime image", d.goals().get(0).description());
        assertEquals("", d.goals().get(1).description(), "run declares none — not the parameter's blurb");
        assertFalse(
                d.goals().stream().anyMatch(g -> g.description().contains("plugin-level")),
                "nor the plugin-level blurb");
    }

    @Test
    void aDescriptorWeCannotNameGoalsForIsRefused() {
        assertNull(MavenPluginGoals.parseDescriptor(null));
        assertNull(MavenPluginGoals.parseDescriptor("   "));
        assertNull(MavenPluginGoals.parseDescriptor("<plugin><mojos></mojos>"), "malformed");
        assertNull(
                MavenPluginGoals.parseDescriptor("<plugin><mojos><mojo><goal>run</goal></mojo></mojos></plugin>"),
                "no goalPrefix — we could not spell the invocation");
        assertNull(MavenPluginGoals.parseDescriptor("<plugin><goalPrefix>x</goalPrefix><mojos/></plugin>"), "no goals");
    }

    @Test
    void duplicateGoalsCollapse() {
        MavenPluginGoals.Descriptor d = MavenPluginGoals.parseDescriptor("""
                <plugin><goalPrefix>x</goalPrefix><mojos>
                  <mojo><goal>run</goal></mojo><mojo><goal>run</goal></mojo>
                </mojos></plugin>
                """);
        assertEquals(1, d.goals().size());
    }

    @Test
    void descriptionsAreFlattenedAndCapped() {
        assertEquals("a b c", MavenPluginGoals.shortDescription("  a\n  b\tc "));
        assertEquals("use --bind-services", MavenPluginGoals.shortDescription("use <code>--bind-services</code>"));
        assertEquals("", MavenPluginGoals.shortDescription(null));
        String long1 = "x".repeat(500);
        assertTrue(MavenPluginGoals.shortDescription(long1).length() < 250);
        assertTrue(MavenPluginGoals.shortDescription(long1).endsWith("…"));
    }

    // --- local repository ------------------------------------------------------------------------

    @Test
    void settingsXmlCanOverrideTheLocalRepository() {
        assertEquals(
                "/srv/m2",
                MavenPluginGoals.localRepositoryFromSettings(
                        "<settings><localRepository>/srv/m2</localRepository></settings>", "/home/u"));
        assertEquals(
                "/home/u/.m2/repo",
                MavenPluginGoals.localRepositoryFromSettings(
                        "<settings><localRepository>${user.home}/.m2/repo</localRepository></settings>", "/home/u"));
    }

    @Test
    void anUnusableSettingsValueFallsBackRatherThanBuildingANonsensePath() {
        assertNull(MavenPluginGoals.localRepositoryFromSettings("<settings/>", "/home/u"), "not declared");
        assertNull(MavenPluginGoals.localRepositoryFromSettings("<settings", "/home/u"), "malformed");
        assertNull(MavenPluginGoals.localRepositoryFromSettings(null, "/home/u"));
        assertNull(
                MavenPluginGoals.localRepositoryFromSettings(
                        "<settings><localRepository>${env.M2}/r</localRepository></settings>", "/home/u"),
                "an unexpanded property must not become a literal path segment");
    }

    @Test
    void jarPathFollowsTheStandardRepositoryLayout() {
        Path repo = Path.of("/r");
        assertEquals(
                Path.of("/r/org/openjfx/javafx-maven-plugin/0.0.8/javafx-maven-plugin-0.0.8.jar"),
                MavenPluginGoals.jarPath(repo, "org.openjfx", "javafx-maven-plugin", "0.0.8"));
    }

    /** A pom is repository content, so a coordinate must never address a file outside the local repository. */
    @Test
    void aCoordinateThatCouldEscapeTheRepositoryIsRefused() {
        Path repo = Path.of("/r");
        assertNull(MavenPluginGoals.jarPath(repo, "..", "a", "1"));
        assertNull(MavenPluginGoals.jarPath(repo, "org.x", "../../etc/passwd", "1"));
        assertNull(MavenPluginGoals.jarPath(repo, "org.${x}", "a", "1"));
        assertNull(MavenPluginGoals.jarPath(repo, "", "a", "1"));
        assertNull(MavenPluginGoals.jarPath(null, "org.x", "a", "1"));
    }

    // --- reading a real jar ----------------------------------------------------------------------

    private static void writePluginJar(Path repo, String groupId, String artifactId, String version, String xml)
            throws IOException {
        Path dir = repo;
        for (String s : groupId.split("\\.")) {
            dir = dir.resolve(s);
        }
        dir = dir.resolve(artifactId).resolve(version);
        Files.createDirectories(dir);
        Path jar = dir.resolve(artifactId + "-" + version + ".jar");
        try (OutputStream os = Files.newOutputStream(jar);
                ZipOutputStream zip = new ZipOutputStream(os)) {
            zip.putNextEntry(new ZipEntry("META-INF/maven/plugin.xml"));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    @Test
    void readsGoalsOutOfTheJarInTheLocalRepository(@TempDir Path repo) throws IOException {
        writePluginJar(repo, "org.openjfx", "javafx-maven-plugin", "0.0.8", DESCRIPTOR);
        PomModel.Plugin plugin = new PomModel.Plugin("org.openjfx", "javafx-maven-plugin", "0.0.8", List.of());
        MavenPluginGoals.Descriptor d = MavenPluginGoals.read(plugin, repo);
        assertNotNull(d, "a plugin declared with no <executions> still offers goals");
        assertEquals(
                List.of("jlink", "run"),
                d.goals().stream().map(MavenPluginGoals.Goal::name).toList());
    }

    /**
     * A version left to a parent / {@code <pluginManagement>} / a property is what a real pom usually has.
     * Falling back to the newest locally-present version is safe: the descriptor only <em>names</em> goals.
     */
    @Test
    void anUnresolvableVersionFallsBackToTheNewestLocalOne(@TempDir Path repo) throws IOException {
        writePluginJar(repo, "org.openjfx", "javafx-maven-plugin", "0.0.8", DESCRIPTOR);
        writePluginJar(
                repo,
                "org.openjfx",
                "javafx-maven-plugin",
                "0.0.10",
                DESCRIPTOR.replace("<goal>run</goal>", "<goal>newest</goal>"));
        for (String declared : new String[] {"", "${javafx.plugin.version}"}) {
            MavenPluginGoals.Descriptor d = MavenPluginGoals.read(
                    new PomModel.Plugin("org.openjfx", "javafx-maven-plugin", declared, List.of()), repo);
            assertNotNull(d, declared);
            assertTrue(
                    d.goals().stream().anyMatch(g -> g.name().equals("newest")),
                    "0.0.10 > 0.0.8 numerically, not alphabetically");
        }
    }

    @Test
    void aPluginThatIsNotDownloadedContributesNothing(@TempDir Path repo) {
        assertNull(MavenPluginGoals.read(new PomModel.Plugin("org.x", "y-maven-plugin", "1.0", List.of()), repo));
        assertEquals(
                List.of(),
                MavenPluginGoals.readAll(
                        new PomModel(
                                "g",
                                "a",
                                "1",
                                "jar",
                                List.of(new PomModel.Plugin("org.x", "y-maven-plugin", "1.0", List.of())),
                                List.of()),
                        repo));
    }

    @Test
    void readAllKeepsPomOrderAndOneGroupPerPrefix(@TempDir Path repo) throws IOException {
        writePluginJar(repo, "org.openjfx", "javafx-maven-plugin", "1.0", DESCRIPTOR);
        writePluginJar(
                repo,
                "com.diffplug.spotless",
                "spotless-maven-plugin",
                "1.0",
                DESCRIPTOR.replace("<goalPrefix>javafx</goalPrefix>", "<goalPrefix>spotless</goalPrefix>"));
        PomModel model = new PomModel(
                "g",
                "a",
                "1",
                "jar",
                List.of(
                        new PomModel.Plugin("org.openjfx", "javafx-maven-plugin", "1.0", List.of()),
                        new PomModel.Plugin("com.diffplug.spotless", "spotless-maven-plugin", "1.0", List.of())),
                List.of());
        assertEquals(
                List.of("javafx", "spotless"),
                MavenPluginGoals.readAll(model, repo).stream()
                        .map(MavenPluginGoals.Descriptor::goalPrefix)
                        .toList());
    }
}
