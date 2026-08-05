package com.editora.maven;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchetypeGenerateTest {

    private static MavenProjectSpec spec(MavenArchetype archetype) {
        return new MavenProjectSpec(
                archetype, "com.example", "demo", "1.0-SNAPSHOT", "com.example.demo", Path.of("/tmp/work"));
    }

    private static MavenArchetype quickstart() {
        return new MavenArchetype("org.apache.maven.archetypes", "maven-archetype-quickstart", "1.5", "", "", true);
    }

    @Test
    void buildsTheWholeCommandLine() {
        assertEquals(
                List.of(
                        "mvn",
                        "archetype:generate",
                        "-B",
                        "-DinteractiveMode=false",
                        "-DarchetypeGroupId=org.apache.maven.archetypes",
                        "-DarchetypeArtifactId=maven-archetype-quickstart",
                        "-DarchetypeVersion=1.5",
                        "-DgroupId=com.example",
                        "-DartifactId=demo",
                        "-Dversion=1.0-SNAPSHOT",
                        "-Dpackage=com.example.demo"),
                ArchetypeGenerate.argv(List.of("mvn"), spec(quickstart())));
    }

    /**
     * The reason this class exists. archetype:generate prompts on stdin by default, and it is driven through
     * a runner with NO stdin and NO timeout — so losing either flag does not fail the build, it hangs the
     * process forever behind a silent Output tab.
     */
    @Test
    void alwaysPassesBothBatchFlags() {
        List<String> argv = ArchetypeGenerate.argv(List.of("mvn"), spec(quickstart()));
        assertTrue(argv.contains("-B"), "batch mode");
        assertTrue(argv.contains("-DinteractiveMode=false"), "older archetype plugins consult this instead");
    }

    @Test
    void honoursTheResolvedMavenExecutable() {
        // A Settings override or a wrapper can be several tokens; all of them must lead.
        List<String> argv = ArchetypeGenerate.argv(List.of("/opt/maven/bin/mvn", "-o"), spec(quickstart()));
        assertEquals("/opt/maven/bin/mvn", argv.get(0));
        assertEquals("-o", argv.get(1));
        assertEquals("archetype:generate", argv.get(2));
    }

    @Test
    void passesArchetypeRepositoryOnlyWhenTheCatalogGaveOne() {
        MavenArchetype withRepo =
                new MavenArchetype("org.example", "custom", "1.0", "", "https://repo.example.com/maven2", false);
        assertTrue(ArchetypeGenerate.argv(List.of("mvn"), spec(withRepo))
                .contains("-DarchetypeRepository=https://repo.example.com/maven2"));
        assertTrue(ArchetypeGenerate.argv(List.of("mvn"), spec(quickstart())).stream()
                .noneMatch(s -> s.startsWith("-DarchetypeRepository")));
    }

    @Test
    void refusesAnInvalidSpecRatherThanLaunchingMaven() {
        MavenProjectSpec bad =
                new MavenProjectSpec(quickstart(), "com example", "demo", "1.0", "com.example.demo", Path.of("/tmp"));
        assertThrows(IllegalArgumentException.class, () -> ArchetypeGenerate.argv(List.of("mvn"), bad));
        assertThrows(IllegalArgumentException.class, () -> ArchetypeGenerate.argv(List.of(), spec(quickstart())));
    }

    @Test
    void specReportsTheFirstProblemAsAKey() {
        assertEquals(
                "groupId", new MavenProjectSpec(quickstart(), "", "demo", "1.0", "p", Path.of("/tmp")).firstProblem());
        assertEquals(
                "artifactId",
                new MavenProjectSpec(quickstart(), "com.example", "", "1.0", "p", Path.of("/tmp")).firstProblem());
        assertEquals(
                "archetype",
                new MavenProjectSpec(null, "com.example", "demo", "1.0", "p", Path.of("/tmp")).firstProblem());
        assertEquals(
                "location", new MavenProjectSpec(quickstart(), "com.example", "demo", "1.0", "p", null).firstProblem());
    }

    @Test
    void specDerivesTheProjectDirectoryMavenWillCreate() {
        assertEquals(Path.of("/tmp/work/demo"), spec(quickstart()).projectDir());
    }
}
