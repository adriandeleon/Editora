package com.editora.maven;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How the fallback plans a module's resolution on a real folder layout (reviews D5, D13). */
class MavenClasspathResolverPlanTest {

    private static final List<String> MVN = List.of("mvn");

    @Test
    void aModuleUnderAFolderWithoutAPomBuildsFromTheReactorThatListsIt(@TempDir Path repo) throws Exception {
        Files.writeString(
                repo.resolve("pom.xml"),
                "<project><artifactId>root</artifactId><packaging>pom</packaging>"
                        + "<modules><module>modules/a</module><module>modules/b</module></modules></project>");
        Path b = Files.createDirectories(repo.resolve("modules").resolve("b"));
        Files.writeString(b.resolve("pom.xml"), "<project><artifactId>b</artifactId></project>");

        MavenClasspathResolver.Plan plan =
                MavenClasspathResolver.plan(b, repo, MVN, List.of("-Pdev"), MavenClasspath.Scope.RUNTIME);
        try {
            assertTrue(plan.multiModule());
            assertEquals(repo.toRealPath(), plan.workingDirectory().toRealPath());
            assertEquals("modules/b", plan.reactor().moduleSelector());
            assertTrue(
                    plan.argv().containsAll(List.of("-pl", "modules/b", "-am", "-Pdev")),
                    plan.argv().toString());
            assertTrue(Files.exists(plan.outputFile()), "the scratch file is made up front");

            // Maven's reactor disagreed: the retry is the same command in the module alone, same scratch file.
            MavenClasspathResolver.Plan alone = plan.singleModule();
            assertFalse(alone.multiModule());
            assertEquals(b, alone.workingDirectory());
            assertEquals(plan.outputFile(), alone.outputFile());
            assertFalse(alone.argv().contains("-pl"), alone.argv().toString());
            assertTrue(alone.argv().contains("-Pdev"), "the profiles stay");
            assertEquals(alone, alone.singleModule(), "already alone");
        } finally {
            MavenClasspathResolver.discard(plan);
        }
        assertFalse(Files.exists(plan.outputFile()));
    }

    @Test
    void aProjectUnderAnUnrelatedPomBuildsAlone(@TempDir Path repo) throws Exception {
        Files.writeString(
                repo.resolve("pom.xml"),
                "<project><artifactId>other</artifactId><modules><module>core</module></modules></project>");
        Path demo = Files.createDirectories(repo.resolve("samples").resolve("demo"));
        Files.writeString(
                repo.resolve("samples").resolve("pom.xml"), "<project><artifactId>samples</artifactId></project>");
        Files.writeString(demo.resolve("pom.xml"), "<project><artifactId>demo</artifactId></project>");

        MavenClasspathResolver.Plan plan =
                MavenClasspathResolver.plan(demo, null, MVN, null, MavenClasspath.Scope.TEST);
        try {
            assertFalse(plan.multiModule());
            assertNull(plan.reactor());
            assertEquals(demo, plan.workingDirectory());
            assertEquals("test-compile", plan.argv().get(2), plan.argv().toString());
            assertEquals(List.of(), plan.toggleArgs());
        } finally {
            MavenClasspathResolver.discard(plan);
        }
    }

    @Test
    void finishReadsTheClasspathTheCommandWroteAndDropsTheFile(@TempDir Path module) throws Exception {
        Files.writeString(module.resolve("pom.xml"), "<project><artifactId>m</artifactId></project>");
        MavenClasspathResolver.Plan plan = MavenClasspathResolver.plan(module, MVN, MavenClasspath.Scope.RUNTIME);
        Files.writeString(plan.outputFile(), "/repo/a.jar" + java.io.File.pathSeparator + "/repo/b.jar");
        assertEquals(
                List.of(module.resolve("target").resolve("classes").toString(), "/repo/a.jar", "/repo/b.jar"),
                MavenClasspathResolver.finish(plan));
        assertFalse(Files.exists(plan.outputFile()));
        assertNull(MavenClasspathResolver.finish(plan), "nothing written: the step failed before the goal ran");
    }
}
