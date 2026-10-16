package com.editora.wsl;

import java.nio.file.Path;
import java.util.List;

import com.editora.process.ExecTarget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How Windows and a WSL distribution name the same files ({@link WslPaths}, {@link WslTargets}). */
class WslPathsTest {

    @Test
    void aFolderInsideTheDistributionIsItsOwnPathInside() {
        assertEquals("/home/ana/app", WslPaths.toLinux("\\\\wsl.localhost\\Ubuntu\\home\\ana\\app", "Ubuntu"));
        assertEquals("/home/ana", WslPaths.toLinux("\\\\WSL$\\ubuntu\\home\\ana", "Ubuntu"), "the old share, any case");
        assertEquals("/", WslPaths.toLinux("\\\\wsl.localhost\\Ubuntu", "Ubuntu"));
        assertEquals("/", WslPaths.toLinux("\\\\wsl.localhost\\Ubuntu\\", "Ubuntu"));
        assertNull(WslPaths.toLinux("\\\\wsl.localhost\\Debian\\home", "Ubuntu"), "another distribution's");
        assertNull(WslPaths.toLinux("\\\\server\\share\\x", "Ubuntu"), "a network share");
    }

    @Test
    void aWindowsDriveIsMountedUnderMnt() {
        assertEquals("/mnt/c/Users/Ana/a b.txt", WslPaths.toLinux("C:\\Users\\Ana\\a b.txt", "Ubuntu"));
        assertEquals("/mnt/d", WslPaths.toLinux("D:\\", "Ubuntu"));
        assertEquals(
                "C:\\Users\\Ana\\a b.txt", WslPaths.toWindows("/mnt/c/Users/Ana/a b.txt", "\\\\wsl.localhost\\Ubuntu"));
        assertEquals("D:\\", WslPaths.toWindows("/mnt/d", "\\\\wsl.localhost\\Ubuntu"));
    }

    @Test
    void theDistributionsPathsComeBackUnderTheShareTheyWereOpenedBy() {
        assertEquals(
                "\\\\wsl.localhost\\Ubuntu\\home\\ana\\app",
                WslPaths.toWindows("/home/ana/app", "\\\\wsl.localhost\\Ubuntu"));
        assertEquals("\\\\wsl$\\Ubuntu\\usr\\lib", WslPaths.toWindows("/usr/lib", "\\\\wsl$\\Ubuntu"));
        assertEquals("\\\\wsl$\\Ubuntu\\", WslPaths.toWindows("/", "\\\\wsl$\\Ubuntu"));
        assertNull(WslPaths.toWindows("relative/path", "\\\\wsl$\\Ubuntu"));
        assertEquals("Ubuntu-24.04", WslPaths.distroOf("\\\\wsl.localhost\\Ubuntu-24.04\\home"));
        assertNull(WslPaths.distroOf("C:\\x"));
        assertNull(WslPaths.sharePrefix("\\\\wsl.localhost\\"), "no distribution named");
    }

    @Test
    void aFolderInsideADistributionRunsItsToolsThereByItsNamesThere() {
        WslTargets targets = new WslTargets(List.of("wsl.exe"));
        Path folder = Path.of("\\\\wsl.localhost\\Ubuntu\\home\\ana\\app");

        ExecTarget target = targets.forPath(folder);

        assertEquals("wsl:Ubuntu", target.id());
        assertFalse(target.isLocal());
        assertSame(target, targets.forPath(Path.of("\\\\wsl.localhost\\Ubuntu\\etc\\hosts")), "one per distribution");
        assertEquals("/home/ana/app", target.toTargetPath(folder));
        assertEquals(folder.toString(), target.toPath("/home/ana/app").toString());
        assertNull(targets.forPath(Path.of("C:\\Users\\Ana")), "a Windows folder runs its tools on Windows");
        assertTrue(WslTargets.isWslPath(folder));
        assertEquals(
                List.of("wsl.exe", "-d", "Ubuntu", "--exec", "/bin/sh", "-c", "true"),
                ((WslExecTarget) target).argv(List.of("/bin/sh", "-c", "true")));
    }
}
