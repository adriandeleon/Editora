package com.editora.dap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The wrapper java-debug runs in place of {@code java} so the program's exit code is known (POSIX only). */
@DisabledOnOs(OS.WINDOWS)
class JavaExitCodeWrapperTest {

    @TempDir
    Path dir;

    /** A stand-in "java": prints each argument on a line of its own, then exits with {@code code} (or sleeps). */
    private Path fakeJava(String body) throws Exception {
        Path java = dir.resolve("fake jdk's/bin/java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwx------"));
        return java;
    }

    @Test
    void argumentsPassThroughUntouchedAndTheExitCodeIsRecorded() throws Exception {
        Path java = fakeJava("for a in \"$@\"; do printf '[%s]\\n' \"$a\"; done\nexit 7\n");
        JavaExitCodeWrapper w = JavaExitCodeWrapper.create(java.toString());
        try {
            List<String> args = List.of("a b", "\"q\"", "$HOME", "ünï", "", "x'y", "@/tmp/argfile", "*");
            List<String> command = new java.util.ArrayList<>(List.of(w.script().toString()));
            command.addAll(args);
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            assertEquals(7, p.waitFor());
            assertEquals("[a b]\n[\"q\"]\n[$HOME]\n[ünï]\n[]\n[x'y]\n[@/tmp/argfile]\n[*]\n", out);
            assertEquals(7, w.awaitExitCode(Duration.ofSeconds(5)));
            assertEquals("bin", w.script().getParent().getFileName().toString(), "java-debug runs <home>/bin/<name>");
        } finally {
            w.delete();
        }
        assertFalse(Files.exists(w.script().getParent().getParent()), "deleted");
    }

    /** java-debug ends a program it started with {@code Process.destroy} (SIGTERM): it reaches the program. */
    @Test
    void aTerminateReachesTheProgram() throws Exception {
        Path pidFile = dir.resolve("pid");
        Path java = fakeJava("echo $$ > '" + pidFile + "'\nexec sleep 60\n");
        JavaExitCodeWrapper w = JavaExitCodeWrapper.create(java.toString());
        try {
            Process p = new ProcessBuilder(w.script().toString()).start();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!(Files.isRegularFile(pidFile) && !Files.readString(pidFile).isBlank()) && System.nanoTime() < end) {
                Thread.onSpinWait();
            }
            long child = Long.parseLong(Files.readString(pidFile).strip());
            p.destroy();
            assertTrue(p.waitFor(10, TimeUnit.SECONDS));
            assertTrue(
                    ProcessHandle.of(child)
                            .map(h -> h.onExit().thenApply(x -> true))
                            .map(f -> f.completeOnTimeout(false, 10, TimeUnit.SECONDS)
                                    .join())
                            .orElse(true),
                    "the program ended with its wrapper");
        } finally {
            w.delete();
        }
    }

    @Test
    void noRecordedCodeIsNull() throws Exception {
        JavaExitCodeWrapper w = JavaExitCodeWrapper.create("/no/java");
        try {
            assertNull(w.awaitExitCode(Duration.ofMillis(50)));
            Files.writeString(w.exitFile(), "not a number\n");
            assertNull(JavaExitCodeWrapper.readExitCode(w.exitFile()));
        } finally {
            w.delete();
        }
    }

    @Test
    void wordsAreSingleQuotedForSh() {
        assertEquals("'/opt/jdk/bin/java'", JavaExitCodeWrapper.quote("/opt/jdk/bin/java"));
        assertEquals("'/a b/it'\\''s/java'", JavaExitCodeWrapper.quote("/a b/it's/java"));
    }
}
