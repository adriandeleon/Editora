package com.editora.build;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure coverage of parsing package.json into scripts + the packageManager field. */
class NpmProjectTest {

    @Test
    void extractsScriptsInOrderAndThePackageManagerField() {
        String json = """
                {
                  "name": "demo",
                  "packageManager": "pnpm@8.6.0",
                  "scripts": { "build": "tsc", "test": "vitest", "lint": "eslint ." }
                }
                """;
        NpmProject p = NpmProject.parse(json);
        assertEquals("demo", p.name());
        assertEquals(List.of("build", "test", "lint"), p.scripts());
        assertEquals("pnpm@8.6.0", p.packageManagerField());
    }

    @Test
    void missingScriptsAndPackageManagerAreEmpty() {
        NpmProject p = NpmProject.parse("{ \"name\": \"x\" }");
        assertEquals("x", p.name());
        assertTrue(p.scripts().isEmpty());
        assertNull(p.packageManagerField());
    }

    @Test
    void missingNameIsNull() {
        NpmProject p = NpmProject.parse("{ \"scripts\": { \"build\": \"tsc\" } }");
        assertNull(p.name());
        assertEquals(List.of("build"), p.scripts());
    }

    @Test
    void malformedJsonThrows() {
        assertThrows(IllegalArgumentException.class, () -> NpmProject.parse("{ not json"));
    }

    @Test
    void aByteOrderMarkIsNotMalformedJson() {
        // npm itself accepts a package.json saved with a BOM (Visual Studio, PowerShell 5).
        NpmProject p = NpmProject.parse("\uFEFF{ \"name\": \"x\", \"scripts\": { \"hello\": \"echo hi\" } }");
        assertEquals("x", p.name());
        assertEquals(List.of("hello"), p.scripts());
    }

    @Test
    void keepsScriptCommandsWorkspacesAndWhetherItHasDependencies() {
        NpmProject p = NpmProject.parse("{\"scripts\":{\"test\":\"node --test\",\"x\":1},\"workspaces\":[\"a\"],"
                + "\"devDependencies\":{\"typescript\":\"^6\"}}");
        assertEquals("node --test", p.commandOf("test"));
        assertEquals("", p.commandOf("x"));
        assertEquals("", p.commandOf("missing"));
        assertTrue(p.workspaces());
        assertTrue(p.hasDependencies());
        assertTrue(!NpmProject.parse("{\"dependencies\":{}}").hasDependencies());
    }

    @Test
    void aMalformedFileSaysWhere() {
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> NpmProject.parse("{\n  \"scripts\": { \"x\": "));
        assertTrue(e.getMessage().startsWith("line 2, column"), e.getMessage());
    }
}
