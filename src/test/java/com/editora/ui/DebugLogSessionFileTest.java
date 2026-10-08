package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session log file {@link DebugLog} mirrors its records to: what is in it when it is attached, what
 * arrives afterwards, who may read it, and that a file that cannot be written costs nothing but the mirror.
 */
class DebugLogSessionFileTest {

    @AfterEach
    void detach() {
        DebugLog.detachFileForTest();
        DebugLog.clear();
    }

    @Test
    void theFileStartsWithWhatWasCapturedBeforeItAndThenFollowsEveryRecord(@TempDir Path configDir) throws Exception {
        DebugLog.clear();
        DebugLog.append("captured before the config dir was known");

        DebugLog.attachFile(configDir);
        Path file = DebugLog.sessionFile(configDir);
        assertEquals(configDir.resolve("editora-session.log"), file);
        assertEquals(List.of("captured before the config dir was known"), Files.readAllLines(file));

        DebugLog.append("logged afterwards");
        assertEquals(
                List.of("captured before the config dir was known", "logged afterwards"),
                Files.readAllLines(file),
                "each record is on disk as soon as it is logged — the file is what survives a crash");
        assertTrue(DebugLog.snapshot().endsWith("logged afterwards"));

        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertEquals(
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(file),
                    "it names the user's files and projects: owner-only, like the rest of the config dir");
        }
    }

    @Test
    void attachingASecondSessionStartsTheFileAgain(@TempDir Path configDir) throws Exception {
        Path file = DebugLog.sessionFile(configDir);
        Files.writeString(file, "left over from the previous run\n");
        DebugLog.clear();
        DebugLog.append("this session");

        DebugLog.attachFile(configDir);

        assertEquals(List.of("this session"), Files.readAllLines(file));
    }

    @Test
    void aSessionFileThatCannotBeWrittenOnlyDisablesTheMirror(@TempDir Path configDir) throws Exception {
        Path notADirectory = Files.writeString(configDir.resolve("config"), "a file where the folder belongs");
        DebugLog.clear();

        DebugLog.attachFile(notADirectory);
        DebugLog.append("still captured");

        assertEquals("still captured", DebugLog.snapshot());
        assertEquals("a file where the folder belongs", Files.readString(notADirectory), "and nothing was replaced");
    }

    @Test
    void withNoConfigDirThereIsNoSessionFile() {
        DebugLog.clear();

        DebugLog.attachFile(null);
        DebugLog.append("in memory only");

        assertNull(DebugLog.sessionFile(null));
        assertEquals("in memory only", DebugLog.snapshot());
    }

    @Test
    void aRecordWithNoMessageOrABrokenPatternIsStillALine() {
        LogRecord empty = new LogRecord(Level.INFO, null);
        empty.setLoggerName("com.editora.ui.Quiet");
        String blank = DebugLog.format(empty);
        assertTrue(blank.endsWith("INFO  Quiet: "), blank);

        LogRecord broken = new LogRecord(Level.WARNING, "took {0,number,#.#.#} ms for {0");
        broken.setLoggerName("Timer.");
        broken.setParameters(new Object[] {"not a number"});
        String raw = DebugLog.format(broken);
        assertTrue(raw.endsWith("WARNING  Timer.: took {0,number,#.#.#} ms for {0"), raw);

        LogRecord literal = new LogRecord(Level.INFO, "100% of {braces} kept");
        literal.setParameters(new Object[] {"unused"});
        assertTrue(DebugLog.format(literal).endsWith("?: 100% of {braces} kept"), "no {0: not a pattern at all");
        assertFalse(DebugLog.format(literal).contains("unused"));
    }
}
