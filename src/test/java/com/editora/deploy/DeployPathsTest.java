package com.editora.deploy;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DeployPathsTest {

    private final Path local = Path.of("/home/me/proj").toAbsolutePath();
    private final Path remote = Path.of("/srv/app").toAbsolutePath(); // a stand-in for the host's path
    private final DeployPaths paths = new DeployPaths(local, remote);

    @Test
    void aProjectFileMapsToItsCopyAndBack() {
        assertEquals(remote.resolve("src/A.java"), paths.toHost(local.resolve("src/A.java")));
        assertEquals(remote, paths.toHost(local));
        assertEquals(local.resolve("src/A.java"), paths.toLocal(remote.resolve("src/A.java")));
        assertNull(paths.toHost(Path.of("/etc/passwd").toAbsolutePath()), "outside the project");
        assertNull(paths.toLocal(Path.of("/srv/other/x").toAbsolutePath()));
        assertNull(paths.toHost(local.resolve("../proj-other/x")), "a sibling that shares the prefix is outside");
    }

    @Test
    void anAbsoluteFieldInsideTheProjectBecomesRelative() {
        assertEquals("sub/run.sh", paths.hostField(local.resolve("sub/run.sh").toString()));
        assertEquals("", paths.hostField(local.toString()), "the root itself: blank means the root");
        assertEquals("sub", paths.hostField("sub"), "already relative: the same on both sides");
        assertEquals("/opt/tool", paths.hostField("/opt/tool"), "outside: left as written, a host path");
        assertEquals("", paths.hostField(""));
    }
}
