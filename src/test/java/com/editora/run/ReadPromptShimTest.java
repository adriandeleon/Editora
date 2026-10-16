package com.editora.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** {@code read -p} prompts show when bash's stdin is a pipe (the Run console), and nothing else changes. */
@DisabledOnOs(OS.WINDOWS)
class ReadPromptShimTest {

    private static String[] run(Path script, Map<String, String> env, String input) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", script.toString());
        pb.environment().remove("BASH_ENV");
        pb.environment().putAll(env);
        Process p = pb.start();
        p.getOutputStream().write(input.getBytes());
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes());
        String err = new String(p.getErrorStream().readAllBytes());
        assertTrue(p.waitFor(20, TimeUnit.SECONDS));
        return new String[] {out, err, String.valueOf(p.exitValue())};
    }

    private static Path script(Path dir) throws Exception {
        Path s = dir.resolve("ask.sh");
        Files.writeString(s, """
                set -euo pipefail
                read -r -p "Name: " n
                read -rp"Age: " a
                read -r path
                while IFS= read -r line; do echo "loop[$line]"; done
                echo "[$n] [$a] [$path] done"
                """);
        return s;
    }

    @Test
    void promptsAppearAndAnswersAreUnchanged(@TempDir Path dir) throws Exception {
        assumeTrue(new ProcessBuilder("bash", "-c", "exit 0").start().waitFor() == 0, "needs bash");
        Path s = script(dir);
        String input = "bob\n42\n/p a\nx\ny\n";

        String[] without = run(s, Map.of(), input);
        assertEquals("", without[1], "bash itself prints no prompt on a pipe — the bug");

        String[] with = run(s, ReadPromptShim.environment(Map.of()), input);
        assertEquals("Name: Age: ", with[1]);
        assertEquals(without[0], with[0], "the script's own output is the same");
        assertEquals("loop[x]\nloop[y]\n[bob] [42] [/p a] done\n", with[0]);
        assertEquals("0", with[2]);
    }

    @Test
    void theUsersOwnBashEnvStillRuns(@TempDir Path dir) throws Exception {
        assumeTrue(new ProcessBuilder("bash", "-c", "exit 0").start().waitFor() == 0, "needs bash");
        Path mine = dir.resolve("mine.bash");
        Files.writeString(mine, "export FROM_MINE=yes\n");
        Path s = dir.resolve("env.sh");
        Files.writeString(s, "echo \"FROM_MINE=${FROM_MINE:-no}\"\n");
        Map<String, String> env = ReadPromptShim.environment(Map.of("BASH_ENV", mine.toString()));
        assertEquals(List.of(ReadPromptShim.USER_BASH_ENV, "BASH_ENV"), List.copyOf(env.keySet()));
        String[] r = run(s, env, "");
        assertEquals("FROM_MINE=yes\n", r[0]);
        assertFalse(r[1].contains("read"), r[1]);
    }
}
