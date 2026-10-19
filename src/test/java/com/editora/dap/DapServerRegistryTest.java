package com.editora.dap;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DapServerRegistryTest {

    @Test
    void languageIdsForDebugAreJavaPythonJavascriptGroovyAndScala() {
        assertEquals(
                Set.of("java", "python", "javascript", "groovy", "scala"), DapServerRegistry.languageIdsForDebug());
    }

    @Test
    void groovyIsDebuggedByEditorasOwnJvmAdapter() {
        DapServerRegistry.DapServerSpec spec = DapServerRegistry.specFor("groovy");
        assertEquals(DapServerRegistry.Kind.JVM, spec.kind());
        assertEquals(DapServerRegistry.JVM_LAUNCH_TYPE, spec.launchType());
        assertEquals(List.of(), spec.adapterArgs(), "nothing to start: it runs in-process");
    }

    @Test
    void isDebuggableMatchesRegisteredLanguages() {
        assertTrue(DapServerRegistry.isDebuggable("java"));
        assertTrue(DapServerRegistry.isDebuggable("python"));
        assertTrue(DapServerRegistry.isDebuggable("javascript"));
        assertTrue(DapServerRegistry.isDebuggable("groovy"));
        assertTrue(DapServerRegistry.isDebuggable("typescript")); // review D2: Node runs .ts, source maps
        assertTrue(DapServerRegistry.isDebuggable("typescriptreact"));
        assertFalse(DapServerRegistry.isDebuggable("ruby"));
        assertFalse(DapServerRegistry.isDebuggable(null));
    }

    @Test
    void kindForEachLanguage() {
        assertEquals(DapServerRegistry.Kind.JDTLS, DapServerRegistry.kindFor("java"));
        assertEquals(DapServerRegistry.Kind.STDIO, DapServerRegistry.kindFor("python"));
        assertEquals(DapServerRegistry.Kind.SOCKET, DapServerRegistry.kindFor("javascript"));
        assertNull(DapServerRegistry.kindFor("go"));
    }

    @Test
    void specForPython() {
        DapServerRegistry.DapServerSpec s = DapServerRegistry.specFor("python");
        assertEquals("python", s.language());
        assertEquals(DapServerRegistry.Kind.STDIO, s.kind());
        assertEquals("python", s.launchType());
        assertEquals("python", s.adapterId());
        assertEquals("python3", s.defaultInterpreter());
        assertEquals(List.of("-m", "debugpy.adapter"), s.adapterArgs());
    }

    @Test
    void specForJavascript() {
        DapServerRegistry.DapServerSpec s = DapServerRegistry.specFor("javascript");
        assertEquals(DapServerRegistry.Kind.SOCKET, s.kind());
        assertEquals("pwa-node", s.launchType());
        assertEquals("pwa-node", s.adapterId());
        assertEquals("node", s.defaultInterpreter());
        assertTrue(s.adapterArgs().isEmpty());
    }

    @Test
    void typescriptAndJsxShareTheJavascriptAdapter() {
        assertEquals("javascript", DapServerRegistry.adapterLanguage("typescript"));
        assertEquals("javascript", DapServerRegistry.adapterLanguage("javascriptreact"));
        assertEquals("javascript", DapServerRegistry.specFor("typescript").language());
        assertEquals("python", DapServerRegistry.adapterLanguage("python"));
        assertEquals("ruby", DapServerRegistry.adapterLanguage("ruby"));
        assertNull(DapServerRegistry.adapterLanguage(null));
    }

    @Test
    void specForUnsupportedIsNull() {
        assertNull(DapServerRegistry.specFor("ruby"));
    }

    @Test
    void interpreterArgvUsesDefaultWhenBlank() {
        assertEquals(List.of("python3"), DapServerRegistry.interpreterArgv("python", ""));
        assertEquals(List.of("python3"), DapServerRegistry.interpreterArgv("python", null));
        assertEquals(List.of("node"), DapServerRegistry.interpreterArgv("javascript", "  "));
    }

    @Test
    void interpreterArgvUsesConfiguredCommandTokenized() {
        assertEquals(
                List.of("/opt/py3/bin/python"), DapServerRegistry.interpreterArgv("python", "/opt/py3/bin/python"));
        assertEquals(
                List.of("/path with space/python"),
                DapServerRegistry.interpreterArgv("python", "\"/path with space/python\""));
    }

    @Test
    void interpreterArgvEmptyForJavaAndUnsupported() {
        // java has no standalone interpreter (started via jdtls).
        assertTrue(DapServerRegistry.interpreterArgv("java", "").isEmpty());
        assertTrue(DapServerRegistry.interpreterArgv("ruby", "ruby").isEmpty());
    }
}
