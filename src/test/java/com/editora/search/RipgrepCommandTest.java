package com.editora.search;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link Ripgrep#command}: the configured value as an argv. */
class RipgrepCommandTest {

    @Test
    void blankMeansTheDefaultCommand() {
        assertEquals(List.of("rg"), Ripgrep.command(null));
        assertEquals(List.of("rg"), Ripgrep.command("  "));
    }

    @Test
    void aMultiTokenCommandIsSplitOnWhitespace() {
        assertEquals(List.of("wsl", "rg"), Ripgrep.command(" wsl  rg "));
    }

    /** A12-22: {@code C:\Program Files\ripgrep\rg.exe} was split in two and never detected. */
    @Test
    void anExistingExecutableWithASpaceInItsPathIsOneToken(@TempDir Path dir) throws Exception {
        Path rg = Files.createDirectories(dir.resolve("my tools")).resolve("rg");
        Files.writeString(rg, "#!/bin/sh\n");
        assertEquals(List.of(rg.toString()), Ripgrep.command(rg.toString()));
    }
}
