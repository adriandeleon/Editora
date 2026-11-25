package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link BreakpointMenus#offersOwnDebugFile}: which files the editor menu gives a Debug File entry of its own. */
class DebugFileEntryTest {

    @Test
    void goAndRustFilesGetOneSinceRunFileCannotRunThem() {
        assertTrue(BreakpointMenus.offersOwnDebugFile("go", false, false));
        assertTrue(BreakpointMenus.offersOwnDebugFile("rust", false, false));
        for (String language : java.util.List.of("c", "cpp", "csharp", "ruby", "php")) {
            assertTrue(BreakpointMenus.offersOwnDebugFile(language, false, false), language);
        }
        assertFalse(BreakpointMenus.offersOwnDebugFile("python", false, false), "Run File runs it");
    }

    @Test
    void aClassicJavaMainClassGetsOneOnlyOutsideAProject() {
        assertTrue(BreakpointMenus.offersOwnDebugFile("java", false, false));
        assertFalse(BreakpointMenus.offersOwnDebugFile("java", false, true), "a project's class: Debug Main Class");
    }

    @Test
    void aRunnableFileHasDebugFileBesideRunFileInstead() {
        assertFalse(BreakpointMenus.offersOwnDebugFile("go", true, false));
        assertFalse(BreakpointMenus.offersOwnDebugFile("rust", true, false));
        assertFalse(BreakpointMenus.offersOwnDebugFile("java", true, false));
    }

    @Test
    void otherLanguagesGetNone() {
        assertFalse(BreakpointMenus.offersOwnDebugFile("python", false, false));
        assertFalse(BreakpointMenus.offersOwnDebugFile("javascript", false, false));
        assertFalse(BreakpointMenus.offersOwnDebugFile("kotlin", false, false));
    }
}
