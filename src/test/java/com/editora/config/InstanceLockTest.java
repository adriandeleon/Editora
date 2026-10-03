package com.editora.config;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The claim two Editora processes make on one config dir. A second process is an ordinary state — a launch
 * with no file argument, {@code --project}, {@code --new-instance} or {@code --diff-ui} is not forwarded to
 * the running editor — so each process has to be able to tell whether it is alone.
 */
class InstanceLockTest {

    /** Another JVM claiming {@code dir}; the first line it prints is the role it got. */
    private static final class OtherProcess implements AutoCloseable {
        private final Process process;
        private final BufferedReader out;
        private final BufferedWriter in;
        final String role;

        OtherProcess(Path dir) throws Exception {
            process = new ProcessBuilder(
                            Path.of(System.getProperty("java.home"), "bin", "java")
                                    .toString(),
                            "-cp",
                            System.getProperty("java.class.path"),
                            InstanceLockProbe.class.getName(),
                            dir.toString())
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            in = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            role = out.readLine();
        }

        /** What the other process sees right now: is anyone else on the directory? */
        boolean seesOthers() throws Exception {
            in.write("probe\n");
            in.flush();
            return "others=true".equals(out.readLine());
        }

        /** The other process exits (stdin closes); returns once it is gone and its locks are released. */
        void exit() throws Exception {
            in.close();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the helper JVM should exit when stdin closes");
        }

        @Override
        public void close() {
            process.destroyForcibly();
        }
    }

    @Test
    void theFirstProcessIsThePrimaryAndSeesALaterOneComeAndGo(@TempDir Path dir) throws Exception {
        try (InstanceLock mine = InstanceLock.claim(dir)) {
            assertTrue(mine.primary());
            assertFalse(mine.othersPresent(), "alone so far");

            try (OtherProcess other = new OtherProcess(dir)) {
                assertEquals("secondary", other.role, "the directory is already taken");
                assertTrue(mine.othersPresent(), "the primary can see that a second process is running");
                assertTrue(other.seesOthers(), "and a secondary always knows it is not alone");

                other.exit();
                assertFalse(mine.othersPresent(), "its presence ends with the process — nothing to clean up");
            }
        }
    }

    @Test
    void aLaterProcessIsASecondaryAndStaysOneAndACrashedPrimaryLeavesNoStaleClaim(@TempDir Path dir) throws Exception {
        try (OtherProcess first = new OtherProcess(dir)) {
            assertEquals("primary", first.role);
            assertFalse(first.seesOthers());

            try (InstanceLock mine = InstanceLock.claim(dir)) {
                assertFalse(mine.primary());
                assertTrue(mine.othersPresent());
                assertTrue(first.seesOthers(), "the primary sees this process");

                first.close(); // killed outright: no shutdown hook, no cleanup
                assertTrue(first.process.waitFor(30, TimeUnit.SECONDS));
                assertFalse(mine.primary(), "a secondary is never promoted: its stores were loaded mid-flight");
            }
            // The dead primary's lock went with it, so the next launch is a primary again.
            try (InstanceLock next = InstanceLock.claim(dir)) {
                assertTrue(next.primary());
                assertFalse(next.othersPresent());
            }
        }
    }

    @Test
    void closingReleasesTheClaimForTheNextProcess(@TempDir Path dir) {
        InstanceLock first = InstanceLock.claim(dir);
        assertTrue(first.primary());
        first.close();
        first.close(); // idempotent
        assertFalse(first.othersPresent(), "a closed claim has nothing left to probe with");

        try (InstanceLock second = InstanceLock.claim(dir)) {
            assertTrue(second.primary());
        }
    }

    @Test
    void whenTheLockFileCannotBeCreatedTheProcessBehavesAsTheOnlyInstance(@TempDir Path dir) throws Exception {
        // A "config dir" that is really a file: nothing can be created under it. Locking is a courtesy between
        // Editora processes, so failing to lock must leave the single-instance behaviour untouched.
        Path notADirectory = Files.writeString(dir.resolve("not-a-dir"), "x");

        try (InstanceLock lock = InstanceLock.claim(notADirectory)) {
            assertTrue(lock.primary());
            assertFalse(lock.othersPresent());
        }
    }

    @Test
    void theClaimIsCreatedInsideAConfigDirThatDoesNotExistYet(@TempDir Path dir) {
        Path fresh = dir.resolve("first-run/.editora");

        try (InstanceLock lock = InstanceLock.claim(fresh)) {
            assertTrue(lock.primary());
            assertTrue(Files.isRegularFile(fresh.resolve(InstanceLock.FILE_NAME)));
        }
    }
}
