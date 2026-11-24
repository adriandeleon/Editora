package com.editora.dap;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteNativeAttachTest {

    @Test
    void gdbAttachesToTheServerWithTheProgramsSymbols() {
        assertEquals(
                Map.of("type", "gdb", "request", "attach", "program", "/build/tally", "target", "board:2345"),
                RemoteNativeAttach.gdb("/build/tally", "board:2345"));
    }

    @Test
    void codelldbMakesTheTargetThenConnectsByGdbRemote() {
        Map<String, Object> m = RemoteNativeAttach.codelldb("/my build/tally", "10.0.0.5:2345", false);
        assertEquals("lldb", m.get("type"));
        assertEquals("launch", m.get("request"));
        assertEquals(List.of("target create \"/my build/tally\""), m.get("targetCreateCommands"));
        assertEquals(List.of("gdb-remote 10.0.0.5:2345"), m.get("processCreateCommands"));
        assertEquals(List.of("cpp"), m.get("sourceLanguages"));
        assertEquals(
                List.of("rust"), RemoteNativeAttach.codelldb("/t", "h:1", true).get("sourceLanguages"));
    }

    @Test
    void aRemoteRequestIsToldFromALocalOne() {
        assertTrue(RemoteNativeAttach.isRemote(RemoteNativeAttach.gdb("/t", "h:1")));
        assertTrue(RemoteNativeAttach.isRemote(RemoteNativeAttach.codelldb("/t", "h:1", false)));
        assertFalse(RemoteNativeAttach.isRemote(Map.of("type", "gdb", "request", "attach", "pid", 42)));
        assertFalse(RemoteNativeAttach.isRemote(Map.of("type", "lldb", "request", "launch", "program", "/t")));
    }

    @Test
    void anAddressIsHostAndPortALonePortIsThisMachines() {
        assertEquals("board:2345", RemoteNativeAttach.address(" board:2345 "));
        assertEquals("localhost:2345", RemoteNativeAttach.address("2345"));
        assertEquals("localhost:2345", RemoteNativeAttach.address(":2345"));
        assertEquals("[::1]:2345", RemoteNativeAttach.address("[::1]:2345"));
        assertNull(RemoteNativeAttach.address("board"));
        assertNull(RemoteNativeAttach.address("board:99999"));
        assertNull(RemoteNativeAttach.address("a b:1"));
        assertNull(RemoteNativeAttach.address(""));
    }
}
