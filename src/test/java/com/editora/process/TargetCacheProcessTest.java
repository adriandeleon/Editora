package com.editora.process;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Editora's working folder on a target ({@link TargetCache}), kept through a file system
 * ({@link PathTargetCache}) and by commands on the target ({@link ShellTargetCache}, here run by this
 * machine's {@code sh}, as a container's would be): both behave the same.
 */
class TargetCacheProcessTest {

    private static final ExecTarget HERE = new LocalExecTarget();

    abstract static class Contract {

        @TempDir
        Path dir;

        abstract TargetCache cache();

        @Test
        void aFolderIsMadeWithItsParents() throws Exception {
            String made = cache().makeDirectories("jdtls-workspaces/abc123");

            assertEquals(dir.resolve("jdtls-workspaces").resolve("abc123").toString(), made);
            assertTrue(Files.isDirectory(Path.of(made)));
            assertEquals(made, cache().makeDirectories("jdtls-workspaces/abc123"), "again is fine");
        }

        @Test
        void aFileIsCopiedOnlyWhenItsSizeChanged(@TempDir Path here) throws Exception {
            Path jar = Files.writeString(here.resolve("debug-0.53.jar"), "jar bytes");

            String copy = cache().putFile("java-debug/debug-0.53.jar", jar);

            assertEquals(dir.resolve("java-debug").resolve("debug-0.53.jar").toString(), copy);
            assertEquals("jar bytes", Files.readString(Path.of(copy)));
            Files.writeString(Path.of(copy), "JAR BYTES"); // same size: taken as the same jar
            cache().putFile("java-debug/debug-0.53.jar", jar);
            assertEquals("JAR BYTES", Files.readString(Path.of(copy)));
            Files.writeString(jar, "a longer jar");
            cache().putFile("java-debug/debug-0.53.jar", jar);
            assertEquals("a longer jar", Files.readString(Path.of(copy)));
            assertFalse(Files.exists(Path.of(copy + ".part")), "nothing left aside");
        }

        @Test
        void textIsWrittenOnceAndKept() throws Exception {
            String text = "[user]\n\tname = Ana Núñez\n";

            String file = cache().putText("git-global-0011.config", text);

            assertEquals(dir.resolve("git-global-0011.config").toString(), file);
            assertEquals(text, Files.readString(Path.of(file), StandardCharsets.UTF_8));
            cache().putText("git-global-0011.config", "something else");
            assertEquals(text, Files.readString(Path.of(file), StandardCharsets.UTF_8), "named by its contents");
            String nested = cache().putText("debug-classes/x/launcher.java", "class A {}\n");
            assertEquals("class A {}\n", Files.readString(Path.of(nested)));
        }

        @Test
        void aFolderIsRemovedWithEverythingInIt() throws Exception {
            cache().putText("debug-classes/run 1/pkg/A.class", "x");
            cache().putText("debug-classes/run 1/A.java", "y");
            cache().putText("debug-classes/run 2/B.java", "z");

            cache().delete("debug-classes/run 1");

            assertFalse(Files.exists(dir.resolve("debug-classes").resolve("run 1")));
            assertTrue(
                    Files.exists(dir.resolve("debug-classes").resolve("run 2").resolve("B.java")));
            cache().delete("debug-classes/never made"); // nothing there: no error
        }

        @Test
        void onlyNamesInsideTheFolderAreAccepted() {
            for (String bad : new String[] {"", "/etc/passwd", "../x", "a/../../x", "a//b", "a/", "./a", "a\\b"}) {
                assertThrows(IllegalArgumentException.class, () -> cache().delete(bad), bad);
            }
        }
    }

    @Nested
    class ThroughTheFileSystem extends Contract {
        @Override
        TargetCache cache() {
            return new PathTargetCache(HERE, dir);
        }
    }

    @Nested
    @DisabledOnOs(OS.WINDOWS)
    class ByCommandsOnTheTarget extends Contract {
        @Override
        TargetCache cache() {
            return new ShellTargetCache(HERE, dir.toString());
        }

        @Test
        void aCommandThatFailsSaysWhatItCouldNotDo() throws IOException {
            Files.writeString(dir.resolve("blocked"), "a file where a folder is wanted");

            IOException e = assertThrows(IOException.class, () -> cache().makeDirectories("blocked/inside"));

            assertTrue(
                    e.getMessage().startsWith("could not make " + dir.resolve("blocked") + "/inside"), e.getMessage());
        }

        @Test
        void theFolderMustBeTheTargetsAbsoluteOne() {
            assertThrows(IllegalArgumentException.class, () -> new ShellTargetCache(HERE, "relative/cache"));
            assertThrows(IllegalArgumentException.class, () -> new ShellTargetCache(HERE, null));
        }
    }
}
