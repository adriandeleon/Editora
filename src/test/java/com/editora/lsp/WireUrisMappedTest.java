package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.process.ExecTargets;
import com.editora.process.MappedTestTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link WireUris#mapped}: a folder of this machine, known to its server by the target's names. */
@DisabledOnOs(OS.WINDOWS) // the stand-in target is a symlink
class WireUrisMappedTest {

    @Test
    void aMappedFolderIsNamedAsItsTargetNamesItAndBack(@TempDir Path dir) throws Exception {
        Path base = dir.toRealPath();
        Path folder = Files.createDirectories(base.resolve("my app"));
        Path alias = base.resolve("mounted");
        ExecTargets.setRemoteEnabled(() -> true);
        MappedTestTarget target = MappedTestTarget.register(folder, alias);
        try {
            WireUris uris = WireUris.mapped(target);
            String key = DocumentUris.of(folder.resolve("src/A b.java"));
            String wire = uris.toWire(key);

            assertEquals(DocumentUris.of(alias.resolve("src/A b.java")), wire);
            assertEquals(key, uris.toKey(wire));
            String outside = DocumentUris.of(base.resolve("elsewhere.txt"));
            assertEquals(outside, uris.toWire(outside), "not in the folder: as it is");
            assertEquals("file:///usr/lib/jvm/src.zip", uris.toKey("file:///usr/lib/jvm/src.zip"), "the target's own");
            assertEquals("jdt://contents/x", uris.toWire("jdt://contents/x"));
            assertEquals(folder.resolve("src/A b.java"), uris.toPath(wire));
            String root = DocumentUris.ofDirectory(folder);
            assertEquals(DocumentUris.ofDirectory(alias), uris.toWire(root), "a folder stays a folder");
            assertEquals(root, uris.toKey(uris.toWire(root)));
        } finally {
            target.unregister();
            ExecTargets.setRemoteEnabled(null);
        }
    }
}
