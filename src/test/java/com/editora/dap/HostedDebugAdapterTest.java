package com.editora.dap;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HostedDebugAdapterTest {

    @Test
    void metalsIsAskedWithItsDiscoveryParams() {
        assertEquals("debug-adapter-start", HostedDebugAdapter.startCommand("scala"));
        assertNull(HostedDebugAdapter.startCommand("java"), "jdtls keeps its own flow");
        assertEquals(
                Map.of("path", "file:///w/Main.scala", "runType", "run"),
                HostedDebugAdapter.discovery("file:///w/Main.scala", false, List.of(), List.of(), Map.of()));
        assertEquals(
                Map.of(
                        "path", "file:///w/MainSpec.scala",
                        "runType", "testFile",
                        "args", List.of("a"),
                        "jvmOptions", List.of("-Xmx1g"),
                        "env", Map.of("MODE", "dev")),
                HostedDebugAdapter.discovery(
                        "file:///w/MainSpec.scala", true, List.of("a"), List.of("-Xmx1g"), Map.of("MODE", "dev")));
    }

    @Test
    void theReplyNamesTheSocket() {
        assertEquals(
                new HostedDebugAdapter.Address("hello", "127.0.0.1", 41235),
                HostedDebugAdapter.address(
                        JsonParser.parseString("{\"name\":\"hello\",\"uri\":\"tcp://127.0.0.1:41235\"}")));
        assertEquals(
                new HostedDebugAdapter.Address("x", "127.0.0.1", 5005),
                HostedDebugAdapter.address(Map.of("name", "x", "uri", "tcp://0.0.0.0:5005")),
                "a reply decoded to a map; the any-address means this machine");
        assertEquals(new HostedDebugAdapter.Address("", "127.0.0.1", 6001), HostedDebugAdapter.address(6001));
        assertNull(HostedDebugAdapter.address(JsonParser.parseString("{\"name\":\"n\"}")));
        assertNull(HostedDebugAdapter.address(JsonParser.parseString("{\"uri\":\"tcp://host\"}")));
        assertNull(HostedDebugAdapter.address(null));
    }

    @Test
    void aScriptFallsBackToEditorasOwnAdapter() {
        assertEquals(DapServerRegistry.Kind.LANGUAGE_SERVER, DapServerRegistry.kindFor("scala"));
        assertEquals(DapServerRegistry.Kind.JVM, DapServerRegistry.fallbackKind("scala"));
        assertNull(DapServerRegistry.fallbackKind("java"));
    }
}
