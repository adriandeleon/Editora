package com.editora.editops;

import java.util.List;

import com.editora.editops.KillRing.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the pure kill ring (no toolkit). */
class KillRingTest {

    private static KillRing ring(String... kills) {
        KillRing r = new KillRing();
        for (String k : kills) {
            r.kill(k, Direction.FORWARD, false);
        }
        return r;
    }

    @Test
    void emptyRingYanksNothing() {
        KillRing r = new KillRing();
        assertTrue(r.isEmpty());
        assertNull(r.current());
        assertNull(r.rotate());
        assertEquals(0, r.size());
    }

    @Test
    void killPushesNewestFirst() {
        KillRing r = ring("one", "two", "three");
        assertEquals("three", r.current());
        assertEquals(List.of("three", "two", "one"), r.entries());
    }

    @Test
    void emptyKillIsIgnored() {
        KillRing r = ring("one");
        r.kill("", Direction.FORWARD, false);
        r.kill(null, Direction.FORWARD, false);
        assertEquals(1, r.size());
        assertEquals("one", r.current());
    }

    // --- consecutive-kill accumulation ---------------------------------------------------------

    @Test
    void consecutiveForwardKillsAppendIntoOneEntry() {
        KillRing r = new KillRing();
        r.kill("first line\n", Direction.FORWARD, false);
        r.kill("second line\n", Direction.FORWARD, true);
        r.kill("third line\n", Direction.FORWARD, true);
        assertEquals(1, r.size(), "consecutive kills accumulate rather than pushing entries");
        assertEquals("first line\nsecond line\nthird line\n", r.current());
    }

    @Test
    void consecutiveBackwardKillsPrependSoTextStaysInReadingOrder() {
        // M-DEL M-DEL over "alpha beta|" kills "beta" then "alpha ", and C-y must restore "alpha beta".
        KillRing r = new KillRing();
        r.kill("beta", Direction.BACKWARD, false);
        r.kill("alpha ", Direction.BACKWARD, true);
        assertEquals(1, r.size());
        assertEquals("alpha beta", r.current());
    }

    @Test
    void mergeOnAnEmptyRingJustPushes() {
        KillRing r = new KillRing();
        r.kill("text", Direction.FORWARD, true);
        assertEquals(1, r.size());
        assertEquals("text", r.current());
    }

    @Test
    void nonMergingKillAfterAMergedRunStartsANewEntry() {
        KillRing r = new KillRing();
        r.kill("a", Direction.FORWARD, false);
        r.kill("b", Direction.FORWARD, true);
        r.kill("c", Direction.FORWARD, false);
        assertEquals(List.of("c", "ab"), r.entries());
    }

    // --- yank pointer --------------------------------------------------------------------------

    @Test
    void rotateStepsTowardsOlderEntriesAndWraps() {
        KillRing r = ring("one", "two", "three");
        assertEquals("three", r.current());
        assertEquals("two", r.rotate());
        assertEquals("one", r.rotate());
        assertEquals("three", r.rotate(), "wraps back to the newest");
    }

    @Test
    void rotateOnASingleEntryReturnsThatEntry() {
        KillRing r = ring("only");
        assertEquals("only", r.rotate());
        assertEquals("only", r.current());
    }

    @Test
    void killResetsTheYankPointer() {
        KillRing r = ring("one", "two");
        r.rotate(); // pointer now on "one"
        assertEquals("one", r.current());
        r.kill("three", Direction.FORWARD, false);
        assertEquals("three", r.current(), "a fresh kill is what the next yank inserts");
        assertEquals(0, r.yankIndex());
    }

    @Test
    void saveResetsTheYankPointerAndNeverMerges() {
        KillRing r = new KillRing();
        r.kill("killed", Direction.FORWARD, false);
        r.save("copied");
        r.save("copied again");
        assertEquals(List.of("copied again", "copied", "killed"), r.entries());
        assertEquals(0, r.yankIndex());
    }

    @Test
    void resetYankReturnsToTheNewestEntry() {
        KillRing r = ring("one", "two", "three");
        r.rotate();
        r.resetYank();
        assertEquals("three", r.current());
    }

    // --- external clipboard ---------------------------------------------------------------------

    @Test
    void adoptExternalPushesTextCopiedElsewhere() {
        KillRing r = ring("mine");
        assertTrue(r.adoptExternal("from another app"));
        assertEquals("from another app", r.current());
        assertEquals("mine", r.rotate(), "the earlier kill is still reachable via yank-pop");
    }

    @Test
    void adoptExternalIgnoresTextAlreadyAtTheYankPointer() {
        KillRing r = ring("mine");
        assertFalse(r.adoptExternal("mine"), "our own kill came back off the clipboard");
        assertEquals(1, r.size());
    }

    @Test
    void adoptExternalIgnoresEmptyAndNull() {
        KillRing r = ring("mine");
        assertFalse(r.adoptExternal(""));
        assertFalse(r.adoptExternal(null));
        assertEquals(1, r.size());
    }

    @Test
    void adoptExternalComparesAgainstTheYankPointerNotTheHead() {
        // Mid-yank-pop the clipboard still holds the *yanked* entry, which must not be re-pushed.
        KillRing r = ring("one", "two");
        r.rotate(); // pointer on "one"
        assertFalse(r.adoptExternal("one"));
        assertEquals(2, r.size());
    }

    // --- bounds ----------------------------------------------------------------------------------

    @Test
    void ringIsBoundedAndDropsTheOldest() {
        KillRing r = new KillRing(3);
        r.kill("a", Direction.FORWARD, false);
        r.kill("b", Direction.FORWARD, false);
        r.kill("c", Direction.FORWARD, false);
        r.kill("d", Direction.FORWARD, false);
        assertEquals(List.of("d", "c", "b"), r.entries());
    }

    @Test
    void maxMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new KillRing(0));
    }

    @Test
    void entriesAreUnmodifiable() {
        KillRing r = ring("a");
        assertThrows(UnsupportedOperationException.class, () -> r.entries().add("b"));
    }

    @Test
    void clearEmptiesTheRing() {
        KillRing r = ring("a", "b");
        r.clear();
        assertTrue(r.isEmpty());
        assertNull(r.current());
    }
}
