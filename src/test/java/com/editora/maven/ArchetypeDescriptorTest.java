package com.editora.maven;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An archetype's required properties, read from its descriptor — and from its jar in the local repository. */
class ArchetypeDescriptorTest {

    /** javafx-archetype-simple 0.0.6's, verbatim but for whitespace. */
    private static final String JAVAFX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <archetype-descriptor xmlns="http://maven.apache.org/plugins/maven-archetype-plugin/archetype-descriptor/1.0.0"
                name="${rootArtifactId}-parent">
                <requiredProperties>
                    <requiredProperty key="javafx-version">
                        <defaultValue>13</defaultValue>
                    </requiredProperty>
                    <requiredProperty key="javafx-maven-plugin-version">
                        <defaultValue>0.0.6</defaultValue>
                    </requiredProperty>
                    <requiredProperty key="add-debug-configuration">
                        <defaultValue>N</defaultValue>
                    </requiredProperty>
                </requiredProperties>
                <fileSets><fileSet filtered="true"><directory>src/main/java</directory></fileSet></fileSets>
            </archetype-descriptor>
            """;

    /** quickstart 1.5's shape: the Java release as a property, plus a property with no default and a reserved one. */
    private static final String QUICKSTART = """
            <archetype-descriptor name="quickstart">
              <requiredProperties>
                <requiredProperty key="javaCompilerVersion"><defaultValue>17</defaultValue></requiredProperty>
                <requiredProperty key="junitVersion"><defaultValue>5.11.0</defaultValue></requiredProperty>
                <requiredProperty key="companyName"/>
                <requiredProperty key="package"><defaultValue>com.example</defaultValue></requiredProperty>
              </requiredProperties>
            </archetype-descriptor>
            """;

    @Test
    void parsesKeysAndDefaults() {
        ArchetypeDescriptor d = ArchetypeDescriptor.parse(JAVAFX);
        assertEquals(
                List.of(
                        new ArchetypeDescriptor.RequiredProperty("javafx-version", "13"),
                        new ArchetypeDescriptor.RequiredProperty("javafx-maven-plugin-version", "0.0.6"),
                        new ArchetypeDescriptor.RequiredProperty("add-debug-configuration", "N")),
                d.requiredProperties());
        assertFalse(d.takesJavaRelease());
        assertEquals(d.requiredProperties(), d.extraProperties());
    }

    @Test
    void theJavaReleaseHasItsOwnRowAndReservedKeysAreNotAsked() {
        ArchetypeDescriptor d = ArchetypeDescriptor.parse(QUICKSTART);
        assertTrue(d.takesJavaRelease());
        assertEquals(
                List.of(
                        new ArchetypeDescriptor.RequiredProperty("junitVersion", "5.11.0"),
                        new ArchetypeDescriptor.RequiredProperty("companyName", null)),
                d.extraProperties());
        assertFalse(d.extraProperties().get(1).hasDefault());
    }

    @Test
    void anUnreadableDescriptorIsNone() {
        assertSame(ArchetypeDescriptor.NONE, ArchetypeDescriptor.parse(null));
        assertSame(ArchetypeDescriptor.NONE, ArchetypeDescriptor.parse("<archetype-descriptor><requiredProperties>"));
        assertEquals(
                List.of(),
                ArchetypeDescriptor.parse("<archetype-descriptor name=\"x\"/>").requiredProperties());
    }

    @Test
    void readsTheDescriptorFromTheJarInTheLocalRepository(@TempDir Path repo) throws Exception {
        MavenArchetype archetype = new MavenArchetype("org.openjfx", "javafx-archetype-simple", "0.0.6", "", "", true);
        Path jar = MavenPluginGoals.jarPath(repo, "org.openjfx", "javafx-archetype-simple", "0.0.6");
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("META-INF/maven/archetype-metadata.xml"));
            zip.write(JAVAFX.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        ArchetypeDescriptor d = ArchetypeDescriptor.readFromLocalRepository(repo, archetype);
        assertEquals("13", d.requiredProperties().get(0).defaultValue());

        // Not downloaded yet: nothing to show, the defaults apply as they always did.
        MavenArchetype absent = new MavenArchetype("org.openjfx", "javafx-archetype-fxml", "0.0.6", "", "", true);
        assertSame(ArchetypeDescriptor.NONE, ArchetypeDescriptor.readFromLocalRepository(repo, absent));
        assertSame(ArchetypeDescriptor.NONE, ArchetypeDescriptor.readFromLocalRepository(null, archetype));
    }

    @Test
    void extrasPassTheJavaReleaseAsAPropertyWhenTheArchetypeTakesItThatWay() {
        MavenProjectExtras extras = new MavenProjectExtras("https://x", "21", false, Map.of("junitVersion", "5.11.0"));
        MavenProjectExtras forQuickstart = extras.forArchetype(ArchetypeDescriptor.parse(QUICKSTART));
        assertEquals(Map.of("junitVersion", "5.11.0", "javaCompilerVersion", "21"), forQuickstart.properties());
        assertEquals("", forQuickstart.javaRelease(), "set once, by the archetype — no pom edit afterwards");
        assertTrue(forQuickstart.editsPom(), "the URL is still a pom edit");

        // An archetype without the property keeps the pom edit as the fallback.
        assertSame(extras, extras.forArchetype(ArchetypeDescriptor.parse(JAVAFX)));
        assertSame(extras, extras.forArchetype(null));
        MavenProjectExtras noRelease = new MavenProjectExtras("", "", true);
        assertSame(noRelease, noRelease.forArchetype(ArchetypeDescriptor.parse(QUICKSTART)));
        assertNull(null);
    }

    @Test
    void blankPropertyValuesAreDropped() {
        MavenProjectExtras extras = new MavenProjectExtras("", "", false, Map.of("a", " 1 ", "b", "  ", "c", "x"));
        assertEquals(Map.of("a", "1", "c", "x"), extras.properties());
        assertFalse(extras.isNoop());
        assertTrue(MavenProjectExtras.NONE.isNoop());
    }
}
