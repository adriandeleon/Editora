package com.editora.process;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessRegistryTest {

    @Test
    void ledgerRoundTrips() {
        ProcessRegistry.LedgerEntry e = new ProcessRegistry.LedgerEntry(4321L, 1_700_000_000_000L, "/usr/bin/jdtls");
        Optional<ProcessRegistry.LedgerEntry> back = ProcessRegistry.LedgerEntry.parse(e.format());
        assertTrue(back.isPresent());
        assertEquals(e, back.get());
    }

    @Test
    void parseRejectsGarbageAndBlanks() {
        assertTrue(ProcessRegistry.LedgerEntry.parse("").isEmpty());
        assertTrue(ProcessRegistry.LedgerEntry.parse("   ").isEmpty());
        assertTrue(ProcessRegistry.LedgerEntry.parse("notanumber\t123\t/bin/x").isEmpty());
        assertTrue(ProcessRegistry.LedgerEntry.parse("123").isEmpty()); // too few fields
    }

    @Test
    void parseLedgerSkipsBadLinesKeepsGood() {
        List<ProcessRegistry.LedgerEntry> out =
                ProcessRegistry.parseLedger(List.of("100\t5\t/bin/a", "", "junk line", "200\t6\t/bin/b"));
        assertEquals(2, out.size());
        assertEquals(100L, out.get(0).pid());
        assertEquals("/bin/b", out.get(1).command());
    }

    @Test
    void commandWithoutStartTimeMatchesOnCommandAlone() {
        var e = new ProcessRegistry.LedgerEntry(1L, 0L, "/usr/bin/jdtls"); // no recorded start
        assertTrue(ProcessRegistry.shouldReap(e, Optional.of(123L), Optional.of("/usr/bin/jdtls")));
        assertFalse(ProcessRegistry.shouldReap(e, Optional.of(123L), Optional.of("/usr/bin/other")));
    }

    @Test
    void reapsOnlyWhenStartTimeAndCommandBothMatch() {
        var e = new ProcessRegistry.LedgerEntry(1L, 999L, "/usr/bin/jdtls");
        // Same pid reused by a different process: command differs -> never reap.
        assertFalse(ProcessRegistry.shouldReap(e, Optional.of(999L), Optional.of("/usr/bin/python3")));
        // Same command but a different start instant (pid reuse, same binary) -> don't reap.
        assertFalse(ProcessRegistry.shouldReap(e, Optional.of(1000L), Optional.of("/usr/bin/jdtls")));
        // Recorded a start time, but the live process reports none -> can't prove identity -> don't reap.
        assertFalse(ProcessRegistry.shouldReap(e, Optional.empty(), Optional.of("/usr/bin/jdtls")));
        // Everything matches -> reap.
        assertTrue(ProcessRegistry.shouldReap(e, Optional.of(999L), Optional.of("/usr/bin/jdtls")));
    }

    @Test
    void blankOrMissingCommandNeverReaps() {
        var blank = new ProcessRegistry.LedgerEntry(1L, 5L, "");
        assertFalse(ProcessRegistry.shouldReap(blank, Optional.of(5L), Optional.of("/usr/bin/jdtls")));
        var e = new ProcessRegistry.LedgerEntry(1L, 5L, "/usr/bin/jdtls");
        assertFalse(ProcessRegistry.shouldReap(e, Optional.of(5L), Optional.empty()));
    }

    // --- rows carry their owner: a second Editora on the same config dir must not reap the first one's ---

    private static final ProcessRegistry.Owner SELF = new ProcessRegistry.Owner(500L, 5_000L, "/opt/editora/bin/java");
    private static final ProcessRegistry.Owner OTHER = new ProcessRegistry.Owner(700L, 7_000L, "/opt/editora/bin/java");

    private static Optional<ProcessRegistry.Live> live(Long start, String command) {
        return Optional.of(new ProcessRegistry.Live(Optional.ofNullable(start), Optional.ofNullable(command)));
    }

    @Test
    void anOwnedRowRoundTripsWithItsOwner() {
        var e = new ProcessRegistry.LedgerEntry(4321L, 1_700_000_000_000L, "/usr/bin/jdtls", OTHER);

        Optional<ProcessRegistry.LedgerEntry> back = ProcessRegistry.LedgerEntry.parse(e.format());

        assertEquals(Optional.of(e), back);
        assertEquals(OTHER, back.orElseThrow().owner());
    }

    @Test
    void aRowWithoutAnOwnerIsStillWrittenInTheOldFormat() {
        // What earlier versions wrote, byte for byte — and what they would still be able to read.
        assertEquals("100\t5\t/bin/a", new ProcessRegistry.LedgerEntry(100L, 5L, "/bin/a").format());
        var legacy = ProcessRegistry.LedgerEntry.parse("100\t5\t/bin/a").orElseThrow();
        assertEquals(ProcessRegistry.Owner.NONE, legacy.owner());
        assertFalse(legacy.owner().known());
    }

    @Test
    void aTornOwnedRowIsSkippedRatherThanReadAsUnowned() {
        // Half an owner must never degrade to "no owner": an unowned row is reapable on sight.
        assertTrue(ProcessRegistry.LedgerEntry.parse("100\t5\t/bin/a\t700").isEmpty());
        assertTrue(
                ProcessRegistry.LedgerEntry.parse("100\t5\t/bin/a\t700\t7000").isEmpty());
        assertTrue(ProcessRegistry.LedgerEntry.parse("100\t5\t/bin/a\tx\t7000\t/bin/java")
                .isEmpty());
        assertTrue(ProcessRegistry.LedgerEntry.parse("100\t5\t/bin/a\t700\t7000\t/bin/java\textra")
                .isEmpty());
    }

    @Test
    void tabsAndNewlinesInACommandCannotShiftTheOwnerFields() {
        var e = new ProcessRegistry.LedgerEntry(1L, 2L, "/odd\tpath\n", new ProcessRegistry.Owner(3L, 4L, "/own\ter"));

        var back = ProcessRegistry.LedgerEntry.parse(e.format()).orElseThrow();

        assertEquals(3L, back.owner().pid());
        assertEquals(4L, back.owner().startEpochMillis());
    }

    @Test
    void aLegacyRowHasNoOwnerToAskSoItIsJudgedOnTheChildAlone() {
        assertTrue(ProcessRegistry.ownerGone(ProcessRegistry.Owner.NONE, SELF, live(1L, "/anything")));
        assertTrue(ProcessRegistry.ownerGone(null, SELF, Optional.empty()));
    }

    @Test
    void anOwnerWhosePidIsNotRunningIsGone() {
        assertTrue(ProcessRegistry.ownerGone(OTHER, SELF, Optional.empty()));
    }

    @Test
    void aRunningOwnerWithTheRecordedStartInstantIsAlive() {
        assertFalse(ProcessRegistry.ownerGone(OTHER, SELF, live(7_000L, "/opt/editora/bin/java")));
        // The start instant is the identity; a differently-reported executable does not override it.
        assertFalse(ProcessRegistry.ownerGone(OTHER, SELF, live(7_000L, "/elsewhere/java")));
    }

    @Test
    void aPidReusedBySomethingElseMeansTheOwnerIsGone() {
        assertTrue(ProcessRegistry.ownerGone(OTHER, SELF, live(9_999L, "/usr/bin/python3")));
    }

    @Test
    void aRunningEditorWhoseStartInstantLooksDifferentIsNotDeclaredDead() {
        // Two JVMs can disagree about one process's start time (on Linux it is derived from the boot time,
        // which moves when the wall clock is stepped). Killing a live editor's servers over that would be
        // far worse than leaving a leaked one a little longer.
        assertFalse(ProcessRegistry.ownerGone(OTHER, SELF, live(7_000L + 1_000L, "/opt/editora/bin/java")));
        assertFalse(ProcessRegistry.ownerGone(OTHER, SELF, live(null, "/opt/editora/bin/java")));
        // Nothing at all to compare: cannot prove it is gone.
        assertFalse(ProcessRegistry.ownerGone(OTHER, SELF, live(null, null)));
        assertFalse(ProcessRegistry.ownerGone(new ProcessRegistry.Owner(700L, 0L, ""), SELF, live(8_000L, "/x/java")));
    }

    @Test
    void aRowStampedWithOurOwnPidButAnotherStartIsALeftoverFromADeadProcess() {
        // We hold the pid, so whoever had it before is dead — whatever the OS says about the pid now (us).
        var previous = new ProcessRegistry.Owner(SELF.pid(), 1_234L, SELF.command());
        assertTrue(ProcessRegistry.ownerGone(previous, SELF, live(SELF.startEpochMillis(), SELF.command())));
        // Our own rows are, of course, not orphans.
        assertFalse(ProcessRegistry.ownerGone(SELF, SELF, live(SELF.startEpochMillis(), SELF.command())));
    }

    @Test
    void aLiveOwnersServerIsNeverReapedEvenThoughItsIdentityMatches() {
        var row = new ProcessRegistry.LedgerEntry(1L, 999L, "/usr/bin/jdtls", OTHER);

        // The first editor is running: its language server is in use, not leaked.
        assertFalse(ProcessRegistry.shouldReap(
                row, SELF, live(7_000L, "/opt/editora/bin/java"), Optional.of(999L), Optional.of("/usr/bin/jdtls")));
        // The first editor is gone: now it is an orphan — provided it is still the process we recorded.
        assertTrue(ProcessRegistry.shouldReap(
                row, SELF, Optional.empty(), Optional.of(999L), Optional.of("/usr/bin/jdtls")));
        assertFalse(ProcessRegistry.shouldReap(
                row, SELF, Optional.empty(), Optional.of(1_000L), Optional.of("/usr/bin/jdtls")));
        assertFalse(ProcessRegistry.shouldReap(
                row, SELF, Optional.empty(), Optional.of(999L), Optional.of("/usr/bin/python3")));
    }

    @Test
    void aLegacyRowIsReapedExactlyAsBefore() {
        var row = new ProcessRegistry.LedgerEntry(1L, 999L, "/usr/bin/jdtls");

        assertTrue(ProcessRegistry.shouldReap(
                row, SELF, Optional.empty(), Optional.of(999L), Optional.of("/usr/bin/jdtls")));
        assertFalse(ProcessRegistry.shouldReap(
                row, SELF, Optional.empty(), Optional.of(999L), Optional.of("/usr/bin/python3")));
    }

    @Test
    void eachProcessWritesALedgerNamedAfterItself() {
        java.nio.file.Path legacy = java.nio.file.Path.of("cfg", "spawned-servers.txt");

        java.nio.file.Path own = ProcessRegistry.ownLedgerFor(legacy, OTHER);

        assertEquals(java.nio.file.Path.of("cfg", "spawned-servers.700.7000.txt"), own);
        assertTrue(ProcessRegistry.isOwnLedgerName(
                "spawned-servers.txt", own.getFileName().toString()));
    }

    @Test
    void onlyPerProcessLedgersAreRecognizedBesideTheLegacyOne() {
        assertTrue(ProcessRegistry.isOwnLedgerName("spawned-servers.txt", "spawned-servers.12.34.txt"));
        assertFalse(ProcessRegistry.isOwnLedgerName("spawned-servers.txt", "spawned-servers.txt"), "the legacy file");
        assertFalse(ProcessRegistry.isOwnLedgerName("spawned-servers.txt", ".spawned-servers.12.34.txt.tmp"));
        assertFalse(ProcessRegistry.isOwnLedgerName("spawned-servers.txt", "spawned-servers.12.txt"));
        assertFalse(ProcessRegistry.isOwnLedgerName("spawned-servers.txt", "spawned-servers.a.b.txt"));
        assertFalse(ProcessRegistry.isOwnLedgerName("spawned-servers.txt", "spawned-servers.12.34.json"));
        assertFalse(ProcessRegistry.isOwnLedgerName("spawned-servers.txt", "settings.json"));
    }
}
