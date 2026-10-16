package com.editora.container;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisabledOnOs(OS.WINDOWS) // POSIX paths on this side
class ContainerMountsTest {

    private final ContainerMounts mounts = new ContainerMounts(List.of(
            new ContainerMounts.Mount(Path.of("/home/ana/app"), "/workspaces/app/"),
            new ContainerMounts.Mount(Path.of("/home/ana/app/vendor"), "/opt/vendor"),
            new ContainerMounts.Mount(Path.of("/srv/data"), "/")));

    @Test
    void aFileUnderAMountHasANameOnBothSides() {
        assertEquals("/workspaces/app/src/A b.java", mounts.toContainer(Path.of("/home/ana/app/src/A b.java")));
        assertEquals("/workspaces/app", mounts.toContainer(Path.of("/home/ana/app")));
        assertEquals(Path.of("/home/ana/app/src/A b.java"), mounts.toLocal("/workspaces/app/src/A b.java"));
        assertEquals(Path.of("/home/ana/app"), mounts.toLocal("/workspaces/app"));
    }

    @Test
    void theDeepestMountAnswersForItsOwnFiles() {
        assertEquals("/opt/vendor/lib.jar", mounts.toContainer(Path.of("/home/ana/app/vendor/lib.jar")));
        assertEquals(Path.of("/home/ana/app/vendor/lib.jar"), mounts.toLocal("/opt/vendor/lib.jar"));
    }

    @Test
    void aMountAtTheRootAndPathsOnOneSideOnly() {
        assertEquals("/x/y", mounts.toContainer(Path.of("/srv/data/x/y")));
        assertEquals(Path.of("/srv/data/usr/lib"), mounts.toLocal("/usr/lib"), "the root mount covers the rest");
        ContainerMounts noRoot = new ContainerMounts(List.of(new ContainerMounts.Mount(Path.of("/a"), "/w")));
        assertNull(noRoot.toContainer(Path.of("/b/c")));
        assertNull(noRoot.toLocal("/usr/lib/jvm"));
        assertNull(noRoot.toLocal("relative/path"));
        assertNull(noRoot.toLocal("/workspaces"), "a prefix of a mount's name is not in it");
        assertTrue(noRoot.covers(Path.of("/a/b")));
        assertFalse(noRoot.covers(Path.of("/ab")));
    }
}
