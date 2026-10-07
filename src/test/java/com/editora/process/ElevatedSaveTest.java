package com.editora.process;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElevatedSaveTest {

    @Test
    void supportedOnLinuxAndMac() {
        assertTrue(ElevatedSave.supportedOnOs("Linux"));
        assertTrue(ElevatedSave.supportedOnOs("GNU/Linux 6.1"));
        assertTrue(ElevatedSave.supportedOnOs("Mac OS X"));
        assertFalse(ElevatedSave.supportedOnOs("Windows 11"));
        assertFalse(ElevatedSave.supportedOnOs(null));
    }

    @Test
    void osDetection() {
        assertTrue(ElevatedSave.isLinux("Linux"));
        assertFalse(ElevatedSave.isLinux("Mac OS X"));
        assertTrue(ElevatedSave.isMac("Mac OS X"));
        assertTrue(ElevatedSave.isMac("Darwin"));
        assertFalse(ElevatedSave.isMac("Windows 11"));
        assertFalse(ElevatedSave.isMac(null));
    }

    @Test
    void elevatedArgvPicksTheToolPerOs() {
        assertEquals(
                "pkexec",
                ElevatedSave.elevatedArgv("Linux", ElevatedSave.PKEXEC, Path.of("/a"), Path.of("/b"))
                        .get(0));
        assertEquals(
                "osascript",
                ElevatedSave.elevatedArgv("Mac OS X", ElevatedSave.PKEXEC, Path.of("/a"), Path.of("/b"))
                        .get(0));
        assertNull(ElevatedSave.elevatedArgv("Windows 11", ElevatedSave.PKEXEC, Path.of("/a"), Path.of("/b")));
    }

    @Test
    void osascriptArgvUsesAdminPrivilegesAndQuotedForm() {
        Path source = Path.of("/tmp/x.tmp");
        Path target = Path.of("/etc/hosts");
        List<String> argv = ElevatedSave.osascriptArgv(source, target);
        assertEquals("osascript", argv.get(0));
        // The AppleScript runs `do shell script … with administrator privileges` and shell-escapes the
        // paths via `quoted form of` (never interpolating them into the script).
        assertTrue(
                argv.stream().anyMatch(s -> s.contains("with administrator privileges")), "elevates via AppleScript");
        assertTrue(argv.stream().anyMatch(s -> s.contains("quoted form of")), "paths shell-escaped");
        // The two paths are passed as osascript argv (item 1/2 of argv), the last two entries.
        assertEquals(source.toString(), argv.get(argv.size() - 2));
        assertEquals(target.toString(), argv.get(argv.size() - 1));
    }

    @Test
    void cancellationDetection() {
        // Linux: pkexec dismiss/not-authorized is exit 126.
        assertTrue(ElevatedSave.isCancellation("Linux", 126, ""));
        assertFalse(ElevatedSave.isCancellation("Linux", 1, "some error"));
        // macOS: osascript reports -128 / "User canceled" in stderr on cancel.
        assertTrue(ElevatedSave.isCancellation("Mac OS X", 1, "execution error: User canceled. (-128)"));
        assertFalse(ElevatedSave.isCancellation("Mac OS X", 1, "some other failure"));
    }

    @Test
    void argvCopiesSourceIntoTargetInPlaceAsRoot() {
        Path source = Path.of("/tmp/x.tmp");
        Path target = Path.of("/etc/hosts");
        List<String> argv = ElevatedSave.pkexecArgv(ElevatedSave.PKEXEC, source, target);
        assertEquals("pkexec", argv.get(0));
        assertEquals("/bin/sh", argv.get(1));
        assertEquals("-c", argv.get(2));
        assertEquals(ElevatedSave.SCRIPT, argv.get(3));
        assertTrue(argv.get(3).contains("cat \"$1\" > \"$2\""), "in-place rewrite preserves owner/mode");
        // The two paths are the last two positional args ($1 and $2), never interpolated into the script.
        assertEquals(source.toString(), argv.get(argv.size() - 2));
        assertEquals(target.toString(), argv.get(argv.size() - 1));
    }

    @Test
    void blankPkexecFallsBackToTheDefault() {
        assertEquals(
                "pkexec",
                ElevatedSave.pkexecArgv("", Path.of("/a"), Path.of("/b")).get(0));
        assertEquals(
                "pkexec",
                ElevatedSave.pkexecArgv(null, Path.of("/a"), Path.of("/b")).get(0));
        assertEquals(
                "/usr/bin/pkexec",
                ElevatedSave.pkexecArgv("/usr/bin/pkexec", Path.of("/a"), Path.of("/b"))
                        .get(0));
    }

    // --- The elevated script: backup first, sync, restore on failure ------------------------------------

    @Test
    void theScriptBacksTheTargetUpBeforeItTruncatesItAndSyncsBoth() {
        String script = ElevatedSave.SCRIPT;
        int backup = script.indexOf("cp -p \"$2\" \"$b\"");
        int truncate = script.indexOf("cat \"$1\" > \"$2\"");
        assertTrue(backup >= 0, "the previous bytes are copied aside, keeping owner and mode");
        assertTrue(truncate > backup, "before the redirect that empties the target");
        assertTrue(script.contains("sync \"$1\" 2>/dev/null || sync"), "one file where sync takes a file, else all");
        assertTrue(script.contains("cp -p \"$2\" \"$b\" && flush \"$b\""), "the backup is on disk before that");
        assertTrue(script.contains("cat \"$1\" > \"$2\" && flush \"$2\""), "the new bytes are synced as well");
        assertTrue(script.contains("cat \"$b\" > \"$2\""), "a failed copy puts the previous bytes back");
        assertFalse(script.contains("'"), "no single quote: the script is passed through `quoted form of`");
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(
            value = org.junit.jupiter.api.condition.OS.WINDOWS,
            disabledReason = "pkexec and osascript paths; a quote is not a legal Windows path character")
    void bothLaunchersRunTheSameScriptWithThePathsAsArguments() {
        Path source = Path.of("/tmp/it's a $source.tmp");
        Path target = Path.of("/etc/odd \"name\"");
        List<String> linux = ElevatedSave.pkexecArgv(ElevatedSave.PKEXEC, source, target);
        assertEquals(
                List.of("pkexec", "/bin/sh", "-c", ElevatedSave.SCRIPT, "editora-admin-save"), linux.subList(0, 5));
        assertEquals(List.of(source.toString(), target.toString()), linux.subList(5, linux.size()));

        List<String> mac = ElevatedSave.osascriptArgv(source, target);
        assertEquals(
                List.of(ElevatedSave.SCRIPT, source.toString(), target.toString()),
                mac.subList(mac.size() - 3, mac.size()),
                "script and paths are osascript arguments, never spliced into the AppleScript text");
        String appleScript = String.join("\n", mac.subList(0, mac.size() - 3));
        assertTrue(appleScript.contains("quoted form of (item 1 of argv)"));
        assertTrue(appleScript.contains("quoted form of (item 2 of argv)"));
        assertTrue(appleScript.contains("quoted form of (item 3 of argv)"));
        assertFalse(appleScript.contains("/etc/odd"));
    }

    @Test
    void failuresAreToldApartByTheScriptsMarkerOrExitCode() {
        assertEquals(ElevatedSave.Failure.RESTORED, ElevatedSave.failureOf(72, ""));
        assertEquals(ElevatedSave.Failure.BACKUP_KEPT, ElevatedSave.failureOf(73, ""));
        assertEquals(ElevatedSave.Failure.NO_BACKUP, ElevatedSave.failureOf(71, ""));
        assertEquals(ElevatedSave.Failure.STALE_BACKUP, ElevatedSave.failureOf(74, ""));
        assertEquals(ElevatedSave.Failure.OTHER, ElevatedSave.failureOf(1, "cat: write error"));
        // osascript exits 1 whatever the shell returned; the marker travels in its error text.
        assertEquals(
                ElevatedSave.Failure.BACKUP_KEPT,
                ElevatedSave.failureOf(
                        1, "0:123: execution error: cat: No space left\neditora-admin-save:backup-kept (73)"));
        assertEquals(
                "cat: No space left on device",
                ElevatedSave.reason("cat: No space left on device\neditora-admin-save:restored\n"));
        assertEquals(Path.of("/etc/fstab.editora-backup"), ElevatedSave.backupOf(Path.of("/etc/fstab")));
    }

    /** Runs the very script root would run, unprivileged, against scratch files. */
    private static int runScript(Path source, Path target, StringBuilder stderr) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isExecutable(Path.of("/bin/sh")));
        Process process = new ProcessBuilder(
                        "/bin/sh",
                        "-c",
                        ElevatedSave.SCRIPT,
                        "editora-admin-save",
                        source.toString(),
                        target.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        stderr.append(new String(process.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        return process.waitFor();
    }

    @Test
    void theScriptWritesTheNewBytesAndLeavesNoBackup(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path source = java.nio.file.Files.writeString(dir.resolve("src.tmp"), "new\n");
        Path target = java.nio.file.Files.writeString(dir.resolve("fs tab"), "old\n");
        StringBuilder err = new StringBuilder();

        assertEquals(0, runScript(source, target, err), err.toString());

        assertEquals("new\n", java.nio.file.Files.readString(target));
        assertFalse(java.nio.file.Files.exists(ElevatedSave.backupOf(target)));
    }

    @Test
    void aCopyThatFailsAfterTruncatingPutsThePreviousBytesBack(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        Path target = java.nio.file.Files.writeString(dir.resolve("fstab"), "precious\n");
        StringBuilder err = new StringBuilder();

        // The source is missing: the redirect has already emptied the target when cat fails.
        int exit = runScript(dir.resolve("missing.tmp"), target, err);

        assertEquals(ElevatedSave.Failure.RESTORED, ElevatedSave.failureOf(exit, err.toString()), err.toString());
        assertEquals("precious\n", java.nio.file.Files.readString(target));
        assertFalse(java.nio.file.Files.exists(ElevatedSave.backupOf(target)));
    }

    @Test
    void anEarlierBackupIsNeverOverwritten(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path source = java.nio.file.Files.writeString(dir.resolve("src.tmp"), "new\n");
        Path target = java.nio.file.Files.writeString(dir.resolve("fstab"), "torn");
        java.nio.file.Files.writeString(ElevatedSave.backupOf(target), "the only good copy\n");
        StringBuilder err = new StringBuilder();

        int exit = runScript(source, target, err);

        assertEquals(ElevatedSave.Failure.STALE_BACKUP, ElevatedSave.failureOf(exit, err.toString()));
        assertEquals("torn", java.nio.file.Files.readString(target), "the target is not touched");
        assertEquals("the only good copy\n", java.nio.file.Files.readString(ElevatedSave.backupOf(target)));
    }

    @Test
    void aTargetThatCannotBeBackedUpIsNotTouched(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path source = java.nio.file.Files.writeString(dir.resolve("src.tmp"), "new\n");
        Path locked = java.nio.file.Files.createDirectory(dir.resolve("locked"));
        Path target = java.nio.file.Files.writeString(locked.resolve("fstab"), "old\n");
        org.junit.jupiter.api.Assumptions.assumeTrue(locked.toFile().setWritable(false));
        org.junit.jupiter.api.Assumptions.assumeFalse(java.nio.file.Files.isWritable(locked), "running as root");
        StringBuilder err = new StringBuilder();
        try {
            int exit = runScript(source, target, err);

            assertEquals(ElevatedSave.Failure.NO_BACKUP, ElevatedSave.failureOf(exit, err.toString()));
            assertEquals("old\n", java.nio.file.Files.readString(target));
        } finally {
            locked.toFile().setWritable(true);
        }
    }

    @Test
    void aRefusedWriteLeavesNoBackupBehind(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path source = java.nio.file.Files.writeString(dir.resolve("src.tmp"), "new\n");
        Path target = java.nio.file.Files.writeString(dir.resolve("fstab"), "old\n");
        org.junit.jupiter.api.Assumptions.assumeTrue(target.toFile().setWritable(false));
        org.junit.jupiter.api.Assumptions.assumeFalse(java.nio.file.Files.isWritable(target), "running as root");
        StringBuilder err = new StringBuilder();

        int exit = runScript(source, target, err);

        assertEquals(ElevatedSave.Failure.OTHER, ElevatedSave.failureOf(exit, err.toString()), err.toString());
        assertEquals("old\n", java.nio.file.Files.readString(target));
        assertFalse(java.nio.file.Files.exists(ElevatedSave.backupOf(target)), "nothing was changed, nothing to keep");
    }
}
