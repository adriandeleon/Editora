package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link DebugCoordinator#offersOwnDebugFile}: which files the editor menu gives a Debug File entry of its own. */
class DebugFileEntryTest {

    @Test
    void goAndRustFilesGetOneSinceRunFileCannotRunThem() {
        assertTrue(DebugCoordinator.offersOwnDebugFile("go", false, false));
        assertTrue(DebugCoordinator.offersOwnDebugFile("rust", false, false));
    }

    @Test
    void aClassicJavaMainClassGetsOneOnlyOutsideAProject() {
        assertTrue(DebugCoordinator.offersOwnDebugFile("java", false, false));
        assertFalse(DebugCoordinator.offersOwnDebugFile("java", false, true), "a project's class: Debug Main Class");
    }

    @Test
    void aRunnableFileHasDebugFileBesideRunFileInstead() {
        assertFalse(DebugCoordinator.offersOwnDebugFile("go", true, false));
        assertFalse(DebugCoordinator.offersOwnDebugFile("rust", true, false));
        assertFalse(DebugCoordinator.offersOwnDebugFile("java", true, false));
    }

    @Test
    void otherLanguagesGetNone() {
        assertFalse(DebugCoordinator.offersOwnDebugFile("python", false, false));
        assertFalse(DebugCoordinator.offersOwnDebugFile("javascript", false, false));
        assertFalse(DebugCoordinator.offersOwnDebugFile("kotlin", false, false));
    }
}
