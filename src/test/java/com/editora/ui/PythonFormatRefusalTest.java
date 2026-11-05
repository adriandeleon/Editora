package com.editora.ui;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Why Ruff/Black cannot format a buffer now (Python review 2, A112): each case used to say "No formatter available
 * for this file", even with Ruff installed.
 */
class PythonFormatRefusalTest {

    @Test
    void eachRefusalSaysWhatToDo() {
        Path local = Path.of("app.py").toAbsolutePath();
        assertNull(PythonFormatCoordinator.refusal(local, false, true));
        assertNull(PythonFormatCoordinator.refusal(null, false, true), "an untitled buffer formats too");
        assertEquals("status.python.formatNarrowed", PythonFormatCoordinator.refusal(local, true, true));
        assertEquals("status.lsp.readOnly", PythonFormatCoordinator.refusal(local, false, false));
    }
}
