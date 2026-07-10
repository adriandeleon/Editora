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
}
