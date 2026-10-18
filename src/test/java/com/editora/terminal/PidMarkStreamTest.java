package com.editora.terminal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The pid line a container or WSL terminal's start script prints, taken out of what the terminal shows. */
class PidMarkStreamTest {

    private final AtomicLong pid = new AtomicLong(-1);

    private String through(InputStream in) throws IOException {
        return new String(new PidMarkStream(in, pid::set).readAllBytes(), StandardCharsets.UTF_8);
    }

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theMarkLineIsTakenOutAndItsPidReported() throws IOException {
        assertEquals("$ prompt", through(bytes("\u001eEDITORA 4242\r\n$ prompt")));
        assertEquals(4242, pid.get());
    }

    @Test
    void whatALauncherPrintsBeforeTheMarkIsKept() throws IOException {
        assertEquals("warning: x\n$ ", through(bytes("warning: x\n\u001eEDITORA 7\r\n$ ")));
        assertEquals(7, pid.get());
    }

    @Test
    void aMarkSplitAcrossReadsIsStillFound() throws IOException {
        // One byte at a time: the script's line can arrive in pieces.
        InputStream trickle = new InputStream() {
            private final byte[] all = "\u001eEDITORA 99\r\nhello".getBytes(StandardCharsets.UTF_8);
            private int at;

            @Override
            public int read() {
                return at < all.length ? all[at++] & 0xff : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (at >= all.length) return -1;
                b[off] = all[at++];
                return 1;
            }
        };
        assertEquals("hello", through(trickle));
        assertEquals(99, pid.get());
    }

    @Test
    void outputWithoutAMarkPassesThroughUnchanged() throws IOException {
        assertEquals("Error: no such container\n", through(bytes("Error: no such container\n")));
        assertEquals(-1, pid.get());
    }

    @Test
    void aMarkWithoutANumberIsNotOurs() throws IOException {
        assertEquals("\u001eEDITORA soon\nrest", through(bytes("\u001eEDITORA soon\nrest")));
        assertEquals(-1, pid.get());
    }

    @Test
    void aLongPreambleStopsTheSearchAndIsPassedOn() throws IOException {
        String noise = "x".repeat(PidMarkStream.LOOK_AHEAD + 10);
        assertEquals(noise + "\u001eEDITORA 1\n", through(bytes(noise + "\u001eEDITORA 1\n")));
        assertEquals(-1, pid.get());
    }
}
