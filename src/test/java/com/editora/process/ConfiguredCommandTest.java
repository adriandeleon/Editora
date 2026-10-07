package com.editora.process;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfiguredCommandTest {

    @Test
    void blankFallsBackToTheBareName() {
        assertEquals(List.of("gh"), ConfiguredCommand.tokens(null, "gh"));
        assertEquals(List.of("gh"), ConfiguredCommand.tokens("   ", "gh"));
    }

    /** G2: what Settings' Browse… button writes — a path with spaces — is one executable, not three tokens. */
    @Test
    void anExistingFileWithSpacesIsOneExecutable(@TempDir Path dir) throws Exception {
        Path tool = Files.createFile(
                Files.createDirectories(dir.resolve("Program Files/GitHub CLI")).resolve("gh.exe"));

        assertEquals(List.of(tool.toString()), ConfiguredCommand.tokens(tool.toString(), "gh"));
        assertEquals(List.of(tool.toString()), ConfiguredCommand.tokens("  " + tool + " ", "gh"));
    }

    @Test
    void aCommandLineIsTokenizedWithQuotes() {
        assertEquals(
                List.of("flatpak-spawn", "--host", "gh"), ConfiguredCommand.tokens("flatpak-spawn --host gh", "gh"));
        assertEquals(
                List.of("/no such dir/gh", "--flag"), ConfiguredCommand.tokens("\"/no such dir/gh\" --flag", "gh"));
        assertEquals(List.of("sh", "/tmp/fake gh"), ConfiguredCommand.tokens("sh '/tmp/fake gh'", "gh"));
    }
}
