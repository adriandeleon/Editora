package com.editora.container;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A file only the container has — in Editora's working folder there — keeps its name through
 * {@link ContainerExecTarget#toPath} and back, on every OS (a driveless {@code \root\…} on Windows).
 */
class ContainerOwnPathsTest {

    @Test
    void aContainersOwnFileKeepsItsNameBothWays(@TempDir Path folder) {
        ContainerExecTarget target = new ContainerExecTarget(
                ContainerEngine.of("docker"),
                new ContainerInfo(
                        "id",
                        "app",
                        true,
                        new ContainerMounts(List.of(new ContainerMounts.Mount(folder, "/workspaces/app"))),
                        "",
                        Map.of()));

        for (String inside : List.of(
                "/root/.cache/editora/debug-classes/1f/Hello.java", "/home/vscode/.cache/editora/java-debug/x.jar")) {
            assertEquals(inside, target.toTargetPath(target.toPath(inside)));
        }
        assertEquals(
                "/workspaces/app/src/A.java",
                target.toTargetPath(folder.resolve("src").resolve("A.java")));
        assertEquals(folder.resolve("src").resolve("A.java"), target.toPath("/workspaces/app/src/A.java"));
    }
}
