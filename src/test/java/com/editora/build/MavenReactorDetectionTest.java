package com.editora.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link BuildTool#MAVEN}'s detection of a module's reactor, on a real folder layout. */
class MavenReactorDetectionTest {

    private static final String ROOT = "<project><groupId>g</groupId><artifactId>root</artifactId><version>1</version>"
            + "<packaging>pom</packaging><modules><module>core</module><module>app</module></modules></project>";
    private static final String MODULE = "<project><parent><groupId>g</groupId><artifactId>root</artifactId>"
            + "<version>1</version></parent><artifactId>%s</artifactId></project>";

    private static MavenActionsProvider provider(BuildTool.Detected detected) {
        return (MavenActionsProvider) detected.provider();
    }

    @Test
    void aListedModuleGetsTheReactorToggleThatBuildsFromTheRoot(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"), ROOT);
        Path app = Files.createDirectories(project.resolve("app"));
        Files.writeString(app.resolve("pom.xml"), MODULE.formatted("app"));

        MavenActionsProvider p = provider(BuildTool.MAVEN.parse(app, project));
        assertNotNull(p.reactor());
        assertEquals("app", p.reactor().moduleSelector());
        assertEquals(
                List.of(
                        "-f",
                        project.toAbsolutePath().normalize().resolve("pom.xml").toString(),
                        "-pl",
                        "app",
                        "-am"),
                p.toggleArgs(Set.of(MavenActionsProvider.REACTOR_TOGGLE)));
    }

    @Test
    void theWalkStopsAtTheOpenProjectAndAnUnlistedFolderIsNoModule(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"), ROOT);
        Path app = Files.createDirectories(project.resolve("app"));
        Files.writeString(app.resolve("pom.xml"), MODULE.formatted("app"));
        Path sample = Files.createDirectories(project.resolve("samples").resolve("demo"));
        Files.writeString(project.resolve("samples").resolve("pom.xml"), ROOT);
        Files.writeString(sample.resolve("pom.xml"), MODULE.formatted("demo"));

        assertNull(provider(BuildTool.MAVEN.parse(app, app)).reactor(), "the open project is the module");
        assertNotNull(provider(BuildTool.MAVEN.parse(app)).reactor(), "no open project: unbounded, the root lists app");
        assertNull(provider(BuildTool.MAVEN.parse(sample, project)).reactor(), "samples/demo is not a module");
    }
}
