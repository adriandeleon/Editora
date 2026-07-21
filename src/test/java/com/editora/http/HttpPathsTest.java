package com.editora.http;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Unit tests for the {@code .http} file-reference containment rule. */
class HttpPathsTest {

    private static final Path BASE = Path.of("/home/u/repo/api");

    @Test
    void resolvesAPlainRelativeReference() {
        assertEquals(BASE.resolve("body.json"), HttpPaths.contained(BASE, "body.json"));
        assertEquals(BASE.resolve("body.json"), HttpPaths.contained(BASE, "./body.json"));
    }

    @Test
    void resolvesADeeperReferenceInsideTheFolder() {
        assertEquals(BASE.resolve("fixtures/big.json"), HttpPaths.contained(BASE, "fixtures/big.json"));
    }

    @Test
    void allowsAClimbThatLandsBackInside() {
        assertEquals(BASE.resolve("body.json"), HttpPaths.contained(BASE, "fixtures/../body.json"));
    }

    @Test
    void refusesAnAbsolutePath() {
        assertNull(HttpPaths.contained(BASE, "/etc/passwd"));
    }

    @Test
    void refusesAParentClimb() {
        assertNull(HttpPaths.contained(BASE, "../../.ssh/id_rsa"));
        assertNull(HttpPaths.contained(BASE, "../secret.txt"));
    }

    @Test
    void refusesASiblingFolderWithACommonPrefix() {
        // "/home/u/repo/api-secrets" must not pass merely because its string starts with the base's
        assertNull(HttpPaths.contained(BASE, "../api-secrets/keys.json"));
    }

    @Test
    void refusesBlankAndNullInput() {
        assertNull(HttpPaths.contained(BASE, null));
        assertNull(HttpPaths.contained(BASE, ""));
        assertNull(HttpPaths.contained(BASE, "   "));
        assertNull(HttpPaths.contained(null, "body.json"));
    }

    @Test
    void treatsTheBaseFolderItselfAsContained() {
        assertEquals(BASE, HttpPaths.contained(BASE, "."));
    }
}
