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
}
