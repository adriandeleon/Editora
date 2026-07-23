package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The no-prepare fallback placeholder: the identifier run around the caret (#676). */
class LspRenameWordAtTest {

    @Test
    void extractsTheIdentifierAroundTheCaret() {
        assertEquals("userName", LspCoordinator.wordAt("int userName = 1;", 6));
        assertEquals("userName", LspCoordinator.wordAt("int userName = 1;", 4)); // at its start
        assertEquals("userName", LspCoordinator.wordAt("int userName = 1;", 12)); // at its end
        assertEquals("", LspCoordinator.wordAt("a + b", 2)); // on the operator
        assertEquals("", LspCoordinator.wordAt("", 0));
        assertEquals("x", LspCoordinator.wordAt("x", 5)); // column clamped
    }
}
