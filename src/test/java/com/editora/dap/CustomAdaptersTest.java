package com.editora.dap;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The user's own debug adapters, read from debug-adapters.json. */
class CustomAdaptersTest {

    @Test
    void eachTransportIsReadAndTheProfileFollowsIt() {
        CustomAdapters.Parsed p = CustomAdapters.parse("""
                {
                  // comments too
                  "adapters": [
                    { "type": "lldb-dap", "name": "LLDB", "command": ["lldb-dap"], "languages": ["zig"],
                      "launch": { "program": "${fileDirname}/a" } },
                    { "type": "net", "command": ["netcoredbg", "--interpreter=vscode", "--server={port}"],
                      "transport": "port", "adapterID": "coreclr" },
                    { "type": "dbg", "command": ["dbg"], "transport": "announce", "announce": "port (\\\\d+)" },
                  ]
                }
                """);
        assertEquals(List.of(), p.problems());
        assertEquals(3, p.adapters().size());
        CustomAdapters.Adapter lldb = p.adapters().getFirst();
        DebugLanguage profile = lldb.profile();
        assertEquals("lldb-dap", profile.id());
        assertEquals("LLDB", profile.displayName());
        assertEquals(java.util.Set.of("zig"), profile.editorLanguages());
        assertEquals(ExternalAdapter.Transport.STDIO, profile.external().transport());
        assertEquals(Map.of("program", "${fileDirname}/a"), lldb.launch());
        DebugLanguage net = p.adapters().get(1).profile();
        assertEquals(ExternalAdapter.Transport.PORT_ARGUMENT, net.external().transport());
        assertEquals("coreclr", net.adapterId());
        assertEquals("net", net.displayName(), "its type when it has no name");
        assertEquals(Map.of(), p.adapters().get(1).launch());
        assertEquals(4711, p.adapters().get(2).profile().external().announcedPort("listening on port 4711"));
        assertEquals(
                1,
                CustomAdapters.parse("[{\"type\": \"x\", \"command\": [\"x\"]}]")
                        .adapters()
                        .size(),
                "a bare list");
    }

    @Test
    void anAdapterThatCannotBeUsedIsSaidAndSkipped() {
        CustomAdapters.Parsed p = CustomAdapters.parse("""
                { "adapters": [
                  "not an object",
                  { "command": ["x"] },
                  { "type": "go", "command": ["x"] },
                  { "type": "a", "command": ["a"] },
                  { "type": "a", "command": ["a"] },
                  { "type": "b", "command": "b" },
                  { "type": "c", "command": ["c"], "transport": "pipe" },
                  { "type": "d", "command": ["d"], "transport": "port" },
                  { "type": "e", "command": ["e", "{port}"] },
                  { "type": "f", "command": ["f"], "transport": "announce" },
                  { "type": "g", "command": ["g"], "transport": "announce", "announce": "(" }
                ] }
                """);
        assertEquals(1, p.adapters().size());
        assertEquals(10, p.problems().size(), String.valueOf(p.problems()));
        assertTrue(p.problems().get(2).contains("Editora's own"));
        assertTrue(p.problems().get(3).contains("twice"));
        assertEquals(List.of("no \"adapters\" list"), CustomAdapters.parse("{}").problems());
        assertEquals(1, CustomAdapters.parse("{ broken").problems().size());
    }

    @Test
    void aProjectsAdapterReplacesTheUsersOfTheSameTypeAndTheyAreFoundByIdAndLanguage() {
        var user = CustomAdapters.parse("[{\"type\": \"x\", \"command\": [\"one\"], \"languages\": [\"zig\"]}]");
        var project = CustomAdapters.parse("[{\"type\": \"x\", \"command\": [\"two\"], \"languages\": [\"zig\"]}]");
        List<CustomAdapters.Adapter> merged = CustomAdapters.merge(List.of(user.adapters(), project.adapters()));
        assertEquals(1, merged.size());
        assertEquals(List.of("two"), merged.getFirst().profile().external().command());
        try {
            DebugLanguages.setCustom(List.of(merged.getFirst().profile()));
            assertSame(merged.getFirst().profile(), DebugLanguages.byId("x"));
            assertSame(merged.getFirst().profile(), DebugLanguages.forLanguage("zig"));
            assertSame(DebugLanguages.CPP, DebugLanguages.forLanguage("c"), "built-ins first");
            assertSame(DebugLanguages.GDB, DebugLanguages.byId("gdb"));
        } finally {
            DebugLanguages.setCustom(List.of());
        }
        assertNull(DebugLanguages.byId("x"));
        assertTrue(CustomAdapters.parse(CustomAdapters.EXAMPLE).problems().isEmpty(), "the example reads");
    }
}
