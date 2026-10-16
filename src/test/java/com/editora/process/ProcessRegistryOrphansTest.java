package com.editora.process;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Programs a launcher started in a container or a WSL distribution outlive a crashed Editora (ending the
 * launcher here does not end them there): their ledger rows say how the next one reaches them, and only a
 * registered reaper ever acts on them — never this machine's process table.
 */
class ProcessRegistryOrphansTest {

    private static final List<String> RECORD =
            List.of("test-kind", "c0ffee", "vscode", "4242", "987654", "podman", "--remote", "a\tb%c\u001fd\ne");

    /** An Editora that is not running: no process has this pid. */
    private static final ProcessRegistry.Owner DEAD =
            new ProcessRegistry.Owner(999_999_999L, 1L, "/opt/editora/bin/java");

    @TempDir
    Path configDir;

    private Path legacy;

    @BeforeEach
    void pointTheLedgerAtTheTempConfigDir() {
        legacy = configDir.resolve("spawned-servers.txt");
        ProcessRegistry.setLedgerFile(legacy);
    }

    @AfterEach
    void detach() {
        ProcessRegistry.setLedgerFile(null);
    }

    private Path mine() {
        return ProcessRegistry.ownLedgerFor(legacy, ProcessRegistry.Owner.of(ProcessHandle.current()));
    }

    /** The rows for programs on a target in {@code file}: other tests in this JVM may have local rows there. */
    private List<ProcessRegistry.LedgerEntry> rows(Path file) throws Exception {
        return Files.exists(file)
                ? ProcessRegistry.parseLedger(Files.readAllLines(file)).stream()
                        .filter(row -> row.remote() != null)
                        .toList()
                : List.of();
    }

    @Test
    void aTargetsRowKeepsItsRecordExactlyAndIsNeverALocalPid() {
        ProcessRegistry.LedgerEntry row = ProcessRegistry.LedgerEntry.remote(RECORD, DEAD);
        String line = row.format();

        assertEquals(7, line.split("\t", -1).length, "one field more than earlier versions read: they skip it");
        assertFalse(line.contains("\n"));
        Optional<ProcessRegistry.LedgerEntry> back = ProcessRegistry.LedgerEntry.parse(line);
        assertTrue(back.isPresent());
        assertEquals(RECORD, back.get().remote());
        assertEquals(0L, back.get().pid());
        assertEquals(DEAD, back.get().owner());

        assertTrue(
                ProcessRegistry.LedgerEntry.parse(line.replaceFirst("^0\t", "4242\t"))
                        .isEmpty(),
                "a seventh field beside a local pid is not a row");
        assertTrue(
                ProcessRegistry.LedgerEntry.parse("0\t0\t\t0\t0\t\ttest-kind").isEmpty(), "nor one without an owner");
        assertTrue(ProcessRegistry.LedgerEntry.parse("0\t0\t\t5\t6\tjava\t").isEmpty(), "nor one with no record");
    }

    @Test
    void aTrackedProgramOnATargetIsRecordedUntilItEnds() throws Exception {
        FakeRemoteProcess p = new FakeRemoteProcess(true);
        p.record = RECORD;

        ProcessRegistry.track(p);
        List<ProcessRegistry.LedgerEntry> rows = List.of();
        for (int i = 0; i < 100 && rows.isEmpty(); i++) {
            Thread.sleep(50);
            rows = rows(mine());
        }
        assertEquals(1, rows.size(), "recorded in this process's own ledger");
        assertEquals(RECORD, rows.get(0).remote());

        p.exit(0);
        for (int i = 0; i < 100 && !rows.isEmpty(); i++) {
            Thread.sleep(50);
            rows = rows(mine());
        }
        assertEquals(List.of(), rows, "it ended: nothing left to reap");
    }

    @Test
    void aTargetThatEndsEditorasProgramsItselfRecordsNothing() throws Exception {
        FakeRemoteProcess p = new FakeRemoteProcess(true); // an SSH session's: no record
        ProcessRegistry.track(p);
        Thread.sleep(300);

        assertEquals(List.of(), rows(mine()));
        p.exit(0);
    }

    @Test
    void aDeadEditorsProgramsAreHandedToTheirReaperAndALiveOnesAreNot() throws Exception {
        List<List<String>> reaped = new java.util.concurrent.CopyOnWriteArrayList<>();
        ProcessRegistry.registerOrphanReaper("test-kind", fields -> reaped.add(fields));
        Path deadLedger = ProcessRegistry.ownLedgerFor(legacy, DEAD);
        Files.write(
                deadLedger,
                List.of(ProcessRegistry.LedgerEntry.remote(RECORD, DEAD).format()));
        // A running Editora: the process that started this test's JVM, whose ledger nothing else writes.
        ProcessRegistry.Owner running =
                ProcessRegistry.Owner.of(ProcessHandle.current().parent().orElseThrow());
        Path runningLedger = ProcessRegistry.ownLedgerFor(legacy, running);
        List<String> liveRecord = List.of("test-kind", "still-in-use");
        Files.write(
                runningLedger,
                List.of(ProcessRegistry.LedgerEntry.remote(liveRecord, running).format()));

        ProcessRegistry.reapOrphans();

        for (int i = 0; i < 100 && reaped.isEmpty(); i++) {
            Thread.sleep(50);
        }
        Thread.sleep(300); // time for a wrong second call to show
        assertEquals(
                List.of(RECORD.subList(1, RECORD.size())), reaped, "only the dead editor's program, with its fields");
        assertFalse(Files.exists(deadLedger), "the dead editor's ledger is gone");
        assertEquals(
                List.of(liveRecord),
                rows(runningLedger).stream()
                        .map(ProcessRegistry.LedgerEntry::remote)
                        .toList(),
                "a running editor's row stays");
    }

    @Test
    void aRowOfAKindNobodyReapsIsLeftAlone() {
        ProcessRegistry.registerOrphanReaper("test-throws", fields -> {
            throw new java.io.IOException("the engine is not running");
        });

        int reaped = ProcessRegistry.reapRemote(List.of(
                ProcessRegistry.LedgerEntry.remote(List.of("nobody-reaps-this", "x"), DEAD),
                ProcessRegistry.LedgerEntry.remote(List.of("test-throws", "y"), DEAD)));

        assertEquals(0, reaped);
    }
}
