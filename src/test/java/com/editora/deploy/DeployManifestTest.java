package com.editora.deploy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeployManifestTest {

    @TempDir
    Path dir;

    @Test
    void entriesRoundTripIncludingNamesWithSpacesAndTabsLeftOut() throws Exception {
        DeployManifest m = DeployManifest.of(Map.of(
                "src/My File.txt", new DeployManifest.Entry(12, 1_700_000_000_500L, "abc", 1_700_000_000L),
                "Ñandú/ü.py", new DeployManifest.Entry(1, 2, "d", 3)));
        Path file = dir.resolve("deploy/x.manifest");
        m.save(file);
        DeployManifest back = DeployManifest.load(file);
        assertEquals(m.entries(), back.entries());
    }

    @Test
    void aMissingOrForeignFileIsAnEmptyManifest() throws Exception {
        assertTrue(DeployManifest.load(dir.resolve("none")).isEmpty());
        Path junk = dir.resolve("junk");
        Files.writeString(junk, "something else\n1\t2\t3\t4\ta\n");
        assertTrue(DeployManifest.load(junk).isEmpty());
    }

    @Test
    void eachProjectAndTargetHasItsOwnFile() {
        Path a = DeployManifest.fileFor(dir, Path.of("/p/one"), "me@h:22:/srv/app");
        assertEquals(a, DeployManifest.fileFor(dir, Path.of("/p/one"), "me@h:22:/srv/app"));
        assertNotEquals(a, DeployManifest.fileFor(dir, Path.of("/p/two"), "me@h:22:/srv/app"));
        assertNotEquals(a, DeployManifest.fileFor(dir, Path.of("/p/one"), "me@h:22:/srv/other"));
        assertTrue(a.startsWith(dir.resolve("deploy")));
    }
}
