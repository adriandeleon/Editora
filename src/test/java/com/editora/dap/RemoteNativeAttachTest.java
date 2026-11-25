package com.editora.dap;

import java.nio.file.Path;
import java.util.LinkedHashMap;
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

    @Test
    void aProgramBuiltInAnotherFolderIsMappedToItsSourcesHereOnEachDebugger() {
        Map<String, String> map = Map.of("/build/app", "/home/me/app");
        Map<String, Object> gdb = RemoteNativeAttach.gdb("/home/me/app/tally", "board:2345", map);
        assertEquals(map, gdb.get(RemoteNativeAttach.SOURCE_FILE_MAP), "GDB's: turned into substitute-path");
        assertFalse(
                RemoteNativeAttach.gdb("/p", "board:2345").containsKey(RemoteNativeAttach.SOURCE_FILE_MAP),
                "no map: none sent");
        assertEquals(
                map, RemoteNativeAttach.codelldb("/p", "board:2345", false, map).get("sourceMap"), "CodeLLDB's own");
        assertFalse(RemoteNativeAttach.codelldb("/p", "board:2345", false).containsKey("sourceMap"));
    }

    @Test
    void gdbIsToldEachMappingBeforeItReadsTheProgramsSymbols() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("/build/app", "/home/me/app");
        map.put("/src/with space", "/home/me/a \"q\" b");
        assertEquals(
                List.of(
                        "-iex",
                        "set substitute-path \"/build/app\" \"/home/me/app\"",
                        "-iex",
                        "set substitute-path \"/src/with space\" \"/home/me/a \\\"q\\\" b\""),
                RemoteNativeAttach.gdbSubstitutions(map));
        assertEquals("\"C:\\\\src\\\\app\"", RemoteNativeAttach.gdbQuoted("C:\\src\\app"), "a backslash escaped");
        assertEquals(List.of(), RemoteNativeAttach.gdbSubstitutions(Map.of()));
    }

    @Test
    void cppdbgsSourceFileMapIsReadAsVsCodeReadsIt() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("/build", "/here");
        raw.put("/lib", Map.of("editorPath", "/here/lib", "useForBreakpoints", true));
        raw.put("/blank", " ");
        raw.put("", "/x");
        raw.put("/num", 3);
        assertEquals(Map.of("/build", "/here", "/lib", "/here/lib"), RemoteNativeAttach.sourceFileMap(raw));
        assertEquals(Map.of(), RemoteNativeAttach.sourceFileMap("/build"));
        assertEquals(Map.of(), RemoteNativeAttach.sourceFileMap(null));
    }

    @Test
    void theFolderAskedForMapsToTheSourcesHereUnlessItIsTheSame() {
        Path here = Path.of("/home/me/app").toAbsolutePath();
        assertEquals(Map.of("/build/app", here.toString()), RemoteNativeAttach.folderMap(here, " /build/app "));
        assertEquals(Map.of(), RemoteNativeAttach.folderMap(here, ""), "blank: the same paths");
        assertEquals(Map.of(), RemoteNativeAttach.folderMap(here, here.toString()));
        assertEquals(Map.of(), RemoteNativeAttach.folderMap(null, "/build/app"));
    }
}
