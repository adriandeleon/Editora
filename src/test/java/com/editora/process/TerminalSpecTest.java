package com.editora.process;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The terminal seam on execution targets: what a target without terminals says, and the size it is given. */
class TerminalSpecTest {

    @Test
    void aPseudoTerminalIsAtLeastOneCellEachWay() {
        TerminalSpec spec = new TerminalSpec(null, 0, -3);
        assertEquals(1, spec.columns());
        assertEquals(1, spec.rows());
    }

    @Test
    void thisMachineHasNoTerminalTarget() {
        // Local shells start on pty4j in the terminal package; the local target offers no terminal of its own.
        IOException e = assertThrows(
                IOException.class, () -> ExecTargets.forPath(null).startTerminal(new TerminalSpec(null, 80, 24)));
        assertTrue(e.getMessage().contains("cannot run a terminal"), e.getMessage());
    }

    @Test
    void aPlaceNothingCanRunOnGivesItsReason(@TempDir Path dir) throws Exception {
        Path zip = dir.resolve("a.zip");
        try (FileSystem fs = FileSystems.newFileSystem(URI.create("jar:" + zip.toUri()), Map.of("create", "true"))) {
            Path inside = Files.createDirectories(fs.getPath("/folder"));
            IOException e = assertThrows(
                    IOException.class,
                    () -> ExecTargets.forPath(inside).startTerminal(new TerminalSpec(inside, 80, 24)));
            assertTrue(
                    e.getMessage().contains("no connection") || e.getMessage().contains("turned off"), e.getMessage());
        }
    }
}
