package com.editora.process;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** PERF-01: a value whose tokens are itself needs no look at the disk; the others are looked at once. */
    @Test
    void theDiskIsAskedOnlyWhenTheAnswerDecidesAndThenOnce(@TempDir Path dir) throws Exception {
        java.util.List<String> heard = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread self = Thread.currentThread(); // what other tests' threads look up is theirs
        com.editora.perf.BlockingSites.listenForTest((site, thread) -> {
            if (thread == self) {
                heard.add(site);
            }
        });
        try {
            assertEquals(List.of("dot"), ConfiguredCommand.argv("dot"));
            assertEquals(List.of("/usr/bin/git"), ConfiguredCommand.argv("/usr/bin/git"));
            assertEquals(List.of(), heard, "a single token is the same whether or not it is a file");

            Path tool = Files.createFile(
                    Files.createDirectories(dir.resolve("My Tools")).resolve("dot"));
            String raw = tool.toString();
            assertEquals(List.of(raw), ConfiguredCommand.argv(raw));
            assertEquals(List.of("tool-path-is-file"), heard, "looked at once");
            Files.delete(tool);
            assertEquals(List.of(raw), ConfiguredCommand.argv(raw), "kept for the run");
            assertEquals(1, heard.size());

            String missing = dir.resolve("No Such").resolve("tool").toString();
            ConfiguredCommand.prepare(java.util.Arrays.asList(null, " ", "rg", missing));
            assertEquals(2, heard.size(), "prepared: only the value that needs it");
            assertFalse(ConfiguredCommand.isFileKept(missing));
            assertEquals(2, heard.size(), "and then known");
            assertTrue(ConfiguredCommand.argv(missing).size() > 1, "not a file: its tokens");
        } finally {
            com.editora.perf.BlockingSites.listenForTest(null);
        }
    }
}
