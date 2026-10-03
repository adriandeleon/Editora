package com.editora.process;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Startup reaping against real processes and real ledger files, when two Editora processes share one config
 * dir.
 *
 * <p>A launch that is not a plain "open these files" (no file argument, {@code --project},
 * {@code --new-instance}, {@code --diff-ui}) is a second process on the same directory. It used to read the
 * shared ledger at startup, force-kill every live pid in it — the first editor's language servers, debug
 * adapters, builds and running programs — and then truncate the file the first editor relied on.
 *
 * <p>{@code sleep} stands in for both the other editor and its servers, so Windows sits this out.
 */
@DisabledOnOs(OS.WINDOWS)
class ProcessRegistryLedgerProcessTest {

    @TempDir
    Path configDir;

    private Path legacy;
    private final List<Process> started = new java.util.ArrayList<>();

    @BeforeEach
    void pointTheLedgerAtTheTempConfigDir() {
        legacy = configDir.resolve("spawned-servers.txt");
        ProcessRegistry.setLedgerFile(legacy);
    }

    @AfterEach
    void detachTheLedgerAndStopEverything() {
        ProcessRegistry.setLedgerFile(null);
        for (Process p : started) {
            ProcessRegistry.untrack(p);
            p.destroyForcibly();
        }
    }

    private Process sleeper() throws Exception {
        Process p = new ProcessBuilder("sleep", "60").start();
        started.add(p);
        return p;
    }

    private static ProcessRegistry.LedgerEntry row(Process server, ProcessRegistry.Owner owner) {
        ProcessHandle.Info info = server.info();
        return new ProcessRegistry.LedgerEntry(
                server.pid(),
                info.startInstant().map(Instant::toEpochMilli).orElse(0L),
                info.command().orElse(""),
                owner);
    }

    @Test
    void theServersOfAnotherRunningEditorAreLeftAloneAndSoIsItsLedger() throws Exception {
        Process otherEditor = sleeper();
        Process itsServer = sleeper();
        ProcessRegistry.Owner owner = ProcessRegistry.Owner.of(otherEditor.toHandle());
        Path itsLedger = ProcessRegistry.ownLedgerFor(legacy, owner);
        Files.write(itsLedger, List.of(row(itsServer, owner).format()));
        List<String> before = Files.readAllLines(itsLedger);

        ProcessRegistry.reapOrphans();

        assertTrue(itsServer.isAlive(), "a second instance must not kill the first one's live language server");
        assertEquals(before, Files.readAllLines(itsLedger), "nor rewrite or clear the first one's ledger");
    }

    @Test
    void onceThatEditorIsGoneItsServersAreOrphansAndAreReaped() throws Exception {
        Process otherEditor = sleeper();
        Process itsServer = sleeper();
        ProcessRegistry.Owner owner = ProcessRegistry.Owner.of(otherEditor.toHandle());
        Path itsLedger = ProcessRegistry.ownLedgerFor(legacy, owner);
        Files.write(itsLedger, List.of(row(itsServer, owner).format()));

        otherEditor.destroyForcibly(); // a hard crash: no shutdown hook, the ledger stays behind
        assertTrue(otherEditor.waitFor(10, TimeUnit.SECONDS));
        ProcessRegistry.reapOrphans();

        assertTrue(itsServer.waitFor(10, TimeUnit.SECONDS), "the dead editor's leaked server must be reaped");
        assertFalse(Files.exists(itsLedger), "and its ledger, which nobody will write again, removed");
    }

    @Test
    void aLedgerLeftByAnEarlierVersionIsStillReaped() throws Exception {
        Process leaked = sleeper();
        Files.write(legacy, List.of(row(leaked, ProcessRegistry.Owner.NONE).format(), "junk line"));

        ProcessRegistry.reapOrphans();

        assertTrue(leaked.waitFor(10, TimeUnit.SECONDS), "an old-format row has no owner and is reaped as before");
        assertFalse(Files.exists(legacy));
    }

    @Test
    void aProcessRecordsItsChildrenInItsOwnFileAndNeverInTheSharedOne() throws Exception {
        Process otherEditor = sleeper();
        Process itsServer = sleeper();
        ProcessRegistry.Owner owner = ProcessRegistry.Owner.of(otherEditor.toHandle());
        Path itsLedger = ProcessRegistry.ownLedgerFor(legacy, owner);
        Files.write(itsLedger, List.of(row(itsServer, owner).format()));
        List<String> othersRows = Files.readAllLines(itsLedger);
        Path mine = ProcessRegistry.ownLedgerFor(legacy, ProcessRegistry.Owner.of(ProcessHandle.current()));

        Process myServer = sleeper();
        ProcessRegistry.track(myServer);

        ProcessRegistry.LedgerEntry myRow = rowFor(mine, myServer.pid());
        assertEquals(ProcessHandle.current().pid(), myRow.owner().pid(), "stamped with this process");
        assertFalse(Files.exists(legacy), "the shared legacy ledger is never written");
        assertEquals(othersRows, Files.readAllLines(itsLedger), "tracking does not touch another editor's rows");

        // Reaping while we are running must treat our own rows as live, too.
        ProcessRegistry.reapOrphans();
        assertTrue(myServer.isAlive());
        assertEquals(myRow, rowFor(mine, myServer.pid()));

        ProcessRegistry.untrack(myServer);
        assertEquals(null, rowFor(mine, myServer.pid()), "an exited child's row is dropped from our ledger");
        assertEquals(othersRows, Files.readAllLines(itsLedger));
    }

    /** The row for {@code pid} in {@code ledger}, or {@code null} (also when the ledger was removed as empty). */
    private static ProcessRegistry.LedgerEntry rowFor(Path ledger, long pid) throws Exception {
        if (!Files.exists(ledger)) {
            return null;
        }
        return ProcessRegistry.parseLedger(Files.readAllLines(ledger)).stream()
                .filter(e -> e.pid() == pid)
                .findFirst()
                .orElse(null);
    }
}
