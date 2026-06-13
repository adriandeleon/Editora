package com.editora.config;

import java.util.List;

import com.editora.ui.MainController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies read-only ("View mode") persistence and the open-read-only decision. */
class WorkspaceStateReadOnlyTest {

    @Test
    void readOnlyFilesRoundTripThroughJson() throws Exception {
        WorkspaceState state = new WorkspaceState();
        state.getReadOnlyFiles().add("/tmp/a.txt");
        state.getReadOnlyFiles().add("/tmp/b.md");

        ObjectMapper mapper = new ObjectMapper();
        WorkspaceState back = mapper.readValue(mapper.writeValueAsString(state), WorkspaceState.class);

        List<String> files = back.getReadOnlyFiles();
        assertEquals(2, files.size());
        assertTrue(files.contains("/tmp/a.txt"));
        assertTrue(files.contains("/tmp/b.md"));
    }

    @Test
    void shouldOpenReadOnlyTruthTable() {
        assertTrue(MainController.shouldOpenReadOnly(true, true)); // user-pinned, writable on disk
        assertTrue(MainController.shouldOpenReadOnly(true, false)); // user-pinned and not writable
        assertTrue(MainController.shouldOpenReadOnly(false, false)); // not pinned but read-only on disk
        assertFalse(MainController.shouldOpenReadOnly(false, true)); // normal, editable file
    }
}
