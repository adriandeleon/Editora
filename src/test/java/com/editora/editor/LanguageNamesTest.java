package com.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Language ids as people read them in the status bar and the Problems window. */
class LanguageNamesTest {

    @Test
    void multiWordAndOddlyCasedNamesReadNaturally() {
        // The status bar used to show "Pip-requirements".
        assertEquals("pip requirements", LanguageNames.display(LanguageRegistry.forFileName("requirements.txt")));
        assertEquals("JavaScript (JSX)", LanguageNames.display("javascriptreact"));
        assertEquals("TypeScript", LanguageNames.display("typescript"));
        assertEquals("Python", LanguageNames.display("python"));
        assertEquals("", LanguageNames.display(null));
    }
}
