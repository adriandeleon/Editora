package com.editora.terminal;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ChildProcessesTest {

    @Test
    void parsesTheKernelsSpaceSeparatedListWithItsTrailingSpace() {
        assertEquals(List.of(1243L, 77L), ChildProcesses.parse("1243 77 \n"));
    }

    @Test
    void anEmptyFileIsNoChildren() {
        assertEquals(List.of(), ChildProcesses.parse(""));
        assertEquals(List.of(), ChildProcesses.parse("\n"));
        assertEquals(List.of(), ChildProcesses.parse(null));
    }

    @Test
    void skipsAnythingThatIsNotAPositivePid() {
        assertEquals(List.of(5L), ChildProcesses.parse("x 0 -3 5"));
    }

    @Test
    void aProcessThatDoesNotExistHasNoChildren() {
        assertEquals(List.of(), ChildProcesses.of(Integer.MAX_VALUE - 1));
        assertEquals(List.of(), ChildProcesses.of(0));
    }

    /** Against the real OS: whichever path answers, it has to find a child this test started. */
    @Test
    void findsAChildThisProcessStarted() throws Exception {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase().startsWith("windows"));
        Process sleeper = new ProcessBuilder("sleep", "30").start();
        try {
            List<Long> children = ChildProcesses.of(ProcessHandle.current().pid());
            assertTrue(children.contains(sleeper.pid()), children + " lacks " + sleeper.pid());
        } finally {
            sleeper.destroyForcibly();
            sleeper.waitFor(5, TimeUnit.SECONDS);
        }
    }
}
