package com.editora.wsl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The argv {@code wsl.exe} is given ({@link WslExecTarget#windowsSafe}): it must reach the distribution's shell
 * intact after Windows joins it into one command line and {@code wsl.exe} splits it again.
 */
class WslArgvTest {

    /** Words a launcher script and its arguments contain, and a few that are hard to carry. */
    private static final List<String> HARD = List.of(
            "plain",
            "two words",
            "say \"hi\"",
            "it's",
            "trailing\\",
            "a\\\"b",
            "",
            "line one\nline two\n",
            "  spaced  ",
            "*.java",
            "$HOME `id` $(id)",
            "-n",
            "%PATH% ^&|<>",
            "naïve ✓ 日本");

    @Test
    void everyArgumentIsOneWindowsWordThatNeedsNoEscaping() {
        List<String> argv = WslExecTarget.windowsSafe(List.of("/bin/sh", "-c", "echo \"$@\"", "x", "a \"b\" c\\"));
        for (String arg : argv) {
            assertFalse(arg.contains("\""), "no quote to escape: " + arg);
            assertFalse(arg.endsWith("\\"), "no backslash before the closing quote: " + arg);
        }
        String encoded = argv.getLast();
        assertTrue(encoded.matches("[A-Za-z0-9_./=,:+@\\\\]+"), "one word, nothing to glob: " + encoded);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void theDistributionsShellRunsExactlyTheWordsItWasGiven() throws Exception {
        List<String> inner =
                new ArrayList<>(List.of("/bin/sh", "-c", "for a; do printf '[%s]' \"$a\"; done", "editora"));
        inner.addAll(HARD);
        StringBuilder expected = new StringBuilder();
        HARD.forEach(word -> expected.append('[').append(word).append(']'));
        List<String> argv = WslExecTarget.windowsSafe(inner);
        assertEquals(expected.toString(), run("/bin/sh", argv));
        if (Files.isExecutable(Path.of("/bin/bash"))) {
            assertEquals(expected.toString(), run("/bin/bash", argv), "a distribution whose sh is bash");
        }
    }

    /** {@code argv} with its shell replaced by {@code shell}. */
    private static String run(String shell, List<String> argv) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(argv);
        command.set(0, shell);
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), out);
        return out;
    }
}
