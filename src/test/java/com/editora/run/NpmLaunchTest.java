package com.editora.run;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Review C101: npm, pnpm, yarn and bun run on the Node.js Run File picks. */
class NpmLaunchTest {

    private static final Path BIN =
            Path.of("/opt/nvm/versions/node/v20.11.1/bin").toAbsolutePath();

    private static NodeRuntimes.NodeRuntime node() {
        return new NodeRuntimes.NodeRuntime(
                List.of(BIN.resolve("node").toString()), NodeRuntimes.Source.VERSION_FILE, ".nvmrc");
    }

    @Test
    void theChosenNodesFolderGoesFirstOnPathAndItsNpmRuns() {
        Set<Path> files = Set.of(BIN.resolve("npm"), BIN.resolve("node"));
        NpmLaunch l = NpmLaunch.plan(List.of("npm", "run", "test"), node(), "/usr/bin", false, false, files::contains);
        assertEquals(List.of(BIN.resolve("npm").toString(), "run", "test"), l.argv());
        assertEquals(BIN + File.pathSeparator + "/usr/bin", l.env().get("PATH"));
        assertEquals("1", l.env().get("FORCE_COLOR"));
        assertEquals(List.of(BIN.resolve("node").toString()), l.probe(), "the chip names that Node");
    }

    @Test
    void aManagerNotBesideNodeStaysBareButStillGetsThatNode() {
        NpmLaunch l = NpmLaunch.plan(List.of("pnpm", "run", "hi"), node(), "/usr/bin", false, true, p -> false);
        assertEquals(List.of("pnpm", "run", "hi"), l.argv());
        assertTrue(l.env().get("PATH").startsWith(BIN.toString()));
        assertFalse(l.env().containsKey("FORCE_COLOR"), "NO_COLOR");
    }

    @Test
    void aUsersPathToTheirManagerIsKept() {
        NpmLaunch l = NpmLaunch.plan(List.of("/usr/local/bin/npm", "ci"), node(), "", false, false, p -> true);
        assertEquals("/usr/local/bin/npm", l.argv().get(0));
        assertEquals(BIN.toString(), l.env().get("PATH"));
    }

    @Test
    void bunIsItsOwnRuntime() {
        NpmLaunch l = NpmLaunch.plan(List.of("bun", "run", "x"), node(), "/usr/bin", false, false, p -> false);
        assertEquals(List.of("bun", "run", "x"), l.probe());
    }

    @Test
    void windowsLooksForNpmCmd() {
        Set<Path> files = Set.of(BIN.resolve("npm.cmd"));
        NpmLaunch l = NpmLaunch.plan(List.of("npm", "test"), node(), "C:\\Windows", true, false, files::contains);
        assertEquals(BIN.resolve("npm.cmd").toString(), l.argv().get(0));
        assertTrue(l.env().containsKey("Path"));
    }

    @Test
    void withNoNodeHereNothingChangesButColour() {
        NpmLaunch l = NpmLaunch.plan(List.of("npm", "start"), null, null, false, false, p -> true);
        assertEquals(List.of("npm", "start"), l.argv());
        assertEquals(Map.of("FORCE_COLOR", "1"), l.env());
        assertEquals(List.of("npm", "start"), l.probe());
        NpmLaunch bare = NpmLaunch.plan(
                List.of("npm"),
                new NodeRuntimes.NodeRuntime(List.of("node"), NodeRuntimes.Source.PATH, null),
                "/usr/bin",
                false,
                false,
                p -> true);
        assertEquals(List.of("npm"), bare.argv(), "a bare node on PATH has no folder to prefer");
    }
}
